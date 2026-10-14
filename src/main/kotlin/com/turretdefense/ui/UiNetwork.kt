package com.turretdefense.ui

import com.google.gson.Gson
import com.sighs.apricityui.util.kjs.ApricityUIServerUtil
import com.sighs.apricityui.container.filter.FilterUtil
import com.turretdefense.TurretDefense
import com.turretdefense.blockentity.*
import com.turretdefense.network.LevelRuntime
import com.turretdefense.registry.ModTags
import com.turretdefense.turret.ModuleEffect
import com.turretdefense.turret.UpgradeRules
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import java.util.UUID

// 槽位同步由真容器负责；按钮和状态使用限频网络桥，所有世界修改都在服务端执行。
object UiNetwork {
    data class StatePacket(val json: String)
    data class ActionPacket(val position: BlockPos,val action: String,val face: Int,val value: String)
    data class AlertPacket(val key: String)
    private data class Viewer(val player: ServerPlayer,val position: BlockPos,val menu: AbstractContainerMenu,var nextUpdate: Long = 0,var lastAction: Long = Long.MIN_VALUE/2)
    private val viewers = mutableMapOf<UUID,Viewer>()
    val gson = Gson()
    val channel = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath(TurretDefense.MOD_ID,"ui"),{ "1" },{ it == "1" },{ it == "1" })
    fun register() {
        channel.messageBuilder(StatePacket::class.java,0,NetworkDirection.PLAY_TO_CLIENT)
            .encoder { packet,buffer -> buffer.writeUtf(packet.json,131072) }.decoder { StatePacket(it.readUtf(131072)) }
            .consumerMainThread { packet,context -> ClientBindings.receiveState(packet.json); context.get().setPacketHandled(true) }.add()
        channel.messageBuilder(ActionPacket::class.java,1,NetworkDirection.PLAY_TO_SERVER)
            .encoder { packet,buffer -> buffer.writeBlockPos(packet.position); buffer.writeUtf(packet.action,32); buffer.writeVarInt(packet.face); buffer.writeUtf(packet.value,128) }
            .decoder { ActionPacket(it.readBlockPos(),it.readUtf(32),it.readVarInt(),it.readUtf(128)) }
            .consumerMainThread { packet,context -> context.get().sender?.let { action(it,packet) }; context.get().setPacketHandled(true) }.add()
        EffectBatch.register(channel)
        channel.messageBuilder(AlertPacket::class.java,3,NetworkDirection.PLAY_TO_CLIENT)
            .encoder { packet,buffer -> buffer.writeUtf(packet.key,128) }.decoder { AlertPacket(it.readUtf(128)) }
            .consumerMainThread { packet,context -> ClientBindings.receiveAlert(packet.key); context.get().setPacketHandled(true) }.add()
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable { ClientBindings.initialize() } }
    }
    fun open(player: ServerPlayer,pos: BlockPos) {
        val entity = player.serverLevel().getBlockEntity(pos)
        if (entity !is MachineBlockEntity || !entity.allowed(player)) return
        val core = entity is BaseCoreBlockEntity
        ApricityUIServerUtil.menu(player,if (core) "screens/base_core.html" else "screens/turret_base.html").bind { binding ->
            val slots = binding.blockEntity(pos)
            if (core) {
                slots.slot("#fuel").filter(FilterUtil.custom { stack -> net.minecraftforge.common.ForgeHooks.getBurnTime(stack,net.minecraft.world.item.crafting.RecipeType.SMELTING) > 0 })
                for (index in 0 until 18) slots.slot("#ammo_$index").filter(FilterUtil.tag(ModTags.AMMO))
            } else for (index in 0 until 5) slots.slot("#module_$index").filter(FilterUtil.tag("turretdefense:module"))
            slots.player()
        }
        viewers[player.uuid] = Viewer(player,pos,player.containerMenu)
        update(viewers.getValue(player.uuid))
    }
    private fun action(player: ServerPlayer,packet: ActionPacket) {
        val viewer = viewers[player.uuid] ?: return
        val now = player.serverLevel().gameTime
        if (viewer.position != packet.position || viewer.menu !== player.containerMenu || now-viewer.lastAction < 2 || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(packet.position)) > 64.0) return
        val entity = player.serverLevel().getBlockEntity(packet.position) as? MachineBlockEntity ?: return
        if (!entity.allowed(player)) return
        viewer.lastAction = now
        val success = when (packet.action) {
            "upgrade_core" -> entity is BaseCoreBlockEntity && UpgradeRules.upgradeCore(player,entity)
            "upgrade_base" -> entity is TurretBaseBlockEntity && UpgradeRules.upgradeBase(player,entity)
            "upgrade_head" -> entity is TurretBaseBlockEntity && packet.face in 0..5 && entity.heads[Direction.values()[packet.face]]?.let { UpgradeRules.upgradeHead(player,it) } == true
            "cancel_upgrade" -> UpgradeRules.cancel(entity)
            "cancel_head_upgrade" -> entity is TurretBaseBlockEntity && packet.face in 0..5 && entity.heads[Direction.values()[packet.face]]?.let { UpgradeRules.cancel(it) } == true
            "overload" -> entity is TurretBaseBlockEntity && entity.activateOverload()
            "priority" -> {
                if (entity !is TurretBaseBlockEntity || packet.face !in 0..5 || !entity.modules.contains(ModuleEffect.Priority)) false
                else entity.heads[Direction.values()[packet.face]]?.let { head ->
                    if (packet.value in listOf("nearest","highest_health","lowest_health")) { head.configurePriority(packet.value); true }
                    else net.minecraft.resources.ResourceLocation.tryParse(packet.value)?.let { id ->
                        net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(id)?.let { type -> head.configurePriority("type",type); true }
                    } ?: false
                } ?: false
            }
            "trust_add","trust_remove" -> {
                if (entity !is BaseCoreBlockEntity || (entity.owner != player.uuid && !player.isCreative)) false
                else try { val id = UUID.fromString(packet.value); com.turretdefense.network.TurretNetworkData.get(player.serverLevel()).trust(entity.blockPos.asLong(),id,packet.action == "trust_add"); true } catch (_: IllegalArgumentException) { false }
            }
            else -> false
        }
        if (!success) player.displayClientMessage(Component.translatable("message.turretdefense.action_failed"),true)
        update(viewer)
    }
    fun tick() {
        val iterator = viewers.values.iterator()
        while (iterator.hasNext()) {
            val viewer = iterator.next(); val player = viewer.player
            val machine = player.serverLevel().getBlockEntity(viewer.position) as? MachineBlockEntity
            if (player.hasDisconnected() || player.containerMenu !== viewer.menu) { iterator.remove(); continue }
            if (machine == null || !machine.allowed(player) || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(viewer.position)) > 64.0) { player.closeContainer(); iterator.remove(); continue }
            val now = player.serverLevel().gameTime
            if (now >= viewer.nextUpdate) { update(viewer); viewer.nextUpdate = now+10 }
        }
    }
    private fun job(machine: MachineBlockEntity): Map<String,Any> {
        val recipe = UpgradeRules.recipe(machine) ?: UpgradeRules.nextRecipe(machine)
        return mapOf("active" to (machine.upgradeJob != null),"paid" to (machine.upgradeJob?.paid ?: 0),"cost" to (recipe?.energyCost ?: 0),"materials" to (recipe?.costs?.map { mapOf("key" to (it.ingredient.items.firstOrNull()?.descriptionId ?: "ui.turretdefense.empty"),"count" to it.count) } ?: emptyList()),"available" to (recipe != null))
    }
    private fun headState(head: TurretHeadBlockEntity) = mapOf("kind" to head.kind.id,"tier" to head.tier,"health" to head.health,"maxHealth" to head.effectiveStats.health,"shield" to head.shieldLayers,"maxShield" to head.effectiveStats.shield,"state" to head.status,"priority" to head.priority,"kills" to head.kills,"xp" to head.pendingXp,"upgrade" to job(head))
    private fun update(viewer: Viewer) {
        val world = viewer.player.serverLevel()
        val entity = world.getBlockEntity(viewer.position) as? MachineBlockEntity ?: return
        if (!entity.allowed(viewer.player)) return
        val state = linkedMapOf<String,Any>("x" to viewer.position.x,"y" to viewer.position.y,"z" to viewer.position.z,"dimension" to world.dimension().location().toString(),"tier" to entity.tier,"owner" to (entity.owner?.toString() ?: ""))
        state["upgrade"] = job(entity)
        when (entity) {
            is TurretBaseBlockEntity -> {
                state["type"] = "base"; state["energy"] = entity.energy.stored; state["maxEnergy"] = entity.energy.getMaxEnergyStored()
                state["priorityEnabled"] = entity.modules.contains(ModuleEffect.Priority); state["overloadEnabled"] = entity.modules.contains(ModuleEffect.Overload)
                state["core"] = entity.core?.blockPos?.toShortString() ?: ""; state["heads"] = Direction.values().map { direction -> entity.heads[direction]?.let { headState(it) } ?: emptyMap<String,Any>() }
                state["overload"] = entity.overloadUntil; state["overheat"] = entity.overheatUntil; state["time"] = world.gameTime
            }
            is BaseCoreBlockEntity -> {
                state["type"] = "core"; state["energy"] = entity.energy.stored; state["maxEnergy"] = entity.energy.getMaxEnergyStored()
                state["health"] = entity.health; state["shield"] = entity.shield; state["burn"] = entity.burnTicks; state["range"] = entity.stats.range
                state["state"] = entity.powerState; state["scanInterval"] = entity.currentScanInterval; state["enemies"] = entity.snapshot.targets.size
                state["trusted"] = com.turretdefense.network.TurretNetworkData.get(world).trustedPlayers(entity.blockPos.asLong()).map { it.toString() }
                state["bases"] = entity.managedBases.values.map { base -> mapOf("pos" to base.blockPos.toShortString(),"tier" to base.tier,"energy" to base.energy.stored,"heads" to base.heads.values.map { headState(it) }) }
            }
        }
        channel.send(PacketDistributor.PLAYER.with { viewer.player },StatePacket(gson.toJson(state)))
    }
    fun alert(player: ServerPlayer,key: String) { channel.send(PacketDistributor.PLAYER.with { player },AlertPacket(key)) }
    fun clear() { viewers.clear() }
}
