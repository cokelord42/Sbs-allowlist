package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.TabListReader;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Displays the Bonus Pest Chance stat from the tab-list "Stats" widget — ported from SkyHanni's
 * BonusPestChanceDisplay.kt. The original pattern matches a specific stat icon glyph before the number;
 * since that exact glyph isn't confirmed here, this matches the number generically after the label
 * instead (robust to the icon either way, since we don't render or depend on the icon itself).
 */
public class BonusPestChanceFeature extends TabWidgetOverlayFeature {
	private static final Pattern BONUS_PEST_CHANCE = Pattern.compile("Bonus Pest Chance:\\s*\\D*(?<amount>[\\d,.]+)");

	private String amount = null;

	@Override
	protected boolean requiresGarden() {
		return true;
	}

	public BonusPestChanceFeature() {
		super("bonus_pest_chance_display", "Bonus Pest Chance Display", FeatureCategory.FARMING, "Pest farming", 0.01f, 0.56f);
	}

	@Override
	public void onTick(net.minecraft.client.Minecraft client) {
		if (!isEnabled()) return;
		amount = null;
		for (String line : TabListReader.readLines()) {
			Matcher matcher = BONUS_PEST_CHANCE.matcher(line);
			if (matcher.find()) {
				amount = matcher.group("amount");
				break;
			}
		}
	}

	@Override
	protected List<String> currentLines() {
		if (amount == null) return List.of();
		return List.of("§2Bonus Pest Chance §f" + amount + "%");
	}

	@Override
	public String getDescription() {
		return "Shows your current Bonus Pest Chance stat, read from the tab list.";
	}
}
