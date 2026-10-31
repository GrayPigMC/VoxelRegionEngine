# Forge 1.20.1 adapter and validation mod

This project builds the reusable Forge adapter and a small mod providing test commands and an adjustable charge. It targets Java 17, Minecraft 1.20.1 and ordinary Forge. Hybrid server cores are outside the contract. GTLCore embedding is documented in the library's integration guide.

Runtime code lives in `org.voxelregion.forge.runtime`; hosts call `org.voxelregion.forge.ForgeRegionEngine`. The separate adapter jar has no `@Mod`. The test mod embeds the adapter and independent Java engine through JarJar. Current VRE code uses MPL-2.0. The previously imported third-party lighting implementation has been removed; its old release44 validation does not validate the replacement implementation. Verification classes, test structures and verification dimensions are excluded from normal artifacts, including artifacts built after a verification compile.

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

`give` accepts `<radius> <fuseTicks> [generate=true] [clearFluids=true] [destroyBedrock=false]`; `blast` accepts `<radius> [generate=true] [clearFluids=true] [destroyBedrock=false]`. Use the charge on a block to arm a stationary smoke marker in the adjacent cell. It is not a falling TNT entity. `blast` submits at the command position's block center with no fuse. The initial `FUSE visited=0` response is acceptance, not evidence of a stalled operation; inspect later status.

For example, `/vre give 2000 20 true true false` preserves bedrock and clears fluids; the same command ending in `true` explicitly allows bedrock removal. Omitting the final argument preserves bedrock, including charges saved by older versions. Other protected blocks and chunk claims still apply.

The commands submit spheres. Hosts can also submit persisted `RegionGeometry.Ball`, `Box`, `Column` and `Combined` expressions through `ForgeRegionEngine.submit`. The independent geometry codec supports schema-checked NBT and bounded union/intersection/difference expressions.

## Runtime behavior

New `CENTER_OUT` tasks prepare and commit whole chunks, starting at the center and proceeding by increasing horizontal chunk distance. Preparation may span ticks without modifying the terrain; each chunk commits in one server-thread call. `AS_FAST_AS_POSSIBLE` uses storage-oriented traversal. Persisted legacy shell tasks retain their original interpretation.

Default `generate=true` permits bounded native loading/generation throughout the requested volume. Explicit `generate=false` still loads persisted FULL chunks, excluding only positions that need generation. Exclusions are remembered and never revisited by the same operation. Tickets remain held through required completion and are released after work, control transitions and shutdown.

Before mutation, FTB Chunks permissions are checked when the mod is installed. Missing FTB permits an ordinary world; an installed but unavailable/incompatible provider pauses safely. Terrain follows the owning team's explosion setting unless configured to protect every claim. There is no claim bypass. Claims do not cast propagation shadows; entity permission is separate and conservatively protects every claimed chunk.

Protected tags, negative or unknown/nonfinite hardness and contextual explosion resistance retain blocks. The default resistance threshold is 6000; existing world configurations keep their stored value. Obsidian's resistance of 1200 is below this new default, while the independent bedrock switch and protection tags still apply. Protected block entities remain intact. `clearFluids=true` clears free source/flowing water and lava after explicit tag/claim protection, and removes water with a destroyable waterlogged host. `false` retains free fluids and replaces a removed waterlogged host with its fluid state. Protected waterlogged solids retain both. Outside sources can flow into openings later.

Section batches require static removal proofs, current mutation revisions, world-border checks and audited relevant host observers. Fully clearable palettes use a constant-size replacement; mixed protected terrain and geometric boundaries use detached palette construction. State containers preserve biomes and reconcile heightmaps, sky sources, changed-cell ticks, light work and aggregated notifications. Block entities, POI-sensitive/special states, lava and waterlogged solids retain native callbacks. Unrelated installed mods no longer disable ordinary terrain batching. **`HostIntegration.registerAuditedMod` is an integration assertion, not a switch that makes arbitrary mod callbacks safe.**

Physics defaults to `NONE` (new whole-chunk native remainder uses flags 16 and consolidated chunk publication). `BOUNDARY_ONLY` notifies retained adjacent states; `COMPATIBLE` uses native flags 3. Both enabled physics modes can produce indirect effects beyond the direct volume or permission boundary. Default DROP_NONE omits block loot and suppresses new synchronous item entities in the actual deletion cell, including native container contents. Implicit unopened native-container loot generation is skipped in that same thread/level/cell scope with a null player, without changing loot metadata. Native removal callbacks still execute. Explicit player opens, existing entities, other cells/threads and later drops are unaffected; mod-specific delayed drops require host support.

Entity damage defaults to zero. Enabled damage considers currently loaded entities once, uses continuous shape containment and linear attenuation from the task's execution center, and dispatches ordinary damage events. It has separate claim checks. Entity occlusion, knockback and automatic replay without entity receipts are not provided.

## Frozen propagation and generation

The server `propagation` setting supports `NONE` (default), `AXIS_6`, `AXIS_26` and `RAYCAST_REFINED`. Non-NONE modes read the original whole region before deleting blocks. The independent `voxelregion_test:blocks_propagation` tag supplies blocker facts; it is not synonymous with `voxelregion_test:protected`. Macro-cell size must be 4, 8 or 16. Quantized directional shadows and refined frozen-box ray intersection do not simulate explosion energy.

