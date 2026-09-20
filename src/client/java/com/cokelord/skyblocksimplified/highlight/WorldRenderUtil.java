package com.cokelord.skyblocksimplified.highlight;

import com.cokelord.skyblocksimplified.gui.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * World-space marker drawing (boxes/lines/tracers/text/"beacons") for dungeon ESP-style features —
 * the generalized version of the technique {@link HighlightBoxRenderer} already uses for entity
 * highlighting, extended to arbitrary AABBs/points (blocks, computed positions) rather than just entities.
 *
 * <p>Ported from the intent of Odin's {@code RenderUtils.kt}, but not its mechanism: Odin issues real
 * depth-tested 3D geometry via a raw {@code VertexConsumer} obtained mid-frame during the world render
 * pass. This MC version's render pipeline doesn't hand mod code that (confirmed while building
 * {@link WorldToScreen}: {@code LevelRenderEvents.END_MAIN} only exposes the already-captured camera
 * matrices, not a drawable consumer) — so, like {@code HighlightBoxRenderer}, everything here is
 * screen-space: world positions are projected via {@link WorldToScreen#project} and drawn as ordinary 2D
 * primitives on the HUD layer. There is no equivalent of Odin's real depth-tested (GPU Z-buffer) ESP mode,
 * so {@link #drawWireBox}/{@link #drawFilledBox} still draw straight through walls unconditionally — that's
 * still the deliberate Phase 1 tradeoff for boxes. The line-drawing methods ({@link #drawLine} and everything
 * built on it — {@link #drawGroundSquare}, {@link #drawTracer}, {@link #drawBeacon}) are the one exception,
 * per user request: each endpoint is checked with a CPU-side raycast (see {@link #isOccluded}) against both
 * blocks and other players, and the whole line is skipped if either end isn't visible from the camera — an
 * approximation of real depth occlusion (visibility is decided per-endpoint, not per-pixel like a real
 * Z-buffer would), but correctly hides a line/square whose endpoint is genuinely behind a wall or a
 * teammate instead of drawing straight through them.
 *
 * <p>This is a plain static utility, not a registry — each feature that wants to draw calls these methods
 * from its own {@code HudElementRegistry.addLast} callback (same pattern {@code CroesusChestOverlayFeature}
 * already uses for its own render hook), passing the {@link GuiGraphicsExtractor} that callback receives.
 */
public final class WorldRenderUtil {
	public enum RenderStyle { OUTLINE, FILLED, FILLED_OUTLINE }

	private WorldRenderUtil() {}

	/** Projects an AABB's 8 corners and returns their 2D screen-space bounding rect {left, top, right,
	 *  bottom}, or null if every corner is behind the camera (nothing to draw). */
	private static int[] projectBoundingRect(AABB box) {
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		boolean any = false;
		for (int i = 0; i < 8; i++) {
			double x = (i & 1) == 0 ? box.minX : box.maxX;
			double y = (i & 2) == 0 ? box.minY : box.maxY;
			double z = (i & 4) == 0 ? box.minZ : box.maxZ;
			WorldToScreen.ScreenPoint p = WorldToScreen.project(new Vec3(x, y, z));
			if (p.behindCamera()) continue;
			any = true;
			minX = Math.min(minX, p.x());
			maxX = Math.max(maxX, p.x());
			minY = Math.min(minY, p.y());
			maxY = Math.max(maxY, p.y());
		}
		if (!any) return null;
		return new int[]{Math.round(minX), Math.round(minY), Math.round(maxX), Math.round(maxY)};
	}

	public static void drawWireBox(GuiGraphicsExtractor graphics, AABB box, int color, int thickness) {
		int[] rect = projectBoundingRect(box);
		if (rect == null) return;
		int left = rect[0], top = rect[1], right = rect[2], bottom = rect[3];
		int t = Math.max(1, thickness);
		RenderUtil.fillRounded(graphics, left, top, right, top + t, 0, color);
		RenderUtil.fillRounded(graphics, left, bottom - t, right, bottom, 0, color);
		RenderUtil.fillRounded(graphics, left, top, left + t, bottom, 0, color);
		RenderUtil.fillRounded(graphics, right - t, top, right, bottom, 0, color);
	}

	public static void drawFilledBox(GuiGraphicsExtractor graphics, AABB box, int color, int fillOpacityPercent) {
		int[] rect = projectBoundingRect(box);
		if (rect == null) return;
		int alpha = Math.round(Math.max(0, Math.min(100, fillOpacityPercent)) / 100f * 255f);
		int fill = (alpha << 24) | (color & 0xFFFFFF);
		graphics.fill(rect[0], rect[1], rect[2], rect[3], fill);
	}

	/** {@code style}: OUTLINE = border only, FILLED = solid fill only, FILLED_OUTLINE = both (fill at
	 *  half the given opacity, matching Odin's "Filled Outline" style). Mirrors the render-style selector
	 *  reused by nearly every ported box-drawing dungeon feature. */
	public static void drawStyledBox(GuiGraphicsExtractor graphics, AABB box, int color, RenderStyle style, int thickness, int fillOpacityPercent) {
		if (style == RenderStyle.FILLED || style == RenderStyle.FILLED_OUTLINE) {
			int opacity = style == RenderStyle.FILLED_OUTLINE ? fillOpacityPercent / 2 : fillOpacityPercent;
			drawFilledBox(graphics, box, color, opacity);
		}
		if (style == RenderStyle.OUTLINE || style == RenderStyle.FILLED_OUTLINE) {
			drawWireBox(graphics, box, color, thickness);
		}
	}

	/** Stepped-square line between two projected screen points — same technique
	 *  {@code HighlightBoxRenderer}'s tracer already uses, generalized to arbitrary endpoints instead of
	 *  always starting at the crosshair. Returns without drawing if either point is behind the camera or
	 *  occluded (see {@link #isOccluded}). */
	public static void drawLine(GuiGraphicsExtractor graphics, Vec3 from, Vec3 to, int color, int thickness) {
		drawLine(graphics, from, to, color, thickness, true);
	}

	/** {@code checkHandOcclusion}: false skips {@link #isBehindHandModel} for both endpoints — real bug found
	 *  (per user report, Mage Beam — "it doesnt show up sometimes when im too close to the mob"): that check
	 *  was built and tuned for a small STATIC marker (Positional Messages) that really could sit directly
	 *  behind the held item, using a deliberately generous zone (up to half the screen width, 40% of the
	 *  height) since hiding a small icon a little too eagerly is harmless. A beam LINE is different — its
	 *  endpoint being a target close to the player routinely projects into that same broad corner just from
	 *  ordinary close-range perspective, with no real hand model anywhere near it, and unlike a static marker
	 *  the whole line vanishes even though only a small fraction of it (if any) is actually behind the hand. */
	public static void drawLine(GuiGraphicsExtractor graphics, Vec3 from, Vec3 to, int color, int thickness, boolean checkHandOcclusion) {
		WorldToScreen.ScreenPoint a = WorldToScreen.project(from);
		WorldToScreen.ScreenPoint b = WorldToScreen.project(to);
		if (a.behindCamera() || b.behindCamera()) return;
		if (isOccluded(from) || isOccluded(to)) return;
		if (checkHandOcclusion && (isBehindHandModel(a) || isBehindHandModel(b))) return;
		queueLine(from, to, color, thickness);
	}

	/** True if a straight line from the camera to {@code point} is blocked before reaching it — either by
	 *  a block (a real raycast via {@link ClipContext}, VISUAL shape so glass/leaves/etc. block the same way
	 *  they'd block your actual view) or by another player's hitbox standing in the way. Per user request
	 *  ("not render through walls and through players"). Cheap enough to run per line-endpoint per frame:
	 *  these are always short, single-room dungeon-scale rays, not open-world distances.
	 *
	 *  <p>Real bug found in the first version of this: it iterated {@code Level#entitiesForRendering()} and
	 *  read each entity's raw {@code getBoundingBox()} — that's the entity's LOGICAL (last-tick) position,
	 *  up to a full tick stale versus where it's actually drawn on screen this frame, so a moving teammate
	 *  routinely wasn't where the check thought they were. {@link WorldToScreen#getPlayerBoxes()} instead
	 *  reuses the exact interpolated {@code EntityRenderState} positions vanilla itself just used to draw
	 *  those players this frame, eliminating the mismatch entirely.
	 *
	 *  <p>What this still can't catch: the local player's own first-person hand/held item. That's not real
	 *  world geometry with a position at all — it's a separate view-model draw ({@code ItemInHandRenderer})
	 *  layered on top of the 3D scene, so there's nothing for a raycast (or any position-based check) to hit.
	 *  Genuinely occluding behind it would need real per-pixel depth-buffer sampling, which (per this class's
	 *  own doc comment) isn't available to mod code in this render pipeline. */
	private static boolean isOccluded(Vec3 point) {
		Vec3 camera = WorldToScreen.getCameraPos();
		if (camera == null) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return false;
		ClipContext ctx = new ClipContext(camera, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player);
		net.minecraft.world.phys.HitResult hit = mc.level.clipIncludingBorder(ctx);
		// Real bug found (per user report — "teleport maze tracer still isn't working", and this affects
		// every caller whose target point sits at or inside a solid block, e.g. a puzzle pad or a beam
		// lantern): a ray aimed AT a solid block's own center inevitably clips that same block's own surface
		// just before reaching it, so the target was ALWAYS reported as occluded by itself, independent of
		// camera angle, walls, or the Y-offset tweaks tried in earlier rounds. Reaching the destination
		// block's own surface isn't real occlusion — only a hit on a DIFFERENT block, actually standing
		// between the camera and the point, counts.
		if (hit.getType() == HitResult.Type.BLOCK && hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
				&& !blockHit.getBlockPos().equals(net.minecraft.core.BlockPos.containing(point))) {
			return true;
		}
		for (AABB box : WorldToScreen.getPlayerBoxes()) {
			if (box.clip(camera, point).isPresent()) return true;
		}
		return false;
	}

	/** Per user report ("the box/text still renders through my own arm... it looks kind of dumb"): this
	 *  mod's world-space overlays are plain 2D HUD drawings composited on top of the entire 3D scene, so
	 *  nothing here has real per-pixel depth to compare against the first-person hand/held-item view-model.
	 *  A genuine fix (reading the GPU depth buffer back to the CPU) turns out to be technically reachable —
	 *  {@code GameRenderer.mainRenderTarget().getDepthTextureView()} plus {@code
	 *  CommandEncoder.copyTextureToBuffer} are real, confirmed-present APIs (verified by decompiling
	 *  vanilla's own {@code DebugCrosshairRenderer}, which reads that exact depth attachment) — but getting
	 *  the destination texture's usage flags and raw depth-format byte layout right needs live in-game
	 *  testing against the real GPU/driver to verify, not something to ship blind: a wrong assumption there
	 *  risks a full client crash for every user, for what's ultimately a cosmetic overlap. Approximated
	 *  instead with a much cheaper, safe screen-space heuristic — the hand/held-item view-model always
	 *  renders in roughly the same bottom corner of the screen (main-hand side, or off-hand's opposite side
	 *  if it's holding something), regardless of what's actually behind it, so skipping the draw when a
	 *  point projects into that same region in first person is a real (if approximate, not pixel-perfect)
	 *  improvement over always drawing on top of it unconditionally. */
	private static boolean isBehindHandModel(WorldToScreen.ScreenPoint point) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson()) return false;
		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		boolean mainHandRight = mc.options.mainHand().get() == net.minecraft.world.entity.HumanoidArm.RIGHT;
		if (isInHandZone(point, width, height, mainHandRight)) return true;
		return !mc.player.getOffhandItem().isEmpty() && isInHandZone(point, width, height, !mainHandRight);
	}

	// Real gap found (per user report — "the occlusion for hiding the positional messages behind the arm
	// looks really stupid when i use item animations to make the item smaller"): this zone was always the
	// same fixed fraction of the screen regardless of the actual held item's rendered size — Item Animations'
	// own "Scale" setting (ItemInHandRendererMixin's poseStack.scale(...)) can shrink the item well below its
	// real footprint, but the occlusion zone never shrank to match, hiding content over a much bigger area
	// than the now-small item actually covers. Scaled the zone's extent from the same bottom-corner anchor
	// the item itself scales from, so a smaller item hides proportionally less screen, and a larger one
	// (Item Animations also allows scaling UP) hides proportionally more.
	private static float heldItemScale() {
		return com.cokelord.skyblocksimplified.feature.FeatureRegistry.get("item_animations")
			instanceof com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature f && f.isEnabled()
			? f.getScale() : 1f;
	}

	private static boolean isInHandZone(WorldToScreen.ScreenPoint point, int width, int height, boolean rightSide) {
		float scale = heldItemScale();
		float spanX = width * 0.5f * scale;
		float spanY = height * 0.4f * scale;
		float minX = rightSide ? width - spanX : 0f;
		float maxX = rightSide ? width : spanX;
		float minY = height - spanY;
		return point.x() >= minX && point.x() <= maxX && point.y() >= minY;
	}

	/** A flat square sitting on the ground at {@code center}'s Y level, {@code halfSize} out from center in
	 *  every horizontal direction — per user request ("build a square that shows the range on the ground"),
	 *  a proper world-space range indicator instead of a wire box. Real bug this replaces: drawWireBox
	 *  derives its rectangle from the on-screen bounding box of the AABB's 8 PROJECTED corners
	 *  (projectBoundingRect) — that's fundamentally a flat 2D screen rectangle no matter what 3D shape goes
	 *  in, since it only ever keeps each corner's min/max screen X/Y and throws away which corner connects
	 *  to which. Every edge here is instead drawn independently via {@link #drawLine} (each endpoint
	 *  projected on its own, connected by its own screen-space line) — the same technique that already
	 *  gives Positional Messages' own tracers/lines correct perspective, so the square actually foreshortens
	 *  and rotates like a real object in the world as the camera moves, instead of staying a flat overlay. */
	public static void drawGroundSquare(GuiGraphicsExtractor graphics, Vec3 center, double halfSize, int color, int thickness) {
		Vec3 a = center.add(-halfSize, 0, -halfSize);
		Vec3 b = center.add(halfSize, 0, -halfSize);
		Vec3 c = center.add(halfSize, 0, halfSize);
		Vec3 d = center.add(-halfSize, 0, halfSize);
		drawLine(graphics, a, b, color, thickness);
		drawLine(graphics, b, c, color, thickness);
		drawLine(graphics, c, d, color, thickness);
		drawLine(graphics, d, a, color, thickness);
	}

	// Not a real curve — same straight-segment approach as drawGroundSquare, just more of them arranged in
	// a ring. 32 segments (a fill() call each, via drawLine) reads as a smooth circle at normal dungeon-room
	// viewing distances without needing anywhere near "a billion" — per user request for a circle option
	// that "wouldn't lag the entire game", not literal smoothness.
	private static final int CIRCLE_SEGMENTS = 32;

	/** A ring on the ground at {@code center}'s Y level, {@code radius} out from center — the round
	 *  alternative to {@link #drawGroundSquare}, per user request ("allow the user to change the render
	 *  mode... to a circle if they want to... doesn't have to be a perfectly smooth unoptimized circle").
	 *  Each segment is its own independently-projected world-space line, same as the square's edges, so it
	 *  foreshortens/rotates correctly with the camera and gets the same wall/player occlusion for free. */
	public static void drawGroundCircle(GuiGraphicsExtractor graphics, Vec3 center, double radius, int color, int thickness) {
		// i <= CIRCLE_SEGMENTS (not <): the last point (angle = 2*PI) lands back on the first point (angle
		// 0), which closes the ring's final segment without needing to special-case it separately.
		Vec3 prev = null;
		for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
			double angle = 2 * Math.PI * i / CIRCLE_SEGMENTS;
			Vec3 point = center.add(radius * Math.cos(angle), 0, radius * Math.sin(angle));
			if (prev != null) drawLine(graphics, prev, point, color, thickness);
			prev = point;
		}
	}

	/** Line from the crosshair (screen center) to a projected world position — occluded by walls/players/
	 *  the held item, same as every other line in this class (see the class doc comment). */
	public static void drawTracer(GuiGraphicsExtractor graphics, Vec3 to, int color, int thickness) {
		drawTracer(graphics, to, color, thickness, true);
	}

	/** Same tracer, but {@code checkOcclusion}: false skips both the wall/player raycast AND the held-item
	 *  zone check — per user request (Dungeon Routes: "the tracers dont render through walls... it should
	 *  render through walls"), a specific feature can opt into an always-visible tracer without changing the
	 *  default occlusion behavior every other caller (Positional Messages, Mage Beam, Boss Guide's own
	 *  waypoint tracer) still deliberately relies on. */
	public static void drawTracer(GuiGraphicsExtractor graphics, Vec3 to, int color, int thickness, boolean checkOcclusion) {
		WorldToScreen.ScreenPoint b = WorldToScreen.project(to);
		if (b.behindCamera()) return;
		if (checkOcclusion && (isOccluded(to) || isBehindHandModel(b))) return;
		// Per user report ("The tracer was not an issue, revert the tracers as they worked"): 1.1.6's
		// GPU-batched queueLine path (see its own doc comment below) drew tracers as a real one-frame-
		// deferred 3D line from the camera's eye through World3DRenderer instead of this immediate 2D
		// screen-space rect — that changed the tracer's actual behavior enough to be a regression, so this
		// draws straight to the crosshair pixel again, same as before 1.1.6. Every other line in this class
		// (drawLine — Mage Beam, Positional Messages, Secrets, non-waypoint Dungeon Routes lines) keeps the
		// GPU-batched path since only the tracer was reported broken.
		Minecraft mc = Minecraft.getInstance();
		float startX = mc.getWindow().getGuiScaledWidth() / 2f;
		float startY = mc.getWindow().getGuiScaledHeight() / 2f;
		if (!Float.isFinite(startX) || !Float.isFinite(startY) || !Float.isFinite(b.x()) || !Float.isFinite(b.y())) return;
		RenderUtil.drawLine(graphics, startX, startY, b.x(), b.y(), thickness, color);
	}

	// Real lag source found (per user report, this round — "check skyhanni, they have pathfinding lines in
	// like every feature... its a bit laggy though"): every line drawn through this class used to become its
	// own separate screen-space rotated-rect fill() call (see the removed drawScreenLine, and the "more lag
	// zoomed in" fix that led to it) — a real, if O(1)-per-call, CPU-side 2D draw, but still N full
	// GuiGraphics.fill() calls (matrix push/rotate/translate + its own buffer submission) for N lines drawn
	// this frame, with ZERO batching between them. SkyHanni's own changelog confirms they moved their
	// LineDrawer to real "vertex-based rendering" for exactly this reason — and this codebase already has
	// that exact technique proven working, in {@link World3DRenderer} (real GPU line vertices via a shared
	// StagedVertexBuffer, where consecutive same-pipeline draws append into ONE GPU draw call instead of a
	// separate one each — see its own getBuffer()). Dungeon Routes' A* waypoint line already draws through
	// World3DRenderer.drawLineThroughWalls directly and gets this for free; every OTHER line in this codebet
	// (Mage Beam, Positional Messages, tracers, Secrets, non-waypoint Dungeon Routes lines — anything going
	// through THIS class) still went through the slower per-call 2D path.
	//
	// Fixed by queuing: every drawLine/drawTracer call still runs its existing occlusion/hand-model checks
	// synchronously (unchanged — same decision, same timing, same behavior), but instead of drawing
	// immediately it appends the survivors' real world positions to {@link #pendingLines}. A single {@link
	// World3DRenderer#addRenderCallback} (registered once, lazily) drains that list during the NEXT level-
	// render pass and draws every queued line as real batched GPU geometry via
	// {@code World3DRenderer.drawLineThroughWalls} — the same NO_DEPTH pipeline this class's own lines
	// already conceptually use (occlusion is this class's own CPU raycast, not the GPU depth buffer; see the
	// class doc comment), so behavior doesn't change, only how the pixels actually get drawn. This defers the
	// real draw by exactly one rendered frame (world pass runs before the HUD pass that queues these, so
	// queued-this-frame lines draw next-frame) — at normal framerates that's a few milliseconds, not
	// something a "line pointing toward a dungeon objective" gesture could ever read as delayed.
	private record QueuedLine(Vec3 from, Vec3 to, int color, float thickness) {}

	private static final java.util.List<QueuedLine> pendingLines = new java.util.ArrayList<>();
	private static boolean flushRegistered = false;

	private static void queueLine(Vec3 from, Vec3 to, int color, int thickness) {
		if (!Double.isFinite(from.x) || !Double.isFinite(from.y) || !Double.isFinite(from.z)
			|| !Double.isFinite(to.x) || !Double.isFinite(to.y) || !Double.isFinite(to.z)) return;
		ensureFlushRegistered();
		pendingLines.add(new QueuedLine(from, to, color, thickness));
	}

	private static void ensureFlushRegistered() {
		if (flushRegistered) return;
		flushRegistered = true;
		World3DRenderer.addRenderCallback(WorldRenderUtil::flushPendingLines);
	}

	private static void flushPendingLines() {
		if (pendingLines.isEmpty()) return;
		for (QueuedLine line : pendingLines) {
			// Real bug avoided (caught before shipping): World3DRenderer.drawLineThroughWalls reads the
			// caller's real alpha channel same as everything else in that class (see its own vertex()) — must
			// NOT be forced to full opacity here, since Mage Beam's own "Opacity" slider feeds a genuinely
			// variable alpha into the color it passes to drawLine, and forcing it opaque would silently break
			// that setting for every caller relying on translucency.
			World3DRenderer.drawLineThroughWalls(line.from(), line.to(), line.color(), line.thickness());
		}
		pendingLines.clear();
	}

	/** Billboarded label at a world position — a fixed screen-space size (no distance scaling, unlike
	 *  Odin's real 3D-projected text), which reads fine for short dungeon-solver labels at ESP range. */
	public static void drawText(GuiGraphicsExtractor graphics, String text, Vec3 pos, int color) {
		WorldToScreen.ScreenPoint p = WorldToScreen.project(pos);
		// Per user report (Positional Messages' own label "visible through the player") — every other
		// world-space draw in this class already checks isOccluded (walls AND player hitboxes); this one
		// never did, so a label behind a wall or another player stayed fully visible regardless.
		if (p.behindCamera() || isOccluded(pos) || isBehindHandModel(p)) return;
		Minecraft mc = Minecraft.getInstance();
		int width = mc.font.width(text);
		graphics.text(mc.font, text, Math.round(p.x() - width / 2f), Math.round(p.y()), color);
	}

	/** Simplified "beacon": a vertical world-space line from the given position up to a fixed height,
	 *  plus a distance label at the top — not a real beacon-shader beam (no depth-tested beam geometry
	 *  available here, see the class doc), but reads the same way at ESP-relevant distances. */
	public static void drawBeacon(GuiGraphicsExtractor graphics, Vec3 pos, int color, double beamHeight) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Vec3 top = pos.add(0, beamHeight, 0);
		drawLine(graphics, pos, top, color, 2);
		double distance = mc.player.position().distanceTo(pos);
		drawText(graphics, Math.round(distance) + "m", top, color | 0xFF000000);
	}
}
