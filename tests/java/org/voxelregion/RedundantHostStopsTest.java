/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.voxelregion.lighting.GeneralChunkLighting;
import org.voxelregion.lighting.GlobalLightField;

/** A host may omit direct-sky stop bits implied by opacity; independent stops remain observable. */
public final class RedundantHostStopsTest {
    private static final int W = 48, H = 32, D = 64, P = W * D, N = P * H;
    private static long checks, values;

    public static void main(String[] args) throws Exception {
        for (int skyMode = 0; skyMode < 3; skyMode++) fullAndIncremental(skyMode);
        explicitTransparentStopsMustRemain();
        System.out.println("RedundantHostStopsTest passed; checks=" + checks
                + " independent light/direct values=" + values);
    }

    private static void fullAndIncremental(int skyMode) throws Exception {
        Fixture f = fixture(skyMode);
        var originalSnapshot = f.snapshot(true);
        var normalizedSnapshot = f.snapshot(false);
        check(sourceEnd(originalSnapshot) == sourceEnd(normalizedSnapshot), "same final source bound");
        var original = GlobalLightField.solve(originalSnapshot);
        var normalized = GlobalLightField.solve(normalizedSnapshot);
        compare(f, original, normalized);

        var untouchedOriginal = original;
        var untouchedNormalized = normalized;
        Fixture untouched = f.copy();
        List<List<Edit>> rounds = List.of(
                List.of(new Edit(15, 15, 15, 15, 15, 4),
                        new Edit(16, 16, 15, 1, 1, 8),
                        new Edit(47, 31, 63, 15, 15, 4),
                        new Edit(47, 31, 63, 0, 0, 0), // Last edit wins, including its stop.
                        new Edit(16, 7, 16, 0, 0, 0)),
                List.of(new Edit(15, 15, 15, 0, 0, 0),
                        new Edit(16, 16, 15, 0, 15, 0),
                        new Edit(47, 31, 63, 1, 1, 4),
                        new Edit(15, 1, 21, 0, 0, 2), // The 4095/4096 material-page edge.
                        new Edit(16, 1, 21, 0, 0, 1)),
                List.of(new Edit(16, 16, 15, 0, 0, 0),
                        new Edit(47, 31, 63, 0, 0, 0),
                        new Edit(15, 1, 21, 1, 0, 0),
                        new Edit(16, 1, 21, 15, 0, 0)));
        for (List<Edit> edits : rounds) {
            List<GlobalLightField.CellUpdate> oldUpdates = new ArrayList<>();
            List<GlobalLightField.CellUpdate> normalizedUpdates = new ArrayList<>();
            for (Edit edit : edits) {
                oldUpdates.add(edit.update(true)); normalizedUpdates.add(edit.update(false));
                f.apply(edit);
            }
            original = finish(new GlobalLightField.Session(original, oldUpdates, List.of()));
            normalized = finish(new GlobalLightField.Session(normalized, normalizedUpdates, List.of()));
            compare(f, original, normalized);
            check(sourceEnd(original.terrain()) == sourceEnd(normalized.terrain()),
                    "incremental conservative source bound is unchanged");
            compare(f, GlobalLightField.solve(original.terrain()), GlobalLightField.solve(normalized.terrain()));
        }
        compare(untouched, untouchedOriginal, untouchedNormalized);
        // A repeated identical optical notification remains a true no-op in either representation.
        Edit same = new Edit(15, 1, 21, 1, 0, 0);
        var oldNoop = new GlobalLightField.Session(original, List.of(same.update(true)), List.of());
        var newNoop = new GlobalLightField.Session(normalized, List.of(same.update(false)), List.of());
        check(oldNoop.isComplete() && oldNoop.result() == original, "old optical no-op retains result identity");
        check(newNoop.isComplete() && newNoop.result() == normalized, "normalized no-op retains result identity");
    }

