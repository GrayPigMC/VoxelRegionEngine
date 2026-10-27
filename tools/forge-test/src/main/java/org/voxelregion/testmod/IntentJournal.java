/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.testmod;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Durable acceptance evidence; does not claim atomicity with entity/POI/world saves. */
final class IntentJournal {
    static void append(ServerLevel level, OperationData.Task task) throws IOException {
        Path directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/voxelregion_test_intents");
        Files.createDirectories(directory);
        Path temporary = directory.resolve(task.id + ".pending");
        Path destination = directory.resolve(task.id + ".nbt");
        CompoundTag tag = task.save();
        tag.putString("dimension", level.dimension().location().toString());
        tag.putInt("schema", 1);
        tag.putString("adapter", "forge-1.20.1-47.3.7-native-baseline-0.1.0");
        NbtIo.writeCompressed(tag, temporary.toFile());
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
        // No destructive replacement: each operation owns a fresh UUID and immutable intent.
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    }
    private IntentJournal() {}
}
