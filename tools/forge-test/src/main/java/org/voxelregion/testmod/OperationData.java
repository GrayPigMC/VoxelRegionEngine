/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.testmod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.voxelregion.geometry.Sphere;
import org.voxelregion.geometry.Volume;

final class OperationData extends SavedData {
    enum State { FUSE, RUNNING, PAUSED, PAUSED_UNLOADED, PAUSED_PROTECTION, PAUSED_RECOVERY, FAILED, CANCELLED, WRITTEN }
    final List<Task> tasks = new ArrayList<>();

    static OperationData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(OperationData::load, OperationData::new, "voxelregion_test_operations");
    }

    static OperationData load(CompoundTag tag) {
        OperationData data = new OperationData();
        ListTag list = tag.getList("operations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                Task task = Task.load(list.getCompound(i));
                if (!task.terminal()) {
                    task.state = State.PAUSED_RECOVERY;
                    task.message = "Restart: mutation/lighting/save acknowledgement is uncertain; replay is disabled. Inspect, then cancel and create a new explicit operation.";
                }
                data.tasks.add(task);
            } catch (RuntimeException ex) {
                VoxelRegionTestMod.LOGGER.error("Invalid persisted region operation; it will not execute", ex);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Task task : tasks) list.add(task.save());
        tag.put("operations", list);
        tag.putInt("schema", 1);
        return tag;
    }

    static final class Task {
        final UUID id;
        final UUID actor;
        final double cx, cy, cz;
        final int radius;
        final boolean generate;
        final boolean clearFluids;
        final double resistance;
        final double entityDamage;
        final boolean protectEveryClaim;
        final AdapterConfig.Physics physics;
        final Volume volume;
        State state = State.FUSE;
        String message = "";
        int fuse;
        int chunkX, chunkZ, sectionY, voxel;
        boolean chunkStarted, entitiesHandled;
        long visited, changed, kept, skippedChunks, finishedChunks;

        Task(UUID id, UUID actor, double cx, double cy, double cz, int radius, int fuse,
             boolean generate, boolean clearFluids, double resistance, double entityDamage, boolean protectEveryClaim, AdapterConfig.Physics physics, int minSectionY) {
            this.id = id;
            this.actor = actor;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.radius = radius;
            this.fuse = fuse;
            this.generate = generate;
            this.clearFluids = clearFluids;
            this.resistance = resistance;
            this.entityDamage = entityDamage;
            this.protectEveryClaim = protectEveryClaim;
            this.physics = physics;
            volume = Sphere.of(cx, cy, cz, radius);
            chunkX = Math.floorDiv(volume.bounds().minX(), 16);
            chunkZ = Math.floorDiv(volume.bounds().minZ(), 16);
            sectionY = minSectionY;
        }

        boolean terminal() { return state == State.CANCELLED || state == State.WRITTEN || state == State.FAILED; }
        boolean active() { return state == State.FUSE || state == State.RUNNING; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putUUID("actor", actor);
            t.putDouble("cx", cx); t.putDouble("cy", cy); t.putDouble("cz", cz);
            t.putInt("radius", radius); t.putInt("fuse", fuse); t.putBoolean("generate", generate);
            t.putBoolean("clearFluids", clearFluids);
            t.putDouble("resistance", resistance); t.putDouble("entityDamage", entityDamage);
            t.putBoolean("protectEveryClaim", protectEveryClaim);
            t.putString("physics", physics.name());
            t.putString("state", state.name()); t.putString("message", message);
            t.putInt("chunkX", chunkX); t.putInt("chunkZ", chunkZ); t.putInt("sectionY", sectionY); t.putInt("voxel", voxel);
            t.putBoolean("chunkStarted", chunkStarted); t.putBoolean("entitiesHandled", entitiesHandled);
            t.putLong("visited", visited); t.putLong("changed", changed); t.putLong("kept", kept);
            t.putLong("skippedChunks", skippedChunks); t.putLong("finishedChunks", finishedChunks);
            return t;
        }

        static Task load(CompoundTag t) {
            Task job = new Task(t.getUUID("id"), t.getUUID("actor"), t.getDouble("cx"), t.getDouble("cy"), t.getDouble("cz"),
                    t.getInt("radius"), t.getInt("fuse"), t.getBoolean("generate"),
                    !t.contains("clearFluids") || t.getBoolean("clearFluids"), t.getDouble("resistance"),
                    t.getDouble("entityDamage"), t.getBoolean("protectEveryClaim"),
                    t.contains("physics") ? AdapterConfig.Physics.valueOf(t.getString("physics")) : AdapterConfig.Physics.NONE, t.getInt("sectionY"));
            job.state = State.valueOf(t.getString("state")); job.message = t.getString("message");
            job.chunkX = t.getInt("chunkX"); job.chunkZ = t.getInt("chunkZ"); job.voxel = t.getInt("voxel");
            job.chunkStarted = t.getBoolean("chunkStarted"); job.entitiesHandled = t.getBoolean("entitiesHandled");
            job.visited = t.getLong("visited"); job.changed = t.getLong("changed"); job.kept = t.getLong("kept");
            job.skippedChunks = t.getLong("skippedChunks"); job.finishedChunks = t.getLong("finishedChunks");
            return job;
        }
    }
}
