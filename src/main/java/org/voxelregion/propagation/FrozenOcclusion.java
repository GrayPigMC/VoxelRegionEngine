/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.propagation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.spatial.SectionKey;

/**
 * Immutable, bounded AXIS_6 obstacle snapshot. Shadows include the blocker and
 * extend away from the blast center with a fixed transverse cross section.
 * Ties choose X, then Y, then Z. If the center is inside any blocker, the whole
 * explosion bounds are shadowed (conservative fail-closed behavior).
 *
 * The host must freeze all relevant obstacles before writes begin. This class
 * does not discover obstacles, read worlds, or claim AXIS_26/raycast support.
 * A bounding-volume tree intersects shadows once per section and emits ranges;
 * it never tests each voxel against every shadow.
 */
public final class FrozenOcclusion {
    public enum Mode { NONE, AXIS_6 }

    private static final FrozenOcclusion NONE = new FrozenOcclusion(Mode.NONE, null, 0, 0);
    private final Mode mode;
    private final Node root;
    private final int sourceBlockers;
    private final int shadowBoxes;

    private FrozenOcclusion(Mode mode, Node root, int sourceBlockers, int shadowBoxes) {
        this.mode = mode;
        this.root = root;
        this.sourceBlockers = sourceBlockers;
        this.shadowBoxes = shadowBoxes;
    }

    public static FrozenOcclusion none() { return NONE; }

