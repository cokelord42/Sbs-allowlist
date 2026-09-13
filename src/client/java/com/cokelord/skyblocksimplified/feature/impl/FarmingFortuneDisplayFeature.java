package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.TabListReader;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Displays the universal Farming Fortune stat from the tab-list "Stats" widget, plus the current crop's
 * own Fortune stat when the tablist also exposes it — ported from SkyHanni's FarmingFortuneDisplay.kt,
 * which sums exactly these two tablist values ({@code tabFortuneUniversal + tabFortuneCrop}) for its
 * "true" total. The full SkyHanni feature also derives item-lore-based reforge/gemstone/dedication
 * breakdowns, which needs an item-lore reading utility this project doesn't have yet; this covers the
 * confirmed, self-contained tablist part.
 *
 * Crop names are the real ones from SkyHanni's CropType enum (Wheat, Carrot, Potato, Nether Wart,
 * Pumpkin, Melon Slice, Cocoa Beans, Sugar Cane, Cactus, Mushroom).
 */
public class FarmingFortuneDisplayFeature extends TabWidgetOverlayFeature {
	private static final Pattern FARMING_FORTUNE = Pattern.compile("Farming Fortune:\\s*\\D*(?<amount>[\\d,.]+)");
	private static final Pattern CROP_FORTUNE = Pattern.compile(
		"(?:Wheat|Carrot|Potato|Nether Wart|Pumpkin|Melon Slice|Cocoa Beans|Sugar Cane|Cactus|Mushroom|Sunflower|Moonflower) Fortune:\\s*\\D*(?<amount>[\\d,.]+)");

	private Double universalAmount = null;
	private Double cropAmount = null;
	public FarmingFortuneDisplayFeature() {
		super("farming_fortune_display", "Farming Fortune Display", FeatureCategory.FARMING, "Farming", 0.01f, 0.44f);
	}

	@Override
	protected boolean requiresGarden() {
		return true;
	}

	@Override
	public void onTick(net.minecraft.client.Minecraft client) {
		if (!isEnabled()) return;
		universalAmount = null;
		cropAmount = null;
		for (String line : TabListReader.readLines()) {
			Matcher universalMatcher = FARMING_FORTUNE.matcher(line);
			if (universalMatcher.find()) {
				if (universalAmount == null) universalAmount = parseAmount(universalMatcher.group("amount"));
				continue; // already handled by the universal pattern — never a crop-fortune candidate too
			}
			if (cropAmount == null) {
				Matcher matcher = CROP_FORTUNE.matcher(line);
				if (matcher.find()) {
					cropAmount = parseAmount(matcher.group("amount"));
				}
			}
		}
	}

	private static Double parseAmount(String raw) {
		try {
			return Double.parseDouble(raw.replace(",", ""));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	protected List<String> currentLines() {
		if (universalAmount == null) return List.of();
		// Crop Fortune isn't always in the tablist (it needs the "latest Crop Fortune" Stats-widget option
		// enabled) — rather than silently showing an incomplete total as if it were the real one, this
		// says so explicitly, same as SkyHanni's own missing-crop-fortune warning.
		if (cropAmount == null) {
			return List.of(
				"§6Farming Fortune§7: §e" + formatAmount(universalAmount),
				"§7(Crop Fortune not in tablist — total may be incomplete)"
			);
		}
		return List.of("§6Farming Fortune§7: §e" + formatAmount(universalAmount + cropAmount));
	}

	private static String formatAmount(double value) {
		long rounded = Math.round(value);
		return String.format(Locale.ROOT, "%,d", rounded);
	}

	@Override
	public String getDescription() {
		return "Shows your total Farming Fortune (universal + current crop) from the tab list.";
	}
}
