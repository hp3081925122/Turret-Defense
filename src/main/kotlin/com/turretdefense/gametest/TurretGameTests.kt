package com.turretdefense.gametest

import com.turretdefense.TurretDefense
import com.turretdefense.block.*
import com.turretdefense.blockentity.*
import com.turretdefense.combat.Combat
import com.turretdefense.command.TurretCommands
import com.turretdefense.config.Config
import com.turretdefense.event.TurretEvents
import com.turretdefense.loot.LootRouting
import com.turretdefense.network.*
import com.turretdefense.registry.*
import com.turretdefense.targeting.ThreatSnapshot
import com.turretdefense.turret.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.projectile.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.entity.ProjectileImpactEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.common.util.FakePlayerFactory

// 开发运行目录内执行；正式 JAR 排除整个测试类包与测试模板。
@GameTestHolder("turretdefense")
@PrefixGameTestTemplate(false)
class TurretGameTests {
    companion object {
        private val CORE = BlockPos(2,2,2)
        private val BASE = BlockPos(6,3,6)
        private fun setup(helper: GameTestHelper,kind: TurretKind = TurretKind.Pulse,coreTier: Int = 5,headTier: Int = 1) {
            for (x in 0 until 16) for (z in 0 until 16) helper.setBlock(x,0,z,Blocks.STONE)
            helper.setBlock(CORE,ModContent.CORE.get().defaultBlockState().setValue(TierBlock.TIER,coreTier))
            helper.setBlock(BASE,ModContent.BASE.get().defaultBlockState().setValue(TierBlock.TIER,5))
            helper.setBlock(BASE.above(),ModContent.HEADS.getValue(kind).get().defaultBlockState().setValue(TierBlock.TIER,headTier).setValue(TurretHeadBlock.FACING,Direction.DOWN))
        }
        private fun core(helper: GameTestHelper) = helper.level.getBlockEntity(helper.absolutePos(CORE)) as BaseCoreBlockEntity
        private fun base(helper: GameTestHelper) = helper.level.getBlockEntity(helper.absolutePos(BASE)) as TurretBaseBlockEntity
        private fun head(helper: GameTestHelper) = helper.level.getBlockEntity(helper.absolutePos(BASE.above())) as TurretHeadBlockEntity

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun six_faces_and_shared_modules(helper: GameTestHelper) {
            setup(helper)
            for (direction in Direction.values()) helper.setBlock(BASE.relative(direction),ModContent.HEADS.getValue(TurretKind.Pulse).get().defaultBlockState().setValue(TurretHeadBlock.FACING,direction.opposite))
            helper.runAtTickTime(5) {
                val base = base(helper); val core = core(helper)
                helper.assertTrue(base.heads.size == 6 && base.core === core,"Six faces must register exactly once")
                base.heads.toMap().forEach { (face,head) -> base.attachHead(face,head) }
                helper.assertTrue(base.heads.size == 6,"Idempotent attach must not duplicate heads")
                base.inventory.insertItem(0,ItemStack(ModContent.item("module_damage_amp_1")),false)
                helper.assertTrue(base.heads.values.all { kotlin.math.abs(it.effectiveStats.damage-10.0) < 0.001 },"Damage module must affect every face")
                val dying = base.heads.getValue(Direction.NORTH)
                dying.shieldLayers = 0; dying.takeDamage(1000.0)
                helper.assertTrue(base.inventory.getStackInSlot(0).count == 1 && base.heads.size == 5,"Ordinary destruction must preserve shared modules")
                helper.assertBlockPresent(ModContent.BASE.get(),BASE)
                helper.level.removeBlock(helper.absolutePos(BASE),false)
                helper.assertTrue(Direction.values().all { helper.level.getBlockEntity(helper.absolutePos(BASE.relative(it))) !is TurretHeadBlockEntity },"No orphan heads may remain")
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun tier_limits_and_saved_state(helper: GameTestHelper) {
            helper.setBlock(BASE,ModContent.BASE.get().defaultBlockState())
            helper.setBlock(BASE.above(),ModContent.HEADS.getValue(TurretKind.Arc).get().defaultBlockState().setValue(TurretHeadBlock.FACING,Direction.DOWN))
            helper.runAtTickTime(5) {
                val base = base(helper); val head = head(helper)
                helper.assertTrue(base.heads.size == 1 && !base.canAttach(Direction.NORTH),"T1 must only allow one face")
                helper.assertTrue(!base.inventory.insertItem(1,ItemStack(ModContent.item("module_armor_plate")),false).isEmpty,"Locked plugin slots must reject insertion")
                head.health = 7.5; head.pendingXp = 6; head.kills = 17; head.priority = "highest_health"; head.nextFireTick = 999
                val saved = head.saveWithoutMetadata()
                val restored = TurretHeadBlockEntity(head.blockPos,head.blockState); restored.load(saved)
                helper.assertTrue(restored.health == 7.5 && restored.pendingXp == 6 && restored.kills == 17 && restored.nextFireTick == 999L && restored.priority == "highest_health","Head state must persist")
                helper.level.setBlock(head.blockPos,head.blockState.setValue(TierBlock.TIER,3),3)
                helper.assertTrue(head.tier == 3 && head.effectiveStats.damage == 10.0,"State tier changes must refresh cached stats")
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun fuel_external_energy_and_distribution(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val core = core(helper); val base = base(helper)
                core.energy.stored = core.stats.energy; core.burnTicks = 100; core.inventory.setStackInSlot(0,ItemStack(Items.COAL,3))
                core.serverTick()
                helper.assertTrue(core.burnTicks == 100 && core.inventory.getStackInSlot(0).count == 3,"Full cache must pause fuel")
                core.energy.stored = 10000; core.burnTicks = 100
                val before = core.burnTicks
                core.energy.receiveEnergy(100,false); core.serverTick()
                helper.assertTrue(core.burnTicks == before,"External FE must take priority over fuel")
                core.energy.stored = core.stats.energy
                base.energy.stored = 0
            }
            helper.runAtTickTime(12) {
                helper.assertTrue(base(helper).energy.stored > 0,"Bound bases must receive FE")
                val core = core(helper)
                val values = intArrayOf(0,8000,32000,80000,160000)
                for ((index,value) in values.withIndex()) { core.energy.stored = value; helper.assertTrue(core.powerState == index,"Energy state threshold mismatch") }
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun ammo_tags_and_atomic_resources(helper: GameTestHelper) {
            setup(helper,TurretKind.Pulse,headTier=4)
            helper.runAtTickTime(5) {
                val core = core(helper); val base = base(helper); val head = head(helper)
                helper.assertTrue(!core.inventory.insertItem(1,ItemStack(Items.DIAMOND),false).isEmpty,"Non-ammo must be rejected")
                core.inventory.setStackInSlot(1,ItemStack(ModContent.item("ammo_pulse_core"),2))
                helper.assertTrue(!core.consumeAmmo(ModTags.KIND_AMMO.getValue(TurretKind.Pulse)[0],true),"T4 cannot consume T5 ammunition")
                core.inventory.setStackInSlot(2,ItemStack(ModContent.item("ammo_iron_slug"),2))
                val zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,10,3,6)
                val snapshot = ThreatSnapshot(listOf(zombie),doubleArrayOf(zombie.distanceToSqr(core.center)),helper.level.gameTime)
                val context = FireContext(helper.level,head,base,core,head.effectiveStats,zombie,snapshot,22,22.0,ModTags.KIND_AMMO.getValue(TurretKind.Pulse)[0])
                base.energy.stored = 21
                helper.assertTrue(!context.consume() && core.inventory.getStackInSlot(2).count == 2,"Insufficient FE cannot consume ammunition")
                base.energy.stored = 100
                helper.assertTrue(context.consume() && base.energy.stored == 78 && core.inventory.getStackInSlot(2).count == 1,"FE and matching ammunition must both be consumed")
                val container = SimpleContainer(3); container.setItem(0,ItemStack(Items.IRON_INGOT,4)); container.setItem(1,ItemStack(Items.REDSTONE,4))
                val costs = listOf(MaterialCost(Ingredient.of(Items.IRON_INGOT),5),MaterialCost(Ingredient.of(Items.REDSTONE),4))
                helper.assertTrue(UpgradeRules.plan(container,costs) == null && container.getItem(0).count == 4,"Material shortage must leave inventory unchanged")
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun upgrade_reserves_and_cumulative_energy(helper: GameTestHelper) {
            setup(helper,coreTier=4)
            helper.runAtTickTime(5) {
                val core = core(helper); core.energy.stored = 0
                val recipe = helper.level.recipeManager.byKey(ResourceLocation.fromNamespaceAndPath("turretdefense","upgrade/core_t5")).orElseThrow() as UpgradeRecipe
                val player = FakePlayerFactory.getMinecraft(helper.level)
                player.inventory.clearContent()
                var slot = 0
                for (cost in recipe.costs) player.inventory.setItem(slot++,cost.ingredient.items.first().copy().also { it.count = cost.count })
                helper.assertTrue(UpgradeRules.upgradeCore(player,core),"Upgrade must reserve all exact materials")
                helper.assertTrue((0 until slot).all { player.inventory.getItem(it).isEmpty },"Reserved materials must leave the inventory")
                core.energy.stored = 80000; UpgradeRules.process(core)
                helper.assertTrue(core.tier == 4 && core.upgradeJob?.paid == 80000,"First energy batch must persist")
                val saved = core.saveWithoutMetadata(); core.load(saved)
                core.energy.stored = 80000; UpgradeRules.process(core)
                helper.assertTrue(core.tier == 4 && core.upgradeJob?.paid == 160000,"Upgrade must survive save/load")
                core.energy.stored = 80000; UpgradeRules.process(core)
                helper.assertTrue(core.tier == 5 && core.upgradeJob == null && core.energy.stored == 40000,"Exactly 200000 FE must complete T5 upgrade")
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=80)
        fun delayed_grenade_and_no_terrain_damage(helper: GameTestHelper) {
            setup(helper,TurretKind.Grenade)
            lateinit var zombie: net.minecraft.world.entity.monster.Zombie
            lateinit var context: FireContext
            helper.runAtTickTime(5) {
                zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,10,3,6)
                zombie.setNoGravity(true); zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,ItemStack(Items.IRON_HELMET))
                val core = core(helper); val base = base(helper); val head = head(helper)
                base.energy.stored = 1000
                val snapshot = ThreatSnapshot(listOf(zombie),doubleArrayOf(zombie.distanceToSqr(core.center)),helper.level.gameTime)
                context = FireContext(helper.level,head,base,core,head.effectiveStats,zombie,snapshot,12,12.0,null)
                helper.assertTrue(BallisticBehavior.fire(context),"Grenade must launch")
            }
            helper.runAtTickTime(10) {
                helper.assertTrue(zombie.health == zombie.maxHealth,"Grenade damage cannot be immediate")
                val far = helper.absolutePos(BlockPos(20,3,20)); zombie.moveTo(far.x.toDouble(),far.y.toDouble(),far.z.toDouble(),0f,0f)
            }
            helper.runAtTickTime(20) {
                helper.assertTrue(zombie.health == zombie.maxHealth,"Moving away from the fixed landing point must evade grenade")
                val close = helper.absolutePos(BlockPos(10,3,6)); zombie.moveTo(close.x.toDouble(),close.y.toDouble(),close.z.toDouble(),0f,0f)
                helper.assertTrue(BallisticBehavior.fire(context),"Second grenade must launch")
            }
            helper.runAtTickTime(35) {
                helper.assertTrue(zombie.health < zombie.maxHealth,"Stationary target must receive delayed AOE")
                helper.assertBlockPresent(Blocks.STONE,BlockPos(10,0,6))
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun loot_and_offline_experience(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val core = core(helper); val base = base(helper); val head = head(helper)
                core.assignOwner(java.util.UUID.randomUUID()); base.assignOwner(core.owner!!); base.bindCore(core)
                val zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,10,3,6)
                zombie.hurt(Combat.source(helper.level,head,core),1000f)
                helper.assertTrue(!zombie.isAlive && head.pendingXp == 5,"Offline owner kills must retain mob experience")
                val second = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,10,3,6)
                second.hurt(Combat.source(helper.level,head,core),1000f)
                helper.assertTrue(head.pendingXp == 3 && (5 until 32).any { base.inventory.getStackInSlot(it).`is`(Items.EXPERIENCE_BOTTLE) },"Ten XP must produce one bottle and three pending XP")
                val center = helper.absolutePos(BASE)
                val items = helper.level.getEntitiesOfClass(ItemEntity::class.java,AABB(center).inflate(12.0))
                helper.assertTrue(items.isEmpty(),"Turret kills cannot create item entities")
                for (slot in 5 until 32) base.inventory.setStackInSlot(slot,ItemStack(Items.DIRT,64))
                head.pendingXp = 14; LootRouting.flush(head)
                helper.assertTrue(head.pendingXp == 14,"Full cache must not erase pending XP")
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun projectile_shields_and_external_explosion(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val core = core(helper); val head = head(helper); val base = base(helper)
                core.energy.stored = core.stats.energy; head.shieldLayers = 2
                val health = head.health
                head.takeDamage(10.0); head.takeDamage(10.0)
                helper.assertTrue(head.health == health && head.shieldLayers == 0,"Each shield layer must fully block one attack")
                val arrow = Arrow(EntityType.ARROW,helper.level); arrow.setDeltaMovement(3.0,0.0,0.0)
                TurretEvents.projectile(ProjectileImpactEvent(arrow,BlockHitResult(head.center,Direction.WEST,head.blockPos,false)))
                helper.assertTrue(head.health < health,"Arrow hit must damage unshielded head")
                val blocks = mutableListOf(core.blockPos,base.blockPos,head.blockPos)
                val explosion = Explosion(helper.level,null,head.center.x,head.center.y+2,head.center.z,1f,false,Explosion.BlockInteraction.KEEP,blocks)
                val before = head.health
                val event = ExplosionEvent.Detonate(helper.level,explosion,mutableListOf())
                TurretEvents.explosion(event)
                helper.assertTrue(event.affectedBlocks.isEmpty() && head.health < before,"External explosion must protect blocks and apply machine damage")
                helper.assertBlockPresent(ModContent.BASE.get(),BASE)
                helper.assertBlockPresent(Blocks.STONE,BlockPos(6,0,6))
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=100)
        fun monsters_attack_and_core_martyr(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val head = head(helper); head.shieldLayers = 0
                helper.setBlock(6,2,7,Blocks.STONE)
                val zombie = helper.spawn(EntityType.ZOMBIE,6,3,7)
                zombie.setNoAi(false)
                zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,ItemStack(Items.IRON_HELMET))
            }
            helper.runAtTickTime(45) {
                val head = head(helper); val core = core(helper); val base = base(helper)
                helper.assertTrue(head.health < head.effectiveStats.health,"Active monster goal must damage a head")
                base.inventory.insertItem(0,ItemStack(ModContent.item("module_armor_plate")),false)
                helper.level.removeBlock(core.blockPos,false)
            }
            helper.runAtTickTime(65) {
                helper.assertTrue(helper.level.getBlockEntity(helper.absolutePos(BASE.above())) !is TurretHeadBlockEntity,"Core destruction must trigger delayed martyr chain")
                helper.assertBlockPresent(ModContent.BASE.get(),BASE)
                helper.assertTrue(base(helper).inventory.getStackInSlot(0).isEmpty,"Martyr must settle shared modules once")
                helper.assertBlockPresent(Blocks.STONE,BlockPos(6,0,6))
                helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun fair_global_fire_limit(helper: GameTestHelper) {
            val heads = (0 until 70).map { index -> TurretHeadBlockEntity(helper.absolutePos(BlockPos(index,2,2)),ModContent.HEADS.getValue(TurretKind.Pulse).get().defaultBlockState()).also { it.setLevel(helper.level) } }
            FireBudget.clear(); FireBudget.beginTick()
            val served = mutableSetOf<TurretHeadBlockEntity>()
            for (head in heads) if (FireBudget.acquire(head)) { served.add(head); FireBudget.recordFire() }
            helper.assertTrue(FireBudget.fires == 64,"Global fire count must have a hard upper limit of 64")
            FireBudget.beginTick()
            for (head in heads) if (FireBudget.acquire(head)) { served.add(head); FireBudget.recordFire() }
            helper.assertTrue(FireBudget.fires <= 64 && served.size == 70,"Deferred heads must receive fair priority next tick")
            FireBudget.clear(); helper.succeed()
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun laser_cooldown_bypass_and_arc_chain(helper: GameTestHelper) {
            setup(helper,TurretKind.Arc,headTier=3)
            helper.runAtTickTime(5) {
                val core = core(helper); val base = base(helper); val head = head(helper)
                core.energy.stored = 0; base.energy.stored = 1000
                val zombies = (10..12).map { x -> helper.spawnWithNoFreeWill(EntityType.ZOMBIE,x,3,6).also { it.setNoGravity(true); it.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,ItemStack(Items.IRON_HELMET)) } }
                val snapshot = ThreatSnapshot(zombies,DoubleArray(3) { zombies[it].distanceToSqr(core.center) },helper.level.gameTime)
                val beam = FireContext(helper.level,head,base,core,TurretStatsTable.LASER[0],zombies[0],snapshot,1,1.0,null)
                repeat(10) { helper.assertTrue(ContinuousBeamBehavior.fire(beam),"Laser must damage every two ticks without native immunity") }
                helper.assertTrue(zombies.all { it.health < 17.0f },"Ten laser settlements must deal approximately four raw damage through the line")
                val before = zombies.map { it.health }
                val arc = FireContext(helper.level,head,base,core,head.effectiveStats,zombies[0],snapshot,18,18.0,null)
                helper.assertTrue(ChainBehavior.fire(arc),"Arc must launch")
                val losses = zombies.indices.map { before[it]-zombies[it].health }
                helper.assertTrue(losses[0] > losses[1] && losses[1] > losses[2] && losses[2] > 0,"Arc must visit three distinct enemies with decreasing damage")
                helper.succeed()
            }
        }

        // 即使旧快照仍保留实体，离开实际扫描半径后也不能继续命中；管理半径独立校验。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun scan_cap_and_management_radius_boundaries(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val core = core(helper); val base = base(helper); val head = head(helper)
                val oldCap = Config.scanRadiusCap.get()
                try {
                    Config.scanRadiusCap.set(16); head.refreshStats()
                    helper.assertTrue(core.scanRadius == 16.0 && head.effectiveStats.range == 16.0,"Actual scan cap must limit effective head range")
                    val zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,10,3,6)
                    val snapshot = ThreatSnapshot(listOf(zombie),doubleArrayOf(zombie.distanceToSqr(core.center)),helper.level.gameTime)
                    val context = FireContext(helper.level,head,base,core,head.effectiveStats,zombie,snapshot,6,6.0,null)
                    helper.assertTrue(context.valid(zombie),"Target inside both radii must be valid")
                    val beyond = core.center.add(17.0,0.0,0.0); zombie.moveTo(beyond.x,beyond.y,beyond.z,0f,0f)
                    helper.assertTrue(zombie.distanceToSqr(head.center) < 16*16 && !context.valid(zombie),"Cached target outside actual core scan cap must be rejected")
                    helper.assertTrue(!core.inRange(core.blockPos.east(129)),"Base beyond management radius cannot receive a binding")
                    head.acceptThreats(snapshot); core.energy.stored = core.stats.energy; base.energy.stored = 1000
                    FireBudget.clear(); FireBudget.beginTick(); head.nextFireTick = helper.level.gameTime; head.serverTick()
                    helper.assertTrue(head.status == "no_target","Sticky target validation must reject scan-cap escape")
                    TurretDefense.LOGGER.debug("Scan cap boundary verified: cap={}, cachedTargetOutsideCap=true",core.scanRadius)
                } finally { Config.scanRadiusCap.set(oldCap); head.refreshStats(); FireBudget.clear() }
                helper.succeed()
            }
        }

        // 斜向射线必须离开自己的碰撞箱；紧贴炮口的实心墙仍应挡住开火且不扣能量。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun diagonal_hitscan_exits_head_but_respects_adjacent_wall(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val core = core(helper); val base = base(helper); val head = head(helper)
                val zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,10,4,10)
                zombie.setNoGravity(true); zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,ItemStack(Items.IRON_HELMET))
                val snapshot = ThreatSnapshot(listOf(zombie),doubleArrayOf(zombie.distanceToSqr(core.center)),helper.level.gameTime)
                val context = FireContext(helper.level,head,base,core,head.effectiveStats,zombie,snapshot,6,6.0,null)
                base.energy.stored = 1000
                helper.assertTrue(HitscanBehavior.fire(context) && zombie.health < zombie.maxHealth,"Diagonal pulse ray must hit target rather than its own full block")
                val position = helper.absolutePos(BlockPos(10,3,6)); zombie.moveTo(position.x+0.5,position.y.toDouble(),position.z+0.5,0f,0f)
                helper.setBlock(7,4,6,Blocks.STONE)
                val energy = base.energy.stored
                helper.assertTrue(!HitscanBehavior.fire(context) && base.energy.stored == energy,"Adjacent solid wall must block the shot without consuming FE")
                TurretDefense.LOGGER.debug("Diagonal hitscan regression verified: ownBlockSkipped=true, adjacentWallBlocked=true")
                helper.succeed()
            }
        }

