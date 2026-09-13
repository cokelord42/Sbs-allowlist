package com.cokelord.skyblocksimplified.hud;

import com.cokelord.skyblocksimplified.api.HypixelElectionApi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated, per-stat scoreboard detection — ported directly from SkyHanni's real, confirmed regex
 * patterns (ScoreboardPattern.kt / PurseApi.kt / TabWidget.kt, from the SkyHanni-7.35.0 source in this
 * user's own Mod folder), per explicit user request to stop relying on this project's own generic "grab
 * whatever's after the label, hope it parses" heuristic (CustomScoreboardFeature's DETECTED line type).
 * That heuristic is what produced every one of this round's scoreboard bugs: labels that only sometimes
 * matched, numbers corrupted by embedded color codes the generic scan didn't expect, "Piggy" and "Purse"
 * treated as unrelated labels when Hypixel (and SkyHanni) treat them as the exact same stat.
 *
 * <p>Each resolver here returns the RAW extracted long value only (not a formatted string) — the caller
 * (CustomScoreboardFeature) still runs it through its own existing applyNumberFormat/line-color/chroma
 * pipeline, so the user's chosen number format and per-line styling keep working exactly as before; only
 * the extraction itself is now a confirmed, exact pattern instead of a generic guess.
 *
 * <p>Deliberately covers the common, self-contained sidebar/tablist stats (Purse, Motes, Copper, Sowdust,
 * Gems, North Stars, Bank) rather than SkyHanni's full ~80-pattern catalog spanning every dungeon/Kuudra/
 * mining-event/rift/carnival/galatea/winter-event context — those depend on entire other SkyHanni
 * subsystems (TabWidget, MiningApi, dungeon FloorApi, a remote-fetched repo, etc.) that don't exist in
 * this project, and porting all of them transitively is a different, much larger undertaking than
 * reliable core-stat detection.
 */
public final class SkyHanniScoreboardStats {
	private SkyHanniScoreboardStats() {}

	public enum Stat {
		PURSE("Purse:"),
		MOTES("Motes:"),
		COPPER("Copper:"),
		SOWDUST("Sowdust:"),
		GEMS("Gems:"),
		NORTH_STARS("North Stars:"),
		BANK("Bank:"),
		COLD("Cold:"),
		HEAT("Heat:"),
		SOULFLOW("Soulflow:"),
		PELTS("Pelts:"),
		DRAGON_ESSENCE("Dragon Essence:"),
		FOSSIL_DUST("Fossil Dust:"),
		BITS("Bits:");

		public final String label;

		Stat(String label) {
			this.label = label;
		}
	}

