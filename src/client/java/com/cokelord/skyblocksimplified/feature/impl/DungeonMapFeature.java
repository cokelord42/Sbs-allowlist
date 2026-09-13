package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.BossMapEntry;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonJsonAssets;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonScan;
import com.cokelord.skyblocksimplified.dungeon.map.IVec2;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonDoor;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DoorType;
import com.cokelord.skyblocksimplified.dungeon.map.tile.MapCheckmark;
import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomShape;
import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomType;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import net.minecraft.world.level.material.MapColor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
/**
 * Draggable, resizable dungeon map GUI element showing the live room/door layout the mod already scans
 * ({@link DungeonScan}/{@link com.cokelord.skyblocksimplified.dungeon.map.MapScan}) — a from-scratch,
 * flat 2D redraw of that data rather than a texture blit of the vanilla map item, so every visual (room
 * fill, door color, checkmark/secret/name labels, teammate markers) is fully mod-drawn and themeable.
 * Door and room-fill colors are decoded straight from the real Minecraft map-color bytes {@link RoomType}
 * carries, so those match what the vanilla map item itself would actually show. Per user request/
 * clarification ("When i said dungeon rooms i meant the text not rooms themselves" — a follow-up on an
 * earlier request that had been read too broadly and briefly also recolored the room tile fill itself),
 * only the room's TEXT — its name/secrets-count/checkmark label — follows the real three-way clear-progress
 * state (gray/white/green, decoded from {@link MapCheckmark} — see {@link #clearStateColor}); the room tile
 * fill itself stays on the room's real TYPE color like before.
 * Cross-checked against devonian's own {@code DungeonMapBaseRenderer.kt} for the overall approach (square
 * HUD element, per-tile room fill with edge-only gaps so multi-tile rooms read as one contiguous blob,
 * frozen room/door state + no live markers while in the boss fight) — re-derived in this codebase's own
 * screen-space drawing style, not copied.
 */
public class DungeonMapFeature extends Feature implements MoveableWidget {
	public enum ShowNamesMode { OFF, HOLDING_LEAP, ALWAYS }
	public enum RoomLabelMode { SECRETS, NAME, CHECKMARK }

	private static final int BASE_SIZE = 132;
	private static final int GRID = 6;

	/** Real per-floor boss-room reference images + calibration, ported from BetterMap's own
	 *  {@code imageData.json}/{@code BossMapRenderer.js} (see {@code bossMaps.json} and {@link BossMapEntry}).
	 *  BetterMap itself is a ChatTriggers/JS 1.8.9 mod; this is a from-scratch re-derivation of its real
	 *  pan/clamp viewport math in this codebase's own screen-space drawing style, not copied code. */
	private static final java.util.Map<String, java.util.List<BossMapEntry>> BOSS_MAPS = DungeonJsonAssets.load(
		"/assets/skyblocksimplified/dungeon/puzzles/bossMaps.json",
		new com.google.gson.reflect.TypeToken<java.util.Map<String, java.util.List<BossMapEntry>>>() {}.getType(),
		new java.util.HashMap<>());

	// Real bug found (per user report — "it should rescan based on current coordinates and show those,
	// because it needs to show the actual bossfight and not the original dungeon" — see isVisible()'s own doc
	// comment on why the frozen floor grid alone can't show this): the boss arena is one large room entirely
	// outside the floor's own 6x6 tile grid this map is otherwise built around, so nothing about that grid can
	// ever represent it. currentBossMap holds whichever of BOSS_MAPS' calibrated images (if any) the player's
	// live position currently falls inside, refreshed at most every 2s (BOSS_MAP_UPDATE_INTERVAL_MS) — same
	// throttle BetterMap's own updateBossImage uses, since this only needs to change when someone actually
	// crosses into/out of a sub-stage's bounds, not every frame.
	private static final long BOSS_MAP_UPDATE_INTERVAL_MS = 2000L;
	private BossMapEntry currentBossMap;
	private long lastBossMapUpdate = 0L;

	/** One F7/M7 boss-fight phase's real fixed camera framing — per user-supplied real coordinates (measured
	 *  in-game, not guessed): where the camera should center (x/z; y is only used to help disambiguate which
	 *  phase is nearest, same as the player's own real height already does for {@link #currentBossMap}
	 *  selection), how many degrees to rotate the boss-map image from its base (unrotated) orientation, and
	 *  how far to zoom out (1.0 = no zoom change). F7 and M7 share the exact same real physical boss-room
	 *  coordinates (Master Mode doesn't move the arena, just the mob stats), so one list covers both — matches
	 *  this feature's own BOSS_MAPS floor key "7" already covering both. */
	private record BossPhase(String name, double x, double y, double z, int rotationDegrees, float zoomOut) {}

	private static final java.util.List<BossPhase> FLOOR7_PHASES = java.util.List.of(
		new BossPhase("Maxor", 73, 221, 40, 180, 1f),
		new BossPhase("Storm", 73, 164, 53, 90, 1f),
		new BossPhase("Terminals S1", 100, 123, 86, 180, 0.9f),
		new BossPhase("Terminals S2", 53, 120, 131, 90, 0.9f),
		new BossPhase("Terminals S3", 6, 117, 85, 0, 0.9f),
		new BossPhase("Terminals S4", 55, 121, 41, 270, 0.9f),
		new BossPhase("Goldor", 54, 114, 63, 0, 1f),
		new BossPhase("Necron", 44, 65, 66, 0, 1f),
		new BossPhase("Dragons/Wither King", 60, 6, 73, 0, 1f)
	);

	private BossPhase currentPhase;

	private boolean outlineEnabled = false;
	private int outlineColor = 0xFF000000;
	private int outlineThickness = 2;

	private boolean backgroundEnabled = true;
	private float backgroundOpacity = 0.55f;
	private int backgroundColor = 0x101010;

	private boolean playerHeads = false;
	// Per explicit user request/informed choice, defaults OFF: showing a room's real type/shape before it's
	// actually been explored (as opposed to the small "?" preview vanilla itself already reveals for a room
	// directly adjacent to one you've opened) goes beyond what Hypixel's own client shows you. The user was
	// told the honest risk assessment (not detectable by Watchdog specifically, but the same "unfair
	// advantage third-party mod" category X-ray falls under regardless of detectability) and asked for this
	// as an explicit opt-in toggle rather than the previous round's always-on behavior.
	private boolean revealUnexploredRooms = false;
	private ShowNamesMode showNamesMode = ShowNamesMode.HOLDING_LEAP;
	private RoomLabelMode roomLabelMode = RoomLabelMode.SECRETS;

	// Per user request ("New subtoggle: Map info... turning it on shows more subtoggles based on what the
	// user wants to display"): a small text block drawn above the map itself, each line independently
	// toggleable. Crypts/Deaths/Mimic all read from DungeonState's own already-tablist-tracked live values —
	// no new detection needed, this is purely a display surface for data this codebase already has.
	private boolean mapInfoEnabled = false;
	private boolean mapInfoShowCrypts = true;
	private boolean mapInfoShowDeaths = true;
	private boolean mapInfoShowMimic = true;
	// Per user request ("Allow users to change the color of all the text that isn't color coded"): only the
	// crypts VALUE has real color-coding (see cryptsColor); every label and every other value here — the
	// "Crypts"/"Deaths"/"Mimic" words, the deaths count, and the mimic checkmark/X — uses this one color.
	private int mapInfoTextColor = 0xFFFFFFFF;

