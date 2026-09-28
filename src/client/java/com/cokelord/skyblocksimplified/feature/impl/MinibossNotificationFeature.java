package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request: shows a title (and plays a sound) the moment any slayer miniboss spawns, across every
 * slayer type.
 *
 * <p>Real bug found (per user report — "The miniboss notification triggers on detection of mobs, which
 * means it triggers on others minibosses. It should trigger on the chat message not on mob detection.
 * 'SLAYER MINI-BOSS Primordial Jockey has spawned!'"): the original version scanned nearby entities by name,
 * which can't distinguish a miniboss YOU spawned from one that happens to be alive nearby because someone
 * else nearby is fighting their own. Hypixel's own real chat line is scoped correctly server-side (it's
 * only ever sent for a miniboss that's actually relevant to you), so detecting off that line entirely
 * instead of scanning the world removes the false-positive class of bug outright rather than trying to
 * layer an ownership check onto entity scanning the way the boss highlights' own "Spawned by:" check does.
 */
public class MinibossNotificationFeature extends Feature {
	// Tolerant \D*? gaps between the fixed keywords, matching the exact same tolerance style established for
	// other real-but-unconfirmed-byte-for-byte Hypixel lines in this project (PET_EQUIP_PATTERN/
	// PestSpawnAlertFeature) — this exact line hasn't been independently captured raw, so this doesn't assume
	// zero extra formatting/symbols around the fixed words.
	//
	// Real bug found (per user report — "The miniboss notification doesnt say the miniboss name, it just
	// says '!'"): the exact same lazy-name-group bug PET_EQUIP_PATTERN's own doc comment already documents —
	// the name group's char class includes a space, so a LAZY "+?" is satisfied by capturing a single space
	// character and letting the trailing lazy "\D*?has" (which also matches letters/spaces) swallow the real
	// name instead, since that's a shorter overall match. Made greedy (no trailing "?") so it consumes every
	// real name character before backtracking — same fix, same root cause.
	private static final Pattern MINIBOSS_SPAWN_PATTERN = Pattern.compile(
		"SLAYER\\D*?MINI-BOSS\\D*?([A-Za-z' ]+)\\D*?has\\D*?spawned", Pattern.CASE_INSENSITIVE);

	private final CustomSoundOption sound = new CustomSoundOption(TerminalSoundsFeature.SOUND_IDS, TerminalSoundsFeature.SOUND_LABELS);

	private static boolean listenerRegistered = false;
	private static MinibossNotificationFeature instance;

	public MinibossNotificationFeature() {
		super("miniboss_notification", "Miniboss Notification", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Slayers"; }

	public CustomSoundOption getSound() { return sound; }

	@Override
	protected void onEnable() {
		if (listenerRegistered) return;
		listenerRegistered = true;
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (instance != null && instance.isEnabled()) {
				try {
					instance.onChatMessage(message.getString());
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Miniboss Notification chat parse failed, skipping this line", e);
				}
			}
			return true;
		});
	}

	private void onChatMessage(String text) {
		Matcher matcher = MINIBOSS_SPAWN_PATTERN.matcher(text);
		if (!matcher.find()) return;
		fireNotification(matcher.group(1).strip());
	}

	private void fireNotification(String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.gui.hud.setTimes(5, 40, 10);
		mc.gui.hud.setTitle(Component.literal("§e" + name + "!"));
		sound.play();
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.add("sound", sound.toJson());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("sound")) sound.fromJson(obj.get("sound"));
	}

	@Override
	public String getDescription() {
		return "Shows a title (and plays a sound) when Hypixel's own chat announces a slayer miniboss has spawned.";
	}
}
