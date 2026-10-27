/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.PaletteSectionClearBackend;

/** Exact vanilla water only: independent final light oracle plus native per-block comparison. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class WaterPaletteGameTests {
    @GameTest(template = "empty", batch = "vre_palette_water_policy", timeoutTicks = 600)
    public static void waterPolicyAndLavaRemainGuarded(GameTestHelper helper) {
        var fixture = PaletteBackendGameTests.fixture(helper, 0);
        fillWater(fixture, false, 0L, false);
        var disabled = PaletteSectionClearBackend.prepare(fixture.level(), fixture.chunk(), fixture.firstIndex(),
                state -> state.getFluidState().isEmpty(), PaletteBackendGameTests.HOST);
        helper.assertTrue(!disabled.accepted() && disabled.rejection() == PaletteSectionClearBackend.Rejection.PROTECTED_STATE,
                "clearFluids=false policy rejects a whole water section before writes");
        var tagged = PaletteSectionClearBackend.prepare(fixture.level(), fixture.chunk(), fixture.firstIndex(),
                state -> !state.getFluidState().is(FluidTags.WATER), PaletteBackendGameTests.HOST);
        helper.assertTrue(!tagged.accepted() && tagged.rejection() == PaletteSectionClearBackend.Rejection.PROTECTED_STATE,
                "An explicit tag preservation predicate is never bypassed for water");
        AtomicBoolean permission = new AtomicBoolean(true);
        var admitted = PaletteSectionClearBackend.prepare(fixture.level(), fixture.chunk(), fixture.firstIndex(),
                state -> permission.get(), PaletteBackendGameTests.HOST);
        helper.assertTrue(admitted.accepted(), "Vanilla source and every flowing water state have a supported proof");
        permission.set(false);
        helper.assertTrue(PaletteSectionClearBackend.commit(admitted.prepared(), () -> true).result()
                        == PaletteSectionClearBackend.CommitResult.STALE,
                "Water preservation policy is checked again immediately before commit");
        helper.assertTrue(fixture.level().getBlockState(fixture.minimum()).is(Blocks.WATER),
                "Rejected water plans leave the original liquid intact");

        BlockPos unsupported = fixture.minimum().offset(8, 8, 8);
        for (var direction : net.minecraft.core.Direction.values())
            fixture.level().setBlock(unsupported.relative(direction), Blocks.AIR.defaultBlockState(), 18);
        for (BlockState state : List.of(Blocks.LAVA.defaultBlockState(),
                Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 3),
                Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true))) {
            fixture.level().setBlock(unsupported, state, 18);
            helper.assertTrue(fixture.level().getBlockState(unsupported) == state,
                    "Unsupported-state fixture has not converted through water/lava interaction");
            PaletteBackendGameTests.repackFixture(fixture);
            var result = PaletteSectionClearBackend.prepare(fixture.level(), fixture.chunk(), fixture.firstIndex(),
                    ignored -> true, PaletteBackendGameTests.HOST);
            helper.assertTrue(!result.accepted() && result.rejection() == PaletteSectionClearBackend.Rejection.UNSUPPORTED_STATE,
                    "Lava emission and waterlogged solid callbacks retain the native fallback: " + state);
            // No game tick elapses here; do not let this policy fixture's water/lava run later.
            fixture.level().setBlock(unsupported, Blocks.WATER.defaultBlockState(), 18);
        }
        freezeFixtureTicks(fixture);
        PaletteBackendGameTests.release(fixture);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_palette_water_light", timeoutTicks = 2400)
    public static void denseAndMixedWaterMatchNativeLightAndTickCleanup(GameTestHelper helper) {
        List<PaletteBackendGameTests.Fixture> fixtures = List.of(PaletteBackendGameTests.fixture(helper, 0),
                PaletteBackendGameTests.fixture(helper, 4));
        CompletableFuture<?>[] work = new CompletableFuture<?>[1];
        var sequence = helper.startSequence();
        for (int variant = 0; variant < 3; variant++) {
            boolean mixed = variant != 0, roof = variant == 2;
            long seed = 0x574154455200L + variant;
            sequence.thenExecute(() -> {
                for (var fixture : fixtures) fillWater(fixture, mixed, seed, roof);
                work[0] = PaletteBackendGameTests.fence(fixtures);
            }).thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Water fixture initial lighting"))
                    .thenExecute(() -> {
                        for (var fixture : fixtures) assertInitialWater(helper, fixture, mixed, roof);
                        var candidate = fixtures.get(0);
                        var first = prepare(helper, candidate, 0);
                        var second = prepare(helper, candidate, 1);
                        int changed = first.changedBlocks() + second.changedBlocks();
                        helper.assertTrue(mixed ? changed > 0 && changed < 8192 : changed == 8192,
                                "Water cells count once, including source, flowing and falling levels");
                        var committed = PaletteSectionClearBackend.commitBatch(List.of(first, second), () -> true);
                        helper.assertTrue(committed.applied() && committed.changedBlocks() == changed && committed.needsChunkPacket(),
                                "Actual production water palette batch applies both adjacent sections");
                        var reference = fixtures.get(1);
                        PaletteBackendGameTests.clearNative(reference);
                        // Region clearing also removes pending work in the selected region. The native
                        // reference uses the identical public tick-container operation, independently
                        // of the production section swap, with outside ticks checked separately.
                        reference.level().getFluidTicks().clearArea(target(reference));
                        for (var fixture : fixtures) assertTicks(helper, fixture);
                        work[0] = CompletableFuture.allOf(committed.lightWork(), PaletteBackendGameTests.fence(fixtures));
                    }).thenWaitUntil(() -> PaletteBackendGameTests.complete(helper, work[0], "Water native/bulk final lighting"))
                    .thenExecute(() -> {
                        PaletteBackendGameTests.compareLight(helper, fixtures, roof);
                        for (var fixture : fixtures) {
                            PaletteBackendGameTests.assertMetadata(helper, fixture);
                            for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                                BlockPos position = fixture.minimum().offset(x, y, z);
                                helper.assertTrue(fixture.level().getBlockState(position).isAir()
                                                && fixture.level().getFluidState(position).isEmpty(),
                                        "Both water paths leave AIR and empty fluid state throughout the selected batch");
                            }
                            assertTicks(helper, fixture);
                        }
                        RuntimeBridge.LOGGER.info("WATER palette differential PASS: mixed={}, roofShaft={}, seed={}, 8192 cells/path, independent block+sky+heightmaps, inside source/flow ticks cleared and outside water/tick preserved",
                                mixed, roof, seed);
                    });
        }
        sequence.thenExecute(() -> fixtures.forEach(PaletteBackendGameTests::release)).thenSucceed();
    }

    private static PaletteSectionClearBackend.Prepared prepare(GameTestHelper helper,
            PaletteBackendGameTests.Fixture fixture, int offset) {
        var result = PaletteSectionClearBackend.prepare(fixture.level(), fixture.chunk(), fixture.firstIndex() + offset,
                state -> true, PaletteBackendGameTests.HOST);
        helper.assertTrue(result.accepted(), "Water palette is supported: " + result.rejection());
        return result.prepared();
    }

    private static void fillWater(PaletteBackendGameTests.Fixture fixture, boolean mixed, long seed, boolean roof) {
        // Shared setup installs six known exterior emitters and (optionally) the retained shaft roof.
        PaletteBackendGameTests.fill(fixture, mixed, seed, roof);
        Random random = new Random(seed);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState previous = fixture.level().getBlockState(fixture.minimum().offset(x, y, z));
            if (mixed && (previous.isAir() || random.nextDouble() < .20)) continue;
            fixture.level().setBlock(fixture.minimum().offset(x, y, z),
                    Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, (x + y + z) & 15), 18);
        }
        // Keep the old skylight shaft, plus a full opaque wall beside it, in the adversarial case.
        if (roof) for (int y = 0; y < 32; y++) {
            fixture.level().setBlock(fixture.minimum().offset(4, y, 4), Blocks.AIR.defaultBlockState(), 18);
            fixture.level().setBlock(fixture.minimum().offset(5, y, 4), Blocks.STONE.defaultBlockState(), 18);
        }
        fixture.level().setBlock(source(fixture), Blocks.WATER.defaultBlockState(), 18);
        fixture.level().setBlock(flow(fixture), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3), 18);
        fixture.level().setBlock(outside(fixture), Blocks.WATER.defaultBlockState(), 18);
        freezeFixtureTicks(fixture);
        fixture.level().scheduleTick(source(fixture), Fluids.WATER, 1_000_000);
        fixture.level().scheduleTick(flow(fixture), Fluids.FLOWING_WATER, 1_000_000);
        fixture.level().scheduleTick(outside(fixture), Fluids.WATER, 1_000_000);
        PaletteBackendGameTests.repackFixture(fixture);
    }

    private static void assertInitialWater(GameTestHelper helper, PaletteBackendGameTests.Fixture fixture,
                                          boolean mixed, boolean roof) {
        helper.assertTrue(fixture.level().getFluidState(source(fixture)).isSource()
                        && !fixture.level().getFluidState(flow(fixture)).isSource()
                        && !fixture.level().getFluidState(flow(fixture)).isEmpty(),
                "The live old field contains source and flowing water before replacement");
        helper.assertTrue(fixture.level().getFluidTicks().hasScheduledTick(source(fixture), Fluids.WATER)
                        && fixture.level().getFluidTicks().hasScheduledTick(flow(fixture), Fluids.FLOWING_WATER),
                "Native source and flowing-water ticks exist before cleanup");
        if (!mixed) helper.assertTrue(fixture.level().getBrightness(LightLayer.SKY, fixture.minimum().offset(8, 16, 8)) < 15,
                "Dense old water attenuates sky before becoming transparent AIR");
        if (roof) helper.assertTrue(fixture.level().getBrightness(LightLayer.SKY, fixture.minimum().offset(4, 16, 4)) == 15,
                "Mixed water test includes an existing internal sky-15 shaft");
    }

    private static void assertTicks(GameTestHelper helper, PaletteBackendGameTests.Fixture fixture) {
        helper.assertTrue(!fixture.level().getFluidTicks().hasScheduledTick(source(fixture), Fluids.WATER)
                        && !fixture.level().getFluidTicks().hasScheduledTick(flow(fixture), Fluids.FLOWING_WATER),
                "Selected source and flowing-water scheduled ticks are removed");
        helper.assertTrue(fixture.level().getFluidTicks().hasScheduledTick(outside(fixture), Fluids.WATER)
                        && fixture.level().getFluidState(outside(fixture)).isSource(),
                "Unselected water and its pending native tick remain intact");
    }

    private static void freezeFixtureTicks(PaletteBackendGameTests.Fixture fixture) {
        BlockPos p = fixture.minimum();
        fixture.level().getFluidTicks().clearArea(new BoundingBox(p.getX() - 2, p.getY() - 2, p.getZ() - 2,
                p.getX() + 34, p.getY() + 34, p.getZ() + 17));
    }
    private static BoundingBox target(PaletteBackendGameTests.Fixture fixture) {
        BlockPos p = fixture.minimum();
        return new BoundingBox(p.getX(), p.getY(), p.getZ(), p.getX() + 15, p.getY() + 31, p.getZ() + 15);
    }
    private static BlockPos source(PaletteBackendGameTests.Fixture f) { return f.minimum().offset(1, 1, 1); }
    private static BlockPos flow(PaletteBackendGameTests.Fixture f) { return f.minimum().offset(2, 17, 2); }
    private static BlockPos outside(PaletteBackendGameTests.Fixture f) { return f.minimum().offset(32, 8, 8); }
    private WaterPaletteGameTests() {}
}
