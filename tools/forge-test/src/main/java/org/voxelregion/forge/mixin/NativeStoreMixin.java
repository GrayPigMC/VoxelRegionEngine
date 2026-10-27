/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.NativeSaveTracker;

@Mixin(IOWorker.class)
abstract class NativeStoreMixin {
    @Inject(method = "store", at = @At("RETURN"))
    private void vre$observeStore(ChunkPos position, CompoundTag data,
                                 CallbackInfoReturnable<CompletableFuture<Void>> result) {
        NativeSaveTracker.stored((IOWorker) (Object) this, position, result.getReturnValue());
    }
}
