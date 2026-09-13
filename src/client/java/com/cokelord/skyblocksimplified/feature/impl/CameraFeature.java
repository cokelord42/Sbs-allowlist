package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Two independent camera tweaks, gated behind one toggle:
 * <ul>
 * <li>{@link #isDisableFrontView()} — skips {@code THIRD_PERSON_FRONT} when F5 cycles perspective (see
 * {@code CameraTypeMixin}), so pressing F5 only ever alternates between first-person and normal
 * over-the-shoulder third person.
 * <li>{@link #getMaxDistance()} — the third-person camera's max backward distance (see {@code
 * CameraMixin}), which vanilla effectively fixes at 4 blocks. Purely a local render-side value: the
 * mixin rewrites the argument to {@code Camera.getMaxZoom}, which still raycasts against blocks and
 * clips to the nearest obstruction, so this can never let the camera clip through walls — it only
 * extends how far it can pull back in open space. Deliberately NOT implemented via the real {@code
 * minecraft:camera_distance} attribute, since that's server-syncable and Hypixel owns it; mutating it
 * locally would just get overwritten by the next attribute-update packet.
 * </ul>
 */
public class CameraFeature extends Feature {
	// Matches the real minecraft:camera_distance attribute's own declared range (base 4, [0,32]) so the
	// slider can't request something vanilla's own raycast distance concept wouldn't otherwise support.
	private static final float MIN_DISTANCE = 4f;
	private static final float MAX_DISTANCE = 32f;
	private static final float DEFAULT_DISTANCE = 4f;

	private boolean disableFrontView = true;
	private float maxDistance = DEFAULT_DISTANCE;

	public CameraFeature() {
		super("camera", "Camera", FeatureCategory.PERFORMANCE, false);
	}

	public boolean isDisableFrontView() {
		return disableFrontView;
	}

	public void setDisableFrontView(boolean disableFrontView) {
		this.disableFrontView = disableFrontView;
	}

	public float getMaxDistance() {
		return maxDistance;
	}

	public void setMaxDistance(float maxDistance) {
		this.maxDistance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, maxDistance));
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("disable_front_view", disableFrontView);
		obj.addProperty("max_distance", maxDistance);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("disable_front_view")) disableFrontView = obj.get("disable_front_view").getAsBoolean();
		if (obj.has("max_distance")) setMaxDistance(obj.get("max_distance").getAsFloat());
	}

	@Override
	public String getDescription() {
		return "Two camera tweaks: skip the front-facing third-person view when cycling perspective with F5, and extend the back-view camera distance.";
	}
}
