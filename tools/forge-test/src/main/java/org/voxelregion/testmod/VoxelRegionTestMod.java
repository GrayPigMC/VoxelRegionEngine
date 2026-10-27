/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.testmod;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
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
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

@Mod(VoxelRegionTestMod.ID)
public final class VoxelRegionTestMod {
    public static final String ID = "voxelregion_test";
    static final Logger LOGGER = LogUtils.getLogger();
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    static final RegistryObject<Item> CHARGE = ITEMS.register("region_charge", RegionChargeItem::new);

    public VoxelRegionTestMod() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        bus.addListener((RegisterCapabilitiesEvent event) -> event.register(ChunkReceipts.class));
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AdapterConfig.SPEC);
        MinecraftForge.EVENT_BUS.addGenericListener(LevelChunk.class, ChunkReceipts::attach);
        MinecraftForge.EVENT_BUS.addListener(this::commands);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> ForgeOperationRunner.stop());
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) ForgeOperationRunner.tick(server);
        }
    }

    private void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("vre").requires(source -> source.hasPermission(2));
        var giveFuse = Commands.argument("fuse", IntegerArgumentType.integer(0, 1200))
                .executes(ctx -> give(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), IntegerArgumentType.getInteger(ctx, "fuse"), false, true));
        var giveGenerate = Commands.argument("generate", BoolArgumentType.bool())
                .executes(ctx -> give(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), IntegerArgumentType.getInteger(ctx, "fuse"), BoolArgumentType.getBool(ctx, "generate"), true));
        giveGenerate.then(Commands.argument("clearFluids", BoolArgumentType.bool())
                .executes(ctx -> give(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), IntegerArgumentType.getInteger(ctx, "fuse"),
                        BoolArgumentType.getBool(ctx, "generate"), BoolArgumentType.getBool(ctx, "clearFluids"))));
        giveFuse.then(giveGenerate);
        root.then(Commands.literal("give").then(Commands.argument("radius", IntegerArgumentType.integer(1, 10000)).then(giveFuse)));
        var blastRadius = Commands.argument("radius", IntegerArgumentType.integer(1, 10000))
                .executes(ctx -> blast(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), false, true));
        var blastGenerate = Commands.argument("generate", BoolArgumentType.bool())
                .executes(ctx -> blast(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), BoolArgumentType.getBool(ctx, "generate"), true));
        blastGenerate.then(Commands.argument("clearFluids", BoolArgumentType.bool())
                .executes(ctx -> blast(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"),
                        BoolArgumentType.getBool(ctx, "generate"), BoolArgumentType.getBool(ctx, "clearFluids"))));
        blastRadius.then(blastGenerate);
        root.then(Commands.literal("blast").then(blastRadius));
        root.then(Commands.literal("status").executes(ctx -> {
            int count = 0;
            for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
                for (var task : OperationData.get(level).tasks) {
                    ctx.getSource().sendSuccess(() -> Component.literal(level.dimension().location() + " ").append(ForgeOperationRunner.status(task)), false);
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
        if (radius > AdapterConfig.MAX_RADIUS.get()) {
            source.sendFailure(Component.literal("Radius exceeds server maximum " + AdapterConfig.MAX_RADIUS.get()));
            return 0;
        }
        ItemStack stack = new ItemStack(CHARGE.get());
        stack.getOrCreateTag().putInt("Radius", radius);
        stack.getOrCreateTag().putInt("Fuse", fuse);
        stack.getOrCreateTag().putBoolean("Generate", generate);
        stack.getOrCreateTag().putBoolean("ClearFluids", clearFluids);
        var player = source.getPlayerOrException();
        if (!player.addItem(stack)) player.drop(stack, false);
        source.sendSuccess(() -> Component.literal("Region charge: radius=" + radius + ", fuse=" + fuse + " ticks, normal generation=" + generate
                + ", clearFluids=" + clearFluids + ". Use on a block to arm a stationary particle marker."), false);
        return 1;
    }

    private static int blast(CommandSourceStack source, int radius, boolean generate, boolean clearFluids) {
        UUID actor = source.getEntity() == null ? new UUID(0, 0) : source.getEntity().getUUID();
        try {
            var task = ForgeOperationRunner.submit(source.getLevel(), actor, BlockPos.containing(source.getPosition()), radius, 0, generate, clearFluids);
            source.sendSuccess(() -> ForgeOperationRunner.status(task), true);
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
            OperationData data = OperationData.get(level);
            for (var task : data.tasks) {
                if (!task.id.equals(id)) continue;
                if (action.equals("resume")) {
                    if (!ForgeOperationRunner.resume(task)) {
                        source.sendFailure(Component.literal("Cannot resume this state. Recovery-paused operations never replay automatically; inspect and cancel them."));
                        return 0;
                    }
                } else if (action.equals("cancel")) ForgeOperationRunner.cancel(task);
                else if (!task.terminal() && task.state != OperationData.State.PAUSED_RECOVERY)
                    ForgeOperationRunner.pause(task, OperationData.State.PAUSED, "Paused by administrator.");
                data.setDirty();
                source.sendSuccess(() -> ForgeOperationRunner.status(task), true);
                return 1;
            }
        }
        source.sendFailure(Component.literal("Unknown region operation."));
        return 0;
    }
}
