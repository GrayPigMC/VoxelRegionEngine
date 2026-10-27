/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.runtime.ChunkMutationTracking;

/** Observe ordinary player, engine and mod writes without replacing native behavior. */
@Mixin(LevelChunk.class)
public abstract class ChunkMutationMixin implements org.voxelregion.forge.runtime.RuntimeBridge.MutationTracked,
        org.voxelregion.forge.NativeAccess.ChunkRevision {
    @Override
    public long vre$mutationRevision() {
        return ChunkMutationTracking.revision((LevelChunk) (Object) this);
    }

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void voxelregion$beforeMutation(BlockPos position, BlockState requested, boolean moving,
                                             CallbackInfoReturnable<BlockState> result) {
        // An external onRemove callback may reenter saving before RETURN.
        ChunkMutationTracking.changing((LevelChunk) (Object) this);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void voxelregion$recordMutation(BlockPos position, BlockState requested, boolean moving,
                                             CallbackInfoReturnable<BlockState> result) {
        BlockState old = result.getReturnValue();
        if (old != null && old != requested) ChunkMutationTracking.changed((LevelChunk) (Object) this);
    }
}
