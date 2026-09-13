package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonObjectPredicates;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;

import java.util.regex.Pattern;

/**
 * Per user request ("Move all 'Hide' modules into one big 'Dungeon de-clutter' module"): the dungeon
 * object-hiding toggles (Superboom TNT, Blessings, Revive Stones, Premium Flesh, Journal Entry, Healer
 * Orbs, Skeleton Skull, Healer Fairy, Soulweaver Skulls, Wither Skulls) used to each be their own separate top-level
 * {@link EntityHideFeature} row in the module list — a leftover from before this consolidation (an older
 * doc comment near their old registration already described them as "Object Hider's 9 sub-toggles," but
 * that consolidation was never actually built). Single settings-cog container now, same shape as {@link
 * ChatDeclutterFeature}'s own category toggles: one master enable, one registered {@link
 * EntityHideRegistry} rule that ORs together whichever individual sub-toggles are currently on.
 */
public class DungeonDeclutterFeature extends Feature {
	private boolean hideSuperboomTnt = false;
	private boolean hideBlessings = false;
	private boolean hideReviveStones = false;
	private boolean hidePremiumFlesh = false;
	private boolean hideJournalEntry = false;
	private boolean hideHealerOrbs = false;
	private boolean hideSkeletonSkull = false;
	private boolean hideHealerFairy = false;
	private boolean hideSoulweaverSkulls = false;
	// Per user request ("hide these annoying wither skulls that send out when a wither skeleton dies in a
	// dungeon, including f7/m7 bossfight"): see DungeonObjectPredicates.isWitherSkull's own doc comment for
	// the real vanilla item this actually matches (Items.WITHER_SKELETON_SKULL, the same icon the Wither
	// Artifact accessory uses).
	private boolean hideWitherSkulls = false;

	// Per user request ("New stuff in dungeon de-clutter... Skull message remover... Milestone message
	// remover... Ability text remover... Remove the splits chat messages"): chat-line suppression toggles
	// living in this module alongside the entity-hide ones above, at the user's explicit direction — the
	// actual chat cancel happens in DungeonChatFilter, same as ChatDeclutterFeature's own booleans, this
	// class just owns the toggle state and the matching logic.
	private boolean hideSkullMessages = false;
	private boolean hideMilestoneMessages = false;
	private boolean hideAbilityMessages = false;

	// Per user follow-up ("Move the solo class, dungeon potion reminder, dungeonbreaker, dungeon mob and
	// unable to teleport to the dungeon de-clutter"): moved here from ChatDeclutterFeature — these are all
	// dungeon-specific system messages, same reasoning as the skull/milestone/ability toggles above.
	private boolean soloClassStatsMessage = false;
	private boolean hideDungeonPotionReminder = false;
	private boolean hideDungeonbreakerMessages = false;
	private boolean hideDungeonMobMessages = false;
	private boolean hideUnableToTeleport = false;

	// Moved here from ChatDeclutterFeature per user request ("dungeon-specific voice-line spam, not general
	// chat declutter") — Oruo's questions/lines are randomized text, so this only matches the fixed speaker
	// prefix, never the message body.
	private boolean hideOruoMessages = false;

	// Real, confirmed prefix — per user quote: "[SKULL] Wither Skull: That's a door, and I keep the
	// door...there." The speaker name after "[SKULL] " varies per skull prop, so this only checks the
	// fixed prefix rather than trying to enumerate every possible skull name.
	private static final String SKULL_PREFIX = "[SKULL]";
	// Real per-class message shape — per user quote: "Mage Milestone ❶: You have dealt 750,000 Total
	// Damage so far! 01s". Every one of the 5 real Catacombs classes uses this exact "<Class> Milestone"
	// template (confirmed by the user directly: "All of them use (class) Milestone however"), so matching
	// against the known class name list is both precise (won't catch unrelated "X Milestone" chat outside
	// a dungeon) and already covers every class without needing per-class number/text variants.
	private static final Pattern MILESTONE_PATTERN =
		Pattern.compile("^(Healer|Mage|Berserk|Archer|Tank) Milestone .*$");
	// Real shape confirmed by user example: "Guided Sheep is now available!" — every class's own ability
	// off-cooldown message ends with this exact suffix regardless of the ability's own name, so matching
	// the suffix covers every class's every ability without needing the full name list.
	private static final String ABILITY_AVAILABLE_SUFFIX = "is now available!";

