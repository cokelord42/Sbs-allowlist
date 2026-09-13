package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

import java.util.List;

/**
 * Per user request ("add scrollable tooltips to the mod so i can scroll down on tooltips. Perfect time to
 * add it since the pf thing will probably require scrolling" — Party Finder's hover lore just grew a lot
 * longer this same round): mouse-wheel scroll support for ANY tooltip, item tooltips and chat hover
 * tooltips (Party Finder's own "Hover for full stats" line) alike.
 *
 * <p>Real single choke point found via {@code javap} on the real (mapped) {@code GuiGraphicsExtractor}
 * class: every tooltip variant — {@code setTooltipForNextFrame} overloads for a plain
 * {@code List<Component>}, an {@code ItemStack}, or a {@code TooltipComponent}, AND the chat hover-text
 * path (confirmed via the sibling {@code GuiGraphicsExtractor.HoveredTextEffects} enum, which gates whether
 * a hovered chat component's tooltip is even allowed) — all funnel into the same real
 * {@code tooltip(Font, List<ClientTooltipComponent>, int, int, ClientTooltipPositioner, Identifier)} method,
 * deferred to run once per frame. {@link com.cokelord.skyblocksimplified.mixin.TooltipScrollMixin} wraps
 * that ENTIRE real method body in a pose translate (HEAD/RETURN injections calling
 * {@link #beginTooltip}/{@link #endTooltip} here) — no scissor needed, since content translated above y=0
 * or past the window's bottom is simply outside the GL viewport and doesn't draw, the same way anything
 * else rendered off-screen behaves. This preserves 100% of vanilla's own real tooltip layout/positioning
 * logic (which this class never touches) and just shifts where the whole thing lands on screen.
 *
 * <p>Scroll input is scoped tightly to avoid stealing a scroll the player meant for something else
 * underneath (hotbar slot selection, a scrollable list behind the cursor, etc.): {@link #handleScroll} only
 * consumes the wheel event when a tooltip taller than the built-in visible cap was ACTUALLY drawn on the
 * immediately preceding game tick (never mid-frame guesswork) — a short, single-line tooltip never captures
 * scroll at all, so this is invisible for the overwhelming majority of hovers.
 */
public class ScrollableTooltipsFeature extends Feature {
	private static ScrollableTooltipsFeature instance;

	// Real px/notch feel, matching this codebase's other UI scroll speeds (see e.g. StorageOverlayFeature's
	// own SCROLL_SPEED) rather than a raw 1:1 wheel-delta mapping, which reads as far too slow for text.
	private static final float SCROLL_SPEED = 14f;
	// How much of a long tooltip must stay visible at the bottom of a full scroll — keeps the last line or
	// two on screen instead of letting the whole tooltip scroll away into empty space.
	private static final int MIN_VISIBLE_TAIL = 40;

	private static float scrollOffset = 0f;
	private static float lastMaxScroll = 0f;
	private static boolean shownThisTick = false;
	private static boolean shownLastTick = false;

	public ScrollableTooltipsFeature() {
		super("scrollable_tooltips", "Scrollable Tooltips", FeatureCategory.INVENTORY, true);
		instance = this;

		// Real scroll-consumption hook: registered globally (no screen-type filter, same "every screen gets
		// this listener, features gate on their own state" pattern this codebase already uses elsewhere,
		// e.g. Storage Overlay's own ScreenEvents.AFTER_INIT registration) since a tooltip can appear over
		// any screen type — not just container screens.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
			ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount) ->
				!handleScroll(verticalAmount)));
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	public void onTick(Minecraft client) {
		// A real game tick passed with no tooltip drawn during it at all (mouse moved off whatever was being
		// hovered) — treat the next tooltip shown as a fresh one and start it unscrolled.
		if (!shownThisTick) {
			scrollOffset = 0f;
			lastMaxScroll = 0f;
		}
		shownLastTick = shownThisTick;
		shownThisTick = false;
	}

	/** Called from {@code TooltipScrollMixin}'s HEAD injection, once per real tooltip draw. */
	public static void beginTooltip(GuiGraphicsExtractor graphics, Font font, List<ClientTooltipComponent> components) {
		shownThisTick = true;
		if (instance == null || !instance.isEnabled()) return;
		int totalHeight = 0;
		for (ClientTooltipComponent c : components) totalHeight += c.getHeight(font) + 2;
		lastMaxScroll = Math.max(0, totalHeight - MIN_VISIBLE_TAIL);
		scrollOffset = Math.max(0f, Math.min(lastMaxScroll, scrollOffset));
		graphics.pose().pushMatrix();
		graphics.pose().translate(0, -scrollOffset);
	}

	/** Called from {@code TooltipScrollMixin}'s RETURN injection — must pop exactly when {@link #beginTooltip}
	 *  pushed, so this re-checks the SAME enabled state rather than assuming it didn't change mid-call (it
	 *  can't, both run synchronously within one real tooltip() invocation, but re-checking costs nothing and
	 *  keeps the push/pop pairing obviously symmetric). */
	public static void endTooltip(GuiGraphicsExtractor graphics) {
		if (instance == null || !instance.isEnabled()) return;
		graphics.pose().popMatrix();
	}

	private static boolean handleScroll(double verticalAmount) {
		if (instance == null || !instance.isEnabled() || !shownLastTick || lastMaxScroll <= 0f) return false;
		scrollOffset = Math.max(0f, Math.min(lastMaxScroll, scrollOffset - (float) verticalAmount * SCROLL_SPEED));
		return true;
	}

	@Override
	public String getDescription() {
		return "Lets you scroll long item tooltips with the mouse wheel instead of them running off the bottom of the screen.";
	}
}
