package com.cokelord.skyblocksimplified.util;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Confirmed real ability cooldown durations, ported from SkyHanni's own ItemAbility.kt table (the same
 * source referenced by name in this project's history) — used so AbilityCooldownTimerFeature can start a
 * timer with the CORRECT duration the instant an item is used, instead of depending on parsing a
 * "Cooldown: Ns" line out of the item's own tooltip lore (unreliable: not every ability item's lore
 * actually prints that exact wording/position, which is the likely reason the timer kept silently not
 * showing up for a lot of real ability items across several previous fix attempts).
 *
 * <p>SkyHanni itself detects activation via a per-item exact sound name/pitch/volume triple this project
 * has no equivalent table for (see ItemAbilityCooldown.kt's onPlaySound) — deliberately NOT ported here,
 * since guessing at unconfirmed sound fingerprints risks silently wrong matches. Instead this is looked up
 * from a genuine right-click/item-use signal (ItemEvents.USE), which SkyHanni's sound-based items don't
 * use at all but is exactly the generic "an item was used" trigger this was built for.
 *
 * <p>Most of SkyHanni's "newVariant" entries (the sound-detected majority) don't carry a separately
 * confirmed real Hypixel item id in their own source — SkyHanni matches those via internalNames built from
 * either an explicit alias or the enum constant's own name. Where no alias exists, this assumes the enum
 * constant name doubles as the real Hypixel item id (a real, common SkyHanni convention, but NOT
 * independently re-confirmed against a live item id for every single entry below — treat any one entry
 * that never triggers as a candidate for a wrong assumed id, not evidence the whole approach is broken).
 * Ability-scroll items (Wither Shield/Shadow Warp/Implosion Scroll, Wither Impact) are deliberately not
 * included: those attach to arbitrary soul weapons via a scroll NBT modifier rather than having their own
 * item id, and this project has no confirmed reader for that modifier.
 */
public final class ItemAbilityDurations {
	private ItemAbilityDurations() {}

	public record AbilityInfo(String abilityName, int cooldownSeconds) {}

	// Real Hypixel item id -> ability info. Matched against SkyblockNbtUtils.getItemId(stack).
	private static final Map<String, AbilityInfo> BY_ID = Map.ofEntries(
		// Real bug found (per user report this round — "Left clicking doesnt change it from R to the 30
		// second timer like it should, and right click shows the 30 second timer when it should actually
		// show 10 for that ability"): an EARLIER round's conclusion (see the removed comment this replaces)
		// had settled on "one ability, right-click only, 30 seconds" after a live test — but the user has
		// since re-confirmed, and GyroHelperFeature's own world-ring cooldown was explicitly built this same
		// session around, a genuine two-ability split: right-click (Gravity Storm) is the SHORT 10-second
		// cooldown, and a separate left-click ability is the LONG 30-second one (see LEFT_CLICK_BY_ID below).
		// This entry's duration now matches the right-click ability specifically.
		Map.entry("GYROKINETIC_WAND", new AbilityInfo("Gyrokinetic Wand", 10)),
		Map.entry("GIANTS_SWORD", new AbilityInfo("Giant's Sword", 30)),
		Map.entry("ICE_SPRAY_WAND", new AbilityInfo("Ice Spray Wand", 5)),
		Map.entry("RAGNAROCK_AXE", new AbilityInfo("Ragnarock Axe", 20)),
		Map.entry("WAND_OF_HEALING", new AbilityInfo("Wand of Atonement", 7)),
		Map.entry("WAND_OF_MENDING", new AbilityInfo("Wand of Atonement", 7)),
		Map.entry("WAND_OF_RESTORATION", new AbilityInfo("Wand of Atonement", 7)),
		Map.entry("SOS_FLARE", new AbilityInfo("SOS Flare", 10)),
		Map.entry("WARNING_FLARE", new AbilityInfo("Alert Flare", 20)),
		Map.entry("GOLEM_SWORD", new AbilityInfo("Golem Sword", 3)),
		Map.entry("END_STONE_SWORD", new AbilityInfo("End Stone Sword", 5)),
		Map.entry("SOUL_ESOWARD", new AbilityInfo("Soul Esoward", 20)),
		Map.entry("PIGMAN_SWORD", new AbilityInfo("Pigman Sword", 5)),
		Map.entry("EMBER_ROD", new AbilityInfo("Ember Rod", 30)),
		Map.entry("STAFF_OF_THE_VOLCANO", new AbilityInfo("Staff of the Volcano", 30)),
		Map.entry("STARLIGHT_WAND", new AbilityInfo("Starlight Wand", 2)),
		Map.entry("VOODOO_DOLL", new AbilityInfo("Voodoo Doll", 5)),
		Map.entry("WEIRD_TUBA", new AbilityInfo("Weird Tuba", 20)),
		Map.entry("WEIRDER_TUBA", new AbilityInfo("Weirder Tuba", 30)),
		Map.entry("FIRE_FREEZE_STAFF", new AbilityInfo("Fire Freeze Staff", 10)),
		Map.entry("SWORD_OF_BAD_HEALTH", new AbilityInfo("Sword of Bad Health", 5)),
		Map.entry("WITHER_CLOAK", new AbilityInfo("Creeper Veil", 10)),
		Map.entry("HOLY_ICE", new AbilityInfo("Holy Ice", 4)),
		Map.entry("FIRE_FURY_STAFF", new AbilityInfo("Fire Fury Staff", 20)),
		Map.entry("SHADOW_FURY", new AbilityInfo("Shadow Fury", 15)),
		Map.entry("STARRED_SHADOW_FURY", new AbilityInfo("Shadow Fury", 15)),
		Map.entry("ROYAL_PIGEON", new AbilityInfo("Royal Pigeon", 5)),
		Map.entry("WAND_OF_STRENGTH", new AbilityInfo("Wand of Strength", 10)),
		Map.entry("TACTICAL_INSERTION", new AbilityInfo("Tactical Insertion", 20)),
		Map.entry("TOTEM_OF_CORRUPTION", new AbilityInfo("Totem of Corruption", 20)),
		Map.entry("ENRAGER", new AbilityInfo("Enrager", 20)),
		// Per user request ("Add a mandatory cooldown to hyperions, valkyries, scyllas and astraeas. It should
		// be 5 seconds to show the cooldown of the wither shield"): Wither Impact (these 4 swords' real
		// right-click ability) already gets its own real, skill-scaled cooldown badge via the generic
		// mana-ability action-bar path (using the item's own printed lore, not this table at all) — Wither
		// Shield is a separate, fixed-duration effect that same activation also grants, so it needs its own
		// entry here, exempted from the usual lore-preference (see MANDATORY_TABLE_DURATION_IDS below).
		Map.entry("HYPERION", new AbilityInfo("Wither Shield", 5)),
		Map.entry("VALKYRIE", new AbilityInfo("Wither Shield", 5)),
		Map.entry("SCYLLA", new AbilityInfo("Wither Shield", 5)),
		Map.entry("ASTRAEA", new AbilityInfo("Wither Shield", 5))
	);

	// See the Wither Shield entries above: these 4 ids' own lore prints Wither Impact's real cooldown, which
	// AbilityCooldownTimerFeature would otherwise prefer over this table's value for every other item — but
	// that lore describes a different ability's cooldown entirely, so it must never override this fixed 5s.
	private static final java.util.Set<String> MANDATORY_TABLE_DURATION_IDS = java.util.Set.of(
		"HYPERION", "VALKYRIE", "SCYLLA", "ASTRAEA");

	public static boolean isMandatoryTableDuration(String id) {
		return id != null && MANDATORY_TABLE_DURATION_IDS.contains(id.toUpperCase(Locale.ROOT));
	}

	// Legacy group SkyHanni itself matches by display name substring, not id (its own comment: "doesn't
	// have a (consistent) sound") — same approach here, against the item's plain (color-stripped) name.
	private static final Map<String, AbilityInfo> BY_NAME_CONTAINS = Map.of(
		"Ender Bow", new AbilityInfo("Ender Warp", 5),
		"Livid Dagger", new AbilityInfo("Throw", 5),
		"Fire Veil Wand", new AbilityInfo("Fire Veil", 5),
		"Ink Wand", new AbilityInfo("Ink Bomb", 30),
		"Rogue Sword", new AbilityInfo("Speed Boost", 30),
		"Talbot's Theodolite", new AbilityInfo("Track", 10),
		"Ancestral Spade", new AbilityInfo("Echo", 3)
	);

	// Real bug found (per user report this round — see BY_ID's own GYROKINETIC_WAND doc comment): a PRIOR
	// round had concluded the Gyrokinetic Wand has only one ability and emptied this map out entirely, but
	// the user has since re-confirmed a genuine second left-click ability exists with its own real 30-second
	// cooldown, independent of the right-click Gravity Storm's 10 seconds. Restored the entry so {@link
	// #findLeftClick} (and the {@code LivingEntitySwingMixin}-driven left-click hotbar badge that calls it)
	// actually activates for a real left-click swing of the wand instead of silently doing nothing.
	private static final Map<String, AbilityInfo> LEFT_CLICK_BY_ID = Map.of(
		"GYROKINETIC_WAND", new AbilityInfo("Gyrokinetic Wand", 30)
	);

	/** Best-effort lookup for the ability (if any) the given held item would trigger on use, or null if
	 *  it isn't a recognized ability item at all. */
	public static AbilityInfo find(ItemStack stack) {
		if (stack.isEmpty()) return null;
		String id = SkyblockNbtUtils.getItemId(stack);
		if (id != null) {
			AbilityInfo byId = BY_ID.get(id.toUpperCase(Locale.ROOT));
			if (byId != null) return byId;
		}
		String plainName = stack.getHoverName().getString().replaceAll("§.", "");
		for (Map.Entry<String, AbilityInfo> entry : BY_NAME_CONTAINS.entrySet()) {
			if (plainName.contains(entry.getKey())) return entry.getValue();
		}
		return null;
	}

	/** Same lookup as {@link #find}, but for a distinct left-click ability — see {@link #LEFT_CLICK_BY_ID}'s
	 *  own doc comment. Null for every item that only has a (or no) right-click ability. */
	public static AbilityInfo findLeftClick(ItemStack stack) {
		if (stack.isEmpty()) return null;
		String id = SkyblockNbtUtils.getItemId(stack);
		if (id == null) return null;
		return LEFT_CLICK_BY_ID.get(id.toUpperCase(Locale.ROOT));
	}

	// Exposed for anything that wants to know the full recognized name set without a lookup (unused for now,
	// kept for parity/debug convenience — matches the style of other *Repo/*Api classes in this project).
	public static List<String> knownIds() {
		return List.copyOf(BY_ID.keySet());
	}
}
