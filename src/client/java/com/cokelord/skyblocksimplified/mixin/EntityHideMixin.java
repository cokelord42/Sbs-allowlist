package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cancels rendering entirely for any entity EntityHideRegistry says to hide (e.g. Hypixel's fake
 *  "damage splash" ArmorStands) — a separate mixin from EntityRendererMixin since this needs to
 *  short-circuit shouldRender with a real return value, not just tweak the extracted render state. */
@Mixin(EntityRenderer.class)
public class EntityHideMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$hideEntity(Entity entity, Frustum frustum, double camX, double camY, double camZ,
											  CallbackInfoReturnable<Boolean> cir) {
		// Never hide a dungeon key currently tracked by Key Highlight — see that feature's own
		// isProtectedKeyEntity doc comment (per user report: "Hide Superboom TNT" was somehow eating the
		// Blood Key right after killing the mob that drops it). Checked first, before any hide rule.
		if (com.cokelord.skyblocksimplified.feature.impl.KeyHighlightFeature.isProtectedKeyEntity(entity)) return;
		if (EntityHideRegistry.shouldHide(entity)) {
			cir.setReturnValue(false);
		}
	}
}
