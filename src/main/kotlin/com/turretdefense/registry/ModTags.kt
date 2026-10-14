package com.turretdefense.registry

import com.turretdefense.TurretDefense
import com.turretdefense.turret.TurretKind
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item

// 弹药匹配使用预建标签，开火路径不解析字符串或创建资源标识。
object ModTags {
    val AMMO: TagKey<Item> = itemTag("ammo")
    val EXPLOSIVE_AMMO: TagKey<Item> = itemTag("ammo/explosive")
    val KIND_AMMO = TurretKind.ALL.associateWith { kind -> arrayOf(itemTag("ammo/${kind.id}_t4"),itemTag("ammo/${kind.id}_t5")) }
    val JUDGMENT_AMMO = arrayOf(itemTag("ammo/judgment_t4"),itemTag("ammo/judgment_t5"))
    private fun itemTag(path: String): TagKey<Item> = TagKey.create(Registries.ITEM,ResourceLocation.fromNamespaceAndPath(TurretDefense.MOD_ID,path))
}
