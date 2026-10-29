/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ClearedChunkLightBatch;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.PaletteSectionClearBackend;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.ClearedChunkLighting.Face;

/** Published-layer reads, implicit sky, optional providers, and up-front copied-layer accounting. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class VisibleLightCaptureGameTests {
    private static final long WAIT_NANOS = 40_000_000_000L;

    @GameTest(template = "empty", batch = "vre_visible_light_queue", timeoutTicks = 100000)
    public static void queuedLayersDoNotReplaceVisibleReadsAndCopiesAreDetached(GameTestHelper helper) {
        var fixture = PaletteBackendGameTests.fixture(helper, 0);
        BlockPos probe = fixture.minimum().offset(8, 8, 8);
        fixture.level().setBlock(probe, Blocks.STONE.defaultBlockState(), 18);
        QueuedLayerControl control = new QueuedLayerControl(fixture, probe);
        onCompletion(helper, control::close);
        CompletableFuture<?>[] work = { PaletteBackendGameTests.fence(List.of(fixture)) };
        long[] started = { System.nanoTime() };
        helper.startSequence().thenWaitUntil(() -> waitFor(helper, work[0].isDone(), started[0], "Visible-layer fixture"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.complete(helper, work[0], "Visible-layer fixture");
                    var listener = fixture.level().getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
                    helper.assertTrue(listener instanceof NativeAccess.VisibleLightData, "Vanilla block light has the visible-only bridge");
                    DataLayer copy = ((NativeAccess.VisibleLightData) listener).vre$copyVisibleSectionData(SectionPos.of(probe).asLong());
                    helper.assertTrue(copy != null, "The nonempty fixture section stores a real visible layer");
                    int original = fixture.level().getBrightness(LightLayer.BLOCK, probe);
                    copy.set(8, 8, 8, 13);
                    helper.assertTrue(fixture.level().getBrightness(LightLayer.BLOCK, probe) == original,
                            "Changing the returned copy never writes native visible storage");
                    control.start(); started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, control.entered.getCount() == 0, started[0], "Queued-only native layer"))
                .thenExecute(() -> {
                    helper.assertTrue(control.entered.getCount() == 0, "The actual light worker entered the queued-layer gate");
                    try {
                        var listener = fixture.level().getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
                        DataLayer queued = listener.getDataLayerData(SectionPos.of(probe));
                        DataLayer visible = ((NativeAccess.VisibleLightData) listener).vre$copyVisibleSectionData(SectionPos.of(probe).asLong());
                        helper.assertTrue(queued != null && queued.get(8, 8, 8) == 13,
                                "The public native data accessor really exposes the queued, unpublished test layer");
                        helper.assertTrue(visible != null && visible.get(8, 8, 8) == control.originalValue
                                        && fixture.level().getBrightness(LightLayer.BLOCK, probe) == control.originalValue,
                                "The new read bridge agrees with visible light rather than queued data");
                    } finally { control.release.countDown(); }
                    work[0] = control.finished; started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, work[0].isDone(), started[0], "Original native layer restored"))
                .thenExecute(() -> PaletteBackendGameTests.complete(helper, work[0], "Original native layer restored"))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_visible_light_implicit_sky", timeoutTicks = 100000)
    public static void missingSkyLayersUseNativeShadowAndSkyDuringRealInstallation(GameTestHelper helper) {
        var candidate = lowerFixture(PaletteBackendGameTests.fixture(helper, 0));
        var reference = lowerFixture(PaletteBackendGameTests.fixture(helper, 4));
        List<PaletteBackendGameTests.Fixture> fixtures = List.of(candidate, reference);
        onCompletion(helper, () -> fixtures.forEach(PaletteBackendGameTests::release));
        for (var fixture : fixtures) {
            for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                fixture.level().setBlock(fixture.minimum().offset(x, y, z), Blocks.STONE.defaultBlockState(), 18);
            for (int z = -16; z < 32; z++) for (int x = -16; x < 32; x++)
                fixture.level().setBlock(fixture.minimum().offset(x, 32, z), Blocks.BEDROCK.defaultBlockState(), 18);
            PaletteBackendGameTests.repackFixture(fixture);
        }
        CompletableFuture<?>[] work = { PaletteBackendGameTests.fence(fixtures) };
        long[] started = { System.nanoTime() }, usage = new long[2];
        @SuppressWarnings("unchecked") Map<String, Long>[] statistics = new Map[1];
        helper.startSequence().thenWaitUntil(() -> waitFor(helper, work[0].isDone(), started[0], "Implicit-sky fixture"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.complete(helper, work[0], "Implicit-sky fixture");
                    var lighting = candidate.level().getChunkSource().getLightEngine();
                    var sky = (NativeAccess.VisibleLightData) lighting.getLayerListener(LightLayer.SKY);
                    var block = (NativeAccess.VisibleLightData) lighting.getLayerListener(LightLayer.BLOCK);
                    BlockPos shadow = candidate.minimum().offset(-1, -32, 8), sun = candidate.minimum().offset(-1, 80, 8);
                    helper.assertTrue(sky.vre$copyVisibleSectionData(SectionPos.of(shadow).asLong()) == null
                                    && candidate.level().getBrightness(LightLayer.SKY, shadow) == 0,
                            "A genuinely absent sky layer below the wide roof resolves to native shadow zero");
                    helper.assertTrue(sky.vre$copyVisibleSectionData(SectionPos.of(sun).asLong()) == null
                                    && candidate.level().getBrightness(LightLayer.SKY, sun) == 15,
                            "A different absent sky layer above the roof resolves to native direct sky fifteen");
                    helper.assertTrue(block.vre$copyVisibleSectionData(SectionPos.of(shadow).asLong()) == null
                                    && candidate.level().getBrightness(LightLayer.BLOCK, shadow) == 0,
                            "Confirmed absent block storage has the native zero semantics");
                    statistics[0] = ClearedChunkLightBatch.captureStatistics();
                    usage[0] = ClearedChunkLightBatch.installedBatches(); usage[1] = ClearedChunkLightBatch.fallbackBatches();
                    var plans = new ArrayList<PaletteSectionClearBackend.Prepared>();
                    BitSet all = new BitSet(4096); all.set(0, 4096);
                    for (int section = 0; section < 2; section++) {
                        var prepared = PaletteSectionClearBackend.prepareSelected(candidate.level(), candidate.chunk(),
                                candidate.firstIndex() + section, all, state -> true, PaletteBackendGameTests.HOST);
                        helper.assertTrue(prepared.accepted() && prepared.prepared().nativeRemainderCount() == 0,
                                "The roof fixture has a genuine static terrain proof");
                        plans.add(prepared.prepared());
                    }
                    var report = PaletteSectionClearBackend.commitBatch(plans, () -> true, ClearedChunkLightBatch.STRATEGY);
                    helper.assertTrue(report.applied(), "Real backend replacement applied before solved-light capture");
                    PaletteBackendGameTests.clearNative(reference);
                    work[0] = CompletableFuture.allOf(
                            NativeCommitFences.enqueueSave(candidate.level(), candidate.chunk(), report.lightWork()).saved(),
                            NativeCommitFences.enqueueSave(reference.level(), reference.chunk(),
                                    PaletteBackendGameTests.fence(List.of(reference))).saved());
                    started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, work[0].isDone(), started[0], "Visible-layer solved/native differential"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.complete(helper, work[0], "Visible-layer solved/native differential");
                    helper.assertTrue(ClearedChunkLightBatch.installedBatches() - usage[0] == 1
                                    && ClearedChunkLightBatch.fallbackBatches() - usage[1] == 0,
                            "This differential exercises an actual bulk installation, not native fallback");
                    var after = ClearedChunkLightBatch.captureStatistics();
                    helper.assertTrue(delta(after, statistics[0], "cachedBlockSamples") == 64L * candidate.level().getHeight()
                                    && delta(after, statistics[0], "nativeBlockSamples") == 0,
                            "All four block-light faces use section snapshots, including confirmed absent layers");
                    helper.assertTrue(delta(after, statistics[0], "cachedSkySamples") > 0
                                    && delta(after, statistics[0], "nativeSkySamples") > 0
                                    && delta(after, statistics[0], "copiedVisibleLayers") > 0
                                    && delta(after, statistics[0], "boundaryNanos") > 0,
                            "Stored sky layers are cached and implicit sky keeps its measured native reads");
                    for (int y = -32; y <= 81; y++) for (int z = -1; z < 17; z++) for (int x = -1; x < 17; x++) {
                        BlockPos a = candidate.minimum().offset(x, y, z), b = reference.minimum().offset(x, y, z);
                        helper.assertTrue(candidate.level().getBlockState(a).equals(reference.level().getBlockState(b)),
                                "Roof terrain agrees with independent native removal");
                        for (LightLayer layer : LightLayer.values()) {
                            int actual = candidate.level().getBrightness(layer, a), expected = reference.level().getBrightness(layer, b);
                            helper.assertTrue(actual == expected, "Visible-layer " + layer + " differential at " + x + "," + y + "," + z
                                    + ": " + actual + "/" + expected);
                        }
                    }
                    RuntimeBridge.LOGGER.info("Visible capture native differential: before={} after={}", statistics[0], after);
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_visible_light_optional", timeoutTicks = 100000)
    public static void unavailableAndUnsupportedReadersUseActualNativeValues(GameTestHelper helper) {
        var fixture = PaletteBackendGameTests.fixture(helper, 0);
        onCompletion(helper, () -> PaletteBackendGameTests.release(fixture));
        fixture.level().setBlock(fixture.minimum().offset(-1, 8, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
        CompletableFuture<?> initial = PaletteBackendGameTests.fence(List.of(fixture));
        long started = System.nanoTime();
        helper.startSequence().thenWaitUntil(() -> waitFor(helper, initial.isDone(), started, "Optional-reader fixture"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.complete(helper, initial, "Optional-reader fixture");
                    verifyOptionalReader(helper, fixture, null, null);
                    verifyOptionalReader(helper, fixture,
                            section -> { throw new UnsupportedOperationException("Verification provider declines copying"); },
                            section -> { throw new NoSuchMethodError("Verification provider lacks optional bridge implementation"); });
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_visible_light_budget", timeoutTicks = 100000)
    public static void copiedLayerReservationIsChargedBeforeAnyBarrierOrCapture(GameTestHelper helper) {
        var fixture = PaletteBackendGameTests.fixture(helper, 0);
        onCompletion(helper, () -> PaletteBackendGameTests.release(fixture));
        long pending = ClearedChunkLightBatch.estimatedPendingBytes();
        long reason = ClearedChunkLightBatch.fallbackReasonCounts().get(ClearedChunkLightBatch.FallbackReason.MEMORY_LIMIT);
        long captures = ClearedChunkLightBatch.captureStatistics().get("calls");
        int previous = AdapterConfig.MAX_PENDING_BYTES.get();
        CompletableFuture<Void> actual;
        try {
            AdapterConfig.MAX_PENDING_BYTES.set(1_048_576);
            actual = ClearedChunkLightBatch.afterWholeChunkMutation(fixture.level(), fixture.chunk(),
                    () -> NativeCommitFences.light(fixture.level(), fixture.chunk()));
            helper.assertTrue(ClearedChunkLightBatch.fallbackReasonCounts().get(ClearedChunkLightBatch.FallbackReason.MEMORY_LIMIT) == reason + 1
                            && ClearedChunkLightBatch.estimatedPendingBytes() == pending
                            && ClearedChunkLightBatch.captureStatistics().get("calls") == captures,
                    "The full solver plus copied-layer reservation is rejected before waiting/allocating/capturing");
        } finally { AdapterConfig.MAX_PENDING_BYTES.set(previous); }
        long started = System.nanoTime();
        helper.startSequence().thenWaitUntil(() -> waitFor(helper, actual.isDone(), started, "Budget fallback native fence"))
                .thenExecute(() -> PaletteBackendGameTests.complete(helper, actual, "Budget fallback native fence")).thenSucceed();
    }

    private static void verifyOptionalReader(GameTestHelper helper, PaletteBackendGameTests.Fixture fixture,
            NativeAccess.VisibleLightData blockReader, NativeAccess.VisibleLightData skyReader) {
        int height = fixture.level().getHeight();
        byte[][] block = new byte[6][], sky = new byte[6][]; BitSet[] open = new BitSet[6];
        for (Face face : Face.values()) {
            int size = ClearedChunkLighting.faceSize(height, face);
            block[face.ordinal()] = new byte[size]; sky[face.ordinal()] = new byte[size]; open[face.ordinal()] = new BitSet(size);
        }
        List<LevelChunk> ring = new ArrayList<>(9);
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++)
            ring.add(fixture.level().getChunk(fixture.chunk().getPos().x + x, fixture.chunk().getPos().z + z));
        Map<String, Long> before = ClearedChunkLightBatch.captureStatistics();
        try {
            Method capture = ClearedChunkLightBatch.class.getDeclaredMethod("captureBoundaryLayers", ServerLevel.class,
                    List.class, byte[][].class, byte[][].class, BitSet[].class,
                    NativeAccess.VisibleLightData.class, NativeAccess.VisibleLightData.class);
            capture.setAccessible(true); capture.invoke(null, fixture.level(), ring, block, sky, open, blockReader, skyReader);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        int sourceIndex = (fixture.minimum().getY() + 8 - fixture.level().getMinBuildHeight()) * 16 + 8;
        helper.assertTrue(block[Face.WEST.ordinal()][sourceIndex] == 15,
                "Unavailable block readers must not turn the real adjacent emitting source into zero");
        for (Face face : new Face[] { Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH })
            for (int y = 0; y < height; y++) for (int edge = 0; edge < 16; edge++) {
                int x = face == Face.WEST ? -1 : face == Face.EAST ? 16 : edge;
                int z = face == Face.NORTH ? -1 : face == Face.SOUTH ? 16 : edge;
                BlockPos source = new BlockPos(fixture.minimum().getX() + x, fixture.level().getMinBuildHeight() + y,
                        fixture.minimum().getZ() + z);
                helper.assertTrue(block[face.ordinal()][y * 16 + edge] == fixture.level().getBrightness(LightLayer.BLOCK, source)
                                && sky[face.ordinal()][y * 16 + edge] == fixture.level().getBrightness(LightLayer.SKY, source),
                        "Optional reader fallback equals the actual native layer values");
            }
        Map<String, Long> after = ClearedChunkLightBatch.captureStatistics();
        helper.assertTrue(delta(after, before, "nativeBlockSamples") == 64L * height
                        && delta(after, before, "nativeSkySamples") == 64L * height
                        && delta(after, before, "cachedBlockSamples") == 0 && delta(after, before, "cachedSkySamples") == 0,
                "Unavailable and throwing readers both use the original native API for every face sample");
    }
    private static PaletteBackendGameTests.Fixture lowerFixture(PaletteBackendGameTests.Fixture base) {
        return new PaletteBackendGameTests.Fixture(base.level(), base.chunk(), base.level().getSectionIndex(128),
                new BlockPos(base.minimum().getX(), 128, base.minimum().getZ()));
    }
    private static long delta(Map<String, Long> after, Map<String, Long> before, String key) { return after.get(key) - before.get(key); }
    private static void waitFor(GameTestHelper helper, boolean done, long started, String stage) {
        helper.assertTrue(done || System.nanoTime() - started > WAIT_NANOS, stage + " pending");
    }
    private static void onCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) { }
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new IllegalStateException("GameTest completion hook unavailable");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    /** Queues and restores only on the actual light worker; failure/timeout always opens the gate. */
    private static final class QueuedLayerControl {
        final PaletteBackendGameTests.Fixture fixture; final BlockPos probe;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        int originalValue; boolean closed;
        QueuedLayerControl(PaletteBackendGameTests.Fixture fixture, BlockPos probe) { this.fixture = fixture; this.probe = probe; }
        @SuppressWarnings("unchecked") void start() {
            var threaded = fixture.level().getChunkSource().getLightEngine();
            var block = (LightEngine<?, ?>) threaded.getLayerListener(LightLayer.BLOCK);
            long section = SectionPos.of(probe).asLong();
            DataLayer original = ((NativeAccess.VisibleLightData) block).vre$copyVisibleSectionData(section);
            if (original == null) throw new IllegalStateException("Stored visible fixture layer required");
            originalValue = original.get(8, 8, 8);
            try {
                Field mailbox = ThreadedLevelLightEngine.class.getDeclaredField("taskMailbox"); mailbox.setAccessible(true);
                ((ProcessorMailbox<Runnable>) mailbox.get(threaded)).tell(() -> {
                    boolean interrupted = false;
                    try {
                        block.queueSectionData(section, new DataLayer(13)); entered.countDown();
                        while (true) { try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
                        block.queueSectionData(section, original); block.runLightUpdates(); finished.complete(null);
                    } catch (Throwable failure) { finished.completeExceptionally(failure); }
                    finally { if (interrupted) Thread.currentThread().interrupt(); }
                });
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        void close() {
            if (closed) return; closed = true; release.countDown(); PaletteBackendGameTests.release(fixture);
        }
    }
    private VisibleLightCaptureGameTests() { }
}
