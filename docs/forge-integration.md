# Forge 1.20.1 adapter integration

The independent engine exposes no Minecraft, Forge or FTB types. `tools/forge-test` is the current executable reference adapter. It is a native block-write baseline for ordinary Forge 1.20.1, not the high-throughput section backend or a complete Phase 1 implementation. Hybrid server cores are outside the supported contract. GTLCore has not been modified by this work.

## Host ownership

A future GTLCore integration should own one scheduler across all dimensions, operation authorization, Minecraft-thread commit access and lifecycle event registration. Do not start independent budgets for each machine or explosion. A machine can translate its center and radius into `Sphere.of(cx,cy,cz,radius)` and submit an operation to the host adapter. Reuse the standalone core and keep FTB/Minecraft linkage in the adapter. Preserve GTLCore's own version; adding this library does not authorize a host version change.

`Sphere.of` accepts finite **half-grid** centers and radii: every supplied value must be an exact multiple of 0.5 within the library's validated coordinate bounds. Arbitrary continuous positions are rejected rather than silently rounded. The test commands deliberately align the center to `(blockX+0.5, blockY+0.5, blockZ+0.5)` and use integer radii. A future host must explicitly decide and document how it quantizes other source positions.

The current GTLCore reference workspace has `FissionReactorMachine.doExplosion(BlockPos,float)` calling `Level.explode`. A future replacement is an explicit integration decision: the new region operation has different attenuation, timing, drops, neighbor physics and entity-damage semantics. It must not be substituted merely by passing the old explosion power as the new radius.

## Version-checked Minecraft paths

These signatures were checked against the local Forge 47.3.7 mapped binary. The accompanying complete local Mojang-mapped Forge 47.3.0 source was used to inspect lifecycle behavior; runtime tests must still cover the actual deployed Forge/mod set.

| Concern | Target API and constraints |
|---|---|
| Loaded state without generation | `ServerChunkCache.getChunkNow(x,z)` on the server thread; null means not available, not proof of never-generated terrain. |
| Normal generation/load | `addRegionTicket` with a distinct operation identity, then poll `getChunkNow` on later ticks; pair every ticket with `removeRegionTicket`. |
| Avoid deceptive asynchronous APIs | `getChunkFuture(...)` calls `managedBlock` when invoked on the main thread in this target version. A returned future does not make the call a bounded nonblocking request. |
| Existing stored data inspection | `chunkMap.read(ChunkPos)` uses the existing I/O worker and includes queued writes; an absent NBT does not prove absence of concurrent generation or structures. Never write `.mca` directly. |
| Compatible mutation | `ServerLevel.setBlock`; `LevelChunk.setBlockState` alone omits the `Level.markAndNotifyBlock` path, including POI/neighbor/client notifications. |
| Palette checks | `LevelChunkSection.hasOnlyAir`, `maybeHas`, `getStates`; static palette tests cannot prove unknown contextual mod callbacks safe. |
| Heightmaps | `LevelChunk.setBlockState` updates all four FULL heightmaps. `Heightmap.primeHeightmaps` does not reset an already populated empty column; a future rebuild must initialize new zeroed maps before priming. |
| Skylight sources | `chunk.initializeLightSources()` rebuilds source-column metadata; this alone does not finish relighting. |
| Lighting | Normal writes enqueue `checkBlock` and section-state changes. `ThreadedLevelLightEngine.lightChunk` participates in generation/light tickets and is not a proven general relight replacement. Do not set `isLightCorrect=true` manually. |
| Client updates | `ServerChunkCache.blockChanged` aggregates changes through `ChunkHolder`; normal world writes request this. Explicit whole-chunk packets require correct tracking and light completion first. |
| Tick cleanup | `LevelTicks.clearArea(BoundingBox)` exists, but clearing an entire section also cancels ticks of retained protected voxels. A masked backend needs selective reconciliation. The current baseline follows native block replacement behavior. |
| Receipts | Forge chunk capabilities serialize through `ForgeCaps` in the same `ChunkSerializer` result. There is no general `LevelChunk.getPersistentData()` method in this target API. |
| Save acknowledgement | `ChunkDataEvent.Save` precedes the asynchronous chunk write and is not disk completion. `chunkMap.flushWorker()` is blocking and does not replace per-operation durable acknowledgements. |

