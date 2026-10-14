# 依赖记录

锁定日期：2026-10-14。平台：Minecraft 1.20.1 / Forge 47.4.23 / Java 17。

| 依赖 | 版本 | Maven 坐标 |
|:---|:---|:---|
| Kotlin for Forge | 4.12.0 | `thedarkcolour:kotlinforforge:4.12.0` |
| ApricityUI | 1.2.7 | `com.sighs:ApricityUI-forge-1.20.1:1.2.7` |
| KubeJS | 2001.6.5-build.26 | `dev.latvian.mods:kubejs-forge:2001.6.5-build.26` |
| JEI | 15.62.0.219 | `mezz.jei:jei-1.20.1-forge-api:15.62.0.219`（编译），同版本 `jei-1.20.1-forge`（开发运行） |

KubeJS 通过 Modrinth 官方 API，按 `forge`、`1.20.1`、`release` 过滤后取发布时间最新项。
[版本 hVR2xUSr](https://modrinth.com/mod/kubejs/version/hVR2xUSr) 发布时间为 2026-05-01；版本号 `2001.6.5-build.26+forge`，Maven 使用不含 loader 后缀的版本。
其官方 Maven 元数据负责管理传递前置，本工程不额外引入功能库。

JEI 版本来源：[官方 Maven 元数据](https://maven.blamejared.com/mezz/jei/jei-1.20.1-forge-api/maven-metadata.xml)。

## JEI 准入记录

依赖名：JEI；版本：15.62.0.219；许可证：MIT（最终设计文档 §1.7.4 已评估通过）。
引入目的：后续配方查询。本次只配置依赖，尚未实现插件。
若不引入：需自行制作配方查询界面与兼容逻辑，维护成本更高。
API 稳定性：指定 Minecraft 分支的 Forge API；版本锁定，不自动更新。
版本跟随成本：后续迁移时单独核对，无运行时下载。
是否硬依赖：否；`compileOnly` API + `runtimeOnly` 开发运行依赖，可选依赖不写入 `mods.toml`。
出错可控性：后续通过 `@JeiPlugin` 独立接入，主入口不引用 JEI 类。
与 AUI / KFF / KubeJS 冲突：当前无交互代码；实际兼容性待游戏内测试。
是否引入新语言：否。
结论：沿用文档已批准的用途；本次只准备依赖配置，未宣称配方查询已完成。
