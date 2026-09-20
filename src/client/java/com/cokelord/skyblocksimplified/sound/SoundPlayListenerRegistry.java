package com.cokelord.skyblocksimplified.sound;

import net.minecraft.client.resources.sounds.SoundInstance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Central registry of "notify me whenever a sound plays" listeners, consumed by the same SoundManagerMixin
 * choke point SoundMuteRegistry uses (SoundManager.play) — needed for features that have to react to a
 * sound's real pitch/volume (e.g. the Moonglade Beacon solver's pitch tuning, which Hypixel only exposes
 * via the pitch of a repeating "block.note_block.bass" sound, not any readable GUI/lore text). Notified
 * unconditionally, even for sounds SoundMuteRegistry goes on to cancel, since muting is a display choice
 * and shouldn't blind a feature that needs to observe the sound regardless.
 */
public final class SoundPlayListenerRegistry {
	private SoundPlayListenerRegistry() {}

	private static final Map<String, Consumer<SoundInstance>> listeners = new LinkedHashMap<>();

	public static void setListener(String id, Consumer<SoundInstance> listener) {
		listeners.put(id, listener);
	}

	public static void clearListener(String id) {
		listeners.remove(id);
	}

	public static void notifyPlayed(SoundInstance instance) {
		for (Consumer<SoundInstance> listener : listeners.values()) {
			try {
				listener.accept(instance);
			} catch (Exception e) {
				// This runs from inside a mixin injected at the HEAD of SoundManager.play — every single
				// sound the game plays, including ones triggered from packet handling. An uncaught
				// exception here previously propagated straight out of that injection with nothing to
				// catch it, which is the likely cause of a real "Network protocol error" disconnect: one
				// listener throwing (e.g. on an unexpected SoundInstance shape) could corrupt whatever
				// call stack led into SoundManager.play, including a packet-handling one. One bad
				// listener must never be able to take down sound playback or anything upstream of it.
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Sound play listener threw, skipping it for this sound", e);
			}
		}
	}
}
