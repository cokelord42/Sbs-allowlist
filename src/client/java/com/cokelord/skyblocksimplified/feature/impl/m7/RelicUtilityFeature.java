package com.cokelord.skyblocksimplified.feature.impl.m7;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.m7.M7Relics;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaFeature;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * M7 "Relic Utility": highlights the altar for the relic in your hotbar (relic-colored, two blocks tall) until
 * it's placed. Its Relic Timer lives in Dungeon Timers; the two are linked — this module being enabled IS
 * Dungeon Timers' "Relic Timer" toggle (see {@link #isLinkedEnabled}/{@link #setLinkedEnabled}).
 */
public class RelicUtilityFeature extends DianaFeature {
	public static final String ID = "m7_relic_utility";
	private static final String[] STYLES = {"3D", "3D Fill", "2D", "2D Fill"};
	private static RelicUtilityFeature instance;
	private boolean hooksRegistered = false;

	public RelicUtilityFeature() {
		super(ID, "Relic Utility", FeatureCategory.COMBAT, false,
			"M7 phase 5: highlights the altar for the relic you're holding (relic-colored) until it's placed. "
				+ "Includes the Relic Timer in Dungeon Timers (the two are linked).");
		defInt("style", 1);
		defBool("occlusion", false);
		defInt("fillOpacity", 30);
		instance = this;
		M7Relics.register();
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	/** Dungeon Timers' "Relic Timer" toggle reads/writes this module's enabled state. */
	public static boolean isLinkedEnabled() {
		return FeatureRegistry.get(ID) instanceof RelicUtilityFeature f && f.isEnabled();
	}

	public static void setLinkedEnabled(boolean value) {
		if (FeatureRegistry.get(ID) instanceof RelicUtilityFeature f) f.setEnabled(value);
	}

	@Override
	protected void onEnable() {
		super.onEnable();
		if (hooksRegistered) return;
		hooksRegistered = true;
		World3DRenderer.addRenderCallback(() -> {
			if (instance == null || !instance.isEnabled() || instance.integer("style") >= 2) return;
			try {
				M7Relics.Relic r = M7Relics.heldRelic();
				if (r != null) instance.draw3D(r);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Relic highlight render failed", e);
			}
		});
		HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "m7_relic_utility_world"),
			(graphics, tracker) -> {
				if (instance == null || !instance.isEnabled() || instance.integer("style") < 2) return;
				try {
					M7Relics.Relic r = M7Relics.heldRelic();
					if (r != null) instance.draw2D(graphics, r);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Relic highlight render failed", e);
				}
			});
	}

	private int fillArgb(int color) {
		int alpha = Math.round(Math.max(0, Math.min(100, integer("fillOpacity"))) / 100f * 255f);
		return (alpha << 24) | (color & 0xFFFFFF);
	}

	private void draw3D(M7Relics.Relic r) {
		AABB box = r.altarBox;
		boolean occlusion = bool("occlusion");
		if (integer("style") == 1) {
			if (occlusion) World3DRenderer.drawFilledBox(box, fillArgb(r.color));
			else World3DRenderer.drawFilledBoxThroughWalls(box, fillArgb(r.color));
		}
		if (occlusion) World3DRenderer.drawWireBox(box, r.color, 2f);
		else World3DRenderer.drawWireBoxThroughWalls(box, r.color, 2f);
	}

	private void draw2D(GuiGraphicsExtractor graphics, M7Relics.Relic r) {
		if (integer("style") == 3) WorldRenderUtil.drawFilledBox(graphics, r.altarBox, r.color, integer("fillOpacity"));
		WorldRenderUtil.drawWireBox(graphics, r.altarBox, r.color, 2);
	}

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			new SettingRow.Cycle("Highlight Style", () -> STYLES[Math.floorMod(integer("style"), 4)],
				() -> setInt("style", (integer("style") + 1) % 4), "3D box, filled 3D box, 2D box or filled 2D box."),
			new SettingRow.Toggle("Occlusion", () -> bool("occlusion"), v -> setBool("occlusion", v),
				"3D styles: hide the box behind walls (off = visible through walls).", () -> integer("style") < 2),
			new SettingRow.Slider("Fill Opacity", 0, 100, () -> integer("fillOpacity"), v -> setInt("fillOpacity", v), "%",
				"Opacity of the filled styles.", () -> integer("style") == 1 || integer("style") == 3));
	}
}
