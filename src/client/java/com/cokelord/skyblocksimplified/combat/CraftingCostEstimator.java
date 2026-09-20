package com.cokelord.skyblocksimplified.combat;

import com.cokelord.skyblocksimplified.api.AuctionApi;
import com.cokelord.skyblocksimplified.api.BazaarApi;
import com.cokelord.skyblocksimplified.api.NeuInternalName;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Estimates an item's value from its own crafting recipe (recursively, in case an ingredient itself has
 * no direct market price but IS craftable) when nothing sells it directly — for items like farming tools
 * that aren't bazaar/NPC-sellable and often aren't auctionable either, but are still clearly worth
 * something because of what it costs to craft them. Recipe data comes from SkyblockItemRepo, itself read
 * straight from Hypixel's own item resource (the same "recipe": {"A1": "ITEM_ID:AMOUNT", ...} object every
 * craftable item's entry already carries — this project just wasn't reading it before).
 *
 * Ingredient cost uses bazaar instant-buy price (what it'd actually cost to acquire it right now), falling
 * back to NPC sell price, then median auction house BIN price as a last resort — this used to stop at NPC
 * sell price, which meant any ingredient that's neither bazaar-tradeable nor NPC-sellable (many rarer
 * crafting components only circulate on the auction house) made the WHOLE recipe chain unpriceable, even
 * when every other ingredient resolved fine. That's the actual "crafting cost doesn't show" gap: one
 * missing price source on one ingredient a few levels deep silently killed the entire estimate. An
 * ingredient that resolves to neither a direct price nor a further recipe still makes the whole estimate
 * unknown (returns null) rather than silently under-counting — a partial total that looks confident but is
 * actually missing a chunk of the real cost is worse than showing nothing.
 */
public final class CraftingCostEstimator {
	private static final int MAX_DEPTH = 6;

	// Per user-confirmed real recipe ("hyperion is craftable with 8 l.a.s.rs eyes and a necrons blade, which
	// is crafted by a necrons handle and 24 wither catalysts"): Hyperion and Necron's Blade are NPC-crafted
	// upgrades (Wither Cage), not a standard 3x3 grid recipe, so Hypixel's own item resource has no "recipe"
	// object for either — confirmed directly against the live /resources/skyblock/items data (both entries'
	// "recipe" field is genuinely absent, not just unread). Without this override neither could ever get a
	// real crafting-cost fallback at all, no matter how unreliable the auction-house price ended up being.
	// Real ids verified against the same live item resource: SUMMONING_EYE ("Summoning Eye"), NECRON_BLADE
	// ("Necron's Blade (Unrefined)"), NECRON_HANDLE ("Necron's Handle"), WITHER_CATALYST ("Wither Catalyst").
	private static final Map<String, Map<String, Integer>> MANUAL_RECIPES = Map.of(
		"HYPERION", Map.of("NECRON_BLADE", 1, "SUMMONING_EYE", 8),
		"NECRON_BLADE", Map.of("NECRON_HANDLE", 1, "WITHER_CATALYST", 24)
	);

	private CraftingCostEstimator() {}

	public static Double estimate(String itemId) {
		return estimate(itemId, new HashSet<>(), 0);
	}

	private static Double estimate(String itemId, Set<String> visiting, int depth) {
		if (depth > MAX_DEPTH || !visiting.add(itemId)) return null;
		try {
			Double direct = directCost(itemId);
			if (direct != null) return direct;

			Map<String, Integer> recipe = MANUAL_RECIPES.get(itemId);
			if (recipe == null) {
				SkyblockItemRepo.ItemInfo info = SkyblockItemRepo.getItem(itemId);
				recipe = info != null ? info.recipe() : null;
			}
			if (recipe == null || recipe.isEmpty()) return null;

			double total = 0;
			for (Map.Entry<String, Integer> ingredient : recipe.entrySet()) {
				Double ingredientCost = estimate(ingredient.getKey(), visiting, depth + 1);
				if (ingredientCost == null) return null;
				total += ingredientCost * ingredient.getValue();
			}
			return total;
		} finally {
			visiting.remove(itemId);
		}
	}

	private static Double directCost(String itemId) {
		NeuInternalName name = NeuInternalName.of(itemId);
		BazaarApi.BazaarPrice price = name.getBazaarPrice();
		if (price != null) return price.buyPrice();
		Double npc = name.getNpcSellPrice();
		if (npc != null) return npc;
		AuctionApi.AuctionPrice auction = AuctionApi.getPrice(name.asString());
		return auction != null ? auction.medianBinPrice() : null;
	}
}
