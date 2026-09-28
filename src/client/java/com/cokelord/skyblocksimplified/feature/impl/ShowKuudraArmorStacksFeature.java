package com.cokelord.skyblocksimplified.feature.impl;

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
import net.minecraft.resources.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request ("I need a new part of player display. 'Show kuudra armor's stacks'. It needs to get the
 * little stair icon... make me a debug that lets me copy my actionbar and ill get it for you so you can
 * syntax it"): a movable HUD readout for Kuudra armor's stack counter, built from the user's own captured
 * real action-bar text:
 * <pre>§c5,852/5,852  §61ᝐ     §a1,093§a§l  T3!     §b704/895 §3400     §4123/123</pre>
 * The stack segment is {@code §61ᝐ} — a legacy gold color code directly followed by the stack count digit(s)
 * and Hypixel's own private-use-area codepoint (U+1750) for the "stair" icon texture, going from 1 to 10 per
 * the user. That codepoint only resolves to a real icon through Hypixel's own server resource pack, so this
 * is drawn with vanilla's own {@link Font} (same as every other HUD stat widget in this class's family, e.g.
 * {@link SecretsCounterFeature}), never this mod's own MSDF menu font — that atlas has no glyph for it at
 * all, the exact class of gap {@code MsdfFont#supportsAllGlyphs} exists to route around elsewhere.
 *
 * <p>Reads {@link PlayerDisplayFeature#getLastRawActionBar()}, which already captures every real action-bar
 * update's raw (still-"§"-coded) text unconditionally regardless of that feature's own on/off state, instead
 * of registering a second independent action-bar hook. Per user clarification ("Kuudra armor is the armor
 * dropped for kuudra but not specifically used in kuudra always"): this is Combat &gt; Combat, not Combat &gt;
 * Kuudra, and reads the action bar unconditionally rather than gating on actually being in a Kuudra fight —
 * the armor (and its stack mechanic) works the same wherever it's worn.
 */
public class ShowKuudraArmorStacksFeature extends Feature implements MoveableWidget {
	// The exact codepoint from the user's own captured sample.
	private static final char STAIR_ICON = 'ᝐ';
	private static final Pattern STACK_PATTERN = Pattern.compile("(\\d+)" + STAIR_ICON);

	private int textColor = 0xFFFFAA00;
	private boolean compactMode = false;
	private int stacks = -1;

	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.1f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static boolean listenersRegistered = false;
	private static ShowKuudraArmorStacksFeature instance;

	public ShowKuudraArmorStacksFeature() {
		super("show_kuudra_armor_stacks", "Show Kuudra Armor Stacks", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Combat"; }

	@Override
	protected void onEnable() {
		stacks = -1;
		if (!listenersRegistered) {
			listenersRegistered = true;
			HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
				Identifier.fromNamespaceAndPath("skyblocksimplified", "show_kuudra_armor_stacks"),
				(graphics, deltaTracker) -> {
					if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
						|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
					Minecraft mc = Minecraft.getInstance();
					int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
					int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
					instance.render(graphics, x, y, instance.position.scale);
				});
		}
	}

	// Real bug found (per user report — "the stacks dont reset. They should keep logging the actionbar for
	// changes and hide once it cant find the stacks"): this only ever updated `stacks` when the pattern
	// matched, never resetting it when it didn't — once any value was captured it stayed on screen forever,
	// frozen at the last real number, even long after the action bar stopped showing the stack segment at
	// all (armor unequipped, stacks fully decayed, etc). Every tick now either finds a fresh value or clears
	// it outright, so isVisible() (gated on stacks >= 0) hides the widget the moment the segment is genuinely
	// gone instead of holding a stale number.
	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled()) return;
		String raw = PlayerDisplayFeature.getLastRawActionBar();
		if (raw == null || raw.isEmpty()) {
			stacks = -1;
			return;
		}
		Matcher matcher = STACK_PATTERN.matcher(raw);
		if (matcher.find()) {
			try {
				stacks = Integer.parseInt(matcher.group(1));
				return;
			} catch (NumberFormatException ignored) {}
		}
		stacks = -1;
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() {
		return isEnabled() && stacks >= 0;
	}

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		int shown = stacks >= 0 ? stacks : 5;
		String text = compactMode ? shown + String.valueOf(STAIR_ICON) : "Kuudra Stacks: " + shown + STAIR_ICON;
		int width = Math.round(font.width(text) * scale);
		int height = Math.round(font.lineHeight * scale);
		if (Math.abs(scale - 1f) < 0.01f) {
			graphics.text(font, text, x, y, textColor);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			graphics.text(font, text, x, y, textColor);
			graphics.pose().popMatrix();
		}
		return new Size(width, height);
	}

	public int getTextColor() { return textColor; }
	public void setTextColor(int value) { textColor = value; }
	public boolean isCompactMode() { return compactMode; }
	public void setCompactMode(boolean value) { compactMode = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("textColor", textColor);
		obj.addProperty("compactMode", compactMode);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("textColor")) textColor = obj.get("textColor").getAsInt();
		if (obj.has("compactMode")) compactMode = obj.get("compactMode").getAsBoolean();
		if (obj.has("anchorX")) position.anchorX = obj.get("anchorX").getAsFloat();
		if (obj.has("anchorY")) position.anchorY = obj.get("anchorY").getAsFloat();
		if (obj.has("scale")) position.scale = obj.get("scale").getAsFloat();
	}

	@Override
	public String getDescription() {
		return "Movable HUD readout showing your currently equipped Kuudra armor's stack count.";
	}
}
