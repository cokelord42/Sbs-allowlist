package com.cokelord.skyblocksimplified.gui;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Should the hovered slot's tooltip be suppressed entirely" rules, consumed by
 * AbstractContainerScreenTooltipMixin right before extractTooltip would otherwise run. Separate from
 * PreTooltipRenderRegistry (which draws underneath a tooltip that's still going to show) — this one is for
 * "there must be no tooltip at all", e.g. Terminal Solver's "Only Show Correct Clicks" blackout mode, where
 * a real item's name/lore showing on hover would give away exactly what's supposed to be hidden.
 */
public final class TooltipSuppressRegistry {
	private TooltipSuppressRegistry() {}

	@FunctionalInterface
	public interface Rule {
		boolean shouldSuppress(AbstractContainerScreen<?> screen, int mouseX, int mouseY);
	}

	private static final Map<String, Rule> rules = new LinkedHashMap<>();

	public static void setRule(String id, Rule rule) {
		rules.put(id, rule);
	}

	public static void clearRule(String id) {
		rules.remove(id);
	}

	public static boolean shouldSuppress(AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
		for (Rule rule : rules.values()) {
			if (rule.shouldSuppress(screen, mouseX, mouseY)) return true;
		}
		return false;
	}
}
