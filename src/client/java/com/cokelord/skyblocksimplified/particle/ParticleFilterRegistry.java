package com.cokelord.skyblocksimplified.particle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import net.minecraft.core.particles.ParticleOptions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Central registry of "don't spawn this particle" rules, consumed by ClientLevelParticleMixin to cancel
 * matching particles right at the single choke point every client particle passes through
 * (ClientLevel.doAddParticle). Mirrors SoundMuteRegistry's design.
 *
 * <p>Per the same real crash {@link ParticlePacketObserverRegistry}'s own doc comment describes (a
 * "Failed to handle packet ...ClientboundLevelParticlesPacket..., disconnecting" report) — {@code
 * doAddParticle} can itself run from inside {@code handleParticleEvent}'s per-particle expansion loop, so
 * an uncaught exception from any single rule here was just as capable of taking the whole connection down
 * as an unguarded packet-observer listener was. Each rule test is now individually guarded the same way
 * {@link ParticleSpawnListenerRegistry} (the sibling registry at the same choke point) already was.
 */
public final class ParticleFilterRegistry {
	private ParticleFilterRegistry() {}

	private static final Map<String, Predicate<ParticleOptions>> rules = new LinkedHashMap<>();
	private static final Map<String, PositionalRule> positionalRules = new LinkedHashMap<>();

	/** Like the plain type-only Predicate rules, but also given the particle's spawn position — for cases
	 *  like "hide particles near a dropped blessing" where the particle type alone (usually a common
	 *  ambient/enchant sparkle also used all over the place) isn't distinctive enough to match on its own. */
	@FunctionalInterface
	public interface PositionalRule {
		boolean test(ParticleOptions options, double x, double y, double z);
	}

	public static void setRule(String id, Predicate<ParticleOptions> matcher) {
		rules.put(id, matcher);
	}

	public static void clearRule(String id) {
		rules.remove(id);
	}

	public static void setPositionalRule(String id, PositionalRule matcher) {
		positionalRules.put(id, matcher);
	}

	public static void clearPositionalRule(String id) {
		positionalRules.remove(id);
	}

	public static boolean shouldHide(ParticleOptions options, double x, double y, double z) {
		for (Predicate<ParticleOptions> rule : rules.values()) {
			try {
				if (rule.test(options)) return true;
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Particle filter rule threw, skipping it for this particle", e);
			}
		}
		for (PositionalRule rule : positionalRules.values()) {
			try {
				if (rule.test(options, x, y, z)) return true;
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Particle filter positional rule threw, skipping it for this particle", e);
			}
		}
		return false;
	}
}
