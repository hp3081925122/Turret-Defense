package com.turretdefense.combat

import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeMap
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.living.LivingAttackEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID
import kotlin.math.roundToInt

// 效果随原版生命周期保存和到期，原有NoAI状态独立保存，避免眩晕永久禁用AI。
object ControlEffects {
    private const val ORIGINAL_AI = "TurretStunOriginalNoAI"
    private val effects = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS,"turretdefense")
    val STUN = effects.register("stun") { object : MobEffect(MobEffectCategory.HARMFUL,0x83DDF4) {
        override fun isDurationEffectTick(duration: Int,amplifier: Int) = true
        override fun applyEffectTick(entity: LivingEntity,amplifier: Int) { freeze(entity) }
        override fun addAttributeModifiers(entity: LivingEntity,attributes: AttributeMap,amplifier: Int) {
            super.addAttributeModifiers(entity,attributes,amplifier); freeze(entity)
        }
        override fun removeAttributeModifiers(entity: LivingEntity,attributes: AttributeMap,amplifier: Int) {
            super.removeAttributeModifiers(entity,attributes,amplifier); restore(entity)
        }
    } }
    val CORROSION = effects.register("corrosion") { object : MobEffect(MobEffectCategory.HARMFUL,0x86B730) {
        init { addAttributeModifier(Attributes.ARMOR,"f8ac3034-ab41-49fd-a1cb-f2e8fb1d94e2",-0.05,AttributeModifier.Operation.MULTIPLY_TOTAL) }
    } }
    val ACID_CORROSION = effects.register("acid_corrosion") { object : MobEffect(MobEffectCategory.HARMFUL,0x6F9B29) {
        init { addAttributeModifier(Attributes.ARMOR,"231410e1-b0f6-480e-9b4f-bbfb91eacaae",-0.1,AttributeModifier.Operation.MULTIPLY_TOTAL) }
    } }
    val CHILL = effects.register("chill") { object : MobEffect(MobEffectCategory.HARMFUL,0xB6E9F1) {
        init { addAttributeModifier(Attributes.MOVEMENT_SPEED,"d4e1c1ab-7089-4cf4-8897-b5b912d27e02",-0.01,AttributeModifier.Operation.MULTIPLY_TOTAL) }
    } }
    private val penetrationId = UUID.fromString("d4085078-70db-4856-8a01-a391b9e5f185")
    fun register(bus: IEventBus) {
        effects.register(bus)
        MinecraftForge.EVENT_BUS.addListener(::livingTick)
        MinecraftForge.EVENT_BUS.addListener(::attacking)
    }
    fun stun(entity: LivingEntity,ticks: Int) { entity.addEffect(MobEffectInstance(STUN.get(),ticks,0,false,true)) }
    fun slow(entity: LivingEntity,ticks: Int,amplifier: Int = 1) { entity.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,ticks,amplifier,false,true)) }
    fun slowPercent(entity: LivingEntity,ticks: Int,fraction: Double) { entity.addEffect(MobEffectInstance(CHILL.get(),ticks,((fraction*100).roundToInt()-1).coerceIn(0,99),false,true)) }
    fun corrode(entity: LivingEntity,ticks: Int,grade: Int) { entity.addEffect(MobEffectInstance(CORROSION.get(),ticks,(grade-1).coerceIn(0,2),false,true)) }
    fun acidCorrode(entity: LivingEntity,ticks: Int,stacks: Int) { entity.addEffect(MobEffectInstance(ACID_CORROSION.get(),ticks,(stacks-1).coerceIn(0,4),false,true)) }
    private fun freeze(entity: LivingEntity) {
        if (entity.level().isClientSide) return
        if (entity is Mob) {
            if (!entity.persistentData.contains(ORIGINAL_AI)) entity.persistentData.putBoolean(ORIGINAL_AI,entity.isNoAi)
            entity.isNoAi = true; entity.navigation.stop(); entity.target = null
        }
        entity.deltaMovement = Vec3.ZERO
    }
    private fun restore(entity: LivingEntity) {
        if (entity.level().isClientSide || entity !is Mob || !entity.persistentData.contains(ORIGINAL_AI)) return
        entity.isNoAi = entity.persistentData.getBoolean(ORIGINAL_AI); entity.persistentData.remove(ORIGINAL_AI)
    }
    private fun livingTick(event: LivingEvent.LivingTickEvent) {
        val entity = event.entity
        if (entity.hasEffect(STUN.get())) freeze(entity) else if (entity.persistentData.contains(ORIGINAL_AI)) restore(entity)
    }
    private fun attacking(event: LivingAttackEvent) {
        val attacker = event.source.entity as? LivingEntity ?: return
        if (event.source.directEntity === attacker && attacker.hasEffect(STUN.get())) event.isCanceled = true
    }
    // 只在本次同步伤害期间乘算护甲；不修改base value，不干扰其他模组的修饰项。
    fun <T> withArmorReduction(entity: LivingEntity,fraction: Double,action: () -> T): T {
        val armor = entity.getAttribute(Attributes.ARMOR)
        if (armor == null || fraction <= 0.0) return action()
        val previous = armor.getModifier(penetrationId)
        previous?.let { armor.removeModifier(it) }
        armor.addTransientModifier(AttributeModifier(penetrationId,"turret_armor_penetration",-fraction.coerceIn(0.0,1.0),AttributeModifier.Operation.MULTIPLY_TOTAL))
        return try { action() } finally {
            armor.removeModifier(penetrationId); previous?.let { armor.addTransientModifier(it) }
        }
    }
}
