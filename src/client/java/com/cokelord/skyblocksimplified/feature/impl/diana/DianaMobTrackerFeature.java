package com.cokelord.skyblocksimplified.feature.impl.diana;

import java.util.List;

/** Mob lines are SBO's DianaMobs overlay (same keys/colors), LS rare mobs split out. */
public class DianaMobTrackerFeature extends DianaCountsHudFeature {
	public DianaMobTrackerFeature() {
		super("diana_mob_tracker", "Mob Tracker",
			"Counts every Diana mob you dig up this event (plus lootshared rare mobs), with your all-time totals.",
			"Diana Mobs", List.of(
				new Line("KING_MINOS", "King Minos", "§c"),
				new Line("KING_MINOS_LS", "King Minos (LS)", "§c"),
				new Line("MANTICORE", "Manticore", "§c"),
				new Line("MANTICORE_LS", "Manticore (LS)", "§c"),
				new Line("MINOS_INQUISITOR", "Minos Inquisitor", "§d"),
				new Line("MINOS_INQUISITOR_LS", "Minos Inquisitor (LS)", "§d"),
				new Line("SPHINX", "Sphinx", "§d"),
				new Line("SPHINX_LS", "Sphinx (LS)", "§d"),
				new Line("MINOS_CHAMPION", "Minos Champion", "§5"),
				new Line("MINOTAUR", "Minotaur", "§6"),
				new Line("GAIA_CONSTRUCT", "Gaia Construct", "§a"),
				new Line("HARPY", "Harpy", "§a"),
				new Line("CRETAN_BULL", "Cretan Bull", "§a"),
				new Line("STRANDED_NYMPH", "Stranded Nymph", "§a"),
				new Line("SIAMESE_LYNXES", "Siamese Lynxes", "§a"),
				new Line("MINOS_HUNTER", "Minos Hunter", "§a"),
				new Line("TOTAL_MOBS", "Total Mobs", "§7")),
			0.01f, 0.55f);
	}
}
