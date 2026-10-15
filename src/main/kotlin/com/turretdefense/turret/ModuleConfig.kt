package com.turretdefense.turret

import net.minecraftforge.common.ForgeConfigSpec

// 附录中已有数值保持冻结；这里只提供未定义的保守初始值和工作量上限。
object ModuleConfig {
    private val builder = ForgeConfigSpec.Builder()
    val secondaryDamage = builder.comment("插件次级伤害相对主命中的倍率").defineInRange("secondaryDamageMultiplier",0.35,0.0,4.0)
    val chainRange = builder.comment("插件额外连锁半径").defineInRange("chainRange",6.0,1.0,32.0)
    val maxSecondary = builder.comment("单次命中最多附加目标数").defineInRange("maxSecondaryTargets",16,1,64)
    val slowAmplifier = builder.comment("冰冻弹头减速等级减一").defineInRange("slowAmplifier",1,0,4)
    val poisonTicks = builder.comment("毒弹头持续刻数").defineInRange("poisonTicks",100,20,1200)
    val corrosionTicks = builder.comment("酸液护甲削弱持续刻数").defineInRange("corrosionTicks",100,20,1200)
    val gravityStrength = builder.comment("引力弹头每次拉拽强度，受击退抗性影响").defineInRange("gravityStrength",0.25,0.0,0.6)
    val gravityRadius = builder.comment("引力弹头牵引半径").defineInRange("gravityRadius",3.0,1.0,16.0)
    val repairThreshold = builder.comment("维修启动生命比例").defineInRange("repairThreshold",0.5,0.05,1.0)
    val repairHealth = builder.comment("维修每秒恢复生命").defineInRange("repairHealth",1.0,0.1,20.0)
    val repairEnergy = builder.comment("维修每秒FE").defineInRange("repairEnergy",50,1,100000)
    val camouflageBlock = builder.comment("伪装方块注册ID").define("camouflageBlock","minecraft:stone")
    val camouflageDelay = builder.comment("开火后再次伪装的延迟刻数").defineInRange("camouflageDelay",100,0,1200)
    val deflectionInterval = builder.comment("弹射偏转检查间隔刻数").defineInRange("deflectionInterval",5,1,40)
    val deflectionEnergy = builder.comment("偏转一枚敌方弹射物的FE").defineInRange("deflectionEnergy",10,0,100000)
    val deflectionLimit = builder.comment("每次偏转最多弹射物数").defineInRange("deflectionLimit",16,1,64)
    val modeDamage = builder.comment("第二攻击模式伤害倍率").defineInRange("alternateModeDamage",1.5,0.1,5.0)
    val modeCooldown = builder.comment("第二攻击模式间隔倍率").defineInRange("alternateModeCooldown",1.5,0.1,5.0)
    val barrageAngle = builder.comment("弹幕扇形夹角").defineInRange("barrageAngleDegrees",50.0,5.0,120.0)
    val clusterSpread = builder.comment("集束子弹药散布半径").defineInRange("clusterSpread",3.0,0.5,12.0)
    val clusterRadius = builder.comment("集束子弹药伤害半径").defineInRange("clusterRadius",1.5,0.5,6.0)
    val SPEC: ForgeConfigSpec = builder.build()
}
