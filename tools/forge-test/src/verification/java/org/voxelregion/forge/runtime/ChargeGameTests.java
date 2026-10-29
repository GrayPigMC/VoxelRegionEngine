/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.testmod.VoxelRegionTestMod;

/** The reported R2000/fuse20 input reaches the real item and ticking runtime. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class ChargeGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_charge_test", Comparator.<UUID>naturalOrder());

    @GameTest(template = "empty", batch = "vre_charge_explicit_generation", timeoutTicks = 100)
    public static void explicitGenerationFalseSurvivesChargeAcceptance(GameTestHelper helper) {
        var level = helper.getLevel();
        var center = helper.absolutePos(new BlockPos(1, 3, 1));
        UUID actor = UUID.randomUUID();
        var player = new FakePlayer(level, new GameProfile(actor, "VREGenerationFlagFixture")) {
            @Override public boolean hasPermissions(int permission) { return permission <= 2; }
            @Override public void displayClientMessage(Component message, boolean overlay) {}
        };
        var stack = new ItemStack(VoxelRegionTestMod.CHARGE.get());
        stack.getOrCreateTag().putInt("Radius", 2);
        stack.getOrCreateTag().putInt("Fuse", 1200);
        stack.getOrCreateTag().putBoolean("Generate", false);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(center.below()), Direction.UP, center.below(), false);
        var result = stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(result == InteractionResult.CONSUME, "Explicit generation policy charge is accepted");
        var task = OperationData.get(level).tasks.stream().filter(t -> t.actor.equals(actor)).findFirst().orElseThrow();
        try {
            helper.assertTrue(task.wholeChunk && !task.generate && task.state == OperationData.State.FUSE,
                    "Explicit Generate=false keeps the complete-chunk path while disabling new terrain generation");
        } finally { ForgeOperationRunner.cancel(task); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_charge_fuse", timeoutTicks = 2400)
    public static void radius2000ChargeHonorsFuseThenClearsItsCenter(GameTestHelper helper) {
        var level = helper.getLevel();
        var center = new BlockPos(8, 200, 8008);
        var chunk = level.getChunkAt(center);
        UUID actor = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk.getPos(), 0, actor);
        var task = new OperationData.Task[1];
        boolean[] cleaned = {false};
        onCompletion(helper, () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            if (task[0] != null && !task[0].terminal()) ForgeOperationRunner.cancel(task[0]);
            level.getChunkSource().removeRegionTicket(HOLD, chunk.getPos(), 0, actor);
        });
        level.setBlock(center, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        level.setBlock(center.below(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        var player = new FakePlayer(level, new GameProfile(actor, "VREChargeFixture")) {
            @Override public boolean hasPermissions(int permission) { return permission <= 2; }
            @Override public void displayClientMessage(Component message, boolean overlay) {}
        };
        var stack = new ItemStack(VoxelRegionTestMod.CHARGE.get());
        stack.getOrCreateTag().putInt("Radius", 2000);
        stack.getOrCreateTag().putInt("Fuse", 20);
        stack.getOrCreateTag().putBoolean("ClearFluids", true);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(center.below()), Direction.UP, center.below(), false);
        InteractionResult result = stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(result == InteractionResult.CONSUME && stack.isEmpty(), "Successful noncreative item use consumes one charge");
        task[0] = OperationData.get(level).tasks.stream().filter(t -> t.actor.equals(actor)).findFirst().orElseThrow();
        helper.assertTrue(task[0].state == OperationData.State.FUSE && task[0].fuse == 20,
                "The actual item captures the requested twenty tick fuse");
        helper.assertTrue(task[0].wholeChunk && task[0].generate,
                "A charge without a legacy Generate NBT field defaults to complete chunks and generation enabled");
        helper.startSequence().thenIdle(18).thenExecute(() -> {
            helper.assertTrue(level.getBlockState(center).is(Blocks.STONE), "The charge does not remove its center before the fuse expires");
        }).thenWaitUntil(() -> helper.assertTrue(level.getBlockState(center).isAir(),
                "Loaded blast center clears after fuse, without waiting for distant unloaded corners: " + ForgeOperationRunner.status(task[0]).getString()))
                .thenExecute(() -> {
                    helper.assertTrue(task[0].changed > 0 && level.getBlockState(center.below()).is(Blocks.BEDROCK),
                            "The live runtime writes terrain and retains the protected support");
                    ForgeOperationRunner.cancel(task[0]);
                }).thenWaitUntil(() -> helper.assertTrue(task[0].unconfirmedFences == 0, "Cancelled fixture finishes its native completion fences"))
                .thenSucceed();
    }

    private static void onCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo test) {}
                    public void testPassed(GameTestInfo test) { cleanup.run(); }
                    public void testFailed(GameTestInfo test) { cleanup.run(); }
                });
                return;
            }
            throw new IllegalStateException("No GameTest cleanup hook");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private ChargeGameTests() {}
}
