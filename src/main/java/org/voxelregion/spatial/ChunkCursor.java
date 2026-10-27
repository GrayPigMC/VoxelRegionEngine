/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.spatial;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Volume;

/**
 * Lazy, constant-storage chunk iterator for a volume and a half-open build
 * height. Chunks are grouped into 32x32 regions using floor division, ordered
 * by region X, region Z, chunk X, then chunk Z. An OUTSIDE region is skipped;
 * an INSIDE region does not require individual chunk classification.
 * This cursor is mutable and not thread safe; construction does not scan.
 */
public final class ChunkCursor implements Iterator<ChunkKey> {
    private final Volume volume;
    private final int minY;
    private final int maxY;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final int minRegionZ;
    private final int maxRegionX;
    private final int maxRegionZ;
    private int regionX;
    private int regionZ;
    private int chunkX;
    private int chunkZ;
    private int startChunkZ;
    private int endChunkX;
    private int endChunkZ;
    private Coverage regionCoverage;
    private boolean regionOpen;
    private boolean exhausted;
    private ChunkKey next;

    public ChunkCursor(Volume volume, int minY, int maxY) {
        this.volume = Objects.requireNonNull(volume, "volume");
        IntBox clipped = volume.bounds().intersection(new IntBox(IntBox.WORLD_MIN, minY,
                IntBox.WORLD_MIN, IntBox.WORLD_MAX, maxY, IntBox.WORLD_MAX));
        this.minY = clipped.minY();
        this.maxY = clipped.maxY();
        exhausted = clipped.isEmpty();
        minChunkX = Math.floorDiv(clipped.minX(), 16);
        minChunkZ = Math.floorDiv(clipped.minZ(), 16);
        maxChunkX = Math.floorDiv(clipped.maxX() - 1, 16);
        maxChunkZ = Math.floorDiv(clipped.maxZ() - 1, 16);
        regionX = Math.floorDiv(minChunkX, 32);
        regionZ = Math.floorDiv(minChunkZ, 32);
        minRegionZ = regionZ;
        maxRegionX = Math.floorDiv(maxChunkX, 32);
        maxRegionZ = Math.floorDiv(maxChunkZ, 32);
    }

    @Override
    public boolean hasNext() {
        if (next != null) return true;
        while (!exhausted) {
            if (!regionOpen && !openRegion()) continue;
            ChunkKey candidate = new ChunkKey(chunkX, chunkZ);
            Coverage coarse = regionCoverage;
            advanceChunk();
            if (coarse == Coverage.INSIDE || volume.classify(candidate.bounds(minY, maxY)) != Coverage.OUTSIDE) {
                next = candidate;
                return true;
            }
        }
        return false;
    }

    @Override
    public ChunkKey next() {
        if (!hasNext()) throw new NoSuchElementException("Chunk cursor exhausted");
        ChunkKey result = next;
        next = null;
        return result;
    }

    private boolean openRegion() {
        chunkX = Math.max(minChunkX, regionX * 32);
        chunkZ = Math.max(minChunkZ, regionZ * 32);
        startChunkZ = chunkZ;
        endChunkX = Math.min(maxChunkX, regionX * 32 + 31);
        endChunkZ = Math.min(maxChunkZ, regionZ * 32 + 31);
        regionCoverage = volume.classify(new IntBox(chunkX * 16, minY, chunkZ * 16,
                (endChunkX + 1) * 16, maxY, (endChunkZ + 1) * 16));
        if (regionCoverage == Coverage.OUTSIDE) {
            advanceRegion();
            return false;
        }
        regionOpen = true;
        return true;
    }

    private void advanceChunk() {
        if (chunkZ < endChunkZ) {
            chunkZ++;
        } else if (chunkX < endChunkX) {
            chunkX++;
            chunkZ = startChunkZ;
        } else {
            regionOpen = false;
            advanceRegion();
        }
    }

    private void advanceRegion() {
        if (regionZ < maxRegionZ) {
            regionZ++;
        } else if (regionX < maxRegionX) {
            regionX++;
            regionZ = minRegionZ;
        } else {
            exhausted = true;
        }
    }
}
