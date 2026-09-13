package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.DisableEndermanDeathAnimationFeature;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Disable Enderman Dying Animation" — the real vanilla death-flop rotation (LivingEntityRenderer.
 * setupRotations, confirmed via decompiling this class: {@code if (state.deathTime > 0.0F)
 * poseStack.mulPose(Axis.ZP.rotationDegrees(fall * getFlipDegrees()))}) reads its input purely off the
 * render STATE snapshot (state.deathTime), not the live entity, and that snapshot is built once per frame
 * in extractRenderState ({@code state.deathTime = entity.deathTime > 0 ? entity.deathTime + partialTicks :
 * 0.0F}). Zeroing the STATE field here — right after vanilla's own assignment, only for a real EnderMan and
 * only while the feature is enabled — is enough to make setupRotations' own check never trigger, without the
 * risk a direct redirect of the rotation call itself would carry: that same method applies several other
 * unrelated rotations (body-facing, auto-spin-attack, sleeping) via the identical PoseStack.mulPose
 * (Quaternionfc) overload, which a same-target @Redirect could only disambiguate with a fragile ordinal.
 * The red hurt-flash overlay (state.hasRedOverlay, set from the real entity.deathTime a few lines earlier
 * in the same method) is deliberately left untouched — only the fall-over rotation is meant to go away, not
 * all death feedback.
 *
 * <p>Mixed into the shared LivingEntityRenderer base (not EndermanRenderer) since extractRenderState always
 * runs there for every mob — if EndermanRenderer has its own override at all, it would still call super()
 * first, so this still fires either way; the entity-type check below is what actually scopes this to
 * Endermen only.
 */
@Mixin(LivingEntityRenderer.class)
public class EndermanDeathFlopMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void skyblocksimplified$suppressEndermanDeathFlop(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		if (!(entity instanceof EnderMan)) return;
		DisableEndermanDeathAnimationFeature feature = FeatureRegistry.get("disable_enderman_death_animation") instanceof DisableEndermanDeathAnimationFeature f ? f : null;
		if (feature == null || !feature.isEnabled()) return;
		state.deathTime = 0.0F;
	}
}
