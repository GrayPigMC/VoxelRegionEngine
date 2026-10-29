# VoxelRegionEngine API 使用与参考

适用版本：**VoxelRegionEngine 0.1.0 / Java 17 / Minecraft 1.20.1 Forge 适配器**。适配器构建依赖 Forge 47.3.7；其他 Forge 构建及模组组合需要由宿主验证。项目许可证为 MPL-2.0。仓库：<https://github.com/GrayPigMC/VoxelRegionEngine>。

本文是可单独分发的接入文档，覆盖地形清理、实体清理、几何、状态与持久化。API 仍处于初始开发期，升级时应核对签名和持久化协议。

## 1. 选择接入层

| 需求 | 使用入口 | 宿主承担的工作 |
|---|---|---|
| 在 Forge 世界中执行区域清理 | `org.voxelregion.forge.ForgeRegionEngine` | 打包、安装、权限认证、宿主变更通知 |
| 清理区域内实体 | `org.voxelregion.forge.ForgeEntityOperations` | 同上；与地形任务分别提交和观察 |
| 仅使用几何、规划或自建世界后端 | 纯 Java `org.voxelregion.*` | 世界读取、提交、光照、保存、恢复和线程管理 |

**库和 Forge 适配器均不要求安装 GTLCore。** GTLCore 是一个使用本库的宿主，自己的机器桥接、命令和世界生成优化不是通用库依赖。

区域清理按几何选区与保护规则执行，不是原版爆炸能量模拟。半径不能直接当作与原版 explosion power 等价的参数。

## 2. 制品、构建和安装

从仓库构建，Windows 使用 `gradlew.bat`，其他系统使用 `./gradlew`：

```text
# 仓库根目录：纯 Java 库
gradlew.bat build

# 切换到 tools/forge-test：Forge 适配器与临时测试模组
../../gradlew.bat adapterJar remapJar
```

| 制品 | 用途 |
|---|---|
| `build/libs/voxel-region-engine-0.1.0.jar` | 无 Minecraft 依赖的算法库 |
| `tools/forge-test/build/libs/voxel-region-forge-adapter-1.20.1-0.1.0.jar` | 发布用 Forge 适配器，SRG 映射，内含适配 mixin |
| `tools/forge-test/build/libs/voxel-region-forge-adapter-1.20.1-0.1.0-dev.jar` | named 开发制品，由 `adapterDevelopmentJar` 任务生成 |
| `tools/forge-test/build/libs/voxel-region-forge-test-1.20.1-0.1.0.jar` | 临时测试模组，内嵌 engine 与 adapter；正式宿主无需它 |

纯 Java 工程可使用本地 jar，或在本仓库执行 `publishToMavenLocal`，然后依赖 `org.voxelregion:voxel-region-engine:0.1.0`。该命令只发布到本机 Maven 仓库，不代表存在公共 Maven 下载源。

Forge 宿主应将 **engine 和发布用 adapter 两份库**作为 Forge JarJar 库随宿主交付，并用自己构建工具的映射支持配置编译依赖。两份库以 `FMLModType=GAMELIBRARY` 交付；adapter 不含独立 `@Mod` 入口。不要将开发映射 jar 直接当发布 jar，也不要同时嵌入已经包含它们的测试模组造成重复类。

宿主 `META-INF/MANIFEST.MF` 必须包含以下两个配置；已有宿主 mixin 配置时合并列表：

```text
MixinConfigs: voxelregion-forge.mixins.json,voxelregion-receipts.mixins.json
```

两个配置与 `voxelregion-forge.refmap.json` 在 adapter 内，打包时需保留。在宿主 `@Mod` 构造阶段安装：

```java
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.voxelregion.forge.ForgeRegionEngine;

// 放在宿主的 mod 构造函数内。
ForgeRegionEngine.install(FMLJavaModLoadingContext.get().getModEventBus(),
        "my_host-voxelregion-server.toml");
```

