package com.cokelord.skyblocksimplified.util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;

/**
 * Real round-trip ping, independent of the tab-list latency ({@code PlayerInfo.getLatency()}) that
 * {@code NetworkDisplayFeature} used to read directly. Per user report ("network display is bugged, the
 * ping shows 1ms"): on Hypixel's BungeeCord proxy the tab-list latency reflects (or at least can reflect)
 * the client's connection to the proxy edge rather than the real round trip to the backend Skyblock
 * server, so it can read misleadingly low. Vanilla's own F3 network chart avoids this by doing a real
 * client-initiated ping/pong round trip ({@code ServerboundPingRequestPacket}/{@code
 * ClientboundPongResponsePacket}, see {@code PingDebugMonitor}) — but that vanilla monitor only ticks
 * while the F3 network chart is actually open ({@code DebugScreenOverlay.showNetworkCharts()}), so its
 * data is empty the rest of the time. This does the same real ping/pong round trip ourselves, once a
 * second, all the time — fed by {@code PongResponseMixin}.
 */
public final class RealPingMonitor {
	private RealPingMonitor() {}

	private static final long PING_INTERVAL_MS = 1000;

	private static long lastSentAtMs = -1;
	private static long lastPingMs = -1;
	private static boolean registered = false;

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(RealPingMonitor::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void tick(Minecraft mc) {
		if (mc.getConnection() == null) return;
		long now = System.currentTimeMillis();
		if (lastSentAtMs >= 0 && now - lastSentAtMs < PING_INTERVAL_MS) return;
		lastSentAtMs = now;
		mc.getConnection().send(new ServerboundPingRequestPacket(now));
	}

	/** Called by {@code PongResponseMixin} the instant the server echoes a ping request back. */
	public static void onPongReceived(long echoedSendTimeMs) {
		long rtt = System.currentTimeMillis() - echoedSendTimeMs;
		if (rtt >= 0) lastPingMs = rtt;
	}

	/** Latest real round-trip ping in milliseconds, or -1 if no round trip has completed yet (e.g. the
	 *  first second after joining). */
	public static long getPing() {
		return lastPingMs;
	}

	private static void reset() {
		lastSentAtMs = -1;
		lastPingMs = -1;
	}
}
