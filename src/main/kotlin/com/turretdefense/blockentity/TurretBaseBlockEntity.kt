package com.turretdefense.blockentity

import com.turretdefense.config.Config
import com.turretdefense.network.LevelRuntime
import com.turretdefense.registry.ModContent
import com.turretdefense.turret.ModuleEffect
import com.turretdefense.turret.TurretStatsTable
import com.turretdefense.util.EnergyBuffer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.items.ItemStackHandler
import java.util.EnumMap
import java.util.UUID

// 底座的五个插件槽和二十七个缓存槽固定编号；底座不保存弹药，也不驱动开火。
class TurretBaseBlockEntity(pos: BlockPos,state: BlockState) : MachineBlockEntity(ModContent.BASE_BE.get(),pos,state) {
    val heads = EnumMap<Direction,TurretHeadBlockEntity>(Direction::class.java)
    var core: BaseCoreBlockEntity? = null
        private set
    var modules: List<ModuleEffect> = emptyList()
        private set
    var overloadUntil = 0L
    var overheatUntil = 0L
    private var lastWarning = Long.MIN_VALUE/2
    private var lastSound = Long.MIN_VALUE/2
    private var soundWindow = 0L
    private var sounds = 0
    val inventory = object : ItemStackHandler(32) {
        override fun getSlotLimit(slot: Int) = if (slot < 5) 1 else 64
        override fun isItemValid(slot: Int,stack: ItemStack): Boolean {
            if (slot >= 5) return true
            if (slot >= tier) return false
            val effect = ModuleEffect.from(stack) ?: return false
            return !effect.special || (0..4).none { it != slot && ModuleEffect.from(getStackInSlot(it))?.special == true }
        }
        override fun onContentsChanged(slot: Int) {
            setChanged()
            if (slot < 5) refreshModules()
        }
    }
    val energy = EnergyBuffer({ TurretStatsTable.CORE[tier-1].energy },changed={ setChanged() })
    private var itemCap = LazyOptional.of { inventory }
    private var energyCap = LazyOptional.of { energy }
    override fun <T: Any?> getCapability(cap: Capability<T>,side: Direction?): LazyOptional<T> {
        if (cap === ForgeCapabilities.ITEM_HANDLER) return itemCap.cast()
        if (cap === ForgeCapabilities.ENERGY) return energyCap.cast()
        return super.getCapability(cap,side)
    }
    override fun invalidateCaps() { super.invalidateCaps(); itemCap.invalidate(); energyCap.invalidate() }
    override fun reviveCaps() { super.reviveCaps(); itemCap = LazyOptional.of { inventory }; energyCap = LazyOptional.of { energy } }
    fun canAttach(direction: Direction) = !heads.containsKey(direction) && heads.size < TurretStatsTable.FACE_LIMITS[tier-1]
    fun attachHead(direction: Direction,head: TurretHeadBlockEntity) {
        if (heads[direction] === head) return
        if (head.facing.opposite != direction || head.blockPos != blockPos.relative(direction)) return
        if (!heads.containsKey(direction) && !canAttach(direction)) { head.dropOrphan(); return }
        heads.put(direction,head)?.unbindBase()
        head.bindBase(this); head.refreshStats(); setChanged()
    }
    fun detachHead(direction: Direction,head: TurretHeadBlockEntity) { if (heads[direction] === head) { heads.remove(direction); setChanged() } }
    fun bindCore(candidate: BaseCoreBlockEntity?) {
        if (core === candidate) return
        core?.unregisterBase(this)
        core = if (candidate != null && candidate.registerBase(this)) candidate else null
        heads.values.forEach { it.refreshStats() }
    }
    fun findCore() {
        val world = level as? ServerLevel ?: return
        val available = LevelRuntime.get(world).cores.values.filter { !it.isRemoved && it.inRange(blockPos) && (it.owner == null || owner == null || it.owner == owner || (owner != null && it.trusted(owner!!))) && (it.managedBases.containsKey(blockPos.asLong()) || it.managedBases.size < Config.maxBases.get()) }
        bindCore(available.minByOrNull { it.blockPos.distSqr(blockPos) })
    }
    override fun assignOwner(id: UUID) { super.assignOwner(id); findCore() }
    override fun allowed(player: Player) = super.allowed(player) || core?.trusted(player.uuid) == true
    override fun onLoad() {
        super.onLoad()
        val world = level as? ServerLevel ?: return
        LevelRuntime.get(world).bases.put(blockPos.asLong(),this)
        refreshModules(); findCore()
        for (direction in Direction.values()) {
            val headPos = blockPos.relative(direction)
            if (world.chunkSource.getChunkNow(headPos.x shr 4,headPos.z shr 4) != null) (world.getBlockEntity(headPos) as? TurretHeadBlockEntity)?.let { attachHead(direction,it) }
        }
    }
    fun refreshModules() {
        modules = (0 until minOf(5,tier)).mapNotNull { ModuleEffect.from(inventory.getStackInSlot(it)) }
        heads.values.forEach { it.refreshStats() }
    }
    fun serverTick() {
        val world = level as? ServerLevel ?: return
        val now = world.gameTime
        if (upgradeJob != null) com.turretdefense.turret.UpgradeRules.process(this)
        if (now%TurretStatsTable.MAINTENANCE_INTERVAL == blockPos.asLong().and(63L)%TurretStatsTable.MAINTENANCE_INTERVAL) {
            findCore()
        }
        if (now%Config.transferInterval.get() != Math.floorMod(blockPos.asLong(),Config.transferInterval.get().toLong())) return
        val start = System.nanoTime()
        heads.values.forEach { com.turretdefense.loot.LootRouting.flush(it) }
        val target = core?.takeIf { !it.isRemoved && it.inRange(blockPos) }
        if (target != null) {
            // 先传附魔之瓶再传其他掉落，缓存紧张时经验仍有优先输出机会。
            for (bottles in listOf(true,false)) for (slot in 5 until inventory.slots) {
                val stack = inventory.getStackInSlot(slot)
                if (stack.isEmpty || stack.`is`(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE) != bottles) continue
                val remaining = target.acceptLoot(stack.copy())
                if (remaining.count != stack.count) inventory.setStackInSlot(slot,remaining)
            }
        }
        LevelRuntime.get(world).recordNanos(start)
    }
    fun acceptLoot(stack: ItemStack): ItemStack {
        var remaining = stack
        for (slot in 5 until inventory.slots) {
            remaining = inventory.insertItem(slot,remaining,false)
            if (remaining.isEmpty) return remaining
        }
        warn("message.turretdefense.cache_full")
        return remaining
    }
    fun warn(key: String) {
        val world = level as? ServerLevel ?: return
        if (world.gameTime-lastWarning < TurretStatsTable.WARNING_INTERVAL) return
        val player = owner?.let { world.server.playerList.getPlayer(it) } ?: return
        lastWarning = world.gameTime; com.turretdefense.ui.UiNetwork.alert(player,key)
    }
    fun sound(event: SoundEvent) {
        val world = level as? ServerLevel ?: return
        val now = world.gameTime
        if (now/20 != soundWindow) { soundWindow = now/20; sounds = 0 }
        if (now == lastSound || sounds >= Config.maxSounds.get()) return
        lastSound = now; sounds++
        world.playSound(null,blockPos,event,SoundSource.BLOCKS,0.8f,1.0f)
    }
    fun activateOverload(): Boolean {
        val now = level?.gameTime ?: return false
        if (!modules.contains(ModuleEffect.Overload) || now < overheatUntil) return false
        overloadUntil = now+TurretStatsTable.OVERLOAD_DURATION
        overheatUntil = overloadUntil+TurretStatsTable.OVERHEAT_DURATION
        setChanged(); return true
    }
    // 普通塔头损毁保留共享插件；只有核心殉爆时按底座统一结算一次。
    fun martyrModules() {
        val world = level as? ServerLevel ?: return
        for (slot in 0..4) {
            val stack = inventory.getStackInSlot(slot)
            if (stack.isEmpty) continue
            val effect = ModuleEffect.from(stack)
            if (world.random.nextDouble() < if (effect?.special == true) 0.1 else 0.5) Block.popResource(world,blockPos,stack.copy())
            inventory.setStackInSlot(slot,ItemStack.EMPTY)
        }
    }
    fun dismantle() {
        com.turretdefense.turret.UpgradeRules.cancel(this)
        heads.values.toList().forEach { it.dropOrphan() }
        val world = level as? ServerLevel ?: return
        for (slot in 0 until inventory.slots) { val stack = inventory.getStackInSlot(slot); if (!stack.isEmpty) Block.popResource(world,blockPos,stack.copy()); inventory.setStackInSlot(slot,ItemStack.EMPTY) }
    }
    override fun setRemoved() {
        (level as? ServerLevel)?.let { val runtime = LevelRuntime.peek(it); if (runtime != null && runtime.bases[blockPos.asLong()] === this) runtime.bases.remove(blockPos.asLong()) }
        core?.unregisterBase(this); core = null
        heads.values.toList().forEach { it.unbindBase() }; heads.clear()
        super.setRemoved()
    }
    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag); tag.put("Inventory",inventory.serializeNBT()); tag.putInt("Energy",energy.stored)
        tag.putLong("Overload",overloadUntil); tag.putLong("Overheat",overheatUntil)
    }
    override fun load(tag: CompoundTag) {
        super.load(tag); inventory.deserializeNBT(tag.getCompound("Inventory")); energy.stored = tag.getInt("Energy")
        overloadUntil = tag.getLong("Overload"); overheatUntil = tag.getLong("Overheat"); refreshModules()
    }
}
