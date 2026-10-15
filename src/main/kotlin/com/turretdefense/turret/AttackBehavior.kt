package com.turretdefense.turret

import com.turretdefense.blockentity.*
import com.turretdefense.combat.Combat
import com.turretdefense.network.FireBudget
import com.turretdefense.network.LevelRuntime
import com.turretdefense.targeting.ThreatSnapshot
import com.turretdefense.ui.EffectBatch
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.Item
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.pow
import kotlin.math.abs

// 行为对象不保存状态；能量、弹药、目标与伤害归属通过每次开火的上下文传入。
class FireContext(val level: ServerLevel,val head: TurretHeadBlockEntity,val base: TurretBaseBlockEntity,val core: BaseCoreBlockEntity,val stats: TurretStats,val target: Mob,val snapshot: ThreatSnapshot,val energyCost: Int,val rawEnergy: Double,val ammo: TagKey<Item>?) {
    val origin get() = head.center
    fun consume(): Boolean {
        if (base.isRemoved || core.isRemoved || !core.inRange(base.blockPos) || base.energy.stored < energyCost) return false
        if (ammo != null && !core.consumeAmmo(ammo,true)) return false
        if (!base.energy.spend(energyCost)) return false
        if (ammo != null) {
            if (!ModuleRuntime.ammoSaved(head)) core.consumeAmmo(ammo,false)
            if (head.kind.continuous) head.persistentData.putLong("NextContinuousAmmo",level.gameTime+AdvancedCombatConfig.fusionAmmoIntervalTicks.get())
        }
        FireBudget.recordFire()
        return true
    }
    fun valid(entity: LivingEntity) = entity.isAlive && !entity.isRemoved && !entity.isSpectator && (entity !is Mob || com.turretdefense.targeting.TargetingRules.matches(head,entity)) && entity.distanceToSqr(origin) <= stats.range*stats.range && entity.distanceToSqr(origin) >= stats.minRange*stats.minRange && entity.distanceToSqr(core.center) <= core.scanRadius*core.scanRadius
    fun hurt(entity: LivingEntity,damage: Double): Boolean {
        val antiair = if (!entity.onGround() && !entity.isInWater) 1.0+core.coreModules.filter { it.category == "antiair" }.sumOf { it.grade*com.turretdefense.core.CoreConfig.airBonus.get() } else 1.0
        return ModuleRuntime.applyDamage(this,entity,damage*antiair)
    }
    fun clippedLine(): Pair<Vec3,Vec3> {
        val aim = target.position().add(0.0,target.bbHeight*0.5,0.0).subtract(origin).normalize()
        // 刚越过自身完整方块的最近面，斜向不命中自己，也不会跳过紧贴的其他障碍。
        val exit = 0.5001/maxOf(abs(aim.x),abs(aim.y),abs(aim.z)).coerceAtLeast(1.0e-9)
        val start = origin.add(aim.scale(exit)); val end = origin.add(aim.scale(stats.range))
        // 只做一次方块射线检测，不在开火时查询世界实体；多目标来自核心缓存。
        val hit = level.clip(ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null))
        return start to hit.location
    }
    // 先用包围球排除射线旁的绝大多数候选，只有可能命中的候选才创建膨胀 AABB。
    fun lineHit(entity: LivingEntity,start: Vec3,end: Vec3): Boolean {
        if (!valid(entity)) return false
        val box = entity.boundingBox
        val x = (box.minX+box.maxX)*0.5; val y = (box.minY+box.maxY)*0.5; val z = (box.minZ+box.maxZ)*0.5
        val dx = end.x-start.x; val dy = end.y-start.y; val dz = end.z-start.z
        val length = dx*dx+dy*dy+dz*dz
        if (length < 1.0e-9) return false
        val factor = (((x-start.x)*dx+(y-start.y)*dy+(z-start.z)*dz)/length).coerceIn(0.0,1.0)
        val ox = x-start.x-dx*factor; val oy = y-start.y-dy*factor; val oz = z-start.z-dz*factor
        val rx = (box.maxX-box.minX)*0.5+0.1; val ry = (box.maxY-box.minY)*0.5+0.1; val rz = (box.maxZ-box.minZ)*0.5+0.1
        if (ox*ox+oy*oy+oz*oz > rx*rx+ry*ry+rz*rz) return false
        return box.inflate(0.1).clip(start,end).isPresent
    }
}

