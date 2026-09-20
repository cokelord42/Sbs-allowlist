package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Single settings-cog container for every dungeon/general chat-spam category (Rare Drops, Keys and
 * Doors, Solo Class, Solo Class Stats, Fairy Dialogue, Boss Messages, Blessing Messages, Hide Keys,
 * Hide Grandma Wolf Combo Messages). DungeonChatFilter reads these booleans directly instead of looking
 * up separate registered features by id. Has its own master on/off toggle (per user request — more
 * categories will land here over time, so it needs a real enable/disable, not just a labeled group) that
 * gates every category at once.
 */
public class ChatDeclutterFeature extends Feature {
	private boolean rareDrops = false;
	private boolean keysAndDoors = false;
	private boolean dupedClassStats = false;
	private boolean soloClassBuffedStats = false;
	private boolean fairyDialogue = false;
	private boolean bossMessages = false;
	private boolean blessingMessages = false;
	private boolean hideKeys = false;
	private boolean hideGrandmaWolfCombo = false;
	private boolean seasonalRewardsMessage = false;
	private boolean eventRewardsMessage = false;
	private boolean profileMessage = false;
	// Per user request ("Hide lowballers should hide any message containing 'Lowballing' from another
	// player"): a plain substring check (case-insensitive, like the other free-text categories above),
	// not a structural pattern — Hypixel doesn't send this as a fixed system message, it's just whatever
	// wording the OTHER player typed that happens to contain that word.
	private boolean hideLowballers = false;
	// Also shown/toggleable from inside the Ultimate Warning module itself (per user request, "linked" —
	// one underlying setting, two places to flip it) rather than a second independent boolean.
	private boolean hideUltimateReady = false;
	// Per user follow-up ("Move the solo class, dungeon potion reminder, dungeonbreaker, dungeon mob and
	// unable to teleport to the dungeon de-clutter"): those 5 categories moved to DungeonDeclutterFeature —
	// only hideGuildExpGain (not dungeon-specific) stayed here.
	private boolean hideGuildExpGain = false;
	// Per user request ("Hide sell messages: Hides any message starting with 'You sold'"): plain fixed
	// prefix, real example quoted: "You sold Beating Heart x1 for 1 Coin!".
	private boolean hideSellMessages = false;
	// Per user request ("Hide healer orb messages... search for 'You picked up' 'from' 'healing you for' and
	// 'granting you'. That should be enough and hide all of them"): real example quoted: "◕ You picked up an
	// Ability Damage Orb from <player> healing you for 0❤ and granting you +20% Ability Damage for 10
	// seconds." — matched by requiring all four of those substrings rather than the whole line verbatim,
	// since the orb type/healer name/amount/buff/duration all vary per pickup.
	private boolean hideHealerOrbMessages = false;
	// Per user request ("make a chat-declutter feature that removes these messages: 'RARE REWARD! Astraaz
	// found a Recombobulator 3000 in their Obsidian Chest!'. It should be called 'rare reward hider'"): a
	// real, distinct broadcast type from the existing "X has obtained Y!" template isObtainingMessage()
	// already covers (Rare Drops above) — Hypixel sends this one with its own fixed "RARE REWARD!" prefix
	// regardless of which player/item/chest tier triggered it, so a plain prefix match (same convention as
	// hideSellMessages) covers every real variant.
	private boolean hideRareReward = false;

	public ChatDeclutterFeature() {
		super("chat_declutter", "Chat De-clutter", FeatureCategory.INVENTORY, false);
	}

	@Override
	public String getSubcategory() {
		return "Misc";
	}

	public boolean isRareDrops() { return rareDrops; }
	public void setRareDrops(boolean v) { rareDrops = v; }

	public boolean isKeysAndDoors() { return keysAndDoors; }
	public void setKeysAndDoors(boolean v) { keysAndDoors = v; }

	public boolean isDupedClassStats() { return dupedClassStats; }
	public void setDupedClassStats(boolean v) { dupedClassStats = v; }

	public boolean isSoloClassBuffedStats() { return soloClassBuffedStats; }
	public void setSoloClassBuffedStats(boolean v) { soloClassBuffedStats = v; }

	public boolean isFairyDialogue() { return fairyDialogue; }
	public void setFairyDialogue(boolean v) { fairyDialogue = v; }

	public boolean isBossMessages() { return bossMessages; }
	public void setBossMessages(boolean v) { bossMessages = v; }

	public boolean isBlessingMessages() { return blessingMessages; }
	public void setBlessingMessages(boolean v) { blessingMessages = v; }

	public boolean isHideKeys() { return hideKeys; }
	public void setHideKeys(boolean v) { hideKeys = v; }

	public boolean isHideGrandmaWolfCombo() { return hideGrandmaWolfCombo; }
	public void setHideGrandmaWolfCombo(boolean v) { hideGrandmaWolfCombo = v; }

