package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Per user request ("Need a new module: Hide mob dying animation... make it overpower the enderman dying
 * animation thing we already have so they dont cancel eachother out or anything"), then corrected ("Hide mob
 * dying animation doesnt hide it it just cancels the animation. I want them mob to genuinely hide when
 * dead"): the general, every-mob version of {@link DisableEndermanDeathAnimationFeature}'s own real hide
 * mechanism — that feature's own doc comment already explains why fully hiding (via
 * {@link EntityHideRegistry}, checked against the real {@code deathTime > 0} state) was needed instead of
 * only cancelling the fall-over rotation, which is the exact same correction this feature needed. No
 * {@code EnderMan} type restriction, otherwise identical. Registering under its own id means both features
 * can be enabled together with no conflict: EntityHideRegistry hides an entity if ANY rule matches it, so
 * two independent rules both saying "hide this dying mob" can't cancel each other out any more than one
 * rule alone could.
 */
public class HideMobDyingAnimationFeature extends Feature {
	private boolean registered = false;

	public HideMobDyingAnimationFeature() {
		super("hide_mob_dying_animation", "Hide Mob Dying Animation", FeatureCategory.COMBAT, false);
	}

	@Override
	public String getSubcategory() {
		return "Combat";
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			EntityHideRegistry.setRule(getId(), HideMobDyingAnimationFeature::isDyingMob);
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

	private static boolean isDyingMob(Entity entity) {
		return entity instanceof LivingEntity living && living.deathTime > 0;
	}

	@Override
	public String getDescription() {
		return "Hides any mob entirely the instant it starts dying, instead of watching it fall over.";
	}
}
