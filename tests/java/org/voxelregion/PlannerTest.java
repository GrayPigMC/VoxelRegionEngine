/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.planner.ChunkPermission;
import org.voxelregion.planner.ChunkPlanning;
import org.voxelregion.planner.ClaimOracle;
import org.voxelregion.planner.ClearOptions;
import org.voxelregion.planner.GenerationSafety;
import org.voxelregion.planner.ProtectionOracle;
import org.voxelregion.planner.SectionPlan;
import org.voxelregion.planner.SectionPlanner;
import org.voxelregion.planner.SectionSnapshot;
import org.voxelregion.planner.WriteKind;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;
import org.voxelregion.spatial.SectionKey;

/** Dependency-free contract tests, executed with one logical JVM processor. */
public final class PlannerTest {
    private static final SectionKey ORIGIN = new SectionKey(0, 0, 0);
    private static final BoxVolume FULL = new BoxVolume(ORIGIN.bounds());
    private static final SectionPlanner PLANNER = new SectionPlanner();
    private static int checks;

    public static void main(String[] args) {
        snapshotAndStaticProof();
        contextualProtection();
        protectionAndShadows();
        axisSemanticsAndIndex();
        lifecycleAndClaims();
        changedPolicyIsRejected();
        fluidSnapshotAndFastPaths();
        retainedFluidsRespectSelection();
        replacementPlanInvariants();
        System.out.println("PlannerTest passed: " + checks + " checks");
    }

    private static void snapshotAndStaticProof() {
        int[] values = solid();
        SectionSnapshot section = new SectionSnapshot(ORIGIN, 17, 0, values);
        values[0] = 9;
        check(section.state(0, 0, 0) == 1, "Snapshot must detach from the source array");
        int[] copy = section.copyStates();
        copy[0] = 8;
        check(section.state(0, 0, 0) == 1, "Snapshot must not expose its storage");
        SectionPlan plan = PLANNER.plan(FULL, section, allDestroyable(), FrozenOcclusion.none());
        check(plan.kind() == WriteKind.WHOLE_AIR && plan.clearMask().isFull(), "Static proof enables whole-section clearing");
        check(plan.sectionRevision() == 17 && plan.protectionRevision() == 29, "Plan retains both revisions");

        BoxVolume half = new BoxVolume(new IntBox(0, 0, 0, 8, 16, 16));
        plan = PLANNER.plan(half, section, allDestroyable(), FrozenOcclusion.none());
        check(plan.kind() == WriteKind.MASKED_CLEAR && plan.clearMask().cardinality() == 2048, "Partial volume uses exact row ranges");
        check(plan.clearMask().contains(7, 15, 15) && !plan.clearMask().contains(8, 15, 15), "Half-open section boundary");

        SectionSnapshot air = new SectionSnapshot(ORIGIN, 1, 42, filled(42));
        plan = PLANNER.plan(FULL, air, allDestroyable(), FrozenOcclusion.none());
        check(plan.kind() == WriteKind.NOOP, "Air state need not equal zero");
        rejects(() -> new SectionPlan(ORIGIN, 1, 1, WriteKind.WHOLE_AIR, VoxelMask.EMPTY), "Plan invariants");
    }

    private static void contextualProtection() {
        final int[] calls = {0};
        ProtectionOracle contextual = new ProtectionOracle() {
            @Override public boolean mayDestroy(int state, int x, int y, int z) {
                calls[0]++;
                return x != 7 || y != 4 || z != 8;
            }
            @Override public boolean blocksPropagation(int state, int x, int y, int z) {
                throw new AssertionError("Destruction cannot implicitly query propagation");
            }
            @Override public StaticSummary staticSummary(SectionSnapshot section) { return StaticSummary.ALL_DESTROYABLE; }
        };
        SectionPlan plan = PLANNER.plan(FULL, snapshot(solid()), contextual, FrozenOcclusion.none());
        check(plan.kind() == WriteKind.MASKED_CLEAR, "Dynamic rules prohibit whole-section clearing");
        check(plan.clearMask().cardinality() == 4095 && !plan.clearMask().contains(7, 4, 8), "Position-dependent block survives");
        check(calls[0] == 4096, "An optimistic static summary cannot bypass dynamic checks");
    }

