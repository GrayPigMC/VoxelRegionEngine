# Voxel Region Operations Engine（VROE）——超大规模爆炸与体素区域操作设计规范

> **版本**：v0.1 / 设计草案  
> **日期**：2026-10-27  
> **状态**：讨论稿，尚未冻结、尚未实现  
> **定位**：独立算法库 + Minecraft 版本适配层  
> **首选适配目标**：1.20.1 forge 
> **核心原则**：从需求、几何模型和公开接口独立设计，不复制第三方项目源码、代码组织或非必要实现细节。

---

## 0. 设计目标与结论

这不是“把原版 TNT 的威力调大”的项目，而是**支持几千万乃至数十亿体素候选范围的区域操作引擎**。爆炸是第一个业务场景，未来可扩展为圆柱开挖、平整区域、区域填充、结构拆除等。

设计的关键转变：

1. **从方块枚举转为区域分类**：按 Region → Chunk → Section → 局部行段逐级缩小工作量。
2. **从单方块写入转为 Section/Chunk 批量写入**：完整覆盖、无需保护时可以整 Section 清空；不必逐格写空气。
3. **区分已加载、已生成未加载、从未生成的 Chunk**：对“可以证明安全”的未生成完整覆盖 Chunk，直接生成合法的空地形 Chunk，而非生成完整地形再删除。
4. **显式尊重保护规则**：原版不可破坏方块、Mod 特殊方块、结构、FTB Chunks 领地保护；既要考虑方块能否删除，也要单独决定它能否阻断冲击波。
5. **传播近似而不是精确流体/冲击波模拟**：默认精确球形几何 + 可选的低分辨率轴向遮挡，不求能量、动量守恒。
6. **优先稳定性**：全局预算、幂等恢复、动态权限复查、Chunk 生命周期合法、服务端线程安全。

**最终推荐组合**：

- 默认：精确球形破坏范围 + 可保护方块 + FTB 权限 + Section 快速路径。
- 可选：六轴粗遮挡 `AXIS_6`；后续增加 26 方向或射线精修。
- 可选：对已确认安全、尚未生成、完全覆盖的 Chunk 执行“合法空 Chunk 物化”。
- 有结构或生成安全性不明时：先正常生成，再使用相同保护与清除算法，牺牲少量性能换取兼容性。

---

## 1. 范围、语义和非目标

### 1.1 必须支持

- 球体 `Sphere`、轴对齐长方体 `AABB`、圆柱体 `Cylinder`；后续支持几何并/交/差。
- 大爆炸的逐圈视觉推进，或可配置为尽快完成。
- 单个、并发、部分重叠的区域操作。
- Minecraft 原版方块与其他 Mod 方块混合存在的世界。
- 未加载区块、未生成区块、不同维度、负 Y、动态世界高度。
- 高抗爆方块的保留与可选遮挡。
- FTB Chunks 及未来其他领地 Mod 的保护插件。
- 方块实体、实体伤害、流体、光照、高度图、客户端同步的正确收尾。
- 任务中止、服务器重启、重试、恢复，避免把已经完成后的玩家建筑再次破坏。

### 1.2 明确不追求

- 不还原原版 TNT 的射线能量衰减、掉落物或所有方块事件。
- 不进行精确的压力波流体动力学、材料形变、热传导、泥沙守恒。
- 不默认把所有未生成的区块强制加载进服务器内存。
- 不保证任意未知 Mod 生成器都可在跳过生成流程后安全产生空 Chunk；无法证明时使用兼容回退。
- 不把不同版本的 `LevelChunk` 内部类当成稳定的算法库 API。

### 1.3 三种相互独立的规则

注意下列规则绝对不能混为一谈：

1. `canDestroy(block, context)`：方块**能否被删除**。
2. `blocksPropagation(block, context)`：方块**是否遮挡冲击波**。
3. `mayMutateChunk(chunk, actor, context)`：引擎**是否有权限改动区块**。

例如：基岩可以“不可删除且能遮挡”；FTB 领地可以“不可修改但不遮挡”；黑曜石可以“保留但不遮挡”，或者按配置成为屏障。

---

## 2. 来自问题观察的性能机会（仅描述需求，不移植实现）

大范围破坏的主要成本不一定是球面数学计算。即使避免了原版爆炸的复杂路径，逐个产生方块坐标、写 Palette、更新光照、按方块同步、加载/生成 Chunk、存档持久化依然昂贵。

应测量并隔离以下成本：

| 层级 | 潜在瓶颈 | 对应策略 |
|---|---|---|
| 几何 | 枚举空气、重复扫描、边界求根 | AABB 分类、跳过、行段生成 |
| Section | 对 4096 个方块逐个设为 AIR | 整 Section 清空快速路径 |
| 保护 | 对每个方块重复查询抗爆/权限 | Palette 预筛、Chunk 级领地快照 |
| Chunk | 强制加载、世界生成 | 区分存档状态；安全空 Chunk 物化 |
| 世界更新 | 高度图、光照、邻接、POI | 变更聚合、专门收尾 |
| 网络 | 频繁细粒度通知 | Section/Chunk 级合并，控制每 Tick 预算 |
| 持久化 | 大量零散写入、未完成任务 | Region 局部性、有限并发、操作日志 |
| 多爆炸 | 重复清除与重复加载 | 合并纯几何工作、空区段跳过 |

任何加速结论必须来自与相同世界、相同保护策略和相同正确性标准的基准对比，不能把跳过光照、结构或实体功能造成的结果当成无代价加速。

---

## 3. 几何算法：逐级分类，而非遍历球体中所有体素

### 3.1 几何约定

- 采用整数方块坐标，空间测试使用 **64 位平方距离**，避免大半径时 32 位乘法溢出。
- 爆心可以是连续坐标；涉及方块时统一使用**方块中心** `(x+0.5, y+0.5, z+0.5)`，或明确采用“方块立方体与体积相交”的语义，禁止在不同阶段混用。
- 方块包围盒采用半开区间 `[min, max)`；`minBuildHeight` 包含，`maxBuildHeight` 排除。
- 对 `r=0`、负半径、世界边界和极大坐标做显式校验。

