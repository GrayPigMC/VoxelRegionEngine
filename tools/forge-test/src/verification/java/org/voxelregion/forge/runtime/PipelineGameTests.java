/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.propagation.FrozenOcclusion;

/** Real multi-chunk writes with deliberately delayed native light/save acknowledgement. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PipelineGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_pipeline_fixture", Comparator.<UUID>naturalOrder());
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", batch = "vre_pipeline_phase_and_overlap", timeoutTicks = 2000)
    public static void multipleChunksPipelineButPhaseAndOverlapWaitForAcknowledgement(GameTestHelper helper) throws IOException {
        Fixture f = fixture(helper, 2200);
        AtomicReference<OperationData.Task> overlap = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> pendingPhase(helper, f))
                .thenExecute(() -> {
                    var progress = ForgeOperationRunner.progress(f.task);
                    helper.assertTrue(progress.pendingLighting() >= 2 && progress.pendingSaves() >= 2,
                            "Public progress exposes multiple queued native stages");
                    helper.assertTrue(f.task.changed == 3 && f.task.finishedChunks == 1,
                            "Several chunks finish writes while only the bootstrap phase has acknowledgement");
                    helper.assertTrue(ForgeOperationRunner.retainedChunks() <= AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get(),
                            "Current and submitted tickets share the global cap");
                    helper.assertTrue(f.gate.releaseOneExcept(new ChunkPos(f.center)), "An independently gated noncentral chunk is available");
                })
                .thenWaitUntil(() -> helper.assertTrue(f.task.finishedChunks >= 2, "One queued chunk acknowledgement is independently drained"))
                .thenExecute(() -> {
                    helper.assertTrue(f.task.phase == 1 && f.task.unconfirmedFences > 0 && f.task.state != OperationData.State.WRITTEN,
                            "One acknowledgement cannot advance a phase with other pending chunks");
                    f.level.setBlock(f.center, Blocks.GOLD_BLOCK.defaultBlockState(), FLAGS);
                    try {
                        var next = ForgeOperationRunner.submit(f.level, UUID.randomUUID(), f.center, 1, 0, false);
                        overlap.set(next); f.tasks.add(next);
                    } catch (IOException failure) { throw new AssertionError(failure); }
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    helper.assertTrue(overlap.get().changed == 0 && f.level.getBlockState(f.center).is(Blocks.GOLD_BLOCK),
                            "Another operation cannot enter a chunk retained by an unacknowledged completion");
                    f.gate.open();
                })
                .thenWaitUntil(() -> helper.assertTrue(f.task.state == OperationData.State.WRITTEN
                        && overlap.get().state == OperationData.State.WRITTEN, "Both operations finish after the retained tickets are released"))
                .thenExecute(() -> {
                    helper.assertTrue(f.task.changed == 3 && overlap.get().changed == 1,
                            "The completed inner phase does not erase later construction; only the new operation removes it");
                    helper.assertTrue(f.task.unconfirmedFences == 0 && overlap.get().unconfirmedFences == 0,
                            "Completion has no outstanding acknowledgement markers");
                    helper.assertTrue(ForgeOperationRunner.progress(f.task).retainedChunks() == 0,
                            "Completed task releases all pipeline reservations");
                    f.close();
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_pipeline_cancel_and_recovery", timeoutTicks = 2000)
    public static void cancellationDrainsEveryChunkAndUnconfirmedReloadCannotSkipTerrain(GameTestHelper helper) throws IOException {
        Fixture f = fixture(helper, 2240);
        long[] changed = {0};
        helper.startSequence()
                .thenWaitUntil(() -> pendingPhase(helper, f))
                .thenExecute(() -> {
                    ListTag list = new ListTag(); list.add(f.task.save());
                    CompoundTag image = new CompoundTag(); image.put("operations", list);
                    OperationData restored = OperationData.load(image);
                    var uncertain = restored.tasks.get(0);
                    helper.assertTrue(uncertain.state == OperationData.State.PAUSED_RECOVERY && uncertain.unconfirmedFences >= 2,
                            "Saved advanced cursors with unconfirmed chunks fail closed on restart");
                    IntentJournal.reconcile(f.level, restored);
                    helper.assertTrue(restored.tasks.stream().filter(task -> task.id.equals(f.task.id)).findFirst().orElseThrow().state
                                    == OperationData.State.PAUSED_RECOVERY,
                            "A progress-only journal cannot erase pipeline uncertainty");
                    helper.assertTrue(!ForgeOperationRunner.resume(uncertain), "Uncertain recovery cannot be forced through resume");
                    for (int i = 0; i < 1000; i++) f.task.excludedChunks.add(ChunkPos.asLong(25000 + i, 25000));
                    f.task.skippedUnloaded = 1000;
                    changed[0] = f.task.changed;
                    ForgeOperationRunner.cancel(f.task);
                    helper.assertTrue(f.task.unconfirmedFences >= 2 && !f.task.excludedChunks.isEmpty(),
                            "Cancellation retains outstanding work rather than discarding its completion bookkeeping");
                    helper.assertTrue(ForgeOperationRunner.progress(f.task).pendingSaves() >= 2,
                            "A terminal control state still reports pending native saves");
                })
                .thenIdle(4)
                .thenExecute(() -> {
                    helper.assertTrue(f.task.changed == changed[0] && f.task.phase == 1,
                            "Cancellation stops new writes and phase advancement while acknowledgements are delayed");
                    f.gate.open();
                })
                .thenWaitUntil(() -> helper.assertTrue(f.task.unconfirmedFences == 0, "Every submitted chunk drains after cancellation"))
                .thenExecute(() -> {
                    helper.assertTrue(f.task.state == OperationData.State.CANCELLED && f.task.changed == changed[0]
                                    && f.task.phase == 1 && ForgeOperationRunner.progress(f.task).retainedChunks() == 0,
                            "Drain preserves cancellation and releases every ticket");
                    helper.assertTrue(f.task.excludedChunks.isEmpty() && f.task.skippedUnloaded == 1000,
                            "Terminal memory drops obsolete exclusion sets but retains historical counts");
                    f.close();
                }).thenSucceed();
    }

    private static void pendingPhase(GameTestHelper helper, Fixture f) {
        helper.assertTrue(f.gate.failure == null, "Native fence gate installed without reflection failure: " + f.gate.failure);
        helper.assertTrue(f.task.phase == 1 && f.task.state == OperationData.State.SETTLING && f.task.unconfirmedFences >= 2
                        && f.gate.permits.size() >= 2,
                "Second shell writes multiple chunks before waiting for the phase barrier: " + ForgeOperationRunner.status(f.task).getString());
    }

    private static Fixture fixture(GameTestHelper helper, int chunkX) throws IOException {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_flat")));
        helper.assertTrue(level != null, "Dedicated flat fixture dimension exists");
        UUID holder = UUID.randomUUID();
        List<ChunkPos> chunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ChunkPos pos = new ChunkPos(chunkX + i, 2200); chunks.add(pos);
            level.getChunkSource().addRegionTicket(HOLD, pos, 0, holder);
            level.getChunk(pos.x, pos.z);
            level.setBlock(new BlockPos(pos.getMinBlockX() + 8, 64, pos.getMinBlockZ() + 8), Blocks.STONE.defaultBlockState(), FLAGS);
        }
        BlockPos center = new BlockPos(chunks.get(1).getMinBlockX() + 8, 64, chunks.get(1).getMinBlockZ() + 8);
        var shape = new RegionGeometry.Box(new IntBox(chunks.get(0).getMinBlockX() + 8, 64, center.getZ(),
                chunks.get(2).getMinBlockX() + 9, 65, center.getZ() + 1));
        var progression = AdapterConfig.PROGRESSION.get(); var propagation = AdapterConfig.PROPAGATION.get();
        int width = AdapterConfig.SHELL_WIDTH.get(); double damage = AdapterConfig.ENTITY_DAMAGE.get();
        OperationData.Task task;
        try {
            AdapterConfig.PROGRESSION.set(AdapterConfig.Progression.CENTER_OUT);
            AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.NONE);
            AdapterConfig.SHELL_WIDTH.set(32); AdapterConfig.ENTITY_DAMAGE.set(0.0);
            task = LegacyShellFixtureGameTests.submitGeometry(level, UUID.randomUUID(), shape,
                    new ForgeRegionEngine.Options(0, false, true));
        } finally {
            AdapterConfig.PROGRESSION.set(progression); AdapterConfig.PROPAGATION.set(propagation);
            AdapterConfig.SHELL_WIDTH.set(width); AdapterConfig.ENTITY_DAMAGE.set(damage);
        }
        Fixture fixture = new Fixture(level, chunks, holder, center, task);
        cleanupOnCompletion(helper, fixture);
        MinecraftForge.EVENT_BUS.register(fixture.gate);
        return fixture;
    }

    /** Delay only these chunks and only after the initial bootstrap, preserving real native lighting and writes. */
    public static final class Gate {
        private final Fixture fixture;
        private final Map<Object, CompletableFuture<Void>> permits = new IdentityHashMap<>();
        private boolean open;
        private Throwable failure;
        Gate(Fixture fixture) { this.fixture = fixture; }
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || open || fixture.task.phase < 1) return;
            try {
                for (Object request : (Iterable<?>) field(NativeCommitFences.class, "pending").get(null)) {
                    if (permits.containsKey(request)) continue;
                    if (field(request.getClass(), "level").get(request) != fixture.level) continue;
                    LevelChunk chunk = (LevelChunk) field(request.getClass(), "chunk").get(request);
                    if (!fixture.chunks.contains(chunk.getPos())) continue;
                    @SuppressWarnings("unchecked") CompletableFuture<Void> original = (CompletableFuture<Void>) field(request.getClass(), "light").get(request);
                    var permit = new CompletableFuture<Void>();
                    permits.put(request, permit);
                    field(request.getClass(), "light").set(request, CompletableFuture.allOf(original, permit));
                }
            } catch (ReflectiveOperationException problem) { failure = problem; open(); }
        }
        boolean releaseOneExcept(ChunkPos excluded) {
            try {
                for (var entry : permits.entrySet()) {
                    LevelChunk chunk = (LevelChunk) field(entry.getKey().getClass(), "chunk").get(entry.getKey());
                    if (!chunk.getPos().equals(excluded)) { entry.getValue().complete(null); return true; }
                }
                return false;
            } catch (ReflectiveOperationException problem) { failure = problem; return false; }
        }
        void open() { open = true; for (var permit : permits.values()) permit.complete(null); MinecraftForge.EVENT_BUS.unregister(this); }
    }
    private static final class Fixture implements AutoCloseable {
        final ServerLevel level; final List<ChunkPos> chunks; final UUID holder; final BlockPos center;
        final OperationData.Task task; final List<OperationData.Task> tasks = new ArrayList<>(); final Gate gate;
        boolean closed;
        Fixture(ServerLevel level, List<ChunkPos> chunks, UUID holder, BlockPos center, OperationData.Task task) {
            this.level = level; this.chunks = chunks; this.holder = holder; this.center = center; this.task = task;
            tasks.add(task); gate = new Gate(this);
        }
        public void close() {
            if (closed) return; closed = true;
            gate.open();
            for (var operation : tasks) if (!operation.terminal()) ForgeOperationRunner.cancel(operation);
            for (var chunk : chunks) level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, holder);
        }
    }
    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void cleanupOnCompletion(GameTestHelper helper, Fixture fixture) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) {}
                    public void testPassed(GameTestInfo info) { fixture.close(); }
                    public void testFailed(GameTestInfo info) { fixture.close(); }
                });
                return;
            }
            throw new AssertionError("GameTest completion listener unavailable");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private PipelineGameTests() {}
}
