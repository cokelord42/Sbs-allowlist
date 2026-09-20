package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.cokelord.skyblocksimplified.util.IslandGate;
import net.minecraft.client.Minecraft;

/** Keybind for "/setspawn" (the same command SkyHanni's HypixelCommands.setSpawn() sends). */
public class SetSpawnKeybindFeature extends Feature {
	private final KeyCombo combo = new KeyCombo();
	private boolean comboHeldLastTick = false;

	public SetSpawnKeybindFeature() {
		super("garden_set_spawn_keybind", "Set Spawn Keybind", FeatureCategory.FARMING, false);
	}

	@Override
	public String getSubcategory() {
		return "Custom Keybinds";
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public KeyCombo getPrimaryKeyCombo() {
		return combo;
	}

	@Override
	public void onTick(Minecraft client) {
		// Real bug found: this fired "/setspawn" on any island the keybind happened to be pressed on — the
		// command only ever makes sense in the Garden (setting your personal farming spawn point), so it
		// needs the same island gate every other Garden-only feature already goes through.
		if (client.player == null || client.gui.screen() != null || !IslandGate.isInGarden()) {
			comboHeldLastTick = false;
			return;
		}
		boolean held = !combo.isEmpty() && combo.isHeld();
		if (held && !comboHeldLastTick) {
			client.player.connection.sendCommand("setspawn");
		}
		comboHeldLastTick = held;
	}

	@Override
	public com.google.gson.JsonElement savePersistedData() {
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (String s : combo.serialize()) arr.add(new com.google.gson.JsonPrimitive(s));
		return arr;
	}

	@Override
	public void loadPersistedData(com.google.gson.JsonElement data) {
		if (!data.isJsonArray()) return;
		java.util.List<String> serialized = new java.util.ArrayList<>();
		for (var e : data.getAsJsonArray()) serialized.add(e.getAsString());
		combo.setKeys(KeyCombo.deserialize(serialized).getKeys());
	}

	@Override
	public String getDescription() {
		return "Keybind that runs /setspawn in the Garden.";
	}
}
