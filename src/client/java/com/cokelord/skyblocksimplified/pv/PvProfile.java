package com.cokelord.skyblocksimplified.pv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One SkyBlock profile as seen by one member, with safe JSON accessors and lazily decoded inventories. */
public final class PvProfile {
	public final JsonObject profile;
	public final JsonObject member;
	public final String uuid;

	private List<ItemStack> inventory, armor, equipment, enderChest, vault, talismans, fishingBag, quiver, potionBag;
	private Map<Integer, List<ItemStack>> armorSets, equipmentSets;
	private Map<Integer, List<ItemStack>> backpacks;
	private Map<Integer, ItemStack> backpackIcons;

	public PvProfile(JsonObject profile, String uuid) {
		this.profile = profile;
		this.uuid = uuid;
		JsonObject members = obj(profile, "members");
		this.member = obj(members, uuid);
	}

	/** The profile's selected-by-player one first, as Hypixel marks it; null when the player has none. */
	public static List<PvProfile> all(JsonArray profiles, String uuid) {
		List<PvProfile> out = new ArrayList<>();
		for (JsonElement el : profiles) {
			if (el instanceof JsonObject p && obj(p, "members").has(uuid)) out.add(new PvProfile(p, uuid));
		}
		out.sort((a, b) -> Boolean.compare(b.isSelected(), a.isSelected()));
		return out;
	}

	public String name() { return str(profile, "cute_name", "Unknown"); }
	public String id() { return str(profile, "profile_id", ""); }
	public boolean isSelected() { return profile.has("selected") && profile.get("selected").getAsBoolean(); }
	public String gameMode() { return str(profile, "game_mode", "normal"); }
	public int memberCount() { return obj(profile, "members").size(); }

	public String modeLabel() {
		return switch (gameMode()) {
			case "ironman" -> "Ironman";
			case "island" -> "Stranded";
			case "bingo" -> "Bingo";
			default -> "";
		};
	}

	// ---- generic JSON helpers (all null-safe, never throw on a missing/odd-shaped path) ----

	public static JsonObject obj(JsonObject parent, String key) {
		return parent != null && parent.get(key) instanceof JsonObject o ? o : new JsonObject();
	}

	public static JsonObject path(JsonObject root, String... keys) {
		JsonObject cur = root;
		for (String k : keys) cur = obj(cur, k);
		return cur;
	}