    private static void protectionAndShadows() {
        int[] values = solid();
        values[1 | (1 << 4) | (1 << 8)] = 2;
        ProtectionOracle protection = new ProtectionOracle() {
            @Override public boolean mayDestroy(int state, int x, int y, int z) { return state != 2; }
            @Override public boolean blocksPropagation(int state, int x, int y, int z) { return false; }
            @Override public boolean requiresContextualChecks() { return false; }
            @Override public StaticSummary staticSummary(SectionSnapshot section) { return StaticSummary.REQUIRES_VOXEL_CHECK; }
        };
        FrozenOcclusion occlusion = FrozenOcclusion.axis6(Point3.of(.5, .5, .5),
                new IntBox(-32, -32, -32, 32, 32, 32), List.of(new IntBox(4, 0, 0, 5, 1, 1)), 8);
        SectionPlan plan = PLANNER.plan(FULL, snapshot(values), protection, occlusion);
        check(plan.clearMask().cardinality() == 4096 - 12 - 1, "Subtract protected cells and axial shadow");
        check(!plan.clearMask().contains(1, 1, 1), "Protected nonblocking state survives");
        check(!plan.clearMask().contains(15, 0, 0) && plan.clearMask().contains(15, 1, 0), "Shadow has a fixed cross section");
    }

    private static void axisSemanticsAndIndex() {
        IntBox domain = new IntBox(-32, -32, -32, 32, 32, 32);
        Point3 center = Point3.of(.5, .5, .5);
        FrozenOcclusion tie = FrozenOcclusion.axis6(center, domain, List.of(new IntBox(4, 4, 0, 5, 5, 1)), 1);
        VoxelMask tieMask = tie.shadowMask(ORIGIN);
        check(tieMask.contains(15, 4, 0) && !tieMask.contains(4, 15, 0), "X wins the X/Y tie");

        FrozenOcclusion negative = FrozenOcclusion.axis6(center, domain, List.of(new IntBox(-5, 0, 0, -4, 1, 1)), 1);
        VoxelMask negativeMask = negative.shadowMask(new SectionKey(-1, 0, 0));
        check(negativeMask.cardinality() == 12 && negativeMask.contains(11, 0, 0)
                && !negativeMask.contains(12, 0, 0), "Negative axis and local coordinates");

        FrozenOcclusion inside = FrozenOcclusion.axis6(center, domain, List.of(new IntBox(0, 0, 0, 1, 1, 1)), 1);
        check(inside.shadowMask(ORIGIN).isFull(), "A blocker containing the center is fail-closed");
        rejects(() -> FrozenOcclusion.axis6(center, domain,
                List.of(new IntBox(1, 1, 1, 2, 2, 2), new IntBox(2, 2, 2, 3, 3, 3)), 1), "Obstacle budget");

        ArrayList<IntBox> blockers = new ArrayList<>(List.of(
                new IntBox(0, 0, 0, 1, 8, 16), new IntBox(0, 8, 0, 1, 16, 16)));
        FrozenOcclusion combined = FrozenOcclusion.axis6(Point3.of(-20, .5, .5), ORIGIN.bounds(), blockers, 2);
        blockers.clear();
        check(combined.shadowMask(ORIGIN).isFull(), "Union of multiple BVH leaves fully shadows a section");
        check(combined.sourceBlockerCount() == 2, "Obstacle input is detached from later list changes");
        check(PLANNER.plan(FULL, snapshot(solid()), allDestroyable(), combined).kind() == WriteKind.NOOP,
                "Union coverage skips a fully shadowed section");
        check(combined.classify(new IntBox(16, 0, 0, 32, 16, 16)) == Coverage.OUTSIDE, "No shadow beyond the requested bounds");

        List<IntBox> six = List.of(new IntBox(4, 0, 0, 5, 1, 1), new IntBox(-5, 0, 0, -4, 1, 1),
                new IntBox(0, 4, 0, 1, 5, 1), new IntBox(0, -5, 0, 1, -4, 1),
                new IntBox(0, 0, 4, 1, 1, 5), new IntBox(0, 0, -5, 1, 1, -4));
        FrozenOcclusion allAxes = FrozenOcclusion.axis6(center, domain, six, 6);
        check(allAxes.shadowMask(ORIGIN).cardinality() == 36, "Positive X/Y/Z union");
        check(allAxes.shadowMask(new SectionKey(0, -1, 0)).cardinality() == 12, "Negative Y shadow");
        check(allAxes.shadowMask(new SectionKey(0, 0, -1)).cardinality() == 12, "Negative Z shadow");
    }

