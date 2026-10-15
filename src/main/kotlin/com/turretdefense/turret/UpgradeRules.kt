package com.turretdefense.turret

import com.google.gson.JsonObject
import com.turretdefense.TurretDefense
import com.turretdefense.block.TierBlock
import com.turretdefense.blockentity.*
import com.turretdefense.registry.ModContent
import net.minecraft.core.NonNullList
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.*
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries

// 高级等级配方由机器界面执行，合成台不会误当作普通配方。
data class MaterialCost(val ingredient: Ingredient,val count: Int)
class UpgradeRecipe(private val recipeId: ResourceLocation,val machine: String,val fromTier: Int,val costs: List<MaterialCost>) : Recipe<Container> {
    val targetTier get() = fromTier+1
    val energyCost get() = when (machine) { "core" -> TurretStatsTable.CORE_UPGRADE_ENERGY[fromTier-1]; "base" -> 0; else -> TurretStatsTable.HEAD_UPGRADE_ENERGY[fromTier-1] }
    val previousItem get() = ModContent.item(if (machine == "core") "base_core_t$fromTier" else if (machine == "base") "turret_base_t$fromTier" else "${machine}_head_t$fromTier")
    val resultItem get() = ModContent.item(if (machine == "core") "base_core_t$targetTier" else if (machine == "base") "turret_base_t$targetTier" else "${machine}_head_t$targetTier")
    override fun matches(container: Container,level: Level) = UpgradeRules.plan(container,costs) != null
    override fun assemble(container: Container,registries: RegistryAccess) = ItemStack(resultItem)
    override fun canCraftInDimensions(width: Int,height: Int) = true
    override fun getResultItem(registries: RegistryAccess) = ItemStack(resultItem)
    override fun getId() = recipeId
    override fun getSerializer() = UpgradeRules.SERIALIZER.get()
    override fun getType() = UpgradeRules.TYPE.get()
    override fun isSpecial() = true
    override fun getIngredients(): NonNullList<Ingredient> = NonNullList.create<Ingredient>().also { list -> costs.forEach { list.add(it.ingredient) } }
}

class UpgradeSerializer : RecipeSerializer<UpgradeRecipe> {
    override fun fromJson(id: ResourceLocation,json: JsonObject): UpgradeRecipe {
        val machine = json.get("machine").asString; val tier = json.get("from_tier").asInt
        require((machine in listOf("core","base") || TurretKind.byId(machine) != null) && tier in 1..4)
        val array = json.getAsJsonArray("materials"); require(array.size() in 1..8)
        val costs = array.map { element -> val entry = element.asJsonObject; val count = entry.get("count").asInt; require(count in 1..256); MaterialCost(Ingredient.fromJson(entry.get("ingredient")),count) }
        return UpgradeRecipe(id,machine,tier,costs)
    }
    override fun fromNetwork(id: ResourceLocation,buffer: FriendlyByteBuf): UpgradeRecipe {
        val machine = buffer.readUtf(32); val tier = buffer.readVarInt(); val size = buffer.readVarInt()
        require(tier in 1..4 && size in 1..8)
        return UpgradeRecipe(id,machine,tier,List(size) { MaterialCost(Ingredient.fromNetwork(buffer),buffer.readVarInt().also { require(it in 1..256) }) })
    }
    override fun toNetwork(buffer: FriendlyByteBuf,recipe: UpgradeRecipe) {
        buffer.writeUtf(recipe.machine,32); buffer.writeVarInt(recipe.fromTier); buffer.writeVarInt(recipe.costs.size)
        recipe.costs.forEach { it.ingredient.toNetwork(buffer); buffer.writeVarInt(it.count) }
    }
}

// 预留材料和已支付能量随机器一起存档，支持超过单次缓存容量的升级。
class UpgradeJob(val recipe: ResourceLocation,var paid: Int,val reserved: List<ItemStack>) {
    fun save(): CompoundTag {
        val tag = CompoundTag(); tag.putString("Recipe",recipe.toString()); tag.putInt("Paid",paid)
        val items = ListTag(); reserved.forEach { items.add(it.save(CompoundTag())) }; tag.put("Reserved",items)
        return tag
    }
    companion object {
        fun load(tag: CompoundTag): UpgradeJob? {
            val id = ResourceLocation.tryParse(tag.getString("Recipe")) ?: return null
            val items = tag.getList("Reserved",10)
            return UpgradeJob(id,tag.getInt("Paid").coerceAtLeast(0),(0 until items.size).map { ItemStack.of(items.getCompound(it)) })
        }
    }
}

