package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.particle.ParticleFilterRegistry;
import net.minecraft.core.particles.ParticleOptions;

import java.util.function.Predicate;

/**
 * Generic "hide any particle matching a predicate" toggle — mirrors SoundMuteFeature's design, reused
 * for every "hide X particles" module instead of a near-identical class per particle type.
 */
public class ParticleFilterFeature extends Feature {
	private final String subcategory;
	private final Predicate<ParticleOptions> matcher;
	private boolean registered = false;

	public ParticleFilterFeature(String id, String displayName, FeatureCategory category, String subcategory, Predicate<ParticleOptions> matcher) {
		super(id, displayName, category, false);
		this.subcategory = subcategory;
		this.matcher = matcher;
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			ParticleFilterRegistry.setRule(getId(), matcher);
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			ParticleFilterRegistry.clearRule(getId());
			registered = false;
		}
	}

	@Override
	public String getSubcategory() {
		return subcategory;
	}

	@Override
	public String getDescription() {
		return "Hides " + getDisplayName() + " particles from rendering.";
	}
}
