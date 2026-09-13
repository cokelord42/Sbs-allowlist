package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/**
 * Automatically makes you sprint. Ported from Odin's {@code AutoSprint.kt} — trivial on Odin's side too,
 * all real logic lives in its {@code LocalPlayerMixin.java} (see {@link
 * com.cokelord.skyblocksimplified.mixin.AutoSprintMixin}), which this class only toggles.
 */
public class AutoSprintFeature extends Feature {
	public AutoSprintFeature() {
		super("auto_sprint", "Auto Sprint", FeatureCategory.INVENTORY, false);
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	public String getDescription() {
		return "Makes you sprint automatically whenever you're moving forward, without holding the sprint key.";
	}
}
