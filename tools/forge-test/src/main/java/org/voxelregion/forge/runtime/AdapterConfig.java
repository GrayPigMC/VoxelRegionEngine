/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import net.minecraftforge.common.ForgeConfigSpec;

final class AdapterConfig {
    enum Physics { NONE, BOUNDARY_ONLY, COMPATIBLE }
    enum Progression { CENTER_OUT, AS_FAST_AS_POSSIBLE }
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.DoubleValue MILLIS_PER_TICK;
    static final ForgeConfigSpec.IntValue VISITS_PER_TICK;
    static final ForgeConfigSpec.IntValue WRITES_PER_TICK;
    static final ForgeConfigSpec.IntValue PACKET_BYTES;
    static final ForgeConfigSpec.IntValue MAX_PENDING_BYTES;
    static final ForgeConfigSpec.IntValue MAX_IN_FLIGHT_CHUNKS;
    static final ForgeConfigSpec.IntValue MAX_RADIUS;
    static final ForgeConfigSpec.IntValue MAX_OPERATIONS;
    static final ForgeConfigSpec.DoubleValue RESISTANCE_LIMIT;
    static final ForgeConfigSpec.DoubleValue ENTITY_DAMAGE;
    static final ForgeConfigSpec.BooleanValue PROTECT_ALL_CLAIMS;
    static final ForgeConfigSpec.EnumValue<Physics> PHYSICS;
    static final ForgeConfigSpec.EnumValue<Progression> PROGRESSION;
    static final ForgeConfigSpec.IntValue SHELL_WIDTH;
    static final ForgeConfigSpec.BooleanValue BULK_SECTIONS;
    static final ForgeConfigSpec.EnumValue<org.voxelregion.propagation.FrozenOcclusion.Mode> PROPAGATION;
    static final ForgeConfigSpec.IntValue OCCLUSION_CELL;
    static {
        var b = new ForgeConfigSpec.Builder();
        b.push("budget");
        MILLIS_PER_TICK = b.comment("One shared cooperative main-thread budget across all dimensions and operations. A single native callback/load cannot be preempted.")
                .defineInRange("millis_per_tick", 4.0, 0.1, 40.0);
        VISITS_PER_TICK = b.defineInRange("voxel_visits_per_tick", 262144, 1, 1_000_000);
        WRITES_PER_TICK = b.defineInRange("block_writes_per_tick", 262144, 1, 1_000_000);
        PACKET_BYTES = b.defineInRange("packet_bytes_per_tick", 1_048_576, 1024, 67_108_864);
        MAX_PENDING_BYTES = b.defineInRange("maximum_pending_metadata_bytes", 33_554_432, 1_048_576, 268_435_456);
        MAX_IN_FLIGHT_CHUNKS = b.comment("Global maximum of currently written and light/save/packet-pending chunks. Their tickets and exclusive reservations stay held until acknowledgement.")
                .defineInRange("maximum_in_flight_chunks", 8, 1, 8);
        b.pop().push("operations");
        MAX_RADIUS = b.defineInRange("maximum_radius", 2000, 1, 10000);
        MAX_OPERATIONS = b.defineInRange("maximum_pending_operations", 16, 1, 128);
        PROGRESSION = b.comment("CENTER_OUT completes concentric non-overlapping shells in order; AS_FAST_AS_POSSIBLE uses region locality without visual wave ordering.")
                .defineEnum("progression", Progression.CENTER_OUT);
        SHELL_WIDTH = b.defineInRange("shell_width_blocks", 32, 1, 128);
        BULK_SECTIONS = b.comment("Use audited whole-section replacement when geometry, palette, protection and host hooks prove it safe.")
                .define("bulk_sections", true);
        PROPAGATION = b.comment("NONE clears only by geometry. Other modes first capture and durably freeze all blocker tags; incomplete or changed snapshots pause safely.")
                .defineEnum("propagation", org.voxelregion.propagation.FrozenOcclusion.Mode.NONE);
        OCCLUSION_CELL = b.comment("Macro blocker cell size: 4, 8 or 16; other values are rejected before accepting an operation.")
                .defineInRange("occlusion_cell_size", 4, 4, 16);
        RESISTANCE_LIMIT = b.comment("Keep blocks with context-sensitive explosion resistance at or above this value; unbreakable blocks and the protected tag are always kept.")
                .defineInRange("protected_resistance", 1200.0, 0.0, 3.4e38);
        ENTITY_DAMAGE = b.comment("0 disables entity damage. Positive values apply linear spherical damage once, to currently loaded entities only. Protected claims are always excluded; no claim bypass.")
                .defineInRange("entity_damage_at_center", 0.0, 0.0, 2048.0);
        PROTECT_ALL_CLAIMS = b.comment("Otherwise follow FTB Chunks canExplosionsDamageTerrain(). Claims never block propagation in this baseline.")
                .define("protect_all_claimed_chunks", false);
        PHYSICS = b.comment("NONE suppresses neighbor physics. BOUNDARY_ONLY notifies retained adjacent states after native writes. COMPATIBLE uses flags 3. Indirect vanilla physics may spread beyond the direct clear volume in both physics-enabled modes.")
                .defineEnum("physics", Physics.NONE);
        b.pop();
        SPEC = b.build();
    }
    private AdapterConfig() {}
}
