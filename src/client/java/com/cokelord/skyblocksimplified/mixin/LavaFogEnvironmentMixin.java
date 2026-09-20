package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.LavaToWaterFeature;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Companion to {@link LavaFluidModelMixin} — swaps lava's own fog behavior for water's real
 *  {@link WaterFogEnvironment} (or, with "Hide Fog" on, removes the underwater-style fog entirely) whenever
 *  {@link LavaToWaterFeature} is enabled. Ported from NoammAddons' confirmed
 *  {@code MixinLavaFogEnvironment.java}. */
@Mixin(LavaFogEnvironment.class)
public abstract class LavaFogEnvironmentMixin {
	@Unique
	private static final WaterFogEnvironment skyblocksimplified$waterFog = new WaterFogEnvironment();

	@Inject(method = "setupFog", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$setupFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!(FeatureRegistry.get("lava_to_water") instanceof LavaToWaterFeature feature) || !feature.isEnabled() || !feature.isHideFog()) return;
		fog.color.set(fog.color.x, fog.color.y, fog.color.z, 0f);
		fog.environmentalStart = renderDistance;
		fog.environmentalEnd = renderDistance;
		ci.cancel();
	}

	@Inject(method = "getBaseColor", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$getBaseColor(ClientLevel level, Camera camera, int renderDistance, float partialTicks, CallbackInfoReturnable<Integer> cir) {
		if (!(FeatureRegistry.get("lava_to_water") instanceof LavaToWaterFeature feature) || !feature.isEnabled()) return;
		int color = feature.isColorTint() ? feature.getTintColor() : skyblocksimplified$waterFog.getBaseColor(level, camera, renderDistance, partialTicks);
		cir.setReturnValue(color);
	}
}
