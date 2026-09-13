package com.cokelord.skyblocksimplified.feature;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FeatureRegistry {
	private static final Map<String, Feature> BY_ID = new LinkedHashMap<>();
	private static final List<Feature> FEATURES = new ArrayList<>();

	private FeatureRegistry() {}

	public static void register(Feature feature) {
		if (BY_ID.putIfAbsent(feature.getId(), feature) != null) {
			throw new IllegalStateException("Duplicate feature id: " + feature.getId());
		}
		FEATURES.add(feature);
	}

	/** Applies each feature's default enabled state, firing onEnable/onDisable as needed. Call once after registering all features, before loading saved config. */
	public static void applyDefaultStates() {
		for (Feature feature : FEATURES) {
			feature.setEnabled(feature.isEnabledByDefault());
		}
	}

	public static Feature get(String id) {
		return BY_ID.get(id);
	}

	public static List<Feature> all() {
		return Collections.unmodifiableList(FEATURES);
	}

	// Throttles the error log for a feature whose onTick() keeps throwing every single tick (20/sec) —
	// still visible for debugging, just not spamming thousands of identical lines a minute.
	private static final Map<String, Long> lastTickErrorLogMillis = new LinkedHashMap<>();
	private static final long TICK_ERROR_LOG_INTERVAL_MILLIS = 10_000;

	public static void tickAll(Minecraft client) {
		// Per user request ("The gui elements page also seems to lag a LOT. Make sure stuff doesnt update or
		// anything when editing gui elements. Make sure they do update when leaving the editing however so
		// stuff like splits doesnt get offset because it was paused while the user was editing gui"): every
		// feature's per-tick work is skipped outright while the HUD position editor is open — that screen's
		// own per-frame render-every-widget pass was stacking directly on top of the mod's usual full tick
		// load, not replacing any of it, which is the real lag source. Safe to skip outright (not just
		// throttle): this project's own established convention for anything duration/elapsed-time-based is a
		// real wall-clock System.currentTimeMillis() timestamp (see SplitsFeature's own doc comment on this
		// exact point), not a per-tick counter — resuming ticks after any gap, long or short, recomputes
		// correctly from that stored timestamp with nothing to "catch up" on, so nothing gets offset by
		// however long editing took.
		if (com.cokelord.skyblocksimplified.gui.HudEditScreen.isOpen()) return;
		for (Feature feature : FEATURES) {
			// Non-toggleable features (isToggleable() == false) always tick: their enabled flag
			// is never exposed in the GUI, so it must never be able to gate anything either —
			// otherwise a stale config value could silently disable infrastructure with no way back.
			if (feature.isEnabled() || !feature.isToggleable()) {
				try {
					feature.onTick(client);
				} catch (Exception e) {
					// This loop used to have no per-feature isolation at all: one feature throwing an
					// uncaught exception (e.g. hitting a null/empty-state edge case only reachable under
					// specific live conditions) aborted the whole for-loop for that tick, meaning every
					// OTHER feature registered after it in FEATURES never got its onTick() called either —
					// for as long as the same condition kept recurring (every single tick), which reads
					// exactly like "every farming HUD widget disappears" with no per-feature toggle able to
					// fix it (the crash isn't in the widget that vanished, it's in whatever's ticking right
					// before it), and "they all reappear the moment X happens" once whatever transient
					// condition triggered the exception stops recurring.
					long now = System.currentTimeMillis();
					long lastLog = lastTickErrorLogMillis.getOrDefault(feature.getId(), 0L);
					if (now - lastLog > TICK_ERROR_LOG_INTERVAL_MILLIS) {
						lastTickErrorLogMillis.put(feature.getId(), now);
						SkyblockSimplified.LOGGER.error("{} onTick() threw, skipping this tick", feature.getId(), e);
					}
				}
			}
		}
	}
}