`install` 幂等；一个 JVM 使用一套运行时和配置，第一次安装决定配置文件名。省略文件名时为 `voxelregion_test-server.toml`。安装后适配器负责服务端 tick 与停服处理，宿主无需调用内部 runner。`install` 不能补装遗漏的 mixin；缺少必要原生 hook 的提交会被拒绝。

宿主资源还应提供自身的保护与传播标签策略，保留以下协议路径：

```text
data/voxelregion_test/tags/blocks/protected.json
data/voxelregion_test/tags/blocks/blocks_propagation.json
```

标签采用标准 Minecraft JSON，例如 `{"replace":false,"values":["minecraft:barrier"]}`。`protected` 表示不可直接清除；`blocks_propagation` 用于非 `NONE` 传播模式的原始阻挡捕获。两者独立。正式宿主应明确提供完整策略，不能假定未安装的测试模组会替它提供资源。

## 3. 服务端线程和权限

所有 Forge 世界操作，包括提交、查询和控制，均须在**对应服务器线程**调用。网络消息先完成权限校验，再通过 Forge 网络上下文的 `enqueueWork` 或服务器任务队列转交服务器线程。不要在客户端或自行创建的工作线程访问实时世界。

公开入口是受信任宿主 API，不自行认证客户端身份。宿主负责调用权限、参数校验和频率限制。地形 API 的 `actor` UUID 用于来源记录及伤害归属，不能代替授权，也不提供绕过领地保护的开关。

## 4. 最小地形清理示例

下面是可放入宿主的完整辅助类；由已经认证的命令或机器逻辑在服务器线程调用：

```java
import java.io.IOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.voxelregion.forge.ForgeRegionEngine;

public final class RegionActions {
    public static ForgeRegionEngine.Handle clearSphere(
            ServerLevel level, UUID actor, BlockPos center) throws IOException {
        return ForgeRegionEngine.submitSphere(level, actor, center, 200,
                new ForgeRegionEngine.Options(20, true, true, false));
    }
}
```

含义：以 `center` 的方块中心为球心，半径 200 格，引信 20 tick，允许处理需要生成的区块，清除流体，保留基岩。成功返回 `Handle` 表示接受意图已持久记录，**不表示清理完成**。持久记录失败可能抛出 `IOException`；参数、配额或原生 hook 不满足也会拒绝请求，调用方需向发起者报告失败。

### 地形选项

`ForgeRegionEngine.Options(int fuseTicks, boolean generateMissingChunks, boolean clearFluids, boolean destroyBedrock)`：

| 参数 | `Options.DEFAULT` | 含义 |
|---|---:|---|
| `fuseTicks` | `0` | 引信延迟，构造器允许 `0..1200` tick |
| `generateMissingChunks` | `true` | 允许正常生成或采用满足条件的清空区块生成路径 |
| `clearFluids` | `true` | 清除选区内允许破坏的流体和含水状态 |
| `destroyBedrock` | `false` | 是否允许破坏选区内基岩；仍受领地、边界等规则约束 |

三参数构造器 `Options(fuseTicks, generateMissingChunks, clearFluids)` 仍可用，等价于 `destroyBedrock=false`。

`generateMissingChunks=false` 仍可读取磁盘上已有的 FULL 区块；需要继续生成的区块会记录为排除项，之后加载也不会自动补炸。`clearFluids=false` 保留纯流体，删除允许破坏的含水固体后留下其流体；受保护的含水固体整体保留。

抗爆阈值、保护、传播、物理和共享预算由服务端配置控制，不是每个任务 `Options` 中的参数。基岩开关不会解除其他方块的保护。

### 地形公开方法

以下均为 `ForgeRegionEngine` 的静态方法：

