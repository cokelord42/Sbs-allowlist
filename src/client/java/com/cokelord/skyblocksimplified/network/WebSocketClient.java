package com.cokelord.skyblocksimplified.network;

import com.cokelord.skyblocksimplified.SkyblockSimplified;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Real, 1:1 port of Odin's own {@code WebSocketConnection.kt} (user-supplied Odin 0.3.2 source) — a thin
 * wrapper over the JDK's built-in {@code java.net.http.WebSocket} (no extra dependency needed, same as
 * Odin's own choice). Odin's author explicitly gave permission to link into their real relay server
 * ({@code wss://ws.odtheking.com}) as long as this doesn't rate-limit it or otherwise misbehave — this
 * class only ever holds a single connection per feature and never reconnects on a timer, matching Odin's
 * own connect-on-real-event / disconnect-on-real-event lifecycle instead of polling or retrying.
 */
public class WebSocketClient {
	private final AtomicReference<WebSocket> webSocket = new AtomicReference<>(null);
	private final HttpClient httpClient = HttpClient.newHttpClient();
	private Consumer<String> onMessage = message -> {};

	public void onMessage(Consumer<String> listener) { onMessage = listener; }

	public boolean isConnected() { return webSocket.get() != null; }

	public boolean send(String message) {
		WebSocket ws = webSocket.get();
		if (ws == null) return false;
		ws.sendText(message, true);
		return true;
	}

	public void connect(String url) {
		WebSocket previous = webSocket.getAndSet(null);
		if (previous != null) previous.sendClose(1000, "Reconnecting");

		WebSocket.Listener listener = new WebSocket.Listener() {
			private final StringBuilder messageBuilder = new StringBuilder();

			@Override
			public void onOpen(WebSocket ws) {
				webSocket.set(ws);
				ws.request(1);
			}

			@Override
			public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
				messageBuilder.append(data);
				if (last) {
					String message = messageBuilder.toString();
					messageBuilder.setLength(0);
					onMessage.accept(message);
				}
				ws.request(1);
				return null;
			}

			@Override
			public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
				onMessage.accept(StandardCharsets.UTF_8.decode(data).toString());
				ws.request(1);
				return null;
			}

			@Override
			public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
				webSocket.compareAndSet(ws, null);
				return null;
			}

			@Override
			public void onError(WebSocket ws, Throwable error) {
				SkyblockSimplified.LOGGER.warn("Melody WebSocket error: {}", error.getMessage());
				webSocket.compareAndSet(ws, null);
			}
		};

		httpClient.newWebSocketBuilder()
			.buildAsync(URI.create(url), listener)
			.exceptionally(error -> {
				SkyblockSimplified.LOGGER.warn("Melody WebSocket failed to connect: {}", error.getMessage());
				return null;
			});
	}

	public void shutdown() {
		WebSocket ws = webSocket.getAndSet(null);
		if (ws != null) ws.sendClose(1000, "Client shutdown");
	}
}
