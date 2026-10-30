/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import org.voxelregion.lighting.GeneralChunkLighting;

/** Incoming-edge relaxation and expanded-domain witnesses for the 16-block optical halo. */
public final class GeneralChunkLightingTest {
    private static long checked;
    private static final int[][] DIRECTION = {
        {-1,0,0}, {1,0,0}, {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}
    };
    private static final int[] OPPOSITE = {1,0,3,2,5,4};

    public static void main(String[] args) {
        if (!GeneralChunkLightingTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        knownFields(); randomFields(); haloIsolation(); frontierWork(); validation();
        System.out.println("GeneralChunkLightingTest passed; independent/cropped values=" + checked);
    }

    private static void knownFields() {
        Fixture open = new Fixture(48,384,48); open.direct.set(0,open.plane);
        var uniform = new GeneralChunkLighting.Session(open.snapshot());
        assert uniform.isComplete() && uniform.result().estimatedBytes() == 80;
        var column = uniform.result().cropColumn(1,1);
        assert column.sky(8,0,8) == 15 && column.sky(8,383,8) == 15;
        assert column.uniformSky(0) == 15 && column.uniformBlock(0) == 0;

        Fixture f = new Fixture(64,16,48);
        f.opacity[f.index(31,8,24)] = 15; f.emission[f.index(31,8,24)] = 15;
        var result = compare(f);
        assert result.block(32,8,24) == 14 : "An emitter must cross a chunk edge";
        assert result.cropColumn(2,1).block(0,8,8) == 14;
        assert result.cropColumn(1,1).block(15,8,8) == 15;

        Fixture water = new Fixture(48,16,48); water.direct.set(0,water.plane);
        Arrays.fill(water.opacity, 8 * water.plane, 10 * water.plane, (byte)1);
        var wet = compare(water);
        assert wet.sky(24,10,24) == 15 && wet.sky(24,9,24) == 14;
        assert wet.sky(24,8,24) == 13 && wet.sky(24,7,24) == 12;

        Fixture vertical = new Fixture(48,16,48); Arrays.fill(vertical.opacity,(byte)15);
        for (int y = 0; y < 16; y++) vertical.opacity[vertical.index(24,y,24)] = 0;
        vertical.direct.set(24 + 48 * 24);
        // No explicit stop: the blocked downward edge proves the direct-sky boundary.
        vertical.blocked[vertical.index(24,9,24)] = 4;
        var shape = compare(vertical);
        assert shape.sky(24,9,24) == 15 && shape.sky(24,8,24) == 0;
    }

    private static void randomFields() {
        Random random = new Random(0x47454e4552414cL);
        for (int trial = 0; trial < 8; trial++) {
            Fixture f = new Fixture(trial % 2 == 0 ? 48 : 64,16,48);
            for (int i = 0; i < f.opacity.length; i++) {
                if (random.nextInt(7) == 0) f.opacity[i] = (byte)random.nextInt(16);
                if (random.nextInt(373) == 0) f.emission[i] = (byte)random.nextInt(16);
                if (random.nextInt(9) == 0) f.blocked[i] = (byte)random.nextInt(64);
                if (random.nextInt(179) == 0) f.stops.set(i);
            }
            for (int column = 0; column < f.plane; column++)
                if (random.nextInt(13) == 0) f.direct.set(column);
            compare(f);
        }
    }

    private static GeneralChunkLighting.Result compare(Fixture f) {
        int[][] expected = oracle(f);
        var snapshot = f.snapshot();
        var session = new GeneralChunkLighting.Session(snapshot);
        int calls = 0;
        while (!session.isComplete()) {
            int budget = 1 + (calls++ * 67 & 4095);
            var progress = session.step(budget);
            assert progress.work() <= budget && progress.queued() <= f.opacity.length;
        }
        var actual = session.result();
        var originalQueue = new GeneralChunkLighting.Session(snapshot, GeneralChunkLighting.DEFAULT_WORKING_BYTES, false);
        while (!originalQueue.isComplete()) originalQueue.step(65536);
        var reference = originalQueue.result();
        for (int y = 0; y < f.height; y++) for (int z = 0; z < f.depth; z++) for (int x = 0; x < f.width; x++) {
            int index = f.index(x,y,z);
            assert actual.sky(x,y,z) == expected[1][index] : "Sky mismatch " + index;
            assert actual.block(x,y,z) == expected[0][index] : "Block mismatch " + index;
            assert reference.sky(x,y,z) == actual.sky(x,y,z) : "Frontier/full queue sky mismatch " + index;
            assert reference.block(x,y,z) == actual.block(x,y,z) : "Frontier/full queue block mismatch " + index;
            checked += 2;
        }
        for (int cz = 1; cz < f.depth / 16 - 1; cz++) for (int cx = 1; cx < f.width / 16 - 1; cx++) {
            var column = actual.cropColumn(cx,cz);
            for (int y = 0; y < f.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                assert column.block(x,y,z) == expected[0][f.index(cx * 16 + x,y,cz * 16 + z)];
                assert column.sky(x,y,z) == expected[1][f.index(cx * 16 + x,y,cz * 16 + z)];
                checked += 2;
            }
            byte[] packed = column.skySection(0); Arrays.fill(packed,(byte)0);
            assert column.sky(0,0,0) == expected[1][f.index(cx * 16,0,cz * 16)];
        }
        Arrays.fill(f.emission,(byte)0); Arrays.fill(f.opacity,(byte)15);
        f.direct.clear(); f.stops.set(0,f.opacity.length); Arrays.fill(f.blocked,(byte)63);
        var again = GeneralChunkLighting.solve(snapshot);
        for (int y = 0; y < f.height; y++)
            assert again.block(20,y,20) == expected[0][f.index(20,y,20)];
        return actual;
    }

    private static void frontierWork() {
        Fixture f = new Fixture(80,96,80); f.direct.set(0,f.plane);
        Arrays.fill(f.opacity,0,5 * f.plane,(byte)15);
        var snapshot = f.snapshot();
        var frontier = new GeneralChunkLighting.Session(snapshot);
        var original = new GeneralChunkLighting.Session(snapshot,GeneralChunkLighting.DEFAULT_WORKING_BYTES,false);
        long a = 0, b = 0;
        while (!frontier.isComplete()) a = frontier.step(65536).totalWork();
        while (!original.isComplete()) b = original.step(65536).totalWork();
        assert a < b * 0.8 : "The open sky volume should not enter the frontier queue: " + a + " / " + b;
        for (int y = 0; y < f.height; y++) for (int z = 16; z < 64; z++) for (int x = 16; x < 64; x++) {
            assert frontier.result().sky(x,y,z) == original.result().sky(x,y,z);
            assert frontier.result().sky(x,y,z) == (y < 5 ? 0 : 15);
            checked += 2;
        }
        System.out.println("General sky frontier work=" + a + ", full-source queue work=" + b);
    }

    private static void haloIsolation() {
        Random random = new Random(0x48414c4fL);
        Fixture inner = new Fixture(48,16,48), expanded = new Fixture(80,16,80);
        for (int i = 0; i < inner.opacity.length; i++) {
            if (random.nextInt(11) == 0) inner.opacity[i] = (byte)random.nextInt(16);
            if (random.nextInt(233) == 0) inner.emission[i] = (byte)random.nextInt(16);
            if (random.nextInt(17) == 0) inner.blocked[i] = (byte)random.nextInt(64);
        }
        for (int column = 0; column < inner.plane; column++) if (random.nextBoolean()) inner.direct.set(column);
        // An adversarially bright exterior may not change the protected center result.
        Arrays.fill(expanded.emission,(byte)15); expanded.direct.set(0,expanded.plane);
        for (int z = 0; z < 48; z++) for (int x = 0; x < 48; x++) {
            expanded.direct.set(x + 16 + expanded.width * (z + 16), inner.direct.get(x + 48 * z));
            for (int y = 0; y < 16; y++) {
                int a = inner.index(x,y,z), b = expanded.index(x + 16,y,z + 16);
                expanded.opacity[b] = inner.opacity[a]; expanded.emission[b] = inner.emission[a];
                expanded.blocked[b] = inner.blocked[a]; expanded.stops.set(b,inner.stops.get(a));
            }
        }
        var a = GeneralChunkLighting.solve(inner.snapshot()).cropColumn(1,1);
        var b = GeneralChunkLighting.solve(expanded.snapshot()).cropColumn(2,2);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assert a.block(x,y,z) == b.block(x,y,z) : "Exterior emitter crossed the full halo";
            assert a.sky(x,y,z) == b.sky(x,y,z) : "Exterior sky crossed the full halo";
            checked += 2;
        }
    }

