package com.cokelord.skyblocksimplified.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Shared async JSON-over-HTTP GET helper for the Hypixel API — one HttpClient reused across every
 *  fetch instead of one per call, and every failure (network error, non-200, bad body) surfaces as an
 *  exceptional CompletableFuture instead of throwing on some other thread unnoticed. */
final class HttpJsonFetcher {
	// java.net.http.HttpClient does NOT follow redirects unless told to (its documented default policy
	// is NEVER) — the real bug behind UpdateApi's jar download silently failing: latest.json's
	// downloadUrl points at a GitHub Releases asset (github.com/.../releases/download/...), which always
	// 302s to a separate CDN domain (objects.githubusercontent.com) to actually serve the file. Without
	// this, fetchBytesAsync received the bare 302 itself (not 200), threw, and startUpdate()'s fail()
	// silently reset the button back to "Update" with no visible explanation — looked exactly like
	// "Downloading... for a split second then nothing happens". The JSON checks never hit this because
	// raw.githubusercontent.com serves latest.json directly with no redirect at all.
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	// Real gap found (per user report — a hard, long-lived 429 from raw.githubusercontent.com, with the
	// response page itself specifically citing GitHub's anti-scraping Terms of Service rather than a plain
	// rate-limit message): none of these requests ever set a User-Agent, so Java's HttpClient sends its own
	// default ("Java-http-client/<version>") — a well-known generic automated-client signature that's a
	// common trigger for being lumped in with scraper/bot traffic and treated more harshly than a legitimate,
	// identifiable client would be. A real, descriptive User-Agent is standard practice for hitting GitHub's
	// infrastructure (api.github.com outright requires one; raw.githubusercontent.com doesn't require it but
	// still profiles on it) and costs nothing to add. This can't force an already-active block to clear any
	// faster, but should reduce the odds of tripping the same stricter abuse-detection path again.
	private static final String USER_AGENT = "SkyblockSimplified-Mod (+https://github.com/cokelord42/sbs-allowlist)";

	private HttpJsonFetcher() {}

	static CompletableFuture<JsonObject> fetchAsync(String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", USER_AGENT)
			.GET()
			.build();
		return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(response -> {
				if (response.statusCode() != 200) {
					// Real bug found (per user report — Party Finder's hover-for-stats silently never
					// attaching): a non-200 response body from a JSON API almost always carries the real
					// reason (e.g. a proxy's own "Invalid API key" error) — the exception message used to
					// drop the body entirely, so every caller's own exceptionally() log line showed just a
					// bare status code with no way to tell an auth/config problem apart from a network blip
					// without re-running the request by hand outside the mod.
					String body = response.body();
					String snippet = body == null ? "" : body.substring(0, Math.min(200, body.length()));
					throw new RuntimeException("HTTP " + response.statusCode() + " fetching " + url + ": " + snippet);
				}
				return JsonParser.parseString(response.body()).getAsJsonObject();
			});
	}

	/** Same as fetchAsync, but for endpoints needing request headers (e.g. an Authorization bearer
	 *  token) and/or a bare JSON array response rather than an object — the GitHub Contents API returns
	 *  a top-level array when asked for raw file content, not an object with a "content" wrapper. */
	static CompletableFuture<JsonElement> fetchElementAsync(String url, Map<String, String> headers) {
		return fetchRawAsync(url, headers).thenApply(JsonParser::parseString);
	}

	/** Raw response body, for callers that need to parse it themselves. */
	static CompletableFuture<String> fetchRawAsync(String url, Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", USER_AGENT)
			.GET();
		for (Map.Entry<String, String> header : headers.entrySet()) {
			builder.header(header.getKey(), header.getValue());
		}
		return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
			.thenApply(response -> {
				if (response.statusCode() != 200) {
					throw new RuntimeException("HTTP " + response.statusCode() + " fetching " + url);
				}
				return response.body();
			});
	}

	/** Raw response bytes, for binary downloads (UpdateApi's jar fetch) — fetchRawAsync's
	 *  BodyHandlers.ofString() round-trips the body through a charset decode/encode that corrupts
	 *  arbitrary binary data; this uses ofByteArray() instead, no text handling involved at all. A much
	 *  longer timeout than the JSON fetches: a ~1MB jar over a slow connection can take a while, and
	 *  failing that early would make every update attempt on a slow line fail outright. */
	static CompletableFuture<byte[]> fetchBytesAsync(String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(120))
			.header("User-Agent", USER_AGENT)
			.GET()
			.build();
		return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
			.thenApply(response -> {
				if (response.statusCode() != 200) {
					throw new RuntimeException("HTTP " + response.statusCode() + " fetching " + url);
				}
				return response.body();
			});
	}
}
