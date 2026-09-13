package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.TabListReader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pest cooldown timer — ported from SkyHanni's PestSpawnTimer.kt. The tab-list "Pests" widget shows
 * a line like " Cooldown: 1m 58s" / " Cooldown: READY" / " Cooldown: MAX PESTS"; this reads that line
 * every tick via TabListReader.
 *
 * The countdown used to re-anchor cooldownEndMillis = now + parsedTime on EVERY tick the line was read,
 * not just when the parsed value actually changed — since Hypixel only pushes a new tablist value every
 * second or so, most ticks in between were re-parsing the same stale string against an always-advancing
 * "now", pushing the end time forward each time and producing exactly the "flickers/jumps by a few
 * seconds" symptom reported. Fixed by only re-anchoring when the parsed text differs from last tick's.
 *
 * Custom Cooldown (off by default) replaces Hypixel's own reported time entirely with a fixed duration
 * the user sets (135s default, up to 300s) — for armor-swapping setups where the tablist's own countdown
 * doesn't reflect the user's real effective cooldown. When enabled, a fresh countdown starts every time
 * the tablist transitions from ready/max-pests back to actively counting down.
 */
public class PestCooldownFeature extends TabWidgetOverlayFeature {
	private static final Pattern COOLDOWN_PATTERN =
		Pattern.compile("\\sCooldown: (?:(?<time>\\d{1,2}[ms](?: \\d{1,2}s?)?)|(?<ready>READY)|(?<maxPests>MAX PESTS)).*");
	private static final Pattern TIME_PART = Pattern.compile("(?<value>\\d{1,2})(?<unit>[ms])");

	public static final int DEFAULT_CUSTOM_SECONDS = 135;
	public static final int MAX_CUSTOM_SECONDS = 300;

	private long cooldownEndMillis = -1;
	private boolean ready = false;
	private boolean maxPests = false;
	private boolean warnedReady = false;
	private String lastParsedTime = null;
	private boolean wasCountingDown = false;

	private boolean customCooldownEnabled = false;
	private int customCooldownSeconds = DEFAULT_CUSTOM_SECONDS;

	public PestCooldownFeature() {
		super("pest_timer", "Pest Timer", FeatureCategory.FARMING, "Pest farming", 0.01f, 0.5f);
	}

	public boolean isCustomCooldownEnabled() {
		return customCooldownEnabled;
	}

	public void setCustomCooldownEnabled(boolean v) {
		customCooldownEnabled = v;
	}

	public int getCustomCooldownSeconds() {
		return customCooldownSeconds;
	}

	public void setCustomCooldownSeconds(int v) {
		customCooldownSeconds = Math.max(1, Math.min(MAX_CUSTOM_SECONDS, v));
	}

	@Override
	protected boolean requiresGarden() {
		return true;
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled()) return;
		boolean found = false;
		boolean isCountingDownNow = false;
		for (String line : TabListReader.readLines()) {
			Matcher matcher = COOLDOWN_PATTERN.matcher(line);
			if (!matcher.find()) continue;
			found = true;
			ready = matcher.group("ready") != null;
			maxPests = matcher.group("maxPests") != null;
			if (ready || maxPests) {
				cooldownEndMillis = -1;
				warnedReady = false;
				lastParsedTime = null;
			} else {
				String time = matcher.group("time");
				isCountingDownNow = time != null;
				if (time != null) {
					if (customCooldownEnabled) {
						if (!wasCountingDown) {
							cooldownEndMillis = System.currentTimeMillis() + customCooldownSeconds * 1000L;
							warnedReady = false;
						}
					} else if (!time.equals(lastParsedTime)) {
						cooldownEndMillis = System.currentTimeMillis() + parseTimeLeft(time);
						warnedReady = false;
					}
					lastParsedTime = time;
				}
			}
			break;
		}
		wasCountingDown = isCountingDownNow;
		if (!found) return;

		if (!ready && !maxPests && cooldownEndMillis > 0 && System.currentTimeMillis() >= cooldownEndMillis && !warnedReady) {
			warnedReady = true;
			var player = client.player;
			if (player != null) {
				client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("§cPest spawn cooldown has expired!"));
			}
		}
	}

	private long parseTimeLeft(String time) {
		long millis = 0;
		Matcher part = TIME_PART.matcher(time);
		while (part.find()) {
			int value = Integer.parseInt(part.group("value"));
			millis += "m".equals(part.group("unit")) ? value * 60_000L : value * 1_000L;
		}
		return millis;
	}

	@Override
	protected List<String> currentLines() {
		String status;
		if (maxPests) {
			status = "§cMax Pests!";
		} else if (ready || cooldownEndMillis < 0) {
			status = "§aReady!";
		} else {
			long remaining = cooldownEndMillis - System.currentTimeMillis();
			if (remaining <= 0) {
				status = "§aReady!";
			} else {
				status = formatDuration(remaining);
			}
		}
		return List.of("§ePest Cooldown: §b" + status);
	}

	private String formatDuration(long millis) {
		long totalSeconds = millis / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
	}

	// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when i
	// relaunch"): these used to build a brand new JsonObject, discarding the position/scale the
	// TabWidgetOverlayFeature base class now persists (see that class's own doc comment) — now calls
	// through super and merges its own keys onto the same object.
	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = (JsonObject) super.savePersistedData();
		obj.addProperty("customCooldownEnabled", customCooldownEnabled);
		obj.addProperty("customCooldownSeconds", customCooldownSeconds);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("customCooldownEnabled")) customCooldownEnabled = obj.get("customCooldownEnabled").getAsBoolean();
		if (obj.has("customCooldownSeconds")) customCooldownSeconds = obj.get("customCooldownSeconds").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Shows your remaining pest-spawn cooldown, read from the tab list.";
	}
}
