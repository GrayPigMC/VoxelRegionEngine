/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import net.minecraft.world.level.chunk.LevelChunk;

/** Shared by the native mutation hook and trusted direct-section backends. */
public final class ChunkMutationTracking {
    private static final java.util.Set<java.util.function.BiConsumer<LevelChunk, Long>> listeners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    static Runnable subscribe(java.util.function.BiConsumer<LevelChunk, Long> listener) {
        listeners.add(listener); return () -> listeners.remove(listener);
    }
    /**
     * Invalidate old checkpoints before entering arbitrary native callbacks.
     * A write attempt can be a no-op; a conservative extra revision is harmless.
     */
    public static void changing(LevelChunk chunk) { changed(chunk); }

    /** Notify once after each successful native or bulk mutation, on the world thread. */
    public static void changed(LevelChunk chunk) {
        // Capability discovery can legitimately be incomplete during construction.
        chunk.getCapability(ChunkReceipts.CAPABILITY).ifPresent(receipts -> {
            receipts.changed();
            if (!listeners.isEmpty())
                for (var listener : java.util.List.copyOf(listeners)) listener.accept(chunk, receipts.mutationRevision());
        });
    }

    /** Use when a backend discovers an untracked write or uncertain mutation provenance. */
    public static void invalidate(LevelChunk chunk) {
        chunk.getCapability(ChunkReceipts.CAPABILITY).ifPresent(ChunkReceipts::invalidateTracking);
        if (!listeners.isEmpty())
            for (var listener : java.util.List.copyOf(listeners)) listener.accept(chunk, -1L);
    }

    /** Missing or invalid capability evidence is unknown, including during chunk construction. */
    public static long revision(LevelChunk chunk) {
        return chunk.getCapability(ChunkReceipts.CAPABILITY).resolve()
                .map(receipts -> receipts.trackingValid() ? receipts.mutationRevision() : -1L).orElse(-1L);
    }

    private ChunkMutationTracking() { }
}
