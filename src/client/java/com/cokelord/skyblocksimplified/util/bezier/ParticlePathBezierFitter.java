package com.cokelord.skyblocksimplified.util.bezier;

import net.minecraft.world.phys.Vec3;

/**
 * Solves the fitted curve for a target position by walking out from the start point along its own
 * tangent, weighted by an empirically-tuned pitch factor — direct port of SkyHanni's real, working
 * ParticlePathBezierFitter.kt (used for its vacuum pest-tracking waypoint). The pitch-weighting solves
 * an inverse trig equation (no closed form) via 100 iterations of binary search, matching the original
 * exactly rather than approximating it.
 */
public final class ParticlePathBezierFitter extends BezierFitter {
	public ParticlePathBezierFitter(int degree) {
		super(degree);
	}

	public Vec3 solve() {
		BezierCurve curve = fit();
		if (curve == null) return null;

		Vec3 startPointDerivative = curve.derivativeAt(0.0);
		double controlPointDistance = computePitchWeight(startPointDerivative);
		double derivativeLength = startPointDerivative.length();
		if (derivativeLength == 0.0) return null;

		double t = 3 * controlPointDistance / derivativeLength;
		return curve.at(t);
	}

	private static double computePitchWeight(Vec3 derivative) {
		return Math.sqrt(24 * Math.sin(getPitchFromDerivative(derivative) - Math.PI) + 25);
	}

	private static double getPitchFromDerivative(Vec3 derivative) {
		double xzLength = Math.sqrt(derivative.x * derivative.x + derivative.z * derivative.z);
		double pitchRadians = -Math.atan2(derivative.y, xzLength);
		// Solve y = atan2(sin(x) - 0.75, cos(x)) for x from y — no closed form, so binary-search it the
		// same way the original does (the 0.75 constant is an empirically-tuned real Hypixel gravity
		// factor for this particle's ballistic arc).
		double guessPitch = pitchRadians;
		double resultPitch = Math.atan2(Math.sin(guessPitch) - 0.75, Math.cos(guessPitch));
		double windowMax = Math.PI / 2;
		double windowMin = -Math.PI / 2;
		for (int i = 0; i < 100; i++) {
			if (resultPitch < pitchRadians) {
				windowMin = guessPitch;
			} else {
				windowMax = guessPitch;
			}
			guessPitch = (windowMin + windowMax) / 2;
			resultPitch = Math.atan2(Math.sin(guessPitch) - 0.75, Math.cos(guessPitch));
			if (resultPitch == pitchRadians) return guessPitch;
		}
		return guessPitch;
	}
}
