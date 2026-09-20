package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.keybind.CustomKeybindRegistry;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.cokelord.skyblocksimplified.mixin.KeyMappingAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One remappable slot (attack/use/left/right/forward/back/jump/sneak) under Custom Keybinds — reuses
 * the same capturable-KeyCombo row OpenGuiFeature already has, instead of a bespoke keybind-picker UI.
 *
 * A slot that's never been explicitly configured (no saved data, never captured) defaults to whatever
 * the player has actually bound that vanilla action to in their own Controls screen — not left unbound —
 * so "Custom Keybinds" starts as a no-op remap rather than silently killing movement/attack until every
 * slot is manually re-captured. Seeded lazily from onTick() (guaranteed to run well after the game and
 * Options are fully initialized) rather than in the constructor, since mod-init timing relative to
 * Options being loaded isn't something to rely on.
 */
public class KeybindOverrideFeature extends Feature {
	private final KeyCombo combo = new KeyCombo();
	private final CustomKeybindRegistry.Slot slot;
	private boolean hasPersistedData = false;
	private boolean defaultSeeded = false;

	public KeybindOverrideFeature(String id, String displayName, CustomKeybindRegistry.Slot slot) {
		super(id, displayName, FeatureCategory.FARMING, false);
		this.slot = slot;
		CustomKeybindRegistry.register(slot, combo);
	}

	@Override
	public String getSubcategory() {
		return "Custom Keybinds";
	}

	@Override
	public List<KeyMapping> getKeybinds() {
		return List.of();
	}

	@Override
	public KeyCombo getPrimaryKeyCombo() {
		return combo;
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public void onTick(Minecraft client) {
		if (defaultSeeded || hasPersistedData) return;
		defaultSeeded = true;
		if (!combo.isEmpty()) return;
		KeyMapping mapping = vanillaMappingFor(client, slot);
		if (mapping == null) return;
		InputConstants.Key key = ((KeyMappingAccessor) mapping).skyblocksimplified$getKey();
		if (key != null && key != InputConstants.UNKNOWN) {
			combo.setKeys(Set.of(key));
		}
	}

	private static KeyMapping vanillaMappingFor(Minecraft client, CustomKeybindRegistry.Slot slot) {
		Options options = client.options;
		if (options == null) return null;
		return switch (slot) {
			case ATTACK -> options.keyAttack;
			case USE -> options.keyUse;
			case LEFT -> options.keyLeft;
			case RIGHT -> options.keyRight;
			case FORWARD -> options.keyUp;
			case BACK -> options.keyDown;
			case JUMP -> options.keyJump;
			case SNEAK -> options.keyShift;
		};
	}

	@Override
	public JsonElement savePersistedData() {
		JsonArray arr = new JsonArray();
		for (String s : combo.serialize()) {
			arr.add(new JsonPrimitive(s));
		}
		return arr;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		// Being called at all means the save file had an entry for this slot — respect that explicit
		// state (even an intentionally-cleared/empty combo) instead of re-seeding a default over it.
		hasPersistedData = true;
		if (!data.isJsonArray()) return;
		List<String> serialized = new ArrayList<>();
		for (JsonElement e : data.getAsJsonArray()) {
			serialized.add(e.getAsString());
		}
		combo.setKeys(KeyCombo.deserialize(serialized).getKeys());
	}

	@Override
	public String getDescription() {
		return "Lets you rebind the " + getDisplayName() + " action to a custom key or mouse-button combo.";
	}
}
