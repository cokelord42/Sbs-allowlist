package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.TerminalTracker;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.gui.TooltipSuppressRegistry;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.HashSet;
import java.util.List;

/**
 * F7 device-room dungeon-terminal solver — highlights the correct slot(s) to click for all 5 click-based
 * terminal types (Panes, Rubix, Numbers, Starts With, Select All) and can block wrong clicks. Ported from
 * Odin's {@code TerminalSolver.kt} + its 5 corresponding {@code terminalhandler/*Handler.kt} classes. The
 * actual solving algorithm lives in {@link TerminalTracker} (shared with Terminal Sounds, which needs the
 * same "is this slot correct" answer for its click-sound gating) — this feature only owns the rendering,
 * click-blocking, and settings on top of that shared solve state.
 *
 * <p>Odin offers 3 render modes (its own overlay, an enlarged vanilla GUI, or a fully custom-drawn GUI)
 * and a full standalone practice simulator ({@code termsim}/{@code termGUI}, ~700 lines). Only the overlay
 * mode is ported — drawn on the real vanilla terminal screen via {@code ScreenEvents}, the same pattern
 * this port already uses for Leap Menu and Croesus — since the other two are pure UI/training conveniences
 * layered on top of the same solving logic, not the solving logic itself. */
public class TerminalSolverFeature extends Feature {
	private static final int GREEN = 0xFF55FF55;
	private static final int YELLOW = 0xFFFFFF55;
	private static final int RED = 0xFFFF5555;

	// 300ms is a hard floor (see setFirstClickProtectionMs), not just a default: letting this go to 0
	// would mean clicks can register the instant the terminal opens, which is the kind of inhuman timing
	// that's actually the bannable pattern here — not the delay itself.
	private static final long MIN_FIRST_CLICK_PROTECTION_MS = 300;

	/** Panel scale as "what real Minecraft GUI Scale would this look like at", not an arbitrary multiplier
	 *  — per user request, independent of whatever GUI Scale the player's own video settings are actually
	 *  set to (see computePanelBounds). */
	public enum GuiScale {
		X2(2), X3(3), X4(4);
		public final int factor;
		GuiScale(int factor) { this.factor = factor; }
	}

	private boolean blockWrongClicks = true;
	// Renamed from onlyShowCorrectClicks (see loadPersistedData's migration fallback for old configs) —
	// the old name described it as a filter ("only show the correct ones"), but it's never actually
	// filtered which slots get highlighted, only whether they're drawn as a separate custom-rendered GUI
	// panel (this) versus a translucent tint directly over the real vanilla GUI (off). Per explicit user
	// request for the toggle set to "make sense": this is the one toggle that turns the custom GUI on,
	// full stop — blocking wrong clicks (blockWrongClicks, below) already works completely independently
	// of this and always has (shouldBlockClick never reads this field).
	private boolean customTerminalGui = false;
	// Real Odin behavior (confirmed via its source, not invented): a left-click on a terminal slot gets
	// rewritten to a real middle-click (ContainerInput.CLONE) before it's sent — apparently the fastest
	// method Hypixel accepts for these menus. Right-clicks pass through unchanged.
	private boolean middleClickRedirect = true;
	private long firstClickProtectionMs = MIN_FIRST_CLICK_PROTECTION_MS;
	// Panes/Starts With/Select used to each have their own separate color — per explicit user request,
	// unified into one shared color since all three are functionally "highlight the correct slot(s)",
	// nothing about them individually needs a distinct color. Numbers and Rubix are unaffected: both
	// still use their own fixed multi-color coding since for those two the color itself carries required
	// information (click order / click direction), not just "correct or not".
	private int terminalColor = 0xFFFF5555;
	private GuiScale guiScale = GuiScale.X3;
	private boolean clickAnimations = true;

	private long termOpenedAtMs;

	// Real bug found: onPanelClick used to call computePanelBounds itself, independently of whatever
	// renderOverlay had most recently computed and actually drawn — two separate calls, potentially a
	// different frame apart. If anything the bounds depend on shifts between those two calls (most exposed
	// on Melody, whose real state changes every single tick), the click can resolve to a different slot
	// than the one visually highlighted when the player clicked it — exactly "click then respawns" (a real
	// click went to the wrong, still-unsolved slot) or "does nothing at all" for Melody specifically. Caching
	// exactly what was last drawn and reusing it for hit-testing guarantees the two can never disagree.
	private PanelBounds lastRenderedPanel;
	private int lastRenderedLeftPos;
	private int lastRenderedTopPos;

	private record ClickAnim(int slotIndex, long startMs) {}
	private static final long CLICK_ANIM_DURATION_MS = 300;
	private final java.util.List<ClickAnim> clickAnims = new java.util.ArrayList<>();

	// Per user report ("Lag causes it to be blank, making it very annoying to find the click that
	// lagged... Make it poll for clicks every 500ms... A newly clicked slot should not poll for another
	// 500ms"): a real click dispatched from the custom GUI panel can silently never land (dropped packet,
	// or just a slow connection) with nothing else ever re-sending it — the player has to notice the slot
	// is still highlighted and manually click it again. This tracks every real click this feature just
	// sent and, if TerminalTracker's own server-confirmed solution still shows that slot as unsolved 500ms
	// later, resends the exact same packet — never re-fires the click animation/sound (those already fired
	// once at the original dispatch, see dispatchPanelClick), purely a raw network resend.
	//
	// Deliberately excludes RUBIX and MELODY. Rubix's "still needs work" state is never a true no-op click
	// target (every click cycles the pane's color again, even a "correctly solved for this step" one), so
	// a duplicate real click there would actually re-advance the color — unlike the other four types, where
	// a solved slot becomes a real Hypixel-side filler pane and a duplicate click against it is a genuine,
	// already-established no-op (see TerminalTracker.isFillerSlot). Melody has no equivalent "resend the
	// same click" concept at all — its button's correctness is purely a live timing alignment that's almost
	// certainly different 500ms later, so retrying the exact same click there would be meaningless.
	private record PendingRealClick(int containerId, int mouseButton, ContainerInput inputType, long lastSentMs) {}
	private static final long CLICK_RETRY_INTERVAL_MS = 500;
	private final java.util.Map<Integer, PendingRealClick> pendingRealClicks = new java.util.HashMap<>();

	private static boolean listenersRegistered = false;
	private static TerminalSolverFeature instance;

