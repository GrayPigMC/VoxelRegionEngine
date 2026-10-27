/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class BoundedNbtReaderGameTests {
    @GameTest(template = "empty", batch = "vre_bounded_journal_reader", timeoutTicks = 100)
    public static void validJournalRoundtripsButCompressedInflatedAndAllocationLimitsFailClosed(GameTestHelper helper) throws IOException {
        Path directory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/vre-bounded-journal-fixtures/" + UUID.randomUUID());
        Files.createDirectories(directory);
        CompoundTag expected = new CompoundTag();
        expected.putUUID("id", UUID.randomUUID()); expected.putString("state", "CANCELLED");
        expected.putLongArray("excluded", new long[] { -1, 0, Long.MAX_VALUE });
        Path valid = directory.resolve("valid.nbt");
        NbtIo.writeCompressed(expected, valid.toFile());
        helper.assertTrue(BoundedNbtReader.read(valid, 4096).equals(expected), "Bounded decoder preserves control and exclusion data");

        rejects(() -> BoundedNbtReader.read(valid, Files.size(valid) - 1), "Compressed file ceiling");
        byte[] bytes = Files.readAllBytes(valid);
        Path truncated = directory.resolve("truncated.nbt");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 1));
        rejects(() -> BoundedNbtReader.read(truncated, 4096), "Truncated gzip footer cannot authorize recovery");

        CompoundTag inflated = new CompoundTag(); inflated.putString("wide", "\u4e00".repeat(4000));
        Path expansion = directory.resolve("expansion.nbt"); NbtIo.writeCompressed(inflated, expansion.toFile());
        helper.assertTrue(Files.size(expansion) < 10_000, "Inflated fixture fits the compressed ceiling");
        rejects(() -> BoundedNbtReader.read(expansion, 10_000), "Inflated UTF-8 bytes are bounded separately");

        ByteArrayOutputStream declared = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(declared))) {
            output.writeByte(Tag.TAG_COMPOUND); output.writeUTF("");
            output.writeByte(Tag.TAG_LONG_ARRAY); output.writeUTF("excluded");
            output.writeInt(Integer.MAX_VALUE); // No large fixture allocation; only the hostile declaration.
        }
        Path allocation = directory.resolve("allocation.nbt"); Files.write(allocation, declared.toByteArray());
        rejects(() -> BoundedNbtReader.read(allocation, 4096), "NBT account rejects oversized arrays before allocation");

        ByteArrayOutputStream trailing = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(trailing))) {
            NbtIo.write(expected, output); output.writeByte(1);
        }
        Path extra = directory.resolve("trailing.nbt"); Files.write(extra, trailing.toByteArray());
        rejects(() -> BoundedNbtReader.read(extra, 4096), "Trailing decompressed data is rejected");
        rejects(() -> BoundedNbtReader.read(valid, 0), "Nonpositive limit is rejected");
        rejects(() -> BoundedNbtReader.read(valid, BoundedNbtReader.MAX_BYTES + 1), "Unbounded caller limit is rejected");
        helper.succeed();
    }
    private static void rejects(IoAction action, String reason) throws IOException {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(reason);
    }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }
    private BoundedNbtReaderGameTests() { }
}
