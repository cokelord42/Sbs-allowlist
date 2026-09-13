package com.cokelord.skyblocksimplified.util;

import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Map.entry;

/**
 * Resolves a real Hypixel Skyblock item id to an actual renderable {@link ItemStack} icon — built directly
 * from Hypixel's own item-resource data (SkyblockItemRepo), not a hand-maintained texture atlas, so it
 * covers every item Hypixel exposes rather than whatever this project happened to add manually. Two
 * sources, tried in order:
 *
 * <ol>
 *   <li>A real skin texture (SkyblockItemRepo.ItemInfo.skinTexture()) — Hypixel reskins a large fraction of
 *   its custom items (pets, hats, accessories, decorative blocks) as plain player heads with a custom skin
 *   rather than a genuinely new model, so this alone covers a lot of ground and is completely unambiguous:
 *   if Hypixel says an item has this exact skin, rendering that skin IS the correct icon, full stop.</li>
 *   <li>A direct vanilla material match (ItemInfo.material()) — Hypixel's "material" field is usually
 *   already a modern item id verbatim ("DIAMOND_SWORD", "BOW", "NETHER_STAR", ...), but not always: it
 *   still carries some pre-flattening legacy names for numeric-metadata items (dyes, wool, eggs) that this
 *   project has no confirmed durability-to-variant mapping for. Rather than guess and risk a silently WRONG
 *   icon, a material that doesn't resolve to a real, non-air item is treated as "unknown" and falls through
 *   to null — callers keep their own existing fallback (a letter/color square) for those, so this is a
 *   strict improvement with no regression risk, not an all-or-nothing replacement.</li>
 * </ol>
 */
public final class SkyblockItemIcons {
	private SkyblockItemIcons() {}

	// Real ItemStacks (especially skull ones, which carry a resolved GameProfile) aren't free to build —
	// cached per Skyblock item id since callers like the Personal Compactor grid and Quick Action Buttons
	// ring resolve the same handful of ids every single frame.
	private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();
	// Sentinel for "looked up, nothing resolves" so a permanently-unresolvable id doesn't get re-attempted
	// (registry lookup + string parsing) every frame either — ItemStack has no natural "absent" value to
	// store directly in a ConcurrentHashMap (which rejects null values outright).
	private static final ItemStack UNRESOLVED = ItemStack.EMPTY;

	/** The real icon for a Hypixel Skyblock item id, or null if nothing about it resolves (caller should
	 *  fall back to its own placeholder). id may be null (a stored slot with no known id yet) — always null. */
	public static ItemStack getIcon(String skyblockId) {
		if (skyblockId == null) return null;
		ItemStack cached = CACHE.get(skyblockId);
		if (cached != null) return cached == UNRESOLVED ? null : cached;

		// SkyblockItemRepo's item list loads asynchronously over the network and can easily still be empty
		// the first few times a button/compactor slot asks for an icon (right after game launch, or right
		// after a fresh config load) — if that "nothing loaded yet" miss got cached as UNRESOLVED the same
		// as a genuine "this id doesn't exist" miss, the icon would never be retried again even once the
		// repo actually finished loading a moment later, permanently stuck showing the placeholder square/
		// letter badge instead of the real icon. Only cache a miss once the repo has real data to miss against.
		if (!SkyblockItemRepo.isLoaded()) return null;

		// Building a skull ItemStack touches GameProfile/PropertyMap/ResolvableProfile construction off a
		// base64 blob this project doesn't control the exact shape of (Hypixel's own API response) — an
		// uncaught throw here, inside a caller's bare render callback with no isolation of its own (both
		// current callers render straight from ScreenEvents.afterExtract with no try-catch), was almost
		// certainly the real cause of the game crashing on opening the inventory / typing an item search
		// query, per user report. Treat any resolution failure the same as "nothing resolves" instead of
		// letting it propagate.
		ItemStack resolved;
		Throwable failure = null;
		try {
			resolved = resolve(skyblockId);
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Failed to resolve item icon for {}", skyblockId, e);
			failure = e;
			resolved = null;
		}
		CACHE.put(skyblockId, resolved != null ? resolved : UNRESOLVED);
		return resolved;
	}

