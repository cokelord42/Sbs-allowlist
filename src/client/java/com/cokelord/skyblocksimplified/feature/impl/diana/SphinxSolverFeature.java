package com.cokelord.skyblocksimplified.feature.impl.diana;

public class SphinxSolverFeature extends DianaFeature {
	private static SphinxSolverFeature instance;

	public SphinxSolverFeature() {
		super("diana_sphinx_solver", "Sphinx Solver", false,
			"Marks the correct answer to the Sphinx's riddle green (others red); click any answer line to submit the right one.");
		instance = this;
	}

	public static boolean on() { return instance != null && instance.isEnabled(); }
}