	// Class name matched against the same known Catacombs class list used by MILESTONE_PATTERN above.
	private static final Pattern SOLO_CLASS_STATS_MESSAGE =
		Pattern.compile("Your (?:Healer|Mage|Berserk|Archer|Tank) stats are doubled because you are the only player using this class!");

	private static final String DUNGEON_POTION_REMINDER =
		"You are not allowed to use Potion Effects while in Dungeon, therefore all active effects have been paused and stored. They will be restored when you leave Dungeon!";

	// Real bug found (per user report — "'A mystical force prevents you from digging that block!' isn't
	// hidden"): a third, separately-worded dungeonbreaker line wasn't in this list at all (the other two
	// use "digging there"/"digging in this room", this one uses "from digging that block").
	private static final java.util.List<String> DUNGEONBREAKER_LITERAL = java.util.List.of(
		"A mystical force prevents you digging there!",
		"A mystical force prevents you digging in this room!",
		"A mystical force prevents you from digging that block!",
		"You don't have enough charges to break this block right now!"
	);

	// "The Lost Adventurer used Dragon's Breath on you!" is fixed text; the other two vary by mob name
	// and/or damage amount. Real bug found (per user report — "The Stormy Crypt Lurker struck you for
	// 1,237.3 damage!" wasn't hidden): damage can be a decimal, not just a whole number with commas — the
	// pattern only ever allowed digits/commas, so any fractional damage amount silently never matched.
	private static final String DRAGONS_BREATH_MESSAGE = "The Lost Adventurer used Dragon's Breath on you!";
	private static final Pattern DUNGEON_MOB_STRUCK = Pattern.compile("The .+ struck you for [\\d,]+(?:\\.\\d+)? damage!");
	private static final Pattern CRYPT_WITHER_SKULL_EXPLODED =
		Pattern.compile("A Crypt Wither Skull exploded, hitting you for [\\d,]+(?:\\.\\d+)? damage\\.");

	private static boolean isDungeonMobMessage(String text) {
		return text.equals(DRAGONS_BREATH_MESSAGE) || DUNGEON_MOB_STRUCK.matcher(text).matches()
			|| CRYPT_WITHER_SKULL_EXPLODED.matcher(text).matches();
	}

	private static final String UNABLE_TO_TELEPORT_MESSAGE =
		"A mystical force in this room prevents you from using that ability!";

	private static final String ORUO_PREFIX = "[STATUE] Oruo the Omniscient:";

	public DungeonDeclutterFeature() {
		super("dungeon_declutter", "Dungeon De-clutter", FeatureCategory.COMBAT, false);
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		EntityHideRegistry.setRule(getId(), this::shouldHide);
	}

	@Override
	protected void onDisable() {
		EntityHideRegistry.clearRule(getId());
	}

	private boolean shouldHide(Entity entity) {
		if (hideSuperboomTnt && DungeonObjectPredicates.isSuperboomTnt(entity)) return true;
		if (hideBlessings && DungeonObjectPredicates.isBlessing(entity)) return true;
		if (hideReviveStones && DungeonObjectPredicates.isReviveStone(entity)) return true;
		if (hidePremiumFlesh && DungeonObjectPredicates.isPremiumFlesh(entity)) return true;
		if (hideJournalEntry && DungeonObjectPredicates.isJournalEntry(entity)) return true;
		if (hideHealerOrbs && DungeonObjectPredicates.isHealerOrb(entity)) return true;
		if (hideSkeletonSkull && DungeonObjectPredicates.isSkeletonSkull(entity)) return true;
		if (hideHealerFairy && DungeonObjectPredicates.isHealerFairy(entity)) return true;
		if (hideSoulweaverSkulls && DungeonObjectPredicates.isSoulweaverSkull(entity)) return true;
		if (hideWitherSkulls && DungeonObjectPredicates.isWitherSkull(entity)) return true;
		return false;
	}

