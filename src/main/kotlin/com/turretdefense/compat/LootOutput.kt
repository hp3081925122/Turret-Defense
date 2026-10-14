package com.turretdefense.compat

import net.minecraft.world.item.ItemStack

// 后续自动化输出的单一边界；首期不链接任何 AE2 类。
fun interface LootOutput {
    fun insert(stack: ItemStack,simulate: Boolean): ItemStack
}