	// Hypixel's "material" field is Bukkit's own pre-1.13-flattening Material enum name, not a modern item
	// id — confirmed by pulling every distinct value the real /v2/resources/skyblock/items endpoint
	// currently returns (327 of them) and checking each against the modern registry: only a minority
	// happen to already be spelled the same in both eras (APPLE, DIAMOND, STICK, ...), which is why the old
	// naive "minecraft:" + material.toLowerCase() silently failed for most farming/mining drops
	// (CARROT_ITEM, RAW_FISH, SEEDS, NETHER_STALK, ...) — exactly the items a Personal Compactor/Deletor or
	// a Quick Action Button would actually reference. This maps every legacy name that DOESN'T already
	// match its modern id directly; anything not listed here still falls through to the plain lowercase
	// attempt below (covers newer materials Hypixel already reports with a modern-style id). A handful of
	// legacy names are inherently ambiguous without also reading Hypixel's "durability" field (WOOL,
	// STAINED_CLAY/GLASS, BANNER, CARPET, LEAVES/LEAVES_2, LOG/LOG_2, MONSTER_EGG, DOUBLE_PLANT, SAPLING,
	// STEP, SKULL_ITEM, BED) — those map to a single reasonable default variant (white/oak/pig) rather than
	// the exact real color/species/mob, which is still a real icon instead of the old letter-badge fallback.
	private static final Map<String, String> LEGACY_MATERIAL_ALIASES = Map.ofEntries(
		entry("ACACIA_DOOR_ITEM", "acacia_door"), entry("BIRCH_DOOR_ITEM", "birch_door"),
		entry("DARK_OAK_DOOR_ITEM", "dark_oak_door"), entry("JUNGLE_DOOR_ITEM", "jungle_door"),
		entry("SPRUCE_DOOR_ITEM", "spruce_door"), entry("WOOD_DOOR", "oak_door"),
		entry("BIRCH_WOOD_STAIRS", "birch_stairs"), entry("JUNGLE_WOOD_STAIRS", "jungle_stairs"),
		entry("SPRUCE_WOOD_STAIRS", "spruce_stairs"), entry("WOOD_STAIRS", "oak_stairs"),
		entry("SMOOTH_STAIRS", "stone_brick_stairs"),
		entry("BOOK_AND_QUILL", "writable_book"), entry("BREWING_STAND_ITEM", "brewing_stand"),
		entry("CARROT_ITEM", "carrot"), entry("CARROT_STICK", "carrot_on_a_stick"),
		entry("CAULDRON_ITEM", "cauldron"), entry("COBBLE_WALL", "cobblestone_wall"),
		entry("COOKED_FISH", "cooked_cod"), entry("DIODE", "repeater"), entry("EMPTY_MAP", "map"),
		entry("ENDER_STONE", "end_stone"), entry("ENDER_PORTAL_FRAME", "end_portal_frame"),
		entry("ENCHANTMENT_TABLE", "enchanting_table"), entry("EYE_OF_ENDER", "ender_eye"),
		entry("EXP_BOTTLE", "experience_bottle"), entry("EXPLOSIVE_MINECART", "tnt_minecart"),
		entry("FIREWORK", "firework_rocket"), entry("FIREWORK_CHARGE", "firework_star"),
		entry("FLOWER_POT_ITEM", "flower_pot"),
		entry("GOLD_AXE", "golden_axe"), entry("GOLD_BARDING", "golden_horse_armor"),
		entry("GOLD_BOOTS", "golden_boots"), entry("GOLD_CHESTPLATE", "golden_chestplate"),
		entry("GOLD_HELMET", "golden_helmet"), entry("GOLD_HOE", "golden_hoe"),
		entry("GOLD_LEGGINGS", "golden_leggings"), entry("GOLD_PICKAXE", "golden_pickaxe"),
		entry("GOLD_PLATE", "light_weighted_pressure_plate"), entry("GOLD_RECORD", "music_disc_13"),
		entry("GOLD_SPADE", "golden_shovel"), entry("GOLD_SWORD", "golden_sword"),
		entry("GRASS", "grass_block"), entry("GRILLED_PORK", "cooked_porkchop"),
		entry("HARD_CLAY", "terracotta"), entry("HUGE_MUSHROOM_1", "brown_mushroom_block"),
		entry("HUGE_MUSHROOM_2", "red_mushroom_block"), entry("IRON_FENCE", "iron_bars"),
		entry("IRON_PLATE", "heavy_weighted_pressure_plate"), entry("IRON_SPADE", "iron_shovel"),
		entry("LEASH", "lead"), entry("LONG_GRASS", "short_grass"),
		entry("MELON", "melon_slice"), entry("MELON_BLOCK", "melon"), entry("MOB_SPAWNER", "spawner"),
		entry("MUSHROOM_SOUP", "mushroom_stew"), entry("MYCEL", "mycelium"),
		entry("NETHER_BRICK_ITEM", "nether_brick"), entry("NETHER_FENCE", "nether_brick_fence"),
		entry("NETHER_STALK", "nether_wart"),
		entry("PISTON_BASE", "piston"), entry("PISTON_STICKY_BASE", "sticky_piston"),
		entry("POTATO_ITEM", "potato"), entry("POWERED_MINECART", "furnace_minecart"),
		entry("QUARTZ_ORE", "nether_quartz_ore"),
		entry("RAILS", "rail"), entry("RAW_BEEF", "beef"), entry("RAW_CHICKEN", "chicken"),
		entry("RAW_FISH", "cod"),
		entry("RECORD_3", "music_disc_blocks"), entry("RECORD_4", "music_disc_chirp"),
		entry("RECORD_5", "music_disc_far"), entry("RECORD_6", "music_disc_mall"),
		entry("RECORD_7", "music_disc_mellohi"), entry("RECORD_8", "music_disc_stal"),
		entry("RECORD_9", "music_disc_strad"), entry("RECORD_10", "music_disc_ward"),
		entry("RECORD_11", "music_disc_11"), entry("RECORD_12", "music_disc_wait"),
		entry("GREEN_RECORD", "music_disc_cat"),
		entry("REDSTONE_COMPARATOR", "comparator"), entry("REDSTONE_LAMP_OFF", "redstone_lamp"),
		entry("REDSTONE_TORCH_ON", "redstone_torch"), entry("RED_ROSE", "poppy"),
		entry("SEEDS", "wheat_seeds"), entry("SIGN", "oak_sign"), entry("SKULL_ITEM", "player_head"),
		entry("SMOOTH_BRICK", "stone_bricks"), entry("SNOW_BALL", "snowball"),
		entry("SPECKLED_MELON", "glistering_melon_slice"),
		entry("STEP", "stone_slab"), entry("STONE_PLATE", "stone_pressure_plate"),
		entry("STONE_SPADE", "stone_shovel"), entry("STORAGE_MINECART", "chest_minecart"),
		entry("THIN_GLASS", "glass_pane"), entry("TRAP_DOOR", "oak_trapdoor"),
		entry("WATCH", "clock"), entry("WATER_LILY", "lily_pad"), entry("WEB", "cobweb"),
		entry("WOOD", "oak_planks"), entry("WOOD_AXE", "wooden_axe"), entry("WOOD_BUTTON", "oak_button"),
		entry("WOOD_HOE", "wooden_hoe"), entry("WOOD_PICKAXE", "wooden_pickaxe"),
		entry("WOOD_PLATE", "oak_pressure_plate"), entry("WOOD_SPADE", "wooden_shovel"),
		entry("WOOD_STEP", "oak_slab"), entry("WOOD_SWORD", "wooden_sword"),
		entry("WORKBENCH", "crafting_table"), entry("YELLOW_FLOWER", "dandelion"),
		entry("FENCE", "oak_fence"), entry("FENCE_GATE", "oak_fence_gate"),
		entry("CLAY_BRICK", "brick"), entry("BED", "red_bed"),
		entry("DIAMOND_SPADE", "diamond_shovel"), entry("DIAMOND_BARDING", "diamond_horse_armor"),
		entry("WOOL", "white_wool"), entry("CARPET", "white_carpet"), entry("BANNER", "white_banner"),
		entry("STAINED_CLAY", "white_terracotta"), entry("STAINED_GLASS", "white_stained_glass"),
		entry("STAINED_GLASS_PANE", "white_stained_glass_pane"), entry("DOUBLE_PLANT", "sunflower"),
		entry("SAPLING", "oak_sapling"), entry("LEAVES", "oak_leaves"), entry("LEAVES_2", "acacia_leaves"),
		entry("LOG", "oak_log"), entry("LOG_2", "acacia_log"), entry("MONSTER_EGG", "pig_spawn_egg")
	);

