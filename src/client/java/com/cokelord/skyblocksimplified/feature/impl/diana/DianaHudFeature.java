package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.hud.ChatOverlapUtil;
import com.cokelord.skyblocksimplified.hud.DebugScreenGate;
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

import java.util.List;

/** A movable (HUD editor) text-lines widget for the Diana modules. Subclasses supply the lines. */
public abstract class DianaHudFeature extends DianaFeature implements MoveableWidget {
	private final HudPosition defaultPosition;
	private final HudPosition position;
	private boolean hudRegistered = false;

	protected DianaHudFeature(String id, String displayName, String description, float anchorX, float anchorY) {
		this(id, displayName, com.cokelord.skyblocksimplified.feature.FeatureCategory.EVENTS, description, anchorX, anchorY);
	}

	protected DianaHudFeature(String id, String displayName, com.cokelord.skyblocksimplified.feature.FeatureCategory category,
			String description, float anchorX, float anchorY) {
		super(id, displayName, category, false, description);
		this.defaultPosition = new HudPosition(anchorX, anchorY, 1f);
		this.position = defaultPosition.copy();
		HudWidgetRegistry.register(this);
	}

	/** Lines to draw right now (legacy § codes allowed), or an empty list to draw nothing. */
	protected abstract List<String> lines();

	/** Placeholder lines for the HUD editor while there's nothing live to show. */
	protected abstract List<String> exampleLines();

	/** Gameplay visibility besides being enabled (e.g. Hub + event active). */
	protected abstract boolean shouldShow();

	@Override
	protected void onEnable() {
		if (hudRegistered) return;
		hudRegistered = true;
		HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", getId()),
			(graphics, tracker) -> {
				if (!isVisible() || DebugScreenGate.isOpen() || ChatOverlapUtil.isBlockingScreen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(position.anchorY * mc.getWindow().getGuiScaledHeight());
				render(graphics, x, y, position.scale);
			});
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() {
		return isEnabled() && shouldShow() && !lines().isEmpty();
	}

	@Override
	public boolean hasVisibleContent() {
		return isEnabled() && shouldShow() && !lines().isEmpty();
	}

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		List<String> lines = lines();
		if (lines.isEmpty()) lines = exampleLines();
		int width = 0;
		for (String line : lines) width = Math.max(width, font.width(line));
		int height = lines.size() * (font.lineHeight + 1);
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale);
		int ly = 0;
		for (String line : lines) {
			graphics.text(font, line, 0, ly, 0xFFFFFFFF);
			ly += font.lineHeight + 1;
		}
		graphics.pose().popMatrix();
		return new Size(Math.round(width * scale), Math.round(height * scale));
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = super.savePersistedData().getAsJsonObject();
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (data == null || !data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
	}
}
