/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.function.Predicate;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.Cylinder;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.Sphere;
import org.voxelregion.geometry.Volume;
import org.voxelregion.geometry.Volumes;
import org.voxelregion.spatial.CenterOutChunkCursor;
import org.voxelregion.spatial.ChunkKey;

/** Brute-force voxel membership plus independent global BigInteger sorting oracle. */
public final class CenterOutChunkCursorTest {
    private static long checkedChunks, restorations;
    public static void main(String[] args) {
        if (!CenterOutChunkCursorTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        smallShapes();
        intersectionsAndConservativeShapes();
        extremesAndInvalidCheckpoints();
        boundedInitializationAndRestore();
        radius2000();
        System.out.println("CenterOutChunkCursorTest passed; checked chunks=" + checkedChunks + ", restorations=" + restorations);
    }

    private static void smallShapes() {
        Random random = new Random(0x43454e544552L);
        for (int i = 0; i < 70; i++) {
            Point3 center = new Point3(random.nextInt(129) - 64, random.nextInt(17) - 8, random.nextInt(129) - 64);
            int radius2 = random.nextInt(81);
            Volume volume = switch (i % 3) {
                case 0 -> new Sphere(center, radius2);
                case 1 -> new Cylinder(center, radius2, -5, 9);
                default -> {
                    int x = random.nextInt(80) - 40, z = random.nextInt(80) - 40;
                    yield new BoxVolume(new IntBox(x, -6, z, x + random.nextInt(65), 8, z + random.nextInt(65)));
                }
            };
            checkExact(volume, center, -3, 6, true);
        }
        for (Point3 center : List.of(new Point3(0, 0, 0), new Point3(-32, 1, 32),
                new Point3(32, -1, -32), new Point3(-1, 1, -1))) {
            Volume box = new BoxVolume(new IntBox(-48, -1, -48, 48, 2, 48));
            List<ChunkKey> chunks = checkExact(box, center, -1, 2, true);
            assert chunks.get(0).equals(new ChunkKey((int) Math.floorDiv(center.x2(), 32), (int) Math.floorDiv(center.z2(), 32)));
        }
        checkExact(Sphere.of(-0.5, 0.5, -0.5, 0), Point3.of(-0.5, 0.5, -0.5), -1, 2, true);
        checkExact(Sphere.of(0, 0, 0, 0), Point3.of(0, 0, 0), -1, 2, true);
        checkExact(new BoxVolume(new IntBox(-5, 10, -5, 5, 20, 5)), Point3.of(0, 0, 0), -3, 4, true);
    }

    private static void intersectionsAndConservativeShapes() {
        Point3 center = Point3.of(-0.5, 1.5, 0);
        Volume sphere = Sphere.of(-0.5, 1.5, 0, 31);
        Volume box = new BoxVolume(new IntBox(-38, -4, -27, 38, 5, 27));
        for (Volume volume : List.of(Volumes.intersection(sphere, box), Volumes.union(sphere, box),
                Volumes.difference(box, sphere), Volumes.difference(sphere, sphere))) {
            List<ChunkKey> actual = consume(new CenterOutChunkCursor(volume, center, -3, 5));
            List<ChunkKey> sorted = new ArrayList<>(actual); sorted.sort(order(center));
            assert actual.equals(sorted) && new HashSet<>(actual).size() == actual.size();
            List<ChunkKey> exactNonempty = oracle(volume, center, -3, 5, key -> anyVoxel(volume, key, -3, 5));
            assert new HashSet<>(actual).containsAll(exactNonempty) : "Conservative classification omitted selected voxels";
            for (ChunkKey key : actual) assert volume.classify(key.bounds(-3, 5)) != Coverage.OUTSIDE;
            verifyEveryRestore(volume, center, -3, 5, actual);
            checkedChunks += actual.size();
        }
    }

    private static void extremesAndInvalidCheckpoints() {
        for (int edge : new int[] { -30_000_000, 29_999_936 }) {
            Volume box = new BoxVolume(new IntBox(edge, -2, edge, edge + 64, 2, edge + 64));
            Point3 center = new Point3(2L * edge + 1, 1, 2L * edge + 1);
            checkExact(box, center, -1, 1, true);
            checkExact(box, new Point3(-center.x2(), 1, -center.z2()), -1, 1, true);
        }
        Volume shape = new BoxVolume(new IntBox(-32, -2, -32, 32, 2, 32));
        Point3 center = Point3.of(0, 0, 0);
        var cursor = new CenterOutChunkCursor(shape, center, -2, 2); cursor.next();
        var saved = cursor.checkpoint();
        rejects(() -> new CenterOutChunkCursor(shape, center, -2, 2, 3));
        rejects(() -> new CenterOutChunkCursor(shape, center, -2, 2, 0));
        rejects(() -> new CenterOutChunkCursor(shape, center, 2, -2));
        rejects(() -> CenterOutChunkCursor.restore(shape, Point3.of(0.5, 0, 0), -2, 2, saved));
        rejects(() -> CenterOutChunkCursor.restore(shape, center, -1, 2, saved));
        rejects(() -> new CenterOutChunkCursor.Checkpoint(2, saved.clippedBounds(), center, 0, 0, false, false));
        rejects(() -> new CenterOutChunkCursor.Checkpoint(1, saved.clippedBounds(), center, 1, 0, false, false));
        rejects(() -> CenterOutChunkCursor.restore(shape, center, -2, 2,
                new CenterOutChunkCursor.Checkpoint(1, saved.clippedBounds(), center, 999, 0, true, false)));
        Volume hollow = Volumes.difference(shape, new BoxVolume(new IntBox(0, -2, 0, 16, 2, 16)));
        rejects(() -> CenterOutChunkCursor.restore(hollow, center, -2, 2, saved));
    }

    private static void boundedInitializationAndRestore() {
        // An enormous one-row volume must not allocate/sort its 3.75 million chunks.
        CountingVolume counted = new CountingVolume(new BoxVolume(new IntBox(-30_000_000, 0, 0, 30_000_000, 1, 16)));
        Point3 center = Point3.of(0.5, 0.5, 0.5);
        var cursor = new CenterOutChunkCursor(counted, center, 0, 1, 1);
        assert cursor.frontierRows() == 1 && cursor.estimatedBytes() == 384 && counted.classifications == 1;
        for (int i = 0; i < 2000; i++) cursor.next();
        var saved = cursor.checkpoint();
        int before = counted.classifications;
        var restored = CenterOutChunkCursor.restore(counted, center, 0, 1, 1, saved);
        assert counted.classifications - before <= 2 : "Restore rescanned emitted chunks";
        for (int i = 0; i < 100; i++) assert cursor.next().equals(restored.next());
        restorations++;
        Volume wide = new BoxVolume(new IntBox(-40_000, 0, -40_000, 40_000, 1, 40_000));
        rejects(() -> new CenterOutChunkCursor(wide, center, 0, 1));
    }

    private static void radius2000() {
        Point3 center = Point3.of(0.5, 64.5, 0.5);
        Volume sphere = new Sphere(center, 4000);
        List<ChunkKey> expected = oracle(sphere, center, -64, 320, key -> {
            // Center Y is in build height. Pick the closest X/Z voxel center independently.
            double x = Math.max(key.x() * 16 + 0.5, Math.min(key.x() * 16 + 15.5, 0.5));
            double z = Math.max(key.z() * 16 + 0.5, Math.min(key.z() * 16 + 15.5, 0.5));
            return (x - 0.5) * (x - 0.5) + (z - 0.5) * (z - 0.5) <= 4_000_000;
        });
        var cursor = new CenterOutChunkCursor(sphere, center, -64, 320);
        assert cursor.frontierRows() == 251 && cursor.estimatedBytes() < 33_000;
        for (int i = 0; i < expected.size(); i++) {
            assert cursor.hasNext() && cursor.next().equals(expected.get(i)) : "R2000 order at " + i;
            if (i == 0 || i == 1999 || i == expected.size() / 2) {
                cursor = CenterOutChunkCursor.restore(sphere, center, -64, 320, cursor.checkpoint()); restorations++;
            }
        }
        assert !cursor.hasNext(); checkedChunks += expected.size();
        System.out.println("R2000 center-out candidates=" + expected.size() + ", frontier rows=" + cursor.frontierRows()
                + ", conservative cursor bytes=" + cursor.estimatedBytes());
    }

    private static List<ChunkKey> checkExact(Volume volume, Point3 center, int minY, int maxY, boolean restore) {
        List<ChunkKey> expected = oracle(volume, center, minY, maxY, key -> anyVoxel(volume, key, minY, maxY));
        List<ChunkKey> actual = consume(new CenterOutChunkCursor(volume, center, minY, maxY));
        assert actual.equals(expected) : "Cursor disagrees with voxel/global-sort oracle\n" + actual + "\n" + expected;
        if (restore) verifyEveryRestore(volume, center, minY, maxY, actual);
        checkedChunks += actual.size(); return actual;
    }

    private static void verifyEveryRestore(Volume volume, Point3 center, int minY, int maxY, List<ChunkKey> expected) {
        var cursor = new CenterOutChunkCursor(volume, center, minY, maxY);
        for (int completed = 0; completed <= expected.size(); completed++) {
            for (boolean prefetched : new boolean[] { false, true }) {
                if (prefetched) cursor.hasNext();
                var restored = CenterOutChunkCursor.restore(volume, center, minY, maxY, cursor.checkpoint());
                assert consume(restored).equals(expected.subList(completed, expected.size())) : "Restore lost/duplicated a candidate";
                restorations++;
            }
            if (completed < expected.size()) assert cursor.next().equals(expected.get(completed));
        }
        assert !cursor.hasNext();
        try { cursor.next(); throw new AssertionError("Exhausted cursor returned a value"); }
        catch (NoSuchElementException expectedFailure) { }
    }

    private static List<ChunkKey> oracle(Volume volume, Point3 center, int minY, int maxY, Predicate<ChunkKey> selected) {
        IntBox bounds = volume.bounds().intersection(new IntBox(IntBox.WORLD_MIN, minY, IntBox.WORLD_MIN,
                IntBox.WORLD_MAX, maxY, IntBox.WORLD_MAX));
        List<ChunkKey> result = new ArrayList<>();
        if (!bounds.isEmpty()) for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX() - 1, 16); x++)
            for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ() - 1, 16); z++) {
                ChunkKey key = new ChunkKey(x, z); if (selected.test(key)) result.add(key);
            }
        result.sort(order(center)); return result;
    }

    private static Comparator<ChunkKey> order(Point3 center) {
        int homeX = (int) Math.floorDiv(center.x2(), 32), homeZ = (int) Math.floorDiv(center.z2(), 32);
        return Comparator.comparing((ChunkKey key) -> distanceOracle(center, key))
                .thenComparingInt(key -> key.x() == homeX && key.z() == homeZ ? 0 : 1)
                .thenComparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z);
    }

    private static BigInteger distanceOracle(Point3 center, ChunkKey key) {
        BigInteger x = BigInteger.valueOf(center.x2()), z = BigInteger.valueOf(center.z2());
        BigInteger closestX = x.max(BigInteger.valueOf(32L * key.x())).min(BigInteger.valueOf(32L * key.x() + 32));
        BigInteger closestZ = z.max(BigInteger.valueOf(32L * key.z())).min(BigInteger.valueOf(32L * key.z() + 32));
        return x.subtract(closestX).pow(2).add(z.subtract(closestZ).pow(2));
    }

    private static boolean anyVoxel(Volume volume, ChunkKey key, int minY, int maxY) {
        IntBox box = key.bounds(minY, maxY).intersection(volume.bounds());
        for (int x = box.minX(); x < box.maxX(); x++) for (int z = box.minZ(); z < box.maxZ(); z++)
            for (int y = box.minY(); y < box.maxY(); y++) if (volume.contains(x, y, z)) return true;
        return false;
    }

    private static List<ChunkKey> consume(CenterOutChunkCursor cursor) {
        List<ChunkKey> result = new ArrayList<>(); cursor.forEachRemaining(result::add); return result;
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejected input");
    }
    private static final class CountingVolume implements Volume {
        final Volume delegate; int classifications;
        CountingVolume(Volume delegate) { this.delegate = delegate; }
        public IntBox bounds() { return delegate.bounds(); }
        public Coverage classify(IntBox box) { classifications++; return delegate.classify(box); }
        public boolean contains(int x, int y, int z) { return delegate.contains(x, y, z); }
        public void emitRanges(IntBox box, org.voxelregion.geometry.RangeSink sink) { delegate.emitRanges(box, sink); }
    }
    private CenterOutChunkCursorTest() { }
}
