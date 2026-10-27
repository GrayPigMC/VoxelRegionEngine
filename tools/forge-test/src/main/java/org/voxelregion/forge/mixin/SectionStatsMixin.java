/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.NativeAccess;

/** A correct non-air counter maintained on load/write; no section scan during region preparation. */
@Mixin(LevelChunkSection.class)
public abstract class SectionStatsMixin implements NativeAccess.SectionStats {
    @Shadow private short nonEmptyBlockCount;
    @Shadow @Final private PalettedContainer<BlockState> states;
    @Unique private int vre$nonAirCount;
    @Unique private boolean vre$countReady;

    @Override public short vre$nonEmptyBlockCount() { return nonEmptyBlockCount; }
    @Override public int vre$accurateNonAirCount() { return vre$countReady ? vre$nonAirCount : -1; }

    @Inject(method = "recalcBlockCounts", at = @At("RETURN"))
    private void vre$afterRecount(CallbackInfo callback) { vre$recount(); }

    @Inject(method = "read", at = @At("RETURN"))
    private void vre$afterPacketRead(FriendlyByteBuf buffer, CallbackInfo callback) { vre$recount(); }

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void vre$afterSet(int x, int y, int z, BlockState next, boolean checked,
                             CallbackInfoReturnable<BlockState> callback) {
        BlockState old = callback.getReturnValue();
        if (!vre$countReady || old == null) { vre$recount(); return; }
        if (!old.isAir()) vre$nonAirCount--;
        if (!next.isAir()) vre$nonAirCount++;
        if (vre$nonAirCount < 0 || vre$nonAirCount > 4096) vre$countReady = false;
    }

    @Unique private void vre$recount() {
        // Vanilla's recount additionally counts fluid-bearing cells; keep this separate instead
        // of changing vanilla behavior. Uniform palettes are O(1); mixed load/read counts run once.
        int[] count = { 0 };
        states.count((state, occurrences) -> { if (!state.isAir()) count[0] += occurrences; });
        vre$nonAirCount = count[0];
        vre$countReady = vre$nonAirCount >= 0 && vre$nonAirCount <= 4096;
    }
}
