package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.combat.ChestValueEstimator;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Estimates the value of a garden visitor's reward offer, highlights the Accept button, and — when the
 * offer is a genuinely RARE reward — blocks the Refuse Offer button (unless a configurable bypass key is
 * held) with a real barrier-texture overlay, so a rare drop can't be accidentally refused. Ported from
 * SkyHanni's VisitorRewardWarning.kt/VisitorReward.kt/VisitorApi.kt (real confirmed slot numbers:
 * INFO_SLOT=13, ACCEPT_SLOT=29, REFUSE_SLOT=33; isVisitorInfo: a 4-line info item whose last line starts
 * with "Offers Accepted:") — matched here as a plain substring against the accept slot's own name AND
 * lore.
 *
 * <p>Per user report ("considers everything a rare reward, including Fine Flour"): a previous round added
 * a second, independent "accept slot uses green terracotta" material check based on one specific user
 * report, but that turned out to be Hypixel's generic accept-button coloring, not a rarity signal — EVERY
 * visitor offer's accept item uses it regardless of value, which is exactly why everything falsely
 * qualified. Removed entirely; name-matching against the list below is the only signal now. That list is
 * also deliberately much smaller than SkyHanni's own 30-entry VisitorReward enum now, per explicit user
 * request — they only want items they'd actually be upset about misclicking away (worth roughly 1M+
 * coins), not SkyHanni's full "every non-guaranteed drop" catalog. Trimmed to exactly the items the user
 * named; ask before adding more rather than guessing at current market values.
 */
public class RareRewardWarningFeature extends Feature {
	private static final int INFO_SLOT = 13;
	private static final int ACCEPT_SLOT = 29;
	private static final int REFUSE_SLOT = 33;
	private static final int SLOT_SIZE = 16;
	private static final int DEFAULT_COLOR = 0x8000FF00;
	// Plain text prefix, no literal §-codes — Component#getString() already strips all formatting, so the
	// original "§7Offers Accepted: §a\d+" full-match pattern could never match, meaning isVisitorInfo()
	// never returned true and NOTHING in this feature ever rendered ("nothing is rendering" per user
	// report). VisitorShoppingListFeature's own doc comment already documents this exact fix; this class
	// just never got the same update when it was reworked this round.
	private static final String OFFERS_ACCEPTED_PREFIX = "Offers Accepted: ";

	// User-curated list of visitor rewards worth ~1M+ coins — see class doc for why this is much shorter
	// than SkyHanni's own catalog. "Dedication" (no roman-numeral tier) still catches every level of the
	// enchant book, same reasoning the old list already used for tiered items.
	private static final String[] RARE_REWARD_NAMES = {
		"Overgrown Grass", "Green Bandana", "Dedication",
	};

	private int color = DEFAULT_COLOR;
	private final KeyCombo bypassCombo = new KeyCombo();
	private boolean registered = false;