	private final HudPosition defaultPosition = new HudPosition(0.85f, 0.3f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static DungeonMapFeature instance;
	// HudElementRegistry.addLast throws IllegalArgumentException on a duplicate id (confirmed via
	// decompiling Fabric's own HudElementRegistryImpl) — onEnable() used to call it unconditionally, so
	// toggling this feature off then back on (a completely normal user action) crashed the render pipeline
	// the moment it re-enabled. Same guarded-registration pattern already used correctly elsewhere in this
	// codebase (see PlayerDisplayFeature/ChatCopyFeature): register exactly once, ever, and let the render
	// callback's own isVisible() check make it a no-op while disabled.
	private static boolean listenersRegistered = false;

	public DungeonMapFeature() {
		super("dungeon_map", "Dungeon Map", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	public static DungeonMapFeature getInstance() { return instance; }

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeon_map"),
			(graphics, deltaTracker) -> {
				if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.render(graphics, x, y, instance.position.scale);
			});
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() {
		// Round 2 (per user request — "it should rescan based on current coordinates and show those, because
		// it needs to show the actual bossfight and not the original dungeon"): no longer hides outright
		// during the boss fight. The real boss arena's own SHAPE genuinely can't be rendered here (it's one
		// large room entirely outside the floor's own 6x6 room grid DungeonScan's tile math is built around —
		// see render()'s own doc comment on the off-grid marker fallback this now reuses for that exact
		// reason), so the room/door grid still shows the frozen pre-boss layout underneath, same as before —
		// but the player MARKERS on top of it are live again, computed from real current coordinates every
		// frame instead of being frozen/hidden, so at least where your team actually is right now is honest,
		// live information instead of a stale or blank map.
		if (!isEnabled()) return false;
		if (!DungeonState.isInDungeon()) return false;
		// Real bug found (per user report — "the map is rendering above the terminal solver"): this HUD
		// widget has no concept of what's currently open, so it kept drawing over the vanilla terminal
		// container screen (and Terminal Solver's own overlay/custom-GUI render on top of it) whenever a
		// terminal was open, clashing with a GUI that already fills most of the screen. Hidden specifically
		// while a real terminal is actively being solved (TerminalTracker.getCurrentType() != null while a
		// container screen is open), not for every screen in general — the map should stay visible for e.g.
		// Leap Menu's own "Sync with Map" mode, which relies on this feature's rendering while its screen is
		// open too.
		if (Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
				&& com.cokelord.skyblocksimplified.dungeon.TerminalTracker.getCurrentType() != null) return false;
		return true;
	}

	@Override
	public boolean isRelevantToCurrentIsland() {
		return DungeonState.isInDungeon();
	}

	// Round 2 (per user request — "remove the extra map that pops up in the leap menu, let players click
	// heads on the real/original map"): Leap Menu used to draw a whole SECOND copy of the map at its own
	// hardcoded corner position specifically so it had something to hit-test clicks against. This widget's
	// own normal per-frame render now populates the exact same kind of hit list every
	// frame it draws — including while a Leap Menu screen happens to be open, since this widget already kept
	// rendering through that screen before this round (see the old isVisible() doc comment this replaced) —
	// so Leap Menu can now just read THIS list and hit-test against the real, already-visible widget instead
	// of needing its own duplicate copy at all.
	private final java.util.List<MarkerHit> lastMarkerHits = new java.util.ArrayList<>();

	public java.util.List<MarkerHit> getLastMarkerHits() { return lastMarkerHits; }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		int size = Math.round(BASE_SIZE * scale);
		// Per user request ("Change the map info to sit under the map horizontally/set them next to
		// eachother"): map info used to draw ABOVE the map (pushing it down by infoHeight) — now the map draws
		// first at the widget's own top, and the info row draws below it instead.
		lastMarkerHits.clear();
		updateCurrentBossMap();
		if (currentBossMap != null) {
			// The real boss arena's actual shape, drawn from BetterMap's pre-baked reference image instead of
			// the (necessarily empty, off-grid) floor room/door layout — see currentBossMap's own doc comment.
			drawBossMap(graphics, x, y, size, currentBossMap, currentPhase, playerHeads, resolveShowNames(),
				backgroundEnabled, backgroundOpacity, backgroundColor,
				outlineEnabled, outlineColor, outlineThickness, lastMarkerHits);
		} else {
			// drawPlayerMarkers is unconditionally true now (previously gated off during the boss fight) — see
			// isVisible()'s own doc comment for why live markers stay meaningful even then. renderOffMapMarkers
			// below is the fallback for whoever's live coordinates don't land inside the floor's own 6x6 grid
			// at all AND aren't inside any calibrated boss-map bounds either (e.g. mid-transition into the
			// arena, or a floor/sub-stage this data doesn't cover).
			drawMap(graphics, x, y, size, roomLabelMode, playerHeads, resolveShowNames(),
				backgroundEnabled, backgroundOpacity, backgroundColor,
				outlineEnabled, outlineColor, outlineThickness, true, lastMarkerHits, revealUnexploredRooms);
			renderOffMapMarkers(graphics, x, y, size, playerHeads, lastMarkerHits);
		}
		// Per user request ("Make the map info go down 2 pixels"): a small downward nudge so the info row
		// doesn't sit flush against the map's own bottom edge — folded into the returned height too so the
		// widget's own edit-screen hitbox still fully contains it.
		int infoHeight = mapInfoEnabled ? drawMapInfo(graphics, x, y + size + 2, scale) + 2 : 0;
		return new Size(size, size + infoHeight);
	}

	// Per user request ("New subtoggle: Map info... turning it on shows more subtoggles based on what the
	// user wants to display. It should have current crypts... Deaths in the dungeon... Mimic dead or
	// alive... Allow users to change the color of all the text that isn't color coded"): a small left-
	// aligned text block drawn above the map's own top edge, one line per enabled item. Returns the total
	// pixel height consumed (0 if every line is disabled) so render() can push the map itself down and grow
	// the widget's own edit-screen hitbox to match.
	// Per user request ("Change the map info to sit under the map horizontally/set them next to eachother"):
	// pixel gap between each segment (Crypts/Deaths/Mimic) when laid out side by side on the one row.
	private static final int MAP_INFO_SEGMENT_GAP = 10;

	private int drawMapInfo(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		var font = Minecraft.getInstance().font;
		int lineHeight = Math.round((font.lineHeight + 1) * scale);
		int curX = x;
		boolean any = false;
		if (mapInfoShowCrypts) {
			int crypts = DungeonState.getCrypts();
			curX = drawMapInfoSegment(graphics, font, curX, y, scale, "Crypts: ", String.valueOf(crypts), cryptsColor(crypts));
			any = true;
		}
		if (mapInfoShowDeaths) {
			curX = drawMapInfoSegment(graphics, font, curX, y, scale, "Deaths: ", String.valueOf(DungeonState.getDeaths()), mapInfoTextColor);
			any = true;
		}
		if (mapInfoShowMimic) {
			// Per user request ("Make the mimic cross red and checkmark green"): used to render with the same
			// plain mapInfoTextColor as every other uncolored value here — now a real, dedicated color pair
			// like Crypts' own clear-progress coloring, instead of relying on glyph shape alone to read status.
			boolean killed = DungeonState.isMimicKilled();
			drawMapInfoSegment(graphics, font, curX, y, scale, "Mimic: ", killed ? "✔" : "✘", killed ? COLOR_CLEAR_STATE_GREEN : 0xFFFF5555);
			any = true;
		}
		return any ? lineHeight : 0;
	}

	// Per user's exact spec: "0-2 is red, 3 and 4 are yellow, 5 and above green".
	private static int cryptsColor(int crypts) {
		if (crypts >= 5) return 0xFF55FF55;
		if (crypts >= 3) return 0xFFFFFF55;
		return 0xFFFF5555;
	}

	/** Draws one "Label: value" segment at {@code x} and returns the x coordinate the NEXT segment should
	 *  start at (this segment's own scaled width plus {@link #MAP_INFO_SEGMENT_GAP}) — the side-by-side
	 *  replacement for the old one-segment-per-row {@code drawMapInfoLine}. */
	private int drawMapInfoSegment(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, int x, int y, float scale, String label, String value, int valueColor) {
		int labelWidth = font.width(label);
		int valueWidth = font.width(value);
		if (Math.abs(scale - 1f) < 0.01f) {
			graphics.text(font, label, x, y, mapInfoTextColor);
			graphics.text(font, value, x + labelWidth, y, valueColor);
			return x + labelWidth + valueWidth + MAP_INFO_SEGMENT_GAP;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale);
		graphics.pose().translate(-x, -y);
		graphics.text(font, label, x, y, mapInfoTextColor);
		graphics.text(font, value, x + labelWidth, y, valueColor);
		graphics.pose().popMatrix();
		return x + Math.round((labelWidth + valueWidth) * scale) + Math.round(MAP_INFO_SEGMENT_GAP * scale);
	}

	/** Refreshes {@link #currentBossMap} from the player's live position, throttled to
	 *  {@link #BOSS_MAP_UPDATE_INTERVAL_MS} like BetterMap's own {@code updateBossImage} — this only needs to
	 *  change when someone actually crosses a sub-stage boundary, not every frame. */
	private void updateCurrentBossMap() {
		long now = System.currentTimeMillis();
		if (now - lastBossMapUpdate < BOSS_MAP_UPDATE_INTERVAL_MS) return;
		lastBossMapUpdate = now;
		currentBossMap = null;
		currentPhase = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
		java.util.List<BossMapEntry> candidates = BOSS_MAPS.get(Integer.toString(DungeonState.getFloorNumber()));
		if (candidates == null) return;
		for (BossMapEntry entry : candidates) {
			if (entry.containsWorldPos(px, py, pz)) {
				currentBossMap = entry;
				break;
			}
		}
		if (currentBossMap == null) return;

		// Per user request ("Dungeon Map: boss room phase-based highlight/center instead of centering the
		// player... it should trigger in phases"): only F7/M7 (BOSS_MAPS' own floor key "7", which already
		// covers both — Master Mode doesn't move the real boss room) get a fixed-camera phase; every other
		// floor's boss arena keeps centering on the player's own live position exactly as before. Whichever
		// real phase coordinate is nearest the player's OWN live position (3D distance, so the real ~150-block
		// height difference between e.g. Maxor and Necron dominates and naturally separates phases that share
		// similar x/z) is picked — the height axis doing double duty here is intentional, not a conflict with
		// containsWorldPos above: that call decides which reference IMAGE is showing (needs to stay exactly
		// as accurate as before so a player who's, say, pre-Necron on a platform above the arena still sees
		// the right image — see this method's own "predeviceing" doc note on the caller), while this only
		// decides which preset camera framing to use once an image is already showing.
		java.util.List<BossMapEntry> floor7 = BOSS_MAPS.get("7");
		if (floor7 != null && floor7.contains(currentBossMap)) {
			BossPhase nearest = null;
			double bestDistSq = Double.POSITIVE_INFINITY;
			for (BossPhase phase : FLOOR7_PHASES) {
				double dx = px - phase.x(), dy = py - phase.y(), dz = pz - phase.z();
				double distSq = dx * dx + dy * dy + dz * dz;
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					nearest = phase;
				}
			}
			currentPhase = nearest;
		}
	}

