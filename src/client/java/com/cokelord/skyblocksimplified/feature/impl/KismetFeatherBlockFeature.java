package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.BazaarApi;
import com.cokelord.skyblocksimplified.api.NeuInternalName;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocks using a Kismet Feather (clicking a dedicated "Reroll Chest" GUI button inside a dungeon reward
 * chest's own contents screen — not the raw feather item itself, see {@link #isKismetFeather}'s own doc
 * comment for the real bug that was) on chests the user has flagged as too risky to gamble on — per user
 * request, later corrected — "it should be 'Block on non-Bedrock chest'. Bedrock chests hold the highest
 * value": "New module: Kismet feathers. Subtoggles: Prevent using kismet feathers on non-Bedrock
 * chests... Prevent using kismet feathers on rare drops... Allow the user to select a threshold... Signal
 * this by rendering a barrier block above the kismet feather like we're doing for the garden visitors."
 * That last part is RareRewardWarningFeature's own real vanilla-barrier-icon-over-the-blocked-item
 * mechanism, reused here.
 *
 * <p>Reward-value estimation reuses InstanceChestProfitFeature's own real approach (reward row 2, cost read
 * from whichever item's lore actually has a "Cost" line) rather than a separate implementation, so a
 * chest's Kismet-block decision always agrees with what its own profit counter shows.
 */
public class KismetFeatherBlockFeature extends Feature {
	private static final java.util.Set<String> CHEST_TYPE_NAMES = java.util.Set.of(
		"Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock", "Free", "Paid");
	private static final Pattern COLOR_CODE = Pattern.compile("§.");
	// Real perf finding: this used to be compiled fresh inside parseFlexibleNumber() every call — and that
	// method is reachable from renderBarriersIfBlocked()'s per-frame isBlocked() check whenever "Prevent
	// using kismet feathers on rare drops" is on, so a reward chest screen with that subtoggle enabled was
	// recompiling this regex 60+ times a second for as long as the screen stayed open. Hoisted to a cached
	// static field like every other Pattern in this class already is — same behavior, just not rebuilt from
	// source every frame.
	private static final Pattern THRESHOLD_SUFFIX = Pattern.compile("(?i)^([\\d.,]+)\\s*([kmb])$");
	private static final Pattern COINS_LINE = Pattern.compile("(?<amount>[\\d,]+) Coins");
	private static final Pattern COST_LINE = Pattern.compile("Cost:?\\s*(?<amount>[\\d,]+)");
	private static final int ROW2_START = 9;
	private static final int ROW2_END = 17;
	private static final int SLOT_SIZE = 16;

	private boolean preventNonBedrock = false;
	private boolean preventRareDrops = false;
	private String thresholdInput = "500,000";

