package com.cokelord.skyblocksimplified.pv;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/** Minimal 1.8 item id/name -> modern item fallback for the Player Viewer. Only used when neither the
 *  texture map nor Hypixel's item "material" resolves an item (mostly vanilla items with no SkyBlock id).
 *  Covers the common cases; anything else falls back to paper. */
final class LegacyItemIds {
	private LegacyItemIds() {}

	private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
		"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
	private static final String[] DYES = {"ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye", "cyan_dye",
		"light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal"};
	private static final String[] SKULLS = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head"};

	private static final Map<Integer, String> BY_ID = new HashMap<>();
	private static final Map<String, String> BY_NAME = new HashMap<>();

	static {
		Object[][] ids = {
			{1, "stone"}, {2, "grass_block"}, {3, "dirt"}, {4, "cobblestone"}, {12, "sand"}, {13, "gravel"}, {14, "gold_ore"},
			{15, "iron_ore"}, {16, "coal_ore"}, {17, "oak_log"}, {20, "glass"}, {41, "gold_block"}, {42, "iron_block"},
			{46, "tnt"}, {49, "obsidian"}, {54, "chest"}, {57, "diamond_block"}, {58, "crafting_table"}, {61, "furnace"},
			{81, "cactus"}, {86, "carved_pumpkin"}, {87, "netherrack"}, {89, "glowstone"}, {101, "iron_bars"}, {103, "melon"},
			{121, "end_stone"}, {130, "ender_chest"}, {133, "emerald_block"}, {137, "command_block"}, {138, "beacon"},
			{144, "player_head"}, {145, "anvil"}, {154, "hopper"}, {166, "barrier"}, {256, "iron_shovel"}, {257, "iron_pickaxe"},
			{258, "iron_axe"}, {259, "flint_and_steel"}, {260, "apple"}, {261, "bow"}, {262, "arrow"}, {263, "coal"},
			{264, "diamond"}, {265, "iron_ingot"}, {266, "gold_ingot"}, {267, "iron_sword"}, {268, "wooden_sword"},
			{272, "stone_sword"}, {276, "diamond_sword"}, {277, "diamond_shovel"}, {278, "diamond_pickaxe"}, {279, "diamond_axe"},
			{280, "stick"}, {283, "golden_sword"}, {287, "string"}, {288, "feather"}, {289, "gunpowder"}, {293, "diamond_hoe"},
			{296, "wheat"}, {298, "leather_helmet"}, {299, "leather_chestplate"}, {300, "leather_leggings"}, {301, "leather_boots"},
			{302, "chainmail_helmet"}, {303, "chainmail_chestplate"}, {304, "chainmail_leggings"}, {305, "chainmail_boots"},
			{306, "iron_helmet"}, {307, "iron_chestplate"}, {308, "iron_leggings"}, {309, "iron_boots"}, {310, "diamond_helmet"},
			{311, "diamond_chestplate"}, {312, "diamond_leggings"}, {313, "diamond_boots"}, {314, "golden_helmet"},
			{315, "golden_chestplate"}, {316, "golden_leggings"}, {317, "golden_boots"}, {318, "flint"}, {331, "redstone"},
			{332, "snowball"}, {339, "paper"}, {340, "book"}, {341, "slime_ball"}, {344, "egg"}, {345, "compass"},
			{346, "fishing_rod"}, {347, "clock"}, {348, "glowstone_dust"}, {352, "bone"}, {353, "sugar"}, {358, "filled_map"},
			{359, "shears"}, {368, "ender_pearl"}, {369, "blaze_rod"}, {370, "ghast_tear"}, {371, "gold_nugget"}, {372, "nether_wart"},
			{373, "potion"}, {375, "spider_eye"}, {377, "blaze_powder"}, {378, "magma_cream"}, {381, "ender_eye"},
			{384, "experience_bottle"}, {385, "fire_charge"}, {388, "emerald"}, {389, "item_frame"}, {391, "carrot"},
			{392, "potato"}, {395, "map"}, {396, "golden_carrot"}, {399, "nether_star"}, {403, "enchanted_book"},
			{406, "quartz"}, {409, "prismarine_shard"}, {410, "prismarine_crystals"}, {417, "iron_horse_armor"},
			{421, "name_tag"}, {425, "white_banner"}, {432, "chorus_fruit"}, {2256, "music_disc_13"},
		};
		for (Object[] e : ids) BY_ID.put((Integer) e[0], (String) e[1]);
		String[][] names = {
			{"skull_item", "player_head"}, {"ink_sack", "ink_sac"}, {"wood_sword", "wooden_sword"}, {"gold_sword", "golden_sword"},
			{"wood_axe", "wooden_axe"}, {"gold_axe", "golden_axe"}, {"wood_pickaxe", "wooden_pickaxe"}, {"gold_pickaxe", "golden_pickaxe"},
			{"wood_hoe", "wooden_hoe"}, {"gold_hoe", "golden_hoe"}, {"wood_spade", "wooden_shovel"}, {"iron_spade", "iron_shovel"},
			{"diamond_spade", "diamond_shovel"}, {"gold_spade", "golden_shovel"}, {"gold_helmet", "golden_helmet"},
			{"gold_chestplate", "golden_chestplate"}, {"gold_leggings", "golden_leggings"}, {"gold_boots", "golden_boots"},
			{"watch", "clock"}, {"sulphur", "gunpowder"}, {"raw_fish", "cod"}, {"cooked_fish", "cooked_cod"}, {"nether_stalk", "nether_wart"},
			{"carrot_item", "carrot"}, {"potato_item", "potato"}, {"exp_bottle", "experience_bottle"}, {"firework", "firework_rocket"},
			{"fireball", "fire_charge"}, {"eye_of_ender", "ender_eye"}, {"empty_map", "map"}, {"book_and_quill", "writable_book"},
			{"red_rose", "poppy"}, {"yellow_flower", "dandelion"}, {"log", "oak_log"}, {"log_2", "acacia_log"}, {"wood", "oak_planks"},
			{"leaves", "oak_leaves"}, {"sapling", "oak_sapling"}, {"ender_stone", "end_stone"}, {"mycel", "mycelium"},
			{"huge_mushroom_1", "brown_mushroom_block"}, {"huge_mushroom_2", "red_mushroom_block"}, {"water_lily", "lily_pad"},
			{"melon_block", "melon"}, {"clay_ball", "clay_ball"}, {"snow_ball", "snowball"}, {"double_plant", "sunflower"},
			{"prismarine_crystals", "prismarine_crystals"}, {"banner", "white_banner"}, {"thin_glass", "glass_pane"},
			{"iron_fence", "iron_bars"}, {"command", "command_block"}, {"monster_egg", "infested_stone"}, {"piston_base", "piston"},
			{"enchantment_table", "enchanting_table"}, {"workbench", "crafting_table"}, {"quartz_ore", "nether_quartz_ore"},
		};
		for (String[] n : names) BY_NAME.put(n[0], n[1]);
	}

