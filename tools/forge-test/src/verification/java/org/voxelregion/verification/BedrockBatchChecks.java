/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.verification;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.voxelregion.forge.ClearedTerrainProfile;
import org.voxelregion.forge.NoiseBatchScope;

/** Shared lattice must preserve every bit of independent single-column native evaluations. */
public final class BedrockBatchChecks {
    public static void run(MinecraftServer server) {
        var executor = Executors.newFixedThreadPool(4);
        var checks = new ArrayList<CompletableFuture<Integer>>();
        try {
            for (var dimension : java.util.List.of(Level.OVERWORLD, Level.NETHER)) {
                var level = server.getLevel(dimension);
                if (level == null) continue;
                var profile = ClearedTerrainProfile.select(level.getChunkSource().getGenerator(), level, true, false);
                if (profile == null || profile.algorithm() != ClearedTerrainProfile.Algorithm.NOISE_BEDROCK) continue;
                var height = LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight());
                var random = level.getChunkSource().randomState();
                for (int side : new int[]{2, 4}) for (var minimum : java.util.List.of(
                        new ChunkPos(0, 0), new ChunkPos(-37, 19), new ChunkPos(91, -45))) {
                    checks.add(CompletableFuture.supplyAsync(() -> {
                        var masks = profile.retainedBedrockSquare(minimum, side, height, random);
                        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
                            var position = new ChunkPos(minimum.x + x, minimum.z + z);
                            var expected = profile.retainedBedrock(position, height, random);
                            var actual = masks.get(z * side + x);
                            if (!expected.equals(actual)) {
                                var difference = (java.util.BitSet) expected.clone(); difference.xor(actual);
                                throw new AssertionError("Batch bedrock mismatch: " + dimension.location() + " " + position
                                        + " side=" + side + " difference=" + difference.cardinality()
                                        + " first=" + difference.nextSetBit(0));
                            }
                        }
                        if (NoiseBatchScope.aquiferBounds(minimum) != minimum)
                            throw new AssertionError("Private noise bounds leaked outside construction");
                        return side * side;
                    }, executor));
                }
            }
            CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).join();
            int count = checks.stream().mapToInt(CompletableFuture::join).sum();
            org.slf4j.LoggerFactory.getLogger("VRE_BEDROCK_BATCH_CHECK").info(
                    "[VRE_BEDROCK_BATCH_CHECK] PASS parallel shared-lattice masks={} (positive/negative coordinates, floor/roof)", count);
        } finally { executor.shutdown(); }
    }
    private BedrockBatchChecks() { }
}
