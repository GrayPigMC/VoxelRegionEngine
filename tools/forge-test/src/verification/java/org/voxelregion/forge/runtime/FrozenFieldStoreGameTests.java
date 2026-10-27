/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.FrozenOcclusionCapture.SealedSnapshot;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;

/** Detached file protocol checks, deliberately executed on one background worker. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class FrozenFieldStoreGameTests {
    private static final String INTENT = "1234567890abcdef".repeat(4);

    @GameTest(template = "empty", batch = "vre_frozen_file_roundtrip", timeoutTicks = 300)
    public static void detachedRoundtripIsCanonicalAndNeverOverwrites(GameTestHelper helper) {
        background(helper, directory -> {
            for (FrozenOcclusion.Mode mode : FrozenOcclusion.Mode.values()) {
                UUID id = UUID.randomUUID();
                SealedSnapshot original = snapshot(mode);
                String hash = FrozenFieldStore.write(directory, id, INTENT, original);
                require(FrozenFieldStore.read(directory, id, INTENT, hash).equals(original), "Full detached snapshot roundtrip");
                require(FrozenFieldStore.read(directory, id, INTENT, hash, original.estimatedBytes()).equals(original),
                        "A matching host reservation allows decoding");
                rejects(() -> FrozenFieldStore.read(directory, id, INTENT, hash, original.estimatedBytes() - 1),
                        "Host reservation is checked before constructing detached blocker/revision objects");
                require(FrozenFieldStore.write(directory, id, INTENT, original).equals(hash), "Identical publication is idempotent");
                Map<ChunkKey, Long> reverse = new LinkedHashMap<>();
                reverse.put(new ChunkKey(0, 0), 9L); reverse.put(new ChunkKey(-1, -1), 7L);
                SealedSnapshot reordered = new SealedSnapshot(original.center(), original.bounds(), mode, original.blockers(),
                        reverse, 3, 4, 4096, 32L * 1024 * 1024, original.estimatedBytes());
                require(FrozenFieldStore.write(directory, id, INTENT, reordered).equals(hash), "Map ordering cannot change evidence hash");
                SealedSnapshot changed = new SealedSnapshot(new Point3(1, 1, 1), original.bounds(), mode, original.blockers(),
                        original.revisions(), 3, 4, 4096, 32L * 1024 * 1024, original.estimatedBytes());
                rejects(() -> FrozenFieldStore.write(directory, id, INTENT, changed), "Different content cannot replace an existing operation file");
                require(FrozenFieldStore.read(directory, id, INTENT, hash).equals(original), "Rejected overwrite preserves original evidence");
                rejects(() -> FrozenFieldStore.read(directory, id, "f".repeat(64), hash), "Wrong intent is rejected");
                rejects(() -> FrozenFieldStore.read(directory, id, INTENT, "0".repeat(64)), "Wrong content hash is rejected before decode");
            }
        });
    }

    @GameTest(template = "empty", batch = "vre_frozen_file_corruption", timeoutTicks = 300)
    public static void malformedAndOversizedDataCannotAuthorizeRecovery(GameTestHelper helper) {
        background(helper, directory -> {
            UUID sourceId = UUID.randomUUID();
            FrozenFieldStore.write(directory, sourceId, INTENT, snapshot(FrozenOcclusion.Mode.AXIS_26));
            CompoundTag original = NbtIo.readCompressed(path(directory, sourceId).toFile());
            for (int variant = 0; variant < 8; variant++) {
                UUID id = UUID.randomUUID();
                CompoundTag tag = original.copy(); tag.putUUID("id", id);
                switch (variant) {
                    case 0 -> tag.putLong("estimated", Long.MAX_VALUE);
                    case 1 -> tag.putLongArray("revisions", new long[] { -1, 9 });
                    case 2 -> tag.putLongArray("chunks", new long[] { 0, 0 });
                    case 3 -> tag.putIntArray("boxes", new int[] { -17, -4, -4, 0, 0, 0 });
                    case 4 -> tag.putString("mode", "UNKNOWN_FUTURE_MODE");
                    case 5 -> tag.putString("macro", "4");
                    case 6 -> tag.putInt("unexpected", 1);
                    case 7 -> tag.putLong("policy", Long.MAX_VALUE);
                    default -> throw new AssertionError();
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(tag, bytes);
                byte[] encoded = bytes.toByteArray();
                Files.write(path(directory, id), encoded);
                rejects(() -> FrozenFieldStore.read(directory, id, INTENT, sha(encoded)), "Malformed snapshot variant " + variant);
            }
            // A tiny compressed declaration must be rejected by the NBT allocation
            // account before an attacker-controlled 32 MiB array can be allocated.
            UUID bombId = UUID.randomUUID();
            ByteArrayOutputStream bomb = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bomb))) {
                out.writeByte(Tag.TAG_COMPOUND); out.writeUTF("");
                out.writeByte(Tag.TAG_INT_ARRAY); out.writeUTF("boxes");
                out.writeInt(FrozenFieldStore.MAX_FILE_BYTES / 4 + 1);
            }
            byte[] encodedBomb = bomb.toByteArray();
            Files.write(path(directory, bombId), encodedBomb);
            rejects(() -> FrozenFieldStore.read(directory, bombId, INTENT, sha(encodedBomb)), "Declared allocation exceeds NBT budget");

            UUID oversizedId = UUID.randomUUID();
            try (FileChannel file = FileChannel.open(path(directory, oversizedId), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                file.position(FrozenFieldStore.MAX_FILE_BYTES); file.write(ByteBuffer.wrap(new byte[] { 0 }));
            }
            rejects(() -> FrozenFieldStore.read(directory, oversizedId, INTENT, "0".repeat(64)), "Oversized compressed file is rejected without loading it");
        });
    }

    private static SealedSnapshot snapshot(FrozenOcclusion.Mode mode) {
        return new SealedSnapshot(new Point3(-1, -1, -1), new IntBox(-16, -16, -16, 16, 16, 16), mode,
                List.of(new IntBox(-4, -4, -4, 0, 0, 0), new IntBox(4, 4, 4, 8, 8, 8)),
                Map.of(new ChunkKey(-1, -1), 7L, new ChunkKey(0, 0), 9L), 3, 4, 4096,
                32L * 1024 * 1024, 524_288L + 2 * 160 + 2 * 1024);
    }
    private static void background(GameTestHelper helper, Work work) {
        Path directory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/vre-frozen-field-fixtures/" + UUID.randomUUID());
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "vre-frozen-field-fixture"));
        CompletableFuture<Void> result = CompletableFuture.runAsync(() -> {
            try { work.run(directory); }
            catch (IOException failure) { throw new CompletionException(failure); }
            finally { executor.shutdown(); }
        }, executor);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(result.isDone(), "Detached file worker completed"))
                .thenExecute(result::join).thenSucceed();
    }
    private static Path path(Path directory, UUID id) { return directory.resolve(id + ".occlusion.nbt"); }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(IoAction action, String message) throws IOException {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(message);
    }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }
    @FunctionalInterface private interface Work { void run(Path directory) throws IOException; }
    private FrozenFieldStoreGameTests() { }
}
