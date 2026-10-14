package com.turretdefense.blockentity

import com.turretdefense.block.TierBlock
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

// 权限与等级共用，等级从方块状态读取，避免 NBT 与外观等级分离。
abstract class MachineBlockEntity(type: BlockEntityType<*>,pos: BlockPos,state: BlockState) : BlockEntity(type,pos,state) {
    val tier get() = blockState.getValue(TierBlock.TIER)
    var owner: UUID? = null
        protected set
    var upgradeJob: com.turretdefense.turret.UpgradeJob? = null
    open fun allowed(player: Player) = owner == null || owner == player.uuid || player.isCreative
    open fun assignOwner(id: UUID) { owner = id; setChanged() }
    override fun saveAdditional(tag: CompoundTag) { super.saveAdditional(tag); owner?.let { tag.putUUID("Owner",it) }; upgradeJob?.let { tag.put("UpgradeJob",it.save()) } }
    override fun load(tag: CompoundTag) { super.load(tag); owner = if (tag.hasUUID("Owner")) tag.getUUID("Owner") else null; upgradeJob = if (tag.contains("UpgradeJob")) com.turretdefense.turret.UpgradeJob.load(tag.getCompound("UpgradeJob")) else null }
}
