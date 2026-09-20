package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/**
 * Per user request ("Add auto-update-on-close module (default off)"): when enabled and an update is
 * currently AVAILABLE (see {@link com.cokelord.skyblocksimplified.api.UpdateApi}), the very next time the
 * player quits the game normally, {@code MinecraftStopMixin} intercepts that quit and silently runs the same
 * download/verify/stage flow the manual Update button already triggers, before actually letting the game
 * close — so the new jar is already staged and ready the next time the player launches, with nothing to
 * click. Default OFF (not every player wants their quit gesture doing extra background work first) and a
 * plain toggle — all the real logic lives in the mixin + UpdateApi, this class is only the registry entry
 * that switch reads.
 */
public class AutoUpdateOnCloseFeature extends Feature {
	public AutoUpdateOnCloseFeature() {
		super("auto_update_on_close", "Auto-Update On Close", FeatureCategory.ABOUT, false);
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	@Override
	public String getDescription() {
		return "When a mod update is available, automatically downloads and installs it the next time you quit the game.";
	}
}
