/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.RegionGeometry;

/** Creates old-format immutable intents before acceptance, to retain legacy shell/recovery coverage. */
final class LegacyShellFixtureGameTests {
    static OperationData.Task submit(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse, boolean generate)
            throws IOException {
        return accept(level, actor, center, radius, fuse, generate, true, null);
    }

    static OperationData.Task submitGeometry(ServerLevel level, UUID actor, RegionGeometry geometry, ForgeRegionEngine.Options options)
            throws IOException {
        var bounds = geometry.volume().bounds();
        BlockPos center = BlockPos.containing(((long) bounds.minX() + bounds.maxX()) / 2.0,
                ((long) bounds.minY() + bounds.maxY()) / 2.0, ((long) bounds.minZ() + bounds.maxZ()) / 2.0);
        double dx = Math.max(center.getX() + .5 - bounds.minX(), bounds.maxX() - center.getX() - .5);
        double dy = Math.max(center.getY() + .5 - bounds.minY(), bounds.maxY() - center.getY() - .5);
        double dz = Math.max(center.getZ() + .5 - bounds.minZ(), bounds.maxZ() - center.getZ() - .5);
        return accept(level, actor, center, Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz))),
                options.fuseTicks(), options.generateMissingChunks(), options.clearFluids(), geometry);
    }

    private static OperationData.Task accept(ServerLevel level, UUID actor, BlockPos center, int radius, int fuse,
            boolean generate, boolean clearFluids, RegionGeometry geometry) throws IOException {
        var data = OperationData.get(level);
        var source = new OperationData.Task(UUID.randomUUID(), actor, center.getX() + .5, center.getY() + .5, center.getZ() + .5,
                radius, fuse, generate, clearFluids, AdapterConfig.RESISTANCE_LIMIT.get(), AdapterConfig.ENTITY_DAMAGE.get(),
                AdapterConfig.PROTECT_ALL_CLAIMS.get(), AdapterConfig.PHYSICS.get(), level.getMinSection());
        if (geometry != null) { source.geometry = geometry; source.volume = geometry.volume(); }
        source.progression = AdapterConfig.PROGRESSION.get(); source.shellWidth = AdapterConfig.SHELL_WIDTH.get();
        source.propagation = AdapterConfig.PROPAGATION.get(); source.occlusionCell = AdapterConfig.OCCLUSION_CELL.get();
        source.centralBootstrap = true;
        var oldFormat = source.save();
        oldFormat.remove("wholeChunk"); oldFormat.remove("centerCursor");
        var task = OperationData.Task.load(oldFormat);
        if (task.wholeChunk) throw new IllegalStateException("Legacy fixture unexpectedly uses complete chunks");
        task.owner = level;
        IntentJournal.append(level, task);
        data.tasks.add(task); data.setDirty();
        return task;
    }
    private LegacyShellFixtureGameTests() {}
}
