package com.cokelord.skyblocksimplified.feature;

import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.google.gson.JsonElement;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

public abstract class Feature {
	private final String id;
	private final String displayName;
	private final FeatureCategory category;
	private final boolean enabledByDefault;
	private boolean enabled;
	// Debounces rapid on/off spam (0.5s, per user request) — several features tear down and rebuild
	// state in onEnable/onDisable (HudElementRegistry add/remove, chat listeners, etc.) that isn't
	// guaranteed to apply synchronously; spamming the toggle faster than that could re-add a layer before
	// the previous removal had actually taken effect, silently leaving the feature "on" in the GUI but
	// permanently gone in reality (this is exactly what broke Custom Scoreboard before — see its onEnable
	// doc comment). Applied once here instead of per-feature so every toggle in the mod is covered.
	private static final long TOGGLE_DEBOUNCE_MS = 500;
	private long lastToggleTimeMs = -TOGGLE_DEBOUNCE_MS;

	protected Feature(String id, String displayName, FeatureCategory category, boolean enabledByDefault) {
		this.id = id;
		this.displayName = displayName;
		this.category = category;
		this.enabledByDefault = enabledByDefault;
	}

	public String getId() {
		return id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public FeatureCategory getCategory() {
		return category;
	}

	public boolean isEnabledByDefault() {
		return enabledByDefault;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		long now = System.currentTimeMillis();
		if (now - lastToggleTimeMs < TOGGLE_DEBOUNCE_MS) return;
		lastToggleTimeMs = now;
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
	}

	/** Called once when the feature transitions from disabled to enabled. Set up state/listeners here. */
	protected void onEnable() {}

	/** Called once when the feature transitions from enabled to disabled. Tear down state here. */
	protected void onDisable() {}

	public void onTick(Minecraft client) {}

	/** Keybinds shown as slots on this feature's settings screen. Empty means the settings cog is inert. */
	public List<KeyMapping> getKeybinds() {
		return List.of();
	}

	/** False hides the on/off toggle entirely. Use for infrastructure features that must never be disabled from the GUI they'd then lock you out of. */
	public boolean isToggleable() {
		return true;
	}

	/** True hides this feature's row from the module list entirely (not just its toggle) — for a shelved,
	 *  force-disabled feature the user doesn't want cluttering the GUI at all until it's revisited. */
	public boolean isHiddenFromGui() {
		return false;
	}

	/** Optional sub-tab under this feature's category (e.g. "Slayers" under Combat). Null if the category has no sub-tabs or this feature isn't in one. */
	public String getSubcategory() {
		return null;
	}

	/** Optional second-level tab within a subcategory (currently only Combat > Slayers uses this, to
	 *  split each slayer type's modules into its own tab) — null means "shown on the general tab". */
	public String getSlayerType() {
		return null;
	}

	/** One or two plain-English sentences explaining what this module actually does, shown as a hover
	 *  tooltip over its name in the mod menu's module list — per user request ("tooltips for every feature
	 *  in the mod... if someone is confused about what something does, hovering the module name... should
	 *  show a small tooltip explaining what it does"). Null (the default) means no tooltip is shown; every
	 *  concrete feature overrides this with a real description. */
	public String getDescription() {
		return null;
	}

	/** Extra search terms that should find this module even though they don't appear in its own display
	 *  name — e.g. an old/community name for the same feature ("Secret Routes" for Dungeon Routes, "CS"/
	 *  "Case" for Chest Rolling). Empty by default; override per feature as needed. Checked in the mod
	 *  menu's search jump right alongside the real display name, ranked just below it (a real alias should
	 *  win over a mere sub-setting match). */
	public java.util.List<String> getSearchAliases() {
		return java.util.List.of();
	}

	/** For non-toggleable features with exactly one keybind: shown as a keybind selector in the row itself, where the toggle would normally be. Null falls back to the normal toggle/cog layout. */
	public KeyMapping getPrimaryKeybind() {
		return null;
	}

	/** Like getPrimaryKeybind(), but for a multi-key/mouse-button combo instead of a single vanilla
	 *  KeyMapping. A feature should expose at most one of the two — MainScreen checks this one first. */
	public KeyCombo getPrimaryKeyCombo() {
		return null;
	}

	/** Arbitrary named float settings to persist (e.g. animation durations). Empty by default. */
	public Map<String, Float> getPersistedFloats() {
		return Map.of();
	}

	/** Called on config load with whatever was saved via getPersistedFloats(). */
	public void loadPersistedFloats(Map<String, Float> values) {}

	/** A single ARGB color to persist (e.g. a chosen theme color). Null means nothing to persist. */
	public Integer getPersistedColor() {
		return null;
	}

	/** Called on config load with a previously saved color, if any. */
	public void loadPersistedColor(int argb) {}

	/** Escape hatch for arbitrary structured data (lists, nested objects) that don't fit the typed
	 *  float/color hooks above — e.g. an ordered line list plus a HUD position. Null means nothing to persist. */
	public JsonElement savePersistedData() {
		return null;
	}

	/** Called on config load with previously saved data, if any. */
	public void loadPersistedData(JsonElement data) {}

	/** Per user report ("Config is not optimized, its still 360000 characters when exporting the mod config
	 *  to clipboard"): {@link #savePersistedData()} is also reused verbatim for the clipboard export, but
	 *  some features persist large local bookkeeping state on disk that's genuinely useful there (a played-
	 *  chest memory cache, a dedup set, etc.) yet is never a real "setting" worth carrying into an export —
	 *  it doesn't represent a user choice, and it's exactly the kind of large, high-entropy data that gzip
	 *  can't shrink much. Defaults to {@link #savePersistedData()} (every feature's export stays identical
	 *  to its disk save unless it overrides this) — a feature with real large-cache bloat overrides this one
	 *  method to strip just that part, while {@link #loadPersistedData} still accepts either shape on import
	 *  (an export simply omits the trimmed field, same as an older save predating it). */
	public JsonElement savePersistedDataForExport() {
		return savePersistedData();
	}
}
