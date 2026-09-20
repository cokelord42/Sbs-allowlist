package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.highlight.EntityRenderStateAlphaAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies EntityTransparencyRegistry's per-entity alpha to the actual model draw call — descriptors and
 * approach confirmed against SkyHanni's own MixinRendererLivingEntity.java (same 26.1+ renderer refactor
 * this project targets: submit() now hands whole models to a SubmitNodeCollector instead of drawing them
 * directly, and getRenderType must also be forced translucent or the reduced alpha argument is silently
 * ignored by an opaque render type).
 *
 * Standard @ModifyArg handlers only reliably receive the one captured argument, not the enclosing
 * method's other parameters (confirmed by SkyHanni routing around the same limitation with its own
 * "current entity" static hook rather than trying to capture state directly in modifyRenderAlpha) — so
 * the render state is stashed in a plain static field at submit()'s HEAD/RETURN instead of captured.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityAlphaMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>>
	extends EntityRenderer<T, S>
	implements RenderLayerParent<S, M> {

	private static LivingEntityRenderState skyblocksimplified$currentState;

	@Shadow
	public abstract Identifier getTextureLocation(LivingEntityRenderState state);

	protected LivingEntityAlphaMixin(EntityRendererProvider.Context context) {
		super(context);
	}

	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD")
	)
	private void skyblocksimplified$captureState(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
												CameraRenderState camera, CallbackInfo ci) {
		skyblocksimplified$currentState = state;
	}

	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("RETURN")
	)
	private void skyblocksimplified$releaseState(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
												CameraRenderState camera, CallbackInfo ci) {
		skyblocksimplified$currentState = null;
	}

	@ModifyArg(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
		index = 6
	)
	private int skyblocksimplified$modifyRenderAlpha(int argb) {
		if (skyblocksimplified$currentState == null) return argb;
		int alpha = ((EntityRenderStateAlphaAccessor) (Object) skyblocksimplified$currentState).skyblocksimplified$getAlpha();
		if (alpha >= 255) return argb;
		int oldAlpha = (argb >> 24) & 0xFF;
		int newAlpha = Math.min(oldAlpha, alpha);
		return (argb & 0xFFFFFF) | (newAlpha << 24);
	}

	@Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$forceTranslucent(LivingEntityRenderState state, boolean showBody, boolean translucent, boolean showOutline,
													 CallbackInfoReturnable<RenderType> cir) {
		if (!showBody) return;
		int alpha = ((EntityRenderStateAlphaAccessor) (Object) state).skyblocksimplified$getAlpha();
		if (alpha >= 255) return;
		cir.setReturnValue(RenderTypes.entityTranslucentCullItemTarget(this.getTextureLocation(state)));
	}
}
