package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.highlight.EntityRenderStateAlphaAccessor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements EntityRenderStateAlphaAccessor {
	@Unique
	private int skyblocksimplified$alpha = 255;

	@Override
	public int skyblocksimplified$getAlpha() {
		return skyblocksimplified$alpha;
	}

	@Override
	public void skyblocksimplified$setAlpha(int alpha) {
		skyblocksimplified$alpha = alpha;
	}
}
