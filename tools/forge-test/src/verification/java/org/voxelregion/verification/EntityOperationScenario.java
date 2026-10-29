/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.verification;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraftforge.common.util.FakePlayer;
import org.voxelregion.forge.ForgeEntityOperations;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;

/** Native entity lifecycle scenario, driven by a separate verification mod or GameTest. */
public final class EntityOperationScenario implements AutoCloseable {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_entity_verification", Comparator.<UUID>naturalOrder());
    private final UUID owner = UUID.randomUUID();
    private final ServerLevel level;
    private final BiConsumer<String, Map<String, Object>> report;
    private final List<ChunkPos> tickets = new ArrayList<>();
    private final Set<LevelChunk> unloaded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final List<Entity> fixtureEntities = new ArrayList<>();
    private final int x, z, y = 200;
    private final ChunkPos savedPosition, absentPosition;
    private final RegionGeometry loadedBox, savedBox;
    private LevelChunk oldSaved;
    private Cow target, outside, savedCow, vetoCow, discardTarget;
    private ItemEntity item, savedOutside, boundary, boundaryOutside;
    private FakePlayer player;
    private UUID savedTargetId, savedOutsideId, vetoId, discardId;
    private ForgeEntityOperations.Handle operation;
    private CompletableFuture<Boolean> absentRead;
    private int stage, ticks;
    private boolean closed;

    public EntityOperationScenario(ServerLevel level, BlockPos origin, BiConsumer<String, Map<String, Object>> report) {
        this.level = level;
        this.report = report;
        x = origin.getX() & ~15;
        z = origin.getZ() & ~15;
        savedPosition = new ChunkPos((x >> 4) + 32, z >> 4);
        absentPosition = new ChunkPos((x >> 4) + 256, z >> 4);
        loadedBox = new RegionGeometry.Box(new IntBox(x + 1, y, z + 1, x + 8, y + 4, z + 8));
        savedBox = new RegionGeometry.Box(new IntBox(savedPosition.getMinBlockX() + 1, y, z + 1,
                savedPosition.getMinBlockX() + 8, y + 4, z + 8));
        hold(new ChunkPos(x >> 4, z >> 4));
        hold(new ChunkPos((x >> 4) + 1, z >> 4));
        hold(savedPosition);
    }

    /** Forward real ChunkEvent.Unload; null getChunkNow alone is not proof of disposal. */
    public void unloaded(LevelChunk chunk) { unloaded.add(chunk); }

    /** Verification launcher forwards a UUID-targeted native LivingAttackEvent cancellation. */
    public boolean rejectAttack(Entity entity) { return !closed && vetoId != null && vetoId.equals(entity.getUUID()); }

