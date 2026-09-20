package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.CameraFeature;
import net.minecraft.client.CameraType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@code CameraType.cycle()} is the sole method F5's key-toggle handler calls to advance the perspective
 *  (FIRST_PERSON -> THIRD_PERSON_BACK -> THIRD_PERSON_FRONT -> FIRST_PERSON). Intercepting the result here,
 *  right at the enum's own cycle step, is the narrowest possible hook — skips straight to FIRST_PERSON
 *  whenever the cycle would have landed on THIRD_PERSON_FRONT, preserving the forward direction of the
 *  cycle instead of just refusing the keypress (which would otherwise get "stuck" showing the front view
 *  as if nothing happened). */
@Mixin(CameraType.class)
public class CameraTypeMixin {
	@Inject(method = "cycle", at = @At("RETURN"), cancellable = true)
	private void skyblocksimplified$skipFrontView(CallbackInfoReturnable<CameraType> cir) {
		if (cir.getReturnValue() != CameraType.THIRD_PERSON_FRONT) return;
		if (FeatureRegistry.get("camera") instanceof CameraFeature feature && feature.isEnabled() && feature.isDisableFrontView()) {
			cir.setReturnValue(CameraType.FIRST_PERSON);
		}
	}
}
