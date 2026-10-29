# API 说明与调用协议

目标 Java 17；包名前缀 `org.voxelregion`。当前版本 0.1.0，API 尚处于初始开发期。没有 `net.minecraft` 类型进入主库。

## 1. 坐标与几何

`IntBox(minX,minY,minZ,maxX,maxY,maxZ)` 使用半开整数区间。空盒合法，反向区间非法；端点范围是 `[-30_000_000,30_000_000]`，适用于所有轴。区块/区段坐标由 `Math.floorDiv` 划分，不能用向零截断替代。

`Point3(x2,y2,z2)` 的每个量是世界坐标的两倍；其绝对值不超过 60,000,000。`Sphere(center,radius2)` 的半径同样放大两倍，范围 `0..120_000_000`。这使所有平方和都能在 signed long 中精确表达。`Point3.of`、`Sphere.of` 拒绝 NaN、无穷和非半格输入。整个空间判断使用 `(2*x+1,2*y+1,2*z+1)` 的方块中心；不会在边界改用“方块盒与球相交”。

```java
Volume sphere = Sphere.of(0.5, 64.5, -15.5, 200);
Volume box = new BoxVolume(new IntBox(-32, -64, -32, 32, 128, 32));
Volume cylinder = new Cylinder(Point3.of(0.5, 0, 0.5), 40, -64, 128);
// cylinder 的 40 是放大两倍后的半径，即 20 格。
Volume opening = Volumes.difference(sphere, box);
Volume combined = Volumes.union(opening, cylinder);
Volume intersection = Volumes.intersection(sphere, cylinder);
```

`Volume` 的四个方法：

| 方法 | 契约 |
|---|---|
| `bounds()` | 候选体素的半开包围盒；裁到可表示世界边界 |
| `contains(x,y,z)` | 此体素中心是否属于区域 |
| `classify(box)` | `OUTSIDE` / `INSIDE` / `PARTIAL`；INSIDE 必须是全覆盖证明 |
| `emitRanges(box,sink)` | 对裁剪范围输出不重复的 `(x,z,minY,maxYExclusive)`；坐标是世界坐标 |

球半径 0 只会选中恰好处于爆心的体素中心；爆心若不在任何体素中心上，结果为空。圆柱的 Y 范围由独立半开区间决定，`center.y2()` 不参与柱高。

## 2. 惰性遍历与掩码

```java
ChunkCursor chunks = new ChunkCursor(sphere, minBuildHeight, maxBuildHeight);
while (chunks.hasNext()) {
    ChunkKey key = chunks.next();
    // 这里不能无预算地同步调用 getChunk：先获取资源额度，再请求宿主加载。
}
```

游标按 32×32 Chunk 的 Region 分组，父范围完全包含时跳过子级几何重算；只保存常数个游标字段。它不是线程安全对象，也不保证每次 `hasNext()` 只做一次分类；调用者要将扫描计入预算。

`checkpoint()` 返回规范化后的下一个候选位置。`ChunkCursor.restore(volume,minY,maxY,checkpoint)` 检查格式和边界后以常数大小状态恢复，不从第一个区块重扫。这个几何游标不是重放世界修改的授权；还要验证同镜像的操作回执。

`SphereShell(center,innerExclusiveRadius2,outerRadius2,minY,maxY)` 选择内球之外、外球之内的体素；初始球用 `innerExclusiveRadius2=-1`。`RadialWavefront` 在收到当前层确认后才返回下一层，支持暂停和保存恢复。宿主不能因墙上时钟超时而跳过尚未完成的层。

`SectionKey(x,y,z).bounds()` 给出该 16³ 区段的世界包围盒。

`VoxelMask` 是不可变 4096 位集合，索引为 `x | z<<4 | y<<8`。`EMPTY`/`ALL` 为单例；使用 `VoxelMask.builder().set(x,y,z)`、`setRange(x,z,minY,maxYExclusive)` 构建。方块局部坐标为 **0..15**，Y 半开区间的终点 `maxYExclusive` 可以为 **16**。`forEachRanges` 也输出这一约定下的局部坐标。`union`、`andNot` 不修改输入。

不要混淆 `Volume.emitRanges` 的世界坐标和 `VoxelMask.forEachRanges` 的局部坐标。

## 3. 保护与 Section 计划

```java
SectionSnapshot snapshot = new SectionSnapshot(key, sectionRevision, airStateId, stateIds4096);
SectionPlan plan = new SectionPlanner().plan(volume, snapshot, protection, FrozenOcclusion.none());
```

输入数组会复制；状态 ID 属于宿主的快照词典，宿主要将普通、洞穴、虚空空气等等价空气归一到同一个 `airStateId`。不要将仍可变化的方块实体或 Minecraft Chunk 对象塞进异步计划。

流体不是空气：水源、流动水、岩浆等必须保留为非空气状态。默认 `ClearOptions.DEFAULT` 的 `clearFluids=true`：流体参与清除，允许删除的含水方块整体替换为空气。含水宿主方块受保护时，保留它及其流体状态；领地权限同样适用于流体。

