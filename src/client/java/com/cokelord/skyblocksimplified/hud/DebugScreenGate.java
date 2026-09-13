package com.cokelord.skyblocksimplified.hud;

import net.minecraft.client.Minecraft;

/**
 * Per user request ("make using the minecraft f3 menu hide all gui elements behind it"): a shared check
 * for the real vanilla F3 debug overlay — it isn't a {@link net.minecraft.client.gui.screens.Screen} (see
 * {@link ChatOverlapUtil}'s own doc comment for that same distinction with chat), so none of this mod's
 * existing "hide while a screen is open" checks ever caught it; its dense text blocks in the top corners
 * are exactly where several of this mod's own HUD widgets like to sit by default.
 */
public final class DebugScreenGate {
	private DebugScreenGate() {}

	public static boolean isOpen() {
		return Minecraft.getInstance().getDebugOverlay().showDebugScreen();
	}
}