`new LevelChunkSection(airStates, oldSection.getBiomes())` can preserve biomes and correct derived counts, but replacing an array element skips native block removal callbacks, block entities, POI, skylight-source maintenance, lighting queues, heightmaps, ticking containers, notifications and host mixins. Consequently the current adapter never enables this optimization. A later fast path needs explicit trusted-state eligibility, correct side-effect reconciliation and native regression evidence before advertising a benefit.

GTLCore also injects `LevelChunk.setBlockState` to notify `BlockStateWatcher`, and its bloom mesh revision tracking injects `LevelChunkSection.setBlockState/read`. Direct container/section replacement bypasses those hooks. A future host bulk-write adapter must either keep the native path or integrate equivalent host notifications and mesh invalidation.

## Optional FTB Chunks integration

The local host dependency `curse.maven:ftb-chunks-forge-314906:5378090` contains FTB Chunks **2001.3.1**. Its published API provides:

```java
FTBChunksAPI.api().isManagerLoaded();
ClaimedChunk claim = FTBChunksAPI.api().getManager().getChunk(
    new ChunkDimPos(level.dimension(), chunkX, chunkZ));
boolean mayDestroy = claim == null || claim.getTeamData().canExplosionsDamageTerrain();
```

`ChunkDimPos` comes from FTB Library. The terrain helper reads the owning team's explosion property. The validation adapter resolves these published methods reflectively only when `ftbchunks` is loaded, avoiding a mandatory FTB dependency. It pauses when an installed provider cannot answer. A production typed adapter may instead use optional compile-only dependencies and isolate its class loading.

Re-query permissions immediately before committing mutations. Treat a claim denial independently of propagation. Do not inherit an administrator's FTB bypass flag automatically. Entity permission is separate; the test adapter conservatively protects all claimed entities. The live FTB matrix, dynamic claim changes and unsupported provider behavior require actual integration tests before release.

## Current execution contract

The test adapter performs Sphere/NONE operations; library support for other shapes or propagation does not imply those modes are wired into Minecraft. It offers loaded-only pause or explicitly authorized normal generation, a shared cooperative write budget, native per-block maintenance, optional distance-based entity damage (default off), explicit physics `NONE`/`COMPATIBLE`, immutable intents, SavedData diagnostics and co-serialized chunk receipts.

Direct clearing includes fluids by default (`clearFluids=true`). After checking the explicit protected tag, pure `LiquidBlock` states with nonempty fluid do not use the solid-block hardness/resistance filter. Claim checks still gate the actual write. Waterlogged solids use their host block's protection decision; an allowed deletion writes AIR. With `clearFluids=false`, free liquids are retained and an allowed waterlogged-solid deletion writes `state.getFluidState().createLegacyBlock()`. Protected solids retain both the block and its water. Persist the immutable choice with each operation and default missing legacy fields to true. Neither mode stops surviving sources outside the operation from flowing into the cleared region later.

`NONE` physics is the test default and means flags 2|16, not `BOUNDARY_ONLY`. `COMPATIBLE` uses flags 3 and allows ordinary indirect effects, which can escape the direct geometry/protection filter. Native callbacks can emit inventory contents. The adapter does not claim to suppress every mod effect, control every save/light/network backlog, finish durable persistence, perform restart replay, or construct legal empty chunks without generation. Status `WRITTEN` acknowledges submitted terrain changes only. Unfinished restart work becomes `PAUSED_RECOVERY` and cannot resume against potentially newer construction.

Keep API design and integration documentation in this library repository. Keep test worlds, logs, player evidence and local incident archives out of uploads and commits.
