/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.voxelregion.forge.NativeAccess;

/** A same-chunk FIFO fence on the native light mailbox; never changes light-correct flags or generation tickets. */
@Mixin(ThreadedLevelLightEngine.class)
abstract class LightFenceMixin implements NativeAccess.LightFence {
    @Shadow @Final private ChunkMap chunkMap;
    @Shadow @Final private ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> sorterMailbox;
    @Shadow @Final private ObjectList<?> lightTasks;
    @Shadow private void runUpdate() { throw new AssertionError(); }

    @Override public CompletableFuture<Void> vre$afterChunkWork(ChunkPos position) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            try {
                // Prior tasks for this chunk have entered lightTasks through the same sorter.
                // This callback runs on the light thread, where no concurrent append is legal.
                do { runUpdate(); } while (!lightTasks.isEmpty());
                result.complete(null);
            } catch (RuntimeException | LinkageError failure) { result.completeExceptionally(failure); }
        }, position.toLong(), ((NativeAccess.ChunkSave) chunkMap).vre$queueLevel(position.toLong())));
        return result;
    }

    @Override public CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs) {
        return vre$batchChecks(position, sectionYs, false);
    }

    @Override public CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs, boolean replaySkySources) {
        // Freeze caller-owned input before handing it to the native lighting thread.
        int[] sections = Arrays.stream(sectionYs).distinct().sorted().toArray();
        CompletableFuture<Void> result = new CompletableFuture<>();
        sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            try {
                // In particular, apply the preceding updateSectionStatus calls before checking nodes.
                do { runUpdate(); } while (!lightTasks.isEmpty());
                var engine = (ThreadedLevelLightEngine) (Object) this;
                LayerLightEventListener block = engine.getLayerListener(LightLayer.BLOCK);
                LayerLightEventListener sky = engine.getLayerListener(LightLayer.SKY);
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                int minX = position.getMinBlockX(), minZ = position.getMinBlockZ();
                for (int i = 0; i < sections.length; i++) {
                    int baseY = sections[i] << 4;
                    // Four side faces, without duplicate edge nodes.
                    for (int y = baseY; y < baseY + 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            vre$checkBoth(block, sky, cursor.set(minX, y, minZ + z));
                            vre$checkBoth(block, sky, cursor.set(minX + 15, y, minZ + z));
                        }
                        for (int x = 1; x < 15; x++) {
                            vre$checkBoth(block, sky, cursor.set(minX + x, y, minZ));
                            vre$checkBoth(block, sky, cursor.set(minX + x, y, minZ + 15));
                        }
                    }
                    boolean bottom = i == 0 || sections[i - 1] != sections[i] - 1;
                    boolean top = i + 1 == sections.length || sections[i + 1] != sections[i] + 1;
                    // Adjacent cleared sections form one prism; their shared horizontal face is internal.
                    for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
                        if (bottom) vre$checkBoth(block, sky, cursor.set(minX + x, baseY, minZ + z));
                        if (top) vre$checkBoth(block, sky, cursor.set(minX + x, baseY + 15, minZ + z));
                    }
                }
                // One native propagation pass consumes both layers' check sets. No generation tickets
                // or light-correct flags are modified by this completion acknowledgement.
                if (replaySkySources && sky instanceof SkyLightEngine skyEngine) {
                    // Existing 15-valued sources can feed old 14-valued interior air. Replaying
                    // increases alone prunes at those unchanged 14 nodes and misses newly opened
                    // side corridors. Native checkNode performs decrease-then-increase at each
                    // source/shadow interface, invalidating the old gradient before rebuilding it.
                    int[] lowest = new int[256];
                    NativeAccess.SkySources sources = (NativeAccess.SkySources) (Object) skyEngine;
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        lowest[(z << 4) | x] = sources.vre$lowestSourceY(minX + x, minZ + z, Integer.MAX_VALUE);
                    // x/z edges already participate in the six exterior faces. Equal-height open
                    // columns add no work; a narrow old shaft adds only its vertical source edges.
                    for (int z = 1; z < 15; z++) for (int x = 1; x < 15; x++) {
                        int column = (z << 4) | x;
                        int low = lowest[column];
                        int high = Math.max(Math.max(lowest[column - 1], lowest[column + 1]),
                                Math.max(lowest[column - 16], lowest[column + 16]));
                        if (low >= high) continue;
                        for (int section : sections) {
                            int bottom = Math.max(section << 4, low);
                            int top = Math.min((section << 4) + 16, high);
                            for (int y = bottom; y < top; y++)
                                sky.checkBlock(cursor.set(minX + x, y, minZ + z));
                        }
                    }
                }
                runUpdate();
                result.complete(null);
            } catch (RuntimeException | LinkageError failure) { result.completeExceptionally(failure); }
        }, position.toLong(), ((NativeAccess.ChunkSave) chunkMap).vre$queueLevel(position.toLong())));
        return result;
    }

    private static void vre$checkBoth(LayerLightEventListener block, LayerLightEventListener sky, BlockPos position) {
        block.checkBlock(position);
        sky.checkBlock(position);
    }
}
