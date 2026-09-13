package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.ChatDeclutterFeature;
import com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocks dungeon/general chat spam by category — ported from SkyHanni's DungeonChatFilter.kt, mapped to
 * the categories requested. Every pattern below is plain text with no literal §-color-code requirements:
 * the original port kept SkyHanni's own patterns verbatim (which do require literal §-codes, since
 * SkyHanni reads chat through a source that keeps legacy formatting inline), but the event this listener
 * actually reads (ClientReceiveMessageEvents, via Component#getString()) already strips all styling — so
 * every one of those patterns could never match anything and every category silently did nothing
 * regardless of its toggle state. All patterns/literals here are the same real message text with the
 * §-codes removed.
 */
public final class DungeonChatFilter {
	// Swapped from a narrow "RARE DROP!" match to the general "X has obtained Y!" template Hypixel uses
	// for every dungeon pickup broadcast (blessings, Superboom TNT, Revive Stone, Premium Flesh, Beating
	// Heart, etc. — confirmed real via SkyHanni's DungeonChatFilter.kt, which uses this exact same
	// template for all of those) — hides all of them except an Ice Spray Wand pickup, per user request.
	private static final Pattern OBTAINED_MESSAGE = Pattern.compile(".* has obtained (?<item>.*)!");

	private static boolean isObtainingMessage(String text) {
		Matcher matcher = OBTAINED_MESSAGE.matcher(text);
		if (!matcher.matches()) return false;
		return !matcher.group("item").trim().equalsIgnoreCase("Ice Spray Wand");
	}
	private static final List<String> KEYS_AND_DOORS_LITERAL = List.of(
		"You do not have the key for this door!",
		"You have already opened this dungeon chest!",
		"This lever has already been used.",
		"This chest has already been searched!",
		"RIGHT CLICK on a WITHER door to open it. This key can only be used to open 1 door!",
		"RIGHT CLICK on the BLOOD DOOR to open it. This key can only be used to open 1 door!"
	);
	private static final List<String> SOLO_CLASS_LITERAL = List.of(
		"[NPC] Mort: Here, I found this map when I first entered the dungeon.",
		"[NPC] Mort: You should find it useful if you get lost.",
		"[NPC] Mort: Good luck.",
		"[NPC] Mort: Talk to me to change your class and ready up."
	);
	// REGEX-TEST (SkyHanni, colors stripped): "[Berserk] Melee Damage 48% -> 88%"
	private static final Pattern SOLO_CLASS_STATS = Pattern.compile("\\[(.*)] (.*) (.*) -> (.*)");
	private static final Pattern FAIRY_DIALOGUE = Pattern.compile(".* the Fairy: .*");

	// Ported from SkyHanni's DungeonBossMessages.kt: boss dialogue lines always start with a "[BOSS] "
	// prefix, then a small excluded-messages exception, then either an exact/contains/ends-with match
	// against known boss names across every Catacombs floor.
	private static final Pattern BOSS_PREFIX = Pattern.compile("\\[BOSS] (.*)");
	// Substring, not exact-line, match — both of these are genuinely useful to keep even with Hide Boss
	// Messages on ("You have proven yourself" tells you the blood room is done; "That will be enough for
	// now" is the Watcher's other real progress line), and an exact-string match is one wording tweak
	// away from silently stopping working.
	private static final List<String> BOSS_EXCLUDED_SUBSTRINGS = List.of(
		"You have proven yourself",
		"That will be enough for now"
	);
	private static final List<String> BOSS_EXACT = List.of(
		"The Crystal withers your soul as you hold it in your hands!",
		"It doesn't seem like that is supposed to go there."
	);
	private static final List<String> BOSS_CONTAINS = List.of(
		" The Watcher: ", " Bonzo: ", " Scarf:", "Professor", " Livid: ", " Enderman: ",
		" Thorn: ", " Sadan: ", " Maxor: ", " Storm: ", " Goldor: ", " Necron: ",
		" Wither King:"
	);
	private static final List<String> BOSS_ENDS_WITH = List.of(
		" Necron: That is enough, fool!",
		" Necron: Adventurers! Be careful of who you are messing with..",
		" Necron: Before I have to deal with you myself."
	);

	// Per user request: simple substring checks rather than porting SkyHanni's four color-coded blessing
	// message variants. Real phrasings confirmed: "You found a Blessing of X!" (the local player) and
	// "A Blessing of X was found!" (broadcast/another player) cover the DISCOVERY announcement — but a
	// real, separate message ("Granted you +1.22x HP and +1.22x Health Regen.") still showed through,
	// confirmed live: that's the stat-APPLICATION line, not the discovery one, and needs its own check.
	// Real bug found (per user report — a multi-stat combined line like "Granted you +29 & +1.15x
	// Intelligence and +24 Speed." wasn't hidden): startsWith("granted you") only matched if that's
	// literally the first text in the line, which fails the instant Hypixel prefixes it with anything
	// (leading whitespace, a stray formatting artifact, etc.) — contains() is a strict superset that still
	// matches every previously-confirmed case, just without requiring it to be at position 0.
	private static boolean isBlessingMessage(String text) {
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("found a blessing of") || (lower.contains("blessing of") && lower.contains("was found"))
			|| lower.contains("granted you");
	}

	// Plain text (no §-code requirement, same as every other pattern here) — the exact chat line an
	// Ultimate ability prints once its cooldown finishes, per user quote: "(Ultimate) is ready to use!
	// Press DROP to activate it!". A leading ability name varies per class, so this checks the fixed
	// tail rather than the whole line.
	private static final String ULTIMATE_READY_SUFFIX = "is ready to use! Press DROP to activate it!";

	// Per user request ("keep in mind they come for like spring and winter aswell... just remove these lines
	// but make it not search for summer/summer sloth specifically") — the seasonal-event name varies
	// (Summer/Spring/Winter/etc.), so both lines match on structure rather than the literal season word.
	private static final Pattern SEASONAL_REWARDS_UNCLAIMED = Pattern.compile("You haven't claimed your .+ Rewards yet!");
	private static final Pattern SEASONAL_REWARDS_SLOTH = Pattern.compile("Talk to the .+ Sloth in the Hub!");

	// The unclaimed-count varies per player, everything else is fixed.
	private static final Pattern EVENT_REWARDS_UNCLAIMED = Pattern.compile("You have \\d+ unclaimed event rewards?!");
	private static final String EVENT_REWARDS_CLICK_HERE = ">>> CLICK HERE to claim! <<<";
	private static final String EVENT_REWARDS_EXPIRY = "Event rewards are deleted after 10 SkyBlock years!";

	// Fruit name and profile UUID both vary per player/profile — matched structurally, same as every other
	// pattern here, per user request ("keep in mind people have other fruits as their profiles, just detect
	// the line and make it remove it even if the fruit is different").
	private static final Pattern PROFILE_PLAYING_ON = Pattern.compile("You are playing on profile: .+");
	private static final Pattern PROFILE_ID = Pattern.compile("Profile ID: [0-9a-fA-F-]+");

	// Shared by isProfileMessage below — reused (not just declared) so the fix actually applies everywhere
	// this file strips codes, per the same convention every other chat-line feature in this codebase already
	// follows (ChatCommandsFeature, PartyApi, DungeonState, etc. all .replaceAll("§.", "") before matching).
	private static final Pattern COLOR_CODE = Pattern.compile("§.");

	private static boolean isSeasonalRewardsMessage(String text) {
		return SEASONAL_REWARDS_UNCLAIMED.matcher(text).matches() || SEASONAL_REWARDS_SLOTH.matcher(text).matches();
	}

	private static boolean isEventRewardsMessage(String text) {
		// contains(), not equals() — Hypixel sends this block hand-centered with baked-in leading spaces,
		// same reasoning as isBlessingMessage's own doc comment above.
		return EVENT_REWARDS_UNCLAIMED.matcher(text.trim()).matches() || text.contains(EVENT_REWARDS_CLICK_HERE) || text.contains(EVENT_REWARDS_EXPIRY);
	}

	private static boolean isProfileMessage(String text) {
		// Real bug found (per user report — still not hiding "You are playing on profile: Coconut (Co-op)"
		// after the earlier trim() fix): trimming the padding wasn't enough, because this specific line is
		// one of the few in this file that Hypixel sends with real §-color codes baked directly into the
		// text content (coloring the profile name/game-mode portion) rather than as pure Style — so despite
		// this file's header comment claiming getString() always strips styling, the literal § characters
		// survive into `text` here and break the fixed-prefix match against a clean-string regex. Every
		// other chat-line feature in this codebase (ChatCommandsFeature, PartyApi, DungeonState, etc.)
		// defensively strips "§." before matching for exactly this reason — do the same here.
		String trimmed = COLOR_CODE.matcher(text).replaceAll("").trim();
		return PROFILE_PLAYING_ON.matcher(trimmed).matches() || PROFILE_ID.matcher(trimmed).matches();
	}

	// The GEXP and Event EXP amounts both vary per grind session; "+ N Event EXP" only appears while an
	// event is actually running, so it's optional.
	private static final Pattern GUILD_EXP_GAIN =
		Pattern.compile("You earned [\\d,]+ GEXP(?: \\+ [\\d,]+ Event EXP)? from playing SkyBlock!");

	// Per user request ("'lowballing' chat de-clutter filter should hide 'lowball', 'lowaball',
	// 'ballinglow/balling low', 'lb' ... just stuff that could be misspelled and such aswell. This should
	// only happen in public chat, never party or guild or private"): four spelling/wording variants of the
	// same "don't lowball me" callout real players type, plus a bare "lb" abbreviation gated on a nearby
	// number (a purse-balance callout like "lb 500k" always pairs the abbreviation with a number, unlike an
	// unrelated "lb" — e.g. "last breath" abbreviated the same way, which never does).
	//
	// Follow-up expansion ("Expand lowballing filter further: 'lobwalling/lobwaling', 'lbing', 'lowbaling',
	// plus a '(number) lb' pattern (digits, 'mil', 'M', or 'B' before 'lb')"): three more misspellings added
	// to the plain-substring list, plus a SECOND number+lb pattern that matches the number coming BEFORE the
	// abbreviation ("500k lb"/"2 mil lb"/"1.5B lb") — the original LOWBALL_LB_PATTERN only ever matched a
	// number AFTER "lb" ("lb 500k"), so a purse-callout phrased the other way around slipped through.
	private static final Pattern LOWBALL_LB_PATTERN = Pattern.compile("(?i)\\blb\\b\\s*:?\\s*\\d");
	private static final Pattern LOWBALL_NUMBER_BEFORE_LB_PATTERN =
		Pattern.compile("(?i)\\d[\\d.,]*\\s*(?:k|m|b|mil|mill|million|bil|billion)?\\s*lb\\b");

	// Per user request ("55m purse lb", "lb taking most 20m+", "LF LB" — "Basically anything that contains
	// lb and numbers should get cancelled"): the two patterns above only ever matched a number IMMEDIATELY
	// adjacent to "lb" — "lb taking most 20m+" has other words in between, so it slipped through. This is a
	// much broader catch-all: any standalone "lb" word anywhere in the message combined with any digit
	// anywhere else in it (not required to be adjacent), plus a bare "LF LB"/"LB" request with no digit at
	// all. LOWBALL_LB_WORD/ANY_DIGIT deliberately supersede the two adjacent-only patterns above (kept for
	// clarity, not removed) rather than replacing them outright.
	private static final Pattern LOWBALL_LB_WORD = Pattern.compile("(?i)\\blb\\b");
	private static final Pattern ANY_DIGIT = Pattern.compile("\\d");
	private static final Pattern LF_LB_PATTERN = Pattern.compile("(?i)\\blf\\s*lb\\b");

	private static boolean isLowballMessage(String text) {
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("lowball") || lower.contains("lowaball") || lower.contains("ballinglow") || lower.contains("balling low")
			|| lower.contains("lobwalling") || lower.contains("lobwaling") || lower.contains("lbing") || lower.contains("lowbaling")) {
			return true;
		}
		if (LF_LB_PATTERN.matcher(text).find()) return true;
		if (LOWBALL_LB_WORD.matcher(text).find() && ANY_DIGIT.matcher(text).find()) return true;
		if (LOWBALL_LB_PATTERN.matcher(text).find() || LOWBALL_NUMBER_BEFORE_LB_PATTERN.matcher(text).find()) return true;
		return isObfuscatedLowballWord(text);
	}

	// Per user request ("[287] [VIP+] Ayko3: 3b lowabbll vissit me" — "words containing key letters from
	// lowballing (l, o, w, b, a) should be blocked. Only if its within a word or maybe two however, since
	// otherwise it just blocks a lot of sentences that are normal"): catches deliberately-garbled spellings
	// (like "lowabbll") that don't match any of the fixed misspelling list above — any single real word (or
	// two adjacent words concatenated, since spammers sometimes break the garbled word across a space) that
	// contains all five of l/o/w/b/a is flagged. A length cap on both checks keeps this from ever matching
	// across an entire long sentence at once, which is what would turn this into a generic false-positive
	// machine per the user's own explicit caveat.
	private static final char[] LOWBALL_LETTERS = {'l', 'o', 'w', 'b', 'a'};
	private static final int LOWBALL_WORD_MAX_LENGTH = 12;
	private static final int LOWBALL_WORD_PAIR_MAX_LENGTH = 16;

	private static boolean containsAllLowballLetters(String word) {
		String lower = word.toLowerCase(java.util.Locale.ROOT);
		for (char letter : LOWBALL_LETTERS) if (lower.indexOf(letter) < 0) return false;
		return true;
	}

	private static boolean isObfuscatedLowballWord(String text) {
		String[] words = text.split("[^A-Za-z]+");
		for (int i = 0; i < words.length; i++) {
			String word = words[i];
			if (word.length() >= 5 && word.length() <= LOWBALL_WORD_MAX_LENGTH && containsAllLowballLetters(word)) return true;
			if (i + 1 < words.length) {
				String pair = word + words[i + 1];
				if (pair.length() >= 5 && pair.length() <= LOWBALL_WORD_PAIR_MAX_LENGTH && containsAllLowballLetters(pair)) return true;
			}
		}
		return false;
	}

	// Real bug found: the lowball filter used to run unconditionally against every line this listener sees
	// (dungeon boss messages, drop broadcasts, AND real chat alike), with no channel check at all — so it
	// silently also stripped "lowballing"/"lb 500k" out of Party/Guild/whisper messages, the exact channels
	// the user explicitly said it should never touch. Same "Party > "/"Guild > "/"From " near-start marker
	// check ChatCommandsFeature already uses to tell channels apart — public/global chat is just whatever's
	// left once none of those three match.
	private static boolean isPublicChatLine(String text) {
		int partyIdx = text.indexOf("Party > ");
		int guildIdx = text.indexOf("Guild > ");
		int fromIdx = text.indexOf("From ");
		return !(partyIdx >= 0 && partyIdx <= 2) && !(guildIdx >= 0 && guildIdx <= 2) && !(fromIdx >= 0 && fromIdx <= 2);
	}

	private static boolean registered = false;

	private DungeonChatFilter() {}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			String text = message.getString();

			// Real bug found (per user report — "The profile hider still doesn't hide this: 'You are
			// playing on profile: Coconut (Co-op)'"): this whole listener used to bail out on ANY overlay
			// (action-bar) message before running a single check, but Hypixel's own profile-switch
			// confirmation can arrive as a transient action-bar message rather than a normal chat line
			// depending on how the switch was triggered — so the profile check never even got a chance to
			// run for that delivery path. Checked here, before the overlay bail-out, so it catches both;
			// every other declutter check below stays chat-only exactly as before.
			ChatDeclutterFeature declutter = declutter();
			if (declutter != null && declutter.isEnabled() && declutter.isProfileMessage() && isProfileMessage(text)) return false;

			// Real bug found (per user report -- "Oruo hider doesn't hide lines after the room is cleared...
			// still shows a lot of '[STATUE] Oruo the Omniscient: You've already proven enough to me! No need
			// to press more of my buttons!'"): unlike Oruo's actual quiz-question dialogue (normal chat lines),
			// this specific "stop clicking me" response to further interaction after the puzzle is solved is
			// Hypixel's action-bar overlay message -- the same delivery path the profile-switch confirmation
			// above was already special-cased for. Every check below this point bails out on `overlay` before
			// ever running, so this line silently never reached DungeonDeclutterFeature.shouldHideMessage at
			// all. Checked here for the same reason as the profile check just above it.
			DungeonDeclutterFeature dungeonDeclutterEarly = dungeonDeclutter();
			if (dungeonDeclutterEarly != null && dungeonDeclutterEarly.shouldHideMessage(text)) return false;

			if (overlay) return true;

			if (declutter != null && declutter.isEnabled()) {
				if (declutter.isRareDrops() && isObtainingMessage(text)) return false;
				if (declutter.isKeysAndDoors() && KEYS_AND_DOORS_LITERAL.contains(text)) return false;
				if (declutter.isDupedClassStats() && SOLO_CLASS_LITERAL.contains(text)) return false;
				if (declutter.isSoloClassBuffedStats() && SOLO_CLASS_STATS.matcher(text).matches()) return false;
				if (declutter.isFairyDialogue() && FAIRY_DIALOGUE.matcher(text).matches()) return false;
				// Per user request ("Hide boss messages subtoggle should hide morts too"): Mort's dialogue
				// lines (already checked under isDupedClassStats() below, unchanged) also get hidden when
				// Boss Messages alone is on, without requiring the separate class-stats toggle.
				if (declutter.isBossMessages() && (isBossMessage(text) || SOLO_CLASS_LITERAL.contains(text))) return false;
				if (declutter.isBlessingMessages() && isBlessingMessage(text)) return false;
				if (declutter.isHideKeys() && isKeyMessage(text)) return false;
				if (declutter.isHideGrandmaWolfCombo() && text.contains("Kill Combo")) return false;
				if (declutter.isHideUltimateReady() && text.contains(ULTIMATE_READY_SUFFIX)) return false;
				if (declutter.isSeasonalRewardsMessage() && isSeasonalRewardsMessage(text)) return false;
				if (declutter.isEventRewardsMessage() && isEventRewardsMessage(text)) return false;
				if (declutter.isHideLowballers() && isPublicChatLine(text) && isLowballMessage(text)) return false;
				if (declutter.isHideGuildExpGain() && GUILD_EXP_GAIN.matcher(text).matches()) return false;
			}

			return true;
		});
	}

	// Case-insensitive — Blood key worked but Wither key didn't, which points at a capitalization
	// mismatch ("Wither Key" vs the previously hardcoded lowercase "Wither key") rather than the phrase
	// being wrong outright.
	private static boolean isKeyMessage(String text) {
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("has obtained blood key!") || lower.contains("has obtained wither key!");
	}

	private static boolean isBossMessage(String text) {
		if (BOSS_EXCLUDED_SUBSTRINGS.stream().anyMatch(text::contains)) return false;
		if (BOSS_EXACT.contains(text)) return true;
		if (!BOSS_PREFIX.matcher(text).matches()) return false;
		return BOSS_CONTAINS.stream().anyMatch(text::contains) || BOSS_ENDS_WITH.stream().anyMatch(text::endsWith);
	}

	private static ChatDeclutterFeature declutter() {
		return FeatureRegistry.get("chat_declutter") instanceof ChatDeclutterFeature f ? f : null;
	}

	private static DungeonDeclutterFeature dungeonDeclutter() {
		return FeatureRegistry.get("dungeon_declutter") instanceof DungeonDeclutterFeature f ? f : null;
	}
}
