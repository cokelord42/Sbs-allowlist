package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.gui.Theme;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** Real panel background theme, replacing the earlier "Themes" row that turned out to just be named
 *  accent-color presets (per user clarification: "Themes clarification: NOT mod accent color... wants a
 *  SEPARATE module with three real panel themes: White (turns the entire mod panel white, not just text —
 *  specifically the panel itself), Black (normal/current), Transparent"). Three real modes, each an actual
 *  different background fill for the whole mod panel (not a text-color change): Black is the mod's
 *  original/current background, White turns the panel itself white, Transparent uses that SAME white
 *  color scheme (per user clarification: "Transparent panel mode should be same as white but just
 *  transparent, not default to black") at low opacity so the game world shows through behind the menu. */
public class PanelThemeFeature extends Feature {
	public enum Mode { BLACK, WHITE, TRANSPARENT, CUSTOM }

	// Matches MainScreen's own original COLOR_PANEL_BG constant exactly, so BLACK (the default) renders
	// identically to how the panel always looked before this module existed.
	private static final int BLACK_ARGB = 0xF0101010;
	// Per user follow-up request ("White theme retune: background #C9C9C9... currently invisible when off
	// since it isn't darker than the background") — the earlier #FBFAF5 off-white base read as too bright/
	// low-contrast against its own module rows and toggle tracks (see Theme.chrome()'s White-mode literal
	// overrides below for the rest of that same retune), retuned to this grayer base.
	private static final int WHITE_ARGB = 0xF0C9C9C9;
	// Per user follow-up ("Make Transparent theme much more transparent"): 0x40 (~25%) still read as a mostly
	// solid panel — dropped to near-see-through so the game world behind the menu is actually the dominant
	// thing visible, with just enough tint left for chrome edges (search box, sliders, borders — see
	// Theme.chrome()'s own alpha-blend) to stay faintly readable rather than vanishing outright.
	private static final int TRANSPARENT_ALPHA = 0x16;

	// Per user request ("Build the 'Custom' theme... a Custom theme with user-picked main color + opacity
	// slider... plus a two-pin drag gradient editor with hex-popup pickers on each end, horizontal/vertical
	// orientation, pins with a 2px minimum gap that can't cross, click-nearest-pin-to-cursor placement,
	// click-and-drag live movement"): pin 1 doubles as both "the Main Color" (out of the box, both pins
	// default to the same color and position 0/1, which reads as a perfectly flat custom color — exactly the
	// "main color + opacity" half of the request) AND the gradient's own left/top stop; pin 2 is the second
	// stop, only visually distinct once the user actually changes its color and/or drags it to a different
	// position than pin 1's. One shared opacity slider applies to both pins' alpha, matching how White/
	// Transparent above already use a single alpha for their one color.
	private int customPin1Argb = 0xFF202020;
	private int customPin2Argb = 0xFF202020;
	private float customPin1Pos = 0f;
	private float customPin2Pos = 1f;
	// Per user request ("Do those now" — add an angular/directional gradient mode with a clock-hand angle
	// control, and a radial 'feathered circle' mode): generalizes the old fixed horizontal/vertical boolean
	// into Theme's own real direction system (see Theme.GradientMode's own doc comment for the exact angle
	// math and the old-boolean migration mapping used in loadPersistedData below).
	private Theme.GradientMode customGradientMode = Theme.GradientMode.LINEAR;
	private float customGradientAngle = 270f;
	private int customOpacityPercent = 100;

	// Per user request ("Add a chroma gradient option that forces the pins to scroll through the chroma
	// colors smoothly. They should never match color, always be one before or ahead so it actually looks
	// chroma"): same real hue-cycling mechanism GuiColorFeature's own accent chroma already uses (advance a
	// stored hue angle every tick, convert to RGB via RenderUtil.hsvToRgb) — reused here instead of building
	// a second one, just driving both gradient pins off the SAME cycling hue with a fixed angular offset
	// between them (HUE_GAP_DEGREES) instead of one fixed color, so the two pins are never equal and the
	// gap always reads as a real moving rainbow sweep rather than a flat color that happens to shimmer.
	private boolean customChromaEnabled = false;
	private float customChromaSpeed = 1f;
	private float customChromaHueDegrees = 0f;
	private static final float CHROMA_BASE_DEGREES_PER_TICK = 3f;
	private static final float CHROMA_HUE_GAP_DEGREES = 50f;

	// Per user request ("Do those now" — "Extend gradient/solid-color option to individual feature rows and
	// subtoggle buttons"): the module row list background (MainScreen's own colorRowBg()) and every
	// subtoggle's off-state track fill (drawToggleRow's own colorToggleOff()) sample this SAME live gradient
	// (via Theme.panelGradientColorAt) at their own screen position instead of a flat chrome() gray, once
	// this is on — an opt-in since a busy, differently-colored row background isn't everyone's taste even
	// with the panel's own gradient enabled.
	private boolean customApplyGradientToRows = false;

	private Mode mode = Mode.BLACK;

	public PanelThemeFeature() {
		// enabledByDefault=true even though isToggleable()=false: same pattern as GuiColorFeature — no
		// visible on/off, onEnable() is just the init hook that applies the saved mode at startup.
		super("panel_theme", "Panel Theme", FeatureCategory.ABOUT, true);
	}

	@Override
	protected void onEnable() {
		applyTheme();
	}

	// Non-toggleable (see isToggleable() below) always ticks, same as GuiColorFeature's own accent chroma —
	// needed so the hue keeps advancing regardless of any per-feature enabled flag.
	@Override
	public void onTick(Minecraft client) {
		if (mode != Mode.CUSTOM || !customChromaEnabled) return;
		customChromaHueDegrees = (customChromaHueDegrees + CHROMA_BASE_DEGREES_PER_TICK * customChromaSpeed) % 360f;
		applyTheme();
	}

	public Mode getMode() {
		return mode;
	}

	public void setMode(Mode value) {
		mode = value;
		applyTheme();
	}

	public int getCustomPin1Argb() { return customPin1Argb; }
	public void setCustomPin1Argb(int value) { customPin1Argb = 0xFF000000 | (value & 0xFFFFFF); applyTheme(); }
	public int getCustomPin2Argb() { return customPin2Argb; }
	public void setCustomPin2Argb(int value) { customPin2Argb = 0xFF000000 | (value & 0xFFFFFF); applyTheme(); }
	public float getCustomPin1Pos() { return customPin1Pos; }
	public float getCustomPin2Pos() { return customPin2Pos; }

	// Per user spec ("pins with a 2px minimum gap that can't cross"): enforced here, the one real choke point
	// every UI-driven pin move (drag or click-nearest-placement) goes through, rather than trusting each call
	// site to re-derive the same clamp correctly. 2px is expressed as a fraction of MainScreen's own gradient
	// bar width at the call site (see setPin1Pos/setPin2Pos callers there) since position is stored 0..1 here,
	// not in pixels.
	public void setCustomPin1Pos(float value, float minGapFraction) {
		customPin1Pos = Math.max(0f, Math.min(customPin2Pos - minGapFraction, value));
		applyTheme();
	}

	public void setCustomPin2Pos(float value, float minGapFraction) {
		customPin2Pos = Math.min(1f, Math.max(customPin1Pos + minGapFraction, value));
		applyTheme();
	}

	public Theme.GradientMode getCustomGradientMode() { return customGradientMode; }
	public void setCustomGradientMode(Theme.GradientMode value) { customGradientMode = value; applyTheme(); }
	public float getCustomGradientAngle() { return customGradientAngle; }
	public void setCustomGradientAngle(float value) {
		customGradientAngle = ((value % 360f) + 360f) % 360f;
		applyTheme();
	}
	public int getCustomOpacityPercent() { return customOpacityPercent; }
	public void setCustomOpacityPercent(int value) { customOpacityPercent = Math.max(0, Math.min(100, value)); applyTheme(); }

	public boolean isCustomChromaEnabled() { return customChromaEnabled; }
	public void setCustomChromaEnabled(boolean value) { customChromaEnabled = value; applyTheme(); }
	public float getCustomChromaSpeed() { return customChromaSpeed; }
	public void setCustomChromaSpeed(float value) { customChromaSpeed = Math.max(0.1f, Math.min(5f, value)); }

	public boolean isCustomApplyGradientToRows() { return customApplyGradientToRows; }
	public void setCustomApplyGradientToRows(boolean value) { customApplyGradientToRows = value; }

	private void applyTheme() {
		if (mode == Mode.CUSTOM) {
			int alpha = Math.round(customOpacityPercent / 100f * 255f);
			int c1, c2;
			if (customChromaEnabled) {
				c1 = (alpha << 24) | (RenderUtil.hsvToRgb(customChromaHueDegrees, 1f, 1f) & 0xFFFFFF);
				c2 = (alpha << 24) | (RenderUtil.hsvToRgb((customChromaHueDegrees + CHROMA_HUE_GAP_DEGREES) % 360f, 1f, 1f) & 0xFFFFFF);
			} else {
				c1 = (alpha << 24) | (customPin1Argb & 0xFFFFFF);
				c2 = (alpha << 24) | (customPin2Argb & 0xFFFFFF);
			}
			// Pin 1 is the theme's single representative color for every other chrome/text contrast decision
			// elsewhere in Theme — see its own doc comment on panelGradientActive for why the gradient itself
			// stays purely additive on top of that.
			Theme.setPanelBackground(c1);
			Theme.setPanelGradient(true, c2, customPin1Pos, customPin2Pos, customGradientMode, customGradientAngle);
			return;
		}
		int argb = switch (mode) {
			case BLACK -> BLACK_ARGB;
			case WHITE -> WHITE_ARGB;
			case TRANSPARENT -> (TRANSPARENT_ALPHA << 24) | (WHITE_ARGB & 0xFFFFFF);
			case CUSTOM -> throw new IllegalStateException("handled above");
		};
		Theme.setPanelBackground(argb);
		Theme.setPanelGradient(false, 0, 0f, 1f, Theme.GradientMode.LINEAR, 270f);
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("mode", mode.name());
		obj.addProperty("customPin1Argb", customPin1Argb);
		obj.addProperty("customPin2Argb", customPin2Argb);
		obj.addProperty("customPin1Pos", customPin1Pos);
		obj.addProperty("customPin2Pos", customPin2Pos);
		obj.addProperty("customGradientMode", customGradientMode.name());
		obj.addProperty("customGradientAngle", customGradientAngle);
		obj.addProperty("customOpacityPercent", customOpacityPercent);
		obj.addProperty("customChromaEnabled", customChromaEnabled);
		obj.addProperty("customChromaSpeed", customChromaSpeed);
		obj.addProperty("customApplyGradientToRows", customApplyGradientToRows);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("mode")) {
			try {
				mode = Mode.valueOf(obj.get("mode").getAsString());
			} catch (IllegalArgumentException ignored) {}
		}
		if (obj.has("customPin1Argb")) customPin1Argb = obj.get("customPin1Argb").getAsInt();
		if (obj.has("customPin2Argb")) customPin2Argb = obj.get("customPin2Argb").getAsInt();
		if (obj.has("customPin1Pos")) customPin1Pos = obj.get("customPin1Pos").getAsFloat();
		if (obj.has("customPin2Pos")) customPin2Pos = obj.get("customPin2Pos").getAsFloat();
		// Real migration needed (per "Do those now" — generalizing the old fixed horizontal/vertical boolean
		// into a real any-angle direction system): existing saved configs on disk only ever have the old
		// "customGradientHorizontal" key, never the new mode/angle ones — reading it as if it were the new
		// format would silently reset every existing Custom-theme user back to angle=0 (Vertical) regardless
		// of what they'd actually picked. old horizontal=true is exactly angle=270 and horizontal=false is
		// exactly angle=0 (see Theme.GradientMode's own doc comment for the derivation) — mapping old configs
		// through that exact equivalence reproduces their existing panel's appearance pixel-for-pixel before
		// they've ever touched the new controls.
		if (obj.has("customGradientMode")) {
			try {
				customGradientMode = Theme.GradientMode.valueOf(obj.get("customGradientMode").getAsString());
			} catch (IllegalArgumentException ignored) {}
			if (obj.has("customGradientAngle")) customGradientAngle = obj.get("customGradientAngle").getAsFloat();
		} else if (obj.has("customGradientHorizontal")) {
			customGradientMode = Theme.GradientMode.LINEAR;
			customGradientAngle = obj.get("customGradientHorizontal").getAsBoolean() ? 270f : 0f;
		}
		if (obj.has("customOpacityPercent")) customOpacityPercent = obj.get("customOpacityPercent").getAsInt();
		if (obj.has("customChromaEnabled")) customChromaEnabled = obj.get("customChromaEnabled").getAsBoolean();
		if (obj.has("customChromaSpeed")) customChromaSpeed = obj.get("customChromaSpeed").getAsFloat();
		if (obj.has("customApplyGradientToRows")) customApplyGradientToRows = obj.get("customApplyGradientToRows").getAsBoolean();
		applyTheme();
	}

	@Override
	public String getDescription() {
		return "Lets you choose the mod menu's overall panel background theme (White/Black/Transparent).";
	}
}
