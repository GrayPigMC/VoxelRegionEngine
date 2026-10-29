/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import org.voxelregion.runtime.CompletionWindow;

public final class CompletionWindowTest {
    public static void main(String[] args) {
        if (!CompletionWindowTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        CompletionWindow window = new CompletionWindow();
        int limit = window.observe(0, 0, 4096);
        assert limit == 128;
        for (int i = 0; i < 1000; i++) limit = window.observe(0, 1213, 4096);
        assert limit == 32 : "Submission without acknowledgement must never grow admission";
        for (int i = 0; i < 10; i++) limit = window.observe(100, 200, 4096);
        assert limit >= 400 : "Real 100/frame throughput must get enough pipeline capacity";
        assert window.observe(1000, 1000, 64) == 64;
        window.reset(); assert window.observe(0, 0, 1) == 1;
        System.out.println("CompletionWindowTest passed (stalled backlog, recovery, fast throughput, configured cap)");
    }
}
