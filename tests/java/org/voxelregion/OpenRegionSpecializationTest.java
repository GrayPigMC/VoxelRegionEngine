/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.GlobalLightField;
import org.voxelregion.lighting.OpenRegionLighting;

/**
 * Pure-kernel gates for selecting the open/bedrock specialization inside the general provider.
 * Compares complete interior crops against the general field, plus analytical outside-source
 * examples which deliberately disagree in the discarded halo. This does not prove host palette
 * eligibility, identity/revision validation, missing-chunk handling, or publication ordering.
 */
public final class OpenRegionSpecializationTest {
    private static long checks;

    public static void main(String[] args) {
        uniformExtremes();
        rectangularFloorsAndShelves();
        retainedBandEdges();
        externalInfluenceStopsBeforeInterior(false);
        externalInfluenceStopsBeforeInterior(true);
        System.out.println("OpenRegionSpecializationTest passed; checks=" + checks);
    }

    private static void uniformExtremes() {
        for (boolean sky : new boolean[] { false, true }) {
            Domain air = new Domain(3, 3, 16);
            var result = compare(air, sky);
            var center = result.get(1 + air.side);
            check(center.uniformBlock(0) == 0 && center.uniformSky(0) == (sky ? 15 : 0), "all-air field");

            Domain solid = new Domain(3, 3, 16);
            for (BitSet mask : solid.columns.values()) mask.set(0, 16 * 256);
            result = compare(solid, sky);
            center = result.get(1 + solid.side);
            check(center.uniformBlock(0) == 0 && center.uniformSky(0) == 0, "opaque at full world height");
        }
    }

    private static void rectangularFloorsAndShelves() {
        for (int[] dimensions : new int[][] { {4, 3}, {3, 4} }) {
            Domain domain = new Domain(dimensions[0], dimensions[1], 48);
            for (int z = 0; z < domain.depth; z++) for (int x = 0; x < domain.width; x++) {
                // Deterministic terrain derived from negative world coordinates, not square slot order.
                int floor = Math.floorMod((x - 137) * 3 + (z - 211) * 7, 7);
                for (int y = 0; y <= floor; y++) domain.opaque(x, y, z);
                if (x >= 12 && x < 37 && z >= 11 && z < 34 && (x + z) % 7 != 0)
                    domain.opaque(x, 20, z);
                if (x >= 29 && x < 35 && z >= 22 && z < 28) domain.opaque(x, 45, z);
            }
            for (boolean sky : new boolean[] { false, true }) compare(domain, sky);
        }
    }

    private static void retainedBandEdges() {
        for (int shelfY : new int[] { 0, 15, 16, 31, 47 }) {
            Domain domain = new Domain(3, 3, 48);
            for (int z = 0; z < domain.depth; z++) for (int x = 0; x < domain.width; x++)
                domain.opaque(x, shelfY, z);
            var result = compare(domain, true).get(1 + domain.side);
            for (int y = 0; y < domain.height; y++)
                check(result.sky(8, y, 8) == (y > shelfY ? 15 : 0), "full shelf exact vertical boundary at " + shelfY);
            for (int section = (shelfY + 16) / 16; section < domain.height / 16; section++)
                check(result.uniformSky(section) == 15, "implicit upper sections retain sky15");
        }
    }

