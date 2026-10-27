/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.voxelregion.forge.ChunkPacketQueue;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.forge.NativeSaveTracker;

/** Real native queues plus targeted failure injection, executed only in verification builds. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class NativeFenceGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_fence_test", Comparator.<UUID>naturalOrder());
    private static final String HASH = "native-save-image-fixture";

    @GameTest(template = "empty", batch = "vre_native_fence_image", timeoutTicks = 500)
    public static void terrainAndReceiptReachTheSameNativeImage(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var chunk = level.getChunkAt(pos);
        UUID id = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk.getPos(), 0, id);
        ChunkReceipts receipts = ChunkReceipts.of(chunk);
        int sectionY = pos.getY() >> 4;
        int voxel = (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8);
        receipts.beginPhase(id, HASH, 0, sectionY, sectionY + 1);
        receipts.advanceCursor(id, 0, sectionY, voxel);
        receipts.beforeWrite(id, 0, sectionY, voxel);
        level.setBlock(pos, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        receipts.afterWrite(id, 0, sectionY, voxel + 1);
        receipts.advanceCursor(id, 0, sectionY + 1, 0);
        receipts.completePhase(id, 0, true);
        long sequence = receipts.checkpoint(id).sequence();
        IOWorker storage = (IOWorker) level.getChunkSource().chunkMap.chunkScanner();
        CompletableFuture<Void> saved = NativeCommitFences.saveAfterLighting(level, chunk);
        AtomicReference<CompletableFuture<Optional<CompoundTag>>> read = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(saved.isDone(), "Native save/light future completed"))
                .thenExecute(() -> {
                    saved.join();
                    helper.assertTrue(level.getBrightness(LightLayer.BLOCK, pos) == 15,
                            "Light mailbox consumed the source change before acknowledging its fence");
                    read.set(storage.loadAsync(chunk.getPos()));
                })
                .thenWaitUntil(() -> helper.assertTrue(read.get().isDone(), "Native queued/disk NBT read completed"))
                .thenExecute(() -> {
                    try {
                        CompoundTag image = read.get().join().orElseThrow(() -> new AssertionError("No persisted chunk image"));
                        helper.assertTrue(savedState(image, pos).is(Blocks.GLOWSTONE),
                                "Native image contains the committed terrain");
                        CompoundTag cap = image.getCompound("ForgeCaps").getCompound(RuntimeBridge.PERSISTENCE_NAMESPACE + ":receipts");
                        ChunkReceipts restored = new ChunkReceipts();
                        restored.deserializeNBT(cap);
                        helper.assertTrue(restored.state(id) == ChunkReceipts.COMPLETE
                                        && restored.checkpoint(id).sequence() >= sequence,
                                "The same native image contains completion receipt and progress sequence");
                        helper.assertTrue(restored.resumeCursor(id, HASH, 0).decision() == RecoveryValidation.Decision.STOP_TERMINAL,
                                "Read-back evidence suppresses replay after later construction");
                    } finally {
                        level.getChunkSource().removeRegionTicket(HOLD, chunk.getPos(), 0, id);
                    }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_native_fence_failure", timeoutTicks = 300)
    public static void failedStoreIsNotHiddenBySuccessfulSynchronization(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 2, 1)));
        IOWorker worker = (IOWorker) level.getChunkSource().chunkMap.chunkScanner();
        CompletableFuture<Void> captured;
        try (var capture = NativeSaveTracker.begin(chunk.getPos(), worker)) {
            NativeSaveTracker.stored(worker, chunk.getPos(),
                    CompletableFuture.failedFuture(new IOException("injected store failure before later successful flush")));
            captured = capture.writes();
        }
        helper.assertTrue(captured.isCompletedExceptionally(), "Individual native write failure is retained");
        CompletableFuture<Void> successfulBarrier = worker.synchronize(true);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(successfulBarrier.isDone(), "Subsequent native flush completed"))
                .thenExecute(() -> {
                    successfulBarrier.join();
                    CompletableFuture<Void> combined = captured.thenCompose(ignored -> successfulBarrier);
                    helper.assertTrue(combined.isCompletedExceptionally(),
                            "A successful later synchronize cannot erase an earlier captured store failure");
                    try (var missing = NativeSaveTracker.begin(chunk.getPos(), worker)) {
                        NativeSaveTracker.stored(worker, new ChunkPos(chunk.getPos().x + 1, chunk.getPos().z),
                                CompletableFuture.completedFuture(null));
                        helper.assertTrue(missing.writes().isCompletedExceptionally(),
                                "A save of a different chunk is not acknowledgement of the requested chunk");
                    }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_native_light_fence_contract", timeoutTicks = 1_000_000)
    public static void lightFenceDoesNotForgeFlagsOrReleaseGenerationTickets(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 2, 1)));
        var manager = level.getChunkSource().chunkMap.getDistanceManager();
        int lightLevel = ChunkLevel.byStatus(ChunkStatus.LIGHT);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk.getPos(), 0, holder);
        boolean[] installed = {false}, cleaned = {false};
        boolean originalCorrect = chunk.isLightCorrect();
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            if (!level.getServer().isSameThread()) throw new AssertionError("Ticket fixture cleanup must run on server thread");
            cleaned[0] = true;
            chunk.setLightCorrect(originalCorrect);
            chunk.setUnsaved(true);
            if (installed[0]) manager.removeTicket(TicketType.LIGHT, chunk.getPos(), lightLevel, chunk.getPos());
            level.getChunkSource().removeRegionTicket(HOLD, chunk.getPos(), 0, holder);
        };
        cleanupOnCompletion(helper, cleanup);
        CompletableFuture<Void> initial = NativeCommitFences.light(level, chunk);
        AtomicReference<CompletableFuture<Void>> measured = new AtomicReference<>();
        var wait = new WallClockFenceWait(helper, level);
        helper.startSequence().thenWaitUntil(() -> wait.check(initial.isDone(), "Initial light queue drained",
                        () -> "initial=" + futureDiagnostic(initial)))
                .thenIdle(3)
                .thenWaitUntil(() -> wait.check(!hasLightTicket(manager, chunk.getPos(), lightLevel),
                        "Pre-existing native generation LIGHT ticket has completed", () -> "initial=" + futureDiagnostic(initial)))
                .thenExecute(() -> {
                    initial.join();
                    helper.assertTrue(level.getServer().isSameThread(), "Ticket fixture mutates native bookkeeping only on its owning thread");
                    helper.assertTrue(!hasLightTicket(manager, chunk.getPos(), lightLevel), "Fixture does not appropriate another generation ticket");
                    manager.addTicket(TicketType.LIGHT, chunk.getPos(), lightLevel, chunk.getPos());
                    installed[0] = true;
                    helper.assertTrue(hasLightTicket(manager, chunk.getPos(), lightLevel), "Generation-ticket sentinel installed");
                    chunk.setLightCorrect(false);
                    measured.set(NativeCommitFences.light(level, chunk));
                }).thenWaitUntil(() -> wait.check(measured.get().isDone(), "Read-only completion fence returned",
                        () -> "measured=" + futureDiagnostic(measured.get())))
                .thenIdle(3).thenExecute(() -> {
                    try {
                        measured.get().join();
                        helper.assertTrue(!chunk.isLightCorrect(), "Fence does not manufacture a light-correct flag");
                        helper.assertTrue(hasLightTicket(manager, chunk.getPos(), lightLevel),
                                "Fence does not release a generation-owned LIGHT ticket");
                    } finally {
                        cleanup.run();
                    }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_packet_independent_fences", timeoutTicks = 500)
    public static void slowPacketFenceDoesNotBlockAStableLaterRequest(GameTestHelper helper) {
        var level = helper.getLevel();
        var firstChunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 2, 1)));
        var nextPos = new ChunkPos(firstChunk.getPos().x + 1, firstChunk.getPos().z);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, firstChunk.getPos(), 0, holder);
        level.getChunkSource().addRegionTicket(HOLD, nextPos, 0, holder);
        var secondChunk = level.getChunk(nextPos.x, nextPos.z);
        CompletableFuture<Void> first = ChunkPacketQueue.enqueue(level, firstChunk);
        var request = queuedRequest(ChunkPacketQueue.class, first);
        CompletableFuture<Void> original = requestLight(request);
        CompletableFuture<Void> permit = gateRequestLight(request);
        CompletableFuture<Void> second = ChunkPacketQueue.enqueue(level, secondChunk);
        cleanupOnCompletion(helper, () -> {
            original.whenComplete((ignored, failure) -> {
                if (failure == null) permit.complete(null); else permit.completeExceptionally(failure);
            });
            level.getChunkSource().removeRegionTicket(HOLD, firstChunk.getPos(), 0, holder);
            level.getChunkSource().removeRegionTicket(HOLD, nextPos, 0, holder);
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(second.isDone(), "Stable later request completes behind a delayed packet barrier"))
                .thenExecute(() -> {
                    second.join();
                    helper.assertTrue(!first.isDone(), "The first request retains its own incomplete acknowledgement");
                })
                .thenWaitUntil(() -> helper.assertTrue(original.isDone(), "Original native lighting completed"))
                .thenExecute(() -> { original.join(); permit.complete(null); })
                .thenWaitUntil(() -> helper.assertTrue(first.isDone(), "Delayed request completes after its own barrier opens"))
                .thenExecute(first::join).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_native_fence_stop", timeoutTicks = 100)
    public static void queuedSaveFailsClosedWhenServiceStops(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunk = level.getChunkAt(helper.absolutePos(new BlockPos(1, 2, 1)));
        CompletableFuture<Void> pending = NativeCommitFences.saveAfterLighting(level, chunk);
        NativeCommitFences.stop(level.getServer());
        helper.assertTrue(pending.isCompletedExceptionally(), "Stopping fails pending acknowledgements rather than claiming durability");
        boolean failed = false;
        try { pending.join(); }
        catch (CompletionException expected) { failed = true; }
        helper.assertTrue(failed && NativeCommitFences.pendingCount() == 0, "Stopped queue releases pending bookkeeping");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_native_save_epoch", timeoutTicks = 1_000_000)
    public static void externalEditAfterLightFenceRefreshesTheSavedImage(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var chunk = level.getChunkAt(pos);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk.getPos(), 0, holder);
        level.setBlock(pos, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        CompletableFuture<Void> saved = NativeCommitFences.saveAfterLighting(level, chunk);
        Object request = queuedRequest(NativeCommitFences.class, saved);
        CompletableFuture<Void> oldLight = requestLight(request);
        CompletableFuture<Void> gatedOldLight = gateRequestLight(request);
        long oldRevision = ChunkMutationTracking.revision(chunk);
        MutationAfterLight mutation = new MutationAfterLight(oldLight, gatedOldLight, () -> {
            helper.assertTrue(level.getBrightness(LightLayer.BLOCK, pos) == 15, "Original fence consumed the old emitting state");
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            helper.assertTrue(ChunkMutationTracking.revision(chunk) > oldRevision, "External native edit increments the tracked revision");
        });
        MinecraftForge.EVENT_BUS.register(mutation);
        cleanupOnCompletion(helper, () -> {
            // Release only the artificial test gate if timeout precedes the injected mutation.
            gatedOldLight.completeExceptionally(new IllegalStateException("Epoch fixture finished before its artificial gate opened"));
            level.getChunkSource().removeRegionTicket(HOLD, chunk.getPos(), 0, holder);
            MinecraftForge.EVENT_BUS.unregister(mutation);
        });
        IOWorker storage = (IOWorker) level.getChunkSource().chunkMap.chunkScanner();
        AtomicReference<CompletableFuture<Optional<CompoundTag>>> read = new AtomicReference<>();
        var wait = new WallClockFenceWait(helper, level);
        helper.startSequence()
                .thenWaitUntil(() -> wait.check(mutation.fired, "External edit entered between light acknowledgement and save processing",
                        () -> "oldLight=" + futureDiagnostic(oldLight) + " " + saveDiagnostic(request)))
                .thenExecute(mutation::check)
                .thenWaitUntil(() -> wait.check(saved.isDone(), "Refreshed save completed", () -> saveDiagnostic(request)))
                .thenExecute(() -> {
                    saved.join();
                    helper.assertTrue(requestLight(request) != gatedOldLight,
                            "Revision mismatch queued a new light barrier, even if asynchronous lighting happened to run early");
                    helper.assertTrue(level.getBrightness(LightLayer.BLOCK, pos) == 0, "Opaque replacement has no stale source light");
                    read.set(storage.loadAsync(chunk.getPos()));
                })
                .thenWaitUntil(() -> wait.check(read.get().isDone(), "Updated native chunk NBT returned",
                        () -> "read=" + futureDiagnostic(read.get()) + " " + saveDiagnostic(request)))
                .thenExecute(() -> {
                    try {
                        CompoundTag image = read.get().join().orElseThrow();
                        helper.assertTrue(savedState(image, pos).is(Blocks.STONE), "Serialized terrain contains the external replacement");
                        helper.assertTrue(savedBlockLight(image, pos) == 0, "The same image contains replacement-era block light");
                        ChunkReceipts restored = new ChunkReceipts();
                        restored.deserializeNBT(image.getCompound("ForgeCaps").getCompound(RuntimeBridge.PERSISTENCE_NAMESPACE + ":receipts"));
                        helper.assertTrue(restored.mutationRevision() >= oldRevision + 1, "Same-image mutation evidence includes the external edit");
                    } finally {
                        level.getChunkSource().removeRegionTicket(HOLD, chunk.getPos(), 0, holder);
                        MinecraftForge.EVENT_BUS.unregister(mutation);
                    }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_native_packet_epoch", timeoutTicks = 300)
    public static void externalEditAfterPacketLightFenceRequiresANewBarrier(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var chunk = level.getChunkAt(pos);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk.getPos(), 0, holder);
        CompletableFuture<Void> packet = ChunkPacketQueue.enqueue(level, chunk);
        Object request = queuedRequest(ChunkPacketQueue.class, packet);
        CompletableFuture<Void> oldLight = requestLight(request);
        CompletableFuture<Void> gatedOldLight = gateRequestLight(request);
        MutationAfterLight mutation = new MutationAfterLight(oldLight, gatedOldLight,
                () -> level.setBlock(pos, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        MinecraftForge.EVENT_BUS.register(mutation);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(mutation.fired, "External edit followed the original packet light barrier"))
                .thenExecute(mutation::check)
                .thenWaitUntil(() -> helper.assertTrue(packet.isDone(), "Packet queue completed its refreshed barrier"))
                .thenExecute(() -> {
                    try {
                        packet.join();
                        helper.assertTrue(requestLight(request) != gatedOldLight, "Packet completion cannot reuse an earlier revision's light barrier");
                        helper.assertTrue(level.getBrightness(LightLayer.BLOCK, pos) == 15, "Packet acknowledgement waits for the replacement source");
                    } finally {
                        level.getChunkSource().removeRegionTicket(HOLD, chunk.getPos(), 0, holder);
                        MinecraftForge.EVENT_BUS.unregister(mutation);
                    }
                }).thenSucceed();
    }

    /** The adapter consumes completed barriers at NORMAL END priority. Inject a real edit immediately before it. */
    public static final class MutationAfterLight {
        private final CompletableFuture<Void> oldLight;
        private final CompletableFuture<Void> gatedOldLight;
        private final Runnable mutation;
        private boolean fired;
        private Throwable failure;
        MutationAfterLight(CompletableFuture<Void> oldLight, CompletableFuture<Void> gatedOldLight, Runnable mutation) {
            this.oldLight = oldLight; this.gatedOldLight = gatedOldLight; this.mutation = mutation;
        }
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || fired || !oldLight.isDone()) return;
            fired = true;
            try { oldLight.join(); mutation.run(); }
            catch (RuntimeException | Error problem) { failure = problem; }
            finally {
                if (failure == null) gatedOldLight.complete(null);
                else gatedOldLight.completeExceptionally(failure);
                MinecraftForge.EVENT_BUS.unregister(this);
            }
        }
        void check() { if (failure != null) throw new AssertionError("External edit fixture failed", failure); }
    }

    private static Object queuedRequest(Class<?> owner, CompletableFuture<Void> result) {
        try {
            Field pending = owner.getDeclaredField("pending"); pending.setAccessible(true);
            for (Object request : (Iterable<?>) pending.get(null)) {
                Field future = request.getClass().getDeclaredField("result"); future.setAccessible(true);
                if (future.get(request) == result) return request;
            }
            throw new AssertionError("Newly enqueued test request is missing");
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect test fence state", failure); }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> requestLight(Object request) {
        try {
            Field light = request.getClass().getDeclaredField("light"); light.setAccessible(true);
            return (CompletableFuture<Void>) light.get(request);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect test light barrier", failure); }
    }

    /** Hold queue consumption until the test injects its edit, avoiding a worker completion race between event priorities. */
    private static CompletableFuture<Void> gateRequestLight(Object request) {
        try {
            Field light = request.getClass().getDeclaredField("light"); light.setAccessible(true);
            CompletableFuture<Void> gate = new CompletableFuture<>();
            light.set(request, gate);
            return gate;
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot gate the test barrier", failure); }
    }

    private static int savedBlockLight(CompoundTag image, BlockPos pos) {
        ListTag sections = image.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (section.getByte("Y") != pos.getY() >> 4) continue;
            byte[] data = section.getByteArray("BlockLight");
            return data.length == 0 ? 0 : new DataLayer(data).get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
        throw new AssertionError("Saved light section missing");
    }

    /** GameTestServer runs without 50 ms tick pacing. Native executor waits therefore use an explicit wall-clock bound. */
    private static final class WallClockFenceWait {
        private static final long LIMIT_NANOS = 30_000_000_000L;
        private final GameTestHelper helper;
        private final ProcessorMailbox<Runnable> mailbox;
        private final long started = System.nanoTime();
        private long stageStarted = started;
        private String stage = "not started";
        private Supplier<String> details = () -> "";
        private boolean timeoutScheduled;

        WallClockFenceWait(GameTestHelper helper, ServerLevel level) {
            this.helper = helper;
            this.mailbox = nativeLightMailbox(level);
            cleanupOnCompletion(helper, () -> RuntimeBridge.LOGGER.info("VRE_NATIVE_FENCE_WALLCLOCK {}", diagnostic()));
        }

        void check(boolean ready, String nextStage, Supplier<String> nextDetails) {
            if (!stage.equals(nextStage)) {
                stage = nextStage;
                stageStarted = System.nanoTime();
            }
            details = nextDetails;
            if (System.nanoTime() - started >= LIMIT_NANOS) {
                if (!timeoutScheduled) {
                    timeoutScheduled = true;
                    String failure = "Native fence exceeded 30-second wall-clock deadline: " + diagnostic();
                    // Failure must be raised inside GameTestInfo.tickInternal so its completion
                    // listeners release tickets and the batch runner advances on every exit path.
                    helper.runAfterDelay(1, () -> helper.fail(failure));
                }
                helper.assertTrue(false, "Native fence wall-clock deadline reached; failure and cleanup scheduled");
            }
            if (ready) return;
            helper.assertTrue(false, "Waiting for " + stage + " under the 30-second wall-clock deadline");
        }

        private String diagnostic() {
            long now = System.nanoTime();
            return "elapsedMs=" + (now - started) / 1_000_000 + " ticks=" + helper.getTick()
                    + " stage=" + stage + " stageMs=" + (now - stageStarted) / 1_000_000
                    + " lightMailbox=" + mailbox + " lightMailboxSize=" + mailbox.size()
                    + " " + details.get();
        }

        @SuppressWarnings("unchecked")
        private static ProcessorMailbox<Runnable> nativeLightMailbox(ServerLevel level) {
            try {
                for (Field field : ThreadedLevelLightEngine.class.getDeclaredFields()) {
                    if (!ProcessorMailbox.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    return (ProcessorMailbox<Runnable>) field.get(level.getChunkSource().getLightEngine());
                }
                throw new IllegalStateException("Native light mailbox diagnostic unavailable");
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native light mailbox diagnostic unavailable", failure); }
        }
    }

    private static String saveDiagnostic(Object request) {
        try {
            Field epoch = request.getClass().getDeclaredField("epoch"); epoch.setAccessible(true);
            Field chunkField = request.getClass().getDeclaredField("chunk"); chunkField.setAccessible(true);
            var chunk = (net.minecraft.world.level.chunk.LevelChunk) chunkField.get(request);
            Field pending = NativeCommitFences.class.getDeclaredField("pending"); pending.setAccessible(true);
            Field active = NativeCommitFences.class.getDeclaredField("active"); active.setAccessible(true);
            Field batch = NativeCommitFences.class.getDeclaredField("batch"); batch.setAccessible(true);
            var batchFuture = (CompletableFuture<?>) batch.get(null);
            return "epoch=" + epoch.getLong(request) + "/" + ChunkMutationTracking.revision(chunk)
                    + " light=" + futureDiagnostic(requestLight(request))
                    + " queued=" + ((java.util.Collection<?>) pending.get(null)).contains(request)
                    + " active=" + ((java.util.Collection<?>) active.get(null)).contains(request)
                    + " batch=" + (batchFuture == null ? "none" : futureDiagnostic(batchFuture))
                    + " fullMspt=" + org.voxelregion.forge.ServerTickTimings.averageMillis();
        } catch (ReflectiveOperationException failure) { return "diagnostic unavailable: " + failure; }
    }
    private static String futureDiagnostic(CompletableFuture<?> future) {
        return future.isCompletedExceptionally() ? "failed" : future.isDone() ? "complete" : "pending";
    }

    private static BlockState savedState(CompoundTag image, BlockPos pos) {
        ListTag sections = image.getList("sections", Tag.TAG_COMPOUND);
        int sectionY = pos.getY() >> 4;
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (section.getByte("Y") != sectionY) continue;
            var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
            PalettedContainer<BlockState> states = codec.parse(NbtOps.INSTANCE, section.getCompound("block_states"))
                    .getOrThrow(false, message -> { throw new AssertionError(message); });
            return states.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
        throw new AssertionError("Saved section missing");
    }

    /** Sequence finally blocks are skipped on timeout; native LIGHT tickets must still be released. */
    private static void cleanupOnCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) {
                if (field.getType() != GameTestInfo.class) continue;
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) {}
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new AssertionError("GameTest completion listener unavailable");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** Inspect test-only native bookkeeping by type, so field obfuscation names are irrelevant. */
    private static boolean hasLightTicket(DistanceManager manager, ChunkPos position, int level) {
        try {
            for (Field field : DistanceManager.class.getDeclaredFields()) {
                if (!Long2ObjectMap.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = ((Long2ObjectMap<?>) field.get(manager)).get(position.toLong());
                if (!(value instanceof Iterable<?> tickets)) continue;
                for (Object candidate : tickets) {
                    if (candidate instanceof Ticket<?> ticket && ticket.getType() == TicketType.LIGHT
                            && ticket.getTicketLevel() == level) return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect native test ticket", failure); }
    }
    private NativeFenceGameTests() { }
}
