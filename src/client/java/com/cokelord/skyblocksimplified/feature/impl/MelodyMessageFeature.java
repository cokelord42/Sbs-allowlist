package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.TerminalTracker;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * Announces the F7 boss-fight melody terminal opening to the party, and (optionally) posts local
 * "Melody 25/50/75%" progress messages by watching which row the lime-terracotta progress marker sits in.
 * Ported from Odin's {@code MelodyMessage.kt} — its cross-user WebSocket progress broadcast/display now
 * lives as a subtoggle on {@link MelodyDisplayFeature} instead (the "party melody display" the user already
 * had), so it can replace that feature's chat-parsed single-player alert with the real live multi-player
 * table when turned on, rather than duplicating a second GUI element here.
 */
public class MelodyMessageFeature extends Feature {
	// Row 2/3/4 = attempt 1/2/3 out of 4 (row 1 is the very start, nothing to announce yet) — matches
	// TerminalTracker.MelodyState's own "movingRow 1..4 = attempt 0/4..3/4" mapping.
	private static final Map<Integer, Integer> CLAY_ROW_ATTEMPT = Map.of(2, 1, 3, 2, 4, 3);

	private boolean sendOpenMessage = true;
	private String openMessage = "Melody Terminal start!";
	private boolean sendProgress = false;
	// Per user request ("allow users to select the message that should be typed before the (1/4) and so
	// on"): the fixed "Melody 25%"/"50%"/"75%" strings are now a user-editable prefix + a real "(N/4)"
	// attempt fraction instead of a hardcoded percentage.
	private String progressMessage = "Melody";

	private Integer lastClayRow = null;

	private static boolean listenersRegistered = false;
	private static MelodyMessageFeature instance;

	public MelodyMessageFeature() {
		super("melody_message", "Melody Message", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		lastClayRow = null;
		if (!listenersRegistered) {
			listenersRegistered = true;
			TerminalTracker.addOpenListener(type -> {
				if (instance == null || !instance.isEnabled() || type != TerminalTracker.TerminalType.MELODY) return;
				if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return;
				instance.lastClayRow = null;
				if (instance.sendOpenMessage) instance.sendPartyChat(instance.openMessage);
			});
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance == null || !instance.isEnabled() || !instance.sendProgress) return;
				instance.tick(client);
			});
		}
	}

	private void tick(Minecraft client) {
		if (TerminalTracker.getCurrentType() != TerminalTracker.TerminalType.MELODY) return;
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return;
		if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;

		var items = screen.getMenu().getItems();
		for (int i = 0; i < items.size(); i++) {
			ItemStack item = items.get(i);
			if (!isLimeTerracotta(item)) continue;
			int row = i / 9;
			if (lastClayRow != null && lastClayRow == row) return;
			lastClayRow = row;
			Integer attempt = CLAY_ROW_ATTEMPT.get(row);
			String message = attempt != null ? progressMessage + " (" + attempt + "/4)" : null;
			if (message != null) sendPartyChat(message);
			return;
		}
	}

	// No compile-time Items.LIME_TERRACOTTA constant exists for every color variant in this MC version
	// (same workaround as RareRewardWarningFeature.isRareReward / DungeonRoom.isBlueTerracotta).
	private static boolean isLimeTerracotta(ItemStack stack) {
		return "minecraft:lime_terracotta".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	private void sendPartyChat(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand("pc " + message);
	}

	public boolean isSendOpenMessage() { return sendOpenMessage; }
	public void setSendOpenMessage(boolean value) { sendOpenMessage = value; }
	public String getOpenMessage() { return openMessage; }
	public void setOpenMessage(String value) { openMessage = value == null || value.isBlank() ? "Melody Terminal start!" : value; }
	public boolean isSendProgress() { return sendProgress; }
	public void setSendProgress(boolean value) { sendProgress = value; }
	public String getProgressMessage() { return progressMessage; }
	public void setProgressMessage(String value) { progressMessage = value == null || value.isBlank() ? "Melody" : value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("sendOpenMessage", sendOpenMessage);
		obj.addProperty("openMessage", openMessage);
		obj.addProperty("sendProgress", sendProgress);
		obj.addProperty("progressMessage", progressMessage);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("sendOpenMessage")) sendOpenMessage = obj.get("sendOpenMessage").getAsBoolean();
		if (obj.has("openMessage")) openMessage = obj.get("openMessage").getAsString();
		if (obj.has("sendProgress")) sendProgress = obj.get("sendProgress").getAsBoolean();
		if (obj.has("progressMessage")) progressMessage = obj.get("progressMessage").getAsString();
	}

	@Override
	public String getDescription() {
		return "Announces the Melody terminal opening to your party, and optionally posts progress updates (25/50/75%) in chat.";
	}
}
