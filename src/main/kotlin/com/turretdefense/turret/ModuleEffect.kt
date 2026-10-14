package com.turretdefense.turret

import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.registry.ModContent
import net.minecraft.world.item.ItemStack

// 插件按槽位顺序执行，先处理加法项，再处理乘法项；共享缓存只在物品栏变化时更新。
sealed class ModuleEffect(val id: String,val special: Boolean = false) {
    open fun addStats(stats: TurretStats) = stats
    open fun multiplyStats(stats: TurretStats) = stats
    open fun incomingDamage(amount: Double) = amount
    open fun onHit(context: FireContext,target: net.minecraft.world.entity.LivingEntity) {}
    open fun onDamaged(head: TurretHeadBlockEntity,amount: Double) = false
    open fun onDestroyed(head: TurretHeadBlockEntity) {}
    open fun onTick(head: TurretHeadBlockEntity,now: Long) {}
    object Damage : ModuleEffect("module_damage_amp_1") {
        override fun multiplyStats(stats: TurretStats) = stats.copy(damage=stats.damage*TurretStatsTable.MODULE_DAMAGE_MULTIPLIER)
    }
    object Rate : ModuleEffect("module_fire_rate_1") {
        override fun multiplyStats(stats: TurretStats) = stats.copy(cooldownTicks=(stats.cooldownTicks*TurretStatsTable.MODULE_COOLDOWN_MULTIPLIER).toInt().coerceAtLeast(1))
    }
    object Shield : ModuleEffect("module_shield_boost_1") {
        override fun addStats(stats: TurretStats) = stats.copy(shield=stats.shield+TurretStatsTable.MODULE_SHIELD_BONUS)
    }
    object Armor : ModuleEffect("module_armor_plate") {
        override fun incomingDamage(amount: Double) = amount*TurretStatsTable.MODULE_ARMOR_MULTIPLIER
    }
    object Priority : ModuleEffect("module_target_priority")
    object Saver : ModuleEffect("module_energy_saver_1") {
        override fun multiplyStats(stats: TurretStats) = stats.copy(energy=stats.energy*TurretStatsTable.MODULE_ENERGY_MULTIPLIER)
    }
    object SelfDestruct : ModuleEffect("module_self_destruct",true) {
        override fun onDestroyed(head: TurretHeadBlockEntity) { head.selfDestruct() }
    }
    object Overload : ModuleEffect("module_overload_core",true)
    companion object {
        val ALL = listOf(Damage,Rate,Shield,Armor,Priority,Saver,SelfDestruct,Overload)
        fun from(stack: ItemStack) = ALL.firstOrNull { stack.`is`(ModContent.item(it.id)) }
    }
}
