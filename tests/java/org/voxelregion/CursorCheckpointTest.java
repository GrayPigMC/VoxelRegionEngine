/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.ArrayList;
import java.util.List;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.RangeSink;
import org.voxelregion.geometry.Sphere;
import org.voxelregion.geometry.SphereShell;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.Volume;
import org.voxelregion.spatial.ChunkCursor;
import org.voxelregion.spatial.ChunkKey;

/** Checkpoint continuation including the iterator's hidden prefetched state. */
public final class CursorCheckpointTest {
    private static long comparisons;

    private CursorCheckpointTest() { }

    public static void main(String[] args) {
        if (!CursorCheckpointTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        for (double center : new double[] {-512.5, -16.5, 0.5, 511.5}) {
            verify(Sphere.of(center, -0.5, center, 48), -32, 32);
            verify(new SphereShell(Point3.of(center, -0.5, center), 80, 96, -12, 25), -32, 32);
        }
        verify(new BoxVolume(new IntBox(-528, -32, -528, 528, 32, 528)), -32, 32);
        verify(Sphere.of(0, 0, 0, 0), -64, 320);
        verify(new BoxVolume(new IntBox(29_999_984, -30_000_000, -30_000_000,
                30_000_000, -29_999_984, -29_999_984)), -30_000_000, -29_999_984);
        validationAndConstantRestore();
        System.out.println("CursorCheckpointTest passed; continued chunk comparisons=" + comparisons);
    }

    private static void verify(Volume volume, int minY, int maxY) {
        List<ChunkKey> expected = drain(new ChunkCursor(volume, minY, maxY));
        ChunkCursor cursor = new ChunkCursor(volume, minY, maxY);
        int emitted = 0;
        while (true) {
            ChunkCursor.Checkpoint before = cursor.checkpoint();
            // Check several complete tails, plus every boundary for small cases.
            if (expected.size() < 100 || emitted % 997 == 0) {
                assert drain(ChunkCursor.restore(volume, minY, maxY, detached(before)))
                        .equals(expected.subList(emitted, expected.size()));
            }
            boolean hasNext = cursor.hasNext();
            ChunkCursor.Checkpoint prefetched = cursor.checkpoint();
            cursor = ChunkCursor.restore(volume, minY, maxY, detached(prefetched));
            assert cursor.hasNext() == hasNext;
            if (!hasNext) break;
            assert cursor.next().equals(expected.get(emitted));
            emitted++;
            comparisons++;
        }
        assert emitted == expected.size();
        assert cursor.checkpoint().exhausted();
        assert !ChunkCursor.restore(volume, minY, maxY, cursor.checkpoint()).hasNext();
    }

    private static ChunkCursor.Checkpoint detached(ChunkCursor.Checkpoint c) {
        IntBox b = c.clippedBounds();
        return new ChunkCursor.Checkpoint(c.formatVersion(), new IntBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()),
                c.regionX(), c.regionZ(), c.chunkX(), c.chunkZ(), c.exhausted());
    }

    private static void validationAndConstantRestore() {
        Volume sphere = Sphere.of(0.5, 64.5, 0.5, 2000);
        CountingVolume counted = new CountingVolume(sphere);
        ChunkCursor cursor = new ChunkCursor(counted, -64, 320);
        for (int i = 0; i < 10_000; i++) cursor.next();
        cursor.hasNext();
        ChunkCursor.Checkpoint saved = cursor.checkpoint();
        counted.calls = 0;
        ChunkCursor restored = ChunkCursor.restore(counted, -64, 320, saved);
        assert counted.calls <= 1 : "Restore must not replay prior chunks or regions";
        assert restored.next().equals(cursor.next());
        IntBox bounds = saved.clippedBounds();
        rejects(() -> new ChunkCursor.Checkpoint(2, bounds, 0, 0, 0, 0, false));
        rejects(() -> new ChunkCursor.Checkpoint(1, bounds, 1, 0, 0, 0, true));
        rejects(() -> ChunkCursor.restore(sphere, -64, 320,
                new ChunkCursor.Checkpoint(1, bounds, 2_000_000, 0, 64_000_000, 0, false)));
        rejects(() -> ChunkCursor.restore(sphere, -64, 320,
                new ChunkCursor.Checkpoint(1, bounds, saved.regionX() + 1, saved.regionZ(), saved.chunkX(), saved.chunkZ(), false)));
        rejects(() -> ChunkCursor.restore(sphere, -63, 320, saved));
        rejects(() -> ChunkCursor.restore(Sphere.of(0.5, 64.5, 0.5, 1999), -64, 320, saved));
        Volume empty = Sphere.of(0, 0, 0, 0);
        ChunkCursor.Checkpoint finished = new ChunkCursor(empty, -64, 320).checkpoint();
        rejects(() -> ChunkCursor.restore(empty, -64, 320,
                new ChunkCursor.Checkpoint(1, finished.clippedBounds(), 0, 0, 0, 0, false)));

        Volume noSelection = new Volume() {
            @Override public IntBox bounds() { return new IntBox(0, 0, 0, 512, 16, 512); }
            @Override public Coverage classify(IntBox box) { return Coverage.OUTSIDE; }
            @Override public boolean contains(int x, int y, int z) { return false; }
            @Override public void emitRanges(IntBox clip, RangeSink sink) { }
        };
        ChunkCursor.Checkpoint initial = new ChunkCursor(noSelection, 0, 16).checkpoint();
        assert !ChunkCursor.restore(noSelection, 0, 16, initial).hasNext();
        rejects(() -> ChunkCursor.restore(noSelection, 0, 16,
                new ChunkCursor.Checkpoint(1, initial.clippedBounds(), 0, 0, 0, 1, false)));
    }

    private static List<ChunkKey> drain(ChunkCursor cursor) {
        ArrayList<ChunkKey> result = new ArrayList<>();
        cursor.forEachRemaining(result::add);
        return result;
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static final class CountingVolume implements Volume {
        private final Volume delegate;
        int calls;
        CountingVolume(Volume delegate) { this.delegate = delegate; }
        @Override public IntBox bounds() { return delegate.bounds(); }
        @Override public Coverage classify(IntBox box) { calls++; return delegate.classify(box); }
        @Override public boolean contains(int x, int y, int z) { return delegate.contains(x, y, z); }
        @Override public void emitRanges(IntBox clip, RangeSink sink) { delegate.emitRanges(clip, sink); }
    }
}
