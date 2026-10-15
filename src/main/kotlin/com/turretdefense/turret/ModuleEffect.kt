package com.turretdefense.turret

import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.combat.ControlEffects
import com.turretdefense.core.DeflectionShield
import com.turretdefense.targeting.TargetingRules
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.roundToInt

// 插件按槽位顺序先加后乘；每种效果自行实现钩子，运行器不按具体插件写分支。
sealed class ModuleEffect(val id: String,val special: Boolean = false,val typeId: String = id,val grade: Int = 1) {
    open fun addStats(stats: TurretStats) = stats
    open fun multiplyStats(stats: TurretStats) = stats
    open fun stateStats(head: TurretHeadBlockEntity,stats: TurretStats) = stats
    open fun incomingDamage(amount: Double) = amount
    open fun outgoingDamage(context: FireContext,target: LivingEntity,amount: Double) = amount
    open fun onHit(context: FireContext,target: LivingEntity) {}
    open fun afterHit(context: FireContext,target: LivingEntity,damage: Double) { onHit(context,target) }
    open fun onDamaged(head: TurretHeadBlockEntity,amount: Double) = false
    open fun onDamaged(head: TurretHeadBlockEntity,amount: Double,attacker: LivingEntity?) = onDamaged(head,amount)
    open fun onDestroyed(head: TurretHeadBlockEntity) {}
    open fun onTick(head: TurretHeadBlockEntity,now: Long) {}
    open fun ready(head: TurretHeadBlockEntity,now: Long) = true
    open fun fired(head: TurretHeadBlockEntity,now: Long) {}
    open fun saveAmmo(head: TurretHeadBlockEntity) = false
    open fun armorPenetration() = 0.0
    open fun homing() = false
    open fun hidden(head: TurretHeadBlockEntity,now: Long) = false
    open fun visualBlock(head: TurretHeadBlockEntity): BlockState? = null
    open fun radarBonus() = 0.0
    open fun detectHidden() = false
    open fun continuousRate(head: TurretHeadBlockEntity) = 1.0
    open fun targetAllowed(head: TurretHeadBlockEntity,target: Mob) = true
    open fun allyDestroyed(head: TurretHeadBlockEntity) {}
    open fun switchMode(head: TurretHeadBlockEntity) = false

