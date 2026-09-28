package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.List;

/** Hex/color pickers for every Diana waypoint type and title. Defaults match SBO's. */
public class DianaColorsFeature extends DianaFeature {
	private static DianaColorsFeature instance;

	public DianaColorsFeature() {
		super("diana_colors", "Diana Colors", false, "Colors (with hex input) for Diana waypoints and titles.");
		defColor("start", 0x55FF55);
		defColor("mob", 0xFF5555);
		defColor("treasure", 0xFFAA00);
		defColor("closestGuess", 0x9933CC);
		defColor("otherGuess", 0x00F6FF);
		defColor("subGuess", 0x8C8C8C);
		defColor("rareMob", 0xFFD700);
		defColor("party", 0x0033FF);
		defColor("titleInquisitor", 0xFF55FF);
		defColor("titleKing", 0xFFAA00);
		defColor("titleManticore", 0x00AA00);
		defColor("titleSphinx", 0x5555FF);
		defColor("titleCocoon", 0x55FFFF);
		defColor("noShuriken", 0xFF5555);
		instance = this;
	}

	@Override
	public boolean isToggleable() { return false; }

	/** Opaque ARGB for {@code key}, or the fallback before the feature exists. */
	public static int color(String key, int fallback) {
		return instance == null ? fallback | 0xFF000000 : instance.integer(key);
	}

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			new SettingRow.Group("Waypoint Colors", "waypoints", List.of(
				color("Start Burrow", "start"),
				color("Mob Burrow", "mob"),
				color("Treasure Burrow", "treasure"),
				color("Closest Guess", "closestGuess"),
				color("Other Guesses", "otherGuess"),
				color("Other Possible Spots", "subGuess"),
				color("Rare Mob", "rareMob"),
				color("Party Coordinates", "party"))),
			new SettingRow.Group("Title Colors", "titles", List.of(
				color("Minos Inquisitor", "titleInquisitor"),
				color("King Minos", "titleKing"),
				color("Manticore", "titleManticore"),
				color("Sphinx", "titleSphinx"),
				color("Cocoon", "titleCocoon"),
				color("No Shuriken Text", "noShuriken"))));
	}
}
