package com.turretdefense.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.turretdefense.TurretDefense
import com.turretdefense.block.*
import com.turretdefense.blockentity.*
import com.turretdefense.config.Config
import com.turretdefense.network.*
import com.turretdefense.registry.ModContent
import com.turretdefense.turret.TurretKind
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

// 压测搭建只允许管理员主动执行；正常游戏不会生成压力场景。
object TurretCommands {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(Commands.literal("turretdefense")
            .then(Commands.literal("perf").requires { it.hasPermission(2) }.executes { context ->
                val world = context.source.level; val runtime = LevelRuntime.get(world)
                val scans = runtime.cores.values.sumOf { it.scanCount }
                val scanMean = if (scans == 0L) 0.0 else runtime.cores.values.sumOf { it.scanTotalNanos }.toDouble()/scans/1_000_000.0
                val scanPeak = (runtime.cores.values.maxOfOrNull { it.scanPeakNanos } ?: 0L)/1_000_000.0
                context.source.sendSuccess({ Component.translatable("command.turretdefense.perf",runtime.cores.size,runtime.bases.size,runtime.heads.size,String.format(java.util.Locale.ROOT,"%.3f",runtime.meanMillis()),String.format(java.util.Locale.ROOT,"%.3f",runtime.peakMillis()),String.format(java.util.Locale.ROOT,"%.3f",scanMean),String.format(java.util.Locale.ROOT,"%.3f",scanPeak),FireBudget.fires,FireBudget.peakFires,FireBudget.limitHits,runtime.particlePackets,world.server.averageTickTime) },false)
                TurretDefense.LOGGER.info("Turret performance: cores={}, bases={}, heads={}, meanMs={}, peakMs={}, scanMeanMs={}, scanPeakMs={}, fires={}, peakFires={}, budgetHits={}",runtime.cores.size,runtime.bases.size,runtime.heads.size,runtime.meanMillis(),runtime.peakMillis(),scanMean,scanPeak,FireBudget.fires,FireBudget.peakFires,FireBudget.limitHits)
                1
            })
            .then(Commands.literal("debug").requires { it.hasPermission(2) }.then(Commands.literal("setupStress").then(Commands.argument("heads",IntegerArgumentType.integer(1,384)).executes { context -> setup(context.source,IntegerArgumentType.getInteger(context,"heads")) }))))
    }
    internal fun setup(source: CommandSourceStack,count: Int): Int {
        if (ceil(count/6.0) > Config.maxBases.get()) { source.sendFailure(Component.translatable("command.turretdefense.stress_limit",Config.maxBases.get()*6)); return 0 }
        val world = source.level; val center = BlockPos.containing(source.position).above(3)
        world.setBlock(center,ModContent.CORE.get().defaultBlockState().setValue(TierBlock.TIER,5),3)
        val core = world.getBlockEntity(center) as? BaseCoreBlockEntity ?: return 0
        core.energy.stored = core.energy.getMaxEnergyStored(); core.burnTicks = 1000000
        var placed = 0; var baseIndex = 0
        while (placed < count) {
            val angle = baseIndex*2.399963229728653; val radius = 6.0+sqrtIndex(baseIndex)*3
            val pos = center.offset((cos(angle)*radius).toInt(),0,(sin(angle)*radius).toInt())
            if (Direction.values().any { world.getBlockState(pos.relative(it)).block is TierBlock } || world.getBlockState(pos).block is TierBlock) { baseIndex++; continue }
            world.setBlock(pos,ModContent.BASE.get().defaultBlockState().setValue(TierBlock.TIER,5),3)
            val base = world.getBlockEntity(pos) as? TurretBaseBlockEntity ?: return 0
            base.energy.stored = base.energy.getMaxEnergyStored(); base.bindCore(core)
            for (direction in Direction.values()) {
                if (placed >= count) break
                world.setBlock(pos.relative(direction),ModContent.HEADS.getValue(TurretKind.Pulse).get().defaultBlockState().setValue(TierBlock.TIER,3).setValue(TurretHeadBlock.FACING,direction.opposite),3)
                placed++
            }
            baseIndex++
        }
        for (index in 0 until 400) {
            val angle = index*2.399963229728653; val radius = 2.0+kotlin.math.sqrt(index/400.0)*28
            val zombie = EntityType.ZOMBIE.create(world) ?: continue
            zombie.moveTo(center.x+cos(angle)*radius,center.y.toDouble(),center.z+sin(angle)*radius,0f,0f)
            zombie.setPersistenceRequired(); world.addFreshEntity(zombie)
        }
        source.sendSuccess({ Component.translatable("command.turretdefense.stress_created",placed,400) },true)
        return 1
    }
    private fun sqrtIndex(value: Int) = kotlin.math.sqrt(value.toDouble())
}