	public RareRewardWarningFeature() {
		super("visitor_reward_warning", "Rare Reward Warning", FeatureCategory.FARMING, false);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (!isEnabled()) return;
				// Per user report ("the barrier and green block highlight are rendering over lore") — this
				// used to always draw via afterExtract, which fires AFTER the vanilla tooltip, painting over
				// it. Skipped here whenever a tooltip is about to show; the PreTooltipRenderRegistry listener
				// below (registered once, globally) handles that exact case by drawing underneath instead —
				// same fix already applied to Personal Compactor Overlay/Quick Action Buttons this session.
				if (isTooltipAboutToShow(containerScreen)) return;
				renderIfVisitorOffer(graphics, containerScreen);
			});
		});
		com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled()) return;
			if (!isTooltipAboutToShow(screen)) return;
			renderIfVisitorOffer(graphics, screen);
		});
	}

	private boolean isTooltipAboutToShow(AbstractContainerScreen<?> screen) {
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		Slot hovered = accessor.skyblocksimplified$getHoveredSlot();
		return hovered != null && !hovered.getItem().isEmpty();
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			ContainerClickRegistry.setRule(getId(), this::shouldCancelClick);
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			ContainerClickRegistry.clearRule(getId());
			registered = false;
		}
	}

	@Override
	public String getSubcategory() {
		return "Visitors";
	}

	public int getColor() {
		return color;
	}

	public void setColor(int color) {
		this.color = color;
	}

	@Override
	public Integer getPersistedColor() {
		return color;
	}

	@Override
	public void loadPersistedColor(int argb) {
		this.color = argb;
	}

	@Override
	public KeyCombo getPrimaryKeyCombo() {
		return bypassCombo;
	}

	private boolean shouldCancelClick(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		if (slotId != REFUSE_SLOT) return false;
		if (!bypassCombo.isEmpty() && bypassCombo.isHeld()) return false;
		Minecraft mc = Minecraft.getInstance();
		return mc.gui.screen() instanceof AbstractContainerScreen<?> screen && isRareOfferShowing(screen);
	}

	private void renderIfVisitorOffer(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		AbstractContainerMenu menu = screen.getMenu();
		if (menu.slots.size() <= REFUSE_SLOT) return;

		ItemStack infoStack = menu.getSlot(INFO_SLOT).getItem();
		if (!isVisitorInfo(infoStack)) return;
		ItemStack acceptStack = menu.getSlot(ACCEPT_SLOT).getItem();
		if (!"Accept Offer".equals(acceptStack.getHoverName().getString())) return;

		double estimatedValue = ChestValueEstimator.estimate(acceptStack, false);

		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();
		Slot acceptSlot = menu.getSlot(ACCEPT_SLOT);
		int x = leftPos + acceptSlot.x;
		int y = topPos + acceptSlot.y;
		RenderUtil.fillRounded(graphics, x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, 2, color);

		Font font = Minecraft.getInstance().font;
		// Per user request ("Visitors profit shows 0 profit... I want it to hide if theres no profit"): a
		// value of 0 just means nothing here resolved to a real price (an unrecognized item, or the offer
		// genuinely being coins-only with none captured) — not useful information either way, so the line
		// no longer renders at all rather than showing a bare, misleading "0".
		if (estimatedValue > 0) {
			String text = "§6Reward value§7: §e" + String.format(Locale.ROOT, "%,.0f", estimatedValue);
			graphics.text(font, Component.literal(text), leftPos, topPos - 12, 0xFFFFFFFF);
		}

		if (isRareReward(acceptStack)) {
			String rareText = (bypassCombo.isEmpty() || !bypassCombo.isHeld())
				? "§d§lRARE REWARD! §7(hold bypass key to refuse)"
				: "§d§lRARE REWARD!";
			graphics.text(font, Component.literal(rareText), leftPos, topPos - 22, 0xFFFFFFFF);

			Slot refuseSlot = menu.getSlot(REFUSE_SLOT);
			int rx = leftPos + refuseSlot.x;
			int ry = topPos + refuseSlot.y;
			if (bypassCombo.isEmpty() || !bypassCombo.isHeld()) {
				// Real vanilla barrier icon, not a colored square — an unmistakable "this is locked" signal,
				// per user request, drawn straight over the Refuse Offer slot's own item.
				graphics.item(new ItemStack(Items.BARRIER), rx, ry);
			} else {
				RenderUtil.fillRounded(graphics, rx - 1, ry - 1, rx + SLOT_SIZE + 1, ry + SLOT_SIZE + 1, 2, 0x60FF5555);
			}
		}
	}

	private boolean isRareOfferShowing(AbstractContainerScreen<?> screen) {
		AbstractContainerMenu menu = screen.getMenu();
		if (menu.slots.size() <= REFUSE_SLOT) return false;
		ItemStack infoStack = menu.getSlot(INFO_SLOT).getItem();
		if (!isVisitorInfo(infoStack)) return false;
		return isRareReward(menu.getSlot(ACCEPT_SLOT).getItem());
	}

	private boolean isRareReward(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (containsAnyRareName(stack.getHoverName().getString().replaceAll("§.", ""))) return true;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) {
				if (containsAnyRareName(line.getString().replaceAll("§.", ""))) return true;
			}
		}
		return false;
	}

	private boolean containsAnyRareName(String text) {
		for (String rare : RARE_REWARD_NAMES) {
			if (text.contains(rare)) return true;
		}
		return false;
	}

	private boolean isVisitorInfo(ItemStack stack) {
		if (stack.isEmpty()) return false;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return false;
		List<Component> lines = lore.lines();
		if (lines.size() != 4) return false;
		return lines.get(3).getString().startsWith(OFFERS_ACCEPTED_PREFIX);
	}

	@Override
	public com.google.gson.JsonElement savePersistedData() {
		com.google.gson.JsonArray comboArr = new com.google.gson.JsonArray();
		for (String s : bypassCombo.serialize()) comboArr.add(new com.google.gson.JsonPrimitive(s));
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
		obj.add("combo", comboArr);
		return obj;
	}

	@Override
	public void loadPersistedData(com.google.gson.JsonElement data) {
		if (!(data instanceof com.google.gson.JsonObject obj)) return;
		if (obj.has("combo") && obj.get("combo").isJsonArray()) {
			java.util.List<String> serialized = new java.util.ArrayList<>();
			for (com.google.gson.JsonElement e : obj.getAsJsonArray("combo")) serialized.add(e.getAsString());
			bypassCombo.setKeys(KeyCombo.deserialize(serialized).getKeys());
		}
	}

	@Override
	public String getDescription() {
		return "Estimates the value of a garden visitor's reward offer and warns you (blocking the Refuse button) before you decline a genuinely rare one.";
	}
}
