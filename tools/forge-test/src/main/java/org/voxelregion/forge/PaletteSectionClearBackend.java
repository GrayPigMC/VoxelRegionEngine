/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;

/**
 * Palette-proven bulk replacement of AIR, opaque static blocks and vanilla non-emitting water.
 * Preparation costs O(palette), final validation O(sections), light checks O(exterior surface).
 * Batches containing AIR or water additionally invalidate native sky-source/shadow interfaces.
 * A batch rebuilds chunk heightmaps and sky-source columns once and requests one chunk packet.
 * The caller owns geometric coverage, claim checks, budget reservation, receipts and chunk tickets.
 *
 * <p>This path deliberately does not issue 4096 native block callbacks or blockChanged messages.
 * Its host must explicitly support aggregated observers. Unknown integrations use the native or
 * {@link StaticSectionClearBackend} path. Physics policy is NONE; propagation is separate.</p>
 */
public final class PaletteSectionClearBackend {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Set<Heightmap.Types> HEIGHTMAPS = Collections.unmodifiableSet(EnumSet.of(
            Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
    private static final Set<BlockState> STATIC_STATES = staticStates();

    /** Full mutation tracking and aggregate observer support are required, not inferred from mod IDs. */
    public interface HostHooks {
        boolean supportsBulk(ServerLevel level);

        /** Nonnegative only when every relevant storage mutation is tracked, including native callbacks. */
        long mutationRevision(LevelChunk chunk);

        /** Must increment the tracked revision exactly once without editing terrain or calling observers. */
        void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY);

        void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY);

        /** The previous palette is an unordered proof set, not a per-position snapshot. */
        default void afterSectionCleared(ServerLevel level, LevelChunk chunk, Prepared previous) {}
    }

    public enum Rejection {
        NONE, WRONG_THREAD, WRONG_CHUNK, INVALID_SECTION, HOST_UNSUPPORTED, TRACKING_UNAVAILABLE,
        CAPTURING_SNAPSHOTS, UNSUPPORTED_STATE, PROTECTED_STATE, NOT_FULL, BLOCK_ENTITY,
        POST_PROCESSING, STALE
    }

    public enum CommitResult { APPLIED_QUEUED, ALREADY_CONSUMED, STALE, PERMISSION_DENIED, HOST_UNSUPPORTED }

    public record Preparation(Prepared prepared, Rejection rejection) {
        public boolean accepted() { return prepared != null; }
    }

    public record CommitReport(CommitResult result, int changedBlocks, boolean needsChunkPacket,
                               CompletableFuture<Void> lightWork) {
        public boolean applied() { return result == CommitResult.APPLIED_QUEUED; }
    }

    public static final class Prepared {
        private final ServerLevel level;
        private final LevelChunk chunk;
        private final int sectionIndex;
        private final int sectionY;
        private final LevelChunkSection original;
        private final long revision;
        private final int changedBlocks;
        private final Set<BlockState> palette;
        private final boolean replaySkySources;
        private final Predicate<BlockState> mayClear;
        private final HostHooks hooks;
        private boolean consumed;
        private boolean observerActive;

        private Prepared(ServerLevel level, LevelChunk chunk, int sectionIndex, long revision, int changedBlocks,
                         Set<BlockState> palette, Predicate<BlockState> mayClear, HostHooks hooks) {
            this.level = level;
            this.chunk = chunk;
            this.sectionIndex = sectionIndex;
            this.sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            this.original = chunk.getSection(sectionIndex);
            this.revision = revision;
            this.changedBlocks = changedBlocks;
            this.palette = Collections.unmodifiableSet(palette);
            // A full non-air water section is transmissive too; count==4096 is not a proof
            // of an initially black field. Preserve the interface repair for every water batch.
            this.replaySkySources = changedBlocks != 4096 || palette.stream().anyMatch(state -> state.is(Blocks.WATER));
            this.mayClear = mayClear;
            this.hooks = hooks;
        }