| 方法 | 返回值与用途 |
|---|---|
| `install(IEventBus modBus[, String serverConfigFile])` | 安装事件和配置；方括号表示可选重载，不是 Java 语法 |
| `maximumRadius()` | 当前服务器配置的最大半径 `int` |
| `submitSphere(ServerLevel, UUID actor, BlockPos center, int radius, Options)` | `Handle`，可能抛出 `IOException` |
| `submit(ServerLevel, UUID actor, RegionGeometry, Options)` | 通用几何任务 `Handle`，可能抛出 `IOException` |
| `status(ServerLevel, UUID id)` | `Optional<Status>`，不存在时为空 |
| `statuses(ServerLevel)` | 本维度任务的 `List<Status>` 快照 |
| `control(ServerLevel, UUID id, Control)` | `ControlResult`：动作是否应用、最新状态和拒绝原因 |

`Handle` 包含 `dimension()` 与 `id()`；查询和控制必须使用对应维度的 `ServerLevel`。不要把客户端保存的 UUID 当成操作授权。

## 5. 几何、坐标和序列化

Forge 提交接受不可变、可持久化的 `RegionGeometry`。它是封闭类型集合，当前不接受任意自定义 `Volume` 作为可恢复世界任务。

```java
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;

RegionGeometry ball = new RegionGeometry.Ball(Point3.of(0.5, 64.5, 0.5), 400);
RegionGeometry box = new RegionGeometry.Box(new IntBox(-32, -64, -32, 32, 128, 32));
RegionGeometry column = new RegionGeometry.Column(Point3.of(0.5, 0, 0.5), 40, -64, 128);
RegionGeometry shell = new RegionGeometry.Combined(
        RegionGeometry.BooleanOperation.DIFFERENCE, ball, column);

// 可保存到宿主自己的 NBT；读回会校验格式。
RegionGeometry restored = RegionGeometry.load(shell.save());
```

| 类型/方法 | 精确定义 |
|---|---|
| `Point3(long x2, long y2, long z2)` | 每个坐标是世界坐标的两倍；`Point3.of(x,y,z)` 接收实际坐标 |
| `Ball(Point3 center, long radius2)` | `radius2` 是实际半径的两倍；示例 `400` 表示半径 200 格 |
| `Box(IntBox bounds)` | `IntBox(minX,minY,minZ,maxX,maxY,maxZ)`，三个轴均为左闭右开 |
| `Column(Point3 center, long radius2, int minY, int maxY)` | 竖直圆柱，Y 范围 `[minY,maxY)`；`center.y2()` 不参与柱高 |
| `Combined(BooleanOperation, left, right)` | `UNION` 并集、`INTERSECTION` 交集、`DIFFERENCE` 左减右 |
| `volume()` | 获得纯 Java `Volume` 几何 |
| `containsPoint(double x, double y, double z)` | 连续坐标包含判断，用于实体位置 |
| `save()` / `RegionGeometry.load(CompoundTag)` | schema 1 NBT 序列化和校验读取 |

几何方块判定采样 `(x+0.5,y+0.5,z+0.5)`，不是只要方块碰到球面就算命中。中心和半径支持精确的半格值；`Point3.of` 等拒绝非 0.5 倍数、NaN 和无穷值，不会悄悄四舍五入。组合深度最多 32 层、总节点最多 256；NBT 不接受未知类型或缺失的必需字段。

通用 `submit` 会从包围盒派生执行中心及向上取整的包围半径，该半径也受最大半径限制；精确选区始终使用原几何。因此盒子的边长小于半径上限，并不保证它一定通过包围半径校验。

## 6. 查询进度、暂停、取消和恢复

在后续服务器 tick 查询，不要阻塞服务器等待任务完成：