	// REGEX-TEST: Piggy: §6423,085,766 / Purse: §6423,085,776 §e(+5) — confirmed via SkyHanni's PurseApi.kt.
	// "Piggy" and "Purse" are the exact same underlying stat on Hypixel's own end (per user confirmation),
	// so one pattern covers both instead of treating them as two unrelated labels the way this project's
	// own auto-detected label text used to.
	private static final Pattern PURSE_PATTERN =
		Pattern.compile("(?:§.)*(?:Piggy|Purse): §6(?<coins>[\\d,.]+)");
	// REGEX-TEST: Motes: §5137,242
	private static final Pattern MOTES_PATTERN = Pattern.compile("(?:§.)*Motes: (?:§.)*(?<motes>[\\d,]+)");
	// REGEX-TEST: Copper: §c3,416
	private static final Pattern COPPER_PATTERN = Pattern.compile("(?:§.)*Copper: (?:§.)*(?<copper>[\\d,]+)");
	// REGEX-TEST: Sowdust: §230,210,307
	private static final Pattern SOWDUST_PATTERN = Pattern.compile("\\s?(?:§.)*Sowdust: (?:§.)*(?<sowdust>[\\d,]+)");
	// REGEX-TEST: Gems: §a350
	private static final Pattern GEMS_PATTERN = Pattern.compile("(?:§.)*Gems: (?:§.)*(?<gems>[\\d,]+)");
	// REGEX-TEST: North Stars: §d1,539
	private static final Pattern NORTH_STARS_PATTERN = Pattern.compile("North Stars: §d(?<northstars>[\\d,]+)");
	// Tab-list widget, not sidebar — TabWidget.BANK's real pattern; "personal" (a coop-member's own share)
	// is intentionally not surfaced here since this stat only needs the total amount.
	private static final Pattern BANK_PATTERN = Pattern.compile("Bank: (?<amount>[\\d,]+)");
	// REGEX-TEST: Cold: §b-1❄ — MiningApi.coldPattern. Value can be negative; kept as-is (not negated) since
	// SkyHanni's own display-side negation (`-MiningApi.cold`) is a display convention this project's number
	// formatting pipeline (applyNumberFormat) doesn't need to replicate — the raw captured sign is shown.
	private static final Pattern COLD_PATTERN = Pattern.compile("(?:§.)*Cold: §.(?<cold>-?\\d+)❄");
	// REGEX-TEST: Heat: §c14♨ — MiningApi.heatPattern, numeric branch only. Hypixel's real value can also be
	// the literal text "IMMUNE" instead of a number; that case isn't representable through this project's
	// numeric-only DETECTED-line pipeline, so it's left unmatched (falls through to "not shown right now")
	// rather than guessing at special-casing text into a numeric slot.
	private static final Pattern HEAT_PATTERN = Pattern.compile("Heat: §.(?<heat>\\d+)♨?");
	// Tab-list widget (TabWidget.SOULFLOW) — real pattern is permissive ("Soulflow: (?<amount>.*)"),
	// narrowed here to digits/commas only since this project's numeric pipeline needs a clean integer.
	private static final Pattern SOULFLOW_PATTERN = Pattern.compile("Soulflow: (?<amount>[\\d,]+)");
	// REGEX-TEST: Pelts: §5160 — ScoreboardPattern.peltsPattern (no named group in the original; added here).
	private static final Pattern PELTS_PATTERN = Pattern.compile("(?:§.)*Pelts: (?:§.)*(?<pelts>[\\d,]+)");
	// REGEX-TEST: Dragon Essence: §d2,442 — ScoreboardPattern.essencePattern, simplified to the common
	// plain-integer case (the original also tolerates a decimal suffix, not needed for the common display).
	private static final Pattern DRAGON_ESSENCE_PATTERN = Pattern.compile("Dragon Essence: §.(?<essence>-?[\\d,]+)");
	// REGEX-TEST: Fossil Dust: §f3,281 §e(+1) — ScoreboardPattern.fossilDustPattern (no named group
	// originally; added here).
	private static final Pattern FOSSIL_DUST_PATTERN = Pattern.compile("Fossil Dust: (?:§f)*(?<fossildust>[\\d,]+)");
	// REGEX-TEST: Bits: §b140,965 — BitsApi.bitsScoreboardPattern. Only the CURRENT bits total (no
	// bits-available fraction, no cookie-buff countdown) — the fraction/buff display needs a real
	// persistent tracker (BitsApi itself has its own storage layer for that) this project doesn't have yet.
	private static final Pattern BITS_PATTERN = Pattern.compile("^Bits: §b(?<amount>[\\d,.]+)");

	/** Raw numeric value for a known stat, scanning the given already-fetched sidebar lines — null if that
	 *  stat isn't present on the current scoreboard at all (different island, not yet loaded, etc.). */
	public static Long resolveFromSidebar(Stat stat, List<String> sidebarLines) {
		Pattern pattern = sidebarPatternFor(stat);
		if (pattern == null) return null;
		String group = groupNameFor(stat);
		for (String line : sidebarLines) {
			Matcher matcher = pattern.matcher(line);
			if (matcher.find()) {
				return parseLong(matcher.group(group));
			}
		}
		return null;
	}

	/** Same idea, for stats that only ever appear in the tab list (Bank, Soulflow). */
	public static Long resolveFromTabList(Stat stat, List<String> tabListLines) {
		Pattern pattern = stat == Stat.BANK ? BANK_PATTERN : stat == Stat.SOULFLOW ? SOULFLOW_PATTERN : null;
		if (pattern == null) return null;
		for (String line : tabListLines) {
			Matcher matcher = pattern.matcher(line);
			if (matcher.find()) {
				return parseLong(matcher.group("amount"));
			}
		}
		return null;
	}

	public static boolean isTabListStat(Stat stat) {
		return stat == Stat.BANK || stat == Stat.SOULFLOW;
	}