        public int sectionIndex() { return sectionIndex; }
        public int sectionY() { return sectionY; }
        public int changedBlocks() { return changedBlocks; }
        public long revision() { return revision; }
        public Set<BlockState> palette() { return palette; }
        /**
         * On-demand old state for audited watchers. Read synchronously inside afterSectionCleared
         * only; retaining this plan for later/off-thread queries is forbidden. No eager copy is made.
         */
        public BlockState previousState(int localX, int localY, int localZ) {
            if (!observerActive || !level.getServer().isSameThread())
                throw new IllegalStateException("Old section states may only be read synchronously during the commit observer");
            if ((localX | localY | localZ) < 0 || localX > 15 || localY > 15 || localZ > 15)
                throw new IndexOutOfBoundsException("Section-local coordinates must be 0..15");
            return original.getBlockState(localX, localY, localZ);
        }
        public BlockPos minimumPosition() {
            return new BlockPos(chunk.getPos().getMinBlockX(), sectionY << 4, chunk.getPos().getMinBlockZ());
        }
    }

    public static Preparation prepare(ServerLevel level, LevelChunk chunk, int sectionIndex,
                                      Predicate<BlockState> mayClear, HostHooks hooks) {
        Objects.requireNonNull(level); Objects.requireNonNull(chunk);
        Objects.requireNonNull(mayClear); Objects.requireNonNull(hooks);
        if (!level.getServer().isSameThread()) return rejected(Rejection.WRONG_THREAD);
        if (!currentChunk(level, chunk)) return rejected(Rejection.WRONG_CHUNK);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return rejected(Rejection.INVALID_SECTION);
        if (level.captureBlockSnapshots) return rejected(Rejection.CAPTURING_SNAPSHOTS);
        if (!hooks.supportsBulk(level)) return rejected(Rejection.HOST_UNSUPPORTED);
        long revision = hooks.mutationRevision(chunk);
        if (revision < 0 || revision == Long.MAX_VALUE) return rejected(Rejection.TRACKING_UNAVAILABLE);
        Rejection auxiliary = auxiliaryState(chunk, sectionIndex);
        if (auxiliary != Rejection.NONE) return rejected(auxiliary);
        LevelChunkSection original = chunk.getSection(sectionIndex);
        if (!(original instanceof NativeAccess.SectionStats stats)
                || stats.vre$accurateNonAirCount() < 1 || stats.vre$accurateNonAirCount() > 4096)
            return rejected(Rejection.NOT_FULL);
        int changedBlocks = stats.vre$accurateNonAirCount();
        BlockPos position = new BlockPos(chunk.getPos().getMinBlockX(), chunk.getSectionYFromSectionIndex(sectionIndex) << 4,
                chunk.getPos().getMinBlockZ());
        Set<BlockState> palette = Collections.newSetFromMap(new IdentityHashMap<>());
        Rejection[] rejection = { Rejection.UNSUPPORTED_STATE };
        boolean unsafe = original.maybeHas(state -> {
            // Only the three exact vanilla air states are equivalent empty terrain. Arbitrary
            // modded isAir() states may have callbacks and are not covered by this proof.
            if (isVanillaAir(state)) return false;
            if (!isSupportedState(state, level, position)) return true;
            if (!mayClear.test(state)) { rejection[0] = Rejection.PROTECTED_STATE; return true; }
            palette.add(state);
            return false;
        });
        if (unsafe || palette.isEmpty()) return rejected(rejection[0]);
        // maybeHas includes unused entries; unsupported ones conservatively reject the fast path.
        if (hooks.mutationRevision(chunk) != revision || chunk.getSection(sectionIndex) != original
                || stats.vre$accurateNonAirCount() != changedBlocks)
            return rejected(Rejection.STALE);
        return new Preparation(new Prepared(level, chunk, sectionIndex, revision, changedBlocks, palette, mayClear, hooks), Rejection.NONE);
    }

    public static CommitReport commit(Prepared prepared, BooleanSupplier finalPermission) {
        return commitBatch(List.of(prepared), finalPermission);
    }

