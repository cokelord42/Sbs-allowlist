package com.cokelord.skyblocksimplified.feature.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real per-floor dungeon reward-chest drop weights, for Chest Rolling's decoy strip — per user request
 * ("It should land on items that are actually in the dungeon, like enchanted books, precursor gears, wither
 * skulls... make sure to add 'Weight' to drops based on drop chance. I dont want stuff like a necrons handle
 * being fake scrolled past the same amount as a rejuvenate 1").
 *
 * <p>Sourced from two user-provided Hypixel Skyblock Wiki loot-chance pulls: an initial one covering Wood/
 * Gold/Diamond/Obsidian/Bedrock across Floors I-VII, and a follow-up filling in the real Emerald-tier tables
 * for Floors I-V plus Master Mode's per-tier Master Skull/Master Star additions and Necron's Handle's real
 * 0.13% chance. Weights here are the real first-roll chance PERCENTAGES from the source, used directly as
 * weighted-random weights (their absolute scale doesn't matter, only their ratio to each other and to
 * {@link #GENERIC_FILLER}) — not the source's separately-listed "Weight" column, since the two sources use
 * different underlying chest-size denominators and mixing them would distort the ratios this whole table
 * exists to get right.
 *
 * <p>Real bug found (per user report — "Im getting master skulls, master stars and dark claymores on the
 * regular floors. Those are master mode drops only"): Master Skull/Master Star/Dark Claymore entries used
 * to be folded into every floor's pool with no normal/master distinction at all — every {@link Drop} now
 * carries a real {@code masterOnly} flag, and every lookup here takes the caller's real master-mode state
 * (from {@code DungeonState.getFloorLabel()}'s own "M"-prefix convention, same check
 * {@code DungeonScoreCalculatorFeature.isMasterMode()} already uses) and filters those entries out unless
 * actually playing Master Mode. Necron's Handle is NOT master-only (it's a real Normal Mode Floor 7 drop
 * too, per the wiki source), so it stays available on regular F7.
 *
 * <p><b>Known real gaps in the source data (not modeled, not guessed):</b> full Master Mode chest tables for
 * Floors III-VII beyond the Master Skull/Star additions above (Master weights are usually near-identical to
 * Normal Mode otherwise, per the source's own note), and Necron's Handle's real drop-table weight (only its
 * chance is known — folded in directly as a weight of 0.13, matching how every other real percentage here
 * is used).
 */
final class ChestDropTables {
	// Real bug found (per user report — "the csgo roller should not show a baked potato... that is not a
	// drop"): Hot Potato Book/Fuming Potato Book previously used Items.BAKED_POTATO/Items.POTATO as their
	// icon — a plausible-looking guess off the item's own name, but wrong; both are real book-model items
	// in-game, matching real Hypixel: they're a plain (non-glinting) Items.BOOK, distinct from a genuine
	// "Ench. Book: X" drop, which is a real vanilla Items.ENCHANTED_BOOK (glint included) — this table now
	// distinguishes the two instead of collapsing everything book-shaped to the same plain icon, which was
	// a real, separate complaint ("the rolling literally shows ONLY hot potato books" — every book-type
	// entry looked visually identical during the fast scroll with no way to tell them apart by eye).
	// Real Hypixel rarity tiers for the fixed set of named drops the user identified by exact real-world
	// rarity ("All enchanted books are rare (including ultimate enchants)... Necron's handle is legendary...
	// Wither armor is legendary... All master stars are epic... Claymore is legendary") — F7/M7 scope only,
	// per the user's own explicit instruction ("F7/M7 is all i care about so i will only add support for
	// those currently"). Real Hypixel rarity colors: COMMON=white, UNCOMMON=green, RARE=blue, EPIC=dark
	// purple, LEGENDARY=gold. Returns null for every other drop (filler decoys, common floor loot with no
	// confirmed real rarity), which keeps their existing plain-gray accent color exactly as before.
	static ChatFormatting rarityOf(String dropName) {
		if (dropName.startsWith("Ench. Book:")) return ChatFormatting.BLUE;
		return switch (dropName) {
			// Real bug found (per user correction — "The master skull in m7 is rare. All below are uncommon
			// and m1 and m2 are common rarity. The epic and legendary [tiers Hypixel's own API lists] are
			// actually only craftable [not real chest drops]"): supersedes an earlier round's tier-by-tier
			// live-API pull (which read Tier V as RARE, Tier VI as EPIC, Tier VII as LEGENDARY) — taken as
			// authoritative since it's the user's own direct in-game observation of what these ACTUALLY drop
			// as, not just what the API's raw tier field happens to say for each MASTER_SKULL_TIER_N id.
			case "Master Skull (Tier I)", "Master Skull (Tier II)" -> ChatFormatting.WHITE;
			// Wither Catalyst, Necromancer's Brooch, Wither Cloak (the sword), and Wither Blood are all real
			// RARE items. Per user's exact item-by-item correction pass this round (live-API-confirmed unless
			// noted): Summoning Ring, Bonzo's Mask, Bonzo's Staff, Red Nose, Warped Stone, Red Scarf and
			// Scarf's Studies (the latter two on M2, alongside Red Scarf's own icon fix below) join here.
			// Master Skull Tier VII also joins here (see the Tier I/II case's own doc comment above).
			case "Wither Catalyst", "Necromancer's Brooch", "Wither Cloak Sword", "Wither Blood",
				"Summoning Ring", "Bonzo's Mask", "Bonzo's Staff", "Red Nose", "Warped Stone",
				"Red Scarf", "Scarf's Studies", "Master Skull (Tier VII)" -> ChatFormatting.BLUE;
			// Uncommon — Master Skull III/IV/V/VI all join here per the user's own correction above (Tier VI
			// stays excluded from the actual droppable pool regardless — see its own FLOOR_RARES doc comment
			// on why, an entirely separate "doesn't actually drop" finding this recoloring doesn't touch).
			case "Master Skull (Tier III)", "Master Skull (Tier IV)", "Master Skull (Tier V)",
				"Master Skull (Tier VI)" -> ChatFormatting.GREEN;
			// Per user report ("Fuming/hot potato books are epic"), added to the Master Star epic tier below.
			// Per user report ("All three wither scrolls (the book and quills) are epic"): the three
			// wither-armor ability scrolls (Wither Shield, Implosion, Shadow Warp) moved here from RARE.
			// Per this round's item-by-item pass: Precursor Gear, the Shadow Assassin set, Spirit Bone, the
			// Adaptive set (armor + Helmet + Blade), Spirit Boots, and Spirit Wing all join here (all confirmed
			// EPIC via a live API pull). "[EPIC] Spirit Pet" joins per this round's rarity fix (see its own
			// FLOOR_RARES doc comment on the Epic/Legendary drop-chest split).
			case "First Master Star", "Second Master Star", "Third Master Star", "Fourth Master Star", "Fifth Master Star",
				"Hot Potato Book", "Fuming Potato Book",
				"Wither Shield", "Implosion", "Shadow Warp",
				"Precursor Gear", "Shadow Assassin Boots", "Shadow Assassin Helmet", "Shadow Assassin Chestplate",
				"Spirit Bone", "Adaptive Chestplate", "Adaptive Boots", "Adaptive Leggings", "Adaptive Helmet",
				"Adaptive Blade", "Spirit Boots", "Spirit Wing", "[EPIC] Spirit Pet" -> ChatFormatting.DARK_PURPLE;
			// Recombobulator 3000/Auto Recombobulator 3000, Precursor Eye, Giant's Sword, Spirit Mask, Spirit
			// Shortbow, and Fel Skull all join per this round's pass (confirmed LEGENDARY via a live API pull,
			// except Spirit Mask which the user identified directly by in-game observation — the API's own
			// base/unstarred tier for it is EPIC, but its real starred drop-chest tier reads Legendary per the
			// user's own report, taken as authoritative). Necromancer Sword joins per user correction ("the
			// necromancer lord sword is legendary" — confirmed LEGENDARY via a live API pull, id
			// NECROMANCER_SWORD; this table's own drop is named plain "Necromancer Sword", the exact name
			// Hypixel's API itself uses). "[LEGENDARY] Spirit Pet" joins per this round's rarity fix (see its
			// own FLOOR_RARES doc comment).
			case "Necron's Handle", "Wither Chestplate", "Wither Leggings", "Wither Helmet", "Wither Boots", "Dark Claymore",
				"Shadow Fury", "Necron Dye", "Recombobulator 3000", "Auto Recombobulator 3000", "Precursor Eye",
				"Giant's Sword", "Spirit Mask", "Spirit Shortbow", "Fel Skull", "Necromancer Sword",
				"[LEGENDARY] Spirit Pet" -> ChatFormatting.GOLD;
			default -> null;
		};
	}

	// minTierRank: the lowest real chest tier this drop can actually come from (see chestTierRank's own doc
	// comment for the rank scale) — 0 (Wood, the default) means "no confirmed minimum," so every existing
	// Drop keeps showing in every chest's decoy strip exactly as before. Only set explicitly for the handful
	// of drops with a real, well-documented minimum tier (Necron's Handle/Dark Claymore are famously Bedrock-
	// chest exclusive; the Spirit Pet's Epic/Legendary split per the user's own report) rather than guessed
	// across this whole table, since a wrong guess here HIDES a real possible drop instead of just wrongly
	// coloring/iconing it — a strictly worse failure mode than this table's usual "safe to guess" fixes.
	record Drop(String name, double weight, Item icon, boolean masterOnly, int minTierRank) {
		Drop(String name, double weight, Item icon) {
			this(name, weight, icon, false, 0);
		}
		Drop(String name, double weight, Item icon, boolean masterOnly) {
			this(name, weight, icon, masterOnly, 0);
		}
		Drop(String name, double weight, Item icon, int minTierRank) {
			this(name, weight, icon, false, minTierRank);
		}
	}

	/** Real chest tier ordering — Wood < Gold < Diamond < (Emerald/Obsidian, same rank: real Hypixel chests
	 *  never offer both on the same floor, Emerald on Floors I-V and Obsidian on Floors V-VII, so treating
	 *  them as equal rank is unambiguous within any single floor) < Bedrock (Floors V-VII only, the true top
	 *  tier). Unknown/null (the test command has no real chest to read a tier from) returns Integer.MAX_VALUE
	 *  so nothing is ever wrongly hidden there. */
	static int chestTierRank(String chestTypeName) {
		if (chestTypeName == null) return Integer.MAX_VALUE;
		return switch (chestTypeName.toLowerCase(Locale.ROOT)) {
			case "wood", "wooden" -> 0;
			case "gold" -> 1;
			case "diamond" -> 2;
			case "emerald", "obsidian" -> 3;
			case "bedrock" -> 4;
			default -> Integer.MAX_VALUE;
		};
	}

	private ChestDropTables() {}

	// Real generic-filler percentages, taken from Floor I's own Emerald table (the source's own note: "Wood/
	// Gold tiers follow the same shape as prior floors" — these numbers are representative of the same small
	// set of enchant books repeating at similar relative weight across nearly every floor/chest in the source).
	private static final List<Drop> GENERIC_FILLER = List.of(
		new Drop("Ench. Book: Rejuvenate I", 41.92, Items.ENCHANTED_BOOK),
		new Drop("Ench. Book: Feather Falling VI", 27.04, Items.ENCHANTED_BOOK),
		new Drop("Ench. Book: Infinite Quiver VI", 27.04, Items.ENCHANTED_BOOK),
		new Drop("Ench. Book: Combo I", 25.75, Items.ENCHANTED_BOOK),
		new Drop("Ench. Book: No Pain No Gain I", 25.75, Items.ENCHANTED_BOOK),
		new Drop("Necromancer's Brooch", 15.6, Items.NETHER_STAR),
		new Drop("Ench. Book: Bank I", 12.58, Items.ENCHANTED_BOOK),
		new Drop("Ench. Book: Ultimate Wise I", 33.81, Items.ENCHANTED_BOOK),
		new Drop("Hot Potato Book", 4.96, Items.BOOK),
		new Drop("Ench. Book: Ultimate Jerry I", 1.62, Items.ENCHANTED_BOOK)
	);

	// Real per-floor headline/BiS item chances, from the Emerald-tier tables (Floors I-V) or the best-
	// available tier the source captured (Floors VI-VII, unchanged from the initial pull — see class doc
	// comment on the gap). Master Skull/Star weights use the real Master Mode chances given directly, keyed
	// to the floor whose Master Mode tier (M1-M7) they belong to.
	private static final Map<Integer, List<Drop>> FLOOR_RARES = Map.ofEntries(
		Map.entry(1, List.of(
			// Real bug found (per user report — "There are random carrots and sticks and pumpkin heads in the
			// m1/f1 roller. The bonzo mask has a texture, the bonzo staff is a blaze rod, i dont know what the
			// carrot is but im guessing its a red nose? That also has a texture/skull on hypixel"): Bonzo's
			// Staff really is a plain vanilla Blaze Rod on Hypixel (not a placeholder-shaped guess) — Items.STICK
			// was wrong. Bonzo's Mask and Red Nose both have real head-based textures (material SKULL_ITEM),
			// same class of fix as Adaptive/Spirit/Shadow Assassin's own helmets above — see
			// SKYBLOCK_ID_OVERRIDES below for their real ids, with Items.PLAYER_HEAD as the safe fallback.
			new Drop("Bonzo's Staff", 0.71, Items.BLAZE_ROD),
			new Drop("Bonzo's Mask", 1.42, Items.PLAYER_HEAD),
			new Drop("Fuming Potato Book", 1.42, Items.BOOK),
			new Drop("Red Nose", 2.13, Items.PLAYER_HEAD),
			// Per user request ("There are a lot of master skulls and they fill the chest sometimes. Make them
			// half as common."): every Master Skull tier's real weight is halved from this round on (Tier VI
			// removed entirely below its own floor — see that entry's own doc comment).
			new Drop("Master Skull (Tier I)", 1.83, Items.WITHER_SKELETON_SKULL, true)
		)),
		Map.entry(2, List.of(
			// Real bug found (per live Hypixel API pull this round, item id STONE_BLADE): Adaptive Blade's real
			// material is a Stone Sword, not Iron — Items.IRON_SWORD was a plausible-but-wrong guess.
			new Drop("Adaptive Blade", 0.89, Items.STONE_SWORD),
			new Drop("Fuming Potato Book", 1.79, Items.BOOK),
			// Real bug found (per user report — "No clue what the leather is on m2 but find and replace the
			// texture since there is no leather"): confirmed via a live Hypixel API pull that both "Red Scarf"
			// (id RED_SCARF) and "Scarf's Studies" (id SCARF_STUDIES) are real player-head-based items (material
			// SKULL_ITEM, real confirmed skins) — Items.LEATHER/Items.WRITTEN_BOOK were plausible-but-wrong
			// name-shaped guesses, same class of fix as every other head-based item in this table.
			new Drop("Red Scarf", 0.89, Items.PLAYER_HEAD),
			new Drop("Scarf's Studies", 3.57, Items.PLAYER_HEAD),
			new Drop("Master Skull (Tier II)", 3.07, Items.WITHER_SKELETON_SKULL, true)
		)),
		Map.entry(3, List.of(
			// Real bug found (per user correction — "The iron armor also needs to be adaptive armor, which is
			// gray leather i think"): these used Items.IRON_* as a plausible name-shaped guess (Adaptive
			// "sounds" metallic), but the real in-game item is dyed leather armor, same class of wrong-icon
			// bug as Wither armor's earlier Netherite guess. Real dye hex (0xBFBCB2) confirmed via the
			// Hypixel Skyblock Wiki mirror — see fillerItemStack's ADAPTIVE_ARMOR_GRAY.
			new Drop("Adaptive Chestplate", 0.73, Items.LEATHER_CHESTPLATE),
			// Real bug found (per live Hypixel API pull this round): unlike the other three Adaptive pieces,
			// the real Helmet is a player-head-based item (material SKULL_ITEM, a real confirmed skin), not
			// dyed leather — Items.LEATHER_HELMET was a plausible-but-wrong guess extrapolated from its siblings.
			new Drop("Adaptive Helmet", 1.46, Items.PLAYER_HEAD),
			new Drop("Adaptive Boots", 1.87, Items.LEATHER_BOOTS),
			new Drop("Adaptive Leggings", 0.73, Items.LEATHER_LEGGINGS),
			new Drop("Fuming Potato Book", 0.73, Items.BOOK),
			// Real bug found (per user report — "M3 is showing random potions. Not a real drop."): "Suspicious
			// Vial" was never a real Catacombs Floor III chest drop — removed entirely rather than re-iconned.
			new Drop("Master Skull (Tier III)", 3.83, Items.WITHER_SKELETON_SKULL, true),
			new Drop("First Master Star", 2.53, Items.NETHER_STAR, true),
			// Per user request (obfuscation-list spec — "M3: recombobulator, master skull, first master star"):
			// a real drop on every Catacombs floor (not master-exclusive), added here and to Floors IV-VII
			// below. Weight is an unsourced approximation (same honest-guess treatment this table already gives
			// Necron's Handle/the wither scrolls).
			new Drop("Recombobulator 3000", 1.0, Items.NETHER_STAR)
		)),
		Map.entry(4, List.of(
			// Real bug found (per user report — "M4 just has a bunch of stuff, like bones and magma cream. It
			// should have spirit bones and spirit pets and spirit shortbows and the spirit wings and such"):
			// "Spirit Stone" (a made-up name, Items.MAGMA_CREAM) was never a real Floor 4 drop — removed and
			// replaced with the real Spirit set's remaining pieces (Spirit Mask, Spirit Shortbow, Spirit Wing —
			// confirmed real names via the Hypixel Skyblock Wiki mirror; NOT "Spirit Bow"/"Spirit Wings" as
			// initially guessed, that name belongs to an unrelated Thorn boss-fight item). Spirit Boots'
			// icon corrected from Items.GOLDEN_BOOTS to dyed leather (real hex 0xBFBFBF, also confirmed via
			// the wiki) — same wrong-icon class as Adaptive Armor above. Spirit Mask dyed the same white to
			// match. Weights for the newly-added pieces are an unsourced approximation matching their
			// siblings' own weight here (same honest-guess treatment this table already gives Necron's
			// Handle/the wither scrolls), since no wiki pull covers the full Spirit set individually.
			new Drop("Fuming Potato Book", 0.9, Items.BOOK),
			// Real gap found (per user report — "No clue what the ghast tear is but find out what and find the
			// matching texture") and later fully resolved once the user supplied a screenshot of the real
			// in-game icon: Skyblock pets (this is the Floor 4 "Spirit" pet drop) aren't in Hypixel's own
			// public items API at all, so this couldn't go through the normal SKYBLOCK_ID_OVERRIDES/
			// SkyblockItemIcons path — but the community-maintained NEU item repo's own "SPIRIT;3"/"SPIRIT;4"
			// (Epic/Legendary-tier Spirit pet) definitions both store the same real Mojang skin hash Hypixel's
			// client actually uses (identical texture for both rarities), confirmed by rendering it and
			// matching pixel-for-pixel against the user's screenshot. These Items.GHAST_TEAR entries are now
			// only the drop TABLE's fallback icon (never actually shown — see ChestRollingFeature.
			// fillerItemStack's special case for both drop names, which renders the real hardcoded skull skin
			// via SkullTextureUtil.fromTextureHash instead).
			//
			// Real bug found (per user report — "Spirit pet also has no rarity. There are epics and
			// legendaries, and i think epic only comes in the diamond chest while legendary comes in emerald
			// and obsidian"): previously a single "[LEGENDARY] Spirit Pet" entry with no real rarity Style at
			// all (just a plain-text "[LEGENDARY]" prefix, per rarityOf's own doc comment on why null skips
			// coloring). Split into the two real drop-chest variants, each with its own real rarity color
			// (see rarityOf) and a real minTierRank (Diamond=2 for Epic, Emerald/Obsidian=3 for Legendary —
			// both ranks are equal-or-above what a plain generic filler item needs, so this is a strict
			// tightening, not a loosening, of when either can show). Weight split roughly 3:1 (Epic more
			// common than Legendary), matching the general rarity/quantity relationship every other real
			// dual-rarity drop in this table follows, off the original combined 0.9 weight.
			new Drop("[EPIC] Spirit Pet", 0.68, Items.GHAST_TEAR, 2),
			new Drop("[LEGENDARY] Spirit Pet", 0.22, Items.GHAST_TEAR, 3),
			// Real bug found (per user report — "Spirit bone is epic and it has a texture, its not a bone"):
			// confirmed via a live Hypixel API pull (item id SPIRIT_BONE) — a real Hypixel resource-pack
			// "item_model" reskin (same mechanism Spirit Wing below already uses), not a plain vanilla bone.
			// Items.PAPER matches Spirit Wing's own fallback convention for this same reskin mechanism.
			new Drop("Spirit Bone", 0.9, Items.PAPER),
			new Drop("Spirit Boots", 0.9, Items.LEATHER_BOOTS),
			// Real bug found (per live Hypixel API pull this round, item id SPIRIT_MASK): the real Spirit Mask
			// is a player-head-based item (material SKULL_ITEM, a real confirmed skin), not dyed leather —
			// Items.LEATHER_HELMET was a wrong guess extrapolated from Spirit Boots' own (correct) leather icon.
			new Drop("Spirit Mask", 0.9, Items.PLAYER_HEAD),
			new Drop("Spirit Shortbow", 0.9, Items.BOW),
			new Drop("Spirit Wing", 0.6, Items.PAPER),
			new Drop("Master Skull (Tier IV)", 3.195, Items.WITHER_SKELETON_SKULL, true),
			new Drop("Second Master Star", 2.74, Items.NETHER_STAR, true),
			new Drop("Recombobulator 3000", 1.0, Items.NETHER_STAR)
		)),
		Map.entry(5, List.of(
			new Drop("Fuming Potato Book", 0.81, Items.BOOK),
			new Drop("Shadow Assassin Boots", 1.34, Items.LEATHER_BOOTS),
			// Real bug found (per user report — "Shadow assassin helmet has a texture"): confirmed via live
			// Hypixel API pull (item id SHADOW_ASSASSIN_HELMET, material SKULL_ITEM, a real confirmed skin) —
			// it's a player-head-based item, not dyed leather like its Boots sibling.
			new Drop("Shadow Assassin Helmet", 0.87, Items.PLAYER_HEAD),
			// Per user request (obfuscation-list spec — "M5: ...shadow assassin chestplate..."): completes the
			// Shadow Assassin set alongside its Boots/Helmet siblings above — icon/weight approximated the same
			// way Boots' own leather-armor icon is (unconfirmed real icon, same honest-guess treatment).
			new Drop("Shadow Assassin Chestplate", 0.87, Items.LEATHER_CHESTPLATE),
			// Real bug found (per user report — "I finally got what the spirit bone on m5 is. Its an end stone
			// because of the warped stone. It has a texture in the game. Its also rare."): confirmed via a live
			// Hypixel API pull (item id AOTE_STONE) — Warped Stone is a real player-head-based item (material
			// SKULL_ITEM, a real confirmed skin), not plain End Stone; Items.PLAYER_HEAD matches this table's
			// usual fallback convention for a head-based item.
			new Drop("Warped Stone", 0.06, Items.PLAYER_HEAD),
			// Real bug found (per user report — "Livid daggers are iron"): Items.WOODEN_SWORD was a plain
			// placeholder guess, not the real weapon-shape icon.
			new Drop("Livid Dagger", 1.05, Items.IRON_SWORD),
			new Drop("Master Skull (Tier V)", 1.465, Items.WITHER_SKELETON_SKULL, true),
			new Drop("Third Master Star", 0.69, Items.NETHER_STAR, true),
			// Per user request (rare-sound restriction spec — "F5: Shadow Fury"/"M5: Shadow Fury, Third Master
			// Star"): not in either wiki pull this table was built from, added directly per that spec. Real
			// Floor 5/M5 Livid drop, not master-only. Weight is an unsourced approximation (same honest-guess
			// treatment this table already gives Necron's Handle/the wither scrolls), placed near Livid
			// Dagger's own rarity. Given an explicit extra dampening in rollWeight() (EXTRA_DAMPENED_NAMES)
			// addressing the earlier "a LOT" complaint. Real bug found (per user report — "Shadow fury shows
			// some weird texture, it should just be the regular diamond sword"): a real Hypixel id
			// (SHADOW_FURY) does exist and was briefly wired in as a SKYBLOCK_ID_OVERRIDES entry, but its real
			// icon is a Hypixel resource-pack "item_model" reskin rather than a plain skin texture — those
			// don't render reliably through this table's real-icon path, unlike guaranteed-safe skin-based
			// items — removed, keeping the plain vanilla Diamond Sword the user explicitly asked for.
			new Drop("Shadow Fury", 0.5, Items.DIAMOND_SWORD)
		)),
		Map.entry(6, List.of(
			// Real bug found (per user report — "Necromancer and giants sword are both iron swords"): confirmed
			// via live Hypixel API pull (item id GIANTS_SWORD, material IRON_SWORD) — Items.STONE_SWORD was a
			// wrong guess. Per user request ("Giants sword... should have the same chance to show as a wither
			// scroll"): see EXTRA_DAMPENED_NAMES in rollWeight() below.
			new Drop("Giant's Sword", 0.19, Items.IRON_SWORD),
			// Per user report ("the precursor eyes, those are actually rarer than a handle and should be
			// treated as such"): real weight (0.11) is already slightly below Necron's Handle's (0.13), but
			// Floor 6's own pool is much smaller than Floor 7's, so the same raw weight buys Precursor Eye a
			// bigger share of ITS floor's strip than Necron's Handle gets of Floor 7's — same "small pool
			// inflates a rare item's visible frequency" effect Giant's Sword/Shadow Fury needed dampening for.
			// See EXTRA_DAMPENED_NAMES below.
			new Drop("Precursor Eye", 0.11, Items.ENDER_EYE),
			// Real icon de-duplication (per user report of confusing "shadow furys on m6" — this table has no
			// actual Shadow Fury entry on Floor 6, but this item shared its exact Diamond Sword icon, which is
			// the far more likely real explanation): changed to Iron Sword so the two are visually distinct
			// during the fast scroll animation.
			new Drop("Necromancer Sword", 1.25, Items.IRON_SWORD),
			new Drop("Summoning Ring", 0.38, Items.GOLD_NUGGET),
			// Real bug found (per user report — "There is no wither skull item on f6. All items that i havent
			// mentioned have skinned heads."): confirmed via a live Hypixel API pull (item id FEL_SKULL) — Fel
			// Skull is a real player-head-based item (material SKULL_ITEM, a real confirmed skin), not a plain
			// vanilla wither skeleton skull.
			new Drop("Fel Skull", 0.56, Items.PLAYER_HEAD),
			// Real bug found (per user report — "Master skulls 1-2 are common, 3-4 are uncommon, 5 is rare, 6
			// is epic (undroppable) and 7 is legendary"): Tier VI is a real Hypixel item (confirmed EPIC via a
			// live API pull, colored accordingly in rarityOf) but a real Hypixel oddity — it doesn't actually
			// drop from chests — removed from this floor's droppable pool entirely rather than just re-colored.
			new Drop("Fourth Master Star", 0.58, Items.NETHER_STAR, true),
			new Drop("Recombobulator 3000", 1.0, Items.NETHER_STAR)
		)),
		Map.entry(7, List.of(
			// Real bug found (per user correction — "Wither armor is fully black leather armor FYI"): these
			// used Items.NETHERITE_* as a plausible-sounding "dark/black armor" guess, but the real in-game
			// item is dyed-black leather armor, not netherite — same class of wrong-icon bug as the earlier
			// baked-potato/nether-star fixes.
			new Drop("Wither Chestplate", 0.32, Items.LEATHER_CHESTPLATE),
			new Drop("Ench. Book: One For All I", 0.32, Items.ENCHANTED_BOOK),
			new Drop("Wither Leggings", 1.29, Items.LEATHER_LEGGINGS),
			new Drop("Wither Cloak Sword", 0.54, Items.NETHERITE_SWORD),
			// Real bug found (per user report — "Wither blood shows redstone for some reason, it has a
			// texture"): plain Items.REDSTONE was a plausible name-shaped guess, but the real item has its
			// own real texture — now tries SkyblockItemIcons first (see SKYBLOCK_ID_OVERRIDES below), keeping
			// this as the safe fallback if that id guess doesn't resolve.
			new Drop("Wither Blood", 1.08, Items.REDSTONE),
			// Real bug found (per user report — "Wither helmet has a texture"): unlike its Chestplate/
			// Leggings/Boots siblings (plain black-dyed leather), the real Helmet is a player-head-based item
			// (material SKULL_ITEM), same class of fix as Adaptive/Spirit/Shadow Assassin's own helmets.
			new Drop("Wither Helmet", 1.08, Items.PLAYER_HEAD),
			new Drop("Wither Boots", 2.15, Items.LEATHER_BOOTS),
			// Real bug found (per user correction — "Wither catalyst is a wither skull"): was Items.BLAZE_ROD,
			// a wrong name-shaped guess.
			new Drop("Wither Catalyst", 2.69, Items.WITHER_SKELETON_SKULL),
			new Drop("Precursor Gear", 3.76, Items.IRON_NUGGET),
			new Drop("Ench. Book: Soul Eater I", 3.23, Items.ENCHANTED_BOOK),
			// Real weight the user supplied directly ("Necron's handle chance is 0.13. Unsure of weight.") —
			// used exactly as given, same as every other real percentage in this table. Not master-only: a
			// real Normal Mode Floor 7 drop too, per the wiki source. Per user request ("I dont want to scroll
			// past a necrons handle when rolling a wooden chest"): given a real minTierRank (Bedrock, 4) —
			// this and Dark Claymore are the two clearest, best-known real Bedrock-chest-exclusive drops in
			// the whole table, unlike most other entries here whose exact minimum tier isn't confidently known.
			new Drop("Necron's Handle", 0.13, Items.STICK, false, 4),
			new Drop("Master Skull (Tier VII)", 0.16, Items.WITHER_SKELETON_SKULL, true),
			// Real bug found (per user correction — "the dark claymore is a stone sword"): Items.NETHERITE_SWORD
			// was a plausible "endgame weapon" guess, but the real icon is a Stone Sword.
			new Drop("Dark Claymore", 0.07, Items.STONE_SWORD, true, 4),
			new Drop("Fifth Master Star", 0.31, Items.NETHER_STAR, true),
			// Real Floor 7/M7 Necron chest drops (the wither-armor ability scrolls) — not in the original
			// wiki pull this table was built from, added per explicit user request ("a wither scroll").
			// Weights are an unsourced approximation (same honest-guess treatment this table already gives
			// Necron's Handle's own weight) — placed near Dark Claymore/Necron's Handle rarity since scrolls
			// are a similarly rare cosmetic-tier drop, not confirmed against a real wiki percentage.
			//
			// Real bug found (per user correction — "the three wither scrolls are book and quills... their
			// names: Wither Shield, Implosion, Shadow Warp"): these used placeholder "Wither Scroll I/II/III"
			// names with a plain Items.PAPER icon; real names and the real book-and-quill icon now used. Not
			// master-only — real Necron chest drops in Normal Mode too.
			new Drop("Wither Shield", 0.4, Items.WRITABLE_BOOK),
			new Drop("Implosion", 0.2, Items.WRITABLE_BOOK),
			new Drop("Shadow Warp", 0.1, Items.WRITABLE_BOOK),
			// Per user request (rare-sound restriction spec — "M7: ...Necron Dye..."): not in either wiki pull
			// this table was built from, added directly per that spec. Master Mode-only Necron chest drop.
			// Weight is an unsourced approximation, placed near Dark Claymore's own rarity.
			// Real bug found (per user correction — "the necron dye item is orange dye"): reverses an earlier
			// round's Items.DYE.purple() guess.
			new Drop("Necron Dye", 0.1, Items.DYE.orange(), true),
			new Drop("Recombobulator 3000", 1.0, Items.NETHER_STAR),
			// Per user request (obfuscation-list spec — "M7: ...auto recombobulator..."): the rarer upgraded
			// version, a real Master Mode Floor 7-exclusive drop (unlike the plain Recombobulator 3000 above,
			// which drops on every floor/mode).
			new Drop("Auto Recombobulator 3000", 0.2, Items.NETHER_STAR, true)
		))
	);

	// Per user follow-up ("the more rare it is to drop the less it should show, but make the chances higher
	// anyway so the user should see all items every so often"): the raw real percentages span a huge range
	// (Rejuvenate I at 41.92 down to Dark Claymore at 0.07 — a ~600x gap), which is exactly right for real
	// drop odds but means the rarest, most exciting entries (Necron's Handle, Master Skulls, Dark Claymore)
	// were mathematically almost never actually picked even inside buildDecoyStrip's own "guaranteed rare
	// slot" quota — a real still-broken instance of the same "the same common item shown constantly" issue
	// the guaranteed-slot mechanic was originally built to fix. Selection now rolls against the SQUARE ROOT
	// of each real weight instead of the raw value — still strictly rarer-shows-less (rank order is
	// unchanged, sqrt is monotonic), but compresses that 600x gap down to about 24x, so a genuinely rare
	// item has a real, human-noticeable chance of scrolling past instead of being effectively impossible in
	// a 50-card fake strip. The real percentages stored on each Drop are untouched — only the local roll math
	// is compressed, so this table still doubles as an accurate reference for real drop odds if read directly.
	// Per user follow-up ("forge the chances for rarer drops so they show up more often in the animation so
	// players think they missed out on something"): pushed past the earlier sqrt compression to a cube root
	// — still strictly monotonic (rarer always shows less), but flattens the ~600x real-odds gap down to
	// roughly 8-9x instead of ~24x, so even the single rarest entry on a floor has a real, frequent-enough
	// shot at appearing in a 50-card strip.
	// Per user request ("Giants sword and shadow fury should have the same chance to show as a wither scroll
	// basically... they need to be more rare"): both are otherwise the single highest-weighted entry on their
	// small floor's own pool (Floor 6 and Floor 5 respectively only have ~7-8 entries each, versus Floor 7's
	// ~20), so even after cbrt compression they took an outsized share of their floor's strip. Same explicit
	// extra dampening class as the Ench. Book case below, tuned to land in the same rollWeight ballpark as
	// Wither Shield/Implosion/Shadow Warp (Floor 7's own real "wither scroll" weights, ~0.1-0.4). Precursor Eye
	// joins this set per user follow-up ("the precursor eyes, those are actually rarer than a handle and
	// should be treated as such") — same small-pool effect. The five Master Stars join per user follow-up
	// ("There are still a bit too many stars") — their own real percentages (0.31-2.74) are high enough
	// relative to Master-Mode-only pools that they crowded out the strip even after the generic-filler dilution.
	// Per user request ("Livid daggers are really common in the chest rolling on f5/m5. Make them a bit more
	// uncommon"): joins the small-pool-inflation set below. Per user request ("Theres still a bit too many
	// rare items overall... every few runs or so the user should see like a really rare item, like a scroll,
	// handle, necron dye, anything like that. Same with the other floors, stuff like giants swords, shadow
	// furys, and spirit masks should be pretty rare to see"): widened from the prior 8-name set to also cover
	// every headline legendary/mythic-tier showpiece the user named directly — Necron's Handle, the three
	// wither scrolls, Dark Claymore, Necron Dye, and Spirit Mask (the rest of the Spirit set stays at normal
	// weight; only the piece the user specifically called out is extra-dampened) — on top of the dampening
	// multiplier itself being lowered (see rollWeight below) so this whole set reads as "every few runs", not
	// "every few cards".
	private static final Set<String> EXTRA_DAMPENED_NAMES = Set.of("Giant's Sword", "Shadow Fury", "Precursor Eye",
		"First Master Star", "Second Master Star", "Third Master Star", "Fourth Master Star", "Fifth Master Star",
		"Livid Dagger", "Necron's Handle", "Wither Shield", "Implosion", "Shadow Warp", "Dark Claymore",
		"Necron Dye", "Spirit Mask");

	private static double rollWeight(Drop d) {
		// Per user request ("Theres still a bit too many rare items overall"): the prior cube-root compression
		// (exponent 1/3 ≈ 0.333) over-corrected past the original square-root's own "still too common" report —
		// pulled back toward (but not all the way to) sqrt, to exponent 0.42, so the real-odds gap between a
		// common filler item and a genuine rare stays much wider than cbrt gave it, without returning all the
		// way to the original "rares almost never show" square-root problem this whole mechanism was built to
		// fix in the first place.
		double base = Math.pow(d.weight(), 0.42);
		// Per user request ("Throw in more rare drops in the animation, im not scrolling past enough
		// necrons handles and wither scrolls, and minimize the enchanted books, add more wither catalysts
		// and other items cause its mostly just enchanted books currently"): enchant books already dominate
		// every floor's own entry COUNT (there are simply more distinct book drops than anything else per
		// floor), so even after the cbrt compression above they still crowded out everything else in
		// practice. Extra display-only dampening applied ONLY here, on top of (not instead of) the existing
		// rarity compression — the real weight() value on each Drop is untouched, so this table still doubles
		// as an accurate real-odds reference if read directly; only the in-strip roll odds are further
		// suppressed for this one category.
		if (d.name().startsWith("Ench. Book:")) base *= 0.3;
		// Per user request (see EXTRA_DAMPENED_NAMES' own doc comment): lowered from 0.35 to push this whole
		// set further toward "every few runs" rather than "every few cards".
		if (EXTRA_DAMPENED_NAMES.contains(d.name())) base *= 0.2;
		return base;
	}

	/** Per user follow-up ("It should include all drops for the floor, then show either of them in the
	 *  panes it scrolls past, and throw in rare items sometimes"): the strip is now drawn from ONE unified
	 *  pool — this floor's own real {@link #FLOOR_RARES} entries, every single one of them, not just a
	 *  ~20% "guaranteed rare slot" quota layered on top of a separate shared filler pool. {@link
	 *  #GENERIC_FILLER} is now purely the fallback for a floor with no specific table entry (an unknown/
	 *  out-of-range floor number) rather than being mixed into every floor's own strip — the shared
	 *  cross-floor filler items (generic enchant books etc.) were part of what made the strip read as
	 *  showing "random stuff that isn't this floor's own loot" to begin with. */
	// Real bug found (per user report — "The chest rolling needs to throw in less fake items. It seems to
	// throw in a LOT of rare items on lower floors... M7 also shows a lot of drops. Look. The rare ones
	// shouldnt be everywhere, they should scale in rarity on chance to appear but should also be higher
	// chance to throw in fakes"): a floor's own {@link #FLOOR_RARES} list used to BE the entire pool, with no
	// common/generic filler mixed in at all — every single card in the strip was some floor-specific special
	// item, with nothing to dilute how often the rare ones came up. {@link #GENERIC_FILLER}'s own real
	// percentages (25-42) dwarf every floor-specific rare's real percentage (under 7 everywhere), so mixing
	// it into every floor's own pool (not just as a total fallback for an unmapped floor) makes plain common
	// drops the majority of each strip again, same as real Hypixel chest odds actually are — this alone cuts
	// every floor's rare-item density substantially without needing per-item tuning.
	private static List<Drop> floorPool(int floorNumber, boolean masterMode) {
		List<Drop> rares = FLOOR_RARES.get(floorNumber);
		List<Drop> pool;
		if (rares == null || rares.isEmpty()) {
			pool = GENERIC_FILLER;
		} else {
			List<Drop> combined = new java.util.ArrayList<>(rares.size() + GENERIC_FILLER.size() * 2);
			combined.addAll(rares);
			combined.addAll(GENERIC_FILLER);
			// Per user report ("There are still a bit too many rare drops in the m7/f7 rolling"): unlike every
			// other floor, Floor 7's own FLOOR_RARES list has NO ordinary-looking entries at all — every single
			// item on it (Wither armor, enchant books, Necron's Handle, the wither scrolls...) reads as
			// special/rare, so even one full copy of GENERIC_FILLER wasn't enough to bring its visual density
			// down to the other floors' level. Doubling the filler mix ONLY for Floor 7 pushes plain common
			// drops back to a comparable majority share without touching any other floor's own balance.
			if (floorNumber == 7) combined.addAll(GENERIC_FILLER);
			pool = combined;
		}
		if (masterMode || pool.stream().noneMatch(Drop::masterOnly)) return pool;
		return pool.stream().filter(d -> !d.masterOnly()).toList();
	}

	// Per user report ("There are still a lot of nether stars. If these are skyblock items find a way to
	// pull the item itself"): the Master Stars/Necromancer's Brooch/Master Skulls above render with plain
	// vanilla NETHER_STAR/WITHER_SKELETON_SKULL icons since this table only ever stored a hand-picked
	// vanilla Item, not each drop's real Hypixel item id — noticeable specifically for the Master Stars
	// since the cbrt rarity-boost (see rollWeight) makes them show up often. Resolved through
	// SkyblockItemIcons — the same real, Hypixel-API-backed item-icon database Personal Compactor Overlay/
	// Quick Action Buttons already use, not a guess — keyed here by this table's own Drop name rather than
	// touching the Drop record or its ~40 existing call sites. Safe by construction: SkyblockItemIcons
	// returns null for any id that doesn't actually match something in the real repo (network-loaded,
	// verified data), so a wrong guess here just keeps today's existing vanilla-icon fallback instead of
	// ever rendering some OTHER, unrelated real item — this can only make an icon MORE accurate, never
	// newly wrong the way guessing a raw vanilla Item constant already burned this table twice before.
	private static final Map<String, String> SKYBLOCK_ID_OVERRIDES = Map.ofEntries(
		Map.entry("Necromancer's Brooch", "NECROMANCER_BROOCH"),
		Map.entry("First Master Star", "FIRST_MASTER_STAR"),
		Map.entry("Second Master Star", "SECOND_MASTER_STAR"),
		Map.entry("Third Master Star", "THIRD_MASTER_STAR"),
		Map.entry("Fourth Master Star", "FOURTH_MASTER_STAR"),
		Map.entry("Fifth Master Star", "FIFTH_MASTER_STAR"),
		Map.entry("Master Skull (Tier I)", "MASTER_SKULL_TIER_1"),
		Map.entry("Master Skull (Tier II)", "MASTER_SKULL_TIER_2"),
		Map.entry("Master Skull (Tier III)", "MASTER_SKULL_TIER_3"),
		Map.entry("Master Skull (Tier IV)", "MASTER_SKULL_TIER_4"),
		Map.entry("Master Skull (Tier V)", "MASTER_SKULL_TIER_5"),
		Map.entry("Master Skull (Tier VI)", "MASTER_SKULL_TIER_6"),
		Map.entry("Master Skull (Tier VII)", "MASTER_SKULL_TIER_7"),
		Map.entry("Precursor Gear", "PRECURSOR_GEAR"),
		Map.entry("Precursor Eye", "PRECURSOR_EYE"),
		Map.entry("Necron's Handle", "NECRONS_HANDLE"),
		Map.entry("Hot Potato Book", "HOT_POTATO_BOOK"),
		Map.entry("Fuming Potato Book", "FUMING_POTATO_BOOK"),
		// Real Hypixel item ids for the F1/M1 head-based items fixed this round (see the F1 FLOOR_RARES
		// entries' own doc comment) and Wither Helmet (see its own entry above) — safe regardless of
		// confirmation status since SkyblockItemIcons fails closed to the Items.PLAYER_HEAD fallback above on
		// any id that doesn't actually resolve.
		Map.entry("Bonzo's Mask", "BONZO_MASK"),
		Map.entry("Red Nose", "RED_NOSE"),
		Map.entry("Wither Helmet", "WITHER_HELMET"),
		// Real bug found (per user reports this round — "I finally got what the spirit bone on m5 is. Its an
		// end stone because of the warped stone. It has a texture in the game."/"No clue what the leather is on
		// m2 but find and replace the texture since there is no leather."/"There is no wither skull item on
		// f6"): confirmed via a live Hypixel API pull that Warped Stone, Red Scarf, Scarf's Studies, and Fel
		// Skull are all real player-head-based items (material SKULL_ITEM, real confirmed skins).
		Map.entry("Warped Stone", "AOTE_STONE"),
		Map.entry("Red Scarf", "RED_SCARF"),
		Map.entry("Scarf's Studies", "SCARF_STUDIES"),
		Map.entry("Fel Skull", "FEL_SKULL"),
		// Unconfirmed real Hypixel API ids (best-effort guess, not cross-checked against the item repo like
		// the rest of this map) — safe regardless, since SkyblockItemIcons fails closed to the existing
		// vanilla-icon fallback on any id that doesn't actually resolve.
		Map.entry("Wither Shield", "WITHER_SHIELD_SCROLL"),
		Map.entry("Implosion", "IMPLOSION_SCROLL"),
		Map.entry("Shadow Warp", "SHADOW_WARP_SCROLL"),
		Map.entry("Wither Blood", "WITHER_BLOOD"),
		// Confirmed real ids this round via a live pull of the actual Hypixel API
		// (https://api.hypixel.net/v2/resources/skyblock/items), replacing last round's unconfirmed guesses.
		Map.entry("Adaptive Chestplate", "ADAPTIVE_CHESTPLATE"),
		Map.entry("Adaptive Helmet", "ADAPTIVE_HELMET"),
		Map.entry("Adaptive Boots", "ADAPTIVE_BOOTS"),
		Map.entry("Adaptive Leggings", "ADAPTIVE_LEGGINGS"),
		Map.entry("Spirit Mask", "SPIRIT_MASK"),
		Map.entry("Spirit Boots", "THORNS_BOOTS"),
		// Real bug found (per user report — "The spirit wing is not an elytra it has a texture/head"): added
		// this round now that the real id is confirmed (was missing entirely before).
		Map.entry("Spirit Wing", "SPIRIT_WING"),
		// Real bug found (per user report — "Spirit shortbow is legendary and its a regular bow no texture"):
		// the real id (ITEM_SPIRIT_BOW, confirmed) only ever resolves via Hypixel's newer "item_model" resource-
		// pack reskin, not a plain skin texture — same class of unreliable-render issue this table's own
		// Shadow Fury/Giant's Sword/Livid Dagger/Dark Claymore/Adaptive Blade removal already documented, and
		// the user explicitly confirmed the real in-game item is just a plain bow with no special texture
		// anyway. Removed entirely rather than re-added.
		Map.entry("Spirit Bone", "SPIRIT_BONE"),
		// Real bug found (per user report — "Shadow assassin helmet has a texture"): added this round now that
		// the real id is confirmed (was missing entirely before).
		Map.entry("Shadow Assassin Helmet", "SHADOW_ASSASSIN_HELMET"),
		Map.entry("Summoning Ring", "SUMMONING_RING"),
		// Real, well-known Hypixel Skyblock item ids (confirmed, not a guess).
		Map.entry("Recombobulator 3000", "RECOMBOBULATOR_3000"),
		// Real bug found (per user report — "Master stars on hypixel are NOT nether stars. They have a
		// texture. I think something is fallbacking to the nether star and i cant tell what."): confirmed via
		// a live pull of the actual Hypixel API that every Master Star tier and the plain Recombobulator 3000
		// above already had a correct, resolving id — this one didn't. The guessed id "AUTO_RECOMBOBULATOR_3000"
		// doesn't exist in the real item repo at all (silently failing closed to the vanilla NETHER_STAR
		// fallback below, per SkyblockItemIcons' own fail-closed design); the real id is "AUTO_RECOMBOBULATOR"
		// (Hypixel's own display name for it is also just "Auto Recombobulator", without "3000" — this table's
		// own drop name keeps the "3000" suffix regardless since that's the name it actually shows in-game).
		Map.entry("Auto Recombobulator 3000", "AUTO_RECOMBOBULATOR"),
		// Unconfirmed real Hypixel API id (best-effort guess, same treatment as Wither Shield/Implosion/Shadow
		// Warp above) — safe regardless since SkyblockItemIcons fails closed to the vanilla fallback.
		Map.entry("Shadow Assassin Chestplate", "SHADOW_ASSASSIN_CHESTPLATE")
		// Real bug found (per user correction — "Wither catalyst is a wither skull and NOT coal"): this used
		// to also guess "WITHER_CATALYST" here, which turned out to be a REAL id in SkyblockItemRepo whose
		// real "material" field is COAL — not a wrong/unresolved guess (which would have failed closed to
		// the vanilla fallback below), but a real repo entry that just isn't the actual visual drop the user
		// sees in-game. Removed entirely so this always falls back to the confirmed-correct
		// Items.WITHER_SKELETON_SKULL vanilla icon above instead.
		//
		// Real bug found (per user reports this round — "Shadow fury shows some weird texture, it should just
		// be the regular diamond sword"/"Necromancer and giants sword are both iron swords"/"Dark claymore is
		// a stone sword"): Giant's Sword, Shadow Fury, Livid Dagger, Dark Claymore, and Adaptive Blade were all
		// removed from this map this round (Giant's Sword's own guessed id was also simply wrong, "GIANT_SWORD"
		// vs the real "GIANTS_SWORD"). All five are real Hypixel weapon items reskinned via the newer
		// "item_model" resource-pack override system (see SkyblockItemRepo.ItemInfo's own doc comment) rather
		// than a plain skin texture, confirmed by this round's live API pull. Unlike a skin (a literal base64
		// texture blob, always renderable), an item_model reference only resolves to Hypixel's own bundled
		// resource-pack model, which the "weird texture" report shows doesn't reliably render through this
		// table's icon path — so these five now intentionally keep their plain vanilla fallback icon instead.
	);

	/** The real Hypixel Skyblock item id for this drop's name, or null if this table has no confirmed id
	 *  for it yet (caller keeps its own existing vanilla-icon fallback in that case). */
	static String skyblockIdFor(String dropName) {
		return SKYBLOCK_ID_OVERRIDES.get(dropName);
	}

	/** Per user request ("Also add the /chestopening f7 handle/withershield/implosion/shadowwarp to force a
	 *  rare drop to test", later broadened — "Add support for forcing all of these drops in singleplayer so i
	 *  can test how it looks"): looks up any real drop by exact name, for the test command to force as the
	 *  winner bypassing the normal weighted pick entirely. Searches every floor's table (not just F7 — Giant's
	 *  Sword/Shadow Fury/the Master Stars each live on their own specific floor, not F7), since the forced-name
	 *  argument alone doesn't say which floor's list to look in. Null if no floor's table has that exact name. */
	static Drop findObfuscationEligibleDrop(String name) {
		for (List<Drop> floorDrops : FLOOR_RARES.values()) {
			for (Drop d : floorDrops) {
				if (d.name().equals(name)) return d;
			}
		}
		return null;
	}

	// Per user request (verbatim spec): "The rare sound on chest opening should literally ONLY play on these
	// rare drops on each floor" — F5: Shadow Fury; F6: Giant's Sword; F7: Necron's Handle + all three wither
	// scrolls; M3: First Master Star; M4: Second Master Star; M5: Shadow Fury + Third Master Star; M6: Giant's
	// Sword + Fourth Master Star; M7: Necron's Handle, Dark Claymore, Necron Dye, all three wither scrolls,
	// Fifth Master Star. Replaces the old blanket "any RARE-or-better lore rarity" landing-sound check (see
	// ChestRollingFeature#isBigWinner's old doc comment) — that fired for lots of real rare-but-not-headline
	// drops (Wither armor pieces, Wither Catalyst, enchanted books) never actually in this exact list. Keyed
	// by the same "F5"/"M5"-style floor label DungeonState.getFloorLabel() already produces, uppercased so a
	// stray-case caller can't silently miss.
	private static final Map<String, Set<String>> RARE_SOUND_DROPS = Map.ofEntries(
		// Per user request ("M4 ... should also play the sound as it is the most expensive drop" — referring
		// to the Spirit set, headlined by the Spirit Pet): F4 had no entry at all before this, so nothing on
		// that floor could ever trigger the landing sound. Both the Epic and Legendary Spirit Pet variants
		// (see FLOOR_RARES' own doc comment on the split) trigger it.
		Map.entry("F4", Set.of("[EPIC] Spirit Pet", "[LEGENDARY] Spirit Pet")),
		Map.entry("F5", Set.of("Shadow Fury")),
		Map.entry("F6", Set.of("Giant's Sword")),
		Map.entry("F7", Set.of("Necron's Handle", "Wither Shield", "Implosion", "Shadow Warp")),
		Map.entry("M3", Set.of("First Master Star")),
		Map.entry("M4", Set.of("[EPIC] Spirit Pet", "[LEGENDARY] Spirit Pet", "Second Master Star")),
		Map.entry("M5", Set.of("Shadow Fury", "Third Master Star")),
		Map.entry("M6", Set.of("Giant's Sword", "Fourth Master Star")),
		Map.entry("M7", Set.of("Necron's Handle", "Dark Claymore", "Necron Dye", "Wither Shield", "Implosion",
			"Shadow Warp", "Fifth Master Star"))
	);

	/** True if {@code plainWinnerName} (already color-code-stripped by the caller) is one of the exact rare
	 *  drops that should play Chest Rolling's landing sound on {@code floorLabel} — see {@link
	 *  #RARE_SOUND_DROPS}'s own doc comment for the full per-floor list. Substring match (not equals), same
	 *  tolerance the obfuscation-eligibility check elsewhere in this feature already uses, since a real live
	 *  drop's own Hypixel-supplied name isn't guaranteed to be byte-for-byte identical to this table's plain
	 *  label. */
	static boolean isRareSoundDrop(String floorLabel, String plainWinnerName) {
		if (floorLabel == null || plainWinnerName == null) return false;
		Set<String> names = RARE_SOUND_DROPS.get(floorLabel.toUpperCase(Locale.ROOT));
		if (names == null) return false;
		for (String name : names) {
			if (plainWinnerName.contains(name)) return true;
		}
		return false;
	}

	/** A random decoy for this floor's strip — weighted (via {@link #rollWeight}) toward that floor's own
	 *  more common entries, without the raw real-percentage gap making the rarest ones nearly unpickable. */
	static Drop randomDecoy(int floorNumber, boolean masterMode) {
		return weightedPick(floorPool(floorNumber, masterMode), ThreadLocalRandom.current());
	}

	// Real bug found (per user report — "It seems to throw in a LOT of rare items on lower floors. Also it
	// seems to throw in drops that arent even possible"): this used to also maintain a WITHER_PIECE_POOL
	// sprinkled into EVERY floor's strip regardless of which floor was actually being played (per an earlier
	// round's "it should almost always scroll past at least some wither piece" request) — since Wither armor
	// is a real Floor 7/M7-exclusive drop, that meant Floor 1-6 strips could show a literally impossible item
	// for that floor. Removed entirely: Wither pieces are already part of Floor 7's own {@link #FLOOR_RARES}
	// list and now show there via {@link #floorPool} like every other floor-specific drop, without also being
	// force-sprinkled onto floors that can't actually drop them.
	private static final java.util.Set<String> RARE_SHOWPIECE_NAMES = java.util.Set.of(
		"Necron's Handle", "Dark Claymore", "Master Skull (Tier VII)",
		"Wither Shield", "Implosion", "Shadow Warp");

	/** Per user request ("Add a subtoggle inside it that obfuscates rare items... which should account for
	 *  the three wither scrolls and necron's handle for now"), later expanded to a full per-floor list
	 *  (verbatim spec — "M3: recombobulator, master skull, first master star. M4: Spirit wing, spirit pet
	 *  (legendary only), recombobulator, master skull, second master star. M5: recombobulator, shadow fury,
	 *  third master star, shadow assassin chestplate, master skull. M6: giant's sword, recombobulator, master
	 *  skull, fourth master star, precursor eye, summoning ring. M7: necron's handle, 5th master star, necron
	 *  dye, dark claymore, auto recombobulator, implosion, shadow warp, wither shield, master skull,
	 *  recombobulator, wither chestplate."): the specific subset of rare drops Chest Rolling's "Obfuscate Rare
	 *  Items" toggle disguises as a mystery item while scrolling, revealing the real item only once it lands —
	 *  keyed by the same "M3"-style floor label {@link #RARE_SOUND_DROPS} already uses, Master Mode only
	 *  (Normal Mode floors have no entry and are therefore never obfuscated, matching the user's own spec —
	 *  every list given was explicitly for a master-mode floor). */
	private static final Map<String, Set<String>> OBFUSCATION_ELIGIBLE_BY_FLOOR = Map.ofEntries(
		Map.entry("M3", Set.of("Recombobulator 3000", "Master Skull (Tier III)", "First Master Star")),
		Map.entry("M4", Set.of("Spirit Wing", "[EPIC] Spirit Pet", "[LEGENDARY] Spirit Pet", "Recombobulator 3000",
			"Master Skull (Tier IV)", "Second Master Star")),
		Map.entry("M5", Set.of("Recombobulator 3000", "Shadow Fury", "Third Master Star",
			"Shadow Assassin Chestplate", "Master Skull (Tier V)")),
			// "Master Skull (Tier VI)" removed from this set (see its own FLOOR_RARES doc comment — it's a
			// real Hypixel oddity that doesn't actually drop from chests, so it was never actually reachable
			// here either).
			Map.entry("M6", Set.of("Giant's Sword", "Recombobulator 3000",
				"Fourth Master Star", "Precursor Eye", "Summoning Ring")),
		Map.entry("M7", Set.of("Necron's Handle", "Fifth Master Star", "Necron Dye", "Dark Claymore",
			"Auto Recombobulator 3000", "Implosion", "Shadow Warp", "Wither Shield", "Master Skull (Tier VII)",
			"Recombobulator 3000", "Wither Chestplate"))
	);

	/** True if {@code winnerHoverText} (a real live drop's own colored name, or a decoy-built winner's plain
	 *  "§7&lt;name&gt;" text) contains one of {@code floorLabel}'s obfuscation-eligible names above. Null/blank
	 *  floorLabel (shouldn't happen once a roll has actually started) or a floor with no entry (every Normal
	 *  Mode floor, plus M1/M2) never obfuscates. */
	static boolean isObfuscationEligible(String floorLabel, String winnerHoverText) {
		if (floorLabel == null || winnerHoverText == null) return false;
		Set<String> names = OBFUSCATION_ELIGIBLE_BY_FLOOR.get(floorLabel.toUpperCase(Locale.ROOT));
		if (names == null) return false;
		for (String name : names) if (winnerHoverText.contains(name)) return true;
		return false;
	}
	private static final List<Drop> RARE_SHOWPIECE_POOL = FLOOR_RARES.get(7).stream()
		.filter(d -> RARE_SHOWPIECE_NAMES.contains(d.name())).toList();

	/** Same masterOnly filter as {@link #floorPool}, applied to the two cross-floor sprinkle pools above so
	 *  a Master Mode-only showpiece (Dark Claymore, Master Skull Tier VII) can't sprinkle into a Normal Mode
	 *  strip either — real bug found alongside the same one {@link #floorPool} fixes. */
	private static List<Drop> masterFiltered(List<Drop> pool, boolean masterMode) {
		if (masterMode || pool.stream().noneMatch(Drop::masterOnly)) return pool;
		return pool.stream().filter(d -> !d.masterOnly()).toList();
	}

	/** A single random rare-showpiece Drop (Necron's Handle/the 3 wither scrolls/Dark Claymore/Master Skull
	 *  VII), weighted by each entry's own real confirmed chance — used by ChestRollingFeature#startRoll's own
	 *  "5% chance to place a rare item right next to the real winner" mechanic (Floor 7/M7 only, since every
	 *  one of these is a real Floor 7-exclusive item). Null if Master Mode filtering leaves the pool empty
	 *  (never happens today — the pool has non-master entries too — but keeps this safe if that ever changes). */
	static Drop randomRareShowpiece(boolean masterMode, int chestTierRank) {
		List<Drop> pool = masterFiltered(RARE_SHOWPIECE_POOL, masterMode).stream()
			.filter(d -> d.minTierRank() <= chestTierRank).toList();
		return pool.isEmpty() ? null : weightedPick(pool, ThreadLocalRandom.current());
	}

	/** A full decoy strip for Chest Rolling's scroll animation — weighted sampling (via {@link
	 *  #rollWeight}) from this floor's own real drop pool ({@link #floorPool}) only, avoiding the exact same
	 *  decoy appearing twice in a row so common entries don't visually spam back-to-back. Per user request
	 *  ("make sure the drops match their respective chests... I dont want to scroll past a necrons handle
	 *  when rolling a wooden chest"): also filtered to {@code chestTierRank} (see {@link #chestTierRank}) so
	 *  a drop with a real confirmed minimum tier can't appear in a strip for a lower-tier chest. */
	static List<Drop> buildDecoyStrip(int floorNumber, boolean masterMode, int length, int chestTierRank) {
		List<Drop> full = floorPool(floorNumber, masterMode);
		List<Drop> tierFiltered = full.stream().filter(d -> d.minTierRank() <= chestTierRank).toList();
		// Safety net: never let tier-filtering leave an empty pool (shouldn't happen today since every
		// floor's pool is dominated by minTierRank-0 generic filler, but a future floor with nothing but
		// high-tier-only entries would otherwise crash weightedPick on a zero-weight roll).
		List<Drop> pool = tierFiltered.isEmpty() ? full : tierFiltered;
		ThreadLocalRandom rng = ThreadLocalRandom.current();

		Drop[] strip = new Drop[length];
		String lastName = null;
		for (int i = 0; i < length; i++) {
			Drop pick = weightedPick(pool, rng);
			// One retry is enough to break up back-to-back repeats without risking a long stall on a tiny
			// pool (some floors only have 4-5 entries defined).
			if (pool.size() > 1 && pick.name().equals(lastName)) pick = weightedPick(pool, rng);
			strip[i] = pick;
			lastName = pick.name();
		}

		// Real bug found (per user report — "M7 also shows a lot of drops. Look. The rare ones shouldnt be
		// everywhere... When theres a handle every 3rd slot it stops making sense"): this used to also force-
		// sprinkle Necron's Handle/the 3 wither scrolls/Dark Claymore/Master Skull VII into 1-2 extra slots per
		// strip (on top of them already being reachable through floorPool's own weighted pick on Floor 7),
		// which is exactly the "handle every 3rd slot" complaint — real double-counting of the same items.
		// Removed: these are already part of Floor 7's own {@link #FLOOR_RARES} list and now diluted by the
		// generic-filler mix in {@link #floorPool} like every other floor, so they show at their real relative
		// rarity instead of being force-injected on top of it.
		return List.of(strip);
	}

	private static Drop weightedPick(List<Drop> drops, ThreadLocalRandom rng) {
		double totalWeight = 0;
		for (Drop d : drops) totalWeight += rollWeight(d);
		double roll = rng.nextDouble(totalWeight);
		for (Drop d : drops) {
			if (roll < rollWeight(d)) return d;
			roll -= rollWeight(d);
		}
		return drops.get(drops.size() - 1);
	}
}
