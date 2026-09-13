package com.cokelord.skyblocksimplified.combat;

import com.cokelord.skyblocksimplified.api.AuctionApi;
import com.cokelord.skyblocksimplified.api.BazaarApi;
import com.cokelord.skyblocksimplified.api.NeuInternalName;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.item.RomanNumeralUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared "estimated coin value of this item's lore" scan — coins lines, essence lines (confirmed
 * regex/formula from SkyHanni's InstanceChestProfit.kt), and any other line whose plain name matches a
 * real Hypixel item via SkyblockItemRepo's reverse name index. Used by every chest/reward value-estimate
 * feature (InstanceChestProfitFeature, CroesusChestOverlayFeature, RareRewardWarningFeature,
 * VisitorShoppingListFeature) so they can't silently drift from each other.
 */
public final class ChestValueEstimator {
	// Plain text, no literal §-code requirements: Component#getString() (what these lore lines actually
	// come through as) already strips all formatting, so the original SkyHanni-ported patterns (which
	// require literal "§6"/"§." prefixes) could never match anything. This silently zeroed out every
	// coin/essence line's contribution — since the remaining boilerplate lore lines (key requirement
	// text etc.) are identical across every chest type, every chest's estimate collapsed to the same
	// constant value regardless of its real cost, which is exactly the "every chest shows the same net
	// loss" bug.
	// Optional leading "-" — a chest's own purchase cost line ("Cost: -100,000 Coins") is a real, negative
	// coin line just like any positive reward line, but the amount group previously couldn't capture the
	// sign at all, so a cost line's magnitude got silently ADDED to the total instead of subtracted. That
	// let a high-cost, low-loot chest read as the single most "valuable" one — the actual "highlights the
	// lowest profit chest" bug, since Croesus's own best-chest picker just takes the highest total value.
	private static final Pattern COINS_LINE = Pattern.compile("(?<sign>-)?(?<amount>[\\d,]+) Coins");
	private static final Pattern ESSENCE_LINE = Pattern.compile("(?<name>\\w+ Essence) x(?<count>\\d+)");
	private static final Pattern COLOR_CODE = Pattern.compile("§.");
	// Real bug found (per user report — "Croesus still doesnt show profit... issues detecting the items
	// inside the chest"): a Croesus reward slot's own item stack name for a book reward is "Enchanted Book
	// (One For All V)" (confirmed real format, ported from Odin's own Croesus.kt previewEnchantedBookRegex)
	// — priceOf(String)'s plain SkyblockItemRepo.findIdByName lookup below can never match this against the
	// repo's item list, since Hypixel's repo only has one generic "Enchanted Book" entry, not one per
	// enchant+level. Enchanted books are routinely the single most valuable item in a chest, so silently
	// pricing them at 0 while coins/essence lines (which use their own dedicated regexes above, unaffected)
	// still worked is exactly "issues detecting the items", not "detects nothing at all".
	private static final Pattern ENCHANTED_BOOK_LINE = Pattern.compile("^Enchanted Book \\(?([\\w ]+) (\\w+)\\)?$");
	// Per user report ("The instance chest profit does not detect books... It needs to read lore on
	// enchanted books to find out the book type and tier"): a reward-chest book slot's own display name is
	// sometimes just a bare "Enchanted Book" with NO enchant/tier in it at all — ENCHANTED_BOOK_LINE above
	// can never match that. The real enchant name+tier still shows up as its own plain lore line instead
	// (e.g. a standalone "Sharpness VII" line) — this is stricter than ENCHANTED_BOOK_LINE's own catch-all
	// since real lore has plenty of other unrelated plain-text lines (flavor text, "Right-click to apply"
	// etc.) that must NOT be mistaken for an enchant line: RomanNumeralUtil.parseLevel's own strict
	// roman/decimal validation (used below, not in this regex) is what actually rejects those.
	private static final Pattern BARE_ENCHANT_LORE_LINE = Pattern.compile("^([A-Za-z][A-Za-z' ]*) ([IVXLCDM]+|\\d+)$");
	// Real, confirmed Hypixel Bazaar naming split (ported verbatim from Odin's own Croesus.kt
	// ultimateEnchants set) — an Ultimate enchantment's bazaar product id is "ENCHANTMENT_ULTIMATE_<name>_
	// <level>", every other enchant is just "ENCHANTMENT_<name>_<level>"; guessing the wrong one for either
	// kind resolves to a nonexistent bazaar product and silently prices at 0.
	private static final Set<String> ULTIMATE_ENCHANTS = Set.of(
		"SOUL_EATER", "COMBO", "LEGION", "ONE_FOR_ALL", "REND", "BANK", "SWARM", "LAST_STAND", "WISDOM", "NO_PAIN_NO_GAIN");

	private ChestValueEstimator() {}

	public static double estimate(ItemStack stack, boolean includeEssence) {
		return estimate(stack, includeEssence, false);
	}

	public record PricedLine(String name, double price) {}
	public record Breakdown(List<PricedLine> lines, double total) {}

	/** Same lore scan as {@link #estimate}, but also returns each priced line individually (name + its own
	 *  value) instead of only the summed total — for a per-item breakdown display (e.g. Croesus's chest
	 *  contents HUD) rather than just a highlight decision. */
	public static Breakdown estimateItemized(ItemStack stack, boolean includeEssence, boolean coinsAreCost) {
		List<PricedLine> lines = new java.util.ArrayList<>();
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return new Breakdown(lines, 0);

		double total = 0;
		for (Component component : lore.lines()) {
			String text = COLOR_CODE.matcher(component.getString()).replaceAll("");

			Matcher coinMatcher = COINS_LINE.matcher(text);
			if (coinMatcher.find()) {
				double amount = Double.parseDouble(coinMatcher.group("amount").replace(",", ""));
				boolean negative = coinMatcher.group("sign") != null;
				if (coinsAreCost) negative = !negative;
				double value = negative ? -amount : amount;
				total += value;
				lines.add(new PricedLine(text.trim(), value));
				continue;
			}

			Matcher essenceMatcher = ESSENCE_LINE.matcher(text);
			if (essenceMatcher.find()) {
				if (includeEssence) {
					int count = Integer.parseInt(essenceMatcher.group("count"));
					double value = count * priceOfEssence(essenceMatcher.group("name"));
					total += value;
					lines.add(new PricedLine(text.trim(), value));
				}
				continue;
			}

			double price = priceOf(text.trim());
			if (price > 0) {
				total += price;
				lines.add(new PricedLine(text.trim(), price));
			}
		}
		return new Breakdown(lines, total);
	}

	/** coinsAreCost: true for an unopened Croesus chest item's own lore, where a "250,000 Coins" line is
	 *  what the chest itself COSTS to open (confirmed real format via SkyHanni's InstanceChestProfit.kt —
	 *  "§6(?&lt;amount&gt;.*) Coins|§aFREE", the exact same "N Coins" shape as an actual reward line, with
	 *  no distinguishing sign or label at all; SkyHanni only tells the two apart by which lore it's reading,
	 *  negating it explicitly). Every other caller (actual reward-slot contents, shopping lists, etc.) wants
	 *  the default false — a "N Coins" line there really does mean "you get N coins," additive. Using the
	 *  same additive default for the Croesus chest icon's own lore was silently adding its purchase cost as
	 *  if it were loot, which inflated exactly the priciest chests into looking like the best pick. */
	public static double estimate(ItemStack stack, boolean includeEssence, boolean coinsAreCost) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return 0;

		double total = 0;
		for (Component component : lore.lines()) {
			// Stripped unconditionally now, not just for the plainName fallback below — real Hypixel lore
			// lines are "§dCrimson Essence §8x250" (confirmed via SkyHanni's own regex test cases), and
			// unlike chat text, getString() does NOT reliably strip these for lore, so the essence pattern
			// (which needs "Essence x250" with no gap) could never match the literal "Essence §8x250" this
			// produced — the actual "Include Essence Price still isn't adding" bug.
			String text = COLOR_CODE.matcher(component.getString()).replaceAll("");

			Matcher coinMatcher = COINS_LINE.matcher(text);
			if (coinMatcher.find()) {
				double amount = Double.parseDouble(coinMatcher.group("amount").replace(",", ""));
				boolean negative = coinMatcher.group("sign") != null;
				if (coinsAreCost) negative = !negative;
				total += negative ? -amount : amount;
				continue;
			}

			Matcher essenceMatcher = ESSENCE_LINE.matcher(text);
			if (essenceMatcher.find()) {
				if (includeEssence) {
					int count = Integer.parseInt(essenceMatcher.group("count"));
					total += count * priceOfEssence(essenceMatcher.group("name"));
				}
				continue;
			}

			total += priceOf(text.trim());
		}
		return total;
	}

	/** Prices a single already-stripped lore line as a plain reward line — coins/essence/named item,
	 *  always additive, never treated as a cost. For a scoped multi-line scan like CroesusFeature's own
	 *  "only the Contents section, cost handled separately" parsing, where the blanket {@code coinsAreCost}
	 *  flag on {@link #estimate}/{@link #estimateItemized} can't tell a reward "N Coins" line living INSIDE
	 *  a chest's contents list apart from that same chest's own purchase-cost line by sign alone — a real
	 *  bug found this way (per user report "STILL doesn't show any profit whatsoever"): with coinsAreCost
	 *  scanning the WHOLE lore, a chest containing a straight coins reward got that reward's own value
	 *  incorrectly subtracted as if it were the chest's cost. */
	public static double priceOfRewardLine(String text, boolean includeEssence) {
		Matcher coinMatcher = COINS_LINE.matcher(text);
		if (coinMatcher.find()) {
			double amount = Double.parseDouble(coinMatcher.group("amount").replace(",", ""));
			boolean negative = coinMatcher.group("sign") != null;
			return negative ? -amount : amount;
		}
		Matcher essenceMatcher = ESSENCE_LINE.matcher(text);
		if (essenceMatcher.find()) {
			if (!includeEssence) return 0;
			int count = Integer.parseInt(essenceMatcher.group("count"));
			return count * priceOfEssence(essenceMatcher.group("name"));
		}
		return priceOf(text.trim());
	}

	// Real bug found (per user report — "it still also doesnt count essence"): every essence branch above
	// priced essence through the generic priceOf(String) plain-name lookup — SkyblockItemRepo.findIdByName
	// against the "Wither Essence"/"Crimson Essence"-style text captured by ESSENCE_LINE. Essence isn't a
	// real purchasable item in Hypixel's public items resource at all (it's a currency, not a catalog item),
	// so that reverse-name lookup can never find it — silently pricing every essence line at 0 regardless of
	// includeEssence. Devonian's own real CroesusProfit.kt confirms the actual bazaar product id shape
	// directly: {@code "ESSENCE_$type"} (e.g. ESSENCE_WITHER) — built here the same way instead of routed
	// through the item-repo name index.
	private static double priceOfEssence(String essenceName) {
		String type = essenceName.replaceAll("(?i)\\s*Essence$", "").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
		return priceOfInternalName(NeuInternalName.of("ESSENCE_" + type));
	}

	private static double priceOf(String plainName) {
		Double bookPrice = priceOfEnchantedBookName(plainName);
		if (bookPrice != null) return bookPrice;
		String id = SkyblockItemRepo.findIdByName(plainName);
		if (id == null) return 0;
		return priceOfInternalName(NeuInternalName.of(id));
	}

	/** Public so other reward-value scanners with their own NBT-id-first lookup (InstanceChestProfitFeature)
	 *  can fall back to this specific-enchant-book-name special case too, instead of duplicating the
	 *  ENCHANTED_BOOK_LINE regex/ULTIMATE_ENCHANTS set. Null (not 0) when {@code plainName} doesn't match the
	 *  "Enchanted Book (Name Level)" shape at all, OR when it does but genuinely prices at 0 — the caller's own
	 *  "no repo match"/"zero price" distinction should still apply in the latter case, not silently swallow it
	 *  as "not a book". */
	public static Double priceOfEnchantedBookName(String plainName) {
		Matcher bookMatcher = ENCHANTED_BOOK_LINE.matcher(plainName);
		if (!bookMatcher.matches()) return null;
		int level = RomanNumeralUtil.parseLevel(bookMatcher.group(2));
		if (level <= 0) return null;
		String enchantName = bookMatcher.group(1).trim().toUpperCase(Locale.ROOT).replace(' ', '_');
		String prefix = ULTIMATE_ENCHANTS.contains(enchantName) ? "ULTIMATE_" : "";
		double price = priceOfInternalName(NeuInternalName.of("ENCHANTMENT_" + prefix + enchantName + "_" + level));
		return price > 0 ? price : null;
	}

	/** Companion to {@link #priceOfEnchantedBookName} for the case where a book reward's own display name
	 *  never reveals the enchant at all (a bare "Enchanted Book") — scans the stack's real lore lines for a
	 *  bare "Name Level" line instead and prices it the same way. Null if no lore line both matches the
	 *  shape AND parses to a real level/enchant price — including when {@code stack} has no lore at all. */
	public static Double priceOfBareEnchantedBookLore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return null;
		for (Component line : lore.lines()) {
			String text = COLOR_CODE.matcher(line.getString()).replaceAll("").trim();
			Matcher matcher = BARE_ENCHANT_LORE_LINE.matcher(text);
			if (!matcher.matches()) continue;
			int level = RomanNumeralUtil.parseLevel(matcher.group(2));
			if (level <= 0) continue;
			String enchantName = matcher.group(1).trim().toUpperCase(Locale.ROOT).replace(' ', '_');
			String prefix = ULTIMATE_ENCHANTS.contains(enchantName) ? "ULTIMATE_" : "";
			double price = priceOfInternalName(NeuInternalName.of("ENCHANTMENT_" + prefix + enchantName + "_" + level));
			if (price > 0) return price;
		}
		return null;
	}

	// Real bug found (per user report — "Instance Chest Profit: fix books still pricing at 0 (auction not
	// falling back to bazaar)"): this used to check Bazaar FIRST and return `price.sellPrice()` immediately
	// whenever a BazaarPrice object existed at all — even when its sellPrice() was 0 (a real bazaar product
	// with no current sell orders, which enchanted books routinely are), short-circuiting before Auction was
	// ever tried. Also didn't match the user's own explicit priority for every other price lookup in this
	// codebase (Auction, then Bazaar, then NPC sell — see ItemPriceUtil.priceOf's doc comment). Reordered to
	// match exactly, with the same ">0" guard on every source so a zero/absent price at any step correctly
	// falls through to the next one instead of "winning" by default.
	private static double priceOfInternalName(NeuInternalName internalName) {
		AuctionApi.AuctionPrice auction = AuctionApi.getPrice(internalName.asString());
		if (auction != null && auction.medianBinPrice() > 0) return auction.medianBinPrice();
		BazaarApi.BazaarPrice price = internalName.getBazaarPrice();
		if (price != null && price.sellPrice() > 0) return price.sellPrice();
		Double npcSellPrice = internalName.getNpcSellPrice();
		return npcSellPrice != null && npcSellPrice > 0 ? npcSellPrice : 0;
	}
}
