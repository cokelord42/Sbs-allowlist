package com.cokelord.skyblocksimplified.diana.pf;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Client for SBO's party finder backend (https://api.skyblockoverhaul.com) — the same REST endpoints and
 * JSON shapes SBO's SboApi.kt uses (it is plain HTTP, not a websocket). Authenticated calls carry the user's
 * own SBO key (from the SBO Discord) in the {@code x-sbo-key} header, exactly like SBO. The User-Agent
 * honestly identifies this mod rather than posing as SBO.
 *
 * <p>Responses are delivered on the client thread. Failures call {@code onError} with a readable message.
 */
public final class SboApi {
	private static final String API_URL = "https://api.skyblockoverhaul.com";
	private static final Gson GSON = new Gson();
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "skyblocksimplified-sbo-api");
		t.setDaemon(true);
		return t;
	});
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_2)
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.executor(EXECUTOR)
		.build();
	private static final String USER_AGENT = "SkyblockSimplified/" + FabricLoader.getInstance().getModContainer(SkyblockSimplified.MOD_ID)
		.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");

	private SboApi() {}

	private static HttpRequest.Builder base(String path) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API_URL + path))
			.timeout(Duration.ofSeconds(20))
			.header("User-Agent", USER_AGENT);
		String key = SboPartyFinder.key();
		if (!key.isBlank()) b.header("x-sbo-key", key);
		return b;
	}

	private static void send(HttpRequest request, Consumer<JsonObject> onSuccess, Consumer<String> onError) {
		CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((response, error) -> {
			String failure = null;
			JsonObject body = null;
			if (error != null) {
				failure = error.getClass().getSimpleName() + (error.getMessage() != null ? ": " + error.getMessage() : "");
			} else {
				try {
					JsonElement parsed = GSON.fromJson(response.body(), JsonElement.class);
					if (parsed != null && parsed.isJsonObject()) body = parsed.getAsJsonObject();
				} catch (Exception e) {
					// fall through to the status message below
				}
				if (body == null) failure = "HTTP " + response.statusCode() + (response.statusCode() == 429 ? " (rate limited)" : "");
			}
			final String f = failure;
			final JsonObject b = body;
			Minecraft.getInstance().execute(() -> {
				if (f != null) onError.accept(f);
				else onSuccess.accept(b);
			});
		});
	}

	private static void get(String path, Consumer<JsonObject> onSuccess, Consumer<String> onError) {
		send(base(path).GET().build(), onSuccess, onError);
	}

	private static void post(String path, JsonObject body, Consumer<JsonObject> onSuccess, Consumer<String> onError) {
		send(base(path).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8)).build(), onSuccess, onError);
	}

	private static String encode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	public static void listParties(String partyType, Consumer<JsonObject> ok, Consumer<String> err) {
		get("/listParties?partyType=" + encode(partyType), ok, err);
	}

	public static void activeUsers(Consumer<JsonObject> ok, Consumer<String> err) {
		get("/activeUsers", ok, err);
	}

	public static void playerInfo(String player, boolean readCache, Consumer<JsonObject> ok, Consumer<String> err) {
		get("/playerInfo?player=" + encode(player) + (readCache ? "" : "&readcache=false"), ok, err);
	}

	public static void partyInfoByUuids(List<String> uuids, Consumer<JsonObject> ok, Consumer<String> err) {
		JsonObject body = new JsonObject();
		JsonArray members = new JsonArray();
		uuids.forEach(members::add);
		body.add("members", members);
		post("/partyInfoByUuids", body, ok, err);
	}

	/** Body matches SBO's PartyRequest serialized with encodeDefaults=false (default-valued fields omitted). */
	public static JsonObject partyRequest(List<String> uuids, PartyModels.Reqs reqs, String partyType, String note, int partySize) {
		JsonObject body = new JsonObject();
		JsonArray arr = new JsonArray();
		uuids.forEach(arr::add);
		body.add("uuids", arr);
		body.add("reqs", reqs.toJson());
		if (!"Diana".equals(partyType)) body.addProperty("partyType", partyType);
		if (!note.isEmpty()) body.addProperty("note", note);
		if (partySize != 6) body.addProperty("partySize", partySize);
		return body;
	}

	public static void createParty(JsonObject request, Consumer<JsonObject> ok, Consumer<String> err) {
		post("/createParty", request, ok, err);
	}

	public static void updateQueuedParty(JsonObject request, Consumer<JsonObject> ok, Consumer<String> err) {
		post("/updateQueuedParty", request, ok, err);
	}

	public static void unqueueParty(Consumer<JsonObject> ok, Consumer<String> err) {
		post("/unqueueParty", new JsonObject(), ok, err);
	}

	public static void refreshParty(Consumer<JsonObject> ok, Consumer<String> err) {
		post("/refreshParty", new JsonObject(), ok, err);
	}

	static boolean bool(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsBoolean();
	}

	static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	static int integer(JsonObject o, String key, int def) {
		JsonElement e = o == null ? null : o.get(key);
		try {
			return e != null && e.isJsonPrimitive() ? e.getAsInt() : def;
		} catch (NumberFormatException ex) {
			return def;
		}
	}
}
