package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auction House sale prices, keyed by Hypixel's own item internal name (e.g. "HYPERION", "ASPECT_OF_THE_DRAGONS")
 * — for gear/weapons/high-tier items that never touch the Bazaar or an NPC sell price, this is the only
 * real source of "what's this actually worth". Primarily backed by the public, keyless
 * `/v2/skyblock/auctions_ended` endpoint (recently-finished auctions, refreshed by Hypixel roughly every
 * 60 seconds), which — unlike `/v2/skyblock/auctions` — is a single page, not a paginated live-auction
 * scan, so a simple periodic fetch is enough for it. That feed is too thin to reliably cover infrequently-
 * traded items, though, so this also runs a full paginated scan of the LIVE `/v2/skyblock/auctions` BIN
 * pool (same pattern as {@link PetAuctionApi}) as a fallback price source — see {@link #getPrice} and the
 * fields around {@link #LIVE_LOWEST_BIN}.
 *
 * <p>Only counts BIN (Buy It Now) sales, not standard auction final bids: a BIN price is a real, chosen
 * "I'll sell for exactly this" number, while a standard auction's winning bid can be far below true value
 * if only one person bid on it. Each item's price is the MEDIAN of its last {@link #SAMPLE_CAP} recorded
 * BIN sales rather than the single lowest one, since a lowest-ever price is far more likely to be a
 * mis-list/scam-bait listing than a representative market price.
 *
 * <p>The endpoint only gives each auction's raw NBT (`item_bytes`, base64 + gzip, the same format the
 * item's real ItemStack NBT would have) — no plain item-name field — so this decodes it with the game's
 * own NbtIo/CompoundTag classes (confirmed real API for this project via the same extracted-class-file
 * process used elsewhere) rather than guessing at a text format. Reads exactly one field
 * (`i[0].tag.ExtraAttributes.id`, Hypixel's standard internal-name location) and discards the rest.
 */
public final class AuctionApi {
	public record AuctionPrice(double medianBinPrice, int sampleCount) {}

	private static final String URL = "https://api.hypixel.net/v2/skyblock/auctions_ended";
	private static final long REFRESH_MINUTES = 2;
	private static final int SAMPLE_CAP = 20;

	// Per user report ("Its detecting the helmet but not the right price" — a Wither Helmet reward priced at
	// the NPC sell floor because the ended-auctions feed above almost never records MIN_RELIABLE_SAMPLES of
	// an infrequently-traded item): scans the FULL LIVE `/v2/skyblock/auctions` BIN pool, same proven
	// full-pagination pattern as PetAuctionApi generalized past pets, tracking the lowest currently-listed
	// BIN price per item so thin-ended-sales-data gear still gets a real market number instead of falling
	// through to Bazaar/NPC. Consulted only as a fallback in getPrice() below — a completed sale (the median
	// above) is a stronger signal than a current listing, so it stays authoritative whenever there's enough
	// of it; this only fills the gap for items that never reach that threshold.
	private static final String LIVE_URL_PREFIX = "https://api.hypixel.net/v2/skyblock/auctions?page=";
	private static final long LIVE_REFRESH_MINUTES = 10;
	private static final Map<String, Double> LIVE_LOWEST_BIN = new ConcurrentHashMap<>();
	private static final Map<String, AtomicInteger> LIVE_SAMPLE_COUNTS = new ConcurrentHashMap<>();
	// Per user report ("The price is still the same" after this live-scan fallback shipped): a ~50-page
	// sequential scan can take a real, non-instant amount of time to complete its first pass after startup,
	// and any given item's few active BIN listings (if it has any at all right now) could be sitting on
	// whichever page hasn't been reached yet — indistinguishable, from a single price-lookup result, from a
	// real bug. Exposed via the getters below so callers can log real diagnostic state (samples tracked,
	// full passes completed) instead of the next report having to guess "still loading" vs "actually broken".
	private static final AtomicInteger LIVE_SCAN_PASSES_COMPLETED = new AtomicInteger(0);

	private static final Map<String, Deque<Double>> RECENT_BIN_PRICES = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-auction-fetch");
		t.setDaemon(true);
		return t;
	});
	private static boolean started = false;

	private AuctionApi() {}

	public static synchronized void start() {
		if (started) return;
		started = true;
		EXECUTOR.scheduleWithFixedDelay(AuctionApi::refresh, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
		EXECUTOR.scheduleWithFixedDelay(() -> fetchLivePage(0), 0, LIVE_REFRESH_MINUTES, TimeUnit.MINUTES);
	}

	// Per user report ("the lowest bin is like 3 mil and it says 5000 coins" — confirmed via a direct check
	// that a real, high-value BIN listing genuinely exists right now, ruling out "just hasn't been scanned
	// yet"): real bug found in the OLD version of this method — the recursive "fetch next page" call only
	// ever happened inside the SUCCESS branch (`.thenAccept`), so a single failed page (one transient
	// network hiccup, a timeout, one bad response out of ~50 sequential requests — a real, not-rare
	// possibility) silently truncated the ENTIRE REST of that pass. Every later page — and whatever it held
	// — went unscanned, every single 10-minute cycle, forever, if that same page kept failing the same way.
	// An item with only a couple of active listings (like dungeon armor pieces) could easily have its one
	// real BIN sitting on a page past whichever one happened to fail, meaning it would NEVER show up no
	// matter how many cycles ran. Now advances to the next page on failure too (just logging and skipping
	// the bad page) instead of stopping, so one bad page only ever costs itself.
	private static volatile int lastKnownTotalPages = 60;

	// Pages fetched one at a time, each awaited before the next starts — same deliberate natural throttle as
	// PetAuctionApi. Walks every real page Hypixel reports (totalPages, refreshed from any successful
	// response — lastKnownTotalPages starts at a generous fallback so a scan isn't understated before the
	// first real totalPages value has ever been seen), not a capped slice.
	private static void fetchLivePage(int page) {
		HttpJsonFetcher.fetchAsync(LIVE_URL_PREFIX + page).handle((json, error) -> {
			if (error != null) {
				SkyblockSimplified.LOGGER.warn("Failed to refresh live auction lowest-BIN prices (page " + page + "), skipping this page and continuing", error);
			} else {
				JsonElement aucEl = json.get("auctions");
				if (aucEl != null && aucEl.isJsonArray()) {
					for (JsonElement el : aucEl.getAsJsonArray()) processLiveAuction(el.getAsJsonObject());
				}
				if (json.has("totalPages")) lastKnownTotalPages = json.get("totalPages").getAsInt();
			}
			int nextPage = page + 1;
			if (nextPage < lastKnownTotalPages) {
				fetchLivePage(nextPage);
			} else {
				LIVE_SCAN_PASSES_COMPLETED.incrementAndGet();
			}
			return null;
		});
	}

	// Real bug found (per user report — "The auction data STILL isnt working", with a real ~3m BIN listing
	// confirmed to exist — the page-failure-resilience fix from last round couldn't have been the whole
	// story): confirmed via a live fetch of the actual `/v2/skyblock/auctions` endpoint that it has NO
	// "price" field at all — that key only exists on the DIFFERENT `/v2/skyblock/auctions_ended` endpoint
	// (used by refresh() above, correctly). The live endpoint's BIN price lives under "starting_bid" instead
	// (confirmed: for a bin=true listing, starting_bid IS the buy-it-now price, since there's no bidding to
	// start from). `auction.has("price")` was therefore ALWAYS false here, so every single live auction was
	// silently skipped — LIVE_LOWEST_BIN was never populated at all, regardless of how many scan passes ran
	// or how resilient the page-fetch loop was. This is the actual root cause, not a timing/coverage issue.
	private static void processLiveAuction(JsonObject auction) {
		if (!auction.has("bin") || !auction.get("bin").getAsBoolean()) return;
		if (!auction.has("starting_bid") || !auction.has("item_bytes")) return;
		double price = auction.get("starting_bid").getAsDouble();
		CompoundTag extraAttrs = decodeExtraAttributes(auction.get("item_bytes").getAsString());
		if (extraAttrs == null) return;
		String internalName = extraAttrs.getString("id").orElse(null);
		if (internalName == null) return;
		for (String key : variantKeysFor(internalName, extraAttrs)) {
			LIVE_LOWEST_BIN.merge(key, price, Math::min);
			LIVE_SAMPLE_COUNTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
		}
	}

	private static void refresh() {
		HttpJsonFetcher.fetchAsync(URL).thenAccept(json -> {
			JsonElement aucEl = json.get("auctions");
			if (aucEl == null || !aucEl.isJsonArray()) return;

			for (JsonElement el : aucEl.getAsJsonArray()) {
				JsonObject auction = el.getAsJsonObject();
				if (!auction.has("bin") || !auction.get("bin").getAsBoolean()) continue;
				if (!auction.has("price") || !auction.has("item_bytes")) continue;

				double price = auction.get("price").getAsDouble();
				CompoundTag extraAttrs = decodeExtraAttributes(auction.get("item_bytes").getAsString());
				if (extraAttrs == null) continue;
				String internalName = extraAttrs.getString("id").orElse(null);
				if (internalName == null) continue;

				for (String key : variantKeysFor(internalName, extraAttrs)) {
					Deque<Double> samples = RECENT_BIN_PRICES.computeIfAbsent(key, k -> new ArrayDeque<>());
					synchronized (samples) {
						samples.addLast(price);
						while (samples.size() > SAMPLE_CAP) samples.removeFirst();
					}
				}
			}
		}).exceptionally(e -> {
			SkyblockSimplified.LOGGER.warn("Failed to refresh Auction House ended-auction prices", e);
			return null;
		});
	}

	// Per user request ("Try to fix estimated item overlay... wither scrolls on a hyperion (wither impact,
	// wither shield, implosion, shadow warp)"): a plain "HYPERION" bucket lumps every scroll variant's real
	// sale price together, which is exactly as wrong for "what is THIS specific Hyperion worth" as the pet
	// bug below is for pets — a Shadow Warp Hyperion and a base (no-scroll/Wither Impact) Hyperion are not
	// the same market. Rather than inventing a guessed price delta per scroll (this codebase's own "never
	// guess a real value" rule), every sale is ALSO recorded under a real, separately-tracked bucket keyed
	// by its own actual scroll (or lack of one) — genuine market data, just correctly split instead of
	// averaged together. Same idea for Aspect of the Void's Etherwarp merge flag: a merged AOTV is a
	// meaningfully different, one-way-upgraded item from an unmerged one, so it gets its own real bucket
	// too. Every sale still ALSO updates the plain base-id bucket (unconditionally, below) so every other
	// item's existing pricing is completely unaffected.
	public static final String HYPERION_ID = "HYPERION";
	public static final String AOTV_ID = "ASPECT_OF_THE_VOID";

	private static java.util.List<String> variantKeysFor(String internalName, CompoundTag extraAttrs) {
		java.util.List<String> keys = new java.util.ArrayList<>(2);
		keys.add(internalName);
		if (HYPERION_ID.equals(internalName)) {
			net.minecraft.nbt.ListTag scrolls = extraAttrs.getListOrEmpty("ability_scroll");
			String scroll = scrolls.isEmpty() ? "NONE" : scrolls.getStringOr(0, "NONE");
			keys.add(internalName + "_SCROLL_" + scroll);
		} else if (AOTV_ID.equals(internalName)) {
			boolean merged = extraAttrs.contains("ethermerge") && extraAttrs.getIntOr("ethermerge", 0) != 0;
			if (merged) keys.add(internalName + "_ETHERMERGED");
		}
		return keys;
	}

	/** The real ExtraAttributes compound off a base64+gzip auction item_bytes blob, or null if it couldn't
	 *  be decoded (a single malformed/unexpected auction entry shouldn't sink the whole refresh). */
	private static CompoundTag decodeExtraAttributes(String base64ItemBytes) {
		try {
			byte[] bytes = Base64.getDecoder().decode(base64ItemBytes);
			CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
			return root.getListOrEmpty("i").getCompoundOrEmpty(0)
				.getCompoundOrEmpty("tag")
				.getCompoundOrEmpty("ExtraAttributes");
		} catch (Exception e) {
			return null;
		}
	}

	// Real bug found (per user report — "my hyperion says 18m value" — still wrong after a previous round
	// only gated the Hyperion/AOTV SPLIT variant bucket, not this method itself): the ended-auctions feed
	// returns only ~150 BIN sales total per 2-minute refresh across every item in the entire game, so a
	// median computed from just one or two samples is really just "whatever that one listing happened to be
	// priced at" (very often a lowball flip) — every caller of this method (crafting cost, chest rolling
	// ranking, the plain-bucket fallback every variant-priced item eventually falls through to) was trusting
	// that unconditionally. Now requires a minimum sample count before returning anything at all — a real
	// but too-thin sample means "not confident enough yet," same honest-null contract every other price
	// source in this codebase already follows, rather than exposing a number that just isn't representative.
	private static final int MIN_RELIABLE_SAMPLES = 3;

	/** The ended-sales median when there are enough real completed sales to trust it; otherwise the lowest
	 *  currently-listed live BIN price for this item, if any has been seen; otherwise null (still loading,
	 *  or a genuinely untraded item). Internal name matches NeuInternalName/SkyblockItemRepo's own id format. */
	public static AuctionPrice getPrice(String internalName) {
		Deque<Double> samples = RECENT_BIN_PRICES.get(internalName);
		if (samples != null && samples.size() >= MIN_RELIABLE_SAMPLES) {
			double[] sorted;
			synchronized (samples) {
				sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
			}
			double median = sorted.length % 2 == 1
				? sorted[sorted.length / 2]
				: (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0;
			return new AuctionPrice(median, sorted.length);
		}

		Double live = LIVE_LOWEST_BIN.get(internalName);
		if (live != null) {
			AtomicInteger countHolder = LIVE_SAMPLE_COUNTS.get(internalName);
			return new AuctionPrice(live, countHolder != null ? countHolder.get() : 1);
		}

		return null;
	}

	public static boolean isLoaded() {
		return !RECENT_BIN_PRICES.isEmpty() || !LIVE_LOWEST_BIN.isEmpty();
	}

	/** How many DISTINCT items currently have an ended-sales median tracked at all (not whether any one of
	 *  them individually meets {@link #MIN_RELIABLE_SAMPLES}) — a real diagnostic number, not a price. */
	public static int endedSalesItemsTracked() {
		return RECENT_BIN_PRICES.size();
	}

	/** How many distinct items currently have a live lowest-BIN price tracked. */
	public static int liveScanItemsTracked() {
		return LIVE_LOWEST_BIN.size();
	}

	/** How many full page-0-through-totalPages live-auction scans have completed since the mod started —
	 *  0 means the very first pass is still in progress (or hasn't started), so "no live-BIN price yet for
	 *  this specific item" is still ambiguous between "genuinely no active BIN listing" and "just hasn't
	 *  been scanned yet". */
	public static int liveScanPassesCompleted() {
		return LIVE_SCAN_PASSES_COMPLETED.get();
	}

}
