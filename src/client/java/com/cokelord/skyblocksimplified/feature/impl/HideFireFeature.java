package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Hides the first-person "on fire" screen overlay (ScreenEffectRenderer's dedicated submitFire — a
 * self-contained private method separate from block/underwater overlays, confirmed via the client jar's
 * method list, so cancelling it can't affect anything else that renders through the same class). The
 * "Hide fire on entities as well" sub-toggle additionally cancels FlameFeatureRenderer's whole per-frame
 * batch (buildGroup) — the flame model wrapped around any burning entity (mobs, other players, and your
 * own third-person model) — kept as a separate toggle since some players want their own fire hidden but
 * still want to see at a glance which mobs are burning.
 */
public class HideFireFeature extends Feature {
	private boolean hideOnEntities = false;

	public HideFireFeature() {
		super("hide_fire", "Hide Fire", FeatureCategory.COMBAT, false);
	}

	@Override
	public String getSubcategory() {
		return "Combat";
	}

	public boolean isHideOnEntities() {
		return hideOnEntities;
	}

	public void setHideOnEntities(boolean hideOnEntities) {
		this.hideOnEntities = hideOnEntities;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("hideOnEntities", hideOnEntities);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("hideOnEntities")) hideOnEntities = obj.get("hideOnEntities").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Hides the full-screen fire overlay that normally covers your view while you're on fire.";
	}
}
