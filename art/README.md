# 美术资源与生成脚本

按开发文档 §4.11.3，AI 绘制的资源全部由本目录的脚本可复现生成，不提交建模工具导出物。

## 炮塔方块模型 `turret_models/gen_turrets.py`

```
python art/turret_models/gen_turrets.py src/main/resources/assets/turretdefense [预览输出目录]
```

输出（会覆盖同名文件）：
- `models/block/<种类>_head_t1..t5.json`：完整模型（物品与远距离静态显示）。
- `models/block/aim/<种类>_head_tN_barrel.json`：可旋转炮管，旋转中心为 (8, 9, 8)，沿 +Y 伸出。
- `models/block/aim/<种类>_head_tN_support_<朝向>.json`：六向固定支座，支座保持四向对称，远近模型切换时不会扭转。
- `models/block/turret_base_t1..t5.json`、`base_core_t1..t5.json`。
- `textures/block/turret/*.png`：分等级的机身、饰边、炮管材质，各炮塔的发光材质，以及底座和核心贴图。

等级规则：T2 加侧装甲和角螺栓，T3 加前后装甲和支撑肋，T4 加发光条，T5 加立柱和侧发光；各炮塔的炮管随等级加长或增加部件。
新增炮塔种类时，在 `KINDS` 和 `KIND_GLOW` 中各登记一项。脚本自带同向共面检查，输出 `coplanar issues: 0` 才算通过。

## 物品图标 `item_textures/gen_items.py`

```
python art/item_textures/gen_items.py src/main/resources/assets/turretdefense/textures/item <预览图路径>
```

生成 36 个 16x16 平面物品材质。
