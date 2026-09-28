package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.Gson;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per user request ("The pixels are still really visible, i would suggest the rewrite since thats the one
 * modernui uses and its really smooth. So go ahead and do that."): a real MSDF (Multi-channel Signed Distance
 * Field) text renderer, replacing the previous vanilla-TTF-provider approach (which rendered correctly once
 * its own path bug was fixed, but is still fundamentally limited to however many texels the FreeType
 * rasterizer baked at a fixed size — MSDF instead stores a resolution-INDEPENDENT distance field, decoded and
 * antialiased live in the fragment shader using screen-space derivatives ({@code fwidth}), so it stays crisp
 * at any GUI scale or render size, not just the one it was baked at).
 *
 * <p>Every atlas (PNG + BMFont-style JSON metrics) was generated with {@code msdf-bmfont-xml} — a real,
 * widely-used open-source tool wrapping {@code msdfgen} (the reference MSDF implementation) — not hand-rolled
 * here; generating a correct multi-channel distance field (with proper edge-coloring to avoid rounding sharp
 * corners) is a real, easy-to-get-wrong algorithm in its own right, so using the established tool for that one
 * step keeps this class's own job to "load the result and draw it," the same division of labor a real
 * MSDF-based text renderer (e.g. ModernUI-MC's own, which the user pointed to) would use. Per user request
 * ("Found two fonts i like. Wire in medium for both."), this loads any of several bundled fonts by
 * {@link FontChoice} rather than one hardcoded atlas — none of the rendering logic below is specific to any
 * one font, only {@link FontChoice}'s own resource paths are, so adding another bundled choice later is just
 * generating its atlas the same way and adding one more enum entry.
 *
 * <p>Renders through a single real custom {@link RenderPipeline} (registered once via
 * {@link #ensurePipelineRegistered()}, shared by every {@link FontChoice} since only the bound texture differs
 * per font, not the pipeline/shader), built with the exact same {@code BindGroupLayouts}/{@code ColorTargetState}/
 * {@code VertexFormat}/{@code PrimitiveTopology} configuration vanilla's own {@code GUI_TEXT} pipeline uses
 * (confirmed via decompiling the real client jar) — only the vertex/fragment shaders differ (this class's own
 * {@code msdf_text.vsh}/{@code .fsh}, the fragment one doing the actual MSDF median-channel decode). Fabric
 * API's own {@code FabricRenderPipeline.Builder} extension (confirmed present on the real
 * {@code RenderPipeline.Builder} class) is what makes registering a custom pipeline from a mod a real,
 * supported operation rather than a fragile internal hack.
 *
 * <p>Each glyph is drawn via the existing {@link GuiGraphicsExtractor#blit} textured-quad helper (already
 * proven to work with a custom pipeline + a non-1:1 source/destination size — see {@code RenderUtil}'s own
 * supersampled corner-mask blits) rather than hand-rolled vertex-buffer submission, using the exact glyph
 * pixel rectangle straight from the BMFont JSON as the {@code u/v/uWidth/vHeight} source region — no need to
 * reimplement quad/vertex emission at all.
 *
 * <p><b>Width/measurement never disagrees with rendering</b>, unlike the old (removed) Java2D renderer's own
 * "REALLY scuffed" positioning bug: {@link #width(String)} and {@link #draw} both read the exact same parsed
 * {@link BmChar#xadvance}/kerning data, so there is no separate measurement code path that could drift out of
 * sync with what actually gets drawn.
 */
public final class MsdfFont {
	private MsdfFont() {}

	/** Every bundled MSDF font choice. Adding one more bundled font is: generate its atlas with the same
	 *  {@code msdf-bmfont-xml} recipe (font-size 42, distance range 16, padding 16, this project's own
	 *  charset), drop the PNG/JSON/{@code .mcmeta} into {@code textures/font}/{@code font}, and add one more
	 *  entry here — nothing else in this class is font-specific. {@code id} is the stable persisted-config
	 *  key (see {@code CustomMenuFontFeature}), never the display name, so renaming a font's label later can't
	 *  silently reset anyone's saved choice. */
	public enum FontChoice {
		QUICKSAND("quicksand", "Quicksand", "font/quicksand_msdf_metrics.json", "textures/font/quicksand_msdf.png", 0f),
		// Per user request ("Dm sans needs to be a pixel bigger" / later, after trying Plus Jakarta Sans a
		// pixel smaller: "Plus jakarta sans needs to be a pixel bigger again, it looks worse like this" — back
		// to matching the shared base size): each font's own apparent size at the SAME nominal
		// TARGET_LINE_HEIGHT can read a little different depending on how "full" its glyphs sit in their own
		// em-square, so each FontChoice carries a small adjustment on top of the shared base size instead of
		// every font being forced to look identical at a given line-height number.
		DM_SANS("dm_sans", "DM Sans", "font/dm_sans_msdf_metrics.json", "textures/font/dm_sans_msdf.png", 1f),
		PLUS_JAKARTA_SANS("plus_jakarta_sans", "Plus Jakarta Sans", "font/plus_jakarta_sans_msdf_metrics.json", "textures/font/plus_jakarta_sans_msdf.png", 0f);

		public final String id;
		public final String displayName;
		private final String metricsResourcePath;
		private final String atlasTexturePath;
		private final float lineHeightAdjust;

		FontChoice(String id, String displayName, String metricsResourcePath, String atlasTexturePath, float lineHeightAdjust) {
			this.id = id;
			this.displayName = displayName;
			this.metricsResourcePath = metricsResourcePath;
			this.atlasTexturePath = atlasTexturePath;
			this.lineHeightAdjust = lineHeightAdjust;
		}

		/** The SAME font, but bundled as a real vanilla custom-font resource (TTF + provider JSON, one per
		 *  {@code size} 10-15 — e.g. {@code assets/skyblocksimplified/font/quicksand_12.json}) rather than an
		 *  MSDF atlas — used only by the "Replace Minecraft Font Globally" option's own font-size slider,
		 *  which redirects vanilla's own default font resolution (see {@code StyleDefaultFontMixin}) rather
		 *  than going through this class's MSDF pipeline at all. Kept as a separate resource on purpose:
		 *  vanilla's text pipeline has no hook for an MSDF decode, so anything rendered through vanilla's own
		 *  Font/Style system needs a font vanilla can rasterize itself (FreeType), not our atlas. Each of
		 *  these provider JSONs also references {@code minecraft:default} as a fallback provider — per user
		 *  request ("doesnt support characters like stars and such... make the font ignore them"), rather
		 *  than a tofu/missing-glyph box, any character our own bundled TTF has no glyph for falls through to
		 *  vanilla's own default font providers, the same way a resource pack that only reskins the Latin
		 *  glyphs and leaves everything else alone would. */
		public Identifier vanillaFontId(int size) {
			return Identifier.fromNamespaceAndPath("skyblocksimplified", id + "_" + size);
		}

		public static FontChoice byId(String id) {
			for (FontChoice choice : values()) if (choice.id.equals(id)) return choice;
			return QUICKSAND;
		}

		public FontChoice next() {
			FontChoice[] all = values();
			return all[(ordinal() + 1) % all.length];
		}
	}

	// The atlas was baked at this em-square size (msdf-bmfont-xml's own "-s" flag, same for every FontChoice)
	// — scale maps that down to TARGET_LINE_HEIGHT. Bumped up from the original 9px-matching value per user
	// feedback that text read as too small; row heights in MainScreen (24-34px) have enough headroom above
	// vanilla's own ~9px line height that this doesn't collide with anything. A real value to tune by eye once
	// testable — same "trial and error" spirit as the font weight/size already went through.
	private static final float TARGET_LINE_HEIGHT = 13f;

	private static RenderPipeline pipeline;
	private static FontChoice activeChoice = FontChoice.QUICKSAND;
	private static final Map<FontChoice, LoadedFont> loadedFonts = new EnumMap<>(FontChoice.class);

	/** Switches which bundled font every subsequent {@link #draw}/{@link #width} call uses — call this once
	 *  whenever {@code CustomMenuFontFeature}'s own font-choice setting changes (including once at startup,
	 *  from its persisted value) rather than threading the choice through every call site. Each font's own
	 *  atlas/metrics load lazily on first use and stay cached afterward, so switching back and forth is free
	 *  after the first time. */
	public static void setFont(FontChoice choice) {
		activeChoice = choice;
	}

	private static LoadedFont current() {
		return loadedFonts.computeIfAbsent(activeChoice, MsdfFont::load);
	}

	private static LoadedFont load(FontChoice choice) {
		ensurePipelineRegistered();
		BmFont metrics;
		try (var reader = new InputStreamReader(
				MsdfFont.class.getResourceAsStream("/assets/skyblocksimplified/" + choice.metricsResourcePath), StandardCharsets.UTF_8)) {
			metrics = new Gson().fromJson(reader, BmFont.class);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("MsdfFont: failed to load {} MSDF metrics, custom font will not render for it", choice.displayName, e);
			metrics = new BmFont();
			metrics.common = new BmCommon();
			metrics.common.lineHeight = 53;
			metrics.chars = List.of();
			metrics.kernings = List.of();
		}
		float targetLineHeight = TARGET_LINE_HEIGHT + choice.lineHeightAdjust;
		float scale = targetLineHeight / metrics.common.lineHeight;
		Identifier atlasTexture = Identifier.fromNamespaceAndPath("skyblocksimplified", choice.atlasTexturePath);
		return new LoadedFont(metrics, scale, atlasTexture, targetLineHeight);
	}

	private static void ensurePipelineRegistered() {
		if (pipeline != null) return;
		// GLOBALS_SNIPPET (confirmed public on RenderPipelines) supplies the BindGroupLayouts.GLOBALS bind
		// group — this fragment shader's own dynamictransforms.glsl import (for ColorModulator) needs that
		// bound, the same way vanilla's own TEXT_SNIPPET starts from this exact snippet. This pipeline is
		// entirely font-agnostic (only the bound texture differs per FontChoice, set per-draw-call below), so
		// it's registered exactly once regardless of how many fonts get loaded.
		pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("skyblocksimplified", "pipeline/msdf_text"))
			.withVertexShader(Identifier.fromNamespaceAndPath("skyblocksimplified", "core/msdf_text"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("skyblocksimplified", "core/msdf_text"))
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withDepthStencilState(Optional.empty())
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.build());
	}

	/** Same contract as {@code Font.width(String)} — the pixel width this text will actually draw at,
	 *  including kerning, so anything centering/right-aligning against this stays correct. Legacy
	 *  {@code §}-formatting codes (color/style) are recognized and contribute zero width, matching vanilla —
	 *  see {@link #draw}'s own doc comment for why this class has to parse them at all. */
	public static int width(String text) {
		LoadedFont font = current();
		float w = 0f;
		BmChar previous = null;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == FORMATTING_PREFIX && i + 1 < text.length() && isFormattingCode(text.charAt(i + 1))) {
				i++;
				continue;
			}
			BmChar glyph = font.metrics.charsById().get((int) c);
			if (glyph == null) continue;
			if (previous != null) w += kerning(font, previous.id, glyph.id) * font.scale;
			w += glyph.xadvance * font.scale;
			previous = glyph;
		}
		return Math.round(w);
	}

	/** The effective line height at this font's current render scale — for callers laying out multiple
	 *  lines, matching {@code Font.lineHeight}'s own role. */
	public static int lineHeight() {
		return Math.round(current().targetLineHeight);
	}

	/** Per user request ("buttons in the mod menu that have symbols dont work with the font, like the play
	 *  sound button for example. exempt the symbols from the font renderer"): every bundled MSDF atlas was
	 *  generated from a fixed Latin/digit/punctuation charset (see {@code msdf-bmfont-xml}'s own "-c"
	 *  charset flag this project's atlases were built with) — a symbol like "▶" simply has no baked glyph,
	 *  so {@link #width}/{@link #draw} silently skip it (zero-width, invisible) rather than drawing a tofu
	 *  box, which is exactly why that button reads as broken/blank instead of just ugly. Unlike the
	 *  "Replace Minecraft Font Globally" TTF path (which falls through per-glyph to a real vanilla font
	 *  provider automatically, see {@code FontChoice#vanillaFontId}'s own doc comment), this MSDF renderer
	 *  is a totally separate pipeline vanilla's own per-glyph provider fallback can't reach into — so
	 *  callers check this first and route the whole string to vanilla rendering instead when it's false,
	 *  rather than drawing a partial line with silent gaps. Formatting codes are skipped, same as
	 *  {@link #width}, since they never need a glyph either way. */
	public static boolean supportsAllGlyphs(String text) {
		LoadedFont font = current();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == FORMATTING_PREFIX && i + 1 < text.length() && isFormattingCode(text.charAt(i + 1))) {
				i++;
				continue;
			}
			if (!font.metrics.charsById().containsKey((int) c)) return false;
		}
		return true;
	}

	/** Draws {@code text} with its baseline-relative glyph offsets anchored so the overall visual box lines
	 *  up with where {@code graphics.text(vanillaFont, text, x, y, color)} would have drawn it — same (x, y)
	 *  meaning as every other text draw in this menu (top-left of the text's own line), so this is a drop-in
	 *  replacement at each call site, not a different coordinate convention to relearn.
	 *
	 *  <p>Every glyph is laid out and blitted using its exact, already-integer atlas-space metrics (xoffset,
	 *  yoffset, width, height, xadvance, kerning are all whole pixels in the BMFont data) — no per-glyph
	 *  division/rounding at all. The one and only scale from atlas space down to {@link #TARGET_LINE_HEIGHT}
	 *  is applied once, continuously, via the GPU pose matrix ({@link org.joml.Matrix3x2fStack#scale}), the
	 *  same way this screen already scales its panel-title text and spinning icons. This is what makes every
	 *  glyph's own proportions and its position relative to its neighbors come out exactly as authored in the
	 *  atlas: the earlier approach of scaling each glyph's box on the CPU and rounding it to whole destination
	 *  pixels independently per glyph is what caused thin/tall glyphs (l, g) and wide ones (m) to visibly drift
	 *  or squish relative to the rest of a line — a discrete rounding error that a single continuous GPU-side
	 *  transform can't introduce in the first place.
	 *
	 *  <p>Also parses legacy {@code §}-formatting codes rather than drawing them as literal glyphs: several
	 *  call sites pass raw strings like {@code "§7Paste configs here"} expecting vanilla's own
	 *  {@code Component}/{@code Font} handling to strip the color code — this renderer draws plain
	 *  {@code String}s with no such preprocessing, so left unhandled the "§" (no glyph, silently skipped)
	 *  and the following digit/letter (a real glyph) would draw as literal text, e.g. a stray "7". Color codes
	 *  (0-9, a-f) swap in the matching vanilla chat color (keeping the caller's own alpha), "r" resets to the
	 *  color the caller passed in, and style codes (k/l/m/n/o — obfuscated/bold/strikethrough/underline/italic)
	 *  are recognized and consumed but have no visual effect (not supported by this renderer). */
	public static void draw(GuiGraphicsExtractor graphics, String text, float x, float y, int color) {
		LoadedFont font = current();
		if (font.metrics.chars.isEmpty()) return;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(font.scale, font.scale);
		int penX = 0;
		int currentColor = color;
		BmChar previous = null;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == FORMATTING_PREFIX && i + 1 < text.length()) {
				char code = Character.toLowerCase(text.charAt(i + 1));
				int colorIndex = formattingColorIndex(code);
				if (colorIndex >= 0) {
					currentColor = (color & 0xFF000000) | FORMATTING_COLORS[colorIndex];
					i++;
					continue;
				} else if (code == 'r') {
					currentColor = color;
					i++;
					continue;
				} else if (isStyleCode(code)) {
					i++;
					continue;
				}
			}
			BmChar glyph = font.metrics.charsById().get((int) c);
			if (glyph == null) { previous = null; continue; }
			if (previous != null) penX += kerning(font, previous.id, glyph.id);
			if (glyph.width > 0 && glyph.height > 0) {
				graphics.blit(pipeline, font.atlasTexture, penX + glyph.xoffset, glyph.yoffset, (float) glyph.x, (float) glyph.y,
					glyph.width, glyph.height, glyph.width, glyph.height, font.metrics.common.scaleW, font.metrics.common.scaleH, currentColor);
			}
			penX += glyph.xadvance;
			previous = glyph;
		}
		pose.popMatrix();
	}

	private static int kerning(LoadedFont font, int first, int second) {
		Integer amount = font.metrics.kerningByPair().get((((long) first) << 32) | (second & 0xFFFFFFFFL));
		return amount != null ? amount : 0;
	}

	// Standard vanilla ChatFormatting palette (0-9, a-f), RGB only — alpha always comes from the caller's color.
	private static final char FORMATTING_PREFIX = '§';
	private static final int[] FORMATTING_COLORS = {
		0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
	};

	private static int formattingColorIndex(char lowerCode) {
		if (lowerCode >= '0' && lowerCode <= '9') return lowerCode - '0';
		if (lowerCode >= 'a' && lowerCode <= 'f') return 10 + (lowerCode - 'a');
		return -1;
	}

	private static boolean isStyleCode(char lowerCode) {
		return switch (lowerCode) {
			case 'k', 'l', 'm', 'n', 'o' -> true;
			default -> false;
		};
	}

	private static boolean isFormattingCode(char code) {
		char lower = Character.toLowerCase(code);
		return formattingColorIndex(lower) >= 0 || lower == 'r' || isStyleCode(lower);
	}

	/** One loaded {@link FontChoice}'s parsed metrics plus its derived render scale and atlas texture —
	 *  everything {@link #draw}/{@link #width} need that varies per font, bundled so {@link #current()} has a
	 *  single cached object to hand back instead of several parallel per-font maps. */
	private static final class LoadedFont {
		final BmFont metrics;
		final float scale;
		final Identifier atlasTexture;
		final float targetLineHeight;

		LoadedFont(BmFont metrics, float scale, Identifier atlasTexture, float targetLineHeight) {
			this.metrics = metrics;
			this.scale = scale;
			this.atlasTexture = atlasTexture;
			this.targetLineHeight = targetLineHeight;
		}
	}

	// --- BMFont JSON schema (msdf-bmfont-xml's own "-f json" output) — only the fields actually used. ---

	private static final class BmChar {
		int id;
		int x, y, width, height, xoffset, yoffset, xadvance;
	}

	private static final class BmCommon {
		int lineHeight;
		int scaleW, scaleH;
	}

	private static final class BmKerning {
		int first, second, amount;
	}

	private static final class BmFont {
		BmCommon common;
		List<BmChar> chars;
		List<BmKerning> kernings;

		// Built lazily right after Gson deserialization — see the field initializer trick below, since Gson
		// itself only ever populates the declared fields above, never anything derived from them.
		private transient Map<Integer, BmChar> charsById;
		private transient Map<Long, Integer> kerningByPair;

		// Gson doesn't call this constructor for deserialized instances (it uses Unsafe field-by-field
		// construction), so the lookup maps are actually built lazily on first real access instead — see
		// charsById()/kerningByPair() below. Kept as plain fields (not methods) for the simplest possible
		// call-site syntax in width()/draw() above, with lazy-init logic tucked into their own accessors.
		Map<Integer, BmChar> charsById() {
			if (charsById == null) {
				charsById = new HashMap<>();
				for (BmChar c : chars) charsById.put(c.id, c);
			}
			return charsById;
		}

		Map<Long, Integer> kerningByPair() {
			if (kerningByPair == null) {
				kerningByPair = new HashMap<>();
				for (BmKerning k : kernings) kerningByPair.put((((long) k.first) << 32) | (k.second & 0xFFFFFFFFL), k.amount);
			}
			return kerningByPair;
		}
	}
}
