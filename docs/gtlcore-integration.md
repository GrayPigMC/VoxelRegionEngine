# GTLCore 接入

GTLCore 集成由纯 Java engine、Forge adapter 和 GTLCore 自身的宿主桥接组成。正常 GTLCore 制品内嵌两份库，不需要安装临时 `voxelregion_test` 模组。宿主版本保持原值；当前库版本为 `0.1.0`。

替换 Core 测试时移除先前的临时测试 mod。早期 0.1.0 测试包使用了不同的内嵌库标识，不能与现在的 Core 混用；新包的 engine/adapter 标识已统一。直接使用 Core 的 `/gtlregion` 命令即可验证，不需要同时安装测试包。

宿主桥接位于 `org.gtlcore.gtlcore.integration.voxelregion`。`GTLCore` 构造阶段安装 `ForgeRegionEngine`，注册真实的方块监视器、多方块结构通知和管理员命令。公开服务 `GtlRegionExplosions` 提供 `submitSphere`、`submit`、`status`、`statuses` 和 `control`；世界操作仍须在服务端线程调用，宿主负责调用者权限。

```java
var handle = GtlRegionExplosions.submitSphere(level, actorUuid, center, 200,
        ForgeRegionEngine.Options.DEFAULT);
var status = GtlRegionExplosions.status(level, handle.id()).orElseThrow();
```

`submit` 接受 adapter 的 `RegionGeometry`，保留可持久恢复的几何描述。提交成功表示接受记录已持久写入，尚不等于世界修改完成。观察 `Status.progress()` 可区分写入、加载、光照、保存和发包工作；取消后仍应等待已经提交的收尾完成。

## 管理员验收入口

`/gtlregion` 要求权限等级 2。

```text
/gtlregion blast <radius> [generate] [clearFluids] [destroyBedrock]
/gtlregion status
/gtlregion pause <operation UUID>
/gtlregion resume <operation UUID>
/gtlregion cancel <operation UUID>
```

爆心取命令执行位置的方块中心。服务端控制台可用：

```text
/execute positioned 0 64 0 run gtlregion blast 2000
```

默认选项来自同一份 adapter API，显式布尔参数供受控验收调整。此入口不依赖测试物品；同一服务也可供 GTLCore 机器或已经认证的脚本调用。未知恢复证据不能通过普通 `resume` 强制绕过。

玩家可用以下命令观察半径 1024、允许处理未生成区块、清除流体并保留基岩的任务：

```text
/gtlregion blast 1024 true true false
```

命令接受后，发起玩家会看到 BossBar，每 10 个服务端 tick 刷新一次，显示实际完成区块数/几何总量、处理百分比、累计耗时及最近约 5 秒的区块/t、区块/s。总量在后台按球体与建造高度的精确区块相交规则统计，不读取世界或加载区块；统计结束前显示“统计范围”，不以包围盒估算百分比。

完成数来自已经确认光照、terrain/POI 持久化和必要发包的区块，不是排队数量或刚写入内存的数量。吞吐只计算实际完成数的增长；区块/t 使用真实经过的服务端 tick，区块/s 使用墙钟时间，不能在服务端变慢时固定按 20 TPS 换算。近期窗口用于观察当前速度，起始样本不足或单个 tick 很长时窗口可能短于或长于 5 秒。计时从本次提交开始，包含接受、等待、暂停和收尾时间；最后一条摘要给出整次观察的平均吞吐。

保护拒绝和禁用生成时排除的区块另外显示为“跳过”。进度条及百分比表示已完成加已跳过的处理进度，完成数本身不含跳过；摘要有跳过时会明确说明该范围未清理。即使所有区块均已处理，收尾尚未确认时也不会显示完全完成。任务最终 `WRITTEN` 表示所需原生光照及真实存盘屏障已经确认，必要网络包也已提交；它不代表远端客户端已经接收或渲染。完成、取消或失败均等待此前提交的工作收尾后移除进度条，并发一次结果摘要；取消不回滚已经清理的地形。

同一玩家再次提交时，仅最新任务保留进度条，旧任务继续运行并在结束时单独汇总。玩家断线会移除其进度显示和观察记录，不取消任务；重连后可用 `/gtlregion status` 查询。停服清理显示与统计线程，任务恢复仍由引擎负责。控制台只收到接受和终态反馈，不持续刷屏；无论是否显示进度，操作调度与预算不变。

`gtlcore-voxelregion-server.toml` 中 `operations.protected_resistance` 的新默认值为 `6000.0`，仍保留“大于等于阈值就保护”的规则。已有存档中保存的 `1200.0` 不会自动覆盖；需要清除黑曜石时可改成 `6000.0`，重新进入存档后提交新任务。已接受任务使用各自记录的阈值，修改配置不会偷偷改变旧任务的破坏范围。基岩继续由 `destroyBedrock` 单独控制。

## 裂变堆入口与策略

`FissionReactorMachine.doExplosion` 的小爆炸保留既有原生行为。`gtlcore-region-explosions-server.toml` 控制大爆炸接入：

