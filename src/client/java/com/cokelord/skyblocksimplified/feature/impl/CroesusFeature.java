package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.combat.ChestValueEstimator;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.gui.TooltipSuppressRegistry;
import com.cokelord.skyblocksimplified.hud.TabListReader;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.inventory.SlotItemOverrideRegistry;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replacement for the old Croesus Chest Overlay, upgraded to Odin's richer {@code Croesus.kt} behavior:
 * ranks every unopened chest by estimated profit and outlines all of them at once — most profitable in
 * green, second in yellow, everything else in red — and hides (rather than just dims) already-claimed
 * chests. Reuses this codebase's existing {@link ChestValueEstimator} (backed by SBAR's own price repo)
 * instead of Odin's own {@code lb.odtheking.com} price fetch, and the existing {@link
 * PreTooltipRenderRegistry}/{@code AbstractContainerScreenAccessor} plumbing the old feature already used,
 * per the port plan's decision to drop cross-mod-server dependencies.
 *
 * <p>No separate HUD breakdown panel (per user request — the three-color outline on the real chest slots
 * is the whole UI now, not a floating GUI on top of it). Trimmed from Odin's original: the Croesus
 * main-menu (floor-select) claimed/unclaimed coloring and the post-open chest-contents live-profit screen
 * are not ported — this focuses on the chest-selection screen highlighting, the feature's core value.
 */
public class CroesusFeature extends Feature {
	private static final Pattern RUN_NAME = Pattern.compile(".*Catacombs - Flo.*|Kuudra - .*");
	private static final Set<String> CHEST_TYPE_NAMES = Set.of(
		"Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock", "Free", "Paid");
	// Real bug found while investigating the profit-sign bug below: confirmed against devonian's own real
	// CroesusProfit.kt (`if (line == "Already opened!") { data.bought = true; break }`, scanning the exact
	// same chest-item lore this feature reads) that a claimed chest's real lore marker is "Already opened!"
	// — "Opened Chest:" (this constant's previous value) never actually appears in this specific lore at
	// all, so isOpenedChest() always returned false and Hide Claimed never actually hid anything.
	private static final String OPENED_CHEST_PHRASE = "Already opened!";
	// Real bug found (per user report — "The highlight most profit also doesnt go off the instance chest
	// profit. The instance chest profit says -1 million which is right but its somehow the most profit
	// chest according to the highlighting"): confirmed against InstanceChestProfitFeature's own
	// findChestCost, which (per its own doc comment) already accounts for this — some chests require
	// spending an actual "Dungeon Chest Key" alongside/instead of coins, and its opportunity cost (its own
	// bazaar sell price) has to be added to the coin cost or the chest looks far more profitable than it
	// really is. computeChestProfit below never checked for this line at all, so a chest whose real net
	// value is deep in the negative (once the key's real value is subtracted, same as Instance Chest Profit
	// correctly does) still ranked as the single most profitable option here.
	private static final String CHEST_KEY_PHRASE = "Dungeon Chest Key";
	// Per user report ("the croesus 'hide claimed chests' doesnt work like its supposed to, its not
	// supposed to black highlight opened chests, its supposed to literally hide opened chests in the menu
	// where all the runs are"): this is a SEPARATE, real screen from the chest-tier picker above (that one
	// is titled by the dungeon/kuudra run name, e.g. "Catacombs - Floor VII", and lists Wood/Gold/Diamond/
	// etc. chest options for ONE run) — "Croesus" itself is the actual NPC/command menu that lists every
	// past run with an unclaimed reward, one item per run, across these exact real slot positions (user-
	// confirmed in-game). An unclaimed run's item literally reads "No chests opened yet!" in its lore;
	// anything else in these slots is a run that's already been (at least partially) claimed and should be
	// hidden outright — not dimmed, not overlaid, genuinely not rendered and not clickable.
	private static final String UNCLAIMED_RUN_PHRASE = "No chests opened yet!";
	private static final int SLOT_SIZE = 16;
	private static final int OUTLINE_THICKNESS = 2;
	private static final Pattern UNCLAIMED_CHESTS_PATTERN = Pattern.compile("^ Unclaimed chests: (\\d+)$");
	private static final Pattern EXTRA_STATS_PATTERN = Pattern.compile(" {29}> EXTRA STATS <");
	// Perf: was compiled fresh inside computeChestProfit on every call — that method runs once per unopened
	// chest slot, every single frame renderOverlay is active (a real Croesus/reward-chest room can have a
	// dozen-plus unopened chests), so this was a genuinely measurable per-frame regex-compile cost. Hoisted
	// to a shared static instance like every other pattern in this class already is.
	private static final Pattern COST_PATTERN = Pattern.compile("^([\\d,]+) Coins$");

