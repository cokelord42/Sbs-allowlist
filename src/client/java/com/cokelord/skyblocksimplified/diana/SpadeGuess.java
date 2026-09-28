package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.SpadeGuessFeature;
import com.cokelord.skyblocksimplified.particle.ParticlePacketEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Spade guess: the spade ability shoots a curved trail of dripping-lava particles toward the burrow. Fitting a
 * cubic through the trail and extrapolating by the control-point distance of the underlying Bezier gives the
 * burrow location. Ported from SBO's PreciseGuessBurrow.kt (same particle filter, 3s window, 4-point minimum
 * and pitch/extrapolation math).
 */
public final class SpadeGuess {
	private static final List<Vec3> particles = new ArrayList<>();
	private static long lastLavaParticleNs = 0;
	private static long lastSpadeUseNs = 0;
	private static DianaWaypoints.Waypoint currentGuess;

	private SpadeGuess() {}

	public static void reset() {
		particles.clear();
		currentGuess = null;
	}

	/** @return true if this spade use should be swallowed (a trail is still flying). */
	static boolean onSpadeUse() {
		long now = System.nanoTime();
		if (SpadeGuessFeature.blockSpadeDuringTrail() && now - lastLavaParticleNs < 200_000_000L) return true;
		particles.clear();
		currentGuess = null;
		lastSpadeUseNs = now;
		return false;
	}

	static void onParticle(ParticlePacketEvent event) {
		if (!SpadeGuessFeature.on() || !DianaState.inHub()) return;
		if (event.type().getType() != ParticleTypes.DRIPPING_LAVA || event.count() != 2 || event.maxSpeed() != -0.5f) return;
		long now = System.nanoTime();
		lastLavaParticleNs = now;
		if (now - lastSpadeUseNs > 3_000_000_000L) return;
		Vec3 location = event.location();
		if (particles.isEmpty()) {
			particles.add(location);
			return;
		}
		double toLast = particles.get(particles.size() - 1).distanceTo(location);
		if (toLast > 3 || toLast == 0) return;
		particles.add(location);

		Vec3 guess = guessLocation();
		if (guess == null) return;
		BlockPos pos = DianaMath.floor(guess.subtract(0, 0.5, 0));
		if (DianaWaypoints.getAt(pos, DianaWaypoints.Type.BURROW) != null) return;
		// One live guess per spade use, refined as more of the trail arrives.
		if (currentGuess != null) DianaWaypoints.remove(currentGuess);
		currentGuess = DianaWaypoints.add("Spade Guess", pos, DianaWaypoints.Type.GUESS, 1800);
	}

	private static Vec3 guessLocation() {
		int n = particles.size();
		if (n < 4) return null;
		double[] t = new double[n], xs = new double[n], ys = new double[n], zs = new double[n];
		for (int i = 0; i < n; i++) {
			Vec3 p = particles.get(i);
			t[i] = i;
			xs[i] = p.x;
			ys[i] = p.y;
			zs[i] = p.z;
		}
		double[] cx = DianaMath.fitPolynomial(t, xs, 3);
		double[] cy = DianaMath.fitPolynomial(t, ys, 3);
		double[] cz = DianaMath.fitPolynomial(t, zs, 3);
		if (cx == null || cy == null || cz == null) return null;
		Vec3 derivative = new Vec3(DianaMath.evaluateDerivative(cx, 0), DianaMath.evaluateDerivative(cy, 0), DianaMath.evaluateDerivative(cz, 0));
		double length = derivative.length();
		if (length < 1e-9) return null;
		double pitch = pitchFromDerivative(derivative);
		double controlPointDistance = Math.sqrt(24 * Math.sin(pitch - Math.PI) + 25);
		double at = 3 * controlPointDistance / length;
		return new Vec3(DianaMath.evaluate(cx, at), DianaMath.evaluate(cy, at), DianaMath.evaluate(cz, at));
	}

	/** Binary search for the launch pitch whose trajectory's initial slope matches the fitted one. */
	private static double pitchFromDerivative(Vec3 d) {
		double xz = Math.sqrt(d.x * d.x + d.z * d.z);
		double target = -Math.atan2(d.y, xz);
		double guess = target, min = -Math.PI / 2, max = Math.PI / 2;
		for (int i = 0; i < 100; i++) {
			double result = Math.atan2(Math.sin(guess) - 0.75, Math.cos(guess));
			if (result == target) return guess;
			if (result < target) min = guess;
			else max = guess;
			guess = (min + max) / 2;
		}
		return guess;
	}
}
