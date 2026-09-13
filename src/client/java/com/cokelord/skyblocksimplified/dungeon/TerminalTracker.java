package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.StainedGlassPaneBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared "is a dungeon terminal currently open, and did I just solve it" tracker — the one piece of
 *  Odin's {@code TerminalUtils.kt} that multiple independently-ported features (Terminal Solver, Terminal
 *  Sounds, Melody Message) all need, factored out so each doesn't re-implement its own screen-title
 *  matching. Not a full port of TerminalUtils (no simulator/custom-GUI packet interception — see the port
 *  plan's notes on {@code TerminalSolverFeature} for what was intentionally left out).
 *
 *  <p>Also owns the actual click-solving algorithm for the 5 click-based terminal types (ported from
 *  Odin's {@code terminalhandler/*Handler.kt}, same as {@code TerminalSolverFeature} used to compute
 *  independently) — moved here so "is this slot currently correct" is real shared state, available to any
 *  consumer (Terminal Sounds included) regardless of whether Terminal Solver's own highlight/block toggle
 *  happens to be on, tracked continuously the same way open/solve detection already is. */
public final class TerminalTracker {
	public enum TerminalType { PANES, RUBIX, NUMBERS, STARTS_WITH, SELECT, MELODY }

	private record TypePattern(TerminalType type, Pattern pattern, int windowSize) {}

	private static final List<TypePattern> PATTERNS = List.of(
		new TypePattern(TerminalType.PANES, Pattern.compile("^Correct all the panes!$"), 45),
		new TypePattern(TerminalType.RUBIX, Pattern.compile("^Change all to same color!$"), 45),
		new TypePattern(TerminalType.NUMBERS, Pattern.compile("^Click in order!$"), 36),
		new TypePattern(TerminalType.STARTS_WITH, Pattern.compile("^What starts with: '(\\w)'\\?$"), 45),
		new TypePattern(TerminalType.SELECT, Pattern.compile("^Select all the ([\\w ]+) items!$"), 54),
		// 54 = the real double-chest grid this terminal is played in (6 rows x 9), not "no window" —
		// needed now that Melody has real per-slot tracking below, see MelodyState.
		new TypePattern(TerminalType.MELODY, Pattern.compile("^Click the button on time!$"), 54)
	);
	// Package-private (not private) so TermSimController can drive its fake Rubix panes through the exact
	// same cycle order the real solver assumes, instead of risking a second, independently-drifting copy.
	static final DyeColor[] RUBIX_ORDER = {DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED};
	// "<name> activated a terminal! (N/M)" — the real solve-confirmation broadcast Hypixel sends to the
	// whole party, filtered down to just the local player's own solves.
	private static final Pattern SOLVE_PATTERN = Pattern.compile("^(.{1,16}) activated a terminal! \\((\\d)/(\\d)\\)$");

	/** One pane's remaining Rubix work: how many clicks, and which direction is cheaper. Real bug fixed
	 *  here — the old solver always assumed the forward (left-click) direction even in cases its own
	 *  "real size" cost comparison already knew backward (right-click) was cheaper (distance 3 or 4 out of
	 *  5 colors), so the highlighted click count could be wrong up to twice over. */
	public record RubixClick(int slotIndex, int count, boolean leftClick) {}

	// Melody ("Click the button on time!") is a 6x9 double chest: row 1 (slots 0-8) always holds one fixed
	// purple/magenta pane marking the target column. A second purple/magenta pane lives on rows 2-5 (slots
	// 9-44) and its column shifts continuously — that's the moving marker. Which of rows 2/3/4/5 it's
	// currently on is the attempt number (0/4 .. 3/4): a correct press of the button slot at the far right
	// of that row (per user description) advances it to the next row down. Re-derived fresh every tick
	// (not gated behind changed(), unlike the other 5 types) since the moving marker's column is exactly
	// what needs to be tracked live, at full 20-ticks/sec (~50ms) resolution.
	public record MelodyState(int targetSlot, int targetColumn, int movingSlot, int movingRow, int movingColumn,
							   int buttonSlot, int attempt, boolean aligned) {}

	private static TerminalType currentType;
	private static int currentWindowSize;
	private static String startsWithLetter;
	private static Set<String> selectPrefixes = Set.of();
	private static List<Integer> currentSolution = List.of();
	private static List<RubixClick> rubixClicks = List.of();
	private static MelodyState melodyState;
	private static final Set<Integer> startsWithClicked = new HashSet<>();
	private static DyeColor rubixLastSolutionColor;
	private static ItemStack[] lastSnapshot = new ItemStack[0];

	// Client-side "I already correctly clicked this" memory, independent of Hypixel's own server-truth item
	// state. Per user report ("Terminal solver sometimes doesn't detect what I've already pressed"): the
	// solve state above is only ever recomputed from the container's real item snapshot, which doesn't
	// change until the server round-trip for that click comes back — for however long that takes, a slot
	// you already correctly clicked keeps being reported as still needing one, which reads exactly like
	// "sometimes doesn't detect." Slots marked here are excluded from the exposed solution for a short grace
	// window regardless of whether the server has caught up yet. Deliberately NOT applied to Rubix: a single
	// Rubix slot can legitimately need several clicks in a row (see RubixClick's count), so hiding it right
	// after the first click would block the very re-clicks the player is supposed to make.
	private static final Map<Integer, Long> recentlyClicked = new HashMap<>();
	private static final long RECENT_CLICK_GRACE_MS = 600;

	// Per Boss Guide's TERMINAL_DONE step type ("detects when the terminal is finished through chat or
	// through the solver having no more steps"): a room-wide "how many of the CURRENT section's terminals
	// are done" tally, independent of solveListeners below (which are self-filtered — only the LOCAL
	// player's own solves — for their click-sound use case). Every party member's own real "<name> activated
	// a terminal! (N/M)" broadcast (SOLVE_PATTERN, below) updates this. Reset the moment
	// {@link DungeonState#getTerminalSection()} changes (a fresh section starting, or the terminal phase
	// ending) so a stale total from a previous section can never read as the current one's.
	private static int lastTerminalProgressCurrent = 0;
	private static int lastTerminalProgressTotal = -1;
	private static DungeonState.TerminalSection lastSectionForProgress = DungeonState.TerminalSection.NONE;

	// Real bug found (per user report — "the rubiks terminal removes the square client side instantly
	// without waiting for server confirmation that the square is clicked, then it polls remaining clicks
	// before the server has registered the click, respawning it until the server registers the click"):
	// Rubix used to have its own optimistic decrement-based tracking here (a per-slot deque of not-yet-
	// server-confirmed click timestamps, subtracted from the real remaining-click count in getRubixClicks()
	// and expired after a grace window by a tick()-driven prune). That's exactly the "instant local hide,
	// then revert once the grace timer runs out before the server catches up" flicker the user described —
	// the same class of bug getCurrentSolution() already had fixed for the other 4 click-based terminal
	// types by dropping all optimistic hiding in favor of always reflecting real, server-confirmed state.
	// Removed entirely rather than re-tuning the grace window again (this was already re-tuned twice per
	// the two bug-fix rounds a previous version of this comment described) — getRubixClicks() below now
	// mirrors getCurrentSolution() exactly: always {@link #rubixClicks} as last rebuilt from a real
	// item-state re-scan, no client-side speculation at all. A slot needing several real clicks in a row
	// (the reason Rubix couldn't just reuse the plain recentlyClicked hide-on-first-click mechanism above)
	// is unaffected: every one of those clicks now goes through exactly the way multiple simultaneously-
	// pending clicks on different NUMBERS/PANES slots already do — sent for real, judged against the last
	// known real state, and reconciled once the server round-trip actually lands.

	/** Widened under estimated server lag (see {@link com.cokelord.skyblocksimplified.util.TpsMonitor}) —
	 *  per user request ("all custom menus need to be able to detect tps drops... to make sure detecting
	 *  goes smoothly"). The real item-state update this grace window stands in for only arrives once the
	 *  server has actually processed the click, so a slower server means a longer real wait — a fixed grace
	 *  window sized for 20 TPS would expire early during a drop and let the "doesn't detect what I already
	 *  pressed" symptom back in for exactly the moments it's most likely to happen.
	 *
	 *  <p>Real bug found (per user report — "if the user is a little too fast or the server lags, the mod
	 *  still thinks the user clicks the slots... it shows the animation but doesn't actually click the
	 *  slot"): TpsMonitor only measures SERVER-side tick lag, a different axis from the player's own
	 *  connection latency — a fine-TPS server with a slow/laggy connection to it still needs a full round
	 *  trip (click packet there, item-state update back) before the real confirmation can arrive, and that
	 *  can easily exceed a fixed 600/1200ms on a bad connection. Also floors the grace at roughly double the
	 *  player's own current ping (confirmed real API — same {@code PlayerInfo.getLatency()} ChatCommandsFeature
	 *  already reads for its own ping display) so the window scales with the actual round-trip time
	 *  instead of just the two fixed TPS-based steps. */
	private static long recentClickGraceMs() {
		long base = com.cokelord.skyblocksimplified.util.TpsMonitor.isLagging() ? RECENT_CLICK_GRACE_MS * 2 : RECENT_CLICK_GRACE_MS;
		int ping = currentPing();
		return ping > 0 ? Math.max(base, ping * 2L + 200) : base;
	}

	private static int currentPing() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getConnection() == null) return -1;
		net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return info != null ? info.getLatency() : -1;
	}

	/** Called by TerminalSolverFeature the instant a click on this slot is judged correct and let through —
	 *  see the recentlyClicked field doc comment for why this exists. Per user pushback (real vanilla
	 *  clicking never blocks a next click while a previous one is still awaiting confirmation — see
	 *  TerminalSolverFeature.judgeClick's own doc comment), this no longer tracks a separate "block the
	 *  next click" timer — only the visual-hide grace window remains, which already correctly supports
	 *  several simultaneously-pending slots via isRecentlyClicked. */
	public static void markClicked(int slotId) {
		recentlyClicked.put(slotId, System.currentTimeMillis());
	}

	private static boolean isRecentlyClicked(int slotId) {
		Long at = recentlyClicked.get(slotId);
		return at != null && System.currentTimeMillis() - at < recentClickGraceMs();
	}

	private static final List<Consumer<TerminalType>> openListeners = new ArrayList<>();
	private static final List<Runnable> solveListeners = new ArrayList<>();
	private static boolean registered = false;

	private TerminalTracker() {}

	public static TerminalType getCurrentType() {
		return currentType;
	}

	/** Slot indices in the currently-open terminal that are correct to click right now. Empty when no
	 *  click-based terminal is open (or the open one is Melody, which has no "correct slot" concept).
	 *
	 *  <p>Per user report ("sometimes it doesn't click the click I clicked but does the animation and
	 *  removes the square, then regenerates the square after a while"): this used to also skip every
	 *  recentlyClicked entry here, optimistically hiding a square the instant a click was judged correct,
	 *  before the server had actually confirmed it — if that click never truly landed (packet loss, or any
	 *  other real-vs-judged mismatch), the square reappeared once the grace window ran out, which reads
	 *  exactly like "regenerates after a while." Now always reflects currentSolution as last rebuilt from a
	 *  real, server-confirmed item-state re-scan — a square only disappears once solving it is actually
	 *  confirmed, at the same every-client-tick (~50ms) cadence {@link #tick} already re-scans at, the
	 *  fastest polling this client-side approach can do (the remaining latency is the real click's own
	 *  server round-trip, which no amount of local polling frequency can shorten further). recentlyClicked
	 *  itself still exists for isCorrectSlot's click-VALIDATION timing below (accepting a rapid follow-up
	 *  click on the next square while the current one's confirmation is still in flight) — just no longer
	 *  used to hide anything visually ahead of confirmation. */
	public static List<Integer> getCurrentSolution() {
		return currentSolution;
	}

	public static boolean isCorrectSlot(int slotId) {
		if (isRecentlyClicked(slotId)) return false;
		// Melody's solve() always returns an empty list (it isn't tracked as a per-slot "correct set" the way
		// the other 5 types are, see MelodyState instead) — checking currentSolution here always returned
		// false for it, silently keeping the click animation/sound features from ever firing on a correct
		// Melody button press.
		if (currentType == TerminalType.MELODY) {
			return melodyState != null && melodyState.aligned() && slotId == melodyState.buttonSlot();
		}
		// Real bug found (per user report — "clicking the wrong squares in the numbers terminal clicks
		// them"): currentSolution for NUMBERS is an ORDER, not an unordered set — every remaining unclicked
		// number sits in it until its turn comes, but a plain .contains() treated ALL of them as "correct"
		// regardless of order, so clicking any number out of sequence still passed shouldBlockClick's
		// correctness check and went through (animation, sound, and — for a real terminal — the actual
		// click packet, none of which should fire for a wrong-order click). Only the list's own head (the
		// one actually next to click) is correct for NUMBERS; the rest still need clicking eventually but
		// aren't valid RIGHT NOW. PANES/STARTS_WITH/SELECT are genuinely unordered (any of them is correct
		// in any order), so they keep the plain membership check.
		//
		// Second real bug found immediately after shipping the first fix (per user report — "clicking the
		// next square doesn't allow me to click for about 0.5s, happens half the time"): currentSolution
		// itself doesn't get rebuilt until the NEXT successful item-state scan (a real server round-trip
		// for a real terminal), so right after clicking the true head correctly, currentSolution.get(0) is
		// STILL that same just-clicked slot for however long that round-trip takes — a naive re-check of
		// .get(0) here rejected the very next (genuinely correct) click as "wrong" for that whole window.
		// getCurrentSolution() (used for rendering, so the player visually sees the NEXT slot highlighted
		// green immediately) already solves this by skipping recently-clicked entries — mirrored here so
		// the click check agrees with what's drawn instead of lagging a full round-trip behind it.
		if (currentType == TerminalType.NUMBERS) {
			for (int candidate : currentSolution) {
				if (isRecentlyClicked(candidate)) continue;
				return candidate == slotId;
			}
			return false;
		}
		return currentSolution.contains(slotId);
	}

	/** Number of slots in the currently-tracked terminal window (0 when none is open) — the full grid
	 *  size, not just the correct-slot count, so renderers can black out every tracked slot rather than
	 *  only the ones highlighted as correct. */
	public static int getCurrentWindowSize() {
		return currentWindowSize;
	}

	/** Rubix-specific per-slot data (empty unless currentType == RUBIX) — the real left/right-click
	 *  direction and click count for each pane still needing work, see {@link RubixClick}. Mirrors
	 *  {@link #getCurrentSolution()}'s already-fixed pattern exactly: always {@link #rubixClicks} as last
	 *  rebuilt from a real, server-confirmed item-state re-scan, with no client-side optimistic adjustment
	 *  — see the field's own doc comment for the flicker/respawn bug removing that adjustment fixed. */
	public static List<RubixClick> getRubixClicks() {
		return rubixClicks;
	}

	/** The REAL (server-confirmed, non-optimistic) set of Rubix slot indices still needing work — this is
	 *  {@link #rubixClicks} itself, not {@link #getRubixClicks()}'s pending-adjusted view. Per user request
	 *  ("dont play the animation unless a button is fully solved... when its fully solved and disappears
	 *  (like all other terminal buttons do) then it should play the animation"), TerminalSolverFeature polls
	 *  this every tick and fires the click animation only for a slot that disappears from THIS real set —
	 *  the exact moment the server itself confirms the pane needs no more clicks, not merely the moment the
	 *  client's own click judgement or optimistic pending-click tracking predicts it does. */
	public static Set<Integer> getRubixActiveSlots() {
		if (rubixClicks.isEmpty()) return Set.of();
		Set<Integer> slots = new java.util.HashSet<>();
		for (RubixClick click : rubixClicks) slots.add(click.slotIndex());
		return slots;
	}

	/** Melody-specific live state (null unless currentType == MELODY and both markers were found this
	 *  tick), see {@link MelodyState}. */
	public static MelodyState getMelodyState() {
		return melodyState;
	}

	public static void addOpenListener(Consumer<TerminalType> listener) {
		ensureRegistered();
		openListeners.add(listener);
	}

	public static void addSolveListener(Runnable listener) {
		ensureRegistered();
		solveListeners.add(listener);
	}

	private static synchronized void ensureRegistered() {
		if (registered) return;
		registered = true;

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tick(client);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("TerminalTracker tick failed, skipping this tick", e);
			}
		});

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			try {
				onChatMessage(message.getString());
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("TerminalTracker chat tracking failed on a message, ignoring it", e);
			}
			return true;
		});
	}

	private static void tick(Minecraft client) {
		// See lastTerminalProgressCurrent/Total's own field doc comment: a section boundary change means any
		// previously-tallied (current/total) is for a DIFFERENT section and must not linger.
		DungeonState.TerminalSection section = DungeonState.getTerminalSection();
		if (section != lastSectionForProgress) {
			lastTerminalProgressCurrent = 0;
			lastTerminalProgressTotal = -1;
			lastSectionForProgress = section;
		}
		if (!recentlyClicked.isEmpty()) {
			long now = System.currentTimeMillis();
			long grace = recentClickGraceMs();
			recentlyClicked.entrySet().removeIf(e -> now - e.getValue() >= grace);
		}
		if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
			reset();
			return;
		}
		String title = screen.getTitle().getString();
		TypePattern matched = null;
		Matcher matcher = null;
		for (TypePattern candidate : PATTERNS) {
			Matcher m = candidate.pattern().matcher(title);
			if (m.matches()) { matched = candidate; matcher = m; break; }
		}
		if (matched == null) {
			if (currentType != null) reset();
			return;
		}
		applyDetectedType(matched, matcher);
		if (currentType == TerminalType.MELODY) {
			updateMelody(screen);
			return;
		}

		List<ItemStack> items = screen.getMenu().getItems();
		int size = Math.min(items.size(), matched.windowSize());
		// Per user report ("terminals still don't recover when the mod's tracked state desyncs from the real
		// server state"): changed() alone only recomputes the solution when an item-state diff is actually
		// observed, so a real desync between currentSolution/rubixClicks and the true server state (e.g. from
		// a missed comparison, a stale rubixLastSolutionColor, or any other bug not yet found/fixed) would
		// stay wrong forever — nothing ever forces a from-scratch recompute against reality otherwise. A cheap
		// unconditional full re-solve every 20 ticks (~1s, same cadence Splits/DungeonState already poll at
		// elsewhere) is a from-scratch resync safety net independent of whether changed() thinks anything moved,
		// so any desync self-heals within a second no matter its root cause.
		boolean itemsChanged = changed(items, size);
		desyncResyncTicks++;
		boolean forceResync = desyncResyncTicks >= 20;
		if (!itemsChanged && !forceResync) return;
		if (forceResync) desyncResyncTicks = 0;
		List<ItemStack> window = items.subList(0, size);
		currentSolution = solve(window);
		rubixClicks = currentType == TerminalType.RUBIX ? computeRubixClicks(window) : List.of();
	}

	private static int desyncResyncTicks;

	private static void applyDetectedType(TypePattern matched, Matcher matcher) {
		if (matched.type() == currentType) return;
		reset();
		currentType = matched.type();
		currentWindowSize = matched.windowSize();
		if (currentType == TerminalType.STARTS_WITH) startsWithLetter = matcher.group(1);
		if (currentType == TerminalType.SELECT) selectPrefixes = selectPrefixesFor(matcher.group(1));
		fireOpen(currentType);
	}

	/** Synchronous type detection straight from the screen's title, callable from the render path instead
	 *  of only waiting on the next {@code tick()} (up to 50ms away, several render frames on a high refresh
	 *  rate display). Per user report ("I remember for a split frame it shows [the real GUI]"): the overlay
	 *  used to only know a terminal was open once the next client tick's title scan caught up, so the very
	 *  first frame(s) after the screen opened rendered nothing over the real vanilla GUI at all. Called from
	 *  TerminalSolverFeature's render hook every frame so type detection (and therefore the "hide the
	 *  original GUI" backdrop) is available on the very first frame, not the first tick. */
	public static TerminalType ensureDetected(AbstractContainerScreen<?> screen) {
		String title = screen.getTitle().getString();
		for (TypePattern candidate : PATTERNS) {
			Matcher m = candidate.pattern().matcher(title);
			if (m.matches()) {
				applyDetectedType(candidate, m);
				return currentType;
			}
		}
		return currentType;
	}

	private static void reset() {
		currentType = null;
		currentWindowSize = 0;
		startsWithLetter = null;
		selectPrefixes = Set.of();
		currentSolution = List.of();
		rubixClicks = List.of();
		melodyState = null;
		startsWithClicked.clear();
		rubixLastSolutionColor = null;
		lastSnapshot = new ItemStack[0];
		recentlyClicked.clear();
		desyncResyncTicks = 0;
	}

	private static boolean changed(List<ItemStack> items, int size) {
		if (lastSnapshot.length != size) {
			lastSnapshot = new ItemStack[size];
			for (int i = 0; i < size; i++) lastSnapshot[i] = items.get(i).copy();
			return true;
		}
		boolean any = false;
		for (int i = 0; i < size; i++) {
			if (!ItemStack.matches(lastSnapshot[i], items.get(i))) { lastSnapshot[i] = items.get(i).copy(); any = true; }
		}
		return any;
	}

	private static final int MELODY_ROW_WIDTH = 9;
	private static final int MELODY_ROWS = 6;

	/** Re-scans the full 54-slot double chest every tick (cheap — 54 item reads) for the target and moving
	 *  markers — verified against Odin's real MelodyHandler.kt (a working, extensively-tested reference):
	 *  target is the FIRST magenta stained glass pane anywhere in the window (not row-restricted — Odin's
	 *  own {@code indexOfFirst} has no row filter, which also explains why the target isn't always on the
	 *  same row), moving is the LAST lime (bright green) stained glass pane. The button slot is Odin's own
	 *  hardcoded set {16,25,34,43} (row*9+7 for rows 1-4) rather than trusting a dynamically-detected
	 *  terracotta block, matching Odin's own click-gating choice over its dynamic solve() detection. */
	private static void updateMelody(AbstractContainerScreen<?> screen) {
		List<ItemStack> items = screen.getMenu().getItems();
		int size = Math.min(items.size(), MELODY_ROW_WIDTH * MELODY_ROWS);
		int targetSlot = -1;
		int movingSlot = -1;
		for (int i = 0; i < size; i++) {
			DyeColor color = paneColorOf(items.get(i));
			if (color == DyeColor.MAGENTA && targetSlot < 0) targetSlot = i;
			if (color == DyeColor.LIME) movingSlot = i;
		}
		if (targetSlot < 0 || movingSlot < 0) {
			melodyState = null;
			return;
		}
		int targetColumn = targetSlot % MELODY_ROW_WIDTH;
		int movingRow = movingSlot / MELODY_ROW_WIDTH;
		int movingColumn = movingSlot % MELODY_ROW_WIDTH;
		// Row indices: 1..4 = attempts 0/4..3/4 (Odin's own hardcoded button rows).
		int attempt = Math.max(0, Math.min(3, movingRow - 1));
		int buttonSlot = movingRow * MELODY_ROW_WIDTH + (MELODY_ROW_WIDTH - 2);
		melodyState = new MelodyState(targetSlot, targetColumn, movingSlot, movingRow, movingColumn,
			buttonSlot, attempt, movingColumn == targetColumn);
	}

	// Package-private for TermSimController, same reasoning as RUBIX_ORDER above.
	static Set<String> selectPrefixesFor(String colorWord) {
		String normalized = colorWord.toUpperCase(Locale.ROOT).replace(' ', '_').replace("SILVER", "LIGHT_GRAY");
		DyeColor color;
		try {
			color = DyeColor.valueOf(normalized);
		} catch (IllegalArgumentException e) {
			return Set.of(colorWord.toLowerCase(Locale.ROOT));
		}
		return switch (color) {
			case BLACK -> Set.of("black", "ink");
			case BLUE -> Set.of("blue", "lapis");
			case BROWN -> Set.of("brown", "cocoa");
			case WHITE -> Set.of("white", "bone", "wool");
			case GREEN -> Set.of("green", "cactus");
			case RED -> Set.of("red", "rose");
			case YELLOW -> Set.of("yellow", "dandelion");
			case LIGHT_GRAY -> Set.of("silver", "light gray");
			default -> Set.of(color.getName().toLowerCase(Locale.ROOT).replace('_', ' '));
		};
	}

	private static List<Integer> solve(List<ItemStack> items) {
		return switch (currentType) {
			case PANES -> solvePanes(items);
			case NUMBERS -> solveNumbers(items);
			case RUBIX -> solveRubix(items);
			case STARTS_WITH -> solveStartsWith(items);
			case SELECT -> solveSelect(items);
			case MELODY -> List.of();
		};
	}

	private static List<Integer> solvePanes(List<ItemStack> items) {
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) if (paneColorOf(items.get(i)) == DyeColor.RED) result.add(i);
		return result;
	}

	private static List<Integer> solveNumbers(List<ItemStack> items) {
		List<Integer> matches = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) if (paneColorOf(items.get(i)) == DyeColor.RED) matches.add(i);
		matches.sort((a, b) -> Integer.compare(items.get(a).getCount(), items.get(b).getCount()));
		return matches;
	}

	private static List<Integer> solveStartsWith(List<ItemStack> items) {
		if (startsWithLetter == null) return List.of();
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			ItemStack item = items.get(i);
			if (startsWithClicked.contains(i)) continue;
			String name = item.getHoverName().getString();
			if (name.regionMatches(true, 0, startsWithLetter, 0, startsWithLetter.length()) && !item.hasFoil()) {
				result.add(i);
			}
		}
		return result;
	}

	private static List<Integer> solveSelect(List<ItemStack> items) {
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			ItemStack item = items.get(i);
			if (item.hasFoil() || paneColorOf(item) == DyeColor.BLACK) continue;
			String name = item.getHoverName().getString().toLowerCase(Locale.ROOT);
			for (String prefix : selectPrefixes) {
				if (name.startsWith(prefix)) { result.add(i); break; }
			}
		}
		return result;
	}

	private record RubixWork(int slotIndex, int count, boolean leftClick) {}

	// Faithful port of Odin's RubixHandler's color-selection logic (pick the color whose total remaining
	// clicks is cheapest, sticking with the previously-chosen color across re-scans once one has been
	// picked) — but the per-slot click count now genuinely picks whichever DIRECTION (left-click cycles
	// forward, right-click cycles backward) is cheaper for THAT slot, instead of a real bug where the
	// per-slot list always assumed forward even on the ~40% of cases (distance 3 or 4 out of 5 colors)
	// where going backward takes fewer clicks — the color-selection cost comparison already accounted for
	// this correctly (5 - count when count >= 3), but the actual returned slot list never did.
	private static List<Integer> solveRubix(List<ItemStack> items) {
		List<Integer> result = new ArrayList<>();
		for (RubixWork w : rubixWorkForBestColor(items)) {
			for (int i = 0; i < w.count(); i++) result.add(w.slotIndex());
		}
		return result;
	}

	private static List<RubixClick> computeRubixClicks(List<ItemStack> items) {
		List<RubixClick> result = new ArrayList<>();
		for (RubixWork w : rubixWorkForBestColor(items)) result.add(new RubixClick(w.slotIndex(), w.count(), w.leftClick()));
		return result;
	}

	private static List<RubixWork> rubixWorkForBestColor(List<ItemStack> items) {
		List<Integer> panes = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			DyeColor color = paneColorOf(items.get(i));
			if (color != null && color != DyeColor.BLACK) panes.add(i);
		}

		if (rubixLastSolutionColor != null) {
			return rubixWorkFor(items, panes, rubixLastSolutionColor);
		}
		List<RubixWork> best = null;
		int bestSize = Integer.MAX_VALUE;
		for (DyeColor color : RUBIX_ORDER) {
			List<RubixWork> candidate = rubixWorkFor(items, panes, color);
			int size = 0;
			for (RubixWork w : candidate) size += w.count();
			if (size < bestSize) {
				bestSize = size;
				best = candidate;
				rubixLastSolutionColor = color;
			}
		}
		return best != null ? best : List.of();
	}

	private static List<RubixWork> rubixWorkFor(List<ItemStack> items, List<Integer> paneIndices, DyeColor goal) {
		int goalIndex = indexOf(RUBIX_ORDER, goal);
		List<RubixWork> result = new ArrayList<>();
		for (int index : paneIndices) {
			DyeColor color = paneColorOf(items.get(index));
			if (color == null) continue;
			int paneIndex = indexOf(RUBIX_ORDER, color);
			if (paneIndex == goalIndex) continue;
			int forward = rubixDistance(paneIndex, goalIndex);
			boolean leftClick = forward * 2 <= RUBIX_ORDER.length;
			int count = leftClick ? forward : RUBIX_ORDER.length - forward;
			result.add(new RubixWork(index, count, leftClick));
		}
		return result;
	}

	private static int rubixDistance(int pane, int most) {
		return pane > most ? (most + RUBIX_ORDER.length) - pane : most - pane;
	}

	static int indexOf(DyeColor[] array, DyeColor value) {
		for (int i = 0; i < array.length; i++) if (array[i] == value) return i;
		return -1;
	}

	// Package-private for TermSimController, same reasoning as RUBIX_ORDER above.
	static DyeColor paneColorOf(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
		if (!(blockItem.getBlock() instanceof StainedGlassPaneBlock pane)) return null;
		return pane.getColor();
	}

	/** True for the black stained-glass-pane filler Hypixel itself uses to pad the unused cells of a
	 *  terminal's real slot window (RUBIX/NUMBERS/PANES/SELECT all confirmed to use it for their unused
	 *  sub-region, and STARTS_WITH/SELECT for their own unfilled slots) — public so TerminalSolverFeature's
	 *  panel-cropping can find the real used-slot bounding box generically, without hardcoding each type's
	 *  own sub-region a second time in the rendering code. */
	public static boolean isFillerSlot(ItemStack stack) {
		return stack.isEmpty() || paneColorOf(stack) == DyeColor.BLACK;
	}

	private static void fireOpen(TerminalType type) {
		for (Consumer<TerminalType> listener : openListeners) {
			try {
				listener.accept(type);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("TerminalTracker open listener threw", e);
			}
		}
	}

	private static void onChatMessage(String text) {
		Matcher matcher = SOLVE_PATTERN.matcher(text);
		if (!matcher.matches()) return;
		// Room-wide progress tally (see the field doc comment above): updated from EVERY party member's own
		// broadcast, not just the local player's — unlike the self-filtered solveListeners callback below,
		// "is the whole section done" doesn't care which teammate activated the last terminal.
		try {
			lastTerminalProgressCurrent = Integer.parseInt(matcher.group(2));
			lastTerminalProgressTotal = Integer.parseInt(matcher.group(3));
		} catch (NumberFormatException ignored) {}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !matcher.group(1).equals(mc.player.getName().getString())) return;
		for (Runnable listener : solveListeners) {
			try {
				listener.run();
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("TerminalTracker solve listener threw", e);
			}
		}
	}

	/** True once the most recent party-wide "&lt;name&gt; activated a terminal! (N/M)" broadcast for the
	 *  CURRENT section (see {@link DungeonState#getTerminalSection()}) reported every terminal in it as done
	 *  (current == total) — a live reading of this class's own shared solver state (the same state {@link
	 *  com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature} already depends on for "is a
	 *  terminal open right now"/"what's left to click in it"), not a separately re-derived count. Resets to
	 *  false the instant the tracked section changes (see the backing fields' own doc comment) — a stale
	 *  "done" from the PREVIOUS section can never read as the current one's. */
	public static boolean isCurrentTerminalSectionFullyActivated() {
		return lastTerminalProgressTotal > 0 && lastTerminalProgressCurrent >= lastTerminalProgressTotal;
	}
}
