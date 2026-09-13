package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.util.IslandGate;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.LevelLoadingScreen;

/** Skips the vanilla "dirt screen" (LevelLoadingScreen) shown while joining/switching islands —
 *  SkyblockAddons had the same toggle on 1.8.9. Just closes the screen the instant it opens.
 *
 *  <p>Real bug found (per long-standing user report of singleplayer worlds bouncing back to the main menu
 *  before finishing a join): {@code LevelLoadingScreen} is the exact same vanilla class shown for a plain
 *  singleplayer world join, not just a Hypixel island switch — this used to dismiss it unconditionally,
 *  which meant a user with this toggle on got their own singleplayer worlds' loading screen force-closed
 *  before the integrated server had actually finished preparing spawn chunks. Gated to {@link
 *  IslandGate#isOnHypixel()} now, matching what the feature is actually meant to do (per its own doc
 *  comment, "joining/switching islands" is a Hypixel-only concept) — never fires in singleplayer or on any
 *  other server. */
public class HideLoadingScreenFeature extends Feature {
	public HideLoadingScreenFeature() {
		super("hide_loading_screen", "Hide Loading Screen", FeatureCategory.INVENTORY, false);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (isEnabled() && screen instanceof LevelLoadingScreen && IslandGate.isOnHypixel()) {
				client.gui.setScreen(null);
			}
		});
	}

	@Override
	public String getSubcategory() {
		return "Inventory";
	}

	@Override
	public String getDescription() {
		return "Skips the vanilla loading (dirt) screen shown while joining or switching islands.";
	}
}
