package com.cokelord.skyblocksimplified.pv;

import com.cokelord.skyblocksimplified.pv.PvUi.Bar;
import com.cokelord.skyblocksimplified.pv.PvUi.Bars;
import com.cokelord.skyblocksimplified.pv.PvUi.Block;
import com.cokelord.skyblocksimplified.pv.PvUi.Gap;
import com.cokelord.skyblocksimplified.pv.PvUi.Header;
import com.cokelord.skyblocksimplified.pv.PvUi.Items;
import com.cokelord.skyblocksimplified.pv.PvUi.Note;
import com.cokelord.skyblocksimplified.pv.PvUi.Stat;
import com.cokelord.skyblocksimplified.pv.PvUi.Stats;
import com.cokelord.skyblocksimplified.pv.PvUi.Table;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static com.cokelord.skyblocksimplified.pv.PvProfile.arr;
import static com.cokelord.skyblocksimplified.pv.PvProfile.num;
import static com.cokelord.skyblocksimplified.pv.PvProfile.obj;
import static com.cokelord.skyblocksimplified.pv.PvProfile.path;
import static com.cokelord.skyblocksimplified.pv.PvProfile.str;
import static com.cokelord.skyblocksimplified.pv.PvUi.commas;
import static com.cokelord.skyblocksimplified.pv.PvUi.pretty;
import static com.cokelord.skyblocksimplified.pv.PvUi.shortNum;

/**
 * Every Player Viewer category (top row) and its subcategories (second row), and the blocks each page shows.
 * Anything that doesn't belong to a skill category lives under Misc, furthest right. Overview is drawn by the
 * screen itself (player model + summary), so its page here only holds the skill summary below that.
 */
public final class PvPages {
	private PvPages() {}

	public record Sub(String name, Function<PvProfile, List<Block>> build) {}
	public record Category(String name, List<Sub> subs) {}

	public static final List<Category> CATEGORIES = List.of(
		new Category("Overview", List.of(new Sub("Overview", PvPages::overviewSkills))),
		new Category("Combat", List.of(
			new Sub("Dungeons", PvPages::dungeons),
			new Sub("Slayers", PvPages::slayers),
			new Sub("Kuudra", PvPages::kuudra),
			new Sub("Bestiary", PvPages::bestiary),
			new Sub("Stats", PvPages::combatStats))),
		new Category("Farming", List.of(
			new Sub("Skill & Contests", PvPages::farming),
			new Sub("Garden", PvPages::garden),
			new Sub("Collections", p -> collections(p, "FARMING")))),
		new Category("Mining", List.of(
			new Sub("Skill & HOTM", PvPages::mining),
			new Sub("Crystals & Glacite", PvPages::crystals),
			new Sub("Forge", PvPages::forge),
			new Sub("Collections", p -> collections(p, "MINING")))),
		new Category("Foraging", List.of(
			new Sub("Skill & HOTF", PvPages::foraging),
			new Sub("Hunting", PvPages::hunting),
			new Sub("Collections", p -> collections(p, "FORAGING")))),
		new Category("Fishing", List.of(
			new Sub("Skill", PvPages::fishing),
			new Sub("Trophy Fish", PvPages::trophyFish),
			new Sub("Collections", p -> collections(p, "FISHING")))),
		// Per user request: Enchanting/Alchemy/Taming/Carpentry only showed a skill level + XP (already on
		// Overview) and are gone, so categories and subcategories each fit on one row. Experimentation moved
		// to Misc, Pets is its own category below.
		new Category("Inventory", List.of(
			new Sub("Inventory", PvPages::inventory),
			new Sub("Ender Chest", PvPages::enderChest),
			new Sub("Backpacks", PvPages::backpacks),
			new Sub("Loadouts", PvPages::loadouts),
			new Sub("Accessories", PvPages::accessories),
			new Sub("Vault", p -> grid("Personal Vault", p.vault(), 9)),
			new Sub("Bags", PvPages::bags),
			new Sub("Sacks", PvPages::sacks))),
		// Per user report ("The PV has no Pets section"): pets were only reachable as Taming > Pets.
		new Category("Pets", List.of(new Sub("Pets", PvPages::pets))),
		new Category("Rift", List.of(
			new Sub("Overview", PvPages::rift),
			new Sub("Inventory", PvPages::riftInventory),
			new Sub("Collections", p -> collections(p, "RIFT")))),
		new Category("Misc", List.of(
			new Sub("General", PvPages::general),
			new Sub("Currencies", PvPages::essence),
			new Sub("Other Skills", PvPages::otherSkills),
			new Sub("Chocolate", PvPages::chocolate),
			new Sub("Stats", PvPages::miscStats),
			new Sub("Minions", PvPages::minions),
			new Sub("Experiments", PvPages::experimentation)))
	);

	// ---------------------------------------------------------------------------------------------------
	// Shared helpers

	static final Map<String, String> SKILL_ICONS = Map.ofEntries(
		Map.entry("farming", "minecraft:golden_hoe"), Map.entry("mining", "minecraft:stone_pickaxe"),
		Map.entry("combat", "minecraft:stone_sword"), Map.entry("foraging", "minecraft:jungle_sapling"),
		Map.entry("fishing", "minecraft:fishing_rod"), Map.entry("enchanting", "minecraft:enchanting_table"),
		Map.entry("alchemy", "minecraft:brewing_stand"), Map.entry("taming", "minecraft:lead"),
		Map.entry("carpentry", "minecraft:crafting_table"), Map.entry("runecrafting", "minecraft:magma_cream"),
		Map.entry("social", "minecraft:emerald"), Map.entry("hunting", "minecraft:bow"));

	static Bar skillBar(PvProfile p, String skill) {
		PvRepo.Level lv = p.skill(skill);
		return levelBar(PvItems.icon(SKILL_ICONS.getOrDefault(skill, "minecraft:book")), pretty(skill), lv);
	}

	static Bar levelBar(ItemStack icon, String name, PvRepo.Level lv) {
		boolean maxed = lv.level() >= lv.cap();
		String detail = maxed ? "MAX  " + commas(lv.xp()) + " XP" : shortNum(lv.xpIntoLevel()) + " / " + shortNum(lv.xpForNext()) + " XP";
		String level = PvRepo.isLevelingLoaded() ? String.valueOf(lv.level()) : "...";
		return new Bar(icon, name, level, maxed ? 1 : lv.progress(), detail,
			PvUi.lines("§e" + name + " " + lv.level() + " §7/ " + lv.cap(), "§7Total XP: §f" + commas(lv.xp()),
				maxed ? "§6Maxed" : "§7Progress: §f" + String.format(Locale.ROOT, "%.1f%%", lv.progress() * 100)));
	}

