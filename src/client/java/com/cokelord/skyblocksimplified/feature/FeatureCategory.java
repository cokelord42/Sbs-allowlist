package com.cokelord.skyblocksimplified.feature;

import java.util.List;

public enum FeatureCategory {
	// "GUI" subcategory removed: Custom Scoreboard was its only member and is now hidden from the module
	// list entirely (shelved per user request) — an empty subcategory tab with no rows to show would just
	// be dead space in the tab bar.
	// Real bug found (per user report — "Change the 'New modules' category. Move it to the About category
	// and make it a subcategory which includes the new modules"): NEW_MODULES used to be its own top-level
	// category with no subcategory of its own — now folded into ABOUT as a real subcategory tab instead, so
	// every LinkedFeatureMirror aimed at it now targets (FeatureCategory.ABOUT, "New Modules").
	ABOUT("About", "Mod settings", "Edit gui locations", "Mod Information", "New Modules"),
	// "Crimson Isle" subcategory removed per user request — its one feature (Hide Irrelevant Mobs) moved
	// under Slayers > Tarantula instead.
	COMBAT("Combat", "Combat", "Slayers", "Dungeons", "Floor 7", "Kuudra", "Puzzles"),
	FARMING("Farming", "Farming", "Pest farming", "Visitors", "Custom Keybinds", "Events", "Greenhouse"),
	// FISHING/MINING removed (2026-08-15): no features exist in either category — an empty tab with no
	// rows to show would just be dead space, same reasoning as the earlier GUI-subcategory removal above.
	FORAGING("Foraging", "Foraging", "HOTF"),
	ENCHANTING("Enchanting"),
	INVENTORY("Inventory", "Inventory", "Enchantments", "Storage", "Misc"),
	PERFORMANCE("Performance");

	private final String displayName;
	private final List<String> subcategories;

	FeatureCategory(String displayName, String... subcategories) {
		this.displayName = displayName;
		this.subcategories = List.of(subcategories);
	}

	public String getDisplayName() {
		return displayName;
	}

	/** Sub-tabs shown at the top of this category's content area. Empty means no sub-tab row. */
	public List<String> getSubcategories() {
		return subcategories;
	}
}
