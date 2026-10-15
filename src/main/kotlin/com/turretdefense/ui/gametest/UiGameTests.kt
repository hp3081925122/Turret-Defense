package com.turretdefense.ui.gametest

import com.mojang.authlib.GameProfile
import com.sighs.apricityui.screen.ApricityContainerMenu
import com.sighs.apricityui.container.filter.FilterUtil
import com.turretdefense.block.TierBlock
import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.blockentity.TurretBaseBlockEntity
import com.turretdefense.registry.ModContent
import com.turretdefense.registry.ModTags
import com.turretdefense.ui.UiNetwork
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

// 仅验证真实服务端菜单；选择器映射由测试按当前 HTML 显式提供，不冒充客户端 DOM 验收。
@GameTestHolder("turretdefense")
@PrefixGameTestTemplate(false)
class UiGameTests {
    companion object {
        private val POSITION = BlockPos(4,2,4)

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun core_menu_all_slots_and_shift_transfer(helper: GameTestHelper) {
            helper.setBlock(POSITION,ModContent.CORE.get().defaultBlockState().setValue(TierBlock.TIER,5))
            helper.runAtTickTime(5) {
                val position = helper.absolutePos(POSITION)
                val core = helper.level.getBlockEntity(position) as BaseCoreBlockEntity
                val player = FakePlayerFactory.get(helper.level,GameProfile(UUID.randomUUID(),"core_menu_test"))
                player.moveTo(position.x+0.5,position.y+0.5,position.z+0.5,0f,0f)
                UiNetwork.open(player,position)
                try {
                    val menu = player.containerMenu as ApricityContainerMenu
                    val filters = (1..18).associateWith { FilterUtil.tag(ModTags.AMMO) }.toMutableMap()
                    filters[0] = FilterUtil.custom { ForgeHooks.getBurnTime(it,net.minecraft.world.item.crafting.RecipeType.SMELTING) > 0 }
                    for (slot in 46..50) filters[slot] = FilterUtil.tag("turretdefense:core_module")
                    menu.installSlotFilters(mapOf("block_entity" to filters))
                    helper.assertTrue(menu.slots.size == 87,"Core menu must expose 51 machine and 36 player slots")
                    for (index in 0 until 51) {
                        val slot = menu.slots[menu.resolveGlobalSlotIndex("block_entity",index)!!]
                        val item = when (index) { 0 -> Items.COAL; in 1..18 -> ModContent.item("ammo_iron_slug"); in 46..50 -> ModContent.item("core_module_capacity"); else -> Items.DIAMOND }
                        val stack = ItemStack(item)
                        helper.assertTrue(slot.mayPlace(stack),"Core slot $index must accept its intended item")
                        if (index <= 18) helper.assertTrue(!slot.mayPlace(ItemStack(Items.DIRT)),"Fuel and ammo slots must reject wrong items")
                        slot.set(stack); helper.assertTrue(core.inventory.getStackInSlot(index).count == 1,"Slot $index must update the real item capability")
                        helper.assertTrue(slot.remove(1).count == 1 && core.inventory.getStackInSlot(index).isEmpty,"Slot $index must support extraction")
                    }
                    // 通过原版菜单点击入口验证光标携带栈，避免只调用底层槽位而漏测普通取放。
                    val playerSlot = menu.resolveGlobalSlotIndex("player",0)!!
                    val ammoSlot = menu.resolveGlobalSlotIndex("block_entity",1)!!
                    player.inventory.items[0] = ItemStack(ModContent.item("ammo_iron_slug"),8)
                    menu.clicked(playerSlot,0,ClickType.PICKUP,player)
                    helper.assertTrue(menu.carried.count == 8 && player.inventory.items[0].isEmpty,"Left-click must pick the player stack onto the cursor")
                    menu.clicked(ammoSlot,1,ClickType.PICKUP,player)
                    helper.assertTrue(menu.carried.count == 7 && core.inventory.getStackInSlot(1).count == 1,"Right-click must deposit one round into the core")
                    menu.clicked(ammoSlot,0,ClickType.PICKUP,player)
                    helper.assertTrue(menu.carried.isEmpty && core.inventory.getStackInSlot(1).count == 8,"Left-click must deposit the cursor remainder")
                    menu.clicked(ammoSlot,0,ClickType.PICKUP,player)
                    menu.clicked(playerSlot,0,ClickType.PICKUP,player)
                    helper.assertTrue(menu.carried.isEmpty && core.inventory.getStackInSlot(1).isEmpty && player.inventory.items[0].count == 8,"Pickup and return must conserve ammunition")
                    player.inventory.items[0] = ItemStack(ModContent.item("ammo_iron_slug"),64)
                    menu.quickMoveStack(player,menu.resolveGlobalSlotIndex("player",0)!!)
                    helper.assertTrue(core.inventory.getStackInSlot(1).count == 64 && player.inventory.items[0].isEmpty,"Shift-click must route matching ammunition into the core")
                    menu.quickMoveStack(player,menu.resolveGlobalSlotIndex("block_entity",1)!!)
                    helper.assertTrue(core.inventory.getStackInSlot(1).isEmpty && player.inventory.items.sumOf { if (it.`is`(ModContent.item("ammo_iron_slug"))) it.count else 0 } == 64,"Shift-click must return core ammunition to player inventory")
                    helper.succeed()
                } finally { player.closeContainer() }
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun base_menu_all_slots_and_locked_modules(helper: GameTestHelper) {
            helper.setBlock(POSITION,ModContent.BASE.get().defaultBlockState().setValue(TierBlock.TIER,5))
            helper.runAtTickTime(5) {
                val position = helper.absolutePos(POSITION)
                val base = helper.level.getBlockEntity(position) as TurretBaseBlockEntity
                val player = FakePlayerFactory.get(helper.level,GameProfile(UUID.randomUUID(),"base_menu_test"))
                player.moveTo(position.x+0.5,position.y+0.5,position.z+0.5,0f,0f)
                UiNetwork.open(player,position)
                try {
                    val menu = player.containerMenu as ApricityContainerMenu
                    menu.installSlotFilters(mapOf("block_entity" to (0..4).associateWith { FilterUtil.tag("turretdefense:module") }))
                    helper.assertTrue(menu.slots.size == 68,"Base menu must expose 32 machine and 36 player slots")
                    for (index in 0 until 32) {
                        val slot = menu.slots[menu.resolveGlobalSlotIndex("block_entity",index)!!]
                        val stack = ItemStack(if (index < 5) ModContent.item("module_damage_amp_1") else Items.DIAMOND)
                        helper.assertTrue(slot.mayPlace(stack),"Base slot $index must accept its intended item")
                        if (index < 5) helper.assertTrue(!slot.mayPlace(ItemStack(Items.DIAMOND)),"Module slot must reject other items")
                        slot.set(stack); helper.assertTrue(base.inventory.getStackInSlot(index).count == 1,"Base slot must update the real capability")
                        slot.remove(1); helper.assertTrue(base.inventory.getStackInSlot(index).isEmpty,"All base slots must support extraction")
                    }
                    // 插件过滤拒绝放入时光标保留原物，缓存则接受原版普通点击取放。
                    val playerSlot = menu.resolveGlobalSlotIndex("player",0)!!
                    val moduleSlot = menu.resolveGlobalSlotIndex("block_entity",0)!!
                    val cacheSlot = menu.resolveGlobalSlotIndex("block_entity",5)!!
                    player.inventory.items[0] = ItemStack(Items.DIAMOND,8)
                    menu.clicked(playerSlot,0,ClickType.PICKUP,player)
                    menu.clicked(moduleSlot,0,ClickType.PICKUP,player)
                    helper.assertTrue(menu.carried.count == 8 && base.inventory.getStackInSlot(0).isEmpty,"Rejected plugin input must preserve the cursor stack")
                    menu.clicked(cacheSlot,1,ClickType.PICKUP,player)
                    helper.assertTrue(menu.carried.count == 7 && base.inventory.getStackInSlot(5).count == 1,"Right-click must put one item into the cache")
                    menu.clicked(cacheSlot,0,ClickType.PICKUP,player)
                    menu.clicked(cacheSlot,0,ClickType.PICKUP,player)
                    menu.clicked(playerSlot,0,ClickType.PICKUP,player)
                    helper.assertTrue(menu.carried.isEmpty && base.inventory.getStackInSlot(5).isEmpty && player.inventory.items[0].count == 8,"Cache pickup and return must conserve items")
                    player.inventory.items[0] = ItemStack(Items.DIAMOND,64)
                    menu.quickMoveStack(player,menu.resolveGlobalSlotIndex("player",0)!!)
                    helper.assertTrue(base.inventory.getStackInSlot(5).count == 64,"Shift-click must skip plugin slots and use loot cache")
                    helper.level.setBlock(position,base.blockState.setValue(TierBlock.TIER,1),3)
                    for (index in 1..4) helper.assertTrue(!menu.slots[menu.resolveGlobalSlotIndex("block_entity",index)!!].mayPlace(ItemStack(ModContent.item("module_damage_amp_1"))),"Locked module slots must reject new input")
                    helper.succeed()
                } finally { player.closeContainer() }
            }
        }
    }
}
