package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.foraging.HotfPerkApi;
import com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Draws each HotF perk's current level as a stack-count-style badge in the corner of its slot —
 * ported from SkyHanni's HotxFeatures.handleLevelStackSize. Drawn as a text overlay rather than
 * actually changing the ItemStack's count (SkyHanni's own "stackTip" is the same kind of overlay, not a
 * real count mutation, so this matches it exactly). Hidden at level 0 or once maxed, same as SkyHanni.
 */
public class HotfLevelStackFeature extends Feature {
	private static final int SLOT_SIZE = 16;

	public HotfLevelStackFeature() {
		super("hotf_level_stack", "Show Level As Item Stack", FeatureCategory.FORAGING, false);
		// Drawn from PreTooltipRenderRegistry (fires right before the hovered slot's tooltip, underneath it
		// in z-order), same as ActiveHotfPerksHighlightFeature/DnaAnalyzerSolverFeature — this used to draw
		// via ScreenEvents.afterExtract, which fires AFTER the tooltip too, so the stack-count badge on any
		// perk slot the tooltip visually extended over drew right on top of it.
		PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled()) return;
			if (!screen.getTitle().getString().contains(HotfPerkApi.INVENTORY_TITLE)) return;
			renderLevels(graphics, screen);
		});
	}

	@Override
	public String getSubcategory() {
		return "HOTF";
	}

	private void renderLevels(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();
		AbstractContainerMenu menu = screen.getMenu();
		var playerInventory = mc.player.getInventory();
		Font font = mc.font;

		for (Slot slot : menu.slots) {
			if (slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;

			String plainName = stack.getHoverName().getString().replaceAll("§.", "").trim();
			Integer maxLevel = HotfPerkApi.maxLevelForName(plainName);
			if (maxLevel == null) continue;
			Integer level = HotfPerkApi.currentLevel(stack);
			if (level == null || level == 0 || level.equals(maxLevel)) continue;

			String text = String.valueOf(level);
			int textWidth = font.width(text);
			int x = leftPos + slot.x + SLOT_SIZE - textWidth;
			int y = topPos + slot.y + SLOT_SIZE - 8;
			graphics.text(font, Component.literal(text), x, y, 0xFFFFFF00);
		}
	}

	@Override
	public String getDescription() {
		return "Draws each Heart of the Forest perk's current level as a small stack-count badge on its slot.";
	}
}
