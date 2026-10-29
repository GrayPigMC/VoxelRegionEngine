/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ClearedChunkLightBatch;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.PaletteSectionClearBackend;

/** Legal native bulk-layer installation compared with independent native per-block removal. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class ClearedChunkLightGameTests {
    @GameTest(template = "empty", batch = "vre_solved_chunk_light", timeoutTicks = 2200)
    public static void solvedLayersPreserveNeighborEmitterAndOpaqueRoofShadow(GameTestHelper helper) {
        runCase(helper, false);
    }

    @GameTest(template = "empty", batch = "vre_solved_chunk_light_fallback", timeoutTicks = 2200)
    public static void retainedInternalEmitterUsesRealNativeFallback(GameTestHelper helper) {
        runCase(helper, true);
    }

    private static void runCase(GameTestHelper helper, boolean retainedEmitter) {
        var candidate = PaletteBackendGameTests.fixture(helper, 0);
        var reference = PaletteBackendGameTests.fixture(helper, 4);
        List<PaletteBackendGameTests.Fixture> fixtures = List.of(candidate, reference);
        fixtures.forEach(fixture -> fill(fixture, retainedEmitter));
        CompletableFuture<?>[] work = { PaletteBackendGameTests.fence(fixtures) };
        long[] counters = new long[2];
        helper.startSequence().thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Nine-chunk setup light per fixture"))
                .thenExecute(() -> {
                    counters[0] = ClearedChunkLightBatch.installedBatches();
                    counters[1] = ClearedChunkLightBatch.fallbackBatches();
                    BitSet all = new BitSet(4096); all.set(0, 4096);
                    var plans = new ArrayList<PaletteSectionClearBackend.Prepared>();
                    for (int offset = 0; offset < 2; offset++) {
                        var prepared = PaletteSectionClearBackend.prepareSelected(candidate.level(), candidate.chunk(),
                                candidate.firstIndex() + offset, all, state -> !state.is(Blocks.BEDROCK), PaletteBackendGameTests.HOST);
                        helper.assertTrue(prepared.accepted() && prepared.prepared().nativeRemainderCount() == 0,
                                "Terrain selection has a real static batch proof: " + prepared.rejection());
                        plans.add(prepared.prepared());
                    }
                    var report = PaletteSectionClearBackend.commitBatch(plans, () -> true, ClearedChunkLightBatch.STRATEGY);
                    helper.assertTrue(report.applied(), "Candidate swaps storage through the production backend");
                    for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        BlockPos position = reference.minimum().offset(x, y, z);
                        if (!reference.level().getBlockState(position).is(Blocks.BEDROCK))
                            reference.level().setBlock(position, Blocks.AIR.defaultBlockState(), 18);
                    }
                    // Saving must await the custom solver, its installation and its native boundary work.
                    work[0] = CompletableFuture.allOf(
                            NativeCommitFences.enqueueSave(candidate.level(), candidate.chunk(), report.lightWork()).saved(),
                            NativeCommitFences.enqueueSave(reference.level(), reference.chunk(),
                                    PaletteBackendGameTests.fence(List.of(reference))).saved());
                }).thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Solved/native lighting and actual save fences"))
                .thenExecute(() -> {
                    try {
                        long installed = ClearedChunkLightBatch.installedBatches() - counters[0];
                        long fallback = ClearedChunkLightBatch.fallbackBatches() - counters[1];
                        helper.assertTrue(retainedEmitter ? installed == 0 && fallback == 1 : installed == 1 && fallback == 0,
                                "Observed strategy usage: installed=" + installed + ", fallback=" + fallback);
                        for (int y = -2; y < 35; y++) for (int z = -1; z < 17; z++) for (int x = -1; x < 17; x++) {
                            BlockPos actual = candidate.minimum().offset(x, y, z), expected = reference.minimum().offset(x, y, z);
                            helper.assertTrue(candidate.level().getBlockState(actual).equals(reference.level().getBlockState(expected)),
                                    "Candidate/native terrain differential at " + x + "," + y + "," + z);
                            for (LightLayer layer : LightLayer.values()) {
                                int a = candidate.level().getBrightness(layer, actual), b = reference.level().getBrightness(layer, expected);
                                helper.assertTrue(a == b, "Candidate/native " + layer + " differential at " + x + "," + y + "," + z + ": " + a + " != " + b);
                            }
                        }
                        helper.assertTrue(candidate.level().getBrightness(LightLayer.BLOCK, candidate.minimum().offset(0, 8, 8)) == 14,
                                "A real adjacent glowstone source crosses the chunk boundary with one-level loss");
                        helper.assertTrue(candidate.level().getBrightness(LightLayer.SKY, candidate.minimum().offset(8, 11, 8)) < 15,
                                "Retained opaque roof does not receive invented direct sky below it");
                        RuntimeBridge.LOGGER.info("ClearedChunkLight differential retainedEmitter={} installed={} fallback={} pendingBytes={}",
                                retainedEmitter, installed, fallback, ClearedChunkLightBatch.estimatedPendingBytes());
                    } finally { fixtures.forEach(PaletteBackendGameTests::release); }
                }).thenSucceed();
    }

    private static void fill(PaletteBackendGameTests.Fixture fixture, boolean retainedEmitter) {
        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            boolean roof = y == 12 && x >= 4 && x < 12 && z >= 4 && z < 12;
            fixture.level().setBlock(fixture.minimum().offset(x, y, z),
                    roof ? Blocks.BEDROCK.defaultBlockState() : Blocks.STONE.defaultBlockState(), 18);
        }
        fixture.level().setBlock(fixture.minimum().offset(-1, 8, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
        if (retainedEmitter) fixture.level().setBlock(fixture.minimum().offset(8, 32, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
        PaletteBackendGameTests.repackFixture(fixture);
    }
    private ClearedChunkLightGameTests() { }
}
