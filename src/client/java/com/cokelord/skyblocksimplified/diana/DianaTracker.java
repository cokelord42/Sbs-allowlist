package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.DianaStatsFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.RareMobShareFeature;
import com.cokelord.skyblocksimplified.hud.ScoreboardReader;
import com.cokelord.skyblocksimplified.util.ChatText;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diana loot/mob tracking, ported from SBO's DianaTracker.kt + Helper's lootshare/mob-death timing +
 * Pickuplog's inventory/sack/purse diffs. Same chat patterns (matched against the § formatted text), same
 * item keys, same "since" bookkeeping and b2b messages.
 *
 * <p>How lootshare drops are counted (the user asked to "figure out how SBO does it"): Hypixel still sends
 * the "RARE DROP!" chat line for a lootshared drop, so SBO counts every rare drop from chat and files it under
 * the item's {@code _LS} key when a "LOOT SHARE" message arrived in the last 2 seconds. Chat alone misses
 * the drops Hypixel sends no line for — Crown of Greed and Hilt of Revelations (unique items, caught when
 * they appear in the inventory with a creation timestamp under 6 seconds old) and the stackable
 * claws/Enchanted Gold (counted from inventory or sack gains within 3-4 seconds of a nearby Diana mob dying
 * or a lootshare) — so those come from inventory and sack diffs instead.
 */
public final class DianaTracker {
	private static final Pattern MOB_DUG = Pattern.compile("(.*?) §eYou dug (.*?)§2(.*?)§e!(.*?)$", Pattern.DOTALL);
	private static final Pattern COINS = Pattern.compile("^§6§lWow! §eYou dug out §6(.*?) coins§e!$", Pattern.DOTALL);
	private static final Pattern TREASURE = Pattern.compile("^§6§lRARE DROP! §eYou dug out a (.*?)§e!$", Pattern.DOTALL);
	private static final Pattern RNG_DROP = Pattern.compile("^§6§lRARE DROP! (.*?)$", Pattern.DOTALL);
	private static final Pattern DYE = Pattern.compile("^§d§lWOW! (.*?) §6found a §2Mythological Dye.*$", Pattern.DOTALL);
	private static final Pattern BURROW = Pattern.compile("^§eYou (.*?) Griffin [Bb]urrow(.*?)$", Pattern.DOTALL);
	private static final Pattern MYTH_FISH = Pattern.compile("^(.*?) §eYou just dug out(.*?)$");
	private static final Pattern LOOT_SHARE = Pattern.compile("^§e§lLOOT SHARE §fYou received loot for assisting (.*?)$", Pattern.DOTALL);
	private static final Pattern SHARD_CHARM_MULTI = Pattern.compile("^(.*?) You charmed a (.*?) and captured (.*?) Shards §7from it.$", Pattern.DOTALL);
	private static final Pattern SHARD_CHARM_ONE = Pattern.compile("^(.*?) You charmed a (.*?) and captured its §9Shard§7.$", Pattern.DOTALL);
	private static final Pattern SHARD_CAUGHT_MULTI = Pattern.compile("^§aYou caught (.*?) (.*?) §aShards(.*?)$", Pattern.DOTALL);
	private static final Pattern SHARD_CAUGHT_ONE = Pattern.compile("^§aYou caught a (.*?) §aShard!$", Pattern.DOTALL);
	private static final Pattern MAGIC_FIND = Pattern.compile("§b\\(\\+§b(\\d+)");
	private static final Pattern SACK_ENTRY = Pattern.compile("\\+([\\d,]+) ([^(]+)");

	private static final List<String> STACKABLE_DROPS = List.of("ENCHANTED_ANCIENT_CLAW", "ANCIENT_CLAW", "ENCHANTED_GOLD");
	private static final Map<String, String> SACK_DROPS = Map.of(
		"Enchanted Gold", "ENCHANTED_GOLD", "Ancient Claw", "ANCIENT_CLAW", "Enchanted Ancient Claw", "ENCHANTED_ANCIENT_CLAW");

	public static String lastSpawnedMob;
	public static long lastSpawnedMobAt;
	private static long lastLootShareAt;
	private static long lastDianaMobDeathAt;
	private static long scavengerBlockedUntil;
	private static final Map<String, Long> cooldowns = new HashMap<>();
	private static final Map<String, Long> lsCooldowns = new HashMap<>();

	private static Map<String, Integer> lastInventory;
	private static long lastPurse = -1;

	private DianaTracker() {}

