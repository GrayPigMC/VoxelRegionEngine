/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import org.voxelregion.lighting.GeneralChunkLighting;
import org.voxelregion.lighting.GlobalLightField;
import org.voxelregion.lighting.GlobalLightField.Snapshot;

/** Independent reference checks for optional FULL initialization summaries; not a performance test. */
public final class GlobalLightSourceIndexTest {
    private static final int W=48, H=48, D=64, P=W*D, N=P*H;
    private static long checks, values;
    private static boolean indexedMode;

    public static void main(String[] args) throws Exception {
        defaultConstructorIsUnindexed();
        for(boolean mode:new boolean[]{false,true}) {
        indexedMode=mode;
        completeTilesAndSkyModes();
        uniformTilesAndAtomicErrors();
        unalignedTilesAndRepeatedOverwrites();
        scalarRangesAndFacePages();
        rawInputFallsBack();
        incrementalTerrainDoesNotInheritSummaries();
        }
        System.out.println("GlobalLightSourceIndexTest passed; checks="+checks+" independent light/direct values="+values);
    }

    private static Snapshot.Builder builder(){return new Snapshot.Builder(W,H,D,indexedMode);}
    private static void defaultConstructorIsUnindexed() throws Exception {
        F f=new F();f.sky.set(0,P);
        try(var b=new Snapshot.Builder(W,H,D)) {
            sky(b,f);set(b,f,at(24,30,24),15,1,false);addFace(b,f,at(25,35,25),4);
            Snapshot result=b.consume();
            check(field(result,"firstDirectY")==null && field(result,"emitterRows")==null,"legacy constructor does not allocate optional indexes");
            compare(f,result,false);
        }
    }

    private static void uniformTilesAndAtomicErrors() throws Exception {
        F f=new F(); f.sky.set(0,P);
        Snapshot snapshot;
        try(var b=builder()) {
            sky(b,f);
            b.fillOpticalTile(7,9,11,15,0,false); f.fillTile(7,9,11,15,0,false);
            b.fillOpticalTile(7,9,11,0,0,false); f.fillTile(7,9,11,0,0,false);
            b.fillOpticalTile(W-16,H-16,D-16,0,1,true);
            f.fillTile(W-16,H-16,D-16,0,1,true);
            b.fillOpticalTile(W-16,H-16,D-16,0,0,false);
            f.fillTile(W-16,H-16,D-16,0,0,false);
            // Uniform scalar writes can set independent transparent stops but cannot
            // erase the separate directed-face channel left by the host.
            addFace(b,f,at(8,25,12),4);
            b.fillOpticalTile(7,9,11,0,0,true); f.fillTile(7,9,11,0,0,true);
            b.fillOpticalTile(7,9,11,0,0,false); f.fillTile(7,9,11,0,0,false);
            b.fillOpticalTile(15,15,31,1,1,false); f.fillTile(15,15,31,1,1,false);
            b.fillOpticalTile(15,15,31,0,1,true); f.fillTile(15,15,31,0,1,true);
            reject(IndexOutOfBoundsException.class,()->b.fillOpticalTile(-1,0,0,15,15,true));
            reject(IndexOutOfBoundsException.class,()->b.fillOpticalTile(W-15,0,0,15,15,true));
            reject(IndexOutOfBoundsException.class,()->b.fillOpticalTile(0,H-15,0,15,15,true));
            reject(IndexOutOfBoundsException.class,()->b.fillOpticalTile(0,0,D-15,15,15,true));
            reject(IndexOutOfBoundsException.class,()->b.fillOpticalTile(Integer.MAX_VALUE,0,0,15,15,true));
            reject(IllegalArgumentException.class,()->b.fillOpticalTile(0,0,0,16,0,false));
            reject(IllegalArgumentException.class,()->b.fillOpticalTile(0,0,0,0,-1,false));
            final Throwable[] error={null};
            Thread foreign=new Thread(()->{
                try{b.fillOpticalTile(0,0,0,15,15,true);}
                catch(Throwable failure){error[0]=failure;}
            },"source-index-uniform-owner-check");
            foreign.start(); foreign.join();
            check(error[0] instanceof IllegalStateException,"whole uniform writes require the Builder owner");
            snapshot=b.consume(); indexed(snapshot);
            reject(IllegalStateException.class,()->b.fillOpticalTile(0,0,0,0,0,false));
        }
        compare(f,snapshot,false);
    }

