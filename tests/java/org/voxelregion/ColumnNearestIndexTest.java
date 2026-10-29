/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.*;
import org.voxelregion.search.ColumnNearestIndex;

public final class ColumnNearestIndexTest {
    public static void main(String[] args) {
        if (!ColumnNearestIndexTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        Random random = new Random(0x434f4c554d4eL); int checked = 0;
        for (int trial = 0; trial < 40; trial++) {
            var entries = new ArrayList<ColumnNearestIndex.Entry<Integer>>();
            for (int i = 0; i < 150; i++) {
                long[] lo = new long[6], hi = new long[6];
                for (int d = 0; d < 6; d++) { lo[d] = random.nextInt(5)*10-20; hi[d] = lo[d]+10; }
                entries.add(new ColumnNearestIndex.Entry<>(lo, hi, random.nextInt(3), random.nextInt(8)));
            }
            var index = new ColumnNearestIndex<Integer>(entries);
            for (int column = 0; column < 12; column++) {
                long[] fixed = new long[5]; for (int d = 0; d < 5; d++) fixed[d] = random.nextInt(61)-30;
                var query = index.column(fixed);
                for (long depth = -40; depth <= 40; depth++) {
                    long best = Long.MAX_VALUE; var winners = new HashSet<Integer>();
                    for (var entry : entries) {
                        long score = entry.offset()*entry.offset(); long[] lo = entry.minimum(), hi = entry.maximum();
                        for (int d = 0; d < 6; d++) {
                            long coordinate = d == 5 ? depth : fixed[d];
                            long distance = coordinate < lo[d] ? lo[d]-coordinate : coordinate > hi[d] ? coordinate-hi[d] : 0;
                            score += distance*distance;
                        }
                        if (score < best) { best = score; winners.clear(); }
                        if (score == best) winners.add(entry.value());
                    }
                    Integer actual = query.unique(depth);
                    assert winners.size() == 1 ? winners.contains(actual) : actual == null : "Incorrect nearest value or missed tie";
                    checked++;
                }
            }
        }
        System.out.println("ColumnNearestIndexTest passed; brute-force queries="+checked);
    }
}
