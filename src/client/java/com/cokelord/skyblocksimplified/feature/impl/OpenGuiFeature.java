package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.MainScreen;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opens the mod's main GUI. Default keybind is Right Shift, rebindable (including to a multi-key or
 * mouse-button combo) from its own settings row. Uses KeyCombo's raw-poll isHeld() rather than a
 * vanilla KeyMapping: vanilla stops updating KeyMapping click/down state while any Screen is open
 * (see KeyboardHandler.keyPress's handlesGameInput check), which used to require a separate direct
 * event check in MainScreen just to detect "close the GUI" presses. Polling isn't screen-gated, so
 * the same rising-edge check in onTick now handles opening AND closing (open() toggles based on
 * whatever screen is currently showing), and that separate check could be removed entirely.
 */
public class OpenGuiFeature extends Feature {
	private final KeyCombo comboKey = new KeyCombo();
	private boolean wasHeld = false;

	public OpenGuiFeature() {
		super("open_gui", "Mod keybind", FeatureCategory.ABOUT, true);
		Set<InputConstants.Key> defaultKeys = new LinkedHashSet<>();
		defaultKeys.add(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_RSHIFT));
		comboKey.setKeys(defaultKeys);
	}

	@Override
	public void onTick(Minecraft client) {
		boolean held = comboKey.isHeld();
		if (held && !wasHeld) {
			open(client);
		}
		wasHeld = held;
	}

	/** Runs the actual screen swap via client.execute so it's safe regardless of the caller's thread
	 *  (this is called both from the main-thread tick handler and from chat-command execution, whose
	 *  threading isn't something to assume without checking). */
	public void open(Minecraft client) {
		client.execute(() -> {
			try {
				Screen current = client.gui.screen();
				if (current instanceof MainScreen mainScreen) {
					mainScreen.requestClose();
				} else {
					client.gui.setScreen(new MainScreen());
				}
			} catch (Exception e) {
				// Anything thrown inside a client.execute() task surfaces in the log as a generic
				// "Error executing task on Client" with no indication of which task or why — logging it
				// here with real context makes that actually diagnosable if it happens again.
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Failed to open/close the mod GUI", e);
			}
		});
	}

	/** No settings box — its one keybind is shown inline in the row instead. */
	@Override
	public List<KeyMapping> getKeybinds() {
		return List.of();
	}

	@Override
	public KeyCombo getPrimaryKeyCombo() {
		return comboKey;
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public String getSubcategory() {
		return "Mod settings";
	}

	@Override
	public JsonElement savePersistedData() {
		JsonArray arr = new JsonArray();
		for (String s : comboKey.serialize()) {
			arr.add(new JsonPrimitive(s));
		}
		return arr;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonArray()) return;
		java.util.List<String> serialized = new java.util.ArrayList<>();
		for (JsonElement e : data.getAsJsonArray()) {
			serialized.add(e.getAsString());
		}
		comboKey.setKeys(KeyCombo.deserialize(serialized).getKeys());
	}

	@Override
	public String getDescription() {
		return "Keybind that opens the mod's main settings menu (default: Right Shift).";
	}
}
