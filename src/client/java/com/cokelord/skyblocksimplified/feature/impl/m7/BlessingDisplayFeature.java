package com.cokelord.skyblocksimplified.feature.impl.m7;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.m7.DungeonBlessings;
import com.cokelord.skyblocksimplified.dungeon.m7.M7Dragons;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaHudFeature;

import java.util.ArrayList;
import java.util.List;

/** Odin's Blessing Display: the dungeon's current blessing levels (from the tab-list footer) as a HUD. */
public class BlessingDisplayFeature extends DianaHudFeature {
	public BlessingDisplayFeature() {
		super("blessing_display", "Blessing Display", FeatureCategory.COMBAT,
			"Shows the dungeon's current Power and Wisdom blessing levels (optionally Time, Stone and Life).", 0.02f, 0.4f);
		defBool("power", true);
		defBool("wisdom", true);
		defBool("time", false);
		defBool("stone", false);
		defBool("life", false);
		DungeonBlessings.register();
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected List<String> lines() {
		List<String> out = new ArrayList<>();
		add(out, "power", "§4Power", DungeonBlessings.Blessing.POWER);
		add(out, "wisdom", "§bWisdom", DungeonBlessings.Blessing.WISDOM);
		add(out, "time", "§5Time", DungeonBlessings.Blessing.TIME);
		add(out, "stone", "§7Stone", DungeonBlessings.Blessing.STONE);
		add(out, "life", "§cLife", DungeonBlessings.Blessing.LIFE);
		return out;
	}

	private void add(List<String> out, String key, String label, DungeonBlessings.Blessing blessing) {
		if (!bool(key) || blessing.current() <= 0) return;
		String paul = blessing == DungeonBlessings.Blessing.POWER && M7Dragons.paulBlessingsActive() ? " §8(§6Paul §7x1.25§8)" : "";
		out.add(label + ": §a" + blessing.current() + paul);
	}

	@Override
	protected List<String> exampleLines() { return List.of("§4Power: §a19", "§bWisdom: §a12"); }

	@Override
	protected boolean shouldShow() { return DungeonState.isInDungeon(); }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			toggle("Power Blessing", "power", "Shows the Power blessing level."),
			toggle("Wisdom Blessing", "wisdom", "Shows the Wisdom blessing level."),
			toggle("Time Blessing", "time", "Shows the Time blessing level."),
			toggle("Stone Blessing", "stone", "Shows the Stone blessing level."),
			toggle("Life Blessing", "life", "Shows the Life blessing level."));
	}
}
