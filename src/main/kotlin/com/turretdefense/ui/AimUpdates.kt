package com.turretdefense.ui

import com.turretdefense.blockentity.TurretHeadBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import java.util.IdentityHashMap
import java.util.UUID

// 每玩家只接收附近塔头，每头最多十刻一条，批量发送且不借用界面状态轮询。
object AimUpdates {
    const val DISTANCE = 24
    const val DISTANCE_SQUARED = 576.0
    private const val INTERVAL = 10L
    private const val MAX_BATCH = 512
    data class Aim(val position: BlockPos,val target: Vec3?,val camouflage: Int = -1,val hidden: Boolean = false)
    data class Packet(val dimension: ResourceLocation,val aims: List<Aim>)
    private data class Entry(val head: TurretHeadBlockEntity,var target: Vec3?)
    private class LevelData {
        val heads = linkedMapOf<Long,Entry>()
        val sent = mutableMapOf<UUID,MutableMap<Long,Long>>()
    }
    private val levels = IdentityHashMap<ServerLevel,LevelData>()
    private var registeredChannel: SimpleChannel? = null

    fun register(channel: SimpleChannel) {
        registeredChannel = channel
        channel.messageBuilder(Packet::class.java,4,NetworkDirection.PLAY_TO_CLIENT)
            .encoder { packet,buffer ->
                buffer.writeResourceLocation(packet.dimension); buffer.writeVarInt(packet.aims.size)
                for (aim in packet.aims) {
                    buffer.writeBlockPos(aim.position); buffer.writeBoolean(aim.target != null)
                    aim.target?.let { buffer.writeDouble(it.x); buffer.writeDouble(it.y); buffer.writeDouble(it.z) }
                    buffer.writeVarInt(aim.camouflage); buffer.writeBoolean(aim.hidden)
                }
            }
            .decoder { buffer ->
                val dimension = buffer.readResourceLocation()
                val count = buffer.readVarInt(); require(count in 0..MAX_BATCH)
                Packet(dimension,List(count) {
                    val position = buffer.readBlockPos()
                    val target = if (buffer.readBoolean()) Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble()).also { require(it.x.isFinite() && it.y.isFinite() && it.z.isFinite()) } else null
                    val camouflage = buffer.readVarInt(); require(camouflage in -1..1000000)
                    Aim(position,target,camouflage,buffer.readBoolean())
                })
            }
            .consumerMainThread { packet,context ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable { com.turretdefense.client.ClientAimTracking.receive(packet) } }
                context.get().setPacketHandled(true)
            }.add()
    }

    fun queue(head: TurretHeadBlockEntity,target: Vec3?) {
        val world = head.level as? ServerLevel ?: return
        if (head.isRemoved || world.players().isEmpty()) return
        if (target != null && (!target.x.isFinite() || !target.y.isFinite() || !target.z.isFinite())) return
        val data = levels.getOrPut(world) { LevelData() }
        val key = head.blockPos.asLong()
        val entry = data.heads[key]
        if (entry?.head === head) entry.target = target else data.heads[key] = Entry(head,target)
    }

    fun flush(level: ServerLevel) {
        val data = levels[level] ?: return
        val channel = registeredChannel ?: return
        val now = level.gameTime
        val removed = data.heads.filterValues { it.head.isRemoved || it.head.level !== level }.keys
        removed.forEach { data.heads.remove(it) }
        if (removed.isNotEmpty()) data.sent.values.forEach { it.keys.removeAll(removed) }
        val players = level.players()
        val activePlayers = players.mapTo(HashSet()) { it.uuid }
        data.sent.keys.retainAll(activePlayers)
        for (player in players) {
            val sent = data.sent.getOrPut(player.uuid) { mutableMapOf() }
            val batch = ArrayList<Aim>()
            for ((key,entry) in data.heads) {
                if (player.distanceToSqr(entry.head.center) > 16384.0) continue
                val previous = sent[key]
                if (previous != null && now-previous < INTERVAL) continue
                val camouflage = com.turretdefense.turret.ModuleRuntime.camouflage(entry.head)?.let { net.minecraft.world.level.block.Block.getId(it) } ?: -1
                batch.add(Aim(entry.head.blockPos,entry.target,camouflage,com.turretdefense.turret.AdvancedAttacks.phaseHidden(entry.head,now))); sent[key] = now
            }
            sent.keys.retainAll(data.heads.keys)
            for (values in batch.chunked(MAX_BATCH)) channel.send(PacketDistributor.PLAYER.with { player },Packet(level.dimension().location(),values))
        }
    }

    fun clear(level: ServerLevel) { levels.remove(level) }
    fun clearAll() { levels.clear() }
}
