# Forge 1.20.1 adapter integration

The portable engine has no Minecraft, Forge or FTB types. The reusable Forge adapter is published separately from the test mod and includes its public facade, runtime and native mixin bridges. It targets ordinary Forge 1.20.1 / 47.3.7 and Java 17. Hybrid server cores are outside the contract; GTLCore has not been modified.

See [embedding and public API](forge-embedding.md) for installation, [execution/performance contracts](high-performance.md) for the batch pipeline, and the [0.1.0 validation record](validation-0.1.0.md) for the tested artifact and deployment scope. This document describes implemented behavior and integration obligations.

## Host ownership and geometry

Install `ForgeRegionEngine` once from the host mod constructor. The adapter then owns one scheduler and lifecycle across all dimensions. The host authenticates requests and invokes submission/control on the server thread; it must not create independent budgets per machine or explosion. Keep Minecraft linkage in the adapter and reuse the independent engine.

`submitSphere` accepts a block position and integer radius, placing the center at that block's center. `submit` accepts immutable, NBT-serializable `RegionGeometry`: Ball, Box, Column, or a bounded Combined expression using union/intersection/difference. Core geometry retains its exact half-grid convention and block-center sampling. An arbitrary custom `Volume` is not automatically a supported persistent Forge request.

For generic geometry, execution uses the bounding box's derived block-center and an enclosing integer radius for wave ordering and attenuation; the supplied exact volume still determines block membership. The enclosing radius is checked against the server maximum. New center-out tasks have a radius-one bootstrap and explicitly select the center chunk first, then complete non-overlapping shells. The phase geometry is intersected with a non-spherical request. The fast progression option uses one phase with storage-oriented traversal. NBT preserves geometry, cursor, policy, shell width and bootstrap interpretation; old tasks are not reinterpreted using new defaults.

A future GTLCore caller replacing a vanilla `Level.explode` must select its region geometry and policy explicitly. Region radius is not vanilla explosion power; timing, drops, neighbor physics, propagation and entity effects differ. Adding this dependency does not authorize changing the host version.

## Native paths and their constraints

The target signatures were checked against the local Forge 47.3.7 mapped binary; complete local Forge 47.3.0 sources also informed lifecycle inspection. Actual deployed mods still need integration validation.

| Concern | Adapter contract |
|---|---|
| Loaded state | Server-thread `getChunkNow(x,z)`; null means unavailable, not proof of never-generated terrain. |
| Loading/generation | Distinct operation tickets, later polling, and paired release. Main-thread `getChunkFuture` can call `managedBlock`, so a future return type alone is not a nonblocking guarantee. |
| Existing data | Native storage/IOWorker reads include queued writes. Absent NBT alone does not prove no existing/in-flight generation. No direct `.mca` writes. |
| Exact mutation | `ServerLevel.setBlock` maintains native block-entity, POI, height, light and notification paths. `LevelChunk.setBlockState` alone is insufficient as a replacement. |
| Palette batches | Full geometry coverage, unshadowed cells, trusted static states, host support, world border and current revisions are checked before replacing state containers. Biomes remain. |
| Height and sky metadata | Batch maintenance rebuilds zero-initialized FULL heightmaps and skylight-source columns; calling `primeHeightmaps` on populated maps is not a reset. |
| Tick cleanup | Whole-deleted sections can clear their ticks. Partial masks retain native replacement behavior; protected voxels must not lose scheduled ticks through a blanket clear. |
| Lighting | Native mailbox tasks consume updates; mixed-air and water batches also reconcile sky-source/shadow interfaces. No manufactured `isLightCorrect` flag and no generation-owned LIGHT ticket release. |
| Packets | Whole-chunk packets follow light and revision validation, use actual tracking recipients, and charge serialized bytes per recipient. Submission is not client acknowledgement. |
| Receipts | Capability data is stored under `ForgeCaps` in the same ChunkSerializer image as terrain. SavedData is not a substitute for that evidence. |
| Save acknowledgement | Captured terrain/POI store futures plus native worker synchronization are awaited. `ChunkDataEvent.Save` precedes async I/O and cannot itself prove durability. |
| Full tick timing | `ServerTickTimings` wraps `tickServer` HEAD/RETURN. Vanilla `averageTickTime` and `forge tps` omit the Forge END work that contains region execution. |

Batch write support is conditional. `HostIntegration` must account for all loaded mods' relevant storage mutation paths and aggregated observers. Unknown mods cause native fallback. Registering an empty observer without auditing its callbacks is not a safety proof. GTLCore's BlockStateWatcher and mesh-revision hooks are examples of observers that a future direct-section integration must explicitly notify or invalidate; the native fallback remains available.

## Protection, fluids and physical effects

Optional FTB Chunks linkage uses the published 2001.3.1 API: `FTBChunksAPI.api().isManagerLoaded()`, the claimed-chunk manager and the owning team's `canExplosionsDamageTerrain()`. Reflection is isolated until `ftbchunks` is present. Installed-but-unavailable or incompatible protection pauses safely. Claims are rechecked before commits, and denial does not become a propagation blocker. Administrator status is not inherited as a bypass.

