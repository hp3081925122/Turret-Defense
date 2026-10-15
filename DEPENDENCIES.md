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

## 客户端验证工具

2026-10-14核对已有Minecraft MCP工具：文件名为 `minecraft-mcp-1.20.1-forge-v0.2.0.jar`，实际 `mods.toml` 为 `mcpmod` / 0.1.1，MIT，适用Minecraft 1.20.1 Forge。来源是本机已下载工具 `C:/Users/30819/Documents/Codex/2026-06-07/1-2-3-4-5-6/outputs/minecraft-mod-mcp-jars/`；原文件保持不变，SHA-256为 `dd99df12c24cc9ee60541e5bca244d315f88dc42920702267ec00c3cb5fcf879`。

原工具HTTP监听所有网卡。本次仅在 `D:/AI临时文件/turret-defense-dev/mcp-maven/` 制作验证副本，将HTTP服务器两条绑定路径改成127.0.0.1，版本坐标为 `local.validation:minecraft-mcp-forge:0.1.1-local`，SHA-256为 `ad576ca804f9e2baa9402f1c3fb46301175512e9af9e35f12b32310bf2d9b358`。通过字节码、实际PID和监听地址核对，开发端口9786仅监听本机。游戏状态查询和真实帧缓冲截图成功；该次客户端之后正常退出，不能据此声称完成GUI交互验收。

只有显式使用 `-PwithClientMcp` 才加入开发运行依赖；`-PclientRunDirectory=绝对路径` 可指定隔离客户端。默认运行与正式JAR均不包含该工具，未写入 `mods.toml`。

## AE2软联动

2026-10-15核对Minecraft 1.20.1 Forge的AE2 15.4.11。官方标签源码：[forge/v15.4.11](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/forge/v15.4.11)，发布JAR使用`curse.maven:applied-energistics-2-223794:8991271`，原文件未修改。许可证按上游仓库LGPL-3.0及附加许可条件记录；不复制或捆绑AE2代码、资源或JAR。

引入目的是核心从ME网补充弹药、提取能量和输出战利品。编译期仅compileOnly；指定`-PwithAe2`才加入开发运行。AE2当前所需GuideME使用`org.appliedenergistics:guideme:20.1.15`，许可证见其发布包（LGPL/MIT/Apache相关声明），来源：[官方发布页](https://guideme.appliedenergistics.org/downloads/)。两者都不写入本模组mods.toml，也不随本模组发布。

已核对IGridNode/StorageHelper/IActionSource/IPlayerRegistry等当前源码接口，所有AE2类型隔离于Ae2Compat类，确认ModList中有ae2才通过延迟工厂加载。节点必须与核心同主人或主人被核心信任；先模拟再提交，返回实际转移量。

本机ForgeGradle在线解析发生TLS错误，未禁用证书验证。验证环境将原AE2 JAR原样镜像到`D:/AI临时文件/turret-defense-dev/ae2-reference/maven`，使用`local.validation:ae2-forge:15.4.11`坐标；源不存在时构建脚本回落上述官方发布坐标。镜像只为本机离线映射验证，不是二次发行。GuideME使用官方JAR和POM的本地镜像。真实网络端到端测试结果在后续功能开发记录登记，不以编译或空测试启动代替。