	/** One teammate marker's on-screen hit box from the most recent {@link #render} call — Leap Menu owns
	 *  real click dispatch (via its existing {@code ScreenMouseEvents}/click-handling), so rather than this
	 *  class reaching into another feature's input
	 *  handling, it just hands back where each marker landed and lets the caller do its own hit-testing
	 *  against these. */
	public record MarkerHit(int x, int y, int width, int height, DungeonPlayer player) {
		public boolean contains(double mx, double my) {
			return mx >= x && mx <= x + width && my >= y && my <= y + height;
		}
	}

	/** Per user report ("The map also STILL doesnt show the bossfight"): the real boss room is one large
	 *  arena entirely outside the normal floor's 6x6 room grid this map's tile math is built around (see
	 *  the "boss fight is visually active" comment in drawMap for why the standalone HUD widget deliberately
	 *  shows an honest blank frame there instead of a stale floor layout) — a teammate physically inside it
	 *  has no valid position on this grid at all, so drawMap's own marker loop silently skips them (their
	 *  computed tile position falls outside [0, GRID]). That "honest blank" is fine for the passive HUD
	 *  widget, but it means Leap Menu's whole point — clicking a teammate to leap/haunt them — stops working
	 *  the instant anyone's in the boss room, exactly when a dead player trying to haunt someone is most
	 *  likely to need it. Teammates the main pass couldn't place (their entity wasn't skipped for any other
	 *  reason, they're just off this grid) get a simple clickable strip along the bottom edge instead — not
	 *  a real position, just enough to keep click-to-leap working everywhere, including mid-boss-fight. */
	private static void renderOffMapMarkers(GuiGraphicsExtractor graphics, int x, int y, int size, boolean heads, java.util.List<MarkerHit> outHits) {
		java.util.List<DungeonPlayer> placed = new java.util.ArrayList<>();
		for (MarkerHit hit : outHits) placed.add(hit.player());
		java.util.List<DungeonPlayer> missing = new java.util.ArrayList<>();
		for (DungeonPlayer player : DungeonState.getTeammates()) {
			if (player.entity instanceof AbstractClientPlayer && !placed.contains(player)) missing.add(player);
		}
		if (missing.isEmpty()) return;

		int markerSize = Math.max(14, Math.round(size * 0.09f));
		int spacing = markerSize + 6;
		int totalWidth = missing.size() * spacing - 6;
		int startX = x + size / 2 - totalWidth / 2;
		float markerY = y + size + 10 + markerSize / 2f;
		for (int i = 0; i < missing.size(); i++) {
			DungeonPlayer player = missing.get(i);
			AbstractClientPlayer acp = (AbstractClientPlayer) player.entity;
			float px = startX + i * spacing + markerSize / 2f;
			drawPlayerMarker(graphics, acp.getSkin(), px, markerY, markerSize, heads, player.getRenderYaw());
			int hit = markerSize + 4;
			outHits.add(new MarkerHit(Math.round(px - hit / 2f), Math.round(markerY - hit / 2f), hit, hit, player));
		}
	}

	/** Draws the real boss arena using BetterMap's own pan/clamp "camera window" technique: the whole
	 *  pre-baked reference image is drawn scaled to real-world size, shifted so the player's live position sits
	 *  centered under the widget (clamped so the viewport never scrolls past the image's own edges), then
	 *  clipped to the widget's square via scissor. Re-derived from {@code BossMapRenderer.js}'s {@code draw()}
	 *  (BetterMap, a ChatTriggers/JS 1.8.9 mod) in this codebase's own screen-space style — same formulas,
	 *  not copied code; this widget has no separate "border" concept BetterMap's own renderContext.borderWidth
	 *  adds, so that term is simply omitted (equivalent to border=0). */
	private static void drawBossMap(GuiGraphicsExtractor graphics, int x, int y, int size, BossMapEntry entry, BossPhase phase,
									 boolean heads, boolean showNames, boolean bgEnabled, float bgOpacity, int bgColor,
									 boolean outline, int outlineColor, int outlineThickness,
									 java.util.List<MarkerHit> outHits) {
		if (size <= 0) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		if (bgEnabled) {
			int alpha = Math.round(Math.max(0f, Math.min(1f, bgOpacity)) * 255f);
			graphics.fill(x, y, x + size, y + size, (alpha << 24) | (bgColor & 0xFFFFFF));
		}

		double renderWidth = entry.renderSize != null ? entry.renderSize : entry.widthInWorld;
		double renderHeight = entry.renderSize != null ? entry.renderSize : entry.heightInWorld;
		double sizeInWorld = Math.min(entry.widthInWorld, Math.min(entry.heightInWorld,
			entry.renderSize != null ? entry.renderSize : Double.POSITIVE_INFINITY));
		double pixelWidth = entry.imageWidth / entry.widthInWorld * renderWidth;
		double pixelHeight = entry.imageHeight / entry.heightInWorld * renderHeight;
		double sizeInPixels = Math.min(pixelWidth, pixelHeight);
		double textureScale = size / sizeInPixels;

		double drawnWidth = entry.imageWidth * textureScale;
		double drawnHeight = entry.imageHeight * textureScale;

		// Per user request ("Dungeon Map: boss room phase-based highlight/center instead of centering the
		// player"): F7/M7 (see updateCurrentBossMap's own doc comment) now centers on the CURRENT real
		// phase's own fixed, user-measured coordinate instead of the player's own live position — every
		// other floor's boss arena keeps the original player-centered behavior (phase == null there).
		//
		// Per user request ("The map should center the player in the boss"): this used to clamp the view
		// offset to the texture's own bounds so the viewport never scrolled past the room image's edge —
		// correct to avoid showing blank space near a wall, but it also meant the player was only EVER
		// truly centered while standing in roughly the middle portion of the arena; anywhere near an edge
		// (a large fraction of most real boss arenas) pinned the view to that edge instead, never actually
		// tracking the player. Unclamped now — the camera stays exactly centered always, even if that
		// means the edge of the room image is reached before the edge of the viewport (texture sampling
		// past the image's own bounds just repeats/clamps the edge pixel row, not a crash or glitch).
		double cameraX = phase != null ? phase.x() : mc.player.getX();
		double cameraZ = phase != null ? phase.z() : mc.player.getZ();
		double offsetX = (cameraX - entry.topLeftX) / sizeInWorld * size - size / 2.0;
		double offsetZ = (cameraZ - entry.topLeftZ) / sizeInWorld * size - size / 2.0;

		Identifier texture = Identifier.fromNamespaceAndPath("skyblocksimplified", "textures/dungeon/bossmaps/" + entry.image + ".png");

		float zoomOut = phase != null ? phase.zoomOut() : 1f;
		float rotationRadians = phase != null ? (float) Math.toRadians(phase.rotationDegrees()) : 0f;
		float centerX = x + size / 2f, centerY = y + size / 2f;

		graphics.enableScissor(x, y, x + size, y + size);
		// Real per-phase rotation/zoom, applied around the widget's own center so the camera framing changes
		// without moving the widget itself — same pushMatrix/translate/rotate pattern this class's own
		// drawRotatedHead/drawCenteredScaledText already use. Local (0,0) after this transform is the
		// widget's own top-left corner, same as the unrotated x/y math below used before phases existed.
		graphics.pose().pushMatrix();
		try {
			graphics.pose().translate(centerX, centerY);
			if (rotationRadians != 0f) graphics.pose().rotate(rotationRadians);
			if (zoomOut != 1f) graphics.pose().scale(zoomOut);
			graphics.pose().translate(-size / 2f, -size / 2f);

			graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture,
				(int) Math.round(-offsetX), (int) Math.round(-offsetZ), 0f, 0f,
				(int) Math.round(drawnWidth), (int) Math.round(drawnHeight),
				entry.imageWidth, entry.imageHeight, entry.imageWidth, entry.imageHeight, 0xFFFFFFFF);

			// Per user request ("Make the player heads on the map smaller"): reduced from 0.5x/6px min to
			// 0.35x/5px min cell size.
			float markerSize = Math.max(5f, (size / (float) GRID) * 0.35f);
			for (DungeonPlayer player : DungeonState.getTeammates()) {
				Player pEntity = player.entity;
				if (!(pEntity instanceof AbstractClientPlayer acp)) continue;
				float px = (float) ((pEntity.getX() - entry.topLeftX) / sizeInWorld * size - offsetX);
				float py = (float) ((pEntity.getZ() - entry.topLeftZ) / sizeInWorld * size - offsetZ);
				drawPlayerMarker(graphics, acp.getSkin(), px, py, Math.round(markerSize), heads, player.getRenderYaw());
				if (showNames) {
					String name = player.name;
					int nameWidth = mc.font.width(name);
					graphics.text(mc.font, name, Math.round(px - nameWidth / 2f), Math.round(py - markerSize / 2f - 9), 0xFFFFFFFF);
				}
				if (outHits != null) {
					// Hit-testing happens in real screen space against real mouse coordinates, so a rotated/
					// zoomed marker's hit box needs the SAME transform applied to its center point manually —
					// pose-stack transforms only affect drawing, never MarkerHit's own plain int rectangle.
					float worldDx = px - size / 2f, worldDy = py - size / 2f;
					float cos = (float) Math.cos(rotationRadians), sin = (float) Math.sin(rotationRadians);
					float screenPx = centerX + (worldDx * cos - worldDy * sin) * zoomOut;
					float screenPy = centerY + (worldDx * sin + worldDy * cos) * zoomOut;
					int hit = Math.round(markerSize * zoomOut) + 4;
					outHits.add(new MarkerHit(Math.round(screenPx - hit / 2f), Math.round(screenPy - hit / 2f), hit, hit, player));
				}
			}
		} finally {
			graphics.pose().popMatrix();
		}

