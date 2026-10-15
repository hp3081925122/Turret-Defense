package com.turretdefense.gametest

import com.turretdefense.blockentity.MachineBlockEntity
import com.turretdefense.worldgen.RuinedBasePiece
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

// 使用原生模板结构片段实际放置并读取箱子战利品，不以JSON存在代替生成验收。
@GameTestHolder("turretdefense_expansion")
@PrefixGameTestTemplate(false)
class WorldgenTests {
    companion object {
        @JvmStatic @GameTest(template="gametest_empty",templateNamespace="turretdefense_expansion",timeoutTicks=40)
        fun ruined_base_native_placement_loot_and_piece_reload(helper: GameTestHelper) {
            helper.runAtTickTime(5) {
                val world = helper.level
                val origin = helper.absolutePos(BlockPos(1,1,1))
                val piece = RuinedBasePiece(world.server.structureManager,origin,Rotation.NONE)
                val bounds = BoundingBox(origin.x,origin.y,origin.z,origin.x+12,origin.y+6,origin.z+12)
                piece.postProcess(world,world.structureManager(),world.chunkSource.generator,world.random,bounds,ChunkPos(origin),origin)
                val chests = BlockPos.betweenClosed(origin,origin.offset(12,6,12)).mapNotNull { world.getBlockEntity(it) as? ChestBlockEntity }.toList()
                helper.assertTrue(chests.size == 2,"Native ruined base template must place two real chests")
                helper.assertTrue(chests.any { chest -> (0 until chest.containerSize).any { !chest.getItem(it).isEmpty } },"Marker must assign a resolvable supply loot table")
                helper.assertTrue(BlockPos.betweenClosed(origin,origin.offset(12,6,12)).none { world.getBlockEntity(it) is MachineBlockEntity },"Abandoned base must not create live automated machines")
                val tag = piece.createTag(StructurePieceSerializationContext.fromLevel(world))
                val restored = RuinedBasePiece(world.server.structureManager,tag)
                helper.assertTrue(restored.rotation == Rotation.NONE && restored.templatePosition() == origin,"Structure piece must preserve template position and rotation through NBT")
                helper.succeed()
            }
        }
    }
}
