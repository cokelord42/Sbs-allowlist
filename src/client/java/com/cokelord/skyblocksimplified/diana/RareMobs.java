package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.DianaColorsFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.RareMobReceiveFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.RareMobScanFeature;
import com.cokelord.skyblocksimplified.util.ChatText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rare Diana mobs (Minos Inquisitor, King Minos, Manticore, Sphinx): waypoints from party coordinates and
 * from loaded-chunk scans, plus their titles. Ported from SBO's WaypointManager (coordinate regex, mob-name
 * aliases, 10/60-block de-duplication, 45s TTL, ground snapping, 40-tick stale removal).
 */
public final class RareMobs {
	/** SBO's coordinate message regex (Patcher-style "x: 1, y: 2, z: 3 | trailing"). */
	private static final Pattern COORDS = Pattern.compile(
		"^(?<channel>.*> )?(?<playerName>.+?)[§&]f: (?:[§&]r)?x: (?<x>[^ ,]+),? y: (?<y>[^ ,]+),? z: (?<z>[^ ,]+)(?<trailing>.*)$");

	public enum Mob {
		INQ("Minos Inquisitor", "INQUISITOR!", "titleInquisitor", 0xFF55FF),
		KING("King Minos", "KING MINOS!", "titleKing", 0xFFAA00),
		MANTI("Manticore", "MANTICORE!", "titleManticore", 0x00AA00),
		SPHINX("Sphinx", "SPHINX!", "titleSphinx", 0x5555FF);

		public final String displayName;
		final String title;
		final String colorKey;
		final int defaultColor;

		Mob(String displayName, String title, String colorKey, int defaultColor) {
			this.displayName = displayName;
			this.title = title;
			this.colorKey = colorKey;
			this.defaultColor = defaultColor;
		}

		public String key() { return name().toLowerCase(Locale.ROOT); }

		public static Mob fromAlias(String alias) {
			return switch (alias) {
				case "minos inquisitor", "inquisitor", "inq" -> INQ;
				case "king minos", "king" -> KING;
				case "manticore" -> MANTI;
				case "sphinx" -> SPHINX;
				default -> null;
			};
		}

		public static Mob fromName(String name) {
			for (Mob mob : values()) if (name.contains(mob.displayName)) return mob;
			return null;
		}
	}

	private static int scanTick = 0;

	private RareMobs() {}

