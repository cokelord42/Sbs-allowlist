package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/**
 * Per user request ("basically just removes the shadow under all rendered text. Some people like the text
 * like this with no shading whatsoever."): strips the drop-shadow off every piece of text drawn through
 * {@code GuiGraphicsExtractor.text(...)} — vanilla HUD/GUI text and this mod's own both go through that one
 * choke point, so a single toggle here covers both. All the real work happens in {@code
 * com.cokelord.skyblocksimplified.mixin.TextShadowMixin}, which this class only gates.
 */
public class RemoveFontShadowFeature extends Feature {
	public RemoveFontShadowFeature() {
		super("remove_font_shadow", "Remove Minecraft Font Shading", FeatureCategory.INVENTORY, false);
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	public String getDescription() {
		return "Removes the drop-shadow under all rendered text, for a flatter look.";
	}
}
