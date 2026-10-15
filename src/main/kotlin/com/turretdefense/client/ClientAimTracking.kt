package com.turretdefense.client

import com.turretdefense.TurretDefense
import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.ui.AimUpdates
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.joml.Quaternionf
import java.util.IdentityHashMap

// 每个已加载塔头独立插值；移动目标只改旋转，只有进入或离开动画距离才重建区块。
@Mod.EventBusSubscriber(modid=TurretDefense.MOD_ID,bus=Mod.EventBusSubscriber.Bus.FORGE,value=[Dist.CLIENT])
object ClientAimTracking {
    private class Rotation(val previous: Quaternionf,val current: Quaternionf)
    private data class Pending(val aim: AimUpdates.Aim,val expires: Long)
    private val rotations = IdentityHashMap<TurretHeadBlockEntity,Rotation>()
    private val pending = linkedMapOf<Long,Pending>()
    private var clientTicks = 0L

    fun loaded(head: TurretHeadBlockEntity) {
        if (head.level?.isClientSide != true) return
        val initial = resting(head)
        rotations[head] = Rotation(Quaternionf(initial),initial)
        pending.remove(head.blockPos.asLong())?.let { if (it.expires >= clientTicks) apply(head,it.aim) }
        updateDistance(head)
    }

    fun removed(head: TurretHeadBlockEntity) {
        rotations.remove(head)
        pending.remove(head.blockPos.asLong())
    }

    fun receive(packet: AimUpdates.Packet) {
        val world = Minecraft.getInstance().level ?: return
        if (world.dimension().location() != packet.dimension) return
        for (aim in packet.aims) {
            val head = world.getBlockEntity(aim.position) as? TurretHeadBlockEntity
            if (head != null) {
                apply(head,aim)
                if (!rotations.containsKey(head)) loaded(head)
            } else {
                if (pending.size >= 1024) pending.remove(pending.keys.first())
                pending[aim.position.asLong()] = Pending(aim,clientTicks+40)
            }
        }
    }
    private fun apply(head: TurretHeadBlockEntity,aim: AimUpdates.Aim) {
        head.renderTarget = aim.target
        if (head.renderCamouflage == aim.camouflage && head.renderHidden == aim.hidden) return
        head.renderCamouflage = aim.camouflage; head.renderHidden = aim.hidden
        head.requestModelDataUpdate()
        head.level?.sendBlockUpdated(head.blockPos,head.blockState,head.blockState,Block.UPDATE_CLIENTS or Block.UPDATE_IMMEDIATE)
    }

    fun rotation(head: TurretHeadBlockEntity,partialTick: Float): Quaternionf {
        val value = rotations[head] ?: return resting(head)
        return Quaternionf(value.previous).slerp(value.current,partialTick.coerceIn(0.0f,1.0f))
    }

    private fun resting(head: TurretHeadBlockEntity): Quaternionf {
        val outward = head.facing.opposite.normal
        return Quaternionf().rotationTo(0.0f,1.0f,0.0f,outward.x.toFloat(),outward.y.toFloat(),outward.z.toFloat())
    }

    private fun updateDistance(head: TurretHeadBlockEntity) {
        val client = Minecraft.getInstance()
        val player = client.player
        val enabled = player != null && head.level === client.level && player.distanceToSqr(head.center) <= AimUpdates.DISTANCE_SQUARED
        if (head.dynamicRender == enabled) return
        head.dynamicRender = enabled
        head.requestModelDataUpdate()
        head.level?.sendBlockUpdated(head.blockPos,head.blockState,head.blockState,Block.UPDATE_CLIENTS or Block.UPDATE_IMMEDIATE)
    }

    @JvmStatic
    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        clientTicks++
        val world = Minecraft.getInstance().level
        val iterator = rotations.entries.iterator()
        while (iterator.hasNext()) {
            val (head,rotation) = iterator.next()
            if (head.isRemoved || head.level !== world) { iterator.remove(); continue }
            updateDistance(head)
            rotation.previous.set(rotation.current)
            if (!head.dynamicRender) continue
            val outward = Vec3.atLowerCornerOf(head.facing.opposite.normal)
            val pivot = head.center.add(outward.scale(1.0/16.0))
            val direction = head.renderTarget?.subtract(pivot)?.takeIf { it.lengthSqr() > 1.0e-8 }?.normalize() ?: outward
            val desired = Quaternionf().rotationTo(0.0f,1.0f,0.0f,direction.x.toFloat(),direction.y.toFloat(),direction.z.toFloat())
            rotation.current.slerp(desired,0.35f).normalize()
        }
        pending.entries.removeIf { it.value.expires < clientTicks }
    }

    @JvmStatic
    @SubscribeEvent
    fun logout(event: ClientPlayerNetworkEvent.LoggingOut) {
        rotations.clear(); pending.clear(); clientTicks = 0
    }
}