	public KismetFeatherBlockFeature() {
		super("kismet_feather_block", "Kismet Feathers", FeatureCategory.COMBAT, false);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				// Real bug found (per user report — "the barrier block renders above lore and everything"):
				// afterExtract fires AFTER the vanilla tooltip render in this game version — same real bug
				// RareRewardWarningFeature's own doc comment already documents and works around. Skipped here
				// whenever a tooltip is about to show; the PreTooltipRenderRegistry listener below (same
				// mechanism, registered once, globally) draws it underneath instead.
				if (isEnabled() && !isTooltipAboutToShow(containerScreen)) renderBarriersIfBlocked(graphics, containerScreen);
			});
		});
		com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled()) return;
			if (!isTooltipAboutToShow(screen)) return;
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			renderBarriersIfBlocked(graphics, containerScreen);
		});
	}

	private boolean isTooltipAboutToShow(AbstractContainerScreen<?> screen) {
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		Slot hovered = accessor.skyblocksimplified$getHoveredSlot();
		return hovered != null && !hovered.getItem().isEmpty();
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		ContainerClickRegistry.setRule(getId(), this::shouldCancelClick);
	}

	@Override
	protected void onDisable() {
		ContainerClickRegistry.clearRule(getId());
	}

	private boolean shouldCancelClick(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		if (!isEnabled()) return false;
		ItemStack stack = slot.getItem();
		if (!isKismetFeather(stack)) return false;
		Minecraft mc = Minecraft.getInstance();
		return mc.gui.screen() instanceof AbstractContainerScreen<?> screen && isBlocked(screen);
	}

	private void renderBarriersIfBlocked(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		// Real bug found (per user report — "it also renders in the boss scrolling animation"): the same
		// chest-contents screen Chest Rolling's own CS:GO-style reveal plays on top of is still, mechanically,
		// the exact screen this feature watches — its barrier icon kept drawing over that reveal the whole
		// time it played. Skipped while a roll is actively animating on this screen.
		if (ChestRollingFeature.shouldReplaceRender(screen)) return;
		if (!isBlocked(screen)) return;
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();
		for (Slot slot : screen.getMenu().slots) {
			if (!isKismetFeather(slot.getItem())) continue;
			int x = leftPos + slot.x;
			int y = topPos + slot.y;
			graphics.item(new ItemStack(Items.BARRIER), x, y);
		}
	}

	// Real bug found (per user report — "Kismet feather STILL isnt blocked... It needs to detect the item
	// named 'Reroll Chest' in the chest gui"): the clickable slot inside a reward chest's own contents
	// screen isn't the raw Kismet Feather item at all — it's a dedicated GUI action button item Hypixel
	// itself names "Reroll Chest" (consumes a feather from the player's inventory when clicked). Matching
	// against "Kismet Feather" here could never match anything in that screen, so isBlocked()/shouldCancel-
	// Click() were unreachable there no matter what chestTypeOf() or the profit math computed.
	private static final String REROLL_CHEST_NAME = "Reroll Chest";

	private static boolean isKismetFeather(ItemStack stack) {
		if (stack.isEmpty()) return false;
		return REROLL_CHEST_NAME.equals(COLOR_CODE.matcher(stack.getHoverName().getString()).replaceAll(""));
	}

	/** True if this screen is a known reward chest AND at least one enabled rule says Kismet Feather use
	 *  should currently be blocked in it. */
	private boolean isBlocked(AbstractContainerScreen<?> screen) {
		String chestType = chestTypeOf(screen);
		if (chestType == null) return false;
		// Per user correction ("I actually did something dumb... it should be 'Block on non-Bedrock chest'.
		// Bedrock chests hold the highest value"): was gating on Obsidian, one tier below the real highest
		// chest — renamed the rule and its detection target to match what was actually meant.
		if (preventNonBedrock && !"Bedrock".equals(chestType)) return true;
		if (preventRareDrops) {
			Double threshold = parseFlexibleNumber(thresholdInput);
			if (threshold != null && estimateChestProfit(screen) > threshold) return true;
		}
		return false;
	}

	/** Real bug found (per user report — "Kismet feather is not being blocked in the chests at croesus"):
	 *  this used to require the WHOLE stripped title to exactly equal one of the 8 known type names, which
	 *  only ever matches a live just-earned chest's own bare screen title. Croesus's replay-a-past-chest
	 *  screen apparently wraps or decorates that title differently (its own branding, a prefix/suffix — real
	 *  exact text not independently confirmed, only that an equals-match against it fails), so it never
	 *  matched at all there and this module silently never activated once a Kismet Feather appeared in a
	 *  Croesus-opened chest. Loosened to "the stripped title CONTAINS one of the known names" instead — safe
	 *  to be lenient here since chestTypeOf() is only ever consulted once a real Kismet Feather item has
	 *  already been found among this exact screen's own slots (see shouldCancelClick/renderBarriersIfBlocked),
	 *  an extremely narrow context for a false match to matter in. */
	private static String chestTypeOf(AbstractContainerScreen<?> screen) {
		String title = COLOR_CODE.matcher(screen.getTitle().getString()).replaceAll("").trim();
		for (String type : CHEST_TYPE_NAMES) {
			if (title.contains(type)) return type;
		}
		return null;
	}

	/** Same real approach InstanceChestProfitFeature uses (reward row 2, cost from whichever item's lore
	 *  actually has a "Cost" line) — kept as its own copy here (not shared) since the two features have no
	 *  other reason to depend on each other, but deliberately mirrors that logic exactly so this module's
	 *  block decision always agrees with what the profit counter itself shows for the same chest. */
	private static double estimateChestProfit(AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return 0;
		Inventory playerInventory = mc.player.getInventory();

		double totalReward = 0;
		int lastSlotIndex = Math.min(ROW2_END, screen.getMenu().slots.size() - 1);
		for (int index = ROW2_START; index <= lastSlotIndex; index++) {
			Slot slot = screen.getMenu().getSlot(index);
			if (slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = stack.getHoverName().getString();
			String plainName = COLOR_CODE.matcher(name).replaceAll("").trim();

			Matcher coinMatcher = COINS_LINE.matcher(name);
			if (coinMatcher.find()) {
				totalReward += Double.parseDouble(coinMatcher.group("amount").replace(",", ""));
				continue;
			}
			String id = SkyblockItemRepo.findIdByName(plainName);
			if (id == null) continue;
			NeuInternalName internalName = NeuInternalName.of(id);
			BazaarApi.BazaarPrice bazaarPrice = internalName.getBazaarPrice();
			Double npcSellPrice = internalName.getNpcSellPrice();
			double unitPrice = bazaarPrice != null ? bazaarPrice.sellPrice() : (npcSellPrice != null ? npcSellPrice : 0);
			totalReward += unitPrice * stack.getCount();
		}

		double cost = 0;
		for (Slot slot : screen.getMenu().slots) {
			if (slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) continue;
			boolean found = false;
			for (Component component : lore.lines()) {
				String text = COLOR_CODE.matcher(component.getString()).replaceAll("");
				Matcher costMatcher = COST_LINE.matcher(text);
				if (!costMatcher.find()) continue;
				try {
					cost = Double.parseDouble(costMatcher.group("amount").replace(",", ""));
					found = true;
				} catch (NumberFormatException ignored) {}
				break;
			}
			if (found) break;
		}
		return totalReward - cost;
	}

	/** Accepts however the user wants to type it — "100K", "100,000", "100000", "100.000" — per user
	 *  request. A trailing k/m/b (case-insensitive) is a thousand/million/billion multiplier; commas are
	 *  always thousands separators; a dot is treated as one too specifically when it's followed by exactly
	 *  3 digits (matching the European-style "100.000" example) rather than a genuine decimal amount like
	 *  "1.5" — anything else is parsed as a plain number. Null on anything unparseable. */
	static Double parseFlexibleNumber(String input) {
		if (input == null) return null;
		String s = input.trim();
		if (s.isEmpty()) return null;

		Matcher suffixMatcher = THRESHOLD_SUFFIX.matcher(s);
		double multiplier = 1;
		String numberPart = s;
		if (suffixMatcher.matches()) {
			numberPart = suffixMatcher.group(1);
			multiplier = switch (Character.toLowerCase(suffixMatcher.group(2).charAt(0))) {
				case 'k' -> 1_000;
				case 'm' -> 1_000_000;
				case 'b' -> 1_000_000_000;
				default -> 1;
			};
		}

		numberPart = numberPart.replace(",", "");
		int lastDot = numberPart.lastIndexOf('.');
		if (lastDot >= 0) {
			String afterDot = numberPart.substring(lastDot + 1);
			if (afterDot.length() == 3 && afterDot.chars().allMatch(Character::isDigit)) {
				numberPart = numberPart.substring(0, lastDot) + afterDot;
			}
		}

		try {
			return Double.parseDouble(numberPart) * multiplier;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public boolean isPreventNonBedrock() { return preventNonBedrock; }
	public void setPreventNonBedrock(boolean value) { preventNonBedrock = value; }
	public boolean isPreventRareDrops() { return preventRareDrops; }
	public void setPreventRareDrops(boolean value) { preventRareDrops = value; }
	public String getThresholdInput() { return thresholdInput; }
	public void setThresholdInput(String value) { thresholdInput = value == null ? "" : value; }
	public String getThresholdDisplay() {
		Double parsed = parseFlexibleNumber(thresholdInput);
		return parsed != null ? String.format(Locale.ROOT, "%,.0f", parsed) : "invalid";
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("preventNonBedrock", preventNonBedrock);
		obj.addProperty("preventRareDrops", preventRareDrops);
		obj.addProperty("thresholdInput", thresholdInput);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("preventNonBedrock")) preventNonBedrock = obj.get("preventNonBedrock").getAsBoolean();
		if (obj.has("preventRareDrops")) preventRareDrops = obj.get("preventRareDrops").getAsBoolean();
		if (obj.has("thresholdInput")) thresholdInput = obj.get("thresholdInput").getAsString();
	}

	@Override
	public String getDescription() {
		return "Blocks you from accidentally using a Kismet Feather to reroll a dungeon reward chest.";
	}
}
