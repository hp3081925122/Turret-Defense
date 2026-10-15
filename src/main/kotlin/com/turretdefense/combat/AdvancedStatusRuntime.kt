package com.turretdefense.combat

import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.network.TurretNetworkData
import com.turretdefense.targeting.TargetingRules
import com.turretdefense.turret.AdvancedCombatConfig
import com.turretdefense.turret.FireContext
import com.turretdefense.ui.EffectBatch
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.min

// 长效状态只存实体NBT；酸池只存每维度SavedData中的位置和数值，不保存实体强引用。
object AdvancedStatusRuntime {
    private const val ROOT = "TurretAdvancedStatus"
    fun register(bus: IEventBus) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,::livingTick)
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,::death)
        MinecraftForge.EVENT_BUS.addListener(::levelTick)
    }
    private fun data(entity: LivingEntity): CompoundTag {
        if (!entity.persistentData.contains(ROOT)) entity.persistentData.put(ROOT,CompoundTag())
        return entity.persistentData.getCompound(ROOT)
    }
    fun acid(context: FireContext,entity: Mob,dps: Double) {
        val now = context.level.gameTime; val tag = data(entity)
        val active = tag.contains("AcidUntil") && tag.getLong("AcidUntil") >= now
        val oldDps = if (active) tag.getDouble("AcidDps") else 0.0
        val stacks = if (active) (tag.getInt("AcidStacks")+1).coerceAtMost(5) else 1
        tag.putInt("AcidStacks",stacks); tag.putLong("AcidUntil",now+100)
        tag.putDouble("AcidDps",maxOf(dps,oldDps))
        if (!tag.contains("AcidNext") || tag.getLong("AcidNext") <= now) tag.putLong("AcidNext",now+20)
        tag.put("AcidSource",source(context)); tag.remove("AcidPoolSpawned")
        ControlEffects.acidCorrode(entity,100,stacks)
    }
    fun pull(entity: Mob,point: Vec3,ticks: Int) {
        val tag = data(entity)
        tag.putLong("PullUntil",entity.level().gameTime+ticks)
        tag.putDouble("PullX",point.x); tag.putDouble("PullY",point.y); tag.putDouble("PullZ",point.z)
    }
    fun lift(context: FireContext,entity: Mob,ticks: Int) {
        val resistance = entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE).coerceIn(0.0,1.0)
        if (resistance >= 1.0 || !entity.isAlive || entity.isPassenger) return
        val tag = data(entity); val now = context.level.gameTime
        val initial = if (tag.contains("LiftUntil")) tag.getDouble("LiftStartY") else entity.y
        if (!tag.contains("LiftGravity")) tag.putBoolean("LiftGravity",entity.isNoGravity)
        entity.move(MoverType.SELF,Vec3(0.0,(initial+3.0*(1.0-resistance)-entity.y).coerceAtLeast(0.0),0.0))
        if (entity.y-initial <= 0.05) { tag.remove("LiftGravity"); return }
        entity.isNoGravity = true; entity.deltaMovement = Vec3.ZERO; entity.fallDistance = 0.0f
        tag.putDouble("LiftStartY",initial); tag.putDouble("LiftY",entity.y)
        tag.putLong("LiftUntil",now+ticks); tag.putLong("LiftDeadline",now+ticks+AdvancedCombatConfig.landingTimeout.get())
        tag.putDouble("LiftDamage",maxOf(tag.getDouble("LiftDamage"),context.stats.damage*1.5)); tag.put("LiftSource",source(context)); tag.remove("LiftReleased")
        ControlEffects.stun(entity,ticks)
    }
    private fun livingTick(event: LivingEvent.LivingTickEvent) {
        val entity = event.entity
        val world = entity.level() as? ServerLevel ?: return
        if (!entity.persistentData.contains(ROOT)) return
        val tag = entity.persistentData.getCompound(ROOT); val now = world.gameTime
        if (tag.contains("AcidUntil")) {
            if (now > tag.getLong("AcidUntil")) remove(tag,"AcidUntil","AcidNext","AcidDps","AcidStacks","AcidSource","AcidPoolSpawned")
            else if (now >= tag.getLong("AcidNext")) {
                tag.putLong("AcidNext",now+20)
                hurt(world,entity,tag.getCompound("AcidSource"),tag.getDouble("AcidDps"))
            }
        }
        if (tag.contains("PullUntil")) {
            if (now >= tag.getLong("PullUntil")) remove(tag,"PullUntil","PullX","PullY","PullZ")
            else {
                val direction = Vec3(tag.getDouble("PullX"),tag.getDouble("PullY"),tag.getDouble("PullZ")).subtract(entity.position())
                if (direction.lengthSqr() > 0.04) {
                    val resistance = entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE).coerceIn(0.0,1.0)
                    entity.deltaMovement = entity.deltaMovement.add(direction.normalize().scale(AdvancedCombatConfig.pullStrength.get()*(1.0-resistance))); entity.hasImpulse = true
                }
            }
        }
        if (tag.contains("LiftUntil")) floatTick(world,entity,tag,now)
        if (tag.isEmpty) entity.persistentData.remove(ROOT)
    }
    private fun floatTick(world: ServerLevel,entity: LivingEntity,tag: CompoundTag,now: Long) {
        entity.fallDistance = 0.0f
        if (now < tag.getLong("LiftUntil")) {
            entity.isNoGravity = true
            entity.move(MoverType.SELF,Vec3(0.0,(tag.getDouble("LiftY")-entity.y).coerceIn(-0.3,0.3),0.0)); entity.deltaMovement = Vec3.ZERO
            return
        }
        if (!tag.getBoolean("LiftReleased")) {
            entity.isNoGravity = tag.getBoolean("LiftGravity"); tag.putBoolean("LiftReleased",true)
            entity.deltaMovement = Vec3(0.0,-0.4,0.0); entity.hasImpulse = true
        }
        if (entity.onGround() || entity.y <= tag.getDouble("LiftStartY")+0.05) {
            hurt(world,entity,tag.getCompound("LiftSource"),tag.getDouble("LiftDamage"))
            remove(tag,"LiftUntil","LiftDeadline","LiftStartY","LiftY","LiftDamage","LiftSource","LiftGravity","LiftReleased")
        } else if (now > tag.getLong("LiftDeadline")) {
            entity.isNoGravity = tag.getBoolean("LiftGravity")
            remove(tag,"LiftUntil","LiftDeadline","LiftStartY","LiftY","LiftDamage","LiftSource","LiftGravity","LiftReleased")
        } else {
            // 浮空结束统一使用碰撞下降，避免原先禁用AI或原生飞行逻辑让目标悬停。
            entity.move(MoverType.SELF,Vec3(0.0,-AdvancedCombatConfig.fallSpeed.get(),0.0)); entity.deltaMovement = Vec3.ZERO
        }
    }
    private fun death(event: LivingDeathEvent) {
        val entity = event.entity
        val world = entity.level() as? ServerLevel ?: return
        if (!entity.persistentData.contains(ROOT)) return
        val tag = entity.persistentData.getCompound(ROOT)
        if (tag.contains("LiftGravity")) entity.isNoGravity = tag.getBoolean("LiftGravity")
        if (!tag.contains("AcidUntil") || tag.getLong("AcidUntil") < world.gameTime || tag.getBoolean("AcidPoolSpawned")) return
        tag.putBoolean("AcidPoolSpawned",true)
        val pool = CompoundTag()
        pool.putDouble("X",entity.x); pool.putDouble("Y",entity.y); pool.putDouble("Z",entity.z)
        pool.putLong("Until",world.gameTime+100); pool.putLong("Next",world.gameTime+20)
        pool.putDouble("Damage",tag.getDouble("AcidDps")*AdvancedCombatConfig.poolDamage.get()); pool.put("Source",tag.getCompound("AcidSource").copy())
        val pools = Pools.get(world)
        while (pools.entries.size >= AdvancedCombatConfig.poolLimit.get()) pools.entries.removeAt(0)
        pools.entries.add(pool); pools.setDirty()
    }
    private fun levelTick(event: TickEvent.LevelTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val world = event.level as? ServerLevel ?: return
        val pools = Pools.get(world); val now = world.gameTime
        // 池伤害触发死亡事件时可能增加新池，遍历快照避免重入修改当前迭代器。
        for (pool in pools.entries.toList()) {
            if (pool !in pools.entries) continue
            if (now > pool.getLong("Until")) { pools.entries.remove(pool); pools.setDirty(); continue }
            val point = Vec3(pool.getDouble("X"),pool.getDouble("Y"),pool.getDouble("Z"))
            if (!loaded(world,BlockPos.containing(point))) continue
            if (now%10 == 0L) EffectBatch.line(world,7,point,point.add(0.0,0.1,0.0))
            if (now < pool.getLong("Next")) continue
            pool.putLong("Next",now+20); pools.setDirty()
            val source = pool.getCompound("Source")
            for (enemy in world.getEntitiesOfClass(Mob::class.java,AABB(point.subtract(2.0,2.0,2.0),point.add(2.0,2.0,2.0))).asSequence().filter { it.distanceToSqr(point) <= 4.0 && eligible(world,it,source) }.take(AdvancedCombatConfig.maxAreaTargets.get())) hurt(world,enemy,source,pool.getDouble("Damage"))
        }
    }
    private fun source(context: FireContext): CompoundTag {
        val tag = CompoundTag()
        tag.putLong("Head",context.head.blockPos.asLong()); tag.putLong("Core",context.core.blockPos.asLong())
        context.core.owner?.let { tag.putUUID("Owner",it) }
        tag.putString("Policy",context.head.targetPolicy); tag.putString("Allowed",context.head.targetAllowList.joinToString(",")); tag.putString("Denied",context.head.targetDenyList.joinToString(","))
        val trust = ListTag()
        for (id in TurretNetworkData.get(context.level).trustedPlayers(context.core.blockPos.asLong())) { val entry = CompoundTag(); entry.putUUID("Id",id); trust.add(entry) }
        tag.put("Trust",trust)
        return tag
    }
    private fun hurt(world: ServerLevel,entity: LivingEntity,source: CompoundTag,amount: Double): Boolean {
        if (!entity.isAlive || amount <= 0.0) return false
        val headPos = BlockPos.of(source.getLong("Head")); val corePos = BlockPos.of(source.getLong("Core"))
        val head = if (loaded(world,headPos)) world.getBlockEntity(headPos) as? TurretHeadBlockEntity else null
        val core = if (loaded(world,corePos)) world.getBlockEntity(corePos) as? BaseCoreBlockEntity else null
        return entity.hurt(Combat.source(world,head,core),amount.toFloat())
    }
    private fun eligible(world: ServerLevel,entity: Mob,source: CompoundTag): Boolean {
        if (!entity.isAlive || entity.isRemoved || entity.isSpectator) return false
        val owner = if (source.hasUUID("Owner")) source.getUUID("Owner") else null
        val ids = mutableSetOf<java.util.UUID>(); owner?.let { ids.add(it) }
        val trust = source.getList("Trust",10)
        for (index in 0 until trust.size) { val entry = trust.getCompound(index); if (entry.hasUUID("Id")) ids.add(entry.getUUID("Id")) }
        if (entity is TamableAnimal && entity.isTame && entity.ownerUUID in ids) return false
        if (ids.any { id -> world.server.playerList.getPlayer(id)?.let { entity.isAlliedTo(it) } == true }) return false
        val id = ForgeRegistries.ENTITY_TYPES.getKey(entity.type) ?: return false
        if (id in TargetingRules.parseList(source.getString("Denied"))) return false
        return when (source.getString("Policy")) {
            "all_mobs" -> true
            "allowlist" -> id in TargetingRules.parseList(source.getString("Allowed"))
            "retaliate" -> entity.persistentData.getLong("TurretAggressionHead") == source.getLong("Head") && entity.persistentData.getLong("TurretAggressionUntil") >= world.gameTime
            else -> entity is Enemy && (entity !is NeutralMob || ids.any { uuid -> world.server.playerList.getPlayer(uuid)?.let { entity.isAngryAt(it) } == true })
        }
    }
    private fun loaded(world: ServerLevel,pos: BlockPos) = world.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4) != null
    private fun remove(tag: CompoundTag,vararg keys: String) { keys.forEach { tag.remove(it) } }
    private class Pools : SavedData() {
        val entries = mutableListOf<CompoundTag>()
        override fun save(tag: CompoundTag): CompoundTag { val list = ListTag(); entries.forEach { list.add(it.copy()) }; tag.put("Pools",list); return tag }
        companion object {
            fun get(world: ServerLevel): Pools = world.dataStorage.computeIfAbsent({ tag ->
                val data = Pools(); val list = tag.getList("Pools",10)
                for (index in 0 until min(list.size,AdvancedCombatConfig.poolLimit.get())) data.entries.add(list.getCompound(index).copy())
                data
            },{ Pools() },"turretdefense_acid_pools")
        }
    }
}
