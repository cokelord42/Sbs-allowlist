package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import net.minecraft.world.entity.LightningBolt;

/** Hides real vanilla lightning-bolt entities — per user request ("Add Hide lightning bolts... to the
 *  performance tab"), ported from NoammAddons' confirmed {@code RenderOptimizer.kt} "Hide Lightning
 *  Bolts" toggle. Same {@link EntityHideRegistry} render-time hide every other "Hide X" performance
 *  toggle in this codebase already uses — no packet interception/mixin needed. */
public class HideLightningBoltsFeature extends Feature {
	private boolean registered = false;

	public HideLightningBoltsFeature() {
		super("performance_hide_lightning_bolts", "Hide Lightning Bolts", FeatureCategory.PERFORMANCE, false);
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			EntityHideRegistry.setRule(getId(), entity -> entity instanceof LightningBolt);
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
		return "Hides real lightning-bolt entities from rendering, useful during storms or lots of lightning-based abilities.";
	}
}
