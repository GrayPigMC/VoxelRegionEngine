/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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

/** Acceptance of the public default complete-chunk path, independent of legacy shell fixtures. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class WholeChunkRunnerGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_whole_chunk_fixture", Comparator.<UUID>naturalOrder());
    private static final long WAIT_NANOS = 30_000_000_000L;
    private static final int SECTIONS = 8, MIN_Y = 64, CELLS = SECTIONS * 4096;
    private static final BlockState[] TERRAIN = { Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.IRON_ORE.defaultBlockState(), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(),
            Blocks.SAND.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
            Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3),
            Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 5) };

    @GameTest(template = "empty", batch = "vre_whole_chunk_atomic", timeoutTicks = 100000)
    public static void defaultSubmitPreparesWithoutWritesThenCommitsAllSectionsInOneTick(GameTestHelper helper) {
        ServerLevel level = fixtureLevel(helper);
        ChunkPos position = new ChunkPos(3000, 3000);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, position, 0, holder);
        LevelChunk chunk = level.getChunk(position.x, position.z);
        BlockState[] old = new BlockState[CELLS];
        for (int index = 0; index < CELLS; index++) {
            int x = index & 15, y = index >>> 8, z = (index >>> 4) & 15;
            // Every section really requires a retained-state encoding. The test does not charge
            // O(palette) complete clears as 4096 visits merely to force a timing result.
            boolean keep = y == 0 || (y % 16 == 0 && x == 8 && z == 8);
            old[index] = keep ? Blocks.BEDROCK.defaultBlockState() : TERRAIN[(x + y + z) % TERRAIN.length];
            level.setBlock(at(position, index), old[index], 18);
        }
        BoundingBox area = new BoundingBox(position.getMinBlockX(), MIN_Y, position.getMinBlockZ(),
                position.getMaxBlockX(), MIN_Y + SECTIONS * 16 - 1, position.getMaxBlockZ());
        level.getBlockTicks().clearArea(area); level.getFluidTicks().clearArea(area);
        CompletableFuture<Void> initial = BackendFixtureGameTests.lightHalo(level, position);
        AtomicObservation observation = new AtomicObservation(level, chunk, old);
        OperationData.Task[] task = new OperationData.Task[1];
        onCompletion(helper, () -> {
            observation.close();
            if (task[0] != null && !task[0].terminal()) ForgeOperationRunner.cancel(task[0]);
            level.getChunkSource().removeRegionTicket(HOLD, position, 0, holder);
        });
        long[] started = { System.nanoTime() };
        helper.startSequence().thenWaitUntil(() -> waitFor(helper, initial.isDone(), started[0], "Fixture lighting"))
                .thenExecute(() -> {
                    helper.assertTrue(initial.isDone() && !initial.isCompletedExceptionally(), "Initial fixture lighting really completed");
                    var geometry = new RegionGeometry.Box(new IntBox(position.getMinBlockX(), MIN_Y, position.getMinBlockZ(),
                            position.getMaxBlockX() + 1, MIN_Y + 16 * SECTIONS, position.getMaxBlockZ() + 1));
                    task[0] = submit(helper, level, geometry, ForgeRegionEngine.Options.DEFAULT);
                    helper.assertTrue(task[0].wholeChunk && task[0].phaseCount() == 1 && task[0].generate,
                            "Actual default acceptance captures whole chunks and generation enabled");
                    observation.start(task[0]); started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, task[0].terminal(), started[0], "Default complete-chunk operation"))
                .thenExecute(() -> {
                    helper.assertTrue(task[0].state == OperationData.State.WRITTEN,
                            "Default task reaches acknowledged WRITTEN: " + ForgeOperationRunner.status(task[0]).getString());
                    helper.assertTrue(observation.failure == null, "No intermediate terrain was exposed: " + observation.failure);
                    helper.assertTrue(observation.preparationFrames >= 2, "Detached mixed section preparation spanned real server frames");
                    helper.assertTrue(observation.mutationCalls == 2 * SECTIONS && observation.firstWriteTick == observation.lastWriteTick,
                            "Exactly one before/after revision pair per section, all swaps in one actual tick; no per-block native deletion");
                    int kept = 256 + SECTIONS - 1;
                    helper.assertTrue(task[0].changed == CELLS - kept && task[0].kept == kept && task[0].bulkSections == SECTIONS
                                    && task[0].committedChunks == 1 && task[0].finishedChunks == 1 && task[0].unconfirmedFences == 0,
                            "Real voxel counts, bulk section count, one committed chunk and native completion all agree");
                    helper.assertTrue(observation.countDifferences() == CELLS - kept, "Every selected removable state clears, every bedrock state remains");
                    helper.assertTrue(level.getFluidState(at(position, 1 | (1 << 8))).isEmpty(), "Cleared section fluids are empty");
                    helper.assertTrue(level.getBrightness(LightLayer.BLOCK, at(position, 1 | (20 << 8))) == 0
                                    && level.getBrightness(LightLayer.SKY, at(position, 1 | (20 << 8))) == 15,
                            "Acknowledgement includes final source-free light and an open sky column");
                    RuntimeBridge.LOGGER.info("Whole chunk acceptance PASS: preparationFrames={}, mutationCalls={}, commitTick={}, sections={}, changed={}, kept={}",
                            observation.preparationFrames, observation.mutationCalls, observation.firstWriteTick, SECTIONS, task[0].changed, task[0].kept);
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_existing_unloaded_chunk", timeoutTicks = 100000)
    public static void generationDisabledStillLoadsAndClearsSavedUnloadedChunk(GameTestHelper helper) {
        ServerLevel level = fixtureLevel(helper);
        ChunkPos position = new ChunkPos(3100, 3100);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, position, 0, holder);
        LevelChunk chunk = level.getChunk(position.x, position.z);
        BlockPos marker = new BlockPos(position.getMinBlockX() + 8, 96, position.getMinBlockZ() + 8);
        level.setBlock(marker, Blocks.STONE.defaultBlockState(), 18);
        CompletableFuture<Void> save = NativeCommitFences.saveAfterLighting(level, chunk);
        IOWorker storage = (IOWorker) level.getChunkSource().chunkMap.chunkScanner();
        @SuppressWarnings("unchecked") CompletableFuture<Optional<CompoundTag>>[] read = new CompletableFuture[1];
        OperationData.Task[] task = new OperationData.Task[1];
        onCompletion(helper, () -> {
            if (task[0] != null && !task[0].terminal()) ForgeOperationRunner.cancel(task[0]);
            level.getChunkSource().removeRegionTicket(HOLD, position, 0, holder);
        });
        long[] started = {System.nanoTime()};
        helper.startSequence().thenWaitUntil(() -> waitFor(helper, save.isDone(), started[0], "Original native save"))
                .thenExecute(() -> {
                    helper.assertTrue(save.isDone() && !save.isCompletedExceptionally(), "Original native save acknowledged");
                    read[0] = storage.loadAsync(position); started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, read[0].isDone(), started[0], "Original persisted image"))
                .thenExecute(() -> {
                    helper.assertTrue(read[0].isDone() && read[0].join().isPresent()
                                    && savedState(helper, read[0].join().orElseThrow(), marker).is(Blocks.STONE),
                            "The stone fixture exists in the real saved FULL image before unload");
                    level.getChunkSource().removeRegionTicket(HOLD, position, 0, holder); started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, level.getChunkSource().getChunkNow(position.x, position.z) == null,
                        started[0], "Native ticket-driven unload"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(position.x, position.z) == null,
                            "The test does not substitute a still-loaded chunk for the reported case");
                    task[0] = submit(helper, level, new RegionGeometry.Box(new IntBox(marker.getX(), marker.getY(), marker.getZ(),
                            marker.getX() + 1, marker.getY() + 1, marker.getZ() + 1)), new ForgeRegionEngine.Options(0, false, true));
                    helper.assertTrue(task[0].wholeChunk && !task[0].generate, "Explicit generate=false is preserved");
                    started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, task[0].terminal(), started[0], "Existing chunk load and clear"))
                .thenExecute(() -> {
                    helper.assertTrue(task[0].state == OperationData.State.WRITTEN && task[0].changed == 1
                                    && task[0].skippedUnloaded == 0 && task[0].committedChunks == 1,
                            "generate=false loads existing storage and clears it instead of skipping: " + ForgeOperationRunner.status(task[0]).getString());
                    read[0] = storage.loadAsync(position); started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, read[0].isDone(), started[0], "Cleared persisted image"))
                .thenExecute(() -> helper.assertTrue(read[0].isDone() && read[0].join().isPresent()
                                && savedState(helper, read[0].join().orElseThrow(), marker).isAir(),
                        "Native readback confirms the formerly unloaded saved block was actually removed"))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_default_generation", timeoutTicks = 100000)
    public static void defaultOptionsGeneratePreviouslyAbsentChunk(GameTestHelper helper) {
        ServerLevel level = fixtureLevel(helper);
        ChunkPos position = new ChunkPos(3200, 3200);
        IOWorker storage = (IOWorker) level.getChunkSource().chunkMap.chunkScanner();
        @SuppressWarnings("unchecked") CompletableFuture<Optional<CompoundTag>>[] read = new CompletableFuture[] { storage.loadAsync(position) };
        OperationData.Task[] task = new OperationData.Task[1];
        onCompletion(helper, () -> { if (task[0] != null && !task[0].terminal()) ForgeOperationRunner.cancel(task[0]); });
        long[] started = {System.nanoTime()};
        helper.startSequence().thenWaitUntil(() -> waitFor(helper, read[0].isDone(), started[0], "Fresh chunk absence proof"))
                .thenExecute(() -> {
                    helper.assertTrue(read[0].isDone() && read[0].join().isEmpty()
                                    && level.getChunkSource().getChunkNow(position.x, position.z) == null,
                            "Generation case begins with absent storage and no loaded chunk");
                    int minY = level.getMinBuildHeight();
                    task[0] = submit(helper, level, new RegionGeometry.Box(new IntBox(position.getMinBlockX(), minY,
                            position.getMinBlockZ(), position.getMaxBlockX() + 1, minY + 1, position.getMaxBlockZ() + 1)),
                            ForgeRegionEngine.Options.DEFAULT);
                    helper.assertTrue(task[0].generate && task[0].wholeChunk, "Public default enables native missing-chunk generation");
                    started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, task[0].terminal(), started[0], "Default native generation"))
                .thenExecute(() -> {
                    helper.assertTrue(task[0].state == OperationData.State.WRITTEN && task[0].kept == 256
                                    && task[0].skippedUnloaded == 0 && task[0].committedChunks == 1,
                            "Default generated the real protected bedrock layer and processed its complete chunk: " + ForgeOperationRunner.status(task[0]).getString());
                    read[0] = storage.loadAsync(position); started[0] = System.nanoTime();
                }).thenWaitUntil(() -> waitFor(helper, read[0].isDone(), started[0], "Generated persisted image"))
                .thenExecute(() -> {
                    helper.assertTrue(read[0].isDone() && read[0].join().isPresent(), "Default generation produced a native saved image");
                    CompoundTag image = read[0].join().orElseThrow();
                    helper.assertTrue(ChunkStatus.byName(image.getString("Status")) == ChunkStatus.FULL && image.getBoolean("isLightOn")
                                    && savedState(helper, image, new BlockPos(position.getMinBlockX(), level.getMinBuildHeight(), position.getMinBlockZ())).is(Blocks.BEDROCK),
                            "Generated chunk has native FULL/light metadata and retained actual terrain");
                }).thenSucceed();
    }

    /** Only bounds the real detached mixed-palette work, and restores shared config within each event dispatch. */
    public static final class AtomicObservation {
        final ServerLevel level; final LevelChunk chunk; final BlockState[] old;
        OperationData.Task task; Runnable unsubscribe; boolean active, overridden;
        int savedVisits, preparationFrames, mutationCalls;
        long firstWriteTick = -1, lastWriteTick = -1;
        String failure;
        AtomicObservation(ServerLevel level, LevelChunk chunk, BlockState[] old) { this.level = level; this.chunk = chunk; this.old = old; }
        void start(OperationData.Task task) {
            this.task = task; active = true;
            unsubscribe = ChunkMutationTracking.subscribe(level, (changed, revision) -> {
                if (!active || changed != chunk) return;
                mutationCalls++;
                int differences = countDifferences();
                int complete = CELLS - (256 + SECTIONS - 1);
                if (differences != 0 && differences != complete) failure = "Partial terrain visible during native mutation notification: " + differences + "/" + complete;
                if (differences > 0) {
                    if (firstWriteTick < 0) firstWriteTick = level.getGameTime();
                    lastWriteTick = level.getGameTime();
                }
            });
            MinecraftForge.EVENT_BUS.register(this);
        }
        int countDifferences() {
            int result = 0;
            for (int index = 0; index < CELLS; index++) {
                BlockState current = chunk.getBlockState(at(chunk.getPos(), index));
                if (current == old[index]) continue;
                if (old[index].is(Blocks.BEDROCK) || !current.isAir()) failure = "Unexpected terrain replacement at index " + index;
                result++;
            }
            return result;
        }
        @SubscribeEvent(priority = EventPriority.HIGH)
        public void before(TickEvent.ServerTickEvent event) {
            if (!active || event.phase != TickEvent.Phase.END || task.changed != 0) return;
            savedVisits = AdapterConfig.VISITS_PER_TICK.get();
            AdapterConfig.VISITS_PER_TICK.set(4096); overridden = true;
        }
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void after(TickEvent.ServerTickEvent event) {
            if (!active || event.phase != TickEvent.Phase.END) return;
            restoreBudget();
            if (task.changed == 0 && task.message.startsWith("Preparing complete chunk")) {
                preparationFrames++;
                if (countDifferences() != 0) failure = "Terrain changed during a detached preparation frame";
            }
        }
        void restoreBudget() { if (overridden) { AdapterConfig.VISITS_PER_TICK.set(savedVisits); overridden = false; } }
        void close() {
            active = false; restoreBudget();
            if (unsubscribe != null) { unsubscribe.run(); unsubscribe = null; }
            MinecraftForge.EVENT_BUS.unregister(this);
        }
    }

    private static OperationData.Task submit(GameTestHelper helper, ServerLevel level, RegionGeometry geometry, ForgeRegionEngine.Options options) {
        var physics = AdapterConfig.PHYSICS.get(); var propagation = AdapterConfig.PROPAGATION.get();
        double damage = AdapterConfig.ENTITY_DAMAGE.get();
        try {
            AdapterConfig.PHYSICS.set(AdapterConfig.Physics.NONE); AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.NONE);
            AdapterConfig.ENTITY_DAMAGE.set(0.0);
            var handle = ForgeRegionEngine.submit(level, UUID.randomUUID(), geometry, options);
            return OperationData.get(level).tasks.stream().filter(task -> task.id.equals(handle.id())).findFirst().orElseThrow();
        } catch (IOException failure) { throw new IllegalStateException("Fixture acceptance failed", failure); }
        finally { AdapterConfig.PHYSICS.set(physics); AdapterConfig.PROPAGATION.set(propagation); AdapterConfig.ENTITY_DAMAGE.set(damage); }
    }
    private static void waitFor(GameTestHelper helper, boolean ready, long started, String stage) {
        helper.assertTrue(ready || System.nanoTime() - started > WAIT_NANOS, stage + " still pending");
    }
    private static BlockPos at(ChunkPos chunk, int index) {
        return new BlockPos(chunk.getMinBlockX() + (index & 15), MIN_Y + (index >>> 8), chunk.getMinBlockZ() + ((index >>> 4) & 15));
    }
    private static ServerLevel fixtureLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_backend_fixture")));
        helper.assertTrue(level != null, "Dedicated backend fixture dimension exists"); return level;
    }
    private static BlockState savedState(GameTestHelper helper, CompoundTag image, BlockPos position) {
        var sections = image.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (section.getByte("Y") != (position.getY() >> 4)) continue;
            return PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState())
                    .parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow(false, helper::fail)
                    .get(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
        }
        helper.fail("Saved section is missing at " + position); return Blocks.AIR.defaultBlockState();
    }
    static void onCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) {}
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new IllegalStateException("No GameTest completion hook");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private WholeChunkRunnerGameTests() {}
}
