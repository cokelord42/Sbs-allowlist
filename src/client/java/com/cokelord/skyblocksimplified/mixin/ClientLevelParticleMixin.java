package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.particle.ParticleFilterRegistry;
import com.cokelord.skyblocksimplified.particle.ParticleSpawnListenerRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ClientLevel.doAddParticle is the single private choke point every public addParticle/
 *  addAlwaysVisibleParticle overload funnels through — cancelling here covers every "hide X particles"
 *  toggle instead of needing a separate hook per spawn path. */
@Mixin(ClientLevel.class)
public class ClientLevelParticleMixin {
	@Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$filterParticles(ParticleOptions options, boolean forceAlwaysRender, boolean ignoreDistance,
												   double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, CallbackInfo ci) {
		ParticleSpawnListenerRegistry.notifySpawned(options, x, y, z);
		if (ParticleFilterRegistry.shouldHide(options, x, y, z)) {
			ci.cancel();
		}
	}
}