    private static void externalInfluenceStopsBeforeInterior(boolean sky) {
        Domain sample = new Domain(3, 3, 48);
        Domain widerWorld = new Domain(5, 5, 48);
        for (int z = 0; z < sample.depth; z++) for (int x = 0; x < sample.width; x++) {
            sample.opaque(x, 0, z);
            widerWorld.opaque(x + 16, 0, z + 16);
            // With sky enabled, only the world outside the sample provides light under the roof.
            if (sky) { sample.opaque(x, 20, z); widerWorld.opaque(x + 16, 20, z + 16); }
        }
        byte[] emission = new byte[widerWorld.width * widerWorld.depth * widerWorld.height];
        int emitterX = 15, emitterY = 19, emitterZ = 40;
        emission[widerWorld.index(emitterX, emitterY, emitterZ)] = 15;
        var general = GlobalLightField.solve(widerWorld.snapshot(sky, emission));
        var open = OpenRegionLighting.solve(sample.height, sample.side, sample.columns, sky);
        var center = open.get(1 + sample.side);
        for (int y = 0; y < sample.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int worldX = x + 32, worldZ = z + 32;
            check(center.block(x, y, z) == general.block(worldX, y, worldZ), "outside emitter cannot reach interior");
            check(center.sky(x, y, z) == general.sky(worldX, y, worldZ), "outside sky cannot reach interior shadow");
        }
        check(general.block(16, 19, 40) == 14, "external emitter actually illuminates discarded west halo");
        check(open.get(sample.side).block(0, 19, 8) == 0, "open kernel must not be used for halo publication");
        check(general.block(32, 19, 40) == 0, "seventeen steps from exterior source to first published cell");
        if (sky) {
            check(general.sky(16, 19, 40) == 14, "external direct sky illuminates discarded halo");
            check(open.get(sample.side).sky(0, 19, 8) == 0, "closed sample halo is not exact world skylight");
        }
    }

    private static Map<Integer, ClearedChunkLighting.Result> compare(Domain domain, boolean sky) {
        var general = GlobalLightField.solve(domain.snapshot(sky, null));
        var open = OpenRegionLighting.solve(domain.height, domain.side, domain.columns, sky);
        check(open.keySet().equals(domain.columns.keySet()), "non-square slot membership preserved");
        for (int cz = 1; cz < domain.columnsZ - 1; cz++) for (int cx = 1; cx < domain.columnsX - 1; cx++) {
            var specialized = open.get(cx + cz * domain.side);
            var cropped = general.cropColumn(cx, cz);
            for (int y = 0; y < domain.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                check(specialized.block(x, y, z) == cropped.block(x, y, z), "interior block parity");
                check(specialized.sky(x, y, z) == cropped.sky(x, y, z), "interior sky parity");
            }
            for (int section = 0; section < domain.height / 16; section++) {
                check(specialized.uniformBlock(section) == cropped.uniformBlock(section), "uniform block metadata");
                check(specialized.uniformSky(section) == cropped.uniformSky(section), "uniform sky metadata");
            }
        }
        return open;
    }

    private static final class Domain {
        final int columnsX, columnsZ, width, depth, height, side;
        final Map<Integer, BitSet> columns = new LinkedHashMap<>();
        Domain(int columnsX, int columnsZ, int height) {
            this.columnsX = columnsX; this.columnsZ = columnsZ;
            this.width = columnsX * 16; this.depth = columnsZ * 16;
            this.height = height; this.side = Math.max(columnsX, columnsZ);
            for (int z = 0; z < columnsZ; z++) for (int x = 0; x < columnsX; x++)
                columns.put(x + z * side, new BitSet());
        }
        void opaque(int x, int y, int z) { columns.get(x / 16 + z / 16 * side).set((x & 15) + (z & 15) * 16 + y * 256); }
        int index(int x, int y, int z) { return x + width * (z + depth * y); }
        GlobalLightField.Snapshot snapshot(boolean sky, byte[] emission) {
            int cells = width * depth * height;
            byte[] opacity = new byte[cells]; BitSet stops = new BitSet(cells), direct = new BitSet(width * depth);
            for (var entry : columns.entrySet()) {
                int bx = entry.getKey() % side * 16, bz = entry.getKey() / side * 16;
                for (int cell = entry.getValue().nextSetBit(0); cell >= 0; cell = entry.getValue().nextSetBit(cell + 1)) {
                    int at = index(bx + (cell & 15), cell >>> 8, bz + ((cell >>> 4) & 15));
                    opacity[at] = 15; stops.set(at);
                }
            }
            if (sky) direct.set(0, width * depth);
            return new GlobalLightField.Snapshot(width, height, depth, opacity,
                    emission == null ? new byte[cells] : emission, null, stops, direct);
        }
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
