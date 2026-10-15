package com.turretdefense.core

import com.turretdefense.turret.CoreStats
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistries

// 核心模块与底座插件分别注册、过滤、存档，同类默认只装一个。
data class CoreModule(val id: String,val category: String,val grade: Int = 1) {
    fun modify(stats: CoreStats): CoreStats = when (category) {
        "capacity" -> stats.copy(energy=(stats.energy.toLong()+CoreConfig.capacity.get()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        "generation" -> stats.copy(generation=maxOf(stats.generation,if (grade == 1) CoreConfig.generation1.get() else CoreConfig.generation2.get()))
        "range" -> stats.copy(range=maxOf(stats.range,(if (grade == 1) CoreConfig.range1.get() else CoreConfig.range2.get()).toDouble()))
        "shield" -> stats.copy(shield=stats.shield+grade*CoreConfig.shieldBonus.get(),projection=stats.projection+grade*CoreConfig.projectionBonus.get(),deflectionRange=minOf(128.0,stats.deflectionRange+grade*CoreConfig.deflectionRangeBonus.get()))
        "repair" -> stats.copy(repair=stats.repair*(1.0+grade*CoreConfig.repairBonus.get()))
        "judgment" -> stats.copy(judgmentDamage=stats.judgmentDamage*(1.0+CoreConfig.judgmentBonus.get()),judgmentCooldown=(stats.judgmentCooldown*CoreConfig.judgmentCooldown.get()).toInt().coerceAtLeast(1))
        else -> stats
    }
    companion object {
        val ALL = listOf(CoreModule("core_module_capacity","capacity"),CoreModule("core_module_generation_1","generation"),CoreModule("core_module_generation_2","generation",2),CoreModule("core_module_range_1","range"),CoreModule("core_module_range_2","range",2),CoreModule("core_module_wireless","wireless"),CoreModule("core_module_judgment","judgment")) +
            listOf("antiair","shield","repair").flatMap { category -> (1..3).map { CoreModule("core_module_${category}_$it",category,it) } }
        fun from(stack: ItemStack): CoreModule? {
            if (stack.isEmpty) return null
            val id = ForgeRegistries.ITEMS.getKey(stack.item) ?: return null
            if (id.namespace != "turretdefense") return null
            return ALL.firstOrNull { it.id == id.path }
        }
    }
}
