/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.PaletteSectionClearBackend;
import org.voxelregion.forge.PostProcessingClearance;
import org.voxelregion.forge.GenerationBridge;

/** Exact native-list ownership, independent of an elapsed-time performance oracle. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PostProcessingClearanceGameTests {
    private static final short FIRST = packed(2, 7, 11), SECOND = packed(9, 3, 5);

    @GameTest(template = "empty", batch = "vre_postprocessing_clearance", timeoutTicks = 800)
    public static void cancellationPreservesNativeListsAndSuccessfulCommitConsumesThem(GameTestHelper helper) throws Exception {
        org.voxelregion.verification.SectionStatsChecks.verify(helper.getLevel());
        var fixture = fixture(helper);
        try {
            var proof = capture(helper, fixture);
            var plans = prepare(helper, fixture, proof, PaletteBackendGameTests.HOST);
            helper.assertTrue(!PaletteSectionClearBackend.prepareSelected(fixture.level(), fixture.chunk(), fixture.firstIndex(),
                    null, state -> true, PaletteBackendGameTests.HOST).accepted(), "Ordinary backend still rejects pending native work");
            helper.assertTrue(proof.plansEraseAll(plans), "Two distinct native coordinate encodings resolve to planned AIR");
            var task = task(fixture);
            deferred().put(task.id, proof);
            ForgeOperationRunner.cancel(task);
            helper.assertTrue(!deferred().containsKey(task.id) && proof.matchesMetadata(),
                    "Real cancellation drops only the detached token, preserving full native lists");
            helper.assertTrue(fixture.chunk().getBlockState(at(fixture, 0, FIRST)).is(Blocks.WATER),
                    "Preparing and cancelling did not mutate fluid terrain");

            var report = PaletteSectionClearBackend.commitBatch(plans, () -> true);
            helper.assertTrue(report.applied() && proof.matchesMetadata(), "Palette commit has not consumed native lists itself");
            helper.assertTrue(proof.consumeAfterCommit() && !ChunkReadAhead.pendingPostProcessing(fixture.chunk()),
                    "Only actual all-AIR completion consumes every captured native entry");
            helper.assertTrue(!proof.consumeAfterCommit(), "Metadata token is one-shot");
            helper.succeed();
        } finally { PaletteBackendGameTests.release(fixture); }
    }

    @GameTest(template = "empty", batch = "vre_postprocessing_clearance", timeoutTicks = 800)
    public static void unselectedProtectedUnknownBlockEntitiesAndUpgradeUseNative(GameTestHelper helper) throws Exception {
        var fixture = fixture(helper);
        try {
            helper.assertTrue(PostProcessingClearance.capture(fixture.level(), fixture.chunk(), (pos, state) -> pos.getX() == -1) == null,
                    "One unselected native entry prevents any deferral");
            BlockPos marker = at(fixture, 0, FIRST);
            fixture.level().setBlock(marker, Blocks.BEDROCK.defaultBlockState(), 18);
            helper.assertTrue(ForgeOperationRunner.postProcessingClearance(fixture.level(), task(fixture), fixture.chunk()) == null,
                    "Real bedrock policy keeps native processing");
            fixture.level().setBlock(marker, Blocks.OAK_SLAB.defaultBlockState(), 18);
            helper.assertTrue(PostProcessingClearance.capture(fixture.level(), fixture.chunk(), (pos, state) -> true) == null,
                    "Unknown terrain callbacks cannot be suppressed");
            fixture.level().setBlock(marker, Blocks.WATER.defaultBlockState(), 18);
            BlockPos entity = fixture.minimum().offset(1, 20, 1);
            fixture.level().setBlock(entity, Blocks.OAK_SLAB.defaultBlockState(), 18);
            helper.assertTrue(PostProcessingClearance.capture(fixture.level(), fixture.chunk(), (pos, state) -> true) == null,
                    "Audited water/stone postlist cannot defer native work when another chunk cell has an unknown callback");
            fixture.level().setBlock(entity, Blocks.AIR.defaultBlockState(), 18);
            fixture.level().setBlock(entity, Blocks.CHEST.defaultBlockState(), 18);
            helper.assertTrue(PostProcessingClearance.capture(fixture.level(), fixture.chunk(), (pos, state) -> true) == null,
                    "A live block entity anywhere in the chunk keeps the native lifecycle");
            fixture.level().setBlock(entity, Blocks.AIR.defaultBlockState(), 18);
            CompoundTag pending = new CompoundTag();
            pending.putString("id", "minecraft:chest"); pending.putInt("x", entity.getX());
            pending.putInt("y", entity.getY()); pending.putInt("z", entity.getZ());
            fixture.chunk().setBlockEntityNbt(pending);
            helper.assertTrue(PostProcessingClearance.capture(fixture.level(), fixture.chunk(), (pos, state) -> true) == null,
                    "Pending block entity NBT also requires native promotion");
            fixture.chunk().removeBlockEntity(entity);
            Field upgrade = null;
            for (Field field : ChunkAccess.class.getDeclaredFields()) if (field.getType() == UpgradeData.class) upgrade = field;
            if (upgrade == null) throw new AssertionError("UpgradeData fixture field missing");
            upgrade.setAccessible(true);
            Object old = upgrade.get(fixture.chunk());
            CompoundTag data = new CompoundTag(); data.putInt("Sides", 1);
            try {
                upgrade.set(fixture.chunk(), new UpgradeData(data, fixture.level()));
                helper.assertTrue(PostProcessingClearance.capture(fixture.level(), fixture.chunk(), (pos, state) -> true) == null,
                        "Pending native upgrade prevents deferral");
                CompoundTag neighbor = new CompoundTag();
                neighbor.putString("i", "minecraft:water"); neighbor.putInt("x", marker.getX());
                neighbor.putInt("y", marker.getY()); neighbor.putInt("z", marker.getZ());
                neighbor.putInt("t", 1); neighbor.putInt("p", 0);
                ListTag ticks = new ListTag(); ticks.add(neighbor);
                CompoundTag onlyTicks = new CompoundTag(); onlyTicks.put("neighbor_fluid_ticks", ticks);
                var tickUpgrade = new UpgradeData(onlyTicks, fixture.level());
                helper.assertTrue(tickUpgrade.isEmpty() && tickUpgrade.write().contains("neighbor_fluid_ticks"),
                        "Native isEmpty omits neighbor ticks: fixture exercises the hidden upgrade work");
                upgrade.set(fixture.chunk(), tickUpgrade);
                helper.assertTrue(PostProcessingClearance.capture(fixture.level(), fixture.chunk(), (pos, state) -> true) == null,
                        "Saved neighbor ticks require native processing even when UpgradeData.isEmpty reports true");
            } finally { upgrade.set(fixture.chunk(), old); }
            helper.succeed();
        } finally { PaletteBackendGameTests.release(fixture); }
    }

    @GameTest(template = "empty", batch = "vre_postprocessing_clearance", timeoutTicks = 800)
    public static void sameLengthMetadataEditInvalidatesBeforeWriteAndLateEditRemainsAfterWrite(GameTestHelper helper) {
        var fixture = fixture(helper);
        try {
            var proof = capture(helper, fixture);
            var plans = prepare(helper, fixture, proof, PaletteBackendGameTests.HOST);
            short replacement = packed(3, 7, 11);
            var list = fixture.chunk().getPostProcessing()[fixture.firstIndex()];
            list.set(0, replacement); // Native metadata does not necessarily advance terrain revision.
            helper.assertTrue(!proof.matchesMetadata() && !PaletteSectionClearBackend.commitBatch(plans, () -> true).applied(),
                    "Same-size list mutation rejects the batch before any palette swap");
            helper.assertTrue(fixture.chunk().getBlockState(at(fixture, 0, FIRST)).is(Blocks.WATER), "Rejected batch left water intact");
            list.set(0, FIRST);
            proof = capture(helper, fixture);
            var late = new PaletteSectionClearBackend.HostHooks() {
                public boolean supportsBulk(ServerLevel level) { return true; }
                public long mutationRevision(LevelChunk chunk) { return PaletteBackendGameTests.HOST.mutationRevision(chunk); }
                public void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
                    PaletteBackendGameTests.HOST.beforePaletteReplaced(level, chunk, sectionY);
                }
                public void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {
                    PaletteBackendGameTests.HOST.afterPaletteReplaced(level, chunk, sectionY);
                }
                public void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
                    chunk.getPostProcessing()[fixture.firstIndex()].set(0, replacement);
                }
            };
            plans = prepare(helper, fixture, proof, late);
            var report = PaletteSectionClearBackend.commitBatch(plans, () -> true);
            helper.assertTrue(report.applied() && !proof.consumeAfterCommit(),
                    "A post-swap observer edit cannot be mistaken for an unapplied transaction or successful list consumption");
            helper.assertTrue(fixture.chunk().getPostProcessing()[fixture.firstIndex()].getShort(0) == replacement,
                    "New native work remains untouched for recovery/native handling");
            helper.assertTrue(fixture.chunk().getBlockState(at(fixture, 0, FIRST)).isAir(),
                    "Already committed terrain remains applied; no synthetic rollback");
            helper.succeed();
        } finally { PaletteBackendGameTests.release(fixture); }
    }

    @GameTest(template = "empty", batch = "vre_postprocessing_clearance", timeoutTicks = 800)
    public static void retainedPlannedEntryCannotAuthorizeCommit(GameTestHelper helper) {
        var fixture = fixture(helper);
        try {
            var proof = capture(helper, fixture);
            var selection = new java.util.BitSet(4096);
            selection.set(0, 4096); selection.clear(2 | (11 << 4) | (7 << 8));
            var partial = PaletteSectionClearBackend.prepareSelected(fixture.level(), fixture.chunk(), fixture.firstIndex(),
                    selection, state -> true, PaletteBackendGameTests.HOST, proof);
            var complete = PaletteSectionClearBackend.prepareSelected(fixture.level(), fixture.chunk(), fixture.firstIndex() + 1,
                    null, state -> true, PaletteBackendGameTests.HOST, proof);
            helper.assertTrue(partial.accepted(), "A detached partial selection can be constructed without side effects");
            helper.assertTrue(complete.accepted(), "Other pending section has a complete plan");
            var plans = List.of(partial.prepared(), complete.prepared());
            helper.assertTrue(!proof.plansEraseAll(plans)
                            && !PaletteSectionClearBackend.commitBatch(plans, () -> true).applied(),
                    "Pending entry retained by actual selection is rejected even if eligibility was initially broader");
            helper.assertTrue(proof.matchesMetadata(), "Rejected partial plan keeps original lists");
            helper.succeed();
        } finally { PaletteBackendGameTests.release(fixture); }
    }

    @GameTest(template = "empty", batch = "vre_postprocessing_clearance", timeoutTicks = 800)
    public static void airOnlyListsStillRequireFinalPermissionAndExactMetadata(GameTestHelper helper) {
        var fixture = fixture(helper);
        try {
            fixture.level().setBlock(at(fixture, 0, FIRST), Blocks.AIR.defaultBlockState(), 18);
            fixture.level().setBlock(at(fixture, 1, SECOND), Blocks.AIR.defaultBlockState(), 18);
            var proof = capture(helper, fixture);
            helper.assertTrue(proof.plansEraseAll(List.of()), "Already AIR native entries need no palette writes");
            helper.assertTrue(!proof.consumeAfterCommit(() -> false) && proof.matchesMetadata(),
                    "Cancel/permission denial before metadata-only commit keeps original lists");
            short replacement = packed(3, 7, 11);
            helper.assertTrue(!proof.consumeAfterCommit(() -> {
                fixture.chunk().getPostProcessing()[fixture.firstIndex()].set(0, replacement);
                return true;
            }), "Metadata-only permission callback cannot replace native work undetected");
            helper.assertTrue(fixture.chunk().getPostProcessing()[fixture.firstIndex()].getShort(0) == replacement,
                    "Replacement native entry remains pending");
            helper.succeed();
        } finally { PaletteBackendGameTests.release(fixture); }
    }

    @GameTest(template = "empty", batch = "vre_postprocessing_clearance", timeoutTicks = 800)
    public static void neighborOnlyUpgradeWaitsForNativeSuccessAndSurvivesTicketDowngrade(GameTestHelper helper) throws Exception {
        var site = BackendFixtureGameTests.allocate(helper, 0);
        var level = site.level();
        var position = site.position();
        var source = level.getChunkSource();
        TicketType<UUID> ticket = TicketType.create("vre_postprocessing_upgrade_fixture", java.util.Comparator.<UUID>naturalOrder());
        UUID id = UUID.randomUUID();
        Runnable cleanup = () -> {
            source.removeRegionTicket(ticket, position, 0, id);
            source.removeRegionTicket(ticket, position, 1, id);
        };
        BackendFixtureGameTests.onFailure(helper, cleanup);
        source.addRegionTicket(ticket, position, 0, id);
        LevelChunk chunk = level.getChunk(position.x, position.z);
        var bridge = (GenerationBridge) source.chunkMap;
        helper.assertTrue(!bridge.vre$postProcessingReady(chunk), "Fresh FULL fixture has not requested native BLOCK_TICKING");
        BlockPos marker = new BlockPos(position.getMinBlockX() + 5, level.getMinBuildHeight() + 1, position.getMinBlockZ() + 7);
        level.setBlock(marker, Blocks.STONE.defaultBlockState(), 18);
        CompoundTag tick = new CompoundTag();
        tick.putString("i", "minecraft:stone"); tick.putInt("x", marker.getX()); tick.putInt("y", marker.getY());
        tick.putInt("z", marker.getZ()); tick.putInt("t", 6000); tick.putInt("p", 0);
        ListTag ticks = new ListTag(); ticks.add(tick);
        CompoundTag savedUpgrade = new CompoundTag(); savedUpgrade.put("neighbor_block_ticks", ticks);
        var upgrade = new UpgradeData(savedUpgrade, level);
        Field field = null;
        for (Field candidate : ChunkAccess.class.getDeclaredFields()) if (candidate.getType() == UpgradeData.class) field = candidate;
        if (field == null) throw new AssertionError("UpgradeData fixture field missing");
        field.setAccessible(true);
        field.set(chunk, upgrade); // Install loaded-NBT metadata before its first native ticking lifecycle.
        Field upgradeField = field;
        helper.assertTrue(upgrade.isEmpty() && java.util.Arrays.stream(chunk.getPostProcessing())
                .allMatch(list -> list == null || list.isEmpty()), "Only saved neighbor ticks are pending");
        helper.assertTrue(!level.getBlockTicks().hasScheduledTick(marker, Blocks.STONE) && ChunkReadAhead.pendingPostProcessing(chunk),
                "Neighbor-only upgrade cannot bypass the real native lifecycle");
        source.addRegionTicket(ticket, position, 1, id);
        source.removeRegionTicket(ticket, position, 0, id);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(bridge.vre$postProcessingReady(chunk),
                "Actual native postProcessGeneration/ticking future completed"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getBlockTicks().hasScheduledTick(marker, Blocks.STONE),
                            "Native upgrade really scheduled the saved neighbor tick");
                    helper.assertTrue(chunk.getUpgradeData() == upgrade && upgrade.write().contains("neighbor_block_ticks"),
                            "Native success leaves UpgradeData and its saved tick list intact");
                    helper.assertTrue(!ChunkReadAhead.pendingPostProcessing(chunk),
                            "Already-ticking chunk records actual successful native initialization");
                    try {
                        upgradeField.set(chunk, new UpgradeData(savedUpgrade, level));
                        helper.assertTrue(bridge.vre$postProcessingReady(chunk),
                                "Previous native ticking future remains successfully completed during replacement");
                        helper.assertTrue(ChunkReadAhead.pendingPostProcessing(chunk),
                                "Old successful future cannot acknowledge a new UpgradeData identity");
                    } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                    finally {
                        try { upgradeField.set(chunk, upgrade); }
                        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                    }
                    source.addRegionTicket(ticket, position, 0, id);
                    source.removeRegionTicket(ticket, position, 1, id);
                }).thenWaitUntil(() -> helper.assertTrue(!bridge.vre$postProcessingReady(chunk),
                        "Ticket downgrade reset the native ticking future"))
                .thenExecute(() -> {
                    helper.assertTrue(!ChunkReadAhead.pendingPostProcessing(chunk),
                            "Successful same-chunk/same-upgrade evidence survives FULL downgrade without perpetual promotion");
                    try {
                        upgradeField.set(chunk, new UpgradeData(savedUpgrade, level));
                        helper.assertTrue(ChunkReadAhead.pendingPostProcessing(chunk),
                                "A different upgrade identity cannot reuse the prior success evidence");
                    } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                    finally {
                        try { upgradeField.set(chunk, upgrade); }
                        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                    }
                    cleanup.run();
                }).thenSucceed();
    }

    private static PaletteBackendGameTests.Fixture fixture(GameTestHelper helper) {
        var fixture = PaletteBackendGameTests.fixture(helper, 0);
        helper.assertTrue(!ChunkReadAhead.pendingPostProcessing(fixture.chunk()) && fixture.chunk().getBlockEntitiesPos().isEmpty(),
                "Fixture starts without unrelated pending native work");
        fixture.level().setBlock(at(fixture, 0, FIRST), Blocks.WATER.defaultBlockState(), 18);
        fixture.level().setBlock(at(fixture, 1, SECOND), Blocks.STONE.defaultBlockState(), 18);
        fixture.chunk().addPackedPostProcess(FIRST, fixture.firstIndex());
        fixture.chunk().addPackedPostProcess(SECOND, fixture.firstIndex() + 1);
        return fixture;
    }
    private static PostProcessingClearance capture(GameTestHelper helper, PaletteBackendGameTests.Fixture fixture) {
        var proof = PostProcessingClearance.capture(fixture.level(), fixture.chunk(), (pos, state) -> true);
        helper.assertTrue(proof != null, "Audited pending water/stone work has a pure clearance proof");
        return proof;
    }
    private static List<PaletteSectionClearBackend.Prepared> prepare(GameTestHelper helper, PaletteBackendGameTests.Fixture fixture,
            PostProcessingClearance proof, PaletteSectionClearBackend.HostHooks hooks) {
        var plans = new java.util.ArrayList<PaletteSectionClearBackend.Prepared>();
        for (int offset = 0; offset < 2; offset++) {
            var prepared = PaletteSectionClearBackend.prepareSelected(fixture.level(), fixture.chunk(), fixture.firstIndex() + offset,
                    null, state -> true, hooks, proof);
            helper.assertTrue(prepared.accepted(), "Proof-aware preparation accepted: " + prepared.rejection());
            plans.add(prepared.prepared());
        }
        return plans;
    }
    private static OperationData.Task task(PaletteBackendGameTests.Fixture fixture) {
        var p = fixture.minimum();
        var task = new OperationData.Task(UUID.randomUUID(), UUID.randomUUID(), p.getX() + 8, p.getY() + 16, p.getZ() + 8,
                64, 0, false, true, 6000, 0, false, AdapterConfig.Physics.NONE, fixture.level().getMinSection());
        task.wholeChunk = true; task.state = OperationData.State.RUNNING;
        return task;
    }
    @SuppressWarnings("unchecked")
    private static Map<UUID, PostProcessingClearance> deferred() throws ReflectiveOperationException {
        Field field = ForgeOperationRunner.class.getDeclaredField("DEFERRED_POST_PROCESSING");
        field.setAccessible(true); return (Map<UUID, PostProcessingClearance>) field.get(null);
    }
    private static BlockPos at(PaletteBackendGameTests.Fixture fixture, int offset, short packed) {
        return fixture.minimum().offset(packed & 15, offset * 16 + ((packed >>> 4) & 15), (packed >>> 8) & 15);
    }
    private static short packed(int x, int y, int z) { return (short) (x | (y << 4) | (z << 8)); }
    private PostProcessingClearanceGameTests() {}
}
