/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.Cylinder;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.RangeSink;
import org.voxelregion.geometry.Sphere;
import org.voxelregion.geometry.SphereShell;
import org.voxelregion.geometry.Volume;
import org.voxelregion.geometry.Volumes;

/** Composition tests using materialized independent small-set oracles. */
public final class BooleanGeometryTest {
    private static final IntBox CLIP = new IntBox(-4, -4, -4, 5, 5, 5);
    private static long checked;

    private BooleanGeometryTest() { }

    public static void main(String[] args) {
        if (!BooleanGeometryTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        exhaustivePairs();
        orderingAndConservativeClassifiers();
        sparseAndTall();
        System.out.println("BooleanGeometryTest passed; independent set comparisons=" + checked);
    }

    private static void exhaustivePairs() {
        List<Volume> shapes = new ArrayList<>();
        shapes.add(new BoxVolume(new IntBox(0, 0, 0, 0, 0, 0)));
        shapes.add(new BoxVolume(new IntBox(-4, -4, -4, 5, 5, 5)));
        shapes.add(new BoxVolume(new IntBox(-3, -1, -2, 1, 3, 2)));
        shapes.add(new BoxVolume(new IntBox(0, -4, 0, 4, 1, 4)));
        for (long cx : new long[] {-3, 0, 1}) {
            for (long radius : new long[] {0, 1, 4, 7}) {
                shapes.add(new Sphere(new Point3(cx, 1, -1), radius));
                shapes.add(new Cylinder(new Point3(cx, 0, -1), radius, -2, 2));
                shapes.add(new SphereShell(new Point3(cx, 1, -1), Math.max(0, radius - 1), radius, -3, 4));
            }
        }
        boolean[][] sets = new boolean[shapes.size()][];
        for (int i = 0; i < shapes.size(); i++) sets[i] = naive(shapes.get(i));
        for (int a = 0; a < shapes.size(); a++) {
            for (int b = 0; b < shapes.size(); b++) {
                Volume first = shapes.get(a), second = shapes.get(b);
                verify(Volumes.union(first, second), combine(sets[a], sets[b], 0));
                verify(Volumes.intersection(first, second), combine(sets[a], sets[b], 1));
                verify(Volumes.difference(first, second), combine(sets[a], sets[b], 2));
            }
        }
        Volume a = shapes.get(7), b = shapes.get(13), c = shapes.get(2);
        boolean[] expected = combine(combine(naive(a), naive(b), 0), naive(c), 2);
        verify(Volumes.difference(Volumes.union(a, b), c), expected);
        verify(Volumes.union(Volumes.difference(a, c), Volumes.difference(b, c)), expected);
        assert Volumes.union(a, a) == a;
        assert Volumes.intersection(a, a) == a;
        assert Volumes.difference(a, a).bounds().isEmpty();
        rejectsNull(() -> Volumes.union(null, a));
        rejectsNull(() -> Volumes.intersection(a, null));
        rejectsNull(() -> Volumes.difference(null, null));
    }

    /** Direct formulas, independent of input contains, bounds or range emission. */
    private static boolean[] naive(Volume volume) {
        boolean[] result = new boolean[729];
        for (int y = -4; y < 5; y++) for (int z = -4; z < 5; z++) for (int x = -4; x < 5; x++) {
            boolean selected;
            if (volume instanceof BoxVolume box) {
                IntBox b = box.bounds();
                selected = x >= b.minX() && x < b.maxX() && y >= b.minY() && y < b.maxY() && z >= b.minZ() && z < b.maxZ();
            } else if (volume instanceof Sphere sphere) {
                selected = squared(sphere.center(), x, y, z) <= sphere.radius2() * sphere.radius2();
            } else if (volume instanceof Cylinder cylinder) {
                long dx = 2L * x + 1 - cylinder.center().x2(), dz = 2L * z + 1 - cylinder.center().z2();
                selected = y >= cylinder.minY() && y < cylinder.maxY() && dx * dx + dz * dz <= cylinder.radius2() * cylinder.radius2();
            } else if (volume instanceof SphereShell shell) {
                long d = squared(shell.center(), x, y, z);
                selected = y >= shell.minY() && y < shell.maxY()
                        && d <= shell.outerInclusiveRadius2() * shell.outerInclusiveRadius2()
                        && (shell.innerExclusiveRadius2() < 0 || d > shell.innerExclusiveRadius2() * shell.innerExclusiveRadius2());
            } else throw new AssertionError("No oracle for input shape");
            result[index(x, y, z)] = selected;
        }
        return result;
    }

    private static long squared(Point3 center, int x, int y, int z) {
        long dx = 2L * x + 1 - center.x2(), dy = 2L * y + 1 - center.y2(), dz = 2L * z + 1 - center.z2();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean[] combine(boolean[] a, boolean[] b, int operation) {
        boolean[] result = new boolean[a.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = switch (operation) {
                case 0 -> a[i] || b[i];
                case 1 -> a[i] && b[i];
                case 2 -> a[i] && !b[i];
                default -> throw new AssertionError();
            };
        }
        return result;
    }

    private static void verify(Volume volume, boolean[] expected) {
        boolean[] emitted = new boolean[729];
        volume.emitRanges(CLIP, (x, z, lo, hi) -> {
            assert lo < hi && CLIP.contains(x, lo, z) && CLIP.contains(x, hi - 1, z);
            for (int y = lo; y < hi; y++) {
                int index = index(x, y, z);
                assert !emitted[index] : "Union/intersection/difference emitted duplicate";
                emitted[index] = true;
            }
        });
        int count = 0;
        for (int y = -4; y < 5; y++) for (int z = -4; z < 5; z++) for (int x = -4; x < 5; x++) {
            int index = index(x, y, z);
            assert volume.contains(x, y, z) == expected[index];
            assert emitted[index] == expected[index];
            if (expected[index]) count++;
            checked++;
        }
        sound(volume.classify(CLIP), count, 729);
        for (int x = -4; x <= 2; x += 3) for (int y = -4; y <= 2; y += 3) for (int z = -4; z <= 2; z += 3) {
            int selected = 0;
            for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
                if (expected[index(x + dx, y + dy, z + dz)]) selected++;
            }
            sound(volume.classify(new IntBox(x, y, z, x + 2, y + 2, z + 2)), selected, 8);
        }
    }

    private static int index(int x, int y, int z) { return x + 4 + 9 * (z + 4) + 81 * (y + 4); }

    private static void sound(Coverage coverage, int count, int size) {
        if (coverage == Coverage.OUTSIDE) assert count == 0;
        if (coverage == Coverage.INSIDE) assert count == size;
    }

    private static void orderingAndConservativeClassifiers() {
        boolean[] a = new boolean[729], b = new boolean[729];
        for (int y = -4; y < 5; y++) for (int z = -4; z < 5; z++) for (int x = -4; x < 5; x++) {
            a[index(x, y, z)] = ((x + y + z) & 1) == 0;
            b[index(x, y, z)] = Math.floorMod(y, 3) == 0;
        }
        Volume first = new UnorderedVolume(a, 23), second = new UnorderedVolume(b, 97);
        verify(Volumes.union(first, second), combine(a, b, 0));
        verify(Volumes.intersection(first, second), combine(a, b, 1));
        verify(Volumes.difference(first, second), combine(a, b, 2));
        Volume full = new BoxVolume(CLIP);
        verify(Volumes.difference(full, second), combine(naive(full), b, 2));
        verify(Volumes.intersection(full, second), b);
    }

    private static void sparseAndTall() {
        IntBox world = new IntBox(-30_000_000, -30_000_000, -30_000_000, 30_000_000, 30_000_000, 30_000_000);
        Volume first = new BoxVolume(new IntBox(-30_000_000, -30_000_000, -30_000_000,
                -29_999_999, -29_999_999, -29_999_999));
        Volume second = new BoxVolume(new IntBox(29_999_999, 29_999_999, 29_999_999,
                30_000_000, 30_000_000, 30_000_000));
        long[] count = {0};
        Volumes.union(first, second).emitRanges(world, (x, z, lo, hi) -> count[0] += hi - lo);
        assert count[0] == 2 : "Sparse union must stream its two operands without scanning the world gap";
        assert Volumes.intersection(first, second).bounds().isEmpty();
        assert Volumes.difference(first, second) == first;

        Volume tall = new BoxVolume(new IntBox(0, -30_000_000, 0, 1, 30_000_000, 1));
        Volume inner = new BoxVolume(new IntBox(0, -29_999_999, 0, 1, 29_999_999, 1));
        long[] outside = {0}, inside = {0};
        Volumes.difference(tall, inner).emitRanges(world, (x, z, lo, hi) -> outside[0] += hi - lo);
        Volumes.intersection(tall, inner).emitRanges(world, (x, z, lo, hi) -> inside[0] += hi - lo);
        assert outside[0] == 2 && inside[0] == 59_999_998;
        Volume combined = Volumes.union(Volumes.difference(tall, inner), inner);
        long[] total = {0};
        combined.emitRanges(world, (x, z, lo, hi) -> total[0] += hi - lo);
        assert total[0] == 60_000_000;
    }

    private static void rejectsNull(Runnable action) {
        try { action.run(); } catch (NullPointerException expected) { return; }
        throw new AssertionError("Expected NullPointerException");
    }

    /** Deliberately unsorted range output and a coarse PARTIAL classifier. */
    private static final class UnorderedVolume implements Volume {
        private final boolean[] selected;
        private final List<Integer> order;

        UnorderedVolume(boolean[] selected, long seed) {
            this.selected = selected.clone();
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < selected.length; i++) if (selected[i]) indices.add(i);
            Collections.shuffle(indices, new Random(seed));
            order = List.copyOf(indices);
        }

        @Override public IntBox bounds() { return CLIP; }
        @Override public Coverage classify(IntBox box) { return box.intersects(CLIP) ? Coverage.PARTIAL : Coverage.OUTSIDE; }
        @Override public boolean contains(int x, int y, int z) { return CLIP.contains(x, y, z) && selected[index(x, y, z)]; }
        @Override public void emitRanges(IntBox clip, RangeSink sink) {
            for (int i : order) {
                int x = i % 9 - 4, z = i / 9 % 9 - 4, y = i / 81 - 4;
                if (clip.contains(x, y, z)) sink.accept(x, z, y, y + 1);
            }
        }
    }
}
