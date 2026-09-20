package com.cokelord.skyblocksimplified.util;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.hud.ScoreboardReader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight "what island/area is the player currently in" check. Prefers Hypixel's own official Mod API
 * (a real, server-pushed packet giving the exact current island — see HypixelLocationApi) when the separate,
 * optional "Hypixel Mod API" Fabric mod is installed; falls back to scanning the sidebar scoreboard
 * (Hypixel always shows the current area near the top of it — confirmed pattern, same one
 * InstanceChestAPI's runNameCroesus regex uses: ".*Catacombs - Flo.*") when it isn't. Used to gate
 * island-specific features (dungeon-only detection, farming-only overlays, etc.) so they don't fire/render
 * on unrelated islands like the main hub.
 *
 * <p>Per repeated user report, clearing pests on the Garden made every garden-gated farming HUD widget
 * vanish — Hypixel's own sidebar visibly disrupts itself around a pest-kill event, and a raw per-frame scan
 * has no way to tell "the island line is gone because the scoreboard is mid-rebuild" apart from "the island
 * line is gone because I actually left". This went through two rounds of sidebar-only fixes (a grace-period
 * timeout, widened once, then a "sticky positive, never cleared by absence" redesign) and the flicker still
 * kept recurring — confirming a pure sidebar-text approach fundamentally can't fully solve this, since
 * Hypixel controls how long and how thoroughly it disrupts the sidebar body, not this project.
 *
 * <p>Hypixel's Mod API sidesteps the whole problem: {@code ClientboundLocationPacket} is pushed by the
 * server itself on real location changes only, never touched by a sidebar rebuild, so there's nothing to
 * debounce at all. This requires the player to have the separate "Hypixel Mod API" Fabric mod installed
 * (confirmed to have a build for this exact Minecraft version) — when it's present, its confirmed value is
 * used directly; when it's absent (or hasn't sent a location packet yet, e.g. in the first moment after
 * connecting), this falls back to the sticky-positive sidebar approach from the previous round, which is
 * still a real, working fallback on its own.
 */
public final class IslandGate {
	private IslandGate() {}

	// Perf finding: String.replaceAll(String, String) always compiles a fresh Pattern internally on every
	// single call, since it only ever takes a regex STRING, never a pre-compiled Pattern. isOnSkyblock() and
	// checkArea() below strip color codes off every sidebar line on every call — and since this whole class
	// is the one thing basically every "am I in a dungeon/kuudra/garden/..." check across the mod funnels
	// through every tick, that was a fresh regex compile per sidebar line, many times a second, for a regex
	// that never actually changes. Cached here once instead — identical output, just not rebuilt from source
	// every call.
	private static final Pattern COLOR_CODE = Pattern.compile("§.");

	// The one currently-confirmed tracked area key ("dungeon"/"kuudra"/"garden"/"crimson"/"burning_desert"),
	// or null if none has been confirmed (yet, or since the last hub visit/level change) — sidebar-fallback
	// state only; whenever the Mod API bridge has a confirmed value, that's used instead and this is unused.
	private static String currentArea = null;
	// Minecraft creates a fresh ClientLevel instance on every world/server (re)join — used as a cheap,
	// reliable "did we just connect somewhere new" signal to hard-reset this cache, so a stale "still in
	// Garden" reading can never leak from one session/server into a completely different one.
	private static Object lastLevel = null;

	private static Boolean modApiActive = null;

