package com.cokelord.skyblocksimplified.dungeon;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import java.util.regex.Pattern;

/**
 * Real, 1:1 port of Odin's own {@code LocationUtils.lobbyId} tracking (user-supplied Odin 0.3.2 source) —
 * the short per-lobby code Hypixel stamps into a scoreboard team's real prefix/suffix text (e.g.
 * "09/04/25 mega42"), used to scope the Melody Message WebSocket relay to players who are actually in the
 * same real Hypixel lobby. Fed by {@link com.cokelord.skyblocksimplified.mixin.SetPlayerTeamPacketMixin}
 * since Fabric API has no stock event for {@code ClientboundSetPlayerTeamPacket}.
 */
public final class LobbyIdTracker {
	// Real regex confirmed from Odin's own LocationUtils.kt.
	private static final Pattern LOBBY_REGEX = Pattern.compile("\\d\\d/\\d\\d/\\d\\d (\\w{0,6}) *");
	private static final Pattern CONTROL_CODES = Pattern.compile("§.");

	private static String lobbyId;
	private static boolean registered = false;

	private LobbyIdTracker() {}

	public static String getLobbyId() { return lobbyId; }

	public static synchronized void ensureRegistered() {
		if (registered) return;
		registered = true;
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> lobbyId = null);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> lobbyId = null);
	}

	/** Called by {@link com.cokelord.skyblocksimplified.mixin.SetPlayerTeamPacketMixin} for every real
	 *  team-prefix/suffix update the server sends. */
	public static void onTeamPrefixSuffix(String rawText) {
		String plain = CONTROL_CODES.matcher(rawText).replaceAll("");
		var matcher = LOBBY_REGEX.matcher(plain);
		if (matcher.find()) lobbyId = matcher.group(1);
	}
}
