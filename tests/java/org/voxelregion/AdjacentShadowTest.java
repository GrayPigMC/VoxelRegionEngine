/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.List;
import java.util.Random;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.propagation.FrozenOcclusion.Mode;
import org.voxelregion.spatial.SectionKey;

/** Differential proof that accepted streaming merges preserve each original shadow mode. */
public final class AdjacentShadowTest {
    public static void main(String[] args) {
        Random random = new Random(0x71adace);
        long compared = 0;
        int accepted = 0, rejected = 0;
        for (int trial = 0; trial < 90; trial++) {
            int x = random.nextInt(17) - 8, y = random.nextInt(17) - 8, z = random.nextInt(17) - 8;
            int sx = 1 + random.nextInt(4), sy = 1 + random.nextInt(4), sz = 1 + random.nextInt(4);
            IntBox a = new IntBox(x, y, z, x + sx, y + sy, z + sz);
            int axis = trial % 3;
            IntBox b = new IntBox(x + (axis == 0 ? sx : 0), y + (axis == 1 ? sy : 0), z + (axis == 2 ? sz : 0),
                    x + sx + (axis == 0 ? sx : 0), y + sy + (axis == 1 ? sy : 0), z + sz + (axis == 2 ? sz : 0));
            Point3 center = new Point3(random.nextInt(41) - 20, random.nextInt(41) - 20, random.nextInt(41) - 20);
            for (Mode mode : Mode.values()) {
                IntBox merged = FrozenOcclusion.mergeAdjacent(mode, center, a, b);
                check(java.util.Objects.equals(merged, FrozenOcclusion.mergeAdjacent(mode, center, b, a)), "merge symmetry");
                if (merged == null) { rejected++; continue; }
                accepted++;
                check(merged.contains(a) && merged.contains(b) && volume(merged) == volume(a) + volume(b), "exact union, no expanded selection");
                for (IntBox domain : List.of(new IntBox(-16, -16, -16, 16, 16, 16), new IntBox(-5, -4, -3, 7, 8, 9))) {
                    FrozenOcclusion before = field(mode, center, domain, List.of(a, b));
                    FrozenOcclusion after = field(mode, center, domain, List.of(merged));
                    for (int cx = -1; cx <= 0; cx++) for (int cy = -1; cy <= 0; cy++) for (int cz = -1; cz <= 0; cz++) {
                        SectionKey section = new SectionKey(cx, cy, cz);
                        VoxelMask first = before.shadowMask(section), second = after.shadowMask(section);
                        check(first.andNot(second).isEmpty() && second.andNot(first).isEmpty(),
                                "shadow changed: " + mode + " center=" + center + " sources=" + a + "," + b + " domain=" + domain);
                        compared += 4096;
                    }
                }
            }
        }
        Point3 origin = new Point3(0, 0, 0);
        IntBox a = new IntBox(4, 0, 0, 5, 4, 1), b = new IntBox(4, 4, 0, 5, 8, 1);
        check(FrozenOcclusion.mergeAdjacent(Mode.AXIS_6, origin, a, b) == null, "different signed principal axes cannot merge");
        for (Mode mode : Mode.values()) {
            check(FrozenOcclusion.mergeAdjacent(mode, origin, a, new IntBox(5, 1, 0, 6, 4, 1)) == null, "L-shape cannot expand to bounding box");
            check(FrozenOcclusion.mergeAdjacent(mode, origin, a, new IntBox(6, 0, 0, 7, 4, 1)) == null, "gap cannot merge");
            check(FrozenOcclusion.mergeAdjacent(mode, origin, a, a) == null, "overlap is not adjacency");
        }
        check(accepted > 200 && rejected > 20, "exercise both proven and refused quantized merges");
        System.out.println("AdjacentShadowTest passed: " + compared + " mask bits, " + accepted + " accepted, " + rejected + " rejected pairs");
    }
    private static FrozenOcclusion field(Mode mode, Point3 center, IntBox bounds, List<IntBox> boxes) {
        return switch (mode) {
            case NONE -> FrozenOcclusion.none();
            case AXIS_6 -> FrozenOcclusion.axis6(center, bounds, boxes, 2);
            case AXIS_26 -> FrozenOcclusion.axis26(center, bounds, boxes, 2);
            case RAYCAST_REFINED -> FrozenOcclusion.raycastRefined(center, bounds, boxes, 2);
        };
    }
    private static long volume(IntBox box) { return (long)(box.maxX() - box.minX()) * (box.maxY() - box.minY()) * (box.maxZ() - box.minZ()); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private AdjacentShadowTest() { }
}
