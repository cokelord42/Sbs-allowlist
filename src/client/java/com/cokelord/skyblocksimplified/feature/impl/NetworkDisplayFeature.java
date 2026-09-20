package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.util.RealPingMonitor;
import com.cokelord.skyblocksimplified.util.TpsMonitor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/** Real client-initiated ping/pong round trip ({@link RealPingMonitor}), not the tab-list latency ({@code
 *  PlayerInfo.getLatency()}) ChatCommandsFeature/TerminalTracker read for their own ping-aware logic — per
 *  user report ("ping shows 1ms"), tab-list latency on Hypixel's BungeeCord proxy can reflect the
 *  connection to the proxy edge rather than the real round trip to the backend Skyblock server, which is
 *  what this display actually needs to be useful. Server TPS has no real client-visible source at all
 *  (Hypixel never sends it directly), so this reuses {@link TpsMonitor}'s existing best-effort estimate
 *  (already relied on elsewhere in this codebase — Terminal Solver/Simon Says widen their own timing
 *  windows off it) rather than inventing a second one. Works on any connected server, not just Hypixel —
 *  both numbers are equally meaningful anywhere.
 *
 *  <p>Round 2 (per task tracker #483 — "Add separate ping/tps color pickers + split into separate movable
 *  GUI elements"): used to be a single combined "Ping: Xms  TPS: Y.Y" widget with one shared color. Split
 *  into two independently positioned/colored {@link MoveableWidget}s, same multi-widget-per-feature pattern
 *  {@link PlayerDisplayFeature}'s own StatWidget/SpeedWidget already establish — each widget still respects
 *  its own "Show Ping"/"Show TPS" toggle to turn off, same as before, just no longer sharing one position or
 *  color with the other number. Bold/italic stay a single shared style toggle (not asked to split). */
public class NetworkDisplayFeature extends Feature {
	private boolean showPing = true;
	private boolean showTps = true;
	private int pingColor = 0xFFFFFFFF;
	private int tpsColor = 0xFFFFFFFF;
	// Per user request ("Add a color coding option to the tps display. 18-20 is lime green, 15-17 is yellow
	// and 14 and below is red"): off by default so an existing user's own picked tpsColor keeps working
	// unchanged until they opt in — this only overrides the rendered color, tpsColor itself is untouched
	// (and still used verbatim in the example preview, which has no real live TPS to color-code against).
	private boolean tpsColorCoded = false;
	// Per user request ("Add an option for bold text and italic text for the network display"), same
	// §l/§o-prefix mechanism Boss Guide/Dungeon Notifications/Dungeon Timers titles already use.
	private boolean boldText = false;
	private boolean italicText = false;

	private static long currentPing() {
		if (Minecraft.getInstance().getConnection() == null) return -1;
		return RealPingMonitor.getPing();
	}

	private String stylePrefix() {
		return (boldText ? "§l" : "") + (italicText ? "§o" : "");
	}

	public final class PingWidget implements MoveableWidget {
		private final HudPosition defaultPosition = new HudPosition(0.02f, 0.02f, 1f);
		private final HudPosition position = defaultPosition.copy();

		@Override
		public String getId() { return "network_display_ping"; }

		@Override
		public String getDisplayName() { return "Network Display: Ping"; }

		@Override
		public HudPosition getPosition() { return position; }

		@Override
		public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

		@Override
		public boolean isVisible() { return isEnabled() && showPing && Minecraft.getInstance().getConnection() != null; }

		@Override
		public boolean isRelevantToCurrentIsland() { return true; }

