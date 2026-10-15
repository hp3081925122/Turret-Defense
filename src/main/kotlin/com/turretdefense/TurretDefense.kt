package com.turretdefense

import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.common.MinecraftForge
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.forge.registerConfig
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
        com.turretdefense.worldgen.WorldgenContent.register(MOD_BUS)
        UpgradeRules.register(MOD_BUS)
        com.turretdefense.combat.ControlEffects.register(MOD_BUS)
        com.turretdefense.combat.AdvancedStatusRuntime.register(MinecraftForge.EVENT_BUS)
        registerConfig(ModConfig.Type.COMMON,Config.SPEC)
        registerConfig(ModConfig.Type.COMMON,com.turretdefense.turret.ModuleConfig.SPEC,"turretdefense-modules.toml")
        registerConfig(ModConfig.Type.COMMON,com.turretdefense.turret.AdvancedCombatConfig.SPEC,"turretdefense-advanced-combat.toml")
        registerConfig(ModConfig.Type.COMMON,com.turretdefense.core.CoreConfig.SPEC,"turretdefense-core-common.toml")
        registerConfig(ModConfig.Type.COMMON,com.turretdefense.worldgen.WorldgenConfig.SPEC,"turretdefense-worldgen.toml")
        registerConfig(ModConfig.Type.COMMON,com.turretdefense.targeting.TargetingRules.SPEC,"turretdefense-targeting.toml")
        MinecraftForge.EVENT_BUS.register(TurretEvents)
        UiNetwork.register()
    }
}
