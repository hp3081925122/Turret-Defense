package com.turretdefense.compat

import com.turretdefense.blockentity.BaseCoreBlockEntity
import net.minecraft.world.item.ItemStack

// 通用接口不暴露任何AE2类型，没有可选模组时不会触达其兼容类。
interface CoreNetwork {
    fun insert(stack: ItemStack,amount: Int): Int
    fun extract(template: ItemStack,amount: Int): ItemStack
    fun energy(maxFe: Int): Int
}
interface CoreNetworkFactory {
    fun find(core: BaseCoreBlockEntity): List<CoreNetwork>
}
