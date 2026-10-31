/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.voxelregion.lighting.GlobalLightField;
import org.voxelregion.lighting.LightBatchPlanner;
import org.voxelregion.lighting.LightBatchPlanner.Column;
import org.voxelregion.lighting.LightBatchPlanner.Rect;

/** Set equality checks coverage independently of the row-run planner. */
public final class LightBatchPlannerTest {
    private static long checks;

    public static void main(String[] args) {
        emptyAndDuplicates(); rectangleAndLimits(); sparseAndHoles(); circleAndRandom();
        signedExtremes(); budgetEstimate(); invalidInputs();
        System.out.println("LightBatchPlannerTest passed; checks=" + checks);
    }

    private static void emptyAndDuplicates() {
        check(LightBatchPlanner.plan(List.of(), 8, 8).isEmpty(), "Empty input");
        List<Column> dirty = new ArrayList<>(List.of(new Column(-3, -2), new Column(-3, -2)));
        var result = verify(dirty, 8, 8);
        check(result.equals(List.of(new Rect(-3, -2, 1, 1))), "Duplicate columns collapse");
        dirty.clear();
        check(result.size() == 1, "Result detached from mutable input");
        try { result.add(new Rect(0, 0, 1, 1)); throw new AssertionError("Mutable result"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }

    private static void rectangleAndLimits() {
        var actualDirty = rectangle(-51, -50, 4, 4);
        var exact = verify(actualDirty, 8, 8);
        check(exact.equals(List.of(new Rect(-51, -50, 4, 4))), "No world-grid alignment padding");
        Rect sample = exact.get(0);
        check(sample.sampleMinX() == -52 && sample.sampleMinZ() == -51
                && sample.sampleWidth() == 6 && sample.sampleDepth() == 6, "Exact surrounding halo");
        var large = rectangle(-37, 11, 35, 27);
        check(verify(large, 8, 8).size() == 20, "Width and depth split a filled rectangle");
        check(verify(large, 1, 1).size() == large.size(), "Unit limits");
        check(verify(large, 1, 8).size() == 35 * 4, "Asymmetric limits");
        check(verify(large, 8, 1).size() == 5 * 27, "Transposed asymmetric limits");
        check(verify(rectangle(0, 0, 8, 17), 8, 8).equals(List.of(
                new Rect(0, 0, 8, 8), new Rect(0, 8, 8, 8), new Rect(0, 16, 8, 1))), "Depth rollover");
    }

    private static void sparseAndHoles() {
        var dirty = new ArrayList<>(rectangle(-3, -3, 7, 7));
        dirty.remove(new Column(0, 0));
        verify(dirty, 8, 8);
        dirty = new ArrayList<>();
        for (int z = -20; z <= 20; z++) for (int x = -20; x <= 20; x++)
            if (((x + z) & 1) == 0) dirty.add(new Column(x, z));
        check(verify(dirty, 8, 8).size() == dirty.size(), "Checkerboard holes stay absent");
        verify(List.of(new Column(3, -5), new Column(3, -3), new Column(3, 0)), 8, 8);
        dirty = new ArrayList<>();
        for (int z = 0; z < 10; z++) for (int x = z; x < 15; x++) dirty.add(new Column(x, z));
        verify(dirty, 4, 3);
    }

    private static void circleAndRandom() {
        List<Column> circle = new ArrayList<>();
        for (int z = -32; z <= 32; z++) for (int x = -32; x <= 32; x++)
            if (x * x + z * z <= 32 * 32) circle.add(new Column(x - 101, z + 117));
        verify(circle, 8, 8);
        Random random = new Random(0x504c414e);
        for (int round = 0; round < 100; round++) {
            List<Column> dirty = new ArrayList<>();
            int dx = random.nextInt(1000) - 500, dz = random.nextInt(1000) - 500;
            for (int z = -12; z <= 12; z++) for (int x = -12; x <= 12; x++)
                if (random.nextInt(4) != 0) dirty.add(new Column(dx + x, dz + z));
            verify(dirty, random.nextInt(8) + 1, random.nextInt(8) + 1);
        }
    }

    private static void signedExtremes() {
        int lo = Integer.MIN_VALUE + 1, hi = Integer.MAX_VALUE - 1;
        List<Column> dirty = new ArrayList<>();
        dirty.addAll(rectangle(lo, lo, 3, 3));
        dirty.addAll(rectangle(hi - 2, hi - 2, 3, 3));
        dirty.addAll(List.of(new Column(lo, hi), new Column(hi, lo)));
        var result = verify(dirty, 8, 8);
        check(result.contains(new Rect(lo, lo, 3, 3)), "Negative extreme rectangle");
        check(result.contains(new Rect(hi - 2, hi - 2, 3, 3)), "Positive extreme rectangle");
        check(new Rect(lo, lo, 1, 1).sampleMinX() == Integer.MIN_VALUE, "Representable minimum halo");
        Rect end = new Rect(hi, hi, 1, 1);
        check((long)end.sampleMinX() + end.sampleWidth() - 1 == Integer.MAX_VALUE, "Representable maximum halo");
        verify(List.of(new Column(hi, 0), new Column(lo, 0)), 8, 8);
    }

    private static void budgetEstimate() {
        int height = 384;
        long budget = 64L << 20;
        int maximum = 8;
        while (new Rect(0, 0, maximum, maximum).estimatedWorkingBytes(height) > budget) maximum--;
        check(maximum == 4, "Host can derive four-column output limit from height and budget");
        var planned = verify(rectangle(-40, -21, 23, 17), maximum, maximum);
        for (Rect rect : planned) {
            long cells = (long)rect.sampleWidth() * 16 * rect.sampleDepth() * 16 * height;
            long queueBound = 512 + 16 * ((cells + 63) / 64)
                    + Math.min(256, (cells + 255) / 256) * 1088;
            long expected = 4096L + 18L * cells + (long)rect.sampleWidth() * rect.sampleDepth() * 256L
                    + queueBound + 256L + 8L * rect.sampleWidth() * rect.sampleDepth() * 256L
                    + 24L * ((cells + 1023L) / 1024L);
            check(rect.estimatedWorkingBytes(height) == expected, "Estimator includes both halo dimensions");
            check(expected <= budget, "Each planned rectangle obeys caller-derived budget");
        }
        check(new Rect(0, 0, 1, 1).estimatedWorkingBytes(16) > 0, "Small height estimate");
        check(new Rect(0, 0, 8, 8).estimatedWorkingBytes(4096) > 256L << 20,
                "Estimator does not silently clamp oversized domains into a budget");
        Rect small = new Rect(0, 0, 1, 1);
        byte[] optics = new byte[48 * 16 * 48];
        var snapshot = new GlobalLightField.Snapshot(48, 16, 48, optics, optics, optics, new BitSet(), new BitSet());
        check(small.estimatedWorkingBytes(16) == 855904L, "Small domain includes page-object overhead");
        check(snapshot.estimatedWorkingBytes() == small.estimatedWorkingBytes(16), "Admission and planner use one bound");
        rejects(() -> new GlobalLightField.Session(snapshot, snapshot.estimatedWorkingBytes() - 1));
        new GlobalLightField.Session(snapshot, snapshot.estimatedWorkingBytes());
        check(GlobalLightField.extraIncreaseWorkingBytes(0) == 512, "Empty queue bookkeeping bound");
        check(GlobalLightField.extraIncreaseWorkingBytes(256) == 1664, "One exact page");
        check(GlobalLightField.extraIncreaseWorkingBytes(257) == 2768, "Next page and spill word boundary");
        check(new Rect(0, 0, 4, 4).estimatedWorkingBytes(384) <= budget
                && new Rect(0, 0, 5, 5).estimatedWorkingBytes(384) > budget,
                "Default complete-height output tile still fits 64 MiB only through four columns");
    }

    private static void invalidInputs() {
        rejects(() -> LightBatchPlanner.plan(null, 8, 8));
        rejects(() -> LightBatchPlanner.plan(Collections.singletonList(null), 8, 8));
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE - 1}) {
            rejects(() -> LightBatchPlanner.plan(List.of(), invalid, 8));
            rejects(() -> LightBatchPlanner.plan(List.of(), 8, invalid));
        }
        for (int edge : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            rejects(() -> LightBatchPlanner.plan(List.of(new Column(edge, 0)), 8, 8));
            rejects(() -> LightBatchPlanner.plan(List.of(new Column(0, edge)), 8, 8));
        }
        rejects(() -> new Rect(Integer.MAX_VALUE - 1, 0, 2, 1));
        rejects(() -> new Rect(0, Integer.MIN_VALUE, 1, 1));
        rejects(() -> new Rect(0, 0, 0, 1));
        rejects(() -> new Rect(0, 0, 1, -1));
        rejects(() -> new Rect(0, 0, 1, 1).estimatedWorkingBytes(0));
        rejects(() -> new Rect(0, 0, 1, 1).estimatedWorkingBytes(17));
        rejects(() -> new Rect(Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1,
                Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 2).estimatedWorkingBytes(16));
        rejects(() -> GlobalLightField.estimatedWorkingBytes(Long.MAX_VALUE, 16, 2));
        rejects(() -> GlobalLightField.estimatedWorkingBytes(Long.MAX_VALUE / 16, 16, 1));
        rejects(() -> GlobalLightField.estimatedWorkingBytes(48, 0, 48));
        rejects(() -> GlobalLightField.estimatedWorkingBytes(-1, 16, 48));
        rejects(() -> GlobalLightField.extraIncreaseWorkingBytes(-1));
    }

