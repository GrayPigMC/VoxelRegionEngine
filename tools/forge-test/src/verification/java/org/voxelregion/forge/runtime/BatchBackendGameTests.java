/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.StaticSectionClearBackend;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.StaticSectionClearBackend.CommitResult;
import org.voxelregion.forge.StaticSectionClearBackend.HostHooks;
import org.voxelregion.forge.StaticSectionClearBackend.Prepared;
import org.voxelregion.forge.StaticSectionClearBackend.Rejection;

/** Native-world checks; verification-only, deliberately independent of the operation scheduler. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class BatchBackendGameTests {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("vre_verification_bulk",
            Comparator.comparingLong(ChunkPos::toLong));
    private static final HostHooks AUDITED_TEST_HOST = new HostHooks() {
        @Override public boolean supportsBulk(ServerLevel level) { return true; }
        @Override public void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
            ChunkMutationTracking.changing(chunk);
        }
        @Override public void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
            ChunkMutationTracking.changed(chunk);
        }
    };

    @GameTest(template = "empty", batch = "vre_bulk_metadata", timeoutTicks = 400)
    public static void bulkClearMaintainsSectionAndChunkMetadata(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 0);
        ServerLevel level = fixture.level;
        BlockPos minimum = fixture.minimum;
        fill(fixture, Blocks.STONE.defaultBlockState());
        level.setBlock(minimum.offset(15, 15, 15), Blocks.AIR.defaultBlockState(), 18);
        // Force one complete column to become empty after the clear. Merely priming an existing
        // populated heightmap would leave its previous top intact in this case.
        for (int y = level.getMinBuildHeight(); y < minimum.getY(); y++)
            level.setBlock(new BlockPos(minimum.getX() + 3, y, minimum.getZ() + 3), Blocks.AIR.defaultBlockState(), 18);
        var original = fixture.chunk.getSection(fixture.sectionIndex);
        var biomes = original.getBiomes();
        List<String> hooks = new ArrayList<>();
        HostHooks observer = new HostHooks() {
            @Override public boolean supportsBulk(ServerLevel world) { return true; }
            @Override public void beforePaletteReplaced(ServerLevel world, LevelChunk chunk, int sectionY) {
                hooks.add("before");
                helper.assertTrue(chunk.getSection(fixture.sectionIndex) == original, "Before hook sees old palette");
                ChunkMutationTracking.changing(chunk);
            }
            @Override public void afterPaletteReplaced(ServerLevel world, LevelChunk chunk, int sectionY) {
                hooks.add("palette");
                helper.assertTrue(chunk.getSection(fixture.sectionIndex) != original, "Palette hook sees new section");
                ChunkMutationTracking.changed(chunk);
            }
            @Override public void afterSectionCleared(ServerLevel world, LevelChunk chunk, Prepared previous) {
                hooks.add("complete");
                assertHeightmaps(helper, fixture);
            }
        };
        BlockPos tick = minimum.offset(2, 2, 2);
        BlockPos outsideTick = minimum.below();
        level.scheduleTick(tick, Blocks.STONE, 200);
        level.scheduleTick(tick, Fluids.WATER, 200);
        level.scheduleTick(outsideTick, Blocks.STONE, 200);
        level.scheduleTick(outsideTick, Fluids.WATER, 200);
        helper.assertTrue(level.getBlockTicks().hasScheduledTick(tick, Blocks.STONE)
                        && level.getFluidTicks().hasScheduledTick(tick, Fluids.WATER), "Fixture scheduled ticks exist");
        Prepared plan = accepted(helper, fixture, state -> true, observer);
        helper.assertTrue(plan.changedBlocks() == 4095, "Only non-air cells consume write budget");
        helper.assertTrue(StaticSectionClearBackend.commit(plan, () -> true) == CommitResult.APPLIED_QUEUED,
                "Audited whole section was applied");
        var cleared = fixture.chunk.getSection(fixture.sectionIndex);
        helper.assertTrue(cleared != original && cleared.hasOnlyAir() && !cleared.isRandomlyTicking(),
                "Replacement section recomputes zero block and random-tick counts");
        helper.assertTrue(cleared.getBiomes() == biomes, "Replacement preserves biome palette");
        helper.assertTrue(fixture.chunk.isUnsaved(), "Terrain change marks chunk unsaved");
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            helper.assertTrue(cleared.getBlockState(x, y, z) == Blocks.AIR.defaultBlockState(), "Cleared cell is ordinary air");
        helper.assertTrue(!level.getBlockTicks().hasScheduledTick(tick, Blocks.STONE)
                        && !level.getFluidTicks().hasScheduledTick(tick, Fluids.WATER), "Whole-section scheduled ticks removed");
        helper.assertTrue(level.getBlockTicks().hasScheduledTick(outsideTick, Blocks.STONE)
                        && level.getFluidTicks().hasScheduledTick(outsideTick, Fluids.WATER), "Outside scheduled ticks preserved");
        helper.assertTrue(hooks.equals(List.of("before", "palette", "complete")), "Host hook order is stable: " + hooks);
        assertHeightmaps(helper, fixture);
        helper.assertTrue(fixture.chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 3, 3) + 1 == level.getMinBuildHeight(),
                "Wholly empty column resets to dimension floor");
        BlockPos rebuilt = minimum.offset(1, 1, 1);
        level.setBlock(rebuilt, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        helper.assertTrue(StaticSectionClearBackend.commit(plan, () -> true) == CommitResult.ALREADY_CONSUMED
                        && level.getBlockState(rebuilt).is(Blocks.DIAMOND_BLOCK), "Used plan cannot delete later construction");
        release(fixture);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_bulk_guards", timeoutTicks = 400)
    public static void bulkRejectsUnsafeAndStaleInputs(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 0);
        ServerLevel level = fixture.level;
        BlockPos position = fixture.minimum.offset(4, 4, 4);
        fill(fixture, Blocks.AIR.defaultBlockState());
        level.setBlock(position, Blocks.STONE.defaultBlockState(), 18);
        assertRejected(helper, fixture, state -> false, AUDITED_TEST_HOST, Rejection.PROTECTED_STATE);
        assertRejected(helper, fixture, state -> true, world -> false, Rejection.HOST_UNSUPPORTED);
        level.captureBlockSnapshots = true;
        try { assertRejected(helper, fixture, state -> true, AUDITED_TEST_HOST, Rejection.CAPTURING_SNAPSHOTS); }
        finally { level.captureBlockSnapshots = false; }

        for (BlockState state : List.of(Blocks.BEDROCK.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_SIGN.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.CAVE_AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true),
                Blocks.BELL.defaultBlockState())) {
            // LevelChunk skips AIR->AIR writes and returns ordinary AIR for an all-air section.
            // Establish a non-air cell first, then inspect the actual section palette for this
            // CAVE_AIR/VOID_AIR identity test rather than the canonicalized level getter.
            level.setBlock(position, Blocks.STONE.defaultBlockState(), 18);
            level.setBlock(position, state, 18);
            BlockState visibleBefore = level.getBlockState(position);
            BlockState storedBefore = fixture.chunk.getSection(fixture.sectionIndex).getBlockState(4, 4, 4);
            helper.assertTrue(storedBefore == state, "Unsafe palette fixture installed: " + state);
            var result = StaticSectionClearBackend.prepare(level, fixture.chunk, fixture.sectionIndex,
                    candidate -> true, AUDITED_TEST_HOST);
            helper.assertTrue(!result.accepted() && level.getBlockState(position) == visibleBefore
                            && fixture.chunk.getSection(fixture.sectionIndex).getBlockState(4, 4, 4) == storedBefore,
                    "Unsafe state is rejected without writes: " + state + " / " + result.rejection());
        }
        level.setBlock(position, Blocks.STONE.defaultBlockState(), 18);
        fixture.chunk.addPackedPostProcess((short) 1, fixture.sectionIndex);
        assertRejected(helper, fixture, state -> true, AUDITED_TEST_HOST, Rejection.POST_PROCESSING);
        fixture.chunk.getPostProcessing()[fixture.sectionIndex].clear();

        Prepared denied = accepted(helper, fixture, state -> true, AUDITED_TEST_HOST);
        helper.assertTrue(StaticSectionClearBackend.commit(denied, () -> false) == CommitResult.PERMISSION_DENIED
                        && level.getBlockState(position).is(Blocks.STONE), "Final permission denial leaves terrain intact");
        Prepared stale = accepted(helper, fixture, state -> true, AUDITED_TEST_HOST);
        helper.assertTrue(StaticSectionClearBackend.commit(stale, () -> {
            level.setBlock(position, Blocks.END_GATEWAY.defaultBlockState(), 18);
            return true;
        }) == CommitResult.STALE, "Final guard callback mutation invalidates the prepared section");
        helper.assertTrue(level.getBlockState(position).is(Blocks.END_GATEWAY) && level.getBlockEntity(position) != null,
                "Newly installed protected block entity survives stale commit");
        level.setBlock(position, Blocks.STONE.defaultBlockState(), 18);

        CompoundTag pending = new CompoundTag();
        pending.putString("id", "minecraft:chest");
        pending.putInt("x", position.getX()); pending.putInt("y", position.getY()); pending.putInt("z", position.getZ());
        fixture.chunk.setBlockEntityNbt(pending);
        assertRejected(helper, fixture, state -> true, AUDITED_TEST_HOST, Rejection.BLOCK_ENTITY);
        // Promote the deliberately pending fixture through its matching state, then remove natively.
        level.setBlock(position, Blocks.CHEST.defaultBlockState(), 18);
        level.setBlock(position, Blocks.STONE.defaultBlockState(), 18);

        HostHooks failsAfterWrite = new HostHooks() {
            @Override public boolean supportsBulk(ServerLevel world) { return true; }
            @Override public void beforePaletteReplaced(ServerLevel world, LevelChunk chunk, int sectionY) {
                ChunkMutationTracking.changing(chunk);
            }
            @Override public void afterPaletteReplaced(ServerLevel world, LevelChunk chunk, int sectionY) {
                ChunkMutationTracking.changed(chunk);
            }
            @Override public void afterSectionCleared(ServerLevel world, LevelChunk chunk, Prepared previous) {
                throw new IllegalStateException("intentional verification observer failure");
            }
        };
        Prepared failing = accepted(helper, fixture, state -> true, failsAfterWrite);
        boolean appliedFailure = false;
        try { StaticSectionClearBackend.commit(failing, () -> true); }
        catch (StaticSectionClearBackend.CommitFailure expected) { appliedFailure = expected.terrainChanged(); }
        helper.assertTrue(appliedFailure && level.getBlockState(position).isAir(),
                "Post-write observer failure is explicitly classified as terrain applied");
        release(fixture);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_bulk_light", timeoutTicks = 100_000)
    public static void bulkLightingMatchesNative(GameTestHelper helper) {
        Fixture bulk = fixture(helper, 0);
        Fixture nativePath = fixture(helper, 4);
        cleanupOnCompletion(helper, () -> { release(bulk); release(nativePath); });
        for (Fixture fixture : List.of(bulk, nativePath)) {
            fill(fixture, Blocks.STONE.defaultBlockState());
            fixture.level.setBlock(fixture.minimum.offset(8, -1, 8), Blocks.GLOWSTONE.defaultBlockState(), 18);
        }
        CompletableFuture<?>[] light = { lightFence(bulk, nativePath) };
        long[] deadline = { System.nanoTime() + 30_000_000_000L };
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(light[0].isDone() || System.nanoTime() >= deadline[0],
                "Waiting for native opaque fixture lighting"))
                .thenExecute(() -> {
            requireLightFence(helper, light[0], "Opaque setup", bulk, nativePath);
            for (Fixture fixture : List.of(bulk, nativePath)) {
                BlockPos center = fixture.minimum.offset(8, 8, 8);
                helper.assertTrue(fixture.level.getBlockState(center).is(Blocks.STONE), "Opaque center remains stone before clearing");
                helper.assertTrue(fixture.level.getBrightness(LightLayer.SKY, center) == 0,
                        "Opaque fixture has settled dark sky light before clearing: " + lightDescription(fixture, center));
            }
            Prepared plan = accepted(helper, bulk, state -> true, AUDITED_TEST_HOST);
            helper.assertTrue(plan.changedBlocks() == 4096, "Full-section plan counts all cells");
            helper.assertTrue(StaticSectionClearBackend.commit(plan, () -> true) == CommitResult.APPLIED_QUEUED,
                    "Bulk light fixture clears successfully");
            fill(nativePath, Blocks.AIR.defaultBlockState());
            light[0] = lightFence(bulk, nativePath);
            deadline[0] = System.nanoTime() + 30_000_000_000L;
        }).thenWaitUntil(() -> helper.assertTrue(light[0].isDone() || System.nanoTime() >= deadline[0],
                "Waiting for native cleared fixture lighting"))
                .thenExecute(() -> {
            requireLightFence(helper, light[0], "Cleared result", bulk, nativePath);
            for (Fixture fixture : List.of(bulk, nativePath))
                helper.assertTrue(fixture.level.getBrightness(LightLayer.BLOCK, fixture.minimum.offset(8, 0, 8)) == 14
                                && fixture.level.getBrightness(LightLayer.SKY, fixture.minimum.offset(8, 0, 8)) == 15,
                        "Both paths settle source block light and open-sky light");
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int expected = Math.max(0, 14 - Math.abs(x - 8) - Math.abs(z - 8) - y);
                BlockPos bulkPosition = bulk.minimum.offset(x, y, z);
                BlockPos nativePosition = nativePath.minimum.offset(x, y, z);
                int bulkBlock = bulk.level.getBrightness(LightLayer.BLOCK, bulkPosition);
                int nativeBlock = nativePath.level.getBrightness(LightLayer.BLOCK, nativePosition);
                helper.assertTrue(bulkBlock == expected && nativeBlock == expected,
                        "Block-light oracle at " + x + "," + y + "," + z + ": bulk=" + bulkBlock + ", native=" + nativeBlock + ", expected=" + expected);
                helper.assertTrue(bulk.level.getBrightness(LightLayer.SKY, bulkPosition) == 15
                                && nativePath.level.getBrightness(LightLayer.SKY, nativePosition) == 15,
                        "Cleared section has identical open-sky light");
            }
        }).thenExecute(() -> {
            assertHeightmaps(helper, bulk);
            assertHeightmaps(helper, nativePath);
        }).thenSucceed();
    }

    private static Fixture fixture(GameTestHelper helper, int chunkOffsetX) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(8, 0, 8));
        ChunkPos chunkPos = new ChunkPos((origin.getX() >> 4) + chunkOffsetX, origin.getZ() >> 4);
        level.getChunkSource().addRegionTicket(TICKET, chunkPos, 2, chunkPos);
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int sectionY = level.getMaxSection() - 2;
        int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
        BlockPos minimum = new BlockPos(chunkPos.getMinBlockX(), sectionY << 4, chunkPos.getMinBlockZ());
        // Different GameTest structures may reuse these high-altitude chunks. Isolate the
        // target and its source halo, rather than leaving previous fixtures' edge emitters.
        for (int x = -2; x < 18; x++) for (int z = -2; z < 18; z++)
            for (int y = minimum.getY() - 2; y < level.getMaxBuildHeight(); y++)
                level.setBlock(new BlockPos(minimum.getX() + x, y, minimum.getZ() + z), Blocks.AIR.defaultBlockState(), 18);
        return new Fixture(level, chunk, sectionIndex, minimum);
    }

    private static void fill(Fixture fixture, BlockState state) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            fixture.level.setBlock(fixture.minimum.offset(x, y, z), state, 18);
    }

    private static Prepared accepted(GameTestHelper helper, Fixture fixture, Predicate<BlockState> predicate, HostHooks hooks) {
        var result = StaticSectionClearBackend.prepare(fixture.level, fixture.chunk, fixture.sectionIndex, predicate, hooks);
        helper.assertTrue(result.accepted(), "Static section preparation accepted: " + result.rejection());
        return result.prepared();
    }

    private static void assertRejected(GameTestHelper helper, Fixture fixture, Predicate<BlockState> predicate,
                                       HostHooks hooks, Rejection expected) {
        var result = StaticSectionClearBackend.prepare(fixture.level, fixture.chunk, fixture.sectionIndex, predicate, hooks);
        helper.assertTrue(!result.accepted() && result.rejection() == expected,
                "Expected rejection " + expected + ", got " + result.rejection());
    }

    private static void assertHeightmaps(GameTestHelper helper, Fixture fixture) {
        for (Heightmap.Types type : List.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int expected = fixture.level.getMinBuildHeight();
                for (int y = fixture.level.getMaxBuildHeight() - 1; y >= fixture.level.getMinBuildHeight(); y--) {
                    BlockState state = fixture.chunk.getBlockState(new BlockPos(fixture.minimum.getX() + x, y, fixture.minimum.getZ() + z));
                    if (type.isOpaque().test(state)) { expected = y + 1; break; }
                }
                int actual = fixture.chunk.getHeight(type, x, z) + 1;
                helper.assertTrue(actual == expected, "Independent full-column " + type + " oracle x=" + x + ",z=" + z
                        + ", actual=" + actual + ", expected=" + expected);
            }
        }
    }

    private static void release(Fixture fixture) {
        fixture.level.getChunkSource().removeRegionTicket(TICKET, fixture.chunk.getPos(), 2, fixture.chunk.getPos());
    }

    private static CompletableFuture<Void> lightFence(Fixture first, Fixture second) {
        return CompletableFuture.allOf(NativeCommitFences.light(first.level, first.chunk),
                NativeCommitFences.light(second.level, second.chunk));
    }

    private static void requireLightFence(GameTestHelper helper, CompletableFuture<?> future,
                                          String stage, Fixture first, Fixture second) {
        helper.assertTrue(future.isDone(), stage + " native light fence exceeded 30 seconds; "
                + lightDescription(first, first.minimum.offset(8, 8, 8)) + "; "
                + lightDescription(second, second.minimum.offset(8, 8, 8)));
        helper.assertTrue(!future.isCompletedExceptionally(), stage + " native light fence failed: " + future);
    }

    private static String lightDescription(Fixture fixture, BlockPos at) {
        return "chunk=" + fixture.chunk.getPos() + " position=" + at + " state=" + fixture.level.getBlockState(at)
                + " block=" + fixture.level.getBrightness(LightLayer.BLOCK, at)
                + " sky=" + fixture.level.getBrightness(LightLayer.SKY, at)
                + " sourceY=" + fixture.chunk.getSkyLightSources().getLowestSourceY(at.getX() & 15, at.getZ() & 15);
    }

    /** Failed assertions/timeouts do not execute the remaining sequence; always release fixture tickets. */
    private static void cleanupOnCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (var field : GameTestHelper.class.getDeclaredFields()) {
                if (field.getType() != GameTestInfo.class) continue;
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) {}
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new IllegalStateException("GameTest completion listener unavailable");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private record Fixture(ServerLevel level, LevelChunk chunk, int sectionIndex, BlockPos minimum) {}
    private BatchBackendGameTests() {}
}
