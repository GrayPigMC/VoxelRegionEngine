# 范围实体操作 API

Forge 1.20.1 适配器提供独立入口 `org.voxelregion.forge.ForgeEntityOperations`。宿主仍通过 `ForgeRegionEngine.install(...)` 安装一次运行时；所有提交、查询和取消调用均在对应服务器线程执行，宿主负责调用权限。纯算法库不依赖 Minecraft 实体类型。

该 API 与地形清理独立。宿主可以只清理实体，也可以在自己的爆炸流程中同时提交地形操作和实体操作；两者的进度分别查询。增加 API 不会改变已存在的地形 `Options` 构造器，也不会让以前的爆炸自动杀死玩家。

## 调用

```java
import org.voxelregion.forge.ForgeEntityOperations;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.Point3;

// radius2 使用半格单位：400 表示半径 200 格。
RegionGeometry area = new RegionGeometry.Ball(
        Point3.of(center.getX() + 0.5, center.getY() + 0.5,
                  center.getZ() + 0.5), 400);

var handle = ForgeEntityOperations.submit(level, area,
        ForgeEntityOperations.Options.DEFAULT);
var progress = ForgeEntityOperations.status(level, handle.id());

// 需要取消时，在后续服务器 tick 调用。
boolean cancelled = ForgeEntityOperations.cancel(level, handle.id());
```

默认 `Options(true, LOAD_EXISTING_SAVED, KILL)` **包含玩家**，并读取范围内尚未加载的已保存实体。需要排除玩家时显式指定：

```java
var options = new ForgeEntityOperations.Options(false,
        ForgeEntityOperations.Scope.LOAD_EXISTING_SAVED,
        ForgeEntityOperations.Mode.KILL);
```

| 选项 | 行为 |
|---|---|
| `includePlayers=true` | 玩家也属于目标，遵循原生死亡流程。 |
| `includePlayers=false` | 保留玩家；仍处理范围内其他实体。 |
| `LOADED_ONLY` | 只处理当前已加载到原生实体管理器的实体，不读取未加载的实体存档。 |
| `LOAD_EXISTING_SAVED` | 通过原生实体存储异步读取已保存实体；扫描器不为寻找实体而生成地形。 |
| `KILL` | 调用原生死亡路径；保留原生事件、掉落与模组回调语义，这些回调可能自行读取或加载地形。 |
| `DISCARD` | 直接移除非玩家实体，避免主动触发生物死亡掉落；包含玩家时，玩家仍走原生死亡流程。 |

球、盒、柱和组合几何都使用实体位置的连续坐标判断，不以所在方块中心代替实体位置，也不以碰撞箱与选区相交作为全部命中条件。几何内部的差集空洞会保留实体。区块扫描采用保守范围，因此球面恰好落在相邻区块边界上的实体也会进入精确判断。

## 执行和完成含义

执行按区块和实体批次分帧，与地形操作共享服务器时间预算。实体文件读取使用原生管理器的异步加载流程；处理前后保留其加载、追踪、移除及保存回调，不直接修改实体 region 文件。未加载区块中不属于选区的实体会保留并交回原生管理器。

读取实体的过程不申请地形票据，但 `KILL` 不等于纯数据删除：原生伤害/死亡和模组回调可以查询世界状态。Forge 1.20.1 中，生物死亡姿态会触发尺寸刷新，再通过 `setPosRaw` 对已经加入世界的实体调用 `level.getChunk(...)`；即使位置没有变化，也会加载地形。实际验证观察到了隐藏生物正常击杀后的这一行为。需要直接清除非玩家实体、避免主动执行死亡掉落流程时选择 `DISCARD`；自定义实体的移除回调仍遵循原生行为。预算为协作式限制，单个原生回调不能中途抢占。

状态为 `QUEUED`、`RUNNING`、`APPLIED`、`CANCELLED` 或 `FAILED`。`APPLIED` 表示这次扫描中的实体动作已执行，**不表示实体文件已完成磁盘同步**；实体持久化仍遵循原生自动保存及正常停服保存。调用方不能把它当成地形操作的 `WRITTEN` 存储屏障。

这是一次有界扫描，不是持续禁止实体存在的区域。实体在轮到相应区块时进行选择，执行前重新检查位置、维度和许可；新生成的死亡掉落、此后进入区域或重生的玩家不会被无限追杀。运行中的实体操作不跨重启自动恢复。取消保留已经执行的动作，并释放尚未处理的读取资源。

玩家指当前世界中的在线玩家实体；离线玩家档案不属于实体区块数据，不会被删除。状态中的 `examined` 是检查数，`affected` 是实际死亡或移除数，`surviving` 是调用后仍存活的目标数，另报告跳过玩家、受保护区块、已扫描区块和待处理区块数。

`KILL` 依然尊重模组对原生死亡流程的修改。拒绝死亡或仍存活的目标通过状态中的存活计数报告，不以“尝试调用过”冒充已经杀死。调用异常、实体读取失败或超时会报告失败，不能将未读到的数据当成空区块。范围、排队数量和候选容量有界，超出限制不会静默截断成成功。

实体操作沿用独立的实体保护边界：所有受保护领地都跳过，保护接口不可用时报错；不提供管理员身份自动绕过。世界边界外不处理。API 是宿主侧受信任操作，不能直接将未认证客户端请求传给它。

验证范围与实际制品记录见 [验证文档](validation-whole-chunk.md)。
