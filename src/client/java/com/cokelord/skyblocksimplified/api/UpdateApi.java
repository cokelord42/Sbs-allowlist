package com.cokelord.skyblocksimplified.api;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-game updater, checked every {@link #POLL_SECONDS} for the whole session, per user request — this
 * never stops once it finds something, since a newer version could get published at any point during a
 * long session and there's no "found it, done" terminal state to stop at. Finding an update only ever changes what {@link #getState}
 * reports and shows the button; it never takes effect until the player actually clicks Update AND restarts
 * the game — there's no way to hot-swap a mod's own jar mid-session (Fabric doesn't support
 * reloading mod code), and the file itself is open/locked by this exact running JVM for as long as it's
 * alive — on Windows in particular, you generally can't delete or overwrite a jar a live process still has
 * open. Trying to do the swap from inside this process (even from a shutdown hook) races the OS's own
 * cleanup of that lock and risks leaving both the old and new jar sitting in {@code mods/} at once, which
 * Fabric Loader treats as a duplicate mod and refuses to launch — a much worse outcome than the update
 * simply not applying.
 *
 * <p>Instead: {@link #startUpdate} downloads and verifies the new jar into a staging folder (never
 * directly into {@code mods/}, so Loader never sees a partial/duplicate file), then extracts and launches
 * a small, fully standalone (no Fabric/Minecraft/mod classes, only {@code java.base}) helper — see
 * {@code tools/UpdaterMain.java} for its source — as a detached process. That helper waits on this game's
 * exact process ID via {@link ProcessHandle#onExit()}, which only completes once this JVM has fully
 * terminated and the OS has released its file lock, and only then deletes the old jar and moves the staged
 * one into place. The game closes itself via {@link Minecraft#stop()} (the same path the vanilla quit
 * button uses, so any in-progress world save still happens normally) right after the helper is launched,
 * since the update can't take effect until next launch regardless of whether the player leaves manually.
 *
 * <p><b>Shipped as pre-compiled bytecode, not source launched via {@code java Foo.java}.</b> An earlier
 * version used the JDK's single-file source launcher, which needs the {@code jdk.compiler} module present
 * in whatever JVM is running the game — a real bug found on MultiMC, which (like most Minecraft-oriented
 * launchers) commonly manages a stripped JRE-only runtime with no compiler at all, since compiling isn't
 * needed to just play the game. The helper's class file is embedded as a resource (deliberately not named
 * with a {@code .class} extension — see {@link #applyDownloadedJar}'s own note for why) and launched via
 * plain {@code java -cp <dir> UpdaterMain}, which only needs a bare JRE — guaranteed present, since it's
 * what's already running Minecraft itself. The java executable's own path is derived from
 * {@code java.home} (always set, points at exactly the JRE/JDK currently running) rather than trusting
 * {@link ProcessHandle#info()} to resolve it (not guaranteed to succeed on every platform) with a bare
 * {@code "java"} fallback (only works if java happens to be on the system PATH, not a safe assumption for
 * a launcher that manages its own Java without touching PATH at all — MultiMC is the one this was actually
 * caught on, but nothing about the fix is MultiMC-specific: Prism Launcher is a MultiMC fork with the same
 * bundled-runtime philosophy, and Modrinth App manages its own per-instance Java the same general way, so
 * the same failure mode plausibly applied there too. {@link #javaExecutablePath} is a 3-tier fallback for
 * exactly this reason — this project has no way to enumerate or test against every launcher's Java setup
 * directly, so no single resolution strategy is trusted alone.
 *
 * <p>{@link #consumePendingNotification} backs the once-per-session bottom-right toast (see
 * {@code UpdateToastRenderer}) that fires the moment an update is first found — this class only owns the
 * one-shot latch, not any rendering/sound, to keep this a pure network/state class.
 *
 * <p><b>Signed, for a sharp reason.</b> The first version of this class checked the downloaded jar's
 * SHA-256 against whatever latest.json itself declared — which only proves the download matches the file,
 * not that the file is trustworthy: latest.json controls both downloadUrl and sha256 in the same breath, so
 * anyone able to edit it (compromised GitHub account, leaked collaborator token, same threat as always)
 * could point downloadUrl at infrastructure they control with a sha256 that matches their own payload, and
 * this class would verify it "successfully." A forged latest.json is arbitrary code execution on every
 * machine that clicks Update, so it gets Ed25519 treatment (see {@link SigningUtil}), and the check happens
 * in {@link #checkForUpdate} before "update available" is even shown, not merely before the download in
 * {@link #startUpdate} — an unverified response never gets far enough to populate downloadUrl/sha256 at
 * all. See {@code tools/SignLatest.java}, the matching local-only signing tool.
 */
public final class UpdateApi {
	// Not treated as sensitive — knowing where the mod checks for updates doesn't grant access to anything,
	// so no reason to hide it.
	private static final String LATEST_JSON_URL = "https://raw.githubusercontent.com/cokelord42/sbs-allowlist/main/latest.json";

	// Per explicit user request, this never stops once it finds something: a newer version can get
	// published at any point during a long session, and there's no "found it, done" terminal state to stop at.
	private static final long POLL_SECONDS = 10;

	// Real root cause of "auto update doesn't trigger on Alt+F4 / crashes with a log instead": Mojang's own
	// com.mojang.blaze3d.platform.ClientShutdownWatchdog (confirmed via decompiling the real client jar) arms
	// a hard, un-cancellable-by-us 15-second kill timer the instant the native window-close callback fires —
	// identically for the taskbar/X close AND Alt+F4, completely independent of MinecraftStopMixin's own
	// interception of Minecraft.stop(). If our background download+stage takes longer than that 15s, the
	// watchdog wins the race: it writes a real crash report and force-kills the process with System.exit(-8)
	// before our own fallback ever gets to run. Scheduling our own safety timeout comfortably under 15s lets
	// startUpdate(Runnable) give up on its own terms — via the already-correct fail()/pendingCloseFallback
	// path — and let the vanilla close proceed instead of getting raced by Mojang's watchdog.
	private static final long CLOSE_UPDATE_TIMEOUT_SECONDS = 8;
	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-update-check");
		t.setDaemon(true);
		return t;
	});
	private static boolean started = false;

	public enum State { CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, ERROR }

	private static volatile State state = State.CHECKING;
	private static volatile String latestVersion = null;
	private static volatile String downloadUrl = null;
	private static volatile String expectedSha256 = null;
	private static volatile String lastError = null;

	// Per user request: the "update available" toast notification fires at most once per session, even
	// though checkForUpdate() itself keeps polling every 10s for as long as the game is open. notified
	// latches permanently the first time an update is found (never reset, so a later poll re-confirming
	// the same update can't re-trigger it); pendingNotification is the one-shot flag the toast renderer
	// polls-and-clears on the render thread.
	private static volatile boolean notifiedThisSession = false;
	private static volatile boolean pendingNotification = false;

	// Per Auto-Update On Close (see MinecraftStopMixin): a background auto-update attempt that ultimately
	// fails (network error, checksum mismatch, staging error) must still let the player's quit gesture go
	// through — without this, cancelling stop() to start the download and then silently failing would trap
	// the player mid-quit with the game just not closing and no obvious reason why. Set immediately before
	// starting an attempt that came from a close interception, invoked-and-cleared the moment fail() runs;
	// a normal manual click of the Update button never sets this, so it stays null and fail() there is a
	// no-op exactly as before.
	private static volatile Runnable pendingCloseFallback = null;

	private UpdateApi() {}

	public static State getState() {
		return state;
	}

	public static String getLatestVersion() {
		return latestVersion;
	}

	public static String getLastError() {
		return lastError;
	}

	/** The version string of the jar actually running right now, same value {@link #checkForUpdate} compares
	 *  against the remote latestVersion — exposed publicly so other callers (e.g. the Mod Information row)
	 *  can show/compare it without duplicating the FabricLoader lookup. */
	public static String getCurrentVersion() {
		return currentModVersion();
	}

	/** True exactly once, the first time it's called after an update is first found this session — every
	 *  later call (whether or not another update is later found) returns false. The toast renderer polls
	 *  this once per frame to know when to start its slide-in. */
	public static boolean consumePendingNotification() {
		if (!pendingNotification) return false;
		pendingNotification = false;
		return true;
	}

	public static synchronized void start() {
		if (started) return;
		started = true;
		scheduleCheck(0);
	}

	private static void scheduleCheck(long delaySeconds) {
		EXECUTOR.schedule(UpdateApi::checkForUpdate, delaySeconds, TimeUnit.SECONDS);
	}

	private static void checkForUpdate() {
		// Skip (but still reschedule below) while a download is actively staging — checkForUpdate()
		// overwrites downloadUrl/expectedSha256/latestVersion on success, which would race whatever
		// startUpdate() is currently mid-flight using those same fields for.
		if (state == State.DOWNLOADING) {
			scheduleCheck(POLL_SECONDS);
			return;
		}
		HttpJsonFetcher.fetchAsync(LATEST_JSON_URL).thenAccept(json -> {
			try {
				String remoteVersion = json.get("version").getAsString();
				String url = json.get("downloadUrl").getAsString();
				String sha256 = json.get("sha256").getAsString().toLowerCase(Locale.ROOT);

				byte[] signature = Base64.getDecoder().decode(json.get("signature").getAsString());
				if (!SigningUtil.verify(canonicalMessage(remoteVersion, url, sha256), signature)) {
					state = State.UP_TO_DATE; // unsigned/forged response: fail closed
					return;
				}

				String currentVersion = currentModVersion();
				boolean newer;
				try {
					Version remoteParsed = Version.parse(remoteVersion);
					Version currentParsed = Version.parse(currentVersion);
					newer = remoteParsed.compareTo(currentParsed) > 0;
				} catch (Exception notSemver) {
					// Fallback for a non-semver-parseable version string — not expected given this mod's
					// own X.Y.Z scheme, but a strict inequality is still strictly better than crashing the
					// whole check over a formatting hiccup in the remote file.
					newer = !remoteVersion.equals(currentVersion);
				}

				// Only reachable once the signature above has already verified — downloadUrl/sha256 are
				// never trusted (or even stored) from an unsigned response, closing the gap where an
				// attacker who could edit latest.json controlled both the download location and the
				// checksum meant to verify it.
				latestVersion = remoteVersion;
				downloadUrl = url;
				expectedSha256 = sha256;
				state = newer ? State.AVAILABLE : State.UP_TO_DATE;
				if (newer && !notifiedThisSession) {
					notifiedThisSession = true;
					pendingNotification = true;
				}
			} catch (Exception e) {
				state = State.UP_TO_DATE; // malformed metadata: fail closed, same as "nothing to update"
			}
		}).exceptionally(e -> {
			state = State.UP_TO_DATE; // no internet / fetch failure: fail closed rather than get stuck showing a stale/wrong button
			return null;
		}).whenComplete((r, e) -> scheduleCheck(POLL_SECONDS));
	}

	/** Must exactly match tools/SignLatest.java's canonical form: the three fields, in this order, joined
	 *  with a plain '\n'. None of version/downloadUrl/sha256 can contain a literal newline in practice
	 *  (an X.Y.Z string, a URL, a 64-char hex digest), so this is unambiguous without needing full JSON
	 *  canonicalization. */
	private static byte[] canonicalMessage(String version, String downloadUrl, String sha256) {
		return (version + "\n" + downloadUrl + "\n" + sha256).getBytes(StandardCharsets.UTF_8);
	}

	private static String currentModVersion() {
		return FabricLoader.getInstance().getModContainer("skyblocksimplified")
			.orElseThrow()
			.getMetadata().getVersion().getFriendlyString();
	}

	/** Kicks off download → verify → stage → launch helper → close game. Safe to call repeatedly; only
	 *  actually does anything while state is AVAILABLE. Any failure resets state back to AVAILABLE (with
	 *  lastError set) rather than leaving a dead-end ERROR state, so the button just comes back for a
	 *  retry — and critically, the game is never closed and nothing in mods/ is ever touched unless every
	 *  prior step (download, checksum, helper launch) already succeeded. */
	public static void startUpdate() {
		startUpdate(null);
	}

	/** Same as {@link #startUpdate()}, but {@code onGiveUp} (if non-null) runs once IF this specific attempt
	 *  ultimately fails instead of successfully relaunching — see {@link #pendingCloseFallback}'s own doc
	 *  comment for why Auto-Update On Close needs this.
	 *
	 *  <p>When {@code onGiveUp} is non-null (i.e. this attempt was triggered by the player closing the game,
	 *  not a manual Update button click), also arms a {@link #CLOSE_UPDATE_TIMEOUT_SECONDS} safety timeout —
	 *  see that constant's doc comment for why: Mojang's own watchdog will hard-kill the process with a real
	 *  crash report at 15s regardless of what we're doing, so we have to give up on our own terms first. */
	public static void startUpdate(Runnable onGiveUp) {
		if (state != State.AVAILABLE) return;
		state = State.DOWNLOADING;
		pendingCloseFallback = onGiveUp;

		if (onGiveUp != null) {
			EXECUTOR.schedule(() -> {
				if (state == State.DOWNLOADING) fail("Timed out waiting for the close-triggered background update");
			}, CLOSE_UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}

		HttpJsonFetcher.fetchBytesAsync(downloadUrl).thenAccept(UpdateApi::applyDownloadedJar)
			.exceptionally(e -> {
				fail("Download failed: " + e.getMessage());
				return null;
			});
	}

	private static void applyDownloadedJar(byte[] jarBytes) {
		try {
			String actualSha256 = sha256Hex(jarBytes);
			if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
				fail("Checksum mismatch (downloaded file doesn't match what latest.json declared)");
				return;
			}

			var container = FabricLoader.getInstance().getModContainer("skyblocksimplified").orElseThrow();
			Path currentJar = container.getOrigin().getPaths().get(0);
			Path modsDir = currentJar.getParent();
			Path gameDir = FabricLoader.getInstance().getGameDir();

			Path stagingDir = gameDir.resolve(".skyblocksimplified-updater");
			Files.createDirectories(stagingDir);
			Path stagedJar = stagingDir.resolve("pending-update.jar");
			Files.write(stagedJar, jarBytes);

			Path targetJar = modsDir.resolve("skyblocksimplified-" + latestVersion + ".jar");
			// The resource is deliberately NOT named "UpdaterMain.class" in the mod jar — ProGuard scans
			// injar zip entries ending in .class to rename/obfuscate, and this class's name has to survive
			// intact byte-for-byte since it's launched by that exact name (java -cp <dir> UpdaterMain)
			// after being extracted. ".classdata" is invisible to that scan; only written back out with
			// the real ".class" extension here, at the one point it actually needs to be a loadable class.
			try (var in = UpdateApi.class.getResourceAsStream("/updater/UpdaterMain.classdata")) {
				if (in == null) throw new java.io.IOException("Embedded updater helper resource missing");
				Files.write(stagingDir.resolve("UpdaterMain.class"), in.readAllBytes());
			}

			String javaBin = javaExecutablePath();
			long pid = ProcessHandle.current().pid();

			ProcessBuilder pb = new ProcessBuilder(
				javaBin, "-cp", stagingDir.toString(), "UpdaterMain",
				String.valueOf(pid), currentJar.toString(), stagedJar.toString(), targetJar.toString()
			);
			pb.redirectOutput(ProcessBuilder.Redirect.appendTo(stagingDir.resolve("updater-log.txt").toFile()));
			pb.redirectError(ProcessBuilder.Redirect.appendTo(stagingDir.resolve("updater-log.txt").toFile()));
			pb.start();

			// Detached: ProcessBuilder-spawned children aren't tied to this JVM's lifetime on any of
			// Windows/Mac/Linux, so it keeps running after Minecraft.stop() below tears this process down.
			Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
		} catch (Exception e) {
			fail("Couldn't stage update: " + e.getMessage());
		}
	}

	/** Three-tier fallback, each strictly weaker than the last — deliberately not a single point of
	 *  failure, since "what Java setup does the player's launcher use" spans a real range (vanilla
	 *  launcher, MultiMC, Prism Launcher, Modrinth App, and whatever else manages its own runtimes the
	 *  same general way) this project has no way to enumerate or test against directly.
	 *  <ol>
	 *  <li>{@code java.home} — a JVM-intrinsic system property, always correctly set to exactly the JRE/JDK
	 *  currently running this process, regardless of how obscure the launcher's own Java management is
	 *  (bundled runtime, custom jlink image, whatever). Existence-checked (not OS-name string matching,
	 *  more robust across the many ways "Windows" shows up in os.name) for both the Windows and Unix
	 *  binary name before trusting it.
	 *  <li>{@link ProcessHandle#info()}'s own reported command — not guaranteed to resolve on every
	 *  platform (which is why it isn't tier 1), but a real fallback if java.home ever points somewhere
	 *  with a nonstandard bin/ layout tier 1 doesn't find a binary in.
	 *  <li>A bare {@code "java"}, relying on the system PATH — only fails to help in exactly the case that
	 *  motivated this whole method (a launcher-managed Java not on PATH), but still strictly better than
	 *  refusing to try at all.
	 *  </ol> */
	private static String javaExecutablePath() {
		Path javaHome = Path.of(System.getProperty("java.home"));
		Path windowsBin = javaHome.resolve("bin").resolve("java.exe");
		if (Files.isExecutable(windowsBin)) return windowsBin.toString();
		Path unixBin = javaHome.resolve("bin").resolve("java");
		if (Files.isExecutable(unixBin)) return unixBin.toString();

		var fromProcessHandle = ProcessHandle.current().info().command();
		if (fromProcessHandle.isPresent() && Files.isExecutable(Path.of(fromProcessHandle.get()))) {
			return fromProcessHandle.get();
		}

		return "java";
	}

	private static void fail(String message) {
		lastError = message;
		state = State.AVAILABLE;
		Runnable fallback = pendingCloseFallback;
		pendingCloseFallback = null;
		if (fallback != null) fallback.run();
	}

	private static String sha256Hex(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data);
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (Exception e) {
			return "";
		}
	}

}