    /**
     * Commits same-chunk plans made from one revision. All policies and identities are checked before
     * the first palette swap. A post-swap failure is explicitly non-replayable. The returned light
     * future is separate from the caller's disk save acknowledgement and client packet flush.
     */
    public static CommitReport commitBatch(List<Prepared> input, BooleanSupplier finalPermission) {
        List<Prepared> plans = List.copyOf(input);
        if (plans.isEmpty()) throw new IllegalArgumentException("No sections to commit");
        Objects.requireNonNull(finalPermission);
        Prepared first = plans.get(0);
        ServerLevel level = first.level;
        LevelChunk chunk = first.chunk;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Bulk commit requires the server thread");
        Set<Integer> sectionIndices = new HashSet<>();
        for (Prepared plan : plans) {
            if (plan.level != level || plan.chunk != chunk || plan.revision != first.revision || plan.hooks != first.hooks
                    || !sectionIndices.add(plan.sectionIndex))
                throw new IllegalArgumentException("Batch must contain unique sections from one chunk, revision and host");
            if (plan.consumed) return notApplied(CommitResult.ALREADY_CONSUMED);
        }
        plans.forEach(plan -> plan.consumed = true);
        if (!currentChunk(level, chunk) || level.captureBlockSnapshots) return notApplied(CommitResult.STALE);
        HostHooks hooks = first.hooks;
        if (!hooks.supportsBulk(level)) return notApplied(CommitResult.HOST_UNSUPPORTED);
        for (Prepared plan : plans) for (BlockState state : plan.palette)
            if (!isSupportedState(state, level, plan.minimumPosition()) || !plan.mayClear.test(state))
                return notApplied(CommitResult.STALE);
        if (hooks.mutationRevision(chunk) != first.revision) return notApplied(CommitResult.STALE);
        if (first.revision > Long.MAX_VALUE - plans.size()) return notApplied(CommitResult.STALE);
        // Trusted epoch-only hooks run before the final claims callback; any native terrain callback
        // adds extra revision changes and will fail the exact revision check below.
        for (Prepared plan : plans) hooks.beforePaletteReplaced(level, chunk, plan.sectionY);
        if (!finalPermission.getAsBoolean()) return notApplied(CommitResult.PERMISSION_DENIED);
        if (!currentChunk(level, chunk) || level.captureBlockSnapshots
                || hooks.mutationRevision(chunk) != first.revision + plans.size()) return notApplied(CommitResult.STALE);
        for (Prepared plan : plans)
            if (chunk.getSection(plan.sectionIndex) != plan.original
                    || ((NativeAccess.SectionStats) plan.original).vre$accurateNonAirCount() != plan.changedBlocks
                    || auxiliaryState(chunk, plan.sectionIndex) != Rejection.NONE) return notApplied(CommitResult.STALE);

        // Allocate before mutating, so allocation/constructor failure cannot leave a partially swapped batch.
        List<LevelChunkSection> replacements = new ArrayList<>(plans.size());
        for (Prepared plan : plans)
            replacements.add(new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, AIR,
                    PalettedContainer.Strategy.SECTION_STATES), plan.original.getBiomes()));
        chunk.setUnsaved(true);
        for (int i = 0; i < plans.size(); i++) chunk.getSections()[plans.get(i).sectionIndex] = replacements.get(i);
        try {
            for (Prepared plan : plans) hooks.afterPaletteReplaced(level, chunk, plan.sectionY);
            for (Heightmap.Types type : HEIGHTMAPS) chunk.setHeightmap(type, new Heightmap(chunk, type).getRawData());
            Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
            chunk.initializeLightSources();
            var lighting = level.getChunkSource().getLightEngine();
            for (Prepared plan : plans) {
                BlockPos minimum = plan.minimumPosition();
                BoundingBox area = new BoundingBox(minimum.getX(), minimum.getY(), minimum.getZ(),
                        minimum.getX() + 15, minimum.getY() + 15, minimum.getZ() + 15);
                level.getBlockTicks().clearArea(area);
                level.getFluidTicks().clearArea(area);
                lighting.updateSectionStatus(minimum, true);
            }
            CompletableFuture<Void> lightWork = ((NativeAccess.LightFence) lighting).vre$batchChecks(chunk.getPos(),
                    plans.stream().mapToInt(Prepared::sectionY).toArray(), plans.stream().anyMatch(plan -> plan.replaySkySources));
            updateNavigation(level, plans);
            for (Prepared plan : plans) {
                plan.observerActive = true;
                try { hooks.afterSectionCleared(level, chunk, plan); }
                finally { plan.observerActive = false; }
            }
            return new CommitReport(CommitResult.APPLIED_QUEUED, plans.stream().mapToInt(Prepared::changedBlocks).sum(), true, lightWork);
        } catch (RuntimeException | LinkageError failure) {
            throw new CommitFailure(plans.size(), failure);
        }
    }

    /** Aggregate native navigation checks; mixed cubes may conservatively recompute extra paths once. */
    private static void updateNavigation(ServerLevel level, List<Prepared> plans) {
        List<PathNavigation> affected = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof Mob mob)) continue;
            PathNavigation navigation = mob.getNavigation();
            var path = navigation.getPath();
            if (path == null || path.isDone() || path.getNodeCount() == 0) continue;
            var end = path.getEndNode();
            double centerX = (end.x + mob.getX()) / 2.0;
            double centerY = (end.y + mob.getY()) / 2.0;
            double centerZ = (end.z + mob.getZ()) / 2.0;
            for (Prepared plan : plans) {
                BlockPos minimum = plan.minimumPosition();
                // Native shouldRecomputePath tests distance from block centers to this midpoint.
                // The nearest lattice center minimizes that distance over every block in the cube.
                BlockPos nearest = new BlockPos(Mth.clamp(Mth.floor(centerX), minimum.getX(), minimum.getX() + 15),
                        Mth.clamp(Mth.floor(centerY), minimum.getY(), minimum.getY() + 15),
                        Mth.clamp(Mth.floor(centerZ), minimum.getZ(), minimum.getZ() + 15));
                if (navigation.shouldRecomputePath(nearest)) { affected.add(navigation); break; }
            }
        }
        for (PathNavigation navigation : affected) navigation.recomputePath();
    }

    public static final class CommitFailure extends RuntimeException {
        private final int appliedSections;
        private CommitFailure(int appliedSections, Throwable cause) {
            super("Palette batch applied but derived bookkeeping failed; do not replay", cause);
            this.appliedSections = appliedSections;
        }
        public int appliedSections() { return appliedSections; }
        public boolean terrainChanged() { return true; }
    }

    private static CommitReport notApplied(CommitResult result) {
        return new CommitReport(result, 0, false, CompletableFuture.completedFuture(null));
    }

    private static Preparation rejected(Rejection rejection) { return new Preparation(null, rejection); }

    private static boolean currentChunk(ServerLevel level, LevelChunk chunk) {
        return chunk.getLevel() == level && level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk;
    }

    private static Rejection auxiliaryState(LevelChunk chunk, int sectionIndex) {
        int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
        for (BlockPos position : chunk.getBlockEntitiesPos())
            if ((position.getY() >> 4) == sectionY) return Rejection.BLOCK_ENTITY;
        var postProcessing = chunk.getPostProcessing()[sectionIndex];
        return postProcessing != null && !postProcessing.isEmpty() ? Rejection.POST_PROCESSING : Rejection.NONE;
    }

    private static boolean isSupportedState(BlockState state, ServerLevel level, BlockPos position) {
        // Exact vanilla identity excludes mod fluids, waterlogged solids and lava emission. Water's
        // inherited onRemove has no fluid interaction callback; the committed region's pending
        // fluid ticks are cleared explicitly. Host permission still runs for every palette state,
        // so clearFluids=false and protected tags cannot be bypassed by this fast path.
        if (state.is(Blocks.WATER) && state.getBlock().getClass() == LiquidBlock.class) {
            var fluid = state.getFluidState();
            return (fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER)
                    && !state.hasBlockEntity() && !state.isRandomlyTicking() && PoiTypes.forState(state).isEmpty()
                    && state.getLightEmission(level, position) == 0 && state.getLightBlock(level, position) == 1;
        }
        return STATIC_STATES.contains(state) && state.getBlock().getClass() == Block.class
                && state == state.getBlock().defaultBlockState() && state.getValues().isEmpty()
                && !state.hasBlockEntity() && state.getFluidState().isEmpty() && !state.isRandomlyTicking()
                && PoiTypes.forState(state).isEmpty() && state.getLightEmission(level, position) == 0
                && state.getLightBlock(level, position) == 15 && Block.isShapeFullBlock(state.getOcclusionShape(level, position));
    }

    private static boolean isVanillaAir(BlockState state) {
        return state == AIR || state == Blocks.CAVE_AIR.defaultBlockState() || state == Blocks.VOID_AIR.defaultBlockState();
    }

    private static Set<BlockState> staticStates() {
        Set<BlockState> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Block block : new Block[] { Blocks.STONE, Blocks.GRANITE, Blocks.POLISHED_GRANITE,
                Blocks.DIORITE, Blocks.POLISHED_DIORITE, Blocks.ANDESITE, Blocks.POLISHED_ANDESITE,
                Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.COBBLESTONE, Blocks.END_STONE,
                Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.COBBLED_DEEPSLATE })
            result.add(block.defaultBlockState());
        return Collections.unmodifiableSet(result);
    }

    private PaletteSectionClearBackend() {}
}
