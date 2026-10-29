/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.voxelregion.lighting.OpenRegionLighting;

/** Dense synchronous relaxation, deliberately independent of the compact production queue. */
public final class OpenRegionLightingTest {
    public static void main(String[] args) {
        if (!OpenRegionLightingTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        Random random = new Random(0x524547494f4eL);
        long checked = 0;
        for (int trial = 0; trial < 10; trial++) {
            Map<Integer, BitSet> columns = new HashMap<>();
            for (int key = 0; key < 16; key++) {
                if (trial == 0 && key != 0 && key != 15 || trial > 2 && random.nextBoolean()) continue;
                BitSet mask = new BitSet(); mask.set(0, 256);
                for (int i = 256; i < 32 * 256; i++) {
                    int y = i >>> 8;
                    if (y < 6 && random.nextBoolean() || trial % 3 == 0 && y == 20 && (i & 15) < 12) mask.set(i);
                }
                columns.put(key, mask);
            }
            int[] expected = oracle(columns, 32);
            var result = OpenRegionLighting.solve(384, columns, true);
            Map<Integer, BitSet> shifted = new HashMap<>();
            columns.forEach((key, mask) -> shifted.put((key % 4 + 11) + (key / 4 + 7) * 16, mask));
            var large = OpenRegionLighting.solve(384, 16, shifted, true);
            var dark = OpenRegionLighting.solve(384, columns, false);
            assert result.keySet().equals(columns.keySet());
            for (int key : columns.keySet()) {
                int bx = (key & 3) * 16, bz = (key >>> 2) * 16;
                var image = result.get(key);
                for (int y = 0; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int value = Math.max(0, expected[bx + x + (bz + z) * 64 + y * 4096]);
                    assert image.sky(x, y, z) == value : "Mismatch trial=" + trial + " key=" + key + " at=" + x + "," + y + "," + z;
                    assert large.get((key % 4 + 11) + (key / 4 + 7) * 16).sky(x, y, z) == value;
                    assert image.block(x, y, z) == 0 && dark.get(key).sky(x, y, z) == 0;
                    checked++;
                }
                for (int section = 2; section < 24; section++) assert image.uniformSky(section) == 15;
            }
        }
        System.out.println("OpenRegionLightingTest passed; independent cells=" + checked);
    }
    private static int[] oracle(Map<Integer, BitSet> columns, int height) {
        int[] current = new int[height * 4096]; Arrays.fill(current, -1);
        for (var entry : columns.entrySet()) {
            int bx = (entry.getKey() & 3) * 16, bz = (entry.getKey() >>> 2) * 16;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                boolean direct = true;
                for (int y = height - 1; y >= 0; y--) {
                    int index = bx + x + (bz + z) * 64 + y * 4096;
                    if (entry.getValue().get(x + z * 16 + y * 256)) direct = false;
                    else current[index] = direct ? 15 : 0;
                }
            }
        }
        for (int iteration = 0; iteration < 16; iteration++) {
            int[] next = current.clone(); boolean changed = false;
            for (int y = 0; y < height; y++) for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
                int index = x + z * 64 + y * 4096;
                if (current[index] < 0) continue;
                int value = current[index];
                if (x > 0) value = Math.max(value, current[index - 1] - 1);
                if (x < 63) value = Math.max(value, current[index + 1] - 1);
                if (z > 0) value = Math.max(value, current[index - 64] - 1);
                if (z < 63) value = Math.max(value, current[index + 64] - 1);
                if (y > 0) value = Math.max(value, current[index - 4096] - 1);
                if (y + 1 < height) value = Math.max(value, current[index + 4096] - 1);
                next[index] = value; changed |= value != current[index];
            }
            current = next;
            if (!changed) return current;
        }
        throw new AssertionError("Oracle failed to converge");
    }
    private OpenRegionLightingTest() { }
}
