package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.combat.ChestValueEstimator;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.hud.TabListReader;
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
 * com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry} plumbing the old feature already used, per the
 * port plan's decision to drop cross-mod-server dependencies.
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
	// Real, user-confirmed lore marker for the SEPARATE run-history LIST screen's own icons (as opposed to
	// the tier-picker screen's chest OPTIONS, which use OPENED_CHEST_PHRASE above) — confirmed via a debug
	// scan that found 0 matches for "Already opened!" against real run icons, followed by the user directly
	// reporting the real line: "Opened Chest: Wood/Emerald/Diamond/Obsidian/Gold/Bedrock" (i.e. this literal
	// prefix followed by whichever tier was actually opened for that run).
	private static final String OPENED_RUN_PHRASE = "Opened Chest:";
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
	private static final int SLOT_SIZE = 16;
	private static final int OUTLINE_THICKNESS = 2;
	private static final Pattern UNCLAIMED_CHESTS_PATTERN = Pattern.compile("^ Unclaimed chests: (\\d+)$");
	private static final Pattern EXTRA_STATS_PATTERN = Pattern.compile(" {29}> EXTRA STATS <");
	// Perf: was compiled fresh inside computeChestProfit on every call — that method runs once per unopened
	// chest slot, every single frame renderOverlay is active (a real Croesus/reward-chest room can have a
	// dozen-plus unopened chests), so this was a genuinely measurable per-frame regex-compile cost. Hoisted
	// to a shared static instance like every other pattern in this class already is.
	private static final Pattern COST_PATTERN = Pattern.compile("^([\\d,]+) Coins$");
	// Real, user-confirmed Croesus run-history browsing screen title (see ChestRollingFeature's own
	// CROESUS_LIST_TITLE, which this duplicates rather than shares since the two features' click/render
	// registrations are independent) — "(1/2) Croesus" style, one page of up to 28 real dungeon-run icons.
	// Real gap found (per user report — "The mod doesnt tie runs to stuff if theres only one page. If theres
	// only one page of runs the gui name is Croesus only, no 1/2 or anything"): the page prefix is entirely
	// absent on a real single-page history, not just "(1/1)" — this never matched a bare "Croesus" title at
	// all, so Hide Opened Chests silently never applied there either.
	private static final Pattern CROESUS_LIST_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Croesus$");

	private boolean highlightProfitable = true;
	private boolean includeEssence = true;
	private int chestWarningThreshold = 55;
	private int firstColor = 0xFF00AA00;
	private int secondColor = 0xFFFFFF55;
	// Per user request ("If the filter for hiding opened chests is on it jumps a step which gets annoying,
	// so add back the modside 'hide opened chests' and make sure its turned on if the chest rolling is on.
	// It should automatically get turned on"): a pure client-render dim of already-claimed run icons on the
	// NEW Croesus run-history browsing screen — deliberately NOT a real slot removal, unlike Hypixel's own
	// native hide-filter (documented by the user to shift every remaining run's real position when toggled),
	// which would desync ChestRollingFeature's own positional run-tracking (see that class's own
	// trackedRuns doc comment) the instant it was flipped on. Forced on automatically by
	// ChestRollingFeature.onEnable() whenever Chest Rolling itself is enabled, for exactly that reason —
	// but still a normal user-toggleable setting the rest of the time.
	private boolean hideOpenedRuns = false;

	private int chestCount = 0;

	private static boolean listenersRegistered = false;
	private static CroesusFeature instance;

	public CroesusFeature() {
		super("croesus", "Croesus", FeatureCategory.COMBAT, false);
		instance = this;
		// Profit highlight drawn from PreItemRenderRegistry (fires before item icons — per user request,
		// "change all item highlights... to render BEHIND the item they are highlighting") so the
		// translucent color sits under the chest icon instead of painted over it.
		com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled() || !RUN_NAME.matcher(screen.getTitle().getString()).matches()) return;
			renderProfitHighlight(graphics, screen);
		});
		// Per user correction ("i want you to genuinely stop them from rendering in the gui and hide their
		// lore... not just overlayed with a black box"): a render-order dim (this round's earlier attempts,
		// both PreItemRenderRegistry- and PreTooltipRenderRegistry-based) can only ever paint OVER or UNDER
		// the real icon, never actually stop it from drawing. SlotItemOverrideRegistry is the real mechanism
		// for that — its own doc comment already names exactly this ("Croesus's 'Hide Claimed Chests'
		// substitutes ItemStack.EMPTY for an already-claimed run's item so it doesn't render at all"), it was
		// simply never wired up to this feature until now. Substituting EMPTY makes the slot render as a
		// genuinely blank slot (no icon, no stack-count overlay, nothing), and real click/menu logic
		// downstream (ChestRollingFeature's own positional counting included) still sees the REAL item, since
		// only this one render call site is redirected.
		com.cokelord.skyblocksimplified.inventory.SlotItemOverrideRegistry.set("croesus_hide_opened", (slot, real) -> {
			if (!isEnabled() || !hideOpenedRuns) return null;
			Minecraft mc = Minecraft.getInstance();
			if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
				|| !isCroesusChestScreen(screen.getTitle().getString())) return null;
			return isOpenedChest(real) ? ItemStack.EMPTY : null;
		});
		// See TooltipSuppressRegistry's own doc comment on why this is separate from the render override
		// above — the real Slot/item is still there underneath the EMPTY-rendered icon, so vanilla's own
		// independent hover/tooltip detection would otherwise still show the real lore on hover.
		com.cokelord.skyblocksimplified.gui.TooltipSuppressRegistry.setRule("croesus_hide_opened", (screen, mouseX, mouseY) -> {
			if (!isEnabled() || !hideOpenedRuns || !isCroesusChestScreen(screen.getTitle().getString())) return false;
			Slot hovered = ((com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor) screen).skyblocksimplified$getHoveredSlot();
			return hovered != null && isOpenedChest(hovered.getItem());
		});
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
			// Per user request ("hidden so it cannot be opened anymore"): the render dim above is purely
			// cosmetic on its own — without this, an already-claimed run's icon would still be fully
			// clickable underneath the overlay. Blocks the click outright instead, same choke point Chest
			// Rolling's own "chest_rolling" rule already uses.
			com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry.setRule("croesus_hide_opened", (slot, slotId, mouseButton, type) -> {
				if (instance == null || !instance.isEnabled() || !instance.hideOpenedRuns) return false;
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
					|| !instance.isCroesusChestScreen(screen.getTitle().getString())) return false;
				return instance.isOpenedChest(slot.getItem());
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

	/** Reverted (per direct user correction — "If i roll a chest it hides the chest i just rolled for some
	 *  reason, the 'hide claimed runs' part of the croesus module should hide RUNS that have opened chests
	 *  and not chests that have been rolled"): a previous round widened this to also match {@link #RUN_NAME}
	 *  (the tier-picker's own individual chest OPTIONS screen), on the theory that {@link #isOpenedChest}'s
	 *  lore check was designed to recognize an opened chest there too. That theory was wrong in practice —
	 *  {@link ChestRollingFeature#isTierRecentlyRolled} (one of {@link #isOpenedChest}'s own fallbacks) marks
	 *  a tier "recently rolled" the INSTANT the player rolls it, specifically so the profit-highlight ranking
	 *  stops treating it as still-unclaimed — wiring that same check into the real hide mechanism meant the
	 *  chest the player had JUST rolled immediately vanished from the tier-picker screen instead of staying
	 *  visible with its result. Only the run-history LIST screen should ever hide a claimed entry. */
	private boolean isCroesusChestScreen(String title) {
		return CROESUS_LIST_TITLE.matcher(title).matches();
	}

	private boolean isOpenedChest(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (var line : lore.lines()) {
				String text = line.getString();
				if (text.contains(OPENED_CHEST_PHRASE) || text.contains(OPENED_RUN_PHRASE)) return true;
			}
		}
		// Per user request ("Same with the croesus highlight best chest"): the exact same "just rolled this
		// tier this GUI session, don't treat it as still-unclaimed" memory ChestRollingFeature's own Hide
		// Lore reshow fix uses also applies here, for the identical reason — Hypixel's own live lore may not
		// have caught up with "Already opened!" yet within this same open screen right after a roll.
		String tier = chestTypeName(stack);
		return tier != null && ChestRollingFeature.isTierRecentlyRolled(tier);
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
		// Real bug found (per user report — "Highlight profitable does NOT show the highlight when the
		// chests that should be rolled have been rolled"): this used to require EVERY priced chest option on
		// the screen to already be opened before revealing anything. rankChests() only ever ranks UNOPENED
		// chests (see its own "unopened" list) — so the one and only moment this condition could ever become
		// true is exactly the moment there are zero unopened chests left to rank, meaning best/second always
		// come back null and nothing ever actually got painted even once the gate "passed". In practice a
		// player rolls one or two tiers on a given run and stops, well short of literally every option, so
		// the gate simply never passed at all either. Loosened to "at least one chest on this screen has been
		// opened" — enough to prove the suspense-protecting concern (nothing revealed before the player has
		// touched anything) without demanding an unreachable all-opened state, and it still leaves real
		// unopened options behind to actually highlight.
		for (Slot slot : chestSlots) {
			if (isOpenedChest(slot.getItem())) return true;
		}
		return chestSlots.isEmpty();
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

	public boolean isHighlightProfitable() { return highlightProfitable; }
	public void setHighlightProfitable(boolean value) { highlightProfitable = value; }
	public boolean isIncludeEssence() { return includeEssence; }
	public void setIncludeEssence(boolean value) { includeEssence = value; }
	public int getChestWarningThreshold() { return chestWarningThreshold; }
	public void setChestWarningThreshold(int value) { chestWarningThreshold = Math.max(0, Math.min(60, value)); }
	public int getFirstColor() { return firstColor; }
	public void setFirstColor(int value) { firstColor = value; }
	public int getSecondColor() { return secondColor; }
	public void setSecondColor(int value) { secondColor = value; }
	public boolean isHideOpenedRuns() { return hideOpenedRuns; }
	public void setHideOpenedRuns(boolean value) { hideOpenedRuns = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("highlightProfitable", highlightProfitable);
		obj.addProperty("includeEssence", includeEssence);
		obj.addProperty("chestWarningThreshold", chestWarningThreshold);
		obj.addProperty("firstColor", firstColor);
		obj.addProperty("secondColor", secondColor);
		obj.addProperty("hideOpenedRuns", hideOpenedRuns);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("highlightProfitable")) highlightProfitable = obj.get("highlightProfitable").getAsBoolean();
		if (obj.has("includeEssence")) includeEssence = obj.get("includeEssence").getAsBoolean();
		if (obj.has("chestWarningThreshold")) chestWarningThreshold = obj.get("chestWarningThreshold").getAsInt();
		if (obj.has("firstColor")) firstColor = obj.get("firstColor").getAsInt();
		if (obj.has("secondColor")) secondColor = obj.get("secondColor").getAsInt();
		if (obj.has("hideOpenedRuns")) hideOpenedRuns = obj.get("hideOpenedRuns").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Ranks every unopened Croesus chest by estimated profit and outlines them all: green for the most profitable, other colors for the rest.";
	}
}