	private boolean highlightProfitable = true;
	private boolean includeEssence = true;
	private boolean hideClaimed = true;
	private int chestWarningThreshold = 55;
	private int firstColor = 0xFF00AA00;
	private int secondColor = 0xFFFFFF55;

	private int chestCount = 0;

	private static boolean listenersRegistered = false;
	private static CroesusFeature instance;

	public CroesusFeature() {
		super("croesus", "Croesus", FeatureCategory.COMBAT, false);
		instance = this;
		// Profit highlight drawn from PreItemRenderRegistry (fires before item icons — per user request,
		// "change all item highlights... to render BEHIND the item they are highlighting") so the
		// translucent color sits under the chest icon instead of painted over it. The "hide claimed chests"
		// blackout is a DIFFERENT thing — it's meant to fully obscure the item, not frame it — so it stays
		// on PreTooltipRenderRegistry, on top of everything (still under the real tooltip).
		com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled() || !RUN_NAME.matcher(screen.getTitle().getString()).matches()) return;
			renderProfitHighlight(graphics, screen);
		});
		PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled() || !RUN_NAME.matcher(screen.getTitle().getString()).matches()) return;
			renderClaimedOverlay(graphics, screen);
		});
		// Real "Croesus" run-list screen (see UNCLAIMED_RUN_PHRASE's own doc comment) — genuinely suppresses
		// the render and blocks clicks on already-claimed run entries, unlike the chest-tier picker's own
		// translucent-overlay treatment above (which stays as-is; that's a different, correctly-working
		// screen).
		SlotItemOverrideRegistry.set("croesus_hide_claimed_runs", (slot, real) -> {
			if (!isEnabled() || !hideClaimed || real.isEmpty()) return null;
			if (!isCroesusRunListScreen() || !isCroesusRunSlotIndex(slot.index)) return null;
			return isClaimedRun(real) ? ItemStack.EMPTY : null;
		});
		ContainerClickRegistry.setRule("croesus_hide_claimed_runs", (slot, slotId, mouseButton, type) -> {
			if (!isEnabled() || !hideClaimed || slot.getItem().isEmpty()) return false;
			if (!isCroesusRunListScreen() || !isCroesusRunSlotIndex(slot.index)) return false;
			return isClaimedRun(slot.getItem());
		});
		// Real gap found (per user report — "the hidden chests are great but they still show lore"): the two
		// rules above only ever stopped the render/click path — the hovered-slot TOOLTIP is extracted straight
		// from Slot.getItem() (the REAL underlying item, unaffected by SlotItemOverrideRegistry's render-only
		// swap), so a claimed run's real lore ("Already opened!" etc.) still showed on hover even though the
		// slot itself rendered as hidden. Suppresses that tooltip outright for the same slots/condition.
		TooltipSuppressRegistry.setRule("croesus_hide_claimed_runs", (screen, mouseX, mouseY) -> {
			if (!isEnabled() || !hideClaimed) return false;
			if (!isCroesusRunListScreen()) return false;
			Slot hovered = ((AbstractContainerScreenAccessor) screen).skyblocksimplified$getHoveredSlot();
			if (hovered == null || !isCroesusRunSlotIndex(hovered.index)) return false;
			return isClaimedRun(hovered.getItem());
		});
	}

	private static boolean isCroesusRunListScreen() {
		var screen = Minecraft.getInstance().gui.screen();
		return screen instanceof AbstractContainerScreen<?> containerScreen
			&& containerScreen.getTitle().getString().contains("Croesus");
	}

	private static boolean isCroesusRunSlotIndex(int index) {
		return (index >= 10 && index <= 16) || (index >= 19 && index <= 25)
			|| (index >= 28 && index <= 34) || (index >= 37 && index <= 43);
	}

	private static boolean isClaimedRun(ItemStack stack) {
		if (stack.isEmpty()) return false;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return true;
		for (var line : lore.lines()) if (line.getString().contains(UNCLAIMED_RUN_PHRASE)) return false;
		return true;
	}

	@Override
	public String getSubcategory() { return "Kuudra"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString());
				return true;
			});
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance != null && instance.isEnabled()) instance.tick();
			});
		}
	}

	private void tick() {
		for (String line : TabListReader.readLines()) {
			Matcher m = UNCLAIMED_CHESTS_PATTERN.matcher(line);
			if (!m.find()) continue;
			try {
				int unclaimed = Integer.parseInt(m.group(1));
				if (unclaimed != chestCount) {
					chestCount = unclaimed;
					warnIfOverThreshold();
				}
			} catch (NumberFormatException ignored) {}
		}
	}

	private void onChatMessage(String text) {
		if (DungeonState.isInBoss() && EXTRA_STATS_PATTERN.matcher(text).matches()) {
			chestCount++;
			warnIfOverThreshold();
		}
	}

	private void warnIfOverThreshold() {
		if (chestCount <= chestWarningThreshold) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§cChest limit reached!"));
	}

	private String chestTypeName(ItemStack stack) {
		if (stack.isEmpty()) return null;
		String name = stack.getHoverName().getString().replace(" Chest Chest", "").replace(" Chest", "").trim();
		return CHEST_TYPE_NAMES.contains(name) ? name : null;
	}

	private boolean isOpenedChest(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return false;
		for (var line : lore.lines()) if (line.getString().contains(OPENED_CHEST_PHRASE)) return true;
		return false;
	}

	/** Real bug found (per user report — "the croesus helper minuses the profits... saying -111k when the
	 *  loot is 11k and the chest is 100k"): confirmed against devonian's own real CroesusProfit.kt (scanning
	 *  the exact same chest-item lore this reads) that the real structure has NO blank-line separator
	 *  between the reward list and the cost at all — it's {@code "Contents" / <reward line>* / "Cost" / "N
	 *  Coins"}, with the literal word "Cost" itself as the section boundary and the coin amount on the very
	 *  next line. Scanning for a blank line (this function's previous approach, ported from a misreading of
	 *  Odin's own real Croesus.kt) never found one, so the "reward" scan ran all the way to the end of the
	 *  lore, INCLUDING the literal "Cost" line (priced at 0, harmless) and — critically — the chest's own
	 *  cost amount itself, which is a plain "N Coins" line indistinguishable from a real reward and so got
	 *  counted as a positive reward instead of ever being subtracted, while the separate cost-scan (never
	 *  reached, since it only started AFTER the blank line that doesn't exist) always found 0. Rewritten to
	 *  match devonian's exact confirmed structure: everything before "Cost" is a reward (additive, {@link
	 *  ChestValueEstimator#priceOfRewardLine}, including essence), the line right after "Cost" is the amount
	 *  to subtract once, and nothing after that matters. */
	// Real gap found (per user report — "The highlight most profit also doesnt go off the instance chest
	// profit... i think it has trouble detecting auction prices or chest costs"): this lore-based ranking is
	// architecturally less reliable than Instance Chest Profit's own post-open calculation (that one prices
	// off each reward's REAL NBT item id, this one can only guess from plain preview-lore TEXT via
	// ChestValueEstimator.priceOfRewardLine's name-based lookup — see that method's own doc comment), and a
	// prior attempt at explaining a reported mismatch (adding Dungeon Chest Key cost detection) was confirmed
	// by the user NOT to be the actual cause. Rather than guess again blind, this now also (a) matches the
	// "Contents"/"Cost" section headers case-insensitively in case of any lore formatting quirk the exact-
	// equals check was missing, and (b) logs a full per-line price breakdown the first time each distinct
	// chest (by its own lore content, so this never spams once per frame) is scanned — next time the ranking
	// looks wrong in-game, check the log for this chest's breakdown to see exactly which line was priced
	// wrong instead of re-guessing.
	private final Map<Integer, String> loggedChestSignatures = new HashMap<>();

	private double computeChestProfit(ItemStack stack, int slotIndex) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return 0;
		List<String> lines = new ArrayList<>();
		for (Component c : lore.lines()) lines.add(c.getString().replaceAll("§.", "").trim());
		String signature = String.join("|", lines);

		StringBuilder breakdown = new StringBuilder();
		double total = 0;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.equalsIgnoreCase("Contents") || line.isEmpty()) continue;
			if (line.equalsIgnoreCase("Cost")) {
				double cost = 0;
				boolean needsChestKey = false;
				for (int j = i + 1; j < lines.size(); j++) {
					Matcher m = COST_PATTERN.matcher(lines.get(j));
					if (m.matches()) cost += Double.parseDouble(m.group(1).replace(",", ""));
					if (lines.get(j).contains(CHEST_KEY_PHRASE)) needsChestKey = true;
				}
				if (needsChestKey) {
					String keyId = com.cokelord.skyblocksimplified.api.SkyblockItemRepo.findIdByName(CHEST_KEY_PHRASE);
					if (keyId != null) {
						var keyPrice = com.cokelord.skyblocksimplified.api.BazaarApi.getPrice(keyId);
						if (keyPrice != null && keyPrice.sellPrice() > 0) cost += keyPrice.sellPrice();
					}
				}
				breakdown.append("\n  Cost: -").append(cost).append(needsChestKey ? " (includes chest key)" : "");
				total -= cost;
				break;
			}
			double lineValue = ChestValueEstimator.priceOfRewardLine(line, includeEssence);
			breakdown.append("\n  \"").append(line).append("\" -> ").append(lineValue);
			total += lineValue;
		}

		if (!signature.equals(loggedChestSignatures.put(slotIndex, signature))) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.info(
				"Croesus profit breakdown for slot {} (total {}):{}", slotIndex, total, breakdown);
		}
		return total;
	}

	/** Whether the chest(s) Chest Rolling's own settings would actually roll for this run have already been
	 *  opened — see renderProfitHighlight's own doc comment for why this gates the highlight instead of a
	 *  blanket suppression. Mirrors Chest Rolling's own "Only Bedrock Chests"/"Obsidian on lower floors"
	 *  top-tier resolution (see that feature's tryStartRoll/highestChestTierPresent) exactly, rather than
	 *  re-deriving it from a separate source of truth: if Only Bedrock is on, only the one chest that setting
	 *  would actually roll (bedrock, or obsidian as its documented lower-floor exception) needs to be opened
	 *  before revealing the FULL ranking; otherwise every priced chest option on this screen needs to be. */
	private boolean requiredChestsRolled(List<Slot> chestSlots) {
		ChestRollingFeature rolling = ChestRollingFeature.getInstance();
		if (rolling == null) return true;
		if (rolling.isOnlyBedrock()) {
			Slot bedrock = null, obsidian = null;
			for (Slot slot : chestSlots) {
				String type = chestTypeName(slot.getItem());
				if ("Bedrock".equals(type)) bedrock = slot;
				else if ("Obsidian".equals(type)) obsidian = slot;
			}
			Slot topTier = bedrock != null ? bedrock : rolling.isObsidianOnLowerFloors() ? obsidian : null;
			// No real top-tier chest present at all on this screen — nothing to gate on, so don't hold the
			// highlight back forever waiting for a chest that was never going to appear here.
			return topTier == null || isOpenedChest(topTier.getItem());
		}
		for (Slot slot : chestSlots) {
			if (!isOpenedChest(slot.getItem())) return false;
		}
		return true;
	}

	private record RankedChests(List<Slot> chestSlots, Slot best, Slot second) {}

	private RankedChests rankChests(AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		var playerInventory = mc.player.getInventory();

		List<Slot> chestSlots = new ArrayList<>();
		Slot woodSlot = null;
		record Scored(Slot slot, double value) {}
		List<Scored> unopened = new ArrayList<>();

		for (Slot slot : screen.getMenu().slots) {
			if (slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			String typeName = chestTypeName(stack);
			if (typeName == null) continue;
			chestSlots.add(slot);
			if ("Wood".equals(typeName)) woodSlot = slot;
			if (isOpenedChest(stack)) continue;

			unopened.add(new Scored(slot, computeChestProfit(stack, slot.index)));
		}

		// Per user request: only the top 2 unopened/unclaimed chests ever get highlighted (green/yellow) —
		// nothing else gets a "rest" color anymore. If every single one is a net loss, highlight the Wood
		// chest specifically instead (cheapest to open) rather than ranking among all-negative options.
		unopened.sort((a, b) -> Double.compare(b.value(), a.value()));
		boolean allNegative = !unopened.isEmpty() && unopened.stream().allMatch(s -> s.value() < 0);
		Slot best = allNegative ? woodSlot : unopened.size() > 0 ? unopened.get(0).slot() : null;
		Slot second = allNegative ? null : unopened.size() > 1 ? unopened.get(1).slot() : null;
		return new RankedChests(chestSlots, best, second);
	}

	// PreItemRenderRegistry fires from inside AbstractContainerScreen's own already-translated
	// pose().translate(leftPos, topPos) block — slot.x/slot.y are already the right coordinates, no manual
	// leftPos/topPos offset needed (see that registry's own doc comment).
	private void renderProfitHighlight(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		if (!highlightProfitable) return;
		RankedChests ranked = rankChests(screen);
		if (ranked == null) return;

		// Real bug found (per user report — "The highlight most profit chest should not highlight until the
		// chests are rolled IF the chest rolling is on. Otherwise it kind of gives away that the most profit
		// is not in the obsidian chest"): this ranking already knows every unopened chest's real contents —
		// Hypixel embeds a full preview in the chest option's own tooltip lore well before the "roll" ever
		// happens — so painting the best/second-best overlay immediately gave away which tier was worth
		// opening before anything was opened at all, defeating the whole point of Chest Rolling's CS-case
		// suspense reveal. Per user follow-up ("I dont want the most profit chest to be supressed entirely, i
		// want it shown when the chest rolls that are supposed to be rolled have been rolled, depending on the
		// users settings. If they have it so only top chest needs rolling it needs to show it all after its
		// been rolled, otherwise show when all has been rolled"): outright suppression (an earlier round's
		// fix) was too blunt — this now waits only for the specific chest(s) Chest Rolling's own settings
		// would actually roll to be opened, then reveals the normal ranking as usual. When Chest Rolling
		// isn't even enabled there's no roll animation to protect, so no gating applies at all.
		if (ChestRollingFeature.isModuleEnabled() && !requiredChestsRolled(ranked.chestSlots())) return;

		for (Slot slot : ranked.chestSlots()) {
			if (isOpenedChest(slot.getItem())) continue;
			if (slot != ranked.best() && slot != ranked.second()) continue;

			// Per user request: an actual translucent box overlay ON the slot (~30% opacity), not an
			// outline, and no floating profit number "rendering above chests" — the color alone is the
			// whole UI now.
			int color = slot == ranked.best() ? firstColor : secondColor;
			int alpha = Math.round(0.30f * 255);
			int fillColor = (alpha << 24) | (color & 0xFFFFFF);
			int slotX = slot.x;
			int slotY = slot.y;
			RenderUtil.fillRounded(graphics, slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0, fillColor);
		}
	}

	private void renderClaimedOverlay(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		if (!hideClaimed) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		var playerInventory = mc.player.getInventory();
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();

		for (Slot slot : screen.getMenu().slots) {
			if (slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			if (chestTypeName(stack) == null || !isOpenedChest(stack)) continue;
			int slotX = leftPos + slot.x;
			int slotY = topPos + slot.y;
			RenderUtil.fillRounded(graphics, slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0, 0xF0101010);
		}
	}

	public boolean isHighlightProfitable() { return highlightProfitable; }
	public void setHighlightProfitable(boolean value) { highlightProfitable = value; }
	public boolean isIncludeEssence() { return includeEssence; }
	public void setIncludeEssence(boolean value) { includeEssence = value; }
	public boolean isHideClaimed() { return hideClaimed; }
	public void setHideClaimed(boolean value) { hideClaimed = value; }
	public int getChestWarningThreshold() { return chestWarningThreshold; }
	public void setChestWarningThreshold(int value) { chestWarningThreshold = Math.max(0, Math.min(60, value)); }
	public int getFirstColor() { return firstColor; }
	public void setFirstColor(int value) { firstColor = value; }
	public int getSecondColor() { return secondColor; }
	public void setSecondColor(int value) { secondColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("highlightProfitable", highlightProfitable);
		obj.addProperty("includeEssence", includeEssence);
		obj.addProperty("hideClaimed", hideClaimed);
		obj.addProperty("chestWarningThreshold", chestWarningThreshold);
		obj.addProperty("firstColor", firstColor);
		obj.addProperty("secondColor", secondColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("highlightProfitable")) highlightProfitable = obj.get("highlightProfitable").getAsBoolean();
		if (obj.has("includeEssence")) includeEssence = obj.get("includeEssence").getAsBoolean();
		if (obj.has("hideClaimed")) hideClaimed = obj.get("hideClaimed").getAsBoolean();
		if (obj.has("chestWarningThreshold")) chestWarningThreshold = obj.get("chestWarningThreshold").getAsInt();
		if (obj.has("firstColor")) firstColor = obj.get("firstColor").getAsInt();
		if (obj.has("secondColor")) secondColor = obj.get("secondColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Ranks every unopened Croesus chest by estimated profit and outlines them all: green for the most profitable, other colors for the rest.";
	}
}
