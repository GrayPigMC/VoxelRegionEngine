/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.voxelregion.forge.DropSuppression;
import org.voxelregion.forge.PaletteSectionClearBackend;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class TerrainBulkGameTests {
    private static final BlockState[] TERRAIN = {
            Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Blocks.COAL_ORE.defaultBlockState(),
            Blocks.COPPER_ORE.defaultBlockState(), Blocks.DIAMOND_ORE.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.SAND.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.PODZOL.defaultBlockState(),
            Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3),
            Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState(), Blocks.CALCITE.defaultBlockState(),
            Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 5)
    };

    @GameTest(template = "empty", batch = "vre_selective_policy_epoch", timeoutTicks = 500)
    public static void selectivePlansKeepFluidPolicyAndRejectInterveningConstruction(GameTestHelper helper) {
        var fixture = PaletteBackendGameTests.fixture(helper, 0);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            fixture.level().setBlock(fixture.minimum().offset(x, y, z), Blocks.STONE.defaultBlockState(), 18);
        BlockPos water = fixture.minimum().offset(1, 8, 1), protectedCell = fixture.minimum().offset(2, 8, 1);
        fixture.level().setBlock(water, Blocks.WATER.defaultBlockState(), 18);
        fixture.level().setBlock(protectedCell, Blocks.BEDROCK.defaultBlockState(), 18);
        java.util.function.Predicate<BlockState> policy = state -> !state.is(Blocks.WATER) && !state.is(Blocks.BEDROCK);
        var old = PaletteSectionClearBackend.prepareSelected(fixture.level(), fixture.chunk(), fixture.firstIndex(),
                null, policy, PaletteBackendGameTests.HOST);
        helper.assertTrue(old.accepted() && old.prepared().changedBlocks() == 4094 && old.prepared().keptBlocks() == 2,
                "Protected water and bedrock are retained in a valid bulk plan");
        BlockPos construction = fixture.minimum().offset(7, 7, 7);
        fixture.level().setBlock(construction, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
        helper.assertTrue(!PaletteSectionClearBackend.validateBatch(List.of(old.prepared())),
                "Whole chunk planning notices an intervening player construction epoch");
        helper.assertTrue(PaletteSectionClearBackend.commit(old.prepared(), () -> true).result()
                        == PaletteSectionClearBackend.CommitResult.STALE,
                "A stale selected plan cannot delete a player's new block");
        helper.assertTrue(fixture.level().getBlockState(construction).is(Blocks.DIAMOND_BLOCK), "New construction remains intact");
        fixture.level().setBlock(construction, Blocks.STONE.defaultBlockState(), 18);
        // The palette retains an unused DIAMOND entry. Planning actual used states must recover
        // bulk eligibility instead of permanently poisoning this terrain section.
        var current = PaletteSectionClearBackend.prepareSelected(fixture.level(), fixture.chunk(), fixture.firstIndex(),
                null, policy, PaletteBackendGameTests.HOST);
        helper.assertTrue(current.accepted() && current.prepared().nativeRemainderCount() == 0
                        && current.prepared().changedBlocks() == 4094,
                "Unused unsupported palette entries do not force a native terrain loop");
        var result = PaletteSectionClearBackend.commit(current.prepared(), () -> true);
        helper.assertTrue(result.applied() && fixture.level().getFluidState(water).isSource()
                        && fixture.level().getBlockState(protectedCell).is(Blocks.BEDROCK),
                "clearFluids=false and protected states survive selective commit");
        helper.startSequence().thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, result.lightWork(), "Selective policy lighting"))
                .thenExecute(() -> PaletteBackendGameTests.release(fixture)).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_real_terrain_bulk", timeoutTicks = 1600)
    public static void ordinaryTerrainPalettesUseStorageBatchAndMatchNative(GameTestHelper helper) {
        var fixtures = List.of(PaletteBackendGameTests.fixture(helper, 0), PaletteBackendGameTests.fixture(helper, 4));
        for (var fixture : fixtures) {
            PaletteBackendGameTests.fill(fixture, false, 0, false);
            for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                fixture.level().setBlock(fixture.minimum().offset(x, y, z), TERRAIN[(x + y + z) % TERRAIN.length], 18);
            fixture.level().getBlockTicks().clearArea(area(fixture));
            fixture.level().getFluidTicks().clearArea(area(fixture));
        }
        CompletableFuture<?>[] work = { PaletteBackendGameTests.fence(fixtures) };
        helper.startSequence().thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Terrain initial light"))
                .thenExecute(() -> {
                    var fixture = fixtures.get(0);
                    var first = selected(helper, fixture, 0, null, PaletteBackendGameTests.HOST);
                    var second = selected(helper, fixture, 1, null, PaletteBackendGameTests.HOST);
                    helper.assertTrue(first.nativeRemainderCount() + second.nativeRemainderCount() == 0,
                            "Real stone/ore/deepslate/grass/sand/gravel/snow/water palettes need no native terrain deletion");
                    helper.assertTrue(first.changedBlocks() + second.changedBlocks() > 8000, "Actual terrain writes are charged");
                    var oldFirst = fixture.chunk().getSection(fixture.firstIndex());
                    var result = PaletteSectionClearBackend.commitBatch(List.of(first, second), () -> true);
                    helper.assertTrue(result.applied() && fixture.chunk().getSection(fixture.firstIndex()) != oldFirst,
                            "Ordinary terrain uses actual section storage replacement");
                    PaletteBackendGameTests.clearNative(fixtures.get(1));
                    work[0] = CompletableFuture.allOf(result.lightWork(), PaletteBackendGameTests.fence(fixtures));
                }).thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Terrain final light"))
                .thenExecute(() -> {
                    PaletteBackendGameTests.compareLight(helper, fixtures, false);
                    fixtures.forEach(fixture -> PaletteBackendGameTests.assertMetadata(helper, fixture));
                    fixtures.forEach(PaletteBackendGameTests::release);
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_selective_terrain_bulk", timeoutTicks = 1600)
    public static void protectedBedrockAndNativeCallbacksDoNotBlockTerrainBatch(GameTestHelper helper) {
        var fixtures = List.of(PaletteBackendGameTests.fixture(helper, 0), PaletteBackendGameTests.fixture(helper, 4));
        BitSet selection = new BitSet(4096);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 8; x++)
            selection.set((y << 8) | (z << 4) | x);
        for (var fixture : fixtures) fillProtected(fixture);
        AtomicInteger callbacks = new AtomicInteger(), observed = new AtomicInteger();
        ProbeBlocks.removalAction = (level, position) -> callbacks.incrementAndGet();
        BackendFixtureGameTests.onFailure(helper, () -> ProbeBlocks.removalAction = (level, position) -> {});
        PaletteSectionClearBackend.HostHooks observer = new PaletteSectionClearBackend.HostHooks() {
            @Override public boolean supportsBulk(ServerLevel level) { return true; }
            @Override public long mutationRevision(LevelChunk chunk) { return PaletteBackendGameTests.HOST.mutationRevision(chunk); }
            @Override public void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
                PaletteBackendGameTests.HOST.beforePaletteReplaced(level, chunk, sectionY);
            }
            @Override public void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
                PaletteBackendGameTests.HOST.afterPaletteReplaced(level, chunk, sectionY);
            }
            @Override public void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
                for (int index = 0; index < 4096; index++) {
                    int x = index & 15, y = index >>> 8, z = (index >>> 4) & 15;
                    if (previous.changed(x, y, z)) {
                        helper.assertTrue(x < 8 && y > 0 && previous.replacementState(x, y, z).isAir(),
                                "Observers see only actual selected changes and their resulting state");
                        helper.assertTrue(!previous.previousState(x, y, z).isAir(), "Observers receive old terrain");
                        observed.incrementAndGet();
                    }
                }
            }
        };
        CompletableFuture<?>[] work = { PaletteBackendGameTests.fence(fixtures) };
        helper.startSequence().thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Protected initial light"))
                .thenExecute(() -> {
                    var candidate = fixtures.get(0);
                    var plan = selected(helper, candidate, 0, selection, observer);
                    helper.assertTrue(plan.nativeRemainderCount() == 2,
                            "Only the chest and unknown callback are native remainders, not the surrounding terrain");
                    helper.assertTrue(plan.keptBlocks() == 127, "Selected bedrock is counted separately from native remainders");
                    var chest = candidate.level().getBlockEntity(candidate.minimum().offset(4, 0, 4));
                    var report = PaletteSectionClearBackend.commit(plan, () -> true);
                    helper.assertTrue(report.applied() && report.changedBlocks() == observed.get() && observed.get() > 1800,
                            "Protected/nonterrain palette entries do not veto safe storage replacement");
                    helper.assertTrue(candidate.level().getBlockEntity(candidate.minimum().offset(4, 0, 4)) == chest,
                            "Preserved block entity identity and ticking registration survive section swap");
                    helper.assertTrue(callbacks.get() == 0, "Native-only onRemove never runs during palette preparation or swap");
                    BitSet nativeRemainder = plan.nativeRemainder();
                    for (int index = nativeRemainder.nextSetBit(0); index >= 0; index = nativeRemainder.nextSetBit(index + 1)) {
                        BlockPos position = candidate.minimum().offset(index & 15, index >>> 8, (index >>> 4) & 15);
                        if (candidate.level().getBlockState(position).is(Blocks.CHEST)) continue;
                        DropSuppression.setBlock(candidate.level(), position, Blocks.AIR.defaultBlockState(), 18);
                    }
                    var reference = fixtures.get(1);
                    for (int index = selection.nextSetBit(0); index >= 0; index = selection.nextSetBit(index + 1)) {
                        BlockPos position = reference.minimum().offset(index & 15, index >>> 8, (index >>> 4) & 15);
                        BlockState state = reference.level().getBlockState(position);
                        if (!state.is(Blocks.BEDROCK) && !state.is(Blocks.CHEST))
                            DropSuppression.setBlock(reference.level(), position, Blocks.AIR.defaultBlockState(), 18);
                    }
                    helper.assertTrue(callbacks.get() == 2, "Candidate and native reference each execute the unknown removal callback exactly once");
                    helper.assertTrue(!candidate.level().getBlockTicks().hasScheduledTick(candidate.minimum().offset(1, 3, 1), Blocks.GRAVEL),
                            "Removed terrain tick is cleaned by changed mask");
                    helper.assertTrue(candidate.level().getBlockTicks().hasScheduledTick(candidate.minimum().offset(9, 3, 1), Blocks.STONE),
                            "Unselected pending tick is preserved");
                    helper.assertTrue(candidate.level().getBlockTicks().hasScheduledTick(candidate.minimum().offset(1, 0, 1), Blocks.BEDROCK),
                            "Protected pending tick is preserved");
                    work[0] = CompletableFuture.allOf(report.lightWork(), PaletteBackendGameTests.fence(fixtures));
                }).thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Protected final light"))
                .thenExecute(() -> {
                    for (var fixture : fixtures) {
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            BlockPos position = fixture.minimum().offset(x, y, z);
                            boolean chest = x == 4 && y == 0 && z == 4;
                            var expected = x >= 8 ? Blocks.STONE : chest ? Blocks.CHEST : y == 0 ? Blocks.BEDROCK : Blocks.AIR;
                            helper.assertTrue(fixture.level().getBlockState(position).is(expected), "Independent geometric/protection state oracle");
                            helper.assertTrue(fixture.level().getBrightness(LightLayer.BLOCK, position) == 0,
                                    "No sources means zero block light throughout both paths");
                            if (y > 0) helper.assertTrue(fixture.level().getBrightness(LightLayer.SKY, position) == (x < 8 ? 15 : 0),
                                    "Independent open-sky/retained-wall oracle at " + x + "," + y + "," + z);
                        }
                        var chest = (ChestBlockEntity) fixture.level().getBlockEntity(fixture.minimum().offset(4, 0, 4));
                        helper.assertTrue(chest != null && chest.getItem(0).getCount() == 17, "Protected inventory survives unchanged");
                        for (Heightmap.Types type : List.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
                                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES))
                            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                                helper.assertTrue(fixture.chunk().getHeight(type, x, z) + 1 == fixture.minimum().getY() + (x < 8 ? 1 : 16),
                                        "Selective storage heightmaps match independent final columns");
                    }
                    ProbeBlocks.removalAction = (level, position) -> {};
                    fixtures.forEach(PaletteBackendGameTests::release);
                }).thenSucceed();
    }

    private static PaletteSectionClearBackend.Prepared selected(GameTestHelper helper, PaletteBackendGameTests.Fixture fixture,
            int offset, BitSet selection, PaletteSectionClearBackend.HostHooks hooks) {
        var result = PaletteSectionClearBackend.prepareSelected(fixture.level(), fixture.chunk(), fixture.firstIndex() + offset,
                selection, state -> !state.is(Blocks.BEDROCK), hooks);
        helper.assertTrue(result.accepted(), "Selected palette plan accepted: " + result.rejection());
        return result.prepared();
    }

    private static void fillProtected(PaletteBackendGameTests.Fixture fixture) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            fixture.level().setBlock(fixture.minimum().offset(x, y, z),
                    x >= 8 ? Blocks.STONE.defaultBlockState() : y == 0 ? Blocks.BEDROCK.defaultBlockState()
                    : TERRAIN[(x + y + z) % TERRAIN.length], 18);
        fixture.level().setBlock(fixture.minimum().offset(4, 0, 4), Blocks.CHEST.defaultBlockState(), 18);
        ((ChestBlockEntity) fixture.level().getBlockEntity(fixture.minimum().offset(4, 0, 4)))
                .setItem(0, new ItemStack(Items.DIAMOND, 17));
        fixture.level().setBlock(fixture.minimum().offset(3, 2, 3), ForgeRegistries.BLOCKS.getValue(ProbeBlocks.REMOVAL_PROBE).defaultBlockState(), 18);
        fixture.level().setBlock(fixture.minimum().offset(1, 3, 1), Blocks.GRAVEL.defaultBlockState(), 18);
        fixture.level().getBlockTicks().clearArea(area(fixture));
        fixture.level().getFluidTicks().clearArea(area(fixture));
        fixture.level().scheduleTick(fixture.minimum().offset(1, 3, 1), Blocks.GRAVEL, 1_000_000);
        fixture.level().scheduleTick(fixture.minimum().offset(9, 3, 1), Blocks.STONE, 1_000_000);
        fixture.level().scheduleTick(fixture.minimum().offset(1, 0, 1), Blocks.BEDROCK, 1_000_000);
    }
    private static BoundingBox area(PaletteBackendGameTests.Fixture fixture) {
        return new BoundingBox(fixture.minimum().getX(), fixture.minimum().getY(), fixture.minimum().getZ(),
                fixture.minimum().getX() + 15, fixture.minimum().getY() + 31, fixture.minimum().getZ() + 15);
    }
    private TerrainBulkGameTests() {}
}
