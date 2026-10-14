package com.turretdefense.blockentity

import com.turretdefense.block.TurretHeadBlock
import com.turretdefense.combat.Combat
import com.turretdefense.config.Config
import com.turretdefense.network.FireBudget
import com.turretdefense.network.LevelRuntime
import com.turretdefense.registry.ModContent
import com.turretdefense.registry.ModTags
import com.turretdefense.targeting.ThreatSnapshot
import com.turretdefense.turret.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// 每个塔头只比较自己的到期时间，候选全部来自核心，空闲时不查询世界实体。
class TurretHeadBlockEntity(pos: BlockPos,state: BlockState) : MachineBlockEntity(ModContent.HEAD_BE.get(),pos,state) {
    val kind get() = (blockState.block as TurretHeadBlock).kind
    val facing get() = blockState.getValue(TurretHeadBlock.FACING)
    val center: Vec3 = Vec3.atCenterOf(pos)
    var boundBase: TurretBaseBlockEntity? = null
        private set
    var health = 0.0
    var shieldLayers = 0
    var pendingXp = 0
    var kills = 0
    var nextFireTick = 0L
    var lastDamageTick = 0L
    var status = "unbound"
        private set
    var priority = "nearest"
    var targetType: EntityType<*>? = null
    var effectiveStats = kind.stats[tier-1]
        private set
    private var initialized = false
    private var destroying = false
    private var prepaidEnergy = 0.0
    private var snapshot = ThreatSnapshot.EMPTY
    private var target: Monster? = null
    fun bindBase(base: TurretBaseBlockEntity) {
        if (boundBase === base) return
        boundBase?.detachHead(facing.opposite,this)
        boundBase = base; refreshStats()
    }
    fun unbindBase() { boundBase = null; target = null; snapshot = ThreatSnapshot.EMPTY; status = "unbound" }
    fun refreshStats() {
        val base = boundBase
        var stats = kind.stats[tier-1]
        // 固定先加后乘，再按槽位顺序执行，确保同一组插件的结果始终相同。
        base?.modules?.forEach { stats = it.addStats(stats) }
        base?.modules?.forEach { stats = it.multiplyStats(stats) }
        val core = base?.core
        val projection = if (core?.isRemoved == false && core.powerState >= 2) (core.stats.projection*Config.projectionMultiplier.get()).roundToInt() else 0
        effectiveStats = stats.copy(health=stats.health*Config.healthMultiplier.get(),shield=((stats.shield+projection)*Config.shieldMultiplier.get()).roundToInt(),damage=stats.damage*Config.damageMultiplier.get(),range=min(stats.range,core?.stats?.range ?: stats.range),jumpRange=stats.jumpRange*Config.arcDistance.get())
        if (!initialized) { health = effectiveStats.health; shieldLayers = effectiveStats.shield; initialized = true }
        else { health = min(health,effectiveStats.health); shieldLayers = min(shieldLayers,effectiveStats.shield) }
        setChanged()
    }
    override fun onLoad() {
        super.onLoad()
        val world = level as? ServerLevel ?: return
        val runtime = LevelRuntime.get(world); runtime.addHead(this)
        resolveBase()
        if (!initialized) refreshStats()
        runtime.scheduler.schedule(world.gameTime+1) { if (!isRemoved && boundBase == null) resolveBase(true) }
        if (nextFireTick < world.gameTime) nextFireTick = world.gameTime+Math.floorMod(blockPos.asLong(),effectiveStats.cooldownTicks.toLong())
    }
    private fun resolveBase(dropIfMissing: Boolean = false) {
        val world = level as? ServerLevel ?: return
        val basePos = blockPos.relative(facing)
        if (!world.hasChunkAt(basePos)) return
        val base = world.getBlockEntity(basePos) as? TurretBaseBlockEntity
        if (base != null) base.attachHead(facing.opposite,this) else if (dropIfMissing) dropOrphan()
    }
    fun acceptThreats(value: ThreatSnapshot) { snapshot = value }
    fun configurePriority(value: String,type: EntityType<*>? = null) { priority = value; targetType = type; target = null; setChanged() }
    override fun setBlockState(state: BlockState) {
        val previous = blockState
        if (state == previous) { super.setBlockState(state); return }
        boundBase?.detachHead(facing.opposite,this)
        super.setBlockState(state)
        if (state != previous) { resolveBase(); refreshStats() }
    }
    private fun valid(entity: Monster?,core: BaseCoreBlockEntity): Boolean = entity != null && entity.isAlive && !entity.isRemoved && snapshot.members.contains(entity) && entity.distanceToSqr(center) <= effectiveStats.range*effectiveStats.range && entity.distanceToSqr(core.center) <= core.stats.range*core.stats.range
    private fun chooseTarget(core: BaseCoreBlockEntity,now: Long): Monster? {
        if (valid(target,core)) return target
        target = null
        val priorityEnabled = boundBase?.modules?.contains(ModuleEffect.Priority) == true
        val bound = sqrt(core.center.distanceToSqr(center))+effectiveStats.range
        var score = Double.POSITIVE_INFINITY
        for (index in snapshot.targets.indices) {
            // 只有本刻新快照才允许三角剪枝；旧距离排序不能用于剪掉已经移动的敌人。
            if (now == snapshot.tick && snapshot.distances[index] > bound*bound) break
            val candidate = snapshot.targets[index]
            if (!valid(candidate,core) || (priorityEnabled && targetType != null && candidate.type !== targetType)) continue
            val value = when {
                priorityEnabled && priority == "highest_health" -> -candidate.health.toDouble()
                priorityEnabled && priority == "lowest_health" -> candidate.health.toDouble()
                else -> candidate.distanceToSqr(center)
            }
            if (value < score) { score = value; target = candidate }
        }
        return target
    }
    fun serverTick() {
        val world = level as? ServerLevel ?: return
        val now = world.gameTime
        if (upgradeJob != null) com.turretdefense.turret.UpgradeRules.process(this)
        if (now < nextFireTick || destroying) return
        val start = System.nanoTime()
        try {
            val base = boundBase
            if (base == null || base.isRemoved || base.level !== world) { resolveBase(true); status = "unbound"; nextFireTick = now+20; FireBudget.releaseUnused(this); return }
            val core = base.core
            if (core == null || core.isRemoved || !core.inRange(base.blockPos)) { status = "offline"; nextFireTick = now+20; FireBudget.releaseUnused(this); return }
            val power = core.powerState
            if (power == 0 || (power <= 2 && base.tier >= 4) || (power == 1 && tier > 3)) { status = "offline"; nextFireTick = now+20; FireBudget.releaseUnused(this); return }
            if (now >= base.overloadUntil && now < base.overheatUntil) { status = "overheated"; nextFireTick = base.overheatUntil; FireBudget.releaseUnused(this); return }
            val enemy = chooseTarget(core,now)
            if (enemy == null) { status = "no_target"; nextFireTick = now+20; FireBudget.releaseUnused(this); return }
            var stats = effectiveStats
            if (kind === TurretKind.Laser && base.modules.contains(ModuleEffect.Rate)) stats = stats.copy(damage=stats.damage/TurretStatsTable.MODULE_COOLDOWN_MULTIPLIER,energy=stats.energy/TurretStatsTable.MODULE_COOLDOWN_MULTIPLIER,cooldownTicks=2)
            if (now < base.overloadUntil) {
                val continuous = kind === TurretKind.Laser
                stats = stats.copy(damage=stats.damage*TurretStatsTable.OVERLOAD_DAMAGE*(if (continuous) TurretStatsTable.OVERLOAD_RATE else 1.0),energy=stats.energy*(if (continuous) TurretStatsTable.OVERLOAD_RATE else 1.0),cooldownTicks=if (continuous) 2 else (stats.cooldownTicks/TurretStatsTable.OVERLOAD_RATE).roundToInt().coerceAtLeast(1))
            }
            val rate = when (power) { 4 -> 1.0; 3 -> 0.5; 2 -> 0.25; else -> Config.emergencyFireRate.get() }
            if (kind === TurretKind.Laser) stats = stats.copy(damage=stats.damage*rate,energy=stats.energy*rate)
            val rawEnergy = stats.energy*(if (kind === TurretKind.Laser) stats.cooldownTicks/20.0 else 1.0)
            val amount = ceil(rawEnergy-prepaidEnergy-1.0e-9).toInt().coerceAtLeast(0)
            val ammo = if (tier >= 4 && kind.ammo4 != null) ModTags.KIND_AMMO.getValue(kind)[tier-4] else null
            if (base.energy.stored < amount) { status = "no_energy"; nextFireTick = now+20; FireBudget.releaseUnused(this); return }
            if (ammo != null && !core.consumeAmmo(ammo,true)) { status = "no_ammo"; base.warn("message.turretdefense.no_ammo"); nextFireTick = now+20; FireBudget.releaseUnused(this); return }
            if (!FireBudget.acquire(this)) { status = "waiting_budget"; nextFireTick = now+1; return }
            val context = FireContext(world,this,base,core,stats,enemy,snapshot,amount,rawEnergy,ammo)
            if (Combat.behavior(kind).fire(context)) {
                prepaidEnergy = (prepaidEnergy+amount-rawEnergy).coerceIn(0.0,1.0)
                status = "ready"; nextFireTick = now+(if (kind === TurretKind.Laser) stats.cooldownTicks else (stats.cooldownTicks/rate).roundToInt().coerceAtLeast(1)); setChanged()
            } else { status = "blocked"; nextFireTick = now+20 }
        } finally { LevelRuntime.get(world).recordNanos(start) }
    }
    fun repair(amount: Double) { if (amount > 0 && health < effectiveStats.health) { health = min(health+amount,effectiveStats.health); setChanged() } }
    fun regenerateShield(now: Long) { if (now-lastDamageTick >= Config.regenDelay.get()*20 && shieldLayers < effectiveStats.shield) { shieldLayers++; setChanged() } }
    fun takeDamage(amount: Double) {
        if (amount <= 0.0 || destroying || isRemoved) return
        val base = boundBase
        lastDamageTick = level?.gameTime ?: 0
        if (base?.modules?.any { it.onDamaged(this,amount) } == true) return
        if (shieldLayers > 0 && base?.core?.powerState?.let { it >= 2 } == true) {
            shieldLayers--; base.sound(SoundEvents.SHIELD_BLOCK); Combat.shieldEffect(this)
        } else {
            var damage = amount; base?.modules?.forEach { damage = it.incomingDamage(damage) }; health -= damage
        }
        setChanged()
        if (health <= 0.0) destroy(false)
    }
    fun selfDestruct() { Combat.headBlast(this,TurretStatsTable.SELF_DESTRUCT_RADIUS,TurretStatsTable.SELF_DESTRUCT_DAMAGE) }
    fun destroy(martyr: Boolean) {
        if (destroying || isRemoved) return
        destroying = true
        val world = level as? ServerLevel ?: return
        boundBase?.modules?.toList()?.forEach { it.onDestroyed(this) }
        com.turretdefense.turret.UpgradeRules.cancel(this)
        if (martyr) Combat.headBlast(this,TurretStatsTable.SELF_DESTRUCT_RADIUS,effectiveStats.damage) else Combat.destroyEffect(this)
        world.removeBlock(blockPos,false)
    }
    private fun dropHead(world: ServerLevel) {
        Block.popResource(world,blockPos,dropStack())
    }
    fun dropStack(): ItemStack {
        val stack = ItemStack(ModContent.item("${kind.id}_head_t$tier"))
        val data = CompoundTag(); saveAdditional(data)
        if (health <= 0) { data.putDouble("Health",effectiveStats.health); data.putInt("Shield",effectiveStats.shield) }
        data.putLong("NextFire",0L); data.putLong("LastDamage",0L)
        stack.orCreateTag.put("BlockEntityTag",data)
        return stack
    }
    fun dropOrphan() {
        if (destroying || isRemoved) return
        destroying = true
        val world = level as? ServerLevel ?: return
        dropHead(world); world.removeBlock(blockPos,false)
    }
    override fun setRemoved() {
        boundBase?.detachHead(facing.opposite,this); boundBase = null
        (level as? ServerLevel)?.let { LevelRuntime.peek(it)?.removeHead(this) }
        snapshot = ThreatSnapshot.EMPTY; target = null
        super.setRemoved()
    }
    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag); tag.putDouble("Health",health); tag.putInt("Shield",shieldLayers); tag.putInt("Xp",pendingXp); tag.putInt("Kills",kills)
        tag.putLong("NextFire",nextFireTick); tag.putLong("LastDamage",lastDamageTick); tag.putDouble("PrepaidEnergy",prepaidEnergy); tag.putBoolean("Initialized",initialized)
        tag.putString("Priority",priority)
        targetType?.let { tag.putString("TargetType",EntityType.getKey(it).toString()) }
    }
    override fun load(tag: CompoundTag) {
        super.load(tag); health = tag.getDouble("Health"); shieldLayers = tag.getInt("Shield"); pendingXp = tag.getInt("Xp"); kills = tag.getInt("Kills")
        nextFireTick = tag.getLong("NextFire"); lastDamageTick = tag.getLong("LastDamage"); prepaidEnergy = tag.getDouble("PrepaidEnergy").coerceIn(0.0,1.0); initialized = tag.getBoolean("Initialized")
        priority = tag.getString("Priority").ifEmpty { "nearest" }
        targetType = net.minecraft.resources.ResourceLocation.tryParse(tag.getString("TargetType"))?.let { net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(it) }
    }
}
