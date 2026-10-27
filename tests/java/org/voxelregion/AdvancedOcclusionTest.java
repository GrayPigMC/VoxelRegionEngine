/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.propagation.MacroVoxelMask;
import org.voxelregion.spatial.SectionKey;

/** Independent extrusion sampling and BigInteger ray-breakpoint oracles. */
public final class AdvancedOcclusionTest {
    private static final IntBox DOMAIN = new IntBox(-16, -16, -16, 16, 16, 16);
    private static long comparisons;

    private AdvancedOcclusionTest() { }

    public static void main(String[] args) {
        if (!AdvancedOcclusionTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        directions();
        rays();
        macroMasks();
        validation();
        System.out.println("AdvancedOcclusionTest passed; independent comparisons=" + comparisons);
    }

    private static void directions() {
        Point3 center = new Point3(1, 1, 1);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            IntBox blocker = new IntBox(dx * 3, dy * 3, dz * 3, dx * 3 + 1, dy * 3 + 1, dz * 3 + 1);
            FrozenOcclusion frozen = FrozenOcclusion.axis26(center, DOMAIN, List.of(blocker), 1);
            assert frozen.mode() == FrozenOcclusion.Mode.AXIS_26;
            SectionKey section = new SectionKey(dx < 0 ? -1 : 0, dy < 0 ? -1 : 0, dz < 0 ? -1 : 0);
            VoxelMask actual = frozen.shadowMask(section);
            IntBox bounds = section.bounds();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int wx = bounds.minX() + x, wy = bounds.minY() + y, wz = bounds.minZ() + z;
                boolean expected = extrusionOracle(blocker, dx, dy, dz, wx, wy, wz);
                assert actual.contains(x, y, z) == expected : "26-direction extrusion disagreement direction="
                        + dx + "," + dy + "," + dz + " voxel=" + wx + "," + wy + "," + wz
                        + " expected=" + expected + " actual=" + actual.contains(x, y, z);
                comparisons++;
            }
            checkClassifications(frozen, section, actual);
        }
        Point3 offGridY = new Point3(1, 0, 1);
        FrozenOcclusion axial = FrozenOcclusion.axis26(offGridY, DOMAIN, List.of(new IntBox(10, 3, 0, 11, 4, 1)), 1);
        assert axial.shadowMask(new SectionKey(0, 0, 0)).contains(15, 3, 0);
        assert !axial.shadowMask(new SectionKey(0, 0, 0)).contains(15, 8, 0);
        FrozenOcclusion diagonal = FrozenOcclusion.axis26(offGridY, DOMAIN, List.of(new IntBox(10, 4, 0, 11, 5, 1)), 1);
        assert diagonal.shadowMask(new SectionKey(0, 0, 0)).contains(15, 9, 0);
        assert !diagonal.shadowMask(new SectionKey(0, 0, 0)).contains(15, 4, 0);
        FrozenOcclusion grazing = FrozenOcclusion.axis26(center, DOMAIN, List.of(new IntBox(3, 3, 0, 4, 4, 1)), 1);
        assert !grazing.shadowMask(new SectionKey(0, 0, 0)).contains(4, 3, 0) : "Excluded max face cannot cast a grazing shadow";
        assert grazing.shadowMask(new SectionKey(0, 0, 0)).contains(4, 4, 0);
    }

    /**
     * Independent finite sampling: all extrusion interval endpoints are integer
     * half-coordinates. Testing every endpoint and midpoint therefore decides
     * membership exactly for this small domain, without a slab-intersection algorithm.
     */
    private static boolean extrusionOracle(IntBox box, int dx, int dy, int dz, int x, int y, int z) {
        for (int twiceT = 0; twiceT <= 128; twiceT++) {
            long sx = 4L * x + 2 - (long) twiceT * dx;
            long sy = 4L * y + 2 - (long) twiceT * dy;
            long sz = 4L * z + 2 - (long) twiceT * dz;
            if (sx >= 4L * box.minX() && sx < 4L * box.maxX()
                    && sy >= 4L * box.minY() && sy < 4L * box.maxY()
                    && sz >= 4L * box.minZ() && sz < 4L * box.maxZ()) return true;
        }
        return false;
    }

    private static void rays() {
        rayFixture(new Point3(1, 1, 1), List.of(new IntBox(3, 0, 0, 4, 1, 1)), new SectionKey(0, 0, 0));
        rayFixture(new Point3(1, 1, 1), List.of(new IntBox(-4, 0, 0, -3, 2, 1)), new SectionKey(-1, 0, 0));
        rayFixture(new Point3(0, -1, 2), List.of(new IntBox(2, 1, -2, 4, 2, 1)), new SectionKey(0, 0, -1));
        rayFixture(new Point3(1, 1, 1), List.of(new IntBox(2, 1, 0, 3, 2, 1), new IntBox(1, 2, 0, 2, 3, 1)),
                new SectionKey(0, 0, 0));
        rayFixture(new Point3(0, 0, 0), List.of(new IntBox(-2, -2, -2, -1, -1, -1)), new SectionKey(-1, -1, -1));
        FrozenOcclusion cone = FrozenOcclusion.raycastRefined(new Point3(1, 1, 1), DOMAIN,
                List.of(new IntBox(3, 0, 0, 4, 1, 1)), 1);
        assert cone.shadowMask(new SectionKey(0, 0, 0)).contains(15, 1, 0) : "Perspective rays widen beyond an axial fixed section";
        FrozenOcclusion tangent = FrozenOcclusion.raycastRefined(new Point3(1, 1, 1), DOMAIN,
                List.of(new IntBox(2, 1, 0, 3, 2, 1)), 1);
        assert !tangent.shadowMask(new SectionKey(0, 0, 0)).contains(4, 4, 0) : "Max-face-only tangency is excluded";

        IntBox clipped = new IntBox(0, 0, 0, 4, 4, 4);
        FrozenOcclusion inside = FrozenOcclusion.raycastRefined(new Point3(1, 1, 1), clipped,
                List.of(new IntBox(0, 0, 0, 1, 1, 1)), 1);
        assert inside.shadowMask(new SectionKey(0, 0, 0)).cardinality() == 64;
        assert inside.classify(new IntBox(4, 0, 0, 5, 1, 1)) == Coverage.OUTSIDE;

        // A blocker outside the target domain can be between the center and its targets.
        FrozenOcclusion between = FrozenOcclusion.raycastRefined(new Point3(-15, 1, 1), new IntBox(0, 0, 0, 16, 16, 16),
                List.of(new IntBox(-4, 0, 0, -3, 1, 1)), 1);
        assert between.shadowMask(new SectionKey(0, 0, 0)).contains(0, 0, 0);

        Point3 far = new Point3(-60_000_000, -60_000_000, -60_000_000);
        IntBox extremeDomain = new IntBox(29_999_984, 29_999_984, 29_999_984, 30_000_000, 30_000_000, 30_000_000);
        List<IntBox> boxes = List.of(new IntBox(-1, -1, -1, 1, 1, 1));
        FrozenOcclusion extreme = FrozenOcclusion.raycastRefined(far, extremeDomain, boxes, 1);
        SectionKey section = new SectionKey(1_874_999, 1_874_999, 1_874_999);
        VoxelMask mask = extreme.shadowMask(section);
        for (int x : new int[] {0, 7, 15}) for (int y : new int[] {0, 7, 15}) for (int z : new int[] {0, 7, 15}) {
            assert mask.contains(x, y, z) == rayOracle(far, 29_999_984 + x, 29_999_984 + y, 29_999_984 + z, boxes.get(0));
            comparisons++;
        }
    }

    private static void rayFixture(Point3 center, List<IntBox> boxes, SectionKey section) {
        FrozenOcclusion frozen = FrozenOcclusion.raycastRefined(center, DOMAIN, boxes, boxes.size());
        assert frozen.mode() == FrozenOcclusion.Mode.RAYCAST_REFINED;
        VoxelMask actual = frozen.shadowMask(section);
        IntBox bounds = section.bounds();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = bounds.minX() + x, wy = bounds.minY() + y, wz = bounds.minZ() + z;
            boolean selected = false;
            for (IntBox blocker : boxes) if (rayOracle(center, wx, wy, wz, blocker)) { selected = true; break; }
            assert actual.contains(x, y, z) == selected : "Ray/BVH disagrees with rational breakpoint oracle";
            comparisons++;
        }
        checkClassifications(frozen, section, actual);
    }

    /** Tests every face crossing and midpoint, with BigInteger rational positions. */
    private static boolean rayOracle(Point3 center, int x, int y, int z, IntBox blocker) {
        long[] source = {center.x2(), center.y2(), center.z2()};
        long[] delta = {2L * x + 1 - source[0], 2L * y + 1 - source[1], 2L * z + 1 - source[2]};
        long[] min = {2L * blocker.minX(), 2L * blocker.minY(), 2L * blocker.minZ()};
        long[] max = {2L * blocker.maxX(), 2L * blocker.maxY(), 2L * blocker.maxZ()};
        Fraction zero = new Fraction(0, 1), one = new Fraction(1, 1);
        ArrayList<Fraction> crossings = new ArrayList<>(List.of(zero, one));
        for (int axis = 0; axis < 3; axis++) {
            if (delta[axis] == 0) continue;
            for (long plane : new long[] {min[axis], max[axis]}) {
                Fraction t = new Fraction(plane - source[axis], delta[axis]);
                if (t.compareTo(zero) >= 0 && t.compareTo(one) <= 0) crossings.add(t);
            }
        }
        Collections.sort(crossings);
        for (int i = 0; i < crossings.size(); i++) {
            if (insideAt(crossings.get(i), source, delta, min, max)) return true;
            if (i + 1 < crossings.size() && insideAt(crossings.get(i).midpoint(crossings.get(i + 1)), source, delta, min, max)) return true;
        }
        return false;
    }

    private static boolean insideAt(Fraction t, long[] source, long[] delta, long[] min, long[] max) {
        for (int axis = 0; axis < 3; axis++) {
            BigInteger position = BigInteger.valueOf(source[axis]).multiply(t.denominator)
                    .add(BigInteger.valueOf(delta[axis]).multiply(t.numerator));
            if (position.compareTo(BigInteger.valueOf(min[axis]).multiply(t.denominator)) < 0
                    || position.compareTo(BigInteger.valueOf(max[axis]).multiply(t.denominator)) >= 0) return false;
        }
        return true;
    }

    private static void checkClassifications(FrozenOcclusion frozen, SectionKey section, VoxelMask expected) {
        IntBox b = section.bounds();
        for (int x = 0; x < 16; x += 4) for (int y = 0; y < 16; y += 4) for (int z = 0; z < 16; z += 4) {
            int selected = 0;
            for (int dx = 0; dx < 4; dx++) for (int dy = 0; dy < 4; dy++) for (int dz = 0; dz < 4; dz++) {
                if (expected.contains(x + dx, y + dy, z + dz)) selected++;
            }
            Coverage coverage = frozen.classify(new IntBox(b.minX() + x, b.minY() + y, b.minZ() + z,
                    b.minX() + x + 4, b.minY() + y + 4, b.minZ() + z + 4));
            if (coverage == Coverage.OUTSIDE) assert selected == 0;
            if (coverage == Coverage.INSIDE) assert selected == 64;
        }
    }

    private static void macroMasks() {
        Random random = new Random(0x64_4_8_16);
        SectionKey section = new SectionKey(-1, -2, 3);
        for (int cellSize : new int[] {4, 8, 16}) {
            assert MacroVoxelMask.from(VoxelMask.EMPTY, cellSize).isEmpty();
            List<IntBox> full = MacroVoxelMask.from(VoxelMask.ALL, cellSize).toBoxes(section);
            assert full.equals(List.of(section.bounds())) : "A full macro grid should merge to one box";
            for (int trial = 0; trial < 50; trial++) {
                VoxelMask.Builder builder = VoxelMask.builder();
                for (int sample = 0; sample < 32; sample++) builder.set(random.nextInt(16), random.nextInt(16), random.nextInt(16));
                VoxelMask exact = builder.build();
                MacroVoxelMask macro = MacroVoxelMask.from(exact, cellSize);
                assert macro.cellSize() == cellSize;
                assert macro.cellsPerAxis() == 16 / cellSize;
                assert MacroVoxelMask.ofBits(cellSize, macro.bits()).bits() == macro.bits();
                boolean[] occupied = new boolean[macro.cellsPerAxis() * macro.cellsPerAxis() * macro.cellsPerAxis()];
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (exact.contains(x, y, z)) occupied[x / cellSize + macro.cellsPerAxis() * (z / cellSize)
                            + macro.cellsPerAxis() * macro.cellsPerAxis() * (y / cellSize)] = true;
                }
                int count = 0;
                for (boolean present : occupied) if (present) count++;
                assert macro.occupiedCells() == count;
                boolean[] emitted = new boolean[4096];
                List<IntBox> boxes = macro.toBoxes(section);
                assert boxes.size() <= 64;
                IntBox sb = section.bounds();
                for (IntBox box : boxes) {
                    assert sb.contains(box);
                    for (int x = box.minX(); x < box.maxX(); x++) for (int y = box.minY(); y < box.maxY(); y++) for (int z = box.minZ(); z < box.maxZ(); z++) {
                        int i = (x - sb.minX()) | ((z - sb.minZ()) << 4) | ((y - sb.minY()) << 8);
                        assert !emitted[i];
                        emitted[i] = true;
                    }
                }
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    boolean expected = occupied[x / cellSize + macro.cellsPerAxis() * (z / cellSize)
                            + macro.cellsPerAxis() * macro.cellsPerAxis() * (y / cellSize)];
                    assert emitted[x | (z << 4) | (y << 8)] == expected;
                    assert macro.containsCell(x / cellSize, y / cellSize, z / cellSize) == expected;
                    assert !exact.contains(x, y, z) || expected;
                    comparisons++;
                }
            }
        }
    }

    private static void validation() {
        Point3 center = new Point3(1, 1, 1);
        List<IntBox> tooMany = List.of(new IntBox(2, 0, 0, 3, 1, 1), new IntBox(3, 0, 0, 4, 1, 1));
        rejects(() -> FrozenOcclusion.axis26(center, DOMAIN, tooMany, 1));
        rejects(() -> FrozenOcclusion.raycastRefined(center, DOMAIN, tooMany, 1));
        rejects(() -> FrozenOcclusion.raycastRefined(center, DOMAIN, List.of(), 0));
        rejects(() -> MacroVoxelMask.from(VoxelMask.ALL, 2));
        rejects(() -> MacroVoxelMask.ofBits(8, 256));
        rejects(() -> MacroVoxelMask.ofBits(16, 2));
        assert FrozenOcclusion.axis26(center, DOMAIN, List.of(), 1).shadowMask(new SectionKey(0, 0, 0)).isEmpty();
        assert FrozenOcclusion.raycastRefined(center, DOMAIN, List.of(), 1).shadowMask(new SectionKey(0, 0, 0)).isEmpty();
        ArrayList<IntBox> source = new ArrayList<>(tooMany);
        FrozenOcclusion detached = FrozenOcclusion.raycastRefined(center, DOMAIN, source, 2);
        VoxelMask before = detached.shadowMask(new SectionKey(0, 0, 0));
        source.clear();
        assert detached.sourceBlockerCount() == 2 && detached.shadowMask(new SectionKey(0, 0, 0)).equals(before);
        assert detached.estimatedBytes() >= 1024;
        FrozenOcclusion blocked = FrozenOcclusion.axis26(center, DOMAIN, List.of(new IntBox(0, 0, 0, 1, 1, 1)), 1);
        assert blocked.shadowMask(new SectionKey(0, 0, 0)).isFull();
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static final class Fraction implements Comparable<Fraction> {
        final BigInteger numerator;
        final BigInteger denominator;

        Fraction(long numerator, long denominator) { this(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)); }
        Fraction(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() < 0) { numerator = numerator.negate(); denominator = denominator.negate(); }
            BigInteger gcd = numerator.gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
        }
        Fraction midpoint(Fraction other) {
            return new Fraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator).multiply(BigInteger.TWO));
        }
        @Override public int compareTo(Fraction other) { return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator)); }
    }
}