### 3.2 球与 AABB 的三态关系

对待判断的 AABB `B` 和半径为 `R` 的球：

- `d_min²(B, center) > R²` ⇒ `OUTSIDE`：完全跳过。
- `d_max²(B, center) ≤ R²` ⇒ `INSIDE`：整个 AABB 在球内。
- 其他 ⇒ `PARTIAL`：继续划分或精确扫描。

其中 `d_min²` 是球心到 AABB 的最小平方距离；`d_max²` 是球心到 AABB 最远角点的平方距离。采用半开边界和体素中心语义时，必须统一调整最大角点取值，保证覆盖判定**不会错误地判定为全覆盖**。

### 3.3 分层扫描

```text
Region（32×32 个水平 Chunk 的物理存储局部性单位）
 └─ Chunk（16×16×整个世界高度）
     └─ Section（16×16×16）
         └─ 必要时的 2D/1D 行段与单体素
```

每一层尽量只计算一次包围盒关系：

- `OUTSIDE`：不加载、不生成、不写入。
- `INSIDE` 且规则可证明安全：使用最快的全覆盖操作。
- `PARTIAL`：向下一层细分。

边缘 Section 不必生成一大串 `BlockPos`，直接按 `(localX, localZ)` 求有效 `Y` 区间，输出**连续范围**，由后端批量清除。

### 3.4 避免冗余扫描

- 不为 X/Z 扫描条带添加多余的“一格重叠”；只要半开区间划分一致就不会有缝。
- `hasOnlyAir` 或等价语义的全空气 Section 直接跳过。
- 非空扫描先判断 Palette 中是否存在可破坏状态，减少不必要的逐方块验证。
- 局部范围可以用 64/256/4096 位掩码、RLE 行段或压缩位图表示，根据密度切换表示形式。
- 不要跨 Chunk 随意重排实际修改，以免增加 Chunk 反复切换与 I/O。

---

## 4. Chunk 生命周期和未生成区块：四路决策

一个 Chunk 的“当前加载状态”与“存档是否已生成”是两个维度，不能简单用 `getChunk()` 判断（它可能触发生成）。

### 4.1 决策表

| Chunk 状态 | 区域覆盖 | 优先路径 |
|---|---|---|
| 已加载 | 完整或局部 | 主线程 Section 级修改 |
| 已生成、未加载 | 完整或局部 | 安全读取已有 Chunk → 清除 → 保存；可按任务预算延后 |
| 从未生成 | 完整覆盖且已证明安全 | 直接物化**合法的全空气地形 Chunk** |
| 从未生成 | 局部覆盖、结构相交或安全性未知 | 正常走世界生成生命周期 → 精确裁剪 |

特别注意：**不允许用新的空 Chunk 覆盖一个已生成但当前未加载的 Chunk**，因为其中可能含玩家建筑、机器、实体、POI、方块实体及 Mod 数据。

### 4.2 “直接生成空 Chunk”的准确含义

它不是 `ChunkStatus.EMPTY`，也不是渲染缺区块用的临时 `EmptyLevelChunk`。目标是一个内容为全空气、但完成所需服务端生成/存档契约的合法 Chunk。

至少需要：

- 正确的维度、Chunk 坐标、生成状态与生物群系信息。
- 合法的全空气状态容器及 Section 派生计数。
- 对全空气世界正确的高度图和光照初始化；**不要伪造光照完成标记**。
- 结构起点/引用、生成任务依赖、特征边界影响的正确状态。
- 空或一致的方块实体、定时刻、实体关联和 POI 状态。
- 使用版本适配层允许的保存/发布流程，不直接绕过 ChunkHolder、生成调度器或竞争性改写 `.mca` 文件。

**这个路径属于高风险性能优化，不可仅通过手写一个 NBT `Status=full` 来实现。**

### 4.3 空 Chunk 安全性证明（EmptyChunk Safety Proof）

`canMaterializeEmpty(chunk)` 不是简单的“这里尚未有方块”。它必须同时证明：

1. Chunk 从未生成且没有已存在玩家/Mod 数据。
2. 几何范围完整覆盖该 Chunk 的所有可破坏空间（若底部需保留基岩，可用“全空气 + 保护层”专用路径，不称为严格全空气）。
3. 不存在须保留的结构组件、特殊地形、世界生成特征或未来跨边界写入；或已安装可验证的约束机制。
4. 传播遮挡结果已确定，该 Chunk 全部位于应清除的暴露区域。
5. FTB/其他领地保护允许修改，并且提交时仍允许。
6. 版本适配层能保证合法的 Chunk 完成生命周期。

安全性结果用三值表示：

- `SAFE`：有充分证据可用快速路径。
- `REQUIRES_GENERATION`：已知存在需要保留的结构或特殊地形。
- `UNKNOWN`：未知生成器/缺少足够证据；**必须回退**。

### 4.4 发现结构后直接正常生成

这是最推荐的兼容策略：

- 先利用世界生成器的结构规划/结构引用数据查找可能相交的结构。
- 若结构任一组件包围盒与 Chunk 有交集，就走正常生成，再执行保护过滤。
- 必须检查跨 Chunk 组件，不仅看结构起点所在 Chunk。
- 不能因为“要塞结构已发现”就只保留整座要塞，也可以在正常生成后仅保留受保护方块（如末地门框架和门内方块）。

**局限**：结构检测无法穷尽基岩层、世界生成阶段的特殊矿物、未知特征、Mod 自定义生成器。原版主世界基岩应采用可证明的专门策略；不清楚的 Mod 世界生成规则必须回退。

### 4.5 基岩层的专用优化

“已知结构稀疏，所以有结构才生成”对要塞通常有效，但**基岩不是稀疏结构**：大面积主世界/下界 Chunk 都含原版生成的基岩。若必须保留原始基岩，通用“全空气 Chunk”判定会失败。

因此可增加 `PROTECTED_TERRAIN_ONLY` 路径：对已经可信识别的原版生成器，只运行或复现**必要的受保护地形生成部分**（例如生成器可提供的基岩层结果），不生成其余噪声地形、洞穴和装饰；然后构造合法 Chunk。这个路径必须和相同世界种子、生成器版本、生成设置下正常生成的保护方块逐格一致，并接受回归测试；不能随便铺一层固定基岩伪装兼容。其他 Mod 生成器若未明确提供安全接口，仍然回退正常生成。

