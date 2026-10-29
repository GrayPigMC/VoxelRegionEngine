/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ClearedTerrainProfile;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class BedrockGenerationGameTests {
    @GameTest(template = "empty", batch = "vre_bedrock_generator", timeoutTicks = 100000)
    public static void protectedBandsMatchFullNativeNoiseAndSurfaceForBothDimensions(GameTestHelper helper) {
        var level = helper.getLevel();
        var registry = level.registryAccess();
        var biomes = registry.registryOrThrow(Registries.BIOME);
        var plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        var settings = registry.registryOrThrow(Registries.NOISE_SETTINGS);
        var noises = registry.lookupOrThrow(Registries.NOISE);
        CompletableFuture<Void> comparison = CompletableFuture.completedFuture(null);
        for (var setting : java.util.List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.NETHER)) {
            for (long seed : new long[] { 0, 7193381, -9021082871L }) {
                var holder = settings.getHolderOrThrow(setting);
                var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(plains), holder);
                var random = RandomState.create(holder.value(), noises, seed);
                var profile = ClearedTerrainProfile.select(generator, level, true, false);
                helper.assertTrue(profile != null && profile.algorithm() == ClearedTerrainProfile.Algorithm.NOISE_BEDROCK,
                        "Actual built-in surface rules select protected-band generation");
                var pos = new ChunkPos(-37 + (int)(seed % 7), 19);
                comparison = comparison.thenCompose(ignored -> {
                    var neighborhood = new ArrayList<ChunkAccess>();
                    for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                        var chunk = new ProtoChunk(new ChunkPos(pos.x + x, pos.z + z), UpgradeData.EMPTY, level, biomes, null);
                        chunk.fillBiomesFromNoise(generator.getBiomeSource(), random.sampler());
                        chunk.setStatus(ChunkStatus.BIOMES);
                        neighborhood.add(chunk);
                    }
                    var center = neighborhood.get(4);
                    var region = new WorldGenRegion(level, neighborhood, ChunkStatus.NOISE, 0);
                    var structures = level.structureManager().forWorldGenRegion(region);
                    return generator.fillFromNoise(Util.backgroundExecutor(), Blender.empty(), random, structures, center)
                            .thenAcceptAsync(filled -> {
                                generator.buildSurface(region, structures, random, filled);
                                BitSet nativeMask = new BitSet();
                                for (int si = 0; si < filled.getSectionsCount(); si++) {
                                    var section = filled.getSection(si);
                                    if (section.hasOnlyAir()) continue;
                                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                                        if (section.getBlockState(x, y, z).is(Blocks.BEDROCK))
                                            nativeMask.set((si * 16 + y) * 256 + z * 16 + x);
                                }
                                var actual = profile.retainedBedrock(pos, level, random);
                                if (!nativeMask.equals(actual)) {
                                    var difference = (BitSet) nativeMask.clone(); difference.xor(actual);
                                    throw new AssertionError("Bedrock differs from full native NOISE+SURFACE: " + setting.location()
                                            + " seed=" + seed + " native=" + nativeMask.cardinality() + " actual=" + actual.cardinality()
                                            + " differing=" + difference.cardinality() + " first=" + difference.nextSetBit(0));
                                }
                                if (actual.isEmpty()) throw new AssertionError("Oracle must actually contain bedrock");
                            }, Util.backgroundExecutor());
                });
            }
        }
        var result = comparison;
        long started = System.nanoTime();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(result.isDone()
                        || System.nanoTime() - started > 60_000_000_000L, "Waiting for full native bedrock oracle"))
                .thenExecute(() -> { helper.assertTrue(result.isDone(), "Bedrock oracle exceeded 60 seconds"); result.join(); })
                .thenSucceed();
    }
    private BedrockGenerationGameTests() { }
}