        // 真实实体触发原版与 Forge 爆炸链，不能用手工构造事件替代。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=100)
        fun real_tnt_and_creeper_damage_only_machine_health(helper: GameTestHelper) {
            setup(helper,headTier=5)
            var before = 0.0
            helper.runAtTickTime(5) {
                val head = head(helper); head.shieldLayers = 0; head.nextFireTick = Long.MAX_VALUE; before = head.health
                val tnt = helper.spawn(EntityType.TNT,BlockPos(6,5,6)); tnt.setNoGravity(true); tnt.setFuse(2)
            }
            helper.runAtTickTime(12) {
                helper.assertTrue(head(helper).health in 0.001..before && head(helper).health < before,"Real TNT must reduce head health without directly deleting its block")
                helper.assertBlockPresent(ModContent.BASE.get(),BASE)
                val head = head(helper); head.health = head.effectiveStats.health; head.shieldLayers = 0; before = head.health
                val creeper = helper.spawnWithNoFreeWill(EntityType.CREEPER,6,5,6); creeper.setNoGravity(true); creeper.ignite()
            }
            helper.runAtTickTime(48) {
                helper.assertTrue(head(helper).health > 0 && head(helper).health < before,"Real creeper explosion must pass through machine damage settlement")
                helper.assertBlockPresent(ModContent.BASE.get(),BASE)
                head(helper).shieldLayers = 0; head(helper).takeDamage(1000.0)
                helper.assertTrue(helper.level.getBlockEntity(helper.absolutePos(BASE.above())) == null,"Head health depletion must remove only the head")
                helper.assertBlockPresent(ModContent.BASE.get(),BASE); helper.succeed()
            }
        }