	public static double num(JsonObject parent, String key, double fallback) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) return fallback;
		try { return parent.get(key).getAsDouble(); } catch (Exception e) { return fallback; }
	}

	public static String str(JsonObject parent, String key, String fallback) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) return fallback;
		return parent.get(key).getAsString();
	}

	public static JsonArray arr(JsonObject parent, String key) {
		return parent != null && parent.get(key) instanceof JsonArray a ? a : new JsonArray();
	}

	/** Numeric entries of an object, sorted by value descending. */
	public static Map<String, Double> numbers(JsonObject parent) {
		Map<String, Double> out = new LinkedHashMap<>();
		if (parent == null) return out;
		parent.entrySet().stream()
			.filter(e -> e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber())
			.sorted((a, b) -> Double.compare(b.getValue().getAsDouble(), a.getValue().getAsDouble()))
			.forEach(e -> out.put(e.getKey(), e.getValue().getAsDouble()));
		return out;
	}

	// ---- common values ----

	public double skillXp(String skill) { return num(path(member, "player_data", "experience"), "SKILL_" + skill.toUpperCase(), 0); }
	public double purse() { return num(obj(member, "currencies"), "coin_purse", 0); }
	public double bank() { return num(obj(profile, "banking"), "balance", 0); }
	public double personalBank() { return num(obj(member, "profile"), "bank_account", 0); }
	public double sbXp() { return num(obj(member, "leveling"), "experience", 0); }
	public long firstJoin() { return (long) num(obj(member, "profile"), "first_join", 0); }

	/** Skill level with cap adjustments: Jacob's farming perk, taming pet sacrifices, and Hypixel's own
	 *  per-skill "extra_level_cap" field (e.g. SKILL_FORAGING_extra_level_cap). */
	public PvRepo.Level skill(String skill) {
		String lower = skill.toLowerCase();
		double xp = skillXp(skill);
		if (lower.equals("runecrafting")) return PvRepo.level(xp, PvRepo.table("runecrafting_xp"), PvRepo.cap("runecrafting", 25));
		if (lower.equals("social")) return PvRepo.level(xp, PvRepo.table("social"), PvRepo.cap("social", 25));
		int cap = PvRepo.cap(lower, 50);
		if (lower.equals("farming")) cap += (int) num(path(member, "jacobs_contest", "perks"), "farming_level_cap", 0);
		if (lower.equals("taming")) cap += Math.min(10, arr(path(member, "pets_data", "pet_care"), "pet_types_sacrificed").size());
		cap += (int) num(path(member, "player_data", "experience"), "SKILL_" + skill.toUpperCase() + "_extra_level_cap", 0);
		return PvRepo.level(xp, PvRepo.table("leveling_xp"), cap);
	}

	public static final String[] SKILLS = {"farming", "mining", "combat", "foraging", "fishing", "enchanting", "alchemy", "taming", "carpentry"};

	public double skillAverage() {
		double sum = 0;
		for (String s : SKILLS) sum += skill(s).exact();
		return sum / SKILLS.length;
	}

	// ---- inventories ----

	private JsonObject inv() { return obj(member, "inventory"); }
	private JsonObject bag(String key) { return obj(obj(inv(), "bag_contents"), key); }

	public synchronized List<ItemStack> inventory() { return inventory != null ? inventory : (inventory = PvItems.decode(obj(inv(), "inv_contents"))); }
	public synchronized List<ItemStack> armor() { return armor != null ? armor : (armor = PvItems.decode(obj(inv(), "inv_armor"))); }
	public synchronized List<ItemStack> equipment() { return equipment != null ? equipment : (equipment = PvItems.decode(obj(inv(), "equipment_contents"))); }
	public synchronized List<ItemStack> enderChest() { return enderChest != null ? enderChest : (enderChest = PvItems.decode(obj(inv(), "ender_chest_contents"))); }
	public synchronized List<ItemStack> vault() { return vault != null ? vault : (vault = PvItems.decode(obj(inv(), "personal_vault_contents"))); }
	public synchronized List<ItemStack> talismans() { return talismans != null ? talismans : (talismans = PvItems.decode(bag("talisman_bag"))); }
	public synchronized List<ItemStack> fishingBag() { return fishingBag != null ? fishingBag : (fishingBag = PvItems.decode(bag("fishing_bag"))); }
	public synchronized List<ItemStack> quiver() { return quiver != null ? quiver : (quiver = PvItems.decode(bag("quiver"))); }
	public synchronized List<ItemStack> potionBag() { return potionBag != null ? potionBag : (potionBag = PvItems.decode(bag("potion_bag"))); }

	// Hypixel's wardrobe/loadout system: numbered armor sets (HELMET..BOOTS blobs) and equipment sets
	// (EQUIPMENT_SLOT_1..4), plus named loadouts pointing at one of each.
	private static final String[] ARMOR_KEYS = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
	private static final String[] EQUIPMENT_KEYS = {"EQUIPMENT_SLOT_1", "EQUIPMENT_SLOT_2", "EQUIPMENT_SLOT_3", "EQUIPMENT_SLOT_4"};

	public synchronized Map<Integer, List<ItemStack>> armorSets() { return armorSets != null ? armorSets : (armorSets = sets("armor", ARMOR_KEYS)); }
	public synchronized Map<Integer, List<ItemStack>> equipmentSets() { return equipmentSets != null ? equipmentSets : (equipmentSets = sets("equipment", EQUIPMENT_KEYS)); }
	public int equippedSet(String kind) { return (int) num(path(member, "loadout", kind), "equipped_set", -1); }
	public JsonObject loadouts() { return path(member, "loadout", "loadouts"); }

	private Map<Integer, List<ItemStack>> sets(String kind, String[] keys) {
		Map<Integer, List<ItemStack>> out = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : path(member, "loadout", kind).entrySet()) {
			if (!(e.getValue() instanceof JsonObject set)) continue;
			List<ItemStack> items = new ArrayList<>(keys.length);
			boolean any = false;
			for (String key : keys) {
				List<ItemStack> decoded = set.get(key) instanceof JsonObject blob ? PvItems.decode(blob) : List.of();
				ItemStack stack = decoded.isEmpty() ? ItemStack.EMPTY : decoded.get(0);
				any |= !stack.isEmpty();
				items.add(stack);
			}
			try { if (any) out.put(Integer.parseInt(e.getKey()), items); } catch (NumberFormatException ignored) {}
		}
		return out;
	}

	public synchronized Map<Integer, List<ItemStack>> backpacks() {
		if (backpacks != null) return backpacks;
		Map<Integer, List<ItemStack>> out = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : obj(inv(), "backpack_contents").entrySet()) {
			if (e.getValue() instanceof JsonObject blob) {
				try { out.put(Integer.parseInt(e.getKey()), PvItems.decode(blob)); } catch (NumberFormatException ignored) {}
			}
		}
		return backpacks = out;
	}

	public synchronized Map<Integer, ItemStack> backpackIcons() {
		if (backpackIcons != null) return backpackIcons;
		Map<Integer, ItemStack> out = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : obj(inv(), "backpack_icons").entrySet()) {
			if (e.getValue() instanceof JsonObject blob) {
				List<ItemStack> icon = PvItems.decode(blob);
				try { if (!icon.isEmpty()) out.put(Integer.parseInt(e.getKey()), icon.get(0)); } catch (NumberFormatException ignored) {}
			}
		}
		return backpackIcons = out;
	}

	public Map<String, Double> sacks() { return numbers(obj(inv(), "sacks_counts")); }

	public JsonObject riftInventory() { return path(member, "rift", "inventory"); }

	/** Active pet from pets_data, or null. */
	public JsonObject activePet() {
		for (JsonElement el : arr(obj(member, "pets_data"), "pets")) {
			if (el instanceof JsonObject pet && pet.has("active") && pet.get("active").getAsBoolean()) return pet;
		}
		return null;
	}
}
