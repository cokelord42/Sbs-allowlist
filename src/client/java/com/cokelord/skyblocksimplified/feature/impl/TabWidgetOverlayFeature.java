package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Shared plumbing for the small text-overlay features ported from SkyHanni's Hypixel tab-list widgets
 * (Jacob's Contest, Pest Timer, Bonus Pest Chance, Visitor Timer) — each just supplies its own current
 * lines from currentLines(), and gets HUD registration, positioning (via the same "Edit gui locations"
 * screen as Custom Scoreboard), and rendering for free instead of repeating this boilerplate per feature.
 */
public abstract class TabWidgetOverlayFeature extends Feature implements MoveableWidget {
	private final String subcategory;
	private final HudPosition defaultPosition;
	private final HudPosition position;
	private final Identifier hudElementId;
	private boolean hudElementRegistered = false;
	private int lastWidth = 0;
	private int lastHeight = 0;

	protected TabWidgetOverlayFeature(String id, String displayName, FeatureCategory category, String subcategory,
									   float defaultAnchorX, float defaultAnchorY) {
		super(id, displayName, category, false);
		this.subcategory = subcategory;
		this.defaultPosition = new HudPosition(defaultAnchorX, defaultAnchorY, 1f);
		this.position = defaultPosition.copy();
		this.hudElementId = Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, id);
		HudWidgetRegistry.register(this);
	}

	/** The lines to render right now, top to bottom. Empty means "nothing detected yet" — still
	 *  registered/positionable, just renders nothing this frame. */
	protected abstract List<String> currentLines();

	@Override
	public String getSubcategory() {
		return subcategory;
	}

	// Unconditionally removes-then-adds on enable / removes on disable, rather than gating on a
	// hudElementRegistered boolean that can drift out of sync with the registry's own real state — see
	// CustomScoreboardFeature's onEnable doc comment for the exact race this fixes (rapid toggling could
	// leave the flag claiming "registered" while the registry no longer had it, permanently no-op'ing
	// every future enable).
	@Override
	protected void onEnable() {
		try {
			HudElementRegistry.removeElement(hudElementId);
		} catch (Exception ignored) {
			// Wasn't registered — the common case.
		}
		try {
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, hudElementId, this::extractHud);
			hudElementRegistered = true;
		} catch (Exception e) {
			hudElementRegistered = false;
			SkyblockSimplified.LOGGER.error("Failed to register {} HUD layer", getId(), e);
		}
	}

	@Override
	protected void onDisable() {
		try {
			HudElementRegistry.removeElement(hudElementId);
		} catch (Exception ignored) {
			// Already gone — fine.
		}
		hudElementRegistered = false;
	}

	private void extractHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		// The whole body used to only wrap render() in try-catch — isVisible() (which calls out to
		// IslandGate.isInGarden(), itself reading the live scoreboard) ran completely unprotected before
		// it. Every farming widget shares this exact same extractHud() method, each registered as its own
		// separate HudElementRegistry layer — if isVisible() threw for any single one of them and Fabric's
		// per-frame Hud dispatch doesn't isolate between registered layers (the same missing-isolation bug
		// already found and fixed at several other call sites this session — FeatureRegistry.tickAll, raw
		// ClientTickEvents listeners, ConfigManager), an uncaught throw here could plausibly take out every
		// OTHER farming widget's render for that frame too, not just this one's — which reads exactly like
		// "all the farming HUD widgets die together," recurring even after tickAll's own isolation fix,
		// since this is a completely separate code path (render, not tick).
		try {
			if (!isVisible()) return;
			Minecraft mc = Minecraft.getInstance();
			int x = Math.round(position.anchorX * mc.getWindow().getGuiScaledWidth());
			int y = Math.round(position.anchorY * mc.getWindow().getGuiScaledHeight());
			// Chat only covers a small bottom-left region — check against last frame's measured size (one
			// frame stale, imperceptible) rather than blanket-hiding for any open screen, since ChatScreen
			// counts as "a screen" too and this widget may not be anywhere near it.
			if (com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isCoveredByChat(x, y, x + lastWidth, y + lastHeight)) return;
			Size size = render(graphics, x, y, position.scale);
			lastWidth = size.width();
			lastHeight = size.height();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("{} render failed, skipping this frame", getId(), e);
		}
	}

	@Override
	public boolean isVisible() {
		boolean enabled = isEnabled();
		boolean blockingScreen = com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen();
		boolean debugScreen = Minecraft.getInstance().getDebugOverlay().showDebugScreen();
		boolean gardenOk = !requiresGarden() || com.cokelord.skyblocksimplified.util.IslandGate.isInGarden();
		return enabled && !blockingScreen && !debugScreen && gardenOk;
	}

	/** True for widgets that only make sense on the Garden (Farming Fortune, Money/Hour, Pest Timer,
	 *  etc.) — without this, several of these were showing in the main lobby and anywhere else on the
	 *  island list, since nothing previously gated any HUD widget by island at all. */
	protected boolean requiresGarden() {
		return false;
	}

	// Per user request: the "Edit gui locations" screen didn't consult requiresGarden() at all (only
	// isVisible() does, and the edit screen deliberately doesn't call isVisible() — that's the "let me
	// position a disabled widget in advance" exemption) — so a Garden-only widget like Pest Timer stayed
	// listed there no matter which island the player was actually standing on. Reuses the exact same flag
	// isVisible() already keys off, just without the isEnabled()/blockingScreen/debugScreen parts of that
	// check, which the edit screen intentionally ignores for other reasons.
	@Override
	public boolean isRelevantToCurrentIsland() {
		return !requiresGarden() || com.cokelord.skyblocksimplified.util.IslandGate.isInGarden();
	}

	@Override
	public HudPosition getPosition() {
		return position;
	}

	@Override
	public boolean rightAlignsPastCenter() {
		return true;
	}

	@Override
	public boolean hasVisibleContent() {
		return !currentLines().isEmpty();
	}

	@Override
	public void resetPosition() {
		position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale);
		position.scale = defaultPosition.scale;
	}

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		List<String> lines = currentLines();
		if (lines.isEmpty()) return new Size(0, 0);

		Font font = Minecraft.getInstance().font;
		int maxWidth = 0;
		for (String line : lines) {
			maxWidth = Math.max(maxWidth, font.width(line));
		}

		// Widgets are user-draggable anywhere on screen via "Edit gui locations"; always drawing left-
		// aligned (growing rightward from the anchor) reads fine on the left half but runs long lines off
		// the right edge of the screen once dragged past center. Flipping to right-aligned (growing
		// leftward, anchor becomes the right edge) for anything right of the crosshair — screen center —
		// keeps every widget's text on-screen and hugging whichever edge it's actually closest to.
		int screenCenterX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2;
		boolean rightAligned = x > screenCenterX;

		int lineHeight = Math.round((font.lineHeight + 1) * scale);
		int curY = y;
		for (String line : lines) {
			int lineWidth = Math.round(font.width(line) * scale);
			int drawX = rightAligned ? x - lineWidth : x;
			drawScaled(graphics, font, line, drawX, curY, scale);
			curY += lineHeight;
		}
		return new Size(Math.round(maxWidth * scale), curY - y);
	}

	// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when i
	// relaunch"): this whole 13-subclass family (Tree Progress, Visitor Timer, Item Pickup Log, Jacob's
	// Contest, Money/Hour, Moonglade Beacon Alert, Nametag Glyph Indicator, Pest Cooldown, Armor Stack
	// Display, Bonus Pest Chance, Farming Fortune Display, Farming Lane Detection, Flare Display) shares
	// this one base class's `position` field, but the base class never persisted it at all — every subclass
	// either had no savePersistedData()/loadPersistedData() override whatsoever (silently inheriting
	// Feature's no-op default) or an override that only covered its own feature-specific settings. Dragging
	// or resizing any of these via "Edit gui locations" was never saved, only ever felt like it worked
	// because the position/scale stayed correct for the rest of that same session. Subclasses with their
	// own override now call these via super and merge their own keys into the same JsonObject rather than
	// each needing to duplicate this.
	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
	}

	private void drawScaled(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, float scale) {
		Component component = Component.literal(text);
		if (Math.abs(scale - 1f) < 0.01f) {
			graphics.text(font, component, x, y, 0xFFFFFFFF);
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale);
		graphics.pose().translate(-x, -y);
		graphics.text(font, component, x, y, 0xFFFFFFFF);
		graphics.pose().popMatrix();
	}

	@Override
	public String getDescription() {
		return "Shows " + getDisplayName() + " as a movable HUD readout.";
	}
}