    private static int[][] oracle(Fixture f) {
        int[][] field = {new int[f.opacity.length],new int[f.opacity.length]};
        for (int z = 0; z < f.depth; z++) for (int x = 0; x < f.width; x++) {
            boolean direct = f.direct.get(x + f.width * z);
            for (int y = f.height - 1; y >= 0; y--) {
                int target = f.index(x,y,z);
                if (f.opacity[target] > 0 || f.stops.get(target) ||
                        y + 1 < f.height && (f.blocked[f.index(x,y + 1,z)] & 4) != 0) direct = false;
                if (direct) field[1][target] = 15;
                field[0][target] = f.emission[target];
            }
        }
        for (int layer = 0; layer < 2; layer++) for (int iteration = 0; ; iteration++) {
            int[] old = field[layer], next = old.clone(); boolean changed = false;
            for (int y = 0; y < f.height; y++) for (int z = 0; z < f.depth; z++) for (int x = 0; x < f.width; x++) {
                int target = f.index(x,y,z), loss = Math.max(1,f.opacity[target]);
                for (int face = 0; face < 6; face++) {
                    int[] d = DIRECTION[face];
                    int xx = x + d[0], yy = y + d[1], zz = z + d[2];
                    if (xx < 0 || xx >= f.width || yy < 0 || yy >= f.height || zz < 0 || zz >= f.depth) continue;
                    int source = f.index(xx,yy,zz);
                    if ((f.blocked[source] & (1 << OPPOSITE[face])) != 0) continue;
                    next[target] = Math.max(next[target],old[source] - loss);
                }
                changed |= old[target] != next[target];
            }
            field[layer] = next;
            if (!changed) break;
            assert iteration < 15 : "Positive edge costs must converge within the light range";
        }
        return field;
    }

