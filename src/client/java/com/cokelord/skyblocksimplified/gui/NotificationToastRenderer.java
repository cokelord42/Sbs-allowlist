package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.UpdateApi;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.CustomMenuFontFeature;
import com.cokelord.skyblocksimplified.feature.impl.ModNotificationsFeature;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bottom-right notification toast — per user request ("The update notification we currently have now should
 * have other uses instead of just the update notification. This is why im making this module to use the
 * exact same notification system and notification look"), this generalizes the old single-purpose
 * {@code UpdateToastRenderer} (which only ever showed one hardcoded "update available" message) into a real
 * FIFO queue any part of the mod can push a title/subtitle onto via {@link #show}. {@link ModNotificationsFeature}
 * owns which kinds of notification are enabled and the shared sound they all play; this class stays a dumb,
 * feature-agnostic queue/animation, matching this project's usual settings/render split. Same visual look
 * and slide-in/out timing as the original toast (see {@link #SLIDE_SECONDS}/{@link #VISIBLE_SECONDS}), just
 * no longer hardcoded to one message — a burst of several notifications (e.g. an update found right as a
 * config import completes) plays back to back instead of overlapping.
 *
 * <p>Per user request ("Make the color match the mod color hex picker color if it isnt already"): both the
 * left accent stripe (already themed before this change) and the title text now read {@link Theme#accent()}
 * — the same accent color {@code GuiColorFeature}'s hex picker sets — instead of the title staying a plain
 * white regardless of theme.
 *
 * <p>Uses a manual {@link System#nanoTime()} delta instead of {@link DeltaTracker}'s tick-based partial-tick
 * value, since {@link Anim} expects real wall-clock seconds and this needs to keep animating smoothly
 * regardless of the game's tick rate.
 */
public final class NotificationToastRenderer {
	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "notification_toast_renderer");
	private static boolean registered = false;

	private static final float SLIDE_SECONDS = 0.5f;
	private static final float VISIBLE_SECONDS = 5f;
	private static final int WIDTH = 220;
	private static final int HEIGHT = 40;
	private static final int MARGIN = 12;
	private static final int RADIUS = 6;
	// Per user report ("The notification doesnt fit the entire export line. The character size cuts off."):
	// a subtitle can force a second line by embedding "\n" (see the Export Successful call site) instead of
	// the toast growing wider — SUBTITLE_LINE_HEIGHT is the extra row height that adds per extra line.
	private static final int SUBTITLE_LINE_HEIGHT = 10;

	private record Notification(String title, String subtitle) {}
	private static final Deque<Notification> queue = new ArrayDeque<>();

	private static boolean active = false;
	private static boolean slidingOut = false;
	private static float elapsedSeconds = 0f;
	private static long lastFrameNanoTime = 0L;
	private static final Anim slideX = new Anim(0f, SLIDE_SECONDS);
	private static String currentTitle = "";
	private static String currentSubtitle = "";

	private NotificationToastRenderer() {}

	public static void register() {
		if (registered) return;
		registered = true;
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, HUD_ID, NotificationToastRenderer::render);
	}

	/** Queues a notification popup. No-op entirely (nothing queued, nothing shown) while
	 *  {@link ModNotificationsFeature} itself is disabled — callers still only need to check their own
	 *  specific subtoggle (Update Ready / Export Successful / Import Successful) before calling this; the
	 *  master on/off switch is handled here, once, centrally. Safe to call from anywhere the mod menu or
	 *  other client code already runs on (render thread), same as every existing call site this replaces.
	 *
	 *  <p>Per user request ("make sure that a notification doesnt play fully, if another notification comes
	 *  along it should retract and the new one should slide in like normal... the notification should cancel
	 *  early when another one is queued"): if one is already up and hasn't started sliding out yet, this cuts
	 *  its hold short and starts that slide-out immediately instead of letting {@link #VISIBLE_SECONDS} run out
	 *  first. {@link #renderInner} already polls the queue for the next notification the instant the current
	 *  one finishes sliding fully offscreen, so forcing that slide-out early is all this needs to do — the
	 *  just-queued notification slides in right behind it with no other code path involved. */
	public static void show(String title, String subtitle) {
		if (FeatureRegistry.get("mod_notifications") instanceof ModNotificationsFeature mnf && !mnf.isEnabled()) return;
		queue.addLast(new Notification(title, subtitle));
		if (active && !slidingOut) {
			slidingOut = true;
			slideX.setTarget(offscreenX());
		}
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		// Per user report ("The notification doesnt render above the gui background blackout"): generalized
		// from a MainScreen-only check into "skip whenever ANY screen is open" — NotificationToastZOrderMixin
		// now draws (and animates) the toast for every screen instead, injected at the tail of every screen's
		// own render so it's guaranteed to land on top of that screen's background/blur/widgets, not just
		// MainScreen's. Skipping here entirely whenever a screen is open avoids double-rendering (and
		// double-speed animating, since renderInner() advances state every time it's called) the same toast
		// twice in one frame.
		if (Minecraft.getInstance().gui.screen() != null) return;
		renderOverlay(graphics);
	}

	/** Draws the toast on top of whatever has already been drawn this frame — the normal HUD-layer path above
	 *  calls this when no screen is open, and {@code NotificationToastZOrderMixin} calls it directly at the
	 *  tail of every screen's own render whenever one is (see {@link #render}'s own doc comment for why). */
	public static void renderOverlay(GuiGraphicsExtractor graphics) {
		try {
			renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Notification toast render failed, skipping this frame", e);
		}
	}

	private static void renderInner(GuiGraphicsExtractor graphics) {
		if (!active) {
			// Update-ready is the one notification kind still driven by polling instead of an explicit
			// producer call — UpdateApi has no reference back into the GUI/feature layer, so this is the one
			// remaining place that bridges it into the shared queue, gated the same way every other kind is.
			if (UpdateApi.consumePendingNotification()
					&& (!(FeatureRegistry.get("mod_notifications") instanceof ModNotificationsFeature mnf) || mnf.isUpdateReadyEnabled())) {
				String version = UpdateApi.getLatestVersion();
				show("Update available", version != null ? "Version " + version + " is ready" : "A new version is ready");
			}
			if (queue.isEmpty()) return;
			Notification next = queue.pollFirst();
			currentTitle = next.title();
			currentSubtitle = next.subtitle();
			start();
		}

		float dt = nextDeltaSeconds();
		elapsedSeconds += dt;
		if (!slidingOut && elapsedSeconds >= VISIBLE_SECONDS - SLIDE_SECONDS) {
			slidingOut = true;
			slideX.setTarget(offscreenX());
		}
		slideX.update(dt);

		if (slidingOut && Math.abs(slideX.get() - offscreenX()) < 0.5f) {
			active = false;
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		String[] subtitleLines = currentSubtitle.split("\n", -1);
		int height = HEIGHT + Math.max(0, subtitleLines.length - 1) * SUBTITLE_LINE_HEIGHT;
		int x0 = Math.round(slideX.get());
		int y0 = mc.getWindow().getGuiScaledHeight() - height - MARGIN;
		int x1 = x0 + WIDTH;
		int y1 = y0 + height;

		RenderUtil.fillRounded(graphics, x0, y0, x1, y1, RADIUS, 0xF0101010);
		RenderUtil.fillRounded(graphics, x0, y0, x0 + 3, y1, 0, Theme.accent());
		text(graphics, "§l" + currentTitle, x0 + 12, y0 + 10, Theme.accent());
		for (int i = 0; i < subtitleLines.length; i++) {
			text(graphics, subtitleLines[i], x0 + 12, y0 + 24 + i * SUBTITLE_LINE_HEIGHT, 0xFFAAAAAA);
		}
	}

	/** Per user request ("I want all mod related guis to use this font, specifically the notification
	 *  handler..." / later: "it should only apply to the notification toasts"): same drop-in wrapper shape as
	 *  {@code MainScreen}'s own {@code text()}, using the same master toggle the menu itself uses — this toast
	 *  renders independently of whether the mod menu is even open, but follows the exact same on/off switch. */
	private static void text(GuiGraphicsExtractor graphics, String str, int x, int y, int color) {
		// Per user request ("exempt the symbols from the font renderer" — MainScreen's own text() doc comment
		// has the full explanation): same MSDF-atlas-has-no-glyph fallback as MainScreen, so a symbol here
		// doesn't silently render as an empty gap either.
		if (CustomMenuFontFeature.isMenuFontActive() && MsdfFont.supportsAllGlyphs(str)) {
			MsdfFont.draw(graphics, str, x, y, color);
		} else {
			graphics.text(Minecraft.getInstance().font, str, x, y, color);
		}
	}

	private static void start() {
		active = true;
		slidingOut = false;
		elapsedSeconds = 0f;
		lastFrameNanoTime = 0L;
		slideX.setDurationSeconds(SLIDE_SECONDS);
		slideX.snapTo(offscreenX());
		slideX.setTarget(onscreenX());
		if (FeatureRegistry.get("mod_notifications") instanceof ModNotificationsFeature mnf) mnf.getSound().play();
	}

	private static float onscreenX() {
		return Minecraft.getInstance().getWindow().getGuiScaledWidth() - WIDTH - MARGIN;
	}

	private static float offscreenX() {
		return Minecraft.getInstance().getWindow().getGuiScaledWidth();
	}

	private static float nextDeltaSeconds() {
		long now = System.nanoTime();
		if (lastFrameNanoTime == 0L) {
			lastFrameNanoTime = now;
			return 0f;
		}
		float dt = (now - lastFrameNanoTime) / 1_000_000_000f;
		lastFrameNanoTime = now;
		// Clamp against a huge first-frame/stutter gap (e.g. the game was paused/loading) turning into one
		// giant animation jump.
		return Math.max(0f, Math.min(dt, 0.1f));
	}
}
