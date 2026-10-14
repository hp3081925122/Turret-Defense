# 美术占位与替换

用户要求：建模、美术先使用占位，后续由用户补充。

- `src/main/resources/assets/turretdefense/models/block/placeholder.json`：原版铁块立方体。
- `src/main/resources/assets/turretdefense/models/item/placeholder.json`：引用通用方块模型。

已注册方块与物品分别引用 `models/block/*_t1.json` 至 `*_t5.json` 和对应 `models/item/`。七个 `blockstates/` 文件驱动等级与朝向。
塔头占位模型由基座和直立炮管组成；`FACING` 指向底座，模型向相反方向伸出。
物品图标引用原版铁、石英、紫水晶、铜、火药等资源，方便区分类型。
正式资源使用稳定路径，替换模型和贴图无需改变玩法 ID。
音效与粒子引用原版注册项，不复制 Minecraft 原版资源文件。
本次不制作复杂模型；用户可将后续 Blockbench 源文件放入本目录。
