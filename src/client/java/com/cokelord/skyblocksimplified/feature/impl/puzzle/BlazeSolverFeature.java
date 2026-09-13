package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.Puzzle;
import com.cokelord.skyblocksimplified.dungeon.PuzzleStatus;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** F4 "Higher/Lower" Blaze puzzle solver — reads each blaze armor stand's HP from its nametag, sorts them
 *  into kill order (highest-HP first on "Lower Blaze", lowest-HP first on "Higher Blaze"), and highlights
 *  the next few in priority order with connecting lines. Ported from Odin's {@code BlazeSolver.kt}. */
public class BlazeSolverFeature extends Feature {
	// Real bug found (per repeated user report — "shows nothing on the blazes"): both this port's original
	// pattern and Odin's own real BlazeSolver.kt hardcode "[Lv15]" — but Hypixel dungeon mobs scale their
	// level with the floor, so a Blaze puzzle on any floor other than whichever one that regex was captured
	// on would never match at all. Skyblocker's own real DungeonBlaze.java confirms this: it never checks a
	// level prefix at all, just `name.contains("Blaze") && name.contains("/")` then parses the number after
	// the "/" up to the last character (the "❤"). Ported that same tolerant approach instead of guessing a
	// level-agnostic regex.
	private static final Pattern HEALTH_PATTERN = Pattern.compile("Blaze [\\d,]+/([\\d,]+)");
	private static final double SCAN_RANGE = 32.0;

	private List<ArmorStand> blazes = new ArrayList<>();
	private int lastBlazeCount = 10;

	private boolean drawLineToNext = true;
	private int lineToNextCount = 2;
	// Per user request ("Add 3d rendering support to the blaze solver aswell"): reuses
	// MobHighlightFeature.RenderMode directly, the same 2D/2D Fill/3D/3D Fill render selector every other
	// entity-highlight module in this codebase already offers (see BloodCampFeature's own doc comment on
	// its identical renderMode field for how the two 3D modes actually get drawn as real occluded/ESP-style
	// boxes via World3DRenderer instead of this class's own 2D WorldRenderUtil path).
	private MobHighlightFeature.RenderMode renderMode = MobHighlightFeature.RenderMode.OUTLINE_2D;
	// Same meaning as MobHighlightFeature/BloodCampFeature's own field of the same name — only consulted for
	// the two 3D render modes.
	private boolean occlusion3D = true;
	// Per later user follow-up ("It should be able to show me current blaze, next blaze and have a color for
	// the rest... three color selectors, one for the current shot, one for the next shot, and one for the
	// rest of the blazes. Allow users to turn off the last one"): back to 3 tiers after an earlier round
	// collapsed "next" and "the rest" into one secondColor — firstColor stays the current target (index 0),
	// secondColor is now specifically the immediate next target (index 1), thirdColor covers every blaze
	// after that (index 2+), independently toggleable off via showRestBlazes.
	private int firstColor = 0xFFFF5555;
	private int secondColor = 0xFFFFAA00;
	private int thirdColor = 0xFF55FF55;
	private boolean showRestBlazes = true;
	private boolean sendCompleteMessage = false;
	// Per user request ("Add a 'Don't render blazes' to the blaze puzzle. This should still show the
	// highlights just not the blazes"): hides the real vanilla armor-stand entity via EntityHideRegistry
	// (same infra EntityHideFeature/DungeonDeclutterFeature use) — purely a render-layer hide, orthogonal
	// to this solver's own box/line rendering below, which reads the tracked blazes list directly and
	// isn't affected either way.
	private boolean hideBlazes = false;

	// Populated by renderInner (the HUD pass) each frame when renderMode is one of the 3D modes — see
	// render3DInner's own doc comment for why the actual draw happens during the level-render pass instead.
	private record PendingBox3D(AABB box, int color) {}
	private final List<PendingBox3D> pending3DBoxes = new ArrayList<>();

	private static boolean listenersRegistered = false;
	private static BlazeSolverFeature instance;

