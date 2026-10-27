# Forge 1.20.1 adapter and validation mod

This project builds the reusable Forge adapter and a small mod providing test commands and an adjustable charge. It targets Java 17, Minecraft 1.20.1 and ordinary Forge 47.3.7. Hybrid server cores are outside the contract. GTLCore is unchanged.

Runtime code lives in `org.voxelregion.forge.runtime`; hosts call `org.voxelregion.forge.ForgeRegionEngine`. The separate adapter jar has no `@Mod`. The test mod embeds both the adapter and independent Java engine through JarJar. All three artifacts use MPL-2.0. Verification classes, test structures and verification dimensions are excluded from normal artifacts, including artifacts built after a verification compile.

## Build and run

From this directory, with Java 17:

```powershell
..\..\gradlew.bat --no-daemon --max-workers=1 adapterJar remapJar
..\..\gradlew.bat --no-daemon --max-workers=1 runClient
..\..\gradlew.bat --no-daemon --max-workers=1 -Pverification runGameTest
```

Use `--offline` when dependencies are cached. Bound local JVM parallelism with `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1`; Gradle workers are also capped. Normal outputs are `build/libs/voxel-region-forge-adapter-1.20.1-0.1.0.jar` and `build/libs/voxel-region-forge-test-1.20.1-0.1.0.jar`. The portable engine is built by the main project. `adapterDevelopmentJar` supplies a named development artifact; `adapterJar` is SRG-remapped for ordinary Forge.

Put the test jar in a disposable Forge installation. It adds an item and belongs on both client and server; it already contains both libraries. Do not add duplicate copies of those libraries. The verification source set is for local GameTests; normal jars still filter its content. See [embedding instructions](../../docs/forge-embedding.md) to use the adapter without the test mod.

## Commands

Giving, using and controlling charges requires permission level 2:

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

`give` accepts `<radius> <fuseTicks> [generate=false] [clearFluids=true]`; `blast` accepts `<radius> [generate=false] [clearFluids=true]`. Use the charge on a block to arm a stationary smoke marker in the adjacent cell. It is not a falling TNT entity. `blast` submits at the command position's block center with no fuse. The initial `FUSE visited=0` response is acceptance, not evidence of a stalled operation; inspect later status.

The commands submit spheres. Hosts can also submit persisted `RegionGeometry.Ball`, `Box`, `Column` and `Combined` expressions through `ForgeRegionEngine.submit`. The independent geometry codec supports schema-checked NBT and bounded union/intersection/difference expressions.

## Runtime behavior

New `CENTER_OUT` tasks first process the center chunk and a radius-one bootstrap region, then non-overlapping shells of the configured width. Default shell width is 32. Non-spherical shapes are intersected with these shells. `AS_FAST_AS_POSSIBLE` uses one volume phase with storage-oriented traversal. Phase progress is persisted; old tasks retain their old wave interpretation. This is shell ordering, not a strict distance sort of every voxel.

Default `generate=false` with propagation `NONE` excludes unloaded chunks and continues. Exclusions are remembered, so later loading does not cause the old operation to revisit them. Completion describes the selected loaded scope, not every chunk in the mathematical radius. With explicit generation permission, native storage/tickets/load-generation handle unavailable chunks. Tickets remain held through required completion and are released after work, control transitions and shutdown.

Before mutation, FTB Chunks permissions are checked when the mod is installed. Missing FTB permits an ordinary world; an installed but unavailable/incompatible provider pauses safely. Terrain follows the owning team's explosion setting unless configured to protect every claim. There is no claim bypass. Claims do not cast propagation shadows; entity permission is separate and conservatively protects every claimed chunk.

Protected tags, negative or unknown/nonfinite hardness and contextual explosion resistance retain blocks. The default resistance threshold is 1200. Protected block entities remain intact. `clearFluids=true` clears free source/flowing water and lava after explicit tag/claim protection, and removes water with a destroyable waterlogged host. `false` retains free fluids and replaces a removed waterlogged host with its fluid state. Protected waterlogged solids retain both. Outside sources can flow into openings later.

Whole-section batches require complete geometry coverage, no shadow, static eligible palettes, current mutation revisions, world-border checks and audited host observers. They replace state containers, preserve biomes and reconcile heightmaps, sky sources, ticks, light work and aggregated notifications. Exact vanilla air variants and eligible vanilla water are supported; block entities, POI-sensitive/special states, lava and waterlogged solids use native writes. Unknown mods or unsupported states also fall back. **`HostIntegration.registerAuditedMod` is an integration assertion, not a switch that makes arbitrary mod callbacks safe.**

Physics defaults to `NONE` (native flags 2|16 on per-block writes). `BOUNDARY_ONLY` notifies retained adjacent states; `COMPATIBLE` uses native flags 3. Both enabled physics modes can produce indirect effects beyond the direct volume or permission boundary. Default DROP_NONE omits block loot and suppresses new synchronous item entities in the actual deletion cell, including native container contents. Native removal callbacks still execute. Existing entities, other cells/threads and later drops are unaffected; mod-specific delayed drops require host support.