### 4.6 跨区块生成与生命周期竞争

- 邻区 FEATURES/结构放置可能向空 Chunk 写树叶、结构或方块。
- 要与正在进行的 Chunk 生成任务、保存队列协调，不能在磁盘上替换正在被写入的区块。
- 如果世界生成后续步骤仍有跨边界写入能力，应维持受控的禁止写入/生成屏障，或在相关生成阶段结束后按操作版本再次清理。
- 服务器版本更新、数据包更新和 Mod 增减可能带来 retrogen/结构语义变化，空 Chunk 快速路径必须有版本兼容策略。

**可行实施顺序**：先做“完整正常生成 → 批量清除”的正确模式；验证正确后，再增加能保持 Chunk 生命周期合法的空 Chunk 物化后端。

---

## 5. 原版与 Mod 高抗爆方块保护

### 5.1 保护规则

默认保护候选包括：

- 基岩、末地传送门框架、末地传送门、末地折跃门、屏障等原版特殊方块。
- 配置中指定的方块 ID/Tag。
- 已注册的 Mod 提供的不可破坏方块或受保护多方块结构。
- 需要世界状态、方块实体或位置判定的动态保护对象。

**不要仅凭单个爆炸抗性数字认定“不可破坏”。** 硬度、爆炸抗性、被某类爆炸破坏的权限是不同语义；实际数值和版本实现可能变化。

建议配置：`protectUnbreakable=true`、`protectedTags=[]`、`protectedBlocks=[]`、`allowBreakingHighResistance=false`、`customResistanceThreshold`（只作为附加策略）。

### 5.2 Palette 预筛选

对一个 Section：

1. 如果全空气，跳过。
2. 使用 Palette 判断是否**可能**包含保护方块。
3. 若能证明 Palette 中没有保护状态，且不存在位置/BE/外部回调规则：可以整 Section 清空。
4. 否则切换到局部范围精确判定；真正受保护的方块保留，其余按清除掩码处理。

缓存最好区分“仅基于 BlockState 的静态判定”和“需要坐标或世界对象的动态判定”。缓存规则版本改变时失效。

### 5.3 以 Section 最大抗爆上界作为快速筛选（可选）

如果使用数值抗爆阈值 `T`，可以在读取 Section Palette 后统计：

- `maxResistanceUpperBound`：该 Section 中各类 BlockState 的保守最大抗爆上界；
- `maybeContainsUnbreakable`：是否可能包含必须保留的特殊方块；
- `requiresContextualCheck`：是否存在世界/坐标/方块实体依赖的动态判定。

若 `maxResistanceUpperBound < T`，没有显式保护标签且没有任何动态规则，便可走整 Section 快速路径。若上界超阈值，则需要定位可能受保护的方块；**不能因为上界高就把整个 Section 都保留**。如果 BlockState 的爆炸抗性会随世界/实体变化，宁可把上界视为 `UNKNOWN`，不强求精确缓存。

这些数据可按 `(Palette identity, policy revision)` 缓存；Palette 变化、数据包刷新、配置或 Mod 注册变化会使缓存失效。实际调用 Minecraft 对应版本公开/受支持的属性接口，不应假设抗爆值常数跨版本不变。

### 5.4 保留方块 ≠ 保留完整结构

- 默认只保证受保护方块存在，例如末地门框架可能悬浮于爆炸坑中。
- 可选提供 `preserveProtectedStructureEnvelope`：保护门框周围一定几何包围盒，防止悬浮与失去支撑。
- 对末地折跃门、特殊传送装置，不能只保存方块状态，还要保留必要方块实体数据和目标连接信息。
- 若结构自身有 Mod 提供的完整性约束，可由对应适配器提供“保护体积”。

---

## 6. FTB Chunks 与其他领地保护系统

### 6.1 兼容原则

引擎直接修改 Section 时，不能依赖原版爆炸事件一定会过滤这些写入。因此要在**真正提交 Chunk 修改之前主动检查保护**。

FTB Chunks 已公开的 API 设计概念包括：查询 Chunk 归属、队伍的 `ALLOW_EXPLOSIONS` 属性、以及管理者的绕过保护能力。**接口与包名以实际编译的目标 MC/FTB 版本为准**；算法库不直接依赖 FTB 类。

建议实现 `ClaimProtectionAdapter`，让其他领地 Mod 也能提供同一语义。

### 6.2 三档 Chunk 破坏策略

| 模式 | 含义 | 默认 |
|---|---|---|
| `RESPECT_CLAIM_SETTINGS` | 遵守领地主队伍的“允许爆炸破坏”设置 | **是** |
| `PROTECT_ALL_CLAIMED` | 所有已认领 Chunk 都保护，不考虑队伍开关 | 否 |
| `BYPASS_CLAIMS` | 管理员强制忽略领地的方块破坏权限 | 否，仅服务器管理员 |

另外提供独立参数：

- `claimedChunksBlockPropagation=false`：被领地保护的 Chunk 是否也作为整体不可穿透屏障。
- `protectEntitiesInClaims=true`：实体伤害走单独的领地兼容规则。
- `respectOtherClaims=true`：未来联动其他保护系统。

**领地可以保护方块但不遮挡冲击波**，这两者默认独立；否则会形成几乎无穷长的奇怪整区块阴影。

### 6.3 处理时序与权限安全

- 任务创建时预读取领地元数据，加速规划。
- 真正修改前重新检查当前 Chunk 归属和队伍爆炸权限。
- 玩家在任务处理中新增认领，应立即阻止尚未提交的清除；取消认领后，已跳过的 Chunk 不应悄悄在未来被旧任务清除，除非重新发起/明确恢复策略。
- `BYPASS_CLAIMS` 是**服务器级授权操作**，不能仅通过客户端 UI 发包开启；也不能因为某玩家有 FTB admin bypass 就默认赋予所有爆炸源绕过权限。
- 爆心所在 Chunk 的权限不代表全部受影响 Chunk 的权限。
- 保护方块的规则只保护直接世界修改；水流、火焰、落沙和连锁爆炸等间接影响需要另行声明策略。

