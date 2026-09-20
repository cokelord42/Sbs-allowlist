package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.MapScan;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.MapCheckmark;
import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomType;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Title + sound alerts for various dungeon events, detected through chat (per user request — "make sure
 * the notifications are detected through chat"), except Room Cleared, which has no real chat line at all —
 * confirmed via Odin's own real {@code RoomClear.kt}, which drives it off the dungeon map's own per-room
 * checkmark color changing (the same {@link MapScan} pipeline {@code DoorHighlightFeature} already depends
 * on), not chat. Ported that same mechanism via {@link MapScan#addCheckmarkListener}, this codebase's
 * existing equivalent of Odin's {@code CheckmarkUpdateEvent}.
 *
 * <p>The role-gated notifications (Healer at SS, Berserker at GY, etc.) only fire for a message that's both
 * the right keyword AND sent by a teammate whose live-tracked class ({@link DungeonState#getTeammates()})
 * actually matches — a Mage typing "SS" doesn't count as "Healer at SS".
 */
public class DungeonNotificationsFeature extends Feature implements MoveableWidget {
	public enum NotificationType {
		WATCHER_DONE_SPAWNING("Watcher Done Spawning"),
		ALL_BLOOD_MOBS_DEAD("All Blood Mobs Dead"),
		HEALER_AT_SS("Healer at SS"),
		BERSERK_AT_GY_DROPOFF("Berserker at GY Dropoff"),
		BERSERK_WAITING_FOR_HEALER("Berserker Waiting for Healer"),
		EARLY_ENTER("All Early Enters"),
		ROOM_CLEARED("Room Cleared"),
		// Per user request: fires once per run, the moment the live Dungeon Score Calculator's total
		// crosses each threshold — title+sound like every other type here, plus an optional user-typed
		// party chat message (see partyChatMessages/partyChatEnabled) neither of the other types have.
		SCORE_300("300 Score"),
		SCORE_270("270 Score"),
		// Per user request ("Remove the 'Ultimate warning' module and move it to dungeon notifications"):
		// folded in from the old standalone UltimateWarningFeature (now deleted) — same chat line, same
		// "collapse repeat nags into one title" cooldown (see lastUltimateReadyMillis/repeatUltimateReminders
		// below), just as a NotificationType here instead of its own module. "Hide Ultimate Ready Message"
		// (the chat-line-hiding half) already lives in Chat De-clutter independently and is unaffected.
		ULTIMATE_READY("Ultimate Ready"),
		// Per user request: titles for the Healer specifically (checked against the local player's own
		// live-tracked class, not who sent a message) the moment Maxor's "YOU TRICKED ME!" line fires —
		// real Hypixel signal that it's time to pop Wish.
		HEALER_SHOULD_ULT("Healer Should Ult"),
		// Per user request ("Storm crushed notification, here's the lines to detect, it can be either of
		// them"): fires the moment Storm's crush line lands, either variant.
		STORM_CRUSHED("Storm Crushed"),
		// Per user request: fires once per run the moment the live tab-list crypt counter
		// (DungeonState.getCrypts(), the same source DungeonScoreCalculatorFeature's bonus score already
		// reads) reaches 5 — same optional party-chat-message support as SCORE_300/SCORE_270 above.
		FIVE_CRYPTS("5 Crypts"),
		// Per user request ("The key drop notification should go in the Dungeon notifications module"):
		// folded in from DoorHighlightFeature's own announceKeySpawn (which used to fire a raw vanilla
		// Hud.setTitle directly, bypassing this module's title box/color/party-chat system entirely).
		// DoorHighlightFeature still does the actual spawn DETECTION (scanning for the Wither/Blood Key
		// armor stand) since that's real gameplay-state tracking outside this chat-driven feature's scope —
		// see fireKeyDrop below, its one external entry point.
		KEY_DROP("Key Drop"),
		// Per user request ("add a new chat command !dt or !downtime — another party member sends '!dt need
		// to fill the quiver', and if the local player is party leader, show a title on screen"): distinct
		// from ChatCommandsFeature's own pre-existing "!dt"/"!downtime" reminder-queue system (which still
		// runs unchanged and queues a summary for end-of-run) — this is an immediate on-screen title fired
		// only for the party leader the moment the command is heard, via fireDowntimeRequest below.
		DT_REQUEST("Downtime Request (Leader)"),
		// Per user request ("Add a new notification to dungeon notifications that notifies when the simon
		// says is complete using the same detection method"): fired by SimonSaysFeature at the exact same
		// detection point its own "SS 5/5" party-chat announcement already uses (stage reaching the real max
		// of 5, the moment `solution` empties on a correct final click) — see fireSimonSaysComplete below.
		SIMON_SAYS_COMPLETE("Simon Says Complete");

		public final String displayName;
		NotificationType(String displayName) { this.displayName = displayName; }
	}

	// Real bug found (per user report — notification never fired): the real line reads "That WILL be
	// enough for now.", not "should be". Unanchored (find(), no trailing $) per this session's own
	// established hardening pattern for boss chat lines — tolerant of anything trailing the real text.
	private static final Pattern WATCHER_DONE_SPAWNING_PATTERN =
		Pattern.compile("^\\[BOSS] The Watcher: That will be enough for now\\.?");
	// Confirmed real text via Odin's own DungeonListener.kt/SplitsManager.kt.
	private static final Pattern ALL_BLOOD_MOBS_DEAD_PATTERN =
		Pattern.compile("^\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.?");
	// Real line, ported verbatim from the old UltimateWarningFeature.
	private static final String ULTIMATE_READY_SUFFIX = "is ready to use! Press DROP to activate it!";
	// Same tolerant-separator fix as ChatCommandsFeature's own PARTY_MARKER — see its doc comment for the
	// real root cause (confirmed via a real user debug log).
	private static final Pattern PARTY_MARKER = Pattern.compile("Party\\s*\\S\\s+");
	private static final Pattern MAXOR_TRICKED_PATTERN = Pattern.compile("^\\[BOSS] Maxor: YOU TRICKED ME!?");
	// Per user-supplied quote, either variant.
	private static final Pattern STORM_CRUSHED_PATTERN =
		Pattern.compile("^\\[BOSS] Storm: (Oof|Ouch, that hurt!?)");
	// Per user request ("detect 'EE2', 'EE3', 'CORE' in the chat, detect who sent it"): replaces the old
	// class-gated "msg.contains(EE)" check — any partymate calling any of these three exact tokens fires
	// it now, not just whichever class is "expected" to call early enter for the current terminal section.
	private static final Pattern EARLY_ENTER_TOKEN = Pattern.compile("\\b(EE2|EE3|CORE)\\b", Pattern.CASE_INSENSITIVE);
	// Per user request ("Add support for (username) on supported notifications... where users can put
	// '(username)' to make it detect username for certain notifications") — literal placeholder, case
	// insensitive so "(Username)"/"(USERNAME)" also work.
	private static final Pattern USERNAME_PLACEHOLDER = Pattern.compile("\\(username\\)", Pattern.CASE_INSENSITIVE);
	// Per user request ("instead of selecting the color from a hex picker allowing users to use stuff like
	// &7 and §7... to color code certain parts of the text") — Bukkit-style '&' convenience on top of the
	// real '§' codes GuiGraphicsExtractor.text already understands natively (see MainScreen's own established
	// legacy-code rendering throughout this codebase); only recognized formatting letters are translated so a
	// stray '&' in normal text (e.g. "Bob & Alice") is left alone.
	private static final String LEGACY_CODE_CHARS = "0123456789abcdefklmnor";

	// Per user request ("make it render on its own gui and make it a movable gui element, like the dungeon
	// timers we have currently, instead of making a title appear in the center of the screen"): the fired
	// title now shows on this feature's own draggable HUD panel (see the MoveableWidget methods below)
	// instead of the vanilla title HUD, for a fixed display window rather than vanilla's fade in/out timing.
	private static final long TITLE_DISPLAY_MILLIS = 2_500L;
	private String activeTitleText = null;
	private int activeTitleColor = 0xFFFFFFFF;
	private long activeTitleUntilMillis = 0L;
	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.3f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private boolean showTitle = true;
	private boolean playSound = true;
	// Per user request ("let users have bold and italic titles"): off by default, matching the plain
	// unstyled look every title already has.
	private boolean boldTitle = false;
	private boolean italicTitle = false;
	// Per user request ("Allow users to change the color with a hex picker of the dungeon notifications") —
	// was previously a hardcoded "§d" (light purple) legacy color code baked into every fire() call, with
	// no way for the user to change it at all. Default matches that same real RGB value (§d = 0xFF55FF) so
	// existing users see no visual change until they actually pick a new color.
	private int titleColor = 0xFFFF55FF;
	private final Map<NotificationType, Boolean> enabled = new EnumMap<>(NotificationType.class);
	// Per user request ("Allow users to customize each notification's title/color") — per-type overrides,
	// empty/absent means "use this type's own default title / the global titleColor above".
	private final Map<NotificationType, String> customTitles = new EnumMap<>(NotificationType.class);
	private final Map<NotificationType, Integer> typeColors = new EnumMap<>(NotificationType.class);
	// Per user request, specific to the two score-threshold types: an optional message sent to party chat
	// (via "/pc", the same command every other party-chat-sending feature in this codebase already uses)
	// when that threshold fires, only if the user both typed one and turned it on.
	private final Map<NotificationType, String> partyChatMessages = new EnumMap<>(NotificationType.class);
	private final Map<NotificationType, Boolean> partyChatEnabled = new EnumMap<>(NotificationType.class);

	// Score-threshold state — each fires at most once per dungeon run, reset the moment the player leaves.
	private boolean inDungeonLastTick = false;
	private boolean fired300 = false;
	private boolean fired270 = false;
	private boolean fired5Crypts = false;
	// Real bug found (per user report — "The healer should ult notif triggers twice when it should only
	// trigger the first time maxor gets lasered"): Hypixel really does send Maxor's "YOU TRICKED ME!" chat
	// line more than once per fight (it fires again on repeat laser volleys, not just the very first one) —
	// there was no debounce here at all, so every repeat line fired its own title. This is a one-time
	// per-run heads-up, not a repeating nag like Ultimate Ready, so it's gated the same way as the score/
	// crypt thresholds above: fires at most once per dungeon run, reset the moment the player leaves.
	private boolean firedHealerShouldUlt = false;

	// Ported from the old UltimateWarningFeature: Hypixel keeps re-sending the ultimate-ready chat line
	// every so often as a nag while the ability sits unused, not just once — collapses back-to-back nags
	// into a single title. Per user request ("Remove repeat reminders from the Ultimate Ready notification
	// (fires repeatedly; should fire once)"): this used to be an opt-out toggle (repeatUltimateReminders,
	// off by default) that let a user re-enable the nag spam — removed entirely so it's unconditional, no
	// setting left to turn repeats back on. Gap is comfortably above Hypixel's own repeat cadence and
	// comfortably below the shortest real ultimate cooldown, so two truly separate ready-ups still each get
	// their own title.
	private static final long ULTIMATE_NEW_OCCURRENCE_GAP_MILLIS = 60_000;
	private long lastUltimateReadyMillis = 0L;

	// Per user request ("The mod doesnt detect the ult being ready for some reason. Make it detect even if
	// the ultimate ready notification isnt on"): the raw chat-line detection used to live entirely inside
	// onChatMessage, which only ever runs when BOTH this whole module is enabled AND the ULTIMATE_READY
	// notification type specifically is toggled on — so nothing else could ever know "the player's ultimate
	// is ready" if the user had that one notification turned off (a very plausible setup: someone who
	// doesn't want the title spam but still wants some OTHER feature to react to it). Tracked here as
	// always-on background infra instead, same pattern as WorldScan/PetDisplayFeature's own
	// isSpiritPetActive() — a static, ungated timestamp any feature can query via lastUltimateReadyMillis(),
	// independent of this module's own enabled state or the notification's own toggle.
	private static volatile long lastUltimateReadyDetectedMillis = 0L;

	/** Real ms-epoch timestamp of the last "ultimate ready" chat line seen this session (0 if none yet) —
	 *  see {@link #lastUltimateReadyDetectedMillis}'s own doc comment for why this is unconditional. */
	public static long lastUltimateReadyMillis() { return lastUltimateReadyDetectedMillis; }

	private static boolean listenersRegistered = false;
	private static DungeonNotificationsFeature instance;

	public DungeonNotificationsFeature() {
		super("dungeon_notifications", "Dungeon Notifications", FeatureCategory.COMBAT, false);
		instance = this;
		for (NotificationType type : NotificationType.values()) enabled.put(type, true);
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	public void onTick(Minecraft client) {
		boolean inDungeon = DungeonState.isInDungeon();
		if (!inDungeon) {
			if (inDungeonLastTick) { fired300 = false; fired270 = false; fired5Crypts = false; firedHealerShouldUlt = false; }
			inDungeonLastTick = false;
			return;
		}
		inDungeonLastTick = true;

		if (!fired5Crypts && isTypeEnabled(NotificationType.FIVE_CRYPTS) && DungeonState.getCrypts() >= 5) {
			fired5Crypts = true;
			fire(NotificationType.FIVE_CRYPTS, "5 Crypts!");
		}

		DungeonScoreCalculatorFeature scoreCalc = DungeonScoreCalculatorFeature.getInstance();
		if (scoreCalc == null || !scoreCalc.isEnabled()) return;
		int score = scoreCalc.computeScore().total();
		if (!fired270 && isTypeEnabled(NotificationType.SCORE_270) && score >= 270) {
			fired270 = true;
			fire(NotificationType.SCORE_270, "270 Score!");
		}
		if (!fired300 && isTypeEnabled(NotificationType.SCORE_300) && score >= 300) {
			fired300 = true;
			fire(NotificationType.SCORE_300, "300 Score!");
		}
	}

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (overlay) return true;
				String text = message.getString();
				// See lastUltimateReadyDetectedMillis's own doc comment — always runs, regardless of this
				// module's enabled state or the ULTIMATE_READY notification's own toggle.
				if (text.contains(ULTIMATE_READY_SUFFIX) && DungeonState.isInDungeon()) {
					lastUltimateReadyDetectedMillis = System.currentTimeMillis();
				}
				if (instance != null && instance.isEnabled()) instance.onChatMessage(text);
				return true;
			});
			MapScan.addCheckmarkListener((room, checkmark) -> {
				if (instance == null || !instance.isEnabled() || !instance.isTypeEnabled(NotificationType.ROOM_CLEARED)) return;
				if (room.type == RoomType.FAIRY || room.type == RoomType.ENTRANCE) return;
				if (checkmark != MapCheckmark.GREEN && checkmark != MapCheckmark.WHITE) return;
				// Real bug found (per user report — "The room cleared notification triggers on all rooms
				// cleared and not only the room the user is currently in"): this listener fires for EVERY
				// room's checkmark transitioning (any teammate clearing any room anywhere in the dungeon
				// flips its checkmark, which this map-wide listener sees regardless of the local player's own
				// position) — there was no check at all restricting it to the room the player is actually
				// standing in. Only fires now when the just-cleared room is the player's own current room
				// (same room lookup StarredMobHighlightFeature already uses for the identical "only in my own
				// room" restriction).
				if (room != WorldScan.getCurrentRoom()) return;
				instance.fire(NotificationType.ROOM_CLEARED,
					checkmark == MapCheckmark.GREEN ? "Room Complete!" : "Room Cleared!");
			});
			Identifier notifId = Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeon_notifications");
			net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement notifElement = (graphics, tracker) -> {
				if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
					|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.render(graphics, x, y, instance.position.scale);
			};
			// Real bug found (per user report — "Dungeon Notifications GUI element should render above the
			// Dungeon Map, currently renders underneath"): both this element and Dungeon Map's own registered
			// via plain addLast, which only renders on top of whatever was already registered earlier — purely
			// a function of which feature happened to enable() first, not any deliberate ordering. Explicitly
			// anchoring after Dungeon Map's own id (attachElementAfter — Fabric's actual API for "always render
			// on top of X specifically") makes this correct regardless of enable order. Falls back to plain
			// addLast if Dungeon Map's id isn't registered at all (module disabled) — attachElementAfter throws
			// on a missing anchor.
			try {
				HudElementRegistry.attachElementAfter(
					Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeon_map"), notifId, notifElement);
			} catch (Exception e) {
				HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, notifId, notifElement);
			}
		}
	}

	private void onChatMessage(String text) {
		if (!DungeonState.isInDungeon()) return;

		if (isTypeEnabled(NotificationType.WATCHER_DONE_SPAWNING) && WATCHER_DONE_SPAWNING_PATTERN.matcher(text).find()) {
			fire(NotificationType.WATCHER_DONE_SPAWNING, "Watcher Done Spawning!");
		}
		if (isTypeEnabled(NotificationType.ALL_BLOOD_MOBS_DEAD) && ALL_BLOOD_MOBS_DEAD_PATTERN.matcher(text).find()) {
			fire(NotificationType.ALL_BLOOD_MOBS_DEAD, "All Blood Mobs Dead!");
		}
		if (isTypeEnabled(NotificationType.ULTIMATE_READY) && text.contains(ULTIMATE_READY_SUFFIX)) {
			long now = System.currentTimeMillis();
			boolean newOccurrence = now - lastUltimateReadyMillis > ULTIMATE_NEW_OCCURRENCE_GAP_MILLIS;
			lastUltimateReadyMillis = now;
			if (newOccurrence) fire(NotificationType.ULTIMATE_READY, "Ultimate Ready!");
		}
		if (!firedHealerShouldUlt && isTypeEnabled(NotificationType.HEALER_SHOULD_ULT) && MAXOR_TRICKED_PATTERN.matcher(text).find()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && classOf(mc.player.getName().getString()) == DungeonClass.HEALER) {
				firedHealerShouldUlt = true;
				fire(NotificationType.HEALER_SHOULD_ULT, "Ult Now!");
			}
		}
		if (isTypeEnabled(NotificationType.STORM_CRUSHED) && STORM_CRUSHED_PATTERN.matcher(text).find()) {
			fire(NotificationType.STORM_CRUSHED, "Storm Crushed!");
		}

		// Role-gated callouts only ever come from party chat. Real bug found (same root cause ChatCommands'
		// own fix already confirmed via a real debug log — Hypixel's ">" separator glyph here isn't
		// necessarily the literal ASCII character this used to hardcode): tolerant regex instead of a literal
		// "Party > " search, matching that same fix.
		Matcher partyMatch = PARTY_MARKER.matcher(text);
		if (!partyMatch.find() || partyMatch.start() > 2) return;
		String rest = text.substring(partyMatch.end());
		int colonIdx = rest.indexOf(':');
		if (colonIdx < 0) return;
		String username = extractUsername(rest.substring(0, colonIdx));
		if (username == null) return;
		String msg = rest.substring(colonIdx + 1).trim();

		DungeonClass senderClass = classOf(username);
		if (senderClass == null) return;

		if (isTypeEnabled(NotificationType.HEALER_AT_SS) && senderClass == DungeonClass.HEALER && msg.contains("SS")) {
			fire(NotificationType.HEALER_AT_SS, "Healer at SS!");
		}
		if (isTypeEnabled(NotificationType.BERSERK_AT_GY_DROPOFF) && senderClass == DungeonClass.BERSERK && msg.contains("GY")) {
			fire(NotificationType.BERSERK_AT_GY_DROPOFF, "Berserker at GY Dropoff!");
		}
		if (isTypeEnabled(NotificationType.BERSERK_WAITING_FOR_HEALER) && senderClass == DungeonClass.BERSERK && msg.contains("Waiting")) {
			fire(NotificationType.BERSERK_WAITING_FOR_HEALER, "Berserker Waiting for Healer!");
		}
		if (isTypeEnabled(NotificationType.EARLY_ENTER)) {
			Matcher eeMatch = EARLY_ENTER_TOKEN.matcher(msg);
			if (eeMatch.find()) {
				String token = eeMatch.group(1).toUpperCase(java.util.Locale.ROOT);
				fire(NotificationType.EARLY_ENTER, username + " is at " + token + "!", username);
			}
		}
	}

	private static DungeonClass classOf(String username) {
		for (DungeonPlayer player : DungeonState.getTeammates()) {
			if (player.name.equals(username)) return player.clazz;
		}
		return null;
	}

	/** Same "[rank] [rank] Name" blob parsing as ChatCommandsFeature's own extractUsername — the last
	 *  whitespace-separated bare token that looks like a real Minecraft username, since rank tags are
	 *  always bracketed and the real name never is. */
	private static final Pattern BARE_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static String extractUsername(String nameBlob) {
		String[] parts = nameBlob.trim().split(" ");
		for (int i = parts.length - 1; i >= 0; i--) {
			if (BARE_USERNAME.matcher(parts[i]).matches()) return parts[i];
		}
		return null;
	}

	private void fire(NotificationType type, String defaultTitleText) {
		fire(type, defaultTitleText, null);
	}

	/** Called by {@link DoorHighlightFeature} the moment it detects a Wither/Blood Key armor stand spawn —
	 *  that detection stays there (it's real gameplay-entity tracking, not a chat line), but the actual
	 *  alert now goes through this module's own title box/color/party-chat/sound pipeline instead of a raw
	 *  vanilla Hud.setTitle call, per user request ("The key drop notification should go in the Dungeon
	 *  notifications module"). No-ops if this module, or the Key Drop type specifically, is disabled.
	 *
	 *  <p>Real bug found (per user report — "The key drop alert doesnt support both Blood and Wither key.
	 *  They should have the same colorcoding. The only color the user should be able to change is the part
	 *  after the key name... 'Blood key' will be red, and the rest 'spawned!' will be whatever color the
	 *  user chooses"): both key names used to share ONE flat user-configurable title color, so there was no
	 *  way to tell a Blood Key alert from a Wither Key alert by color, and the user's color choice affected
	 *  the whole string including the key name. The key name itself now always gets a fixed color per key
	 *  (matching the existing chat-message convention below: §c red for Blood, §8 dark gray for Wither) via
	 *  an embedded legacy color code, then §r resets formatting before " spawned!" so that part alone falls
	 *  back to the title's normal user-customizable color (set via {@link #getTypeColor}) — the base color
	 *  {@code render()} passes in only applies to text with no explicit style, exactly like vanilla titles. */
	public static void fireKeyDrop(String keyName) {
		if (instance == null || !instance.isEnabled() || !instance.isTypeEnabled(NotificationType.KEY_DROP)) return;
		keyDropTitleShownAtMillis = System.currentTimeMillis();
		String keyColorCode = "Wither Key".equals(keyName) ? "§8" : "§c";
		instance.fire(NotificationType.KEY_DROP, keyColorCode + keyName + "§r spawned!");
	}

	/** Called by {@link ChatCommandsFeature} the moment it parses a "!dt"/"!downtime" party chat command from
	 *  ANOTHER party member, only for the local player when they're the party leader (per user request: "if
	 *  the local player is party leader, show a title on screen"). Distinct from ChatCommandsFeature's own
	 *  pre-existing "!dt" reminder-queue system, which still runs unaffected — this is purely an immediate
	 *  on-screen alert so a busy leader notices the request right away instead of only at run-end. */
	public static void fireDowntimeRequest(String username, String reason) {
		if (instance == null || !instance.isEnabled() || !instance.isTypeEnabled(NotificationType.DT_REQUEST)) return;
		instance.fire(NotificationType.DT_REQUEST, "DT! " + username + " \"" + reason + "\"", username);
	}

	/** Called by {@link SimonSaysFeature} the moment its own tracked solution empties on a correct final
	 *  click AND that round's revealed sequence had reached the real max length (5) — the same detection
	 *  point its "SS 5/5" party-chat announcement already fires from, per user request ("using the same
	 *  detection method"), not a new/separate signal. */
	public static void fireSimonSaysComplete() {
		if (instance == null || !instance.isEnabled() || !instance.isTypeEnabled(NotificationType.SIMON_SAYS_COMPLETE)) return;
		instance.fire(NotificationType.SIMON_SAYS_COMPLETE, "Simon Says Complete!");
	}

	// See fireKeyDrop/fire()'s own doc comments — both types now share this class's single title-box slot,
	// so this timestamp is how Room Cleared still knows to skip stomping a still-fresh Key Drop title,
	// exactly the cross-feature check DoorHighlightFeature.isKeyTitleActive() used to do before Key Drop
	// moved into this class.
	private static long keyDropTitleShownAtMillis = 0L;

	private void fire(NotificationType type, String defaultTitleText, String username) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String titleText = getTypeTitle(type, defaultTitleText);
		titleText = resolveUsernamePlaceholder(titleText, username);
		titleText = translateAmpersandCodes(titleText);
		// Per user request ("Blood key dropped notification should outweigh the room cleared notification"):
		// this mod's own panel only ever shows one title at a time, and Room Cleared is the one of these two
		// types actually prone to firing right around the same moment a key spawns (both keys, opening a
		// door, etc. all cluster near room transitions) — skips just the title (sound/party chat still fire
		// normally) while a still-fresh Key Drop title is on screen, rather than overwriting it.
		boolean suppressTitle = type == NotificationType.ROOM_CLEARED
			&& System.currentTimeMillis() - keyDropTitleShownAtMillis < TITLE_DISPLAY_MILLIS;
		if (showTitle && !suppressTitle) {
			activeTitleText = titleText;
			activeTitleColor = 0xFF000000 | (getTypeColor(type) & 0xFFFFFF);
			activeTitleUntilMillis = System.currentTimeMillis() + TITLE_DISPLAY_MILLIS;
		}
		if (playSound) mc.player.playSound(SoundEvents.PLAYER_LEVELUP, 1f, 1f);
		String partyMsg = partyChatMessages.get(type);
		if (partyChatEnabled.getOrDefault(type, false) && partyMsg != null && !partyMsg.isBlank()
			&& mc.player.connection != null) {
			mc.player.connection.sendCommand("pc " + partyMsg);
		}
	}

	private static String resolveUsernamePlaceholder(String text, String username) {
		if (username == null) return text;
		return USERNAME_PLACEHOLDER.matcher(text).replaceAll(Matcher.quoteReplacement(username));
	}

	private static String translateAmpersandCodes(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		char[] chars = text.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			char c = chars[i];
			if (c == '&' && i + 1 < chars.length && LEGACY_CODE_CHARS.indexOf(Character.toLowerCase(chars[i + 1])) >= 0) {
				sb.append('§').append(chars[i + 1]);
				i++;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	// ---- MoveableWidget: this feature's own draggable HUD panel, replacing the vanilla title HUD ----

	@Override
	public String getId() { return "dungeon_notifications"; }

	@Override
	public String getDisplayName() { return "Dungeon Notifications"; }

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() {
		return isEnabled() && showTitle && activeTitleText != null && System.currentTimeMillis() < activeTitleUntilMillis;
	}

	@Override
	public boolean isRelevantToCurrentIsland() { return DungeonState.isInDungeon(); }

	@Override
	public boolean hasVisibleContent() { return isVisible(); }

	// Per user report ("gui element text should always center regardless of length, currently drifts off-
	// center on longer text"): a title-style element should keep its visual center pinned to the anchor the
	// user actually dragged it to, same as vanilla's own title HUD — see MoveableWidget#alwaysCentersOnAnchor's
	// own doc comment for why this needs a distinct flag from rightAlignsPastCenter.
	@Override
	public boolean alwaysCentersOnAnchor() { return true; }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		String text = activeTitleText != null ? activeTitleText : "§d§lDungeon Notifications";
		return renderText(graphics, x, y, scale, text);
	}

	private Size renderText(GuiGraphicsExtractor graphics, int x, int y, float scale, String text) {
		Font font = Minecraft.getInstance().font;
		String stylePrefix = (boldTitle ? "§l" : "") + (italicTitle ? "§o" : "");
		String rendered = stylePrefix + text;
		int panelWidth = font.width(rendered) + 12;
		int panelHeight = font.lineHeight + 8;
		// alwaysCentersOnAnchor(): x is the visual CENTER, not the left edge — shift the actual draw origin
		// so the panel is centered on it regardless of how wide this particular message is. The scale
		// pivot below stays at the real anchor x/y (not drawX) so zooming still happens around the anchor.
		int drawX = x - panelWidth / 2;

		if (Math.abs(scale - 1f) < 0.01f) {
			drawPanel(graphics, font, drawX, y, rendered, panelWidth, panelHeight);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			drawPanel(graphics, font, drawX, y, rendered, panelWidth, panelHeight);
			graphics.pose().popMatrix();
		}
		return new Size(Math.round(panelWidth * scale), Math.round(panelHeight * scale));
	}

	// Per user request ("Remove the background from the dungeon notifications gui element"): text-only now,
	// same as a vanilla title, no panel drawn behind it. panelWidth/panelHeight (and the returned Size) stay
	// as they were — still needed as the element's real drag/selection hitbox in the GUI editor even though
	// nothing visible fills it anymore.
	private void drawPanel(GuiGraphicsExtractor graphics, Font font, int x, int y, String text, int panelWidth, int panelHeight) {
		graphics.text(font, text, x + 6, y + 4, activeTitleColor);
	}

	public boolean isShowTitle() { return showTitle; }
	public void setShowTitle(boolean value) { showTitle = value; }
	public boolean isPlaySound() { return playSound; }
	public void setPlaySound(boolean value) { playSound = value; }
	public int getTitleColor() { return titleColor; }
	public void setTitleColor(int value) { titleColor = value; }
	public boolean isTypeEnabled(NotificationType type) { return enabled.getOrDefault(type, true); }
	public void setTypeEnabled(NotificationType type, boolean value) { enabled.put(type, value); }
	public boolean isBoldTitle() { return boldTitle; }
	public void setBoldTitle(boolean value) { boldTitle = value; }
	public boolean isItalicTitle() { return italicTitle; }
	public void setItalicTitle(boolean value) { italicTitle = value; }

	/** Resolved title for this type: the user's own override if they've typed one, else the caller-supplied
	 *  default. {@code getTypeTitleRaw} is the unresolved version (empty string when unset) the settings
	 *  panel's text field actually edits. */
	public String getTypeTitle(NotificationType type, String defaultTitleText) {
		String custom = customTitles.get(type);
		return custom != null && !custom.isEmpty() ? custom : defaultTitleText;
	}
	public String getTypeTitleRaw(NotificationType type) { return customTitles.getOrDefault(type, ""); }
	public void setTypeTitle(NotificationType type, String value) { customTitles.put(type, value); }

	public int getTypeColor(NotificationType type) { return typeColors.getOrDefault(type, titleColor); }
	public void setTypeColor(NotificationType type, int value) { typeColors.put(type, value); }

	public String getPartyChatMessage(NotificationType type) { return partyChatMessages.getOrDefault(type, ""); }
	public void setPartyChatMessage(NotificationType type, String value) { partyChatMessages.put(type, value); }
	public boolean isPartyChatEnabled(NotificationType type) { return partyChatEnabled.getOrDefault(type, false); }
	public void setPartyChatEnabled(NotificationType type, boolean value) { partyChatEnabled.put(type, value); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showTitle", showTitle);
		obj.addProperty("playSound", playSound);
		obj.addProperty("titleColor", titleColor);
		obj.addProperty("boldTitle", boldTitle);
		obj.addProperty("italicTitle", italicTitle);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		for (NotificationType type : NotificationType.values()) {
			obj.addProperty(type.name(), enabled.getOrDefault(type, true));
			if (customTitles.containsKey(type)) obj.addProperty(type.name() + "_title", customTitles.get(type));
			if (typeColors.containsKey(type)) obj.addProperty(type.name() + "_color", typeColors.get(type));
			if (partyChatMessages.containsKey(type)) obj.addProperty(type.name() + "_partyMsg", partyChatMessages.get(type));
			if (partyChatEnabled.containsKey(type)) obj.addProperty(type.name() + "_partyEnabled", partyChatEnabled.get(type));
		}
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("showTitle")) showTitle = obj.get("showTitle").getAsBoolean();
		if (obj.has("playSound")) playSound = obj.get("playSound").getAsBoolean();
		if (obj.has("titleColor")) titleColor = obj.get("titleColor").getAsInt();
		// "repeatUltimateReminders" key from an older config is silently ignored — repeats are always
		// collapsed now, per user request, nothing left for it to control.
		if (obj.has("boldTitle")) boldTitle = obj.get("boldTitle").getAsBoolean();
		if (obj.has("italicTitle")) italicTitle = obj.get("italicTitle").getAsBoolean();
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
		for (NotificationType type : NotificationType.values()) {
			if (obj.has(type.name())) enabled.put(type, obj.get(type.name()).getAsBoolean());
			if (obj.has(type.name() + "_title")) customTitles.put(type, obj.get(type.name() + "_title").getAsString());
			if (obj.has(type.name() + "_color")) typeColors.put(type, obj.get(type.name() + "_color").getAsInt());
			if (obj.has(type.name() + "_partyMsg")) partyChatMessages.put(type, obj.get(type.name() + "_partyMsg").getAsString());
			if (obj.has(type.name() + "_partyEnabled")) partyChatEnabled.put(type, obj.get(type.name() + "_partyEnabled").getAsBoolean());
		}
	}

	@Override
	public String getDescription() {
		return "Shows title and sound alerts for dungeon events (room cleared, blessings, key drops, boss phases, etc.), detected from chat.";
	}
}
