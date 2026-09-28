package com.cokelord.skyblocksimplified.dungeon.m7;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.particle.ParticlePacketObserverRegistry;
import com.cokelord.skyblocksimplified.util.ChatText;
import com.cokelord.skyblocksimplified.util.ServerClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M7 (P5) Wither Dragons state machine — a direct port of Odin's WitherDragons / WitherDragonsEnum /
 * DragonCheck / DragonPriority (odtheking/Odin, BSD-3). Untested in a real M7 run: the positions, particle
 * signature, timings and priority rules are Odin's own, copied as-is.
 *
 * <p>Adaptations: Odin counts "server ticks" off its transaction-packet TickEvent.Server; this uses
 * {@link ServerClock}'s tick counter (same server-tick basis). Odin's packet events (entity data, equipment)
 * are read by polling the live entities once per tick instead. Odin's "Paul Buff" toggle is replaced by
 * detecting Paul's "Benediction" perk (blessings 25% stronger) from the election API.
 */
public final class M7Dragons {
	private M7Dragons() {}

	public enum State { SPAWNING, ALIVE, DEAD }

	public enum Dragon {
		RED(new BlockPos(27, 14, 59), new BlockPos(32, 22, 59), new AABB(14.5, 13.0, 45.5, 39.5, 28.0, 70.5), 'c', 0xFFFF5555, 24, 30, 56, 62),
		ORANGE(new BlockPos(85, 14, 56), new BlockPos(80, 23, 56), new AABB(72.0, 8.0, 47.0, 102.0, 28.0, 77.0), '6', 0xFFFFAA00, 82, 88, 53, 59),
		GREEN(new BlockPos(27, 14, 94), new BlockPos(32, 23, 94), new AABB(7.0, 8.0, 80.0, 37.0, 28.0, 110.0), 'a', 0xFF55FF55, 23, 29, 91, 97),
		BLUE(new BlockPos(84, 14, 94), new BlockPos(79, 23, 94), new AABB(71.5, 13.0, 82.5, 96.5, 26.0, 107.5), 'b', 0xFF55FFFF, 82, 88, 91, 97),
		PURPLE(new BlockPos(56, 14, 125), new BlockPos(56, 22, 120), new AABB(45.5, 13.0, 113.5, 68.5, 23.0, 136.5), '5', 0xFFAA00AA, 53, 59, 122, 128);

		public final BlockPos spawnPos, statuePos;
		public final AABB box;
		public final char colorCode;
		public final int color;
		final double xMin, xMax, zMin, zMax;
		public int timeToSpawn = 100;
		public State state = State.DEAD;
		public int timesSpawned = 0;
		public UUID entityUuid = null;
		public boolean sprayed = false;
		public long spawnedTick = 0;

		Dragon(BlockPos spawnPos, BlockPos statuePos, AABB box, char colorCode, int color, double xMin, double xMax, double zMin, double zMax) {
			this.spawnPos = spawnPos; this.statuePos = statuePos; this.box = box; this.colorCode = colorCode; this.color = color;
			this.xMin = xMin; this.xMax = xMax; this.zMin = zMin; this.zMax = zMax;
		}

		public String display() { return name().charAt(0) + name().substring(1).toLowerCase(); }

		void setAlive(UUID uuid) {
			if (uuid != null) entityUuid = uuid;
			if (state != State.SPAWNING) return;
			state = State.ALIVE;
			timesSpawned++;
			spawnedTick = currentTick;
			sprayed = false;
			if (settings.enabled() && settings.sendSpawned()) ChatText.clientMessage(PREFIX + "§" + colorCode + display() + " §fdragon spawned §8(§7" + timesSpawned + "§8)");
		}

		void setDead(boolean realTime) {
			if (state != State.DEAD) deathsThisRun++;
			state = State.DEAD;
			entityUuid = null;
			lastDragonDeath = this;
			if (priorityDragon == this) priorityDragon = null;
			if (settings.enabled() && settings.sendTime() && realTime) {
				ChatText.clientMessage(PREFIX + "§" + colorCode + display() + " §7was alive for §6"
					+ String.format(java.util.Locale.ROOT, "%.2f", (currentTick - spawnedTick) / 20f) + "s§7!");
			}
		}
	}