### 6.4 建议缓存

按 `(dimension, chunkX, chunkZ, claimRevision)` 缓存权限决策，在同一批 Section 中复用；到提交阶段校验修订版本或重新查询。若保护插件不提供修订号，就通过短生命周期缓存并强制提交前复查。

---

## 7. 冲击波遮挡：仅遮挡方向，允许不精确

### 7.1 三档传播模式

| 模式 | 定义 | 目标 |
|---|---|---|
| `NONE` | 仅按球/几何体清空；保护方块自身，但不遮挡后方 | 最快、默认基线 |
| `AXIS_6` | 遇到阻挡物后沿一个主坐标轴延伸固定宽度阴影 | 首选近似模式 |
| `AXIS_26` | 量化为 6 面 + 12 棱 + 8 角方向 | 视觉改善，成本适中 |
| `RAYCAST_REFINED` | 必要位置使用分层 DDA/稀疏射线精修 | 可选高质量模式 |

名称 `AXIS_6` 的“6”指正负 X/Y/Z 六个方向，而不是精确的 3D 扇形。

### 7.2 主轴阴影（独立算法定义）

给定爆心 `C` 和阻挡盒 `B`：

1. 计算 `B` 中心到 `C` 的方向向量 `v`。
2. 选择 `abs(v.x), abs(v.y), abs(v.z)` 最大的轴及其符号作为主传播方向。
3. 沿该方向，将 `B` 投影延伸至爆炸边界，形成**固定横截面的长方体阴影**。
4. 将所有阴影体积求并集；再与原爆炸几何体求差集，作为“需被清除的候选体积”。
5. 对边界 Section 再细分，避免粗阴影误伤受保护对象。

数学上可将每个阻挡盒的轴向挤出表示为 `Shadow(B, primaryAxis, R)`；最终候选清除区域为：

`Candidate = ExplosionVolume \ Union(Shadow_i)`

之后仍必须做 `canDestroy` 与领地保护判定。这里的阴影是**游戏视觉近似**，不是物理精确结果；斜向爆炸、墙角绕射可能有明显不真实形状。

### 7.3 阻挡物来自哪里

- 已加载/已生成 Chunk：扫描 Section Palette 的潜在阻挡物，必要时精确定位。
- FTB 领地：若配置 `claimedChunksBlockPropagation=true`，可直接将整个领地 Chunk 的空间盒用作屏障，**无需生成其地形**。
- 已知结构：通过结构组件定位标记需要生成的 Chunk，正常生成后确认实际阻挡方块。
- 未知生成器：没有“这里不存在阻挡物”的证据时，不能声称已经得到精确遮挡；应回退生成或将对应方向标记为未决。

### 7.4 低分辨率遮挡加速

- 一个 16³ Section 可粗划分为 `4×4×4 = 64` 个宏体素，每个代表 4³ 个普通方块。
- 用 64-bit 掩码记录各宏体素是否包含阻挡候选，或存其他低分辨率索引。
- 相邻占据宏体素合并成盒，再做轴向阴影延伸。
- 允许 4、8、16 方块遮挡粒度；但**最终保护方块的保留仍以精确方块级规则执行**。
- 如果阻挡物极多（例如高密度人工城墙），可以动态切换到按切片位图、固定预算射线或低质量近似，防止组合阴影规模爆炸。

### 7.5 传播与修改时序

**遮挡必须基于爆炸开始时的逻辑世界状态，而不是一边清空一边重新读取已清空的墙体。** 否则执行顺序会改变结果。

对于大范围不能一次性复制世界快照的情况：

- 规划阶段只提取必要的阻挡体/掩码以及版本信息。
- 正在规划的范围应按一致性协议读取，允许对变更过的 Section 重新规划。
- 最终清除掩码按操作 ID 固定；对新的玩家放置/认领变化，再做提交前保护检查。
- 不能把“六轴阴影近似”宣传为能量守恒或原版射线等效。

### 7.6 多爆心与合并

无传播时，纯几何删除的候选区域可以并集去重。启用遮挡时，每个爆心要分别求其暴露范围与阴影，之后再做候选区域并集；不能先合并成一个大球再推断遮挡。

---

## 8. 大规模写入：Section、方块实体、光照与网络

### 8.1 每个 Section 四种提交路径

- `NOOP`：球外、全空气、无可破坏方块、整个区域被领地/阴影保护。
- `WHOLE_AIR`：全覆盖且经保护策略证明可以整 Section 清空。
- `MASKED_CLEAR`：球边界、保护方块、阴影边界或动态规则导致的部分清除。
- `GENERATION_FALLBACK`：尚未生成且无法安全走空 Chunk 路径。

### 8.2 整 Section 清空的前提

不能把 Palette 的数组直接清零或假设 Palette 索引 0 永远等于空气。快速路径必须通过适配层保持：

- Palette 和计数（非空气、流体、随机 Tick 等）正确。
- 旧方块实体撤销注册，清理旧 Ticker/能力缓存；必要时保留 Mod 扩展数据。
- Block/Fluid tick、POI、结构及其他世界附属状态一致。
- 对需要邻接通知的特殊方块，采用兼容性慢路径或专门收尾逻辑。
- 标记 Chunk 已修改，必要时触发存档。

### 8.3 高度图

- 使用 16×16 = 256 列的位图表示变更列。
- 对同一列批量修改后，**仅在最后**更新相关高度图，不要在每次空气写入后更新。
- 如果整个 Chunk 是合法空地形，可使用空地形对应的高度图初始化；如果保留底部基岩层，必须重新计算顶部高度。
- 各版本的高度图类型与行为由适配层处理。

### 8.4 光照

- 收集真正需要重算的 Section、方块发光变化、天空光阻挡变化。
- 区分“Section 从非空变成全空”和“某一方块光照属性变化”，两种事件不要混为一谈。
- 支持 `DEFERRED_CORRECT`（优先推荐）、`IMMEDIATE`、`FAST_VISUAL_UNSAFE`（仅测试用），明确最后一档不保证即时正确视觉。
- 禁止伪造 light completed 标志；光照队列未完成前不应宣告该 Chunk 已具备完整正确的可见状态。

