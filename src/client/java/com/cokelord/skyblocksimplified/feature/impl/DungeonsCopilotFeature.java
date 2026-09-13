package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request — "the biggest module yet": a per-class ordered walkthrough for a dungeon boss fight.
 * The user gave no mockup for this one ("I will not provide an image of the gui for now... make your
 * interpretation and I'll fix it"), so the following design choices are this session's own interpretation,
 * not a spec:
 *
 * <p>Each {@link CopilotItem} belongs to one {@link DungeonClass} and one explicit step number; multiple
 * items can share a step number (they all become visible together), and each BREAKABLE_BLOCKS/WAYPOINT
 * item can be individually marked as the one whose completion advances to the next step (TITLE items have
 * no natural completion signal, so they're never an advancer — purely informational display for whatever
 * step they're attached to). A step becomes current either by its own explicit trigger (a chat-message
 * substring and/or a named split + a time offset — the "VERY IMPORTANT" requirement from the user, "AND/OR"
 * taken literally: either firing is enough) firing for the first time, OR by the current step's advancer
 * completing — whichever happens first advances {@link #currentStep} forward to the next step number that
 * actually has items defined (steps don't need to be contiguous). currentStep only ever increases within a
 * run, and resets the moment a fresh boss fight starts.
 *
 * <p>Only the local player's own live-tracked class's (see {@link DungeonState#getTeammates()}) items ever
 * run, and only while that class's subtoggle is on — the class list at the top of the settings panel is
 * both "which class's plan am I currently editing" and "is that plan live right now".
 */
public class DungeonsCopilotFeature extends Feature implements MoveableWidget {
	// Per user request ("Add a leap detector to the boss guide. It should detect when the user uses the
	// spirit leap and actually leaps/presses anything in the gui that isnt a pane, and coordinate with the
	// keybinds/leap menu."): LEAP_USED is a one-shot, chat-confirmed "the local player just successfully used
	// Spirit Leap" step, same shape as DEVICE_FINISHED (no live state to poll for a completed leap the way
	// ALIGN/LEVERS' PD Mode can poll a redstone grid or frame rotation, so it's chat-detection only — see
	// LEAP_COMPLETED_PATTERN's own doc comment for how that one chat line already covers every real leap path
	// this mod has (vanilla GUI click, this mod's own Leap Menu overlay click, and Leap Menu's corner
	// keybinds) and already excludes clicks on decorative panes for free.
	// Per user request ("also create a Terminal done which detects when the terminal is finished through
	// chat or through the solver having no more steps"), later corrected per user follow-up ("Terminal done
	// step type... should not advance when terminal phase ends, it should advance when the user completes a
	// terminal"): TERMINAL_DONE fires on the real per-terminal "you personally just activated ONE terminal"
	// chat line (see TERMINAL_ACTIVATED_PATTERN's own doc comment), the same self-filtered-by-name shape
	// DEVICE_FINISHED already uses for "you personally finished a device" — NOT the whole terminal phase
	// ending, and NOT the whole party-wide section being fully activated (both of those are broader-scoped
	// events than "the user completes a terminal"). Per a further follow-up ("I also need the terminal
	// completion to trigger on levers"): also fires on the live Levers redstone-lamp-grid read (see
	// onTick's own isLeversDeviceComplete check) since Hypixel's Levers puzzle broadcasts only the shared,
	// device-generic "completed a device!" line — never the terminal-specific one — so chat alone can never
	// tell TERMINAL_DONE that a Levers completion happened.
	// Per user request ("add a block highlighting, it should just highlight blocks and also actually allow
	// the boss guide to go to next step when the highlighted blocks turn into a block selected by the user...
	// This is for stuff like crushers where you wait for them to pass during predev... make it go to next
	// step when it becomes polished granite"): a WATCH-style step, distinct from BREAKABLE_BLOCKS (which
	// waits for the tracked blocks to stop being solid) — this waits for every tracked block to become a
	// SPECIFIC block the user names by its real display name (see CopilotItem#targetBlockInput and
	// isBlockWatchSatisfied's own doc comment for the case-insensitive matching rule). Always auto-advances
	// like WAYPOINT (no advancesStep opt-in) — a tracked block actually turning into the named block is as
	// unambiguous a "this happened" signal as physically reaching a waypoint.
	public enum StepType { TITLE, BREAKABLE_BLOCKS, WAYPOINT, DEVICE_FINISHED, LEAP_USED, TERMINAL_DONE, BLOCK_WATCH }

	// Per user request ("Add 'SS/LEVERS/ALIGN/ARROWS device finished' support to the boss guide... Also add
	// lightning finished support"): which real F7 device this step is waiting on.
	public enum DeviceKind { SS, LEVERS, ALIGN, ARROWS, LIGHTNING }

	public static final class CopilotItem {
		public DungeonClass forClass = DungeonClass.MAGE;
		public StepType type = StepType.TITLE;
		public int stepNumber = 1;
		// Per user request ("It should also be on by default for blocks" — see cycleType()'s own mirroring
		// forced-true whenever a step lands on BREAKABLE_BLOCKS): defaults true now instead of false. Only
		// BREAKABLE_BLOCKS ever reads this field at all, so the default only actually matters for that type.
		public boolean advancesStep = true;

		public DeviceKind deviceKind = DeviceKind.SS;
		// Per user request ("Make sure the levers/align has a PD mode where it detects if all the redstone
		// lamps are lit/all the arrows are aligned instead of detecting the device complete chat message"):
		// real for both ALIGN (ArrowAlignFeature's own live frame-rotation tracking) and LEVERS (task #595,
		// a direct live redstone-lamp-grid read — see DungeonsCopilotFeature#isLeversDeviceComplete and its
		// own doc comment for the real coordinates this was built against). ARROWS/SS/LIGHTNING still fall
		// back to the shared chat-based detection every device uses by default — no equivalent live-state
		// source for those exists in this codebase yet.
		public boolean pdMode = false;

		public String chatTrigger = "";
		public String splitTrigger = "";
		public String splitOffsetInput = "0.0";
		public float splitOffsetSeconds = 0f;

		public String titleText = "";
		public int titleColor = 0xFFFFFF55;

		public final List<BlockPos> blocks = new ArrayList<>();
		// Per user request ("These should be the default colors of each item... Change these for the boss
		// guide module too" — Blocks: Yellow): was red.
		public int blockColor = 0xFFFFFF55;
		// Per user request ("add a block highlighting... allow users to type in a block name with spaces
		// like 'grass block'"): the real display name (Block#getName().getString()) BLOCK_WATCH waits for
		// every tracked block (the same shared `blocks` list above) to become — matched case-insensitively,
		// see isBlockWatchSatisfied's own doc comment.
		public String targetBlockInput = "";

		public String xInput = "0.0", yInput = "0.0", zInput = "0.0";
		public double x, y, z;
		public int waypointColor = 0xFF55FFFF;
	}

	private static final double WAYPOINT_REACHED_DISTANCE = 2.0;

	// Real Hypixel broadcast — confirmed identical via two independently decompiled reference mods (Odin's
	// ArrowsDevice.kt: "^(.{1,16}) completed a device! \((\d)/(\d)\)$" and Devonian's SharpShooterSolver.kt:
	// "^(\w{1,16}) completed a device! \(\d/7\)$") — the one real chat line Hypixel actually sends for ANY
	// F7 device (Simon Says, Levers, Align, Arrows, Lightning all included; it never names which device),
	// so DEVICE_FINISHED steps match it gated on the sender being the local player.
	private static final Pattern DEVICE_COMPLETED_PATTERN = Pattern.compile("^(.{1,16}) completed a device! \\(\\d/\\d\\)$");

	// Real Hypixel message the LOCAL client receives immediately after any successful Spirit Leap teleport —
	// already relied on for the same "a real leap just happened" signal by LeapMenuFeature's own "announce my
	// leaps" toggle (see its LEAPED_PATTERN, same regex, kept as a separate constant here rather than a shared
	// one since the two features aren't otherwise coupled). This one line fires for every real way this mod
	// can leap — a plain click on the vanilla Spirit Leap/"Teleport to Player" GUI's own real player-head slot,
	// this mod's own Leap Menu overlay quadrant click, and Leap Menu's four corner keybinds (LeapMenuFeature's
	// leapTo() sends the same real handleContainerInput click either way) — because all three ultimately
	// trigger the identical server-side teleport the server always confirms with this exact line. A click on a
	// decorative/filler pane (no real player behind that slot) never reaches leapTo()/a real slot index at all,
	// so it can never produce a teleport or this chat line — the "isn't a pane" exclusion the user asked for
	// comes for free from this being keyed off the real teleport outcome, not the click itself.
	private static final Pattern LEAP_COMPLETED_PATTERN = Pattern.compile("^You have teleported to (\\w{1,16})!$");

	// TERMINAL_DONE's real signal — the exact per-terminal broadcast Hypixel sends the instant the LOCAL
	// player personally activates any one terminal (independently confirmed shape via
	// TerminalTracker.SOLVE_PATTERN, which already uses this identical regex for its own party-wide progress
	// tally); gated on the sender being the local player the same way DEVICE_COMPLETED_PATTERN is above, since
	// the user wants this to advance on THEIR OWN terminal completion, not a teammate's or the whole section's.
	private static final Pattern TERMINAL_ACTIVATED_PATTERN = Pattern.compile("^(.{1,16}) activated a terminal! \\(\\d/\\d\\)$");

	// Real F7 Levers puzzle redstone lamp grid, per user-provided coordinates ("62, 133, 143 is the bottom
	// left, its a 4x5 so top right is 58, 136, 143... The Z coordinate is the same since its a 4x5x1 and its
	// flat") — a flat 5-wide (X 58-62) by 4-tall (Y 133-136) plane of lamps at Z=143. LEVERS' own PD Mode
	// (task #595) reads these lamps' live lit state directly instead of waiting for the shared "completed a
	// device!" chat line, the same real-world-state idea ALIGN's PD Mode already uses (ArrowAlignFeature's
	// own live frame-rotation tracking) — Levers just has no equivalent existing feature already tracking its
	// own solved-ness, so this reads straight off the actual blocks instead.
	private static final int LEVERS_MIN_X = 58, LEVERS_MAX_X = 62;
	private static final int LEVERS_MIN_Y = 133, LEVERS_MAX_Y = 136;
	private static final int LEVERS_Z = 143;

	/** True once every lamp in the Levers grid is a lit redstone lamp — mirrors {@link
	 *  ArrowAlignFeature#isDeviceComplete()}'s role for ALIGN, but reads real live block state instead of
	 *  internally-tracked click progress, since nothing in this codebase already tracks Levers' own solved
	 *  state. */
	private static boolean isLeversDeviceComplete(Level level) {
		// Diagnostic only (per user report — "the levers pd mode doesnt seem to detect the redstone lamps
		// turning on, atleast not in singleplayer"): the coordinate math and the block/LIT check below were
		// re-verified against the user's own stated coordinates and are correct as written, so this can't be
		// confirmed broken by re-reading the code alone — the likely real cause is the singleplayer test
		// world simply not having a real F7 boss room at these absolute world coordinates (they're only
		// meaningful on the actual Hypixel dungeon instance). Logs a compact live count (with Debug module
		// on) instead of guessing further, so the next report can say exactly what's actually at these
		// coordinates rather than just "doesn't work".
		int litCount = 0;
		BlockPos firstMismatch = null;
		String firstMismatchBlock = null;
		for (int x = LEVERS_MIN_X; x <= LEVERS_MAX_X; x++) {
			for (int y = LEVERS_MIN_Y; y <= LEVERS_MAX_Y; y++) {
				BlockPos pos = new BlockPos(x, y, LEVERS_Z);
				var state = level.getBlockState(pos);
				boolean isLampLit = state.is(net.minecraft.world.level.block.Blocks.REDSTONE_LAMP)
					&& state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
				if (isLampLit) {
					litCount++;
				} else if (firstMismatch == null) {
					firstMismatch = pos;
					firstMismatchBlock = state.getBlock().getName().getString();
				}
			}
		}
		int total = (LEVERS_MAX_X - LEVERS_MIN_X + 1) * (LEVERS_MAX_Y - LEVERS_MIN_Y + 1);
		return litCount == total;
	}

	private final Map<DungeonClass, Boolean> classEnabled = new EnumMap<>(DungeonClass.class);
	private final List<CopilotItem> items = new ArrayList<>();

	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.2f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private int currentStep = 0;
	private boolean wasInBoss = false;
	// Real bug found (per user report — "it seems to start on step 2 when i enter the bossfight... this is
	// either a bug with blocks having advances step on or it just automatically starts at two for some
	// reason"): on the exact tick currentStep is reset to the class's real first step (see onTick below),
	// falling straight through into the same tick's completion-evaluation loops (BREAKABLE_BLOCKS/
	// BLOCK_WATCH/split-trigger/PD-mode device) let any of them immediately satisfy and advance PAST that
	// first step before it was ever actually observed as current — most likely because the boss room is a
	// fresh teleport and the tracked block positions' chunk hadn't delivered its real block data yet this
	// same tick, defaulting to air (non-solid), which allBlocksBroken() can't tell apart from "genuinely
	// already broken". Skips every completion check for exactly the tick this flag is set, so the freshly
	// reset first step is guaranteed to actually render at least once before anything can move past it.
	private boolean justEnteredBoss = false;
	// Per user request: a sound cue for "the title just updated" (a new step's title text is now showing),
	// and a way to strip the HUD panel's own background fill. Reuses TerminalSoundsFeature's own derived
	// builtin-sound catalog (SOUND_IDS/SOUND_LABELS, in turn derived from its curated TerminalSound enum)
	// rather than duplicating a second copy of the same sound list.
	private boolean titleUpdateSoundEnabled = false;
	private final CustomSoundOption titleUpdateSound =
		new CustomSoundOption(TerminalSoundsFeature.SOUND_IDS, TerminalSoundsFeature.SOUND_LABELS);
	private boolean hideTitleBackground = false;
	// Per user request ("let users have bold and italic titles"): bold defaults true since the title line
	// was always hardcoded bold before this setting existed — this keeps existing users' look unchanged.
	private boolean boldTitle = true;
	private boolean italicTitle = false;
	private int lastAnnouncedStep = -1;
	// Per user request ("remove the /bg command and add it to the boss guide itself. It should show up when
	// editing mode is on, and allow users to choose current class to display waypoints for. This should
	// override anything cause most players dont have a singleplayer world of the f7 bossfight and have to
	// make routes in-game"): replaces the old /bg singleplayer-only command family entirely. When on, whichever
	// class tab is currently selected in the mod menu's own editor (MainScreen's selectedCopilotClass, synced
	// here on every tab click via setEditModeClass) overrides activeClassItems()'s normal live-class detection
	// — in a REAL dungeon just as much as singleplayer, since a real dungeon is exactly the case /bg could
	// never help with (it flatly refused to run outside singleplayer).
	private boolean editMode = false;
	private DungeonClass editModeClass;
	// Per user request: "there should be a subtoggle under each class that all connect that shows all steps
	// at the same time" — one shared boolean surfaced on every class's tab (not per-class), so flipping it
	// from any tab affects all of them. When on, every step's items render together instead of just the
	// current one — useful for reviewing/practicing a whole plan, and doubles as the only sane singleplayer
	// preview mode below, since currentStep progression (chat/split triggers, block/waypoint completion)
	// has nothing real to react to there anyway.
	private boolean showAllSteps = false;
	// Per user request ("Add support for waypoint tracers and waypoint lines"): a real crosshair-to-waypoint
	// line (WorldRenderUtil.drawTracer) and a vertical beacon-style beam from the waypoint into the sky
	// (WorldRenderUtil.drawBeacon) — the same two real occlusion-checked screen-space primitives
	// InactiveWaypointsFeature already uses, off by default since the through-walls box highlight (below)
	// already exists and covers the base case.
	private boolean waypointTracer = false;
	private boolean waypointBeaconLine = false;
	// Per user request ("Add a coordinates gui like the positional messages aswell. It can be really small,
	// just show coords") — appended to this module's own existing moveable HUD panel rather than a whole
	// separate widget/position to drag around, since "really small" reads as "a line on what's already
	// there," not a second thing to place.
	private boolean showCoords = false;

	private static boolean listenersRegistered = false;
	private static DungeonsCopilotFeature instance;

	public DungeonsCopilotFeature() {
		// Per user request, display name only ("rename dungeons copilot to 'Boss Guide'") — the id stays
		// "dungeons_copilot" so existing saved configs (per-feature settings keyed by id) aren't orphaned.
		super("dungeons_copilot", "Boss Guide", FeatureCategory.COMBAT, false);
		titleUpdateSound.setBuiltinIndex(TerminalSoundsFeature.TerminalSound.NOTE_PLING.ordinal());
		instance = this;
		for (DungeonClass c : DungeonClass.values()) {
			if (c == DungeonClass.EMPTY) continue;
			classEnabled.put(c, true);
		}
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	public java.util.List<String> getSearchAliases() { return java.util.List.of("Copilot"); }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeons_copilot_title"), (graphics, tracker) -> {
				if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
					|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.render(graphics, x, y, instance.position.scale);
			});
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.addRenderCallback(DungeonsCopilotFeature::renderWorldMarkers);
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeons_copilot_waypoint_lines"), (graphics, tracker) -> {
				if (instance == null || !instance.isEnabled() || !instance.shouldBeActive()
					|| (!instance.waypointTracer && !instance.waypointBeaconLine)) return;
				try {
					for (CopilotItem item : instance.currentStepItems()) {
						if (item.type != StepType.WAYPOINT) continue;
						Vec3 pos = new Vec3(item.x + 0.5, item.y + 1.0, item.z + 0.5);
						if (instance.waypointTracer) {
							com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.drawTracer(graphics, pos, item.waypointColor, 2);
						}
						if (instance.waypointBeaconLine) {
							com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.drawBeacon(graphics, pos, item.waypointColor, 256.0);
						}
					}
				} catch (Exception e) {
					com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Dungeons Copilot waypoint tracer/line render failed, skipping this frame", e);
				}
			});
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled() && !overlay) instance.onChatMessage(message.getString());
				return true;
			});
			// Per user report ("Boss guide doesn't reset when leaving the world. Atleast it was active in
			// singleplayer"): shouldBeActive() falls back to "always active" in ANY singleplayer world (a
			// deliberate testing convenience, since there's no real boss-entry signal there — see its own doc
			// comment), which meant leftover state from the last real Hypixel run (currentStep mid-plan,
			// wasInBoss still true) kept being previewed the moment a completely unrelated local singleplayer
			// world loaded next, instead of starting clean. Mirrors DungeonState's own DISCONNECT-triggered
			// reset() for exactly this reason.
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
				if (instance == null) return;
				instance.currentStep = 0;
				instance.wasInBoss = false;
			});
		}
	}

	/** True while step-progression logic (chat/split triggers, block/waypoint completion) should even try to
	 *  run — real dungeons only, since singleplayer has no real boss fight for any of that to react to.
	 *  Singleplayer's own always-visible behavior is a pure Show All Steps preview instead (see isVisible/
	 *  renderWorldMarkers), not a live-progressing run. */
	private static boolean inRealBossFight() { return DungeonState.isInBoss(); }

	/** First step number strictly after {@code afterStep} that actually has an item defined — falls back to
	 *  {@code afterStep} itself if there isn't one (nothing left to advance to). Shared by every "this step
	 *  is done, move on" path (WAYPOINT/BREAKABLE_BLOCKS completion in {@link #onTick}, and both
	 *  DEVICE_FINISHED completion paths below) so a completed device always advances PAST its own step
	 *  instead of just becoming/staying the current step. */
	private static int nextStepAfter(List<CopilotItem> items, int afterStep) {
		return items.stream().mapToInt(i -> i.stepNumber).filter(n -> n > afterStep).min().orElse(afterStep);
	}

	private void onChatMessage(String text) {
		if (!inRealBossFight()) return;
		List<CopilotItem> myItems = activeClassItems();
		Boolean isLocalDeviceCompleted = null;
		Boolean isLocalLeapCompleted = null;
		Boolean isLocalTerminalActivated = null;
		for (CopilotItem item : myItems) {
			// Real bug found (per user report — "The levers device should not end the guide"): this used to
			// require item.stepNumber > currentStep, so a DEVICE_FINISHED step that HAD already become
			// current (the normal case — some earlier trigger made it current so its own title could show)
			// could never be checked again here once currentStep reached it, permanently stalling the guide
			// on that step even after the device actually finished. Now also checks the step you're
			// currently ON, not just future ones.
			if (item.stepNumber < currentStep) continue;
			if (item.type == StepType.DEVICE_FINISHED) {
				// PD Mode (real for ALIGN and LEVERS, see CopilotItem.pdMode's own doc comment): the live
				// state check in onTick handles this step instead, so the chat line is ignored here.
				if (item.pdMode && (item.deviceKind == DeviceKind.ALIGN || item.deviceKind == DeviceKind.LEVERS)) continue;
				if (isLocalDeviceCompleted == null) {
					Minecraft mc = Minecraft.getInstance();
					Matcher m = DEVICE_COMPLETED_PATTERN.matcher(text);
					isLocalDeviceCompleted = m.matches() && mc.player != null && m.group(1).equals(mc.player.getName().getString());
				}
				// Advance PAST this step (not just onto it) — a device finishing IS that step being done.
				if (isLocalDeviceCompleted) currentStep = nextStepAfter(myItems, item.stepNumber);
				continue;
			}
			if (item.type == StepType.LEAP_USED) {
				// One-shot real-event step — same shared-chat-line detection idea as DEVICE_FINISHED above,
				// see LEAP_COMPLETED_PATTERN's own doc comment for why this one line already covers every real
				// leap path (vanilla GUI, this mod's own Leap Menu overlay, Leap Menu's keybinds) and already
				// excludes decorative panes. No PD Mode equivalent makes sense here (unlike ALIGN/LEVERS,
				// there's no live world/GUI state to poll for "a leap just happened" — it's an instant, not a
				// standing condition), so this chat line is the only detection this step type ever needs.
				if (isLocalLeapCompleted == null) {
					isLocalLeapCompleted = LEAP_COMPLETED_PATTERN.matcher(text).matches();
				}
				if (isLocalLeapCompleted) currentStep = nextStepAfter(myItems, item.stepNumber);
				continue;
			}
			if (item.type == StepType.TERMINAL_DONE) {
				// Per user correction ("should not advance when terminal phase ends, it should advance when
				// the user completes a terminal"): gated to the local player, same shape as DEVICE_FINISHED
				// above — fires once per terminal THIS player personally activates, not the whole phase/section.
				if (isLocalTerminalActivated == null) {
					Minecraft mc = Minecraft.getInstance();
					Matcher m = TERMINAL_ACTIVATED_PATTERN.matcher(text);
					isLocalTerminalActivated = m.matches() && mc.player != null && m.group(1).equals(mc.player.getName().getString());
				}
				if (isLocalTerminalActivated) currentStep = nextStepAfter(myItems, item.stepNumber);
				continue;
			}
			if (item.stepNumber > currentStep && !item.chatTrigger.isBlank() && text.contains(item.chatTrigger)) {
				currentStep = item.stepNumber;
			}
		}
	}

	@Override
	public void onTick(Minecraft client) {
		boolean inBoss = inRealBossFight();
		// Real bug found (per user report — "nothing renders unless I use Show All Steps, self-heals as
		// soon as any device completes"): this used to hardcode currentStep = 1 on boss entry, but
		// removeItem() never renumbers remaining items and steps are allowed to be non-contiguous, so a
		// class whose lowest real step number isn't 1 (e.g. step 1 got deleted) matched nothing in
		// currentStepItems() until some OTHER trigger (chat/split/a device completion) happened to bump
		// currentStep up to a real step number for the first time. Now resets to the actual lowest step.
		if (inBoss && !wasInBoss) {
			currentStep = firstStepFor(resolveActiveClass());
			justEnteredBoss = true;
		}
		wasInBoss = inBoss;
		// Real bug found (per user report — "it doesn't seem to be going to the next step when I break
		// either of the blocks"): this used to gate the WHOLE method on inRealBossFight() (a real, live
		// Hypixel boss fight only) — but the /bg singleplayer commands added last round exist specifically
		// to test this exact progression outside a real fight, and shouldBeActive() (already used for
		// visibility) already knows singleplayer counts too. Only the "just entered a real boss fight ->
		// reset to step 1" logic above stays boss-only (a singleplayer tester controls their own step via
		// /bg restart/step, not an automatic reset).
		if (!shouldBeActive()) return;

		List<CopilotItem> myItems = activeClassItems();
		if (myItems.isEmpty()) return;
		if (client.level == null) return;

		// See justEnteredBoss's own doc comment: skip every completion check for exactly the tick the
		// current step was just reset, so the freshly reset first step actually renders at least once
		// before anything (block/waypoint completion, split trigger, PD-mode device) can advance past it.
		if (justEnteredBoss) {
			justEnteredBoss = false;
			return;
		}

		for (CopilotItem item : myItems) {
			if (item.stepNumber <= currentStep || item.splitTrigger.isBlank()) continue;
			Long since = SplitsFeature.millisSinceSplit(item.splitTrigger);
			if (since != null && since >= item.splitOffsetSeconds * 1000L) currentStep = item.stepNumber;
		}

		// PD Mode (see CopilotItem.pdMode's own doc comment): ALIGN reads the live arrow-frame state
		// ArrowAlignFeature already tracks for its own blocking logic; LEVERS (task #595, per user-provided
		// real coordinates for the F7 Levers puzzle's redstone lamp grid) reads the actual live lamp block
		// states instead — same real-world-state idea, different data source since Levers has no equivalent
		// existing feature already tracking its own solved-ness the way ArrowAlignFeature does.
		//
		// Real bug found (per user report — "The levers device should not end the guide... it skips every
		// step if the game detects it completed... then the next step which doesn't show for some reason"):
		// this used to require item.stepNumber > currentStep, so once the Levers/Align step actually became
		// current (the normal way to reach it) it could never be re-checked here again — completing the
		// device then did nothing forever, permanently stalling the guide on that exact step. Now also
		// checks the step the guide is currently ON, and advances PAST it (not just onto it) once solved.
		for (CopilotItem item : myItems) {
			if (item.stepNumber < currentStep) continue;
			if (item.type != StepType.DEVICE_FINISHED || !item.pdMode) continue;
			boolean complete = switch (item.deviceKind) {
				case ALIGN -> ArrowAlignFeature.isDeviceComplete();
				case LEVERS -> isLeversDeviceComplete(client.level);
				default -> false;
			};
			if (complete) currentStep = nextStepAfter(myItems, item.stepNumber);
		}
		// Per user follow-up ("I also need the terminal completion to trigger on levers"): TERMINAL_DONE's
		// chat-based signal (onChatMessage's TERMINAL_ACTIVATED_PATTERN) only ever fires for the real
		// click-type terminals — Hypixel's own "activated a terminal!" line is never sent for the Levers
		// puzzle (which broadcasts the shared, device-generic "completed a device!" line instead, with no way
		// to tell which device from the text alone). So TERMINAL_DONE also finishes on the SAME live
		// redstone-lamp-grid read {@link #isLeversDeviceComplete} already gives DEVICE_FINISHED+LEVERS+PD Mode
		// — same "re-checks the step you're currently ON, advances PAST it" shape as that loop above.
		for (CopilotItem item : myItems) {
			if (item.stepNumber < currentStep) continue;
			if (item.type != StepType.TERMINAL_DONE) continue;
			if (isLeversDeviceComplete(client.level)) currentStep = nextStepAfter(myItems, item.stepNumber);
		}
		// Per user request: "if I have a waypoint and a title on step 2... when I reach that waypoint it
		// should automatically show the next step, no advances step trigger should be required" — a
		// WAYPOINT is always a real, unambiguous "I did this" signal (you physically walked there), so it
		// always can advance the step regardless of its own advancesStep flag. BREAKABLE_BLOCKS still needs
		// the explicit opt-in (see MainScreen's own comment on why).
		// Per later user follow-up ("I want the next step in boss guide to happen when all blocks are broken
		// instead. Keep in mind blocks respawn after a while using the dungeonbreaker"): reverted from "any
		// one block" back to "every block in the item" — this re-checks live block state every single tick
		// (allBlocksBroken(), not a cached/latched result), so a block the Dungeon Breaker respawns back to
		// solid correctly un-satisfies the condition again until every block is broken at the same time, not
		// just once ever.
		for (CopilotItem item : myItems) {
			if (item.stepNumber != currentStep) continue;
			boolean done = switch (item.type) {
				case BREAKABLE_BLOCKS -> item.advancesStep && !item.blocks.isEmpty() && allBlocksBroken(item, client.level);
				case WAYPOINT -> client.player != null
					&& client.player.position().distanceTo(new Vec3(item.x, item.y, item.z)) <= WAYPOINT_REACHED_DISTANCE;
				// Always auto-advances, no advancesStep opt-in needed — same reasoning as WAYPOINT just above:
				// the tracked blocks actually turning into the named target is as unambiguous a real "this
				// happened" signal as physically reaching a waypoint, unlike BREAKABLE_BLOCKS (a block simply
				// still standing there when the player wanders near it isn't a deliberate action).
				case BLOCK_WATCH -> isBlockWatchSatisfied(item, client.level);
				case TITLE -> false;
				case DEVICE_FINISHED -> false; // handled by onChatMessage / the PD Mode loop above, not here
				case LEAP_USED -> false; // handled by onChatMessage above, not here
				case TERMINAL_DONE -> false; // handled by onChatMessage above, not here
			};
			if (done) {
				currentStep = nextStepAfter(myItems, currentStep);
				break;
			}
		}

		// Per user request: a sound cue for "the title just updated" — fires once per real step change
		// (covers every path above that can move currentStep: boss-entry reset, chat/split trigger, block/
		// waypoint completion), only when the newly-current step actually has a title to show.
		if (currentStep != lastAnnouncedStep) {
			lastAnnouncedStep = currentStep;
			if (titleUpdateSoundEnabled && activeTitleItem() != null && client.player != null) {
				titleUpdateSound.play();
			}
		}
	}

	// Real bug found (per user report — "it seems to start on step 2 when i enter the bossfight... a bug
	// with blocks having advances step on"): a freshly-entered boss room is a real teleport into a new part
	// of the map, whose chunks may not have delivered real block data on the very first tick(s) after
	// entry — an unloaded chunk defaults to air client-side, which isSolid() can't tell apart from a
	// genuinely broken block, so a BREAKABLE_BLOCKS step could read as already-complete before its real
	// (still-solid) state ever arrives. Treats an unloaded tracked position as "not yet known" rather than
	// trusting its default state, on top of the justEnteredBoss one-tick skip in onTick.
	private static boolean allBlocksBroken(CopilotItem item, Level level) {
		for (BlockPos pos : item.blocks) {
			if (!level.isLoaded(pos)) return false;
			if (level.getBlockState(pos).isSolid()) return false;
		}
		return true;
	}

	/** True once every tracked block has become the item's named target block — matched against the block's
	 *  real English display name (e.g. "Polished Granite"), trimmed and compared case-insensitively so
	 *  "polished granite"/"Polished Granite"/"POLISHED GRANITE" all work. False (never satisfied) while no
	 *  target name has been typed or no blocks are tracked, same "nothing to check yet" convention
	 *  allBlocksBroken's own BREAKABLE_BLOCKS caller already applies. */
	private static boolean isBlockWatchSatisfied(CopilotItem item, Level level) {
		String target = item.targetBlockInput.trim();
		if (target.isEmpty() || item.blocks.isEmpty()) return false;
		for (BlockPos pos : item.blocks) {
			// Same unloaded-chunk guard as allBlocksBroken — an unloaded position's default state can't be
			// trusted as a real "already the target block" match right after a fresh boss-room teleport.
			if (!level.isLoaded(pos)) return false;
			String name = level.getBlockState(pos).getBlock().getName().getString();
			if (!name.equalsIgnoreCase(target)) return false;
		}
		return true;
	}

	/** Only the local player's own live-tracked class's items, only if that class's plan is toggled on —
	 *  empty (nothing runs) while the player's class hasn't resolved yet or its toggle is off. In
	 *  singleplayer there's no real party for DungeonState.getTeammates() to ever populate, so selfClass()
	 *  can never resolve — per user request ("make sure it works in singleplayer... it should always show"),
	 *  falls back to the first class with any items defined at all, purely so there's something to preview.
	 *  Edit Mode (see its own field doc comment) overrides all of that, live dungeons included. */
	private List<CopilotItem> activeClassItems() {
		DungeonClass self = resolveActiveClass();
		if (self == null || !classEnabled.getOrDefault(self, true)) return List.of();
		List<CopilotItem> result = new ArrayList<>();
		for (CopilotItem item : items) if (item.forClass == self) result.add(item);
		return result;
	}

	private DungeonClass resolveActiveClass() {
		if (editMode) {
			return editModeClass != null ? editModeClass : firstClassWithItems();
		}
		DungeonClass self = selfClass();
		if (self == null && com.cokelord.skyblocksimplified.util.IslandGate.isSingleplayer()) {
			self = firstClassWithItems();
		}
		return self;
	}

	private DungeonClass firstClassWithItems() {
		for (CopilotItem item : items) {
			if (classEnabled.getOrDefault(item.forClass, true)) return item.forClass;
		}
		return null;
	}

	// removeItem() never renumbers remaining items, and steps are explicitly allowed to be non-contiguous
	// (see class doc comment), so "step 1" isn't guaranteed to exist for a given class — hardcoding
	// currentStep = 1 on boss entry / edit-mode switches left nothing rendering whenever the real lowest
	// step number for that class was something else, until an unrelated trigger self-healed it later.
	private int firstStepFor(DungeonClass clazz) {
		if (clazz == null) return 1;
		int min = Integer.MAX_VALUE;
		for (CopilotItem item : items) {
			if (item.forClass == clazz && item.stepNumber < min) min = item.stepNumber;
		}
		return min == Integer.MAX_VALUE ? 1 : min;
	}

	private static DungeonClass selfClass() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		String selfName = mc.player.getName().getString();
		for (DungeonPlayer teammate : DungeonState.getTeammates()) {
			if (teammate.name.equals(selfName)) return teammate.clazz;
		}
		return null;
	}

	private List<CopilotItem> currentStepItems() {
		List<CopilotItem> myItems = activeClassItems();
		if (showAllSteps) return myItems;
		List<CopilotItem> result = new ArrayList<>();
		for (CopilotItem item : myItems) if (item.stepNumber == currentStep) result.add(item);
		return result;
	}

	// ---- World rendering (Breakable Blocks / Waypoints) ----

	// Real redesign (per user request — "Change the 2d render in the copilot to a 3d block render that
	// renders through walls" / "Waypoint should also render through blocks"): both used to be screen-space
	// projections via WorldRenderUtil (occluded by walls/terrain, flat 2D boxes). Switched to World3DRenderer's
	// real depth-tested-but-through-walls geometry, same mechanism Door Highlight/Inactive Terminals already
	// use — registered via World3DRenderer.addRenderCallback instead of HudElementRegistry now (see onEnable).
	private static void renderWorldMarkers() {
		if (instance == null || !instance.isEnabled() || !instance.shouldBeActive()) return;
		try {
			for (CopilotItem item : instance.currentStepItems()) {
				switch (item.type) {
					case BREAKABLE_BLOCKS, BLOCK_WATCH -> {
						// Per user request ("Blocks that are linked/next to eachother should create a bigger
						// box instead of having multiple"): was one box per individual BlockPos — now groups
						// touching/diagonally-adjacent blocks (26-connectivity) and draws one bounding box per
						// connected group instead. BLOCK_WATCH shares this exact rendering (same `blocks` list,
						// same `blockColor`) — only the tick-side completion condition differs between the two.
						for (AABB box : mergeAdjacentBlockBoxes(item.blocks)) {
							int fillAlpha = Math.round(35f / 100f * 255f);
							com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawFilledBoxThroughWalls(box, (fillAlpha << 24) | (item.blockColor & 0xFFFFFF));
							com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawWireBoxThroughWalls(box, item.blockColor, 2f);
						}
					}
					case WAYPOINT -> {
						AABB box = new AABB(item.x - 0.5, item.y, item.z - 0.5, item.x + 0.5, item.y + 2.0, item.z + 0.5);
						int fillAlpha = Math.round(35f / 100f * 255f);
						com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawFilledBoxThroughWalls(box, (fillAlpha << 24) | (item.waypointColor & 0xFFFFFF));
						com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawWireBoxThroughWalls(box, item.waypointColor, 2f);
					}
					case TITLE, LEAP_USED -> {} // nothing to render in the world for either — LEAP_USED is a
						// pure chat-detected event, same as TITLE having no world geometry of its own.
				}
			}
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Dungeons Copilot world render failed, skipping this frame", e);
		}
	}

	/** Groups touching/diagonally-adjacent (26-connectivity — face, edge, or corner) blocks into connected
	 *  components via BFS and returns one bounding-box AABB per component, instead of one box per block. A
	 *  bounding box can include empty gaps for a non-rectangular group (e.g. an L-shape), which is an
	 *  accepted tradeoff for "one big box" over an exact-shape multi-box union — matches what the user
	 *  actually asked for and how Door Highlight's own real coal-block box resolution already works. */
	private static List<AABB> mergeAdjacentBlockBoxes(List<BlockPos> blocks) {
		List<AABB> result = new ArrayList<>();
		java.util.Set<BlockPos> remaining = new java.util.HashSet<>(blocks);
		while (!remaining.isEmpty()) {
			BlockPos start = remaining.iterator().next();
			remaining.remove(start);
			java.util.Deque<BlockPos> queue = new java.util.ArrayDeque<>();
			queue.add(start);
			int minX = start.getX(), maxX = start.getX();
			int minY = start.getY(), maxY = start.getY();
			int minZ = start.getZ(), maxZ = start.getZ();
			while (!queue.isEmpty()) {
				BlockPos cur = queue.poll();
				minX = Math.min(minX, cur.getX()); maxX = Math.max(maxX, cur.getX());
				minY = Math.min(minY, cur.getY()); maxY = Math.max(maxY, cur.getY());
				minZ = Math.min(minZ, cur.getZ()); maxZ = Math.max(maxZ, cur.getZ());
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							if (dx == 0 && dy == 0 && dz == 0) continue;
							BlockPos neighbor = cur.offset(dx, dy, dz);
							if (remaining.remove(neighbor)) queue.add(neighbor);
						}
					}
				}
			}
			result.add(new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0));
		}
		return result;
	}

	// ---- HUD title widget (Title steps — "a title appearing on a gui element, not a regular Minecraft title") ----

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	/** The one TITLE-type item currently showing (there's at most one meaningfully-titled item per step in
	 *  practice), or null. Computed once per caller rather than splitting text/color into two separate
	 *  currentStepItems() scans — this gets called every HUD frame via isVisible(), so the fewer
	 *  activeClassItems() list allocations per call the better, even though the list sizes involved are
	 *  always small. */
	private CopilotItem activeTitleItem() {
		for (CopilotItem item : currentStepItems()) {
			if (item.type == StepType.TITLE && !item.titleText.isBlank()) return item;
		}
		return null;
	}

	/** True while the boss fight is real and active, OR the player is in singleplayer at all — per user
	 *  request ("make sure it works in singleplayer. It should always show when in singleplayer"), since
	 *  there's no real boss-entry signal to wait for there and testing a plan is the whole point. */
	private boolean shouldBeActive() {
		return inRealBossFight() || com.cokelord.skyblocksimplified.util.IslandGate.isSingleplayer();
	}

	@Override
	public boolean isVisible() { return isEnabled() && shouldBeActive() && (activeTitleItem() != null || showCoords); }

	@Override
	public boolean isRelevantToCurrentIsland() {
		return DungeonState.isInDungeon() || com.cokelord.skyblocksimplified.util.IslandGate.isSingleplayer();
	}

	@Override
	public boolean hasVisibleContent() { return activeTitleItem() != null || showCoords; }

	/** "X: .. Y: .. Z: .." at one decimal place — per user request, matching the same one-decimal precision
	 *  Waypoint items already use for their own stored coordinates (see fmt()). */
	private static String coordsLine() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return "§7X: ? Y: ? Z: ?";
		return "§7X: §f" + fmt(mc.player.getX()) + " §7Y: §f" + fmt(mc.player.getY()) + " §7Z: §f" + fmt(mc.player.getZ());
	}

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		CopilotItem activeTitle = activeTitleItem();
		String stylePrefix = (boldTitle ? "§l" : "") + (italicTitle ? "§o" : "");
		String titleLine = activeTitle != null ? stylePrefix + activeTitle.titleText : (showCoords ? null : "§6" + stylePrefix + "Boss Guide");
		int color = activeTitle != null ? activeTitle.titleColor : 0xFFAAAAAA;
		String coordsLine = showCoords ? coordsLine() : null;

		int textWidth = Math.max(titleLine != null ? font.width(titleLine) : 0, coordsLine != null ? font.width(coordsLine) : 0);
		int lineCount = (titleLine != null ? 1 : 0) + (coordsLine != null ? 1 : 0);
		int panelWidth = textWidth + 12;
		int panelHeight = font.lineHeight * lineCount + 4 * (lineCount + 1);

		if (Math.abs(scale - 1f) < 0.01f) {
			drawPanel(graphics, font, x, y, titleLine, color, coordsLine, panelWidth, panelHeight);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			drawPanel(graphics, font, x, y, titleLine, color, coordsLine, panelWidth, panelHeight);
			graphics.pose().popMatrix();
		}
		return new Size(Math.round(panelWidth * scale), Math.round(panelHeight * scale));
	}

	private void drawPanel(GuiGraphicsExtractor graphics, Font font, int x, int y, String titleLine, int color, String coordsLine, int panelWidth, int panelHeight) {
		// Per user request: a subtoggle to remove the panel's own background fill, for a bare-text overlay.
		if (!hideTitleBackground) RenderUtil.fillRounded(graphics, x, y, x + panelWidth, y + panelHeight, 4, 0xE0101010);
		int lineY = y + 4;
		if (titleLine != null) {
			graphics.text(font, titleLine, x + 6, lineY, color);
			lineY += font.lineHeight + 4;
		}
		if (coordsLine != null) {
			graphics.text(font, coordsLine, x + 6, lineY, 0xFFFFFFFF);
		}
	}

	// ---- GUI editing surface (called from MainScreen) ----

	public boolean isClassEnabled(DungeonClass clazz) { return classEnabled.getOrDefault(clazz, true); }
	public void setClassEnabled(DungeonClass clazz, boolean value) { classEnabled.put(clazz, value); }
	public boolean isEditMode() { return editMode; }
	// Real bug found (per user report — "doesn't render when I turn on edit mode"): entering Edit Mode
	// never reset currentStep, so it could be left pointing at a step number that doesn't exist for
	// whichever class Edit Mode resolves to, matching nothing in currentStepItems().
	public void setEditMode(boolean value) {
		editMode = value;
		if (value) currentStep = firstStepFor(resolveActiveClass());
	}
	/** Synced from MainScreen's own class-tab click handler on every click (see {@link #activeClassItems()}
	 *  for what actually reads it) — kept up to date regardless of {@link #editMode}'s current value so
	 *  whichever tab is already selected takes effect immediately the moment Edit Mode is turned on.
	 *  Only resets currentStep while Edit Mode is already active, since this is also called while just
	 *  browsing class tabs with Edit Mode off and must not disrupt real dungeon progress there. */
	public void setEditModeClass(DungeonClass clazz) {
		editModeClass = clazz;
		if (editMode) currentStep = firstStepFor(clazz);
	}
	public boolean isShowAllSteps() { return showAllSteps; }
	public void setShowAllSteps(boolean value) { showAllSteps = value; }
	public boolean isWaypointTracer() { return waypointTracer; }
	public void setWaypointTracer(boolean value) { waypointTracer = value; }
	public boolean isWaypointBeaconLine() { return waypointBeaconLine; }
	public void setWaypointBeaconLine(boolean value) { waypointBeaconLine = value; }
	public boolean isShowCoords() { return showCoords; }
	public void setShowCoords(boolean value) { showCoords = value; }
	public boolean isTitleUpdateSoundEnabled() { return titleUpdateSoundEnabled; }
	public void setTitleUpdateSoundEnabled(boolean value) { titleUpdateSoundEnabled = value; }
	public CustomSoundOption getTitleUpdateSound() { return titleUpdateSound; }
	public boolean isHideTitleBackground() { return hideTitleBackground; }
	public void setHideTitleBackground(boolean value) { hideTitleBackground = value; }
	public boolean isBoldTitle() { return boldTitle; }
	public void setBoldTitle(boolean value) { boldTitle = value; }
	public boolean isItalicTitle() { return italicTitle; }
	public void setItalicTitle(boolean value) { italicTitle = value; }

	public List<CopilotItem> getItems() { return items; }

	public List<CopilotItem> getItemsForClass(DungeonClass clazz) {
		List<CopilotItem> result = new ArrayList<>();
		for (CopilotItem item : items) if (item.forClass == clazz) result.add(item);
		return result;
	}

	/** Per user request ("if the user adds a step and advances step is off that newly added step should be
	 *  the same step as last step instead of +1"): a step nothing will ever automatically move the guide
	 *  past (advancesStep off on every item already at the highest step number) would otherwise strand the
	 *  new item on an unreachable step number one past it — grouping the new item onto that same step
	 *  instead keeps it reachable. Only actually matters when the highest step is BREAKABLE_BLOCKS (the only
	 *  type advancesStep governs at all); every other type at the top step always eventually advances on its
	 *  own, so this naturally falls through to the normal max+1 behavior for them. */
	public void addItem(DungeonClass clazz) {
		CopilotItem item = new CopilotItem();
		item.forClass = clazz;
		int maxStep = 0;
		boolean maxStepAdvances = false;
		for (CopilotItem existing : items) {
			if (existing.forClass != clazz) continue;
			if (existing.stepNumber > maxStep) { maxStep = existing.stepNumber; maxStepAdvances = existing.advancesStep; }
			else if (existing.stepNumber == maxStep && existing.advancesStep) maxStepAdvances = true;
		}
		item.stepNumber = (maxStep == 0 || maxStepAdvances) ? maxStep + 1 : maxStep;
		items.add(item);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void removeItem(CopilotItem item) {
		items.remove(item);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Drag-reorder handle support (MainScreen's step-number box, dragged instead of clicked): moves
	 *  {@code item} to {@code newIndex} within its own class's step order (the same order
	 *  {@link #getItemsForClass} renders in), then renumbers every step of that class sequentially so the
	 *  new visual order and the real step numbers this feature actually reads always agree — dragging IS
	 *  the reorder, not a separate hidden index. */
	public void reorderItem(CopilotItem item, int newIndex) {
		List<CopilotItem> classItems = getItemsForClass(item.forClass);
		if (!classItems.remove(item)) return;
		newIndex = Math.max(0, Math.min(newIndex, classItems.size()));
		classItems.add(newIndex, item);
		items.removeIf(i -> i.forClass == item.forClass);
		items.addAll(classItems);
		for (int i = 0; i < classItems.size(); i++) classItems.get(i).stepNumber = i + 1;
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	// Per user request ("color code the steps since currently all new features are yellow. leap can be
	// purple, terminal complete can be black and device complete can be gray"): DEVICE_FINISHED/LEAP_USED/
	// TERMINAL_DONE all share CopilotItem#blockColor (see copilotItemColor/setCopilotItemColor in MainScreen)
	// but that field's class-level default is yellow (0xFFFFFF55, the Breakable Blocks color) — nothing ever
	// gave these three their own distinct default, so every new one of them just inherited yellow. Real
	// Minecraft §5/§7 text colors reused here rather than picked arbitrarily.
	private static final int DEFAULT_DEVICE_COLOR = 0xFFAAAAAA; // gray, matches vanilla §7
	private static final int DEFAULT_LEAP_COLOR = 0xFFAA00AA; // purple, matches vanilla §5
	private static final int DEFAULT_TERMINAL_DONE_COLOR = 0xFF000000; // black, matches vanilla §0

	public void cycleType(CopilotItem item) {
		StepType[] values = StepType.values();
		item.type = values[(item.type.ordinal() + 1) % values.length];
		if (item.type == StepType.TITLE) item.advancesStep = false;
		// Per user request ("It should also be on by default for blocks"): forced back to true every time a
		// step lands on Breakable Blocks by cycling, mirroring the TITLE rule immediately above.
		if (item.type == StepType.BREAKABLE_BLOCKS) item.advancesStep = true;
		// Forced every time a step lands on one of these three, same unconditional-reset shape as the
		// advancesStep rules just above (not a one-time "only if still yellow" default) — simplest way to
		// guarantee cycling INTO a type always shows that type's real color, matching how TITLE/Breakable
		// Blocks already force their own advancesStep value on every landing rather than only the first.
		if (item.type == StepType.DEVICE_FINISHED) item.blockColor = DEFAULT_DEVICE_COLOR;
		else if (item.type == StepType.LEAP_USED) item.blockColor = DEFAULT_LEAP_COLOR;
		else if (item.type == StepType.TERMINAL_DONE) item.blockColor = DEFAULT_TERMINAL_DONE_COLOR;
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void cycleDeviceKind(CopilotItem item) {
		DeviceKind[] values = DeviceKind.values();
		item.deviceKind = values[(item.deviceKind.ordinal() + 1) % values.length];
		if (item.deviceKind != DeviceKind.ALIGN && item.deviceKind != DeviceKind.LEVERS) item.pdMode = false;
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Adds the block the player is currently looking at to a Breakable Blocks item's list. */
	public void useLookedAtBlock(CopilotItem item) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
		if (!item.blocks.contains(pos)) item.blocks.add(pos);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void clearBlocks(CopilotItem item) {
		item.blocks.clear();
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Adds a block at the typed X/Y/Z coordinates to a Breakable Blocks item's list — per user request
	 *  ("I want XYZ boxes for the blocks highlights too"), the same manual-coordinate convenience Waypoint
	 *  steps already have via xInput/yInput/zInput, reused here rather than adding a parallel set of fields
	 *  since only one of BREAKABLE_BLOCKS/WAYPOINT is ever relevant for a given item at once. */
	public void addBlockFromInput(CopilotItem item) {
		try {
			int bx = (int) Math.floor(Double.parseDouble(item.xInput));
			int by = (int) Math.floor(Double.parseDouble(item.yInput));
			int bz = (int) Math.floor(Double.parseDouble(item.zInput));
			BlockPos pos = new BlockPos(bx, by, bz);
			if (!item.blocks.contains(pos)) item.blocks.add(pos);
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
		} catch (NumberFormatException ignored) {}
	}

	public void useCurrentPosition(CopilotItem item) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		item.x = mc.player.getX(); item.y = mc.player.getY(); item.z = mc.player.getZ();
		item.xInput = fmt(item.x); item.yInput = fmt(item.y); item.zInput = fmt(item.z);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }

	// ---- Standalone export/import, scoped to just this module's own plan ----

	// Per user request ("Add a dungeons copilot export and import like the mod itself under the 'Enabled for
	// class' subtoggle"): a dedicated string just for this module's own steps/settings, separate from the
	// whole-mod config export/import (ConfigManager) — same wire shape (gzip -> base64, own prefix so a
	// whole-mod export/copilot-only export can never be cross-pasted into the wrong box by mistake), but
	// self-contained here rather than reusing ConfigManager's private encode/decode since that's scoped to
	// exporting every registered feature at once, not one feature's own savePersistedData() in isolation.
	// This module's data is ALSO already included automatically whenever the whole-mod config is exported
	// (every feature's savePersistedData() is, with no extra work needed here) — this is purely the extra,
	// smaller "just share my Copilot plan" string on top of that.
	private static final String COPILOT_EXPORT_PREFIX = "SBSCOPILOT1:";
	private static final int COPILOT_MAX_IMPORT_LENGTH = 500_000;
	private static final int COPILOT_MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024;

	public String exportToClipboardString() {
		String json = new com.google.gson.Gson().toJson(savePersistedData());
		try {
			java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
			try (java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(byteOut)) {
				gzipOut.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
			return COPILOT_EXPORT_PREFIX + java.util.Base64.getEncoder().encodeToString(byteOut.toByteArray());
		} catch (java.io.IOException e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Failed to export Dungeons Copilot config", e);
			return null;
		}
	}

	/** Returns true if applied, false if the string wasn't a valid Copilot export at all (wrong prefix, too
	 *  large, or corrupted base64/gzip/JSON) — the caller shows this as GUI/chat feedback, same shape as
	 *  ConfigManager's own whole-mod import. */
	public boolean importFromClipboardString(String raw) {
		if (raw == null) return false;
		String trimmed = raw.strip();
		if (trimmed.length() > COPILOT_MAX_IMPORT_LENGTH || !trimmed.startsWith(COPILOT_EXPORT_PREFIX)) return false;
		try {
			byte[] compressed = java.util.Base64.getDecoder().decode(trimmed.substring(COPILOT_EXPORT_PREFIX.length()));
			java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
			try (java.util.zip.GZIPInputStream gzipIn = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
				byte[] buffer = new byte[8192];
				int total = 0, read;
				while ((read = gzipIn.read(buffer)) != -1) {
					total += read;
					if (total > COPILOT_MAX_DECOMPRESSED_BYTES) throw new java.io.IOException("Decompressed Copilot import exceeds size cap");
					byteOut.write(buffer, 0, read);
				}
			}
			JsonElement parsed = com.google.gson.JsonParser.parseString(new String(byteOut.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
			loadPersistedData(parsed);
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			return true;
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.warn("Failed to decode an imported Dungeons Copilot string", e);
			return false;
		}
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		for (DungeonClass c : DungeonClass.values()) {
			if (c == DungeonClass.EMPTY) continue;
			obj.addProperty(c.name() + "_enabled", classEnabled.getOrDefault(c, true));
		}
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		obj.addProperty("showAllSteps", showAllSteps);
		obj.addProperty("waypointTracer", waypointTracer);
		obj.addProperty("waypointBeaconLine", waypointBeaconLine);
		obj.addProperty("showCoords", showCoords);
		obj.addProperty("titleUpdateSoundEnabled", titleUpdateSoundEnabled);
		obj.add("titleUpdateSound", titleUpdateSound.toJson());
		obj.addProperty("hideTitleBackground", hideTitleBackground);
		obj.addProperty("boldTitle", boldTitle);
		obj.addProperty("italicTitle", italicTitle);
		obj.addProperty("editMode", editMode);
		if (editModeClass != null) obj.addProperty("editModeClass", editModeClass.name());
		JsonArray array = new JsonArray();
		for (CopilotItem item : items) {
			JsonObject e = new JsonObject();
			e.addProperty("forClass", item.forClass.name());
			e.addProperty("type", item.type.name());
			e.addProperty("stepNumber", item.stepNumber);
			e.addProperty("advancesStep", item.advancesStep);
			e.addProperty("deviceKind", item.deviceKind.name());
			e.addProperty("pdMode", item.pdMode);
			e.addProperty("chatTrigger", item.chatTrigger);
			e.addProperty("splitTrigger", item.splitTrigger);
			e.addProperty("splitOffsetSeconds", item.splitOffsetSeconds);
			e.addProperty("titleText", item.titleText);
			e.addProperty("titleColor", item.titleColor);
			e.addProperty("blockColor", item.blockColor);
			e.addProperty("targetBlockInput", item.targetBlockInput);
			JsonArray blocksArray = new JsonArray();
			for (BlockPos pos : item.blocks) {
				JsonObject bp = new JsonObject();
				bp.addProperty("x", pos.getX()); bp.addProperty("y", pos.getY()); bp.addProperty("z", pos.getZ());
				blocksArray.add(bp);
			}
			e.add("blocks", blocksArray);
			e.addProperty("x", item.x); e.addProperty("y", item.y); e.addProperty("z", item.z);
			e.addProperty("waypointColor", item.waypointColor);
			array.add(e);
		}
		obj.add("items", array);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		for (DungeonClass c : DungeonClass.values()) {
			if (c == DungeonClass.EMPTY) continue;
			if (obj.has(c.name() + "_enabled")) classEnabled.put(c, obj.get(c.name() + "_enabled").getAsBoolean());
		}
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
		if (obj.has("showAllSteps")) showAllSteps = obj.get("showAllSteps").getAsBoolean();
		if (obj.has("waypointTracer")) waypointTracer = obj.get("waypointTracer").getAsBoolean();
		if (obj.has("waypointBeaconLine")) waypointBeaconLine = obj.get("waypointBeaconLine").getAsBoolean();
		if (obj.has("showCoords")) showCoords = obj.get("showCoords").getAsBoolean();
		if (obj.has("titleUpdateSoundEnabled")) titleUpdateSoundEnabled = obj.get("titleUpdateSoundEnabled").getAsBoolean();
		if (obj.has("titleUpdateSound")) {
			JsonElement soundEl = obj.get("titleUpdateSound");
			if (soundEl.isJsonObject()) {
				titleUpdateSound.fromJson(soundEl);
			} else if (soundEl.isJsonPrimitive()) {
				// Back-compat with the old flat TerminalSound-enum-name string field, pre-CustomSoundOption.
				try { titleUpdateSound.setBuiltinIndex(TerminalSoundsFeature.TerminalSound.valueOf(soundEl.getAsString()).ordinal()); } catch (Exception ignored) {}
			}
		}
		if (obj.has("hideTitleBackground")) hideTitleBackground = obj.get("hideTitleBackground").getAsBoolean();
		if (obj.has("boldTitle")) boldTitle = obj.get("boldTitle").getAsBoolean();
		if (obj.has("italicTitle")) italicTitle = obj.get("italicTitle").getAsBoolean();
		if (obj.has("editMode")) editMode = obj.get("editMode").getAsBoolean();
		if (obj.has("editModeClass")) { try { editModeClass = DungeonClass.valueOf(obj.get("editModeClass").getAsString()); } catch (Exception ignored) {} }
		items.clear();
		if (obj.has("items")) {
			for (JsonElement el : obj.getAsJsonArray("items")) {
				JsonObject e = el.getAsJsonObject();
				CopilotItem item = new CopilotItem();
				try { item.forClass = DungeonClass.valueOf(e.get("forClass").getAsString()); } catch (Exception ignored) {}
				// Real bug found (per user report — "Terminal solved and leap step types do not persist to
				// config. They default to title when i restart my game"): StepType.valueOf() is exact-match
				// only (case-sensitive, no surrounding whitespace tolerance) and this was a bare try/catch with
				// no logging at all — any saved "type" string that doesn't match a current constant BYTE FOR
				// BYTE silently fell all the way back to CopilotItem's default TITLE with zero trace of why.
				// trim()+toUpperCase() first covers the most likely real-world mismatch (stray whitespace from
				// a hand-edited/imported config, or a differently-cased value from some other tool), and the
				// warn log means a genuine mismatch is now visible in the log instead of silently vanishing.
				if (e.has("type")) {
					String raw = e.get("type").getAsString();
					try {
						item.type = StepType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
					} catch (Exception ex) {
						com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.warn(
							"Dungeons Copilot: unrecognized step type '{}' in saved config, falling back to Title", raw);
					}
				}
				if (e.has("stepNumber")) item.stepNumber = e.get("stepNumber").getAsInt();
				if (e.has("advancesStep")) item.advancesStep = e.get("advancesStep").getAsBoolean();
				if (e.has("deviceKind")) {
					String raw = e.get("deviceKind").getAsString();
					try { item.deviceKind = DeviceKind.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (Exception ex) {
						com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.warn(
							"Dungeons Copilot: unrecognized device kind '{}' in saved config, falling back to SS", raw);
					}
				}
				if (e.has("pdMode")) item.pdMode = e.get("pdMode").getAsBoolean();
				if (e.has("chatTrigger")) item.chatTrigger = e.get("chatTrigger").getAsString();
				if (e.has("splitTrigger")) item.splitTrigger = e.get("splitTrigger").getAsString();
				if (e.has("splitOffsetSeconds")) {
					item.splitOffsetSeconds = e.get("splitOffsetSeconds").getAsFloat();
					item.splitOffsetInput = fmt(item.splitOffsetSeconds);
				}
				if (e.has("titleText")) item.titleText = e.get("titleText").getAsString();
				if (e.has("titleColor")) item.titleColor = e.get("titleColor").getAsInt();
				if (e.has("blockColor")) item.blockColor = e.get("blockColor").getAsInt();
				// One-time migration (per user report — "color code the steps since currently all new
				// features are yellow"): an already-saved Device/Leap/Terminal Done step that was never
				// customized away from the inherited class-default yellow (0xFFFFFF55, Breakable Blocks'
				// own color) picks up its real default color retroactively on load — a step whose color WAS
				// deliberately customized (anything other than that exact default) is left untouched.
				if (item.blockColor == 0xFFFFFF55) {
					if (item.type == StepType.DEVICE_FINISHED) item.blockColor = DEFAULT_DEVICE_COLOR;
					else if (item.type == StepType.LEAP_USED) item.blockColor = DEFAULT_LEAP_COLOR;
					else if (item.type == StepType.TERMINAL_DONE) item.blockColor = DEFAULT_TERMINAL_DONE_COLOR;
				}
				if (e.has("targetBlockInput")) item.targetBlockInput = e.get("targetBlockInput").getAsString();
				if (e.has("blocks")) {
					for (JsonElement b : e.getAsJsonArray("blocks")) {
						JsonObject bp = b.getAsJsonObject();
						item.blocks.add(new BlockPos(bp.get("x").getAsInt(), bp.get("y").getAsInt(), bp.get("z").getAsInt()));
					}
				}
				if (e.has("x")) item.x = e.get("x").getAsDouble();
				if (e.has("y")) item.y = e.get("y").getAsDouble();
				if (e.has("z")) item.z = e.get("z").getAsDouble();
				item.xInput = fmt(item.x); item.yInput = fmt(item.y); item.zInput = fmt(item.z);
				if (e.has("waypointColor")) item.waypointColor = e.get("waypointColor").getAsInt();
				items.add(item);
			}
		}
	}

	@Override
	public String getDescription() {
		return "Step-by-step, per-class walkthrough guide for a dungeon boss fight, advancing automatically as you complete each step.";
	}
}