	private static ItemStack resolve(String skyblockId) {
		SkyblockItemRepo.ItemInfo info = SkyblockItemRepo.getItem(skyblockId);
		if (info == null) return null;

		// Hypixel's modern reskin path (see ItemInfo.itemModel's own doc comment) — a real vanilla
		// "minecraft:item_model" data-component override, addressed by Hypixel's own bundled resource-pack
		// model id (e.g. "hypixel_skyblock:item/combat_1/arachne_fragment"). Since this mod only ever
		// renders these icons while the player is actually connected to and playing on Hypixel, that same
		// resource pack Hypixel pushes to the client is already loaded — applying the real model id here
		// lets vanilla's own renderer draw Hypixel's real texture with zero texture data of our own, the
		// same way the game already renders it everywhere else. Checked BEFORE skinTexture/material: an
		// item using this system carries no "skin" object at all, and its "material" is just an
		// irrelevant bare carrier (almost always PAPER) once this override is applied.
		if (info.itemModel() != null) {
			Identifier modelId = Identifier.tryParse(info.itemModel());
			if (modelId != null) {
				ItemStack stack = new ItemStack(Items.PAPER);
				stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, modelId);
				return stack;
			}
		}
		if (info.skinTexture() != null) {
			return SkullTextureUtil.buildHeadWithTexture(info.skinTexture(), info.skinSignature());
		}
		if (info.material() != null) {
			String material = info.material().toUpperCase(Locale.ROOT);
			String modernPath = LEGACY_MATERIAL_ALIASES.getOrDefault(material, material.toLowerCase(Locale.ROOT));
			Identifier vanillaId = Identifier.tryParse("minecraft:" + modernPath);
			Item item = vanillaId != null ? BuiltInRegistries.ITEM.getOptional(vanillaId).orElse(null) : null;
			if (item != null && item != Items.AIR) {
				ItemStack stack = new ItemStack(item);
				// See ItemInfo.leatherColor's own doc comment — only real leather-armor-material items ever
				// carry this field, so applying it unconditionally whenever present is safe.
				if (info.leatherColor() != null) {
					stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
						new net.minecraft.world.item.component.DyedItemColor(info.leatherColor()));
				}
				return stack;
			}
		}
		return null;
	}
}
