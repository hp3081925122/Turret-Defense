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
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.item.Item
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.pow

// 行为对象不保存状态；能量、弹药、目标与伤害归属通过每次开火的上下文传入。
class FireContext(val level: ServerLevel,val head: TurretHeadBlockEntity,val base: TurretBaseBlockEntity,val core: BaseCoreBlockEntity,val stats: TurretStats,val target: Monster,val snapshot: ThreatSnapshot,val energyCost: Int,val rawEnergy: Double,val ammo: TagKey<Item>?) {
    val origin get() = head.center
    fun consume(): Boolean {
        if (base.isRemoved || core.isRemoved || !core.inRange(base.blockPos) || base.energy.stored < energyCost) return false
        if (ammo != null && !core.consumeAmmo(ammo,true)) return false
        if (!base.energy.spend(energyCost)) return false
        if (ammo != null) core.consumeAmmo(ammo,false)
        FireBudget.recordFire()
        return true
    }
    fun valid(entity: LivingEntity) = entity.isAlive && !entity.isRemoved && !entity.isSpectator && entity.distanceToSqr(origin) <= stats.range*stats.range && entity.distanceToSqr(core.center) <= core.stats.range*core.stats.range
    fun hurt(entity: LivingEntity,damage: Double): Boolean {
        if (!valid(entity)) return false
        val success = entity.hurt(Combat.source(level,head,core),damage.toFloat())
        if (success) base.modules.forEach { it.onHit(this,entity) }
        return success
    }
    fun clippedLine(): Pair<Vec3,Vec3> {
        val aim = target.position().add(0.0,target.bbHeight*0.5,0.0).subtract(origin).normalize()
        val start = origin.add(aim.scale(0.6)); val end = origin.add(aim.scale(stats.range))
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
        val targets = arrayOfNulls<Monster>(context.stats.pierceCount+1)
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
        EffectBatch.line(context.level,if (context.head.kind === TurretKind.Particle) 1 else 0,start,end)
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
        EffectBatch.line(context.level,3,start,end)
        context.base.sound(SoundEvents.BEACON_AMBIENT)
        return true
    }
}

object BallisticBehavior : AttackBehavior {
    override fun fire(context: FireContext): Boolean {
        if (!context.consume()) return false
        val landing = context.target.position()
        val damageSource = Combat.source(context.level,context.head,context.core)
        EffectBatch.line(context.level,2,context.origin,landing)
        context.base.sound(SoundEvents.FIREWORK_ROCKET_LAUNCH)
        LevelRuntime.get(context.level).scheduler.schedule(context.level.gameTime+com.turretdefense.config.Config.flightTicks.get()) {
            val candidates = if (!context.core.isRemoved) context.core.snapshot.targets else context.snapshot.targets
            Combat.blast(context.level,landing,context.stats.aoeRadius,context.stats.damage,damageSource,candidates)
        }
        return true
    }
}

object ChainBehavior : AttackBehavior {
    override fun fire(context: FireContext): Boolean {
        if (!context.consume()) return false
        val visited = Collections.newSetFromMap(IdentityHashMap<Monster,Boolean>())
        var current: Monster? = context.target
        var previous = context.origin
        for (jump in 0 until context.stats.chainCount) {
            val enemy = current ?: break
            if (!context.valid(enemy)) break
            visited.add(enemy)
            val point = enemy.position().add(0.0,enemy.bbHeight*0.5,0.0)
            context.hurt(enemy,context.stats.damage*TurretStatsTable.ARC_FALLOFF.pow(jump))
            EffectBatch.line(context.level,4,previous,point); previous = point
            current = context.snapshot.targets.asSequence().filter { !visited.contains(it) && context.valid(it) && it.distanceToSqr(point) <= context.stats.jumpRange*context.stats.jumpRange }.minByOrNull { it.distanceToSqr(point) }
        }
        context.base.sound(SoundEvents.LIGHTNING_BOLT_IMPACT)
        return true
    }
}
