# GTLCore / 其他宿主对接

本轮不修改或提交 GTLCore。以下是后续接入契约；可运行的 Forge 验证后端在本仓库 `tools/forge-test`，临时炸药和测试命令不应直接进入正式整合包。

## 打包方式

推荐与 CGSE 类似的独立 Java 子模块，但不提供可热替换/外挂引擎 mod。将主库的 `src/main/java/org/voxelregion`、LICENSE、NOTICE 和独立 Java 构建定义同步到宿主的 `vre-engine/`，或者固定依赖已验证的 Maven/JAR 版本。

```groovy
// settings.gradle
include ':vre-engine'

// host build.gradle，Architectury Loom 的 include 会将库内嵌
dependencies {
    implementation project(':vre-engine')
    include project(':vre-engine')
    forgeRuntimeLibrary project(':vre-engine') // Loom 的开发运行环境也必须能加载该库
}
```

保持该子模块独立 source set；不要让两个模块同时拥有宿主 `src/main/java`。主库中不放 Minecraft mixin、注册事件或 FTB 类。协议和算法版本固定随宿主发布，不要求玩家额外安装 VoxelRegionEngine 核心。

## 宿主需要负责的内容

| 接口/责任 | 宿主处理 |
|---|---|
| 触发源 | TNT、机器、指令等转换为不可变操作参数；记录申请主体 |
| 权限 | 每个受影响 Chunk 提交前检查；绕过领地必须由服务端授权 |
| Chunk 读取 | 区分未加载与未生成；避免隐式生成探测；控制 region tickets |
| 快照 | 世界线程读取，复制稳定状态 ID；保留 revision / 规则 revision |
| 原生写入 | 方块实体、POI、高度图、光照、tick、网络、邻接策略 |
| 完成证据 | 多 operation receipts 与 Chunk 状态同存；不要只记任务游标 |
| 生命周期 | 取消、停服、切换维度、失败时释放加载票据；不假定后台对象永久有效 |
| 可观测性 | 计划量、实际删除量、加载/写入/保存/光照状态分别报告 |

世界线程读取或写入没有因为“在库里调用”就变成线程安全。纯几何和不可变快照计划可放工作线程；`LevelChunk` 和 `BlockEntity` 等可变对象不能跨线程直接使用。

`clearFluids` 默认 true，保存为任务不可变参数。关闭时，在快照中额外捕获每格流体的方块状态 ID，并提交计划的 `retainedFluidWrites`；不能只处理 `clearMask`。保留纯流体和含水固体残留水时禁止直接使用 WHOLE_AIR。缺失流体快照应拒绝保留模式，不能猜测该区段没有流体。

## 从测试后端迁移

先保留原生安全写入基线并在真实 GTL 世界验证，之后逐项引入整 Section 快速路径。仅当 palette 证明没有动态回调、BE、POI、受保护方块等时才考虑直接批量提交；仍要修正计数、各类高度图、天空光源、光照队列和网络。

`Heightmap.primeHeightmaps` 不是清空旧高度图的接口；对已经有高度图的全空列，直接重新 prime 可能保留旧值。类似的细节必须针对实际 Minecraft/Forge 版本验证。

未生成 Chunk 先走正常生命周期。设计文档的 SAFE_EMPTY 是扩展点，不是当前已支持功能；禁止手写 `Status=full` 或用 `EmptyLevelChunk` 冒充生成完成。

## 上游与许可

本次核对的 GTLCore 上游为 `GNSW/GTLCore` 的 `gtl-1431-skyblock`，提交 `d014e9c3`；已有工作基线 `88ddb00e` 包含它及最新 CGSE 同步。为后续接入准备的本地分支是 `feat/voxel-region-engine`，本轮没有在该分支落入库实现。

库代码使用 MPL-2.0；复制或修改覆盖文件时保留许可证与文件头。不要把测试存档、玩家证据或本地运行日志打包进正式发行物。
