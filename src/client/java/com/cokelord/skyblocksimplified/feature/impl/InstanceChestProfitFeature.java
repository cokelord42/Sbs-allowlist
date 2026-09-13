package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.ItemPriceUtil;
import com.cokelord.skyblocksimplified.api.NeuInternalName;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimated profit of a dungeon/Kuudra reward chest, shown inline on the GUI's own title bar (e.g.
 * "Obsidian Chest  +45,231") — colored red on loss, yellow under 500,000 profit, green at or above it (see
 * GREEN_THRESHOLD).
 *
 * <p>Full rewrite per an exact user spec, replacing the previous "scan the whole container for any
 * coins-line item" approach (which had gone through several rounds of drift and was reported broken again):
 * reward items are read from a FIXED slot range — the chest GUI's own second row, slots 9 through 13 (the
 * user's own literal numbers: "before slot 14, or actually 13") — and the chest's own cost is read from a
 * FIXED slot, 31, rather than searched for. Both fixed indices come directly from the user's own confirmed
 * report of this exact GUI's real layout, not a guess.
 */
public class InstanceChestProfitFeature extends Feature {
	// Fixed from an earlier "ends with ' Chest'" regex that no longer matched anything: SkyHanni's own
	// InstanceChestAPI.CroesusChestType.getByInventoryName() confirms Hypixel now titles these chests
	// with just the bare type — "Emerald", not "Emerald Chest" — with " Chest"/" Chest Chest" suffixes
	// (the latter a known Hypixel Kuudra-chest naming bug) stripped before an EXACT match against one of
	// the 8 known type names, not a "contains" check.
	private static final java.util.Set<String> CHEST_TYPE_NAMES = java.util.Set.of(
		"Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock", "Free", "Paid");
	private static final Pattern COLOR_CODE = Pattern.compile("§.");
	// Plain text, no §-code requirement — see ChestValueEstimator's doc comment for why (this had the
	// same bug: matched against Component#getString(), which already strips all formatting). Matches a
	// literal "4,000,000 Coins" style line, per the user's own exact example.
	private static final Pattern COINS_LINE = Pattern.compile("(?<amount>[\\d,]+) Coins");
	// Per user report ("Include essence profit also doesnt add, but i think its because the essence is
	// displayed weird, like Undead Essence x26 for example, which the mod may have issues detecting"):
	// essence rewards bake their stack count straight into the display name instead of a real ItemStack
	// count, so a plain SkyblockItemRepo lookup on "Undead Essence x26" never matches "Undead Essence".
	// Strips a trailing " xN" and uses N as the quantity instead of stack.getCount() when present.
	private static final Pattern NAME_COUNT_SUFFIX = Pattern.compile("^(?<name>.+) x(?<count>\\d+)$");
	// Real reward-item slot range per user's own exact spec ("Search the second row within chests... All
	// items are before slot 14, or actually 13 since its always -1 for some reason") — the chest GUI's
	// second row, capped at 13 rather than the row's full normal width (9-17), per the user's own literal
	// numbers for this specific GUI's real layout.
	private static final int REWARD_SLOT_START = 9;
	private static final int REWARD_SLOT_END = 13;
	// Real chest-cost slot per user's own exact spec ("detecting the chest item in the gui (slot 31
	// accounted for the -1)") — a fixed index instead of searching the whole container for a coins-line
	// item, which is what previously misfired.
	private static final int CHEST_COST_SLOT = 31;
	// Profit color thresholds per user request.
	private static final double GREEN_THRESHOLD = 500_000;

	// Real bug found (per user report — full container dump came back completely empty, every single slot,
	// not just the reward/cost ones): computeText() used to run synchronously inside ScreenEvents.AFTER_INIT,
	// which fires the instant the screen OBJECT is constructed client-side (right after the open-screen
	// packet) — but the menu's actual item stacks arrive in a separate, later container-content-sync packet,
	// so at that exact instant EVERY slot in the container reads empty regardless of layout. The previous
	// round's "full container dump" diagnostic (added on the theory that the fixed slot indices were simply
	// wrong for this chest tier) proved that theory wrong — the indices were never the problem, the TIMING
	// was. Fixed by polling on tick instead of computing immediately: waits until the container actually has
	// at least one non-empty slot (or a short timeout elapses, in case a real chest ever is empty), computes
	// once, then stops — preserving the original "don't repeat a heavy price-lookup scan every frame" intent.
	private static final int MAX_WAIT_TICKS = 40; // 2 real seconds — generous margin over any normal sync delay.
	private AbstractContainerScreen<?> pendingScreen;
	private int pendingWaitTicks;

