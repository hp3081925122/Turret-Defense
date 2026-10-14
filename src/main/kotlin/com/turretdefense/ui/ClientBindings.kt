package com.turretdefense.ui

import com.google.gson.JsonParser
import com.sighs.apricityui.registry.annotation.KJSBindings
import com.sighs.apricityui.init.Document
import com.sighs.apricityui.util.kjs.ApricityUIClientUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

// 此文件仅从客户端分支触达，服务器桥接类的方法签名不包含任何客户端类型。
@KJSBindings(value="TurretDefense",modId="turretdefense",isClient=true)
object ClientBindings {
    private var json = "{}"
    private var alertKey = ""
    private var alertUntil = 0L
    private var overlay: Document? = null
    private var particleWindow = -1L
    private var particles = 0
    private var cursor = 0
    fun initialize() {
        com.sighs.apricityui.script.KubeJS.scanPackage("com.turretdefense.ui")
        MinecraftForge.EVENT_BUS.register(this)
    }
    fun receiveState(value: String) { json = value }
    fun receiveAlert(key: String) {
        alertKey = key; alertUntil = (Minecraft.getInstance().level?.gameTime ?: 0)+160
        if (overlay == null) overlay = ApricityUIClientUtil.createDocument("overlays/energy_alert.html")
    }
    @SubscribeEvent fun clientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val world = Minecraft.getInstance().level
        if (world == null || world.gameTime >= alertUntil) { overlay?.remove(); overlay = null; alertKey = ""; if (world == null) json = "{}" }
    }
    @JvmStatic fun getStateJson(): String = json
    @JvmStatic fun text(key: String): String = I18n.get(key)
    @JvmStatic fun getAlert(): String = if (alertKey.isEmpty()) "" else I18n.get(alertKey)
    @JvmStatic fun getHeadInfo(faceIndex: Int): List<String>? {
        val value = JsonParser.parseString(json).asJsonObject
        val heads = value.getAsJsonArray("heads") ?: return null
        if (faceIndex !in 0 until heads.size()) return null
        val head = heads[faceIndex].asJsonObject
        if (!head.has("kind")) return null
        return listOf(head.get("kind").asString,head.get("tier").asString,head.get("health").asString,head.get("shield").asString,head.get("state").asString)
    }
    @JvmStatic fun requestUpgrade(faceIndex: Int): Boolean = request("upgrade_head",faceIndex,"")
    @JvmStatic fun upgradeBase(): Boolean = request("upgrade_base",-1,"")
    @JvmStatic fun upgradeCore(): Boolean = request("upgrade_core",-1,"")
    @JvmStatic fun cancelUpgrade(): Boolean = request("cancel_upgrade",-1,"")
    @JvmStatic fun cancelHeadUpgrade(faceIndex: Int): Boolean = request("cancel_head_upgrade",faceIndex,"")
    @JvmStatic fun overload(): Boolean = request("overload",-1,"")
    @JvmStatic fun setTargetPriority(faceIndex: Int,value: String): Boolean = request("priority",faceIndex,value)
    @JvmStatic fun trust(value: String,add: Boolean): Boolean = request(if (add) "trust_add" else "trust_remove",-1,value)
    private fun request(action: String,face: Int,value: String): Boolean {
        val state = JsonParser.parseString(json).asJsonObject
        if (!state.has("x") || Minecraft.getInstance().level?.dimension()?.location()?.toString() != state.get("dimension")?.asString) return false
        UiNetwork.channel.sendToServer(UiNetwork.ActionPacket(BlockPos(state.get("x").asInt,state.get("y").asInt,state.get("z").asInt),action,face,value))
        return true
    }
    fun effects(packet: EffectBatch.Packet) {
        val client = Minecraft.getInstance(); val world = client.level ?: return
        val now = world.gameTime
        if (particleWindow != now) { particleWindow = now; particles = 0 }
        val player = client.player ?: return
        val range = minOf(packet.distance,com.turretdefense.config.Config.particleDistance.get()).toDouble()
        if (packet.effects.isEmpty()) return
        for (offset in packet.effects.indices) {
            val effect = packet.effects[(offset+cursor)%packet.effects.size]
            val distance = player.distanceToSqr(effect.start)
            if (distance > range*range) continue
            val count = when { distance < 256 -> 12; distance < 1024 -> 8; else -> 4 }
            val particle = when (effect.type) { 2 -> ParticleTypes.SMOKE; 3 -> ParticleTypes.END_ROD; 4 -> ParticleTypes.ELECTRIC_SPARK; 5 -> ParticleTypes.EXPLOSION; 6 -> ParticleTypes.ENCHANTED_HIT; else -> ParticleTypes.CRIT }
            val actual = if (effect.type >= 5) 1 else count
            for (index in 0 until actual) {
                if (particles >= 128) break
                val fraction = if (actual == 1) 0.0 else index.toDouble()/(actual-1)
                var point = effect.start.lerp(effect.end,fraction)
                if (effect.type == 2) point = point.add(0.0,4*fraction*(1-fraction)*4.0,0.0)
                world.addParticle(particle,point.x,point.y,point.z,0.0,0.0,0.0); particles++
            }
        }
        cursor = (cursor+1)%packet.effects.size
    }
}
