package com.turretdefense.turret

import net.minecraftforge.common.ForgeConfigSpec

// 只补充归档没有定义的弹速、视觉轨迹和控制维持参数，不改已有伤害与持续时间。
object AdvancedCombatConfig {
    private val builder = ForgeConfigSpec.Builder()
    val fallSpeed = builder.comment("重力浮空结束后的碰撞下降速度，格每刻").defineInRange("gravitonFallSpeed",0.25,0.05,0.5)
    val underwaterArcRange = builder.comment("电弧水下跳跃距离倍率").defineInRange("underwaterArcRangeMultiplier",1.5,1.0,4.0)
    val mortarSpeed = builder.comment("迫击炮模拟弹速，格每刻").defineInRange("mortarSpeed",2.5,0.25,16.0)
    val arcHeight = builder.comment("迫击抛物线相对水平距离的拱高").defineInRange("mortarArcHeight",0.35,0.05,1.0)
    val arcSegments = builder.comment("每条迫击弹道最多碰撞采样段数").defineInRange("mortarArcSegments",48,8,128)
    val pullTicks = builder.comment("引力落点牵引持续刻数").defineInRange("gravityPullTicks",20,1,100)
    val pullStrength = builder.comment("每刻牵引强度，受击退抗性影响").defineInRange("gravityPullStrength",0.08,0.01,0.3)
    val sniperSpeed = builder.comment("狙击弹模拟弹速，格每刻；水中乘0.7").defineInRange("sniperSpeed",6.0,0.5,32.0)
    val fusionAmmoIntervalTicks = builder.comment("聚变持续束两次耗弹最短间隔刻数").defineInRange("fusionAmmoIntervalTicks",20,1,200)
    val phaseCritical = builder.comment("相位隐形首击伤害倍率").defineInRange("phaseAmbushMultiplier",2.0,1.0,5.0)
    val phaseReveal = builder.comment("相位开火或隐形结束后显形刻数").defineInRange("phaseRevealTicks",40,1,200)
    val cryoDuration = builder.comment("凝霜减速持续刻数").defineInRange("cryoSlowTicks",60,20,600)
    val maxAreaTargets = builder.comment("一次范围攻击最多目标数").defineInRange("maxAreaTargets",64,1,256)
    val poolLimit = builder.comment("每维度同时维持的酸池上限").defineInRange("maxAcidPools",128,1,1024)
    val poolDamage = builder.comment("死亡酸池每秒伤害相对原腐蚀DPS").defineInRange("acidPoolDamageMultiplier",1.0,0.1,5.0)
    val landingTimeout = builder.comment("浮空结束后等待落地的最多刻数").defineInRange("landingTimeoutTicks",200,20,1200)
    val SPEC: ForgeConfigSpec = builder.build()
}
