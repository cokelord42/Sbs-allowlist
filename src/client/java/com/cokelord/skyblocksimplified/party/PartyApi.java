package com.cokelord.skyblocksimplified.party;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks who's currently in the player's party, purely by watching chat — ported from SkyHanni's
 * PartyApi.kt, keeping its confirmed join/leave/kick/disconnect/transfer/disband patterns and the
 * "/party list" response patterns. Doesn't cover the Kuudra/Dungeon Party Finder join lines or the
 * Spongebob easter egg — those aren't needed for a simple "who's in my party" tracker.
 */
public final class PartyApi {
	private PartyApi() {}

	// Real bug found (per user report — "Chat commands in party chat still don't work", after reading
	// Odin's own ChatManager/ChatPacketEvent source for comparison): Fabric's ClientReceiveMessageEvents
	// dispatches every registered ALLOW_GAME listener from ONE plain sequential loop with no per-listener
	// exception isolation at all (confirmed by decompiling EventFactory's generated invoker) — unlike
	// ContainerClickRegistry, which this codebase already hardened against exactly this failure mode (see
	// its own doc comment: "a broken rule now loses its own vote... instead of taking every other rule...
	// down"). This project registers over two dozen separate ALLOW_GAME listeners across many features; if
	// ANY of them throws while processing a given chat line, every listener registered AFTER it in Fabric's
	// internal array — which could easily include this class's own party-tracking listener, or
	// ChatCommandsFeature's — silently never runs for that exact message, with zero visible error. Since
	// ChatCommandsFeature depends on this class for isLeader()/members(), a party-chat command could look
	// broken for a completely unrelated reason elsewhere in the mod. Fixed generically (not by auditing
	// every other listener for exception-proofing) by registering both this class's own chat listener AND
	// ChatCommandsFeature's on a custom EARLY phase ordered before Fabric's DEFAULT_PHASE — since no other
	// feature in this codebase uses a custom phase, this guarantees party tracking and chat commands always
	// run before any other feature's listener gets a chance to throw and cut the loop short.
	public static final net.minecraft.resources.Identifier EARLY_CHAT_PHASE =
		net.minecraft.resources.Identifier.fromNamespaceAndPath(com.cokelord.skyblocksimplified.SkyblockSimplified.MOD_ID, "early_chat");

	// These used to hardcode literal "§e"/"§6"/"§c" color codes, but Component#getString() (what every
	// chat listener in this mod actually reads, this class included) strips all formatting and returns
	// plain text — those codes could never appear in the string being tested, so every regex below that
	// had one baked in NEVER matched, silently leaving partyLeader/partyMembers empty forever. That broke
	// isLeader() (always false) and therefore every leader-gated chat command (!warp, !kick, !promote,
	// !pt, !f1-!t5, etc.) plus Highlight Party Members. Confirmed via a real captured log line resolving
	// to plain "Party > [MVP+] heatniner: !m7" with zero § characters once formatting is stripped.
	private static final Pattern YOU_JOINED = Pattern.compile("You have joined (?<name>.*)'s? party!");
	private static final Pattern OTHER_JOINED = Pattern.compile("(?<name>.*) joined the party\\.");
	private static final Pattern OTHERS_IN_PARTY = Pattern.compile("You'll be partying with: (?<names>.*)");
	private static final Pattern OTHER_LEFT = Pattern.compile("(?<name>.*) has left the party\\.");
	private static final Pattern OTHER_KICKED = Pattern.compile("(?<name>.*) has been removed from the party\\.");
	private static final Pattern OTHER_OFFLINE_KICKED = Pattern.compile("Kicked (?<name>.*) because they were offline\\.");
	private static final Pattern OTHER_DISCONNECTED = Pattern.compile("(?<name>.*) was removed from your party because they disconnected\\.");
	private static final Pattern TRANSFER_ON_LEAVE = Pattern.compile("The party was transferred to (?<newowner>.*) because (?<name>.*) left");
	private static final Pattern TRANSFER_VOLUNTARY = Pattern.compile("The party was transferred to (?<newowner>.*) by (?<name>.*)");
	private static final Pattern DISBANDED = Pattern.compile(".* has disbanded the party!");
	private static final Pattern KICKED = Pattern.compile("You have been kicked from the party by .*");
	private static final Pattern MEMBERS_START = Pattern.compile("Party Members \\(\\d+\\)");
	private static final Pattern MEMBER_LIST = Pattern.compile("Party (?<kind>Leader|Moderators|Members): (?<names>.*)");