	public static void showTitle(Mob mob, String subtitle) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		int rgb = DianaColorsFeature.color(mob.colorKey, mob.defaultColor) & 0xFFFFFF;
		MutableComponent frame = Component.literal("<").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal("O").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD, ChatFormatting.OBFUSCATED))
			.append(Component.literal(">").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		MutableComponent title = Component.empty().append(frame.copy()).append(" ")
			.append(Component.literal(mob.title).withColor(rgb).withStyle(ChatFormatting.BOLD)).append(" ").append(frame);
		mc.gui.hud.setTimes(0, 60, 0);
		mc.gui.hud.setSubtitle(Component.literal(subtitle == null ? "" : subtitle));
		mc.gui.hud.setTitle(title);
		mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1.5f);
	}

	// ---- receive (party coordinates) ---------------------------------------------------------------------

	static void onChat(String legacy) {
		RareMobReceiveFeature feature = RareMobReceiveFeature.get();
		if (feature == null || !feature.isEnabled()) return;
		Matcher m = COORDS.matcher(legacy);
		if (!m.matches()) return;
		String channel = m.group("channel") == null ? "" : m.group("channel");
		if (channel.contains("Guild")) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		int x, y, z;
		try {
			x = (int) Math.round(Double.parseDouble(ChatText.strip(m.group("x"))));
			y = (int) Math.round(Double.parseDouble(ChatText.strip(m.group("y"))));
			z = (int) Math.round(Double.parseDouble(ChatText.strip(m.group("z"))));
		} catch (NumberFormatException e) {
			return;
		}
		y = firstSolidBelow(mc.level, x, y, z);
		String player = m.group("playerName");
		String selfName = mc.player.getGameProfile().name();
		boolean own = ChatText.strip(player).contains(selfName);
		String trailing = ChatText.strip(m.group("trailing")).replace("|", "").trim().toLowerCase(Locale.ROOT);
		Mob mob = Mob.fromAlias(trailing);
		BlockPos pos = new BlockPos(x, y, z);

		if (mob != null) {
			if (!feature.bool(mob.key())) return;
			if (own && feature.bool("ignoreOwn")) return;
			for (DianaWaypoints.Waypoint w : DianaWaypoints.ofType(DianaWaypoints.Type.RARE_MOB)) {
				if (w.pos.distSqr(pos) <= 100) return;
			}
			if (feature.bool("title")) showTitle(mob, ChatText.strip(player).trim());
			DianaWaypoints.add(mob.displayName + " §7(" + ChatText.strip(player).trim() + ")", pos, DianaWaypoints.Type.RARE_MOB, 45);
		} else if (feature.bool("plainCoords")) {
			if (own) return;
			DianaWaypoints.add(player.trim(), pos, DianaWaypoints.Type.WORLD, 30);
		}
	}

	private static int firstSolidBelow(ClientLevel level, int x, int y, int z) {
		for (int cy = y; cy > level.getMinY(); cy--) {
			if (!level.getBlockState(new BlockPos(x, cy, z)).isAir()) return cy;
		}
		return y;
	}

	// ---- scan (loaded chunks) -----------------------------------------------------------------------------

	static void tick(Minecraft mc) {
		if (++scanTick % 4 != 0) return;
		RareMobScanFeature scan = RareMobScanFeature.get();
		RareMobReceiveFeature receive = RareMobReceiveFeature.get();
		boolean scanOn = scan != null && scan.isEnabled();
		boolean validate = scanOn || receive != null && receive.isEnabled();
		if (!validate || mc.level == null || mc.player == null || !DianaState.inHub()) return;

		List<Vec3> present = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
			String name = stand.getCustomName().getString();
			if (name.contains("0/")) continue;
			Mob mob = Mob.fromName(name);
			if (mob == null || stand.isDeadOrDying()) continue;
			Vec3 at = stand.position();
			present.add(at);
			if (!scanOn || !scan.bool(mob.key())) continue;
			// Best effort not to reveal mobs the player couldn't see anyway (SBO does the same).
			if (!mc.player.hasLineOfSight(stand)) continue;
			boolean known = false;
			for (DianaWaypoints.Waypoint w : DianaWaypoints.ofType(DianaWaypoints.Type.RARE_MOB)) {
				if (w.distanceTo(at) <= 60) { known = true; break; }
			}
			if (known) continue;
			boolean ownSpawn = mob.displayName.equals(DianaTracker.lastSpawnedMob) && System.currentTimeMillis() - DianaTracker.lastSpawnedMobAt <= 5000;
			if (ownSpawn && scan.bool("ignoreOwn")) continue;
			if (scan.bool("title")) showTitle(mob, null);
			DianaWaypoints.add(mob.displayName, groundBelow(mc.level, at), DianaWaypoints.Type.RARE_MOB, 45);
		}
		removeStale(mc, present);
	}

	/** A rare mob waypoint within 30 blocks with no rare mob near it for ~2s (40 ticks) is gone. */
	private static void removeStale(Minecraft mc, List<Vec3> present) {
		Vec3 player = mc.player.position();
		List<DianaWaypoints.Waypoint> list = DianaWaypoints.ofType(DianaWaypoints.Type.RARE_MOB);
		for (int i = list.size() - 1; i >= 0; i--) {
			DianaWaypoints.Waypoint w = list.get(i);
			if (w.distanceTo(player) > 30) {
				w.rareMobMissingTicks = 0;
				continue;
			}
			boolean found = false;
			for (Vec3 p : present) if (w.distanceTo(p) <= 30) { found = true; break; }
			if (found) {
				w.rareMobMissingTicks = 0;
				continue;
			}
			w.rareMobMissingTicks += 4;
			if (w.rareMobMissingTicks >= 40) list.remove(i);
		}
	}

	/** SBO floorToGround: grass straight down, else nearby grass, else any solid block below/nearby. */
	private static BlockPos groundBelow(ClientLevel level, Vec3 pos) {
		int x = (int) Math.round(pos.x), y = (int) Math.round(pos.y), z = (int) Math.round(pos.z);
		Predicate<BlockState> grass = s -> s.is(Blocks.GRASS_BLOCK);
		Predicate<BlockState> solid = s -> !s.isAir();
		Integer gy = findGroundY(level, x, y, z, grass);
		if (gy != null) return new BlockPos(x, gy, z);
		BlockPos near = findNearby(level, x, y, z, grass);
		if (near != null) return near;
		gy = findGroundY(level, x, y, z, solid);
		if (gy != null) return new BlockPos(x, gy, z);
		near = findNearby(level, x, y, z, solid);
		return near != null ? near : new BlockPos(x, y, z);
	}

	private static Integer findGroundY(ClientLevel level, int x, int y, int z, Predicate<BlockState> test) {
		for (int cy = y, depth = 0; cy > level.getMinY() && depth < 10; cy--, depth++) {
			if (test.test(level.getBlockState(new BlockPos(x, cy, z)))) return cy;
		}
		return null;
	}

	private static BlockPos findNearby(ClientLevel level, int x, int y, int z, Predicate<BlockState> test) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos origin = new BlockPos(x, y, z);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				Integer gy = findGroundY(level, x + dx, y, z + dz, test);
				if (gy == null) continue;
				BlockPos candidate = new BlockPos(x + dx, gy, z + dz);
				double d = candidate.distSqr(origin);
				if (d < bestDist) { bestDist = d; best = candidate; }
			}
		}
		return best;
	}
}
