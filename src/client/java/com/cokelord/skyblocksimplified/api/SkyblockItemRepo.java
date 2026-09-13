package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Item metadata (display name, NPC sell price, tier, category) keyed by Hypixel's own item id
 * (e.g. "ENCHANTED_CARROT"), backed by the public, keyless `/v2/resources/skyblock/items` endpoint —
 * this is Hypixel's own official item repo, so it's used directly instead of syncing a separate
 * community repo (NEU's) just to get the same kind of id -> name/price mapping.
 */
public final class SkyblockItemRepo {
	// recipe: ingredient item id -> count needed, aggregated across every crafting-grid slot that
	// references it (a recipe can use the same ingredient in more than one slot). Null/empty means either
	// not craftable or the repo entry didn't include a recipe (raw drops, boss drops, etc.) — real Hypixel
	// item resources include a "recipe" object ({"A1": "COBBLESTONE:8", ...}) for every craftable item,
	// this project just wasn't reading it before.
	// material: Hypixel's own vanilla material name for this item (e.g. "DIAMOND_SWORD") — usually a direct
	// modern item id, though Hypixel's API still carries some pre-flattening legacy names (numeric-metadata
	// items like dyes/wool) that don't resolve 1:1; callers should treat a failed resolve as "unknown", not
	// an error. skinTexture: for player-head-based items (Hypixel reskins a huge fraction of its custom
	// items — pets, hats, accessories, decorative blocks — as player heads with a custom skin rather than
	// a real new model), the real base64 skin blob straight from Hypixel's own "skin.value" field, in
	// exactly the format ResolvableProfile/GameProfile already expect (a Property named "textures") — see
	// SkullTextureUtil, which already reads this exact same shape back OFF a real head ItemStack elsewhere
	// in this project. Both null just means this item is a plain, undecorated vanilla item, or that this
	// particular repo entry didn't include either field.
	// itemModel: Hypixel's MODERN reskin mechanism (confirmed real field via a live API pull — e.g.
	// ARACHNE_FRAGMENT: {"material":"PAPER","item_model":"hypixel_skyblock:item/combat_1/arachne_fragment",
	// ...}), a real vanilla resource-location for the "minecraft:item_model" data component (the 1.21.2+
	// per-stack model-override system, next to "material" in the same response object) — the "material"
	// field for these items is just a bare carrier item (almost always PAPER) that means nothing visually
	// once this override is applied. This is DIFFERENT from skinTexture (the older player-head-skin trick):
	// an item using this newer system has no "skin" object in Hypixel's response at all, so falling through
	// to "material" alone (as this project did before) rendered literal Paper for every item using it —
	// several hundred of the ~5600 items in the real repo, per user report ("a lot of those have player
	// heads" — Hypixel's own client-side resource pack renders this model id as a head skin, but that visual
	// isn't reconstructable from the public API at all; applying the same real model id vanilla-side is).
	// skinSignature: the real Mojang signature Hypixel's "skin" object always includes alongside "value" —
	// previously discarded entirely (see SkullTextureUtil.buildHeadWithTexture, which now accepts it too).
	// leatherColor: Hypixel's real "color": "R,G,B" comma-separated string (NOT a nested {"r":,"g":,"b":}
	// object — a wrong assumption this project had until a user report on real Adaptive/Shadow Assassin
	// armor colors traced it back here), present on leather-armor-material items with a fixed, non-user-
	// dyeable canonical color (many dungeon/cosmetic "starter" armor sets reuse plain LEATHER_HELMET/
	// CHESTPLATE/LEGGINGS/BOOTS as their material with a preset color baked into this field rather than a
	// real new model) — packed as a plain 0xRRGGBB int, the same shape DyedItemColor's single-int
	// constructor already expects elsewhere in this project (see SkyblockItemIcons#resolve).
	public record ItemInfo(String id, String name, String category, String tier, Double npcSellPrice,
							Map<String, Integer> recipe, String material, String skinTexture, String itemModel,
							String skinSignature, Integer leatherColor) {}

	private static final String URL = "https://api.hypixel.net/v2/resources/skyblock/items";
	// The item list only changes with game content updates, not moment to moment, so this just needs
	// to eventually notice new/changed items rather than track anything live.
	private static final long REFRESH_MINUTES = 30;

	private static final Map<String, ItemInfo> ITEMS = new ConcurrentHashMap<>();
	// Reverse index (normalized display name -> id) for features that only have an item's tooltip name
	// to go on (lore text, chest reward slots) and no NBT internal-name reader built yet — same
	// name->id resolution NEU/SkyHanni's own NeuInternalName.fromItemName does.
	private static final Map<String, String> NAME_TO_ID = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-item-repo-fetch");
		t.setDaemon(true);
		return t;
	});
	private static boolean started = false;

	private SkyblockItemRepo() {}

	public static synchronized void start() {
		if (started) return;
		started = true;
		EXECUTOR.scheduleWithFixedDelay(SkyblockItemRepo::refresh, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
	}

	private static void refresh() {
		HttpJsonFetcher.fetchAsync(URL).thenAccept(json -> {
			JsonElement itemsEl = json.get("items");
			if (itemsEl == null || !itemsEl.isJsonArray()) return;

			Map<String, ItemInfo> next = new HashMap<>();
			Map<String, String> nextNames = new HashMap<>();
			for (JsonElement el : itemsEl.getAsJsonArray()) {
				JsonObject item = el.getAsJsonObject();
				JsonElement idEl = item.get("id");
				if (idEl == null) continue;
				String id = idEl.getAsString();
				String name = item.has("name") ? item.get("name").getAsString() : id;
				String category = item.has("category") ? item.get("category").getAsString() : null;
				String tier = item.has("tier") ? item.get("tier").getAsString() : null;
				Double npcSellPrice = item.has("npc_sell_price") ? item.get("npc_sell_price").getAsDouble() : null;
				Map<String, Integer> recipe = parseRecipe(item);
				String material = item.has("material") ? item.get("material").getAsString() : null;
				String skinTexture = parseSkinTexture(item);
				String skinSignature = parseSkinSignature(item);
				String itemModel = item.has("item_model") ? item.get("item_model").getAsString() : null;
				Integer leatherColor = parseLeatherColor(item);
				next.put(id, new ItemInfo(id, name, category, tier, npcSellPrice, recipe, material, skinTexture, itemModel, skinSignature, leatherColor));
				nextNames.putIfAbsent(normalizeName(name), id);
			}
			ITEMS.putAll(next);
			NAME_TO_ID.putAll(nextNames);
			SkyblockSimplified.LOGGER.info("Loaded {} items from the Hypixel Skyblock item repo", next.size());
		}).exceptionally(e -> {
			SkyblockSimplified.LOGGER.warn("Failed to refresh the Hypixel Skyblock item repo", e);
			return null;
		});
	}

	/** Null if the id is unknown or the repo hasn't loaded yet. */
	public static ItemInfo getItem(String id) {
		return ITEMS.get(id);
	}

	/** Real Hypixel recipe grid slots are keyed "A1".."C3"; anything else (e.g. a "count" metadata field
	 *  some entries include) is skipped. Each slot value is "ITEM_ID:AMOUNT" — aggregated by item id since
	 *  a recipe can use the same ingredient across more than one slot. Returns null if the item has no
	 *  recipe object at all (not craftable — a raw drop, boss drop, shop-only item, etc.). */
	private static Map<String, Integer> parseRecipe(JsonObject item) {
		if (!item.has("recipe") || !item.get("recipe").isJsonObject()) return null;
		JsonObject recipeObj = item.getAsJsonObject("recipe");
		Map<String, Integer> ingredients = new HashMap<>();
		for (String key : recipeObj.keySet()) {
			if (!key.matches("[ABC][123]")) continue;
			JsonElement value = recipeObj.get(key);
			if (value == null || !value.isJsonPrimitive()) continue;
			String[] parts = value.getAsString().split(":", 2);
			if (parts.length != 2) continue;
			try {
				ingredients.merge(parts[0], Integer.parseInt(parts[1]), Integer::sum);
			} catch (NumberFormatException ignored) {
				// Malformed slot value — skip it rather than let one bad ingredient poison the whole recipe.
			}
		}
		return ingredients.isEmpty() ? null : ingredients;
	}

	/** Hypixel's real resource entries nest the skin blob as {@code "skin": {"value": "<base64>", ...}} —
	 *  null whenever this item either isn't a player-head-based item at all, or the repo entry didn't
	 *  include one (e.g. fetched before Hypixel added the field to a given item). */
	private static String parseSkinTexture(JsonObject item) {
		if (!item.has("skin") || !item.get("skin").isJsonObject()) return null;
		JsonObject skin = item.getAsJsonObject("skin");
		return skin.has("value") ? skin.get("value").getAsString() : null;
	}

	/** Real bug found (per user report — "Adaptive armor is brown, it needs to be gray"): Hypixel's actual
	 *  live format is a plain comma-separated {@code "color": "R,G,B"} STRING, not the nested
	 *  {@code {"r":,"g":,"b":}} object this previously assumed — that wrong shape meant `isJsonObject()`
	 *  always failed and every leather-material item's real dye color silently resolved to null forever, so
	 *  SkyblockItemIcons rendered plain undyed (default brown) leather armor for anything that took this
	 *  real-icon path, never reaching ChestRollingFeature's own hardcoded LEATHER_DYE_OVERRIDES fallback
	 *  (only used when no real icon resolves at all) — see {@link ItemInfo#leatherColor}'s own doc comment. */
	private static Integer parseLeatherColor(JsonObject item) {
		if (!item.has("color") || !item.get("color").isJsonPrimitive()) return null;
		String[] parts = item.get("color").getAsString().split(",");
		if (parts.length != 3) return null;
		try {
			int r = Integer.parseInt(parts[0].trim()) & 0xFF;
			int g = Integer.parseInt(parts[1].trim()) & 0xFF;
			int b = Integer.parseInt(parts[2].trim()) & 0xFF;
			return (r << 16) | (g << 8) | b;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String parseSkinSignature(JsonObject item) {
		if (!item.has("skin") || !item.get("skin").isJsonObject()) return null;
		JsonObject skin = item.getAsJsonObject("skin");
		return skin.has("signature") ? skin.get("signature").getAsString() : null;
	}

	/** Best-effort id lookup from a plain (color-stripped) tooltip display name — null if nothing
	 *  in the repo has exactly that name. Ambiguous names keep whichever id was seen first. */
	public static String findIdByName(String displayName) {
		return NAME_TO_ID.get(normalizeName(displayName));
	}

	// Per user request ("Make sure the necrons handle and wither pieces also support the shiny prefix, which
	// is a rare cosmetic prefix that adds like an effect on those items. It should not break chest detection
	// or chest rolling"): a Shiny-cosmetic item's real Hypixel display name is prefixed with "✦ Shiny " before
	// the item's own real name (e.g. "✦ Shiny Necron's Handle") — findIdByName only ever did a plain
	// trim+lowercase before this, so a Shiny-prefixed name never matched any real repo entry at all. Stripped
	// here (not in chest-rolling's own winner-matching, which already goes through the real NBT item id via
	// SkyblockNbtUtils.getItemId and was never affected by a display-name cosmetic prefix in the first place)
	// since this is the one place a display name is used as the actual lookup key.
	private static final java.util.regex.Pattern SHINY_PREFIX = java.util.regex.Pattern.compile("^✦\\s*shiny\\s+");

	private static String normalizeName(String name) {
		String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
		return SHINY_PREFIX.matcher(normalized).replaceFirst("");
	}

	public static boolean isLoaded() {
		return !ITEMS.isEmpty();
	}

	/** Substring search over item display names, for a "type to filter" picker — case-insensitive,
	 *  capped at limit results since this runs on every keystroke. */
	public static java.util.List<ItemInfo> search(String query, int limit) {
		String needle = normalizeName(query);
		if (needle.isBlank()) return java.util.List.of();
		java.util.List<ItemInfo> results = new java.util.ArrayList<>();
		for (ItemInfo info : ITEMS.values()) {
			if (results.size() >= limit) break;
			if (normalizeName(info.name()).contains(needle) || normalizeName(info.id()).contains(needle)) {
				results.add(info);
			}
		}
		results.sort(java.util.Comparator.comparing(ItemInfo::name));
		return results;
	}
}
