package com.turretdefense.block

import com.turretdefense.blockentity.*
import com.turretdefense.registry.ModContent
import com.turretdefense.turret.TurretKind
import com.turretdefense.ui.UiNetwork
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.phys.BlockHitResult

// 等级状态由物品决定，不通过物品 NBT 指定任意等级。
class TierBlockItem(block: Block,val tier: Int,id: String) : BlockItem(block,Item.Properties()) {
    private val nameKey = "item.turretdefense.$id"
    override fun getDescriptionId(): String = nameKey
    override fun getPlacementState(context: BlockPlaceContext): BlockState? = super.getPlacementState(context)?.setValue(TierBlock.TIER,tier)
}

abstract class TierBlock : BaseEntityBlock(BlockBehaviour.Properties.of().strength(4.0f,Float.MAX_VALUE).pushReaction(PushReaction.BLOCK).noOcclusion()) {
    init { registerDefaultState(stateDefinition.any().setValue(TIER,1)) }
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block,BlockState>) { builder.add(TIER) }
    override fun getRenderShape(state: BlockState) = RenderShape.MODEL
    override fun getPistonPushReaction(state: BlockState) = PushReaction.BLOCK
    abstract fun itemId(state: BlockState): String
    override fun getDrops(state: BlockState, builder: LootParams.Builder): MutableList<ItemStack> = mutableListOf((builder.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY) as? TurretHeadBlockEntity)?.dropStack() ?: ItemStack(ModContent.item(itemId(state))))
    override fun getCloneItemStack(level: net.minecraft.world.level.BlockGetter,pos: BlockPos,state: BlockState) = ItemStack(ModContent.item(itemId(state)))
    override fun setPlacedBy(level: Level,pos: BlockPos,state: BlockState,player: LivingEntity?,stack: ItemStack) {
        if (!level.isClientSide && player is Player) {
            when (val entity = level.getBlockEntity(pos)) {
                is BaseCoreBlockEntity -> entity.assignOwner(player.uuid)
                is TurretBaseBlockEntity -> entity.assignOwner(player.uuid)
            }
        }
    }
    override fun use(state: BlockState,level: Level,pos: BlockPos,player: Player,hand: InteractionHand,hit: BlockHitResult): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        if (player.isShiftKeyDown || player.mainHandItem.item is TierBlockItem) return InteractionResult.PASS
        if (player is ServerPlayer) {
            val entity = level.getBlockEntity(pos)
            val allowed = when (entity) {
                is BaseCoreBlockEntity -> entity.allowed(player)
                is TurretBaseBlockEntity -> entity.allowed(player)
                is TurretHeadBlockEntity -> entity.boundBase?.allowed(player) == true
                else -> false
            }
            if (allowed) UiNetwork.open(player,if (entity is TurretHeadBlockEntity) entity.boundBase!!.blockPos else pos)
            else player.displayClientMessage(Component.translatable("message.turretdefense.denied"),true)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
    companion object { val TIER: IntegerProperty = IntegerProperty.create("tier",1,5) }
}

class BaseCoreBlock : TierBlock() {
    override fun itemId(state: BlockState) = "base_core_t${state.getValue(TIER)}"
    override fun newBlockEntity(pos: BlockPos,state: BlockState) = BaseCoreBlockEntity(pos,state)
    override fun <T: BlockEntity> getTicker(level: Level,state: BlockState,type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null else createTickerHelper(type,ModContent.CORE_BE.get()) { _,_,_,entity -> entity.serverTick() }
    override fun onRemove(state: BlockState,level: Level,pos: BlockPos,newState: BlockState,moving: Boolean) {
        if (state.block !== newState.block && !level.isClientSide) (level.getBlockEntity(pos) as? BaseCoreBlockEntity)?.destroyCore()
        super.onRemove(state,level,pos,newState,moving)
    }
}

class TurretBaseBlock : TierBlock() {
    override fun itemId(state: BlockState) = "turret_base_t${state.getValue(TIER)}"
    override fun newBlockEntity(pos: BlockPos,state: BlockState) = TurretBaseBlockEntity(pos,state)
    override fun <T: BlockEntity> getTicker(level: Level,state: BlockState,type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null else createTickerHelper(type,ModContent.BASE_BE.get()) { _,_,_,entity -> entity.serverTick() }
    override fun onRemove(state: BlockState,level: Level,pos: BlockPos,newState: BlockState,moving: Boolean) {
        if (state.block !== newState.block && !level.isClientSide) (level.getBlockEntity(pos) as? TurretBaseBlockEntity)?.dismantle()
        super.onRemove(state,level,pos,newState,moving)
    }
}

class TurretHeadBlock(val kind: TurretKind) : TierBlock() {
    init { registerDefaultState(defaultBlockState().setValue(FACING,net.minecraft.core.Direction.DOWN)) }
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block,BlockState>) { super.createBlockStateDefinition(builder); builder.add(FACING) }
    override fun itemId(state: BlockState) = "${kind.id}_head_t${state.getValue(TIER)}"
    override fun newBlockEntity(pos: BlockPos,state: BlockState) = TurretHeadBlockEntity(pos,state)
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.clickedFace.opposite
        val base = context.level.getBlockEntity(context.clickedPos.relative(facing)) as? TurretBaseBlockEntity ?: return null
        if (!base.canAttach(facing.opposite) || (context.player != null && !base.allowed(context.player!!))) return null
        return defaultBlockState().setValue(FACING,facing)
    }
    override fun <T: BlockEntity> getTicker(level: Level,state: BlockState,type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null else createTickerHelper(type,ModContent.HEAD_BE.get()) { _,_,_,entity -> entity.serverTick() }
    companion object { val FACING = BlockStateProperties.FACING }
}