    private static void completeTilesAndSkyModes() throws Exception {
        byte[] op=new byte[4096], em=new byte[4096];
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            int y=(x*7+z*3)&15;
            if((x+z)%5!=0)op[local(x,y,z)]=(byte)(1+(x+z)%15);
            if((x+z)%11==0)em[local(x,(y+1)&15,z)]=(byte)(1+(x*3+z)%15);
        }
        em[local(3,14,9)]=1;
        Snapshot.OpticalTile tile=new Snapshot.OpticalTile(op,em);
        for(int skyMode=0;skyMode<3;skyMode++) {
            F f=new F(); if(skyMode!=2)f.sky.set(0,P);
            if(skyMode==1)for(int column=0;column<P;column+=7)f.sky.clear(column);
            try(var b=builder()) {
                sky(b,f);
                // Reverse vertical order proves that placement order is not a hidden contract.
                for(int y=32;y>=0;y-=16)for(int z=0;z<D;z+=16)for(int x=0;x<W;x+=16) {
                    b.copyOpticalTile(x,y,z,tile); f.tile(x,y,z,op,em);
                }
                Snapshot s=b.consume(); indexed(s); compare(f,s,false);
            }
        }
    }

    private static void unalignedTilesAndRepeatedOverwrites() throws Exception {
        F f=new F(); f.sky.set(0,P);
        byte[] op=new byte[4096],em=new byte[4096];
        Arrays.fill(op,(byte)15); em[4095]=15; em[0]=1;
        Snapshot.OpticalTile solid=new Snapshot.OpticalTile(op,em);
        Snapshot.OpticalTile zero=new Snapshot.OpticalTile(new byte[4096],new byte[4096]);
        try(var b=builder()) {
            sky(b,f);
            b.copyOpticalTile(7,15,9,solid); f.tile(7,15,9,op,em);
            // Preserve explicit stops and directed faces independently of copied opacity.
            setStop(b,f,at(8,28,10),true); addFace(b,f,at(9,31,11),4);
            b.copyOpticalTile(7,15,9,zero); f.tile(7,15,9,new byte[4096],new byte[4096]);
            setStop(b,f,at(8,28,10),false);
            b.copyOpticalTile(31,32,47,solid); f.tile(31,32,47,op,em);
            b.copyOpticalTile(31,32,47,zero); f.tile(31,32,47,new byte[4096],new byte[4096]);
            // An unaligned source row must retain both edge emissions, including level one.
            byte[] rowOp=new byte[16],rowEm=new byte[16]; rowEm[0]=1; rowEm[15]=15;
            int start=at(15,16,20);
            b.copyCheckedOpticalRow(start,rowOp,0,rowEm,0,16);
            System.arraycopy(rowEm,0,f.em,start,16);
            reject(IndexOutOfBoundsException.class,()->b.copyOpticalTile(-1,0,0,solid));
            reject(IndexOutOfBoundsException.class,()->b.copyOpticalTile(W-15,0,0,solid));
            reject(IndexOutOfBoundsException.class,()->b.copyOpticalTile(0,H-15,0,solid));
            reject(IndexOutOfBoundsException.class,()->b.copyOpticalTile(0,0,D-15,solid));
            reject(NullPointerException.class,()->b.copyOpticalTile(0,0,0,null));
            Snapshot s=b.consume(); indexed(s); compare(f,s,true);
        }
    }

    private static void scalarRangesAndFacePages() throws Exception {
        F f=new F(); f.sky.set(0,P);
        try(var b=builder()) {
            sky(b,f);
            fill(b,f,at(47,13,63),at(2,16,0),15,0,false); // Crosses row and Y-plane boundaries.
            fill(b,f,0,N,0,0,false); // Removes every former top obstruction.
            for(int x=0;x<W;x++) {
                set(b,f,at(x,3,x%D),1,0,false);
                set(b,f,at(x,30,x%D),15,0,false);
                set(b,f,at(x,30,x%D),0,0,false);
            }
            addFace(b,f,at(1,H-1,1),4); // DOWN at y gives cut y, not y+1.
            addFace(b,f,at(2,0,2),4);   // DOWN out of the domain cannot remove any direct cell.
            addFace(b,f,at(3,H-1,3),1|2|8|16|32); // Horizontal/UP masks do not stop direct sky.
            set(b,f,at(4,H-1,4),1,0,false); // Opacity at y gives cut y+1, including world top.
            setStop(b,f,at(5,H-1,5),true);  // Opacity zero explicit stop has the same cut.
            setStop(b,f,at(6,H-1,6),true); setStop(b,f,at(6,H-1,6),false);
            set(b,f,at(24,9,24),0,1,false);
            byte[] masks=new byte[4096]; masks[0]=4; masks[4095]=4|2;
            int base=N-4096;
            b.copyBlockedPage(base,new Snapshot.BlockedPage(masks));
            System.arraycopy(masks,0,f.faces,base,4096);
            b.copyBlockedPage(base,new Snapshot.BlockedPage(new byte[4096]));
            Arrays.fill(f.faces,base,N,(byte)0);
            // Legacy one-row tile copies may start at an arbitrary linear index.
            byte[] tileOp=new byte[4096],tileEm=new byte[4096];
            tileOp[255<<4]=15; tileEm[(255<<4)+15]=1;
            Snapshot.OpticalTile tile=new Snapshot.OpticalTile(tileOp,tileEm);
            int start=at(47,31,63);
            b.copyOpticalTileRow(start,tile,255);
            System.arraycopy(tileOp,255<<4,f.op,start,16); System.arraycopy(tileEm,255<<4,f.em,start,16);
            Snapshot s=b.consume(); indexed(s); compare(f,s,false);
        }
    }

    private static void rawInputFallsBack() throws Exception {
        F f=new F(); f.sky.set(0,P);
        try(var b=builder()) {
            sky(b,f);
            b.copyOpticalRow(N-1,new byte[]{-1},0,new byte[]{63},0,1);
            b.setOptics(N-1,0,0,false); // Raw validation still examines final contents.
            b.copyOpticalRow(at(24,25,24),new byte[]{1},0,new byte[]{1},0,1);
            f.op[at(24,25,24)]=1; f.em[at(24,25,24)]=1;
            Snapshot s=b.consume();
            check(field(s,"firstDirectY")==null && field(s,"emitterRows")==null,"raw input selects exact old scan");
            compare(f,s,false);
        }
        try(var b=builder()) {
            b.copyOpticalRow(N-1,new byte[]{16},0,new byte[]{0},0,1);
            reject(IllegalArgumentException.class,b::consume);
            reject(IllegalStateException.class,()->b.setOptics(0,0,0,false));
        }
    }

    private static void incrementalTerrainDoesNotInheritSummaries() throws Exception {
        F f=new F(); f.sky.set(0,P);
        GlobalLightField.Result old;
        try(var b=builder()) {
            sky(b,f); set(b,f,at(24,24,24),15,15,false); setStop(b,f,at(25,25,25),true);
            Snapshot s=b.consume(); indexed(s); old=compare(f,s,false);
        }
        F before=f.copy();
        List<GlobalLightField.CellUpdate> updates=List.of(
                new GlobalLightField.CellUpdate(24,24,24,0,0,0,false),
                new GlobalLightField.CellUpdate(25,25,25,0,0,4,false),
                new GlobalLightField.CellUpdate(23,26,23,0,1,0,false));
        f.op[at(24,24,24)]=0; f.em[at(24,24,24)]=0; f.stops.clear(at(25,25,25));
        f.faces[at(25,25,25)]=4; f.em[at(23,26,23)]=1;
        var changed=finish(new GlobalLightField.Session(old,updates,List.of()),false);
        check(field(changed.terrain(),"firstDirectY")==null && field(changed.terrain(),"emitterRows")==null,
                "incremental arrays do not inherit stale full initialization summaries");
        compareResult(f,changed); compareResult(f,GlobalLightField.solve(changed.terrain()));
        compareResult(before,old);
    }

    private static GlobalLightField.Result compare(F f,Snapshot s,boolean singleStep) throws Exception {
        check(sourceEnd(s)==sourceEnd(f.snapshot()),"sourceScanEnd retains exact final semantics");
        var actual=finish(new GlobalLightField.Session(s),singleStep);
        compareResult(f,actual);
        compareResult(f,GlobalLightField.solve(f.snapshot()));
        return actual;
    }
    private static void compareResult(F f,GlobalLightField.Result actual) throws Exception {
        var reference=GeneralChunkLighting.solve(new GeneralChunkLighting.Snapshot(W,H,D,
                f.op,f.em,f.faces,f.stops,f.sky));
        BitSet direct=(BitSet)field(actual,"direct");
        for(int z=0;z<D;z++)for(int x=0;x<W;x++) {
            boolean open=f.sky.get(x+W*z);
            for(int y=H-1;y>=0;y--) {
                int i=at(x,y,z);
                if(f.op[i]!=0 || f.stops.get(i) || y+1<H && (f.faces[i+P]&4)!=0)open=false;
                if(actual.sky(x,y,z)!=reference.sky(x,y,z)||actual.block(x,y,z)!=reference.block(x,y,z)
                        ||direct.get(i)!=open)throw new AssertionError("Independent field/direct oracle differs at "+x+","+y+","+z);
                values+=3;
            }
        }
    }
    private static GlobalLightField.Result finish(GlobalLightField.Session s,boolean singleStep) {
        int calls=0; long work=0;
        check(s.step(0).work()==0,"zero budget does not advance summary initialization");
        while(!s.isComplete()) {
            int budget=singleStep?1:(calls<4?new int[]{1,3,63,65}[calls]:4096);
            var p=s.step(budget); check(p.work()>=0 && p.work()<=budget,"summary step respects exact budget");
            work+=p.work(); check(p.totalWork()==work,"summary work reported exactly once");
            check(++calls<20_000_000,"summary initialization makes bounded progress");
        }
        return s.result();
    }
    private static void indexed(Snapshot s)throws Exception {
        check((field(s,"firstDirectY")!=null)==indexedMode && (field(s,"emitterRows")!=null)==indexedMode,"safe Builder follows explicit summary selection");
    }
    private static void sky(Snapshot.Builder b,F f) {
        for(int from=f.sky.nextSetBit(0);from>=0;from=f.sky.nextSetBit(from+1))b.setSkyColumns(from,from+1,true);
    }
    private static void set(Snapshot.Builder b,F f,int i,int op,int em,boolean stop) {
        b.setOptics(i,op,em,stop);f.op[i]=(byte)op;f.em[i]=(byte)em;f.stops.set(i,stop);
    }
    private static void fill(Snapshot.Builder b,F f,int from,int to,int op,int em,boolean stop) {
        b.fillOptics(from,to,op,em,stop);Arrays.fill(f.op,from,to,(byte)op);Arrays.fill(f.em,from,to,(byte)em);f.stops.set(from,to,stop);
    }
    private static void setStop(Snapshot.Builder b,F f,int i,boolean stop) { b.setStopRange(i,i+1,stop);f.stops.set(i,stop); }
    private static void addFace(Snapshot.Builder b,F f,int i,int mask) { b.orBlockedFaces(i,mask);f.faces[i]|=(byte)mask; }
    private static int at(int x,int y,int z){return x+W*(z+D*y);}
    private static int local(int x,int y,int z){return x+(z<<4)+(y<<8);}
    private static Object field(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static int sourceEnd(Snapshot s)throws Exception{return (Integer)field(s,"sourceScanEnd");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void reject(Class<? extends Throwable> type,Action action)throws Exception {
        try{action.run();}catch(Throwable e){if(type.isInstance(e)){checks++;return;}throw new AssertionError("Unexpected exception",e);}
        throw new AssertionError("Expected "+type.getSimpleName());
    }
    private interface Action{void run()throws Exception;}
    private static final class F {
        final byte[] op=new byte[N],em=new byte[N],faces=new byte[N];final BitSet stops=new BitSet(N),sky=new BitSet(P);
        Snapshot snapshot(){return new Snapshot(W,H,D,op,em,faces,stops,sky);}
        void tile(int x,int y,int z,byte[] a,byte[] b){
            for(int dy=0;dy<16;dy++)for(int dz=0;dz<16;dz++) {
                System.arraycopy(a,local(0,dy,dz),op,at(x,y+dy,z+dz),16);
                System.arraycopy(b,local(0,dy,dz),em,at(x,y+dy,z+dz),16);
            }
        }
        void fillTile(int x,int y,int z,int opacity,int emission,boolean stop){
            for(int dy=0;dy<16;dy++)for(int dz=0;dz<16;dz++) {
                int start=at(x,y+dy,z+dz);
                Arrays.fill(op,start,start+16,(byte)opacity);
                Arrays.fill(em,start,start+16,(byte)emission);
                stops.set(start,start+16,stop);
            }
        }
        F copy(){F c=new F();System.arraycopy(op,0,c.op,0,N);System.arraycopy(em,0,c.em,0,N);
            System.arraycopy(faces,0,c.faces,0,N);c.stops.or(stops);c.sky.or(sky);return c;}
    }
}
