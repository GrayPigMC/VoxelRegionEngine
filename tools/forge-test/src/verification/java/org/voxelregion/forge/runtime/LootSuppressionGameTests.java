/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.voxelregion.forge.DropSuppression;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.propagation.FrozenOcclusion;

/** Counts real loot-table evaluation without performing an expensive exploration-map search. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
@Mod.EventBusSubscriber(modid = RuntimeBridge.PERSISTENCE_NAMESPACE)
public final class LootSuppressionGameTests {
    private static final ResourceLocation TABLE = new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "chests/verification_counted_container");
    private static final ResourceLocation CALLBACK = new ResourceLocation(RuntimeBridge.PERSISTENCE_NAMESPACE, "verification_loot_neighbor");
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final TicketType<UUID> HOLD = TicketType.create("vre_loot_fixture", Comparator.<UUID>naturalOrder());
    private static BiConsumer<LevelReader, BlockPos> neighborAction = (level, removed) -> {};

    @SubscribeEvent
    public static void countedTable(LootTableLoadEvent event) {
        if (!TABLE.equals(event.getName())) return;
        event.setTable(LootTable.lootTable().setParamSet(LootContextParamSets.CHEST)
                .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1)).add(LootItem.lootTableItem(Items.EMERALD)))
                .apply(() -> new LootItemFunction() {
                    @Override public ItemStack apply(ItemStack stack, LootContext context) {
                        GENERATIONS.incrementAndGet(); return stack;
                    }
                    @Override public LootItemFunctionType getType() {
                        // Runtime verification instrumentation is never serialized. Vanilla's
                        // composite runtime functions use the same unsupported-type contract.
                        throw new UnsupportedOperationException("Runtime-only counted loot function");
                    }
                }).build());
    }

    @Mod.EventBusSubscriber(modid = RuntimeBridge.PERSISTENCE_NAMESPACE, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void register(RegisterEvent event) {
            event.register(ForgeRegistries.Keys.BLOCKS, helper -> helper.register(CALLBACK,
                    new Block(Block.Properties.of().strength(1)) {
                        @Override public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
                            neighborAction.accept(level, neighbor);
                        }
                    }));
        }
        private Registration() {}
    }

    @GameTest(template = "empty", batch = "vre_unopened_container_loot", timeoutTicks = 100000)
    public static void publicDropNoneSkipsUnopenedLootButKeepsNativeContainerRemoval(GameTestHelper helper) throws IOException {
        Fixture fixture = fixture(helper); ServerLevel level = fixture.level; BlockPos position = fixture.position;
        ChestBlockEntity unopened = chest(level, position);
        unopened.setLootTable(TABLE, 78431L);
        CompoundTag pending = unopened.saveWithoutMetadata();
        helper.assertTrue(TABLE.toString().equals(pending.getString("LootTable")), "Fixture really has unopened loot metadata");
        UUID itemToken = UUID.randomUUID();
        ChestBlockEntity filled = chest(level, position.offset(2, 0, 0));
        ItemStack existing = new ItemStack(Items.DIAMOND, 64); existing.getOrCreateTag().putUUID("VreLootProbe", itemToken);
        filled.setItem(0, existing);
        helper.assertTrue(level.getServer().getLootData().getLootTable(TABLE) != LootTable.EMPTY,
                "The counted table was loaded by the actual server loot manager");
        GENERATIONS.set(0);
        OperationData.Task task;
        var physics = AdapterConfig.PHYSICS.get(); var propagation = AdapterConfig.PROPAGATION.get();
        double damage = AdapterConfig.ENTITY_DAMAGE.get();
        try {
            AdapterConfig.PHYSICS.set(AdapterConfig.Physics.NONE); AdapterConfig.PROPAGATION.set(FrozenOcclusion.Mode.NONE);
            AdapterConfig.ENTITY_DAMAGE.set(0.0);
            var handle = ForgeRegionEngine.submit(level, UUID.randomUUID(), new RegionGeometry.Box(new IntBox(
                    position.getX(), position.getY(), position.getZ(), position.getX() + 3, position.getY() + 1, position.getZ() + 1)),
                    new ForgeRegionEngine.Options(0, false, true));
            task = OperationData.get(level).tasks.stream().filter(value -> value.id.equals(handle.id())).findFirst().orElseThrow();
        } finally {
            AdapterConfig.PHYSICS.set(physics); AdapterConfig.PROPAGATION.set(propagation); AdapterConfig.ENTITY_DAMAGE.set(damage);
        }
        BackendFixtureGameTests.onFailure(helper, () -> { if (!task.terminal()) ForgeOperationRunner.cancel(task); fixture.close(); });
        long started = System.nanoTime();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(task.terminal() || System.nanoTime() - started > 30_000_000_000L,
                "Real container transaction and native acknowledgements pending"))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(task.state == OperationData.State.WRITTEN && task.changed == 2,
                                "Public operation completes both native containers: " + ForgeOperationRunner.status(task).getString());
                        helper.assertTrue(level.getBlockState(position).isAir() && level.getBlockEntity(position) == null
                                        && unopened.isRemoved() && filled.isRemoved(),
                                "Normal state and block-entity removal lifecycle still executes");
                        helper.assertTrue(GENERATIONS.get() == 0, "No discarded loot function ran while deleting the unopened chest");
                        helper.assertTrue(countItems(level, itemToken) == 0, "Already-materialized inventory still creates no drop entities");
                        assertPending(helper, unopened, pending, "Even the removed object retains its original loot metadata");
                        ChestBlockEntity ordinary = chest(level, position.offset(5, 0, 0));
                        ordinary.setLootTable(TABLE, 991L);
                        ordinary.unpackLootTable(null);
                        helper.assertTrue(GENERATIONS.get() == 1 && emeralds(ordinary) == 1,
                                "Outside the scope the same real table generates exactly once");
                        ChestBlockEntity playerOpen = chest(level, position.offset(7, 0, 0));
                        playerOpen.setLootTable(TABLE, 992L);
                        playerOpen.unpackLootTable(FakePlayerFactory.getMinecraft(level));
                        helper.assertTrue(GENERATIONS.get() == 2 && emeralds(playerOpen) == 1,
                                "An ordinary explicit player open still evaluates its loot");
                        RuntimeBridge.LOGGER.info("Loot suppression public path PASS: discarded generations=0, ordinary generations=2, native removed containers=2");
                    } finally { fixture.close(); }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_unopened_loot_failure", timeoutTicks = 300)
    public static void failedNativeRemovalPreservesLootAndNeighborWorldThreadScope(GameTestHelper helper) {
        Fixture fixture = fixture(helper); ServerLevel level = fixture.level; BlockPos position = fixture.position;
        ChestBlockEntity target = chest(level, position); target.setLootTable(TABLE, 73129L);
        ChestBlockEntity neighbor = chest(level, position.offset(3, 0, 0)); neighbor.setLootTable(TABLE, 491L);
        CompoundTag original = target.saveWithoutMetadata();
        GENERATIONS.set(0);
        var oldAction = neighborAction;
        try {
            helper.assertTrue(!DropSuppression.setBlock(level, position, level.getBlockState(position), 18),
                    "An actual no-op native replacement returns false");
            helper.assertTrue(GENERATIONS.get() == 0, "A failed no-op cannot realize or consume pending loot");
            assertPending(helper, target, original, "No-op preserves table and seed");
            Block callback = ForgeRegistries.BLOCKS.getValue(CALLBACK);
            helper.assertTrue(callback != null, "The native comparator-neighbor callback probe is registered");
            level.setBlock(position.above(), callback.defaultBlockState(), 18);
            boolean[] observed = new boolean[5];
            neighborAction = (world, removed) -> {
                if (world != level || !removed.equals(position)) return;
                observed[0] = true;
                helper.assertTrue(GENERATIONS.get() == 0, "Native ChestBlock.onRemove skipped loot before its output-signal callback");
                assertPending(helper, target, original, "Unpacking cancellation changes no metadata before callback failure");
                observed[1] = DropSuppression.suppressesLootUnpacking(level, position);
                observed[2] = !DropSuppression.suppressesLootUnpacking(level, neighbor.getBlockPos());
                observed[3] = !DropSuppression.suppressesLootUnpacking(level.getServer().overworld(), position);
                observed[4] = CompletableFuture.supplyAsync(() -> !DropSuppression.suppressesLootUnpacking(level, position)).join();
                neighbor.unpackLootTable(null);
                helper.assertTrue(GENERATIONS.get() == 1 && emeralds(neighbor) == 1,
                        "A different container still generates while the target's deletion scope is active");
                throw new IllegalStateException("VRE expected container neighbor failure");
            };
            boolean failed = false;
            try { DropSuppression.setBlock(level, position, Blocks.AIR.defaultBlockState(), 18); }
            catch (RuntimeException failure) { failed = observed[0]; }
            helper.assertTrue(failed, "The real vanilla chest onRemove reaches the native callback and propagates its failure");
            for (int i = 0; i < observed.length; i++) helper.assertTrue(observed[i], "Exact loot scope case " + i);
            helper.assertTrue(!DropSuppression.suppressesLootUnpacking(level, position), "Finally releases the scope after a thrown callback");
            assertPending(helper, target, original, "The failed container deletion has not permanently consumed its loot metadata");
            neighborAction = oldAction;
            // The native exception can leave a partially changed block/BE pair. A host rollback
            // restores the saved original metadata; the suppression policy never edits that data.
            level.removeBlockEntity(position);
            ChestBlockEntity restored = chest(level, position); restored.load(original);
            restored.unpackLootTable(null);
            helper.assertTrue(GENERATIONS.get() == 2 && emeralds(restored) == 1,
                    "Recovered original table and seed remain usable after scope cleanup");
            RuntimeBridge.LOGGER.info("Loot suppression failure path PASS: exact scope, native callback failure, preserved metadata, normal recovery generation");
        } finally { neighborAction = oldAction; fixture.close(); }
        helper.succeed();
    }

    private static void assertPending(GameTestHelper helper, ChestBlockEntity chest, CompoundTag original, String description) {
        CompoundTag actual = chest.saveWithoutMetadata();
        helper.assertTrue(original.getString("LootTable").equals(actual.getString("LootTable"))
                && original.getLong("LootTableSeed") == actual.getLong("LootTableSeed"), description);
    }
    private static ChestBlockEntity chest(ServerLevel level, BlockPos position) {
        level.setBlock(position, Blocks.CHEST.defaultBlockState(), 18);
        return (ChestBlockEntity) level.getBlockEntity(position);
    }
    private static int emeralds(ChestBlockEntity chest) {
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) if (chest.getItem(slot).is(Items.EMERALD)) count += chest.getItem(slot).getCount();
        return count;
    }
    private static int countItems(ServerLevel level, UUID token) {
        int count = 0;
        for (var entity : level.getAllEntities()) if (entity instanceof ItemEntity item) {
            CompoundTag tag = item.getItem().getTag();
            if (tag != null && tag.hasUUID("VreLootProbe") && token.equals(tag.getUUID("VreLootProbe"))) count += item.getItem().getCount();
        }
        return count;
    }
    private static Fixture fixture(GameTestHelper helper) {
        var site = BackendFixtureGameTests.allocate(helper, 0); UUID holder = UUID.randomUUID();
        site.level().getChunkSource().addRegionTicket(HOLD, site.position(), 0, holder);
        site.level().getChunk(site.position().x, site.position().z);
        Fixture fixture = new Fixture(site.level(), site.position(), new BlockPos(site.position().getMinBlockX() + 2, 100,
                site.position().getMinBlockZ() + 2), holder);
        BackendFixtureGameTests.onFailure(helper, fixture::close); return fixture;
    }
    private record Fixture(ServerLevel level, ChunkPos chunk, BlockPos position, UUID holder) {
        void close() { level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, holder); }
    }
    private LootSuppressionGameTests() {}
}
