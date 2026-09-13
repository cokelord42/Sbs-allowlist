package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.Slot;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request ("I need it to display at the bottom of an auction what day and time it ends, basically
 * like Monday the 19th, 23:07 or whatever. It should automatically detect the time on auctions. The lore
 * line is 'Ends in: time'... Make it a module aswell if people don't want it, and allow users to change the
 * color and add bold and italic"): reads Hypixel's own real "Ends in: 01h 05m 31s"-style lore line — present
 * on every auction listing tooltip (Auction House browse/search results, Your Bids, Your Auctions alike) —
 * and appends a real absolute day/time line at the bottom of that same tooltip, computed as
 * {@code now + the parsed countdown}, so it never goes stale between hovers.
 *
 * <p>Uses the real Fabric {@link ItemTooltipCallback} event (the same mechanism {@code EnchantTooltipFilter}/
 * {@code InventoryTooltipFilter} already use elsewhere in this codebase) to append directly to the actual
 * tooltip Minecraft renders, rather than drawing a separate overlay — since the whole point is "add this to
 * what's already shown", not build a second thing next to it.
 *
 * <p>Per user report ("Auction timers doesnt work, and for some reason it has toggles for chat and title
 * notifications? It should not notify anything, it should literally just display the time... by doing the
 * math on the 'Ends in:' lore line... It should then place the final time in the item lore"): the earlier
 * version's session-tracked-timer/chat-sound-title notification system is gone entirely — this is a pure
 * tooltip-append, nothing else. It also had a real bug explaining "doesn't work": {@link #ENDS_IN_LINE} was
 * matched against the raw, un-stripped tooltip line text, but Hypixel's real lore carries literal {@code §}
 * color codes inline (confirmed the same way in {@code CroesusFeature}/{@code ChestRollingFeature} — see
 * their own doc comments) — e.g. {@code "§7Ends in: §a01h 05m 31s"} — so the match against the literal text
 * {@code "Ends in: "} immediately followed by digits never actually succeeded on a real tooltip. Now stripped
 * with {@link #COLOR_CODE} first, matching every other lore-line parser in this codebase.
 *
 * <p>Per user follow-up report ("Auction timers doesnt work. Im looking at items on the auction house, make
 * sure they match others items aswell, literally any item on the auction house should show a timer"): a
 * SECOND real bug on top of the color-code fix above — most real Hypixel auctions run 1-2 real days, and a
 * listing with more than an hour left shows a lore line like {@code "Ends in: 1d 3h"} (days, no minutes/
 * seconds at all) or {@code "Ends in: 3h 12m"} (no seconds) — {@link #ENDS_IN_LINE}'s old shape required a
 * literal seconds group, so it only ever matched auctions in their very last minute, i.e. almost none of what
 * "literally any item" implies. Added an optional days group and made minutes AND seconds both optional (only
 * hours was already optional) so every real Hypixel countdown shape matches. Also scoped to the real Auction
 * House browse screen's own item slots (user-confirmed real slot indices: 11-16, 20-25, 29-34, 38-43 — a
 * 6-row bordered grid, the same "content slots inside a decorative border" shape {@code CroesusFeature}'s own
 * run-list slots use, just numbered one lower since this screen's border differs) via the hovered-slot
 * accessor, rather than pattern-matching every tooltip in the game — narrower, and avoids any chance of a
 * coincidental "Ends in:" match outside the real Auction House screen ever appending a fake timer.
 */
public class AuctionTimersFeature extends Feature {
	// Optional-days/optional-hours/optional-minutes/optional-seconds — real Hypixel Auction House countdowns
	// shrink which units are shown as the auction gets closer to ending (e.g. "1d 3h", "3h 12m", "45m 12s",
	// "38s"), so every component needs to be independently optional; only requires at least one to be present.
	private static final Pattern ENDS_IN_LINE =
		Pattern.compile("Ends in: (?:(\\d+)d ?)?(?:(\\d+)h ?)?(?:(\\d+)m ?)?(?:(\\d+)s)?");
	private static final Pattern COLOR_CODE = Pattern.compile("§.");

	// Real slot indices of the Auction House browse screen's item grid (user-confirmed in-game) — see this
	// class's own doc comment for why detection is scoped to these instead of matching any tooltip anywhere.
	private static boolean isAuctionHouseSlotIndex(int index) {
		return (index >= 11 && index <= 16) || (index >= 20 && index <= 25)
			|| (index >= 29 && index <= 34) || (index >= 38 && index <= 43);
	}

	private int textColor = 0xFFFFFF55;
	private boolean bold = false;
	private boolean italic = true;

	private static boolean listenersRegistered = false;
	private static AuctionTimersFeature instance;

	public AuctionTimersFeature() {
		super("auction_timers", "Auction Timers", FeatureCategory.INVENTORY, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (instance != null && instance.isEnabled() && instance.isHoveringAuctionHouseSlot()) instance.onTooltip(lines);
		});
	}

	/** Real Auction House browse screen check — see this class's own doc comment for why detection is scoped
	 *  to the hovered slot's real index instead of matching any tooltip in the game. */
	private boolean isHoveringAuctionHouseSlot() {
		var screen = Minecraft.getInstance().gui.screen();
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return false;
		Slot hovered = ((AbstractContainerScreenAccessor) containerScreen).skyblocksimplified$getHoveredSlot();
		return hovered != null && isAuctionHouseSlotIndex(hovered.index);
	}

	private void onTooltip(java.util.List<Component> lines) {
		for (Component line : lines) {
			String plain = COLOR_CODE.matcher(line.getString()).replaceAll("");
			Matcher m = ENDS_IN_LINE.matcher(plain);
			if (!m.find()) continue;
			int days = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
			int hours = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
			int minutes = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
			int seconds = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
			if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) continue;
			long totalSeconds = days * 86400L + hours * 3600L + minutes * 60L + seconds;
			long endMillis = System.currentTimeMillis() + totalSeconds * 1000L;

			Style style = Style.EMPTY.withBold(bold).withItalic(italic).withColor(TextColor.fromRgb(textColor & 0xFFFFFF));
			// Appended to the true end of the tooltip (per the user's own "at the bottom of an auction"
			// wording), not spliced in right after the "Ends in:" line itself.
			lines.add(Component.literal(formatEndTime(endMillis)).setStyle(style));
			return;
		}
	}

	private static String formatEndTime(long millis) {
		LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
		String dayName = dt.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
		int day = dt.getDayOfMonth();
		String time = String.format(Locale.ROOT, "%02d:%02d", dt.getHour(), dt.getMinute());
		return dayName + " the " + day + ordinalSuffix(day) + ", " + time;
	}

	private static String ordinalSuffix(int day) {
		if (day >= 11 && day <= 13) return "th";
		return switch (day % 10) {
			case 1 -> "st";
			case 2 -> "nd";
			case 3 -> "rd";
			default -> "th";
		};
	}

	public int getTextColor() { return textColor; }
	public void setTextColor(int value) { textColor = value; }
	public boolean isBold() { return bold; }
	public void setBold(boolean value) { bold = value; }
	public boolean isItalic() { return italic; }
	public void setItalic(boolean value) { italic = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("textColor", textColor);
		obj.addProperty("bold", bold);
		obj.addProperty("italic", italic);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!(el instanceof JsonObject obj)) return;
		if (obj.has("textColor")) textColor = obj.get("textColor").getAsInt();
		if (obj.has("bold")) bold = obj.get("bold").getAsBoolean();
		if (obj.has("italic")) italic = obj.get("italic").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Shows the real day and time an auction ends, appended directly to its tooltip.";
	}
}
