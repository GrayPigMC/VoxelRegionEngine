/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.voxelregion.lighting.GeneralChunkLighting;
import org.voxelregion.lighting.GlobalLightField;
import org.voxelregion.lighting.GlobalLightField.Snapshot;

/** Owned capture publication, defensive legacy inputs, invalidation and independent optical oracle. */
public final class GlobalLightSnapshotBuilderTest {
    private static final int W = 48, H = 32, D = 64, P = W * D, N = P * H;
    private static final Snapshot.OpticalTile ZERO_TILE = new Snapshot.OpticalTile(new byte[4096],new byte[4096]);
    private static final Snapshot.BlockedPage ZERO_FACES = new Snapshot.BlockedPage(new byte[4096]);
    private static long checks, lightValues;

    public static void main(String[] args) throws Exception {
        bulkRowsAndFinalBounds();
        checkedRowsAndFinalBounds();
        checkedRowsRejectAtomically();
        immutableTilesAndRows();
        immutableBlockedPages();
        rawRowsRetainFinalValidation();
        inputAliasesAndLegacyConstructor();
        oneShotAndAbandonment();
        failedConsumptionSeals();
        invalidArgumentsAreAtomic();
        ownerThreadAndReaderTransfer();
        randomFieldsAndIndependentOracle();
        System.out.println("GlobalLightSnapshotBuilderTest passed; checks=" + checks
                + " independent light values=" + lightValues);
    }

    private static void checkedRowsAndFinalBounds() throws Exception {
        Fixture expected = new Fixture();
        byte[] opacity = new byte[24], emission = new byte[24];
        for (int i = 0; i < 24; i++) { opacity[i] = (byte)(i & 15); emission[i] = (byte)(15 - (i & 15)); }
        try (var builder = new Snapshot.Builder(W,H,D)) {
            builder.setSkyColumns(0,P,true); expected.sky.set(0,P);
            for (int length = 0; length <= 16; length++) {
                int start = at(31,15,15) + 32 * length;
                builder.copyCheckedOpticalRow(start,opacity,3,emission,5,length);
                System.arraycopy(opacity,3,expected.opacity,start,length);
                System.arraycopy(emission,5,expected.emission,start,length);
            }
            // A high nibble at bit 60 must survive signed-long staging without sign extension.
            byte[] bright = new byte[16]; Arrays.fill(bright,(byte)15);
            builder.copyCheckedOpticalRow(N - 32,bright,0,bright,0,16);
            expected.fill(N - 32,N - 16,15,15,false);
            builder.copyCheckedOpticalRow(N - 32,new byte[16],0,new byte[16],0,16);
            expected.fill(N - 32,N - 16,0,0,false);
            builder.setOptics(N - 1,15,15,true); builder.setOptics(N - 1,0,0,false);
            builder.setStopRange(N - 9,N,true); builder.setStopRange(N - 9,N,false);
            builder.orBlockedFaces(N - 2,1 | 2 | 8 | 16 | 32);
            expected.blocked[N - 2] = 1 | 2 | 8 | 16 | 32;
            builder.orBlockedFaces(at(20,20,20),4); expected.blocked[at(20,20,20)] = 4;
            Arrays.fill(opacity,(byte)-1); Arrays.fill(emission,(byte)31);
            Snapshot actual = builder.consume();
            check(sourceEnd(actual) == at(20,20,20) + 1,"checked writes trim cleared upper sources exactly");
            compare(expected,actual,true);
        }
        try (var builder = new Snapshot.Builder(W,H,D)) {
            builder.copyCheckedOpticalRow(N,new byte[0],0,new byte[0],0,0);
            builder.copyOpticalRow(N,new byte[0],0,new byte[0],0,0);
            builder.fillOptics(N,N,15,15,true); builder.setStopRange(N,N,true);
            check((Integer)field(builder,"sourceCandidate") == 0,"empty writes leave source candidate unchanged");
            compare(new Fixture(),builder.consume(),false);
        }
    }

