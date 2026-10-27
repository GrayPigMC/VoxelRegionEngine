/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.PaletteSectionClearBackend;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class VanillaAirPaletteGameTests {
    @GameTest(template = "empty", batch = "vre_palette_vanilla_air", timeoutTicks = 1200)
    public static void caveAndVoidAirKeepNonAirBudgetAndNativeLighting(GameTestHelper helper) {
        var fast = PaletteBackendGameTests.fixture(helper, 0);
        var nativePath = PaletteBackendGameTests.fixture(helper, 4);
        var fixtures = List.of(fast, nativePath);
        int[] solidCounts = new int[2];
        for (var fixture : fixtures) {
            PaletteBackendGameTests.fill(fixture, true, 0xCA7E015EL, false);
            int[] observed = new int[4];
            for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var position = fixture.minimum().offset(x, y, z);
                if (fixture.level().getBlockState(position).isAir()) {
                    int variant = (x + y + z) % 3;
                    var state = switch (variant) {
                        case 0 -> Blocks.AIR.defaultBlockState();
                        case 1 -> Blocks.CAVE_AIR.defaultBlockState();
                        default -> Blocks.VOID_AIR.defaultBlockState();
                    };
                    fixture.level().setBlock(position, state, 18);
                    helper.assertTrue(fixture.chunk().getSection(fixture.firstIndex() + (y >> 4))
                            .getBlockState(x, y & 15, z) == state, "Actual mixed section stores each exact vanilla air state");
                    observed[variant]++;
                } else {
                    observed[3]++;
                    if (fixture == fast) solidCounts[y >> 4]++;
                }
            }
            for (int count : observed) helper.assertTrue(count > 0, "Fixture contains all three vanilla airs and solid stone");
            PaletteBackendGameTests.repackFixture(fixture);
        }
        var initial = PaletteBackendGameTests.fence(fixtures);
        CompletableFuture<?>[] finished = new CompletableFuture<?>[1];
        helper.startSequence().thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, initial, "Cave/void fixture lighting"))
                .thenExecute(() -> {
                    var first = PaletteSectionClearBackend.prepare(fast.level(), fast.chunk(), fast.firstIndex(),
                            state -> true, PaletteBackendGameTests.HOST);
                    var second = PaletteSectionClearBackend.prepare(fast.level(), fast.chunk(), fast.firstIndex() + 1,
                            state -> true, PaletteBackendGameTests.HOST);
                    helper.assertTrue(first.accepted() && second.accepted(),
                            "Exact vanilla cave/void air admits the production bulk path: " + first.rejection() + "/" + second.rejection());
                    helper.assertTrue(first.prepared().changedBlocks() == solidCounts[0]
                                    && second.prepared().changedBlocks() == solidCounts[1],
                            "AIR/CAVE_AIR/VOID_AIR do not consume the non-air mutation budget");
                    var report = PaletteSectionClearBackend.commitBatch(List.of(first.prepared(), second.prepared()), () -> true);
                    helper.assertTrue(report.applied() && report.changedBlocks() == solidCounts[0] + solidCounts[1],
                            "Actual vanilla-air batch charges exactly the solid cells");
                    PaletteBackendGameTests.clearNative(nativePath);
                    finished[0] = CompletableFuture.allOf(report.lightWork(), PaletteBackendGameTests.fence(fixtures));
                }).thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, finished[0], "Cave/void native and bulk propagation"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.compareLight(helper, fixtures, false);
                    for (var fixture : fixtures) {
                        PaletteBackendGameTests.assertMetadata(helper, fixture);
                        PaletteBackendGameTests.release(fixture);
                    }
                }).thenSucceed();
    }
    private VanillaAirPaletteGameTests() {}
}