```java
var current = ForgeRegionEngine.status(level, handle.id());
if (current.isPresent()) {
    var s = current.get();
    var p = s.progress();
    // s.state()：状态；s.message()：原因；s.asComponent()：完整状态文本。
    // p.loadingChunks()、p.pendingLighting()、p.pendingSaves()：阶段积压。
    boolean durablyFinished = s.state() == ForgeRegionEngine.State.WRITTEN;
}

var result = ForgeRegionEngine.control(level, handle.id(),
        ForgeRegionEngine.Control.PAUSE);
// 检查 result.applied()；拒绝原因在 result.reason()。
// 恢复/取消分别使用 Control.RESUME、Control.CANCEL。
```

| 状态 | 含义 |
|---|---|
| `FUSE` | 引信等待 |
| `RUNNING` | 准备和执行 |
| `SETTLING` | 等待已提交工作的光照、保存或网络收尾 |
| `RECOVERING` | 正在核对持久化恢复证据 |
| `PAUSED` | 已暂停 |
| `PAUSED_UNLOADED` / `PAUSED_PROTECTION` | 因加载或保护条件暂停，查看 `message()` |
| `PAUSED_RECOVERY` | 缺少可信恢复证据；普通 resume 不能强行重放 |
| `FAILED` | 失败；此前世界写入可能已发生 |
| `CANCELLED` | 已接受取消；不回滚已执行的世界修改 |
| `WRITTEN` | 全部阶段完成，必要光照、terrain/POI 存储和网络提交屏障已确认 |

`Status.terminal()` 对 `FAILED`、`CANCELLED`、`WRITTEN` 返回 true，不能仅凭 terminal 判断成功。取消/失败后，已提交工作仍可能等待收尾；还需观察 `progress()`。`WRITTEN` 的网络确认是交给原生连接，不是客户端已经接收或渲染。

`Status` 还提供 `radius`、`phase`、`phaseCount`、`visited`、`changed`、`kept`、`completedChunkPhases`、`deniedChunks`、`unloadedChunks`、`bulkSections`、`clearFluids`、`description`。其中 `completedChunkPhases` 对新整区块任务每区块计一次；旧球壳任务可能重复计算同一区块的多个阶段。

`Progress` 字段为 `preparationStage`、`writingChunks`、`loadingChunks`、`pendingLighting`、`pendingSaves`、`pendingPackets`、`retainedChunks`。这些是实际阶段计数，**可以重叠，不能相加当成完成百分比**。公共 API 未提供统一的总区块数或剩余时间。

同步区域变更回调内发起控制会收到 `applied=false`，应排到后续服务器 tick。任务恢复使用与地形共同保存的 receipt、修订号和必要的冻结证据，不能只恢复 UUID/游标便重放。缺证据时保持暂停；避免旧任务删除重启后新建的内容。

## 7. 范围实体清理 API

实体任务与地形任务独立，安装仍使用 `ForgeRegionEngine.install(...)`。它们不共享一个原子提交或完成状态；需要同时处理时分别提交、保存两个 handle，并分别报告结果。

**实体默认选项包含在线玩家，也覆盖未加载区块中已保存的实体；非玩家默认直接移除。** 地形 `Options.DEFAULT` 本身不会自动触发这项实体清理。

```java
import org.voxelregion.forge.ForgeEntityOperations;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.Point3;

RegionGeometry area = new RegionGeometry.Ball(
        Point3.of(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5),
        400); // 半径 200 格

var entityHandle = ForgeEntityOperations.submit(level, area,
        ForgeEntityOperations.Options.DEFAULT);
var entityStatus = ForgeEntityOperations.status(level, entityHandle.id());

// 如需排除玩家，显式使用：
var keepPlayers = new ForgeEntityOperations.Options(false,
        ForgeEntityOperations.Scope.LOAD_EXISTING_SAVED,
        ForgeEntityOperations.Mode.DISCARD);
```

`ForgeEntityOperations.Options(boolean includePlayers, Scope scope, Mode mode)` 默认是 `(true, LOAD_EXISTING_SAVED, DISCARD)`。

