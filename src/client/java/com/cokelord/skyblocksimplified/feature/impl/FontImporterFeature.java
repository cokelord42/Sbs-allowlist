package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.gui.CustomFontLoader;
import com.cokelord.skyblocksimplified.gui.GameWideFontOverride;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Per user request ("go ahead and build it. It needs a reset button, import button (allows users to select
 * a file) and a cog... Inside the cog users should be able to choose if they want to replace the minecraft
 * font aswell. or just keep it inside the mod"): a toggle-less "cog only" module (same structural template
 * as {@link PanelThemeFeature} — {@code isToggleable() == false}, subcategory "Mod settings") letting a user
 * pick their own {@code .ttf} (or a {@code .zip} containing one), either scoped to just this mod's own menu
 * or applied to the ENTIRE game via {@link GameWideFontOverride}.
 *
 * <p>Per a later user request ("reset the font to default minecraft and revert it to how it was before
 * quicksand. Its causing too many issues. Keep the font importer however incase people want that, even if
 * most fonts probably wont work"): this mod no longer bundles a default custom font at all — the mod menu
 * renders with plain vanilla Minecraft font unless a user explicitly imports one here. The reset button now
 * genuinely resets to vanilla, not to a bundled Quicksand.
 *
 * <p>The imported font is copied into the mod's own config directory (not just remembered by path, unlike
 * {@link MobHighlightFeature}'s custom images) since losing text rendering entirely if the user's original
 * file moves is a much worse failure mode than losing a highlight image. Every step — reading the file,
 * baking it via {@link CustomFontLoader} (real FreeType-backed baking, not a guess), applying it — is
 * wrapped so a bad/corrupt file can only ever fail back to vanilla, never break the menu or crash the game.
 */
public class FontImporterFeature extends Feature {
	public enum Scope { MOD_ONLY, GAME_WIDE }

	private static final Path FONT_FILE = FabricLoader.getInstance().getConfigDir().resolve("skyblocksimplified_imported_font.ttf");

	private boolean hasImportedFont = false;
	private Scope scope = Scope.MOD_ONLY;
	private String lastError = null;

	private CustomFontLoader.Loaded loaded;
	private Font cachedMenuFont;

	public FontImporterFeature() {
		// enabledByDefault=true, isToggleable()=false — same pattern as PanelThemeFeature: no visible
		// on/off, onEnable() is just the startup init hook that reloads whatever was already imported.
		super("font_importer", "Font Importer", FeatureCategory.ABOUT, true);
	}

	@Override
	protected void onEnable() {
		if (Files.isRegularFile(FONT_FILE)) {
			try {
				importBytes(Files.readAllBytes(FONT_FILE));
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("FontImporterFeature: failed to reload the previously imported font on startup, falling back to vanilla", e);
				hasImportedFont = false;
				lastError = "Failed to reload saved font: " + e.getMessage();
			}
		}
	}

	@Override
	public void onTick(Minecraft client) {
		// See GameWideFontOverride's own doc comment: a real resource reload silently rebuilds
		// Minecraft.font out from under any override — this is the cheap per-tick recovery for that.
		if (hasImportedFont && scope == Scope.GAME_WIDE && loaded != null && !GameWideFontOverride.isStillApplied()) {
			GameWideFontOverride.apply(loaded.source, loaded.effect);
		}
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	public boolean hasImportedFont() {
		return hasImportedFont;
	}

	public String getLastError() {
		return lastError;
	}

	public Scope getScope() {
		return scope;
	}

	public void setScope(Scope value) {
		if (scope == value) return;
		scope = value;
		applyScope();
	}

	/** Called from the mod menu's Import button click handler with whatever path the native file picker
	 *  returned — {@code .ttf} directly, or a {@code .zip} whose first {@code .ttf} entry gets extracted. */
	public void importFromPath(String pickedPath) {
		try {
			byte[] bytes = readTtfBytes(Path.of(pickedPath));
			Files.createDirectories(FONT_FILE.getParent());
			Files.write(FONT_FILE, bytes);
			importBytes(bytes);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("FontImporterFeature: failed to import font from {}", pickedPath, e);
			lastError = "Failed to import: " + e.getMessage();
			hasImportedFont = false;
		}
	}

	public void reset() {
		hasImportedFont = false;
		lastError = null;
		try {
			Files.deleteIfExists(FONT_FILE);
		} catch (Exception ignored) {}
		GameWideFontOverride.clear();
		com.cokelord.skyblocksimplified.gui.SmoothTextRenderer.clearActiveFont();
		if (loaded != null) {
			loaded.close();
			loaded = null;
		}
		cachedMenuFont = null;
	}

	/** MainScreen's constructor calls this for its own {@code this.font} — delegates back to plain vanilla
	 *  {@code mc.font} whenever no valid import is active (the default state), so this is always safe to call
	 *  unconditionally. */
	public static Font currentMenuFont(Minecraft mc) {
		Feature f = FeatureRegistry.get("font_importer");
		if (f instanceof FontImporterFeature fif && fif.hasImportedFont && fif.cachedMenuFont != null) {
			return fif.cachedMenuFont;
		}
		return mc.font;
	}

	private void importBytes(byte[] ttfBytes) throws Exception {
		// Real bug found (per user report — "Importing a font doesnt change the mod font. Only the
		// minecraft font when i turn it on"): nearly every string in the mod menu now renders through
		// SmoothTextRenderer, which used to hardcode the bundled Quicksand TTF as its own separate Java2D
		// font — completely independent of whatever this class built for `this.font`/GameWideFontOverride.
		// An imported font could change GAME-WIDE rendering (which really does go through a real
		// Minecraft.font swap) but had zero effect on the mod's own menu specifically, the opposite of what
		// "keep inside mod" scope promises. Loading the same bytes as a plain AWT Font here and handing it
		// to SmoothTextRenderer.setActiveFont is what actually makes "keep inside mod" visible.
		java.awt.Font newAwtFont;
		try (java.io.InputStream in = new java.io.ByteArrayInputStream(ttfBytes)) {
			newAwtFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in);
		}
		CustomFontLoader.Loaded newLoaded = CustomFontLoader.load(ttfBytes);
		if (loaded != null) loaded.close();
		loaded = newLoaded;
		cachedMenuFont = buildMenuFont(loaded.source, loaded.effect);
		hasImportedFont = true;
		lastError = null;
		com.cokelord.skyblocksimplified.gui.SmoothTextRenderer.setActiveFont(newAwtFont);
		applyScope();
	}

	private static Font buildMenuFont(GlyphSource source, net.minecraft.client.gui.font.glyphs.EffectGlyph effect) {
		return new Font(new Font.Provider() {
			@Override
			public GlyphSource glyphs(net.minecraft.network.chat.FontDescription description) {
				return source;
			}

			@Override
			public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() {
				return effect;
			}
		});
	}

	private void applyScope() {
		if (hasImportedFont && scope == Scope.GAME_WIDE && loaded != null) {
			GameWideFontOverride.apply(loaded.source, loaded.effect);
		} else {
			GameWideFontOverride.clear();
		}
	}

	/** Reads raw TTF bytes from either a direct {@code .ttf} file or the first {@code .ttf} entry found
	 *  inside a {@code .zip} (case-insensitive extension match, first match wins — good enough for the
	 *  common "font family zip with one weight the user wants" case without needing a full sub-file
	 *  picker UI). */
	private static byte[] readTtfBytes(Path path) throws Exception {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".zip")) {
			try (ZipFile zip = new ZipFile(path.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					if (entry.isDirectory()) continue;
					if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".ttf")) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						return in.readAllBytes();
					}
				}
			}
			throw new IllegalArgumentException("zip contains no .ttf file");
		}
		return Files.readAllBytes(path);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("hasImportedFont", hasImportedFont);
		obj.addProperty("scope", scope.name());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("scope")) {
			try {
				scope = Scope.valueOf(obj.get("scope").getAsString());
			} catch (IllegalArgumentException ignored) {}
		}
		// hasImportedFont itself is re-derived from whether FONT_FILE actually loads in onEnable() rather
		// than trusted blindly from config — a stale "true" here with a since-deleted/corrupt file must
		// fall back to Quicksand, not claim success it can't back up.
	}

	@Override
	public String getDescription() {
		return "Lets you import your own custom font file for the mod's menu (and optionally the whole game) to use.";
	}
}
