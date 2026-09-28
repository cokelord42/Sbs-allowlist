package com.cokelord.skyblocksimplified.api;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SkyBlock item id -> the vanilla model (and head skin) that item used before Hypixel's forced resource
 * pack, for Remove Skyblock Texture Pack. Same data source Detexturify uses ({@code data.starred.foo},
 * credited there to Noamm9's item list): {@code {"ITEM_ID": {"model": "minecraft:x", "texture": "<base64>"}}}.
 * Fetched once, only when that module is actually used — it's ~2.5MB.
 */
public final class SkyblockTextureMapApi {
	private static final String URL = "https://data.starred.foo/items.json";
	private static final AtomicBoolean loading = new AtomicBoolean(false);
	private static volatile Map<String, Entry> map = null;

	private SkyblockTextureMapApi() {}

	public static final class Entry {
		public final Identifier model;
		private final String texture;
		private ResolvableProfile profile;

		Entry(Identifier model, String texture) {
			this.model = model;
			this.texture = texture;
		}

		/** Null when the item isn't a textured head. Built on first use (render thread). */
		public ResolvableProfile profile() {
			if (texture == null) return null;
			if (profile == null) {
				com.google.common.collect.Multimap<String, Property> raw = com.google.common.collect.HashMultimap.create();
				raw.put("textures", new Property("textures", texture));
				UUID id = UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				profile = ResolvableProfile.createResolved(new GameProfile(id, "SbsTexture", new PropertyMap(raw)));
			}
			return profile;
		}
	}

	/** Null until loaded. */
	public static Map<String, Entry> get() {
		return map;
	}

	public static void ensureLoaded() {
		if (map != null || !loading.compareAndSet(false, true)) return;
		HttpJsonFetcher.fetchAsync(URL).thenAccept(json -> {
			Map<String, Entry> next = new HashMap<>(json.size() * 2);
			for (Map.Entry<String, JsonElement> e : json.entrySet()) {
				if (!(e.getValue() instanceof JsonObject obj) || !obj.has("model")) continue;
				Identifier model = normalize(obj.get("model").getAsString());
				// Some entries still use 1.8 item names; drop anything that isn't a real item now so the
				// caller falls back to the item's own vanilla model instead of the missing-model cube.
				if (model == null || !BuiltInRegistries.ITEM.containsKey(model)) continue;
				String texture = obj.has("texture") && !obj.get("texture").isJsonNull() ? obj.get("texture").getAsString() : null;
				next.put(e.getKey(), new Entry(model, texture));
			}
			map = next;
			SkyblockSimplified.LOGGER.info("Loaded {} SkyBlock item texture mappings", next.size());
		}).whenComplete((ignored, error) -> {
			if (error != null) SkyblockSimplified.LOGGER.error("Failed to load SkyBlock item texture mappings", error);
			loading.set(false);
		});
	}

	private static Identifier normalize(String raw) {
		Identifier id = Identifier.tryParse(raw);
		if (id == null || !id.getNamespace().equals("minecraft")) return id;
		return switch (id.getPath()) {
			case "skull" -> Identifier.withDefaultNamespace("player_head");
			case "banner" -> Identifier.withDefaultNamespace("white_banner");
			default -> id;
		};
	}
}