    open class DamageGrade(grade: Int) : ModuleEffect("module_damage_amp_$grade",typeId="damage",grade=grade) {
        override fun multiplyStats(stats: TurretStats) = stats.copy(damage=stats.damage*(1.0+0.25*grade))
    }
    object Damage : DamageGrade(1)
    open class RateGrade(grade: Int) : ModuleEffect("module_fire_rate_$grade",typeId="fire_rate",grade=grade) {
        override fun multiplyStats(stats: TurretStats) = stats.copy(cooldownTicks=(stats.cooldownTicks*listOf(0.8,0.65,0.5)[grade-1]).toInt().coerceAtLeast(1))
        override fun continuousRate(head: TurretHeadBlockEntity) = 1.0/listOf(0.8,0.65,0.5)[grade-1]
    }
    object Rate : RateGrade(1)
    class RangeGrade(grade: Int) : ModuleEffect("module_range_$grade",typeId="range",grade=grade) {
        override fun addStats(stats: TurretStats) = stats.copy(range=stats.range+listOf(4.0,8.0,16.0)[grade-1])
    }
    object Pierce : ModuleEffect("module_piercing_round") {
        override fun stateStats(head: TurretHeadBlockEntity,stats: TurretStats) = if (head.kind.id in listOf("pulse","particle","arbiter","sniper")) stats.copy(pierceCount=stats.pierceCount+1) else stats
    }
    object Explosive : ModuleEffect("module_explosive_round") {
        override fun afterHit(context: FireContext,target: LivingEntity,damage: Double) {
            if (context.head.kind.id in listOf("pulse","particle","arbiter")) ModuleRuntime.secondary(context,target.position(),target,ModuleConfig.maxSecondary.get(),3.0,damage*ModuleConfig.secondaryDamage.get())
        }
    }
    object Burning : ModuleEffect("module_incendiary_round") {
        override fun onHit(context: FireContext,target: LivingEntity) { target.setSecondsOnFire(5) }
    }
    object Freezing : ModuleEffect("module_freezing_round") {
        override fun onHit(context: FireContext,target: LivingEntity) { ControlEffects.slow(target,60,ModuleConfig.slowAmplifier.get()) }
    }
    object Shocking : ModuleEffect("module_shocking_round") {
        override fun onHit(context: FireContext,target: LivingEntity) { ControlEffects.stun(target,10) }
    }
    object Chain : ModuleEffect("module_chain_round") {
        override fun afterHit(context: FireContext,target: LivingEntity,damage: Double) {
            if (context.head.kind.id in listOf("particle","arbiter")) ModuleRuntime.bounce(context,target,2,ModuleConfig.chainRange.get(),damage*ModuleConfig.secondaryDamage.get())
        }
    }
    object Gravity : ModuleEffect("module_gravity_round") {
        override fun onHit(context: FireContext,target: LivingEntity) {
            if (context.head.kind.id !in listOf("gravity","graviton")) return
            val radius = ModuleConfig.gravityRadius.get()
            for (enemy in context.snapshot.targets.asSequence().filter { context.valid(it) && it.distanceToSqr(target.position()) <= radius*radius }.take(ModuleConfig.maxSecondary.get())) {
                val resistance = enemy.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE).coerceIn(0.0,1.0)
                val pull = target.position().subtract(enemy.position())
                if (pull.lengthSqr() > 0.01) { enemy.deltaMovement = enemy.deltaMovement.add(pull.normalize().scale(ModuleConfig.gravityStrength.get()*(1.0-resistance))); enemy.hasImpulse = true }
            }
        }
    }
    object Poison : ModuleEffect("module_poison_round") {
        override fun onHit(context: FireContext,target: LivingEntity) { target.addEffect(MobEffectInstance(MobEffects.POISON,ModuleConfig.poisonTicks.get(),0,false,true)) }
    }
    object Leech : ModuleEffect("module_leech_round") {
        override fun afterHit(context: FireContext,target: LivingEntity,damage: Double) { context.head.repair(damage*0.1) }
    }
    open class ShieldGrade(grade: Int) : ModuleEffect("module_shield_boost_$grade",typeId="shield",grade=grade) {
        override fun addStats(stats: TurretStats) = stats.copy(shield=stats.shield+10*grade)
    }
    object Shield : ShieldGrade(1)
    object ShieldRegen : ModuleEffect("module_shield_regenerator") {
        override fun onTick(head: TurretHeadBlockEntity,now: Long) {
            if (now-head.lastDamageTick < 200 || head.boundBase?.core?.powerState?.let { it >= 2 } != true || head.shieldLayers >= head.effectiveStats.shield) return
            val next = head.persistentData.getLong("TurretModuleNextShield")
            if (now >= next) { head.shieldLayers++; head.persistentData.putLong("TurretModuleNextShield",now+200); head.setChanged() }
        }
    }
    object Armor : ModuleEffect("module_armor_plate") {
        override fun incomingDamage(amount: Double) = amount*0.8
    }
    object Thorns : ModuleEffect("module_thorns_coating") {
        override fun onDamaged(head: TurretHeadBlockEntity,amount: Double,attacker: LivingEntity?): Boolean {
            if (attacker != null) ModuleRuntime.retaliate(head,attacker,amount*0.3)
            return false
        }
    }
    object Repair : ModuleEffect("module_repair") {
        override fun onTick(head: TurretHeadBlockEntity,now: Long) {
            if (now%20 != Math.floorMod(head.blockPos.asLong(),20L) || head.health >= head.effectiveStats.health*ModuleConfig.repairThreshold.get()) return
            if (head.boundBase?.energy?.spend(ModuleConfig.repairEnergy.get()) == true) head.repair(ModuleConfig.repairHealth.get())
        }
    }
    object Priority : ModuleEffect("module_target_priority")
    object Friend : ModuleEffect("module_friend_or_foe") {
        override fun targetAllowed(head: TurretHeadBlockEntity,target: Mob): Boolean {
            val core = head.boundBase?.core ?: return false
            if (TargetingRules.friendly(core,target)) return false
            val world = head.level as? ServerLevel ?: return false
            return world.server.playerList.players.none { core.trusted(it.uuid) && target.isAlliedTo(it) }
        }
    }
    class AmmoGrade(grade: Int) : ModuleEffect("module_ammo_saver_$grade",typeId="ammo_saver",grade=grade) {
        override fun saveAmmo(head: TurretHeadBlockEntity) = (head.level?.random?.nextDouble() ?: 1.0) < listOf(0.15,0.3,0.5)[grade-1]
    }
    open class SaverGrade(grade: Int) : ModuleEffect("module_energy_saver_$grade",typeId="energy_saver",grade=grade) {
        override fun multiplyStats(stats: TurretStats) = stats.copy(energy=stats.energy*listOf(0.8,0.65,0.5)[grade-1])
    }
    object Saver : SaverGrade(1)
    object Camouflage : ModuleEffect("module_camouflage") {
        override fun hidden(head: TurretHeadBlockEntity,now: Long) = now-head.persistentData.getLong("TurretModuleLastShot") >= ModuleConfig.camouflageDelay.get()
        override fun visualBlock(head: TurretHeadBlockEntity): BlockState? {
            if (!hidden(head,head.level?.gameTime ?: 0)) return null
            return ResourceLocation.tryParse(ModuleConfig.camouflageBlock.get())?.let { ForgeRegistries.BLOCKS.getValue(it) }?.takeUnless { it === Blocks.AIR }?.defaultBlockState() ?: Blocks.STONE.defaultBlockState()
        }
    }
    class RadarGrade(grade: Int) : ModuleEffect("module_radar_$grade",typeId="radar",grade=grade) {
        override fun radarBonus() = 8.0*grade
        override fun addStats(stats: TurretStats) = stats.copy(range=stats.range+radarBonus())
    }
    object SelfDestruct : ModuleEffect("module_self_destruct",true) {
        override fun onDestroyed(head: TurretHeadBlockEntity) { head.selfDestruct() }
    }
    object Vengeance : ModuleEffect("module_vengeance",true) {
        override fun allyDestroyed(head: TurretHeadBlockEntity) { val data = head.persistentData; data.putInt("TurretVengeanceStacks",(data.getInt("TurretVengeanceStacks")+1).coerceAtMost(10)) }
        override fun stateStats(head: TurretHeadBlockEntity,stats: TurretStats): TurretStats {
            val stacks = head.persistentData.getInt("TurretVengeanceStacks").coerceIn(0,10)
            return stats.copy(damage=stats.damage*(1.0+0.1*stacks),cooldownTicks=(stats.cooldownTicks/(1.0+0.05*stacks)).roundToInt().coerceAtLeast(1),range=stats.range+stacks)
        }
        override fun continuousRate(head: TurretHeadBlockEntity) = 1.0+0.05*head.persistentData.getInt("TurretVengeanceStacks").coerceIn(0,10)
    }
    object Mode : ModuleEffect("module_mode_switch",true) {
        override fun switchMode(head: TurretHeadBlockEntity): Boolean {
            head.attackMode = if (head.attackMode == "secondary") "primary" else "secondary"; head.setChanged(); return true
        }
        override fun stateStats(head: TurretHeadBlockEntity,stats: TurretStats) = if (head.kind.id != "arbiter" && head.attackMode == "secondary") stats.copy(damage=stats.damage*ModuleConfig.modeDamage.get(),cooldownTicks=(stats.cooldownTicks*ModuleConfig.modeCooldown.get()).roundToInt().coerceAtLeast(1)) else stats
        override fun continuousRate(head: TurretHeadBlockEntity) = if (head.kind.id != "arbiter" && head.attackMode == "secondary") 1.0/ModuleConfig.modeCooldown.get() else 1.0
    }
    object Deflector : ModuleEffect("module_projectile_deflector",true) {
        override fun onTick(head: TurretHeadBlockEntity,now: Long) {
            if (Math.floorMod(now+head.blockPos.asLong(),ModuleConfig.deflectionInterval.get().toLong()) != 0L) return
            val world = head.level as? ServerLevel ?: return
            val base = head.boundBase ?: return; val core = base.core ?: return
            if (core.powerState < 2) return
            val box = AABB(head.center.subtract(5.0,5.0,5.0),head.center.add(5.0,5.0,5.0))
            var handled = 0
            for (projectile in world.getEntitiesOfClass(Projectile::class.java,box)) {
                if (handled >= ModuleConfig.deflectionLimit.get()) break
                if (projectile.isRemoved || projectile.distanceToSqr(head.center) > 25.0 || now < projectile.persistentData.getLong("TurretDeflectionUntil")) continue
                val owner = projectile.owner
                if (owner is Player && (owner.uuid == core.owner || core.trusted(owner.uuid) || owner.isCreative)) continue
                if (owner is Mob && !TargetingRules.matches(head,owner)) continue
                if (projectile.deltaMovement.lengthSqr() < 1.0e-6 || projectile.deltaMovement.dot(projectile.position().subtract(head.center)) >= 0.0) continue
                if (!base.energy.spend(ModuleConfig.deflectionEnergy.get())) return
                DeflectionShield.reflect(projectile,head.center,now); handled++
                core.owner?.let { world.server.playerList.getPlayer(it)?.let { player -> projectile.owner = player } }
            }
        }
    }
    object Overload : ModuleEffect("module_overload_core",true)
    class ClusterGrade(grade: Int) : ModuleEffect("module_cluster_round_$grade",true,"cluster",grade) {
        override fun afterHit(context: FireContext,target: LivingEntity,damage: Double) { ModuleRuntime.cluster(context,target.position(),listOf(3,5,8)[grade-1],damage*ModuleConfig.secondaryDamage.get()) }
    }
    class RicochetGrade(grade: Int) : ModuleEffect("module_ricochet_round_$grade",true,"ricochet",grade) {
        override fun afterHit(context: FireContext,target: LivingEntity,damage: Double) { ModuleRuntime.bounce(context,target,listOf(2,3,5)[grade-1],ModuleConfig.chainRange.get(),damage*ModuleConfig.secondaryDamage.get()) }
    }
    class ChargeGrade(grade: Int) : ModuleEffect("module_charge_$grade",true,"charge",grade) {
        private val key get() = "TurretModuleCharge$grade"
        override fun onTick(head: TurretHeadBlockEntity,now: Long) { ready(head,now) }
        override fun ready(head: TurretHeadBlockEntity,now: Long): Boolean {
            if (!head.persistentData.contains(key)) { head.persistentData.putLong(key,now); head.setChanged() }
            return now-head.persistentData.getLong(key) >= (6-grade)*20
        }
        override fun outgoingDamage(context: FireContext,target: LivingEntity,amount: Double) = amount*(grade+2)
        override fun fired(head: TurretHeadBlockEntity,now: Long) { head.persistentData.putLong(key,now) }
    }
    class AcidGrade(grade: Int) : ModuleEffect("module_acid_coating_$grade",true,"acid",grade) {
        override fun onHit(context: FireContext,target: LivingEntity) { ControlEffects.corrode(target,ModuleConfig.corrosionTicks.get(),grade) }
    }
    class BarrageGrade(grade: Int) : ModuleEffect("module_barrage_$grade",true,"barrage",grade) {
        override fun afterHit(context: FireContext,target: LivingEntity,damage: Double) { ModuleRuntime.barrage(context,listOf(3,5,7)[grade-1],damage*ModuleConfig.secondaryDamage.get()) }
    }
    object Homing : ModuleEffect("module_homing_round",true) { override fun homing() = true }
    class CriticalGrade(grade: Int) : ModuleEffect("module_critical_$grade",true,"critical",grade) {
        override fun outgoingDamage(context: FireContext,target: LivingEntity,amount: Double) = amount*(if (context.level.random.nextDouble() < listOf(0.15,0.25,0.35)[grade-1]) 2.0 else 1.0)
    }
    object ArmorPiercing : ModuleEffect("module_armor_penetration",true) { override fun armorPenetration() = 0.5 }
    object AntiInvisible : ModuleEffect("module_anti_invisibility",true) { override fun detectHidden() = true }

    companion object {
        // 37类效果共63个等级物品；首期8个对象和注册ID完全保留。
        val ALL: List<ModuleEffect> = listOf(Damage,DamageGrade(2),DamageGrade(3),Rate,RateGrade(2),RateGrade(3)) +
            (1..3).map(::RangeGrade) + listOf(Pierce,Explosive,Burning,Freezing,Shocking,Chain,Gravity,Poison,Leech,Shield,ShieldGrade(2),ShieldGrade(3),ShieldRegen,Armor,Thorns,Repair,Priority,Friend) +
            (1..3).map(::AmmoGrade) + listOf(Saver,SaverGrade(2),SaverGrade(3),Camouflage) + (1..3).map(::RadarGrade) +
            listOf(SelfDestruct,Vengeance,Mode,Deflector,Overload) + (1..3).map(::ClusterGrade) + (1..3).map(::RicochetGrade) +
            (1..3).map(::ChargeGrade) + (1..3).map(::AcidGrade) + (1..3).map(::BarrageGrade) + listOf(Homing) + (1..3).map(::CriticalGrade) + listOf(ArmorPiercing,AntiInvisible)
        private val byId = ALL.associateBy { it.id }
        fun from(stack: ItemStack): ModuleEffect? {
            if (stack.isEmpty) return null
            val name = ForgeRegistries.ITEMS.getKey(stack.item) ?: return null
            return if (name.namespace == "turretdefense") byId[name.path] else null
        }
    }
}
