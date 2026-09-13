package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;

/**
 * Closes a secret chest the moment you press a movement key (WASD/shift), same as walking away would
 * naturally do — a manual "just keep moving, don't bother pressing Escape" convenience.
 *
 * <p>Two real bugs found (per user report — "in ANY gui", not just secret chests): this used to (1) match
 * EVERY bound keybind, not just movement, and (2) approximate "secret chest" via a fragile "opened within
 * 2s of any chest/trapped-chest block click" heuristic that — by this class's own previous doc comment —
 * admittedly couldn't actually distinguish a secret reward chest from any other ordinary chest in a
 * dungeon, so it fired for every chest GUI, not just secret ones. Confirmed against devonian's real {@code
 * CloseChestOnKey.kt}: it only checks the four movement keys plus sneak, and — critically — the actual
 * "is this a secret chest" signal is the chest screen's own title being the exact literal string "Chest"
 * (Hypixel's plain, generic title used specifically for secret/reward chests; other chest-shaped Hypixel
 * menus — NPC shops, storage, etc. — always have their own distinct titles). Matches that exactly now,
 * dropping the click-timing heuristic entirely.
 */
public class SecretChestAnyKeyCloseFeature extends Feature {
	private static final String SECRET_CHEST_TITLE = "Chest";

	private static boolean listenersRegistered = false;
	private static SecretChestAnyKeyCloseFeature instance;

	public SecretChestAnyKeyCloseFeature() {
		super("secret_chest_any_key_close", "Movement Closes Secret Chest", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!DungeonState.isInDungeon()) return;
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			if (!(containerScreen.getMenu() instanceof ChestMenu)) return;
			if (!SECRET_CHEST_TITLE.equals(containerScreen.getTitle().getString())) return;

			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
				if (instance == null || !instance.isEnabled()) return true;
				KeyMapping[] movementKeys = {
					client.options.keyUp, client.options.keyLeft, client.options.keyRight,
					client.options.keyDown, client.options.keyShift
				};
				for (KeyMapping mapping : movementKeys) {
					if (mapping.isUnbound() || !mapping.matches(event)) continue;
					client.gui.setScreen(null);
					return false;
				}
				return true;
			});
		});
	}

	@Override
	public String getDescription() {
		return "Closes a secret chest the moment you press a movement key, the same as walking away would.";
	}
}
