/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.List;
import org.voxelregion.lighting.GeneralChunkLighting;
import org.voxelregion.lighting.GlobalLightField;

/** Full-height common-sky fills must preserve exact direct sources and resumable work. */
public final class GlobalLightSkyTailTest {
    private static final int W = 48, H = 48, D = 48, P = W * D, N = P * H;
    private static long checks, values;

    public static void main(String[] args) throws Exception {
        extrema();
        roughColumns();
        abandonedWorkAndImmutablePredecessor();
        System.out.println("GlobalLightSkyTailTest passed; checks=" + checks + " independent light/direct values=" + values);
    }

    private static void extrema() throws Exception {
        Fixture air = new Fixture(true);
        compare(air, finish(new GlobalLightField.Session(air.snapshot()), false)); // common ceiling zero
        Fixture darkness = new Fixture(false);
        compare(darkness, finish(new GlobalLightField.Session(darkness.snapshot()), false)); // ceiling height
        Fixture roof = new Fixture(true);
        for (int col = 0; col < P; col++) roof.opacity[col + (H - 1) * P] = 15;
        compare(roof, finish(new GlobalLightField.Session(roof.snapshot()), false)); // all blocked at top

        // A single column can force the common ceiling to height; the others must still
        // receive their full vertical intervals, including intervals spanning row ends.
        Fixture oneClosed = new Fixture(true);
        oneClosed.opacity[N - 1] = 15;
        compare(oneClosed, finish(new GlobalLightField.Session(oneClosed.snapshot()), false));
        Fixture partial = new Fixture(true);
        partial.skyColumns.clear(0); partial.skyColumns.clear(47); partial.skyColumns.clear(48);
        partial.skyColumns.clear(P - 1);
        compare(partial, finish(new GlobalLightField.Session(partial.snapshot()), false));
    }

