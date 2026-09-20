package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clean "Secrets: X/Y" HUD readout for the <b>current room</b> — a movable GUI element mirroring exactly
 * what Hypixel's own action bar already shows there, just repositionable and (per user request) able to
 * replace/hide the vanilla one. Reads the action bar directly, on purpose: {@link DungeonState}'s
 * secrets fields (tab-list-sourced "Secrets Found: N%") were tried as a delay-free alternative, but that
 * number tracks the <b>whole dungeon run's</b> progress, not the current room's — the wrong scope for what
 * this HUD is supposed to show, so despite the action bar's real (Hypixel-side, not this mod's) refresh
 * delay, it's the only source that's actually per-room. Per user: "I wouldn't mind having some delay
 * honestly so just make it pull from the actionbar element for now."
 *
 * <p>Real confirmed live format (via Debug module output): {@code "3,664/3,664     376 Defense
 * 2,341/2,341 27          0/4 Secrets"} — the count/total comes BEFORE the "Secrets" label, not after a
 * "Secrets:" prefix as originally guessed, and there's no "✎" icon in the captured text at all (Hypixel's
 * action-bar icons are private-use-area codepoints, not the literal glyph).
 */
public class SecretsCounterFeature extends Feature implements MoveableWidget {
	private static final Pattern[] SECRETS_PATTERNS = {
		Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)\\s*Secrets"),
		Pattern.compile("✎\\s*([\\d,]+)(?:\\s*/\\s*([\\d,]+))?"),
		Pattern.compile("(?i)secrets\\s*:?\\s*([\\d,]+)(?:\\s*/\\s*([\\d,]+))?"),
	};

	private boolean hideVanillaActionBar = true;
	private int textColor = 0xFFFFFFFF;
	private boolean compactMode = false;

	private int secretsFound = -1;
	private Integer secretsTotal = null;

	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.02f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static boolean listenersRegistered = false;
	private static SecretsCounterFeature instance;

	public SecretsCounterFeature() {
		super("secrets_counter", "Secrets Counter", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		secretsFound = -1;
		secretsTotal = null;
		if (!listenersRegistered) {
			listenersRegistered = true;
			// Real bug found (per user report — a room with 0 secrets kept showing the PREVIOUS room's
			// count): the action bar only ever includes a "N/Y Secrets" segment when there's something to
			// show, so a room with genuinely zero secrets never sends one at all — onActionBar only updates
			// state on a match, so nothing ever cleared the last room's stale numbers. Reset on every room
			// change instead; a 0-secret room then simply never gets a fresh match and stays hidden
			// (isVisible() already gates on secretsFound >= 0), which is exactly the "hide it" behavior
			// requested.
			WorldScan.addRoomEnterListener(room -> {
				if (instance == null) return;
				instance.secretsFound = -1;
				instance.secretsTotal = null;
			});
			// Real bug found (per user report — "the action bar issue... only specifically bugs out when
			// im in a room with secrets"): this used to cancel the WHOLE actionbar chat message here
			// (returning false from ALLOW_GAME) whenever it matched a "N/Y Secrets" segment. Health/mana/
			// defense/secrets all ride the exact same combined action-bar string (see this class's own doc
			// comment for the confirmed live format), and per PlayerDisplayActionBarMixin's doc comment,
			// returning false from ALLOW_GAME stops ChatListener from ever calling Hud#setOverlayMessage for
			// that update — which means that mixin (and therefore PlayerDisplayFeature's health/mana/defense
			// parsing) never fired either, freezing the whole stat line specifically whenever the room being
			// stood in actually has secrets. Now only parses here and suppresses vanilla's own RENDERING of
			// the line below (via the same replaceElement mechanism PlayerDisplayFeature already uses),
			// leaving the underlying message itself untouched so every other listener still sees it.
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance == null || !instance.isEnabled() || !overlay || !DungeonState.isInDungeon()) return true;
				instance.onActionBar(message.getString());
				return true;
			});
			net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.OVERLAY_MESSAGE,
				original -> (graphics, tracker) -> {
					if (instance != null && instance.hideVanillaActionBar && instance.isVisible()) return;
					original.extractRenderState(graphics, tracker);
				});
			net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST,
				net.minecraft.resources.Identifier.fromNamespaceAndPath("skyblocksimplified", "secrets_counter"),
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

	private boolean onActionBar(String rawText) {
		// Real bug found via live debug data: parsed "70/4" from a raw string that visibly ends "...0/4
		// Secrets" — Hypixel's action bar text (unlike chat, which really is pre-stripped) carries LITERAL
		// "§" legacy formatting codes embedded directly in the text content, not real Component styling.
		// A stray "§7" (gray) sitting directly against the real digits meant [\d,]+ silently absorbed the
		// "7" from the color code into the number, turning "0" into "70". Stripping codes first fixes this
		// for good instead of hoping no color code ever lands adjacent to a real digit again.
		String text = rawText.replaceAll("§.", "");
		for (Pattern pattern : SECRETS_PATTERNS) {
			Matcher matcher = pattern.matcher(text);
			// The secrets segment is always the LAST "found/total" pair in the action bar (health, mana
			// and magic find all come earlier) — take the rightmost match rather than the first, in case
			// an earlier stat segment ever loosely satisfies the same pattern.
			java.util.regex.MatchResult last = null;
			while (matcher.find()) last = matcher.toMatchResult();
			if (last == null) continue;
			try {
				int newFound = Integer.parseInt(last.group(1).replace(",", ""));
				Integer newTotal = last.group(2) != null ? Integer.parseInt(last.group(2).replace(",", "")) : null;
				secretsFound = newFound;
				secretsTotal = newTotal;
			} catch (NumberFormatException ignored) {}
			return true;
		}
		return false;
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() {
		// Per user request: this shows the CURRENT ROOM's secrets, which is meaningless once the boss fight
		// starts (there's no "current room" anymore) — hidden during it instead of showing a stale count.
		return isEnabled() && DungeonState.isInDungeon() && !DungeonState.isInBoss() && secretsFound >= 0;
	}

	@Override
	public boolean isRelevantToCurrentIsland() { return DungeonState.isInDungeon(); }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		int shown = secretsFound >= 0 ? secretsFound : 3;
		// Per user request: a user-pickable text color, plus a "Compact Mode" that drops the "Secrets: "
		// label entirely (just "0/7") — the old hardcoded §b/§f/§7 color codes baked into the string would
		// have overridden any color the user picked, so the whole string is now plain text colored via the
		// render color argument instead.
		String text;
		if (compactMode) {
			text = secretsTotal != null ? shown + "/" + secretsTotal : String.valueOf(shown);
		} else {
			text = secretsTotal != null ? "Secrets: " + shown + "/" + secretsTotal : "Secrets: " + shown;
		}
		int width = Math.round(font.width(text) * scale);
		int height = Math.round(font.lineHeight * scale);
		// scale used to be accepted but never applied — see PlayerDisplayFeature.StatWidget.render()'s
		// comment for why that made a resized widget's real render stop matching its own edit-mode outline.
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

	public boolean isHideVanillaActionBar() { return hideVanillaActionBar; }
	public void setHideVanillaActionBar(boolean value) { hideVanillaActionBar = value; }
	public int getTextColor() { return textColor; }
	public void setTextColor(int value) { textColor = value; }
	public boolean isCompactMode() { return compactMode; }
	public void setCompactMode(boolean value) { compactMode = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("hideVanillaActionBar", hideVanillaActionBar);
		obj.addProperty("textColor", textColor);
		obj.addProperty("compactMode", compactMode);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when
		// i relaunch"): scale was never saved alongside the anchor, so resizing via "Edit gui locations" was
		// always lost on relaunch.
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("hideVanillaActionBar")) hideVanillaActionBar = obj.get("hideVanillaActionBar").getAsBoolean();
		if (obj.has("textColor")) textColor = obj.get("textColor").getAsInt();
		if (obj.has("compactMode")) compactMode = obj.get("compactMode").getAsBoolean();
		if (obj.has("anchorX")) position.anchorX = obj.get("anchorX").getAsFloat();
		if (obj.has("anchorY")) position.anchorY = obj.get("anchorY").getAsFloat();
		if (obj.has("scale")) position.scale = obj.get("scale").getAsFloat();
	}

	@Override
	public String getDescription() {
		return "Movable HUD readout showing secrets found in the current dungeon room.";
	}
}