| 选项 | 行为 |
|---|---|
| `includePlayers=true` | 当前世界中的在线玩家也参与；离线玩家存档不属于扫描目标 |
| `Scope.LOADED_ONLY` | 只处理已加载的实体 |
| `Scope.LOAD_EXISTING_SAVED` | 还通过原生实体管理器异步读取已保存实体；扫描本身不请求生成地形 |
| `Mode.DISCARD` | 非玩家直接移除；原生容器实体先清理库存与未展开战利品信息，避免把内容物变成掉落物 |
| `Mode.KILL` | 使用原生死亡路径，保留原生/模组死亡、掉落和回调语义 |

**玩家即使在 `DISCARD` 模式也走原生死亡流程。** `DISCARD` 不是无条件绕过所有模组回调的磁盘删除器；自定义实体移除回调仍可能访问地形或生成其他实体。`KILL` 的伤害、死亡回调尤其可能引发地形加载。

目标包括生物、箱子矿车、漏斗矿车、箱船、普通船、掉落物和弹射物，不限于 `LivingEntity`。普通箱子是方块/方块实体，由地形 API 处理。选中与否按实体当前位置和精确几何判断，不按碰撞箱相交，也不按所在方块中心判断。

公开静态方法：

| 方法 | 返回值 |
|---|---|
| `submit(ServerLevel, RegionGeometry, Options)` | 实体 `Handle(dimension,id)` |
| `status(ServerLevel, UUID id)` | `Optional<Status>` |
| `cancel(ServerLevel, UUID id)` | `boolean`，是否接受取消；已执行动作不回滚 |

实体状态是 `QUEUED`、`RUNNING`、`APPLIED`、`CANCELLED`、`FAILED`，后三者为 terminal。`Status` 提供 `examined`、`affected`、`surviving`、`skippedPlayers`、`deniedChunks`、`completedChunks`、`pendingChunks`、`message`。

`affected` 是已死亡/移除数；`surviving` 表示调用后仍存活的目标，例如模组拒绝死亡。**`APPLIED` 仅表示本次扫描动作执行完，不是实体存档已刷盘**，不能当作地形 `WRITTEN` 使用。实体保存仍遵循原生自动保存和正常停服保存。

这是一次有界扫描，执行前重新检查位置、维度和许可；不是持续禁止实体存在的区域。扫描后出现的掉落物、进入区域的实体或重生玩家不会被无限清理。实体任务不跨重启恢复。所有受保护领地跳过，保护接口不可用时报错，不提供管理员身份自动绕过。

## 8. 配置、保护和性能边界

以下是 0.1.0 当前 Forge 适配器配置中的主要项目。配置文件名由第一次 `install` 决定；表中值是**新生成配置的默认值**，不会自动覆盖已有世界的配置。已接受任务的不可变策略不会因后来改配置而悄悄改变。

| 配置键 | 默认值 | 用途 |
|---|---:|---|
| `operations.maximum_radius` | `2000` | 半径上限，可配置 `1..10000` |
| `operations.maximum_pending_operations` | `16` | 待处理任务上限 |
| `operations.protected_resistance` | `6000.0` | 上下文抗爆性大于等于此值则保留；保护标签和不可破坏规则另行生效 |
| `operations.progression` | `CENTER_OUT` | 中心向外整区块处理；另可选 `AS_FAST_AS_POSSIBLE` |
| `operations.bulk_sections` | `true` | 满足几何、保护和宿主条件时整区段替换 |
| `operations.propagation` | `NONE` | 纯几何清理；另有 `AXIS_6`、`AXIS_26`、`RAYCAST_REFINED` |
| `operations.physics` | `NONE` | 抑制邻居物理；另有 `BOUNDARY_ONLY`、`COMPATIBLE` |
| `operations.entity_damage_at_center` | `0.0` | 关闭地形任务附带的已加载实体衰减伤害；与独立实体清理 API 不同 |
| `operations.exact_new_chunk_biomes` | `false` | 仅对从未生成、直接物化为清空地形的区块生效；true 才逐个 quart 使用原生采样 |
| `budget.millis_per_tick` | `40.0` | 所有维度/任务共享的协作式主线程预算 |
| `budget.maximum_in_flight_chunks` | `4096` | 写入和等待光照/保存/发包的全局区块窗口 |
| `budget.read_ahead_chunks` | `1024` | 允许生成时原生异步读取/生成的预取窗口 |
| `budget.io_threads` / `budget.preparation_threads` | `max(4,min(16,可用处理器数))` | 独立 I/O 通道、压缩和脱离实时世界的准备工作；范围 `1..32` |

