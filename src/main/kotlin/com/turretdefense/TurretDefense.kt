package com.turretdefense

import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.common.MinecraftForge
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import com.turretdefense.registry.ModContent
import com.turretdefense.turret.UpgradeRules
import com.turretdefense.config.Config
import com.turretdefense.ui.UiNetwork
import com.turretdefense.event.TurretEvents

@Mod(TurretDefense.MOD_ID)
object TurretDefense {
    const val MOD_ID = "turretdefense"
    val LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID)
    init {
        // 注册项与服务端配置先于方块实体创建。
        ModContent.register(MOD_BUS)
        UpgradeRules.register(MOD_BUS)
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON,Config.SPEC)
        MinecraftForge.EVENT_BUS.register(TurretEvents)
        UiNetwork.register()
    }
}
