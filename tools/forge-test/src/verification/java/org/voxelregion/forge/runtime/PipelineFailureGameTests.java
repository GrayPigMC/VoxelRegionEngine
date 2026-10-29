/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
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
import org.voxelregion.propagation.FrozenOcclusion;

/** One known failed stage must stop new work while a separately pending stage retains its reservation. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class PipelineFailureGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_stage_failure_fixture", Comparator.<UUID>naturalOrder());

    @GameTest(template = "empty", batch = "vre_pipeline_stage_failure", timeoutTicks = 1600)
    public static void failedSaveStopsImmediatelyButRetainsPendingLightTicket(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_flat")));
        helper.assertTrue(level != null, "Dedicated flat fixture dimension exists");
        ChunkPos position = new ChunkPos(2340, 2340);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, position, 0, holder);
        level.getChunk(position.x, position.z);
        BlockPos center = new BlockPos(position.getMinBlockX() + 8, 128, position.getMinBlockZ() + 8);
        BlockPos later = center.offset(4, 0, 0);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(center, Blocks.STONE.defaultBlockState(), flags);
        level.setBlock(later, Blocks.GOLD_BLOCK.defaultBlockState(), flags);
        var progression = AdapterConfig.PROGRESSION.get(); var propagation = AdapterConfig.PROPAGATION.get();
        double damage = AdapterConfig.ENTITY_DAMAGE.get();
        OperationData.Task task;
        try {
            AdapterConfig.PROGRESSION.set(AdapterConfig.Progression.CENTER_OUT);
            AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.NONE); AdapterConfig.ENTITY_DAMAGE.set(0.0);
            task = LegacyShellFixtureGameTests.submit(level, UUID.randomUUID(), center, 32, 0, false);
        } finally {
            AdapterConfig.PROGRESSION.set(progression); AdapterConfig.PROPAGATION.set(propagation);
            AdapterConfig.ENTITY_DAMAGE.set(damage);
        }
        CompletableFuture<Void> delayedBackendLight = new CompletableFuture<>();
        task.queuedLightWork = delayedBackendLight;
        SaveFailure failure = new SaveFailure(level, position);
        MinecraftForge.EVENT_BUS.register(failure);
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            delayedBackendLight.complete(null);
            MinecraftForge.EVENT_BUS.unregister(failure);
            if (!task.terminal()) ForgeOperationRunner.cancel(task);
            level.getChunkSource().removeRegionTicket(HOLD, position, 0, holder);
        };
        cleanupOnCompletion(helper, cleanup);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(failure.problem == null && failure.injected && task.state == OperationData.State.FAILED,
                        "Known exceptional save stops scheduling while the combined future is still pending: " + failure.problem))
                .thenExecute(() -> {
                    helper.assertTrue(!delayedBackendLight.isDone() && task.unconfirmedFences == 1
                                    && ForgeOperationRunner.progress(task).retainedChunks() == 1,
                            "Failure does not release a ticket required by another pending completion stage");
                    helper.assertTrue(task.changed == 1 && task.phase == 0 && level.getBlockState(later).is(Blocks.GOLD_BLOCK),
                            "A failed inner phase cannot start subsequent writes");
                })
                .thenIdle(4)
                .thenExecute(() -> helper.assertTrue(task.state == OperationData.State.FAILED && task.unconfirmedFences == 1
                                && task.changed == 1 && !delayedBackendLight.isDone(),
                        "The terminal failure is visible without waiting for the unrelated delayed light stage"))
                .thenExecute(() -> delayedBackendLight.complete(null))
                .thenWaitUntil(() -> helper.assertTrue(NativeCommitFences.pendingCount() == 0,
                        "Native storage request drains after its actual backend lighting prerequisite is released"))
                .thenWaitUntil(() -> helper.assertTrue(task.unconfirmedFences == 0, "Every stage terminates before ticket release"))
                .thenExecute(() -> {
                    helper.assertTrue(task.state == OperationData.State.FAILED && ForgeOperationRunner.progress(task).retainedChunks() == 0,
                            "Draining cannot turn a failed acknowledgement into success");
                    cleanup.run();
                }).thenSucceed();
    }

    public static final class SaveFailure {
        final ServerLevel level; final ChunkPos position; boolean injected; Throwable problem;
        SaveFailure(ServerLevel level, ChunkPos position) { this.level = level; this.position = position; }
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || injected) return;
            try {
                for (Object request : (Iterable<?>) field(NativeCommitFences.class, "pending").get(null)) {
                    if (field(request.getClass(), "level").get(request) != level) continue;
                    var chunk = (LevelChunk) field(request.getClass(), "chunk").get(request);
                    if (!chunk.getPos().equals(position)) continue;
                    @SuppressWarnings("unchecked") var result = (CompletableFuture<Void>) field(request.getClass(), "result").get(request);
                    result.completeExceptionally(new IOException("injected saved-stage failure while backend light remains pending"));
                    injected = true;
                    MinecraftForge.EVENT_BUS.unregister(this);
                    return;
                }
            } catch (ReflectiveOperationException failure) { problem = failure; MinecraftForge.EVENT_BUS.unregister(this); }
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
                    public void testStructureLoaded(GameTestInfo info) {}
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new AssertionError("GameTest completion listener unavailable");
        } catch (ReflectiveOperationException problem) { throw new AssertionError(problem); }
    }
    private PipelineFailureGameTests() {}
}
