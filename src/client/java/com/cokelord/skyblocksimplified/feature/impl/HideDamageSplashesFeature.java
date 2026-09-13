package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.combat.BossProximityDetector;
import com.cokelord.skyblocksimplified.combat.DamageSplashDetector;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Replaces the old plain "Hide Damage Splash" toggle: the settings cog now holds two independent scoping
 * toggles ("only near slayer bosses" / "only near dungeon bosses", either/both/neither) instead of a
 * second full-Feature duplicate module for the slayer-only case. With both off, every damage splash on
 * the island is hidden (previous, default behavior); with either on, hiding is scoped to when a matching
 * boss is nearby.
 */
public class HideDamageSplashesFeature extends Feature {
	private boolean onlyNearSlayerBosses = false;
	private boolean onlyNearDungeonBosses = false;

	public HideDamageSplashesFeature() {
		super("hide_damage_splash", "Hide Damage Splashes", FeatureCategory.COMBAT, false);
	}

	// Combat has real sub-tabs (Combat/Slayers/Dungeons/Kuudra/Crimson Isle) — a feature with no
	// subcategory match here is permanently invisible (MainScreen only shows a feature under whichever
	// tab is currently selected, and the general "Combat" tab is the default selection), which is exactly
	// why this module disappeared from the GUI once Combat grew sub-tabs.
	@Override
	public String getSubcategory() {
		return "Combat";
	}

	@Override
	protected void onEnable() {
		EntityHideRegistry.setRule(getId(), entity -> {
			if (!DamageSplashDetector.isDamageSplash(entity)) return false;
			if (!onlyNearSlayerBosses && !onlyNearDungeonBosses) return true;
			if (onlyNearSlayerBosses && BossProximityDetector.isNearSlayerBoss()) return true;
			return onlyNearDungeonBosses && BossProximityDetector.isNearDungeonBoss();
		});
	}

	@Override
	protected void onDisable() {
		EntityHideRegistry.clearRule(getId());
	}

	public boolean isOnlyNearSlayerBosses() {
		return onlyNearSlayerBosses;
	}

	public void setOnlyNearSlayerBosses(boolean v) {
		this.onlyNearSlayerBosses = v;
	}

	public boolean isOnlyNearDungeonBosses() {
		return onlyNearDungeonBosses;
	}

	public void setOnlyNearDungeonBosses(boolean v) {
		this.onlyNearDungeonBosses = v;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("onlyNearSlayerBosses", onlyNearSlayerBosses);
		obj.addProperty("onlyNearDungeonBosses", onlyNearDungeonBosses);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("onlyNearSlayerBosses")) onlyNearSlayerBosses = obj.get("onlyNearSlayerBosses").getAsBoolean();
		if (obj.has("onlyNearDungeonBosses")) onlyNearDungeonBosses = obj.get("onlyNearDungeonBosses").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Hides the floating damage numbers that appear when something takes damage, with options to scope it to slayers or dungeon bosses only.";
	}
}
