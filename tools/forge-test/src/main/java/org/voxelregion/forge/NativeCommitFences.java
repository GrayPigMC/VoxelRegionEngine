/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;

/** Bounded, nonblocking native lighting, terrain and POI persistence barriers. World access stays on the server thread. */
public final class NativeCommitFences {
    private static final int MAX_PENDING = 32;
    private static final int MAX_BATCH = 8;
    private static final List<Request> pending = new ArrayList<>();
    private static CompletableFuture<Void> batch;
    private static List<Request> active = List.of();
    private static long generation;

    public static CompletableFuture<Void> light(ServerLevel level, LevelChunk chunk) {
        requireOwner(level.getServer());
        return ((NativeAccess.LightFence) level.getChunkSource().getLightEngine()).vre$afterChunkWork(chunk.getPos());
    }

    /** Caller holds the chunk ticket until completion; a save failure must not be treated as a completed phase. */
    public static CompletableFuture<Void> saveAfterLighting(ServerLevel level, LevelChunk chunk) {
        return enqueueSave(level, chunk).saved();
    }

    /** Current native stages for bounded runtime progress reporting; methods are read on the server thread. */
    public static final class SaveFence {
        private final Request request;
        private SaveFence(Request request) { this.request = request; }
        public CompletableFuture<Void> lighting() { return request.light; }
        public CompletableFuture<Void> saved() { return request.result; }
    }
    public static SaveFence enqueueSave(ServerLevel level, LevelChunk chunk) {
        requireOwner(level.getServer());
        if (pending.size() + active.size() >= MAX_PENDING) throw new IllegalStateException("Native save queue capacity reached");
        Request request = new Request(level, chunk);
        pending.add(request);
        return new SaveFence(request);
    }

    public static int pendingCount() { return pending.size() + active.size(); }

    public static void tick(MinecraftServer server) {
        tick(server, Long.MAX_VALUE);
    }

    public static void tick(MinecraftServer server, long deadline) {
        requireOwner(server);
        if (batch != null && !batch.isDone()) return;
        if (batch != null) {
            Throwable failure = null;
            try { batch.join(); } catch (RuntimeException ex) { failure = ex; }
            for (Request request : active) {
                if (failure == null) request.result.complete(null);
                else { request.chunk.setUnsaved(true); request.result.completeExceptionally(failure); }
            }
            active = List.of();
        }
        batch = null;
        List<Request> selected = new ArrayList<>();
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        Set<IOWorker> workers = new LinkedHashSet<>();
        Iterator<Request> iterator = pending.iterator();
        while (iterator.hasNext() && selected.size() < MAX_BATCH && System.nanoTime() < deadline) {
            Request request = iterator.next();
            if (!request.light.isDone()) continue;
            boolean detached = false;
            try {
                request.light.join(); // Already complete; never blocks the server thread.
                if (request.level.getChunkSource().getChunkNow(request.chunk.getPos().x, request.chunk.getPos().z) != request.chunk)
                    throw new IllegalStateException("Chunk ticket was released before its save fence");
                if (revision(request.chunk) != request.epoch) {
                    // An external write can enter after the old mailbox barrier. Its terrain
                    // must not be paired with light from the earlier revision.
                    request.awaitCurrentLight();
                    continue;
                }
                iterator.remove();
                detached = true;
                var map = request.level.getChunkSource().chunkMap;
                if (!(map.chunkScanner() instanceof IOWorker terrain))
                    throw new IllegalStateException("Native chunk storage cannot supply a completion fence");
                try (var capture = NativeSaveTracker.begin(request.chunk.getPos(), terrain)) {
                    request.chunk.setUnsaved(true);
                    if (!((NativeAccess.ChunkSave) map).vre$save(request.chunk))
                        throw new IllegalStateException("Native chunk serialization/save rejected the request");
                    if (revision(request.chunk) != request.epoch)
                        throw new IllegalStateException("Chunk changed during native serialization; stable terrain/light image cannot be acknowledged");
                    writes.add(capture.writes());
                    workers.addAll(capture.workers());
                    selected.add(request);
                }
            } catch (RuntimeException | LinkageError failure) {
                // It may still be queued when revision validation or light completion fails.
                if (!detached) iterator.remove();
                request.chunk.setUnsaved(true);
                request.result.completeExceptionally(failure);
            }
        }
        if (selected.isEmpty()) return;
        active = List.copyOf(selected);
        // Capture individual futures before the barrier: synchronize alone can miss an earlier failed store.
        batch = CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> CompletableFuture.allOf(workers.stream()
                        .map(worker -> worker.synchronize(true)).toArray(CompletableFuture[]::new)));
        // Completion is observed on the server thread on the next tick. No old asynchronous
        // callback can acknowledge a stopped server or mutate a new server's batch.
    }

    public static void stop(MinecraftServer server) {
        requireOwner(server);
        IllegalStateException failure = new IllegalStateException("Server stopped before native completion acknowledgement");
        for (Request request : pending) request.result.completeExceptionally(failure);
        for (Request request : active) request.result.completeExceptionally(failure);
        pending.clear();
        active = List.of();
        generation++;
        batch = null;
    }

    private static void requireOwner(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Native commit fences require the server thread");
    }
    static long revision(LevelChunk chunk) {
        if (!(chunk instanceof NativeAccess.ChunkRevision tracked))
            throw new IllegalStateException("Native chunk mutation revision hook is missing");
        long revision = tracked.vre$mutationRevision();
        if (revision < 0) throw new IllegalStateException("Native chunk mutation revision is unavailable or invalid");
        return revision;
    }
    private static final class Request {
        final ServerLevel level;
        final LevelChunk chunk;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<Void> light;
        long epoch;
        Request(ServerLevel level, LevelChunk chunk) {
            this.level = level; this.chunk = chunk;
            awaitCurrentLight();
        }
        void awaitCurrentLight() {
            epoch = revision(chunk);
            light = NativeCommitFences.light(level, chunk);
        }
    }
    private NativeCommitFences() {}
}
