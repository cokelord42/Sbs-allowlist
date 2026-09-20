package com.cokelord.skyblocksimplified.particle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import net.minecraft.core.particles.ParticleOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Notifies listeners of every real particle spawn (position included) at the same
 * ClientLevel.doAddParticle choke point ParticleFilterRegistry filters at — mirrors
 * SoundPlayListenerRegistry's design, including wrapping each listener call in try/catch so one
 * feature's bug can't take down particle rendering for the rest of the frame (or worse; a mixin
 * injection point is not a safe place to let an unchecked exception propagate).
 */
public final class ParticleSpawnListenerRegistry {
	private ParticleSpawnListenerRegistry() {}

	public record Spawn(ParticleOptions options, double x, double y, double z) {}

	private static final List<Consumer<Spawn>> listeners = new ArrayList<>();

	public static void addListener(Consumer<Spawn> listener) {
		listeners.add(listener);
	}

	public static void notifySpawned(ParticleOptions options, double x, double y, double z) {
		if (listeners.isEmpty()) return;
		Spawn spawn = new Spawn(options, x, y, z);
		for (Consumer<Spawn> listener : listeners) {
			try {
				listener.accept(spawn);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Particle spawn listener threw, skipping it this frame", e);
			}
		}
	}
}
