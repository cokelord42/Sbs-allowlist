package com.cokelord.skyblocksimplified.util.bezier;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Accumulates a moving window of 3D points and fits a degree-N Bezier-like curve through them —
 *  port of SkyHanni's BezierFitter.kt. */
public class BezierFitter {
	private final int degree;
	private final List<Vec3> points = new ArrayList<>();
	private final PolynomialFitter[] fitters;
	private BezierCurve lastCurve;

	public BezierFitter(int degree) {
		this.degree = degree;
		fitters = new PolynomialFitter[]{new PolynomialFitter(degree), new PolynomialFitter(degree), new PolynomialFitter(degree)};
	}

	public void addPoint(Vec3 point) {
		double t = points.size();
		fitters[0].addPoint(t, point.x);
		fitters[1].addPoint(t, point.y);
		fitters[2].addPoint(t, point.z);
		points.add(point);
		lastCurve = null;
	}

	public Vec3 getLastPoint() {
		return points.isEmpty() ? null : points.get(points.size() - 1);
	}

	public boolean isEmpty() {
		return points.isEmpty();
	}

	public int count() {
		return points.size();
	}

	/** A degree-N curve needs N+1 unique points to be solvable. */
	public BezierCurve fit() {
		if (points.size() <= degree) return null;
		if (lastCurve != null) return lastCurve;
		double[] cx = fitters[0].fit();
		double[] cy = fitters[1].fit();
		double[] cz = fitters[2].fit();
		lastCurve = new BezierCurve(cx, cy, cz);
		return lastCurve;
	}

	public void reset() {
		points.clear();
		for (PolynomialFitter f : fitters) f.reset();
		lastCurve = null;
	}

	/** Adds the point if it's a plausible continuation of the path (not too far from the last point,
	 *  not a duplicate), otherwise leaves the fitter untouched. Returns true if the point was accepted. */
	public boolean tryAdd(Vec3 location, double maxDistanceToLast, Predicate<Vec3> emptyCondition) {
		if (isEmpty()) {
			if (emptyCondition.test(location)) return false;
			addPoint(location);
			return false;
		}
		Vec3 last = getLastPoint();
		double distToLast = last.distanceTo(location);
		if (distToLast == 0.0) return false;
		if (distToLast > maxDistanceToLast) {
			// Too far from the old trail's last point to be a continuation — but discarding it outright
			// (the old behavior) left the fitter frozen on that stale last point forever, since a rejected
			// point never becomes the new "last" to compare against. A jump this size within the same
			// still-open tracking window almost always means a second, different pest got locked onto
			// before the idle-timeout reset ever fired, so every point of that new trail kept failing the
			// exact same distance check — "only guesses once per session" was this. Starting fresh here
			// instead lets a new trail begin immediately rather than needing an explicit reset first.
			reset();
			if (emptyCondition.test(location)) return false;
			addPoint(location);
			return false;
		}
		addPoint(location);
		return true;
	}
}