    /** Returns true only after all independent observations pass. Invoke on the owning server thread. */
    public boolean tick() {
        if (closed) return true;
        if (++ticks > 1600) throw new AssertionError("Entity API fixture timed out at stage " + stage + " status " + statusText());
        if (ticks % 100 == 0) report.accept("PROGRESS", Map.of("stage", stage, "ticks", ticks, "operation", statusText()));
        switch (stage) {
            case 0 -> {
                for (var position : tickets)
                    if (level.getChunkSource().getChunkNow(position.x, position.z) == null || !level.areEntitiesLoaded(position.toLong())) return false;
                oldSaved = level.getChunkSource().getChunkNow(savedPosition.x, savedPosition.z);
                target = cow(x + 3, y + 1, z + 3);
                outside = cow(x + 10, y + 1, z + 3);
                vetoCow = cow(x + 7, y + 1, z + 3); vetoId = vetoCow.getUUID();
                item = item(x + 5, y + 1, z + 3);
                savedCow = cow(savedPosition.getMinBlockX() + 3, y + 1, z + 3);
                savedOutside = item(savedPosition.getMinBlockX() + 12, y + 1, z + 3);
                savedTargetId = savedCow.getUUID(); savedOutsideId = savedOutside.getUUID();
                player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "VREEntityFixture")) {
                    @Override public boolean isInvulnerableTo(DamageSource source) { return false; }
                };
                player.setPos(x + 6, y + 1, z + 3);
                level.addNewPlayer(player);
                check(level.getEntity(player.getUUID()) == player && player.isAlive(), "Registered player is alive and queryable");
                operation = ForgeEntityOperations.submit(level, loadedBox,
                        new ForgeEntityOperations.Options(false, ForgeEntityOperations.Scope.LOADED_ONLY, ForgeEntityOperations.Mode.KILL));
                stage = 1;
            }
            case 1 -> {
                if (!applied()) return false;
                check(!target.isAlive() && item.isRemoved(), "KILL applies actual mob death and removes item");
                check(outside.isAlive() && player.isAlive(), "Outside mob and excluded player remain alive");
                check(status().skippedPlayers() >= 1, "Explicit player exclusion is reported");
                check(vetoCow.isAlive() && status().surviving() >= 1, "Native kill veto preserves its target and reports a survivor");
                report.accept("LOADED_KILL_PASS", Map.of("affected", status().affected(), "skippedPlayers", status().skippedPlayers(),
                        "nativeKillVetoSurvivors", status().surviving()));
                check(ForgeEntityOperations.Options.DEFAULT.includePlayers(), "Default explicitly includes players");
                operation = ForgeEntityOperations.submit(level, loadedBox, ForgeEntityOperations.Options.DEFAULT);
                stage = 2;
            }
            case 2 -> {
                if (!applied()) return false;
                check(!player.isAlive(), "Default KILL kills the included player");
                check(!player.isRemoved(), "KILL does not discard the player object");
                level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                player = null;
                // Two radius 0.5 spheres tangent at a chunk boundary have a continuous
                // intersection point, but no selected voxel center in their intersection.
                boundary = item(x + 16, y + 2, z + 8);
                boundaryOutside = item(x + 16.75, y + 2, z + 8);
                var tangent = new RegionGeometry.Combined(RegionGeometry.BooleanOperation.INTERSECTION,
                        new RegionGeometry.Ball(Point3.of(x + 15.5, y + 2, z + 8), 1),
                        new RegionGeometry.Ball(Point3.of(x + 16.5, y + 2, z + 8), 1));
                check(tangent.containsPoint(x + 16, y + 2, z + 8)
                        && !tangent.volume().contains(x + 16, y + 2, z + 8), "Continuous tangent does not select a voxel center");
                operation = ForgeEntityOperations.submit(level, tangent,
                        new ForgeEntityOperations.Options(false, ForgeEntityOperations.Scope.LOADED_ONLY, ForgeEntityOperations.Mode.DISCARD));
                stage = 3;
            }
            case 3 -> {
                if (!applied()) return false;
                check(boundary.isRemoved(), "Continuous sphere center on chunk boundary is cleared");
                check(!boundaryOutside.isRemoved(), "Entity outside exact sphere is retained despite broad bounds");
                check(outside.isAlive(), "Outside control still alive");
                var cancelBox = new RegionGeometry.Box(new IntBox(x + 9, y, z + 1, x + 12, y + 4, z + 5));
                operation = ForgeEntityOperations.submit(level, cancelBox, ForgeEntityOperations.Options.DEFAULT);
                check(ForgeEntityOperations.cancel(level, operation.id()), "Queued cancellation accepted");
                check(status().state() == ForgeEntityOperations.State.CANCELLED && status().affected() == 0 && outside.isAlive(),
                        "Cancellation before processing leaves target unchanged");
                report.accept("PLAYER_BOUNDARY_CANCEL_PASS", Map.of("playerKilled", true, "boundaryDiscarded", true, "outsideRetained", true));
                entityManager().saveAll();
                release(savedPosition);
                operation = null;
                stage = 4;
            }
            case 4 -> {
                if (!trulyUnloaded()) return false;
                check(savedCow.isRemoved() && savedOutside.isRemoved(), "Native entity unload retired both original objects");
                check(level.getEntity(savedTargetId) == null && level.getEntity(savedOutsideId) == null, "Saved entities are absent from loaded lookup");
                entityManager().saveAll();
                operation = ForgeEntityOperations.submit(level, savedBox,
                        new ForgeEntityOperations.Options(false, ForgeEntityOperations.Scope.LOADED_ONLY, ForgeEntityOperations.Mode.DISCARD));
                stage = 5;
            }
            case 5 -> {
                if (!applied()) return false;
                check(status().affected() == 0 && !level.areEntitiesLoaded(savedPosition.toLong()), "Loaded-only query does not load or affect saved entities");
                hold(savedPosition); stage = 6;
            }
            case 6 -> {
                var current = level.getChunkSource().getChunkNow(savedPosition.x, savedPosition.z);
                if (current == null || !level.areEntitiesLoaded(savedPosition.toLong())) return false;
                check(current != oldSaved, "Native terrain reload creates a new chunk object");
                check(level.getEntity(savedTargetId) != savedCow && level.getEntity(savedTargetId) != null
                        && level.getEntity(savedTargetId).isAlive(), "Loaded-only left saved target alive through actual reload");
                check(level.getEntity(savedOutsideId) != null && level.getEntity(savedOutsideId).isAlive(), "Same-chunk outside saved control reloads");
                oldSaved = current; unloaded.remove(oldSaved);
                entityManager().saveAll();
                release(savedPosition); operation = null; stage = 7;
            }
            case 7 -> {
                if (!trulyUnloaded()) return false;
                entityManager().saveAll();
                operation = ForgeEntityOperations.submit(level, savedBox, ForgeEntityOperations.Options.DEFAULT);
                stage = 8;
            }
            case 8 -> {
                if (!applied()) return false;
                check(status().affected() >= 1, "Saved-scope operation affected an actually unloaded entity");
                // Native damage/death callbacks are allowed to access terrain. The scanner
                // itself does not request terrain; DISCARD tests that narrower contract below.
                report.accept("SAVED_KILL_NATIVE_CALLBACKS", Map.of("terrainLoadedAfterKill",
                        level.getChunkSource().getChunkNow(savedPosition.x, savedPosition.z) != null));
                entityManager().saveAll();
                hold(savedPosition); stage = 9;
            }
            case 9 -> {
                var current = level.getChunkSource().getChunkNow(savedPosition.x, savedPosition.z);
                if (current == null || !level.areEntitiesLoaded(savedPosition.toLong())) return false;
                var dead = level.getEntity(savedTargetId);
                check(dead == null || !dead.isAlive(), "Killed saved target does not return alive after reload");
                var kept = level.getEntity(savedOutsideId);
                check(kept != null && kept.isAlive(), "Outside-volume saved entity survives same-chunk load/action/save cycle");
                report.accept("SAVED_SCOPE_PASS", Map.of("loadedOnlyPreserved", true, "defaultKilledSaved", true, "outsideSavedRetained", true));
                discardTarget = cow(savedPosition.getMinBlockX() + 3, y + 1, z + 3); discardId = discardTarget.getUUID();
                oldSaved = current; unloaded.remove(oldSaved);
                entityManager().saveAll(); release(savedPosition); operation = null; stage = 13;
            }
            case 10 -> {
                if (!absentRead.isDone()) return false;
                check(absentRead.join(), "Far no-terrain control is initially absent on disk");
                var region = new RegionGeometry.Box(new IntBox(absentPosition.getMinBlockX() + 1, y, z + 1,
                        absentPosition.getMinBlockX() + 8, y + 4, z + 8));
                operation = ForgeEntityOperations.submit(level, region, ForgeEntityOperations.Options.DEFAULT);
                stage = 11;
            }
            case 11 -> {
                if (!applied()) return false;
                check(status().affected() == 0, "Empty absent entity region has no affected entities");
                check(level.getChunkSource().getChunkNow(absentPosition.x, absentPosition.z) == null, "Absent entity scan did not load/generate a terrain chunk");
                absentRead = absentTerrain(); stage = 12;
            }
            case 12 -> {
                if (!absentRead.isDone()) return false;
                check(absentRead.join(), "No terrain was written by entity-only operation");
                report.accept("PASS", Map.of("ticks", ticks, "loadedKill", true, "playerDefaultKill", true,
                        "exactBoundaryDiscard", true, "savedUnloadReload", true, "outsidePreserved", true,
                        "queuedCancel", true, "noTerrainGenerated", true, "savedDiscardNoTerrain", true, "nativeKillVeto", true));
                close(); return true;
            }
            case 13 -> {
                if (!trulyUnloaded()) return false;
                check(discardTarget.isRemoved() && level.getEntity(discardId) == null, "Discard fixture genuinely unloaded its original entity");
                entityManager().saveAll();
                operation = ForgeEntityOperations.submit(level, savedBox,
                        new ForgeEntityOperations.Options(false, ForgeEntityOperations.Scope.LOAD_EXISTING_SAVED, ForgeEntityOperations.Mode.DISCARD));
                stage = 14;
            }
            case 14 -> {
                if (!applied()) return false;
                check(status().affected() >= 1, "Saved DISCARD affects actually unloaded target");
                check(level.getChunkSource().getChunkNow(savedPosition.x, savedPosition.z) == null, "Saved non-player DISCARD does not load terrain");
                entityManager().saveAll(); hold(savedPosition); stage = 15;
            }
            case 15 -> {
                var current = level.getChunkSource().getChunkNow(savedPosition.x, savedPosition.z);
                if (current == null || !level.areEntitiesLoaded(savedPosition.toLong())) return false;
                check(current != oldSaved, "Discard readback reloads a fresh terrain chunk");
                check(level.getEntity(discardId) == null, "Discarded saved target remains absent after native save/reload");
                var kept = level.getEntity(savedOutsideId);
                check(kept != null && kept.isAlive(), "Outside saved control remains after DISCARD save/reload");
                report.accept("SAVED_DISCARD_PASS", Map.of("noTerrainLoaded", true, "targetAbsentOnReload", true, "outsideRetained", true));
                absentRead = absentTerrain(); stage = 10;
            }
            default -> throw new AssertionError("Unknown fixture stage " + stage);
        }
        return false;
    }

    private boolean trulyUnloaded() {
        return unloaded.contains(oldSaved) && level.getChunkSource().getChunkNow(savedPosition.x, savedPosition.z) == null
                && !level.areEntitiesLoaded(savedPosition.toLong());
    }
    private CompletableFuture<Boolean> absentTerrain() {
        var storage = level.getChunkSource().chunkMap.chunkScanner();
        check(storage instanceof IOWorker, "Fixture uses real native terrain storage");
        return ((IOWorker)storage).loadAsync(absentPosition).thenApply(java.util.Optional::isEmpty);
    }
    private ForgeEntityOperations.Status status() { return ForgeEntityOperations.status(level, operation.id()).orElseThrow(); }
    private String statusText() { return operation == null ? "none" : status().toString(); }
    private boolean applied() {
        var status = status();
        check(status.state() != ForgeEntityOperations.State.FAILED && status.state() != ForgeEntityOperations.State.CANCELLED,
                "Operation failed before application: " + status);
        return status.state() == ForgeEntityOperations.State.APPLIED;
    }
    private Cow cow(double atX, double atY, double atZ) {
        var cow = EntityType.COW.create(level);
        check(cow != null, "Cow entity factory");
        cow.setNoAi(true); cow.setNoGravity(true); cow.setPersistenceRequired(); cow.setPos(atX, atY, atZ);
        check(level.addFreshEntity(cow), "Cow insertion"); fixtureEntities.add(cow); return cow;
    }
    private ItemEntity item(double atX, double atY, double atZ) {
        var item = new ItemEntity(level, atX, atY, atZ, new ItemStack(Items.DIAMOND));
        item.setNoGravity(true); item.setDeltaMovement(0, 0, 0); item.setPickUpDelay(32767);
        check(level.addFreshEntity(item), "Item insertion"); fixtureEntities.add(item); return item;
    }
    private void hold(ChunkPos position) {
        level.getChunkSource().addRegionTicket(HOLD, position, 0, owner); tickets.add(position);
    }
    private void release(ChunkPos position) {
        level.getChunkSource().removeRegionTicket(HOLD, position, 0, owner); tickets.remove(position);
    }
    @SuppressWarnings("unchecked")
    private PersistentEntitySectionManager<Entity> entityManager() {
        try {
            for (Field field : ServerLevel.class.getDeclaredFields())
                if (PersistentEntitySectionManager.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true); return (PersistentEntitySectionManager<Entity>)field.get(level);
                }
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Native entity manager", failure); }
        throw new AssertionError("Native entity manager field missing");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (operation != null && !status().terminal()) ForgeEntityOperations.cancel(level, operation.id());
        if (player != null) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        for (Entity entity : fixtureEntities) if (!entity.isRemoved()) entity.discard();
        for (var position : List.copyOf(tickets)) release(position);
    }
}
