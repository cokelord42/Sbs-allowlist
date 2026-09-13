package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Remembers which mod-menu category/subcategory tab was open when MainScreen last closed, and reopens
 * there next time instead of always landing on About > Mod settings — per user request, on by default so
 * a fresh install gets this behavior without needing to find and enable it first. The actual read/write
 * happens in MainScreen itself (constructor/onClose); this class only owns the toggle and the persisted
 * "last page" values, mirroring how other purely-data-holding toggles in this project work.
 */
public class RememberLastPageFeature extends Feature {
	private static String lastCategory = null;
	private static String lastSubcategory = null;
	// Which module's settings panel (if any) was expanded, and how far the list was scrolled — per user
	// request, "remember the exact same look when closed and reopened", not just which tab. Both are only
	// meaningful together with lastCategory/lastSubcategory (the expanded feature belongs to whichever
	// category/subcategory was open when it was recorded), so MainScreen restores all four at once.
	private static String lastExpandedFeatureId = null;
	private static float lastScrollOffset = 0f;
	private static String lastSearchQuery = "";
	// Per user report ("the 'remember last mod page' doesnt remember certain buttons, like it always opens
	// the 'Mage' portion of the boss guide when the mod menu is reopened"): MainScreen's own
	// selectedCopilotClass field (which class's Boss Guide steps are being viewed/edited) is a sub-tab
	// inside the Dungeons Copilot panel, not part of the category/subcategory system above at all — it was
	// never wired into this class, so it silently reset to its hardcoded MAGE default on every MainScreen
	// reconstruction (i.e. every single mod-menu reopen) regardless of this toggle. Any settings panel with
	// its own internal sub-tab like this should record/restore through here the same way; Dungeons Copilot
	// is the one confirmed case so far.
	private static String lastCopilotClass = null;
	// Per user report ("The mod remembering feature doesnt remember when i am in a room config") — same gap
	// as lastCopilotClass above, just for Dungeon Routes' own room-editor sub-tab (MainScreen's
	// selectedRouteRoomName: null shows the room grid, non-null is that room's step editor).
	private static String lastRouteRoom = null;

	public RememberLastPageFeature() {
		super("remember_last_mod_page", "Remember Last Mod Page", FeatureCategory.ABOUT, true);
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	public static void recordPage(FeatureCategory category, String subcategory) {
		lastCategory = category.name();
		lastSubcategory = subcategory;
	}

	/** Called alongside recordPage() on screen close. featureId is null when nothing was expanded (or the
	 *  panel had already been collapsed/wasn't fully open) — nothing to reopen next time either way. */
	public static void recordExpandedAndScroll(String featureId, float scrollOffset) {
		lastExpandedFeatureId = featureId;
		lastScrollOffset = scrollOffset;
	}

	/** The remembered category, or null if nothing's been recorded yet (first-ever open) or the feature
	 *  is disabled — callers should fall back to their own default in either case. */
	public static FeatureCategory lastCategoryOrNull(boolean enabled) {
		if (!enabled || lastCategory == null) return null;
		try {
			return FeatureCategory.valueOf(lastCategory);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static String lastSubcategoryOrNull(boolean enabled) {
		return enabled ? lastSubcategory : null;
	}

	public static String lastExpandedFeatureIdOrNull(boolean enabled) {
		return enabled ? lastExpandedFeatureId : null;
	}

	public static float lastScrollOffsetOrZero(boolean enabled) {
		return enabled ? lastScrollOffset : 0f;
	}

	public static void recordSearchQuery(String query) {
		lastSearchQuery = query;
	}

	public static String lastSearchQueryOrEmpty(boolean enabled) {
		return enabled ? lastSearchQuery : "";
	}

	public static void recordCopilotClass(com.cokelord.skyblocksimplified.dungeon.DungeonClass clazz) {
		lastCopilotClass = clazz.name();
	}

	/** The remembered Dungeons Copilot class tab, or null if nothing's been recorded yet/the feature is
	 *  disabled — caller falls back to its own default (MAGE) in either case. */
	public static com.cokelord.skyblocksimplified.dungeon.DungeonClass lastCopilotClassOrNull(boolean enabled) {
		if (!enabled || lastCopilotClass == null) return null;
		try {
			return com.cokelord.skyblocksimplified.dungeon.DungeonClass.valueOf(lastCopilotClass);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static void recordRouteRoom(String roomName) {
		lastRouteRoom = roomName;
	}

	/** The remembered Dungeon Routes room-editor tab, or null if nothing's been recorded yet/the feature is
	 *  disabled — caller falls back to its own default (the room grid) in either case. */
	public static String lastRouteRoomOrNull(boolean enabled) {
		return enabled ? lastRouteRoom : null;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		if (lastCategory != null) obj.addProperty("category", lastCategory);
		if (lastSubcategory != null) obj.addProperty("subcategory", lastSubcategory);
		if (lastExpandedFeatureId != null) obj.addProperty("expandedFeatureId", lastExpandedFeatureId);
		obj.addProperty("scrollOffset", lastScrollOffset);
		if (!lastSearchQuery.isEmpty()) obj.addProperty("searchQuery", lastSearchQuery);
		if (lastCopilotClass != null) obj.addProperty("copilotClass", lastCopilotClass);
		if (lastRouteRoom != null) obj.addProperty("routeRoom", lastRouteRoom);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("category")) lastCategory = obj.get("category").getAsString();
		if (obj.has("subcategory")) lastSubcategory = obj.get("subcategory").getAsString();
		if (obj.has("expandedFeatureId")) lastExpandedFeatureId = obj.get("expandedFeatureId").getAsString();
		if (obj.has("scrollOffset")) lastScrollOffset = obj.get("scrollOffset").getAsFloat();
		if (obj.has("searchQuery")) lastSearchQuery = obj.get("searchQuery").getAsString();
		if (obj.has("copilotClass")) lastCopilotClass = obj.get("copilotClass").getAsString();
		if (obj.has("routeRoom")) lastRouteRoom = obj.get("routeRoom").getAsString();
	}

	@Override
	public String getDescription() {
		return "Reopens the mod menu on whichever page/tab you had open last time, instead of always starting on the same page.";
	}
}
