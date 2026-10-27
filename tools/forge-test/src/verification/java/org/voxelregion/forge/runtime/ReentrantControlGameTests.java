/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.propagation.FrozenOcclusion;

/** Public controls cannot transfer half-finished receipt/light bookkeeping from a synchronous callback. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class ReentrantControlGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_reentrant_control_fixture", Comparator.<UUID>naturalOrder());

    @GameTest(template = "empty", batch = "vre_reentrant_control", timeoutTicks = 1200)
    public static void removalCallbackControlIsRejectedAndNextTickCancellationDrains(GameTestHelper helper) throws IOException {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "generation_flat")));
        helper.assertTrue(level != null, "Dedicated flat fixture dimension exists");
        ChunkPos position = new ChunkPos(2320, 2320);
        BlockPos center = new BlockPos(position.getMinBlockX() + 8, 128, position.getMinBlockZ() + 8);
        UUID holder = UUID.randomUUID();
        level.getChunkSource().addRegionTicket(HOLD, position, 0, holder);
        level.getChunk(position.x, position.z);
        Block probe = ForgeRegistries.BLOCKS.getValue(ProbeBlocks.REMOVAL_PROBE);
        helper.assertTrue(probe != null, "Native removal probe registered");
        level.setBlock(center, probe.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        var originalAction = ProbeBlocks.removalAction;
        AtomicReference<ForgeRegionEngine.Handle> handle = new AtomicReference<>();
        AtomicReference<ForgeRegionEngine.ControlResult> pause = new AtomicReference<>(), cancel = new AtomicReference<>();
        ProbeBlocks.removalAction = (world, removed) -> {
            if (world != level || !removed.equals(center)) return;
            pause.set(ForgeRegionEngine.control(level, handle.get().id(), ForgeRegionEngine.Control.PAUSE));
            cancel.set(ForgeRegionEngine.control(level, handle.get().id(), ForgeRegionEngine.Control.CANCEL));
        };
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            ProbeBlocks.removalAction = originalAction;
            if (handle.get() != null) ForgeRegionEngine.control(level, handle.get().id(), ForgeRegionEngine.Control.CANCEL);
            level.getChunkSource().removeRegionTicket(HOLD, position, 0, holder);
        };
        cleanupOnCompletion(helper, cleanup);
        var progression = AdapterConfig.PROGRESSION.get(); var propagation = AdapterConfig.PROPAGATION.get();
        double damage = AdapterConfig.ENTITY_DAMAGE.get(); int width = AdapterConfig.SHELL_WIDTH.get();
        try {
            AdapterConfig.PROGRESSION.set(AdapterConfig.Progression.CENTER_OUT);
            AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.NONE);
            AdapterConfig.ENTITY_DAMAGE.set(0.0); AdapterConfig.SHELL_WIDTH.set(32);
            handle.set(ForgeRegionEngine.submitSphere(level, UUID.randomUUID(), center, 32, ForgeRegionEngine.Options.DEFAULT));
        } finally {
            AdapterConfig.PROGRESSION.set(progression); AdapterConfig.PROPAGATION.set(propagation);
            AdapterConfig.ENTITY_DAMAGE.set(damage); AdapterConfig.SHELL_WIDTH.set(width);
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(cancel.get() != null, "Real native onRemove called the public control API"))
                .thenExecute(() -> {
                    helper.assertTrue(!pause.get().applied() && !cancel.get().applied()
                                    && pause.get().reason().contains("synchronous") && cancel.get().reason().contains("synchronous"),
                            "Reentrant pause and cancel are explicitly rejected without throwing through native mutation");
                    var status = ForgeRegionEngine.status(level, handle.get().id()).orElseThrow();
                    helper.assertTrue(status.changed() == 1 && level.getBlockState(center).isAir()
                                    && status.state() != ForgeRegionEngine.State.FAILED,
                            "The outer native write finishes its terrain and receipt bookkeeping");
                    var result = ForgeRegionEngine.control(level, handle.get().id(), ForgeRegionEngine.Control.CANCEL);
                    helper.assertTrue(result.applied() && result.status().orElseThrow().state() == ForgeRegionEngine.State.CANCELLED,
                            "The same public cancellation is accepted outside the synchronous callback on a later tick");
                })
                .thenWaitUntil(() -> helper.assertTrue(ForgeRegionEngine.status(level, handle.get().id()).orElseThrow()
                        .progress().retainedChunks() == 0, "Cancellation drains its real native fences"))
                .thenExecute(() -> {
                    var status = ForgeRegionEngine.status(level, handle.get().id()).orElseThrow();
                    helper.assertTrue(status.state() == ForgeRegionEngine.State.CANCELLED && status.changed() == 1,
                            "No receipt exception replaces the explicit terminal cancellation");
                    cleanup.run();
                }).thenSucceed();
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
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private ReentrantControlGameTests() {}
}
