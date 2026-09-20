package com.cokelord.skyblocksimplified.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Listeners fired from AbstractContainerScreenSlotsMixin, right before vanilla draws any slot/item icon —
 * the one point in a container screen's render pass where a highlight can draw UNDER the item it's
 * highlighting instead of on top of it. Per user request ("change all item highlights... to render BEHIND
 * the item they are highlighting"): {@link PreTooltipRenderRegistry} fires AFTER every item icon is already
 * drawn (by design, so it can draw under the TOOLTIP), which is exactly backwards for a highlight meant to
 * frame an item rather than obscure it — a big fillRounded box behind a 16x16 item icon reads as a colored
 * background/frame, while the same box in FRONT reads as a colored film painted over the item's own texture.
 *
 * <p>Fires from inside {@code AbstractContainerScreen.extractContents}'s already-translated
 * {@code pose().translate(leftPos, topPos)} block (confirmed via decompiling the real
 * extractContents/extractSlots call chain), so — unlike {@link PreTooltipRenderRegistry}'s listeners, which
 * fire after that block has been popped and so add {@code leftPos}/{@code topPos} to every slot coordinate
 * themselves — listeners here draw using a {@link net.minecraft.world.inventory.Slot}'s own raw
 * {@code slot.x}/{@code slot.y} directly, with no manual offset needed.
 */
public final class PreItemRenderRegistry {
	private PreItemRenderRegistry() {}

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
