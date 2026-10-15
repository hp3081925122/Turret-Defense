package com.turretdefense.compat

import appeng.api.config.Actionable
import appeng.api.config.PowerMultiplier
import appeng.api.features.IPlayerRegistry
import appeng.api.networking.GridHelper
import appeng.api.networking.IGrid
import appeng.api.networking.IGridNode
import appeng.api.networking.security.IActionHost
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.AEItemKey
import appeng.api.storage.StorageHelper
import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.core.CoreConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.floor

// 所有AE2类型只出现在软依赖桥接文件，类本身没有静态注册或持久化网格引用。
class Ae2Compat : CoreNetworkFactory {
    override fun find(core: BaseCoreBlockEntity): List<CoreNetwork> {
        val world = core.level as? ServerLevel ?: return emptyList()
        if (core.owner == null) return emptyList()
        val result = ArrayList<CoreNetwork>()
        val seen = Collections.newSetFromMap(IdentityHashMap<IGrid,Boolean>())
        val radius = CoreConfig.outputRadius.get()
        for (cursor in BlockPos.betweenClosed(core.blockPos.offset(-radius,-radius,-radius),core.blockPos.offset(radius,radius,radius))) {
            val pos = cursor.immutable()
            if (pos == core.blockPos || world.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4) == null) continue
            val host = GridHelper.getNodeHost(world,pos) ?: continue
            for (side in Direction.values()) {
                val node = host.getGridNode(side) ?: continue
                if (!node.isActive || !permitted(core,world,node)) continue
                val grid = node.grid
                if (seen.add(grid)) result.add(Network(core,world,pos,node,grid))
            }
        }
        return result
    }

    // AE2当前版本没有旧安全服务；先校验节点归属，避免离线机器身份跨主人取用。
    private fun permitted(core: BaseCoreBlockEntity,world: ServerLevel,node: IGridNode): Boolean {
        val owner = IPlayerRegistry.getMapping(world.server).getProfileId(node.owningPlayerId) ?: return false
        return owner == core.owner || core.trusted(owner)
    }

    private inner class Network(private val core: BaseCoreBlockEntity,private val world: ServerLevel,private val pos: BlockPos,private val node: IGridNode,private val grid: IGrid) : CoreNetwork {
        private val host = object : IActionHost { override fun getActionableNode() = node }
        private fun active(): Boolean {
            if (core.isRemoved || world.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4) == null) return false
            val current = GridHelper.getNodeHost(world,pos) ?: return false
            return Direction.values().any { current.getGridNode(it) === node } && node.isActive && node.grid === grid && permitted(core,world,node)
        }
        private fun source(): IActionSource {
            val player = core.owner?.let { world.server.playerList.getPlayer(it) }
            return if (player != null) IActionSource.ofPlayer(player,host) else IActionSource.ofMachine(host)
        }
        override fun insert(stack: ItemStack,amount: Int): Int {
            if (amount <= 0 || !active()) return 0
            val key = AEItemKey.of(stack) ?: return 0
            val inventory = grid.storageService.inventory
            val source = source()
            val count = minOf(amount,stack.count).toLong()
            val possible = StorageHelper.poweredInsert(grid.energyService,inventory,key,count,source,Actionable.SIMULATE).coerceIn(0,count)
            return StorageHelper.poweredInsert(grid.energyService,inventory,key,possible,source,Actionable.MODULATE).coerceIn(0,possible).toInt()
        }
        override fun extract(template: ItemStack,amount: Int): ItemStack {
            if (amount <= 0 || !active()) return ItemStack.EMPTY
            val key = AEItemKey.of(template) ?: return ItemStack.EMPTY
            val inventory = grid.storageService.inventory
            val source = source()
            val count = minOf(amount,template.maxStackSize).toLong()
            val possible = StorageHelper.poweredExtraction(grid.energyService,inventory,key,count,source,Actionable.SIMULATE).coerceIn(0,count)
            val actual = StorageHelper.poweredExtraction(grid.energyService,inventory,key,possible,source,Actionable.MODULATE).coerceIn(0,possible)
            return key.toStack(actual.toInt())
        }
        override fun energy(maxFe: Int): Int {
            if (maxFe <= 0 || !active()) return 0
            val service = grid.energyService
            if (!service.isNetworkPowered) return 0
            val rate = CoreConfig.ae2EnergyRate.get()
            val tag = core.persistentData
            val carried = tag.getDouble(REMAINDER).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val fraction = CoreConfig.ae2EnergyFraction.get()
            val limit = minOf(maxFe.toDouble(),CoreConfig.ae2EnergyLimit.get().toDouble())
            val cached = minOf(service.storedPower,service.maxStoredPower).coerceAtLeast(0.0)
            val requested = ((limit-carried).coerceAtLeast(0.0)/rate).coerceAtMost(cached*fraction)
            val possible = service.extractAEPower(requested,Actionable.SIMULATE,PowerMultiplier.ONE).coerceIn(0.0,requested)
            val actual = service.extractAEPower(possible,Actionable.MODULATE,PowerMultiplier.ONE).coerceIn(0.0,possible)
            val total = carried+actual*rate
            val received = floor(total).toInt().coerceIn(0,maxFe)
            // 分数FE保存在Forge持久化数据中，卸载和重启也不丢弃已提取能量。
            tag.putDouble(REMAINDER,total-received); core.setChanged()
            return received
        }
    }
    companion object { private const val REMAINDER = "TurretDefenseAe2EnergyRemainder" }
}
