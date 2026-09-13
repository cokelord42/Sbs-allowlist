package com.cokelord.skyblocksimplified.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Listeners fired from AbstractContainerScreenTooltipMixin, right before vanilla draws the hovered slot's
 * tooltip — the one point in a container screen's render pass that's after every slot/item icon has been
 * drawn but before the tooltip itself. Any slot-highlight overlay registered here draws underneath the
 * tooltip by construction, instead of the old workaround of just skipping whichever slot the mouse
 * currently hovers (which only avoided covering that ONE slot's own tooltip — a highlight on any OTHER
 * slot the tooltip visually extended over still drew on top of it). Mirrors ParticleFilterRegistry's/
 * EntityHideRegistry's "shared choke point, many independent listeners" shape.
 */
public final class PreTooltipRenderRegistry {
	private PreTooltipRenderRegistry() {}

	public interface Listener {
		void render(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int mouseX, int mouseY);
	}

	private static final List<Listener> listeners = new ArrayList<>();

	public static void addListener(Listener listener) {
		listeners.add(listener);
	}

	public static void fire(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
		for (Listener listener : listeners) {
			listener.render(graphics, screen, mouseX, mouseY);
		}
	}
}