object UpgradeRules {
    private val serializers = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS,TurretDefense.MOD_ID)
    private val types = DeferredRegister.create(Registries.RECIPE_TYPE,TurretDefense.MOD_ID)
    val SERIALIZER = serializers.register("upgrade") { UpgradeSerializer() }
    val TYPE = types.register("upgrade") { object : RecipeType<UpgradeRecipe> { override fun toString() = "turretdefense:upgrade" } }
    fun register(bus: IEventBus) { serializers.register(bus); types.register(bus) }
    // 先规划每个槽位扣款；不足时完全不修改背包，重叠标签不会重复计算材料。
    fun plan(container: Container,costs: List<MaterialCost>): IntArray? {
        val remaining = IntArray(container.containerSize) { container.getItem(it).count }
        val consumed = IntArray(container.containerSize)
        for (cost in costs.sortedBy { cost -> remaining.indices.count { remaining[it] > 0 && cost.ingredient.test(container.getItem(it)) } }) {
            var needed = cost.count
            for (slot in remaining.indices) {
                if (remaining[slot] <= 0 || !cost.ingredient.test(container.getItem(slot))) continue
                val taken = minOf(needed,remaining[slot]); remaining[slot] -= taken; consumed[slot] += taken; needed -= taken
                if (needed == 0) break
            }
            if (needed != 0) return null
        }
        return consumed
    }
    fun upgradeCore(player: ServerPlayer,core: BaseCoreBlockEntity) = begin(player,core)
    fun upgradeBase(player: ServerPlayer,base: TurretBaseBlockEntity) = begin(player,base)
    fun upgradeHead(player: ServerPlayer,head: TurretHeadBlockEntity) = begin(player,head)
    fun nextRecipe(machine: MachineBlockEntity): UpgradeRecipe? {
        val world = machine.level as? ServerLevel ?: return null
        val id = when (machine) { is BaseCoreBlockEntity -> "core"; is TurretBaseBlockEntity -> "base"; is TurretHeadBlockEntity -> machine.kind.id; else -> return null }
        return world.recipeManager.getAllRecipesFor(TYPE.get()).firstOrNull { it.machine == id && it.fromTier == machine.tier }
    }
    private fun begin(player: ServerPlayer,machine: MachineBlockEntity): Boolean {
        if (machine.level !is ServerLevel) return false
        if (machine.tier >= 5 || machine.upgradeJob != null) return false
        val recipe = nextRecipe(machine) ?: return false
        val consumed = if (player.isCreative) IntArray(player.inventory.containerSize) else plan(player.inventory,recipe.costs) ?: return false
        val reserved = ArrayList<ItemStack>()
        for (slot in consumed.indices) if (consumed[slot] > 0) reserved.add(player.inventory.removeItem(slot,consumed[slot]))
        machine.upgradeJob = UpgradeJob(recipe.id,if (player.isCreative) recipe.energyCost else 0,reserved)
        machine.setChanged(); player.inventory.setChanged(); player.containerMenu.broadcastChanges()
        process(machine)
        return true
    }
    fun recipe(machine: MachineBlockEntity): UpgradeRecipe? {
        val level = machine.level as? ServerLevel ?: return null
        val job = machine.upgradeJob ?: return null
        return level.recipeManager.byKey(job.recipe).orElse(null) as? UpgradeRecipe
    }
    fun process(machine: MachineBlockEntity) {
        val job = machine.upgradeJob ?: return
        val level = machine.level as? ServerLevel ?: return
        val recipe = recipe(machine)
        if (recipe == null || recipe.fromTier != machine.tier) { cancel(machine); return }
        val buffer = when (machine) { is BaseCoreBlockEntity -> machine.energy; is TurretBaseBlockEntity -> machine.energy; is TurretHeadBlockEntity -> machine.boundBase?.energy; else -> null } ?: return
        val amount = minOf((recipe.energyCost-job.paid).coerceAtLeast(0),buffer.stored)
        if (amount > 0 && buffer.spend(amount)) { job.paid += amount; machine.setChanged() }
        if (job.paid < recipe.energyCost) return
        val oldHealth = when (machine) { is TurretHeadBlockEntity -> machine.health/machine.effectiveStats.health; is BaseCoreBlockEntity -> machine.health/(machine.stats.health*com.turretdefense.config.Config.healthMultiplier.get()); else -> 1.0 }
        machine.upgradeJob = null
        level.setBlock(machine.blockPos,machine.blockState.setValue(TierBlock.TIER,recipe.targetTier),3)
        when (machine) {
            is TurretHeadBlockEntity -> { machine.refreshStats(); machine.health = machine.effectiveStats.health*oldHealth; machine.shieldLayers = minOf(machine.shieldLayers,machine.effectiveStats.shield) }
            is BaseCoreBlockEntity -> { machine.refreshTier(); machine.health = machine.stats.health*com.turretdefense.config.Config.healthMultiplier.get()*oldHealth }
            is TurretBaseBlockEntity -> machine.refreshModules()
        }
        machine.setChanged()
        TurretDefense.LOGGER.debug("Machine upgrade completed: pos={}, tier={}, paidFE={}",machine.blockPos,recipe.targetTier,job.paid)
    }
    fun cancel(machine: MachineBlockEntity): Boolean {
        val job = machine.upgradeJob ?: return false
        val level = machine.level as? ServerLevel ?: return false
        machine.upgradeJob = null; machine.setChanged()
        job.reserved.forEach { Block.popResource(level,machine.blockPos,it.copy()) }
        return true
    }
}
