package com.turretdefense.gametest

import com.turretdefense.TurretDefense
import com.turretdefense.block.TierBlock
import com.turretdefense.block.TurretHeadBlock
import com.turretdefense.blockentity.*
import com.turretdefense.network.TurretNetworkData
import com.turretdefense.registry.ModContent
import com.turretdefense.turret.TurretKind
import com.turretdefense.turret.UpgradeJob
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.server.level.ServerLevel
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

// 只写独立 GameTest 存档，正式 JAR 排除此包；必须明确传入测试阶段才执行。
object PersistenceProbe {
    private val corePos = BlockPos(10000,60,10000)
    private val basePos = corePos.east(4)
    private val headPos = basePos.above()
    private val owner = UUID(1234,5678)
    private val trusted = UUID(4321,8765)
    var verified = false
        private set

    // 写入发生在停止事件中，随后由原版正常停服流程将区块和 SavedData 保存到磁盘。
    @SubscribeEvent fun seedDiskFixture(event: ServerStoppingEvent) {
        if (System.getProperty("turretdefense.persistencePhase") != "seed") return
        val level = event.server.overworld()
        level.setBlock(corePos,ModContent.CORE.get().defaultBlockState().setValue(TierBlock.TIER,5),3)
        level.setBlock(basePos,ModContent.BASE.get().defaultBlockState().setValue(TierBlock.TIER,3),3)
        level.setBlock(headPos,ModContent.HEADS.getValue(TurretKind.Pulse).get().defaultBlockState().setValue(TierBlock.TIER,2).setValue(TurretHeadBlock.FACING,Direction.DOWN),3)
        val core = level.getBlockEntity(corePos) as BaseCoreBlockEntity
        val base = level.getBlockEntity(basePos) as TurretBaseBlockEntity
        val head = level.getBlockEntity(headPos) as TurretHeadBlockEntity
        core.onLoad(); base.onLoad(); head.onLoad()
        core.assignOwner(owner); base.assignOwner(owner)
        core.energy.stored = 42123; core.health = 271.5; core.shield = 11; core.burnTicks = 19; core.pendingXp = 6
        core.inventory.setStackInSlot(0,ItemStack(Items.COAL,13)); core.inventory.setStackInSlot(1,ItemStack(ModContent.item("ammo_iron_slug"),9))
        base.energy.stored = 31415; base.inventory.setStackInSlot(0,ItemStack(ModContent.item("module_armor_plate")))
        base.inventory.setStackInSlot(5,ItemStack(Items.DIAMOND,12))
        head.health = 13.5; head.shieldLayers = 3; head.pendingXp = 6; head.kills = 11; head.configurePriority("highest_health")
        head.upgradeJob = UpgradeJob(ResourceLocation.fromNamespaceAndPath("turretdefense","upgrade/pulse_t3"),17,listOf(ItemStack(Items.IRON_INGOT,8)))
        TurretNetworkData.get(level).trust(corePos.asLong(),trusted,true)
        core.setChanged(); base.setChanged(); head.setChanged()
        TurretDefense.LOGGER.info("Disk persistence fixture written before shutdown: core={}, owner={}",corePos,owner)
    }

    // 第二个 JVM 首次加载远端区块时立即检查，避免后续发电或升级改变待核对的值。
    fun verifyDiskFixture(level: ServerLevel) {
        verified = false
        if (System.getProperty("turretdefense.persistencePhase") != "verify") return
        val core = level.getBlockEntity(corePos) as? BaseCoreBlockEntity ?: error("Missing persisted core")
        val base = level.getBlockEntity(basePos) as? TurretBaseBlockEntity ?: error("Missing persisted base")
        val head = level.getBlockEntity(headPos) as? TurretHeadBlockEntity ?: error("Missing persisted head")
        check(core.tier == 5 && base.tier == 3 && head.tier == 2)
        check(core.owner == owner && base.owner == owner && core.trusted(trusted))
        check(core.energy.stored == 42123 && core.health == 271.5 && core.shield == 11 && core.burnTicks == 19 && core.pendingXp == 6)
        check(core.inventory.getStackInSlot(0).count == 13 && core.inventory.getStackInSlot(1).count == 9)
        check(base.energy.stored == 31415 && base.inventory.getStackInSlot(5).count == 12 && base.modules.isNotEmpty())
        check(head.health == 13.5 && head.shieldLayers == 3 && head.pendingXp == 6 && head.kills == 11 && head.priority == "highest_health")
        check(head.upgradeJob?.paid == 17 && head.upgradeJob?.reserved?.single()?.count == 8)
        verified = true
        TurretDefense.LOGGER.info("Disk persistence verified in a new server process: energy, inventory, health, shields, experience, owner, trust and upgrade state restored")
    }
}

// 单独命名空间确保两次启动只运行持久化测试，不复用带怪物和殉爆的完整功能测试场地。
@GameTestHolder("turretdefense_persistence")
@PrefixGameTestTemplate(false)
class PersistenceGameTests {
    companion object {
        @JvmStatic @GameTest(template="gametest_empty",timeoutTicks=40)
        fun disk_persistence_across_server_runs(helper: GameTestHelper) {
            when (System.getProperty("turretdefense.persistencePhase")) {
                "seed" -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(PersistenceProbe)
                "verify" -> { PersistenceProbe.verifyDiskFixture(helper.level); helper.assertTrue(PersistenceProbe.verified,"Separate server process must restore the disk fixture") }
                else -> error("Persistence test requires seed or verify phase")
            }
            helper.succeed()
        }
    }
}
