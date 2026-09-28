package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.DianaColorsFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaWaypointsFeature;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.cokelord.skyblocksimplified.highlight.WorldToScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Every Diana waypoint: detected burrows, spade/arrow guesses, arrow "possible" spots, rare mob locations
 * and plain party coordinates. Ported from SBO's WaypointManager/Waypoint, keeping its upkeep rules
 * (TTL, hub-bounds cleanup, guess de-duplication, dig-count carry-over) but single-threaded — every caller
 * (particle observer, chat, tick) already runs on the client thread, so plain lists replace SBO's
 * concurrent collections.
 *
 * <p>Rendering, per user request: a screen-space text label (like Positional Messages) while the waypoint
 * is farther than the "Box Distance" setting, and a highlighted block (real 3D box, visible through walls)
 * once within it.
 */
public final class DianaWaypoints {
	public enum Type { BURROW, GUESS, ARROW, SUBGUESS, RARE_MOB, WORLD }

	// SBO's typical burrow area (WaypointManager's hub-bounds removal and ArrowGuessBurrow.HUB_BOUNDS).
	public static final int HUB_MIN_X = -283, HUB_MAX_X = 175, HUB_MIN_Y = 60, HUB_MAX_Y = 105, HUB_MIN_Z = -208, HUB_MAX_Z = 205;

	public static final class Waypoint {
		public String text;
		public final BlockPos pos;
		public final Type type;
		public final long createdNs = System.nanoTime();
		public final long ttlSeconds;
		public boolean hidden;
		public boolean closest;
		public boolean userInteracted;
		public boolean preventInvalidRemoval;
		public int timesDug;
		public int rareMobMissingTicks;
		String label = "";
		double distance;

		Waypoint(String text, BlockPos pos, Type type, long ttlSeconds) {
			this.text = text;
			this.pos = pos.immutable();
			this.type = type;
			this.ttlSeconds = ttlSeconds;
		}

		public boolean olderThanMs(long ms) {
			return System.nanoTime() - createdNs > ms * 1_000_000L;
		}

		public double distanceTo(Vec3 from) {
			return Math.sqrt(from.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()));
		}

		boolean strongerThan(Waypoint other) {
			return timesDug > other.timesDug || userInteracted && !other.userInteracted;
		}

