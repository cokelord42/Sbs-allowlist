package com.cokelord.skyblocksimplified.util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Lightweight client-side server-TPS estimator — per user request ("all custom menus need to be able to
 * detect tps drops... to make sure detecting goes smoothly"). Real per-server TPS is never sent to the
 * client directly, but Hypixel's server sends a {@code ClientboundSetTimePacket} once every real server
 * tick (its {@code gameTime} field increments by 1 per tick) — when the server is lagging, those ticks
 * (and therefore these packets) slow down too, so measuring real wall-clock time between consecutive
 * packets against the expected 50ms/tick gives a reasonable estimate without needing a scoreboard/chat
 * "/tps"-style readout (which, per {@code ChatCommandsFeature}'s own doc comment, has no confirmed source
 * in this codebase). Fed by {@code SetTimePacketMixin}.
 */
public final class TpsMonitor {
	private TpsMonitor() {}

	private static long lastPacketAtMs = -1;
	private static long lastGameTime = -1;
	private static double estimatedTps = 20.0;
	private static boolean registered = false;

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	public static void onSetTime(long gameTime) {
		long now = System.currentTimeMillis();
		if (lastPacketAtMs >= 0 && lastGameTime >= 0) {
			long tickDelta = gameTime - lastGameTime;
			long msDelta = now - lastPacketAtMs;
			// Ignore anything that isn't a normal forward tick step (world reload, a huge time-skip command,
			// clock desync) — those would produce a nonsense instantaneous TPS reading rather than a real one.
			if (tickDelta > 0 && tickDelta < 200 && msDelta > 0) {
				double instantTps = Math.min(20.0, (tickDelta * 1000.0) / msDelta);
				// Exponential moving average so one delayed/bundled packet doesn't swing the estimate wildly —
				// "detect drops" needs a stable trend, not single-sample noise.
				estimatedTps = estimatedTps * 0.7 + instantTps * 0.3;
			}
		}
		lastPacketAtMs = now;
		lastGameTime = gameTime;
	}

	/** Best-effort estimated server TPS (capped at 20, the vanilla/Hypixel target). */
	public static double getEstimatedTps() {
		return estimatedTps;
	}

	/** True once the estimate has dropped meaningfully below 20 — a threshold, not an exact "is the server
	 *  currently unplayable" claim, since this is inherently an approximation. Consumers (Terminal Solver,
	 *  Simon Says, etc.) use this to widen their own timing-sensitive grace windows, not to block anything
	 *  outright — during real lag, server-side item/state updates for a click also arrive later, which is
	 *  exactly when those grace windows matter most. */
	public static boolean isLagging() {
		return estimatedTps < 15.0;
	}

	private static void reset() {
		lastPacketAtMs = -1;
		lastGameTime = -1;
		estimatedTps = 20.0;
	}
}
