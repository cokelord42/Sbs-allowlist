package com.cokelord.skyblocksimplified.diana;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-rare-mob drop pools for the Diana roll, per the user's list: Inquisitor = Chimera, King Minos = Crown
 * of Greed + Shimmering Wool, Manticore = Manti-core + Fateful Stinger, Sphinx = Brain Food. Everything else
 * in the strip is a common every Diana mob drops (Enchanted Gold, Ancient Claw, Enchanted Ancient Claw). THE WEIGHTS ARE UNCONFIRMED PLACEHOLDERS — per user request they're
 * tuned by trial and error with /dianaroll. They only decide which decoys scroll past; the landed item is
 * always the real drop from chat.
 */
public final class DianaDropTables {
	/** @param color legacy color code (SBO tracker's rarity colors) @param rare shown as a "near miss" and plays the land sound */
	public record Drop(String name, String skyblockId, Item fallback, String color, double weight, boolean rare) {}

	// Per user's list: each rare mob only drops its own items. Chimera, Crown of Greed and Shimmering Wool have
	// no repo texture, so they use a vanilla stand-in (skyblockId null); the rest use their real icons.
	private static final Drop CHIMERA = new Drop("Chimera I", null, Items.ENCHANTED_BOOK, "§d", 0.2, true);
	private static final Drop WOOL = new Drop("Shimmering Wool", null, Items.WOOL.yellow(), "§c", 0.2, true);
	private static final Drop CROWN = new Drop("Crown of Greed", null, Items.GOLDEN_HELMET, "§6", 0.4, true);
	private static final Drop CORE = new Drop("Manti-core", "MANTI_CORE", Items.MAGMA_CREAM, "§c", 0.2, true);
	private static final Drop STINGER = new Drop("Fateful Stinger", "FATEFUL_STINGER", Items.POINTED_DRIPSTONE, "§d", 0.3, true);
	private static final Drop BRAIN_FOOD = new Drop("Brain Food", "BRAIN_FOOD", Items.COOKIE, "§5", 0.3, true);

	// Commons every Diana mob drops (per user). Fill most of the strip; a kill with no rare drop lands on one.
	private static final Drop ENCHANTED_GOLD = new Drop("Enchanted Gold", "ENCHANTED_GOLD", Items.GOLD_INGOT, "§a", 6, false);
	private static final Drop ANCIENT_CLAW = new Drop("Ancient Claw", "ANCIENT_CLAW", Items.FLINT, "§9", 5, false);
	private static final Drop ENCH_ANCIENT_CLAW = new Drop("Enchanted Ancient Claw", "ENCHANTED_ANCIENT_CLAW", Items.FLINT, "§9", 2, false);
	public static final List<Drop> COMMONS = List.of(ENCHANTED_GOLD, ANCIENT_CLAW, ENCH_ANCIENT_CLAW);

	/** Common entry for a Skyblock item id picked up after a kill, or null. */
	public static Drop commonById(String id) {
		for (Drop d : COMMONS) if (d.skyblockId().equals(id)) return d;
		return null;
	}

	private static final Map<RareMobs.Mob, List<Drop>> SPECIAL = Map.of(
		RareMobs.Mob.INQ, List.of(CHIMERA),
		RareMobs.Mob.KING, List.of(WOOL, CROWN),
		RareMobs.Mob.MANTI, List.of(CORE, STINGER),
		RareMobs.Mob.SPHINX, List.of(BRAIN_FOOD));

	private DianaDropTables() {}

	public static List<Drop> pool(RareMobs.Mob mob) {
		List<Drop> pool = new ArrayList<>(SPECIAL.getOrDefault(mob, List.of()));
		pool.addAll(COMMONS);
		return pool;
	}

	public static Drop weighted(List<Drop> pool) {
		double total = 0;
		for (Drop d : pool) total += d.weight();
		double r = ThreadLocalRandom.current().nextDouble(total);
		for (Drop d : pool) {
			r -= d.weight();
			if (r <= 0) return d;
		}
		return pool.get(pool.size() - 1);
	}

	/** Decoy strip: weighted picks, never the same drop twice in a row, plus a sprinkle of this mob's rares. */
	public static List<Drop> strip(RareMobs.Mob mob, int length) {
		List<Drop> pool = pool(mob);
		List<Drop> rares = new ArrayList<>();
		for (Drop d : pool) if (d.rare()) rares.add(d);
		List<Drop> out = new ArrayList<>(length);
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		for (int i = 0; i < length; i++) {
			Drop pick = !rares.isEmpty() && rng.nextInt(100) < 3 ? rares.get(rng.nextInt(rares.size())) : weighted(pool);
			if (!out.isEmpty() && out.get(out.size() - 1) == pick) pick = weighted(pool);
			out.add(pick);
		}
		return out;
	}

	/** A random rare from this mob's own pool (near-miss neighbour of the winner), or null. */
	public static Drop randomRare(RareMobs.Mob mob) {
		List<Drop> rares = new ArrayList<>();
		for (Drop d : pool(mob)) if (d.rare()) rares.add(d);
		return rares.isEmpty() ? null : rares.get(ThreadLocalRandom.current().nextInt(rares.size()));
	}

	/** Maps a real "RARE DROP!" line (color-stripped) to its pool entry, or null. */
	public static Drop fromChat(String plainDrop) {
		for (RareMobs.Mob mob : RareMobs.Mob.values()) {
			for (Drop d : pool(mob)) {
				String key = d == CHIMERA ? "Chimera" : d.name();
				if (plainDrop.contains(key)) return d;
			}
		}
		return null;
	}

	/** Loose lookup for /dianaroll's forced item argument. */
	public static Drop byName(RareMobs.Mob mob, String query) {
		String q = query.toLowerCase(java.util.Locale.ROOT).replace("_", " ");
		for (Drop d : pool(mob)) if (d.name().toLowerCase(java.util.Locale.ROOT).contains(q)) return d;
		return null;
	}
}