        // 使用实际对外能力接口验证六面输入、配置关闭以及能力失效后的重建。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun external_capabilities_and_revival(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val core = core(helper); core.energy.stored = 0
                for (direction in Direction.values()) {
                    val storage = core.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY,direction).resolve().orElseThrow()
                    helper.assertTrue(storage.receiveEnergy(100,true) == 100,"FE simulation must be accepted from every side")
                    storage.receiveEnergy(100,false)
                    helper.assertTrue(storage.extractEnergy(100,false) == 0,"Input-only core must not expose internal energy extraction")
                }
                helper.assertTrue(core.energy.stored == 600,"Simulated receipts cannot double count FE")
                val enabled = Config.externalFE.get()
                try { Config.externalFE.set(false); helper.assertTrue(core.energy.receiveEnergy(100,false) == 0,"External FE configuration must reject input") }
                finally { Config.externalFE.set(enabled) }
                val old = core.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                core.invalidateCaps(); helper.assertTrue(old.resolve().isEmpty,"Old capability must invalidate")
                core.reviveCaps()
                helper.assertTrue(core.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).resolve().orElseThrow().energyStored == 600,"Revived capability must retain stored energy")
                helper.succeed()
            }
        }

        // 核心仅剩一个空槽时，传输必须先填入整组经验瓶并保留暂时无法传出的物品。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=160)
        fun scheduled_loot_transfer_prioritizes_experience(helper: GameTestHelper) {
            setup(helper)
            val drop = Config.dropOnFull.get()
            helper.runAtTickTime(5) {
                Config.dropOnFull.set(false)
                val core = core(helper); val base = base(helper); head(helper).nextFireTick = Long.MAX_VALUE
                for (slot in 19 until 46) core.inventory.setStackInSlot(slot,ItemStack(Items.DIRT,64))
                core.inventory.setStackInSlot(19,ItemStack.EMPTY)
                base.inventory.setStackInSlot(5,ItemStack(Items.DIAMOND,64)); base.inventory.setStackInSlot(6,ItemStack(Items.EXPERIENCE_BOTTLE,64))
            }
            helper.runAtTickTime(115) {
                try {
                    val core = core(helper); val base = base(helper)
                    helper.assertTrue(core.inventory.getStackInSlot(19).`is`(Items.EXPERIENCE_BOTTLE) && core.inventory.getStackInSlot(19).count == 64,"Scheduled transfer must prioritize experience bottles")
                    helper.assertTrue(base.inventory.getStackInSlot(6).isEmpty && base.inventory.getStackInSlot(5).count == 64,"Full core must leave nontransferred items in the base")
                    helper.succeed()
                } finally { Config.dropOnFull.set(drop) }
            }
        }

        // 零能量停止修复，供能恢复后由真实核心维护周期执行修复。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=100)
        fun periodic_repair_requires_core_energy(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) { val core = core(helper); core.energy.stored = 0; head(helper).health = 1.0; head(helper).nextFireTick = Long.MAX_VALUE }
            helper.runAtTickTime(30) { helper.assertTrue(head(helper).health == 1.0,"Empty core cannot repair heads"); core(helper).energy.stored = core(helper).stats.energy }
            helper.runAtTickTime(60) { helper.assertTrue(head(helper).health > 1.0,"Powered core must repair damaged heads on its maintenance cycle"); helper.succeed() }
        }

        // 对整个测试体积逐方块比对，自爆、裁决、核心弹药殉爆不得修改其他方块。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=80)
        fun all_mod_blasts_preserve_surrounding_blocks(helper: GameTestHelper) {
            setup(helper)
            val original = linkedMapOf<BlockPos,net.minecraft.world.level.block.state.BlockState>()
            helper.runAtTickTime(5) {
                for (x in 0 until 16) for (y in 0 until 8) for (z in 0 until 16) {
                    val pos = helper.absolutePos(BlockPos(x,y,z)); val state = helper.level.getBlockState(pos)
                    if (state.block !is TierBlock) original[pos] = state
                }
                val core = core(helper); val head = head(helper)
                val enemies = (9..11).map { helper.spawnWithNoFreeWill(EntityType.ZOMBIE,it,3,6) }
                Combat.judgment(core,enemies)
                Combat.headBlast(head,5.0,40.0)
                core.inventory.setStackInSlot(1,ItemStack(ModContent.item("ammo_grenade"),8))
                core.destroyCore()
            }
            helper.runAtTickTime(45) {
                helper.assertTrue(original.all { (pos,state) -> helper.level.getBlockState(pos) == state },"Self-destruct, judgment and martyr blasts must preserve every surrounding block")
                helper.succeed()
            }
        }

        // 让原版流体、火焰和活塞实际更新，确认底座不被替换或移动。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=140)
        fun base_resists_water_fire_and_piston(helper: GameTestHelper) {
            setup(helper)
            lateinit var originalBase: TurretBaseBlockEntity
            helper.runAtTickTime(5) {
                originalBase = base(helper)
                helper.setBlock(BASE.above(3),Blocks.WATER)
                helper.setBlock(7,2,6,Blocks.STONE); helper.setBlock(7,3,6,Blocks.FIRE)
                helper.setBlock(5,3,6,Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,Direction.EAST))
                helper.setBlock(5,3,5,Blocks.REDSTONE_BLOCK)
            }
            helper.runAtTickTime(100) {
                helper.assertBlockPresent(ModContent.BASE.get(),BASE)
                helper.assertBlockPresent(ModContent.HEADS.getValue(TurretKind.Pulse).get(),BASE.above())
                helper.assertTrue(helper.level.getBlockEntity(helper.absolutePos(BASE)) === originalBase,"Piston and fluid updates cannot replace the base entity")
                helper.succeed()
            }
        }

        // 远离强加载的测试结构，只读检查不会给远端区块续票，待其自然卸载后再重新加载。
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=300)
        fun natural_chunk_unload_clears_runtime_and_rebinds(helper: GameTestHelper) {
            val corePos = BlockPos(20000,60,20000); val basePos = corePos.east(4); val headPos = basePos.above()
            var oldHead: TurretHeadBlockEntity? = null
            helper.runAtTickTime(5) {
                val level = helper.level
                level.setBlock(corePos,ModContent.CORE.get().defaultBlockState().setValue(TierBlock.TIER,5),3)
                level.setBlock(basePos,ModContent.BASE.get().defaultBlockState().setValue(TierBlock.TIER,3),3)
                level.setBlock(headPos,ModContent.HEADS.getValue(TurretKind.Pulse).get().defaultBlockState().setValue(TierBlock.TIER,2).setValue(TurretHeadBlock.FACING,Direction.DOWN),3)
                oldHead = level.getBlockEntity(headPos) as TurretHeadBlockEntity
                oldHead!!.pendingXp = 6; oldHead!!.kills = 23; oldHead!!.setChanged()
            }
            helper.runAtTickTime(220) {
                val level = helper.level; val runtime = LevelRuntime.get(level)
                helper.assertTrue(level.chunkSource.getChunkNow(corePos.x shr 4,corePos.z shr 4) == null,"Remote chunk must naturally unload without a forced ticket")
                helper.assertTrue(oldHead!!.isRemoved && !runtime.heads.containsKey(headPos.asLong()) && !runtime.bases.containsKey(basePos.asLong()) && !runtime.cores.containsKey(corePos.asLong()),"Unloaded machines cannot remain in runtime registries")
                level.setChunkForced(corePos.x shr 4,corePos.z shr 4,true)
                level.getBlockEntity(corePos); level.getBlockEntity(basePos); level.getBlockEntity(headPos)
            }
            helper.runAtTickTime(245) {
                try {
                    val level = helper.level; val core = level.getBlockEntity(corePos) as BaseCoreBlockEntity
                    val base = level.getBlockEntity(basePos) as TurretBaseBlockEntity; val head = level.getBlockEntity(headPos) as TurretHeadBlockEntity
                    helper.assertTrue(head !== oldHead && head.pendingXp == 6 && head.kills == 23,"Reload must instantiate saved head data")
                    helper.assertTrue(head.boundBase === base && base.core === core && core.managedBases[basePos.asLong()] === base,"Reloaded machines must rebind bidirectionally")
                    helper.succeed()
                } finally { helper.level.setChunkForced(corePos.x shr 4,corePos.z shr 4,false) }
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun priority_change_reselects_sticky_target(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val core = core(helper); val base = base(helper); val head = head(helper)
                core.energy.stored = core.stats.energy; base.energy.stored = 1000
                base.inventory.setStackInSlot(0,ItemStack(ModContent.item("module_target_priority")))
                val near = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,10,3,6)
                val far = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,12,3,8)
                for (zombie in listOf(near,far)) { zombie.setNoGravity(true); zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,ItemStack(Items.IRON_HELMET)) }
                head.acceptThreats(ThreatSnapshot(listOf(near,far),doubleArrayOf(near.distanceToSqr(core.center),far.distanceToSqr(core.center)),helper.level.gameTime))
                FireBudget.clear(); FireBudget.beginTick(); head.nextFireTick = helper.level.gameTime; head.serverTick()
                val nearHealth = near.health; val farHealth = far.health
                helper.assertTrue(nearHealth < farHealth,"Nearest target must be selected first")
                head.configurePriority("highest_health")
                FireBudget.clear(); FireBudget.beginTick(); head.nextFireTick = helper.level.gameTime; head.serverTick()
                helper.assertTrue(near.health == nearHealth && far.health < farHealth,"Changed priority must invalidate the previous sticky target")
                FireBudget.clear(); helper.succeed()
            }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",batch="stress",timeoutTicks=260)
        fun two_hundred_heads_and_four_hundred_monsters(helper: GameTestHelper) {
            val previousLimit = Config.maxBases.get()
            Config.maxBases.set(34)
            val player = FakePlayerFactory.getMinecraft(helper.level)
            val position = helper.absolutePos(BlockPos(48,1,48))
            player.moveTo(position.x.toDouble(),position.y.toDouble(),position.z.toDouble(),0f,0f)
            for (x in 8..88) for (z in 8..88) helper.setBlock(x,2,z,Blocks.STONE)
            helper.assertTrue(TurretCommands.setup(player.createCommandSourceStack(),200) == 1,"Stress scene command must create the full requested count")
            val corePos = BlockPos.containing(player.position()).above(3)
            helper.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie::class.java,AABB(corePos).inflate(40.0)).forEach { it.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,ItemStack(Items.IRON_HELMET)) }
            helper.runAtTickTime(10) {
                val core = helper.level.getBlockEntity(corePos) as BaseCoreBlockEntity
                helper.assertTrue(core.managedBases.size == 34 && core.managedBases.values.sumOf { it.heads.size } == 200,"All 200 heads must be registered to one T5 core")
            }
            helper.runAtTickTime(220) {
                val core = helper.level.getBlockEntity(corePos) as BaseCoreBlockEntity
                val runtime = LevelRuntime.get(helper.level)
                helper.assertTrue(core.scanCount > 0 && core.scanPeakNanos > 0,"Stress scene must execute measured scans")
                helper.assertTrue(FireBudget.peakFires <= 64,"Stress scene cannot exceed the global firing limit")
                TurretDefense.LOGGER.info("Stress sample after warmup: requestedHeads=200, initialMonsters=400, currentEnemies={}, coreRange={}, modMeanMs={}, modPeakMs={}, scanMeanMs={}, scanPeakMs={}, pathMeanMs={}, pathPeakMs={}, peakFires={}",core.snapshot.targets.size,core.stats.range,runtime.meanMillis(),runtime.peakMillis(),core.scanTotalNanos.toDouble()/core.scanCount/1_000_000.0,core.scanPeakNanos/1_000_000.0,if (runtime.pathCount == 0L) 0.0 else runtime.pathNanos.toDouble()/runtime.pathCount/1_000_000.0,runtime.pathPeakNanos/1_000_000.0,FireBudget.peakFires)
                // 压测批次结束必须移除自己的怪物和机器，避免其攻击或殉爆污染随后运行的功能测试。
                helper.level.getEntitiesOfClass(net.minecraft.world.entity.Entity::class.java,AABB(corePos).inflate(96.0)).filter { it !is net.minecraft.world.entity.player.Player }.forEach { it.discard() }
                val bases = core.managedBases.values.toList()
                val positions = bases.flatMap { it.heads.values.map { head -> head.blockPos } }+bases.map { it.blockPos }+core.blockPos
                positions.forEach { helper.level.removeBlockEntity(it); helper.level.setBlock(it,Blocks.AIR.defaultBlockState(),3) }
                TurretDefense.LOGGER.debug("Stress fixture cleaned before the next test batch: machines={}",positions.size)
                Config.maxBases.set(previousLimit)
                helper.succeed()
            }
            helper.runAtTickTime(45) {
                val core = helper.level.getBlockEntity(corePos) as BaseCoreBlockEntity
                helper.assertTrue(core.currentScanInterval == 40,"400-monster load must trigger adaptive scanning")
            }
            helper.runAtTickTime(60) { LevelRuntime.get(helper.level).resetSamples(); (helper.level.getBlockEntity(corePos) as BaseCoreBlockEntity).resetProbe() }
        }

        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense",timeoutTicks=40)
        fun advanced_bases_remain_offline_in_emergency(helper: GameTestHelper) {
            setup(helper)
            helper.runAtTickTime(5) {
                val core = core(helper); val head = head(helper)
                for (energy in intArrayOf(30000,7000)) {
                    core.energy.stored = energy; head.nextFireTick = helper.level.gameTime; head.serverTick()
                    helper.assertTrue(head.status == "offline","Advanced bases must remain offline as energy falls below 20 and 5 percent")
                }
                val zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE,10,3,6)
                val enabled = Config.monstersAttack.get()
                try { Config.monstersAttack.set(false); helper.assertTrue(!com.turretdefense.targeting.TurretAttackGoal(zombie).canUse(),"Disabled monster attacks must not activate") }
                finally { Config.monstersAttack.set(enabled) }
                helper.succeed()
            }
        }
    }
}
