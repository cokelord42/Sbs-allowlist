package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.cokelord.skyblocksimplified.sound.SoundMuteRegistry;
import com.cokelord.skyblocksimplified.sound.SoundPlayListenerRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Replaces the real vanilla "arrow hit a player" sound with a more distinct/audible substitute — per user
 * request, ported from NoammAddons' confirmed {@code ArrowHitSound.kt}/{@code MixinSoundManager.java}.
 * Ported here onto this codebase's own existing {@code SoundMuteRegistry}/{@code SoundPlayListenerRegistry}
 * pair (the same real {@code SoundManager.play} choke point NoammAddons' own Mixin hooks, already wired up
 * in this codebase — see {@link EtherwarpFeature}'s own success-sound subtoggle, which uses the identical
 * replace-a-real-sound pattern) instead of adding a second, redundant Mixin on the same method.
 *
 * <p>Sound selection (built-in choice, custom .wav import, volume/pitch, deadspace trim) is fully delegated
 * to {@link CustomSoundOption} — per user request ("Add sound importing to all sound modules... volume and
 * pitch sliders... a toggle for removing deadspace"), the same shared object every other sound-picking
 * module in the mod now uses instead of each hand-rolling its own soundIndex/playSound(1f, 1f) call.
 */
public class ArrowHitSoundFeature extends Feature {
	public static final String[] SOUND_IDS = {
		"block.note_block.harp", "entity.experience_orb.pickup", "block.note_block.pling", "entity.arrow.hit"
	};
	public static final String[] SOUND_LABELS = {"Note Block Harp", "Orb Pickup", "Pling", "Vanilla (Arrow Hit Block)"};

	private final CustomSoundOption sound = new CustomSoundOption(SOUND_IDS, SOUND_LABELS);

	private static ArrowHitSoundFeature instance;
	private static boolean listenersRegistered = false;

	public ArrowHitSoundFeature() {
		super("arrow_hit_sound", "Arrow Hit Sound", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Combat"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			SoundMuteRegistry.setRule("arrow_hit_sound", inst -> instance != null && instance.isEnabled() && instance.matches(inst));
			SoundPlayListenerRegistry.setListener("arrow_hit_sound", inst -> {
				if (instance != null && instance.isEnabled() && instance.matches(inst)) instance.sound.play();
			});
		}
	}

	private boolean matches(SoundInstance inst) {
		return SoundEvents.ARROW_HIT_PLAYER.location().equals(inst.getIdentifier());
	}

	public CustomSoundOption getSound() { return sound; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.add("sound", sound.toJson());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!(el instanceof JsonObject obj)) return;
		if (obj.has("sound")) sound.fromJson(obj.get("sound"));
		// Back-compat with the old flat soundIndex field, pre-CustomSoundOption.
		else if (obj.has("soundIndex")) sound.setBuiltinIndex(obj.get("soundIndex").getAsInt());
	}

	@Override
	public String getDescription() {
		return "Replaces the vanilla arrow-hit-a-player sound with a louder, more distinct one.";
	}
}
