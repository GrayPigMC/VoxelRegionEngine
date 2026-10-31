/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.List;
import org.voxelregion.lighting.GeneralChunkLighting;
import org.voxelregion.lighting.GlobalLightField;

/** Highest-source scan bounds must omit only proven irrelevant air, including after edits. */
public final class GlobalLightSourceBoundTest {
    private static final int W = 48, H = 32, D = 48, P = W * D, N = P * H;
    private static final Field END;
    private static long checks, values;
    static {
        try { END = GlobalLightField.Snapshot.class.getDeclaredField("sourceScanEnd"); END.setAccessible(true); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static void main(String[] args) throws Exception {
        emptyAndBoundarySources();
        horizontalFacesNeedNoSourceScan();
        incrementalBoundAndNewHighSource();
        independentSkyProofChanges();
        immutableInputAndWorkSaving();
        System.out.println("GlobalLightSourceBoundTest passed; checks=" + checks + " independent light values=" + values);
    }

    private static void emptyAndBoundarySources() throws Exception {
        for (boolean sky : new boolean[] {false, true}) {
            Fixture empty = new Fixture(sky);
            var snapshot = empty.snapshot();
            check(end(snapshot) == 0, "empty optics have zero exclusive scan bound");
            var result = solve(snapshot);
            compare(empty, result);
            check(result.block(20,20,20) == 0 && result.sky(20,20,20) == (sky ? 15 : 0), "analytic empty field");
        }
        // Flat-index zero, last cell before a plane, first cell after a section boundary,
        // and the last cell in the full domain exercise inclusive/exclusive calculations.
        int[][] locations = {{0,0,0}, {47,0,47}, {0,16,0}, {47,31,47}};
        for (int[] pos : locations) for (int kind = 0; kind < 5; kind++) {
            Fixture f = new Fixture(true);
            int index = at(pos[0],pos[1],pos[2]);
            switch (kind) {
                case 0 -> f.emission[index] = 1;
                case 1 -> f.emission[index] = 15;
                case 2 -> f.opacity[index] = 1;
                case 3 -> f.stops.set(index);
                case 4 -> f.blocked[index] = 4;
                default -> throw new AssertionError();
            }
            var snapshot = f.snapshot();
            check(end(snapshot) == index + 1, "highest optical property must be included exactly");
            var result = solve(snapshot); compare(f,result);
            if (kind == 0) {
                check(result.block(pos[0],pos[1],pos[2]) == 1, "level-one source still owns a light value");
                int neighborX = pos[0] == 0 ? 1 : pos[0] - 1;
                check(result.block(neighborX,pos[1],pos[2]) == 0, "level-one source does not propagate");
            }
            if (kind == 4) check(result.sky(pos[0],pos[1],pos[2]) == 15,
                    "DOWN obstruction stops the lower cell, not its own direct sky");
        }
        Fixture darkDown = new Fixture(false);
        darkDown.blocked[at(24,31,24)] = 4;
        compare(darkDown,solve(darkDown.snapshot()));
    }

    private static void horizontalFacesNeedNoSourceScan() throws Exception {
        Fixture f = new Fixture(false);
        for (int z = 0; z < D; z++) for (int x = 0; x < 24; x++) f.direct.set(x + W * z);
        for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) f.blocked[at(23,y,z)] = 2;
        // All WEST/EAST/UP/NORTH/SOUTH bits are irrelevant to finding vertical source
        // ends but must remain available to the later diffuse frontier propagation.
        f.blocked[at(47,31,47)] = 59;
        var snapshot = f.snapshot();
        check(end(snapshot) == 0, "non-DOWN masks alone need no source scan");
        var result = solve(snapshot); compare(f,result);
        check(result.sky(23,20,24) == 15 && result.sky(24,20,24) == 0, "outgoing horizontal mask still applies");
    }

    private static void incrementalBoundAndNewHighSource() throws Exception {
        Fixture f = new Fixture(true);
        for (int index = 0; index < P; index++) f.opacity[index] = 15;
        f.emission[at(24,2,24)] = 15;
        var result = solve(f.snapshot());
        int lowEnd = end(result.terrain());
        var high = new GlobalLightField.CellUpdate(47,31,47,0,1,0,false);
        result = edit(f,result,List.of(high),List.of());
        check(end(result.terrain()) == N, "incremental source beyond old bound extends to last cell");
        check(result.block(47,31,47) == 1, "incremental level-one source is preserved");
        result = edit(f,result,List.of(new GlobalLightField.CellUpdate(47,31,47,0,0,0,false)),List.of());
        check(end(result.terrain()) >= lowEnd, "removing high source may keep a conservative upper bound");
        result = edit(f,result,List.of(new GlobalLightField.CellUpdate(24,2,24,0,0,0,false)),List.of());
        check(result.block(24,2,24) == 0, "last real emitter removal has no stale light");

        // Reopen a fresh low bound for each new optical channel, rather than relying on
        // an earlier high source's retained conservative bound to conceal a missing update.
        for (var update : List.of(new GlobalLightField.CellUpdate(30,30,30,0,0,4,false),
                new GlobalLightField.CellUpdate(30,30,30,1,0,0,false),
                new GlobalLightField.CellUpdate(30,30,30,0,0,0,true))) {
            Fixture low = new Fixture(true); low.opacity[at(20,1,20)] = 15;
            var state = solve(low.snapshot());
            state = edit(low,state,List.of(update),List.of());
            check(end(state.terrain()) >= at(30,30,30) + 1, "new high stop/opacity/DOWN expands scan bound");
            var noOp = new GlobalLightField.Session(state,List.of(update),List.of());
            check(noOp.isComplete() && noOp.result() == state, "unchanged edit retains Result identity");
            var cancel = new GlobalLightField.Session(state,List.of(
                    new GlobalLightField.CellUpdate(47,31,47,15,15,4,true),
                    new GlobalLightField.CellUpdate(47,31,47,0,0,0,false)),List.of());
            check(cancel.isComplete() && cancel.result() == state, "cancelled high edit remains a true no-op");
        }
    }

    private static void independentSkyProofChanges() throws Exception {
        Fixture f = new Fixture(false);
        var state = solve(f.snapshot());
        state = edit(f,state,List.of(),List.of(new GlobalLightField.SkyColumnUpdate(24,24,true)));
        check(end(state.terrain()) == 0, "sky proof alone does not invent an optical source bound");
        check(state.sky(24,0,24) == 15 && state.sky(25,0,24) == 14, "new sky proof illuminates entire column");
        state = edit(f,state,List.of(),List.of(new GlobalLightField.SkyColumnUpdate(24,24,false)));
        check(state.sky(24,31,24) == 0, "removing sky proof clears previous direct source");
    }

    private static void immutableInputAndWorkSaving() throws Exception {
        Fixture f = new Fixture(true); f.emission[at(24,1,24)] = 1;
        var snapshot = f.snapshot(); int capturedEnd = end(snapshot);
        f.emission[at(24,1,24)] = 0; f.emission[N-1] = 15;
        var captured = solve(snapshot);
        check(end(snapshot) == capturedEnd && captured.block(24,1,24) == 1 && captured.block(47,31,47) == 0,
                "source proof belongs to cloned arrays, not caller mutations");

        Fixture empty = new Fixture(true);
        var bounded = new GlobalLightField.Session(empty.snapshot()); long boundedWork = finish(bounded);
        // Only a test control disables the private optimization. The solver and all
        // optical contents are otherwise identical; no production bypass API is added.
        var unboundedSnapshot = empty.snapshot(); END.setInt(unboundedSnapshot,N);
        var unbounded = new GlobalLightField.Session(unboundedSnapshot); long unboundedWork = finish(unbounded);
        check(unboundedWork - boundedWork == N, "proven empty air avoids exactly the full optical source scan");
        for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            check(bounded.result().block(x,y,z) == unbounded.result().block(x,y,z), "bounded/unbounded block");
            check(bounded.result().sky(x,y,z) == unbounded.result().sky(x,y,z), "bounded/unbounded sky");
        }
    }