	public boolean isHideUltimateReady() { return hideUltimateReady; }
	public void setHideUltimateReady(boolean v) { hideUltimateReady = v; }

	public boolean isSeasonalRewardsMessage() { return seasonalRewardsMessage; }
	public void setSeasonalRewardsMessage(boolean v) { seasonalRewardsMessage = v; }

	public boolean isEventRewardsMessage() { return eventRewardsMessage; }
	public void setEventRewardsMessage(boolean v) { eventRewardsMessage = v; }

	public boolean isProfileMessage() { return profileMessage; }
	public void setProfileMessage(boolean v) { profileMessage = v; }

	public boolean isHideLowballers() { return hideLowballers; }
	public void setHideLowballers(boolean v) { hideLowballers = v; }

	public boolean isHideGuildExpGain() { return hideGuildExpGain; }
	public void setHideGuildExpGain(boolean v) { hideGuildExpGain = v; }

	public boolean isHideSellMessages() { return hideSellMessages; }
	public void setHideSellMessages(boolean v) { hideSellMessages = v; }

	public boolean isHideHealerOrbMessages() { return hideHealerOrbMessages; }
	public void setHideHealerOrbMessages(boolean v) { hideHealerOrbMessages = v; }

	public boolean isHideRareReward() { return hideRareReward; }
	public void setHideRareReward(boolean v) { hideRareReward = v; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("rareDrops", rareDrops);
		obj.addProperty("keysAndDoors", keysAndDoors);
		obj.addProperty("dupedClassStats", dupedClassStats);
		obj.addProperty("soloClassBuffedStats", soloClassBuffedStats);
		obj.addProperty("fairyDialogue", fairyDialogue);
		obj.addProperty("bossMessages", bossMessages);
		obj.addProperty("blessingMessages", blessingMessages);
		obj.addProperty("hideKeys", hideKeys);
		obj.addProperty("hideGrandmaWolfCombo", hideGrandmaWolfCombo);
		obj.addProperty("hideUltimateReady", hideUltimateReady);
		obj.addProperty("seasonalRewardsMessage", seasonalRewardsMessage);
		obj.addProperty("eventRewardsMessage", eventRewardsMessage);
		obj.addProperty("profileMessage", profileMessage);
		obj.addProperty("hideLowballers", hideLowballers);
		obj.addProperty("hideGuildExpGain", hideGuildExpGain);
		obj.addProperty("hideSellMessages", hideSellMessages);
		obj.addProperty("hideHealerOrbMessages", hideHealerOrbMessages);
		obj.addProperty("hideRareReward", hideRareReward);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("rareDrops")) rareDrops = obj.get("rareDrops").getAsBoolean();
		if (obj.has("keysAndDoors")) keysAndDoors = obj.get("keysAndDoors").getAsBoolean();
		if (obj.has("dupedClassStats")) dupedClassStats = obj.get("dupedClassStats").getAsBoolean();
		if (obj.has("soloClassBuffedStats")) soloClassBuffedStats = obj.get("soloClassBuffedStats").getAsBoolean();
		if (obj.has("fairyDialogue")) fairyDialogue = obj.get("fairyDialogue").getAsBoolean();
		if (obj.has("bossMessages")) bossMessages = obj.get("bossMessages").getAsBoolean();
		if (obj.has("blessingMessages")) blessingMessages = obj.get("blessingMessages").getAsBoolean();
		if (obj.has("hideKeys")) hideKeys = obj.get("hideKeys").getAsBoolean();
		if (obj.has("hideGrandmaWolfCombo")) hideGrandmaWolfCombo = obj.get("hideGrandmaWolfCombo").getAsBoolean();
		if (obj.has("hideUltimateReady")) hideUltimateReady = obj.get("hideUltimateReady").getAsBoolean();
		if (obj.has("seasonalRewardsMessage")) seasonalRewardsMessage = obj.get("seasonalRewardsMessage").getAsBoolean();
		if (obj.has("eventRewardsMessage")) eventRewardsMessage = obj.get("eventRewardsMessage").getAsBoolean();
		if (obj.has("profileMessage")) profileMessage = obj.get("profileMessage").getAsBoolean();
		if (obj.has("hideLowballers")) hideLowballers = obj.get("hideLowballers").getAsBoolean();
		if (obj.has("hideGuildExpGain")) hideGuildExpGain = obj.get("hideGuildExpGain").getAsBoolean();
		if (obj.has("hideSellMessages")) hideSellMessages = obj.get("hideSellMessages").getAsBoolean();
		if (obj.has("hideHealerOrbMessages")) hideHealerOrbMessages = obj.get("hideHealerOrbMessages").getAsBoolean();
		if (obj.has("hideRareReward")) hideRareReward = obj.get("hideRareReward").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "One settings panel with a toggle for every category of spammy dungeon/general chat message you can hide (rare drops, keys and doors, class stats, boss messages, etc.).";
	}
}
