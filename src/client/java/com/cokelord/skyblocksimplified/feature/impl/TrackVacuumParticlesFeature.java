package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.highlight.WorldToScreen;
import com.cokelord.skyblocksimplified.particle.ParticleSpawnListenerRegistry;
import com.cokelord.skyblocksimplified.util.bezier.ParticlePathBezierFitter;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Tracks the Pest Vacuum's own homing particles (Hypixel fires a short trail of ANGRY_VILLAGER particles
 * from the vacuum toward whatever pest it's currently locked onto — confirmed real signature via
 * SkyHanni's PestParticleWaypoint.kt: type ANGRY_VILLAGER, count 1, zero speed/offset) and shows a guessed
 * target position. Uses a real degree-3 Bezier curve fit through the observed particle positions (ported
 * from SkyHanni's own PolynomialFitter/BezierFitter/ParticlePathBezierFitter — same math, not an
 * approximation) instead of naive two-point linear extrapolation, so the guess accounts for the particle's
 * actual curved ballistic arc rather than just its instantaneous heading.
 */
public class TrackVacuumParticlesFeature extends Feature {
	private static final long HOLD_WINDOW_MILLIS = 5_000;
	private static final long SHOW_MILLIS = 4_000;
	private static final double MAX_DISTANCE_TO_LAST = 3.0;
	private static final double EMPTY_CONDITION_DISTANCE = 5.0;
	private static final double CLOSE_ENOUGH_DISTANCE = 8.0;

	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "track_vacuum_particles");

	private final ParticlePathBezierFitter bezierFitter = new ParticlePathBezierFitter(3);

	private Vec3 guessPos = null;
	private long lastParticleMillis = 0;
	private long lastVacuumHeldMillis = 0;

	public TrackVacuumParticlesFeature() {
		super("track_vacuum_particles", "Track Vacuum Particles", FeatureCategory.FARMING, false);
		ParticleSpawnListenerRegistry.addListener(this::onParticle);
	}

	@Override
	public String getSubcategory() {
		return "Pest farming";
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled() || client.player == null) return;
		String heldName = client.player.getMainHandItem().getHoverName().getString().toLowerCase(Locale.ROOT);
		if (heldName.contains("vacuum")) {
			lastVacuumHeldMillis = System.currentTimeMillis();
		}

		// Close enough to the guessed target that continuing to show it is no longer useful — matches
		// SkyHanni's own onTick reset condition.
		if (guessPos != null) {
			long sinceLastParticle = System.currentTimeMillis() - lastParticleMillis;
			if (client.player.position().distanceTo(guessPos) <= CLOSE_ENOUGH_DISTANCE
				&& sinceLastParticle > 1000 && sinceLastParticle < SHOW_MILLIS) {
				resetTracking();
			}
		}
	}

	private void onParticle(ParticleSpawnListenerRegistry.Spawn spawn) {
		if (!isEnabled()) return;
		if (spawn.options().getType() != ParticleTypes.ANGRY_VILLAGER) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (System.currentTimeMillis() - lastVacuumHeldMillis > HOLD_WINDOW_MILLIS) return;

		Vec3 pos = new Vec3(spawn.x(), spawn.y(), spawn.z());
		Vec3 playerPos = mc.player.position();
		boolean added = bezierFitter.tryAdd(pos, MAX_DISTANCE_TO_LAST, loc -> loc.distanceTo(playerPos) > EMPTY_CONDITION_DISTANCE);
		lastParticleMillis = System.currentTimeMillis();
		if (!added) return;

		Vec3 solved = bezierFitter.solve();
		if (solved != null) guessPos = solved;
	}

	@Override
	protected void onEnable() {
		try {
			HudElementRegistry.removeElement(HUD_ID);
		} catch (Exception ignored) {
			// Wasn't registered — the common case.
		}
		try {
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, HUD_ID, this::render);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Failed to register track_vacuum_particles HUD layer", e);
		}
	}

	@Override
	protected void onDisable() {
		try {
			HudElementRegistry.removeElement(HUD_ID);
		} catch (Exception ignored) {
			// Already gone — fine.
		}
		resetTracking();
	}

	private void resetTracking() {
		bezierFitter.reset();
		guessPos = null;
	}

	private void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		// Wrapped in try-catch — was running completely unprotected, same gap as every other HUD widget's
		// render callback found in this same investigation (see TabWidgetOverlayFeature/
		// AbilityCooldownTimerFeature's own doc comments). An uncaught throw here risks taking out every
		// OTHER HUD widget's render for that frame too, not just this one's.
		try {
			if (guessPos == null) return;
			if (System.currentTimeMillis() - lastParticleMillis > SHOW_MILLIS) {
				resetTracking();
				return;
			}

			WorldToScreen.ScreenPoint point = WorldToScreen.project(guessPos);
			if (point.behindCamera()) return;
			Minecraft mc = Minecraft.getInstance();
			Font font = mc.font;
			String label = "Pest Guess";
			int textWidth = font.width(label);
			int x = Math.round(point.x());
			int y = Math.round(point.y());
			RenderUtil.fillRounded(graphics, x - textWidth / 2 - 3, y - 8, x + textWidth / 2 + 3, y + 6, 3, 0xC0101010);
			graphics.text(font, Component.literal(label), x - textWidth / 2, y - 4, 0xFFFF5555);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Track Vacuum Particles render failed, skipping this frame", e);
		}
	}

	@Override
	public String getDescription() {
		return "Tracks the Pest Vacuum's homing particle trail so you can tell which pest it's currently locked onto.";
	}
}