	/** What the feature modules configure (implemented by DragonsFeature / DragonPriorityFeature). */
	public interface Settings {
		boolean enabled();
		boolean dragonTitle();
		boolean sendSpawned();
		boolean sendTime();
		boolean sendSpray();
		boolean sendCounts();
		boolean priorityEnabled();
		int normalPower();
		int easyPower();
		boolean healerSoloDebuff(); // false = Tank solo debuffs purple (Odin's default)
		boolean soloDebuffOnAll();
	}

	private static final String PREFIX = "§b[SBS] ";
	private static final Settings DISABLED = new Settings() {
		public boolean enabled() { return false; }
		public boolean dragonTitle() { return false; }
		public boolean sendSpawned() { return false; }
		public boolean sendTime() { return false; }
		public boolean sendSpray() { return false; }
		public boolean sendCounts() { return false; }
		public boolean priorityEnabled() { return false; }
		public int normalPower() { return 0; }
		public int easyPower() { return 0; }
		public boolean healerSoloDebuff() { return false; }
		public boolean soloDebuffOnAll() { return false; }
	};
	static Settings settings = DISABLED;

	public static Dragon priorityDragon = null;
	static Dragon lastDragonDeath = null;
	static long currentTick = 0;
	private static double lastServerTick = Double.NaN;
	/** Live dragon health by entity id (Odin: dragonHealthMap). */
	public static final Map<Integer, EnderDragon> dragons = new HashMap<>();

	private static final Pattern WITHER_KING = Pattern.compile("^\\[BOSS] Wither King: (Oh, this one hurts!|I have more of those\\.|My soul is disposable\\.)$");
	private static boolean registered = false;

	public static void setSettings(Settings s) { settings = s != null ? s : DISABLED; }

	/** Set by features that need dragon spawn/death state without the Dragons module (Boss Guide's Dragon
	 *  dead step). Detection runs; the Dragons module's own messages/titles stay off unless it's enabled. */
	public static volatile boolean externalTracking = false;
	private static int deathsThisRun = 0;

	private static boolean tracking() { return settings.enabled() || externalTracking; }

	/** Dragons that died (or counted) this run — Boss Guide's Dragon dead steps compare against this. */
	public static int deathsThisRun() { return deathsThisRun; }

