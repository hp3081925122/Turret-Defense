# 炮塔防线（Turret Defense）

以基地核心为枢纽的科技防御炮塔模组。首期代码与占位资源已接入，构建和14项独立服务端逻辑测试已通过；客户端交互尚未验收。按用户最新要求，性能指标作为参考，当前优化先到这里，保留实测数据。当前为开发测试版本。

| 项目 | 值 |
|:---|:---|
| Minecraft / Forge | 1.20.1 / 47.4.23 |
| Java / Kotlin | 17 / 2.2.21 |
| ID / 包名 | `turretdefense` / `com.turretdefense` |
| 版本 / 许可证 | 0.0.1 / MIT |

开发依据：[最终文档 v2.0](docs/炮塔防线-开发文档-v2.0-约束版.md)。原样保存，第一版仅作为 `docs/archive/` 下的历史参考。
文档内部冲突和本次准备状态见 [工作区初始化记录](docs/工作区初始化记录.md)。

## 前置

- [Kotlin for Forge](https://www.curseforge.com/minecraft/mc-mods/kotlin-for-forge)：4.12.0，客户端和服务端必需。
- [ApricityUI](https://doc.sighs.cc/ApricityUI/)：1.20.1 Forge 1.2.7，客户端和服务端必需。
- [KubeJS](https://modrinth.com/mod/kubejs/version/hVR2xUSr)：2001.6.5-build.26，客户端必需。其传递前置由官方仓库元数据管理。
- [JEI](https://www.curseforge.com/minecraft/mc-mods/jei)：15.62.0.219，开发运行环境安装，玩家可不安装；未写入 `mods.toml`。

依赖已写入 `gradle.properties`，来源见 [DEPENDENCIES.md](DEPENDENCIES.md)。

## 开发

使用 JDK 17，在本目录执行 `./gradlew.bat compileKotlin processResources` 检查基础工程，或 `./gradlew.bat build` 构建。
服务端自动检查执行 `./gradlew.bat runGameTestServer`，独立运行目录与日志位于 `D:/AI临时文件/turret-defense-dev/`。按用户要求才启动客户端。构建、服务端测试和客户端游戏内验收分别记录在 [首期开发记录](docs/首期开发记录.md)。

## 当前玩法

- 基地核心燃料发电、FE 输入与无线分配；默认管理 32 个底座，T5 管理半径128格。
- 五种塔头各五等级：脉冲、粒子、榴弹、激光、电弧。塔头有独立生命和次数护盾；底座按等级支持1/2/3/4/6个面。
- 核心18格弹药库；高级激光只耗能，其他T4/T5塔头同时耗能与型号弹药。
- 底座32格：5插件槽、27战利品槽。核心46格：燃料1格、弹药18格、战利品27格。
- 击杀物品直接进缓存，经验每7点压缩成一瓶，余数保存；普通塔头损毁保留共享插件。
- T1普通合成；高级等级在界面精确预留背包材料并累计支付FE，支持跨缓存容量的升级。取消只退材料，不退已经支付的FE。
- 怪物攻击、外部爆炸和弹射物受击；核心裁决与殉爆；所有本模组制造的爆炸只伤害实体。
- 按最终文档，核心被拆除或摧毁会引发64格内已加载炮塔的殉爆，包含其他核心或玩家所属的炮塔。正式基地使用前请先在测试世界确认影响范围。
- `/turretdefense perf` 显示真实探针采样。仅在专用测试世界使用 `/turretdefense debug setupStress <数量>`，命令会放置方块并生成400只僵尸。200塔头需要 `maxBasesPerCore >= 34`。

美术、UI和数值尚未客户端游戏内验收。护盾按最终文档原值实现，未擅自重定标。配方和冲突裁决见 [首期配方与裁决](docs/首期配方与裁决.md)。完整验证结果、性能数据及待测项目见 [首期开发记录](docs/首期开发记录.md)。

## 美术占位

首期复用原版模型、材质、粒子与声音；后续由用户补充正式美术。
已关联7个方块状态、35个等级方块模型和55个物品模型。位置见 [art/README.md](art/README.md)。
AUI 页面目录为 `src/main/resources/assets/apricityui/apricity/`；没有复制原版纹理文件。
