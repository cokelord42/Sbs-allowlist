package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.util.SkullTextureUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * HUD readout for the Spirit Mask / Bonzo's Mask / Phoenix Pet "second life" procs — chat-regex detection,
 * per-item active/cooldown countdown, alert sound + party announce on proc. Ported from Odin's
 * {@code InvincibilityTimer.kt}.
 *
 * <p>Odin also draws the cooldown directly on the mask's inventory slot as a durability bar (via a
 * {@code GuiEvent.RenderSlot} cancel-and-redraw hook). This codebase's closest equivalent,
 * {@code SlotItemOverrideRegistry}, only substitutes which item renders — it has no post-render overlay
 * hook — so that specific sub-feature is dropped; the HUD readout (this port's primary value) is kept in
 * full. */
public class InvincibilityTimerFeature extends Feature implements MoveableWidget {
	private enum Type {
		SPIRIT("Spirit Mask", Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!$"), 60, 600,
			"9bbe721d7ad8ab965f08cbec0b834f779b5197f79da4aea3d13d253ece9dec2"),
		BONZO("Bonzo's Mask", Pattern.compile("^Your (?:. )?Bonzo's Mask saved your life!$"), 60, 3600,
			"12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c"),
		PHOENIX("Phoenix Pet", Pattern.compile("^Your Phoenix Pet saved you from certain death!$"), 80, 1200,
			"66b1b59bc890c9c97527787dde20600c8b86f6b9912d51a6bfcdb0e4c2aa3c97");

		final String displayName;
		final Pattern regex;
		final int maxActiveTicks;
		final int maxCooldownTicks;
		final String skullTexture;
		private ItemStack icon;
		int activeTicks;
		int cooldownTicks;

		Type(String displayName, Pattern regex, int maxActiveTicks, int maxCooldownTicks, String skullTexture) {
			this.displayName = displayName;
			this.regex = regex;
			this.maxActiveTicks = maxActiveTicks;
			this.maxCooldownTicks = maxCooldownTicks;
			this.skullTexture = skullTexture;
			// NOT built here: this constructor runs as part of the enum's static class-init, the first
			// time Type is referenced — which happens inside onEnable(), called from ConfigManager.load()
			// at mod-init time, while Minecraft's own constructor is still running. Building an ItemStack
			// that early crashes with "Components not bound yet" (the data-component registry isn't bound
			// yet at that point) — a real startup crash this caused. Built lazily on first render instead,
			// by which point the game has fully finished loading.
		}

		ItemStack getIcon() {
			if (icon == null) icon = SkullTextureUtil.fromTextureHash(skullTexture);
			return icon;
		}

		void proc() {
			activeTicks = maxActiveTicks;
			cooldownTicks = maxCooldownTicks;
		}

		void tick() {
			if (cooldownTicks > 0) cooldownTicks--;
			if (activeTicks > 0) activeTicks--;
		}

		void reset() {
			activeTicks = 0;
			cooldownTicks = 0;
		}
	}

	private boolean invincibilityAlert = true;
	private boolean invincibilityAnnounce = true;
	private boolean showSpirit = true;
	private boolean showBonzo = true;
	private boolean showPhoenix = true;
	private boolean onlyInDungeons = true;
	private boolean showOnlyInBoss = false;
	private boolean showTitle = false;

	// Per user report: the old default (0.02, 0.3 — left side, roughly a third down the screen) sat right
	// over the crosshair/shooting area during the F7 emerald-block device phase. Moved up near the top,
	// under where vanilla's own boss bar renders, since that's out of the way for every boss-fight phase,
	// not just this one.
	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.1f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static boolean listenersRegistered = false;
	private static InvincibilityTimerFeature instance;

	public InvincibilityTimerFeature() {
		super("invincibility_timer", "Invincibility Timer", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		for (Type type : Type.values()) type.reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString());
				return true;
			});
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance == null || !instance.isEnabled()) return;
				for (Type type : Type.values()) type.tick();
			});
			net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, 
				net.minecraft.resources.Identifier.fromNamespaceAndPath("skyblocksimplified", "invincibility_timer"),
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

	private void onChatMessage(String text) {
		if (onlyInDungeons && !DungeonState.isInDungeon()) return;
		for (Type type : Type.values()) {
			if (!type.regex.matcher(text).matches()) continue;
			type.proc();
			long usedMasks = 0;
			for (Type t : Type.values()) if (t.cooldownTicks > 0) usedMasks++;
			Minecraft mc = Minecraft.getInstance();
			if (invincibilityAnnounce && mc.player != null && mc.player.connection != null) {
				mc.player.connection.sendCommand("pc " + type.displayName + " Procced! (" + usedMasks + "/" + Type.values().length + ")");
			}
			if (invincibilityAlert && mc.player != null) {
				mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§d" + type.displayName));
				mc.player.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1f, 1f);
			}
			if (showTitle) {
				mc.gui.hud.setTitle(Component.literal("§d" + type.displayName + " Procced!"));
			}
			return;
		}
	}

	private boolean shouldShow(Type type) {
		return switch (type) {
			case SPIRIT -> showSpirit;
			case BONZO -> showBonzo;
			case PHOENIX -> showPhoenix;
		};
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() {
		if (!isEnabled()) return false;
		if (onlyInDungeons && !DungeonState.isInDungeon()) return false;
		// Per user report ("shows up after killing all blood mobs. It should show when entering boss"):
		// switched from isInBoss() (true the instant the Blood Door opens — see
		// DungeonState.bossGreetingSeen's own doc comment for why that reads as "way too early") to the
		// narrower isBossFightVisuallyActive(), true only once the boss's own real greeting has been seen.
		return !showOnlyInBoss || DungeonState.isBossFightVisuallyActive();
	}

	// Mirrors the onlyInDungeons half of isVisible() above (not showOnlyInBoss — that's a narrower runtime
	// restriction, not about island relevance; positioning this in a dungeon but outside the boss fight
	// still makes sense for the edit screen).
	@Override
	public boolean isRelevantToCurrentIsland() { return !onlyInDungeons || DungeonState.isInDungeon(); }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		int rowHeight = 14;
		int width = 40;
		int rows = 0;

		// scale used to be accepted but never applied — see PlayerDisplayFeature.StatWidget.render()'s
		// comment for why that made a resized widget's real render stop matching its own edit-mode outline.
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale);
		graphics.pose().translate(-x, -y);

		for (Type type : Type.values()) {
			if (!shouldShow(type)) continue;
			int rowY = y + rows * rowHeight;
			graphics.item(type.getIcon(), x, rowY - 1);

			String text;
			int color;
			if (type.activeTicks > 0) {
				text = String.format(Locale.ROOT, "%.1fs", type.activeTicks / 20f);
				color = 0xFFFFAA00;
			} else if (type.cooldownTicks > 0) {
				text = String.format(Locale.ROOT, "%.1fs", type.cooldownTicks / 20f);
				color = 0xFFFF5555;
			} else {
				text = "✔";
				color = 0xFF55FF55;
			}
			int textWidth = font.width(text);
			RenderUtil.fillRounded(graphics, x + 16, rowY + 2, x + 16 + textWidth + 4, rowY + 12, 2, 0x80000000);
			graphics.text(font, text, x + 18, rowY + 3, color);
			width = Math.max(width, 20 + textWidth);
			rows++;
		}

		graphics.pose().popMatrix();
		return new Size(Math.round(width * scale), Math.round(Math.max(1, rows) * rowHeight * scale));
	}

	public boolean isInvincibilityAlert() { return invincibilityAlert; }
	public void setInvincibilityAlert(boolean value) { invincibilityAlert = value; }
	public boolean isInvincibilityAnnounce() { return invincibilityAnnounce; }
	public void setInvincibilityAnnounce(boolean value) { invincibilityAnnounce = value; }
	public boolean isShowSpirit() { return showSpirit; }
	public void setShowSpirit(boolean value) { showSpirit = value; }
	public boolean isShowBonzo() { return showBonzo; }
	public void setShowBonzo(boolean value) { showBonzo = value; }
	public boolean isShowPhoenix() { return showPhoenix; }
	public void setShowPhoenix(boolean value) { showPhoenix = value; }
	public boolean isOnlyInDungeons() { return onlyInDungeons; }
	public void setOnlyInDungeons(boolean value) { onlyInDungeons = value; }
	public boolean isShowOnlyInBoss() { return showOnlyInBoss; }
	public void setShowOnlyInBoss(boolean value) { showOnlyInBoss = value; }
	public boolean isShowTitle() { return showTitle; }
	public void setShowTitle(boolean value) { showTitle = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("invincibilityAlert", invincibilityAlert);
		obj.addProperty("invincibilityAnnounce", invincibilityAnnounce);
		obj.addProperty("showSpirit", showSpirit);
		obj.addProperty("showBonzo", showBonzo);
		obj.addProperty("showPhoenix", showPhoenix);
		obj.addProperty("onlyInDungeons", onlyInDungeons);
		obj.addProperty("showOnlyInBoss", showOnlyInBoss);
		obj.addProperty("showTitle", showTitle);
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
		if (obj.has("invincibilityAlert")) invincibilityAlert = obj.get("invincibilityAlert").getAsBoolean();
		if (obj.has("invincibilityAnnounce")) invincibilityAnnounce = obj.get("invincibilityAnnounce").getAsBoolean();
		if (obj.has("showSpirit")) showSpirit = obj.get("showSpirit").getAsBoolean();
		if (obj.has("showBonzo")) showBonzo = obj.get("showBonzo").getAsBoolean();
		if (obj.has("showPhoenix")) showPhoenix = obj.get("showPhoenix").getAsBoolean();
		if (obj.has("onlyInDungeons")) onlyInDungeons = obj.get("onlyInDungeons").getAsBoolean();
		if (obj.has("showOnlyInBoss")) showOnlyInBoss = obj.get("showOnlyInBoss").getAsBoolean();
		if (obj.has("showTitle")) showTitle = obj.get("showTitle").getAsBoolean();
		if (obj.has("anchorX")) position.anchorX = obj.get("anchorX").getAsFloat();
		if (obj.has("anchorY")) position.anchorY = obj.get("anchorY").getAsFloat();
		if (obj.has("scale")) position.scale = obj.get("scale").getAsFloat();
	}

	@Override
	public String getDescription() {
		return "Tracks Spirit Mask / Bonzo's Mask / Phoenix Pet \"second life\" procs and shows an active/cooldown countdown, with an alert sound.";
	}
}