    private static void lifecycleAndClaims() {
        ChunkPermission allowed = new ChunkPermission(3, true, false);
        GenerationSafety unknown = GenerationSafety.unknown("Generator has no lifecycle proof");
        GenerationSafety safe = new GenerationSafety(GenerationSafety.Decision.SAFE, "Fixture generator verifies every safety precondition");
        check(ChunkPlanning.route(ChunkPlanning.Presence.NEVER_GENERATED, Coverage.INSIDE, true, allowed, unknown)
                == ChunkPlanning.Route.NORMAL_GENERATION, "Unknown generator must fall back");
        check(ChunkPlanning.route(ChunkPlanning.Presence.NEVER_GENERATED, Coverage.INSIDE, true, allowed, safe)
                == ChunkPlanning.Route.MATERIALIZE_SAFE_EMPTY, "Only full, exposed, proven-safe new chunks can materialize empty");
        check(ChunkPlanning.route(ChunkPlanning.Presence.NEVER_GENERATED, Coverage.PARTIAL, true, allowed, safe)
                == ChunkPlanning.Route.NORMAL_GENERATION, "Partial chunks cannot materialize empty");
        check(ChunkPlanning.route(ChunkPlanning.Presence.NEVER_GENERATED, Coverage.INSIDE, false, allowed, safe)
                == ChunkPlanning.Route.NORMAL_GENERATION, "Unproven exposure cannot materialize empty");
        check(ChunkPlanning.route(ChunkPlanning.Presence.GENERATED_UNLOADED, Coverage.INSIDE, true, allowed, safe)
                == ChunkPlanning.Route.PROCESS_EXISTING, "Existing unloaded chunks must never be overwritten");
        check(ChunkPlanning.route(ChunkPlanning.Presence.GENERATING, Coverage.INSIDE, true, allowed, safe)
                == ChunkPlanning.Route.DEFER, "Coordinate with ongoing generation");
        check(ChunkPlanning.route(ChunkPlanning.Presence.UNKNOWN, Coverage.INSIDE, true, allowed, safe)
                == ChunkPlanning.Route.DEFER, "Unknown existence cannot authorize new terrain");
        rejects(() -> new GenerationSafety(GenerationSafety.Decision.SAFE, " "), "Safety evidence is required");

        ClaimOracle claims = (dimension, chunk, actor) -> new ChunkPermission(8, false, true);
        ChunkPermission denied = claims.query("test:dimension", new ChunkKey(0, 0), "test:actor");
        check(!denied.mayMutate() && denied.mayDamageEntities(), "Block and entity permissions are independent");
        check(ChunkPlanning.route(ChunkPlanning.Presence.LOADED, Coverage.INSIDE, true, denied, unknown)
                == ChunkPlanning.Route.SKIP, "Denied claims are skipped by lifecycle routing");
        check(FrozenOcclusion.none().shadowMask(ORIGIN).isEmpty(), "A claim decision does not implicitly cast a shadow");
    }

    private static void changedPolicyIsRejected() {
        ProtectionOracle changing = new ProtectionOracle() {
            private long revision;
            @Override public long revision() { return revision; }
            @Override public boolean mayDestroy(int state, int x, int y, int z) { revision++; return true; }
            @Override public boolean blocksPropagation(int state, int x, int y, int z) { return false; }
        };
        rejects(() -> PLANNER.plan(FULL, snapshot(solid()), changing, FrozenOcclusion.none()), "Changed policy invalidates a plan");
        int[] fluids = new int[4096];
        fluids[0] = 2;
        rejects(() -> PLANNER.plan(FULL, new SectionSnapshot(ORIGIN, 1, 0, solid(), fluids), changing,
                FrozenOcclusion.none(), new ClearOptions(false)), "Changed policy also invalidates fluid replacement plans");
    }

