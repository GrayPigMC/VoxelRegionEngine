/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.testmod;

import org.voxelregion.forge.ForgeRegionEngine;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(VoxelRegionTestMod.ID)
public final class VoxelRegionTestMod {
    public static final String ID = "voxelregion_test";
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final RegistryObject<Item> CHARGE = ITEMS.register("region_charge", RegionChargeItem::new);

    public VoxelRegionTestMod() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        ForgeRegionEngine.install(bus);
        MinecraftForge.EVENT_BUS.addListener(this::commands);
    }

    private void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("vre").requires(source -> source.hasPermission(2));
        var giveFuse = Commands.argument("fuse", IntegerArgumentType.integer(0, 1200))
                .executes(ctx -> give(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), IntegerArgumentType.getInteger(ctx, "fuse"), true, true));
        var giveGenerate = Commands.argument("generate", BoolArgumentType.bool())
                .executes(ctx -> give(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), IntegerArgumentType.getInteger(ctx, "fuse"), BoolArgumentType.getBool(ctx, "generate"), true));
        var giveFluids = Commands.argument("clearFluids", BoolArgumentType.bool())
                .executes(ctx -> give(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), IntegerArgumentType.getInteger(ctx, "fuse"),
                        BoolArgumentType.getBool(ctx, "generate"), BoolArgumentType.getBool(ctx, "clearFluids")));
        giveFluids.then(Commands.argument("destroyBedrock", BoolArgumentType.bool())
                .executes(ctx -> give(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), IntegerArgumentType.getInteger(ctx, "fuse"),
                        BoolArgumentType.getBool(ctx, "generate"), BoolArgumentType.getBool(ctx, "clearFluids"), BoolArgumentType.getBool(ctx, "destroyBedrock"))));
        giveGenerate.then(giveFluids);
        giveFuse.then(giveGenerate);
        root.then(Commands.literal("give").then(Commands.argument("radius", IntegerArgumentType.integer(1, 10000)).then(giveFuse)));
        var blastRadius = Commands.argument("radius", IntegerArgumentType.integer(1, 10000))
                .executes(ctx -> blast(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), true, true));
        var blastGenerate = Commands.argument("generate", BoolArgumentType.bool())
                .executes(ctx -> blast(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), BoolArgumentType.getBool(ctx, "generate"), true));
        var blastFluids = Commands.argument("clearFluids", BoolArgumentType.bool())
                .executes(ctx -> blast(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"),
                        BoolArgumentType.getBool(ctx, "generate"), BoolArgumentType.getBool(ctx, "clearFluids")));
        blastFluids.then(Commands.argument("destroyBedrock", BoolArgumentType.bool())
                .executes(ctx -> blast(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"),
                        BoolArgumentType.getBool(ctx, "generate"), BoolArgumentType.getBool(ctx, "clearFluids"), BoolArgumentType.getBool(ctx, "destroyBedrock"))));
        blastGenerate.then(blastFluids);
        blastRadius.then(blastGenerate);
        root.then(Commands.literal("blast").then(blastRadius));
        root.then(Commands.literal("status").executes(ctx -> {
            int count = 0;
            for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
                for (var status : ForgeRegionEngine.statuses(level)) {
                    ctx.getSource().sendSuccess(() -> Component.literal(level.dimension().location() + " ").append(status.asComponent()), false);
                    count++;
                }
            }
            if (count == 0) ctx.getSource().sendSuccess(() -> Component.literal("No region operations."), false);
            return count;
        }));
        for (String action : new String[] { "pause", "resume", "cancel" })
            root.then(Commands.literal(action).then(Commands.argument("id", StringArgumentType.word())
                    .executes(ctx -> control(ctx.getSource(), StringArgumentType.getString(ctx, "id"), action))));
        event.getDispatcher().register(root);
    }

    private static int give(CommandSourceStack source, int radius, int fuse, boolean generate, boolean clearFluids) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return give(source, radius, fuse, generate, clearFluids, false);
    }
    private static int give(CommandSourceStack source, int radius, int fuse, boolean generate, boolean clearFluids,
            boolean destroyBedrock) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (radius > ForgeRegionEngine.maximumRadius()) {
            source.sendFailure(Component.literal("Radius exceeds server maximum " + ForgeRegionEngine.maximumRadius()));
            return 0;
        }
        ItemStack stack = new ItemStack(CHARGE.get());
        stack.getOrCreateTag().putInt("Radius", radius);
        stack.getOrCreateTag().putInt("Fuse", fuse);
        stack.getOrCreateTag().putBoolean("Generate", generate);
        stack.getOrCreateTag().putBoolean("ClearFluids", clearFluids);
        stack.getOrCreateTag().putBoolean("DestroyBedrock", destroyBedrock);
        var player = source.getPlayerOrException();
        if (!player.addItem(stack)) player.drop(stack, false);
        source.sendSuccess(() -> Component.literal("Region charge: radius=" + radius + ", fuse=" + fuse + " ticks, normal generation=" + generate
                + ", clearFluids=" + clearFluids + ", destroyBedrock=" + destroyBedrock + ". Use on a block to arm a stationary particle marker."), false);
        return 1;
    }

    private static int blast(CommandSourceStack source, int radius, boolean generate, boolean clearFluids) {
        return blast(source, radius, generate, clearFluids, false);
    }
    private static int blast(CommandSourceStack source, int radius, boolean generate, boolean clearFluids, boolean destroyBedrock) {
        UUID actor = source.getEntity() == null ? new UUID(0, 0) : source.getEntity().getUUID();
        try {
            var handle = ForgeRegionEngine.submitSphere(source.getLevel(), actor, BlockPos.containing(source.getPosition()), radius,
                    new ForgeRegionEngine.Options(0, generate, clearFluids, destroyBedrock));
            var status = ForgeRegionEngine.status(source.getLevel(), handle.id()).orElseThrow();
            source.sendSuccess(() -> status.asComponent(), true);
            return 1;
        } catch (IOException | RuntimeException ex) {
            source.sendFailure(Component.literal("Operation not accepted: " + ex.getMessage()));
            return 0;
        }
    }

    private static int control(CommandSourceStack source, String text, String action) {
        UUID id;
        try { id = UUID.fromString(text); }
        catch (IllegalArgumentException ex) { source.sendFailure(Component.literal("Expected operation UUID from /vre status.")); return 0; }
        for (ServerLevel level : source.getServer().getAllLevels()) {
            var result = ForgeRegionEngine.control(level, id,
                    ForgeRegionEngine.Control.valueOf(action.toUpperCase(java.util.Locale.ROOT)));
            if (result.status().isEmpty()) continue;
            if (!result.applied()) {
                source.sendFailure(Component.literal(result.reason()));
                return 0;
            }
            source.sendSuccess(() -> result.status().orElseThrow().asComponent(), true);
            return 1;
        }
        source.sendFailure(Component.literal("Unknown region operation."));
        return 0;
    }
}
