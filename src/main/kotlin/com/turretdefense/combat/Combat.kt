package com.turretdefense.combat

import com.turretdefense.TurretDefense
import com.turretdefense.blockentity.*
import com.turretdefense.config.Config
import com.turretdefense.turret.*
import com.turretdefense.ui.EffectBatch
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

// 方块炮塔不是实体，伤害源直接保存塔头与核心归属，避免创建伪实体或按击杀查询世界。
class TurretDamageSource(level: ServerLevel,val head: TurretHeadBlockEntity?,val core: BaseCoreBlockEntity?) : DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(Combat.DAMAGE_KEY),core?.owner?.let { level.server.playerList.getPlayer(it) })

object Combat {
    val DAMAGE_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE,ResourceLocation(TurretDefense.MOD_ID,"turret"))
    fun source(level: ServerLevel,head: TurretHeadBlockEntity?,core: BaseCoreBlockEntity?) = TurretDamageSource(level,head,core)
    fun behavior(kind: TurretKind): AttackBehavior = when (kind) {
        TurretKind.Pulse,TurretKind.Particle -> HitscanBehavior
        TurretKind.Grenade -> BallisticBehavior
        TurretKind.Laser -> ContinuousBeamBehavior
        TurretKind.Arc -> ChainBehavior
    }
    fun shieldEffect(head: TurretHeadBlockEntity) { (head.level as? ServerLevel)?.let { EffectBatch.line(it,6,head.center,head.center) } }
    fun destroyEffect(head: TurretHeadBlockEntity) { (head.level as? ServerLevel)?.let { EffectBatch.line(it,5,head.center,head.center) }; head.boundBase?.sound(net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE) }
    fun headBlast(head: TurretHeadBlockEntity,radius: Double,damage: Double) {
        val world = head.level as? ServerLevel ?: return
        blast(world,head.center,radius,damage,source(world,head,head.boundBase?.core))
        head.boundBase?.sound(net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE)
    }
    fun coreBlast(core: BaseCoreBlockEntity,radius: Double,damage: Double) {
        val world = core.level as? ServerLevel ?: return
        blast(world,core.center,radius,damage,source(world,null,core))
    }
    // 只伤害实体，始终不调用 Level.explode，也不修改任何地形方块。
    fun blast(level: ServerLevel,center: Vec3,radius: Double,damage: Double,source: TurretDamageSource,candidates: List<out LivingEntity>? = null,onKill: ((LivingEntity) -> Unit)? = null) {
        val entities = candidates ?: level.getEntitiesOfClass(LivingEntity::class.java,AABB(center.x-radius,center.y-radius,center.z-radius,center.x+radius,center.y+radius,center.z+radius))
        var hits = 0
        for (entity in entities) {
            if (hits >= TurretStatsTable.MAX_BLAST_TARGETS) break
            if (!entity.isAlive || entity.isRemoved || entity.isSpectator || (entity is Player && entity.isCreative)) continue
            val square = entity.distanceToSqr(center)
            if (square >= radius*radius) continue
            val falloff = (1.0-sqrt(square)/radius).coerceIn(0.0,1.0)
            entity.hurt(source,(damage*falloff).toFloat())
            if (square > 1.0e-6) entity.knockback(TurretStatsTable.KNOCKBACK*falloff,center.x-entity.x,center.z-entity.z)
            hits++
            if (!entity.isAlive) onKill?.invoke(entity)
        }
        EffectBatch.line(level,5,center,center)
    }
    fun judgment(core: BaseCoreBlockEntity,enemies: List<LivingEntity>) {
        val world = core.level as? ServerLevel ?: return
        val radius = TurretStatsTable.JUDGMENT_RADIUS*(if (core.tier == 5) TurretStatsTable.JUDGMENT_T5_RADIUS_MULTIPLIER else 1.0)
        val damage = core.stats.judgmentDamage*Config.damageMultiplier.get()*(if (core.tier == 4) TurretStatsTable.JUDGMENT_T4_MULTIPLIER else 1.0)
        val target = enemies.firstOrNull { it.isAlive } ?: return
        val origin = target.position()
        val source = source(world,null,core)
        val queue = java.util.ArrayDeque<Pair<Vec3,Int>>()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<LivingEntity,Boolean>())
        queue.add(origin to 0)
        // 每刻只结算一轮连锁，保留所有深度与击杀触发，避免同步递归造成一刻雪崩。
        fun advance() {
            if (queue.isEmpty()) return
            val (point,depth) = queue.removeFirst()
            blast(world,point,radius,damage,source,enemies) { enemy ->
                if (core.tier == 5 && depth < Config.chainDepth.get() && visited.add(enemy)) queue.add(enemy.position() to depth+1)
            }
            if (queue.isNotEmpty()) com.turretdefense.network.LevelRuntime.get(world).scheduler.schedule(world.gameTime+1) { advance() }
        }
        advance()
        EffectBatch.line(world,3,core.center,origin)
    }
}
