# Forge 1.20.1 宿主接入

适配器面向 Java 17、Minecraft 1.20.1、Forge 47.3.7。公开入口是 `org.voxelregion.forge.ForgeRegionEngine`。纯算法库继续保持不依赖 Minecraft；宿主接入无需复制测试物品、命令或测试模组注册类。

## 制品与启动

| 制品 | 内容 |
|---|---|
| 纯 Java engine jar | 几何、规划、遮挡与预算等 Minecraft 无关类型 |
| `voxel-region-forge-adapter-1.20.1-0.1.0.jar` | Forge 公开 API、运行时与版本专用 mixin；`FMLModType=GAMELIBRARY`，没有 `@Mod` 注册 |
| `voxel-region-forge-test-1.20.1-0.1.0.jar` | 临时验证物品与命令，JarJar 内嵌上述两份库 |

在 `tools/forge-test` 执行 `../../gradlew adapterJar remapJar` 生成两个 Forge 制品。`adapterDevelopmentJar` 产生命名空间为 named 的开发制品；`adapterJar` 的类经过 SRG remap，用于标准 Forge 启动。宿主应通过自己的 Forge 构建工具提供正确的开发映射依赖，并将发布版 adapter 和 engine 作为 JarJar 库收纳。不要同时把独立 adapter、engine 和已经内嵌它们的测试模组重复打入宿主。

宿主 jar 的 `META-INF/MANIFEST.MF` 必须在启动阶段声明：

```text
MixinConfigs: voxelregion-forge.mixins.json,voxelregion-receipts.mixins.json
```

两个配置及 `voxelregion-forge.refmap.json` 位于 adapter jar 内。`install` 注册 Forge 事件和配置，不能在世界启动后补装 mixin。提交操作前会检查原生保存、光照和区块变更 hook；缺少 hook 时拒绝接受任务。

在宿主的 `@Mod` 构造阶段调用：

```java
ForgeRegionEngine.install(FMLJavaModLoadingContext.get().getModEventBus(),
        "my_host-voxelregion-server.toml");
```

调用是幂等的；同一 JVM 只安装一套事件处理器、预算调度器和配置。无文件名重载保留测试模组的 `voxelregion_test-server.toml` 名称。宿主若需要独立配置文件，第一次安装时传入自己的文件名。安装后由适配器处理服务端 tick 和停服清理，宿主不要重复调用内部 runner。

宿主不安装测试 mod 时，应在自己的资源包中提供 `data/voxelregion_test/tags/blocks/protected.json` 和 `blocks_propagation.json` 策略。测试 mod 的同名资源可作默认配置参考；不能把适配器库没有注册游戏内容理解成宿主标签策略已经自动配置。

## 提交、观察和控制

所有世界相关公开 API 必须在对应服务器线程执行。网络处理器应先转到服务器任务队列；宿主负责认证权限、操作来源与调用频率。`actor` 用于记录和实体伤害归属，不是管理员授权证明。公开入口不提供绕过领地保护的客户端开关。

```java
var handle = ForgeRegionEngine.submitSphere(level, actorUuid, centerBlockPos, 200,
        new ForgeRegionEngine.Options(20, false, true));
var status = ForgeRegionEngine.status(level, handle.id()).orElseThrow();
var paused = ForgeRegionEngine.control(level, handle.id(), ForgeRegionEngine.Control.PAUSE);
```

`Options(fuseTicks, generateMissingChunks, clearFluids)` 默认值为 `(0,false,true)`。球心是 `centerBlockPos` 的方块中心；半径和引信必须通过服务器配置限制。保护、物理、推进与传播策略由服务器配置选择并进入任务策略，预算由服务器全局共享。在默认 `NONE` 传播下，`generateMissingChunks=false` 记录并排除未加载区块，不因以后加载而补炸；它不保证整个球体全部清除。`clearFluids=false` 保留纯流体，并在删除允许破坏的含水固体后写回其流体状态。

通用几何使用 `submit`，不需要把盒或柱近似成球：

