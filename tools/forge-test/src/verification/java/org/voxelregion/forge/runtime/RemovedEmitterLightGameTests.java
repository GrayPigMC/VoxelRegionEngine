/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ClearedChunkLightBatch;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.PaletteSectionClearBackend;

/** An extinguished internal source must not be reconstructed from its old neighbor light. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class RemovedEmitterLightGameTests {
    @GameTest(template = "empty", batch = "vre_solved_light_removed_emitter", timeoutTicks = 2200)
    public static void removedInternalLampCannotReturnThroughOldNeighborLight(GameTestHelper helper) {
        var candidate = PaletteBackendGameTests.fixture(helper, 0);
        var reference = PaletteBackendGameTests.fixture(helper, 4);
        List<PaletteBackendGameTests.Fixture> fixtures = List.of(candidate, reference);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BackendFixtureGameTests.onFailure(helper, () -> {
            release.countDown(); fixtures.forEach(PaletteBackendGameTests::release);
        });
        for (var fixture : fixtures) {
            for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                fixture.level().setBlock(fixture.minimum().offset(x, y, z), Blocks.STONE.defaultBlockState(), 18);
            fixture.level().setBlock(fixture.minimum().offset(0, 8, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
            PaletteBackendGameTests.repackFixture(fixture);
        }
        CompletableFuture<?>[] work = { PaletteBackendGameTests.fence(fixtures) };
        long[] counters = new long[2];
        helper.startSequence().thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Original emitter and nine-chunk halo are lit"))
                .thenExecute(() -> {
                    helper.assertTrue(candidate.level().getBrightness(LightLayer.BLOCK, candidate.minimum().offset(-1, 8, 8)) == 14,
                            "Old boundary light is demonstrably supplied by the internal source");
                    counters[0] = ClearedChunkLightBatch.installedBatches(); counters[1] = ClearedChunkLightBatch.fallbackBatches();
                    // Hold only the native light worker, after every synchronous fixture load.
                    // Finally/failure cleanup always releases it; no world-thread join is used.
                    mailbox(candidate.level().getChunkSource().getLightEngine()).tell(() -> {
                        entered.countDown();
                        boolean interrupted = false;
                        while (true) {
                            try { release.await(); break; }
                            catch (InterruptedException ignored) { interrupted = true; }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                    });
                }).thenWaitUntil(() -> helper.assertTrue(entered.getCount() == 0, "Native light worker entered the fixture gate"))
                .thenExecute(() -> {
                    try {
                        for (var fixture : fixtures) {
                            fixture.level().setBlock(fixture.minimum().offset(0, 8, 8), Blocks.AIR.defaultBlockState(), 18);
                            PaletteBackendGameTests.repackFixture(fixture);
                        }
                        helper.assertTrue(candidate.level().getBrightness(LightLayer.BLOCK, candidate.minimum().offset(-1, 8, 8)) == 14,
                                "Removed emitter still has deliberately unprocessed old neighbor light");
                        BitSet all = new BitSet(4096); all.set(0, 4096);
                        var plans = new ArrayList<PaletteSectionClearBackend.Prepared>();
                        for (int offset = 0; offset < 2; offset++) {
                            var preparation = PaletteSectionClearBackend.prepareSelected(candidate.level(), candidate.chunk(),
                                    candidate.firstIndex() + offset, all, state -> true, PaletteBackendGameTests.HOST);
                            helper.assertTrue(preparation.accepted() && preparation.prepared().nativeRemainderCount() == 0,
                                    "After native emitter removal the remaining terrain qualifies for solved lighting");
                            plans.add(preparation.prepared());
                        }
                        var report = PaletteSectionClearBackend.commitBatch(plans, () -> true, ClearedChunkLightBatch.STRATEGY);
                        helper.assertTrue(report.applied() && !report.lightWork().isDone(),
                                "Real fast-path work is waiting behind the old-light decrease work");
                        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                            reference.level().setBlock(reference.minimum().offset(x, y, z), Blocks.AIR.defaultBlockState(), 18);
                        work[0] = CompletableFuture.allOf(
                                NativeCommitFences.enqueueSave(candidate.level(), candidate.chunk(), report.lightWork()).saved(),
                                NativeCommitFences.enqueueSave(reference.level(), reference.chunk(),
                                        PaletteBackendGameTests.fence(List.of(reference))).saved());
                    } finally { release.countDown(); }
                }).thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Removed-source fast/native light and actual storage complete"))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(ClearedChunkLightBatch.installedBatches() - counters[0] == 1
                                        && ClearedChunkLightBatch.fallbackBatches() - counters[1] == 0,
                                "The eligible path actually installed solved data, instead of hiding behind fallback");
                        for (int y = -6; y <= 32; y++) for (int z = -8; z < 24; z++) for (int x = -16; x < 16; x++) {
                            BlockPos a = candidate.minimum().offset(x, y, z), b = reference.minimum().offset(x, y, z);
                            int actual = candidate.level().getBrightness(LightLayer.BLOCK, a);
                            int expected = reference.level().getBrightness(LightLayer.BLOCK, b);
                            helper.assertTrue(actual == 0 && expected == 0,
                                    "Extinguished source cannot recirculate at " + x + "," + y + "," + z + ": " + actual + "/" + expected);
                            helper.assertTrue(candidate.level().getBrightness(LightLayer.SKY, a)
                                            == reference.level().getBrightness(LightLayer.SKY, b),
                                    "Native/solved sky differential after removed emitter");
                        }
                        RuntimeBridge.LOGGER.info("RemovedEmitterLight installed=1 fallback=0; old boundary14 -> final block0");
                    } finally { release.countDown(); fixtures.forEach(PaletteBackendGameTests::release); }
                }).thenSucceed();
    }
    @SuppressWarnings("unchecked")
    private static ProcessorMailbox<Runnable> mailbox(ThreadedLevelLightEngine engine) {
        try {
            Field field = ThreadedLevelLightEngine.class.getDeclaredField("taskMailbox"); field.setAccessible(true);
            return (ProcessorMailbox<Runnable>) field.get(engine);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private RemovedEmitterLightGameTests() { }
}
