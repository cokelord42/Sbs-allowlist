package com.cokelord.skyblocksimplified.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@link AbstractTexture}'s private-in-practice {@code sampler} field so subclass mixins (like
 *  {@code FontTextureFilterMixin}, targeting {@code FontTexture}) can replace it — a plain {@code @Shadow}
 *  in a mixin targeting the SUBCLASS doesn't work for a field declared on the superclass (confirmed by a
 *  real crash: "InvalidMixinException: @Shadow field sampler was not located in the target class
 *  FontTexture"), so this accessor targets {@link AbstractTexture} itself instead. */
@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {
	@Accessor("sampler")
	void skyblocksimplified$setSampler(GpuSampler sampler);
}