```java
RegionGeometry shape = new RegionGeometry.Combined(
        RegionGeometry.BooleanOperation.DIFFERENCE,
        new RegionGeometry.Box(new IntBox(-8, 64, -8, 8, 80, 8)),
        new RegionGeometry.Column(Point3.of(0.5, 0, 0.5), 8, 64, 80));
CompoundTag encoded = shape.save();
RegionGeometry restored = RegionGeometry.load(encoded);
var handle = ForgeRegionEngine.submit(level, actorUuid, restored,
        ForgeRegionEngine.Options.DEFAULT);
```

例中柱半径为 4 格，`radius2=8`。支持 `Ball`、`Box`、`Column` 和 `Combined(UNION/INTERSECTION/DIFFERENCE)`；几何 NBT 使用 schema 1，限制表达式深度与节点数，并拒绝未知类型或缺失字段。核心几何遵循半格输入和半开盒范围。接口不接受任意自定义 `Volume` 作为可恢复任务。

通用提交按包围盒派生执行中心及向上取整的包围半径，用于波面、额度和实体伤害衰减；精确选区仍由原几何决定。包围半径同样受服务器最大半径限制，不能只按盒边长估计是否会被接受。需要方块中心球语义时直接使用 `submitSphere`。

新 `CENTER_OUT` 任务明确先调度中心区块，首阶段为一格半径，再按默认 32 格宽的互不重叠壳层推进；非球体与壳层相交。`AS_FAST_AS_POSSIBLE` 使用单阶段并侧重存储局部性。旧任务缺少 `centralBootstrap` 字段时沿用旧解释；任务持久化的壳宽和阶段协议不会被当前配置替换。

提交返回 `Handle(dimension,id)` 前，适配器已经写入持久接受记录。提交可能抛出 `IOException` 或因边界、配额、缺少 hook 而拒绝；调用者不能把失败当成接受成功。`status` 和 `statuses` 返回不可变快照，不暴露 `Task`、SavedData 或可变游标。使用 `Status.asComponent()` 可取得独立的状态文本。

控制动作有 `PAUSE`、`RESUME`、`CANCEL`。`ControlResult.applied` 表示本次动作是否被接受，`reason` 给出拒绝原因，`status` 在任务存在时包含动作后的快照。取消保留已经完成的世界写入。`PAUSED_RECOVERY` 不能通过普通 resume 强制回放；没有可靠证据的旧操作必须保持暂停或取消。

`WRITTEN` 表示所有选中阶段完成，原生光照、地形/POI 保存和所需网络队列已确认。`SETTLING` 仍在等待这些确认。状态中的 `phase` 从 0 开始，显示文本按 1 开始；`completedChunkPhases` 统计区块阶段，不是唯一 chunk 数量。同一 chunk 可以经历多个向外推进的壳层。

`Status.progress()` 分别报告准备阶段，以及正在写入、加载、等待光照、存盘和发包的区块数。取消或失败的任务仍可能有此前提交的工作等待收尾，应检查这些计数；不能把接受取消等同于已经回滚或全部排空。

保存和发包队列在光照完成后重验区块对象和修改修订号。若外部写入已改变区块，会重新排入光照屏障；序列化或编码期间发生重入修改则拒绝确认。存储确认同时保留各 terrain/POI store future 的失败，后续成功 flush 不会抹掉先前失败。包提交只表示交给原生连接，不代表远端渲染确认。

## 传播、生成和策略边界

服务器 `propagation` 默认为 `NONE`，也支持 `AXIS_6`、`AXIS_26`、`RAYCAST_REFINED`。后三种先按 `voxelregion_test:blocks_propagation` 捕获原始阻挡事实；`occlusion_cell_size` 只能为 4、8、16。保护标签、传播阻挡和领地许可是独立规则，几何阴影不是爆炸能量模型。

冻结捕获要求全范围可读、变更跟踪可信，并通过持续 mutation 订阅和标签规则版本检查。完成只读捕获后发布原始快照文件，再由一个有界后台工作线程建立索引；该线程不访问世界对象。没有完整数据、捕获期间修改或安全证据失效会暂停。特别是 `generate=false` 加非 NONE 传播不能通过“排除未知区块”假定那里没有阻挡物。

