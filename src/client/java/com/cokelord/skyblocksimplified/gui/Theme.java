package com.cokelord.skyblocksimplified.gui;

/** Holds the user's chosen GUI accent color (set via the "GUI Color" feature). Panel colors elsewhere
 *  still stay fixed dark neutrals for contrast. Per user follow-up request ("make sure the GUI color
 *  applies on ALL colors in the GUI"), the toggle-on track and the "listening for a keybind" box in
 *  MainScreen now read this live too (see {@code colorToggleOn()}/{@code colorKeybindListening()} there)
 *  instead of being independently hardcoded — only close/remove ('x') buttons still stay literal red, kept
 *  deliberately since a destructive action staying red regardless of theme is a real usability convention,
 *  not an oversight. */
public final class Theme {
	private static volatile int accent = 0xFFCC3333;

	// Real panel background theme (per user clarification: "Themes clarification: NOT mod accent color...
	// wants a SEPARATE module with three real panel themes: White/Black/Transparent"), set by
	// PanelThemeFeature. Default matches MainScreen's own original COLOR_PANEL_BG constant so an unmodified
	// install renders identically to before this existed.
	private static volatile int panelBackground = 0xF0101010;

	// Per user request (Custom panel theme — "a two-pin drag gradient editor... horizontal/vertical
	// orientation"): purely an ADDITIVE decorative detail for how the panel's own background rect actually
	// gets painted (see MainScreen's own panel-fill call sites) — deliberately NOT plumbed into
	// isLightTheme()/chrome()/text() below, which all key off panelBackground alone. panelBackground itself
	// always stays set to the gradient's own pin-1 color whenever this is active (see PanelThemeFeature's own
	// applyTheme()), so every one of this class's existing contrast/hierarchy decisions keeps working exactly
	// as already tested for Black/White/Transparent, using pin-1 as the theme's single representative color,
	// with zero new logic to get wrong there.
	private static volatile boolean panelGradientActive = false;
	private static volatile int panelGradientColor2 = 0xF0101010;
	private static volatile float panelGradientPos1 = 0f;
	private static volatile float panelGradientPos2 = 1f;

	/** Per user request ("Do those now" — add an angular/directional gradient mode with a clock-hand angle
	 *  control, and a radial 'feathered circle' mode): generalizes the old plain horizontal/vertical boolean
	 *  into a real direction system. LINEAR now supports ANY angle (0°=top=pin1, clockwise — see
	 *  {@code angleDegrees}), which fully subsumes the old two fixed orientations (old horizontal=true is
	 *  exactly angle=270, old horizontal=false/"Vertical" is exactly angle=0 — see PanelThemeFeature's own
	 *  migration in its loadPersistedData for existing saved configs). RADIAL is a new "feathered circle" mode
	 *  — pin1 fills a center circle sized by pos1, feathering out to pin2 by pos2, reusing the exact same
	 *  pos1/pos2 fields as radial-distance fractions instead of linear-position fractions. */
	public enum GradientMode { LINEAR, RADIAL }
	private static volatile GradientMode panelGradientMode = GradientMode.LINEAR;
	private static volatile float panelGradientAngle = 270f;

	private Theme() {}

	public static int accent() {
		return accent;
	}

	public static void setAccent(int argb) {
		accent = argb;
	}

	public static int panelBackground() {
		return panelBackground;
	}

	public static void setPanelBackground(int argb) {
		panelBackground = argb;
	}

	/** Sets (or, with {@code active=false}, clears) the two-pin gradient descriptor MainScreen's own
	 *  panel-fill code checks before deciding whether to paint a flat {@link #panelBackground()} or a real
	 *  two-stop linear gradient — see {@link #panelGradientActive} field's own doc comment for why this
	 *  never touches isLightTheme()/chrome()/text(). */
	public static void setPanelGradient(boolean active, int color2, float pos1, float pos2, GradientMode mode, float angleDegrees) {
		panelGradientActive = active;
		panelGradientColor2 = color2;
		panelGradientPos1 = pos1;
		panelGradientPos2 = pos2;
		panelGradientMode = mode;
		panelGradientAngle = angleDegrees;
	}

	public static boolean isPanelGradientActive() {
		return panelGradientActive;
	}

	/** Pin 1's color for the gradient is always {@link #panelBackground()} itself — see the field's own doc
	 *  comment for why. This is only pin 2. */
	public static int panelGradientColor2() {
		return panelGradientColor2;
	}

	public static float panelGradientPos1() {
		return panelGradientPos1;
	}

	public static float panelGradientPos2() {
		return panelGradientPos2;
	}

	public static GradientMode panelGradientMode() {
		return panelGradientMode;
	}

	public static float panelGradientAngle() {
		return panelGradientAngle;
	}

