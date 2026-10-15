package com.turretdefense.client

import net.minecraftforge.client.model.data.ModelData
import net.minecraftforge.client.model.data.ModelProperty

// 只持有 Forge 的不可变模型数据，不加载 Minecraft 客户端，服务端方块实体也能安全引用。
object AimModelData {
    val DYNAMIC = ModelProperty<Boolean>()
    val CAMOUFLAGE = ModelProperty<Int>()
    val HIDDEN = ModelProperty<Boolean>()
    private val dynamic = ModelData.builder().with(DYNAMIC,true).build()
    private val stationary = ModelData.builder().with(DYNAMIC,false).build()
    fun of(enabled: Boolean,camouflage: Int = -1,hidden: Boolean = false): ModelData = if (camouflage < 0 && !hidden) { if (enabled) dynamic else stationary } else ModelData.builder().with(DYNAMIC,enabled).with(CAMOUFLAGE,camouflage).with(HIDDEN,hidden).build()
}
