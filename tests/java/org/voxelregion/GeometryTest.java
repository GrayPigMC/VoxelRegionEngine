/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.Cylinder;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.RangeSink;
import org.voxelregion.geometry.Sphere;
import org.voxelregion.geometry.Volume;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.spatial.ChunkCursor;
import org.voxelregion.spatial.ChunkKey;
import org.voxelregion.spatial.SectionKey;

/** Dependency-free regression entry point; run with java -ea. */
public final class GeometryTest {
    private static final IntBox SMALL = new IntBox(-4, -4, -4, 5, 5, 5);
    private static long oracleVoxels;

    private GeometryTest() { }

    public static void main(String[] args) {
        if (!GeometryTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        inputValidation();
        exhaustiveShapes();
        extremeCoordinates();
        squareRootBoundaries();
        masks();
        cursors();
        largeCursor();
        System.out.println("GeometryTest passed; independently checked voxels=" + oracleVoxels);
    }

    private static void inputValidation() {
        assert Point3.of(-0.5, 0, 1.5).equals(new Point3(-1, 0, 3));
        rejects(() -> Point3.of(Double.NaN, 0, 0));
        rejects(() -> Point3.of(Double.POSITIVE_INFINITY, 0, 0));
        rejects(() -> Point3.of(0.25, 0, 0));
        rejects(() -> Point3.of(30_000_000.5, 0, 0));
        rejects(() -> new Point3(Long.MIN_VALUE, 0, 0));
        rejects(() -> new Sphere(new Point3(0, 0, 0), -1));
        rejects(() -> new Sphere(new Point3(0, 0, 0), 120_000_001));
        rejects(() -> Sphere.of(0, 0, 0, 0.25));
        rejects(() -> Sphere.of(0, 0, 0, Double.NEGATIVE_INFINITY));
        rejects(() -> new IntBox(1, 0, 0, 0, 1, 1));
        rejects(() -> new IntBox(-30_000_001, 0, 0, 0, 1, 1));
        rejects(() -> new IntBox(0, 0, 0, 0, 30_000_001, 0));
        rejects(() -> Cylinder.of(0, 0, 1, 1, 0));
        rejects(() -> new SectionKey(Integer.MAX_VALUE, 0, 0));
        rejects(() -> SectionKey.fromBlock(30_000_000, 0, 0));
        rejects(() -> new ChunkCursor(Sphere.of(0, 0, 0, 1), 1, -1));

        Sphere center = Sphere.of(-0.5, -1.5, -2.5, 0);
        assert center.contains(-1, -2, -3);
        assert center.classify(new IntBox(-1, -2, -3, 0, -1, -2)) == Coverage.INSIDE;
        assert Sphere.of(0, 0, 0, 0).classify(SMALL) == Coverage.OUTSIDE;
        assert Sphere.of(0, 0, 0, 0.5).classify(SMALL) == Coverage.OUTSIDE;
        IntBox empty = new IntBox(1, 1, 1, 1, 2, 2);
        assert center.classify(empty) == Coverage.OUTSIDE;
        assert SMALL.contains(empty);
        assert SMALL.intersection(new IntBox(10, 10, 10, 11, 11, 11)).isEmpty();
    }

    private static void exhaustiveShapes() {
        for (long cx = -3; cx <= 3; cx++) {
            for (long cy = -3; cy <= 3; cy++) {
                for (long cz = -3; cz <= 3; cz++) {
                    for (long radius = 0; radius <= 8; radius++) {
                        Sphere sphere = new Sphere(new Point3(cx, cy, cz), radius);
                        checkShape(sphere, (x, y, z) -> sphereOracle(sphere, x, y, z));
                    }
                }
            }
        }
        for (long cx = -4; cx <= 4; cx++) {
            for (long cz = -4; cz <= 4; cz++) {
                for (long radius = 0; radius <= 8; radius++) {
                    for (int minY = -2; minY <= 2; minY++) {
                        Cylinder cylinder = new Cylinder(new Point3(cx, 3, cz), radius, minY, 3);
                        checkShape(cylinder, (x, y, z) -> cylinderOracle(cylinder, x, y, z));
                    }
                }
            }
        }
        for (int min = -4; min <= 4; min++) {
            for (int max = min; max <= 5; max++) {
                BoxVolume box = new BoxVolume(new IntBox(min, -3, -2, max, 3, 2));
                checkShape(box, (x, y, z) -> x >= minValue(box) && x < box.bounds().maxX()
                        && y >= -3 && y < 3 && z >= -2 && z < 2);
            }
        }
    }

    private static int minValue(BoxVolume box) { return box.bounds().minX(); }

    private static void checkShape(Volume volume, Oracle oracle) {
        boolean[] emitted = new boolean[9 * 9 * 9];
        volume.emitRanges(SMALL, (x, z, minY, maxY) -> {
            assert minY < maxY;
            assert SMALL.contains(x, minY, z) && SMALL.contains(x, maxY - 1, z);
            for (int y = minY; y < maxY; y++) {
                int index = (x + 4) + 9 * (z + 4) + 81 * (y + 4);
                assert !emitted[index] : "Duplicate range voxel";
                emitted[index] = true;
            }
        });
        int count = 0;
        for (int y = -4; y < 5; y++) {
            for (int z = -4; z < 5; z++) {
                for (int x = -4; x < 5; x++) {
                    boolean expected = oracle.contains(x, y, z);
                    int index = (x + 4) + 9 * (z + 4) + 81 * (y + 4);
                    assert emitted[index] == expected : "Range/oracle disagreement";
                    assert volume.contains(x, y, z) == expected : "contains/oracle disagreement";
                    assert !expected || volume.bounds().contains(x, y, z);
                    if (expected) count++;
                    oracleVoxels++;
                }
            }
        }
        assert volume.classify(SMALL) == coverage(count, emitted.length);
        // Includes inside, outside and partial boxes, with negative and odd endpoints.
        for (int y = -4; y <= 2; y += 2) {
            for (int z = -4; z <= 2; z += 2) {
                for (int x = -4; x <= 2; x += 2) {
                    IntBox box = new IntBox(x, y, z, x + 2, y + 2, z + 2);
                    int selected = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                if (oracle.contains(x + dx, y + dy, z + dz)) selected++;
                            }
                        }
                    }
                    assert volume.classify(box) == coverage(selected, 8) : "Classification/oracle disagreement";
                    oracleVoxels += 8;
                }
            }
        }
    }

    private static boolean sphereOracle(Sphere sphere, int x, int y, int z) {
        // Independent direct center-distance calculation, without shape bounds,
        // classification, range generation, or integer-square-root helpers.
        long dx = 2L * x + 1 - sphere.center().x2();
        long dy = 2L * y + 1 - sphere.center().y2();
        long dz = 2L * z + 1 - sphere.center().z2();
        return dx * dx + dy * dy + dz * dz <= sphere.radius2() * sphere.radius2();
    }

    private static boolean cylinderOracle(Cylinder cylinder, int x, int y, int z) {
        long dx = 2L * x + 1 - cylinder.center().x2();
        long dz = 2L * z + 1 - cylinder.center().z2();
        return y >= cylinder.minY() && y < cylinder.maxY()
                && dx * dx + dz * dz <= cylinder.radius2() * cylinder.radius2();
    }

    private static Coverage coverage(int selected, int size) {
        return selected == 0 ? Coverage.OUTSIDE : selected == size ? Coverage.INSIDE : Coverage.PARTIAL;
    }

    private static void extremeCoordinates() {
        Random random = new Random(0x2916_0027L);
        int[] starts = {-30_000_000, -29_999_998, -1, 29_999_996, 29_999_998};
        for (int i = 0; i < 1_000; i++) {
            Point3 center = new Point3(random.nextLong(120_000_001L) - 60_000_000,
                    random.nextLong(120_000_001L) - 60_000_000,
                    random.nextLong(120_000_001L) - 60_000_000);
            Sphere sphere = new Sphere(center, random.nextLong(120_000_001L));
            int bx = starts[random.nextInt(starts.length)];
            int by = starts[random.nextInt(starts.length)];
            int bz = starts[random.nextInt(starts.length)];
            IntBox box = new IntBox(bx, by, bz, bx + 2, by + 2, bz + 2);
            Set<String> emitted = new HashSet<>();
            sphere.emitRanges(box, (x, z, lo, hi) -> {
                assert lo < hi && box.contains(x, lo, z) && box.contains(x, hi - 1, z);
                for (int y = lo; y < hi; y++) assert emitted.add(x + "," + y + "," + z);
            });
            int count = 0;
            for (int x = bx; x < bx + 2; x++) {
                for (int y = by; y < by + 2; y++) {
                    for (int z = bz; z < bz + 2; z++) {
                        BigInteger dx = BigInteger.valueOf(2L * x + 1 - center.x2());
                        BigInteger dy = BigInteger.valueOf(2L * y + 1 - center.y2());
                        BigInteger dz = BigInteger.valueOf(2L * z + 1 - center.z2());
                        boolean expected = dx.pow(2).add(dy.pow(2)).add(dz.pow(2))
                                .compareTo(BigInteger.valueOf(sphere.radius2()).pow(2)) <= 0;
                        assert sphere.contains(x, y, z) == expected;
                        assert emitted.contains(x + "," + y + "," + z) == expected;
                        if (expected) count++;
                        oracleVoxels++;
                    }
                }
            }
            assert sphere.classify(box) == coverage(count, 8);
            assert !sphere.contains(Integer.MIN_VALUE, 0, 0);
            assert !sphere.contains(0, Integer.MAX_VALUE, 0);
        }
        Sphere max = new Sphere(new Point3(-60_000_000, -60_000_000, -60_000_000), 120_000_000);
        assert !max.contains(29_999_999, 29_999_999, 29_999_999);
        assert max.contains(-30_000_000, -30_000_000, -30_000_000);
        Sphere edge = Sphere.of(29_999_999.5, -29_999_999.5, 0.5, 0);
        assert edge.bounds().equals(new IntBox(29_999_999, -30_000_000, 0, 30_000_000, -29_999_999, 1));
        assert Sphere.of(30_000_000, 30_000_000, 30_000_000, 0).bounds().isEmpty();
    }

    private static void squareRootBoundaries() {
        Random random = new Random(0x5A17_B0A1L);
        for (int i = 0; i < 2_000; i++) {
            long radius2 = 1 + random.nextLong(59_999_998);
            int x = random.nextInt(4);
            int z = random.nextInt(4);
            Sphere sphere = new Sphere(new Point3(1, 1, 1), radius2);
            IntBox column = new IntBox(x, -30_000_000, z, x + 1, 30_000_000, z + 1);
            BigInteger remaining = BigInteger.valueOf(radius2).pow(2)
                    .subtract(BigInteger.valueOf(2L * x).pow(2)).subtract(BigInteger.valueOf(2L * z).pow(2));
            int[] actual = {0, 0, 0};
            sphere.emitRanges(column, (rx, rz, lo, hi) -> {
                assert rx == x && rz == z;
                actual[0]++;
                actual[1] = lo;
                actual[2] = hi;
            });
            if (remaining.signum() < 0) {
                assert actual[0] == 0;
            } else {
                // Block Y centers are an even offset (2*y) from center 0.5.
                long reachBlocks = remaining.sqrt().longValueExact() / 2;
                assert actual[0] == 1;
                assert actual[1] == -reachBlocks;
                assert actual[2] == reachBlocks + 1;
            }
        }
        Sphere pythagorean = Sphere.of(0.5, 0.5, 0.5, 5);
        assert pythagorean.contains(3, 4, 0);
        assert !pythagorean.contains(3, 4, 1);
        // (119,999,999^2 - 1) rounds to the same double as the perfect square.
        // Correcting the sqrt candidate must exclude the world-edge voxel.
        for (long centerY : new long[] {-60_000_000, 60_000_000}) {
            Sphere corrected = new Sphere(new Point3(0, centerY, 1), 119_999_999);
            IntBox column = new IntBox(0, -30_000_000, 0, 1, 30_000_000, 1);
            int[] result = {0, 0, 0};
            corrected.emitRanges(column, (x, z, lo, hi) -> {
                result[0]++;
                result[1] = lo;
                result[2] = hi;
            });
            assert result[0] == 1;
            assert result[1] == (centerY > 0 ? -29_999_999 : -30_000_000);
            assert result[2] == (centerY > 0 ? 30_000_000 : 29_999_999);
        }
    }

    private static void masks() {
        assert VoxelMask.builder().build() == VoxelMask.EMPTY;
        VoxelMask.Builder full = new VoxelMask.Builder();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) full.setRange(x, z, 0, 16);
        assert full.build() == VoxelMask.ALL;
        VoxelMask.Builder mutable = new VoxelMask.Builder().set(1, 2, 3);
        VoxelMask first = mutable.build();
        mutable.set(2, 2, 3);
        assert first.cardinality() == 1 && !first.contains(2, 2, 3);
        assert mutable.build().cardinality() == 2;
        assert VoxelMask.ALL.andNot(first).cardinality() == 4095;
        assert VoxelMask.ALL.andNot(first).union(first) == VoxelMask.ALL;
        assert first.andNot(first) == VoxelMask.EMPTY;
        assert VoxelMask.EMPTY.union(first) == first;
        assert first.equals(new VoxelMask.Builder().set(1, 2, 3).build());
        assert first.hashCode() == new VoxelMask.Builder().set(1, 2, 3).build().hashCode();
        expect(IndexOutOfBoundsException.class, () -> full.set(-1, 0, 0));
        expect(IndexOutOfBoundsException.class, () -> full.setRange(0, 0, 0, 17));
        expect(IndexOutOfBoundsException.class, () -> full.setRange(0, 0, 2, 1));
        expect(IndexOutOfBoundsException.class, () -> VoxelMask.EMPTY.contains(16, 0, 0));

        Random random = new Random(0x4096_0064);
        for (int trial = 0; trial < 100; trial++) {
            boolean[] a = new boolean[4096];
            boolean[] b = new boolean[4096];
            VoxelMask.Builder ab = VoxelMask.builder();
            VoxelMask.Builder bb = VoxelMask.builder();
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, z = (i >>> 4) & 15, y = i >>> 8;
                if (random.nextInt(3) == 0) { a[i] = true; ab.set(x, y, z); }
                if (random.nextInt(3) == 0) { b[i] = true; bb.set(x, y, z); }
            }
            VoxelMask am = ab.build(), bm = bb.build();
            checkMask(am, a);
            boolean[] union = new boolean[4096], difference = new boolean[4096];
            for (int i = 0; i < 4096; i++) {
                union[i] = a[i] || b[i];
                difference[i] = a[i] && !b[i];
            }
            checkMask(am.union(bm), union);
            checkMask(am.andNot(bm), difference);
        }
        boolean[] all = new boolean[4096];
        java.util.Arrays.fill(all, true);
        checkMask(VoxelMask.ALL, all);
        checkMask(VoxelMask.EMPTY, new boolean[4096]);
    }

    private static void checkMask(VoxelMask mask, boolean[] expected) {
        boolean[] seen = new boolean[4096];
        mask.forEachRanges((x, z, lo, hi) -> {
            assert 0 <= lo && lo < hi && hi <= 16;
            assert lo == 0 || !mask.contains(x, lo - 1, z);
            assert hi == 16 || !mask.contains(x, hi, z);
            for (int y = lo; y < hi; y++) {
                int index = x | (z << 4) | (y << 8);
                assert !seen[index];
                seen[index] = true;
            }
        });
        int count = 0;
        for (int i = 0; i < 4096; i++) {
            assert seen[i] == expected[i];
            assert mask.contains(i & 15, i >>> 8, (i >>> 4) & 15) == expected[i];
            if (expected[i]) count++;
        }
        assert mask.cardinality() == count;
        assert mask.isEmpty() == (count == 0);
        assert mask.isFull() == (count == 4096);
        assert mask.estimatedBytes() > 0;
    }

    private static void cursors() {
        assert SectionKey.fromBlock(-1, -17, -16).equals(new SectionKey(-1, -2, -1));
        assert new SectionKey(-1, -2, -1).bounds().equals(new IntBox(-16, -32, -16, 0, -16, 0));
        assert ChunkKey.fromBlock(-1, -16).equals(new ChunkKey(-1, -1));
        assert new ChunkKey(-1, 2).section(-3).chunk().equals(new ChunkKey(-1, 2));
        Sphere sphere = Sphere.of(-511.5, 0.5, -16.5, 52);
        Set<ChunkKey> expected = new HashSet<>();
        for (int cx = -36; cx <= -28; cx++) {
            for (int cz = -5; cz <= 3; cz++) {
                boolean found = false;
                for (int x = cx * 16; x < cx * 16 + 16 && !found; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        if (sphereOracle(sphere, x, 0, z)) { found = true; break; }
                    }
                }
                if (found) expected.add(new ChunkKey(cx, cz));
            }
        }
        ChunkCursor cursor = new ChunkCursor(sphere, -16, 16);
        Set<ChunkKey> actual = new HashSet<>();
        ChunkKey previous = null;
        while (cursor.hasNext()) {
            assert cursor.hasNext();
            ChunkKey key = cursor.next();
            assert actual.add(key);
            if (previous != null) assert ordered(previous, key);
            previous = key;
        }
        assert actual.equals(expected);
        assert !cursor.hasNext();
        expect(NoSuchElementException.class, cursor::next);
        assert !new ChunkCursor(sphere, 100, 101).hasNext();
        assert !new ChunkCursor(sphere, 0, 0).hasNext();
        assert !new ChunkCursor(Sphere.of(0, 0, 0, 0), -64, 320).hasNext();

        CountingVolume all = new CountingVolume(new BoxVolume(new IntBox(-512, 0, -512, 512, 16, 512)));
        ChunkCursor inside = new ChunkCursor(all, 0, 16);
        assert all.classifications == 0 : "Cursor construction must stay lazy";
        int count = 0;
        while (inside.hasNext()) { inside.next(); count++; }
        assert count == 4096;
        assert all.classifications == 4 : "INSIDE regions must bypass chunk classification";
        CountingVolume none = new CountingVolume(new BoxVolume(new IntBox(0, 0, 0, 0, 0, 0))) {
            @Override public IntBox bounds() { return new IntBox(-512, 0, -512, 512, 16, 512); }
        };
        assert !new ChunkCursor(none, 0, 16).hasNext();
        assert none.classifications == 4 : "OUTSIDE regions must skip their chunks";

        Volume worldEdge = new BoxVolume(new IntBox(29_999_984, -30_000_000, -30_000_000,
                30_000_000, -29_999_984, -29_999_984));
        ChunkCursor edge = new ChunkCursor(worldEdge, -30_000_000, -29_999_984);
        assert edge.next().equals(new ChunkKey(1_874_999, -1_875_000));
        assert !edge.hasNext();
    }

    private static boolean ordered(ChunkKey a, ChunkKey b) {
        int[] ak = {Math.floorDiv(a.x(), 32), Math.floorDiv(a.z(), 32), a.x(), a.z()};
        int[] bk = {Math.floorDiv(b.x(), 32), Math.floorDiv(b.z(), 32), b.x(), b.z()};
        for (int i = 0; i < ak.length; i++) {
            if (ak[i] != bk[i]) return ak[i] < bk[i];
        }
        return false;
    }

    private static void largeCursor() {
        Sphere sphere = Sphere.of(0.5, 64.5, 0.5, 2000);
        long expected = 0;
        for (int cx = -125; cx <= 125; cx++) {
            long dx = nearestBlockOffset(cx);
            for (int cz = -125; cz <= 125; cz++) {
                long dz = nearestBlockOffset(cz);
                if (dx * dx + dz * dz <= 4_000_000L) expected++;
            }
        }
        long actual = 0;
        ChunkCursor cursor = new ChunkCursor(sphere, -64, 320);
        ChunkKey previous = null;
        while (cursor.hasNext()) {
            ChunkKey key = cursor.next();
            assert previous == null || ordered(previous, key);
            previous = key;
            actual++;
        }
        assert actual == expected : "R2000 cursor/oracle mismatch";
        System.out.println("R2000 center=(0.5,64.5,0.5), buildY=[-64,320): chunks=" + actual);
    }

    private static long nearestBlockOffset(int chunk) {
        int min = chunk * 16, max = min + 15;
        return min > 0 ? min : max < 0 ? -(long) max : 0;
    }

    private static void rejects(Runnable action) { expect(IllegalArgumentException.class, action); }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            assert type.isInstance(error) : error;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private interface Oracle { boolean contains(int x, int y, int z); }

    private static class CountingVolume implements Volume {
        private final Volume delegate;
        int classifications;
        CountingVolume(Volume delegate) { this.delegate = delegate; }
        @Override public IntBox bounds() { return delegate.bounds(); }
        @Override public Coverage classify(IntBox box) { classifications++; return delegate.classify(box); }
        @Override public boolean contains(int x, int y, int z) { return delegate.contains(x, y, z); }
        @Override public void emitRanges(IntBox clip, RangeSink sink) { delegate.emitRanges(clip, sink); }
    }
}
