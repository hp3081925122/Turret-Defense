package com.turretdefense.event

import com.turretdefense.block.TierBlock
import com.turretdefense.blockentity.*
import com.turretdefense.command.TurretCommands
import com.turretdefense.loot.LootRouting
import com.turretdefense.network.*
import com.turretdefense.targeting.TurretAttackGoal
import com.turretdefense.ui.*
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.projectile.*
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.level.Explosion
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.ProjectileImpactEvent
import net.minecraftforge.event.entity.living.*
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.fml.util.ObfuscationReflectionHelper
import kotlin.math.ceil
import kotlin.math.sqrt

// 事件只负责入口与生命周期；塔头伤害、掉落与界面逻辑由各自模块执行。
object TurretEvents {
    private val explosionRadius = ObfuscationReflectionHelper.findField(Explosion::class.java,"f_46017_")
    @SubscribeEvent fun serverTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.START) FireBudget.beginTick() else UiNetwork.tick()
    }
    @SubscribeEvent fun levelTick(event: TickEvent.LevelTickEvent) {
        val world = event.level as? ServerLevel ?: return
        val runtime = LevelRuntime.get(world)
        if (event.phase == TickEvent.Phase.START) {
            runtime.navigation.beginTick()
            val start = System.nanoTime(); runtime.scheduler.tick(world.gameTime); runtime.recordNanos(start)
        } else { val start = System.nanoTime(); EffectBatch.flush(world); AimUpdates.flush(world); runtime.recordNanos(start); runtime.finishTick() }
    }
    @SubscribeEvent fun unload(event: LevelEvent.Unload) { (event.level as? ServerLevel)?.let { EffectBatch.clear(it); AimUpdates.clear(it); LevelRuntime.unload(it) } }
    @SubscribeEvent fun stopped(event: ServerStoppedEvent) { LevelRuntime.clearAll(); EffectBatch.clearAll(); AimUpdates.clearAll(); LootRouting.clear(); UiNetwork.clear() }
    @SubscribeEvent fun join(event: EntityJoinLevelEvent) {
        val mob = event.entity as? Mob ?: return
        if (!event.level.isClientSide && mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) != null) mob.goalSelector.addGoal(2,TurretAttackGoal(mob))
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) fun death(event: LivingDeathEvent) = LootRouting.death(event)
    @SubscribeEvent(priority=EventPriority.LOWEST) fun drops(event: LivingDropsEvent) = LootRouting.drops(event)
    @SubscribeEvent(priority=EventPriority.LOWEST) fun experience(event: LivingExperienceDropEvent) = LootRouting.experience(event)
    @SubscribeEvent fun commands(event: RegisterCommandsEvent) { TurretCommands.register(event.dispatcher) }
    // 原版爆炸仍可影响其他地形，只保护本模组方块，并用生命值系统结算受击。
    @SubscribeEvent fun explosion(event: ExplosionEvent.Detonate) {
        val world = event.level as? ServerLevel ?: return
        event.affectedBlocks.removeIf { world.getBlockState(it).block is TierBlock }
        val center = event.explosion.position
        val diameter = explosionRadius.getFloat(event.explosion)*2.0
        if (diameter <= 0) return
        val runtime = LevelRuntime.get(world)
        val heads = runtime.heads.values.toList(); val cores = runtime.cores.values.toList()
        fun damage(square: Double): Double {
            if (square > diameter*diameter) return 0.0
            val impact = 1.0-sqrt(square)/diameter
            return ((impact*impact+impact)/2.0*7.0*diameter+1.0)
        }
        for (head in heads) if (!head.isRemoved) head.takeDamage(damage(head.center.distanceToSqr(center)))
        for (core in cores) if (!core.isRemoved) core.takeDamage(damage(core.center.distanceToSqr(center)))
    }
    // 不取消原生撞击，箭仍会嵌入方块，火球仍走自己的爆炸路径。
    @SubscribeEvent fun projectile(event: ProjectileImpactEvent) {
        val world = event.projectile.level() as? ServerLevel ?: return
        val hit = event.rayTraceResult as? BlockHitResult ?: return
        val entity = world.getBlockEntity(hit.blockPos)
        if (entity !is TurretHeadBlockEntity && entity !is BaseCoreBlockEntity) return
        val projectile = event.projectile
        val damage = when (projectile) {
            is ThrownTrident -> 8.0
            is AbstractArrow -> {
                val value = ceil(projectile.baseDamage*projectile.deltaMovement.length().toFloat()).coerceIn(0.0,Int.MAX_VALUE.toDouble()).toInt()
                value.toDouble()+if (projectile.isCritArrow) world.random.nextInt(value/2+2) else 0
            }
            is SmallFireball -> 5.0
            is LargeFireball -> 6.0
            is WitherSkull -> if (projectile.owner is net.minecraft.world.entity.LivingEntity) 8.0 else 5.0
            is ShulkerBullet -> 4.0
            else -> 0.0
        }
        if (entity is TurretHeadBlockEntity) entity.takeDamage(damage,projectile.owner as? net.minecraft.world.entity.LivingEntity) else (entity as BaseCoreBlockEntity).takeDamage(damage)
    }
}
