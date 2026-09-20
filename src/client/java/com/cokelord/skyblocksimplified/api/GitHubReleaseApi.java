package com.cokelord.skyblocksimplified.api;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Per user request ("I also want to add support for patch notes in the mod information panel, if the mod
 * version is on GitHub, make it pull the release description and add it to the mod information panel so
 * players can read what the latest update was"): looks up the GitHub Release whose tag matches the exact
 * version currently running (tried both bare, e.g. "1.0.36", and "v"-prefixed, e.g. "v1.0.36" — this repo
 * had zero actual GitHub Releases published as of this being written, so the real tag convention the user
 * ends up using isn't known yet) and exposes its name/body/URL for {@link
 * com.cokelord.skyblocksimplified.feature.impl.ModInformationFeature}'s panel to render. A single one-shot
 * lookup at startup, not a repeating poll like {@link UpdateApi} — unlike "is a newer version available"
 * this is static per-version content that doesn't change while the game is already running, so there's
 * nothing to keep re-checking for.
 */
public final class GitHubReleaseApi {
	private static final String OWNER = "cokelord42";
	private static final String REPO = "sbs";

	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "skyblocksimplified-release-notes");
		t.setDaemon(true);
		return t;
	});
	private static boolean started = false;

	public enum State { LOADING, FOUND, NOT_FOUND, ERROR }

	private static volatile State state = State.LOADING;
	private static volatile String releaseName = null;
	private static volatile String releaseBody = null;
	private static volatile String htmlUrl = null;

	private GitHubReleaseApi() {}

	public static State getState() {
		return state;
	}

	/** The release's own title (e.g. "1.0.36") — may differ from the raw tag/version string. */
	public static String getReleaseName() {
		return releaseName;
	}

	/** The release description exactly as typed on GitHub (Markdown source, unrendered — MainScreen strips
	 *  the handful of common Markdown tokens it can't otherwise display). Empty string, not null, if the
	 *  release exists but its body was left blank. */
	public static String getReleaseBody() {
		return releaseBody;
	}

	public static String getHtmlUrl() {
		return htmlUrl;
	}

	public static synchronized void start() {
		if (started) return;
		started = true;
		// Real bug found (per user report — "Patch notes don't fetch the latest release's actual notes"):
		// this used to look up the release whose TAG exactly matches the currently-running jar's embedded
		// version string (falling back to a "v"-prefixed variant), which only ever finds anything on the
		// exact build a user updated to and this project's own tagging happened to match at that moment —
		// any tag-naming drift (a release cut before the version bump landed, a typo, a non-numeric suffix)
		// silently returns nothing with no way to tell "there's genuinely no release yet" apart from "the
		// lookup just didn't match". GitHub's own /releases/latest endpoint is the real fix: it always
		// resolves to whatever the most recently published release actually is, with no tag-matching to get
		// wrong — which is what "patch notes" for "what's new" should show regardless of exactly which
		// version string the running jar reports.
		EXECUTOR.submit(GitHubReleaseApi::fetchLatest);
	}

	private static void fetchLatest() {
		String url = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
		HttpJsonFetcher.fetchAsync(url).thenAccept(GitHubReleaseApi::applyRelease).exceptionally(e -> {
			// Not an error condition — the repo simply has no published GitHub Release yet. The panel just
			// shows nothing in that case rather than an error message.
			state = State.NOT_FOUND;
			return null;
		});
	}

	private static void applyRelease(com.google.gson.JsonObject json) {
		try {
			releaseName = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : null;
			releaseBody = json.has("body") && !json.get("body").isJsonNull() ? json.get("body").getAsString() : "";
			htmlUrl = json.has("html_url") && !json.get("html_url").isJsonNull() ? json.get("html_url").getAsString() : null;
			state = State.FOUND;
		} catch (Exception e) {
			state = State.ERROR;
		}
	}
}
