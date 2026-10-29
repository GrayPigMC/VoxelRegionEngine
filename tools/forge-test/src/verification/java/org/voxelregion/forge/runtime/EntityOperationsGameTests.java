/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.verification.EntityOperationScenario;

/** Repository entry point for the native loaded/saved entity lifecycle scenario. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class EntityOperationsGameTests {
    @GameTest(template = "empty", batch = "vre_entity_api", timeoutTicks = 1800)
    public static void entityApiPreservesGeometryScopePlayersAndNativeLifecycle(GameTestHelper helper) {
        var site = BackendFixtureGameTests.allocate(helper, 0);
        // Keep fixture/entity-storage-only readback far from spawn and GameTest forced
        // chunks. Each allocation is spaced far enough that its absent control is fresh.
        var origin = new BlockPos(1_000_000 + site.position().x * 512, 200,
                1_000_000 + site.position().z * 512);
        var scenario = new EntityOperationScenario(site.level(), origin,
                (event, fields) -> RuntimeBridge.LOGGER.info("EntityApiScenario {} {}", event, fields));
        Consumer<ChunkEvent.Unload> unload = event -> {
            if (event.getLevel() == site.level() && event.getChunk() instanceof LevelChunk chunk) scenario.unloaded(chunk);
        };
        Consumer<LivingAttackEvent> veto = event -> {
            if (scenario.rejectAttack(event.getEntity())) event.setCanceled(true);
        };
        var cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            MinecraftForge.EVENT_BUS.unregister(unload);
            MinecraftForge.EVENT_BUS.unregister(veto);
            scenario.close();
        };
        BackendFixtureGameTests.onFailure(helper, cleanup);
        MinecraftForge.EVENT_BUS.addListener(unload);
        MinecraftForge.EVENT_BUS.addListener(veto);
        Throwable[] failure = new Throwable[1];
        helper.startSequence().thenWaitUntil(() -> {
            boolean complete;
            try { complete = scenario.tick(); }
            catch (RuntimeException | AssertionError rejected) { failure[0] = rejected; return; }
            helper.assertTrue(complete, "Waiting for native entity loading, actions and genuine unload/reload");
        }).thenExecute(() -> {
            cleanup.run();
            helper.assertTrue(failure[0] == null, "Native entity scenario failed: " + failure[0]);
        }).thenSucceed();
    }

    private EntityOperationsGameTests() { }
}
