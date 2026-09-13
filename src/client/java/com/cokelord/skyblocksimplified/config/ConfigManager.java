package com.cokelord.skyblocksimplified.config;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Owns both on-disk persistence (load/save) and the clipboard export/import string format
 * (exportToClipboardString/importFromClipboardString) — the same {@link FeatureState} snapshot shape
 * backs both, via {@link #buildStatesSnapshot(boolean)}.
 *
 * <p><b>Compatibility contract — per explicit user requirement that config import/export must never break
 * across mod or Minecraft-version updates.</b> This is what actually makes that true, and what any future
 * change to this class or {@link FeatureState} must keep true:
 * <ul>
 *   <li>{@code EXPORT_PREFIX} is the tag every NEW export is written with. Per explicit user request
 *       ("The config system also starts with SBAR, change that to SBS but allow usage of SBAR for old
 *       configs"), this changed once from the mod's old pre-rename name ("SBAR1:", SkyblockAllRound) to
 *       its current one ("SBS1:", SkyblockSimplified) — {@link #LEGACY_EXPORT_PREFIXES} lists every prefix
 *       still accepted on import so old exports keep working. Do not repeat this churn casually: every
 *       prefix a real export was ever written with must stay in that list forever, and the ACTIVE prefix
 *       this class writes should otherwise stay fixed.</li>
 *   <li>{@link FeatureState}'s field names never change or get removed, only gain new ones. Gson silently
 *       ignores unknown fields on read and leaves missing fields at their Java default — that's what lets
 *       an old export load into a newer version (and a newer export's unknown extra fields get ignored by
 *       an older version) with no explicit migration code, but only holds as long as no existing field is
 *       ever renamed or repurposed.</li>
 *   <li>A {@code Feature}'s id string (the map key) is effectively permanent once shipped — changing it is
 *       equivalent to deleting the old feature and adding an unrelated new one from this format's point of
 *       view, silently dropping that feature's persisted state on import.</li>
 *   <li>Every {@code loadPersistedData}/{@code loadPersistedFloats}/{@code loadPersistedColor} implementation
 *       must check shape before casting (isJsonObject()/isJsonPrimitive()/instanceof, {@code obj.has(key)}
 *       before {@code obj.get(key)}) rather than assume the shape a current version would have written —
 *       an older or foreign export won't always match. This is on top of, not instead of, the per-feature
 *       try/catch isolation in load()/importFromClipboardString() below, which exists specifically so one
 *       feature's incompatible data can never block every other feature's.</li>
 * </ul>
 */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// Compact (no pretty-printing) specifically for exportToClipboardString() — every byte here ends up
	// gzipped+base64'd into what the user pastes/shares, so the indentation/spacing setPrettyPrinting()
	// adds for the on-disk file would be pure waste in the clipboard string.
	private static final Gson GSON_COMPACT = new Gson();
	private static final Type STATE_MAP_TYPE = new TypeToken<Map<String, FeatureState>>() {}.getType();
	// Identifies a pasted string as a real export from this format up front, so a corrupted or unrelated
	// paste is rejected immediately instead of failing deep inside base64/gzip/JSON decoding with a less
	// obvious error.
	private static final String EXPORT_PREFIX = "SBS1:";
	// Every prefix a real export was ever written with, newest first — checked in order on import so an
	// old "SBAR1:"-tagged export (from before the SkyblockAllRound -> SkyblockSimplified rename) still
	// works forever, even though new exports are always written with EXPORT_PREFIX above.
	private static final String[] LEGACY_EXPORT_PREFIXES = {EXPORT_PREFIX, "SBAR1:"};
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("skyblocksimplified.json");
	// The mod's old on-disk filename, from before the SkyblockAllRound -> SkyblockSimplified rename — read
	// as a one-time fallback in load() (see below) so upgrading players don't silently lose every setting
	// just because the config file's name changed along with the mod's. Never written to again; the first
	// save() after a migrated load() always writes CONFIG_PATH.
	private static final Path LEGACY_CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("skyblockallround.json");
	// save() used to do the actual file write synchronously on whatever thread called it — always the
	// render thread in practice, since every toggle/slider-drag-frame/checkbox calls it directly. That's
	// real disk I/O (directory check + open + JSON serialize + write) blocking a frame each time, which
	// is exactly the kind of thing that reads as "the game freezes for a split second" on the first
	// toggle. The JSON snapshot is still built synchronously (must reflect current values immediately);
	// only the disk write itself moves off-thread.
	private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-config-save");
		t.setDaemon(true);
		return t;
	});

	// Previously disabled in the dev environment to stop a shared run/config from round-tripping between
	// contributors. Single-developer project now, and settings need to persist during dev-client testing
	// same as a packaged jar, so persistence is always on.
	private static final boolean PERSISTENCE_ENABLED = true;

	private ConfigManager() {}

	/** Overrides each registered feature's enabled state from disk. Call after FeatureRegistry.applyDefaultStates(). */
	public static void load() {
		if (!PERSISTENCE_ENABLED) return;
		// Migration from the pre-rename filename: only when the new file has never been written yet — an
		// existing skyblocksimplified.json (even an empty/fresh one from a previous run under the new name)
		// always wins, so this can never clobber settings someone has already changed post-rename.
		Path pathToRead = Files.exists(CONFIG_PATH) ? CONFIG_PATH : (Files.exists(LEGACY_CONFIG_PATH) ? LEGACY_CONFIG_PATH : null);
		if (pathToRead == null) return;

		try (Reader reader = Files.newBufferedReader(pathToRead, StandardCharsets.UTF_8)) {
			Map<String, FeatureState> states = GSON.fromJson(reader, STATE_MAP_TYPE);
			if (states == null) return;
			for (Feature feature : FeatureRegistry.all()) {
				FeatureState state = states.get(feature.getId());
				if (state == null) continue;
				// Isolated per-feature, same reasoning as FeatureRegistry.tickAll's own per-feature
				// try-catch: this used to have no isolation at all, so one feature's persisted data being
				// malformed (e.g. an old on-disk shape from before a rework) threw straight out of the whole
				// loop, silently skipping loadPersistedData/Floats/Color for every OTHER feature registered
				// after it too — which reads exactly like "my settings don't persist" for whatever feature
				// happens to be later in the registration order than whichever one actually broke.
				try {
					feature.setEnabled(state.enabled);
					if (state.floats != null) feature.loadPersistedFloats(state.floats);
					if (state.color != null) feature.loadPersistedColor(state.color);
					if (state.extra != null) feature.loadPersistedData(state.extra);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Failed to load persisted state for {}, skipping it", feature.getId(), e);
				}
			}
		} catch (IOException e) {
			SkyblockSimplified.LOGGER.error("Failed to load config from {}", CONFIG_PATH, e);
		}
	}

	public static void save() {
		if (!PERSISTENCE_ENABLED) return;
		Map<String, FeatureState> states = buildStatesSnapshot(false);
		SAVE_EXECUTOR.execute(() -> writeToDisk(states));
	}

	/** Real bug found (per user report — "its not caching properly, when i restart it resets" — affecting
	 *  Equipment Display and Pet Display's captured-state persistence): {@link #save()} always writes to disk
	 *  on {@link #SAVE_EXECUTOR}, a DAEMON thread (see that field's own doc comment for why it's async at
	 *  all — avoiding a frame-time hitch on every toggle). A daemon thread is exactly the kind the JVM does
	 *  NOT wait for on exit — {@code ClientLifecycleEvents.CLIENT_STOPPING} fires, this codebase's own handler
	 *  calls {@code save()}, and the process can finish exiting before that queued write ever actually reaches
	 *  disk, silently losing whatever only just changed (e.g. a pet summoned or gear captured this session with
	 *  no other settings touched — nothing else would have triggered an earlier reactive save() call at all).
	 *  Used only at real shutdown, where blocking briefly for a real flush is the correct tradeoff (the game is
	 *  already closing, so there's no frame to protect) — everywhere else keeps using the async save() above. */
	public static void saveBlocking() {
		if (!PERSISTENCE_ENABLED) return;
		Map<String, FeatureState> states = buildStatesSnapshot(false);
		java.util.concurrent.Future<?> future = SAVE_EXECUTOR.submit(() -> writeToDisk(states));
		try {
			future.get(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Failed to flush config to disk on shutdown", e);
		}
	}

	/** Every registered feature's current persisted-state snapshot, isolated per-feature — shared by
	 *  save() and exportToClipboardString() so both go through the exact same try-catch isolation
	 *  (a single feature throwing while building ITS OWN snapshot must never blank out every other
	 *  feature's save/export, see save()'s original doc comment on why this used to happen).
	 *
	 *  <p>{@code forExport}: true for the clipboard string (uses {@link Feature#savePersistedDataForExport()},
	 *  which trims large local-only bookkeeping state a few features carry — see that method's own doc
	 *  comment), false for the real on-disk save (uses {@link Feature#savePersistedData()} unchanged, so
	 *  nothing about local persistence itself changes).
	 *
	 *  <p>Per user request ("config export... target under 2000 chars" — not fully achievable across this
	 *  many modules, but a real pass): a feature whose entire snapshot is exactly its shipped default
	 *  (enabled state matches {@link Feature#isEnabledByDefault()}, no floats/color/extra data at all) is
	 *  skipped outright rather than written as a wasted {@code "id":{"enabled":false}} entry. This is safe
	 *  precisely because it's already how a MISSING entry has always been treated on both load() and
	 *  importFromClipboardString() (see the class doc comment's compatibility contract) — omitting it here
	 *  changes nothing about what a round-trip actually restores, it just stops paying real export bytes
	 *  (gzip can shrink repetition, but it can't get back bytes for data that was never written at all) for
	 *  every one of this project's many features a given user never touched away from its default. */
	private static Map<String, FeatureState> buildStatesSnapshot(boolean forExport) {
		Map<String, FeatureState> states = new LinkedHashMap<>();
		for (Feature feature : FeatureRegistry.all()) {
			try {
				FeatureState state = new FeatureState();
				state.enabled = feature.isEnabled();
				Map<String, Float> floats = feature.getPersistedFloats();
				if (!floats.isEmpty()) state.floats = floats;
				state.color = feature.getPersistedColor();
				state.extra = forExport ? feature.savePersistedDataForExport() : feature.savePersistedData();
				if (isAllDefault(feature, state)) continue;
				states.put(feature.getId(), state);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Failed to snapshot persisted state for {}, skipping it this save", feature.getId(), e);
			}
		}
		return states;
	}

	private static boolean isAllDefault(Feature feature, FeatureState state) {
		return state.enabled == feature.isEnabledByDefault()
			&& state.floats == null
			&& state.color == null
			&& state.extra == null;
	}

	/** Every current setting as one clipboard-safe string — per user request, "as optimized as possible":
	 *  compact JSON (see GSON_COMPACT), then gzipped (settings JSON compresses well — lots of repeated
	 *  key names and small values), then base64'd since raw gzip bytes aren't safe to round-trip through
	 *  a text clipboard. Returns null on the (extremely unlikely, in-memory-only) chance the gzip stream
	 *  itself throws.
	 *
	 *  <p>Per user follow-up ("optimize the config export even more if possible... below 2000 [chars],
	 *  not required"): measured (see the scratch benchmark this comment is based on, run against a
	 *  synthetic blob sized like a real heavily-customized export) that forcing {@link
	 *  Deflater#BEST_COMPRESSION} instead of Java's default level saves a real ~3% off the final base64
	 *  string, for zero decoding-side risk — {@link GZIPInputStream} decodes identically regardless of
	 *  what compression level the encoder used, so this needed no new export-format prefix and can't break
	 *  any existing export. Beyond that, the honest finding: for a config this size (500+ features, many
	 *  with real per-item customization — Dungeon Routes rooms/waypoints, Boss Guide steps, Custom Loadout
	 *  Keybinds, moved HUD widget positions, etc.) getting under 2000 chars isn't realistically achievable
	 *  without either dropping real user data or renaming {@link FeatureState}'s wire-format field names —
	 *  the latter is explicitly the one thing this class's own compatibility contract (see the class doc
	 *  comment) says must never happen, since it would silently break every export ever produced. A mostly
	 *  default/untouched config already exports far smaller than 18000 chars on its own; the size scales
	 *  with how much the player has actually customized, which is exactly the data the export exists to
	 *  preserve. */
	public static String exportToClipboardString() {
		String json = GSON_COMPACT.toJson(buildStatesSnapshot(true), STATE_MAP_TYPE);
		try {
			ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
			try (GZIPOutputStream gzipOut = new GZIPOutputStream(byteOut) {
				{ def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION); }
			}) {
				gzipOut.write(json.getBytes(StandardCharsets.UTF_8));
			}
			return EXPORT_PREFIX + Base64.getEncoder().encodeToString(byteOut.toByteArray());
		} catch (IOException e) {
			SkyblockSimplified.LOGGER.error("Failed to export config", e);
			return null;
		}
	}

	// A real exported config (every feature's full state) is a few KB at most, even compact+gzipped+
	// base64'd. These caps are generous multiples of that, purely defensive: the import box accepts
	// arbitrary pasted text from anywhere (a friend's paste, a stale/corrupted clipboard, or a
	// deliberately hostile string), and gzip in particular can decompress a tiny input into an enormous
	// output ("zip bomb") — capped explicitly rather than trusting GZIPInputStream#transferTo, which has
	// no limit of its own and would otherwise let a malicious paste exhaust memory.
	private static final int MAX_IMPORT_STRING_LENGTH = 2_000_000;
	private static final int MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;

	/** Reverses exportToClipboardString() and applies every setting it contains — same per-feature
	 *  isolation as load()/save(), so one feature's malformed/foreign data can't block every other
	 *  feature's import. Persists the result to disk immediately (so it survives even if the game closes
	 *  without the player touching anything else first). Returns how many features were actually applied,
	 *  or -1 if the string isn't a valid export at all (wrong prefix, too large, or corrupted
	 *  base64/gzip/JSON) — the caller shows this as GUI/chat feedback.
	 *
	 * <p>See the class doc comment above buildStatesSnapshot()/FeatureState for the compatibility contract
	 * this relies on to keep working unmodified across mod and Minecraft versions: this method only ever
	 * looks up ids from the CURRENTLY REGISTERED FeatureRegistry.all() inside the parsed map, never the
	 * reverse — so an older export simply leaves newer features at their defaults (their id is absent from
	 * the map), and a newer export's extra ids are silently ignored by an older version (never iterated at
	 * all). The gzip/base64/Map&lt;String,FeatureState&gt; wire shape has never changed since this was
	 * introduced; only the prefix tag itself has (see LEGACY_EXPORT_PREFIXES's own doc comment) — every
	 * prefix ever shipped is still checked here, so nothing built with any of them stops importing. */
	public static int importFromClipboardString(String raw) {
		if (raw == null) return -1;
		String trimmed = raw.strip();
		if (trimmed.length() > MAX_IMPORT_STRING_LENGTH) {
			SkyblockSimplified.LOGGER.warn("Rejected an imported config string: {} chars exceeds the {}-char cap", trimmed.length(), MAX_IMPORT_STRING_LENGTH);
			return -1;
		}
		String matchedPrefix = null;
		for (String prefix : LEGACY_EXPORT_PREFIXES) {
			if (trimmed.startsWith(prefix)) {
				matchedPrefix = prefix;
				break;
			}
		}
		if (matchedPrefix == null) return -1;

		Map<String, FeatureState> states;
		try {
			byte[] compressed = Base64.getDecoder().decode(trimmed.substring(matchedPrefix.length()));
			byte[] decompressed = readBounded(compressed, MAX_DECOMPRESSED_BYTES);
			states = GSON_COMPACT.fromJson(new String(decompressed, StandardCharsets.UTF_8), STATE_MAP_TYPE);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.warn("Failed to decode an imported config string", e);
			return -1;
		}
		if (states == null) return -1;

		int applied = 0;
		for (Feature feature : FeatureRegistry.all()) {
			FeatureState state = states.get(feature.getId());
			if (state == null) continue;
			try {
				feature.setEnabled(state.enabled);
				if (state.floats != null) feature.loadPersistedFloats(state.floats);
				if (state.color != null) feature.loadPersistedColor(state.color);
				if (state.extra != null) feature.loadPersistedData(state.extra);
				applied++;
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Failed to apply imported state for {}, skipping it", feature.getId(), e);
			}
		}
		save();
		return applied;
	}

	/** Decompresses gzipped bytes, throwing IOException instead of continuing the moment the output would
	 *  exceed maxBytes — the zip-bomb guard MAX_DECOMPRESSED_BYTES relies on; a plain transferTo() has no
	 *  such limit and would keep inflating (and allocating) for as long as the compressed stream claims to
	 *  have data. */
	private static byte[] readBounded(byte[] gzipped, int maxBytes) throws IOException {
		try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
			ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int total = 0;
			int read;
			while ((read = gzipIn.read(buffer)) != -1) {
				total += read;
				if (total > maxBytes) throw new IOException("Decompressed config exceeds the " + maxBytes + "-byte cap");
				byteOut.write(buffer, 0, read);
			}
			return byteOut.toByteArray();
		}
	}

	private static void writeToDisk(Map<String, FeatureState> states) {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(states, STATE_MAP_TYPE, writer);
			}
		} catch (IOException e) {
			SkyblockSimplified.LOGGER.error("Failed to save config to {}", CONFIG_PATH, e);
		}
	}
}