同维度范围相交且任一启用传播的请求按接受顺序执行，前一请求及其原生收尾结束后，后一请求才开始捕获。前一请求暂停时仍保留顺序，取消它可在收尾完成后放行；每个快照对应轮到该请求时的世界，不是多个爆心共用的瞬时快照。纯 NONE 请求仍可在不同区块并发推进。

恢复使用原冻结文件及哈希，不重新扫描已经改变的世界。显式允许生成时，快照捕获加载正常原始地形。生成写入快捷路径只适用于通过证明的原版平坦配置、完整高度覆盖、NONE 传播和受审核的生成环境；其余情况走原生生成再清除。现有 NBT 不会被新空区块覆盖。

物理默认 `NONE`，另有 `BOUNDARY_ONLY`、`COMPATIBLE`；后两种可能产生区域外间接效应。实体伤害默认关闭，启用时按独立领地许可、连续几何包含和距离衰减处理已加载实体；不提供实体遮挡、击退或无证据自动重放。详细原生维护契约见 [Forge 适配说明](forge-integration.md)。

## 批量写入观察者

批量 section 写入只在全部加载模组都有已审核适配时启用。宿主存在逐块事件、区段缓存或保护观察者时，需要审计其替代通知，并在构造阶段注册：

```java
HostIntegration.registerAuditedMod("my_host", new HostIntegration.Observer() {
    @Override
    public void afterSectionCleared(ServerLevel level, LevelChunk chunk, int sectionY) {
        // 使宿主维护的相关缓存失效或发出宿主需要的聚合通知。
    }
});
```

只有确认宿主没有额外观察需求且相关修改路径有修订号跟踪时才使用空 observer。未知模组使批量提交回退原生路径；非 NONE 快照模式也不能凭未知变更跟踪继续执行。注册声明不证明模组没有 worldgen、retrogen 或其他生成钩子；生成快捷路径仍需要独立安全证明，UNKNOWN 走原生生成。服务端开始后拒绝变更观察者注册表。

## 持久协议与打包验证

Java 包迁移保持既有协议名称：SavedData `voxelregion_test_operations`、世界目录 `data/voxelregion_test_intents`、能力键 `voxelregion_test:receipts`、保护标签 `voxelregion_test:protected`。传播标签为 `voxelregion_test:blocks_propagation`。这些名称属于兼容协议，不会跟随宿主 mod id 改名。冻结文件 `<uuid>.occlusion.nbt` 保存原阻挡盒、修订号和策略，经过大小、schema、身份和 SHA-256 校验，并且不覆盖已有证据。

receipt 和 terrain 共同保存在 chunk NBT 中；SavedData 游标不能替代这份证据。重启后先协调 journal 并进入恢复检查：跨阶段恢复跳过已完成阶段，再检查剩余阶段的 mutation revision 和 write sentinel。缺少 receipt、遗留无游标 STARTED、未知 pending write、修改版本不一致或缺少原冻结文件均保持暂停。未访问区块在重启后也不自动成为可信剩余工作，从而避免旧任务清除后来新建的方块。

性能计时使用公开 `ServerTickTimings.lastNanos()`、`averageMillis()`、`sampleCount()`，其采样点覆盖完整 `tickServer`，包括 Forge END。在 END 回调里读取时，最新已完成样本属于上一 tick；按 sampleCount 对齐。原生 `forge tps`/`averageTickTime` 不包含 END 中的区域处理，不能单独作为适配器性能通过依据。预算、真实加载范围、光照/存档尾部和完成状态须一起记录，参见 [性能契约](high-performance.md)。

`org.voxelregion.forge.runtime`、`NativeAccess` 和 mixin 是内部实现，不是稳定宿主接口。正常制品过滤所有 `*GameTests*`、`ProbeBlocks`、测试结构和验证维度；测试代码保留在仓库的 verification 源集。`tools/verify_artifacts.py --core ... --adapter ... --forge ...` 检查两层 JarJar 内容、重复类、许可证、refmap 和测试内容隔离。标准 Forge 服务端的真实启动、持久回读和大半径运行仍需针对最终制品另行验证；编译或制品结构检查本身不等于生产验收通过。