	// Per user request ("hide the 'You are not currently in a party.' spam from the mod's own silent
	// /party list probe"): Hypixel wraps that reply (and the "you're in a party" success reply below) in
	// the exact same plain horizontal-rule line before AND after the real content — a real chat line, not
	// a client-side decoration, so it has to be cancelled the same way as the message itself.
	private static final Pattern DASH_LINE = Pattern.compile("-{10,}");
	private static final String NOT_IN_PARTY_LINE = "You are not currently in a party.";

	private static final Set<String> partyMembers = new LinkedHashSet<>();
	private static String partyLeader = null;
	private static boolean registered = false;

	// Suppression state machine for this class's own silent "/party list" probe's "not in a party" reply —
	// set only from the one call site below that actually sends that command on this mod's own behalf, so
	// a player who types /party list themselves is never affected. AWAITING_LEADING_DASH is optimistic:
	// the leading dash line is identical for both the "not in a party" AND the "here's your party" replies,
	// so it gets cancelled immediately and, if the very next line turns out to belong to the success case
	// instead, is re-inserted verbatim (see onChat) so that case is never actually altered.
	private enum AutoReplySuppression { IDLE, AWAITING_LEADING_DASH, AWAITING_MESSAGE_LINE, AWAITING_TRAILING_DASH }
	private static final long AUTO_REPLY_WINDOW_MILLIS = 5_000L;
	private static AutoReplySuppression autoReplyState = AutoReplySuppression.IDLE;
	private static long autoReplyDeadlineMillis = 0L;
	private static String heldDashLine = null;

	public static Set<String> members() {
		return partyMembers;
	}

	public static boolean isInParty() {
		return !partyMembers.isEmpty();
	}

	/** Null if not in a party or the leader hasn't been observed yet (e.g. joined before this tracker
	 *  saw a "/party list" response). */
	public static String leader() {
		return partyLeader;
	}

	public static boolean isLeader() {
		Minecraft mc = Minecraft.getInstance();
		return partyLeader != null && mc.player != null && partyLeader.equals(mc.player.getName().getString());
	}

