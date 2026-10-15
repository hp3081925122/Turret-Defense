package com.turretdefense.blockentity

import com.turretdefense.TurretDefense
import com.turretdefense.combat.Combat
import com.turretdefense.config.Config
import com.turretdefense.network.LevelRuntime
import com.turretdefense.network.TurretNetworkData
import com.turretdefense.registry.ModContent
import com.turretdefense.registry.ModTags
import com.turretdefense.targeting.ThreatSnapshot
import com.turretdefense.turret.TurretStatsTable
import com.turretdefense.util.EnergyBuffer
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.items.ItemStackHandler
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

// 核心独占扫描、供能、供弹与权限管理；只遍历登记底座及已加载区块。
class BaseCoreBlockEntity(pos: BlockPos,state: BlockState) : MachineBlockEntity(ModContent.CORE_BE.get(),pos,state) {
    var coreModules: List<com.turretdefense.core.CoreModule> = emptyList()
        private set
    private var moduleStats: com.turretdefense.turret.CoreStats? = null
    val stats get() = moduleStats ?: TurretStatsTable.CORE[tier-1]
    val scanRadius get() = min(stats.range,Config.scanRadiusCap.get().toDouble())
    val center: Vec3 = Vec3.atCenterOf(pos)
    val managedBases = Long2ObjectOpenHashMap<TurretBaseBlockEntity>()
    var health = 0.0
    var shield = 0
    var pendingXp = 0
    var burnTicks = 0
    var snapshot = ThreatSnapshot.EMPTY
        private set
    var currentScanInterval = 20
        private set
    var scanCount = 0L
        private set
    var scanTotalNanos = 0L
        private set
    var scanPeakNanos = 0L
        private set
    fun resetProbe() { scanCount = 0; scanTotalNanos = 0; scanPeakNanos = 0 }
    fun requestTargetRefresh() { nextScan = 0L }
    private var nextScan = 0L
    private var nextJudgment = 0L
    private var lastDamage = 0L
    private var lastExternalInput = Long.MIN_VALUE/2
    private var lastWarning = Long.MIN_VALUE/2
    private var initialized = false
    private var decommissioned = false
    private var previousPower = -1
    private var cachedScanRadius = -1.0
    private var cachedScanVertical = -1.0
    private var scanBox = AABB(pos)
    // 旧0–45槽保持不变，新模块槽接在末尾；旧库存读入时扩容而不移动物品。
    val inventory = object : ItemStackHandler(51) {
        override fun deserializeNBT(nbt: CompoundTag) {
            val compatible = nbt.copy(); compatible.putInt("Size",51)
            super.deserializeNBT(compatible); refreshCoreModules()
        }
        override fun getSlotLimit(slot: Int) = if (slot >= 46) 1 else 64
        override fun isItemValid(slot: Int,stack: ItemStack) = when (slot) {
            0 -> ForgeHooks.getBurnTime(stack,RecipeType.SMELTING) > 0
            in 1..18 -> stack.`is`(ModTags.AMMO)
            in 46..50 -> {
                val module = com.turretdefense.core.CoreModule.from(stack)
                slot < 46+tier && module != null && (com.turretdefense.core.CoreConfig.duplicates.get() || (46 until 46+tier).none { it != slot && com.turretdefense.core.CoreModule.from(getStackInSlot(it))?.category == module.category })
            }
            else -> true
        }
        override fun onContentsChanged(slot: Int) { setChanged(); if (slot >= 46) refreshCoreModules() }
    }
    val energy = EnergyBuffer({ stats.energy },{ Config.externalFE.get() },{ setChanged() },{ lastExternalInput = level?.gameTime ?: 0 })
    private var itemCap = LazyOptional.of { inventory }
    private var energyCap = LazyOptional.of { energy }
    fun refreshCoreModules() {
        coreModules = (46 until 46+tier).mapNotNull { com.turretdefense.core.CoreModule.from(inventory.getStackInSlot(it)) }
        var value = TurretStatsTable.CORE[tier-1]
        coreModules.forEach { value = it.modify(value) }
        moduleStats = value
        energy.stored = energy.stored
        health = min(health,stats.health*Config.healthMultiplier.get()); shield = min(shield,(stats.shield*Config.shieldMultiplier.get()).roundToInt())
        managedBases.values.forEach { base -> base.heads.values.forEach { it.refreshStats() } }
        setChanged()
    }
    override fun <T: Any?> getCapability(cap: Capability<T>,side: Direction?): LazyOptional<T> {
        if (cap === ForgeCapabilities.ITEM_HANDLER) return itemCap.cast()
        if (cap === ForgeCapabilities.ENERGY) return energyCap.cast()
        return super.getCapability(cap,side)
    }
    override fun invalidateCaps() { super.invalidateCaps(); itemCap.invalidate(); energyCap.invalidate() }
    override fun reviveCaps() { super.reviveCaps(); itemCap = LazyOptional.of { inventory }; energyCap = LazyOptional.of { energy } }
    fun trusted(id: UUID) = (level as? ServerLevel)?.let { TurretNetworkData.get(it).trusted(blockPos.asLong(),id) } == true
    override fun allowed(player: Player) = super.allowed(player) || trusted(player.uuid)
    fun inRange(pos: BlockPos) = blockPos.distSqr(pos) <= stats.range*stats.range
    val powerState: Int get() {
        val ratio = energy.stored.toDouble()/stats.energy
        return when {
            energy.stored == 0 -> 0
            ratio <= Config.emergencyThreshold.get() -> 1
            ratio <= Config.criticalThreshold.get() -> 2
            ratio <= Config.lowThreshold.get() -> 3
            else -> 4
        }
    }
    fun registerBase(base: TurretBaseBlockEntity): Boolean {
        if (!inRange(base.blockPos) || isRemoved || (managedBases.size >= Config.maxBases.get() && !managedBases.containsKey(base.blockPos.asLong()))) return false
        if (owner != null && base.owner != null && owner != base.owner && !trusted(base.owner!!)) return false
        managedBases.put(base.blockPos.asLong(),base)
        return true
    }
    fun unregisterBase(base: TurretBaseBlockEntity) { if (managedBases[base.blockPos.asLong()] === base) managedBases.remove(base.blockPos.asLong()) }
    override fun onLoad() {
        super.onLoad()
        val world = level as? ServerLevel ?: return
        refreshCoreModules()
        if (!initialized) { health = stats.health*Config.healthMultiplier.get(); shield = (stats.shield*Config.shieldMultiplier.get()).roundToInt(); initialized = true }
        currentScanInterval = Config.scanInterval.get()
        LevelRuntime.get(world).cores.put(blockPos.asLong(),this)
        val data = TurretNetworkData.get(world); if (data.cores.add(blockPos.asLong())) data.setDirty()
        scheduleLoadedChunkRegistration(world)
    }
    // 核心放置后的反向登记按每刻四个已加载区块执行，不请求生成或加载区块。
    private fun scheduleLoadedChunkRegistration(world: ServerLevel) {
        val runtime = LevelRuntime.get(world)
        val radius = stats.range.toInt()
        val minX = (blockPos.x-radius) shr 4; val maxX = (blockPos.x+radius) shr 4
        val minZ = (blockPos.z-radius) shr 4; val maxZ = (blockPos.z+radius) shr 4
        var column = 0
        for (x in minX..maxX) for (z in minZ..maxZ) {
            val delay = column++/TurretStatsTable.CHUNKS_PER_TICK
            runtime.scheduler.schedule(world.gameTime+delay+1) {
                if (!isRemoved) {
                    val chunk = world.chunkSource.getChunkNow(x,z)
                    if (chunk != null) chunk.blockEntities.values.filterIsInstance<TurretBaseBlockEntity>().forEach { if (inRange(it.blockPos)) it.findCore() }
                }
            }
        }
    }
    fun serverTick() {
        val world = level as? ServerLevel ?: return
        if (decommissioned) return
        val start = System.nanoTime(); val now = world.gameTime
        generate(now)
        if (upgradeJob != null) com.turretdefense.turret.UpgradeRules.process(this)
        if (now%TurretStatsTable.DISTRIBUTION_INTERVAL == 0L) distribute()
        if (now%20 == 0L) maintain(now)
        if (powerState != previousPower) {
            previousPower = powerState
            managedBases.values.forEach { base -> base.heads.values.forEach { it.refreshStats() } }
            if (previousPower < 4) warn("message.turretdefense.low_energy")
        }
        if (now >= nextScan) scan(world,now)
        if (now >= nextJudgment && powerState >= 3) judgment(world,now)
        com.turretdefense.core.DeflectionShield.tick(this)
        com.turretdefense.core.CoreAutomation.tick(this)
        LevelRuntime.get(world).recordNanos(start)
    }
    private fun generate(now: Long) {
        if (energy.stored >= energy.getMaxEnergyStored() || now-lastExternalInput <= 1) return
        if (burnTicks == 0) {
            val fuel = inventory.getStackInSlot(0)
            val duration = ForgeHooks.getBurnTime(fuel,RecipeType.SMELTING)
            if (duration > 0) {
                val used = inventory.extractItem(0,1,false)
                burnTicks = duration
                if (used.hasCraftingRemainingItem()) {
                    val remaining = used.craftingRemainingItem
                    if (inventory.getStackInSlot(0).isEmpty) inventory.setStackInSlot(0,remaining) else acceptLoot(remaining)
                }
            }
        }
        if (burnTicks > 0) { burnTicks--; energy.add(stats.generation); setChanged() }
        else if (energy.stored == 0 && now%TurretStatsTable.SELF_CHARGE_INTERVAL == 0L) energy.add(TurretStatsTable.SELF_CHARGE_ENERGY)
    }
    private fun distribute() {
        val bases = managedBases.values
        if (bases.isEmpty()) return
        // 分配时保留严重不足阈值对应的核心缓存，防止无线传输本身把核心抽到休眠。
        val reserve = (Config.criticalThreshold.get()-if (coreModules.any { it.category == "wireless" }) com.turretdefense.core.CoreConfig.wirelessReserveReduction.get() else 0.0).coerceAtLeast(0.0)
        var budget = (energy.stored-(stats.energy*reserve).toInt()).coerceAtLeast(0)
        var remaining = bases.size
        for (base in bases) {
            if (base.isRemoved || !inRange(base.blockPos)) { remaining--; continue }
            val idle = base.heads.size*TurretStatsTable.IDLE_ENERGY_PER_TICK*TurretStatsTable.DISTRIBUTION_INTERVAL
            base.energy.spend(minOf(idle,base.energy.stored))
            val share = if (remaining > 0) budget/remaining else 0
            val sent = base.energy.receiveEnergy(share,true)
            if (sent > 0 && energy.spend(sent)) { base.energy.receiveEnergy(sent,false); budget -= sent }
            remaining--
        }
        if (powerState >= 2 && bases.any { it.heads.isNotEmpty() }) energy.spend(TurretStatsTable.SHIELD_ENERGY_PER_TICK*TurretStatsTable.DISTRIBUTION_INTERVAL)
    }
    private fun maintain(now: Long) {
        if (now%TurretStatsTable.MAINTENANCE_INTERVAL == 0L) {
            val iterator = managedBases.values.iterator()
            while (iterator.hasNext()) { val base = iterator.next(); if (base.isRemoved || !inRange(base.blockPos)) iterator.remove() }
        }
        val factor = when (powerState) { 4 -> 1.0; 3 -> 0.5; 2 -> 0.1; else -> 0.0 }
        if (factor > 0 && managedBases.values.any { base -> base.heads.values.any { it.health < it.effectiveStats.health } } && energy.spend(TurretStatsTable.REPAIR_ENERGY_PER_SECOND)) {
            managedBases.values.forEach { base -> base.heads.values.forEach { it.repair(stats.repair*factor) } }
        }
        if (powerState >= 2 && now-lastDamage >= Config.regenDelay.get()*20 && now%(Config.regenInterval.get()*20) == 0L) {
            shield = min(shield+1,(stats.shield*Config.shieldMultiplier.get()).roundToInt())
            managedBases.values.forEach { base -> base.heads.values.forEach { it.regenerateShield(now) } }
            setChanged()
        }
    }
    private fun scan(world: ServerLevel,now: Long) {
        val active = managedBases.values.any { base -> !base.isRemoved && base.heads.isNotEmpty() }
        if (!active || powerState == 0) { snapshot = ThreatSnapshot.EMPTY; nextScan = now+Config.scanInterval.get(); return }
        val start = System.nanoTime()
        val radius = scanRadius
        val vertical = min(radius,Config.scanVerticalRange.get().toDouble())
        // AABB 在此映射中是不可变类，只在半径或配置改变时替换缓存。
        if (cachedScanRadius != radius || cachedScanVertical != vertical) {
            scanBox = AABB(center.x-radius,center.y-vertical,center.z-radius,center.x+radius,center.y+vertical,center.z+radius)
            cachedScanRadius = radius; cachedScanVertical = vertical
        }
        val targets = world.getEntitiesOfClass(Mob::class.java,scanBox) { it.isAlive && !it.isRemoved && !it.isSpectator && it.distanceToSqr(center) <= radius*radius && com.turretdefense.targeting.TargetingRules.candidate(this,it) }.sortedBy { it.distanceToSqr(center) }
        snapshot = ThreatSnapshot(targets,DoubleArray(targets.size) { targets[it].distanceToSqr(center) },now)
        managedBases.values.forEach { base -> base.heads.values.forEach { it.acceptThreats(snapshot) } }
        val cost = System.nanoTime()-start
        scanCount++; scanTotalNanos += cost; scanPeakNanos = maxOf(scanPeakNanos,cost)
        val previous = currentScanInterval
        currentScanInterval = when {
            targets.size > Config.highLoad.get() || cost/1_000_000.0 > Config.scanBudget.get() -> min(currentScanInterval*2,40)
            targets.size < Config.lowLoad.get() -> maxOf(currentScanInterval/2,Config.scanInterval.get())
            else -> currentScanInterval
        }
        if (previous != currentScanInterval) TurretDefense.LOGGER.debug("Core scan interval changed: pos={}, entities={}, costMs={}, interval={}",blockPos,targets.size,cost/1_000_000.0,currentScanInterval)
        nextScan = now+currentScanInterval
    }
    private fun judgment(world: ServerLevel,now: Long) {
        val enemies = snapshot.targets.filter { it.isAlive && !it.isRemoved && it.distanceToSqr(center) <= scanRadius*scanRadius }
        if (enemies.size < TurretStatsTable.JUDGMENT_COUNT && enemies.sumOf { it.health.toDouble() } < TurretStatsTable.JUDGMENT_HEALTH) return
        if (energy.stored < stats.judgmentEnergy || (tier >= 4 && !consumeAmmo(ModTags.JUDGMENT_AMMO[tier-4],true))) return
        if (tier >= 4) consumeAmmo(ModTags.JUDGMENT_AMMO[tier-4],false)
        energy.spend(stats.judgmentEnergy)
        Combat.judgment(this,enemies)
        nextJudgment = now+stats.judgmentCooldown; setChanged()
    }
    fun consumeAmmo(tag: TagKey<Item>,simulate: Boolean): Boolean {
        for (slot in 1..18) if (inventory.getStackInSlot(slot).`is`(tag)) {
            if (!simulate) inventory.extractItem(slot,1,false)
            return true
        }
        return false
    }
    fun acceptLoot(stack: ItemStack): ItemStack {
        var remaining = stack
        for (slot in 19..45) { remaining = inventory.insertItem(slot,remaining,false); if (remaining.isEmpty) return remaining }
        if (!remaining.isEmpty && Config.dropOnFull.get()) {
            (level as? ServerLevel)?.let { Block.popResource(it,blockPos.above(),remaining); remaining = ItemStack.EMPTY }
        } else warn("message.turretdefense.core_full")
        return remaining
    }
    fun warn(key: String) {
        val world = level as? ServerLevel ?: return
        if (world.gameTime-lastWarning < TurretStatsTable.WARNING_INTERVAL) return
        lastWarning = world.gameTime
        owner?.let { world.server.playerList.getPlayer(it)?.let { player -> com.turretdefense.ui.UiNetwork.alert(player,key) } }
    }
    fun takeDamage(amount: Double) {
        if (decommissioned || amount <= 0.0) return
        lastDamage = level?.gameTime ?: 0
        if (shield > 0 && powerState >= 2) shield-- else health -= amount
        setChanged()
        if (health <= 0) { destroyCore(); (level as? ServerLevel)?.removeBlock(blockPos,false) }
    }
    fun refreshTier() { refreshCoreModules(); scheduleLoadedChunkRegistration(level as? ServerLevel ?: return); setChanged() }
    fun destroyCore() {
        if (decommissioned) return
        val world = level as? ServerLevel ?: return
        decommissioned = true
        com.turretdefense.turret.UpgradeRules.cancel(this)
        val runtime = LevelRuntime.get(world)
        val victims = runtime.bases.values.filter { it.blockPos.distSqr(blockPos) <= TurretStatsTable.MARTYR_RADIUS*TurretStatsTable.MARTYR_RADIUS }
        var index = 0
        victims.forEach { base ->
            base.bindCore(null)
            val heads = base.heads.values.toList()
            if (heads.isNotEmpty()) runtime.scheduler.schedule(world.gameTime+index*TurretStatsTable.MARTYR_INTERVAL+1) { if (!base.isRemoved) base.martyrModules() }
            heads.forEach { head ->
                val due = world.gameTime+index++*TurretStatsTable.MARTYR_INTERVAL+1
                runtime.scheduler.schedule(due) { if (!head.isRemoved) head.destroy(true) }
            }
        }
        for (slot in 0 until inventory.slots) {
            val stack = inventory.getStackInSlot(slot)
            if (stack.isEmpty) continue
            if (stack.`is`(ModTags.EXPLOSIVE_AMMO)) Combat.coreBlast(this,TurretStatsTable.SELF_DESTRUCT_RADIUS,TurretStatsTable.SELF_DESTRUCT_DAMAGE)
            else if (slot !in 1..18 || world.random.nextBoolean()) Block.popResource(world,blockPos,stack.copy())
            inventory.setStackInSlot(slot,ItemStack.EMPTY)
        }
        energy.stored = 0
        TurretNetworkData.get(world).forget(blockPos.asLong()); if (runtime.cores[blockPos.asLong()] === this) runtime.cores.remove(blockPos.asLong())
        managedBases.clear()
    }
    override fun setRemoved() {
        (level as? ServerLevel)?.let { val runtime = LevelRuntime.peek(it); if (runtime != null && runtime.cores[blockPos.asLong()] === this) runtime.cores.remove(blockPos.asLong()) }
        managedBases.values.toList().forEach { it.bindCore(null) }; managedBases.clear()
        super.setRemoved()
    }
    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag); tag.put("Inventory",inventory.serializeNBT()); tag.putInt("Energy",energy.stored)
        tag.putDouble("Health",health); tag.putInt("Shield",shield); tag.putInt("Burn",burnTicks); tag.putInt("Xp",pendingXp)
        tag.putBoolean("Initialized",initialized); tag.putLong("LastDamage",lastDamage); tag.putLong("NextJudgment",nextJudgment)
    }
    override fun load(tag: CompoundTag) {
        super.load(tag); inventory.deserializeNBT(tag.getCompound("Inventory")); energy.stored = tag.getInt("Energy")
        health = tag.getDouble("Health"); shield = tag.getInt("Shield"); burnTicks = tag.getInt("Burn"); pendingXp = tag.getInt("Xp")
        initialized = tag.getBoolean("Initialized"); lastDamage = tag.getLong("LastDamage"); nextJudgment = tag.getLong("NextJudgment")
    }
}
