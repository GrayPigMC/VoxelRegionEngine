/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.testmod;

import net.minecraftforge.common.ForgeConfigSpec;

final class AdapterConfig {
    enum Physics { NONE, COMPATIBLE }
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.DoubleValue MILLIS_PER_TICK;
    static final ForgeConfigSpec.IntValue VISITS_PER_TICK;
    static final ForgeConfigSpec.IntValue WRITES_PER_TICK;
    static final ForgeConfigSpec.IntValue MAX_RADIUS;
    static final ForgeConfigSpec.IntValue MAX_OPERATIONS;
    static final ForgeConfigSpec.DoubleValue RESISTANCE_LIMIT;
    static final ForgeConfigSpec.DoubleValue ENTITY_DAMAGE;
    static final ForgeConfigSpec.BooleanValue PROTECT_ALL_CLAIMS;
    static final ForgeConfigSpec.EnumValue<Physics> PHYSICS;
    static {
        var b = new ForgeConfigSpec.Builder();
        b.push("budget");
        MILLIS_PER_TICK = b.comment("One shared cooperative main-thread budget across all dimensions and operations. A single native callback/load cannot be preempted.")
                .defineInRange("millis_per_tick", 4.0, 0.1, 40.0);
        VISITS_PER_TICK = b.defineInRange("voxel_visits_per_tick", 8192, 1, 1_000_000);
        WRITES_PER_TICK = b.defineInRange("block_writes_per_tick", 1024, 1, 100_000);
        b.pop().push("operations");
        MAX_RADIUS = b.defineInRange("maximum_radius", 2000, 1, 10000);
        MAX_OPERATIONS = b.defineInRange("maximum_pending_operations", 16, 1, 128);
        RESISTANCE_LIMIT = b.comment("Keep blocks with context-sensitive explosion resistance at or above this value; unbreakable blocks and the protected tag are always kept.")
                .defineInRange("protected_resistance", 1200.0, 0.0, 3.4e38);
        ENTITY_DAMAGE = b.comment("0 disables entity damage. Positive values apply linear spherical damage once, to currently loaded entities only. Protected claims are always excluded; no claim bypass.")
                .defineInRange("entity_damage_at_center", 0.0, 0.0, 2048.0);
        PROTECT_ALL_CLAIMS = b.comment("Otherwise follow FTB Chunks canExplosionsDamageTerrain(). Claims never block propagation in this baseline.")
                .define("protect_all_claimed_chunks", false);
        PHYSICS = b.comment("NONE uses native flags 2|16 but suppresses neighbor physics. COMPATIBLE uses native flags 3, including indirect effects outside the direct sphere/claims. BOUNDARY_ONLY is not implemented.")
                .defineEnum("physics", Physics.NONE);
        b.pop();
        SPEC = b.build();
    }
    private AdapterConfig() {}
}
