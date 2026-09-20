package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.foraging.HotfPerkApi;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Highlights each perk in the Heart of the Forest GUI green (enabled), red (unlocked but disabled), or
 * gray (locked) — ported from SkyHanni's HotxFeatures.onBackgroundDrawn, scoped to HotF only (SkyHanni
 * shares this with Heart of the Mountain too, which this project doesn't have a mining-perk-tree
 * counterpart for yet). The enabled/green color is user-configurable like any other highlight; disabled
 * and locked stay fixed (they're status colors, not a stylistic choice).
 */
public class ActiveHotfPerksHighlightFeature extends Feature {
	private static final int SLOT_SIZE = 16;
	// Halved from the original 0x40/0x80/0x80 alphas — "really bright" per user report — and every
	// highlight now skips whichever slot the mouse is currently over (see renderHighlights) so it
	// doesn't paint across a slot's own hover tooltip.
	private static final int DEFAULT_ENABLED_COLOR = 0x3000FF00;
	private static final int DISABLED_COLOR = 0x50FF0000;
	private static final int LOCKED_COLOR = 0x50404040;

	private int enabledColor = DEFAULT_ENABLED_COLOR;

	public ActiveHotfPerksHighlightFeature() {
		super("hotf_enable_highlight", "Active HOTF Perks Highlight", FeatureCategory.FORAGING, false);
		// Drawn from PreItemRenderRegistry (fires right before item icons are drawn, so this highlight sits
		// UNDER the perk icon instead of painted over it — per user request, "change all item highlights...
		// to render BEHIND the item they are highlighting"). Previously PreTooltipRenderRegistry (fires
		// after every item icon, right before the hovered slot's tooltip — correctly under the tooltip, but
		// still on top of the items themselves).
		com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled()) return;
			if (!screen.getTitle().getString().contains(HotfPerkApi.INVENTORY_TITLE)) return;
			renderHighlights(graphics, screen, mouseX, mouseY);
		});
	}

	@Override
	public String getSubcategory() {
		return "HOTF";
	}

	public int getEnabledColor() {
		return enabledColor;
	}

	public void setEnabledColor(int color) {
		this.enabledColor = color;
	}

	@Override
	public Integer getPersistedColor() {
		return enabledColor;
	}

	@Override
	public void loadPersistedColor(int argb) {
		this.enabledColor = argb;
	}

	private void renderHighlights(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		AbstractContainerMenu menu = screen.getMenu();
		var playerInventory = mc.player.getInventory();

		for (Slot slot : menu.slots) {
			if (slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;

			HotfPerkApi.PerkState state = HotfPerkApi.determineState(stack);
			if (state == null) continue;
			// PreItemRenderRegistry fires from inside AbstractContainerScreen's own already-translated
			// pose().translate(leftPos, topPos) block — slot.x/slot.y are already the right coordinates,
			// no manual leftPos/topPos offset needed (see that registry's own doc comment).
			int x = slot.x;
			int y = slot.y;
			int color = switch (state) {
				case ENABLED -> enabledColor;
				case DISABLED -> DISABLED_COLOR;
				case LOCKED -> LOCKED_COLOR;
			};
			RenderUtil.fillRounded(graphics, x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, 2, color);
		}
	}

	@Override
	public String getDescription() {
		return "Highlights each Heart of the Forest perk green if it's active, red if it's unlocked but turned off, or gray if it's still locked.";
	}
}
