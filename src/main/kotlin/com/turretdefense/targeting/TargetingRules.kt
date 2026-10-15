package com.turretdefense.targeting

import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.blockentity.TurretHeadBlockEntity
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.registries.ForgeRegistries

// 物种敌对标记与当前仇恨分别判断；显式黑名单始终优先于还击。
object TargetingRules {
    private val builder = ForgeConfigSpec.Builder()
    val defaultPolicy = builder.comment("新塔头默认策略：hostile敌对与有敌意中立、retaliate仅还击、allowlist仅白名单、all_mobs所有非友方生物").define("defaultPolicy","hostile")
    val ignoreCalm = builder.comment("默认不主动攻击尚未对基地成员产生敌意的中立生物").define("ignoreCalmNeutral",true)
    val memory = builder.comment("受到生物伤害后记住还击目标的刻数").defineInRange("retaliationMemoryTicks",300,20,12000)
    val fallbackMovement = builder.comment("原生导航不能产生有效路径时启用通用碰撞移动，不按生物类型特判").define("fallbackMovement",true)
    val fallbackSpeed = builder.comment("通用移动每刻水平加速度上限").defineInRange("fallbackAcceleration",0.045,0.0,0.1)
    val stalledTicks = builder.comment("朝炮塔导航连续无进展后启用通用移动的刻数").defineInRange("navigationStalledTicks",20,5,100)
    val pathRetryTicks = builder.comment("导航停滞后重新尝试原生路径的间隔").defineInRange("navigationRetryTicks",40,10,200)
    val neutralRetaliationOnly = builder.comment("中立生物只在被炮塔攻击后才反击炮塔").define("neutralMobsRetaliationOnly",true)
    val ignoredAttackers = builder.comment("不主动攻击炮塔的生物注册ID，逗号分隔").define("ignoredAttackerIds","")
    val POLICIES = listOf("hostile","retaliate","allowlist","all_mobs")
    val SPEC: ForgeConfigSpec = builder.build()
    fun initialPolicy() = defaultPolicy.get().takeIf { it in POLICIES } ?: "hostile"
    fun friendly(core: BaseCoreBlockEntity,mob: Mob): Boolean {
        val owner = core.owner
        if (mob is TamableAnimal && mob.isTame && mob.ownerUUID?.let { it == owner || core.trusted(it) } == true) return true
        val player = owner?.let { (core.level as? ServerLevel)?.server?.playerList?.getPlayer(it) }
        return player != null && mob.isAlliedTo(player)
    }
    private fun attackingOurSide(core: BaseCoreBlockEntity,mob: Mob): Boolean {
        val target = mob.target
        if (target is Player && (target.uuid == core.owner || core.trusted(target.uuid))) return true
        val player = core.owner?.let { (core.level as? ServerLevel)?.server?.playerList?.getPlayer(it) }
        return mob is NeutralMob && player != null && mob.isAngryAt(player)
    }
    fun matches(head: TurretHeadBlockEntity,mob: Mob): Boolean {
        val core = head.boundBase?.core ?: return false
        if (!mob.isAlive || mob.isRemoved || mob.isSpectator || friendly(core,mob)) return false
        val id = ForgeRegistries.ENTITY_TYPES.getKey(mob.type) ?: return false
        if (id in head.targetDenyList) return false
        val provoked = head.retaliationTarget == mob.uuid && (head.level?.gameTime ?: 0) <= head.retaliationUntil
        return when (head.targetPolicy) {
            "retaliate" -> provoked || attackingOurSide(core,mob)
            "allowlist" -> id in head.targetAllowList
            "all_mobs" -> true
            else -> provoked || attackingOurSide(core,mob) || (mob is Enemy && (mob !is NeutralMob || !ignoreCalm.get()))
        }
    }
    fun candidate(core: BaseCoreBlockEntity,mob: Mob) = core.managedBases.values.any { base -> !base.isRemoved && base.heads.values.any { matches(it,mob) } }
    fun canAttackTurrets(mob: Mob,now: Long): Boolean {
        val id = ForgeRegistries.ENTITY_TYPES.getKey(mob.type)?.toString() ?: return false
        if (ignoredAttackers.get().split(',').any { it.trim() == id }) return false
        if ((mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)?.value ?: 0.0) <= 0.0) return false
        val provoked = mob.persistentData.contains("TurretAggressionUntil") && mob.persistentData.getLong("TurretAggressionUntil") >= now
        if (mob is NeutralMob && neutralRetaliationOnly.get()) return provoked
        return mob is Enemy || provoked
    }
    fun parseList(value: String): Set<ResourceLocation> = value.split(',', ';', ' ', '\n').filter { it.isNotBlank() }.mapNotNull { ResourceLocation.tryParse(it.trim()) }.toSet()
}
