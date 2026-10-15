package com.turretdefense.targeting

import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.config.Config
import com.turretdefense.network.LevelRuntime
import com.turretdefense.turret.TurretStatsTable
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.Mob
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import java.util.EnumSet

// 玩家及其他实体目标总是先行；方块塔头不需要创建伪生物作为攻击目标。
class TurretAttackGoal(private val mob: Mob) : Goal() {
    private var head: TurretHeadBlockEntity? = null
    private var nextSearch = mob.level().gameTime+Math.floorMod(mob.id,20)
    private var nextPath = 0L
    private var nextAttack = 0L
    private var progressAt = 0L
    private var closestDistance = Double.MAX_VALUE
    private var lastFallbackLog = Long.MIN_VALUE/2
    private var pathTarget: Vec3? = null
    init { setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)) }
    override fun requiresUpdateEveryTick() = true
    override fun canUse(): Boolean {
        if (!Config.monstersAttack.get() || mob.target?.isAlive == true) return false
        val world = mob.level() as? ServerLevel ?: return false
        if (!TargetingRules.canAttackTurrets(mob,world.gameTime)) return false
        if (world.gameTime < nextSearch) return false
        nextSearch = world.gameTime+20
        val start = System.nanoTime()
        val runtime = LevelRuntime.get(world)
        head = runtime.nearestHead(mob.position(),TurretStatsTable.MONSTER_TARGET_RANGE,mob)
        pathTarget = head?.let { standingPoint(world,it) }
        runtime.recordNanos(start)
        return head != null
    }
    private fun inReach(point: Vec3,target: TurretHeadBlockEntity): Boolean {
        val x = max(abs(point.x-target.center.x)-0.5,0.0); val y = max(abs(point.y-target.center.y)-0.5,0.0); val z = max(abs(point.z-target.center.z)-0.5,0.0)
        val reach = 1.5+mob.bbWidth/2.0
        return x*x+y*y+z*z <= reach*reach
    }
    // 找可容纳怪物的实际落脚点，不让原版 A* 对固体塔头中心反复穷举不可达路径。
    private fun standingPoint(world: ServerLevel,target: TurretHeadBlockEntity): Vec3? {
        var best: Vec3? = null; var distance = Double.POSITIVE_INFINITY
        val height = kotlin.math.ceil(mob.bbHeight.toDouble()).toInt()
        for (direction in Direction.values()) {
            if (direction.stepY != 0) continue
            for (offset in 1..2) for (depth in 0..2) {
                val position = target.blockPos.relative(direction,offset).below(depth)
                if (world.chunkSource.getChunkNow(position.x shr 4,position.z shr 4) == null) continue
                val below = position.below()
                if (world.getBlockState(below).getCollisionShape(world,below).isEmpty) continue
                var clear = true
                for (y in 0 until height) { val cell = position.above(y); if (!world.getBlockState(cell).getCollisionShape(world,cell).isEmpty) { clear = false; break } }
                if (!clear) continue
                val eye = Vec3(position.x+0.5,position.y+mob.eyeHeight.toDouble(),position.z+0.5)
                if (!inReach(eye,target)) continue
                val point = Vec3(position.x+0.5,position.y.toDouble(),position.z+0.5)
                val square = mob.distanceToSqr(point)
                if (square < distance) { distance = square; best = point }
            }
        }
        return best
    }
    override fun canContinueToUse() = Config.monstersAttack.get() && TargetingRules.canAttackTurrets(mob,mob.level().gameTime) && mob.target?.isAlive != true && head?.let { !it.isRemoved && it.level === mob.level() && mob.distanceToSqr(it.center) <= TurretStatsTable.MONSTER_TARGET_RANGE*TurretStatsTable.MONSTER_TARGET_RANGE } == true
    override fun start() { mob.navigation.stop(); nextPath = 0L; progressAt = mob.level().gameTime; closestDistance = head?.center?.distanceToSqr(mob.position()) ?: Double.MAX_VALUE }
    override fun stop() { head = null; pathTarget = null; mob.navigation.stop(); (mob.level() as? ServerLevel)?.let { LevelRuntime.get(it).navigation.release(mob) } }
    override fun tick() {
        val target = head ?: return
        val world = mob.level() as? ServerLevel ?: return
        val start = System.nanoTime(); val now = world.gameTime
        val runtime = LevelRuntime.get(world)
        val distance = target.center.distanceToSqr(mob.position())
        if (distance+0.25 < closestDistance) { closestDistance = distance; progressAt = now }
        val stalled = now-progressAt >= TargetingRules.stalledTicks.get()
        if (stalled && TargetingRules.fallbackMovement.get()) {
            mob.navigation.stop(); runtime.navigation.release(mob); nextPath = now+TargetingRules.pathRetryTicks.get()
            if (now-lastFallbackLog >= 100) { com.turretdefense.TurretDefense.LOGGER.debug("Turret mob navigation stalled: entity={}, head={}, distance={}",mob.type,target.blockPos,kotlin.math.sqrt(distance)); lastFallbackLog = now }
        }
        mob.lookControl.setLookAt(target.center.x,target.center.y,target.center.z)
        // 塔头坐标是方块中心，近战从怪物视点判断，避免把脚底高度当作手部攻击高度。
        if (inReach(mob.eyePosition,target)) {
            mob.navigation.stop()
            runtime.navigation.release(mob)
            if (now >= nextAttack) {
                nextAttack = now+TurretStatsTable.MONSTER_ATTACK_INTERVAL
                val hit = com.turretdefense.combat.LoadedRay.clip(world,ClipContext(mob.eyePosition,target.center,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mob))
                if (hit.blockPos == target.blockPos) { mob.swing(InteractionHand.MAIN_HAND); target.takeDamage(mob.getAttributeValue(Attributes.ATTACK_DAMAGE),mob,true) }
            }
        } else if (now >= nextPath && mob.navigation.isDone && runtime.navigation.acquire(mob)) {
            nextPath = now+20
            val destination = standingPoint(world,target)
            pathTarget = destination
            val pathStart = System.nanoTime()
            if (destination != null) mob.navigation.moveTo(destination.x,destination.y,destination.z,1.0)
            val cost = System.nanoTime()-pathStart
            runtime.navigation.finish(cost); runtime.pathNanos += cost; runtime.pathCount++; runtime.pathPeakNanos = maxOf(runtime.pathPeakNanos,cost)
        }
        // 导航失败时统一施加有限水平移动，保留原版重力与方块碰撞，不读取具体生物或移动控制器类型。
        if (!inReach(mob.eyePosition,target) && (mob.navigation.isDone || stalled) && TargetingRules.fallbackMovement.get()) {
            val offset = target.center.subtract(mob.position()).multiply(1.0,0.0,1.0)
            val velocity = mob.deltaMovement
            if (offset.lengthSqr() > 0.01 && velocity.horizontalDistanceSqr() < 0.04) {
                val acceleration = minOf(TargetingRules.fallbackSpeed.get(),mob.getAttributeValue(Attributes.MOVEMENT_SPEED)*0.2)
                mob.deltaMovement = velocity.add(offset.normalize().scale(acceleration))
                mob.hasImpulse = true
                if (mob.horizontalCollision && mob.onGround()) mob.jumpControl.jump()
            }
        }
        runtime.recordNanos(start)
    }
}