	/** Real bug found while planning the Angular/Radial redesign — the row/subtoggle background fills (see
	 *  MainScreen's own colorRowBg()/drawToggleRow) needed a way to sample the SAME live gradient at an
	 *  arbitrary fractional position within the panel's full span, without duplicating this class's own
	 *  direction math or reaching back into RenderUtil's texture-baking internals. Returns {@link
	 *  #panelBackground()} unchanged whenever the gradient isn't active, so every existing caller of
	 *  panelBackground() elsewhere keeps working unmodified. */
	public static int panelGradientColorAt(float fracX, float fracY, int fullWidthPx, int fullHeightPx) {
		if (!panelGradientActive) return panelBackground;
		return RenderUtil.colorAtFraction(panelBackground, panelGradientColor2, panelGradientPos1, panelGradientPos2,
			panelGradientMode, panelGradientAngle, fullWidthPx, fullHeightPx, fracX, fracY);
	}

	// Real bug found (per user report — "The themes don't change everything. They should change everything
	// in the mod menu itself and keep the same hierarchy of colors... The colors for the text should also
	// change when using White mode, they should all be black instead of white since the white blends in"):
	// PanelThemeFeature previously only ever swapped the outer panel fill; every other structural chrome
	// color in MainScreen (header strip, module rows, sub-tab tiles, search box, sliders, toggle track-off,
	// etc.) stayed hardcoded for the dark/Black look, so White/Transparent only ever recolored one rectangle
	// behind everything else that never moved. chrome()/text() below let MainScreen derive every one of
	// those constants from the live panel theme instead.
	//
	// Every one of those hardcoded constants is a pure gray (r==g==b), tuned by hand against the original
	// Black theme's panel base (0x101010, luminance 16). chrome() re-derives a background constant by
	// preserving its ORIGINAL proportional position relative to that base — how far toward black (if darker
	// than the base) or toward white (if lighter) it originally sat — and re-applies that same proportion
	// against the CURRENT theme's own panel luminance and its own remaining headroom toward black/white.
	// That's what keeps the real visual hierarchy (e.g. "the category part is a little lighter than the
	// module list") intact under every theme, rather than a flat per-channel offset that would just clip
	// everything to the same color the instant the new base sits near either extreme.
	private static final int BASE_PANEL_LUM = luminance(0x101010);

