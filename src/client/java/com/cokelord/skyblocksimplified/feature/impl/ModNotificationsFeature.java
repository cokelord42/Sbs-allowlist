package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Per user request: generalizes the old single-purpose "update available" toast into a real notification
 * system other parts of the mod can reuse — same visual look and slide-in/out animation
 * ({@link com.cokelord.skyblocksimplified.gui.NotificationToastRenderer}, itself a straight generalization
 * of the old {@code UpdateToastRenderer}), just no longer hardcoded to one message. This feature owns which
 * kinds of notification are enabled and the one shared sound they all play — the renderer itself stays a
 * dumb, feature-agnostic queue/animation, matching this project's usual split between a Feature (settings)
 * and its own render/infra class.
 */
public class ModNotificationsFeature extends Feature {
	public static final String[] SOUND_IDS = {
		"block.note_block.pling", "block.note_block.harp", "entity.experience_orb.pickup", "block.note_block.bell"
	};
	public static final String[] SOUND_LABELS = {"Pling", "Note Block Harp", "Orb Pickup", "Bell"};

	private boolean updateReadyEnabled = true;
	private boolean exportSuccessEnabled = true;
	private boolean importSuccessEnabled = true;
	// Defaults to Orb Pickup (index 2) — matches the old update toast's own primary sound, so an unmodified
	// install sounds identical to before this existed.
	private final CustomSoundOption sound = new CustomSoundOption(SOUND_IDS, SOUND_LABELS);

	public ModNotificationsFeature() {
		super("mod_notifications", "Mod Notifications", FeatureCategory.ABOUT, true);
		sound.setBuiltinIndex(2);
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	public boolean isUpdateReadyEnabled() { return updateReadyEnabled; }
	public void setUpdateReadyEnabled(boolean value) { updateReadyEnabled = value; }

	public boolean isExportSuccessEnabled() { return exportSuccessEnabled; }
	public void setExportSuccessEnabled(boolean value) { exportSuccessEnabled = value; }

	public boolean isImportSuccessEnabled() { return importSuccessEnabled; }
	public void setImportSuccessEnabled(boolean value) { importSuccessEnabled = value; }

	public CustomSoundOption getSound() { return sound; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("updateReadyEnabled", updateReadyEnabled);
		obj.addProperty("exportSuccessEnabled", exportSuccessEnabled);
		obj.addProperty("importSuccessEnabled", importSuccessEnabled);
		obj.add("sound", sound.toJson());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("updateReadyEnabled")) updateReadyEnabled = obj.get("updateReadyEnabled").getAsBoolean();
		if (obj.has("exportSuccessEnabled")) exportSuccessEnabled = obj.get("exportSuccessEnabled").getAsBoolean();
		if (obj.has("importSuccessEnabled")) importSuccessEnabled = obj.get("importSuccessEnabled").getAsBoolean();
		if (obj.has("sound")) sound.fromJson(obj.get("sound"));
	}

	@Override
	public String getDescription() {
		return "The mod's shared notification popup (used for update-ready, config export, and config import) — turn individual kinds off, or change the sound it plays.";
	}
}
