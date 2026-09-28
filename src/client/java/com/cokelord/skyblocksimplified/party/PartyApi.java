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
 *
 * <p>Per user request, following up on the "chat commands don't work in party" leader-detection
 * regression: {@link #isLeader()}/{@link #leader()} now prefer Hypixel's own real Mod API party packet
 * (see {@link HypixelPartyApi}) whenever it has a confirmed answer, falling back to this class's own
 * passive chat tracking otherwise (membership also resyncs from the Mod API party packet). That tracking is left fully in place — it's still the only
 * source for the full member list, and the fallback for a session where the Mod API bridge isn't active.
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
	// Real, confirmed lines cross-checked against Odin's own PartyUtils.kt (the user provided its source
	// specifically to fix this) — three more organic, passive signals for the party leader that this class
	// was missing entirely, none of which need this class to ever send anything: Hypixel's own leader-
	// disconnect/rejoin notices, and the fact that only the real leader can ever send a party into the
	// Dungeon/Kuudra finder queue. Odin's own version relies on passive signals like these ALONE — see
	// register()'s own doc comment for why this codebase still keeps one active fallback probe on top,
	// rather than matching Odin's design exactly.
	private static final Pattern LEADER_DISCONNECTED = Pattern.compile(
		"The party leader, (?<name>.*) has disconnected, they have 5 minutes to rejoin before the party is disbanded\\.");
	private static final Pattern LEADER_REJOINED = Pattern.compile("The party leader (?<name>.*) has rejoined\\.");
	private static final Pattern QUEUED_IN_FINDER = Pattern.compile("Party Finder > Your party has been queued in the dungeon finder!");
	// Odin's own heuristic, not a certainty (with the "All Invite" party setting on, a non-leader can also
	// invite) — only ever used to fill in an otherwise-unknown leader, same "last resort, never overwrites a
	// real known value" spirit as every other guess in this class.
	private static final Pattern PARTY_INVITE = Pattern.compile(
		"(?<inviter>.*) invited (?<invitee>.*) to the party! They have 60 seconds to accept\\.");

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
	 *  saw a "/party list" response). Prefers the Mod-API-confirmed leader — see {@link #confirmedLeader()}. */
	public static String leader() {
		String confirmed = confirmedLeader();
		return confirmed != null ? confirmed : partyLeader;
	}

	public static boolean isLeader() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		String confirmed = confirmedLeader();
		if (confirmed != null) return confirmed.equals(mc.player.getName().getString());
		return partyLeader != null && partyLeader.equals(mc.player.getName().getString());
	}

	/** Real, server-confirmed leader per Hypixel's own Mod API party packet (see {@link HypixelPartyApi}),
	 *  when available — always preferred over the passive chat-scraped {@link #partyLeader} the same way
	 *  IslandGate always prefers a confirmed Mod API island over its own sidebar fallback. Discovered while
	 *  fixing the "chat commands don't work in party" regression (per user question: "doesnt hypixel mod api
	 *  feed party data?") — this is a real fix for the underlying fragility, not just the one regression:
	 *  every {@code isLeader()}-gated command now has an authoritative source that doesn't depend on having
	 *  organically observed a join/transfer line or having Hypixel's opt-in Party tab-list widget enabled.
	 *  Falls back to the chat-scraped value whenever the Mod API bridge isn't active, hasn't received a
	 *  response yet, or the leader's UUID doesn't resolve to a name in this client's local player-info cache. */
	private static String confirmedLeader() {
		return isModApiActive() ? HypixelPartyApi.confirmedLeaderName() : null;
	}

	// Real bug found (per user report — "Chat commands actually does not work in a party"): every leader-
	// gated command (!warp, !kick, !promote, !demote, !pt, !kickoffline, !f1-!t5, !reinvite — i.e. most of
	// what ChatCommandsFeature's party commands actually DO) depends on isLeader(), but this class is 100%
	// passive: partyLeader only ever gets set by organically OBSERVING a join/transfer/`/party list` chat
	// line. If the party already existed before this listener started watching this session (rejoining a
	// world/server mid-party, a reconnect, or simply never having run `/party list` since login), the leader
	// is never learned at all and stays null for the rest of the session — silently no-opping every one of
	// those commands with zero feedback, for the real leader included. Self-heals via the "known members,
	// unknown leader" check at the bottom of onChat(), which schedules a silent `/party list` probe.
	//
	// Real bug found (per user report — "the /p list thing is still a thing even though it can read the
	// tablist... is it even important, can it be tablist read instead?"): this schedules the same probe
	// exactly {@link #BOOTSTRAP_DELAY_MILLIS} after every Hypixel connection — a round later, that scheduling
	// was removed outright (kept only as a REACTIVE "known members, unknown leader" check further down),
	// since the tablist scan below covers the common "joined mid-party" gap more precisely. That overcorrected
	// into a real regression (per a later report — "party commands don't work... guild worked... either
	// entirely broken or its only party"): a player reconnecting to an ALREADY-EXISTING party gets no organic
	// join/transfer line at all (nobody just joined), and the tablist scan only works if the player has
	// Hypixel's own opt-in "Party" tab-list widget enabled — with neither signal, partyLeader stayed null for
	// the entire session, silently no-oping every leader-gated command while guild's (which don't check
	// isLeader() at all) kept working. Restored, but the actual send now only happens if the leader is STILL
	// unknown once the delay elapses (see the join-triggered scheduling's own doc comment) — so it's a real
	// guaranteed fallback again without reintroducing the original "runs every login even when unneeded"
	// complaint. Checked Odin's own PartyUtils.kt (the user provided its source): Odin never sends
	// `/party list` at all — it's purely passive. Rather than copy that exactly (which really does just
	// accept "leader unknown" for however long no organic signal happens to fire), this keeps the probe as a
	// real last-resort AND adds three more organic signals Odin's own file has that this class was missing
	// (leader disconnect/rejoin notices, and the dungeon/Kuudra-finder queue confirmation only a real leader
	// can trigger — see LEADER_DISCONNECTED/LEADER_REJOINED/QUEUED_IN_FINDER above), so the probe should
	// rarely need to actually fire in practice even though it's always scheduled.
	private static final long BOOTSTRAP_DELAY_MILLIS = 3_000L;
	private static Long bootstrapAtMillis = null;

	private static Boolean modApiActive = null;

	/** Same lazy, defensive pattern as IslandGate.isModApiActive() — checked/started lazily since Fabric's
	 *  mod list isn't guaranteed populated before this class's own static init, and wrapped so any
	 *  unexpected bridge failure just falls back to the existing chat-scraping approach instead of breaking
	 *  party tracking entirely. */
	private static boolean isModApiActive() {
		if (modApiActive == null) {
			boolean loaded = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("hypixel-mod-api");
			if (loaded) {
				try {
					HypixelPartyApi.start();
					modApiActive = true;
				} catch (Throwable t) {
					com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
						"HypixelPartyApi bridge failed to start, falling back to chat-scraped party leader", t);
					modApiActive = false;
				}
			} else {
				modApiActive = false;
			}
		}
		return modApiActive;
	}

	/** Fires the Mod API's own request half of the party-info request/response pair (see
	 *  HypixelPartyApi's own doc comment on why this isn't an EventPacket) — a no-op if the bridge isn't
	 *  active. Safe to call from any real party-change signal; never called on a timer. */
	private static void requestModApiUpdate() {
		if (isModApiActive()) HypixelPartyApi.requestUpdate();
	}

	/** Asks Hypixel's Mod API for fresh party info (answered via HypixelPartyApi listeners).
	 *  @return false when the Mod API bridge isn't installed/active. */
	public static boolean requestModApiPartyInfo() {
		if (!isModApiActive()) return false;
		HypixelPartyApi.requestUpdate();
		return true;
	}

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
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			bootstrapAtMillis = null;
			modApiActive = null;
			HypixelPartyApi.reset();
		});
		// Real bug found (per user report — "Chat commands don't seem to work in party... guild worked
		// earlier either chat commands are entirely broken or its only party"): removing this JOIN trigger
		// entirely (see its own removal's doc comment below) also removed the ONLY fallback for reconnecting
		// to a party that already existed before this session started — no organic join/transfer line ever
		// fires for a party nobody just joined, and the tablist scan only works at all if the player has
		// Hypixel's own opt-in "Party" tab-list widget enabled. Without either signal, partyMembers/
		// partyLeader stayed null for the WHOLE session, silently no-oping every leader-gated party command
		// (isLeader() requires a known leader) while guild commands — which don't depend on this at all —
		// kept working, exactly the reported split. Restored, but the actual send below is now gated on
		// still not knowing the leader once the delay elapses (see that check's own doc comment) instead of
		// firing unconditionally, so the original redundant-probe complaint this was removed for doesn't come
		// back either: tablist/chat resolving the leader first (very likely well within this delay) still
		// skips it completely.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			bootstrapAtMillis = System.currentTimeMillis() + BOOTSTRAP_DELAY_MILLIS;
			// Fire the real authoritative request immediately (a tiny custom-payload packet, not a visible
			// chat command — no reason to wait the same delay the chat-based /party list fallback needs).
			requestModApiUpdate();
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (bootstrapAtMillis == null || System.currentTimeMillis() < bootstrapAtMillis) return;
			bootstrapAtMillis = null;
			// Only actually send the probe if something (tablist scan, an organic chat line, this same check
			// firing earlier from the reactive "known members, unknown leader" trigger) hasn't already
			// resolved the leader by now — the real fix for "redundant /party list on every login" was never
			// removing this check outright, just skipping the SEND once it's genuinely not needed anymore.
			if (partyLeader != null) return;
			if (client.player != null && client.player.connection != null) {
				// The reply to THIS specific silent probe is what gets hidden if it's the "not in a party"
				// flavor — start watching for it right as it's sent.
				autoReplyState = AutoReplySuppression.AWAITING_LEADING_DASH;
				autoReplyDeadlineMillis = System.currentTimeMillis() + AUTO_REPLY_WINDOW_MILLIS;
				client.player.connection.sendCommand("party list");
			}
		});
		// Per user request ("remove the tablist reading for party since we use hypixel-mod-api"): membership
		// now also comes from the Mod API party packet (UUID-keyed, server-confirmed) instead of scanning the
		// opt-in Party tab-list widget every tick.
		if (isModApiActive()) {
			HypixelPartyApi.addListener(packet -> Minecraft.getInstance().execute(() -> applyModApiParty(packet)));
		}
	}

	/** Resyncs members/leader from a Mod API party packet. A member whose UUID isn't in this client's
	 *  player-info cache yet (not on this server) keeps whatever chat tracking already knew, so an
	 *  incomplete resolution never shrinks a correct list. */
	private static void applyModApiParty(net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket packet) {
		if (!packet.isInParty()) {
			partyMembers.clear();
			partyLeader = null;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) return;
		java.util.Set<String> resolved = new LinkedHashSet<>();
		boolean allResolved = true;
		for (java.util.UUID uuid : packet.getMembers()) {
			var info = mc.getConnection().getPlayerInfo(uuid);
			if (info != null) resolved.add(info.getProfile().name());
			else allResolved = false;
		}
		if (allResolved) partyMembers.clear();
		for (String name : resolved) addPlayer(name);
		String leaderName = HypixelPartyApi.confirmedLeaderName();
		if (leaderName != null) partyLeader = leaderName;
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
			requestModApiUpdate();
		}
		matcher = TRANSFER_VOLUNTARY.matcher(stripped.replaceAll("§.", ""));
		if (matcher.matches()) {
			partyLeader = cleanName(matcher.group("newowner"));
			requestModApiUpdate();
		}

		String noColor = stripped.replaceAll("§.", "");
		matcher = LEADER_DISCONNECTED.matcher(noColor);
		if (matcher.matches()) partyLeader = cleanName(matcher.group("name"));
		matcher = LEADER_REJOINED.matcher(noColor);
		if (matcher.matches()) {
			partyLeader = cleanName(matcher.group("name"));
			requestModApiUpdate();
		}
		if (QUEUED_IN_FINDER.matcher(noColor).matches() && partyLeader == null) partyLeader = selfName();
		matcher = PARTY_INVITE.matcher(noColor);
		if (matcher.matches()) {
			String inviter = cleanName(matcher.group("inviter"));
			addPlayer(inviter);
			if (partyLeader == null) partyLeader = inviter;
		}

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
			requestModApiUpdate();
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