| 项 | 默认值 | 含义 |
|---|---:|---|
| `fission.use_region_engine` | `true` | 对达到阈值的裂变堆事故使用区域任务 |
| `fission.region_threshold` | `32` | 达到此原有 explosion power 后采用区域任务 |

转换使用 `ceil(power)` 作为精确区域半径，**不声称它与原版爆炸强度的地形结果等价**。机器先提交持久任务，接受成功后才移除源控制器。请求失败会记录错误，不回退到一次同步的大范围原版爆炸。区域任务的保护、流体、实体伤害、加载和预算策略来自 `gtlcore-voxelregion-server.toml`。

## 实际观察者

GTLCore 注册两个独立桥接：

- `BlockStateWatcher` 用 section 索引查找已登记位置，只有批量提交实际改变的位置才收到新状态；回调可以安全移除自身。卸载时清理索引，适用于独立服务器和集成服务器。
- GTCEu 多方块桥接检查同区块已成型结构的缓存。一个已提交 section 中有实际改变的缓存位置时，发出一次 `onBlockStateChanged`，由现有 GTLCore 逻辑重新检查完整结构，避免为每个普通方块重复检查形状。

GTCEu `1.4.4` 的准确 `OreBlock` 类、非沙质矿石注册了静态删除证明。沙矿、机器、方块实体、POI、发光或含流体状态仍须通过 adapter 的额外条件；此证明不按整个 GT 命名空间放行所有方块。原生剩余状态继续运行正常回调。

## 自包含构建与快照

GTLCore 保存 `vre-engine` 的便携源码和 `vre-forge` 的 Forge 源码。构建不依赖旁边存在 VoxelRegionEngine 工作树，也不下载临时测试模组作为前提。维护者在库源码稳定后同步：

```text
python tools/sync_gtl_vre.py --core <authorized-GTLCore-checkout> --apply
```

不加 `--apply` 只显示待同步快照。`vre-vendor.json` 记录源 revision、dirty 标记、整体 SHA-256 和每个文件的内容哈希；同步工具只管理两份 VRE 源模块，保留宿主 API 和构建桥接。

宿主一次编译 adapter，再单独 remap 为 SRG Jar-in-Jar 库。adapter 使用从宿主编译结果中提取的独立 `voxelregion-forge.refmap.json`，不混入 GTLCore refmap 条目。外层 GTLCore 仅保留宿主代码和策略资源，避免重复打包 `org.voxelregion` 类。MPL-2.0 许可证和 NOTICE 随两个库保留。

在 GTLCore 工作树运行 `build` 会执行 `verifyPackagedVre`，检查 adapter API、两份 mixin 配置、独立 refmap、两份内嵌库和测试内容隔离。临时验证源只在 `-PvreVerification` 下启用：

```text
gradlew.bat runVreGameTest -PvreVerification -PvreVerificationRunDirectory=<isolated-run-directory> -PvreVerificationRuntimeDir=<local-verification-jars> --max-workers=1
```

验证目录可提供宿主元数据要求、但未纳入开发 runtime 的必要依赖，例如 MAE2；这些本地 jar 由 Loom 正常重映射，只用于测试，不写入交付制品。记录每份依赖的版本和哈希，不能通过删除必需依赖声明来伪造启动成功。Java 的 `ActiveProcessorCount` 和操作系统亲和性还须由测试启动器限制，总重测 CPU 不超过工作区约定。

上游开发依赖中的 AE2WTLib 可能早于宿主声明的最低版本；`-PvreVerificationWirelessJar=<local-jar>` 可在验证模式显式使用满足版本要求的公开模组 jar，不修改发布依赖声明。部分第三方模组的开发环境 `DistExecutor` 校验会提前加载客户端 lambda 类型，因此也提供 `vreVerificationJar`：它仅包含独立验证模组、GameTest 夹具及模板，包名与 Core 不重叠，不嵌入正常 Core jar。

生产验证可将正常 Core 制品、实际服务器依赖和该独立验证 jar 放入新的标准 Forge 服务端，添加 `-Dforge.enableGameTest=true -Dforge.enabledGameTestNamespaces=gtlcore`，正常启动世界与保护模组管理器后，从控制台执行：

```text
execute positioned 64 128 64 run test run hostbatchpreservesprotectionandnotifiesonlychangedwatchedpositions
```

Forge 自动 GameTest 发现和 tick 在生产模式默认禁用；独立验证 jar 显式注册这一个夹具，并在服务器 END tick 驱动原生 GameTestTicker（若 Forge 已启用则不重复驱动）。它不替换待测 API、保护逻辑或提交后端。全部宿主断言通过时记录 `VRE_CORE_NATIVE_GATE_PASS`，包括实际删除数、批量 section 数、被测矿石 ID 和通知次数。独立验证 jar 只用于隔离测试世界。

宿主验证用例经真实 API 批量清除一个混合 section，检查普通 GT 矿石、受保护基岩、监视器自移除和结构通知的数量。结构夹具验证真实 saved-data 路由和通知次数，未代替具体机器的完整成型测试。它不等于覆盖整包所有机器和第三方钩子。最终验收还须记录实际 Forge/模组版本、制品哈希、完整 tick 时间、客户端发布结果及原生磁盘回读。本接入文档本身不宣告这些验证已经通过。
