/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.verification;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.voxelregion.forge.NativeAccess;

/** Native section construction, recount, mutation and packet-read contracts; no world writes. */
public final class SectionStatsChecks {
    public static void verify(ServerLevel level) {
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var template = new LevelChunkSection(biomes);
        for (var state : new BlockState[] { Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.STONE.defaultBlockState() }) {
            var uniform = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                    state, PalettedContainer.Strategy.SECTION_STATES), template.getBiomes());
            checkCounts(uniform, true);
            uniform.recalcBlockCounts();
            checkCounts(uniform, true);
        }

        BlockState[] values = { Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true) };
        var states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        for (int i = 0; i < 4096; i++) states.getAndSet(i & 15, i >>> 8, (i >>> 4) & 15, values[i % values.length]);
        var mixed = new LevelChunkSection(states, template.getBiomes());
        checkCounts(mixed, true);
        mixed.recalcBlockCounts();
        checkCounts(mixed, true);

        // Bulk palette replacement bypasses setBlockState. A competing native recount redirect
        // must invalidate our previously ready value before the compatible return-path scan.
        for (int i = 0; i < 4096; i++)
            states.getAndSet(i & 15, i >>> 8, (i >>> 4) & 15,
                    i < 37 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        mixed.recalcBlockCounts();
        checkCounts(mixed, true);
        check(((NativeAccess.SectionStats) mixed).vre$accurateNonAirCount() == 37,
                "Recount replaces an already-ready count after direct palette mutation");

        for (int i = 0; i < 4096; i += 3)
            mixed.setBlockState(i & 15, i >>> 8, (i >>> 4) & 15, values[(i / 3 + 1) % values.length], (i & 1) == 0);
        checkCounts(mixed, false);
        mixed.recalcBlockCounts();
        checkCounts(mixed, true);

        var packet = new FriendlyByteBuf(Unpooled.buffer());
        try {
            mixed.write(packet);
            var restored = new LevelChunkSection(biomes);
            restored.read(packet);
            check(packet.readableBytes() == 0, "Section packet consumed exactly");
            checkCounts(restored, false);
            check(((NativeAccess.SectionStats) restored).vre$nonEmptyBlockCount()
                    == ((NativeAccess.SectionStats) mixed).vre$nonEmptyBlockCount(), "Packet preserves native non-empty count");
            for (int i = 0; i < 4096; i++) check(restored.getBlockState(i & 15, i >>> 8, (i >>> 4) & 15)
                    == mixed.getBlockState(i & 15, i >>> 8, (i >>> 4) & 15), "Packet preserves every block state");
            restored.recalcBlockCounts();
            checkCounts(restored, true);
        } finally { packet.release(); }

        var initiallyUncounted = new LevelChunkSection(biomes);
        initiallyUncounted.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
        checkCounts(initiallyUncounted, false);
        initiallyUncounted.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState(), true);
        initiallyUncounted.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState(), false);
        checkCounts(initiallyUncounted, false);
        org.slf4j.LoggerFactory.getLogger("VRE_SECTION_STATS_CHECK").info(
                "[VRE_SECTION_STATS_CHECK] PASS mixed water/air/solid/waterlogged counters, native random ticks, repeat recount, packet read, initial set fallback");
    }

    private static void checkCounts(LevelChunkSection section, boolean freshlyRecounted) {
        int nonAir = 0, fluidCells = 0;
        boolean tickingBlocks = false, tickingFluids = false;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var state = section.getBlockState(x, y, z);
            if (!state.isAir()) { nonAir++; tickingBlocks |= state.isRandomlyTicking(); }
            var fluid = state.getFluidState();
            if (!fluid.isEmpty()) { fluidCells++; tickingFluids |= fluid.isRandomlyTicking(); }
        }
        var stats = (NativeAccess.SectionStats) section;
        check(stats.vre$accurateNonAirCount() == nonAir, "Independent count equals direct voxel scan");
        if (freshlyRecounted) {
            check(stats.vre$nonEmptyBlockCount() == nonAir + fluidCells, "Native recount retains fluid-bearing count semantics");
            check(section.isRandomlyTickingBlocks() == tickingBlocks, "Native random-block count preserved");
            check(section.isRandomlyTickingFluids() == tickingFluids, "Native random-fluid count preserved");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private SectionStatsChecks() { }
}
