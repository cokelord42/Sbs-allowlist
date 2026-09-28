package com.cokelord.skyblocksimplified.diana;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SBO's Diana party chat commands (general/PartyCommands.kt dianaCommands + !since + !stats), answering from
 * this Diana event's tracker counts. Wired into the Chat Commands module's party branch.
 */
public final class DianaPartyCommands {
	private static final Map<String, Supplier<String>> COMMANDS = new HashMap<>();

	private DianaPartyCommands() {}

	private static void add(List<String> aliases, Supplier<String> response) {
		for (String alias : aliases) COMMANDS.put(alias, response);
	}

	private static int e(String key) { return DianaData.event(key); }

	private static String pct(int count, int of) {
		return String.format(Locale.ROOT, "%.2f", of <= 0 ? 0.0 : count * 100.0 / of);
	}

	/** "Label: count (percent%)" — percent of the given mob count, or of total mobs when mobKey is null. */
	private static String fmt(String label, String itemKey, String mobKey) {
		int count = e(itemKey);
		int of = mobKey != null ? e(mobKey) : e("TOTAL_MOBS");
		return label + ": " + count + " (" + pct(count, of) + "%)";
	}

	static {
		add(List.of("chim", "chimera", "chims", "chimeras", "book", "books"), () -> fmt("Chimera", "CHIMERA", "MINOS_INQUISITOR") + " +" + e("CHIMERA_LS") + " LS");
		add(List.of("inqsls", "inquisitorls", "inquisls", "lsinq", "lsinqs", "lsinquisitor", "lsinquis"), () -> "Inquisitor LS: " + e("MINOS_INQUISITOR_LS"));
		add(List.of("inq", "inqs", "inquisitor", "inquis"), () -> fmt("Inquisitor", "MINOS_INQUISITOR", null));
		add(List.of("kingls", "kingsls"), () -> "King LS: " + e("KING_MINOS_LS"));
		add(List.of("king", "kings"), () -> fmt("King", "KING_MINOS", null));
		add(List.of("sphinxls", "sphinxsls"), () -> "Sphinx LS: " + e("SPHINX_LS"));
		add(List.of("sphinx", "sphinxs"), () -> fmt("Sphinx", "SPHINX", null));
		add(List.of("mantils", "mantisls"), () -> "Manticore LS: " + e("MANTICORE_LS"));
		add(List.of("manti", "mantis"), () -> fmt("Manticore", "MANTICORE", null));
		add(List.of("dye", "dyes"), () -> fmt("Dye", "MYTHOLOGICAL_DYE", null));
		add(List.of("burrows", "burrow"), () -> "Burrows: " + String.format(Locale.ROOT, "%,d", e("TOTAL_BURROWS")));
		add(List.of("relic", "relics"), () -> fmt("Relics", "MINOS_RELIC", "MINOS_CHAMPION"));
		add(List.of("chimls", "chimerals", "bookls", "lschim", "lsbook", "lootsharechim", "lschimera"), () -> fmt("Chimera LS", "CHIMERA_LS", "MINOS_INQUISITOR_LS"));
		add(List.of("core", "manticore"), () -> fmt("Cores", "MANTI_CORE", "MANTICORE"));
		add(List.of("corels", "manticorels", "lscore", "lsmanticore"), () -> fmt("Core LS", "MANTI_CORE_LS", "MANTICORE_LS"));
		add(List.of("stinger", "fatefulstinger"), () -> fmt("Stingers", "FATEFUL_STINGER", "MANTICORE"));
		add(List.of("stingerls", "fatefulstingerls", "lsstinger", "lsfatefulstinger"), () -> fmt("Stinger LS", "FATEFUL_STINGER_LS", "MANTICORE_LS"));
		add(List.of("wool", "shimmering", "shimmeringwool"), () -> fmt("Wool", "SHIMMERING_WOOL", "KING_MINOS"));
		add(List.of("woolls", "shimmeringwoolls", "lsshimmering", "lsshimmeringwool"), () -> fmt("Wool LS", "SHIMMERING_WOOL_LS", "KING_MINOS_LS"));
		add(List.of("food", "brainfood", "brain"), () -> fmt("Brain Food", "BRAIN_FOOD", "SPHINX"));
		add(List.of("foodls", "brainfoodls", "lsbrainfood", "lsbrain"), () -> fmt("Brain Food LS", "BRAIN_FOOD_LS", "SPHINX_LS"));
		add(List.of("braided", "braideds"), () -> fmt("Braided feathers", "BRAIDED_GRIFFIN_FEATHER", null));
		add(List.of("kingshard", "kingshards"), () -> fmt("King Shards", "KING_MINOS_SHARD", "KING_MINOS"));
		add(List.of("sphinxshard", "sphinxshards"), () -> fmt("Sphinx Shards", "SPHINX_SHARD", "SPHINX"));
		add(List.of("minotaurshard", "minotaurshards"), () -> fmt("Minotaur Shards", "MINOTAUR_SHARD", "MINOTAUR"));
		add(List.of("certanshard", "certanshards"), () -> fmt("Certan Shards", "CRETAN_BULL_SHARD", "CRETAN_BULL"));
		add(List.of("mythofrag", "frags"), () -> "Mytho Frags: " + e("MYTHOS_FRAGMENT"));
		add(List.of("urns", "urn", "cretanurn"), () -> fmt("Urns", "CRETAN_URN", "CRETAN_BULL"));
		add(List.of("hilt", "hiltofrevelations"), () -> fmt("Hilts", "HILT_OF_REVELATIONS", "MINOS_HUNTER"));
		add(List.of("sticks", "stick"), () -> fmt("Sticks", "DAEDALUS_STICK", "MINOTAUR"));
		add(List.of("feathers", "feather"), () -> "Feathers: " + e("GRIFFIN_FEATHER"));
		add(List.of("coins", "coin"), () -> "Coins: " + String.format(Locale.ROOT, "%,d", e("COINS")));
		add(List.of("mobs", "mob"), () -> "Mobs: " + e("TOTAL_MOBS"));
	}

