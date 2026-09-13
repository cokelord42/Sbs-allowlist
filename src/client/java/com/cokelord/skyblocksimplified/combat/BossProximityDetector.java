package com.cokelord.skyblocksimplified.combat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * "Is a known slayer/dungeon boss entity within range of the player right now" — used to scope Hide
 * Damage Splashes down to just boss fights instead of every splash on the island. Name lists are the
 * same confirmed boss names already used by this project's own slayer-boss MobHighlightFeature
 * registrations and DungeonChatFilter's boss-message matching, kept here instead of re-declared per
 * caller.
 *
 * <p>Perf: {@link HideDamageSplashesFeature}'s EntityHideRegistry rule calls into here once PER DAMAGE-
 * SPLASH ENTITY, every render frame (that's how vanilla's own shouldRender hook works) — in an active
 * fight with several splashes on screen at once, that used to mean several independent 20-block entity
 * scans doing the same name-list check, every single frame. The answer to "is a boss nearby right now"
 * is identical for every splash checked within the same tick, so this is now computed once per tick
 * (entity positions only actually change that often anyway) and cached; the public methods below just
 * read the cache instead of re-scanning on every call. */
public final class BossProximityDetector {
	private static final double RANGE = 20.0;

	private static final List<String> SLAYER_BOSS_NAMES = List.of(
		"Tarantula Broodfather", "Voidgloom Seraph", "Inferno Demonlord", "Bloodfiend", "Revenant Horror", "Sven Packmaster");

	private static final List<String> DUNGEON_BOSS_NAMES = List.of(
		"The Watcher", "Bonzo", "Scarf", "Professor", "Livid", "Enderman", "Thorn", "Sadan", "Maxor", "Storm", "Goldor", "Necron", "Wither King");

	private static boolean registered = false;
	private static boolean nearSlayerBoss = false;
	private static boolean nearDungeonBoss = false;

	private BossProximityDetector() {}

	public static void register() {
		if (registered) return;
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			nearSlayerBoss = false;
			nearDungeonBoss = false;
			return;
		}
		boolean slayer = false;
		boolean dungeon = false;
		AABB area = mc.player.getBoundingBox().inflate(RANGE);
		for (Entity entity : mc.level.getEntities(mc.player, area, e -> true)) {
			String name = entity.getName().getString();
			if (!slayer) {
				for (String bossName : SLAYER_BOSS_NAMES) {
					if (name.contains(bossName)) { slayer = true; break; }
				}
			}
			if (!dungeon) {
				for (String bossName : DUNGEON_BOSS_NAMES) {
					if (name.contains(bossName)) { dungeon = true; break; }
				}
			}
			if (slayer && dungeon) break;
		}
		nearSlayerBoss = slayer;
		nearDungeonBoss = dungeon;
	}

	public static boolean isNearSlayerBoss() {
		return nearSlayerBoss;
	}

	public static boolean isNearDungeonBoss() {
		return nearDungeonBoss;
	}
}