    private static Fixture fixture(int skyMode) {
        Fixture f = new Fixture();
        if (skyMode != 2) f.sky.set(0, P);
        for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            int column = x + W * z;
            f.opacity[column] = 15;
            int y = 2 + Math.floorMod(x * 7 + z * 11, 13);
            if ((column % 5) != 0) f.opacity[at(x, y, z)] = (byte)(1 + column % 15);
            if (column % 97 == 0) f.emission[at(x, 1, z)] = (byte)(1 + column % 15);
            if (column % 23 == 0) f.blocked[at(x, 20, z)] |= 4;
            if (skyMode == 1 && (x % 7 == 0 || z % 11 == 0)) f.sky.clear(column);
        }
        f.blocked[4095] |= 2; f.blocked[4096] |= 1;
        f.blocked[at(15, 15, 15)] |= 4; f.blocked[at(15, 14, 15)] |= 8;
        f.opacity[at(47, 31, 63)] = 1;
        return f;
    }

    private static void explicitTransparentStopsMustRemain() throws Exception {
        Fixture f = new Fixture(); f.sky.set(0, P);
        BitSet independentStops = new BitSet(N);
        independentStops.set(at(24, 20, 24));
        var withStop = GlobalLightField.solve(new GlobalLightField.Snapshot(W,H,D,
                f.opacity,f.emission,f.blocked,independentStops,f.sky));
        var withoutStop = GlobalLightField.solve(f.snapshot(false));
        var reference = GeneralChunkLighting.solve(new GeneralChunkLighting.Snapshot(W,H,D,
                f.opacity,f.emission,f.blocked,independentStops,f.sky));
        compareReference(withStop, reference);
        check(!direct(withStop).get(at(24,20,24)) && direct(withoutStop).get(at(24,20,24)),
                "opacity-zero explicit stops affect the direct field and must remain supported");
        check(withStop.sky(24,20,24) < withoutStop.sky(24,20,24), "dropping a nonredundant stop changes real sky values");
        var removed = finish(new GlobalLightField.Session(withStop,
                List.of(new GlobalLightField.CellUpdate(24,20,24,0,0,0,false)), List.of()));
        equal(removed, withoutStop);
        var restored = finish(new GlobalLightField.Session(removed,
                List.of(new GlobalLightField.CellUpdate(24,20,24,0,0,0,true)), List.of()));
        equal(restored, withStop);
    }

    private static GlobalLightField.Result finish(GlobalLightField.Session session) {
        int calls = 0;
        while (!session.isComplete()) {
            int budget = calls < 4 ? new int[] {1,7,63,65}[calls] : 4096;
            var progress = session.step(budget);
            check(progress.work() >= 0 && progress.work() <= budget, "incremental work respects its budget");
            check(++calls < 100000, "incremental normalization terminates");
        }
        return session.result();
    }

    private static void compare(Fixture fixture, GlobalLightField.Result old, GlobalLightField.Result normalized)
            throws Exception {
        var reference = GeneralChunkLighting.solve(new GeneralChunkLighting.Snapshot(W,H,D,
                fixture.opacity,fixture.emission,fixture.blocked,fixture.redundantStops(),fixture.sky));
        compareReference(old, reference); compareReference(normalized, reference); equal(old, normalized);
    }
    private static void compareReference(GlobalLightField.Result actual, GeneralChunkLighting.Result expected) {
        for (int y=0;y<H;y++) for(int z=0;z<D;z++) for(int x=0;x<W;x++) {
            if (actual.block(x,y,z)!=expected.block(x,y,z) || actual.sky(x,y,z)!=expected.sky(x,y,z))
                throw new AssertionError("Independent oracle differs at " + x + "," + y + "," + z);
            values += 2;
        }
    }
    private static void equal(GlobalLightField.Result a, GlobalLightField.Result b) throws Exception {
        for (int y=0;y<H;y++) for(int z=0;z<D;z++) for(int x=0;x<W;x++) {
            if (a.block(x,y,z)!=b.block(x,y,z) || a.sky(x,y,z)!=b.sky(x,y,z))
                throw new AssertionError("Normalization changed field at " + x + "," + y + "," + z);
            values += 2;
        }
        check(direct(a).equals(direct(b)), "complete direct-source bitmaps are identical"); values += N;
    }
    private static BitSet direct(GlobalLightField.Result value) throws Exception {
        Field field=GlobalLightField.Result.class.getDeclaredField("direct"); field.setAccessible(true);
        return (BitSet)field.get(value);
    }
    private static int sourceEnd(GlobalLightField.Snapshot value) throws Exception {
        Field field=GlobalLightField.Snapshot.class.getDeclaredField("sourceScanEnd"); field.setAccessible(true);
        return field.getInt(value);
    }
    private static int at(int x,int y,int z) { return x + W * (z + D * y); }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); checks++; }
    private record Edit(int x,int y,int z,int opacity,int emission,int faces) {
        GlobalLightField.CellUpdate update(boolean redundantStops) {
            return new GlobalLightField.CellUpdate(x,y,z,opacity,emission,faces,redundantStops && opacity!=0);
        }
    }
    private static final class Fixture {
        final byte[] opacity=new byte[N], emission=new byte[N], blocked=new byte[N];
        final BitSet sky=new BitSet(P);
        BitSet redundantStops() {
            BitSet result=new BitSet(N);
            for(int i=0;i<N;i++)if(opacity[i]!=0)result.set(i);
            return result;
        }
        GlobalLightField.Snapshot snapshot(boolean redundantStops) {
            return new GlobalLightField.Snapshot(W,H,D,opacity,emission,blocked,
                    redundantStops?redundantStops():new BitSet(),sky);
        }
        void apply(Edit edit) {
            int i=at(edit.x,edit.y,edit.z); opacity[i]=(byte)edit.opacity;
            emission[i]=(byte)edit.emission; blocked[i]=(byte)edit.faces;
        }
        Fixture copy() {
            Fixture result=new Fixture();
            System.arraycopy(opacity,0,result.opacity,0,N); System.arraycopy(emission,0,result.emission,0,N);
            System.arraycopy(blocked,0,result.blocked,0,N); result.sky.or(sky); return result;
        }
    }
}
