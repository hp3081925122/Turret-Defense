package com.turretdefense.worldgen

import com.turretdefense.TurretDefense
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister

// 结构类型和存档片段类型使用独立注册器，数据包负责分布和群系筛选。
object WorldgenContent {
    private val types = DeferredRegister.create(Registries.STRUCTURE_TYPE, TurretDefense.MOD_ID)
    private val pieces = DeferredRegister.create(Registries.STRUCTURE_PIECE, TurretDefense.MOD_ID)
    val RUINED_BASE = types.register("ruined_base") { StructureType { RuinedBaseStructure.CODEC } }
    val RUINED_BASE_PIECE = pieces.register("ruined_base") {
        StructurePieceType { context, tag -> RuinedBasePiece(context.structureTemplateManager(), tag) }
    }
    fun register(bus: IEventBus) {
        types.register(bus)
        pieces.register(bus)
    }
}
