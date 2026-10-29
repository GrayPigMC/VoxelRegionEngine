/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.PaletteSectionClearBackend;
import org.voxelregion.forge.StaticSectionClearBackend;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PaletteBackendGameTests {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("vre_verification_palette",
            Comparator.comparingLong(ChunkPos::toLong));
    static final PaletteSectionClearBackend.HostHooks HOST = new PaletteSectionClearBackend.HostHooks() {
        @Override public boolean supportsBulk(ServerLevel level) { return true; }
        @Override public long mutationRevision(LevelChunk chunk) {
            var receipts = ChunkReceipts.of(chunk);
            return receipts.trackingValid() ? receipts.mutationRevision() : -1;
        }
        @Override public void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
            ChunkMutationTracking.changing(chunk);
        }
        @Override public void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
            ChunkMutationTracking.changed(chunk);
        }
    };
    private static final StaticSectionClearBackend.HostHooks SNAPSHOT_HOST = new StaticSectionClearBackend.HostHooks() {
        @Override public boolean supportsBulk(ServerLevel level) { return true; }
        @Override public void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
            ChunkMutationTracking.changing(chunk);
        }
        @Override public void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
            ChunkMutationTracking.changed(chunk);
        }
    };
    private static final List<BlockPos> SOURCES = List.of(new BlockPos(8, -1, 8), new BlockPos(16, 8, 8),
            new BlockPos(-1, 24, 4), new BlockPos(8, 12, -1), new BlockPos(4, 20, 16), new BlockPos(4, 32, 4));
    private static final List<BlockPos> ROOF_SOURCES = List.of(new BlockPos(8, -1, 8), new BlockPos(16, 8, 8),
            new BlockPos(-1, 24, 4), new BlockPos(8, 12, -1), new BlockPos(4, 20, 16), new BlockPos(12, 32, 12));

    @GameTest(template = "empty", batch = "vre_palette_counts", timeoutTicks = 400)
    public static void accurateCounterAndRevisionProtectFastPath(GameTestHelper helper) {
        org.voxelregion.verification.SectionStatsChecks.verify(helper.getLevel());
        Fixture fixture = fixture(helper, 0);
        var fluids = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.WATER.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                fixture.chunk.getSection(fixture.firstIndex).getBiomes());
        var counter = (NativeAccess.SectionStats) fluids;
        helper.assertTrue(counter.vre$accurateNonAirCount() == 4096, "Fluid-bearing cells are counted once by the independent counter");
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            fluids.setBlockState(x, y, z, Blocks.AIR.defaultBlockState());
        helper.assertTrue(counter.vre$accurateNonAirCount() == 0, "Native fluid removal updates the independent counter to zero");
        RuntimeBridge.LOGGER.info("Section count oracle after water->air: native={}, accurate={}",
                counter.vre$nonEmptyBlockCount(), counter.vre$accurateNonAirCount());

        fill(fixture, false, 0, false);
        var full = prepare(helper, fixture, 0);
        helper.assertTrue(full.changedBlocks() == 4096, "Unused AIR palette entries do not exclude a proven full section");
        BlockPos rebuilt = fixture.minimum.offset(8, 8, 8);
        fixture.level.setBlock(rebuilt, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        helper.assertTrue(PaletteSectionClearBackend.commit(full, () -> true).result() == PaletteSectionClearBackend.CommitResult.STALE,
                "Native mutation revision invalidates old palette proof");
        helper.assertTrue(fixture.level.getBlockState(rebuilt).is(Blocks.DIAMOND_BLOCK), "Stale palette proof preserves new construction");

        fixture.level.setBlock(rebuilt, Blocks.STONE.defaultBlockState(), 18);
        // The unused unsupported DIAMOND entry is intentionally conservative; compact an equivalent
        // all-stone fixture as save/load palette packing would, without changing any block states.
        compactUniformSection(fixture, 0);
        var guarded = prepare(helper, fixture, 0);
        helper.assertTrue(PaletteSectionClearBackend.commit(guarded, () -> {
            fixture.level.setBlock(rebuilt, Blocks.END_GATEWAY.defaultBlockState(), 18);
            return true;
        }).result() == PaletteSectionClearBackend.CommitResult.STALE, "Final claim callback mutation is detected by revision");
        helper.assertTrue(fixture.level.getBlockEntity(rebuilt) != null, "New protected block entity survives fast-path rejection");
        release(fixture);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_palette_full_batch", timeoutTicks = 800)
    public static void paletteBatchLightingEqualsSnapshotAndNative(GameTestHelper helper) {
        List<Fixture> fixtures = List.of(fixture(helper, 0), fixture(helper, 4), fixture(helper, 8));
        for (Fixture fixture : fixtures) fill(fixture, false, 0, false);
        CompletableFuture<Void> initial = fence(fixtures);
        CompletableFuture<?>[] finished = new CompletableFuture<?>[1];
        helper.startSequence().thenWaitUntil(() -> complete(helper, initial, "Full opaque fixture lighting"))
                .thenExecute(() -> {
                    Fixture fast = fixtures.get(0);
                    var plans = List.of(prepare(helper, fast, 0), prepare(helper, fast, 1));
                    var beforeBiomes = fast.chunk.getSection(fast.firstIndex).getBiomes();
                    var report = PaletteSectionClearBackend.commitBatch(plans, () -> true);
                    helper.assertTrue(report.applied() && report.changedBlocks() == 8192 && report.needsChunkPacket(),
                            "Two adjacent sections commit together and request one chunk packet");
                    helper.assertTrue(fast.chunk.getSection(fast.firstIndex).getBiomes() == beforeBiomes,
                            "Palette batch preserves biome identity");
                    clearSnapshot(helper, fixtures.get(1));
                    clearNative(fixtures.get(2));
                    finished[0] = CompletableFuture.allOf(report.lightWork(), fence(fixtures));
                }).thenWaitUntil(() -> complete(helper, finished[0], "Full palette/snapshot/native light propagation"))
                .thenExecute(() -> {
                    compareLight(helper, fixtures, false);
                    for (Fixture fixture : fixtures) assertMetadata(helper, fixture);
                    for (Fixture fixture : fixtures) release(fixture);
                }).thenSucceed();
    }

    /** Mixed AIR uses the production boundary batch plus native sky-source replay. */
    @GameTest(template = "empty", batch = "vre_palette_mixed_boundary", timeoutTicks = 1600)
    public static void mixedAirAndRoofShaftEqualSnapshotAndNative(GameTestHelper helper) {
        List<Fixture> fixtures = List.of(fixture(helper, 0), fixture(helper, 4), fixture(helper, 8));
        CompletableFuture<?>[] work = new CompletableFuture<?>[1];
        var sequence = helper.startSequence();
        for (long seed : new long[] { 0x62C9A17DL, 0x7001D00DL, 0x5EED0342L, 0xC0FFEE42L }) {
            boolean roof = seed == 0xC0FFEE42L;
            sequence.thenExecute(() -> {
                for (Fixture fixture : fixtures) fill(fixture, true, seed, roof);
                work[0] = fence(fixtures);
            }).thenWaitUntil(() -> complete(helper, work[0], "Mixed fixture lighting, seed=" + seed))
                    .thenExecute(() -> {
                        Fixture candidate = fixtures.get(0);
                        if (roof) {
                            helper.assertTrue(candidate.level.getBrightness(LightLayer.SKY, candidate.minimum.offset(4, 16, 4)) == 15,
                                    "Roof fixture has an existing interior level-15 skylight shaft");
                            helper.assertTrue(candidate.level.getBlockState(candidate.minimum.offset(5, 16, 4)).is(Blocks.STONE),
                                    "Roof fixture begins with an opaque wall beside the old shaft");
                        }
                        var plans = List.of(prepare(helper, candidate, 0), prepare(helper, candidate, 1));
                        int expectedWrites = plans.stream().mapToInt(PaletteSectionClearBackend.Prepared::changedBlocks).sum();
                        helper.assertTrue(expectedWrites > 0 && expectedWrites < 8192, "Mixed AIR charges only actual solids");
                        var report = PaletteSectionClearBackend.commitBatch(plans, () -> true);
                        helper.assertTrue(report.applied() && report.changedBlocks() == expectedWrites && report.needsChunkPacket(),
                                "Production mixed-palette batch applied with accurate write count");
                        clearSnapshot(helper, fixtures.get(1));
                        clearNative(fixtures.get(2));
                        work[0] = CompletableFuture.allOf(report.lightWork(), fence(fixtures));
                    }).thenWaitUntil(() -> complete(helper, work[0], "Mixed boundary/snapshot/native propagation, seed=" + seed))
                    .thenExecute(() -> {
                        compareLight(helper, fixtures, roof);
                        for (Fixture fixture : fixtures) assertMetadata(helper, fixture);
                        RuntimeBridge.LOGGER.info("Mixed AIR production palette differential: seed={}, roofShaft={}, two adjacent sections, six external sources, all 8192 block/sky light cells match native and full-check reference", seed, roof);
                    });
        }
        sequence.thenExecute(() -> fixtures.forEach(PaletteBackendGameTests::release)).thenSucceed();
    }

    private static PaletteSectionClearBackend.Prepared prepare(GameTestHelper helper, Fixture fixture, int offset) {
        var preparation = PaletteSectionClearBackend.prepare(fixture.level, fixture.chunk, fixture.firstIndex + offset, state -> true, HOST);
        if (!preparation.accepted()) {
            java.util.Map<BlockState, Integer> actual = new java.util.IdentityHashMap<>();
            var section = fixture.chunk.getSection(fixture.firstIndex + offset);
            section.getStates().count((state, count) -> actual.put(state, count));
            java.util.Set<BlockState> palette = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            section.maybeHas(state -> { palette.add(state); return false; });
            RuntimeBridge.LOGGER.error("Palette fixture rejected: chunk={}, sectionY={}, reason={}, actual={}, palette={}",
                    fixture.chunk.getPos(), fixture.chunk.getSectionYFromSectionIndex(fixture.firstIndex + offset),
                    preparation.rejection(), actual, palette);
        }
        helper.assertTrue(preparation.accepted(), "Palette proof accepted: " + preparation.rejection());
        return preparation.prepared();
    }

    static void fill(Fixture fixture, boolean mixed, long seed, boolean roof) {
        Random random = new Random(seed);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            boolean corridor = (z == 8 && y % 16 == 8) || (x == 4 && z == 4) || (x == 8 && y % 16 == 12);
            BlockState state = mixed && (corridor || random.nextDouble() < .42)
                    ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
            if (roof && x == 5 && z == 4) state = Blocks.STONE.defaultBlockState();
            fixture.level.setBlock(fixture.minimum.offset(x, y, z), state, 18);
        }
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            fixture.level.setBlock(fixture.minimum.offset(x, 32, z), roof && (x != 4 || z != 4)
                    ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 18);
        for (BlockPos source : roof ? ROOF_SOURCES : SOURCES)
            fixture.level.setBlock(fixture.minimum.offset(source), Blocks.GLOWSTONE.defaultBlockState(), 18);
        repackFixture(fixture);
    }

    /** Test setup removes unused entries left by prior fixtures, preserving every actual state. */
    static void repackFixture(Fixture fixture) {
        for (int offset = 0; offset < 2; offset++) {
            var original = fixture.chunk.getSection(fixture.firstIndex + offset);
            var replacement = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                    Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES), original.getBiomes());
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                replacement.setBlockState(x, y, z, original.getBlockState(x, y, z));
            ChunkMutationTracking.changing(fixture.chunk);
            fixture.chunk.getSections()[fixture.firstIndex + offset] = replacement;
            ChunkMutationTracking.changed(fixture.chunk);
        }
        fixture.chunk.setUnsaved(true);
    }

    static void clearNative(Fixture fixture) {
        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            fixture.level.setBlock(fixture.minimum.offset(x, y, z), Blocks.AIR.defaultBlockState(), 18);
    }

    private static void clearSnapshot(GameTestHelper helper, Fixture fixture) {
        for (int i = 0; i < 2; i++) {
            var preparation = StaticSectionClearBackend.prepare(fixture.level, fixture.chunk, fixture.firstIndex + i,
                    state -> true, SNAPSHOT_HOST);
            helper.assertTrue(preparation.accepted(), "Full-check reference accepted fixture: " + preparation.rejection());
            helper.assertTrue(StaticSectionClearBackend.commit(preparation.prepared(), () -> true)
                    == StaticSectionClearBackend.CommitResult.APPLIED_QUEUED, "Full-check reference applied");
        }
    }

    private static void compactUniformSection(Fixture fixture, int offset) {
        var old = fixture.chunk.getSection(fixture.firstIndex + offset);
        ChunkMutationTracking.changing(fixture.chunk);
        fixture.chunk.getSections()[fixture.firstIndex + offset] = new LevelChunkSection(new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, Blocks.STONE.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES), old.getBiomes());
        ChunkMutationTracking.changed(fixture.chunk);
        fixture.chunk.setUnsaved(true);
    }

    static CompletableFuture<Void> fence(List<Fixture> fixtures) {
        return CompletableFuture.allOf(fixtures.stream().map(f -> BackendFixtureGameTests.lightHalo(f.level, f.chunk.getPos()))
                .toArray(CompletableFuture[]::new));
    }

    static void complete(GameTestHelper helper, CompletableFuture<?> future, String description) {
        helper.assertTrue(future != null && future.isDone(), description + " is complete");
        future.join();
    }

    static void compareLight(GameTestHelper helper, List<Fixture> fixtures, boolean roof) {
        int[] skyOracle = skyOracle(roof);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int expectedBlock = 0;
            for (BlockPos source : roof ? ROOF_SOURCES : SOURCES)
                expectedBlock = Math.max(expectedBlock, 15 - Math.abs(x - source.getX()) - Math.abs(y - source.getY()) - Math.abs(z - source.getZ()));
            int expectedSky = skyOracle[(y << 8) | (z << 4) | x];
            for (int path = 0; path < fixtures.size(); path++) {
                Fixture fixture = fixtures.get(path);
                BlockPos position = fixture.minimum.offset(x, y, z);
                int block = fixture.level.getBrightness(LightLayer.BLOCK, position);
                int sky = fixture.level.getBrightness(LightLayer.SKY, position);
                if (block != expectedBlock || sky != expectedSky)
                    logLightDifference(fixtures, x, y, z, expectedBlock, expectedSky, roof);
                helper.assertTrue(block == expectedBlock && sky == expectedSky,
                        "Light oracle path=" + path + ", relative=" + x + "," + y + "," + z
                                + ", block=" + block + "/" + expectedBlock + ", sky=" + sky + "/" + expectedSky);
            }
        }
    }

    private static void logLightDifference(List<Fixture> fixtures, int x, int y, int z,
                                          int expectedBlock, int expectedSky, boolean roof) {
        StringBuilder detail = new StringBuilder();
        for (int path = 0; path < fixtures.size(); path++) {
            Fixture fixture = fixtures.get(path);
            BlockPos position = fixture.minimum.offset(x, y, z);
            detail.append(" path=").append(path).append(" origin=").append(fixture.minimum)
                    .append(" actualBlock=").append(fixture.level.getBrightness(LightLayer.BLOCK, position))
                    .append(" actualSky=").append(fixture.level.getBrightness(LightLayer.SKY, position));
            for (int[] column : new int[][] { { 4, 4 }, { 3, 4 }, { 4, 3 }, { 3, 3 } }) {
                detail.append(" col[").append(column[0]).append(',').append(column[1]).append("] sourceY=")
                        .append(fixture.chunk.getSkyLightSources().getLowestSourceY(column[0], column[1])).append(" skyY=");
                for (int yy : new int[] { 0, 1, 2, 8, 15, 16, 31 })
                    detail.append(yy).append(':').append(fixture.level.getBrightness(LightLayer.SKY,
                            fixture.minimum.offset(column[0], yy, column[1]))).append(',');
            }
        }
        RuntimeBridge.LOGGER.error("Light differential roof={} relative={},{},{} expectedBlock={} expectedSky={}{}",
                roof, x, y, z, expectedBlock, expectedSky, detail);
    }

    /** Independent flood oracle over the known fixture, including the six opaque source blocks. */
    private static int[] skyOracle(boolean roof) {
        // Two open columns of padding surround every side obstruction; all terrain is far below
        // this isolated fixture. Thus the outer padding columns contain direct sky at every height.
        int width = 20, height = 37, layer = width * width;
        boolean[] opaque = new boolean[layer * height];
        int[] top = new int[layer];
        Arrays.fill(top, Integer.MIN_VALUE);
        List<BlockPos> sources = roof ? ROOF_SOURCES : SOURCES;
        for (BlockPos source : sources) {
            int x = source.getX() + 2, y = source.getY() + 2, z = source.getZ() + 2;
            opaque[y * layer + z * width + x] = true;
            top[z * width + x] = Math.max(top[z * width + x], y);
        }
        if (roof) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            if (x == 4 && z == 4) continue;
            opaque[34 * layer + (z + 2) * width + x + 2] = true;
            top[(z + 2) * width + x + 2] = 34;
        }
        int[] light = new int[opaque.length];
        int[] queue = new int[opaque.length];
        int tail = 0;
        for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            int cell = y * layer + z * width + x;
            if (y > top[z * width + x] && !opaque[cell]) { light[cell] = 15; queue[tail++] = cell; }
        }
        for (int head = 0; head < tail; head++) {
            int cell = queue[head], y = cell / layer, z = cell % layer / width, x = cell % width;
            int nextLight = light[cell] - 1;
            if (nextLight <= 0) continue;
            for (int direction = 0; direction < 6; direction++) {
                int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
                int ny = y + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
                int nz = z + (direction == 4 ? -1 : direction == 5 ? 1 : 0);
                if (nx < 0 || nx >= width || ny < 0 || ny >= height || nz < 0 || nz >= width) continue;
                int neighbor = ny * layer + nz * width + nx;
                if (!opaque[neighbor] && light[neighbor] == 0) {
                    light[neighbor] = nextLight;
                    queue[tail++] = neighbor;
                }
            }
        }
        int[] result = new int[8192];
        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            result[(y << 8) | (z << 4) | x] = light[(y + 2) * layer + (z + 2) * width + x + 2];
        return result;
    }

    static void assertMetadata(GameTestHelper helper, Fixture fixture) {
        for (int i = 0; i < 2; i++) {
            var section = fixture.chunk.getSection(fixture.firstIndex + i);
            helper.assertTrue(section.hasOnlyAir() && ((NativeAccess.SectionStats) section).vre$accurateNonAirCount() == 0,
                    "Cleared section has consistent native and independent counts");
        }
        for (Heightmap.Types type : List.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int expected = fixture.level.getMinBuildHeight();
                for (int y = fixture.level.getMaxBuildHeight() - 1; y >= fixture.level.getMinBuildHeight(); y--)
                    if (type.isOpaque().test(fixture.chunk.getBlockState(new BlockPos(fixture.minimum.getX() + x, y, fixture.minimum.getZ() + z)))) {
                        expected = y + 1; break;
                    }
                helper.assertTrue(fixture.chunk.getHeight(type, x, z) + 1 == expected,
                        "Palette/native full-column height oracle for " + type + " x=" + x + ",z=" + z);
            }
        }
    }

    static Fixture fixture(GameTestHelper helper, int offsetX) {
        var site = BackendFixtureGameTests.allocate(helper, offsetX);
        ServerLevel level = site.level();
        ChunkPos position = site.position();
        level.getChunkSource().addRegionTicket(TICKET, position, 2, position);
        LevelChunk chunk = level.getChunk(position.x, position.z);
        int sectionY = level.getMaxSection() - 4;
        BlockPos minimum = new BlockPos(position.getMinBlockX(), sectionY << 4, position.getMinBlockZ());
        // A two-cell halo cannot isolate a light oracle: old level-15 emitters up to fourteen
        // Manhattan steps away still contribute. Clear a full light-range halo and every column
        // above it; sources outside this box cannot reach the target with positive block light.
        // The three fixtures remain four chunks apart, so these isolation boxes do not overlap.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++)
            for (int y = minimum.getY() - 16; y < level.getMaxBuildHeight(); y++) {
                cursor.set(minimum.getX() + x, y, minimum.getZ() + z);
                if (!level.getBlockState(cursor).isAir()) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 18);
            }
        Fixture fixture = new Fixture(level, chunk, level.getSectionIndexFromSectionY(sectionY), minimum);
        BackendFixtureGameTests.onFailure(helper, () -> release(fixture));
        return fixture;
    }

    static void release(Fixture fixture) {
        fixture.level.getChunkSource().removeRegionTicket(TICKET, fixture.chunk.getPos(), 2, fixture.chunk.getPos());
    }

    record Fixture(ServerLevel level, LevelChunk chunk, int firstIndex, BlockPos minimum) {}
    private PaletteBackendGameTests() {}
}