    public static FrozenOcclusion axis6(Point3 center, IntBox explosionBounds,
                                        List<IntBox> blockers, int maxBlockers) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(explosionBounds, "explosionBounds");
        Objects.requireNonNull(blockers, "blockers");
        if (maxBlockers < 1) throw new IllegalArgumentException("maxBlockers must be positive");
        if (blockers.size() > maxBlockers) throw new IllegalArgumentException("Obstacle snapshot exceeds its budget");
        List<IntBox> frozen = List.copyOf(blockers);
        if (frozen.size() > maxBlockers) throw new IllegalArgumentException("Obstacle snapshot exceeds its budget");
        if (explosionBounds.isEmpty()) return new FrozenOcclusion(Mode.AXIS_6, null, frozen.size(), 0);
        ArrayList<IntBox> shadows = new ArrayList<>(frozen.size());
        for (IntBox blocker : frozen) {
            if (blocker.isEmpty() || !blocker.intersects(explosionBounds)) continue;
            if (containsCenter(blocker, center)) {
                return new FrozenOcclusion(Mode.AXIS_6, new Node(explosionBounds, null, null), frozen.size(), 1);
            }
            long dx = (long) blocker.minX() + blocker.maxX() - center.x2();
            long dy = (long) blocker.minY() + blocker.maxY() - center.y2();
            long dz = (long) blocker.minZ() + blocker.maxZ() - center.z2();
            long ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
            IntBox shadow;
            if (ax >= ay && ax >= az) {
                shadow = new IntBox(dx >= 0 ? blocker.minX() : explosionBounds.minX(),
                        blocker.minY(), blocker.minZ(),
                        dx >= 0 ? explosionBounds.maxX() : blocker.maxX(), blocker.maxY(), blocker.maxZ());
            } else if (ay >= az) {
                shadow = new IntBox(blocker.minX(), dy >= 0 ? blocker.minY() : explosionBounds.minY(),
                        blocker.minZ(), blocker.maxX(),
                        dy >= 0 ? explosionBounds.maxY() : blocker.maxY(), blocker.maxZ());
            } else {
                shadow = new IntBox(blocker.minX(), blocker.minY(),
                        dz >= 0 ? blocker.minZ() : explosionBounds.minZ(),
                        blocker.maxX(), blocker.maxY(), dz >= 0 ? explosionBounds.maxZ() : blocker.maxZ());
            }
            IntBox clipped = shadow.intersection(explosionBounds);
            if (!clipped.isEmpty()) shadows.add(clipped);
        }
        return new FrozenOcclusion(Mode.AXIS_6, build(shadows, 0, shadows.size()), frozen.size(), shadows.size());
    }

    public Mode mode() { return mode; }
    public int sourceBlockerCount() { return sourceBlockers; }
    public int shadowBoxCount() { return shadowBoxes; }
    public long estimatedBytes() { return 64L + 512L * shadowBoxes; }

    /** Conservative classification: multiple overlapping shadows may return PARTIAL. */
    public Coverage classify(IntBox box) {
        Objects.requireNonNull(box, "box");
        if (box.isEmpty()) return Coverage.OUTSIDE;
        return classify(root, box);
    }

    public VoxelMask shadowMask(SectionKey section) {
        Objects.requireNonNull(section, "section");
        IntBox bounds = section.bounds();
        Coverage coverage = classify(bounds);
        if (coverage == Coverage.OUTSIDE) return VoxelMask.EMPTY;
        if (coverage == Coverage.INSIDE) return VoxelMask.ALL;
        VoxelMask.Builder result = VoxelMask.builder();
        emit(root, bounds, result);
        return result.build();
    }

    private static boolean containsCenter(IntBox box, Point3 point) {
        return 2L * box.minX() <= point.x2() && point.x2() < 2L * box.maxX()
                && 2L * box.minY() <= point.y2() && point.y2() < 2L * box.maxY()
                && 2L * box.minZ() <= point.z2() && point.z2() < 2L * box.maxZ();
    }

    private static Coverage classify(Node node, IntBox box) {
        if (node == null || !node.bounds.intersects(box)) return Coverage.OUTSIDE;
        if (node.left == null) return node.bounds.contains(box) ? Coverage.INSIDE : Coverage.PARTIAL;
        Coverage left = classify(node.left, box);
        if (left == Coverage.INSIDE) return left;
        Coverage right = classify(node.right, box);
        if (right == Coverage.INSIDE) return right;
        return left == Coverage.OUTSIDE && right == Coverage.OUTSIDE ? Coverage.OUTSIDE : Coverage.PARTIAL;
    }

    private static void emit(Node node, IntBox section, VoxelMask.Builder mask) {
        if (node == null || !node.bounds.intersects(section)) return;
        if (node.left != null) {
            emit(node.left, section, mask);
            emit(node.right, section, mask);
            return;
        }
        IntBox clipped = node.bounds.intersection(section);
        int minY = clipped.minY() - section.minY(), maxY = clipped.maxY() - section.minY();
        for (int x = clipped.minX(); x < clipped.maxX(); x++) {
            for (int z = clipped.minZ(); z < clipped.maxZ(); z++) {
                mask.setRange(x - section.minX(), z - section.minZ(), minY, maxY);
            }
        }
    }

    private static Node build(ArrayList<IntBox> boxes, int from, int to) {
        if (from == to) return null;
        IntBox bounds = boxes.get(from);
        for (int i = from + 1; i < to; i++) bounds = unionBounds(bounds, boxes.get(i));
        if (to - from == 1) return new Node(bounds, null, null);
        long dx = (long) bounds.maxX() - bounds.minX();
        long dy = (long) bounds.maxY() - bounds.minY();
        long dz = (long) bounds.maxZ() - bounds.minZ();
        int axis = dx >= dy && dx >= dz ? 0 : dy >= dz ? 1 : 2;
        Comparator<IntBox> order = Comparator.comparingLong(box -> centerSum(box, axis));
        boxes.subList(from, to).sort(order);
        int middle = (from + to) >>> 1;
        return new Node(bounds, build(boxes, from, middle), build(boxes, middle, to));
    }

    private static long centerSum(IntBox box, int axis) {
        return switch (axis) {
            case 0 -> (long) box.minX() + box.maxX();
            case 1 -> (long) box.minY() + box.maxY();
            default -> (long) box.minZ() + box.maxZ();
        };
    }

    private static IntBox unionBounds(IntBox a, IntBox b) {
        return new IntBox(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }

    private record Node(IntBox bounds, Node left, Node right) { }
}
