package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.mixin.MinecraftFontAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;

/**
 * Replaces {@link Minecraft#font} itself — the one {@link Font} instance every vanilla screen and every
 * OTHER mod's screen reads from — with one that always resolves to a user-imported font's
 * {@link GlyphSource}, regardless of what {@code FontDescription} a {@code Style} asks for. Same
 * redirect-the-{@code Font.Provider} trick {@link ModFont} already uses to scope Quicksand to just this
 * mod's own menu, just applied to the ACTUAL field every other screen reads instead of building a
 * separate, differently-scoped {@code Font} object.
 *
 * <p>Known limitation (documented rather than silently broken): a REAL vanilla resource reload (changing
 * resource packs, F3+T, language change, etc.) rebuilds {@code Minecraft.font} from scratch and would
 * silently discard this override. {@link com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature}
 * re-applies it every tick as a cheap safety net (a single reference-equality check) so it recovers
 * automatically after any such reload while the game-wide mode is active, rather than needing the user to
 * reopen the mod menu to restore it.
 */
public final class GameWideFontOverride {
	private GameWideFontOverride() {}

	private static Font vanillaFont;
	private static Font overrideFont;

	public static void apply(GlyphSource source, EffectGlyph effect) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.font == null) return;
			if (vanillaFont == null) vanillaFont = mc.font;
			Font.Provider provider = new Font.Provider() {
				@Override
				public GlyphSource glyphs(net.minecraft.network.chat.FontDescription description) {
					return source;
				}

				@Override
				public EffectGlyph effect() {
					return effect;
				}
			};
			overrideFont = new Font(provider);
			((MinecraftFontAccessor) mc).skyblocksimplified$setFont(overrideFont);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("GameWideFontOverride: failed to apply imported font game-wide, leaving vanilla font in place", e);
		}
	}

	/** Cheap per-tick check: a real resource reload rebuilds {@code Minecraft.font} out from under this
	 *  override without notifying us — if that happened, {@code mc.font} is no longer our own
	 *  {@code overrideFont} instance, so re-apply against the (now-updated) vanilla font as the new
	 *  restore point isn't needed since the source/effect are still the same cached ones; this just
	 *  re-installs the override on top of whatever vanilla just rebuilt. */
	public static boolean isStillApplied() {
		Minecraft mc = Minecraft.getInstance();
		return overrideFont != null && mc.font == overrideFont;
	}

	public static void clear() {
		if (vanillaFont == null) return;
		try {
			Minecraft mc = Minecraft.getInstance();
			((MinecraftFontAccessor) mc).skyblocksimplified$setFont(vanillaFont);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("GameWideFontOverride: failed to restore the vanilla font", e);
		} finally {
			overrideFont = null;
		}
	}
}