`exact_new_chunk_biomes=false` 的快捷物化区块使用近海平面中心采样后均匀填充，省略地下生物群系分层；**不能宣称该模式保留全部原始生物群系数据**。已存在区块的生物群系始终保留。确有完整原生采样要求时设为 true。

未生成区块只有满足完整覆盖、保护层、生成环境及所用快捷路径的证明条件才可直接生成清空结果；其他情况仍走原生读取/生成后清除。`generateMissingChunks=true` 不保证每个区块都跳过世界生成，调大线程数也不会消除原生依赖链。

非 `NONE` 传播先捕获并冻结原始阻挡，缺数据、捕获期间修改或恢复证据无效时暂停。不能将未知未加载区块当成透明空气。开启物理的模式可能产生选区外的间接变化。光照快捷路径也有适用条件，流体、透明形状或保留光源等情况会使用相应原生路径。

预算是协作式的：一个原生回调或整区块提交不能在执行中途强行抢占。不承诺固定区块/tick；读取、生成、保护、实时世界写入、光照、保存和客户端网络都可能影响吞吐。`ServerTickTimings.lastNanos()`、`averageMillis()`、`sampleCount()` 可观察完整服务器 tick，调用 Forge END 时最新完成样本属于上一 tick。

## 9. 宿主批量变更桥接

宿主存在全局方块监听、多方块缓存等逻辑时，必须在服务端启动前注册实际的聚合通知。不要用空观察者假装完成兼容：

```java
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.voxelregion.forge.HostIntegration;

HostIntegration.requireMutationObserver("my_host");
HostIntegration.registerAuditedMod("my_host", new HostIntegration.Observer() {
    @Override
    public void afterSectionCleared(ServerLevel level, LevelChunk chunk, int sectionY) {
        // 在这里调用宿主真正的区段缓存失效、多方块或监视器通知。
    }
});
```

`Observer.supportsBulk(ServerLevel)` 可否决无法维持契约的世界。需要旧状态时可覆盖接收 `PaletteSectionClearBackend.Prepared` 的重载，但只能在同步回调内读取，不能保留计划。当前适配器对已知全局变更 hook 要求对应观察者；未知方块的移除仍走原生回调，不因一个无关 UI 模组存在便统一禁用所有地形批处理。

`registerStaticRemovalProof(String, Predicate<BlockState>)` 是高级接入点：只有逐状态/精确类型审计其 `onRemove` 和继承移除逻辑后才可注册。不能按整个 mod 命名空间或抗爆值断言安全。方块实体、POI、流体和发光状态仍受额外限制。观察者注册不等于生成器或 retrogen 的安全证明。

GTLCore 中另有 `org.gtlcore.gtlcore.integration.voxelregion.GtlRegionExplosions` 和 `/gtlregion` 宿主入口。通用宿主直接使用本文 API 即可；GT 世界生成调度、矿物线程安全桥接和进度条实现属于 GTLCore，不是本库可随意开启的通用并行开关。

## 10. 只使用纯 Java 算法库

纯 Java 库不读取世界、不加载区块、不自动写入 Minecraft。几何可直接使用：

