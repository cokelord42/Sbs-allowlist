package com.cokelord.skyblocksimplified.pv;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.HttpJsonFetcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Static reference data the Player Viewer needs, fetched once per session on first use:
 * <ul>
 *   <li>NEU-REPO {@code constants/leveling.json} — skill/runecrafting/social/catacombs/HOTM/HOTF per-level XP
 *       tables, level caps and slayer thresholds (same source CatacombsExpCalculatorFeature's table was
 *       confirmed against).</li>
 *   <li>NEU-REPO {@code constants/pets.json} — pet level XP table + per-rarity offsets + custom curves
 *       (Golden Dragon's 200 levels).</li>
 *   <li>NEU-REPO {@code constants/reforgestones.json} — reforge name -> reforge stone id, for networth.</li>
 *   <li>Hypixel {@code /v2/resources/skyblock/collections} (keyless) — collection categories, names, tiers.</li>
 * </ul>
 * Every accessor returns null until its file has loaded; callers render a placeholder in that case.
 */
public final class PvRepo {
	private static final String NEU = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/";
	private static final AtomicBoolean started = new AtomicBoolean(false);

	private static volatile JsonObject leveling;
	private static volatile JsonObject pets;
	private static volatile JsonObject collections;
	private static volatile Map<String, String> reforgeNameToStone;

	private PvRepo() {}

	public static void ensureLoaded() {
		if (!started.compareAndSet(false, true)) return;
		load(NEU + "leveling.json", json -> leveling = json);
		load(NEU + "pets.json", json -> pets = json);
		load("https://api.hypixel.net/v2/resources/skyblock/collections", json -> collections = json.getAsJsonObject("collections"));
		load(NEU + "reforgestones.json", json -> {
			Map<String, String> map = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : json.entrySet()) {
				if (e.getValue() instanceof JsonObject stone && stone.has("reforgeName")) {
					map.put(stone.get("reforgeName").getAsString().toLowerCase(java.util.Locale.ROOT), e.getKey());
				}
			}
			reforgeNameToStone = map;
		});
	}

	private static final java.util.concurrent.atomic.AtomicInteger VERSION = new java.util.concurrent.atomic.AtomicInteger();

	/** Bumped every time a data file finishes loading, so an open viewer knows to rebuild its pages. */
	public static int version() {
		return VERSION.get() + (com.cokelord.skyblocksimplified.api.SkyblockTextureMapApi.get() != null ? 1000 : 0);
	}

	private static void load(String url, java.util.function.Consumer<JsonObject> sink) {
		HttpJsonFetcher.fetchAsync(url).thenAccept(sink).thenRun(VERSION::incrementAndGet).exceptionally(e -> {
			SkyblockSimplified.LOGGER.warn("Player Viewer: failed to load {}", url, e);
			return null;
		});
	}

	public static boolean isLevelingLoaded() { return leveling != null; }
	public static JsonObject collections() { return collections; }

	/** Reforge stone item id for an applied reforge (the NBT "modifier" value), or null for basic reforges. */
	public static String reforgeStone(String modifier) {
		Map<String, String> map = reforgeNameToStone;
		return map == null || modifier == null ? null : map.get(modifier.toLowerCase(java.util.Locale.ROOT));
	}

	public static int[] table(String key) {
		JsonObject lv = leveling;
		if (lv == null || !(lv.get(key) instanceof JsonArray arr)) return null;
		int[] out = new int[arr.size()];
		for (int i = 0; i < out.length; i++) out[i] = arr.get(i).getAsInt();
		return out;
	}

	public static int cap(String skill, int fallback) {
		JsonObject lv = leveling;
		if (lv == null || !(lv.get("leveling_caps") instanceof JsonObject caps) || !caps.has(skill)) return fallback;
		return caps.get(skill).getAsInt();
	}

	/** Cumulative slayer XP thresholds per level (index 0 = level 1). */
	public static int[] slayerThresholds(String boss) {
		JsonObject lv = leveling;
		if (lv == null || !(lv.get("slayer_xp") instanceof JsonObject s) || !(s.get(boss) instanceof JsonArray arr)) return null;
		int[] out = new int[arr.size()];
		for (int i = 0; i < out.length; i++) out[i] = arr.get(i).getAsInt();
		return out;
	}

	/** Level progress for a per-level XP table: whole level, fraction into the next, and the level cap. */
	public record Level(int level, double progress, int cap, double xp, double xpIntoLevel, double xpForNext) {
		public double exact() { return level + (level >= cap ? 0 : progress); }
	}

	public static Level level(double xp, int[] perLevel, int cap) {
		if (perLevel == null) return new Level(0, 0, cap, xp, 0, 0);
		int maxLevel = Math.min(cap, perLevel.length);
		double remaining = xp;
		int level = 0;
		while (level < maxLevel && remaining >= perLevel[level]) {
			remaining -= perLevel[level];
			level++;
		}
		double next = level < maxLevel ? perLevel[level] : 0;
		return new Level(level, next > 0 ? remaining / next : 1, maxLevel, xp, remaining, next);
	}

	private static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};

	public static int rarityIndex(String tier) {
		for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equalsIgnoreCase(tier)) return i;
		return 0;
	}

	/** Pet level for the given XP (NEU's own algorithm: the base table starting at the rarity offset, with a
	 *  species' custom curve appended/used when one exists). Returns 1 until pets.json has loaded. */
	public static int petLevel(String type, String tier, double xp) {
		JsonObject p = pets;
		if (p == null) return 1;
		int offset = p.getAsJsonObject("pet_rarity_offset").has(tier) ? p.getAsJsonObject("pet_rarity_offset").get(tier).getAsInt() : 0;
		java.util.List<Integer> levels = new java.util.ArrayList<>();
		for (JsonElement e : p.getAsJsonArray("pet_levels")) levels.add(e.getAsInt());
		int maxLevel = 100;
		if (p.getAsJsonObject("custom_pet_leveling").get(type) instanceof JsonObject custom) {
			if (custom.has("max_level")) maxLevel = custom.get("max_level").getAsInt();
			if (custom.has("rarity_offset") && custom.getAsJsonObject("rarity_offset").has(tier)) {
				offset = custom.getAsJsonObject("rarity_offset").get(tier).getAsInt();
			}
			if (custom.get("pet_levels") instanceof JsonArray extra) {
				int mode = custom.has("type") ? custom.get("type").getAsInt() : 0;
				if (mode == 2) levels.clear();
				for (JsonElement e : extra) levels.add(e.getAsInt());
			}
		}
		double remaining = xp;
		int level = 1;
		for (int i = offset; i < levels.size() && level < maxLevel; i++) {
			if (remaining < levels.get(i)) break;
			remaining -= levels.get(i);
			level++;
		}
		return level;
	}
}
