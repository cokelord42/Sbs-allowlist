package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/**
 * A plain on/off toggle with no attached behavior yet — just a registered, persisted, correctly-placed
 * switch in the GUI. Used for module-list entries that need real Hypixel-specific detection (tab/
 * scoreboard parsing, item NBT, dungeon/slayer state machines, price APIs) that hasn't been built yet;
 * per project convention, that kind of data-dependent logic isn't guessed at without confirmed live
 * game text or the user's go-ahead to reference a specific format. Swap these for a real Feature
 * subclass once the actual detection is ready — the id stays the same so persisted state carries over.
 */
public class SimpleToggleFeature extends Feature {
	private final String subcategory;
	private final String slayerType;

	public SimpleToggleFeature(String id, String displayName, FeatureCategory category, String subcategory) {
		this(id, displayName, category, subcategory, null);
	}

	public SimpleToggleFeature(String id, String displayName, FeatureCategory category, String subcategory, String slayerType) {
		super(id, displayName, category, false);
		this.subcategory = subcategory;
		this.slayerType = slayerType;
	}

	@Override
	public String getSubcategory() {
		return subcategory;
	}

	@Override
	public String getSlayerType() {
		return slayerType;
	}

	@Override
	public String getDescription() {
		return "A plain on/off switch for " + getDisplayName() + ".";
	}
}