	public TerminalSolverFeature() {
		super("terminal_solver", "Terminal Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;

			TerminalTracker.addOpenListener(type -> {
				if (instance != null) instance.termOpenedAtMs = System.currentTimeMillis();
			});

			// Real terminals only: closes the screen right after Hypixel's own "<name> activated a
			// terminal!" confirmation for this player, per user request ("after a terminal is done it
			// should auto close"). termsim closes itself locally the moment its own fake solve state empties
			// (see TermSimController) rather than waiting on this, since there's no real confirmation to wait
			// for there.
			TerminalTracker.addSolveListener(() -> {
				if (instance == null || !instance.isEnabled()) return;
				Minecraft mc = Minecraft.getInstance();
				if (mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.gui.setScreen(null);
			});

			ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
				if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
				// Custom Terminal GUI mode renders via TerminalCustomGuiRenderMixin instead (a true cancel-
				// and-replace of the real GUI's own render, matching Odin's mechanism — see that mixin's doc
				// comment) — afterExtract only ever fires for the OFF case now (the translucent-tint-over-the-
				// still-visible-real-GUI mode), since the mixin's ci.cancel() means extractRenderState never
				// reaches completion for afterExtract to fire from in the ON case anyway.
				ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
					if (instance == null || !instance.isEnabled() || instance.customTerminalGui) return;
					instance.renderOverlay(graphics, containerScreen);
				});
				// Custom Terminal GUI mode moves all the actual clicking onto the centered panel (see
				// renderReplacement's doc comment) — a click landing on the panel needs to be translated back
				// to the real slot it represents and dispatched as a real container click, since nothing else
				// makes that happen once the real GUI is hidden underneath. Every click is swallowed while a
				// custom-GUI terminal is open, not just ones that land on the panel — matching Odin's own
				// CustomTermGui.kt click handler (unconditional `return true` for the whole screen while
				// active) — the real GUI's own hoveredSlot tracking stopped updating the moment its render got
				// cancelled, so letting a stray click fall through to vanilla's now-stale click routing would
				// be undefined, not just "does nothing".
				ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
					if (instance == null || !instance.isEnabled() || !instance.customTerminalGui) return true;
					if (TerminalTracker.getCurrentType() == null) return true;
					instance.onPanelClick(containerScreen, event.x(), event.y(), event.button());
					return false;
				});
			});

			ContainerClickRegistry.setRule("terminal_solver", (slot, slotId, mouseButton, type) ->
				instance != null && instance.isEnabled() && instance.shouldBlockClick(slotId, mouseButton));

			// Left-click -> middle-click (CLONE) redirect, see middleClickRedirect's doc comment. Gated on
			// mouseButton == 0 (left only) so a real right-click still passes through as PICKUP, matching
			// Odin's own confirmed behavior. termsim never reaches this rule (its own veto in the "rules" map
			// above always wins first at the same choke point), which is correct — termsim never sends a real
			// packet, so which ContainerInput a real click "would" use is meaningless there.
			ContainerClickRegistry.setMiddleClickRule("terminal_solver", (slot, slotId, mouseButton, type) ->
				instance != null && instance.isEnabled() && instance.middleClickRedirect && mouseButton == 0
					&& TerminalTracker.getCurrentType() != null && slotId < TerminalTracker.getCurrentWindowSize());

			// Real bug found (per user report — "The rubiks terminal fix only worked for left click. Right
			// clicks are the same."): the middle-click rule above only ever covers mouseButton == 0, so a real
			// right-click on a Rubix pane always ran vanilla's slotClicked body unmodified — which locally
			// predicts the click's outcome (mutating the pane's item) via menu.clicked() before the server's
			// own authoritative response arrives. TerminalTracker.tick()'s changed() check then reads that
			// local mutation as a genuine server-confirmed change, making onTick's real-solve detection (see
			// getRubixActiveSlots' doc comment) fire the "fully solved" animation on every right click, not
			// just a truly-solved one. Rubix is the only terminal type this matters for (it's the only one
			// onTick treats specially for the disappear-animation), and only for clicks NOT already covered by
			// the middle-click rule above — a raw resend (original button/type, no CLONE substitution) skips
			// that local prediction while sending Hypixel the exact same packet an unmodified click would.
			ContainerClickRegistry.setRawResendRule("terminal_solver", (slot, slotId, mouseButton, type) ->
				instance != null && instance.isEnabled() && TerminalTracker.getCurrentType() == TerminalTracker.TerminalType.RUBIX
					&& slotId < TerminalTracker.getCurrentWindowSize()
					&& !(instance.middleClickRedirect && mouseButton == 0));

			// A real item's name/lore showing on hover would give away exactly what Custom Terminal GUI mode
			// is meant to hide — the opaque backdrop alone only hides the icon, not vanilla's own independent
			// hover/tooltip detection against the real (still-there, just invisible) slot underneath.
			TooltipSuppressRegistry.setRule("terminal_solver", (s, mouseX, mouseY) ->
				instance != null && instance.isEnabled() && instance.customTerminalGui && TerminalTracker.getCurrentType() != null);
		}
	}

	private record ClickJudgement(boolean blocked, boolean correct) {}

	/** The actual blocking/correctness decision, no side effects — shared by real terminals (via
	 *  {@link #shouldBlockClick}, decision + effects together) and termsim (decision now, effects
	 *  deferred — see {@link #shouldBlockTerminalClick}'s doc comment for why that split exists). */
	private ClickJudgement judgeClick(int slotId, int mouseButton) {
		TerminalTracker.TerminalType type = TerminalTracker.getCurrentType();
		if (type == null) return new ClickJudgement(false, false);
		// Real design correction (per user pushback — "regular minecraft allows you to queue a single
		// click... click current then click next and it will click the next one after clicking current",
		// and separately "the terminals are still REALLY slow"): the previous round's NUMBERS-only block-
		// the-next-click gate was solving the ordering-desync risk with the wrong tool — real vanilla
		// clicking never makes you wait out one click's round trip before your next click is even accepted,
		// it just sends each one immediately and reconciles independently. TerminalTracker's own
		// recentlyClicked skip-logic (isCorrectSlot/getCurrentSolution both already skip every entry still
		// awaiting confirmation, not just the most recent one) already handles several simultaneously-
		// pending NUMBERS clicks correctly without needing to block input at all.
		// Rubix correctness is direction-aware (a slot still needing forward progress isn't "correct" just
		// because it's still in the solve list), same distinction shouldBlockRubixClick already makes for
		// blocking — reused here so the animation/sound gating matches exactly, not just "is this slot
		// anywhere in the solution".
		boolean correct = type == TerminalTracker.TerminalType.RUBIX
			? !shouldBlockRubixClick(slotId, mouseButton == 1)
			: TerminalTracker.isCorrectSlot(slotId);
		boolean withinProtectionWindow = blockWrongClicks && type != TerminalTracker.TerminalType.MELODY
			&& System.currentTimeMillis() - termOpenedAtMs < firstClickProtectionMs;
		boolean blocked = withinProtectionWindow || (blockWrongClicks && type != TerminalTracker.TerminalType.MELODY && !correct);
		return new ClickJudgement(blocked, correct);
	}

	/** Real terminals: decision and effects (animation, optimistic hide) fire together, immediately —
	 *  there's no artificial delay to wait for, a real click either lands on Hypixel's server right now or
	 *  it doesn't, so "the click counted" and "show that it counted" are the same moment. */
	private boolean shouldBlockClick(int slotId, int mouseButton) {
		ClickJudgement judgement = judgeClick(slotId, mouseButton);
		// Gated on "not blocked" too, not just "correct" — a click still swallowed by the first-click
		// protection window never actually reaches the server, so treating it as having counted would make
		// the solver silently drop a slot the player still genuinely needs to click.
		if (!judgement.blocked() && judgement.correct()) fireCorrectClickEffects(slotId, TerminalTracker.getCurrentType());
		return judgement.blocked();
	}

	/** Per user request: the click animation should only fire on a click that actually counted, matching
	 *  TerminalSoundsFeature's own click sound (which already only plays on a correct click). Rubix is the
	 *  one exception: per user clarification ("dont play the animation unless a button is fully solved...
	 *  when its fully solved and disappears (like all other terminal buttons do) then it should play the
	 *  animation"), a single accepted Rubix click is very often NOT the pane's last click (see RubixClick's
	 *  count), so animating every one of them here would fire far more often than the "button disappears"
	 *  moment this is meant to mirror — that real-solve moment is detected separately in {@link #onTick}
	 *  once server state (not this optimistic judgement) confirms the slot fully done. */
	private void fireCorrectClickEffects(int slotId, TerminalTracker.TerminalType type) {
		if (clickAnimations && type != TerminalTracker.TerminalType.RUBIX) clickAnims.add(new ClickAnim(slotId, System.currentTimeMillis()));
		// Client-side "already handled" memory so this slot stops being highlighted/required immediately,
		// instead of waiting on the server round-trip to update the container's real item state — see
		// TerminalTracker.markClicked's doc comment. Rubix is deliberately excluded (a slot can legitimately
		// need several more real clicks in a row — see TerminalTracker.getRubixClicks' doc comment for the
		// optimistic-tracking bug this used to cause when Rubix had its own such mechanism, since removed):
		// every judgeClick call for Rubix already reads TerminalTracker's real, server-confirmed state
		// directly, so re-clicking the same slot before confirmation just sends another real click, exactly
		// as intended.
		if (type != TerminalTracker.TerminalType.RUBIX) TerminalTracker.markClicked(slotId);
	}

	/** Lets TermSimController reuse this exact same blocking DECISION (Rubix direction-awareness included)
	 *  for its own simulated clicks, instead of relying on which of "terminal_solver"/"termsim" happens to
	 *  be registered first in ContainerClickRegistry's rule map — that ordering isn't guaranteed (depends on
	 *  feature-enable timing), so a direct call is the only way to make termsim's overclick prevention
	 *  reliably match the real one regardless of it.
	 *
	 *  <p>Real bug found (per user report — "the animation is instant... if I put 5000 ping, the animation
	 *  is instant, the button comes back and then 5 seconds later the button activates"): this used to call
	 *  the combined {@link #shouldBlockClick}, which fires the click animation and the optimistic "already
	 *  handled" hide the INSTANT the raw click event happens — that's correct for a real terminal (there's
	 *  no delay to simulate), but for termsim it meant the animation/hide fired immediately regardless of
	 *  the configured simulated ping, then visibly reverted once the ~600ms optimistic-hide grace window
	 *  expired — well before the real (and typically much longer) simulated network delay did. Now
	 *  decision-only: TermSimController still needs an immediate answer to "should I even queue this
	 *  click" (no reason to simulate a delay for a click that was already wrong), but the actual animation/
	 *  hide only fires once the simulated delay has elapsed — see {@link #applyTerminalClickEffects}. */
	public static boolean shouldBlockTerminalClick(int slotId, int mouseButton) {
		return instance != null && instance.isEnabled() && instance.judgeClick(slotId, mouseButton).blocked();
	}

	/** Fires the click animation + optimistic hide for a termsim click that has now actually landed —
	 *  called by TermSimController.applyClick once its simulated ping delay elapses, instead of at raw
	 *  click time (see {@link #shouldBlockTerminalClick}'s doc comment for the bug this fixes). Confirmed
	 *  this never touches real network dispatch at all: termsim's own click path never calls
	 *  {@code handleContainerInput} in the first place (see TermSimController's class doc comment) — this
	 *  only ever fires the local animation/highlight-hide, nothing is sent anywhere. */
	public static void applyTerminalClickEffects(int slotId) {
		if (instance != null && instance.isEnabled()) instance.fireCorrectClickEffects(slotId, TerminalTracker.getCurrentType());
	}

	/** Direction-aware, ported from Odin's confirmed RubixHandler.canClick: a slot still needing forward
	 *  (left-click) progress blocks right-clicks on it, a slot needing backward (right-click) progress
	 *  blocks anything else, and a slot no longer in the list at all (fully solved) blocks every click —
	 *  the plain "is this slot anywhere in the solution" check the other 4 types use doesn't distinguish
	 *  direction, which is what let genuinely wrong-direction clicks slip through as "overclicking". */
	private boolean shouldBlockRubixClick(int slotId, boolean rightClick) {
		for (TerminalTracker.RubixClick click : TerminalTracker.getRubixClicks()) {
			if (click.slotIndex() == slotId) return click.leftClick() == rightClick;
		}
		return true;
	}

	// Real Hypixel pane color for Melody's marker panes — used for both target and moving squircles.
	private static final int PURPLE = 0xFFCC33FF;
	private static final int PANEL_RADIUS = 8;
	private static final int SQUIRCLE_RADIUS = 4;
	// Mirrors TerminalTracker's own real Melody layout constant (private there) — the button column is
	// always MELODY_ROW_WIDTH - 2, see renderMelody's own doc comment on the vertical click-line.
	private static final int MELODY_ROW_WIDTH = 9;

	private record SlotRect(int x, int y, int size) {}

	/** Where the custom GUI panel sits and how a real slot's (leftPos/topPos-relative) offset maps into
	 *  it — {@code null} everywhere a render method takes a PanelBounds parameter means "customTerminalGui
	 *  is off, draw at the real slot position instead" (the translucent-tint-over-the-real-GUI mode). */
	private record PanelBounds(int x0, int y0, int x1, int y1, float scaleX, float scaleY, int cropX, int cropY) {
		int mapX(int realOffsetX) { return x0 + Math.round((realOffsetX - cropX) * scaleX); }
		int mapY(int realOffsetY) { return y0 + Math.round((realOffsetY - cropY) * scaleY); }
		int mapSize(int realSize) { return Math.max(1, Math.round(realSize * scaleX)); }
	}

	private SlotRect slotRect(Slot slot, int leftPos, int topPos, PanelBounds panel) {
		if (panel == null) return new SlotRect(leftPos + slot.x, topPos + slot.y, 16);
		return new SlotRect(panel.mapX(slot.x), panel.mapY(slot.y), panel.mapSize(16));
	}

	private static final int ROW_WIDTH = 9;

	/** The fixed real sub-region for each click-based type — {minRow, minCol, maxRowExclusive,
	 *  maxColExclusive} — confirmed against Odin's own TerminalTypes.kt simpleTermGui() calls (cross-
	 *  referenced in TermSimController's own per-type generation comments, not re-derived here). Melody has
	 *  no such documented fixed sub-region (its real "used" cells already never turn into filler as you
	 *  solve, so the dynamic scan below never shrank for it in the first place) — null means "fall back to
	 *  scanning for real items". */
	private static int[] fixedRegionFor(TerminalTracker.TerminalType type) {
		return switch (type) {
			case PANES -> new int[]{1, 2, 4, 7};
			case RUBIX -> new int[]{1, 3, 4, 6};
			case NUMBERS -> new int[]{1, 1, 3, 8};
			case STARTS_WITH -> new int[]{1, 1, 4, 8};
			case SELECT -> new int[]{1, 1, 5, 8};
			case MELODY -> null;
		};
	}

	/** Centered on screen — per user request, a straight reskin of the real terminal rather than a
	 *  side-by-side panel.
	 *
	 *  <p>Real bug found: this used to crop to the bounding box of "whichever slots currently hold a real,
	 *  non-filler item" (Hypixel pads every terminal type's unused cells with a black stained-glass pane —
	 *  see TerminalTracker.isFillerSlot). That's a live, per-frame scan — as the player correctly solves
	 *  slots, Hypixel turns them into filler too, so the bounding box (and therefore the whole panel)
	 *  visibly shrank over the course of a solve, one slot at a time, most obviously whenever an edge slot
	 *  was the one solved. Real terminals never do this — they're always their type's fixed real size for
	 *  the whole time they're open. Now crops to that fixed region (see fixedRegionFor) instead, so the
	 *  panel size stays constant regardless of solve progress, matching real Hypixel exactly. */
	private PanelBounds computePanelBounds(AbstractContainerScreen<?> screen, AbstractContainerScreenAccessor accessor, int leftPos, int topPos) {
		int imageWidth = accessor.skyblocksimplified$getImageWidth();
		int imageHeight = accessor.skyblocksimplified$getImageHeight();

		int windowSize = TerminalTracker.getCurrentWindowSize();
		int[] fixed = fixedRegionFor(TerminalTracker.getCurrentType());
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		if (fixed != null) {
			for (int row = fixed[0]; row < fixed[2]; row++) {
				for (int col = fixed[1]; col < fixed[3]; col++) {
					int index = row * ROW_WIDTH + col;
					if (index >= windowSize) continue;
					Slot slot = screen.getMenu().getSlot(index);
					minX = Math.min(minX, slot.x);
					minY = Math.min(minY, slot.y);
					maxX = Math.max(maxX, slot.x + 16);
					maxY = Math.max(maxY, slot.y + 16);
				}
			}
		} else {
			for (int i = 0; i < windowSize; i++) {
				Slot slot = screen.getMenu().getSlot(i);
				if (TerminalTracker.isFillerSlot(slot.getItem())) continue;
				minX = Math.min(minX, slot.x);
				minY = Math.min(minY, slot.y);
				maxX = Math.max(maxX, slot.x + 16);
				maxY = Math.max(maxY, slot.y + 16);
			}
		}
		int cropX, cropY, cropWidth, cropHeight;
		if (minX <= maxX) {
			int margin = 4;
			cropX = Math.max(0, minX - margin);
			cropY = Math.max(0, minY - margin);
			cropWidth = Math.min(imageWidth, maxX + margin) - cropX;
			cropHeight = Math.min(imageHeight, maxY + margin) - cropY;
		} else {
			// Nothing real found yet (e.g. the very first frame before items arrive) — fall back to the
			// whole container rather than a degenerate zero-size crop.
			cropX = 0;
			cropY = 0;
			cropWidth = imageWidth;
			cropHeight = imageHeight;
		}

		float panelScale = guiScale.factor;
		float panelW = cropWidth * panelScale;
		float panelH = cropHeight * panelScale;

		Minecraft mc = Minecraft.getInstance();
		int screenW = mc.getWindow().getGuiScaledWidth();
		int screenH = mc.getWindow().getGuiScaledHeight();

		int x0 = Math.round((screenW - panelW) / 2f);
		int y0 = Math.round((screenH - panelH) / 2f);
		x0 = Math.max(0, Math.min(x0, Math.round(screenW - panelW)));
		y0 = Math.max(0, Math.min(y0, Math.round(screenH - panelH)));

		return new PanelBounds(x0, y0, x0 + Math.round(panelW), y0 + Math.round(panelH),
			panelW / cropWidth, panelH / cropHeight, cropX, cropY);
	}

	private void drawPanelBackground(net.minecraft.client.gui.GuiGraphicsExtractor graphics, PanelBounds panel) {
		RenderUtil.fillRounded(graphics, panel.x0(), panel.y0(), panel.x1(), panel.y1(), PANEL_RADIUS, 0xFF000000);
	}

	/** Custom Terminal GUI off: the old semi-transparent tint drawn directly over the still-visible real
	 *  items (panel stays null throughout). On, the real vanilla GUI's render is cancelled entirely by
	 *  {@link com.cokelord.skyblocksimplified.mixin.TerminalCustomGuiRenderMixin} before this method is ever
	 *  reached — see {@link #renderReplacement} for that path instead. Numbers and Rubix always use their own
	 *  fixed multi-color coding regardless of the toggle, since for those two the color itself carries
	 *  required information (click order / direction).
	 *
	 *  <p>Type is detected synchronously here via {@link TerminalTracker#ensureDetected} rather than only
	 *  reading whatever the last tick found — per user report ("I remember for a split frame it shows [the
	 *  real GUI]"), the tick-based detection could lag several render frames behind the screen actually
	 *  opening, showing the raw, un-hidden vanilla GUI for that gap every single time. */
	private void renderOverlay(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		TerminalTracker.TerminalType type = TerminalTracker.ensureDetected(screen);
		if (type == null) return;
		try {
			AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
			int leftPos = accessor.skyblocksimplified$getLeftPos();
			int topPos = accessor.skyblocksimplified$getTopPos();
			lastRenderedPanel = null;
			lastRenderedLeftPos = leftPos;
			lastRenderedTopPos = topPos;
			renderPanelContent(graphics, screen, leftPos, topPos, null, type);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Terminal Solver overlay render failed, skipping this frame", e);
		}
	}

	/** Called from {@link com.cokelord.skyblocksimplified.mixin.TerminalCustomGuiRenderMixin} once
	 *  {@link #shouldReplaceRender} has confirmed this screen's real render was just cancelled — matches
	 *  Odin's own {@code CustomTermGui.render()} behavior exactly: no full-screen dim at all, just the
	 *  centered panel's own rounded background drawn directly over the live, undimmed game world, since the
	 *  vanilla GUI never rendered in the first place for there to be anything left to hide underneath it. */
	public static void renderReplacement(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		if (instance != null) instance.doRenderReplacement(graphics, screen);
	}

	/** True while a custom-GUI terminal is genuinely open for this exact screen — gates both the mixin's
	 *  cancellation and (implicitly, since nothing else calls it) {@link #renderReplacement}. Type is
	 *  detected synchronously via {@link TerminalTracker#ensureDetected}, same reasoning as {@link #renderOverlay}. */
	public static boolean shouldReplaceRender(AbstractContainerScreen<?> screen) {
		return instance != null && instance.isEnabled() && instance.customTerminalGui
			&& TerminalTracker.ensureDetected(screen) != null;
	}

	private void doRenderReplacement(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		TerminalTracker.TerminalType type = TerminalTracker.ensureDetected(screen);
		if (type == null) return;
		try {
			AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
			int leftPos = accessor.skyblocksimplified$getLeftPos();
			int topPos = accessor.skyblocksimplified$getTopPos();
			PanelBounds panel = computePanelBounds(screen, accessor, leftPos, topPos);
			lastRenderedPanel = panel;
			lastRenderedLeftPos = leftPos;
			lastRenderedTopPos = topPos;
			// No full-screen cover here (tried once as a defensive backstop, then removed per user request
			// — "not needed", and looked wrong stacked with a working cancellation anyway). The mixin's
			// ci.cancel() is what actually hides the real GUI; the one case it visibly didn't per user
			// testing was termsim specifically, whose ChestMenu bundles the player's OWN real inventory as
			// part of the same menu (see TermSimController's own doc comment) — real Hypixel terminal
			// screens don't have that same real-inventory tail to leak, per the user's own read of it.
			drawPanelBackground(graphics, panel);
			renderPanelContent(graphics, screen, leftPos, topPos, panel, type);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Terminal Solver custom GUI render failed, skipping this frame", e);
		}
	}

	/** Click animations plus whichever type-specific highlight render applies — shared by both
	 *  {@link #renderOverlay} (panel always null, the tint-over-real-GUI mode) and
	 *  {@link #doRenderReplacement} (panel always non-null, the true replacement mode). */
	private void renderPanelContent(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int leftPos, int topPos, PanelBounds panel, TerminalTracker.TerminalType type) {
		renderClickAnimations(graphics, screen, leftPos, topPos, panel);

		if (type == TerminalTracker.TerminalType.MELODY) {
			TerminalTracker.MelodyState state = TerminalTracker.getMelodyState();
			if (state == null) return;
			renderMelody(graphics, screen, leftPos, topPos, panel, state);
			return;
		}

		List<Integer> solution = TerminalTracker.getCurrentSolution();
		if (solution.isEmpty()) return;

		switch (type) {
			case NUMBERS -> renderNumbers(graphics, screen, leftPos, topPos, panel, solution);
			case RUBIX -> renderRubix(graphics, screen, leftPos, topPos, panel);
			default -> renderFlat(graphics, screen, leftPos, topPos, panel, solution, colorForType(type));
		}
	}

	/** A click landing on the panel is translated back to the real slot it visually represents and
	 *  dispatched as a real container click — see the onEnable registration's doc comment. The caller
	 *  already swallows every click screen-wide while a custom-GUI terminal is open (matching Odin), so this
	 *  only needs to find (or not find) a slot to dispatch to, not decide whether to consume the click. */
	private void onPanelClick(AbstractContainerScreen<?> screen, double mouseX, double mouseY, int button) {
		// Reuses exactly what renderReplacement last drew (see lastRenderedPanel's doc comment) instead of
		// recomputing independently, so the slot a click resolves to can never disagree with what's shown.
		PanelBounds panel = lastRenderedPanel;
		int leftPos = lastRenderedLeftPos;
		int topPos = lastRenderedTopPos;
		if (panel == null) return;
		if (mouseX < panel.x0() || mouseX >= panel.x1() || mouseY < panel.y0() || mouseY >= panel.y1()) return;

		int windowSize = TerminalTracker.getCurrentWindowSize();
		int slotCount = screen.getMenu().slots.size();
		for (int i = 0; i < windowSize && i < slotCount; i++) {
			Slot slot = screen.getMenu().getSlot(i);
			SlotRect r = slotRect(slot, leftPos, topPos, panel);
			if (mouseX >= r.x() && mouseX < r.x() + r.size() && mouseY >= r.y() && mouseY < r.y() + r.size()) {
				dispatchPanelClick(screen, i, button);
				break;
			}
		}
	}

	/** Mirrors what a real click on the (now-hidden) slot itself would do: gated through the exact same
	 *  shouldBlockClick logic real clicks use (blocking, click animation, and TerminalTracker.markClicked
	 *  all still apply), then sent as either a real middle-click (CLONE, matching middleClickRedirect's own
	 *  real-click behavior) or a plain left/right PICKUP.
	 *
	 *  <p>{@code /termsim} has no real server-side container to click into at all, so it's checked FIRST and
	 *  directly (not via {@link ContainerClickRegistry}'s shared choke point, which also holds this
	 *  feature's own "terminal_solver" rule — routing through there double-evaluated shouldBlockClick for
	 *  the exact same click and silently dropped it; see {@link com.cokelord.skyblocksimplified.dungeon.TermSimController#handlePanelClick}'s
	 *  doc comment for the full real-bug writeup). Real terminals are unaffected: handlePanelClick returns
	 *  false immediately for them and this falls through to the single shouldBlockClick call below exactly
	 *  as before. */
	private void dispatchPanelClick(AbstractContainerScreen<?> screen, int slotId, int mouseButton) {
		if (com.cokelord.skyblocksimplified.dungeon.TermSimController.handlePanelClick(slotId, mouseButton == 1)) return;
		if (shouldBlockClick(slotId, mouseButton)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) return;
		int containerId = screen.getMenu().containerId;
		int realButton = middleClickRedirect && mouseButton == 0 ? 0 : mouseButton;
		ContainerInput inputType = middleClickRedirect && mouseButton == 0 ? ContainerInput.CLONE : ContainerInput.PICKUP;
		mc.gameMode.handleContainerInput(containerId, slotId, realButton, inputType, mc.player);

		// See pendingRealClicks' own doc comment for why RUBIX/MELODY are excluded here.
		TerminalTracker.TerminalType type = TerminalTracker.getCurrentType();
		if (type != null && type != TerminalTracker.TerminalType.RUBIX && type != TerminalTracker.TerminalType.MELODY) {
			pendingRealClicks.put(slotId, new PendingRealClick(containerId, realButton, inputType, System.currentTimeMillis()));
		}
	}

	/** Resends any real click from {@link #pendingRealClicks} that TerminalTracker's own server-confirmed
	 *  solution still shows as unsolved 500ms after it was sent — see that field's doc comment. Bails out
	 *  (clearing everything tracked) the instant the screen, container id, or detected terminal type no
	 *  longer match what a pending click was sent into, so a resend can never land in some unrelated menu
	 *  opened right after a terminal closes. */
	// Real (server-confirmed) Rubix slots still needing work as of the last tick — compared against the
	// current real set every tick so a slot's disappearance (fully solved, per TerminalTracker's own
	// non-optimistic rubixClicks field) can be detected and animated exactly once, matching every other
	// terminal type's "animation plays when the button disappears" behavior.
	private java.util.Set<Integer> lastRubixActiveSlots = java.util.Set.of();

	@Override
	public void onTick(Minecraft client) {
		if (TerminalTracker.getCurrentType() == TerminalTracker.TerminalType.RUBIX) {
			java.util.Set<Integer> active = TerminalTracker.getRubixActiveSlots();
			if (clickAnimations) {
				for (Integer slot : lastRubixActiveSlots) {
					if (!active.contains(slot)) clickAnims.add(new ClickAnim(slot, System.currentTimeMillis()));
				}
			}
			lastRubixActiveSlots = active;
		} else if (!lastRubixActiveSlots.isEmpty()) {
			lastRubixActiveSlots = java.util.Set.of();
		}

		if (pendingRealClicks.isEmpty()) return;
		if (!customTerminalGui || client.player == null || client.gameMode == null
			|| !(client.gui.screen() instanceof AbstractContainerScreen<?> screen)
			|| TerminalTracker.getCurrentType() == null) {
			pendingRealClicks.clear();
			return;
		}
		int containerId = screen.getMenu().containerId;
		List<Integer> solution = TerminalTracker.getCurrentSolution();
		long now = System.currentTimeMillis();
		pendingRealClicks.entrySet().removeIf(entry -> {
			PendingRealClick pending = entry.getValue();
			if (pending.containerId() != containerId) return true;
			if (!solution.contains(entry.getKey())) return true;
			if (now - pending.lastSentMs() < CLICK_RETRY_INTERVAL_MS) return false;
			client.gameMode.handleContainerInput(containerId, entry.getKey(), pending.mouseButton(), pending.inputType(), client.player);
			entry.setValue(new PendingRealClick(containerId, pending.mouseButton(), pending.inputType(), now));
			return false;
		});
	}

	@Override
	protected void onDisable() {
		pendingRealClicks.clear();
	}

	/** Renders a short-lived expanding-and-fading white square centered on whatever slot was just clicked
	 *  — per user request, click feedback for the terminal solver, toggleable via clickAnimations since
	 *  not everyone wants it. Pruned/drawn every frame rather than scheduled, same "just check elapsed time
	 *  against System.currentTimeMillis()" pattern this project already uses everywhere else (e.g.
	 *  MoneyPerHourFeature's idle-hide, TerminalSolverFeature's own firstClickProtectionMs). */
	private void renderClickAnimations(net.minecraft.client.gui.GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int leftPos, int topPos, PanelBounds panel) {
		if (clickAnims.isEmpty()) return;
		long now = System.currentTimeMillis();
		clickAnims.removeIf(anim -> now - anim.startMs() >= CLICK_ANIM_DURATION_MS);
		int slotCount = screen.getMenu().slots.size();
		for (ClickAnim anim : clickAnims) {
			if (anim.slotIndex() < 0 || anim.slotIndex() >= slotCount) continue;
			float t = (now - anim.startMs()) / (float) CLICK_ANIM_DURATION_MS;
			Slot slot = screen.getMenu().getSlot(anim.slotIndex());
			SlotRect r = slotRect(slot, leftPos, topPos, panel);
			int cx = r.x() + r.size() / 2;
			int cy = r.y() + r.size() / 2;
			int size = Math.round((4f + t * 20f) * (r.size() / 16f));
			int half = size / 2;
			int alpha = Math.round((1f - t) * 200f);
			int color = (alpha << 24) | 0xFFFFFF;
			RenderUtil.fillRounded(graphics, cx - half, cy - half, cx + half, cy + half, Math.max(1, size / 4), color);
		}
	}

	/** Per the user's hand-drawn reference: two small purple squircles (the fixed target the player aligns
	 *  to, and the marker that's actually moving) plus a tall bar on the right spanning every row the button
	 *  can appear on — a dim "track" showing where to watch, with a bright green/red highlight over the
	 *  currently-active row's segment of it. The button's exact column and the target/moving marker's shade
	 *  are a best-effort reading of the user's description, not independently confirmed against real packet
	 *  data like the other 5 types — worth a live double-check in an actual dungeon. */
	/** Per user request ("add a slight outline on the squircles in the custom terminal gui, like a 1 pixel
	 *  thick outline but make it like half opacity, it should not be annoying but still show buttons more
	 *  clearly"): a thin, half-opacity white ring around every terminal-solver squircle button, drawn on top
	 *  of that button's own fill — works over any fill color (purple, red, green, yellow) since it's just a
	 *  subtle edge highlight, not a full-strength border. */
	private static void drawSquircleOutline(net.minecraft.client.gui.GuiGraphicsExtractor graphics, SlotRect r) {
		RenderUtil.fillRoundedRing(graphics, r.x(), r.y(), r.x() + r.size(), r.y() + r.size(), SQUIRCLE_RADIUS, 1, 0x80FFFFFF);
	}

	private void renderMelody(net.minecraft.client.gui.GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int leftPos, int topPos, PanelBounds panel, TerminalTracker.MelodyState state) {
		int slotCount = screen.getMenu().slots.size();

		if (state.targetSlot() < slotCount) {
			Slot slot = screen.getMenu().getSlot(state.targetSlot());
			SlotRect r = slotRect(slot, leftPos, topPos, panel);
			int fill = panel != null ? PURPLE : ((0x60 << 24) | (PURPLE & 0xFFFFFF));
			RenderUtil.fillRounded(graphics, r.x(), r.y(), r.x() + r.size(), r.y() + r.size(), SQUIRCLE_RADIUS, fill);
			drawSquircleOutline(graphics, r);
		}
		if (state.movingSlot() < slotCount) {
			Slot slot = screen.getMenu().getSlot(state.movingSlot());
			SlotRect r = slotRect(slot, leftPos, topPos, panel);
			int fill = panel != null ? PURPLE : ((0x60 << 24) | (PURPLE & 0xFFFFFF));
			RenderUtil.fillRounded(graphics, r.x(), r.y(), r.x() + r.size(), r.y() + r.size(), SQUIRCLE_RADIUS, fill);
			drawSquircleOutline(graphics, r);
		}

		// Per user request ("Hide the dark green parts of the melody terminal... hide the green line on the
		// right side"): used to also draw a tall dark-green "track" bar spanning the whole button column
		// (rows 1-4) behind the active button below — removed entirely now, keeping just the active button
		// itself (red/green per state.aligned()), which is the part that actually tells the user where to
		// click.
		if (state.buttonSlot() < slotCount) {
			Slot active = screen.getMenu().getSlot(state.buttonSlot());
			SlotRect rActive = slotRect(active, leftPos, topPos, panel);
			int color = state.aligned() ? GREEN : RED;
			int activeFill = panel != null ? (0xFF000000 | (color & 0xFFFFFF)) : ((0xA0 << 24) | (color & 0xFFFFFF));
			RenderUtil.fillRounded(graphics, rActive.x(), rActive.y(), rActive.x() + rActive.size(), rActive.y() + rActive.size(), SQUIRCLE_RADIUS, activeFill);
			drawSquircleOutline(graphics, rActive);
			// Per user request ("Hide the little 1/4 in the melody term gui"): the attempt-count text used to
			// draw here — removed, the button's own red/green fill already tells the user everything they need.
			// Per later user request ("Termsim line should be thicker. Actually the exact same as the current
			// green button the user presses when its aligned. I literally just want to make all 4 melody
			// buttons the user clicks when the panes are aligned red, but the current one (the one for the row
			// the user is on) should be the same green as currently"): replaces the thin red line this used to
			// draw down the button column with the SAME squircle style/size as the active button, drawn at
			// each of the other 3 real button-row positions — the melody button always sits at the same fixed
			// column (MELODY_ROW_WIDTH - 2) and only ever moves between these 4 fixed rows (see this method's
			// own already-cited real layout), so this shows all 4 possible click targets at a glance, with
			// only the row the user is actually on singled out in green/red per state.aligned() above.
			int melodyButtonColumn = MELODY_ROW_WIDTH - 2;
			int redFill = panel != null ? (0xFF000000 | (RED & 0xFFFFFF)) : ((0xA0 << 24) | (RED & 0xFFFFFF));
			// Real bug found (per user report — "You restored the wrong melody square. It shouldve been like
			// it was, with no square on the upper row, but it should add a square on the fifth row since
			// melody has 4 rows and there are only three squares"): a PRIOR round's "fix" changed this loop to
			// rows 0-3, on the theory that row 0 (where the static target pane sits) was also a real button
			// row — it isn't. TerminalTracker.updateMelody's own real button-row math
			// (buttonSlot = movingRow * MELODY_ROW_WIDTH + ...) only ever uses movingRow 1-4, never 0 — the 4
			// real button positions are rows 1-4, not 0-3. Corrected back to the real range.
			for (int row = 1; row <= 4; row++) {
				int slotIndex = row * MELODY_ROW_WIDTH + melodyButtonColumn;
				if (slotIndex == state.buttonSlot() || slotIndex >= slotCount) continue;
				Slot rowSlot = screen.getMenu().getSlot(slotIndex);
				SlotRect r = slotRect(rowSlot, leftPos, topPos, panel);
				RenderUtil.fillRounded(graphics, r.x(), r.y(), r.x() + r.size(), r.y() + r.size(), SQUIRCLE_RADIUS, redFill);
				drawSquircleOutline(graphics, r);
			}
		}
	}

	private void renderFlat(net.minecraft.client.gui.GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen,
							 int leftPos, int topPos, PanelBounds panel, List<Integer> solution, int color) {
		int fillColor = panel != null ? (0xFF000000 | (color & 0xFFFFFF)) : ((0x60 << 24) | (color & 0xFFFFFF));
		for (int slotIndex : new HashSet<>(solution)) {
			if (slotIndex >= screen.getMenu().slots.size()) continue;
			Slot slot = screen.getMenu().getSlot(slotIndex);
			SlotRect r = slotRect(slot, leftPos, topPos, panel);
			RenderUtil.fillRounded(graphics, r.x(), r.y(), r.x() + r.size(), r.y() + r.size(), SQUIRCLE_RADIUS, fillColor);
			drawSquircleOutline(graphics, r);
		}
	}

	/** currentSolution for NUMBERS is already ordered (index 0 = next number to click). Per user request,
	 *  only the next 3 clicks are ever shown — current (green), then (yellow), then the one after that
	 *  (red) — everything beyond that stays unhighlighted so it blends into the panel's own black
	 *  background instead of cluttering the whole grid with a wall of red squares. */
	private void renderNumbers(net.minecraft.client.gui.GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen,
								int leftPos, int topPos, PanelBounds panel, List<Integer> solution) {
		for (int i = 0; i < solution.size() && i < 3; i++) {
			int slotIndex = solution.get(i);
			if (slotIndex >= screen.getMenu().slots.size()) continue;
			int color = i == 0 ? GREEN : i == 1 ? YELLOW : RED;
			int fillColor = panel != null ? (0xFF000000 | (color & 0xFFFFFF)) : ((0x60 << 24) | (color & 0xFFFFFF));
			Slot slot = screen.getMenu().getSlot(slotIndex);
			SlotRect r = slotRect(slot, leftPos, topPos, panel);
			RenderUtil.fillRounded(graphics, r.x(), r.y(), r.x() + r.size(), r.y() + r.size(), SQUIRCLE_RADIUS, fillColor);
			drawSquircleOutline(graphics, r);
		}
	}

	/** Left-click (forward) panes green, right-click (backward) panes red, with the remaining click count
	 *  drawn as text — per explicit user instruction: "make the one they need to left click green and the
	 *  ones they need to right click red and also show how many times." */
	private void renderRubix(net.minecraft.client.gui.GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen,
							  int leftPos, int topPos, PanelBounds panel) {
		Minecraft mc = Minecraft.getInstance();
		for (TerminalTracker.RubixClick click : TerminalTracker.getRubixClicks()) {
			if (click.slotIndex() >= screen.getMenu().slots.size()) continue;
			int color = click.leftClick() ? GREEN : RED;
			int fillColor = panel != null ? (0xFF000000 | (color & 0xFFFFFF)) : ((0x60 << 24) | (color & 0xFFFFFF));
			Slot slot = screen.getMenu().getSlot(click.slotIndex());
			SlotRect r = slotRect(slot, leftPos, topPos, panel);
			RenderUtil.fillRounded(graphics, r.x(), r.y(), r.x() + r.size(), r.y() + r.size(), SQUIRCLE_RADIUS, fillColor);
			drawSquircleOutline(graphics, r);
			// Right-click slots get a literal "-" prefix (e.g. "-2") — matches Odin's own confirmed
			// RubixHandler rendering (a negative click count, rendered via toString()), not just a color
			// difference, so it reads correctly even for someone who can't distinguish green/red at a glance.
			String countText = (click.leftClick() ? "" : "-") + click.count();
			int textX = r.x() + r.size() - mc.font.width(countText) - 2;
			graphics.text(mc.font, countText, textX, r.y() + r.size() / 2 - 4, 0xFFFFFFFF);
		}
	}

	private int colorForType(TerminalTracker.TerminalType type) {
		return switch (type) {
			case PANES, STARTS_WITH, SELECT -> terminalColor;
			case NUMBERS, RUBIX, MELODY -> 0;
		};
	}

	public boolean isBlockWrongClicks() { return blockWrongClicks; }
	public void setBlockWrongClicks(boolean value) { blockWrongClicks = value; }
	public boolean isCustomTerminalGui() { return customTerminalGui; }
	public void setCustomTerminalGui(boolean value) { customTerminalGui = value; }
	public boolean isMiddleClickRedirect() { return middleClickRedirect; }
	public void setMiddleClickRedirect(boolean value) { middleClickRedirect = value; }
	public long getFirstClickProtectionMs() { return firstClickProtectionMs; }
	public void setFirstClickProtectionMs(long value) { firstClickProtectionMs = Math.max(MIN_FIRST_CLICK_PROTECTION_MS, Math.min(2000, value)); }
	public int getTerminalColor() { return terminalColor; }
	public void setTerminalColor(int value) { terminalColor = value; }
	public GuiScale getGuiScale() { return guiScale; }
	public void setGuiScale(GuiScale value) { guiScale = value; }
	public void cycleGuiScale() {
		GuiScale[] values = GuiScale.values();
		guiScale = values[(guiScale.ordinal() + 1) % values.length];
	}
	public static String guiScaleLabel(GuiScale scale) { return scale.factor + "x"; }
	public boolean isClickAnimations() { return clickAnimations; }
	public void setClickAnimations(boolean value) {
		clickAnimations = value;
		if (!value) clickAnims.clear();
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("blockWrongClicks", blockWrongClicks);
		obj.addProperty("customTerminalGui", customTerminalGui);
		obj.addProperty("middleClickRedirect", middleClickRedirect);
		obj.addProperty("firstClickProtectionMs", firstClickProtectionMs);
		obj.addProperty("terminalColor", terminalColor);
		obj.addProperty("guiScale", guiScale.name());
		obj.addProperty("clickAnimations", clickAnimations);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("blockWrongClicks")) blockWrongClicks = obj.get("blockWrongClicks").getAsBoolean();
		// New key first; falls back to the pre-rename key so existing configs aren't silently reset.
		if (obj.has("customTerminalGui")) customTerminalGui = obj.get("customTerminalGui").getAsBoolean();
		else if (obj.has("onlyShowCorrectClicks")) customTerminalGui = obj.get("onlyShowCorrectClicks").getAsBoolean();
		if (obj.has("middleClickRedirect")) middleClickRedirect = obj.get("middleClickRedirect").getAsBoolean();
		// Routed through the setter, not assigned directly — a config saved before the 300ms floor existed
		// could still have a lower value on disk, and that floor must win on load too.
		if (obj.has("firstClickProtectionMs")) setFirstClickProtectionMs(obj.get("firstClickProtectionMs").getAsLong());
		// New shared key first; falls back to the old per-type "panesColor" as a reasonable starting value
		// for anyone whose config predates the unification, rather than silently resetting to the default.
		if (obj.has("terminalColor")) terminalColor = obj.get("terminalColor").getAsInt();
		else if (obj.has("panesColor")) terminalColor = obj.get("panesColor").getAsInt();
		if (obj.has("guiScale")) { try { guiScale = GuiScale.valueOf(obj.get("guiScale").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("clickAnimations")) clickAnimations = obj.get("clickAnimations").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Solves dungeon terminals (Panes, Rubix, Numbers, Starts With, Select All): highlights the correct slot(s) to click and can block wrong clicks.";
	}
}