    private static void validation() {
        Fixture f = new Fixture(48,16,48);
        rejects(() -> new GeneralChunkLighting.Session(f.snapshot(),1));
        f.emission[0] = 16; rejects(f::snapshot); f.emission[0] = 0;
        f.opacity[0] = -1; rejects(f::snapshot); f.opacity[0] = 0;
        f.blocked[0] = 64; rejects(f::snapshot); f.blocked[0] = 0;
        f.stops.set(f.opacity.length); rejects(f::snapshot); f.stops.clear();
        f.direct.set(f.plane); rejects(f::snapshot); f.direct.clear();
        var result = GeneralChunkLighting.solve(f.snapshot());
        rejects(() -> result.cropColumn(0,1)); rejects(() -> result.cropColumn(1,2));
        rejects(() -> new GeneralChunkLighting.Snapshot(32,16,48,f.opacity,f.emission,null,f.stops,f.direct));
        rejects(() -> new GeneralChunkLighting.Snapshot(256,4096,256,new byte[0],new byte[0],null,f.stops,f.direct));
    }

    private static final class Fixture {
        final int width, height, depth, plane;
        final byte[] opacity, emission, blocked;
        final BitSet stops = new BitSet(), direct = new BitSet();
        Fixture(int width, int height, int depth) {
            this.width = width; this.height = height; this.depth = depth; plane = width * depth;
            opacity = new byte[plane * height]; emission = new byte[opacity.length]; blocked = new byte[opacity.length];
        }
        int index(int x, int y, int z) { return x + width * (z + depth * y); }
        GeneralChunkLighting.Snapshot snapshot() {
            return new GeneralChunkLighting.Snapshot(width,height,depth,opacity,emission,blocked,stops,direct);
        }
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private GeneralChunkLightingTest() { }
}