	private static boolean onCooldown(Map<String, Long> map, String key, long ms) {
		long now = System.currentTimeMillis();
		Long until = map.get(key);
		if (until != null && now < until) return true;
		map.put(key, now + ms);
		return false;
	}

	static boolean lootShareRecently(long ms) { return System.currentTimeMillis() - lastLootShareAt <= ms; }
	static boolean dianaMobDiedRecently(long ms) { return System.currentTimeMillis() - lastDianaMobDeathAt <= ms; }

	private static void since(String message) {
		if (DianaStatsFeature.sinceMessages()) ChatText.clientMessage("§b[SBS] " + message);
	}

	// ---- chat ---------------------------------------------------------------------------------------------

	static void onChat(String legacy) {
		Matcher m;
		if (LOOT_SHARE.matcher(legacy).find()) {
			lastLootShareAt = System.currentTimeMillis();
			return;
		}
		if ((m = BURROW.matcher(legacy)).find()) {
			DianaData.add("TOTAL_BURROWS", 1);
		}
		if ((m = MOB_DUG.matcher(legacy)).find()) {
			String mob = ChatText.strip(m.group(3)).trim();
			if (!onCooldown(cooldowns, "mob:" + mob, 500)) onMobSpawn(mob, false);
			return;
		}
		if ((m = COINS.matcher(legacy)).find()) {
			scavengerBlockedUntil = System.currentTimeMillis() + 1000;
			try {
				DianaData.add("COINS", Integer.parseInt(ChatText.strip(m.group(1)).replace(",", "")));
			} catch (NumberFormatException ignored) {
			}
			return;
		}
		if ((m = TREASURE.matcher(legacy)).find()) {
			String drop = ChatText.strip(m.group(1)).trim();
			switch (drop) {
				case "Griffin Feather" -> DianaData.add("GRIFFIN_FEATHER", 1);
				case "Mythos Fragment" -> DianaData.add("MYTHOS_FRAGMENT", 1);
				case "Braided Griffin Feather" -> onRareDrop("BRAIDED_GRIFFIN_FEATHER", false, true);
				default -> {}
			}
			return;
		}
		if ((m = RNG_DROP.matcher(legacy)).find()) {
			onRngDrop(m.group(1));
			return;
		}
		if ((m = DYE.matcher(legacy)).find()) {
			if (isSelf(ChatText.strip(m.group(1)))) onRareDrop("MYTHOLOGICAL_DYE", false, true);
			return;
		}
		if ((m = MYTH_FISH.matcher(legacy)).find() && m.group(2).contains("Myth the Fish")) {
			onRareDrop("MYTH_THE_FISH", false, true);
			return;
		}
		trackShards(legacy);
	}

