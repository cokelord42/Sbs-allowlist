package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full real Hypixel SkyBlock profile data for Party Finder joiners, via the user's own self-hosted
 * keyless-to-us Cloudflare Worker proxy (the worker holds the real Hypixel API key server-side; this mod
 * just hits a plain {@code ?uuid=} endpoint).
 *
 * <p>Per user clarification after the earlier direct-fetch/GUI-API-key detour this class went through: the
 * real root cause of the hover stats not showing was Hypixel's own API being down that day, not the
 * Cloudflare Worker or the key setup — and asking users to paste their own personal Hypixel API key into the
 * mod isn't actually allowed (that's a per-developer credential, not meant to be shared/redistributed this
 * way). Back to routing every request through the user's own Worker, same as originally built.
 *
 * <p>Every field below was verified against a real live response before being wired up here — real member
 * sub-object paths ({@code dungeons.dungeon_types.catacombs}, {@code dungeons.secrets},
 * {@code pets_data.pets[].type}, {@code inventory.inv_armor}/{@code equipment_contents}), a real
 * profile-level {@code banking.balance} field, and the item-bytes base64+gzip NBT shape {@link AuctionApi}
 * already decodes the same way for auction listings. Real, live-confirmed internal ids for the specific
 * items the user asked to flag: {@code HYPERION} (already confirmed elsewhere in this codebase, see
 * {@link AuctionApi#HYPERION_ID}), {@code TERMINATOR} (checked live against Hypixel's own
 * {@code /resources/skyblock/items} catalog), and {@code CATACOMBS_EXPERT_RING} (confirmed after an earlier
 * display-name-based match never fired — see {@link #CATACOMBS_EXPERT_RING_ID}'s own doc comment).
 *
 * <p>Per user request ("The api data gather needs to get catacombs level aswell"): now computes a real
 * Catacombs Level from {@code catacombs.experience} using the exact same per-level dungeon XP table {@link
 * CatacombsExpCalculatorFeature} already carries — independently confirmed there against NotEnoughUpdates-
 * REPO's own {@code constants/leveling.json} "catacombs" array (see that class's own doc comment for the
 * source), the exact table this class's own doc comment used to say blocked a level number from ever being
 * computed here. Duplicated locally (small, established convention in this codebase — see e.g.
 * MelodyDisplayFeature's own duplicated classColor/classIcon) rather than shared, since the two classes have
 * no other coupling. The raw XP is still exposed too, for anyone who wants sub-level progress.
 */
public final class SkyblockStatsApi {
	private static final String BASE_URL = "https://skyblock-stats-proxy.cokelordthe42nd-748.workers.dev/?uuid=";
	// Real, live-confirmed internal ids (see class doc comment) — not guessed.
	private static final String HYPERION_ID = "HYPERION";
	private static final String TERMINATOR_ID = "TERMINATOR";
	// Real, live-confirmed internal id for the Catacombs Expert Ring (per user follow-up after the
	// display-name match still never found it) — replaces the earlier display-name guess entirely.
	private static final String CATACOMBS_EXPERT_RING_ID = "CATACOMBS_EXPERT_RING";
	private static final String GOLDEN_DRAGON_TYPE = "GOLDEN_DRAGON";
	// Per user request (Auto-Kick: "Add an ender dragon option to the auto-kick aswell as the golden
	// dragon"): same real pets_data pet-type id convention as GOLDEN_DRAGON_TYPE above.
	private static final String ENDER_DRAGON_TYPE = "ENDER_DRAGON";
	// Real Hypixel pets_data pet-type id ("SPIRIT") — used for the dungeon score first-death forgiveness
	// rule (per user request: "if they have a legendary spirit pet in their pets menu the death should only
	// be a -1"). Matched against the pet's OWN tier field (not whichever pet is currently active/equipped —
	// the real Hypixel rule only cares whether one exists anywhere in the player's pets menu).
	private static final String SPIRIT_PET_TYPE = "SPIRIT";

	// Real per-level Catacombs (dungeon) XP requirements, level 1 through 50 — duplicated verbatim from
	// CatacombsExpCalculatorFeature.LEVEL_XP (see this class's own doc comment on why it's safe to trust and
	// why it's copied rather than shared).
	private static final long[] LEVEL_XP = {
		50, 75, 110, 160, 230, 330, 470, 670, 950, 1340, 1890, 2665, 3760, 5260, 7380, 10300, 14400, 20000,
		27600, 38000, 52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000, 1065000,
		1410000, 1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000, 15000000, 19000000,
		24000000, 30000000, 38000000, 48000000, 60000000, 75000000, 93000000, 116250000
	};

	/** Real Catacombs level derived from raw XP via {@link #LEVEL_XP} — caps at 50 (XP earned past that no
	 *  longer raises a displayed level), same algorithm as CatacombsExpCalculatorFeature's own currentLevel. */
	private static int catacombsLevelFromXp(double xp) {
		double remaining = xp;
		int level = 0;
		while (level < LEVEL_XP.length && remaining >= LEVEL_XP[level]) {
			remaining -= LEVEL_XP[level];
			level++;
		}
		return level;
	}

	/** Real per-floor best-clear times, in whole seconds — confirmed against a real captured
	 *  {@code /v2/skyblock/profiles} response (see this class's own commit history): Hypixel exposes three
	 *  separate maps per dungeon type ({@code fastest_time}/{@code fastest_time_s}/{@code fastest_time_s_plus},
	 *  each floor-number -> milliseconds), not a single time plus a rank string — there is no rank field at
	 *  all, S+/S/A/B/C is only ever a letter grade computed from {@code best_score} by threshold (see
	 *  {@link #scoreRank}). Any field may be null if the player has never posted a clear of that rank on this
	 *  floor (e.g. sPlusSeconds is null until they've actually scored 300+ on it at least once). */
	public record FloorTimes(Integer fastestSeconds, Integer sSeconds, Integer sPlusSeconds) {
		/** Real rank-preference rule per user spec ("Make the personal best always go off S+ ranking, and S
		 *  ranking on mastermode/regular floor 4 and below... If the user has no S+ or S runs on the floor
		 *  queued it should detect any personal best timer, no matter the rank"): S+ first if it exists; S
		 *  next only if the caller says S is acceptable for this floor/mode; any-rank fastest time as the
		 *  final fallback. Null only if the player has never cleared this floor at all. */
		public Integer bestSecondsPreferringRank(boolean sRankAcceptable) {
			if (sPlusSeconds != null) return sPlusSeconds;
			if (sRankAcceptable && sSeconds != null) return sSeconds;
			return fastestSeconds;
		}
	}

	public record PlayerStats(
		int f7Completions,
		int m5Completions,
		int m6Completions,
		int m7Completions,
		long totalSecrets,
		long totalRuns,
		double catacombsExperience,
		int catacombsLevel,
		boolean hasHyperion,
		boolean hasTerminator,
		boolean hasGoldenDragon,
		boolean hasEnderDragon,
		boolean hasLegendarySpiritPet,
		// Real, live-confirmed internal id ("CATACOMBS_EXPERT_RING") — replaces an earlier display-name-based
		// guess that turned out to never actually match (per user report: "still does not get calculated").
		boolean hasCatacombsExpertRing,
		double bankBalance,
		List<String> armorNames,
		List<String> equipmentNames,
		Map<Integer, FloorTimes> regularFloorTimes,
		Map<Integer, FloorTimes> masterFloorTimes,
		// Per user request (Catacombs Experience Calculator — "the helmet with the highest level of
		// hecatomb"): accessoryNames comes from the same real per-container decode already used for
		// armorNames/equipmentNames, just pointed at Hypixel's real accessory-bag inventory key
		// ("talisman_bag") instead. maxHecatombLevel is the highest "hecatomb" enchant level found on ANY item
		// across every container this class already scans (inv_armor/equipment_contents/inv_contents/
		// talisman_bag) — Hecatomb is a real Hypixel enchant only ever obtainable on helmets, so no separate
		// armor-slot-type check is needed to know a hit is a helmet.
		List<String> accessoryNames,
		int maxHecatombLevel
	) {
		public double secretsPerRun() { return totalRuns > 0 ? (double) totalSecrets / totalRuns : 0; }
		public boolean hasBillionInBank() { return bankBalance >= 1_000_000_000; }

		/** Real floor-1..7 best-time lookup for either dungeon type — null if the floor number is out of
		 *  range or the player has no record for it at all. */
		public FloorTimes floorTimes(boolean masterMode, int floor) {
			Map<Integer, FloorTimes> map = masterMode ? masterFloorTimes : regularFloorTimes;
			return map.get(floor);
		}
	}

	/** Real Hypixel dungeon score->letter-grade thresholds — Hypixel exposes only the numeric
	 *  {@code best_score}, never a rank string; these boundaries are independently confirmed both
	 *  empirically (every real run matching a live {@code fastest_time_s_plus} entry scores >=300, every one
	 *  matching {@code fastest_time_s} but not {@code _s_plus} scores in [270,299]) and against Devonian's own
	 *  live ScoreDisplay.kt, which hardcodes the identical breakpoints. Not currently consumed by autokick
	 *  (which reads the real fastest_time_s/_s_plus maps directly instead of re-deriving rank from score),
	 *  kept here since it's the same real threshold data other future features may want. */
	public static String scoreRank(int score) {
		if (score >= 300) return "S+";
		if (score >= 270) return "S";
		if (score >= 230) return "A";
		if (score >= 160) return "B";
		if (score >= 100) return "C";
		return "D";
	}

	private static final Map<String, PlayerStats> CACHE = new ConcurrentHashMap<>();
	private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	private SkyblockStatsApi() {}

	/** Queues a fetch for this player's real, live SkyBlock data. No-op if already cached or already
	 *  in flight — a Party Finder join re-requesting the same recently-seen player shouldn't re-hit the
	 *  worker every time. */
	public static void request(String uuid) {
		String key = normalizeUuid(uuid);
		if (key.isEmpty() || CACHE.containsKey(key) || !IN_FLIGHT.add(key)) return;
		HttpJsonFetcher.fetchAsync(BASE_URL + key).thenAccept(json -> {
			try {
				PlayerStats stats = parse(json, key);
				if (stats != null) CACHE.put(key, stats);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.warn("SkyblockStatsApi: failed to parse response for {}", key, e);
			} finally {
				IN_FLIGHT.remove(key);
			}
		}).exceptionally(e -> {
			IN_FLIGHT.remove(key);
			SkyblockSimplified.LOGGER.warn("SkyblockStatsApi: fetch failed for {}", key, e);
			return null;
		});
	}

	/** Null if never requested, still pending, or the worker returned nothing usable for this player —
	 *  callers must treat null as "no enrichment available yet" and degrade gracefully. */
	public static PlayerStats get(String uuid) {
		return CACHE.get(normalizeUuid(uuid));
	}

	private static String normalizeUuid(String uuid) {
		return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
	}

	private static PlayerStats parse(JsonObject root, String uuid) {
		if (root == null || !root.has("success") || !root.get("success").getAsBoolean()) return null;
		if (!root.has("profiles") || !root.get("profiles").isJsonArray()) return null;
		JsonArray profiles = root.getAsJsonArray("profiles");
		JsonObject selected = null;
		for (JsonElement el : profiles) {
			if (!el.isJsonObject()) continue;
			JsonObject p = el.getAsJsonObject();
			if (p.has("selected") && p.get("selected").getAsBoolean()) { selected = p; break; }
		}
		if (selected == null) return null;
		if (!selected.has("members") || !selected.get("members").isJsonObject()) return null;
		JsonObject members = selected.getAsJsonObject("members");
		if (!members.has(uuid) || !members.get(uuid).isJsonObject()) return null;
		JsonObject member = members.getAsJsonObject(uuid);

		double bankBalance = 0;
		if (selected.has("banking") && selected.get("banking").isJsonObject()) {
			JsonObject banking = selected.getAsJsonObject("banking");
			if (banking.has("balance")) bankBalance = banking.get("balance").getAsDouble();
		}

		JsonObject dungeons = jsonObj(member, "dungeons");
		JsonObject dungeonTypes = jsonObj(dungeons, "dungeon_types");
		JsonObject catacombs = jsonObj(dungeonTypes, "catacombs");
		JsonObject masterCatacombs = jsonObj(dungeonTypes, "master_catacombs");

		// Per user request ("The m5+ completions shouldnt be m5+, it should count out the amount of
		// completions on each floor on f7 + m5 and up, for example show many runs they have on f7, m5, m6,
		// m7"): individual per-tier reads off the SAME already-fetched master_catacombs.tier_completions map
		// this used to sum into one aggregate — no new network call, just reading three more of its own keys.
		int f7 = tierCompletion(catacombs, "7");
		int m5 = tierCompletion(masterCatacombs, "5");
		int m6 = tierCompletion(masterCatacombs, "6");
		int m7 = tierCompletion(masterCatacombs, "7");

		long totalSecrets = dungeons.has("secrets") && dungeons.get("secrets").isJsonPrimitive() ? dungeons.get("secrets").getAsLong() : 0;
		long totalRuns = totalCompletions(catacombs) + totalCompletions(masterCatacombs);
		double catacombsXp = catacombs.has("experience") && catacombs.get("experience").isJsonPrimitive() ? catacombs.get("experience").getAsDouble() : 0;

		boolean hasGoldenDragon = false;
		boolean hasEnderDragon = false;
		boolean hasLegendarySpiritPet = false;
		JsonObject petsData = jsonObj(member, "pets_data");
		if (petsData.has("pets") && petsData.get("pets").isJsonArray()) {
			for (JsonElement pe : petsData.getAsJsonArray("pets")) {
				if (!pe.isJsonObject()) continue;
				JsonObject pet = pe.getAsJsonObject();
				String petType = pet.has("type") && pet.get("type").isJsonPrimitive() ? pet.get("type").getAsString() : null;
				if (GOLDEN_DRAGON_TYPE.equals(petType)) hasGoldenDragon = true;
				if (ENDER_DRAGON_TYPE.equals(petType)) hasEnderDragon = true;
				if (SPIRIT_PET_TYPE.equals(petType) && pet.has("tier") && pet.get("tier").isJsonPrimitive()
					&& "LEGENDARY".equalsIgnoreCase(pet.get("tier").getAsString())) {
					hasLegendarySpiritPet = true;
				}
			}
		}

		List<String> armorNames = new ArrayList<>();
		List<String> equipmentNames = new ArrayList<>();
		List<String> accessoryNames = new ArrayList<>();
		boolean[] hasHyperion = {false};
		boolean[] hasTerminator = {false};
		boolean[] hasCatacombsExpertRing = {false};
		int[] hecatombLevel = {0};
		JsonObject inventory = jsonObj(member, "inventory");
		decodeItems(inventory, "inv_armor", armorNames, hasHyperion, hasTerminator, hasCatacombsExpertRing, hecatombLevel);
		decodeItems(inventory, "equipment_contents", equipmentNames, hasHyperion, hasTerminator, hasCatacombsExpertRing, hecatombLevel);
		// Real bug found (per user report — "party finder stats has issues detecting Hyperion and
		// Terminator"): Hyperion and Terminator are WEAPONS (swords), not armor or an equipment-slot cosmetic
		// — they were never going to show up scanning only inv_armor/equipment_contents regardless of
		// reforges, because a sword simply never lives in either of those containers. It sits in the player's
		// real main inventory (Hypixel's own "inv_contents" key), so that container needs scanning too — a
		// throwaway list since armorNames/equipmentNames display is unaffected, only the weapon flags matter
		// here.
		decodeItems(inventory, "inv_contents", new ArrayList<>(), hasHyperion, hasTerminator, hasCatacombsExpertRing, hecatombLevel);
		// Real gap found (per user report — "the api also needs to detect the items like hyperion and
		// terminator in backpacks and enderchests if players dont have it out in their inventory when joining
		// parties but actually own one"): nobody carries their best weapon loose in their main inventory
		// alongside dungeon loot — it's routinely stashed in a backpack or the ender chest while a cheaper
		// swap weapon is held instead, and neither was ever scanned, so Auto-kick's Hyperion/Terminator checks
		// missed players who genuinely owned one. Ender chest is one single combined blob across every page
		// ("ender_chest_contents", same real {type,data} shape every other container here decodes) — scanned
		// the same way via decodeItems.
		decodeItems(inventory, "ender_chest_contents", new ArrayList<>(), hasHyperion, hasTerminator, hasCatacombsExpertRing, hecatombLevel);
		// Backpacks are DIFFERENT: Hypixel's real API nests one SEPARATE blob per backpack under
		// "backpack_contents", keyed by slot index ("0", "1", ...) — not a single combined blob like the
		// ender chest — so every one of them needs decoding individually.
		decodeBackpacks(inventory, new ArrayList<>(), hasHyperion, hasTerminator, hasCatacombsExpertRing, hecatombLevel);
		// Real bug found (per user report — "it cant find catacombs expert ring"): unlike inv_armor/
		// equipment_contents/inv_contents (real top-level siblings under "inventory"), Hypixel's real API
		// response nests every BAG container one level deeper, under "inventory.bag_contents" — talisman_bag,
		// potion_bag, fishing_bag, quiver, and sacks_bag all live there, not directly under "inventory" itself.
		// Reading "inventory.talisman_bag" directly (the old code) always found nothing, since that exact key
		// never exists at that level — the accessory bag was never actually being scanned at all.
		JsonObject bagContents = jsonObj(inventory, "bag_contents");
		decodeItems(bagContents, "talisman_bag", accessoryNames, hasHyperion, hasTerminator, hasCatacombsExpertRing, hecatombLevel);

		Map<Integer, FloorTimes> regularFloorTimes = parseFloorTimes(catacombs);
		Map<Integer, FloorTimes> masterFloorTimes = parseFloorTimes(masterCatacombs);

		return new PlayerStats(f7, m5, m6, m7, totalSecrets, totalRuns, catacombsXp, catacombsLevelFromXp(catacombsXp),
			hasHyperion[0], hasTerminator[0], hasGoldenDragon, hasEnderDragon, hasLegendarySpiritPet, hasCatacombsExpertRing[0], bankBalance, armorNames, equipmentNames,
			regularFloorTimes, masterFloorTimes, accessoryNames, hecatombLevel[0]);
	}

	/** Real per-floor best-time maps, confirmed against a real captured API response (see FloorTimes' own
	 *  doc comment): {@code fastest_time}/{@code fastest_time_s}/{@code fastest_time_s_plus} are each a real
	 *  floor-number(as string) -> milliseconds map, plus an unused "best"/"0" key this skips (floor 0 is the
	 *  entrance, never a real dungeon floor autokick would care about). */
	private static Map<Integer, FloorTimes> parseFloorTimes(JsonObject dungeonType) {
		Map<Integer, Integer> fastest = parseMsMap(jsonObj(dungeonType, "fastest_time"));
		Map<Integer, Integer> sTimes = parseMsMap(jsonObj(dungeonType, "fastest_time_s"));
		Map<Integer, Integer> sPlusTimes = parseMsMap(jsonObj(dungeonType, "fastest_time_s_plus"));
		Map<Integer, FloorTimes> result = new java.util.HashMap<>();
		for (int floor = 1; floor <= 7; floor++) {
			Integer f = fastest.get(floor), s = sTimes.get(floor), sp = sPlusTimes.get(floor);
			if (f == null && s == null && sp == null) continue;
			result.put(floor, new FloorTimes(f, s, sp));
		}
		return result;
	}

	private static Map<Integer, Integer> parseMsMap(JsonObject obj) {
		Map<Integer, Integer> result = new java.util.HashMap<>();
		for (String key : obj.keySet()) {
			if (key.equals("best")) continue;
			try {
				int floor = Integer.parseInt(key);
				if (obj.get(key).isJsonPrimitive()) result.put(floor, obj.get(key).getAsInt() / 1000);
			} catch (NumberFormatException ignored) {}
		}
		return result;
	}

	private static JsonObject jsonObj(JsonObject parent, String key) {
		return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
	}

	private static int tierCompletion(JsonObject dungeonType, String tier) {
		JsonObject tc = jsonObj(dungeonType, "tier_completions");
		return tc.has(tier) && tc.get(tier).isJsonPrimitive() ? tc.get(tier).getAsInt() : 0;
	}

	private static long totalCompletions(JsonObject dungeonType) {
		JsonObject tc = jsonObj(dungeonType, "tier_completions");
		return tc.has("total") && tc.get("total").isJsonPrimitive() ? tc.get("total").getAsLong() : 0;
	}

	/** Decodes one base64+gzip NBT inventory blob (Hypixel's real {@code {"type":..,"data":"..."}} item-bytes
	 *  shape — same format {@link AuctionApi#decodeExtraAttributes} already decodes for auction listings) into
	 *  real item display names, flagging Hyperion/Terminator/the Catacombs Expert Ring by their confirmed real
	 *  internal ids along the way. A single malformed/empty blob just yields no names rather than failing the
	 *  whole player's stats. */
	private static void decodeItems(JsonObject inventory, String key, List<String> outNames, boolean[] hyperionFlag,
									 boolean[] terminatorFlag, boolean[] catacombsRingFlag, int[] maxHecatombLevel) {
		if (!inventory.has(key) || !inventory.get(key).isJsonObject()) return;
		decodeBlob(inventory.getAsJsonObject(key), outNames, hyperionFlag, terminatorFlag, catacombsRingFlag, maxHecatombLevel);
	}

	/** Real gap found (per user report — see decodeBackpacks' own call-site doc comment): unlike every other
	 *  container here, Hypixel's real API nests one SEPARATE {@code {"type":..,"data":"..."}} blob PER
	 *  BACKPACK under {@code "backpack_contents"}, keyed by slot index ("0", "1", ...) rather than a single
	 *  combined blob — every one of them needs decoding, since a player can own several backpacks and the
	 *  weapon in question could be in any of them. */
	private static void decodeBackpacks(JsonObject inventory, List<String> outNames, boolean[] hyperionFlag,
										 boolean[] terminatorFlag, boolean[] catacombsRingFlag, int[] maxHecatombLevel) {
		if (!inventory.has("backpack_contents") || !inventory.get("backpack_contents").isJsonObject()) return;
		JsonObject backpacks = inventory.getAsJsonObject("backpack_contents");
		for (String key : backpacks.keySet()) {
			if (!backpacks.get(key).isJsonObject()) continue;
			decodeBlob(backpacks.getAsJsonObject(key), outNames, hyperionFlag, terminatorFlag, catacombsRingFlag, maxHecatombLevel);
		}
	}

	private static void decodeBlob(JsonObject blob, List<String> outNames, boolean[] hyperionFlag,
									boolean[] terminatorFlag, boolean[] catacombsRingFlag, int[] maxHecatombLevel) {
		if (!blob.has("data") || !blob.get("data").isJsonPrimitive()) return;
		try {
			byte[] bytes = Base64.getDecoder().decode(blob.get("data").getAsString());
			CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
			ListTag items = root.getListOrEmpty("i");
			for (int i = 0; i < items.size(); i++) {
				CompoundTag item = items.getCompoundOrEmpty(i);
				if (item.isEmpty()) continue;
				CompoundTag tag = item.getCompoundOrEmpty("tag");
				CompoundTag display = tag.getCompoundOrEmpty("display");
				String name = display.getStringOr("Name", "");
				if (!name.isBlank()) outNames.add(name);
				CompoundTag extraAttrs = tag.getCompoundOrEmpty("ExtraAttributes");
				String id = extraAttrs.getStringOr("id", "");
				if (HYPERION_ID.equals(id)) hyperionFlag[0] = true;
				if (TERMINATOR_ID.equals(id)) terminatorFlag[0] = true;
				if (CATACOMBS_EXPERT_RING_ID.equals(id)) catacombsRingFlag[0] = true;
				// Real Hypixel custom-enchant NBT shape (tag.ExtraAttributes.enchantments.<name> -> level int),
				// the same path every other real SkyBlock tool reads enchant levels from — "hecatomb" is only
				// ever obtainable on a helmet, so a hit here is unambiguously a helmet with no separate
				// armor-slot check needed (see the Catacombs Experience Calculator's own request for why this
				// matters: "the helmet with the highest level of hecatomb").
				CompoundTag enchantments = extraAttrs.getCompoundOrEmpty("enchantments");
				int hecatombLevel = enchantments.getIntOr("hecatomb", 0);
				if (hecatombLevel > maxHecatombLevel[0]) maxHecatombLevel[0] = hecatombLevel;
			}
		} catch (Exception ignored) {
			// Malformed/unreadable item-bytes blob — this player's armor/equipment section just stays empty,
			// same "degrade gracefully" contract every other optional field on this record already has.
		}
	}
}
