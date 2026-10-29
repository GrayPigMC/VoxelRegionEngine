/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.verification;

import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.NativePaletteImage;
import org.voxelregion.forge.ParallelChunkCompression;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
