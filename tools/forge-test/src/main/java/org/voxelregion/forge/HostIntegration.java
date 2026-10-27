/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.fml.ModList;

/** Embedding hosts explicitly audit their observers before enabling aggregate world writes. */
public final class HostIntegration {
    public interface Observer {
        default void afterSectionCleared(ServerLevel level, LevelChunk chunk, int sectionY) {}

        /** Previous states may be queried synchronously during this callback; do not retain the plan. */
        default void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
            afterSectionCleared(level, chunk, previous.sectionY());
        }
    }
    private static final Map<String, Observer> observers = new HashMap<>();
    static {
        Observer vanilla = new Observer() {};
        observers.put("minecraft", vanilla); observers.put("forge", vanilla); observers.put("voxelregion_test", vanilla);
    }
    /** Register from mod setup, before a world runs. This asserts that all of that mod's relevant hooks are covered. */
    public static void registerAuditedMod(String modId, Observer observer) {
        if (modId == null || modId.isBlank() || observer == null) throw new IllegalArgumentException("Missing audited integration");
        if (net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() != null)
            throw new IllegalStateException("Bulk integrations must be fixed before starting the server");
        observers.put(modId, observer);
    }
    public static boolean supportsBulk(ServerLevel level) {
        return ModList.get().getMods().stream().allMatch(mod -> observers.containsKey(mod.getModId()));
    }
    public static void afterSectionCleared(ServerLevel level, LevelChunk chunk, int sectionY) {
        for (var observer : observers.values()) observer.afterSectionCleared(level, chunk, sectionY);
    }
    public static void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
        for (var observer : observers.values()) observer.afterSectionCleared(level, chunk, previous);
    }
    private HostIntegration() {}
}
