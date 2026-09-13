package com.cokelord.skyblocksimplified.highlight;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Central registry of "cancel the real vanilla container screen's own render (slots/background/tooltip),
 * a custom overlay is replacing it" rules — mirrors {@link EntityHideRegistry}'s shape, but for whole
 * screens. Consumed by {@code TerminalCustomGuiRenderMixin}/{@code TerminalCustomGuiBackgroundMixin} (see
 * their own doc comments for exactly which vanilla draw calls this cancels and why two separate mixins are
 * needed) alongside {@code TerminalSolverFeature}'s own hard-wired check, so any other feature that wants
 * this same "draw my own custom panel, hide the real GUI underneath it" behavior — e.g. Storage Overlay, per
 * user request ("hide the gui in the background... I know this is possible from the terminal solver, we
 * have done it before") — can register its own predicate here instead of every such feature needing its own
 * pair of mixins.
 */
public final class ScreenRenderCancelRegistry {
	private ScreenRenderCancelRegistry() {}

	private static final Map<String, Predicate<AbstractContainerScreen<?>>> rules = new LinkedHashMap<>();

	public static void setRule(String id, Predicate<AbstractContainerScreen<?>> matcher) {
		rules.put(id, matcher);
	}

	public static void clearRule(String id) {
		rules.remove(id);
	}

	public static boolean shouldCancel(AbstractContainerScreen<?> screen) {
		for (Predicate<AbstractContainerScreen<?>> matcher : rules.values()) {
			if (matcher.test(screen)) return true;
		}
		return false;
	}
}