### 8.5 客户端同步

- 先在内存中合并同 Section 的变更，再决定 Section 变化包还是整 Chunk 包。
- 阈值基于**实际序列化字节、服务端 CPU 与玩家数量**校准，而不是只看修改方块个数。
- 原版 `sendBlockUpdated` 可能已经合并通知；优化要测量 CPU 与包体，不能把调用次数等同于网络包数。
- 给每 Tick 网络同步设置独立预算，避免爆炸完成后一次性发出大量 Chunk 包。
- 对无人追踪的 Chunk 不需要立即广播，但必须保存最新状态。

---

## 9. 实体、掉落物、流体与世界副作用

### 9.1 实体

- 实体伤害与方块删除分开执行；不以一次 Chunk 清除完成作为杀实体依据。
- 对球形爆炸先做 AABB 粗筛，再用真实球形距离判定，不应杀死球外立方体角落的实体。
- 尊重创造模式、无敌状态、权限/队伍以及 Mod 特有实体规则。
- FTB 的**方块破坏权限**和**实体伤害规则**不能简单视为同一个布尔值。
- 只处理服务器中实际存在/已加载的实体；不应为了“杀死”尚未生成 Chunk 的潜在生物而触发世界生成。

### 9.2 方块掉落

超大规模清除默认 `DROP_NONE`，否则海量掉落实体本身会击穿性能。可选 `DROP_AGGREGATED` 汇总为特殊收集结果，或小范围兼容原版掉落；这些模式必须单独测性能。

### 9.3 流体、红石、重力与邻接通知

直接改存储会绕过大量正常 Block 更新。需要明确：

- 是否允许保留物理一致性（如沙子坠落、水流更新、红石断线、连锁 TNT）；
- 是否对爆炸边界进行有限补偿更新；
- 是否保护邻近已认领的 Chunk 不受二次流体/火焰影响；
- 是否允许完全省略大面积内部的无意义邻接通知。

推荐 `PHYSICS_BOUNDARY_ONLY`：只对爆炸区域边界和残留的特殊方块做必要补偿，内部已全空气无需逐格通知。复杂 Mod 行为无法安全批处理时退回兼容路径。

### 9.4 其他状态

- POI（村民工作站等）、方块实体关联与票据。
- scheduled block/fluid ticks、传送门/折跃门方块实体。
- 生物群系、结构引用、生成版本、气候系统和 Mod 自定义 Chunk Attachment。
- 领地保护与其他事件回调的兼容契约。

---

## 10. 调度器、并发和吞吐控制

### 10.1 全局预算而非每个爆炸独立固定块数

每个爆炸任务独立声明“每 Tick 处理 50,000 方块”不能保证 TPS；多个爆炸会叠加，几何分类、Chunk 生成和光照成本也不随方块数线性变化。

改为服务器/维度级统一调度：

- `cpuBudgetMsPerTick`：服务器主线程实际允许消耗的时间。
- `maxInFlightChunkLoads`：同时读取/生成的 Chunk 数。
- `maxInFlightSaves`：同时等待保存的 Chunk 数。
- `lightWorkBudget`：光照更新积压限制。
- `packetBytesPerTick`：网络广播预算。
- `maxPendingBytes`：待提交掩码、缓存、Chunk 的内存上限。
- 通过观测的 MSPT 调节吞吐，但保持硬上限以避免饥饿。

### 10.2 线程安全

- 纯几何分类、空间索引与不含世界可变对象的数据可多线程。
- 从世界读取 Palette/BE 或写活跃 Chunk 时，只能采用适配层允许的线程模型与快照策略。
- 不允许让工作线程直接并发修改普通活跃 `LevelChunkSection`。
- 版本适配层显式区分 `planOffThread()` 与 `commitOnWorldThread()`。

### 10.3 局部性与内存

- 优先将任务按 Region、Chunk 分组，避免反复装载同一 Chunk。
- 规划数据使用流式任务窗口，而非保存整个半径 2000 球体的全部 `BlockPos`。
- Section 掩码使用稀疏/稠密自适应表示。
- 合并相交的幂等几何清除，但**不合并时序敏感的传播和权限判断**。
- 已在本操作中清除的 Section 记住状态，避免重复网络和保存工作。

### 10.4 可视化进度

进度指标至少分开显示：规划、世界生成、写入、光照、保存。不要用球半径占比冒充实际完成进度。一个 2000 格球，前 25 格的“实心球”在体积上只占极小比例。

---

## 11. 任务日志、重启恢复与确定性

### 11.1 核心约束

对世界数据的修改是长事务，不可能保证整个几十万 Section 的操作原子完成。必须让每个 Chunk 的提交**幂等、可恢复、可追踪**。

操作标识：`operationId`、维度、几何参数、开始时策略快照、申请权限主体、创建时间、传播模式、规则版本。

### 11.2 推荐提交协议

1. 在执行任何修改前，持久化操作意图。
2. 对 Chunk 申请受控的修改权/排队顺序，取得一致的规划依据。
3. 在内存中计算新 Chunk 状态和该 Chunk 的 `lastAppliedOperationId` 或操作完成标记。
4. 使用版本适配层支持的**同一 Chunk 保存结果**持久化修改和完成标记；不能先单独记“已完成”再保存旧世界数据。
5. 重启后重新扫描未完成任务；如果保存的 Chunk 已带对应完成标记，则跳过，否则重新应用仍有效的任务。
6. 日志全部提交、必要的光照/附属数据补偿完成后才清理。

### 11.3 防止旧任务毁掉新建筑

即使操作本身是“设为空气”（重复执行看似幂等），也不能在任务完成后又重放同一老爆炸，否则会清除爆炸完成后新建的房子。

- `operationId` 的应用标记应与 Chunk 状态协同保存。
- 正在执行的 Chunk 可临时串行化写入顺序，以定义“先爆炸还是先玩家建造”。
- 崩溃恢复时若无法证明 Chunk 与完成标记一致，应保守暂停/修复，不能冒险覆盖新编辑。
- FTB 权限变化属于新的外部状态；恢复时重新判断是否仍可执行剩余操作。