	private static boolean isSelf(String name) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && name.toLowerCase(Locale.ROOT).contains(mc.player.getGameProfile().name().toLowerCase(Locale.ROOT));
	}

	/** Sacks message ("[Sacks] +N items") — the per-item gains are only in the hover text. */
	static void onSacksMessage(Component message) {
		if (!dianaMobDiedRecently(4000)) return;
		for (Component part : message.getSiblings()) {
			if (!part.getString().contains(" item")) continue;
			if (!(part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText show)) continue;
			Matcher m = SACK_ENTRY.matcher(show.value().getString());
			while (m.find()) {
				String item = m.group(2).replace("Ingot", "").trim();
				String key = SACK_DROPS.get(item);
				if (key == null) continue;
				try {
					DianaData.add(key, Integer.parseInt(m.group(1).replace(",", "")));
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	private static void trackShards(String legacy) {
		Matcher m;
		String shard = null;
		int amount = 0;
		if ((m = SHARD_CHARM_MULTI.matcher(legacy)).find()) {
			shard = ChatText.strip(m.group(2));
			amount = parseInt(ChatText.strip(m.group(3)));
		} else if ((m = SHARD_CHARM_ONE.matcher(legacy)).find()) {
			shard = ChatText.strip(m.group(2));
			amount = 1;
		} else if ((m = SHARD_CAUGHT_MULTI.matcher(legacy)).find()) {
			shard = ChatText.strip(m.group(2));
			amount = parseInt(ChatText.strip(m.group(1)).replace("x", "").trim());
		} else if ((m = SHARD_CAUGHT_ONE.matcher(legacy)).find()) {
			shard = ChatText.strip(m.group(1));
			amount = 1;
		}
		if (shard == null || amount <= 0) return;
		String key = switch (shard.trim()) {
			case "King Minos" -> "KING_MINOS_SHARD";
			case "Sphinx" -> "SPHINX_SHARD";
			case "Minotaur" -> "MINOTAUR_SHARD";
			case "Cretan Bull" -> "CRETAN_BULL_SHARD";
			case "Harpy" -> "HARPY_SHARD";
			default -> null;
		};
		if (key != null) DianaData.add(key, amount);
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// ---- mobs ---------------------------------------------------------------------------------------------

	/** A mob was dug out (or a mob you spawned was cocooned — SBO counts that as a fresh spawn). */
	static void onMobSpawn(String mob, boolean fromCocoon) {
		lastSpawnedMob = mob;
		lastSpawnedMobAt = System.currentTimeMillis();
		switch (mob) {
			case "King Minos" -> {
				rareSpawn(mob, "KING_MINOS", "mobsSinceKing", "King", "b2bKing", "a King");
				DianaData.addSince("kingSinceWool", 1);
				if (DianaData.since("kingSinceWool") >= 2) { DianaData.setFlag("b2bWool", false); DianaData.setFlag("b2bWoolLs", false); }
			}
			case "Manticore" -> {
				rareSpawn(mob, "MANTICORE", "mobsSinceManti", "Manticore", "b2bManti", "a Manticore");
				DianaData.addSince("mantiSinceCore", 1);
				DianaData.addSince("mantiSinceStinger", 1);
				if (DianaData.since("mantiSinceCore") >= 2) DianaData.setFlag("b2bCore", false);
				if (DianaData.since("mantiSinceStinger") >= 2) DianaData.setFlag("b2bStinger", false);
			}
			case "Minos Inquisitor" -> {
				rareSpawn(mob, "MINOS_INQUISITOR", "mobsSinceInq", "Inquisitor", "b2bInq", "an Inquis");
				DianaData.addSince("inqsSinceChim", 1);
				if (DianaData.since("inqsSinceChim") >= 2) DianaData.setFlag("b2bChim", false);
			}
			case "Sphinx" -> {
				rareSpawn(mob, "SPHINX", "mobsSinceSphinx", "Sphinx", "b2bSphinx", "a Sphinx");
				DianaData.addSince("sphinxSinceFood", 1);
				if (DianaData.since("sphinxSinceFood") >= 2) DianaData.setFlag("b2bFood", false);
			}
			case "Minos Champion" -> {
				DianaData.addSince("champsSinceRelic", 1);
				trackMob("MINOS_CHAMPION");
			}
			case "Minotaur" -> {
				DianaData.addSince("minotaursSinceStick", 1);
				if (DianaData.since("minotaursSinceStick") >= 2) DianaData.setFlag("b2bStick", false);
				trackMob("MINOTAUR");
			}
			case "Gaia Construct", "Harpy", "Cretan Bull", "Stranded Nymph", "Siamese Lynxes", "Minos Hunter" ->
				trackMob(mob.toUpperCase(Locale.ROOT).replace(' ', '_'));
			default -> {}
		}
	}

	private static void rareSpawn(String mob, String key, String sinceKey, String shortName, String b2bKey, String article) {
		RareMobShareFeature.onRareSpawn(mob);
		int took = DianaData.since(sinceKey) + 1;
		trackMob(key);
		since("§eTook §c" + took + " §eMobs to get " + article + "!");
		if (took == 1) {
			if (DianaData.flag(b2bKey)) since("§cb2b2b " + shortName + "!");
			else since("§cb2b " + shortName + "!");
			DianaData.setFlag(b2bKey, true);
		}
		DianaData.setSince(sinceKey, 0);
	}

	private static void trackMob(String key) {
		DianaData.add(key, 1);
		DianaData.add("TOTAL_MOBS", 1);
		for (String[] pair : new String[][]{{"mobsSinceInq", "b2bInq"}, {"mobsSinceKing", "b2bKing"},
			{"mobsSinceManti", "b2bManti"}, {"mobsSinceSphinx", "b2bSphinx"}}) {
			DianaData.addSince(pair[0], 1);
			if (DianaData.since(pair[0]) >= 2) DianaData.setFlag(pair[1], false);
		}
	}

	/** A tracked Diana mob's health hit 0 (see DianaMobs). {@code nearby}: within 30 blocks. */
	static void onDianaMobDeath(String name, boolean nearby) {
		String rareKey = null;
		if (name.contains("Minos Inquisitor")) rareKey = "MINOS_INQUISITOR";
		else if (name.contains("King Minos")) rareKey = "KING_MINOS";
		else if (name.contains("Sphinx")) rareKey = "SPHINX";
		else if (name.contains("Manticore")) rareKey = "MANTICORE";
		if (rareKey != null && lootShareRecently(2000) && !onCooldown(lsCooldowns, rareKey, 2000)) {
			DianaData.add(rareKey + "_LS", 1);
			switch (rareKey) {
				case "MINOS_INQUISITOR" -> DianaData.addSince("inqsSinceLsChim", 1);
				case "KING_MINOS" -> DianaData.addSince("kingSinceLsWool", 1);
				case "MANTICORE" -> { DianaData.addSince("mantiSinceLsCore", 1); DianaData.addSince("mantiSinceLsStinger", 1); }
				case "SPHINX" -> DianaData.addSince("sphinxSinceLsFood", 1);
				default -> {}
			}
		}
		if (nearby) lastDianaMobDeathAt = System.currentTimeMillis();
	}

	// ---- drops --------------------------------------------------------------------------------------------

	private static void onRngDrop(String drop) {
		boolean ls = lootShareRecently(2000);
		if (drop.contains("Shimmering Wool")) {
			dropWithSince("SHIMMERING_WOOL", ls, "kingSinceWool", "kingSinceLsWool", "b2bWool", "b2bWoolLs", "King Minos", "Shimmering Wool");
		} else if (drop.contains("Manti-core")) {
			dropWithSince("MANTI_CORE", ls, "mantiSinceCore", "mantiSinceLsCore", "b2bCore", "b2bCoreLs", "Manticores", "Manti-core");
		} else if (drop.contains("Fateful Stinger")) {
			dropWithSince("FATEFUL_STINGER", ls, "mantiSinceStinger", "mantiSinceLsStinger", "b2bStinger", "b2bStingerLs", "Manticores", "Fateful Stinger");
		} else if (drop.contains("Enchanted Book") && drop.contains("Chimera")) {
			dropWithSince("CHIMERA", ls, "inqsSinceChim", "inqsSinceLsChim", "b2bChim", "b2bChimLs", "Inquisitors", "Chimera");
		} else if (drop.contains("Brain Food")) {
			dropWithSince("BRAIN_FOOD", ls, "sphinxSinceFood", "sphinxSinceLsFood", "b2bFood", "b2bFoodLs", "Sphinx", "Brain Food");
		} else if (drop.contains("Daedalus Stick")) {
			onRareDrop("DAEDALUS_STICK", false, true);
			int took = DianaData.since("minotaursSinceStick");
			since("§eTook §c" + took + " §eMinotaurs to get a Daedalus Stick!");
			if (took == 1) {
				since(DianaData.flag("b2bStick") ? "§cb2b2b Daedalus Stick!" : "§cb2b Daedalus Stick!");
				DianaData.setFlag("b2bStick", true);
			}
			DianaData.setSince("minotaursSinceStick", 0);
		} else if (drop.contains("Minos Relic")) {
			onRareDrop("MINOS_RELIC", false, true);
			int took = DianaData.since("champsSinceRelic");
			since("§eTook §c" + took + " §eChampions to get a Minos Relic!");
			if (took == 1) since("§cb2b Minos Relic!");
			DianaData.setSince("champsSinceRelic", 0);
		} else if (drop.contains("Washed-up Souvenir")) {
			onRareDrop("WASHED_UP_SOUVENIR", false, true);
		} else if (drop.contains("Dwarf Turtle Shelmet")) {
			onRareDrop("DWARF_TURTLE_SHELMET", false, true);
		} else if (drop.contains("Crochet Tiger Plushie")) {
			onRareDrop("CROCHET_TIGER_PLUSHIE", false, true);
		} else if (drop.contains("Antique Remedies")) {
			onRareDrop("ANTIQUE_REMEDIES", false, true);
		} else if (drop.contains("Cretan Urn")) {
			onRareDrop("CRETAN_URN", false, true);
		} else if (drop.contains("Hilt of Revelations")) {
			onRareDrop("HILT_OF_REVELATIONS", false, true);
		}
	}

	private static void dropWithSince(String key, boolean ls, String sinceKey, String sinceLsKey, String b2bKey, String b2bLsKey,
									  String mobPlural, String itemName) {
		// Chimera: lootsharing your own Inquisitor can drop two books from one mob, so no cooldown (SBO).
		onRareDrop(key, true, !key.equals("CHIMERA"));
		String sk = ls ? sinceLsKey : sinceKey;
		String fk = ls ? b2bLsKey : b2bKey;
		int took = DianaData.since(sk);
		since("§eTook §c" + took + " §e" + mobPlural + " to " + (ls ? "lootshare " : "get ") + itemName + "!");
		if (took == 1) {
			String prefix = ls ? "Lootshare " : "";
			since(DianaData.flag(fk) ? "§cb2b2b " + prefix + itemName + "!" : "§cb2b " + prefix + itemName + "!");
			DianaData.setFlag(fk, true);
		}
		DianaData.setSince(sk, 0);
	}

	private static void onRareDrop(String key, boolean trackLootshare, boolean enforceCooldown) {
		if (enforceCooldown && onCooldown(cooldowns, "item:" + key, 500)) return;
		boolean ls = trackLootshare && lootShareRecently(2000);
		DianaData.add(ls ? key + "_LS" : key, 1);
	}

	/** Magic find shown after a rare drop line, e.g. "§b(+§b483 ✯ Magic Find)" (unused for now; kept for
	 *  parity with SBO's parsing). */
	static int magicFind(String drop) {
		Matcher m = MAGIC_FIND.matcher(drop);
		return m.find() ? parseInt(m.group(1)) : 0;
	}

	// ---- inventory / purse --------------------------------------------------------------------------------

	/** Every 5 ticks while in the Hub: inventory gains for drops without a chat line, and purse gains for
	 *  Scavenger coins (SBO Pickuplog + trackScavengerCoins). */
	static void inventoryTick(Minecraft mc) {
		if (mc.player == null) return;
		if (!mc.player.containerMenu.getCarried().isEmpty()) return;
		Map<String, Integer> now = new HashMap<>();
		Map<String, ItemStack> samples = new HashMap<>();
		List<ItemStack> items = mc.player.getInventory().getNonEquipmentItems();
		for (int slot = 0; slot < items.size(); slot++) {
			if (slot == 8) continue; // Skyblock menu star
			ItemStack stack = items.get(slot);
			if (stack.isEmpty()) continue;
			String id = SkyblockNbtUtils.getItemId(stack);
			if (id == null) continue;
			String uuid = SkyblockNbtUtils.getItemUuid(stack);
			String key = uuid != null ? id + "#" + uuid : id;
			now.merge(key, stack.getCount(), Integer::sum);
			samples.putIfAbsent(key, stack);
		}
		if (lastInventory != null) {
			for (Map.Entry<String, Integer> e : now.entrySet()) {
				int gained = e.getValue() - lastInventory.getOrDefault(e.getKey(), 0);
				if (gained <= 0) continue;
				String key = e.getKey();
				String id = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
				if (key.contains("#")) onUniquePickup(id, samples.get(key));
				else if (STACKABLE_DROPS.contains(id) && (dianaMobDiedRecently(3000) || lootShareRecently(3000))) {
					DianaData.add(id, gained);
					DianaRoll.onCommonPickup(id);
				}
			}
		}
		lastInventory = now;

		long purse = readPurse();
		if (purse >= 0 && lastPurse >= 0) {
			long change = purse - lastPurse;
			if (change > 0 && change <= 150_000 && dianaMobDiedRecently(4000) && System.currentTimeMillis() > scavengerBlockedUntil) {
				DianaData.add("SCAVENGER_COINS", (int) change);
				DianaData.add("COINS", (int) change);
			}
		}
		if (purse >= 0) lastPurse = purse;
	}

	private static void onUniquePickup(String id, ItemStack stack) {
		if (!id.equals("HILT_OF_REVELATIONS") && !id.equals("CROWN_OF_GREED")) return;
		Long created = stack == null ? null : SkyblockNbtUtils.getAttributeLong(stack, "timestamp");
		if (created != null && System.currentTimeMillis() - created > 6000) return;
		// No creation timestamp to go on: only trust it right after a Diana mob died or a lootshare.
		if (created == null && !dianaMobDiedRecently(6000) && !lootShareRecently(6000)) return;
		onRareDrop(id, false, true);
	}

	private static long readPurse() {
		String line = ScoreboardReader.findCurrentLineForLabel("Purse");
		if (line == null) line = ScoreboardReader.findCurrentLineForLabel("Piggy");
		if (line == null) return -1;
		String plain = ChatText.strip(line);
		int idx = plain.indexOf(':');
		if (idx < 0) return -1;
		String value = plain.substring(idx + 1).trim().split(" ")[0].replace(",", "");
		try {
			return (long) Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	static void resetInventorySnapshot() {
		lastInventory = null;
		lastPurse = -1;
	}
}
