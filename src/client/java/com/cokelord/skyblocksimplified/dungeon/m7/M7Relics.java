package com.cokelord.skyblocksimplified.dungeon.m7;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.impl.SplitsFeature;
import com.cokelord.skyblocksimplified.util.ServerClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M7 phase 5 King relics. Tracks which relic the local player carries (hotbar, 9th slot first — per user:
 * "Corrupted Red/Purple/Orange/Green/Blue Relic"), whether it was picked up / placed this run (Boss Guide's
 * Relic steps), and the relic spawn countdown: per user, relics spawn 2.1s after the "Cleared" split
 * (Necron's "All this, for nothing..."; Odin's King Relic uses the same line with a 38-tick default).
 * Altar coordinates are the user's (two blocks tall, y 6 and 7; they match Odin's cauldron positions at y 7).
 */
public final class M7Relics {
	private M7Relics() {}

	public enum Relic {
		PURPLE("Purple", 54, 41, 0xFFAA00AA),
		RED("Red", 51, 42, 0xFFFF5555),
		GREEN("Green", 49, 44, 0xFF55FF55),
		ORANGE("Orange", 57, 42, 0xFFFFAA00),
		BLUE("Blue", 59, 44, 0xFF55FFFF);

		public final String display;
		public final BlockPos altarLow;
		public final int color;
		/** Both altar blocks (y 6 and 7) as one box. */
		public final AABB altarBox;

		Relic(String display, int x, int z, int color) {
			this.display = display;
			this.altarLow = new BlockPos(x, 6, z);
			this.color = color;
			this.altarBox = new AABB(x, 6, z, x + 1, 8, z + 1);
		}
	}

	/** Per user: relic spawns 2.1s after the "Cleared" split starts. */
	public static final long RELIC_SPAWN_MILLIS = 2_100L;

	private static final Pattern RELIC_NAME = Pattern.compile("Corrupted (Red|Purple|Orange|Green|Blue) Relic");

	private static Relic held = null;
	private static boolean pickedUpThisRun = false;
	private static int lastRunId = Integer.MIN_VALUE;
	private static boolean registered = false;

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(M7Relics::tick);
	}

	private static void tick(Minecraft mc) {
		int runId = DungeonState.getRunId();
		if (runId != lastRunId) { lastRunId = runId; held = null; pickedUpThisRun = false; }
		if (mc.player == null || !inM7Boss()) { held = null; return; }
		held = findHeld(mc);
		if (held != null) pickedUpThisRun = true;
	}

	private static Relic findHeld(Minecraft mc) {
		var inv = mc.player.getInventory();
		Relic r = relicOf(inv.getItem(8));
		if (r != null) return r;
		for (int i = 0; i < 8; i++) {
			r = relicOf(inv.getItem(i));
			if (r != null) return r;
		}
		return null;
	}

	private static Relic relicOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		Matcher m = RELIC_NAME.matcher(stack.getHoverName().getString().replaceAll("§.", ""));
		if (!m.find()) return null;
		return switch (m.group(1)) {
			case "Red" -> Relic.RED;
			case "Purple" -> Relic.PURPLE;
			case "Orange" -> Relic.ORANGE;
			case "Green" -> Relic.GREEN;
			default -> Relic.BLUE;
		};
	}

	/** Floor 7 boss, excluding a known normal-mode (F7) label — relics only exist in Master Mode. */
	public static boolean inM7Boss() {
		if (!DungeonState.isFloor(7) || !DungeonState.isInBoss()) return false;
		String label = DungeonState.getFloorLabel();
		return label == null || !label.toUpperCase(java.util.Locale.ROOT).startsWith("F");
	}

	/** The relic in the local player's hotbar right now, or null (none / placed). */
	public static Relic heldRelic() { return held; }

	/** A relic has been in the hotbar at some point this run. */
	public static boolean pickedUpThisRun() { return pickedUpThisRun; }

	/** Picked up this run and no longer in the hotbar. */
	public static boolean placedThisRun() { return pickedUpThisRun && held == null; }

	/** Milliseconds (server-tick time) until relics spawn, or -1 when the countdown isn't running. */
	public static long spawnRemainingMillis() {
		if (!inM7Boss()) return -1;
		Long since = SplitsFeature.millisSinceSplit("Cleared");
		if (since == null) return -1;
		long elapsed = ServerClock.elapsedMillis(since);
		return elapsed >= RELIC_SPAWN_MILLIS ? -1 : RELIC_SPAWN_MILLIS - elapsed;
	}
}
