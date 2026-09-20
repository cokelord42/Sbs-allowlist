package com.cokelord.skyblocksimplified.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared enchant-lore-line detection — ported from SkyHanni's EnchantParser.kt confirmed regex (a lore
 *  line counts as "enchant text" if it's ENTIRELY made of comma-separated "Name Level" pairs, optionally
 *  followed by a stacking-progress number like "§82.1M"). Used by both Hide Vanilla Enchants (Enchants/
 *  StoredEnchantments data component lines) and Hide Enchant Description (the italic lore line Hypixel
 *  prints under each enchant, only when the item has few enough enchants to fit descriptions). */
public final class EnchantLoreUtil {
	private EnchantLoreUtil() {}

	private static final Pattern ENCHANT_LINE = Pattern.compile(
		"^(?:(?:§.)*[A-Za-z][A-Za-z '-]+ (?:[IVXLCDM]+|[0-9]+)(?:(?:§r)?, |$| (?:§r)?§8\\d{1,3}(?:[,.]\\d{1,3})*)[kKmMbB]?)+$"
	);

	public static boolean isEnchantLine(String line) {
		return !line.isBlank() && ENCHANT_LINE.matcher(line).matches();
	}

	/** One "Name Level" run detected within a lore line (not necessarily a whole-line enchant list — a
	 *  single enchant can also appear inline elsewhere), with [start, end) character offsets into that
	 *  line's plain string covering just the name+level text (excluding any leading §-color codes),
	 *  ready to be sliced out and replaced by a re-styled Component. */
	public record EnchantToken(int start, int end, String name, String levelText) {}

	// Same name/level char classes as ENCHANT_LINE, but scanning for individual occurrences anywhere in
	// the line rather than requiring the whole line to be made of them — used by Custom Enchant Parsing
	// to find and restyle just the matching "Name Level" run, leaving surrounding lore text untouched.
	// The lookahead requires the token be followed by a real delimiter (comma, end of line, reset+comma,
	// or a stacking-count suffix like " §82.1M") so e.g. "Ultimate Wise V" doesn't get cut short at "V".
	private static final Pattern ENCHANT_TOKEN = Pattern.compile(
		"(?:§.)*([A-Za-z][A-Za-z '-]*?) ([IVXLCDM]+|[0-9]+)(?=(?:§r)?(?:,|$| §8))"
	);

	public static List<EnchantToken> findTokens(String line) {
		List<EnchantToken> tokens = new ArrayList<>();
		Matcher m = ENCHANT_TOKEN.matcher(line);
		while (m.find()) {
			String name = m.group(1).trim();
			// Real bug found (per user report — "The enchant parsing triggers on everything with roman
			// numerals"): this pattern only ever checked shape (a word run followed by a roman numeral/plain
			// number, then a real delimiter) — any non-enchant lore line that happens to end the same way
			// (e.g. some other feature's own "Combo VI"-shaped text) matched just as well as a real enchant.
			// Gated here against the user's own real, complete list of every actual Hypixel Skyblock enchant
			// name, so only a genuine enchant token is ever returned.
			if (!isKnownEnchantName(name)) continue;
			tokens.add(new EnchantToken(m.start(1), m.end(2), name, m.group(2)));
		}
		return tokens;
	}

	public static boolean isKnownEnchantName(String name) {
		return KNOWN_ENCHANT_NAMES.contains(name.toLowerCase(Locale.ROOT));
	}

	// Real, complete list of every actual Hypixel Skyblock enchant name, provided verbatim by the user —
	// replaces the old "any word run shaped like a roman numeral" heuristic findTokens used to rely on alone.
	private static final Set<String> KNOWN_ENCHANT_NAMES = Set.of(
		"absorb", "angler", "aqua affinity", "bane of arthropods", "big brain", "blast protection", "blessing",
		"bobbin' time", "bountiful", "caster", "cayenne", "champion", "chance", "charm", "cleave", "compact",
		"corruption", "counter-strike", "critical", "cubism", "cultivating", "dedication", "delicate",
		"depth strider", "divine gift", "dragon hunter", "dragon tracer", "drain", "efficiency", "ender slayer",
		"execute", "experience", "expert catches", "feather falling", "fire aspect", "fire protection",
		"first strike", "flame", "frail", "frost walker", "giant killer", "gravity", "growth",
		// Real bug found (per user report — "The enchant parsing doesnt seem to trigger on certain enchants,
		// like rejuvenate, and pyroclasm"): both are real Hypixel enchants that were simply missing from this
		// list, so isKnownEnchantName rejected them outright regardless of shape.
		"rejuvenate", "pyroclasm",
		"harvesting", "hecatomb", "ice cold", "impaling", "infinite quiver", "knockback", "lethality",
		"life steal", "looting", "luck", "lure", "magnet",
		// Per user report ("They also recently changed all mana enchants to vitality enchants"): Hypixel's
		// own rename — kept the old mana-* names alongside these too (never remove a recognized name, only
		// ever add) in case any old, un-migrated item lore still shows them.
		"hardened vitality", "strong vitality", "vampiric vitality",
		"mana pool", "mana regeneration", "mana steal", "melon tactic", "overload",
		"pesterminator", "piercing", "piscary", "power", "pristine", "projectile protection", "prosecute",
		"prosperity", "protection", "pumpkin pacer", "punch", "rainbow", "reflection", "refrigerate", "replenish",
		"respiration", "scavenger", "sharpness", "silk touch", "smart extra", "smelting touch", "smite",
		"smoldering", "sniper", "spiked hook", "sugar rush", "sunder", "syphon", "tabasco", "telekinesis",
		"the one", "thorns", "thunderbolt", "thunderlord", "titan killer", "toxophilite", "transylvanian",
		"triple-strike", "true protection", "turbo-cactus", "turbo-cane", "turbo-carrot", "turbo-cocoa",
		"turbo-melon", "turbo-mushroom", "turbo-warts", "turbo-potato", "turbo-pumpkin", "turbo-wheat",
		"vampirism", "venomous", "veterans", "vicious", "woodsplitter"
	);
}
