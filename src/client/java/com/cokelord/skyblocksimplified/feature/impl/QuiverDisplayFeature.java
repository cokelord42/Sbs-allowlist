package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request ("Do the odin version, theirs works well") — ported from Odin's confirmed
 * {@code QuiverDisplay.kt}: shows the current arrow count off whichever real arrow/quiver item is carrying
 * an "Arrows Remaining: N" lore line.
 *
 * <p>Two independent, agreeing sources feed the same cached name/count:
 * <ol>
 *   <li><b>Real packet match (primary, matches Odin exactly)</b> — {@link #onSetSlot}, called from
 *   {@link com.cokelord.skyblocksimplified.mixin.ContainerSetSlotPacketMixin} for every real vanilla
 *   per-slot inventory sync packet, matching Odin's own exact container-slot-index convention: slot 9 while
 *   clearing rooms (not in the boss fight), slot 44 while actually in the boss fight — real, Hypixel-side
 *   fixed positions in the player's own persistent inventory container (id 0), independent of whatever GUI
 *   happens to be on screen. See that mixin's own doc comment — its method name/accessors are confirmed
 *   real (decompiled from this project's own mapped MC 26.2 artifacts), not a guess.</li>
 *   <li><b>Inventory scan (fallback)</b> — {@link #onTick}, a plain once-per-tick scan of the player's own
 *   live inventory for the same real lore line, regardless of slot position. Kept as a safety net in case a
 *   future MC update changes this packet's shape or the slot-index convention above.</li>
 * </ol>
 * Neither path ever clears the cached name/count once found (same as Odin) — the last real reading stays
 * displayed until a new one replaces it, rather than blanking out the moment neither source has a fresh
 * match this exact instant.
 */
public class QuiverDisplayFeature extends Feature implements MoveableWidget {
	private static final Pattern ARROWS_REMAINING = Pattern.compile("^Arrows Remaining: ([\\d,]+)$");
	// Real, confirmed Odin convention (DungeonUtils.inClear = inDungeons && !inBoss): while clearing rooms,
	// Hypixel updates slot 9 of the player's own persistent inventory (container id 0); during the boss
	// fight specifically, it updates slot 44 instead. See this class's own doc comment for why both paths
	// (this exact convention, and the version-independent tick scan) are kept side by side.
	private static final int CLEARING_SLOT = 9;
	private static final int BOSS_SLOT = 44;
	private static final int PLAYER_INVENTORY_CONTAINER_ID = 0;

	private boolean showName = true;
	private int countColor = 0xFF55FF55;

	private String arrowName = null;
	private String arrowCount = null;

	private final HudPosition defaultPosition = new HudPosition(0.01f, 0.6f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static QuiverDisplayFeature instance;
	// Real bug found (per user report — "Quiver display has issues, it doesnt render. It seems to be right
	// cause it renders in the edit gui panel but not in game"): the edit panel renders every registered
	// MoveableWidget directly for its own preview, but nothing was ever attaching this widget to the real
	// in-game HUD render pipeline — every other HUD widget in this codebase (DungeonMapFeature,
	// StormPadTimerFeature, etc.) registers its own HudElementRegistry callback in onEnable(); this class
	// never did. Guarded the same way those do (register the callback exactly once, ever, and let isVisible()
	// make it a no-op while disabled) so re-toggling the feature doesn't hit HudElementRegistry's own
	// duplicate-id crash.
	private static boolean listenersRegistered = false;

	public QuiverDisplayFeature() {
		super("quiver_display", "Quiver Display", FeatureCategory.INVENTORY, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
			Identifier.fromNamespaceAndPath("skyblocksimplified", "quiver_display"), (graphics, tracker) -> {
				if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
					|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.render(graphics, x, y, instance.position.scale);
			});
	}

	/** Called from {@link com.cokelord.skyblocksimplified.mixin.ContainerSetSlotPacketMixin} for every real
	 *  per-slot inventory sync packet — see this class's own doc comment for the exact slot-index convention
	 *  this mirrors from Odin. */
	public static void onSetSlot(int containerId, int slotIndex, ItemStack stack) {
		if (instance == null || !instance.isEnabled() || containerId != PLAYER_INVENTORY_CONTAINER_ID) return;
		boolean clearing = DungeonState.isInDungeon() && !DungeonState.isInBoss();
		if (slotIndex != (clearing ? CLEARING_SLOT : BOSS_SLOT)) return;
		instance.tryCapture(stack);
	}

	@Override
	public void onTick(Minecraft client) {
		if (client.player == null) return;
		for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
			if (tryCapture(stack)) return;
		}
	}

	private boolean tryCapture(ItemStack stack) {
		if (stack.isEmpty() || !(stack.is(Items.ARROW) || stack.is(Items.FEATHER))) return false;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return false;
		for (Component line : lore.lines()) {
			Matcher m = ARROWS_REMAINING.matcher(line.getString());
			if (!m.matches()) continue;
			arrowName = stack.getHoverName().getString();
			arrowCount = m.group(1);
			return true;
		}
		return false;
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() { return isEnabled() && arrowCount != null; }

	@Override
	public boolean hasVisibleContent() { return arrowCount != null; }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		boolean isExample = arrowCount == null;
		String name = isExample ? "Flint Arrow" : arrowName;
		String count = isExample ? "500" : arrowCount;
		// Per user request ("Change the quiver display to say n arrows instead of arrow x n. I want it to be
		// like '500 bouncy arrows'. It should be plural and when the amount is 1 it should be singular"):
		// every real Hypixel arrow/quiver item name already ends in a singular noun ("Arrow"/"Feather"), so a
		// trailing "s" is enough to pluralize it — skipped only when the count is exactly "1".
		String pluralName = "1".equals(count) ? name : name + "s";
		String suffix = showName ? " " + pluralName : "";
		int countWidth = font.width(count);
		int width = countWidth + font.width(suffix);

		if (Math.abs(scale - 1f) < 0.01f) {
			drawText(graphics, font, x, y, count, suffix, countWidth);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			drawText(graphics, font, x, y, count, suffix, countWidth);
			graphics.pose().popMatrix();
		}
		return new Size(Math.round(width * scale), Math.round(font.lineHeight * scale));
	}

	private void drawText(GuiGraphicsExtractor graphics, Font font, int x, int y, String count, String suffix, int countWidth) {
		graphics.text(font, count, x, y, countColor);
		if (!suffix.isEmpty()) graphics.text(font, suffix, x + countWidth, y, 0xFFFFFFFF);
	}

	public boolean isShowName() { return showName; }
	public void setShowName(boolean value) { showName = value; }
	public int getCountColor() { return countColor; }
	public void setCountColor(int value) { countColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showName", showName);
		obj.addProperty("countColor", countColor);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("showName")) showName = obj.get("showName").getAsBoolean();
		if (obj.has("countColor")) countColor = obj.get("countColor").getAsInt();
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
	}

	@Override
	public String getDescription() {
		return "Displays the number of arrows remaining in your currently-held quiver/arrow item.";
	}
}