    private static void checkedRowsRejectAtomically() throws Exception {
        for (int channel = 0; channel < 2; channel++) for (byte invalid : new byte[] {-1,16,63}) {
            Fixture expected = new Fixture();
            try (var builder = new Snapshot.Builder(W,H,D)) {
                int start = at(16,16,16);
                builder.setOptics(start,1,2,true); expected.set(start,1,2,true);
                byte[] opacity = new byte[16], emission = new byte[16];
                Arrays.fill(opacity,(byte)15); Arrays.fill(emission,(byte)14);
                if (channel == 0) opacity[15] = invalid; else emission[15] = invalid;
                rejected(IllegalArgumentException.class,() -> builder.copyCheckedOpticalRow(start,
                        opacity,0,emission,0,16),"invalid last byte cannot partially change either channel");
                check((Integer)field(builder,"sourceCandidate") == start + 1,"invalid row does not change source bound");
                compare(expected,builder.consume(),false);
            }
        }
        try (var builder = new Snapshot.Builder(W,H,D)) {
            rejected(IllegalArgumentException.class,() -> builder.copyCheckedOpticalRow(0,
                    new byte[17],0,new byte[17],0,17),"checked row is bounded to sixteen cells");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyCheckedOpticalRow(0,
                    new byte[1],0,new byte[1],0,-1),"negative checked length");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyCheckedOpticalRow(N - 1,
                    new byte[16],0,new byte[16],0,16),"checked destination endpoint");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyCheckedOpticalRow(0,
                    new byte[] {15},0,new byte[0],0,1),"checked second source fails before destination change");
            rejected(NullPointerException.class,() -> builder.copyCheckedOpticalRow(0,
                    new byte[] {15},0,null,0,1),"checked null source fails before destination change");
            compare(new Fixture(),builder.consume(),false);
        }
    }

    private static void immutableTilesAndRows() throws Exception {
        byte[] opacity = new byte[4096], emission = new byte[4096];
        for (int i = 0; i < 4096; i++) if ((i >>> 4) % 3 != 0) {
            opacity[i] = (byte)(i & 15); emission[i] = (byte)((i >>> 4) & 15);
        }
        Snapshot.OpticalTile tile = new Snapshot.OpticalTile(opacity,emission);
        Fixture expected = new Fixture();
        try (var builder = new Snapshot.Builder(W,H,D)) {
            builder.setSkyColumns(0,P,true); expected.sky.set(0,P);
            for (int row = 0; row < 256; row++) {
                int target = at(16,row >>> 4,16 + (row & 15));
                System.arraycopy(opacity,row << 4,expected.opacity,target,16);
                System.arraycopy(emission,row << 4,expected.emission,target,16);
            }
            // The tile freezes its inputs before a future capture reads any row.
            Arrays.fill(opacity,(byte)-1); Arrays.fill(emission,(byte)63);
            for (int row = 0; row < 256; row++)
                builder.copyOpticalTileRow(at(16,row >>> 4,16 + (row & 15)),tile,row);
            builder.setStopRange(N - 5,N - 4,true); expected.stops.set(N - 5,N - 4);
            builder.fillOptics(N - 16,N,15,15,false); expected.fill(N - 16,N,15,15,false);
            builder.copyOpticalTileRow(N - 16,ZERO_TILE,255);
            Arrays.fill(expected.opacity,N - 16,N,(byte)0); Arrays.fill(expected.emission,N - 16,N,(byte)0);
            // Tile rows change only scalar channels; this explicitly retained stop survives.
            builder.setStopRange(N - 5,N - 4,true); expected.stops.set(N - 5,N - 4);
            compare(expected,builder.consume(),true);
        }
        check(tile.estimatedBytes() >= 8192,"tile budget includes both owned channels");
        for (int channel = 0; channel < 2; channel++) {
            byte[] badOpacity = new byte[4096], badEmission = new byte[4096];
            if (channel == 0) badOpacity[4095] = 16; else badEmission[4095] = -1;
            rejected(IllegalArgumentException.class,() -> new Snapshot.OpticalTile(badOpacity,badEmission),
                    "tile validates actual private contents through last cell");
        }
        rejected(IllegalArgumentException.class,() -> new Snapshot.OpticalTile(new byte[4095],new byte[4096]),"tile exact size");
        rejected(NullPointerException.class,() -> new Snapshot.OpticalTile(new byte[4096],null),"tile null source");
        try (var builder = new Snapshot.Builder(W,H,D)) {
            rejected(IndexOutOfBoundsException.class,() -> builder.copyOpticalTileRow(0,tile,-1),"negative tile row");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyOpticalTileRow(0,tile,256),"tile row endpoint");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyOpticalTileRow(N - 15,tile,255),"tile destination bound");
            rejected(NullPointerException.class,() -> builder.copyOpticalTileRow(0,null,0),"tile nonnull before write");
            compare(new Fixture(),builder.consume(),false);
        }
    }

    private static void immutableBlockedPages() throws Exception {
        byte[] masks = new byte[4096], sideOnly = new byte[4096];
        for (int i = 0; i < masks.length; i++) {
            masks[i] = (byte)(i & 63); sideOnly[i] = (byte)(masks[i] & ~4);
        }
        Snapshot.BlockedPage page = new Snapshot.BlockedPage(masks);
        Snapshot.BlockedPage lateral = new Snapshot.BlockedPage(sideOnly);
        Fixture expected = new Fixture();
        try (var builder = new Snapshot.Builder(W,H,D)) {
            builder.setSkyColumns(0,P,true); expected.sky.set(0,P);
            builder.setOptics(4094,0,15,false); expected.set(4094,0,15,false);
            // A page is a linear slice; this deliberately crosses a page and x/z/y rows.
            builder.copyBlockedPage(4095,page); System.arraycopy(masks,0,expected.blocked,4095,4096);
            builder.copyBlockedPage(N - 4096,page);
            builder.copyBlockedPage(N - 4096,ZERO_FACES); // replacement clears stale DOWN and lateral bits
            builder.copyBlockedPage(N - 4096,lateral);
            System.arraycopy(sideOnly,0,expected.blocked,N - 4096,4096);
            Arrays.fill(masks,(byte)-1); Arrays.fill(sideOnly,(byte)64);
            Snapshot actual = builder.consume();
            check(sourceEnd(actual) == 8191,"upper lateral masks do not retain an obsolete DOWN source bound");
            compare(expected,actual,true);
        }
        check(page.estimatedBytes() >= 4096,"page budget includes its owned masks");
        for (byte invalid : new byte[] {-1,64,127}) {
            byte[] invalidPage = new byte[4096]; invalidPage[4095] = invalid;
            rejected(IllegalArgumentException.class,() -> new Snapshot.BlockedPage(invalidPage),
                    "page validates its private copy through the last mask");
        }
        rejected(IllegalArgumentException.class,() -> new Snapshot.BlockedPage(new byte[4095]),"exact face page size");
        rejected(NullPointerException.class,() -> new Snapshot.BlockedPage(null),"nonnull face page input");
        try (var builder = new Snapshot.Builder(W,H,D)) {
            rejected(IndexOutOfBoundsException.class,() -> builder.copyBlockedPage(-1,page),"negative face target");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyBlockedPage(N - 4095,page),"face destination bound");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyBlockedPage(Integer.MAX_VALUE,page),"face target overflow");
            rejected(NullPointerException.class,() -> builder.copyBlockedPage(0,null),"nonnull before face write");
            compare(new Fixture(),builder.consume(),false);
        }
    }

    private static void rawRowsRetainFinalValidation() throws Exception {
        try (var builder = new Snapshot.Builder(W,H,D)) {
            builder.copyCheckedOpticalRow(0,new byte[] {15},0,new byte[] {15},0,1);
            builder.copyOpticalRow(N - 1,new byte[] {-1},0,new byte[] {63},0,1);
            builder.copyCheckedOpticalRow(N - 1,new byte[1],0,new byte[1],0,1);
            Fixture expected = new Fixture(); expected.set(0,15,15,false);
            compare(expected,builder.consume(),false); // legacy permits correcting raw values before final validation
        }
        var polluted = new Snapshot.Builder(W,H,D);
        polluted.copyOpticalTileRow(0,ZERO_TILE,0);
        polluted.copyOpticalRow(20,new byte[] {31},0,new byte[1],0,1);
        polluted.copyOpticalRow(N - 1,new byte[] {-1},0,new byte[1],0,1);
        polluted.copyCheckedOpticalRow(N - 1,new byte[1],0,new byte[1],0,1);
        rejected(IllegalArgumentException.class,polluted::consume,"checked overwrite cannot erase earlier raw validation obligation");
        for (Runnable mutation : writes(polluted)) rejected(IllegalStateException.class,mutation,"mixed failed consume remains sealed");
        polluted.close();
    }

    private static void bulkRowsAndFinalBounds() throws Exception {
        Fixture expected = new Fixture();
        try (var builder = new Snapshot.Builder(W,H,D)) {
            builder.setSkyColumns(0,P,true); expected.sky.set(0,P);
            builder.fillOptics(0,2 * W,15,0,true); expected.fill(0,2 * W,15,0,true);
            builder.fillOptics(16,32,0,1,false); expected.fill(16,32,0,1,false);
            byte[] opacity = new byte[24], emission = new byte[24];
            for (int i = 0; i < opacity.length; i++) { opacity[i] = (byte)(i & 15); emission[i] = (byte)(15 - (i & 15)); }
            int target = at(47,15,15); // a valid contiguous slice crossing a horizontal row
            builder.copyOpticalRow(target,opacity,4,emission,7,16);
            System.arraycopy(opacity,4,expected.opacity,target,16);
            System.arraycopy(emission,7,expected.emission,target,16);
            builder.setStopRange(target,target + 16,true); expected.stops.set(target,target + 16);
            builder.setStopRange(target + 2,target + 11,false); expected.stops.clear(target + 2,target + 11);
            builder.orBlockedFaces(target,1); builder.orBlockedFaces(target,4 | 16);
            expected.blocked[target] = 1 | 4 | 16;

            // Final-content validation must not retain a stale high bound after overwrites.
            builder.setOptics(N - 1,15,15,true); expected.set(N - 1,15,15,true);
            builder.setOptics(N - 1,0,0,false); expected.set(N - 1,0,0,false);
            builder.setStopRange(N - 9,N,true); expected.stops.set(N - 9,N);
            builder.setStopRange(N - 9,N,false); expected.stops.clear(N - 9,N);
            builder.orBlockedFaces(at(20,20,20),4); expected.blocked[at(20,20,20)] = 4;
            builder.orBlockedFaces(N - 2,1 | 2 | 8 | 16 | 32);
            expected.blocked[N - 2] = 1 | 2 | 8 | 16 | 32;
            builder.setSkyColumns(47,49,false); expected.sky.clear(47,49);
            Snapshot actual = builder.consume();
            check(sourceEnd(actual) == at(20,20,20) + 1, "only final emission/opacity/stop/DOWN constrain source bound");
            compare(expected,actual,true);
        }
    }

    private static void inputAliasesAndLegacyConstructor() throws Exception {
        Fixture expected = new Fixture();
        byte[] opacity = new byte[16], emission = new byte[16];
        Arrays.fill(opacity,(byte)1); emission[3] = 14;
        int start = at(16,16,16);
        Snapshot captured;
        try (var builder = new Snapshot.Builder(W,H,D)) {
            builder.copyOpticalRow(start,opacity,0,emission,0,16);
            System.arraycopy(opacity,0,expected.opacity,start,16);
            System.arraycopy(emission,0,expected.emission,start,16);
            // Neither temporary tile input is owned by the builder, even before consume.
            Arrays.fill(opacity,(byte)-1); Arrays.fill(emission,(byte)31);
            captured = builder.consume();
        }
        Arrays.fill(opacity,(byte)0); Arrays.fill(emission,(byte)0);
        compare(expected,captured,true);

        Fixture legacy = new Fixture(); legacy.sky.set(0,P);
        legacy.set(at(20,20,20),1,15,true); legacy.blocked[at(20,20,20)] = 4;
        Snapshot publicCopy = legacy.snapshot();
        Fixture saved = legacy.copy();
        Arrays.fill(legacy.opacity,(byte)0); Arrays.fill(legacy.emission,(byte)0);
        Arrays.fill(legacy.blocked,(byte)0); legacy.stops.clear(); legacy.sky.clear();
        compare(saved,publicCopy,true);
    }

    private static void oneShotAndAbandonment() throws Exception {
        var builder = new Snapshot.Builder(W,H,D);
        builder.setOptics(0,0,1,false);
        Snapshot published = builder.consume();
        for (Runnable mutation : writes(builder)) rejected(IllegalStateException.class,mutation,"write after consume");
        rejected(IllegalStateException.class,builder::consume,"second consume");
        builder.close(); builder.close(); // owner close is deliberately idempotent
        check(GlobalLightField.solve(published).block(0,0,0) == 1, "closing consumed builder cannot mutate publication");

        var abandoned = new Snapshot.Builder(W,H,D);
        abandoned.setOptics(N - 1,15,15,true); abandoned.close();
        for (Runnable mutation : writes(abandoned)) rejected(IllegalStateException.class,mutation,"write after close");
        rejected(IllegalStateException.class,abandoned::consume,"closed capture cannot publish");
        compare(new Fixture(),new Snapshot.Builder(W,H,D).consume(),false);
    }

    private static void failedConsumptionSeals() throws Exception {
        for (int channel = 0; channel < 2; channel++) for (byte invalid : new byte[] {-1,16,63}) {
            var builder = new Snapshot.Builder(W,H,D);
            byte[] opacity = {0}, emission = {0};
            if (channel == 0) opacity[0] = invalid; else emission[0] = invalid;
            builder.copyOpticalRow(N - 1,opacity,0,emission,0,1);
            rejected(IllegalArgumentException.class,builder::consume,"copied bytes require final complete validation");
            for (Runnable mutation : writes(builder)) rejected(IllegalStateException.class,mutation,"failed consume permanently seals");
            rejected(IllegalStateException.class,builder::consume,"failed consume cannot be retried");
            builder.close();
        }
    }

    private static void invalidArgumentsAreAtomic() throws Exception {
        rejected(IllegalArgumentException.class,() -> new Snapshot.Builder(32,H,D),"width requires full halo");
        rejected(IllegalArgumentException.class,() -> new Snapshot.Builder(W,17,D),"height alignment");
        rejected(IllegalArgumentException.class,() -> new Snapshot.Builder(256,4096,256),"cell limit before allocation");
        try (var builder = new Snapshot.Builder(W,H,D)) {
            rejected(IllegalArgumentException.class,() -> builder.setOptics(0,256,0,false),"int must not wrap to valid byte");
            rejected(IllegalArgumentException.class,() -> builder.fillOptics(0,1,0,-1,false),"negative scalar");
            rejected(IllegalArgumentException.class,() -> builder.orBlockedFaces(0,64),"seventh face bit rejected");
            rejected(IndexOutOfBoundsException.class,() -> builder.setOptics(N,0,0,false),"cell endpoint excluded");
            rejected(IndexOutOfBoundsException.class,() -> builder.fillOptics(2,1,0,0,false),"reversed range");
            rejected(IndexOutOfBoundsException.class,() -> builder.setStopRange(0,N + 1,true),"stop range bound");
            rejected(IndexOutOfBoundsException.class,() -> builder.setSkyColumns(0,P + 1,true),"sky uses plane bound");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyOpticalRow(Integer.MAX_VALUE,
                    new byte[1],0,new byte[1],0,1),"destination overflow");
            rejected(IndexOutOfBoundsException.class,() -> builder.copyOpticalRow(0,
                    new byte[] {15},0,new byte[0],0,1),"both source ranges checked before first copy");
            rejected(NullPointerException.class,() -> builder.copyOpticalRow(0,
                    new byte[] {15},0,null,0,1),"both sources nonnull before either copy");
            // Empty end ranges are valid and must not accidentally extend either bitmap.
            builder.fillOptics(N,N,0,0,false); builder.setStopRange(N,N,true);
            builder.setSkyColumns(P,P,true);
            builder.copyOpticalRow(N,new byte[0],0,new byte[0],0,0);
            compare(new Fixture(),builder.consume(),false);
        }
    }

    private static void ownerThreadAndReaderTransfer() throws Exception {
        var builder = new Snapshot.Builder(W,H,D);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread stranger = new Thread(() -> {
            try {
                for (Runnable mutation : writes(builder)) expectForeignRejection(mutation);
                expectForeignRejection(builder::consume); expectForeignRejection(builder::close);
            } catch (Throwable failure) { failed.set(failure); }
        },"optical-builder-foreign-writer-test");
        stranger.start(); stranger.join(5_000);
        check(!stranger.isAlive() && failed.get() == null,"foreign thread rejected without closing owner: " + failed.get());
        builder.setOptics(0,0,15,false);
        Snapshot published = builder.consume();
        Thread reader = new Thread(() -> {
            try {
                var result = GlobalLightField.solve(published);
                if (result.block(0,0,0) != 15 || result.block(1,0,0) != 14)
                    throw new AssertionError("Immutable publication unavailable to worker thread");
            } catch (Throwable failure) { failed.set(failure); }
        },"optical-builder-published-reader-test");
        reader.start(); reader.join(5_000);
        check(!reader.isAlive() && failed.get() == null,"immutable snapshot transfers to numerical worker: " + failed.get());
    }

    private static void randomFieldsAndIndependentOracle() throws Exception {
        Random random = new Random(0x4255494c444552L);
        for (int mode = 0; mode < 3; mode++) {
            Fixture expected = new Fixture();
            try (var builder = new Snapshot.Builder(W,H,D)) {
                if (mode != 0) { builder.setSkyColumns(0,P,true); expected.sky.set(0,P); }
                if (mode == 2) for (int col = 0; col < P; col += 7) {
                    builder.setSkyColumns(col,col + 1,false); expected.sky.clear(col);
                }
                for (int z = 0; z < D; z++) {
                    int start = z * W;
                    builder.fillOptics(start,start + W,15,0,true); expected.fill(start,start + W,15,0,true);
                }
                for (int edit = 0; edit < 300; edit++) {
                    int index = random.nextInt(N), opacity = random.nextInt(4) == 0 ? 1 : random.nextBoolean() ? 0 : 15;
                    int emission = random.nextInt(8) == 0 ? 1 + random.nextInt(15) : 0;
                    boolean stop = random.nextInt(7) == 0;
                    int mask = random.nextInt(5) == 0 ? random.nextInt(64) : 0;
                    builder.setOptics(index,opacity,emission,stop); expected.set(index,opacity,emission,stop);
                    builder.orBlockedFaces(index,mask); expected.blocked[index] |= (byte)mask;
                }
                compare(expected,builder.consume(),true);
            }
        }
    }

    private static List<Runnable> writes(Snapshot.Builder builder) {
        return List.of(() -> builder.setOptics(0,0,0,false),
                () -> builder.fillOptics(0,1,0,0,false),
                () -> builder.copyOpticalRow(0,new byte[1],0,new byte[1],0,1),
                () -> builder.copyCheckedOpticalRow(0,new byte[1],0,new byte[1],0,1),
                () -> builder.copyOpticalTileRow(0,ZERO_TILE,0),
                () -> builder.copyBlockedPage(0,ZERO_FACES),
                () -> builder.orBlockedFaces(0,0),
                () -> builder.setStopRange(0,1,false),
                () -> builder.setSkyColumns(0,1,false));
    }
    private static void expectForeignRejection(Runnable action) {
        try { action.run(); throw new AssertionError("Foreign writer accepted"); }
        catch (IllegalStateException expected) { }
    }
    private static void rejected(Class<? extends Throwable> type,Runnable action,String message) {
        checks++;
        try { action.run(); throw new AssertionError(message + ": accepted"); }
        catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError(message + ": wrong exception",failure);
        }
    }
    private static void compare(Fixture expected,Snapshot actual,boolean solve) throws Exception {
        for (String channel : List.of("opacity","emission","blocked")) {
            byte[] reference = switch (channel) {
                case "opacity" -> expected.opacity;
                case "emission" -> expected.emission;
                default -> expected.blocked;
            };
            byte[] values = (byte[])field(actual,channel);
            int mismatch = Arrays.mismatch(reference,values); checks += N;
            if (mismatch >= 0) throw new AssertionError(channel + " differs at " + mismatch);
        }
        check(expected.stops.equals(field(actual,"stops")),"exact final stops");
        check(expected.sky.equals(field(actual,"skyColumns")),"exact final sky columns");
        check(sourceEnd(actual) == sourceEnd(expected.snapshot()),"identical final source bound to defensive-copy constructor");
        check(actual.estimatedWorkingBytes() == expected.snapshot().estimatedWorkingBytes(),"working budget unchanged");
        if (!solve) return;
        var result = GlobalLightField.solve(actual);
        var oracle = GeneralChunkLighting.solve(new GeneralChunkLighting.Snapshot(W,H,D,
                expected.opacity,expected.emission,expected.blocked,expected.stops,expected.sky));
        for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            if (result.block(x,y,z) != oracle.block(x,y,z) || result.sky(x,y,z) != oracle.sky(x,y,z))
                throw new AssertionError("Builder field differs from independent solver at " + x + "," + y + "," + z);
            lightValues += 2;
        }
    }
    private static Object field(Object target,String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static int sourceEnd(Snapshot snapshot) throws Exception { return (Integer)field(snapshot,"sourceScanEnd"); }
    private static int at(int x,int y,int z) { return x + W * (z + D * y); }
    private static void check(boolean condition,String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static final class Fixture {
        final byte[] opacity = new byte[N], emission = new byte[N], blocked = new byte[N];
        final BitSet stops = new BitSet(N), sky = new BitSet(P);
        void set(int index,int opacity,int emission,boolean stop) {
            this.opacity[index] = (byte)opacity; this.emission[index] = (byte)emission; stops.set(index,stop);
        }
        void fill(int from,int to,int opacity,int emission,boolean stop) {
            Arrays.fill(this.opacity,from,to,(byte)opacity); Arrays.fill(this.emission,from,to,(byte)emission);
            stops.set(from,to,stop);
        }
        Snapshot snapshot() { return new Snapshot(W,H,D,opacity,emission,blocked,stops,sky); }
        Fixture copy() {
            Fixture result = new Fixture();
            System.arraycopy(opacity,0,result.opacity,0,N); System.arraycopy(emission,0,result.emission,0,N);
            System.arraycopy(blocked,0,result.blocked,0,N); result.stops.or(stops); result.sky.or(sky); return result;
        }
    }
}
