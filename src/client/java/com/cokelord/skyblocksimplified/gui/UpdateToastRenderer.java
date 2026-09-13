package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.UpdateApi;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * Bottom-right "update available" toast — fires once per session (see
 * {@link UpdateApi#consumePendingNotification}), slides in over {@link #SLIDE_SECONDS} using the same
 * exponential chase every other animation in this project uses ({@link Anim}), stays up for
 * {@link #VISIBLE_SECONDS} total, then slides back out the same way. Always-on infra registered once at
 * client init (same pattern as HighlightBoxRenderer) rather than gated behind a feature toggle — this
 * isn't a real feature to configure, just a notification.
 *
 * <p>Uses a manual {@link System#nanoTime()} delta instead of {@link DeltaTracker}'s tick-based partial-tick
 * value, since {@link Anim} expects real wall-clock seconds and this needs to keep animating smoothly
 * regardless of the game's tick rate.
 */
public final class UpdateToastRenderer {
	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "update_toast_renderer");
	private static boolean registered = false;

	private static final float SLIDE_SECONDS = 0.5f;
	private static final float VISIBLE_SECONDS = 5f;
	private static final int WIDTH = 220;
	private static final int HEIGHT = 40;
	private static final int MARGIN = 12;
	private static final int RADIUS = 6;

	private static boolean active = false;
	private static boolean slidingOut = false;
	private static float elapsedSeconds = 0f;
	private static long lastFrameNanoTime = 0L;
	private static final Anim slideX = new Anim(0f, SLIDE_SECONDS);

	private UpdateToastRenderer() {}

	public static void register() {
		if (registered) return;
		registered = true;
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, HUD_ID, UpdateToastRenderer::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		try {
			renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Update toast render failed, skipping this frame", e);
		}
	}

	private static void renderInner(GuiGraphicsExtractor graphics) {
		if (!active && UpdateApi.consumePendingNotification()) {
			start();
		}
		if (!active) return;

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
		int x0 = Math.round(slideX.get());
		int y0 = mc.getWindow().getGuiScaledHeight() - HEIGHT - MARGIN;
		int x1 = x0 + WIDTH;
		int y1 = y0 + HEIGHT;

		RenderUtil.fillRounded(graphics, x0, y0, x1, y1, RADIUS, 0xF0101010);
		RenderUtil.fillRounded(graphics, x0, y0, x0 + 3, y1, 0, Theme.accent());
		graphics.text(mc.font, "§lUpdate available", x0 + 12, y0 + 10, 0xFFFFFFFF);
		String version = UpdateApi.getLatestVersion();
		graphics.text(mc.font, version != null ? "Version " + version + " is ready" : "A new version is ready",
			x0 + 12, y0 + 24, 0xFFAAAAAA);
	}

	private static void start() {
		active = true;
		slidingOut = false;
		elapsedSeconds = 0f;
		lastFrameNanoTime = 0L;
		slideX.setDurationSeconds(SLIDE_SECONDS);
		slideX.snapTo(offscreenX());
		slideX.setTarget(onscreenX());
		playNotificationSounds();
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

	// Per user request: random.orb (the classic XP-orb pickup sound, EXPERIENCE_ORB_PICKUP under its
	// modern name) plus the standard vanilla options-menu button click sound, together.
	private static void playNotificationSounds() {
		var soundManager = Minecraft.getInstance().getSoundManager();
		// forUI(sound, pitch) alone hardcodes 0.25f volume (confirmed via decompile) — real fix for "all the
		// sounds are really low volume": use the 3-arg overload with a real 1.0f volume instead.
		soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f));
		soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1f));
	}
}
