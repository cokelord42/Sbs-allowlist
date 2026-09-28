package com.cokelord.skyblocksimplified.diana.pf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** SBO party finder data classes (DataClasses.kt: Party, PartyPlayerStats, Reqs), parsed leniently. */
public final class PartyModels {
	private PartyModels() {}

	public record Reqs(int lvl, int kills, boolean eman9, boolean looting5, int mp) {
		public static final Reqs NONE = new Reqs(-1, 0, false, false, 0);

		static Reqs from(JsonObject o) {
			if (o == null) return NONE;
			return new Reqs(SboApi.integer(o, "lvl", -1), SboApi.integer(o, "kills", 0), SboApi.bool(o, "eman9"),
				SboApi.bool(o, "looting5"), SboApi.integer(o, "mp", 0));
		}

		/** encodeDefaults=false, like SBO: only non-default fields are sent. */
		JsonObject toJson() {
			JsonObject o = new JsonObject();
			if (lvl != -1) o.addProperty("lvl", lvl);
			if (kills != 0) o.addProperty("kills", kills);
			if (eman9) o.addProperty("eman9", true);
			if (looting5) o.addProperty("looting5", true);
			if (mp != 0) o.addProperty("mp", mp);
			return o;
		}
	}

	public record PlayerStats(String name, int sbLvl, boolean eman9, boolean looting5daxe, int emanLvl, List<String> warnings,
							  String uuid, boolean clover, int daxeLootingLvl, int daxeChimLvl, int magicalPower, int enrichments,
							  int missingEnrichments, String griffinRarity, String griffinItem, int killLeaderboard, int mythosKills) {
		public static final PlayerStats EMPTY = new PlayerStats("", -1, false, false, 0, List.of(), "", false, 0, 0, 0, 0, 0, "", null, 999999, 0);

		static PlayerStats from(JsonObject o) {
			if (o == null) return EMPTY;
			List<String> warnings = new ArrayList<>();
			if (o.has("warnings") && o.get("warnings").isJsonArray()) for (JsonElement e : o.getAsJsonArray("warnings")) warnings.add(e.getAsString());
			String griffinItem = o.has("griffinItem") && o.get("griffinItem").isJsonPrimitive() ? o.get("griffinItem").getAsString() : null;
			return new PlayerStats(SboApi.str(o, "name") == null ? "" : SboApi.str(o, "name"), SboApi.integer(o, "sbLvl", -1),
				SboApi.bool(o, "eman9"), SboApi.bool(o, "looting5daxe"), SboApi.integer(o, "emanLvl", 0), warnings,
				SboApi.str(o, "uuid") == null ? "" : SboApi.str(o, "uuid"), SboApi.bool(o, "clover"),
				SboApi.integer(o, "daxeLootingLvl", 0), SboApi.integer(o, "daxeChimLvl", 0), SboApi.integer(o, "magicalPower", 0),
				SboApi.integer(o, "enrichments", 0), SboApi.integer(o, "missingEnrichments", 0),
				SboApi.str(o, "griffinRarity") == null ? "" : SboApi.str(o, "griffinRarity"), griffinItem,
				SboApi.integer(o, "killLeaderboard", 999999), SboApi.integer(o, "mythosKills", 0));
		}
	}

	public record Party(List<PlayerStats> members, Reqs reqs, String leader, int memberCount, String leaderName, String note, int partySize) {
		static Party from(JsonObject o) {
			List<PlayerStats> members = new ArrayList<>();
			if (o.has("partyinfo") && o.get("partyinfo").isJsonArray()) {
				for (JsonElement e : o.getAsJsonArray("partyinfo")) if (e.isJsonObject()) members.add(PlayerStats.from(e.getAsJsonObject()));
			}
			JsonObject reqs = o.has("reqs") && o.get("reqs").isJsonObject() ? o.getAsJsonObject("reqs") : null;
			String note = SboApi.str(o, "note");
			return new Party(members, Reqs.from(reqs), SboApi.str(o, "leader"), SboApi.integer(o, "partymembers", members.size()),
				SboApi.str(o, "leaderName") == null ? "?" : SboApi.str(o, "leaderName"), note == null ? "" : note.replace("%20", " "),
				SboApi.integer(o, "partySize", 6));
		}
	}

	static List<Party> parties(JsonObject response) {
		List<Party> out = new ArrayList<>();
		if (response != null && response.has("Parties") && response.get("Parties").isJsonArray()) {
			JsonArray arr = response.getAsJsonArray("Parties");
			for (JsonElement e : arr) if (e.isJsonObject()) out.add(Party.from(e.getAsJsonObject()));
		}
		return out;
	}

	// ---- SBO Helper color formatting ---------------------------------------------------------------------

	public static String lvlColor(int lvl) {
		String c = lvl >= 480 ? "§4" : lvl >= 440 ? "§c" : lvl >= 400 ? "§6" : lvl >= 360 ? "§5" : lvl >= 320 ? "§d"
			: lvl >= 280 ? "§9" : lvl >= 240 ? "§3" : lvl >= 200 ? "§b" : "§7";
		return c + lvl;
	}

	public static String numberColor(int n, int max) {
		return (n == max ? "§c" : n == max - 1 ? "§6" : "§9") + n;
	}

	public static String killsColor(int kills) {
		String c = kills >= 200_000 ? "§6" : kills >= 150_000 ? "§e" : kills >= 100_000 ? "§c" : kills >= 75_000 ? "§d"
			: kills >= 50_000 ? "§9" : kills >= 25_000 ? "§a" : kills >= 10_000 ? "§2" : "§7";
		return c + String.format(java.util.Locale.ROOT, "%,d", kills);
	}

	public static String rarityColor(String rarity) {
		return switch (rarity.toLowerCase(java.util.Locale.ROOT).trim()) {
			case "common" -> "§f";
			case "uncommon" -> "§a";
			case "rare" -> "§9";
			case "epic" -> "§5";
			case "legendary" -> "§6";
			case "mythic" -> "§d";
			default -> "§7";
		} + rarity;
	}

	public static String griffinItemColor(String item) {
		if (item == null || item.isEmpty()) return "§7None";
		String name = item.replace("PET_ITEM_", "").replace("_", " ");
		name = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
		return switch (name.toLowerCase(java.util.Locale.ROOT)) {
			case "four eyed fish", "crochet tiger plushie", "antique remedies", "minos relic" -> "§5";
			case "dwarf turtle shelmet", "lucky clover" -> "§a";
			default -> "§7";
		} + name;
	}
}
