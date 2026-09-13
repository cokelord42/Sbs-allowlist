package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Real redesign per user request ("Replace Hypixel Mod API dependency module with an always-on warning
 * banner"): this used to be a togglable on/off switch letting the user opt OUT of the separate, optional
 * "Hypixel Mod API" companion Fabric mod even when it was installed (see IslandGate's now-removed gate on
 * this feature's enabled state). There was never a real reason to want that — the companion mod is strictly
 * more reliable than the sidebar-scan fallback (see IslandGate's own class doc comment on the sidebar
 * flicker problems it exists to sidestep), so a toggle to deliberately downgrade detection quality with no
 * upside was pure clutter. Now always-on/non-toggleable (see {@link #isToggleable()}) — IslandGate always
 * prefers the companion mod's data whenever it's actually installed and running, with no way to opt out from
 * the GUI. This module's row instead shows a live, informational status line (see
 * {@code MainScreen.drawHypixelModApiRowInline}): a plain confirmation when the companion mod is detected,
 * or a warning banner explaining that installing it improves location-detection reliability when it isn't —
 * exactly the "banner" a user report about a do-nothing toggle deserved instead.
 */
public class HypixelModApiDependencyFeature extends Feature {
	public HypixelModApiDependencyFeature() {
		super("hypixel_mod_api_dependency", "Hypixel Mod API", FeatureCategory.ABOUT, true);
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	/** True once Fabric Loader confirms the separate "hypixel-mod-api" companion mod is actually present in
	 *  this install — same detection FabricLoader.isModLoaded call IslandGate itself uses, exposed here too
	 *  so the GUI row and the real detection logic can never disagree about whether it's installed. */
	public static boolean isCompanionModInstalled() {
		return FabricLoader.getInstance().isModLoaded("hypixel-mod-api");
	}

	@Override
	public String getDescription() {
		return "Shows a warning banner if the optional Hypixel Mod API companion mod isn't installed, since some features rely on it.";
	}
}
