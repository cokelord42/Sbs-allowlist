package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Prevents the cursor from being reset when opening a GUI. Ported from Odin's {@code NoCursorReset.kt} +
 * its {@code MouseHandlerMixin.java}, replacing this codebase's previous, more elaborate setScreen-hop
 * capture/restore implementation per user instruction. Odin's approach is simpler: hook
 * {@code MouseHandler.grabMouse()}/{@code releaseMouse()} directly (see {@link
 * com.cokelord.skyblocksimplified.mixin.NoCursorResetMixin}) and, within a short window after a GUI was last
 * open, force the cursor back to its pre-grab position instead of letting vanilla recenter it. This class
 * only tracks the "was a screen recently open" timing window and the toggle/setting state; the actual
 * cursor-position capture and restore lives in the mixin, matching where Odin split the same logic.
 */
public class NoCursorResetFeature extends Feature {
	private int unhookTimeoutMillis = 150;

	private long clock = System.currentTimeMillis();
	private boolean wasNotNull = false;

	public NoCursorResetFeature() {
		super("dont_reset_cursor_between_inventories", "No Cursor Reset", FeatureCategory.INVENTORY, false);
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	public void onTick(Minecraft client) {
		boolean screenOpen = client.gui.screen() != null;
		if (screenOpen) {
			wasNotNull = true;
			clock = System.currentTimeMillis();
		} else if (wasNotNull) {
			wasNotNull = false;
			clock = System.currentTimeMillis();
		}
	}

	/** Called from NoCursorResetMixin's releaseMouse injection to decide whether to override the vanilla recenter. */
	public boolean shouldHookMouse() {
		return isEnabled() && com.cokelord.skyblocksimplified.util.IslandGate.isOnHypixel()
			&& System.currentTimeMillis() - clock < unhookTimeoutMillis;
	}

	public int getUnhookTimeoutMillis() { return unhookTimeoutMillis; }
	public void setUnhookTimeoutMillis(int value) { unhookTimeoutMillis = Math.max(0, Math.min(1000, value)); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("unhookTimeoutMillis", unhookTimeoutMillis);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("unhookTimeoutMillis")) unhookTimeoutMillis = obj.get("unhookTimeoutMillis").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Stops your mouse cursor from being reset to the center of the screen every time you open a GUI.";
	}
}
