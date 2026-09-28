package com.cokelord.skyblocksimplified.pv;

import com.cokelord.skyblocksimplified.api.AuctionApi;
import com.cokelord.skyblocksimplified.api.BazaarApi;
import com.cokelord.skyblocksimplified.api.PetAuctionApi;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Networth, ported from skyblock-pv's NetworthCalculator/NetworthCategory and the item valuation it delegates
 * to (SkyBlockAPI's ItemValueSource calculators). Same rule set: an item is worth its base price plus the
 * full price of everything applied to it — recombobulator, reforge stone, enchantment books, hot/fuming potato
 * books, Art of War/Peace, Jalapeno, Wither scrolls, stars (essence + master stars, from Hypixel's own
 * upgrade_costs), gemstones and their slot unlock costs, drill/rod parts, runes, dyes, helmet skins,
 * enrichments, Polarvoid/Wet books, Silex beyond Efficiency 5, Divan coating, overclockers, boosters.
 *
 * <p>Pricing follows SkyBlockAPI's {@code Pricing.getPrice}: Bazaar sell price first, then the auction price
 * (this mod's AuctionApi: median of recent BIN sales, else lowest live BIN). Pets use PetAuctionApi's lowest
 * BIN matched by XP, plus held item and skin.
 *
 * <p>Soulbound split (per user request): an item/pet counts as soulbound when its lore says "Soulbound" (both
 * "* Soulbound *" and "* Co-op Soulbound *") or pets_data marks it petSoulbound. Unsoulbound = total - soulbound.
 */
public final class PvNetworth {
	private PvNetworth() {}

	public record Result(double total, double soulbound, Map<String, Double> categories, boolean pricesReady) {
		public double unsoulbound() { return total - soulbound; }
	}

	private static final class Acc {
		double total, soulbound;
		final Map<String, Double> categories = new LinkedHashMap<>();

		void add(String category, double value, boolean isSoulbound) {
			if (value <= 0) return;
			total += value;
			if (isSoulbound) soulbound += value;
			categories.merge(category, value, Double::sum);
		}
	}

	public static Result calculate(PvProfile p) {
		Acc acc = new Acc();
		acc.add("Purse", p.purse(), false);
		acc.add("Bank", p.bank() + p.personalBank(), false);
		items(acc, "Inventory", p.inventory());
		items(acc, "Armor", p.armor());
		items(acc, "Equipment", p.equipment());
		// Loadout sets (the wardrobe). The currently equipped set is already counted as Armor/Equipment.
		for (Map.Entry<Integer, List<ItemStack>> set : p.armorSets().entrySet()) {
			if (set.getKey() != p.equippedSet("armor")) items(acc, "Wardrobe", set.getValue());
		}
		for (Map.Entry<Integer, List<ItemStack>> set : p.equipmentSets().entrySet()) {
			if (set.getKey() != p.equippedSet("equipment")) items(acc, "Wardrobe", set.getValue());
		}
		items(acc, "Ender Chest", p.enderChest());
		for (List<ItemStack> backpack : p.backpacks().values()) items(acc, "Backpacks", backpack);
		items(acc, "Accessories", p.talismans());
		items(acc, "Personal Vault", p.vault());
		items(acc, "Fishing Bag", p.fishingBag());
		items(acc, "Quiver", p.quiver());
		items(acc, "Potion Bag", p.potionBag());
		for (Map.Entry<String, Double> sack : p.sacks().entrySet()) {
			acc.add("Sacks", price(sack.getKey()) * sack.getValue(), false);
		}
		for (JsonElement el : PvProfile.arr(PvProfile.obj(p.member, "pets_data"), "pets")) {
			if (!(el instanceof JsonObject pet)) continue;
			String type = PvProfile.str(pet, "type", null);
			String tier = PvProfile.str(pet, "tier", null);
			double xp = PvProfile.num(pet, "exp", 0);
			PetAuctionApi.PriceMatch match = PetAuctionApi.findClosest(type, tier, xp);
			double value = (match != null ? match.price() : 0)
				+ price(PvProfile.str(pet, "heldItem", null))
				+ (pet.has("skin") && !pet.get("skin").isJsonNull() ? price("PET_SKIN_" + pet.get("skin").getAsString()) : 0);
			boolean soulbound = pet.has("petSoulbound") && pet.get("petSoulbound").getAsBoolean();
			acc.add("Pets", value, soulbound);
		}
		boolean ready = BazaarApi.isLoaded() && AuctionApi.isLoaded();
		return new Result(acc.total, acc.soulbound, acc.categories, ready);
	}

	private static void items(Acc acc, String category, List<ItemStack> stacks) {
		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) continue;
			acc.add(category, itemValue(stack), isSoulbound(stack));
		}
	}

	public static boolean isSoulbound(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return false;
		for (Component line : lore.lines()) {
			if (line.getString().contains("Soulbound")) return true;
		}
		return false;
	}

	/** Bazaar sell, else auction price, else 0. */
	public static double price(String id) {
		if (id == null || id.isEmpty()) return 0;
		BazaarApi.BazaarPrice bz = BazaarApi.getPrice(id);
		if (bz != null && bz.sellPrice() > 0) return bz.sellPrice();
		AuctionApi.AuctionPrice ah = AuctionApi.getPrice(id);
		return ah != null ? ah.medianBinPrice() : 0;
	}

	public static double itemValue(ItemStack stack) {
		CompoundTag extra = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		String id = extra.getStringOr("id", null);
		if (id == null) return 0;
		if ("PET".equals(id)) return petItemValue(extra) * stack.getCount();
		double v = price(id);

		if (extra.getIntOr("rarity_upgrades", 0) > 0) v += price("RECOMBOBULATOR_3000");
		String stone = PvRepo.reforgeStone(extra.getStringOr("modifier", null));
		if (stone != null) v += price(stone);
		CompoundTag enchants = extra.getCompoundOrEmpty("enchantments");
		for (String ench : enchants.keySet()) {
			v += price("ENCHANTMENT_" + ench.toUpperCase(java.util.Locale.ROOT) + "_" + enchants.getIntOr(ench, 0));
		}
		int potato = extra.getIntOr("hot_potato_count", 0);
		v += Math.min(10, potato) * price("HOT_POTATO_BOOK") + Math.max(0, potato - 10) * price("FUMING_POTATO_BOOK");
		if (extra.getIntOr("art_of_war_count", 0) > 0) v += price("THE_ART_OF_WAR");
		if (extra.getIntOr("artOfPeaceApplied", 0) > 0) v += price("THE_ART_OF_PEACE");
		if (extra.getIntOr("jalapeno_count", 0) > 0) v += price("JALAPENO_BOOK");
		if (extra.contains("stats_book")) v += price("BOOK_OF_STATS");
		v += Math.min(5, extra.getIntOr("wet_book_count", 0)) * price("WET_BOOK");
		v += Math.min(5, extra.getIntOr("polarvoid", 0)) * price("POLARVOID_BOOK");
		v += extra.getIntOr("divan_powder_coating", 0) * price("DIVAN_POWDER_COATING");
		v += Math.min(10, extra.getIntOr("levelable_overclocks", 0)) * price("OVERCLOCKER_3000");
		for (String key : new String[]{"power_ability_scroll", "drill_part_fuel_tank", "drill_part_engine", "drill_part_upgrade_module", "dye_item", "skin"}) {
			v += price(extra.getStringOr(key, null));
		}
		String enrichment = extra.getStringOr("talisman_enrichment", null);
		if (enrichment != null) v += price("TALISMAN_ENRICHMENT_" + enrichment.toUpperCase(java.util.Locale.ROOT));
		for (String part : new String[]{"hook", "line", "sinker"}) {
			v += price(extra.getCompoundOrEmpty(part).getStringOr("part", null));
		}
		CompoundTag runes = extra.getCompoundOrEmpty("runes");
		for (String rune : runes.keySet()) v += price(rune.toUpperCase(java.util.Locale.ROOT) + "_RUNE;" + runes.getIntOr(rune, 1));
		int efficiency = enchants.getIntOr("efficiency", 0);
		if (efficiency > 5 && !id.startsWith("STONK_PICKAXE") && !id.startsWith("PROMISING_")) v += Math.min(5, efficiency - 5) * price("SIL_EX");
		CompoundTag boosters = extra.getCompoundOrEmpty("booster_tiers");
		for (String type : boosters.keySet()) {
			int max = boosters.getIntOr(type, 0);
			for (int tier = 1; tier <= max; tier++) v += price(tier == 1 ? type + "_BOOSTER" : type + "_BOOSTER_" + RARITY_NAMES[Math.min(tier - 1, RARITY_NAMES.length - 1)]);
		}
		v += scrolls(extra);
		v += stars(id, extra);
		v += gemstones(id, extra);
		return v * stack.getCount();
	}

	private static final String[] RARITY_NAMES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE"};
	private static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};

	private static double scrolls(CompoundTag extra) {
		ListTag list = extra.getListOrEmpty("ability_scroll");
		double v = 0;
		for (int i = 0; i < list.size(); i++) {
			String scroll = list.getStringOr(i, "");
			if (scroll.equals("ULTIMATE_WITHER_SCROLL")) {
				v += price("WITHER_SHIELD_SCROLL") + price("SHADOW_WARP_SCROLL") + price("IMPLOSION_SCROLL");
			} else {
				v += price(scroll);
			}
		}
		return v;
	}

	private static double stars(String id, CompoundTag extra) {
		int stars = extra.getIntOr("upgrade_level", extra.getIntOr("dungeon_item_level", 0));
		if (stars <= 0) return 0;
		JsonObject data = SkyblockItemRepo.getUpgradeData(id);
		double v = 0;
		if (data != null && data.get("dungeon_item_conversion_cost") instanceof JsonObject conversion) v += cost(conversion);
		JsonArray costs = data != null && data.get("upgrade_costs") instanceof JsonArray a ? a : new JsonArray();
		int essenceStars = Math.min(stars, costs.size());
		for (int i = 0; i < essenceStars; i++) {
			if (costs.get(i) instanceof JsonArray star) for (JsonElement c : star) if (c instanceof JsonObject o) v += cost(o);
		}
		// Dungeon items: stars beyond the 5 essence stars are master stars.
		if (extra.contains("dungeon_item_level") || costs.size() == 5) {
			for (int i = 0; i < Math.min(5, Math.max(0, stars - 5)); i++) v += price(MASTER_STARS[i]);
		}
		return v;
	}

	private static double gemstones(String id, CompoundTag extra) {
		CompoundTag gems = extra.getCompoundOrEmpty("gems");
		if (gems.isEmpty()) return 0;
		JsonObject data = SkyblockItemRepo.getUpgradeData(id);
		JsonArray slots = data != null && data.get("gemstone_slots") instanceof JsonArray a ? a : new JsonArray();
		java.util.Set<Integer> usedSlotDefs = new java.util.HashSet<>();
		double v = 0;
		for (String key : gems.keySet()) {
			if (key.endsWith("_gem") || key.equals("unlocked_slots") || key.equals("formatted_slots")) continue;
			String slotType = key.contains("_") ? key.substring(0, key.lastIndexOf('_')) : key;
			String quality = gems.getCompound(key).map(c -> c.getStringOr("quality", "ROUGH")).orElseGet(() -> gems.getStringOr(key, "ROUGH"));
			String gem = gems.getStringOr(key + "_gem", slotType);
			v += price(quality + "_" + gem + "_GEM");
			for (int i = 0; i < slots.size(); i++) {
				if (usedSlotDefs.contains(i) || !(slots.get(i) instanceof JsonObject slot)) continue;
				if (!slotType.equalsIgnoreCase(PvProfile.str(slot, "slot_type", ""))) continue;
				usedSlotDefs.add(i);
				for (JsonElement c : PvProfile.arr(slot, "costs")) if (c instanceof JsonObject o) v += cost(o);
				break;
			}
		}
		return v;
	}

	private static double cost(JsonObject c) {
		return switch (PvProfile.str(c, "type", "")) {
			case "COINS" -> PvProfile.num(c, "coins", 0);
			case "ESSENCE" -> PvProfile.num(c, "amount", 0) * price("ESSENCE_" + PvProfile.str(c, "essence_type", ""));
			case "ITEM" -> PvProfile.num(c, "amount", 1) * price(PvProfile.str(c, "item_id", null));
			default -> 0;
		};
	}

	private static double petItemValue(CompoundTag extra) {
		try {
			JsonObject pet = com.google.gson.JsonParser.parseString(extra.getStringOr("petInfo", "{}")).getAsJsonObject();
			PetAuctionApi.PriceMatch match = PetAuctionApi.findClosest(PvProfile.str(pet, "type", null), PvProfile.str(pet, "tier", null), PvProfile.num(pet, "exp", 0));
			return (match != null ? match.price() : 0) + price(PvProfile.str(pet, "heldItem", null)) + price(pet.has("skin") && !pet.get("skin").isJsonNull() ? "PET_SKIN_" + pet.get("skin").getAsString() : null);
		} catch (Exception e) {
			return 0;
		}
	}
}
