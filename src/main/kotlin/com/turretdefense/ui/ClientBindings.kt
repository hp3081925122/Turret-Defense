package com.turretdefense.ui

import com.google.gson.JsonParser
import com.sighs.apricityui.registry.annotation.KJSBindings
import com.sighs.apricityui.init.Document
import com.sighs.apricityui.util.kjs.ApricityUIClientUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import org.joml.Vector3f
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.client.event.ScreenEvent
import com.sighs.apricityui.screen.ApricityContainerScreen
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

// 此文件仅从客户端分支触达，服务器桥接类的方法签名不包含任何客户端类型。
@KJSBindings(value="TurretDefense",modId="turretdefense",isClient=true)
object ClientBindings {
    private var json = "{}"
    private var stateMenuId = -1
    private var pointerMenuId = -1
    private var pointerSamples = 0
    private var alertKey = ""
    private var alertUntil = 0L
    private var overlay: Document? = null
    private var particleWindow = -1L
    private var particles = 0
    private var cursor = 0
    // 与 TurretKind.ALL 顺序一致；颜色取自 art/turret_models 中各炮塔的发光色。
    private class Style(val color: Int,val scale: Float,val muzzle: ParticleOptions,val trail: ParticleOptions?,val impact: ParticleOptions)
    private val STYLES = listOf(
        Style(0x34D6F0,0.9f,ParticleTypes.CRIT,null,ParticleTypes.CRIT),
        Style(0xB45CF0,0.8f,ParticleTypes.WITCH,ParticleTypes.WITCH,ParticleTypes.ENCHANTED_HIT),
        Style(0xFF9A2E,1.0f,ParticleTypes.FLAME,ParticleTypes.SMOKE,ParticleTypes.FLAME),
        Style(0xFF4040,1.3f,ParticleTypes.SMALL_FLAME,null,ParticleTypes.LAVA),
        Style(0x8FD8FF,0.7f,ParticleTypes.ELECTRIC_SPARK,ParticleTypes.ELECTRIC_SPARK,ParticleTypes.ELECTRIC_SPARK),
        Style(0x8E5CFF,1.0f,ParticleTypes.REVERSE_PORTAL,ParticleTypes.REVERSE_PORTAL,ParticleTypes.REVERSE_PORTAL),
        Style(0x3F7DFF,1.1f,ParticleTypes.FIREWORK,ParticleTypes.FIREWORK,ParticleTypes.FIREWORK),
        Style(0x2FE3B0,0.9f,ParticleTypes.PORTAL,ParticleTypes.REVERSE_PORTAL,ParticleTypes.REVERSE_PORTAL),
        Style(0x6AFF6A,0.6f,ParticleTypes.SMOKE,null,ParticleTypes.CRIT),
        Style(0xFFF0A8,0.9f,ParticleTypes.END_ROD,ParticleTypes.END_ROD,ParticleTypes.ENCHANTED_HIT),
        Style(0xFFB02E,1.4f,ParticleTypes.FLAME,ParticleTypes.FLAME,ParticleTypes.LAVA),
        Style(0x9CFF3A,0.9f,ParticleTypes.ITEM_SLIME,ParticleTypes.ITEM_SLIME,ParticleTypes.ITEM_SLIME),
        Style(0xA8F0FF,0.9f,ParticleTypes.SNOWFLAKE,ParticleTypes.SNOWFLAKE,ParticleTypes.SNOWFLAKE),
        Style(0xFFE23A,1.0f,ParticleTypes.ELECTRIC_SPARK,ParticleTypes.ELECTRIC_SPARK,ParticleTypes.WAX_OFF),
        Style(0xD83CFF,1.0f,ParticleTypes.REVERSE_PORTAL,ParticleTypes.WITCH,ParticleTypes.REVERSE_PORTAL)
    )
    fun initialize() {
        com.sighs.apricityui.script.KubeJS.scanPackage("com.turretdefense.ui")
        MinecraftForge.EVENT_BUS.register(this)
    }
    fun receiveState(value: String) {
        val state = runCatching { JsonParser.parseString(value).asJsonObject }.getOrNull() ?: return
        val menuId = state.get("menuId")?.asInt ?: return
        if (stateMenuId != menuId) com.turretdefense.TurretDefense.LOGGER.info("Turret UI state received [slot-input-pass-through-v1]: menuId={}, currentMenuId={}, type={}",menuId,Minecraft.getInstance().player?.containerMenu?.containerId,state.get("type")?.asString)
        json = value; stateMenuId = menuId
    }
    // 只记录本项目每个菜单的前24次原版点击结果，便于确认输入已到达真实容器。
    @SubscribeEvent fun slotPointer(event: ScreenEvent.MouseButtonPressed.Post) {
        val screen = event.screen as? ApricityContainerScreen ?: return
        val menu = screen.menu
        if (menu.templatePath !in listOf("screens/turret_base.html","screens/base_core.html")) return
        if (pointerMenuId != menu.containerId) { pointerMenuId = menu.containerId; pointerSamples = 0 }
        if (pointerSamples++ >= 24) return
        val index = screen.findSlotIndexAt(event.mouseX,event.mouseY)
        com.turretdefense.TurretDefense.LOGGER.info("Turret UI native click: menuId={}, stateMenuId={}, slot={}, button={}, handled={}, carried={}, x={}, y={}",menu.containerId,stateMenuId,index,event.button,event.wasHandled(),menu.carried.count,event.mouseX,event.mouseY)
    }
    fun receiveAlert(key: String) {
        alertKey = key; alertUntil = (Minecraft.getInstance().level?.gameTime ?: 0)+160
        if (overlay == null) overlay = ApricityUIClientUtil.createDocument("overlays/energy_alert.html")
    }
    @SubscribeEvent fun clientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val world = Minecraft.getInstance().level
        if (world == null || world.gameTime >= alertUntil) { overlay?.remove(); overlay = null; alertKey = ""; if (world == null) json = "{}" }
    }
    @JvmStatic fun getStateJson(): String = if (Minecraft.getInstance().player?.containerMenu?.containerId == stateMenuId) json else "{}"
    @JvmStatic fun text(key: String): String = I18n.get(key)
    @JvmStatic fun getAlert(): String = if (alertKey.isEmpty()) "" else I18n.get(alertKey)
    @JvmStatic fun getHeadInfo(faceIndex: Int): List<String>? {
        val value = JsonParser.parseString(getStateJson()).asJsonObject
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
    @JvmStatic fun showHelp(): Boolean = request("open_help",-1,"")
    @JvmStatic fun showDetails(face: Int): Boolean = request("open_details",face,"")
    @JvmStatic fun backToMachine(): Boolean = request("back",-1,"")
    @JvmStatic fun switchMode(face: Int): Boolean = request("switch_mode",face,"")
    @JvmStatic fun setTargetPolicy(face: Int,value: String): Boolean = request("target_policy",face,value)
    @JvmStatic fun setTargetLists(face: Int,allowed: String,denied: String): Boolean = request("target_lists",face,UiNetwork.gson.toJson(mapOf("allowed" to allowed,"denied" to denied)))
    private fun request(action: String,face: Int,value: String): Boolean {
        if (value.length > 16384) return false
        val state = JsonParser.parseString(getStateJson()).asJsonObject
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
            if (effect.kind > 0) {
                val style = STYLES[effect.kind-1]
                val dust = DustParticleOptions(Vector3f((style.color shr 16 and 255)/255f,(style.color shr 8 and 255)/255f,(style.color and 255)/255f),style.scale)
                val random = world.random
                // 近处加密、远处抽稀；持续光束每次开火都会发包，爆散数量减半。
                val spacing = when { distance < 256 -> 0.35; distance < 1024 -> 0.6; else -> 1.2 }
                var burst = when { distance < 256 -> 4; distance < 1024 -> 2; else -> 1 }
                if (effect.type == 3) burst = (burst+1)/2
                // 落点爆炸：原版爆炸粒子加一圈彩色碎屑。
                if (effect.type == 5) {
                    if (particles < 128) { world.addParticle(ParticleTypes.EXPLOSION,effect.start.x,effect.start.y,effect.start.z,0.0,0.0,0.0); particles++ }
                    for (index in 0 until burst*3) {
                        if (particles >= 128) break
                        val particle = if (index%2 == 0) style.impact else dust
                        world.addParticle(particle,effect.start.x,effect.start.y,effect.start.z,(random.nextDouble()-0.5)*0.5,random.nextDouble()*0.3,(random.nextDouble()-0.5)*0.5); particles++
                    }
                    continue
                }
                // 炮口闪光：抛物线分段不重复播放，type 9 只播放炮口闪光。
                if (effect.type != 2 && effect.type != 8) {
                    for (index in 0 until burst) {
                        if (particles >= 128) break
                        world.addParticle(style.muzzle,effect.start.x,effect.start.y,effect.start.z,(random.nextDouble()-0.5)*0.08,(random.nextDouble()-0.5)*0.08,(random.nextDouble()-0.5)*0.08); particles++
                    }
                    if (particles < 128) { world.addParticle(dust,effect.start.x,effect.start.y,effect.start.z,0.0,0.0,0.0); particles++ }
                }
                if (effect.type == 9) continue
                // 弹道：彩色尘埃连线，每隔几个点穿插炮塔专属粒子。
                val steps = (effect.start.distanceTo(effect.end)/spacing).toInt().coerceIn(1,24)
                for (index in 1..steps) {
                    if (particles >= 128) break
                    val point = effect.start.lerp(effect.end,index.toDouble()/steps)
                    var particle: net.minecraft.core.particles.ParticleOptions = dust
                    if (style.trail != null && index%3 == 0) particle = style.trail
                    world.addParticle(particle,point.x,point.y,point.z,0.0,0.0,0.0); particles++
                }
                if (effect.type == 2 || effect.type == 8) continue
                // 命中爆散。
                for (index in 0 until burst) {
                    if (particles >= 128) break
                    world.addParticle(style.impact,effect.end.x,effect.end.y,effect.end.z,(random.nextDouble()-0.5)*0.2,random.nextDouble()*0.15,(random.nextDouble()-0.5)*0.2); particles++
                }
                continue
            }
            val count = when { distance < 256 -> 12; distance < 1024 -> 8; else -> 4 }
            val particle = when (effect.type) { 2,8 -> ParticleTypes.SMOKE; 3 -> ParticleTypes.END_ROD; 4 -> ParticleTypes.ELECTRIC_SPARK; 5 -> ParticleTypes.EXPLOSION; 6 -> ParticleTypes.ENCHANTED_HIT; 7 -> ParticleTypes.SPORE_BLOSSOM_AIR; else -> ParticleTypes.CRIT }
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
