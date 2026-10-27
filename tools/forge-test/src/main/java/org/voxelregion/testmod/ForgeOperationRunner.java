/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.testmod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.spatial.ChunkKey;
import org.voxelregion.spatial.SectionKey;

/** Server-thread baseline. No world objects or mutable palettes are sent to worker threads. */
final class ForgeOperationRunner {
    private static final TicketType<UUID> TICKET = TicketType.create("voxelregion_test", Comparator.<UUID>naturalOrder());
    private static final TagKey<Block> PROTECTED = TagKey.create(Registries.BLOCK, new ResourceLocation(VoxelRegionTestMod.ID, "protected"));
    private static final Map<UUID, HeldChunk> HELD = new HashMap<>();
    private static final Map<UUID, EntityWork> ENTITIES = new HashMap<>();
    private static int roundRobin;
    private static final int MAX_TICKETS = 2;

    static OperationData.Task submit(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse, boolean generate) throws IOException {
        return submit(level, actor, center, radius, fuse, generate, true);
    }

    static OperationData.Task submit(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse,
                                     boolean generate, boolean clearFluids) throws IOException {
        if (radius < 1 || radius > AdapterConfig.MAX_RADIUS.get() || fuse < 0 || fuse > 1200)
            throw new IllegalArgumentException("Radius/fuse outside configured limits");
        OperationData data = OperationData.get(level);
        long pending = 0;
        for (ServerLevel world : level.getServer().getAllLevels())
            pending += OperationData.get(world).tasks.stream().filter(t -> !t.terminal()).count();
        if (pending >= AdapterConfig.MAX_OPERATIONS.get()) throw new IllegalStateException("Maximum pending operations reached");
        var task = new OperationData.Task(UUID.randomUUID(), actor, center.getX() + .5, center.getY() + .5, center.getZ() + .5,
                radius, fuse, generate, clearFluids, AdapterConfig.RESISTANCE_LIMIT.get(), AdapterConfig.ENTITY_DAMAGE.get(),
                AdapterConfig.PROTECT_ALL_CLAIMS.get(), AdapterConfig.PHYSICS.get(), level.getMinSection());
        if (task.volume.bounds().maxY() <= level.getMinBuildHeight() || task.volume.bounds().minY() >= level.getMaxBuildHeight())
            throw new IllegalArgumentException("Sphere does not intersect this dimension's build height");
        IntentJournal.append(level, task);
        data.tasks.removeIf(t -> t.terminal() && data.tasks.size() > 64);
        data.tasks.add(task);
        data.setDirty();
        return task;
    }

