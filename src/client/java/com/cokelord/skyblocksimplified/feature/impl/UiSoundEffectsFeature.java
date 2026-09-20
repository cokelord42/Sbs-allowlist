package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/** Master mute switch for the mod menu's own UI click/toggle sounds (module toggle, category/subcategory/
 *  search clicks, settings-cog open/close) — {@link com.cokelord.skyblocksimplified.gui.MainScreen}'s sound
 *  helpers check {@link #isSoundEnabled()} before playing anything. Purely a GUI-feedback preference (same
 *  "on by default" convention as {@link GuiAnimationsFeature}), not a real gameplay feature. */
public class UiSoundEffectsFeature extends Feature {
	private static UiSoundEffectsFeature instance;

	public UiSoundEffectsFeature() {
		super("ui_sound_effects", "UI Sound Effects", FeatureCategory.ABOUT, true);
		instance = this;
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	public static boolean isSoundEnabled() {
		return instance == null || instance.isEnabled();
	}

	@Override
	public String getDescription() {
		return "Master mute switch for the mod menu's own click/toggle sound effects.";
	}
}
