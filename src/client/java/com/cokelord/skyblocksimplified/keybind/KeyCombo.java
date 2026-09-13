package com.cokelord.skyblocksimplified.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A keybind made of multiple simultaneously-held keys and/or mouse buttons (e.g. Ctrl+F), unlike
 * vanilla's KeyMapping which only ever represents one physical key. Checked via raw GLFW polling
 * rather than vanilla's KeyMapping/Options system, so it works regardless of Minecraft's own
 * screen-focus gating (KeyMapping click/down state stops updating while any Screen is open).
 */
public class KeyCombo {
	private final Set<InputConstants.Key> keys = new LinkedHashSet<>();

	public void setKeys(Set<InputConstants.Key> newKeys) {
		keys.clear();
		keys.addAll(newKeys);
	}

	public Set<InputConstants.Key> getKeys() {
		return keys;
	}

	public boolean isEmpty() {
		return keys.isEmpty();
	}

	public boolean isHeld() {
		if (keys.isEmpty()) return false;
		for (InputConstants.Key key : keys) {
			if (!isDown(key)) return false;
		}
		return true;
	}

	public static boolean isDown(InputConstants.Key key) {
		long handle = Minecraft.getInstance().getWindow().handle();
		if (key.getType() == InputConstants.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
		}
		return GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
	}

	public String getDisplayName() {
		if (keys.isEmpty()) return "NONE";
		StringBuilder sb = new StringBuilder();
		for (InputConstants.Key key : keys) {
			if (sb.length() > 0) sb.append(" + ");
			sb.append(key.getDisplayName().getString());
		}
		return sb.toString();
	}

	public static boolean isModifierKey(InputConstants.Key key) {
		if (key.getType() != InputConstants.Type.KEYSYM) return false;
		int v = key.getValue();
		return v == InputConstants.KEY_LCONTROL || v == InputConstants.KEY_RCONTROL
			|| v == InputConstants.KEY_LSHIFT || v == InputConstants.KEY_RSHIFT
			|| v == InputConstants.KEY_LALT || v == InputConstants.KEY_RALT;
	}

	/** Serializes to a compact string list ("type:value") for JSON persistence. */
	public java.util.List<String> serialize() {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (InputConstants.Key key : keys) {
			out.add(key.getType().name() + ":" + key.getValue());
		}
		return out;
	}

	public static KeyCombo deserialize(java.util.List<String> serialized) {
		KeyCombo combo = new KeyCombo();
		Set<InputConstants.Key> parsed = new LinkedHashSet<>();
		for (String entry : serialized) {
			String[] parts = entry.split(":", 2);
			if (parts.length != 2) continue;
			try {
				InputConstants.Type type = InputConstants.Type.valueOf(parts[0]);
				parsed.add(type.getOrCreate(Integer.parseInt(parts[1])));
			} catch (IllegalArgumentException ignored) {
				// unknown/corrupt entry: skip it rather than fail the whole load
			}
		}
		combo.setKeys(parsed);
		return combo;
	}
}