```java
import org.voxelregion.geometry.*;
import org.voxelregion.spatial.ChunkCursor;

Volume sphere = Sphere.of(0.5, 64.5, 0.5, 200);
Volume box = new BoxVolume(new IntBox(-16, -64, -16, 16, 320, 16));
Volume selected = Volumes.difference(sphere, box);

boolean selectedBlock = selected.contains(10, 64, 10);
Coverage coverage = selected.classify(new IntBox(0, 64, 0, 16, 80, 16));
ChunkCursor chunks = new ChunkCursor(selected, -64, 320);
// 分批调用 chunks.hasNext()/next()；游标不会替宿主加载或修改区块。
```

`Volume.emitRanges(clip,sink)` 输出世界坐标 `(x,z,minY,maxYExclusive)`。`VoxelMask` 使用区段局部坐标，索引 `x | (z << 4) | (y << 8)`，不要混用。`ChunkCursor` 和 `CenterOutChunkCursor` 是有状态游标，不可多线程共享；几何与已脱离实时世界的不可变快照可用于纯计算线程。

区段规划使用 `org.voxelregion.planner`：

```java
import org.voxelregion.geometry.Volume;
import org.voxelregion.planner.*;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.SectionKey;

// 方法参数由宿主在正确线程捕获；stateIds 必须恰好 4096 格。
SectionPlan planSection(Volume volume, SectionKey key, long revision,
        int airStateId, int[] stateIds, ProtectionOracle protection) {
    var snapshot = new SectionSnapshot(key, revision, airStateId, stateIds);
    return new SectionPlanner().plan(volume, snapshot, protection, FrozenOcclusion.none());
}
```

`ProtectionOracle` 提供 `mayDestroy(state,x,y,z)`、`blocksPropagation(state,x,y,z)`，规则变化时必须改变 `revision()`。位置、方块实体或外部状态会影响结果时保持 `requiresContextualChecks()=true`；`staticSummary` 只能返回有证据的摘要，不能用启发式猜测整段安全。

`SectionSnapshot` 复制输入数组。默认 `ClearOptions.DEFAULT` 清除流体；要使用 `new ClearOptions(false)`，必须以五参数 `SectionSnapshot(key,revision,airStateId,states,fluidStates)` 提供完整流体目标数组：无流体填空气 ID，纯流体填自身状态，含水固体填留下的流体方块状态。

`SectionPlan.kind()` 为 `NOOP`、`WHOLE_AIR`、`MASKED_CLEAR` 或 `MASKED_REPLACE`。`clearMask()` 只表示写入空气；`retainedFluidWrites()` 是流体状态 ID 到互斥掩码的映射，提交时两类写入都要处理。`WHOLE_AIR` 是规划结果，不自动赋予宿主绕过原生维护逻辑的权限。

自建后端还必须实现：意图先持久化、资源额度和背压、提交前重验领地/保护/区段修订、合法世界写入、光照与网络更新、与区块共同保存的多操作 receipt、恢复证据核对。几何游标 checkpoint 或 `FileOperationJournal` 单独存在，都不构成可安全重放世界写入的完整事务。

## 11. 接入验收要点

1. 最终 jar 同时包含 engine、正确映射的 adapter、两个 mixin 配置、refmap 和许可证；不依赖临时测试模组。
2. 在真实宿主环境检查地形与实体两套 API，确认权限、保护、流体、基岩和容器行为符合宿主策略。
3. 对地形等待 `WRITTEN` 后正常保存并重新加载确认结果；实体 `APPLIED` 不能代替保存屏障。
4. 检查暂停、取消和重启后的证据不足状态，不能通过直接改游标或强制 resume 清除保护。
5. 测量实际完成数量与完整 tick 时间，区分正在准备、原生生成、光照、保存、发包积压；只计排队速度没有实际吞吐意义。

库源码和 API 参考以当前版本为准。`org.voxelregion.forge.runtime`、`NativeAccess` 及 mixin 是内部实现，第三方业务代码不要直接依赖它们。
