package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/**
 * No-toggle "module" (same {@code isToggleable()=false} convention as {@link ConfigExportImportFeature}
 * and {@link UpdateModuleFeature}) whose row shows the currently running mod version alongside a
 * "Fully Tested."/"Untested." indicator — all the actual version data comes from
 * {@link com.cokelord.skyblocksimplified.api.UpdateApi}; this class is only the registry entry
 * MainScreen hangs that row off.
 */
public class ModInformationFeature extends Feature {
	public ModInformationFeature() {
		super("mod_information", "Mod Information", FeatureCategory.ABOUT, true);
	}

	@Override
	public String getSubcategory() {
		return "Mod Information";
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public String getDescription() {
		return "Shows the mod's current version and whether this build has been fully tested.";
	}
}
