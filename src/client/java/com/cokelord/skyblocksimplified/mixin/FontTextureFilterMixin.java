package com.cokelord.skyblocksimplified.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Real root cause of "the mod font still looks bad/not smooth" (per multiple user reports across several
 * rounds): confirmed via bytecode inspection that vanilla's {@code FontTexture} unconditionally samples its
 * baked glyph atlas with {@code FilterMode.NEAREST}
 * ({@code RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)} in its constructor) — correct for
 * vanilla's own pixel-art bitmap fonts (unifont/ascii), which are designed to look crisp at nearest-neighbor,
 * but wrong for a smooth TTF font like Quicksand, whose baked (even 16x-oversampled) glyph bitmaps get
 * visibly jagged/aliased edges once GUI scale stretches them to a size that isn't an exact 1:1 pixel match.
 * No font.json setting controls this — it's hardcoded in vanilla's own {@code FontTexture} constructor.
 *
 * <p>Scoped ONLY to this mod's own Quicksand atlas pages, never vanilla's fonts: each glyph atlas page is
 * built with a real debug-name {@link Supplier} whose string is that page's backing
 * {@code Identifier.toString()} (confirmed via bytecode — {@code GlyphStitcher}'s {@code texturePrefix} is
 * literally the requested font id itself, e.g. {@code skyblocksimplified:quicksand/0}), so checking that
 * string for this mod's namespace can never touch {@code minecraft:default}/{@code minecraft:uniform}/etc.
 */
@Mixin(FontTexture.class)
public abstract class FontTextureFilterMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void skyblocksimplified$smoothModFont(Supplier<String> debugName, GlyphRenderTypes renderTypes, boolean colored, CallbackInfo ci) {
		String name;
		try {
			name = debugName.get();
		} catch (Exception e) {
			return;
		}
		if (name == null || !name.contains("skyblocksimplified")) return;
		((AbstractTextureAccessor) (Object) this).skyblocksimplified$setSampler(RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
	}
}
