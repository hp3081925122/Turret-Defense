package com.turretdefense.worldgen

import net.minecraftforge.common.ForgeConfigSpec

// 自然生成只在原生候选区块执行，配置改变不会回填已经探索的区域。
object WorldgenConfig {
    private val builder = ForgeConfigSpec.Builder().push("ruinedBase")
    val enabled = builder.comment("允许在新生成的区块自然生成废弃基地").define("enabled", true)
    val generationChance = builder.comment("每个符合地形和群系要求的原生候选位置通过概率").defineInRange("generationChance", 0.75, 0.0, 1.0)
    val minimumSurfaceY = builder.comment("允许生成的最低地表高度；同时排除低于海平面的地形").defineInRange("minimumSurfaceY", 63, -64, 320)
    val maximumSurfaceY = builder.comment("允许生成的最高地表高度").defineInRange("maximumSurfaceY", 180, -64, 320)
    val maximumSlope = builder.comment("中心及四角地表高度的最大允许差值").defineInRange("maximumSlope", 3, 0, 16)
    val suppliesEnabled = builder.comment("废弃基地箱子是否使用补给战利品表；关闭时保留空箱").define("suppliesEnabled", true)
    val extraSupplyChestChance = builder.comment("第二个补给箱获得战利品的概率；第一个补给箱默认保留").defineInRange("extraSupplyChestChance", 0.35, 0.0, 1.0)
    val SPEC: ForgeConfigSpec = builder.pop().build()
}
