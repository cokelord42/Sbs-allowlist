package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.FullbrightFeature;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code Options.gamma()} is an {@code OptionInstance<Double>} backed by {@code UnitDouble}, whose
 *  {@code validateValue} rejects anything outside [0, 1] — calling {@code .set(1500.0)} directly (the
 *  first approach tried) silently fails validation and resets the option back to its 0.5 default instead
 *  of erroring loudly, which is why the module visibly did nothing. The lightmap shader itself has no such
 *  clamp: {@code color = mix(color, notGamma(color), BrightnessFactor)} happily extrapolates BrightnessFactor
 *  values far past 1.0 toward full white before the GPU clamps the final UNORM texture write, so overriding
 *  {@link LightmapRenderState#brightness} directly (after {@link LightmapRenderStateExtractor#extract} sets
 *  it from the real option) is the real point of control — same value, just past the option's own gate. */
@Mixin(LightmapRenderStateExtractor.class)
public class FullbrightMixin {
	@Inject(method = "extract", at = @At("RETURN"))
	private void skyblocksimplified$applyFullbright(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
		if (FeatureRegistry.get("performance_fullbright") instanceof FullbrightFeature feature && feature.isEnabled()) {
			renderState.brightness = FullbrightFeature.FULLBRIGHT_BRIGHTNESS;
			// Per user report, some spots stayed dark even at a huge BrightnessFactor. The shader's notGamma()
			// curve divides by the pixel's own max color component (maxScaled / maxComponent), which is
			// undefined for a genuinely pure-black pixel (no ambient/sky/block light reaching it at all,
			// common in unlit dungeon corners) — BrightnessFactor alone can't brighten something that curve
			// can't process. Forcing the ambient base color to full white guarantees the shader's starting
			// color is never pure black, sidestepping that edge case entirely.
			renderState.ambientColor = new Vector3f(1f, 1f, 1f);
		}
	}
}
