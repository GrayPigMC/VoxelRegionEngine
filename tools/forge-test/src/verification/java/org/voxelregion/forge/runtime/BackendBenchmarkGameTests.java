/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.PaletteSectionClearBackend;

/** Backend sampling, not an end-to-end TPS or player-network benchmark; no speed threshold is asserted. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class BackendBenchmarkGameTests {
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final TicketType<ChunkPos> HOLD = TicketType.create("vre_backend_sample", Comparator.comparingLong(ChunkPos::toLong));
    private static final TagKey<Block> PROTECTED = TagKey.create(Registries.BLOCK,
            new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "protected"));
    private static final List<Heightmap.Types> HEIGHTMAP_TYPES = List.of(Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    private static final PaletteSectionClearBackend.HostHooks HOST = new PaletteSectionClearBackend.HostHooks() {
        @Override public boolean supportsBulk(ServerLevel level) { return true; }
        @Override public long mutationRevision(LevelChunk chunk) {
            var receipts = ChunkReceipts.of(chunk);
            return receipts.trackingValid() ? receipts.mutationRevision() : -1;
        }
        @Override public void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
            ChunkMutationTracking.changing(chunk);
        }
        @Override public void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
            ChunkMutationTracking.changed(chunk);
        }
    };

    @GameTest(template = "empty", batch = "vre_backend_sampling", timeoutTicks = 100_000)
    public static void nativeAndPaletteSampleTheSameWorldContract(GameTestHelper helper) {
        if (CPU.isCurrentThreadCpuTimeSupported() && !CPU.isThreadCpuTimeEnabled()) CPU.setThreadCpuTimeEnabled(true);
        // Each path owns a site in the same bedrock-only dimension. GameTest markers and natural
        // random ticks cannot mutate a heightmap between the save image and its asynchronous read.
        Fixture nativePath = fixture(helper, 4), palettePath = fixture(helper, 8);
        List<Sample> samples = new ArrayList<>();
        CompletableFuture<?>[] initial = new CompletableFuture<?>[1];
        Sample[] active = new Sample[1];
        var sequence = helper.startSequence();
        for (int round = 0; round < 4; round++) {
            int sampleNumber = round;
            boolean mixed = (round & 1) != 0;
            boolean paletteFirst = round == 1 || round == 2;
            sequence.thenExecute(() -> {
                fill(nativePath, mixed);
                fill(palettePath, mixed);
                initial[0] = CompletableFuture.allOf(BackendFixtureGameTests.lightHalo(nativePath.level, nativePath.chunk.getPos()),
                        BackendFixtureGameTests.lightHalo(palettePath.level, palettePath.chunk.getPos()));
            }).thenWaitUntil(() -> complete(helper, initial[0], "Untimed fixture lighting"));
            // Balance execution order within both dense/mixed pairs; each sample finishes its light/save
            // pipeline before the other path starts, avoiding overlapping benchmark queue contention.
            for (boolean palette : paletteFirst ? new boolean[] { true, false } : new boolean[] { false, true }) {
                Fixture fixture = palette ? palettePath : nativePath;
                sequence.thenExecute(() -> {
                    active[0] = measure(helper, fixture, palette, mixed, sampleNumber);
                    samples.add(active[0]);
                }).thenWaitUntil(() -> complete(helper, active[0].light, "Measured native light fence"))
                        .thenExecute(() -> {
                            assertWorld(helper, fixture);
                            Sample sample = active[0];
                            sample.expectedRevision = ChunkMutationTracking.revision(fixture.chunk);
                            for (Heightmap.Types type : HEIGHTMAP_TYPES)
                                sample.expectedHeightmaps.put(type, fixture.chunk.getOrCreateHeightmapUnprimed(type).getRawData().clone());
                            sample.saveStarted = System.nanoTime();
                            sample.saved = NativeCommitFences.saveAfterLighting(fixture.level, fixture.chunk);
                            sample.saved.whenComplete((ignored, failure) -> sample.saveCompleted.set(System.nanoTime()));
                        }).thenWaitUntil(() -> awaitSaved(helper, fixture, active[0]))
                        .thenExecute(() -> {
                            helper.assertTrue(active[0].saved.isDone(), "Native save exceeded its 30-second wall watchdog: "
                                    + saveDiagnostics(fixture, active[0].saved));
                            helper.assertTrue(!active[0].saved.isCompletedExceptionally(), "Native save failed: " + active[0].saved);
                            assertStableImage(helper, fixture, active[0], "before readback");
                            active[0].read = ((IOWorker) fixture.level.getChunkSource().chunkMap.chunkScanner()).loadAsync(fixture.chunk.getPos());
                        })
                        .thenWaitUntil(() -> complete(helper, active[0].read, "Untimed native image readback"))
                        .thenExecute(() -> {
                            Sample sample = active[0];
                            assertStableImage(helper, fixture, sample, "after readback");
                            assertSaved(helper, fixture, sample.read.join().orElseThrow(), sample);
                            RuntimeBridge.LOGGER.info("VRE_BACKEND_SAMPLE round={} case={} backend={} cells={} changed={} prepareCpuNs={} commitCpuNs={} prepareWallNs={} commitWallNs={} lightTailWallNs={} saveFenceWallNs={} players=0 diskTerrainReceiptHeightmaps=PASS liveLightOracle=PASS fixtureDimension={} chunk={} imageEpoch={}",
                                    sample.round, sample.mixed ? "mixed_air" : "dense", sample.palette ? "palette" : "native",
                                    8192, sample.changed, sample.prepareCpu, sample.commitCpu, sample.prepareWall, sample.commitWall,
                                    Math.max(0, sample.lightCompleted.get() - sample.lightStarted), sample.saveCompleted.get() - sample.saveStarted,
                                    fixture.level.dimension().location(), fixture.chunk.getPos(), sample.expectedRevision);
                        });
            }
        }
        sequence.thenExecute(() -> {
            helper.assertTrue(samples.size() == 8, "All eight complete backend samples retain light/save/readback evidence");
            RuntimeBridge.LOGGER.info("VRE_BACKEND_SAMPLE_SCOPE mc=1.20.1 forge=47.3.7 logicalProcessors={} cpuClock={} physics=NONE protectedResistance=1200 commitReceipt=one_chunk_transaction geometry=fixed_two_sections fixture=dedicated_flat_bedrock setupUntimed=true initialHaloLightFence=true immutableSameEpochImageOracle=true watchers=0 excludes=geometry_enumeration,generation,rendering,network_throughput,journal_fsync no_speed_threshold=true",
                    Runtime.getRuntime().availableProcessors(), CPU.isCurrentThreadCpuTimeSupported());
            release(nativePath); release(palettePath);
        }).thenSucceed();
    }

    private static Sample measure(GameTestHelper helper, Fixture fixture, boolean palette, boolean mixed, int round) {
        helper.assertTrue(fixture.level.getChunkSource().chunkMap.getPlayers(fixture.chunk.getPos(), false).isEmpty(),
                "Sampling fixture has no client watchers; network performance is excluded explicitly");
        Sample sample = new Sample(palette, mixed, round);
        Explosion explosion = new Explosion(fixture.level, null, fixture.minimum.getX() + 8.5,
                fixture.minimum.getY() + 16.5, fixture.minimum.getZ() + 8.5, 32, false, Explosion.BlockInteraction.KEEP);
        BlockState[] original = palette ? null : new BlockState[8192];
        List<PaletteSectionClearBackend.Prepared> plans = new ArrayList<>(2);
        long cpu = cpuNow(), wall = System.nanoTime();
        helper.assertTrue(claimAllows(fixture), "Same claim policy authorizes both backends");
        if (palette) {
            for (int i = 0; i < 2; i++) {
                var plan = PaletteSectionClearBackend.prepare(fixture.level, fixture.chunk, fixture.firstIndex + i,
                        state -> removable(state, fixture, fixture.minimum, explosion), HOST);
                helper.assertTrue(plan.accepted(), "Sampling palette qualifies: " + plan.rejection());
                plans.add(plan.prepared()); sample.changed += plan.prepared().changedBlocks();
            }
        } else {
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            for (int i = 0; i < 8192; i++) {
                relative(position, fixture.minimum, i);
                BlockState state = fixture.chunk.getBlockState(position);
                original[i] = state;
                if (!state.isAir()) {
                    helper.assertTrue(removable(state, fixture, position, explosion),
                            "Native preparation accepts the common static fixture policy");
                    sample.changed++;
                }
            }
        }
        sample.prepareWall = System.nanoTime() - wall;
        sample.prepareCpu = elapsedCpu(cpu);
        ChunkReceipts receipts = ChunkReceipts.of(fixture.chunk);
        int firstY = fixture.minimum.getY() >> 4;
        receipts.beginPhase(sample.operation, "backend-sample-" + round, 0, firstY, firstY + 2);
        cpu = cpuNow(); wall = System.nanoTime();
        receipts.beforeWrite(sample.operation, 0, firstY, 0);
        CompletableFuture<Void> backendLight = CompletableFuture.completedFuture(null);
        if (palette) {
            var report = PaletteSectionClearBackend.commitBatch(plans, () -> claimAllows(fixture));
            helper.assertTrue(report.applied() && report.changedBlocks() == sample.changed && report.needsChunkPacket(),
                    "Palette commit applies the same expected non-air writes");
            backendLight = report.lightWork();
        } else {
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            for (int i = 0; i < original.length; i++) {
                if (original[i].isAir()) continue;
                relative(position, fixture.minimum, i);
                helper.assertTrue(claimAllows(fixture) && fixture.chunk.getBlockState(position) == original[i],
                        "Native commit claim/state remains unchanged during the common fixture");
                helper.assertTrue(fixture.level.setBlock(position, Blocks.AIR.defaultBlockState(), 18),
                        "Native write applies the expected block change");
            }
        }
        receipts.afterWrite(sample.operation, 0, firstY + 2, 0);
        receipts.completePhase(sample.operation, 0, true);
        sample.commitWall = System.nanoTime() - wall;
        sample.commitCpu = elapsedCpu(cpu);
        sample.lightStarted = System.nanoTime();
        sample.light = CompletableFuture.allOf(backendLight, NativeCommitFences.light(fixture.level, fixture.chunk));
        sample.light.whenComplete((ignored, failure) -> sample.lightCompleted.set(System.nanoTime()));
        return sample;
    }

    private static boolean claimAllows(Fixture fixture) {
        return ClaimChecks.check(fixture.level, fixture.chunk.getPos().x, fixture.chunk.getPos().z, false) == ClaimChecks.Decision.ALLOW;
    }

    private static boolean removable(BlockState state, Fixture fixture, BlockPos position, Explosion explosion) {
        if (state.is(PROTECTED)) return false;
        float hardness = state.getDestroySpeed(fixture.level, position);
        float resistance = state.getExplosionResistance(fixture.level, position, explosion);
        return Float.isFinite(hardness) && hardness >= 0 && Float.isFinite(resistance) && resistance >= 0 && resistance < 1200;
    }

    private static void fill(Fixture fixture, boolean mixed) {
        Random random = new Random(0x7120C0DEL);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 8192; i++) {
            relative(position, fixture.minimum, i);
            fixture.level.setBlock(position, mixed && random.nextDouble() < .35 ? Blocks.AIR.defaultBlockState()
                    : (i & 3) == 0 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState(), 18);
        }
        fixture.level.setBlock(fixture.minimum.offset(8, -1, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
        fixture.level.setBlock(fixture.minimum.offset(15, 32, 15), Blocks.BEDROCK.defaultBlockState(), 18);
    }

    private static void assertWorld(GameTestHelper helper, Fixture fixture) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 8192; i++) {
            relative(position, fixture.minimum, i);
            int x = i & 15, z = (i >>> 4) & 15, y = i >>> 8;
            helper.assertTrue(fixture.chunk.getBlockState(position).isAir() && fixture.chunk.getFluidState(position).isEmpty(),
                    "Sample clears the full agreed region");
            int expectedBlock = Math.max(0, 14 - Math.abs(x - 8) - Math.abs(z - 8) - y);
            int expectedSky = x == 15 && z == 15 ? 14 : 15;
            helper.assertTrue(fixture.level.getBrightness(LightLayer.BLOCK, position) == expectedBlock
                            && fixture.level.getBrightness(LightLayer.SKY, position) == expectedSky,
                    "Both backend samples finish the same independent source/skylight oracle");
        }
        helper.assertTrue(fixture.level.getBlockState(fixture.minimum.offset(15, 32, 15)).is(Blocks.BEDROCK),
                "Protected outside sentinel is preserved");
        for (Heightmap.Types type : HEIGHTMAP_TYPES) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int expected = fixture.level.getMinBuildHeight();
                for (int y = fixture.level.getMaxBuildHeight() - 1; y >= fixture.level.getMinBuildHeight(); y--)
                    if (type.isOpaque().test(fixture.chunk.getBlockState(new BlockPos(fixture.minimum.getX() + x, y, fixture.minimum.getZ() + z)))) {
                        expected = y + 1; break;
                    }
                helper.assertTrue(fixture.chunk.getHeight(type, x, z) + 1 == expected, "Sample full-column heightmap oracle: " + type);
            }
        }
    }

    private static void assertSaved(GameTestHelper helper, Fixture fixture, CompoundTag image, Sample sample) {
        ListTag sections = image.getList("sections", Tag.TAG_COMPOUND);
        Map<Integer, PalettedContainer<BlockState>> savedStates = new HashMap<>();
        var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        int checked = 0;
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            int y = section.getByte("Y");
            if (!section.contains("block_states", Tag.TAG_COMPOUND)) continue;
            var states = codec.parse(NbtOps.INSTANCE, section.getCompound("block_states"))
                    .getOrThrow(false, helper::fail);
            helper.assertTrue(savedStates.put(y, states) == null, "Saved section Y appears only once");
            if (y < (fixture.minimum.getY() >> 4) || y >= (fixture.minimum.getY() >> 4) + 2) continue;
            states.getAll(state -> helper.assertTrue(state.isAir(), "Native saved image contains the cleared section"));
            checked++;
        }
        helper.assertTrue(checked == 2, "Both changed section palettes exist in native saved image");
        ChunkReceipts restored = new ChunkReceipts();
        restored.deserializeNBT(image.getCompound("ForgeCaps").getCompound(RuntimeBridge.PERSISTENCE_NAMESPACE + ":receipts"));
        helper.assertTrue(restored.state(sample.operation) == ChunkReceipts.COMPLETE, "Saved image contains the matching terminal receipt");
        helper.assertTrue(restored.mutationRevision() == sample.expectedRevision,
                "Terrain receipt and the verified input describe the same immutable mutation epoch");
        int minimum = fixture.level.getMinBuildHeight(), maximum = fixture.level.getMaxBuildHeight();
        int bits = 32 - Integer.numberOfLeadingZeros(fixture.level.getHeight());
        for (Heightmap.Types type : HEIGHTMAP_TYPES) {
            long[] packed = image.getCompound("Heightmaps").getLongArray(type.getSerializationKey());
            long[] expectedPacked = sample.expectedHeightmaps.get(type);
            helper.assertTrue(packed.length == (256 + 64 / bits - 1) / (64 / bits), "Saved heightmap storage length: " + type);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int columnHeight = minimum;
                for (int y = maximum - 1; y >= minimum; y--) {
                    var states = savedStates.get(y >> 4);
                    BlockState state = states == null ? Blocks.AIR.defaultBlockState() : states.get(x, y & 15, z);
                    if (type.isOpaque().test(state)) { columnHeight = y + 1; break; }
                }
                int column = z * 16 + x;
                int savedHeight = packedValue(packed, bits, column) + minimum;
                int frozenHeight = packedValue(expectedPacked, bits, column) + minimum;
                helper.assertTrue(savedHeight == columnHeight && savedHeight == frozenHeight,
                        "Saved heightmap independent image oracle: " + type + " x=" + x + " z=" + z
                                + " stored=" + savedHeight + " savedColumn=" + columnHeight + " frozenExpected=" + frozenHeight
                                + " imageEpoch=" + restored.mutationRevision() + " expectedEpoch=" + sample.expectedRevision);
            }
        }
    }

    private static int packedValue(long[] values, int bits, int index) {
        int perLong = 64 / bits;
        return (int) ((values[index / perLong] >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
    }

    private static void assertStableImage(GameTestHelper helper, Fixture fixture, Sample sample, String stage) {
        long current = ChunkMutationTracking.revision(fixture.chunk);
        helper.assertTrue(current == sample.expectedRevision, "Isolated sampling fixture changed " + stage
                + ": chunk=" + fixture.chunk.getPos() + " expectedEpoch=" + sample.expectedRevision + " actualEpoch=" + current);
    }

    private static Fixture fixture(GameTestHelper helper, int offsetX) {
        var site = BackendFixtureGameTests.allocate(helper, offsetX);
        ServerLevel level = site.level();
        ChunkPos position = site.position();
        level.getChunkSource().addRegionTicket(HOLD, position, 2, position);
        LevelChunk chunk = level.getChunk(position.x, position.z);
        int sectionY = level.getMaxSection() - 4;
        BlockPos minimum = new BlockPos(position.getMinBlockX(), sectionY << 4, position.getMinBlockZ());
        // Isolate the high-altitude fixture and its light padding from earlier GameTest artifacts;
        // the protected sentinel/source are installed afterwards. This is untimed setup only.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++)
            for (int y = minimum.getY() - 16; y < level.getMaxBuildHeight(); y++) {
                cursor.set(minimum.getX() + x, y, minimum.getZ() + z);
                if (!level.getBlockState(cursor).isAir()) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 18);
            }
        Fixture fixture = new Fixture(level, chunk, minimum, level.getSectionIndexFromSectionY(sectionY));
        BackendFixtureGameTests.onFailure(helper, () -> release(fixture));
        return fixture;
    }

    private static void relative(BlockPos.MutableBlockPos target, BlockPos minimum, int index) {
        target.set(minimum.getX() + (index & 15), minimum.getY() + (index >>> 8), minimum.getZ() + ((index >>> 4) & 15));
    }
    private static long cpuNow() { return CPU.isCurrentThreadCpuTimeSupported() ? CPU.getCurrentThreadCpuTime() : -1; }
    private static long elapsedCpu(long start) { long end = cpuNow(); return start < 0 || end < 0 ? -1 : end - start; }
    private static void complete(GameTestHelper helper, CompletableFuture<?> future, String description) {
        helper.assertTrue(future != null && future.isDone(), description + " completed"); future.join();
    }
    private static void awaitSaved(GameTestHelper helper, Fixture fixture, Sample sample) {
        if (!sample.saved.isDone()) {
            long elapsed = System.nanoTime() - sample.saveStarted;
            if (!sample.saveWaitLogged && elapsed >= 1_000_000_000L) {
                sample.saveWaitLogged = true;
                RuntimeBridge.LOGGER.warn("VRE_BACKEND_SAVE_WAIT round={} backend={} elapsedNs={} {}",
                        sample.round, sample.palette ? "palette" : "native", elapsed, saveDiagnostics(fixture, sample.saved));
            }
            // Leave the waiting phase at the wall limit; the following thenExecute assertion
            // records a normal GameTest failure instead of throwing an uncaught AssertionError.
            helper.assertTrue(elapsed >= 30_000_000_000L, "Measured native save fence pending");
        }
    }
    /** Read-only inspection of our own adapter's queue, kept exclusively in verification classes. */
    private static String saveDiagnostics(Fixture fixture, CompletableFuture<Void> result) {
        StringBuilder text = new StringBuilder("pendingCount=").append(NativeCommitFences.pendingCount())
                .append(" currentEpoch=").append(((NativeAccess.ChunkRevision) fixture.chunk).vre$mutationRevision());
        try {
            for (String queue : new String[] { "pending", "active" }) {
                var field = NativeCommitFences.class.getDeclaredField(queue); field.setAccessible(true);
                for (Object request : (List<?>) field.get(null)) {
                    var resultField = request.getClass().getDeclaredField("result"); resultField.setAccessible(true);
                    if (resultField.get(request) != result) continue;
                    var light = request.getClass().getDeclaredField("light"); light.setAccessible(true);
                    var epoch = request.getClass().getDeclaredField("epoch"); epoch.setAccessible(true);
                    text.append(" queue=").append(queue).append(" requestEpoch=").append(epoch.getLong(request))
                            .append(" light=").append(light.get(request));
                }
            }
            var batch = NativeCommitFences.class.getDeclaredField("batch"); batch.setAccessible(true);
            text.append(" batch=").append(batch.get(null));
        } catch (ReflectiveOperationException failure) { text.append(" diagnosticFailure=").append(failure); }
        return text.toString();
    }
    private static void release(Fixture fixture) {
        fixture.level.getChunkSource().removeRegionTicket(HOLD, fixture.chunk.getPos(), 2, fixture.chunk.getPos());
    }
    private record Fixture(ServerLevel level, LevelChunk chunk, BlockPos minimum, int firstIndex) {}
    private static final class Sample {
        final boolean palette, mixed;
        final int round;
        final UUID operation = UUID.randomUUID();
        final AtomicLong lightCompleted = new AtomicLong(), saveCompleted = new AtomicLong();
        int changed;
        long prepareCpu, commitCpu, prepareWall, commitWall, lightStarted, saveStarted;
        long expectedRevision;
        final Map<Heightmap.Types, long[]> expectedHeightmaps = new EnumMap<>(Heightmap.Types.class);
        CompletableFuture<Void> light, saved;
        CompletableFuture<Optional<CompoundTag>> read;
        boolean saveWaitLogged;
        Sample(boolean palette, boolean mixed, int round) { this.palette = palette; this.mixed = mixed; this.round = round; }
    }
    private BackendBenchmarkGameTests() {}
}
