/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.voxelregion.forge.ChunkPacketQueue;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.NativeCommitFences;

/** Internal bridge; integrations use ForgeRegionEngine rather than mutable runtime state. */
public final class RuntimeBridge {
    // Persisted names are intentionally independent of Java package or embedding mod id.
    static final String PERSISTENCE_NAMESPACE = "voxelregion_test";
    static final Logger LOGGER = LogUtils.getLogger();
    private static boolean installed;

    /** Marker supplied by the mandatory mutation mixin, checked before any operation is accepted. */
    public interface MutationTracked {}

    public static synchronized void install(IEventBus modBus, String configFile) {
        if (installed) return;
        if (configFile.isBlank() || configFile.contains("/") || configFile.contains("\\"))
            throw new IllegalArgumentException("Expected a server config filename");
        if (ServerLifecycleHooks.getCurrentServer() != null)
            throw new IllegalStateException("Install the adapter during mod construction, before a server starts");
        modBus.addListener((RegisterCapabilitiesEvent event) -> event.register(ChunkReceipts.class));
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AdapterConfig.SPEC, configFile);
        MinecraftForge.EVENT_BUS.addGenericListener(LevelChunk.class, ChunkReceipts::attach);
        MinecraftForge.EVENT_BUS.addListener(RuntimeBridge::tick);
        MinecraftForge.EVENT_BUS.addListener(RuntimeBridge::stop);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerAboutToStartEvent event) -> org.voxelregion.forge.ServerTickTimings.reset());
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TagsUpdatedEvent event) -> OperationOcclusion.rulesChanged());
        installed = true;
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long deadline = sharedDeadline();
        NativeCommitFences.tick(server, deadline);
        ChunkPacketQueue.tick(server, AdapterConfig.PACKET_BYTES.get(), deadline);
        ForgeOperationRunner.tick(server, deadline);
    }
    static long sharedDeadline() {
        double mspt = org.voxelregion.forge.ServerTickTimings.averageMillis();
        double scale = mspt <= 40 ? 1.0 : Math.max(.1, (50.0 - mspt) / 10.0);
        return System.nanoTime() + (long) (AdapterConfig.MILLIS_PER_TICK.get() * scale * 1_000_000);
    }
    private static void stop(ServerStoppingEvent event) {
        for (var level : event.getServer().getAllLevels()) org.voxelregion.forge.GenerationGate.stop(level);
        NativeCommitFences.stop(event.getServer());
        ChunkPacketQueue.stop();
        ForgeOperationRunner.stop();
    }
    public static int maximumRadius() { return AdapterConfig.MAX_RADIUS.get(); }

    private static void checkThread(ServerLevel level) {
        if (!installed) throw new IllegalStateException("ForgeRegionEngine.install must run during mod construction");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Region API requires the server thread");
    }
    public static ForgeRegionEngine.Handle submitSphere(ServerLevel level, UUID actor, BlockPos center, int radius,
                                                         ForgeRegionEngine.Options options) throws IOException {
        checkThread(level);
        if (!MutationTracked.class.isAssignableFrom(LevelChunk.class)
                || !NativeAccess.ChunkSave.class.isAssignableFrom(ChunkMap.class)
                || !(level.getChunkSource().getLightEngine() instanceof NativeAccess.LightFence))
            throw new IllegalStateException("Required VRE mixins are absent; declare both supplied MixinConfigs before mod loading");
        var task = ForgeOperationRunner.submit(level, actor, center, radius, options.fuseTicks(),
                options.generateMissingChunks(), options.clearFluids());
        return new ForgeRegionEngine.Handle(level.dimension().location(), task.id);
    }
    public static ForgeRegionEngine.Handle submit(ServerLevel level, UUID actor, org.voxelregion.forge.RegionGeometry geometry,
            ForgeRegionEngine.Options options) throws IOException {
        checkThread(level);
        if (!MutationTracked.class.isAssignableFrom(LevelChunk.class)
                || !NativeAccess.ChunkSave.class.isAssignableFrom(ChunkMap.class)
                || !(level.getChunkSource().getLightEngine() instanceof NativeAccess.LightFence))
            throw new IllegalStateException("Required VRE mixins are absent");
        var task = ForgeOperationRunner.submitGeometry(level, actor, geometry, options);
        return new ForgeRegionEngine.Handle(level.dimension().location(), task.id);
    }
    public static Optional<ForgeRegionEngine.Status> status(ServerLevel level, UUID id) {
        checkThread(level);
        return OperationData.get(level).tasks.stream().filter(task -> task.id.equals(id)).findFirst().map(task -> snapshot(level, task));
    }
    public static List<ForgeRegionEngine.Status> statuses(ServerLevel level) {
        checkThread(level);
        return OperationData.get(level).tasks.stream().map(task -> snapshot(level, task)).toList();
    }
    public static ForgeRegionEngine.ControlResult control(ServerLevel level, UUID id, ForgeRegionEngine.Control action) {
        checkThread(level);
        OperationData data = OperationData.get(level);
        var found = data.tasks.stream().filter(task -> task.id.equals(id)).findFirst();
        if (found.isEmpty()) return new ForgeRegionEngine.ControlResult(false, Optional.empty(), "Unknown region operation.");
        var task = found.get();
        if (ForgeOperationRunner.executingStep())
            return new ForgeRegionEngine.ControlResult(false, Optional.of(snapshot(level, task)),
                    "Control is unavailable during a synchronous region callback; queue it for a later server tick.");
        boolean applied;
        switch (action) {
            case RESUME -> applied = ForgeOperationRunner.resume(task);
            case CANCEL -> { applied = !task.terminal(); if (applied) ForgeOperationRunner.cancel(task); }
            case PAUSE -> {
                applied = !task.terminal() && task.state != OperationData.State.PAUSED_RECOVERY;
                if (applied) ForgeOperationRunner.pause(task, OperationData.State.PAUSED, "Paused by host.");
            }
            default -> throw new AssertionError(action);
        }
        if (applied) data.setDirty();
        return new ForgeRegionEngine.ControlResult(applied, Optional.of(snapshot(level, task)),
                applied ? "" : "Operation state does not allow this control; uncertain recovery cannot be forced.");
    }
    private static ForgeRegionEngine.Status snapshot(ServerLevel level, OperationData.Task task) {
        return new ForgeRegionEngine.Status(new ForgeRegionEngine.Handle(level.dimension().location(), task.id),
                ForgeRegionEngine.State.valueOf(task.state.name()), task.radius, task.phase, task.phaseCount(),
                task.visited, task.changed, task.kept, task.finishedChunks, task.skippedChunks, task.skippedUnloaded,
                task.bulkSections, task.clearFluids, task.message, ForgeOperationRunner.status(task).getString(), ForgeOperationRunner.progress(task));
    }
    private RuntimeBridge() {}
}
