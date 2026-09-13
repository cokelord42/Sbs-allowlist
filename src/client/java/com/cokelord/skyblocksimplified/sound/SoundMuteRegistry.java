package com.cokelord.skyblocksimplified.sound;

import net.minecraft.client.resources.sounds.SoundInstance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Central registry of "don't play this sound" rules, consumed by SoundManagerMixin to cancel matching
 * sounds right at the single choke point every client sound passes through (SoundManager.play). Mirrors
 * MobHighlightRegistry's design: features register/clear a rule under their own stable id rather than
 * touching sound playback directly.
 *
 * Matches against the whole SoundInstance (not just its Identifier) so rules can key off pitch/volume/
 * position too — needed for e.g. mute_bugged_spade, which can only be told apart from real music by
 * those fields, not by sound id alone.
 */
public final class SoundMuteRegistry {
	private SoundMuteRegistry() {}

	private static final Map<String, Predicate<SoundInstance>> rules = new LinkedHashMap<>();

	public static void setRule(String id, Predicate<SoundInstance> matcher) {
		rules.put(id, matcher);
	}

	public static void clearRule(String id) {
		rules.remove(id);
	}

	public static boolean isMuted(SoundInstance instance) {
		for (Predicate<SoundInstance> rule : rules.values()) {
			if (rule.test(instance)) return true;
		}
		return false;
	}
}