	/** The known stat whose label this DETECTED line was seeded with, or null for anything not in the
	 *  confirmed set (falls back to this project's existing generic label-matching). "Piggy:" resolves to
	 *  PURSE too, matching the shared underlying regex above. */
	public static Stat statForLabel(String label) {
		String plain = label.replaceAll("§.?", "").trim();
		if (plain.equals("Piggy:") || plain.equals("Purse:")) return Stat.PURSE;
		for (Stat stat : Stat.values()) {
			if (stat.label.equals(plain)) return stat;
		}
		return null;
	}

	private static Pattern sidebarPatternFor(Stat stat) {
		return switch (stat) {
			case PURSE -> PURSE_PATTERN;
			case MOTES -> MOTES_PATTERN;
			case COPPER -> COPPER_PATTERN;
			case SOWDUST -> SOWDUST_PATTERN;
			case GEMS -> GEMS_PATTERN;
			case NORTH_STARS -> NORTH_STARS_PATTERN;
			case COLD -> COLD_PATTERN;
			case HEAT -> HEAT_PATTERN;
			case PELTS -> PELTS_PATTERN;
			case DRAGON_ESSENCE -> DRAGON_ESSENCE_PATTERN;
			case FOSSIL_DUST -> FOSSIL_DUST_PATTERN;
			case BITS -> BITS_PATTERN;
			case BANK, SOULFLOW -> null; // tab-list only
		};
	}

	private static String groupNameFor(Stat stat) {
		return switch (stat) {
			case PURSE -> "coins";
			case MOTES -> "motes";
			case COPPER -> "copper";
			case SOWDUST -> "sowdust";
			case GEMS -> "gems";
			case NORTH_STARS -> "northstars";
			case COLD -> "cold";
			case HEAT -> "heat";
			case PELTS -> "pelts";
			case DRAGON_ESSENCE -> "essence";
			case FOSSIL_DUST -> "fossildust";
			case BITS -> "amount";
			case BANK, SOULFLOW -> "amount";
		};
	}

