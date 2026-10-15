package com.turretdefense.turret

import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.combat.Combat
import com.turretdefense.combat.ControlEffects
import com.turretdefense.ui.EffectBatch
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.sqrt

// 统一入口只分发钩子，不按插件ID或炮塔种类添加分支，次级命中不会递归触发命中钩子。
object ModuleRuntime {
    private fun modules(head: TurretHeadBlockEntity) = head.boundBase?.modules.orEmpty()
    fun modifyStats(head: TurretHeadBlockEntity,stats: TurretStats): TurretStats = modules(head).fold(stats) { current,module -> module.stateStats(head,current) }
    fun ready(head: TurretHeadBlockEntity,now: Long) = modules(head).all { it.ready(head,now) }
    fun damage(context: FireContext,target: LivingEntity,amount: Double) = modules(context.head).fold(amount) { current,module -> module.outgoingDamage(context,target,current) }
    fun fired(head: TurretHeadBlockEntity,now: Long) {
        head.persistentData.putLong("TurretModuleLastShot",now)
        modules(head).forEach { it.fired(head,now) }; head.setChanged()
    }
    fun tick(head: TurretHeadBlockEntity,now: Long) { modules(head).forEach { it.onTick(head,now) } }
    fun ammoSaved(head: TurretHeadBlockEntity): Boolean = modules(head).any { it.saveAmmo(head) }
    fun armorPenetration(head: TurretHeadBlockEntity) = modules(head).maxOfOrNull { it.armorPenetration() } ?: 0.0
    fun homing(head: TurretHeadBlockEntity) = modules(head).any { it.homing() }
    fun hidden(head: TurretHeadBlockEntity,now: Long) = modules(head).any { it.hidden(head,now) }
    fun camouflage(head: TurretHeadBlockEntity) = modules(head).firstNotNullOfOrNull { it.visualBlock(head) }
    fun radarBonus(head: TurretHeadBlockEntity) = modules(head).sumOf { it.radarBonus() }
    fun continuousRate(head: TurretHeadBlockEntity) = modules(head).fold(1.0) { rate,module -> rate*module.continuousRate(head) }
    fun targetAllowed(head: TurretHeadBlockEntity,target: Mob): Boolean {
        val effects = modules(head)
        val concealed = target.isInvisible || "turretdefense:phased" in target.tags || target.persistentData.getBoolean("TurretPhase")
        return (!concealed || effects.any { it.detectHidden() }) && effects.all { it.targetAllowed(head,target) }
    }
    fun allyDestroyed(head: TurretHeadBlockEntity) { modules(head).forEach { it.allyDestroyed(head) }; head.setChanged() }
    fun switchMode(head: TurretHeadBlockEntity) = modules(head).any { it.switchMode(head) }
    fun mode(head: TurretHeadBlockEntity) = head.attackMode
    fun hit(context: FireContext,target: LivingEntity,actualDamage: Double) { modules(context.head).forEach { it.afterHit(context,target,actualDamage) } }
    fun applyDamage(context: FireContext,target: LivingEntity,amount: Double,secondary: Boolean = false): Boolean {
        if (!context.valid(target) || (target is Mob && !targetAllowed(context.head,target))) return false
        val finalDamage = if (secondary) amount else damage(context,target,amount)
        if (!finalDamage.isFinite() || finalDamage <= 0.0) return false
        val before = target.health+target.absorptionAmount
        val penetration = maxOf(context.stats.armorPenetration,armorPenetration(context.head))
        val success = ControlEffects.withArmorReduction(target,penetration) { target.hurt(Combat.source(context.level,context.head,context.core),finalDamage.toFloat()) }
        if (success && target is Mob) {
            target.persistentData.putLong("TurretAggressionUntil",context.level.gameTime+com.turretdefense.targeting.TargetingRules.memory.get())
            target.persistentData.putLong("TurretAggressionHead",context.head.blockPos.asLong())
        }
        if (success && !secondary) hit(context,target,(before-target.health-target.absorptionAmount).toDouble().coerceAtLeast(0.0))
        return success
    }
    // 邻近扩散只从核心快照选敌人；逐目标检测地形阻挡，不查询或强加载新区块。
    fun secondary(context: FireContext,origin: Vec3,excluded: LivingEntity,count: Int,radius: Double,amount: Double,cone: Boolean = false) {
        val aim = context.target.position().subtract(context.origin).normalize()
        val limit = minOf(count,ModuleConfig.maxSecondary.get())
        val candidates = context.snapshot.targets.asSequence().filter { it !== excluded && context.valid(it) && targetAllowed(context.head,it) && it.distanceToSqr(origin) <= radius*radius }
            .filter { !cone || it.position().subtract(context.origin).normalize().dot(aim) >= cos(Math.toRadians(ModuleConfig.barrageAngle.get()/2.0)) }
            .sortedBy { it.distanceToSqr(origin) }.take(limit)
        for (entity in candidates) {
            val end = entity.position().add(0.0,entity.bbHeight*0.5,0.0)
            val start = if (origin.distanceToSqr(context.origin) < 0.01) origin.add(end.subtract(origin).normalize().scale(0.88)) else origin
            val hit = com.turretdefense.combat.LoadedRay.clip(context.level,ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null))
            if (hit.location.distanceToSqr(end) > 0.01) continue
            if (applyDamage(context,entity,amount,true)) EffectBatch.line(context.level,4,start,end)
        }
    }
    fun retaliate(head: TurretHeadBlockEntity,attacker: LivingEntity,amount: Double) {
        val world = head.level as? ServerLevel ?: return
        if (!attacker.isAlive || attacker.distanceToSqr(head.center) > 9.0) return
        val core = head.boundBase?.core
        if (attacker is net.minecraft.world.entity.player.Player && core != null && (attacker.uuid == core.owner || core.trusted(attacker.uuid) || attacker.isCreative)) return
        if (attacker is Mob && core != null && com.turretdefense.targeting.TargetingRules.friendly(core,attacker)) return
        attacker.hurt(Combat.source(world,head,head.boundBase?.core),amount.toFloat())
    }
    // 回旋每跳重新从上一个落点寻找最近未访问目标，既不回击同一实体，也不会穿墙。
    fun bounce(context: FireContext,first: LivingEntity,count: Int,radius: Double,amount: Double) {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<LivingEntity,Boolean>())
        visited.add(first)
        var start = first.position().add(0.0,first.bbHeight*0.5,0.0)
        repeat(minOf(count,ModuleConfig.maxSecondary.get())) {
            val origin = start
            val next = context.snapshot.targets.asSequence().filter { it !in visited && context.valid(it) && targetAllowed(context.head,it) && it.distanceToSqr(origin) <= radius*radius }
                .sortedBy { it.distanceToSqr(origin) }.firstOrNull { clear(context,origin,it.position().add(0.0,it.bbHeight*0.5,0.0)) } ?: return
            val end = next.position().add(0.0,next.bbHeight*0.5,0.0)
            visited.add(next)
            applyDamage(context,next,amount,true); EffectBatch.line(context.level,4,start,end); start = end
        }
    }
    // 子弹药拥有独立落点和小范围伤害，使用既有快照与纯实体结算，不产生实体或破坏地形。
    fun cluster(context: FireContext,landing: Vec3,count: Int,amount: Double) {
        repeat(minOf(count,ModuleConfig.maxSecondary.get())) {
            val angle = context.level.random.nextDouble()*Math.PI*2.0
            val radius = sqrt(context.level.random.nextDouble())*ModuleConfig.clusterSpread.get()
            val end = landing.add(cos(angle)*radius,0.2,sin(angle)*radius)
            if (!clear(context,landing.add(0.0,0.2,0.0),end)) return@repeat
            EffectBatch.line(context.level,2,landing,end); EffectBatch.line(context.level,5,end,end)
            context.snapshot.targets.asSequence().filter { context.valid(it) && targetAllowed(context.head,it) && it.distanceToSqr(end) <= ModuleConfig.clusterRadius.get()*ModuleConfig.clusterRadius.get() }
                .take(ModuleConfig.maxSecondary.get()).forEach { entity ->
                    if (clear(context,end,entity.position().add(0.0,entity.bbHeight*0.5,0.0))) applyDamage(context,entity,amount,true)
                }
        }
    }
    // 主命中已结算中央一束，另外结算扇形中的其余射线；每条射线仍受方块阻挡。
    fun barrage(context: FireContext,count: Int,amount: Double) {
        val aim = context.target.position().add(0.0,context.target.bbHeight*0.5,0.0).subtract(context.origin).normalize()
        val rays = minOf(count,ModuleConfig.maxSecondary.get()+1)
        for (index in 0 until rays) {
            if (index == rays/2) continue
            val angle = Math.toRadians(ModuleConfig.barrageAngle.get()*(index.toDouble()/(rays-1)-0.5))
            val direction = Vec3(aim.x*cos(angle)-aim.z*sin(angle),aim.y,aim.x*sin(angle)+aim.z*cos(angle)).normalize()
            val exit = 0.5001/maxOf(abs(direction.x),abs(direction.y),abs(direction.z)).coerceAtLeast(1.0e-9)
            val start = context.origin.add(direction.scale(exit))
            val end = com.turretdefense.combat.LoadedRay.clip(context.level,ClipContext(start,context.origin.add(direction.scale(context.stats.range)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null)).location
            context.snapshot.targets.asSequence().filter { context.lineHit(it,start,end) && targetAllowed(context.head,it) }.sortedBy { it.distanceToSqr(start) }
                .take((context.stats.pierceCount+1).coerceAtMost(ModuleConfig.maxSecondary.get())).forEach { applyDamage(context,it,amount,true) }
            EffectBatch.line(context.level,0,start,end)
        }
    }
    private fun clear(context: FireContext,start: Vec3,end: Vec3) = com.turretdefense.combat.LoadedRay.clip(context.level,ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null)).location.distanceToSqr(end) <= 0.01
}
