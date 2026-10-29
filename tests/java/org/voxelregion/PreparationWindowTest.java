/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import org.voxelregion.runtime.PreparationWindow;

public final class PreparationWindowTest {
    public static void main(String[] args) {
        if (!PreparationWindowTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        var window = new PreparationWindow();
        var decision = window.observe(0, 0, 0, 128, true, 0, 128, 10_000_000, 1024, true);
        assert decision.target() == 128;
        for (int i = 0; i < 80; i++)
            decision = window.observe(8, 4, 4, decision.target(), true, 0, 128, 10_000_000, 1024, true);
        assert decision.target() == 1024 : "Upstream progress must grow independently of final acknowledgements";
        assert decision.submissions() <= 128 && decision.promotions() <= 128 : "Per-frame native requests remain bounded";

        window = new PreparationWindow();
        for (int i = 0; i < 1000; i++)
            decision = window.observe(0, 0, 0, 1024, true, 0, 128, 10_000_000, 1024, true);
        assert decision.target() == 256 : "No-progress probes stop at 256 instead of collapsing or growing without evidence";
        for (int i = 0; i < 80; i++)
            decision = window.observe(100, 100, 0, 1024, true, 96, 128, 10_000_000, 1024, true);
        assert decision.target() == 32 && decision.submissions() == 0 : "Real downstream congestion wins over upstream demand";

        window = new PreparationWindow();
        for (int i = 0; i < 80; i++)
            decision = window.observe(100, 100, 0, 1024, true, 0, 128, 100_000, 1024, true);
        assert decision.target() == 32 && decision.submissions() == 0 && decision.promotions() == 0;
        window = new PreparationWindow();
        for (int i = 0; i < 80; i++)
            decision = window.observe(100, 100, 100, 1024, false, 0, 128, 10_000_000, 1024, true);
        assert decision.target() == 128 : "Cached readiness alone does not enlarge the ordered preparation window";
        for (int i = 0; i < 80; i++)
            decision = window.observe(100, 100, 0, 1024, true, 0, 128, 10_000_000, 1024, false);
        assert decision.target() == 128 : "Partial readiness samples cannot manufacture starvation";
        for (int maximum : new int[] {0, 1, 2, 3, 31}) {
            window = new PreparationWindow();
            decision = window.observe(100, 100, 0, 1024, true, 0, 1, 10_000_000, maximum, true);
            assert decision.target() <= maximum && decision.submissions() <= maximum && decision.promotions() <= maximum;
        }
        System.out.println("PreparationWindowTest passed (upstream latency, bounded probes, pressure, per-frame caps, small limits)");
    }
}
