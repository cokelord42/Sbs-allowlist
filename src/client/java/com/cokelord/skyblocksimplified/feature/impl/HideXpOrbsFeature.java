package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import net.minecraft.world.entity.ExperienceOrb;

/** Hides real vanilla experience-orb entities — per user request ("Add ... hide XP orbs ... to the
 *  performance tab"), ported from NoammAddons' confirmed {@code RenderOptimizer.kt} "Hide XP Orbs"
 *  toggle. Same {@link EntityHideRegistry} render-time hide every other "Hide X" performance toggle in
 *  this codebase already uses — no packet interception/mixin needed. */
public class HideXpOrbsFeature extends Feature {
	private boolean registered = false;

	public HideXpOrbsFeature() {
		super("performance_hide_xp_orbs", "Hide XP Orbs", FeatureCategory.PERFORMANCE, false);
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			EntityHideRegistry.setRule(getId(), entity -> entity instanceof ExperienceOrb);
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
	public String getDescription() {
		return "Hides experience orb entities from rendering.";
	}
}
