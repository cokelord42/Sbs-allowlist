package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per user request ("Add Pet Keybinds. It should detect the pets menu like the pet display is currently
 * doing, add keybinds for 1-8 (the top row). It should click slots 11-17, but it could be offset again for -1
 * so be ready to make it -1 when i need you to"): while Hypixel's real Pets menu is open, pressing one of 8
 * independently rebindable keys clicks the corresponding pet icon on the menu's top row directly, without
 * needing to move the mouse there — the exact same real-key-event architecture {@link
 * CustomLoadoutKeybindsFeature} already uses for the Loadouts menu (see that class's own doc comment for why
 * a plain polled {@code KeyMapping}/{@code KeyCombo.isHeld()} can never see a click while a screen without
 * {@code passEvents=true} is open, and why this instead reads the raw keydown through {@code
 * ScreenKeyboardEvents.allowKeyPress}).
 *
 * <p>Pets-menu detection reuses {@link PetDisplayFeature}'s own confirmed title pattern
 * ("(n/n) Pets" or bare "Pets") rather than a second guessed regex.
 *
 * <p>{@link #PET_SLOTS} was originally a best-guess constant (8 keys for the top row's 8 pet icons, starting
 * at container slot 11) — per user report ("The pet keybinds also need to go -1 since the first keybind is
 * clicking the second pet"), confirmed off by one the same way {@link CustomLoadoutKeybindsFeature}'s own
 * Round 11 needed, and shifted down by one accordingly.
 */
public class PetKeybindsFeature extends Feature {
	private static final Pattern PETS_MENU_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Pets$");

	// Real bug found (per user report — "the first keybind is clicking the second pet"): shifted down by one
	// from the original best-guess {11..18} — see this class's own doc comment.
	private static final int[] PET_SLOTS = {10, 11, 12, 13, 14, 15, 16, 17};
	private static final int[] DEFAULT_KEYS = {
		InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4,
		InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8
	};

	private final KeyCombo[] petCombos = new KeyCombo[PET_SLOTS.length];

	private static boolean listenersRegistered = false;
	private static PetKeybindsFeature instance;

	public PetKeybindsFeature() {
		super("pet_keybinds", "Pet Keybinds", FeatureCategory.INVENTORY, false);
		instance = this;
		for (int i = 0; i < petCombos.length; i++) {
			petCombos[i] = new KeyCombo();
			petCombos[i].setKeys(java.util.Set.of(InputConstants.Type.KEYSYM.getOrCreate(DEFAULT_KEYS[i])));
		}
	}

	@Override
	public String getSubcategory() {
		return "Inventory";
	}

	public int getPetSlotCount() { return PET_SLOTS.length; }
	public KeyCombo getPetCombo(int index) { return petCombos[index]; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		JsonArray combosArray = new JsonArray();
		for (KeyCombo combo : petCombos) {
			JsonArray keysArray = new JsonArray();
			for (String key : combo.serialize()) keysArray.add(key);
			combosArray.add(keysArray);
		}
		obj.add("petCombos", combosArray);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("petCombos")) {
			JsonArray combosArray = obj.getAsJsonArray("petCombos");
			for (int i = 0; i < petCombos.length && i < combosArray.size(); i++) {
				List<String> serialized = new java.util.ArrayList<>();
				for (JsonElement keyEl : combosArray.get(i).getAsJsonArray()) serialized.add(keyEl.getAsString());
				petCombos[i].setKeys(KeyCombo.deserialize(serialized).getKeys());
			}
		}
	}

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
				if (instance == null || !instance.isEnabled()) return true;
				return instance.onKeyPress(client, containerScreen, event);
			});
		});
	}

	/** Same combo-matches-this-keydown check {@link CustomLoadoutKeybindsFeature} already uses — see that
	 *  class's own doc comment (round 12) for why a raw keydown event has to be matched this way instead of
	 *  polling {@code KeyCombo#isHeld()}. */
	private static boolean comboMatchesEvent(KeyCombo combo, net.minecraft.client.input.KeyEvent event) {
		if (combo.isEmpty()) return false;
		InputConstants.Key eventKey = InputConstants.Type.KEYSYM.getOrCreate(event.key());
		if (!combo.getKeys().contains(eventKey)) return false;
		for (InputConstants.Key key : combo.getKeys()) {
			if (!key.equals(eventKey) && !KeyCombo.isDown(key)) return false;
		}
		return true;
	}

	private boolean onKeyPress(Minecraft client, AbstractContainerScreen<?> screen, net.minecraft.client.input.KeyEvent event) {
		if (!PETS_MENU_TITLE.matcher(screen.getTitle().getString()).matches()) return true;
		LocalPlayer player = client.player;
		if (player == null || client.gameMode == null) return true;

		int matchedIndex = -1;
		for (int i = 0; i < petCombos.length; i++) {
			if (comboMatchesEvent(petCombos[i], event)) { matchedIndex = i; break; }
		}
		if (matchedIndex < 0) return true;

		int inventorySlot = PET_SLOTS[matchedIndex];
		List<Slot> allSlots = screen.getMenu().slots;
		int containerSlotCount = Math.max(0, allSlots.size() - 36);
		if (inventorySlot >= containerSlotCount) return false;

		client.gameMode.handleContainerInput(screen.getMenu().containerId, inventorySlot, 0, ContainerInput.PICKUP, player);
		return false;
	}

	@Override
	public String getDescription() {
		return "In the Pets menu, pressing 1-8 selects the matching pet directly instead of clicking it.";
	}
}
