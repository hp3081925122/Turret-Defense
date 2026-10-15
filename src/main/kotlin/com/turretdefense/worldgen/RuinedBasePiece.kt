package com.turretdefense.worldgen

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager

// 模板仅包含原版残骸和箱子，机器以箱内物品提供，避免无人核心参与战斗。
class RuinedBasePiece : TemplateStructurePiece {
    constructor(manager: StructureTemplateManager, position: BlockPos, rotation: Rotation) : super(
        WorldgenContent.RUINED_BASE_PIECE.get(), 0, manager, TEMPLATE, TEMPLATE.toString(), settings(rotation), position
    )

    constructor(manager: StructureTemplateManager, tag: CompoundTag) : super(
        WorldgenContent.RUINED_BASE_PIECE.get(), tag, manager, { settings(Rotation.valueOf(tag.getString("Rot"))) }
    )

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        super.addAdditionalSaveData(context, tag)
        tag.putString("Rot", placeSettings.rotation.name)
    }

    // 结构标记位于箱子上方；只处理当前放置范围内的箱子，防止跨区块重复结算。
    override fun handleDataMarker(marker: String, position: BlockPos, level: ServerLevelAccessor, random: RandomSource, bounds: BoundingBox) {
        if (!bounds.isInside(position) || !WorldgenConfig.suppliesEnabled.get()) return
        if (marker != "supplies" && marker != "extra_supplies") return
        if (marker == "extra_supplies" && random.nextDouble() >= WorldgenConfig.extraSupplyChestChance.get()) return
        val chest = level.getBlockEntity(position.below()) as? ChestBlockEntity ?: return
        chest.setLootTable(LOOT, random.nextLong())
    }

    companion object {
        val TEMPLATE: ResourceLocation = ResourceLocation.fromNamespaceAndPath("turretdefense", "ruined_base")
        val LOOT: ResourceLocation = ResourceLocation.fromNamespaceAndPath("turretdefense", "chests/ruined_base")
        private fun settings(rotation: Rotation): StructurePlaceSettings = StructurePlaceSettings()
            .setRotation(rotation).setMirror(Mirror.NONE).setRotationPivot(BlockPos(6, 0, 6))
            .setIgnoreEntities(true).setKeepLiquids(false).addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK)
    }
}