		if (outline && outlineThickness > 0) {
			int t = outlineThickness;
			graphics.fill(x, y, x + size, y + t, outlineColor);
			graphics.fill(x, y + size - t, x + size, y + size, outlineColor);
			graphics.fill(x, y, x + t, y + size, outlineColor);
			graphics.fill(x + size - t, y, x + size, y + size, outlineColor);
		}

		graphics.disableScissor();
	}

	private boolean resolveShowNames() {
		return switch (showNamesMode) {
			case ALWAYS -> true;
			case OFF -> false;
			case HOLDING_LEAP -> isHoldingSpiritLeap();
		};
	}

	private static boolean isHoldingSpiritLeap() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		return "SPIRIT_LEAP".equals(SkyblockNbtUtils.getItemId(mc.player.getMainHandItem()))
			|| "SPIRIT_LEAP".equals(SkyblockNbtUtils.getItemId(mc.player.getOffhandItem()));
	}

	private static void drawMap(GuiGraphicsExtractor graphics, int x, int y, int size, RoomLabelMode labelMode,
								 boolean heads, boolean showNames, boolean bgEnabled, float bgOpacity, int bgColor,
								 boolean outline, int outlineColor, int outlineThickness, boolean drawPlayerMarkers,
								 java.util.List<MarkerHit> outHits, boolean revealUnexploredRooms) {
		if (size <= 0) return;
		float cell = size / (float) GRID;
		float gap = Math.max(1f, cell * 0.06f);

		if (bgEnabled) {
			int alpha = Math.round(Math.max(0f, Math.min(1f, bgOpacity)) * 255f);
			graphics.fill(x, y, x + size, y + size, (alpha << 24) | (bgColor & 0xFFFFFF));
		}

		// Per user request ("Dungeon Map in the boss fight needs retrying... it should preserve
		// explored-room/player-position state on boss entry" instead of either reloading or blanking): this
		// used to skip the whole room/door/label grid outright the instant boss fight became visually active
		// (drawPlayerMarkers doubling as that signal), on the theory that DungeonScan's room data was reset
		// to nothing the moment boss entry fired and therefore had nothing honest left to show. Per
		// WorldScan#tick's own updated doc comment, that reset itself was the actual bug — DungeonScan.rooms/
		// doors are no longer wiped on boss entry, so they now stay frozen at exactly the last state explored
		// before entering, which is genuinely accurate (if stale) data worth showing rather than hiding. Only
		// the live PLAYER MARKERS below are still skipped in boss (drawPlayerMarkers stays gated on
		// !isBossFightVisuallyActive() at the call site) — the real F7/M7 boss room is one large arena outside
		// this 6x6 floor grid entirely, so a "live" marker position computed against that grid would be
		// meaningless there, unlike the frozen room layout itself.

		// Per user request ("doors should render UNDER the brown rooms, looks kind of dumb otherwise"): doors
		// drawn first so each room's own fill paints over the small overlap at its edge, instead of the door
		// bar poking out on top of the room color.
		// Real bug found (per user report — "The doors are not slimmer. I didnt mean like that. I meant
		// that they should be 'shorter' i guess. The other dimension"): the previous round shrank
		// doorThickness, the dimension PERPENDICULAR to the wall (how far the bar pokes into each
		// neighboring room) — reverted back to its original 0.3x. What the user actually meant was the
		// LENGTH along the wall itself (the span this door bar covers where it crosses the gap between
		// rooms), controlled by the 0.2f/0.8f fractions below — narrowed from a 0.6-cell span to a 0.4-cell
		// span (0.3f/0.7f) for a visibly shorter bar in that other dimension.
		float doorThickness = Math.max(2f, cell * 0.3f);
		float doorLengthStart = 0.3f, doorLengthEnd = 0.7f;
		for (DungeonDoor door : DungeonScan.doors.values()) {
			// Per user request ("doors that lead to rooms that are unopened/unexplored should be darker
			// than the doors that lead between explored rooms"): reuses dimColor, the same halved-brightness
			// treatment knownButUnexplored rooms already get.
			int doorColor = doorLeadsToUnexplored(door) ? dimColor(doorColor(door.type)) : doorColor(door.type);
			IVec2 p = door.position;
			if (door.rotation == com.cokelord.skyblocksimplified.dungeon.map.tile.DoorRotation.HORIZONTAL) {
				float cx = x + (p.x() + 1) * cell;
				float cy0 = y + p.z() * cell + cell * doorLengthStart;
				float cy1 = y + p.z() * cell + cell * doorLengthEnd;
				graphics.fill(Math.round(cx - doorThickness / 2f), Math.round(cy0), Math.round(cx + doorThickness / 2f), Math.round(cy1), doorColor);
			} else {
				float cy = y + (p.z() + 1) * cell;
				float cx0 = x + p.x() * cell + cell * doorLengthStart;
				float cx1 = x + p.x() * cell + cell * doorLengthEnd;
				graphics.fill(Math.round(cx0), Math.round(cy - doorThickness / 2f), Math.round(cx1), Math.round(cy + doorThickness / 2f), doorColor);
			}
		}

		// revealUnexploredRooms (off by default — see the field's own doc comment on DungeonMapFeature for
		// the risk/detectability discussion this toggle came out of): ON shows a room's real type/shape from
		// the map's own corner-pixel color the instant it's in the vanilla map's reveal radius, well before
		// it's actually been explored. OFF (the vanilla-accurate behavior) only shows a small "?" placeholder
		// at grid positions directly adjacent to an already-explored room — the same small unrevealed-room
		// hint Hypixel's own client already gives you, nothing more.
		for (DungeonRoom room : DungeonScan.rooms) {
			boolean viewable = room.isViewable();
			boolean knownButUnexplored = revealUnexploredRooms && !viewable && room.type != null;
			if (!viewable && !knownButUnexplored) continue;
			int fillColor = knownButUnexplored ? dimColor(roomColor(room)) : roomColor(room);
			for (IVec2 tile : room.tiles) {
				boolean left = room.tiles.contains(new IVec2(tile.x() - 1, tile.z()));
				boolean right = room.tiles.contains(new IVec2(tile.x() + 1, tile.z()));
				boolean up = room.tiles.contains(new IVec2(tile.x(), tile.z() - 1));
				boolean down = room.tiles.contains(new IVec2(tile.x(), tile.z() + 1));
				float x0 = x + tile.x() * cell + (left ? 0f : gap);
				float x1 = x + (tile.x() + 1) * cell - (right ? 0f : gap);
				float y0 = y + tile.z() * cell + (up ? 0f : gap);
				float y1 = y + (tile.z() + 1) * cell - (down ? 0f : gap);
				if (x1 > x0 && y1 > y0) graphics.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), fillColor);
			}
		}

		if (!revealUnexploredRooms) {
			for (int i = 0; i < DungeonScan.tiles.length; i++) {
				var tile = DungeonScan.tiles[i];
				if (tile == null || (tile.room != null && tile.room.isViewable())) continue;
				// Real bug found (per user report — "The legit map seems to be showing rooms that aren't
				// connected to any open rooms. It just outlines the opened rooms in question mark rooms when
				// it should only show the ones that have doors leading from the opened rooms"): this used to
				// check plain 6x6-grid adjacency (any of the 4 neighboring grid cells belongs to an opened
				// room), which draws a "?" on every tile that merely sits next to an opened room on the coarse
				// room grid — including tiles separated from it by a solid wall with no actual doorway. Real
				// vanilla dungeon maps only ever hint a room once a genuine door leads to it. Switched to a
				// real door-connectivity check against DungeonScan.doors (the same origin/destinationTileIndex
				// pairs doorLeadsToUnexplored below already keys off of).
				if (!hasDoorToViewableTile(i)) continue;
				int tx = i % GRID, tz = i / GRID;
				float x0 = x + tx * cell + gap, x1 = x + (tx + 1) * cell - gap;
				float y0 = y + tz * cell + gap, y1 = y + (tz + 1) * cell - gap;
				if (x1 > x0 && y1 > y0) graphics.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), 0xFF404040);
				Minecraft previewMc = Minecraft.getInstance();
				drawCenteredScaledText(graphics, previewMc, "?", x + (tx + 0.5f) * cell, y + (tz + 0.5f) * cell, cell, 0xFFAAAAAA);
			}
		}

		Minecraft mc = Minecraft.getInstance();
		for (DungeonRoom room : DungeonScan.rooms) {
			boolean viewable = room.isViewable();
			boolean knownButUnexplored = revealUnexploredRooms && !viewable && room.type != null;
			if ((!viewable && !knownButUnexplored) || room.tiles.isEmpty()) continue;
			// A known-but-unexplored room's EXACT name/secret count genuinely isn't resolved yet regardless of
			// labelMode — those come from DungeonScan's real terrain-hash matching (WorldScan), which requires
			// actually being near the room, not just the vanilla map's own low-fidelity corner-pixel color
			// (MapScan, which only ever resolves the room's broad CATEGORY — Puzzle/Trap/Miniboss/etc. — see
			// RoomType.fromMapColor). Per user follow-up ("reveal the names or secrets of the rooms instead of
			// showing a ?"), showing that real category name is still strictly more informative than a bare
			// "?" even though it's not the room's actual specific name or secret count — full identification
			// pre-exploration would need a whole separate pixel-pattern room database this codebase doesn't
			// have (same idea as NEU/SkyHanni's bundled room-data repo), out of scope here.
			String text = knownButUnexplored ? roomTypeLabel(room.type) : roomLabel(room, labelMode);
			if (text == null || text.isEmpty()) continue;
			double avgX, avgZ;
			if (room.shape == RoomShape.L && room.tiles.size() == 3) {
				// An L room is 3 of the 4 cells in a 2x2 bounding box (one corner — the "notch" —
				// isn't part of the room at all), so averaging all tile centers lands the label near
				// the middle of the full box, right by/inside that empty notch. Anchor at the elbow
				// tile instead: the corner diagonally opposite the notch, i.e. the one tile where the
				// L's two arms actually meet.
				int minX = room.tiles.stream().mapToInt(IVec2::x).min().orElseThrow();
				int maxX = room.tiles.stream().mapToInt(IVec2::x).max().orElseThrow();
				int minZ = room.tiles.stream().mapToInt(IVec2::z).min().orElseThrow();
				int maxZ = room.tiles.stream().mapToInt(IVec2::z).max().orElseThrow();
				int notchX = minX, notchZ = minZ;
				for (int[] corner : new int[][]{{minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ}}) {
					if (!room.tiles.contains(new IVec2(corner[0], corner[1]))) {
						notchX = corner[0];
						notchZ = corner[1];
						break;
					}
				}
				avgX = (minX + maxX - notchX) + 0.5;
				avgZ = (minZ + maxZ - notchZ) + 0.5;
			} else {
				avgX = room.tiles.stream().mapToInt(IVec2::x).average().orElse(0) + 0.5;
				avgZ = room.tiles.stream().mapToInt(IVec2::z).average().orElse(0) + 0.5;
			}
			int labelColor = roomLabelColor(room, labelMode);
			drawCenteredScaledText(graphics, mc, text, x + (float) (avgX * cell), y + (float) (avgZ * cell), cell, labelColor);
		}

		// Real bug found (per user report — "the outline of the map goes through my player head"): this used
		// to draw AFTER the player markers, so a player near the map's edge (common — the local player is
		// often close to it) had their own head marker painted over by the border. Moved before the markers
		// so the border sits underneath them instead of on top.
		if (outline && outlineThickness > 0) {
			int t = outlineThickness;
			graphics.fill(x, y, x + size, y + t, outlineColor);
			graphics.fill(x, y + size - t, x + size, y + size, outlineColor);
			graphics.fill(x, y, x + t, y + size, outlineColor);
			graphics.fill(x + size - t, y, x + size, y + size, outlineColor);
		}

		if (drawPlayerMarkers) {
			// Real bug found (per user report — "The other players on the map are bugging a LOT. it depends
			// on render distance or something, they arent visible most of the time"): this used to require a
			// live, currently-loaded Entity for a teammate (mc.level.getPlayerByUUID) — which real vanilla
			// networking only sends/keeps for players the client is actually tracking, a range affected by
			// the client's own "Entity Distance" option (a real, confirmed vanilla setting, distinct from
			// world/chunk render distance). A teammate in a different room or just far enough away often has
			// no live Entity object at all, so its marker silently vanished — exactly the reported symptom.
			// NoammAddons' own real, working dungeon map (see its MapUpdater.kt) never relies on live entity
			// tracking for this at all — it reads teammate positions straight from the real vanilla dungeon
			// map item's own MapDecoration data (the same packet that paints the room pixels), which Hypixel
			// populates for every real teammate regardless of entity-tracking distance. The live-entity path
			// stays PRIMARY (more accurate, real skin/facing) and unchanged when it's available.
			//
			// Real bug found in the FALLBACK itself (per user follow-up — "It should correspond with the
			// actual hotbar map and detect where the blue arrows are"): the first version of this fallback
			// matched decorations by their name field, on the unverified guess that Hypixel sets one to the
			// teammate's username — its own diagnostic dump existed specifically because that was never
			// confirmed live. The user's own correction names the real, verifiable signal instead: teammates
			// on Hypixel's dungeon map render as the real vanilla BLUE_MARKER decoration type (a genuine,
			// confirmed entry in MapDecorationTypes — the same "blue arrow" icon vanilla itself ships) — not
			// identified by name at all. Matching is now type-first: every BLUE_MARKER decoration on the held
			// map is collected once per render pass, matched to a missing-entity teammate by name if one
			// happens to be set (kept as a bonus, not the primary signal anymore), and any still-unclaimed
			// teammate just claims the next unclaimed BLUE_MARKER decoration in list order — since Hypixel
			// only ever puts one such decoration per teammate without a live entity, this still lands a real
			// marker at the real position even when no name is ever provided.
			// Tenth bug found (per user report — "the mod has issues remembering who is who on the dungeon
			// map... I hold my leap and leap to a user that is nowhere near where the map says he is"): the
			// fallback above used to claim decorations in plain LIST ORDER — claimDecorationForTeammate's own
			// "unclaimed.remove(0)" — for every teammate with no live entity this frame. Order is not a real
			// identity signal: mapData.getDecorations() has no defined correspondence between its own element
			// order and DungeonState.getTeammates()'s order, so with 2+ teammates simultaneously out of render
			// distance (extremely common — right after ANY teleport, since the client hasn't started tracking
			// the entity at the new position yet, and routinely just from being in a different room), this
			// could and did hand two teammates' markers to each other. A player who looked fine one frame could
			// silently swap positions with another the next, which is exactly "leap to a user nowhere near
			// where the map says" — you were leaping to the marker showing a DIFFERENT real teammate's spot.
			// Fixed with real remembered state instead of blind order: DungeonPlayer now caches its own last
			// known live position (lastWorldX/lastWorldZ, set below whenever a live entity IS available this
			// frame) — a real memory of where THAT SPECIFIC teammate actually was, kept for the whole run (see
			// DungeonPlayer's own doc comment on those fields). When a live entity isn't available, teammates
			// are matched to decorations by nearest remembered position via matchFallbackTeammates() below —
			// greedy nearest-neighbor across the whole unmatched set, not first-come order — so a teammate
			// keeps their own real spot even while briefly untracked, and a genuine teleport (Spirit Leap included)
			// still resolves correctly the moment the map's own decorations for the OTHER teammates update,
			// since the matcher works across all of them at once rather than one at a time.
			java.util.List<DungeonPlayer> needsFallback = new java.util.ArrayList<>();
			for (DungeonPlayer player : DungeonState.getTeammates()) {
				Player entity = player.entity;
				AbstractClientPlayer acp = entity instanceof AbstractClientPlayer ap ? ap : null;
				if (acp != null) {
					float worldX = (float) entity.getX();
					float worldZ = (float) entity.getZ();
					player.lastWorldX = worldX;
					player.lastWorldZ = worldZ;
					player.lastSeenAtMillis = System.currentTimeMillis();
					drawTeammateMarker(graphics, mc, player, worldX, worldZ, player.getRenderYaw(), acp.getSkin(),
						x, y, cell, heads, showNames, outHits);
				} else {
					needsFallback.add(player);
				}
			}
			if (!needsFallback.isEmpty()) {
				java.util.List<net.minecraft.world.level.saveddata.maps.MapDecoration> unclaimedBlueMarkers = collectBlueMarkerDecorations();
				var matches = matchFallbackTeammates(needsFallback, unclaimedBlueMarkers);
				for (var entry : matches.entrySet()) {
					DungeonPlayer player = entry.getKey();
					var deco = entry.getValue();
					float[] world = decorationPixelToWorld(deco);
					if (world == null) continue;
					float worldX = world[0];
					float worldZ = world[1];
					float yawDeg = deco.rot() * 22.5f;
					// Real bug found (per user report — "The arrow that displays on the map when a player isnt
					// within render should look like the regular arrow... It should be able to render player
					// heads since it can remember usernames, so just make it parse the player head of the
					// usernames and display that"): this used to force the plain-arrow fallback whenever there
					// was no live Entity (hasHeadTexture=false, unconditionally), even though a real skin
					// texture for this exact username is still available without one — Minecraft's own tab
					// list (PlayerInfo, from ClientboundPlayerInfoUpdatePacket) tracks every real player on the
					// server by name regardless of entity-render distance, and carries the same real
					// PlayerSkin a live entity's own getSkin() would return. Looked up by name here (mirrors
					// this class's own existing DungeonState.java PlayerInfo-by-name usage), so a teammate with
					// no live entity still renders a real rotated head, just like one that does.
					var mcConn = Minecraft.getInstance().getConnection();
					var info = mcConn != null ? mcConn.getPlayerInfo(player.name) : null;
					net.minecraft.world.entity.player.PlayerSkin skin = info != null ? info.getSkin() : null;
					drawTeammateMarker(graphics, mc, player, worldX, worldZ, yawDeg, skin, x, y, cell, heads, showNames, outHits);
				}
			}
		}

	}

	// Draws one teammate's marker (head/arrow + optional name label + hit box) at a given real world
	// position — shared by both the live-entity path and the decoration-fallback path above so the two
	// don't duplicate this rendering logic.
	private static void drawTeammateMarker(GuiGraphicsExtractor graphics, Minecraft mc, DungeonPlayer player,
											float worldX, float worldZ, float yawDeg,
											net.minecraft.world.entity.player.PlayerSkin skin,
											int x, int y, float cell, boolean heads, boolean showNames,
											java.util.List<MarkerHit> outHits) {
		float tileXFloat = (worldX + 201) / 32f;
		float tileZFloat = (worldZ + 201) / 32f;
		if (tileXFloat < 0 || tileXFloat > GRID || tileZFloat < 0 || tileZFloat > GRID) return;
		float px = x + tileXFloat * cell;
		float py = y + tileZFloat * cell;
		// Per user request ("Make the player heads on the map smaller"): reduced from 0.5x/6px min to
		// 0.35x/5px min cell size.
		int markerSize = Math.round(Math.max(5f, cell * 0.35f));
		drawPlayerMarker(graphics, skin, px, py, markerSize, heads && skin != null, yawDeg);
		if (showNames) {
			String name = player.name;
			int nameWidth = mc.font.width(name);
			graphics.text(mc.font, name, Math.round(px - nameWidth / 2f), Math.round(py - markerSize / 2f - 9), 0xFFFFFFFF);
		}
		if (outHits != null) {
			int hit = markerSize + 4;
			outHits.add(new MarkerHit(Math.round(px - hit / 2f), Math.round(py - hit / 2f), hit, hit, player));
		}
	}

	// See drawMap's own doc comment on the tenth bug fix above. Matches teammates who currently have no live
	// entity to the anonymous BLUE_MARKER decorations on the held map by nearest remembered position instead
	// of arbitrary list order: a name match (kept as the strongest possible signal, in case Hypixel ever
	// does set one) first, then greedy nearest-neighbor using each teammate's own DungeonPlayer.lastWorldX/Z
	// against every still-unclaimed decoration's real world position, repeatedly taking the globally closest
	// remaining (teammate, decoration) pair. Teammates with no remembered position yet at all (brand new
	// this run, never once seen with a live entity) have nothing to match by — those fall back to the old
	// first-available order once every teammate with real position memory has already been matched, exactly
	// like before this fix, since there's genuinely no better information for them yet.
	private static java.util.Map<DungeonPlayer, net.minecraft.world.level.saveddata.maps.MapDecoration> matchFallbackTeammates(
			java.util.List<DungeonPlayer> needsFallback,
			java.util.List<net.minecraft.world.level.saveddata.maps.MapDecoration> unclaimed) {
		java.util.Map<DungeonPlayer, net.minecraft.world.level.saveddata.maps.MapDecoration> result = new java.util.HashMap<>();
		java.util.List<DungeonPlayer> remaining = new java.util.ArrayList<>(needsFallback);

		java.util.Iterator<DungeonPlayer> nameIt = remaining.iterator();
		while (nameIt.hasNext()) {
			DungeonPlayer player = nameIt.next();
			net.minecraft.world.level.saveddata.maps.MapDecoration matched = null;
			for (var deco : unclaimed) {
				if (deco.name().isPresent() && deco.name().get().getString().equals(player.name)) {
					matched = deco;
					break;
				}
			}
			if (matched != null) {
				result.put(player, matched);
				unclaimed.remove(matched);
				nameIt.remove();
			}
		}

		while (!remaining.isEmpty() && !unclaimed.isEmpty()) {
			DungeonPlayer bestPlayer = null;
			net.minecraft.world.level.saveddata.maps.MapDecoration bestDeco = null;
			double bestDistSq = Double.MAX_VALUE;
			for (DungeonPlayer player : remaining) {
				if (Float.isNaN(player.lastWorldX)) continue;
				for (var deco : unclaimed) {
					float[] world = decorationPixelToWorld(deco);
					if (world == null) continue;
					double dx = world[0] - player.lastWorldX;
					double dz = world[1] - player.lastWorldZ;
					double distSq = dx * dx + dz * dz;
					if (distSq < bestDistSq) {
						bestDistSq = distSq;
						bestPlayer = player;
						bestDeco = deco;
					}
				}
			}
			if (bestPlayer == null) break;
			result.put(bestPlayer, bestDeco);
			remaining.remove(bestPlayer);
			unclaimed.remove(bestDeco);
		}

		// No remembered position at all to go on for whoever's left (and/or no decorations left to match
		// with real distance info) — best-effort first-available, same as this fallback's original behavior.
		for (DungeonPlayer player : remaining) {
			if (unclaimed.isEmpty()) break;
			result.put(player, unclaimed.remove(0));
		}
		return result;
	}

	// See drawMap's own doc comment on the player-marker fallback for why this exists and why it matches on
	// decoration TYPE now, not name. Collects every real vanilla BLUE_MARKER decoration on the held dungeon
	// map, once per render pass (not per-teammate) since every teammate needing the fallback shares the same
	// underlying map.
	private static java.util.List<net.minecraft.world.level.saveddata.maps.MapDecoration> collectBlueMarkerDecorations() {
		java.util.List<net.minecraft.world.level.saveddata.maps.MapDecoration> markers = new java.util.ArrayList<>();
		try {
			Minecraft mc = Minecraft.getInstance();
			var mapData = com.cokelord.skyblocksimplified.dungeon.map.MapScan.findDungeonMap(mc);
			if (mapData == null) return markers;
			for (var deco : mapData.getDecorations()) {
				if (deco.type() == net.minecraft.world.level.saveddata.maps.MapDecorationTypes.BLUE_MARKER) markers.add(deco);
			}
			// Real, honest caveat: it's not confirmed live that Hypixel's dungeon map actually reuses
			// vanilla's own BLUE_MARKER type for teammates — it's the user's own real, concrete lead ("detect
			// where the blue arrows are"), and BLUE_MARKER is a genuine, confirmed vanilla decoration type
			// (MapDecorationTypes), not a guess — but if it turns out to be the wrong type, this fallback
			// simply never finds a marker and silently falls back to "no marker" exactly like before, no
			// regression either way. This one-shot diagnostic dump gives the next round real ground truth
			// (every decoration's real type id) instead of another guess if that's what's happening.
			if (markers.isEmpty()) {
				StringBuilder sb = new StringBuilder("DungeonMap: no BLUE_MARKER decorations found, raw dump:");
				for (var deco : mapData.getDecorations()) {
					sb.append(" [x=").append(deco.x()).append(" y=").append(deco.y()).append(" rot=").append(deco.rot())
						.append(" type=").append(deco.type().unwrapKey().map(k -> k.identifier().toString()).orElse("<unregistered>"))
						.append(" name=").append(deco.name().map(net.minecraft.network.chat.Component::getString).orElse("<none>")).append(']');
				}
			}
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("DungeonMapFeature: failed to read held map decorations", e);
		}
		return markers;
	}


	/** Converts a real MapDecoration's own map-local byte x/y (see NoammAddons' confirmed
	 *  {@code MapDecoration.mapX}/{@code mapZ} extension properties — {@code (byte + 128) >> 1} maps the
	 *  -128..127 byte range onto the real 0..127 pixel space MapScan/DungeonScan already operate in) into
	 *  real world X/Z, by inverting the exact same pixel&lt;-&gt;world formulas this class's own live-entity
	 *  marker code and {@link com.cokelord.skyblocksimplified.dungeon.map.DungeonScan}'s own tile-grid math
	 *  already use elsewhere — not a new/independent formula. Returns null if DungeonScan's own tile-grid
	 *  calibration (roomGap) isn't resolved yet. */
	private static float[] decorationPixelToWorld(net.minecraft.world.level.saveddata.maps.MapDecoration deco) {
		if (com.cokelord.skyblocksimplified.dungeon.map.DungeonScan.roomGap <= 0) return null;
		int pixelX = (deco.x() + 128) >> 1;
		int pixelZ = (deco.y() + 128) >> 1;
		double tileXFloat = (pixelX - com.cokelord.skyblocksimplified.dungeon.map.DungeonScan.startX) / (double) com.cokelord.skyblocksimplified.dungeon.map.DungeonScan.roomGap;
		double tileZFloat = (pixelZ - com.cokelord.skyblocksimplified.dungeon.map.DungeonScan.startY) / (double) com.cokelord.skyblocksimplified.dungeon.map.DungeonScan.roomGap;
		return new float[]{(float) (tileXFloat * 32 - 201), (float) (tileZFloat * 32 - 201)};
	}

	private static void drawPlayerMarker(GuiGraphicsExtractor graphics, net.minecraft.world.entity.player.PlayerSkin skin, float px, float py,
										  int markerSize, boolean heads, float yawDegrees) {
		// Real facing bug found (per user report — "the arrow/thing that shows the player is facing the wrong
		// way, complete 180"): the old formula (-(yaw+90) fed straight into cos/sin) didn't match Minecraft's
		// real yaw convention. Standard, textbook MC horizontal facing vector is (-sin(yaw), cos(yaw)) — yaw in
		// degrees, 0 = south/+Z, increasing clockwise — which maps directly onto this map's screen axes since
		// screen +X already IS world +X and screen +Y (down) already IS world +Z, with no extra rotation/offset
		// needed. Both the fallback tick AND the (new) rotated head marker below share this one vector so they
		// never drift apart again.
		double yawRad = Math.toRadians(yawDegrees);
		float fx = (float) -Math.sin(yawRad);
		float fz = (float) Math.cos(yawRad);

		if (heads && skin != null) {
			try {
				drawRotatedHead(graphics, skin, px, py, markerSize, fx, fz);
				return;
			} catch (Exception ignored) {
				// Skin texture not downloaded/ready yet this frame — fall through to the plain-dot marker below.
			}
		}
		RenderUtil.fillCircle(graphics, Math.round(px), Math.round(py), Math.max(2, markerSize / 3), 0xFFFFFFFF);
		float tickLen = markerSize * 0.9f;
		float tx = px + fx * tickLen;
		float ty = py + fz * tickLen;
		RenderUtil.drawLine(graphics, px, py, tx, ty, 2f, 0xFFFFFFFF);
	}

	/** Player-head marker that rotates with facing like the arrow does, per user request, plus a 1px black
	 *  outline so it stays legible against light room fills. Rotation reuses the exact angle convention
	 *  {@link RenderUtil#drawLine} already establishes (rotate() aligns local +X with (fx,fz)); a face
	 *  texture's own "up" edge needs to point along (fx,fz) instead of its "right" edge, hence the extra +90°. */
	private static void drawRotatedHead(GuiGraphicsExtractor graphics, net.minecraft.world.entity.player.PlayerSkin skin, float px, float py,
										 int markerSize, float fx, float fz) {
		float angle = (float) Math.atan2(fz, fx) + (float) (Math.PI / 2);
		int half = markerSize / 2;
		// Real bug found (per user report — "the outline of heads on the map only displays on one side of the
		// head"): for an odd markerSize, integer-division half truncates, so the face texture (drawn from
		// -half spanning markerSize) actually ends at -half+markerSize — one pixel PAST the "symmetric" +half
		// the background square assumed. The background was centered on (0,0) but the face wasn't, so their
		// edges coincided exactly on one side (hiding the border there) while doubling up on the other. Using
		// the face's own real bounds for the background square keeps them concentric regardless of parity.
		int faceMaxHalf = markerSize - half;
		graphics.pose().pushMatrix();
		try {
			graphics.pose().translate(px, py);
			graphics.pose().rotate(angle);
			// Per user request ("Make the head outline a pixel thicker"): was a 1px border (background square
			// only 1px larger than the face on every side); now 2px.
			graphics.fill(-half - 2, -half - 2, faceMaxHalf + 2, faceMaxHalf + 2, 0xFF000000);
			PlayerFaceExtractor.extractRenderState(graphics, skin, -half, -half, markerSize);
		} finally {
			graphics.pose().popMatrix();
		}
	}

	private static void drawCenteredScaledText(GuiGraphicsExtractor graphics, Minecraft mc, String text, float cx, float cy, float cell, int color) {
		int rawWidth = mc.font.width(text);
		float maxWidth = cell * 0.9f;
		float textScale = rawWidth <= 0 ? 1f : Math.min(1f, maxWidth / rawWidth);
		textScale = Math.max(0.4f, textScale);
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy);
		graphics.pose().scale(textScale);
		graphics.text(mc.font, text, Math.round(-rawWidth / 2f), -4, color);
		graphics.pose().popMatrix();
	}

	/** True if a real door (from DungeonScan.doors, not just plain grid adjacency) connects the tile at
	 *  index tileIndex to a tile belonging to a room the player has actually explored — used to decide
	 *  whether an unopened tile gets the small vanilla-accurate "?" preview. See this method's call site
	 *  above for the real bug this replaced (plain 4-neighbor grid adjacency, with no check that an actual
	 *  doorway — as opposed to a solid wall — connects the two tiles). */
	private static boolean hasDoorToViewableTile(int tileIndex) {
		var tiles = DungeonScan.tiles;
		for (DungeonDoor door : DungeonScan.doors.values()) {
			int otherIndex;
			if (door.originTileIndex == tileIndex) otherIndex = door.destinationTileIndex;
			else if (door.destinationTileIndex == tileIndex) otherIndex = door.originTileIndex;
			else continue;
			if (otherIndex < 0 || otherIndex >= tiles.length) continue;
			var otherTile = tiles[otherIndex];
			if (otherTile != null && otherTile.room != null && otherTile.room.isViewable()) return true;
		}
		return false;
	}

	private static String roomLabel(DungeonRoom room, RoomLabelMode mode) {
		return switch (mode) {
			// Per user request: "found/max" instead of just the max — room.secretsFound is only ever known
			// once the player has actually stood in the room and the action bar confirmed it (see
			// DungeonRoom.secretsFound's own doc comment), so it shows "?" until then rather than a fabricated
			// found-count. A fully cleared room (GREEN checkmark) shows its real total for both halves, since
			// "done" means every secret in it was found even if this particular room's own reading was missed.
			// Per user request: "rooms that dont have secrets should just show the name of the room" — a
			// "0/0" fraction on a room with no secrets at all (a plain connector/trap/blood room, etc.) is
			// pure clutter compared to the room's actual name, so it falls back to NAME mode's own text there.
			case SECRETS -> {
				if (room.data == null) yield "?";
				int total = room.data.getSecrets();
				if (total == 0) yield room.getName() != null ? room.getName() : "?";
				// Per user request ("A fully cleared room also still shows secrets. Add a filter, if a room
				// ... has max secrets count (full secrets) then it shouldnt show secrets"): a fully cleared
				// (GREEN checkmark) room's fraction is always total/total — no new information over just
				// knowing it's done — so it now falls back to the room's name instead, same as the
				// zero-secrets case right above.
				if (room.checkmark == MapCheckmark.GREEN) yield room.getName() != null ? room.getName() : "?";
				int found = room.secretsFound;
				yield (found >= 0 ? found : "?") + "/" + total;
			}
			case NAME -> room.getName() != null ? room.getName() : "?";
			case CHECKMARK -> switch (room.checkmark) {
				case GREEN, WHITE -> "✓";
				case RED -> "✗";
				case QUESTION_MARK -> "?";
				case NONE, UNDISCOVERED -> "";
			};
		};
	}

	/** Best-available text for a cheat-map-revealed room the player hasn't actually explored yet — just its
	 *  broad category (Puzzle/Trap/Miniboss/etc.), the only real data the vanilla map's own corner-pixel
	 *  color resolves this early (see the call site's own doc comment for why exact name/secrets aren't
	 *  resolvable here at all). */
	private static String roomTypeLabel(com.cokelord.skyblocksimplified.dungeon.map.tile.RoomType type) {
		if (type == null) return "?";
		String name = type.name();
		return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
	}

	private static final int COLOR_CLEAR_STATE_GRAY = 0xFFAAAAAA;
	private static final int COLOR_CLEAR_STATE_WHITE = 0xFFFFFFFF;
	private static final int COLOR_CLEAR_STATE_GREEN = 0xFF55FF55;

	/** Per user request — a real three-way clear-progress color, not the previous binary white/gray:
	 *  gray means the room's starred mobs (miniboss) aren't dead yet, white means they are but not every
	 *  secret in the room has been taken, green means BOTH — a real GREEN {@link MapCheckmark}, exactly what
	 *  Hypixel's own map pixel data already reports (WHITE = mobs cleared, GREEN = fully cleared including
	 *  secrets — see {@link MapCheckmark#fromMapColor}). Superseded an earlier round's white-only-when-GREEN
	 *  spec, and now applies uniformly across every label mode (including Checkmark, whose own ✓/✗/? glyph
	 *  gets this same coloring instead of being hardcoded white regardless of state). */
	private static int clearStateColor(DungeonRoom room) {
		return switch (room.checkmark) {
			case GREEN -> COLOR_CLEAR_STATE_GREEN;
			case WHITE -> COLOR_CLEAR_STATE_WHITE;
			case NONE, RED, QUESTION_MARK, UNDISCOVERED -> COLOR_CLEAR_STATE_GRAY;
		};
	}

	private static int roomLabelColor(DungeonRoom room, RoomLabelMode mode) {
		return clearStateColor(room);
	}

	/** Decodes the room's real vanilla-map pixel color (see {@link RoomType#getMapColor()}) into an actual
	 *  RGB int via Minecraft's own {@link MapColor}, instead of a guessed palette — matches this project's
	 *  "never guess a real value" rule, since this IS the exact color Hypixel's own map item already shows.
	 *  Per user clarification ("When i said dungeon rooms i meant the text not rooms themselves"), the room
	 *  tile fill stays on this real TYPE color; only the text/label color follows clear-progress state (see
	 *  {@link #clearStateColor}/{@link #roomLabelColor}). */
	private static int roomColor(DungeonRoom room) {
		byte mapColor = room.type != null ? room.type.getMapColor() : RoomType.UNKNOWN.getMapColor();
		return 0xFF000000 | MapColor.getColorFromPackedId(mapColor & 0xFF);
	}

	/** Halves an opaque color's brightness — used for known-but-unexplored rooms so they read as "there,
	 *  but not confirmed" without being mistaken for a fully resolved room's real color. */
	private static int dimColor(int argb) {
		int r = ((argb >> 16) & 0xFF) / 2, g = ((argb >> 8) & 0xFF) / 2, b = (argb & 0xFF) / 2;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	// Real per-Hypixel-dungeon-map door colors — the same convention every reference mod (Odin/SkyHanni/
	// devonian, cross-checked against devonian's own DungeonMapRenderOptions default door colors) uses:
	// wither/blood/fairy doors get a dedicated color, normal doors a plain light gray.
	/** True if either side of the door isn't a real, actually-explored room yet — same tile/room graph
	 *  Door Highlight's own resolveDoorHighestBlock already uses to look up a door's neighboring rooms. */
	private static boolean doorLeadsToUnexplored(DungeonDoor door) {
		var tiles = DungeonScan.tiles;
		for (int tileIndex : new int[]{door.originTileIndex, door.destinationTileIndex}) {
			if (tileIndex < 0 || tileIndex >= tiles.length) return true;
			var tile = tiles[tileIndex];
			if (tile == null || tile.room == null || !tile.room.isViewable()) return true;
		}
		return false;
	}

	private static int doorColor(DoorType type) {
		return switch (type) {
			case NORMAL -> 0xFFAAAAAA;
			case WITHER -> 0xFF1A1A1A;
			case BLOOD -> 0xFF8B0000;
			case FAIRY -> 0xFFFF66CC;
		};
	}

	public boolean isOutlineEnabled() { return outlineEnabled; }
	public void setOutlineEnabled(boolean value) { outlineEnabled = value; }
	public int getOutlineColor() { return outlineColor; }
	public void setOutlineColor(int value) { outlineColor = value; }
	public int getOutlineThickness() { return outlineThickness; }
	public void setOutlineThickness(int value) { outlineThickness = Math.max(1, Math.min(10, value)); }

	public boolean isBackgroundEnabled() { return backgroundEnabled; }
	public void setBackgroundEnabled(boolean value) { backgroundEnabled = value; }
	public float getBackgroundOpacity() { return backgroundOpacity; }
	public void setBackgroundOpacity(float value) { backgroundOpacity = Math.max(0f, Math.min(1f, value)); }
	public int getBackgroundColor() { return backgroundColor; }
	public void setBackgroundColor(int value) { backgroundColor = value & 0xFFFFFF; }

	public boolean isPlayerHeads() { return playerHeads; }
	public void setPlayerHeads(boolean value) { playerHeads = value; }
	public boolean isRevealUnexploredRooms() { return revealUnexploredRooms; }
	public void setRevealUnexploredRooms(boolean value) { revealUnexploredRooms = value; }
	public ShowNamesMode getShowNamesMode() { return showNamesMode; }
	public void setShowNamesMode(ShowNamesMode value) { showNamesMode = value; }
	public RoomLabelMode getRoomLabelMode() { return roomLabelMode; }
	public void setRoomLabelMode(RoomLabelMode value) { roomLabelMode = value; }
	public boolean isMapInfoEnabled() { return mapInfoEnabled; }
	public void setMapInfoEnabled(boolean value) { mapInfoEnabled = value; }
	public boolean isMapInfoShowCrypts() { return mapInfoShowCrypts; }
	public void setMapInfoShowCrypts(boolean value) { mapInfoShowCrypts = value; }
	public boolean isMapInfoShowDeaths() { return mapInfoShowDeaths; }
	public void setMapInfoShowDeaths(boolean value) { mapInfoShowDeaths = value; }
	public boolean isMapInfoShowMimic() { return mapInfoShowMimic; }
	public void setMapInfoShowMimic(boolean value) { mapInfoShowMimic = value; }
	public int getMapInfoTextColor() { return mapInfoTextColor; }
	public void setMapInfoTextColor(int value) { mapInfoTextColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("outlineEnabled", outlineEnabled);
		obj.addProperty("outlineColor", outlineColor);
		obj.addProperty("outlineThickness", outlineThickness);
		obj.addProperty("backgroundEnabled", backgroundEnabled);
		obj.addProperty("backgroundOpacity", backgroundOpacity);
		obj.addProperty("backgroundColor", backgroundColor);
		obj.addProperty("playerHeads", playerHeads);
		obj.addProperty("revealUnexploredRooms", revealUnexploredRooms);
		obj.addProperty("showNamesMode", showNamesMode.name());
		obj.addProperty("roomLabelMode", roomLabelMode.name());
		obj.addProperty("mapInfoEnabled", mapInfoEnabled);
		obj.addProperty("mapInfoShowCrypts", mapInfoShowCrypts);
		obj.addProperty("mapInfoShowDeaths", mapInfoShowDeaths);
		obj.addProperty("mapInfoShowMimic", mapInfoShowMimic);
		obj.addProperty("mapInfoTextColor", mapInfoTextColor);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("outlineEnabled")) outlineEnabled = obj.get("outlineEnabled").getAsBoolean();
		if (obj.has("outlineColor")) outlineColor = obj.get("outlineColor").getAsInt();
		if (obj.has("outlineThickness")) outlineThickness = obj.get("outlineThickness").getAsInt();
		if (obj.has("backgroundEnabled")) backgroundEnabled = obj.get("backgroundEnabled").getAsBoolean();
		if (obj.has("backgroundOpacity")) backgroundOpacity = obj.get("backgroundOpacity").getAsFloat();
		if (obj.has("backgroundColor")) backgroundColor = obj.get("backgroundColor").getAsInt();
		if (obj.has("playerHeads")) playerHeads = obj.get("playerHeads").getAsBoolean();
		if (obj.has("revealUnexploredRooms")) revealUnexploredRooms = obj.get("revealUnexploredRooms").getAsBoolean();
		if (obj.has("showNamesMode")) { try { showNamesMode = ShowNamesMode.valueOf(obj.get("showNamesMode").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("roomLabelMode")) { try { roomLabelMode = RoomLabelMode.valueOf(obj.get("roomLabelMode").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("mapInfoEnabled")) mapInfoEnabled = obj.get("mapInfoEnabled").getAsBoolean();
		if (obj.has("mapInfoShowCrypts")) mapInfoShowCrypts = obj.get("mapInfoShowCrypts").getAsBoolean();
		if (obj.has("mapInfoShowDeaths")) mapInfoShowDeaths = obj.get("mapInfoShowDeaths").getAsBoolean();
		if (obj.has("mapInfoShowMimic")) mapInfoShowMimic = obj.get("mapInfoShowMimic").getAsBoolean();
		if (obj.has("mapInfoTextColor")) mapInfoTextColor = obj.get("mapInfoTextColor").getAsInt();
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
	}

	@Override
	public String getDescription() {
		return "Draggable, resizable live map of the dungeon showing rooms and doors as you explore them.";
	}
}
