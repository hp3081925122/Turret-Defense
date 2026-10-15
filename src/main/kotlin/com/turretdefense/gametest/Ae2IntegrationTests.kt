package com.turretdefense.gametest

import appeng.api.config.Actionable
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.AEItemKey
import appeng.blockentity.storage.DriveBlockEntity
import appeng.blockentity.networking.EnergyCellBlockEntity
import appeng.core.definitions.AEBlocks
import appeng.core.definitions.AEItems
import com.mojang.authlib.GameProfile
import com.turretdefense.block.TierBlock
import com.turretdefense.block.TurretHeadBlock
import com.turretdefense.blockentity.*
import com.turretdefense.core.CoreAutomation
import com.turretdefense.registry.ModContent
import com.turretdefense.turret.TurretKind
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.fml.ModList
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

// 仅在withAe2专服测试命名空间执行，真实驱动器、电池、存储元件与网络完成端到端传输。
@GameTestHolder("turretdefense_ae2")
@PrefixGameTestTemplate(false)
class Ae2IntegrationTests {
    companion object {
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_ae2",timeoutTicks=360)
        fun real_me_network_ammo_energy_loot_and_owner_gate(helper: GameTestHelper) {
            helper.assertTrue(ModList.get().isLoaded("ae2"),"AE2 integration suite requires -PwithAe2")
            val corePos = BlockPos(3,1,5); val basePos = BlockPos(1,1,5)
            helper.setBlock(corePos,ModContent.CORE.get().defaultBlockState().setValue(TierBlock.TIER,5))
            helper.setBlock(basePos,ModContent.BASE.get().defaultBlockState().setValue(TierBlock.TIER,5))
            helper.setBlock(basePos.above(),ModContent.HEADS.getValue(TurretKind.Pulse).get().defaultBlockState().setValue(TierBlock.TIER,4).setValue(TurretHeadBlock.FACING,Direction.DOWN))
            helper.setBlock(BlockPos(5,1,5),AEBlocks.DRIVE.block().defaultBlockState())
            helper.setBlock(BlockPos(5,1,6),AEBlocks.ENERGY_CELL.block().defaultBlockState())
            val core = helper.level.getBlockEntity(helper.absolutePos(corePos)) as BaseCoreBlockEntity
            val drive = helper.level.getBlockEntity(helper.absolutePos(BlockPos(5,1,5))) as DriveBlockEntity
            val cell = helper.level.getBlockEntity(helper.absolutePos(BlockPos(5,1,6))) as EnergyCellBlockEntity
            val owner = FakePlayerFactory.get(helper.level,GameProfile(UUID.randomUUID(),"ae_network_owner"))
            core.assignOwner(owner.uuid); drive.mainNode.setOwningPlayer(owner); cell.mainNode.setOwningPlayer(owner)
            helper.assertTrue(drive.internalInventory.addItems(AEItems.ITEM_CELL_64K.stack()).isEmpty,"Real item cell must install in drive")
            cell.injectAEPower(4000.0,Actionable.MODULATE)
            (helper.level.getBlockEntity(helper.absolutePos(basePos.above())) as TurretHeadBlockEntity).nextFireTick = Long.MAX_VALUE
            helper.startSequence().thenWaitUntil {
                helper.assertTrue(drive.mainNode.isActive,"Waiting for real ME grid power and channel activation")
            }.thenExecute {
                val inventory = drive.mainNode.node!!.grid.storageService.inventory
                val source = IActionSource.ofPlayer(owner,null)
                helper.assertTrue(inventory.insert(AEItemKey.of(ModContent.item("ammo_iron_slug")),128,Actionable.MODULATE,source) == 128L,"Real drive must accept seed ammunition")
            }.thenWaitUntil {
                helper.assertTrue(helper.level.gameTime%100 == 0L,"Waiting for configured automation interval")
            }.thenExecute {
                core.energy.stored = 0
                core.inventory.setStackInSlot(19,ItemStack(Items.DIAMOND,11))
                CoreAutomation.tick(core)
                val inventory = drive.mainNode.node!!.grid.storageService.inventory
                val source = IActionSource.ofPlayer(owner,null)
                val supplied = (1..18).sumOf { core.inventory.getStackInSlot(it).count }
                helper.assertTrue(supplied > 0 && inventory.extract(AEItemKey.of(ModContent.item("ammo_iron_slug")),128,Actionable.SIMULATE,source)+supplied == 128L,"ME ammunition must transfer without duplication or loss")
                helper.assertTrue(core.energy.stored > 0 && cell.aeCurrentPower < 4000.0,"ME finite energy must actually supply FE")
                helper.assertTrue(core.inventory.getStackInSlot(19).isEmpty && inventory.extract(AEItemKey.of(Items.DIAMOND),64,Actionable.SIMULATE,source) == 11L,"Loot must enter real ME storage")
                core.assignOwner(UUID.randomUUID()); core.energy.stored = 0
                core.inventory.setStackInSlot(19,ItemStack(Items.EMERALD,3))
                CoreAutomation.tick(core)
                helper.assertTrue(core.energy.stored == 0 && core.inventory.getStackInSlot(19).count == 3,"Untrusted ME node owner must block energy and item access")
            }.thenSucceed()
        }
    }
}
