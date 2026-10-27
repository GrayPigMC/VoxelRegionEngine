/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion;

import java.math.BigInteger;
import java.util.NoSuchElementException;
import java.util.Random;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.RadialWavefront;
import org.voxelregion.geometry.SphereShell;

/** Independent voxel oracle and progression tests; run with java -ea. */
public final class WavefrontTest {
    private static final IntBox CLIP = new IntBox(-4, -4, -4, 5, 5, 5);
    private static long checkedVoxels;

    private WavefrontTest() { }

    public static void main(String[] args) {
        if (!WavefrontTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        validation();
        exhaustiveShells();
        extremes();
        progression();
        System.out.println("WavefrontTest passed; independent oracle voxels=" + checkedVoxels);
    }

    private static void validation() {
        Point3 center = new Point3(1, 1, 1);
        reject(IllegalArgumentException.class, () -> new SphereShell(center, -2, 1));
        reject(IllegalArgumentException.class, () -> new SphereShell(center, 2, 1));
        reject(IllegalArgumentException.class, () -> new SphereShell(center, -1, -1));
        reject(IllegalArgumentException.class, () -> new SphereShell(center, -1, 120_000_001));
        reject(IllegalArgumentException.class, () -> new SphereShell(center, 0, 1, 3, 2));
        reject(IllegalArgumentException.class, () -> new RadialWavefront(center, 2, 0, -64, 320));
        reject(IllegalArgumentException.class, () -> new RadialWavefront(center, -1, 1, -64, 320));
        reject(IllegalArgumentException.class, () -> new RadialWavefront(center, 1, 120_000_001, -64, 320));
        reject(IllegalArgumentException.class, () -> new RadialWavefront(center, 1, 1, -30_000_001, 320));
        SphereShell zero = new SphereShell(center, -1, 0);
        assert zero.contains(0, 0, 0);
        assert !zero.contains(-1, 0, 0);
        assert new SphereShell(center, 0, 0).bounds().isEmpty();
        assert new SphereShell(new Point3(0, 0, 0), -1, 0).bounds().isEmpty();
        SphereShell shell = new SphereShell(center, 10, 12);
        assert !shell.contains(3, 4, 0) : "Inner boundary is excluded";
        assert shell.contains(0, 6, 0) : "Outer boundary is included";
        assert !shell.contains(1, 6, 0);
        assert !new SphereShell(center, -1, 12, -4, 0).contains(0, 0, 0);
        assert shell.classify(new IntBox(0, 0, 0, 0, 1, 1)) == Coverage.OUTSIDE;
        // A large already completed interior must be rejected by one coarse
        // classification, without walking any of its voxel columns.
        SphereShell huge = new SphereShell(center, 119_999_999, 120_000_000);
        IntBox interior = new IntBox(-1_000_000, -1_000_000, -1_000_000,
                1_000_000, 1_000_000, 1_000_000);
        assert huge.classify(interior) == Coverage.OUTSIDE;
        huge.emitRanges(interior, (x, z, lo, hi) -> { throw new AssertionError("Completed interior emitted"); });
    }

    private static void exhaustiveShells() {
        int[][] heights = {{-4, 5}, {-1, 2}, {1, 1}};
        for (long cx = -2; cx <= 2; cx++) {
            for (long cy = -1; cy <= 1; cy++) {
                for (long cz = -2; cz <= 2; cz++) {
                    for (long outer = 0; outer <= 8; outer++) {
                        for (long inner = -1; inner <= outer; inner++) {
                            for (int[] height : heights) {
                                SphereShell shell = new SphereShell(new Point3(cx, cy, cz), inner, outer,
                                        height[0], height[1]);
                                verify(shell, CLIP);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void verify(SphereShell shell, IntBox clip) {
        int sx = clip.maxX() - clip.minX(), sy = clip.maxY() - clip.minY(), sz = clip.maxZ() - clip.minZ();
        boolean[] emitted = new boolean[sx * sy * sz];
        shell.emitRanges(clip, (x, z, lo, hi) -> {
            assert lo < hi;
            assert clip.contains(x, lo, z) && clip.contains(x, hi - 1, z);
            for (int y = lo; y < hi; y++) {
                int index = index(clip, x, y, z);
                assert !emitted[index] : "Duplicate emitted voxel";
                emitted[index] = true;
            }
        });
        int count = 0;
        for (int x = clip.minX(); x < clip.maxX(); x++) {
            for (int y = clip.minY(); y < clip.maxY(); y++) {
                for (int z = clip.minZ(); z < clip.maxZ(); z++) {
                    boolean selected = oracle(shell, x, y, z);
                    assert shell.contains(x, y, z) == selected;
                    assert emitted[index(clip, x, y, z)] == selected : "Shell ranges disagree with oracle";
                    assert !selected || shell.bounds().contains(x, y, z);
                    if (selected) count++;
                    checkedVoxels++;
                }
            }
        }
        assertSoundClassification(shell.classify(clip), count, emitted.length);
        for (int x = clip.minX(); x + 2 <= clip.maxX(); x += 3) {
            for (int y = clip.minY(); y + 2 <= clip.maxY(); y += 3) {
                for (int z = clip.minZ(); z + 2 <= clip.maxZ(); z += 3) {
                    int selected = 0;
                    for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
                        if (oracle(shell, x + dx, y + dy, z + dz)) selected++;
                        checkedVoxels++;
                    }
                    assertSoundClassification(shell.classify(new IntBox(x, y, z, x + 2, y + 2, z + 2)), selected, 8);
                }
            }
        }
    }

    private static void assertSoundClassification(Coverage coverage, int count, int size) {
        if (coverage == Coverage.OUTSIDE) assert count == 0 : "False OUTSIDE proof";
        if (coverage == Coverage.INSIDE) assert count == size && size > 0 : "False INSIDE proof";
        if (count == size && size > 0) assert coverage == Coverage.INSIDE;
    }

    private static boolean oracle(SphereShell shell, int x, int y, int z) {
        if (y < shell.minY() || y >= shell.maxY()) return false;
        long dx = 2L * x + 1 - shell.center().x2();
        long dy = 2L * y + 1 - shell.center().y2();
        long dz = 2L * z + 1 - shell.center().z2();
        long squared = dx * dx + dy * dy + dz * dz;
        return squared <= shell.outerInclusiveRadius2() * shell.outerInclusiveRadius2()
                && (shell.innerExclusiveRadius2() < 0 || squared > shell.innerExclusiveRadius2() * shell.innerExclusiveRadius2());
    }

    private static int index(IntBox box, int x, int y, int z) {
        int sx = box.maxX() - box.minX(), sz = box.maxZ() - box.minZ();
        return x - box.minX() + sx * (z - box.minZ()) + sx * sz * (y - box.minY());
    }

    private static void extremes() {
        Random random = new Random(0x5AE11_2026L);
        for (int i = 0; i < 200; i++) {
            Point3 center = new Point3(random.nextLong(120_000_001) - 60_000_000,
                    random.nextLong(120_000_001) - 60_000_000, random.nextLong(120_000_001) - 60_000_000);
            long outer = random.nextLong(120_000_001);
            long inner = random.nextInt(4) == 0 ? -1 : Math.max(0, outer - random.nextInt(8));
            int bx = random.nextBoolean() ? -30_000_000 : 29_999_998;
            int by = random.nextBoolean() ? -30_000_000 : 29_999_998;
            int bz = random.nextBoolean() ? -30_000_000 : 29_999_998;
            SphereShell shell = new SphereShell(center, inner, outer, by, by + 2);
            verify(shell, new IntBox(bx, by, bz, bx + 2, by + 2, bz + 2));
            for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
                int x = bx + dx, y = by + dy, z = bz + dz;
                BigInteger xx = BigInteger.valueOf(2L * x + 1 - center.x2());
                BigInteger yy = BigInteger.valueOf(2L * y + 1 - center.y2());
                BigInteger zz = BigInteger.valueOf(2L * z + 1 - center.z2());
                BigInteger distance = xx.pow(2).add(yy.pow(2)).add(zz.pow(2));
                boolean expected = distance.compareTo(BigInteger.valueOf(outer).pow(2)) <= 0
                        && (inner < 0 || distance.compareTo(BigInteger.valueOf(inner).pow(2)) > 0);
                assert shell.contains(x, y, z) == expected;
                checkedVoxels++;
            }
        }
        // The inner sphere must use an exact sqrt too: n^2 - 1 rounds up
        // in double arithmetic at this scale, but its excluded Y extent is smaller.
        SphereShell corrected = new SphereShell(new Point3(0, 60_000_000, 1), 119_999_999, 120_000_000);
        int[] counts = {0};
        corrected.emitRanges(new IntBox(0, -30_000_000, 0, 1, 30_000_000, 1), (x, z, lo, hi) -> {
            counts[0]++;
            assert lo == -30_000_000 && hi == -29_999_999;
        });
        assert counts[0] == 1;
    }

    private static void progression() {
        IntBox work = new IntBox(-6, -6, -6, 7, 7, 7);
        for (long cx : new long[] {-3, 0, 1}) {
            for (long cy : new long[] {-3, 0, 1}) {
                for (long cz : new long[] {-3, 0, 1}) {
                    for (long finalRadius : new long[] {0, 1, 3, 8, 9}) {
                        for (long step : new long[] {1, 2, 4, 12}) {
                            Point3 center = new Point3(cx, cy, cz);
                            RadialWavefront wave = new RadialWavefront(center, finalRadius, step, -2, 3);
                            boolean[] visited = new boolean[13 * 13 * 13];
                            assert wave.completedRadius2() == -1 && wave.completedShellCount() == 0;
                            long previous = -1;
                            long shellCount = 0;
                            SphereShell stale = null;
                            while (wave.hasNext()) {
                                SphereShell shell = wave.currentShell();
                                assert shell == wave.currentShell();
                                assert shell.innerExclusiveRadius2() == previous;
                                assert shell.outerInclusiveRadius2() > previous;
                                assert shell.outerInclusiveRadius2() <= finalRadius;
                                wave.pause();
                                for (int tick = 0; tick < 4; tick++) {
                                    assert wave.isPaused() && wave.hasNext();
                                    assert wave.currentShell() == shell;
                                    assert wave.completedRadius2() == previous;
                                }
                                reject(IllegalStateException.class, () -> wave.completeCurrent(shell));
                                wave.resume();
                                assert !wave.isPaused();
                                if (stale != null) {
                                    SphereShell rejected = stale;
                                    reject(IllegalStateException.class, () -> wave.completeCurrent(rejected));
                                }
                                SphereShell foreign = new SphereShell(center, shell.innerExclusiveRadius2(),
                                        shell.outerInclusiveRadius2(), -2, 3);
                                reject(IllegalStateException.class, () -> wave.completeCurrent(foreign));
                                shell.emitRanges(work, (x, z, lo, hi) -> {
                                    for (int y = lo; y < hi; y++) {
                                        int index = index(work, x, y, z);
                                        assert !visited[index] : "Adjacent shells overlap";
                                        visited[index] = true;
                                    }
                                });
                                wave.completeCurrent(shell);
                                shellCount++;
                                assert wave.completedShellCount() == shellCount;
                                previous = shell.outerInclusiveRadius2();
                                stale = shell;
                            }
                            assert wave.isComplete() && wave.completedRadius2() == finalRadius;
                            assert shellCount == (finalRadius == 0 ? 1 : (finalRadius + step - 1) / step);
                            reject(NoSuchElementException.class, wave::currentShell);
                            SphereShell last = stale;
                            reject(IllegalStateException.class, () -> wave.completeCurrent(last));
                            SphereShell finalBall = new SphereShell(center, -1, finalRadius, -2, 3);
                            for (int x = -6; x < 7; x++) for (int y = -6; y < 7; y++) for (int z = -6; z < 7; z++) {
                                assert visited[index(work, x, y, z)] == oracle(finalBall, x, y, z) : "Wave missed final-ball voxel";
                                checkedVoxels++;
                            }
                        }
                    }
                }
            }
        }
        RadialWavefront many = new RadialWavefront(new Point3(1, 1, 1), 120_000_000, 1, -64, 320);
        SphereShell first = many.currentShell();
        assert first.outerInclusiveRadius2() == 1 && many.completedShellCount() == 0;
        for (int i = 0; i < 100; i++) assert many.currentShell() == first;
        RadialWavefront emptyHeight = new RadialWavefront(new Point3(1, 1, 1), 0, 1, 0, 0);
        assert emptyHeight.currentShell().bounds().isEmpty();
        emptyHeight.completeCurrent(emptyHeight.currentShell());
        assert emptyHeight.isComplete();
        restore();
    }

    private static void restore() {
        Point3 center = new Point3(-3, 1, 0);
        for (long finalRadius : new long[] {0, 1, 17, 120_000_000}) {
            for (long step : new long[] {1, 4, 120_000_000}) {
                long total = finalRadius == 0 ? 1 : 1 + (finalRadius - 1) / step;
                for (long completed : new long[] {0, total / 2, total}) {
                    long radius = completed == 0 ? -1 : Math.min(finalRadius, completed * step);
                    RadialWavefront restored = RadialWavefront.restore(center, finalRadius, step,
                            -64, 320, completed, radius, true);
                    assert restored.completedShellCount() == completed;
                    assert restored.completedRadius2() == radius && restored.isPaused();
                    assert restored.isComplete() == (completed == total);
                    if (!restored.isComplete()) {
                        assert restored.currentShell().innerExclusiveRadius2() == radius;
                        assert restored.currentShell().outerInclusiveRadius2() == Math.min(finalRadius, Math.max(0, radius) + step);
                        restored.resume();
                        restored.completeCurrent(restored.currentShell());
                        assert restored.completedShellCount() == completed + 1;
                    }
                }
            }
        }
        reject(IllegalArgumentException.class, () -> RadialWavefront.restore(center, 5, 2, -64, 320, 1, 3, false));
        reject(IllegalArgumentException.class, () -> RadialWavefront.restore(center, 5, 2, -64, 320, 0, 0, false));
        reject(IllegalArgumentException.class, () -> RadialWavefront.restore(center, 5, 2, -64, 320, 4, 5, false));
        reject(IllegalArgumentException.class, () -> RadialWavefront.restore(center, 5, 2, -64, 320, -1, -1, false));
        reject(IllegalArgumentException.class, () -> RadialWavefront.restore(center, 120_000_000, 120_000_000,
                -64, 320, Long.MAX_VALUE, 120_000_000, false));
    }

    private static void reject(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            assert type.isInstance(failure) : failure;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
