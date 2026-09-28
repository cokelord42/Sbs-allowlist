package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.DianaMobs;
import com.cokelord.skyblocksimplified.diana.DianaState;
import com.cokelord.skyblocksimplified.diana.RareMobs;
import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.ArrayList;
import java.util.List;

public class NoShurikenFeature extends DianaHudFeature {
	private static NoShurikenFeature instance;

	public NoShurikenFeature() {
		super("diana_no_shuriken", "No Shuriken Overlay",
			"Shows \"No shuriken!\" on screen while a rare Diana mob hasn't been hit with an Extremely Sharp Shuriken.", 0.5f, 0.3f);
		for (RareMobs.Mob mob : RareMobs.Mob.values()) defBool(mob.key(), true);
		instance = this;
	}

	/** Whether the overlay checks this rare mob (display name). */
	public static boolean checks(String rareMobName) {
		if (instance == null || !instance.isEnabled()) return false;
		RareMobs.Mob mob = RareMobs.Mob.fromName(rareMobName);
		return mob != null && instance.bool(mob.key());
	}

	@Override
	protected List<String> lines() {
		return DianaMobs.noShuriken() ? List.of("§lNo shuriken!") : List.of();
	}

	@Override
	protected List<String> exampleLines() { return List.of("§lNo shuriken!"); }

	@Override
	protected boolean shouldShow() { return DianaState.inHub(); }

	@Override
	public Size render(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, float scale) {
		net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
		String line = "§lNo shuriken!";
		int color = DianaColorsFeature.color("noShuriken", 0xFF5555) | 0xFF000000;
		float s = scale * 2f;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(s);
		graphics.text(font, line, 0, 0, color);
		graphics.pose().popMatrix();
		return new Size(Math.round(font.width(line) * s), Math.round(font.lineHeight * s));
	}

	@Override
	public List<SettingRow> getSettingRows() {
		List<SettingRow> mobs = new ArrayList<>();
		for (RareMobs.Mob mob : RareMobs.Mob.values()) mobs.add(toggle(mob.displayName, mob.key(), "Check " + mob.displayName + " for a shuriken."));
		return List.of(new SettingRow.Group("Mobs To Check", "mobs", mobs));
	}
}