	/** Called by {@link com.cokelord.skyblocksimplified.dungeon.DungeonChatFilter}, same pattern as its
	 *  own {@code ChatDeclutterFeature} check — this module's own three chat-line removers, gated on this
	 *  feature's own master enable like every other check there. */
	public boolean shouldHideMessage(String text) {
		if (!isEnabled()) return false;
		if (hideSkullMessages && text.startsWith(SKULL_PREFIX)) return true;
		if (hideMilestoneMessages && MILESTONE_PATTERN.matcher(text).matches()) return true;
		if (hideAbilityMessages && text.endsWith(ABILITY_AVAILABLE_SUFFIX)) return true;
		if (soloClassStatsMessage && SOLO_CLASS_STATS_MESSAGE.matcher(text).matches()) return true;
		if (hideDungeonPotionReminder && text.equals(DUNGEON_POTION_REMINDER)) return true;
		if (hideDungeonbreakerMessages && DUNGEONBREAKER_LITERAL.contains(text)) return true;
		if (hideDungeonMobMessages && isDungeonMobMessage(text)) return true;
		if (hideUnableToTeleport && text.equals(UNABLE_TO_TELEPORT_MESSAGE)) return true;
		if (hideOruoMessages && text.startsWith(ORUO_PREFIX)) return true;
		return false;
	}

