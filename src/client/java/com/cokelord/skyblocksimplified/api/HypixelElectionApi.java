package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Current Skyblock mayor + active perks — ported from SkyHanni's ElectionApi.kt, which sources this from
 * Hypixel's own public, keyless resource endpoint (the exact same kind of endpoint SkyblockItemRepo already
 * uses for item data, so this follows that same fetch/cache/refresh shape) rather than parsing the
 * /calendar GUI or chat — no chat-based election tracking needed at all since Hypixel just publishes the
 * answer directly.
 */
public final class HypixelElectionApi {
	private static final String URL = "https://api.hypixel.net/v2/resources/skyblock/election";
	// Mayor changes at most once every real-life ~voting cycle — nowhere near frequent enough to need a
	// short refresh; matches SkyblockItemRepo's own "content, not moment-to-moment" reasoning.
	private static final long REFRESH_MINUTES = 10;

	public record MayorInfo(String name, List<String> perkNames) {}

	private static final AtomicReference<MayorInfo> CURRENT = new AtomicReference<>();
	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-election-fetch");
		t.setDaemon(true);
		return t;
	});
	private static boolean started = false;

	private HypixelElectionApi() {}

	public static synchronized void start() {
		if (started) return;
		started = true;
		EXECUTOR.scheduleWithFixedDelay(HypixelElectionApi::refresh, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
	}

	private static void refresh() {
		HttpJsonFetcher.fetchAsync(URL).thenAccept(json -> {
			JsonElement mayorEl = json.get("mayor");
			if (mayorEl == null || !mayorEl.isJsonObject()) return;
			JsonObject mayor = mayorEl.getAsJsonObject();
			String name = mayor.has("name") ? mayor.get("name").getAsString() : null;
			if (name == null) return;
			List<String> perkNames = new ArrayList<>();
			if (mayor.has("perks") && mayor.get("perks").isJsonArray()) {
				for (JsonElement perkEl : mayor.getAsJsonArray("perks")) {
					if (!perkEl.isJsonObject()) continue;
					JsonObject perk = perkEl.getAsJsonObject();
					if (perk.has("name")) perkNames.add(perk.get("name").getAsString());
				}
			}
			CURRENT.set(new MayorInfo(name, perkNames));
		}).exceptionally(e -> {
			SkyblockSimplified.LOGGER.warn("Failed to refresh the Hypixel election API", e);
			return null;
		});
	}

	/** Null until the first successful fetch completes. */
	public static MayorInfo currentMayor() {
		return CURRENT.get();
	}
}