    private static void roughColumns() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Fixture rough = rough(mode);
            var snapshot = rough.snapshot();
            var completed = finish(new GlobalLightField.Session(snapshot), mode == 0);
            compare(rough, completed);
            // A different step partition must not change the exact direct bitmap or either layer.
            var other = GlobalLightField.solve(snapshot);
            equalResults(completed, other, "step partition changed a rough field");
        }
    }

    private static Fixture rough(int mode) {
        Fixture f = new Fixture(mode != 2);
        for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            int col = x + W * z;
            int low = 2 + Math.floorMod(x * 7 + z * 11, 19);
            int index = col + low * P;
            switch (col & 3) {
                case 0 -> f.opacity[index] = 15;
                case 1 -> f.stops.set(index); // no opacity: stop semantics alone
                case 2 -> f.blocked[index] = 4; // DOWN keeps this cell direct, stops the cell below
                case 3 -> f.opacity[index] = 1; // translucent attenuation still stops direct15
                default -> throw new AssertionError();
            }
            if (mode == 1 && (x % 7 == 0 || z % 13 == 0)) f.skyColumns.clear(col);
            if (x % 17 == 0 && z % 19 == 0) f.emission[col + P] = 12;
            if (x == 24 && z >= 10 && z < 36) f.blocked[col + 25 * P] |= 2;
        }
        // Explicitly cover both sides of a 16-high section boundary and non-word-aligned runs.
        f.stops.set(at(15,15,15)); f.blocked[at(16,16,15)] |= 4;
        f.opacity[at(47,31,0)] = 1;
        return f;
    }

    private static void abandonedWorkAndImmutablePredecessor() throws Exception {
        Fixture f = rough(0);
        var snapshot = f.snapshot();
        var original = GlobalLightField.solve(snapshot);
        var originalReference = reference(f);
        BitSet oldDirect = (BitSet)direct(original).clone();

        // Session has no cancel method: cancellation discards the owner job without
        // publishing result(). Pause midway through the direct stage, then abandon it.
        var abandoned = new GlobalLightField.Session(snapshot);
        int calls = 0;
        while (abandoned.step(1).stage() != GlobalLightField.Stage.DIRECT_SKY) {
            check(++calls <= N + P, "reaches direct initialization");
        }
        var paused = abandoned.step(17);
        check(!abandoned.isComplete(), "small partial sky fill remains unpublished");
        check(abandoned.step(0).totalWork() == paused.totalWork(), "zero budget preserves partial tail cursor");
        try { abandoned.result(); throw new AssertionError("partial field published"); }
        catch (IllegalStateException expected) { checks++; }
        compareReference(original, originalReference);
        check(direct(original).equals(oldDirect), "abandoned full job cannot modify predecessor direct sources");
        compare(f, finish(new GlobalLightField.Session(snapshot), false));

        // Also abandon an incremental borrower; its sky/direct scratch must be private.
        var abandonedEdit = new GlobalLightField.Session(original,
                List.of(new GlobalLightField.CellUpdate(24,40,24,15,15,4,true)), List.of());
        abandonedEdit.step(3);
        check(!abandonedEdit.isComplete(), "incremental cancellation fixture remains partial");
        compareReference(original, originalReference);
        check(direct(original).equals(oldDirect), "abandoned increment cannot mutate original direct sources");
        equalResults(original, GlobalLightField.solve(original.terrain()), "original snapshot mutated by borrower");
    }

    private static GlobalLightField.Result finish(GlobalLightField.Session job, boolean singleSteps) {
        check(job.step(0).work() == 0, "zero budget does not advance source initialization");
        int[] budgets = {1,3,47,63,65,257,4095};
        long work = 0; int calls = 0;
        while (!job.isComplete()) {
            int budget = singleSteps ? 1 : budgets[calls % budgets.length];
            var progress = job.step(budget); calls++;
            check(progress.work() >= 0 && progress.work() <= budget, "work-bounded sky fill");
            work += progress.work();
            check(progress.totalWork() == work, "reported cumulative work covers exact partition");
            check(calls < 5_000_000, "source/fill/frontier terminates with small budgets");
        }
        return job.result();
    }

    private static void compare(Fixture fixture, GlobalLightField.Result actual) throws Exception {
        compareReference(actual, reference(fixture));
        BitSet actualDirect = direct(actual);
        for (int col = 0; col < P; col++) {
            boolean open = fixture.skyColumns.get(col);
            for (int y = H - 1; y >= 0; y--) {
                int index = col + y * P;
                if (fixture.opacity[index] > 0 || fixture.stops.get(index)
                        || y + 1 < H && (fixture.blocked[index + P] & 4) != 0) open = false;
                if (actualDirect.get(index) != open)
                    throw new AssertionError("Direct source differs at column=" + col + " y=" + y);
                values++;
            }
        }
    }

    private static GeneralChunkLighting.Result reference(Fixture f) {
        return GeneralChunkLighting.solve(new GeneralChunkLighting.Snapshot(W,H,D,
                f.opacity,f.emission,f.blocked,f.stops,f.skyColumns));
    }
    private static void compareReference(GlobalLightField.Result actual, GeneralChunkLighting.Result expected) {
        for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            if (actual.block(x,y,z) != expected.block(x,y,z) || actual.sky(x,y,z) != expected.sky(x,y,z))
                throw new AssertionError("Independent light oracle differs at " + x + "," + y + "," + z);
            values += 2;
        }
    }
    private static void equalResults(GlobalLightField.Result first, GlobalLightField.Result second, String message) throws Exception {
        for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            if (first.block(x,y,z) != second.block(x,y,z) || first.sky(x,y,z) != second.sky(x,y,z))
                throw new AssertionError(message + " at " + x + "," + y + "," + z);
            values += 2;
        }
        check(direct(first).equals(direct(second)), message + " direct bitmap");
    }
    private static BitSet direct(GlobalLightField.Result value) throws Exception {
        Field field = GlobalLightField.Result.class.getDeclaredField("direct"); field.setAccessible(true);
        return (BitSet)field.get(value);
    }
    private static int at(int x,int y,int z) { return x + W * (z + D * y); }
    private static void check(boolean condition,String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static final class Fixture {
        final byte[] opacity = new byte[N], emission = new byte[N], blocked = new byte[N];
        final BitSet stops = new BitSet(N), skyColumns = new BitSet(P);
        Fixture(boolean sky) { if (sky) skyColumns.set(0,P); }
        GlobalLightField.Snapshot snapshot() { return new GlobalLightField.Snapshot(W,H,D,opacity,emission,blocked,stops,skyColumns); }
    }
}
