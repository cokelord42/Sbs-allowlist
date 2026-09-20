package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Animations: user-adjustable position/rotation/scale for the currently held first-person item, plus
 * toggles to skip the swing and item-swap tweens entirely — real transform applied on top of vanilla's own
 * (renderItem already has vanilla's swing/bob baked into the PoseStack by the time this runs, so this adds
 * to it rather than replacing it), not a fabricated preview.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
	@Inject(method = "renderItem", at = @At("HEAD"))
	private void skyblocksimplified$applyTransform(LivingEntity entity, ItemStack stack, ItemDisplayContext displayContext,
												  PoseStack poseStack, SubmitNodeCollector collector, int packedLight, CallbackInfo ci) {
		ItemAnimationsFeature feature = feature();
		if (feature == null || !feature.isEnabled()) return;
		if (entity != Minecraft.getInstance().player) return;
		if (displayContext != ItemDisplayContext.FIRST_PERSON_LEFT_HAND && displayContext != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) return;

		poseStack.translate(feature.getOffsetX(), feature.getOffsetY(), feature.getOffsetZ());
		poseStack.mulPose(Axis.YP.rotationDegrees(feature.getYaw()));
		poseStack.mulPose(Axis.XP.rotationDegrees(feature.getPitch()));
		poseStack.mulPose(Axis.ZP.rotationDegrees(feature.getRoll()));
		poseStack.scale(feature.getScale(), feature.getScale(), feature.getScale());
	}

	// Injected at HEAD (before vanilla's own tick body), not TAIL — forcing height to 1 AFTER vanilla ran
	// used to mean vanilla's own "if (mainHandHeight < 0.1F) mainHandItem = newItem" swap gate could never
	// fire (we always undid the dip that gate depends on before it could trip), so the rendered item just
	// silently never changed on a real swap. Instead, this preemptively assigns the tracked item fields to
	// whatever's actually equipped right now — mirroring vanilla's own shouldInstantlyReplaceVisibleItem
	// fast path (already used for same-item stack-count changes) but applied unconditionally — then pins
	// height at 1 so vanilla's own logic (which runs right after, sees mainHandItem already matching, and
	// computes a "no change needed" target) has nothing left to animate.
	@Inject(method = "tick", at = @At("HEAD"))
	private void skyblocksimplified$skipSwapTween(CallbackInfo ci) {
		ItemAnimationsFeature feature = feature();
		if (feature == null || !feature.isEnabled() || !feature.isDisableItemSwapping()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		ItemInHandRendererAccessor accessor = (ItemInHandRendererAccessor) this;
		accessor.skyblocksimplified$setMainHandItem(mc.player.getMainHandItem());
		accessor.skyblocksimplified$setOffHandItem(mc.player.getOffhandItem());
		accessor.skyblocksimplified$setMainHandHeight(1.0f);
		accessor.skyblocksimplified$setOMainHandHeight(1.0f);
		accessor.skyblocksimplified$setOffHandHeight(1.0f);
		accessor.skyblocksimplified$setOOffHandHeight(1.0f);
	}

	// "Disable Full Swing Animation" — real fix for "swings in place, doesn't travel toward the crosshair".
	// Confirmed via decompiling this class: swingArm(swingProgress, poseStack, arm, humanoidArm) does
	// exactly one PoseStack.translate(arm * x, y, z) call (the toward-crosshair push, x/y/z all derived
	// from swingProgress) immediately before calling applyItemArmAttackTransform(poseStack, humanoidArm,
	// swingProgress) for the rotation — same swingProgress value feeding both, but the translate and the
	// rotate are two totally independent calls. Redirecting just this translate to a no-op cancels the
	// positional travel while leaving the rotation untouched.
	// Per user request ("It should still swing, but the swing animation should happen in place, i.e the sword
	// itself shouldnt move places on the screen but the animation should still trigger indicating a swing.
	// Devonian has this, port theirs"): "In Place Swing" reuses this exact same translate-cancel (Devonian's
	// own equivalent does the identical thing — see ItemAnimationsFeature's own doc comment) but WITHOUT also
	// cancelling the rotate below, so the item still visibly swings via rotation, just without physically
	// traveling toward the crosshair.
	@Redirect(method = "swingArm", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void skyblocksimplified$cancelSwingTranslate(PoseStack instance, float x, float y, float z) {
		ItemAnimationsFeature feature = feature();
		if (feature != null && feature.isEnabled() && (feature.isDisableFullSwing() || feature.isInPlaceSwing())) return;
		instance.translate(x, y, z);
	}

	// Real bug found (per user report — the item still visibly swings/rotates in place after the above:
	// "Disable full swing animation not fully cancelling swing"): cancelling only the translate was a
	// deliberate earlier choice to keep the rotation while removing just the toward-crosshair push, but a
	// module literally named "Disable FULL Swing Animation" reads as "no visible swing motion at all" — the
	// still-rotating arm doesn't meet that. This redirects the applyItemArmAttackTransform call itself (the
	// rotation half of the same two-call sequence above) to a no-op when the toggle is on, via the invoker
	// on ItemInHandRendererAccessor since the real method is private and can't be called directly from a
	// separate mixin class. Falls through to the real call unchanged when the toggle is off.
	@Redirect(method = "swingArm", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;applyItemArmAttackTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V"))
	private void skyblocksimplified$cancelSwingRotate(ItemInHandRenderer instance, PoseStack poseStack,
													   net.minecraft.world.entity.HumanoidArm arm, float swingProgress) {
		ItemAnimationsFeature feature = feature();
		if (feature != null && feature.isEnabled() && feature.isDisableFullSwing()) return;
		((ItemInHandRendererAccessor) instance).skyblocksimplified$callApplyItemArmAttackTransform(poseStack, arm, swingProgress);
	}

	// "Disable hand swaying" — vanilla rotates the whole held-item PoseStack by 10% of the player's raw
	// view pitch/yaw right at the top of submitHandsWithItems (before either hand is even chosen), which is
	// exactly the "held item lags/sways when you move your mouse" effect: confirmed via decompiling this
	// class, two back-to-back calls — mulPose(Axis.XP.rotationDegrees((viewXRot - xBob) * 0.1F)) and the Y
	// equivalent — applied to nothing but this same PoseStack, before any attack/swap/bob transform runs.
	// Both calls share the identical mulPose(Quaternionfc) signature, so one @Redirect (no ordinal needed)
	// cancels both at once, leaving every other transform (attack swing, item-swap height, walk bob inside
	// submitArmWithItem) completely untouched.
	@Redirect(method = "submitHandsWithItems", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V"))
	private void skyblocksimplified$cancelViewSway(PoseStack instance, org.joml.Quaternionfc rotation) {
		ItemAnimationsFeature feature = feature();
		if (feature != null && feature.isEnabled() && feature.isDisableHandSwaying()) return;
		instance.mulPose(rotation);
	}

	private static ItemAnimationsFeature feature() {
		return FeatureRegistry.get("item_animations") instanceof ItemAnimationsFeature f ? f : null;
	}
}
