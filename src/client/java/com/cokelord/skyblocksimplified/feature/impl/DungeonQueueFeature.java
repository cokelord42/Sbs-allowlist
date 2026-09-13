package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.regex.Pattern;

/** HUD warp-cooldown timer + optional auto-requeue at the end of a dungeon run, ported from Odin's
 *  {@code DungeonQueue.kt}. Party-leave detection is done via this feature's own chat regexes rather
 *  than a shared party-leave event (SBAR's existing {@code party.PartyApi} only exposes current
 *  membership queries, not a leave callback). */
public class DungeonQueueFeature extends Feature implements MoveableWidget {
	private static final Pattern ENTER_PATTERN = Pattern.compile("entered (?:MM )?\\w+ Catacombs, Floor (\\w+)!");
	private static final Pattern KICKED_INSTANCE_PATTERN = Pattern.compile("^You are no longer allowed to access this instance!$");
	private static final Pattern KICKED_JOINING_PATTERN = Pattern.compile("^You were kicked while joining that server!$");
	private static final String EXTRA_STATS_MARKER = "EXTRA STATS";
	private static final Pattern PARTY_LEFT_PATTERN = Pattern.compile(
		"^(?:You have left the party\\.|You left the party\\.|You have been removed from the party by .+|" +
		"You have been kicked from the party by .+|The party was disbanded because .+|" +
		"You have been kicked from the party\\.)$");

	private boolean announceKick = false;
	private boolean autoRequeue = false;
	private int requeueDelaySeconds = 2;
	private boolean disableOnPartyLeave = true;

	private long warpTimerEndMillis = 0;
	private boolean disableRequeue = false;
	private long requeueAtMillis = -1;

	private final HudPosition defaultPosition = new HudPosition(0.02f, 0.5f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static boolean chatRegistered = false;
	private static DungeonQueueFeature instance;

	public DungeonQueueFeature() {
		super("dungeon_queue", "Dungeon Queue", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() {
		return "Dungeons";
	}

	@Override
	protected void onEnable() {
		disableRequeue = false;
		requeueAtMillis = -1;
		if (!chatRegistered) {
			chatRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (isEnabled()) onChatMessage(message.getString());
				return true;
			});
			// warpTimerEndMillis is a raw timestamp set 30s out on entering a dungeon floor — with no reset
			// here, disconnecting (or crashing/alt-F4ing) inside that 30s window left the warp-timer badge
			// rendering in whatever the player loaded next (singleplayer included), since the render lambda
			// below only ever checked the timestamp itself, never connection state. Per user report ("leaving
			// the server makes the gui elements stay... in singleplayer").
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
				warpTimerEndMillis = 0;
				requeueAtMillis = -1;
				disableRequeue = false;
			});
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, 
				Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeon_queue_warp_timer"),
				(graphics, deltaTracker) -> {
					if (instance == null || !instance.isEnabled() || !com.cokelord.skyblocksimplified.util.IslandGate.isOnHypixel()
						|| instance.warpTimerEndMillis - System.currentTimeMillis() <= 0
						|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
					Minecraft mc = Minecraft.getInstance();
					int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
					int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
					instance.render(graphics, x, y, instance.position.scale);
				});
		}
	}

	private void onChatMessage(String text) {
		if (announceKick && (KICKED_JOINING_PATTERN.matcher(text).find() || KICKED_INSTANCE_PATTERN.matcher(text).find())) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand("pc I was kicked!");
			return;
		}

		if (ENTER_PATTERN.matcher(text).find()) {
			warpTimerEndMillis = System.currentTimeMillis() + 30_000L;
			return;
		}

		if (autoRequeue && text.contains(EXTRA_STATS_MARKER)) {
			if (disableRequeue) {
				disableRequeue = false;
				return;
			}
			requeueAtMillis = System.currentTimeMillis() + requeueDelaySeconds * 1000L;
			return;
		}

		if (disableOnPartyLeave && PARTY_LEFT_PATTERN.matcher(text).matches()) {
			disableRequeue = true;
		}
	}

	@Override
	public void onTick(Minecraft client) {
		if (requeueAtMillis > 0 && System.currentTimeMillis() >= requeueAtMillis) {
			requeueAtMillis = -1;
			if (!disableRequeue && client.player != null && client.player.connection != null) {
				client.player.connection.sendCommand("instancerequeue");
			}
		}
		if (!DungeonState.isInDungeon()) disableRequeue = false;
	}

	public boolean isAnnounceKick() { return announceKick; }
	public void setAnnounceKick(boolean value) { announceKick = value; }
	/** Exposed for ChatCommandsFeature's !downtime/!undowntime commands, which pause auto-requeue for the
	 *  rest of the run without needing the player to disable the whole Dungeon Queue feature. */
	public boolean isDisableRequeue() { return disableRequeue; }
	public void setDisableRequeue(boolean value) { disableRequeue = value; }
	public boolean isAutoRequeue() { return autoRequeue; }
	public void setAutoRequeue(boolean value) { autoRequeue = value; }
	public int getRequeueDelaySeconds() { return requeueDelaySeconds; }
	public void setRequeueDelaySeconds(int value) { requeueDelaySeconds = Math.max(0, Math.min(30, value)); }
	public boolean isDisableOnPartyLeave() { return disableOnPartyLeave; }
	public void setDisableOnPartyLeave(boolean value) { disableOnPartyLeave = value; }

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() { return isEnabled(); }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		long remainingMillis = warpTimerEndMillis - System.currentTimeMillis();
		String text = remainingMillis > 0
			? String.format(Locale.ROOT, "§eWarp: §a%.1fs", remainingMillis / 1000f)
			: "§eWarp: §a0s";
		Font font = Minecraft.getInstance().font;
		int width = font.width(text) + 8;
		int height = font.lineHeight + 4;
		// scale used to be accepted but never applied — see PlayerDisplayFeature.StatWidget.render()'s
		// comment for why that made a resized widget's real render stop matching its own edit-mode outline.
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale);
		graphics.pose().translate(-x, -y);
		RenderUtil.fillRounded(graphics, x, y, x + width, y + height, 3, 0x80000000);
		graphics.text(font, Component.literal(text), x + 4, y + 2, 0xFFFFFFFF);
		graphics.pose().popMatrix();
		return new Size(Math.round(width * scale), Math.round(height * scale));
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("announceKick", announceKick);
		obj.addProperty("autoRequeue", autoRequeue);
		obj.addProperty("requeueDelaySeconds", requeueDelaySeconds);
		obj.addProperty("disableOnPartyLeave", disableOnPartyLeave);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when
		// i relaunch"): scale was never saved alongside the anchor, so resizing via "Edit gui locations" was
		// always lost on relaunch.
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("announceKick")) announceKick = obj.get("announceKick").getAsBoolean();
		if (obj.has("autoRequeue")) autoRequeue = obj.get("autoRequeue").getAsBoolean();
		if (obj.has("requeueDelaySeconds")) requeueDelaySeconds = obj.get("requeueDelaySeconds").getAsInt();
		if (obj.has("disableOnPartyLeave")) disableOnPartyLeave = obj.get("disableOnPartyLeave").getAsBoolean();
		if (obj.has("anchorX")) position.anchorX = obj.get("anchorX").getAsFloat();
		if (obj.has("anchorY")) position.anchorY = obj.get("anchorY").getAsFloat();
		if (obj.has("scale")) position.scale = obj.get("scale").getAsFloat();
	}

	@Override
	public String getDescription() {
		return "HUD countdown for your dungeon warp cooldown, with an option to auto-requeue as soon as your party leaves.";
	}
}
