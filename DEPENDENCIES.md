# 依赖记录

锁定日期：2026-10-14。平台：Minecraft 1.20.1 / Forge 47.4.23 / Java 17。

| 依赖 | 版本 | Maven 坐标 |
|:---|:---|:---|
| Kotlin for Forge | 4.12.0 | `thedarkcolour:kotlinforforge:4.12.0` |
| ApricityUI | 1.2.7 | `com.sighs:ApricityUI-forge-1.20.1:1.2.7` |
| KubeJS | 2001.6.5-build.26 | `dev.latvian.mods:kubejs-forge:2001.6.5-build.26` |
| JEI | 15.62.0.219 | 同版本 `jei-1.20.1-common-api` 与 `jei-1.20.1-forge-api`（编译），`jei-1.20.1-forge`（开发运行） |
| Rhino | 2001.2.2-build.17 | `dev.latvian.mods:rhino-forge:2001.2.2-build.17`（KubeJS 前置） |
| Architectury | 9.1.12 | `dev.architectury:architectury-forge:9.1.12`（KubeJS 前置） |

KubeJS 通过 Modrinth 官方 API，按 `forge`、`1.20.1`、`release` 过滤后取发布时间最新项。
[版本 hVR2xUSr](https://modrinth.com/mod/kubejs/version/hVR2xUSr) 发布时间为 2026-05-01；版本号 `2001.6.5-build.26+forge`，Maven 使用不含 loader 后缀的版本。
Rhino 与 Architectury 版本取自该 KubeJS 官方 POM。本工程显式使用 `fg.deobf` 登记两者，保证 Forge 开发运行能把它们识别为模组；没有额外加入功能库。

JEI 版本来源：[官方 Maven 元数据](https://maven.blamejared.com/mezz/jei/jei-1.20.1-forge-api/maven-metadata.xml)。

## JEI 准入记录

依赖名：JEI；版本：15.62.0.219；许可证：MIT（最终设计文档 §1.7.4 已评估通过）。
引入目的：显示机器界面升级配方，包含旧等级机器、精确材料数量、结果等级与累计 FE。
若不引入：需自行制作配方查询界面与兼容逻辑，维护成本更高。
API 稳定性：指定 Minecraft 分支的 Forge API；版本锁定，不自动更新。
版本跟随成本：后续迁移时单独核对，无运行时下载。
是否硬依赖：否；`compileOnly` API + `runtimeOnly` 开发运行依赖，可选依赖不写入 `mods.toml`。
出错可控性：通过 `@JeiPlugin` 独立接入，主入口不引用 JEI 类。`-PwithoutJei` 可在开发环境移除 JEI，服务端逻辑测试已通过。
与 AUI / KFF / KubeJS 冲突：专服加载及逻辑测试通过；JEI 客户端配方页面、AUI 交互仍待游戏内验收。
是否引入新语言：否。
结论：实现了指定的软依赖接入方式；没有把 JEI 写入 `mods.toml`，未把编译与专服启动当作客户端显示证据。

Forge 开发运行启用依赖 refmap 的 SRG→official 映射，映射文件由 `createSrgToMcp` 生成。KFF 官方仓库已验证可直连，保持证书校验；为无超时的 ForgeGradle 环境探测增加10秒连接和读取超时，避免离线构建也无限等待 TLS 握手。
