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

	/** Best-effort estimated server TPS (capped at 20, the vanilla/Hypixel target).
	 *
	 *  <p>Real regression found and reverted (per user report — the TPS counter "keeps cycling from 20 to 1
	 *  in a second then goes back up to 20 instant and recycles", and Blood Camp "shows no timer or final box
	 *  anymore"): a previous round tried bounding this by how long it had been since the last time packet
	 *  (treating any gap over 100ms as an active freeze), on the assumption — stated in this class's own doc
	 *  comment and {@code SetTimePacketMixin}'s — that the server sends this packet once per real tick
	 *  (~50ms). That assumption is evidently wrong in practice (Hypixel's real send cadence for this packet is
	 *  clearly much sparser/burstier than a steady 50ms), so that "staleness" check fired constantly under
	 *  completely normal, non-laggy play, not just during real freezes — cycling the estimate down to near
	 *  zero every time a normal gap between packets happened to exceed 100ms, then back up the instant the
	 *  next one arrived. Downstream, Blood Camp's {@code lagAdjustedTickMillis()} multiplying by that
	 *  near-zero estimate froze {@code currentTickTime} in place most of the time, which made
	 *  {@code speedVectors} (position delta / near-1ms {@code timeTook}) blow up to nonsense magnitudes and
	 *  silently break the whole render pass. Reverted to the plain EMA below — no staleness bound — since a
	 *  confirmed-broken feature is worse than the lag-detection gap this was trying to close. The Blood Camp
	 *  countdown-vs-lag report from earlier this round is still open; it needs a fix that doesn't depend on
	 *  guessing this packet's real send interval. */
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
