package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.gui.MsdfFont;
import com.cokelord.skyblocksimplified.gui.MsdfFont.FontChoice;
import net.minecraft.network.chat.FontDescription;

import java.util.HashMap;
import java.util.Map;

/**
 * Per user request ("just specifically quicksand only for the gui... No font importer or anything"): a plain
 * on/off switch for the mod's custom MSDF font (see {@link com.cokelord.skyblocksimplified.gui.MsdfFont}).
 *
 * <p>Per later user request ("Found two fonts i like. Wire in medium for both." / "is this tailored to
 * quicksand only, or would another font be the same?"): expanded from Quicksand-only into a small font picker
 * ({@link #fontChoice}) — {@link MsdfFont}'s own rendering is already font-agnostic, so adding a bundled
 * choice is just generating its atlas the same way and adding an enum entry there, not touching this class
 * beyond the persistence plumbing below.
 *
 * <p>Per later user request ("Remove the whole apply to gui elements thing. First of all it doesnt work...
 * it should only apply to the notification toasts. Actually, make a toggle like we have before where it
 * replaces the minecraft font entirely instead"): the earlier "apply to GUI elements" subtoggle only ever
 * covered the notification toast (every other mod GUI/HUD element still called vanilla's Font directly, so
 * toggling it visibly changed nothing for them — hence "it doesnt work"), and covering the rest for real would
 * mean converting dozens of feature files one by one. Replaced with {@link #replaceMinecraftFontGlobally}: a
 * real vanilla custom-font redirect (see {@code StyleDefaultFontMixin}), which changes what
 * {@code Style.getFont()} resolves to for its DEFAULT case everywhere in the client, not just this mod's own
 * code — so it reaches every mod GUI element "for free," exactly as the user reasoned ("This will apply to
 * the gui elements anyway cause they use the minecraft font"). The tradeoff, since there is one: this uses a
 * REAL vanilla FreeType-rendered font (a bundled TTF + font provider JSON per {@link FontChoice}), not this
 * mod's own MSDF pipeline — vanilla's text pipeline has no hook for an MSDF decode, so anything routed through
 * vanilla's own Font/Style system needs a font vanilla can rasterize itself. It's also a genuinely global
 * redirect with no way to scope it to "only this mod's own elements": turning it on changes vanilla UI text
 * too (tooltips, inventory, chat, etc.), which is why it defaults to off and this class's own on/off switch
 * still separately gates the notification toast + mod menu's own (MSDF) rendering regardless of this setting.
 */
public class CustomMenuFontFeature extends Feature {
	private FontChoice fontChoice = FontChoice.QUICKSAND;
	private boolean replaceMinecraftFontGlobally = false;
	// Per user request ("The fonts are also a little too big when replacing minecrafts font specifically.
	// Add a font size slider if the replace minecraft font option is on... 10-15" / "the new font size slider
	// should only affect the minecraft font change and not any font in the mod menu or the notification
	// toast as those are already tailored to perfection"): this ONLY sizes the vanilla-rendered global
	// replacement font (see globalReplacementFontOrNull/vanillaFontId(int)) — MsdfFont's own TARGET_LINE_HEIGHT
	// for the mod menu/toast is a completely separate constant this never touches.
	public static final int MIN_GLOBAL_FONT_SIZE = 10;
	public static final int MAX_GLOBAL_FONT_SIZE = 15;
	private int globalFontSize = 11;

	public CustomMenuFontFeature() {
		super("custom_menu_font", "Custom Menu Font", FeatureCategory.ABOUT, true);
	}

	@Override
	protected void onEnable() {
		MsdfFont.setFont(fontChoice);
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	@Override
	public String getDescription() {
		return "Renders the mod menu and notification toasts in a custom font instead of Minecraft's default font.";
	}

	public FontChoice getFontChoice() {
		return fontChoice;
	}

	public void setFontChoice(FontChoice fontChoice) {
		this.fontChoice = fontChoice;
		MsdfFont.setFont(fontChoice);
	}

	public boolean isReplaceMinecraftFontGlobally() {
		return replaceMinecraftFontGlobally;
	}

	public void setReplaceMinecraftFontGlobally(boolean replaceMinecraftFontGlobally) {
		this.replaceMinecraftFontGlobally = replaceMinecraftFontGlobally;
	}

	public int getGlobalFontSize() {
		return globalFontSize;
	}

	public void setGlobalFontSize(int globalFontSize) {
		this.globalFontSize = Math.max(MIN_GLOBAL_FONT_SIZE, Math.min(MAX_GLOBAL_FONT_SIZE, globalFontSize));
	}

	/** Whether the mod menu/notification toast should render through {@link MsdfFont} right now — this
	 *  feature's own master on/off switch, nothing more. */
	public static boolean isMenuFontActive() {
		return FeatureRegistry.get("custom_menu_font") instanceof CustomMenuFontFeature f && f.isEnabled();
	}

	/** What {@code Style.getFont()} should return in place of {@link FontDescription#DEFAULT}, or {@code null}
	 *  to leave vanilla's own resolution alone — see {@code StyleDefaultFontMixin}, the only caller. */
	public static FontDescription globalReplacementFontOrNull() {
		if (!(FeatureRegistry.get("custom_menu_font") instanceof CustomMenuFontFeature f) || !f.isEnabled() || !f.replaceMinecraftFontGlobally) {
			return null;
		}
		return new FontDescription.Resource(f.fontChoice.vanillaFontId(f.globalFontSize));
	}

	@Override
	public Map<String, Float> getPersistedFloats() {
		Map<String, Float> values = new HashMap<>();
		values.put("fontChoice", (float) fontChoice.ordinal());
		values.put("replaceGlobally", replaceMinecraftFontGlobally ? 1f : 0f);
		values.put("globalFontSize", (float) globalFontSize);
		return values;
	}

	@Override
	public void loadPersistedFloats(Map<String, Float> values) {
		if (values.containsKey("fontChoice")) {
			int ordinal = Math.round(values.get("fontChoice"));
			FontChoice[] all = FontChoice.values();
			fontChoice = ordinal >= 0 && ordinal < all.length ? all[ordinal] : FontChoice.QUICKSAND;
		}
		if (values.containsKey("replaceGlobally")) replaceMinecraftFontGlobally = values.get("replaceGlobally") >= 0.5f;
		if (values.containsKey("globalFontSize")) setGlobalFontSize(Math.round(values.get("globalFontSize")));
		MsdfFont.setFont(fontChoice);
	}
}
