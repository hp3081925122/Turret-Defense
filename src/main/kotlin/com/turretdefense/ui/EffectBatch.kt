package com.turretdefense.ui

import com.turretdefense.config.Config
import com.turretdefense.network.LevelRuntime
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import java.util.IdentityHashMap

// 原版粒子包不能携带多条不同位置的射线，故按空间区域与类型一次批量发送轨迹数据。
object EffectBatch {
    data class Effect(val type: Int,val start: Vec3,val end: Vec3)
    data class Packet(val distance: Int,val effects: List<Effect>)
    private data class Region(val type: Int,val x: Int,val y: Int,val z: Int)
    private val pending = IdentityHashMap<ServerLevel,MutableList<Effect>>()
    fun register(channel: SimpleChannel) {
        channel.messageBuilder(Packet::class.java,2,NetworkDirection.PLAY_TO_CLIENT)
            .encoder { packet,buffer ->
                buffer.writeVarInt(packet.distance); buffer.writeVarInt(packet.effects.size)
                packet.effects.forEach { effect -> buffer.writeByte(effect.type); buffer.writeDouble(effect.start.x); buffer.writeDouble(effect.start.y); buffer.writeDouble(effect.start.z); buffer.writeDouble(effect.end.x); buffer.writeDouble(effect.end.y); buffer.writeDouble(effect.end.z) }
            }
            .decoder { buffer ->
                val distance = buffer.readVarInt().coerceIn(0,128)
                val count = buffer.readVarInt(); require(count in 0..512)
                Packet(distance,List(count) { Effect(buffer.readUnsignedByte().toInt(),Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble()),Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble())) })
            }
            .consumerMainThread { packet,context -> ClientBindings.effects(packet); context.get().setPacketHandled(true) }.add()
    }
    fun line(level: ServerLevel,type: Int,start: Vec3,end: Vec3) {
        if (level.players().isEmpty()) return
        val batch = pending.getOrPut(level) { ArrayList() }
        if (batch.size < 512) batch.add(Effect(type,start,end))
    }
    fun flush(level: ServerLevel) {
        val effects = pending.remove(level) ?: return
        val runtime = LevelRuntime.get(level)
        val groups = effects.groupBy { Region(it.type,kotlin.math.floor(it.start.x/32).toInt(),kotlin.math.floor(it.start.y/32).toInt(),kotlin.math.floor(it.start.z/32).toInt()) }
        for ((region,values) in groups) {
            val point = PacketDistributor.TargetPoint(region.x*32.0+16,region.y*32.0+16,region.z*32.0+16,Config.particleDistance.get()+28.0,level.dimension())
            UiNetwork.channel.send(PacketDistributor.NEAR.with { point },Packet(Config.particleDistance.get(),values))
            runtime.particlePackets++
        }
    }
    fun clear(level: ServerLevel) { pending.remove(level) }
    fun clearAll() { pending.clear() }
}
