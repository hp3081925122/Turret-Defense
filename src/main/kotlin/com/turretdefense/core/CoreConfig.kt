package com.turretdefense.core

import net.minecraftforge.common.ForgeConfigSpec

// 补充设计的缺省数值独立配置，不改首期冻结表。
object CoreConfig {
    private val builder = ForgeConfigSpec.Builder()
    init { builder.push("deflection") }
    val enabled = builder.comment("开启基地级弹射物偏转护盾").define("enabled",true)
    val interval = builder.comment("偏转扫描间隔，单位刻").defineInRange("scanInterval",5,1,40)
    val limit = builder.comment("每次扫描最多处理弹射物数量").defineInRange("maxProjectiles",64,1,256)
    val costMultiplier = builder.comment("等级偏转耗能50/75/100/150/200的倍率").defineInRange("energyMultiplier",1.0,0.0,100.0)
    val lowEfficiency = builder.comment("低于正常供能时的偏转成功率").defineInRange("lowEfficiency",0.5,0.0,1.0)
    val criticalEfficiency = builder.comment("严重缺能时的偏转成功率").defineInRange("criticalEfficiency",0.25,0.0,1.0)
    val reflectionCooldown = builder.comment("同一弹射物两次偏转之间的刻数").defineInRange("reflectionCooldown",20,1,200)
    init { builder.pop(); builder.push("modules") }
    val duplicates = builder.comment("允许同类核心模块重复安装").define("allowDuplicateCategories",false)
    val capacity = builder.comment("每个能量扩展模块增加的容量").defineInRange("capacityBonus",10000,1,1000000)
    val generation1 = builder.comment("发电效率一级最低发电量").defineInRange("generation1",64,1,100000)
    val generation2 = builder.comment("发电效率二级最低发电量").defineInRange("generation2",128,1,100000)
    val range1 = builder.comment("范围扩展一级最低管理半径，仍受128格扫描上限约束").defineInRange("range1",64,16,128)
    val range2 = builder.comment("范围扩展二级最低管理半径").defineInRange("range2",128,16,128)
    val wirelessReserveReduction = builder.comment("无线升级减少的核心保留能量比例").defineInRange("wirelessReserveReduction",0.10,0.0,0.2)
    val shieldBonus = builder.comment("每级核心护盾强化增加的核心层数").defineInRange("shieldLayersPerLevel",10,0,1000)
    val projectionBonus = builder.comment("每级核心护盾强化增加的投射层数").defineInRange("projectionPerLevel",5,0,1000)
    val deflectionRangeBonus = builder.comment("每级核心护盾强化增加的偏转范围").defineInRange("deflectionRangePerLevel",4.0,0.0,64.0)
    val repairBonus = builder.comment("每级维修强化增加的修复倍率").defineInRange("repairMultiplierPerLevel",0.5,0.0,100.0)
    val airBonus = builder.comment("每级防空模块增加的对飞行生物伤害倍率").defineInRange("airDamagePerLevel",0.5,0.0,10.0)
    val judgmentBonus = builder.comment("裁决强化的伤害增加倍率，原生裁决保持已解锁").defineInRange("judgmentDamageBonus",0.25,0.0,10.0)
    val judgmentCooldown = builder.comment("裁决强化的冷却倍率").defineInRange("judgmentCooldownMultiplier",0.8,0.05,1.0)
    init { builder.pop(); builder.push("automation") }
    val outputEnabled = builder.comment("自动将核心战利品输出到附近容器").define("enabled",true)
    val outputRadius = builder.comment("外部容器扫描半径；默认2即5×5×5").defineInRange("outputRadius",2,1,5)
    val outputInterval = builder.comment("核心输出扫描间隔刻数").defineInRange("interval",100,20,1200)
    val outputLimit = builder.comment("每次最多传输的物品总数").defineInRange("itemsPerTransfer",256,1,4096)
    val ae2Enabled = builder.comment("有AE2时使用相邻ME网络接口").define("enableAE2",true)
    val ae2Refill = builder.comment("弹药库低于此占比时从ME补给").defineInRange("ammoRefillThreshold",0.2,0.0,1.0)
    val ae2EnergyRate = builder.comment("一AE对应多少FE").defineInRange("fePerAE",2.0,0.01,100.0)
    val ae2EnergyFraction = builder.comment("一次取能不超过网络缓存的此占比").defineInRange("maxNetworkEnergyFraction",0.30,0.0,0.30)
    val ae2EnergyLimit = builder.comment("每次自动化取能的FE上限").defineInRange("fePerTransfer",10000,0,500000000)
    init { builder.pop() }
    val SPEC: ForgeConfigSpec = builder.build()
}
