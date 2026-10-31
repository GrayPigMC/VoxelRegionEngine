/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.voxelregion.lighting.FreshLightBatchPlanner;
import org.voxelregion.lighting.FreshLightBatchPlanner.Group;
import org.voxelregion.lighting.GeneralChunkLighting;
import org.voxelregion.lighting.LightBatchPlanner.Rect;

/** Independent set-based checks of publication and sampled-terrain preservation. */
public final class FreshLightBatchPlannerTest {
    private static long checks;

    public static void main(String[] args) {
        emptyAndOwnership(); adjacentAndHoles(); unsafeOrUnprofitableGaps(); budgetsAndLimits();
        determinismAndRandom(); signedExtremes(); boundedLocality(); invalidInputs();
        System.out.println("FreshLightBatchPlannerTest passed; checks=" + checks);
    }

    private static void emptyAndOwnership() {
        check(plan(List.of(), 4, 4).isEmpty(), "Empty input");
        List<Rect> input = new ArrayList<>(List.of(new Rect(-3, -7, 1, 1), new Rect(-2, -7, 1, 1)));
        List<Rect> before = List.copyOf(input);
        List<Group> result = verify(input, 4, 4, 384, Long.MAX_VALUE);
        check(input.equals(before), "Input not mutated");
        input.clear();
        check(result.size() == 1 && result.get(0).outputs().size() == 2, "Detached input ownership");
        unsupported(() -> result.clear());
        unsupported(() -> result.get(0).outputs().clear());
        List<Rect> mutable = new ArrayList<>(before);
        Group detached = new Group(new Rect(-3, -7, 2, 1), mutable);
        mutable.clear();
        check(detached.outputs().equals(before), "Public Group copies output list");
    }

    private static void adjacentAndHoles() {
        var pair = verify(List.of(new Rect(0, 0, 1, 1), new Rect(1, 0, 1, 1)), 4, 4, 384, Long.MAX_VALUE);
        check(pair.size() == 1 && pair.get(0).field().equals(new Rect(0, 0, 2, 1)), "Adjacent fields merge");
        List<Rect> ring = List.of(new Rect(-4, -7, 3, 1), new Rect(-4, -6, 1, 1),
                new Rect(-2, -6, 1, 1), new Rect(-4, -5, 3, 1));
        var result = verify(ring, 4, 4, 384, Long.MAX_VALUE);
        check(result.size() == 1 && result.get(0).field().equals(new Rect(-4, -7, 3, 3)),
                "All ring sides share a solve without pairwise halo prerequisites");
        check(result.get(0).outputs().equals(ring), "Original ring rectangles retained unchanged");
        check(!outputCells(result.get(0).outputs()).contains(key(-3, -6)), "Hole never published");
        var gap = verify(List.of(new Rect(0, 0, 1, 1), new Rect(2, 0, 1, 1)), 4, 4, 384, Long.MAX_VALUE);
        check(gap.size() == 1 && gap.get(0).field().width() == 3, "Covered one-column hole can merge");
        check(outputCells(gap.get(0).outputs()).size() == 2, "Bounding hole excluded from outputs");
        var filled = verify(units(-51, -50, 4, 4), 4, 4, 384, Long.MAX_VALUE);
        check(filled.size() == 1 && filled.get(0).field().equals(new Rect(-51, -50, 4, 4)),
                "Fresh rectangular job group uses its exact surrounding halo");
    }

    private static void unsafeOrUnprofitableGaps() {
        var diagonal = verify(List.of(new Rect(0, 0, 1, 1), new Rect(1, 1, 1, 1)), 4, 4, 384, Long.MAX_VALUE);
        check(diagonal.size() == 2, "Diagonal bounding halo introduces absent corner terrain");
        var equalArea = verify(List.of(new Rect(0, 0, 1, 1), new Rect(3, 0, 1, 1)), 4, 4, 384, Long.MAX_VALUE);
        check(equalArea.size() == 2, "Equal sampling area is not an optimization");
        var absentStrip = verify(List.of(new Rect(0, 0, 1, 1), new Rect(4, 0, 1, 1)), 8, 8, 384, Long.MAX_VALUE);
        check(absentStrip.size() == 2, "Missing sample strip cannot be invented");
        List<Rect> irregular = List.of(new Rect(0, 0, 3, 1), new Rect(0, 1, 1, 2));
        check(verify(irregular, 4, 4, 384, Long.MAX_VALUE).size() == 2,
                "L-shaped source halos do not cover bounding far corner");
    }

