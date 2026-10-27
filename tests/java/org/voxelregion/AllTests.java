// SPDX-License-Identifier: MPL-2.0
package org.voxelregion;

public final class AllTests {
    public static void main(String[] args) throws Exception {
        GeometryTest.main(args);
        WavefrontTest.main(args);
        BooleanGeometryTest.main(args);
        AdvancedOcclusionTest.main(args);
        AdjacentShadowTest.main(args);
        CursorCheckpointTest.main(args);
        PlannerTest.main(args);
        RuntimeTest.main(args);
    }
}
