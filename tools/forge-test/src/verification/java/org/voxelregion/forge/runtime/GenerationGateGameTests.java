/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.GenerationGate;
import org.voxelregion.forge.GenerationSafetyOracle;
import org.voxelregion.forge.NativeCommitFences;
import org.voxelregion.geometry.BoxVolume;
import org.voxelregion.geometry.Volume;
import org.voxelregion.journal.FileOperationJournal;
import org.voxelregion.journal.OperationIntent;
import org.voxelregion.planner.GenerationSafety;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;

/** Native generation, cancellation and saved-image tests in dedicated verification dimensions. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class GenerationGateGameTests {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_generation_test", Comparator.<UUID>naturalOrder());
    private enum Change { NONE, PROTECTION_CHANGED, PROTECTION_EXCEPTION, BORDER_SHRUNK }

    @GameTest(template = "empty", batch = "vre_generation_flat_clear", timeoutTicks = 1000)
    public static void nativeFlatShortcutReachesFullLightAndDisk(GameTestHelper helper) throws IOException {
        generationCase(helper, 1024, true, false, false);
    }

    @GameTest(template = "empty", batch = "vre_generation_flat_keep_water", timeoutTicks = 1000)
    public static void nativeFlatShortcutPreservesConfiguredWater(GameTestHelper helper) throws IOException {
        generationCase(helper, 1088, false, false, false);
    }

    @GameTest(template = "empty", batch = "vre_generation_revoked", timeoutTicks = 1000)
    public static void revocationBeforeNoiseRunsOriginalGeneration(GameTestHelper helper) throws IOException {
        generationCase(helper, 1152, true, true, false);
    }

    @GameTest(template = "empty", batch = "vre_generation_cancelled", timeoutTicks = 1000)
    public static void cancelledPermitCannotBeReusedByGeneration(GameTestHelper helper) throws IOException {
        generationCase(helper, 1216, true, false, true);
    }

    @GameTest(template = "empty", batch = "vre_generation_changed_rule", timeoutTicks = 1000)
    public static void changedProtectionBeforeNoiseRunsOriginalGeneration(GameTestHelper helper) throws IOException {
        generationCase(helper, 1280, true, false, false, Change.PROTECTION_CHANGED);
    }

    @GameTest(template = "empty", batch = "vre_generation_unknown_rule", timeoutTicks = 1000)
    public static void unreadableProtectionBeforeNoiseRunsOriginalGeneration(GameTestHelper helper) throws IOException {
        generationCase(helper, 1344, true, false, false, Change.PROTECTION_EXCEPTION);
    }

    @GameTest(template = "empty", batch = "vre_generation_changed_border", timeoutTicks = 1000)
    public static void shrunkenBorderBeforeNoiseRunsOriginalGeneration(GameTestHelper helper) throws IOException {
        generationCase(helper, 1408, true, false, false, Change.BORDER_SHRUNK);
    }

    private static void generationCase(GameTestHelper helper, int chunkX, boolean clearFluids,
            boolean revoke, boolean cancel) throws IOException {
        generationCase(helper, chunkX, clearFluids, revoke, cancel, Change.NONE);
    }

    private static void generationCase(GameTestHelper helper, int chunkX, boolean clearFluids,
            boolean revoke, boolean cancel, Change change) throws IOException {
        ServerLevel level = fixtureLevel(helper, change == Change.BORDER_SHRUNK ? "generation_border" : "generation_flat");
        ChunkKey key = new ChunkKey(chunkX, 1024);
        ChunkPos position = new ChunkPos(key.x(), key.z());
        UUID operation = UUID.randomUUID();
        String hash = persistIntent(level, operation, key, clearFluids);
        AtomicBoolean permission = new AtomicBoolean(true);
        AtomicBoolean policyChanged = new AtomicBoolean(false);
        GenerationGate.Rules rules = new GenerationGate.Rules(true, clearFluids, 7, state -> {
            if (policyChanged.get() && change == Change.PROTECTION_EXCEPTION)
                throw new IllegalStateException("Deliberately unavailable protection fixture");
            return !state.is(Blocks.BEDROCK) && !(policyChanged.get() && change == Change.PROTECTION_CHANGED);
        });
        Volume full = new BoxVolume(key.bounds(level.getMinBuildHeight(), level.getMaxBuildHeight()));
        CompletableFuture<GenerationGate.Registration> prepared = GenerationGate.register(level, operation, hash,
                key, full, FrozenOcclusion.Mode.NONE, rules, () -> permission.get());
        AtomicReference<GenerationGate.Registration> registration = new AtomicReference<>();
        AtomicReference<LevelChunk> generated = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> saved = new AtomicReference<>();
        AtomicReference<CompletableFuture<Optional<CompoundTag>>> read = new AtomicReference<>();
        boolean applied = !revoke && !cancel && change == Change.NONE;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(prepared.isDone(), "Asynchronous absence/profile admission finished"))
                .thenExecute(() -> {
                    GenerationGate.Registration admission = prepared.join();
                    registration.set(admission);
                    helper.assertTrue(admission.safety().decision() == GenerationSafety.Decision.SAFE,
                            "Verification flat profile must be SAFE: " + admission.safety().evidence());
                    if (revoke) permission.set(false);
                    if (cancel) admission.close();
                    policyChanged.set(change != Change.NONE);
                    level.getChunkSource().addRegionTicket(HOLD, position, 0, operation);
                    // Deliberately exercise native managedBlock: the NOISE callback
                    // must use ChunkMap's own main executor, not the server queue.
                    var border = level.getWorldBorder();
                    double oldBorderX = border.getCenterX(), oldBorderZ = border.getCenterZ(), oldBorderSize = border.getSize();
                    LevelChunk chunk;
                    try {
                        if (change == Change.BORDER_SHRUNK) {
                            border.setCenter(position.getMinBlockX() + 8, position.getMinBlockZ() + 8);
                            border.setSize(8);
                            helper.assertTrue(GenerationSafetyOracle.assess(level, key, full, FrozenOcclusion.Mode.NONE, rules).decision()
                                            == GenerationSafety.Decision.REQUIRES_GENERATION,
                                    "A partially bordered chunk fails a new admission even though the native chunk overlaps the border");
                        }
                        chunk = level.getChunk(position.x, position.z);
                    } finally {
                        if (change == Change.BORDER_SHRUNK) {
                            border.setCenter(oldBorderX, oldBorderZ);
                            border.setSize(oldBorderSize);
                        }
                    }
                    generated.set(chunk);
                    helper.assertTrue(chunk.getStatus() == ChunkStatus.FULL && chunk.isLightCorrect(),
                            "Native pipeline reached FULL and completed lighting");
                    helper.assertTrue(admission.result().isDone(), "NOISE admission has a terminal outcome");
                    GenerationGate.Result outcome = admission.result().join();
                    GenerationGate.Outcome expected = cancel ? GenerationGate.Outcome.CANCELLED
                            : !applied ? GenerationGate.Outcome.NATIVE_FALLBACK : GenerationGate.Outcome.APPLIED_SAFE_FLAT;
                    helper.assertTrue(outcome.outcome() == expected && admission.applied() == applied,
                            "Recorded branch matches actual permission/cancellation outcome: " + outcome);
                    int bottom = level.getMinBuildHeight();
                    BlockPos bedrock = new BlockPos(position.getMinBlockX() + 8, bottom, position.getMinBlockZ() + 8);
                    BlockPos dirt = bedrock.above(2), water = bedrock.above(5);
                    helper.assertTrue(chunk.getBlockState(bedrock).is(Blocks.BEDROCK), "Actual configured bedrock layer retained at min build height");
                    helper.assertTrue(applied ? chunk.getBlockState(dirt).isAir() : chunk.getBlockState(dirt).is(Blocks.DIRT),
                            "Dirt reflects shortcut vs original native generation");
                    helper.assertTrue(applied && clearFluids ? chunk.getBlockState(water).isAir() : chunk.getBlockState(water).is(Blocks.WATER),
                            "Water follows frozen clearFluids only on the authorized branch");
                    int expectedHeight = applied && clearFluids ? bottom + 1 : bottom + 6;
                    helper.assertTrue(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8) + 1 == expectedHeight,
                            "Native heightmap matches retained layers");
                    if (applied) helper.assertTrue(outcome.skippedBlockWrites() == (clearFluids ? 1280 : 1024)
                                    && outcome.preservedBlockWrites() == (clearFluids ? 256 : 512),
                            "Shortcut skipped exactly the unneeded original flat writes");
                    // A second admission must reject an already generated/in-flight holder.
                    var second = GenerationGate.register(level, UUID.randomUUID(), hash, key, full,
                            FrozenOcclusion.Mode.NONE, rules, () -> true).join();
                    helper.assertTrue(second.safety().decision() != GenerationSafety.Decision.SAFE,
                            "Existing generated chunk cannot be replaced by a new generation permit");
                    second.close();
                    saved.set(NativeCommitFences.saveAfterLighting(level, chunk));
                })
                .thenWaitUntil(() -> helper.assertTrue(saved.get().isDone(), "Native light/save fence completed"))
                .thenExecute(() -> {
                    saved.get().join();
                    read.set(((IOWorker) level.getChunkSource().chunkMap.chunkScanner()).loadAsync(position));
                })
                .thenWaitUntil(() -> helper.assertTrue(read.get().isDone(), "Native persisted image loaded"))
                .thenExecute(() -> {
                    try {
                        CompoundTag image = read.get().join().orElseThrow(() -> new AssertionError("No native generation image saved"));
                        helper.assertTrue(image.getString("Status").equals(ChunkStatus.FULL.toString()) && image.getBoolean("isLightOn"),
                                "Saved status/light flags came from the actual native pipeline");
                        int bottom = level.getMinBuildHeight();
                        BlockPos bedrock = new BlockPos(position.getMinBlockX() + 8, bottom, position.getMinBlockZ() + 8);
                        helper.assertTrue(savedState(image, bedrock).is(Blocks.BEDROCK), "Persisted terrain retains configured protected layer");
                        helper.assertTrue(applied ? savedState(image, bedrock.above(2)).isAir() : savedState(image, bedrock.above(2)).is(Blocks.DIRT),
                                "Persisted terrain agrees with NOISE outcome");
                        helper.assertTrue(applied && clearFluids ? savedState(image, bedrock.above(5)).isAir() : savedState(image, bedrock.above(5)).is(Blocks.WATER),
                                "Persisted fluid layer agrees with policy");
                        helper.assertTrue(image.getCompound("Heightmaps").contains("WORLD_SURFACE"), "Native heightmap is serialized");
                        helper.assertTrue(!image.getList("sections", Tag.TAG_COMPOUND).isEmpty(), "Native sections and biome palettes are serialized");
                        finishIntent(level, operation);
                        RuntimeBridge.LOGGER.info("Generation validation: chunk={}, clearFluids={}, revoke={}, cancel={}, change={}, outcome={}, native FULL/light/save verified",
                                position, clearFluids, revoke, cancel, change, registration.get().result().join());
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    } finally {
                        registration.get().close();
                        level.getChunkSource().removeRegionTicket(HOLD, position, 0, operation);
                    }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_generation_moving_border", timeoutTicks = 100)
    public static void movingBorderRequiresOrdinaryPerBlockPlanning(GameTestHelper helper) {
        ServerLevel level = fixtureLevel(helper, "generation_border");
        var border = level.getWorldBorder();
        double oldX = border.getCenterX(), oldZ = border.getCenterZ(), oldSize = border.getSize();
        try {
            border.setCenter(0, 0);
            border.setSize(4096);
            ChunkKey key = new ChunkKey(0, 0);
            Volume full = new BoxVolume(key.bounds(level.getMinBuildHeight(), level.getMaxBuildHeight()));
            helper.assertTrue(GenerationSafetyOracle.assess(level, key, full, FrozenOcclusion.Mode.NONE, rules(true)).decision()
                            == GenerationSafety.Decision.SAFE, "The stationary fixture first has a valid full-chunk proof");
            border.lerpSizeBetween(4096, 2048, 10_000);
            helper.assertTrue(GenerationSafetyOracle.assess(level, key, full, FrozenOcclusion.Mode.NONE, rules(true)).decision()
                            == GenerationSafety.Decision.REQUIRES_GENERATION,
                    "A real-time moving border cannot authorize a frozen whole-chunk shortcut");
        } finally {
            border.setCenter(oldX, oldZ);
            border.setSize(oldSize);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_generation_retained_budget", timeoutTicks = 100)
    public static void retainedTerrainBudgetIsInclusiveAndRequiresSkippedWork(GameTestHelper helper) {
        for (String name : new String[] {"generation_retained_limit", "generation_retained_over_limit"}) {
            ServerLevel level = fixtureLevel(helper, name);
            ChunkKey key = new ChunkKey(2048, 2048);
            Volume full = new BoxVolume(key.bounds(level.getMinBuildHeight(), level.getMaxBuildHeight()));
            GenerationSafety result = GenerationSafetyOracle.assess(level, key, full, FrozenOcclusion.Mode.NONE,
                    new GenerationGate.Rules(true, true, 0, state -> state.is(Blocks.DIRT)));
            GenerationSafety.Decision expected = name.equals("generation_retained_limit")
                    ? GenerationSafety.Decision.SAFE : GenerationSafety.Decision.REQUIRES_GENERATION;
            helper.assertTrue(result.decision() == expected,
                    "Exactly sixteen retained layers (4096 writes) fit; seventeen must remain on the native worker: " + result);
        }
        ServerLevel flat = fixtureLevel(helper, "generation_flat");
        ChunkKey key = new ChunkKey(2048, 2048);
        GenerationSafety unchanged = GenerationSafetyOracle.assess(flat, key,
                new BoxVolume(key.bounds(flat.getMinBuildHeight(), flat.getMaxBuildHeight())), FrozenOcclusion.Mode.NONE,
                new GenerationGate.Rules(true, true, 0, state -> false));
        helper.assertTrue(unchanged.decision() == GenerationSafety.Decision.REQUIRES_GENERATION,
                "Preserving every layer cannot justify moving ordinary terrain generation onto the main thread");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "vre_generation_fallback_profiles", timeoutTicks = 100)
    public static void noiseFeaturesPartialAndContextualProfilesFallBack(GameTestHelper helper) {
        for (String name : new String[] {"generation_noise", "generation_features"}) {
            ServerLevel level = fixtureLevel(helper, name);
            ChunkKey key = new ChunkKey(2048, 2048);
            GenerationSafety safety = GenerationSafetyOracle.assess(level, key,
                    new BoxVolume(key.bounds(level.getMinBuildHeight(), level.getMaxBuildHeight())),
                    FrozenOcclusion.Mode.NONE, rules(true));
            helper.assertTrue(safety.decision() == GenerationSafety.Decision.REQUIRES_GENERATION,
                    "Unproved generator/features require ordinary generation: " + name + " " + safety);
        }
        ServerLevel flat = fixtureLevel(helper, "generation_flat");
        ChunkKey key = new ChunkKey(2048, 2048);
        Volume full = new BoxVolume(key.bounds(flat.getMinBuildHeight(), flat.getMaxBuildHeight()));
        helper.assertTrue(GenerationSafetyOracle.assess(flat, key, full, FrozenOcclusion.Mode.AXIS_6, rules(true)).decision()
                        == GenerationSafety.Decision.REQUIRES_GENERATION, "Occluded generation lacks a complete proof");
        helper.assertTrue(GenerationSafetyOracle.assess(flat, key, full, FrozenOcclusion.Mode.NONE,
                new GenerationGate.Rules(false, true, 0, state -> true)).decision() == GenerationSafety.Decision.REQUIRES_GENERATION,
                "Contextual protection must inspect the generated world");
        Volume partial = new BoxVolume(key.bounds(flat.getMinBuildHeight() + 1, flat.getMaxBuildHeight()));
        helper.assertTrue(GenerationSafetyOracle.assess(flat, key, partial, FrozenOcclusion.Mode.NONE, rules(true)).decision()
                        == GenerationSafety.Decision.REQUIRES_GENERATION, "Partial build-height coverage cannot authorize empty generation");
        helper.succeed();
    }

    private static GenerationGate.Rules rules(boolean clearFluids) {
        return new GenerationGate.Rules(true, clearFluids, 0, state -> !state.is(Blocks.BEDROCK));
    }

    private static ServerLevel fixtureLevel(GameTestHelper helper, String name) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, name));
        ServerLevel level = helper.getLevel().getServer().getLevel(key);
        if (level == null) throw new AssertionError("Verification dimension missing: " + key.location());
        return level;
    }

    private static Path journalDirectory(ServerLevel level, UUID operation) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/vre-generation-fixtures/" + operation);
    }

    private static String persistIntent(ServerLevel level, UUID operation, ChunkKey key, boolean clearFluids) throws IOException {
        byte[] payload = ("flat-fixture-v1;chunk=" + key + ";height=" + level.getMinBuildHeight() + ":" + level.getMaxBuildHeight()
                + ";clearFluids=" + clearFluids + ";protect=bedrock;propagation=NONE").getBytes(StandardCharsets.UTF_8);
        try (FileOperationJournal journal = new FileOperationJournal(journalDirectory(level, operation))) {
            journal.prepare(new OperationIntent(operation, level.dimension().location().toString(), "gametest", payload));
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void finishIntent(ServerLevel level, UUID operation) throws IOException {
        try (FileOperationJournal journal = new FileOperationJournal(journalDirectory(level, operation))) {
            journal.transition(operation, FileOperationJournal.State.COMPLETE, "Native FULL lighting and saved terrain image verified");
        }
    }

    private static BlockState savedState(CompoundTag image, BlockPos position) {
        ListTag sections = image.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (section.getByte("Y") != (position.getY() >> 4)) continue;
            var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
            return codec.parse(NbtOps.INSTANCE, section.getCompound("block_states"))
                    .getOrThrow(false, message -> { throw new AssertionError(message); })
                    .get(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
        }
        throw new AssertionError("Saved section missing");
    }

    private GenerationGateGameTests() { }
}
