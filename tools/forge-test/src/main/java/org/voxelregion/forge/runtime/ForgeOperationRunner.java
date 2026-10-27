/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.spatial.ChunkKey;
import org.voxelregion.spatial.SectionKey;
import org.voxelregion.geometry.Volume;
import org.voxelregion.spatial.ChunkCursor;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.PaletteSectionClearBackend;
import org.voxelregion.forge.ChunkPacketQueue;
import org.voxelregion.forge.HostIntegration;
import org.voxelregion.forge.GenerationGate;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.propagation.FrozenOcclusion;
import net.minecraftforge.fml.ModList;

/** Bounded server-thread execution with palette batches and durable native completion fences. */
final class ForgeOperationRunner {
    private static final TicketType<UUID> TICKET = TicketType.create("voxelregion_test", Comparator.<UUID>naturalOrder());
    private static final TagKey<Block> PROTECTED = TagKey.create(Registries.BLOCK, new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "protected"));
    private static final Map<UUID, HeldChunk> HELD = new HashMap<>();
    private static final Map<UUID, EntityWork> ENTITIES = new HashMap<>();
    private static final Map<UUID, CompletableFuture<Void>> COMMITTING = new HashMap<>();
    private static final Map<UUID, java.util.ArrayDeque<PendingCommit>> IN_FLIGHT = new HashMap<>();
    private static final Map<UUID, CompletableFuture<GenerationGate.Registration>> GENERATING = new HashMap<>();
    private static final int MAX_ENTITY_CANDIDATES = 16_384;
    private static int roundRobin;
    private static boolean executingStep;
    static boolean executingStep() { return executingStep; }
    private static final PaletteSectionClearBackend.HostHooks BULK_HOOKS = new PaletteSectionClearBackend.HostHooks() {
        @Override public boolean supportsBulk(ServerLevel level) {
            // Embedding hosts with additional block/section watchers must register an audited hook implementation.
            return HostIntegration.supportsBulk(level);
        }
        @Override public long mutationRevision(LevelChunk chunk) { return ChunkMutationTracking.revision(chunk); }
        @Override public void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) { ChunkMutationTracking.changing(chunk); }
        @Override public void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) { ChunkMutationTracking.changed(chunk); }
        @Override public void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
            HostIntegration.afterSectionCleared(level, chunk, previous);
        }
    };

    static OperationData.Task submit(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse, boolean generate) throws IOException {
        return submit(level, actor, center, radius, fuse, generate, true);
    }

    static OperationData.Task submit(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse,
                                     boolean generate, boolean clearFluids) throws IOException {
        return submitConfigured(level, actor, center, radius, fuse, generate, clearFluids, null);
    }

    static OperationData.Task submitGeometry(ServerLevel level, UUID actor, RegionGeometry geometry,
            org.voxelregion.forge.ForgeRegionEngine.Options options) throws IOException {
        IntBox b = geometry.volume().bounds();
        if (b.isEmpty()) throw new IllegalArgumentException("Empty operation geometry");
        BlockPos center = BlockPos.containing(((long)b.minX() + b.maxX()) / 2.0,
                ((long)b.minY() + b.maxY()) / 2.0, ((long)b.minZ() + b.maxZ()) / 2.0);
        double dx = Math.max(center.getX() + .5 - b.minX(), b.maxX() - center.getX() - .5);
        double dy = Math.max(center.getY() + .5 - b.minY(), b.maxY() - center.getY() - .5);
        double dz = Math.max(center.getZ() + .5 - b.minZ(), b.maxZ() - center.getZ() - .5);
        double radius = Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
        if (radius > AdapterConfig.MAX_RADIUS.get()) throw new IllegalArgumentException("Geometry enclosing radius exceeds server maximum");
        return submitConfigured(level, actor, center, Math.max(1, (int) radius), options.fuseTicks(),
                options.generateMissingChunks(), options.clearFluids(), geometry);
    }

    private static OperationData.Task submitConfigured(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse,
            boolean generate, boolean clearFluids, RegionGeometry geometry) throws IOException {
        if (radius < 1 || radius > AdapterConfig.MAX_RADIUS.get() || fuse < 0 || fuse > 1200)
            throw new IllegalArgumentException("Radius/fuse outside configured limits");
        OperationData data = OperationData.get(level);
        long pending = 0;
        for (ServerLevel world : level.getServer().getAllLevels())
            pending += OperationData.get(world).tasks.stream().filter(t -> !t.terminal()).count();
        if (pending >= AdapterConfig.MAX_OPERATIONS.get()) throw new IllegalStateException("Maximum pending operations reached");
        var task = new OperationData.Task(UUID.randomUUID(), actor, center.getX() + .5, center.getY() + .5, center.getZ() + .5,
                radius, fuse, generate, clearFluids, AdapterConfig.RESISTANCE_LIMIT.get(), AdapterConfig.ENTITY_DAMAGE.get(),
                AdapterConfig.PROTECT_ALL_CLAIMS.get(), AdapterConfig.PHYSICS.get(), level.getMinSection());
        if (geometry != null) { task.geometry = geometry; task.volume = geometry.volume(); }
        task.owner = level;
        task.progression = AdapterConfig.PROGRESSION.get();
        task.propagation = AdapterConfig.PROPAGATION.get();
        task.occlusionCell = AdapterConfig.OCCLUSION_CELL.get();
        if (task.propagation != FrozenOcclusion.Mode.NONE && !java.util.Set.of(4,8,16).contains(task.occlusionCell))
            throw new IllegalArgumentException("Occlusion macro cells must be 4, 8 or 16");
        task.shellWidth = AdapterConfig.SHELL_WIDTH.get();
        if (task.volume.bounds().maxY() <= level.getMinBuildHeight() || task.volume.bounds().minY() >= level.getMaxBuildHeight())
            throw new IllegalArgumentException("Sphere does not intersect this dimension's build height");
        IntentJournal.append(level, task);
        data.tasks.removeIf(t -> t.terminal() && !IN_FLIGHT.containsKey(t.id) && !HELD.containsKey(t.id)
                && !GENERATING.containsKey(t.id) && data.tasks.size() > 64);
        data.tasks.add(task);
        data.setDirty();
        return task;
    }

    static void tick(MinecraftServer server) {
        tick(server, RuntimeBridge.sharedDeadline());
    }

    static void tick(MinecraftServer server, long deadline) {
        // Always drain submitted work, including paused, cancelled and failed tasks.
        for (ServerLevel level : server.getAllLevels()) {
            OperationData data = OperationData.get(level);
            for (var task : data.tasks) {
                drainCommits(level, data, task);
                if (pruneTerminalMemory(task)) data.setDirty();
            }
        }
        Budget budget = new Budget(deadline, AdapterConfig.VISITS_PER_TICK.get(), AdapterConfig.WRITES_PER_TICK.get());
        List<Work> work = new ArrayList<>();
        long metadata = estimatedMetadataBytes(server) + OperationOcclusion.estimatedBytes();
        for (ServerLevel level : server.getAllLevels()) {
            OperationData data = OperationData.get(level);
            for (OperationData.Task task : data.tasks) {
                if (task.state == OperationData.State.FUSE) {
                    if (task.fuse > 0) {
                        if (task.fuse % 10 == 0)
                            level.sendParticles(ParticleTypes.SMOKE, task.cx, task.cy, task.cz, 4, .1, .15, .1, .01);
                        task.fuse--;
                    } else task.state = OperationData.State.RUNNING;
                    data.setDirty();
                }
                if (task.state == OperationData.State.RUNNING || task.state == OperationData.State.RECOVERING
                        || task.state == OperationData.State.SETTLING) work.add(new Work(level, data, task));
            }
        }
        if (work.isEmpty()) return;
        if (metadata > AdapterConfig.MAX_PENDING_BYTES.get()) {
            for (Work item : work) pause(item.task, OperationData.State.PAUSED,
                    "Global operation metadata budget reached; cancel old operations or raise the configured cap.");
            return;
        }
        int idle = 0;
        while (budget.available() && idle < work.size()) {
            Work item = work.get(Math.floorMod(roundRobin++, work.size()));
            if (!item.task.active() || item.task.state == OperationData.State.FUSE) { idle++; continue; }
            try {
                executingStep = true;
                if (step(item, budget)) idle = 0; else idle++;
            } catch (RuntimeException ex) {
                item.task.state = OperationData.State.FAILED;
                item.task.message = "Native callback/adapter failed; no automatic retry: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                RuntimeBridge.LOGGER.error("Region operation {} failed at chunk {},{}", item.task.id, item.task.chunkX, item.task.chunkZ, ex);
                try { IntentJournal.checkpoint(item.level, item.task); } catch (RuntimeException journalFailure) { ex.addSuppressed(journalFailure); }
                OperationOcclusion.release(item.task.id);
                item.task.needsChunkPacket = true;
                try { settlePartial(item.task); }
                catch (RuntimeException cleanupFailure) { ex.addSuppressed(cleanupFailure); release(item.task.id); }
            } finally {
                executingStep = false;
            }
            item.data.setDirty();
        }
    }

    private static boolean step(Work work, Budget budget) {
        ServerLevel level = work.level;
        OperationData.Task task = work.task;
        for (var earlier : work.data.tasks) {
            if (earlier == task) break;
            if ((task.propagation != FrozenOcclusion.Mode.NONE || earlier.propagation != FrozenOcclusion.Mode.NONE)
                    && task.volume.bounds().intersects(earlier.volume.bounds())
                    && (!earlier.terminal() || IN_FLIGHT.containsKey(earlier.id) || HELD.containsKey(earlier.id))) {
                task.message = "Waiting for earlier overlapping propagation operation " + earlier.id
                        + " (including paused work and outstanding completion fences).";
                return false;
            }
        }
        if (task.state == OperationData.State.SETTLING) {
            if (task.unconfirmedFences != 0) return false;
            task.state = OperationData.State.RUNNING;
        }
        if (task.propagation != FrozenOcclusion.Mode.NONE && !task.occlusionReady) {
            var planning = OperationOcclusion.step(level, task, budget.visits, budget.deadline,
                    () -> hold(level, task), () -> releaseTicket(task.id));
            budget.visits -= Math.max(1, planning.visits());
            if (!planning.ready()) return planning.visits() > 0 || !task.active();
        }
        if (!task.entitiesHandled) return entityStep(level, task, budget);
        if (task.phase >= task.phaseCount()) {
            if (task.unconfirmedFences != 0) { task.state = OperationData.State.SETTLING; return false; }
            task.state = OperationData.State.WRITTEN;
            pruneTerminalMemory(task);
            task.message = "All requested phases completed; native lighting and terrain/POI persistence fences acknowledged."
                    + (task.skippedUnloaded == 0 ? "" : " Loaded-only mode excluded " + task.skippedUnloaded + " chunks.");
            IntentJournal.checkpoint(level, task);
            release(task.id);
            return true;
        }
        Volume currentVolume = task.phaseVolume(level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (task.phaseCursor == null) {
            if (task.restoredCursor != null) {
                task.phaseCursor = ChunkCursor.restore(currentVolume, level.getMinBuildHeight(), level.getMaxBuildHeight(), task.restoredCursor);
                task.replayOffset = task.cursorOffset; task.restoredCursor = null;
            } else task.phaseCursor = new ChunkCursor(currentVolume, level.getMinBuildHeight(), level.getMaxBuildHeight());
        }
        // Restore an iterator without an unbounded restart loop; no world reads/writes occur while replaying its geometry.
        if (task.replayOffset < task.cursorOffset) {
            budget.visits--;
            if (!task.phaseCursor.hasNext()) throw new IllegalStateException("Persisted chunk cursor is outside the phase");
            task.phaseCursor.next(); task.replayOffset++;
            return true;
        }
        if (!task.chunkSelected && task.phase == 0 && task.centralBootstrap && !task.centerChunkScheduled) {
            task.centerChunkScheduled = true;
            task.chunkX = Math.floorDiv((int)Math.floor(task.cx), 16); task.chunkZ = Math.floorDiv((int)Math.floor(task.cz), 16);
            task.chunkSelected = true; task.sectionY = level.getMinSection(); task.voxel = 0;
        }
        if (!task.chunkSelected) {
            budget.visits--;
            if (!task.phaseCursor.hasNext()) {
                if (task.unconfirmedFences != 0) {
                    task.state = OperationData.State.SETTLING;
                    task.message = "Waiting for all chunk fences in this phase: " + task.unconfirmedFences;
                    return false;
                }
                task.phase++; task.cursorOffset = 0; task.replayOffset = 0;
                task.phaseCursor = null; task.activeVolume = null;
                IntentJournal.checkpoint(level, task);
                return true;
            }
            ChunkKey next = task.phaseCursor.next(); task.cursorOffset++; task.replayOffset++;
            task.chunkX = next.x(); task.chunkZ = next.z(); task.chunkSelected = true;
            task.sectionY = level.getMinSection(); task.voxel = 0;
        }
        if (task.excludedChunks.contains(ChunkPos.asLong(task.chunkX, task.chunkZ))) {
            finishChunk(level, task);
            return true;
        }
        ClaimChecks.Decision permission = ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim);
        if (permission == ClaimChecks.Decision.UNAVAILABLE) {
            pause(task, OperationData.State.PAUSED_PROTECTION, "FTB protection is not ready or its API failed; no writes permitted.");
            return true;
        }
        if (permission == ClaimChecks.Decision.DENY) {
            skipDeniedChunk(level, task);
            return true;
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(task.chunkX, task.chunkZ);
        if (chunk == null) {
            if (!task.generate) {
                // Exclude an unloaded chunk rather than allowing a distant corner to prevent visible central work.
                if (!excludeChunk(level, task)) return false;
                task.skippedUnloaded++;
                finishChunk(level, task);
                return true;
            }
            if (retainedChunks() >= AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get() && !HELD.containsKey(task.id)) return false;
            var registration = GENERATING.get(task.id);
            if (registration == null) {
                registration = GenerationGate.register(level, task.id, task.intentHash(), new ChunkKey(task.chunkX, task.chunkZ),
                        currentVolume, task.propagation,
                        new GenerationGate.Rules(HostIntegration.supportsBulk(level), task.clearFluids, 0,
                                state -> !state.is(PROTECTED) && state.getBlock().getClass() == Block.class
                                        && state.getDestroySpeed(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 0
                                        && state.getBlock().getExplosionResistance() < task.resistance),
                        () -> task.active() && ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW);
                GENERATING.put(task.id, registration);
            }
            if (!registration.isDone()) { task.message = "Checking native chunk storage and generation safety."; return false; }
            var admitted = registration.join();
            if (!hold(level, task)) return false;
            task.message = "Waiting for native FULL generation/loading: " + admitted.safety().evidence();
            return false;
        }
        if (!hold(level, task)) return false;
        var generated = GENERATING.remove(task.id);
        if (generated != null) {
            var registration = generated.join();
            if (registration.result().isDone()) {
                var result = registration.result().join();
                task.generatedSkipped += result.skippedBlockWrites();
            }
            registration.close();
        }
        task.message = "";
        ChunkReceipts receipts = ChunkReceipts.of(chunk);
        if (task.chunkStarted) receipts.adoptLiveChanges(task.id, task.phase);
        if (!task.chunkStarted) {
            ChunkReceipts.Cursor cursor;
            var prior = receipts.checkpoint(task.id);
            if (!task.recovering && !task.resumeRequiresValidation && prior != null && prior.workingPhase() < 0 && prior.completedThroughPhase() < task.phase
                    && prior.state() == ChunkReceipts.STARTED) {
                cursor = receipts.beginLiveNextPhase(task.id, task.intentHash(), task.phase, level.getMinSection(), level.getMaxSection());
            } else if (receipts.state(task.id) != 0 || task.recovering || task.resumeRequiresValidation) {
                var recovery = receipts.resumeCursor(task.id, task.intentHash(), task.phase);
                switch (recovery.decision()) {
                    case SKIP_PHASE, STOP_TERMINAL -> { finishChunk(level, task); return true; }
                    case PAUSE_UNCERTAIN -> { pause(task, OperationData.State.PAUSED_RECOVERY, recovery.reason()); return true; }
                    case RESUME -> cursor = recovery.cursor();
                    case BEGIN_NEXT_PHASE -> cursor = receipts.beginPhase(task.id, task.intentHash(), task.phase, level.getMinSection(), level.getMaxSection());
                    default -> throw new IllegalStateException("Unknown recovery decision");
                }
            } else {
                cursor = receipts.beginPhase(task.id, task.intentHash(), task.phase, level.getMinSection(), level.getMaxSection());
            }
            task.sectionY = cursor.sectionY(); task.voxel = cursor.voxel();
            chunk.setUnsaved(true);
            task.chunkStarted = true; task.resumeRequiresValidation = false;
        }
        if (task.sectionY >= level.getMaxSection()) {
            receipts.completePhase(task.id, task.phase, task.phase + 1 == task.phaseCount());
            chunk.setUnsaved(true);
            enqueueCommit(task, chunk, true);
            finishChunk(level, task); // The submitted chunk's ticket now belongs to its fence.
            return true;
        }
        var section = chunk.getSection(level.getSectionIndexFromSectionY(task.sectionY));
        Coverage coverage = currentVolume.classify(new SectionKey(task.chunkX, task.sectionY, task.chunkZ).bounds());
        Coverage shadow = task.occlusion.classify(new SectionKey(task.chunkX, task.sectionY, task.chunkZ).bounds());
        if (shadow == Coverage.INSIDE) coverage = Coverage.OUTSIDE;
        else if (shadow == Coverage.PARTIAL && coverage != Coverage.OUTSIDE) coverage = Coverage.PARTIAL;
        if (coverage == Coverage.OUTSIDE || section.hasOnlyAir()) {
            task.sectionY++;
            task.voxel = 0;
            receipts.advanceCursor(task.id, task.phase, task.sectionY, 0);
            return true;
        }
        Explosion context = new Explosion(level, null, task.cx, task.cy, task.cz, task.radius, false, Explosion.BlockInteraction.KEEP);
        if (task.voxel == 0 && coverage == Coverage.INSIDE && task.physics == AdapterConfig.Physics.NONE
                && AdapterConfig.BULK_SECTIONS.get() && budget.writes >= 4096 && budget.visits >= 4096) {
            List<PaletteSectionClearBackend.Prepared> plans = new ArrayList<>();
            int maximum = Math.min(budget.writes / 4096, budget.visits / 4096);
            for (int sectionY = task.sectionY; sectionY < level.getMaxSection() && plans.size() < maximum; sectionY++) {
                if (System.nanoTime() >= budget.deadline || !chunkInsideBorder(level, chunk) || task.occlusion.classify(new SectionKey(task.chunkX, sectionY, task.chunkZ).bounds()) != Coverage.OUTSIDE || currentVolume.classify(new SectionKey(task.chunkX, sectionY, task.chunkZ).bounds()) != Coverage.INSIDE) break;
                BlockPos minimum = new BlockPos(task.chunkX << 4, sectionY << 4, task.chunkZ << 4);
                var preparation = PaletteSectionClearBackend.prepare(level, chunk, level.getSectionIndexFromSectionY(sectionY),
                        state -> !mustKeep(state, level, minimum, context, task.resistance, task.clearFluids), BULK_HOOKS);
                if (!preparation.accepted()) break;
                plans.add(preparation.prepared());
            }
            if (!plans.isEmpty()) {
                receipts.adoptLiveChanges(task.id, task.phase);
                receipts.beforeWrite(task.id, task.phase, task.sectionY, 0);
                var report = PaletteSectionClearBackend.commitBatch(plans, () -> task.active() && ClaimChecks.check(level,
                        task.chunkX, task.chunkZ, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW && chunkInsideBorder(level, chunk));
                if (report.applied()) {
                    // A later FIFO barrier cannot erase an earlier batch's exceptional completion.
                    task.queuedLightWork = CompletableFuture.allOf(task.queuedLightWork, report.lightWork());
                    task.changed += report.changedBlocks(); task.bulkSections += plans.size();
                    budget.writes -= report.changedBlocks(); budget.visits -= 4096 * plans.size();
                    task.visited += 4096L * plans.size(); task.sectionY += plans.size();
                    task.needsChunkPacket |= report.needsChunkPacket();
                    receipts.afterWrite(task.id, task.phase, task.sectionY, 0);
                    return true;
                }
                receipts.abortUnappliedWrite(task.id, task.phase);
            }
        }
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        if (coverage == Coverage.PARTIAL && (task.sectionSelection == null || task.selectionSectionY != task.sectionY)) {
            var bits = new java.util.BitSet(4096);
            int baseX = task.chunkX << 4, baseY = task.sectionY << 4, baseZ = task.chunkZ << 4;
            currentVolume.emitRanges(new SectionKey(task.chunkX, task.sectionY, task.chunkZ).bounds(), (x, z, from, to) -> {
                int column = (x - baseX) | ((z - baseZ) << 4);
                for (int y = from; y < to; y++) bits.set(column | ((y - baseY) << 8));
            });
            task.occlusion.shadowMask(new SectionKey(task.chunkX, task.sectionY, task.chunkZ)).forEachRanges((x, z, from, to) -> {
                int column = x | (z << 4);
                for (int y = from; y < to; y++) bits.clear(column | (y << 8));
            });
            task.sectionSelection = bits; task.selectionSectionY = task.sectionY;
        }
        int end = Math.min(4096, task.voxel + 64);
        while (task.active() && task.voxel < end && budget.available()) {
            if (coverage == Coverage.PARTIAL) {
                int selected = task.sectionSelection.nextSetBit(task.voxel);
                if (selected < 0) selected = 4096;
                if (selected != task.voxel) {
                    task.voxel = selected;
                    receipts.advanceCursor(task.id, task.phase, task.sectionY, selected);
                }
                if (selected >= end) break;
            }
            int index = task.voxel;
            int x = (task.chunkX << 4) + (index & 15);
            int y = (task.sectionY << 4) + (index >>> 8);
            int z = (task.chunkZ << 4) + ((index >>> 4) & 15);
            position.set(x, y, z);
            budget.visits--;
            task.visited++;
            if (y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()
                    && (coverage == Coverage.INSIDE || currentVolume.contains(x, y, z))
                    && level.getWorldBorder().isWithinBounds(position)) {
                BlockState state = chunk.getBlockState(position);
                if (!state.isAir()) {
                    if (mustKeep(state, level, position, context, task.resistance, task.clearFluids)) {
                        task.kept++;
                    } else {
                        // Resolve potentially mod-defined fluid methods before the final
                        // permission and identity checks, so their callbacks cannot invalidate them.
                        BlockState replacement = task.clearFluids ? Blocks.AIR.defaultBlockState()
                                : state.getFluidState().createLegacyBlock();
                        // Revalidate immediately before each actual write, including after arbitrary mod callbacks.
                        ClaimChecks.Decision current = ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim);
                        if (current != ClaimChecks.Decision.ALLOW) {
                            if (current == ClaimChecks.Decision.DENY) {
                                skipDeniedChunk(level, task);
                            } else pause(task, OperationData.State.PAUSED_PROTECTION, "Protection changed/unavailable during commit.");
                            return true;
                        }
                        // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE. Native chunk code maintains BE, POI,
                        // heightmaps, skylight sources, dirty state, callbacks and light checks.
                        // Neighbor physics is deliberately suppressed in this isolated baseline.
                        int flags = task.physics == AdapterConfig.Physics.COMPATIBLE ? Block.UPDATE_ALL
                                : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
                        // Contextual mod protection callbacks can themselves replace this state.
                        // Never apply the old state's decision to a newly placed protected machine.
                        if (task.active() && chunk.getBlockState(position) == state && level.getWorldBorder().isWithinBounds(position)) {
                            receipts.adoptLiveChanges(task.id, task.phase);
                            receipts.beforeWrite(task.id, task.phase, task.sectionY, task.voxel);
                            if (org.voxelregion.forge.DropSuppression.setBlock(level, position, replacement, flags)) {
                                task.changed++;
                                if (task.physics == AdapterConfig.Physics.BOUNDARY_ONLY)
                                    notifyBoundary(level, task, position, state.getBlock());
                            }
                            budget.writes--;
                            receipts.afterWrite(task.id, task.phase, task.sectionY, task.voxel + 1);
                        } else task.kept++;
                    }
                }
            }
            task.voxel++;
            receipts.adoptLiveChanges(task.id, task.phase);
            var checkpoint = receipts.checkpoint(task.id);
            var next = new ChunkReceipts.Cursor(task.sectionY, task.voxel);
            if (!next.equals(checkpoint.cursor())) receipts.advanceCursor(task.id, task.phase, next.sectionY(), next.voxel());
        }
        if (task.voxel == 4096) { task.voxel = 0; task.sectionY++; }
        return true;
    }

    private static boolean mustKeep(BlockState state, ServerLevel level, BlockPos position, Explosion context, double limit, boolean clearFluids) {
        if (state.is(PROTECTED)) return true;
        // A region clear includes free liquids. Their ray-explosion hardness/resistance
        // is not a preservation rule. Explicit block tags and chunk claims still win;
        // waterlogged solids do not enter this branch and retain their host protection.
        if (state.getBlock() instanceof LiquidBlock && !state.getFluidState().isEmpty()) return !clearFluids;
        float hardness = state.getDestroySpeed(level, position);
        if (!Float.isFinite(hardness) || hardness < 0) return true;
        float resistance = state.getExplosionResistance(level, position, context);
        return !Float.isFinite(resistance) || resistance < 0 || resistance >= limit;
    }

    private static void notifyBoundary(ServerLevel level, OperationData.Task task, BlockPos source, Block oldBlock) {
        for (var direction : net.minecraft.core.Direction.values()) {
            BlockPos neighbor = source.relative(direction);
            if (!level.hasChunkAt(neighbor)) continue;
            BlockState retained = level.getBlockState(neighbor);
            if (retained.isAir()) continue;
            // Internal ordinary terrain will be removed by a later range. External terrain,
            // fluids and special retained blocks receive a bounded direct notification.
            if (task.volume.contains(neighbor.getX(), neighbor.getY(), neighbor.getZ())
                    && retained.getBlock().getClass() == Block.class && !retained.is(PROTECTED)) continue;
            if (ClaimChecks.check(level, neighbor.getX() >> 4, neighbor.getZ() >> 4, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW)
                level.neighborChanged(neighbor, oldBlock, source);
        }
    }

    private static boolean entityStep(ServerLevel level, OperationData.Task task, Budget budget) {
        if (task.entityDamage <= 0) { task.entitiesHandled = true; return true; }
        if (task.recovering) {
            pause(task, OperationData.State.PAUSED_RECOVERY, "Entity damage cannot be replayed without a co-saved entity receipt.");
            return true;
        }
        EntityWork work = ENTITIES.get(task.id);
        if (work == null) {
            List<Entity> candidates = new ArrayList<>();
            level.getEntities().get(net.minecraft.world.level.entity.EntityTypeTest.forClass(Entity.class),
                    new AABB(task.cx - task.radius, task.cy - task.radius, task.cz - task.radius,
                            task.cx + task.radius, task.cy + task.radius, task.cz + task.radius), entity -> {
                        if (!entity.isSpectator()) candidates.add(entity);
                        return candidates.size() > MAX_ENTITY_CANDIDATES
                                ? net.minecraft.util.AbortableIterationConsumer.Continuation.ABORT
                                : net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE;
                    });
            if (candidates.size() > MAX_ENTITY_CANDIDATES) {
                pause(task, OperationData.State.PAUSED, "Entity candidate quota exceeded before applying any entity effects.");
                return true;
            }
            work = new EntityWork(candidates);
            ENTITIES.put(task.id, work);
        }
        if (work.index == work.entities.size()) {
            ENTITIES.remove(task.id);
            task.entitiesHandled = true;
            return true;
        }
        Entity entity = work.entities.get(work.index);
        ClaimChecks.Decision claim = ClaimChecks.check(level, entity.chunkPosition().x, entity.chunkPosition().z, true);
        if (claim == ClaimChecks.Decision.UNAVAILABLE) {
            pause(task, OperationData.State.PAUSED_PROTECTION, "Entity claim protection unavailable.");
            return true;
        }
        work.index++;
        budget.visits--;
        if (claim == ClaimChecks.Decision.ALLOW && entity.isAlive() && entity.level() == level) {
            double distance = Math.sqrt(entity.distanceToSqr(task.cx, task.cy, task.cz));
            if (distance <= task.radius && (task.geometry == null || task.geometry.containsPoint(entity.getX(), entity.getY(), entity.getZ()))) {
                float damage = (float) (task.entityDamage * (1.0 - distance / task.radius));
                if (damage > 0) {
                    Explosion explosion = new Explosion(level, level.getPlayerByUUID(task.actor), task.cx, task.cy, task.cz,
                            task.radius, false, Explosion.BlockInteraction.KEEP);
                    entity.hurt(level.damageSources().explosion(explosion), damage);
                }
            }
        }
        return true;
    }

    private static boolean hold(ServerLevel level, OperationData.Task task) {
        if (HELD.containsKey(task.id)) return true;
        if (retainedChunks() >= AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get()) return false;
        ChunkPos position = new ChunkPos(task.chunkX, task.chunkZ);
        for (HeldChunk held : HELD.values()) if (held.level == level && held.position.equals(position)) return false;
        for (var queue : IN_FLIGHT.values()) for (var commit : queue)
            if (commit.held.level == level && commit.held.position.equals(position)) return false;
        level.getChunkSource().addRegionTicket(TICKET, position, 0, task.id);
        HELD.put(task.id, new HeldChunk(level, position));
        return true;
    }

    private static void finishChunk(ServerLevel level, OperationData.Task task) {
        releaseTicket(task.id);
        task.chunkStarted = false;
        task.chunkSelected = false;
        task.sectionSelection = null;
        task.selectionSectionY = Integer.MIN_VALUE;
        task.voxel = 0;
        task.sectionY = level.getMinSection();
    }

    static void pause(OperationData.Task task, OperationData.State state, String message) {
        task.state = state;
        task.message = message;
        task.resumeRequiresValidation |= task.chunkStarted;
        task.chunkStarted = false; // Resume must validate its co-saved receipt, not adopt intervening edits.
        checkpointAndSettle(task);
    }

    static boolean resume(OperationData.Task task) {
        if (task.terminal() || task.active() || task.state == OperationData.State.PAUSED_RECOVERY || task.unconfirmedFences != 0) return false;
        task.state = task.fuse > 0 ? OperationData.State.FUSE : OperationData.State.RUNNING;
        task.message = "";
        try { if (task.owner != null) IntentJournal.checkpoint(task.owner, task); }
        catch (RuntimeException failure) {
            task.state = OperationData.State.FAILED;
            task.message = "Resume control journal failed; no new writes are permitted.";
            if (task.owner != null) OperationData.get(task.owner).setDirty();
            throw failure;
        }
        return true;
    }

    static void cancel(OperationData.Task task) {
        task.state = OperationData.State.CANCELLED;
        task.message = "Cancelled; previous writes are retained.";
        try { checkpointAndSettle(task); }
        finally { ENTITIES.remove(task.id); OperationOcclusion.release(task.id); }
    }

    private static void checkpointAndSettle(OperationData.Task task) {
        if (task.owner != null) OperationData.get(task.owner).setDirty();
        RuntimeException checkpointFailure = null;
        try { if (task.owner != null) IntentJournal.checkpoint(task.owner, task); }
        catch (RuntimeException failure) {
            checkpointFailure = failure;
            task.state = OperationData.State.FAILED;
            task.message = "Control journal failed; submitted writes still require completion: " + failure.getMessage();
        }
        try { settlePartial(task); }
        catch (RuntimeException cleanupFailure) {
            if (checkpointFailure == null) throw cleanupFailure;
            checkpointFailure.addSuppressed(cleanupFailure);
        }
        if (checkpointFailure != null) throw checkpointFailure;
    }

    private static void settlePartial(OperationData.Task task) {
        HeldChunk held = HELD.get(task.id);
        if (held == null) { releaseTicket(task.id); return; }
        LevelChunk chunk = held.level.getChunkSource().getChunkNow(held.position.x, held.position.z);
        if (chunk == null) { releaseTicket(task.id); return; }
        if (task.state == OperationData.State.CANCELLED && ChunkReceipts.of(chunk).state(task.id) != 0)
            ChunkReceipts.of(chunk).cancel(task.id);
        enqueueCommit(task, chunk, false);
    }

    /** Transfer one current ticket to a bounded completion queue before advancing any cursor. */
    private static void enqueueCommit(OperationData.Task task, LevelChunk chunk, boolean countAck) {
        HeldChunk held = HELD.get(task.id);
        if (held == null || !held.position.equals(chunk.getPos())) throw new IllegalStateException("Completion lost its current chunk reservation");
        CompletableFuture<Void> saved;
        NativeCommitFences.SaveFence nativeSave = null;
        try { nativeSave = NativeCommitFences.enqueueSave(held.level, chunk); saved = nativeSave.saved(); }
        catch (RuntimeException failure) { saved = CompletableFuture.failedFuture(failure); }
        CompletableFuture<Void> packet = CompletableFuture.completedFuture(null);
        if (task.needsChunkPacket) {
            try { packet = ChunkPacketQueue.enqueue(held.level, chunk); }
            catch (RuntimeException failure) { packet = CompletableFuture.failedFuture(failure); }
        }
        var future = CompletableFuture.allOf(saved, packet, task.queuedLightWork);
        var queue = IN_FLIGHT.computeIfAbsent(task.id, ignored -> new java.util.ArrayDeque<>());
        queue.addLast(new PendingCommit(held, task.phase, task.queuedLightWork, saved, packet, nativeSave, future, countAck));
        task.unconfirmedFences++;
        HELD.remove(task.id);
        task.needsChunkPacket = false;
        task.queuedLightWork = CompletableFuture.completedFuture(null); // Ownership moved into the queued future.
        refreshAggregate(task.id, queue);
    }

    private static void drainCommits(ServerLevel level, OperationData data, OperationData.Task task) {
        var queue = IN_FLIGHT.get(task.id);
        if (queue == null) return;
        boolean changed = false, failed = false;
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            PendingCommit commit = iterator.next();
            if (!commit.future.isDone()) {
                if (task.state != OperationData.State.FAILED && (commit.save.isCompletedExceptionally()
                        || commit.packet.isCompletedExceptionally() || commit.light.isCompletedExceptionally()
                        || commit.nativeSave != null && commit.nativeSave.lighting().isCompletedExceptionally())) {
                    failed = true; changed = true;
                    task.state = OperationData.State.FAILED;
                    task.message = "A native completion stage failed; new writes stopped while other submitted stages drain.";
                    RuntimeBridge.LOGGER.error("Region operation {} has a failed completion stage at {}; retaining its ticket until remaining stages terminate",
                            task.id, commit.held.position);
                }
                continue;
            }
            iterator.remove(); changed = true;
            try {
                commit.future.join();
                if (commit.countAck) task.finishedChunks++;
            } catch (RuntimeException failure) {
                failed = true;
                task.state = OperationData.State.FAILED;
                task.message = "Native completion failed; no automatic retry: " + failure.getMessage();
                RuntimeBridge.LOGGER.error("Region operation {} failed while settling chunk {}", task.id, commit.held.position, failure);
            } finally {
                commit.held.level.getChunkSource().removeRegionTicket(TICKET, commit.held.position, 0, task.id);
                task.unconfirmedFences--;
            }
        }
        if (!changed) return;
        if (queue.isEmpty()) IN_FLIGHT.remove(task.id);
        refreshAggregate(task.id, queue);
        if (failed) {
            OperationOcclusion.release(task.id); ENTITIES.remove(task.id);
            task.needsChunkPacket = true;
            settlePartial(task); // Already submitted writes in a current chunk also have to settle.
        }
        data.setDirty();
        if (!task.active() && (failed || task.unconfirmedFences == 0)) {
            pruneTerminalMemory(task);
            try { IntentJournal.checkpoint(level, task); }
            catch (RuntimeException failure) {
                task.state = OperationData.State.FAILED;
                task.message = "Completion control checkpoint failed: " + failure.getMessage();
                RuntimeBridge.LOGGER.error("Region completion checkpoint failed for {}", task.id, failure);
            }
        }
    }

    private static void refreshAggregate(UUID id, java.util.ArrayDeque<PendingCommit> queue) {
        if (queue.isEmpty()) COMMITTING.remove(id);
        else COMMITTING.put(id, CompletableFuture.allOf(queue.stream().map(commit -> commit.future).toArray(CompletableFuture[]::new)));
    }
    static int retainedChunks() {
        int result = HELD.size();
        for (var queue : IN_FLIGHT.values()) result += queue.size();
        return result;
    }
    static long estimatedMetadataBytes(MinecraftServer server) {
        long bytes = 8L * 1024; // Reserve the fixed global completion queue before captures consume the quota.
        for (var level : server.getAllLevels()) for (var task : OperationData.get(level).tasks) {
            bytes += 4096L + 24L * task.excludedChunks.size(); // Cursor, current masks and bounded per-task bookkeeping.
            if (!task.terminal() && task.entityDamage > 0 && !task.entitiesHandled)
                bytes += 1024L + 16L * (MAX_ENTITY_CANDIDATES + 1);
        }
        return bytes;
    }
    private static boolean excludeChunk(ServerLevel level, OperationData.Task task) {
        long key = ChunkPos.asLong(task.chunkX, task.chunkZ);
        if (task.excludedChunks.contains(key)) return true;
        if (task.excludedChunks.size() >= 2_000_000) throw new IllegalStateException("Excluded chunk metadata limit reached");
        if (estimatedMetadataBytes(level.getServer()) + OperationOcclusion.estimatedBytes() + 24
                > AdapterConfig.MAX_PENDING_BYTES.get()) {
            pause(task, OperationData.State.PAUSED, "Global metadata quota reached before extending loaded-only/permission exclusions.");
            return false;
        }
        task.excludedChunks.add(key);
        return true;
    }
    private static boolean pruneTerminalMemory(OperationData.Task task) {
        if (!task.terminal() || IN_FLIGHT.containsKey(task.id) || HELD.containsKey(task.id)
                || GENERATING.containsKey(task.id) || task.excludedChunks.isEmpty()) return false;
        task.excludedChunks.clear();
        task.excludedChunks.trim();
        return true;
    }

    private static boolean chunkInsideBorder(ServerLevel level, LevelChunk chunk) {
        var border = level.getWorldBorder();
        return border.getLerpRemainingTime() == 0 && chunk.getPos().getMinBlockX() >= border.getMinX()
                && chunk.getPos().getMinBlockX() + 16 <= border.getMaxX()
                && chunk.getPos().getMinBlockZ() >= border.getMinZ() && chunk.getPos().getMinBlockZ() + 16 <= border.getMaxZ();
    }

    private static void skipDeniedChunk(ServerLevel level, OperationData.Task task) {
        if (!excludeChunk(level, task)) return;
        task.skippedChunks++;
        var chunk = level.getChunkSource().getChunkNow(task.chunkX, task.chunkZ);
        if (chunk != null && HELD.containsKey(task.id)) {
            var receipts = ChunkReceipts.of(chunk);
            if (receipts.state(task.id) != 0) receipts.skipPermission(task.id);
            settlePartial(task);
            finishChunk(level, task);
            task.message = "Permission changed; prior writes are settling while other allowed chunks continue.";
        } else finishChunk(level, task);
    }

    static Component status(OperationData.Task task) {
        var stages = progress(task);
        return Component.literal(task.id + " " + task.state + " radius=" + task.radius + " chunk=" + task.chunkX + "," + task.chunkZ
                + " clearFluids=" + task.clearFluids + " physics=" + task.physics + " propagation=" + task.propagation + " visited=" + task.visited + " changed=" + task.changed + " kept=" + task.kept
                + " phase=" + Math.min(task.phase + 1, task.phaseCount()) + "/" + task.phaseCount() + " bulkSections=" + task.bulkSections
                + " inFlight=" + task.unconfirmedFences + " chunks=" + task.finishedChunks + " denied=" + task.skippedChunks + " unloaded=" + task.skippedUnloaded + " generationWritesSkipped=" + task.generatedSkipped
                + " stage=" + stages.preparationStage() + " writing=" + stages.writingChunks() + " loading=" + stages.loadingChunks()
                + " lighting=" + stages.pendingLighting() + " saving=" + stages.pendingSaves() + " packets=" + stages.pendingPackets()
                + (task.message.isEmpty() ? "" : " | " + task.message));
    }

    static org.voxelregion.forge.ForgeRegionEngine.Progress progress(OperationData.Task task) {
        var held = HELD.get(task.id);
        int loading = GENERATING.containsKey(task.id) || held != null
                && held.level.getChunkSource().getChunkNow(held.position.x, held.position.z) == null ? 1 : 0;
        int writing = task.active() && task.chunkStarted && held != null && loading == 0 ? 1 : 0;
        int lighting = 0, saves = 0, packets = 0, retained = held == null ? 0 : 1;
        var queue = IN_FLIGHT.get(task.id);
        if (queue != null) for (var commit : queue) {
            retained++;
            if (!commit.light.isDone() || commit.nativeSave != null && !commit.nativeSave.lighting().isDone()) lighting++;
            if (!commit.save.isDone()) saves++;
            if (!commit.packet.isDone()) packets++;
        }
        String stage = task.state == OperationData.State.FUSE ? "FUSE" : task.propagation == FrozenOcclusion.Mode.NONE
                ? "READY" : OperationOcclusion.stage(task.id);
        if (task.recovering && !task.terminal()) stage = "RECOVERY_" + stage;
        return new org.voxelregion.forge.ForgeRegionEngine.Progress(stage, writing, loading, lighting, saves, packets, retained);
    }

    private static void releaseTicket(UUID id) {
        var generation = GENERATING.remove(id);
        if (generation != null) generation.thenAccept(GenerationGate.Registration::close);
        HeldChunk held = HELD.remove(id);
        if (held != null) held.level.getChunkSource().removeRegionTicket(TICKET, held.position, 0, id);
    }
    private static void release(UUID id) { OperationOcclusion.release(id); releaseTicket(id); ENTITIES.remove(id); if (!IN_FLIGHT.containsKey(id)) COMMITTING.remove(id); }
    static void stop() {
        java.util.Set<UUID> ids = new java.util.HashSet<>(HELD.keySet()); ids.addAll(GENERATING.keySet());
        for (UUID id : ids) release(id);
        // Stop is not a durable acknowledgement: retain each task's nonzero persisted uncertainty marker.
        for (var entry : IN_FLIGHT.entrySet()) for (var commit : entry.getValue())
            commit.held.level.getChunkSource().removeRegionTicket(TICKET, commit.held.position, 0, entry.getKey());
        IN_FLIGHT.clear(); COMMITTING.clear();
        ENTITIES.clear(); OperationOcclusion.stop(); roundRobin = 0;
    }
    private record Work(ServerLevel level, OperationData data, OperationData.Task task) {}
    private record HeldChunk(ServerLevel level, ChunkPos position) {}
    private record PendingCommit(HeldChunk held, long phase, CompletableFuture<Void> light,
            CompletableFuture<Void> save, CompletableFuture<Void> packet, NativeCommitFences.SaveFence nativeSave,
            CompletableFuture<Void> future, boolean countAck) {}
    private static final class EntityWork {
        final List<Entity> entities;
        int index;
        EntityWork(List<Entity> entities) { this.entities = entities; }
    }
    private static final class Budget {
        final long deadline;
        int visits, writes;
        Budget(long deadline, int visits, int writes) { this.deadline = deadline; this.visits = visits; this.writes = writes; }
        boolean available() { return visits > 0 && writes > 0 && System.nanoTime() < deadline; }
    }
    private ForgeOperationRunner() {}
}