	static Item get(int id, int damage) {
		if (id == 35) return item(COLORS[Math.floorMod(damage, 16)] + "_wool");
		if (id == 95) return item(COLORS[Math.floorMod(damage, 16)] + "_stained_glass");
		if (id == 160) return item(COLORS[Math.floorMod(damage, 16)] + "_stained_glass_pane");
		if (id == 159) return item(COLORS[Math.floorMod(damage, 16)] + "_terracotta");
		if (id == 351) return item(DYES[Math.floorMod(damage, 16)]);
		if (id == 397) return item(SKULLS[Math.min(Math.max(damage, 0), SKULLS.length - 1)]);
		String name = BY_ID.get(id);
		return name != null ? item(name) : null;
	}

	static Item byName(String legacyName, int damage) {
		switch (legacyName) {
			case "wool": return item(COLORS[Math.floorMod(damage, 16)] + "_wool");
			case "stained_glass": return item(COLORS[Math.floorMod(damage, 16)] + "_stained_glass");
			case "stained_glass_pane": return item(COLORS[Math.floorMod(damage, 16)] + "_stained_glass_pane");
			case "stained_clay": return item(COLORS[Math.floorMod(damage, 16)] + "_terracotta");
			case "ink_sack": return item(DYES[Math.floorMod(damage, 16)]);
			case "skull_item": return damage == 3 || damage == 0 ? Items.PLAYER_HEAD : item(SKULLS[Math.min(Math.max(damage, 0), SKULLS.length - 1)]);
			default: break;
		}
		String name = BY_NAME.get(legacyName);
		return name != null ? item(name) : null;
	}

	private static Item item(String path) {
		Identifier id = Identifier.withDefaultNamespace(path);
		return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : null;
	}
}
