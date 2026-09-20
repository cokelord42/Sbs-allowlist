package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.particle.ParticlePacketEvent;
import com.cokelord.skyblocksimplified.particle.ParticlePacketObserverRegistry;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ClientPacketListener.handleParticleEvent(ClientboundLevelParticlesPacket) is the single point where
 * the packet's own count/maxSpeed/offset (xDist/yDist/zDist) fields are still intact, before the client
 * expands them into individual jittered ClientLevel.doAddParticle calls — burrow-detection needs those
 * exact packet-level values (see ParticlePacketEvent's doc comment), not the post-expansion per-particle
 * ones ClientLevelParticleMixin sees.
 */
@Mixin(ClientPacketListener.class)
public class ParticleEventPacketMixin {
	@Inject(method = "handleParticleEvent", at = @At("HEAD"))
	private void skyblocksimplified$observeParticlePacket(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		ParticlePacketObserverRegistry.notifyReceived(new ParticlePacketEvent(
			packet.getParticle(),
			new Vec3(packet.getX(), packet.getY(), packet.getZ()),
			new Vec3(packet.getXDist(), packet.getYDist(), packet.getZDist()),
			packet.getMaxSpeed(),
			packet.getCount()));
	}
}
