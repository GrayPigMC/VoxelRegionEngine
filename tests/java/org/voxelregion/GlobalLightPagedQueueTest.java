/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.lang.reflect.Field;
import java.util.*;
import org.voxelregion.lighting.GlobalLightField;
import org.voxelregion.lighting.GeneralChunkLighting;

/** Independent numerical checks with a deliberately saturated, one-page increase queue. */
public final class GlobalLightPagedQueueTest {
    static final int W=48,H=48,D=48,P=W*D,N=P*H;
    static final Field PAGE_LIMIT,PEAK_PAGES,SPILLS,STALE;
    static {
        try {
            PAGE_LIMIT=GlobalLightField.Session.class.getDeclaredField("pageLimit");
            PEAK_PAGES=GlobalLightField.Session.class.getDeclaredField("peakIncreasePages");
            SPILLS=GlobalLightField.Session.class.getDeclaredField("spillOffers");
            STALE=GlobalLightField.Session.class.getDeclaredField("staleEntries");
            for(Field f:List.of(PAGE_LIMIT,PEAK_PAGES,SPILLS,STALE))f.setAccessible(true);
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    static int at(int x,int y,int z){return x+W*(z+D*y);}
    static long checked,spilled,stale;
    static GlobalLightField.Result finish(GlobalLightField.Session session)throws Exception {
        PAGE_LIMIT.setInt(session,1);
        int calls=0;
        while(!session.isComplete()) {
            int budget=(calls++%3==0)?1:(calls%3==1?31:4096);
            var progress=session.step(budget);
            assert progress.work()<=budget;
            assert calls<200000:"Spill queue failed to terminate";
        }
        assert PEAK_PAGES.getInt(session)<=1:"Queue exceeded hard page cap";
        spilled+=SPILLS.getLong(session);stale+=STALE.getLong(session);
        return session.result();
    }
    static void compare(GlobalLightField.Result actual,byte[] opacity,byte[] emission,byte[] blocked,BitSet stops,BitSet direct) {
        var reference=GeneralChunkLighting.solve(new GeneralChunkLighting.Snapshot(W,H,D,opacity,emission,blocked,stops,direct));
        for(int y=0;y<H;y++)for(int z=0;z<D;z++)for(int x=0;x<W;x++) {
            assert actual.block(x,y,z)==reference.block(x,y,z):"Spill block mismatch at "+x+","+y+","+z;
            assert actual.sky(x,y,z)==reference.sky(x,y,z):"Spill sky mismatch at "+x+","+y+","+z;
            checked+=2;
        }
    }
    public static void main(String[] args)throws Exception {
        if(!GlobalLightPagedQueueTest.class.desiredAssertionStatus())throw new IllegalStateException("Use -ea");
        byte[] opacity=new byte[N],emission=new byte[N],blocked=new byte[N];
        BitSet stops=new BitSet(N),direct=new BitSet(P);direct.set(0,P);
        Arrays.fill(opacity,0,P*2,(byte)15);
        for(int z=4;z<44;z++)for(int x=4;x<44;x++) {
            if((x+z)%7!=0)opacity[at(x,30,z)]=15;
            if((x+z)%11==0)blocked[at(x,39,z)]=4;
            if((x+2*z)%13==0)opacity[at(x,17,z)]=1;
        }
        for(int y=5;y<47;y+=8)for(int z=7;z<42;z+=5)for(int x=7;x<42;x+=5)
            emission[at(x,y,z)]=(byte)(3+(x+y+z)%13);
        emission[at(20,40,20)]=3;emission[at(22,40,20)]=15;
        var state=finish(new GlobalLightField.Session(new GlobalLightField.Snapshot(W,H,D,opacity,emission,blocked,stops,direct)));
        compare(state,opacity,emission,blocked,stops,direct);
        List<GlobalLightField.CellUpdate> edits=new ArrayList<>();
        for(int y=5;y<47;y+=8)for(int z=7;z<42;z+=5)for(int x=7;x<42;x+=5) {
            int index=at(x,y,z);emission[index]=0;
            edits.add(new GlobalLightField.CellUpdate(x,y,z,opacity[index],0,blocked[index],false));
        }
        // Remove many unrelated roots, open a skylight, and change a source's outgoing
        // face without changing its own value, all before monotone increase begins.
        opacity[at(20,30,20)]=0;edits.add(new GlobalLightField.CellUpdate(20,30,20,0,0,0,false));
        blocked[at(22,40,20)]=1;edits.add(new GlobalLightField.CellUpdate(22,40,20,0,15,1,false));
        state=finish(new GlobalLightField.Session(state,edits,List.of()));
        compare(state,opacity,emission,blocked,stops,direct);
        edits.clear();
        for(int z=0;z<D;z++)for(int x=0;x<W;x++) {
            int index=at(x,45,z);opacity[index]=1;
            edits.add(new GlobalLightField.CellUpdate(x,45,z,1,emission[index],blocked[index],false));
        }
        state=finish(new GlobalLightField.Session(state,edits,List.of()));
        compare(state,opacity,emission,blocked,stops,direct);
        assert spilled>0:"Fixture did not exercise capacity spill";
        assert stale>0:"Fixture did not exercise obsolete carried levels";
        long small=GlobalLightField.extraIncreaseWorkingBytes(48*16*48);
        assert small>=4L*48*16*48+16L*((48*16*48+63)/64):"Small domain page bytes were omitted";
        System.out.println("GlobalLightPagedQueueTest PASS; values="+checked+" maxPages=1 spillOffers="+spilled+" staleEntries="+stale+" extraBytesSmall="+small);
    }
}