关闭流体清除时，纯流体保留；可破坏的含水固体删除后留下其流体。宿主必须在同一快照中提供每格流体所对应的方块状态 ID：无流体填 `airStateId`，纯流体填原状态，含水固体填其流体的方块状态。Minecraft 对应 `state.getFluidState().createLegacyBlock()`，没有 Minecraft 类型进入主库。

```java
SectionSnapshot wetSnapshot = new SectionSnapshot(key, sectionRevision,
        airStateId, stateIds4096, fluidBlockStateIds4096);
SectionPlan keepWater = new SectionPlanner().plan(volume, wetSnapshot, protection,
        FrozenOcclusion.none(), new ClearOptions(false));
```

流体数组也会复制。旧构造函数没有流体数据，不能据此假定没有水；以它请求 `clearFluids=false` 会被拒绝。默认开启时无需流体数组，保留原快速路径；关闭时，快照已证明无流体的区段也仍可走整区段清除。

`ProtectionOracle`：

- `mayDestroy(state,x,y,z)`：能否删除。
- `blocksPropagation(state,x,y,z)`：是否阻挡传播，与能否删除独立。
- `revision()`：规则修订号，计划和提交时都要检查。
- `requiresContextualChecks()`：默认 true。位置、BE、外部规则会影响结果时必须保持 true。
- `staticSummary(snapshot)`：默认 UNKNOWN。只有证明静态规则允许全部状态时才能返回 ALL_DESTROYABLE；不能从平均抗爆值或“没见过特殊方块”推断。

计划的 `WriteKind`：NOOP、WHOLE_AIR、MASKED_CLEAR、MASKED_REPLACE。每个计划保存 `sectionRevision`、`protectionRevision` 和不可变 `clearMask`，该掩码始终仅表示写入空气。`retainedFluidWrites` 是“目标流体方块状态 ID → 不可变掩码”的映射，与 `clearMask` 以及彼此互斥；含水固体留下水时使用 MASKED_REPLACE。提交后端必须应用这两类掩码，不能将整个计划当成清空。

WHOLE_AIR 仅说明删除选择覆盖整个区段且无需留下流体，后端仍必须检查自己是否有安全的整区段提交实现。`clearFluids` 属于不可变任务参数，应与意图、进度和测试炸药参数一起保存；旧格式缺字段时默认 true。

`ClaimOracle.query(dimension,chunk,actor)` 返回独立的方块与实体许可。领地保护默认不产生阴影。提交阶段必须重新查询当前许可；不能把规划缓存当成持久授权。

`ChunkPlanning.route` 区分已存在、从未生成、生成中和未知状态。只有完整覆盖、无遮挡、得到有效 SAFE 证据时才建议安全空物化；已有 Chunk 永远不能以新空 Chunk 覆盖。返回某条路由不表示后端已实现该能力。

## 4. 冻结遮挡

```java
FrozenOcclusion occlusion = FrozenOcclusion.axis6(center, volume.bounds(), frozenBlockers, maxBlockers);
FrozenOcclusion diagonal = FrozenOcclusion.axis26(center, volume.bounds(), frozenBlockers, maxBlockers);
FrozenOcclusion refined = FrozenOcclusion.raycastRefined(center, volume.bounds(), frozenBlockers, maxBlockers);
```

`AXIS_26` 使用固定平局规则选择面、棱或角方向并挤出阻挡盒。`RAYCAST_REFINED` 对冻结盒的 BVH 做精确有理数视线相交，不模拟爆炸能量。`MacroVoxelMask` 支持 4、8、16 格宏体素；粗遮挡不改变最后的精确方块保护检查。

`FrozenOcclusion.mergeAdjacent` 仅在几何相接且传播语义保持等价时合并盒。AXIS 模式要求合并前后具有相同的带符号量化方向，不能单凭两个盒相邻就合并。

阻挡盒会被复制并构建 BVH。`AXIS_6` 的主轴取中心方向绝对值最大项，平局 X→Y→Z；其阴影包含阻挡盒自身，横截面固定，延伸到爆炸包围盒。中心在参与遮挡的阻挡盒内时保守地将整个范围遮蔽。

盒数量超预算直接拒绝，不丢弃部分阻挡物继续爆炸。输入必须来自同一逻辑快照；边改世界边采集会使结果依赖顺序，主库无法替宿主修复这种错误。Forge 适配器通过 `FrozenOcclusionCapture` 完整捕获并验证原始阻挡状态，以独立文件持久化后才构建索引和删除；不完整或变化的快照会暂停。

## 5. 调度与背压

`TickBudget.Limits(nanos,work,lightUpdates,packetBytes)` 为所有并发任务共享的额度。`trySpend` 原子检查四个维度；拒绝不会部分扣减。`RegionScheduler` 在创建它的线程上运行，轮转 `Job.step`，每次派发本身也消耗 work，防止空转。

`Step.WAITING` 表示等待加载或保存；整队等待后退出本 tick。`PAUSED` 需要调用 `resume`；异常终止并清理，不自动重放未知结果的世界写入。完成状态只代表宿主 Job 宣称完成，宿主必须等必要的保存/光照条件满足后才返回 COMPLETE。

