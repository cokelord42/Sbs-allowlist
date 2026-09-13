package com.cokelord.skyblocksimplified.util;

import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

/**
 * Bridge to Hypixel's own official Mod API — a real, server-pushed packet giving the exact current island
 * with no sidebar scraping at all, confirmed (by reading SkyHanni's real source) as its own fully
 * authoritative source for island detection, immune to the sidebar-rebuild flicker that kept defeating
 * every timeout/hysteresis fix attempted in IslandGate. IslandGate is still the only place other code
 * should call into — this class exists purely to isolate every reference to Hypixel Mod API types in one
 * place.
 *
 * <p>The API classes this compiles against (net.hypixel:mod-api) don't by themselves put anything on the
 * wire — that requires the separate, optional "Hypixel Mod API" Fabric mod (id "hypixel-mod-api") actually
 * being installed alongside this one. Deliberately isolated into its own class for exactly that reason:
 * IslandGate only ever calls {@link #start()} after confirming via FabricLoader that mod is present, and
 * because Java only verifies/links a class's own referenced types the first time that class is actively
 * used (not at JVM startup), a player who hasn't installed the companion mod never triggers class-loading
 * of these types at all — this project's own island detection just falls back to its existing sidebar-
 * based approach untouched, no crash, no missing-class error.
 */
public final class HypixelLocationApi {
	private HypixelLocationApi() {}

	private static volatile String currentIslandMode = null;
	private static boolean started = false;

	/** Idempotent. Only ever called after the caller has already confirmed the "hypixel-mod-api" Fabric
	 *  mod is loaded — see IslandGate. */
	public static void start() {
		if (started) return;
		started = true;
		HypixelModAPI.getInstance().subscribeToEventPacket(ClientboundLocationPacket.class);
		HypixelModAPI.getInstance().createHandler(ClientboundLocationPacket.class, packet ->
			currentIslandMode = packet.getMode().orElse(null));
	}

	/** Hypixel's own real API id for the current island (confirmed real values, from SkyHanni's own
	 *  IslandType.kt: "garden", "dungeon", "kuudra", "crimson_isle", "hub", etc.) — or null if no location
	 *  packet has arrived yet this session (e.g. in the brief window right after connecting, before
	 *  Hypixel sends the first update). */
	public static String currentIslandMode() {
		return currentIslandMode;
	}

	/** Clears the confirmed mode on disconnect — see IslandGate.register() — so a stale island from a
	 *  previous Hypixel session can't briefly read as "confirmed" again before the next real location
	 *  packet arrives on reconnect. */
	public static void reset() {
		currentIslandMode = null;
	}
}
