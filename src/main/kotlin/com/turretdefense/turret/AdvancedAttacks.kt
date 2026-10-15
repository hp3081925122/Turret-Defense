package com.turretdefense.turret

import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.blockentity.TurretBaseBlockEntity
import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.combat.AdvancedStatusRuntime
import com.turretdefense.combat.ControlEffects
import com.turretdefense.network.LevelRuntime
import com.turretdefense.ui.EffectBatch
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

// 后续塔种的射击只消费一次能量与弹药；弹道用分段射线和延迟队列，不创建弹射物实体。
object AdvancedAttacks : AttackBehavior {
    override fun fire(context: FireContext): Boolean = when (context.head.kind.id) {
        "gravity" -> mortar(context)
        "railgun" -> penetrating(context,false)
        "phase" -> phase(context)
        "sniper" -> penetrating(context,true)
        "arbiter" -> arbiter(context)
        "fusion" -> fusion(context)
        "acid" -> statusShot(context) { enemy ->
            val dps = listOf(1.0,2.0,3.0,5.0,8.0)[context.head.tier-1]*com.turretdefense.config.Config.damageMultiplier.get()
            AdvancedStatusRuntime.acid(context,enemy,dps)
        }
        "cryo" -> statusShot(context) { enemy ->
            val tier = context.head.tier
            ControlEffects.slowPercent(enemy,AdvancedCombatConfig.cryoDuration.get(),listOf(0.25,0.35,0.45,0.55,0.65)[tier-1])
            val chance = if (tier == 5) 0.25 else if (tier == 4) 0.15 else 0.0
            if (context.level.random.nextDouble() < chance) ControlEffects.stun(enemy,if (tier == 5) 40 else 30)
        }
        "emp" -> statusShot(context) { ControlEffects.stun(it,listOf(10,16,20,24,30)[context.head.tier-1]) }
        "graviton" -> statusShot(context) { AdvancedStatusRuntime.lift(context,it,listOf(20,24,30,36,40)[context.head.tier-1]) }
        else -> false
    }
    private fun statusShot(context: FireContext,effect: (Mob) -> Unit): Boolean {
        val (start,end) = context.clippedLine()
        if (!context.lineHit(context.target,start,end) || !context.consume()) return false
        // 酸蚀先登记死亡池；其他控制在命中后执行，不能先清空中立仇恨或移出射程而吞掉直伤。
        val acid = context.head.kind === TurretKind.Acid
        if (acid) effect(context.target)
        val hit = context.hurt(context.target,context.stats.damage)
        if (!acid && hit && context.target.isAlive) effect(context.target)
        EffectBatch.line(context.level,1,start,context.target.position().add(0.0,context.target.bbHeight*0.5,0.0),context.head.kind)
        context.base.sound(if (context.head.kind.id == "emp") SoundEvents.LIGHTNING_BOLT_IMPACT else SoundEvents.ARROW_SHOOT)
        return true
    }
    private fun penetrating(context: FireContext,delayed: Boolean): Boolean {
        val (start,end) = context.clippedLine()
        if (context.snapshot.targets.none { context.lineHit(it,start,end) } || !context.consume()) return false
        EffectBatch.line(context.level,0,start,end,context.head.kind); context.base.sound(SoundEvents.ARROW_SHOOT)
        if (!delayed) {
            context.snapshot.targets.asSequence().filter { context.lineHit(it,start,end) }.sortedBy { it.distanceToSqr(start) }.take(context.stats.pierceCount.coerceAtLeast(1)).forEach { context.hurt(it,context.stats.damage) }
            return true
        }
        val steps = ceil(start.distanceTo(end)*2.0).toInt().coerceAtLeast(1)
        var time = 0.0
        for (step in 1..steps) {
            val point = start.lerp(end,step.toDouble()/steps)
            val inWater = loaded(context.level,point) && context.level.getFluidState(BlockPos.containing(point)).`is`(FluidTags.WATER)
            time += start.distanceTo(end)/steps/(AdvancedCombatConfig.sniperSpeed.get()*(if (inWater) 0.7 else 1.0))
        }
        later(context,ceil(time).toLong().coerceAtLeast(1)) { fresh ->
            val (shotStart,shotEnd) = if (ModuleRuntime.homing(fresh.head)) fresh.clippedLine() else start to com.turretdefense.combat.LoadedRay.clip(fresh.level,ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null)).location
            fresh.snapshot.targets.asSequence().filter { fresh.lineHit(it,shotStart,shotEnd) }.sortedBy { it.distanceToSqr(shotStart) }.take(fresh.stats.pierceCount+1).forEach { fresh.hurt(it,fresh.stats.damage) }
        }
        return true
    }
    private fun arbiter(context: FireContext): Boolean {
        val candidates = context.snapshot.targets.asSequence().filter { context.valid(it) && visible(context,it) }
            .sortedBy { if (it === context.target) -1.0 else it.distanceToSqr(context.origin) }.take(if (context.head.attackMode == "secondary") 3 else context.stats.pierceCount+1).toList()
        if (candidates.isEmpty() || !context.consume()) return false
        candidates.forEach { enemy -> context.hurt(enemy,context.stats.damage);
            // 起点移到炮塔方块表面外，炮口闪光不被方块遮住。
            val point = enemy.position().add(0.0,enemy.bbHeight*0.5,0.0); val direction = point.subtract(context.origin).normalize()
            EffectBatch.line(context.level,1,context.origin.add(direction.scale(0.5001/maxOf(abs(direction.x),abs(direction.y),abs(direction.z)).coerceAtLeast(1.0e-9))),point,context.head.kind)
        }
        context.base.sound(SoundEvents.ARROW_SHOOT); return true
    }
    private fun fusion(context: FireContext): Boolean {
        val (start,end) = context.clippedLine()
        val targets = context.snapshot.targets.filter { context.lineHit(it,start,end) }
        if (targets.isEmpty() || !context.consume()) return false
        val data = context.head.persistentData; val now = context.level.gameTime
        if (!data.hasUUID("TurretFusionTarget") || data.getUUID("TurretFusionTarget") != context.target.uuid || now-data.getLong("TurretFusionLast") > 4) {
            data.putUUID("TurretFusionTarget",context.target.uuid); data.putLong("TurretFusionStart",now)
        }
        val seconds = ((now-data.getLong("TurretFusionStart"))/20).coerceIn(0,4)
        val multiplier = 1.0+0.5*seconds
        val amount = context.stats.damage*context.stats.cooldownTicks/20.0*multiplier
        targets.forEach { context.hurt(it,amount) }
        data.putLong("TurretFusionLast",now); context.head.setChanged()
        val last = targets.maxBy { it.distanceToSqr(start) }
        EffectBatch.line(context.level,3,start,last.position().add(0.0,last.bbHeight*0.5,0.0),context.head.kind); context.base.sound(SoundEvents.BEACON_AMBIENT)
        return true
    }
    private fun phase(context: FireContext): Boolean {
        val (start,end) = context.clippedLine()
        if (!context.lineHit(context.target,start,end) || !context.consume()) return false
        val ambush = phaseHidden(context.head,context.level.gameTime)
        context.hurt(context.target,context.stats.damage*(if (ambush) AdvancedCombatConfig.phaseCritical.get() else 1.0))
        context.head.persistentData.putLong("TurretPhaseUntil",context.level.gameTime)
        context.head.persistentData.putLong("TurretPhaseNext",context.level.gameTime+AdvancedCombatConfig.phaseReveal.get())
        context.head.setChanged(); EffectBatch.line(context.level,1,start,context.target.position().add(0.0,context.target.bbHeight*0.5,0.0),context.head.kind); context.base.sound(SoundEvents.ENDERMAN_TELEPORT)
        return true
    }
    fun tick(head: TurretHeadBlockEntity,now: Long) { if (head.kind.id == "phase") phaseHidden(head,now) }
    fun phaseHidden(head: TurretHeadBlockEntity,now: Long): Boolean {
        if (head.kind.id != "phase") return false
        val data = head.persistentData
        if (head.level?.isClientSide != false) return now < data.getLong("TurretPhaseUntil")
        if (!data.contains("TurretPhaseNext") || now >= data.getLong("TurretPhaseNext")) {
            val until = now+listOf(60,60,80,80,100)[head.tier-1]
            data.putLong("TurretPhaseUntil",until); data.putLong("TurretPhaseNext",until+AdvancedCombatConfig.phaseReveal.get()); head.setChanged()
        }
        return now < data.getLong("TurretPhaseUntil")
    }
    fun evades(head: TurretHeadBlockEntity,now: Long) = phaseHidden(head,now) && (head.level?.random?.nextDouble() ?: 1.0) < 0.5
    fun grenade(context: FireContext): Boolean = mortar(context,true)
    private fun mortar(context: FireContext,grenade: Boolean = false): Boolean {
        if (context.target.distanceToSqr(context.origin) < context.stats.minRange*context.stats.minRange) return false
        val destination = context.target.position()
        val delta = destination.subtract(context.origin)
        val height = sqrt(delta.x*delta.x+delta.z*delta.z)*AdvancedCombatConfig.arcHeight.get()+2.0
        val segments = ceil(delta.length()).toInt().coerceIn(8,AdvancedCombatConfig.arcSegments.get())
        val points = ArrayList<Vec3>()
        for (step in 0..segments) {
            val t = step.toDouble()/segments
            points.add(context.origin.add(delta.scale(t)).add(0.0,4.0*height*t*(1.0-t),0.0))
        }
        val firstDirection = points[1].subtract(points[0]).normalize()
        points[0] = context.origin.add(firstDirection.scale(0.5001/maxOf(abs(firstDirection.x),abs(firstDirection.y),abs(firstDirection.z)).coerceAtLeast(1.0e-9)))
        val path = ArrayList<Vec3>(); path.add(points[0])
        for (index in 1 until points.size) {
            if (!loaded(context.level,points[index])) break
            val hit = com.turretdefense.combat.LoadedRay.clip(context.level,ClipContext(path.last(),points[index],ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null))
            path.add(hit.location)
            if (hit.type != HitResult.Type.MISS) break
        }
        if (path.size < 2 || !context.consume()) return false
        var length = 0.0
        for (index in 1 until path.size) { length += path[index-1].distanceTo(path[index]); EffectBatch.line(context.level,8,path[index-1],path[index],context.head.kind) }
        EffectBatch.line(context.level,9,path[0],path[0],context.head.kind)
        val landing = path.last()
        val guideOrigin = path[path.size/2]
        val canGuide = path.size == points.size
        val delay = if (grenade) com.turretdefense.config.Config.flightTicks.get().toLong() else ceil(length/AdvancedCombatConfig.mortarSpeed.get()).toLong().coerceAtLeast(1)
        later(context,delay) { fresh ->
            val impact = if (ModuleRuntime.homing(fresh.head) && canGuide) com.turretdefense.combat.LoadedRay.clip(fresh.level,ClipContext(guideOrigin,fresh.target.position(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null)).location else landing
            var hits = 0
            val water = loaded(fresh.level,impact) && fresh.level.getFluidState(BlockPos.containing(impact)).`is`(FluidTags.WATER)
            val radius = fresh.stats.aoeRadius*(if (grenade && water) 0.8 else 1.0)
            for (enemy in fresh.snapshot.targets) {
                if (hits >= AdvancedCombatConfig.maxAreaTargets.get()) break
                val distance = enemy.distanceToSqr(impact)
                if (!fresh.valid(enemy) || distance >= radius*radius) continue
                val falloff = (1.0-sqrt(distance)/radius).coerceIn(0.0,1.0)
                fresh.hurt(enemy,fresh.stats.damage*falloff)
                if (grenade) {
                    if (distance > 1.0e-6) enemy.knockback(TurretStatsTable.KNOCKBACK*falloff,impact.x-enemy.x,impact.z-enemy.z)
                } else AdvancedStatusRuntime.pull(enemy,impact,AdvancedCombatConfig.pullTicks.get())
                hits++
            }
            EffectBatch.line(fresh.level,5,impact,impact,fresh.head.kind)
        }
        context.base.sound(SoundEvents.FIREWORK_ROCKET_LAUNCH); return true
    }
    private fun visible(context: FireContext,target: Mob): Boolean {
        val end = target.position().add(0.0,target.bbHeight*0.5,0.0)
        val direction = end.subtract(context.origin).normalize()
        val start = context.origin.add(direction.scale(0.5001/maxOf(abs(direction.x),abs(direction.y),abs(direction.z)).coerceAtLeast(1.0e-9)))
        return com.turretdefense.combat.LoadedRay.clip(context.level,ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null)).location.distanceToSqr(end) < 0.01
    }
    private fun loaded(level: ServerLevel,point: Vec3): Boolean { val position = BlockPos.containing(point); return level.chunkSource.getChunkNow(position.x shr 4,position.z shr 4) != null }
    private fun later(context: FireContext,delay: Long,action: (FireContext) -> Unit) {
        val level = context.level; val headPos = context.head.blockPos; val basePos = context.base.blockPos; val corePos = context.core.blockPos
        val stats = context.stats; val target = context.target.uuid; val ammo = context.ammo
        LevelRuntime.get(level).scheduler.schedule(level.gameTime+delay) {
            if (level.chunkSource.getChunkNow(headPos.x shr 4,headPos.z shr 4) == null || level.chunkSource.getChunkNow(corePos.x shr 4,corePos.z shr 4) == null) return@schedule
            val head = level.getBlockEntity(headPos) as? TurretHeadBlockEntity ?: return@schedule
            val base = level.getBlockEntity(basePos) as? TurretBaseBlockEntity ?: return@schedule
            val core = level.getBlockEntity(corePos) as? BaseCoreBlockEntity ?: return@schedule
            if (head.boundBase !== base || base.core !== core) return@schedule
            val enemy = core.snapshot.targets.firstOrNull { it.uuid == target && it.isAlive } ?: core.snapshot.targets.firstOrNull { it.isAlive } ?: return@schedule
            action(FireContext(level,head,base,core,stats,enemy,core.snapshot,0,0.0,ammo))
        }
    }
}
