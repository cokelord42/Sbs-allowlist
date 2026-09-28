package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.google.gson.JsonElement;

import java.util.List;

public class CloseBurrowDetectionFeature extends DianaFeature {
	private static CloseBurrowDetectionFeature instance;
	private static final String[] SOUND_IDS = {"block.note_block.pling", "entity.experience_orb.pickup", "block.note_block.harp", "block.note_block.bell"};
	private static final String[] SOUND_LABELS = {"Pling", "Orb Pickup", "Note Block Harp", "Bell"};
	private final CustomSoundOption burrowSound = new CustomSoundOption(SOUND_IDS, SOUND_LABELS);

	public CloseBurrowDetectionFeature() {
		super("diana_close_burrow", "Close Burrow Detection", false,
			"Detects nearby burrows from their particles and marks them as Start, Mob or Treasure. "
				+ "Requires /particlequality Extreme with Critical Hit and Enchant particles. /sbsclearburrows clears all burrow waypoints.");
		defBool("sound", true);
		instance = this;
	}

	public static boolean on() { return instance != null && instance.isEnabled(); }
	public static boolean sound() { return instance != null && instance.bool("sound"); }

	/** Plays the configured new-burrow sound (shared sound picker: built-in or imported file). */
	public static void playBurrowSound() {
		if (instance != null && instance.bool("sound")) instance.burrowSound.play();
	}

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			toggle("Sound On New Burrow", "sound", "Plays a sound when a new burrow is found."),
			new SettingRow.Sound(burrowSound, () -> bool("sound")));
	}

	@Override
	public JsonElement savePersistedData() {
		com.google.gson.JsonObject obj = super.savePersistedData().getAsJsonObject();
		obj.add("burrowSound", burrowSound.toJson());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (data != null && data.isJsonObject() && data.getAsJsonObject().has("burrowSound")) {
			burrowSound.fromJson(data.getAsJsonObject().get("burrowSound"));
		}
	}
}
