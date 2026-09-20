package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Per user request: a list of rows (add/remove, same shape as Command Aliases/Positional Messages), each
 * with a command to send and its own keybind — pressing that combo sends the command exactly once, not
 * repeatedly while held (a plain rising-edge check on {@link KeyCombo#isHeld()}, same idea
 * {@code CustomKeybindRegistry.overrideConsumeClick} already uses for its own click-once behavior). Only
 * fires with no screen open (chat/inventory/this very mod menu, etc.) — the same convention every other
 * mod keybind in this codebase follows, and it sidesteps any chance of a shortcut's own combo firing while
 * the player is mid-capture setting a DIFFERENT row's keybind.
 */
public class CommandShortcutsFeature extends Feature {
	public static final class Shortcut {
		// Stored with a leading slash always present — see MainScreen's collapseLeadingSlash, which keeps
		// the text field's displayed value identical to what's actually stored. normalize() below strips
		// it back off again for sending.
		public String commandInput = "/";
		public final KeyCombo combo = new KeyCombo();
		private boolean wasHeld = false;
	}

	private final List<Shortcut> shortcuts = new ArrayList<>();

	public CommandShortcutsFeature() {
		super("command_shortcuts", "Command Shortcuts", FeatureCategory.INVENTORY, false);
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	public void onTick(Minecraft client) {
		// Real bug found (per user report — "Holding X when exiting a gui opens a menu binded to my key
		// shortcuts module. It should only try to run the command once."): this used to bail out of the
		// whole loop while any screen was open, which meant wasHeld never got updated for the entire time a
		// GUI was showing. KeyCombo.isHeld() polls raw GLFW state directly (see its own doc comment) and
		// keeps reporting "held" the whole time regardless of screen focus, so if the player was already
		// holding the combo before/during a GUI being open, wasHeld was still stuck at its stale pre-GUI
		// value the instant that GUI closed — read as a brand-new rising edge on close, firing the command
		// again even though the key was never actually released and re-pressed. Rising-edge tracking now
		// runs unconditionally every tick so it always reflects real hold state; only the actual firing
		// (which needs no screen open, and no real server command to alias in singleplayer) is gated.
		boolean singleplayer = com.cokelord.skyblocksimplified.util.IslandGate.isSingleplayer();
		for (Shortcut shortcut : shortcuts) {
			boolean held = !shortcut.combo.isEmpty() && shortcut.combo.isHeld();
			boolean risingEdge = held && !shortcut.wasHeld;
			shortcut.wasHeld = held;
			if (risingEdge && client.gui.screen() == null && !singleplayer) fire(shortcut);
		}
	}

	private void fire(Shortcut shortcut) {
		String command = normalize(shortcut.commandInput);
		if (command.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand(command);
	}

	/** Strips any leading slash(es) so the stored value always matches what ClientConnection#sendCommand
	 *  already expects (no slash) — the visible text field re-adds a single leading "/" purely for display,
	 *  same "auto-add the slash, strip one if the user types it" behavior as Command Aliases' own fields. */
	static String normalize(String s) {
		if (s == null) return "";
		s = s.trim();
		while (s.startsWith("/")) s = s.substring(1);
		return s.trim();
	}

	// ---- GUI editing surface (called from MainScreen) ----
	public List<Shortcut> getShortcuts() { return shortcuts; }

	public void addShortcut() {
		shortcuts.add(new Shortcut());
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void removeShortcut(Shortcut shortcut) {
		shortcuts.remove(shortcut);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	@Override
	public JsonElement savePersistedData() {
		JsonArray array = new JsonArray();
		for (Shortcut shortcut : shortcuts) {
			JsonObject obj = new JsonObject();
			obj.addProperty("command", shortcut.commandInput);
			JsonArray keys = new JsonArray();
			for (String key : shortcut.combo.serialize()) keys.add(key);
			obj.add("combo", keys);
			array.add(obj);
		}
		return array;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		shortcuts.clear();
		if (!data.isJsonArray()) return;
		for (JsonElement el : data.getAsJsonArray()) {
			if (!el.isJsonObject()) continue;
			JsonObject obj = el.getAsJsonObject();
			Shortcut shortcut = new Shortcut();
			if (obj.has("command")) shortcut.commandInput = obj.get("command").getAsString();
			if (obj.has("combo") && obj.get("combo").isJsonArray()) {
				List<String> serialized = new ArrayList<>();
				for (JsonElement key : obj.getAsJsonArray("combo")) serialized.add(key.getAsString());
				shortcut.combo.setKeys(KeyCombo.deserialize(serialized).getKeys());
			}
			shortcuts.add(shortcut);
		}
	}

	@Override
	public String getDescription() {
		return "Lets you bind a keybind to instantly send a specific chat command, without typing it.";
	}
}