    private static void budgetsAndLimits() {
        var input = units(0, 0, 2, 1);
        long required = new Rect(0, 0, 2, 1).estimatedWorkingBytes(384);
        check(verify(input, 4, 4, 384, required - 1).size() == 2, "Merge over budget refused");
        check(verify(input, 4, 4, 384, required).size() == 1, "Exact budget admitted");
        check(verify(input, 4, 4, 384, 1).size() == 2, "Original jobs preserved below their own budget");
        check(verify(input, 1, 4, 384, Long.MAX_VALUE).size() == 2, "Width bound enforced");
        check(verify(units(0, 0, 1, 2), 4, 1, 384, Long.MAX_VALUE).size() == 2, "Depth bound enforced");
        var tall = verify(units(0, 0, 4, 1), 4, 4, 4096, Long.MAX_VALUE);
        check(tall.size() > 1, "Kernel cell limit enforced even with unlimited byte budget");
        var wide = verify(units(0, 0, 14, 1), 14, 14, 16, Long.MAX_VALUE);
        check(wide.size() == 1 && wide.get(0).field().width() == 14, "Largest legal horizontal field");
    }

    private static void determinismAndRandom() {
        Random random = new Random(0x46524553484cL);
        for (int round = 0; round < 30; round++) {
            List<Rect> input = new ArrayList<>();
            for (int z = -4; z < 4; z++) for (int x = -4; x < 4; x++)
                if (random.nextInt(3) != 0) input.add(new Rect(x, z, 1, 1));
            int width = 2 + random.nextInt(3), depth = 2 + random.nextInt(3);
            var expected = verify(input, width, depth, 384, Long.MAX_VALUE);
            for (int shuffle = 0; shuffle < 3; shuffle++) {
                Collections.shuffle(input, random);
                check(plan(input, width, depth).equals(expected), "Order-independent deterministic grouping");
            }
        }
    }

    private static void signedExtremes() {
        int low = Integer.MIN_VALUE + 1, high = Integer.MAX_VALUE - 1;
        List<Rect> inputs = new ArrayList<>(units(low, low, 3, 3));
        inputs.addAll(units(high - 2, high - 2, 3, 3));
        inputs.add(new Rect(low, high, 1, 1));
        inputs.add(new Rect(high, low, 1, 1));
        var result = verify(inputs, 4, 4, 384, Long.MAX_VALUE);
        check(result.size() == 4, "Both extreme squares merge without wrapping into distant columns");
        check(result.stream().anyMatch(group -> group.field().equals(new Rect(low, low, 3, 3))),
                "Minimum signed sampling halo retained");
        check(result.stream().anyMatch(group -> group.field().equals(new Rect(high - 2, high - 2, 3, 3))),
                "Maximum signed sampling halo retained");
    }

    private static void boundedLocality() {
        List<Rect> isolated = new ArrayList<>();
        for (int index = 0; index < 3000; index++) isolated.add(new Rect(index * 20 - 30000, index % 17 * 20, 1, 1));
        check(verify(isolated, 4, 4, 384, Long.MAX_VALUE).size() == isolated.size(),
                "Thousands of remote inputs stay local singletons");
        var dense = verify(units(-32, -32, 64, 64), 4, 4, 384, Long.MAX_VALUE);
        check(dense.size() == 256, "Thousands of dense inputs split at local size bounds");
    }

