package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Per user request ("Optimize EVERYTHING, add subtoggles for everything that might require a higher
 * computer power/uses more fps. Add a module to performance called 'Performance toggles' that has a lot of
 * subtoggles about polling rates (like the slot locking), and a bunch of other features that the user can
 * turn off if they want more frames. This shouldn't including animation speeds and animation toggles, they
 * should go in the 'mod animations' module"): one consolidated panel for trading visual/detection quality
 * for frame rate, split into two kinds of subtoggle (quick-access on/off mirrors of other modules used to live here too —
 * removed, since switching one off read like a performance option but silently disabled the real module):
 * <ul>
 *   <li><b>Free real throttles</b> — {@link #isReduceRarityBackgroundUpdates()} actually changes how often a
 *       specific per-frame computation runs (see {@link ItemRarityBackgroundFeature}'s own cache), not just
 *       a proxy for some other setting, with NO visible behavior change either way — a pure efficiency win,
 *       so it defaults on.</li>
 *   <li><b>Tradeoff real throttles</b> — {@link #isThrottleNametagHiding()}/{@link #isThrottleMaxorScan()}
 *       also change how often a specific per-frame/per-tick scan runs, but unlike the free throttle above,
 *       turning them on has a real, if minor, user-visible cost (documented in each toggle's own row
 *       tooltip in MainScreen) — so unlike the free throttle, these default OFF, matching this panel's own
 *       "opt into a visible tradeoff for more frames" framing rather than silently changing behavior.</li>
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

	// Tradeoff throttles — see this class's own doc comment for why these default OFF (unlike the free
	// throttle above) and MainScreen's own row tooltips for each one's real, user-visible cost.
	private static boolean throttleNametagHiding = false;
	private static boolean throttleMaxorScan = false;

	public static boolean isThrottleNametagHiding() { return throttleNametagHiding; }
	public void setThrottleNametagHiding(boolean value) { throttleNametagHiding = value; }

	public static boolean isThrottleMaxorScan() { return throttleMaxorScan; }
	public void setThrottleMaxorScan(boolean value) { throttleMaxorScan = value; }

	public PerformanceTogglesFeature() {
		// Per user request: matches every other real feature module's default-off convention (see
		// FeatureRegistry's own "actual feature modules default OFF" rule) — this wasn't following that
		// convention even though it's a real toggleable feature, not an always-on infrastructure one.
		super("performance_toggles", "Performance Toggles", FeatureCategory.PERFORMANCE, false);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("reduceRarityBackgroundUpdates", reduceRarityBackgroundUpdates);
		obj.addProperty("throttleNametagHiding", throttleNametagHiding);
		obj.addProperty("throttleMaxorScan", throttleMaxorScan);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("reduceRarityBackgroundUpdates")) reduceRarityBackgroundUpdates = obj.get("reduceRarityBackgroundUpdates").getAsBoolean();
		if (obj.has("throttleNametagHiding")) throttleNametagHiding = obj.get("throttleNametagHiding").getAsBoolean();
		if (obj.has("throttleMaxorScan")) throttleMaxorScan = obj.get("throttleMaxorScan").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "A bundle of extra performance subtoggles for things that trade a bit of visual polish for more FPS.";
	}
}
