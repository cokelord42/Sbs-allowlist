package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * "Only when holding these items" — Hoe / Fishing Rod, each independently toggleable, shown in the
 * settings cog. The Pest Vacuum toggle was removed per user request: Hypixel nerfed the vacuum item
 * (Buzzy Bee) so the near-0 mouse sensitivity this whole subsystem existed for isn't needed with it
 * anymore.
 */
public class CustomKeybindsHoldingOnlyFeature extends Feature {
	private boolean hoe = true;
	private boolean fishingRod = true;

	public CustomKeybindsHoldingOnlyFeature() {
		super("custom_keybinds_holding_only", "Only when holding these items", FeatureCategory.FARMING, false);
	}

	@Override
	public String getSubcategory() {
		return "Custom Keybinds";
	}

	public boolean isHoe() { return hoe; }
	public void setHoe(boolean v) { hoe = v; }
	public boolean isFishingRod() { return fishingRod; }
	public void setFishingRod(boolean v) { fishingRod = v; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("hoe", hoe);
		obj.addProperty("fishingRod", fishingRod);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("hoe")) hoe = obj.get("hoe").getAsBoolean();
		if (obj.has("fishingRod")) fishingRod = obj.get("fishingRod").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Sub-setting for Custom Keybinds: only remaps your keys while holding specific tools (Hoe, Fishing Rod).";
	}
}
