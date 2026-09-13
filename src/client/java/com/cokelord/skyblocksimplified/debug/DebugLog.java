package com.cokelord.skyblocksimplified.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Fire-and-forget chat logging for troubleshooting feature detection/rendering.
 *
 * <p>Per user request ("delete the DEBUG module since everything fully works now and nothing needs
 * debugging"): the owner-only Debug module toggle that used to gate this has been removed entirely — this
 * is now a permanent no-op. Kept in place (rather than ripping out every call site across the several
 * features that still call it) since removing the toggle already achieves the goal — nothing prints
 * regardless of what any call site passes — and every one of those call sites is otherwise unrelated,
 * already-working feature logic that doesn't need touching just to delete some dead debug output.
 */
public final class DebugLog {
	private DebugLog() {}

	private static final java.util.Map<String, Long> lastLoggedAt = new java.util.concurrent.ConcurrentHashMap<>();

	/** Same as log()/rendered(), but skips if the same key was already logged within intervalMillis — for
	 *  call sites that fire every frame/tick for the same ongoing condition (e.g. an entity that stays
	 *  glowing for several seconds straight), where logging every single frame just floods chat with the
	 *  same line repeated dozens of times a second instead of once per real event. */
	public static void throttled(String key, long intervalMillis, String message) {
		long now = System.currentTimeMillis();
		Long last = lastLoggedAt.get(key);
		if (last != null && now - last < intervalMillis) return;
		lastLoggedAt.put(key, now);
		rendered(message);
	}

	/** Detection-side event, e.g. "detected superpairs!" — prefixed/colored to distinguish from render logs. */
	public static void detected(String message) {
		log("§b[Debug] §7detect: §f" + message);
	}

	/** Render/display-side event, e.g. "Slayer boss highlight rendering!" */
	public static void rendered(String message) {
		log("§b[Debug] §7render: §f" + message);
	}

	public static void log(String message) {
		if (true) return;
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		// LocalPlayer#sendSystemMessage() round-trips through ChatListener.handleSystemMessage(), which
		// re-fires ClientReceiveMessageEvents.GAME — the exact same event every chat-reading feature in
		// this project listens on. Any feature that calls DebugLog on a line matching its own trigger
		// condition (e.g. "unmatched pest-related chat line: ...Pest...") would see its own debug output
		// come back through as a new "message", log again, and recurse forever — this crashed the game
		// with a StackOverflowError during a real farming session. Hud#getChat().addClientSystemMessage()
		// appends straight to the chat display instead, bypassing ChatListener/the event system entirely.
		client.gui.hud.getChat().addClientSystemMessage(Component.literal(message.startsWith("§") ? message : "§b[Debug] §f" + message));
	}
}
