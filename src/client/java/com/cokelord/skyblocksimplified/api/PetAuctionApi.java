package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Real lowest-active-BIN price per pet, keyed by species+tier (e.g. "GOLDEN_DRAGON_LEGENDARY") AND matched
 * by the pet's own real XP — per user request ("Pets dont show the correct one either. Pull from lowest
 * bin", refined to "if its not level 100 or 200 it should check the closest level on auction... round
 * down... add a ~ to show that its not entirely accurate"). {@link AuctionApi}'s own median-of-recently-
 * ended-sales is useless for pets specifically: a pet's real {@code ExtraAttributes.id} is always the
 * generic literal "PET" (the actual species/tier/XP lives in a separate {@code ExtraAttributes.petInfo}
 * JSON string this codebase never read before), so AuctionApi's single "PET" bucket was a median across
 * every species/tier/level ever sold, mixed together indiscriminately — the confirmed root cause of "pets
 * dont show the correct one." This scans the LIVE, currently-listed {@code /v2/skyblock/auctions} BIN pool
 * directly (not ended sales) and decodes each pet's real petInfo, tracking the lowest BIN price seen per
 * species+tier+XP.
 *
 * <p>Matching is by raw pet XP, not a computed level number: Hypixel's real per-rarity/per-max-level XP-to-
 * level conversion table isn't available from any API this codebase already talks to, and hardcoding a
 * guessed copy of it risked silently wrong numbers — exactly the class of bug this whole project has spent
 * many rounds fixing. XP is real data already present on both the item being priced and every auction
 * listing, is monotonic with level (so "closest XP" means the same thing as "closest level" in practice),
 * and naturally reproduces "level 100/200 gets an exact match" for free: every capped pet of a given
 * species+rarity shares the exact same maximum XP constant, so they collide on the same key without this
 * class ever needing to know what that constant actually is.
 *
 * <p>The live endpoint is paginated (~1000 auctions/page). Per user follow-up ("make them force lowest bin
 * value (nearest level like we talked about) since all pets are sellable" — after a previous round's fixed
 * 20-page cap/rotation still wasn't reliably covering every pet): now scans every real page each refresh
 * cycle (confirmed live against the actual endpoint — currently 50 pages, ~49k auctions total — sequential
 * single-page-at-a-time requests take well under a minute), so coverage is the FULL live BIN pool every
 * cycle instead of a partial or gradually-rotating sample — every sellable pet should resolve to something.
 */
public final class PetAuctionApi {
	public record PriceMatch(double price, boolean exact) {}

	private static final String URL_PREFIX = "https://api.hypixel.net/v2/skyblock/auctions?page=";
	private static final long REFRESH_MINUTES = 10;

	// type_tier -> (xp -> lowest BIN price seen at that exact xp). A NavigableMap so a non-exact request
	// can cheaply find the closest xp from below (floorEntry) and above (ceilingEntry).
	private static final Map<String, ConcurrentSkipListMap<Double, Double>> BY_XP = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-pet-auction-fetch");
		t.setDaemon(true);
		return t;
	});
	private static boolean started = false;

	private PetAuctionApi() {}

	public static synchronized void start() {
		if (started) return;
		started = true;
		EXECUTOR.scheduleWithFixedDelay(() -> fetchPage(0), 0, REFRESH_MINUTES, TimeUnit.MINUTES);
	}

	// Pages fetched one at a time, each awaited before the next starts — a deliberate natural throttle
	// (no burst of dozens of simultaneous requests), not just a simplicity shortcut. Walks every real page
	// Hypixel reports (totalPages), not a capped slice — see this class's own doc comment for why.
	private static void fetchPage(int page) {
		HttpJsonFetcher.fetchAsync(URL_PREFIX + page).thenAccept(json -> {
			JsonElement aucEl = json.get("auctions");
			if (aucEl != null && aucEl.isJsonArray()) {
				for (JsonElement el : aucEl.getAsJsonArray()) processAuction(el.getAsJsonObject());
			}
			int totalPages = json.has("totalPages") ? json.get("totalPages").getAsInt() : 1;
			int nextPage = page + 1;
			if (nextPage < totalPages) fetchPage(nextPage);
		}).exceptionally(e -> {
			SkyblockSimplified.LOGGER.warn("Failed to refresh live pet auction prices (page " + page + ")", e);
			return null;
		});
	}

	// Real bug found alongside the identical one in AuctionApi's own live-scan (see its doc comment): the
	// LIVE `/v2/skyblock/auctions` endpoint has no "price" field — that only exists on the separate
	// `/v2/skyblock/auctions_ended` endpoint. A bin=true listing's actual price lives under "starting_bid"
	// here. This meant BY_XP was NEVER populated at all — every live pet auction was silently skipped,
	// unnoticed because pet pricing hadn't been specifically re-reported as broken since this shipped.
	private static void processAuction(JsonObject auction) {
		if (!auction.has("bin") || !auction.get("bin").getAsBoolean()) return;
		if (!auction.has("starting_bid") || !auction.has("item_bytes")) return;
		double price = auction.get("starting_bid").getAsDouble();
		PetEntry entry = decodePetEntry(auction.get("item_bytes").getAsString());
		if (entry == null) return;
		BY_XP.computeIfAbsent(entry.key(), k -> new ConcurrentSkipListMap<>())
			.merge(entry.xp(), price, Math::min);
	}

	private record PetEntry(String key, double xp) {}

	private static PetEntry decodePetEntry(String base64ItemBytes) {
		try {
			byte[] bytes = Base64.getDecoder().decode(base64ItemBytes);
			CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
			CompoundTag extraAttrs = root.getListOrEmpty("i").getCompoundOrEmpty(0)
				.getCompoundOrEmpty("tag")
				.getCompoundOrEmpty("ExtraAttributes");
			if (!"PET".equals(extraAttrs.getString("id").orElse(null))) return null;
			String petInfoJson = extraAttrs.getString("petInfo").orElse(null);
			if (petInfoJson == null) return null;
			JsonObject petInfo = JsonParser.parseString(petInfoJson).getAsJsonObject();
			String type = petInfo.has("type") ? petInfo.get("type").getAsString() : null;
			String tier = petInfo.has("tier") ? petInfo.get("tier").getAsString() : null;
			double xp = petInfo.has("exp") ? petInfo.get("exp").getAsDouble() : -1;
			if (type == null || tier == null || xp < 0) return null;
			return new PetEntry(type + "_" + tier, xp);
		} catch (Exception e) {
			// A single malformed/unexpected auction entry shouldn't sink the whole page.
			return null;
		}
	}

	/** The closest real BIN price seen for this exact pet species+tier, matched by XP — an exact XP match
	 *  if one exists (always true for two capped/max-level pets of the same species+rarity, since they
	 *  share the same maximum XP constant), else the closest XP from BELOW (round down) if any listing has
	 *  less XP than the target, else the closest from above if not. Null if nothing has been seen for this
	 *  species+tier at all yet. */
	public static PriceMatch findClosest(String type, String tier, double targetXp) {
		if (type == null || tier == null) return null;
		ConcurrentSkipListMap<Double, Double> byXp = BY_XP.get(type + "_" + tier);
		if (byXp == null || byXp.isEmpty()) return null;

		Double exact = byXp.get(targetXp);
		if (exact != null) return new PriceMatch(exact, true);

		Map.Entry<Double, Double> below = byXp.floorEntry(targetXp);
		if (below != null) return new PriceMatch(below.getValue(), false);

		Map.Entry<Double, Double> above = byXp.ceilingEntry(targetXp);
		if (above != null) return new PriceMatch(above.getValue(), false);

		return null;
	}

	public static boolean isLoaded() {
		return !BY_XP.isEmpty();
	}
}
