package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.NeuInternalName;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request: a CS:GO-case-style opening animation for Hypixel's real dungeon reward chest
 * (Wooden/Gold/Diamond/Emerald/Obsidian/Bedrock — opened live at the end of a run, or replayed later via
 * Croesus). Ported from reading SkyOcean's real implementation (uploaded by the user for exactly this,
 * with an explicit request to check it for anything malicious first — see the session notes: an extensive
 * scan for webhooks/process-exec/credential-reads/suspicious network calls turned up nothing, so this is a
 * genuine port of its approach, not a blind copy of its code — SkyOcean is Kotlin built against a shared
 * framework (SkyblockAPI, resourcefullib) this project doesn't have, so every mechanism below is
 * reimplemented against this codebase's own primitives, same as every other ported reference feature here).
 *
 * <p>What's faithfully ported from SkyOcean's {@code DungeonGamblingRenderer}/{@code DungeonGambling}:
 * <ul>
 *   <li>Real loot detection: the chest's real drop is the container-slot item with the highest resolvable
 *   value (same {@link com.cokelord.skyblocksimplified.api.ItemPriceUtil#priceOf} price chain shared across
 *   this codebase) among items that resolve to a real Hypixel item id — no guessing needed, this
 *   is just "which of the container's real slots is worth the most."</li>
 *   <li>Live-run vs. Croesus detection: a BARRIER item in the container means a live just-earned chest, an
 *   ARROW item means Croesus is replaying a past one — the exact same marker-item signal SkyOcean's own
 *   {@code DungeonGambling.onScreenChange} keys off.</li>
 *   <li>The scrolling mechanic itself: a strip of decoy items with the real winner spliced in near the end
 *   (not at the very end, so the strip visibly overshoots slightly before easing back), an ease-in-out-circ
 *   curve, and a per-card "clunk" sound as each one passes the center pointer.</li>
 * </ul>
 * What's NOT ported (SkyOcean-specific infra this project has no equivalent of, or genuinely proprietary
 * data): SkyOcean's real per-floor weighted decoy-item pool comes from their own remote data repo, which
 * this project has no access to and no reason to reverse-engineer — the decoys here are a small fixed set
 * of plain reskinned vanilla items (matching the user's own "handles, scrolls, books" examples) instead,
 * since they're explicitly fake filler the player was never going to see the real odds of anyway.
 */
public class ChestRollingFeature extends Feature {
	// Real bug found (per user report — "Chest rolling no longer triggers on the actual server"): required
	// literally "Wooden", but this table's own class doc comment elsewhere quotes the real source data as
	// "Wood/Gold tiers" — Hypixel's actual reward-chest title is "Wood Chest", not "Wooden Chest", so the
	// lowest tier (very commonly the FIRST chest rolled in any given run) never matched at all. Accepts
	// either spelling now; captured group 1 is only ever used for the bedrock-only check, which doesn't
	// care which spelling matched.
	private static final Pattern CHEST_TITLE_PATTERN =
		Pattern.compile("^(Wood|Wooden|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$", Pattern.CASE_INSENSITIVE);

	// Per user request (Hide Lore, moved from CroesusFeature — see hideLore's own doc comment): the real
	// chest-tier-picker screen, titled by the dungeon/kuudra run name (e.g. "Catacombs - Floor VII"), that
	// lists Wood/Gold/Diamond/Emerald/Obsidian/Bedrock chest options for one run — the exact same pattern
	// CroesusFeature's own RUN_NAME used, duplicated here rather than shared since it's now this module's own
	// concern, not Croesus's.
	private static final Pattern TIER_PICKER_SCREEN = Pattern.compile(".*Catacombs - Flo.*|Kuudra - .*");
	private static final java.util.Set<String> CHEST_TIER_NAMES = java.util.Set.of(
		"Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock");
	// Same real lore marker CroesusFeature's own isOpenedChest() uses — an already-opened chest option has
	// nothing left to spoil, so it's never worth hiding regardless of tier.
	private static final String OPENED_CHEST_PHRASE = "Already opened!";

	private static String chestTierName(ItemStack stack) {
		if (stack.isEmpty()) return null;
		String name = stack.getHoverName().getString().replace(" Chest Chest", "").replace(" Chest", "").trim();
		return CHEST_TIER_NAMES.contains(name) ? name : null;
	}

	private static boolean isOpenedChestOption(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return false;
		for (var line : lore.lines()) if (line.getString().contains(OPENED_CHEST_PHRASE)) return true;
		return false;
	}

	/** The highest chest tier actually present (and still unopened) on this tier-picker screen — Bedrock if
	 *  one exists, else Obsidian, else null (per user spec: "search for a bedrock chest and if it cant find
	 *  one then hide the obsidian chest" — no other tier is ever treated as "the maxed chest"). */
	private static String highestChestTierPresent(AbstractContainerScreen<?> screen) {
		boolean hasBedrock = false, hasObsidian = false;
		for (Slot slot : screen.getMenu().slots) {
			ItemStack stack = slot.getItem();
			String tier = chestTierName(stack);
			if (tier == null || isOpenedChestOption(stack)) continue;
			if ("Bedrock".equals(tier)) hasBedrock = true;
			else if ("Obsidian".equals(tier)) hasObsidian = true;
		}
		return hasBedrock ? "Bedrock" : hasObsidian ? "Obsidian" : null;
	}

	/** Rewrites a hidden chest's real tooltip lines in place: the "Contents" and "Cost" header lines survive
	 *  verbatim, every real reward line between them becomes a plain gray "§7?", and the real coin amount right
	 *  after "Cost" becomes "§7? Coins" — see the Hide Lore registration's own doc comment above for the full
	 *  spec this implements. */
	private static void fabricateChestLore(List<Component> lines) {
		boolean inContents = false;
		for (int i = 0; i < lines.size(); i++) {
			String plain = lines.get(i).getString().replaceAll("§.", "").trim();
			if (plain.equals("Contents")) {
				inContents = true;
				continue;
			}
			if (plain.equals("Cost")) {
				inContents = false;
				if (i + 1 < lines.size()) lines.set(i + 1, Component.literal("§7? Coins"));
				continue;
			}
			if (inContents && !plain.isEmpty()) lines.set(i, Component.literal("§7?"));
		}
	}

	// Real bug found (per user report — "Making the time for the roll higher should also show more items
	// at the same speed instead of slowing it down"): STRIP_LENGTH/WINNER_INDEX used to be fixed constants,
	// so a longer configured duration just stretched the SAME ~40-card distance over more wall-clock time —
	// a longer roll felt slower, not longer. The strip's length (and where the winner sits in it) is now
	// computed per-roll from animationDurationSeconds against a fixed target cruise speed (see
	// CRUISE_CARDS_PER_SECOND/computeRollShape below), so a longer duration shows proportionally more cards
	// scrolling past at the same visual speed instead.
	// Per user report ("The chest rolling is a bit too fast, it hurts my eyes a little"): this cards-per-
	// second rate was calibrated for the OLD, much smaller fixed-size cards — the CS2-style rework (see this
	// class's own doc comment, "way bigger boxes, only fitting like 7 on the screen at once") made each card
	// several times wider on screen at the SAME cards/second rate, which reads as a big jump in raw pixel
	// scroll speed even though the "cards per second" number never changed. Slowed down directly (not by
	// changing the ratio's calibration comment below, which still describes its origin) rather than only
	// tuning the default duration, since duration only ever controls how many cards go by, never how fast.
	private static final float CRUISE_CARDS_PER_SECOND = 7f;
	private static final int WINNER_OVERSHOOT_CARDS = 10;
	private static final int MIN_WINNER_INDEX = 15;
	private static final int CARD_WIDTH = 24;
	private static final int CARD_HEIGHT = 18;
	private static final int CARD_GAP = 5;
	private static final long SETTLE_MILLIS = 1500L;
	// Per user request ("When landing on an item, it should take a full second until it exits the chest
	// rolling menu. It should slow to a stop near the end, getting slower and slower until it stops for a
	// second then it exits."): the strip already decelerates smoothly to a full stop by the end of
	// animationDurationSeconds (see computeRollShape/cardsScrolled's own exponential-decay landing curve) —
	// but isSpinning() used to flip to isSettling() (which swaps back to the REAL chest screen) at the exact
	// instant that stop completed, so the fully-stopped strip was never actually visible; it read as an
	// abrupt cut the moment the deceleration finished rather than a held pause. This holds the CS2 strip
	// screen open (frozen at its final resting position — renderRoll's own elapsed-time clamp to
	// animationDurationSeconds already keeps cardsScrolled() pinned there) for one more full second before
	// handing off to isSettling(), so the stop itself is actually seen and held before the exit.
	private static final long LAND_HOLD_MILLIS = 1000L;
	private static final int BACKGROUND_DIM = 0x90000000;
	// Real redesign (per user request — "Change the chest opening animation a little. It should be more like
	// cs2, way bigger boxes (only fitting like 7 on the screen at the same time)"): card size used to be a
	// fixed CARD_SCALE=3 constant, independent of the real screen width — replaced the old fixed pixel scale
	// with one derived live from screenWidth/VISIBLE_CARDS_TARGET each frame (see renderRoll), so exactly
	// this many cards' worth of width always fits regardless of resolution/GUI scale, and cards read as "way
	// bigger" on any normal screen than the old constant ever produced.
	private static final int VISIBLE_CARDS_TARGET = 7;
	// Per user request ("displays a circle in the center, fitting about 2 items max, and those 2 items are a
	// little magnified and also have full opacity"): the combined width (in cards) of the always-full-opacity
	// center zone — see renderRoll's per-card fade calc.
	private static final float CENTER_ZONE_CARDS = 2f;
	// Per user request, after several iterations landing on a full-card circular "magnifying glass" lens
	// that then turned out "absolutely massive": "Go back to making the one the stick in the middle was
	// currently hovering over a bit bigger than others next to it (make it overlap the ones next to it, it
	// should end when the stick reaches the middle of the next one, then that one should become the bigger
	// one)". No separate lens/overlay pass anymore — see renderRoll's own per-card magnifyT calc, which
	// scales the WHOLE card (box, border, icon together) by up to this fraction, peaking at 0 distance from
	// the pointer and falling linearly to 0 extra scale at exactly half a card-width away (where the
	// neighboring card takes over as the nearest one) — continuous, no snap, and only ever one card boosted
	// at a time since cards are spaced a full card-width apart.
	private static final float MAGNIFY_BOOST = 0.25f;
	// See renderRoll's own doc comment on VIGNETTE_TIGHTNESS's use — the fraction of the way from the
	// always-full-opacity zone to the true screen edge where cards now finish fading to fully black.
	private static final float VIGNETTE_TIGHTNESS = 0.65f;

	private boolean onlyBedrock = false;
	// Per user request: "Only Bedrock Chests" excludes every other chest tier — but floors F4/M4 and under
	// have no Bedrock chest at all (it doesn't exist below F5), so with Only Bedrock on, rolling never
	// triggers there even though those floors still hand out real rare drops via their Obsidian chest
	// (the top tier that DOES exist on those floors). This lets Obsidian through as an exception, but only
	// on floors low enough to lack a Bedrock chest — it doesn't loosen Only Bedrock on F5+.
	private boolean obsidianOnLowerFloors = false;
	private boolean onlyCroesus = false;
	private float animationDurationSeconds = 3.5f;
	// Per user request ("Add a subtoggle inside it that obfuscates rare items, for example instead of
	// landing on a necron handle it becomes more like the CS2 cases, where it lands on a legendary item...
	// and then reveals what you got when it lands"), later expanded to a full per-floor (M3-M7) list: only
	// ever affects the names in ChestDropTables.isObfuscationEligible's own per-floor map — see startRoll's
	// own doc comment on how the swap works.
	private boolean obfuscateRareItems = false;
	// Per user request ("The hide lore should be in the chest rolling module... it should specifically only
	// happen on the highest chest, so it should search for a bedrock chest and if it cant find one then hide
	// the obsidian chest. This way the maxed chest (the one that gets rolled) hides rewards"): moved from
	// CroesusFeature's own hideChestLore, which used to hide EVERY unopened chest option's tooltip on the
	// tier-picker screen — that spoiled nothing extra for the one chest players actually care about (the
	// highest tier present) while still needlessly hiding the low-tier ones nobody rolls. Default off, same
	// as the old toggle (a real behavior change, not a pure bugfix).
	private boolean hideLore = false;
	// Per user request ("Add an option for making all of the chest lore hidden aswell for the people that
	// roll every chest"): opts back into the old CroesusFeature behavior of hiding every unopened chest
	// option's tooltip, not just the single highest-tier one.
	private boolean hideLoreAllChests = false;
	// Per user report ("The lore doesnt reshow after rolling a chest. If the user clicks the back button
	// (slot 49) then it should show the lore of that chest until the gui is closed"): Hypixel's own real
	// "Already opened!" lore marker (see isOpenedChestOption) is the authoritative long-term signal, but
	// there's no guarantee it's already synced back into this SAME open GUI session's item stacks the exact
	// moment the player backs out of the reward screen — the tier-picker screen reappearing (whether via a
	// literal new container or the same one repopulated) might still be showing the pre-roll, not-yet-
	// opened item for a beat, or indefinitely for however Hypixel's own client-side item caching behaves.
	// This is a purely session-scoped memory (matches SkyOcean's own real "no persisted cache at all"
	// approach — see this class's own doc comment on why 1.3.9 removed a similar persisted cache) — cleared
	// the moment the player leaves this chest-picker/reward GUI flow entirely (see onTick's own clearing
	// logic), not carried across a real GUI close/reopen the way "until the gui is closed" literally asks.
	private final java.util.Set<String> recentlyRolledTiers = new java.util.HashSet<>();

	/** Exposed for {@link CroesusFeature}'s own "isOpenedChest" gating — per user request ("Same with the
	 *  croesus highlight best chest"), the exact same "just rolled this tier this GUI session, don't treat
	 *  it as still-unclaimed" memory used for the Hide Lore reshow fix above also applies to Croesus's own
	 *  profit-highlight suppression, for the identical reason (Hypixel's own live lore may not have caught
	 *  up yet within this same open screen). */
	public static boolean isTierRecentlyRolled(String tier) {
		return instance != null && instance.recentlyRolledTiers.contains(tier);
	}

	// ---- Croesus run tracking (per user spec) ----
	// Per user request: "Use the same detection that auto-requeue uses to detect when a run is over. Add
	// that run to a list and set a 3 day timestamp for deletion... when in croesus, detect all chests...
	// The highest slot number should be the oldest run on the list... it should tie a run to a chest in the
	// menu when opening croesus (first line is first run/highest slot number of run)."
	//
	// Real, user-confirmed mechanics this implements: Croesus's real run-history list ("(1/2) Croesus" —
	// title format and layout confirmed by the user directly) always shows every real run at a STABLE
	// position — a newly-completed run is inserted at the very front (lowest slot number) and every older
	// run shifts one slot later; a claimed run does NOT disappear or renumber on its own (confirmed by the
	// user — "Stays listed but new runs take the top of the list shifting it over one slot every run"); and
	// a run drops off Croesus entirely after 3 real days (also user-confirmed — "3 days is Hypixel's real
	// Croesus expiry"). Since the ordering is purely chronological and 1:1 with "when did this run
	// complete," a client-side list that mirrors exactly the same two events (a new completion prepends;
	// 3 real days after completion it's dropped) stays positionally aligned with Croesus's own real list —
	// same index, same slot — with NO need to fingerprint individual chests by content or NBT at all. This
	// deliberately avoids `1.3.9`'s own real collision bug (a persisted cache keyed by a HASH of a chest's
	// visible reward content, where two genuinely different real chests with the same visible loot got
	// treated as "the same chest already opened" and silently broke) — nothing here is ever keyed by a
	// chest's own content; purely by chronological position, which can't collide the same way.
	//
	// Oldest-first (index 0 = the OLDEST tracked run, newest appended at the very end) — per the user's own
	// exact spec/correction: "The lowest line on the list (latest addition) matches with the run with the
	// lowest slot number... we add the newest runs to the bottom of the list" and later confirmed with real
	// numbers ("run 0 should tie to slot 11... the lowest list number should tie to the oldest run possible
	// and the bottom of the list should tie to the newest run/lowest slot/slot 10" for a 2-run list). Mapping
	// a real Croesus (page, position-on-page) to a list index is therefore an INVERTED rank: rank-from-newest
	// = (page-1)*CROESUS_RUN_SLOTS.length + position (0 = the single newest run, matching Croesus's own
	// newest-first display), and list index = trackedRuns.size() - 1 - rankFromNewest — see
	// onCroesusListClicked/debugPrintCroesusRunMatches/getTiedSlot, which all share this exact formula (or
	// its inverse).
	//
	// Per user request/redesign ("I need it to write down each run it detects being complete to a list...
	// It then needs to mark these tied runs when rolled. When i roll the oldest run i have it should be
	// marked as rolled. When i reopen that chest the mod should realize that the run has been marked as
	// rolled already"): a plain List<Long> of expiry timestamps had no way to remember "this specific run
	// already had its chest rolled" at all — the only thing that ever remembered "just rolled" state was
	// recentlyRolledTiers, a set of plain TIER NAMES ("Wood"/"Gold"/...) that's wiped the instant the player
	// leaves the tier-picker screen (see that field's own doc comment), which is exactly why leaving and
	// re-entering the same run let it be rolled again. Each tracked run is now its own mutable object
	// carrying a real `rolled` flag alongside its expiry — safe even though a new completion is appended at
	// the end and pruning removes expired entries from the front, both of which shift surviving entries'
	// POSITION in this list, since the flag travels with the object itself rather than living in some
	// separately-indexed map that a shift would desync.
	private static final class TrackedRun {
		long expiryMillis;
		boolean rolled;
		TrackedRun(long expiryMillis, boolean rolled) { this.expiryMillis = expiryMillis; this.rolled = rolled; }
	}
	private final java.util.List<TrackedRun> trackedRuns = new java.util.ArrayList<>();
	private static final long RUN_TRACK_EXPIRY_MILLIS = 3L * 24 * 60 * 60 * 1000L; // 3 real days, matches Hypixel's own confirmed Croesus retention.
	// Real gap found (per user report — "The mod doesnt tie runs to stuff if theres only one page. If theres
	// only one page of runs the gui name is Croesus only, no 1/2 or anything"): a single-page Croesus history
	// has no "(N/M) " prefix on the real screen title at all — just the bare word "Croesus" — which this
	// regex never matched, so run-tying silently never fired until a second page actually existed. The page
	// group is now optional; {@link #resolvePage} treats a missing match as page 1.
	private static final Pattern CROESUS_LIST_TITLE = Pattern.compile("^(?:\\((\\d+)/(\\d+)\\) )?Croesus$");

	/** Null-safe page number from a matched {@link #CROESUS_LIST_TITLE} — group 1 is absent on the real
	 *  single-page title ("Croesus" alone), which is always page 1 of 1. */
	private static int resolvePage(Matcher listMatch) {
		return listMatch.group(1) != null ? Integer.parseInt(listMatch.group(1)) : 1;
	}
	// Real, user-confirmed fixed slot layout for one Croesus list page's run icons — a 6-row/9-col chest GUI,
	// with the middle 4 rows' inner 7 columns (skipping the border column each side) holding one run icon
	// each, top-to-bottom/left-to-right = newest-to-oldest. Real bug found (per user report — "The slot math
	// is really wrong... it says slot 10 (position 10 on page 1)"): position used to be computed by counting
	// every non-empty, non-player-inventory slot in raw menu order, which also swept up whatever border/
	// filler items pad the other rows/columns — this fixed list is the actual real run-icon slots and
	// nothing else, so position is simply this array's own index now.
	private static final int[] CROESUS_RUN_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
		37, 38, 39, 40, 41, 42, 43
	};
	// Real Hypixel auto-requeue signal this whole tracker reuses verbatim, per user request ("Use the same
	// detection that auto-requeue uses") — see DungeonQueueFeature's own confirmed real EXTRA_STATS_MARKER.
	private static final String RUN_COMPLETE_MARKER = "EXTRA STATS";
	// Set the instant a click on the Croesus LIST screen resolves to a tracked run, per user spec ("detect
	// which slot is clicked") — promoted to activeRunIndexForTierPicker once a real NEW tier-picker screen
	// actually opens afterward (see onTick), so a click that doesn't lead anywhere never wrongly attaches.
	private Integer pendingCroesusRunIndex = null;
	// The run this SPECIFIC open tier-picker session is tied to, if it was reached via a Croesus click —
	// per user spec ("if a rolling sequence happens without croesus opening again the user is rolling the
	// clicked chest and not anything else"), this stays valid across multiple rolls within the same tier-
	// picker session and is only ever replaced by a fresh Croesus click, never by simply revisiting the
	// tier-picker on its own.
	private Integer activeRunIndexForTierPicker = null;
	// Lets onTick tell "a genuinely NEW tier-picker screen just opened" (the only moment a pending Croesus
	// click should ever get promoted to activeRunIndexForTierPicker) apart from "still looking at the same
	// one."
	private int lastTierPickerContainerId = -1;

	/** Removes every real-Croesus-expired entry — always safe to remove purely from the front (never the
	 *  middle): every entry shares the exact same {@link #RUN_TRACK_EXPIRY_MILLIS} duration and is appended
	 *  in strict chronological order (see {@link #trackedRuns}' own doc comment — oldest at the front, newest
	 *  appended at the end), so the OLDEST entry (the front) always expires first — mirroring Hypixel's own
	 *  real "oldest run ages off Croesus first" behavior exactly. */
	private void pruneExpiredRuns() {
		long now = System.currentTimeMillis();
		while (!trackedRuns.isEmpty() && trackedRuns.get(0).expiryMillis <= now) {
			trackedRuns.remove(0);
		}
	}

	/** Per user spec ("detect which slot is clicked"): fires the instant a real click on the Croesus LIST
	 *  screen resolves — the clicked slot's own position in {@link #CROESUS_RUN_SLOTS} (real bug found and
	 *  fixed this round: this used to count every non-empty, non-player-inventory slot in raw menu order,
	 *  which also swept up border/filler items and produced a wrong position — now the clicked slot's own
	 *  fixed-array index, or ignored entirely if it isn't one of the real run-icon slots at all, e.g. a page
	 *  nav arrow) resolves which tracked run it corresponds to. Sets {@link #pendingCroesusRunIndex} rather
	 *  than {@link #activeRunIndexForTierPicker} directly — a click that doesn't actually lead to a new
	 *  tier-picker screen opening should never wrongly attach to whatever tier-picker screen opens next. */
	private void onCroesusListClicked(int slotId) {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
		Matcher m = CROESUS_LIST_TITLE.matcher(screen.getTitle().getString());
		if (!m.matches()) return;
		int page = resolvePage(m);

		int position = -1;
		for (int i = 0; i < CROESUS_RUN_SLOTS.length; i++) {
			if (CROESUS_RUN_SLOTS[i] == slotId) { position = i; break; }
		}
		if (position < 0) return;

		pruneExpiredRuns();
		// See trackedRuns' own doc comment for this inverted-rank formula — index 0 is the OLDEST tracked
		// run, so the newest (rank 0, lowest slot) maps to the LAST index, not index 0.
		int rankFromNewest = (page - 1) * CROESUS_RUN_SLOTS.length + position;
		int trackedIndex = trackedRuns.size() - 1 - rankFromNewest;
		pendingCroesusRunIndex = trackedIndex >= 0 && trackedIndex < trackedRuns.size() ? trackedIndex : null;
	}

	/** Per user request ("please add a 'clear cache' button inside croesus that fully clears the list so i
	 *  can do testing"): wipes every tracked run outright — exposed for MainScreen's own new Croesus-panel
	 *  button. */
	public void clearTrackedRuns() {
		trackedRuns.clear();
		pendingCroesusRunIndex = null;
		activeRunIndexForTierPicker = null;
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	private int lastProcessedContainerId = -1;
	private Long rollStartMillis;
	private ItemStack winnerStack;
	// Per user request (rare-sound restriction spec): the "F5"/"M7"-style floor label this specific roll was
	// started for — see startRoll's own doc comment for why this is captured at roll-start time instead of
	// re-read fresh from DungeonState when the sound actually needs to play (real dungeon state can't be
	// trusted for a /chestopening test roll, which has no real floor to read at all).
	private String currentRollFloorLabel;
	// True for the current roll only when obfuscateRareItems is on AND the real winner is one of the 4
	// obfuscation-eligible names — mysteryStackForRoll (the exact object placed into the strip in that case)
	// lets drawCard tell "this specific card is the disguised winner" apart from a real mystery-shaped decoy
	// by simple reference equality, with no extra per-card flag needed.
	private boolean winnerObfuscated;
	private ItemStack mysteryStackForRoll;
	private final List<ItemStack> strip = new ArrayList<>();
	private int randomOffsetPx;
	private int lastSoundIndex;
	// Per user request ("Landing on an item with legendary rarity should play the entity.player.levelup
	// sound", broadened later to RARE+): guards computing isBigWinner() and playing the sound more than once
	// per roll (reset in startRoll()). An earlier round deferred the actual playback all the way to onTick(),
	// after isRollActive() fully flipped false, per a request that it "play after the mod has exited the
	// chest rolling animation" — that ended up a real ~2.5-second gap (LAND_HOLD_MILLIS + SETTLE_MILLIS) past
	// the visual landing, reported as "super late" (see renderRoll()'s own comment at the decision site) —
	// so it's back to playing immediately once the strip actually stops.
	private boolean landOutcomeDecided;
	// Per-roll shape, computed once in startRoll()/computeRollShape() from animationDurationSeconds — see
	// that method's own doc comment for why recomputing this every frame would be pointless (it only
	// depends on the configured duration, not on progress).
	private int winnerIndex;
	private float landSeconds;
	// Real redesign (per user request — "do the same fix they have for this [SkyOcean's DungeonGambling.kt],
	// the exact same method, with the already opened and whatever"): this used to be a persisted, content-
	// hashed "have I ever played this chest's animation" cache (chestContentsKey/playedChestKeys, now
	// removed) — but keying on chest type + slot contents meant two genuinely DIFFERENT real chests that
	// happened to land on the same combination of common/filler rewards (very plausible on the cheaper Wood/
	// Gold tiers) collided, silently skipping the second chest's roll entirely (confirmed real bug, per user
	// report — "the newer chests dont roll when i open them"). Read SkyOcean's own real source directly:
	// their equivalent (DungeonGambling.onScreenChange) does NOT persist anything at all — just a single
	// in-memory `menu: Int?` (this codebase's own lastProcessedContainerId, already doing the same job
	// against onTick's own re-detection) — and relies entirely on Hypixel's own real, already-opened chests
	// simply not carrying an ARROW/BARRIER marker item anymore (the exact same sawMarker check tryStartRoll
	// already does below) rather than remembering anything client-side. Matching that exact method here: no
	// separate persisted cache, no possible collision between unrelated chests — a chest either still has its
	// real marker item (roll it) or it doesn't (nothing to roll), which is also simply true across a client
	// restart or a later Croesus revisit, with no separate "remembered" state required to get that right.

	private static ChestRollingFeature instance;
	private static boolean commandRegistered = false;

	/** Whether the Chest Rolling module itself is toggled on — NOT whether a roll animation is currently
	 *  playing (see {@link #isRollActive()} for that). Exposed for {@link CroesusFeature}'s own profit-
	 *  highlight gating (see that class's {@code renderProfitHighlight} doc comment): it needs to know
	 *  whether revealing which chest is most profitable before any chest is even opened would spoil this
	 *  module's own CS-case-style suspense reveal, without duplicating its own copy of the enabled check. */
	public static boolean isModuleEnabled() {
		return instance != null && instance.isEnabled();
	}

	/** Exposed for CroesusFeature's own profit-highlight gating — it needs to read this module's own "Only
	 *  Bedrock Chests"/"Obsidian on lower floors" settings to know WHICH chest(s) actually need to be rolled
	 *  before it's safe to reveal the ranking (see CroesusFeature.requiredChestsRolled's own doc comment). */
	public static ChestRollingFeature getInstance() {
		return instance;
	}

	public ChestRollingFeature() {
		super("chest_rolling", "Chest Rolling", FeatureCategory.COMBAT, false);
		instance = this;
		// Per user request (Hide Lore redesign — "The 'Hide lore' in the chest rolling should show the lore
		// honestly, but instead of showing the drops and cost it should just fabricate. All lore on all chests
		// has a second line where it says 'Contents' followed by lines of the loot. Try to fabricate a question
		// mark on each line. Make sure its always gray aswell and doesnt match loot rarity. You should also
		// hide the cost... it should say '? Coins'"): outright suppressing the tooltip (the old behavior) read
		// as "broken" rather than "hidden" — a real Hypixel chest tooltip still shows its own name/header, so
		// this now REWRITES the tooltip in place instead of blanking it: the "Contents" header line and the
		// "Cost" header line both survive verbatim (so the tooltip's real shape is still recognizable), but
		// every actual reward line between them becomes a plain gray "?" (explicitly §7, never a rarity color,
		// per the user's own "doesnt match loot rarity" spec) and the real coin amount right after "Cost"
		// becomes "§7? Coins" — same "Contents"/reward-lines/"Cost"/"N Coins" lore shape CroesusFeature's own
		// computeChestProfit already confirmed real Hypixel chest lore uses (this is the exact same tier-picker
		// screen CroesusFeature's RUN_NAME/TIER_PICKER_SCREEN pattern also matches), so no separate cost-slot
		// lookup is needed — the real cost line is right there in this same stack's own lore. Uses
		// ItemTooltipCallback (a rewrite hook) instead of TooltipSuppressRegistry (suppress-only) since the
		// whole point now is "show fabricated content", not "show nothing".
		net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!isEnabled() || !hideLore) return;
			var screen = Minecraft.getInstance().gui.screen();
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			if (!TIER_PICKER_SCREEN.matcher(containerScreen.getTitle().getString()).matches()) return;
			Slot hovered = ((com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor) containerScreen).skyblocksimplified$getHoveredSlot();
			if (hovered == null || hovered.getItem() != stack) return;
			String tier = chestTierName(stack);
			if (tier == null || isOpenedChestOption(stack) || recentlyRolledTiers.contains(tier)) return;
			if (!hideLoreAllChests && !tier.equals(highestChestTierPresent(containerScreen))) return;
			fabricateChestLore(lines);
		});
		// Per user request: a way to trigger the roll animation in singleplayer without a real dungeon
		// chest to test against — "/chestopening" (optionally "/chestopening f3"/"m5" to pick which floor's
		// real drop pool to roll from, matching the f1-f7/m1-m7 naming ChatCommandsFeature already uses).
		// Registered once, unconditionally (same pattern as TermSimController/Boss Guide's own /bg
		// commands) — actual effect gated inside the handler.
		if (!commandRegistered) {
			commandRegistered = true;
			ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal("chestopening")
					.executes(ctx -> { testRoll(ctx.getSource(), null, null); return 1; })
					.then(ClientCommands.argument("floor", StringArgumentType.word())
						.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
							java.util.stream.IntStream.rangeClosed(1, 7).boxed()
								.flatMap(n -> java.util.stream.Stream.of("f" + n, "m" + n)), builder))
						.executes(ctx -> { testRoll(ctx.getSource(), StringArgumentType.getString(ctx, "floor"), null); return 1; })
						// Per user request ("Also add the /chestopening f7 handle/withershield/implosion/
						// shadowwarp to force a rare drop to test"): forces that exact drop as the roll's
						// winner instead of a random weighted pick, so the obfuscation toggle above (and the
						// rare-drop reveal generally) can actually be tested on demand.
						.then(ClientCommands.argument("rare", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(RARE_ARG_TO_NAME.keySet(), builder))
							.executes(ctx -> { testRoll(ctx.getSource(), StringArgumentType.getString(ctx, "floor"), StringArgumentType.getString(ctx, "rare")); return 1; })))));
		}
	}

	// Per user request ("Add support for forcing all of these drops in singleplayer so i can test how it
	// looks and stuff like that"): expanded from the original 4 (handle/withershield/implosion/shadowwarp) to
	// every drop the rare-landing-sound restriction (see ChestDropTables.RARE_SOUND_DROPS/isRareSoundDrop)
	// actually cares about, so each one can be tested on demand regardless of which floor it really drops on.
	private static final java.util.Map<String, String> RARE_ARG_TO_NAME = java.util.Map.ofEntries(
		java.util.Map.entry("handle", "Necron's Handle"),
		java.util.Map.entry("withershield", "Wither Shield"),
		java.util.Map.entry("implosion", "Implosion"),
		java.util.Map.entry("shadowwarp", "Shadow Warp"),
		java.util.Map.entry("giantssword", "Giant's Sword"),
		java.util.Map.entry("shadowfury", "Shadow Fury"),
		java.util.Map.entry("darkclaymore", "Dark Claymore"),
		java.util.Map.entry("necrondye", "Necron Dye"),
		java.util.Map.entry("masterstar1", "First Master Star"),
		java.util.Map.entry("masterstar2", "Second Master Star"),
		java.util.Map.entry("masterstar3", "Third Master Star"),
		java.util.Map.entry("masterstar4", "Fourth Master Star"),
		java.util.Map.entry("masterstar5", "Fifth Master Star"));

	/** Parses "f3"/"m5"-style floor selectors (master mode reuses the same per-floor drop pool — this table
	 *  has no separate master tier data, see the class doc comment) down to a bare floor number 1-7, or null
	 *  for anything else (including no argument at all, which rolls a random floor). */
	private static Integer parseFloorArg(String arg) {
		if (arg == null || arg.length() < 2) return null;
		char kind = Character.toLowerCase(arg.charAt(0));
		if (kind != 'f' && kind != 'm') return null;
		try {
			int n = Integer.parseInt(arg.substring(1));
			return n >= 1 && n <= 7 ? n : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static void testRoll(FabricClientCommandSource source, String floorArg, String rareArg) {
		// Dev testing command — singleplayer-only is the real safety rail here (see IslandGate check below).
		if (!com.cokelord.skyblocksimplified.util.IslandGate.isSingleplayer()) {
			source.sendError(Component.literal("§c/chestopening only works in singleplayer."));
			return;
		}
		if (instance == null || !instance.isEnabled()) {
			source.sendError(Component.literal("§cEnable Chest Rolling first."));
			return;
		}
		Integer floor = parseFloorArg(floorArg);
		int floorNumber = floor != null ? floor : 1 + ThreadLocalRandom.current().nextInt(7);
		if (floorArg != null && floor == null) {
			source.sendError(Component.literal("§cUnknown floor '" + floorArg + "'. Try f1-f7 or m1-m7."));
			return;
		}
		boolean masterMode = floorArg != null && !floorArg.isEmpty() && Character.toLowerCase(floorArg.charAt(0)) == 'm';

		String rareName = rareArg != null ? RARE_ARG_TO_NAME.get(rareArg.toLowerCase(Locale.ROOT)) : null;
		if (rareArg != null && rareName == null) {
			source.sendError(Component.literal("§cUnknown rare drop '" + rareArg + "'. Try one of: " + String.join("/", RARE_ARG_TO_NAME.keySet())));
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		int resolvedFloor = floorNumber;
		// Deferred to next tick: setScreen()/startRoll() called synchronously from inside a chat-command's
		// executes() callback loses a race against vanilla's own "close the chat screen after submitting"
		// logic, which runs right after and would silently null out (or, for startRoll, blow away
		// lastProcessedContainerId's freshly-opened screen) whatever we just did — the exact "the command
		// insta-closes itself" bug this project already found and fixed once for /termsim (see
		// TermSimController#startType's own doc comment on this same race). mc.execute() queues this for
		// after that finishes, which is the standard fix.
		mc.execute(() -> {
			if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) {
				mc.setScreenAndShow(new InventoryScreen(mc.player));
			}
			if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || instance == null) return;

			instance.lastProcessedContainerId = screen.getMenu().containerId;
			ChestDropTables.Drop winnerDrop = rareName != null
				? ChestDropTables.findObfuscationEligibleDrop(rareName)
				: ChestDropTables.randomDecoy(resolvedFloor, masterMode);
			if (winnerDrop == null) winnerDrop = ChestDropTables.randomDecoy(resolvedFloor, masterMode);
			// No real chest to read a tier from here — null is the most permissive tier (see startRoll's own
			// doc comment), so every drop stays testable regardless of chest-tier scoping.
			instance.startRoll(fillerItemStack(winnerDrop), resolvedFloor, masterMode, null);
		});
		source.sendFeedback(Component.literal("§aRolling a test chest for floor " + floorNumber
			+ (rareName != null ? " (forced: " + rareName + ")" : "") + "."));
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	public java.util.List<String> getSearchAliases() { return java.util.List.of("CS", "Case"); }

	private static boolean runTrackingListenersRegistered = false;

	@Override
	protected void onEnable() {
		// Real bug found (per user report — "cant move stuff from my storage to my inventory sometimes...
		// its in all menus where my inventory is showing"): this used to block a click on ANY open container
		// screen for the whole roll-animation window, with no check that the click was even happening on the
		// screen the roll is playing on — unlike shouldReplaceRender below, which correctly only replaces
		// rendering for the exact screen matching lastProcessedContainerId. If the roll's own chest screen
		// ever changed out from under it mid-animation (auto-closed by the server, player backed into their
		// inventory/storage, etc.) while rollStartMillis was still counting down, every click on the NEW
		// screen — including plain inventory-to-inventory moves — got silently swallowed with no visual
		// explanation, since the (correctly-scoped) animation itself wasn't even visible there anymore. Now
		// scoped to the same containerId check the render mixin already uses.
		ContainerClickRegistry.setRule("chest_rolling", (slot, slotId, mouseButton, type) -> {
			Minecraft mc = Minecraft.getInstance();
			if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return false;
			// Also blocks clicks during the new blanked "pending" window (see shouldReplaceRender's own doc
			// comment) — the screen is drawn blank there too now, so nothing should be clickable underneath.
			if (pendingChestScreen == screen) return true;
			// Real bug found (per user report — "theres a little bug that makes me unable to press the arrow
			// back button for like 3 seconds after rolling"): this used to gate on isRollActive(), which
			// stays true through the ENTIRE spin+hold+settle window (by default ~3.5s + 1s + 1.5s = 6s) — but
			// the real chest screen (including any of its own real nav items, like a back arrow) only stays
			// visually replaced by this module's own CS-strip render during isSpinning() (see
			// shouldReplaceRender below, which already only checks isSpinning() for exactly this reason); once
			// the strip visually stops and the real screen is showing again, blocking its clicks for another
			// ~2.5s of hold+settle serves no purpose the player can see — it just reads as an unresponsive
			// button. Narrowed to isSpinning() so clicks work again the moment the roll itself visually stops.
			if (!isSpinning()) return false;
			return screen.getMenu().containerId == lastProcessedContainerId;
		});

		// Per user request ("make sure its turned on if the chest rolling is on. It should automatically get
		// turned on"): forces Croesus's own client-side "Hide Opened Chests" toggle on whenever this feature
		// is enabled, since the run-tracking below assumes a stable, non-shifting Croesus list — real
		// Hypixel's own native hide-filter is documented by the user to shift positions when toggled, which
		// this mod-side one (a pure render dim, no real slot change) deliberately doesn't.
		com.cokelord.skyblocksimplified.feature.impl.CroesusFeature croesus =
			(com.cokelord.skyblocksimplified.feature.impl.CroesusFeature) com.cokelord.skyblocksimplified.feature.FeatureRegistry.get("croesus");
		if (croesus != null) croesus.setHideOpenedRuns(true);

		if (!runTrackingListenersRegistered) {
			runTrackingListenersRegistered = true;
			// See trackedRuns' own field doc comment. Real signal reused verbatim from Auto-
			// Requeue per user request, gated on this feature's own enabled state the same way every other
			// chat listener in this class already is.
			net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled() && message.getString().contains(RUN_COMPLETE_MARKER)) {
					// Per user report ("make sure its only on run completion, the user needs to be in the boss
					// the same run for it to add a run, cause there obviously are no chests and runs can also
					// end with everyone dying and the mod thinking a run completed instead of failed"): the
					// EXTRA STATS chat block fires on BOTH a genuine completion and a full-party wipe (the same
					// real signal DungeonQueueFeature's own Auto-Requeue already treats as "the run is over"
					// generally, for the same reason) — with no gate at all, a wipe added a phantom run to this
					// tracker even though a failed run never actually produces a real Croesus chest, silently
					// misaligning every position after it for the rest of the 3-day window. Gated on
					// DungeonState.isInBoss() as the user's own requested proxy for "this run actually reached
					// a real chest-rewarding state" — still reliably true at this exact instant, since
					// DungeonState only resets later, on the separate "instance will close" chat line.
					if (com.cokelord.skyblocksimplified.dungeon.DungeonState.isInBoss()) {
						instance.pruneExpiredRuns();
						// Appended at the END — see trackedRuns' own doc comment: index 0 is the OLDEST tracked
						// run, newest goes at the bottom.
						instance.trackedRuns.add(new TrackedRun(System.currentTimeMillis() + RUN_TRACK_EXPIRY_MILLIS, false));
						com.cokelord.skyblocksimplified.config.ConfigManager.save();
					}
				}
				return true;
			});
			// Per user spec ("detect which slot is clicked"): fires synchronously the instant a real click on
			// the Croesus LIST screen is allowed through (before vanilla's own click handling runs) — reads
			// the CLICKED slot's own position among every other real run-entry slot on the same screen/page to
			// resolve which tracked run it corresponds to.
			ContainerClickRegistry.setAllowedListener("chest_rolling_croesus_run", (slot, slotId, mouseButton, type) -> {
				if (instance == null || !instance.isEnabled()) return;
				try {
					instance.onCroesusListClicked(slotId);
				} catch (Exception e) {
					com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
						"Chest Rolling: Croesus run-click tracking failed, skipping this click", e);
				}
			});
		}
	}

	@Override
	protected void onDisable() {
		ContainerClickRegistry.clearRule("chest_rolling");
		rollStartMillis = null;
		pendingChestScreen = null;
	}

	// Real bug found (per user report — "The chest randomly stopped rolling for some reason. I rolled like
	// 2 of my 8 runs and the rest just didnt roll when i opened them"): onTick used to call tryStartRoll the
	// very first tick a new chest screen's title was seen, marking lastProcessedContainerId = containerId
	// BEFORE that call — but tryStartRoll's own marker-item (ARROW/BARRIER) scan and winner-price scan read
	// the container's real item stacks, which (same root cause already found and fixed for
	// InstanceChestProfitFeature's identical symptom — see that class's own AFTER_INIT doc comment) arrive
	// in a LATER, separate container-content-sync packet than the one that opens the screen (title/menu
	// type only). Whenever that packet hadn't landed yet on the exact tick the screen first appeared — a
	// real, server-lag-dependent race, not consistently reproducible, which is exactly why it looked
	// "random" and hit some runs but not others — sawMarker came back false and/or winner came back null,
	// and tryStartRoll silently gave up with NO retry ever scheduled, since this same containerId was
	// already recorded as handled. Polls for up to MAX_ROLL_WAIT_TICKS instead of evaluating immediately,
	// the same wait-for-real-sync pattern InstanceChestProfitFeature already uses for this exact packet-
	// ordering race.
	private static final int MAX_ROLL_WAIT_TICKS = 40; // 2 real seconds — same margin as InstanceChestProfitFeature.
	private AbstractContainerScreen<?> pendingChestScreen;
	private String pendingChestTypeName;
	private int pendingChestWaitTicks;

	@Override
	public void onTick(Minecraft client) {
		if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
			pendingChestScreen = null;
			// Real bug found (per user report — escaping out mid-roll left things in a stuck state): pressing
			// Escape mid-animation closes the screen instantly, but this used to leave rollStartMillis counting
			// down in the background regardless — isSpinning()/isRollActive() stayed true for whatever time was
			// left in the original window even though there's no longer any screen for that state to apply to.
			// Finalizing it here the moment there's no container screen at all removes that dangling window
			// outright instead of just waiting for it to time out on its own.
			rollStartMillis = null;
			// See recentlyRolledTiers' own field doc comment — cleared the moment the player leaves this
			// chest-picker/reward GUI flow entirely (no screen at all here).
			recentlyRolledTiers.clear();
			return;
		}
		// See recentlyRolledTiers' own field doc comment — also cleared for any OTHER real screen (backed all
		// the way out to a completely unrelated GUI), not just "no screen at all".
		String screenTitle = screen.getTitle().getString();
		Matcher croesusListMatch = CROESUS_LIST_TITLE.matcher(screenTitle);
		if (!croesusListMatch.matches() && !TIER_PICKER_SCREEN.matcher(screenTitle).matches() && !CHEST_TITLE_PATTERN.matcher(screenTitle.trim()).matches()) {
			recentlyRolledTiers.clear();
		}

		// ---- Croesus run tracking: page detection + tier-picker promotion (per user spec) ----
		if (croesusListMatch.matches()) {
			// Per user spec ("if a rolling sequence happens without croesus opening again the user is
			// rolling the clicked chest and not anything else"): the Croesus list screen being open AT ALL —
			// not just a fresh page swap — always ends whatever tier-picker "sequence" was previously active,
			// even if the page number itself happens to be unchanged from before (e.g. backing out of a
			// tier-picker straight back to the same page). The very next click always starts fresh.
			activeRunIndexForTierPicker = null;
		} else if (TIER_PICKER_SCREEN.matcher(screenTitle).matches()) {
			int tierPickerContainerId = screen.getMenu().containerId;
			if (tierPickerContainerId != lastTierPickerContainerId) {
				lastTierPickerContainerId = tierPickerContainerId;
				if (pendingCroesusRunIndex != null) {
					activeRunIndexForTierPicker = pendingCroesusRunIndex;
					pendingCroesusRunIndex = null;
				}
			}
		}

		if (isRollActive()) return;

		if (pendingChestScreen != null) {
			if (screen != pendingChestScreen) {
				// Screen closed/replaced before its contents ever synced — abandon rather than roll against a
				// menu that's no longer the one on-screen.
				pendingChestScreen = null;
			} else {
				pollPendingChestScreen(screen);
				return;
			}
		}

		int containerId = screen.getMenu().containerId;
		if (containerId == lastProcessedContainerId) return;

		String title = screen.getTitle().getString();
		var matcher = CHEST_TITLE_PATTERN.matcher(title.trim());
		if (!matcher.matches()) return;

		lastProcessedContainerId = containerId;
		pendingChestTypeName = matcher.group(1);
		pendingChestWaitTicks = 0;
		pendingChestScreen = screen;
		pollPendingChestScreen(screen);
	}

	private void pollPendingChestScreen(AbstractContainerScreen<?> screen) {
		var playerInventory = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getInventory() : null;
		boolean hasAnyItem = false;
		for (Slot slot : screen.getMenu().slots) {
			if (playerInventory != null && slot.container == playerInventory) continue;
			if (!slot.getItem().isEmpty()) { hasAnyItem = true; break; }
		}
		if (hasAnyItem || ++pendingChestWaitTicks >= MAX_ROLL_WAIT_TICKS) {
			String typeName = pendingChestTypeName;
			pendingChestScreen = null;
			tryStartRoll(typeName, screen);
		}
	}

	// Per user request ("Add the new sound options for the chest rolling aswell. Also add a way to disable
	// the sound cause i forgot that"): the landing sound now goes through the same shared CustomSoundOption
	// every other sound-picking module in the mod uses (built-in choice OR an imported file, with real
	// volume/pitch/deadspace) instead of a hardcoded single playSound call. "PRAY TO RNGESUS" (this mod's own
	// real Hypixel-jingle sound event, shipped as assets/skyblocksimplified/sounds/pray_to_rngesus.ogg and
	// declared in sounds.json) stays the default built-in choice — CustomSoundOption.playBuiltin() plays a
	// built-in id through the exact same Identifier.tryParse + player.playSound(SoundEvent, volume, pitch)
	// mechanism this custom event already worked through, so wiring it in as builtin index 0 is a drop-in
	// replacement, not a behavior change, for anyone who hasn't touched the new settings.
	private static final String[] LAND_SOUND_IDS = {
		com.cokelord.skyblocksimplified.SkyblockSimplified.MOD_ID + ":chest_rolling.pray_to_rngesus",
		"entity.player.levelup", "block.note_block.pling", "entity.experience_orb.pickup"
	};
	private static final String[] LAND_SOUND_LABELS = {"PRAY TO RNGESUS", "Level Up", "Note Block Pling", "Orb Pickup"};
	private final com.cokelord.skyblocksimplified.sound.CustomSoundOption landSound =
		new com.cokelord.skyblocksimplified.sound.CustomSoundOption(LAND_SOUND_IDS, LAND_SOUND_LABELS);
	// Per user report ("also add a way to disable the sound cause i forgot that"): no such toggle existed at
	// all before — the sound was always unconditionally on.
	private boolean landSoundEnabled = true;

	// CustomSoundOption's own volume/pitch default to 1f each, but the old hardcoded call this replaces
	// played at 0.6f specifically (per user report — "a bit loud") — applied here as landSound's own default
	// so a fresh install keeps that same real-world loudness before any config load might override it.
	{ landSound.setVolume(0.6f); }

	private void playLandSound() {
		if (landSoundEnabled) landSound.play();
	}

	private void tryStartRoll(String chestTypeName, AbstractContainerScreen<?> screen) {
		boolean viaCroesus = false;
		boolean sawMarker = false;
		for (Slot slot : screen.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.is(Items.ARROW)) { viaCroesus = true; sawMarker = true; break; }
			if (stack.is(Items.BARRIER)) { viaCroesus = false; sawMarker = true; break; }
		}
		if (!sawMarker) {
			// Real bug found (per user report — "some chests just dont get rolled for some reason, it just
			// instantly opens no rolling animation"): pollPendingChestScreen's own "ready" signal is just ANY
			// single non-empty slot appearing — if the server happens to sync ordinary reward items a tick or
			// two before this Croesus/normal-open marker item specifically, tryStartRoll used to fire and bail
			// out HERE permanently (a bare return, with lastProcessedContainerId already marked as handled
			// back in onTick — see its own doc comment — so nothing ever revisits this exact container again).
			// The chest then just opens normally with no roll at all, even though the marker was only ever a
			// few ticks away from syncing. Re-arms the same poll instead of giving up outright, bounded by the
			// same MAX_ROLL_WAIT_TICKS budget pollPendingChestScreen already uses for the initial wait.
			if (pendingChestWaitTicks < MAX_ROLL_WAIT_TICKS) {
				pendingChestScreen = screen;
			}
			return;
		}

		// Real bug found (per user report — "if i have it so only bedrock/obsidian on lower floors gets
		// opened, that means i can open a lower chest without rolling anything and it doesnt hide it and
		// remove it from the list"): this "chest was genuinely just opened" bookkeeping used to live AFTER
		// the Only Bedrock/Only Croesus animation gates below, which return early for any tier those settings
		// don't want US to animate — but the real chest purchase already happened server-side the moment
		// sawMarker confirms this is a genuine reward screen, regardless of whether OUR animation plays for
		// this specific tier. Moved above the animation gates so a lower-tier chest opened while Only Bedrock
		// Chests is on still gets marked claimed (Hide Lore's reshow suppression, Croesus's own Hide Opened
		// Chests hide/click-block) even though it never plays the CS-case animation.
		String normalizedTier = "wooden".equalsIgnoreCase(chestTypeName) ? "Wood"
			: Character.toUpperCase(chestTypeName.charAt(0)) + chestTypeName.substring(1).toLowerCase(Locale.ROOT);
		recentlyRolledTiers.add(normalizedTier);
		// Per user redesign — see trackedRuns'/TrackedRun's own doc comment: this is the one place a genuine
		// chest-open is already confirmed (sawMarker, above every animation gate), so it's also the right
		// place to permanently mark the SPECIFIC tracked run this tier-picker session was opened from (set
		// only when reached via a real Croesus list click — see activeRunIndexForTierPicker's own doc
		// comment) as rolled, rather than relying on recentlyRolledTiers' session-only memory that forgets
		// the moment the player leaves the tier-picker screen.
		boolean wasAlreadyRolled = false;
		if (activeRunIndexForTierPicker != null && activeRunIndexForTierPicker < trackedRuns.size()) {
			TrackedRun run = trackedRuns.get(activeRunIndexForTierPicker);
			wasAlreadyRolled = run.rolled;
			run.rolled = true;
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
		}
		// Per user report ("The tracking now works perfectly... but now doesnt actually stop the rolling from
		// happening if the chest has been rolled"): the whole point of the `rolled` flag is to know when a
		// specific tracked run's chest has already been claimed once — reaching this exact point again for
		// the SAME run (`rolled` was already true before the line above) means this is a re-open of an
		// already-rolled chest, so the CS-case animation shouldn't play a second time. Same early-return shape
		// as the onlyBedrock/onlyCroesus gates right below (the real screen still renders normally underneath,
		// nothing here ever hides/blocks the vanilla GUI itself — only OUR animation is skipped).
		if (wasAlreadyRolled) return;

		boolean bedrock = "bedrock".equalsIgnoreCase(chestTypeName);
		if (onlyBedrock && !bedrock) {
			boolean obsidian = "obsidian".equalsIgnoreCase(chestTypeName);
			int floorNumber = com.cokelord.skyblocksimplified.dungeon.DungeonState.getFloorNumber();
			boolean noBedrockOnThisFloor = floorNumber > 0 && floorNumber <= 4;
			if (!(obsidianOnLowerFloors && obsidian && noBedrockOnThisFloor)) return;
		}
		if (onlyCroesus && !viaCroesus) return;

		// Real bug found (per user report — "the chest animation also lands on random items that arent
		// physically possible, and it also doesnt land on the item its supposed to land on"): this scanned
		// EVERY slot in the menu with no exclusion for the player's OWN inventory slots (also present in a
		// container Menu's slot list) — unlike InstanceChestProfitFeature's equivalent scan, which already
		// correctly skips `slot.container == playerInventory`. Any genuinely valuable item already sitting
		// in the player's hotbar/inventory (their weapon, armor, a stack of books — extremely common mid-
		// dungeon) priced higher than the actual chest reward got picked as "winner" instead, so the
		// animation landed on a real inventory item that was never actually a drop from this chest at all.
		var playerInventory = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getInventory() : null;
		ItemStack winner = null;
		double bestPrice = -1;
		for (Slot slot : screen.getMenu().slots) {
			if (playerInventory != null && slot.container == playerInventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty() || isGlassPaneFiller(stack)) continue;
			double p = priceForWinnerRanking(stack);
			if (winner == null || p > bestPrice) {
				winner = stack.copy();
				bestPrice = p;
			}
		}
		if (winner == null) return;

		String realFloorLabel = com.cokelord.skyblocksimplified.dungeon.DungeonState.getFloorLabel();
		boolean realMasterMode = realFloorLabel != null && realFloorLabel.startsWith("M");
		startRoll(winner, com.cokelord.skyblocksimplified.dungeon.DungeonState.getFloorNumber(), realMasterMode, chestTypeName);
	}

	// Real bug found (per user report — "chest rolling sometimes lands on a glass pane"): Hypixel pads unused
	// cells of many of its own GUIs (terminals, this chest-reward screen included) with a stained-glass-pane
	// filler item — same real convention already relied on elsewhere in this codebase (see
	// TerminalTracker#paneColorOf, StorageOverlayFeature's own gray-glass "empty slot" check). A filler pane
	// has no real price and priceForWinnerRanking correctly returns 0 for it, but so does any genuine reward
	// item this codebase's price lookup simply doesn't recognize (a real, separate gap tracked elsewhere) —
	// when that happens, the winner loop's "first slot wins on a price tie" rule could let an EARLIER filler
	// pane beat a LATER real-but-unpriced reward for the "winner" slot. Filler panes are never themselves a
	// real drop, so excluding them from the candidate pool entirely removes this failure mode regardless of
	// whether the real reward's price is ever fixed.
	private static boolean isGlassPaneFiller(ItemStack stack) {
		return stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem
			&& blockItem.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock;
	}

	private static final java.util.regex.Pattern WINNER_COLOR_CODE = java.util.regex.Pattern.compile("§.");

	/** Real bug found (per user report — "the final drop should always be the most expensive one, this rules
	 *  out if the user gets a book and a wither scroll and it lands on a book instead when it shouldve been a
	 *  wither scroll"): the winner-ranking loop below priced every reward purely off its raw NBT id via {@link
	 *  com.cokelord.skyblocksimplified.api.ItemPriceUtil#priceOf}, and Hypixel's ExtraAttributes "id" for EVERY
	 *  enchanted book is the same generic literal "ENCHANTED_BOOK" regardless of which enchant it actually is
	 *  (the real enchant/level lives in a separate NBT field, not "id") — the exact same root cause already
	 *  found and fixed for Instance Chest Profit's book pricing (see {@link InstanceChestProfitFeature#priceOf}).
	 *  A specific book (e.g. a real Wither Scroll-tier enchant) priced as the bare generic id can end up
	 *  outranking a genuinely cheaper-priced-but-correctly-resolved item, or vice versa — either way the
	 *  ranking wasn't using each item's real value. Tries the enchant-specific book price first (same as the
	 *  other reward scanners in this codebase), only falling back to the raw-id lookup when the item isn't a
	 *  recognized specific-enchant book name. */
	private static double priceForWinnerRanking(ItemStack stack) {
		String plainName = WINNER_COLOR_CODE.matcher(stack.getHoverName().getString()).replaceAll("");
		Double bookPrice = com.cokelord.skyblocksimplified.combat.ChestValueEstimator.priceOfEnchantedBookName(plainName);
		if (bookPrice != null) return bookPrice;
		String id = SkyblockNbtUtils.getItemId(stack);
		if (id == null) return 0;
		Double price = com.cokelord.skyblocksimplified.api.ItemPriceUtil.priceOf(NeuInternalName.of(id));
		return price != null ? price : 0;
	}

	/** Per user request ("Add support for forcing all of these drops in singleplayer so i can test how it
	 *  looks and stuff like that"): {@code floorNumber}/{@code masterMode} are now passed in explicitly by the
	 *  caller (the real chest's own live floor state for {@link #tryStartRoll}, the test command's chosen
	 *  floor argument for {@code testRoll}) instead of this method re-deriving them fresh from
	 *  {@code DungeonState} itself — the test command's f/m floor argument used to have NO effect on the
	 *  decoy strip's masterMode filtering at all, since this re-derivation ignored it and read whatever the
	 *  (likely stale or absent, in singleplayer) real dungeon state said instead. Also the single source of
	 *  truth for {@link #currentRollFloorLabel}, which {@link #isBigWinner()} checks the winner's name against.
	 *  {@code chestTypeName} (null for the test command, which has no real chest to read one from — treated
	 *  as the most permissive tier, see {@link ChestDropTables#chestTierRank}) scopes the decoy strip to
	 *  drops that can actually come from that real chest tier, per user request ("make sure the drops match
	 *  their respective chests... I dont want to scroll past a necrons handle when rolling a wooden chest"). */
	private void startRoll(ItemStack winner, int floorNumber, boolean masterMode, String chestTypeName) {
		winnerStack = winner;
		currentRollFloorLabel = (masterMode ? "M" : "F") + floorNumber;
		rollStartMillis = System.currentTimeMillis();
		lastSoundIndex = -1;
		landOutcomeDecided = false;
		// Small fixed-pixel jitter so the pointer doesn't land at the exact dead-center of a card every
		// time — independent of card size now that cards are sized dynamically per screen width (see
		// VISIBLE_CARDS_TARGET's own doc comment), unlike the old CARD_SCALE-multiplied version.
		randomOffsetPx = ThreadLocalRandom.current().nextInt(-15, 16);

		// Real redesign (per user request — "It should land on items that are actually in the dungeon, like
		// enchanted books, precursor gears, wither skulls... make sure to add 'Weight' to drops based on drop
		// chance. I dont want stuff like a necrons handle being fake scrolled past the same amount as a
		// rejuvenate 1"): decoys now come from ChestDropTables' real per-floor weighted drop data instead of
		// a uniform-random pool of plain reskinned vanilla items with no connection to actual dungeon loot.
		// Per user follow-up ("It should show items based on the list I gave you of possible drops, and
		// fake some stuff... like a real csgo case does"): buildDecoyStrip guarantees a shuffled sprinkling
		// of this floor's own rare/expensive drops throughout the strip and avoids the same exact filler
		// repeating back-to-back — see its own doc comment for why plain per-card weighted sampling (the
		// old loop here) read as "the same book shown 1000 times" instead.
		computeRollShape();
		strip.clear();
		int stripLength = winnerIndex + WINNER_OVERSHOOT_CARDS;
		int chestTierRank = ChestDropTables.chestTierRank(chestTypeName);
		for (ChestDropTables.Drop drop : ChestDropTables.buildDecoyStrip(floorNumber, masterMode, stripLength, chestTierRank)) strip.add(fillerItemStack(drop));

		// Per user request ("instead of landing on a necron handle it becomes more like the CS2 cases, where
		// it lands on a legendary item... and then reveals what you got when it lands"): matched by plain
		// substring against the winner's own real hover text rather than an exact-name check, so this works
		// for BOTH a decoy-built winner (fillerItemStack's own "§7<name>" format) and a real live chest
		// drop's actual Hypixel-colored name — both always contain the plain drop name verbatim somewhere.
		String winnerHoverText = winner.getHoverName().getString();
		winnerObfuscated = obfuscateRareItems
			&& ChestDropTables.isObfuscationEligible(currentRollFloorLabel, winnerHoverText);
		if (winnerObfuscated) {
			mysteryStackForRoll = mysteryStack();
			strip.set(winnerIndex, mysteryStackForRoll);
		} else {
			mysteryStackForRoll = null;
			strip.set(winnerIndex, winner);
		}
		// Per user request ("make the rare items have a 5% chance to place next to the actual item the user
		// gets, so it scrolls past/stops right next to them but is really really close to getting something
		// rare"): only possible here, not inside buildDecoyStrip itself — that method has no idea where the
		// winner will actually land, since winnerIndex is computed independently from roll-duration kinematics
		// (computeRollShape, above) and spliced in afterward.
		// Real bug found (per user report — "It seems to throw in drops that arent even possible"):
		// ChestDropTables.randomRareShowpiece() only ever returns real Floor 7/M7 items (Necron's Handle, the
		// wither scrolls, Dark Claymore, Master Skull VII) — placing one next to the winner on ANY floor meant
		// Floor 1-6 rolls could show an impossible drop for that floor. Gated to Floor 7 only, same floor its
		// pool is actually drawn from.
		if (floorNumber == 7 && strip.size() > 1 && ThreadLocalRandom.current().nextInt(100) < 5) {
			ChestDropTables.Drop showpiece = ChestDropTables.randomRareShowpiece(masterMode, chestTierRank);
			if (showpiece != null) {
				int neighborIndex = winnerIndex == 0 ? 1 : winnerIndex - 1;
				strip.set(neighborIndex, fillerItemStack(showpiece));
			}
		}
	}

	// Per user request ("Making the time for the roll higher should also show more items at the same speed
	// instead of slowing it down"): models the roll as real kinematics instead of an abstract 0-1 progress
	// curve — a short quadratic "speed up really fast" ramp, a cruise phase at a FIXED real speed
	// (CRUISE_CARDS_PER_SECOND, calibrated so the old 3.5s/40-card default feel is unchanged), then a
	// smooth exponential-decay landing over the same duration-aware ~2-second window this class already
	// used. Because cruise speed is a real constant independent of duration, and winnerIndex is just "however
	// many cards fit in that much cruising" for a given duration, a longer roll shows proportionally more
	// cards at the same visual speed rather than the same card count stretched thinner.
	private static final float RAMP_SECONDS = 0.15f;
	private static final float LAND_SECONDS_CAP = 2.0f;
	private static final float LAND_FRACTION_CAP = 0.5f;
	// Exponential decay time-constant as a fraction of the landing window: tau = landSeconds * this, so by
	// the end of the window (5 time-constants in) velocity has decayed to exp(-5) =~ 0.7% of cruise speed —
	// visually stopped without an artificial hard clamp to exactly zero.
	private static final float LAND_DECAY_TAU_FRACTION = 0.2f;

	private void computeRollShape() {
		landSeconds = Math.min(LAND_SECONDS_CAP, animationDurationSeconds * LAND_FRACTION_CAP);
		float cruiseSeconds = Math.max(0f, animationDurationSeconds - RAMP_SECONDS - landSeconds);
		float rampDist = CRUISE_CARDS_PER_SECOND * RAMP_SECONDS / 2f;
		float cruiseDist = CRUISE_CARDS_PER_SECOND * cruiseSeconds;
		float tau = landSeconds * LAND_DECAY_TAU_FRACTION;
		float landDist = tau > 0f ? CRUISE_CARDS_PER_SECOND * tau * (1f - (float) Math.exp(-landSeconds / tau)) : 0f;
		winnerIndex = Math.max(MIN_WINNER_INDEX, Math.round(rampDist + cruiseDist + landDist));
	}

	/** Real distance (in cards, fractional) scrolled after {@code elapsedSeconds} of real roll time — the
	 *  smooth replacement for the old 0-1 {@code ease()} curve, see {@link #computeRollShape()}'s doc
	 *  comment for the overall model. C1-continuous (matching velocity) at the ramp/cruise boundary by
	 *  construction; the cruise/landing boundary has at most a sub-1-card rounding wobble from winnerIndex
	 *  being rounded to an integer card index, imperceptible in practice and far smoother than the old
	 *  piecewise-quadratic curve's hard velocity discontinuities. */
	private float cardsScrolled(float elapsedSeconds) {
		if (elapsedSeconds <= 0f) return 0f;
		float cruiseSeconds = Math.max(0f, animationDurationSeconds - RAMP_SECONDS - landSeconds);
		float landStart = RAMP_SECONDS + cruiseSeconds;
		float rampDist = CRUISE_CARDS_PER_SECOND * RAMP_SECONDS / 2f;

		if (elapsedSeconds < RAMP_SECONDS) {
			float local = elapsedSeconds / RAMP_SECONDS;
			return rampDist * local * local;
		}
		if (elapsedSeconds < landStart) {
			return rampDist + CRUISE_CARDS_PER_SECOND * (elapsedSeconds - RAMP_SECONDS);
		}
		float cruiseDist = CRUISE_CARDS_PER_SECOND * cruiseSeconds;
		float tau = landSeconds * LAND_DECAY_TAU_FRACTION;
		if (tau <= 0f) return winnerIndex;
		float tSinceLand = Math.min(elapsedSeconds - landStart, landSeconds);
		float rawLandDist = CRUISE_CARDS_PER_SECOND * tau * (1f - (float) Math.exp(-tSinceLand / tau));
		float fullLandDist = CRUISE_CARDS_PER_SECOND * tau * (1f - (float) Math.exp(-landSeconds / tau));
		// Renormalized so the curve lands EXACTLY on winnerIndex at elapsedSeconds == duration — a pure
		// exponential decay only ever approaches (never exactly reaches) its asymptote in finite time.
		float landDist = fullLandDist > 0f ? (winnerIndex - rampDist - cruiseDist) * (rawLandDist / fullLandDist) : 0f;
		return rampDist + cruiseDist + landDist;
	}

	private static final java.util.Set<net.minecraft.world.item.Item> LEATHER_ARMOR_ICONS = java.util.Set.of(
		Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
	// Real black, matching the user's own confirmation ("Wither armor is fully black leather armor") — same
	// RGB vanilla leather armor renders as when fully un-dyed color 0x000000 is applied.
	private static final int WITHER_ARMOR_BLACK = 0x1D1D1D;
	// Real confirmed hex colors, cross-checked this round against a live Hypixel API pull (both exact matches):
	// Adaptive Chestplate/Boots/Leggings are a real Catacombs Floor III drop dyed 0xBFBCB2; Spirit Boots (the
	// Floor 4 Spirit set) is dyed 0xBFBFBF. Keyed by drop name so fillerItemStack's existing LEATHER_ARMOR_ICONS
	// dye branch below can pick the right color per drop instead of defaulting every leather-icon drop to
	// Wither's black. Real bug found (per this round's live API pull): unlike their leather-armor siblings,
	// Adaptive Helmet/Spirit Mask/Shadow Assassin Helmet are all real player-head-based items (material
	// SKULL_ITEM) — no longer dyed leather Drop icons at all (see ChestDropTables), so they don't belong here.
	// Real bug found (per user report — "The shadow assassin chestplate is #000000"): confirmed via a live
	// Hypixel API pull that both Shadow Assassin leather pieces are exactly 0x000000 (pure black), not the
	// slightly-lighter 0x1D1D1D this table's own WITHER_ARMOR_BLACK default was previously giving them.
	private static final java.util.Map<String, Integer> LEATHER_DYE_OVERRIDES = java.util.Map.of(
		"Adaptive Chestplate", 0xBFBCB2,
		"Adaptive Boots", 0xBFBCB2,
		"Adaptive Leggings", 0xBFBCB2,
		"Spirit Boots", 0xBFBFBF,
		"Shadow Assassin Chestplate", 0x000000,
		"Shadow Assassin Boots", 0x000000);

	// Real bug found (per user follow-up, providing a screenshot of the real in-game icon — a frosty pale
	// blue-gray head with dark speckled eyes/jaw): the Spirit Pet has no id in Hypixel's public items API at
	// all (pets genuinely aren't in that resource — see this table's own earlier "researched, unresolvable"
	// note), so it could never go through the normal SKYBLOCK_ID_OVERRIDES/SkyblockItemIcons path. Found the
	// real skin anyway via the community-maintained NEU repo's own "SPIRIT;3"/"SPIRIT;4" (Epic/Legendary-tier
	// Spirit pet) item definitions, which both store the exact same Mojang skin hash Hypixel's own client
	// uses for this real drop regardless of rarity — confirmed by rendering the actual texture and matching
	// it against the user's screenshot pixel for pixel. Hardcoded here (not resolvable at runtime from any
	// live API this project already polls), same "raw texture hash" pattern InvincibilityTimerFeature's own
	// boss-icon skulls already use.
	private static final String SPIRIT_PET_SKIN_HASH =
		"8d9ccc670677d0cebaad4058d6aaf9acfab09abea5d86379a059902f2fe22655";

	private static ItemStack fillerItemStack(ChestDropTables.Drop drop) {
		if ("[EPIC] Spirit Pet".equals(drop.name()) || "[LEGENDARY] Spirit Pet".equals(drop.name())) {
			ItemStack stack = com.cokelord.skyblocksimplified.util.SkullTextureUtil.fromTextureHash(SPIRIT_PET_SKIN_HASH);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7" + drop.name()));
			return stack;
		}
		// Real icon first (see ChestDropTables.SKYBLOCK_ID_OVERRIDES' own doc comment) — falls back to the
		// existing hand-picked vanilla icon whenever this table has no confirmed id for the drop, or the
		// real repo doesn't have it loaded/resolvable yet.
		String skyblockId = ChestDropTables.skyblockIdFor(drop.name());
		ItemStack realIcon = skyblockId != null ? com.cokelord.skyblocksimplified.util.SkyblockItemIcons.getIcon(skyblockId) : null;
		ItemStack stack = realIcon != null ? realIcon.copy() : new ItemStack(drop.icon());
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7" + drop.name()));
		if (realIcon == null && LEATHER_ARMOR_ICONS.contains(drop.icon())) {
			int dyeColor = LEATHER_DYE_OVERRIDES.getOrDefault(drop.name(), WITHER_ARMOR_BLACK);
			stack.set(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(dyeColor));
		}
		// Real bug found (per user correction — "The scrolls and hot potato books still dont have enchant
		// glint. Nothing does infact. Everything that isnt a skull should have glint"): the PREVIOUS round's
		// fix had this exactly backwards (glint only for "Ench. Book:" entries, off for everything else) —
		// the real rule is the opposite: every decoy glints EXCEPT the skull-shaped ones (Master Skulls,
		// Wither Catalyst, Fel Skull — real vanilla WITHER_SKELETON_SKULL/PLAYER_HEAD icons, which never
		// glint on a real skull in-game). Checked against the actual resolved stack's item (not drop.icon(),
		// which only reflects the hand-picked vanilla fallback and not a real-icon override that resolves to
		// a different item entirely — e.g. a real-icon player-head skin for a drop whose fallback wasn't one).
		boolean isSkull = stack.getItem() == Items.PLAYER_HEAD || stack.getItem() == Items.WITHER_SKELETON_SKULL;
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, !isSkull);
		// Real bug found (per user report — "The chest rolling items dont have any rarity"): fillerItemStack
		// never set any lore at all, so accentColorOf's lore-color-scan (winner cards) always fell through to
		// its plain-gray default, no matter what the item actually was — there was nothing there to read.
		// Real Hypixel rarity is structural Style color, not literal text (see accentColorOf's own doc
		// comment and this exact class of bug elsewhere in the project), so this needs a real styled
		// Component, not a "§9RARE" string — ChatFormatting.withStyle applies real TextColor.
		net.minecraft.ChatFormatting rarity = ChestDropTables.rarityOf(drop.name());
		if (rarity != null) {
			// Real bug found (per user report — "Make the prayrngesus sound play with the command aswell"):
			// this used to write rarity.name() as the lore text (e.g. "GOLD"/"DARK_PURPLE"/"BLUE", the
			// ChatFormatting enum's own name) instead of the actual rarity word ("LEGENDARY"/"EPIC"/"RARE") —
			// isBigWinner()'s lore-text check never matched, so /chestopening's forced-winner stacks (built
			// through this same method) could never trigger the landing sound, even though a real dungeon
			// drop's genuine Hypixel-supplied lore text always does. mysteryStack() below already did this
			// correctly (a literal "LEGENDARY" string) — this now matches that, and still keeps the real
			// ChatFormatting Style color so accentColorOf's unrelated lore-color-scan is unaffected.
			String rarityLabel = RARITY_LABELS.getOrDefault(rarity, rarity.name());
			stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
				java.util.List.of(Component.literal(rarityLabel).withStyle(rarity))));
		}
		return stack;
	}

	// Real bug found (this round, alongside the Master Skull rarity rework): WHITE/GREEN had no entry here at
	// all, so the getOrDefault fallback below wrote the literal ChatFormatting enum name ("WHITE"/"GREEN") as
	// the item's visible lore line instead of a real rarity word — never previously noticed since nothing
	// used those two colors until this round's Master Skull Common/Uncommon tiers.
	private static final java.util.Map<net.minecraft.ChatFormatting, String> RARITY_LABELS = java.util.Map.of(
		net.minecraft.ChatFormatting.WHITE, "COMMON",
		net.minecraft.ChatFormatting.GREEN, "UNCOMMON",
		net.minecraft.ChatFormatting.BLUE, "RARE",
		net.minecraft.ChatFormatting.DARK_PURPLE, "EPIC",
		net.minecraft.ChatFormatting.GOLD, "LEGENDARY");

	// Per user request ("I resent the noamm and skyhanni source... Look at image 1 in this message and its
	// there. Its the golden thing"): the user's own custom gold wreath/"?" badge image, drawn in place of
	// the vanilla golden chestplate icon (see drawCard's own use of this) whenever the card being rendered
	// is the disguised mystery stack. Untinted (0xFFFFFFFF) — this is a real full-color image, not a
	// tintable white silhouette like drawIcon's own textures in MainScreen.
	private static final net.minecraft.resources.Identifier MYSTERY_TEXTURE = net.minecraft.resources.Identifier.fromNamespaceAndPath(
		com.cokelord.skyblocksimplified.SkyblockSimplified.MOD_ID, "textures/gui/chest_rolling_mystery.png");

	// Per user request ("Make it display golden nautilus armor with a question mark over rare drops if this
	// is enabled"): a real, always-resolvable golden armor piece (no dependency on resolving a specific
	// Skyblock icon texture, unlike fillerItemStack's real-icon lookups above) stands in for the disguised
	// item — glinting, with a fixed LEGENDARY-gold lore line so accentColorOf's own lore-color-scan reads it
	// as gold too, rather than showing the real item's true rarity color early and giving away what it is.
	// Still used for the card's own accent-color border; the icon itself is now the user's own custom image
	// (MYSTERY_TEXTURE, drawn in drawCard) instead of this stack's real golden-chestplate render.
	private static ItemStack mysteryStack() {
		ItemStack stack = new ItemStack(Items.GOLDEN_CHESTPLATE);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6???"));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
			java.util.List.of(Component.literal("LEGENDARY").withStyle(net.minecraft.ChatFormatting.GOLD))));
		return stack;
	}

	// Per user request ("I also want the item name popup to not have an animation, and instead of showing
	// when it lands on an item, show it in the chest menu where the real item and the chest itself is
	// shown"): split what used to be one "isRollActive" window (the full scroll animation PLUS the old
	// SETTLE_MILLIS grace period) into two distinct phases. isSpinning() is the CS2-strip full-screen
	// replacement (shouldReplaceRender below); isSettling() is the brief window right after it ends where
	// the REAL chest screen renders normally again (the mixin no longer intercepts it) — that settle window's
	// own plain post-roll name label (which used to render here) was removed per a later user request
	// ("remove post-drop text"), so this phase is now just a brief click-blocked pause before the real chest
	// screen is fully interactive again.
	// Real bug found (per user report — "unable to press the arrow back button for like 3 seconds after
	// rolling"): click-blocking used to gate on isRollActive() (spin+hold+settle combined) so the player
	// couldn't "snatch the reveal away before they see it" — but the settle phase is exactly the window where
	// the real screen (including its own real nav items) is ALREADY showing normally again, so blocking its
	// clicks for that extra ~1.5s had no visible protective effect, just an unresponsive-feeling button.
	// Click-blocking (see onEnable's own ContainerClickRegistry rule) now only gates on isSpinning() — still
	// covers the one phase that genuinely needs it (the CS-strip is the only thing actually being protected).
	private boolean isSpinning() {
		if (!isEnabled() || rollStartMillis == null) return false;
		long elapsed = System.currentTimeMillis() - rollStartMillis;
		return elapsed < Math.round(animationDurationSeconds * 1000) + LAND_HOLD_MILLIS;
	}

	private boolean isSettling() {
		if (!isEnabled() || rollStartMillis == null) return false;
		long elapsed = System.currentTimeMillis() - rollStartMillis;
		long spinMillis = Math.round(animationDurationSeconds * 1000) + LAND_HOLD_MILLIS;
		return elapsed >= spinMillis && elapsed < spinMillis + SETTLE_MILLIS;
	}

	private boolean isRollActive() {
		if (!isEnabled() || rollStartMillis == null) return false;
		long elapsed = System.currentTimeMillis() - rollStartMillis;
		long total = Math.round(animationDurationSeconds * 1000) + LAND_HOLD_MILLIS + SETTLE_MILLIS;
		if (elapsed >= total) {
			rollStartMillis = null;
			return false;
		}
		return true;
	}

	// ---- Mixin hooks (ChestRollingCustomGuiBackgroundMixin/ChestRollingCustomGuiRenderMixin) ----

	public static boolean shouldReplaceRender(AbstractContainerScreen<?> screen) {
		if (instance == null) return false;
		if (instance.isSpinning() && screen.getMenu().containerId == instance.lastProcessedContainerId) return true;
		// Real bug found (per user report — "The chestrolling needs to insta cancel the chest gui. For a
		// split second i can see the drops"): pollPendingChestScreen (see its own doc comment) has to wait for
		// the real chest's items to actually sync from the server before a winner can even be picked — that
		// wait is genuinely unavoidable, but until now the real vanilla screen (full spoiler contents
		// included) rendered completely normally the whole time, since nothing replaced its render until
		// startRoll() actually fired at the END of that wait. Blanking the exact same screen reference the
		// poll is already tracking closes that gap — the real contents are never drawn on screen at all, from
		// the very first frame the chest GUI opens.
		return instance.pendingChestScreen == screen;
	}

	public static void renderReplacement(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		if (instance == null) return;
		if (instance.isSpinning() && screen.getMenu().containerId == instance.lastProcessedContainerId) {
			instance.renderRoll(graphics, screen);
			return;
		}
		// Pending window (see shouldReplaceRender's own doc comment) — same blank dim background the real
		// roll animation itself opens with (renderRoll's own first line), just without the scroll strip yet
		// since no winner is known.
		Minecraft mc = Minecraft.getInstance();
		graphics.fill(0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), BACKGROUND_DIM);
	}

	private void renderRoll(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();
		graphics.fill(0, 0, screenWidth, screenHeight, BACKGROUND_DIM);

		long elapsed = System.currentTimeMillis() - rollStartMillis;
		float elapsedSeconds = Math.max(0f, elapsed / 1000f);

		// Plays the landing sound the instant the strip actually stops (real deceleration end point). Used to
		// be deferred all the way to onTick(), after LAND_HOLD_MILLIS (1000ms) + SETTLE_MILLIS (1500ms) had
		// ALSO fully elapsed — a real, very noticeable 2.5-SECOND gap between the visual landing and the sound,
		// per user report ("The sound for the chest rolling is super late") — an overcorrection from an
		// earlier round's "should play after the mod has exited the chest rolling animation" request. Playing
		// it right here, at the same instant the outcome is decided, matches what "landing on it" actually
		// looks like to the player.
		if (!landOutcomeDecided && elapsed >= Math.round(animationDurationSeconds * 1000) && winnerStack != null) {
			landOutcomeDecided = true;
			if (isBigWinner()) playLandSound();
		}

		// Per user request ("way bigger boxes (only fitting like 7 on the screen at the same time)"): card
		// size is derived live from the real screen width (see VISIBLE_CARDS_TARGET's own doc comment)
		// instead of a fixed pixel constant — cardsScrolled's own kinematics model operates purely in
		// "cards" units and only gets converted to pixels here, so this doesn't need to touch that model.
		int cardFullWidth = Math.max(60, screenWidth / VISIBLE_CARDS_TARGET);
		float scale = (cardFullWidth - CARD_GAP) / (float) CARD_WIDTH;

		float endOffset = cardsScrolled(Math.min(elapsedSeconds, animationDurationSeconds)) * cardFullWidth;

		int soundIndex = (int) (endOffset / cardFullWidth);
		if (soundIndex > lastSoundIndex) {
			lastSoundIndex = soundIndex;
			// Per user request ("Add sounds when it scrolls to an item. Play the Minecraft:block.chain.fall
			// sound"), replacing the old plain ITEM_PICKUP clunk.
			if (mc.player != null) mc.player.playSound(SoundEvents.CHAIN_FALL, 1f, 1f);
		}

		int centerX = screenWidth / 2;
		int centerY = screenHeight / 2;
		int baseX = centerX - Math.round(endOffset) - Math.round(8 * scale) - randomOffsetPx;

		// Per user request ("displays a circle in the center, fitting about 2 items max, and those 2 items
		// are a little magnified and also have full opacity. The items next to them... slowly lose opacity,
		// until it gets almost to the edge where it has no opacity at all"): cards inside the combined
		// CENTER_ZONE_CARDS-wide zone straddling the pointer render fully opaque; everything else fades
		// toward the dim background. Per later follow-up ("Tighten Chest Rolling vignette"): the fade used
		// to only reach full black exactly at the true screen edge (a card sitting at 95% of the way there
		// was still faintly visible) — VIGNETTE_TIGHTNESS pulls the "fully black" point in to 65% of the
		// half-width instead, so cards actually finish fading well before the edge rather than lingering
		// dimly visible all the way out. A real progressive BLUR (not just darkening) near the edges was
		// also asked for, but this engine has no per-widget blur primitive — the only blur this mod has
		// anywhere is the vanilla whole-game-world background blur behind the panel (GuiAnimationsFeature's
		// own blur slider), which can't be aimed at individual scrolling item cards; a real one would need a
		// render-to-texture + shader pass this session can't build and verify blind. Tightening the existing
		// darken-based fade is the honest, achievable half of this request.
		float centerZoneHalfWidthPx = cardFullWidth * CENTER_ZONE_CARDS / 2f;
		float halfWidth = Math.max(1f, screenWidth / 2f);
		float maxDist = centerZoneHalfWidthPx + (halfWidth - centerZoneHalfWidthPx) * VIGNETTE_TIGHTNESS;

		graphics.pose().pushMatrix();
		try {
			// Per user request ("The chest rolling magnifying is a LOT... Go back to making the one the stick
			// in the middle was currently hovering over a bit bigger than others next to it (make it overlap
			// the ones next to it, it should end when the stick reaches the middle of the next one, then that
			// one should become the bigger one)"): no separate lens overlay pass anymore — the card nearest
			// the fixed center pointer is simply drawn at a slightly larger scale than the rest, growing
			// continuously from 1x (at a half-card-width away, exactly where the next card takes over as
			// nearest) up to its peak boost at 0 distance (dead-centered on the pointer). Two passes so the
			// currently-boosted card draws LAST — on top of its now-smaller neighbors, which is what actually
			// reads as "overlapping" rather than being drawn over by them.
			int boostedIndex = -1;
			int boostedCardCenterX = centerX;
			float boostedCardScale = scale;
			float boostedFadeT = 0f;
			for (int i = 0; i < strip.size(); i++) {
				int cardX = baseX + i * cardFullWidth;
				int cardCenterX = cardX + cardFullWidth / 2;
				if (cardCenterX < -cardFullWidth || cardCenterX > screenWidth + cardFullWidth) continue;
				float distFromCenter = Math.abs(cardCenterX - centerX);
				float fadeT = distFromCenter <= centerZoneHalfWidthPx ? 0f
					: Math.min(1f, (distFromCenter - centerZoneHalfWidthPx) / Math.max(1f, maxDist - centerZoneHalfWidthPx));
				// Per user report ("The chest rolling is really bugged, it shows nothing now... jumps back to
				// the starting position then nothing actually happens"): the grow/hold/shrink trapezoid tried
				// here broke the roll outright — reverted to the known-good plain linear tent (0 at the zone's
				// edge, ramping straight up to 1 at dead center, then back down) rather than risk shipping a
				// second unverified version of this same formula blind.
				float magnifyT = Math.max(0f, 1f - distFromCenter / (cardFullWidth / 2f));
				float cardScale = scale * (1f + MAGNIFY_BOOST * magnifyT);
				if (magnifyT > 0f) {
					// Defer this one card until every unboosted neighbor has already been drawn.
					boostedIndex = i;
					boostedCardCenterX = cardCenterX;
					boostedCardScale = cardScale;
					boostedFadeT = fadeT;
					continue;
				}
				drawCard(graphics, strip.get(i), cardCenterX, centerY, cardScale, fadeT);
			}
			if (boostedIndex >= 0) {
				drawCard(graphics, strip.get(boostedIndex), boostedCardCenterX, centerY, boostedCardScale, boostedFadeT);
			}
		} finally {
			graphics.pose().popMatrix();
		}

		int magnifiedHeight = Math.round(CARD_HEIGHT * scale);
		int pointerHeight = Math.round(magnifiedHeight * 0.6f);
		RenderUtil.fillRounded(graphics, centerX - 1, centerY - magnifiedHeight / 2 - pointerHeight / 4,
			centerX + 1, centerY + magnifiedHeight / 2 + pointerHeight / 4, 1, 0xFFFF5555);
		// Per user request ("I also want the item name popup to not have an animation, and instead of
		// showing when it lands on an item, show it in the chest menu where the real item and the chest
		// itself is shown"): the old mid-air scaling reveal text lived here — removed entirely. A later
		// round added a plain non-animated name label over the REAL chest screen instead (once isSettling()
		// took over from this CS2-strip replacement); a further user request ("remove post-drop text")
		// removed that too, so the winner is now revealed purely by the real item sitting in its real slot.
	}

	private void drawCard(GuiGraphicsExtractor graphics, ItemStack item, int centerX, int centerY, float scale, float fadeT) {
		int color = accentColorOf(item);
		int w = Math.round(CARD_WIDTH * scale);
		int h = Math.round(CARD_HEIGHT * scale);
		graphics.pose().pushMatrix();
		try {
			graphics.pose().translate(centerX - w / 2f, centerY - h / 2f);
			graphics.pose().scale(scale);
			RenderUtil.fillRounded(graphics, 0, 0, CARD_WIDTH, CARD_HEIGHT - 1, 0, 0x80303030, false, false, false, false);
			graphics.fill(0, CARD_HEIGHT - 1, CARD_WIDTH, CARD_HEIGHT, color | 0xFF000000);
			if (item == mysteryStackForRoll) {
				int size = 20;
				graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, MYSTERY_TEXTURE,
					CARD_WIDTH / 2 - size / 2, CARD_HEIGHT / 2 - size / 2, 0f, 0f, size, size, size, size, size, size, 0xFFFFFFFF);
			} else {
				graphics.item(item, CARD_WIDTH / 2 - 8, CARD_HEIGHT / 2 - 8);
			}
			// Per user request ("it should slowly become less opacity the further it goes out... until it
			// gets almost to the edge where it has no opacity at all"): GuiGraphicsExtractor's item(...) has
			// no alpha-aware overload to blend the icon itself against, so this darkens toward the dim
			// background instead of true alpha compositing — same visual language (less prominent near the
			// edges), just achieved by overlaying a translucent-to-opaque black rect instead.
			if (fadeT > 0f) {
				// Per "Tighten Chest Rolling vignette" follow-up: fully faded cards now go completely black
				// (255) instead of capping at 235/255 — "no opacity at all" per the original spec, not just
				// mostly-none.
				int overlayAlpha = Math.round(fadeT * 255f) << 24;
				graphics.fill(0, 0, CARD_WIDTH, CARD_HEIGHT, overlayAlpha);
			}
		} finally {
			graphics.pose().popMatrix();
		}
	}

	/** Winner cards get the real item's rarity color (read off its last lore line's own resolved text
	 *  color, same trick ItemPickupLogFeature already uses); filler decoys have no such lore, so they get
	 *  a plain neutral gray. */
	// Per user request (verbatim spec — "The rare sound on chest opening should literally ONLY play on these
	// rare drops on each floor"): replaces the old blanket "any RARE-or-better lore rarity" check (which fired
	// for lots of real rare-but-not-headline drops — Wither armor pieces, Wither Catalyst, enchanted books —
	// never actually in the user's exact per-floor list). See ChestDropTables.RARE_SOUND_DROPS for the full
	// floor-by-floor table this now checks against.
	//
	/** True only when {@link #winnerStack}'s own plain (color-stripped) name is one of the exact rare drops
	 *  {@link ChestDropTables#isRareSoundDrop} lists for {@link #currentRollFloorLabel} — the floor this
	 *  specific roll was started for (captured in {@link #startRoll}, not re-read from live DungeonState,
	 *  since that can't be trusted during a /chestopening test roll). */
	private boolean isBigWinner() {
		if (winnerStack == null || currentRollFloorLabel == null) return false;
		String plainName = WINNER_COLOR_CODE.matcher(winnerStack.getHoverName().getString()).replaceAll("");
		return ChestDropTables.isRareSoundDrop(currentRollFloorLabel, plainName);
	}

	private static int accentColorOf(ItemStack stack) {
		var lore = stack.get(DataComponents.LORE);
		if (lore == null) return 0xFF888888;
		var lines = lore.lines();
		for (int i = lines.size() - 1; i >= 0; i--) {
			Component line = lines.get(i);
			if (line.getString().isBlank()) continue;
			Integer rgb = line.visit((style, text) -> {
				if (text.isEmpty() || style.getColor() == null) return java.util.Optional.<Integer>empty();
				return java.util.Optional.of(style.getColor().getValue());
			}, net.minecraft.network.chat.Style.EMPTY).orElse(null);
			return rgb != null ? (0xFF000000 | rgb) : 0xFF888888;
		}
		return 0xFF888888;
	}

	public boolean isOnlyBedrock() { return onlyBedrock; }
	public void setOnlyBedrock(boolean value) { onlyBedrock = value; }
	public boolean isObsidianOnLowerFloors() { return obsidianOnLowerFloors; }
	public void setObsidianOnLowerFloors(boolean value) { obsidianOnLowerFloors = value; }
	public boolean isOnlyCroesus() { return onlyCroesus; }
	public void setOnlyCroesus(boolean value) { onlyCroesus = value; }
	public float getAnimationDurationSeconds() { return animationDurationSeconds; }
	public void setAnimationDurationSeconds(float value) { animationDurationSeconds = Math.max(0.5f, Math.min(10f, value)); }
	public boolean isObfuscateRareItems() { return obfuscateRareItems; }
	public void setObfuscateRareItems(boolean value) { obfuscateRareItems = value; }
	public boolean isHideLore() { return hideLore; }
	public void setHideLore(boolean value) { hideLore = value; }
	public boolean isHideLoreAllChests() { return hideLoreAllChests; }
	public void setHideLoreAllChests(boolean value) { hideLoreAllChests = value; }
	public boolean isLandSoundEnabled() { return landSoundEnabled; }
	public void setLandSoundEnabled(boolean value) { landSoundEnabled = value; }
	public com.cokelord.skyblocksimplified.sound.CustomSoundOption getLandSound() { return landSound; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("onlyBedrock", onlyBedrock);
		obj.addProperty("obsidianOnLowerFloors", obsidianOnLowerFloors);
		obj.addProperty("onlyCroesus", onlyCroesus);
		obj.addProperty("animationDurationSeconds", animationDurationSeconds);
		obj.addProperty("obfuscateRareItems", obfuscateRareItems);
		obj.addProperty("hideLore", hideLore);
		obj.addProperty("hideLoreAllChests", hideLoreAllChests);
		obj.addProperty("landSoundEnabled", landSoundEnabled);
		obj.add("landSound", landSound.toJson());
		// See trackedRuns'/TrackedRun's own doc comment — persisted so the tracker (including which runs
		// have already been rolled) survives a relaunch instead of forgetting everything the moment the game
		// closes.
		JsonArray runsArray = new JsonArray();
		for (TrackedRun run : trackedRuns) {
			JsonObject runObj = new JsonObject();
			runObj.addProperty("expiry", run.expiryMillis);
			runObj.addProperty("rolled", run.rolled);
			runsArray.add(runObj);
		}
		obj.add("trackedRuns", runsArray);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("onlyBedrock")) onlyBedrock = obj.get("onlyBedrock").getAsBoolean();
		if (obj.has("obsidianOnLowerFloors")) obsidianOnLowerFloors = obj.get("obsidianOnLowerFloors").getAsBoolean();
		if (obj.has("onlyCroesus")) onlyCroesus = obj.get("onlyCroesus").getAsBoolean();
		if (obj.has("animationDurationSeconds")) animationDurationSeconds = obj.get("animationDurationSeconds").getAsFloat();
		if (obj.has("obfuscateRareItems")) obfuscateRareItems = obj.get("obfuscateRareItems").getAsBoolean();
		if (obj.has("hideLore")) hideLore = obj.get("hideLore").getAsBoolean();
		if (obj.has("hideLoreAllChests")) hideLoreAllChests = obj.get("hideLoreAllChests").getAsBoolean();
		if (obj.has("landSoundEnabled")) landSoundEnabled = obj.get("landSoundEnabled").getAsBoolean();
		if (obj.has("landSound")) landSound.fromJson(obj.get("landSound"));
		if (obj.has("trackedRuns") && obj.get("trackedRuns").isJsonArray()) {
			trackedRuns.clear();
			for (JsonElement el : obj.getAsJsonArray("trackedRuns")) {
				if (el.isJsonObject()) {
					JsonObject runObj = el.getAsJsonObject();
					long expiry = runObj.has("expiry") ? runObj.get("expiry").getAsLong() : 0L;
					boolean rolled = runObj.has("rolled") && runObj.get("rolled").getAsBoolean();
					trackedRuns.add(new TrackedRun(expiry, rolled));
				} else if (el.isJsonPrimitive()) {
					trackedRuns.add(new TrackedRun(el.getAsLong(), false));
				}
			}
			pruneExpiredRuns();
		} else if (obj.has("trackedRunExpiryMillis") && obj.get("trackedRunExpiryMillis").isJsonArray()) {
			// Backward compat with the pre-run-list-rewrite key/shape — a plain expiry-millis list with no
			// rolled state, from before this round.
			trackedRuns.clear();
			for (JsonElement el : obj.getAsJsonArray("trackedRunExpiryMillis")) trackedRuns.add(new TrackedRun(el.getAsLong(), false));
			pruneExpiredRuns();
		}
	}

	@Override
	public String getDescription() {
		return "Plays a CS:GO-style case-opening animation for a real Hypixel dungeon reward chest you just opened.";
	}
}
