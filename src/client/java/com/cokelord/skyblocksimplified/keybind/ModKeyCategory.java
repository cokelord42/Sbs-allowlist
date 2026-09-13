package com.cokelord.skyblocksimplified.keybind;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Real bug found (per user report — "Move mod keybinds out of vanilla 'Inventory' controls category"):
 * every real vanilla {@link KeyMapping} this mod registers (Slot Locking's toggle, Leap Menu's 4 per-class
 * binds, Dungeon Routes' "next step" bind) was constructed with {@code KeyMapping.Category.INVENTORY} —
 * presumably copied from one real example without a second thought — which lists them in the Controls
 * screen mixed in among vanilla's own inventory-related binds instead of under their own section. A single
 * shared category, registered once here, groups all of them together instead.
 */
public final class ModKeyCategory {
	public static final KeyMapping.Category MAIN =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skyblocksimplified", "main"));

	private ModKeyCategory() {}
}
