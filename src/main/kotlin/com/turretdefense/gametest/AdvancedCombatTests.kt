package com.turretdefense.gametest

import com.turretdefense.block.TierBlock
import com.turretdefense.block.TurretHeadBlock
import com.turretdefense.blockentity.*
import com.turretdefense.combat.*
import com.turretdefense.registry.ModContent
import com.turretdefense.targeting.ThreatSnapshot
import com.turretdefense.turret.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import kotlin.math.abs

// 使用真实生物、属性与时间推进验证战斗机制，样本物种不参与生产实现。
@GameTestHolder("turretdefense_expansion")
@PrefixGameTestTemplate(false)
class AdvancedCombatTests {
    companion object {
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_expansion",timeoutTicks=30)
        fun long_ray_stops_before_unloaded_chunk_without_loading_it(helper: GameTestHelper) {
            helper.runAtTickTime(5) {
                val start = net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(BlockPos(3,6,3)))
                val end = start.add(4096.0,0.0,0.0)
                val chunkZ = net.minecraft.core.BlockPos.containing(start).z shr 4
                val chunkX = net.minecraft.core.BlockPos.containing(start).x shr 4
                val missing = (chunkX..chunkX+256).first { helper.level.chunkSource.getChunkNow(it,chunkZ) == null }
                val hit = LoadedRay.clip(helper.level,net.minecraft.world.level.ClipContext(start,end,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,null))
                helper.assertTrue(helper.level.chunkSource.getChunkNow(missing,chunkZ) == null && hit.location.distanceToSqr(start) < end.distanceToSqr(start),"Long ray must stop at an unloaded boundary without creating or loading that chunk")
                helper.succeed()
            }
        }
        private val BASE = BlockPos(3,1,8)
        private fun setup(helper: GameTestHelper,kind: TurretKind,tier: Int = 1): TurretHeadBlockEntity {
            // 宽模板只记录地板，不依赖测试服务器原有地层恰好为空。
            for (x in 0..15) for (y in 1..7) for (z in 0..15) helper.setBlock(x,y,z,Blocks.AIR)
            for (x in 0..15) for (z in 0..15) helper.setBlock(x,0,z,Blocks.STONE)
            helper.setBlock(BlockPos(1,1,1),ModContent.CORE.get().defaultBlockState().setValue(TierBlock.TIER,5))
            helper.setBlock(BASE,ModContent.BASE.get().defaultBlockState().setValue(TierBlock.TIER,5))
            helper.setBlock(BASE.above(),ModContent.HEADS.getValue(kind).get().defaultBlockState().setValue(TierBlock.TIER,tier).setValue(TurretHeadBlock.FACING,Direction.DOWN))
            return (helper.level.getBlockEntity(helper.absolutePos(BASE.above())) as TurretHeadBlockEntity).also { it.nextFireTick = Long.MAX_VALUE }
        }
        private fun mob(helper: GameTestHelper,x: Double,z: Double = 8.5): Mob {
            val enemy = EntityType.HUSK.create(helper.level)!!
            val origin = helper.absolutePos(BlockPos.ZERO)
            enemy.setPos(origin.x+x,origin.y+1.0,origin.z+z); enemy.isNoAi = true
            enemy.getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 1000.0; enemy.health = 1000f
            enemy.getAttribute(Attributes.ARMOR)!!.baseValue = 0.0
            helper.level.addFreshEntity(enemy); return enemy
        }
        private fun context(helper: GameTestHelper,head: TurretHeadBlockEntity,targets: List<Mob>,target: Mob = targets.first(),stats: TurretStats = head.effectiveStats): FireContext {
            val base = head.boundBase!!; val core = base.core!!
            base.energy.stored = base.energy.maxEnergyStored; core.energy.stored = core.energy.maxEnergyStored
            return FireContext(helper.level,head,base,core,stats,target,ThreatSnapshot(targets,DoubleArray(targets.size),helper.level.gameTime),1,1.0,null)
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_expansion",timeoutTicks=50)
        fun railgun_six_targets_and_temporary_armor_penetration(helper: GameTestHelper) {
            val head = setup(helper,TurretKind.Railgun,5)
            helper.runAtTickTime(5) {
                val targets = (0..6).map { mob(helper,6.5+it) }
                targets.forEach { it.getAttribute(Attributes.ARMOR)!!.baseValue = 20.0 }
                val ctx = context(helper,head,targets)
                val line = ctx.clippedLine()
                com.turretdefense.TurretDefense.LOGGER.debug("Railgun geometry: origin={}, line={}, targets={}",head.center,line,targets.map { "${it.position()}/${it.bbHeight}: valid=${ctx.valid(it)}, line=${ctx.lineHit(it,line.first,line.second)}, box=${it.boundingBox}" })
                helper.assertTrue(Combat.behavior(head.kind).fire(ctx),"Railgun must fire")
                com.turretdefense.TurretDefense.LOGGER.debug("Railgun regression: damage={}, pierce={}, health={}, armor={}",head.effectiveStats.damage,head.effectiveStats.pierceCount,targets.map { it.health },targets.map { it.getAttributeValue(Attributes.ARMOR) })
                helper.assertTrue(targets.take(6).all { abs(it.health-955.0) < 0.01 } && targets.last().health == 1000f,"T5 railgun must ignore armor and hit exactly six enemies")
                helper.assertTrue(targets.all { it.getAttributeValue(Attributes.ARMOR) == 20.0 },"Temporary penetration must restore all armor attributes")
                targets.forEach { it.discard() }; helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_expansion",timeoutTicks=120)
        fun fusion_ramp_pierces_and_resets(helper: GameTestHelper) {
            val head = setup(helper,TurretKind.Fusion)
            lateinit var targets: List<Mob>
            helper.runAtTickTime(4) { targets = listOf(mob(helper,7.5),mob(helper,10.5)) }
            for (shot in 0..44) helper.runAtTickTime(5+shot*2L) {
                helper.assertTrue(Combat.behavior(head.kind).fire(context(helper,head,targets,stats=head.effectiveStats.copy(damage=10.0))),"Continuous beam must keep firing through native invulnerability frames")
            }
            helper.runAtTickTime(95) {
                com.turretdefense.TurretDefense.LOGGER.debug("Fusion regression: targetHealth={}, data={}",targets.map { it.health },head.persistentData)
                helper.assertTrue(targets.all { abs(it.health-915.0) < 0.03 },"Both aligned enemies must receive exact per-second ramp damage")
                val before = targets.last().health
                Combat.behavior(head.kind).fire(context(helper,head,targets,targets.last(),head.effectiveStats.copy(damage=10.0)))
                helper.assertTrue(abs(targets.last().health-(before-1.0)) < 0.01,"Changing primary target must reset fusion ramp")
                targets.forEach { it.discard() }; helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_expansion",timeoutTicks=60)
        fun exact_slow_stun_and_corrosion_expire(helper: GameTestHelper) {
            val head = setup(helper,TurretKind.Cryo)
            lateinit var enemy: Mob
            var speed = 0.0
            helper.runAtTickTime(5) {
                enemy = mob(helper,8.5); speed = enemy.getAttributeValue(Attributes.MOVEMENT_SPEED)
                enemy.getAttribute(Attributes.ARMOR)!!.baseValue = 20.0
                ControlEffects.slowPercent(enemy,20,0.65); ControlEffects.stun(enemy,10); ControlEffects.acidCorrode(enemy,20,5)
                helper.assertTrue(abs(enemy.getAttributeValue(Attributes.MOVEMENT_SPEED)-speed*0.35) < 0.0001 && enemy.getAttributeValue(Attributes.ARMOR) == 10.0,"Cryo and five acid stacks must use exact percentage modifiers")
                helper.assertTrue(enemy.isNoAi,"Stun must disable native AI")
                helper.assertTrue(ModuleEffect.ALL.size == 63 && ModuleEffect.ALL.map { it.typeId }.distinct().size == 37,"All module grades and categories must be registered")
                helper.assertTrue(TurretKind.ALL.size == 15,"All fifteen turret kinds must be present")
            }
            helper.runAtTickTime(32) {
                helper.assertTrue(enemy.isNoAi && enemy.getAttributeValue(Attributes.ARMOR) == 20.0 && abs(enemy.getAttributeValue(Attributes.MOVEMENT_SPEED)-speed) < 0.0001,"Expiry must restore original NoAI, speed and armor")
                enemy.discard(); helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_expansion",timeoutTicks=60)
        fun arbiter_modes_and_phase_ambush(helper: GameTestHelper) {
            val head = setup(helper,TurretKind.Arbiter)
            helper.runAtTickTime(5) {
                val targets = listOf(mob(helper,7.5),mob(helper,9.5,9.5),mob(helper,11.5,7.5))
                com.turretdefense.TurretDefense.LOGGER.debug("Arbiter geometry: origin={}, targets={}",head.center,targets.map { "${it.position()}/${it.bbHeight}: valid=${context(helper,head,targets).valid(it)}" })
                Combat.behavior(head.kind).fire(context(helper,head,targets))
                com.turretdefense.TurretDefense.LOGGER.debug("Arbiter regression: mode={}, stats={}, health={}",head.attackMode,head.effectiveStats,targets.map { it.health })
                helper.assertTrue(targets.count { it.health < 1000 } == 1 && abs(targets.first().health-988) < 0.01,"Precision mode must double damage and hit one enemy")
                head.attackMode = "secondary"; head.refreshStats()
                val before = targets.map { it.health }
                Combat.behavior(head.kind).fire(context(helper,head,targets))
                helper.assertTrue(targets.indices.all { abs(targets[it].health-(before[it]-3.0)) < 0.01 },"Rapid mode must hit three enemies at half base damage")
                val phaseBase = head.boundBase!!
                helper.level.setBlock(head.blockPos,ModContent.HEADS.getValue(TurretKind.Phase).get().defaultBlockState().setValue(TurretHeadBlock.FACING,Direction.DOWN),3)
                val phase = helper.level.getBlockEntity(head.blockPos) as TurretHeadBlockEntity
                phaseBase.attachHead(Direction.UP,phase)
                phase.nextFireTick = Long.MAX_VALUE
                AdvancedAttacks.tick(phase,helper.level.gameTime)
                helper.assertTrue(AdvancedAttacks.phaseHidden(phase,helper.level.gameTime),"Phase turret must enter cloak")
                val health = targets.first().health
                Combat.behavior(phase.kind).fire(context(helper,phase,targets))
                helper.assertTrue(abs(targets.first().health-(health-16.0)) < 0.01 && !AdvancedAttacks.phaseHidden(phase,helper.level.gameTime),"Cloaked first shot must double damage and reveal turret")
                targets.forEach { it.discard() }; helper.succeed()
            }
        }
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_expansion",timeoutTicks=140)
        fun acid_dot_and_graviton_real_landing(helper: GameTestHelper) {
            val head = setup(helper,TurretKind.Acid)
            lateinit var acid: Mob; lateinit var lifted: Mob
            var initialY = 0.0
            helper.runAtTickTime(5) {
                acid = mob(helper,7.5); lifted = mob(helper,11.5); initialY = lifted.y
                val ctx = context(helper,head,listOf(acid,lifted))
                repeat(6) { AdvancedStatusRuntime.acid(ctx,acid,2.0) }
                AdvancedStatusRuntime.lift(ctx,lifted,20)
                helper.assertTrue(acid.getEffect(ControlEffects.ACID_CORROSION.get())?.amplifier == 4,"Corrosion must cap at five stacks")
                helper.assertTrue(abs(lifted.y-initialY-3.0) < 0.01 && lifted.isNoGravity,"Levitation must move through real collision and suspend three blocks above floor")
            }
            helper.runAtTickTime(115) {
                com.turretdefense.TurretDefense.LOGGER.debug("Status landing regression: acidHealth={}, gravity={}, onGround={}, y={}, start={}, health={}, data={}",acid.health,lifted.isNoGravity,lifted.onGround(),lifted.y,initialY,lifted.health,lifted.persistentData)
                helper.assertTrue(abs(acid.health-990.0) < 0.02,"Five-second corrosion must deal exactly five DPS slices")
                val below = BlockPos.containing(lifted.position().add(0.0,-0.01,0.0))
                helper.assertTrue(!lifted.isNoGravity && !helper.level.getBlockState(below).getCollisionShape(helper.level,below).isEmpty && abs(lifted.y-initialY) < 0.02 && abs(lifted.health-995.5) < 0.02,"Landing must restore gravity, reach real floor collision and apply one 1.5x base damage hit")
                acid.discard(); lifted.discard(); helper.succeed()
            }
        }
    }
}
