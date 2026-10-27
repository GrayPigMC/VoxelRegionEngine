# VoxelRegionEngine

Java 17 的体素区域操作库，采用 MPL-2.0。面向大范围爆炸、开挖和区域清除；算法核心不依赖 Minecraft、Forge、FTB 或 GTLCore。

这是 **0.1.0 开发基线**，不是对设计草案全部功能的完成声明。原始需求见 [DESIGN-v0.1.md](DESIGN-v0.1.md)；实际接口以源码和 [API 说明](docs/api.md) 为准。

## 已实现

- 球、盒、竖直圆柱：精确的体素中心判定、三态分类、连续 Y 行段。
- Region → Chunk 惰性遍历，负坐标及世界高度裁剪；不建立整个爆炸的方块列表。
- Section 的不可变掩码、空气跳过、静态保护证明和动态逐格保护。
- `clearFluids` 默认开启；关闭时保留纯流体，删除可破坏的含水固体后留下水，计划显式记录不同替换目标。
- 冻结阻挡盒输入的 `AXIS_6` 遮挡内核；BVH 按 Section 查询。宿主必须先取得一致的阻挡快照。
- 共享 tick 预算、公平轮转、加载/保存/待处理内存资源限额。
- 有校验和的任务意图日志、多个操作的 Chunk receipts、保守恢复决策及提交修订号检查。
- 独立的 Forge 1.20.1 测试 mod，可调半径和引信的测试炸药、任务管理命令。它是适配验证工具，**不是要求玩家额外安装的外挂核心**。

## 当前边界

核心的 `WHOLE_AIR` 是经过几何和保护检查的计划，**不等于 Minecraft 后端已能安全裸替换 Section**。测试后端仍通过原生写入维护方块实体、POI、高度图和光照流程。

当前没有实现合法空 Chunk 物化、受保护地形专用生成、AXIS_26、射线精修、完整球面推进和自动崩溃续跑。未生成区域走受控正常生成；无法证明恢复安全时暂停。不能把任务“已应用”当成“世界已持久化且光照已收敛”。FTB 联动、机器、流体、客户端和重启行为仍需在实际整合包中验证。

首版几何使用 **半格定点输入**：中心和半径必须是 0.5 的整数倍，内部放大两倍后用 `long` 平方距离；不悄悄四舍五入任意小数。可调炸药放在方块中心。方块坐标统一为半开范围，空间取样统一为方块中心。

## 构建与测试

```text
./gradlew build
./gradlew regression
./gradlew geometryBenchmark
./gradlew publishToMavenLocal
```

Windows 使用 `gradlew.bat`。需要 JDK 17；主库没有第三方运行依赖。测试是可独立运行的 Java 程序，不需要下载测试框架。

库文件：`build/libs/voxel-region-engine-0.1.0.jar`。这是 Java 库，不能直接扔进 Minecraft 的 `mods` 文件夹。

Forge 测试文件的构建、命令及行为限制见 [测试 mod](tools/forge-test/README.md)。

## 对接

- [API 与完整调用顺序](docs/api.md)
- [GTLCore 与其他宿主对接](docs/host-integration.md)
- [Forge 1.20.1 适配说明](docs/forge-integration.md)
- [实现取舍、性能与验证范围](docs/design-decisions.md)

GTLCore 后续以源码模块或内嵌库依赖接入，不需要单独的可替换核心 mod。本仓库保留算法、文档和测试工具；这一轮不向 GTLCore 提交内容。
