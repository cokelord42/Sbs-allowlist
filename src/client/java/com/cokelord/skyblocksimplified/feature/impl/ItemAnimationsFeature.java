package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * User-adjustable position/rotation/scale for the currently held first-person item, plus toggles to skip
 * the swing and item-swap animations entirely — real transform applied via ItemInHandRendererMixin/
 * DisableSwingMixin, on top of vanilla's own render (not a fabricated preview).
 */
public class ItemAnimationsFeature extends Feature {
	// Absolute swing-duration spec, not a multiplier on the item's own vanilla timing — see
	// DisableSwingMixin's getCurrentSwingDuration override for the actual formula (0.1 -> 10s full swing,
	// 1.0 -> instant/no swing, linear between).
	public static final float MIN_SWING_SPEED = 0.1f;
	public static final float MAX_SWING_SPEED = 1f;

	private float offsetX = 0f;
	private float offsetY = 0f;
	private float offsetZ = 0f;
	private float yaw = 0f;
	private float pitch = 0f;
	private float roll = 0f;
	private float scale = 1f;
	private boolean disableFullSwing = false;
	// Per user request ("The hand swinging thing is wrong. It should still swing, but the swing animation
	// should happen in place, i.e the sword itself shouldnt move places on the screen but the animation should
	// still trigger indicating a swing. Devonian has this, port theirs"): Devonian's own equivalent
	// ("inplaceSwing" — "swing animation only rotates item") cancels only the toward-crosshair translate,
	// leaving the rotation untouched — exactly the same translate-cancel Disable Full Swing already does (see
	// ItemInHandRendererMixin's cancelSwingTranslate), just without ALSO cancelling the rotate the way Disable
	// Full Swing does. Independent of disableFullSwing so either can be toggled on its own.
	private boolean inPlaceSwing = false;
	private boolean disableItemSwapping = false;
	private float swingSpeed = 1f;
	private boolean disableHandSwaying = false;

	public ItemAnimationsFeature() {
		super("item_animations", "Item Animations", FeatureCategory.INVENTORY, false);
	}

	@Override
	public String getSubcategory() {
		return "Inventory";
	}

	public float getOffsetX() { return offsetX; }
	public void setOffsetX(float v) { offsetX = v; }
	public float getOffsetY() { return offsetY; }
	public void setOffsetY(float v) { offsetY = v; }
	public float getOffsetZ() { return offsetZ; }
	public void setOffsetZ(float v) { offsetZ = v; }
	public float getYaw() { return yaw; }
	public void setYaw(float v) { yaw = v; }
	public float getPitch() { return pitch; }
	public void setPitch(float v) { pitch = v; }
	public float getRoll() { return roll; }
	public void setRoll(float v) { roll = v; }
	public float getScale() { return scale; }
	public void setScale(float v) { scale = Math.max(0.1f, Math.min(3f, v)); }
	public boolean isDisableFullSwing() { return disableFullSwing; }
	public void setDisableFullSwing(boolean v) { disableFullSwing = v; }
	public boolean isInPlaceSwing() { return inPlaceSwing; }
	public void setInPlaceSwing(boolean v) { inPlaceSwing = v; }
	public boolean isDisableItemSwapping() { return disableItemSwapping; }
	public void setDisableItemSwapping(boolean v) { disableItemSwapping = v; }
	public float getSwingSpeed() { return swingSpeed; }
	public void setSwingSpeed(float v) { swingSpeed = Math.max(MIN_SWING_SPEED, Math.min(MAX_SWING_SPEED, v)); }
	public boolean isDisableHandSwaying() { return disableHandSwaying; }
	public void setDisableHandSwaying(boolean v) { disableHandSwaying = v; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("offsetX", offsetX);
		obj.addProperty("offsetY", offsetY);
		obj.addProperty("offsetZ", offsetZ);
		obj.addProperty("yaw", yaw);
		obj.addProperty("pitch", pitch);
		obj.addProperty("roll", roll);
		obj.addProperty("scale", scale);
		obj.addProperty("disableFullSwing", disableFullSwing);
		obj.addProperty("inPlaceSwing", inPlaceSwing);
		obj.addProperty("disableItemSwapping", disableItemSwapping);
		obj.addProperty("swingSpeed", swingSpeed);
		obj.addProperty("disableHandSwaying", disableHandSwaying);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("offsetX")) offsetX = obj.get("offsetX").getAsFloat();
		if (obj.has("offsetY")) offsetY = obj.get("offsetY").getAsFloat();
		if (obj.has("offsetZ")) offsetZ = obj.get("offsetZ").getAsFloat();
		if (obj.has("yaw")) yaw = obj.get("yaw").getAsFloat();
		if (obj.has("pitch")) pitch = obj.get("pitch").getAsFloat();
		if (obj.has("roll")) roll = obj.get("roll").getAsFloat();
		if (obj.has("scale")) scale = obj.get("scale").getAsFloat();
		if (obj.has("disableFullSwing")) disableFullSwing = obj.get("disableFullSwing").getAsBoolean();
		if (obj.has("inPlaceSwing")) inPlaceSwing = obj.get("inPlaceSwing").getAsBoolean();
		if (obj.has("disableItemSwapping")) disableItemSwapping = obj.get("disableItemSwapping").getAsBoolean();
		if (obj.has("swingSpeed")) swingSpeed = obj.get("swingSpeed").getAsFloat();
		if (obj.has("disableHandSwaying")) disableHandSwaying = obj.get("disableHandSwaying").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Lets you adjust the position, rotation, and scale of your held first-person item, and optionally skip the swing/swap animations.";
	}
}
