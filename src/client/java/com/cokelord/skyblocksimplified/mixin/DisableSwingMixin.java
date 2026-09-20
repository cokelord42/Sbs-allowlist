package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Item Animations' "Disable Full Swing Animation" toggle no longer touches attackAnim/getAttackAnim at
 *  all — that was scaling the swing down to a smaller amplitude, but a small amplitude is still an amplitude:
 *  the item still visibly traveled toward the crosshair, just less far, which is NOT what "swing in place"
 *  means per user clarification. Confirmed via decompiling ItemInHandRenderer: the actual toward-crosshair
 *  motion is a separate PoseStack.translate(...) call inside swingArm(), computed from the same swing
 *  progress value but entirely independent of the rotation applyItemArmAttackTransform() applies right
 *  after it — so the real fix is canceling that ONE translate call (see ItemInHandRendererMixin), not
 *  scaling the progress value feeding both. This class now only handles the swing-speed duration override.
 *
 *  Swing Speed is an absolute duration spec (0.1 -> 10s full swing, 1.0 -> instant), not a multiplier on
 *  the item's own vanilla timing — see getCurrentSwingDuration below. Independent of Disable Full Swing:
 *  gated purely on the module being enabled, since it's its own separate row in the settings panel.
 *
 *  Scoped to the local player only via an identity check, so other players' swings are untouched. */
@Mixin(LivingEntity.class)
public class DisableSwingMixin {
	private static final float TICK_MILLIS = 50f;
	private static final float MAX_SWING_DURATION_MILLIS = 10_000f;

	@Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
	private void skyblocksimplified$overrideSwingSpeed(CallbackInfoReturnable<Integer> cir) {
		if ((LivingEntity) (Object) this != Minecraft.getInstance().player) return;
		if (!(FeatureRegistry.get("item_animations") instanceof ItemAnimationsFeature feature) || !feature.isEnabled()) return;
		float speed = feature.getSwingSpeed();
		float t = (speed - ItemAnimationsFeature.MIN_SWING_SPEED) / (ItemAnimationsFeature.MAX_SWING_SPEED - ItemAnimationsFeature.MIN_SWING_SPEED);
		float durationMillis = MAX_SWING_DURATION_MILLIS * (1f - t);
		// A literal 0-tick duration risks a divide-by-zero wherever vanilla computes attackAnim as
		// swingTime / this value — 1 tick is as close to "instant" as it can safely get.
		int ticks = Math.max(1, Math.round(durationMillis / TICK_MILLIS));
		cir.setReturnValue(ticks);
	}
}
