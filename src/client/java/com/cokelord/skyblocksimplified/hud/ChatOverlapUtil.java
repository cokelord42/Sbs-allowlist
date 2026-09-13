package com.cokelord.skyblocksimplified.hud;

import com.cokelord.skyblocksimplified.dungeon.TerminalTracker;
import com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Our own HUD overlays used to hide themselves any time {@code mc.gui.screen() != null}, which also
 * covers the vanilla chat box being open (typing a message opens a real {@link ChatScreen}) — so every
 * widget vanished the moment the player pressed T/Enter, even ones nowhere near the chat box itself.
 * Chat only occupies a small region at the bottom-left, so a widget should only hide for it if the two
 * actually overlap; any other real screen (inventory, our own mod menu, etc.) still hides everything.
 */
public final class ChatOverlapUtil {
	private ChatOverlapUtil() {}

	/** True when a real screen other than chat is open — should hide any bare HUD overlay entirely.
	 *  Round 2 (per task tracker #594 — "Show other GUI elements during Terminal Solver custom GUI"):
	 *  Terminal Solver's Custom Terminal GUI mode ({@link TerminalSolverFeature#shouldReplaceRender})
	 *  cancels the real vanilla terminal screen's own content and draws its own small centered panel
	 *  instead — the live game world (and everything on it) stays fully visible everywhere outside that
	 *  panel, unlike a real inventory/chest screen. Bare HUD overlays gated on this method (cooldown
	 *  timers, highlight boxes, the Blaze/Quiz puzzle solvers) were still treating it as a normal
	 *  screen-open state and hiding themselves anyway, even though there's no vanilla GUI backdrop left
	 *  underneath them to conflict with — same exception shape as the ChatScreen one above.
	 *  Round 3 (per user report — "all the gui elements dont show when a terminal is open"): this
	 *  exception only ever covered Custom Terminal GUI's replace-render mode, but almost every other HUD
	 *  feature (Melody Display, Dungeon Notifications, Splits, timers, etc.) never used this helper at
	 *  all — they each had their own copy of the raw {@code screen() != null} check, so they stayed
	 *  hidden through ANY terminal, custom-GUI mode or not. Widened the carve-out here to cover a plain
	 *  vanilla terminal screen too ({@link TerminalTracker#ensureDetected}) — the vanilla chest-slot grid
	 *  is still drawn in that case, but our own widgets are all corner/edge-anchored HUD overlays that
	 *  don't visually sit on top of the terminal's own panel, so leaving them visible is safe — and every
	 *  feature listed above was switched from the raw check to this helper. */
	public static boolean isBlockingScreen() {
		var screen = Minecraft.getInstance().gui.screen();
		if (screen == null || screen instanceof ChatScreen) return false;
		if (screen instanceof AbstractContainerScreen<?> containerScreen
			&& (TerminalSolverFeature.shouldReplaceRender(containerScreen) || TerminalTracker.ensureDetected(containerScreen) != null)) return false;
		return true;
	}

	/** True when chat is currently open AND the given widget rect overlaps the vanilla chat box's rect
	 *  (bottom-left corner, sized by the player's chat width/height options). */
	public static boolean isCoveredByChat(int x0, int y0, int x1, int y1) {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.gui.screen() instanceof ChatScreen)) return false;
		// ChatComponent doesn't expose an instance getter off Gui in this version, but its pixel
		// width/height are pure functions of the player's chat-width/chat-height-focused sliders (the
		// same static helpers ChatComponent itself calls internally) — no instance needed.
		int chatWidth = ChatComponent.getWidth(mc.options.chatWidth().get());
		int chatHeight = ChatComponent.getHeight(mc.options.chatHeightFocused().get());
		int guiHeight = mc.getWindow().getGuiScaledHeight();
		int chatX0 = 2;
		int chatX1 = chatX0 + chatWidth;
		int chatY1 = guiHeight - 40;
		int chatY0 = chatY1 - chatHeight;
		return x0 < chatX1 && x1 > chatX0 && y0 < chatY1 && y1 > chatY0;
	}
}
