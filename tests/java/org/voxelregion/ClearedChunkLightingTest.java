/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.ClearedChunkLighting.Face;

/** Independent synchronous relaxation oracle, separate from the production queue. */
public final class ClearedChunkLightingTest {
    private static long checked;
    public static void main(String[] args) {
        if (!ClearedChunkLightingTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        simpleFields(); croppedFields(); randomFields(); validation();
        System.out.println("ClearedChunkLightingTest passed; independent layer values=" + checked);
    }
    private static void croppedFields() {
        Random random = new Random(0x424544524f434bL);
        for (int height : new int[]{32, 96, 384}) {
            Fixture fixture = new Fixture(height); fixture.openSky();
            fixture.opaque.set(0, 256);
            for (int index = 256; index < 6 * 256; index++) if (random.nextBoolean()) fixture.opaque.set(index);
            int[][] expected = oracle(fixture);
            var result = ClearedChunkLighting.solve(fixture.snapshot());
            var independentColumn = org.voxelregion.lighting.OpenColumnLighting.solve(height, fixture.opaque, true);
            assert result.height() == height && result.estimatedBytes() <= 4096;
            for (int y = 0; y < height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                assert result.sky(x, y, z) == expected[1][x | z << 4 | y << 8];
                assert independentColumn.sky(x, y, z) == expected[1][x | z << 4 | y << 8];
                assert result.block(x, y, z) == 0;
                checked += 2;
            }
            for (int section = 1; section < height / 16; section++) {
                assert result.uniformSky(section) == 15 && result.uniformBlock(section) == 0;
                for (byte value : result.skySection(section)) assert value == (byte)255;
            }
        }
    }
    private static void simpleFields() {
        Fixture open = new Fixture(384); open.openSky();
        var session = new ClearedChunkLighting.Session(open.snapshot());
        assert session.isComplete() && session.result().estimatedBytes() == 64;
        assert session.result().sky(8, 200, 8) == 15 && session.result().block(8, 200, 8) == 0;
        for (int section = 0; section < 24; section++) assert session.result().uniformSky(section) == 15;

        Fixture floor = new Fixture(32); floor.openSky(); floor.opaque.set(0, 256);
        var floorResult = compare(floor, true);
        assert floorResult.sky(8, 0, 8) == 0 && floorResult.sky(8, 1, 8) == 15;
        Fixture roof = new Fixture(16); roof.openSky(); roof.opaque.set(12 * 256, 13 * 256);
        var roofResult = compare(roof, false);
        assert roofResult.sky(8, 11, 8) == 0 && roofResult.sky(8, 13, 8) == 15;

        Fixture torch = new Fixture(16); torch.block[Face.WEST.ordinal()][8 + 16 * 8] = 15;
        var torchResult = compare(torch, true);
        for (int x = 0; x < 16; x++) assert torchResult.block(x, 8, 8) == Math.max(0, 14 - x);
        assert torchResult.block(0, 9, 8) == 13;
        Fixture diffuse = new Fixture(16); Arrays.fill(diffuse.sky[Face.UP.ordinal()], (byte) 15);
        var diffuseResult = compare(diffuse, false);
        assert diffuseResult.sky(8, 15, 8) == 14 && diffuseResult.sky(8, 2, 8) == 1;

        Fixture closed = new Fixture(16);
        for (int face = 0; face < 6; face++) { Arrays.fill(closed.block[face], (byte) 15); closed.open[face].clear(); }
        assert new ClearedChunkLighting.Session(closed.snapshot()).isComplete();
        assert compare(closed, false).block(0, 0, 0) == 0;
    }
    private static void randomFields() {
        Random random = new Random(0x4c49474854L);
        for (int trial = 0; trial < 35; trial++) {
            Fixture fixture = new Fixture(trial % 4 == 0 ? 32 : 16);
            for (int index = 0; index < fixture.height * 256; index++) if (random.nextInt(7) == 0) fixture.opaque.set(index);
            for (Face face : Face.values()) {
                int f = face.ordinal();
                for (int i = 0; i < fixture.block[f].length; i++) {
                    if (random.nextInt(13) == 0) fixture.block[f][i] = (byte) random.nextInt(16);
                    if (random.nextInt(13) == 0) fixture.sky[f][i] = (byte) random.nextInt(16);
                    if (random.nextBoolean()) fixture.open[f].clear(i);
                }
            }
            for (int column = 0; column < 256; column++) if (random.nextInt(11) == 0) {
                fixture.direct.set(column); fixture.sky[Face.UP.ordinal()][column] = 15;
                fixture.open[Face.UP.ordinal()].set(column);
            }
            compare(fixture, true);
        }
    }
    private static ClearedChunkLighting.Result compare(Fixture fixture, boolean incremental) {
        int[][] expected = oracle(fixture);
        var snapshot = fixture.snapshot();
        var session = new ClearedChunkLighting.Session(snapshot);
        int calls = 0;
        while (!session.isComplete()) {
            int maximum = incremental ? 1 + (calls++ * 37 & 255) : 65_536;
            var progress = session.step(maximum);
            assert progress.work() <= maximum && progress.queued() <= fixture.height * 256;
        }
        var actual = session.result();
        for (int y = 0; y < fixture.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int index = x | z << 4 | y << 8;
            assert actual.block(x, y, z) == expected[0][index] : "Block oracle mismatch at " + x + "," + y + "," + z;
            assert actual.sky(x, y, z) == expected[1][index] : "Sky oracle mismatch at " + x + "," + y + "," + z;
            checked += 2;
        }
        for (int section = 0; section < fixture.height / 16; section++) {
            byte[] packed = actual.skySection(section);
            for (int i = 0; i < 4096; i++) assert ((packed[i / 2] >> (4 * (i % 2))) & 15) == expected[1][section * 4096 + i];
            Arrays.fill(packed, (byte) 0x7f);
            assert actual.sky(0, section * 16, 0) == expected[1][section * 4096];
        }
        // Snapshot arrays and masks were defensively detached from the fixture.
        for (byte[] face : fixture.block) Arrays.fill(face, (byte) 0);
        fixture.opaque.clear(); fixture.direct.clear();
        var second = ClearedChunkLighting.solve(snapshot);
        for (int i = 0; i < fixture.height * 256; i++) assert second.block(i & 15, i >>> 8, i >>> 4 & 15) == expected[0][i];
        return actual;
    }
    private static int[][] oracle(Fixture f) {
        int[][] seeds = { new int[f.height * 256], new int[f.height * 256] };
        for (int y = 0; y < f.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int cell = x + 16 * z + 256 * y;
            if (f.opaque.get(cell)) continue;
            if (f.direct.get(x + 16 * z)) {
                boolean exposed = true;
                for (int above = y + 1; above < f.height; above++) if (f.opaque.get(x + 16 * z + 256 * above)) exposed = false;
                if (exposed) seeds[1][cell] = 15;
            }
            for (Face face : Face.values()) {
                boolean touches = switch (face) {
                    case WEST -> x == 0; case EAST -> x == 15; case NORTH -> z == 0;
                    case SOUTH -> z == 15; case DOWN -> y == 0; case UP -> y == f.height - 1;
                };
                if (!touches) continue;
                int offset = switch (face) { case WEST, EAST -> z + 16 * y; case NORTH, SOUTH -> x + 16 * y; default -> x + 16 * z; };
                if (f.open[face.ordinal()].get(offset)) {
                    seeds[0][cell] = Math.max(seeds[0][cell], f.block[face.ordinal()][offset] - 1);
                    seeds[1][cell] = Math.max(seeds[1][cell], f.sky[face.ordinal()][offset] - 1);
                }
            }
        }
        for (int layer = 0; layer < 2; layer++) {
            int[] current = seeds[layer];
            for (int iteration = 0; iteration < 16; iteration++) {
                int[] next = current.clone(); boolean changed = false;
                for (int y = 0; y < f.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int cell = x + 16 * z + 256 * y;
                    if (f.opaque.get(cell)) continue;
                    int maximum = current[cell];
                    for (int[] direction : DIRECTIONS) {
                        int xx = x + direction[0], yy = y + direction[1], zz = z + direction[2];
                        if (xx >= 0 && xx < 16 && yy >= 0 && yy < f.height && zz >= 0 && zz < 16)
                            maximum = Math.max(maximum, current[xx + 16 * zz + 256 * yy] - 1);
                    }
                    next[cell] = maximum; changed |= maximum != current[cell];
                }
                current = next; if (!changed) break;
                assert iteration < 15 : "Oracle failed to converge within light range";
            }
            seeds[layer] = current;
        }
        return seeds;
    }
    private static final int[][] DIRECTIONS = { {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1} };
    private static void validation() {
        Fixture f = new Fixture(16);
        rejects(() -> new ClearedChunkLighting.Snapshot(17, f.opaque, f.block, f.sky, f.open, f.direct));
        rejects(() -> new ClearedChunkLighting.Session(f.snapshot(), 1));
        f.direct.set(0); rejects(f::snapshot); f.direct.clear();
        f.block[0][0] = 16; rejects(f::snapshot); f.block[0][0] = 0;
        f.opaque.set(4096); rejects(f::snapshot);
    }
    private static final class Fixture {
        final int height; final BitSet opaque = new BitSet(), direct = new BitSet();
        final byte[][] block = new byte[6][], sky = new byte[6][];
        final BitSet[] open = new BitSet[6];
        Fixture(int height) {
            this.height = height;
            for (Face face : Face.values()) {
                int f = face.ordinal(), size = ClearedChunkLighting.faceSize(height, face);
                block[f] = new byte[size]; sky[f] = new byte[size]; open[f] = new BitSet(size); open[f].set(0, size);
            }
        }
        void openSky() { direct.set(0, 256); Arrays.fill(sky[Face.UP.ordinal()], (byte) 15); }
        ClearedChunkLighting.Snapshot snapshot() { return new ClearedChunkLighting.Snapshot(height, opaque, block, sky, open, direct); }
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private ClearedChunkLightingTest() { }
}
