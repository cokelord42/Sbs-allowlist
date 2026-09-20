package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.impl.puzzle.QuizSolverFeature;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Per user request ("Make a new performance feature called 'Hide nametags' which force hides all
 * nametags, add a subtoggle for only in dungeons aswell"): a blanket per-tick {@code setCustomNameVisible
 * (false)} sweep over every entity with a custom nametag — the same non-destructive visibility toggle
 * {@link StarredMobHighlightFeature} and {@link InactiveWaypointsFeature} already use for their own
 * narrower hides, just applied unconditionally here instead of gated on a specific mob/label match.
 *
 * <p>Only the visibility FLAG is ever touched, never the underlying name text/component itself — every
 * feature that detects things by reading a nametag's actual string ({@link StarredMobHighlightFeature},
 * {@link InactiveWaypointsFeature}'s own scan, {@link NametagGlyphIndicatorFeature},
 * {@link DamageTruncatorFeature}, {@link TreeProgressDisplayFeature}, {@code DungeonObjectPredicates})
 * reads {@code getName()}/{@code getCustomName()} directly, which this never modifies — so this feature
 * can never blind any of those detectors regardless of tick order.
 *
 * <p>Per the user's explicit "should not try to overpower stuff like the inactive terminals title hider":
 * {@link InactiveWaypointsFeature} is the one other place in this codebase that ever sets a nametag's
 * visibility back to TRUE (its own {@code hideDefaultNames} subtoggle, applied every frame in its render
 * method) — without an exemption, this feature's blanket false-every-tick sweep would fight that and
 * flicker. {@link InactiveWaypointsFeature#isManagingNameVisibility} lets this skip anything that feature
 * is actively managing, leaving its own toggle as the sole authority over those specific stands.
 */
public class HideNametagsFeature extends Feature {
	private boolean onlyInDungeons = false;
	private static boolean listenersRegistered = false;
	private static HideNametagsFeature instance;

	public HideNametagsFeature() {
		super("performance_hide_nametags", "Hide Nametags", FeatureCategory.PERFORMANCE, false);
		instance = this;
	}

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		// Real bug found (per user report — "Nametags hider doesn't update fast enough"): a client TICK
		// only runs 20 times/second, but a freshly-loaded or newly-visible entity can render several frames
		// before the next tick ever fires (worse under high FPS, or right as an entity re-enters render
		// range) — a real, visible flash of its nametag every time. Switched from onTick to this feature's
		// own per-RENDER-FRAME hook (World3DRenderer's callback list, the same one this codebase's other
		// nametag-visibility managers — InactiveWaypointsFeature's renderInner, QuizSolverFeature — already
		// reassert their own visibility flag from every frame instead of once per tick), so the hide is
		// reapplied as often as the game actually draws, not once every 50ms.
		com.cokelord.skyblocksimplified.highlight.World3DRenderer.addRenderCallback(() -> {
			if (instance != null && instance.isEnabled()) instance.hideAll();
		});
	}

	private void hideAll() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		if (onlyInDungeons && !DungeonState.isInDungeon()) return;

		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!entity.hasCustomName() || !entity.isCustomNameVisible()) continue;
			if (InactiveWaypointsFeature.isManagingNameVisibility(entity)) continue;
			// Real bug found (per user report — Quiz Solver "hides all the nametags of the options meaning
			// it didn't leave the right one shown"): this blanket sweep had no exemption for the three quiz-
			// option NPCs, so with this toggle on it fought QuizSolverFeature's own per-tick visibility
			// management every frame and always won for the correct answer too. Same exemption pattern as
			// InactiveWaypointsFeature just above.
			if (QuizSolverFeature.isManagingNameVisibility(entity)) continue;
			entity.setCustomNameVisible(false);
		}
	}

	public boolean isOnlyInDungeons() { return onlyInDungeons; }
	public void setOnlyInDungeons(boolean value) { onlyInDungeons = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("onlyInDungeons", onlyInDungeons);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("onlyInDungeons")) onlyInDungeons = obj.get("onlyInDungeons").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Force-hides every entity's nametag, with an option to only do it inside dungeons.";
	}
}