    private static void fluidSnapshotAndFastPaths() {
        check(ClearOptions.DEFAULT.clearFluids(), "Default policy clears fluids");
        SectionSnapshot unknown = snapshot(solid());
        check(!unknown.hasFluidData(), "Old snapshots explicitly lack fluid evidence");
        rejects(unknown::hasFluids, "Unknown metadata is not proof of a dry section");
        rejects(() -> unknown.fluidState(0, 0, 0), "Reading uncaptured fluid data must fail");
        // Every fluid accessor on this snapshot throws; both default entry points must avoid them.
        check(PLANNER.plan(FULL, unknown, allDestroyable(), FrozenOcclusion.none()).kind() == WriteKind.WHOLE_AIR,
                "Legacy default path does not query missing fluid metadata");
        check(PLANNER.plan(FULL, unknown, allDestroyable(), FrozenOcclusion.none(), ClearOptions.DEFAULT).kind() == WriteKind.WHOLE_AIR,
                "Explicit default path does not query missing fluid metadata");
        rejects(() -> PLANNER.plan(FULL, unknown, allDestroyable(), FrozenOcclusion.none(), new ClearOptions(false)),
                "Preservation requires complete fluid metadata");
        rejects(() -> PLANNER.plan(FULL, snapshot(new int[4096]), allDestroyable(), FrozenOcclusion.none(), new ClearOptions(false)),
                "Air skipping cannot turn unknown fluid metadata into evidence");
        rejects(() -> new SectionSnapshot(ORIGIN, 1, 0, solid(), new int[4095]), "Fluid array length must match a section");
        rejects(() -> new SectionSnapshot(ORIGIN, 1, 0, new int[4096], filled(2)), "Air/fluid snapshot inconsistency is rejected");

        int[] fluids = filled(2);
        SectionSnapshot water = new SectionSnapshot(ORIGIN, 2, 0, filled(2), fluids);
        fluids[0] = 0;
        int[] copy = water.copyFluidStates();
        copy[0] = 0;
        check(water.hasFluidData() && water.hasFluids() && water.fluidState(0, 0, 0) == 2,
                "Fluid metadata is detached from source and returned arrays");
        check(water.estimatedBytes() > unknown.estimatedBytes() + 16_000, "Snapshot accounting includes retained fluid storage");
        check(PLANNER.plan(FULL, water, allDestroyable(), FrozenOcclusion.none()).kind() == WriteKind.WHOLE_AIR,
                "Default policy still clears an all-fluid section using its static proof");
        SectionPlan pure = PLANNER.plan(FULL, water, allDestroyable(), FrozenOcclusion.none(), new ClearOptions(false));
        check(pure.kind() == WriteKind.NOOP && pure.clearMask().isEmpty() && pure.retainedFluidWrites().isEmpty(),
                "Preserving pure fluid performs no identical replacement writes");

        int[] callbacks = {0};
        ProtectionOracle proven = new ProtectionOracle() {
            @Override public boolean mayDestroy(int state, int x, int y, int z) { callbacks[0]++; return true; }
            @Override public boolean blocksPropagation(int state, int x, int y, int z) { throw new AssertionError("No propagation lookup"); }
            @Override public boolean requiresContextualChecks() { return false; }
            @Override public StaticSummary staticSummary(SectionSnapshot section) { return StaticSummary.ALL_DESTROYABLE; }
        };
        SectionSnapshot dry = new SectionSnapshot(ORIGIN, 1, 42, solid(), filled(42));
        check(!dry.hasFluids(), "A complete dry snapshot is a constant-time no-fluid proof");
        SectionPlan whole = PLANNER.plan(FULL, dry, proven, FrozenOcclusion.none(), new ClearOptions(false));
        check(whole.kind() == WriteKind.WHOLE_AIR && whole.clearMask() == VoxelMask.ALL,
                "Preserve plus no-fluid evidence retains the whole-section singleton path");
        SectionPlan half = PLANNER.plan(new BoxVolume(new IntBox(0, 0, 0, 8, 16, 16)), dry, proven,
                FrozenOcclusion.none(), new ClearOptions(false));
        check(half.kind() == WriteKind.MASKED_CLEAR && half.clearMask().cardinality() == 2048,
                "Preserve plus no-fluid evidence retains the partial range path");
        check(callbacks[0] == 0, "Dry static fast paths perform zero voxel protection callbacks");
    }

