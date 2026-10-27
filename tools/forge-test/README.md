# Forge 1.20.1 validation adapter

This is a runnable validation baseline for VoxelRegionEngine, not a completed implementation of all Phase 1 requirements. It targets ordinary Forge 1.20.1, Forge 47.3.7 and Java 17. Hybrid/external server cores are unsupported. It does not modify GTLCore.

The release jar contains the independent engine as a nested library. The engine is not a separate gameplay mod. Both this original adapter and the engine use MPL-2.0; their license is included. The optional verification sources and worlds are local test material and are not included in a normal build.

## Build and run

From this directory, with Java 17:

```powershell
..\..\gradlew.bat --no-daemon --max-workers=1 build
..\..\gradlew.bat --no-daemon --max-workers=1 runClient
..\..\gradlew.bat --no-daemon --max-workers=1 -Pverification runGameTest
```

Add `--offline` when the Minecraft/Forge/Loom dependencies are already cached. For bounded local testing set `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1`; the supplied Gradle settings also cap workers at one. Put the normal `build/libs/voxel-region-forge-test-1.20.1-0.1.0.jar` in an ordinary Forge test installation. It must be present on both client and server because it adds an item. A build with `-Pverification` is for local tests only.

Commands require permission level 2, including using the item after it has been given to someone else:

```text
/vre give 64 80
/vre give 64 80 true
/vre give 64 80 false false
/vre blast 16
/vre blast 16 false false
/vre status
/vre pause <operation-UUID>
/vre resume <operation-UUID>
/vre cancel <operation-UUID>
```

`give` accepts `<radius> <fuseTicks> [generate=false] [clearFluids=true]`. `blast` accepts `<radius> [generate=false] [clearFluids=true]`. `generate` permits normal chunk loading/generation; `clearFluids=false` retains free fluids and leaves water behind when removing a destroyable waterlogged solid. Thus `false false` means loaded-only and preserve fluids. Existing shorter commands retain their original meaning.

Use the charge on a block: it arms a stationary smoke marker in the adjacent block cell and reports its operation UUID. It is a test item, not a falling TNT entity. `blast` immediately submits a sphere centered on the command position's block center. The fluid choice is stored in the item, immutable intent and task; older data without this field defaults to true. Status and the item tooltip display the choice.

Use separate disposable worlds for different generation and protection experiments. This engine intentionally performs permanent terrain changes; cancelling stops remaining work and does not restore earlier changes.

## Implemented behavior

- Exact block-center sphere geometry and library chunk/section classification. Air sections and outside sections are skipped; inside sections avoid repeated containment math.
- One cooperative main-thread CPU/visit/write budget shared across operations and dimensions, round-robin execution, at most two retained/requested chunks globally. An individual native block callback or world-generation task cannot be preempted by this budget.
- Loaded-only mode pauses at unloaded intersecting chunks. Explicit generation mode uses ordinary Forge chunk tickets and polls `getChunkNow` across ticks. It never overwrites chunk NBT, creates `EmptyLevelChunk`, fakes `Status=full`, or bypasses generation. Tickets are released after each chunk and on pause/cancel/shutdown.
- Before every direct block mutation, optional FTB Chunks public API permissions are checked. Missing FTB permits normal worlds; installed FTB with an unavailable manager or incompatible API pauses work. The default respects `canExplosionsDamageTerrain`; the configuration can protect every claim. There is no claim bypass mode. Claims do not block propagation.
- Protected tag `voxelregion_test:protected`, negative destroy speed and context-sensitive explosion resistance protect blocks. The default resistance threshold is 1200. Block entities belonging to protected blocks stay in place. Other mod protection systems require additional adapters; they are not silently advertised as supported.
- Fluids are included in region clearing by default. With `clearFluids=true`, pure `LiquidBlock` states with nonempty fluid bypass hardness/resistance thresholds after the explicit protected-tag check; claim permissions still apply. This covers source and flowing water/lava. Destroying a waterlogged solid removes both the block and its water. With `clearFluids=false`, free liquids stay, and a destroyable waterlogged solid is replaced by its fluid state. Protected waterlogged solids retain both in either mode. Ordinary fluid ticks can later refill an opening from surviving outside sources; this is not a persistent fluid exclusion zone.
- Native `ServerLevel.setBlock` maintains block entities, POI notifications, heightmaps, skylight-source data, light checks, dirty chunks and normal client updates. **No direct section replacement is enabled.** Native removal callbacks may drop container contents or run mod-specific effects; ordinary block loot generation is not explicitly invoked.
- **Physics defaults to `NONE`** (`UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`, flags 2|16). This deliberately suppresses neighbor/shape propagation and is shown in status. Server config `physics="COMPATIBLE"` uses native flags 3 for a compatibility comparison. Native neighbor/fluid/falling-block effects can affect locations outside the direct sphere or claims; this adapter has no indirect-effect protection or `BOUNDARY_ONLY` implementation.
- Entity damage defaults to zero. When explicitly enabled, each currently loaded entity in the initial broad-phase snapshot is considered once using its actual current distance from the continuous sphere center, linear attenuation and ordinary damage events. Every claimed chunk protects entities regardless of terrain explosion settings. This is separate from block permission. Unloaded entities, occlusion and physical knockback are not implemented.

Settings are in the world's `serverconfig/voxelregion_test-server.toml`. Defaults are 4 ms, 8192 visited voxel candidates and 1024 native block writes per server tick. The current scheduler cannot cap vanilla's separate lighting, saving, network or generation worker costs. Large-radius throughput and TPS improvements have not been established by these defaults.

## Persistence and completion limits

Before a task is accepted, an immutable, flushed intent is written under the world's `data/voxelregion_test_intents/<uuid>.nbt`. SavedData tracks status and progress per dimension. A Forge chunk capability stores `STARTED` and `COMPLETE` receipts in the same chunk serialization as terrain. Receipts are not replaced by a single latest UUID, so a later operation cannot erase an older operation's receipt. Capacity exhaustion fails closed instead of deleting evidence.

`WRITTEN` means direct writes were submitted. It explicitly does **not** acknowledge completed lighting, client delivery or durable disk storage. There is no per-chunk durable-save acknowledgement or automatic crash recovery in this baseline. All unfinished tasks read from SavedData become `PAUSED_RECOVERY`; `/vre resume` refuses them. Inspect their immutable intent and chunk receipts, cancel them, and use a new explicitly requested operation if appropriate. An intent whose SavedData entry was never saved remains manual diagnostic evidence and is never automatically executed. Recovery does not replay old explosions against subsequent player construction.

In-memory pauses retain the exact scan cursor and can resume. Completed, cancelled and failed operations cannot resume. No physical rollback is provided. Receipt and SavedData round-trip tests are not a substitute for power-loss, torn-write, live FTB or client synchronization testing.

## Native test coverage

The optional GameTests exercise direct spherical inclusion/exclusion, bedrock and gateway block-entity retention, ordinary chest block-entity removal, native heightmap updates, receipt NBT round trips, refusal to replay completed/cancelled operations, and conservative reload of unfinished tasks. Fluid cases check source/flowing water and lava, removable/protected waterlogged solids, outside preservation, and a lowered resistance threshold. The fluid fixtures isolate native flow so they measure direct removal, not later refill. Test execution and results must be recorded separately; compiling this module alone is not a live-world pass. The template is an independently generated empty Minecraft structure.
