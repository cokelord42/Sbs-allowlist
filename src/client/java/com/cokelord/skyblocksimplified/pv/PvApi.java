package com.cokelord.skyblocksimplified.pv;

import com.cokelord.skyblocksimplified.api.HttpJsonFetcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player Viewer fetches. Everything keyed goes through the same Cloudflare Worker SkyblockStatsApi uses (the
 * Worker holds the Hypixel API key). {@code &type=all} asks the Worker for profiles + online status + player
 * record in one round trip; an older Worker that ignores {@code type} just returns the profiles shape, which
 * is handled the same way (status/player simply come back null).
 */
public final class PvApi {
	private static final String WORKER = "https://skyblock-stats-proxy.cokelordthe42nd-748.workers.dev/";
	private static final long CACHE_MILLIS = 60_000L;

	public record PvData(String uuid, String name, JsonArray profiles, JsonObject status, JsonObject player, long fetchedAt) {}

	private static final Map<String, PvData> CACHE = new ConcurrentHashMap<>();

	private PvApi() {}

	/** Resolves {@code name} (or uses the given uuid directly when known) and loads that player's data. */
	public static CompletableFuture<PvData> load(String name, String knownUuid) {
		PvRepo.ensureLoaded();
		com.cokelord.skyblocksimplified.api.SkyblockTextureMapApi.ensureLoaded();
		CompletableFuture<String[]> resolved = knownUuid != null
			? CompletableFuture.completedFuture(new String[]{knownUuid.replace("-", "").toLowerCase(Locale.ROOT), name})
			: HttpJsonFetcher.fetchAsync("https://api.mojang.com/users/profiles/minecraft/" + name)
				.thenApply(json -> new String[]{json.get("id").getAsString().toLowerCase(Locale.ROOT), json.get("name").getAsString()});
		return resolved.thenCompose(idName -> {
			PvData cached = CACHE.get(idName[0]);
			if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < CACHE_MILLIS) return CompletableFuture.completedFuture(cached);
			return HttpJsonFetcher.fetchAsync(WORKER + "?type=all&uuid=" + idName[0]).thenApply(json -> {
				JsonArray profiles = json.get("profiles") instanceof JsonArray arr ? arr : new JsonArray();
				JsonObject status = json.get("status") instanceof JsonObject s ? s : null;
				JsonObject player = json.get("player") instanceof JsonObject p ? p : null;
				PvData data = new PvData(idName[0], idName[1], profiles, status, player, System.currentTimeMillis());
				CACHE.put(idName[0], data);
				return data;
			});
		});
	}
}
