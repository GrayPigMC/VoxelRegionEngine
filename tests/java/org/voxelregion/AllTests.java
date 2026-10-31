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
        CenterOutChunkCursorTest.main(args);
        ClearedChunkLightingTest.main(args);
        OpticalChunkLightingTest.main(args);
        GeneralChunkLightingTest.main(args);
        GlobalLightFieldTest.main(args);
        GlobalLightPagedQueueTest.main(args);
        GlobalLightSourceBoundTest.main(args);
        GlobalLightSkyTailTest.main(args);
        GlobalLightSnapshotBuilderTest.main(args);
        GlobalLightSourceIndexTest.main(args);
        RedundantHostStopsTest.main(args);
        LightBatchPlannerTest.main(args);
        FreshLightBatchPlannerTest.main(args);
        LightWorkingBudgetTest.main(args);
        GeneralLightQueueTest.main(args);
        SpatialTaskSchedulerTest.main(args);
        LightPublicationCohortsTest.main(args);
        LightOwnerFailureTest.main(args);
        OpenRegionLightingTest.main(args);
        OpenRegionSpecializationTest.main(args);
        CompletionWindowTest.main(args);
        PreparationWindowTest.main(args);
        ScopedReentryPermitTest.main(args);
        ColumnNearestIndexTest.main(args);
        PlannerTest.main(args);
        RuntimeTest.main(args);
    }
}
