package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.gui.Theme;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** Lets the player pick the GUI's accent color. The chosen color is the single source of truth
 *  (stored as ARGB); hue/saturation/value are derived from it on demand for rendering the picker,
 *  never stored separately, so there's nothing that can fall out of sync.
 *
 * <p>Chroma (per user request): cycles {@link Theme#accent()} itself through the hue wheel instead of
 * being a separate system layered on top, so every existing use of the accent color (menu highlights,
 * knobs, sliders, the picker's own reset swatch) gets it for free with no per-feature change needed.
 * Deliberately scoped to just this global accent, not a mod-wide refactor of every feature's own
 * independently-configurable colors (highlight colors, pane colors, etc.) — see project notes. */
public class GuiColorFeature extends Feature {
	private int colorArgb = 0xFFCC3333;
	private boolean chromaEnabled = false;
	private float chromaSaturation = 1f;
	// "Speed" as a multiplier on a fixed base rate (full hue cycle in ~6s at 1.0x), not a raw
	// degrees-per-tick value — a multiplier reads more intuitively on a slider than a raw rate would.
	private float chromaSpeed = 1f;
	private float chromaHueDegrees = 0f;
	private static final float BASE_DEGREES_PER_TICK = 3f;

	public GuiColorFeature() {
		// enabledByDefault=true even though isToggleable()=false: this feature has no visible on/off,
		// but onEnable() is (ab)used as an init hook to apply the theme color at startup.
		super("gui_color", "GUI Color", FeatureCategory.ABOUT, true);
	}

	@Override
	protected void onEnable() {
		Theme.setAccent(colorArgb);
	}

	// Non-toggleable features always tick (see FeatureRegistry.tickAll's own doc comment), which is
	// exactly what this needs — chroma must keep advancing regardless of any per-feature enabled flag,
	// since this feature doesn't have one in the normal sense.
	@Override
	public void onTick(Minecraft client) {
		if (!chromaEnabled) return;
		chromaHueDegrees = (chromaHueDegrees + BASE_DEGREES_PER_TICK * chromaSpeed) % 360f;
		Theme.setAccent(RenderUtil.hsvToRgb(chromaHueDegrees, chromaSaturation, 1f));
	}

	public int getColor() {
		return colorArgb;
	}

	public void setColor(int argb) {
		colorArgb = 0xFF000000 | (argb & 0xFFFFFF);
		if (!chromaEnabled) Theme.setAccent(colorArgb);
	}

	public boolean isChromaEnabled() {
		return chromaEnabled;
	}

	public void setChromaEnabled(boolean value) {
		chromaEnabled = value;
		// Snap back to the real chosen color the instant chroma turns off, instead of leaving the accent
		// stuck on whatever hue the cycle last landed on until something else happens to re-set it.
		if (!value) Theme.setAccent(colorArgb);
	}

	public float getChromaSaturation() {
		return chromaSaturation;
	}

	public void setChromaSaturation(float value) {
		chromaSaturation = RenderUtil.clamp01(value);
	}

	public float getChromaSpeed() {
		return chromaSpeed;
	}

	public void setChromaSpeed(float value) {
		chromaSpeed = Math.max(0.1f, Math.min(5f, value));
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	// Per user request ("Remove the gui color module, since the accent color is now in the panel theme
	// thing. It should just always live there, even if the theme isnt custom, since all themes use the
	// accent color"): this used to be its own special-cased top-level "swatch row" module. The underlying
	// color/chroma state and logic in this class are unchanged and still the single source of truth (Panel
	// Theme's own settings panel reads/writes this exact instance via FeatureRegistry.get("gui_color")) —
	// only its old standalone top-level row is gone, hidden from the module list (and CTRL+F/search-jump)
	// entirely rather than deleting the class, since deleting it would mean re-plumbing persistence and the
	// shared color-picker widget code from scratch for no real benefit.
	@Override
	public boolean isHiddenFromGui() {
		return true;
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	@Override
	public Integer getPersistedColor() {
		return colorArgb;
	}

	@Override
	public void loadPersistedColor(int argb) {
		colorArgb = 0xFF000000 | (argb & 0xFFFFFF);
		if (!chromaEnabled) Theme.setAccent(colorArgb);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("chromaEnabled", chromaEnabled);
		obj.addProperty("chromaSaturation", chromaSaturation);
		obj.addProperty("chromaSpeed", chromaSpeed);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("chromaEnabled")) chromaEnabled = obj.get("chromaEnabled").getAsBoolean();
		if (obj.has("chromaSaturation")) chromaSaturation = obj.get("chromaSaturation").getAsFloat();
		if (obj.has("chromaSpeed")) chromaSpeed = obj.get("chromaSpeed").getAsFloat();
	}

	@Override
	public String getDescription() {
		return "Lets you pick the mod menu's accent color.";
	}
}
