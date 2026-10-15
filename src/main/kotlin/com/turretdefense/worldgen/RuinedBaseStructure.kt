package com.turretdefense.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import java.util.Optional

// 使用原生结构生成上下文查询五处地表，不访问服务器区块加载接口。
class RuinedBaseStructure(settings: StructureSettings) : Structure(settings) {
    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        if (!WorldgenConfig.enabled.get() || context.random().nextDouble() >= WorldgenConfig.generationChance.get()) return Optional.empty()
        val x = context.chunkPos().minBlockX + 7
        val z = context.chunkPos().minBlockZ + 7
        val samples = arrayOf(0 to 0, -6 to -6, -6 to 6, 6 to -6, 6 to 6).map { (dx, dz) ->
            context.chunkGenerator().getFirstOccupiedHeight(x + dx, z + dz, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState())
        }
        val low = samples.min()
        val high = samples.max()
        if (low < maxOf(WorldgenConfig.minimumSurfaceY.get(), context.chunkGenerator().seaLevel) || high > WorldgenConfig.maximumSurfaceY.get() || high - low > WorldgenConfig.maximumSlope.get()) return Optional.empty()
        val origin = BlockPos(x - 6, low, z - 6)
        val rotation = Rotation.getRandom(context.random())
        return Optional.of(GenerationStub(BlockPos(x, low, z)) { builder ->
            builder.addPiece(RuinedBasePiece(context.structureTemplateManager(), origin, rotation))
        })
    }

    override fun type(): StructureType<*> = WorldgenContent.RUINED_BASE.get()

    companion object {
        val CODEC: Codec<RuinedBaseStructure> = simpleCodec(::RuinedBaseStructure)
    }
}
