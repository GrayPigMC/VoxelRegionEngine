# v0.1 设计覆盖与限制

本表按原始 `DESIGN-v0.1.md` 核对实现路径。“已有代码”与“已经在目标整合包验证”分开；旧版球壳测试通过不能证明新的整区块路径或宿主集成通过。

| 设计项 | 实现路径 | 限制 |
|---|---|---|
| 球、盒、圆柱、组合、三态分类和连续行段 | `geometry`、`RegionGeometry` | 方块中心与半格定点语义；不是任意浮点形状 |
| 惰性遍历和中心优先 | `ChunkCursor`、`CenterOutChunkCursor` | 中心顺序按区块水平距离；不是逐格波面模拟 |
| 完整区段及混合保护/边界批量处理 | `PaletteSectionClearBackend.prepareSelected` | 静态证明状态离线改 Palette，未知方块保留原生回调 |
| 整个当前区块一起清理 | `ForgeOperationRunner.prepareWholeChunk` | 准备可跨帧；提交不可抢占；异常回调可能留下需要恢复检查的部分修改 |
| 未加载的已有区块 | 原生 `IOWorker`、受控 ticket 和 FULL 加载 | 禁止用空区块覆盖已存盘内容 |
| 未生成区块 | `GenerationGate` 和原生生成流水线 | 新请求默认开启生成，仍受窗口限制 |
| 可证明的空区块/保护地形快捷生成 | `ClearedGeneration`、`ClearedTerrainProfile` | 原生确认无存档记录；按基岩选项选择空地、平坦保护层或噪声基岩带；未知规则走普通生成；新区块群系默认海平面列采样，可选精确采样 |
| 保留与传播分离、领地保护 | 保护标签/上下文阻力、`FrozenOcclusion`、`ClaimChecks` | FTB 不可用时暂停，不假定允许 |
| 6 轴、26 轴、按需射线阴影 | `FrozenOcclusion`、`ProjectedShadows`、冻结索引 | 几何近似，不是流体冲击波；需要完整原始阻挡事实 |
| 高度图、生物群系、定时刻、POI/方块实体 | 批量维护 + 必要原生回调 | 不跳过未知方块的原生删除生命周期 |
| 外部光照求解和批量层安装 | `ClearedChunkLighting`、`ClearedChunkLightBatch` | 空气/不发光不透明残留的证明域；实际边界快照，失效则原生重算；不伪造完成标记 |
| 合并保存和客户端提交 | `NativeCommitFences`、`ParallelChunkCompression`、`ParallelRegionStores`、`ChunkPacketQueue` | 并行预压缩、不同 region 文件并行写入/同步、同文件保持顺序和批量持久化；发包确认不等于客户端已渲染 |
| 分帧、背压、全局预算 | 准备工作量、完整 tick 时间、票据/元数据/包预算 | 不是硬实时系统；未知原生回调和单次序列化不能中途抢占 |
| 意图日志、同镜像回执、恢复 | `IntentJournal`、`ChunkReceipts`、冻结场文件 | 证据不充分就暂停；取消不回滚 |
| GTLCore 嵌入 | `tools/sync_gtl_vre.py` 和宿主真实观察器 | 无需外挂核心；测试物品不嵌入 Core |

目前没有可支持“每 tick 完整生成、修改、照明并落盘 10,000 个区块”的实测结果，不能把几何候选数、排队数或空区块跳过数作为这个指标。性能验收需要分别记录实际修改提交数与完成全部收尾的区块数，同时记录完整 tick 时间和测试范围。

原设计的可选 `DROP_AGGREGATED`、不安全视觉光照档、任意第三方生成器快捷物化、不同操作的工作合并没有作为完整运行功能交付。默认使用不掉落、真实光照确认、受控原生生成和保持操作顺序的执行方式。
