# 后续功能分工与进度

来源：[设计](后续功能-design.md)、[总任务表](task-plan.md)。同阶段文件仅有一个负责人；整合文件由主代理串行修改。状态由主代理接收完成报告后精确写回三个文档，避免并行覆盖。

## 阶段A：契约

☑ PA-0，负责人/root：规格核对完成，用户允许设计可配置缺省值；契约见设计。门槛：当前已有构建基准614c2e8，原源码保持可回归。

## 阶段B：独立组件

- ☑ PB-1，负责人/root，映射T1：核心模块/偏转与配置。Owns core/、核心BE、Config、注册整合。
- ☑ PB-2，负责人/root/help_pages，已移交主线，映射T2：Owns screens/help.html、screens/head_details.html、expansion-help.js、expansion-help.css；Reads现有UI与状态契约。输出独立页面与待合并语言JSON。
- ◐ PB-3，负责人/root/aim_rendering，代码已移交、图形待验，映射T3：Owns client/、ui/AimUpdates.kt及动画模型资源；Reads BE和冻结渲染契约。避免近处双炮管与远处缺失，限制同步与重建。

门槛B：独立组件、BE/GUI入口接入后compileKotlin/processResources；核心/菜单测试。UiNetwork、ClientBindings、原GUI/语言由/root在整合阶段接入，组件负责人不编辑。

## 阶段C：战斗与自动化

- ☑ PC-1，负责人/root，映射T4/T5：插件、状态、十炮塔与全地形。
- ☑ PC-2，负责人/root/ae2_automation，已移交主线，映射T7：AE2源码核对后可选适配和核心外部输出，注册/构建由/root接入。
- ☑ PC-3，负责人/root，已整合，映射T8：原生废弃基地、loot与生成配置。

门槛C：配置/软依赖编译，行为与生成数据测试；新的数值假设写回设计和记录。

## 阶段D：串行整合与回归

◐ PD-1，负责人/root，映射T6/T9：注册、资源与配方、语言、JEI、旧存档、完整服务端回归、打包审核及状态更新。模型资源在此阶段从PB-3串行移交；不重写其他人未完成的变更。

## 文件责任

| 文件 | 负责人/阶段 |
|---|---|
| docs/三个计划、开发记录 | /root，全程进度写回 |
| core/、Config、核心BE | PB-1 /root |
| 新帮助与详情HTML/JS/CSS | PB-2 |
| client/、AimUpdates、动画模型 | PB-3 → PD-1串行移交 |
| 其他BE、TurretStatsTable、ModuleEffect、AttackBehavior、Combat、事件 | PC-1 /root |
| AE2适配 | PC-2，后续分配 |
| worldgen/、结构、生成战利品 | PC-3，后续分配 |
| UiNetwork、ClientBindings、原GUI、注册、语言与recipeJSON、build.gradle | /root串行整合 |

每位执行者只改Owns文件，不撤销他人变更。涉及共享契约或他人文件时先报告主代理；主代理分阶段接入并记录，不通过空实现掩盖未完成项。

## 2026-10-15串行整合写回

各组件已移交，当前代码整合统一由/root负责。/root/generic_target_audit只执行确定性的服务端回归与结果报告；/root/aui_layout_slots只调查客户端日志及当前AUI输入字节码，不编辑项目或启动客户端。37类插件和10炮塔由/root/expanded_modules协作后移交。共享注册、资源、网络、GUI、文档和最终产物由主线审核；当前客户端取物故障仍未闭环，不能把独立组件完成解释为可交付完成。
