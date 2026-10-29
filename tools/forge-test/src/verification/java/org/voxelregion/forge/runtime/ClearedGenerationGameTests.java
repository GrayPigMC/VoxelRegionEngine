/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ClearedGeneration;
import org.voxelregion.forge.ClearedTerrainProfile;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.spatial.ChunkKey;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class ClearedGenerationGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_materialization_test", Comparator.<UUID>naturalOrder());

    @GameTest(template = "empty", batch = "vre_materialization_keep", timeoutTicks = 100000)
    public static void preservedBedrockIsMaterializedBeforeNativeLightAndFull(GameTestHelper helper) { materialize(helper, 2700, false); }

    @GameTest(template = "empty", batch = "vre_materialization_empty", timeoutTicks = 100000)
    public static void explicitBedrockDestructionSelectsEmptyTerrainAlgorithm(GameTestHelper helper) { materialize(helper, 2740, true); }

    private static void materialize(GameTestHelper helper, int coordinate, boolean destroyBedrock) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation("voxelregion_test", "generation_flat")));
        var pos = new ChunkPos(coordinate, coordinate);
        var operation = UUID.randomUUID();
        var active = new AtomicBoolean(true);
        var volume = new BoxVolume(new ChunkKey(pos.x, pos.z).bounds(level.getMinBuildHeight(), level.getMaxBuildHeight()));
        helper.assertTrue(ClearedTerrainProfile.select(level.getChunkSource().getGenerator(), level, false, destroyBedrock) == null,
                "Preserving fluids must select a different terrain algorithm");
        long before = ClearedGeneration.materializedChunks();
        ClearedGeneration.register(level, operation, "6".repeat(64), volume, true, destroyBedrock, active::get, p -> true);
        level.getChunkSource().addRegionTicket(HOLD, pos, 0, operation);
        var future = level.getChunkSource().getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
        WholeChunkRunnerGameTests.onCompletion(helper, () -> {
            active.set(false); ClearedGeneration.release(operation);
            level.getChunkSource().removeRegionTicket(HOLD, pos, 0, operation);
        });
        long start = System.nanoTime();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(future.isDone()
                        || System.nanoTime() - start > 45_000_000_000L, "Waiting for native light/FULL"))
                .thenExecute(() -> {
                    helper.assertTrue(future.isDone(), "Materialization did not finish within 45 seconds");
                    var chunk = (LevelChunk) future.join().left().orElseThrow();
                    helper.assertTrue(ClearedGeneration.isCleared(chunk) && ClearedGeneration.materializedChunks() == before + 1,
                            "Exactly the absent, fully covered target used the new algorithm");
                    helper.assertTrue(chunk.isLightCorrect() && chunk.getStatus() == ChunkStatus.FULL,
                            "Native lighting and native FULL promotion actually completed");
                    int bedrock = 0, other = 0;
                    for (var section : chunk.getSections()) if (!section.hasOnlyAir())
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = section.getBlockState(x, y, z);
                            if (state.is(Blocks.BEDROCK)) bedrock++; else if (!state.isAir()) other++;
                        }
                    helper.assertTrue(other == 0 && bedrock == (destroyBedrock ? 0 : 256),
                            "New terrain contains only the requested protection layer: bedrock=" + bedrock + " other=" + other);
                    var image = ChunkSerializer.write(level, chunk);
                    helper.assertTrue(image.getCompound(ClearedGeneration.MARKER).getBoolean("destroyBedrock") == destroyBedrock,
                            "Materialization semantics are part of the actual saved chunk image");
                    var loaded = ChunkSerializer.read(level, level.getPoiManager(), pos, image);
                    helper.assertTrue(ClearedGeneration.isCleared(loaded), "Native read restores the generation barrier");
                }).thenSucceed();
    }
    private ClearedGenerationGameTests() { }
}
