/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.IdMapper;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.RegionBlockStateContainer;

/** Differential native serialization checks; absent from normal artifacts. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class UniformPaletteGameTests {
    private static final PalettedContainer.Strategy STATES = PalettedContainer.Strategy.SECTION_STATES;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Codec<PalettedContainer<BlockState>> CODEC =
            PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, STATES, AIR);

    @GameTest(template = "empty", batch = "vre_uniform_pack", timeoutTicks = 100)
    public static void uniformAirAndNonAirMatchNativeCodec(GameTestHelper helper) {
        for (BlockState state : List.of(AIR, Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState())) {
            var candidate = new RegionBlockStateContainer(state);
            var nativeContainer = nativeContainer(state);
            var first = candidate.pack(Block.BLOCK_STATE_REGISTRY, STATES);
            var second = candidate.pack(Block.BLOCK_STATE_REGISTRY, STATES);
            helper.assertTrue(first != second && first.storage().isEmpty() && second.storage().isEmpty(),
                    "Each uniform pack creates an independent result with no mutable stream or array");
            helper.assertTrue(first.paletteEntries().equals(List.of(state)), "Exact air/non-air identity is retained");
            boolean immutable = false;
            try { first.paletteEntries().add(AIR); } catch (UnsupportedOperationException expected) { immutable = true; }
            helper.assertTrue(immutable, "Packed palette is detached and immutable");
            for (int repeat = 0; repeat < 3; repeat++) assertEquivalent(helper, candidate, nativeContainer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_uniform_pack", timeoutTicks = 100)
    public static void laterWritesAndNativeReadsNeverReuseUniformEvidence(GameTestHelper helper) {
        var candidate = new RegionBlockStateContainer(AIR);
        var nativeContainer = nativeContainer(AIR);
        Tag original = encode(candidate);
        BlockState stone = Blocks.STONE.defaultBlockState();
        candidate.set(2, 3, 4, stone);
        nativeContainer.set(2, 3, 4, stone);
        helper.assertTrue(candidate.pack(Block.BLOCK_STATE_REGISTRY, STATES).storage().isPresent(),
                "The first different state invalidates the uniform path without any external mutation hook");
        assertEquivalent(helper, candidate, nativeContainer);
        helper.assertTrue(decode(original).get(2, 3, 4) == AIR, "Earlier encoded NBT does not alias later writes");
        candidate.getAndSet(2, 3, 4, AIR);
        nativeContainer.getAndSet(2, 3, 4, AIR);
        helper.assertTrue(candidate.maybeHas(state -> state != AIR), "Fixture retains an unused old palette entry");
        assertEquivalent(helper, candidate, nativeContainer);

        var packetSource = nativeContainer(Blocks.WATER.defaultBlockState());
        packetSource.set(5, 6, 7, Blocks.GLASS.defaultBlockState());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packetSource.write(buffer);
            candidate.read(buffer);
        } finally { buffer.release(); }
        assertEquivalent(helper, candidate, packetSource);
        var snapshot = candidate.copy();
        candidate.set(5, 6, 7, AIR);
        helper.assertTrue(snapshot.get(5, 6, 7).is(Blocks.GLASS), "Native copy retains independent mutable storage");
        assertEquivalent(helper, snapshot, packetSource);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_uniform_pack", timeoutTicks = 100)
    public static void otherRegistryAndStrategyKeepNativeContract(GameTestHelper helper) {
        var candidate = new RegionBlockStateContainer(AIR);
        var nativeContainer = nativeContainer(AIR);
        var alternateRegistry = new IdMapper<BlockState>();
        alternateRegistry.add(AIR);
        var actual = candidate.pack(alternateRegistry, STATES);
        var expected = nativeContainer.pack(alternateRegistry, STATES);
        helper.assertTrue(actual.paletteEntries().equals(expected.paletteEntries())
                        && actual.storage().isEmpty() && expected.storage().isEmpty(),
                "A different registry follows the native representation");
        // A 4096-cell states container cannot be encoded with the 64-cell biome strategy. Native
        // pack rejects the size mismatch; the uniform override must not silently accept it.
        Class<?> actualFailure = wrongStrategyFailure(candidate);
        Class<?> expectedFailure = wrongStrategyFailure(nativeContainer);
        helper.assertTrue(actualFailure != null && actualFailure == expectedFailure,
                "Wrong strategy keeps the native failure instead of returning a misleading uniform image");
        assertEquivalent(helper, candidate, nativeContainer);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_uniform_chunk_image", timeoutTicks = 300)
    public static void realChunkSerializerRoundTripPreservesBiomesAndLaterConstruction(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunk = level.getChunk(3100, 3100);
        int sectionIndex = chunk.getSectionIndex(128);
        var original = chunk.getSection(sectionIndex);
        var states = new RegionBlockStateContainer(AIR);
        var replacement = new LevelChunkSection(states, original.getBiomes());
        try {
            chunk.getSections()[sectionIndex] = replacement;
            helper.assertTrue(replacement.getBiomes() == original.getBiomes(), "The live biome container is retained");
            CompoundTag first = ChunkSerializer.write(level, chunk);
            var firstRead = ChunkSerializer.read(level, level.getPoiManager(), chunk.getPos(), first);
            helper.assertTrue(firstRead.getSection(sectionIndex).getBlockState(5, 7, 9) == AIR,
                    "Native ChunkSerializer reads the uniform representation");
            replacement.setBlockState(5, 7, 9, Blocks.DIAMOND_BLOCK.defaultBlockState());
            CompoundTag second = ChunkSerializer.write(level, chunk);
            var secondRead = ChunkSerializer.read(level, level.getPoiManager(), chunk.getPos(), second);
            helper.assertTrue(secondRead.getSection(sectionIndex).getBlockState(5, 7, 9).is(Blocks.DIAMOND_BLOCK),
                    "A later native section mutation is present in the next full chunk image");
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
                helper.assertTrue(secondRead.getSection(sectionIndex).getBiomes().get(x, y, z)
                                .equals(original.getBiomes().get(x, y, z)), "All 64 native biome entries survive");
            var detachedAgain = ChunkSerializer.read(level, level.getPoiManager(), chunk.getPos(), first);
            helper.assertTrue(detachedAgain.getSection(sectionIndex).getBlockState(5, 7, 9) == AIR,
                    "The earlier full chunk image remains unchanged after subsequent saves");
        } finally { chunk.getSections()[sectionIndex] = original; }
        helper.succeed();
    }

    private static PalettedContainer<BlockState> nativeContainer(BlockState state) {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, state, STATES);
    }

    private static void assertEquivalent(GameTestHelper helper, PalettedContainer<BlockState> candidate,
                                         PalettedContainer<BlockState> nativeContainer) {
        Tag actual = encode(candidate), expected = encode(nativeContainer);
        helper.assertTrue(actual.equals(expected), "Native codec NBT is exactly equal");
        var restored = decode(actual);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            helper.assertTrue(restored.get(x, y, z) == nativeContainer.get(x, y, z),
                    "Every native decoded state is equal");
    }

    private static Class<?> wrongStrategyFailure(PalettedContainer<BlockState> container) {
        try { container.pack(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_BIOMES); }
        catch (RuntimeException expected) { return expected.getClass(); }
        return null;
    }

    private static Tag encode(PalettedContainer<BlockState> container) {
        return CODEC.encodeStart(NbtOps.INSTANCE, container).getOrThrow(false, message -> { });
    }

    private static PalettedContainer<BlockState> decode(Tag image) {
        return CODEC.parse(NbtOps.INSTANCE, image).getOrThrow(false, message -> { });
    }
}
