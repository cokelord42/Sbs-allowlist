package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Jacob's Farming Contest schedule for the current SkyBlock year — backed by the same public API
 * SkyHanni uses (EliteDevApi.fetchUpcomingContests, {@code GET https://api.eliteskyblock.com/contests/at/now}),
 * since contest timing/crops aren't derivable client-side and SkyHanni's own comment confirms this ("the
 * full feature also reads... an external API"). Response shape: {@code {"contests": {"<unix-seconds>":
 * ["Crop1","Crop2","Crop3"], ...}}} — each contest slot runs for exactly one SkyBlock day (20 real minutes).
 */
public final class JacobContestApi {
	public record ContestSlot(long startEpochMillis, List<String> crops) {
		public long endEpochMillis() {
			return startEpochMillis + 20 * 60_000L;
		}

		public boolean isActive(long nowMillis) {
			return nowMillis >= startEpochMillis && nowMillis < endEpochMillis();
		}
	}

	private static final String URL = "https://api.eliteskyblock.com/contests/at/now";
	// Contest slots are 20 real minutes apart — a few-minute refresh is frequent enough to always have
	// the next slot loaded well before it starts, without hammering the endpoint every tick.
	private static final long REFRESH_MINUTES = 5;

	private static volatile List<ContestSlot> contests = List.of();
	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-jacob-contest-fetch");
		t.setDaemon(true);
		return t;
	});
	private static boolean started = false;

	private JacobContestApi() {}

	public static synchronized void start() {
		if (started) return;
		started = true;
		EXECUTOR.scheduleWithFixedDelay(JacobContestApi::refresh, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
	}

	private static void refresh() {
		HttpJsonFetcher.fetchAsync(URL).thenAccept(json -> {
			JsonElement contestsEl = json.get("contests");
			if (contestsEl == null || !contestsEl.isJsonObject()) return;
			JsonObject contestsObj = contestsEl.getAsJsonObject();

			List<ContestSlot> parsed = new ArrayList<>();
			for (var entry : contestsObj.entrySet()) {
				long epochSeconds;
				try {
					epochSeconds = Long.parseLong(entry.getKey());
				} catch (NumberFormatException e) {
					continue;
				}
				if (!entry.getValue().isJsonArray()) continue;
				JsonArray cropsArr = entry.getValue().getAsJsonArray();
				List<String> crops = new ArrayList<>();
				for (JsonElement el : cropsArr) crops.add(el.getAsString());
				if (crops.isEmpty()) continue;
				parsed.add(new ContestSlot(epochSeconds * 1000L, crops));
			}
			parsed.sort(Comparator.comparingLong(ContestSlot::startEpochMillis));
			contests = parsed;
		}).exceptionally(e -> {
			SkyblockSimplified.LOGGER.warn("Failed to refresh Jacob's Contest schedule", e);
			return null;
		});
	}

	/** The contest running right now, or null if none is active (between contests). */
	public static ContestSlot getActiveContest() {
		long now = System.currentTimeMillis();
		for (ContestSlot slot : contests) {
			if (slot.isActive(now)) return slot;
		}
		return null;
	}

	/** The soonest upcoming contest, or null if the schedule hasn't loaded yet. */
	public static ContestSlot getNextContest() {
		long now = System.currentTimeMillis();
		ContestSlot best = null;
		for (ContestSlot slot : contests) {
			if (slot.startEpochMillis() > now && (best == null || slot.startEpochMillis() < best.startEpochMillis())) {
				best = slot;
			}
		}
		return best;
	}

	public static boolean isLoaded() {
		return !contests.isEmpty();
	}
}
