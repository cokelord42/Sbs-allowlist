package com.cokelord.skyblocksimplified.feature;

import net.minecraft.client.KeyMapping;

import java.util.List;

/**
 * A second sidebar row for a feature that needs to appear under two subcategories at once (e.g. Croesus
 * Chest Overlay living in both Kuudra and Dungeons) without actually duplicating its state or logic.
 * Toggling either row flips the same underlying target: onEnable/onDisable forward to it, and isEnabled()
 * reads its live state directly, so the two rows can never drift out of sync. Settings-panel dispatch in
 * MainScreen unwraps mirrors back to their target before doing any instanceof-based routing, so the
 * mirror's cog opens the exact same panel as the real row.
 */
public class LinkedFeatureMirror extends Feature {
	private final Feature target;
	private final String subcategory;

	public LinkedFeatureMirror(Feature target, String subcategory) {
		this(target, target.getCategory(), subcategory);
	}

	/** Same idea, but into a different category entirely — used to mirror every new module into New
	 *  Modules (which has no subcategories of its own, so subcategory is typically just null there) so
	 *  it's easy to find and test alongside wherever it actually belongs. */
	public LinkedFeatureMirror(Feature target, FeatureCategory category, String subcategory) {
		super(target.getId() + "_mirror_" + category.name().toLowerCase(java.util.Locale.ROOT)
				+ (subcategory != null ? "_" + subcategory.toLowerCase(java.util.Locale.ROOT).replace(' ', '_') : ""),
			target.getDisplayName(), category, target.isEnabledByDefault());
		this.target = target;
		this.subcategory = subcategory;
	}

	public Feature getTarget() {
		return target;
	}

	@Override
	public String getSubcategory() {
		return subcategory;
	}

	@Override
	public boolean isEnabled() {
		return target.isEnabled();
	}

	// Feature.setEnabled() normally no-ops when its own private `enabled` field already matches the
	// requested value — fine for a real feature, but a mirror's own `enabled` field never gets touched by
	// anything except this method, so it silently drifts out of sync with the target the moment the
	// *other* row (or the target itself) changes state without going through this mirror. That desync is
	// exactly why toggling off only worked from one of the two subcategory rows and not the other:
	// whichever row was used to switch it on left the other row's private field stuck at the opposite
	// value, so its next click's guard short-circuited before ever forwarding to the target. Overriding
	// setEnabled to always forward unconditionally sidesteps that bookkeeping entirely — the target's own
	// setEnabled call still has a correct, single source of truth for its own dedup guard.
	@Override
	public void setEnabled(boolean enabled) {
		target.setEnabled(enabled);
	}

	@Override
	public List<KeyMapping> getKeybinds() {
		return target.getKeybinds();
	}
}
