package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.DianaWarp;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public class DianaWarpFeature extends DianaFeature {
	private static DianaWarpFeature instance;
	private final KeyCombo combo = new KeyCombo();
	private boolean heldLastTick = false;

	public DianaWarpFeature() {
		super("diana_warp", "Diana Warp", false,
			"Press a key to run the travel-scroll warp closest to the current burrow guess (or newest rare mob). "
				+ "Hub warp is always used; Wizard and Castle compare distance ignoring height.");
		defInt("minDistance", 30);
		defBool("preferRareMob", true);
		defBool("blockNearBurrow", true);
		for (DianaWarp.Warp warp : DianaWarp.Warp.values()) {
			if (!warp.alwaysAvailable()) defBool("warp_" + warp.command(), warp.enabledByDefault());
		}
		instance = this;
	}

	public static DianaWarpFeature get() { return instance; }

	public boolean warpAllowed(DianaWarp.Warp warp) {
		return warp.alwaysAvailable() || bool("warp_" + warp.command());
	}

	@Override
	public void onTick(Minecraft client) {
		boolean held = client.gui.screen() == null && !combo.isEmpty() && combo.isHeld();
		if (held && !heldLastTick) DianaWarp.warpToTarget();
		heldLastTick = held;
	}

	@Override
	public List<SettingRow> getSettingRows() {
		List<SettingRow> warps = new ArrayList<>();
		for (DianaWarp.Warp warp : DianaWarp.Warp.values()) {
			if (warp.alwaysAvailable()) continue;
			warps.add(toggle(warp.label(), "warp_" + warp.command(), "Allow /warp " + warp.command() + " (needs the travel scroll unlocked)."));
		}
		return List.of(
			new SettingRow.Keybind("Warp Key", combo),
			slider("Minimum Distance", "minDistance", 0, 100, " blocks",
				"Won't warp if the guess is closer to you than this."),
			toggle("Don't Warp Near A Burrow", "blockNearBurrow",
				"Won't warp while Close Burrow Detection has found a burrow within 60 blocks of you."),
			toggle("Prefer Rare Mob Waypoints", "preferRareMob",
				"When a rare mob waypoint exists, warp toward the newest one instead of the burrow guess."),
			new SettingRow.Group("Warps", "warps", warps));
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = super.savePersistedData().getAsJsonObject();
		JsonArray arr = new JsonArray();
		for (String s : combo.serialize()) arr.add(new JsonPrimitive(s));
		obj.add("combo", arr);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (data == null || !data.isJsonObject() || !data.getAsJsonObject().has("combo")) return;
		List<String> keys = new ArrayList<>();
		for (JsonElement e : data.getAsJsonObject().getAsJsonArray("combo")) keys.add(e.getAsString());
		combo.setKeys(KeyCombo.deserialize(keys).getKeys());
	}
}
