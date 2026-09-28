package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.HypixelElectionApi;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diana tracker storage: per-event and lifetime counts (same keys as SBO's DianaTracker items/mobs), the
 * "since" counters behind Diana Stats, and b2b flags. Kept in its own file (not the feature config) since it
 * changes constantly while digging; writes are debounced to at most once every 5 seconds.
 *
 * <p>The event counts reset whenever the election API reports that Diana's Mythological Ritual is no longer
 * active (neither mayor nor minister), or a new election year's Diana term starts.
 */
public final class DianaData {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("skyblocksimplified").resolve("diana-tracker.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final Map<String, Integer> event = new LinkedHashMap<>();
	private static final Map<String, Integer> total = new LinkedHashMap<>();
	private static final Map<String, Integer> since = new LinkedHashMap<>();
	private static final Map<String, Boolean> flags = new LinkedHashMap<>();
	private static int eventYear = 0;
	private static boolean loaded = false;
	private static boolean dirty = false;
	private static long lastSaveAt = 0;
	private static long lastMayorCheck = 0;
	private static int version = 0;

	private DianaData() {}

	/** Bumped on every change; HUDs rebuild their cached lines only when this moves. */
	public static int version() { return version; }

	public static int event(String key) { return event.getOrDefault(key, 0); }
	public static int total(String key) { return total.getOrDefault(key, 0); }

	public static void add(String key, int amount) {
		if (amount == 0) return;
		event.merge(key, amount, Integer::sum);
		total.merge(key, amount, Integer::sum);
		changed();
	}

	public static int since(String key) { return since.getOrDefault(key, 0); }

	public static void setSince(String key, int value) {
		since.put(key, value);
		changed();
	}

	public static void addSince(String key, int amount) {
		since.merge(key, amount, Integer::sum);
		changed();
	}

	public static boolean flag(String key) { return flags.getOrDefault(key, false); }

	public static void setFlag(String key, boolean value) {
		flags.put(key, value);
		changed();
	}

	public static void resetEvent() {
		event.clear();
		changed();
	}

	public static void resetTotal() {
		total.clear();
		changed();
	}

	public static void resetSince() {
		since.clear();
		flags.clear();
		changed();
	}

	private static void changed() {
		dirty = true;
		version++;
	}

	// ---- lifecycle ----------------------------------------------------------------------------------------

	static void load() {
		if (loaded) return;
		loaded = true;
		if (!Files.exists(FILE)) return;
		try {
			JsonObject obj = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), JsonObject.class);
			if (obj == null) return;
			readInts(obj, "event", event);
			readInts(obj, "total", total);
			readInts(obj, "since", since);
			if (obj.has("flags") && obj.get("flags").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("flags").entrySet()) flags.put(e.getKey(), e.getValue().getAsBoolean());
			}
			if (obj.has("eventYear")) eventYear = obj.get("eventYear").getAsInt();
			version++;
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Failed to read Diana tracker data", e);
		}
	}

	private static void readInts(JsonObject obj, String key, Map<String, Integer> into) {
		if (!obj.has(key) || !obj.get(key).isJsonObject()) return;
		for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject(key).entrySet()) into.put(e.getKey(), e.getValue().getAsInt());
	}

	static void tick() {
		long now = System.currentTimeMillis();
		if (now - lastMayorCheck > 5000) {
			lastMayorCheck = now;
			checkMayor();
		}
		if (dirty && now - lastSaveAt > 5000) save();
	}

	private static void checkMayor() {
		HypixelElectionApi.MayorInfo mayor = HypixelElectionApi.currentMayor();
		if (mayor == null) return;
		if (!mayor.isDianaEventActive()) {
			if (!event.isEmpty() || eventYear != 0) {
				event.clear();
				eventYear = 0;
				changed();
			}
			return;
		}
		if (mayor.year() != 0 && eventYear != mayor.year()) {
			if (eventYear != 0) event.clear();
			eventYear = mayor.year();
			changed();
		}
	}

	public static void save() {
		dirty = false;
		lastSaveAt = System.currentTimeMillis();
		JsonObject obj = new JsonObject();
		obj.add("event", GSON.toJsonTree(event));
		obj.add("total", GSON.toJsonTree(total));
		obj.add("since", GSON.toJsonTree(since));
		obj.add("flags", GSON.toJsonTree(flags));
		obj.addProperty("eventYear", eventYear);
		String json = GSON.toJson(obj);
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SkyblockSimplified.LOGGER.error("Failed to save Diana tracker data", e);
		}
	}
}
