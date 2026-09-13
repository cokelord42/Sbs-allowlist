package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Master on/off for Custom Keybinds — now also carries a mouse-sensitivity multiplier (1.0 = normal,
 * 0.0 = camera doesn't turn at all) applied while the remap is active, per user request.
 */
public class CustomKeybindsMasterFeature extends Feature {
	private float sensitivity = 1.0f;

	public CustomKeybindsMasterFeature() {
		super("custom_keybinds", "Custom Keybinds", FeatureCategory.FARMING, false);
	}

	@Override
	public String getSubcategory() {
		return "Custom Keybinds";
	}

	public float getSensitivity() {
		return sensitivity;
	}

	public void setSensitivity(float sensitivity) {
		this.sensitivity = Math.max(0f, Math.min(1f, sensitivity));
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("sensitivity", sensitivity);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("sensitivity")) sensitivity = obj.get("sensitivity").getAsFloat();
	}

	@Override
	public String getDescription() {
		return "Master switch for Custom Keybinds, with an adjustable mouse-sensitivity multiplier while a remap is active.";
	}
}
