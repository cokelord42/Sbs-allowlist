package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

public class DianaRollFeature extends DianaFeature {
	private static DianaRollFeature instance;
	private static final String[] SOUND_IDS = {"skyblocksimplified:chest_rolling.pray_to_rngesus", "entity.player.levelup", "block.note_block.pling", "entity.experience_orb.pickup"};
	private static final String[] SOUND_LABELS = {"PRAY TO RNGESUS", "Level Up", "Note Block Pling", "Orb Pickup"};
	private final CustomSoundOption landSound = new CustomSoundOption(SOUND_IDS, SOUND_LABELS);

	public DianaRollFeature() {
		super("diana_roll", "Diana Rolling", false,
			"Every rare Diana mob kill (yours or a lootshare) plays a case-opening roll on screen that lands on what actually dropped. "
				+ "Rare drop chat lines are held until the roll lands. Test with /dianaroll <inquisitor|king|manticore|sphinx> [item].");
		defInt("duration", 4);
		defInt("height", 30);
		defBool("tickSound", true);
		defBool("landSoundOn", true);
		instance = this;
	}

	public static boolean on() { return instance != null && instance.isEnabled(); }
	public static float durationSeconds() { return instance == null ? 4f : instance.integer("duration"); }
	public static int heightPercent() { return instance == null ? 30 : instance.integer("height"); }
	public static boolean tickSound() { return instance != null && instance.bool("tickSound"); }

	public static void playLandSound() {
		if (instance != null && instance.bool("landSoundOn")) instance.landSound.play();
	}

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			slider("Roll Duration", "duration", 3, 8, "s", "How long the strip spins before landing."),
			slider("Height On Screen", "height", 10, 80, "%", "Vertical position of the roll overlay."),
			toggle("Tick Sound", "tickSound", "Plays a tick as each item passes the pointer."),
			toggle("Rare Land Sound", "landSoundOn", "Plays a sound when it lands on a rare drop."),
			new SettingRow.Sound(landSound, () -> bool("landSoundOn")));
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = super.savePersistedData().getAsJsonObject();
		obj.add("landSound", landSound.toJson());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (data != null && data.isJsonObject() && data.getAsJsonObject().has("landSound")) landSound.fromJson(data.getAsJsonObject().get("landSound"));
	}
}
