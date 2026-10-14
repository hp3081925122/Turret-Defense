# 美术占位与替换

用户要求：建模、美术先使用占位，后续由用户补充。

- `src/main/resources/assets/turretdefense/models/block/placeholder.json`：原版铁块立方体。
- `src/main/resources/assets/turretdefense/models/item/placeholder.json`：引用通用方块模型。

这些文件是后续资源制作入口，尚未注册游戏内方块或物品。
正式资源使用稳定路径，替换模型和贴图无需改变玩法 ID。
音效与粒子在实现功能时先引用原版注册项，不复制 Minecraft 原版资源文件。
本次不制作复杂模型；用户可将后续 Blockbench 源文件放入本目录。
