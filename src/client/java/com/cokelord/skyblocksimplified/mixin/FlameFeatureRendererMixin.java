package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.HideFireFeature;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** "Hide fire on entities aswell" — cancels the whole per-frame flame-model batch (the fire wrapped
 *  around any burning entity: mobs, other players, your own third-person model) rather than modifying
 *  vertex geometry, since buildGroup's own job is purely "build this frame's flame quads" with nothing
 *  else mixed in, so skipping it entirely is a clean, side-effect-free cancel. */
@Mixin(FlameFeatureRenderer.class)
public class FlameFeatureRendererMixin {
	@Inject(method = "buildGroup", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$hideEntityFire(FeatureFrameContext context, List<FlameFeatureRenderer.Submit> submits, CallbackInfo ci) {
		if (FeatureRegistry.get("hide_fire") instanceof HideFireFeature feature && feature.isEnabled() && feature.isHideOnEntities()) {
			ci.cancel();
		}
	}
}
