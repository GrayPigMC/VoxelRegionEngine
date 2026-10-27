/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.FrozenOcclusionCapture;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class FrozenCaptureGameTests {
    @GameTest(template = "empty", batch = "vre_frozen_capture", timeoutTicks = 400)
    public static void boundedCaptureSealsDetachedMacroOcclusion(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        BlockPos obstacle = fixture.minimum.offset(12, 8, 8);
        fixture.level.setBlock(obstacle, Blocks.BEDROCK.defaultBlockState(), 18);
        AtomicLong policyEpoch = new AtomicLong(1);
        AtomicInteger calls = new AtomicInteger();
        var session = session(fixture, 256, 4, policy(policyEpoch, calls));
        scan(helper, fixture, session, 73);
        helper.assertTrue(session.state() == FrozenOcclusionCapture.State.READY_TO_FREEZE && session.capturedChunks() == 2,
                "Both requested chunks are captured before sealing");
        helper.assertTrue(fixture.level.getBlockState(obstacle).is(Blocks.BEDROCK), "Read-only capture does not alter blocker");
        var sealed = session.sealSnapshot((key, revision) -> current(fixture.level, key, revision));
        helper.assertTrue(sealed.blockers().size() == 1 && sealed.revisions().size() == 2,
                "Detached snapshot contains merged macro boxes and numeric chunk epochs");
        int beforeCompile = calls.get();
        var frozen = sealed.compileIndex();
        helper.assertTrue(calls.get() == beforeCompile, "Pure BVH compilation never re-queries world blocker policy");
        IntBox expectedMacro = new IntBox(fixture.minimum.getX() + 12, fixture.minimum.getY() + 8, fixture.minimum.getZ() + 8,
                fixture.minimum.getX() + 16, fixture.minimum.getY() + 12, fixture.minimum.getZ() + 12);
        helper.assertTrue(sealed.blockers().get(0).equals(expectedMacro), "One exact blocker expands only to its 4-block macro cell");
        helper.assertTrue(frozen.occlusion().classify(voxel(fixture.minimum.offset(24, 8, 8))) == Coverage.INSIDE,
                "Frozen AXIS_6 blocker shadows voxels behind it across chunk boundary");
        helper.assertTrue(frozen.occlusion().classify(voxel(fixture.minimum.offset(8, 8, 8))) == Coverage.OUTSIDE
                        && frozen.occlusion().classify(voxel(fixture.minimum.offset(24, 8, 0))) == Coverage.OUTSIDE,
                "Frozen shadow preserves front and transverse exclusion");
        fixture.level.setBlock(obstacle, Blocks.AIR.defaultBlockState(), 18);
        helper.assertTrue(frozen.occlusion().classify(voxel(fixture.minimum.offset(24, 8, 8))) == Coverage.INSIDE,
                "Post-seal world changes do not silently recapture original blockers");
        helper.assertTrue(!frozen.validate((key, revision) -> current(fixture.level, key, revision)),
                "Snapshot exposes revision mismatch for caller recovery decisions");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_frozen_stale", timeoutTicks = 400)
    public static void captureRejectsWorldAndPolicyChanges(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        BlockPos obstacle = fixture.minimum.offset(12, 8, 8);
        fixture.level.setBlock(obstacle, Blocks.BEDROCK.defaultBlockState(), 18);
        AtomicLong epoch = new AtomicLong(3);
        var first = session(fixture, 256, 4, policy(epoch, new AtomicInteger()));
        acceptRequested(fixture.level, first);
        first.step(11, Long.MAX_VALUE);
        fixture.level.setBlock(fixture.minimum.offset(2, 2, 2), Blocks.STONE.defaultBlockState(), 18);
        helper.assertTrue(first.step(11, Long.MAX_VALUE).state() == FrozenOcclusionCapture.State.STALE,
                "Chunk mutation between scan slices fails closed");

        var changedPolicy = session(fixture, 256, 4, policy(epoch, new AtomicInteger()));
        acceptRequested(fixture.level, changedPolicy);
        changedPolicy.step(11, Long.MAX_VALUE);
        epoch.incrementAndGet();
        helper.assertTrue(changedPolicy.step(11, Long.MAX_VALUE).state() == FrozenOcclusionCapture.State.STALE,
                "Blocker tag epoch change between slices fails closed");

        var observed = session(fixture, 256, 4, policy(epoch, new AtomicInteger()));
        ChunkKey capturedKey = observed.nextChunk();
        acceptRequested(fixture.level, observed);
        while (observed.state() == FrozenOcclusionCapture.State.SCANNING) observed.step(127, Long.MAX_VALUE);
        helper.assertTrue(observed.state() == FrozenOcclusionCapture.State.NEED_CHUNK,
                "Completed chunk releases its active reference before next chunk");
        LevelChunk changed = fixture.level.getChunk(capturedKey.x(), capturedKey.z());
        fixture.level.setBlock(fixture.minimum.offset(3, 3, 3), Blocks.STONE.defaultBlockState(), 18);
        observed.observeMutation(capturedKey, revision(changed));
        helper.assertTrue(observed.state() == FrozenOcclusionCapture.State.STALE,
                "Mutation subscription invalidates an earlier completed chunk");
        helper.assertTrue(fixture.level.getBlockState(obstacle).is(Blocks.BEDROCK), "All failed captures preserve obstacle terrain");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_frozen_limits", timeoutTicks = 400)
    public static void captureLimitsAndOptionalWholeClaimBarrier(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        BlockPos first = fixture.minimum.offset(1, 1, 1), second = fixture.minimum.offset(9, 9, 9);
        fixture.level.setBlock(first, Blocks.BEDROCK.defaultBlockState(), 18);
        fixture.level.setBlock(second, Blocks.BEDROCK.defaultBlockState(), 18);
        var policy = policy(new AtomicLong(1), new AtomicInteger());
        var limited = session(fixture, 1, 4, policy);
        scan(helper, fixture, limited, 91);
        helper.assertTrue(limited.state() == FrozenOcclusionCapture.State.MEMORY_LIMIT,
                "Two separated macro cells exceed the one-box cap without writes");
        helper.assertTrue(fixture.level.getBlockState(first).is(Blocks.BEDROCK)
                        && fixture.level.getBlockState(second).is(Blocks.BEDROCK), "Memory cap preserves blockers");

        var chunkLimited = session(fixture, 256, 1, policy);
        scan(helper, fixture, chunkLimited, 91);
        helper.assertTrue(chunkLimited.state() == FrozenOcclusionCapture.State.MEMORY_LIMIT,
                "Numeric chunk manifest is bounded independently of obstacle count");

        var barrier = session(fixture, 256, 4, policy);
        ChunkKey key = barrier.nextChunk();
        LevelChunk chunk = fixture.level.getChunk(key.x(), key.z());
        barrier.recordWholeChunkBarrier(chunk, revision(chunk));
        scan(helper, fixture, barrier, 91);
        var sealed = barrier.sealSnapshot((position, captured) -> current(fixture.level, position, captured));
        helper.assertTrue(sealed.blockers().stream().anyMatch(box -> box.minX() == fixture.minimum.getX()
                        && box.maxX() == fixture.minimum.getX() + 16 && box.minY() == fixture.minimum.getY()
                        && box.maxY() == fixture.minimum.getY() + 16), "Whole-chunk barrier is added only through explicit opt-in API");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_frozen_merge", timeoutTicks = 400)
    public static void adjacentCaptureBoxesMergeOnlyWithIdenticalShadows(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        for (int x = 0; x < 32; x++) for (int z = 0; z < 16; z++)
            fixture.level.setBlock(fixture.minimum.offset(x, 0, z), Blocks.BEDROCK.defaultBlockState(), 18);
        var policy = policy(new AtomicLong(1), new AtomicInteger());
        var sameDirection = new FrozenOcclusionCapture.Session(fixture.level, new BoxVolume(fixture.bounds),
                Point3.of(fixture.minimum.getX() + 16.5, fixture.minimum.getY() + 40.5, fixture.minimum.getZ() + 8.5),
                FrozenOcclusion.Mode.AXIS_6, 4, 1, 4, 2_097_152, policy, FrozenCaptureGameTests::revision);
        scan(helper, fixture, sameDirection, 83);
        helper.assertTrue(sameDirection.state() == FrozenOcclusionCapture.State.READY_TO_FREEZE,
                "Adjacent same-direction bedrock strips merge before applying the one-box cap");
        var sealed = sameDirection.sealSnapshot((key, revision) -> current(fixture.level, key, revision));
        helper.assertTrue(sealed.blockers().size() == 1 && sealed.revisions().size() == 2
                        && sealed.blockers().get(0).equals(new IntBox(fixture.minimum.getX(), fixture.minimum.getY(), fixture.minimum.getZ(),
                        fixture.minimum.getX() + 32, fixture.minimum.getY() + 4, fixture.minimum.getZ() + 16)),
                "Streaming merge preserves the exact macro occupancy and both independent chunk epochs");
        var differentDirections = session(fixture, 1, 4, policy);
        scan(helper, fixture, differentDirections, 83);
        helper.assertTrue(differentDirections.state() == FrozenOcclusionCapture.State.MEMORY_LIMIT,
                "Geometrically adjacent strips with different AXIS_6 directions must not merge to evade the cap");
        helper.succeed();
    }

    private static FrozenOcclusionCapture.BlockerPolicy policy(AtomicLong epoch, AtomicInteger calls) {
        return new FrozenOcclusionCapture.BlockerPolicy() {
            @Override public boolean blocks(net.minecraft.world.level.block.state.BlockState state) {
                calls.incrementAndGet();
                return state.is(Blocks.BEDROCK);
            }
            @Override public long revision() { return epoch.get(); }
        };
    }

    private static FrozenOcclusionCapture.Session session(Fixture fixture, int boxes, int chunks,
                                                          FrozenOcclusionCapture.BlockerPolicy policy) {
        return new FrozenOcclusionCapture.Session(fixture.level, new BoxVolume(fixture.bounds),
                Point3.of(fixture.minimum.getX() + 4.5, fixture.minimum.getY() + 8.5, fixture.minimum.getZ() + 8.5),
                FrozenOcclusion.Mode.AXIS_6, 4, boxes, chunks, 2_097_152, policy, FrozenCaptureGameTests::revision);
    }

    private static void scan(GameTestHelper helper, Fixture fixture, FrozenOcclusionCapture.Session session, int budget) {
        int slices = 0;
        while (session.state() == FrozenOcclusionCapture.State.NEED_CHUNK || session.state() == FrozenOcclusionCapture.State.SCANNING) {
            if (session.state() == FrozenOcclusionCapture.State.NEED_CHUNK) acceptRequested(fixture.level, session);
            var progress = session.step(budget, Long.MAX_VALUE);
            helper.assertTrue(progress.visited() <= budget, "Capture respects per-slice visit allowance");
            helper.assertTrue(++slices < 1000, "Capture makes bounded progress");
        }
    }

    private static void acceptRequested(ServerLevel level, FrozenOcclusionCapture.Session session) {
        ChunkKey key = session.nextChunk();
        LevelChunk chunk = level.getChunk(key.x(), key.z());
        session.acceptChunk(chunk, revision(chunk));
    }

    private static long revision(LevelChunk chunk) { return ChunkReceipts.of(chunk).mutationRevision(); }

    private static boolean current(ServerLevel level, ChunkKey key, long expected) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
        return chunk != null && ChunkReceipts.of(chunk).trackingValid() && revision(chunk) == expected;
    }

    private static Fixture fixture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(8, 0, 8));
        BlockPos minimum = new BlockPos((origin.getX() >> 4) << 4, (level.getMaxSection() - 2) << 4,
                (origin.getZ() >> 4) << 4);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 32; x++)
            level.setBlock(minimum.offset(x, y, z), Blocks.AIR.defaultBlockState(), 18);
        IntBox bounds = new IntBox(minimum.getX(), minimum.getY(), minimum.getZ(),
                minimum.getX() + 32, minimum.getY() + 16, minimum.getZ() + 16);
        return new Fixture(level, minimum, bounds);
    }

    private static IntBox voxel(BlockPos position) {
        return new IntBox(position.getX(), position.getY(), position.getZ(), position.getX() + 1, position.getY() + 1, position.getZ() + 1);
    }

    private record Fixture(ServerLevel level, BlockPos minimum, IntBox bounds) {}
    private FrozenCaptureGameTests() {}
}