Entity damage defaults to zero. Enabled damage considers currently loaded entities once, uses continuous shape containment and linear attenuation from the task's execution center, and dispatches ordinary damage events. It has separate claim checks. Entity occlusion, knockback and automatic replay without entity receipts are not provided.

## Frozen propagation and generation

The server `propagation` setting supports `NONE` (default), `AXIS_6`, `AXIS_26` and `RAYCAST_REFINED`. Non-NONE modes read the original whole region before deleting blocks. The independent `voxelregion_test:blocks_propagation` tag supplies blocker facts; it is not synonymous with `voxelregion_test:protected`. Macro-cell size must be 4, 8 or 16. Quantized directional shadows and refined frozen-box ray intersection do not simulate explosion energy.

Capture watches chunk revisions and tag-policy changes, writes an immutable hash-checked blocker file, then compiles its index on one bounded worker without world access. Missing chunks, unknown mutation tracking, changes during capture or exceeded limits prevent unsafe execution. In particular, loaded-only propagation capture pauses when it cannot read the complete original region; it cannot apply the NONE mode's unloaded exclusion rule to pretend unknown space is transparent. Recovery reloads the frozen file rather than recapturing already-cleared terrain.

Generation shortcuts are deliberately narrow: absent stored data, no existing/in-flight holder, a native fresh ProtoChunk, exact audited vanilla flat settings, full-height coverage, NONE propagation and current permissions/rules. The native NOISE stage retains protected layers and skips eligible generation writes; native status progression, lighting and FULL publication continue. Noise/custom generators or unknown world-generation integrations use ordinary generation. Bulk-observer registration does not supply world-generation proof. No fake FULL NBT, direct region-file writes or universal empty-chunk constructor is used.

## Configuration and measurements

Settings are in `world/serverconfig/voxelregion_test-server.toml` unless the embedding host chooses another name.

| Default | Value |
|---|---|
| Shared cooperative CPU budget | 4 ms/tick |
| Candidate visits / block writes | 262144 / 262144 per tick |
| Chunk packet budget | 1048576 bytes/tick, multiplied by tracking recipients |
| Pending metadata budget | 33554432 bytes |
| Maximum radius / pending operations | 2000 / 16 |
| Retained/requested operation chunks | Configurable up to eight globally; default eight, including pending completion |
| Progression / shell width | CENTER_OUT / 32 blocks |
| Physics / propagation / entity damage | NONE / NONE / 0 |

A native callback, serialization call or generation task is not preemptible. These are cooperative limits, not a hard guarantee that every tick finishes in 4 ms. Lighting, storage and packet backlogs have separate completion paths. An indivisible oversized packet incurs bandwidth debt rather than starving forever.

`ServerTickTimings` measures `MinecraftServer.tickServer` HEAD to RETURN, including Forge END work, and supplies the scheduler's adaptive timing. Vanilla `averageTickTime` and `forge tps` are sampled before END and omit adapter execution there. Report full-tick samples, CPU/RSS, stage timings and completion scope; do not label a nearby loaded-only R2000 fixture as a completed full-radius benchmark.

## Persistence and completion

Acceptance writes a flushed immutable intent under `data/voxelregion_test_intents/<uuid>.nbt`. Geometry, immutable policies, bootstrap/phase interpretation and frozen-field identity accompany persisted progress. `FrozenFieldStore` publishes `<uuid>.occlusion.nbt` without replacing existing evidence and validates schema, identity, size and SHA-256 on read.

Chunk capabilities store operation identities, completed-phase watermarks, exact in-progress cursors, pending-write sentinels and mutation revisions in the same native NBT image as terrain. Receipt capacity exhaustion fails closed. SavedData is not sufficient proof to replay a write. Journal reconciliation enters recovery; completed phases are skipped, and remaining work continues only with matching original intent and valid same-image evidence. Missing receipts, legacy STARTED without a cursor, revision mismatch, uncertain pending writes or missing frozen snapshots pause safely. An unvisited chunk after restart is not implicitly trusted.

`WRITTEN` waits for native light work, captured terrain/POI store futures, storage synchronization and required packet submission. It does not promise remote-client receipt/rendering. Save and packet queues recheck the chunk revision after light completion; a later edit requires a new light barrier. Failures are not erased by a later successful flush. Pause/cancel also settle already-submitted work; cancellation is durable and does not undo earlier writes. Ordinary resume cannot override `PAUSED_RECOVERY`.

## Validation scope

Verification sources cover geometry/fluid/protection fixtures, native block entities/heightmaps, palette/light differentials, generation proof invalidation, frozen capture and NBT, wave/control/recovery behavior, public facade calls, store failures and late-mutation fences. Added cases still require the unified GameTest run; listing or compiling them is not a pass statement. Final remapped artifacts additionally require standard dedicated-server loading, persistence and bounded large-radius measurements. Live FTB/modpack interactions, client behavior and interrupted-world cases must be assessed in their actual deployment context.

The [0.1.0 validation record](../../docs/validation-0.1.0.md) identifies the tested artifacts, all 66 passing native GameTests, standard dedicated-server checks, persistence/restart results and bounded timing samples. It also records the untested deployment boundaries. Use disposable worlds; keep player reports, runtime worlds, raw logs and local incident evidence out of commits/uploads.