### 11.4 确定性

同一初始世界快照 + 同一参数与保护/遮挡策略 ⇒ 结果应与 Chunk 访问顺序、线程调度无关。几何采用稳定的整数规则；传播基于冻结的阻挡事实或版本化快照。

---

## 12. 建议算法库 API（伪接口，仅表达契约）

独立库应只使用自己的基础类型（整数坐标、AABB、BitMask、DimensionKey 的不透明标识等），不要在核心暴露 `net.minecraft`、NeoForge 或 FTB 类型。

```java
// 伪接口；名称与层次是全新设计，不要求照此实现。
enum Coverage { OUTSIDE, INSIDE, PARTIAL }
enum Safety { SAFE, REQUIRES_GENERATION, UNKNOWN }
enum WriteKind { NOOP, WHOLE_AIR, MASKED_CLEAR, GENERATION_FALLBACK }
enum Propagation { NONE, AXIS_6, AXIS_26, RAYCAST_REFINED }

interface Volume {
    Coverage classify(IntBox box);
    void emitRanges(IntBox box, RangeSink sink);
}

interface ProtectionOracle {
    ProtectionSummary summarizeSection(SectionSnapshot section);
    boolean mayDestroy(VoxelSnapshot voxel, OperationContext context);
    boolean blocksPropagation(VoxelSnapshot voxel, OperationContext context);
}

interface ClaimOracle {
    ChunkPermission query(DimensionId dimension, int chunkX, int chunkZ,
                          OperationContext context);
}

interface GenerationOracle {
    Safety canMaterializeEmpty(DimensionId dimension, ChunkBox chunk,
                               OperationContext context);
}

interface WorldCommitBackend {
    ChunkSnapshot readExistingOrGenerate(ChunkKey key, GenerationPolicy policy);
    CommitResult commit(ChunkPlan plan, OperationId operationId);
}
```

### 12.1 模块建议

```text
vroe-core/
  geometry/          # 球、盒、圆柱、三态分类、行段与掩码
  spatial/           # Region/Chunk/Section 索引
  propagation/       # NONE、6轴、26轴、按需精修
  planner/           # Chunk 快路径判定、保护和遮挡计划
  journal/           # 操作ID、持久化意图、恢复协议
  scheduler/         # 全局预算、任务排序、积压控制
  api/               # 与 Minecraft 无关的接口

vroe-adapter-mc1211-neoforge/
  world/             # Chunk 安全访问与写入
  generation/        # 合法空 Chunk 物化或回退
  lighting/          # 高度图、光照、网络收尾
  protection/        # 原版不可破坏规则、Mod Hook

vroe-integration-ftbchunks/
  # 可选依赖：按目标版本 FTB API 实现 ClaimOracle

vroe-qcc-bridge/      # 可选：作为外部业务适配插件，纯接口调用
benchmarks/
tests/
```

建议默认采用 Java 独立库：减少与 Minecraft JVM 之间的数据复制与 JNI/FFM 成本。Rust/native 仅在基准确认几何内核确实成为瓶颈时再评估。

### 12.2 主规划算法（原创伪代码）

```text
planOperation(volume, rules, world):
    persistIntent(operation)
    for region in regionsIntersecting(volume):
        for chunk in region.chunksIntersecting(volume):
            if claimOracle.deniesMutation(chunk):
                markProtectedOrSkip(chunk)
                continue

            state = world.inspectChunkExistenceWithoutGenerating(chunk)
            coverage = volume.classify(chunk.bounds)
            if coverage == OUTSIDE:
                continue

            occlusion = propagationPlanner.exposure(chunk)
            if occlusion == FULL_SHADOW:
                continue

            if state == NEVER_GENERATED and coverage == INSIDE:
                safety = generationOracle.canMaterializeEmpty(chunk)
                if safety == SAFE and occlusion == FULL_EXPOSURE:
                    enqueue(MaterializeSafeEmptyChunk(chunk))
                    continue

            enqueue(ProcessNormalChunk(chunk))

processNormalChunk(chunk):
    chunk = loadOrGenerateWithSupportedLifecycle(chunk)
    for section in chunk.sections:
        if outsideVolume(section) or fullShadow(section) or alreadyAir(section):
            continue
        if fullyCovered(section) and allStatesProvablyDestroyable(section):
            enqueue(WholeSectionClear(section))
        else:
            ranges = volume.emitRanges(section)
            mask = ranges - protectedVoxelMask - occlusionMask
            enqueue(MaskedSectionClear(section, mask))

commitChunk(chunkPlan):
    revalidateClaimAndProtectionVersions(chunkPlan)
    mutateUsingVersionSafeBackend(chunkPlan)
    aggregateHeightmapLightPoiPhysicsAndPacketUpdates()
    persistChunkStateAndAppliedMarkerTogether()
```

> 注意：上述伪代码表达**需求和顺序约束**，不构成第三方项目源码移植。实际异步实现需要考虑正在生成的 Chunk、未知遮挡、世界存档竞争和任务取消；不能把伪代码直接照抄为可运行 Mod。

---

## 13. 建议配置（示意，不是既有 Mod 配置）

```toml
[explosion]
geometry = "SPHERE"
progression = "CENTER_OUT"
block_drops = "NONE"
physics = "BOUNDARY_ONLY"
entity_damage = "DISTANCE_AWARE"

[protection]
protect_unbreakable = true
protected_block_tags = []
protected_block_ids = []
protected_structure_envelope = false
unknown_dynamic_rule = "FALLBACK"

[claims]
provider = "AUTO"
mode = "RESPECT_CLAIM_SETTINGS"   # PROTECT_ALL_CLAIMED / BYPASS_CLAIMS
claimed_chunks_block_propagation = false
protect_entities_in_claims = true
bypass_requires_server_authorization = true

[propagation]
mode = "NONE"                    # AXIS_6 / AXIS_26 / RAYCAST_REFINED
occlusion_macro_voxel_size = 4   # 4 / 8 / 16
unknown_obstacle_policy = "GENERATE_OR_DEFER"

[generation]
safe_empty_chunk_materialization = true
unknown_generator_policy = "NORMAL_GENERATION"
known_structure_policy = "NORMAL_GENERATION"
verify_cross_chunk_features = true
no_overwrite_existing_chunks = true

[performance]
cpu_budget_ms_per_tick = 4.0
max_in_flight_chunk_loads = 8
max_in_flight_saves = 8
max_pending_memory_mib = 256
packet_budget_kib_per_tick = 1024
adaptive_mspt_budget = true

[updates]
light = "DEFERRED_CORRECT"
heightmaps = "BATCHED"
client_sync = "COALESCED"

[recovery]
enabled = true
resume_incomplete_on_restart = true
persist_chunk_operation_markers = true
```

