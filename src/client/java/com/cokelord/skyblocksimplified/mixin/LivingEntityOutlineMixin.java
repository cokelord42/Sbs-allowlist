package com.cokelord.skyblocksimplified.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Belt-and-suspenders fix for the 3D highlight outline not rendering despite the color/glow-forcing half of
 * the pipeline demonstrably working (EntityRendererMixin sets state.outlineColor, isCurrentlyGlowing() is
 * forced true, DebugLog confirms both firing). Traced the real mapped MC source for this build
 * (LivingEntityRenderer.submit -> SubmitNodeCollection.submitModel) and confirmed the *architecturally
 * correct* path only actually pushes an outline draw when
 * {@code getOutlineRenderType(baseRenderType)} returns non-null — which requires the entity's own normal
 * (non-outline) RenderType to have been built with AFFECTS_OUTLINE. Every standard render-type factory
 * defaults that to true, but there's no way to confirm from static analysis alone that the SPECIFIC vanilla
 * mob model Hypixel's server reskins for a given pest (never confirmed — could be Silverfish, Bat, Slime,
 * etc.) actually goes through one of those defaulting factories rather than a model-specific renderType
 * function that built its RenderType with the outline flag off.
 *
 * <p>Rather than depend on that per-entity-type render type at all, this submits an explicit second outline
 * draw directly with {@code RenderTypes.outline(texture)} — the same render type vanilla's own invisible-
 * glowing-entity trick uses, guaranteed to hit the outline bucket regardless of what the entity's normal
 * body render type supports. For an entity that already outlines fine on its own (vanilla glow, or a pest
 * whose model type turns out to support it after all) this is a harmless redundant submission of the same
 * color to the same bucket; for one that doesn't, it's the only thing that actually gets it on screen.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(LivingEntityRenderer.class)
public class LivingEntityOutlineMixin {
	@Inject(method = "submit", at = @At("TAIL"))
	private void skyblocksimplified$forceOutlineSubmit(LivingEntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, CameraRenderState camera, CallbackInfo ci) {
		if (state.outlineColor == 0) return;
		try {
			LivingEntityRenderer self = (LivingEntityRenderer) (Object) this;
			Identifier texture = self.getTextureLocation(state);
			if (texture == null) return;
			Model model = self.getModel();
			if (model == null) return;
			RenderType outlineType = RenderTypes.outline(texture);
			submitNodeCollector.submitModel(model, state, poseStack, outlineType, 15728880,
				OverlayTexture.NO_OVERLAY, -1, null, state.outlineColor, null);
		} catch (Exception e) {
			// Cosmetic-only — never let a failed extra outline submission take down the entity's normal render.
		}
	}
}
