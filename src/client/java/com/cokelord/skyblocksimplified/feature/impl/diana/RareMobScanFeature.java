package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.RareMobs;
import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.ArrayList;
import java.util.List;

public class RareMobScanFeature extends DianaFeature {
	private static RareMobScanFeature instance;

	public RareMobScanFeature() {
		super("diana_scan_rare_mobs", "Scan World For Rare Mobs", false,
			"Scans loaded chunks for rare Diana mobs you can see and places a waypoint and title when one appears.");
		for (RareMobs.Mob mob : RareMobs.Mob.values()) defBool(mob.key(), true);
		defBool("title", true);
		defBool("ignoreOwn", true);
		instance = this;
	}

	public static RareMobScanFeature get() { return instance; }

	@Override
	public List<SettingRow> getSettingRows() {
		List<SettingRow> mobs = new ArrayList<>();
		for (RareMobs.Mob mob : RareMobs.Mob.values()) mobs.add(toggle(mob.displayName, mob.key(), "Scan for " + mob.displayName + "."));
		return List.of(
			toggle("Title", "title", "Shows a title when a rare mob is found."),
			toggle("Ignore Your Own Spawns", "ignoreOwn", "Skip a rare mob you just dug up yourself."),
			new SettingRow.Group("Mobs To Scan For", "mobs", mobs));
	}
}
