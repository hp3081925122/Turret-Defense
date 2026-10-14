package com.turretdefense.loot

import com.turretdefense.blockentity.TurretHeadBlockEntity
import com.turretdefense.combat.TurretDamageSource
import com.turretdefense.config.Config
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameRules
import net.minecraftforge.event.ForgeEventFactory
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingDropsEvent
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent
import java.util.IdentityHashMap

// 经验归属仅在同步死亡回调期间保存；不留持久实体引用，离线所有者也能收集经验。
object LootRouting {
    private val xpRecipients = IdentityHashMap<LivingEntity,TurretDamageSource>()
    fun death(event: LivingDeathEvent) {
        val source = event.source as? TurretDamageSource ?: return
        if (!Config.collectLoot.get() || event.isCanceled) return
        source.head?.let { it.kills++; it.setChanged() }
        if (!event.entity.level().gameRules.getBoolean(GameRules.RULE_DOMOBLOOT)) return
        xpRecipients[event.entity] = source
        try { ForgeEventFactory.getExperienceDrop(event.entity,source.entity as? Player,event.entity.experienceReward) }
        finally { xpRecipients.remove(event.entity) }
        event.entity.skipDropExperience()
    }
    fun drops(event: LivingDropsEvent) {
        val source = event.source as? TurretDamageSource ?: return
        if (!Config.collectLoot.get() || event.isCanceled) return
        val base = source.head?.boundBase?.takeIf { !it.isRemoved }
        val core = source.core?.takeIf { !it.isRemoved }
        if (base == null && core == null) return
        event.isCanceled = true
        event.drops.forEach { entity -> if (base != null) base.acceptLoot(entity.item.copy()) else core!!.acceptLoot(entity.item.copy()) }
    }
    fun experience(event: LivingExperienceDropEvent) {
        val source = xpRecipients[event.entity] ?: return
        if (!Config.collectLoot.get() || event.isCanceled) return
        event.isCanceled = true
        val head = source.head
        if (head != null && !head.isRemoved && head.boundBase?.isRemoved == false) {
            head.pendingXp = (head.pendingXp.toLong()+event.droppedExperience).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            flush(head); head.setChanged()
        } else {
            val core = source.core?.takeIf { !it.isRemoved } ?: return
            core.pendingXp = (core.pendingXp.toLong()+event.droppedExperience).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val count = minOf(core.pendingXp/Config.xpRatio.get(),64)
            if (count > 0) { val left = core.acceptLoot(ItemStack(Items.EXPERIENCE_BOTTLE,count)); core.pendingXp -= (count-left.count)*Config.xpRatio.get() }
            core.setChanged()
        }
    }
    fun flush(head: TurretHeadBlockEntity) {
        val base = head.boundBase ?: return
        val count = minOf(head.pendingXp/Config.xpRatio.get(),64)
        if (count <= 0) return
        val left = base.acceptLoot(ItemStack(Items.EXPERIENCE_BOTTLE,count))
        head.pendingXp -= (count-left.count)*Config.xpRatio.get(); head.setChanged()
    }
    fun clear() { xpRecipients.clear() }
}
