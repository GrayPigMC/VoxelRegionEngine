/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.voxelregion.forge.GenerationBridge;
import org.voxelregion.spatial.CenterOutChunkCursor;

/** Bounded native loading pipeline. Actual terrain commits still consume the authoritative center-out cursor. */
final class ChunkReadAhead {
    private static final TicketType<UUID> TICKET = TicketType.create("vre_read_ahead", Comparator.<UUID>naturalOrder());
    private static final Map<UUID, Window> windows = new HashMap<>();
    private static int retained;

    static void fill(ServerLevel level, OperationData.Task task, long deadline) {
        if (!task.generate || !task.wholeChunk || task.centerCursor == null || task.replayOffset < task.cursorOffset
                || task.propagation != org.voxelregion.propagation.FrozenOcclusion.Mode.NONE
                || AdapterConfig.READ_AHEAD_CHUNKS.get() == 0) return;
        var window = windows.computeIfAbsent(task.id, ignored -> new Window(level,
                CenterOutChunkCursor.restore(task.volume, org.voxelregion.geometry.Point3.of(task.cx, task.cy, task.cz),
                        level.getMinBuildHeight(), level.getMaxBuildHeight(), task.centerCursor.checkpoint())));
        int target = Math.min(Math.min(AdapterConfig.READ_AHEAD_CHUNKS.get(), ForgeOperationRunner.admissionWindow()),
                AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get() / 2);
        // FULL alone leaves fluid/shape/upgrade post-processing dormant. Warm that
        // lifecycle ahead of the commit cursor too, without blocking the server or
        // promoting the whole configured read window to ticking in one frame.
        warmPostProcessing(window, task, deadline);
        while (window.positions.size() < target && System.nanoTime() < deadline
                && RuntimeBridge.availableMetadataBytes(level.getServer()) >= 1024
                && ForgeOperationRunner.retainedChunks() < AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get() - 1
                && window.cursor.hasNext()) {
            var key = window.cursor.next();
            if (task.excludedChunks.contains(ChunkPos.asLong(key.x(), key.z()))
                    || ClaimChecks.check(level, key.x(), key.z(), task.protectEveryClaim) != ClaimChecks.Decision.ALLOW) continue;
            var pos = new ChunkPos(key.x(), key.z());
            // Ticket submission is nonblocking. Never call getChunk/getChunkFuture here: those
            // may managedBlock and turn the preparation window back into serial world generation.
            level.getChunkSource().addRegionTicket(TICKET, pos, 0, task.id);
            window.positions.put(pos, 0); retained++;
        }
    }

    static boolean pendingPostProcessing(LevelChunk chunk) {
        if (!chunk.getUpgradeData().isEmpty()) return true;
        for (var section : chunk.getPostProcessing()) if (section != null && !section.isEmpty()) return true;
        return false;
    }

    private static void warmPostProcessing(Window window, OperationData.Task task, long deadline) {
        int tick = window.level.getServer().getTickCount();
        if (window.checkedTick == tick) return;
        window.checkedTick = tick;
        int promotions = Math.min(32, Math.max(4, ForgeOperationRunner.admissionWindow() / 4));
        var source = window.level.getChunkSource();
        var bridge = (GenerationBridge) source.chunkMap;
        for (var entry : window.positions.entrySet()) {
            if (System.nanoTime() >= deadline) break;
            var pos = entry.getKey();
            LevelChunk chunk = source.getChunkNow(pos.x, pos.z);
            if (chunk == null) continue;
            if (entry.getValue() != 0) {
                if (!pendingPostProcessing(chunk) && bridge.vre$postProcessingReady(chunk)) {
                    source.addRegionTicket(TICKET, pos, 0, task.id);
                    source.removeRegionTicket(TICKET, pos, 1, task.id);
                    entry.setValue(0);
                }
            } else if (promotions > 0 && pendingPostProcessing(chunk)
                    && ClaimChecks.check(window.level, pos.x, pos.z, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW) {
                source.addRegionTicket(TICKET, pos, 1, task.id);
                source.removeRegionTicket(TICKET, pos, 0, task.id);
                entry.setValue(1); promotions--;
            }
        }
    }

    static int ticketDistance(UUID operation, ChunkPos pos) {
        var window = windows.get(operation);
        return window == null ? 0 : window.positions.getOrDefault(pos, 0);
    }

    /** Transfer to the runner's own ticket before releasing the prefetch lease. */
    static void adopted(UUID operation, ChunkPos pos) {
        var window = windows.get(operation);
        Integer distance = window == null ? null : window.positions.remove(pos);
        if (distance != null) {
            window.level.getChunkSource().removeRegionTicket(TICKET, pos, distance, operation);
            retained--;
        }
    }

    static void release(UUID operation) {
        var window = windows.remove(operation);
        if (window == null) return;
        for (var entry : window.positions.entrySet())
            window.level.getChunkSource().removeRegionTicket(TICKET, entry.getKey(), entry.getValue(), operation);
        retained -= window.positions.size();
    }
    static int retained() { return retained; }
    static int retained(UUID id) { var window = windows.get(id); return window == null ? 0 : window.positions.size(); }
    static int loading(UUID id) {
        var window = windows.get(id); if (window == null) return 0;
        int count = 0;
        for (var entry : window.positions.entrySet()) {
            var pos = entry.getKey();
            var chunk = window.level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null || pendingPostProcessing(chunk) || entry.getValue() != 0
                    && !((GenerationBridge) window.level.getChunkSource().chunkMap).vre$postProcessingReady(chunk)) count++;
        }
        return count;
    }
    static long estimatedBytes() {
        long bytes = retained * 96L;
        for (var window : windows.values()) bytes += window.cursor.estimatedBytes();
        return bytes;
    }
    static void stop() { for (UUID id : java.util.List.copyOf(windows.keySet())) release(id); }
    private static final class Window {
        final ServerLevel level;
        final CenterOutChunkCursor cursor;
        final LinkedHashMap<ChunkPos, Integer> positions = new LinkedHashMap<>();
        int checkedTick = Integer.MIN_VALUE;
        Window(ServerLevel level, CenterOutChunkCursor cursor) { this.level = level; this.cursor = cursor; }
    }
    private ChunkReadAhead() { }
}
