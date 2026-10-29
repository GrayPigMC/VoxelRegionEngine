/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.IdentityHashMap;
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
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.voxelregion.forge.NativeCommitFences;

/** Real tick-runner tests; native save futures, rather than global cursor values, fence each shell. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class OperationWaveGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_wave_test", Comparator.<UUID>naturalOrder());
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", batch = "vre_wave_order", timeoutTicks = 2400)
    public static void centerPrecedesOuterShellAndLiveEditsSurvive(GameTestHelper helper) throws IOException {
        Fixture fixture = fixture(helper, 1600);
        OperationData.Task task = fixture.task;
        helper.startSequence()
                .thenWaitUntil(() -> firstShellSettling(helper, fixture))
                .thenExecute(() -> {
                    helper.assertTrue(fixture.level.getBlockState(fixture.far).is(Blocks.STONE),
                            "The outer shell still exists when the central shell is committed");
                    var receipt = ChunkReceipts.of(fixture.level.getChunkAt(fixture.center)).checkpoint(task.id);
                    helper.assertTrue(receipt != null && receipt.workingPhase() < 0 && receipt.completedThroughPhase() == 0,
                            "The first native chunk receipt records a completed shell before the edit");
                    // Ordinary native edits between live phases must be adopted for
                    // the next shell, while the completed inner sphere is never replayed.
                    fixture.level.setBlock(fixture.center, Blocks.GOLD_BLOCK.defaultBlockState(), FLAGS);
                    fixture.level.setBlock(fixture.far, Blocks.DIAMOND_BLOCK.defaultBlockState(), FLAGS);
                    fixture.gate.open();
                })
                .thenWaitUntil(() -> helper.assertTrue(task.state == OperationData.State.WRITTEN,
                        "All four shells finish without an erroneous recovery pause: " + ForgeOperationRunner.status(task).getString()))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(fixture.level.getBlockState(fixture.center).is(Blocks.GOLD_BLOCK),
                                "New construction inside an already completed shell is retained");
                        helper.assertTrue(fixture.level.getBlockState(fixture.far).isAir(),
                                "The later shell removes the current native outer block");
                        helper.assertTrue(fixture.level.getBlockState(fixture.outside).is(Blocks.EMERALD_BLOCK),
                                "The operation leaves blocks outside the sphere untouched");
                        helper.assertTrue(task.phase == 4 && task.changed == 2 && task.finishedChunks == 4,
                                "Non-overlapping shells visit the chunk four times and change each selected fixture block once");
                        helper.assertTrue(!hasRunnerEntry("HELD", task.id) && !hasRunnerEntry("COMMITTING", task.id),
                                "Completed waves release their native save future and ticket");
                    } finally { fixture.close(); }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_wave_pause", timeoutTicks = 2400)
    public static void pauseDuringSettlingDrainsAndResumes(GameTestHelper helper) throws IOException {
        Fixture fixture = fixture(helper, 1640);
        OperationData.Task task = fixture.task;
        AtomicReference<CompletableFuture<?>> nativeFence = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> firstShellSettling(helper, fixture))
                .thenExecute(() -> {
                    nativeFence.set(committing(task.id));
                    helper.assertTrue(nativeFence.get() != null, "SETTLING owns the real native light/save fence");
                    ForgeOperationRunner.pause(task, OperationData.State.PAUSED, "Wave fixture pause");
                    fixture.gate.open();
                })
                .thenWaitUntil(() -> drained(helper, task, nativeFence.get()))
                .thenIdle(3)
                .thenExecute(() -> {
                    nativeFence.get().join();
                    helper.assertTrue(task.state == OperationData.State.PAUSED && task.phase == 0 && task.changed == 1,
                            "Draining a paused save does not advance the radius or reactivate writes");
                    helper.assertTrue(fixture.level.getBlockState(fixture.center).isAir()
                                    && fixture.level.getBlockState(fixture.far).is(Blocks.STONE),
                            "Pause retains the committed central change and untouched outer shell");
                    helper.assertTrue(ForgeOperationRunner.resume(task), "An explicit ordinary pause can resume");
                })
                .thenWaitUntil(() -> helper.assertTrue(task.state == OperationData.State.WRITTEN,
                        "Resumed wave finishes from its completed shell receipt: " + ForgeOperationRunner.status(task).getString()))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(task.changed == 2 && fixture.level.getBlockState(fixture.far).isAir(),
                                "Resume finishes outer work without repeating central writes");
                    } finally { fixture.close(); }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_wave_cancel", timeoutTicks = 2400)
    public static void cancelDuringSettlingDrainsAndJournalPreventsRevival(GameTestHelper helper) throws IOException {
        Fixture fixture = fixture(helper, 1680);
        OperationData.Task task = fixture.task;
        AtomicReference<CompletableFuture<?>> nativeFence = new AtomicReference<>();
        AtomicReference<CompoundTag> staleSavedData = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> firstShellSettling(helper, fixture))
                .thenExecute(() -> {
                    nativeFence.set(committing(task.id));
                    helper.assertTrue(nativeFence.get() != null, "Cancellation begins with a real save in flight");
                    ListTag operations = new ListTag();
                    operations.add(task.save());
                    CompoundTag stale = new CompoundTag();
                    stale.put("operations", operations);
                    staleSavedData.set(stale);
                    ForgeOperationRunner.cancel(task);
                    fixture.gate.open();
                })
                .thenWaitUntil(() -> drained(helper, task, nativeFence.get()))
                .thenIdle(3)
                .thenExecute(() -> {
                    try {
                        nativeFence.get().join();
                        helper.assertTrue(task.state == OperationData.State.CANCELLED && task.phase == 0 && task.changed == 1,
                                "Cancellation drains previously accepted native work without advancing the wave");
                        helper.assertTrue(fixture.level.getBlockState(fixture.center).isAir()
                                        && fixture.level.getBlockState(fixture.far).is(Blocks.STONE),
                                "Cancellation retains prior writes and stops future shells");
                        helper.assertTrue(!ForgeOperationRunner.resume(task), "Cancelled operations cannot be resumed");
                        OperationData recovered = OperationData.load(staleSavedData.get());
                        var before = recovered.tasks.stream().filter(value -> value.id.equals(task.id)).findFirst().orElseThrow();
                        helper.assertTrue(before.state == OperationData.State.PAUSED_RECOVERY && before.unconfirmedFences > 0,
                                "Stale SavedData with an unacknowledged pipeline cannot use its advanced cursor as replay proof");
                        IntentJournal.reconcile(fixture.level, recovered);
                        var after = recovered.tasks.stream().filter(value -> value.id.equals(task.id)).findFirst().orElseThrow();
                        helper.assertTrue(after.state == OperationData.State.CANCELLED && after.terminal() && !after.active(),
                                "The durable cancellation checkpoint dominates stale active SavedData");
                        helper.assertTrue(!ForgeOperationRunner.resume(after), "Reconciled cancellation cannot revive through resume");
                    } finally { fixture.close(); }
                }).thenSucceed();
    }

    private static Fixture fixture(GameTestHelper helper, int chunkX) throws IOException {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_flat"));
        ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
        helper.assertTrue(level != null, "Dedicated generation_flat fixture dimension exists");
        ChunkPos chunk = new ChunkPos(chunkX, 1600);
        UUID ticket = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk, 0, ticket);
        level.getChunk(chunk.x, chunk.z);
        BlockPos center = new BlockPos(chunk.getMinBlockX() + 8, 64, chunk.getMinBlockZ() + 8);
        BlockPos far = center.offset(6, 0, 0), outside = center.offset(7, 0, 7);
        level.setBlock(center, Blocks.STONE.defaultBlockState(), FLAGS);
        level.setBlock(far, Blocks.STONE.defaultBlockState(), FLAGS);
        level.setBlock(outside, Blocks.EMERALD_BLOCK.defaultBlockState(), FLAGS);
        var oldProgression = AdapterConfig.PROGRESSION.get();
        int oldWidth = AdapterConfig.SHELL_WIDTH.get();
        var oldPhysics = AdapterConfig.PHYSICS.get();
        double oldDamage = AdapterConfig.ENTITY_DAMAGE.get();
        try {
            // submit freezes these values before the shared config is restored;
            // asynchronous tests never retain a global configuration override.
            AdapterConfig.PROGRESSION.set(AdapterConfig.Progression.CENTER_OUT);
            AdapterConfig.SHELL_WIDTH.set(2);
            AdapterConfig.PHYSICS.set(AdapterConfig.Physics.NONE);
            AdapterConfig.ENTITY_DAMAGE.set(0.0);
            OperationData.Task task = LegacyShellFixtureGameTests.submit(level, UUID.randomUUID(), center, 7, 0, false);
            Fixture fixture = new Fixture(level, chunk, ticket, center, far, outside, task);
            cleanupOnCompletion(helper, fixture);
            MinecraftForge.EVENT_BUS.register(fixture.gate);
            return fixture;
        } catch (IOException | RuntimeException failure) {
            level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, ticket);
            throw failure;
        } finally {
            AdapterConfig.PROGRESSION.set(oldProgression);
            AdapterConfig.SHELL_WIDTH.set(oldWidth);
            AdapterConfig.PHYSICS.set(oldPhysics);
            AdapterConfig.ENTITY_DAMAGE.set(oldDamage);
        }
    }

    private static void firstShellSettling(GameTestHelper helper, Fixture fixture) {
        helper.assertTrue((fixture.task.state == OperationData.State.SETTLING || fixture.task.state == OperationData.State.RUNNING)
                        && fixture.task.phase == 0 && fixture.task.unconfirmedFences > 0
                        && !fixture.gate.permits.isEmpty() && fixture.level.getBlockState(fixture.center).isAir(),
                "First shell clears its center and retains an unacknowledged native save: " + ForgeOperationRunner.status(fixture.task).getString());
    }

    private static void drained(GameTestHelper helper, OperationData.Task task, CompletableFuture<?> fence) {
        helper.assertTrue(fence != null && fence.isDone() && !hasRunnerEntry("COMMITTING", task.id)
                        && !hasRunnerEntry("HELD", task.id),
                "Inactive operation drains its own native future and releases its own chunk ticket");
    }

    private static CompletableFuture<?> committing(UUID id) {
        return (CompletableFuture<?>) runnerMap("COMMITTING").get(id);
    }
    private static boolean hasRunnerEntry(String field, UUID id) { return runnerMap(field).containsKey(id); }
    private static Map<?, ?> runnerMap(String name) {
        // Reflection is confined to our own unmapped runtime bookkeeping; native
        // progress and writes still travel through the actual production runner.
        try {
            Field field = ForgeOperationRunner.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }

    /** Keep the first real native save in flight; do not rely on catching a transient scheduler state. */
    public static final class WaveFenceGate {
        private final Fixture fixture;
        private final Map<Object, CompletableFuture<Void>> permits = new IdentityHashMap<>();
        private boolean open;
        WaveFenceGate(Fixture fixture) { this.fixture = fixture; }
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || open || fixture.task.phase != 0) return;
            try {
                for (Object request : (Iterable<?>) field(NativeCommitFences.class, "pending").get(null)) {
                    if (permits.containsKey(request) || field(request.getClass(), "level").get(request) != fixture.level) continue;
                    var chunk = (net.minecraft.world.level.chunk.LevelChunk) field(request.getClass(), "chunk").get(request);
                    if (!chunk.getPos().equals(fixture.chunk)) continue;
                    @SuppressWarnings("unchecked") var light = (CompletableFuture<Void>) field(request.getClass(), "light").get(request);
                    var permit = new CompletableFuture<Void>();
                    permits.put(request, permit);
                    field(request.getClass(), "light").set(request, CompletableFuture.allOf(light, permit));
                }
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        void open() {
            if (open) return;
            open = true;
            permits.values().forEach(permit -> permit.complete(null));
            MinecraftForge.EVENT_BUS.unregister(this);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level; final ChunkPos chunk; final UUID ticket;
        final BlockPos center, far, outside; final OperationData.Task task;
        final WaveFenceGate gate; boolean closed;
        Fixture(ServerLevel level, ChunkPos chunk, UUID ticket, BlockPos center, BlockPos far, BlockPos outside, OperationData.Task task) {
            this.level = level; this.chunk = chunk; this.ticket = ticket;
            this.center = center; this.far = far; this.outside = outside; this.task = task;
            gate = new WaveFenceGate(this);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            gate.open();
            if (!task.terminal()) ForgeOperationRunner.cancel(task);
            level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, ticket);
        }
    }

    private static void cleanupOnCompletion(GameTestHelper helper, Fixture fixture) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo test) {}
                    public void testPassed(GameTestInfo test) { fixture.close(); }
                    public void testFailed(GameTestInfo test) { fixture.close(); }
                });
                return;
            }
            throw new IllegalStateException("Missing GameTest cleanup hook");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private OperationWaveGameTests() { }
}
