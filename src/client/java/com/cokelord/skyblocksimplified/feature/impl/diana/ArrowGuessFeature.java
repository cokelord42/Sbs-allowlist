package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.List;

public class ArrowGuessFeature extends DianaFeature {
	private static ArrowGuessFeature instance;

	public ArrowGuessFeature() {
		super("diana_arrow_guess", "Arrow Guess", false,
			"Guesses the next burrow from the particle arrow that appears after digging a burrow in a chain. "
				+ "Requires /particlequality Extreme with Dust and Smoke particles.");
		defBool("subGuesses", false);
		defBool("subGuessText", false);
		instance = this;
	}

	public static boolean on() { return instance != null && instance.isEnabled(); }
	public static boolean subGuesses() { return instance != null && instance.bool("subGuesses"); }
	public static boolean subGuessText() { return instance != null && instance.bool("subGuessText"); }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			toggle("Show Other Possible Spots", "subGuesses",
				"Also marks the other blocks along the arrow the burrow could be on, until the main guess moves to one."),
			new SettingRow.Toggle("\"Possible\" Text On Other Spots", () -> instance.bool("subGuessText"),
				v -> instance.setBool("subGuessText", v), "Labels those extra spots with \"Possible\".", () -> instance.bool("subGuesses")));
	}
}