	public InstanceChestProfitFeature() {
		super("instance_chest_profit", "Instance Chest Profit", FeatureCategory.COMBAT, false);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			String rawTitle = containerScreen.getTitle().getString();
			String title = COLOR_CODE.matcher(rawTitle).replaceAll("");
			title = title.replace(" Chest Chest", "").replace(" Chest", "");
			if (!CHEST_TYPE_NAMES.contains(title)) return;
			cachedText = null;
			pendingScreen = containerScreen;
			pendingWaitTicks = 0;
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (!isEnabled() || cachedText == null) return;
				renderValue(graphics, containerScreen);
			});
		});
	}

	@Override
	public String getSubcategory() {
		return "Dungeons";
	}

	@Override
	public void onTick(Minecraft client) {
		if (pendingScreen == null) return;
		if (client.gui.screen() != pendingScreen) {
			// Screen closed/replaced before its contents ever synced — abandon rather than compute stale data
			// against a menu that's no longer the one on-screen.
			pendingScreen = null;
			return;
		}
		boolean hasAnyItem = false;
		var allSlots = pendingScreen.getMenu().slots;
		int containerSlotCount = Math.max(0, allSlots.size() - 36);
		for (Slot slot : allSlots) {
			if (slot.index >= containerSlotCount) break;
			if (!slot.getItem().isEmpty()) { hasAnyItem = true; break; }
		}
		if (hasAnyItem || ++pendingWaitTicks >= MAX_WAIT_TICKS) {
			cachedText = computeText(pendingScreen);
			pendingScreen = null;
		}
	}

	private String cachedText;

	private void renderValue(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		// Real bug found (per user report — "the csgo scrolling animation still shows the negative chest
		// profit number while scrolling"): ChestRollingFeature replaces this same screen's render with its own
		// full-screen dim + card strip via a separate mixin hook, but this feature's title-bar text is drawn
		// through an independent ScreenEvents.afterExtract hook that mixin never touches — so the two composited
		// every frame the roll animation was up, bleeding this text through the dim overlay.
		if (ChestRollingFeature.shouldReplaceRender(screen)) return;
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int x = accessor.skyblocksimplified$getLeftPos() + accessor.skyblocksimplified$getTitleLabelX();
		int y = accessor.skyblocksimplified$getTopPos() + accessor.skyblocksimplified$getTitleLabelY();
		int titleWidth = mc.font.width(screen.getTitle());
		graphics.text(mc.font, Component.literal(cachedText), x + titleWidth + 6, y, 0xFFFFFFFF);
	}

	/** The heavy per-open scan — see the AFTER_INIT registration's own doc comment for why this now runs once
	 *  per GUI open instead of every frame. */
	private String computeText(AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		if (ChestRollingFeature.shouldReplaceRender(screen)) return null;

		double cost = findChestCost(screen);

		double totalReward = 0;
		boolean anyUnknown = false;
		StringBuilder scanLog = new StringBuilder();
		for (Slot slot : screen.getMenu().slots) {
			if (slot.index < REWARD_SLOT_START || slot.index > REWARD_SLOT_END) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;

			String name = stack.getHoverName().getString();
			String plainName = COLOR_CODE.matcher(name).replaceAll("").trim();

			String lookupName = plainName;
			int quantity = stack.getCount();
			Matcher countSuffix = NAME_COUNT_SUFFIX.matcher(plainName);
			if (countSuffix.matches()) {
				lookupName = countSuffix.group("name");
				quantity = Integer.parseInt(countSuffix.group("count"));
			}
			double unitPrice = priceOf(stack, lookupName, slot.index);
			// Real bug found (per user report — "multi-book price aggregation broken: works for a single book
			// like Rejuvenate, but breaks — even for the FIRST book — once multiple books are present, yielding
			// '?'"): priceOf() returns NaN for one specific genuinely-unpriceable reward (a bare "Enchanted
			// Book" whose lore fallback also failed to resolve an enchant), and `totalReward += lineValue` used
			// to add that NaN straight into the running sum — once ANY addend is NaN, a double sum is
			// permanently NaN from that point on, silently discarding every other slot's already-known price
			// too (Rejuvenate's own correct value included). A second, unrelated unknown book in the same chest
			// therefore blanked out the whole total instead of just being the one thing that's actually
			// unknown. Unknown slots are now skipped from the running sum (their value simply isn't counted,
			// same as essences with no resolvable price above) and tracked separately via anyUnknown, so every
			// known price still contributes to the real total.
			if (Double.isNaN(unitPrice)) {
				anyUnknown = true;
				scanLog.append("\n  slot ").append(slot.index).append(" \"").append(plainName).append("\": unknown (excluded)");
				continue;
			}
			double lineValue = unitPrice * quantity;
			totalReward += lineValue;
			scanLog.append("\n  slot ").append(slot.index).append(" \"").append(plainName).append("\": ").append(lineValue);
		}

		double profit = totalReward - cost;
		// Per user request ("I would much rather you just add a question mark to the profit counter to show
		// that it is uncertain... instead of showing 0"): still surfaces uncertainty rather than a falsely
		// confident number when part of the reward pool couldn't be priced — now as a trailing "?" appended to
		// the real known total (from the slots that DID resolve) instead of discarding that known total
		// entirely, so a chest with one unpriceable book alongside other correctly-priced rewards still shows
		// what's actually known plus an honest "some of this is uncertain" marker.
		String color;
		String text;
		color = profit < 0 ? "§c" : profit >= GREEN_THRESHOLD ? "§a" : "§e";
		// Per user report ("The question mark at the end of the profit calc should be gray. It otherwise
		// blends in and kind of looks like an 8"): its own §7 color code, applied after the number's own
		// color prefix so only the "?" itself re-colors, not the whole line.
		text = (profit > 0 ? "+" : "") + String.format(Locale.ROOT, "%,.0f", profit) + (anyUnknown ? "§7?" : "");
		return color + text;
	}

	/** Resolves a reward slot's unit price — Auction lowest-BIN first, then Bazaar (books/essences etc that
	 *  don't trade on Auction), per user spec. Shares {@link ItemPriceUtil#priceOf} with Chest Rolling/
	 *  crafting-cost estimation (Auction median-BIN, then Bazaar, then NPC sell as a last-resort fallback).
	 *  0 (with a throttled debug log) if neither lookup finds an id, or the id has no resolvable price
	 *  through any of those. */
	private static double priceOf(ItemStack stack, String plainName, int slotIndexForLog) {
		Double namedBookPrice = com.cokelord.skyblocksimplified.combat.ChestValueEstimator.priceOfEnchantedBookName(plainName);
		if (namedBookPrice != null) return namedBookPrice;
		String id = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemId(stack);
		if (id == null) id = SkyblockItemRepo.findIdByName(plainName);
		if (id == null || "ENCHANTED_BOOK".equals(id)) {
			// Real bug found (per user report — "The instance chest profit does not detect books. It needs
			// to read lore on enchanted books to find out the book type and tier"): the earlier round assumed
			// a bare "Enchanted Book" display name meant the enchant was genuinely unknowable pre-claim, and
			// surfaced NaN ("?") instead — but the real enchant name+tier is actually right there in the
			// stack's own LORE (a standalone "Sharpness VII"-style line), just never in the display name.
			// Try that before giving up.
			if ("Enchanted Book".equals(plainName)) {
				Double lorePrice = com.cokelord.skyblocksimplified.combat.ChestValueEstimator.priceOfBareEnchantedBookLore(stack);
				return lorePrice != null ? lorePrice : Double.NaN;
			}
			// Real finding: confirmed live against Hypixel's own `/v2/resources/skyblock/items` catalog —
			// every boss-specific essence type EXCEPT "True Essence" simply isn't in it at all (non-tradeable
			// per-boss currency, not a real item with a bazaar/NPC/auction price). No real value to attach —
			// counted as 0 rather than guessed.
			return 0;
		}
		Double unitPrice = ItemPriceUtil.priceOf(NeuInternalName.of(id));
		if (unitPrice == null) {
			return 0;
		}
		return unitPrice;
	}

	/** Real chest cost — read from the fixed {@link #CHEST_COST_SLOT}, searching that one item's lore for a
	 *  line matching {@link #COINS_LINE} (e.g. "4,000,000 Coins"), per the user's own exact spec. Returns 0
	 *  (with a throttled debug log) if that slot is empty or has no such line.
	 *
	 *  <p>Per user report ("sometimes users have already opened a chest and want to open another one, but
	 *  the mod doesnt take the chest key into consideration when doing the profit calculation"): the same
	 *  item also carries a "Dungeon Chest Key" line under its coins-cost line whenever opening it will
	 *  actually consume one of those instead of/alongside coins — factored in here as the key's current
	 *  lowest bazaar price (its instant-sell value — the real opportunity cost of consuming rather than
	 *  selling one), added onto the coin cost. */
	private static double findChestCost(AbstractContainerScreen<?> screen) {
		var slots = screen.getMenu().slots;
		if (CHEST_COST_SLOT >= slots.size()) return 0;
		Slot costSlot = slots.get(CHEST_COST_SLOT);
		ItemStack stack = costSlot.getItem();
		if (stack.isEmpty()) return 0;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return 0;

		double cost = 0;
		boolean needsChestKey = false;
		for (Component line : lore.lines()) {
			String text = COLOR_CODE.matcher(line.getString()).replaceAll("").trim();
			Matcher matcher = COINS_LINE.matcher(text);
			if (matcher.find()) {
				try {
					cost += Double.parseDouble(matcher.group("amount").replace(",", ""));
				} catch (NumberFormatException ignored) {}
			}
			if (text.contains("Dungeon Chest Key")) needsChestKey = true;
		}
		if (needsChestKey) {
			String keyId = SkyblockItemRepo.findIdByName("Dungeon Chest Key");
			if (keyId != null) {
				var keyPrice = com.cokelord.skyblocksimplified.api.BazaarApi.getPrice(keyId);
				if (keyPrice != null && keyPrice.sellPrice() > 0) cost += keyPrice.sellPrice();
			}
		}
		return cost;
	}

	@Override
	public String getDescription() {
		return "Shows the estimated profit of a dungeon/Kuudra reward chest right on the chest's own title bar.";
	}
}
