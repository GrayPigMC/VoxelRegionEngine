/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.testmod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;

/** Receipts serialize in the same ForgeCaps chunk NBT as the mutated terrain. */
final class ChunkReceipts implements ICapabilitySerializable<CompoundTag> {
    static final Capability<ChunkReceipts> CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {});
    static final byte STARTED = 1, COMPLETE = 2;
    private static final int LIMIT = 1024;
    private final Map<UUID, Byte> states = new LinkedHashMap<>();
    private final LazyOptional<ChunkReceipts> self = LazyOptional.of(() -> this);

    static void attach(AttachCapabilitiesEvent<LevelChunk> event) {
        ChunkReceipts data = new ChunkReceipts();
        event.addCapability(new ResourceLocation(VoxelRegionTestMod.ID, "receipts"), data);
        event.addListener(data.self::invalidate);
    }

    static ChunkReceipts of(LevelChunk chunk) {
        return chunk.getCapability(CAPABILITY).orElseThrow(() -> new IllegalStateException("Chunk receipt capability missing"));
    }

    byte state(UUID operation) { return states.getOrDefault(operation, (byte) 0); }
    void mark(UUID operation, byte state) {
        if (state != STARTED && state != COMPLETE) throw new IllegalArgumentException("Invalid receipt state");
        if (state(operation) == COMPLETE && state != COMPLETE)
            throw new IllegalStateException("A completed operation receipt cannot move backwards");
        if (!states.containsKey(operation) && states.size() >= LIMIT)
            throw new IllegalStateException("Chunk receipt capacity reached; refusing to discard recovery evidence");
        states.put(operation, state);
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        return capability == CAPABILITY ? self.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        states.forEach((id, state) -> tag.putByte(id.toString(), state));
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag.getAllKeys().size() > LIMIT) throw new IllegalStateException("Too many region operation receipts");
        Map<UUID, Byte> restored = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            byte state = tag.getByte(key);
            if (state != STARTED && state != COMPLETE) throw new IllegalStateException("Invalid region receipt state");
            try { restored.put(UUID.fromString(key), state); }
            catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid region receipt UUID", ex); }
        }
        states.clear();
        states.putAll(restored);
    }
}