这些数值仅是**初始调参占位值，不代表测得的最佳值**。特别是 Chunk 并发量、MSPT 预算和网络预算要依赖服务器硬件、玩家数及存档介质实测调整。

---

## 14. 完整测试计划

### 14.1 正确性（优先）

- 球体中心、负坐标、半开区间、边界刚好切到 Section/Chunk、极大半径、世界上下界。
- 球外不删除；全覆盖不留缝；相邻批次无重复遗漏。
- 基岩、末地门框架、已激活门方块、末地折跃门及对应方块实体。
- 原版世界、下界、末地；自定义高度和 Mod 地形生成器。
- 跨 Chunk 结构的起点在边界外，组件实际进入爆炸区。
- FTB：未认领、认领且允许爆炸、认领且拒绝、管理员绕过、爆炸过程中新认领/取消认领。
- 领地仅保护直接方块但不遮挡时，其他方向的区域仍可正确删除。
- 6 轴/26 轴遮挡，阻挡物恰在对角方向、长墙、多层遮挡、密集阻挡体。
- 连续两次爆炸、并发十次重叠爆炸、正在操作时玩家放置受保护机器。
- Chunk 从未生成、已生成未加载、正在生成、已加载但没有玩家追踪。
- 生成后的相邻 FEATURES、结构跨边界放置、服务器重启后重新进入。
- 方块实体、POI、Fluid/Block Tick、光照、高度图、网络客户端同步。
- 世界保存期间强制结束进程；重启恢复；任务中途取消和继续；确认不重复删除之后的新建筑。

### 14.2 性能实验设计

使用相同的世界快照，每组至少重复多次并记录分布（中位数、P95、最差 Tick），避免偶然的 Chunk 缓存、JIT 和磁盘缓存干扰。

**主测试矩阵**：

| 变量 | 代表场景 |
|---|---|
| 爆炸大小 | R=64、200、800、2000 |
| 世界状态 | 已完整预生成 / 部分生成 / 完全未探索 |
| 世界内容 | 空气、平原、群山、洞穴、建筑密集区 |
| 保护 | 关闭 / 原版保护 / FTB 保护 / 大量 Mod 方块 |
| 遮挡 | NONE / AXIS_6 / AXIS_26 / 精修 |
| 更新 | 正确光照 / 延迟正确光照 / 仅测试跳过光照 |
| 任务 | 单爆炸 / 大量重叠爆炸 |

指标：

- 总挂钟时间、CPU 时间；生成/读取/保存时间占比。
- TPS 与主线程 MSPT（均值/P95/P99/最大值）。
- 每秒完成的 Chunk、Section、实际删除方块数量。
- 创建、释放对象数、GC 时间与峰值 RSS/Heap。
- 传输字节、广播包数、在线客户端卡顿与区块更新延迟。
- 磁盘写入量、Region 文件增长量、空 Chunk 实际存储开销。
- “保护正确率/误伤数/漏清除数”和任务崩溃恢复时间。

必须把“仅生成计划”“仅清除已加载 Chunk”“触发新世界生成”“持久化与光照收尾”分别计时。

### 14.3 规模估算：半径 2000 并不意味着真正要写遍全部方块

以主世界建筑高度约 384 格、爆心处于常规高度作为示例：半径 2000 的球与世界高度截取后的候选体素数量约 **48 亿**（取决于爆心 Y 与世界高度）。如果某实现每 Tick 只按坐标处理 50,000 格，在稳定 20 TPS、忽略所有其他开销的理想情况下，仅处理这些坐标就需要约 **80 分钟**。这只是量级示例，不是任何项目的实测。

同一个球的水平投影约涉及 `π × (2000/16)^2 ≈ 4.9 万` 个 Chunk。改成 Section 级分类之后，需要判定的 Section 数量约是百万量级，但**真正需要清除或正常生成的数量可能显著更少**；保护、空气跳过、阴影和世界上下界都会影响实际任务数。

即便空 Chunk 极易压缩，如果将约 4.9 万个 Chunk 都预物化并以常规 Anvil Region 格式持久化，4 KiB 扇区粒度也意味着约 **190 MiB** 的量级下限（再加文件头、元数据与实际序列化开销）。因此空 Chunk 快速路径解决了世界生成 CPU 成本，却没有消除 Chunk 生命周期、写盘和文件系统 I/O 成本。

### 14.4 基线和验收

- Baseline A：正确、兼容的普通逐方块清除路径。
- Baseline B：常规按 Chunk/Section 批处理，但不走空 Chunk 物化。
- Candidate：分层几何 + 保护 + 安全空 Chunk + 可选遮挡。

**验收优先级**：零误删受保护方块/领地、零 Chunk 损坏、可恢复 > TPS 可控 > 总耗时最短。禁止以跳过保护、光照和 BE 清理换取虚假的性能领先。

---

## 15. 分阶段实现建议

### Phase 1 — 安全的 Section 写入基线

- 实现几何 AABB 三态分类、Range Sink、Section 级掩码。
- 全空气跳过、已加载 Chunk 批量清除、Palette 保护、原版特殊方块规则。
- 正确处理方块实体、高度图、光照、POI、网络同步。
- 增加 FTB 领地适配与服务端权限控制。
- 配置全局 CPU 预算、任务日志与崩溃恢复。

**完成标准**：在已预生成世界里，精确性与保护行为可靠，再谈吞吐量。

### Phase 2 — 未生成世界快速路径

