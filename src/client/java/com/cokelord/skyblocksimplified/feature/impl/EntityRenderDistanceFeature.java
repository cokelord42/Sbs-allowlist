package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Culls rendering of entities beyond a configurable distance from the player — the single biggest lever
 * for frame time on a crowded island (Dungeons, Slayers, the Garden mid pest-wave), where dozens of mobs,
 * projectiles and dropped items can be loaded well outside anything actually visible on screen. Purely a
 * render-skip through EntityHideRegistry/EntityHideMixin — the same "shouldRender" hook every other Hide*
 * module in this project already uses — so the entity keeps ticking, keeps its hitbox, keeps existing;
 * only its model/texture draw call is skipped. Nothing about combat, loot, or mob AI changes.
 */
public class EntityRenderDistanceFeature extends Feature {
	private static final float MIN_DISTANCE = 8f;
	private static final float MAX_DISTANCE = 128f;
	private static final float DEFAULT_DISTANCE = 48f;

	private float distance = DEFAULT_DISTANCE;
	// Off by default: hiding other real players (party members, guildmates, other people around you) is a
	// bigger tradeoff than hiding mobs/projectiles/items, so this stays opt-in rather than folded into the
	// same default cull as everything else.
	private boolean includePlayers = false;
	private boolean registered = false;

	public EntityRenderDistanceFeature() {
		super("performance_entity_render_distance", "Entity Render Distance", FeatureCategory.PERFORMANCE, false);
	}

	public float getDistance() {
		return distance;
	}

	public void setDistance(float distance) {
		this.distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
	}

	public boolean isIncludePlayers() {
		return includePlayers;
	}

	public void setIncludePlayers(boolean includePlayers) {
		this.includePlayers = includePlayers;
	}

	@Override
	protected void onEnable() {
		if (registered) return;
		registered = true;
		EntityHideRegistry.setRule(getId(), this::shouldHide);
	}

	@Override
	protected void onDisable() {
		if (!registered) return;
		registered = false;
		EntityHideRegistry.clearRule(getId());
	}

	private boolean shouldHide(Entity entity) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || entity == player) return false;
		if (!includePlayers && entity instanceof Player) return false;
		// Squared-distance compare avoids a sqrt per candidate entity — this runs once per entity in the
		// render list, every frame, easily hundreds of times on a crowded island, so skipping distanceTo()'s
		// internal Math.sqrt in favor of one extra multiply on the threshold side actually matters here.
		return player.distanceToSqr(entity) > (double) distance * distance;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("distance", distance);
		obj.addProperty("include_players", includePlayers);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("distance")) setDistance(obj.get("distance").getAsFloat());
		if (obj.has("include_players")) includePlayers = obj.get("include_players").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Stops rendering entities beyond a distance you choose, to help FPS on crowded islands.";
	}
}
