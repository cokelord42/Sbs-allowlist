package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live Bazaar buy/sell prices, keyed by Hypixel's own bazaar product id (e.g. "ENCHANTED_CARROT",
 * "INK_SACK:3"). Backed by the public, keyless `/v2/skyblock/bazaar` endpoint — no NEU repo clone or
 * API key needed, refreshed on a background timer since it's the same data every player would see.
 */
public final class BazaarApi {
	public record BazaarPrice(double buyPrice, double sellPrice) {}

	private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";
	// Bazaar prices move continuously but not so fast that a couple minutes of staleness matters for
	// the profit/shopping-list style features this backs — matches NEU/SkyHanni's own refresh cadence.
	private static final long REFRESH_MINUTES = 2;

	private static final Map<String, BazaarPrice> PRICES = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-bazaar-fetch");
		t.setDaemon(true);
		return t;
	});
	private static boolean started = false;

	private BazaarApi() {}

	public static synchronized void start() {
		if (started) return;
		started = true;
		EXECUTOR.scheduleWithFixedDelay(BazaarApi::refresh, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
	}

	private static void refresh() {
		HttpJsonFetcher.fetchAsync(URL).thenAccept(json -> {
			var productsEl = json.get("products");
			if (productsEl == null || !productsEl.isJsonObject()) return;
			JsonObject products = productsEl.getAsJsonObject();

			Map<String, BazaarPrice> next = new HashMap<>();
			for (Entry<String, com.google.gson.JsonElement> entry : products.entrySet()) {
				JsonObject quickStatus = entry.getValue().getAsJsonObject().getAsJsonObject("quick_status");
				if (quickStatus == null) continue;
				double buy = quickStatus.get("buyPrice").getAsDouble();
				double sell = quickStatus.get("sellPrice").getAsDouble();
				next.put(entry.getKey(), new BazaarPrice(buy, sell));
			}
			PRICES.putAll(next);
		}).exceptionally(e -> {
			SkyblockSimplified.LOGGER.warn("Failed to refresh Bazaar prices", e);
			return null;
		});
	}

	/** Null if the product id isn't on the bazaar (e.g. it's an NPC-only or AH-only item) or prices
	 *  haven't loaded yet. */
	public static BazaarPrice getPrice(String productId) {
		return PRICES.get(productId);
	}

	public static boolean isLoaded() {
		return !PRICES.isEmpty();
	}
}
