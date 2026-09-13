package com.cokelord.skyblocksimplified.highlight;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the screen-space box for any highlighted entity — either a MobHighlightFeature-owned rule (using
 * that feature's own thickness/fill/tracer/color settings) or, via a generic fallback, any other
 * MobHighlightRegistry rule with no owning Feature (e.g. PlayerGlowFeature's party-highlight rules, which
 * register several dynamically-colored rules rather than one rule per Feature — see the fallback branch in
 * {@link #renderInner}). Every MobHighlightRegistry consumer renders through this one 2D path now — the 3D
 * real-model glow-outline pass (HighlightRenderModeUtil/EntityRendererMixin) is unused by anything in this
 * codebase as of the fix described in PlayerGlowFeature's own class doc comment, kept only as available
 * infrastructure for a hypothetical future highlight that specifically wants it. Always-on infra registered
 * once at client init, the same pattern as
 * DungeonBlockDetector/PestPlotTracker: it reads whichever MobHighlightRegistry rules are currently
 * active rather than each highlight feature managing its own render hook.
 *
 * Bounded to a 48-block radius specifically to keep this cheap (per user request): a handful of nearby
 * entities checked against already-registered Predicate<Entity> rules once per HUD frame is negligible —
 * this does not scan the whole loaded world, and draws nothing at all when no highlight rule is active.
 */
public final class HighlightBoxRenderer {
	private static final double RANGE = 48.0;
	// Real Hypixel pest entities (and some other small custom mobs) have a genuinely tiny programmatic
	// hitbox relative to their visual model — inflated in WORLD SPACE up to this minimum (in blocks) before
	// projecting, not clamped in pixel space after projecting (the old approach). A flat pixel-space
	// minimum has no relationship to FOV at all, so when the rest of the screen visibly grows on zooming
	// in, a fixed-pixel box does not grow with it — making it look disproportionately smaller by
	// comparison the more you zoom in, which is the actual "squares get smaller when I zoom/lower FOV"
	// bug. Inflating the real AABB and projecting THAT through the same FOV-aware projection matrix
	// everything else uses scales correctly with both distance and FOV like a real object would. 0.9 reads
	// as close to "a full block" per user report ("pests are pretty big, basically a block").
	private static final double MIN_WORLD_SIZE = 0.9;
	// Per-rule override for the (width, height) floor above, for mobs where the generic pest-sized cube is
	// visibly wrong. Zealots are a confirmed real vanilla Enderman under the hood (per the community-
	// maintained Hypixel Skyblock wiki: "Zealots are a type of Enderman... found exclusively in the
	// Dragon's Nest") — real vanilla Enderman dimensions (0.6 wide, 2.9 tall) used directly rather than a
	// guess, since a 0.9-tall floor badly undersized their real ~3-block-tall model.
	private static final java.util.Map<String, double[]> RULE_MIN_SIZE_OVERRIDE = java.util.Map.of(
		"zealot_highlight", new double[]{0.6, 2.9}
	);
	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "highlight_box_renderer");
	private static boolean registered = false;

	// Real bug found (per user report — "The 3d teammate highlight does not work. Its still 2d even on 3d
	// mode"): this used to cache just the ruleId and re-derive everything else (owning feature, color) via
	// FeatureRegistry.get(ruleId) at render time — which only ever works when the rule id IS a real,
	// registered Feature id. HighlightPartyMembersFeature's "Color by Role" mode registers 5 synthetic rule
	// ids instead (one per DungeonClass) that were never going to resolve that way, so those rules could
	// never pick up the owning feature's Render Mode (or thickness/fill/tracer settings), always falling
	// through to the generic 2D-only fallback. Caching the resolved MobHighlightRegistry.Rule itself — set
	// explicitly by whoever registers it, see that record's own doc comment — fixes this for every such
	// rule, not just this one feature's.
	private record Matched(Entity entity, String ruleId, MobHighlightRegistry.Rule rule) {}
	// Perf: the entity scan + per-entity rule matching used to run every RENDER frame (this is always-on
	// infra with no framerate cap) — on a busy dungeon room with dozens of nearby entities and several
	// registered highlight rules, that's a full 48-block getEntities() query plus a full rule-predicate
	// sweep per entity, potentially hundreds of times per second at a high framerate. Entity positions only
	// actually change once per TICK (20/sec) server-side anyway, so re-scanning that fast bought nothing —
	// moved the scan+match off the render path, cached here, and the render path (still per-frame, since
	// the camera genuinely does move every frame) just iterates this small cached list and projects/draws.
	private static List<Matched> cachedMatches = List.of();
	// Real-time-based instead of tied to ClientTickEvents — lets MobHighlightFeature's shared
	// "High Update Rate" toggle (see that class's own doc comment) simply halve this interval instead of
	// needing a second tick-registration path. Baseline matches one real server tick (50ms); the toggle
	// halves it to 25ms (~40/sec) — per user request ("twice as much... noticeable but not huge") rather
	// than a full per-frame rescan, which is exactly the cost this caching was added to eliminate.
	private static long lastScanAtMs = 0L;
	private static final long BASE_SCAN_INTERVAL_MS = 50;

	private HighlightBoxRenderer() {}

	public static void register() {
		if (registered) return;
		registered = true;
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, HUD_ID, HighlightBoxRenderer::render);
		World3DRenderer.addRenderCallback(HighlightBoxRenderer::render3D);
	}

	private static void scanIfDue(Minecraft mc) {
		long interval = MobHighlightFeature.isHighFrequencyPolling() ? BASE_SCAN_INTERVAL_MS / 2 : BASE_SCAN_INTERVAL_MS;
		long now = System.currentTimeMillis();
		if (now - lastScanAtMs < interval) return;
		lastScanAtMs = now;

		if (mc.level == null || mc.player == null || MobHighlightRegistry.ruleIds().isEmpty()) {
			if (!cachedMatches.isEmpty()) cachedMatches = List.of();
			return;
		}
		AABB area = mc.player.getBoundingBox().inflate(RANGE);
		List<Matched> matches = new ArrayList<>();
		for (Entity entity : mc.level.getEntities(mc.player, area, e -> true)) {
			var entry = MobHighlightRegistry.getMatchedEntry(entity);
			if (entry != null) matches.add(new Matched(entity, entry.getKey(), entry.getValue()));
		}
		cachedMatches = matches;
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		// Wrapped in try-catch — this is always-on infra (registered once at startup, not gated behind any
		// single feature toggle), so it runs every frame any highlight rule at all is active, on real
		// per-entity data. Same missing-isolation gap found and fixed at several other HUD render callbacks
		// in this same investigation: an uncaught throw here — e.g. from a genuinely malformed/edge-case
		// entity in the per-frame scan — risks taking out every OTHER HUD widget's render for that frame too.
		try {
			// Real free improvement (per user report — "the entity highlight boxes are kind of laggy"): the
			// box's drawn position used to come straight from entity.getBoundingBox(), the entity's raw
			// LOGICAL position — which only actually changes once per server tick (20/sec), unlike the real
			// mob MODEL vanilla renders, which smoothly interpolates between ticks using the frame's partial-
			// tick value (Entity.getPosition(partialTick), the exact same lerp vanilla's own renderer uses).
			// The highlight box was never doing that interpolation at all, so it visibly snapped once per
			// tick while the mob it's drawn around glided smoothly — costs nothing extra (the interpolated
			// position is already sitting on the entity object) and isn't gated behind the polling-rate
			// toggle, since it's a strict improvement with no frame cost of its own.
			float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
			renderInner(graphics, partialTick);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Highlight box render failed, skipping this frame", e);
		}
	}

	/** {@link World3DRenderer} render callback (registered once, see {@link #register}) — draws the real
	 *  world-space box for every currently-matched entity whose owning feature is set to
	 *  {@link MobHighlightFeature.RenderMode#WIRE_3D} or {@link MobHighlightFeature.RenderMode#FULL_3D}.
	 *  Runs during the level-render pass, separately from {@link #render}'s HUD pass, but reuses the same
	 *  {@link #cachedMatches} scan (calling {@link #scanIfDue} itself too, since this callback can fire
	 *  before or after the HUD one within a frame). */
	private static void render3D() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
			scanIfDue(mc);
			if (cachedMatches.isEmpty()) return;
			for (Matched matched : cachedMatches) {
				Entity entity = matched.entity();
				if (!entity.isAlive()) continue;
				MobHighlightFeature feature = matched.rule().owner();
				MobHighlightFeature.RenderMode mode3D = feature != null ? feature.getRenderMode() : null;
				if (mode3D == MobHighlightFeature.RenderMode.WIRE_3D || mode3D == MobHighlightFeature.RenderMode.FULL_3D) {
					// partialTick=1f rather than a real DeltaTracker value (this Runnable callback gets none) —
					// computeInterpolatedBox's lerp delta evaluates to zero at partialTick=1 (current tick
					// position, same as entity.position()), so this draws the raw, tick-quantized box with no
					// interpolation. Matches every other World3DRenderer consumer in this codebase (Door
					// Highlight, Boss Guide markers, etc.), none of which interpolate entity motion either.
					drawBox3D(entity, feature, matched.rule().color(), matched.ruleId(), 1f);
				}
			}
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Highlight 3D box render failed, skipping this frame", e);
		}
	}

	private static void renderInner(GuiGraphicsExtractor graphics, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		// Only a real screen (inventory, our own mod menu, etc.) should hide this — chat only covers a
		// small bottom-left corner and shouldn't blank out highlights on mobs elsewhere on screen.
		if (mc.level == null || mc.player == null || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		scanIfDue(mc);
		if (cachedMatches.isEmpty()) return;

		for (Matched matched : cachedMatches) {
			Entity entity = matched.entity();
			if (!entity.isAlive()) continue;
			MobHighlightFeature feature = matched.rule().owner();
			int color = matched.rule().color();
			if (feature != null) {
				MobHighlightFeature.RenderMode mode = feature.getRenderMode();
				if (mode == MobHighlightFeature.RenderMode.WIRE_3D || mode == MobHighlightFeature.RenderMode.FULL_3D) {
					// The actual box is drawn by render3D (real world-space geometry, drawn during the level
					// render pass so it can be depth-tested/occluded like a real object) — this 2D pass still
					// owns the tracer line, since that's inherently a 2D screen-space overlay (crosshair to
					// box center) with no 3D equivalent in World3DRenderer.
					if (feature.isTracersEnabled()) drawTracerFor(graphics, entity, feature, color, matched.ruleId(), partialTick);
				} else {
					boolean full = mode == MobHighlightFeature.RenderMode.FULL_2D;
					drawBox(graphics, entity, feature, color, full, matched.ruleId(), partialTick);
				}
			} else {
				// Generic fallback for a MobHighlightRegistry rule with no owning MobHighlightFeature — e.g.
				// PlayerGlowFeature's party-highlight rules, which register several dynamically-colored rules
				// under synthetic ids rather than one rule per Feature, so they don't fit the lookup above.
				// Per user report ("The highlight should not make them glow, it should highlight them like a
				// starred mob (2d box)"), draws the same plain outline-only 2D box every other highlight in
				// this codebase uses, in the rule's own registered color.
				drawSimpleBox(graphics, entity, color, partialTick);
			}
		}
	}

	/** Same projection/outline logic as {@link #drawBox}, stripped down for a MobHighlightRegistry rule
	 *  with no owning {@link MobHighlightFeature} to read thickness/fill/tracer settings from — a fixed
	 *  default thickness, outline-only, no fill/tracers/per-rule Y offset. See the fallback branch above
	 *  in {@link #renderInner} for when this is used. */
	private static void drawSimpleBox(GuiGraphicsExtractor graphics, Entity entity, int color, float partialTick) {
		net.minecraft.world.phys.Vec3 interpolated = entity.getPosition(partialTick).subtract(entity.position());
		AABB box = inflateToMinimumSize(entity.getBoundingBox(), null);
		box = box.move(interpolated.x, interpolated.y + Y_OFFSET, interpolated.z);
		WorldToScreen.ScreenPoint center = WorldToScreen.project(box.getCenter());
		if (center.behindCamera()) return;
		Minecraft mc = Minecraft.getInstance();
		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();
		if (center.x() < -64 || center.x() > screenWidth + 64 || center.y() < -64 || center.y() > screenHeight + 64) return;

		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		boolean any = false;
		for (int i = 0; i < 8; i++) {
			double x = (i & 1) == 0 ? box.minX : box.maxX;
			double y = (i & 2) == 0 ? box.minY : box.maxY;
			double z = (i & 4) == 0 ? box.minZ : box.maxZ;
			WorldToScreen.ScreenPoint p = WorldToScreen.project(new net.minecraft.world.phys.Vec3(x, y, z));
			if (p.behindCamera()) continue;
			any = true;
			minX = Math.min(minX, p.x());
			maxX = Math.max(maxX, p.x());
			minY = Math.min(minY, p.y());
			maxY = Math.max(maxY, p.y());
		}
		if (!any) return;
		int left = Math.round(minX);
		int right = Math.round(maxX);
		int top = Math.round(minY);
		int bottom = Math.round(maxY);
		int thickness = Math.max(1, Math.round(2 * center.scale()));
		RenderUtil.fillRounded(graphics, left, top, right, top + thickness, 0, color);
		RenderUtil.fillRounded(graphics, left, bottom - thickness, right, bottom, 0, color);
		RenderUtil.fillRounded(graphics, left, top, left + thickness, bottom, 0, color);
		RenderUtil.fillRounded(graphics, right - thickness, top, right, bottom, 0, color);
	}

	// Per user report, the box sat noticeably too high above every highlighted mob — shifted down in world
	// space (not pixel space, so it stays correct at any distance/FOV) before projecting. -0.5 overshot,
	// -0.3 still read as slightly too far down; raised back up by another 0.1 to -0.2.
	private static final double Y_OFFSET = -0.2;
	// Real vanilla Enderman under the hood (per the Hypixel Skyblock wiki: Zealots, and the Voidgloom
	// Seraph slayer boss/its miniboss — see DisableEndermanDeathAnimationFeature's own doc comment for the
	// same Enderman-identity confirmation) render their model noticeably higher above their hitbox than
	// every other highlighted mob — the shared Y_OFFSET above wasn't enough for these specifically. Per
	// user report ("about 2 blocks lower"), rather than tuning the shared offset (which would then be
	// wrong for every non-Enderman mob again) these three get their own, much larger downward shift.
	private static final java.util.Set<String> ENDERMAN_RULE_IDS = java.util.Set.of(
		"zealot_highlight", "voidgloom_miniboss_highlight", "voidgloom_boss_highlight"
	);
	private static final double ENDERMAN_Y_OFFSET = Y_OFFSET - 2.0;
	// Per live in-game testing, Zealot specifically still sat too high even at the shared Enderman offset
	// above (Voidgloom Seraph wasn't reported as off, so that shared value is left alone rather than
	// guessing it needs the same extra nudge) — an additional half-block down just for this one rule.
	private static final double ZEALOT_Y_OFFSET = ENDERMAN_Y_OFFSET - 0.5;
	// Per user report ("a little low, about 0.3 blocks"), starred dungeon mobs specifically sit too far
	// down at the shared offset — raised by 0.3 for this one rule rather than tuning Y_OFFSET itself, same
	// per-rule-override precedent as the Enderman/Zealot cases above. That tuning was done against a real,
	// awakened mob entity (the thing StarredMobHighlightFeature finds BELOW a starred nametag stand).
	private static final double STARRED_MOB_Y_OFFSET = Y_OFFSET + 0.3;

	/** Same per-rule Y-offset tuning + minimum-size inflation + partial-tick interpolation used by every
	 *  render path in this class (2D outline/fill, 2D tracer target, and the 3D world-space box) — shared so
	 *  all three always agree on exactly where "the box" is for a given entity. */
	private static AABB computeInterpolatedBox(Entity entity, String ruleId, float partialTick) {
		double yOffset = "zealot_highlight".equals(ruleId) ? ZEALOT_Y_OFFSET
			: ENDERMAN_RULE_IDS.contains(ruleId) ? ENDERMAN_Y_OFFSET
			: "starred_mob_highlight".equals(ruleId) ? STARRED_MOB_Y_OFFSET : Y_OFFSET;
		// Shift the raw (logical, once-per-tick) bounding box by the same interpolation delta vanilla's own
		// entity renderer applies — see render()'s own doc comment for why this is what actually fixes the
		// "boxes look laggy/snap around" report, not the polling-rate toggle.
		net.minecraft.world.phys.Vec3 interpolated = entity.getPosition(partialTick).subtract(entity.position());
		AABB box = inflateToMinimumSize(entity.getBoundingBox(), RULE_MIN_SIZE_OVERRIDE.get(ruleId));
		return box.move(interpolated.x, interpolated.y + yOffset, interpolated.z);
	}

	private static void drawBox(GuiGraphicsExtractor graphics, Entity entity, MobHighlightFeature feature, int color, boolean full, String ruleId, float partialTick) {
		AABB box = computeInterpolatedBox(entity, ruleId, partialTick);
		WorldToScreen.ScreenPoint center = WorldToScreen.project(box.getCenter());
		if (center.behindCamera()) return;
		Minecraft mc = Minecraft.getInstance();
		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();
		if (center.x() < -64 || center.x() > screenWidth + 64 || center.y() < -64 || center.y() > screenHeight + 64) return;

		// Project all 8 corners of the entity's real bounding box and take the 2D screen-space bounding
		// rect of those — perspective-correct at any distance/FOV, unlike the earlier approach of
		// inflating a single projected point by a flat pixel-per-block heuristic (which produced a
		// box far smaller than the actual mob at most distances).
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		boolean any = false;
		for (int i = 0; i < 8; i++) {
			double x = (i & 1) == 0 ? box.minX : box.maxX;
			double y = (i & 2) == 0 ? box.minY : box.maxY;
			double z = (i & 4) == 0 ? box.minZ : box.maxZ;
			WorldToScreen.ScreenPoint p = WorldToScreen.project(new net.minecraft.world.phys.Vec3(x, y, z));
			if (p.behindCamera()) continue;
			any = true;
			minX = Math.min(minX, p.x());
			maxX = Math.max(maxX, p.x());
			minY = Math.min(minY, p.y());
			maxY = Math.max(maxY, p.y());
		}
		if (!any) return;
		int left = Math.round(minX);
		int right = Math.round(maxX);
		int top = Math.round(minY);
		int bottom = Math.round(maxY);

		if (full) {
			int fillAlpha = Math.round(feature.getFillOpacity() / 100f * 255f);
			// Per user request: an optional local image drawn over the box instead of a flat color, in one
			// of 4 fit modes (see drawCustomImage). getCustomImageTextureId() itself falls back to null
			// (and clears the feature's own broken path) on any load failure, so a missing/corrupted file
			// just silently falls through to the plain flat-color fill below rather than needing a
			// separate error path here.
			net.minecraft.resources.Identifier customImage = feature.isUseCustomImage() ? feature.getCustomImageTextureId() : null;
			if (customImage != null) {
				drawCustomImage(graphics, customImage, feature, left, top, right, bottom, fillAlpha);
			} else {
				int fill = (fillAlpha << 24) | (feature.getFillColor() & 0xFFFFFF);
				graphics.fill(left, top, right, bottom, fill);
			}
		}
		int thickness = Math.max(1, Math.round(feature.getOutlineThickness() * center.scale()));
		int outline = color;
		RenderUtil.fillRounded(graphics, left, top, right, top + thickness, 0, outline);
		RenderUtil.fillRounded(graphics, left, bottom - thickness, right, bottom, 0, outline);
		RenderUtil.fillRounded(graphics, left, top, left + thickness, bottom, 0, outline);
		RenderUtil.fillRounded(graphics, right - thickness, top, right, bottom, 0, outline);

		if (feature.isTracersEnabled()) {
			int tracerAlpha = Math.round(feature.getTracerOpacity() / 100f * 255f);
			int tracerColor = (tracerAlpha << 24) | (outline & 0xFFFFFF);
			drawTracer(graphics, mc, (left + right) / 2f, (top + bottom) / 2f, tracerColor, feature.getTracerThickness());
		}
	}

	/** Real world-space box for {@link MobHighlightFeature.RenderMode#WIRE_3D}/{@link
	 *  MobHighlightFeature.RenderMode#FULL_3D} — always draws the wire outline (feature's own color/
	 *  thickness); the filled interior (feature's fill color/opacity, skipped entirely at 0% opacity rather
	 *  than issuing a fully-transparent draw call) only draws for FULL_3D, matching the OUTLINE_2D/FULL_2D
	 *  split of the 2D modes (per user request — was previously one combined mode that always drew both).
	 *  Occlusion is the feature's own {@link MobHighlightFeature#isOcclusion3D()} subtoggle: true routes
	 *  through {@link World3DRenderer}'s normal (real depth-tested) draw calls, false through its
	 *  ThroughWalls counterparts — see that class's own doc comment for the actual depth-stencil-state
	 *  difference. */
	private static void drawBox3D(Entity entity, MobHighlightFeature feature, int color, String ruleId, float partialTick) {
		AABB box = computeInterpolatedBox(entity, ruleId, partialTick);
		boolean fill = feature.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
		int fillAlpha = fill ? Math.round(feature.getFillOpacity() / 100f * 255f) : 0;
		int fillArgb = (fillAlpha << 24) | (feature.getFillColor() & 0xFFFFFF);
		int outlineArgb = color;
		float lineWidth = feature.getOutlineThickness();
		if (feature.isOcclusion3D()) {
			if (fillAlpha > 0) World3DRenderer.drawFilledBox(box, fillArgb);
			World3DRenderer.drawWireBox(box, outlineArgb, lineWidth);
		} else {
			if (fillAlpha > 0) World3DRenderer.drawFilledBoxThroughWalls(box, fillArgb);
			World3DRenderer.drawWireBoxThroughWalls(box, outlineArgb, lineWidth);
		}
	}

	/** Tracer line for a {@link MobHighlightFeature.RenderMode#WIRE_3D}/{@link
	 *  MobHighlightFeature.RenderMode#FULL_3D} entity — the box itself is drawn by
	 *  {@link #render3D}/{@link #drawBox3D} during the level pass, but the tracer stays a 2D HUD overlay (no
	 *  3D equivalent exists in {@link World3DRenderer}), so it's drawn here from {@link #renderInner} same as
	 *  every other tracer, just targeting the 3D box's own projected center instead of a 2D bounding rect's. */
	private static void drawTracerFor(GuiGraphicsExtractor graphics, Entity entity, MobHighlightFeature feature, int color, String ruleId, float partialTick) {
		AABB box = computeInterpolatedBox(entity, ruleId, partialTick);
		WorldToScreen.ScreenPoint center = WorldToScreen.project(box.getCenter());
		if (center.behindCamera()) return;
		Minecraft mc = Minecraft.getInstance();
		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();
		if (center.x() < -64 || center.x() > screenWidth + 64 || center.y() < -64 || center.y() > screenHeight + 64) return;
		int tracerAlpha = Math.round(feature.getTracerOpacity() / 100f * 255f);
		int tracerColor = (tracerAlpha << 24) | (color & 0xFFFFFF);
		drawTracer(graphics, mc, center.x(), center.y(), tracerColor, feature.getTracerThickness());
	}

	/** The 4 fit modes, matching a Windows desktop-background picker (per user request) — STRETCH is the
	 *  original unconditional behavior, kept as the default so nothing changes for anyone who already had
	 *  an image set before this existed. */
	private static final int MIN_TILE_PX = 16;

	private static void drawCustomImage(GuiGraphicsExtractor graphics, Identifier textureId, MobHighlightFeature feature,
										 int left, int top, int right, int bottom, int fillAlpha) {
		int boxW = right - left, boxH = bottom - top;
		int imgW = feature.getCustomImageWidth(), imgH = feature.getCustomImageHeight();
		if (boxW <= 0 || boxH <= 0 || imgW <= 0 || imgH <= 0) return;
		int tint = (fillAlpha << 24) | 0xFFFFFF;
		var pipeline = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
		switch (feature.getImageFillMode()) {
			case STRETCH -> graphics.blit(pipeline, textureId, left, top, 0f, 0f, boxW, boxH, imgW, imgH, imgW, imgH, tint);
			case FIT -> {
				// Aspect preserved, whole image visible, letterboxed with the flat fill color — the fill
				// color draws first so it only shows in the leftover space around the (smaller) image.
				graphics.fill(left, top, right, bottom, (fillAlpha << 24) | (feature.getFillColor() & 0xFFFFFF));
				float scale = Math.min(boxW / (float) imgW, boxH / (float) imgH);
				int drawW = Math.round(imgW * scale), drawH = Math.round(imgH * scale);
				int dx = left + (boxW - drawW) / 2, dy = top + (boxH - drawH) / 2;
				graphics.blit(pipeline, textureId, dx, dy, 0f, 0f, drawW, drawH, imgW, imgH, imgW, imgH, tint);
			}
			case FILL -> {
				// Aspect preserved, box fully covered, overflow cropped via scissor rather than a computed
				// source sub-region — simpler and avoids any off-by-one in the crop math.
				float scale = Math.max(boxW / (float) imgW, boxH / (float) imgH);
				int drawW = Math.round(imgW * scale), drawH = Math.round(imgH * scale);
				int dx = left - (drawW - boxW) / 2, dy = top - (drawH - boxH) / 2;
				graphics.enableScissor(left, top, right, bottom);
				graphics.blit(pipeline, textureId, dx, dy, 0f, 0f, drawW, drawH, imgW, imgH, imgW, imgH, tint);
				graphics.disableScissor();
			}
			case TILE -> {
				// Native image size, repeated across the box — per user request, "places as many as it
				// can fit," so partial tiles at the box's right/bottom edges are just clipped rather than
				// stretched to fit evenly. A pathologically tiny source image (a few px) would otherwise
				// mean thousands of blit calls for one highlighted entity — scaled up (never down) so no
				// tile dimension goes below MIN_TILE_PX, keeping the draw-call count bounded regardless of
				// the source image's real size.
				float tileScale = Math.max(1f, MIN_TILE_PX / (float) Math.min(imgW, imgH));
				int tileW = Math.round(imgW * tileScale), tileH = Math.round(imgH * tileScale);
				graphics.enableScissor(left, top, right, bottom);
				for (int ty = top; ty < bottom; ty += tileH) {
					for (int tx = left; tx < right; tx += tileW) {
						graphics.blit(pipeline, textureId, tx, ty, 0f, 0f, tileW, tileH, imgW, imgH, imgW, imgH, tint);
					}
				}
				graphics.disableScissor();
			}
		}
	}

	// From the crosshair (real screen center, matching what the player is actually aiming at) to the
	// highlighted box's own center. Previously stamped a chain of stepped squares along the line (a stand-in
	// for a rotated quad, on the mistaken assumption the pose stack didn't support 2D rotation — it does,
	// confirmed by MainScreen's own cog-spin animation using graphics.pose().rotate(radians) the same way).
	// Per user report the stepped version read as visibly blocky rather than a smooth line; a single filled
	// rect drawn once in a rotated/translated pose is both a true straight edge AND cheaper (one draw call
	// instead of up to ~2 per pixel of travel), which is also the actual fix for "make it not lag" — the
	// old version's per-frame cost scaled with on-screen distance to the target, this one doesn't.
	private static void drawTracer(GuiGraphicsExtractor graphics, Minecraft mc, float targetX, float targetY, int color, int thickness) {
		float startX = mc.getWindow().getGuiScaledWidth() / 2f;
		float startY = mc.getWindow().getGuiScaledHeight() / 2f;
		float dx = targetX - startX;
		float dy = targetY - startY;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length < 2f) return;
		int size = Math.max(1, thickness);
		float angle = (float) Math.atan2(dy, dx);
		graphics.pose().pushMatrix();
		graphics.pose().translate(startX, startY);
		graphics.pose().rotate(angle);
		graphics.fill(0, -size / 2, Math.round(length), -size / 2 + size, color);
		graphics.pose().popMatrix();
	}

	private static AABB inflateToMinimumSize(AABB box, double[] sizeOverride) {
		double minWidth = sizeOverride != null ? sizeOverride[0] : MIN_WORLD_SIZE;
		double minHeight = sizeOverride != null ? sizeOverride[1] : MIN_WORLD_SIZE;
		double width = box.maxX - box.minX;
		double height = box.maxY - box.minY;
		double depth = box.maxZ - box.minZ;
		if (width >= minWidth && height >= minHeight && depth >= minWidth) return box;
		double cx = (box.minX + box.maxX) / 2;
		double cz = (box.minZ + box.maxZ) / 2;
		double hx = Math.max(width, minWidth) / 2;
		double hz = Math.max(depth, minWidth) / 2;

		double newMinY, newMaxY;
		if (sizeOverride != null) {
			// Per user report ("esp on zealots is about 1.1 blocks too high up"): a per-mob override height
			// grown SYMMETRICALLY around the real hitbox's own center (the generic-case behavior below) badly
			// misplaces it whenever the real programmatic hitbox is small and sits low/off-center — a real
			// mob's Y position is always its FEET, so anchoring the override height there and growing upward
			// keeps it correctly planted on the ground regardless of how small/oddly-centered the real hitbox
			// this project can't otherwise see actually is.
			newMinY = box.minY;
			newMaxY = box.minY + minHeight;
		} else {
			double cy = (box.minY + box.maxY) / 2;
			double hy = Math.max(height, minHeight) / 2;
			newMinY = cy - hy;
			newMaxY = cy + hy;
		}
		return new AABB(cx - hx, newMinY, cz - hz, cx + hx, newMaxY, cz + hz);
	}
}
