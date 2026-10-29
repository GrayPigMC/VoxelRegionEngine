/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class BedrockPolicyGameTests {
    @GameTest(template = "empty", batch = "vre_bedrock_policy", timeoutTicks = 100000)
    public static void explicitBedrockDestructionPreservesOtherProtectionAndDurablePolicy(GameTestHelper helper) throws Exception {
        var fixtures = List.of(PaletteBackendGameTests.fixture(helper, 0), PaletteBackendGameTests.fixture(helper, 4));
        var tasks = new ArrayList<OperationData.Task>();
        WholeChunkRunnerGameTests.onCompletion(helper, () -> {
            for (var task : tasks) if (!task.terminal()) ForgeOperationRunner.cancel(task);
            fixtures.forEach(PaletteBackendGameTests::release);
        });
        for (int i = 0; i < fixtures.size(); i++) {
            var fixture = fixtures.get(i);
            var p = fixture.minimum().offset(2, 2, 2);
            fixture.level().setBlock(p, Blocks.BEDROCK.defaultBlockState(), 18);
            fixture.level().setBlock(p.offset(1, 0, 0), Blocks.END_PORTAL_FRAME.defaultBlockState(), 18);
            fixture.level().setBlock(p.offset(2, 0, 0), Blocks.STONE.defaultBlockState(), 18);
            var handle = ForgeRegionEngine.submit(fixture.level(), UUID.randomUUID(),
                    new RegionGeometry.Box(new IntBox(p.getX(), p.getY(), p.getZ(), p.getX() + 3, p.getY() + 1, p.getZ() + 1)),
                    new ForgeRegionEngine.Options(0, false, true, i == 1));
            var task = OperationData.get(fixture.level()).tasks.stream().filter(t -> t.id.equals(handle.id())).findFirst().orElseThrow();
            tasks.add(task);
            var restored = OperationData.Task.load(task.save());
            helper.assertTrue(restored.destroyBedrock == (i == 1) && restored.intentHash().equals(task.intentHash()),
                    "Bedrock policy is part of the durable immutable intent");
            if (i == 0) {
                var legacy = task.save(); legacy.remove("destroyBedrock");
                var old = OperationData.Task.load(legacy);
                helper.assertTrue(!old.destroyBedrock && old.intentHash().equals(task.intentHash()),
                        "Old stored tasks preserve bedrock and their original intent identity");
            }
        }
        helper.assertTrue(!ForgeRegionEngine.Options.DEFAULT.destroyBedrock()
                        && !new ForgeRegionEngine.Options(0, true, true).destroyBedrock(),
                "Public defaults and old constructors preserve bedrock");
        long start = System.nanoTime();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(tasks.stream().allMatch(OperationData.Task::terminal)
                        || System.nanoTime() - start > 40_000_000_000L, "Waiting for actual light/save acknowledgements"))
                .thenExecute(() -> {
                    for (int i = 0; i < fixtures.size(); i++) {
                        var fixture = fixtures.get(i); var p = fixture.minimum().offset(2, 2, 2);
                        helper.assertTrue(tasks.get(i).state == OperationData.State.WRITTEN, "Bedrock policy operation completed");
                        helper.assertTrue(fixture.level().getBlockState(p).is(i == 0 ? Blocks.BEDROCK : Blocks.AIR),
                                "Existing bedrock obeys only the explicit per-operation option");
                        helper.assertTrue(fixture.level().getBlockState(p.offset(1, 0, 0)).is(Blocks.END_PORTAL_FRAME),
                                "Destroying bedrock does not authorize other protected blocks");
                        helper.assertTrue(fixture.level().getBlockState(p.offset(2, 0, 0)).isAir(), "Ordinary terrain is removed in both modes");
                    }
                }).thenSucceed();
    }
    private BedrockPolicyGameTests() {}
}
