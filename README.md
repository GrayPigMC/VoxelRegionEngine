# VoxelRegionEngine

Forge 集成的全局光照组件单独沿用 LGPL-3.0，源码在 `third_party/starlight-forge/`；VRE 自有算法保持 MPL-2.0。接管范围、存档格式和光影/泛光验证边界见 [全局光照说明](docs/global-lighting.md)。

Java 17 的体素区域操作库，采用 MPL-2.0。面向大范围爆炸、开挖和区域清除；算法核心不依赖 Minecraft、Forge、FTB 或 GTLCore。当前版本为 0.1.0，接口处于初始开发期。

仓库包含独立算法库、Forge 1.20.1 适配器和临时测试 mod。运行时已经接入批量区段写入、冻结遮挡、分阶段推进、原生保存确认与基于证据的恢复；这些实现说明不代替最终制品的回归和生产验收。原始需求见 [DESIGN-v0.1.md](DESIGN-v0.1.md)，接口与边界见下列文档。

## 功能

- 球、盒、竖直圆柱及并集、交集、差集；精确体素中心判定、三态分类、连续 Y 行段和不可变 Section 掩码。
- Region → Chunk 惰性遍历、负坐标及世界高度裁剪、可持久化游标。默认从中心向外按区块距离推进，准备完整区块再一次提交；可选侧重存储局部性的顺序。
- 几何、逐格保护、传播阻挡和领地权限分别判断。`clearFluids` 默认开启；关闭时保留纯流体，并在删除可破坏的含水固体后留下其流体。
- `NONE`、`AXIS_6`、`AXIS_26`、`RAYCAST_REFINED` 传播模式；后三种先捕获一致的原始阻挡快照、持久化校验，再由独立工作线程编译索引。
- Forge 区段经过 Palette、保护、修订号和宿主观察器审核后使用批量路径；混合保护和几何边界使用离线替换掩码，未知状态保留必要的原生回调。
- 跨任务、跨维度的协作预算和背压；独立光照、存储、网络确认；完整服务端 tick 计时。
- 独立范围实体 API：默认 `DISCARD`，包含玩家与未加载的已保存实体，覆盖矿车、船、掉落物等非生物实体；支持精确连续几何选择、取消及进度查询。玩家保留原生死亡流程，容器实体直接移除前清空内容物，扫描实体不申请地形生成。
- 持久意图、冻结场文件、阶段检查点与同 Chunk NBT 保存的 receipts。恢复跳过已完成阶段，只在剩余游标与修改修订号证据一致时继续。

## 执行边界

新任务默认 `generate=true`、传播 `NONE`，按有界窗口处理未加载和未生成区块。显式关闭生成仍读取已存盘的 FULL 区块，仅排除需要生成的区块；旧格式任务保留旧行为。安全生成快捷路径限于能够证明的原版平坦配置；未知生成器或 Mod 钩子走原生流程，不伪造 FULL Chunk 或直接写 `.mca`。

开启遮挡需要先读完整范围。缺失区块、失效的变更跟踪、捕获期间修改或额度不足不能当成透明空间。无法取得安全快照或恢复证据时暂停。

批量写入依赖 `HostIntegration` 的相关观察器审核，不因无关模组而全局关闭。不能用空 observer 声称覆盖了机器、方块监听器或网格缓存。原版静态地形和水已纳入证明；动态保护、方块实体、岩浆、含水固体和特殊状态保留原生处理。默认物理模式为 `NONE`，另有 `BOUNDARY_ONLY` 和 `COMPATIBLE`；后两者允许原生间接物理效应，并不构成区域外效果隔离。

`WRITTEN` 包含必要的光照、地形/POI 保存和网络提交确认；网络提交不等于远程客户端已经收到或渲染。取消不回滚既有写入。缺少证据的旧任务不能重新清除后来新建的方块。

几何使用半格定点输入：中心和半径必须是 0.5 的整数倍，内部放大两倍后使用 `long` 距离运算；不默默舍入任意小数。方块空间统一按方块中心取样，盒范围使用半开区间。

## 构建与制品

```text
./gradlew build
./gradlew regression
./gradlew geometryBenchmark
./gradlew publishToMavenLocal
```

Windows 使用 `gradlew.bat`，需要 JDK 17。主库无第三方运行依赖；测试为可独立运行的 Java 程序。

| 制品 | 用途 |
|---|---|
| `voxel-region-engine-0.1.0.jar` | 纯 Java 算法库，主项目 `build/libs/` |
| `voxel-region-forge-adapter-1.20.1-0.1.0.jar` | 无 `@Mod` 的 Forge 库，公开 `ForgeRegionEngine`，位于 `tools/forge-test/build/libs/` |
| `vre-starlight-forge-1.20.1-1.1.2-vre.jar` | LGPL 全局光照组件，位于 `tools/forge-test/build/intermediates/vre/` |
| `voxel-region-forge-test-1.20.1-0.1.0.jar` | 可调炸药与命令，内嵌 engine、adapter 和光照组件三份 JarJar 库 |

Forge 制品在 `tools/forge-test` 内执行 `../../gradlew adapterJar remapJar` 构建。独立库不注册测试物品或命令；宿主以内嵌依赖接入，无需要求玩家额外安装外挂核心。测试源码、测试结构、验证维度及本地世界不进入正常发布包。

## 文档与验证

- [独立 API 使用与参考文档（可单独转发）](docs/API_REFERENCE.zh-CN.md)
- [算法 API 与调用协议](docs/api.md)
- [Forge 公开 API、安装与嵌入](docs/forge-embedding.md)
- [范围实体杀死与清理 API](docs/entity-operations.md)
- [Forge 原生维护、权限与恢复契约](docs/forge-integration.md)
- [批量执行、推进顺序与性能口径](docs/high-performance.md)
- [设计覆盖与未完成项](docs/design-coverage.md)
- [测试 mod 的构建、命令与配置](tools/forge-test/README.md)
- [整区块执行与 GTLCore 实测记录](docs/validation-whole-chunk.md)
- [旧球壳制品的历史验证记录](docs/validation-0.1.0.md)
- [GTLCore 与其他宿主对接](docs/host-integration.md)
- [GTLCore 源码同步与嵌入](docs/gtlcore-integration.md)

性能测量使用完整 `tickServer` 时间。原生 `forge tps` 漏掉 Forge END 中的区域操作，不能独自证明本引擎的 MSPT。加载范围有限的 R2000 样本也不等于全 R2000 完成。最终结果须对应实际 jar、配置与测试记录；新增集成不能仅凭编译或先前版本测试宣称验收通过。

GTLCore 接入位于独立宿主工作分支，算法、对接文档和同步工具保留本仓库；玩家证据、运行日志和测试存档保留本地，不进入提交或上传。
