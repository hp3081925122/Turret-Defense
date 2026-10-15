package com.turretdefense.client

import com.turretdefense.TurretDefense
import com.turretdefense.block.TierBlock
import com.turretdefense.block.TurretHeadBlock
import com.turretdefense.registry.ModContent
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.BlockModelShaper
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.ModelEvent
import net.minecraftforge.client.model.BakedModelWrapper
import net.minecraftforge.client.model.data.ModelData
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

// 模型只随资源重载烘焙；近处留下固定支撑，远处和物品继续使用完整静态模型。
@Mod.EventBusSubscriber(modid=TurretDefense.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD,value=[Dist.CLIENT])
object AimModels {
    fun barrelId(kind: String,tier: Int): ResourceLocation = ResourceLocation.fromNamespaceAndPath(TurretDefense.MOD_ID,"block/aim/${kind}_head_t${tier}_barrel")
    private fun supportId(kind: String,tier: Int,facing: Direction): ResourceLocation = ResourceLocation.fromNamespaceAndPath(TurretDefense.MOD_ID,"block/aim/${kind}_head_t${tier}_support_${facing.serializedName}")

    @JvmStatic
    @SubscribeEvent
    fun additional(event: ModelEvent.RegisterAdditional) {
        for (kind in ModContent.HEADS.keys) for (tier in 1..5) {
            event.register(barrelId(kind.id,tier))
            Direction.values().forEach { event.register(supportId(kind.id,tier,it)) }
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun baking(event: ModelEvent.ModifyBakingResult) {
        for ((kind,block) in ModContent.HEADS) for (state in block.get().stateDefinition.possibleStates) {
            val id = BlockModelShaper.stateToModelLocation(state)
            val complete = event.models[id] ?: continue
            val support = event.models[supportId(kind.id,state.getValue(TierBlock.TIER),state.getValue(TurretHeadBlock.FACING))] ?: continue
            event.models[id] = HeadModel(complete,support)
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun renderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModContent.HEAD_BE.get()) { TurretHeadRenderer() }
    }

    private class HeadModel(complete: BakedModel,private val support: BakedModel) : BakedModelWrapper<BakedModel>(complete) {
        override fun getQuads(state: BlockState?,side: Direction?,random: RandomSource,data: ModelData,renderType: RenderType?): List<BakedQuad> {
            if (data.get(AimModelData.HIDDEN) == true) return emptyList()
            val camouflage = data.get(AimModelData.CAMOUFLAGE) ?: -1
            if (camouflage >= 0) {
                val disguised = net.minecraft.world.level.block.Block.stateById(camouflage)
                return net.minecraft.client.Minecraft.getInstance().blockRenderer.getBlockModel(disguised).getQuads(disguised,side,random,ModelData.EMPTY,renderType)
            }
            val selected = if (data.get(AimModelData.DYNAMIC) == true) support else originalModel
            return selected.getQuads(state,side,random,data,renderType)
        }
    }
}
