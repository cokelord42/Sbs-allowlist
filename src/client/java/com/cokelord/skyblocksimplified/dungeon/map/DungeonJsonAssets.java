package com.cokelord.skyblocksimplified.dungeon.map;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/** Loads the bundled dungeon room-database and puzzle-solution JSON assets under
 *  {@code assets/skyblocksimplified/dungeon/} (ported from Odin's equivalent {@code assets/odin/} data —
 *  these are plain scanned-world data tables, not code, carried over as-is). */
public final class DungeonJsonAssets {
	private static final Gson GSON = new Gson();

	private DungeonJsonAssets() {}

	public static <T> T load(String resourcePath, Type type, T defaultValue) {
		try (InputStream stream = DungeonJsonAssets.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				SkyblockSimplified.LOGGER.error("Dungeon data resource not found: {}", resourcePath);
				return defaultValue;
			}
			try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				T result = GSON.fromJson(reader, type);
				return result != null ? result : defaultValue;
			}
		} catch (IOException e) {
			SkyblockSimplified.LOGGER.error("Failed to load dungeon data resource: {}", resourcePath, e);
			return defaultValue;
		}
	}
}
