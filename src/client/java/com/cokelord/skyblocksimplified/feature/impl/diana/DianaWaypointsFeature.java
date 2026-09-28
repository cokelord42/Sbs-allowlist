package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.List;

/** Display settings for every Diana waypoint (burrows, guesses, rare mobs, party coordinates). */
public class DianaWaypointsFeature extends DianaFeature {
	private static DianaWaypointsFeature instance;

	public DianaWaypointsFeature() {
		super("diana_waypoints", "Diana Waypoints", true,
			"Shows burrow, guess and rare mob waypoints: a text label while far away, and a block highlight once you're close.");
		defInt("boxDistance", 10);
		defBool("showDistance", true);
		defBool("showTimesDug", true);
		defBool("warpText", true);
		defBool("lineToClosest", true);
		defBool("lineToRareMob", true);
		defInt("lineWidth", 2);
		defInt("fillOpacity", 30);
		instance = this;
	}

	public static DianaWaypointsFeature get() { return instance; }
	public static boolean on() { return instance != null && instance.isEnabled(); }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			slider("Box Distance", "boxDistance", 3, 30, " blocks", "Closer than this, the label turns into a highlighted block."),
			toggle("Show Distance", "showDistance", "Adds the distance in blocks to each label."),
			toggle("Show Times Dug", "showTimesDug", "Shows how many times a Mob/Treasure burrow has been dug out of 2."),
			toggle("Show Warp On Closest", "warpText", "The closest waypoint's label shows which warp gets you there fastest."),
			toggle("Line To Closest Burrow", "lineToClosest", "Draws a line from your crosshair to the closest burrow or guess."),
			toggle("Line To Rare Mob", "lineToRareMob", "Draws a line to the newest rare mob waypoint."),
			slider("Line Width", "lineWidth", 1, 5, "", "Thickness of the guide lines."),
			slider("Box Fill Opacity", "fillOpacity", 0, 100, "%", "How solid the close-range block highlight is."));
	}
}