	private static Long parseLong(String digitsWithCommas) {
		if (digitsWithCommas == null) return null;
		try {
			return Long.parseLong(digitsWithCommas.replace(",", ""));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---- Multi-line composite stats -------------------------------------------------------------
	// A few real SkyHanni elements return SEVERAL rendered lines from one config entry (Party's member
	// list, Slayer's quest block, Powder's three types, Mayor's active perks) rather than a single value.
	// These labels are recognized here the same way single-value Stat labels are, but resolve straight to
	// already-formatted display lines instead of a raw number CustomScoreboardFeature would run through
	// applyNumberFormat — there's no "number format" to apply to a player name or a perk description.

	public static final List<String> MULTI_LINE_LABELS = List.of("Party:", "Slayer Quest:", "Powder:", "Mayor:", "SB Level:");

	private static final Pattern SLAYER_MARKER = Pattern.compile("Slayer Quest");
	// REGEX-TEST: §2᠅ §fMithril§f: §235,448 / §d᠅ §fGemstone§f: §d36,758 / §b᠅ §fGlacite§f: §b29,537 —
	// ScoreboardPattern.powderPattern, ported directly (current amount only — HotmApi's own lifetime-total
	// tracking isn't replicated here, matching this file's general "no new persistent trackers beyond what
	// a single scoreboard read already gives us" scope).
	private static final Pattern POWDER_LINE = Pattern.compile(
		"(?:§.)*᠅ §.(?<type>Gemstone|Mithril|Glacite)(?: Powder)?(?:§.)*:? (?:§.)*(?<amount>[\\d,.]*)");
	// Tab-list widget (TabWidget.SB_LEVEL) — "SB Level: [287] 26" (xp toward the next level, out of 100).
	private static final Pattern SB_LEVEL_PATTERN = Pattern.compile("SB Level: \\[(?<level>\\d+)] (?<xp>\\d+)");

	public static boolean isMultiLineLabel(String label) {
		String plain = label.replaceAll("§.?", "").trim();
		return MULTI_LINE_LABELS.contains(plain);
	}

	/** Resolves a recognized multi-line label to its already-formatted display lines, or null if that
	 *  content genuinely isn't available right now (not in a slayer quest, no party, mayor API not loaded
	 *  yet, etc.) — same "null means hide/fallback" contract as the single-value resolvers above. */
	public static List<String> resolveMultiLine(String label, List<String> sidebarLines, List<String> tabListLines) {
		String plain = label.replaceAll("§.?", "").trim();
		return switch (plain) {
			case "Party:" -> resolveParty();
			case "Slayer Quest:" -> resolveSlayer(sidebarLines);
			case "Powder:" -> resolvePowder(sidebarLines);
			case "Mayor:" -> resolveMayor();
			case "SB Level:" -> resolveSkyBlockLevel(tabListLines);
			default -> null;
		};
	}

	// Real bug found: this used to read from HypixelPartyTracker, a second, separate party-chat listener
	// whose regexes still had literal "§e"/"§b"/"§6"/"§7" color codes baked in — the exact same "Component
	// #getString() already strips formatting, so a pattern expecting raw § codes in that plain text NEVER
	// matches" bug com.cokelord.skyblocksimplified.party.PartyApi's own doc comment already documents (and
	// already fixed, for itself) elsewhere in this codebase. Worse, HypixelPartyTracker.start() itself was
	// never even called from anywhere, so its listener never registered in the first place either — this
	// scoreboard's Party section could never show for anyone, regardless of the regex bug. PartyApi is the
	// one real, working, already-registered party tracker in this project (every other party-gated feature,
	// e.g. ChatCommandsFeature's leader-only commands, already depends on it) — reused here instead of
	// fixing a second, redundant implementation of the same thing.
	private static List<String> resolveParty() {
		java.util.Set<String> members = com.cokelord.skyblocksimplified.party.PartyApi.members();
		if (members.isEmpty()) return null;
		String leader = com.cokelord.skyblocksimplified.party.PartyApi.leader();
		List<String> lines = new ArrayList<>();
		lines.add("§9§lParty (" + members.size() + ")");
		if (leader != null) lines.add(" §7- §f" + leader + " §e♚");
		for (String member : members) {
			if (member.equals(leader)) continue;
			lines.add(" §7- §f" + member);
		}
		return lines;
	}

	private static List<String> resolveSlayer(List<String> sidebarLines) {
		for (int i = 0; i < sidebarLines.size(); i++) {
			if (SLAYER_MARKER.matcher(sidebarLines.get(i).replaceAll("§.?", "").trim()).matches()) {
				List<String> lines = new ArrayList<>();
				lines.add(sidebarLines.get(i));
				for (int j = i + 1; j < Math.min(i + 3, sidebarLines.size()); j++) {
					lines.add(sidebarLines.get(j));
				}
				return lines;
			}
		}
		return null;
	}

	private static List<String> resolvePowder(List<String> sidebarLines) {
		List<String> lines = new ArrayList<>();
		for (String line : sidebarLines) {
			Matcher matcher = POWDER_LINE.matcher(line);
			if (matcher.find()) {
				String type = matcher.group("type");
				String amount = matcher.group("amount");
				if (amount == null || amount.isBlank()) continue;
				lines.add(" §7- §f" + type + ": §f" + amount);
			}
		}
		if (lines.isEmpty()) return null;
		lines.add(0, "§9§lPowder");
		return lines;
	}

	private static List<String> resolveMayor() {
		HypixelElectionApi.MayorInfo mayor = HypixelElectionApi.currentMayor();
		if (mayor == null) return null;
		List<String> lines = new ArrayList<>();
		lines.add("§2" + mayor.name());
		for (String perk : mayor.perkNames()) {
			lines.add(" §7- §e" + perk);
		}
		return lines;
	}

	private static List<String> resolveSkyBlockLevel(List<String> tabListLines) {
		for (String line : tabListLines) {
			Matcher matcher = SB_LEVEL_PATTERN.matcher(line);
			if (matcher.find()) {
				return List.of("SB Level: " + matcher.group("level"), "XP: §b" + matcher.group("xp") + "§3/§b100");
			}
		}
		return null;
	}

	// ---- Passthrough event lines -------------------------------------------------------------------
	// SkyHanni's per-event ScoreboardEvent* classes (Carnival, Anniversary, New Year, Spooky Festival,
	// Traveling Zoo, Redstone, Visiting, Voting, and a handful of Rift-specific ones) are, functionally,
	// each just "does any sidebar line match this one confirmed pattern? if so, show that exact line" —
	// none of them track state across frames the way Party/Mayor do. Rather than a synthetic per-event
	// class, one shared "does this pattern match any current sidebar line" resolver covers all of them;
	// the config-facing "label" is a synthetic display name (never literally present in the raw text) used
	// purely to pick which pattern to test, the same way MULTI_LINE_LABELS' labels are lookup keys rather
	// than required prefixes.
	// Deliberately excludes anything whose real line already starts with a clean "Label: value" shape
	// (Clues:, Hay Eaten:, Big damage in: all do) — those work fine through the existing generic
	// label-match path already and don't need a dedicated pattern here, just a KNOWN_LABELS entry.
	public static final List<String> PASSTHROUGH_EVENT_LABELS = List.of(
		"Carnival Event:", "Anniversary Event:", "Traveling Zoo Event:", "New Year Event:", "Spooky Festival Event:",
		"Redstone Event:", "Visiting:", "Election Votes:"
	);

	// REGEX-TEST: §eCarnival§f 85:33:57
	private static final Pattern CARNIVAL_EVENT = Pattern.compile("§eCarnival§f \\d+(?::\\d+)*");
	// REGEX-TEST: §d5th Anniversary§f 167:59:54 / §bCentury Raffle§f 124:00:00
	private static final Pattern ANNIVERSARY_EVENT = Pattern.compile("(?:§d\\d+(?:st|nd|rd|th) Anniversary|§bCentury Raffle)§f (?:\\d|:)+");
	// REGEX-TEST: §aTraveling Zoo§f 43:41
	private static final Pattern TRAVELING_ZOO_EVENT = Pattern.compile("§aTraveling Zoo§f \\d*:\\d+");
	// REGEX-TEST: §dNew Year Event!§f 17:53
	private static final Pattern NEW_YEAR_EVENT = Pattern.compile("§dNew Year Event!§f \\d*:?\\d+");
	// REGEX-TEST: §6Spooky Festival§f 50:54
	private static final Pattern SPOOKY_EVENT = Pattern.compile("§6Spooky Festival§f \\d*:?\\d+");
	// REGEX-TEST: §e§l⚡ §cRedstone: §e§b4%
	private static final Pattern REDSTONE_EVENT = Pattern.compile("(?:§.)*⚡ §cRedstone: (?:§.)*\\d+%");
	// REGEX-TEST: §a✌ §7(§a9§7/20)
	private static final Pattern VISITING = Pattern.compile("§a✌ §7\\(§.\\d+(?:§.)?/\\d+(?:§.)?\\)");
	// REGEX-TEST: §6Year 384 Votes
	private static final Pattern ELECTION_VOTES = Pattern.compile("§6Year \\d+ Votes");

	public static boolean isPassthroughEventLabel(String label) {
		String plain = label.replaceAll("§.?", "").trim();
		return PASSTHROUGH_EVENT_LABELS.contains(plain);
	}

	/** The exact matching sidebar line, verbatim (still carrying its own § codes — same as any other raw
	 *  sidebar line this project passes to applyNumberFormat, which strips them before rendering), or null
	 *  if that event genuinely isn't active right now. */
	public static String resolvePassthroughEvent(String label, List<String> sidebarLines) {
		Pattern pattern = passthroughPatternFor(label.replaceAll("§.?", "").trim());
		if (pattern == null) return null;
		for (String line : sidebarLines) {
			if (pattern.matcher(line).find()) return line;
		}
		return null;
	}

	private static Pattern passthroughPatternFor(String plainLabel) {
		return switch (plainLabel) {
			case "Carnival Event:" -> CARNIVAL_EVENT;
			case "Anniversary Event:" -> ANNIVERSARY_EVENT;
			case "Traveling Zoo Event:" -> TRAVELING_ZOO_EVENT;
			case "New Year Event:" -> NEW_YEAR_EVENT;
			case "Spooky Festival Event:" -> SPOOKY_EVENT;
			case "Redstone Event:" -> REDSTONE_EVENT;
			case "Visiting:" -> VISITING;
			case "Election Votes:" -> ELECTION_VOTES;
			default -> null;
		};
	}
}