    private static void invalidInputs() {
        rejects(() -> plan(List.of(new Rect(0, 0, 1, 1), new Rect(0, 0, 1, 1)), 4, 4));
        rejects(() -> plan(List.of(new Rect(0, 0, 2, 2), new Rect(1, 1, 1, 1)), 4, 4));
        rejects(() -> plan(List.of(new Rect(0, 0, 5, 1)), 4, 4));
        rejects(() -> plan(List.of(), 0, 4));
        rejects(() -> plan(List.of(), 4, 15));
        rejects(() -> FreshLightBatchPlanner.plan(List.of(), 4, 4, 17, Long.MAX_VALUE));
        rejects(() -> FreshLightBatchPlanner.plan(List.of(), 4, 4, 4112, Long.MAX_VALUE));
        rejects(() -> FreshLightBatchPlanner.plan(List.of(), 4, 4, 384, 0));
        try { plan(null, 4, 4); throw new AssertionError("Null collection accepted"); }
        catch (NullPointerException expected) { checks++; }
        List<Rect> withNull = new ArrayList<>();
        withNull.add(null);
        try { plan(withNull, 4, 4); throw new AssertionError("Null rectangle accepted"); }
        catch (NullPointerException expected) { checks++; }
    }

    private static List<Group> verify(List<Rect> input, int width, int depth, int height, long budget) {
        var result = FreshLightBatchPlanner.plan(input, width, depth, height, budget);
        Set<Rect> remaining = new HashSet<>(input);
        check(remaining.size() == input.size(), "Test input unique");
        Set<Long> published = new HashSet<>();
        for (Group group : result) {
            Rect field = group.field();
            check(field.width() <= width && field.depth() <= depth, "Field fits side limits");
            check(!group.outputs().isEmpty(), "Nonempty publication list");
            Set<Long> originalHalo = new HashSet<>();
            long originalArea = 0;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            long maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
            for (Rect output : group.outputs()) {
                check(remaining.remove(output), "Original rectangle published once and unchanged");
                for (long cell : outputCells(List.of(output))) check(published.add(cell), "Disjoint publication");
                originalHalo.addAll(sampleCells(output));
                originalArea += (long)output.sampleWidth() * output.sampleDepth();
                minX = Math.min(minX, output.x()); minZ = Math.min(minZ, output.z());
                maxX = Math.max(maxX, (long)output.x() + output.width());
                maxZ = Math.max(maxZ, (long)output.z() + output.depth());
            }
            check(field.equals(new Rect(minX, minZ, (int)(maxX - minX), (int)(maxZ - minZ))), "Exact numerical bounding box");
            check(sampleCells(field).equals(originalHalo), "Every sampled column was already required, none added");
            if (group.outputs().size() > 1) {
                check((long)field.sampleWidth() * field.sampleDepth() < originalArea, "Strict sample area reduction");
                check(field.estimatedWorkingBytes(height) <= budget, "Merge fits byte budget");
                check((long)field.sampleWidth() * field.sampleDepth() * 256 * height <= GeneralChunkLighting.MAX_CELLS,
                        "Merge fits kernel cell limit");
            } else check(field.equals(group.outputs().get(0)), "Singleton unchanged");
        }
        check(remaining.isEmpty(), "No lost original output rectangle");
        check(published.equals(outputCells(input)), "Exact original publication domain");
        return result;
    }

    private static List<Group> plan(List<Rect> input, int width, int depth) {
        return FreshLightBatchPlanner.plan(input, width, depth, 384, Long.MAX_VALUE);
    }

    private static List<Rect> units(int x, int z, int width, int depth) {
        List<Rect> result = new ArrayList<>();
        for (int dz = 0; dz < depth; dz++) for (int dx = 0; dx < width; dx++)
            result.add(new Rect(x + dx, z + dz, 1, 1));
        return result;
    }

    private static Set<Long> outputCells(List<Rect> outputs) {
        Set<Long> result = new HashSet<>();
        for (Rect output : outputs) for (int z = 0; z < output.depth(); z++)
            for (int x = 0; x < output.width(); x++) result.add(key(output.x() + x, output.z() + z));
        return result;
    }

    private static Set<Long> sampleCells(Rect rectangle) {
        Set<Long> result = new HashSet<>();
        for (int z = 0; z < rectangle.sampleDepth(); z++) for (int x = 0; x < rectangle.sampleWidth(); x++)
            result.add(key(rectangle.sampleMinX() + x, rectangle.sampleMinZ() + z));
        return result;
    }

    private static long key(int x, int z) { return (long)x << 32 | z & 0xffffffffL; }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid input accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void unsupported(Runnable action) {
        try { action.run(); throw new AssertionError("Mutable output"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }
}