	/** Odin's M7Phases.P5: floor 7 boss and the player below y=45 (the dragon arena). */
	public static boolean inP5() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && DungeonState.isFloor(7) && DungeonState.isInBoss() && mc.player.getY() <= 45;
	}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		DungeonBlessings.register(); // priority needs the Power/Time blessing levels even without Blessing Display

		ParticlePacketObserverRegistry.register(event -> {
			if (tracking() && inP5()) handleSpawnParticle(event);
		});

		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!tracking() || !(entity instanceof EnderDragon) || !inP5()) return;
			for (Dragon d : Dragon.values()) {
				if (d.box.contains(entity.position())) { d.setAlive(entity.getUUID()); break; }
			}
		});

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay || !tracking() || !inP5()) return true;
			if (!WITHER_KING.matcher(message.getString().replaceAll("§.", "")).matches()) return true;
			Dragon d = lastDragonDeath;
			if (d == null) for (Dragon x : Dragon.values()) if (x.state != State.DEAD) { d = x; break; }
			if (d != null) {
				if (settings.enabled() && settings.sendCounts()) ChatText.clientMessage(PREFIX + "§" + d.colorCode + d.display() + " dragon counts.");
				if (d.state != State.DEAD) d.setDead(false);
				lastDragonDeath = null;
			}
			return true;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
		ClientPlayConnectionEvents.JOIN.register((h, s, c) -> reset());
	}

	private static int lastRunId = Integer.MIN_VALUE;

	private static void tick(Minecraft mc) {
		// Odin resets on every world load; a new dungeon run is our equivalent.
		int runId = DungeonState.getRunId();
		if (runId != lastRunId) { lastRunId = runId; reset(); }
		// Server ticks since the last client tick (Odin decrements per server tick, so lag slows the timers).
		double now = ServerClock.tickNow();
		int serverTicks = 1;
		if (!Double.isNaN(now)) {
			serverTicks = Double.isNaN(lastServerTick) ? 1 : (int) Math.max(0, Math.min(20, Math.floor(now) - Math.floor(lastServerTick)));
			lastServerTick = now;
		}
		for (int i = 0; i < serverTicks; i++) {
			for (Dragon d : Dragon.values()) {
				if (d.timeToSpawn > 0) d.timeToSpawn--;
				else if (d.state == State.SPAWNING) d.setAlive(null);
			}
			currentTick++;
		}

		if (!tracking() || mc.level == null || mc.player == null || !inP5()) {
			if (!dragons.isEmpty()) dragons.clear();
			return;
		}

		// Health (Odin reads entity data id 9) + death, and ice spray (packed ice on a nearby armor stand).
		dragons.clear();
		AABB arena = mc.player.getBoundingBox().inflate(160);
		for (EnderDragon dragon : mc.level.getEntitiesOfClass(EnderDragon.class, arena)) {
			dragons.put(dragon.getId(), dragon);
			for (Dragon d : Dragon.values()) {
				if (!dragon.getUUID().equals(d.entityUuid)) continue;
				if (dragon.getHealth() <= 0 && d.state != State.DEAD) d.setDead(true);
				if (d.state == State.ALIVE && !d.sprayed && settings.enabled() && settings.sendSpray() && sprayedNear(mc, dragon)) {
					long ticks = currentTick - d.spawnedTick;
					ChatText.clientMessage(PREFIX + "§" + d.colorCode + d.display() + " §fdragon was sprayed in §c" + ticks + " §ftick" + (ticks > 1 ? "s" : "") + ".");
					d.sprayed = true;
				}
			}
		}
	}

	private static boolean sprayedNear(Minecraft mc, EnderDragon dragon) {
		for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, dragon.getBoundingBox().inflate(8))) {
			if (stand.distanceTo(dragon) > 8) continue;
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				if (stand.getItemBySlot(slot).is(Items.PACKED_ICE)) return true;
			}
		}
		return false;
	}

	/** ClientLevelBlockUpdateMixin: a dragon's statue block turning to air means that dragon died. */
	public static void onBlockChange(BlockPos pos, BlockState state) {
		if (!tracking() || !state.isAir() || !inP5()) return;
		for (Dragon d : Dragon.values()) if (d.statuePos.equals(pos)) d.setDead(false);
	}

	private static void handleSpawnParticle(com.cokelord.skyblocksimplified.particle.ParticlePacketEvent p) {
		if (p.count() != 20 || p.location().y != 19.0 || p.type().getType() != ParticleTypes.FLAME
			|| p.offset().x != 2.0 || p.offset().y != 3.0 || p.offset().z != 2.0 || p.maxSpeed() != 0f
			|| p.location().x % 1 != 0.0 || p.location().z % 1 != 0.0) return;

		int spawned = 0;
		List<Dragon> spawning = new ArrayList<>();
		for (Dragon d : Dragon.values()) {
			spawned += d.timesSpawned;
			if (d.state == State.SPAWNING) {
				if (!spawning.contains(d)) spawning.add(d);
				continue;
			}
			double x = p.location().x, z = p.location().z;
			if (x < d.xMin || x > d.xMax || z < d.zMin || z > d.zMax) continue;
			d.state = State.SPAWNING;
			d.timeToSpawn = 100;
			spawning.add(d);
		}

		if (!spawning.isEmpty() && (spawning.size() == 2 || spawned >= 2) && priorityDragon == null) {
			Dragon dragon = findPriority(spawning);
			priorityDragon = dragon;
			if (settings.enabled() && settings.dragonTitle()) {
				Minecraft mc = Minecraft.getInstance();
				ChatText.title("§" + dragon.colorCode + dragon.display() + " is spawning!", null, 5, 30, 5);
				if (mc.player != null) mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f);
			}
			if (settings.enabled() && settings.priorityEnabled() && spawning.size() > 1) {
				StringBuilder sb = new StringBuilder(PREFIX);
				for (int i = 0; i < spawning.size(); i++) {
					if (i > 0) sb.append("§r, ");
					sb.append('§').append(spawning.get(i).colorCode).append(spawning.get(i).display());
				}
				sb.append("§r -> §").append(dragon.colorCode).append(dragon.display()).append(" §7is your priority dragon!");
				ChatText.clientMessage(sb.toString());
			}
		}
	}

	// ---- DragonPriority (Odin) ----
	private static final List<Dragon> DEFAULT_ORDER = List.of(Dragon.RED, Dragon.ORANGE, Dragon.BLUE, Dragon.PURPLE, Dragon.GREEN);
	private static final List<Dragon> DRAGON_LIST = List.of(Dragon.ORANGE, Dragon.GREEN, Dragon.RED, Dragon.BLUE, Dragon.PURPLE);

	/** Paul's "Benediction" perk (mayor or minister) makes blessings 25% stronger — replaces Odin's manual toggle. */
	public static boolean paulBlessingsActive() {
		return com.cokelord.skyblocksimplified.api.HypixelElectionApi.hasPerk("Benediction");
	}

	public static double totalPower() {
		return DungeonBlessings.Blessing.POWER.current() * (paulBlessingsActive() ? 1.25 : 1.0)
			+ (DungeonBlessings.Blessing.TIME.current() > 0 ? 2.5 : 0.0);
	}

	static Dragon findPriority(List<Dragon> spawning) {
		List<Dragon> list = new ArrayList<>(spawning);
		if (!settings.priorityEnabled()) {
			list.sort(Comparator.comparingInt(DEFAULT_ORDER::indexOf));
			return list.get(0);
		}
		double power = totalPower();
		DungeonClass clazz = selfClass();
		if (clazz == DungeonClass.EMPTY) ChatText.clientMessage(PREFIX + "§cFailed to get dungeon class.");
		boolean hasPurple = list.contains(Dragon.PURPLE);
		List<Dragon> priority;
		if (power >= settings.normalPower() || (hasPurple && power >= settings.easyPower())) {
			priority = (clazz == DungeonClass.BERSERK || clazz == DungeonClass.MAGE) ? DRAGON_LIST : reversed(DRAGON_LIST);
		} else {
			priority = DEFAULT_ORDER;
		}
		list.sort(Comparator.comparingInt(priority::indexOf));
		if (power >= settings.easyPower()) {
			boolean appliesTo = hasPurple || settings.soloDebuffOnAll();
			if ((settings.healerSoloDebuff() && clazz == DungeonClass.TANK && appliesTo)
				|| (clazz == DungeonClass.HEALER && appliesTo)) {
				list.sort(Comparator.comparingInt((Dragon d) -> priority.indexOf(d)).reversed());
			}
		}
		return list.get(0);
	}

	private static List<Dragon> reversed(List<Dragon> in) {
		List<Dragon> out = new ArrayList<>(in);
		java.util.Collections.reverse(out);
		return out;
	}

	private static DungeonClass selfClass() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return DungeonClass.EMPTY;
		String self = mc.player.getName().getString();
		for (DungeonPlayer p : DungeonState.getTeammates()) if (p.name.equals(self) && p.clazz != null) return p.clazz;
		return DungeonClass.EMPTY;
	}

	public static void reset() {
		for (Dragon d : Dragon.values()) {
			d.timeToSpawn = 0;
			d.timesSpawned = 0;
			d.state = State.DEAD;
			d.entityUuid = null;
			d.sprayed = false;
			d.spawnedTick = 0;
		}
		priorityDragon = null;
		lastDragonDeath = null;
		dragons.clear();
		deathsThisRun = 0;
	}

	/** Odin's timer colors: red <= 1s, yellow <= 3s, else green. style: 0 ms, 1 seconds, 2 ticks. */
	public static String timerText(int ticks, int style, boolean symbol) {
		String color = ticks <= 20 ? "§c" : ticks <= 60 ? "§e" : "§a";
		return color + switch (style) {
			case 1 -> String.format(java.util.Locale.ROOT, "%.1f", ticks / 20f) + (symbol ? "s" : "");
			case 2 -> ticks + (symbol ? "t" : "");
			default -> (ticks * 50) + (symbol ? "ms" : "");
		};
	}

	public static String healthText(float health) {
		String color = health >= 750_000_000 ? "§a" : health >= 500_000_000 ? "§e" : health >= 250_000_000 ? "§6" : "§c";
		String v;
		if (health >= 1_000_000_000) v = String.format(java.util.Locale.ROOT, "%.1fb", health / 1_000_000_000f);
		else if (health >= 1_000_000) v = String.format(java.util.Locale.ROOT, "%.1fm", health / 1_000_000f);
		else if (health >= 1_000) v = String.format(java.util.Locale.ROOT, "%.1fk", health / 1_000f);
		else v = String.valueOf((int) health);
		return color + v;
	}

	public static Vec3 center(BlockPos pos) { return Vec3.atCenterOf(pos); }
}
