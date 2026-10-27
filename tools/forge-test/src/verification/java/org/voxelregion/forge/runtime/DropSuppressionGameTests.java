/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.voxelregion.forge.DropSuppression;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class DropSuppressionGameTests {
    @GameTest(template = "empty", batch = "vre_drop_container", timeoutTicks = 1200)
    public static void regionDropNoneRemovesChestWithoutInventoryEntities(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(7, 5, 7));
        UUID deletedToken = UUID.randomUUID(), normalToken = UUID.randomUUID();
        ChestBlockEntity original = filledChest(level, center, deletedToken);
        ItemEntity alreadyPresent = new ItemEntity(level, center.getX() + .5, center.getY() + .5, center.getZ() + .5,
                new ItemStack(Items.GOLD_INGOT));
        alreadyPresent.setNoGravity(true);
        helper.assertTrue(level.addFreshEntity(alreadyPresent), "Existing item entity fixture enters normally");
        var task = ForgeOperationRunner.submit(level, UUID.randomUUID(), center, 2, 0, false);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(task.state == OperationData.State.WRITTEN,
                "Container deletion completes: " + task.state + ": " + task.message)).thenExecute(() -> {
            helper.assertTrue(level.getBlockState(center).isAir() && level.getBlockEntity(center) == null && original.isRemoved(),
                    "Native container state and block entity lifecycle still complete");
            helper.assertTrue(countItems(level, deletedToken) == 0, "Full chest inventory creates no ItemEntity under DROP_NONE");
            helper.assertTrue(level.getEntity(alreadyPresent.getUUID()) == alreadyPresent && !alreadyPresent.isRemoved(),
                    "DROP_NONE never deletes pre-existing item entities");
            BlockPos outside = center.offset(6, 0, 0);
            ChestBlockEntity normal = filledChest(level, outside, normalToken);
            int expected = normal.getContainerSize() * 64;
            level.setBlock(outside, Blocks.AIR.defaultBlockState(), 18);
            helper.assertTrue(countItems(level, normalToken) == expected,
                    "Ordinary outside deletion still drops the complete inventory after the region operation");
        }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_drop_scope", timeoutTicks = 300)
    public static void scopeFiltersOnlyItsItemsAndRestoresAfterCallbackFailure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos position = helper.absolutePos(new BlockPos(6, 6, 6));
        var probe = ForgeRegistries.BLOCKS.getValue(ProbeBlocks.REMOVAL_PROBE);
        helper.assertTrue(probe != null, "Removal callback probe registered");
        level.setBlock(position, probe.defaultBlockState(), 18);
        boolean[] observed = new boolean[5];
        var oldAction = ProbeBlocks.removalAction;
        try {
            ProbeBlocks.removalAction = (world, at) -> {
                observed[0] = true;
                ItemEntity inside = item(level, at);
                observed[1] = !level.addFreshEntity(inside);
                observed[2] = level.addFreshEntity(item(level, at.offset(3, 0, 0)));
                ExperienceOrb xp = new ExperienceOrb(level, at.getX() + .5, at.getY() + .5, at.getZ() + .5, 3);
                observed[3] = level.addFreshEntity(xp);
                // Check admission policy on another thread only; never add an entity off-thread.
                observed[4] = !CompletableFuture.supplyAsync(() -> DropSuppression.suppresses(level, inside)).join();
                throw new IllegalStateException("VRE expected removal probe failure");
            };
            boolean failed = false;
            try { DropSuppression.setBlock(level, position, Blocks.AIR.defaultBlockState(), 18); }
            catch (RuntimeException expected) { failed = observed[0]; }
            helper.assertTrue(failed, "The actual native removal callback throws through the scoped writer");
        } finally {
            ProbeBlocks.removalAction = oldAction;
        }
        for (int i = 0; i < observed.length; i++) helper.assertTrue(observed[i], "Scoped admission contract case " + i);
        helper.assertTrue(level.addFreshEntity(item(level, position)),
                "Finally cleanup permits normal same-cell drops immediately after a failed write");

        // Exercise nested writes using the same reusable stack. Inner completion must not remove
        // the outer scope, and both cells return to normal after the outer write completes.
        BlockPos inner = position.offset(4, 0, 0);
        level.setBlock(position, probe.defaultBlockState(), 18);
        level.setBlock(inner, probe.defaultBlockState(), 18);
        boolean[] nested = new boolean[4];
        try {
            ProbeBlocks.removalAction = (world, at) -> {
                if (at.equals(inner)) {
                    nested[0] = !level.addFreshEntity(item(level, inner));
                    nested[1] = !level.addFreshEntity(item(level, position));
                } else {
                    DropSuppression.setBlock(level, inner, Blocks.AIR.defaultBlockState(), 18);
                    nested[2] = !level.addFreshEntity(item(level, position));
                    nested[3] = level.addFreshEntity(item(level, inner));
                }
            };
            DropSuppression.setBlock(level, position, Blocks.AIR.defaultBlockState(), 18);
        } finally {
            ProbeBlocks.removalAction = oldAction;
        }
        for (int i = 0; i < nested.length; i++) helper.assertTrue(nested[i], "Nested reusable scope contract case " + i);
        helper.assertTrue(level.addFreshEntity(item(level, position)) && level.addFreshEntity(item(level, inner)),
                "Both reusable frames release their admission restrictions after return");
        helper.succeed();
    }

    private static ChestBlockEntity filledChest(ServerLevel level, BlockPos position, UUID token) {
        level.setBlock(position, Blocks.CHEST.defaultBlockState(), 18);
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(position);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = new ItemStack(Items.DIAMOND, 64);
            stack.getOrCreateTag().putUUID("VreDropVerification", token);
            chest.setItem(slot, stack);
        }
        return chest;
    }

    private static int countItems(ServerLevel level, UUID token) {
        int count = 0;
        for (var entity : level.getAllEntities()) if (entity instanceof ItemEntity item) {
            var stack = item.getItem();
            var tag = stack.getTag();
            if (tag != null && tag.hasUUID("VreDropVerification") && token.equals(tag.getUUID("VreDropVerification")))
                count += stack.getCount();
        }
        return count;
    }

    private static ItemEntity item(ServerLevel level, BlockPos position) {
        return new ItemEntity(level, position.getX() + .5, position.getY() + .5, position.getZ() + .5,
                new ItemStack(Items.EMERALD));
    }
    private DropSuppressionGameTests() {}
}
