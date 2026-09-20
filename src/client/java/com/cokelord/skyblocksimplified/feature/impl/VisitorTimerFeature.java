package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.TabListReader;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Next-visitor countdown — ported from SkyHanni's GardenVisitorTimer.kt. Reads the tab-list
 * " Next Visitor: 11m" / " Next Visitor: Queue Full!" line directly instead of SkyHanni's own
 * millisecond-precise extrapolation between updates (which depends on a persisted per-profile average
 * visitor interval this project doesn't track yet) — this shows Hypixel's own rounded value as-is.
 */
public class VisitorTimerFeature extends TabWidgetOverlayFeature {
	private static final Pattern NEXT_VISITOR = Pattern.compile("Next Visitor: (?<info>.*)");

	private String display = null;

	@Override
	protected boolean requiresGarden() {
		return true;
	}

	public VisitorTimerFeature() {
		super("visitor_timer", "Visitor Timer", FeatureCategory.FARMING, "Visitors", 0.01f, 0.68f);
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled()) return;
		display = null;
		for (String line : TabListReader.readLines()) {
			Matcher matcher = NEXT_VISITOR.matcher(line);
			if (matcher.find()) {
				String info = matcher.group("info").trim();
				display = "Queue Full!".equals(info) ? "§6Queue Full!" : "§b" + info;
				break;
			}
		}
	}

	@Override
	protected List<String> currentLines() {
		if (display == null) return List.of();
		return List.of("§eNext Visitor: " + display);
	}

	@Override
	public String getDescription() {
		return "Countdown to your next garden visitor, read from the tab list.";
	}
}
