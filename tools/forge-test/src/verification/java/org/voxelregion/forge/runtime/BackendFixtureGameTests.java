/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.voxelregion.forge.NativeCommitFences;

/** Verification-only sites: no GameTest marker blocks, random terrain, fluid flow or reused light fields. */
final class BackendFixtureGameTests {
    private static int nextSite;
    record Site(ServerLevel level, ChunkPos position) {}

    static Site allocate(GameTestHelper helper, int localOffset) {
        if (localOffset < 0 || localOffset > 8) throw new IllegalArgumentException("Invalid fixture offset");
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_backend_fixture")));
        helper.assertTrue(level != null, "Dedicated bedrock-only backend fixture dimension exists");
        int index = nextSite++;
        // Each allocation owns a separate site even across test classes. Offsets 0/4/8 retain
        // the callers' spacing convention; the closest two sites are still eight chunks apart.
        return new Site(level, new ChunkPos((index & 7) * 16 + localOffset, (index >>> 3) * 16));
    }

    static CompletableFuture<Void> lightHalo(ServerLevel level, ChunkPos center) {
        List<CompletableFuture<Void>> barriers = new ArrayList<>(9);
        // Native checkBlock tasks for setup's emitters/halo belong to their own chunk mailbox.
        // A fence for only the target chunk cannot acknowledge those other source removals.
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            barriers.add(NativeCommitFences.light(level, level.getChunk(center.x + dx, center.z + dz)));
        return CompletableFuture.allOf(barriers.toArray(CompletableFuture[]::new));
    }

    static void onFailure(GameTestHelper helper, Runnable cleanup) {
        try {
            for (var field : GameTestHelper.class.getDeclaredFields()) {
                if (field.getType() != GameTestInfo.class) continue;
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) {}
                    public void testPassed(GameTestInfo info) {}
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new IllegalStateException("GameTest completion listener unavailable");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private BackendFixtureGameTests() {}
}
