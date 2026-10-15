package com.turretdefense.gametest

import com.turretdefense.block.TierBlock
import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.core.CoreAutomation
import com.turretdefense.core.CoreConfig
import com.turretdefense.core.DeflectionShield
import com.turretdefense.registry.ModContent
import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

// 覆盖扩展槽位的旧存档迁移、真实弹射物偏转和实际箱子部分输出。
@GameTestHolder("turretdefense")
@PrefixGameTestTemplate(false)
class ExpansionCoreTests {
    companion object {
        private val POSITION = BlockPos(4,3,4)
        private fun core(helper: GameTestHelper,tier: Int = 5): BaseCoreBlockEntity {
            helper.setBlock(POSITION,ModContent.CORE.get().defaultBlockState().setValue(TierBlock.TIER,tier))
            return helper.level.getBlockEntity(helper.absolutePos(POSITION)) as BaseCoreBlockEntity
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=30,batch="config_inventory")
        fun core_module_legacy_inventory_and_capacity(helper: GameTestHelper) {
            val core = core(helper)
            helper.runAtTickTime(5) {
                core.inventory.setStackInSlot(0,ItemStack(Items.COAL,3))
                core.inventory.setStackInSlot(19,ItemStack(Items.DIAMOND,9))
                val old = core.inventory.serializeNBT(); old.putInt("Size",46)
                core.inventory.deserializeNBT(old)
                helper.assertTrue(core.inventory.slots == 51 && core.inventory.getStackInSlot(0).count == 3 && core.inventory.getStackInSlot(19).count == 9,"Legacy inventory must expand without moving or losing old items")
                val capacity = core.energy.maxEnergyStored
                core.inventory.setStackInSlot(46,ItemStack(ModContent.item("core_module_capacity")))
                helper.assertTrue(core.energy.maxEnergyStored == capacity+CoreConfig.capacity.get(),"Core capacity module must alter actual FE capacity")
                core.energy.stored = core.energy.maxEnergyStored
                core.inventory.setStackInSlot(46,ItemStack.EMPTY)
                helper.assertTrue(core.energy.stored == capacity,"Removing capacity must clamp stored energy")
                for (slot in 19..45) core.inventory.setStackInSlot(slot,ItemStack(Items.DIRT,64))
                val drop = com.turretdefense.config.Config.dropOnFull.get()
                try {
                    com.turretdefense.config.Config.dropOnFull.set(false)
                    helper.assertTrue(core.acceptLoot(ItemStack(Items.DIAMOND)).count == 1 && (46..50).all { core.inventory.getStackInSlot(it).isEmpty },"Full loot must never spill into core module slots")
                } finally { com.turretdefense.config.Config.dropOnFull.set(drop) }
                helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=30)
        fun core_module_level_lock_and_duplicate_category(helper: GameTestHelper) {
            val core = core(helper,1)
            helper.runAtTickTime(5) {
                val module = ItemStack(ModContent.item("core_module_generation_1"))
                helper.assertTrue(core.inventory.isItemValid(46,module) && !core.inventory.isItemValid(47,module),"T1 must expose only one core module slot")
                helper.assertTrue(!core.inventory.isItemValid(46,ItemStack(ModContent.item("module_damage_amp_1"))),"Base modules must not enter core module slots")
                helper.level.setBlock(core.blockPos,core.blockState.setValue(TierBlock.TIER,5),3); core.refreshTier()
                core.inventory.setStackInSlot(46,module)
                helper.assertTrue(core.stats.generation >= CoreConfig.generation1.get(),"Generation module must change core generation")
                helper.assertTrue(!core.inventory.isItemValid(47,ItemStack(ModContent.item("core_module_generation_2"))),"Default same-category duplicate must be refused")
                helper.assertTrue(core.inventory.isItemValid(47,ItemStack(ModContent.item("core_module_repair_1"))),"Different categories must remain compatible")
                helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=30,batch="config_deflection")
        fun core_deflection_preserves_projectile_and_friendship(helper: GameTestHelper) {
            val core = core(helper)
            helper.runAtTickTime(5) {
                core.energy.stored = core.energy.maxEnergyStored
                val arrow = Arrow(EntityType.ARROW,helper.level)
                arrow.setPos(core.center.x+5,core.center.y,core.center.z); arrow.setDeltaMovement(-1.0,0.0,0.0)
                helper.level.addFreshEntity(arrow)
                val id = arrow.id; val interval = CoreConfig.interval.get()
                try {
                    CoreConfig.interval.set(1); DeflectionShield.tick(core)
                    helper.assertTrue(!arrow.isRemoved && arrow.id == id && arrow.deltaMovement.x > 0.9,"Hostile incoming arrow must reverse without replacement")
                    helper.assertTrue(core.energy.stored < core.energy.maxEnergyStored,"Deflection must consume energy")
                    helper.assertTrue(!DeflectionShield.eligible(core,arrow,64.0,helper.level.gameTime),"Outgoing or cooled projectile must not repeat-reflect")
                    val friend = FakePlayerFactory.get(helper.level,GameProfile(UUID.randomUUID(),"shield_friend")); core.assignOwner(friend.uuid)
                    val friendly = Arrow(EntityType.ARROW,helper.level); friendly.owner = friend
                    friendly.setPos(core.center.x+5,core.center.y,core.center.z); friendly.setDeltaMovement(-1.0,0.0,0.0)
                    helper.assertTrue(!DeflectionShield.eligible(core,friendly,64.0,helper.level.gameTime),"Owner projectiles must remain unreflected")
                    core.energy.stored = 0; arrow.persistentData.remove("TurretDeflectionUntil"); arrow.setDeltaMovement(-1.0,0.0,0.0)
                    DeflectionShield.tick(core)
                    helper.assertTrue(arrow.deltaMovement.x < 0.0,"Empty core must not reflect")
                } finally { CoreConfig.interval.set(interval); arrow.discard() }
                helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=30,batch="config_output")
        fun core_container_output_partial_and_bottle_priority(helper: GameTestHelper) {
            val core = core(helper)
            helper.setBlock(POSITION.east(),Blocks.CHEST)
            helper.runAtTickTime(5) {
                val chest = helper.level.getBlockEntity(helper.absolutePos(POSITION.east())) as ChestBlockEntity
                for (slot in 0 until chest.containerSize) chest.setItem(slot,ItemStack(Items.DIRT,64))
                chest.setItem(0,ItemStack(Items.EXPERIENCE_BOTTLE,58))
                core.inventory.setStackInSlot(0,ItemStack(Items.COAL,2)); core.inventory.setStackInSlot(1,ItemStack(ModContent.item("ammo_iron_slug"),4))
                core.inventory.setStackInSlot(19,ItemStack(Items.DIAMOND,8)); core.inventory.setStackInSlot(20,ItemStack(Items.EXPERIENCE_BOTTLE,8))
                core.inventory.setStackInSlot(46,ItemStack(ModContent.item("core_module_capacity")))
                val interval = CoreConfig.outputInterval.get(); val budget = CoreConfig.outputLimit.get()
                try {
                    CoreConfig.outputInterval.set(1); CoreConfig.outputLimit.set(8); CoreAutomation.tick(core)
                    helper.assertTrue(chest.getItem(0).count == 64 && core.inventory.getStackInSlot(20).count == 2,"Only six accepted bottles may be deducted")
                    helper.assertTrue(core.inventory.getStackInSlot(19).count == 8,"Full container must leave ordinary loot in core")
                    helper.assertTrue(core.inventory.getStackInSlot(0).count == 2 && core.inventory.getStackInSlot(1).count == 4 && core.inventory.getStackInSlot(46).count == 1,"Output must skip fuel, ammunition and modules")
                } finally { CoreConfig.outputInterval.set(interval); CoreConfig.outputLimit.set(budget) }
                helper.succeed()
            }
        }
    }
}
