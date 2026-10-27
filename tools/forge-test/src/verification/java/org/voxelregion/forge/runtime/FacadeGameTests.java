/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ForgeRegionEngine;

/** Public adapter boundary: accepted immutable options and typed durable controls. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class FacadeGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void publicFacadeKeepsDetachedSnapshotsAndControls(GameTestHelper helper) throws IOException {
        var level = helper.getLevel();
        var handle = ForgeRegionEngine.submitSphere(level, UUID.randomUUID(), helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)),
                3, new ForgeRegionEngine.Options(100, false, false));
        var original = ForgeRegionEngine.status(level, handle.id()).orElseThrow();
        helper.assertTrue(handle.dimension().equals(level.dimension().location()) && !original.clearFluids(), "Handle and fluid policy are public immutable values");
        var paused = ForgeRegionEngine.control(level, handle.id(), ForgeRegionEngine.Control.PAUSE);
        helper.assertTrue(paused.applied() && paused.status().orElseThrow().state() == ForgeRegionEngine.State.PAUSED,
                "Public pause changes the durable runtime state");
        helper.assertTrue(original.state() == ForgeRegionEngine.State.FUSE, "An earlier status snapshot cannot mutate with runtime state");
        helper.assertTrue(ForgeRegionEngine.control(level, handle.id(), ForgeRegionEngine.Control.RESUME).applied(), "Valid paused work can resume");
        helper.assertTrue(ForgeRegionEngine.control(level, handle.id(), ForgeRegionEngine.Control.CANCEL).applied(), "Cancellation is applied once");
        helper.assertTrue(!ForgeRegionEngine.control(level, handle.id(), ForgeRegionEngine.Control.RESUME).applied(), "Terminal work cannot resume");
        helper.assertTrue(!ForgeRegionEngine.control(level, UUID.randomUUID(), ForgeRegionEngine.Control.CANCEL).applied(), "Unknown ids do not mutate other tasks");
        helper.assertTrue(ForgeRegionEngine.Options.DEFAULT.clearFluids() && !ForgeRegionEngine.Options.DEFAULT.generateMissingChunks(),
                "Public defaults retain clearFluids=true and loaded-only execution");
        helper.succeed();
    }
}
