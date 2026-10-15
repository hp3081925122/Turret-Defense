package com.turretdefense.ui.gametest

import com.mojang.authlib.GameProfile
import com.turretdefense.block.TurretHeadBlock
import com.turretdefense.blockentity.TurretBaseBlockEntity
import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.registry.ModContent
import com.turretdefense.turret.TurretKind
import com.turretdefense.ui.UiNetwork
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

// 真菜单导航与服务端动作校验不替代客户端鼠标、布局和缩放验收。
@GameTestHolder("turretdefense_expansion")
@PrefixGameTestTemplate(false)
class ExpansionUiTests {
    companion object {
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_expansion",timeoutTicks=60)
        fun help_details_target_lists_modes_and_permission_gate(helper: GameTestHelper) {
            val basePos = BlockPos(4,1,4)
            helper.setBlock(basePos,ModContent.BASE.get().defaultBlockState())
            helper.setBlock(basePos.above(),ModContent.HEADS.getValue(TurretKind.Arbiter).get().defaultBlockState().setValue(TurretHeadBlock.FACING,Direction.DOWN))
            val owner = FakePlayerFactory.get(helper.level,GameProfile(UUID.randomUUID(),"expansion_ui_owner"))
            val position = helper.absolutePos(basePos)
            val base = helper.level.getBlockEntity(position) as TurretBaseBlockEntity
            lateinit var head: TurretHeadBlockEntity
            var machineMenu: net.minecraft.world.inventory.AbstractContainerMenu? = null
            helper.runAtTickTime(5) {
                owner.moveTo(position.x+0.5,position.y+0.5,position.z+0.5,0f,0f); base.assignOwner(owner.uuid)
                head = helper.level.getBlockEntity(position.above()) as TurretHeadBlockEntity
                UiNetwork.open(owner,position); machineMenu = owner.containerMenu
                UiNetwork.action(owner,UiNetwork.ActionPacket(position,"open_help",-1,""))
                helper.assertTrue(owner.containerMenu !== machineMenu && base.inventory.slots == 32,"Help must open a separate menu without altering inventory")
            }
            helper.runAtTickTime(8) { UiNetwork.action(owner,UiNetwork.ActionPacket(position,"back",-1,"")); helper.assertTrue(owner.containerMenu.slots.size == 68,"Back must restore real machine and player slots") }
            helper.runAtTickTime(11) { UiNetwork.action(owner,UiNetwork.ActionPacket(position,"open_details",1,"")) }
            helper.runAtTickTime(14) { UiNetwork.action(owner,UiNetwork.ActionPacket(position,"switch_mode",1,"")); helper.assertTrue(head.attackMode == "secondary","Details mode action must change real arbiter mode") }
            helper.runAtTickTime(17) { UiNetwork.action(owner,UiNetwork.ActionPacket(position,"target_policy",1,"allowlist")); helper.assertTrue(head.targetPolicy == "allowlist","Owner must be able to change target policy without a priority plugin") }
            helper.runAtTickTime(20) {
                UiNetwork.action(owner,UiNetwork.ActionPacket(position,"target_lists",1,"{\"allowed\":\"minecraft:slime,minecraft:cow\",\"denied\":\"minecraft:enderman\"}"))
                helper.assertTrue(head.targetAllowList.size == 2 && head.targetDenyList == setOf(ResourceLocation.fromNamespaceAndPath("minecraft","enderman")),"Validated registry lists must reach the head")
            }
            helper.runAtTickTime(23) {
                UiNetwork.action(owner,UiNetwork.ActionPacket(position,"target_lists",1,"{\"allowed\":\"minecraft:missing_entity\",\"denied\":\"\"}"))
                helper.assertTrue(head.targetAllowList.size == 2 && head.targetDenyList.size == 1,"Invalid registry IDs must not erase prior lists")
            }
            helper.runAtTickTime(26) {
                try {
                    base.assignOwner(UUID.randomUUID())
                    UiNetwork.action(owner,UiNetwork.ActionPacket(position,"target_policy",1,"all_mobs"))
                    helper.assertTrue(head.targetPolicy == "allowlist","An existing viewer must lose write access immediately after ownership changes")
                    helper.succeed()
                } finally { owner.closeContainer() }
            }
        }
    }
}
