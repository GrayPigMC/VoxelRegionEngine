/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Version-specific mixin bridges; these are never part of the portable engine API. */
public final class NativeAccess {
    public interface ChunkSave {
        boolean vre$save(ChunkAccess chunk);
        IntSupplier vre$queueLevel(long position);
    }
    /** A negative revision means mutation provenance is unavailable and cannot prove a stable image. */
    public interface ChunkRevision {
        long vre$mutationRevision();
    }
    public interface LightFence {
        CompletableFuture<Void> vre$afterChunkWork(ChunkPos position);
        CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs);
        CompletableFuture<Void> vre$batchChecks(ChunkPos position, int[] sectionYs, boolean replaySkySources);
    }
    public interface SectionStats {
        short vre$nonEmptyBlockCount();
        int vre$accurateNonAirCount();
    }
    public interface SkySources {
        int vre$lowestSourceY(int worldX, int worldZ, int missingValue);
    }
    private NativeAccess() {}
}
