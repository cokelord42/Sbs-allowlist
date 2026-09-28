package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.RareMobs;
import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.ArrayList;
import java.util.List;

public class RareMobReceiveFeature extends DianaFeature {
	private static RareMobReceiveFeature instance;

	public RareMobReceiveFeature() {
		super("diana_receive_rare_mobs", "Receive Rare Mobs", false,
			"Places a waypoint (and shows a title) when someone shares rare mob coordinates in chat.");
		for (RareMobs.Mob mob : RareMobs.Mob.values()) defBool(mob.key(), true);
		defBool("title", true);
		defBool("ignoreOwn", true);
		defBool("plainCoords", false);
		instance = this;
	}

	public static RareMobReceiveFeature get() { return instance; }

	@Override
	public List<SettingRow> getSettingRows() {
		List<SettingRow> mobs = new ArrayList<>();
		for (RareMobs.Mob mob : RareMobs.Mob.values()) mobs.add(toggle(mob.displayName, mob.key(), "React to shared " + mob.displayName + " coordinates."));
		return List.of(
			toggle("Title", "title", "Shows a title with who shared it."),
			toggle("Ignore Your Own Shares", "ignoreOwn", "Don't make a waypoint from coordinates you sent yourself."),
			toggle("Waypoint Plain Coordinates", "plainCoords", "Also turns any other \"x: y: z:\" coordinates in party chat into a 30s waypoint."),
			new SettingRow.Group("Mobs To Receive", "mobs", mobs));
	}
}
