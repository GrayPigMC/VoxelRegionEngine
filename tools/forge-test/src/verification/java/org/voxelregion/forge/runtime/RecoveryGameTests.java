/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Recovery evidence tests; no SavedData cursor is used as an oracle. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class RecoveryGameTests {
    private static final String HASH = "immutable-geometry-and-policy-fixture-v1";

    @GameTest(template = "empty", batch = "vre_receipt_protocol", timeoutTicks = 100)
    public static void fullPipelineWindowSurvivesReloadWithoutAuthorizingReplay(GameTestHelper helper) {
        for (int pending : new int[] {0, 512, 513, 1024, 4096}) {
            var task = new OperationData.Task(UUID.randomUUID(), UUID.randomUUID(), .5, 64.5, .5,
                    64, 0, true, true, 6000, 0, false, AdapterConfig.Physics.NONE, -4);
            task.wholeChunk = true;
            task.phase = 1;
            task.state = OperationData.State.SETTLING;
            task.unconfirmedFences = pending;
            OperationData data = new OperationData();
            data.tasks.add(task);
            OperationData restored = OperationData.load(data.save(new CompoundTag()));
            helper.assertTrue(restored.tasks.size() == 1,
                    "A valid " + pending + "-chunk window must retain its operation record on reload");
            var recovered = restored.tasks.get(0);
            helper.assertTrue(recovered.id.equals(task.id) && recovered.unconfirmedFences == pending,
                    "Reload preserves the exact identity and pending-write evidence");
            helper.assertTrue(recovered.state == (pending == 0
                            ? OperationData.State.RECOVERING : OperationData.State.PAUSED_RECOVERY),
                    "Pending writes require evidence inspection rather than silently completing or replaying");
            for (int invalid : new int[] {-1, 4097}) {
                CompoundTag malformed = task.save();
                malformed.putInt("unconfirmedFences", invalid);
                boolean rejected = false;
                try { OperationData.Task.load(malformed); }
                catch (IllegalArgumentException expected) { rejected = true; }
                helper.assertTrue(rejected, "Invalid pending-write count remains rejected: " + invalid);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_receipt_protocol", timeoutTicks = 100)
    public static void detailedReceiptsKeepPhaseAndWriteEvidence(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        ChunkReceipts receipts = new ChunkReceipts();
        helper.assertTrue(receipts.resumeCursor(id, HASH, 0).decision() == RecoveryValidation.Decision.PAUSE_UNCERTAIN,
                "An unvisited chunk cannot resume from a global task cursor");
        receipts.beginPhase(id, HASH, 0, -4, 20);
        receipts.beforeWrite(id, 0, -4, 0);
        ChunkReceipts interrupted = restored(receipts);
        helper.assertTrue(interrupted.resumeCursor(id, HASH, 0).decision() == RecoveryValidation.Decision.PAUSE_UNCERTAIN,
                "A save during a native call keeps pending-write uncertainty");
        receipts.changed();
        receipts.afterWrite(id, 0, -4, 1);
        ChunkReceipts saved = restored(receipts);
        var decision = saved.resumeCursor(id, HASH, 0);
        helper.assertTrue(decision.decision() == RecoveryValidation.Decision.RESUME
                        && decision.cursor().sectionY() == -4 && decision.cursor().voxel() == 1,
                "Co-saved cursor resumes after the processed cell");
        helper.assertTrue(saved.resumeCursor(id, HASH + "-changed", 0).decision() == RecoveryValidation.Decision.PAUSE_UNCERTAIN,
                "Different operation parameters cannot reuse progress");
        saved.changed();
        helper.assertTrue(saved.resumeCursor(id, HASH, 0).decision() == RecoveryValidation.Decision.PAUSE_UNCERTAIN,
                "External mutation invalidates remaining-work recovery");

        receipts.advanceCursor(id, 0, 20, 0);
        receipts.completePhase(id, 0, false);
        helper.assertTrue(receipts.resumeCursor(id, HASH, 0).decision() == RecoveryValidation.Decision.SKIP_PHASE,
                "A completed shell is never replayed");
        helper.assertTrue(receipts.resumeCursor(id, HASH, 1).decision() == RecoveryValidation.Decision.BEGIN_NEXT_PHASE,
                "An unchanged chunk can enter the next shell");
        for (int phase = 1; phase < 2048; phase++) {
            receipts.beginPhase(id, HASH, phase, -4, 20);
            receipts.advanceCursor(id, phase, 20, 0);
            receipts.completePhase(id, phase, false);
        }
        CompoundTag serialized = receipts.serializeNBT();
        helper.assertTrue(serialized.getCompound("_details").getAllKeys().size() == 1
                        && serialized.getAllKeys().size() == 5,
                "Thousands of shells retain one bounded receipt per operation/chunk");
        ChunkReceipts phases = restored(receipts);
        helper.assertTrue(phases.resumeCursor(id, HASH, 2047).decision() == RecoveryValidation.Decision.SKIP_PHASE,
                "Completed phase watermark survives serialization");
        UUID denied = UUID.randomUUID();
        phases.skipPermission(denied);
        helper.assertTrue(restored(phases).resumeCursor(denied, HASH, 5000).decision() == RecoveryValidation.Decision.STOP_TERMINAL,
                "A denied chunk does not reactivate in a later shell");

        CompoundTag corrupt = serialized.copy();
        corrupt.getCompound("_details").getCompound(id.toString()).remove("revision");
        boolean rejected = false;
        try { phases.deserializeNBT(corrupt); }
        catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected && phases.state(denied) == ChunkReceipts.SKIPPED_PERMISSION,
                "Malformed progress rejects atomically without discarding prior receipts");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_receipt_native", timeoutTicks = 100)
    public static void nativeEditsAndAbaPreventOldRecovery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), flags);
        var chunk = level.getChunkAt(pos);
        ChunkReceipts receipts = ChunkReceipts.of(chunk);
        UUID id = UUID.randomUUID();
        int section = pos.getY() >> 4;
        int voxel = (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8);
        receipts.beginPhase(id, HASH, 0, section, section + 1);
        receipts.advanceCursor(id, 0, section, voxel);
        long before = receipts.mutationRevision();
        receipts.beforeWrite(id, 0, section, voxel);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        receipts.afterWrite(id, 0, section, voxel + 1);
        helper.assertTrue(receipts.mutationRevision() > before, "Native setBlockState hook records mutations");
        helper.assertTrue(restored(receipts).resumeCursor(id, HASH, 0).decision() == RecoveryValidation.Decision.RESUME,
                "Own successful commit updates the co-saved expected revision");
        level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), flags);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        helper.assertTrue(level.getBlockState(pos).isAir()
                        && restored(receipts).resumeCursor(id, HASH, 0).decision() == RecoveryValidation.Decision.PAUSE_UNCERTAIN,
                "Returning to an identical state is still an edit, so ABA cannot authorize replay");
        receipts.cancel(id);
        helper.assertTrue(restored(receipts).resumeCursor(id, HASH, 0).decision() == RecoveryValidation.Decision.STOP_TERMINAL,
                "Cancellation remains terminal after reload");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_receipt_legacy", timeoutTicks = 100)
    public static void legacyStartedNeverInventsACursor(GameTestHelper helper) {
        UUID started = UUID.randomUUID(), complete = UUID.randomUUID();
        CompoundTag old = new CompoundTag();
        old.putByte(started.toString(), ChunkReceipts.STARTED);
        old.putByte(complete.toString(), ChunkReceipts.COMPLETE);
        ChunkReceipts receipts = new ChunkReceipts();
        receipts.deserializeNBT(old);
        helper.assertTrue(receipts.resumeCursor(started, HASH, 0).decision() == RecoveryValidation.Decision.PAUSE_UNCERTAIN,
                "Legacy STARTED has no progress proof");
        helper.assertTrue(receipts.resumeCursor(complete, HASH, 0).decision() == RecoveryValidation.Decision.STOP_TERMINAL,
                "Legacy COMPLETE still prevents replay");
        UUID fresh = UUID.randomUUID();
        receipts.beginPhase(fresh, HASH, 0, -4, 20);
        helper.assertTrue(restored(receipts).resumeCursor(fresh, HASH, 0).decision() == RecoveryValidation.Decision.RESUME,
                "Explicit new operations can establish their own current revision after legacy migration");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_receipt_runner_missing", timeoutTicks = 150)
    public static void realRunnerPausesWhenRestartHasNoChunkReceipt(GameTestHelper helper) throws IOException {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 4, 3));
        level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        var original = ForgeOperationRunner.submit(level, UUID.randomUUID(), pos, 2, 60, false);
        OperationData active = OperationData.get(level);
        OperationData loaded = OperationData.load(active.save(new CompoundTag()));
        var recovered = loaded.tasks.stream().filter(task -> task.id.equals(original.id)).findFirst().orElseThrow();
        helper.assertTrue(recovered.state == OperationData.State.RECOVERING, "Reload first enters evidence validation");
        // Replace this task only; other tests' operations are neither cleared nor copied.
        active.tasks.set(active.tasks.indexOf(original), recovered);
        active.setDirty();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(recovered.state == OperationData.State.PAUSED_RECOVERY,
                        "Actual server runner rejects restart work lacking a co-saved receipt"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK),
                            "Missing evidence never authorizes destruction of current construction");
                    helper.assertTrue(!ForgeOperationRunner.resume(recovered), "Uncertain recovery cannot be manually replayed as a normal pause");
                    ForgeOperationRunner.cancel(recovered);
                }).thenSucceed();
    }

    private static ChunkReceipts restored(ChunkReceipts original) {
        ChunkReceipts copy = new ChunkReceipts();
        copy.deserializeNBT(original.serializeNBT());
        return copy;
    }
    private RecoveryGameTests() { }
}