    private static GlobalLightField.Result edit(Fixture f, GlobalLightField.Result old,
            List<GlobalLightField.CellUpdate> edits, List<GlobalLightField.SkyColumnUpdate> proofs) throws Exception {
        for (var edit : edits) f.apply(edit);
        for (var proof : proofs) f.direct.set(proof.x() + W * proof.z(),proof.directSky());
        var job = new GlobalLightField.Session(old,edits,proofs); finish(job);
        var result = job.result();
        compare(f,result);
        int required = f.requiredEnd();
        check(end(result.terrain()) >= required && end(result.terrain()) <= N, "incremental source bound remains conservative");
        // Future FULL jobs may use an incrementally constructed Snapshot. This exposes an
        // incorrect bound even when the incremental invalidation itself used direct edits.
        compare(f,solve(result.terrain()));
        return result;
    }
    private static GlobalLightField.Result solve(GlobalLightField.Snapshot snapshot) {
        var job = new GlobalLightField.Session(snapshot); finish(job); return job.result();
    }
    private static long finish(GlobalLightField.Session job) {
        check(job.step(0).work() == 0, "zero work budget makes no progress");
        long work = 0; int calls = 0;
        while (!job.isComplete()) {
            int budget = calls++ < 8 ? new int[] {1,2,47,48,49,255,256,257}[calls-1] : 4096;
            var progress = job.step(budget); work += progress.work();
            check(progress.work() <= budget, "resumable step obeys work limit");
            check(calls < 100000, "source scan/interval frontier terminates");
        }
        return work;
    }
    private static void compare(Fixture f, GlobalLightField.Result actual) {
        var reference = GeneralChunkLighting.solve(new GeneralChunkLighting.Snapshot(W,H,D,
                f.opacity,f.emission,f.blocked,f.stops,f.direct));
        for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            if (actual.block(x,y,z) != reference.block(x,y,z) || actual.sky(x,y,z) != reference.sky(x,y,z))
                throw new AssertionError("Source bound changed light at " + x + "," + y + "," + z);
            values += 2;
        }
    }
    private static int end(GlobalLightField.Snapshot snapshot) throws IllegalAccessException { return END.getInt(snapshot); }
    private static int at(int x,int y,int z) { return x + W * (z + D * y); }
    private static void check(boolean condition,String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static final class Fixture {
        final byte[] opacity = new byte[N], emission = new byte[N], blocked = new byte[N];
        final BitSet stops = new BitSet(N), direct = new BitSet(P);
        Fixture(boolean sky) { if (sky) direct.set(0,P); }
        GlobalLightField.Snapshot snapshot() { return new GlobalLightField.Snapshot(W,H,D,opacity,emission,blocked,stops,direct); }
        void apply(GlobalLightField.CellUpdate value) {
            int index = at(value.x(),value.y(),value.z()); opacity[index] = (byte)value.opacity();
            emission[index] = (byte)value.emission(); blocked[index] = (byte)value.blockedOutgoingFaces();
            stops.set(index,value.stopDirectSky());
        }
        int requiredEnd() {
            for (int i = N - 1; i >= 0; i--)
                if (opacity[i] != 0 || emission[i] != 0 || (blocked[i] & 4) != 0 || stops.get(i)) return i + 1;
            return 0;
        }
    }
}