`ResourceGate` 限制所有任务的 outstanding load/save 和 pending bytes。先获取 Lease 再分配快照、发起加载；完成/取消/失败都要 close。同一个 Lease 重复 close 安全。

这是协作式预算，不是 JVM 抢占器。单个 Mod 回调、生成任务或区段写入仍可能超时；`TickReport.overranTime` 会报告超额。宿主要把步骤做小，并记录实际成本，不能声称严格保证每 tick 不超过 4ms。

## 6. 持久化与恢复

`FileOperationJournal.prepare(OperationIntent)` 必须在第一笔世界修改前成功返回。payload 由宿主按版本编码几何、策略、维度、权限主体等不可变输入；最大 1MiB。journal 使用独占目录锁、追加记录、CRC 和 `FileChannel.force(true)`。同一 ID 不能重新 prepare，终结状态不能复活。

日志本身不等于世界事务。文件日志主要防护 JVM/进程崩溃；没有宣称跨磁盘、目录元数据及突然断电的一致事务。损坏或截断记录必须停下检查，不能截去尾部后盲目重试。

`ChunkReceipts` 保存多个 operationId→outcome。APPLIED、SKIPPED_PERMISSION、CANCELLED 都是终结结果。不能仅存最后一个 ID：A→B 后恢复 A 会丢失 A 的保护证据。receipt 必须与对应 Chunk 世界数据写入同一存档结果。

`RecoveryPolicy` 规则：

1. 操作已终结：不再重放。
2. Chunk 已保存该操作 receipt：跳过，即使玩家后来新建了建筑。
3. 无 receipt 且无法证明 Chunk 从意图产生后未变：PAUSE_UNCERTAIN。
4. 确认未触碰后，重新检查当前权限，再决定恢复或终结跳过。

`CommitGuard.check` 只做纯判断。宿主必须在同一世界线程提交窗口内复查 receipt、section revision、保护 revision 和权限；检查与写入之间不能让其他线程修改 Chunk。

## 7. 建议完整调用顺序

```text
参数/服务端权限校验 → 持久化意图 → 获取全局资源额度
→ Region/Chunk 粗筛 → 查当前领地 → 无副作用地检查存在状态
→ 等已有读取/正常生成 → 一致 Section 快照 → 纯计划
→ 主线程再查保护/修订/领地 → 原生或已证明安全的批量写入
→ 世界数据与多个 receipts 一起保存 → 等光照/网络收尾
→ 日志终结；释放所有加载票据与资源额度
```

尚未取得快照、保存结果不明、规则版本改变，都不是“成功”。宿主应在状态/UI 中分别显示规划、加载、写入、收尾和暂停原因。

## 8. 中心优先的区块游标

`new CenterOutChunkCursor(volume, center, minY, maxY)` 流式返回与几何相交的区块，按中心到水平区块包围盒的最小距离排列；平局时中心所在区块优先，再按 X/Z 确定排序。每个区块最多返回一次。内存与包围盒较短一轴的区块数成正比，不与全部面积成正比。可传 `maxFrontierRows` 限制队列大小。

`checkpoint()` 保存最后返回项与几何边界，`restore(...)` 用相同体积、中心和高度恢复；`hasNext()` 的预取不推进持久游标。完成某个区块的原生操作仍需要单独回执，游标不是世界写入成功证明。

## 9. 清理后区块的独立光照求解

`ClearedChunkLighting.Snapshot` 接受高为 16 的整数倍的 16×16 列，内容只能是空气或不透明、不发光的保留格。`opaque` 使用 `x | z<<4 | y<<8`；六面顺序由 `Face` 枚举规定，面数组长度用 `faceSize(height, face)` 获取。输入包含真实邻格的方块光/天空光、可通光边界掩码及有独立证据的直射天空列。仅有亮度 15 不能充当直射天空证明。

输入会复制为不可变数据。`new Session(snapshot, maxWorkingBytes)` 后调用 `step(maxWork)` 可分段求解；完成后用 `result()` 读取亮度或每段的 packed nibble 数组。`solve(snapshot)` 是一次完成的便捷接口，纯计算可在宿主允许的工作线程运行。对均匀全空气结果无需分配每格光照数组。

这是固定边界问题的求解器，不证明外部世界已收敛。宿主必须复查地形和边界版本，通过合法原生接口安装结果，再处理外边界传播。Forge 的 `ClearedChunkLightBatch.STRATEGY` 实现该接入，不能单独将求解结果当作保存/网络发布许可。流体、透明形状、保留发光源等不满足证明域的内容使用原生光照。

## Column nearest search

`org.voxelregion.search.ColumnNearestIndex<T>` compiles axis-aligned parameter intervals into a query with fixed leading coordinates and a varying final coordinate. `column(fixedCoordinates).unique(variableCoordinate)` returns the unique nearest value, or `null` when different values tie. Arithmetic overflow is explicit. The Forge exact-biome path falls back to native tie handling. This utility has no Minecraft/world dependency and does not approximate parameter distances.