    private static void retainedFluidsRespectSelection() {
        int[] states = new int[4096], fluids = new int[4096];
        states[0] = 1; // Dry solid.
        states[1] = fluids[1] = 2; // Pure water.
        states[2] = fluids[2] = 3; // Pure lava.
        states[3] = 4; fluids[3] = 2; // Water-containing solid.
        states[4] = 5; fluids[4] = 2; // Protected water-containing solid.
        states[5] = 6; fluids[5] = 3; // Host-defined solid retaining another fluid.
        SectionSnapshot mixed = new SectionSnapshot(ORIGIN, 7, 0, states, fluids);
        int[] callbacks = {0};
        ProtectionOracle protection = new ProtectionOracle() {
            @Override public boolean mayDestroy(int state, int x, int y, int z) { callbacks[0]++; return state != 5; }
            @Override public boolean blocksPropagation(int state, int x, int y, int z) { return false; }
        };
        SectionPlan plan = PLANNER.plan(FULL, mixed, protection, FrozenOcclusion.none(), new ClearOptions(false));
        check(plan.kind() == WriteKind.MASKED_REPLACE && plan.clearMask().cardinality() == 1
                && plan.clearMask().contains(0, 0, 0), "Only dry destroyed solids are written as air");
        check(plan.retainedFluidWrites().size() == 2 && plan.retainedFluidWrites().get(2).cardinality() == 1
                && plan.retainedFluidWrites().get(2).contains(3, 0, 0)
                && plan.retainedFluidWrites().get(3).contains(5, 0, 0), "Fluid-containing solids retain their individual target states");
        check(!plan.clearMask().contains(1, 0, 0) && !plan.clearMask().contains(2, 0, 0)
                && !plan.retainedFluidWrites().get(2).contains(1, 0, 0)
                && !plan.retainedFluidWrites().get(3).contains(2, 0, 0), "Pure fluids do not enter any write mask");
        check(!plan.retainedFluidWrites().get(2).contains(4, 0, 0) && !plan.clearMask().contains(4, 0, 0),
                "Protected fluid-containing solids remain intact");
        check(callbacks[0] == 4, "Protection checks cover solids only; pure fluids never request destruction");

        SectionPlan boundary = PLANNER.plan(new BoxVolume(new IntBox(0, 0, 0, 4, 1, 1)), mixed, protection,
                FrozenOcclusion.none(), new ClearOptions(false));
        check(boundary.retainedFluidWrites().size() == 1 && boundary.retainedFluidWrites().get(2).contains(3, 0, 0)
                && !boundary.retainedFluidWrites().containsKey(3), "Half-open geometry clips retained-fluid writes");
        FrozenOcclusion shadow = FrozenOcclusion.axis6(Point3.of(.5, .5, .5), ORIGIN.bounds(),
                List.of(new IntBox(3, 0, 0, 4, 1, 1)), 1);
        SectionPlan shadowed = PLANNER.plan(FULL, mixed, protection, shadow, new ClearOptions(false));
        check(shadowed.kind() == WriteKind.MASKED_CLEAR && shadowed.clearMask().cardinality() == 1
                && shadowed.retainedFluidWrites().isEmpty(), "Shadows suppress fluid replacements as well as air writes");

        SectionPlan defaultPlan = PLANNER.plan(FULL, mixed, protection, FrozenOcclusion.none());
        check(defaultPlan.kind() == WriteKind.MASKED_CLEAR && defaultPlan.clearMask().cardinality() == 5
                && defaultPlan.retainedFluidWrites().isEmpty(), "Default mode clears fluid and solids while retaining protected blocks");
        SectionPlan staticallyProven = PLANNER.plan(FULL, mixed, allDestroyable(), FrozenOcclusion.none(), new ClearOptions(false));
        check(staticallyProven.kind() == WriteKind.MASKED_REPLACE
                && staticallyProven.retainedFluidWrites().get(2).cardinality() == 2,
                "A valid static proof avoids callbacks while preserving the requested fluids");
    }

