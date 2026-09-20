package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import net.minecraft.world.entity.Entity;

import java.util.function.Predicate;

/**
 * Generic "hide any entity matching a predicate" toggle — reused for every "hide X" module instead of
 * a near-identical class per case (mirrors SoundMuteFeature/MobHighlightFeature's shape). Two different
 * toggles (e.g. a combat-wide and a slayer-specific one) can point at the exact same matcher and each
 * registers under its own id, since EntityHideRegistry hides an entity if ANY rule matches it.
 */
public class EntityHideFeature extends Feature {
	private final String subcategory;
	private final Predicate<Entity> matcher;
	private boolean registered = false;

	public EntityHideFeature(String id, String displayName, FeatureCategory category, String subcategory, Predicate<Entity> matcher) {
		super(id, displayName, category, false);
		this.subcategory = subcategory;
		this.matcher = matcher;
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			EntityHideRegistry.setRule(getId(), matcher);
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

	@Override
	public String getSubcategory() {
		return subcategory;
	}

	@Override
	public String getDescription() {
		return "Hides " + getDisplayName() + " from rendering entirely.";
	}
}
