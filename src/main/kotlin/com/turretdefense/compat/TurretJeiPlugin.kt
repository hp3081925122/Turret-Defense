package com.turretdefense.compat

import com.turretdefense.registry.ModContent
import com.turretdefense.turret.*
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import net.minecraft.client.gui.GuiGraphics
import mezz.jei.api.registration.*
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

// 仅由 JEI 注解扫描加载；主模组不会引用该类，缺少 JEI 仍可运行。
@JeiPlugin
class TurretJeiPlugin : IModPlugin {
    override fun getPluginUid() = ResourceLocation("turretdefense","jei")
    override fun registerCategories(registration: IRecipeCategoryRegistration) { registration.addRecipeCategories(UpgradeCategory(registration.jeiHelpers.guiHelper)) }
    override fun registerRecipes(registration: IRecipeRegistration) {
        val recipes = Minecraft.getInstance().level?.recipeManager?.getAllRecipesFor(UpgradeRules.TYPE.get()) ?: emptyList()
        registration.addRecipes(UPGRADES,recipes)
    }
    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        for (tier in 1..5) { registration.addRecipeCatalyst(ItemStack(ModContent.item("base_core_t$tier")),UPGRADES); registration.addRecipeCatalyst(ItemStack(ModContent.item("turret_base_t$tier")),UPGRADES) }
    }
    companion object { val UPGRADES = RecipeType.create("turretdefense","upgrade",UpgradeRecipe::class.java) }
}

class UpgradeCategory(helper: IGuiHelper) : IRecipeCategory<UpgradeRecipe> {
    private val icon = helper.createDrawableItemStack(ItemStack(ModContent.item("turret_base_t1")))
    override fun getRecipeType() = TurretJeiPlugin.UPGRADES
    override fun getTitle() = Component.translatable("jei.turretdefense.upgrade")
    override fun getWidth() = 180
    override fun getHeight() = 76
    override fun getIcon() = icon
    override fun draw(recipe: UpgradeRecipe,slots: IRecipeSlotsView,graphics: GuiGraphics,mouseX: Double,mouseY: Double) {
        graphics.drawString(Minecraft.getInstance().font,Component.translatable("jei.turretdefense.energy",recipe.energyCost),28,7,0xff444444.toInt(),false)
    }
    override fun setRecipe(builder: IRecipeLayoutBuilder,recipe: UpgradeRecipe,focuses: IFocusGroup) {
        builder.addInputSlot(4,4).addItemStack(ItemStack(recipe.previousItem))
        recipe.costs.forEachIndexed { index,cost -> builder.addInputSlot(4+(index%4)*30,28+(index/4)*22).addItemStacks(cost.ingredient.items.map { it.copy().also { stack -> stack.count = cost.count } }) }
        builder.addOutputSlot(154,4).addItemStack(ItemStack(recipe.resultItem))
    }
}
