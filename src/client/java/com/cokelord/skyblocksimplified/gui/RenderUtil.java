package com.cokelord.skyblocksimplified.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Shared drawing primitives used by MainScreen and any feature that draws its own HUD (e.g. the
 *  Custom Scoreboard's background/outline). Kept as static methods rather than duplicated per-class. */
public final class RenderUtil {
	private RenderUtil() {}

	/** Real, IMMEDIATE (non-deferred) item tooltip render — draws right now instead of queuing into
	 *  {@code GuiGraphicsExtractor}'s own {@code setTooltipForNextFrame}/{@code extractDeferredElements}
	 *  mechanism. Confirmed via javap on the real MC 26.2 client jar that the deferred queue's own flush
	 *  ({@code extractDeferredElements}) does nothing more than call this exact same real
	 *  {@code graphics.tooltip(Font, List, int, int, ClientTooltipPositioner, Identifier)} overload one
	 *  real frame later — and that {@code setTooltipForNextFrame(Font, ItemStack, int, int)} builds its
	 *  component list via {@code Screen.getTooltipFromItem} + {@code ItemStack.getTooltipImage()}, inserting
	 *  the image component right after the name line (index 0 if the list is otherwise empty, else index 1)
	 *  — replicated here exactly, just called immediately instead of deferred.
	 *
	 *  <p>Real bug found (per user report — "There is still no lore on the storage overlay items"): Storage
	 *  Overlay renders from inside a render hook that fires while the real vanilla container screen's own
	 *  {@code extractRenderState} is cancelled (see {@code ScreenRenderCancelRegistry}) so it can draw its
	 *  own replacement panel — decompiling MC's real tooltip-flush call chain could not conclusively prove
	 *  the deferred queue still gets flushed at a useful point relative to that cancellation, so this bypasses
	 *  the queue entirely rather than trust it. */
	public static void renderItemTooltip(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int mouseX, int mouseY) {
		if (stack.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		List<Component> lines = Screen.getTooltipFromItem(mc, stack);
		List<ClientTooltipComponent> components = new ArrayList<>();
		for (Component line : lines) components.add(ClientTooltipComponent.create(line.getVisualOrderText()));
		stack.getTooltipImage().ifPresent(image ->
			components.add(components.isEmpty() ? 0 : 1, ClientTooltipComponent.create(image)));
		if (components.isEmpty()) return;
		graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
	}

	/** Rounded-corner rectangle fill. The straight top/bottom strip between the corners is one plain
	 *  fill(); each corner's own r×r pixel box is rasterized individually with real 2D supersampled
	 *  coverage (see {@link #circleCoverage}) instead of a per-row scanline inset — a scanline inset is
	 *  mathematically exact along one row's own horizontal cross-section, but a rounded corner's curve
	 *  only gets as many distinct cross-sections as there are rows, which reads as a visible staircase at
	 *  small radii/low GUI Scale no matter how precisely each individual row is computed. Supersampling
	 *  each corner pixel directly against the true circle (16 sub-samples/pixel) fixes that without needing
	 *  a custom shader/render pipeline — per user request, "every single squircle needs to be smooth". */
	public static void fillRounded(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color,
									boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight) {
		fillRounded(graphics, x0, y0, x1, y1, radius, color, roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight, false);
	}

	// Real bug found (per user report — "the toggles are now really rectangly, they used to be more of an
	// ellipse, not a squircle"): a toggle track is drawn as one fillRounded() call with FULL_ROUND (radius
	// clamped to height/2), so its two end-caps ARE the corner boxes — there's no flat straight run between
	// them the way a panel's gentle corner rounding has. A superellipse's flatter sides read as barely
	// perceptible on a large panel's small corner radius, but blown up to a whole end-cap they read as a
	// visibly flat-sided, almost-rectangular end instead of the round pill it used to be. forceCircle lets
	// toggle-track callers opt back into true circular ends regardless of the Squircle Corners setting,
	// while every other fillRounded caller (panels, buttons, swatches) keeps respecting that setting.
	public static void fillRounded(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color,
									boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight,
									boolean forceCircle) {
		int width = x1 - x0;
		int height = y1 - y0;
		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		if (r <= 0) {
			graphics.fill(x0, y0, x1, y1, color);
			return;
		}
		int baseAlpha = (color >>> 24) & 0xFF;
		fillRoundedCorner(graphics, x0, y0, r, 1, 1, roundTopLeft, color, baseAlpha, forceCircle);
		fillRoundedCorner(graphics, x1, y0, r, -1, 1, roundTopRight, color, baseAlpha, forceCircle);
		fillRoundedCorner(graphics, x0, y1, r, 1, -1, roundBottomLeft, color, baseAlpha, forceCircle);
		fillRoundedCorner(graphics, x1, y1, r, -1, -1, roundBottomRight, color, baseAlpha, forceCircle);
		graphics.fill(x0, y0 + r, x1, y1 - r, color);
		graphics.fill(x0 + r, y0, x1 - r, y0 + r, color);
		graphics.fill(x0 + r, y1 - r, x1 - r, y1, color);
	}

	/** True circular-pill fill regardless of the Squircle Corners setting — see the doc comment on the
	 *  forceCircle {@code fillRounded} overload above for why toggle tracks need this instead. */
	public static void fillPill(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
		fillRounded(graphics, x0, y0, x1, y1, radius, color, true, true, true, true, true);
	}

	// Sub-sample grid per boundary pixel — 4x4 -> 8x8 -> this (16x16) across successive user follow-ups
	// ("not really smooth enough" / "not perfectly smooth like I want them"). Only the boundary band of
	// each small corner/circle is ever sampled at this density, so even at 16x16 the extra cost stays
	// negligible redrawn every frame at typical UI radii (well under 16px throughout this mod) — this is a
	// real resolution ceiling, though: past a certain sample density the remaining "not perfectly smooth"
	// is the native/low GUI-scale pixel grid itself, which no amount of supersampling removes without an
	// actual shader-based render pass (see this class's own doc comment on fillRounded).
	private static final int AA_SAMPLES = 16;

	// Real fix (per user report — "the squircles still lag a LOT, going from 140 fps to about 70 in the
	// dungeon routes menu... ive seen mods do smooth edges and squircles without any issues"): the previous
	// per-pixel rewrite (analytic SDF antialiasing) made the MATH itself cheaper, but never touched the real
	// bottleneck — every boundary pixel still issued its own individual graphics.fill(px, py, px+1, py+1,
	// ...) call. Each of those is a full draw call through the render pipeline, and a content-dense screen
	// like Dungeon Routes draws dozens of rounded elements a frame, each with up to 4 corners' worth of
	// boundary-ring pixels — hundreds of individual 1-pixel draw calls a frame, which is exactly the kind of
	// cost GPU draw-call overhead (not raw math) dominates. This is the exact same root cause already found
	// and fixed for the color-picker gradient square (see colorSquareGradientTexture's own doc comment in
	// MainScreen) — bake the shape ONCE into a real texture (a white silhouette whose alpha channel IS the
	// antialiased coverage), then every actual draw is a single textured-quad blit, tinted to the caller's
	// real color via the pipeline's own vertex-color multiply (confirmed already relied on elsewhere in this
	// codebase — see drawIcon's own doc comment). One bake per distinct (radius, shape, pixelated) combo —
	// there are only ever a handful of distinct radii in this mod's whole UI — then zero further CPU math or
	// per-pixel draw calls for the rest of the session.
	//
	// Per user follow-up ("Remove the subtoggle for the squircle corners as they are default and the toggle
	// does nothing"): squircle is no longer conditional on a setting at all — every non-forceCircle rounded
	// corner is always a squircle now. forceCircle (toggle-track pills, see fillPill) still renders a true
	// circle, which is why the cache key still distinguishes the two shapes.
	private static final java.util.Map<Long, net.minecraft.resources.Identifier> cornerTextureCache = new java.util.HashMap<>();

	// Real bug found (per user report — "The squircles are scuffed now. They are missing corners. Bottom
	// left and top right corners are just genuinely missing"): the previous version baked ONE canonical
	// "top-left style" mask and mirrored it at render time for the other 3 orientations via a pose-stack
	// scale(signX, signY). That works fine when BOTH axes flip (scale(-1,-1), used for the bottom-right
	// corner — same as a 180° rotation, which preserves the quad's winding order/determinant) or NEITHER
	// does (top-left, no flip at all) — exactly the two corners that kept working. But a SINGLE-axis flip
	// (scale(-1,1) for top-right, scale(1,-1) for bottom-left) has a NEGATIVE determinant: it reverses the
	// blitted quad's winding order, which the newer 3D-oriented GUI render pipeline (RenderPipelines.
	// GUI_TEXTURED) apparently backface-culls — the quad is still "drawn," just as an invisible back face.
	// Exactly "missing," not distorted, and exactly the two single-axis-flip corners, matching the report
	// precisely. Fixed by baking each of the 4 screen orientations as its OWN texture directly (mirroring
	// which destination pixel each coverage sample writes to at BAKE time, not via any runtime transform) —
	// no pose-stack flip at blit time at all, so there's no winding order to get culled. Still only a
	// handful of small NativeImages total (4 per distinct radius/shape/pixelated combo, baked once and
	// cached), not a per-frame cost.
	private static net.minecraft.resources.Identifier cornerTexture(int r, boolean circle, boolean pixelated, int signX, int signY) {
		long key = ((long) r << 4) | (circle ? 8 : 0) | (pixelated ? 4 : 0) | (signX > 0 ? 2 : 0) | (signY > 0 ? 1 : 0);
		net.minecraft.resources.Identifier cached = cornerTextureCache.get(key);
		if (cached != null) return cached;

		com.mojang.blaze3d.platform.NativeImage image =
			new com.mojang.blaze3d.platform.NativeImage(com.mojang.blaze3d.platform.NativeImage.Format.RGBA, r, r, false);
		double cx = r, cy = r;
		for (int j = 0; j < r; j++) {
			for (int i = 0; i < r; i++) {
				float coverage;
				if (pixelated) {
					if (circle) {
						double dist = Math.hypot(i + 0.5 - cx, j + 0.5 - cy);
						coverage = dist <= r ? 1f : 0f;
					} else {
						double ax = Math.abs(i + 0.5 - cx) / r, ay = Math.abs(j + 0.5 - cy) / r;
						coverage = Math.pow(ax, SQUIRCLE_EXPONENT) + Math.pow(ay, SQUIRCLE_EXPONENT) <= 1.0 ? 1f : 0f;
					}
				} else {
					coverage = circle ? circleCoverage(i, j, cx, cy, r) : squircleCoverage(i, j, cx, cy, r);
				}
				int alpha = Math.round(clamp01(coverage) * 255f);
				// Canonical (i,j) is computed as if this were the "top-left style" mask (transparent near
				// local (0,0), solid near local (r-1,r-1)) — writing it to a mirrored destination pixel per
				// orientation bakes the flip into the texture data itself instead of a render-time transform.
				int destI = signX > 0 ? i : (r - 1 - i);
				int destJ = signY > 0 ? j : (r - 1 - j);
				image.setPixelABGR(destI, destJ, (alpha << 24) | 0xFFFFFF);
			}
		}
		net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath(
			"skyblocksimplified", "corner_mask_" + r + "_" + (circle ? "c" : "s") + (pixelated ? "_px" : "")
				+ "_" + (signX > 0 ? "p" : "n") + (signY > 0 ? "p" : "n"));
		Minecraft.getInstance().getTextureManager().register(id,
			new net.minecraft.client.renderer.texture.DynamicTexture(() -> "sbs-corner-mask", image));
		cornerTextureCache.put(key, id);
		return id;
	}

	/** Draws one r×r corner box, textured (see {@link #cornerTexture}) rather than per-pixel — signX/signY
	 *  say which direction is "into" the rect from (cornerX, cornerY), the box's own 90° outer corner point.
	 *  When this specific corner isn't meant to be rounded, the whole box is just filled solid instead
	 *  (matches the old per-flag behavior, e.g. a panel rounded on top only). */
	private static void fillRoundedCorner(GuiGraphicsExtractor graphics, int cornerX, int cornerY, int r,
										   int signX, int signY, boolean rounded, int color, int baseAlpha, boolean forceCircle) {
		int bx0 = signX > 0 ? cornerX : cornerX - r;
		int by0 = signY > 0 ? cornerY : cornerY - r;
		if (!rounded) {
			graphics.fill(bx0, by0, bx0 + r, by0 + r, color);
			return;
		}
		// Per user request ("after all this work just revert the squircles back to the pixellated look and
		// make the pixellated thing default... remove that subtoggle"): no longer a per-user setting — every
		// rounded corner is drawn with the hard, binary inside/outside cutoff now (see cornerTexture's own
		// pixelated branch), unconditionally.
		net.minecraft.resources.Identifier tex = cornerTexture(r, forceCircle, true, signX, signY);
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, bx0, by0, 0f, 0f, r, r, r, r, r, r, color);
	}

	// Real bug found (per user report — "the squircles are smooth but not really smooth enough"): 4.0 is
	// the standard Apple/Android app-icon exponent, but that shape is tuned for LARGE icon-sized corners —
	// at this mod's small UI radii (well under 16px), exponent 4's flatter sides read as a harder, more
	// abrupt corner transition than the antialiasing alone can soften. Lowered toward a true circle's
	// implicit exponent of 2 (which would remove the squircle shape's distinct flatter-sided character
	// entirely) without going all the way there, so the corner is still recognizably a squircle, just
	// visibly rounder/smoother at this mod's scale.
	private static final double SQUIRCLE_EXPONENT = 2.5;

	/** Same supersampled-coverage idea as {@link #circleCoverage}, but against the superellipse
	 *  |dx/r|^n + |dy/r|^n &lt;= 1 instead of a true circle.
	 *
	 *  <p>Real bug found (per user report — "the entire mod menu seems to lag a LOT... this wasnt happening
	 *  recently", worst on content-dense screens like Dungeon Routes): unlike {@link #circleCoverage}'s own
	 *  caller, which has a cheap Euclidean-distance fast path that skips the expensive per-subsample loop
	 *  entirely for pixels that are obviously fully inside or fully outside the circle (only the thin
	 *  boundary ring pays the full {@code AA_SAMPLES}² cost), this method used to run that same expensive
	 *  loop — {@code AA_SAMPLES}² iterations with 2 {@code Math.pow} calls each — for EVERY pixel in the
	 *  whole r×r corner box, every rounded element, every frame, with no caching. Raising AA_SAMPLES 8→16
	 *  earlier this session (for smoother corners) quadrupled that already-uncapped per-pixel cost, which is
	 *  exactly the kind of change that would silently tank framerate specifically once Squircle Corners is
	 *  turned on, on whichever screen happens to draw the most rounded elements per frame. Fixed with the
	 *  equivalent fast path: since a superellipse with exponent ≥ 1 is convex, if the FARTHEST corner of a
	 *  pixel square from the center still satisfies the inequality then the whole square does too (a convex
	 *  region contains the convex hull of any of its own points); conversely if the CLOSEST point of the
	 *  square to the center already fails it, no part of the square can satisfy it. Only pixels where neither
	 *  bound resolves it — the actual boundary ring, same as the circle path — ever run the supersampled loop. */
	private static float squircleCoverage(int px, int py, double cx, double cy, double r) {
		double nx0 = (px - cx) / r, nx1 = (px + 1 - cx) / r;
		double ny0 = (py - cy) / r, ny1 = (py + 1 - cy) / r;
		double nxFar = Math.max(Math.abs(nx0), Math.abs(nx1));
		double nyFar = Math.max(Math.abs(ny0), Math.abs(ny1));
		if (Math.pow(nxFar, SQUIRCLE_EXPONENT) + Math.pow(nyFar, SQUIRCLE_EXPONENT) <= 1.0) return 1f;
		double nxClose = (nx0 <= 0 && nx1 >= 0) ? 0 : Math.min(Math.abs(nx0), Math.abs(nx1));
		double nyClose = (ny0 <= 0 && ny1 >= 0) ? 0 : Math.min(Math.abs(ny0), Math.abs(ny1));
		if (Math.pow(nxClose, SQUIRCLE_EXPONENT) + Math.pow(nyClose, SQUIRCLE_EXPONENT) > 1.0) return 0f;

		// Real improvement (per user follow-up — "make squircle corners as smooth as possible"): replaced the
		// old AA_SAMPLES×AA_SAMPLES discrete supersampling of the boundary ring with an analytic,
		// signed-distance-style estimate — evaluate the implicit superellipse function F(x,y) = |x/r|^n +
		// |y/r|^n - 1 at the pixel center, then divide by the local gradient magnitude |∇F| to convert F into
		// an approximate distance from the true curve IN PIXELS, and turn that into a soft [0,1] coverage
		// over one pixel of falloff. This is the same technique shader-based signed-distance-field rounded-
		// rect rendering uses (e.g. Inigo Quilez's SDF antialiasing) — it's perfectly continuous (nothing to
		// alias against, unlike a fixed sub-pixel grid, which can only ever approximate the curve to within
		// one sub-sample's width) AND strictly cheaper per pixel (one evaluation instead of AA_SAMPLES², i.e.
		// 256 at the current setting) — a genuine improvement on both smoothness and cost, not a tradeoff
		// between them, so AA_SAMPLES itself no longer bounds squircle quality at all (still used by
		// circleCoverage below).
		double dxp = px + 0.5 - cx, dyp = py + 0.5 - cy;
		double ax = Math.abs(dxp) / r, ay = Math.abs(dyp) / r;
		double f = Math.pow(ax, SQUIRCLE_EXPONENT) + Math.pow(ay, SQUIRCLE_EXPONENT) - 1.0;
		double gx = ax > 1e-6 ? SQUIRCLE_EXPONENT * Math.pow(ax, SQUIRCLE_EXPONENT - 1.0) / r : 0.0;
		double gy = ay > 1e-6 ? SQUIRCLE_EXPONENT * Math.pow(ay, SQUIRCLE_EXPONENT - 1.0) / r : 0.0;
		double gradMag = Math.max(Math.sqrt(gx * gx + gy * gy), 1e-6);
		double distancePixels = f / gradMag; // negative = inside the curve, positive = outside, in pixel units
		return (float) Math.max(0.0, Math.min(1.0, 0.5 - distancePixels));
	}

	/** Fraction of pixel (px,py) (unit square at [px,px+1)×[py,py+1)) that falls within radius r of
	 *  (cx,cy), sampled on an {@link #AA_SAMPLES}×{@link #AA_SAMPLES} sub-pixel grid — true 2D coverage,
	 *  not a 1D per-row approximation. */
	private static float circleCoverage(int px, int py, double cx, double cy, double r) {
		double rSq = r * r;
		int inside = 0;
		for (int sy = 0; sy < AA_SAMPLES; sy++) {
			double sampleY = py + (sy + 0.5) / AA_SAMPLES;
			double dy = sampleY - cy;
			double dySq = dy * dy;
			for (int sx = 0; sx < AA_SAMPLES; sx++) {
				double sampleX = px + (sx + 0.5) / AA_SAMPLES;
				double dx = sampleX - cx;
				if (dx * dx + dySq <= rSq) inside++;
			}
		}
		return inside / (float) (AA_SAMPLES * AA_SAMPLES);
	}

	public static void fillRounded(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
		fillRounded(graphics, x0, y0, x1, y1, radius, color, true, true, true, true);
	}

	// Per user request ("Add gradients to all mod menu elements, not like strong or anything but make it
	// gradient into a slightly darker color so it looks atleast a bit more sophisticated. Ignore text
	// however"): a real top-to-bottom gradient variant of fillRounded, for the structural background
	// panels/rows this mod's own menu chrome uses — deliberately NOT wired into the plain fillRounded
	// overloads above, since those are also used for things a flat, exact color actually matters for (color
	// picker swatches/cells, the GUI Color preview swatch) where gradienting would misrepresent the real
	// value being shown.
	private static final float GRADIENT_DARKEN_AMOUNT = 0.14f;

	/** Darkens an opaque(-ish) color's RGB channels by a fraction, alpha unchanged — the "slightly darker"
	 *  half of the gradient. */
	public static int darken(int argb, float amount) {
		int a = (argb >>> 24) & 0xFF;
		int r = Math.round(((argb >> 16) & 0xFF) * (1f - amount));
		int g = Math.round(((argb >> 8) & 0xFF) * (1f - amount));
		int b = Math.round((argb & 0xFF) * (1f - amount));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Same shape as {@link #fillRounded}, but a subtle top(topColor)-to-bottom(bottomColor) gradient
	 *  instead of one flat color — the rounded corners themselves stay flat (topColor for the top two,
	 *  bottomColor for the bottom two) since they're only ever a few pixels tall at this mod's usual radii,
	 *  an imperceptible approximation for a deliberately subtle effect; the straight top/middle/bottom bands
	 *  use the real {@code fillGradient} primitive, each interpolated to the correct color at its own y-span
	 *  so the whole shape reads as one continuous gradient with no visible seam at the corner boundaries. */
	public static void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius,
											int topColor, int bottomColor,
											boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight) {
		int width = x1 - x0;
		int height = y1 - y0;
		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		if (r <= 0) {
			graphics.fillGradient(x0, y0, x1, y1, topColor, bottomColor);
			return;
		}
		fillRoundedCorner(graphics, x0, y0, r, 1, 1, roundTopLeft, topColor, (topColor >>> 24) & 0xFF, false);
		fillRoundedCorner(graphics, x1, y0, r, -1, 1, roundTopRight, topColor, (topColor >>> 24) & 0xFF, false);
		fillRoundedCorner(graphics, x0, y1, r, 1, -1, roundBottomLeft, bottomColor, (bottomColor >>> 24) & 0xFF, false);
		fillRoundedCorner(graphics, x1, y1, r, -1, -1, roundBottomRight, bottomColor, (bottomColor >>> 24) & 0xFF, false);

		float rFrac = height <= 0 ? 0f : r / (float) height;
		int colorAtTopEdgeEnd = lerpColor(topColor, bottomColor, rFrac);
		int colorAtBottomEdgeStart = lerpColor(topColor, bottomColor, 1f - rFrac);
		graphics.fillGradient(x0, y0 + r, x1, y1 - r, colorAtTopEdgeEnd, colorAtBottomEdgeStart);
		graphics.fillGradient(x0 + r, y0, x1 - r, y0 + r, topColor, colorAtTopEdgeEnd);
		graphics.fillGradient(x0 + r, y1 - r, x1 - r, y1, colorAtBottomEdgeStart, bottomColor);
	}

	/** Convenience overload — gradients from {@code color} into a slightly darker shade of itself, rounded
	 *  on all four corners. The common case for this mod's own panel/row/tile backgrounds. */
	public static void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
		fillRoundedGradient(graphics, x0, y0, x1, y1, radius, color, darken(color, GRADIENT_DARKEN_AMOUNT), true, true, true, true);
	}

	/** Same as the 6-arg overload, but with per-corner rounding flags — matches {@link #fillRounded}'s own
	 *  10-arg shape overload. */
	public static void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color,
											boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight) {
		fillRoundedGradient(graphics, x0, y0, x1, y1, radius, color, darken(color, GRADIENT_DARKEN_AMOUNT),
			roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight);
	}

	// Per user request (Custom panel theme — "a two-pin drag gradient editor... horizontal/vertical
	// orientation", generalized this round into "Do those now" — angular/radial modes): unlike the fixed
	// 0..1 topColor/bottomColor gradient above, this supports two independently POSITIONED color stops
	// (pin1 at pos1, pin2 at pos2), clamped flat to each pin's own color before/after its position, at ANY
	// angle (not just horizontal/vertical) or as a radial "feathered circle" — baked into a small cached 2D
	// texture rather than a live per-pixel fill loop, the same real perf fix this file's own
	// cornerTexture/colorSquareGradientTexture already use for exactly this class of "many tiny draws every
	// frame" problem.
	//
	// Real GPU-texture leak found while planning the Angular/Radial redesign: this cache used to be a plain
	// unbounded HashMap that registered a brand-new GPU texture on every distinct (color1,color2,pos1,pos2)
	// combination and never released an old one — harmless for a static gradient (a handful of combinations,
	// ever), but the Chroma gradient mode added this same session changes color1/color2 EVERY CLIENT TICK
	// (20/sec), so leaving Chroma running leaked one new GPU texture per tick forever. A bounded
	// access-order LinkedHashMap evicts (and actually {@code TextureManager.release()}s — confirmed via
	// javap on the real MC 26.2 client jar as the correct API for this) the least-recently-used entry once
	// the cache grows past a small cap, so a long Chroma session settles into re-baking a small rotating set
	// of textures instead of accumulating them forever.
	private static final int GRADIENT_TEXTURE_CACHE_CAP = 48;
	private static final java.util.LinkedHashMap<Long, net.minecraft.resources.Identifier> panelGradientTextureCache =
		new java.util.LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(java.util.Map.Entry<Long, net.minecraft.resources.Identifier> eldest) {
				if (size() <= GRADIENT_TEXTURE_CACHE_CAP) return false;
				Minecraft.getInstance().getTextureManager().release(eldest.getValue());
				return true;
			}
		};
	private static final int PANEL_GRADIENT_TEXTURE_SIZE = 64;

	/** The raw (unclamped, pre pos1/pos2-remap) gradient parameter at a given fractional position within the
	 *  FULL span a gradient is stretched across — {@code (u,v)} both 0..1. Shared by the texture baker below
	 *  and by single-point evaluation (rounded-corner flat tints, row/subtoggle background sampling for the
	 *  extended gradient reach — see {@link Theme#panelGradientColorAt}).
	 *
	 *  <p>LINEAR: 0°=top=pin1, clockwise (per spec) — unit direction {@code u=(sin θ,-cos θ)}, projected
	 *  against the position normalized to [-0.5,0.5] on each axis; this only depends on FRACTIONAL position,
	 *  not pixel dimensions, so a non-square full span just reads as the gradient stretching more along its
	 *  longer axis (same as a CSS linear-gradient against a non-square box). Old horizontal=true is exactly
	 *  angle=270, old horizontal=false ("Vertical") is exactly angle=0 — see PanelThemeFeature's own
	 *  migration for existing saved configs.
	 *
	 *  <p>RADIAL: a true feathered CIRCLE regardless of the full span's own aspect ratio — distance is
	 *  measured in real PIXELS ({@code fullWidthPx}/{@code fullHeightPx}) from center, normalized by
	 *  {@code 0.5 * min(fullWidthPx, fullHeightPx)}, rather than normalizing each axis independently (which
	 *  would stretch the circle into an ellipse on a non-square panel). */
	private static float gradientRawT(Theme.GradientMode mode, float angleDegrees, int fullWidthPx, int fullHeightPx, float u, float v) {
		if (mode == Theme.GradientMode.RADIAL) {
			float dx = (u - 0.5f) * fullWidthPx;
			float dy = (v - 0.5f) * fullHeightPx;
			float maxRadius = 0.5f * Math.min(fullWidthPx, fullHeightPx);
			if (maxRadius <= 1e-4f) return 0f;
			return (float) Math.hypot(dx, dy) / maxRadius;
		}
		double rad = Math.toRadians(angleDegrees);
		float ux = (float) Math.sin(rad), uy = (float) -Math.cos(rad);
		float nx = u - 0.5f, ny = v - 0.5f;
		return 0.5f - (nx * ux + ny * uy);
	}

	/** The final, remapped gradient color at fractional position {@code (u,v)} within the full span —
	 *  {@code gradientRawT} above, then the same clamp-remap every other stop-based gradient in this codebase
	 *  already uses. Exposed for {@link Theme#panelGradientColorAt} (row/subtoggle background sampling). */
	public static int colorAtFraction(int color1, int color2, float pos1, float pos2, Theme.GradientMode mode,
									   float angleDegrees, int fullWidthPx, int fullHeightPx, float u, float v) {
		float raw = gradientRawT(mode, angleDegrees, fullWidthPx, fullHeightPx, u, v);
		float t = clamp01((raw - pos1) / Math.max(1e-4f, pos2 - pos1));
		return lerpColor(color1, color2, t);
	}

	private static net.minecraft.resources.Identifier panelGradientTexture2D(int color1, int color2, float pos1, float pos2,
																			  Theme.GradientMode mode, float angleDegrees,
																			  int fullWidthPx, int fullHeightPx) {
		int p1 = Math.round(clamp01(pos1) * 1000f);
		int p2 = Math.round(clamp01(pos2) * 1000f);
		// Cache key mixing, same lossy-but-practically-collision-free approach the old 1D texture cache
		// already used (a hash mix via XOR/shift, not a mathematically perfect discriminator) — acceptable
		// here for the same reason it was acceptable there: a false cache hit would just briefly show a
		// slightly-wrong bake, not crash, and is astronomically unlikely given how many bits distinguish
		// realistic distinct gradients.
		long key = ((long) (color1 & 0xFFFFFFFFL) << 32) ^ ((long) (color2 & 0xFFFFFFFFL) << 12) ^ ((long) p1 << 24) ^ p2;
		key ^= ((long) mode.ordinal()) << 62;
		if (mode == Theme.GradientMode.RADIAL) {
			// A true circle depends on the full span's own aspect ratio (see gradientRawT's own doc comment)
			// — bake per distinct aspect ratio rather than per exact pixel size, so ordinary GUI-scale/window
			// resizes that don't change the ratio don't force a re-bake.
			int aspectKey = Math.round(clamp01((float) fullWidthPx / (fullWidthPx + fullHeightPx)) * 4095f);
			key ^= ((long) aspectKey) << 44;
		} else {
			int angleKey = Math.round(((angleDegrees % 360f) + 360f) % 360f * 10f);
			key ^= ((long) angleKey) << 44;
		}
		net.minecraft.resources.Identifier cached = panelGradientTextureCache.get(key);
		if (cached != null) return cached;

		int size = PANEL_GRADIENT_TEXTURE_SIZE;
		com.mojang.blaze3d.platform.NativeImage image =
			new com.mojang.blaze3d.platform.NativeImage(com.mojang.blaze3d.platform.NativeImage.Format.RGBA, size, size, false);
		for (int j = 0; j < size; j++) {
			float v = (j + 0.5f) / size;
			for (int i = 0; i < size; i++) {
				float u = (i + 0.5f) / size;
				int rgb = colorAtFraction(color1, color2, pos1, pos2, mode, angleDegrees, fullWidthPx, fullHeightPx, u, v);
				int a = (rgb >>> 24) & 0xFF, r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
				image.setPixelABGR(i, j, (a << 24) | (b << 16) | (g << 8) | r);
			}
		}
		net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath(
			"skyblocksimplified", "panel_gradient2_" + Long.toHexString(key));
		Minecraft.getInstance().getTextureManager().register(id,
			new net.minecraft.client.renderer.texture.DynamicTexture(() -> "sbs-panel-gradient", image));
		panelGradientTextureCache.put(key, id);
		return id;
	}

	/** Draws a two-pin-positioned gradient panel background (linear at any angle, or radial), rounded on all
	 *  four corners — see {@link #gradientRawT} for the direction math. Convenience overload: full span
	 *  defaults to this call's own rect (no wider shared gradient to slice into). */
	public static void fillPanelGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius,
										  int color1, int color2, float pos1, float pos2, Theme.GradientMode mode, float angleDegrees) {
		fillPanelGradient(graphics, x0, y0, x1, y1, radius, color1, color2, pos1, pos2, mode, angleDegrees, true, true, true, true);
	}

	/** Same as the 11-arg overload, but with per-corner rounding flags — matches {@link #fillRounded}'s own
	 *  10-arg shape overload, needed since this mod's own header/sidebar/content panel fills each round only
	 *  some of the panel's four corners. Full span defaults to this call's own rect — see the {@code fullX0}/
	 *  {@code fullY0} overload below for a caller drawing only part of a wider shared gradient (e.g. the
	 *  sidebar/content split). */
	public static void fillPanelGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius,
										  int color1, int color2, float pos1, float pos2, Theme.GradientMode mode, float angleDegrees,
										  boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight) {
		fillPanelGradient(graphics, x0, y0, x1, y1, radius, color1, color2, pos1, pos2, mode, angleDegrees,
			roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight, x0, x1);
	}

	/** Same as the 14-arg overload, but lets a caller drawing only PART of the panel (the sidebar or content
	 *  section, each a narrower slice of the real full panel width) say what that full width actually is —
	 *  see the class's earlier "gradient cuts off at the sides" bug (fixed for horizontal-only mode, now
	 *  generalized here for any angle/radial) for why every section must paint only its own true fractional
	 *  slice of ONE shared gradient rather than re-traversing its own local 0..1 span. Full Y span defaults
	 *  to this call's own rect (only the X span is ever actually split between sections in this mod's layout). */
	public static void fillPanelGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius,
										  int color1, int color2, float pos1, float pos2, Theme.GradientMode mode, float angleDegrees,
										  boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight,
										  int fullX0, int fullX1) {
		fillPanelGradient(graphics, x0, y0, x1, y1, radius, color1, color2, pos1, pos2, mode, angleDegrees,
			roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight, fullX0, y0, fullX1, y1);
	}

	/** Full 16-arg form — an independent full Y span too, needed for RADIAL mode to stay a true circle when
	 *  a caller's own draw rect is a narrower/shorter slice of a taller/wider shared full panel (matches
	 *  {@code fullX0}/{@code fullX1}'s own reasoning, just for the vertical axis). */
	public static void fillPanelGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius,
										  int color1, int color2, float pos1, float pos2, Theme.GradientMode mode, float angleDegrees,
										  boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight,
										  int fullX0, int fullY0, int fullX1, int fullY1) {
		int width = x1 - x0, height = y1 - y0;
		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		float p1 = Math.min(clamp01(pos1), clamp01(pos2));
		float p2 = Math.max(clamp01(pos1), clamp01(pos2));
		int fullWidth = Math.max(1, fullX1 - fullX0);
		int fullHeight = Math.max(1, fullY1 - fullY0);
		net.minecraft.resources.Identifier tex = panelGradientTexture2D(color1, color2, p1, p2, mode, angleDegrees, fullWidth, fullHeight);
		com.mojang.blaze3d.pipeline.RenderPipeline pipeline = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;

		if (r <= 0) {
			blitGradientSubRect(graphics, pipeline, tex, x0, y0, x1, y1, fullX0, fullY0, fullWidth, fullHeight);
			return;
		}

		// Real bug found (per user report — "the gradient doesn't follow the squircle... the squircle just
		// overlays it, gradient is a full square"): an earlier version of this method blitted ONE opaque
		// rectangle across the WHOLE rect (corners included) and only drew the rounded-corner mask ON TOP —
		// but the mask's alpha=0 pixels outside its own curve don't erase anything already drawn underneath,
		// they just let it show through unchanged, so the mask never actually cut the square corners away at
		// all; the panel just kept rendering as a literal square with a flat-tinted disk sitting on top of
		// each corner. Fixed by going back to never drawing the base gradient INTO a rounded corner's own r×r
		// box in the first place — only the masked disk is drawn there (same as {@link #fillRounded}'s own
		// plain-color corners), so whatever's genuinely behind the panel shows through outside its curve. A
		// SQUARE (non-rounded) corner still gets its own plain, fully-opaque r×r sub-blit of the real
		// gradient texture — the actual fix for the older "square corner breaks the gradient with a flat
		// patch" bug, now handled per-corner instead of via one unclippable base layer.
		blitGradientSubRect(graphics, pipeline, tex, x0 + r, y0, x1 - r, y0 + r, fullX0, fullY0, fullWidth, fullHeight);
		blitGradientSubRect(graphics, pipeline, tex, x0 + r, y1 - r, x1 - r, y1, fullX0, fullY0, fullWidth, fullHeight);
		blitGradientSubRect(graphics, pipeline, tex, x0, y0 + r, x1, y1 - r, fullX0, fullY0, fullWidth, fullHeight);

		float fracXStart = (float) (x0 - fullX0) / fullWidth, fracXEnd = (float) (x1 - fullX0) / fullWidth;
		float fracYStart = (float) (y0 - fullY0) / fullHeight, fracYEnd = (float) (y1 - fullY0) / fullHeight;
		int colorTL = colorAtFraction(color1, color2, p1, p2, mode, angleDegrees, fullWidth, fullHeight, fracXStart, fracYStart);
		int colorTR = colorAtFraction(color1, color2, p1, p2, mode, angleDegrees, fullWidth, fullHeight, fracXEnd, fracYStart);
		int colorBL = colorAtFraction(color1, color2, p1, p2, mode, angleDegrees, fullWidth, fullHeight, fracXStart, fracYEnd);
		int colorBR = colorAtFraction(color1, color2, p1, p2, mode, angleDegrees, fullWidth, fullHeight, fracXEnd, fracYEnd);
		if (roundTopLeft) fillRoundedCorner(graphics, x0, y0, r, 1, 1, true, colorTL, (colorTL >>> 24) & 0xFF, false);
		else blitGradientSubRect(graphics, pipeline, tex, x0, y0, x0 + r, y0 + r, fullX0, fullY0, fullWidth, fullHeight);
		if (roundTopRight) fillRoundedCorner(graphics, x1, y0, r, -1, 1, true, colorTR, (colorTR >>> 24) & 0xFF, false);
		else blitGradientSubRect(graphics, pipeline, tex, x1 - r, y0, x1, y0 + r, fullX0, fullY0, fullWidth, fullHeight);
		if (roundBottomLeft) fillRoundedCorner(graphics, x0, y1, r, 1, -1, true, colorBL, (colorBL >>> 24) & 0xFF, false);
		else blitGradientSubRect(graphics, pipeline, tex, x0, y1 - r, x0 + r, y1, fullX0, fullY0, fullWidth, fullHeight);
		if (roundBottomRight) fillRoundedCorner(graphics, x1, y1, r, -1, -1, true, colorBR, (colorBR >>> 24) & 0xFF, false);
		else blitGradientSubRect(graphics, pipeline, tex, x1 - r, y1 - r, x1, y1, fullX0, fullY0, fullWidth, fullHeight);
	}

	private static void blitGradientSubRect(GuiGraphicsExtractor graphics, com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
											 net.minecraft.resources.Identifier tex, int x0, int y0, int x1, int y1,
											 int fullX0, int fullY0, int fullWidth, int fullHeight) {
		int width = x1 - x0, height = y1 - y0;
		if (width <= 0 || height <= 0) return;
		float uStart = (float) (x0 - fullX0) / fullWidth * PANEL_GRADIENT_TEXTURE_SIZE;
		float uEnd = (float) (x1 - fullX0) / fullWidth * PANEL_GRADIENT_TEXTURE_SIZE;
		float vStart = (float) (y0 - fullY0) / fullHeight * PANEL_GRADIENT_TEXTURE_SIZE;
		float vEnd = (float) (y1 - fullY0) / fullHeight * PANEL_GRADIENT_TEXTURE_SIZE;
		int srcWidth = Math.max(1, Math.round(uEnd - uStart));
		int srcHeight = Math.max(1, Math.round(vEnd - vStart));
		graphics.blit(pipeline, tex, x0, y0, uStart, vStart, width, height, srcWidth, srcHeight,
			PANEL_GRADIENT_TEXTURE_SIZE, PANEL_GRADIENT_TEXTURE_SIZE, 0xFFFFFFFF);
	}

	/** A real arbitrary-angle screen-space line, not the stepped-square approximation used elsewhere —
	 *  rotates the pose stack to align a thin filled rect along (x0,y0)-(x1,y1), then draws it with a
	 *  plain axis-aligned fill() (GuiGraphicsExtractor has no line primitive of its own). */
	public static void drawLine(GuiGraphicsExtractor graphics, float x0, float y0, float x1, float y1, float thickness, int color) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length < 0.01f) return;
		float angle = (float) Math.atan2(dy, dx);
		graphics.pose().pushMatrix();
		graphics.pose().translate(x0, y0);
		graphics.pose().rotate(angle);
		graphics.fill(0, (int) Math.floor(-thickness / 2f), Math.round(length), (int) Math.ceil(thickness / 2f), color);
		graphics.pose().popMatrix();
	}

	/** A true circle (row-by-row rasterization) — use where something should look like an actual
	 *  circle (close buttons, knobs), since fillRounded's corner-inset approximation reads as slightly
	 *  chunky/octagonal at small sizes. */
	public static void fillCircle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
		for (int dy = -radius; dy < radius; dy++) {
			double yMid = dy + 0.5;
			double halfWidth = Math.sqrt(Math.max(0.0, (double) radius * radius - yMid * yMid));
			int rowY = cy + dy;
			int x0 = (int) Math.round(cx - halfWidth);
			int x1 = (int) Math.round(cx + halfWidth);
			if (x1 > x0) graphics.fill(x0, rowY, x1, rowY + 1, color);
		}
	}

	public static float[] rgbToHsv(int argb) {
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		float h;
		if (delta < 1e-6f) {
			h = 0f;
		} else if (max == r) {
			h = 60f * (((g - b) / delta) % 6f);
		} else if (max == g) {
			h = 60f * (((b - r) / delta) + 2f);
		} else {
			h = 60f * (((r - g) / delta) + 4f);
		}
		if (h < 0) h += 360f;
		float s = max <= 1e-6f ? 0f : delta / max;
		return new float[]{h, s, max};
	}

	public static int hsvToRgb(float h, float s, float v) {
		h = ((h % 360f) + 360f) % 360f;
		float c = v * s;
		float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
		float m = v - c;
		float r, g, b;
		if (h < 60) { r = c; g = x; b = 0; }
		else if (h < 120) { r = x; g = c; b = 0; }
		else if (h < 180) { r = 0; g = c; b = x; }
		else if (h < 240) { r = 0; g = x; b = c; }
		else if (h < 300) { r = c; g = 0; b = x; }
		else { r = x; g = 0; b = c; }
		int ri = Math.round((r + m) * 255);
		int gi = Math.round((g + m) * 255);
		int bi = Math.round((b + m) * 255);
		return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
	}

	public static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	/** A rounded-rect ring (hollow border, not filled) — follows the same curve as fillRounded rather
	 *  than a plain rectangular frame, computed per-row so it works regardless of what's behind it
	 *  (doesn't rely on "punching a hole" by painting over with a background color). */
	public static void fillRoundedRing(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int outerRadius, int thickness, int color) {
		fillRoundedRing(graphics, x0, y0, x1, y1, outerRadius, thickness, color, color);
	}

	/** Same ring, but interpolates from topColor at row 0 to bottomColor at the last row — a real
	 *  per-pixel gradient rather than two flat halves split down the middle. */
	public static void fillRoundedRing(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int outerRadius, int thickness, int topColor, int bottomColor) {
		int width = x1 - x0;
		int height = y1 - y0;
		int r = Math.max(0, Math.min(outerRadius, Math.min(width, height) / 2));
		int t = Math.max(1, Math.min(thickness, Math.max(1, Math.min(width, height) / 2)));
		int innerR = Math.max(0, r - t);
		int innerHeight = height - 2 * t;

		for (int row = 0; row < height; row++) {
			int y = y0 + row;
			int color = height <= 1 ? topColor : lerpColor(topColor, bottomColor, row / (float) (height - 1));
			int outerInset = cornerInset(row, height, r);
			int outerX0 = x0 + outerInset;
			int outerX1 = x1 - outerInset;
			if (outerX1 <= outerX0) continue;

			int innerRow = row - t;
			boolean hasInnerHole = innerHeight > 0 && innerRow >= 0 && innerRow < innerHeight;
			if (!hasInnerHole) {
				graphics.fill(outerX0, y, outerX1, y + 1, color);
				continue;
			}
			int innerInset = cornerInset(innerRow, innerHeight, innerR);
			int innerX0 = x0 + t + innerInset;
			int innerX1 = x1 - t - innerInset;
			if (innerX1 <= innerX0) {
				graphics.fill(outerX0, y, outerX1, y + 1, color);
			} else {
				if (innerX0 > outerX0) graphics.fill(outerX0, y, Math.min(innerX0, outerX1), y + 1, color);
				if (innerX1 < outerX1) graphics.fill(Math.max(innerX1, outerX0), y, outerX1, y + 1, color);
			}
		}
	}

	public static int lerpColor(int a, int b, float t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int ra = Math.round(aa + (ba - aa) * t);
		int rr = Math.round(ar + (br - ar) * t);
		int rg = Math.round(ag + (bg - ag) * t);
		int rb = Math.round(ab + (bb - ab) * t);
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
	}

	/** Exposed so callers that need to clip their OWN per-row fills to the same rounded-corner curve
	 *  (e.g. chroma bands drawn as flat rects) can match fillRounded's shape instead of drawing square. */
	public static int cornerInset(int row, int height, int r) {
		if (r <= 0) return 0;
		if (row < r) {
			double dy = r - row - 0.5;
			return (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
		}
		if (row >= height - r) {
			int fromBottom = height - row - 1;
			double dy = r - fromBottom - 0.5;
			return (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
		}
		return 0;
	}
}
