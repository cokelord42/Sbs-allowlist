package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.cokelord.skyblocksimplified.mixin.BossHealthOverlayAccessor;
import com.cokelord.skyblocksimplified.mixin.LerpingBossEventAccessor;
import com.cokelord.skyblocksimplified.util.SkullTextureUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * F7 boss-fight "Blood Camp" helper — ported from Odin's {@code BloodCamp.kt}. Two independently-toggleable
 * pieces (matching Odin's two settings dropdowns):
 * <ul>
 *   <li><b>Move Prediction</b>: chat-regex-timed countdown + kill-title for when the Watcher moves after
 *       its first spawn wave.</li>
 *   <li><b>Blood Assist</b>: predicts where each blood-mob "spawn preview" armor stand (a Hypixel-side
 *       marker that visibly drifts toward the real spawn point over ~38 ticks) will land, drawing boxes +
 *       a countdown label.</li>
 * </ul>
 *
 * <p>Odin drives Blood Assist off the raw {@code ClientboundMoveEntityPacket} (reading the packet's own
 * delta-position shorts) and detects the Watcher/spawn-preview entities via a raw
 * {@code ClientboundSetEquipmentPacket}. This codebase has no generic incoming-packet listener registry
 * for either without a new Mixin, so both are adapted to tick-polling: the position delta is computed by
 * diffing the tracked entity's {@code position()} between ticks (the entity's logical position already
 * reflects the latest server update by the time a tick runs, so this loses at most the sub-tick timing a
 * raw packet handler would have — immaterial for a puzzle timed in whole seconds) and the Watcher/spawn
 * markers are found by scanning nearby entities each tick for a head texture matching the same known skull
 * lists Odin uses, via this codebase's existing {@link SkullTextureUtil}. Odin's "Watcher Bar" idea (showing
 * mobs-killed/total instead of the boss bar's own HP percentage) IS implemented, per later user request —
 * not via a real interception of vanilla's own boss bar (this codebase has no such hook), but by feeding
 * {@link #isWatcherCounterReady()}/{@link #watcherCounterText()} into {@link BossBarFeature}'s own
 * already-existing custom text readout, which substitutes for vanilla's real bar when "Hide Vanilla" is on.
 * The counter used to also render as its own separate moveable "The Watcher 8/17" HUD widget; per user
 * request ("tie this to the show watcher mobs left and remove the gui element... just replaces the
 * percentage with the mobs killed out of mobs total instead") that standalone widget is gone — this class no
 * longer implements {@code MoveableWidget} at all, since the box-prediction pipeline above was never
 * position-editable to begin with (it draws directly in world space, not at a HUD anchor).
 */
public class BloodCampFeature extends Feature {
	private static final Pattern BLOOD_START_PATTERN = Pattern.compile(
		"^\\[BOSS] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|" +
		"Ah, we meet again\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|You've managed to scratch and claw your way here, eh\\?|" +
		"I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|Things feel a little more roomy now, eh\\?)$");
	private static final Pattern BLOOD_MOVE_PATTERN = Pattern.compile("^\\[BOSS] The Watcher: Let's see how you can handle this\\.$");
	// Real, confirmed Hypixel line (already relied on elsewhere in this codebase — see DungeonState's own
	// WATCHER_PORTAL_ENTRY_PATTERN, same text) marking the real end of the Watcher fight — the gate opens and
	// the player passes through to the rest of F7. Per user request ("It should also hide when the [BOSS] The
	// Watcher: You have proven yourself. You may pass. pops up in chat"): not anchored with a trailing "$" for
	// the same reason DungeonState's own copy of this line isn't — a real trailing color/reset artifact
	// shouldn't silently fail the whole match.
	private static final Pattern WATCHER_CLEARED_PATTERN = Pattern.compile("^\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.?");
	// Real bug found (per user report — "sometimes the blood camp helper doesnt show, but the kill timer
	// does"): the kill timer is purely chat-driven (works regardless of where the player stands), but the
	// box-prediction pipeline below used to scan for the Watcher/spawn markers within a fixed radius of the
	// PLAYER's own position — so standing anywhere past that radius from the Watcher (easy to do in a room
	// this size, especially right as the fight starts) silently produced zero boxes while the timer kept
	// working fine, exactly the split the report describes. Neither Odin's real BloodCamp.kt (matches
	// Zombies straight off ClientboundSetEquipmentPacket, no distance check at all) nor Skyblocker's real
	// BloodCampHelper.java (ClientEntityEvents.ENTITY_LOAD, also no player-distance check — markers are only
	// filtered by distance to the WATCHER, {@code watcher.getBoundingBox().inflate(2)}) ever gate this on
	// the player's position. Fixed below by scanning every client-tracked entity ({@code
	// level.entitiesForRendering()}) instead of a player-centered AABB — the existing distance-to-WATCHER
	// check for markers (not distance-to-player) is the only range filter needed, matching both references.

	// ---- Move Prediction settings ----
	private boolean movePrediction = true;
	private boolean partyMoveTime = false;
	private boolean killTitle = true;

	// ---- Blood Assist settings ----
	private boolean bloodAssist = true;
	private int spawnColor = 0xFFFF5555;
	private int finalColor = 0xFF00AAAA;
	private int positionColor = 0xFF55FF55;
	private double boxSize = 1.0;
	private boolean drawLine = true;
	private boolean drawTimeLeft = true;
	private int assumeTick = 38;
	private int offsetMillis = 40;
	private boolean pingOffset = true;
	private float manualOffsetMillis = 0f;
	// Real bug found (per user report — "Bloodcamps '3d boxes' does nothing. It should have a selector
	// exactly like the highlight modules"): the previous boolean version of this only ever flipped between
	// the old plain wireframe (drawWireBox) and one fixed style (FILLED_OUTLINE via drawStyledBox) — not a
	// real style selector, and per the report it wasn't even reaching the render path correctly. A follow-up
	// round replaced it with a WorldRenderUtil.RenderStyle 3-way cycle (Outline/Filled/Filled Outline), but
	// per a further user report ("The blood camp feature still doesnt have the render selector. Replace the
	// 'Box style' with it") that still wasn't the SAME render selector every entity-highlight module
	// (MobHighlightFeature) exposes — a real 2D/3D split with an actual depth-tested/occluded 3D box, not
	// just a 2D fill-style variant. Reusing MobHighlightFeature.RenderMode directly (rather than inventing a
	// parallel enum) lets this share that same 4-way 2D/2D Fill/3D/3D Fill cycle, including the Occlusion
	// subtoggle for the two 3D modes — see renderMode3DBoxes/render3DStatic below for how the 3D modes are
	// actually drawn (a real World3DRenderer callback during the level pass, not this HUD pass, since only
	// that pass can depth-test/occlude against the world).
	private MobHighlightFeature.RenderMode renderMode = MobHighlightFeature.RenderMode.OUTLINE_2D;
	// Same meaning as MobHighlightFeature's own field of the same name: only consulted for the two 3D render
	// modes above. true = real occluded box (World3DRenderer.drawFilledBox/drawWireBox); false = "ESP-style"
	// always-visible-through-walls (the *ThroughWalls counterparts).
	private boolean occlusion3D = true;
	private static final int BOX_FILL_OPACITY_PERCENT = 40;
	// See updateTracking's own doc comment on the "timer expires ~2s early, sometimes" bug this fixes: the
	// minimum per-tick movement (in blocks squared) treated as "real drift has started," filtering out
	// client-side interpolation jitter (well under this) without missing the real drift's own much larger
	// per-tick movement.
	private static final double MIN_REAL_MOVEMENT_SQR = 0.0004;
	// See updateTracking's own doc comment on the real switch from a cumulative-since-start average to an
	// exponential moving average of each tick's instantaneous velocity. Real client ticks fire at a stable
	// ~50ms of real wall-clock time regardless of server-side lag (unlike currentTickTime, which is
	// intentionally lag-scaled for countdown scheduling against SERVER ticks) — this is a plain physical
	// constant, not a tunable.
	private static final double NOMINAL_CLIENT_TICK_MILLIS = 50.0;
	// Per Gemini's suggested smoothing curve (asked for one after a real recording showed the box still
	// snapping, just in bigger chunks post-deadzone-widening — see updateTracking's own doc comment): each
	// tick's own instantaneous velocity is blended 40% into the running estimate, 60% kept from before — new
	// samples matter enough that the estimate tracks the real drift's acceleration promptly, but a single
	// noisy/interpolation-jittery tick still can't swing the displayed speed on its own.
	private static final double VELOCITY_EMA_ALPHA = 0.4;
	// Per user report on the first wave's ~4 simultaneously-spawning mobs (see EntityData.consecutiveMovementTicks'
	// own doc comment): a single qualifying tick can be a one-off client-load stutter, not real drift — real
	// drift is a sustained ~38-tick animation, so requiring the movement signal to repeat for this many
	// consecutive ticks before actually latching data.started rejects a one-tick spike without meaningfully
	// delaying detection of genuine movement (delayed by at most (this - 1) real ticks either way).
	private static final int REQUIRED_CONSECUTIVE_MOVEMENT_TICKS = 3;
	// History on speedVectors' averaging method, for whoever reads this next: a permanent early-window
	// freeze (locks in whatever the first ~500ms measured) was tried and reverted — a lag spike landing in
	// that window corrupted the rest of the drop with no way to recover. A plain cumulative-since-start
	// average was tried next and also replaced — real Hypixel drift is an EASED animation, not constant
	// velocity, so that average was a systematic (not just noisy) underestimate early on, visibly "catching
	// up" as the drop progressed. The current approach (see updateTracking's own doc comment) is a plain
	// exponential moving average of each tick's own instantaneous velocity: always recomputing (so a lag
	// spike's bad reading isn't ever permanently locked in, unlike the freeze), but recency-weighted (so it
	// tracks the real drift's own acceleration instead of averaging it away with old, slower samples).
	// Populated by renderInner (the HUD pass) each frame when renderMode is one of the 3D modes — one entry
	// per box that frame would otherwise have drawn in 2D — and drained by render3DInner (the World3DRenderer
	// pass) on the very next opportunity. A box's real world position only meaningfully changes a few times a
	// second (it's a slow drift-prediction endpoint, not something snapping every frame), so reading last
	// frame's computed list from the level-render pass is never visibly stale.
	private record PendingBox3D(AABB box, int color) {}
	private final List<PendingBox3D> pending3DBoxes = new ArrayList<>();

	private Zombie currentWatcherEntity;
	private boolean firstSpawns = true;
	// Real bug found (per user report — "they seem to spawn earlier when you do the skip (killing the first
	// 4 mobs at the same time, the mob kill timer)... pulls off about a second or something"): killing that
	// first wave simultaneously skips Hypixel straight into the next spawn cycle early, so the fixed
	// assumeTick/offsetMillis budget getTimeUntilSpawn() assumes (tuned against the NORMAL, un-skipped
	// timing) overshoots by however much real time the skip actually saved — every mob after a skip spawns
	// for real before its own predicted countdown ever reaches 0. Detected in renderInner() by noticing a
	// tracked marker go stale (its real mob has already spawned/replaced it) while its own predicted `time`
	// was still positive — exactly "the mob didn't reach the prediction box" — and the remaining amount is
	// subtracted from every later prediction this same encounter via getTimeUntilSpawn(), until a fresh
	// BLOOD_START_PATTERN line (a genuinely new blood-camp room) resets it back to zero.
	private long spawnTimingSkewMillis = 0L;
	// Per a further user report ("the timer for the first four mobs seems to run out really early aswell,
	// like more than a second off"): unlike the kill-skip case just above, this is the FIRST wave's own
	// prediction running out early, not a later one after a skip. No first-wave-specific numeric bug was
	// found in the budget math itself (getTimeUntilSpawn's +2000/assumeTick/offsetMillis constants are
	// unchanged from what's already confirmed correct for the non-first-wave case). A same-round attempt to
	// blame this on undetected server lag (see TpsMonitor.getEstimatedTps()'s own doc comment) was reverted
	// after it caused a real, confirmed regression elsewhere — so this report is still genuinely open and
	// needs a live, timestamped repro to isolate further; DebugLog is permanently disabled per an earlier
	// explicit user request, so there's no way to add live instrumentation for it here.

	// ---- Watcher Mobs Left Counter ----
	// Per user request: "It will do this by taking the bossbar before it is hidden at full, and when the
	// user kills the first mob it can calculate how much went down, and by that get the exact number of
	// mobs required to kill the watcher... make sure it works if i kill multiple mobs at once, cause of the
	// 4 mob spawn kill skip thing." Reads the exact same real vanilla boss-bar signal {@link BossBarFeature}
	// already does (via {@link BossHealthOverlayAccessor}), but off {@link LerpingBossEventAccessor}'s raw
	// {@code targetPercent} instead of the smoothly-animating {@code getProgress()} — the target changes
	// exactly once per real Hypixel update, giving one clean before/after pair per kill (or kill-batch)
	// instead of several fake small "drops" while the bar's own render animation catches up.
	private boolean watcherMobsCounter = true;
	// The bar's own reading the very first time it's seen this encounter — the "100%" reference every later
	// drop is measured against, rather than assuming it's always exactly literal 100.
	private Float watcherBaselinePercent = null;
	private Float watcherLastPercent = null;
	// Per user follow-up ("I think the way we fix the 4 count issue is to always do the math when a mob
	// gets killed, so if the mod thinks i killed one mob giving 20% when i actually killed 4, killing the
	// next mob only gives 5% and then the mod detects that one mob is only 5% and therefore redoes the
	// math"): a kill-skip batch always drops the bar by an exact multiple of the true single-kill amount, so
	// the smallest drop ever observed is always the most accurate "how much one real kill is worth" — every
	// later, smaller reading replaces this and the whole count is recomputed off it.
	private Float watcherPerKillPercent = null;
	private Integer watcherTotalMobs = null;
	private int watcherMobsKilled = 0;
	// Per user request ("remove the [P4] when all watcher mobs are dead") — consulted by BossBarFeature to
	// suppress its own F7Phase suffix once this is true (the Watcher fight is a pre-boss gate, not a real F7
	// phase, so the height-band phase calc coincidentally landing in P4's range there was always meaningless
	// — see BossBarFeature's own doc comment on where it reads this).
	private boolean watcherAllMobsDead = false;

	private Float moveTimeSeconds = null;
	private long currentTickTime = 0L;
	private Long startTime = null;
	private long killTitleAtTick = -1;
	// Per user request ("make a 3 second countdown before the kill mobs title") — 3/2/1 fire as their own
	// short titles+ticks in the seconds leading up to killTitleAtTick, tracked so each number fires exactly
	// once per cycle regardless of how many ticks land inside its 1-second window.
	private final Set<Integer> firedCountdownSeconds = new HashSet<>();
	// Real bug found (per user report — "it counts 3 and 2 really fast then 1 and 0 really slowly"): the
	// real gap between "Let's see how you can handle this" and Kill Mobs is predTicks*50ms, which per its
	// own bucketing below is typically only ~1.2-1.8 real seconds (predTicks in [24,36]) — well under the 3
	// whole seconds a "3, 2, 1" countdown needs to feel evenly paced. Whichever countdown numbers were
	// already below their own threshold the very FIRST tick this runs (almost always true for "3", often
	// true for "2" too, since the window rarely reaches 2-3s) fired within milliseconds of each other, while
	// only the LAST number-to-Kill-Mobs gap is ever a genuine, full 1000ms — exactly the "fast, fast, ...
	// slow" pattern reported. Fixed by starting the countdown at whatever whole second is ACTUALLY the
	// highest one that fits in the real remaining time (computed once, when the countdown is armed below),
	// instead of always starting from a hardcoded "3" regardless of how much real time exists — every number
	// that does get shown is then a genuine, evenly-paced ~1 real second apart.
	private int countdownStartSec = 0;

	private static final class EntityData {
		Vec3 startVector;
		// Not final anymore — see updateTracking's own doc comment for why this needs to move once real
		// movement actually starts, instead of staying pinned to whenever the entity was first detected.
		long started;
		final boolean firstSpawns;
		final ArrayDeque<Vec3> deltaHistory = new ArrayDeque<>();
		Vec3 lastPosition;
		// Per user report ("the blood camp timer and box fix we did earlier breaks the first four mobs. It
		// makes the timer wrong completely and the final box is way too early... then it keeps going for a
		// while more"): the first wave's ~4 preview markers all spawn at once, right as the room's own
		// entities/chunks are loading in — the single heaviest-stutter moment of the whole encounter. A real
		// client hitch there can make entity.position() jump by a single large step as interpolation catches
		// up to a resync, well above MIN_REAL_MOVEMENT_SQR, even though the marker hasn't actually started its
		// real ~38-tick drift yet. Since data.started latches on the very first qualifying tick, that one spurious
		// jump was enough to start the countdown against a mob that, physically, was still sitting still —
		// exactly "the timer wrong completely... enters the box making it blue... then keeps going for a while
		// more" once the real drift finally does start afterward. See updateTracking's own use of this counter.
		int consecutiveMovementTicks = 0;

		EntityData(Vec3 startVector, long started, boolean firstSpawns) {
			this.startVector = startVector;
			this.started = started;
			this.firstSpawns = firstSpawns;
			this.lastPosition = startVector;
		}
	}

	private static final class RenderData {
		Vec3 currVector;
		Vec3 speedVectors;
		// Diagnostic only (per repeated user report — "still going too far" — after multiple already-applied
		// fixes whose math now line-for-line matches Odin's own confirmed real BloodCamp.kt: the 16.1/11.9
		// distance constants, the boxOffset formula, and even the uncapped-deltaHistory direction-summing
		// approach, which telescopes to exactly (currentPos - startVector) regardless of per-tick vs.
		// per-packet sampling granularity). With no further confirmable static-analysis lead, logs the actual
		// predicted-vs-real error in blocks once per entity instead of guessing a fourth blind tweak.
		boolean loggedFinal = false;
		// Real bug found (per user report — "It starts with a prediction, then slowly gets closer to the mob
		// head while the mob head is moving" — and, after a widened display deadzone only turned the creep
		// into occasional bigger snaps instead of actually fixing it — "It seems to jump closer and further
		// like before but now just jumps in bigger chunks, which means the math for the speed is failing"):
		// the real cause was `speedVectors` itself, not how often the display updated — see updateTracking's
		// own doc comment on the switch to a recency-weighted exponential moving average. Once the underlying
		// velocity estimate is genuinely smooth, the projected endpoint is smooth by construction with no
		// separate deadzone/snap step needed — this field is now just the raw endpoint, recomputed fresh
		// every frame in renderInner (which still freezes it once the countdown reaches 0 — see that method's
		// own doc comment on why recomputing off the entity's live position after time-up is wrong).
		Vec3 displayedEndPoint;
	}

	private final Map<ArmorStand, EntityData> entityDataMap = new HashMap<>();
	private final Map<ArmorStand, RenderData> renderDataMap = new HashMap<>();

	private static boolean listenersRegistered = false;
	private static BloodCampFeature instance;

	public BloodCampFeature() {
		super("blood_camp", "Blood Camp", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				// Stripped here too, same reasoning as DungeonState's own fix this round (per user report,
				// pointed at comparing against PestSpawnAlertFeature's already-working, defensively-stripped
				// chat regex) — the BLOOD_START/BLOOD_MOVE patterns are exact-text matches with no defense
				// against embedded "§" formatting codes getString() alone might not have stripped.
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString().replaceAll("§.", ""));
				return true;
			});
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "blood_camp"), BloodCampFeature::renderStatic);
			World3DRenderer.addRenderCallback(BloodCampFeature::render3DStatic);
		}
	}

	@Override
	protected void onDisable() { reset(); }

	private void reset() {
		currentWatcherEntity = null;
		entityDataMap.clear();
		renderDataMap.clear();
		currentTickTime = 0;
		firstSpawns = true;
		moveTimeSeconds = null;
		startTime = null;
		killTitleAtTick = -1;
		firedCountdownSeconds.clear();
		countdownStartSec = 0;
		pending3DBoxes.clear();
		spawnTimingSkewMillis = 0L;
		resetWatcherCounter();
	}

	private void resetWatcherCounter() {
		watcherBaselinePercent = null;
		watcherLastPercent = null;
		watcherPerKillPercent = null;
		watcherTotalMobs = null;
		watcherMobsKilled = 0;
		watcherAllMobsDead = false;
	}

	/** Real root cause found (per user report — "still isn't rendering any boxes for the predictions"):
	 *  this feature's whole pipeline (chat parsing, tick-based watcher/spawn-marker scan, AND rendering)
	 *  used to gate on {@code DungeonState.isInBoss()} — but the Watcher fight happens in an ordinary
	 *  dungeon ROOM guarding F7's entrance, BEFORE the Maxor boss door is ever entered, not inside the
	 *  boss instance at all. Confirmed against Odin's own real {@code DungeonUtils.inClear = inDungeons &&
	 *  !inBoss} (the exact flag Odin's real {@code BloodCamp.kt} gates every one of its own listeners on)
	 *  — the literal OPPOSITE of what this port was checking, so {@code isInBoss()} being false the whole
	 *  time Blood Camp is actually relevant (which it always is) meant nothing here could ever run. */
	private static boolean inClear() {
		return DungeonState.isInDungeon() && !DungeonState.isInBoss();
	}

	private void onChatMessage(String text) {
		if (!inClear()) return;
		// Watcher Mobs Left Counter runs independently of Move Prediction's own toggle (see the field block's
		// doc comment) — a genuinely new blood-camp room resets its baseline/count too, same anchor point
		// spawnTimingSkewMillis already resets on for the same reason. Per user request ("It should also hide
		// when the [BOSS] The Watcher: You have proven yourself. You may pass. pops up in chat"): the real
		// end-of-fight line resets it the same way, hiding the readout (isVisible() requires a non-null total).
		if (BLOOD_START_PATTERN.matcher(text).matches()) resetWatcherCounter();
		if (WATCHER_CLEARED_PATTERN.matcher(text).find()) resetWatcherCounter();
		if (!movePrediction) return;

		// Diagnostic only (per user report — "its STILL not detecting the boss messages... a regex issue at
		// this point" for the separate F7-phase lines, and separately "issues detecting when the kill mobs
		// title should appear, it doesnt sometimes" here): if BLOOD_START_PATTERN never matches for a given
		// encounter (e.g. an unlisted taunt-line variant), startTime stays null and the BLOOD_MOVE_PATTERN
		// branch below silently bails out with no Kill Mobs alert at all for that whole run — matches
		// Odin's own real bail-out exactly, so not a porting bug, but logged here so the next live run with
		// debug logging on shows whether this specific path is why it's missing sometimes.
		if (BLOOD_START_PATTERN.matcher(text).matches()) {
			startTime = currentTickTime;
			// See spawnTimingSkewMillis's own field doc comment: each real blood-camp encounter starts this
			// line fresh — any correction learned during a previous attempt in a previous dungeon doesn't
			// carry over to a new one.
			spawnTimingSkewMillis = 0L;
		} else if (BLOOD_MOVE_PATTERN.matcher(text).matches()) {
			firstSpawns = false;
			Long tickTime = startTime;
			if (tickTime == null) {
				return;
			}
			long moveTicks = (currentTickTime - tickTime) / 1000;
			long predTicks;
			if (moveTicks >= 31 && moveTicks < 34) predTicks = 36;
			else if (moveTicks >= 28 && moveTicks < 31) predTicks = 33;
			else if (moveTicks >= 25 && moveTicks < 28) predTicks = 30;
			else if (moveTicks >= 22 && moveTicks < 25) predTicks = 27;
			else if (moveTicks >= 1 && moveTicks < 22) predTicks = 24;
			else predTicks = moveTicks + 3;

			moveTimeSeconds = predTicks / 20f;
			Minecraft mc = Minecraft.getInstance();
			if (partyMoveTime && mc.player != null && mc.player.connection != null) {
				mc.player.connection.sendCommand("pc Watcher will move in " + fmt(moveTimeSeconds) + "s.");
			}
			killTitleAtTick = currentTickTime + predTicks * 50;
			firedCountdownSeconds.clear();
			// Real bug found (per user report — "the kill mobs countdown counts from 1"): predTicks tops out
			// at 36 (1.8 real seconds), so `(predTicks*50L)/1000L` floor-divides to exactly 1 EVERY time,
			// capping the loop below to only ever consider sec=1 — "2" and "3" could never fire even though
			// the per-second bucket check two lines down (remainingMs <= sec*1000 && > (sec-1)*1000) already
			// gates each number to its own natural one-second window on its own and would have let "2" (or
			// "3", on a longer prediction) fire for real once the countdown reached it. The cap was solving a
			// DIFFERENT, already-fixed problem (multiple numbers racing together with no bucket check at all)
			// and just needlessly truncated the countdown once that bucket check made it redundant.
			countdownStartSec = 3;
		}
	}

	@Override
	public void onTick(Minecraft client) {
		currentTickTime += lagAdjustedTickMillis();
		if (moveTimeSeconds != null) {
			moveTimeSeconds -= 0.05f;
			if (moveTimeSeconds <= 0f) moveTimeSeconds = null;
		}
		// Per user request ("make a 3 second countdown before the kill mobs title") — fires "3", "2", "1"
		// as their own short titles+ticks in the seconds leading up to the real Kill Mobs alert, each
		// exactly once regardless of how many 50ms ticks land inside that 1-second window.
		if (killTitleAtTick > 0 && killTitle && client.player != null) {
			long remainingMs = killTitleAtTick - currentTickTime;
			for (int sec = countdownStartSec; sec >= 1; sec--) {
				if (remainingMs <= sec * 1000L && remainingMs > (sec - 1) * 1000L && firedCountdownSeconds.add(sec)) {
					client.gui.hud.setTitle(Component.literal("§e§l" + sec));
					client.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
				}
			}
		}
		if (killTitleAtTick > 0 && currentTickTime >= killTitleAtTick) {
			killTitleAtTick = -1;
			firedCountdownSeconds.clear();
			// Real bug found (per user report — "Kills mobs should show a title and play a sound"): this only
			// ever sent a plain chat message, not an actual vanilla title — confirmed against Odin's own real
			// alert() (setTitle + SoundEvents.NOTE_BLOCK_PLING, exactly what Odin's own "Kill Mobs" call uses)
			// that it should be both. Matches the same mc.gui.hud.setTitle/playSound pattern DungeonNotifications-
			// Feature and InvincibilityTimerFeature already use for their own title alerts.
			if (killTitle && client.player != null) {
				client.gui.hud.setTitle(Component.literal("§c§lKill Mobs"));
				client.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f);
			}
		}

		if (!inClear() || client.level == null || client.player == null) return;
		updateWatcherMobsCounter(client);
		if (!bloodAssist) return;

		if (currentWatcherEntity == null) {
			for (var entity : client.level.entitiesForRendering()) {
				if (!(entity instanceof Zombie zombie)) continue;
				ItemStack head = zombie.getItemBySlot(EquipmentSlot.HEAD);
				String texture = SkullTextureUtil.fromItem(head);
				if (texture != null && WATCHER_SKULLS.contains(texture)) { currentWatcherEntity = zombie; break; }
			}
		} else if (!currentWatcherEntity.isAlive()) {
			currentWatcherEntity = null;
		}
		if (currentWatcherEntity == null) return;

		for (var entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof ArmorStand stand)) continue;
			if (stand.distanceTo(currentWatcherEntity) > 20) continue;
			ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
			if (!head.is(Items.PLAYER_HEAD)) continue;
			String texture = SkullTextureUtil.fromItem(head);
			if (texture == null || !ALLOWED_MOB_SKULLS.contains(texture)) continue;

			updateTracking(stand);
		}
	}

	/** Reads the exact same real vanilla boss-overlay events map {@link BossBarFeature} reads (see that
	 *  class's own doc comment for how this was confirmed), off {@link LerpingBossEventAccessor}'s raw
	 *  {@code targetPercent} instead of the animated {@code getProgress()} — see that mixin's own doc
	 *  comment for why the raw target is what makes the "how much did one kill drop it" math exact instead
	 *  of noisy. Per user request, see the watcher-counter field block's own doc comment for the full
	 *  self-correcting algorithm this implements. */
	private void updateWatcherMobsCounter(Minecraft client) {
		if (!watcherMobsCounter) return;
		var overlay = client.gui.hud.getBossOverlay();
		Map<UUID, LerpingBossEvent> events = ((BossHealthOverlayAccessor) (Object) overlay).skyblocksimplified$getEvents();
		if (events.isEmpty()) return;
		LerpingBossEvent event = events.values().iterator().next();
		if (!event.getName().getString().toLowerCase(Locale.ROOT).contains("watcher")) return;

		float percent = ((LerpingBossEventAccessor) (Object) event).skyblocksimplified$getTargetPercent() * 100f;

		if (watcherBaselinePercent == null) {
			// First real sighting of the Watcher's own bar this encounter — "taking the bossbar before it is
			// hidden at full" per the user's own words: whatever it reads right now becomes the 100% reference
			// every later drop is measured against.
			watcherBaselinePercent = percent;
			watcherLastPercent = percent;
			return;
		}
		if (watcherLastPercent == null) {
			watcherLastPercent = percent;
			return;
		}

		float drop = watcherLastPercent - percent;
		// Ignores float noise and any non-decrease (the bar never goes back up mid-fight) — a real kill (or
		// kill-batch) always drops it by a real, meaningfully-sized amount.
		if (drop <= 0.05f) return;
		watcherLastPercent = percent;

		if (watcherPerKillPercent == null || drop < watcherPerKillPercent) {
			// See the field's own doc comment: a batch kill can only ever be an exact multiple of the true
			// single-kill amount, so any drop smaller than the current assumption can only be a genuine solo
			// kill revealing the real value — redo the whole count off it.
			watcherPerKillPercent = drop;
		}
		if (watcherPerKillPercent <= 0.01f) return;

		watcherTotalMobs = Math.max(1, Math.round(watcherBaselinePercent / watcherPerKillPercent));
		float totalDropped = watcherBaselinePercent - percent;
		watcherMobsKilled = Math.max(0, Math.min(watcherTotalMobs, Math.round(totalDropped / watcherPerKillPercent)));
		watcherAllMobsDead = watcherMobsKilled >= watcherTotalMobs;
	}

	private void updateTracking(ArmorStand entity) {
		Vec3 currentPos = entity.position();
		EntityData data = entityDataMap.computeIfAbsent(entity, e -> new EntityData(currentPos, currentTickTime, firstSpawns));

		Vec3 delta = currentPos.subtract(data.lastPosition);
		data.lastPosition = currentPos;
		boolean wasStationary = data.deltaHistory.isEmpty();
		// Real bug found (per user report — "the timer was going off too early, expiring like 2 seconds
		// before the mob actually came out... the timer detection system is wrong sometimes"): this used to
		// treat ANY nonzero delta.lengthSqr() as "real movement has begun" — but entity.position() is the
		// client's own INTERPOLATED render position, which can drift by tiny sub-pixel amounts tick to tick
		// purely from interpolation/position-resync smoothing even before the real Hypixel drift animation
		// has genuinely started. Since data.started (the countdown's own zero point) latches on the very
		// first tick "movement" is seen, a stray jitter tick a few ticks early starts the whole budget
		// counting down before the mob has actually begun moving for real — exactly an intermittent ("wrong
		// sometimes") early expiry, not a constant bias, since interpolation jitter isn't guaranteed to occur
		// every single spawn. MIN_REAL_MOVEMENT_SQR is comfortably above any interpolation noise (well under
		// a hundredth of a block) but far below the real drift's actual per-tick speed (16.1/11.9 blocks over
		// ~38 ticks is roughly 0.3-0.4 blocks/tick), so genuine movement is still caught on its very first
		// real tick.
		if (delta.lengthSqr() > MIN_REAL_MOVEMENT_SQR) {
			// See EntityData.consecutiveMovementTicks' own doc comment (the first-wave-of-4 stutter bug): only
			// counts toward "real movement has begun" once this many ticks in a row have qualified, rejecting
			// a single spurious client-load-stutter jump.
			data.consecutiveMovementTicks++;
			if (data.consecutiveMovementTicks >= REQUIRED_CONSECUTIVE_MOVEMENT_TICKS) {
				// Real bug found (per user report — "the timers on the blood mobs doesnt seem to reset... its
				// -15 seconds and keeps adding for each mob"): data.started used to be fixed at whenever this
				// entity was FIRST DETECTED by the scan above (in the EntityData constructor) — but a spawn-
				// preview marker can sit visible-but-motionless for several real seconds before Hypixel actually
				// starts drifting it (that head start is the whole point of a preview marker). getTimeUntilSpawn
				// computes its countdown off (currentTickTime - data.started), so by the time this entity's box
				// even starts rendering (gated on real movement below, per the fix above this round), timeTook
				// already included that whole motionless dwell period — starting the countdown deeply negative
				// instead of at the real ~2-4 second budget. Reset started to the tick movement ACTUALLY begins,
				// not whenever the entity was first seen.
				if (wasStationary) data.started = currentTickTime;
				// Real bug found (per user report — "spawn boxes are really offset sometimes... way further than
				// the mob actually goes"): this used to cap deltaHistory at a 20-sample sliding window (an
				// arbitrary value this port invented — Odin's own real BloodCamp.kt never caps it at all, just
				// {@code data.deltaHistory.addLast(delta)} for the entity's whole tracked lifetime). With no cap,
				// summing every recorded delta since tracking began telescopes into exactly (currentPos -
				// startVector) — i.e. "the straight-line direction from where it started to where it is right
				// now", which only gets MORE accurate as the mob moves further. Capping the window instead meant
				// the direction was only ever "the trend over the last ~1 second", which could drift/wobble away
				// from the true overall direction (e.g. as the drift animation eases toward its final position)
				// and get scaled by the same fixed 16.1/11.9-block distance regardless — overshooting whenever
				// the recent-second trend didn't match the real overall direction. Uncapped now, matching Odin.
				data.deltaHistory.addLast(delta);
			}
		} else {
			data.consecutiveMovementTicks = 0;
		}

		// Real bug found (per user report — "sees the still heads on the walls as soon as i enter the
		// bloodroom. Its only supposed to do it for the moving ones"): this used to add every matching
		// ArmorStand to renderDataMap (and therefore start drawing a box for it) the instant it was found by
		// the scan above, regardless of whether it had ever actually moved — which is exactly what the
		// static decorative heads mounted on the walls are: entities with the same allowed-mob-skull
		// texture that never move at all. Confirmed against Skyblocker's own real BloodCampHelper.java: it
		// never predicts/renders a mob until it's accumulated real nonzero movement samples (its own
		// DELTA_SAMPLES gate) — a static entity's delta is always exactly zero, so it never qualifies.
		// Mirrored here with a simpler "at least one real movement sample observed" gate, skipping the
		// render-data update (and therefore the box) entirely until the entity has actually started moving.
		if (data.deltaHistory.isEmpty()) return;

		// Real bug found (per user report — "the timer is now perfect but does not move the box
		// accordingly. It should detect the movement speed of the head and then do the math on where it
		// will be when the timer is finished and display the box there"): this used to project the box to a
		// FIXED total travel distance (16.1/11.9 blocks, Odin's own known real spawn-drift distance) along
		// the summed direction traveled so far — a box position entirely independent of the countdown timer,
		// which only converges on the real landing spot once enough of that fixed distance has actually been
		// covered. Replaced with exactly what was asked: speedVectors (the head's own observed average
		// velocity) times however many ms getTimeUntilSpawn() says are left, added to the head's CURRENT
		// live position — recomputed fresh every render frame in renderInner(), so the box continuously
		// tracks both the head's real position and the countdown's own remaining time.
		RenderData render = renderDataMap.computeIfAbsent(entity, e -> new RenderData());
		// Real bug found (per user report, after watching a real recording — "It seems to jump closer and
		// further like before but now just jumps in bigger chunks, which means the math for the speed is
		// failing. Can we introduce a curve to cancel it out?"): the previous round's wider display deadzone
		// only masked the symptom (fewer, bigger snaps instead of constant small creep) without fixing why
		// the estimate needed correcting at all. The real cause is this cumulative-average formula itself:
		// `(currentPos - startVector) / timeTook` assumes CONSTANT velocity for the mob's entire drop, but
		// Hypixel's real drift is an EASED animation (slow at first, accelerating) — early samples are
		// therefore always genuinely too slow, not just noisy, so the average is a systematic underestimate
		// that keeps climbing as more (faster) later ticks get folded in. That's exactly "starts with a
		// prediction, then slowly creeps": not sensor noise, a biased formula assuming the wrong motion model.
		// Replaced with an exponential moving average of each tick's own INSTANTANEOUS velocity (this real
		// tick's position delta over one real client tick's wall-clock length — a stable ~50ms regardless of
		// server-side lag, since updateTracking runs once per real client tick, unlike the lag-scaled
		// currentTickTime used for countdown scheduling) — the same fix Gemini suggested when asked for a
		// smoothing curve. An EMA weights RECENT motion far more than old history, so as the real drift
		// accelerates the estimate tracks the CURRENT speed almost immediately instead of being dragged down
		// by the slow early ticks for the whole rest of the drop — no separate deadzone/snap step is needed
		// on the endpoint anymore (see renderInner's own note): a smoothly-updating velocity produces a
		// smoothly-updating projected endpoint by construction.
		// Real bug found (per user follow-up — "It jumps around a lot due to tps and such i think. It seems to
		// jitter a lot in the same place kinda, it just jumps back and forth very fast when starting to move
		// and when its getting close to the final box"): the EMA above still blended EVERY tick's raw delta
		// into the estimate, including ticks whose delta is itself just client-side interpolation/network-
		// timing noise rather than a real movement sample — exactly worst right at the two transitions this
		// report names: the very start of the drift (a discontinuity as interpolation catches up to the first
		// real server update) and the very end (the real animation is decelerating toward a stop, so the true
		// signal shrinks toward the same size as the noise floor). Gated on the same MIN_REAL_MOVEMENT_SQR
		// threshold already used to decide "has real movement begun" at all: a tick whose delta doesn't clear
		// it is treated as an unreliable sample and skipped entirely (the estimate holds at whatever it already
		// was) instead of blending in a near-zero/noisy reading that would otherwise drag the EMA back and
		// forth every time one of these ticks lands.
		if (delta.lengthSqr() > MIN_REAL_MOVEMENT_SQR) {
			Vec3 instantVelocity = new Vec3(delta.x / NOMINAL_CLIENT_TICK_MILLIS, delta.y / NOMINAL_CLIENT_TICK_MILLIS, delta.z / NOMINAL_CLIENT_TICK_MILLIS);
			render.speedVectors = render.speedVectors == null ? instantVelocity : new Vec3(
				instantVelocity.x * VELOCITY_EMA_ALPHA + render.speedVectors.x * (1 - VELOCITY_EMA_ALPHA),
				instantVelocity.y * VELOCITY_EMA_ALPHA + render.speedVectors.y * (1 - VELOCITY_EMA_ALPHA),
				instantVelocity.z * VELOCITY_EMA_ALPHA + render.speedVectors.z * (1 - VELOCITY_EMA_ALPHA));
		}
		render.currVector = currentPos;
	}

	// Per user report ("Blood camp timers do not account for lag"): currentTickTime used to advance a flat
	// 50ms per client tick, which is correct only at a real 20 server TPS — the Watcher-move/Kill-Mobs
	// countdown (predTicks*50 after the taunt line, see onChatMessage) and the blood-mob spawn-box prediction
	// (data.started/timeTook/assumeTick*50L) both schedule against a fixed number of SERVER ticks, and under
	// real server lag those server ticks take longer than 50ms of real wall-clock time to actually happen —
	// so a countdown that just keeps advancing at the normal rate reaches its target before the real event
	// does. Same fix DungeonTimersFeature.lagAdjustedElapsed() already uses for the identical class of
	// problem (Purple Pad, Goldor Start, Early Enter, Maxor's crystal window, Necron's lava drop): scale by
	// the live TpsMonitor estimate, a no-op at a genuine 20 TPS and proportionally slower under real lag.
	private static long lagAdjustedTickMillis() {
		double tps = com.cokelord.skyblocksimplified.util.TpsMonitor.getEstimatedTps();
		return Math.round(50 * (tps / 20.0));
	}

	private long getTimeUntilSpawn(boolean isFirstSpawn, long timeTook) {
		return (isFirstSpawn ? 2000 : 0) + (assumeTick * 50L) - timeTook + offsetMillis - spawnTimingSkewMillis;
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled() || !instance.bloodAssist || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Blood Camp render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		if (!inClear()) return;

		// Repopulated below (2D modes never add to it) — the render3D pass reads whatever's here from the
		// last time this ran. See pending3DBoxes' own field doc comment for why draining/refilling once per
		// HUD frame instead of computing directly from the level-render pass is fine here.
		pending3DBoxes.clear();
		Vec3 boxOffset = new Vec3(boxSize / -2.0, 1.5, boxSize / -2.0);
		List<ArmorStand> stale = new ArrayList<>();

		for (var entry : renderDataMap.entrySet()) {
			ArmorStand entity = entry.getKey();
			if (!entity.isAlive()) {
				// See spawnTimingSkewMillis's own field doc comment ("the mob didn't reach the prediction
				// box"): this marker is gone (its real mob has already spawned in) while our own predicted
				// countdown for it still thought there was real time left — a skip pulled the real spawn
				// earlier than assumeTick/offsetMillis assumed. Bank the leftover time as a correction for
				// every later prediction this same encounter, so the next box doesn't overshoot the same way.
				EntityData staleData = entityDataMap.get(entity);
				if (staleData != null) {
					long staleTimeTook = currentTickTime - staleData.started;
					long staleTimeLeft = getTimeUntilSpawn(staleData.firstSpawns, staleTimeTook);
					if (staleTimeLeft > 200L) {
						spawnTimingSkewMillis = Math.min(2000L, spawnTimingSkewMillis + staleTimeLeft);
						com.cokelord.skyblocksimplified.debug.DebugLog.detected("Blood Camp: mob spawned "
							+ staleTimeLeft + "ms early (likely a kill-skip) — applying that as a correction"
							+ " to future predictions this encounter (total skew now " + spawnTimingSkewMillis + "ms)");
					}
				}
				stale.add(entity);
				continue;
			}
			EntityData data = entityDataMap.get(entity);
			if (data == null) continue;
			RenderData render = entry.getValue();

			long timeTook = currentTickTime - data.started;
			long time = getTimeUntilSpawn(data.firstSpawns, timeTook);

			float mobOffset = pingOffset ? estimatePingMillis() : manualOffsetMillis;
			Vec3 pingPoint = new Vec3(
				entity.getX() + render.speedVectors.x * mobOffset,
				entity.getY() + render.speedVectors.y * mobOffset,
				entity.getZ() + render.speedVectors.z * mobOffset);

			// Real bug found (per user report — "It starts with a prediction, then slowly gets closer to the
			// mob head while the mob head is moving", and later, after a display-deadzone attempt at this same
			// complaint, "It seems to jump closer and further like before but now just jumps in bigger chunks,
			// which means the math for the speed is failing"): the actual root cause was speedVectors' own
			// averaging method, now fixed at the source (see updateTracking's own doc comment on the switch to
			// an exponential moving average) — a smoothly-updating velocity produces a smoothly-updating
			// endpoint with no artificial deadzone/snap step needed here at all, so this just recomputes fresh
			// every frame, same as the box's own live position tracking already does.
			//
			// Real bug found (per user report — "the timer still moves when the timer is gone"): once the
			// predicted countdown actually reaches 0, `remainingMs` clamps to 0, which would collapse the raw
			// endpoint to exactly the entity's OWN LIVE position (speedVectors * 0 = no look-ahead offset) — so
			// if the real entity has any residual motion of its own after its predicted spawn moment (idle
			// sway, a late correction, whatever), the box (and its "0.00s" text) would keep drifting along
			// with it even though the countdown itself has already run out. Only recompute a new raw endpoint
			// while real time is still left; once it hits 0, freeze displayedEndPoint at whatever it already
			// was — the landing spot doesn't change after the countdown ends.
			long remainingMs = Math.max(0L, time);
			if (remainingMs > 0L) {
				render.displayedEndPoint = new Vec3(
					entity.getX() + render.speedVectors.x * remainingMs,
					entity.getY() + render.speedVectors.y * remainingMs,
					entity.getZ() + render.speedVectors.z * remainingMs);
			}
			Vec3 endPoint = render.displayedEndPoint;

			AABB pingAabb = new AABB(0, 0, 0, boxSize, boxSize, boxSize).move(boxOffset.add(pingPoint));
			AABB endAabb = new AABB(0, 0, 0, boxSize, boxSize, boxSize).move(boxOffset.add(endPoint));

			if (mobOffset < time) {
				drawBox(graphics, pingAabb, positionColor);
				drawBox(graphics, endAabb, spawnColor);
			} else {
				drawBox(graphics, endAabb, finalColor);
				// See RenderData.loggedFinal's own doc comment — direct predicted-vs-actual ground truth,
				// logged once per entity right as the predicted spawn moment is reached.
				if (!render.loggedFinal) {
					render.loggedFinal = true;
					Vec3 actual = entity.position();
					double errorBlocks = endPoint.distanceTo(actual);
				}
			}

			if (drawLine) {
				WorldRenderUtil.drawLine(graphics, render.currVector.add(0, 2, 0), endPoint.add(0, 2, 0), 0xFFFF5555, 2);
			}

			if (drawTimeLeft) {
				float timeDisplaySeconds = (time - offsetMillis) / 1000f;
				String color = timeDisplaySeconds > 1.5f ? "§a" : timeDisplaySeconds >= 0.5f ? "§6" : timeDisplaySeconds >= 0f ? "§c" : "§b";
				// Per user report ("goes past 0 seconds to like -0.05 for a split second"): the countdown
				// keeps recomputing off real elapsed time even after the predicted spawn moment has passed
				// (briefly, until this entity is cleaned up), which is correct for the blue "it's late" color
				// cue above, but reads oddly as raw text — clamp only the DISPLAYED number at 0, the color
				// still reacts to the real (unclamped) value.
				WorldRenderUtil.drawText(graphics, color + fmt(Math.max(0f, timeDisplaySeconds)) + "s", endPoint.add(0, 2, 0), 0xFFFFFFFF);
			}
		}

		for (ArmorStand entity : stale) {
			renderDataMap.remove(entity);
			entityDataMap.remove(entity);
		}
	}

	/** Dispatches on {@link #renderMode}: the two 2D modes still draw immediately here (this is the HUD pass,
	 *  a plain screen-space overlay); the two 3D modes instead queue into {@link #pending3DBoxes} for
	 *  {@link #render3DInner} to actually draw during the level-render pass, since only that pass can be
	 *  depth-tested/occluded against the real world the way MobHighlightFeature's own 3D modes are. */
	private void drawBox(GuiGraphicsExtractor graphics, AABB box, int color) {
		if (renderMode == MobHighlightFeature.RenderMode.WIRE_3D || renderMode == MobHighlightFeature.RenderMode.FULL_3D) {
			pending3DBoxes.add(new PendingBox3D(box, color));
			return;
		}
		WorldRenderUtil.RenderStyle style = renderMode == MobHighlightFeature.RenderMode.FULL_2D
			? WorldRenderUtil.RenderStyle.FILLED_OUTLINE : WorldRenderUtil.RenderStyle.OUTLINE;
		WorldRenderUtil.drawStyledBox(graphics, box, color, style, 2, BOX_FILL_OPACITY_PERCENT);
	}

	private static void render3DStatic() {
		if (instance == null || !instance.isEnabled() || !instance.bloodAssist || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		try {
			instance.render3DInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Blood Camp 3D render failed, skipping this frame", e);
		}
	}

	/** Draws whatever {@link #pending3DBoxes} held after the most recent HUD-pass computation — see that
	 *  field's own doc comment. Mirrors {@code HighlightBoxRenderer.drawBox3D}'s real-vs-ESP occlusion split
	 *  exactly (same World3DRenderer calls, same occlusion3D meaning) so Blood Camp's 3D modes behave
	 *  identically to every other module offering this render selector. */
	private void render3DInner() {
		if (renderMode != MobHighlightFeature.RenderMode.WIRE_3D && renderMode != MobHighlightFeature.RenderMode.FULL_3D) return;
		boolean fill = renderMode == MobHighlightFeature.RenderMode.FULL_3D;
		for (PendingBox3D pending : pending3DBoxes) {
			int fillAlpha = fill ? Math.round(BOX_FILL_OPACITY_PERCENT / 100f * 255f) : 0;
			int fillArgb = (fillAlpha << 24) | (pending.color() & 0xFFFFFF);
			if (occlusion3D) {
				if (fillAlpha > 0) World3DRenderer.drawFilledBox(pending.box(), fillArgb);
				World3DRenderer.drawWireBox(pending.box(), pending.color(), 2f);
			} else {
				if (fillAlpha > 0) World3DRenderer.drawFilledBoxThroughWalls(pending.box(), fillArgb);
				World3DRenderer.drawWireBoxThroughWalls(pending.box(), pending.color(), 2f);
			}
		}
	}

	public boolean isWatcherMobsCounter() { return watcherMobsCounter; }
	public void setWatcherMobsCounter(boolean value) { watcherMobsCounter = value; }

	/** Consulted by {@link BossBarFeature} to decide whether to show "killed/total" instead of the boss
	 *  bar's own real HP percentage — per user request ("i actually want the watcher N/N to replace the %
	 *  in the bossbar. Tie this to the show watcher mobs left and remove the gui element... just replaces
	 *  the percentage with the mobs killed out of mobs total instead"), replacing the old standalone "The
	 *  Watcher 8/17" HUD widget entirely rather than showing both. */
	public static boolean isWatcherCounterReady() {
		return instance != null && instance.watcherMobsCounter && instance.watcherTotalMobs != null;
	}

	/** The "killed/total" text itself — only ever called after {@link #isWatcherCounterReady()} confirms
	 *  both are non-null. */
	public static String watcherCounterText() {
		return instance.watcherMobsKilled + "/" + instance.watcherTotalMobs;
	}

	/** Consulted by {@link BossBarFeature} to strip its own F7Phase suffix once true — see the watcher-
	 *  counter field block's own doc comment on why "[P4]" showing during the Watcher fight is always
	 *  meaningless, and why this codebase only actually removes it once the fight is confirmed over rather
	 *  than unconditionally, per the user's exact wording ("remove the [P4] when all watcher mobs are dead"). */
	public static boolean isWatcherAllMobsDead() {
		return instance != null && instance.watcherAllMobsDead;
	}

	// Same real-vs-proxy-skewed ping fix as NetworkDisplayFeature's earlier this session: PlayerInfo's
	// tab-list latency can read misleadingly low/wrong on Hypixel's proxy, and this value directly drives
	// how far ahead the "current position" ping box gets projected — a wrong ping here is a real, if
	// secondary, contributor to boxes looking positioned wrong. Reuses the same always-on real ping/pong
	// round trip instead of a second ping source.
	private static float estimatePingMillis() {
		if (Minecraft.getInstance().getConnection() == null) return 0f;
		long ping = com.cokelord.skyblocksimplified.util.RealPingMonitor.getPing();
		return ping >= 0 ? ping : 0f;
	}

	private static String fmt(float value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	private static String fmtVec(Vec3 v) {
		return "(" + fmt((float) v.x) + ", " + fmt((float) v.y) + ", " + fmt((float) v.z) + ")";
	}

	// ---- getters/setters ----
	public boolean isMovePrediction() { return movePrediction; }
	public void setMovePrediction(boolean value) { movePrediction = value; }
	public boolean isPartyMoveTime() { return partyMoveTime; }
	public void setPartyMoveTime(boolean value) { partyMoveTime = value; }
	public boolean isKillTitle() { return killTitle; }
	public void setKillTitle(boolean value) { killTitle = value; }
	public boolean isBloodAssist() { return bloodAssist; }
	public void setBloodAssist(boolean value) { bloodAssist = value; }
	public int getSpawnColor() { return spawnColor; }
	public void setSpawnColor(int value) { spawnColor = value; }
	public int getFinalColor() { return finalColor; }
	public void setFinalColor(int value) { finalColor = value; }
	public int getPositionColor() { return positionColor; }
	public void setPositionColor(int value) { positionColor = value; }
	public double getBoxSize() { return boxSize; }
	public void setBoxSize(double value) { boxSize = Math.max(0.1, Math.min(1.0, value)); }
	public boolean isDrawLine() { return drawLine; }
	public void setDrawLine(boolean value) { drawLine = value; }
	public boolean isDrawTimeLeft() { return drawTimeLeft; }
	public void setDrawTimeLeft(boolean value) { drawTimeLeft = value; }
	public int getAssumeTick() { return assumeTick; }
	public void setAssumeTick(int value) { assumeTick = Math.max(35, Math.min(41, value)); }
	public int getOffsetMillis() { return offsetMillis; }
	public void setOffsetMillis(int value) { offsetMillis = Math.max(-100, Math.min(100, value)); }
	public boolean isPingOffset() { return pingOffset; }
	public void setPingOffset(boolean value) { pingOffset = value; }
	public float getManualOffsetMillis() { return manualOffsetMillis; }
	public void setManualOffsetMillis(float value) { manualOffsetMillis = Math.max(0, Math.min(300, value)); }
	public MobHighlightFeature.RenderMode getRenderMode() { return renderMode; }
	public void setRenderMode(MobHighlightFeature.RenderMode value) { renderMode = value; }
	public boolean isOcclusion3D() { return occlusion3D; }
	public void setOcclusion3D(boolean value) { occlusion3D = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("movePrediction", movePrediction);
		obj.addProperty("partyMoveTime", partyMoveTime);
		obj.addProperty("killTitle", killTitle);
		obj.addProperty("bloodAssist", bloodAssist);
		obj.addProperty("spawnColor", spawnColor);
		obj.addProperty("finalColor", finalColor);
		obj.addProperty("positionColor", positionColor);
		obj.addProperty("boxSize", boxSize);
		obj.addProperty("drawLine", drawLine);
		obj.addProperty("drawTimeLeft", drawTimeLeft);
		obj.addProperty("assumeTick", assumeTick);
		obj.addProperty("offsetMillis", offsetMillis);
		obj.addProperty("pingOffset", pingOffset);
		obj.addProperty("manualOffsetMillis", manualOffsetMillis);
		obj.addProperty("renderMode", renderMode.name());
		obj.addProperty("occlusion3D", occlusion3D);
		obj.addProperty("watcherMobsCounter", watcherMobsCounter);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("movePrediction")) movePrediction = obj.get("movePrediction").getAsBoolean();
		if (obj.has("partyMoveTime")) partyMoveTime = obj.get("partyMoveTime").getAsBoolean();
		if (obj.has("killTitle")) killTitle = obj.get("killTitle").getAsBoolean();
		if (obj.has("bloodAssist")) bloodAssist = obj.get("bloodAssist").getAsBoolean();
		if (obj.has("spawnColor")) spawnColor = obj.get("spawnColor").getAsInt();
		if (obj.has("finalColor")) finalColor = obj.get("finalColor").getAsInt();
		if (obj.has("positionColor")) positionColor = obj.get("positionColor").getAsInt();
		if (obj.has("boxSize")) boxSize = obj.get("boxSize").getAsDouble();
		if (obj.has("drawLine")) drawLine = obj.get("drawLine").getAsBoolean();
		if (obj.has("drawTimeLeft")) drawTimeLeft = obj.get("drawTimeLeft").getAsBoolean();
		if (obj.has("assumeTick")) assumeTick = obj.get("assumeTick").getAsInt();
		if (obj.has("offsetMillis")) offsetMillis = obj.get("offsetMillis").getAsInt();
		if (obj.has("pingOffset")) pingOffset = obj.get("pingOffset").getAsBoolean();
		if (obj.has("manualOffsetMillis")) manualOffsetMillis = obj.get("manualOffsetMillis").getAsFloat();
		if (obj.has("renderMode")) {
			try { renderMode = MobHighlightFeature.RenderMode.valueOf(obj.get("renderMode").getAsString()); } catch (IllegalArgumentException ignored) {}
		}
		if (obj.has("occlusion3D")) occlusion3D = obj.get("occlusion3D").getAsBoolean();
		if (obj.has("watcherMobsCounter")) watcherMobsCounter = obj.get("watcherMobsCounter").getAsBoolean();
	}

	// Watcher head-skull textures (ported verbatim from Odin's BloodCamp.kt — real Hypixel skull data).
	private static final Set<String> WATCHER_SKULLS = Set.of(
		"ewogICJ0aW1lc3RhbXAiIDogMTY5NzMwOTQxNzI1NiwKICAicHJvZmlsZUlkIiA6ICJjYjYxY2U5ODc4ZWI0NDljODA5MzliNWYxNTkwMzE1MiIsCiAgInByb2ZpbGVOYW1lIiA6ICJWb2lkZWRUcmFzaDUxODUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTY2MmI2ZmI0YjhiNTg2ZGM0Y2RmODAzYjA0NDRkOWI0MWQyNDVjZGY2NjhkYWIzOGZhNmMwNjRhZmU4ZTQ2MSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjM1MjMyMiwKICAicHJvZmlsZUlkIiA6ICI3MmY5MTdjNWQyNDU0OTk0YjlmYzQ1YjVhM2YyMjIzMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGF0X0d1eV9Jc19NZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yNzM5ZDdmNGU2NmE3ZGIyZWE2Y2Q0MTRlNGM0YmE0MWRmN2E5MjQ1NWM5ZmM0MmNhYWIwMTQ2NjVjMzY3YWQ1IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjI5MjgzNiwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9iZjZlMWU3ZWQzNjU4NmMyZDk4MDU3MDAyYmMxYWRjOTgxZTI4ODlmN2JkN2I1YjM4NTJiYzU1Y2M3ODAyMjA0IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTY5NzIzODQ0NjgxMiwKICAicHJvZmlsZUlkIiA6ICJmMjc0YzRkNjI1MDQ0ZTQxOGVmYmYwNmM3NWIyMDIxMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJIeXBpZ3NlbCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS80Y2VjNDAwMDhlMWMzMWMxOTg0ZjRkNjUwYWJiMzQxMGYyMDM3MTE5ZmQ2MjRhZmM5NTM1NjNiNzM1MTVhMDc3IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjAwOTg2NywKICAicHJvZmlsZUlkIiA6ICJiMGQ0YjI4YmMxZDc0ODg5YWYwZTg2NjFjZWU5NmFhYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lU2tpbl9vcmciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjM3ZGQxOGI1OTgzYTc2N2U1NTZkYzY0NDI0YWY0YjlhYmRiNzVkNGM5ZThiMDk3ODE4YWZiYzQzMWJmMGUwOSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNTkyNDIwNSwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9mNWYwZDc4ZmUzOGQxZDdmNzVmMDhjZGNmMmExODU1ZDZkYTAzMzdlMTE0YTNjNjNlM2JmM2M2MThiYzczMmIwIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTU1MDkyNjM2MSwKICAicHJvZmlsZUlkIiA6ICI0ZDcwNDg2ZjUwOTI0ZDMzODZiYmZjOWMxMmJhYjRhZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJzaXJGYWJpb3pzY2hlIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzUxOTY3ZGI1ZTMxOTk5MTYyNTIwMjE5MDNjZjRlOTk1MmVmN2NlYzIyMGZhYWNhMWJhNzliYWZlNTkzOGJkODAiCiAgICB9CiAgfQp9",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIxMjc1NSwKICAicHJvZmlsZUlkIiA6ICI2NGRiNmMwNTliOTk0OTM2YTY0M2QwODEwODE0ZmJkMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVTaWx2ZXJEcmVhbXMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWZkNjFlODA1NWY2ZWU5N2FiNWI2MTk2YThkN2VjOTgwNzhhYzM3ZTAwMzc2MTU3YjZiNTIwZWFhYTJmOTNhZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIzOTU4NiwKICAicHJvZmlsZUlkIiA6ICJhYWZmMDUwYTExOTk0NzM1YjEyNDVlNDk0MGFlZjY4NCIsCiAgInByb2ZpbGVOYW1lIiA6ICJMYXN0SW1tb3J0YWwiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTVjMWRjNDdhMDRjZTU3MDAxYThiNzI2ZjAxOGNkZWY0MGI3ZWE5ZDdiZDZkODM1Y2E0OTVhMGVmMTY5Zjg5MyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");

	// Blood-mob spawn-preview head textures (ported verbatim from Odin's BloodCamp.kt — data credited
	// there to "DocilElm"). Package-visible (not private) so StarredMobHighlightFeature can also match
	// against it for its own "resting Fels head on the ground" case — see that class's own doc comment.
	static final Set<String> ALLOWED_MOB_SKULLS = Set.of(
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDEwNjQwNTAsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVhNzk4NjBhY2E3OTk0MDdjMGZhYTEwYjFiYmNmNDI5OThmYWQ0ZWJjZjMxZDdhMjE0MTgwODI2YjRhYzk0ZTEifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDExODY2MzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzQ3NzQ4NzExOTBjODc4YzlhMmM0NDk2YzFlMTAyNTdjNmM0ZWExMzgwN2Q3MmMxNWQ3YWM2YWIzYTdhOWE4ZGMifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDAyMDM1NzMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2Y0NjI0YTlhOGM2OWNhMjA0NTA0YWJiMDQzZDQ3NDU2Y2Q5YjA5NzQ5YTM2MzU3NDYyMzAzZjI3NmEyMjlkNCJ9fX0=",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDExNDUyMjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2M5MTllNWI4ZDU2ZjA2MmEyMWQyMjRkZTE0YWY3NzFlMmY1NWQwOWI1OWU3YjA5OWQwOWRhYTU3NTQwYjc5Y2YiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA1MzgzODIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2E4OWY2MzAzYWY4NTg3NzYxMDkxMmRjMDRiOGIxZTg5NzI0NzUyZjBhN2VlYTA1YWI2NTQ3ZTIyODE3OWMwNmYiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA5ODk1NTgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzY3MjM3ZWRkYWViZGJiZGFhY2ZhOTEyODg1NTYwY2NkYzY1ZGE5M2I0YzNkNTEzNTMyODY4ZWMyM2JiNWI0NDgifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA0OTUwMjgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2ZmMTg0YzE5ZTcyNTYyM2QzMjgyOGEwYTRlNzQxZTg2ZjEzNWFjNjNkYmM4MjhmZjNjODQ2ODMzOGYzNjgzYiJ9fX0=",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDEwMzA3NjUsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVjY2NkNTNmNTE5MWMyOWE5ZGM4ZjAxNzBmYmRjNGU1OWU2NjQ3NmFhZTMzZGUyN2I0NjhmMWRlMWI3Y2YzYjIifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA5MTc4NzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2I1YmE3NmUwMmNhYjcyZmE3ZDhhYzU0Y2VlYzg0OTk3NmFiMGIwMGEwMTA2OGQ2OGMyNjY3NjZiZjcwYzM5OTcifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA3Njk2MTQsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FhMjNjOGNkZTI5NDNjODQyNDlkZTgzNTFiYzM1NDBiZTVmOGFmYWFiYThiMmNiMDMyZmM1YWNhZDc4YTI2OWIifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA4MTg4MDMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzkxNzFmMzViOGY1MDgxNDJiZDhjNjU0MTdkMGYzMjQxNTNhYjkxNDc3MzllZTRkMTBkZWE3MzNjYzgwZWFhMjAifX19",
		"eyJ0aW1lc3RhbXAiOjE1ODYwNDA5NTY0MjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzdkMTJiMmFkZTQxM2E2Y2Q3Y2NhM2M5NWU5NjFiYTlmMGFlNzE2NWZhNDFmYzdiNWQ1ZjA5NGEwMTI0MGM2MDkifX19",
		"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTZjM2UzMWNmYzY2NzMzMjc1YzQyZmNmYjVkOWE0NDM0MmQ2NDNiNTVjZDE0YzljNzdkMjczYTIzNTIifX19",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzE2OTIxMSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODQyMWJhNWI4ZTM1NzNlZjk3YmViNWI0MGUxNWQxNWIyMGYzMDYzMWM0YzUzMzBjM2RlZGEzMDQ3ZGYwZTkyIgogICAgfQogIH0KfQ==",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzExMjUwMCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQyMjc3MmY3NjkwNDVmZGM1YmU4MTlhZDY4YjAxYTk3YWMwNGM2MDg4NmQyY2E3YWZlZTM5YjI4MmY3YTM4MyIKICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzM4Njc5NCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQ2N2Y5N2Q3ZjgyMTcyOWJlYjM0YTgyYzNmMTM1OTJiNDA0MzlmZTUyNDhlNzI1NzZmZGU3YWExODBiZjc3IgogICAgfQogIH0KfQ==",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzIxNTkwNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmIzOTczYTc1MmIyNGEyZjNhYmIwMDM0MjdmNmRiZTZjYTNhNjFkYjBhMWJjZjM1MWM2ZWFiMjdlYzI3ZTUwIgogICAgfQogIH0KfQ==",
		"eyJ0aW1lc3RhbXAiOjE1NzQ0MTkzMTAxNjQsInByb2ZpbGVJZCI6Ijc1MTQ0NDgxOTFlNjQ1NDY4Yzk3MzlhNmUzOTU3YmViIiwicHJvZmlsZU5hbWUiOiJUaGFua3NNb2phbmciLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzEyNzE2ZWNiZjViOGRhMDBiMDVmMzE2ZWM2YWY2MWU4YmQwMjgwNWIyMWViOGU0NDAxNTE0NjhkYzY1NjU0OWMifX19",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzAyODAxNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzI2MDMyNTE3MWE3YmE4NDYwODMwYzBlZWE1MTVjNzU3YTY2NWU1YjE2YTE0MjA3YmExYTMxODI3NTJiZWU4NyIKICAgIH0KICB9Cn0=",
		"ewogICJ0aW1lc3RhbXAiIDogMTU5NTQyODIyMDAyMCwKICAicHJvZmlsZUlkIiA6ICJkYTQ5OGFjNGU5Mzc0ZTVjYjYxMjdiMzgwODU1Nzk4MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJOaXRyb2hvbGljXzIiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjJkOGZkM2FhNTYxN2IxZGFjMGFhZTljODFmNmRkNzBhZDkzYTU5OTQyZjQ2MGQyN2U0ZDU1YTVjYjg5MThlOCIKICAgIH0KICB9Cn0=",
		"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTZmYzg1NGJiODRjZjRiNzY5NzI5Nzk3M2UwMmI3OWJjMTA2OTg0NjBiNTFhNjM5YzYwZTVlNDE3NzM0ZTExIn19fQ==",
		"ewogICJ0aW1lc3RhbXAiIDogMTU4OTc5MzA2ODgzOSwKICAicHJvZmlsZUlkIiA6ICIyYzEwNjRmY2Q5MTc0MjgyODRlM2JmN2ZhYTdlM2UxYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJOYWVtZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS83ZGU3YmJiZGYyMmJmZTE3OTgwZDRlMjA2ODdlMzg2ZjExZDU5ZWUxZGI2ZjhiNDc2MjM5MWI3OWE1YWM1MzJkIgogICAgfQogIH0KfQ==",
		"eyJ0aW1lc3RhbXAiOjE1OTg5NzcyNTkzNTcsInByb2ZpbGVJZCI6ImU3OTNiMmNhN2EyZjQxMjZhMDk4MDkyZDdjOTk0MTdiIiwicHJvZmlsZU5hbWUiOiJUaGVfSG9zdGVyX01hbiIsInNpZ25hdHVyZVJlcXVpcmVkIjp0cnVlLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEwMDdjNWI3MTE0YWJlYzczNDIwNmQ0ZmM2MTNkYTRmM2EwZTk5ZjcxZmY5NDljZWRhZGM5OTA3OTEzNWEwYiJ9fX0="
	);

	@Override
	public String getDescription() {
		return "F7 boss-fight helper for the Blood Camp room: predicts safe move timing and can highlight the correct camp blocks.";
	}
}