	/**
	 * @param command lowercase command without "!"
	 * @param arg     first argument or null
	 * @param self    this player's name (for !stats &lt;name&gt;)
	 * @return the party chat reply, or null if this isn't a Diana command
	 */
	public static String respond(String command, String arg, String self) {
		switch (command) {
			case "since" -> { return since(arg); }
			case "stats", "stat" -> { return arg != null && arg.equalsIgnoreCase(self) ? stats() : null; }
			default -> {
				if (arg != null) return null;
				Supplier<String> s = COMMANDS.get(command);
				return s == null ? null : s.get();
			}
		}
	}

	public static boolean handles(String command) {
		return COMMANDS.containsKey(command) || command.equals("since") || command.equals("stats") || command.equals("stat");
	}

	private static String since(String arg) {
		if (arg == null) return null;
		return switch (arg.toLowerCase(Locale.ROOT)) {
			case "chimera", "chim", "chims", "chimeras", "book", "books" -> "Inqs since chim: " + DianaData.since("inqsSinceChim");
			case "stick", "sticks" -> "Minos since stick: " + DianaData.since("minotaursSinceStick");
			case "relic", "relics" -> "Champs since relic: " + DianaData.since("champsSinceRelic");
			case "inq", "inqs", "inquisitor", "inquisitors", "inquis" -> "Mobs since inq: " + DianaData.since("mobsSinceInq");
			case "lschim", "chimls", "lschimera", "chimerals", "lsbook", "bookls", "lootsharechim" -> "Inqs since lootshare chim: " + DianaData.since("inqsSinceLsChim");
			case "kings", "king" -> "Mobs since king: " + DianaData.since("mobsSinceKing");
			case "manti" -> "Mobs since manti: " + DianaData.since("mobsSinceManti");
			case "core", "cores" -> "Mantis since core: " + DianaData.since("mantiSinceCore");
			case "wool", "wools" -> "Kings since wool: " + DianaData.since("kingSinceWool");
			case "corels", "lscore" -> "Mantis since lootshare core: " + DianaData.since("mantiSinceLsCore");
			case "woolls", "lswool" -> "Kings since lootshare wool: " + DianaData.since("kingSinceLsWool");
			default -> null;
		};
	}

	/** SBO's sendPlayerStats shape, minus playtime/profit (not tracked here). */
	private static String stats() {
		return "Burrows: " + String.format(Locale.ROOT, "%,d", e("TOTAL_BURROWS"))
			+ " - Mobs: " + e("TOTAL_MOBS")
			+ " - Inquisitors: " + e("MINOS_INQUISITOR") + " (" + pct(e("MINOS_INQUISITOR"), e("TOTAL_MOBS")) + "%)"
			+ " - LS Inqs: " + e("MINOS_INQUISITOR_LS")
			+ " - Chimeras: " + e("CHIMERA") + " (" + pct(e("CHIMERA"), e("MINOS_INQUISITOR")) + "%)"
			+ " - LS: " + e("CHIMERA_LS") + " (" + pct(e("CHIMERA_LS"), e("MINOS_INQUISITOR_LS")) + "%)"
			+ " - Sticks: " + e("DAEDALUS_STICK") + " (" + pct(e("DAEDALUS_STICK"), e("MINOTAUR")) + "%)"
			+ " - Relics: " + e("MINOS_RELIC") + " (" + pct(e("MINOS_RELIC"), e("MINOS_CHAMPION")) + "%)";
	}
}
