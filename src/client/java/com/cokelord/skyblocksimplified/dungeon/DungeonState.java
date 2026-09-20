package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonScan;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.hud.TabListReader;
import com.cokelord.skyblocksimplified.item.RomanNumeralUtil;
import com.cokelord.skyblocksimplified.util.IslandGate;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Current dungeon-run state: floor, boss-fight gate, teammates, and run stats — the Java equivalent of
 *  Odin's combined {@code DungeonUtils.kt}/{@code DungeonListener.kt} facade, trimmed to what the
 *  currently-ported feature set needs. Reads SBAR's existing {@link TabListReader}/{@link IslandGate}
 *  instead of raw packet listeners, since those already expose the same underlying tab-list text Odin
 *  taps via {@code ClientboundPlayerInfoUpdatePacket}. Stats are polled once per tick (see {@link #tick()},
 *  called from {@link WorldScan}'s own tick handler) rather than event-driven — a handful of regex passes
 *  over a short tab list is cheap enough not to need push invalidation, matching this codebase's general
 *  tick-polling convention.
 *
 *  <p>Call {@link #register()} once at client init (alongside {@link WorldScan#register()}) to wire up the
 *  chat-based tracking (party kill announcements, door-open attribution) that can't be tick-polled. */
public final class DungeonState {
	// Cached rather than the sidebar-stripping loop below using String.replaceAll's own implicit per-call
	// Pattern.compile — that loop runs every single tick regardless of state (see its own doc comment), over
	// every current sidebar line, so this was a fresh regex compile per line, every tick, for the entire time
	// a dungeon-adjacent screen was open. Same regex, just built once instead of every tick.
	private static final Pattern COLOR_CODE = Pattern.compile("§.");
	private static final Pattern FLOOR_PATTERN = Pattern.compile("The Catacombs \\(([EFM]\\d*)\\)$");
	// Tab-list teammate line, e.g. " [42] Steve ✏ (Archer IV)" / "... (DEAD)" — ported from Odin's tablistRegex.
	// Real bug found (per user report — "The mod warns it cant find any information about the class in the
	// tablist when in dungeons. It only needs to detect the line which contains the username of the player,
	// then search the same line for any class names"): this used to be a single FULLY-ANCHORED (^...$) regex
	// requiring the entire line to match one exact shape end-to-end — the same class of bug this file's own
	// FLOOR_PATTERN and SelfClassCache's CURRENTLY_SELECTED_PATTERN/SIDEBAR_CLASS_PATTERN were each already
	// fixed for (a trailing color/reset artifact, or any real-server line-shape variation this codebase never
	// actually confirmed, silently fails the WHOLE match instead of just not capturing that part). Split into
	// two independent, unanchored steps per the user's own exact spec: TEAMMATE_NAME_PATTERN finds just the
	// username prefix, then TEAMMATE_STATUS_PATTERN is searched separately anywhere in that SAME line for
	// either a real class name (optionally followed by a roman-numeral level) or the literal DEAD marker.
	private static final Pattern TEAMMATE_NAME_PATTERN = Pattern.compile("^\\[\\d+] (?:\\[\\S+] )*(\\w{1,16})");
	private static final Pattern TEAMMATE_STATUS_PATTERN = Pattern.compile(
		"\\((DEAD|Healer|Mage|Tank|Archer|Berserk)(?: ([IVXLCDM]+))?\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SECRET_PERCENT_PATTERN = Pattern.compile("^ Secrets Found: ([\\d.]+)%$");
	private static final Pattern SECRET_COUNT_PATTERN = Pattern.compile("^ Secrets Found: (\\d+)$");
	private static final Pattern COMPLETED_ROOMS_PATTERN = Pattern.compile("^ Completed Rooms: (\\d+)$");
	// Real, confirmed sidebar line ("Cleared: 45% (123)", the trailing parenthesized number discarded) —
	// cross-referenced against devonian's own Dungeons.kt clearedPercentRegex, a DIFFERENT line from
	// " Completed Rooms: N" above (that one's a live per-run counter; this one's the floor-clear percentage
	// used for the dungeon score calculator's total-room-count estimate).
	private static final Pattern CLEARED_PERCENT_PATTERN = Pattern.compile("^Cleared: (\\d+)% \\(\\d+\\)$");
	private static final Pattern OPENED_ROOMS_PATTERN = Pattern.compile("^ Opened Rooms: (\\d+)$");
	private static final Pattern PUZZLE_COUNT_PATTERN = Pattern.compile("^Puzzles: \\((\\d+)\\)$");
	private static final Pattern DEATHS_PATTERN = Pattern.compile("^Team Deaths: (\\d+)$");
	private static final Pattern CRYPT_PATTERN = Pattern.compile("^ Crypts: (\\d+)$");
	private static final Pattern TIME_PATTERN = Pattern.compile("^ Time: ((?:\\d+h ?)?(?:\\d+m ?)?\\d+s)$");
	private static final Pattern PUZZLE_STATUS_PATTERN = Pattern.compile("^ (\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?$");
	private static final Pattern PARTY_MESSAGE_PATTERN = Pattern.compile("^Party > .*?: (.+)$");
	private static final Pattern DOOR_OPEN_PATTERN = Pattern.compile("^(?:\\[\\w+] )?(\\w+) opened a (?:WITHER|Blood) door!");
	// Marks the start of the real Watcher fight window — see watcherGateActive's own field doc comment for
	// why this needed its own tracked flag.
	// Real bug found (per user report — "the bossbar still does not show in the blood room... its a regex
	// issue or a rendering issue most likely"): this line's exact real capitalization/trailing punctuation
	// was never independently confirmed against a real captured chat log — every usage of it across this
	// codebase (here, DoorHighlightFeature) traces back to the SAME original, unverified guess, so a wrong
	// assumption here would silently break all of them the same way, not just this one gate. Hardened the
	// same way every other chat-line detector in this project already has to be for exactly this class of
	// bug: case-insensitive (in case the real line's capitalization differs from this guess) and no longer
	// anchored at the end (a real trailing artifact — extra punctuation, unexpected trailing text — would
	// otherwise silently fail the whole match the same way it has for other lines elsewhere in this file).
	private static final Pattern BLOOD_DOOR_OPEN_PATTERN = Pattern.compile("^The BLOOD DOOR has been opened!", Pattern.CASE_INSENSITIVE);
	private static final Pattern DEATH_PATTERN = Pattern.compile("☠ (\\w{1,16}) .* and became a ghost\\.");
	// Real, confirmed per-floor boss-entry lines (already used by SplitsFeature to trigger its own boss
	// splits) — indices 0-6 correspond to floors 1-7. This is the sole signal isInBoss() is now based on
	// (see the reset()-adjacent isInBoss computation for why an old X/Z-coordinate heuristic was removed).
	// Anchored only at the start (not the end, and matched with find() below rather than matches()) — per
	// user report ("entering the boss is STILL not detected"): the message text itself is confirmed
	// correct against four real mods' own copies (SkyHanni's own REGEX-TEST comment includes the raw
	// formatted string), so a full-line exact match failing would most likely mean something trails the
	// confirmed text (stray whitespace, a hidden formatting artifact getString() didn't fully strip) that
	// a `$`-anchored matches() call would silently reject outright with no fallback.
	private static final Pattern[] BOSS_ENTRY_PATTERNS = {
		Pattern.compile("^\\[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable\\."),
		Pattern.compile("^\\[BOSS] Scarf: This is where the journey ends for you, Adventurers\\."),
		Pattern.compile("^\\[BOSS] The Professor: I was burdened with terrible news recently\\.\\.\\."),
		Pattern.compile("^\\[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!"),
		Pattern.compile("^\\[BOSS] Livid: Welcome, you've arrived right on time\\. I am Livid, the Master of Shadows\\."),
		Pattern.compile("^\\[BOSS] Sadan: So you made it all the way here\\.\\.\\. Now you wish to defy me\\? Sadan\\?!"),
		// Real bug found (per repeated user report — "STILL isnt being detected" — after two earlier
		// rounds already tried the exact-taunt-line approach and an entity-scan fallback): requiring this
		// ONE specific taunt line is fragile against anything trailing/differing in the exact text. Per
		// direct user request, this now matches ANY line Maxor says at all ("detect '[BOSS] Maxor:' case
		// insensitive so it detects all of those lines... the first one means the boss is active") — Maxor
		// says several different lines over the fight, but every single one is still a real, unambiguous
		// "the Maxor phase is happening right now" signal, and the FIRST one received is exactly the
		// "boss just started" moment this needs. Case-insensitive per the same request, in case Hypixel's
		// exact capitalization on any given line ever differs from what's been captured so far.
		Pattern.compile("^\\[BOSS] Maxor:", Pattern.CASE_INSENSITIVE),
	};
	// F7's Goldor phase (P3) terminal room: 4 progressively-unlocked sections (S1-S4 in this project's own
	// party lingo), each with its own set of terminals/levers/device that must all finish before the next
	// section opens. Real, confirmed chat lines — cross-referenced against two independent real mods:
	// SkyHanni's DungeonBossApi.kt (F7_GOLDOR_1..5 phase enum, goldorStartPattern/goldorTerminalPattern/
	// goldor5StartPattern) and Skyblocker's GoldorWaypointsManager.java (TERMINALS_START/TERMINAL_ACTIVATED/
	// DEVICE_ACTIVATED/LEVER_ACTIVATED/CORE_ENTRANCE), which independently agree on all of these lines.
	// Storm's own death line fires first and is Skyblocker's chosen "terminals started" signal; Goldor's own
	// intro line follows immediately after — both are accepted as the S1-start signal (whichever arrives)
	// for the same reliability-via-redundancy reason DungeonState already uses elsewhere (see
	// checkForMaxorEntity()'s doc comment).
	// Fourth (well, third — this predates checkForMaxorEntity()'s own entity fallback and fires earlier than
	// either it or the Maxor chat line) signal for "the F7 boss fight has started", per user request ("It
	// STILL doesnt detect entering the bossfight... make it detect '...You have proven yourself. You may
	// pass.' and after that make it watch for '...WELL! WELL! WELL! LOOK WHO'S HERE!'"): this Watcher line
	// is said the instant the Blood Camp gate opens, unconditionally, well before the player has necessarily
	// walked far enough to trigger Maxor's own greeting or for the Maxor entity to be in render range —
	// already confirmed real and reliable enough that SplitsFeature uses this exact same line as its own
	// "Portal Entry" split. Checked FIRST in onChatMessage below; the Maxor line/entity checks remain as
	// they were, so entry is confirmed by whichever of the three signals fires first.
	private static final Pattern WATCHER_PORTAL_ENTRY_PATTERN = Pattern.compile("^\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.?");
	private static final Pattern GOLDOR_STORM_END_PATTERN = Pattern.compile("^\\[BOSS] Storm: I should have known that I stood no chance\\.$");
	private static final Pattern GOLDOR_START_PATTERN = Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");
	// F7Phase chat-based detection — real, user-confirmed lines (a live in-game capture, not a guess) for
	// each phase's own opening line: Maxor's is already BOSS_ENTRY_PATTERNS[6] (floor 7's boss-entry line).
	// See computeF7Phase()'s own doc comment for why these take priority over the old Y-height heuristic
	// instead of just supplementing it.
	// Same "match any line from this boss, case-insensitive, first one wins" fix as BOSS_ENTRY_PATTERNS'
	// Maxor entry above, per the same user request — used only for f7ChatPhase's P2/P3 transition below,
	// NOT for terminalSection tracking (that still needs GOLDOR_START_PATTERN/GOLDOR_STORM_END_PATTERN's
	// specific exact lines, since "Goldor said literally anything" isn't a meaningful section-boundary
	// signal the way it is for "which phase is the fight in").
	private static final Pattern STORM_ANY_LINE = Pattern.compile("^\\[BOSS] Storm:", Pattern.CASE_INSENSITIVE);
	private static final Pattern GOLDOR_ANY_LINE = Pattern.compile("^\\[BOSS] Goldor:", Pattern.CASE_INSENSITIVE);
	// "<name> activated a terminal! (N/M)" / "<name> activated a lever! (N/M)" / "<name> completed a
	// device! (N/M)" — current==total on any of these three means the CURRENT section's full set (terminals
	// + levers + device) just finished, not that specific item alone. Deliberately generic (current==total)
	// rather than hardcoding each section's real total (SkyHanni's own source confirms sections use either
	// 7 or 8 total, varying by how many terminals that section has) — matching current==total is what
	// SkyHanni's own DungeonBossApi.kt does, and avoids needing to memorize/hardcode which section has which
	// total.
	private static final Pattern GOLDOR_PROGRESS_PATTERN = Pattern.compile("^.{1,16} (?:activated a (?:terminal|lever)|completed a device)! \\((\\d+)/(\\d+)\\)$");
	private static final String GOLDOR_CORE_ENTRANCE_LINE = "The Core entrance is opening!";
	// Real, confirmed line (devonian's CroesusChestCounter.kt calls it squadWipeRegex) that fires both on a
	// full team wipe and on a normal dungeon completion — either way "the terminal phase" stops being a
	// meaningful concept, so this is an unconditional, floor-independent reset trigger.
	//
	// Real bug found (per user report — "Its still not detecting leaving the dungeon/bossfight. Im in the
	// dungeon hub with the splits and map showing."): this used to be a full-line String#equals() — the
	// single most fragile match in this whole file, more so than every OTHER chat pattern here, which are
	// all tolerant regexes for exactly this reason. The Dungeon Hub itself is still structurally part of
	// the same Catacombs instance from IslandGate/the Mod API's point of view (isInDungeon() correctly
	// stays true there), so this exact chat line — sent once, ~10 real seconds before the player actually
	// lands back in it — is the ONLY reliable "the run is really over" signal this file has for that
	// transition; any mismatch on Hypixel's exact wording (a slightly different second count, punctuation,
	// case) meant it silently never fired at all, leaving every downstream reader (Splits, the map, inBoss)
	// stuck showing the finished run indefinitely. Matched as a tolerant substring now, same as this file's
	// other real chat signals.
	private static final Pattern DUNGEON_INSTANCE_CLOSING_PATTERN = Pattern.compile("(?i)instance will close");

	private static String floorLabel = null;
	private static int floorNumber = -1;
	// Per user report ("if I join a new dungeon from the dungeon without dying/going to the lobby...
	// running the command again just leaves it all as the old dungeon, including the map"): bumped every
	// time a genuinely NEW run's floor is detected (including the very first one each session) — see the
	// floor-detection block in tick() below. Features that only reset on isInDungeon() going false->true
	// (e.g. DoorHighlightFeature's own key/door-box cache) miss a direct dungeon-to-dungeon rejoin, since
	// isInDungeon() never actually goes false for that transition; comparing against this instead catches
	// it regardless of whether that boolean ever flipped. Exposed via {@link #getRunId()}.
	private static int runId = 0;
	private static boolean inBoss = false;
	// Real bug found (per user report — "The bossbar has started showing in regular dungeons, not
	// specifically when blood has been opened or when the user is in the bossfight"): isInBoss() only
	// becomes true once the Watcher is actually DEFEATED (WATCHER_PORTAL_ENTRY_PATTERN, "You may pass" —
	// the portal opening) — the real Watcher FIGHT itself (blood door already open, Watcher alive, being
	// fought) happens entirely BEFORE that and is therefore NOT covered by isInBoss() at all (see
	// BloodCampFeature's own inClear() doc comment, which independently confirms this same gap). This tracks
	// that missing window directly: true from the confirmed Blood Door open line until the Watcher is
	// cleared (or the run ends), so a consumer that means "show only during blood/boss, never an ordinary
	// room" can check {@code isInBoss() || isWatcherGateActive()} instead of the much broader isInDungeon().
	private static boolean watcherGateActive = false;
	// Chat-confirmed F7Phase, UNKNOWN until one of the real phase-start lines fires — see
	// computeF7Phase()'s own doc comment.
	private static F7Phase f7ChatPhase = F7Phase.UNKNOWN;
	private static boolean bossEntryMessageSeen = false;
	// Per user report ("the invincibility timer shows up after killing all blood mobs. It should show when
	// entering boss... same with the map, it just stops showing when the blood mobs are killed. It should
	// show the boss when entering the boss"): bossEntryMessageSeen/inBoss above are intentionally the
	// EARLIEST possible "boss encounter has started" signal (the Watcher's portal line, said the instant
	// the Blood Door opens — added per an earlier explicit user request for exactly that reliability), which
	// is genuinely useful for some things (Splits' own "Portal Entry" split already keys off the identical
	// line) but reads as "way too early" for anything meant to represent the REAL boss fight actually being
	// visible/happening — the player is often still walking from the blood door to Maxor's room at that
	// point. This tracks the narrower, later signal instead: the per-floor boss's own real greeting line
	// (BOSS_ENTRY_PATTERNS) or the F7 Maxor-entity fallback — see isBossFightVisuallyActive().
	private static boolean bossGreetingSeen = false;
	// Per user report ("The mod STILL has issues detecting leaving a dungeon. Doing stuff like /warp
	// dungeonhub still shows the map and stuff. It should search every second to see if a player is still
	// in a dungeon or not"): real bug — Dungeon Hub is part of the SAME "dungeon" island as an actual
	// Catacombs run per Hypixel's own Mod API, so isInDungeon() never goes false for a /warp dungeonhub
	// transition at all, meaning the whole reset branch at the top of tick() never even runs for it. The
	// floor label itself ("The Catacombs (FN)") is the one signal that actually distinguishes an ACTIVE RUN
	// from the hub/lobby area of that same island — already scanned every tick below, just never checked
	// for DISAPPEARING. Tracks consecutive ~1-second checks that found no floor label anywhere in tab
	// list/sidebar while a run was active; a few straight misses (a real, sustained absence a mid-rebuild
	// sidebar flicker wouldn't survive) means the run is really over.
	private static int floorLabelCheckTickCounter = 0;
	private static final int FLOOR_LABEL_CHECK_INTERVAL_TICKS = 20; // ~1 real second, just throttles the scan itself
	// Real bug found (per user report — "When the server freezes the map resets like I would leave the
	// dungeon"): a real Hypixel TPS/lag spike (common, and usually resolves within a few seconds on its own)
	// can leave tab list/sidebar packets stale or briefly blank client-side the same way a genuine
	// /warp dungeonhub leave does — this check had no way to tell the two apart. It also used to count
	// consecutive ~1-tick-interval "misses" rather than real elapsed time, which is fragile right around a
	// freeze: Minecraft can process a burst of queued ticks back-to-back once a freeze actually ends,
	// letting several "misses" get counted well within one real second. Tracked by wall-clock duration now
	// instead — the floor label has to be missing continuously for this long, regardless of how many ticks
	// that spans, before it's treated as a real leave. 15 seconds comfortably outlasts an ordinary lag spike;
	// a real leave (which never recovers) still eventually resets, just not within the first few seconds of
	// what might only be lag.
	private static final long FLOOR_LABEL_MISSING_RESET_MILLIS = 15_000L;
	private static long floorLabelMissingSinceMillis = 0L;
	// Real bug found (per user report — "i joined a dungeon from another dungeon and my starred mob esp
	// doesnt work and my dungeon map is gone"): the floor-change reset above only fires when the newly
	// detected floor NUMBER differs from the currently-known one — a same-floor-type rejoin (e.g. leaving
	// one F7 run straight into another F7 run via a party leader's /joininstance, no hub stop in between)
	// keeps reporting the identical floor number, so that branch never sees a difference and never resets
	// anything: DungeonScan's tiles/rooms/doors, WorldScan's room cache, and every other per-run cache all
	// stay stuck on the PREVIOUS run's data. Same underlying transition IslandGate already treats as
	// unambiguous ground truth for its own area cache (see its own resetOnLevelChange()) — Hypixel's
	// dungeon instances are separate backend worlds, so any dungeon-to-dungeon (or dungeon-to-hub) jump
	// swaps out the client's whole ClientLevel object, a hard Java reference change with zero flicker risk,
	// unlike the sidebar/tab-list text this class otherwise has to stay cautious about. Tracked here
	// independently of IslandGate's own copy since the two classes intentionally don't share state.
	private static Object lastLevelObj = null;

	private static final List<DungeonPlayer> teammates = new ArrayList<>(5);
	private static int secretsFound = 0;
	private static float secretsPercent = 0f;
	private static int knownSecrets = 0;
	private static int crypts = 0;
	private static int openedRooms = 0;
	private static int completedRooms = 0;
	private static int clearedPercent = 0;
	private static int deaths = 0;
	// Per user request ("On the first death, it should pull api data about the player that died and if they
	// have a legendary spirit pet in their pets menu the death should only be a -1"): real Hypixel dungeon
	// score rule — only the FIRST death of a run is ever eligible for the reduced penalty, and only if that
	// specific player owns a Legendary Spirit Pet (confirmed via the Hypixel wiki's own Dungeon Score page).
	// Kicks off the real name->UUID->profile lookup chain (MojangApi/SkyblockStatsApi, same pattern Party
	// Finder already uses) the moment the first death happens, so the data has time to arrive before the
	// score is actually checked.
	private static String firstDeathPlayerName = null;
	private static String elapsedTime = "0s";
	private static int puzzleCount = 0;
	private static String doorOpener = "Unknown";
	private static boolean mimicKilled = false;
	private static boolean princeKilled = false;
	private static boolean batKilled = false;

	private static boolean registered = false;

	private DungeonState() {}

	// Real bug found (per user report — "door highlight isnt highlighting the doors anymore"): the "once
	// floorNumber is confirmed, don't trust a bare IslandGate sidebar-flicker false negative alone" leniency
	// (see tick()'s own extended doc comment for the full history) was only ever applied inside THIS class's
	// own tick()/onChatMessage() gates — every OTHER feature that reads isInDungeon() directly (Door
	// Highlight, Mimic, the puzzle solvers, etc.) still called straight through to the still-fragile
	// IslandGate.isInDungeon(), fully exposed to the exact same flicker every one of those internal gates
	// was specifically fixed to ignore. Centralizing the same trust here fixes it for every caller uniformly
	// instead of needing the identical fix duplicated into each feature separately.
	public static boolean isInDungeon() {
		if (floorNumber >= 0) return true;
		return IslandGate.isInDungeon();
	}

	public static boolean isInBoss() {
		return inBoss;
	}

	/** See {@link #watcherGateActive}'s own field doc comment. */
	public static boolean isWatcherGateActive() {
		return watcherGateActive;
	}

	/** Narrower than {@link #isInBoss()}: true only once the boss's own real greeting line (or, for F7, the
	 *  Maxor entity itself) has actually been seen — not just the earlier "boss encounter has started"
	 *  signal isInBoss()/inBoss uses (see bossGreetingSeen's own field doc comment). Use this for anything
	 *  meant to represent the real boss fight being visually in progress (Invincibility Timer's "only in
	 *  boss", Dungeon Map's "Show in Boss") rather than general dungeon-state tracking. */
	public static boolean isBossFightVisuallyActive() {
		return inBoss && bossGreetingSeen;
	}

	public static int getFloorNumber() {
		return floorNumber;
	}

	public static String getFloorLabel() {
		return floorLabel;
	}

	/** Bumped once per genuinely new dungeon run (see runId's own doc comment) — a feature that only clears
	 *  its own per-run cache on isInDungeon() going false-&gt;true can instead cache the value from its last
	 *  reset and compare here, catching a same-session dungeon-to-dungeon rejoin too. */
	public static int getRunId() {
		return runId;
	}

	public static boolean isFloor(int... numbers) {
		for (int n : numbers) if (n == floorNumber) return true;
		return false;
	}

	public enum F7Phase { P1, P2, P3, P4, P5, UNKNOWN }

	/** Current phase of the F7 boss fight, by player Y height — ported from Odin's {@code getF7Phase()}. */
	private static F7Phase lastLoggedF7Phase = F7Phase.UNKNOWN;

	public static F7Phase getF7Phase() {
		F7Phase phase = computeF7Phase();
		if (phase != lastLoggedF7Phase) {
			lastLoggedF7Phase = phase;
		}
		return phase;
	}

	/** Real bug found (per user report — "it doesnt seem to detect when each phase of the boss starts"):
	 *  this used to be Y-height-only. Confirmed real, user-captured chat lines for each phase's own start
	 *  (Maxor's boss-entry line, Storm's intro right after Maxor dies, Goldor's terminal-start line — the
	 *  same one terminalSection already tracks below) are a far more reliable signal than a height threshold
	 *  (affected by knockback, falling, room layout assumptions, etc.). Chat confirmation is now
	 *  authoritative once it's fired at all (f7ChatPhase != UNKNOWN); the Y-height check only remains as a
	 *  fallback for the moment BEFORE any phase-start line has been seen yet this fight (e.g. reconnecting
	 *  mid-fight, having missed the line). P4/P5 have no known confirmed chat line, so they stay
	 *  height-only regardless. */
	private static F7Phase computeF7Phase() {
		if (!isFloor(7) || !inBoss) return F7Phase.UNKNOWN;
		if (f7ChatPhase != F7Phase.UNKNOWN) return f7ChatPhase;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return F7Phase.UNKNOWN;
		double y = mc.player.getY();
		if (y > 210) return F7Phase.P1;
		if (y > 155) return F7Phase.P2;
		if (y > 100) return F7Phase.P3;
		if (y > 45) return F7Phase.P4;
		return F7Phase.P5;
	}

	/** Which of F7 Goldor's 4 progressively-unlocked terminal sections is currently active — NONE outside
	 *  the terminal phase entirely (including before it starts and during pre-device work in earlier phases,
	 *  see PositionalMessagesFeature's per-message section gating for why this matters). */
	public enum TerminalSection { NONE, S1, S2, S3, S4 }

	private static TerminalSection terminalSection = TerminalSection.NONE;
	// Real bug found (per user report — "terminals timer is STILL starting too early... it starts after
	// storm is dead, and not when terminals are actually able to be opened"): terminalSection above
	// deliberately starts on WHICHEVER of Storm's own death line or Goldor's greeting line fires first (see
	// its own doc comment for why — a general "roughly in the terminal phase" signal for consumers like
	// InactiveWaypointsFeature), and Storm's death line always fires first in the real sequence (Storm dies
	// -> death line -> Goldor spawns/walks in -> his own greeting line -> terminals become clickable some
	// time after that). DungeonTimersFeature's countdown specifically needs the LATER, more accurate anchor
	// (Goldor's own greeting), not the general terminalSection signal, so that's tracked separately here
	// instead of changing terminalSection's own intentionally-early-and-redundant semantics for everyone
	// else that reads it.
	private static Long goldorGreetingSeenAtMillis = null;
	// Real bug found (per user report — "the terminals countdown is STILL too early. It should count down
	// until the Terminals split starts, which is like 5.7 seconds after storm dies"): the Goldor-greeting
	// anchor above (plus DungeonTimersFeature's own 3s countdown) still isn't the real signal the user wants
	// timed off — they've confirmed by direct in-game timing that the real "terminals become clickable"
	// moment is a fixed 5.7s after STORM'S OWN death line specifically, not Goldor's later greeting. Tracked
	// separately from goldorGreetingSeenAtMillis since some other line-order edge case might still need the
	// old anchor; this is the one DungeonTimersFeature's countdown now uses.
	private static Long stormDeathSeenAtMillis = null;

	public static TerminalSection getTerminalSection() { return terminalSection; }

	/** Wall-clock timestamp Goldor's own "Who dares trespass into my domain?" line was seen this run, or
	 *  null if it hasn't happened yet — see {@link #goldorGreetingSeenAtMillis}'s doc comment for why this
	 *  exists separately from {@link #getTerminalSection()}. */
	public static Long getGoldorGreetingSeenAtMillis() { return goldorGreetingSeenAtMillis; }

	/** Wall-clock timestamp Storm's own death line ("I should have known that I stood no chance.") was seen
	 *  this run, or null if it hasn't happened yet — see {@link #stormDeathSeenAtMillis}'s doc comment. */
	public static Long getStormDeathSeenAtMillis() { return stormDeathSeenAtMillis; }

	public static List<DungeonPlayer> getTeammates() {
		return teammates;
	}

	public static List<DungeonPlayer> getTeammatesNoSelf() {
		Minecraft mc = Minecraft.getInstance();
		String selfName = mc.player != null ? mc.player.getName().getString() : null;
		List<DungeonPlayer> result = new ArrayList<>();
		for (DungeonPlayer player : teammates) if (!player.name.equals(selfName)) result.add(player);
		return result;
	}

	public static int getSecretsFound() { return secretsFound; }
	public static float getSecretsPercent() { return secretsPercent; }
	public static int getKnownSecrets() { return knownSecrets; }
	public static int getCrypts() { return crypts; }
	public static int getOpenedRooms() { return openedRooms; }
	public static int getCompletedRooms() { return completedRooms; }
	public static int getClearedPercent() { return clearedPercent; }
	public static int getDeaths() { return deaths; }
	/** Real username of whoever died FIRST this run, or null if nobody has died yet — see the field's own
	 *  doc comment for why only the first death matters here. */
	public static String getFirstDeathPlayerName() { return firstDeathPlayerName; }
	public static String getElapsedTime() { return elapsedTime; }
	public static int getPuzzleCount() { return puzzleCount; }
	public static String getDoorOpener() { return doorOpener; }
	public static boolean isMimicKilled() { return mimicKilled; }
	public static boolean isPrinceKilled() { return princeKilled; }
	public static boolean isBatKilled() { return batKilled; }
	public static void setMimicKilled() { mimicKilled = true; }
	public static void setPrinceKilled() { princeKilled = true; }
	public static void setBatKilled() { batKilled = true; }

	/** Total secrets estimate from found-count + found-percentage, matching Odin's {@code totalSecrets}. */
	public static int getTotalSecrets() {
		if (secretsFound == 0 || secretsPercent == 0f) return 0;
		return (int) Math.floor(100 / secretsPercent * secretsFound + 0.5);
	}

	public static void register() {
		if (registered) return;
		registered = true;

		WorldScan.addRoomEnterListener(room -> recomputeKnownSecrets());

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			try {
				// Real bug found (per user report — "STILL not detecting the boss messages... a regex
				// issue", pointed at comparing against the pest-spawn detector): PestSpawnAlertFeature's own
				// working chat regex strips "§." formatting codes before matching (message.getString()
				// .replaceAll("§.", "")); every pattern in this class's own onChatMessage — including all
				// the boss-entry/F7-phase/terminal-progress ones — never did, relying on getString() alone
				// having already stripped everything. That's only true when Hypixel sends the line's color
				// as real Style objects; if it instead sends this SPECIFIC line as one literal string with
				// "§" codes embedded directly in the text content (which getString() then preserves as
				// literal characters), every one of these exact-text patterns would silently never match at
				// all, anywhere the codes land — not just at the edges (which the earlier find()-vs-matches()
				// fix already covered), but interspersed within the matched text itself. Stripped here now,
				// matching the one detector already confirmed working.
				onChatMessage(message.getString().replaceAll("§.", ""));
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("DungeonState chat tracking failed on a message, ignoring it", e);
			}
			return true;
		});

		// Real bug found (per user report — "the splits dont disappear/reset when i leave the game/die...
		// same with all dungeon related modules"): reset() otherwise only ever fires on a strong IN-SESSION
		// signal (reaching the hub/lobby, or the "instance will close" chat line) — a player who quits/
		// disconnects mid-run WITHOUT ever passing back through the hub first (closing the client, a dropped
		// connection, using a server-switch command) never sent either signal, so every downstream reader of
		// this state (Splits, Invincibility Timer, the map, etc.) stayed stuck showing that run's last known
		// state indefinitely — even surviving a full reconnect, since floorNumber etc. are plain static
		// fields that persist across the whole client session. A real disconnect is an unambiguous "the run
		// is definitely over" signal on its own, same reasoning IslandGate's own DISCONNECT hook already
		// uses to clear its caches.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void recomputeKnownSecrets() {
		int sum = 0;
		for (DungeonRoom room : DungeonScan.rooms) {
			if (room.walkedInto && room.data != null) sum += room.data.getSecrets();
		}
		knownSecrets = sum;
	}

	private static void onChatMessage(String text) {
		// Diagnostic only (per user report — boss detection "STILL isnt being detected" even with the
		// confirmed-correct real chat text and the sidebar-persistence bug already fixed): logs every
		// "[BOSS]" line unconditionally, BEFORE the gate below gets a chance to bail — together with the
		// exact floorNumber/isInDungeon()/bossEntryMessageSeen state at that instant, so the next real test
		// pinpoints whether the line even reaches here, and if so, exactly which check is rejecting it,
		// instead of further guessing (the regexes themselves are now confirmed correct against real
		// pasted debug/log text — "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", "[BOSS] Storm:
		// Pathetic Maxor, just like expected.", "[BOSS] Goldor: Who dares trespass into my domain?" — all
		// three match BOSS_ENTRY_PATTERNS[6]/STORM_ANY_LINE/GOLDOR_ANY_LINE exactly).
		// Same "don't trust a bare isInDungeon() no during an already-active run" leniency as tick() above
		// (see its own extended doc comment) — this is the more directly-affected gate, since a boss chat
		// line arriving while IslandGate's sidebar check reads false would otherwise bail out here before
		// any of the boss-entry/F7Phase patterns below even get a chance to run.
		if (!isInDungeon() && floorNumber < 0) return;

		// See watcherGateActive's own field doc comment — starts on the confirmed Blood Door line, ends the
		// instant the Watcher is actually cleared (the same portal-open line that also sets bossEntryMessageSeen
		// right below), both gated to F7 same as that check already is.
		if (!watcherGateActive && floorNumber == 7 && BLOOD_DOOR_OPEN_PATTERN.matcher(text).find()) {
			watcherGateActive = true;
		}
		if (watcherGateActive && WATCHER_PORTAL_ENTRY_PATTERN.matcher(text).find()) {
			watcherGateActive = false;
		}

		if (!bossEntryMessageSeen && floorNumber == 7 && WATCHER_PORTAL_ENTRY_PATTERN.matcher(text).find()) {
			bossEntryMessageSeen = true;
		}

		// Deliberately NOT gated on !bossEntryMessageSeen — for F7 that flag is usually already true by now
		// (set by the earlier, deliberately-early Watcher portal line above), which would otherwise stop
		// this real per-floor greeting line from ever being checked at all. See bossGreetingSeen's own field
		// doc comment for why this needs to be tracked as a separate, later signal.
		if (!bossGreetingSeen && floorNumber >= 1 && floorNumber <= 7 && BOSS_ENTRY_PATTERNS[floorNumber - 1].matcher(text).find()) {
			bossGreetingSeen = true;
		}

		if (!bossEntryMessageSeen && floorNumber >= 1 && floorNumber <= 7
			&& BOSS_ENTRY_PATTERNS[floorNumber - 1].matcher(text).find()) {
			bossEntryMessageSeen = true;
		}

		// F7Phase chat detection — real, user-confirmed lines. Takes over from bossEntryMessageSeen (already
		// Maxor's own entry line) for P1, adds Storm's own intro for P2, and Goldor's terminal-start lines
		// (already tracked below for terminalSection) for P3 — see computeF7Phase()'s own doc comment for
		// why this is now authoritative over the old Y-height heuristic.
		if (floorNumber == 7) {
			if (f7ChatPhase != F7Phase.P1 && BOSS_ENTRY_PATTERNS[6].matcher(text).find()) {
				f7ChatPhase = F7Phase.P1;
			} else if (f7ChatPhase != F7Phase.P2 && STORM_ANY_LINE.matcher(text).find()) {
				f7ChatPhase = F7Phase.P2;
			} else if (f7ChatPhase != F7Phase.P3 && GOLDOR_ANY_LINE.matcher(text).find()) {
				f7ChatPhase = F7Phase.P3;
			}
		}

		// Real, confirmed "the run is over" signal (devonian's own CroesusChestCounter.kt calls this
		// squadWipeRegex — fires on both a full team wipe and a normal completion) — a full reset here,
		// not just terminalSection, gives a reliable chat-driven "run ended" trigger that doesn't have to
		// wait on IslandGate's sidebar-based detection to catch up (see tick()'s own doc comment for why
		// that can lag/differ during the fight itself).
		if (DUNGEON_INSTANCE_CLOSING_PATTERN.matcher(text).find()) {
			reset();
			return;
		}

		if (floorNumber == 7) {
			if (goldorGreetingSeenAtMillis == null && GOLDOR_START_PATTERN.matcher(text).matches()) {
				goldorGreetingSeenAtMillis = System.currentTimeMillis();
			}
			if (stormDeathSeenAtMillis == null && GOLDOR_STORM_END_PATTERN.matcher(text).matches()) {
				stormDeathSeenAtMillis = System.currentTimeMillis();
			}
			if (terminalSection == TerminalSection.NONE
				&& (GOLDOR_STORM_END_PATTERN.matcher(text).matches() || GOLDOR_START_PATTERN.matcher(text).matches())) {
				terminalSection = TerminalSection.S1;
			} else if (terminalSection != TerminalSection.NONE && GOLDOR_CORE_ENTRANCE_LINE.equals(text)) {
				terminalSection = TerminalSection.NONE;
			} else if (terminalSection != TerminalSection.NONE) {
				Matcher progressMatch = GOLDOR_PROGRESS_PATTERN.matcher(text);
				if (progressMatch.matches()) {
					try {
						int current = Integer.parseInt(progressMatch.group(1));
						int total = Integer.parseInt(progressMatch.group(2));
						if (current == total) {
							TerminalSection next = switch (terminalSection) {
								case S1 -> TerminalSection.S2;
								case S2 -> TerminalSection.S3;
								case S3 -> TerminalSection.S4;
								case S4, NONE -> terminalSection;
							};
							if (next != terminalSection) {
								terminalSection = next;
							}
						}
					} catch (NumberFormatException ignored) {}
				}
			}
		}

		Matcher doorMatch = DOOR_OPEN_PATTERN.matcher(text);
		if (doorMatch.find()) doorOpener = doorMatch.group(1);

		Matcher deathMatch = DEATH_PATTERN.matcher(text);
		if (deathMatch.find()) {
			Minecraft mc = Minecraft.getInstance();
			String name = deathMatch.group(1).equals("You") && mc.player != null ? mc.player.getName().getString() : deathMatch.group(1);
			for (DungeonPlayer player : teammates) if (player.name.equals(name)) player.deaths++;
			if (firstDeathPlayerName == null) {
				firstDeathPlayerName = name;
				com.cokelord.skyblocksimplified.api.MojangApi.resolve(name,
					uuid -> com.cokelord.skyblocksimplified.api.SkyblockStatsApi.request(uuid));
			}
		}

		Matcher partyMatch = PARTY_MESSAGE_PATTERN.matcher(text);
		if (!partyMatch.find()) return;
		String content = partyMatch.group(1).toLowerCase(Locale.ROOT);

		if (isKillPhrase(content, "mimic")) {
			if (isFloor(6, 7)) mimicKilled = true;
		} else if (isKillPhrase(content, "prince")) {
			princeKilled = true;
		} else if (isKillPhrase(content, "bat")) {
			batKilled = true;
		} else if (content.startsWith("blaze done") || content.equals("blaze puzzle solved!")) {
			for (Puzzle puzzle : Puzzle.values()) if (puzzle == Puzzle.BLAZE) puzzle.status = PuzzleStatus.COMPLETED;
		}
	}

	private static boolean isKillPhrase(String content, String subject) {
		return content.equals(subject + " killed") || content.equals(subject + " slain") || content.equals(subject + " killed!")
			|| content.equals(subject + " dead") || content.equals(subject + " dead!");
	}

	public static void tick() {
		// See lastLevelObj's own doc comment above: a real ClientLevel object swap (any dungeon<->dungeon or
		// dungeon<->hub jump) is unambiguous ground truth, unlike a merely-missing floor label, so this
		// force-resets even when the freshly detected floor number happens to match the previous run's —
		// exactly the "rejoined the same floor type without a hub stop" case the floor-number-diff check
		// below can never catch on its own.
		Object currentLevelObj = Minecraft.getInstance().level;
		if (currentLevelObj != lastLevelObj) {
			boolean hadActiveRun = floorNumber != -1;
			lastLevelObj = currentLevelObj;
			if (hadActiveRun) {
				reset();
				com.cokelord.skyblocksimplified.dungeon.map.WorldScan.forceReset();
			}
		}
		// Real bug found (per user report — boss-room chat lines never detected, but the Blood Camp portal-
		// entry line, still just barely in the normal dungeon, works every time): confirmed against Odin's
		// own real DungeonUtils.kt/LocationUtils.kt that "am I still in this dungeon run" is meant to stay
		// true for the WHOLE run including the boss fight — Odin's own inBoss is a flag layered ON TOP of
		// inDungeons, never something that flips inDungeons itself false, and Odin's own inDungeons check is
		// tab-list based ("Dungeon: " prefix), never affected by whatever the sidebar happens to show.
		// IslandGate's own sidebar-substring fallback here ("Catacombs"/"Master Mode" somewhere in the
		// scoreboard body) is a reasonable proxy for most of a run, but Hypixel's real boss-room sidebar
		// doesn't show that text for the fight's whole duration — not the brief mid-rebuild flicker
		// IslandGate's own sticky-positive design already tolerates, but a sustained "missing" reading that
		// used to wipe floorNumber (and every floorNumber-gated boss check below it) back to unset the
		// instant the boss room was entered, before a single boss chat line ever had a chance to fire.
		// Once a run is already confirmed active (floorNumber known), a bare "isInDungeon() says no" reading
		// is no longer trusted alone to mean the player left — only a stronger signal does now: a genuinely
		// empty sidebar/real hub (IslandGate.isInHubOrLobby()), or the explicit "instance will close" chat
		// line above, which already does its own full reset(). Same sticky-until-strong-evidence philosophy
		// IslandGate itself already applies to its own sidebar fallback, just extended to cover this too.
		// Real bug found (per user report — "It still doesnt detect when i leave the bossfight, everything
		// boss related is still running. Detect through the hypixel-mod-api mod."): isInHubOrLobby() alone
		// only agrees the player actually left once the Mod API mode is literally "hub" (or the sidebar goes
		// empty) — leaving a dungeon by warping straight to a DIFFERENT populated island (Garden, Crimson
		// Isle, etc.) makes isInDungeon() correctly go false via the same Mod API packet, but isInHubOrLobby()
		// stays false too (wrong mode, non-empty sidebar), so this whole branch never ran and every boss-
		// related flag/timer stayed stuck exactly as reported. IslandGate.isConfirmedOffDungeon() is the same
		// authoritative Mod API signal without that extra "must be hub specifically" requirement — trusted
		// here on equal footing with isInHubOrLobby().
		boolean activeRun = floorNumber >= 0;
		if (!isInDungeon() && (!activeRun || IslandGate.isInHubOrLobby() || IslandGate.isConfirmedOffDungeon())) {
			if (floorNumber != -1 || inBoss || !teammates.isEmpty()) reset();
			return;
		}

		List<String> tabList = TabListReader.readLines();
		// Real bug found: floor/stat detection used to scan ONLY the tab list. Cross-referencing three real
		// mods (Odin's DungeonListener.kt, SkyHanni's DungeonApi.kt, devonian's Dungeons.kt) shows the floor
		// label specifically ("The Catacombs (F7)") is confirmed sourced from the SCOREBOARD side
		// (SkyHanni's onScoreboardUpdate / Odin's raw ClientboundSetPlayerTeamPacket listener) — a real mod
		// this codebase already has a dedicated reader for (ScoreboardReader) but never actually consulted
		// here. Scanning both sources defensively (same "redundant signal" reasoning as
		// checkForMaxorEntity()'s own doc comment below) means floor detection — and therefore floorNumber,
		// isFloor(), computeF7Phase(), and getTerminalSection(), all downstream of it — no longer silently
		// stays stuck at -1/UNKNOWN for an entire run if Hypixel only ever put this particular line on the
		// one surface this code wasn't checking.
		// Real bug found (per user report — a real live boss-fight debug line showing "floorNumber=-1" even
		// deep into the F7 fight, meaning floor detection had silently never once succeeded for the entire
		// run): ScoreboardReader.readCurrentSidebarLines() returns RAW, un-stripped §-color-coded text by
		// its own documented convention — every other caller of it in this codebase (IslandGate, SplitsFeature,
		// PestPlotTracker) strips "§." locally before matching, but this one never did. FLOOR_PATTERN is
		// anchored with a trailing `$`, so any trailing color/reset code Hypixel puts after the closing
		// paren (near-universal on real colored sidebar lines) silently failed the match on every single
		// tick, for every floor, the entire session — the exact same class of "$-anchored against unstripped
		// text" bug already found and fixed for BOSS_ENTRY_PATTERNS/WATCHER_PORTAL_ENTRY_PATTERN elsewhere in
		// this file, just never applied here. This one root cause explains why every floorNumber-gated boss
		// check (BOSS_ENTRY_PATTERNS, checkForMaxorEntity, f7ChatPhase, terminalSection) has never fired all
		// session regardless of how many times the boss-detection logic itself was rewritten.
		List<String> sidebar = com.cokelord.skyblocksimplified.hud.ScoreboardReader.readCurrentSidebarLines();
		List<String> combined = new ArrayList<>(tabList.size() + sidebar.size());
		combined.addAll(tabList);
		for (String line : sidebar) combined.add(COLOR_CODE.matcher(line).replaceAll(""));

		// Real bug found (per user report — "if I join a new dungeon from the dungeon without dying/going to
		// the lobby... running the command again just leaves it all as the old dungeon, including the map"):
		// this only ever scanned while floorNumber was still -1, so a direct dungeon-to-dungeon rejoin (party
		// leader re-running /joininstance mid-run) — which never makes isInDungeon() go false, the only other
		// signal reset() below listens for — left the OLD floor's number, and every downstream cache keyed off
		// it (DungeonScan's tiles/rooms/doors, WorldScan's room cache, this class's own teammates/secrets/etc.)
		// stuck for the rest of the client session. Now scans every tick regardless, and a match whose number
		// actually DIFFERS from the currently-known floorNumber (not just the first-ever detection) is treated
		// as an unambiguous "this is a different run" signal — a single Catacombs run's floor number never
		// legitimately changes mid-run, so any change can only mean a new one started.
		boolean floorLabelPresent = false;
		for (String line : combined) {
			Matcher matcher = FLOOR_PATTERN.matcher(line);
			if (!matcher.find()) continue;
			floorLabelPresent = true;
			String label = matcher.group(1);
			int number = parseFloorNumber(label);
			if (number == floorNumber) break;
			if (floorNumber != -1) {
				reset();
				com.cokelord.skyblocksimplified.dungeon.map.WorldScan.forceReset();
			}
			floorLabel = label;
			floorNumber = number;
			runId++;
			DungeonScan.initClient(number);
			break;
		}

		// See FLOOR_LABEL_MISSING_RESET_MILLIS's own doc comment above — catches a /warp dungeonhub-style
		// leave that isInDungeon() itself can never see, while surviving an ordinary server lag spike.
		if (++floorLabelCheckTickCounter >= FLOOR_LABEL_CHECK_INTERVAL_TICKS) {
			floorLabelCheckTickCounter = 0;
			if (floorNumber >= 0 && !floorLabelPresent) {
				long now = System.currentTimeMillis();
				if (floorLabelMissingSinceMillis == 0L) floorLabelMissingSinceMillis = now;
				long missingMillis = now - floorLabelMissingSinceMillis;
				if (missingMillis >= FLOOR_LABEL_MISSING_RESET_MILLIS) {
					reset();
					com.cokelord.skyblocksimplified.dungeon.map.WorldScan.forceReset();
					floorLabelMissingSinceMillis = 0L;
					return;
				}
			} else {
				floorLabelMissingSinceMillis = 0L;
			}
		}

		// Was gated on !bossEntryMessageSeen alone, which (for F7) is usually already true by the time this
		// runs — the deliberately-early Watcher portal line sets it well before Maxor is actually visible —
		// so this entity scan stopped running (and could never confirm the later, real bossGreetingSeen
		// signal) almost immediately after every F7 boss encounter began. Needs both to still be unconfirmed.
		if (!bossGreetingSeen && floorNumber == 7) checkForMaxorEntity();

		boolean wasInBoss = inBoss;
		// Real bug found: computeInBoss() (now removed, see below) checked "player.x > threshold && player.z
		// > threshold" per floor — an unbounded half-plane, not a box around the actual boss room. Dungeon
		// rooms are laid out on a grid expanding toward positive X/Z from the entrance, so a player crosses
		// most floors' thresholds early into completely normal exploration and computeInBoss() then stays
		// true for almost the entire rest of the run, not just the real boss fight. Every feature gated on
		// !isInBoss() (Door Highlight among them) was therefore disabled for nearly the whole normal-room
		// phase, which reads exactly like "still not working in dungeons." bossEntryMessageSeen alone (a
		// real "[BOSS] <name>: <line>" chat confirmation per floor, plus the Maxor entity fallback above for
		// F7) is a strictly reliable signal with no false-positive failure mode like this — using it alone.
		inBoss = floorNumber >= 0 && bossEntryMessageSeen;

		updateTeammates(tabList);
		updateStats(combined);
		updatePuzzles(combined);
	}

	private static void updateTeammates(List<String> tabList) {
		Minecraft mc = Minecraft.getInstance();
		for (String line : tabList) {
			Matcher nameMatcher = TEAMMATE_NAME_PATTERN.matcher(line);
			if (!nameMatcher.find()) continue;
			String name = nameMatcher.group(1);
			Matcher statusMatcher = TEAMMATE_STATUS_PATTERN.matcher(line);
			if (!statusMatcher.find()) continue;
			String clazzText = statusMatcher.group(1);

			DungeonPlayer existing = null;
			for (DungeonPlayer player : teammates) if (player.name.equals(name)) { existing = player; break; }

			if (existing != null) {
				// Real bug found (per user report — "the user class needs to be detected in dungeons too...
				// swaps usually happen in dungeons"): this used to only ever read a fresh class off the tab
				// list while existing.clazz was still EMPTY — i.e. exactly once, the very first time this
				// teammate's class was resolved at all. A real mid-run class swap (Hypixel's own tab list line
				// updates to show the new class immediately) re-parsed to the same non-null clazzText every
				// tick after that, but since existing.clazz was already non-EMPTY the whole block was skipped,
				// so neither this teammate's tracked class NOR (for the local player specifically)
				// SelfClassCache ever picked up the swap — the exact scenario this bridge exists for, since a
				// player can swap loadouts mid-dungeon at any class-swapper NPC. Now re-parses every tick and
				// only ignores a genuinely unresolvable read (a transient/blank class token parses to EMPTY,
				// e.g. the brief window before Hypixel populates this line, or "DEAD" — findClass only ever
				// matches a real DungeonClass name) rather than gating on "only the first time ever."
				DungeonClass freshClazz = findClass(clazzText);
				if (freshClazz != DungeonClass.EMPTY && freshClazz != existing.clazz) {
					existing.clazz = freshClazz;
					String level = statusMatcher.group(2);
					existing.clazzLvl = level != null && !level.isEmpty() ? RomanNumeralUtil.parseLevel(level) : -1;
					if (mc.player != null && name.equals(mc.player.getName().getString())) {
						SelfClassCache.set(existing.clazz);
					}
				}
				existing.isDead = "DEAD".equalsIgnoreCase(clazzText);
				// Real bug found (per user report — "the map does detect the new dungeon but my player head
				// is still stuck where it was in the old dungeon"): existing.entity was only ever resolved
				// ONCE, the very first time a teammate with this name was seen — never refreshed again for
				// the rest of the client session. DungeonMapFeature's own marker draw reads entity.getX()/
				// getZ() live every frame (there's no cached position anywhere), so that part was already
				// correct — but a same-session dungeon rejoin whose party keeps the same member names (a
				// same-floor requeue especially, which never even changes DungeonState.runId) never
				// re-triggers this method's own "add a new DungeonPlayer" branch, so the stale Entity
				// reference from the OLD run's now-detached player object was kept forever, frozen at
				// wherever it last ticked before being replaced. Re-resolved every tick instead (a cheap
				// UUID lookup) so it always tracks whatever the CURRENT real entity actually is, self-healing
				// regardless of whether anything else noticed a new run started.
				if (mc.level != null && mc.getConnection() != null) {
					PlayerInfo refreshInfo = mc.getConnection().getPlayerInfo(name);
					if (refreshInfo != null) existing.entity = mc.level.getPlayerByUUID(refreshInfo.getProfile().id());
				}
			} else {
				if (mc.getConnection() == null) continue;
				PlayerInfo info = mc.getConnection().getPlayerInfo(name);
				if (info == null) continue;
				DungeonClass clazz = findClass(clazzText);
				if (clazz == null) continue;
				String level = statusMatcher.group(2);
				DungeonPlayer player = new DungeonPlayer(name, clazz, level != null && !level.isEmpty() ? RomanNumeralUtil.parseLevel(level) : -1);
				if (mc.level != null) player.entity = mc.level.getPlayerByUUID(info.getProfile().id());
				teammates.add(player);
				if (mc.player != null && name.equals(mc.player.getName().getString())) {
					SelfClassCache.set(clazz);
				}
			}
		}
	}

	private static DungeonClass findClass(String text) {
		for (DungeonClass clazz : DungeonClass.values()) if (clazz.name().equalsIgnoreCase(text)) return clazz;
		return DungeonClass.EMPTY;
	}

	private static void updateStats(List<String> tabList) {
		for (String line : tabList) {
			Matcher m;
			if ((m = SECRET_PERCENT_PATTERN.matcher(line)).find()) {
				try { secretsPercent = Float.parseFloat(m.group(1)); } catch (NumberFormatException ignored) {}
			}
			if ((m = SECRET_COUNT_PATTERN.matcher(line)).find()) {
				try { secretsFound = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
			}
			if ((m = COMPLETED_ROOMS_PATTERN.matcher(line)).find()) {
				try { completedRooms = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
			}
			if ((m = CLEARED_PERCENT_PATTERN.matcher(line)).find()) {
				try { clearedPercent = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
			}
			if ((m = OPENED_ROOMS_PATTERN.matcher(line)).find()) {
				try { openedRooms = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
			}
			if ((m = PUZZLE_COUNT_PATTERN.matcher(line)).find()) {
				try { puzzleCount = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
			}
			if ((m = DEATHS_PATTERN.matcher(line)).find()) {
				try { deaths = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
			}
			if ((m = CRYPT_PATTERN.matcher(line)).find()) {
				try { crypts = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
			}
			if ((m = TIME_PATTERN.matcher(line)).find()) {
				elapsedTime = m.group(1);
			}
		}
	}

	private static void updatePuzzles(List<String> tabList) {
		for (String line : tabList) {
			Matcher matcher = PUZZLE_STATUS_PATTERN.matcher(line);
			if (!matcher.find()) continue;
			String name = matcher.group(1);
			String status = matcher.group(2);
			String player = matcher.group(3);

			Puzzle puzzle = null;
			for (Puzzle p : Puzzle.values()) if (p != Puzzle.UNKNOWN && p.displayName.equals(name)) { puzzle = p; break; }
			if (puzzle == null) continue;

			if (player != null && !player.isEmpty()) puzzle.player = player;
			puzzle.status = switch (status) {
				case "✖" -> PuzzleStatus.FAILED;
				case "✔" -> PuzzleStatus.COMPLETED;
				case "✦" -> PuzzleStatus.INCOMPLETE;
				default -> puzzle.status;
			};
		}
	}

	private static int parseFloorNumber(String label) {
		if (label.equals("E")) return 0;
		try {
			return Integer.parseInt(label.substring(1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	// Diagnostic-only throttle for checkForMaxorEntity()'s own "still searching" log below — per user
	// request ("check other mods... make sure it works" — can't live-test this round), cross-referenced all
	// three other real mods' own boss-entry detection: Odin's own inBoss is a per-tick coordinate check
	// (reverted here once already — see the doc comment above tick()'s inBoss computation — because a raw
	// port of Odin's own absolute-world-coordinate thresholds falsely triggered mid-exploration; Odin's own
	// numbers are almost certainly relative to ITS OWN dungeon-map-alignment origin, not raw world
	// coordinates, so blindly re-porting them a second time without also porting that alignment logic would
	// likely just reproduce the same confirmed-wrong behavior rather than fix anything), Skyblocker's own
	// gate is the Hypixel Mod API's real location packet (this project already prefers that exact same
	// source in IslandGate when the optional companion mod is installed), and devonian's own gate reads the
	// TAB LIST header text specifically (not the sidebar) — a real, independently-confirmed reason a
	// sidebar-only signal can be less stable than this project's own IslandGate fallback already assumed.
	// None of those three offer a SAFE, confirmed-correct drop-in replacement for this project's own
	// already-working three-signal design (Watcher portal line + chat boss-entry line + this Maxor-entity
	// scan) without risking reintroducing an already-diagnosed regression — so instead of guessing a fourth
	// detection mechanism blind, this makes the EXISTING entity scan self-report whether it's even running
	// and what it's finding, the same diagnostic-first approach onChatMessage's own "[BOSS] line reached"
	// log above already takes, so the next real F7 pull's debug log distinguishes "floorNumber never reached
	// 7" from "the scan runs every tick but never finds an entity named Maxor" without another guess.
	private static long lastMaxorScanLogMillis = 0L;

	/** Third, independent signal for "the F7 boss fight has started" alongside the coordinate check and the
	 *  chat-line match — per user report, Positional Messages (gated on isInBoss() + floor 7) sometimes
	 *  never triggers for the whole fight, and computeInBoss()'s coordinate boundaries are already flagged
	 *  above as unconfirmed/unreliable. Scanning for the Maxor boss entity itself is the same kind of
	 *  packet/entity-based detection Odin's own boss-device modules prefer over chat-line matching for
	 *  exactly this reliability reason (see ArrowsDevice's ClientboundSetEntityDataPacket listener). Reuses
	 *  bossEntryMessageSeen rather than a new flag since both mean the same thing: "confirmed by a reliable
	 *  signal, not just the coordinate guess." */
	private static void checkForMaxorEntity() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		int scanned = 0;
		for (var entity : mc.level.entitiesForRendering()) {
			scanned++;
			if (entity.getName().getString().contains("Maxor")) {
				bossEntryMessageSeen = true;
				// Real bug found (per user report — "the map stops showing the rooms" right around when
				// blood mobs die, the SAME symptom bossGreetingSeen was originally split out to fix): this
				// used to also set bossGreetingSeen here on the theory that the entity being renderable was
				// "at least as precise" a signal as Maxor's own greeting line — false in practice.
				// entitiesForRendering() scans the player's whole render distance, not just the boss room, so
				// Maxor's entity can already be renderable well before the player has actually walked in and
				// triggered his real greeting dialogue (right around when blood mobs finish dying and the
				// player starts approaching, exactly matching the report). Only bossEntryMessageSeen (the
				// deliberately-early, "roughly entered boss" signal — see its own field doc comment) is set
				// here now; bossGreetingSeen stays gated on the real chat line alone.
				return;
			}
		}
		long now = System.currentTimeMillis();
		if (now - lastMaxorScanLogMillis >= 5000) {
			lastMaxorScanLogMillis = now;
		}
	}

	private static void reset() {
		floorLabel = null;
		floorNumber = -1;
		floorLabelMissingSinceMillis = 0L;
		inBoss = false;
		watcherGateActive = false;
		bossEntryMessageSeen = false;
		bossGreetingSeen = false;
		f7ChatPhase = F7Phase.UNKNOWN;
		lastLoggedF7Phase = F7Phase.UNKNOWN;
		terminalSection = TerminalSection.NONE;
		goldorGreetingSeenAtMillis = null;
		stormDeathSeenAtMillis = null;
		teammates.clear();
		secretsFound = 0;
		secretsPercent = 0f;
		knownSecrets = 0;
		crypts = 0;
		openedRooms = 0;
		completedRooms = 0;
		clearedPercent = 0;
		deaths = 0;
		firstDeathPlayerName = null;
		elapsedTime = "0s";
		puzzleCount = 0;
		doorOpener = "Unknown";
		mimicKilled = false;
		princeKilled = false;
		batKilled = false;
		for (Puzzle puzzle : Puzzle.values()) {
			puzzle.status = null;
			puzzle.player = null;
		}
	}
}