`voxelregion_test:protected`, unbreakable/unknown hardness and context-sensitive resistance protect blocks. The fluid policy is persisted independently. `clearFluids=true` includes free source/flowing liquids after explicit tag and claim checks; waterlogged solids obey their host protection. With `false`, free liquids remain and allowed host removal writes `state.getFluidState().createLegacyBlock()`. Protected hosts keep both. Neither choice prevents later refill from outside sources.

Physics defaults to `NONE` (2|16). `BOUNDARY_ONLY` notifies retained neighbors; `COMPATIBLE` uses flags 3. Indirect fluids, falling blocks and callbacks can extend beyond the direct selection or claim boundary. Ordinary block loot is not generated. A synchronous deletion scope suppresses new item entities inside the replaced cell, including native container contents, while still running native removal callbacks. Existing items, other cells, other threads and later ordinary drops are untouched; mod-specific deferred drops require host integration.

Entity damage is independent and defaults to zero. Enabled processing considers loaded entities once, checks continuous geometry membership and separate claim permission, then uses linear attenuation from the execution center and native damage events. Every claim protects entities. This does not add entity occlusion or knockback; uncertain entity replay after restart is refused.

## Frozen propagation and generation

Server policy selects `NONE`, `AXIS_6`, `AXIS_26` or `RAYCAST_REFINED`. Non-NONE modes use the separate `voxelregion_test:blocks_propagation` tag, macro-cell sizes 4/8/16, and a complete pre-mutation capture. A tag can protect a block without blocking propagation, or block propagation independently of deletion permission. These modes define geometric shadows, not an energy simulation.

Capture runs on the world thread in bounded steps. A mutation subscription and rule revision protect the entire capture interval. A sealed snapshot contains detached boxes and revision evidence, is written as immutable checked NBT, and is compiled on a bounded single worker without world access. The original frozen file and its hash are reused after restart; already-cleared terrain is never used to reconstruct the original shadow.

Overlapping operations in the same dimension execute in acceptance order when either enables propagation. The later operation cannot start capture until the earlier operation is terminal and all of its native acknowledgements have drained. A paused predecessor retains this ordering. Each snapshot describes the stable world when that operation becomes eligible; separate explosions do not pretend to share one simultaneous snapshot. NONE/NONE work can still advance concurrently under chunk reservations.

Default NONE/loaded-only execution excludes unavailable chunks and continues. Non-NONE capture instead requires the original entire region to be readable: unknown space, changing facts, unsupported tracking or exhausted limits cannot be treated as transparent. Those conditions wait for bounded resources or pause safely, depending on the failure. Explicit generation permission permits normal terrain loading before capture; it does not authorize a shortcut that erases blockers before they are observed.

The generation fast path supports only proven vanilla flat profiles: native storage absence, no existing/in-flight holder, a fresh native ProtoChunk, full-height coverage, static rules, NONE propagation, no features/structures and a strictly verified generator/mod set. NOISE processing retains protected configured layers while omitting eligible layer writes. Retained terrain is capped at 4096 writes and at least one nonair layer must be skipped; otherwise ordinary generation is preferable. Permissions, rules and border are checked again before applying the shortcut; native generation/light/FULL progression remains in place. UNKNOWN or changed evidence uses normal generation. Auditing a bulk observer does not certify worldgen/retrogen behavior.

## Completion and recovery

The flushed immutable intent precedes acceptance. Durable checkpoints and SavedData preserve controls and phase progress; chunk receipts record operation identity, completed-phase watermarks, exact remaining cursor, pending-write sentinel and mutation revision in the terrain image. Recovery reconciles journals, skips already-completed phases and validates the remaining evidence before replay. A missing receipt, legacy STARTED without a cursor, changed revision, uncertain pending write or unavailable original frozen file pauses the old operation. A later loaded chunk is not implicitly authorized for a restarted task.

`SETTLING` waits for submitted work. `WRITTEN` follows required native lighting, captured storage writes/synchronization and packet submission. Batch light failures remain part of the combined acknowledgement. Save/packet queues validate the current chunk identity and mutation revision after their light future; intervening edits trigger a new barrier, and reentrant serialization changes cannot be acknowledged as a stable image. Stop fails pending acknowledgements rather than marking them complete.

Pause/cancel also settle already-submitted work. Durable cancellation takes precedence over older SavedData, completed phase receipts prevent replay against new construction, and ordinary resume cannot override uncertain recovery. Cancellation retains prior writes and is not rollback. These guarantees depend on audited mutation tracking; they do not claim a global atomic snapshot across all chunks, entities and remote clients.

## Verification and deployment

The repository contains native GameTests for the implemented paths and a separate local production probe. Final acceptance must use the actual remapped artifacts, not only a Loom development launch. Record seed, budgets, loaded/generated scope, CPU/heap, full-tick timing, light/storage tail and task outcome. A nearby fixture pass in loaded-only R2000 proves neither full-radius completion nor arbitrary mod compatibility. Newly added epoch, geometry and propagation cases require their own completed unified run before being described as passing.

Keep API design, integration documentation and test sources here. Keep local worlds, player evidence, raw logs and incident archives out of commits and uploads.
