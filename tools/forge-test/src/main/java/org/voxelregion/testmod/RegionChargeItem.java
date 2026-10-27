/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.testmod;

import org.voxelregion.forge.ForgeRegionEngine;

import java.io.IOException;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class RegionChargeItem extends Item {
    RegionChargeItem() { super(new Item.Properties().stacksTo(1)); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !player.hasPermissions(2)) return InteractionResult.FAIL;
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        var tag = context.getItemInHand().getTag();
        if (tag == null || !tag.contains("Radius")) {
            player.displayClientMessage(Component.literal("Configure a charge with /vre give <radius> <fuseTicks> [generate] [clearFluids]."), false);
            return InteractionResult.FAIL;
        }
        ServerLevel level = (ServerLevel) context.getLevel();
        BlockPos center = context.getClickedPos().relative(context.getClickedFace());
        try {
            var handle = ForgeRegionEngine.submitSphere(level, player.getUUID(), center, tag.getInt("Radius"),
                    new ForgeRegionEngine.Options(tag.getInt("Fuse"), tag.getBoolean("Generate"), clearsFluids(context.getItemInHand())));
            level.playSound(null, center, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
            player.displayClientMessage(ForgeRegionEngine.status(level, handle.id()).orElseThrow().asComponent(), false);
            if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
            return InteractionResult.CONSUME;
        } catch (IOException | RuntimeException ex) {
            player.displayClientMessage(Component.literal("Charge not armed: " + ex.getMessage()), false);
            return InteractionResult.FAIL;
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        var tag = stack.getTag();
        if (tag != null) tooltip.add(Component.literal("Radius " + tag.getInt("Radius") + ", fuse " + tag.getInt("Fuse")
                + " ticks; generation " + tag.getBoolean("Generate") + "; clearFluids " + clearsFluids(stack)));
        tooltip.add(Component.literal("Requires permission level 2. Stationary charge; server selects neighbor physics."));
    }

    public static boolean clearsFluids(ItemStack stack) {
        var tag = stack.getTag();
        return tag == null || !tag.contains("ClearFluids") || tag.getBoolean("ClearFluids");
    }
}
