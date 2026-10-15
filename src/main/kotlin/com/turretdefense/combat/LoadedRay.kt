package com.turretdefense.combat

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult

// 使用原版形状射线，但未加载区块视作实体边界，长射程不会为寻路或弹道续加载区块。
object LoadedRay {
    fun clip(level: ServerLevel,context: ClipContext): BlockHitResult = object : BlockGetter {
        override fun getBlockEntity(pos: BlockPos) = level.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4)?.getBlockEntity(pos)
        override fun getBlockState(pos: BlockPos) = level.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4)?.getBlockState(pos) ?: Blocks.BARRIER.defaultBlockState()
        override fun getFluidState(pos: BlockPos) = getBlockState(pos).fluidState
        override fun getHeight() = level.height
        override fun getMinBuildHeight() = level.minBuildHeight
    }.clip(context)
}
