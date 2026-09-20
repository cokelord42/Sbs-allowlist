package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/**
 * No-toggle "module" (same {@code isToggleable()=false} convention as {@link GuiColorFeature} — a module
 * list entry that's really just a place for MainScreen to hang a custom panel off, not a real on/off
 * switch) exposing config export/import. Per user request: "for when the user updates the minecraft
 * version" — every current setting round-trips through one clipboard-safe string, so upgrading doesn't
 * mean re-configuring everything from scratch, and settings can be shared between players. All the actual
 * export/import logic lives in {@link com.cokelord.skyblocksimplified.config.ConfigManager}
 * (exportToClipboardString/importFromClipboardString) — this class is only the registry entry.
 */
public class ConfigExportImportFeature extends Feature {
	public ConfigExportImportFeature() {
		super("config_export_import", "Config", FeatureCategory.ABOUT, true);
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
		return "Exports your whole mod configuration to the clipboard, or imports a previously exported config, so you can back it up or share it.";
	}
}