    static void tick(MinecraftServer server) {
        long deadline = System.nanoTime() + (long) (AdapterConfig.MILLIS_PER_TICK.get() * 1_000_000);
        Budget budget = new Budget(deadline, AdapterConfig.VISITS_PER_TICK.get(), AdapterConfig.WRITES_PER_TICK.get());
        List<Work> work = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            OperationData data = OperationData.get(level);
            for (OperationData.Task task : data.tasks) {
                if (task.state == OperationData.State.FUSE) {
                    if (task.fuse > 0) {
                        if (task.fuse % 10 == 0)
                            level.sendParticles(ParticleTypes.SMOKE, task.cx, task.cy, task.cz, 4, .1, .15, .1, .01);
                        task.fuse--;
                    } else task.state = OperationData.State.RUNNING;
                    data.setDirty();
                }
                if (task.state == OperationData.State.RUNNING) work.add(new Work(level, data, task));
            }
        }
        if (work.isEmpty()) return;
        int idle = 0;
        while (budget.available() && idle < work.size()) {
            Work item = work.get(Math.floorMod(roundRobin++, work.size()));
            if (item.task.state != OperationData.State.RUNNING) { idle++; continue; }
            try {
                if (step(item, budget)) idle = 0; else idle++;
            } catch (RuntimeException ex) {
                item.task.state = OperationData.State.FAILED;
                item.task.message = "Native callback/adapter failed; no automatic retry: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                VoxelRegionTestMod.LOGGER.error("Region operation {} failed at chunk {},{}", item.task.id, item.task.chunkX, item.task.chunkZ, ex);
                release(item.task.id);
            }
            item.data.setDirty();
        }
    }

    private static boolean step(Work work, Budget budget) {
        ServerLevel level = work.level;
        OperationData.Task task = work.task;
        if (!task.entitiesHandled) return entityStep(level, task, budget);
        IntBox bounds = task.volume.bounds();
        int lastX = Math.floorDiv(bounds.maxX() - 1, 16), lastZ = Math.floorDiv(bounds.maxZ() - 1, 16);
        if (task.chunkZ > lastZ) {
            task.state = OperationData.State.WRITTEN;
            task.message = "Terrain writes submitted; native lighting/network/save queues are not a durable completion acknowledgement.";
            release(task.id);
            return true;
        }
        if (!task.chunkStarted && task.volume.classify(new ChunkKey(task.chunkX, task.chunkZ).bounds(level.getMinBuildHeight(), level.getMaxBuildHeight())) == Coverage.OUTSIDE) {
            advanceChunk(level, task, lastX);
            return true;
        }
        ClaimChecks.Decision permission = ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim);
        if (permission == ClaimChecks.Decision.UNAVAILABLE) {
            pause(task, OperationData.State.PAUSED_PROTECTION, "FTB protection is not ready or its API failed; no writes permitted.");
            return true;
        }
        if (permission == ClaimChecks.Decision.DENY) {
            task.skippedChunks++;
            advanceChunk(level, task, lastX);
            return true;
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(task.chunkX, task.chunkZ);
        if (chunk == null) {
            if (!task.generate) {
                pause(task, OperationData.State.PAUSED_UNLOADED, "Load this chunk normally, then resume; loaded-only mode never requests generation.");
                return true;
            }
            hold(level, task);
            task.message = "Waiting for normal FULL chunk generation/loading; no empty-chunk shortcut.";
            return false;
        }
        if (!hold(level, task)) return false;
        task.message = "";
        ChunkReceipts receipts = ChunkReceipts.of(chunk);
        if (!task.chunkStarted) {
            if (receipts.state(task.id) == ChunkReceipts.COMPLETE) {
                advanceChunk(level, task, lastX);
                return true;
            }
            if (receipts.state(task.id) == ChunkReceipts.STARTED) {
                pause(task, OperationData.State.PAUSED_RECOVERY, "Previously started chunk has no completion receipt; automatic replay disabled.");
                return true;
            }
            receipts.mark(task.id, ChunkReceipts.STARTED);
            chunk.setUnsaved(true);
            task.chunkStarted = true;
        }
        if (task.sectionY >= level.getMaxSection()) {
            receipts.mark(task.id, ChunkReceipts.COMPLETE);
            chunk.setUnsaved(true);
            task.finishedChunks++;
            advanceChunk(level, task, lastX);
            return true;
        }
        var section = chunk.getSection(level.getSectionIndexFromSectionY(task.sectionY));
        Coverage coverage = task.volume.classify(new SectionKey(task.chunkX, task.sectionY, task.chunkZ).bounds());
        if (coverage == Coverage.OUTSIDE || section.hasOnlyAir()) {
            task.sectionY++;
            task.voxel = 0;
            return true;
        }
        Explosion context = new Explosion(level, null, task.cx, task.cy, task.cz, task.radius, false, Explosion.BlockInteraction.KEEP);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int end = Math.min(4096, task.voxel + 64);
        while (task.voxel < end && budget.available()) {
            int index = task.voxel;
            int x = (task.chunkX << 4) + (index & 15);
            int y = (task.sectionY << 4) + (index >>> 8);
            int z = (task.chunkZ << 4) + ((index >>> 4) & 15);
            position.set(x, y, z);
            budget.visits--;
            task.visited++;
            if (y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()
                    && (coverage == Coverage.INSIDE || task.volume.contains(x, y, z))
                    && level.getWorldBorder().isWithinBounds(position)) {
                BlockState state = chunk.getBlockState(position);
                if (!state.isAir()) {
                    if (mustKeep(state, level, position, context, task.resistance, task.clearFluids)) {
                        task.kept++;
                    } else {
                        // Resolve potentially mod-defined fluid methods before the final
                        // permission and identity checks, so their callbacks cannot invalidate them.
                        BlockState replacement = task.clearFluids ? Blocks.AIR.defaultBlockState()
                                : state.getFluidState().createLegacyBlock();
                        // Revalidate immediately before each actual write, including after arbitrary mod callbacks.
                        ClaimChecks.Decision current = ClaimChecks.check(level, task.chunkX, task.chunkZ, task.protectEveryClaim);
                        if (current != ClaimChecks.Decision.ALLOW) {
                            if (current == ClaimChecks.Decision.DENY) {
                                task.skippedChunks++;
                                advanceChunk(level, task, lastX);
                            } else pause(task, OperationData.State.PAUSED_PROTECTION, "Protection changed/unavailable during commit.");
                            return true;
                        }
                        // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE. Native chunk code maintains BE, POI,
                        // heightmaps, skylight sources, dirty state, callbacks and light checks.
                        // Neighbor physics is deliberately suppressed in this isolated baseline.
                        int flags = task.physics == AdapterConfig.Physics.COMPATIBLE ? Block.UPDATE_ALL
                                : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
                        // Contextual mod protection callbacks can themselves replace this state.
                        // Never apply the old state's decision to a newly placed protected machine.
                        if (chunk.getBlockState(position) == state) {
                            if (level.setBlock(position, replacement, flags)) task.changed++;
                            budget.writes--;
                        } else task.kept++;
                    }
                }
            }
            task.voxel++;
        }
        if (task.voxel == 4096) { task.voxel = 0; task.sectionY++; }
        return true;
    }

    private static boolean mustKeep(BlockState state, ServerLevel level, BlockPos position, Explosion context, double limit, boolean clearFluids) {
        if (state.is(PROTECTED)) return true;
        // A region clear includes free liquids. Their ray-explosion hardness/resistance
        // is not a preservation rule. Explicit block tags and chunk claims still win;
        // waterlogged solids do not enter this branch and retain their host protection.
        if (state.getBlock() instanceof LiquidBlock && !state.getFluidState().isEmpty()) return !clearFluids;
        float hardness = state.getDestroySpeed(level, position);
        if (!Float.isFinite(hardness) || hardness < 0) return true;
        float resistance = state.getExplosionResistance(level, position, context);
        return !Float.isFinite(resistance) || resistance < 0 || resistance >= limit;
    }

    private static boolean entityStep(ServerLevel level, OperationData.Task task, Budget budget) {
        if (task.entityDamage <= 0) { task.entitiesHandled = true; return true; }
        EntityWork work = ENTITIES.computeIfAbsent(task.id, id -> new EntityWork(level.getEntities((Entity) null,
                new AABB(task.cx - task.radius, task.cy - task.radius, task.cz - task.radius,
                        task.cx + task.radius, task.cy + task.radius, task.cz + task.radius))));
        if (work.index == work.entities.size()) {
            ENTITIES.remove(task.id);
            task.entitiesHandled = true;
            return true;
        }
        Entity entity = work.entities.get(work.index);
        ClaimChecks.Decision claim = ClaimChecks.check(level, entity.chunkPosition().x, entity.chunkPosition().z, true);
        if (claim == ClaimChecks.Decision.UNAVAILABLE) {
            pause(task, OperationData.State.PAUSED_PROTECTION, "Entity claim protection unavailable.");
            return true;
        }
        work.index++;
        budget.visits--;
        if (claim == ClaimChecks.Decision.ALLOW && entity.isAlive() && entity.level() == level) {
            double distance = Math.sqrt(entity.distanceToSqr(task.cx, task.cy, task.cz));
            if (distance <= task.radius) {
                float damage = (float) (task.entityDamage * (1.0 - distance / task.radius));
                if (damage > 0) {
                    Explosion explosion = new Explosion(level, level.getPlayerByUUID(task.actor), task.cx, task.cy, task.cz,
                            task.radius, false, Explosion.BlockInteraction.KEEP);
                    entity.hurt(level.damageSources().explosion(explosion), damage);
                }
            }
        }
        return true;
    }

    private static boolean hold(ServerLevel level, OperationData.Task task) {
        if (HELD.containsKey(task.id)) return true;
        if (HELD.size() >= MAX_TICKETS) return false;
        ChunkPos position = new ChunkPos(task.chunkX, task.chunkZ);
        level.getChunkSource().addRegionTicket(TICKET, position, 0, task.id);
        HELD.put(task.id, new HeldChunk(level, position));
        return true;
    }

    private static void advanceChunk(ServerLevel level, OperationData.Task task, int lastX) {
        releaseTicket(task.id);
        task.chunkStarted = false;
        task.voxel = 0;
        task.sectionY = level.getMinSection();
        if (++task.chunkX > lastX) {
            task.chunkX = Math.floorDiv(task.volume.bounds().minX(), 16);
            task.chunkZ++;
        }
    }

    static void pause(OperationData.Task task, OperationData.State state, String message) {
        task.state = state;
        task.message = message;
        releaseTicket(task.id);
    }

    static boolean resume(OperationData.Task task) {
        if (task.terminal() || task.active() || task.state == OperationData.State.PAUSED_RECOVERY) return false;
        task.state = task.fuse > 0 ? OperationData.State.FUSE : OperationData.State.RUNNING;
        task.message = "";
        return true;
    }

    static void cancel(OperationData.Task task) {
        task.state = OperationData.State.CANCELLED;
        task.message = "Cancelled; previous writes are retained.";
        release(task.id);
    }

    static Component status(OperationData.Task task) {
        return Component.literal(task.id + " " + task.state + " radius=" + task.radius + " chunk=" + task.chunkX + "," + task.chunkZ
                + " clearFluids=" + task.clearFluids + " physics=" + task.physics + " propagation=NONE visited=" + task.visited + " changed=" + task.changed + " kept=" + task.kept
                + " chunks=" + task.finishedChunks + " denied=" + task.skippedChunks + (task.message.isEmpty() ? "" : " | " + task.message));
    }

    private static void releaseTicket(UUID id) {
        HeldChunk held = HELD.remove(id);
        if (held != null) held.level.getChunkSource().removeRegionTicket(TICKET, held.position, 0, id);
    }
    private static void release(UUID id) { releaseTicket(id); ENTITIES.remove(id); }
    static void stop() {
        for (UUID id : new ArrayList<>(HELD.keySet())) release(id);
        ENTITIES.clear(); roundRobin = 0;
    }
    private record Work(ServerLevel level, OperationData data, OperationData.Task task) {}
    private record HeldChunk(ServerLevel level, ChunkPos position) {}
    private static final class EntityWork {
        final List<Entity> entities;
        int index;
        EntityWork(List<Entity> entities) { this.entities = entities; }
    }
    private static final class Budget {
        final long deadline;
        int visits, writes;
        Budget(long deadline, int visits, int writes) { this.deadline = deadline; this.visits = visits; this.writes = writes; }
        boolean available() { return visits > 0 && writes > 0 && System.nanoTime() < deadline; }
    }
    private ForgeOperationRunner() {}
}
