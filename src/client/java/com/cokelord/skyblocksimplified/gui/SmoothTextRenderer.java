package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per user request ("reset the font to default minecraft and revert it to how it was before quicksand. Its
 * causing too many issues. Keep the font importer however incase people want that"): this class used to
 * auto-load a bundled Quicksand TTF as its own baseline whenever no font had been imported, rendering nearly
 * every string in the mod menu through Java2D by default. That's now removed — {@link #baseFont()} only ever
 * returns a font the user explicitly imported via {@link com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature}
 * ({@link #setActiveFont}); with nothing imported, {@code null} short-circuits every {@link #draw} call
 * straight to a plain {@code graphics.text(...)} call, i.e. the real vanilla Minecraft font, matching how the
 * mod menu rendered before Quicksand was ever introduced.
 *
 * <p>For anyone who DOES import a font (most won't work well — Minecraft's own hand-tuned bitmap font is the
 * safer default for most players, which is exactly why it's the default again): the active TTF renders
 * through Java2D's own font engine (real antialiasing, hinting, fractional-metrics positioning) onto an
 * in-memory {@link BufferedImage}, uploaded as a one-off GPU texture via the exact NativeImage/DynamicTexture
 * pattern this codebase already uses for custom highlight images (see MobHighlightFeature's own
 * {@code readAnyImage}), and blitted in place of a normal {@code graphics.text} call.
 *
 * <p>If the active (imported) TTF fails to load for any reason, every call here falls back to a plain
 * {@code graphics.text(...)} call instead — this must never be able to make mod text disappear or crash a
 * screen over what's ultimately a cosmetic upgrade.
 */
public final class SmoothTextRenderer {
	private SmoothTextRenderer() {}

	// Real bug found (per user report — "Importing a font doesnt change the mod font"): this class used to
	// hardcode the bundled Quicksand resource with no way for FontImporterFeature's own "keep inside mod"
	// scope to ever reach it — since this class is now what renders nearly every string in the mod menu,
	// an imported font that never touches THIS font had no visible effect on the menu at all, only on
	// whatever separately goes through GameWideFontOverride. FontImporterFeature now calls
	// setActiveFont/clearActiveFont directly; the whole draw-time cache is cleared on change since every
	// entry in it was baked from whatever font used to be active.
	private static volatile java.awt.Font overrideFont;

	public static void setActiveFont(java.awt.Font font) {
		overrideFont = font;
		clearCache();
	}

	public static void clearActiveFont() {
		overrideFont = null;
		clearCache();
	}

	/** Null (no font imported, the default state) short-circuits every {@link #draw} call straight to plain
	 *  vanilla text rendering — see this class's own doc comment for why there's no bundled fallback font
	 *  here anymore. */
	private static java.awt.Font baseFont() {
		return overrideFont;
	}

	// Real bug found (per user report — "not centered like how it was" and "still a bit too small"): every
	// centered-button/label call site in MainScreen computes its x offset from vanilla `this.font.width(text)`
	// (Minecraft's own bitmap-font metrics), then draws through THIS renderer — whose own Java2D-measured
	// width for the same string is a different number (a different typeface, different per-glyph advances).
	// That mismatch is what visibly throws off centering on every single one of those ~100 call sites; fixing
	// it individually at each site isn't practical, so this class now forces its OWN rendered width to match
	// vanilla's `Font.width(text)` exactly (stretching/compressing the baked glyph run to fit, imperceptible
	// at the small width deltas involved) — every existing centering call site stays correct with zero changes
	// there. Real fix for "too small" alongside it: HEIGHT_BOOST renders the glyphs at a deliberately larger
	// nominal size than the vanilla line height calls for (Quicksand's x-height/cap-height ratio undershoots
	// Minecraft's own hand-drawn bitmap glyphs at the same nominal cell height), then reports that larger
	// height back through logicalHeight/paddingY so it's the boosted size that actually lands on screen.
	private static final float HEIGHT_BOOST = 1.15f;

	// Real fix (per user report — "numbers seem to be really squished") for a case the width-forcing above
	// didn't account for: vanilla's own bitmap digits are narrow and nearly fixed-width, while Quicksand's
	// proportional digits often measure noticeably WIDER at the same nominal size — forcing an exact stretch
	// to vanillaWidth for a short numeric string (where that gap is a much bigger fraction of the total width
	// than in a long label) visibly compresses the glyphs instead of the "imperceptible" case this was
	// designed around. Clamping the stretch keeps the shape recognizable; the (rare, small) resulting gap
	// from vanillaWidth is recentered in {@link #render} instead of left-anchored, not left uncompensated.
	private static final float MIN_WIDTH_STRETCH = 0.85f;
	private static final float MAX_WIDTH_STRETCH = 1.2f;

	private record CacheKey(String text, int color, int logicalHeight) {}

	// Real regression found (per user report with a real screenshot — text went from "a bit aliased" to
	// "REALLY blurry... more pixelated now" after a prior round pre-downsampled the bake to exactly logical
	// size before upload): GuiGraphics' automatic GUI-Scale transform is a SEPARATE magnification step that
	// happens AFTER this class' own blit call, multiplying whatever destination size was requested by the
	// live GUI Scale when the frame is actually presented. Baking (and uploading) at exactly logical size
	// meant that automatic magnification had to stretch an already-final-size texture by the GUI Scale
	// factor with nearest-neighbor sampling — blocky. Baking at SUPERSAMPLE times logical size and blitting
	// dest=logical/source=bake instead means that same automatic magnification is offset by an equal-or-
	// larger GPU-side MINIFICATION during the blit itself (real source detail being discarded down to
	// destination size, not a tiny texture being blown up) — much closer to how the bundled Quicksand bitmap
	// font (baked at oversample=16, the same idea) already survives GUI Scale scaling acceptably.
	private static final int SUPERSAMPLE = 4;

	private static final class CacheEntry {
		final Identifier textureId;
		final int bakeWidth, bakeHeight, logicalWidth, logicalHeight, paddingX, paddingY, textWidth;
		CacheEntry(Identifier textureId, int bakeWidth, int bakeHeight, int logicalWidth, int logicalHeight,
				   int paddingX, int paddingY, int textWidth) {
			this.textureId = textureId;
			this.bakeWidth = bakeWidth;
			this.bakeHeight = bakeHeight;
			this.logicalWidth = logicalWidth;
			this.logicalHeight = logicalHeight;
			this.paddingX = paddingX;
			this.paddingY = paddingY;
			this.textWidth = textWidth;
		}
	}

	// Generous relative to how many distinct feature display names/labels this mod actually has (well under
	// 300 total) — in practice this just caches everything a caller ever draws through this class without
	// evicting, rather than genuinely bounding a large working set. Eviction still releases the GPU texture
	// (see removeEldestEntry) so a caller that DOES feed this highly dynamic/unique text (not recommended —
	// see this class' own doc comment) can't leak GPU memory unbounded, just thrash a bit.
	private static final int MAX_CACHE_ENTRIES = 300;
	private static final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
			if (size() <= MAX_CACHE_ENTRIES) return false;
			Minecraft.getInstance().getTextureManager().release(eldest.getValue().textureId);
			return true;
		}
	};

	private static void clearCache() {
		for (CacheEntry entry : cache.values()) {
			Minecraft.getInstance().getTextureManager().release(entry.textureId);
		}
		cache.clear();
	}

	private static final AtomicLong idCounter = new AtomicLong();

	/** Same left-x/top-y anchor convention as {@code graphics.text(font, text, x, y, color)} — a drop-in
	 *  replacement at a call site, not a different API to learn. Returns the measured text width (Java2D's
	 *  own metrics, not vanilla font's — close enough for layout, not pixel-identical) so a caller that needs
	 *  it for centering/wrapping still gets a usable value. {@code font} is only used to read the current
	 *  logical line height (to size the render at roughly the right on-screen pixel height for the current
	 *  GUI Scale) and as the fallback renderer if Java2D rasterization isn't available. */
	public static int draw(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		if (text == null || text.isEmpty()) return 0;
		java.awt.Font base = baseFont();
		if (base == null) {
			graphics.text(font, text, x, y, color);
			return font.width(text);
		}
		// Two real ways this active TTF can't safely stand in for a plain graphics.text call, both needed
		// once this got rolled out mod-wide (not just the one hand-picked row label it started on):
		// 1. Legacy "§"-formatting codes (e.g. "§7", used throughout this mod's own hint/label text) are
		//    interpreted by vanilla's font renderer, not drawn as literal glyphs — Java2D has no idea what a
		//    section sign means and would draw the raw "§7" characters right into the string.
		// 2. AWT's Font.createFont loads exactly one Latin TTF with no per-glyph fallback chain the way
		//    Minecraft's own layered font providers have — a symbol/icon character this specific font
		//    doesn't contain (e.g. "▶") would rasterize as a missing-glyph tofu box instead of quietly
		//    falling back to another font the way vanilla text rendering does.
		// Both cases fall back to the plain bitmap-font draw instead of risking visibly broken text.
		if (text.indexOf('§') >= 0 || base.canDisplayUpTo(text) != -1) {
			graphics.text(font, text, x, y, color);
			return font.width(text);
		}
		int logicalHeight = Math.max(6, font.lineHeight);
		int vanillaWidth = Math.max(1, font.width(text));
		CacheKey key = new CacheKey(text, color, logicalHeight);
		CacheEntry entry = cache.get(key);
		if (entry == null) {
			entry = render(base, text, color, logicalHeight, vanillaWidth);
			if (entry == null) {
				graphics.text(font, text, x, y, color);
				return font.width(text);
			}
			cache.put(key, entry);
		}
		// Destination size is the LOGICAL image size (matching every other draw call in this same coordinate
		// space); source region is the full higher-resolution baked texture, so the GPU downsamples it —
		// exactly the supersampling this class relies on for a genuinely smooth result. See SUPERSAMPLE's
		// own doc comment for why these two sizes must never be conflated (or collapsed to the same value).
		graphics.blit(RenderPipelines.GUI_TEXTURED, entry.textureId, x - entry.paddingX, y - entry.paddingY, 0f, 0f,
			entry.logicalWidth, entry.logicalHeight, entry.bakeWidth, entry.bakeHeight, entry.bakeWidth, entry.bakeHeight, 0xFFFFFFFF);
		return entry.textWidth;
	}

	private static CacheEntry render(java.awt.Font base, String text, int colorArgb, int logicalHeight, int vanillaWidth) {
		try {
			int bakeHeight = Math.round(logicalHeight * HEIGHT_BOOST) * SUPERSAMPLE;
			java.awt.Font awtFont = base.deriveFont((float) bakeHeight);

			BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			Graphics2D measureGraphics = measureImage.createGraphics();
			measureGraphics.setFont(awtFont);
			java.awt.FontMetrics metrics = measureGraphics.getFontMetrics();
			int bakeTextWidth = Math.max(1, metrics.stringWidth(text));
			int ascent = metrics.getAscent();
			int descent = metrics.getDescent();
			measureGraphics.dispose();

			int bakePadding = 2 * SUPERSAMPLE;
			int bakeWidth = bakeTextWidth + bakePadding * 2;
			int bakeImageHeight = ascent + descent + bakePadding * 2;
			BufferedImage image = new BufferedImage(bakeWidth, bakeImageHeight, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setFont(awtFont);
			int a = (colorArgb >>> 24) & 0xFF;
			if (a == 0) a = 0xFF; // Minecraft color ints are conventionally opaque even when the alpha byte is left 0
			int r = (colorArgb >>> 16) & 0xFF;
			int gr = (colorArgb >>> 8) & 0xFF;
			int b = colorArgb & 0xFF;
			g.setColor(new java.awt.Color(r, gr, b, a));
			g.drawString(text, bakePadding, bakePadding + ascent);
			g.dispose();

			// Same BufferedImage(ARGB) -> NativeImage(ABGR) per-pixel channel swap this codebase already uses
			// in MobHighlightFeature.readAnyImage for loading arbitrary user image files.
			NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, bakeWidth, bakeImageHeight, false);
			for (int py = 0; py < bakeImageHeight; py++) {
				for (int px = 0; px < bakeWidth; px++) {
					int argb = image.getRGB(px, py);
					int pa = (argb >>> 24) & 0xFF;
					int pr = (argb >>> 16) & 0xFF;
					int pg = (argb >>> 8) & 0xFF;
					int pb = argb & 0xFF;
					nativeImage.setPixelABGR(px, py, (pa << 24) | (pb << 16) | (pg << 8) | pr);
				}
			}

			Identifier id = Identifier.fromNamespaceAndPath("skyblocksimplified", "smooth_text_" + idCounter.incrementAndGet());
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "sbs-smooth-text", nativeImage));
			// Destination width aims for vanilla's own Font#width(text) (every existing centering call site
			// computes its x offset from that same vanilla width) but the stretch needed to hit it exactly is
			// now clamped (see MIN/MAX_WIDTH_STRETCH's own doc comment) rather than always applied in full —
			// short numeric strings in particular can need a much larger stretch than a longer label does, and
			// that's exactly the case that visibly distorted digit shapes.
			int naturalLogicalWidth = Math.max(1, Math.round(bakeWidth / (float) SUPERSAMPLE));
			float rawStretch = vanillaWidth / (float) naturalLogicalWidth;
			float widthStretch = Math.max(MIN_WIDTH_STRETCH, Math.min(MAX_WIDTH_STRETCH, rawStretch));
			int logicalWidth = Math.max(1, Math.round(naturalLogicalWidth * widthStretch));
			// Y is NOT stretched — kept at its natural (supersample-scaled-down) size, same as before.
			int naturalLogicalHeight = Math.max(1, Math.round(bakeImageHeight / (float) SUPERSAMPLE));

			// Real fix (per user report — "letters... not centered... mostly on the bottom of the box") for a
			// bug HEIGHT_BOOST introduced: every call site's own vertical-centering math assumes this renders
			// at plain `font.lineHeight` (that's what `logicalHeight` here is, before the boost), but the
			// actual rendered image is HEIGHT_BOOST taller than that — and only the small anti-aliasing bake
			// margin was ever subtracted back out of its top offset, so nearly all of that extra height spilled
			// downward past where callers expected the glyph to end. Centering the WHOLE rendered image
			// (boost included) on the same vertical midpoint an unboosted render would have used keeps every
			// existing call site's own centering math landing in the right place.
			int logicalPaddingY = Math.round((naturalLogicalHeight - logicalHeight) / 2f);
			// X padding: the natural left margin scales with the same clamped stretch the glyph run itself
			// got, then gets recentered by however far short/over logicalWidth landed versus the full
			// vanillaWidth callers assume text occupies (positive when the clamp left a gap, i.e. the glyph is
			// narrower than the claimed box — shifts it right to center in that box instead of hugging its
			// left edge).
			float naturalPaddingX = bakePadding / (float) SUPERSAMPLE;
			float centeringOffset = (vanillaWidth - logicalWidth) / 2f;
			int logicalPaddingX = Math.round(naturalPaddingX * widthStretch - centeringOffset);
			return new CacheEntry(id, bakeWidth, bakeImageHeight, logicalWidth, naturalLogicalHeight, logicalPaddingX, logicalPaddingY, vanillaWidth);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("SmoothTextRenderer: failed to rasterize '{}', falling back to the normal bitmap font for this string", text, e);
			return null;
		}
	}
}