	// Real bug found (per user report — "Chat commands actually does not work in a party"): every leader-
	// gated command (!warp, !kick, !promote, !demote, !pt, !kickoffline, !f1-!t5, !reinvite — i.e. most of
	// what ChatCommandsFeature's party commands actually DO) depends on isLeader(), but this class is 100%
	// passive: partyLeader only ever gets set by organically OBSERVING a join/transfer/`/party list` chat
	// line. If the party already existed before this listener started watching this session (rejoining a
	// world/server mid-party, a reconnect, or simply never having run `/party list` since login), the leader
	// is never learned at all and stays null for the rest of the session — silently no-opping every one of
	// those commands with zero feedback, for the real leader included. Self-heals now by silently requesting
	// `/party list` shortly after every Hypixel connection — Hypixel's own reply (whether "you're in a
	// party, here's who" or "you're not in a party") is exactly the real signal onChat() already parses
	// correctly, so this just makes sure that signal actually gets asked for at least once per session
	// instead of only ever arriving by chance.
	private static final long BOOTSTRAP_DELAY_MILLIS = 3_000L;
	private static Long bootstrapAtMillis = null;

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientReceiveMessageEvents.ALLOW_GAME.addPhaseOrdering(EARLY_CHAT_PHASE, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
		ClientReceiveMessageEvents.ALLOW_GAME.register(EARLY_CHAT_PHASE, (message, overlay) -> {
			if (overlay) return true;
			try {
				return onChat(message.getString());
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("PartyApi chat listener threw, party tracking may be stale for this line", e);
				return true;
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> bootstrapAtMillis = System.currentTimeMillis() + BOOTSTRAP_DELAY_MILLIS);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> bootstrapAtMillis = null);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (bootstrapAtMillis == null || System.currentTimeMillis() < bootstrapAtMillis) return;
			bootstrapAtMillis = null;
			if (client.player != null && client.player.connection != null) {
				// The reply to THIS specific silent probe is what gets hidden if it's the "not in a party"
				// flavor — start watching for it right as it's sent.
				autoReplyState = AutoReplySuppression.AWAITING_LEADING_DASH;
				autoReplyDeadlineMillis = System.currentTimeMillis() + AUTO_REPLY_WINDOW_MILLIS;
				client.player.connection.sendCommand("party list");
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				scanTabListParty();
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("PartyApi tablist scan threw, skipping this tick", e);
			}
		});
	}

	// Per user request ("I also figured out another way of getting the party instead of using /party list
	// and reading the chat messages. It can detect through the tablist... a line saying 'Party: n/5' then 5
	// lines saying the entire party list"): a real Hypixel tab-list widget (opt-in in their own settings,
	// same category as the Pests/Jacob's Contest widgets TabListReader already reads for other features),
	// scanned every tick as a second, self-correcting membership source alongside the chat-based tracking
	// above rather than replacing it — chat lines can be missed (client just connected mid-party, a message
	// dropped), while this widget reflects the CURRENT true membership every time it's visible. Every match
	// overwrites partyMembers/partyLeader wholesale, the same full-resync treatment MEMBER_LIST's own
	// "/party list" reply already gets, since re-deriving from scratch here is just as cheap and immune to
	// drift from a stale add/remove.
	private static final Pattern TABLIST_PARTY_HEADER = Pattern.compile("Party: (\\d+)/5");
	private static final int TABLIST_MAX_MEMBERS = 5;
	// Only warn once per session, and only after enough consecutive misses that a player who simply doesn't
	// have their tab list open right now isn't told to change a setting they already have right.
	private static final int MISSING_WIDGET_WARNING_TICKS = 20 * 30;
	private static int ticksSinceTablistPartySeen = 0;
	private static boolean warnedMissingTablistWidget = false;

	private static void scanTabListParty() {
		java.util.List<String> lines = com.cokelord.skyblocksimplified.hud.TabListReader.readLines();
		int headerIndex = -1;
		int expectedCount = 0;
		for (int i = 0; i < lines.size(); i++) {
			Matcher m = TABLIST_PARTY_HEADER.matcher(lines.get(i).replaceAll("§.", ""));
			if (m.find()) {
				headerIndex = i;
				expectedCount = Integer.parseInt(m.group(1));
				break;
			}
		}
		if (headerIndex == -1) {
			ticksSinceTablistPartySeen++;
			if (!warnedMissingTablistWidget && !partyMembers.isEmpty()
				&& ticksSinceTablistPartySeen > MISSING_WIDGET_WARNING_TICKS) {
				warnedMissingTablistWidget = true;
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
						"§e[SBS] §fEnable the Party tab-list widget in Hypixel's own settings for more reliable party tracking."));
				}
			}
			return;
		}
		ticksSinceTablistPartySeen = 0;
		warnedMissingTablistWidget = false;
		if (expectedCount <= 0) return;

		java.util.Set<String> scanned = new LinkedHashSet<>();
		for (int i = headerIndex + 1; i < lines.size() && scanned.size() < Math.min(expectedCount, TABLIST_MAX_MEMBERS); i++) {
			String name = cleanName(lines.get(i));
			if (!name.isBlank() && name.matches("\\w{1,16}")) scanned.add(name);
		}
		if (scanned.isEmpty()) return;

		partyMembers.clear();
		for (String name : scanned) addPlayer(name);
		// Real, honest limitation: the widget doesn't mark who the leader is, so a leader already known from
		// chat tracking is preserved rather than being blanked out by a resync that has no opinion on it.
	}

	/** @return false to cancel/hide this chat line, true to let it render normally. */
	private static boolean onChat(String message) {
		String stripped = message.strip();

		boolean allow = handleAutoReplySuppression(stripped);

		Matcher matcher = YOU_JOINED.matcher(stripped);
		if (matcher.matches()) {
			String name = cleanName(matcher.group("name"));
			partyLeader = name;
			addPlayer(name);
		}
		matcher = OTHER_JOINED.matcher(stripped);
		if (matcher.matches()) {
			String name = cleanName(matcher.group("name"));
			if (partyMembers.isEmpty()) partyLeader = selfName();
			addPlayer(name);
		}
		matcher = OTHERS_IN_PARTY.matcher(stripped);
		if (matcher.matches()) {
			for (String name : matcher.group("names").split(", ")) {
				addPlayer(cleanName(name));
			}
		}
		matcher = OTHER_LEFT.matcher(stripped);
		if (matcher.matches()) partyMembers.remove(cleanName(matcher.group("name")));
		matcher = OTHER_KICKED.matcher(stripped);
		if (matcher.matches()) partyMembers.remove(cleanName(matcher.group("name")));
		matcher = OTHER_OFFLINE_KICKED.matcher(stripped);
		if (matcher.matches()) partyMembers.remove(cleanName(matcher.group("name")));
		matcher = OTHER_DISCONNECTED.matcher(stripped);
		if (matcher.matches()) partyMembers.remove(cleanName(matcher.group("name")));
		matcher = TRANSFER_ON_LEAVE.matcher(stripped.replaceAll("§.", ""));
		if (matcher.matches()) {
			partyLeader = cleanName(matcher.group("newowner"));
			partyMembers.remove(cleanName(matcher.group("name")));
		}
		matcher = TRANSFER_VOLUNTARY.matcher(stripped.replaceAll("§.", ""));
		if (matcher.matches()) partyLeader = cleanName(matcher.group("newowner"));

		// Real bug found: these literal comparisons still had the exact same baked-in "§e"/"§c" color-code
		// prefixes the field-level comment above already diagnosed and fixed for the regex Patterns — missed
		// here since it's a separate String#equals() check, not one of those Patterns. Component#getString()
		// strips all formatting the same way for every chat line, this block included, so none of these ever
		// matched either: leaving the party (or any of the other ways a party ends) never actually cleared
		// partyMembers/partyLeader, leaving stale membership/leader data behind for the rest of the session.
		if (DISBANDED.matcher(stripped).matches() || KICKED.matcher(stripped).matches()
			|| stripped.equals("You left the party.")
			|| stripped.equals("The party was disbanded because all invites expired and the party was empty.")
			|| stripped.equals("You are not currently in a party.")
			|| stripped.equals("You are not in a party.")
			|| stripped.equals("The party was disbanded because the party leader disconnected.")) {
			partyMembers.clear();
			partyLeader = null;
		}

		if (MEMBERS_START.matcher(stripped.replaceAll("§r", "")).matches()) {
			partyMembers.clear();
		}
		matcher = MEMBER_LIST.matcher(stripped.replaceAll("§.", ""));
		if (matcher.matches()) {
			boolean isLeader = matcher.group("kind").equals("Leader");
			for (String name : matcher.group("names").split(" ● ")) {
				String cleaned = cleanName(name.replace(" ●", ""));
				addPlayer(cleaned);
				if (isLeader) partyLeader = cleaned;
			}
		}

		// Real bug found (per the umpteenth "chat commands still don't work" report — this time traced past
		// the channel-marker matching, which was already independently confirmed correct against the user's
		// own debug log): OTHERS_IN_PARTY ("You'll be partying with: A, B, C" — the line Hypixel actually
		// sends when a party finishes forming via the Dungeon Party Finder / matchmaking accept flow, not the
		// normal "/party invite" one-at-a-time flow every other branch above assumes) adds every named member
		// but never learns who the leader is — that message doesn't say. Every leader-gated chat command
		// (!f1-!t5, !warp, !kick, !promote...) depends on isLeader(), so a party formed this way silently
		// no-ops all of them forever, for the real leader included, with zero feedback — exactly the "the
		// debug line prints but the command just does nothing" symptom reported. Re-uses the same self-heal
		// this class already established for a stale post-reconnect party (BOOTSTRAP_DELAY_MILLIS + a
		// silent "/party list" request): whenever known members exist but the leader is still unknown, ask.
		if (!partyMembers.isEmpty() && partyLeader == null && bootstrapAtMillis == null) {
			bootstrapAtMillis = System.currentTimeMillis() + BOOTSTRAP_DELAY_MILLIS;
		}

		return allow;
	}

	/** Advances {@link #autoReplyState} for one incoming chat line and reports whether that line should be
	 *  cancelled. Only ever active for a few seconds right after this class's own silent "/party list"
	 *  probe (see the tick handler in {@link #register()}) — never for a player-typed command. */
	private static boolean handleAutoReplySuppression(String stripped) {
		if (autoReplyState == AutoReplySuppression.IDLE) return true;
		if (System.currentTimeMillis() > autoReplyDeadlineMillis) {
			autoReplyState = AutoReplySuppression.IDLE;
			heldDashLine = null;
			return true;
		}

		switch (autoReplyState) {
			case AWAITING_LEADING_DASH:
				if (DASH_LINE.matcher(stripped).matches()) {
					// Optimistically held — restored below if this turns out to be the success reply instead.
					heldDashLine = stripped;
					autoReplyState = AutoReplySuppression.AWAITING_MESSAGE_LINE;
					return false;
				}
				// Not our reply at all (something else arrived first) — stop watching.
				autoReplyState = AutoReplySuppression.IDLE;
				return true;
			case AWAITING_MESSAGE_LINE:
				if (stripped.equals(NOT_IN_PARTY_LINE)) {
					heldDashLine = null;
					autoReplyState = AutoReplySuppression.AWAITING_TRAILING_DASH;
					return false;
				}
				// Not the "not in a party" line (most likely the "here's your party" success reply, e.g. a
				// MEMBER_LIST/MEMBERS_START line) — put back the leading dash line we held, verbatim and
				// still ahead of this line, so that case renders completely unchanged.
				restoreHeldDashLine();
				autoReplyState = AutoReplySuppression.IDLE;
				return true;
			case AWAITING_TRAILING_DASH:
				autoReplyState = AutoReplySuppression.IDLE;
				// Cancel the trailing dash line too, completing the 3-line suppression; if it's somehow
				// missing/different, there's nothing left to restore — just let this line through as-is.
				return !DASH_LINE.matcher(stripped).matches();
			default:
				return true;
		}
	}

	private static void restoreHeldDashLine() {
		if (heldDashLine == null) return;
		String dashLine = heldDashLine;
		heldDashLine = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(dashLine));
		}
	}

	private static void addPlayer(String name) {
		if (name.equals(selfName())) return;
		partyMembers.add(name);
	}

	private static String selfName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? "" : mc.player.getName().getString();
	}

	/** Rank-prefixed chat names look like "[500✫] [MVP+] Throwpo" — any number of bracketed rank/level
	 *  tags (or none at all) followed by the real username. Takes the LAST whitespace-separated token
	 *  that's a bare valid Minecraft username instead of assuming a fixed bracket count, so it works for
	 *  every rank (default, VIP, MVP+, high network-level players with a level-star tag, etc.) — same
	 *  technique as ChatCommandsFeature's extractUsername, verified against a real captured chat line. */
	private static String cleanName(String raw) {
		String name = raw.strip();
		if (name.endsWith("'s")) name = name.substring(0, name.length() - 2);
		String[] parts = name.split(" ");
		for (int i = parts.length - 1; i >= 0; i--) {
			String candidate = parts[i].replaceAll("§.", "");
			if (candidate.matches("\\w{1,16}")) return candidate;
		}
		return name.replaceAll("§.", "");
	}
}
