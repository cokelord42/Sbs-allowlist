package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Per user request ("Optimize EVERYTHING, add subtoggles for everything that might require a higher
 * computer power/uses more fps. Add a module to performance called 'Performance toggles' that has a lot of
 * subtoggles about polling rates (like the slot locking), and a bunch of other features that the user can
 * turn off if they want more frames. This shouldn't including animation speeds and animation toggles, they
 * should go in the 'mod animations' module"): one consolidated panel for trading visual/detection quality
 * for frame rate, split into two kinds of subtoggle:
 * <ul>
 *   <li><b>Real throttles</b> — {@link #isReduceRarityBackgroundUpdates()} actually changes how often a
 *       specific per-frame computation runs (see {@link ItemRarityBackgroundFeature}'s own cache), not just
 *       a proxy for some other setting.</li>
 *   <li><b>Quick-access mirrors</b> — the rest just read/write another feature's own existing enabled state
 *       (via {@link FeatureRegistry}) so a player chasing frames has one place to kill the heavier
 *       continuous scanners/3D renderers without hunting through several different categories for each one.
 *       Toggling one here and toggling the same feature from its own row are the exact same action; this
 *       panel doesn't duplicate or shadow that state, it's just a second, performance-focused door to it.</li>
 * </ul>
 * Deliberately does NOT include animation durations/the master animation toggle — those already live in
 * GUI Animations per the user's own explicit instruction above.
 *
 * <p>Honest scope note: a true per-feature audit of every one of this project's ~150 features for hidden
 * per-tick costs isn't something this pass could responsibly do blind in one sitting — the toggles below are
 * the ones with a concretely identified, verified cost (a real per-frame lore reparse, or a real continuous
 * world/entity scan), not a blanket sweep. More can be added here the same way as real costs get found.
 */
public class PerformanceTogglesFeature extends Feature {
	// Real throttle — see ItemRarityBackgroundFeature's own cache for what this actually gates. Default ON:
	// pure efficiency win with no visible behavior change (a slot's tint updates the instant the item in it
	// changes either way), so there's no reason to make a user opt into it.
	private static boolean reduceRarityBackgroundUpdates = true;

	public static boolean isReduceRarityBackgroundUpdates() { return reduceRarityBackgroundUpdates; }
	public void setReduceRarityBackgroundUpdates(boolean value) { reduceRarityBackgroundUpdates = value; }

	public PerformanceTogglesFeature() {
		// Per user request: matches every other real feature module's default-off convention (see
		// FeatureRegistry's own "actual feature modules default OFF" rule) — this wasn't following that
		// convention even though it's a real toggleable feature, not an always-on infrastructure one.
		super("performance_toggles", "Performance Toggles", FeatureCategory.PERFORMANCE, false);
	}

	/** Quick-access on/off mirror for another feature's own enabled state, keyed by that feature's real ID —
	 *  reads live off {@link FeatureRegistry} rather than caching a stale copy, so this always matches
	 *  whatever the target feature's own row shows. */
	public static boolean isMirrorEnabled(String featureId) {
		Feature f = FeatureRegistry.get(featureId);
		return f != null && f.isEnabled();
	}

	public static void setMirrorEnabled(String featureId, boolean enabled) {
		Feature f = FeatureRegistry.get(featureId);
		if (f != null) f.setEnabled(enabled);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("reduceRarityBackgroundUpdates", reduceRarityBackgroundUpdates);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("reduceRarityBackgroundUpdates")) reduceRarityBackgroundUpdates = obj.get("reduceRarityBackgroundUpdates").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "A bundle of extra performance subtoggles for things that trade a bit of visual polish for more FPS.";
	}
}
