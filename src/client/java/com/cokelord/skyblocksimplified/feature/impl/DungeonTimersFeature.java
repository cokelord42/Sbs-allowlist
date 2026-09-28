package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per user request: generalizes the old "Purple Pad Timer" (formerly a lone toggle inside Dungeon
 * Notifications, migrated here wholesale) into a dedicated F7-boss-fight timer panel with three more
 * timers alongside it:
 * <ul>
 *   <li>Storm purple pad — unchanged from its old home, same 27s-into-Storm / 5s-countdown constants.</li>
 *   <li>Terminals — a stopwatch counting up from the moment the Goldor/terminal phase starts
 *       ({@link DungeonState#getTerminalSection()} leaving NONE) until it ends (returning to NONE).</li>
 *   <li>Goldor's 3-second early-enter loop — the invincibility-item-popping tick during terminals. Per
 *       user request ("I'm still thinking about how to do this one... a repeating 3 second countdown
 *       [or] a notification"), both styles are implemented and selectable rather than guessing which one
 *       they'll want — {@link GoldorLoopStyle#COUNTDOWN} shows a persistent repeating decimal countdown,
 *       {@link GoldorLoopStyle#NOTIFICATION} instead flashes a title once per 3-second boundary.</li>
 *   <li>Necron lava-drop timer — per user report ("Necron drop timer just says 'LAVA!' there should be no
 *       notification for this, this should be a timer Until it drops the player instead"): a persistent
 *       countdown (like Maxor's crystal timer below) counting down the same real 6-second window, anchored
 *       off the "Necron" split (see {@link SplitsFeature#millisSinceSplit}), instead of the one-shot title+
 *       sound this used to fire the instant that window elapsed.</li>
 *   <li>Maxor's crystal timer — per user request ("the one that counts down until maxor starts moving, add
 *       this to dungeon timers"), ported from NoammAddons' confirmed {@code MaxorsCrystals.kt} "Spawn
 *       Timer": a real 34-tick (1.7s) countdown that starts the instant either of Maxor's own two real
 *       chat lines fires ("THAT BEAM! IT HURTS! IT HURTS!!" / "YOU TRICKED ME!") — the real window between
 *       a crystal EMP-ing Maxor and him waking back up and resuming movement.</li>
 * </ul>
 * All four state machines reset together the moment the player leaves the dungeon (or the terminal phase
 * ends, for the ones scoped to it) rather than lingering — same reasoning as the Splits/Secrets Counter
 * fixes earlier this round.
 *
 * <p>Per user question ("is it possible to make the dungeon timers lag proof"): every timer here anchors
 * off a real {@code System.currentTimeMillis()} timestamp and every displayed/compared duration is a plain
 * subtraction against the current wall-clock time, never a tick count — a client-side stutter can't skew
 * any of these; onTick() is just when the check happens to run, not the unit anything is measured in (same
 * reasoning documented on SplitsFeature, which this module was generalized out of). That covers CLIENT lag,
 * but per a later user report ("stuff like the early enter timer doesn't stop for [server lag] and
 * therefore gives the wrong timings") SERVER lag is a separate axis entirely: the five countdowns that
 * predict a fixed server-tick-scheduled future event (purple pad, Goldor Start, the early-enter loop,
 * Maxor's crystal window, Necron's lava drop) now also run their elapsed time through {@code
 * lagAdjustedElapsed()} (see its own doc comment, right before {@code purplePadLine()}), which scales by
 * the live {@link com.cokelord.skyblocksimplified.util.TpsMonitor} estimate — under real server lag the
 * server-side event itself runs behind schedule too, so these now slow down proportionally instead of
 * reaching 0 on a fixed real-time schedule that no longer matches reality.
 *
 * <p>Note: {@code DungeonState.getTerminalSection()} itself starts on WHICHEVER of two lines fires first —
 * Storm's own death line ("I should have known that I stood no chance.") OR Goldor's greeting ("Who dares
 * trespass into my domain?") — a deliberate redundancy for other consumers like {@code
 * InactiveWaypointsFeature} that just need "roughly in the terminal phase" and don't care which of the two
 * fired. This class's own Terminals timer no longer uses that section flip as its anchor at all — see the
 * doc comment further down, right before {@code terminalsLine()}/{@code goldorLoopLine()}, for why it now
 * reads elapsed time straight from {@code SplitsFeature} instead of keeping its own guessed anchor+offset.
 */
public class DungeonTimersFeature extends Feature implements MoveableWidget {
	public enum GoldorLoopStyle { COUNTDOWN, NOTIFICATION }

	private static final long PURPLE_PAD_DELAY_MILLIS = 27_000L;
	private static final long PURPLE_PAD_COUNTDOWN_MILLIS = 5_000L;
	// Per user report ("it showed 'Purple pad!' until the terminals phase started" / "should hide itself
	// after like a second of inactivity"): the static "Purple pad!" text used to show indefinitely once the
	// countdown hit zero, with nothing bounding how long it stuck around beyond whatever cleared
	// stormPhaseStartMillis externally (the Storm->Goldor F7Phase transition) — if that transition is even
	// slightly late or misses a beat, the line just sits there. Bounding its own display time directly
	// means this widget doesn't depend on an external phase flip to clear itself.
	private static final long PURPLE_PAD_LINGER_MILLIS = 1_000L;
	private static final long GOLDOR_LOOP_MILLIS = 3_000L;
	private static final long NECRON_WARNING_THRESHOLD_MILLIS = 6_000L;
	// Real bug found (per user report — the guessed fixed-delay approach below kept being wrong in both
	// directions across multiple rounds: 3s, then 5.7s, then 7.8s, then finally reported as "too late" —
	// each guess was a new attempt to time "how long after Storm's death/Goldor's greeting do terminals
	// actually become clickable" by ear, and none of them held up). Per user request ("check how splits
	// does it"): SplitsFeature's own "§6Terminals" split triggers directly off Goldor's greeting line with
	// ZERO added delay (see its ENTRY_REGEXES usage for floor 7) — that's the one number in this codebase
	// the user already trusts as accurate. Rather than maintain a second, independently-guessed anchor here,
	// terminalsLine()/goldorLoopLine() below now just ask SplitsFeature directly (via the same
	// millisSinceSplit("Maxor")/("Necron") pattern maxorCrystalLine()/necronLine() already use), so this
	// timer can never drift out of sync with Splits again — whatever Splits shows for "Terminals" elapsed
	// time is exactly what this panel shows too.
	// Per user report ("The maxor moving triggers when hes in a laser. It should trigger at 8.40 seconds on
	// the Maxor split."): the old trigger fired off Maxor's own laser/beam chat lines ("THAT BEAM! IT HURTS!
	// IT HURTS!!" / "YOU TRICKED ME!"), which is exactly the "when hes in a laser" behavior being reported
	// as wrong — replaced with a fixed elapsed-time trigger off the real Maxor split anchor instead.
	//
	// Real bug found (per a later user report — "The maxor timer starts WHEN he moves, it should count down
	// until the time i mentioned goes off on the split... it should count down from 5 when the split is at
	// 3.4 seconds"): a follow-up round made this fire a NEW, separate short countdown starting only once the
	// 8.4s trigger moment was already reached — showing nothing at all before then, the opposite of a real
	// "time remaining until Maxor moves" display. maxorCrystalLine/maxorCrystalActive below now compute the
	// remaining time directly off the live Maxor split elapsed time instead (MAXOR_MOVE_TRIGGER_MILLIS minus
	// elapsed), visible continuously from the moment the Maxor split itself starts down to 0 at the real
	// move moment — exactly the user's own worked example (8.4 - 3.4 = 5.0).
	private static final long MAXOR_MOVE_TRIGGER_MILLIS = 8_400L;

	// Per user request ("port Noamm's 'Goldor start' Tick Timers terminal countdown detection"): NoammAddons'
	// TickTimers.kt "Goldor Start" (p3) counts down 104 real server ticks (104 * 50ms = 5200ms) from Storm's
	// own death line ("I should have known that I stood no chance.") — the real, server-tick-accurate gap
	// before terminals/Goldor's greeting actually lands, as opposed to this class's own Terminals/Early-enter
	// timers above which only start once Goldor's greeting (or the "Terminals" split) has already fired.
	// DungeonState already tracks that exact chat line's arrival time (getStormDeathSeenAtMillis(), used for
	// TerminalSection tracking) — reused directly here instead of adding a second, redundant chat listener.
	private static final long GOLDOR_START_COUNTDOWN_MILLIS = 5_200L;

	// Per user request ("Fold the new storm pad timer into dungeon timers. Add its subtoggles (color code,
	// bold, everything else) into it aswell. Make sure that timers stack..."): the standalone Storm Pad Timer
	// module (a repeating 20-tick countdown for Storm's purple pad, ported from Odin's own TickTimers.kt —
	// see its own former class doc comment for the full mechanic) folded in here as just another candidate
	// line in activeLines(), so it automatically gets this panel's existing dynamic stacking for free instead
	// of being a second, uncoordinated floating widget. Distinct name from stormPadTimerEnabled above (this
	// panel's OWN pre-existing "27s delay + 5s countdown, fires once" purple-pad predictor) — these are two
	// genuinely different mechanics that happen to both be about the same purple pad, not a rename.
	private boolean stormPadTickTimerEnabled = true;
	// Per user request ("Remove the hex picker and storm pad tick: bold text and italic text subtoggles"):
	// the tick timer's own dedicated style/color settings are gone — its "Pad: " label is now a fixed cyan
	// and it follows this panel's shared Bold/Italic Text toggles like every other line.
	private static final int STORM_PAD_TICK_LABEL_COLOR = 0xFF55FFFF;
	private static final int TICK_COLOR_HIGH = 0xFF55FF55;
	private static final int TICK_COLOR_MID = 0xFFFFFF55;
	private static final int TICK_COLOR_LOW = 0xFFFF5555;
	// Same real line DungeonNotificationsFeature's own STORM_CRUSHED type already fires on — duplicated here
	// rather than shared, matching this codebase's established convention for small, self-contained per-
	// feature logic (see e.g. PetDisplayFeature/MelodyDisplayFeature's own duplicated classIcon()).
	private static final Pattern STORM_CRUSHED_PATTERN = Pattern.compile("^\\[BOSS] Storm: (Oof|Ouch, that hurt!?)");
	private int stormCrushCount = 0;

	private boolean stormPadTimerEnabled = true;
	private boolean terminalsTimerEnabled = true;
	private boolean goldorLoopEnabled = true;
	private GoldorLoopStyle goldorLoopStyle = GoldorLoopStyle.COUNTDOWN;
	private boolean necronWarningEnabled = true;
	private boolean maxorCrystalTimerEnabled = true;
	private boolean goldorStartTimerEnabled = true;

	// Per user request ("Terracotta respawn timers... detect when the user enters the floor 6 bossfight, and
	// detect when dirt blocks appear in the arena. When they do appear it needs to place a timer above them
	// counting down from 12" / "Terracotta spawn timer... count down from 12 when entering the floor 6
	// bossfight"). 12s is the user's own figure. A dead Terracotta's marker block is detected off real
	// server block updates (ClientLevelBlockUpdateMixin — both single and multi-block update packets funnel
	// through ClientLevel#setServerVerifiedBlockState, confirmed by decompiling ClientPacketListener).
	// Matches dirt (user-confirmed) and any potted plant — Odin's own TerracottaTimer.kt keys off a flower
	// pot appearing at the same spot, so both are accepted and deduplicated by position.
	private static final long TERRACOTTA_RESPAWN_MILLIS = 12_000L;
	// Per user: the spawn countdown runs one second longer than the respawn one.
	private static final long TERRACOTTA_SPAWN_MILLIS = 13_300L;
	private static final double TERRACOTTA_DEDUP_DIST_SQ = 1.5 * 1.5;
	private boolean terracottaRespawnTimersEnabled = true;
	private boolean terracottaSpawnTimerEnabled = true;
	// Per user request: Last Breath charge timer for Storm. Draw back at 29.60 on the Storm split, release at
	// 34.60 (5.00s countdown), with a box at the spot to shoot. Uses the raw split time (not lag-adjusted) so
	// it matches exactly what the Splits display reads, which is what the user timed it against.
	private static final long LAST_BREATH_DRAW_MILLIS = 29_600L;
	private static final long LAST_BREATH_RELEASE_MILLIS = 34_600L;
	private static final long LAST_BREATH_LINGER_MILLIS = 1_000L;
	private static final net.minecraft.world.phys.AABB LAST_BREATH_BOX = new net.minecraft.world.phys.AABB(new BlockPos(98, 183, 63));
	private boolean lastBreathTimerEnabled = true;
	private int lastBreathBoxColor = 0xFFFF55FF;
	private static boolean lastBreathBoxRegistered = false;
	private record TerracottaTimer(Vec3 labelPos, long startMillis) {}
	// Only ever touched on the client main thread (packet handling runs there via ensureRunningOnSameThread,
	// same as onTick/render), so a plain list is safe.
	private final List<TerracottaTimer> terracottaTimers = new ArrayList<>();
	private boolean wasInF6Boss = false;
	private Long f6BossEnteredMillis = null;
	// Per user request ("let users pick the type of text they want for the timers"): both off by default,
	// matching the plain unstyled look every line already has.
	private boolean boldText = false;
	private boolean italicText = false;

	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.25f, 1f);
	private final HudPosition position = defaultPosition.copy();

	// Storm purple pad state — ported as-is from DungeonNotificationsFeature's old PurplePadTimerWidget.
	private DungeonState.F7Phase lastF7Phase = DungeonState.F7Phase.UNKNOWN;
	private Long stormPhaseStartMillis = null;

	// Terminals / Goldor loop state. Real elapsed time comes straight from SplitsFeature.millisSinceSplit
	// ("Terminals") now (see the class-level doc comment above) — lastTerminalSection is only kept around to
	// detect the moment the terminal phase ends so goldorBoundariesFired can reset for next time.
	private DungeonState.TerminalSection lastTerminalSection = DungeonState.TerminalSection.NONE;
	private long goldorBoundariesFired = 0L;


	private static DungeonTimersFeature instance;

	// Per user request ("Storm lightning sync... count down the storm lightning timer that displays as a
	// title. It should also hide the original title."): the real countdown number, read off the vanilla
	// title packet itself (see HudTitleMixin) rather than guessed — Hypixel's own value sometimes starts at
	// 5 instead of 6, so this can't be a fixed local countdown.
	private boolean stormLightningSyncEnabled = false;
	private Integer stormLightningNumber = null;
	private long stormLightningReceivedMillis = 0L;
	private static final long STORM_LIGHTNING_STALE_MILLIS = 1_500L;

	// HudElementRegistry.addLast throws IllegalArgumentException on a duplicate id (confirmed via
	// decompiling Fabric's own HudElementRegistryImpl) — onEnable() used to call it unconditionally, so
	// toggling this feature off then back on crashed the render pipeline the moment it re-enabled. Same
	// guarded-registration pattern already used correctly elsewhere in this codebase (see
	// PlayerDisplayFeature/ChatCopyFeature): register exactly once, ever, and let the render callback's own
	// isVisible() check make it a no-op while disabled.
	private static boolean listenersRegistered = false;

	public DungeonTimersFeature() {
		super("dungeon_timers", "Dungeon Timers", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	public static DungeonTimersFeature getInstance() { return instance; }

	public boolean isStormLightningSyncEnabled() { return stormLightningSyncEnabled; }
	public void setStormLightningSyncEnabled(boolean value) { stormLightningSyncEnabled = value; }

	/** Called from HudTitleMixin the moment a real Storm lightning countdown title arrives. */
	public void onStormLightningTitle(int number) {
		stormLightningNumber = number;
		stormLightningReceivedMillis = System.currentTimeMillis();
	}

	private String stormLightningLine() {
		if (!stormLightningSyncEnabled || stormLightningNumber == null) return null;
		if (System.currentTimeMillis() - stormLightningReceivedMillis > STORM_LIGHTNING_STALE_MILLIS) return null;
		return "§bLightning: §f" + stormLightningNumber;
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (instance != null && instance.isEnabled() && DungeonState.isInBoss() && DungeonState.isFloor(7)
				&& STORM_CRUSHED_PATTERN.matcher(message.getString()).find()) {
				instance.stormCrushCount++;
			}
			return true;
		});
		if (!lastBreathBoxRegistered) {
			lastBreathBoxRegistered = true;
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.addRenderCallback(DungeonTimersFeature::renderLastBreathBox);
		}
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeon_timers"), (graphics, tracker) -> {
			if (instance == null || !instance.isEnabled() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
				|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
			instance.drawTerracottaTimers(graphics);
			if (!instance.isVisible()) return;
			Minecraft mc = Minecraft.getInstance();
			int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
			int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
			instance.render(graphics, x, y, instance.position.scale);
		});
	}

	@Override
	public void onTick(Minecraft client) {
		if (!DungeonState.isInDungeon()) {
			if (lastF7Phase != DungeonState.F7Phase.UNKNOWN || stormPhaseStartMillis != null || lastTerminalSection != DungeonState.TerminalSection.NONE) {
				lastF7Phase = DungeonState.F7Phase.UNKNOWN;
				stormPhaseStartMillis = null;
				lastTerminalSection = DungeonState.TerminalSection.NONE;
				goldorBoundariesFired = 0L;
				stormCrushCount = 0;
			}
			resetTerracotta();
			return;
		}

		boolean inF6Boss = DungeonState.isInBoss() && DungeonState.isFloor(6);
		if (inF6Boss != wasInF6Boss) {
			wasInF6Boss = inF6Boss;
			if (inF6Boss) {
				f6BossEnteredMillis = System.currentTimeMillis();
			} else {
				resetTerracotta();
			}
		}
		// Per user: the respawn timers end once the Giants split starts (Terracottas no longer respawn).
		if (!terracottaTimers.isEmpty() && SplitsFeature.millisSinceSplit("Giants") != null) terracottaTimers.clear();
		if (!terracottaTimers.isEmpty()) {
			long now = System.currentTimeMillis();
			terracottaTimers.removeIf(t -> lagAdjustedElapsed(now - t.startMillis()) >= TERRACOTTA_RESPAWN_MILLIS);
		}
		rebuildTerracottaLabels();

		DungeonState.F7Phase phase = DungeonState.getF7Phase();
		if (phase != lastF7Phase) {
			stormPhaseStartMillis = phase == DungeonState.F7Phase.P2 ? System.currentTimeMillis() : null;
			if (phase == DungeonState.F7Phase.P2) stormCrushCount = 0;
			lastF7Phase = phase;
		}

		DungeonState.TerminalSection section = DungeonState.getTerminalSection();
		if (section != lastTerminalSection) {
			if (section == DungeonState.TerminalSection.NONE) goldorBoundariesFired = 0L;
			lastTerminalSection = section;
		}

		Long sinceTerminals = SplitsFeature.millisSinceSplit("Terminals");
		if (goldorLoopEnabled && goldorLoopStyle == GoldorLoopStyle.NOTIFICATION && sinceTerminals != null && !pastTerminalsPhase()) {
			long boundariesPassed = lagAdjustedElapsed(sinceTerminals) / GOLDOR_LOOP_MILLIS;
			if (boundariesPassed > goldorBoundariesFired) {
				goldorBoundariesFired = boundariesPassed;
				fireTitle("§bEarly enter!", 0xFF55FFFF, 1.4f);
			}
		}

	}

	private void fireTitle(String text, int color, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.gui.hud.setTitle(Component.literal(text)
			.withStyle(Style.EMPTY.withColor(color).withBold(boldText).withItalic(italicText)));
		mc.player.playSound(SoundEvents.PLAYER_LEVELUP, 1f, pitch);
	}

	// Real bug found (per user report — "Most of the stuff like timers and stuff misaligns when the server
	// lags... stuff like the early enter timer doesn't stop for this and therefore gives the wrong
	// timings"): every countdown below predicts a FIXED SERVER-TICK-SCHEDULED future event (the early-enter
	// loop, Goldor Start, Maxor's crystal window, Necron's lava drop, the purple pad delay) off raw
	// wall-clock elapsed time since a chat-line anchor. That's correct under normal 20-TPS play, but during
	// real server lag the underlying server-side event itself runs behind schedule too — a countdown that
	// just keeps counting real seconds reaches 0 (or fires a boundary) before the real event has actually
	// happened, exactly the "misaligns"/"wrong timings" report. ServerClock converts the real interval into
	// the server ticks that actually ran during it (from ClientboundSetTimePacket game times), so under lag
	// these countdowns pause for exactly the lost ticks instead of scaling by a TPS guess. Deliberately
	// NOT applied to terminalsLine() (a genuine up-counting stopwatch of real elapsed time, not a
	// prediction) or stormLightningLine() (driven directly by a live title packet, not a local guess).
	private static long lagAdjustedElapsed(long realElapsedMillis) {
		// Server tick clock (see ServerClock): counts the ticks the server actually processed, so a timer
		// pauses exactly for the ticks lost to lag instead of scaling everything by the current TPS guess.
		return com.cokelord.skyblocksimplified.util.ServerClock.elapsedMillis(realElapsedMillis);
	}

	private void resetTerracotta() {
		wasInF6Boss = false;
		f6BossEnteredMillis = null;
		terracottaTimers.clear();
		terracottaLabels = List.of();
	}

	/** Called from ClientLevelBlockUpdateMixin for every server-sent block change. Cheapest checks first —
	 *  this fires for every block update anywhere, so the block-type test runs before any state lookup. */
	public static void onServerBlockChange(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos, BlockState newState) {
		DungeonTimersFeature f = instance;
		if (f == null || !f.terracottaRespawnTimersEnabled || !f.wasInF6Boss || !f.isEnabled() || !isTerracottaMarker(newState)) return;
		if (isTerracottaMarker(level.getBlockState(pos))) return;
		if (SplitsFeature.millisSinceSplit("Giants") != null) return;
		Vec3 labelPos = new Vec3(pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5);
		for (TerracottaTimer t : f.terracottaTimers) {
			double dx = t.labelPos().x - labelPos.x, dz = t.labelPos().z - labelPos.z;
			if (dx * dx + dz * dz <= TERRACOTTA_DEDUP_DIST_SQ && Math.abs(t.labelPos().y - labelPos.y) <= 2) return;
		}
		f.terracottaTimers.add(new TerracottaTimer(labelPos, System.currentTimeMillis()));
	}

	private static boolean isTerracottaMarker(BlockState state) {
		return state.is(Blocks.DIRT) || state.getBlock() instanceof FlowerPotBlock;
	}

	/** Per user spec: whole seconds 12-8 green, 7-4 yellow, 3-0 red (by the integer part shown). */
	private static String countdownColor(float remainingSeconds) {
		if (remainingSeconds >= 8f) return "§a";
		if (remainingSeconds >= 4f) return "§e";
		return "§c";
	}

	// Per user report ("Terras still lag a little when a lot die at the same time"): labels are built once per
	// client tick (server-clock math + formatting per timer) and each frame only projects and draws them.
	// The countdown shows one decimal, so per-tick (50ms) text updates look identical.
	private record TerracottaLabel(Vec3 pos, String text) {}
	private List<TerracottaLabel> terracottaLabels = List.of();

	private void rebuildTerracottaLabels() {
		if (terracottaTimers.isEmpty()) { terracottaLabels = List.of(); return; }
		long now = System.currentTimeMillis();
		String style = stylePrefix();
		List<TerracottaLabel> labels = new ArrayList<>(terracottaTimers.size());
		for (TerracottaTimer t : terracottaTimers) {
			float remaining = Math.max(0f, (TERRACOTTA_RESPAWN_MILLIS - lagAdjustedElapsed(now - t.startMillis())) / 1000f);
			labels.add(new TerracottaLabel(t.labelPos(), countdownColor(remaining) + style + String.format(Locale.ROOT, "%.1f", remaining)));
		}
		terracottaLabels = labels;
	}

	private void drawTerracottaTimers(GuiGraphicsExtractor graphics) {
		for (TerracottaLabel label : terracottaLabels) {
			com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.drawTextNoOcclusion(graphics, label.text(), label.pos(), 0xFFFFFFFF);
		}
	}

	private float terracottaSpawnRemaining() {
		if (!terracottaSpawnTimerEnabled || f6BossEnteredMillis == null) return -1f;
		long elapsed = lagAdjustedElapsed(com.cokelord.skyblocksimplified.gui.HudEditScreen.previewNowMillis() - f6BossEnteredMillis);
		return elapsed < TERRACOTTA_SPAWN_MILLIS ? (TERRACOTTA_SPAWN_MILLIS - elapsed) / 1000f : -1f;
	}

	private String terracottaSpawnLine() {
		float remaining = terracottaSpawnRemaining();
		if (remaining < 0f) return null;
		return "§6Terracotta: " + countdownColor(remaining) + String.format(Locale.ROOT, "%.1f", remaining) + "s";
	}

	private String purplePadLine() {
		if (!stormPadTimerEnabled || stormPhaseStartMillis == null) return null;
		long elapsed = lagAdjustedElapsed(com.cokelord.skyblocksimplified.gui.HudEditScreen.previewNowMillis() - stormPhaseStartMillis) - PURPLE_PAD_DELAY_MILLIS;
		if (elapsed < 0) return null;
		if (elapsed > PURPLE_PAD_COUNTDOWN_MILLIS + PURPLE_PAD_LINGER_MILLIS) return null;
		float remaining = Math.max(0f, (PURPLE_PAD_COUNTDOWN_MILLIS - elapsed) / 1000f);
		return remaining > 0f ? "§dPurple pad: " + String.format(Locale.ROOT, "%.1f", remaining) + "s" : "§d§lPurple pad!";
	}

	// Real bug found (per user report — "Terminals timer and Early Enter timer don't turn off when the split
	// becomes Goldor (should stop once past Terminals phase)"): both used to gate purely on
	// millisSinceSplit("Terminals") != null, which fires once terminals start and — unlike a fresh
	// per-terminal-attempt timestamp — never goes null again for the rest of the run, so both kept counting
	// straight through Goldor dying and into the "§7Goldor" split (fired on "The Core entrance is opening!",
	// SplitsFeature's own real post-terminals signal) and beyond. Both timers only make sense DURING
	// terminals, so they now also hide once that later split has fired too.
	private boolean pastTerminalsPhase() {
		return SplitsFeature.millisSinceSplit("Goldor") != null;
	}

	private String terminalsLine() {
		if (!terminalsTimerEnabled || pastTerminalsPhase()) return null;
		Long sinceTerminals = SplitsFeature.millisSinceSplit("Terminals");
		if (sinceTerminals == null) return null;
		long elapsedSeconds = sinceTerminals / 1000L;
		return String.format(Locale.ROOT, "§6Terminals: %d:%02d", elapsedSeconds / 60, elapsedSeconds % 60);
	}

	private String goldorLoopLine() {
		if (!goldorLoopEnabled || goldorLoopStyle != GoldorLoopStyle.COUNTDOWN || pastTerminalsPhase()) return null;
		Long sinceTerminals = SplitsFeature.millisSinceSplit("Terminals");
		if (sinceTerminals == null) return null;
		long adjusted = lagAdjustedElapsed(sinceTerminals);
		float remaining = GOLDOR_LOOP_MILLIS / 1000f - (adjusted % GOLDOR_LOOP_MILLIS) / 1000f;
		return "§bEarly enter: " + String.format(Locale.ROOT, "%.1f", remaining) + "s";
	}

	/** Real, continuously-live "time remaining until Maxor moves" — computed straight off the live Maxor
	 *  split elapsed time (see MAXOR_MOVE_TRIGGER_MILLIS's own doc comment for why this replaced the earlier
	 *  anchor-based one-shot countdown). Visible from the moment the Maxor split itself starts, counting down
	 *  to 0 at the real 8.4s move moment, then disappearing. */
	private String maxorCrystalLine() {
		if (!maxorCrystalTimerEnabled) return null;
		Long sinceMaxor = SplitsFeature.millisSinceSplit("Maxor");
		if (sinceMaxor == null) return null;
		long adjusted = lagAdjustedElapsed(sinceMaxor);
		if (adjusted >= MAXOR_MOVE_TRIGGER_MILLIS) return null;
		float remaining = (MAXOR_MOVE_TRIGGER_MILLIS - adjusted) / 1000f;
		return "§dMaxor moves in: " + String.format(Locale.ROOT, "%.1f", remaining) + "s";
	}

	/** Per user report — see this class's own doc comment's "Necron lava-drop timer" bullet: a persistent
	 *  countdown to the real lava drop instead of the old one-shot "LAVA!" title, same shape as {@link
	 *  #maxorCrystalLine} (counts down, then disappears once the window elapses — nothing to fire/hide a
	 *  flag for, unlike the old notification). */
	private String necronLine() {
		if (!necronWarningEnabled) return null;
		Long sinceNecron = SplitsFeature.millisSinceSplit("Necron");
		if (sinceNecron == null) return null;
		long adjusted = lagAdjustedElapsed(sinceNecron);
		if (adjusted >= NECRON_WARNING_THRESHOLD_MILLIS) return null;
		float remaining = (NECRON_WARNING_THRESHOLD_MILLIS - adjusted) / 1000f;
		return "§4Drop in: " + String.format(Locale.ROOT, "%.1f", remaining) + "s";
	}

	/** NoammAddons-ported "Goldor Start" tick countdown — see GOLDOR_START_COUNTDOWN_MILLIS's own doc comment.
	 *  Color-coded green/yellow/red by fraction remaining, same convention as the source's formatTimer().
	 *
	 *  <p>Per user report ("The timer still isnt right for some reason, but since its the same as noamm its
	 *  probably fine. Just hide the timer when the terminals phase actually starts if its still active."):
	 *  the fixed 104-tick window is a guess at how long the real gap usually is, not a guarantee — terminals
	 *  can become clickable slightly before or after that fixed countdown reaches 0 on any given run. Rather
	 *  than trying to guess the exact real duration yet again, this now defers to whichever real signal
	 *  arrives FIRST: once terminals have really started, the countdown hides itself immediately even if its
	 *  own fixed timer hasn't reached 0 yet.
	 *
	 *  <p>Real bug found (per user report — "The countdown till terminals start doesnt show at all, it
	 *  should only hide if the terminals actually start before it ends"): the "real signal" this used to
	 *  gate on was {@link DungeonState#getTerminalSection()} leaving NONE — but that flips NONE-&gt;S1 on
	 *  the exact SAME chat line (Storm's own death line) that sets {@code stormDeathSeenAtMillis} in the
	 *  first place, in the same {@code onChatLine} call (see {@code DungeonState}'s own line-handling code).
	 *  So the very instant this countdown's anchor timestamp gets set, its own visibility gate was ALREADY
	 *  false — the countdown could never show at all, not just hide early. Swapped to the same real
	 *  "terminals actually started" signal {@link #terminalsLine()}/{@link #goldorLoopLine()} already trust
	 *  ({@code SplitsFeature.millisSinceSplit("Terminals")}, which fires off Goldor's own greeting line —
	 *  genuinely later than Storm's death line, not the same instant). */
	private String goldorStartLine() {
		if (!goldorStartTimerEnabled) return null;
		if (SplitsFeature.millisSinceSplit("Terminals") != null) return null;
		Long seenAt = DungeonState.getStormDeathSeenAtMillis();
		if (seenAt == null) return null;
		long elapsed = lagAdjustedElapsed(com.cokelord.skyblocksimplified.gui.HudEditScreen.previewNowMillis() - seenAt);
		if (elapsed < 0 || elapsed >= GOLDOR_START_COUNTDOWN_MILLIS) return null;
		float remaining = (GOLDOR_START_COUNTDOWN_MILLIS - elapsed) / 1000f;
		float fraction = remaining / (GOLDOR_START_COUNTDOWN_MILLIS / 1000f);
		String color = fraction >= 0.66f ? "§a" : fraction >= 0.33f ? "§6" : "§c";
		return "§7Goldor Start: " + color + String.format(Locale.ROOT, "%.1f", remaining) + "s";
	}

	private boolean goldorStartActive() {
		if (!goldorStartTimerEnabled) return false;
		if (SplitsFeature.millisSinceSplit("Terminals") != null) return false;
		Long seenAt = DungeonState.getStormDeathSeenAtMillis();
		if (seenAt == null) return false;
		long elapsed = lagAdjustedElapsed(com.cokelord.skyblocksimplified.gui.HudEditScreen.previewNowMillis() - seenAt);
		return elapsed >= 0 && elapsed < GOLDOR_START_COUNTDOWN_MILLIS;
	}

	// Per user request ("Fold the new storm pad timer into dungeon timers... Make sure that timers stack when
	// multiple are on screen, so if another timer pops up it goes to the next line... until the other timer
	// is gone and then it goes to first line"): every other line here is already a single plain string with
	// its own colors baked in as legacy "§" codes — the tick timer needs a genuinely separate custom RGB
	// color for its "Pad: " label vs. its number (and the number's own color can additionally be a live
	// threshold color, not a fixed one at all), which a single legacy-code string can't express. Both prefix
	// and suffix already carry their own resolved style-code prefix (bold/italic) baked in at construction
	// time, so drawPanel/renderLines below never need to know which "kind" of source line produced them.
	private record TimerLine(String prefixText, int prefixColor, String suffixText, int suffixColor) {}

	/** Wraps one of this panel's existing single-string legacy-coded lines (all colors embedded as "§"
	 *  codes) into a {@link TimerLine} with an empty, invisible prefix — this panel's own shared boldText/
	 *  italicText stylePrefix is baked into the (only) suffix segment, same as every one of these lines
	 *  already had applied to it before this fold-in. */
	private TimerLine plainLine(String legacyText) {
		return new TimerLine("", 0xFFFFFFFF, stylePrefix() + legacyText, 0xFFFFFFFF);
	}

	private String stylePrefix() {
		return boldText ? (italicText ? "§l§o" : "§l") : (italicText ? "§o" : "");
	}

	private List<TimerLine> activeLines() {
		List<TimerLine> lines = new ArrayList<>(8);
		String pad = purplePadLine(); if (pad != null) lines.add(plainLine(pad));
		TimerLine tick = stormPadTickLine(); if (tick != null) lines.add(tick);
		String term = terminalsLine(); if (term != null) lines.add(plainLine(term));
		String goldorStart = goldorStartLine(); if (goldorStart != null) lines.add(plainLine(goldorStart));
		String goldor = goldorLoopLine(); if (goldor != null) lines.add(plainLine(goldor));
		String necron = necronLine(); if (necron != null) lines.add(plainLine(necron));
		String relic = relicLine(); if (relic != null) lines.add(plainLine(relic));
		String maxor = maxorCrystalLine(); if (maxor != null) lines.add(plainLine(maxor));
		String lightning = stormLightningLine(); if (lightning != null) lines.add(plainLine(lightning));
		String terracotta = terracottaSpawnLine(); if (terracotta != null) lines.add(plainLine(terracotta));
		Long sinceStorm = lastBreathElapsed();
		if (sinceStorm != null) {
			boolean released = sinceStorm >= LAST_BREATH_RELEASE_MILLIS;
			float remaining = Math.max(0f, (LAST_BREATH_RELEASE_MILLIS - sinceStorm) / 1000f);
			lines.add(plainLine(released ? "§a§lRelease!" : "§eDraw back..."));
			lines.add(plainLine((released ? "§a" : "§f") + String.format(Locale.ROOT, "%.2f", remaining) + "s"));
		}
		return lines;
	}

	/** {@link #stormPadTickLine}'s own three-bucket thresholds, per the original Storm Pad Timer module's
	 *  exact user spec ("15-20 is lime green, 5-15 is yellow, 0-5 is red") — inclusive on each bucket's
	 *  lower bound, same convention this codebase's other threshold-colored readouts already use (e.g.
	 *  DungeonMapFeature's own cryptsColor). Per user request ("Force the color coding for the storm pad
	 *  tick timer"): this is now the number's only possible color — the old opt-out toggle (which fell back
	 *  to the flat Text Color instead) is gone. */
	private static int tickColor(int value) {
		if (value >= 15) return TICK_COLOR_HIGH;
		if (value >= 5) return TICK_COLOR_MID;
		return TICK_COLOR_LOW;
	}

	/** Real, repeating 20-tick countdown for Storm's purple pad — ported as-is from the old standalone Storm
	 *  Pad Timer module (see its own former class doc comment for the full "why a repeating sawtooth"
	 *  reasoning and the Odin TickTimers.kt cross-check). Reuses stormPhaseStartMillis/lastF7Phase directly —
	 *  the same "when did Storm's phase start" anchor this panel's own pre-existing Purple Pad predictor
	 *  above already tracks, no separate state needed for that half of it. */
	private int computeStormPadTickValue() {
		long adjusted = lagAdjustedElapsed(com.cokelord.skyblocksimplified.gui.HudEditScreen.previewNowMillis() - stormPhaseStartMillis);
		long msIntoSecond = adjusted % 1000L;
		int value = 20 - Math.round(msIntoSecond / 50f);
		return Math.max(0, Math.min(20, value));
	}

	private boolean stormPadTickActive() {
		return stormPadTickTimerEnabled && stormPhaseStartMillis != null && stormCrushCount < 2
			&& DungeonState.getStormDeathSeenAtMillis() == null;
	}

	private TimerLine stormPadTickLine() {
		if (!stormPadTickActive()) return null;
		int value = computeStormPadTickValue();
		String style = stylePrefix();
		return new TimerLine(style + "Pad: ", STORM_PAD_TICK_LABEL_COLOR, style + value, tickColor(value));
	}

	// Cheap yes/no mirrors of purplePadLine()/terminalsLine()/goldorLoopLine() with no String.format or
	// allocation — isVisible()/hasVisibleContent() only need to know WHETHER something's showing, and both
	// get checked every HUD frame (60+/sec), not just once per render() call. Keeping these in exact sync
	// with the real line-builders above is the tradeoff for skipping the actual formatting work there.
	private boolean purplePadActive() {
		if (!stormPadTimerEnabled || stormPhaseStartMillis == null) return false;
		long elapsed = lagAdjustedElapsed(com.cokelord.skyblocksimplified.gui.HudEditScreen.previewNowMillis() - stormPhaseStartMillis) - PURPLE_PAD_DELAY_MILLIS;
		return elapsed >= 0 && elapsed <= PURPLE_PAD_COUNTDOWN_MILLIS + PURPLE_PAD_LINGER_MILLIS;
	}

	private boolean terminalsActive() {
		if (!terminalsTimerEnabled || pastTerminalsPhase()) return false;
		return SplitsFeature.millisSinceSplit("Terminals") != null;
	}

	private boolean goldorLoopActive() {
		if (!goldorLoopEnabled || goldorLoopStyle != GoldorLoopStyle.COUNTDOWN || pastTerminalsPhase()) return false;
		return SplitsFeature.millisSinceSplit("Terminals") != null;
	}

	private boolean maxorCrystalActive() {
		if (!maxorCrystalTimerEnabled) return false;
		Long sinceMaxor = SplitsFeature.millisSinceSplit("Maxor");
		return sinceMaxor != null && lagAdjustedElapsed(sinceMaxor) < MAXOR_MOVE_TRIGGER_MILLIS;
	}

	private boolean necronActive() {
		if (!necronWarningEnabled) return false;
		Long sinceNecron = SplitsFeature.millisSinceSplit("Necron");
		return sinceNecron != null && lagAdjustedElapsed(sinceNecron) < NECRON_WARNING_THRESHOLD_MILLIS;
	}

	/** Per user request: M7 relics spawn 2.1s after the "Cleared" split (Necron dead). The toggle is linked to
	 *  the Relic Utility module (see RelicUtilityFeature#isLinkedEnabled). */
	private String relicLine() {
		if (!isRelicTimerEnabled()) return null;
		long remaining = com.cokelord.skyblocksimplified.dungeon.m7.M7Relics.spawnRemainingMillis();
		if (remaining < 0) return null;
		return "§3Relic: " + countdownColor(remaining / 1000f) + String.format(Locale.ROOT, "%.2f", remaining / 1000f) + "s";
	}

	public boolean isRelicTimerEnabled() { return com.cokelord.skyblocksimplified.feature.impl.m7.RelicUtilityFeature.isLinkedEnabled(); }
	public void setRelicTimerEnabled(boolean value) { com.cokelord.skyblocksimplified.feature.impl.m7.RelicUtilityFeature.setLinkedEnabled(value); }

	private boolean hasAnyLine() {
		return relicLine() != null || purplePadActive() || stormPadTickActive() || terminalsActive() || goldorStartActive() || goldorLoopActive()
			|| necronActive() || maxorCrystalActive() || stormLightningLine() != null || terracottaSpawnRemaining() >= 0f
			|| lastBreathElapsed() != null;
	}

	/** Server-time elapsed since the Storm split while the Last Breath window (29.60s draw -> 34.60s release,
	 *  +1s linger) is live, else null. Runs on the server tick clock (per user: must slow during lag). */
	private Long lastBreathElapsed() {
		if (!lastBreathTimerEnabled) return null;
		Long sinceStorm = SplitsFeature.millisSinceSplit("Storm");
		if (sinceStorm == null) return null;
		long elapsed = lagAdjustedElapsed(sinceStorm);
		if (elapsed < LAST_BREATH_DRAW_MILLIS || elapsed >= LAST_BREATH_RELEASE_MILLIS + LAST_BREATH_LINGER_MILLIS) return null;
		return elapsed;
	}

	private static void renderLastBreathBox() {
		DungeonTimersFeature f = instance;
		if (f == null || !f.isEnabled() || f.lastBreathElapsed() == null) return;
		int color = f.lastBreathBoxColor;
		com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawFilledBoxThroughWalls(LAST_BREATH_BOX, (0x50 << 24) | (color & 0xFFFFFF));
		com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawWireBoxThroughWalls(LAST_BREATH_BOX, color | 0xFF000000, 2f);
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() { return isEnabled() && DungeonState.isInDungeon() && hasAnyLine(); }

	@Override
	public boolean isRelevantToCurrentIsland() { return DungeonState.isInDungeon(); }

	@Override
	public boolean hasVisibleContent() { return hasAnyLine(); }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		List<TimerLine> lines = activeLines();
		if (lines.isEmpty()) lines = List.of(plainLine("§6§lDungeon Timers"), plainLine("§7(none active)"));
		return renderLines(graphics, x, y, scale, lines);
	}

	private Size renderLines(GuiGraphicsExtractor graphics, int x, int y, float scale, List<TimerLine> lines) {
		Font font = Minecraft.getInstance().font;

		int maxWidth = 0;
		for (TimerLine line : lines) maxWidth = Math.max(maxWidth, font.width(line.prefixText()) + font.width(line.suffixText()));
		int panelWidth = maxWidth + 12;
		int panelHeight = lines.size() * (font.lineHeight + 2) + 8;

		if (Math.abs(scale - 1f) < 0.01f) {
			drawPanel(graphics, font, x, y, lines, panelWidth, panelHeight);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			drawPanel(graphics, font, x, y, lines, panelWidth, panelHeight);
			graphics.pose().popMatrix();
		}
		return new Size(Math.round(panelWidth * scale), Math.round(panelHeight * scale));
	}

	// Per user request ("why does the dungeon timer have a background? Remove it.") — plain text now, no
	// panel fill, matching how the Invincibility Timer/Secrets Counter already render.
	private void drawPanel(GuiGraphicsExtractor graphics, Font font, int x, int y, List<TimerLine> lines, int panelWidth, int panelHeight) {
		int lineY = y + 4;
		for (TimerLine line : lines) {
			int prefixWidth = font.width(line.prefixText());
			if (!line.prefixText().isEmpty()) graphics.text(font, line.prefixText(), x + 6, lineY, line.prefixColor());
			graphics.text(font, line.suffixText(), x + 6 + prefixWidth, lineY, line.suffixColor());
			lineY += font.lineHeight + 2;
		}
	}

	public boolean isStormPadTimerEnabled() { return stormPadTimerEnabled; }
	public void setStormPadTimerEnabled(boolean value) { stormPadTimerEnabled = value; }
	public boolean isTerminalsTimerEnabled() { return terminalsTimerEnabled; }
	public void setTerminalsTimerEnabled(boolean value) { terminalsTimerEnabled = value; }
	public boolean isGoldorLoopEnabled() { return goldorLoopEnabled; }
	public void setGoldorLoopEnabled(boolean value) { goldorLoopEnabled = value; }
	public GoldorLoopStyle getGoldorLoopStyle() { return goldorLoopStyle; }
	public void cycleGoldorLoopStyle() {
		GoldorLoopStyle[] values = GoldorLoopStyle.values();
		goldorLoopStyle = values[(goldorLoopStyle.ordinal() + 1) % values.length];
	}
	public boolean isNecronWarningEnabled() { return necronWarningEnabled; }
	public void setNecronWarningEnabled(boolean value) { necronWarningEnabled = value; }
	public boolean isMaxorCrystalTimerEnabled() { return maxorCrystalTimerEnabled; }
	public void setMaxorCrystalTimerEnabled(boolean value) { maxorCrystalTimerEnabled = value; }
	public boolean isGoldorStartTimerEnabled() { return goldorStartTimerEnabled; }
	public void setGoldorStartTimerEnabled(boolean value) { goldorStartTimerEnabled = value; }
	public boolean isBoldText() { return boldText; }
	public void setBoldText(boolean value) { boldText = value; }
	public boolean isItalicText() { return italicText; }
	public void setItalicText(boolean value) { italicText = value; }
	public boolean isStormPadTickTimerEnabled() { return stormPadTickTimerEnabled; }
	public void setStormPadTickTimerEnabled(boolean value) { stormPadTickTimerEnabled = value; }
	public boolean isTerracottaRespawnTimersEnabled() { return terracottaRespawnTimersEnabled; }
	public void setTerracottaRespawnTimersEnabled(boolean value) {
		terracottaRespawnTimersEnabled = value;
		if (!value) terracottaTimers.clear();
	}
	public boolean isTerracottaSpawnTimerEnabled() { return terracottaSpawnTimerEnabled; }
	public boolean isLastBreathTimerEnabled() { return lastBreathTimerEnabled; }
	public void setLastBreathTimerEnabled(boolean value) { lastBreathTimerEnabled = value; }
	public int getLastBreathBoxColor() { return lastBreathBoxColor; }
	public void setLastBreathBoxColor(int value) { lastBreathBoxColor = value | 0xFF000000; }
	public void setTerracottaSpawnTimerEnabled(boolean value) { terracottaSpawnTimerEnabled = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("stormPadTimerEnabled", stormPadTimerEnabled);
		obj.addProperty("terminalsTimerEnabled", terminalsTimerEnabled);
		obj.addProperty("goldorLoopEnabled", goldorLoopEnabled);
		obj.addProperty("goldorLoopStyle", goldorLoopStyle.name());
		obj.addProperty("necronWarningEnabled", necronWarningEnabled);
		obj.addProperty("maxorCrystalTimerEnabled", maxorCrystalTimerEnabled);
		obj.addProperty("goldorStartTimerEnabled", goldorStartTimerEnabled);
		obj.addProperty("boldText", boldText);
		obj.addProperty("italicText", italicText);
		obj.addProperty("stormLightningSyncEnabled", stormLightningSyncEnabled);
		obj.addProperty("stormPadTickTimerEnabled", stormPadTickTimerEnabled);
		obj.addProperty("terracottaRespawnTimersEnabled", terracottaRespawnTimersEnabled);
		obj.addProperty("terracottaSpawnTimerEnabled", terracottaSpawnTimerEnabled);
		obj.addProperty("lastBreathTimerEnabled", lastBreathTimerEnabled);
		obj.addProperty("lastBreathBoxColor", lastBreathBoxColor);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("stormPadTimerEnabled")) stormPadTimerEnabled = obj.get("stormPadTimerEnabled").getAsBoolean();
		if (obj.has("terminalsTimerEnabled")) terminalsTimerEnabled = obj.get("terminalsTimerEnabled").getAsBoolean();
		if (obj.has("goldorLoopEnabled")) goldorLoopEnabled = obj.get("goldorLoopEnabled").getAsBoolean();
		if (obj.has("goldorLoopStyle")) {
			try { goldorLoopStyle = GoldorLoopStyle.valueOf(obj.get("goldorLoopStyle").getAsString()); } catch (IllegalArgumentException ignored) {}
		}
		if (obj.has("necronWarningEnabled")) necronWarningEnabled = obj.get("necronWarningEnabled").getAsBoolean();
		if (obj.has("maxorCrystalTimerEnabled")) maxorCrystalTimerEnabled = obj.get("maxorCrystalTimerEnabled").getAsBoolean();
		if (obj.has("goldorStartTimerEnabled")) goldorStartTimerEnabled = obj.get("goldorStartTimerEnabled").getAsBoolean();
		if (obj.has("boldText")) boldText = obj.get("boldText").getAsBoolean();
		if (obj.has("italicText")) italicText = obj.get("italicText").getAsBoolean();
		if (obj.has("stormLightningSyncEnabled")) stormLightningSyncEnabled = obj.get("stormLightningSyncEnabled").getAsBoolean();
		if (obj.has("stormPadTickTimerEnabled")) stormPadTickTimerEnabled = obj.get("stormPadTickTimerEnabled").getAsBoolean();
		if (obj.has("terracottaRespawnTimersEnabled")) terracottaRespawnTimersEnabled = obj.get("terracottaRespawnTimersEnabled").getAsBoolean();
		if (obj.has("terracottaSpawnTimerEnabled")) terracottaSpawnTimerEnabled = obj.get("terracottaSpawnTimerEnabled").getAsBoolean();
		if (obj.has("lastBreathTimerEnabled")) lastBreathTimerEnabled = obj.get("lastBreathTimerEnabled").getAsBoolean();
		if (obj.has("lastBreathBoxColor")) lastBreathBoxColor = obj.get("lastBreathBoxColor").getAsInt();
		// Older configs' "stormPadTickColorCodeNumber"/"stormPadTickBoldText"/"stormPadTickItalicText"/
		// "stormPadTickTextColor" keys are silently ignored — those settings were removed per user request.
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
	}

	@Override
	public String getDescription() {
		return "Boss-fight timer panel: F7 Storm/Goldor/Maxor/Necron countdowns and F6 Terracotta spawn/respawn timers.";
	}
}
