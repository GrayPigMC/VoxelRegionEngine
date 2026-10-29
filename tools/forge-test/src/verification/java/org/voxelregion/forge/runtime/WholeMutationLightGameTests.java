/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ClearedChunkLightBatch;
import org.voxelregion.forge.ClearedChunkLightBatch.FallbackReason;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.PaletteSectionClearBackend;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.propagation.FrozenOcclusion;

/** Final-image light proofs, bounded refresh, and real native stale-image repair. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class WholeMutationLightGameTests {
    private static final long WAIT_NANOS = 40_000_000_000L;
    private enum Mode { PUBLIC_NATIVE, REFRESH_ONCE, REFRESH_TWICE, STALE_CAPTURE }

    @GameTest(template = "empty", batch = "vre_final_mutation_light", timeoutTicks = 100000)
    public static void publicWholeChunkInstallsAfterNativeEmitterRemovalWithGhostPalette(GameTestHelper helper) {
        runCase(helper, Mode.PUBLIC_NATIVE);
    }

    @GameTest(template = "empty", batch = "vre_light_refresh_once", timeoutTicks = 100000)
    public static void onePreCaptureNeighborMutationRefreshesAndInstalls(GameTestHelper helper) {
        runCase(helper, Mode.REFRESH_ONCE);
    }

    @GameTest(template = "empty", batch = "vre_light_refresh_bounded", timeoutTicks = 100000)
    public static void secondPreCaptureMutationUsesNativeFallbackWithoutAnotherRefresh(GameTestHelper helper) {
        runCase(helper, Mode.REFRESH_TWICE);
    }

    @GameTest(template = "empty", batch = "vre_light_captured_stale", timeoutTicks = 100000)
    public static void emitterAddedAfterCaptureRejectsFrozenImageAndRepairsNatively(GameTestHelper helper) {
        runCase(helper, Mode.STALE_CAPTURE);
    }

    private static void runCase(GameTestHelper helper, Mode mode) {
        var candidate = PaletteBackendGameTests.fixture(helper, 0);
        var reference = PaletteBackendGameTests.fixture(helper, 4);
        List<PaletteBackendGameTests.Fixture> fixtures = List.of(candidate, reference);
        fixtures.forEach(fixture -> fill(fixture, mode == Mode.PUBLIC_NATIVE));
        Control control = new Control(candidate, reference, mode);
        onCompletion(helper, control::close);
        CompletableFuture<?>[] work = { PaletteBackendGameTests.fence(fixtures) };
        long[] started = { System.nanoTime() };
        helper.startSequence().thenWaitUntil(() -> waitFor(helper, work[0].isDone(), started[0], "Initial native halo"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.complete(helper, work[0], "Initial native halo");
                    control.recordCounters();
                    if (mode == Mode.PUBLIC_NATIVE) {
                        var prepared = PaletteSectionClearBackend.prepareSelected(candidate.level(), candidate.chunk(),
                                candidate.firstIndex(), all(), state -> !state.is(Blocks.BEDROCK), PaletteBackendGameTests.HOST);
                        helper.assertTrue(prepared.accepted() && prepared.prepared().nativeRemainderCount() > 0,
                                "A real emitting block requires the production native-removal remainder");
                        control.task = submit(helper, candidate);
                        clearNative(reference);
                        work[0] = CompletableFuture.completedFuture(null);
                    } else {
                        control.installInterception();
                        control.blockWorker();
                    }
                    started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, mode == Mode.PUBLIC_NATIVE || control.entered.getCount() == 0,
                        started[0], "Native worker fixture gate"))
                .thenExecute(() -> {
                    if (mode != Mode.PUBLIC_NATIVE) {
                        helper.assertTrue(control.entered.getCount() == 0, "The native worker really entered the gate");
                        try {
                            var plans = new ArrayList<PaletteSectionClearBackend.Prepared>();
                            for (int offset = 0; offset < 2; offset++) {
                                var prepared = PaletteSectionClearBackend.prepareSelected(candidate.level(), candidate.chunk(),
                                        candidate.firstIndex() + offset, all(), state -> !state.is(Blocks.BEDROCK), PaletteBackendGameTests.HOST);
                                helper.assertTrue(prepared.accepted() && prepared.prepared().nativeRemainderCount() == 0,
                                        "The independent backend case has no native remainder");
                                plans.add(prepared.prepared());
                            }
                            var report = PaletteSectionClearBackend.commitBatch(plans, () -> true, ClearedChunkLightBatch.STRATEGY);
                            helper.assertTrue(report.applied(), "Real palette replacement applied");
                            clearNative(reference);
                            if (mode == Mode.REFRESH_ONCE || mode == Mode.REFRESH_TWICE)
                                control.changeNeighbor(8);
                            work[0] = NativeCommitFences.enqueueSave(candidate.level(), candidate.chunk(), report.lightWork()).saved();
                        } finally { control.release.countDown(); }
                    }
                    started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, work[0].isDone() && (control.task == null || control.task.terminal()),
                        started[0], "Final mutation light and persistence"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.complete(helper, work[0], "Candidate native save");
                    if (control.task != null) {
                        helper.assertTrue(control.task.state == OperationData.State.WRITTEN && control.task.committedChunks == 1
                                        && control.task.finishedChunks == 1 && control.task.unconfirmedFences == 0,
                                "Public whole-chunk acceptance includes real light/save acknowledgements: "
                                        + ForgeOperationRunner.status(control.task).getString());
                        var section = candidate.chunk().getSection(candidate.firstIndex());
                        helper.assertTrue(section.maybeHas(state -> state.is(Blocks.GLOWSTONE)),
                                "Native deletion really left an unused glowstone palette entry; the fixture does not repack it");
                        boolean[] actualEmitter = { false };
                        section.getStates().getAll(state -> actualEmitter[0] |= state.is(Blocks.GLOWSTONE));
                        helper.assertTrue(!actualEmitter[0], "No actual voxel still references the obsolete emitter entry");
                    }
                    // The interception can also mutate the independent reference after its
                    // first checks were queued. Fence its final actual nine-chunk halo here.
                    work[0] = NativeCommitFences.enqueueSave(reference.level(), reference.chunk(),
                            PaletteBackendGameTests.fence(fixtures)).saved();
                    started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, work[0].isDone(), started[0], "Final independent native oracle"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.complete(helper, work[0], "Final independent native oracle");
                    long installed = ClearedChunkLightBatch.installedBatches() - control.installed;
                    long fallback = ClearedChunkLightBatch.fallbackBatches() - control.fallback;
                    long refreshes = ClearedChunkLightBatch.preCaptureRefreshes() - control.refreshes;
                    boolean shouldInstall = mode == Mode.PUBLIC_NATIVE || mode == Mode.REFRESH_ONCE;
                    helper.assertTrue(installed == (shouldInstall ? 1 : 0) && fallback == (shouldInstall ? 0 : 1),
                            "Actual light strategy installed=" + installed + ", fallback=" + fallback);
                    helper.assertTrue(refreshes == (mode == Mode.REFRESH_ONCE || mode == Mode.REFRESH_TWICE ? 1 : 0),
                            "Pre-capture refresh is bounded to one: " + refreshes);
                    if (mode == Mode.REFRESH_TWICE || mode == Mode.STALE_CAPTURE) {
                        FallbackReason reason = mode == Mode.REFRESH_TWICE
                                ? FallbackReason.STALE_BEFORE_CAPTURE : FallbackReason.STALE_AFTER_CAPTURE;
                        helper.assertTrue(control.intercepted && ClearedChunkLightBatch.fallbackReasonCounts().get(reason)
                                        - control.reasonBefore == 1,
                                "A real boundary mutation produced the exact native fallback reason " + reason);
                    }
                    compare(helper, candidate, reference);
                    if (mode == Mode.STALE_CAPTURE)
                        helper.assertTrue(candidate.level().getBrightness(LightLayer.BLOCK, candidate.minimum().offset(8, 12, 8)) == 15,
                                "A new emitter at an originally retained voxel survives stale-proof repair");
                    RuntimeBridge.LOGGER.info("WholeMutationLight mode={} installed={} fallback={} refreshes={} reasons={}",
                            mode, installed, fallback, refreshes, ClearedChunkLightBatch.fallbackReasonCounts());
                }).thenSucceed();
    }

    private static void fill(PaletteBackendGameTests.Fixture fixture, boolean internalEmitter) {
        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            fixture.level().setBlock(fixture.minimum().offset(x, y, z),
                    y == 12 && x >= 4 && x < 12 && z >= 4 && z < 12
                            ? Blocks.BEDROCK.defaultBlockState() : Blocks.STONE.defaultBlockState(), 18);
        fixture.level().setBlock(fixture.minimum().offset(-1, 8, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
        if (internalEmitter) fixture.level().setBlock(fixture.minimum().offset(8, 8, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
        PaletteBackendGameTests.repackFixture(fixture);
    }
    private static void clearNative(PaletteBackendGameTests.Fixture fixture) {
        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockPos position = fixture.minimum().offset(x, y, z);
            if (!fixture.level().getBlockState(position).is(Blocks.BEDROCK))
                fixture.level().setBlock(position, Blocks.AIR.defaultBlockState(), 18);
        }
    }
    private static BitSet all() { BitSet result = new BitSet(4096); result.set(0, 4096); return result; }
    private static OperationData.Task submit(GameTestHelper helper, PaletteBackendGameTests.Fixture fixture) {
        var physics = AdapterConfig.PHYSICS.get(); var propagation = AdapterConfig.PROPAGATION.get();
        double damage = AdapterConfig.ENTITY_DAMAGE.get();
        try {
            AdapterConfig.PHYSICS.set(AdapterConfig.Physics.NONE); AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.NONE);
            AdapterConfig.ENTITY_DAMAGE.set(0.0);
            BlockPos p = fixture.minimum();
            var handle = ForgeRegionEngine.submit(fixture.level(), UUID.randomUUID(),
                    new RegionGeometry.Box(new IntBox(p.getX(), p.getY(), p.getZ(), p.getX() + 16, p.getY() + 32, p.getZ() + 16)),
                    new ForgeRegionEngine.Options(0, false, true));
            var task = OperationData.get(fixture.level()).tasks.stream().filter(value -> value.id.equals(handle.id())).findFirst().orElseThrow();
            helper.assertTrue(task.wholeChunk, "Public facade selects the actual complete-chunk runner"); return task;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { AdapterConfig.PHYSICS.set(physics); AdapterConfig.PROPAGATION.set(propagation); AdapterConfig.ENTITY_DAMAGE.set(damage); }
    }
    private static void compare(GameTestHelper helper, PaletteBackendGameTests.Fixture candidate, PaletteBackendGameTests.Fixture reference) {
        for (int y = -2; y < 35; y++) for (int z = -1; z < 17; z++) for (int x = -1; x < 17; x++) {
            BlockPos a = candidate.minimum().offset(x, y, z), b = reference.minimum().offset(x, y, z);
            helper.assertTrue(candidate.level().getBlockState(a).equals(reference.level().getBlockState(b)),
                    "Independent final terrain oracle at " + x + "," + y + "," + z);
            for (LightLayer layer : LightLayer.values()) {
                int actual = candidate.level().getBrightness(layer, a), expected = reference.level().getBrightness(layer, b);
                helper.assertTrue(actual == expected, "Independent " + layer + " oracle at " + x + "," + y + "," + z
                        + ": " + actual + "/" + expected);
            }
        }
    }
    private static void waitFor(GameTestHelper helper, boolean done, long start, String stage) {
        helper.assertTrue(done || System.nanoTime() - start > WAIT_NANOS, stage + " pending");
    }
    private static void onCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) {}
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new IllegalStateException("GameTest completion hook unavailable");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    /** Intercepts enqueue timing only; every message and completion still uses the actual native engine. */
    private static final class Control {
        final PaletteBackendGameTests.Fixture candidate, reference; final Mode mode;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        long installed, fallback, refreshes, reasonBefore; volatile boolean intercepted, closed;
        OperationData.Task task; Field sorterField; ProcessorHandle<Object> originalSorter;
        Control(PaletteBackendGameTests.Fixture candidate, PaletteBackendGameTests.Fixture reference, Mode mode) {
            this.candidate = candidate; this.reference = reference; this.mode = mode;
        }
        void recordCounters() {
            installed = ClearedChunkLightBatch.installedBatches(); fallback = ClearedChunkLightBatch.fallbackBatches();
            refreshes = ClearedChunkLightBatch.preCaptureRefreshes();
            reasonBefore = ClearedChunkLightBatch.fallbackReasonCounts().get(mode == Mode.STALE_CAPTURE
                    ? FallbackReason.STALE_AFTER_CAPTURE : FallbackReason.STALE_BEFORE_CAPTURE);
        }
        @SuppressWarnings("unchecked") void blockWorker() {
            try {
                Field mailbox = ThreadedLevelLightEngine.class.getDeclaredField("taskMailbox"); mailbox.setAccessible(true);
                ((ProcessorMailbox<Runnable>) mailbox.get(candidate.level().getChunkSource().getLightEngine())).tell(() -> {
                    entered.countDown(); boolean interrupted = false;
                    while (true) {
                        try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                });
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        @SuppressWarnings("unchecked") void installInterception() {
            if (mode != Mode.REFRESH_TWICE && mode != Mode.STALE_CAPTURE) return;
            try {
                var engine = candidate.level().getChunkSource().getLightEngine();
                sorterField = ThreadedLevelLightEngine.class.getDeclaredField("sorterMailbox"); sorterField.setAccessible(true);
                originalSorter = (ProcessorHandle<Object>) sorterField.get(engine);
                sorterField.set(engine, ProcessorHandle.<Object>of(originalSorter.name(), message -> {
                    if (!intercepted && !closed) {
                        boolean atRefresh = candidate.level().getServer().isSameThread()
                                && mode == Mode.REFRESH_TWICE && ClearedChunkLightBatch.preCaptureRefreshes() > refreshes;
                        boolean afterCapture = mode == Mode.STALE_CAPTURE && StackWalker.getInstance().walk(frames ->
                                frames.anyMatch(frame -> frame.getMethodName().contains("solveAndInstall")));
                        if (atRefresh || afterCapture) {
                            intercepted = true; // Native setBlock itself also queues messages through this wrapper.
                            // Detached calculation now enqueues installation from its worker.
                            // Mutate on the real server owner before forwarding the actual native message.
                            candidate.level().getServer().execute(() -> {
                                if (!closed) {
                                    if (atRefresh) changeNeighbor(9);
                                    else for (var fixture : List.of(candidate, reference))
                                        fixture.level().setBlock(fixture.minimum().offset(8, 12, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
                                }
                                originalSorter.tell(message);
                            });
                            return;
                        }
                    }
                    originalSorter.tell(message);
                }));
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        void changeNeighbor(int y) {
            for (var fixture : List.of(candidate, reference))
                fixture.level().setBlock(fixture.minimum().offset(16, y, 8), Blocks.STONE.defaultBlockState(), 18);
        }
        void close() {
            if (closed) return; closed = true; release.countDown();
            if (sorterField != null && originalSorter != null) {
                try { sorterField.set(candidate.level().getChunkSource().getLightEngine(), originalSorter); }
                catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
            }
            if (task != null && !task.terminal()) ForgeOperationRunner.cancel(task);
            PaletteBackendGameTests.release(candidate); PaletteBackendGameTests.release(reference);
        }
    }
    private WholeMutationLightGameTests() { }
}
