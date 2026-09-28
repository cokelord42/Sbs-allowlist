package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.List;

public class SpadeGuessFeature extends DianaFeature {
	private static SpadeGuessFeature instance;

	public SpadeGuessFeature() {
		super("diana_spade_guess", "Spade Guess", false,
			"Guesses where the next burrow is from the particle trail your spade ability shoots. "
				+ "Requires /particlequality Extreme and Dripping Lava particles.");
		defBool("blockSpadeDuringTrail", true);
		instance = this;
	}

	public static boolean on() { return instance != null && instance.isEnabled(); }

	/** SBO behavior: a second spade use while the first trail is still flying restarts the trail, so it's
	 *  swallowed for 200ms after the last trail particle. */
	public static boolean blockSpadeDuringTrail() { return instance != null && instance.bool("blockSpadeDuringTrail"); }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			toggle("Block Spade While Trail Is Flying", "blockSpadeDuringTrail",
				"Ignores a spade use while the previous trail is still flying, so it can't wipe the guess before it finishes."));
	}
}
