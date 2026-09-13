package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/**
 * No-toggle "module" (same {@code isToggleable()=false} convention as {@link ConfigExportImportFeature})
 * whose row shows an "Update" button in place of the usual on/off switch — visible only while
 * {@link com.cokelord.skyblocksimplified.api.UpdateApi} reports a newer version available, hidden
 * otherwise, per explicit request. All the actual check/download/install logic lives in UpdateApi; this
 * class is only the registry entry MainScreen hangs that row off.
 */
public class UpdateModuleFeature extends Feature {
	public UpdateModuleFeature() {
		super("update", "Update", FeatureCategory.ABOUT, true);
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public String getDescription() {
		return "Shows an Update button when a new version of the mod is available, and downloads it for you.";
	}
}
