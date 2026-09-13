package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.Anim;

import java.util.HashMap;
import java.util.Map;

/**
 * Controls the GUI's animation durations (0-0.5s each, "seconds" suffix shown in the UI; 0 = instant
 * for that one animation specifically). Turning the feature itself off forces every {@link Anim} in
 * the GUI to snap instantly regardless of its individual duration — a lightweight mode for people on
 * weaker hardware. This mainly saves the small per-frame cost of the exponential-approach math itself;
 * it does NOT reduce draw-call count (rounded corners, rows, etc. still render the same either way),
 * so it's a modest, not dramatic, performance win — worth having, not a silver bullet.
 */
public class GuiAnimationsFeature extends Feature {
	public static final float MAX_DURATION = 0.5f;
	// Per user request ("The animation is good now but a little too fast kind of. Set max to 1 seconds and
	// keep default as 0.5 seconds"): the Opening slider specifically gets a higher cap than every other
	// duration here — a 3-phase sequential reveal reads as rushed at the same max a single fade/slide was
	// tuned for, but the default stays untouched so nobody's existing setting silently changes.
	public static final float MAX_OPEN_DURATION = 1.0f;

	private float openDuration = 0.5f;
	private float closeDuration = 0.3f;
	private float expandDuration = 0.4f;
	private float searchDuration = 0.5f;
	// Per user request ("Changing categories should also have an animation... make sure all animations can
	// be disabled/sped up in the gui animations module"): the category-sidebar / flat-subcategory-tab
	// crossfade — see MainScreen's contentSwapFadeAnim/triggerContentSwap.
	private float contentSwitchDuration = 0.3f;
	private int blurAmount = 0;
	private boolean cogSpinEnabled = true;
	private boolean sliderAnimationEnabled = true;
	// Per user request ("add smooth scrolling... add a subtoggle to mod animations to disable it if the
	// user wants to") — smooth (exponential ease-out) scrolling for the mod menu's content/sidebar lists
	// already existed (MainScreen's scrollAnim/sidebarScrollAnim, same Anim mechanism as every other
	// animated value here), already gated by this feature's own master on/off switch above — this adds the
	// SAME independent-subtoggle pattern isCogSpinEnabled/isSliderAnimationEnabled already use, so a player
	// can turn off just scroll easing (e.g. if it feels laggy on a heavy scroll) without losing every other
	// GUI animation, or vice versa.
	private boolean scrollAnimationEnabled = true;
	// Per user request ("Remove the subtoggle for the squircle corners as they are default and the toggle
	// does nothing") — squircle corners are no longer a separate opt-in shape; RenderUtil now always uses
	// them for every non-forceCircle rounded element (see its own doc comment), so the setting that used to
	// gate that choice is gone entirely, not just hidden.
	//
	// Per later user request ("after all this work just revert the squircles back to the pixellated look and
	// make the pixellated thing default... remove that subtoggle"): the opt-in "Pixelated Look" toggle (and
	// its Performance Toggles mirror row) that used to gate this is gone too — RenderUtil now always uses the
	// hard, binary inside/outside pixel cutoff unconditionally, same as squircle's own history above.

	public GuiAnimationsFeature() {
		super("gui_animations", "GUI Animations", FeatureCategory.ABOUT, true);
	}

	@Override
	protected void onEnable() {
		Anim.setGlobalInstant(false);
	}

