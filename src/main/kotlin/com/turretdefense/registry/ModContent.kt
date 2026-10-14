package com.turretdefense.registry

import com.turretdefense.TurretDefense
import com.turretdefense.block.*
import com.turretdefense.blockentity.*
import com.turretdefense.turret.TurretKind
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

// 方块固定为七个，等级只改变状态；每个等级都有独立可合成物品。
object ModContent {
    val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS,TurretDefense.MOD_ID)
    val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,TurretDefense.MOD_ID)
    val ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES,TurretDefense.MOD_ID)
    val TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB,TurretDefense.MOD_ID)
    val CORE = BLOCKS.register("base_core") { BaseCoreBlock() }
    val BASE = BLOCKS.register("turret_base") { TurretBaseBlock() }
    val HEADS: Map<TurretKind,RegistryObject<TurretHeadBlock>> = TurretKind.ALL.associateWith { kind -> BLOCKS.register("${kind.id}_head") { TurretHeadBlock(kind) } }
    val CORE_BE = ENTITIES.register("base_core") { BlockEntityType.Builder.of(::BaseCoreBlockEntity,CORE.get()).build(null) }
    val BASE_BE = ENTITIES.register("turret_base") { BlockEntityType.Builder.of(::TurretBaseBlockEntity,BASE.get()).build(null) }
    val HEAD_BE = ENTITIES.register("turret_head") { BlockEntityType.Builder.of(::TurretHeadBlockEntity,*HEADS.values.map { it.get() }.toTypedArray()).build(null) }
    val ALL_ITEMS = linkedMapOf<String,RegistryObject<out Item>>()
    init {
        for (tier in 1..5) {
            tierItem("base_core_t$tier",CORE,tier)
            tierItem("turret_base_t$tier",BASE,tier)
            HEADS.forEach { (kind,block) -> tierItem("${kind.id}_head_t$tier",block,tier) }
        }
        listOf("ammo_iron_slug","ammo_pulse_core","ammo_particle_clip","ammo_hv_particle_clip","ammo_grenade","ammo_he_grenade","ammo_arc_cell","ammo_hv_arc_cell","ammo_decider","ammo_finality","turret_core","nether_star_fragment",
            "module_damage_amp_1","module_fire_rate_1","module_shield_boost_1","module_armor_plate","module_target_priority","module_energy_saver_1","module_self_destruct","module_overload_core").forEach { id ->
            ALL_ITEMS[id] = ITEMS.register(id) { Item(Item.Properties()) }
        }
    }
    val TAB = TABS.register("main") {
        CreativeModeTab.builder().title(Component.translatable("itemGroup.turretdefense"))
            .icon { ItemStack(item("base_core_t1")) }
            .displayItems { _,output -> ALL_ITEMS.values.forEach { output.accept(it.get()) } }.build()
    }
    private fun tierItem(id: String, block: RegistryObject<out Block>, tier: Int) {
        ALL_ITEMS[id] = ITEMS.register(id) { TierBlockItem(block.get(),tier,id) }
    }
    fun item(id: String): Item = ALL_ITEMS.getValue(id).get()
    fun register(bus: IEventBus) { BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); TABS.register(bus) }
}
