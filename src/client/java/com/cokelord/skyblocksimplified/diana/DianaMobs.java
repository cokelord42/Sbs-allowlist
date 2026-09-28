package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.CocoonNotifierFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaColorsFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.MythosMobHpFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.NoShurikenFeature;
import com.cokelord.skyblocksimplified.util.ChatText;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the name-tag armor stands of Diana mobs (ported from SBO's DianaMobDetect.kt): a stand counts as a
 * Diana mob once its name shows the mythological mob-type glyph (U+E07E, dark green) or King Minos' "N Hits"
 * phase. From those it derives the Mob HP lines, the No Shuriken flag (a rare mob without the "✯" Extremely
 * Sharp mark), and death events (health 0) for lootshare counting.
 */
public final class DianaMobs {
	private static final String MYTHO_GLYPH = "§2";
	private static final Pattern HEALTH = Pattern.compile("([0-9]+(?:\\.[0-9]+)?[MK]?)§f/");
	private static final Pattern KING_HITS = Pattern.compile(".*?(\\d+)\\s+Hits.*");
	private static final Pattern COCOON = Pattern.compile("^§a§lCAUGHT!.*?You cocooned a (?<name>.+?)!.*$");
	private static final List<String> PREFIXES = List.of("Empyrean", "Exalted", "Runic", "Venerable", "Stalwart", "Blessed");
	public static final List<String> RARE_MOBS = List.of("Minos Inquisitor", "King Minos", "Sphinx", "Manticore");

	private static final Map<Integer, Long> unconfirmed = new HashMap<>();
	private static final Map<Integer, ArmorStand> pending = new HashMap<>();
	private static final Map<Integer, ArmorStand> tracked = new HashMap<>();
	private static final Set<Integer> defeated = new HashSet<>();

	private static List<String> hpLines = List.of();
	private static boolean noShuriken = false;
	private static boolean registered = false;

	private DianaMobs() {}

	public static List<String> hpLines() { return hpLines; }
	public static boolean noShuriken() { return noShuriken; }

	static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ArmorStand stand) {
				unconfirmed.put(stand.getId(), System.currentTimeMillis());
				pending.put(stand.getId(), stand);
			}
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (!(entity instanceof ArmorStand)) return;
			unconfirmed.remove(entity.getId());
			pending.remove(entity.getId());
			tracked.remove(entity.getId());
			defeated.remove(entity.getId());
		});
	}

	public static String rareMobIn(String name) {
		for (String rare : RARE_MOBS) if (name.contains(rare)) return rare;
		return null;
	}

	private static String nameOf(ArmorStand stand) {
		Component custom = stand.getCustomName();
		return ChatText.legacy(custom != null ? custom : stand.getName());
	}

	private static boolean isKingHitPhase(String name) {
		return name.contains("Hits") && KING_HITS.matcher(name).matches();
	}

	private static Double parseHealth(String name) {
		Matcher m = HEALTH.matcher(name);
		if (!m.find()) return null;
		String raw = m.group(1);
		try {
			if (raw.endsWith("M")) return Double.parseDouble(raw.substring(0, raw.length() - 1)) * 1_000_000;
			if (raw.endsWith("K")) return Double.parseDouble(raw.substring(0, raw.length() - 1)) * 1_000;
			return Double.parseDouble(raw);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) return;
		boolean inHub = DianaState.inHub();
		long now = System.currentTimeMillis();

		// Stands get their name a tick or two after spawning: confirm within 1s, else forget them.
		Iterator<Map.Entry<Integer, Long>> it = unconfirmed.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Long> e = it.next();
			ArmorStand stand = pending.get(e.getKey());
			if (stand == null || !stand.isAlive() || stand.level() != mc.level || !inHub) {
				it.remove();
				pending.remove(e.getKey());
				continue;
			}
			String name = nameOf(stand);
			if (name.contains(MYTHO_GLYPH) || isKingHitPhase(name)) {
				tracked.put(e.getKey(), stand);
				it.remove();
				pending.remove(e.getKey());
			} else if (now - e.getValue() > 1000) {
				it.remove();
				pending.remove(e.getKey());
			}
		}

		MythosMobHpFeature hpFeature = MythosMobHpFeature.get();
		boolean wantHp = hpFeature != null && hpFeature.isEnabled();
		double hpRangeSq = wantHp ? Math.pow(hpFeature.integer("range"), 2) : 0;
		List<String> lines = wantHp ? new ArrayList<>() : List.of();
		boolean starless = false;

		Iterator<Map.Entry<Integer, ArmorStand>> tit = tracked.entrySet().iterator();
		while (tit.hasNext()) {
			Map.Entry<Integer, ArmorStand> e = tit.next();
			int id = e.getKey();
			ArmorStand stand = e.getValue();
			if (!stand.isAlive() || stand.level() != mc.level) {
				tit.remove();
				defeated.remove(id);
				continue;
			}
			String name = nameOf(stand);
			if (name.isEmpty() || name.equals("Armor Stand")) continue;
			double distSq = stand.distanceToSqr(mc.player);

			Matcher hits = KING_HITS.matcher(name);
			if (name.contains("Hits") && hits.matches()) {
				if (wantHp && distSq <= hpRangeSq) lines.add("§6King Minos §7- §5" + hits.group(1) + " Hits");
				continue;
			}
			if (!name.contains(MYTHO_GLYPH)) continue;

			Double health = parseHealth(name);
			if (health != null && health <= 0 && defeated.add(id)) {
				DianaTracker.onDianaMobDeath(ChatText.strip(name), distSq <= 900);
				DianaRoll.onRareMobDeath(ChatText.strip(name), distSq <= 900);
			}
			if (wantHp && distSq <= hpRangeSq) lines.add(name.replace(MYTHO_GLYPH, "").trim());

			String rare = rareMobIn(name);
			if (rare != null && !defeated.contains(id) && !name.contains("✯") && (health == null || health > 0)
				&& NoShurikenFeature.checks(rare)) {
				starless = true;
			}
		}
		hpLines = lines;
		noShuriken = starless;
	}

	// ---- cocoon -------------------------------------------------------------------------------------------

	static void onChat(String legacy) {
		Matcher m = COCOON.matcher(legacy);
		if (!m.matches()) return;
		String clean = ChatText.strip(m.group("name")).trim();
		String rare = rareMobIn(clean);
		String display = rare != null ? rare : removePrefix(clean);
		boolean dianaMob = rare != null || !display.equals(clean);
		// A cocooned mob you spawned yourself counts as a fresh spawn (SBO: mob tracking + since stats).
		if (dianaMob && display.equals(DianaTracker.lastSpawnedMob)) DianaTracker.onMobSpawn(display, true);
		if (rare == null) return;
		CocoonNotifierFeature feature = CocoonNotifierFeature.get();
		if (feature == null || !feature.isEnabled()) return;
		if (feature.bool("party")) ChatText.partyChat("Cocooned a " + rare + "!");
		if (feature.bool("title")) {
			int rgb = DianaColorsFeature.color("titleCocoon", 0x55FFFF) & 0xFFFFFF;
			Minecraft mc = Minecraft.getInstance();
			mc.gui.hud.setTimes(10, 40, 10);
			mc.gui.hud.setSubtitle(Component.literal(rare).withColor(rgb));
			mc.gui.hud.setTitle(Component.literal("COCOON!").withColor(rgb).withStyle(net.minecraft.ChatFormatting.BOLD));
			if (mc.player != null) mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1.2f);
		}
	}

	private static String removePrefix(String name) {
		for (String prefix : PREFIXES) if (name.startsWith(prefix + " ")) return name.substring(prefix.length() + 1);
		return name;
	}
}
