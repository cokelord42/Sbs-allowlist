package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.particle.ParticleFilterRegistry;
import com.cokelord.skyblocksimplified.particle.ParticlePacketObserverRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Renders a single clean line for the Mage class's beam-ability particle trail (instead of the raw
 *  scattered firework-particle spray), and can hide the vanilla particles entirely. Ported from Odin's
 *  {@code MageBeam.kt}, using this codebase's existing {@code ParticlePacketObserverRegistry}/
 *  {@code ParticleFilterRegistry} particle-packet infra (already Mixin-backed) instead of a new hook.
 *
 * <p>Round 2 (per user report — "the mage line issue", checked against Odin's real source): the line
 * itself was drawn via {@link com.cokelord.skyblocksimplified.highlight.WorldRenderUtil}, this codebase's
 * screen-space-projected 2D line renderer (flat AABB-corner-style projection plus a CPU raycast
 * approximating occlusion) — never real 3D geometry. Odin's own {@code MageBeam.kt} draws its beam through
 * a real depth-tested {@code VertexConsumer}, same as every other line/box it renders, with a user-facing
 * "Depth Check" toggle deciding whether it's occluded by walls at all. This codebase already has the exact
 * real-3D equivalent — {@link World3DRenderer#drawLine}/{@link World3DRenderer#drawLineThroughWalls}, proven
 * working via Dungeon Routes' own waypoint path line — so this now draws through that instead, with the
 * same Depth Check toggle Odin exposes. Also fixes task #121 ("mage beam renders through everything,
 * including gui") for free: real level-pass geometry is drawn before the GUI layer even starts, so there's
 * no screen-open check left to need — unlike the old {@code HudElementRegistry} hook, which composited on
 * top of whatever screen happened to be open.
 *
 * <p>{@code points} stays a plain {@code ArrayList} — a full-array-copy structure would be a real lag
 * source for it specifically, since a beam's point list is written to once per incoming particle (many
 * per second during a cast) while also being rescanned every client tick.
 *
 * <p>{@code activeBeams} is a real, confirmed exception to that: a previous round of this comment claimed
 * no concurrency need existed for it either (reasoning that {@code ParticleEventPacketMixin}'s injection
 * point always runs on the main client thread same as the tick/render code reading this list) and swapped
 * it from {@code CopyOnWriteArrayList} to a plain {@code ArrayList} — but a real crash (a null {@code Beam}
 * read out of this exact list mid-iteration, confirmed via the proguard mapping against the user's actual
 * crash report) proved that assumption wrong for this MC version/setup. Reverted to
 * {@code CopyOnWriteArrayList} for this list specifically — cheap here regardless, since
 * {@link #MAX_ACTIVE_BEAMS} caps it at a handful of small object references, nothing like the up-to-40-
 * point-per-beam churn {@code points} sees. */
public class MageBeamFeature extends Feature {
	private static final class Beam {
		final List<Vec3> points = new ArrayList<>();
		int lastUpdateTick;
		final int removeAtTick;
		Vec3 closestPoint;
		Vec3 furthestPoint;

		Beam(Vec3 first, int tick, int removeAtTick) {
			points.add(first);
			lastUpdateTick = tick;
			this.removeAtTick = removeAtTick;
		}

		void updateEndpoints(Vec3 playerPos) {
			if (points.isEmpty()) return;
			Vec3 closest = points.get(0), furthest = points.get(0);
			double minSqr = closest.distanceToSqr(playerPos), maxSqr = minSqr;
			for (int i = 1; i < points.size(); i++) {
				Vec3 p = points.get(i);
				double d = p.distanceToSqr(playerPos);
				if (d < minSqr) { minSqr = d; closest = p; }
				if (d > maxSqr) { maxSqr = d; furthest = p; }
			}
			closestPoint = closest;
			furthestPoint = furthest;
		}
	}

	// Real lag source: nothing capped how many beams could pile up in activeBeams — a full dungeon party
	// all casting Mage abilities within the same durationTicks window could easily rack up dozens of
	// simultaneous beams, each drawing its own line every frame. Only the most recent ones are worth
	// showing (older beams are also the ones closest to expiring anyway), so the oldest are dropped once
	// this cap is exceeded instead of letting the list grow unbounded.
	private static final int MAX_ACTIVE_BEAMS = 8;

	private final List<Beam> activeBeams = new java.util.concurrent.CopyOnWriteArrayList<>();
	private int currentTick = 0;

	private int durationTicks = 40;
	private int color = 0xFFAA0000;
	private float opacity = 1f;
	private int thickness = 8;
	private boolean hideParticles = true;
	private int minPoints = 3;
	// Matches Odin's own MageBeam.kt "Depth Check" setting (default true there too) — whether the beam is
	// occluded by walls/terrain like real world geometry, or always drawn through them.
	private boolean depthCheck = true;

	private static boolean listenersRegistered = false;
	private static MageBeamFeature instance;

	public MageBeamFeature() {
		super("mage_beam", "Mage Beam", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		activeBeams.clear();
		currentTick = 0;
		if (!listenersRegistered) {
			listenersRegistered = true;

			ParticlePacketObserverRegistry.register(event -> {
				if (instance == null || !instance.isEnabled()) return;
				if (!DungeonState.isInDungeon() || event.type() != ParticleTypes.FIREWORK) return;
				instance.onFireworkParticle(event.location());
			});

			ParticleFilterRegistry.setRule("mage_beam", options ->
				instance != null && instance.isEnabled() && instance.hideParticles
					&& DungeonState.isInDungeon() && options == ParticleTypes.FIREWORK);

			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance == null || !instance.isEnabled() || !DungeonState.isInDungeon() || client.player == null) return;
				instance.currentTick++;
				Vec3 playerPos = client.player.position();
				for (Beam beam : instance.activeBeams) beam.updateEndpoints(playerPos);
				instance.activeBeams.removeIf(b -> instance.currentTick >= b.removeAtTick);
			});

			World3DRenderer.addRenderCallback(MageBeamFeature::renderStatic);
		}
	}

	@Override
	protected void onDisable() {
		activeBeams.clear();
	}

	// Real lag source found: nothing capped how many points a single beam's own list could grow to — a
	// mage beam stays "recent" (and keeps appending) for its whole durationTicks window, and every one of
	// those points gets rescanned in full, every single client tick, by updateEndpoints (needed since it's
	// relative to the player's current, constantly-moving position — not something that can be computed
	// once and cached). A beam that receives many particle packets over 40+ ticks could grow into the
	// hundreds of points, and with up to MAX_ACTIVE_BEAMS of those all rescanned 20 times/sec, that adds up
	// fast during a party-wide Mage-heavy boss fight. Only the closest/furthest point ever actually gets
	// used for rendering, so points past this cap add scan cost for zero visual benefit.
	private static final int MAX_POINTS_PER_BEAM = 40;

	private void onFireworkParticle(Vec3 point) {
		Beam recent = activeBeams.isEmpty() ? null : activeBeams.get(activeBeams.size() - 1);
		// Real bug found (per user report — "it doesnt show up sometimes when im too close to the mob"):
		// requiring the next particle to land in the EXACT same tick as the last one (< 1) assumed Odin's
		// original raw-packet timing, where every particle in a beam burst really does arrive together —
		// but this codebase polls particles on a per-tick basis (see this class's own doc comment on why),
		// and a short beam (close range) compresses the same particle count into a shorter physical span,
		// making it more likely consecutive particles straddle a tick boundary by chance. Any beam whose
		// particles happened to land one tick apart silently started a brand-new Beam every time instead of
		// accumulating — never reaching minPoints, so it just never rendered. Widened to tolerate a couple
		// ticks of drift between particles of the same real beam.
		if (recent != null && (currentTick - recent.lastUpdateTick) <= 2 && isInBeamDirection(recent.points, point)) {
			if (recent.points.size() >= MAX_POINTS_PER_BEAM) recent.points.remove(0);
			recent.points.add(point);
			recent.lastUpdateTick = currentTick;
		} else {
			activeBeams.add(new Beam(point, currentTick, currentTick + durationTicks));
			while (activeBeams.size() > MAX_ACTIVE_BEAMS) activeBeams.remove(0);
		}
	}

	private static boolean isInBeamDirection(List<Vec3> points, Vec3 newPoint) {
		if (points.size() <= 1) return true;
		Vec3 last = points.get(points.size() - 1);
		Vec3 dir1 = last.subtract(points.get(0)).normalize();
		Vec3 dir2 = newPoint.subtract(last).normalize();
		// Real bug found (per user report — same "too close" symptom as the tick-window fix above): a short
		// beam (close-range cast) means each individual point-to-point segment is also physically short,
		// which amplifies ordinary particle-position jitter into a proportionally larger angular deviation —
		// a dot-product cutoff this tight (>0.99, ~8 degrees) was rejecting real continuations of the same
		// beam at close range far more often than at long range, splitting one beam into several sub-
		// minPoints fragments that never rendered. Only the closest/furthest point of a beam is ever actually
		// drawn, so loosening this has no real visual cost even if it occasionally lumps a slight bend in.
		return dir1.dot(dir2) > 0.9;
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled() || !DungeonState.isInDungeon()) return;
		try {
			for (Beam beam : instance.activeBeams) {
				if (beam.points.size() < instance.minPoints) continue;
				Vec3 closest = beam.closestPoint, furthest = beam.furthestPoint;
				if (closest == null || furthest == null || closest.equals(furthest)) continue;
				int alpha = Math.round(RenderUtil.clamp01(instance.opacity) * 255f);
				int renderColor = (alpha << 24) | (instance.color & 0xFFFFFF);
				// Per user report ("it doesn't look that good when it turns invisible half the time"): plain
				// depth-tested drawLine is real, correct occlusion (same primitive Positional Messages' own
				// depthCheck already uses) — the "half the time" symptom isn't a rendering bug, it's what real
				// occlusion looks like for a straight line swept through a dungeon room full of blocks/pillars,
				// popping fully in and out as it crosses behind them. Rather than choosing between "always
				// solid, ignores walls" (Depth Check off) and "vanishes constantly" (Depth Check on), this now
				// layers both: a faint through-walls pass everywhere, then the real depth-tested pass on top —
				// same "dim when occluded, full color when actually visible" idea this codebase's own ESP-style
				// entity highlight occlusion mode already uses, so the beam is never fully invisible but still
				// reads as properly occluded, not just an x-ray line.
				if (instance.depthCheck) {
					int dimAlpha = Math.round(alpha * 0.35f);
					int dimColor = (dimAlpha << 24) | (instance.color & 0xFFFFFF);
					World3DRenderer.drawLineThroughWalls(closest, furthest, dimColor, instance.thickness);
					World3DRenderer.drawLine(closest, furthest, renderColor, instance.thickness);
				} else {
					World3DRenderer.drawLineThroughWalls(closest, furthest, renderColor, instance.thickness);
				}
			}
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Mage Beam render failed, skipping this frame", e);
		}
	}

	public int getDurationTicks() { return durationTicks; }
	public void setDurationTicks(int value) { durationTicks = Math.max(1, Math.min(100, value)); }
	public int getColor() { return color; }
	public void setColor(int value) { color = 0xFF000000 | (value & 0xFFFFFF); }
	public float getOpacity() { return opacity; }
	public void setOpacity(float value) { opacity = RenderUtil.clamp01(value); }
	public int getThickness() { return thickness; }
	public void setThickness(int value) { thickness = Math.max(1, Math.min(20, value)); }
	public boolean isHideParticles() { return hideParticles; }
	public void setHideParticles(boolean value) { hideParticles = value; }
	public int getMinPoints() { return minPoints; }
	public void setMinPoints(int value) { minPoints = Math.max(2, Math.min(10, value)); }
	public boolean isDepthCheck() { return depthCheck; }
	public void setDepthCheck(boolean value) { depthCheck = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("durationTicks", durationTicks);
		obj.addProperty("color", color);
		obj.addProperty("opacity", opacity);
		obj.addProperty("thickness", thickness);
		obj.addProperty("hideParticles", hideParticles);
		obj.addProperty("minPoints", minPoints);
		obj.addProperty("depthCheck", depthCheck);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("durationTicks")) durationTicks = obj.get("durationTicks").getAsInt();
		if (obj.has("color")) color = obj.get("color").getAsInt();
		if (obj.has("opacity")) opacity = obj.get("opacity").getAsFloat();
		if (obj.has("thickness")) thickness = obj.get("thickness").getAsInt();
		if (obj.has("hideParticles")) hideParticles = obj.get("hideParticles").getAsBoolean();
		if (obj.has("minPoints")) minPoints = obj.get("minPoints").getAsInt();
		if (obj.has("depthCheck")) depthCheck = obj.get("depthCheck").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Draws a single clean line for the Mage class's beam ability instead of the scattered particle spray, with an option to hide the vanilla particles.";
	}
}