    private static void replacementPlanInvariants() {
        VoxelMask a = VoxelMask.builder().set(0, 0, 0).build();
        VoxelMask b = VoxelMask.builder().set(1, 0, 0).build();
        rejects(() -> new SectionPlan(ORIGIN, 1, 1, WriteKind.MASKED_REPLACE, VoxelMask.EMPTY),
                "Replacement kind requires a nonempty target map");
        rejects(() -> new SectionPlan(ORIGIN, 1, 1, WriteKind.MASKED_CLEAR, a, Map.of(2, b)),
                "Air-only kind cannot hide replacement writes");
        rejects(() -> new SectionPlan(ORIGIN, 1, 1, WriteKind.WHOLE_AIR, VoxelMask.ALL, Map.of(2, b)),
                "Whole-air kind cannot preserve fluid");
        rejects(() -> new SectionPlan(ORIGIN, 1, 1, WriteKind.NOOP, VoxelMask.EMPTY, Map.of(2, b)),
                "Noop cannot hide replacement writes");
        rejects(() -> new SectionPlan(ORIGIN, 1, 1, WriteKind.MASKED_REPLACE, VoxelMask.EMPTY, Map.of(2, VoxelMask.EMPTY)),
                "Every fluid target needs a nonempty mask");
        rejects(() -> new SectionPlan(ORIGIN, 1, 1, WriteKind.MASKED_REPLACE, a, Map.of(2, a)),
                "Air and fluid writes must not overlap");
        rejects(() -> new SectionPlan(ORIGIN, 1, 1, WriteKind.MASKED_REPLACE, VoxelMask.EMPTY, Map.of(2, a, 3, a)),
                "Different fluid targets must not overlap");

        Map<Integer, VoxelMask> mutable = new HashMap<>();
        mutable.put(2, b);
        SectionPlan plan = new SectionPlan(ORIGIN, 1, 1, WriteKind.MASKED_REPLACE, a, mutable);
        mutable.clear();
        check(plan.retainedFluidWrites().size() == 1, "Replacement targets are detached from the source map");
        boolean immutable = false;
        try { plan.retainedFluidWrites().put(3, a); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Replacement targets are immutable");
        check(plan.estimatedBytes() > new SectionPlan(ORIGIN, 1, 1, WriteKind.MASKED_CLEAR, a).estimatedBytes(),
                "Plan accounting includes target maps and masks");
        SectionPlan fluidOnly = new SectionPlan(ORIGIN, 1, 1, WriteKind.MASKED_REPLACE, VoxelMask.EMPTY, Map.of(2, b));
        check(fluidOnly.clearMask().isEmpty() && !fluidOnly.retainedFluidWrites().isEmpty(),
                "Fluid replacement need not include any air writes");
    }

    private static ProtectionOracle allDestroyable() {
        return new ProtectionOracle() {
            @Override public boolean mayDestroy(int state, int x, int y, int z) {
                throw new AssertionError("The static proof should avoid voxel callbacks");
            }
            @Override public boolean blocksPropagation(int state, int x, int y, int z) { return false; }
            @Override public long revision() { return 29; }
            @Override public boolean requiresContextualChecks() { return false; }
            @Override public StaticSummary staticSummary(SectionSnapshot section) { return StaticSummary.ALL_DESTROYABLE; }
        };
    }

    private static SectionSnapshot snapshot(int[] states) { return new SectionSnapshot(ORIGIN, 1, 0, states); }
    private static int[] solid() { return filled(1); }
    private static int[] filled(int state) { int[] values = new int[4096]; Arrays.fill(values, state); return values; }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message + ": expected rejection");
    }
}
