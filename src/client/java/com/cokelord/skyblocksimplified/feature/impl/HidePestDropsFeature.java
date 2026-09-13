package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.regex.Pattern;

/**
 * Hides pest-kill drop spam ("You received 64x Enchanted Sugar for killing a Mosquito!") and, as a
 * sub-toggle, the separate rare vinyl-drop announcement — both confirmed patterns, ported from
 * SkyHanni's PestApi.kt (pestDeathChatPattern) and its "RARE DROP! ... Vinyl" messages. A vinyl-drop
 * sound mute was also requested, but no confirmed sound-id fingerprint for it was found (unlike the
 * dungeon chest/lever sounds elsewhere in this project, which do have one) — not guessed at.
 */
public class HidePestDropsFeature extends Feature {
	private static final Pattern PEST_DEATH_DROP = Pattern.compile("§eYou received §a[0-9]+x .* §efor killing an? §2.*§e!");
	private static final Pattern VINYL_DROP = Pattern.compile("§6§lRARE DROP! .*Vinyl.*");

	private boolean hideVinylDrops = false;

	public HidePestDropsFeature() {
		super("hide_pest_drops", "Hide Pest Drops", FeatureCategory.FARMING, false);
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay || !isEnabled()) return true;
			String text = message.getString();
			if (PEST_DEATH_DROP.matcher(text).matches()) return false;
			return !(hideVinylDrops && VINYL_DROP.matcher(text).matches());
		});
	}

	@Override
	public String getSubcategory() {
		return "Pest farming";
	}

	public boolean isHideVinylDrops() {
		return hideVinylDrops;
	}

	public void setHideVinylDrops(boolean hideVinylDrops) {
		this.hideVinylDrops = hideVinylDrops;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("hideVinylDrops", hideVinylDrops);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("hideVinylDrops")) hideVinylDrops = obj.get("hideVinylDrops").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Hides the pest-kill drop spam chat messages, with a separate toggle for rare vinyl-drop announcements.";
	}
}
