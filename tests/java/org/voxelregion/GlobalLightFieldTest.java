/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.voxelregion.lighting.GeneralChunkLighting;
import org.voxelregion.lighting.GlobalLightField;
import org.voxelregion.lighting.VersionedLightStore;
import org.voxelregion.lighting.VersionedLightStore.ColumnKey;

/** Independent finite-path oracle, incremental/rebuild parity and publication lifetime checks. */
public final class GlobalLightFieldTest {
    private static final int[] MASK = {1,2,4,8,16,32}, REVERSE = {2,1,8,4,32,16};
    private static long checked;
    public static void main(String[] args) {
        if (!GlobalLightFieldTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        blockAddsAndRemovals(); tiedSourcesAndChangedFace(); skyRoofAndShape(); randomMixedChanges();
        tallOrdinaryGrid(); fullRangeInitialization(); unchangedIdentity(); storageLifetime(); validation();
        System.out.println("GlobalLightFieldTest passed; independent/previous solver values=" + checked);
    }

    private static void blockAddsAndRemovals() {
        Fixture f = new Fixture(64,16,48); var dark = compare(f, GlobalLightField.solve(f.snapshot()));
        var add = new GlobalLightField.CellUpdate(31,8,24,15,15,0,false);
        f.apply(add);
        var session = new GlobalLightField.Session(dark, List.of(add), List.of());
        long work = finish(session); var lit = compare(f, session.result());
        assert work < f.cells : "An ordinary emitter change should not scan every optical cell";
        assert lit.block(31,8,24) == 15 && lit.block(32,8,24) == 14 && lit.block(45,8,24) == 1;
        assert lit.cropColumn(2,1).block(0,8,8) == 14;
        assert dark.block(31,8,24) == 0 : "Previous immutable field was mutated";
        var remove = new GlobalLightField.CellUpdate(31,8,24,0,0,0,false);
        f.apply(remove); session = new GlobalLightField.Session(lit, List.of(remove), List.of());
        finish(session); compare(f, session.result());
        assert session.result().block(32,8,24) == 0 : "Mutually illuminated cells kept a ghost source";
        assert lit.block(32,8,24) == 14;
    }

    private static void skyRoofAndShape() {
        Fixture f = new Fixture(48,32,48); f.direct.set(0,f.plane);
        var state = compare(f, GlobalLightField.solve(f.snapshot()));
        List<GlobalLightField.CellUpdate> roof = new ArrayList<>();
        for (int z = 15; z < 33; z++) for (int x = 15; x < 33; x++)
            roof.add(new GlobalLightField.CellUpdate(x,24,z,15,0,0,false));
        state = edit(f,state,roof,List.of());
        assert state.sky(24,23,24) < 15;
        var gap = new GlobalLightField.CellUpdate(24,24,24,0,0,4,false);
        state = edit(f,state,List.of(gap),List.of());
        assert state.sky(24,24,24) == 15 && state.sky(24,23,24) < 15 : "Downward shape was ignored";
        state = edit(f,state,List.of(new GlobalLightField.CellUpdate(24,24,24,0,0,0,false)),List.of());
        assert state.sky(24,0,24) == 15 : "Opening a skylight failed to restore direct sky";
        state = edit(f,state,List.of(new GlobalLightField.CellUpdate(24,20,24,1,0,0,false)),List.of());
        assert state.sky(24,20,24) == 14 : "Water-like opacity must stop direct sky then attenuate it";
        state = edit(f,state,List.of(),List.of(new GlobalLightField.SkyColumnUpdate(24,24,false)));
        assert state.sky(24,31,24) == 14;
        state = edit(f,state,List.of(),List.of(new GlobalLightField.SkyColumnUpdate(24,24,true)));
        assert state.sky(24,31,24) == 15;
    }

    private static void tiedSourcesAndChangedFace() {
        Fixture f = new Fixture(64,16,48);
        f.apply(new GlobalLightField.CellUpdate(24,8,24,0,15,0,false));
        f.apply(new GlobalLightField.CellUpdate(40,8,24,0,15,0,false));
        var state = compare(f, GlobalLightField.solve(f.snapshot()));
        assert state.block(32,8,24) == 7;
        state = edit(f,state,List.of(new GlobalLightField.CellUpdate(24,8,24,0,0,0,false)),List.of());
        assert state.block(32,8,24) == 7 && state.block(24,8,24) == 0;
        // The emitter's own value stays15, but its old westward contribution must be removed.
        state = edit(f,state,List.of(new GlobalLightField.CellUpdate(40,8,24,0,15,1,false)),List.of());
        assert state.block(40,8,24) == 15 && state.block(39,8,24) == 12;
        state = edit(f,state,List.of(new GlobalLightField.CellUpdate(40,8,24,0,15,0,false)),List.of());
        assert state.block(39,8,24) == 14;
    }

    private static void tallOrdinaryGrid() {
        Fixture f = new Fixture(64,384,64); f.direct.set(0,f.plane);
        Arrays.fill(f.opacity,0,f.plane,(byte)15);f.stops.set(0,f.plane);
        var empty = GlobalLightField.solve(f.snapshot());
        List<GlobalLightField.CellUpdate> add = new ArrayList<>(), remove = new ArrayList<>();
        for(int z=17;z<48;z+=2)for(int x=17;x<48;x+=2) {
            add.add(new GlobalLightField.CellUpdate(x,64,z,15,15,0,true));
            remove.add(new GlobalLightField.CellUpdate(x,64,z,0,0,0,false));
        }
        var session = new GlobalLightField.Session(empty,add,List.of());finish(session);var lit=session.result();
        session = new GlobalLightField.Session(lit,remove,List.of());finish(session);var cleared=session.result();
        for(int y=0;y<384;y++)for(int z=0;z<64;z++)for(int x=0;x<64;x++) {
            int distance=Math.abs(y-64)+gridDistance(x)+gridDistance(z);
            int block=Math.max(0,15-distance);
            boolean underSource=x>=17&&x<=47&&z>=17&&z<=47&&(x&1)==1&&(z&1)==1;
            int sky=y==0?0:underSource&&y==64?0:underSource&&y<64?14:15;
            assert lit.block(x,y,z)==block : "Tall grid block";
            assert lit.sky(x,y,z)==sky : "Tall grid sky at "+x+","+y+","+z;
            assert cleared.block(x,y,z)==0;
            assert cleared.sky(x,y,z)==(y==0?0:15);
            checked+=4;
        }
        assert empty.block(17,64,17)==0&&lit.block(17,64,17)==15;
    }
    private static int gridDistance(int value) { return value<17?17-value:value>47?value-47:(value-17)&1; }

    private static void fullRangeInitialization() {
        // Complete-height intervals must resume correctly at arbitrary small work limits,
        // including runs across horizontal rows and no-sky/closed columns among open ones.
        Fixture mixed = new Fixture(64,64,48); mixed.direct.set(0,mixed.plane);
        for (int z=0;z<mixed.depth;z++) for(int x=0;x<mixed.width;x++) {
            int height = 2 + (x*3+z*5)%23;
            for(int y=0;y<height;y++) mixed.opacity[mixed.index(x,y,z)]=15;
            if(x%7==0) mixed.blocked[mixed.index(x,43,z)]=4;
            if(x%11==0) mixed.stops.set(mixed.index(x,31,z));
            if(z%13==0) mixed.direct.clear(x+mixed.width*z);
            if(x%17==0 && z%19==0) mixed.emission[mixed.index(x,25,z)]=12;
            if(z%5==0) mixed.opacity[mixed.index(x,37,z)]=1;
        }
        var resumable = new GlobalLightField.Session(mixed.snapshot());
        assert resumable.step(0).work()==0;
        finish(resumable); compare(mixed,resumable.result());

        Fixture tall = new Fixture(48,384,48); tall.direct.set(0,tall.plane);
        for(int mode=0;mode<3;mode++) {
            Arrays.fill(tall.opacity,(byte)(mode==2?15:0));
            if(mode==1) Arrays.fill(tall.opacity,0,tall.plane,(byte)15);
            var session = new GlobalLightField.Session(tall.snapshot());finish(session);
            var actual = session.result();
            for(int y=0;y<tall.height;y++)for(int z=0;z<tall.depth;z++)for(int x=0;x<tall.width;x++) {
                assert actual.block(x,y,z)==0;
                assert actual.sky(x,y,z)==(mode==2 || mode==1 && y==0?0:15);
                checked+=2;
            }
        }
    }

    private static void unchangedIdentity() {
        Fixture f = new Fixture(48,16,48);f.direct.set(0,f.plane);
        var original=GlobalLightField.solve(f.snapshot());
        var unchanged=new GlobalLightField.Session(original,List.of(
                new GlobalLightField.CellUpdate(20,8,20,0,0,0,false)),List.of());
        assert unchanged.isComplete()&&unchanged.result()==original;
        var cancelled=new GlobalLightField.Session(original,List.of(
                new GlobalLightField.CellUpdate(20,8,20,15,15,63,true),
                new GlobalLightField.CellUpdate(20,8,20,0,0,0,false)),List.of(
                new GlobalLightField.SkyColumnUpdate(20,20,false),new GlobalLightField.SkyColumnUpdate(20,20,true)));
        assert cancelled.isComplete()&&cancelled.result()==original;
    }

    private static void randomMixedChanges() {
        Random random = new Random(0x564c474c4f42414cL);
        for (int trial = 0; trial < 3; trial++) {
            Fixture f = new Fixture(48,16,48);
            for (int i = 0; i < f.cells; i++) {
                if (random.nextInt(5) == 0) f.opacity[i] = (byte)random.nextInt(16);
                if (random.nextInt(400) == 0) f.emission[i] = (byte)(1+random.nextInt(15));
                if (random.nextInt(7) == 0) f.blocked[i] = (byte)random.nextInt(64);
                if (random.nextInt(400) == 0) f.stops.set(i);
            }
            for (int i = 0; i < f.plane; i++) if (random.nextBoolean()) f.direct.set(i);
            var state = compare(f,GlobalLightField.solve(f.snapshot()));
            for (int turn = 0; turn < 3; turn++) {
                List<GlobalLightField.CellUpdate> edits = new ArrayList<>();
                for (int i = 0; i < 80; i++) edits.add(new GlobalLightField.CellUpdate(random.nextInt(f.width),
                        random.nextInt(f.height),random.nextInt(f.depth),random.nextInt(16),
                        random.nextInt(9)==0?random.nextInt(16):0,random.nextInt(64),random.nextInt(50)==0));
                state = edit(f,state,edits,List.of(new GlobalLightField.SkyColumnUpdate(random.nextInt(f.width),
                        random.nextInt(f.depth),random.nextBoolean())));
            }
        }
    }

    private static GlobalLightField.Result edit(Fixture f, GlobalLightField.Result previous,
            List<GlobalLightField.CellUpdate> edits, List<GlobalLightField.SkyColumnUpdate> sky) {
        for (var edit : edits) f.apply(edit);
        for (var edit : sky) f.direct.set(edit.x()+f.width*edit.z(),edit.directSky());
        var session = new GlobalLightField.Session(previous,edits,sky);
        finish(session); return compare(f,session.result());
    }
    private static long finish(GlobalLightField.Session session) {
        long work = 0; int call = 0;
        while (!session.isComplete()) {
            int budget = 1 + ((call++ * 991) & 4095);
            var progress = session.step(budget); assert progress.work() <= budget;
            work += progress.work();
            assert call < 100_000 : "Relaxation failed to terminate";
        }
        assert session.step(0).work() == 0;
        return work;
    }
    private static GlobalLightField.Result compare(Fixture f, GlobalLightField.Result actual) {
        int[][] expected = finitePathOracle(f);
        var reference = GeneralChunkLighting.solve(new GeneralChunkLighting.Snapshot(f.width,f.height,f.depth,
                f.opacity,f.emission,f.blocked,f.stops,f.direct));
        for (int y = 0; y < f.height; y++) for (int z = 0; z < f.depth; z++) for (int x = 0; x < f.width; x++) {
            int i = f.index(x,y,z);
            assert actual.block(x,y,z) == expected[0][i] : "Block " + x+","+y+","+z+" actual="+actual.block(x,y,z)+" expected="+expected[0][i];
            assert actual.sky(x,y,z) == expected[1][i] : "Sky " + x+","+y+","+z+" actual="+actual.sky(x,y,z)+" expected="+expected[1][i];
            assert actual.block(x,y,z) == reference.block(x,y,z);
            assert actual.sky(x,y,z) == reference.sky(x,y,z);
            checked += 4;
        }
        return actual;
    }

    /** Enumerates paths of up to 14 diffuse edges in synchronous sweeps, without a work queue. */
    private static int[][] finitePathOracle(Fixture f) {
        int[][] sources = {new int[f.cells],new int[f.cells]};
        for (int column = 0; column < f.plane; column++) {
            boolean sky = f.direct.get(column);
            for (int y = f.height-1; y >= 0; y--) {
                int i = column+y*f.plane;
                if (f.opacity[i] > 0 || f.stops.get(i) || y < f.height-1 && (f.blocked[i+f.plane]&4)!=0) sky = false;
                sources[0][i] = f.emission[i]; sources[1][i] = sky?15:0;
            }
        }
        int[][] current = {sources[0].clone(),sources[1].clone()};
        for (int distance = 1; distance <= 14; distance++) {
            int[][] next = {sources[0].clone(),sources[1].clone()};
            for (int y = 0; y < f.height; y++) for (int z = 0; z < f.depth; z++) for (int x = 0; x < f.width; x++) {
                int i = f.index(x,y,z), loss = Math.max(1,f.opacity[i]);
                int[] neighbors = {x>0?i-1:-1,x+1<f.width?i+1:-1,y>0?i-f.plane:-1,
                        y+1<f.height?i+f.plane:-1,z>0?i-f.width:-1,z+1<f.depth?i+f.width:-1};
                for (int face = 0; face < 6; face++) {
                    int source = neighbors[face];
                    if (source >= 0 && (f.blocked[source]&REVERSE[face]) == 0)
                        for (int layer = 0; layer < 2; layer++)
                            next[layer][i] = Math.max(next[layer][i],current[layer][source]-loss);
                }
            }
            current = next;
        }
        return current;
    }

    private static void storageLifetime() {
        Fixture f = new Fixture(48,16,48); f.direct.set(0,f.plane);
        var solution = GlobalLightField.solve(f.snapshot()).cropColumn(1,1);
        var store = new VersionedLightStore(); var a = new ColumnKey(-2,3); var b = new ColumnKey(-1,3);
        store.markChanged(a.x(),a.z()); store.markChanged(b.x(),b.z());
        var first = store.capture(List.of(a,b));
        assert store.publishIfUnchanged(first,-4,Map.of(a,solution,b,solution)).installed();
        var old = store.read(a.x(),a.z()).orElseThrow(); assert old.sky(0,-64,0) == 15;
        var pending = store.capture(List.of(a,b));
        store.markChanged(b.x(),b.z());
        assert !store.publishIfUnchanged(pending,-4,Map.of(a,solution)).installed() : "Read halo invalidation was missed";
        assert store.read(a.x(),a.z()).orElseThrow() == old;
        assert !store.isCurrent(b.x(),b.z()) && store.isCurrent(a.x(),a.z());
        var unloading = store.capture(List.of(a,b)); store.remove(a.x(),a.z()); store.markChanged(a.x(),a.z());
        assert !store.unchanged(unloading) : "Unload/reload ABA";
        var other = new VersionedLightStore(); boolean foreignRejected = false;
        try { other.unchanged(first); } catch (IllegalArgumentException expected) { foreignRejected = true; }
        assert foreignRejected;
        byte[] nibble = old.data().skySection(0); Arrays.fill(nibble,(byte)0);
        assert old.sky(0,-64,0) == 15 : "Published section exposed mutable nibble data";
        assert store.statistics().stalePublications() == 1;
    }

    private static void validation() {
        Fixture f = new Fixture(48,16,48); var snapshot = f.snapshot();
        Arrays.fill(f.emission,(byte)15); assert GlobalLightField.solve(snapshot).block(0,0,0) == 0;
        var result = GlobalLightField.solve(snapshot);
        boolean rejected = false;
        try { result.cropColumn(0,0); } catch (IllegalArgumentException expected) { rejected = true; }
        assert rejected;
        rejected = false;
        try { new GlobalLightField.Session(result,List.of(new GlobalLightField.CellUpdate(48,0,0,0,0,0,false)),List.of()); }
        catch (IndexOutOfBoundsException expected) { rejected = true; }
        assert rejected;
        rejected = false;
        try { new GlobalLightField.Session(snapshot,1); } catch (IllegalArgumentException expected) { rejected = true; }
        assert rejected;
    }
    private static final class Fixture {
        final int width,height,depth,plane,cells;
        final byte[] opacity,emission,blocked;
        final BitSet stops = new BitSet(),direct = new BitSet();
        Fixture(int width,int height,int depth) {
            this.width=width;this.height=height;this.depth=depth;plane=width*depth;cells=plane*height;
            opacity=new byte[cells];emission=new byte[cells];blocked=new byte[cells];
        }
        int index(int x,int y,int z) { return x+width*(z+depth*y); }
        GlobalLightField.Snapshot snapshot() { return new GlobalLightField.Snapshot(width,height,depth,opacity,emission,blocked,stops,direct); }
        void apply(GlobalLightField.CellUpdate update) {
            int i=index(update.x(),update.y(),update.z());opacity[i]=(byte)update.opacity();emission[i]=(byte)update.emission();
            blocked[i]=(byte)update.blockedOutgoingFaces();stops.set(i,update.stopDirectSky());
        }
    }
}
