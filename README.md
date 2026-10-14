# 炮塔防线（Turret Defense）

以基地核心为枢纽的科技防御炮塔模组。当前是工作区初始化阶段，尚未实现炮塔玩法。

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
按用户要求才启动客户端；本次未进行游戏内或服务端功能验收。

## 美术占位

首期复用原版模型、材质、粒子与声音；后续由用户补充正式美术。
已放入通用占位模型，尚未关联注册内容。位置见 [art/README.md](art/README.md)。
AUI 页面预留目录为 `src/main/resources/assets/apricityui/apricity/`。