- 区分 Chunk 未加载/未生成，禁止隐式生成的状态查询。
- 结构规划/特殊地形判定 + 三态安全证明。
- 优先完成已知结构正常生成的兼容回退。
- 实现合法全空气 Chunk 物化，正确处理生成生命周期和跨边界写入。
- 加入 Region 优先的 I/O 队列及批量保存调度。

**完成标准**：新世界爆炸在确保没有破坏结构/Mod 数据的前提下，明显减少生成开销。

### Phase 3 — 近似冲击波与并发优化

- 稀疏阻挡物索引、AXIS_6、64 位宏体素掩码。
- 可选 AXIS_26 和边界精修。
- 多爆炸重复工作消除、动态粒度选择、保护与几何联合索引。
- 复杂 Mod 生成器、跨版本专用适配。

**完成标准**：结果可预测、与任务顺序无关、不会因为大量阻挡物导致服务器不可控卡顿。

---

## 16. 尚未冻结的设计问题

1. **爆炸对“不可破坏”的具体定义**：以硬编码原版标签、抗爆阈值、`destroySpeed < 0` 或外部策略为主？建议组合策略，默认显式保护列表，并允许每个 Mod 扩展。
2. **结构保留粒度**：只保留保护方块，还是额外保护传送门周围框架/房间？默认只保留方块，完整性保护可选。
3. **FTB 领地是否阻挡冲击波**：默认不阻挡，避免形成异常巨大的立方体阴影。
4. **6 轴阴影的遮挡粒度**：宏体素 4、8、16；视觉与 CPU 的最佳折中需要测。
5. **精确几何的体素语义**：方块中心在球内 vs 方块 AABB 与球有交集，必须定一个作为协议。
6. **空 Chunk 的生成器保证**：哪些原版或第三方世界生成器可以提供可信的安全证明？默认未知回退。
7. **大规模操作与玩家实时修改**：需要 Chunk 顺序写入语义、提交前权限验证、版本冲突重规划。
8. **照明与 POI 延迟多久算完成**：进度条是否在玩家可见状态一致后才显示 100%。
9. **是否允许主线程之外构造未发布的 Chunk 数据**：必须以每个目标版本的原生生命周期测试为准，不能假定可随意异步写 Region。
10. **多爆炸合并**：独立几何集合可以合并；带传播、实体与时序效应的操作须保持语义。

---

## 17. 许可证、版本和独立实现边界

### 17.1 GPL 不随抽象算法自动传播

版权一般保护具体代码表达，不保护算法思想、数学关系、方法本身。**自行编写实现并不因为采用同一几何思路就自动受原项目 GPL 约束**。但实质复制代码、非必要的独特组织表达、由受版权保护代码改造而来的衍生实现，仍可能涉及版权许可；若还存在专利/商标问题需另行判断。

因此本方案要求：

- 独立写需求、数学定义、接口契约和测试用例。
- 不复制 QCC 的源码、源文件结构、长段注释或具体实现。
- 根据这里的接口与可观察行为，独立编写算法与 Minecraft 适配器。
- 与 FTB Chunks 的联动通过其公开 API；不复制其内部实现。
- 项目最终许可由**独立实现的版权持有人**决定，发布前仍应检查所链接/打包依赖的实际许可证义务。

这是一项一般性技术/版权风险说明，不是司法辖区特定的法律意见。

### 17.2 版本说明

作为背景核对：相关 Mod 当前公开仓库同时有 `1.21.1` 和 `26.1.2` 两条分支；其中 `1.21.1` 分支在 `gradle.properties` 中标记 Minecraft `1.21.1`、NeoForge `21.1.238`、Mod `1.3.5`；`26.1.2` 分支标记 Minecraft `26.1.2`、NeoForge `26.1.2.71`、Mod `1.3.4`。**本设计不从这些源码移植实现，也不与其分支 API 耦合。**

### 17.3 参考资料（用于兼容与法律事实核对，不作为代码模板）

- 美国版权局《Computer Programs》：https://www.copyright.gov/register/tx-programs.html
- 美国版权局《What Does Copyright Protect?》：https://www.copyright.gov/help/faq/faq-protect.html
- GNU GPL 官方 FAQ：https://www.gnu.org/licenses/gpl-faq.en.html
- FTB Chunks 公开 API：https://github.com/FTBTeam/FTB-Chunks/tree/main/common/src/main/java/dev/ftb/mods/ftbchunks/api
- FTB Chunks NeoForge 爆炸事件兼容代码（仅确认需要主动检查领地保护）：https://github.com/FTBTeam/FTB-Chunks/blob/main/neoforge/src/main/java/dev/ftb/mods/ftbchunks/neoforge/NeoEventListeners.java
- QCC 仅用于版本和需求背景核对：https://github.com/wanggugu197/Quantum-Chromodynamic-Charge

---

## 18. 一页总结：首版的决策树

```text
收到区域爆炸任务
    │
    ├─ 参数合法？权限足够？否则拒绝
    ├─ 持久化任务意图
    ├─ 几何粗筛：Region → Chunk
    │
    ├─ Chunk 不相交 → 跳过
    ├─ FTB/保护插件禁止破坏 → 跳过（按配置可作为遮挡体）
    ├─ 传播遮挡完全覆盖 → 跳过
    │
    ├─ 已存在 Chunk？
    │     └─ 安全读取 → Section 分类 → 全清/部分清 → 收尾 → 保存
    │
    └─ 尚未生成 Chunk？
          ├─ 未完整覆盖 / 有已知结构 / 未知生成规则
          │     └─ 正常生成 → Section 级保护清除 → 收尾 → 保存
          └─ 全覆盖 + 传播已确定 + 所有保护条件可证明安全
                └─ 通过版本适配层物化合法的空 Chunk → 保存

每个 Chunk 提交前：复查权限、保护版本与世界状态；
每个 Chunk 提交后：记录同一存档状态下的操作完成标记；
所有任务结束后：等光照、数据一致性与必要存档完成，再报告完成。
```

**总之：真正决定吞吐量的，不是把每个方块破坏得更快，而是尽可能证明一大片空间完全不必逐块处理、不必完整生成，甚至不必加载。**