	@Override
	protected void onDisable() {
		Anim.setGlobalInstant(true);
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	public float getOpenDuration() {
		return openDuration;
	}

	public void setOpenDuration(float seconds) {
		openDuration = Math.max(0f, Math.min(MAX_OPEN_DURATION, seconds));
	}

	public float getCloseDuration() {
		return closeDuration;
	}

	public void setCloseDuration(float seconds) {
		closeDuration = clamp(seconds);
	}

	public float getExpandDuration() {
		return expandDuration;
	}

	public void setExpandDuration(float seconds) {
		expandDuration = clamp(seconds);
	}

	public float getSearchDuration() {
		return searchDuration;
	}

	public void setSearchDuration(float seconds) {
		searchDuration = clamp(seconds);
	}

	public float getContentSwitchDuration() {
		return contentSwitchDuration;
	}

	public void setContentSwitchDuration(float seconds) {
		contentSwitchDuration = clamp(seconds);
	}

	/** 0-10, matching vanilla's own menu-background-blur option exactly (it only has 11 discrete
	 *  steps — MAX_BLUR_RADIUS is hardcoded to 10 — so a finer-grained slider would just be lossily
	 *  rounded down to these same 11 values anyway). Drives a temporary, save-and-restore override of
	 *  that vanilla option while this GUI is open (see MainScreen.extractBackground) rather than a
	 *  custom shader. Note: calling into that vanilla option does flip the player's Graphics Preset to
	 *  "Custom" the first time it's used, same as manually changing any single graphics option would. */
	public int getBlurAmount() {
		return blurAmount;
	}

	public void setBlurAmount(int level) {
		blurAmount = Math.max(0, Math.min(10, level));
	}

	private static float clamp(float seconds) {
		return Math.max(0f, Math.min(MAX_DURATION, seconds));
	}

	public boolean isCogSpinEnabled() {
		return cogSpinEnabled;
	}

	public void setCogSpinEnabled(boolean cogSpinEnabled) {
		this.cogSpinEnabled = cogSpinEnabled;
	}

	public boolean isSliderAnimationEnabled() {
		return sliderAnimationEnabled;
	}

	public void setSliderAnimationEnabled(boolean sliderAnimationEnabled) {
		this.sliderAnimationEnabled = sliderAnimationEnabled;
	}

	public boolean isScrollAnimationEnabled() {
		return scrollAnimationEnabled;
	}

	public void setScrollAnimationEnabled(boolean scrollAnimationEnabled) {
		this.scrollAnimationEnabled = scrollAnimationEnabled;
	}

	@Override
	public Map<String, Float> getPersistedFloats() {
		Map<String, Float> values = new HashMap<>();
		values.put("open", openDuration);
		values.put("close", closeDuration);
		values.put("expand", expandDuration);
		values.put("search", searchDuration);
		values.put("contentSwitch", contentSwitchDuration);
		values.put("blur", (float) blurAmount);
		values.put("cogSpin", cogSpinEnabled ? 1f : 0f);
		values.put("sliderAnim", sliderAnimationEnabled ? 1f : 0f);
		values.put("scrollAnim", scrollAnimationEnabled ? 1f : 0f);
		return values;
	}

	@Override
	public void loadPersistedFloats(Map<String, Float> values) {
		if (values.containsKey("open")) setOpenDuration(values.get("open"));
		if (values.containsKey("close")) closeDuration = clamp(values.get("close"));
		if (values.containsKey("expand")) expandDuration = clamp(values.get("expand"));
		if (values.containsKey("search")) searchDuration = clamp(values.get("search"));
		if (values.containsKey("contentSwitch")) contentSwitchDuration = clamp(values.get("contentSwitch"));
		if (values.containsKey("blur")) blurAmount = Math.round(values.get("blur"));
		if (values.containsKey("cogSpin")) cogSpinEnabled = values.get("cogSpin") >= 0.5f;
		if (values.containsKey("sliderAnim")) sliderAnimationEnabled = values.get("sliderAnim") >= 0.5f;
		if (values.containsKey("scrollAnim")) scrollAnimationEnabled = values.get("scrollAnim") >= 0.5f;
		// "squircleCorners"/"pixelatedLook" keys from an older config are silently ignored — squircle is
		// unconditional and pixelated is now the only rendering mode, per user request, nothing left for
		// either to control.
		// "tileTransition"/"flatSubcategories" keys from an older config are silently ignored — the
		// tile-grid subcategory system they controlled is gone entirely now, per user request.
	}

	@Override
	public String getDescription() {
		return "Controls how fast the mod menu's own open/close/slide animations play, or turns them off entirely.";
	}
}
