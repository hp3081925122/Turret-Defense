package com.turretdefense.gametest

import com.mojang.authlib.GameProfile
import com.turretdefense.block.TierBlock
import com.turretdefense.block.TurretHeadBlock
import com.turretdefense.blockentity.BaseCoreBlockEntity
import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.registry.ModContent
import com.turretdefense.targeting.TargetingRules
import com.turretdefense.turret.TurretKind
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.monster.MagmaCube
import net.minecraft.world.entity.monster.Slime
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

// 真实扫描与AI专项覆盖Enemy但非Monster的生物，以及中立仇恨和名单持久化。
@GameTestHolder("turretdefense")
@PrefixGameTestTemplate(false)
class TargetingRuleTests {
    companion object {
        private val CORE = BlockPos(2,1,2)
        private val BASE = BlockPos(6,1,6)
        private fun setup(helper: GameTestHelper): Pair<BaseCoreBlockEntity,TurretHeadBlockEntity> {
            for (x in 0..15) for (y in 1..7) for (z in 0..15) helper.setBlock(x,y,z,Blocks.AIR)
            for (x in 0..15) for (z in 0..15) helper.setBlock(x,0,z,Blocks.STONE)
            helper.setBlock(CORE,ModContent.CORE.get().defaultBlockState())
            helper.setBlock(BASE,ModContent.BASE.get().defaultBlockState())
            helper.setBlock(BASE.above(),ModContent.HEADS.getValue(TurretKind.Pulse).get().defaultBlockState().setValue(TurretHeadBlock.FACING,Direction.DOWN))
            val core = helper.level.getBlockEntity(helper.absolutePos(CORE)) as BaseCoreBlockEntity
            val head = helper.level.getBlockEntity(helper.absolutePos(BASE.above())) as TurretHeadBlockEntity
            head.nextFireTick = helper.level.gameTime+1000
            return core to head
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=50)
        fun slime_magma_scanning_and_allow_deny_lists(helper: GameTestHelper) {
            val (core,head) = setup(helper)
            helper.runAtTickTime(5) {
                val slime = Slime(EntityType.SLIME,helper.level); slime.setSize(2,true); slime.isNoAi = true
                val magma = MagmaCube(EntityType.MAGMA_CUBE,helper.level); magma.setSize(2,true); magma.isNoAi = true
                val point = helper.absolutePos(BlockPos(9,1,6)); slime.setPos(point.x+0.5,point.y.toDouble(),point.z+0.5); magma.setPos(point.x+2.5,point.y.toDouble(),point.z+0.5)
                helper.level.addFreshEntity(slime); helper.level.addFreshEntity(magma)
                core.energy.stored = core.energy.maxEnergyStored; core.requestTargetRefresh(); core.serverTick()
                helper.assertTrue(core.snapshot.members.contains(slime) && core.snapshot.members.contains(magma),"Slime and magma cube must enter hostile snapshots even though they are not Monster")
                val slimeId = ResourceLocation.fromNamespaceAndPath("minecraft","slime")
                head.configureTargeting("allowlist",setOf(slimeId)); core.serverTick()
                helper.assertTrue(core.snapshot.members.contains(slime) && !core.snapshot.members.contains(magma),"Allowlist must admit only selected species")
                head.configureTargeting("allowlist",setOf(slimeId),setOf(slimeId)); core.serverTick()
                helper.assertTrue(!core.snapshot.members.contains(slime),"Denylist must override allowlist")
                slime.discard(); magma.discard(); helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=50)
        fun calm_neutral_retaliation_and_target_settings_save(helper: GameTestHelper) {
            val (core,head) = setup(helper)
            helper.runAtTickTime(5) {
                val neutral = EnderMan(EntityType.ENDERMAN,helper.level); neutral.isNoAi = true
                val owner = FakePlayerFactory.get(helper.level,GameProfile(UUID.randomUUID(),"target_owner")); core.assignOwner(owner.uuid)
                helper.assertTrue(!TargetingRules.matches(head,neutral),"Calm neutral species must not be treated as currently hostile")
                neutral.target = owner
                helper.assertTrue(TargetingRules.matches(head,neutral),"Neutral attacking core owner must become a valid hostile target")
                neutral.target = null; head.configureTargeting("retaliate")
                helper.assertTrue(!TargetingRules.matches(head,neutral),"Retaliation-only must not initiate attack")
                head.takeDamage(1.0,neutral)
                helper.assertTrue(TargetingRules.matches(head,neutral),"Actual attacker must enter retaliation memory")
                val id = ResourceLocation.fromNamespaceAndPath("minecraft","enderman")
                head.configureTargeting("allowlist",setOf(id),setOf(ResourceLocation.fromNamespaceAndPath("minecraft","slime")))
                val tag = head.saveWithoutMetadata()
                head.configureTargeting("all_mobs",emptySet(),emptySet()); head.load(tag)
                helper.assertTrue(head.targetPolicy == "allowlist" && id in head.targetAllowList && head.targetDenyList.size == 1 && head.retaliationTarget == neutral.uuid,"Policy, allow/deny IDs and retaliation must survive NBT")
                helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=220)
        fun mob_uses_generic_movement_to_attack_turret(helper: GameTestHelper) {
            genericMovement(helper,EntityType.SLIME)
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=220)
        fun another_enemy_uses_same_generic_movement(helper: GameTestHelper) {
            genericMovement(helper,EntityType.MAGMA_CUBE)
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=220)
        fun pathfinding_enemy_uses_same_generic_movement(helper: GameTestHelper) {
            genericMovement(helper,EntityType.HUSK)
        }
        // 物种只用于样本构造，攻击、导航停滞与补偿均走同一生产实现。
        private fun genericMovement(helper: GameTestHelper,type: EntityType<out net.minecraft.world.entity.Mob>) {
            val (core,head) = setup(helper)
            var enemy: net.minecraft.world.entity.Mob? = null
            helper.runAtTickTime(5) {
                core.energy.stored = 0; head.shieldLayers = 0
                val mob = type.create(helper.level)!!
                if (mob is Slime) mob.setSize(2,true)
                mob.getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 1024.0; mob.health = 1024f
                mob.getAttribute(Attributes.ATTACK_DAMAGE)!!.baseValue = 1.0
                val start = helper.absolutePos(BlockPos(11,1,6)); mob.setPos(start.x+0.5,start.y.toDouble(),start.z+0.5)
                helper.level.addFreshEntity(mob); enemy = mob
            }
            helper.runAtTickTime(170) {
                try {
                    com.turretdefense.TurretDefense.LOGGER.debug("Generic movement regression: entity={}, position={}, head={}, health={}, pathDone={}",type,enemy?.position(),head.blockPos,head.health,enemy?.navigation?.isDone)
                    helper.assertTrue(head.health < head.effectiveStats.health,"Enemy outside Monster hierarchy must reach turret through generic Mob AI without species adapters")
                    helper.succeed()
                } finally { enemy?.discard() }
            }
        }
    }
}
