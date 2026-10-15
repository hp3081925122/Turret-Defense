package com.turretdefense.core

import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.turret.TurretStatsTable
import com.turretdefense.ui.EffectBatch
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

// 直接修改来袭弹射物速度，不新建弹射物；朝外、友方和冷却中的弹射物跳过。
object DeflectionShield {
    private val costs = intArrayOf(50,75,100,150,200)
    fun tick(core: BaseCoreBlockEntity) {
        val world = core.level as? ServerLevel ?: return
        val now = world.gameTime
        if (!CoreConfig.enabled.get() || core.powerState < 2 || Math.floorMod(now+core.blockPos.asLong(),CoreConfig.interval.get().toLong()) != 0L) return
        val radius = core.stats.deflectionRange
        if (radius <= 0) return
        val box = AABB(core.center.subtract(radius,radius,radius),core.center.add(radius,radius,radius))
        var processed = 0
        for (projectile in world.getEntitiesOfClass(Projectile::class.java,box)) {
            if (processed >= CoreConfig.limit.get()) break
            if (!eligible(core,projectile,radius,now)) continue
            processed++
            // 既有塔头投射护盾已经付维持费；独立核心在发生来袭时补同一维持费用。
            if (processed == 1 && core.managedBases.values.none { it.heads.isNotEmpty() } && !core.energy.spend(TurretStatsTable.SHIELD_ENERGY_PER_TICK*CoreConfig.interval.get())) return
            val efficiency = when (core.powerState) { 4 -> 1.0; 3 -> CoreConfig.lowEfficiency.get(); 2 -> CoreConfig.criticalEfficiency.get(); else -> 0.0 }
            if (world.random.nextDouble() >= efficiency) continue
            val cost = ceil(costs[core.tier-1]*CoreConfig.costMultiplier.get()).toInt()
            if (!core.energy.spend(cost)) return
            reflect(projectile,core.center,now)
            core.owner?.let { world.server.playerList.getPlayer(it)?.let { owner -> projectile.owner = owner } }
            EffectBatch.line(world,6,projectile.position(),projectile.position())
        }
    }
    fun eligible(core: BaseCoreBlockEntity,projectile: Projectile,radius: Double,now: Long): Boolean {
        if (projectile.isRemoved || projectile.distanceToSqr(core.center) > radius*radius || now < projectile.persistentData.getLong("TurretDeflectionUntil")) return false
        val owner = projectile.owner
        if (owner is Player && (owner.uuid == core.owner || core.trusted(owner.uuid) || owner.isCreative)) return false
        val movement = projectile.deltaMovement
        if (movement.lengthSqr() < 1.0e-6) return false
        return movement.dot(projectile.position().subtract(core.center)) < 0.0
    }
    fun reflect(projectile: Projectile,center: Vec3,now: Long) {
        val velocity = projectile.deltaMovement
        val outward = projectile.position().subtract(center).normalize()
        val reflected = if (outward.lengthSqr() < 1.0e-6) velocity.scale(-1.0) else velocity.subtract(outward.scale(2.0*velocity.dot(outward)))
        projectile.deltaMovement = reflected
        projectile.hasImpulse = true
        projectile.persistentData.putLong("TurretDeflectionUntil",now+CoreConfig.reflectionCooldown.get())
    }
}
