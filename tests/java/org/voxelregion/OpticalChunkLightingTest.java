/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.ClearedChunkLighting.Face;

/** Independent synchronous relaxation for scalar attenuation, emitters and directed shape edges. */
public final class OpticalChunkLightingTest {
    private static long checked;
    private static final int[][] DIRECTION = {
        {-1,0,0}, {1,0,0}, {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}
    };
    private static final int[] OPPOSITE = {1,0,3,2,5,4};

    public static void main(String[] args) {
        if (!OpticalChunkLightingTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        knownFields(); legacyEquivalence(); randomFields(); validation();
        System.out.println("OpticalChunkLightingTest passed; independent layer values=" + checked);
    }

    private static void knownFields() {
        Fixture emitter = new Fixture(16);
        emitter.opacity[index(8,8,8)] = 15;
        emitter.emission[index(8,8,8)] = 15;
        var glowingOpaque = compare(emitter, true);
        assert glowingOpaque.block(8,8,8) == 15 && glowingOpaque.block(9,8,8) == 14;
        assert glowingOpaque.block(10,8,8) == 13;

        Fixture water = new Fixture(32); water.openSky();
        Arrays.fill(water.opacity, 20 * 256, 23 * 256, (byte)1);
        var wet = compare(water, false);
        assert wet.sky(8,23,8) == 15 && wet.sky(8,22,8) == 14;
        assert wet.sky(8,21,8) == 13 && wet.sky(8,20,8) == 12 && wet.sky(8,19,8) == 11;

        Fixture attenuated = tunnel();
        attenuated.emission[index(5,8,8)] = 15;
        attenuated.opacity[index(6,8,8)] = 4;
        var loss = compare(attenuated, true);
        assert loss.block(6,8,8) == 11 && loss.block(7,8,8) == 10;

        Fixture shape = tunnel();
        shape.emission[index(5,8,8)] = 15;
        shape.blocked[index(5,8,8)] = (byte)(1 << Face.EAST.ordinal());
        var blocked = compare(shape, true);
        assert blocked.block(4,8,8) == 14 && blocked.block(6,8,8) == 0;

        Fixture directed = tunnel();
        directed.emission[index(6,8,8)] = 15;
        directed.blocked[index(5,8,8)] = (byte)(1 << Face.EAST.ordinal());
        assert compare(directed, false).block(5,8,8) == 14 : "The opposite directed edge is independent";

        Fixture vertical = new Fixture(16); Arrays.fill(vertical.opacity, (byte)15);
        for (int y = 0; y < 16; y++) vertical.opacity[index(8,y,8)] = 0;
        int column = 8 + 16 * 8;
        vertical.direct.set(column); vertical.sky[Face.UP.ordinal()][column] = 15;
        // No explicit stop: the downward edge must stop direct sky at its lower endpoint.
        vertical.blocked[index(8,9,8)] = (byte)(1 << Face.DOWN.ordinal());
        var skyShape = compare(vertical, true);
        assert skyShape.sky(8,9,8) == 15 && skyShape.sky(8,8,8) == 0;

        Fixture directOnlyStop = new Fixture(16); directOnlyStop.openSky();
        directOnlyStop.stops.set(8 * 256, 9 * 256);
        var diffuse = compare(directOnlyStop, false);
        assert diffuse.sky(8,9,8) == 15 && diffuse.sky(8,8,8) == 14;
        assert diffuse.sky(8,7,8) == 13 : "Stopping direct sky does not forbid diffuse edges";

        Fixture boundary = tunnel();
        boundary.block[Face.WEST.ordinal()][8 + 16 * 8] = 15;
        boundary.opacity[index(0,8,8)] = 4;
        assert compare(boundary, false).block(0,8,8) == 11;
    }

    private static Fixture tunnel() {
        Fixture f = new Fixture(16); Arrays.fill(f.opacity, (byte)15);
        for (int x = 0; x < 16; x++) f.opacity[index(x,8,8)] = 0;
        return f;
    }

    private static void legacyEquivalence() {
        Random random = new Random(0x4c4547414359L);
        for (int trial = 0; trial < 8; trial++) {
            Fixture f = new Fixture(16); BitSet opaque = new BitSet();
            for (int i = 0; i < f.opacity.length; i++) if (random.nextInt(6) == 0) {
                f.opacity[i] = 15; opaque.set(i);
            }
            f.openSky();
            for (byte[] face : f.block) for (int i = 0; i < face.length; i++)
                if (random.nextInt(37) == 0) face[i] = (byte)random.nextInt(16);
            var old = ClearedChunkLighting.solve(new ClearedChunkLighting.Snapshot(
                    f.height, opaque, f.block, f.sky, f.open, f.direct));
            var general = ClearedChunkLighting.solve(f.snapshot());
            for (int i = 0; i < f.opacity.length; i++) {
                assert general.block(i & 15, i >>> 8, i >>> 4 & 15) == old.block(i & 15, i >>> 8, i >>> 4 & 15);
                assert general.sky(i & 15, i >>> 8, i >>> 4 & 15) == old.sky(i & 15, i >>> 8, i >>> 4 & 15);
                checked += 2;
            }
        }
    }

    private static void randomFields() {
        Random random = new Random(0x4f5054494353L);
        for (int trial = 0; trial < 24; trial++) {
            Fixture f = new Fixture(trial % 5 == 0 ? 32 : 16);
            for (int i = 0; i < f.opacity.length; i++) {
                if (random.nextInt(5) == 0) f.opacity[i] = (byte)random.nextInt(16);
                if (random.nextInt(83) == 0) f.emission[i] = (byte)random.nextInt(16);
                if (random.nextInt(7) == 0) f.blocked[i] = (byte)random.nextInt(64);
                if (random.nextInt(97) == 0) f.stops.set(i);
            }
            for (int face = 0; face < 6; face++) for (int i = 0; i < f.block[face].length; i++) {
                if (random.nextInt(17) == 0) f.block[face][i] = (byte)random.nextInt(16);
                if (random.nextInt(17) == 0) f.sky[face][i] = (byte)random.nextInt(16);
                if (random.nextBoolean()) f.open[face].clear(i);
            }
            for (int column = 0; column < 256; column++) if (random.nextInt(13) == 0) {
                f.direct.set(column); f.sky[Face.UP.ordinal()][column] = 15;
                f.open[Face.UP.ordinal()].set(column);
            }
            compare(f, true);
        }
    }

    private static ClearedChunkLighting.Result compare(Fixture f, boolean incremental) {
        int[][] expected = oracle(f);
        var snapshot = f.snapshot();
        var session = new ClearedChunkLighting.Session(snapshot);
        int calls = 0;
        while (!session.isComplete()) {
            int work = incremental ? 1 + (calls++ * 53 & 511) : 65536;
            var progress = session.step(work);
            assert progress.work() <= work && progress.queued() <= f.height * 256;
        }
        var actual = session.result();
        for (int i = 0; i < f.opacity.length; i++) {
            assert actual.block(i & 15, i >>> 8, i >>> 4 & 15) == expected[0][i] : "Block mismatch " + i;
            assert actual.sky(i & 15, i >>> 8, i >>> 4 & 15) == expected[1][i] : "Sky mismatch " + i;
            checked += 2;
        }
        for (int section = 0; section < f.height / 16; section++) {
            byte[] block = actual.blockSection(section), sky = actual.skySection(section);
            for (int cell = 0; cell < 4096; cell++) {
                assert ((block[cell >>> 1] >>> (4 * (cell & 1))) & 15) == expected[0][section * 4096 + cell];
                assert ((sky[cell >>> 1] >>> (4 * (cell & 1))) & 15) == expected[1][section * 4096 + cell];
            }
        }
        Arrays.fill(f.opacity, (byte)15); Arrays.fill(f.emission, (byte)0);
        Arrays.fill(f.blocked, (byte)63); f.stops.set(0, f.height * 256);
        var repeated = ClearedChunkLighting.solve(snapshot);
        for (int i = 0; i < f.opacity.length; i++) {
            assert repeated.block(i & 15, i >>> 8, i >>> 4 & 15) == expected[0][i];
            assert repeated.sky(i & 15, i >>> 8, i >>> 4 & 15) == expected[1][i];
        }
        return actual;
    }

    /** Bellman-Ford relaxation of incoming edges; production instead pushes an outgoing work queue. */
    private static int[][] oracle(Fixture f) {
        int cells = f.height * 256;
        int[][] result = {new int[cells], new int[cells]};
        for (int y = 0; y < f.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int target = index(x,y,z), loss = Math.max(1, f.opacity[target]);
            result[0][target] = f.emission[target];
            if (f.direct.get(x + 16 * z)) {
                boolean direct = true;
                for (int above = y; above < f.height; above++)
                    if (f.opacity[index(x,above,z)] > 0 || f.stops.get(index(x,above,z)) ||
                            above + 1 < f.height && (f.blocked[index(x,above + 1,z)] & 4) != 0) direct = false;
                if (direct) result[1][target] = 15;
            }
            for (int face = 0; face < 6; face++) {
                int[] d = DIRECTION[face];
                int xx = x + d[0], yy = y + d[1], zz = z + d[2];
                if (inside(xx,yy,zz,f.height)) continue;
                int offset = face < 2 ? z + 16 * y : face < 4 ? x + 16 * z : x + 16 * y;
                if (f.open[face].get(offset)) {
                    result[0][target] = Math.max(result[0][target], f.block[face][offset] - loss);
                    result[1][target] = Math.max(result[1][target], f.sky[face][offset] - loss);
                }
            }
        }
        for (int layer = 0; layer < 2; layer++) for (int iteration = 0; ; iteration++) {
            int[] old = result[layer], next = old.clone(); boolean changed = false;
            for (int y = 0; y < f.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int target = index(x,y,z), loss = Math.max(1,f.opacity[target]);
                for (int face = 0; face < 6; face++) {
                    int[] d = DIRECTION[face];
                    int xx = x + d[0], yy = y + d[1], zz = z + d[2];
                    if (!inside(xx,yy,zz,f.height)) continue;
                    int source = index(xx,yy,zz);
                    if ((f.blocked[source] & (1 << OPPOSITE[face])) != 0) continue;
                    next[target] = Math.max(next[target], old[source] - loss);
                }
                changed |= next[target] != old[target];
            }
            result[layer] = next;
            if (!changed) break;
            assert iteration < 15 : "Positive edge costs must converge within the light range";
        }
        return result;
    }

    private static int index(int x, int y, int z) { return x + 16 * z + 256 * y; }
    private static boolean inside(int x, int y, int z, int h) { return x >= 0 && x < 16 && z >= 0 && z < 16 && y >= 0 && y < h; }

    private static void validation() {
        Fixture f = new Fixture(16);
        f.opacity[0] = 16; rejects(f::snapshot); f.opacity[0] = 0;
        f.emission[0] = -1; rejects(f::snapshot); f.emission[0] = 0;
        f.blocked[0] = 64; rejects(f::snapshot); f.blocked[0] = 0;
        f.stops.set(4096); rejects(f::snapshot); f.stops.clear();
        rejects(() -> new ClearedChunkLighting.Snapshot(16, new byte[1], f.emission, null,
                f.stops, f.block, f.sky, f.open, f.direct));
        var noShape = new ClearedChunkLighting.Snapshot(16, f.opacity, f.emission, null,
                f.stops, f.block, f.sky, f.open, f.direct);
        assert new ClearedChunkLighting.Session(noShape).isComplete();
    }

    private static final class Fixture {
        final int height; final byte[] opacity, emission, blocked;
        final BitSet direct = new BitSet(), stops = new BitSet();
        final byte[][] block = new byte[6][], sky = new byte[6][];
        final BitSet[] open = new BitSet[6];
        Fixture(int height) {
            this.height = height; opacity = new byte[height * 256];
            emission = new byte[opacity.length]; blocked = new byte[opacity.length];
            for (Face face : Face.values()) {
                int f = face.ordinal(), size = ClearedChunkLighting.faceSize(height, face);
                block[f] = new byte[size]; sky[f] = new byte[size]; open[f] = new BitSet(size); open[f].set(0,size);
            }
        }
        void openSky() { direct.set(0,256); Arrays.fill(sky[Face.UP.ordinal()], (byte)15); }
        ClearedChunkLighting.Snapshot snapshot() {
            return new ClearedChunkLighting.Snapshot(height, opacity, emission, blocked, stops, block, sky, open, direct);
        }
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private OpticalChunkLightingTest() { }
}