	public boolean isHideSuperboomTnt() { return hideSuperboomTnt; }
	public void setHideSuperboomTnt(boolean v) { hideSuperboomTnt = v; }
	public boolean isHideBlessings() { return hideBlessings; }
	public void setHideBlessings(boolean v) { hideBlessings = v; }
	public boolean isHideReviveStones() { return hideReviveStones; }
	public void setHideReviveStones(boolean v) { hideReviveStones = v; }
	public boolean isHidePremiumFlesh() { return hidePremiumFlesh; }
	public void setHidePremiumFlesh(boolean v) { hidePremiumFlesh = v; }
	public boolean isHideJournalEntry() { return hideJournalEntry; }
	public void setHideJournalEntry(boolean v) { hideJournalEntry = v; }
	public boolean isHideHealerOrbs() { return hideHealerOrbs; }
	public void setHideHealerOrbs(boolean v) { hideHealerOrbs = v; }
	public boolean isHideSkeletonSkull() { return hideSkeletonSkull; }
	public void setHideSkeletonSkull(boolean v) { hideSkeletonSkull = v; }
	public boolean isHideHealerFairy() { return hideHealerFairy; }
	public void setHideHealerFairy(boolean v) { hideHealerFairy = v; }
	public boolean isHideSoulweaverSkulls() { return hideSoulweaverSkulls; }
	public void setHideSoulweaverSkulls(boolean v) { hideSoulweaverSkulls = v; }
	public boolean isHideWitherSkulls() { return hideWitherSkulls; }
	public void setHideWitherSkulls(boolean v) { hideWitherSkulls = v; }
	public boolean isHideSkullMessages() { return hideSkullMessages; }
	public void setHideSkullMessages(boolean v) { hideSkullMessages = v; }
	public boolean isHideMilestoneMessages() { return hideMilestoneMessages; }
	public void setHideMilestoneMessages(boolean v) { hideMilestoneMessages = v; }
	public boolean isHideAbilityMessages() { return hideAbilityMessages; }
	public void setHideAbilityMessages(boolean v) { hideAbilityMessages = v; }
	public boolean isSoloClassStatsMessage() { return soloClassStatsMessage; }
	public void setSoloClassStatsMessage(boolean v) { soloClassStatsMessage = v; }
	public boolean isHideDungeonPotionReminder() { return hideDungeonPotionReminder; }
	public void setHideDungeonPotionReminder(boolean v) { hideDungeonPotionReminder = v; }
	public boolean isHideDungeonbreakerMessages() { return hideDungeonbreakerMessages; }
	public void setHideDungeonbreakerMessages(boolean v) { hideDungeonbreakerMessages = v; }
	public boolean isHideDungeonMobMessages() { return hideDungeonMobMessages; }
	public void setHideDungeonMobMessages(boolean v) { hideDungeonMobMessages = v; }
	public boolean isHideUnableToTeleport() { return hideUnableToTeleport; }
	public void setHideUnableToTeleport(boolean v) { hideUnableToTeleport = v; }
	public boolean isHideOruoMessages() { return hideOruoMessages; }
	public void setHideOruoMessages(boolean v) { hideOruoMessages = v; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("hideSuperboomTnt", hideSuperboomTnt);
		obj.addProperty("hideBlessings", hideBlessings);
		obj.addProperty("hideReviveStones", hideReviveStones);
		obj.addProperty("hidePremiumFlesh", hidePremiumFlesh);
		obj.addProperty("hideJournalEntry", hideJournalEntry);
		obj.addProperty("hideHealerOrbs", hideHealerOrbs);
		obj.addProperty("hideSkeletonSkull", hideSkeletonSkull);
		obj.addProperty("hideHealerFairy", hideHealerFairy);
		obj.addProperty("hideSoulweaverSkulls", hideSoulweaverSkulls);
		obj.addProperty("hideWitherSkulls", hideWitherSkulls);
		obj.addProperty("hideSkullMessages", hideSkullMessages);
		obj.addProperty("hideMilestoneMessages", hideMilestoneMessages);
		obj.addProperty("hideAbilityMessages", hideAbilityMessages);
		obj.addProperty("soloClassStatsMessage", soloClassStatsMessage);
		obj.addProperty("hideDungeonPotionReminder", hideDungeonPotionReminder);
		obj.addProperty("hideDungeonbreakerMessages", hideDungeonbreakerMessages);
		obj.addProperty("hideDungeonMobMessages", hideDungeonMobMessages);
		obj.addProperty("hideUnableToTeleport", hideUnableToTeleport);
		obj.addProperty("hideOruoMessages", hideOruoMessages);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("hideSuperboomTnt")) hideSuperboomTnt = obj.get("hideSuperboomTnt").getAsBoolean();
		if (obj.has("hideBlessings")) hideBlessings = obj.get("hideBlessings").getAsBoolean();
		if (obj.has("hideReviveStones")) hideReviveStones = obj.get("hideReviveStones").getAsBoolean();
		if (obj.has("hidePremiumFlesh")) hidePremiumFlesh = obj.get("hidePremiumFlesh").getAsBoolean();
		if (obj.has("hideJournalEntry")) hideJournalEntry = obj.get("hideJournalEntry").getAsBoolean();
		if (obj.has("hideHealerOrbs")) hideHealerOrbs = obj.get("hideHealerOrbs").getAsBoolean();
		if (obj.has("hideSkeletonSkull")) hideSkeletonSkull = obj.get("hideSkeletonSkull").getAsBoolean();
		if (obj.has("hideHealerFairy")) hideHealerFairy = obj.get("hideHealerFairy").getAsBoolean();
		if (obj.has("hideSoulweaverSkulls")) hideSoulweaverSkulls = obj.get("hideSoulweaverSkulls").getAsBoolean();
		if (obj.has("hideWitherSkulls")) hideWitherSkulls = obj.get("hideWitherSkulls").getAsBoolean();
		if (obj.has("hideSkullMessages")) hideSkullMessages = obj.get("hideSkullMessages").getAsBoolean();
		if (obj.has("hideMilestoneMessages")) hideMilestoneMessages = obj.get("hideMilestoneMessages").getAsBoolean();
		if (obj.has("hideAbilityMessages")) hideAbilityMessages = obj.get("hideAbilityMessages").getAsBoolean();
		if (obj.has("soloClassStatsMessage")) soloClassStatsMessage = obj.get("soloClassStatsMessage").getAsBoolean();
		if (obj.has("hideDungeonPotionReminder")) hideDungeonPotionReminder = obj.get("hideDungeonPotionReminder").getAsBoolean();
		if (obj.has("hideDungeonbreakerMessages")) hideDungeonbreakerMessages = obj.get("hideDungeonbreakerMessages").getAsBoolean();
		if (obj.has("hideDungeonMobMessages")) hideDungeonMobMessages = obj.get("hideDungeonMobMessages").getAsBoolean();
		if (obj.has("hideUnableToTeleport")) hideUnableToTeleport = obj.get("hideUnableToTeleport").getAsBoolean();
		if (obj.has("hideOruoMessages")) hideOruoMessages = obj.get("hideOruoMessages").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "One panel with a toggle for every dungeon object you can hide from view (Superboom TNT, Blessings, Revive Stones, Premium Flesh, Journal Entries, Healer Orbs, Skeleton Skulls, etc.).";
	}
}
