package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.LavaToWaterFeature;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Swaps lava's baked fluid model for water's whenever {@link LavaToWaterFeature} is enabled — the same
 *  real model-substitution NoammAddons' confirmed {@code LavaToWater.kt} uses (its {@code modelHook}, since
 *  ported to a real Mixin injection here rather than a manually-registered hook). Since {@link
 *  FluidStateModelSet#get} is looked up per fluid state, recursing into it for water's own state is safe —
 *  water isn't lava, so this same injection is simply a no-op on that inner call. */
@Mixin(FluidStateModelSet.class)
public abstract class LavaFluidModelMixin {
	@Inject(method = "get", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$lavaToWater(FluidState state, CallbackInfoReturnable<FluidModel> cir) {
		if (!(FeatureRegistry.get("lava_to_water") instanceof LavaToWaterFeature feature) || !feature.isEnabled()) return;
		if (state.getType() != Fluids.LAVA && state.getType() != Fluids.FLOWING_LAVA) return;

		FluidStateModelSet self = (FluidStateModelSet) (Object) this;
		FluidModel waterModel = self.get(Fluids.WATER.defaultFluidState());

		if (!feature.isColorTint()) {
			cir.setReturnValue(waterModel);
			return;
		}
		int rgb = feature.getTintColor() & 0xFFFFFF;
		cir.setReturnValue(new FluidModel(waterModel.layer(), waterModel.stillMaterial(), waterModel.flowingMaterial(),
			waterModel.overlayMaterial(), BlockTintSources.constant(rgb, rgb)));
	}
}
