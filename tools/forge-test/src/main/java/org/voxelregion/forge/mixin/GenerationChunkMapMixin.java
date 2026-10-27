/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.GenerationBridge;
import org.voxelregion.forge.GenerationGate;

/** Admission facts from the actual native disk-read and new-ProtoChunk path. */
@Mixin(ChunkMap.class)
public abstract class GenerationChunkMapMixin implements GenerationBridge {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow @Final ServerLevel level;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Override public boolean vre$generationBusy(long position) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Generation holder evidence requires the server thread");
        return updatingChunkMap.containsKey(position) || visibleChunkMap.containsKey(position) || pendingUnloads.containsKey(position);
    }
    @Override public Executor vre$generationExecutor() { return mainThreadExecutor; }

    @Inject(method = "readChunk", at = @At("RETURN"), cancellable = true)
    private void vre$observeDiskRead(ChunkPos position,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback) {
        callback.setReturnValue(GenerationGate.observeNativeRead(level, position, callback.getReturnValue(), mainThreadExecutor));
    }

    @Inject(method = "createEmptyChunk", at = @At("RETURN"))
    private void vre$bindFreshIdentity(ChunkPos position, CallbackInfoReturnable<ChunkAccess> callback) {
        GenerationGate.observeFreshChunk(level, callback.getReturnValue());
    }
}
