package com.cokelord.skyblocksimplified.hud;

/** Where a HUD element sits on screen: fractional anchor (0..1 of the current screen width/height,
 *  so it stays put proportionally across window resizes) plus a scale multiplier. */
public class HudPosition {
	public float anchorX;
	public float anchorY;
	public float scale;
	// Per user request ("Add per-widget snap toggle (S button) to Player Display HUD elements in edit GUI"):
	// HudEditScreen's snap-to-other-widgets behavior used to apply uniformly to every draggable widget with
	// no way to opt a specific one out — e.g. wanting Health to snap against Mana/Defense but Overflow Mana
	// to sit exactly where dragged without catching on anything. Default true so every existing widget's
	// drag behavior is unchanged unless a widget's own edit-screen "S" button explicitly turns it off.
	public boolean snapEnabled = true;

	public HudPosition(float anchorX, float anchorY, float scale) {
		this.anchorX = anchorX;
		this.anchorY = anchorY;
		this.scale = scale;
	}

	public void set(float anchorX, float anchorY) {
		this.anchorX = anchorX;
		this.anchorY = anchorY;
	}

	/** Also resets scale — used by "Reset" in the edit screen, per user request, since the plain
	 *  position-only {@link #set(float, float)} used to leave a resized element resized. */
	public void set(float anchorX, float anchorY, float scale) {
		this.anchorX = anchorX;
		this.anchorY = anchorY;
		this.scale = scale;
	}

	public HudPosition copy() {
		HudPosition copy = new HudPosition(anchorX, anchorY, scale);
		copy.snapEnabled = snapEnabled;
		return copy;
	}
}