	/** Hard-resets the sticky area cache the instant the player disconnects, rather than waiting for the
	 *  next tick's sidebar read to notice (readCurrentSidebarLines already returns empty once mc.level is
	 *  null, so checkArea's own empty-sidebar clear would eventually catch this too, but only once
	 *  something calls it again — this closes the gap immediately instead of leaving a one-tick, or longer
	 *  if nothing polls that tick, window where a just-disconnected HUD element still reads as gated-true). */
	public static void register() {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			currentArea = null;
			lastLevel = null;
			modApiActive = null;
			HypixelLocationApi.reset();
		});
	}

	/** Real bug found (per user report — "the mod seemed to think i was in dungeons for like 30 seconds"
	 *  and, after that report kept recurring across several rounds, "can't we just use hypixel-mod-api?"):
	 *  the previous version of this method trusted only a short "known NOT a dungeon" allowlist (hub,
	 *  garden, kuudra, crimson_isle, foraging_2) and ASSUMED any other unrecognized Mod API mode value was
	 *  "probably a dungeon" by exclusion — meaning every real island Hypixel actually reports that wasn't on
	 *  that short list (Private Island, Dark Auction, Winter/Jerry's Workshop, the Farming Islands, Gold
	 *  Mine, Deep Caverns, Dwarven Mines, Crystal Hollows, Mineshaft, Backwater Bayou, Lotus Atoll, The Park,
	 *  Torrhus Canyon, Spider's Den, The End, The Rift, Critter Safari, and even Dungeon Hub itself) got
	 *  silently misread as "in a dungeon" even with the Mod API working perfectly. Confirmed real, complete
	 *  mode-string list from SkyHanni's own {@code IslandType.kt} (in scratchpad, cross-checked): the ONLY
	 *  real mode value Catacombs (including the boss fight, which is still part of the same Catacombs
	 *  island) ever reports is the literal {@code "dungeon"} string — Dungeon Hub is its own distinct
	 *  {@code "dungeon_hub"} value, not the same as an active run, contrary to this file's own earlier
	 *  (incorrect) assumption. A confirmed Mod API mode is now trusted as an exact positive/negative match
	 *  directly, with no more "assume dungeon by exclusion" — the sidebar fallback below only matters when
	 *  the Mod API itself isn't active/hasn't reported yet. */
	public static boolean isInDungeon() {
		resetOnLevelChange();
		if (isModApiActive()) {
			String mode = HypixelLocationApi.currentIslandMode();
			if (mode != null) return "dungeon".equals(mode);
		}
		return checkArea("dungeon", "Catacombs", "Master Mode");
	}

	public static boolean isInKuudra() {
		return isOnIsland("kuudra", "kuudra", "Kuudra");
	}

	public static boolean isInGarden() {
		return isOnIsland("garden", "garden", "Garden");
	}

	public static boolean isInCrimsonIsle() {
		return isOnIsland("crimson_isle", "crimson", "Crimson Isle");
	}

	/** Moonglade Marsh — the SECOND of the three real Foraging islands (confirmed real API mode + sidebar
	 *  name via SkyHanni's own IslandType.kt: {@code GALATEA("Moonglade Marsh", "foraging_2")}). Not "The
	 *  Park" ({@code foraging_1}) — that's a separate, distinct island, per user correction. */
	public static boolean isInGalatea() {
		return isOnIsland("foraging_2", "galatea", "Moonglade Marsh");
	}

	/** The Burning Desert area specifically (within Crimson Isle) — the scoreboard shows this as its own
	 *  area line, narrower than just "on Crimson Isle somewhere"; Hypixel's Mod API doesn't expose a
	 *  separate island id for it (it's a sub-area of Crimson Isle, not its own island), so this stays
	 *  sidebar-only regardless of whether the Mod API bridge is active. */
	public static boolean isInBurningDesert() {
		return checkArea("burning_desert", "Burning Desert");
	}

	/** True while playing a local/singleplayer world (including one opened to LAN) — i.e. definitely NOT
	 *  real Hypixel, so there's no real party/terminal/boss state to match against at all. Added per user
	 *  request (Positional Messages: restrictions should always show as "matched" here, since testing/
	 *  placing points happens in a singleplayer world with no dungeon party to actually satisfy them). */
	public static boolean isSingleplayer() {
		return Minecraft.getInstance().hasSingleplayerServer();
	}

	/** True whenever actually connected to Hypixel (any island, including the hub/lobby) — for features
	 *  that should apply anywhere in Skyblock/Hypixel but must stay off on singleplayer or other servers. */
	public static boolean isOnHypixel() {
		Minecraft mc = Minecraft.getInstance();
		var connection = mc.getConnection();
		if (connection == null) return false;
		var server = mc.getCurrentServer();
		return server != null && server.ip != null && server.ip.toLowerCase(java.util.Locale.ROOT).contains("hypixel.net");
	}

	/** True only while actually playing Skyblock specifically — not just anywhere on Hypixel. Per user
	 *  report, features gated only on {@link #isOnHypixel} (server IP check) could still show while playing
	 *  a completely different Hypixel game mode, since those also broadcast an action-bar-shaped stat line
	 *  their own regexes could misfire on. Prefers the Mod API's island id (only ever populated by real
	 *  Skyblock islands, per HypixelLocationApi's own doc comment); falls back to checking the sidebar for
	 *  any of ScoreboardReader's known real Skyblock scoreboard labels when the Mod API isn't available. */
	public static boolean isOnSkyblock() {
		if (!isOnHypixel()) return false;
		if (isModApiActive() && HypixelLocationApi.currentIslandMode() != null) return true;
		for (String line : ScoreboardReader.readCurrentSidebarLines()) {
			String plain = COLOR_CODE.matcher(line).replaceAll("");
			for (String label : ScoreboardReader.KNOWN_LABELS) {
				if (plain.contains(label)) return true;
			}
		}
		return false;
	}

	public static boolean isInHubOrLobby() {
		resetOnLevelChange();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		if (isModApiActive()) {
			String mode = HypixelLocationApi.currentIslandMode();
			if (mode != null) return "hub".equals(mode);
		}
		// Hypixel lobbies (main lobby, Skyblock hub) don't show a Skyblock sidebar objective at all; an
		// empty sidebar is the closest reliable "not on a Skyblock island" signal — and a qualitatively
		// different, much stronger one than "has content but is missing one specific keyword right now"
		// (the ambiguous mid-rebuild case checkArea already tolerates indefinitely), so this is the one
		// place besides a real level change/Mod API "hub" confirmation that's trusted to actually clear
		// the sticky area cache.
		boolean empty = ScoreboardReader.readCurrentSidebarLines().isEmpty();
		if (empty) currentArea = null;
		return empty;
	}

	/** True if the confirmed real-time Mod API signal is available for this session — the separate
	 *  "hypixel-mod-api" Fabric mod is installed and its bridge started without error. Checked/started
	 *  lazily (not at class load) since Fabric's mod list isn't guaranteed populated before then, and
	 *  wrapped defensively so any unexpected failure just falls back to sidebar-only detection instead of
	 *  breaking island gating entirely.
	 *
	 *  <p>Per user request ("Make Hypixel Mod API a hard dependency"): the companion "hypixel-mod-api" Fabric
	 *  mod is now declared in this mod's own {@code fabric.mod.json} `depends` block, so Fabric Loader itself
	 *  refuses to launch at all without it — replacing the earlier always-on warning-banner approach (a
	 *  {@code HypixelModApiDependencyFeature} settings row explaining the upside of installing it), which is
	 *  no longer reachable now that its "missing" case can't happen. {@link #isModApiActive} still checks
	 *  {@code FabricLoader.isModLoaded} and starts the bridge lazily/defensively rather than assuming success,
	 *  since a hard dependency guarantees the mod is PRESENT, not that its own bridge start-up can't fail. */
	private static boolean isModApiActive() {
		if (modApiActive == null) {
			boolean loaded = FabricLoader.getInstance().isModLoaded("hypixel-mod-api");
			if (loaded) {
				try {
					HypixelLocationApi.start();
					modApiActive = true;
				} catch (Throwable t) {
					SkyblockSimplified.LOGGER.error("Hypixel Mod API bridge failed to start, falling back to sidebar-only island detection", t);
					modApiActive = false;
				}
			} else {
				modApiActive = false;
			}
		}
		return modApiActive;
	}

	/** Per user report ("It still doesnt detect when i leave the bossfight, everything boss related is still
	 *  running. Detect through the hypixel-mod-api mod."): true only when the Mod API bridge has a CONFIRMED
	 *  (non-null) current island mode that is definitely something other than "dungeon" — a strong,
	 *  authoritative "the player really left" signal, unlike the ambiguous "sidebar just doesn't mention
	 *  Catacombs right now" case the sidebar fallback has to tolerate mid-fight (see the long comment on
	 *  DungeonState.tick()). Real bug found there: leaving a dungeon by warping straight to a DIFFERENT
	 *  populated island (Garden, Crimson Isle, etc., not the hub) correctly made isInDungeon() go false via
	 *  this exact Mod API packet, but DungeonState.tick() only trusted that once isInHubOrLobby() ALSO agreed
	 *  — which requires the Mod API mode to be literally "hub" or the sidebar to be empty, neither of which
	 *  is true on another real island with its own sidebar. A Mod-API-confirmed negative doesn't need that
	 *  corroboration at all (see isOnIsland()'s own doc comment on why a confirmed packet is authoritative on
	 *  its own); this exposes that distinction directly so callers can trust it without requiring hub
	 *  specifically. False whenever the Mod API isn't active — nothing stronger to offer there than the
	 *  existing sidebar-based checks already provide. */
	public static boolean isConfirmedOffDungeon() {
		if (!isModApiActive()) return false;
		String mode = HypixelLocationApi.currentIslandMode();
		return mode != null && !"dungeon".equals(mode);
	}

	private static boolean isOnIsland(String apiMode, String sidebarKey, String... sidebarNeedles) {
		resetOnLevelChange();
		if (isModApiActive()) {
			String mode = HypixelLocationApi.currentIslandMode();
			// A confirmed packet is always authoritative once one has arrived — including a NEGATIVE
			// answer (mode present but not this island), which the old sidebar-only approach could never
			// safely express at all (missing keyword was always ambiguous). Only falls through to the
			// sidebar when nothing has been confirmed yet this session.
			if (mode != null) return apiMode.equals(mode);
		}
		return checkArea(sidebarKey, sidebarNeedles);
	}

	// Per-key sidebar needles for every OTHER real island this project already recognizes — reused here so
	// that, on the sidebar-only fallback path (no Hypixel Mod API companion mod installed), a sidebar that
	// clearly shows a DIFFERENT real island's own name is trusted as strong evidence of having left `key`,
	// same as isModApiActive()'s own KNOWN_NON_DUNGEON_MODES check does for the Mod API case. See
	// checkArea's own doc comment for why this is safe to trust immediately, unlike a merely-missing needle.
	private static final java.util.Map<String, String[]> KNOWN_ISLAND_NEEDLES = java.util.Map.of(
		"dungeon", new String[]{"Catacombs", "Master Mode"},
		"kuudra", new String[]{"Kuudra"},
		"garden", new String[]{"Garden"},
		"crimson", new String[]{"Crimson Isle"},
		"galatea", new String[]{"Moonglade Marsh"},
		"burning_desert", new String[]{"Burning Desert"}
	);

	private static boolean matchesAnyOtherKnownIsland(List<String> plainLines, String excludeKey) {
		for (var entry : KNOWN_ISLAND_NEEDLES.entrySet()) {
			if (entry.getKey().equals(excludeKey)) continue;
			for (String line : plainLines) {
				for (String needle : entry.getValue()) {
					if (line.contains(needle)) return true;
				}
			}
		}
		return false;
	}

	private static boolean checkArea(String key, String... needles) {
		resetOnLevelChange();
		List<String> lines = ScoreboardReader.readCurrentSidebarLines();
		List<String> plainLines = new java.util.ArrayList<>(lines.size());
		for (String line : lines) {
			String plain = COLOR_CODE.matcher(line).replaceAll("");
			plainLines.add(plain);
			for (String needle : needles) {
				if (plain.contains(needle)) {
					currentArea = key;
					return true;
				}
			}
		}
		// A populated sidebar simply missing this particular keyword is deliberately NOT treated as
		// evidence of having left (see class doc comment) — Hypixel's own mid-rebuild flicker looks
		// exactly like that. But a completely EMPTY sidebar (no Skyblock objective at all) is the same
		// strong "actually not on any island" signal isInHubOrLobby() already trusts — checked here too,
		// not just there, since nothing else polls isInHubOrLobby() in the background. Without this, a
		// sticky-positive area (and every HUD element gated on it) could never clear itself after the
		// player left — the old sidebar-fallback path relied entirely on a level-object change or the Mod
		// API bridge to notice, neither of which fires for e.g. a dungeon-to-hub portal on the same level.
		if (lines.isEmpty()) {
			currentArea = null;
		} else if (key.equals(currentArea) && matchesAnyOtherKnownIsland(plainLines, key)) {
			// Real bug found (per user report — up to ~30 real seconds "still in dungeon" after warping
			// straight to a different POPULATED island with no Hypixel Mod API companion mod installed,
			// stuck waiting on DungeonState's own much slower 15-second floor-label-missing timeout): unlike
			// a missing needle, a sidebar clearly showing a DIFFERENT real island's own name can't be a
			// Hypixel mid-rebuild flicker — clears immediately instead of waiting on that separate timeout.
			currentArea = null;
		}
		return key.equals(currentArea);
	}

	private static void resetOnLevelChange() {
		Object currentLevelObj = Minecraft.getInstance().level;
		if (currentLevelObj != lastLevel) {
			lastLevel = currentLevelObj;
			currentArea = null;
		}
	}
}