		@Override
		public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
			long ping = currentPing();
			return renderText(graphics, x, y, scale, "Ping: " + (ping >= 0 ? ping + "ms" : "--"), pingColor);
		}
	}

	public final class TpsWidget implements MoveableWidget {
		private final HudPosition defaultPosition = new HudPosition(0.02f, 0.05f, 1f);
		private final HudPosition position = defaultPosition.copy();

		@Override
		public String getId() { return "network_display_tps"; }

		@Override
		public String getDisplayName() { return "Network Display: TPS"; }

		@Override
		public HudPosition getPosition() { return position; }

		@Override
		public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

		@Override
		public boolean isVisible() { return isEnabled() && showTps && Minecraft.getInstance().getConnection() != null; }

		@Override
		public boolean isRelevantToCurrentIsland() { return true; }

		@Override
		public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
			double tps = TpsMonitor.getEstimatedTps();
			// Real bug found (per user report — "The color code tps option inside of network display
			// shouldnt color code the entire line, only the tps number"): tpsColorCoded used to recolor the
			// WHOLE "TPS: 20.0" string, including the plain "TPS: " label — only the number itself should
			// react to the live TPS value. The label always renders in the base tpsColor now; the number
			// alone switches to tpsColorFor(tps) while color-coding is on.
			int numberColor = tpsColorCoded ? tpsColorFor(tps) : tpsColor;
			return renderTwoPart(graphics, x, y, scale, "TPS: ", String.format(Locale.ROOT, "%.1f", tps), tpsColor, numberColor);
		}
	}

	// Per user's exact spec: "18-20 is lime green, 15-17 is yellow and 14 and below is red".
	private static int tpsColorFor(double tps) {
		if (tps >= 18) return 0xFF55FF55;
		if (tps >= 15) return 0xFFFFFF55;
		return 0xFFFF5555;
	}

	public final PingWidget pingWidget = new PingWidget();
	public final TpsWidget tpsWidget = new TpsWidget();

	private static NetworkDisplayFeature instance;
	// HudElementRegistry.addLast throws IllegalArgumentException on a duplicate id (confirmed via
	// decompiling Fabric's own HudElementRegistryImpl) — onEnable() used to call it unconditionally, so
	// toggling this feature off then back on crashed the render pipeline the moment it re-enabled. Same
	// guarded-registration pattern already used correctly elsewhere in this codebase (see
	// PlayerDisplayFeature/ChatCopyFeature): register exactly once, ever, and let the render callback's own
	// isVisible() check make it a no-op while disabled.
	private static boolean listenersRegistered = false;

	public NetworkDisplayFeature() {
		super("network_display", "Network Display", FeatureCategory.PERFORMANCE, false);
		instance = this;
		HudWidgetRegistry.register(pingWidget);
		HudWidgetRegistry.register(tpsWidget);
	}

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		for (MoveableWidget widget : java.util.List.of(pingWidget, tpsWidget)) {
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", widget.getId()), (graphics, deltaTracker) -> {
				Minecraft mc = Minecraft.getInstance();
				if (!widget.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen() || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
				HudPosition pos = widget.getPosition();
				int x = Math.round(pos.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(pos.anchorY * mc.getWindow().getGuiScaledHeight());
				widget.render(graphics, x, y, pos.scale);
			});
		}
	}

	private MoveableWidget.Size renderText(GuiGraphicsExtractor graphics, int x, int y, float scale, String rawText, int color) {
		Font font = Minecraft.getInstance().font;
		String text = stylePrefix() + rawText;
		int width = Math.round(font.width(text) * scale);
		int height = Math.round(font.lineHeight * scale);
		if (Math.abs(scale - 1f) < 0.01f) {
			graphics.text(font, text, x, y, color);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			graphics.text(font, text, x, y, color);
			graphics.pose().popMatrix();
		}
		return new MoveableWidget.Size(width, height);
	}

	/** Same as {@link #renderText} but draws the label and value in two independently-colored halves — see
	 *  TpsWidget's own render() for why this exists (only the number should react to color-coding, not the
	 *  fixed "TPS: " label it's prefixed with). */
	private MoveableWidget.Size renderTwoPart(GuiGraphicsExtractor graphics, int x, int y, float scale,
											   String label, String value, int labelColor, int valueColor) {
		Font font = Minecraft.getInstance().font;
		// Real bug found (per user report — "The tps color coding doesnt match the bold and italic
		// options. The text should still match that, just not the color and instead be color coded"): the
		// style prefix (bold/italic) used to only get prepended to the label half — each graphics.text()
		// call is independent, so formatting codes baked into one string never carry over into a separate
		// call for the other. Applied to both halves now, so bold/italic still cover the whole readout
		// exactly like before color-coding split it into two draws; only the color itself differs per half.
		String prefix = stylePrefix();
		String styledLabel = prefix + label;
		String styledValue = prefix + value;
		int labelWidth = font.width(styledLabel);
		int valueWidth = font.width(styledValue);
		int width = Math.round((labelWidth + valueWidth) * scale);
		int height = Math.round(font.lineHeight * scale);
		Runnable draw = () -> {
			graphics.text(font, styledLabel, x, y, labelColor);
			graphics.text(font, styledValue, x + labelWidth, y, valueColor);
		};
		if (Math.abs(scale - 1f) < 0.01f) {
			draw.run();
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			draw.run();
			graphics.pose().popMatrix();
		}
		return new MoveableWidget.Size(width, height);
	}

	public boolean isShowPing() { return showPing; }
	public void setShowPing(boolean value) { showPing = value; }
	public boolean isShowTps() { return showTps; }
	public void setShowTps(boolean value) { showTps = value; }
	public int getPingColor() { return pingColor; }
	public void setPingColor(int value) { pingColor = value; }
	public int getTpsColor() { return tpsColor; }
	public void setTpsColor(int value) { tpsColor = value; }
	public boolean isTpsColorCoded() { return tpsColorCoded; }
	public void setTpsColorCoded(boolean value) { tpsColorCoded = value; }
	public boolean isBoldText() { return boldText; }
	public void setBoldText(boolean value) { boldText = value; }
	public boolean isItalicText() { return italicText; }
	public void setItalicText(boolean value) { italicText = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showPing", showPing);
		obj.addProperty("showTps", showTps);
		obj.addProperty("pingColor", pingColor);
		obj.addProperty("tpsColor", tpsColor);
		obj.addProperty("tpsColorCoded", tpsColorCoded);
		obj.addProperty("boldText", boldText);
		obj.addProperty("italicText", italicText);
		obj.addProperty("pingAnchorX", pingWidget.position.anchorX);
		obj.addProperty("pingAnchorY", pingWidget.position.anchorY);
		obj.addProperty("pingScale", pingWidget.position.scale);
		obj.addProperty("tpsAnchorX", tpsWidget.position.anchorX);
		obj.addProperty("tpsAnchorY", tpsWidget.position.anchorY);
		obj.addProperty("tpsScale", tpsWidget.position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("showPing")) showPing = obj.get("showPing").getAsBoolean();
		if (obj.has("showTps")) showTps = obj.get("showTps").getAsBoolean();
		// pingColor/tpsColor fall back to the old shared "textColor" field so a config saved before this
		// round's split still restores its one previous color onto both new widgets, rather than silently
		// resetting to white.
		int legacyColor = obj.has("textColor") ? obj.get("textColor").getAsInt() : 0xFFFFFFFF;
		pingColor = obj.has("pingColor") ? obj.get("pingColor").getAsInt() : legacyColor;
		tpsColor = obj.has("tpsColor") ? obj.get("tpsColor").getAsInt() : legacyColor;
		if (obj.has("tpsColorCoded")) tpsColorCoded = obj.get("tpsColorCoded").getAsBoolean();
		if (obj.has("boldText")) boldText = obj.get("boldText").getAsBoolean();
		if (obj.has("italicText")) italicText = obj.get("italicText").getAsBoolean();
		if (obj.has("pingAnchorX") && obj.has("pingAnchorY")) {
			float s = obj.has("pingScale") ? obj.get("pingScale").getAsFloat() : pingWidget.position.scale;
			pingWidget.position.set(obj.get("pingAnchorX").getAsFloat(), obj.get("pingAnchorY").getAsFloat(), s);
		} else if (obj.has("anchorX") && obj.has("anchorY")) {
			// Legacy single combined-widget position — reused as the Ping widget's starting point (the more
			// visually prominent of the two, shown first in the old combined text) so a pre-split config
			// doesn't just reset both widgets to their hardcoded defaults with no relation to where the
			// player actually had this before.
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : pingWidget.position.scale;
			pingWidget.position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
		if (obj.has("tpsAnchorX") && obj.has("tpsAnchorY")) {
			float s = obj.has("tpsScale") ? obj.get("tpsScale").getAsFloat() : tpsWidget.position.scale;
			tpsWidget.position.set(obj.get("tpsAnchorX").getAsFloat(), obj.get("tpsAnchorY").getAsFloat(), s);
		}
	}

	@Override
	public String getDescription() {
		return "Shows your real ping and the server's TPS as separate movable HUD widgets.";
	}
}