	public BlazeSolverFeature() {
		super("puzzle_blaze", "Blaze (Higher/Lower) Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Puzzles"; }

	@Override
	protected void onEnable() {
		reset();
		applyHideRule();
		if (!listenersRegistered) {
			listenersRegistered = true;
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "puzzle_blaze"), BlazeSolverFeature::renderStatic);
			World3DRenderer.addRenderCallback(BlazeSolverFeature::render3DStatic);
		}
	}

	@Override
	protected void onDisable() {
		reset();
		EntityHideRegistry.clearRule("puzzle_blaze_hide");
		com.cokelord.skyblocksimplified.sound.SoundMuteRegistry.clearRule("puzzle_blaze_hide_sound");
	}

	private void reset() {
		lastBlazeCount = 10;
		blazes.clear();
		pending3DBoxes.clear();
	}

	// Real bug found (per user report — "'Don't render blazes' toggle... doesn't mute sound"):
	// EntityHideRegistry only ever hooks EntityRenderer#shouldRender (visual only, see its own class doc
	// comment) — there was never any sound-side rule at all, so the real vanilla Blaze burn/ambient/hurt
	// sounds these mobs make kept playing even with the toggle on. Muted the same way every other "mute X"
	// module in this codebase does (SoundMuteRegistry, see SoundMuteFeature's own doc comment) rather than
	// building a one-off mechanism — matched on sound id containing "blaze" (the real vanilla sound event
	// family: entity.blaze.ambient/hurt/death/burn/shoot), gated on the same hideBlazes+isBlazeRoom()
	// condition the visual hide already uses.
	private void applyHideRule() {
		if (hideBlazes) {
			EntityHideRegistry.setRule("puzzle_blaze_hide", this::isTrackedBlaze);
			com.cokelord.skyblocksimplified.sound.SoundMuteRegistry.setRule("puzzle_blaze_hide_sound",
				sound -> isEnabled() && isBlazeRoom() && sound.getIdentifier().getPath().contains("blaze"));
		} else {
			EntityHideRegistry.clearRule("puzzle_blaze_hide");
			com.cokelord.skyblocksimplified.sound.SoundMuteRegistry.clearRule("puzzle_blaze_hide_sound");
		}
	}

	// Real bug found (per user report — "the issue with blaze slayer also was not the sounds, the blazes
	// just kept rendering"): the earlier round only ever fixed the SOUND side (see applyHideRule's own doc
	// comment) on the assumption the visual hide already worked because it hid the ArmorStand carrying the
	// "Blaze 5/20❤" nametag. But that ArmorStand only carries the HP TEXT — Hypixel spawns the actual
	// visible blaze MODEL as a separate entity positioned right at/next to it (same pattern as its real
	// damage-splash ArmorStands elsewhere, which this exact mixin already hides correctly per its own doc
	// comment: the text and the thing it's labeling aren't always the same entity). Hiding only the text
	// carrier left the real model fully visible the whole time. Now also hides any other non-player entity
	// close enough to a currently tracked blaze's own position to plausibly be that same blaze's visible
	// body, using the positions {@link #onTick} already scans every tick for sorting/highlighting.
	private static final double CO_LOCATED_RADIUS_SQ = 1.44; // 1.2 blocks

	private boolean isTrackedBlaze(Entity entity) {
		if (!isEnabled() || !isBlazeRoom()) return false;
		if (entity instanceof ArmorStand stand) return HEALTH_PATTERN.matcher(stand.getName().getString()).find();
		if (entity instanceof net.minecraft.world.entity.player.Player) return false;
		// Real bug found (per user report — "Blazes are still rendering fully... the blazes themselves are
		// still rendering"): the co-located-ArmorStand proximity check below is a heuristic (its own doc
		// comment already says so) and apparently still isn't always catching the real visible model. Any
		// vanilla Blaze mob entity in a room that's currently a Blaze puzzle room is unambiguously one of
		// these — there's no other reason that entity type would spawn here — so it's hidden directly and
		// unconditionally now, without depending on proximity to a still-alive/still-tracked ArmorStand at
		// all (which the co-location check alone requires).
		if (entity instanceof net.minecraft.world.entity.monster.Blaze) return true;
		for (ArmorStand stand : blazes) {
			if (stand.distanceToSqr(entity) <= CO_LOCATED_RADIUS_SQ) return true;
		}
		return false;
	}

	private static boolean isBlazeRoom() {
		DungeonRoom room = WorldScan.getCurrentRoom();
		return room != null && room.data != null
			&& ("Lower Blaze".equals(room.data.getName()) || "Higher Blaze".equals(room.data.getName()));
	}

	// Diagnostic only: user reports "detects entering a puzzle but not which one" — logs the room's own
	// resolved type/name (or confirms it's still null/unresolved) once per room change, so it's clear
	// whether room.data ever actually resolves for Blaze rooms specifically (a rooms.json core-hash gap,
	// unverifiable without this live data) versus some other cause.
	private static DungeonRoom lastLoggedRoom;

	private static void logRoomDataOnce(Minecraft client) {
		DungeonRoom room = WorldScan.getCurrentRoom();
		if (room == lastLoggedRoom) return;
		lastLoggedRoom = room;
		if (room == null) return;
	}

	@Override
	public void onTick(Minecraft client) {
		logRoomDataOnce(client);
		if (client.level == null || client.player == null || !isBlazeRoom()) {
			// Real bug found (per user report — "very wrong at times... it usually happens when leaving the
			// room and coming back"): blazes/lastBlazeCount used to stay completely untouched here whenever
			// the player stepped out of range/left the room — a genuine re-entry (a fresh puzzle attempt with
			// new armor stand entities) started out rendering/sorting against last visit's stale references
			// until the first full rescan below happened to overwrite them. Cleared eagerly instead so
			// "currently outside the room" is always a clean slate.
			if (!blazes.isEmpty()) blazes = new ArrayList<>();
			lastBlazeCount = 10;
			return;
		}

		var found = new java.util.HashMap<ArmorStand, Integer>();
		List<ArmorStand> nearby = new ArrayList<>();
		for (var entity : client.level.getEntities(client.player, client.player.getBoundingBox().inflate(SCAN_RANGE), e -> e instanceof ArmorStand)) {
			ArmorStand stand = (ArmorStand) entity;
			Matcher m = HEALTH_PATTERN.matcher(stand.getName().getString());
			if (!m.find()) continue;
			try {
				int hp = Integer.parseInt(m.group(1).replace(",", ""));
				found.put(stand, hp);
				nearby.add(stand);
			} catch (NumberFormatException ignored) {}
		}

		DungeonRoom room = WorldScan.getCurrentRoom();
		boolean lower = room != null && room.data != null && "Lower Blaze".equals(room.data.getName());
		nearby.sort((a, b) -> lower ? found.get(b) - found.get(a) : found.get(a) - found.get(b));
		blazes = nearby;

		// Real bug found (per task tracker #581 investigation): this completion bookkeeping used to live in
		// renderInner(), which renderStatic() now skips entirely whenever a blocking screen (e.g. the player's
		// own inventory) is open — see renderStatic()'s own doc comment on that fix. onTick() always runs
		// regardless of GUI state, so the last blaze dying while a blocking screen happens to be open no
		// longer misses the COMPLETED transition and its "pc Blaze puzzle solved!" message entirely.
		if (nearby.isEmpty() && lastBlazeCount == 1) {
			Puzzle.BLAZE.status = PuzzleStatus.COMPLETED;
			if (sendCompleteMessage && client.player.connection != null) {
				client.player.connection.sendCommand("pc Blaze puzzle solved!");
			}
			lastBlazeCount = 0;
		} else {
			lastBlazeCount = nearby.size();
		}
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled()) return;
		// Real bug found (per user report — "renders through the inventory, it should render through items
		// and tooltips and guis"): this ESP-style overlay is drawn via HudElementRegistry, which (unlike
		// real depth-tested world geometry) still fires every frame even while an inventory/GUI screen is
		// open on top — nothing about it was ever gated on GUI state. Skipping the whole draw whenever any
		// real screen is open (same ChatOverlapUtil helper HighlightBoxRenderer already uses for this exact
		// purpose — a real screen blocks it, chat alone doesn't) keeps it from poking through item icons/
		// tooltips/backgrounds.
		if (com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Blaze solver render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		// Repopulated below (2D modes never add to it) — the render3D pass reads whatever's here from the
		// last time this ran, same "drain/refill once per HUD pass" shape BloodCampFeature's own identical
		// field uses.
		pending3DBoxes.clear();
		if (!isBlazeRoom() || blazes.isEmpty()) return;

		// Render-time-only filter — the real completion bookkeeping (and lastBlazeCount tracking) now lives
		// in onTick() (see its own doc comment on why), which always runs regardless of GUI state; this just
		// keeps a stale dead entity from drawing for the fraction of a tick before onTick's own fresh scan
		// removes it from `blazes` for good.
		List<ArmorStand> alive = blazes.stream().filter(ArmorStand::isAlive).toList();
		if (alive.isEmpty()) return;

		Vec3 lastCenter = null;
		for (int index = 0; index < alive.size(); index++) {
			// Real bug found (per user report — "it should have three color selectors, one for the current
			// shot, one for the next shot, and one for the rest of the blazes... currently the 'next blaze'
			// color applies to all blazes that are after current"): index 1+ used to all share secondColor,
			// so "next" and "everything after that" were visually indistinguishable. Now a real 3-tier split,
			// with the "rest" tier (index 2+) independently hideable via showRestBlazes.
			if (index >= 2 && !showRestBlazes) break;
			ArmorStand entity = alive.get(index);
			int color = index == 0 ? firstColor : index == 1 ? secondColor : thirdColor;
			AABB box = entity.getBoundingBox().inflate(0.5, 1.0, 0.5).move(0, -1.0, 0);
			drawBox(graphics, box, color);

			if (drawLineToNext && index > 0 && index <= lineToNextCount && lastCenter != null) {
				WorldRenderUtil.drawLine(graphics, lastCenter, box.getCenter(), color, 2);
			}
			lastCenter = box.getCenter();
		}
	}

	/** Dispatches on {@link #renderMode}: the two 2D modes still draw immediately here (this is the HUD
	 *  pass, a plain screen-space overlay); the two 3D modes instead queue into {@link #pending3DBoxes} for
	 *  {@link #render3DInner} to actually draw during the level-render pass, since only that pass can be
	 *  depth-tested/occluded against the real world. Same split BloodCampFeature's own drawBox uses. */
	private void drawBox(GuiGraphicsExtractor graphics, AABB box, int color) {
		if (renderMode == MobHighlightFeature.RenderMode.WIRE_3D || renderMode == MobHighlightFeature.RenderMode.FULL_3D) {
			pending3DBoxes.add(new PendingBox3D(box, color));
			return;
		}
		WorldRenderUtil.RenderStyle style = renderMode == MobHighlightFeature.RenderMode.FULL_2D
			? WorldRenderUtil.RenderStyle.FILLED_OUTLINE : WorldRenderUtil.RenderStyle.OUTLINE;
		WorldRenderUtil.drawStyledBox(graphics, box, color, style, 2, 40);
	}

	private static void render3DStatic() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.render3DInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Blaze solver 3D render failed, skipping this frame", e);
		}
	}

	/** Draws whatever {@link #pending3DBoxes} held after the most recent HUD-pass computation — see that
	 *  field's own doc comment. Mirrors {@code HighlightBoxRenderer.drawBox3D}/BloodCampFeature's own
	 *  render3DInner exactly (same World3DRenderer calls, same occlusion3D meaning) so this behaves
	 *  identically to every other module offering this render selector. */
	private void render3DInner() {
		if (renderMode != MobHighlightFeature.RenderMode.WIRE_3D && renderMode != MobHighlightFeature.RenderMode.FULL_3D) return;
		boolean fill = renderMode == MobHighlightFeature.RenderMode.FULL_3D;
		for (PendingBox3D pending : pending3DBoxes) {
			int fillAlpha = fill ? Math.round(40 / 100f * 255f) : 0;
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

	public boolean isDrawLineToNext() { return drawLineToNext; }
	public void setDrawLineToNext(boolean value) { drawLineToNext = value; }
	public int getLineToNextCount() { return lineToNextCount; }
	public void setLineToNextCount(int value) { lineToNextCount = Math.max(0, Math.min(10, value)); }
	public MobHighlightFeature.RenderMode getRenderMode() { return renderMode; }
	public void setRenderMode(MobHighlightFeature.RenderMode value) { renderMode = value; }
	public boolean isOcclusion3D() { return occlusion3D; }
	public void setOcclusion3D(boolean value) { occlusion3D = value; }
	public int getFirstColor() { return firstColor; }
	public void setFirstColor(int value) { firstColor = value; }
	public int getSecondColor() { return secondColor; }
	public void setSecondColor(int value) { secondColor = value; }
	public int getThirdColor() { return thirdColor; }
	public void setThirdColor(int value) { thirdColor = value; }
	public boolean isShowRestBlazes() { return showRestBlazes; }
	public void setShowRestBlazes(boolean value) { showRestBlazes = value; }
	public boolean isSendCompleteMessage() { return sendCompleteMessage; }
	public void setSendCompleteMessage(boolean value) { sendCompleteMessage = value; }
	public boolean isHideBlazes() { return hideBlazes; }
	public void setHideBlazes(boolean value) { hideBlazes = value; applyHideRule(); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("drawLineToNext", drawLineToNext);
		obj.addProperty("lineToNextCount", lineToNextCount);
		obj.addProperty("renderMode", renderMode.name());
		obj.addProperty("occlusion3D", occlusion3D);
		obj.addProperty("firstColor", firstColor);
		obj.addProperty("secondColor", secondColor);
		obj.addProperty("thirdColor", thirdColor);
		obj.addProperty("showRestBlazes", showRestBlazes);
		obj.addProperty("sendCompleteMessage", sendCompleteMessage);
		obj.addProperty("hideBlazes", hideBlazes);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("drawLineToNext")) drawLineToNext = obj.get("drawLineToNext").getAsBoolean();
		if (obj.has("lineToNextCount")) lineToNextCount = obj.get("lineToNextCount").getAsInt();
		if (obj.has("renderMode")) {
			try { renderMode = MobHighlightFeature.RenderMode.valueOf(obj.get("renderMode").getAsString()); } catch (IllegalArgumentException ignored) {}
		} else if (obj.has("style")) {
			// Migrating an older save from before this module offered 3D render modes (task: "Add 3d
			// rendering support to the blaze solver aswell") — the old 3-way Outline/Filled/Filled Outline
			// cycle has no exact equivalent in the new 2D/2D Fill/3D/3D Fill selector, so this is a
			// best-effort lossy mapping: plain Outline stays outline-only, either filled variant becomes the
			// new filled 2D mode (closest visual match to what was actually showing before).
			renderMode = "OUTLINE".equals(obj.get("style").getAsString())
				? MobHighlightFeature.RenderMode.OUTLINE_2D : MobHighlightFeature.RenderMode.FULL_2D;
		}
		if (obj.has("occlusion3D")) occlusion3D = obj.get("occlusion3D").getAsBoolean();
		if (obj.has("firstColor")) firstColor = obj.get("firstColor").getAsInt();
		if (obj.has("secondColor")) secondColor = obj.get("secondColor").getAsInt();
		if (obj.has("thirdColor")) thirdColor = obj.get("thirdColor").getAsInt();
		if (obj.has("showRestBlazes")) showRestBlazes = obj.get("showRestBlazes").getAsBoolean();
		if (obj.has("sendCompleteMessage")) sendCompleteMessage = obj.get("sendCompleteMessage").getAsBoolean();
		if (obj.has("hideBlazes")) hideBlazes = obj.get("hideBlazes").getAsBoolean();
		applyHideRule();
	}

	@Override
	public String getDescription() {
		return "Solves the F4 Higher/Lower Blaze puzzle: reads each blaze's HP and highlights the next one to kill in the right order.";
	}
}
