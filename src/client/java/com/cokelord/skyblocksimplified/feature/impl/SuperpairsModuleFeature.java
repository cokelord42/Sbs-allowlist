package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.ExperimentationTableApi;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.inventory.SlotItemOverrideRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Restored a second time (see git history — shipped, deleted alongside the old Ultra-Rare Book Alert half
 * of this same module, now rebuilt as just this one half, per task tracker #598 — "Restore Superpairs
 * module as 'Keep Superpairs Items Visible'"). Originally ported from SkyHanni's
 * SuperPairsItemVisibility.kt.
 *
 * <p>Hypixel re-hides a matched-but-not-yet-cleared pair behind its "?" placeholder between rounds,
 * forcing you to re-memorize slots you already uncovered. This caches the last real item shown in each
 * slot and, via {@link SlotItemOverrideRegistry}, substitutes it back in whenever that slot's current item
 * is the placeholder text again — a live scan every frame rather than SkyHanni's click-then-confirm-on-
 * next-update sequence, since this project doesn't have its own click-event plumbing hooked up for
 * observation (only for the cancellable veto {@code ContainerClickRegistry} provides), and a real item is
 * either already showing (nothing to do) or still hidden (nothing cached yet either way).
 */
public class SuperpairsModuleFeature extends Feature {
	// Plain text, no literal §-code requirement — matched against Component#getString(), which already
	// strips all formatting.
	private static final Pattern UNKNOWN_CLICK_PATTERN = Pattern.compile(
		"\\?|(?:Click a(?: seco)?n[dy]|Next) button(?: is instantly rewarded)?!?");

	private final Map<Integer, ItemStack> knownSlotItems = new HashMap<>();

	public SuperpairsModuleFeature() {
		super("superpairs_module", "Keep Superpairs Items Visible", FeatureCategory.ENCHANTING, false);
		ExperimentationTableApi.start();

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> {
				if (!isEnabled() || !ExperimentationTableApi.inSuperpairs()) return;
				scanSlots(containerScreen.getMenu());
			});
			ScreenEvents.remove(screen).register(s -> {
				if (ExperimentationTableApi.inSuperpairs()) knownSlotItems.clear();
			});
		});
	}

	// Real bug found (per an audit for the "Remember Last Page doesn't fully work" report): this used to
	// override getSubcategory() to return "Misc" — not a valid subcategory of FeatureCategory.ENCHANTING at
	// all (that category defines no subcategory tabs, same as its only other member, ExperimentAddonsFeature,
	// which correctly doesn't override this). A (category, subcategory) pair Remember Last Page saves for
	// this feature's page could never be validly restored, and it likely couldn't be reliably reached by
	// normal browsing either. Removed the override entirely so this defaults to null like its sibling.

	@Override
	protected void onEnable() {
		SlotItemOverrideRegistry.set("superpairs", this::overrideItem);
	}

	@Override
	protected void onDisable() {
		SlotItemOverrideRegistry.clear("superpairs");
	}

	private void scanSlots(AbstractContainerMenu menu) {
		for (Slot slot : menu.slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = stack.getHoverName().getString();
			if (UNKNOWN_CLICK_PATTERN.matcher(name).matches()) continue;
			knownSlotItems.put(slot.index, stack.copy());
		}
	}

	private ItemStack overrideItem(Slot slot, ItemStack real) {
		if (!isEnabled() || !ExperimentationTableApi.inSuperpairs()) return real;
		if (real.isEmpty()) return real;
		if (!UNKNOWN_CLICK_PATTERN.matcher(real.getHoverName().getString()).matches()) return real;
		ItemStack cached = knownSlotItems.get(slot.index);
		return cached != null ? cached : real;
	}

	@Override
	public String getDescription() {
		return "Keeps Superpairs card items visible/rendering normally instead of Hypixel's own obfuscated display.";
	}
}
