/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;

/** Public API: original capture, durable snapshot, worker index, shadow mask and native completion. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PropagationRunnerGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_propagation_runner", Comparator.<UUID>naturalOrder());

    @GameTest(template = "empty", batch = "vre_propagation_runner", timeoutTicks = 1200)
    public static void frozenBedrockWallShadowsFarTerrainThroughPublicRunner(GameTestHelper helper) throws IOException {
        propagationCase(helper, 1840, FrozenOcclusion.Mode.AXIS_6);
    }

    @GameTest(template = "empty", batch = "vre_propagation_runner_axis26", timeoutTicks = 1200)
    public static void frozenAxis26ShadowsFarTerrainThroughPublicRunner(GameTestHelper helper) throws IOException {
        propagationCase(helper, 1880, FrozenOcclusion.Mode.AXIS_26);
    }

    @GameTest(template = "empty", batch = "vre_propagation_runner_refined", timeoutTicks = 1200)
    public static void frozenRefinedFieldShadowsFarTerrainThroughPublicRunner(GameTestHelper helper) throws IOException {
        propagationCase(helper, 1920, FrozenOcclusion.Mode.RAYCAST_REFINED);
    }

    @GameTest(template = "empty", batch = "vre_propagation_overlap_order", timeoutTicks = 1600)
    public static void laterPropagationWaitsForEarlierGeometryAndItsNativeFences(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_flat")));
        helper.assertTrue(level != null, "Dedicated flat fixture exists");
        ChunkPos chunk = new ChunkPos(1960, 1600);
        UUID ticket = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk, 0, ticket);
        level.getChunk(chunk.x, chunk.z);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockPos center = new BlockPos(chunk.getMinBlockX() + 8, 66, chunk.getMinBlockZ() + 8);
        BlockPos near = center.offset(2, 0, 0), far = center.offset(6, 0, 0);
        level.setBlock(center, Blocks.STONE.defaultBlockState(), flags);
        level.setBlock(near, Blocks.STONE.defaultBlockState(), flags);
        level.setBlock(far, Blocks.STONE.defaultBlockState(), flags);
        for (int y = 64; y < 68; y++) for (int z = 8; z < 12; z++)
            level.setBlock(new BlockPos(chunk.getMinBlockX() + 12, y, chunk.getMinBlockZ() + z),
                    Blocks.BEDROCK.defaultBlockState(), flags);

        var previousMode = AdapterConfig.PROPAGATION.get(); var previousProgression = AdapterConfig.PROGRESSION.get();
        var previousPhysics = AdapterConfig.PHYSICS.get(); double previousDamage = AdapterConfig.ENTITY_DAMAGE.get();
        OperationData.Task earlier, later;
        try {
            AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.NONE);
            AdapterConfig.PROGRESSION.set(AdapterConfig.Progression.AS_FAST_AS_POSSIBLE);
            AdapterConfig.PHYSICS.set(AdapterConfig.Physics.NONE); AdapterConfig.ENTITY_DAMAGE.set(0.0);
            var first = ForgeRegionEngine.submitSphere(level, UUID.randomUUID(), center, 1, ForgeRegionEngine.Options.DEFAULT);
            earlier = OperationData.get(level).tasks.stream().filter(task -> task.id.equals(first.id())).findFirst().orElseThrow();
            AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.AXIS_6);
            var second = ForgeRegionEngine.submitSphere(level, UUID.randomUUID(), center, 6, ForgeRegionEngine.Options.DEFAULT);
            later = OperationData.get(level).tasks.stream().filter(task -> task.id.equals(second.id())).findFirst().orElseThrow();
        } finally {
            AdapterConfig.PROPAGATION.set(previousMode); AdapterConfig.PROGRESSION.set(previousProgression);
            AdapterConfig.PHYSICS.set(previousPhysics); AdapterConfig.ENTITY_DAMAGE.set(previousDamage);
        }
        Path directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/voxelregion_test_intents");
        OrderingGate gate = new OrderingGate(level, chunk);
        MinecraftForge.EVENT_BUS.register(gate);
        AtomicBoolean unsafeCapture = new AtomicBoolean();
        Runnable unsubscribe = ChunkMutationTracking.subscribe(level, (changed, revision) -> {
            if (changed.getLevel() == level && changed.getPos().equals(chunk) && later.occlusionReady
                    && (earlier.state != OperationData.State.WRITTEN || earlier.unconfirmedFences != 0))
                unsafeCapture.set(true);
        });
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            gate.open(); unsubscribe.run();
            if (!earlier.terminal()) ForgeOperationRunner.cancel(earlier);
            if (!later.terminal()) ForgeOperationRunner.cancel(later);
            level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, ticket);
        };
        cleanupOnCompletion(helper, cleanup);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(earlier.unconfirmedFences > 0 && gate.gated(),
                        "Earlier geometry has submitted real native work with delayed acknowledgement"))
                .thenIdle(4)
                .thenExecute(() -> {
                    helper.assertTrue(earlier.changed == 1 && level.getBlockState(center).isAir()
                                    && earlier.state != OperationData.State.WRITTEN,
                            "Earlier operation has edited terrain but has not finished its native fences");
                    helper.assertTrue(later.changed == 0 && later.visited == 0 && !later.occlusionReady
                                    && later.frozenHash.isEmpty() && OperationOcclusion.stage(later.id).equals("NOT_STARTED")
                                    && !Files.exists(directory.resolve(later.id + ".occlusion.nbt"))
                                    && later.message.contains(earlier.id.toString()),
                            "Overlapping propagation neither captures nor publishes a snapshot before earlier completion");
                    gate.open();
                }).thenWaitUntil(() -> helper.assertTrue(earlier.state == OperationData.State.WRITTEN
                                && later.state == OperationData.State.WRITTEN,
                        "Both operations finish in accepted order: " + ForgeOperationRunner.status(later).getString()))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(!unsafeCapture.get() && earlier.unconfirmedFences == 0 && later.unconfirmedFences == 0,
                                "Later world writes begin only after the earlier native work has drained");
                        helper.assertTrue(earlier.changed == 1 && later.changed == 1
                                        && level.getBlockState(near).isAir() && level.getBlockState(far).is(Blocks.STONE),
                                "Later snapshot sees the earlier deletion and preserves the original bedrock shadow");
                        var frozen = FrozenFieldStore.read(directory, later.id, later.intentHash(), later.frozenHash);
                        helper.assertTrue(!frozen.blockers().isEmpty() && frozen.mode() == FrozenOcclusion.Mode.AXIS_6,
                                "Ordered capture still produces durable blocker evidence");
                    } catch (IOException failure) { throw new AssertionError(failure); }
                    finally { cleanup.run(); }
                }).thenSucceed();
    }

    /** Verification-only acknowledgement delay; native lighting and storage still run. */
    public static final class OrderingGate {
        private final ServerLevel level;
        private final ChunkPos position;
        private final Map<Object, CompletableFuture<Void>> permits = new IdentityHashMap<>();
        private boolean open;
        OrderingGate(ServerLevel level, ChunkPos position) { this.level = level; this.position = position; }
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || open) return;
            try {
                for (Object request : (Iterable<?>) field(NativeCommitFences.class, "pending").get(null)) {
                    if (permits.containsKey(request) || field(request.getClass(), "level").get(request) != level) continue;
                    LevelChunk chunk = (LevelChunk) field(request.getClass(), "chunk").get(request);
                    if (!chunk.getPos().equals(position)) continue;
                    @SuppressWarnings("unchecked") var original = (CompletableFuture<Void>) field(request.getClass(), "light").get(request);
                    var permit = new CompletableFuture<Void>(); permits.put(request, permit);
                    field(request.getClass(), "light").set(request, CompletableFuture.allOf(original, permit));
                }
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        boolean gated() { return !permits.isEmpty(); }
        void open() {
            open = true; for (var permit : permits.values()) permit.complete(null);
            MinecraftForge.EVENT_BUS.unregister(this);
        }
    }
    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void cleanupOnCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) { }
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new AssertionError("GameTest completion listener unavailable");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void propagationCase(GameTestHelper helper, int chunkX, FrozenOcclusion.Mode mode) throws IOException {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_flat")));
        helper.assertTrue(level != null, "Dedicated flat fixture exists");
        ChunkPos chunk = new ChunkPos(chunkX, 1600);
        UUID ticket = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, chunk, 0, ticket);
        level.getChunk(chunk.x, chunk.z);
        int baseX = chunk.getMinBlockX(), baseZ = chunk.getMinBlockZ();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockPos center = new BlockPos(baseX + 8, 66, baseZ + 8);
        BlockPos near = center.offset(2, 0, 0), far = center.offset(6, 0, 0), outside = center.offset(7, 0, 0);
        level.setBlock(center, Blocks.STONE.defaultBlockState(), flags);
        level.setBlock(near, Blocks.STONE.defaultBlockState(), flags);
        level.setBlock(far, Blocks.STONE.defaultBlockState(), flags);
        level.setBlock(outside, Blocks.EMERALD_BLOCK.defaultBlockState(), flags);
        for (int y = 64; y < 68; y++) for (int z = 8; z < 12; z++)
            level.setBlock(new BlockPos(baseX + 12, y, baseZ + z), Blocks.BEDROCK.defaultBlockState(), flags);

        Path directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/voxelregion_test_intents");
        AtomicReference<OperationData.Task> running = new AtomicReference<>();
        AtomicBoolean firstWriteSeen = new AtomicBoolean(), unsafeFirstWrite = new AtomicBoolean();
        Runnable unsubscribe = ChunkMutationTracking.subscribe(level, (changedChunk, revision) -> {
            OperationData.Task task = running.get();
            if (changedChunk.getLevel() == level && changedChunk.getPos().equals(chunk) && task != null
                    && firstWriteSeen.compareAndSet(false, true)) {
                if (!task.occlusionReady || task.frozenHash.isEmpty()
                        || !Files.isRegularFile(directory.resolve(task.id + ".occlusion.nbt")))
                    unsafeFirstWrite.set(true);
            }
        });
        var oldMode = AdapterConfig.PROPAGATION.get();
        var oldPhysics = AdapterConfig.PHYSICS.get();
        double oldDamage = AdapterConfig.ENTITY_DAMAGE.get();
        ForgeRegionEngine.Handle handle;
        try {
            AdapterConfig.PROPAGATION.set(mode);
            AdapterConfig.PHYSICS.set(AdapterConfig.Physics.NONE);
            AdapterConfig.ENTITY_DAMAGE.set(0.0);
            handle = ForgeRegionEngine.submitSphere(level, UUID.randomUUID(), center, 6, ForgeRegionEngine.Options.DEFAULT);
        } catch (IOException | RuntimeException failure) {
            unsubscribe.run(); level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, ticket); throw failure;
        } finally {
            AdapterConfig.PROPAGATION.set(oldMode);
            AdapterConfig.PHYSICS.set(oldPhysics);
            AdapterConfig.ENTITY_DAMAGE.set(oldDamage);
        }
        OperationData.Task task = OperationData.get(level).tasks.stream()
                .filter(value -> value.id.equals(handle.id())).findFirst().orElseThrow();
        running.set(task);
        helper.assertTrue(task.propagation == mode && task.frozenHash.isEmpty(),
                "Accepted task freezes the requested mode and starts without invented blocker evidence");
        helper.startSequence().thenWaitUntil(() -> {
            var status = ForgeRegionEngine.status(level, handle.id()).orElseThrow();
            helper.assertTrue(status.state() == ForgeRegionEngine.State.WRITTEN,
                    "Real propagation runner completes capture/index/write/light/save: " + status.description());
        }).thenExecute(() -> {
            try {
                helper.assertTrue(firstWriteSeen.get() && !unsafeFirstWrite.get(),
                        "Native mutation listener observes the durable frozen field before the first deletion");
                helper.assertTrue(level.getBlockState(center).isAir() && level.getBlockState(near).isAir(),
                        "Visible terrain before the bedrock wall is removed");
                helper.assertTrue(level.getBlockState(far).is(Blocks.STONE),
                        "The original bedrock wall shadows terrain behind it");
                helper.assertTrue(level.getBlockState(outside).is(Blocks.EMERALD_BLOCK)
                                && level.getBlockState(center.offset(4, 0, 0)).is(Blocks.BEDROCK),
                        "Outside geometry and the protected source blocker are preserved");
                helper.assertTrue(task.changed == 2 && task.frozenHash.matches("[0-9a-f]{64}"),
                        "Exactly two visible fixture stones changed and a content hash was retained");
                var frozen = FrozenFieldStore.read(directory, task.id, task.intentHash(), task.frozenHash);
                helper.assertTrue(frozen.mode() == mode && !frozen.blockers().isEmpty()
                                && frozen.revisions().containsKey(new ChunkKey(chunk.x, chunk.z)),
                        "Verified artifact contains original blocker boxes and the captured chunk epoch");
                var checkpoint = NbtIo.readCompressed(directory.resolve(task.id + ".checkpoint.nbt").toFile());
                helper.assertTrue(checkpoint.getString("frozenHash").equals(task.frozenHash)
                                && checkpoint.getString("propagation").equals(mode.name())
                                && OperationData.Task.load(task.save()).frozenHash.equals(task.frozenHash),
                        "Journal and SavedData retain the exact frozen snapshot reference for restart");
            } catch (IOException failure) { throw new AssertionError(failure); }
            finally { unsubscribe.run(); level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, ticket); }
        }).thenSucceed();
    }

    private PropagationRunnerGameTests() { }
}
