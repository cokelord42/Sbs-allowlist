package com.cokelord.skyblocksimplified.farming;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks how many of each real Skyblock-farmable crop block the local player has broken recently, via
 * Fabric's own PlayerBlockBreakEvents (a real client-side block-break signal, not a guessed Hypixel
 * data format) — backs Money/Hour Display. This approximates SkyHanni's own collection-stat-based
 * money/hour (which reads Hypixel's actual per-second collection counter) with a simpler "raw blocks
 * broken per minute" rate; it won't account for Farming Fortune multiplying yield per break, so the
 * money/hour figure runs low compared to SkyHanni's, but it's a real measurement, not a fabricated one.
 *
 * Not thread-safe on purpose: PlayerBlockBreakEvents.AFTER and the client tick loop both run on the
 * client thread, so a plain (unsynchronized) ArrayDeque per crop is enough — no concurrent collection
 * overhead needed. Expired timestamps are pruned only when a new break comes in (rare: at most a few
 * times a second), not on every breaksPerSecond() read, which MoneyPerHourFeature calls once per crop
 * every tick (up to 160x/sec) — the old CopyOnWriteArrayList + per-read removeIf combination redid a
 * full copy-on-write pass on nearly every one of those calls for no reason, since almost none of them
 * land on the rare tick where anything has actually expired.
 */
public final class CropBreakTracker {
	private static final Map<Block, String> CROP_NAMES = new LinkedHashMap<>();
	private static final long WINDOW_MILLIS = 20_000;
	private static boolean listenerRegistered = false;

	private static final Map<String, Deque<Long>> BREAK_TIMESTAMPS = new HashMap<>();
	private static String lastBrokenCrop = null;
	private static long lastBrokenMillis = 0;

	static {
		CROP_NAMES.put(Blocks.WHEAT, "Wheat");
		CROP_NAMES.put(Blocks.CARROTS, "Carrot");
		CROP_NAMES.put(Blocks.POTATOES, "Potato");
		CROP_NAMES.put(Blocks.PUMPKIN, "Pumpkin");
		CROP_NAMES.put(Blocks.MELON, "Melon");
		CROP_NAMES.put(Blocks.SUGAR_CANE, "Sugar Cane");
		CROP_NAMES.put(Blocks.NETHER_WART, "Nether Wart");
		CROP_NAMES.put(Blocks.CACTUS, "Cactus");
		// Cocoa and the two mushroom types were missing entirely — breaking those never counted toward
		// Money/Hour at all, per user report.
		CROP_NAMES.put(Blocks.COCOA, "Cocoa Beans");
		CROP_NAMES.put(Blocks.BROWN_MUSHROOM, "Brown Mushroom");
		CROP_NAMES.put(Blocks.RED_MUSHROOM, "Red Mushroom");
	}

	private CropBreakTracker() {}

	public static void start() {
		if (listenerRegistered) return;
		listenerRegistered = true;
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			String cropName = CROP_NAMES.get(state.getBlock());
			if (cropName == null) return;
			long now = System.currentTimeMillis();
			Deque<Long> timestamps = BREAK_TIMESTAMPS.computeIfAbsent(cropName, k -> new ArrayDeque<>());
			timestamps.addLast(now);
			pruneExpired(timestamps, now);
			lastBrokenCrop = cropName;
			lastBrokenMillis = now;
		});
	}

	/** The crop display name of the most recently broken tracked crop block, or null if none yet. */
	public static String currentCrop() {
		return lastBrokenCrop;
	}

	/** Milliseconds since the last tracked crop block was broken, or Long.MAX_VALUE if none yet. */
	public static long millisSinceLastBreak() {
		return lastBrokenCrop == null ? Long.MAX_VALUE : System.currentTimeMillis() - lastBrokenMillis;
	}

	private static void pruneExpired(Deque<Long> timestamps, long now) {
		long cutoff = now - WINDOW_MILLIS;
		while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
			timestamps.pollFirst();
		}
	}

	/** Breaks per second over a recent rolling window, for a crop display name (e.g. "Wheat"). Read-only
	 *  — doesn't prune, since pruning already happens on insert and this is called far more often. */
	public static double breaksPerSecond(String cropName) {
		Deque<Long> timestamps = BREAK_TIMESTAMPS.get(cropName);
		if (timestamps == null || timestamps.isEmpty()) return 0;
		long cutoff = System.currentTimeMillis() - WINDOW_MILLIS;
		int count = 0;
		for (long timestamp : timestamps) {
			if (timestamp >= cutoff) count++;
		}
		return count / (WINDOW_MILLIS / 1000.0);
	}

	public static Iterable<String> trackedCropNames() {
		return CROP_NAMES.values();
	}

	public static String cropNameForBlock(Block block) {
		return CROP_NAMES.get(block);
	}
}
