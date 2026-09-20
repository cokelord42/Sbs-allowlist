package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonBlockDetector;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.dungeon.map.tile.MapCheckmark;
import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomData;
import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomRotation;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per user request — "Next big module: Dungeon routes. Will work a lot like copilot but in rooms": an
 * ordered per-ROOM walkthrough (Boss Guide/{@link DungeonsCopilotFeature} is per-CLASS, inside the boss
 * fight only). The settings panel lists every known room name ({@link RoomData#getAllRoomNames()}) as a
 * small button; picking one opens that room's own step editor, the same shape as Boss Guide's.
 *
 * <p>Real design difference from Boss Guide: the boss room always sits at the same fixed world position
 * every run, so Boss Guide stores absolute world XYZ. A regular room can appear ANYWHERE on the floor's
 * layout in any of 4 rotations, so every position a route step stores ({@link RouteItem#blocks}, {@link
 * RouteItem#x}/{@link RouteItem#y}/{@link RouteItem#z}) is room-relative (as if the room's own clay corner
 * were the origin, facing north) via {@link DungeonRoom#getRelativeCoords}/{@link DungeonRoom#getRealCoords}
 * — the exact same convention every other room-relative dungeon feature (puzzle solvers, door highlight)
 * already uses. Capturing a position (Use Looked-At Block / Use Pos / Add Block's typed XYZ, which is read
 * as the real world coordinate the player can see on F3) only works while the player is actually standing
 * in the room currently being edited, since that's the only time this mod has a real, geometry-resolved
 * {@link DungeonRoom} instance for that room to convert through.
 *
 * <p>Secret steps (per user request — "Secret pickup/chest/bat/essence highlight and support it going to
 * the next step once a secret step has been completed") reuse {@link DungeonBlockDetector}'s existing
 * real pickup/chest/bat/essence detection (already built for the Clicked Blocks feature) instead of a new
 * detection mechanism — a secret step both highlights a position (same box/tracer/beacon rendering as a
 * WAYPOINT) and auto-advances the moment a matching event fires while that step is current.
 */
public class DungeonRoutesFeature extends Feature {
	// Per user request ("Remove title, no need for titles during dungeon secrets") — TITLE (and the
	// moveable HUD title panel it fed) existed here only because it was carried over from Boss Guide's own
	// StepType, which genuinely needs a title (a boss-fight phase banner); a Dungeon Routes step is scoped
	// to physically standing in a room, where a title banner never added anything Boss Guide's use case
	// does. Removed the step type entirely, along with the HUD widget/MoveableWidget plumbing that only
	// ever existed to render it.
	//
	// Per user request ("Find a way to import routes from SecretRoutes mod... Remove the waypoint type
	// from ours and import theirs"): INTERACT_WAYPOINT was added to mirror the one SecretRoutes waypoint
	// category this codebase had no equivalent for ("interacts" — intermediate buttons/plates/levers you
	// click while still routing toward the actual secret). SecretRoutes' other category, "mines" ("Stonk"
	// blocks — per user correction, just community slang for the old ghost-block item used to mine them,
	// not a mechanically distinct thing from Breakable Blocks), maps straight onto the existing
	// BREAKABLE_BLOCKS type instead of getting its own — no separate STONK_BLOCKS type exists. WAYPOINT
	// itself is kept in the enum (deleting it outright would throw on load for every existing user's
	// already-saved WAYPOINT steps, since {@link #itemFromJson}'s {@code StepType.valueOf} has nothing to
	// fall back to and this class's own default field initializer references it) — but the SecretRoutes
	// importer (see {@link SecretRoutesImporter}) never produces one: every imported step's own walk path
	// (SecretRoutes' "locations") becomes a cosmetic {@link RouteItem#pathPoints} line instead of a chain of
	// generic WAYPOINT steps, so "the waypoint type" is genuinely retired for anything coming from an import.
	public enum StepType { BREAKABLE_BLOCKS, WAYPOINT, SECRET_PICKUP, SECRET_CHEST, SECRET_BAT, SECRET_ESSENCE, SECRET_LEVER, ENDERPEARL_WAYPOINT, ENDERWARP_WAYPOINT, SUPERBOOM_BLOCK, INTERACT_WAYPOINT }

	// Per user diagnosis ("we already have a lever module") — INTERACT_WAYPOINT is kept in the enum above
	// purely so already-saved/imported steps of that type keep loading and rendering (see
	// SecretRoutesImporter's own doc comment on why new imports redirect to SECRET_LEVER instead), but it's
	// deliberately excluded from this cycle-through list so the "add step, pick type" cog button in the
	// editor (cycleType, below) can no longer land on it — the manual picker and the importer now agree on
	// SECRET_LEVER being the one real "click something along the way" type.
	private static final StepType[] SELECTABLE_TYPES = {
		StepType.BREAKABLE_BLOCKS, StepType.WAYPOINT, StepType.SECRET_PICKUP, StepType.SECRET_CHEST,
		StepType.SECRET_BAT, StepType.SECRET_ESSENCE, StepType.SECRET_LEVER, StepType.ENDERPEARL_WAYPOINT,
		StepType.ENDERWARP_WAYPOINT, StepType.SUPERBOOM_BLOCK
	};

	/** A single ender pearl throw captured for an {@link StepType#ENDERPEARL_WAYPOINT} item — per user
	 *  request ("add their pearl launch angle lines as an asset to ours"), a launch position PLUS the real
	 *  facing angle at that position, so the world render can draw the actual throw direction as a line (not
	 *  just a box marking where to stand). A single item can hold several (SecretRoutes' own format allows
	 *  more than one pearl per step) — see {@link #pearlShots}. Position is room-relative, same fractional
	 *  convention as {@link RouteItem#x}/{@link RouteItem#y}/{@link RouteItem#z}. */
	public static final class PearlShot {
		public double x, y, z;
		public float yaw, pitch;
	}

	public static final class RouteItem {
		public String roomName = "";
		public StepType type = StepType.WAYPOINT;
		public int stepNumber = 1;
		// Per user request ("Allow users to also select which of the items on the same step should advance
		// to next step, since etherwarping advances to next step even if the user hasn't opened the
		// chest/took the secret") — true by default (matches the old unconditional-advance behavior for a
		// step with only one item); toggled off per-item so, e.g., an Etherwarp Waypoint sharing a step with
		// a Secret Chest doesn't skip past the chest the moment the player etherwarps.
		public boolean advancesStep = true;

		public final List<BlockPos> blocks = new ArrayList<>();
		// Per user request ("These should be the default colors of each item") — real per-type defaults now
		// (see defaultColorFor), applied when an item is created/cycled to a new type; these two field
		// initializers only matter for the very first instant before that runs.
		public int blockColor = 0xFFFFFF55;

		public String xInput = "0.0", yInput = "0.0", zInput = "0.0";
		public double x, y, z; // room-relative
		public int waypointColor = 0xFF55FFFF;

		// Per SecretRoutes import — pearl throws for ENDERPEARL_WAYPOINT (see PearlShot's own doc comment).
		// Only ever populated by an import or "Use Current Facing"; a manually-placed ENDERPEARL_WAYPOINT
		// with no shots still renders exactly as it always has, via the legacy x/y/z above.
		public final List<PearlShot> pearlShots = new ArrayList<>();

		// Per SecretRoutes import — a cosmetic, non-interactive walking path (SecretRoutes' own "locations"
		// list). Per later user request ("remove the skinny weird lines"), this is no longer rendered at
		// all — the field and its import/save-load plumbing are kept only so existing imports still parse.
		public final List<BlockPos> pathPoints = new ArrayList<>();

		// An imported room can carry more than one full alternate route (SecretRoutes' own "RoomName:1",
		// "RoomName:2", ... keys) — empty string means the single/default route for that room, matching
		// every pre-existing manually-authored item exactly (so this is fully additive, zero behavior change
		// for anyone who never imports anything). See pickVariant()'s own doc comment for how one gets
		// chosen when a room actually has more than one — deliberately NOT position-based (see there for why).
		public String variant = "";
	}

	/** Per user request — a real distinct default color per step type instead of one shared color for
	 *  everything but Blocks, using Minecraft's own standard 16 chat-color palette for consistency with the
	 *  rest of this codebase. Applied when an item is first created and whenever its type is cycled (see
	 *  {@link #addItem}/{@link #cycleType}) — not re-applied on its own after that, so a manually re-colored
	 *  item keeps whatever the user picked until they cycle its type again. */
	private static int defaultColorFor(StepType type) {
		return switch (type) {
			// Per user request ("All the colors for the waypoints are the exact same, so default them to
			// this when imported: Blocks yellow, Superboom red, Etherwarp green, Chest secrets white, Wither
			// essence and bats black, item pickups purple, Levers gray") — Superboom/Chest swapped from their
			// previous Gold/Red to match; every other value here already matched what was asked for.
			case BREAKABLE_BLOCKS -> 0xFFFFFF55; // Yellow
			case WAYPOINT -> 0xFF55FFFF; // Aqua ("light blue") — unchanged
			case SECRET_PICKUP -> 0xFFAA00AA; // Purple
			case SECRET_CHEST -> 0xFFFFFFFF; // White
			case SECRET_BAT -> 0xFF000000; // Black
			case SECRET_ESSENCE -> 0xFF000000; // Black
			case SECRET_LEVER -> 0xFFAAAAAA; // Gray
			case ENDERPEARL_WAYPOINT -> 0xFF0000AA; // Dark Blue
			case ENDERWARP_WAYPOINT -> 0xFF55FF55; // Green
			case SUPERBOOM_BLOCK -> 0xFFFF5555; // Red
			case INTERACT_WAYPOINT -> 0xFF00AAAA; // Dark Aqua
		};
	}

	private static final double WAYPOINT_REACHED_DISTANCE = 2.0;
	// Per user request ("detect when the player is within a 5 block range AND when a superboom in their
	// inventory happens to get used/stack goes down by one") — see the SUPERBOOM_BLOCK case below.
	private static final double SUPERBOOM_USE_RANGE = 5.0;
	private static final long SUPERBOOM_USE_WINDOW_MILLIS = 1500L;

	private final List<RouteItem> items = new ArrayList<>();

	// Per user report ("The route should not reset when reentering the room, it should reset on dungeon
	// leave"): this used to be a single `currentStep` int, reset to 1 on EVERY room-enter event — walking
	// out of a room and back in (to grab a secret you skipped, or just passing back through) silently threw
	// away all progress in that room. Now keyed per room name, so leaving and re-entering the same room
	// mid-run keeps whatever step it was on; only actually leaving the dungeon (see the run-tracking fields
	// below, same runId-comparison pattern DoorHighlightFeature already uses) or disconnecting clears it.
	private final java.util.Map<String, Integer> stepByRoom = new java.util.HashMap<>();
	private String activeRoomName = null;
	// Per user request ("Supports multiple routes in the same room (picks closest one)... add this as a
	// feature to our version of the routes aswell") — see RouteItem#variant's own doc comment and
	// pickVariant() below for how this gets chosen on room entry.
	private String activeRoomVariant = "";
	private boolean wasInDungeonForRouteState = false;
	private int lastSeenRunId = -1;

	private boolean waypointTracer = false;
	private boolean waypointBeaconLine = false;
	// Per user request ("Allow users to change opacity for all highlights in the dungeon routes") — replaces
	// the hardcoded 35% fill alpha renderWorldMarkers used to bake in directly, same 0-100 slider convention
	// as DungeonClickedBlocksFeature's own "Box Opacity".
	private int highlightOpacityPercent = 35;
	// Per user request ("Add an 'Edit mode' toggle that restarts the route when its finished instead of
	// hiding it in that room") — off by default (matches the existing behavior: reaching the last step just
	// stays there). With it on, finishing a room's route loops back to step 1 instead, so a route can be
	// tested/edited repeatedly in one room visit — useful now that progress persists across re-entry (see
	// stepByRoom above) and no longer resets just by walking out and back in.
	private boolean editMode = false;
	// Per user request ("Our waypoint path system was good with the line from the player. Revert that and
	// add the moving line back and remove the skinny weird lines"): the bounded A* pathfinding system this
	// used to cache a walkable path in (findPath/simplifyPath/isWalkable et al) is removed entirely — see
	// renderWorldMarkers' own WAYPOINT beacon-line branch, which now just draws a live straight line from the
	// player to the target every frame instead ("the moving line"), no caching or recalculation needed.

	// Per user report ("For some reason the blocks become a single block offsync. Detect like a chest
	// secret and see if its on the block that the mod will be highlighting, and if not offset the dungeon
	// routes until it is"): room-relative positions only convert to real world coordinates correctly if
	// DungeonRoom#clayPos (the room's detected real-world anchor) is exactly right — a small detection
	// error there shifts EVERY step in that room by the same fixed amount, reading exactly like "the blocks
	// become a single block offsync" without any one step being individually wrong. A secret chest click
	// (see onBlockEvent) is a real, ground-truth ONE-block position this feature can independently verify
	// against — if it disagrees with what realPositionOf already computed for that same expected chest, the
	// discovered delta is recorded here per-room-instance (identity-keyed, not by name — a new room re-roll
	// gets its own fresh, unproven offset) and both realPositionOf/relativeVecFromReal apply it from then on,
	// self-correcting the rest of that room's route without needing the author to re-place every step.
	private static final Map<DungeonRoom, Vec3> roomAlignmentOffsets = new HashMap<>();
	// A genuinely different, unrelated chest elsewhere in a large room must never be mistaken for the one
	// this step expects and yank the whole room's alignment off somewhere wrong — capped to a small radius.
	private static final double MAX_ALIGNMENT_CORRECTION_BLOCKS = 2.0;

	private static boolean listenersRegistered = false;
	private static DungeonRoutesFeature instance;

	// Per user request ("Allow users to select a keybind to go to the next step of the route if it bugs or a
	// bat is gone or something. This should only trigger in dungeons/the keybind should only do something
	// while in dungeons and while a route is active") — manual override for whenever auto-detection misses a
	// real trigger.
	private final net.minecraft.client.KeyMapping nextStepKey = new net.minecraft.client.KeyMapping(
		"key.skyblocksimplified.dungeon_routes_next_step", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
		com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(), com.cokelord.skyblocksimplified.keybind.ModKeyCategory.MAIN);

	public DungeonRoutesFeature() {
		super("dungeon_routes", "Dungeon Routes", FeatureCategory.COMBAT, false);
		instance = this;
		net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(nextStepKey);
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	public java.util.List<String> getSearchAliases() { return java.util.List.of("Secret Routes"); }

	@Override
	public java.util.List<net.minecraft.client.KeyMapping> getKeybinds() { return java.util.List.of(nextStepKey); }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;

			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance == null || !instance.isEnabled()) return;
				// Real bug found (per user report — "The dungeon routes sometimes dont render in certain
				// rooms, and sometimes do. I think theres issues detecting rooms at times"): activeRoomName
				// used to only ever get set once, from WorldScan's room-ENTER event — a snapshot of
				// room.getName() (= room.data.getName()) taken at the exact instant that event fires. If the
				// room's terrain-hash data hadn't finished resolving yet at that exact tick (a real race —
				// the same "may lag a frame or several behind the screen/room actually being ready" caveat
				// this codebase already documents for other screen/room-open captures), activeRoomName got
				// stuck at null PERMANENTLY for that visit, since nothing ever re-checked it afterward —
				// resolvedActiveRoom() then always returns null and every render/step-progression path here
				// silently no-ops for the whole room. Whether that race is won or lost is exactly why this
				// "sometimes works, sometimes doesn't" instead of being consistently broken or working.
				// Self-heals every tick instead: whenever nothing is currently tracked but WorldScan already
				// has a real, named current room, adopts it — same self-heal shape already used elsewhere in
				// this codebase for an unknown-duration client/server race (e.g. PartyApi's leader resolve).
				if (instance.activeRoomName == null && com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) {
					DungeonRoom liveRoom = WorldScan.getCurrentRoom();
					if (liveRoom != null && liveRoom.getName() != null) {
						instance.activeRoomName = liveRoom.getName();
						instance.pickVariant(instance.activeRoomName);
					}
				}
				while (instance.nextStepKey.consumeClick()) {
					if (!com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) continue;
					if (instance.activeRoomName == null) continue;
					List<RouteItem> myItems = instance.activeItems();
					if (myItems.isEmpty()) continue;
					instance.advanceStep(instance.activeRoomName, myItems);
				}
			});

			WorldScan.addRoomEnterListener(room -> {
				if (instance == null) return;
				instance.activeRoomName = room != null ? room.getName() : null;
				instance.pickVariant(instance.activeRoomName);
			});

			World3DRenderer.addRenderCallback(DungeonRoutesFeature::renderWorldMarkers);

			// Per user report ("the tracers dont render through walls") — WorldRenderUtil.drawTracer's default
			// occlusion check (per an EARLIER user request, on Positional Messages/Mage Beam) is deliberately
			// kept as the default for every other caller; Dungeon Routes opts into the always-visible overload
			// instead of changing that shared default. The path LINE (see renderWorldMarkers below, real 3D
			// through-walls geometry) replaces the old vertical "beacon" beam entirely — per user report ("the
			// waypoint beacon line doesnt render at all"): a beacon beam shoots 256 blocks straight up, which a
			// dungeon's own ceiling always occludes, so it could never actually draw one. The new line traces
			// an actual walkable path instead, per user request ("it should find the fastest way to the
			// waypoint and render that with a line").
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeon_routes_waypoint_lines"), (graphics, tracker) -> {
				if (instance == null || !instance.isEnabled() || !instance.waypointTracer) return;
				try {
					DungeonRoom room = instance.resolvedActiveRoom();
					Minecraft mc = Minecraft.getInstance();
					// Real bug found (per user report — "The secret tracer is rendering through guis"): this
					// is drawn via HudElementRegistry, which composites on top of whatever screen is currently
					// open (a container/chest/inventory GUI included) — same class of bug already fixed for
					// InactiveWaypointsFeature's own text label and TerminalHitboxesFeature's box (see their
					// own doc comments), and the same fix: skip entirely while any screen is open. The real 3D
					// path LINE (renderWorldMarkers, below) already runs during the level render pass instead,
					// well before the screen layer, so it isn't affected by this and stays as-is.
					if (mc.gui.screen() != null) return;
					if (room == null || mc.player == null) return;
					// Same "cleared + secrets remaining" gate as renderWorldMarkers — see that method's own
					// doc comment. Applied here too so the navigation tracer doesn't point at a step whose box
					// isn't even drawn anymore.
					if (room.checkmark != MapCheckmark.WHITE && room.checkmark != MapCheckmark.GREEN) return;
					if (room.data != null && room.secretsFound >= 0 && room.secretsFound >= room.data.getSecrets()) return;
					// Per user report ("The tracers and line should only render the closest step [item]... it
					// renders a tracer and line to both"): only ever navigate toward whichever item on the
					// current step is physically closest right now — the highlight boxes themselves (see
					// renderWorldMarkers) still show every item on the step, this just stops the navigation
					// aids from pointing two different directions at once.
					//
					// Per user follow-up ("The pathfinding line should only show for the waypoints steps, and
					// tracers should show for everything else"): the walkable path LINE (drawn separately, see
					// renderWorldMarkers below) and this tracer used to be independently toggle-gated across
					// EVERY positional type, so both could draw for the same item at once. Now mutually
					// exclusive by the closest item's own type — a real walkable-path search only makes sense
					// for a WAYPOINT (a plain "walk here" destination); every other type (secrets, Enderpearl/
					// Etherwarp/Superboom, which are about reaching a precise point via a non-walking action)
					// gets the simpler straight tracer beam instead.
					RouteItem closest = instance.closestStepItem(room, mc.player.position());
					if (closest != null && closest.type != StepType.WAYPOINT) {
						WorldRenderUtil.drawTracer(graphics, realPositionOf(room, closest), routeItemColor(closest), 2, false);
					}
				} catch (Exception e) {
					com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Dungeon Routes waypoint tracer render failed, skipping this frame", e);
				}
			});

			DungeonBlockDetector.addListener((pos, type) -> {
				if (instance != null) instance.onBlockEvent(pos, type);
			});

			net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
				if (instance == null) return;
				instance.activeRoomName = null;
				instance.activeRoomVariant = "";
				instance.stepByRoom.clear();
				roomAlignmentOffsets.clear();
			});
		}
	}

	// Per user request ("Allow multiple etherwarp points per single Etherwarp step, list-based, like Blocks
	// already works"): ENDERWARP_WAYPOINT moved off the single x/y/z "positional" system entirely and onto
	// the same {@link RouteItem#blocks} list BREAKABLE_BLOCKS already uses (an etherwarp target is a real
	// block you land on, same as a block you break) — see the GUI editor (drawRouteItemBlock in MainScreen)
	// and renderWorldMarkers below for the matching list-based capture/render logic. It's excluded from
	// isPositional here for exactly the same reason BREAKABLE_BLOCKS already is: there's no single point to
	// navigate a tracer/path line toward when the step has a whole list of them.
	private static boolean isPositional(StepType type) {
		return type == StepType.WAYPOINT || type == StepType.SECRET_PICKUP || type == StepType.SECRET_CHEST
			|| type == StepType.SECRET_BAT || type == StepType.SECRET_ESSENCE || type == StepType.SECRET_LEVER
			|| type == StepType.ENDERPEARL_WAYPOINT || type == StepType.SUPERBOOM_BLOCK;
	}

	/** List-based types that get their own per-point box rendered from {@link RouteItem#blocks} instead of a
	 *  single {@link RouteItem#x}/{@link RouteItem#y}/{@link RouteItem#z} — same rendering shape
	 *  ENDERWARP_WAYPOINT already used before INTERACT_WAYPOINT existed (see the SecretRoutes import doc
	 *  comment on {@link StepType}). "Done" is proximity-based for both: reached the moment the player is
	 *  near ANY listed point. */
	private static boolean isPointList(StepType type) {
		return type == StepType.ENDERWARP_WAYPOINT || type == StepType.INTERACT_WAYPOINT;
	}

	/** The live {@link DungeonRoom} the player is currently standing in, only if it's both geometry-resolved
	 *  and actually the same room this feature's step progression is currently tracking (activeRoomName) —
	 *  null otherwise, which every caller below treats as "nothing to do right now". */
	private DungeonRoom resolvedActiveRoom() {
		if (activeRoomName == null) return null;
		DungeonRoom room = WorldScan.getCurrentRoom();
		if (room == null || room.clayPos == null || room.rotation == null) return null;
		if (!activeRoomName.equals(room.getName())) return null;
		return room;
	}

	private List<RouteItem> itemsForRoom(String roomName) {
		if (roomName == null) return List.of();
		List<RouteItem> result = new ArrayList<>();
		for (RouteItem item : items) if (item.roomName.equals(roomName)) result.add(item);
		return result;
	}

	/** Just this room's items belonging to whichever variant {@link #pickVariant} chose on entry — every
	 *  internal tick/render/click-detection call site uses this instead of the raw {@link #itemsForRoom},
	 *  so an imported room with several alternate routes (SecretRoutes' own "RoomName:1", "RoomName:2", ...)
	 *  only ever tracks/highlights ONE of them at a time. The public editor-facing {@link #getItemsForRoom}
	 *  deliberately stays variant-unaware (shows every variant's steps together) — building a variant picker
	 *  into the editor UI itself is out of scope for what this exists to support (picking the right route to
	 *  FOLLOW while playing, not authoring/managing several by hand). */
	private List<RouteItem> activeItems() {
		if (activeRoomName == null) return List.of();
		List<RouteItem> result = new ArrayList<>();
		for (RouteItem item : items) if (item.roomName.equals(activeRoomName) && item.variant.equals(activeRoomVariant)) result.add(item);
		return result;
	}

	/** Chooses which variant of {@code roomName}'s route is active for this visit — trivial (the room's only
	 *  variant, almost always {@code ""}) unless an import actually left more than one, in which case this
	 *  always picks the SAME one (whichever sorts first), regardless of where the player entered the room.
	 *
	 *  <p>Per user report ("the closest step thing is really weird, this means that it can start on the last
	 *  step and just not show the rest of the route... if you cant find a way to combat this... just remove
	 *  that feature and make the routes load the same not depending on where the player entered the room"):
	 *  this used to compare the player's real position on entry against each variant's own first-step
	 *  reference point and pick whichever was closest (mirroring SecretRoutes' own {@code Room.getData}). The
	 *  real bug that produced the reported symptom: {@link #stepByRoom} is keyed by room name only, not by
	 *  (room, variant) — if two visits to the same room picked DIFFERENT variants (entirely possible walking
	 *  in from a different angle the second time), the step number saved from the first visit — valid for
	 *  variant A's step count — got reused against variant B's completely different item set on the second,
	 *  which is exactly "starts on the last step and doesn't show the rest of the route" whenever variant B
	 *  has fewer steps than whatever number got saved. Fixed at the root per the user's own fallback
	 *  instruction: variant selection no longer depends on player position at all, so it can never change
	 *  between visits to the same room in the same run. */
	private void pickVariant(String roomName) {
		List<RouteItem> all = itemsForRoom(roomName);
		String best = null;
		for (RouteItem it : all) {
			if (best == null || it.variant.compareTo(best) < 0) best = it.variant;
		}
		activeRoomVariant = best != null ? best : "";
	}

	/** The step currently active FOR THAT ROOM — whatever's actually stored in {@link #stepByRoom}, or (if
	 *  nothing's been recorded there yet) 1 if the room has any items at all, 0 otherwise. Deliberately
	 *  NEVER caches that fallback into the map — real bug found (per user report — "the routes arent
	 *  loading... Ive made a route for the room 'Lava ravine'... nothing makes the first step appear"): this
	 *  used to be {@code computeIfAbsent}, which permanently pinned a room at step 0 the moment it was first
	 *  looked up with zero items (e.g. just walking into it before authoring any steps yet, or before this
	 *  particular run's own stepByRoom entry existed) — adding real items to that room afterward never
	 *  un-stuck it, since the cached 0 was never re-derived. Read-only computation here fixes that: a room
	 *  with no recorded progress simply re-evaluates "does it have items now" on every call instead of
	 *  trusting a stale answer from whenever it happened to be asked first. */
	private int stepFor(String roomName) {
		if (roomName == null) return 0;
		Integer stored = stepByRoom.get(roomName);
		if (stored != null) return stored;
		return itemsForRoom(roomName).isEmpty() ? 0 : 1;
	}

	private List<RouteItem> currentStepItems() {
		List<RouteItem> myItems = activeItems();
		int step = stepFor(activeRoomName);
		List<RouteItem> result = new ArrayList<>();
		for (RouteItem item : myItems) if (item.stepNumber == step) result.add(item);
		return result;
	}

	/** Whichever POSITIONAL item on the current step is physically closest to the player right now — see
	 *  the tracer/beacon-line render sites' own doc comments for why only one item is ever navigated toward
	 *  at a time, even when several share the same step. Null if the current step has no positional items
	 *  at all (e.g. it's only a Breakable Blocks step, which has no single point to navigate to). */
	private RouteItem closestStepItem(DungeonRoom room, Vec3 playerPos) {
		RouteItem closest = null;
		double closestDistSq = Double.MAX_VALUE;
		for (RouteItem item : currentStepItems()) {
			if (!isPositional(item.type)) continue;
			double distSq = playerPos.distanceToSqr(realPositionOf(room, item));
			if (distSq < closestDistSq) {
				closestDistSq = distSq;
				closest = item;
			}
		}
		return closest;
	}

	/** Sentinel step value meaning "route finished in this room, nothing left to render" — distinct from any
	 *  real step number (which are always &gt;= 0), so {@link #currentStepItems()} naturally returns empty
	 *  once a room is set to this and every renderer that keys off it (tracer, path line, highlight boxes)
	 *  stops drawing on its own without needing its own separate "are we done" check. */
	private static final int ROUTE_FINISHED_STEP = -1;

	private void advanceStep(String roomName, List<RouteItem> myItems) {
		int current = stepFor(roomName);
		int next = myItems.stream().mapToInt(i -> i.stepNumber).filter(n -> n > current).min().orElse(ROUTE_FINISHED_STEP);
		if (next == ROUTE_FINISHED_STEP) {
			// Real bug found (per user report — "After the last step of the dungeon route the secrets should
			// stop rendering in that room completely... currently I have to add a waypoint at 0 0 or something
			// so it stops rendering cause otherwise it just stays on the last step"): this used to fall back to
			// `current` when no higher step existed, which is indistinguishable from "still on the last step"
			// — every renderer kept treating the finished route as still-active forever. With Edit Mode on,
			// loop back to the first step instead so the route can be re-walked without leaving/re-entering.
			if (editMode) next = myItems.stream().mapToInt(i -> i.stepNumber).min().orElse(current);
		}
		stepByRoom.put(roomName, next);
	}

	// Real bug found (per user report — "if i spam a chest to make sure i get it... the mod only confirms i
	// clicked a chest whatsoever. It doesnt check for which chest i clicked. If i spam a chest and the next
	// secret is a chest, it just completely skips the next step because i just clicked a chest which the mod
	// thought was my current secret"): SECRET_CHEST/SECRET_LEVER below used to match on the clicked block's
	// TYPE alone, with no check that the specific block clicked was anywhere near THIS step's own expected
	// chest/lever — any chest or lever click anywhere in the room satisfied it. Gated on real proximity to
	// the step's own realPositionOf now, same distance-based "close enough" check every other positional step
	// type in this class already uses (see WAYPOINT_REACHED_DISTANCE's other call sites) but tighter, since a
	// block click's position is exact rather than a walked-to approximation.
	private static final double SECRET_CLICK_MAX_DISTANCE = 3.0;

	/** Fed by {@link MimicFeature} on a confirmed Mimic kill — see that class's own doc comment on
	 *  {@code mimicKilled} for why a real chest click can never fire near a live Mimic (its entity hitbox
	 *  intercepts the raycast before it ever reaches the disguised block). Routed through the exact same
	 *  {@link #onBlockEvent} path a real chest click uses, as a {@code TRAPPED_CHEST} type (already one of
	 *  the two block types {@code SECRET_CHEST} steps accept), so the kill advances a matching step exactly
	 *  like clicking the chest would have. */
	static void notifyMimicChestRevealed(BlockPos pos) {
		if (instance != null) instance.onBlockEvent(pos, DungeonBlockDetector.ClickedBlockType.TRAPPED_CHEST);
	}

	private void onBlockEvent(BlockPos pos, DungeonBlockDetector.ClickedBlockType type) {
		if (!isEnabled() || activeRoomName == null) return;
		List<RouteItem> myItems = activeItems();
		int step = stepFor(activeRoomName);
		DungeonRoom room = resolvedActiveRoom();
		for (RouteItem item : myItems) {
			if (item.stepNumber != step) continue;
			boolean matches = switch (item.type) {
				// Real gap found (per user report — "the secret pickup step in the dungeon routes still is not
				// triggering when an item is picked up"): ITEM_SECRET_PICKUP alone only ever covers
				// DungeonBlockDetector's own fixed item-id whitelist, which keeps missing real dungeon items
				// nobody's specifically reported yet. ANY_ITEM_PICKUP (see that type's own doc comment) fires
				// for every other real local pickup — accepted here too, gated on the exact same real
				// ground-truth proximity check SECRET_CHEST/SECRET_LEVER below already use, so a pickup nowhere
				// near this step's own expected position still can't wrongly advance it.
				case SECRET_PICKUP -> type == DungeonBlockDetector.ClickedBlockType.ITEM_SECRET_PICKUP
					|| (type == DungeonBlockDetector.ClickedBlockType.ANY_ITEM_PICKUP
						&& pos != null && room != null && Vec3.atCenterOf(pos).distanceTo(realPositionOf(room, item)) <= SECRET_CLICK_MAX_DISTANCE);
				case SECRET_CHEST -> (type == DungeonBlockDetector.ClickedBlockType.CHEST || type == DungeonBlockDetector.ClickedBlockType.TRAPPED_CHEST)
					&& pos != null && room != null && Vec3.atCenterOf(pos).distanceTo(realPositionOf(room, item)) <= SECRET_CLICK_MAX_DISTANCE;
				case SECRET_BAT -> type == DungeonBlockDetector.ClickedBlockType.SECRET_BAT_DEATH;
				case SECRET_ESSENCE -> type == DungeonBlockDetector.ClickedBlockType.WITHER_ESSENCE_PICKUP;
				case SECRET_LEVER -> type == DungeonBlockDetector.ClickedBlockType.LEVER
					&& pos != null && room != null && Vec3.atCenterOf(pos).distanceTo(realPositionOf(room, item)) <= SECRET_CLICK_MAX_DISTANCE;
				default -> false;
			};
			// A click event is a one-shot signal (unlike onTick's polled "done" state above), so a matching
			// but non-advancing item still needs to stop the loop here — there's nothing left to re-check
			// later the way onTick's own loop would. It just doesn't call advanceStep.
			if (matches) {
				// See roomAlignmentOffsets' own field doc comment — a real secret-chest click is exactly the
				// kind of ground-truth position this self-correction needs, and pos is only non-null here
				// when DungeonBlockDetector actually resolved a real clicked block.
				if (item.type == StepType.SECRET_CHEST && pos != null && room != null) {
					recordAlignmentCorrection(room, item, pos);
				}
				if (item.advancesStep) advanceStep(activeRoomName, myItems);
				break;
			}
		}
	}

	/** See roomAlignmentOffsets' own field doc comment for why this exists at all. */
	private static void recordAlignmentCorrection(DungeonRoom room, RouteItem item, BlockPos actualChestPos) {
		Vec3 expected = realPositionOf(room, item);
		Vec3 actual = Vec3.atCenterOf(actualChestPos);
		Vec3 delta = actual.subtract(expected);
		if (delta.length() < 0.01 || delta.length() > MAX_ALIGNMENT_CORRECTION_BLOCKS) return;
		Vec3 existing = roomAlignmentOffsets.get(room);
		Vec3 combined = existing != null ? existing.add(delta) : delta;
		roomAlignmentOffsets.put(room, combined);
		com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.info(
			"[DungeonRoutes] Real secret chest at {} disagreed with expected {} for room {} (item step {}) — applying a ({}, {}, {}) alignment correction",
			actualChestPos, expected, room.getName(), item.stepNumber, combined.x, combined.y, combined.z);
	}

	@Override
	public void onTick(Minecraft client) {
		// Per user report ("it should reset on dungeon leave"): same runId-comparison pattern
		// DoorHighlightFeature already uses to catch both "left the dungeon entirely" AND a direct
		// dungeon-to-dungeon rejoin (which never flips isInDungeon() false->true at all) as one real "a new
		// dungeon run started" signal — the only time stepByRoom should actually be thrown away.
		boolean inDungeonNow = DungeonState.isInDungeon();
		int runId = DungeonState.getRunId();
		if (inDungeonNow && (!wasInDungeonForRouteState || runId != lastSeenRunId)) {
			stepByRoom.clear();
		}
		wasInDungeonForRouteState = inDungeonNow;
		lastSeenRunId = runId;

		if (client.level == null || client.player == null) return;
		if (!inDungeonNow || DungeonState.isInBoss()) return;
		DungeonRoom room = resolvedActiveRoom();
		if (room == null) return;

		List<RouteItem> myItems = activeItems();
		if (myItems.isEmpty()) return;

		int step = stepFor(activeRoomName);
		for (RouteItem item : myItems) {
			if (item.stepNumber != step) continue;
			boolean done = switch (item.type) {
				case BREAKABLE_BLOCKS -> !item.blocks.isEmpty() && allBlocksBroken(item, room, client.level);
				// Per user request ("Allow multiple etherwarp points per single Etherwarp step, list-based,
				// like Blocks already works"): done the moment the player reaches ANY of the listed points —
				// only one of possibly several designated etherwarp spots needs to actually be used. Same
				// mechanic now also covers the imported "Interact" waypoint category (see StepType's own doc
				// comment) — an intermediate button/plate is "used" the same way, by reaching it.
				case ENDERWARP_WAYPOINT, INTERACT_WAYPOINT -> !item.blocks.isEmpty() && item.blocks.stream()
					.anyMatch(relative -> client.player.position().distanceTo(Vec3.atCenterOf(room.getRealCoords(relative))) <= WAYPOINT_REACHED_DISTANCE);
				// A real ender pearl both lands the player at (roughly) the target spot, same as physically
				// walking to a WAYPOINT — reuses the identical proximity check.
				case WAYPOINT, ENDERPEARL_WAYPOINT -> client.player.position().distanceTo(realPositionOf(room, item)) <= WAYPOINT_REACHED_DISTANCE;
				// Per user request ("should advance when a TNT entity is actually placed in the target
				// square, rather than only tracking player position near it"): a real vanilla PrimedTnt
				// entity spawning near the target IS the actual TNT placement — confirmed as the genuine,
				// already-used-elsewhere signal for a real Superboom TNT going off (see
				// DungeonObjectPredicates#isSuperboomTnt's own doc comment: "Hypixel spawns a genuine
				// primed-TNT entity for the explosion itself... can't legitimately appear on a Catacombs/
				// Master Mode floor any other way"). Player proximity alone used to count "standing near the
				// wall" as done, which fired even if the player never actually placed the TNT at all.
				// Per user report ("Superboom tnt part of the routes doesnt seem to update when i place tnt
				// inside of it"): the underlying PrimedTnt-entity signal is the same one already proven
				// correct elsewhere (DungeonObjectPredicates#isSuperboomTnt, used by the Declutter TNT hider),
				// so the detection MECHANISM isn't in question — widened the search radius from 1.5 to 3.0
				// blocks instead, since a real thrown/placed TNT landing anywhere near the target wall square
				// (not dead-center on the exact block) previously fell just outside the old radius and never
				// matched at all. A false match from some unrelated real TNT is a non-issue either way — per
				// isSuperboomTnt's own doc comment, a genuine PrimedTnt simply can't legitimately exist on a
				// Catacombs/Master Mode floor any other way.
				// Round 2 (per user report — "Superboom TNT detector still isn't working. I think its because
				// superboom tnt isn't actually getting placed"): the PrimedTnt-entity signal above stays as
				// one real path (still correct when it DOES fire), but the user's own theory is that no such
				// entity is guaranteed to spawn at all for every real Superboom throw — no amount of tuning
				// that one signal fixes a case where the server never emits it. Added a second, genuinely
				// independent real signal per the user's exact suggestion: player within 5 blocks of the
				// target AND a real inventory-confirmed use (Superboom TNT stack shrinking, or Infiniboom
				// Powder's own use-flicker — see SuperboomUseTracker's own doc comment) observed recently.
				// Either signal marks the step done.
				case SUPERBOOM_BLOCK -> {
					BlockPos target = BlockPos.containing(realPositionOf(room, item));
					boolean primedTntNearby = !client.level.getEntities(client.player, new AABB(target).inflate(3.0),
						e -> e instanceof net.minecraft.world.entity.item.PrimedTnt).isEmpty();
					boolean usedNearby = client.player.position().distanceTo(Vec3.atCenterOf(target)) <= SUPERBOOM_USE_RANGE
						&& com.cokelord.skyblocksimplified.dungeon.SuperboomUseTracker.usedWithinMillis(SUPERBOOM_USE_WINDOW_MILLIS);
					yield primedTntNearby || usedNearby;
				}
				// Per user report ("The steps for the secrets should hide when clicked if they arent
				// already"): a lever is the one clickable secret type with a real, persistent, queryable
				// "already done" world state (its own POWERED block state) — a chest/wither essence pickup has
				// no vanilla equivalent, so those stay click-event-only (onBlockEvent above).
				case SECRET_LEVER -> isLeverAlreadyPowered(room, item, client.level);
				case SECRET_PICKUP, SECRET_CHEST, SECRET_BAT, SECRET_ESSENCE -> false;
			};
			// Real bug avoided here: only actually BREAK the loop once something advances the step — an item
			// that's done but marked not to advance (see RouteItem#advancesStep) must NOT stop the loop from
			// still checking the other items sharing this step, or a non-advancing item that's permanently
			// "done" (e.g. an Etherwarp Waypoint the player is already standing on) would silently block
			// every OTHER item on the same step from ever being checked again, every tick, forever.
			if (done && item.advancesStep) { advanceStep(activeRoomName, myItems); break; }
		}
	}

	private static boolean isLeverAlreadyPowered(DungeonRoom room, RouteItem item, Level level) {
		BlockPos pos = BlockPos.containing(realPositionOf(room, item));
		var state = level.getBlockState(pos);
		return state.hasProperty(LeverBlock.POWERED) && state.getValue(LeverBlock.POWERED);
	}

	// Per user request ("the coordinates for it should be able to have 1 decimal point"): a route item's
	// own x/y/z were already stored as doubles and displayed with 1-decimal precision (fmt()), but every
	// consumer silently floored them to a whole BlockPos before use — decimal input was accepted by the
	// text field and then quietly discarded. DungeonRoom's own getRelativeCoords/getRealCoords only operate
	// on BlockPos (whole blocks), so these are the same real rotation math (see DungeonRoom's own
	// rotateAroundNorth/rotateToNorth, which are private there) rebuilt for a fractional Vec3 instead,
	// preserving the sub-block precision the user actually asked for — needed for the enderpearl waypoint
	// in particular, where "throw at the exact spot I'm aiming at" often isn't block-aligned.
	private static Vec3 realPositionOf(DungeonRoom room, RouteItem item) {
		Vec3 rotated = rotateVecAroundNorth(new Vec3(item.x, item.y, item.z), room.rotation);
		Vec3 world = rotated.add(room.clayPos.getX(), 0, room.clayPos.getZ());
		Vec3 correction = roomAlignmentOffsets.get(room);
		return correction != null ? world.add(correction) : world;
	}

	private static Vec3 realPositionOf(DungeonRoom room, double relX, double relY, double relZ) {
		Vec3 rotated = rotateVecAroundNorth(new Vec3(relX, relY, relZ), room.rotation);
		Vec3 world = rotated.add(room.clayPos.getX(), 0, room.clayPos.getZ());
		Vec3 correction = roomAlignmentOffsets.get(room);
		return correction != null ? world.add(correction) : world;
	}

	/** Yaw offset added to a room-relative facing angle to get the real world yaw a captured/imported pearl
	 *  throw actually needs — independently derived from (and consistent with) {@link #rotateVecAroundNorth}'s
	 *  own position transform per rotation, since a yaw is really just the angle of a 2D direction vector
	 *  undergoing that exact same rotation. Confirmed to exactly match SecretRoutes' own
	 *  {@code RotationUtils.relativeToActualYaw} (S=0, W=+90, N=+180, E=+270/-90) despite being worked out
	 *  independently — real evidence the two mods store this room-relative coordinate space identically. */
	private static float roomYawOffset(RoomRotation rotation) {
		return switch (rotation) {
			case SOUTH -> 0f;
			case WEST -> 90f;
			case NORTH -> 180f;
			case EAST -> -90f;
		};
	}

	private static Vec3 relativeVecFromReal(DungeonRoom room, Vec3 real) {
		Vec3 correction = roomAlignmentOffsets.get(room);
		Vec3 corrected = correction != null ? real.subtract(correction) : real;
		Vec3 offset = corrected.subtract(room.clayPos.getX(), 0, room.clayPos.getZ());
		return rotateVecToNorth(offset, room.rotation);
	}

	private static Vec3 rotateVecAroundNorth(Vec3 pos, RoomRotation rotation) {
		double x = pos.x, y = pos.y, z = pos.z;
		return switch (rotation) {
			case NORTH -> new Vec3(-x, y, -z);
			case WEST -> new Vec3(-z, y, x);
			case SOUTH -> new Vec3(x, y, z);
			case EAST -> new Vec3(z, y, -x);
		};
	}

	private static Vec3 rotateVecToNorth(Vec3 pos, RoomRotation rotation) {
		double x = pos.x, y = pos.y, z = pos.z;
		return switch (rotation) {
			case NORTH -> new Vec3(-x, y, -z);
			case WEST -> new Vec3(z, y, -x);
			case SOUTH -> new Vec3(x, y, z);
			case EAST -> new Vec3(-z, y, x);
		};
	}

	private static boolean allBlocksBroken(RouteItem item, DungeonRoom room, Level level) {
		for (BlockPos relative : item.blocks) {
			if (level.getBlockState(room.getRealCoords(relative)).isSolid()) return false;
		}
		return true;
	}

	// Per later user request ("Our waypoint path system was good with the line from the player. Revert that
	// and add the moving line back and remove the skinny weird lines"): the bounded A* pathfinding subsystem
	// that used to live in this section (recalculatePaths/findPath/simplifyPath/isWalkable/et al) is removed
	// entirely — see renderWorldMarkers' own WAYPOINT branch below for the live straight-line replacement.

	// ---- World rendering (Breakable Blocks / Waypoint / Secret highlights) ----

	/** Per user correction ("Only enderpearl should be 0.5 blocks") — the highlight size is per-type:
	 *  Enderpearl Waypoint stays a small 0.5-block cube (a precise point to throw at), Etherwarp Waypoint is
	 *  a full 1x1x1 block (per user request — "It should just highlight the block"), Waypoint keeps the
	 *  original 1x2x1 "player stands here" box (a spot you physically walk to and stand in, unlike a secret
	 *  you interact with once). Real bug found (per user report — "The boxes for chests are rendering too
	 *  high. They are two tall. All secret highlights should be a block exactly"): every SECRET_* type
	 *  shared that same 1x2x1 box, which reads as "floating a block too high" for anything that isn't
	 *  actually a place to stand — a chest/lever/pickup/bat/essence is one specific block, not a 2-tall
	 *  standing spot. Shrunk to a real 1x1x1 box anchored to the same floored block position. */
	private static AABB positionalBox(StepType type, Vec3 center) {
		return switch (type) {
			case ENDERPEARL_WAYPOINT -> {
				double half = 0.25;
				yield new AABB(center.x - half, center.y - half, center.z - half, center.x + half, center.y + half, center.z + half);
			}
			case ENDERWARP_WAYPOINT -> {
				double half = 0.5;
				yield new AABB(center.x - half, center.y - half, center.z - half, center.x + half, center.y + half, center.z + half);
			}
			case WAYPOINT -> {
				int fx = (int) Math.floor(center.x), fy = (int) Math.floor(center.y), fz = (int) Math.floor(center.z);
				yield new AABB(fx, fy, fz, fx + 1.0, fy + 2.0, fz + 1.0);
			}
			default -> {
				int fx = (int) Math.floor(center.x), fy = (int) Math.floor(center.y), fz = (int) Math.floor(center.z);
				yield new AABB(fx, fy, fz, fx + 1.0, fy + 1.0, fz + 1.0);
			}
		};
	}

	private static void renderWorldMarkers() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			DungeonRoom room = instance.resolvedActiveRoom();
			if (room == null) return;
			// Per user request ("the routes should not render unless room is cleared and there are actually
			// secrets to take, i.e not 5/5 for example"): a room's own map-pixel checkmark already tells us
			// exactly when Hypixel itself considers it cleared — but per this codebase's own established
			// WHITE/GREEN distinction (see DungeonNotificationsFeature's "Room Cleared!" (WHITE) vs "Room
			// Complete!" (GREEN), and DungeonMapFeature's own SECRETS label mode doc comment: "GREEN... every
			// secret in it was found"), WHITE means "cleared" (mobs dead) while GREEN means "cleared AND all
			// secrets already found". Real bug found (per user report — "Secret routes dont render at all...
			// I think you messed that up"): requiring GREEN here was requiring the room to ALREADY have every
			// secret found before rendering anything — but the very next line then bails out once secrets are
			// maxed, so the two conditions almost never both held at once and nothing ever rendered. Accepting
			// WHITE (mobs dead, not yet secret-maxed) alongside GREEN restores the real intended gate: render
			// once the room is cleared, stop once secrets are already maxed.
			if (room.checkmark != MapCheckmark.WHITE && room.checkmark != MapCheckmark.GREEN) return;
			if (room.data != null && room.secretsFound >= 0 && room.secretsFound >= room.data.getSecrets()) return;
			// Per user report ("The tracers and line should only render the closest step [item]... it
			// renders a tracer and line to both") — every item on the current step still gets its own
			// highlight box below (that part is unaffected), but the beacon LINE specifically should only
			// ever point at one of them at a time.
			Minecraft mcForClosest = Minecraft.getInstance();
			RouteItem closestForLine = mcForClosest.player != null ? instance.closestStepItem(room, mcForClosest.player.position()) : null;
			for (RouteItem item : instance.currentStepItems()) {
				if (item.type == StepType.BREAKABLE_BLOCKS) {
					List<BlockPos> realBlocks = new ArrayList<>();
					for (BlockPos relative : item.blocks) realBlocks.add(room.getRealCoords(relative));
					for (AABB box : mergeAdjacentBlockBoxes(realBlocks)) {
						int fillAlpha = Math.round(instance.highlightOpacityPercent / 100f * 255f);
						World3DRenderer.drawFilledBoxThroughWalls(box, (fillAlpha << 24) | (item.blockColor & 0xFFFFFF));
						World3DRenderer.drawWireBoxThroughWalls(box, item.blockColor, 2f);
					}
				} else if (isPointList(item.type)) {
					// Per user request ("Allow multiple etherwarp points per single Etherwarp step") — each
					// stored point gets its own individual 1x1x1 box (positionalBox's ENDERWARP_WAYPOINT case),
					// not merged like Breakable Blocks' boxes are — these are separate teleport/interact
					// destinations, not one contiguous shape. Same rendering now also covers the imported
					// Interact waypoint category (see StepType's own doc comment).
					int color = routeItemColor(item);
					int fillAlpha = Math.round(instance.highlightOpacityPercent / 100f * 255f);
					for (BlockPos relative : item.blocks) {
						Vec3 center = Vec3.atCenterOf(room.getRealCoords(relative));
						AABB box = positionalBox(item.type, center);
						World3DRenderer.drawFilledBoxThroughWalls(box, (fillAlpha << 24) | (color & 0xFFFFFF));
						World3DRenderer.drawWireBoxThroughWalls(box, color, 2f);
					}
				} else if (isPositional(item.type)) {
					Vec3 center = realPositionOf(room, item);
					AABB box = positionalBox(item.type, center);
					int color = routeItemColor(item);
					int fillAlpha = Math.round(instance.highlightOpacityPercent / 100f * 255f);
					World3DRenderer.drawFilledBoxThroughWalls(box, (fillAlpha << 24) | (color & 0xFFFFFF));
					World3DRenderer.drawWireBoxThroughWalls(box, color, 2f);

					// Per user report ("random lines going through the air with waypoints, remove those i dont
					// like how those look") — the directional throw-angle line previously drawn from each
					// pearl shot is gone; every captured/imported pearl shot still gets its own box (shot 0
					// coincides with the legacy center box above, so only the rest need a separate one), just
					// no line shooting out from it. The underlying angle data (PearlShot#yaw/pitch) is kept —
					// only the render was the problem, not the capture.
					if (item.type == StepType.ENDERPEARL_WAYPOINT && item.pearlShots.size() > 1) {
						for (int i = 1; i < item.pearlShots.size(); i++) {
							PearlShot shot = item.pearlShots.get(i);
							Vec3 shotCenter = realPositionOf(room, shot.x, shot.y, shot.z);
							AABB shotBox = positionalBox(item.type, shotCenter);
							World3DRenderer.drawFilledBoxThroughWalls(shotBox, (fillAlpha << 24) | (color & 0xFFFFFF));
							World3DRenderer.drawWireBoxThroughWalls(shotBox, color, 2f);
						}
					}

					// Per later user request ("Our waypoint path system was good with the line from the player.
					// Revert that and add the moving line back and remove the skinny weird lines"): no more
					// cached/pathfound line — just a live straight line from the player's current position to
					// the target, redrawn fresh every frame (hence "moving"), same as this looked before the
					// A* pathfinding system existed. The SecretRoutes-imported pathPoints line (the "skinny
					// weird lines" — a separate, pre-authored walking path some imports carry) is no longer
					// rendered at all now; RouteItem#pathPoints itself is left alone so existing imports still
					// parse without error, it's just not drawn.
					if (instance.waypointBeaconLine && item == closestForLine && item.type == StepType.WAYPOINT) {
						Minecraft mc = Minecraft.getInstance();
						if (mc.player != null) {
							World3DRenderer.drawLineThroughWalls(mc.player.position(), center, color | 0xFF000000, 3f);
						}
					}
				}
			}
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Dungeon Routes world render failed, skipping this frame", e);
		}
	}

	/** Same 26-connectivity adjacent-block-grouping helper as {@link DungeonsCopilotFeature} — kept as its
	 *  own small copy rather than a shared util, matching how each feature here already owns its render
	 *  helpers independently. */
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

	// ---- GUI editing surface (called from MainScreen) ----

	public boolean isWaypointTracer() { return waypointTracer; }
	public void setWaypointTracer(boolean value) { waypointTracer = value; }
	public boolean isWaypointBeaconLine() { return waypointBeaconLine; }
	public void setWaypointBeaconLine(boolean value) { waypointBeaconLine = value; }
	public int getHighlightOpacityPercent() { return highlightOpacityPercent; }
	public void setHighlightOpacityPercent(int value) { highlightOpacityPercent = Math.max(0, Math.min(100, value)); }
	public boolean isEditMode() { return editMode; }
	public void setEditMode(boolean value) { editMode = value; }

	/** The room the player is CURRENTLY standing in and actively tracking steps for — same value {@link
	 *  #resolvedActiveRoom()} itself gates on, so unlike a raw {@code WorldScan.getCurrentRoom()} call this
	 *  already respects the boss-room/dungeon-state checks {@link #onTick} applies before ever setting
	 *  {@link #activeRoomName}. Used by MainScreen's "Open current room" button. */
	public String getActiveRoomName() { return activeRoomName; }

	public List<String> getAllRoomNames() { return RoomData.getAllRoomNames(); }

	public List<RouteItem> getItemsForRoom(String roomName) { return itemsForRoom(roomName); }

	public int getStepCountForRoom(String roomName) {
		java.util.Set<Integer> steps = new java.util.TreeSet<>();
		for (RouteItem item : itemsForRoom(roomName)) steps.add(item.stepNumber);
		return steps.size();
	}

	public void addItem(String roomName) {
		RouteItem item = new RouteItem();
		item.roomName = roomName;
		int maxStep = 0;
		RouteItem lastItem = null;
		// Per user report ("The routes STILL dont go in the order of steps for some reason"): a room with more
		// than one imported variant (SecretRoutes' own "RoomName:1", "RoomName:2", ...) has each variant's own
		// independent 1..N stepNumber sequence — this used to scan every item in the room regardless of
		// variant, so a manually-added item's stepNumber (and reorderItem's renumbering below) mixed those
		// separate sequences together and scrambled whichever variant wasn't being edited. Scoped to the new
		// item's own variant (empty string, same as every manually-authored item) so it only ever competes
		// with steps that actually belong to the same route.
		for (RouteItem existing : items) {
			if (!existing.roomName.equals(roomName) || !existing.variant.equals(item.variant)) continue;
			if (lastItem == null || existing.stepNumber >= maxStep) {
				maxStep = existing.stepNumber;
				lastItem = existing;
			}
		}
		// Per user request ("If 'Advance step' is off for the last step/last added step, the next step added
		// should be on the same step as last, this is just QOL since advance step off means it needs another
		// trigger with it on, otherwise the route can't continue obviously"): appending at maxStep + 1
		// unconditionally used to silently create an unreachable step whenever the last step's Advance Step
		// was off — nothing would ever call advanceStep() past that item, so the new step could never activate.
		item.stepNumber = (lastItem != null && !lastItem.advancesStep) ? maxStep : maxStep + 1;
		applyDefaultColor(item);
		items.add(item);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	// Real bug found (per user report — "Dungeon routes still has issues going to the next step instead of
	// skipping steps for some reason"): this used to just remove the item with NO renumbering afterward — if
	// the removed item was the only one at its stepNumber, every later item in that room/variant kept its
	// OLD stepNumber, leaving a permanent gap (e.g. steps 1,2,4,5 with nothing at 3). advanceStep's own
	// "next higher stepNumber present" logic has no way to know 3 was ever skipped on purpose vs. never
	// existing at all — jumping straight from 2 to 4 the moment step 2 completes, exactly the reported
	// symptom, and completely independent of (and far more directly triggerable than) the duplicate-secret
	// import case a previous round already fixed. Deleting a step through the editor is a routine, everyday
	// action, so this gap could open on essentially any edited route. Fixed the same way
	// SecretRoutesImporter's own dedupe pass closes an import-time gap: renumber the room/variant's
	// remaining items to a gapless 1..N sequence afterward, keeping items that already shared a stepNumber
	// grouped together.
	public void removeItem(RouteItem item) {
		items.remove(item);
		List<RouteItem> roomItems = new ArrayList<>();
		for (RouteItem it : itemsForRoom(item.roomName)) if (it.variant.equals(item.variant)) roomItems.add(it);
		roomItems.sort((a, b) -> Integer.compare(a.stepNumber, b.stepNumber));
		int newStep = 0;
		int lastOldStep = Integer.MIN_VALUE;
		for (RouteItem it : roomItems) {
			if (it.stepNumber != lastOldStep) {
				newStep++;
				lastOldStep = it.stepNumber;
			}
			it.stepNumber = newStep;
		}
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Drag-reorder handle support (MainScreen's step-number box, dragged instead of clicked) — same
	 *  approach as {@link DungeonsCopilotFeature#reorderItem}: moves {@code item} to {@code newIndex} within
	 *  its own room's step order, then renumbers every step of that room sequentially.
	 *
	 *  <p>Per user report ("The routes STILL dont go in the order of steps for some reason"): this used to
	 *  operate on {@link #itemsForRoom} — every variant of the room mixed together — so reordering one item
	 *  renumbered every OTHER variant's steps too, scrambling whichever variant wasn't being edited (the
	 *  variant actually followed during a real run, per {@link #pickVariant}, is almost never the one the
	 *  editor happens to be showing at the time). Scoped to just {@code item}'s own variant so reordering one
	 *  route can no longer corrupt a different route sharing the same room. */
	public void reorderItem(RouteItem item, int newIndex) {
		List<RouteItem> roomItems = new ArrayList<>();
		for (RouteItem it : itemsForRoom(item.roomName)) if (it.variant.equals(item.variant)) roomItems.add(it);
		if (!roomItems.remove(item)) return;
		newIndex = Math.max(0, Math.min(newIndex, roomItems.size()));
		roomItems.add(newIndex, item);
		items.removeIf(i -> i.roomName.equals(item.roomName) && i.variant.equals(item.variant));
		items.addAll(roomItems);
		for (int i = 0; i < roomItems.size(); i++) roomItems.get(i).stepNumber = i + 1;
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void cycleType(RouteItem item) {
		int currentIndex = -1;
		for (int i = 0; i < SELECTABLE_TYPES.length; i++) {
			if (SELECTABLE_TYPES[i] == item.type) { currentIndex = i; break; }
		}
		// An existing INTERACT_WAYPOINT item (old data) isn't in SELECTABLE_TYPES at all — cycling it starts
		// from the front of the list instead of indexing off a -1 that was never in range.
		item.type = SELECTABLE_TYPES[(currentIndex + 1) % SELECTABLE_TYPES.length];
		applyDefaultColor(item);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Public so {@link com.cokelord.skyblocksimplified.dungeon.SecretRoutesImporter} can give every imported
	 *  item the same real per-type default color the editor's own "add item"/"cycle type" actions apply —
	 *  per user report ("All the colors for the waypoints are the exact same"), the importer never called
	 *  this before, so every imported item fell back to whichever field initializer {@link RouteItem}
	 *  happened to declare (aqua for almost everything). */
	public static void applyDefaultColor(RouteItem item) {
		int color = defaultColorFor(item.type);
		if (item.type == StepType.BREAKABLE_BLOCKS) item.blockColor = color;
		else item.waypointColor = color;
	}

	/** True only while the player is standing in the SAME room this item belongs to — the only time this
	 *  mod has a real, geometry-resolved room instance to convert a real world position through into the
	 *  room-relative coordinates this item actually stores. */
	public boolean canCapturePosition(RouteItem item) {
		DungeonRoom room = resolvedActiveRoom();
		return room != null && item.roomName.equals(activeRoomName);
	}

	public void useLookedAtBlock(RouteItem item) {
		DungeonRoom room = resolvedActiveRoom();
		if (room == null || !item.roomName.equals(activeRoomName)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;
		BlockPos real = ((BlockHitResult) mc.hitResult).getBlockPos();
		BlockPos relative = room.getRelativeCoords(real);
		if (!item.blocks.contains(relative)) item.blocks.add(relative);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void clearBlocks(RouteItem item) {
		item.blocks.clear();
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Typed X/Y/Z is read as the real world coordinate (same as Boss Guide's identical field, and what the
	 *  player can see on F3) and converted to this item's stored room-relative coordinate through the
	 *  currently-resolved room — only works while standing in the room being edited (see canCapturePosition). */
	public void addBlockFromInput(RouteItem item) {
		DungeonRoom room = resolvedActiveRoom();
		if (room == null || !item.roomName.equals(activeRoomName)) return;
		try {
			int bx = (int) Math.floor(Double.parseDouble(item.xInput));
			int by = (int) Math.floor(Double.parseDouble(item.yInput));
			int bz = (int) Math.floor(Double.parseDouble(item.zInput));
			BlockPos relative = room.getRelativeCoords(new BlockPos(bx, by, bz));
			if (!item.blocks.contains(relative)) item.blocks.add(relative);
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
		} catch (NumberFormatException ignored) {}
	}

	public void useCurrentPosition(RouteItem item) {
		DungeonRoom room = resolvedActiveRoom();
		if (room == null || !item.roomName.equals(activeRoomName)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		setPositionFromReal(item, room, mc.player.position());
	}

	/** Per user request ("I should be able to select the block im looking at like the break block
	 *  highlights, and that's where they should throw a pearl"): same capture gesture as Breakable Blocks'
	 *  "Use Looked-At Block", but writes the looked-at block's CENTER into this item's own x/y/z (a single
	 *  precise target position) instead of adding it to a block list. */
	public void useLookedAtPosition(RouteItem item) {
		DungeonRoom room = resolvedActiveRoom();
		if (room == null || !item.roomName.equals(activeRoomName)) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.info(
				"[DungeonRoutes] useLookedAtPosition({}) failed: not standing in {} (resolvedRoom={}, activeRoomName={})",
				item.type, item.roomName, room != null ? room.getName() : null, activeRoomName);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.info(
				"[DungeonRoutes] useLookedAtPosition({}) failed: not looking at a block (hitResult={})",
				item.type, mc.hitResult == null ? null : mc.hitResult.getType());
			return;
		}
		BlockPos real = ((BlockHitResult) mc.hitResult).getBlockPos();
		setPositionFromReal(item, room, Vec3.atCenterOf(real));
		com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.info(
			"[DungeonRoutes] useLookedAtPosition({}) captured realBlock={} -> relative=({},{},{})",
			item.type, real, item.x, item.y, item.z);
	}

	/** Per user request ("this should have a currently-looking feature, instead of looking for currently
	 *  looked at block it should look at the yaw, pitch, and everything to make it just like the angle the
	 *  user is currently facing when the button is pressed") — captures the player's real position AND real
	 *  facing angle as a new {@link PearlShot}, for {@link StepType#ENDERPEARL_WAYPOINT} items specifically.
	 *  Deliberately doesn't require looking at a block (a pearl throw angle has nothing to aim at — you're
	 *  authoring the exact throw itself, standing where you'd throw from and facing the way you'd throw). The
	 *  first captured shot also becomes this item's legacy x/y/z so every existing distance/proximity check
	 *  elsewhere in this class keeps working unchanged. */
	public void useCurrentFacingAsPearl(RouteItem item) {
		DungeonRoom room = resolvedActiveRoom();
		if (room == null || !item.roomName.equals(activeRoomName)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Vec3 relative = relativeVecFromReal(room, mc.player.position());
		PearlShot shot = new PearlShot();
		shot.x = relative.x; shot.y = relative.y; shot.z = relative.z;
		shot.yaw = mc.player.getYRot() - roomYawOffset(room.rotation);
		shot.pitch = mc.player.getXRot();
		item.pearlShots.add(shot);
		if (item.pearlShots.size() == 1) {
			item.x = shot.x; item.y = shot.y; item.z = shot.z;
			item.xInput = fmt(item.x); item.yInput = fmt(item.y); item.zInput = fmt(item.z);
		}
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void clearPearlShots(RouteItem item) {
		item.pearlShots.clear();
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	private static void setPositionFromReal(RouteItem item, DungeonRoom room, Vec3 real) {
		Vec3 relative = relativeVecFromReal(room, real);
		item.x = relative.x; item.y = relative.y; item.z = relative.z;
		item.xInput = fmt(item.x); item.yInput = fmt(item.y); item.zInput = fmt(item.z);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Commits whatever's currently typed in the X/Y/Z fields to this item's actual stored position — per
	 *  user request ("the coordinates for it should be able to have 1 decimal point"), the room-relative
	 *  value itself (not a real-world coordinate needing room conversion, unlike Breakable Blocks' typed
	 *  XYZ), so no room/standing-in-it requirement applies here — it can be set from anywhere. */
	public void applyTypedPosition(RouteItem item) {
		try {
			double px = Double.parseDouble(item.xInput);
			double py = Double.parseDouble(item.yInput);
			double pz = Double.parseDouble(item.zInput);
			item.x = px; item.y = py; item.z = pz;
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
		} catch (NumberFormatException ignored) {}
	}

	public static int routeItemColor(RouteItem item) {
		return switch (item.type) {
			case BREAKABLE_BLOCKS -> item.blockColor;
			default -> item.waypointColor;
		};
	}

	public static void setRouteItemColor(RouteItem item, int rgb) {
		switch (item.type) {
			case BREAKABLE_BLOCKS -> item.blockColor = 0xFF000000 | rgb;
			default -> item.waypointColor = 0xFF000000 | rgb;
		}
	}

	public static String typeLabel(StepType type) {
		return switch (type) {
			case BREAKABLE_BLOCKS -> "Blocks";
			case WAYPOINT -> "Waypoint";
			case SECRET_PICKUP -> "Secret: Pickup";
			case SECRET_CHEST -> "Secret: Chest";
			case SECRET_BAT -> "Secret: Bat";
			case SECRET_ESSENCE -> "Secret: Essence";
			case SECRET_LEVER -> "Secret: Lever";
			case ENDERPEARL_WAYPOINT -> "Enderpearl";
			case ENDERWARP_WAYPOINT -> "Etherwarp";
			case SUPERBOOM_BLOCK -> "Superboom";
			case INTERACT_WAYPOINT -> "Interact";
		};
	}

	/** Per user report ("Theres a new 'Interact' trigger which i have no idea what it does") — a short
	 *  explanation for every step type, shown as a hover tooltip on the type badge in the editor (see
	 *  MainScreen's drawRouteItemBlock). */
	public static String typeDescription(StepType type) {
		return switch (type) {
			case BREAKABLE_BLOCKS -> "Blocks: a wall/obstruction to break or mine through to keep going.";
			case WAYPOINT -> "Waypoint: a plain spot to walk to, with no other action.";
			case SECRET_PICKUP -> "Secret: Pickup — an item lying on the ground to walk over and grab.";
			case SECRET_CHEST -> "Secret: Chest — a chest (locked or plain-interact) to open for the secret.";
			case SECRET_BAT -> "Secret: Bat — a secret bat to kill.";
			case SECRET_ESSENCE -> "Secret: Essence — a Wither Essence orb to pick up.";
			case SECRET_LEVER -> "Secret: Lever — a lever to pull, usually to unlock a nearby secret chest.";
			case ENDERPEARL_WAYPOINT -> "Enderpearl: throw an ender pearl from here to reach the next spot.";
			case ENDERWARP_WAYPOINT -> "Etherwarp: an Aspect of the Void teleport target block.";
			case SUPERBOOM_BLOCK -> "Superboom: a wall to blow open with a Superboom TNT.";
			case INTERACT_WAYPOINT -> "Interact: an intermediate button, plate, or lever to click along the "
				+ "way to the real secret — not the secret itself.";
		};
	}

	private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }

	private static JsonObject itemToJson(RouteItem item) {
		JsonObject e = new JsonObject();
		e.addProperty("roomName", item.roomName);
		e.addProperty("type", item.type.name());
		e.addProperty("stepNumber", item.stepNumber);
		e.addProperty("advancesStep", item.advancesStep);
		e.addProperty("blockColor", item.blockColor);
		JsonArray blocksArray = new JsonArray();
		for (BlockPos pos : item.blocks) {
			JsonObject bp = new JsonObject();
			bp.addProperty("x", pos.getX()); bp.addProperty("y", pos.getY()); bp.addProperty("z", pos.getZ());
			blocksArray.add(bp);
		}
		e.add("blocks", blocksArray);
		e.addProperty("x", item.x); e.addProperty("y", item.y); e.addProperty("z", item.z);
		e.addProperty("waypointColor", item.waypointColor);
		e.addProperty("variant", item.variant);
		JsonArray pathArray = new JsonArray();
		for (BlockPos pos : item.pathPoints) {
			JsonObject pp = new JsonObject();
			pp.addProperty("x", pos.getX()); pp.addProperty("y", pos.getY()); pp.addProperty("z", pos.getZ());
			pathArray.add(pp);
		}
		e.add("pathPoints", pathArray);
		JsonArray pearlArray = new JsonArray();
		for (PearlShot shot : item.pearlShots) {
			JsonObject ps = new JsonObject();
			ps.addProperty("x", shot.x); ps.addProperty("y", shot.y); ps.addProperty("z", shot.z);
			ps.addProperty("yaw", shot.yaw); ps.addProperty("pitch", shot.pitch);
			pearlArray.add(ps);
		}
		e.add("pearlShots", pearlArray);
		return e;
	}

	private static RouteItem itemFromJson(JsonObject e) {
		RouteItem item = new RouteItem();
		if (e.has("roomName")) item.roomName = e.get("roomName").getAsString();
		try { item.type = StepType.valueOf(e.get("type").getAsString()); } catch (Exception ignored) {}
		if (e.has("stepNumber")) item.stepNumber = e.get("stepNumber").getAsInt();
		if (e.has("advancesStep")) item.advancesStep = e.get("advancesStep").getAsBoolean();
		if (e.has("blockColor")) item.blockColor = e.get("blockColor").getAsInt();
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
		if (e.has("variant")) item.variant = e.get("variant").getAsString();
		if (e.has("pathPoints")) {
			for (JsonElement p : e.getAsJsonArray("pathPoints")) {
				JsonObject pp = p.getAsJsonObject();
				item.pathPoints.add(new BlockPos(pp.get("x").getAsInt(), pp.get("y").getAsInt(), pp.get("z").getAsInt()));
			}
		}
		if (e.has("pearlShots")) {
			for (JsonElement p : e.getAsJsonArray("pearlShots")) {
				JsonObject ps = p.getAsJsonObject();
				PearlShot shot = new PearlShot();
				shot.x = ps.get("x").getAsDouble(); shot.y = ps.get("y").getAsDouble(); shot.z = ps.get("z").getAsDouble();
				shot.yaw = ps.get("yaw").getAsFloat(); shot.pitch = ps.get("pitch").getAsFloat();
				item.pearlShots.add(shot);
			}
		}
		return item;
	}

	// ---- Export/import — per user request ("Add imports/exports to each room and an overall import/export
	// for all dungeon rooms"), same gzip -> base64 shape as Boss Guide's own standalone export/import, just
	// with two distinct prefixes so a room-scoped string and an all-rooms string can never be cross-pasted
	// into the wrong box by mistake. The all-rooms string reuses savePersistedData()/loadPersistedData()
	// directly (same shape as the whole-mod config export already covers), while the room-scoped one is a
	// bare item array scoped to just that room.
	private static final String ROUTE_ALL_EXPORT_PREFIX = "SBSROUTESALL1:";
	private static final String ROUTE_ROOM_EXPORT_PREFIX = "SBSROUTESROOM1:";
	private static final int ROUTE_MAX_IMPORT_LENGTH = 500_000;
	private static final int ROUTE_MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024;

	private static String gzipBase64(String prefix, String json) {
		try {
			java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
			try (java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(byteOut)) {
				gzipOut.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
			return prefix + java.util.Base64.getEncoder().encodeToString(byteOut.toByteArray());
		} catch (java.io.IOException e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Failed to export a Dungeon Routes string", e);
			return null;
		}
	}

	/** Returns the decompressed JSON string, or null if `raw` isn't a valid string under `prefix` (wrong
	 *  prefix, too large, or corrupted base64/gzip) — mirrors Boss Guide's own import validation. */
	private static String gunzipBase64(String prefix, String raw) {
		if (raw == null) return null;
		String trimmed = raw.strip();
		if (trimmed.length() > ROUTE_MAX_IMPORT_LENGTH || !trimmed.startsWith(prefix)) return null;
		try {
			byte[] compressed = java.util.Base64.getDecoder().decode(trimmed.substring(prefix.length()));
			java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
			try (java.util.zip.GZIPInputStream gzipIn = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
				byte[] buffer = new byte[8192];
				int total = 0, read;
				while ((read = gzipIn.read(buffer)) != -1) {
					total += read;
					if (total > ROUTE_MAX_DECOMPRESSED_BYTES) throw new java.io.IOException("Decompressed Dungeon Routes import exceeds size cap");
					byteOut.write(buffer, 0, read);
				}
			}
			return new String(byteOut.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.warn("Failed to decode an imported Dungeon Routes string", e);
			return null;
		}
	}

	public String exportAllToClipboardString() {
		return gzipBase64(ROUTE_ALL_EXPORT_PREFIX, new com.google.gson.Gson().toJson(savePersistedData()));
	}

	public boolean importAllFromClipboardString(String raw) {
		String json = gunzipBase64(ROUTE_ALL_EXPORT_PREFIX, raw);
		if (json == null) return false;
		try {
			loadPersistedData(com.google.gson.JsonParser.parseString(json));
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			return true;
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.warn("Failed to apply an imported Dungeon Routes (all rooms) string", e);
			return false;
		}
	}

	public String exportRoomToClipboardString(String roomName) {
		JsonArray array = new JsonArray();
		for (RouteItem item : itemsForRoom(roomName)) array.add(itemToJson(item));
		return gzipBase64(ROUTE_ROOM_EXPORT_PREFIX, new com.google.gson.Gson().toJson(array));
	}

	/** Replaces just `roomName`'s own steps with the imported set — every other room's steps are untouched.
	 *  The imported items' own stored room name is overwritten to `roomName` regardless of what it was
	 *  exported as, so pasting one room's export into a different room's box re-targets it there instead of
	 *  silently doing nothing (the target room is whichever editor box the string was pasted into). */
	public boolean importRoomFromClipboardString(String roomName, String raw) {
		String json = gunzipBase64(ROUTE_ROOM_EXPORT_PREFIX, raw);
		if (json == null) return false;
		try {
			JsonArray array = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
			List<RouteItem> imported = new ArrayList<>();
			for (JsonElement el : array) {
				RouteItem item = itemFromJson(el.getAsJsonObject());
				item.roomName = roomName;
				imported.add(item);
			}
			items.removeIf(item -> item.roomName.equals(roomName));
			items.addAll(imported);
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			return true;
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.warn("Failed to apply an imported Dungeon Routes room string", e);
			return false;
		}
	}

	/** Bulk replace, one room at a time — used by {@link com.cokelord.skyblocksimplified.dungeon.
	 *  SecretRoutesImporter} (see its own doc comment for the full import). Only rooms actually present in
	 *  {@code byRoom} are touched; every other room's existing steps (manually authored or from an earlier
	 *  import) are left completely alone. */
	public void importRouteItems(Map<String, List<RouteItem>> byRoom) {
		for (Map.Entry<String, List<RouteItem>> entry : byRoom.entrySet()) {
			items.removeIf(i -> i.roomName.equals(entry.getKey()));
			items.addAll(entry.getValue());
		}
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("waypointTracer", waypointTracer);
		obj.addProperty("waypointBeaconLine", waypointBeaconLine);
		obj.addProperty("highlightOpacityPercent", highlightOpacityPercent);
		obj.addProperty("editMode", editMode);
		JsonArray array = new JsonArray();
		for (RouteItem item : items) array.add(itemToJson(item));
		obj.add("items", array);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("waypointTracer")) waypointTracer = obj.get("waypointTracer").getAsBoolean();
		if (obj.has("waypointBeaconLine")) waypointBeaconLine = obj.get("waypointBeaconLine").getAsBoolean();
		if (obj.has("highlightOpacityPercent")) highlightOpacityPercent = obj.get("highlightOpacityPercent").getAsInt();
		if (obj.has("editMode")) editMode = obj.get("editMode").getAsBoolean();
		items.clear();
		if (obj.has("items")) {
			for (JsonElement el : obj.getAsJsonArray("items")) items.add(itemFromJson(el.getAsJsonObject()));
		}
		// Real bug found (see removeItem's own doc comment): a previous session's deletes could already have
		// left permanent stepNumber gaps baked into this exact saved config, which would keep skipping steps
		// forever even after removeItem itself got fixed — this self-heals any already-corrupted route the
		// instant it loads, not just ones edited from now on.
		closeAllStepNumberGaps();
	}

	/** Renumbers every room/variant's own items to a gapless 1..N stepNumber sequence, independently per
	 *  room+variant — see removeItem's and loadPersistedData's own doc comments for the real bug this closes. */
	private void closeAllStepNumberGaps() {
		Map<String, List<RouteItem>> byRoomVariant = new java.util.LinkedHashMap<>();
		for (RouteItem it : items) byRoomVariant.computeIfAbsent(it.roomName + " " + it.variant, k -> new ArrayList<>()).add(it);
		for (List<RouteItem> group : byRoomVariant.values()) {
			group.sort((a, b) -> Integer.compare(a.stepNumber, b.stepNumber));
			int newStep = 0;
			int lastOldStep = Integer.MIN_VALUE;
			for (RouteItem it : group) {
				if (it.stepNumber != lastOldStep) {
					newStep++;
					lastOldStep = it.stepNumber;
				}
				it.stepNumber = newStep;
			}
		}
	}

	@Override
	public String getDescription() {
		return "An ordered, room-by-room walkthrough guide (waypoints, secrets, superboom spots) for a dungeon room you've mapped out.";
	}
}