    private static List<Rect> verify(List<Column> dirty, int maxWidth, int maxDepth) {
        List<Column> before = new ArrayList<>(dirty);
        List<Rect> planned = LightBatchPlanner.plan(dirty, maxWidth, maxDepth);
        check(dirty.equals(before), "Input not mutated");
        Set<Column> expected = new HashSet<>(dirty), observed = new HashSet<>();
        Rect previous = null;
        for (Rect rect : planned) {
            check(rect.width() <= maxWidth && rect.depth() <= maxDepth, "Explicit dimensions respected");
            if (previous != null) check(previous.z() < rect.z() || previous.z() == rect.z() && previous.x() < rect.x(),
                    "Deterministic z/x output order");
            previous = rect;
            for (int dz = 0; dz < rect.depth(); dz++) for (int dx = 0; dx < rect.width(); dx++) {
                Column cell = new Column(Math.addExact(rect.x(), dx), Math.addExact(rect.z(), dz));
                check(expected.contains(cell), "No clean output or hole filled");
                check(observed.add(cell), "No output overlap");
            }
        }
        check(observed.equals(expected), "No dirty column lost");
        Collections.shuffle(before, new Random(0x44495254));
        check(LightBatchPlanner.plan(before, maxWidth, maxDepth).equals(planned), "Input iteration order independent");
        return planned;
    }

    private static List<Column> rectangle(int x, int z, int width, int depth) {
        List<Column> result = new ArrayList<>();
        for (int dz = 0; dz < depth; dz++) for (int dx = 0; dx < width; dx++)
            result.add(new Column(Math.addExact(x, dx), Math.addExact(z, dz)));
        return result;
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid input accepted"); }
        catch (IllegalArgumentException | NullPointerException expected) { checks++; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