		void carryOver(Waypoint other) {
			if (other.timesDug > timesDug) timesDug = other.timesDug;
			if (other.userInteracted) userInteracted = true;
		}
	}

	private static final EnumMap<Type, List<Waypoint>> WAYPOINTS = new EnumMap<>(Type.class);
	private static boolean registered = false;

	static {
		for (Type type : Type.values()) WAYPOINTS.put(type, new ArrayList<>());
	}

	private DianaWaypoints() {}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
			Identifier.fromNamespaceAndPath("skyblocksimplified", "diana_waypoints"), DianaWaypoints::renderLabels);
		World3DRenderer.addRenderCallback(DianaWaypoints::renderBoxes);
	}

	// ---- storage -------------------------------------------------------------------------------------------

	public static Waypoint add(String text, BlockPos pos, Type type, long ttlSeconds) {
		Waypoint waypoint = new Waypoint(text, pos, type, ttlSeconds);
		WAYPOINTS.get(type).add(waypoint);
		return waypoint;
	}

	public static void remove(Waypoint waypoint) {
		WAYPOINTS.get(waypoint.type).remove(waypoint);
	}

	public static Waypoint getAt(BlockPos pos, Type type) {
		for (Waypoint w : WAYPOINTS.get(type)) if (w.pos.equals(pos)) return w;
		return null;
	}

	public static void removeAt(BlockPos pos, Type type) {
		WAYPOINTS.get(type).removeIf(w -> w.pos.equals(pos));
	}

	public static void removeAllAt(BlockPos pos) {
		for (List<Waypoint> list : WAYPOINTS.values()) list.removeIf(w -> w.pos.equals(pos));
	}

	public static void clear(Type type) {
		WAYPOINTS.get(type).clear();
	}

	public static void clearAll() {
		for (List<Waypoint> list : WAYPOINTS.values()) list.clear();
	}

	public static List<Waypoint> ofType(Type type) {
		return WAYPOINTS.get(type);
	}

	/** Burrows, arrow guesses and spade guesses (SBO getAllGuessesAndBurrows). */
	public static List<Waypoint> guessesAndBurrows() {
		List<Waypoint> out = new ArrayList<>(WAYPOINTS.get(Type.BURROW));
		out.addAll(WAYPOINTS.get(Type.ARROW));
		out.addAll(WAYPOINTS.get(Type.GUESS));
		return out;
	}

	public static Waypoint bestGuessFrom(Vec3 from) {
		Waypoint best = null;
		double bestDist = Double.MAX_VALUE;
		for (Type type : new Type[]{Type.BURROW, Type.ARROW, Type.GUESS}) {
			for (Waypoint w : WAYPOINTS.get(type)) {
				if (w.hidden) continue;
				double d = w.distanceTo(from);
				if (d < bestDist) { bestDist = d; best = w; }
			}
		}
		return best;
	}

	public static Waypoint newestRareMob() {
		Waypoint newest = null;
		for (Waypoint w : WAYPOINTS.get(Type.RARE_MOB)) if (newest == null || w.createdNs > newest.createdNs) newest = w;
		return newest;
	}

	static boolean insideHubBounds(BlockPos pos) {
		return pos.getX() >= HUB_MIN_X && pos.getX() <= HUB_MAX_X && pos.getY() >= HUB_MIN_Y && pos.getY() <= HUB_MAX_Y
			&& pos.getZ() >= HUB_MIN_Z && pos.getZ() <= HUB_MAX_Z;
	}

	// ---- per-tick upkeep (SBO WaypointManager's onTick(1) block) -------------------------------------------

	public static void tick(Minecraft mc) {
		if (mc.player == null) return;
		boolean inHub = DianaState.inHub();
		long now = System.nanoTime();

		for (Type type : Type.values()) {
			List<Waypoint> list = WAYPOINTS.get(type);
			for (int i = list.size() - 1; i >= 0; i--) {
				Waypoint w = list.get(i);
				boolean expired = w.ttlSeconds > 0 && now - w.createdNs > w.ttlSeconds * 1_000_000_000L;
				boolean outOfBounds = inHub && !w.preventInvalidRemoval && type != Type.WORLD && !insideHubBounds(w.pos);
				if (!expired && !outOfBounds) continue;
				list.remove(i);
				if (type == Type.ARROW) {
					if (expired) ArrowGuess.removeFromInternalState(w.pos);
					else ArrowGuess.removeOrMoveFromInternalState(w.pos);
				} else if (type == Type.BURROW) {
					BurrowDetector.removeFromInternalState(w.pos);
				}
			}
		}

		if (inHub) {
			// Spade guesses pointing at blocks a burrow can't be on.
			WAYPOINTS.get(Type.GUESS).removeIf(g -> !ArrowGuess.isBlockValid(g.pos));

			// Arrow guesses on an invalid block: hidden for 15s (so the guess can advance), then removed.
			List<Waypoint> arrows = WAYPOINTS.get(Type.ARROW);
			for (int i = arrows.size() - 1; i >= 0; i--) {
				Waypoint arrow = arrows.get(i);
				if (!ArrowGuess.isBlockValid(arrow.pos)) {
					if (arrow.olderThanMs(15_000)) {
						arrows.remove(i);
						ArrowGuess.removeOrMoveFromInternalState(arrow.pos);
					} else {
						arrow.hidden = true;
					}
				} else {
					arrow.hidden = false;
				}
			}

			List<Waypoint> subs = WAYPOINTS.get(Type.SUBGUESS);
			for (int i = subs.size() - 1; i >= 0; i--) {
				Waypoint sub = subs.get(i);
				if (!ArrowGuess.isBlockValid(sub.pos)) {
					subs.remove(i);
					ArrowGuess.removeSubGuessFromInternalState(sub.pos);
				}
			}
		}

		// A spade guess within 30 blocks of a known burrow or arrow guess is redundant (the spade guess is the
		// least precise source) — fold its dig state into that waypoint and drop it.
		List<Waypoint> guesses = WAYPOINTS.get(Type.GUESS);
		for (int i = guesses.size() - 1; i >= 0; i--) {
			Waypoint guess = guesses.get(i);
			Waypoint anchor = null;
			for (Waypoint w : WAYPOINTS.get(Type.BURROW)) if (w.pos.distSqr(guess.pos) <= 900) { anchor = w; break; }
			if (anchor == null) for (Waypoint w : WAYPOINTS.get(Type.ARROW)) if (w.pos.distSqr(guess.pos) <= 900) { anchor = w; break; }
			if (anchor != null) {
				anchor.carryOver(guess);
				guesses.remove(i);
			}
		}
		// Duplicate spade guesses within 30 blocks of each other: keep the one with more progress.
		for (int i = 0; i < guesses.size(); i++) {
			for (int j = guesses.size() - 1; j > i; j--) {
				Waypoint a = guesses.get(i), b = guesses.get(j);
				if (a.pos.distSqr(b.pos) > 900) continue;
				if (b.strongerThan(a)) {
					b.carryOver(a);
					guesses.set(i, b);
				} else {
					a.carryOver(b);
				}
				guesses.remove(j);
			}
		}
		// Arrow guess on the same block as a known burrow: the burrow wins, keeping the higher dig count.
		List<Waypoint> arrows = WAYPOINTS.get(Type.ARROW);
		for (int i = arrows.size() - 1; i >= 0; i--) {
			Waypoint arrow = arrows.get(i);
			Waypoint known = getAt(arrow.pos, Type.BURROW);
			if (known == null) continue;
			known.carryOver(arrow);
			arrows.remove(i);
			ArrowGuess.removeFromInternalState(arrow.pos);
		}

		formatLabels(mc);
	}

	private static void formatLabels(Minecraft mc) {
		DianaWaypointsFeature settings = DianaWaypointsFeature.get();
		Vec3 eye = mc.player.position();
		Waypoint best = bestGuessFrom(eye);
		Waypoint newestRare = newestRareMob();
		boolean showDistance = settings == null || settings.bool("showDistance");
		boolean showDug = settings == null || settings.bool("showTimesDug");
		com.cokelord.skyblocksimplified.feature.impl.diana.DianaWarpFeature warpFeature = com.cokelord.skyblocksimplified.feature.impl.diana.DianaWarpFeature.get();
		boolean warpText = (settings == null || settings.bool("warpText")) && warpFeature != null && warpFeature.isEnabled();

		for (List<Waypoint> list : WAYPOINTS.values()) {
			for (Waypoint w : list) {
				w.distance = w.distanceTo(eye);
				w.closest = newestRare == null ? w == best : w == newestRare;
				String dist = showDistance ? " §b[" + Math.round(w.distance) + "m]" : "";
				String dug = showDug && w.type == Type.BURROW && !"Start".equals(w.text)
					? " §7[" + (w.timesDug >= 1 ? "§6" : "§e") + w.timesDug + "§7/§a2§7]" : "";
				String warp = "";
				if (warpText && w.closest && (w.type == Type.BURROW || w.type == Type.ARROW || w.type == Type.GUESS || w.type == Type.RARE_MOB)) {
					DianaWarp.Warp best1 = DianaWarp.bestWarpFor(w.pos);
					if (best1 != null) warp = " §7(warp " + best1.command() + ")";
				}
				w.label = switch (w.type) {
					case SUBGUESS -> w.text;
					case WORLD -> w.text + dist;
					default -> w.text + warp + dist + dug;
				};
			}
		}
	}

	// ---- rendering ----------------------------------------------------------------------------------------

	private static boolean shouldRender(Waypoint w) {
		return !w.hidden;
	}

	private static int colorOf(Waypoint w) {
		return switch (w.type) {
			case BURROW -> switch (w.text) {
				case "Start" -> DianaColorsFeature.color("start", 0x55FF55);
				case "Mob" -> DianaColorsFeature.color("mob", 0xFF5555);
				default -> DianaColorsFeature.color("treasure", 0xFFAA00);
			};
			case GUESS, ARROW -> w.closest ? DianaColorsFeature.color("closestGuess", 0x9933CC) : DianaColorsFeature.color("otherGuess", 0x00F6FF);
			case SUBGUESS -> DianaColorsFeature.color("subGuess", 0x8C8C8C);
			case RARE_MOB -> DianaColorsFeature.color("rareMob", 0xFFD700);
			case WORLD -> DianaColorsFeature.color("party", 0x0033FF);
		};
	}

	/** Only party/world waypoints show outside the Diana context (Hub + spade), same as SBO. */
	private static boolean typeVisible(Type type, boolean dianaContext) {
		return dianaContext || type == Type.WORLD;
	}

	private static void renderLabels(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		DianaWaypointsFeature settings = DianaWaypointsFeature.get();
		Minecraft mc = Minecraft.getInstance();
		if (settings == null || !settings.isEnabled() || mc.player == null || mc.level == null) return;
		if (com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		boolean context = DianaState.dianaContext();
		int boxDistance = settings.integer("boxDistance");
		int lineWidth = settings.integer("lineWidth");
		Vec3 eye = mc.player.position();
		try {
			for (Type type : Type.values()) {
				if (!typeVisible(type, context)) continue;
				for (Waypoint w : WAYPOINTS.get(type)) {
					if (!shouldRender(w)) continue;
					Vec3 center = new Vec3(w.pos.getX() + 0.5, w.pos.getY() + 1.5, w.pos.getZ() + 0.5);
					double distance = w.distanceTo(eye);
					if (distance > boxDistance && !w.label.isEmpty()) {
						drawLabel(graphics, mc, w.label, center, colorOf(w));
					}
					boolean line = w.closest && (w.type == Type.RARE_MOB ? settings.bool("lineToRareMob") && distance >= 8
						: settings.bool("lineToClosest") && w.type != Type.SUBGUESS && w.type != Type.WORLD);
					if (line) {
						WorldRenderUtil.drawTracer(graphics, new Vec3(w.pos.getX() + 0.5, w.pos.getY() + 1, w.pos.getZ() + 0.5),
							colorOf(w) | 0xFF000000, lineWidth, false);
					}
				}
			}
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Diana waypoint labels failed, skipping this frame", e);
		}
	}

	/** Positional-Messages-style label, but not occluded by terrain (burrows are usually behind hills). */
	private static void drawLabel(GuiGraphicsExtractor graphics, Minecraft mc, String text, Vec3 pos, int color) {
		WorldToScreen.ScreenPoint p = WorldToScreen.project(pos);
		if (p.behindCamera() || !Float.isFinite(p.x()) || !Float.isFinite(p.y())) return;
		int width = mc.font.width(text);
		int x = Math.round(p.x() - width / 2f);
		int y = Math.round(p.y());
		graphics.fill(x - 2, y - 2, x + width + 2, y + 9, 0x60000000);
		graphics.text(mc.font, text, x, y, color | 0xFF000000);
	}

	private static void renderBoxes() {
		DianaWaypointsFeature settings = DianaWaypointsFeature.get();
		Minecraft mc = Minecraft.getInstance();
		if (settings == null || !settings.isEnabled() || mc.player == null || mc.level == null) return;
		boolean context = DianaState.dianaContext();
		int boxDistance = settings.integer("boxDistance");
		int fillAlpha = Math.round(settings.integer("fillOpacity") * 2.55f);
		Vec3 eye = mc.player.position();
		for (Type type : Type.values()) {
			if (!typeVisible(type, context)) continue;
			for (Waypoint w : WAYPOINTS.get(type)) {
				if (!shouldRender(w) || w.distanceTo(eye) > boxDistance) continue;
				int color = colorOf(w);
				AABB box = new AABB(w.pos);
				if (fillAlpha > 0) World3DRenderer.drawFilledBoxThroughWalls(box, (fillAlpha << 24) | (color & 0xFFFFFF));
				World3DRenderer.drawWireBoxThroughWalls(box, color | 0xFF000000, 2f);
			}
		}
	}
}
