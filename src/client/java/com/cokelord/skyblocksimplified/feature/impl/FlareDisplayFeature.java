package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.combat.FlareDetector;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Shows the remaining time on active Flares (Warning/Alert/SOS distress-signal markers) and warns
 * shortly before one expires — ported from SkyHanni's FlareDisplay.kt, keeping its confirmed 3-minute
 * flare lifetime and its skin-texture identification (via FlareDetector). Scoped to the timer readout;
 * SkyHanni's world-space wireframe/sphere outline rendering around the flare entity itself isn't ported.
 */
public class FlareDisplayFeature extends TabWidgetOverlayFeature {
	private static final long MAX_FLARE_TICKS = 3 * 60 * 20L;
	private static final long WARN_THRESHOLD_TICKS = 20L * 10;

	private final Map<FlareDetector.FlareType, Long> remainingTicks = new EnumMap<>(FlareDetector.FlareType.class);
	private final Map<FlareDetector.FlareType, Boolean> warned = new EnumMap<>(FlareDetector.FlareType.class);
	private int tickCounter = 0;

	public FlareDisplayFeature() {
		super("combat_flare", "Flare", FeatureCategory.COMBAT, null, 0.01f, 0.8f);
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled() || client.level == null) return;
		// A countdown timer doesn't need 20/sec precision — only re-scan every entity 5x/sec, a few
		// hundred milliseconds of slack on the expiry warning is imperceptible.
		if (tickCounter++ % 4 != 0) return;
		remainingTicks.clear();
		for (Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof ArmorStand stand)) continue;
			FlareDetector.FlareType type = FlareDetector.getFlareType(stand);
			if (type == null) continue;
			long remaining = MAX_FLARE_TICKS - stand.tickCount;
			remainingTicks.merge(type, remaining, Math::max);
		}

		for (FlareDetector.FlareType type : FlareDetector.FlareType.values()) {
			Long remaining = remainingTicks.get(type);
			if (remaining == null) {
				warned.put(type, false);
				continue;
			}
			boolean aboutToExpire = remaining >= 0 && remaining <= WARN_THRESHOLD_TICKS;
			if (aboutToExpire && !Boolean.TRUE.equals(warned.get(type))) {
				warned.put(type, true);
				var player = client.player;
				if (player != null) {
					client.gui.hud.getChat().addClientSystemMessage(Component.literal(displayName(type) + " §eexpires in: §b" + (remaining / 20) + "s"));
				}
			}
		}
	}

	private String displayName(FlareDetector.FlareType type) {
		return switch (type) {
			case SOS -> "§5SOS Flare";
			case ALERT -> "§9Alert Flare";
			case WARNING -> "§aWarning Flare";
		};
	}

	@Override
	protected List<String> currentLines() {
		if (remainingTicks.isEmpty()) return List.of();
		return remainingTicks.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(e -> displayName(e.getKey()) + ": §b" + formatTicks(e.getValue()))
			.toList();
	}

	private String formatTicks(long ticks) {
		long totalSeconds = Math.max(0, ticks) / 20;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
	}

	@Override
	public String getDescription() {
		return "Shows the remaining time on any active Flare and warns shortly before it expires.";
	}
}
