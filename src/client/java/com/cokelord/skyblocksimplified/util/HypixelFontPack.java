package com.cokelord.skyblocksimplified.util;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps Hypixel's icon font working while Remove Skyblock Texture Pack blocks the rest of their pack.
 *
 * <p>Hypixel now writes every stat/gem/mob-type icon (❤ Health, ☘ Fortune, Fragged ⚚, ...) as private-use
 * characters that only their pack's {@code assets/minecraft/font/default.json} can draw (bitmap providers
 * over a few small sheets: gui/stats.png, skills.png, icons.png, mobs.png, icons/staff.png). Blocking the pack
 * made all of them blank. On the first blocked push this downloads the pack once in the background, keeps
 * ONLY the font JSON(s) and the textures their bitmap providers reference (~30KB of the ~17MB pack), and
 * serves that as a tiny always-on resource pack (registered by HypixelFontPackSourceMixin, ordered right above
 * vanilla by HypixelFontPackOrderMixin — the same placement Detexturify uses for its cached pack). Cached on
 * disk by pack URL: later launches load it immediately with no download; a new Hypixel pack version re-fetches.
 */
public final class HypixelFontPack {
	public static final String PACK_ID = "skyblocksimplified/hypixel_font";
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("skyblocksimplified").resolve("hypixel-font");
	private static final Path SOURCE_FILE = DIR.resolve("source-url.txt");
	private static final AtomicBoolean downloading = new AtomicBoolean(false);

	private HypixelFontPack() {}

	public static boolean isAvailable() {
		return Files.exists(DIR.resolve("pack.mcmeta"));
	}

	/** The pack to add during resource-pack discovery, or null when there's nothing cached / not wanted. */
	public static Pack pack() {
		if (!isAvailable() || !com.cokelord.skyblocksimplified.feature.impl.RemoveSkyblockTexturePackFeature.isFontPackWanted()) return null;
		try {
			PackLocationInfo info = new PackLocationInfo(PACK_ID, Component.literal("SkyBlock icon font (SkyblockSimplified)"), PackSource.BUILT_IN, Optional.empty());
			return Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(DIR), PackType.CLIENT_RESOURCES,
				new PackSelectionConfig(true, Pack.Position.TOP, false));
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.warn("Hypixel icon font pack failed to load", e);
			return null;
		}
	}

	/** Called when a SkyBlock pack push was blocked: makes sure the icon font from that exact pack is cached. */
	public static void ensure(String packUrl) {
		if (packUrl == null || packUrl.isEmpty()) return;
		try {
			if (isAvailable() && Files.exists(SOURCE_FILE) && packUrl.equals(Files.readString(SOURCE_FILE).trim())) return;
		} catch (Exception ignored) {}
		if (!downloading.compareAndSet(false, true)) return;
		Thread thread = new Thread(() -> {
			Path zip = null;
			try {
				Files.createDirectories(DIR.getParent());
				zip = Files.createTempFile(DIR.getParent(), "hypixel-pack", ".zip");
				HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
				HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(packUrl)).timeout(Duration.ofMinutes(3)).GET().build(),
					HttpResponse.BodyHandlers.ofFile(zip));
				if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
				extract(zip);
				Files.writeString(SOURCE_FILE, packUrl, StandardCharsets.UTF_8);
				SkyblockSimplified.LOGGER.info("Cached Hypixel's icon font from {}", packUrl);
				Minecraft.getInstance().execute(() -> Minecraft.getInstance().reloadResourcePacks());
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.warn("Failed to cache Hypixel's icon font from {}", packUrl, e);
			} finally {
				if (zip != null) try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
				downloading.set(false);
			}
		}, "skyblocksimplified-hypixel-font");
		thread.setDaemon(true);
		thread.start();
	}

	/** Writes pack.mcmeta, every assets/NS/font/*.json, and each texture a bitmap provider references. */
	private static void extract(Path zipPath) throws Exception {
		Path staging = DIR.resolveSibling("hypixel-font.staging");
		deleteTree(staging);
		Files.createDirectories(staging);
		try (ZipFile zip = new ZipFile(zipPath.toFile())) {
			copy(zip, "pack.mcmeta", staging);
			Set<String> textures = new HashSet<>();
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || !name.matches("assets/[^/]+/font/.+\\.json")) continue;
				copy(zip, name, staging);
				try (InputStream in = zip.getInputStream(entry)) {
					JsonObject font = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
					for (JsonElement provider : font.getAsJsonArray("providers")) {
						if (!(provider instanceof JsonObject p) || !p.has("file")) continue;
						String file = p.get("file").getAsString();
						String ns = file.contains(":") ? file.substring(0, file.indexOf(':')) : "minecraft";
						String path = file.contains(":") ? file.substring(file.indexOf(':') + 1) : file;
						textures.add("assets/" + ns + "/textures/" + path);
					}
				}
			}
			for (String texture : textures) copy(zip, texture, staging);
		}
		deleteTree(DIR);
		Files.move(staging, DIR, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void copy(ZipFile zip, String name, Path root) throws Exception {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null) return;
		Path out = root.resolve(name).normalize();
		if (!out.startsWith(root)) return; // zip-slip guard
		Files.createDirectories(out.getParent());
		try (InputStream in = zip.getInputStream(entry)) {
			Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteTree(Path path) throws Exception {
		if (!Files.exists(path)) return;
		try (var walk = Files.walk(path)) {
			for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}
}
