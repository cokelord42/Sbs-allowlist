package com.cokelord.skyblocksimplified.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Server tick clock for lag-accurate timers. Hypixel's time-sync packets (seen by {@link TpsMonitor}) carry
 * the server's game time, which advances exactly once per processed server tick. Between packets the tick is
 * interpolated (capped at one packet interval, scaled by the estimated TPS) and kept monotonic.
 *
 * <p>{@link #elapsedMillis(long)} turns "real ms since some moment" into "server ms since that moment" by
 * looking up what the server tick was at that moment (a 3-minute per-client-tick history) and counting the
 * ticks since. Unlike the old "scale the whole interval by the current TPS estimate" approach, lag is only
 * subtracted for the ticks the server actually missed, when it missed them.
 */
public final class ServerClock {
	private static final int CAPACITY = 20 * 180;
	private static final long[] sampleWall = new long[CAPACITY];
	private static final double[] sampleTick = new double[CAPACITY];
	private static int head = 0; // next write index
	private static int count = 0;
	private static double lastTick = Double.NaN;
	private static boolean registered = false;

	private ServerClock() {}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> sample());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void reset() {
		count = 0;
		head = 0;
		lastTick = Double.NaN;
	}

	/** Current server tick estimate, or NaN before the first time-sync packet. */
	public static double tickNow() {
		long gameTime = TpsMonitor.lastGameTime();
		long at = TpsMonitor.lastPacketAtMs();
		if (gameTime < 0 || at < 0) return Double.NaN;
		long since = Math.min(1000L, Math.max(0L, System.currentTimeMillis() - at));
		double tick = gameTime + since / 50.0 * (TpsMonitor.getEstimatedTps() / 20.0);
		if (!Double.isNaN(lastTick)) {
			// A late packet can land below an earlier extrapolation: hold instead of running backwards. A big
			// drop means a new world/server (game time restarted), so start over.
			if (tick < lastTick - 40) reset();
			else tick = Math.max(tick, lastTick);
		}
		lastTick = tick;
		return tick;
	}

	private static void sample() {
		double tick = tickNow();
		if (Double.isNaN(tick)) return;
		sampleWall[head] = System.currentTimeMillis();
		sampleTick[head] = tick;
		head = (head + 1) % CAPACITY;
		if (count < CAPACITY) count++;
	}

	/** Server tick at wall-clock time {@code wallMs}, or NaN if it's older than the history. */
	private static double tickAt(long wallMs) {
		if (count == 0) return Double.NaN;
		int newest = (head - 1 + CAPACITY) % CAPACITY;
		if (wallMs >= sampleWall[newest]) {
			double now = tickNow();
			return now - (System.currentTimeMillis() - wallMs) / 50.0 * (TpsMonitor.getEstimatedTps() / 20.0);
		}
		int oldest = (head - count + CAPACITY) % CAPACITY;
		if (wallMs < sampleWall[oldest]) return Double.NaN;
		// Binary search over the ring (logical index 0 = oldest).
		int lo = 0, hi = count - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (sampleWall[(oldest + mid) % CAPACITY] <= wallMs) lo = mid;
			else hi = mid - 1;
		}
		int a = (oldest + lo) % CAPACITY;
		int b = (oldest + Math.min(lo + 1, count - 1)) % CAPACITY;
		long wa = sampleWall[a], wb = sampleWall[b];
		if (wb <= wa) return sampleTick[a];
		double f = (wallMs - wa) / (double) (wb - wa);
		return sampleTick[a] + (sampleTick[b] - sampleTick[a]) * f;
	}

	/** Server-time milliseconds elapsed over the last {@code realElapsedMillis} of real time. Falls back to
	 *  TPS scaling when there's no tick data covering that window yet. */
	public static long elapsedMillis(long realElapsedMillis) {
		if (realElapsedMillis <= 0) return realElapsedMillis;
		double now = tickNow();
		double then = Double.isNaN(now) ? Double.NaN : tickAt(System.currentTimeMillis() - realElapsedMillis);
		if (Double.isNaN(then)) return Math.round(realElapsedMillis * (TpsMonitor.getEstimatedTps() / 20.0));
		return Math.max(0L, Math.round((now - then) * 50.0));
	}
}
