# GTLCore / 其他宿主对接

GTLCore 嵌入算法库与适配库，通过 `ForgeRegionEngine` 提交任务，具体同步和构建见 [GTLCore 接入](gtlcore-integration.md)。临时炸药和 `/vre` 命令由测试 mod 注册，不进入适配库。通用示例见 [Forge 打包与接入](forge-embedding.md)。

## 打包方式

推荐与 CGSE 类似的独立 Java 子模块，但不提供可热替换/外挂引擎 mod。将主库的 `src/main/java/org/voxelregion`、LICENSE、NOTICE 和独立 Java 构建定义同步到宿主的 `vre-engine/`，或者固定依赖已验证的 Maven/JAR 版本。

```groovy
// settings.gradle
include ':vre-engine'

// host build.gradle，Architectury Loom 的 include 会将库内嵌
dependencies {
    implementation project(':vre-engine')
    forgeRuntimeLibrary project(':vre-engine') // Loom 的开发运行环境也必须能加载该库
}
```

保持该子模块独立 source set；不要让两个模块同时拥有宿主 `src/main/java`。主库中不放 Minecraft mixin、注册事件或 FTB 类。协议和算法版本固定随宿主发布，不要求玩家额外安装 VoxelRegionEngine 核心。

正式 JarJar 打包必须使用固定标识 `org.voxelregion:voxel-region-engine` 和 `org.voxelregion:voxel-region-forge-adapter-1.20.1`；不要让 Gradle 子项目别名 `engine`/`vre-engine` 成为不同库标识，否则与临时测试 mod 同时安装会重复选择同一组类。参考测试项目的 `forgeNestedJars` 元数据构建或 GTLCore 的 `vre-packaging.gradle`，两个入口必须一致。

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

## 使用已经实现的 Forge 后端

适配库已经包含 Palette 证明、整区段替换、区块内批量收尾和原生精确写入路径。接入宿主时注册经过审查的聚合观察器；无法确认宿主对裸区段替换的兼容性时，使用原生精确路径。不能仅为打开快速路径而空注册一个观察器：需要核对本宿主和其他 Mod 的方块变更缓存、结构维护与回调。

`Heightmap.primeHeightmaps` 不是清空旧高度图的接口；对已经有高度图的全空列，直接重新 prime 可能保留旧值。类似的细节必须针对实际 Minecraft/Forge 版本验证。

未生成区块沿原生生命周期推进。已实现的生成快捷路径仅接受通过证明的原版平坦生成器，在原生 NOISE 阶段保留受保护层、跳过可删层；其余生成状态、光照和 FULL 发布仍由 Minecraft 完成。未知生成器、结构或特征仍走正常生成。禁止手写 `Status=full` 或用 `EmptyLevelChunk` 冒充生成完成。

## 上游与许可

GTLCore 工作分支 `feat/voxel-region-engine` 已先快进合并 `GNSW/GTLCore` 的 `gtl-1431-skyblock` 上游 `04ad4b07`，再接入 VRE。保留宿主上游版本号。

库代码使用 MPL-2.0；复制或修改覆盖文件时保留许可证与文件头。不要把测试存档、玩家证据或本地运行日志打包进正式发行物。
