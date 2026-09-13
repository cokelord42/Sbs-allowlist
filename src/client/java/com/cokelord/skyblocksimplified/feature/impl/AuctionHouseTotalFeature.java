package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sums every finished auction's sale price in the "Manage Auctions" screen and shows it next to the title
 * bar — same title-bar treatment as Instance Chest Profit. Per user spec: search each item's lore for a
 * line containing the literal phrase "Sold for:" and sum the number right after it, rather than the
 * earlier two-line "Status: Sold" + separate price-line approach (which also matched against literal
 * §-color-code prefixes that Component#getString() already strips, so it never matched anything at all).
 *
 * <p>Per user follow-up request ("the 'auction house total number' renders above the lore. If possible
 * please add another element that renders above it which shows how much all items currently on the auction
 * would be if everything sold"): a second line right below the sold-total, summing every STILL-ACTIVE
 * (not-yet-sold) auction's current bid — or its starting/BIN price if nobody's bid yet — the amount the
 * player would walk away with if every listing currently up sold right now. Uses the current highest bid
 * when one exists (that's what a sale would actually pay out), falling back to Buy It Now price, then
 * starting bid, for listings with no bids yet. Label wording is matched loosely (multiple real Hypixel
 * phrasings, case-insensitive) rather than one exact string, the same lesson the sold-total match above
 * already learned the hard way once.
 */
public class AuctionHouseTotalFeature extends Feature {
	private static final String TITLE = "Manage Auctions";
	// Per user request: the "if sold" total is now its own subtoggle instead of always showing whenever
	// it's nonzero.
	private boolean showActiveTotal = true;
	private static final Pattern SOLD_FOR_LINE = Pattern.compile(".*Sold for:\\s*(?<coins>[\\d,]+).*");
	private static final Pattern TOP_BID_LINE = Pattern.compile("(?i).*(top|highest|current)\\s*bid:\\s*(?<coins>[\\d,]+).*");
	private static final Pattern BUY_NOW_LINE = Pattern.compile("(?i).*buy\\s*it\\s*now:\\s*(?<coins>[\\d,]+).*");
	private static final Pattern STARTING_BID_LINE = Pattern.compile("(?i).*(starting|start)\\s*bid:\\s*(?<coins>[\\d,]+).*");

	public AuctionHouseTotalFeature() {
		super("auction_house_total", "Auction House Total Number", FeatureCategory.INVENTORY, false);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			if (!containerScreen.getTitle().getString().contains(TITLE)) return;
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				// Real bug found (per user report — "the auction house total and current sell texts render
				// above lore"): afterExtract fires AFTER the vanilla tooltip render in this game version —
				// same real bug KismetFeatherBlockFeature's own doc comment already documents and works
				// around. Skipped here whenever a tooltip is about to show; the PreTooltipRenderRegistry
				// listener below (same mechanism, registered once, globally) draws it underneath instead.
				if (isEnabled() && !isTooltipAboutToShow(containerScreen)) renderTotal(graphics, containerScreen);
			});
		});
		com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled()) return;
			if (!isTooltipAboutToShow(screen)) return;
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			if (!containerScreen.getTitle().getString().contains(TITLE)) return;
			renderTotal(graphics, containerScreen);
		});
	}

	private boolean isTooltipAboutToShow(AbstractContainerScreen<?> screen) {
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		Slot hovered = accessor.skyblocksimplified$getHoveredSlot();
		return hovered != null && !hovered.getItem().isEmpty();
	}

	@Override
	public String getSubcategory() {
		return "Inventory";
	}

	private void renderTotal(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		var playerInventory = mc.player.getInventory();

		double soldTotal = 0;
		double activeIfSoldTotal = 0;
		for (Slot slot : screen.getMenu().slots) {
			if (slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;

			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) continue;

			boolean sold = false;
			Double topBid = null, buyNow = null, startingBid = null;
			for (Component line : lore.lines()) {
				String text = line.getString();
				Matcher soldMatcher = SOLD_FOR_LINE.matcher(text);
				if (soldMatcher.matches()) {
					soldTotal += applyAuctionTax(parseCoins(soldMatcher.group("coins")));
					sold = true;
					break;
				}
				if (!showActiveTotal) continue;
				Matcher m;
				if (topBid == null && (m = TOP_BID_LINE.matcher(text)).matches()) topBid = parseCoins(m.group("coins"));
				else if (buyNow == null && (m = BUY_NOW_LINE.matcher(text)).matches()) buyNow = parseCoins(m.group("coins"));
				else if (startingBid == null && (m = STARTING_BID_LINE.matcher(text)).matches()) startingBid = parseCoins(m.group("coins"));
			}
			if (sold || !showActiveTotal) continue;
			// The amount an active listing would actually pay out if it sold right now: whatever the
			// current highest bid is, else the BIN price for a no-bids-yet Buy It Now listing, else the
			// plain starting bid for a no-bids-yet regular auction.
			Double payout = topBid != null ? topBid : (buyNow != null ? buyNow : startingBid);
			if (payout != null) activeIfSoldTotal += applyAuctionTax(payout);
		}

		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int x = accessor.skyblocksimplified$getLeftPos() + accessor.skyblocksimplified$getTitleLabelX();
		int y = accessor.skyblocksimplified$getTopPos() + accessor.skyblocksimplified$getTitleLabelY();
		int titleWidth = mc.font.width(screen.getTitle());
		int lineHeight = mc.font.lineHeight + 1;

		// Active-if-sold renders ABOVE the sold total per the user's own ordering request.
		if (activeIfSoldTotal > 0) {
			String activeText = "§b~+" + String.format(Locale.ROOT, "%,.0f", activeIfSoldTotal) + " if sold";
			graphics.text(mc.font, Component.literal(activeText), x + titleWidth + 6, y - lineHeight, 0xFFFFFFFF);
		}
		if (soldTotal > 0) {
			String soldText = "§6+" + String.format(Locale.ROOT, "%,.0f", soldTotal);
			graphics.text(mc.font, Component.literal(soldText), x + titleWidth + 6, y, 0xFFFFFFFF);
		}
	}

	public boolean isShowActiveTotal() { return showActiveTotal; }
	public void setShowActiveTotal(boolean value) { showActiveTotal = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showActiveTotal", showActiveTotal);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("showActiveTotal")) showActiveTotal = obj.get("showActiveTotal").getAsBoolean();
	}

	// Per user request ("Auction house numbers dont account for taxes... up to 1% tax on auctions/BINs over
	// 1,000,000 coins, capped so it can't bring the result below 1,000,000 coins"): applied per-listing
	// (not on the aggregate total) since that's how Hypixel actually taxes each individual sale.
	private static double applyAuctionTax(double price) {
		if (price <= 1_000_000) return price;
		return Math.max(price * 0.99, 1_000_000);
	}

	private static double parseCoins(String raw) {
		try {
			return Double.parseDouble(raw.replace(",", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Override
	public String getDescription() {
		return "Adds up every sale price shown in the Manage Auctions screen and displays the running total next to the title.";
	}
}