	static List<Block> skillPage(PvProfile p, String skill) {
		List<Block> out = new ArrayList<>();
		out.add(new Header(pretty(skill)));
		out.add(new Bars(List.of(skillBar(p, skill)), 260));
		return out;
	}

	static List<Block> grid(String title, List<ItemStack> items, int cols) {
		List<Block> out = new ArrayList<>();
		out.add(new Header(title));
		if (items.isEmpty()) out.add(new Note("Nothing here, or this API setting is turned off."));
		else out.add(new Items(items, cols));
		return out;
	}

	/** kills map like {"zombie_1": 20, "zombie_5": 3} aggregated per mob family. */
	static Map<String, Double> aggregateByFamily(JsonObject counts) {
		Map<String, Double> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : counts.entrySet()) {
			if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) continue;
			String family = e.getKey().replaceAll("_\\d+$", "");
			out.merge(family, e.getValue().getAsDouble(), Double::sum);
		}
		List<Map.Entry<String, Double>> sorted = new ArrayList<>(out.entrySet());
		sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		Map<String, Double> result = new LinkedHashMap<>();
		for (Map.Entry<String, Double> e : sorted) result.put(e.getKey(), e.getValue());
		return result;
	}

	static List<String[]> topRows(Map<String, Double> values, int limit) {
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, Double> e : values.entrySet()) {
			if (rows.size() >= limit) break;
			rows.add(new String[]{pretty(e.getKey()), commas(e.getValue())});
		}
		return rows;
	}

	// ---------------------------------------------------------------------------------------------------
	// Overview (below the screen's own summary)

	static List<Block> overviewSkills(PvProfile p) {
		List<Block> out = new ArrayList<>();
		out.add(new Header("Skills  §7(average " + String.format(Locale.ROOT, "%.2f", p.skillAverage()) + ")"));
		List<Bar> bars = new ArrayList<>();
		for (String s : PvProfile.SKILLS) bars.add(skillBar(p, s));
		bars.add(skillBar(p, "hunting"));
		out.add(new Bars(bars, 175));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Combat

	static PvRepo.Level catacombs(PvProfile p) {
		double xp = num(path(p.member, "dungeons", "dungeon_types", "catacombs"), "experience", 0);
		return PvRepo.level(xp, PvRepo.table("catacombs"), PvRepo.cap("catacombs", 50));
	}

	static final String[] CLASSES = {"healer", "mage", "berserk", "archer", "tank"};
	static final Map<String, String> CLASS_ICONS = Map.of("healer", "minecraft:potion", "mage", "minecraft:blaze_rod",
		"berserk", "minecraft:iron_sword", "archer", "minecraft:bow", "tank", "minecraft:leather_chestplate");

	static List<Block> dungeons(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject d = obj(p.member, "dungeons");
		JsonObject cata = path(d, "dungeon_types", "catacombs");
		JsonObject master = path(d, "dungeon_types", "master_catacombs");
		String selected = str(d, "selected_dungeon_class", "none");
		out.add(new Header("Catacombs"));
		List<Bar> bars = new ArrayList<>();
		bars.add(levelBar(PvItems.icon("minecraft:wither_skeleton_skull"), "Catacombs", catacombs(p)));
		double classSum = 0;
		for (String c : CLASSES) {
			PvRepo.Level lv = PvRepo.level(num(path(d, "player_classes", c), "experience", 0), PvRepo.table("catacombs"), 50);
			classSum += lv.exact();
			bars.add(levelBar(PvItems.icon(CLASS_ICONS.get(c)), pretty(c) + (c.equals(selected) ? " §a(selected)" : ""), lv));
		}
		out.add(new Bars(bars));
		double totalRuns = 0;
		for (JsonObject type : new JsonObject[]{cata, master}) {
			for (Map.Entry<String, Double> e : PvProfile.numbers(obj(type, "tier_completions")).entrySet()) {
				if (!e.getKey().equals("total")) totalRuns += e.getValue();
			}
		}
		out.add(new Stats(List.of(
			new Stat("Secrets", commas(num(d, "secrets", 0))),
			new Stat("Class Average", String.format(Locale.ROOT, "%.2f", classSum / CLASSES.length)),
			new Stat("Total Runs", commas(totalRuns)),
			new Stat("Secrets / Run", totalRuns > 0 ? String.format(Locale.ROOT, "%.2f", num(d, "secrets", 0) / totalRuns) : "-"),
			new Stat("Watcher Kills", commas(num(cata, "watcher_kills", 0))),
			new Stat("Highest Floor", master.has("highest_tier_completed") ? "M" + (int) num(master, "highest_tier_completed", 0)
				: "F" + (int) num(cata, "highest_tier_completed", 0)))));
		out.add(new Gap(4));
		out.add(new Header("Floors"));
		out.add(floorTable(cata, "F", 0));
		if (!obj(master, "tier_completions").entrySet().isEmpty()) {
			out.add(new Gap(4));
			out.add(new Header("Master Mode"));
			out.add(floorTable(master, "M", 1));
		}
		return out;
	}

	static Table floorTable(JsonObject type, String prefix, int from) {
		List<String[]> rows = new ArrayList<>();
		for (int f = from; f <= 7; f++) {
			String k = String.valueOf(f);
			double runs = num(obj(type, "tier_completions"), k, 0);
			if (runs <= 0 && !obj(type, "fastest_time").has(k)) continue;
			rows.add(new String[]{f == 0 ? "Entrance" : prefix + f, commas(runs), commas(num(obj(type, "best_score"), k, 0)),
				PvUi.runTime(num(obj(type, "fastest_time_s"), k, 0)), PvUi.runTime(num(obj(type, "fastest_time_s_plus"), k, 0))});
		}
		return new Table(new String[]{"Floor", "Runs", "Best Score", "Fastest S", "Fastest S+"}, rows, 0.24f);
	}

	static final String[][] SLAYERS = {
		{"zombie", "Revenant Horror", "minecraft:rotten_flesh"}, {"spider", "Tarantula Broodfather", "minecraft:cobweb"},
		{"wolf", "Sven Packmaster", "minecraft:mutton"}, {"enderman", "Voidgloom Seraph", "minecraft:ender_pearl"},
		{"blaze", "Inferno Demonlord", "minecraft:blaze_powder"}, {"vampire", "Riftstalker Bloodfiend", "minecraft:redstone"}};

	static int slayerLevel(String boss, double xp) {
		int[] thresholds = PvRepo.slayerThresholds(boss);
		if (thresholds == null) return 0;
		int level = 0;
		for (int t : thresholds) if (xp >= t) level++;
		return level;
	}

	static List<Stat> slayerSummary(PvProfile p) {
		List<Stat> out = new ArrayList<>();
		double total = 0;
		for (String[] s : SLAYERS) {
			double xp = num(path(p.member, "slayer", "slayer_bosses", s[0]), "xp", 0);
			total += xp;
			out.add(new Stat(s[1].split(" ")[0], slayerLevel(s[0], xp) + " §7(" + shortNum(xp) + ")"));
		}
		out.add(new Stat("Total XP", shortNum(total)));
		return out;
	}

	static List<Block> slayers(PvProfile p) {
		List<Block> out = new ArrayList<>();
		out.add(new Header("Slayers"));
		List<Bar> bars = new ArrayList<>();
		List<String[]> rows = new ArrayList<>();
		for (String[] s : SLAYERS) {
			JsonObject boss = path(p.member, "slayer", "slayer_bosses", s[0]);
			double xp = num(boss, "xp", 0);
			int level = slayerLevel(s[0], xp);
			int[] t = PvRepo.slayerThresholds(s[0]);
			double prev = level > 0 && t != null ? t[level - 1] : 0;
			double next = t != null && level < t.length ? t[level] : 0;
			double progress = next > 0 ? (xp - prev) / (next - prev) : 1;
			bars.add(new Bar(PvItems.icon(s[2]), s[1], String.valueOf(level), progress,
				next > 0 ? commas(xp) + " / " + commas(next) + " XP" : "MAX  " + commas(xp) + " XP", null));
			String[] row = new String[6];
			row[0] = s[1];
			for (int tier = 0; tier < 5; tier++) row[tier + 1] = commas(num(boss, "boss_kills_tier_" + tier, 0));
			rows.add(row);
		}
		out.add(new Bars(bars));
		out.add(new Gap(4));
		out.add(new Header("Boss Kills"));
		out.add(new Table(new String[]{"Boss", "T1", "T2", "T3", "T4", "T5"}, rows, 0.3f));
		return out;
	}

	static List<Block> kuudra(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject nether = obj(p.member, "nether_island_player_data");
		JsonObject tiers = obj(nether, "kuudra_completed_tiers");
		out.add(new Header("Kuudra"));
		List<String[]> rows = new ArrayList<>();
		String[][] kuudraTiers = {{"none", "Basic"}, {"hot", "Hot"}, {"burning", "Burning"}, {"fiery", "Fiery"}, {"infernal", "Infernal"}};
		double total = 0;
		for (String[] t : kuudraTiers) {
			double runs = num(tiers, t[0], 0);
			total += runs;
			rows.add(new String[]{t[1], commas(runs), String.valueOf((int) num(tiers, "highest_wave_" + t[0], 0))});
		}
		out.add(new Table(new String[]{"Tier", "Completions", "Highest Wave"}, rows));
		out.add(new Stats(List.of(new Stat("Total Runs", commas(total)))));
		out.add(new Gap(4));
		out.add(new Header("Crimson Isle"));
		out.add(new Stats(List.of(
			new Stat("Faction", pretty(str(nether, "selected_faction", "none"))),
			new Stat("Mage Reputation", commas(num(nether, "mages_reputation", 0)), 0xFF5555FF),
			new Stat("Barbarian Reputation", commas(num(nether, "barbarians_reputation", 0)), 0xFFFF5555),
			new Stat("Matriarch Pearls", commas(num(obj(nether, "matriarch"), "pearls_collected", 0))))));
		JsonObject dojo = obj(nether, "dojo");
		if (!dojo.entrySet().isEmpty()) {
			out.add(new Gap(4));
			out.add(new Header("Dojo"));
			List<String[]> dojoRows = new ArrayList<>();
			double totalPoints = 0;
			for (String key : dojo.keySet()) {
				if (!key.startsWith("dojo_points_")) continue;
				String challenge = key.substring("dojo_points_".length());
				double points = num(dojo, key, 0);
				totalPoints += points;
				dojoRows.add(new String[]{pretty(challenge), commas(points), PvUi.runTime(num(dojo, "dojo_time_" + challenge, 0))});
			}
			out.add(new Table(new String[]{"Challenge", "Points", "Time"}, dojoRows));
			out.add(new Stats(List.of(new Stat("Total Points", commas(totalPoints)))));
		}
		return out;
	}

	static List<Block> bestiary(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject b = obj(p.member, "bestiary");
		out.add(new Header("Bestiary"));
		Map<String, Double> kills = aggregateByFamily(obj(b, "kills"));
		Map<String, Double> deaths = aggregateByFamily(obj(b, "deaths"));
		double totalKills = kills.values().stream().mapToDouble(Double::doubleValue).sum();
		out.add(new Stats(List.of(
			new Stat("Milestone", String.valueOf((int) num(obj(b, "milestone"), "last_claimed_milestone", 0))),
			new Stat("Mob Types", String.valueOf(kills.size())),
			new Stat("Total Kills", commas(totalKills)))));
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, Double> e : kills.entrySet()) {
			rows.add(new String[]{pretty(e.getKey()), commas(e.getValue()), commas(deaths.getOrDefault(e.getKey(), 0d))});
		}
		out.add(new Table(new String[]{"Mob", "Kills", "Deaths"}, rows, 0.5f));
		return out;
	}

	static List<Block> combatStats(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject stats = obj(p.member, "player_stats");
		out.add(new Header("Combat"));
		out.add(new Bars(List.of(skillBar(p, "combat")), 260));
		Map<String, Double> kills = PvProfile.numbers(obj(stats, "kills"));
		Map<String, Double> deaths = PvProfile.numbers(obj(stats, "deaths"));
		double k = kills.getOrDefault("total", 0d), d = deaths.getOrDefault("total", 0d);
		kills.remove("total");
		deaths.remove("total");
		out.add(new Stats(List.of(
			new Stat("Kills", commas(k)), new Stat("Deaths", commas(d)),
			new Stat("K/D", d > 0 ? String.format(Locale.ROOT, "%.2f", k / d) : "-"),
			new Stat("Highest Damage", commas(num(stats, "highest_damage", 0))),
			new Stat("Highest Crit Damage", commas(num(stats, "highest_critical_damage", 0))))));
		out.add(new Gap(4));
		out.add(new Header("Top Kills"));
		out.add(new Table(new String[]{"Mob", "Kills"}, topRows(kills, 20), 0.6f));
		out.add(new Gap(4));
		out.add(new Header("Top Deaths"));
		out.add(new Table(new String[]{"Cause", "Deaths"}, topRows(deaths, 15), 0.6f));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Farming

	static List<Block> farming(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject jacob = obj(p.member, "jacobs_contest");
		out.add(new Header("Farming"));
		out.add(new Bars(List.of(skillBar(p, "farming")), 260));
		JsonObject medals = obj(jacob, "medals_inv");
		JsonObject perks = obj(jacob, "perks");
		double contests = jacob.has("contest_count") ? num(jacob, "contest_count", 0) : obj(jacob, "contests").size();
		out.add(new Stats(List.of(
			new Stat("Contests", commas(contests)),
			new Stat("Gold Medals", commas(num(medals, "gold", 0)), 0xFFFFAA00),
			new Stat("Silver Medals", commas(num(medals, "silver", 0)), 0xFFAAAAAA),
			new Stat("Bronze Medals", commas(num(medals, "bronze", 0)), 0xFFCC7722),
			new Stat("Double Drops", "Level " + (int) num(perks, "double_drops", 0)),
			new Stat("Level Cap Bonus", "+" + (int) num(perks, "farming_level_cap", 0)))));
		out.add(new Gap(4));
		out.add(new Header("Best Contest Brackets"));
		JsonObject brackets = obj(jacob, "unique_brackets");
		String[][] order = {{"diamond", "§bDiamond"}, {"platinum", "§3Platinum"}, {"gold", "§6Gold"}, {"silver", "§7Silver"}, {"bronze", "§cBronze"}};
		Map<String, String> best = new LinkedHashMap<>();
		for (String[] medal : order) {
			for (JsonElement crop : arr(brackets, medal[0])) best.putIfAbsent(crop.getAsString(), medal[1]);
		}
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, String> e : best.entrySet()) rows.add(new String[]{collectionName(e.getKey()), e.getValue()});
		out.add(rows.isEmpty() ? new Note("No contests participated in.") : new Table(new String[]{"Crop", "Best Bracket"}, rows, 0.5f));
		return out;
	}

	static List<Block> garden(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject g = obj(p.member, "garden_player_data");
		out.add(new Header("Garden"));
		out.add(new Stats(List.of(
			new Stat("Copper", commas(num(g, "copper", 0)), 0xFFFF7755),
			new Stat("Larva Consumed", commas(num(g, "larva_consumed", 0))),
			new Stat("Analyzed Crops", String.valueOf(arr(g, "analyzed_greenhouse_crops").size())),
			new Stat("Discovered Crops", String.valueOf(arr(g, "discovered_greenhouse_crops").size())))));
		out.add(new Note("Garden level, plots and visitors come from a separate Hypixel endpoint that isn't loaded yet."));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Mining

	static List<Block> mining(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject core = obj(p.member, "mining_core");
		JsonObject tree = obj(p.member, "skill_tree");
		out.add(new Header("Mining"));
		PvRepo.Level hotm = PvRepo.level(num(obj(tree, "experience"), "mining", 0), PvRepo.table("HOTM"), PvRepo.cap("HOTM", 10));
		out.add(new Bars(List.of(skillBar(p, "mining"), levelBar(PvItems.icon("minecraft:iron_pickaxe"), "Heart of the Mountain", hotm))));
		out.add(new Stats(List.of(
			new Stat("Tokens Spent", String.valueOf((int) num(obj(tree, "tokens_spent"), "mountain", 0))),
			new Stat("Pickaxe Ability", pretty(str(obj(tree, "selected_ability"), "mining", "none"))))));
		out.add(new Gap(4));
		out.add(new Header("Powder"));
		List<String[]> rows = new ArrayList<>();
		for (String[] powder : new String[][]{{"mithril", "§2Mithril"}, {"gemstone", "§dGemstone"}, {"glacite", "§bGlacite"}}) {
			double current = num(core, "powder_" + powder[0], 0), spent = num(core, "powder_spent_" + powder[0], 0);
			rows.add(new String[]{powder[1], commas(current), commas(spent), commas(current + spent)});
		}
		out.add(new Table(new String[]{"Powder", "Available", "Spent", "Total"}, rows, 0.25f));
		return out;
	}

	static List<Block> crystals(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject crystals = path(p.member, "mining_core", "crystals");
		out.add(new Header("Crystal Hollows"));
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : crystals.entrySet()) {
			if (!(e.getValue() instanceof JsonObject c)) continue;
			rows.add(new String[]{pretty(e.getKey()), pretty(str(c, "state", "NOT_FOUND")), commas(num(c, "total_found", 0)), commas(num(c, "total_placed", 0))});
		}
		out.add(rows.isEmpty() ? new Note("No crystals found.") : new Table(new String[]{"Crystal", "State", "Found", "Placed"}, rows, 0.3f));
		JsonObject glacite = obj(p.member, "glacite_player_data");
		out.add(new Gap(4));
		out.add(new Header("Glacite Tunnels"));
		JsonObject corpses = obj(glacite, "corpses_looted");
		List<Stat> stats = new ArrayList<>();
		stats.add(new Stat("Mineshafts Entered", commas(num(glacite, "mineshafts_entered", 0))));
		stats.add(new Stat("Fossils Donated", String.valueOf(arr(glacite, "fossils_donated").size())));
		stats.add(new Stat("Fossil Dust", commas(num(glacite, "fossil_dust", 0))));
		for (Map.Entry<String, Double> e : PvProfile.numbers(corpses).entrySet()) stats.add(new Stat(pretty(e.getKey()) + " Corpses", commas(e.getValue())));
		out.add(new Stats(stats));
		return out;
	}

	static List<Block> forge(PvProfile p) {
		List<Block> out = new ArrayList<>();
		out.add(new Header("Forge"));
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, JsonElement> forge : path(p.member, "forge", "forge_processes").entrySet()) {
			if (!(forge.getValue() instanceof JsonObject slots)) continue;
			for (Map.Entry<String, JsonElement> slot : slots.entrySet()) {
				if (!(slot.getValue() instanceof JsonObject s)) continue;
				long started = (long) num(s, "startTime", 0);
				rows.add(new String[]{pretty(str(s, "id", "?")), "Slot " + slot.getKey(), started > 0 ? PvUi.duration(System.currentTimeMillis() - started) + " ago" : "-"});
			}
		}
		out.add(rows.isEmpty() ? new Note("Nothing in the forge.") : new Table(new String[]{"Item", "Slot", "Started"}, rows, 0.5f));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Foraging

	static List<Block> foraging(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject tree = obj(p.member, "skill_tree");
		out.add(new Header("Foraging"));
		PvRepo.Level hotf = PvRepo.level(num(obj(tree, "experience"), "foraging", 0), PvRepo.table("HOTF"), PvRepo.cap("HOTF", 8));
		out.add(new Bars(List.of(skillBar(p, "foraging"), levelBar(PvItems.icon("minecraft:oak_sapling"), "Heart of the Forest", hotf))));
		out.add(new Stats(List.of(new Stat("Tokens Spent", String.valueOf((int) num(obj(tree, "tokens_spent"), "forest", 0))))));
		JsonObject core = obj(p.member, "foraging_core");
		List<Stat> stats = new ArrayList<>();
		for (Map.Entry<String, Double> e : PvProfile.numbers(core).entrySet()) {
			if (!e.getKey().endsWith("_day")) stats.add(new Stat(pretty(e.getKey()), commas(e.getValue())));
		}
		if (!stats.isEmpty()) {
			out.add(new Gap(4));
			out.add(new Header("Foraging Stats"));
			out.add(new Stats(stats));
		}
		return out;
	}

	static List<Block> hunting(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject stats = obj(p.member, "player_stats");
		out.add(new Header("Hunting"));
		out.add(new Bars(List.of(skillBar(p, "hunting")), 260));
		out.add(new Stats(List.of(
			new Stat("Unique Shards", commas(num(stats, "unique_shards", 0))),
			new Stat("Combat Hunts", commas(num(stats, "shard_combat_hunts", 0))),
			new Stat("Fishing Hunts", commas(num(stats, "shard_fishing_hunts", 0))),
			new Stat("Forest Hunts", commas(num(stats, "shard_forest_hunts", 0))),
			new Stat("Trap Hunts", commas(num(stats, "shard_trap_hunts", 0))),
			new Stat("Salt Hunts", commas(num(stats, "shard_salt_hunts", 0))))));
		Map<String, Double> attributes = PvProfile.numbers(path(p.member, "attributes", "stacks"));
		if (!attributes.isEmpty()) {
			out.add(new Gap(4));
			out.add(new Header("Attributes"));
			out.add(new Table(new String[]{"Attribute", "Stacks"}, topRows(attributes, 200), 0.6f));
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Fishing

	static List<Block> fishing(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject stats = obj(p.member, "player_stats");
		out.add(new Header("Fishing"));
		out.add(new Bars(List.of(skillBar(p, "fishing")), 260));
		Map<String, Double> fished = PvProfile.numbers(obj(stats, "items_fished"));
		out.add(new Stats(List.of(
			new Stat("Items Fished", commas(fished.getOrDefault("total", 0d))),
			new Stat("Treasures", commas(fished.getOrDefault("treasure", 0d))),
			new Stat("Large Treasures", commas(fished.getOrDefault("large_treasure", 0d))),
			new Stat("Sea Creature Kills", commas(num(stats, "sea_creature_kills", 0))),
			new Stat("Trophy Fish Caught", commas(num(obj(p.member, "trophy_fish"), "total_caught", 0))))));
		return out;
	}

	static List<Block> trophyFish(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject t = obj(p.member, "trophy_fish");
		out.add(new Header("Trophy Fish"));
		out.add(new Stats(List.of(new Stat("Total Caught", commas(num(t, "total_caught", 0))),
			new Stat("Last Caught", pretty(str(t, "last_caught", "-").replace('/', ' '))))));
		List<String> fish = new ArrayList<>();
		for (String key : t.keySet()) {
			if (key.equals("total_caught") || key.equals("last_caught") || key.equals("rewards")) continue;
			if (key.endsWith("_bronze") || key.endsWith("_silver") || key.endsWith("_gold") || key.endsWith("_diamond")) continue;
			fish.add(key);
		}
		Collections.sort(fish);
		List<String[]> rows = new ArrayList<>();
		for (String f : fish) {
			rows.add(new String[]{pretty(f), commas(num(t, f, 0)), commas(num(t, f + "_bronze", 0)), commas(num(t, f + "_silver", 0)),
				commas(num(t, f + "_gold", 0)), commas(num(t, f + "_diamond", 0))});
		}
		out.add(new Table(new String[]{"Fish", "Total", "§cBronze", "§7Silver", "§6Gold", "§bDiamond"}, rows, 0.3f));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Enchanting / Taming

	static List<Block> experimentation(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject e = obj(p.member, "experimentation");
		out.add(new Header("Experimentation Table"));
		List<String[]> rows = new ArrayList<>();
		for (String[] game : new String[][]{{"pairings", "Superpairs"}, {"simon", "Chronomatron"}, {"numbers", "Ultrasequencer"}}) {
			JsonObject g = obj(e, game[0]);
			double bestScore = 0, attempts = 0;
			for (Map.Entry<String, Double> entry : PvProfile.numbers(g).entrySet()) {
				if (entry.getKey().startsWith("best_score")) bestScore = Math.max(bestScore, entry.getValue());
				if (entry.getKey().startsWith("attempts")) attempts += entry.getValue();
			}
			rows.add(new String[]{game[1], commas(attempts), commas(bestScore)});
		}
		out.add(new Table(new String[]{"Game", "Attempts", "Best Score"}, rows));
		out.add(new Stats(List.of(new Stat("Serums Drank", commas(num(e, "serums_drank", 0))))));
		return out;
	}

	static final Map<String, String> RARITY_COLORS = Map.of("COMMON", "§f", "UNCOMMON", "§a", "RARE", "§9", "EPIC", "§5",
		"LEGENDARY", "§6", "MYTHIC", "§d", "DIVINE", "§b", "SPECIAL", "§c");

	public static ItemStack petStack(JsonObject pet) {
		String type = str(pet, "type", "UNKNOWN");
		String tier = str(pet, "tier", "COMMON");
		int level = PvRepo.petLevel(type, tier, num(pet, "exp", 0));
		ItemStack stack = PvItems.icon(type + ";" + PvRepo.rarityIndex(tier)).copy();
		String color = RARITY_COLORS.getOrDefault(tier, "§f");
		stack.set(DataComponents.CUSTOM_NAME, PvItems.legacy("§7[Lvl " + level + "] " + color + pretty(type)));
		List<Component> lore = new ArrayList<>();
		lore.add(PvItems.legacy(color + "§l" + tier));
		lore.add(PvItems.legacy("§7XP: §f" + commas(num(pet, "exp", 0))));
		if (pet.has("heldItem") && !pet.get("heldItem").isJsonNull()) lore.add(PvItems.legacy("§7Held Item: §a" + pretty(pet.get("heldItem").getAsString().replace("PET_ITEM_", ""))));
		if (pet.has("skin") && !pet.get("skin").isJsonNull()) lore.add(PvItems.legacy("§7Skin: §d" + pretty(pet.get("skin").getAsString())));
		if ((int) num(pet, "candyUsed", 0) > 0) lore.add(PvItems.legacy("§7Candy Used: §f" + (int) num(pet, "candyUsed", 0)));
		if (pet.has("active") && pet.get("active").getAsBoolean()) lore.add(PvItems.legacy("§a§lACTIVE"));
		stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
		return stack;
	}

	static List<Block> pets(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonArray pets = arr(obj(p.member, "pets_data"), "pets");
		List<JsonObject> sorted = new ArrayList<>();
		for (JsonElement el : pets) if (el instanceof JsonObject o) sorted.add(o);
		sorted.sort((a, b) -> {
			int r = Integer.compare(PvRepo.rarityIndex(str(b, "tier", "")), PvRepo.rarityIndex(str(a, "tier", "")));
			return r != 0 ? r : Double.compare(num(b, "exp", 0), num(a, "exp", 0));
		});
		List<ItemStack> stacks = new ArrayList<>();
		for (JsonObject pet : sorted) stacks.add(petStack(pet));
		JsonObject active = p.activePet();
		out.add(new Header("Pets §7(" + stacks.size() + ")"));
		out.add(new Stats(List.of(
			new Stat("Active Pet", active != null ? pretty(str(active, "type", "")) : "None"),
			new Stat("Pet Score", commas(num(obj(p.member, "leveling"), "highest_pet_score", 0))),
			new Stat("Pets Sacrificed", String.valueOf(arr(path(p.member, "pets_data", "pet_care"), "pet_types_sacrificed").size())))));
		out.add(stacks.isEmpty() ? new Note("No pets.") : new Items(stacks, 12));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Inventory

	static List<Block> inventory(PvProfile p) {
		List<Block> out = new ArrayList<>();
		List<ItemStack> armor = new ArrayList<>(p.armor());
		Collections.reverse(armor);
		out.add(new Header("Armor & Equipment"));
		List<ItemStack> worn = new ArrayList<>(armor);
		worn.add(ItemStack.EMPTY);
		worn.addAll(p.equipment());
		out.add(new Items(worn, 9));
		out.add(new Gap(4));
		out.add(new Header("Inventory"));
		List<ItemStack> inv = p.inventory();
		if (inv.isEmpty()) {
			out.add(new Note("Inventory API is turned off for this player."));
			return out;
		}
		// Hypixel stores the hotbar first; show it at the bottom like the real inventory screen.
		List<ItemStack> ordered = new ArrayList<>();
		if (inv.size() >= 36) {
			ordered.addAll(inv.subList(9, 36));
			ordered.addAll(inv.subList(0, 9));
		} else {
			ordered.addAll(inv);
		}
		out.add(new Items(ordered, 9));
		return out;
	}

	static List<Block> enderChest(PvProfile p) {
		List<ItemStack> ec = p.enderChest();
		if (ec.isEmpty()) return grid("Ender Chest", ec, 9);
		List<PvUi.Panel> pages = new ArrayList<>();
		for (int page = 0; page * 45 < ec.size(); page++) {
			pages.add(new PvUi.Panel("Page " + (page + 1), ec.subList(page * 45, Math.min(ec.size(), page * 45 + 45)), 9));
		}
		return List.of(new Header("Ender Chest"), new PvUi.Panels(pages));
	}

	static List<Block> backpacks(PvProfile p) {
		Map<Integer, List<ItemStack>> packs = p.backpacks();
		if (packs.isEmpty()) return grid("Backpacks", List.of(), 9);
		Map<Integer, ItemStack> icons = p.backpackIcons();
		List<PvUi.Panel> panels = new ArrayList<>();
		for (Map.Entry<Integer, List<ItemStack>> e : packs.entrySet()) {
			ItemStack icon = icons.get(e.getKey());
			String name = icon != null ? icon.getHoverName().getString().replaceAll("§.", "") : "Backpack";
			panels.add(new PvUi.Panel((e.getKey() + 1) + ". " + name, e.getValue(), 9));
		}
		return List.of(new Header("Backpacks"), new PvUi.Panels(panels));
	}

	static List<Block> loadouts(PvProfile p) {
		List<Block> out = new ArrayList<>();
		Map<Integer, List<ItemStack>> armorSets = p.armorSets();
		Map<Integer, List<ItemStack>> equipmentSets = p.equipmentSets();
		JsonObject loadouts = p.loadouts();
		Map<String, String> petNames = new java.util.HashMap<>();
		for (JsonElement el : arr(obj(p.member, "pets_data"), "pets")) {
			if (el instanceof JsonObject pet) petNames.put(str(pet, "uniqueId", ""), pretty(str(pet, "type", "")));
		}
		for (Map.Entry<String, JsonElement> e : loadouts.entrySet()) {
			if (!(e.getValue() instanceof JsonObject l) || !l.has("armor_set_id") && !l.has("equipment_set_id")) continue;
			List<ItemStack> row = new ArrayList<>(armorSet(p, (int) num(l, "armor_set_id", -1)));
			while (row.size() < 4) row.add(ItemStack.EMPTY);
			row.add(ItemStack.EMPTY);
			row.addAll(equipmentSet(p, (int) num(l, "equipment_set_id", -1)));
			String extras = (l.has("pet") ? "  §7Pet: §f" + petNames.getOrDefault(str(l, "pet", ""), "?") : "")
				+ (l.has("power_stone") ? "  §7Power: §f" + pretty(str(l, "power_stone", "")) : "");
			out.add(new Header(str(l, "name", "Loadout " + e.getKey()) + extras));
			out.add(new Items(row, 9));
			out.add(new Gap(4));
		}
		out.add(new Header("Wardrobe Armor Sets"));
		java.util.TreeSet<Integer> ids = new java.util.TreeSet<>(armorSets.keySet());
		if (p.equippedSet("armor") > 0) ids.add(p.equippedSet("armor"));
		if (ids.isEmpty()) out.add(new Note("No saved armor sets, or this API setting is turned off."));
		List<PvUi.Panel> panels = new ArrayList<>();
		for (int id : ids) panels.add(new PvUi.Panel("Set " + id + (id == p.equippedSet("armor") ? " §a(equipped)" : ""), armorSet(p, id), 1));
		if (!panels.isEmpty()) out.add(new PvUi.Panels(panels));
		return out;
	}

	/** Hypixel leaves the equipped set's slot empty in loadout.armor — its items are the ones being worn. */
	static List<ItemStack> armorSet(PvProfile p, int id) {
		if (id > 0 && id == p.equippedSet("armor")) {
			List<ItemStack> worn = new ArrayList<>(p.armor());
			Collections.reverse(worn);
			return worn;
		}
		return p.armorSets().getOrDefault(id, List.of());
	}

	static List<ItemStack> equipmentSet(PvProfile p, int id) {
		if (id > 0 && id == p.equippedSet("equipment")) return p.equipment();
		return p.equipmentSets().getOrDefault(id, List.of());
	}

	static List<Block> accessories(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject storage = obj(p.member, "accessory_bag_storage");
		out.add(new Header("Accessory Bag"));
		out.add(new Stats(List.of(
			new Stat("Magical Power", commas(num(storage, "highest_magical_power", 0)), 0xFF55FFFF),
			new Stat("Selected Power", pretty(str(storage, "selected_power", "none"))),
			new Stat("Unlocked Powers", String.valueOf(arr(storage, "unlocked_powers").size())),
			new Stat("Bag Upgrades", String.valueOf((int) num(storage, "bag_upgrades_purchased", 0))))));
		JsonObject tuning = obj(obj(storage, "tuning"), "slot_0");
		List<Stat> tunings = new ArrayList<>();
		for (Map.Entry<String, Double> e : PvProfile.numbers(tuning).entrySet()) {
			if (e.getValue() > 0 && !e.getKey().equals("purchase_ts")) tunings.add(new Stat(pretty(e.getKey()), String.valueOf(e.getValue().intValue())));
		}
		if (!tunings.isEmpty()) {
			out.add(new Header("Tuning Points"));
			out.add(new Stats(tunings, 140));
		}
		out.add(new Gap(4));
		out.addAll(grid("Accessories", p.talismans(), 0));
		return out;
	}

	static List<Block> bags(PvProfile p) {
		List<Block> out = new ArrayList<>();
		out.addAll(grid("Fishing Bag", p.fishingBag(), 9));
		out.add(new Gap(4));
		out.addAll(grid("Quiver", p.quiver(), 9));
		out.add(new Gap(4));
		out.addAll(grid("Potion Bag", p.potionBag(), 9));
		return out;
	}

	static List<Block> sacks(PvProfile p) {
		List<Block> out = new ArrayList<>();
		Map<String, Double> sacks = p.sacks();
		double value = 0;
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, Double> e : sacks.entrySet()) {
			if (e.getValue() <= 0) continue;
			double v = PvNetworth.price(e.getKey()) * e.getValue();
			value += v;
			rows.add(new String[]{itemName(e.getKey()), commas(e.getValue()), v > 0 ? shortNum(v) : "-"});
		}
		out.add(new Header("Sacks"));
		out.add(new Stats(List.of(new Stat("Item Types", String.valueOf(rows.size())), new Stat("Value", shortNum(value), 0xFFFFAA00))));
		out.add(rows.isEmpty() ? new Note("Sacks are empty or the API setting is off.") : new Table(new String[]{"Item", "Amount", "Value"}, rows, 0.5f));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Collections

	static String itemName(String id) {
		com.cokelord.skyblocksimplified.api.SkyblockItemRepo.ItemInfo info = com.cokelord.skyblocksimplified.api.SkyblockItemRepo.getItem(id);
		return info != null ? info.name().replaceAll("§.", "") : pretty(id);
	}

	static String collectionName(String id) {
		JsonObject all = PvRepo.collections();
		if (all != null) {
			for (Map.Entry<String, JsonElement> cat : all.entrySet()) {
				JsonObject items = obj(cat.getValue().getAsJsonObject(), "items");
				if (items.has(id)) return str(items.getAsJsonObject(id), "name", pretty(id));
			}
		}
		if (id.equals("MUSHROOM_COLLECTION")) return "Mushroom";
		return itemName(id);
	}

	static List<Block> collections(PvProfile p, String category) {
		List<Block> out = new ArrayList<>();
		JsonObject all = PvRepo.collections();
		out.add(new Header(pretty(category) + " Collections"));
		if (all == null) {
			out.add(new Note("Loading collection data..."));
			return out;
		}
		JsonObject items = obj(obj(all, category), "items");
		JsonObject mine = obj(p.member, "collection");
		List<Bar> bars = new ArrayList<>();
		int maxed = 0;
		for (Map.Entry<String, JsonElement> e : items.entrySet()) {
			JsonObject info = e.getValue().getAsJsonObject();
			double amount = num(mine, e.getKey(), 0);
			JsonArray tiers = arr(info, "tiers");
			int tier = 0;
			double prev = 0, next = 0;
			for (JsonElement t : tiers) {
				double req = num(t.getAsJsonObject(), "amountRequired", 0);
				if (amount >= req) { tier++; prev = req; } else { next = req; break; }
			}
			int maxTiers = (int) num(info, "maxTiers", tiers.size());
			if (tier >= maxTiers) maxed++;
			double progress = next > 0 ? (amount - prev) / (next - prev) : 1;
			bars.add(new Bar(PvItems.icon(e.getKey()), str(info, "name", pretty(e.getKey())), tier + "/" + maxTiers, progress,
				next > 0 ? commas(amount) + " / " + commas(next) : "MAX  " + commas(amount), null));
		}
		out.add(new Stats(List.of(new Stat("Maxed", maxed + " / " + items.size()))));
		out.add(new Bars(bars, 175));
		out.add(new Note("Co-op collections count only this player's own contribution."));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Rift

	static List<Block> rift(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject rift = obj(p.member, "rift");
		JsonObject stats = path(p.member, "player_stats", "rift");
		out.add(new Header("Rift"));
		List<Stat> s = new ArrayList<>();
		s.add(new Stat("Motes", commas(num(obj(p.member, "currencies"), "motes_purse", 0)), 0xFFFF55FF));
		s.add(new Stat("Enigma Souls", String.valueOf(arr(obj(rift, "enigma"), "found_souls").size())));
		s.add(new Stat("Montezuma Cats", String.valueOf(arr(obj(rift, "dead_cats"), "found_cats").size())));
		s.add(new Stat("Timecharms", String.valueOf(arr(obj(rift, "gallery"), "secured_trophies").size())));
		s.add(new Stat("Burger Stacks", String.valueOf((int) num(obj(rift, "castle"), "grubber_stacks", 0))));
		for (Map.Entry<String, Double> e : PvProfile.numbers(stats).entrySet()) s.add(new Stat(pretty(e.getKey()), commas(e.getValue())));
		out.add(new Stats(s));
		return out;
	}

	static List<Block> riftInventory(PvProfile p) {
		JsonObject inv = p.riftInventory();
		List<Block> out = new ArrayList<>();
		List<ItemStack> armor = new ArrayList<>(PvItems.decode(obj(inv, "inv_armor")));
		Collections.reverse(armor);
		armor.add(ItemStack.EMPTY);
		armor.addAll(PvItems.decode(obj(inv, "equipment_contents")));
		out.addAll(grid("Rift Armor & Equipment", armor, 9));
		out.add(new Gap(4));
		out.addAll(grid("Rift Inventory", PvItems.decode(obj(inv, "inv_contents")), 9));
		out.add(new Gap(4));
		out.addAll(grid("Rift Ender Chest", PvItems.decode(obj(inv, "ender_chest_contents")), 9));
		return out;
	}

	// ---------------------------------------------------------------------------------------------------
	// Misc

	static List<Block> general(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject fairy = obj(p.member, "fairy_soul");
		JsonObject profileData = obj(p.member, "profile");
		out.add(new Header("Profile"));
		out.add(new Stats(List.of(
			new Stat("Profile", p.name() + (p.modeLabel().isEmpty() ? "" : " (" + p.modeLabel() + ")")),
			new Stat("Members", String.valueOf(p.memberCount())),
			new Stat("First Join", p.firstJoin() > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(p.firstJoin())) : "-"),
			new Stat("Booster Cookie", profileData.has("cookie_buff_active") && profileData.get("cookie_buff_active").getAsBoolean() ? "§aActive" : "§cInactive"),
			new Stat("Fairy Souls", commas(num(fairy, "total_collected", 0))),
			new Stat("Fairy Exchanges", commas(num(fairy, "fairy_exchanges", 0))),
			new Stat("Deaths", commas(num(path(p.member, "player_data"), "death_count", 0))),
			new Stat("Personal Bank", "Tier " + (int) num(profileData, "personal_bank_upgrade", 0)))));
		return out;
	}

	static List<Block> essence(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject currencies = obj(p.member, "currencies");
		out.add(new Header("Currencies"));
		out.add(new Stats(List.of(
			new Stat("Purse", commas(p.purse()), 0xFFFFAA00),
			new Stat("Bank", commas(p.bank()), 0xFFFFAA00),
			new Stat("Personal Bank", commas(p.personalBank()), 0xFFFFAA00),
			new Stat("Motes", commas(num(currencies, "motes_purse", 0)), 0xFFFF55FF),
			new Stat("Copper", commas(num(obj(p.member, "garden_player_data"), "copper", 0)), 0xFFFF7755))));
		out.add(new Gap(4));
		out.add(new Header("Essence"));
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : obj(currencies, "essence").entrySet()) {
			double amount = e.getValue() instanceof JsonObject o ? num(o, "current", 0) : 0;
			double value = amount * PvNetworth.price("ESSENCE_" + e.getKey());
			rows.add(new String[]{pretty(e.getKey()), commas(amount), value > 0 ? shortNum(value) : "-"});
		}
		out.add(new Table(new String[]{"Essence", "Amount", "Value"}, rows));
		return out;
	}

	static List<Block> otherSkills(PvProfile p) {
		List<Block> out = new ArrayList<>();
		out.add(new Header("Other Skills"));
		out.add(new Bars(List.of(skillBar(p, "runecrafting"), skillBar(p, "social"))));
		return out;
	}

	static List<Block> chocolate(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject easter = path(p.member, "events", "easter");
		out.add(new Header("Chocolate Factory"));
		if (easter.entrySet().isEmpty()) {
			out.add(new Note("No Chocolate Factory data."));
			return out;
		}
		JsonObject rabbits = obj(easter, "rabbits");
		int uniqueRabbits = 0;
		for (Map.Entry<String, JsonElement> e : rabbits.entrySet()) if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) uniqueRabbits++;
		out.add(new Stats(List.of(
			new Stat("Chocolate", shortNum(num(easter, "chocolate", 0)), 0xFFAA5500),
			new Stat("All-time Chocolate", shortNum(num(easter, "total_chocolate", 0))),
			new Stat("Since Prestige", shortNum(num(easter, "chocolate_since_prestige", 0))),
			new Stat("Prestige Level", String.valueOf((int) num(easter, "chocolate_level", 1))),
			new Stat("Unique Rabbits", String.valueOf(uniqueRabbits)),
			new Stat("Barn Level", String.valueOf((int) num(easter, "rabbit_barn_capacity_level", 0))),
			new Stat("Click Upgrades", String.valueOf((int) num(easter, "click_upgrades", 0))),
			new Stat("Multiplier Upgrades", String.valueOf((int) num(easter, "chocolate_multiplier_upgrades", 0))))));
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, Double> e : PvProfile.numbers(obj(easter, "employees")).entrySet()) rows.add(new String[]{pretty(e.getKey()), String.valueOf(e.getValue().intValue())});
		if (!rows.isEmpty()) {
			out.add(new Gap(4));
			out.add(new Header("Employees"));
			out.add(new Table(new String[]{"Employee", "Level"}, rows, 0.6f));
		}
		return out;
	}

	static List<Block> miscStats(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonObject stats = obj(p.member, "player_stats");
		for (String group : new String[]{"auctions", "gifts", "mythos", "end_island", "spooky", "races", "candy_collected", "pets"}) {
			List<Stat> s = new ArrayList<>();
			flatten(obj(stats, group), "", s);
			if (s.isEmpty()) continue;
			out.add(new Header(pretty(group)));
			out.add(new Stats(s));
			out.add(new Gap(4));
		}
		if (out.isEmpty()) out.add(new Note("No miscellaneous stats."));
		return out;
	}

	private static void flatten(JsonObject o, String prefix, List<Stat> out) {
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			String name = prefix.isEmpty() ? pretty(e.getKey()) : prefix + " " + pretty(e.getKey());
			if (e.getValue() instanceof JsonObject child) flatten(child, name, out);
			else if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) out.add(new Stat(name, commas(e.getValue().getAsDouble())));
		}
	}

	static List<Block> minions(PvProfile p) {
		List<Block> out = new ArrayList<>();
		JsonArray crafted = arr(path(p.member, "player_data"), "crafted_generators");
		Map<String, Integer> highest = new java.util.TreeMap<>();
		for (JsonElement el : crafted) {
			String s = el.getAsString();
			int split = s.lastIndexOf('_');
			if (split < 0) continue;
			try {
				highest.merge(s.substring(0, split), Integer.parseInt(s.substring(split + 1)), Math::max);
			} catch (NumberFormatException ignored) {}
		}
		out.add(new Header("Minions"));
		out.add(new Stats(List.of(new Stat("Unique Tiers Crafted", String.valueOf(crafted.size())), new Stat("Minion Types", String.valueOf(highest.size())))));
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, Integer> e : highest.entrySet()) rows.add(new String[]{pretty(e.getKey()), "Tier " + e.getValue()});
		out.add(new Table(new String[]{"Minion", "Highest Tier"}, rows, 0.6f));
		return out;
	}
}