Capture watches chunk revisions and tag-policy changes, writes an immutable hash-checked blocker file, then compiles its index on one bounded worker without world access. Missing chunks, unknown mutation tracking, changes during capture or exceeded limits prevent unsafe execution. In particular, loaded-only propagation capture pauses when it cannot read the complete original region; it cannot apply the NONE mode's unloaded exclusion rule to pretend unknown space is transparent. Recovery reloads the frozen file rather than recapturing already-cleared terrain.

Generation shortcuts require absent stored data, a native fresh ProtoChunk, full-height coverage, fluid clearing, NONE propagation/physics and current permissions/rules. `destroyBedrock=true` prepares empty sections directly. With bedrock retained, native flat layers and recognized vanilla noise surface rules have separate preparation algorithms; unsupported rules use ordinary generation. Existing terrain is always read from native storage. New images join native section-status, lighting and FULL publication, with actual save completion. See [high-performance paths](../../docs/high-performance.md) for the proof domains.

Preparation, compression and region-file storage have separate worker queues. Different region files write and flush concurrently; writes and deletes within the same file remain ordered. Native sector allocation and file format are retained. Read/scan overlays, file leases, durable header publication and shutdown draining cover the newly asynchronous writes. Completed progress waits for the real light/save/packet barriers.

## Configuration and measurements

Settings are in `world/serverconfig/voxelregion_test-server.toml` unless the embedding host chooses another name.

| Default | Value |
|---|---|
| Shared cooperative CPU budget | 40 ms/tick, reduced by current full-tick load |
| Preparation / commit work units | 262144 / 262144 per tick; palette/section batching is counted as real work, not virtual per-block writes |
| Chunk packet budget | 8388608 bytes/tick, multiplied by tracking recipients |
| Pending metadata budget | 134217728 bytes |
| Maximum radius / pending operations | 2000 / 16 |
| Retained/requested operation chunks | 4096 globally, including pending completion; read-ahead window 1024 |
| I/O and preparation workers | Each defaults to visible CPU count clamped to 4–16; configurable 1–32, restart required |
| Exact new-chunk biomes | false: one native sample near sea level fills the new column; true preserves every native quart sample. Existing biomes are unchanged |
| Progression | CENTER_OUT, whole chunks; shell width is a legacy task setting |
| Physics / propagation / entity damage | NONE / NONE / 0 |

A native callback, serialization call or generation task is not preemptible. These are cooperative limits, not a hard tick-time guarantee. Lighting, storage and packet backlogs have separate completion paths. An indivisible oversized packet incurs bandwidth debt rather than starving forever. Existing configuration files retain their previous values; the old 4 ms budget and 64-chunk window continue limiting throughput unless updated.

`ServerTickTimings` measures `MinecraftServer.tickServer` HEAD to RETURN, including Forge END work, and supplies the scheduler's adaptive timing. Vanilla `averageTickTime` and `forge tps` are sampled before END and omit adapter execution there. Report full-tick samples, CPU/RSS, stage timings and completion scope; do not label a nearby loaded-only R2000 fixture as a completed full-radius benchmark.

## Persistence and completion

Acceptance writes a flushed immutable intent under `data/voxelregion_test_intents/<uuid>.nbt`. Geometry, immutable policies, bootstrap/phase interpretation and frozen-field identity accompany persisted progress. `FrozenFieldStore` publishes `<uuid>.occlusion.nbt` without replacing existing evidence and validates schema, identity, size and SHA-256 on read.

Chunk capabilities store operation identities, completed-phase watermarks, exact in-progress cursors, pending-write sentinels and mutation revisions in the same native NBT image as terrain. Receipt capacity exhaustion fails closed. SavedData is not sufficient proof to replay a write. Journal reconciliation enters recovery; completed phases are skipped, and remaining work continues only with matching original intent and valid same-image evidence. Missing receipts, legacy STARTED without a cursor, revision mismatch, uncertain pending writes or missing frozen snapshots pause safely. An unvisited chunk after restart is not implicitly trusted.

`WRITTEN` waits for native light work, captured terrain/POI store futures, storage synchronization and required packet submission. It does not promise remote-client receipt/rendering. Save and packet queues recheck the chunk revision after light completion; a later edit requires a new light barrier. Failures are not erased by a later successful flush. Pause/cancel also settle already-submitted work; cancellation is durable and does not undo earlier writes. Ordinary resume cannot override `PAUSED_RECOVERY`.

## Validation scope

Verification sources cover geometry/fluid/protection fixtures, native block entities/heightmaps, palette/light differentials, generation proof invalidation, frozen capture and NBT, wave/control/recovery behavior, public facade calls, store failures and late-mutation fences. Added cases still require the unified GameTest run; listing or compiling them is not a pass statement. Final remapped artifacts additionally require standard dedicated-server loading, persistence and bounded large-radius measurements. Live FTB/modpack interactions, client behavior and interrupted-world cases must be assessed in their actual deployment context.

The [whole-chunk validation record](../../docs/validation-whole-chunk.md) identifies the tested artifacts, native gates, GTLCore integration, persistence/restart checks and bounded timing samples. The [original 0.1.0 record](../../docs/validation-0.1.0.md) remains historical evidence for the earlier shell implementation; its results do not validate later changes. Both records state untested boundaries and incomplete performance targets. Use disposable worlds; keep player reports, runtime worlds, raw logs and local incident evidence out of commits/uploads.
