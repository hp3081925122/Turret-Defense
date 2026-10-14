package com.turretdefense.config

import net.minecraftforge.common.ForgeConfigSpec

// 配置只暴露全局倍率与性能预算；原始等级数值保存在数值表。
object Config {
    private val builder = ForgeConfigSpec.Builder()
    private fun integer(name: String, value: Int, min: Int, max: Int, note: String) = builder.comment(note).defineInRange(name,value,min,max)
    private fun decimal(name: String, value: Double, min: Double, max: Double, note: String) = builder.comment(note).defineInRange(name,value,min,max)
    private fun flag(name: String, value: Boolean, note: String) = builder.comment(note).define(name,value)
    init { builder.push("performance") }
    val maxBases = integer("maxBasesPerCore",32,8,64,"每个核心最多管理的底座数量")
    val scanInterval = integer("scanIntervalTicks",20,10,40,"集中扫描基础间隔，单位为游戏刻")
    val scanRadiusCap = integer("scanRadiusCap",128,16,128,"扫描半径上限，默认保留完整的128格")
    val scanVerticalRange = integer("scanVerticalRange",64,16,128,"核心上下扫描范围")
    val scanBudget = decimal("scanCostBudgetMs",3.0,0.1,100.0,"扫描耗时超过此值时自动降频")
    val highLoad = integer("highLoadEntityCount",150,1,10000,"实体数量超过此值时降低扫描频率")
    val lowLoad = integer("lowLoadEntityCount",50,0,9999,"实体数量低于此值时恢复扫描频率")
    val maxFires = integer("maxFiresPerTick",64,1,64,"全服务器每刻开火次数硬上限，不允许超过64")
    val transferInterval = integer("lootTransferInterval",100,40,400,"底座战利品传输间隔")
    val particleDistance = integer("particleRenderDistance",64,16,128,"开火粒子的最大可见距离")
    val maxSounds = integer("maxSoundPerBasePerSecond",4,0,20,"每个底座每秒最多播放音效次数")
    init { builder.pop(); builder.push("energy") }
    val externalFE = flag("enableExternalFE",true,"允许核心接受外部能量输入")
    val lowThreshold = decimal("lowThreshold",0.50,0.01,1.0,"节能状态能量占比阈值")
    val criticalThreshold = decimal("criticalThreshold",0.20,0.001,1.0,"严重不足状态能量占比阈值")
    val emergencyThreshold = decimal("emergencyThreshold",0.05,0.001,1.0,"濒临耗尽状态能量占比阈值")
    val emergencyFireRate = decimal("emergencyFireRate",0.125,0.01,1.0,"原文未定量的濒临耗尽最低射速倍率")
    init { builder.pop(); builder.push("shield") }
    val shieldMultiplier = decimal("layersMultiplier",1.0,0.0,100.0,"次数制护盾层数倍率，零为禁用护盾")
    val projectionMultiplier = decimal("projectionMultiplier",1.0,0.0,100.0,"核心护盾投射倍率")
    val regenDelay = integer("regenDelaySeconds",5,0,3600,"受击后护盾再生等待秒数")
    val regenInterval = integer("regenIntervalSeconds",10,1,3600,"护盾层数恢复间隔秒数")
    init { builder.pop(); builder.push("loot") }
    val collectLoot = flag("enabled",true,"将炮塔击杀的掉落物直接存入底座缓存")
    val xpRatio = integer("xpToBottleRatio",7,1,20,"转换一瓶附魔之瓶需要的经验值")
    val dropOnFull = flag("dropOnCoreFull",true,"核心缓冲区已满时将待传输物品掉落在核心旁")
    init { builder.pop(); builder.push("combat") }
    val monstersAttack = flag("monstersAttackTurrets",true,"怪物在没有玩家目标时主动攻击塔头")
    val damageMultiplier = decimal("turretDamageMultiplier",1.0,0.0,100.0,"全部炮塔的伤害倍率")
    val healthMultiplier = decimal("turretHealthMultiplier",1.0,0.01,100.0,"全部炮塔与核心的生命倍率")
    val flightTicks = integer("ballisticFlightTicks",12,1,200,"榴弹命中前的飞行刻数")
    val arcDistance = decimal("arcJumpDistanceMultiplier",1.0,0.01,10.0,"电弧跳跃距离倍率")
    val chainDepth = integer("judgmentMaxChainDepth",3,0,3,"终焉裁决击杀连锁的最大深度")
    init { builder.pop(); builder.push("module") }
    val specialLimit = integer("specialModuleLimitPerBase",1,1,1,"每个底座最多安装一个特殊插件")
    init { builder.pop() }
    val SPEC: ForgeConfigSpec = builder.build()
}
