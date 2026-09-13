package com.cokelord.skyblocksimplified.particle;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;

/**
 * The raw fields off ClientboundLevelParticlesPacket, before the client expands it into N individual
 * ClientLevel.doAddParticle calls with jittered positions/velocities. Burrow particle-signature matching
 * (see BurrowParticleScanner/ArrowBurrowGuesser) needs the packet's own count/maxSpeed/offset exactly as
 * the server sent them — those values are lost once expanded, so this observes at the packet level
 * instead of ClientLevelParticleMixin's per-particle choke point.
 */
public record ParticlePacketEvent(ParticleOptions type, Vec3 location, Vec3 offset, float maxSpeed, int count) {
}