	private static int luminance(int rgb) {
		return (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
	}

	// Real bug found (per user report — "Now the text in transparent mode is fully transparent. It should be
	// white. Change the text i told you to put back to black back to white again aswell, like the current
	// selected category"): Transparent mode's RGB base is the SAME off-white as White mode (per its own
	// design — "same as white but just transparent"), so a pure-luminance check reads Transparent as "light"
	// too and inverted its text to near-black, same as White. White has an actual opaque light panel behind
	// it, so dark text is the right call there — but Transparent is mostly the (often dark) game world, not a
	// light panel, so the SAME inverted-to-black text read as invisible against it. A theme only genuinely
	// needs dark text once it's opaque enough to actually BE the light backdrop the text sits on.
	private static final int LIGHT_THEME_MIN_ALPHA = 0x80;

	/** True once the current panel theme reads as light overall AND opaque enough to actually be the surface
	 *  text sits on (White mode) rather than dark or mostly-see-through (Black/Transparent) — the signal
	 *  {@link #text(int)} uses to decide whether text needs to flip to stay readable. */
	public static boolean isLightTheme() {
		return luminance(panelBackground & 0xFFFFFF) > 128 && ((panelBackground >>> 24) & 0xFF) >= LIGHT_THEME_MIN_ALPHA;
	}

	/** Re-derives a hardcoded gray chrome BACKGROUND constant (originally tuned against the Black theme) so
	 *  it keeps its original relative lightness to the panel background under any theme. See the class doc
	 *  comment above for why a proportional re-derivation, not a flat offset, is what actually preserves the
	 *  hierarchy. */
	// Per user request ("White theme retune: background #C9C9C9, modules #E6E6E6, textboxes/misc #F0F0F0,
	// toggle-off #D1D1D1"): the proportional re-derivation below gets every OTHER chrome element close
	// enough to its original relative lightness automatically, but these three specific tiers were called
	// out by exact hex value, and the proportional math doesn't reliably land on an exact requested value —
	// only the three original constants MainScreen's own colorRowBg()/colorToggleOff()/colorSearchBg()
	// actually use are overridden here (module row fill, toggle-off track, textbox/search fill), each keyed
	// by the exact literal Black-theme original those functions pass in; every other chrome() caller still
	// falls through to the proportional formula unchanged, so this can't regress anything not explicitly
	// asked for. Only applies once White mode is fully opaque (isLightTheme()) — Transparent (same RGB base,
	// per its own "same as White, just transparent" design) still gets the proportional derivation so its
	// own alpha-fade logic isn't bypassed.
	private static int whiteModeOverride(int originalArgb) {
		return switch (originalArgb) {
			case 0xFF1B1B1B -> 0xFFE6E6E6; // colorRowBg() — module rows
			case 0xFF2A2A2A -> 0xFFD1D1D1; // colorToggleOff() — toggle-off track
			case 0xFF3A3A3A -> 0xFFF0F0F0; // colorSearchBg()/textbox fills
			default -> 0;
		};
	}

	public static int chrome(int originalArgb) {
		if (isLightTheme()) {
			int override = whiteModeOverride(originalArgb);
			if (override != 0) return override;
		}
		int origAlpha = (originalArgb >>> 24) & 0xFF;
		int origLum = luminance(originalArgb & 0xFFFFFF);
		int curLum = luminance(panelBackground & 0xFFFFFF);
		int newLum;
		float f;
		if (origLum >= BASE_PANEL_LUM) {
			f = (origLum - BASE_PANEL_LUM) / (float) (255 - BASE_PANEL_LUM);
			newLum = Math.round(curLum + f * (255 - curLum));
		} else {
			f = (BASE_PANEL_LUM - origLum) / (float) BASE_PANEL_LUM;
			newLum = Math.round(curLum * (1f - f));
		}
		newLum = Math.max(0, Math.min(255, newLum));

		// Real bug found (per user report — "Transparent should make everything transparent, but some parts
		// a little less/more transparent depending on if they need to be darker/lighter"): this used to keep
		// origAlpha (always 0xFF here) no matter what PanelThemeFeature set the panel's own alpha to, so
		// Transparent mode only ever faded the outer panel rectangle — every chrome element drawn on top of
		// it (module rows, config boxes, selector squares, sliders...) stayed fully opaque. Blend each
		// constant's alpha toward the panel's own current alpha, weighted by the same `f` used for its color
		// above: shades that sit close to the panel base (f near 0 — subtle background-y fills) fade in step
		// with the panel itself, while shades further from the base (f near 1 — bright borders/highlights,
		// meant to stand out) stay closer to fully opaque so they don't disappear over the game world. Under
		// Black/White (panelAlpha == 0xF0, already near-opaque) this only nudges background-y fills a few
		// percent more translucent, matching how the panel itself has always rendered.
		int panelAlpha = (panelBackground >>> 24) & 0xFF;
		int newAlpha = Math.round(panelAlpha + f * (origAlpha - panelAlpha));
		newAlpha = Math.max(0, Math.min(255, newAlpha));

		return (newAlpha << 24) | (newLum << 16) | (newLum << 8) | newLum;
	}

	// Per user request ("The text in the transparent mode is gray and not white, it should be that smooth
	// white i sent earlier, i think its like FBFAF5"): matches PanelThemeFeature's own WHITE_ARGB base
	// exactly, so Transparent-mode text is literally the same white the panel itself is built from.
	private static final int TRANSPARENT_TEXT_RGB = 0xFBFAF5;

	/** Re-derives a hardcoded light-gray chrome TEXT constant so it stays readable under any theme: under an
	 *  opaque light theme (White) it's inverted to a dark gray instead of the light gray that used to blend
	 *  straight into the light panel. Under Transparent (light RGB base, but excluded from the White branch
	 *  above by {@link #isLightTheme()}'s own alpha check) it's forced to a flat smooth white instead of
	 *  simply left at its original Black-theme-tuned gray shade — real bug found (per user report — "the text
	 *  in transparent mode is gray and not white"): once Transparent stopped taking the invert-to-black
	 *  branch, every text call just fell through to its ORIGINAL color unchanged, and almost every one of
	 *  those ~90 call sites passes a plain gray (0xFFAAAAAA/0xFFDDDDDD/etc., hand-tuned against the dark
	 *  Black panel) rather than white — only literal-white callers (e.g. the selected category label, which
	 *  the user pointed to as looking right) happened to already read correctly. Under Black, color is
	 *  returned unchanged, same as always.
	 *
	 *  <p>Real bug found (per repeated user report — "Actually fix Transparent theme text transparency", after
	 *  two earlier rounds still left it broken): this used to touch color only and always kept the input's
	 *  original alpha (every caller passes a plain 0xFF-opaque constant — see MainScreen's ~90 {@code
	 *  Theme.text(0xFF...)} call sites), so under Transparent mode every label/value string in the mod menu
	 *  stayed a fully solid block sitting on top of an otherwise near-see-through panel — exactly the "some
	 *  parts still aren't actually transparent" bug {@link #chrome} was already fixed for. Alpha is now ALSO
	 *  blended toward the panel's own current alpha, same idea as {@link #chrome}'s own blend but biased more
	 *  toward staying opaque (65%) since text specifically needs to stay legible over the game world behind it
	 *  — a background chrome box can fade nearly all the way, but the same fade on text makes it unreadable. */
	public static int text(int originalArgb) {
		int origAlpha = (originalArgb >>> 24) & 0xFF;
		int rgb = originalArgb & 0xFFFFFF;
		if (isLightTheme()) {
			int r = 255 - ((rgb >> 16) & 0xFF);
			int g = 255 - ((rgb >> 8) & 0xFF);
			int b = 255 - (rgb & 0xFF);
			rgb = (r << 16) | (g << 8) | b;
		} else if (luminance(panelBackground & 0xFFFFFF) > 128) {
			rgb = TRANSPARENT_TEXT_RGB;
		}
		int panelAlpha = (panelBackground >>> 24) & 0xFF;
		int newAlpha = Math.round(panelAlpha + 0.65f * (origAlpha - panelAlpha));
		newAlpha = Math.max(0, Math.min(255, newAlpha));
		return (newAlpha << 24) | rgb;
	}
}
