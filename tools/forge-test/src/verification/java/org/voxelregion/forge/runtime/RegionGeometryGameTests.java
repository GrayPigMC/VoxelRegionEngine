/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.propagation.FrozenOcclusion;

/** Public submission path, independent block-center oracle and durable geometry roundtrip. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class RegionGeometryGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_geometry_api_test", Comparator.<UUID>naturalOrder());
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", batch = "vre_geometry_api_clear", timeoutTicks = 800)
    public static void cylinderMinusBoxClearsOnlySelectedCellsAndFluids(GameTestHelper helper) throws IOException {
        difference(helper, 1760, true);
    }

    @GameTest(template = "empty", batch = "vre_geometry_api_water", timeoutTicks = 800)
    public static void cylinderMinusBoxPreservesInternalBoxAndWaterPolicy(GameTestHelper helper) throws IOException {
        difference(helper, 1800, false);
    }

    private static void difference(GameTestHelper helper, int chunkX, boolean clearFluids) throws IOException {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_flat")));
        helper.assertTrue(level != null, "Dedicated flat fixture exists");
        ChunkPos chunk = new ChunkPos(chunkX, 1600);
        UUID ticket = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk, 0, ticket);
        level.getChunk(chunk.x, chunk.z);
        int baseX = chunk.getMinBlockX(), baseZ = chunk.getMinBlockZ();
        Point3 center = Point3.of(baseX + 8.5, 66.5, baseZ + 8.5);
        IntBox keep = new IntBox(baseX + 7, 65, baseZ + 7, baseX + 10, 68, baseZ + 10);
        RegionGeometry geometry = new RegionGeometry.Combined(RegionGeometry.BooleanOperation.DIFFERENCE,
                new RegionGeometry.Column(center, 11, 64, 70), new RegionGeometry.Box(keep));
        BlockPos water = new BlockPos(baseX + 12, 65, baseZ + 8);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        long selected = 0;
        for (int x = 2; x <= 14; x++) for (int z = 2; z <= 14; z++) for (int y = 63; y <= 70; y++) {
            position.set(baseX + x, y, baseZ + z);
            level.setBlock(position, Blocks.STONE.defaultBlockState(), FLAGS);
            if (selected(center, keep, position)) selected++;
        }
        level.setBlock(water, Blocks.WATER.defaultBlockState(), FLAGS);
        // Isolate the requested direct fluid policy from later vanilla flow. No
        // test tick suppression is installed; only these newly scheduled fixture ticks are removed.
        level.getFluidTicks().clearArea(new BoundingBox(baseX, 63, baseZ, baseX + 15, 70, baseZ + 15));
        var oldPhysics = AdapterConfig.PHYSICS.get();
        var oldPropagation = AdapterConfig.PROPAGATION.get();
        double oldDamage = AdapterConfig.ENTITY_DAMAGE.get();
        ForgeRegionEngine.Handle handle;
        try {
            AdapterConfig.PHYSICS.set(AdapterConfig.Physics.NONE);
            AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.NONE);
            AdapterConfig.ENTITY_DAMAGE.set(0.0);
            handle = ForgeRegionEngine.submit(level, UUID.randomUUID(), geometry,
                    new ForgeRegionEngine.Options(0, false, clearFluids));
        } finally {
            AdapterConfig.PHYSICS.set(oldPhysics);
            AdapterConfig.PROPAGATION.set(oldPropagation);
            AdapterConfig.ENTITY_DAMAGE.set(oldDamage);
        }
        var intent = NbtIo.readCompressed(level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/voxelregion_test_intents/" + handle.id() + ".nbt").toFile());
        RegionGeometry persisted = RegionGeometry.load(intent.getCompound("geometry"));
        helper.assertTrue(persisted.equals(geometry) && persisted.canonical().equals(geometry.canonical()),
                "Public acceptance durably stores the complete cylinder/difference/box expression");
        var task = OperationData.get(level).tasks.stream().filter(value -> value.id.equals(handle.id())).findFirst().orElseThrow();
        helper.assertTrue(OperationData.Task.load(task.save()).intentHash().equals(task.intentHash()),
                "SavedData reconstruction preserves immutable geometry identity");
        long expectedChanges = selected - (clearFluids ? 0 : 1);
        helper.startSequence().thenWaitUntil(() -> {
            var status = ForgeRegionEngine.status(level, handle.id()).orElseThrow();
            helper.assertTrue(status.state() == ForgeRegionEngine.State.WRITTEN,
                    "Public geometry operation finishes native settling: " + status.description());
        }).thenExecute(() -> {
            try {
                BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
                for (int x = 2; x <= 14; x++) for (int z = 2; z <= 14; z++) for (int y = 63; y <= 70; y++) {
                    probe.set(baseX + x, y, baseZ + z);
                    var actual = level.getBlockState(probe);
                    boolean remove = selected(center, keep, probe);
                    boolean valid = probe.equals(water) && !clearFluids ? actual.is(Blocks.WATER)
                            : remove ? actual.isAir() : actual.is(Blocks.STONE);
                    helper.assertTrue(valid, "Independent cylinder-minus-box oracle at " + probe + ": " + actual);
                }
                var status = ForgeRegionEngine.status(level, handle.id()).orElseThrow();
                helper.assertTrue(status.changed() == expectedChanges && status.unloadedChunks() == 0,
                        "Public counters match exactly selected direct writes and preserve the inner box");
            } finally { level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, ticket); }
        }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_geometry_nbt", timeoutTicks = 100)
    public static void everyGeometryKindRoundtripsWithExactNegativeCoordinates(GameTestHelper helper) {
        RegionGeometry ball = new RegionGeometry.Ball(new Point3(-3, 1, -1), 7);
        RegionGeometry column = new RegionGeometry.Column(new Point3(-1, 0, -3), 5, -3, 4);
        RegionGeometry box = new RegionGeometry.Box(new IntBox(-3, -2, -3, 1, 3, 2));
        for (RegionGeometry.BooleanOperation operation : RegionGeometry.BooleanOperation.values()) {
            RegionGeometry expression = new RegionGeometry.Combined(operation, ball,
                    new RegionGeometry.Combined(RegionGeometry.BooleanOperation.INTERSECTION, column, box));
            RegionGeometry restored = RegionGeometry.load(expression.save());
            helper.assertTrue(restored.equals(expression) && restored.canonical().equals(expression.canonical()),
                    "All geometry fields survive typed NBT: " + operation);
            for (int x = -6; x <= 4; x++) for (int y = -5; y <= 5; y++) for (int z = -6; z <= 4; z++) {
                long dx = 2L * x + 4, dy = 2L * y, dz = 2L * z + 2;
                boolean inBall = dx * dx + dy * dy + dz * dz <= 49;
                long cx = 2L * x + 2, cz = 2L * z + 4;
                boolean other = cx * cx + cz * cz <= 25 && y >= -3 && y < 4
                        && x >= -3 && x < 1 && y >= -2 && y < 3 && z >= -3 && z < 2;
                boolean expected = switch (operation) {
                    case UNION -> inBall || other;
                    case INTERSECTION -> inBall && other;
                    case DIFFERENCE -> inBall && !other;
                };
                helper.assertTrue(restored.volume().contains(x, y, z) == expected,
                        "Negative half-grid roundtrip agrees with independent squared-distance/interval oracle");
            }
        }
        helper.succeed();
    }

    private static boolean selected(Point3 center, IntBox keep, BlockPos position) {
        long dx = 2L * position.getX() + 1 - center.x2(), dz = 2L * position.getZ() + 1 - center.z2();
        boolean insideBox = position.getX() >= keep.minX() && position.getX() < keep.maxX()
                && position.getY() >= keep.minY() && position.getY() < keep.maxY()
                && position.getZ() >= keep.minZ() && position.getZ() < keep.maxZ();
        return position.getY() >= 64 && position.getY() < 70 && dx * dx + dz * dz <= 121 && !insideBox;
    }
    private RegionGeometryGameTests() { }
}