sealed interface AttackBehavior { fun fire(context: FireContext): Boolean }

object HitscanBehavior : AttackBehavior {
    override fun fire(context: FireContext): Boolean {
        val (start,end) = context.clippedLine()
        val targets = arrayOfNulls<Mob>(context.stats.pierceCount+1)
        val distances = DoubleArray(targets.size) { Double.POSITIVE_INFINITY }
        for (candidate in context.snapshot.targets) {
            if (!context.lineHit(candidate,start,end)) continue
            val distance = candidate.distanceToSqr(start)
            for (index in targets.indices) if (distance < distances[index]) {
                for (slot in targets.lastIndex downTo index+1) { targets[slot] = targets[slot-1]; distances[slot] = distances[slot-1] }
                targets[index] = candidate; distances[index] = distance; break
            }
        }
        if (targets[0] == null || !context.consume()) return false
        targets.forEach { it?.let { entity -> context.hurt(entity,context.stats.damage) } }
        // 弹道止于最远被击中的敌人，命中粒子落在敌人身上。
        val last = targets.lastOrNull { it != null }!!
        EffectBatch.line(context.level,0,start,last.position().add(0.0,last.bbHeight*0.5,0.0),context.head.kind)
        context.base.sound(SoundEvents.ARROW_SHOOT)
        return true
    }
}

object ContinuousBeamBehavior : AttackBehavior {
    override fun fire(context: FireContext): Boolean {
        val (start,end) = context.clippedLine()
        val targets = context.snapshot.targets.filter { context.lineHit(it,start,end) }
        if (targets.isEmpty() || !context.consume()) return false
        val amount = context.stats.damage*context.stats.cooldownTicks/20.0
        targets.forEach { context.hurt(it,amount) }
        val last = targets.maxBy { it.distanceToSqr(start) }
        EffectBatch.line(context.level,3,start,last.position().add(0.0,last.bbHeight*0.5,0.0),context.head.kind)
        context.base.sound(SoundEvents.BEACON_AMBIENT)
        return true
    }
}

object BallisticBehavior : AttackBehavior {
    override fun fire(context: FireContext): Boolean = AdvancedAttacks.grenade(context)
}

object ChainBehavior : AttackBehavior {
    override fun fire(context: FireContext): Boolean {
        if (!context.consume()) return false
        val visited = Collections.newSetFromMap(IdentityHashMap<Mob,Boolean>())
        var current: Mob? = context.target
        var previous = context.origin
        for (jump in 0 until context.stats.chainCount) {
            val enemy = current ?: break
            if (!context.valid(enemy)) break
            visited.add(enemy)
            val point = enemy.position().add(0.0,enemy.bbHeight*0.5,0.0)
            context.hurt(enemy,context.stats.damage*TurretStatsTable.ARC_FALLOFF.pow(jump))
            EffectBatch.line(context.level,4,previous,point,context.head.kind); previous = point
            val jumpRange = context.stats.jumpRange*(if (enemy.isInWater) AdvancedCombatConfig.underwaterArcRange.get() else 1.0)
            current = context.snapshot.targets.asSequence().filter { !visited.contains(it) && context.valid(it) && it.distanceToSqr(point) <= jumpRange*jumpRange }.minByOrNull { it.distanceToSqr(point) }
        }
        context.base.sound(SoundEvents.LIGHTNING_BOLT_IMPACT)
        return true
    }
}
