/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

/** Deliberately stateful/unknown mod callbacks, present only in local verification runs. */
@Mod.EventBusSubscriber(modid = RuntimeBridge.PERSISTENCE_NAMESPACE, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ProbeBlocks {
    static final ResourceLocation SWITCHING = new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "verification_switching");
    static final ResourceLocation UNKNOWN = new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "verification_unknown_resistance");
    static final ResourceLocation PROTECTED_WATERLOGGED = new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "verification_protected_waterlogged");
    static final ResourceLocation REMOVAL_PROBE = new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "verification_removal_probe");
    static java.util.function.BiConsumer<Level, BlockPos> removalAction = (level, position) -> {};

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> {
            helper.register(SWITCHING, new Block(Block.Properties.of().strength(1, 1)) {
                @Override
                public float getExplosionResistance(BlockState state, BlockGetter reader, BlockPos position, Explosion explosion) {
                    if (reader instanceof Level level) level.setBlock(position, Blocks.END_GATEWAY.defaultBlockState(), 18);
                    return 1;
                }
            });
            helper.register(UNKNOWN, new Block(Block.Properties.of().strength(1, 1)) {
                @Override
                public float getExplosionResistance(BlockState state, BlockGetter reader, BlockPos position, Explosion explosion) {
                    return Float.NaN;
                }
            });
            helper.register(PROTECTED_WATERLOGGED, new SlabBlock(Block.Properties.of().strength(-1, 3_600_000)));
            helper.register(REMOVAL_PROBE, new Block(Block.Properties.of().strength(1, 1)) {
                @Override public void onRemove(BlockState old, Level level, BlockPos position, BlockState replacement, boolean moving) {
                    super.onRemove(old, level, position, replacement, moving);
                    if (old.getBlock() != replacement.getBlock()) removalAction.accept(level, position);
                }
            });
        });
    }
    private ProbeBlocks() {}
}
