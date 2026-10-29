/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import org.voxelregion.testmod.VoxelRegionTestMod;
import org.voxelregion.testmod.RegionChargeItem;
import java.util.UUID;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class RegionGameTests {
    private static final TicketType<UUID> NATIVE_FIXTURE_HOLD = TicketType.create(
            "vre_native_identity_fixture", Comparator.<UUID>naturalOrder());
    @GameTest(template = "empty", batch = "vre_fluids_default", timeoutTicks = 100000)
    public static void defaultPolicyClearsFluids(GameTestHelper helper) throws IOException {
        fluidCase(helper, AdapterConfig.RESISTANCE_LIMIT.get(), true);
    }

    @GameTest(template = "empty", batch = "vre_fluids", timeoutTicks = 100000)
    public static void freeLiquidsAndWaterloggedSolidsClear(GameTestHelper helper) throws IOException {
        fluidCase(helper, 100.0, true);
    }

    @GameTest(template = "empty", batch = "vre_fluids_preserved", timeoutTicks = 100000)
    public static void disabledFluidClearingRetainsLiquids(GameTestHelper helper) throws IOException {
        fluidCase(helper, 100.0, false);
    }

    private static void fluidCase(GameTestHelper helper, double resistanceLimit, boolean clearFluids) throws IOException {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(8, 8, 8));
        BlockPos waterSource = center.offset(-3, 0, -3);
        BlockPos flowingWater = center.offset(-3, 0, 0);
        BlockPos waterloggedSlab = center.offset(-3, 0, 3);
        BlockPos lavaSource = center.offset(3, 0, -3);
        BlockPos flowingLava = center.offset(3, 0, 0);
        BlockPos protectedSlab = center.offset(3, 0, 3);
        BlockPos outsideWater = center.offset(-7, 0, -3);
        BlockPos outsideLava = center.offset(7, 0, -3);
        BlockPos outsideWaterlogged = center.offset(-7, 0, 3);
        BlockState wetSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState protectedWetSlab = ForgeRegistries.BLOCKS.getValue(ProbeBlocks.PROTECTED_WATERLOGGED)
                .defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        pocket(level, waterSource, Blocks.WATER.defaultBlockState());
        pocket(level, flowingWater, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        pocket(level, lavaSource, Blocks.LAVA.defaultBlockState());
        pocket(level, flowingLava, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        pocket(level, waterloggedSlab, wetSlab);
        pocket(level, protectedSlab, protectedWetSlab);
        pocket(level, outsideWater, Blocks.WATER.defaultBlockState());
        pocket(level, outsideLava, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        pocket(level, outsideWaterlogged, wetSlab);
        // Freeze fixture flow ticks, not production ticks: this test measures direct
        // region writes without natural flow decay, refilling or water/lava interaction.
        level.getFluidTicks().clearArea(new BoundingBox(center.getX() - 9, center.getY() - 2, center.getZ() - 5,
                center.getX() + 9, center.getY() + 2, center.getZ() + 5));
        helper.assertTrue(level.getFluidState(waterSource).isSource() && level.getFluidState(lavaSource).isSource(),
                "Water/lava source fixtures are sources");
        helper.assertTrue(!level.getFluidState(flowingWater).isEmpty() && !level.getFluidState(flowingWater).isSource()
                        && !level.getFluidState(flowingLava).isEmpty() && !level.getFluidState(flowingLava).isSource(),
                "Flowing water/lava fixtures contain non-source fluid");
        helper.assertTrue(!level.getFluidState(waterloggedSlab).isEmpty() && !level.getFluidState(protectedSlab).isEmpty(),
                "Waterlogged fixtures contain water before mutation");
        Map<BlockPos, BlockState> originalLiquids = new LinkedHashMap<>();
        for (BlockPos position : List.of(waterSource, flowingWater, lavaSource, flowingLava))
            originalLiquids.put(position, level.getBlockState(position));

        double oldLimit = AdapterConfig.RESISTANCE_LIMIT.get();
        OperationData.Task task;
        try {
            // Vanilla water/lava resistance is exactly 100 in 1.20.1. Equality would
            // preserve them under the old solid-only threshold rule; solids still obey it.
            AdapterConfig.RESISTANCE_LIMIT.set(resistanceLimit);
            // Exercise both the backwards-compatible default-true API and the explicit false API.
            task = clearFluids ? ForgeOperationRunner.submit(level, UUID.randomUUID(), center, 6, 0, false)
                    : ForgeOperationRunner.submit(level, UUID.randomUUID(), center, 6, 0, false, false);
        } finally { AdapterConfig.RESISTANCE_LIMIT.set(oldLimit); }
        helper.assertTrue(task.clearFluids == clearFluids, "Task captures its immutable fluid policy");
        CompoundTag saved = task.save();
        helper.assertTrue(OperationData.Task.load(saved).clearFluids == clearFluids, "Fluid policy survives task NBT round trip");
        CompoundTag legacy = saved.copy();
        legacy.remove("clearFluids");
        helper.assertTrue(OperationData.Task.load(legacy).clearFluids, "Old task NBT without fluid policy defaults to true");
        CompoundTag intent = NbtIo.readCompressed(level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/voxelregion_test_intents").resolve(task.id + ".nbt").toFile());
        helper.assertTrue(intent.contains("clearFluids") && intent.getBoolean("clearFluids") == clearFluids,
                "Durable intent records the selected fluid policy before mutation");
        ItemStack charge = new ItemStack(VoxelRegionTestMod.CHARGE.get());
        helper.assertTrue(RegionChargeItem.clearsFluids(charge), "Legacy item NBT defaults to clearing fluids");
        charge.getOrCreateTag().putBoolean("ClearFluids", clearFluids);
        helper.assertTrue(RegionChargeItem.clearsFluids(charge) == clearFluids, "Charge item reads the explicit fluid policy");
        helper.assertTrue(ForgeOperationRunner.status(task).getString().contains("clearFluids=" + clearFluids),
                "Operation status exposes fluid policy");
        List<BlockPos> cleared = List.of(waterSource, flowingWater, lavaSource, flowingLava, waterloggedSlab);
        long started = System.nanoTime();
        helper.startSequence().thenWaitUntil(() -> {
            String status = ForgeOperationRunner.status(task).getString() + " " + ForgeOperationRunner.progress(task);
            if (task.state == OperationData.State.FAILED || System.nanoTime() - started > 20_000_000_000L)
                helper.fail("Fluid operation failed or exceeded 20 seconds of native completion time: " + status);
            helper.assertTrue(task.state == OperationData.State.WRITTEN, "Fluid operation finishes: " + status);
        }).thenExecute(() -> {
            if (clearFluids) {
                for (BlockPos position : cleared) {
                    helper.assertTrue(level.getBlockState(position).isAir(), "Fluid/host cleared to air at " + position);
                    helper.assertTrue(level.getFluidState(position).isEmpty(), "No fluid remains at " + position);
                }
            } else {
                for (var entry : originalLiquids.entrySet()) {
                    helper.assertTrue(level.getBlockState(entry.getKey()) == entry.getValue(), "Pure liquid state retained at " + entry.getKey());
                    helper.assertTrue(!level.getFluidState(entry.getKey()).isEmpty(), "Pure fluid remains at " + entry.getKey());
                }
                helper.assertTrue(level.getBlockState(waterloggedSlab).is(Blocks.WATER) && level.getFluidState(waterloggedSlab).isSource(),
                        "Destroyable waterlogged solid is removed while its source water remains");
            }
            helper.assertTrue(level.getBlockState(protectedSlab) == protectedWetSlab && !level.getFluidState(protectedSlab).isEmpty(),
                    "Unbreakable waterlogged host and its water are retained");
            helper.assertTrue(level.getBlockState(outsideWater).is(Blocks.WATER) && level.getFluidState(outsideWater).isSource(),
                    "Outside water source retained");
            helper.assertTrue(level.getBlockState(outsideLava).is(Blocks.LAVA) && !level.getFluidState(outsideLava).isEmpty(),
                    "Outside flowing lava retained");
            helper.assertTrue(level.getBlockState(outsideWaterlogged) == wetSlab && !level.getFluidState(outsideWaterlogged).isEmpty(),
                    "Outside waterlogged solid retained");
            RuntimeBridge.LOGGER.info("Fluid region validation: clearFluids={}, inside cells match selected policy; protected waterlogged host and 3 outside cells retained; resistance threshold={}",
                    task.clearFluids, task.resistance);
        }).thenSucceed();
    }

    private static void pocket(ServerLevel level, BlockPos position, BlockState content) {
        for (Direction direction : Direction.values()) level.setBlock(position.relative(direction), Blocks.BEDROCK.defaultBlockState(), 18);
        level.setBlock(position, content, 18);
    }

    @GameTest(template = "empty", batch = "vre_mod_callbacks", timeoutTicks = 400)
    public static void contextualCallbacksCannotDeleteReplacement(GameTestHelper helper) throws IOException {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(7, 5, 7));
        BlockPos unknown = center.offset(1, 0, 0);
        var switchingBlock = ForgeRegistries.BLOCKS.getValue(ProbeBlocks.SWITCHING);
        var unknownBlock = ForgeRegistries.BLOCKS.getValue(ProbeBlocks.UNKNOWN);
        helper.assertTrue(switchingBlock != null && unknownBlock != null, "Verification probe blocks registered");
        level.setBlock(center, switchingBlock.defaultBlockState(), 18);
        level.setBlock(unknown, unknownBlock.defaultBlockState(), 18);
        var task = ForgeOperationRunner.submit(level, UUID.randomUUID(), center, 2, 0, false);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(task.state == OperationData.State.WRITTEN,
                "Callback operation finishes: " + task.state + ": " + task.message)).thenExecute(() -> {
            helper.assertTrue(level.getBlockState(center).is(Blocks.END_GATEWAY), "Fresh protected state installed by a callback survives stale decision");
            helper.assertTrue(level.getBlockEntity(center) instanceof TheEndGatewayBlockEntity, "Replacement protected block entity survives");
            helper.assertTrue(level.getBlockState(unknown).is(unknownBlock), "Unknown NaN resistance fails closed");
        }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_native", timeoutTicks = 100000)
    public static void sphereProtectionAndNativeCleanup(GameTestHelper helper) throws IOException {
        // The whole-column oracle needs a world without unrelated aquifer flow into
        // its cleared shaft. Adding tests moves GameTest templates across natural
        // terrain; that must not change this deterministic native-write fixture.
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_backend_fixture")));
        helper.assertTrue(level != null, "Dedicated native-cleanup fixture dimension exists");
        BlockPos center = helper.absolutePos(new BlockPos(7, 6, 7));
        ChunkPos fixtureChunk = new ChunkPos(center);
        UUID fixtureHolder = UUID.randomUUID();
        OperationData.Task[] fixtureTask = new OperationData.Task[1];
        level.getChunkSource().addRegionTicket(NATIVE_FIXTURE_HOLD, fixtureChunk, 0, fixtureHolder);
        WholeChunkRunnerGameTests.onCompletion(helper, () -> {
            if (fixtureTask[0] != null && !fixtureTask[0].terminal()) ForgeOperationRunner.cancel(fixtureTask[0]);
            level.getChunkSource().removeRegionTicket(NATIVE_FIXTURE_HOLD, fixtureChunk, 0, fixtureHolder);
        });
        RuntimeBridge.LOGGER.info("Heightmap fixture before isolation: center={}, {}", center, columnDescription(level, center));
        // A GameTest structure only bounds its template; generated terrain above it is
        // not necessarily empty. Isolate the complete assertion column using native writes.
        for (int y = center.getY() + 4; y < level.getMaxBuildHeight(); y++)
            level.setBlock(new BlockPos(center.getX(), y, center.getZ()), Blocks.AIR.defaultBlockState(), 18);
        for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++)
            level.setBlock(center.offset(x, y, z), Blocks.STONE.defaultBlockState(), 18);
        BlockPos outside = center.offset(4, 0, 0);
        level.setBlock(outside, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        level.setBlock(center, Blocks.BEDROCK.defaultBlockState(), 18);
        BlockPos chest = center.above();
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 18);
        helper.assertTrue(level.getBlockEntity(chest) instanceof ChestBlockEntity, "Chest fixture has a block entity");
        BlockPos gateway = center.offset(-1, 0, 0);
        level.setBlock(gateway, Blocks.END_GATEWAY.defaultBlockState(), 18);
        helper.assertTrue(level.getBlockEntity(gateway) instanceof TheEndGatewayBlockEntity, "Gateway fixture has a block entity");
        var originalGateway = level.getBlockEntity(gateway);
        RuntimeBridge.LOGGER.info("Heightmap fixture initialized: center={}, expected={}, {}", center, center.getY() + 4,
                columnDescription(level, center));
        helper.assertTrue(highestNonAir(level, center).getY() == center.getY() + 3,
                "Isolated column starts at fixture stone top: " + columnDescription(level, center));
        helper.assertTrue(level.getHeight(Heightmap.Types.WORLD_SURFACE, center.getX(), center.getZ()) == center.getY() + 4,
                "Initial native heightmap matches fixture stone top: " + columnDescription(level, center));
        var task = ForgeOperationRunner.submit(level, UUID.randomUUID(), center, 3, 0, false);
        fixtureTask[0] = task;
        long started = System.nanoTime();
        helper.startSequence().thenWaitUntil(() -> {
            if (task.state == OperationData.State.FAILED || System.nanoTime() - started > 20_000_000_000L)
                helper.fail("Native operation failed or exceeded 20 seconds: " + task.state + ": " + task.message);
            helper.assertTrue(task.state == OperationData.State.WRITTEN,
                    "Operation finishes native writes: " + task.state + ": " + task.message);
        }).thenExecute(() -> {
            for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++) {
                BlockPos pos = center.offset(x, y, z);
                if (pos.equals(center) || pos.equals(gateway)) continue;
                boolean inside = (x * x + y * y + z * z) <= 9;
                helper.assertTrue(inside ? level.getBlockState(pos).isAir() : level.getBlockState(pos).is(Blocks.STONE),
                        "Exact center-based sphere at " + pos);
            }
            helper.assertTrue(level.getBlockState(center).is(Blocks.BEDROCK), "Bedrock retained");
            helper.assertTrue(level.getBlockEntity(gateway) == originalGateway, "Protected gateway block entity retained");
            helper.assertTrue(level.getBlockEntity(chest) == null, "Removed chest leaves no block entity");
            helper.assertTrue(level.getBlockState(outside).is(Blocks.DIAMOND_BLOCK), "Outside block retained");
            BlockPos highest = highestNonAir(level, center);
            RuntimeBridge.LOGGER.info("Heightmap after region writes: center={}, expected={}, {}", center, center.getY() + 1,
                    columnDescription(level, center));
            helper.assertTrue(highest.equals(center) && level.getBlockState(highest).is(Blocks.BEDROCK),
                    "Full column scan ends at protected bedrock: " + columnDescription(level, center));
            helper.assertTrue(level.getHeight(Heightmap.Types.WORLD_SURFACE, center.getX(), center.getZ()) == center.getY() + 1,
                    "Heightmap falls to protected bedrock, expected=" + (center.getY() + 1) + ": " + columnDescription(level, center));
            var receipt = ChunkReceipts.of(level.getChunkAt(center));
            helper.assertTrue(receipt.state(task.id) == ChunkReceipts.COMPLETE, "Chunk carries operation completion receipt");
            CompoundTag serialized = receipt.serializeNBT();
            ChunkReceipts restored = new ChunkReceipts();
            restored.deserializeNBT(serialized);
            helper.assertTrue(restored.state(task.id) == ChunkReceipts.COMPLETE, "Receipt survives NBT round trip");
            boolean rejectedRegression = false;
            try { restored.mark(task.id, ChunkReceipts.STARTED); }
            catch (IllegalStateException expected) { rejectedRegression = true; }
            helper.assertTrue(rejectedRegression, "Completed receipt cannot regress to STARTED");
            CompoundTag malformed = serialized.copy();
            malformed.putByte(task.id.toString(), (byte) 99);
            boolean rejectedUnknown = false;
            try { restored.deserializeNBT(malformed); }
            catch (IllegalStateException expected) { rejectedUnknown = true; }
            helper.assertTrue(rejectedUnknown && restored.state(task.id) == ChunkReceipts.COMPLETE,
                    "Unknown receipt states reject atomically without erasing previous evidence");
            level.setBlock(chest, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
            helper.assertTrue(!ForgeOperationRunner.resume(task), "Completed operation cannot replay");
        }).thenIdle(5).thenExecute(() -> helper.assertTrue(level.getBlockState(chest).is(Blocks.GOLD_BLOCK),
                "Later construction survives completed task")).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_recovery", timeoutTicks = 100)
    public static void cancellationAndRecoveryDoNotReplay(GameTestHelper helper) throws IOException {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(7, 5, 7));
        level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        var cancelled = ForgeOperationRunner.submit(level, UUID.randomUUID(), pos, 2, 60, false);
        ForgeOperationRunner.cancel(cancelled);
        helper.assertTrue(!ForgeOperationRunner.resume(cancelled), "Cancelled task cannot replay");
        var unfinished = ForgeOperationRunner.submit(level, UUID.randomUUID(), pos, 2, 60, false);
        var restored = OperationData.load(OperationData.get(level).save(new CompoundTag()));
        var recovered = restored.tasks.stream().filter(task -> task.id.equals(unfinished.id)).findFirst().orElseThrow();
        helper.assertTrue(recovered.state == OperationData.State.RECOVERING, "Interrupted task loads into receipt validation; its SavedData cursor does not authorize replay");
        helper.assertTrue(!ForgeOperationRunner.resume(recovered), "Uncertain recovery cannot replay against later construction");
        ForgeOperationRunner.cancel(unfinished);
        helper.startSequence().thenIdle(5).thenExecute(() -> helper.assertTrue(level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK),
                "Cancellation and recovery leave construction unchanged")).thenSucceed();
    }

    private static BlockPos highestNonAir(ServerLevel level, BlockPos column) {
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockPos position = new BlockPos(column.getX(), y, column.getZ());
            if (!level.getBlockState(position).isAir()) return position;
        }
        return new BlockPos(column.getX(), level.getMinBuildHeight() - 1, column.getZ());
    }

    private static String columnDescription(ServerLevel level, BlockPos column) {
        BlockPos highest = highestNonAir(level, column);
        return "heightmap=" + level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ())
                + ", highestNonAirY=" + highest.getY() + ", highestBlock=" + ForgeRegistries.BLOCKS.getKey(level.getBlockState(highest).getBlock());
    }
}
