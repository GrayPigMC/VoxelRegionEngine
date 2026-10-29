/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.verification;

import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.NativePaletteImage;
import org.voxelregion.forge.ParallelChunkCompression;
import org.voxelregion.forge.ParallelRegionStores;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;

/** Small native differential checks for the packed palette and parallel region persistence paths. */
public final class BatchPipelineChecks {
    public static void verify(Path root) throws Exception {
        Files.createDirectories(root);
        palettes();
        deferredHeader(root.resolve("header"));
        parallelStores(root.resolve("parallel"));
        verifyDurableFences(root.resolve("durable-fences"));
    }
    private static void palettes() {
        var values = new ArrayList<BlockState>();
        for (var state : Block.BLOCK_STATE_REGISTRY) { values.add(state); if (values.size() == 100) break; }
        var random = new Random(0x5518A1);
        for (int size : new int[]{2, 8, 20, 100}) {
            var source = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            var expected = new BlockState[4096];
            for (int i = 0; i < 4096; i++) {
                var state = values.get(random.nextInt(size)); expected[i] = state;
                source.set(i & 15, i >> 8, (i >> 4) & 15, state);
            }
            var image = NativePaletteImage.capture(source);
            check(image != null, "Native palette image must be available");
            var mapped = image.states.stream().map(state -> values.indexOf(state) % 3 == 0 ? Blocks.AIR.defaultBlockState() : state).toList();
            var output = image.remap(mapped); check(output != null, "Native remap must construct a container");
            var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
            var encoded = codec.encodeStart(NbtOps.INSTANCE, output).result().orElseThrow();
            var decoded = codec.parse(NbtOps.INSTANCE, encoded).result().orElseThrow();
            source.set(0, 0, 0, Blocks.DIAMOND_BLOCK.defaultBlockState());
            for (int i = 0; i < 4096; i++) {
                var wanted = values.indexOf(expected[i]) % 3 == 0 ? Blocks.AIR.defaultBlockState() : expected[i];
                check(output.get(i & 15, i >> 8, (i >> 4) & 15) == wanted, "Remap changed a retained cell or aliased source storage");
                check(decoded.get(i & 15, i >> 8, (i >> 4) & 15) == wanted, "Native codec lost duplicate remapped palette indices");
            }
            output.set(0, 0, 0, Blocks.GOLD_BLOCK.defaultBlockState());
            check(source.get(0, 0, 0).is(Blocks.DIAMOND_BLOCK), "Output and source must own separate bit storage");
        }
    }
    private static void deferredHeader(Path directory) throws Exception {
        Files.createDirectories(directory); Path path = directory.resolve("r.0.0.mca");
        try (var file = new RegionFile(path, directory, true)) {
            for (int x = 0; x < 8; x++) try (var output = file.getChunkDataOutputStream(new ChunkPos(x, 0))) { NbtIo.write(tag(x, 0), output); }
            file.flush();
            for (int round = 1; round <= 3; round++) for (int x = 0; x < 8; x++)
                ((NativeAccess.PreparedRegionFile)file).vre$writeCompressed(new ChunkPos(x, 0), compressed(tag(x, round)));
            try (var disk = new RegionFile(path, directory, true)) {
                for (int x = 0; x < 8; x++) try (var input = disk.getChunkDataInputStream(new ChunkPos(x, 0))) {
                    check(NbtIo.read(input).getInt("round") == 0, "Unpublished offsets must retain the old durable payload");
                }
            }
            file.flush();
            try (var disk = new RegionFile(path, directory, true)) {
                for (int x = 0; x < 8; x++) try (var input = disk.getChunkDataInputStream(new ChunkPos(x, 0))) {
                    check(NbtIo.read(input).getInt("round") == 3, "Flush must publish the latest complete cohort");
                }
            }
        }
    }
    private static void parallelStores(Path directory) throws Exception {
        Files.createDirectories(directory);
        try (var worker = new Worker(directory)) {
            var writes = new ArrayList<CompletableFuture<Void>>();
            for (int round = 0; round < 4; round++) for (int lane = 0; lane < 4; lane++) for (int x = 0; x < 8; x++) {
                var position = new ChunkPos(lane * 32 + x, 0); var data = tag(position.x, round);
                if (round != 2) ParallelChunkCompression.prepare(data, 8L * 1024 * 1024);
                writes.add(worker.store(position, data));
                check(worker.loadAsync(position).join().orElseThrow().getInt("round") == round,
                        "Read must include native pending and already dispatched latest writes");
            }
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
            worker.synchronize(true).join();
            for (int lane = 0; lane < 4; lane++) try (var disk = new RegionFile(directory.resolve("r." + lane + ".0.mca"), directory, true)) {
                for (int x = 0; x < 8; x++) try (var input = disk.getChunkDataInputStream(new ChunkPos(lane * 32 + x, 0))) {
                    check(NbtIo.read(input).getInt("round") == 3, "Parallel native fence acknowledged stale disk data");
                }
            }
            var deleted = new ChunkPos(0, 0); worker.store(deleted, null).join();
            check(worker.loadAsync(deleted).join().isEmpty(), "Deletion after a parallel store must remain ordered");
        }
    }
    /** Real native mailbox, region headers and close path; all waits have a small wall-clock bound. */
    public static void verifyDurableFences(Path directory) throws Exception {
        Files.createDirectories(directory);
        var a = new ChunkPos(0, 0); var a2 = new ChunkPos(1, 0); var b = new ChunkPos(32, 0);
        var worker = new Worker(directory);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> close = null;
        try {
            for (var position : List.of(a, a2, b)) worker.store(position, tag(position.x, 0)).get(10, TimeUnit.SECONDS);
            worker.synchronize(true).get(10, TimeUnit.SECONDS);
            for (var position : List.of(a, a2, b)) {
                var data = tag(position.x, 1);
                ParallelChunkCompression.prepare(data, 8L * 1024 * 1024);
                worker.store(position, data).get(10, TimeUnit.SECONDS);
            }
            ((NativeAccess.DurableStorage) (Object) worker).vre$flushStored(List.of(a, a2)).get(10, TimeUnit.SECONDS);
            check(diskRound(directory, a) == 1 && diskRound(directory, a2) == 1,
                    "A touched-region force must durably include all its preceding stores");
            check(diskRound(directory, b) == 0,
                    "A force of region A must not force untouched buffered region B");

            // Hold only detached IO workers, never the native mailbox or a RegionFile lock.
            // A native load must still pass while the accepted force waits for a worker.
            var poolField = ParallelRegionStores.class.getDeclaredField("workers"); poolField.setAccessible(true);
            var pool = (ThreadPoolExecutor) poolField.get(null);
            var occupied = new CountDownLatch(pool.getCorePoolSize());
            for (int i = 0; i < pool.getCorePoolSize(); i++) pool.execute(() -> {
                occupied.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(occupied.await(5, TimeUnit.SECONDS), "Detached force workers reached fixture gate");
            var fence = ((NativeAccess.DurableStorage) (Object) worker).vre$flushStored(List.of(b));
            check(worker.loadAsync(a).get(3, TimeUnit.SECONDS).orElseThrow().getInt("round") == 1,
                    "An unfinished force must not block unrelated native mailbox reads");
            check(!fence.isDone(), "Durability acknowledgement must wait for the actual file force");
            close = CompletableFuture.runAsync(() -> {
                try { worker.close(); } catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
            });
            var closedField = Arrays.stream(IOWorker.class.getDeclaredFields())
                    .filter(field -> field.getType() == java.util.concurrent.atomic.AtomicBoolean.class).findFirst().orElseThrow();
            closedField.setAccessible(true);
            var shuttingDown = (java.util.concurrent.atomic.AtomicBoolean) closedField.get(worker);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!shuttingDown.get() && System.nanoTime() < deadline) Thread.sleep(1);
            check(shuttingDown.get() && !close.isDone(), "Close must retain its mailbox until an accepted force settles");
            release.countDown();
            close.get(10, TimeUnit.SECONDS); fence.get(10, TimeUnit.SECONDS);
            check(diskRound(directory, b) == 1, "Close cannot acknowledge before the force reaches the region header");
        } finally {
            release.countDown();
            if (close == null) worker.close(); else close.get(10, TimeUnit.SECONDS);
        }
        verifyDeleteForceOrdering(directory.resolve("delete-force"));
    }
    private static void verifyDeleteForceOrdering(Path directory) throws Exception {
        Files.createDirectories(directory);
        var deleted = new ChunkPos(0, 0); var retained = new ChunkPos(1, 0);
        try (var worker = new Worker(directory)) {
            worker.store(retained, tag(retained.x, 42)).get(10, TimeUnit.SECONDS);
            for (int round = 0; round < 8; round++) {
                var data = tag(deleted.x, round);
                ParallelChunkCompression.prepare(data, 8L * 1024 * 1024);
                worker.store(deleted, data).get(10, TimeUnit.SECONDS);
                // The completed store releases its write lane. This null store follows
                // the native no-lane clear path while an independent force is in flight.
                var force = ((NativeAccess.DurableStorage) (Object) worker).vre$flushStored(List.of(deleted));
                var removed = worker.store(deleted, null);
                CompletableFuture.allOf(force, removed).get(10, TimeUnit.SECONDS);
                ((NativeAccess.DurableStorage) (Object) worker).vre$flushStored(List.of(deleted)).get(10, TimeUnit.SECONDS);
                try (var disk = new RegionFile(directory.resolve("r.0.0.mca"), directory, true)) {
                    check(disk.getChunkDataInputStream(deleted) == null,
                            "Concurrent force/delete must not republish a removed region offset");
                    try (var input = disk.getChunkDataInputStream(retained)) {
                        check(input != null && NbtIo.read(input).getInt("round") == 42,
                                "Concurrent force/delete must retain the neighboring chunk header and sectors");
                    }
                }
            }
        }
    }
    private static int diskRound(Path directory, ChunkPos position) throws Exception {
        try (var disk = new RegionFile(directory.resolve("r." + position.getRegionX() + "." + position.getRegionZ() + ".mca"), directory, true);
             var input = disk.getChunkDataInputStream(position)) {
            check(input != null, "Expected an existing durable chunk payload");
            return NbtIo.read(input).getInt("round");
        }
    }
    private static ByteBuffer compressed(CompoundTag data) throws Exception {
        var output = new java.io.ByteArrayOutputStream(); output.write(new byte[5]);
        try (var compressed = new java.io.DataOutputStream(new java.util.zip.DeflaterOutputStream(output))) { NbtIo.write(data, compressed); }
        byte[] bytes = output.toByteArray(); ByteBuffer.wrap(bytes).putInt(bytes.length - 4).put((byte)2); return ByteBuffer.wrap(bytes);
    }
    private static CompoundTag tag(int x, int round) { var tag = new CompoundTag(); tag.putInt("x", x); tag.putInt("round", round); return tag; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Worker extends IOWorker { Worker(Path directory) { super(directory, true, "vre-batch-check"); } }
    private BatchPipelineChecks() { }
}
