package com.turretdefense.client

import com.mojang.blaze3d.vertex.PoseStack
import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.ui.AimUpdates
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.RenderTypeHelper
import net.minecraftforge.client.model.data.ModelData

// 固定支撑由区块模型负责，渲染器只绕安装面外侧的轴承转动预烘焙炮管。
class TurretHeadRenderer : BlockEntityRenderer<TurretHeadBlockEntity> {
    private val random = RandomSource.create(42)

    override fun render(head: TurretHeadBlockEntity,partialTick: Float,pose: PoseStack,buffers: MultiBufferSource,light: Int,overlay: Int) {
        if (!head.dynamicRender || head.renderCamouflage >= 0 || head.renderHidden) return
        val client = Minecraft.getInstance()
        val model = client.modelManager.getModel(AimModels.barrelId(head.kind.id,head.tier))
        val outward = head.facing.opposite.normal
        pose.pushPose()
        try {
            pose.translate(0.5+outward.x/16.0,0.5+outward.y/16.0,0.5+outward.z/16.0)
            pose.mulPose(ClientAimTracking.rotation(head,partialTick))
            pose.translate(-0.5,-9.0/16.0,-0.5)
            random.setSeed(42)
            for (type in model.getRenderTypes(head.blockState,random,ModelData.EMPTY)) {
                val buffer = buffers.getBuffer(RenderTypeHelper.getEntityRenderType(type,false))
                client.blockRenderer.modelRenderer.renderModel(pose.last(),buffer,head.blockState,model,1.0f,1.0f,1.0f,light,overlay,ModelData.EMPTY,type)
            }
        } finally { pose.popPose() }
    }

    override fun shouldRender(head: TurretHeadBlockEntity,camera: Vec3): Boolean = head.dynamicRender
    override fun getViewDistance(): Int = AimUpdates.DISTANCE
}
