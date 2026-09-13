package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

/**
 * Hides arrow entities — a dedicated class (not the generic {@link EntityHideFeature}) per user request for
 * an "Only hide arrows on ground/in players" sub-toggle: {@code AbstractArrow.isInGround()} is protected
 * (no accessor exposed anywhere else in this codebase, not worth a Mixin just for this), so "landed"
 * (stuck in the ground OR stuck in whatever entity it hit) is approximated by near-zero velocity instead —
 * an arrow still actively flying has real, non-trivial delta movement every tick; one that's landed doesn't,
 * regardless of whether it landed in a block or a mob.
 */
public class HideArrowsFeature extends Feature {
	private static final double STATIONARY_THRESHOLD_SQR = 0.0025;

	private boolean onlyGroundOrPlayers = false;
	private boolean registered = false;

	public HideArrowsFeature() {
		super("performance_hide_arrows", "Hide Arrows", FeatureCategory.PERFORMANCE, false);
	}

	private boolean matches(Entity entity) {
		if (!(entity instanceof AbstractArrow)) return false;
		if (!onlyGroundOrPlayers) return true;
		return entity.getDeltaMovement().lengthSqr() < STATIONARY_THRESHOLD_SQR;
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			EntityHideRegistry.setRule(getId(), this::matches);
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			EntityHideRegistry.clearRule(getId());
			registered = false;
		}
	}

	public boolean isOnlyGroundOrPlayers() { return onlyGroundOrPlayers; }
	public void setOnlyGroundOrPlayers(boolean value) { onlyGroundOrPlayers = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("onlyGroundOrPlayers", onlyGroundOrPlayers);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("onlyGroundOrPlayers")) onlyGroundOrPlayers = obj.get("onlyGroundOrPlayers").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Hides arrow entities in the world, with an option to only hide ones already stuck in the ground or in players.";
	}
}
