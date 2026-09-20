package com.cokelord.skyblocksimplified.particle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Broadcasts every raw particle packet the client receives to registered listeners, consumed by
 * ParticleEventPacketMixin. Mirrors SoundPlayObserverRegistry's "observe everything, let features
 * decide" design rather than ParticleFilterRegistry's "first match wins" filter design, since multiple
 * burrow-detection systems (BurrowParticleScanner, ArrowBurrowGuesser) each need to see every packet.
 *
 * <p>Real crash found (per user report — a "Failed to handle packet ...ClientboundLevelParticlesPacket...,
 * disconnecting" disconnect, the exact vanilla message {@code ClientPacketListener} logs when a packet
 * handler throws): {@code ParticleEventPacketMixin} injects straight into {@code handleParticleEvent} with
 * no surrounding try-catch of its own, so an uncaught exception from any single listener here used to
 * propagate all the way back out through the mixin and into vanilla's packet-dispatch exception handling —
 * which disconnects the client, exactly like a genuinely malformed packet would. Every listener call is
 * now individually guarded so one listener's bug can't take down the connection (same defensive pattern
 * DungeonBlockDetector's own tick listeners already use for the same class of risk).
 */
public final class ParticlePacketObserverRegistry {
	private ParticlePacketObserverRegistry() {}

	private static final List<Consumer<ParticlePacketEvent>> listeners = new CopyOnWriteArrayList<>();

	public static void register(Consumer<ParticlePacketEvent> listener) {
		listeners.add(listener);
	}

	public static void notifyReceived(ParticlePacketEvent event) {
		for (Consumer<ParticlePacketEvent> listener : listeners) {
			try {
				listener.accept(event);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Particle packet observer threw, skipping it for this packet", e);
			}
		}
	}
}
