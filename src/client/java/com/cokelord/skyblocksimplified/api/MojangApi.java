package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Real, public, keyless Mojang endpoint — {@code api.mojang.com/users/profiles/minecraft/<name>} — used to
 * turn a Party Finder join line's plain username into the real UUID {@link SkyblockStatsApi} needs (a
 * joiner isn't guaranteed to already be in the local tab list at the moment their join line arrives, so
 * that shortcut can't be relied on). No API key, same trust level as any other public Mojang lookup.
 *
 * <p>Confirmed still live and returning real data as of this round's investigation (per user report — "The
 * join line sends... just not the hover for info part") — a live curl against this exact endpoint returned
 * a normal 200 with valid JSON, ruling out "this endpoint is dead" as the cause. The real failure point is
 * more likely downstream, in {@link SkyblockStatsApi}'s own fetch to the user's Cloudflare Worker — see the
 * throttled diagnostic in {@code PartyFinderFeature#announce}, which distinguishes a null {@code uuid}
 * (this class never resolved) from a null {@code stats} with a real uuid (the Worker fetch is the one
 * failing) — that's the next real signal to check.
 */
public final class MojangApi {
	private static final String BASE_URL = "https://api.mojang.com/users/profiles/minecraft/";

	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	private MojangApi() {}

	/** Resolves {@code username} to a real UUID (no dashes) and hands it to {@code onResolved} — called on
	 *  whatever thread the HTTP response lands on, same as every other API class in this package; callers
	 *  that touch client state must hop back to the main thread themselves. Silently does nothing on
	 *  failure/unknown username, same "no enrichment available" contract as this package's other APIs. */
	public static void resolve(String username, Consumer<String> onResolved) {
		String key = username.toLowerCase(Locale.ROOT);
		String cached = CACHE.get(key);
		if (cached != null) { onResolved.accept(cached); return; }
		if (!IN_FLIGHT.add(key)) return;
		HttpJsonFetcher.fetchAsync(BASE_URL + username).thenAccept(json -> {
			try {
				String uuid = extractUuid(json);
				if (uuid != null) {
					CACHE.put(key, uuid);
					onResolved.accept(uuid);
				}
			} finally {
				IN_FLIGHT.remove(key);
			}
		}).exceptionally(e -> {
			IN_FLIGHT.remove(key);
			SkyblockSimplified.LOGGER.warn("MojangApi: resolve failed for {}", username, e);
			return null;
		});
	}

	/** Cache-only lookup — null if never resolved (or still in flight). */
	public static String get(String username) {
		return CACHE.get(username.toLowerCase(Locale.ROOT));
	}

	private static String extractUuid(JsonObject json) {
		if (json == null || !json.has("id") || !json.get("id").isJsonPrimitive()) return null;
		return json.get("id").getAsString();
	}
}
