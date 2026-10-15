package com.turretdefense.core

import com.turretdefense.TurretDefense
import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.blockentity.MachineBlockEntity
import com.turretdefense.compat.CoreNetwork
import com.turretdefense.compat.CoreNetworkFactory
import com.turretdefense.registry.ModContent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.fml.ModList
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.ItemHandlerHelper
import net.minecraftforge.registries.ForgeRegistries
import java.util.Collections
import java.util.IdentityHashMap

// 周期性自动化只扫描已加载区块；ME兼容类在确认模组存在后才加载。
object CoreAutomation {
    private val factory: CoreNetworkFactory? by lazy {
        if (!ModList.get().isLoaded("ae2")) null else try {
            Class.forName("com.turretdefense.compat.Ae2Compat").getDeclaredConstructor().newInstance() as CoreNetworkFactory
        } catch (failure: ReflectiveOperationException) {
            TurretDefense.LOGGER.error("AE2 integration could not initialize",failure); null
        } catch (failure: LinkageError) {
            TurretDefense.LOGGER.error("AE2 integration API is incompatible",failure); null
        }
    }
    fun tick(core: BaseCoreBlockEntity) {
        val world = core.level as? ServerLevel ?: return
        if (core.isRemoved || world.gameTime%CoreConfig.outputInterval.get() != 0L) return
        if (!CoreConfig.outputEnabled.get() && !CoreConfig.ae2Enabled.get()) return
        val networks = if (CoreConfig.ae2Enabled.get()) factory?.find(core).orEmpty() else emptyList()
        if (CoreConfig.outputEnabled.get()) output(core,networks,containers(core,world))
        if (networks.isNotEmpty()) { supplyEnergy(core,networks); refillAmmo(core,networks) }
    }

    // 防止输出回到另一个炮塔机器的燃料、弹药或模块槽，也不缓存可卸载的能力。
    private fun containers(core: BaseCoreBlockEntity,world: ServerLevel): List<IItemHandler> {
        val result = ArrayList<IItemHandler>()
        val seen = Collections.newSetFromMap(IdentityHashMap<IItemHandler,Boolean>())
        val radius = CoreConfig.outputRadius.get()
        for (pos in BlockPos.betweenClosed(core.blockPos.offset(-radius,-radius,-radius),core.blockPos.offset(radius,radius,radius))) {
            val chunk = world.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4) ?: continue
            val entity = chunk.getBlockEntity(pos) ?: continue
            if (entity.isRemoved || entity is MachineBlockEntity || ForgeRegistries.BLOCKS.getKey(entity.blockState.block)?.namespace == "ae2") continue
            for (side in Direction.values()) {
                entity.getCapability(ForgeCapabilities.ITEM_HANDLER,side).ifPresent { handler -> if (seen.add(handler)) result.add(handler) }
            }
            entity.getCapability(ForgeCapabilities.ITEM_HANDLER,null).ifPresent { handler -> if (seen.add(handler)) result.add(handler) }
        }
        return result
    }

    // 只扣除实际接收数量，满仓或受过滤限制时物品仍留在原战利品槽。
    private fun output(core: BaseCoreBlockEntity,networks: List<CoreNetwork>,containers: List<IItemHandler>) {
        var budget = CoreConfig.outputLimit.get()
        val slots = (19..45).sortedBy { if (core.inventory.getStackInSlot(it).`is`(Items.EXPERIENCE_BOTTLE)) 0 else 1 }
        for (slot in slots) {
            for (network in networks) {
                val stack = core.inventory.getStackInSlot(slot)
                if (stack.isEmpty || budget <= 0) break
                val count = minOf(stack.count,budget)
                val moved = network.insert(stack.copyWithCount(count),count).coerceIn(0,count)
                if (moved > 0) { core.inventory.extractItem(slot,moved,false); budget -= moved }
            }
            for (container in containers) {
                val stack = core.inventory.getStackInSlot(slot)
                if (stack.isEmpty || budget <= 0) break
                val count = minOf(stack.count,budget)
                val candidate = stack.copyWithCount(count)
                val possible = count-ItemHandlerHelper.insertItemStacked(container,candidate,true).count
                if (possible <= 0) continue
                val accepted = possible-ItemHandlerHelper.insertItemStacked(container,stack.copyWithCount(possible),false).count
                val moved = accepted.coerceIn(0,possible)
                if (moved > 0) { core.inventory.extractItem(slot,moved,false); budget -= moved }
            }
            if (budget <= 0) return
        }
    }

    // 在提取AE前确认FE接收空间，供能总量受配置限制而不是按接口数量放大。
    private fun supplyEnergy(core: BaseCoreBlockEntity,networks: List<CoreNetwork>) {
        var budget = CoreConfig.ae2EnergyLimit.get()
        for (network in networks) {
            val capacity = core.energy.receiveEnergy(budget,true)
            if (capacity <= 0) return
            val received = network.energy(capacity).coerceIn(0,capacity)
            if (received > 0) { core.energy.receiveEnergy(received,false); budget -= received }
        }
    }

    // 绑定塔头形成需求列表，只在库存低于阈值时按种类分配有限补给预算。
    private fun refillAmmo(core: BaseCoreBlockEntity,networks: List<CoreNetwork>) {
        val demand = core.managedBases.values.filter { !it.isRemoved && it.core === core }.flatMap { base ->
            base.heads.values.filter { !it.isRemoved && it.tier >= 4 }.mapNotNull { head -> if (head.tier == 4) head.kind.ammo4 else head.kind.ammo5 }
        }.distinct()
        if (demand.isEmpty()) return
        val capacity = (1..18).sumOf { core.inventory.getSlotLimit(it) }
        val quota = (capacity/demand.size).coerceAtLeast(1)
        var budget = CoreConfig.outputLimit.get()
        for ((index,id) in demand.withIndex()) {
            val registered = ModContent.ALL_ITEMS[id] ?: continue
            val template = ItemStack(registered.get())
            val stored = (1..18).sumOf { slot -> core.inventory.getStackInSlot(slot).let { if (ItemHandlerHelper.canItemStacksStack(it,template)) it.count else 0 } }
            if (stored.toDouble()/quota >= CoreConfig.ae2Refill.get()) continue
            val allowance = (budget/(demand.size-index)).coerceAtLeast(1)
            var remainingBudget = minOf(budget,allowance,quota-stored)
            for (network in networks) {
                if (remainingBudget <= 0) break
                val wanted = minOf(remainingBudget,template.maxStackSize)
                val candidate = template.copyWithCount(wanted)
                var simulated = candidate
                for (slot in 1..18) { simulated = core.inventory.insertItem(slot,simulated,true); if (simulated.isEmpty) break }
                val room = wanted-simulated.count
                if (room <= 0) break
                var extracted = network.extract(template,room)
                val actual = extracted.count
                for (slot in 1..18) { extracted = core.inventory.insertItem(slot,extracted,false); if (extracted.isEmpty) break }
                // 原子服务端执行下槽位不变；仍保留异常返回的余额而不生成掉落物。
                if (!extracted.isEmpty) core.acceptLoot(extracted)
                remainingBudget -= actual; budget -= actual
            }
            if (budget <= 0) break
        }
    }
}
