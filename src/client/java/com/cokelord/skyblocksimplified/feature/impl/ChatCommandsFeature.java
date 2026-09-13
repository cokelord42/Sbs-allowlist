package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.debug.DebugLog;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.party.PartyApi;
import com.cokelord.skyblocksimplified.util.IslandGate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * "!command" party/guild/private-chat responders (boop, kick, coinflip, 8ball, etc.) plus optional chat
 * emote text replacement. Ported from Odin's {@code ChatCommands.kt}, one of its bigger grab-bag modules.
 * A few sub-features are dropped, each because the thing they depended on doesn't carry over to SBAR:
 * <ul>
 * <li>{@code tps} — Odin's server-TPS reading has no confirmed equivalent in this codebase and no reliable
 * client-only TPS source exists to build one from scratch for a minor chat command.</li>
 * <li>{@code odin}/{@code od} — was Odin's own Discord self-promotion link; meaningless for this mod.</li>
 * </ul>
 * Everything else (help, coords, boop, cf, 8ball, dice, racism, ping, fps, time, location, holding, party
 * warp/allinvite/transfer/promote/demote/kick/kickoffline, downtime reminders, reinvite, private invite,
 * and the {@code !f1}..{@code !m7}/{@code !t1}..{@code !t5} instance-join shortcuts) is ported faithfully.
 * The real instance ids (confirmed from Odin's own {@code MainCommand.kt}: {@code
 * catacombs_floor_<one..seven>}, {@code master_catacombs_floor_<one..seven>} for master mode, and {@code
 * kuudra_<normal|hot|burning|fiery|infernal>} for T1-T5) are sent to {@code /joininstance} directly via
 * {@link #instanceIdFor}.
 *
 * <p>Per user follow-up ("when the chat commands module is enabled, add those commands... /f7 instead of
 * the entire command... I meant actual commands, not party chat"): {@code /f1}-{@code /f7}, {@code /m1}-
 * {@code /m7}, {@code /t1}-{@code /t5} are ALSO registered as real client-side slash commands, for the
 * player's own personal use regardless of party chat — see {@link #INSTANCE_JOIN_COMMANDS} and
 * SkyblockSimplifiedClient's ClientCommandRegistrationCallback registration, which calls back into this
 * class's own {@link #instanceIdFor} so both entry points share one source of truth for the real ids. */
public class ChatCommandsFeature extends Feature {
	private enum ChatChannel { PARTY, GUILD, PRIVATE }

	// Odin's original regex (ported verbatim from regex101.com/r/joY7dm/1) hardcoded a single optional
	// "[rank]" bracket between the channel prefix and the username — but real Hypixel players above a
	// network-level threshold get a SECOND bracket first (a level-star tag, e.g. "[500✫] [MVP+] Name:"),
	// which that rigid alternation can't match at all, silently breaking every chat command for anyone
	// testing with such an account.
	//
	// That first fix wasn't enough either — per user report the module still did "whatsoever" nothing
	// even after it shipped. The real remaining problem: this used Matcher#matches(), which anchors BOTH
	// ends of the string. ANY deviation from the exact expected shape at all — a stray leading character,
	// an unaccounted-for icon Hypixel occasionally tucks in front of the channel tag, a name/status glyph
	// after the username but before the colon that the alternation didn't foresee — fails the WHOLE match
	// silently, with zero partial credit, which reads exactly like "detection doesn't work whatsoever."
	// Rewritten below to only require finding the channel marker near the start of the line and a colon
	// somewhere after it, then slicing out just the pieces in between — tolerant of anything unexpected
	// on either side, which is what actually makes this robust against however Hypixel decorates the line.
	private static final Pattern BARE_USERNAME = Pattern.compile("^\\w{1,16}$");
	private static final Pattern END_RUN_PATTERN = Pattern.compile(
		" {29}> EXTRA STATS <|^\\[NPC] Elle: Good job everyone\\. A hard fought battle come to an end\\. Let's get out of here before we run into any more trouble!$");
	// Widened from 2 to 5 as a hedge: Hypixel's rank tag ("[MVP++]") can push the channel marker further
	// from index 0 than originally assumed once server-side prefixes/icons are involved.
	private static final int CHANNEL_MARKER_LEADING_TOLERANCE = 5;
	// Real root cause found (per user's own FIFTH debug log on this exact symptom, still failing after the
	// \S-separator regex fix below): that regex still assumed the separator between "Party"/"Guild" and the
	// name is exactly one non-whitespace character surrounded by whitespace on both sides — a real captured
	// line ("Party > [VIP] cokelord422: !f7") that reads as exactly that shape when printed to a log STILL
	// failed to match, meaning some real character in there isn't behaving the way \s/\S expect even after
	// Unicode-aware whitespace normalization (a lookalike glyph Java's own Unicode tables don't classify as
	// either \s or \S the way a human eye reading the log would assume). Rather than chase a sixth guess at
	// exactly which separator shape Hypixel uses, this drops the separator-matching requirement completely:
	// only the bare literal word "Party"/"Guild" needs to appear near the start of the line at all — nothing
	// about what comes immediately after it needs to be inspected here. Everything between the end of that
	// literal word and the colon (rank tags, whatever separator glyph, extra spacing) is left for
	// extractUsername below, which already tokenizes on plain spaces and picks the LAST bare-username-shaped
	// token working backward from the colon — it doesn't care how many non-username tokens (brackets, stray
	// separator characters, whatever) come before that, so it's naturally immune to this whole class of bug.
	private static final String PARTY_MARKER = "Party";
	private static final String GUILD_MARKER = "Guild";
	// Any Unicode whitespace category (not just ASCII space) collapses to a plain space before matching —
	// see the real-bug comment at this class's normalization call site for why the old fixed-codepoint
	// replace() chain wasn't enough.
	private static final Pattern WHITESPACE_NORMALIZE = Pattern.compile("\\p{javaWhitespace}+");
	// Zero-width/format characters that carry no visual space at all but can still split up a literal
	// "Party"/"Guild"/"From " marker if Hypixel inserts one mid-word — stripped outright rather than
	// normalized to a space.
	private static final Pattern INVISIBLE_CHARS = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");

	private boolean chatEmotes = false;
	private boolean partyChatCommands = true;
	private boolean guildChatCommands = false;
	private boolean privateChatCommands = true;

	private boolean partyWarp = true;
	private boolean coords = true;
	private boolean partyAllInvite = true;
	private boolean boop = true;
	private boolean kick = true;
	private boolean coinFlip = true;
	private boolean eightBall = true;
	private boolean dice = true;
	private boolean partyTransfer = false;
	private boolean reinvite = false;
	private boolean ping = true;
	private boolean fps = true;
	private boolean dt = true;
	private boolean invite = true;
	private boolean autoConfirm = false;
	private boolean racism = false;
	private boolean time = false;
	private boolean partyDemote = false;
	private boolean partyPromote = false;
	private boolean kickOffline = false;
	private boolean location = true;
	private boolean holding = true;
	private boolean queInstance = true;

	private final List<Map.Entry<String, String>> dtReasons = new ArrayList<>();

	private static boolean listenersRegistered = false;
	private static ChatCommandsFeature instance;

	public ChatCommandsFeature() {
		super("chat_commands", "Chat Commands", FeatureCategory.INVENTORY, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			// Registered on PartyApi's EARLY_CHAT_PHASE — see that field's own doc comment (per user report,
			// "Chat commands in party chat still don't work") for why: Fabric's ALLOW_GAME dispatch has no
			// per-listener exception isolation, so a throw in any OTHER feature's default-phase chat listener
			// registered before this one could silently stop this listener from ever running for that line.
			// addPhaseOrdering is idempotent/order-independent to call again here — doesn't assume PartyApi's
			// own registration (which also declares this ordering) has necessarily run first.
			ClientReceiveMessageEvents.ALLOW_GAME.addPhaseOrdering(com.cokelord.skyblocksimplified.party.PartyApi.EARLY_CHAT_PHASE, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
			ClientReceiveMessageEvents.ALLOW_GAME.register(com.cokelord.skyblocksimplified.party.PartyApi.EARLY_CHAT_PHASE, (message, overlay) -> {
				if (instance != null && instance.isEnabled() && !overlay) {
					try {
						instance.onChatLine(message.getString());
					} catch (Exception e) {
						com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("ChatCommandsFeature chat listener threw, skipping this line", e);
					}
				}
				return true;
			});
			// Real structural gap found on deep re-comparison against Odin's own packet-level ChatPacketEvent
			// (which hooks the RAW incoming packet, not a Fabric API convenience event that only covers ONE of
			// the two distinct packet types Minecraft's post-secure-chat protocol can deliver a message on):
			// ALLOW_GAME only ever fires for ClientboundSystemChatPacket — genuinely anonymous, fully server-
			// authored text (join/leave notices, "/party list" replies, etc, which is why PartyApi's own
			// parsing of THOSE lines already works fine). But "Party > Name: !command" is decorated text
			// wrapped around something a real player actually TYPED, and Mojang's chat-reporting requirements
			// (mandatory since 1.19.1, still in force here) mean a compliant server is expected to deliver any
			// broadcast of real player-authored text as a disguised, still-technically-signed
			// ClientboundPlayerChatPacket instead — which Fabric routes to the completely separate ALLOW_CHAT
			// event, never ALLOW_GAME. If Hypixel does this for party/guild/private chat (plausible — it's
			// exactly the class of message chat-reporting compliance cares about), every previous fix to this
			// feature's ALLOW_GAME listener could have been correctly written and still never fire at all for
			// the one line type this feature actually needs. Hooking ALLOW_CHAT too, with the exact same
			// onChatLine parsing (its first Component parameter is the same fully-decorated display text
			// ALLOW_GAME hands over, so no separate extraction logic is needed) costs nothing if this
			// hypothesis turns out wrong — a line can only ever arrive on one of the two events, never both,
			// so there's no risk of double-handling a single command.
			ClientReceiveMessageEvents.ALLOW_CHAT.addPhaseOrdering(com.cokelord.skyblocksimplified.party.PartyApi.EARLY_CHAT_PHASE, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
			ClientReceiveMessageEvents.ALLOW_CHAT.register(com.cokelord.skyblocksimplified.party.PartyApi.EARLY_CHAT_PHASE, (message, signedMessage, sender, params, receptionTimestamp) -> {
				if (instance != null && instance.isEnabled()) {
					try {
						instance.onChatLine(message.getString());
					} catch (Exception e) {
						com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("ChatCommandsFeature signed-chat listener threw, skipping this line", e);
					}
				}
				return true;
			});
			ClientSendMessageEvents.MODIFY_CHAT.register(text -> instance != null && instance.isEnabled() ? instance.applyEmotes(text) : text);
			ClientSendMessageEvents.MODIFY_COMMAND.register(text -> instance != null && instance.isEnabled() ? instance.applyEmotesToCommand(text) : text);
		}
	}

	private void onChatLine(String rawText) {
		// Real bug found (per user report — "Chat commands still don't work" in party/private chat, a
		// FOURTH round on this exact symptom, this time with a real captured line to work from): every
		// theory that could be checked statically (short-circuiting AND-compound event dispatch, an
		// uncaught-exception aborting the listener loop, the message arriving as an overlay/action-bar
		// delivery instead of real chat) was individually ruled out by decompiling Fabric API's own
		// ClientReceiveMessageEvents class (its compound invoker is a plain `iand` accumulator over ALL
		// registered listeners every time, no short-circuit; both this class's own listener and PartyApi's
		// are already exception-isolated; the user's own raw log line is tagged "[CHAT]", not an overlay
		// delivery) and by hand-tracing this exact captured line through the existing extractUsername/regex
		// logic (it already parses correctly once the plain-text form is reached). The one remaining
		// unverifiable variable: Hypixel is well known among Minecraft mod developers for occasionally using
		// non-breaking/lookalike Unicode whitespace (e.g. U+00A0) instead of a literal ASCII space inside
		// its own rank/channel-tag formatting — invisible to a human reading or even copy-pasting the text,
		// but fatal to a literal ASCII-space `indexOf("Party > ")` search, which would fail completely
		// silently (explaining the total absence of even a "channel marker too far from start" debug log).
		// Normalizing every whitespace-like character to a plain space before any matching below is a safe,
		// harmless hedge against that regardless of whether it's the true cause here.
		// Real bug found (per user's own FRESH debug log still showing a real, plainly-rendered
		// "Party > [VIP] cokelord422: !f7" line failing every marker, even after the fix above): hardcoding
		// individual guessed lookalike-space codepoints (U+00A0/U+2007/U+2060/U+200B one at a time) can never
		// cover every character Hypixel might actually use, and Java's \s/\S regex classes only recognize
		// plain ASCII whitespace by default (Pattern.UNICODE_CHARACTER_CLASS was never set on PARTY_MARKER/
		// GUILD_MARKER below) — a real Unicode whitespace char that LOOKS identical to a normal space when
		// printed to a log would still fail \s/\S matching even after this exact-codepoint replace() chain,
		// if it wasn't one of those four. Replaced the whole guess-the-codepoint approach with a real
		// Unicode-property regex sweep (\p{javaWhitespace} covers every Unicode whitespace category, not
		// just the ones spotted so far) plus a strip of common invisible zero-width/format characters, so no
		// future exotic separator glyph can silently break this again.
		String text = WHITESPACE_NORMALIZE.matcher(INVISIBLE_CHARS.matcher(rawText).replaceAll("")).replaceAll(" ");
		// Real bug found (per user-supplied Odin 0.3.2 source — ChatCommands.kt's own messageRegex anchors
		// flatly at the very start of the line with no tolerance window and no whitespace/invisible-char
		// normalization at all, yet is a confirmed-working reference): Odin never needed any of the
		// invisible-character theories chased across the many rounds above. The user's own debug log
		// (partyIdx=2, consistently, across multiple different senders/messages) proves this exact account
		// receives a fixed-length prefix before "Party" that survives both WHITESPACE_NORMALIZE (would
		// collapse to at most 1 char if it were real whitespace) and INVISIBLE_CHARS (which only lists five
		// specific zero-width codepoints) — almost certainly a small number of Unicode Private Use Area /
		// symbol characters (not classified as whitespace OR as one of those five exact codepoints) that this
		// specific account's client renders as invisible. Rather than add a sixth guessed exact character (the
		// same mistake made every previous round), strip ANY run of non-letter characters from the very start
		// of the line unconditionally — whatever Hypixel/the client prepends, "Party"/"Guild"/"From" is always
		// the first real WORD, so this structurally can't be broken by a future exotic prefix glyph again.
		text = text.replaceFirst("^[^\\p{L}]+", "");
		if (END_RUN_PATTERN.matcher(text).find()) {
			if (dt && !dtReasons.isEmpty()) {
				List<Map.Entry<String, String>> captured = new ArrayList<>(dtReasons);
				announceDowntime(captured);
				dtReasons.clear();
				DungeonQueueFeature dq = dungeonQueueFeature();
				if (dq != null) dq.setDisableRequeue(false);
			}
		}

		ChatChannel channel;
		String rest;
		// Only requires the marker to appear near the very start of the line (allowing for a stray leading
		// character or two) rather than anchoring the whole string — see the field-level comment above.
		int partyIdx = text.indexOf(PARTY_MARKER);
		int guildIdx = text.indexOf(GUILD_MARKER);
		int fromIdx = text.indexOf("From ");
		// Real bug found (per user report — "Chat commands STILL dont work and i actually cant tell why.
		// Either !cf does nothing or chat commands dont work. Add the debug line back."): a past round's own
		// diagnostic here (referenced by this same doc comment) was one of the 39 files mechanically stripped
		// during a later "clean out already-tested debug logs" round — except this one was NEVER actually
		// confirmed fixed, so removing it took away the one tool that could tell "!cf" apart from "chat
		// commands." Re-added: logs the raw normalized line plus all three marker indices the instant it
		// contains a "!" and any channel marker at all, whether or not the channel/tolerance check below
		// ultimately accepts it — a live tester can then see directly whether the failure is the channel
		// marker never being found/too far into the line (this check), or something further downstream
		// (the per-channel subtoggle, the command's own gate, or the module being disabled entirely).
		//
		// Real bug found (per user report — "chat commands gives me this debug line but doesnt seem to do
		// anything," posted after this exact line already confirmed the channel/marker detection was correct):
		// every instance-queue command (`!f1`-`!t5`) and most party commands (`!warp`/`!kick`/`!promote`/etc.)
		// are gated on PartyApi.isLeader(), which is 100% invisible from this log line alone — appended it here
		// so the next report already answers "was the leader even known" instead of needing another round.
		if (text.contains("!") && (partyIdx >= 0 || guildIdx >= 0 || fromIdx >= 0)) {
			DebugLog.throttled("chatcmd_line_" + text.hashCode(), 3000L,
				"ChatCommands saw: \"" + text + "\" (partyIdx=" + partyIdx + " guildIdx=" + guildIdx
					+ " fromIdx=" + fromIdx + " tolerance=" + CHANNEL_MARKER_LEADING_TOLERANCE
					+ " partyChatCommands=" + partyChatCommands + " guildChatCommands=" + guildChatCommands
					+ " privateChatCommands=" + privateChatCommands + " partyLeader=" + PartyApi.leader()
					+ " isLeader=" + PartyApi.isLeader() + " queInstance=" + queInstance + ")");
		}
		if (partyIdx < 0 && guildIdx < 0 && fromIdx < 0) {
			// Cheap heuristic to avoid logging every ordinary chat line (most lines contain none of these
			// words at all): only log a near-miss when the line looks like it was trying to be a channel
			// line but didn't match our literal marker — e.g. a different arrow glyph, extra formatting,
			// or a marker further into the line than expected. This is the exact signal needed to tell
			// whether party/private lines reach this method at all with a marker we fail to recognize.
			return;
		}
		if (partyIdx >= 0 && partyIdx <= CHANNEL_MARKER_LEADING_TOLERANCE) {
			if (!partyChatCommands) {
				return;
			}
			channel = ChatChannel.PARTY;
			rest = text.substring(partyIdx + PARTY_MARKER.length());
		} else if (guildIdx >= 0 && guildIdx <= CHANNEL_MARKER_LEADING_TOLERANCE) {
			if (!guildChatCommands) {
				return;
			}
			channel = ChatChannel.GUILD;
			rest = text.substring(guildIdx + GUILD_MARKER.length());
		} else if (fromIdx >= 0 && fromIdx <= CHANNEL_MARKER_LEADING_TOLERANCE) {
			if (!privateChatCommands) {
				return;
			}
			channel = ChatChannel.PRIVATE;
			rest = text.substring(fromIdx + "From ".length());
		} else {
			int nearestIdx = List.of(partyIdx, guildIdx, fromIdx).stream().filter(i -> i >= 0).min(Integer::compareTo).orElse(-1);
			return;
		}

		int colonIdx = rest.indexOf(':');
		if (colonIdx < 0) {
			return;
		}
		String nameBlob = rest.substring(0, colonIdx);
		String msg = rest.substring(colonIdx + 1).trim();

		String ign = extractUsername(nameBlob);
		if (ign == null) {
			return;
		}
		if (msg.isEmpty() || !msg.startsWith("!")) return;

		// Per user report ("chat commands gives me this debug line but doesnt seem to do anything," posted
		// again after this exact call chain was independently confirmed correct on paper against their own
		// captured line, gates included — partyLeader/isLeader/queInstance all checked out): the OUTER
		// listener registration already wraps onChatLine in a try/catch, but it only logs to the game's log
		// file (SkyblockSimplified.LOGGER), which the user has never been shown to have open — an exception
		// thrown anywhere inside handleChatCommand (a bad sendCommand call, an NPE, anything) would be
		// completely invisible to them: no chat message, no obvious error, exactly the reported symptom.
		// Catching here too and echoing the exception straight into the user's own chat makes a real failure
		// impossible to miss on the next attempt, instead of silently vanishing into a log file nobody's
		// watching.
		try {
			handleChatCommand(msg, ign, channel);
		} catch (Exception e) {
			chatMessage("§c[ChatCommands] " + msg + " failed: " + e);
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("ChatCommandsFeature failed handling \"" + msg + "\"", e);
		}
	}

	/** Picks the username out of a "[500✫] [MVP+] Name" style blob — the last whitespace-separated token
	 *  that's a bare valid Minecraft username, since every rank/level tag is bracketed and the real name
	 *  never is. Works no matter how many bracket tags precede it. */
	private static String extractUsername(String nameBlob) {
		String[] parts = nameBlob.trim().split(" ");
		for (int i = parts.length - 1; i >= 0; i--) {
			if (BARE_USERNAME.matcher(parts[i]).matches()) return parts[i];
		}
		return null;
	}

	private void handleChatCommand(String message, String name, ChatChannel channel) {
		String[] words = message.substring(1).split(" ");
		for (int i = 0; i < words.length; i++) words[i] = words[i].toLowerCase(java.util.Locale.ROOT);
		String command = words[0];
		String arg = words.length > 1 && words[1].length() <= 16 ? words[1] : null;

		switch (command) {
			case "help", "h" -> { if (true) channelMessage("Commands: " + availableCommands(channel), name, channel); }
			case "coords", "co" -> { if (coords) channelMessage(getPositionString(), name, channel); }
			case "boop" -> { if (boop && arg != null) sendCommand("boop " + arg); }
			case "cf" -> { if (coinFlip) channelMessage(Math.random() < 0.5 ? "heads" : "tails", name, channel); }
			case "8ball" -> { if (eightBall) channelMessage(RESPONSES[(int) (Math.random() * RESPONSES.length)], name, channel); }
			case "dice" -> { if (dice) channelMessage(1 + (int) (Math.random() * 6), name, channel); }
			case "racism" -> { if (racism) channelMessage(name + " is " + (1 + (int) (Math.random() * 100)) + "% racist. Racism is not allowed!", name, channel); }
			case "ping" -> { if (ping) channelMessage("Current Ping: " + currentPing() + "ms", name, channel); }
			case "fps" -> { if (this.fps) channelMessage("Current FPS: " + Minecraft.getInstance().getFps(), name, channel); }
			case "time" -> { if (time) channelMessage("Current Time: " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")), name, channel); }
			case "location" -> { if (location) channelMessage("Current Location: " + currentLocationName(), name, channel); }
			case "holding" -> { if (holding) channelMessage("Holding: " + holdingItemName(), name, channel); }

			case "warp", "w" -> { if (channel == ChatChannel.PARTY && partyWarp && PartyApi.isLeader()) sendCommand("party warp"); }
			case "allinvite", "allinv" -> { if (channel == ChatChannel.PARTY && partyAllInvite && PartyApi.isLeader()) sendCommand("party settings allinvite"); }
			case "pt", "ptme", "transfer" -> { if (channel == ChatChannel.PARTY && partyTransfer && PartyApi.isLeader()) sendCommand("party transfer " + (arg != null ? findPartyMember(arg) : name)); }
			case "promote" -> { if (channel == ChatChannel.PARTY && partyPromote && PartyApi.isLeader()) sendCommand("party promote " + (arg != null ? findPartyMember(arg) : name)); }
			case "demote" -> { if (channel == ChatChannel.PARTY && partyDemote && PartyApi.isLeader()) sendCommand("party demote " + (arg != null ? findPartyMember(arg) : name)); }
			case "kick", "k" -> { if (channel == ChatChannel.PARTY && kick && PartyApi.isLeader()) sendCommand("p kick " + (arg != null ? findPartyMember(arg) : name)); }
			case "kickoffline", "ko" -> { if (channel == ChatChannel.PARTY && kickOffline && PartyApi.isLeader()) sendCommand("p kickoffline"); }

			case "downtime", "dt" -> {
				if (!dt || channel != ChatChannel.PARTY) return;
				String reason = words.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length)).trim() : "";
				if (reason.isEmpty()) reason = "No reason given";
				if (dtReasons.stream().anyMatch(e -> e.getKey().equals(name))) { chatMessage("§6" + name + " §calready has a reminder!"); return; }
				chatMessage("§aReminder set for the end of the run! §7(disabled auto requeue for this run)");
				dtReasons.add(Map.entry(name, reason));
				DungeonQueueFeature dq = dungeonQueueFeature();
				if (dq != null) dq.setDisableRequeue(true);
				// Per user request ("if the local player is party leader, show a title on screen") — additive
				// to the reminder-queue behavior above, not a replacement for it.
				if (PartyApi.isLeader()) DungeonNotificationsFeature.fireDowntimeRequest(name, reason);
			}
			case "undowntime", "undt" -> {
				if (!dt || channel != ChatChannel.PARTY) return;
				if (dtReasons.stream().noneMatch(e -> e.getKey().equals(name))) { chatMessage("§6" + name + " §chas no reminder set!"); return; }
				chatMessage("§aReminder removed!");
				dtReasons.removeIf(e -> e.getKey().equals(name));
				if (dtReasons.isEmpty()) { DungeonQueueFeature dq = dungeonQueueFeature(); if (dq != null) dq.setDisableRequeue(false); }
			}
			case "f1", "f2", "f3", "f4", "f5", "f6", "f7", "m1", "m2", "m3", "m4", "m5", "m6", "m7", "t1", "t2", "t3", "t4", "t5" -> {
				if (!queInstance || channel != ChatChannel.PARTY || !PartyApi.isLeader()) return;
				chatMessage("§8Entering -> §e" + command.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + command.substring(1));
				// Per user report ("still no visible effect, even with isLeader=true confirmed") — this sends
				// the exact same string, via the exact same sendCommand(), that this class's own real /f1-/t5
				// client commands (SkyblockSimplifiedClient's ClientCommandRegistrationCallback block) send —
				// both call instanceIdFor(command) and pass the result to sendCommand("joininstance " + id)
				// with no other divergence. Printing the literal id here (not just in the debug log, which the
				// user may not have open) gives a directly comparable, chat-visible answer to "does this match
				// what /f7 sends" without needing to go find the debug output.
				String instanceId = instanceIdFor(command);
				DebugLog.throttled("chatcmd_joininstance_" + command, 1000L, "ChatCommands sending: joininstance " + instanceId);
				sendCommand("joininstance " + instanceId);
			}

			case "reinv", "reinvite" -> {
				if (!reinvite || channel != ChatChannel.PARTY || !PartyApi.isLeader()) return;
				chatMessage("§aReinviting §6" + name + " §ain 5 seconds...");
				String finalName = name;
				new Thread(() -> {
					try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
					Minecraft mc = Minecraft.getInstance();
					mc.execute(() -> sendCommand("p invite " + finalName));
				}, "skyblocksimplified-reinvite-delay").start();
			}

			case "invite", "inv" -> {
				if (invite && channel == ChatChannel.PRIVATE) {
					if (autoConfirm) { sendCommand("p invite " + name); return; }
					Minecraft mc = Minecraft.getInstance();
					if (mc.player == null) return;
					String finalName = name;
					Component msg = Component.literal("§aClick on this message to invite " + name + " to your party!").withStyle(style -> style
						.withClickEvent(new ClickEvent.RunCommand("/party invite " + finalName))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("§6Click to invite " + finalName + " to your party."))));
					mc.gui.hud.getChat().addClientSystemMessage(msg);
					mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f);
				}
			}
			default -> {}
		}
	}

	private void announceDowntime(List<Map.Entry<String, String>> captured) {
		Map<String, List<String>> byReason = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : captured) byReason.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());

		Minecraft mc = Minecraft.getInstance();
		String selfName = mc.player != null ? mc.player.getName().getString() : null;
		for (Map.Entry<String, String> entry : captured) {
			if (entry.getKey().equals(selfName)) sendCommand("pc Downtime needed: " + entry.getValue());
		}
		StringBuilder summary = new StringBuilder();
		for (Map.Entry<String, List<String>> entry : byReason.entrySet()) {
			if (!summary.isEmpty()) summary.append(", ");
			summary.append(String.join(", ", entry.getValue())).append(": ").append(entry.getKey());
		}
		chatMessage("DT Reasons: " + summary);
	}

	private DungeonQueueFeature dungeonQueueFeature() {
		Feature f = com.cokelord.skyblocksimplified.feature.FeatureRegistry.get("dungeon_queue");
		return f instanceof DungeonQueueFeature dq ? dq : null;
	}

	private String availableCommands(ChatChannel channel) {
		Map<String, Boolean> commands = switch (channel) {
			case PARTY -> {
				Map<String, Boolean> m = new LinkedHashMap<>();
				m.put("coords", coords); m.put("boop", boop); m.put("kick", kick); m.put("cf", coinFlip); m.put("8ball", eightBall);
				m.put("dice", dice); m.put("racism", racism); m.put("warp", partyWarp); m.put("allinvite", partyAllInvite);
				m.put("pt", partyTransfer); m.put("time", time); m.put("demote", partyDemote); m.put("promote", partyPromote);
				m.put("reinvite", reinvite); m.put("kickoffline", kickOffline); m.put("downtime", dt); m.put("location", location); m.put("holding", holding);
				m.put("f1-f7/m1-m7/t1-t5", queInstance);
				yield m;
			}
			case GUILD -> {
				Map<String, Boolean> m = new LinkedHashMap<>();
				m.put("coords", coords); m.put("boop", boop); m.put("cf", coinFlip); m.put("8ball", eightBall); m.put("dice", dice);
				m.put("racism", racism); m.put("ping", ping); m.put("time", time); m.put("location", location); m.put("holding", holding);
				yield m;
			}
			case PRIVATE -> {
				Map<String, Boolean> m = new LinkedHashMap<>();
				m.put("coords", coords); m.put("boop", boop); m.put("cf", coinFlip); m.put("8ball", eightBall); m.put("dice", dice);
				m.put("racism", racism); m.put("ping", ping); m.put("invite", invite); m.put("time", time); m.put("location", location); m.put("holding", holding);
				yield m;
			}
		};
		List<String> enabled = new ArrayList<>();
		for (Map.Entry<String, Boolean> e : commands.entrySet()) if (e.getValue()) enabled.add(e.getKey());
		return String.join(", ", enabled);
	}

	private static final String[] FLOOR_WORDS = {"one", "two", "three", "four", "five", "six", "seven"};

	/** Every instance-join shortcut this feature understands — shared with SkyblockSimplifiedClient's real
	 *  client-side /f1../t5 command registration (per user request: "when the chat commands module is
	 *  enabled, add those commands... /f7 instead of the entire command... I meant actual commands", not
	 *  just the party-chat !f1 triggers above), so both call sites stay in sync off one list. */
	public static final List<String> INSTANCE_JOIN_COMMANDS = List.of(
		"f1", "f2", "f3", "f4", "f5", "f6", "f7",
		"m1", "m2", "m3", "m4", "m5", "m6", "m7",
		"t1", "t2", "t3", "t4", "t5");

	/** Real Hypixel /joininstance ids, confirmed from Odin's own MainCommand.kt (Floors.instance()/
	 *  KuudraTier.instance()) — see the class doc comment. */
	public static String instanceIdFor(String command) {
		char kind = command.charAt(0);
		int n = command.charAt(1) - '1';
		if (kind == 'f' && n >= 0 && n < FLOOR_WORDS.length) return "catacombs_floor_" + FLOOR_WORDS[n];
		if (kind == 'm' && n >= 0 && n < FLOOR_WORDS.length) return "master_catacombs_floor_" + FLOOR_WORDS[n];
		return switch (command) {
			case "t1" -> "kuudra_normal";
			case "t2" -> "kuudra_hot";
			case "t3" -> "kuudra_burning";
			case "t4" -> "kuudra_fiery";
			case "t5" -> "kuudra_infernal";
			default -> "";
		};
	}

	private static String findPartyMember(String partialName) {
		for (String member : PartyApi.members()) if (member.toLowerCase(java.util.Locale.ROOT).contains(partialName.toLowerCase(java.util.Locale.ROOT))) return member;
		return partialName;
	}

	private void channelMessage(Object message, String name, ChatChannel channel) {
		switch (channel) {
			case GUILD -> sendCommand("gc " + message);
			case PARTY -> sendCommand("pc " + message);
			case PRIVATE -> sendCommand("msg " + name + " " + message);
		}
	}

	private static void sendCommand(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand(command);
	}

	private void chatMessage(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
	}

	private static String getPositionString() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return "Unknown";
		return String.format(java.util.Locale.ROOT, "X: %.0f, Y: %.0f, Z: %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
	}

	private static int currentPing() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getConnection() == null) return -1;
		PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return info != null ? info.getLatency() : -1;
	}

	private static String currentLocationName() {
		if (IslandGate.isInDungeon()) return "Catacombs";
		if (IslandGate.isInKuudra()) return "Kuudra's Hollow";
		if (IslandGate.isInGarden()) return "The Garden";
		if (IslandGate.isInCrimsonIsle()) return "Crimson Isle";
		if (IslandGate.isInHubOrLobby()) return "Hub";
		return "Unknown";
	}

	private static String holdingItemName() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return "Nothing :(";
		var stack = mc.player.getMainHandItem();
		if (stack.isEmpty()) return "Nothing :(";
		return stack.getHoverName().getString().replaceAll("§.", "");
	}

	private String applyEmotes(String message) {
		if (!chatEmotes) return message;
		return replaceEmotes(message);
	}

	private String applyEmotesToCommand(String command) {
		if (!chatEmotes) return command;
		for (String prefix : new String[]{"pc ", "ac ", "gc ", "msg ", "w ", "r "}) {
			if (command.startsWith(prefix)) {
				return prefix + replaceEmotes(command.substring(prefix.length()));
			}
		}
		return command;
	}

	private static String replaceEmotes(String message) {
		String[] words = message.split(" ");
		boolean replaced = false;
		for (int i = 0; i < words.length; i++) {
			String replacement = EMOTE_REPLACEMENTS.get(words[i]);
			if (replacement != null) { words[i] = replacement; replaced = true; }
		}
		return replaced ? String.join(" ", words) : message;
	}

	private static final String[] RESPONSES = {
		"It is certain", "It is decidedly so", "Without a doubt",
		"Yes definitely", "You may rely on it", "As I see it, yes",
		"Most likely", "Outlook good", "Yes", "Signs point to yes",
		"Reply hazy try again", "Ask again later", "Better not tell you now",
		"Cannot predict now", "Concentrate and ask again", "Don't count on it",
		"My reply is no", "My sources say no", "Outlook not so good", "Very doubtful"
	};

	private static final Map<String, String> EMOTE_REPLACEMENTS = new LinkedHashMap<>();
	static {
		EMOTE_REPLACEMENTS.put("<3", "❤");
		EMOTE_REPLACEMENTS.put("o/", "( ﾟ◡ﾟ)/");
		EMOTE_REPLACEMENTS.put(":star:", "✮");
		EMOTE_REPLACEMENTS.put(":yes:", "✔");
		EMOTE_REPLACEMENTS.put(":no:", "✖");
		EMOTE_REPLACEMENTS.put(":java:", "☕");
		EMOTE_REPLACEMENTS.put(":arrow:", "➜");
		EMOTE_REPLACEMENTS.put(":shrug:", "¯\\_(ツ)_/¯");
		EMOTE_REPLACEMENTS.put(":tableflip:", "(╯°□°）╯︵ ┻━┻");
		EMOTE_REPLACEMENTS.put(":totem:", "☉_☉");
		EMOTE_REPLACEMENTS.put(":typing:", "✎...");
		EMOTE_REPLACEMENTS.put(":maths:", "√(π+x)=L");
		EMOTE_REPLACEMENTS.put(":snail:", "@'-'");
		EMOTE_REPLACEMENTS.put("ez", "ｅｚ");
		EMOTE_REPLACEMENTS.put(":thinking:", "(0.o?)");
		EMOTE_REPLACEMENTS.put(":gimme:", "༼つ◕_◕༽つ");
		EMOTE_REPLACEMENTS.put(":wizard:", "('-')⊃━☆ﾟ.*･｡ﾟ");
		EMOTE_REPLACEMENTS.put(":pvp:", "⚔");
		EMOTE_REPLACEMENTS.put(":peace:", "✌");
		EMOTE_REPLACEMENTS.put(":puffer:", "<('O')>");
		EMOTE_REPLACEMENTS.put("h/", "ヽ(^◇^*)/");
		EMOTE_REPLACEMENTS.put(":sloth:", "(・⊝・)");
		EMOTE_REPLACEMENTS.put(":dog:", "(ᵔᴥᵔ)");
		EMOTE_REPLACEMENTS.put(":dj:", "ヽ(⌐■_■)ノ♬");
		EMOTE_REPLACEMENTS.put(":yey:", "ヽ (◕◡◕) ﾉ");
		EMOTE_REPLACEMENTS.put(":snow:", "☃");
		EMOTE_REPLACEMENTS.put(":dab:", "<o/");
		EMOTE_REPLACEMENTS.put(":cat:", "= ＾● ⋏ ●＾ =");
		EMOTE_REPLACEMENTS.put(":cute:", "(✿◠‿◠)");
		EMOTE_REPLACEMENTS.put(":skull:", "☠");
		EMOTE_REPLACEMENTS.put(":bum:", "♿");
	}

	public boolean isChatEmotes() { return chatEmotes; }
	public void setChatEmotes(boolean value) { chatEmotes = value; }
	public boolean isPartyChatCommands() { return partyChatCommands; }
	public void setPartyChatCommands(boolean value) { partyChatCommands = value; }
	public boolean isGuildChatCommands() { return guildChatCommands; }
	public void setGuildChatCommands(boolean value) { guildChatCommands = value; }
	public boolean isPrivateChatCommands() { return privateChatCommands; }
	public void setPrivateChatCommands(boolean value) { privateChatCommands = value; }
	public boolean isPartyWarp() { return partyWarp; }
	public void setPartyWarp(boolean value) { partyWarp = value; }
	public boolean isCoords() { return coords; }
	public void setCoords(boolean value) { coords = value; }
	public boolean isPartyAllInvite() { return partyAllInvite; }
	public void setPartyAllInvite(boolean value) { partyAllInvite = value; }
	public boolean isBoop() { return boop; }
	public void setBoop(boolean value) { boop = value; }
	public boolean isKick() { return kick; }
	public void setKick(boolean value) { kick = value; }
	public boolean isCoinFlip() { return coinFlip; }
	public void setCoinFlip(boolean value) { coinFlip = value; }
	public boolean isEightBall() { return eightBall; }
	public void setEightBall(boolean value) { eightBall = value; }
	public boolean isDice() { return dice; }
	public void setDice(boolean value) { dice = value; }
	public boolean isPartyTransfer() { return partyTransfer; }
	public void setPartyTransfer(boolean value) { partyTransfer = value; }
	public boolean isReinvite() { return reinvite; }
	public void setReinvite(boolean value) { reinvite = value; }
	public boolean isPing() { return ping; }
	public void setPing(boolean value) { ping = value; }
	public boolean isFps() { return fps; }
	public void setFps(boolean value) { fps = value; }
	public boolean isDt() { return dt; }
	public void setDt(boolean value) { dt = value; }
	public boolean isInvite() { return invite; }
	public void setInvite(boolean value) { invite = value; }
	public boolean isAutoConfirm() { return autoConfirm; }
	public void setAutoConfirm(boolean value) { autoConfirm = value; }
	public boolean isRacism() { return racism; }
	public void setRacism(boolean value) { racism = value; }
	public boolean isTime() { return time; }
	public void setTime(boolean value) { time = value; }
	public boolean isPartyDemote() { return partyDemote; }
	public void setPartyDemote(boolean value) { partyDemote = value; }
	public boolean isPartyPromote() { return partyPromote; }
	public void setPartyPromote(boolean value) { partyPromote = value; }
	public boolean isKickOffline() { return kickOffline; }
	public void setKickOffline(boolean value) { kickOffline = value; }
	public boolean isLocation() { return location; }
	public void setLocation(boolean value) { location = value; }
	public boolean isHolding() { return holding; }
	public void setHolding(boolean value) { holding = value; }
	public boolean isQueInstance() { return queInstance; }
	public void setQueInstance(boolean value) { queInstance = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("chatEmotes", chatEmotes);
		obj.addProperty("partyChatCommands", partyChatCommands);
		obj.addProperty("guildChatCommands", guildChatCommands);
		obj.addProperty("privateChatCommands", privateChatCommands);
		obj.addProperty("partyWarp", partyWarp);
		obj.addProperty("coords", coords);
		obj.addProperty("partyAllInvite", partyAllInvite);
		obj.addProperty("boop", boop);
		obj.addProperty("kick", kick);
		obj.addProperty("coinFlip", coinFlip);
		obj.addProperty("eightBall", eightBall);
		obj.addProperty("dice", dice);
		obj.addProperty("partyTransfer", partyTransfer);
		obj.addProperty("reinvite", reinvite);
		obj.addProperty("ping", ping);
		obj.addProperty("fps", fps);
		obj.addProperty("dt", dt);
		obj.addProperty("invite", invite);
		obj.addProperty("autoConfirm", autoConfirm);
		obj.addProperty("racism", racism);
		obj.addProperty("time", time);
		obj.addProperty("partyDemote", partyDemote);
		obj.addProperty("partyPromote", partyPromote);
		obj.addProperty("kickOffline", kickOffline);
		obj.addProperty("location", location);
		obj.addProperty("holding", holding);
		obj.addProperty("queInstance", queInstance);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("chatEmotes")) chatEmotes = obj.get("chatEmotes").getAsBoolean();
		if (obj.has("partyChatCommands")) partyChatCommands = obj.get("partyChatCommands").getAsBoolean();
		if (obj.has("guildChatCommands")) guildChatCommands = obj.get("guildChatCommands").getAsBoolean();
		if (obj.has("privateChatCommands")) privateChatCommands = obj.get("privateChatCommands").getAsBoolean();
		if (obj.has("partyWarp")) partyWarp = obj.get("partyWarp").getAsBoolean();
		if (obj.has("coords")) coords = obj.get("coords").getAsBoolean();
		if (obj.has("partyAllInvite")) partyAllInvite = obj.get("partyAllInvite").getAsBoolean();
		if (obj.has("boop")) boop = obj.get("boop").getAsBoolean();
		if (obj.has("kick")) kick = obj.get("kick").getAsBoolean();
		if (obj.has("coinFlip")) coinFlip = obj.get("coinFlip").getAsBoolean();
		if (obj.has("eightBall")) eightBall = obj.get("eightBall").getAsBoolean();
		if (obj.has("dice")) dice = obj.get("dice").getAsBoolean();
		if (obj.has("partyTransfer")) partyTransfer = obj.get("partyTransfer").getAsBoolean();
		if (obj.has("reinvite")) reinvite = obj.get("reinvite").getAsBoolean();
		if (obj.has("ping")) ping = obj.get("ping").getAsBoolean();
		if (obj.has("fps")) fps = obj.get("fps").getAsBoolean();
		if (obj.has("dt")) dt = obj.get("dt").getAsBoolean();
		if (obj.has("invite")) invite = obj.get("invite").getAsBoolean();
		if (obj.has("autoConfirm")) autoConfirm = obj.get("autoConfirm").getAsBoolean();
		if (obj.has("racism")) racism = obj.get("racism").getAsBoolean();
		if (obj.has("time")) time = obj.get("time").getAsBoolean();
		if (obj.has("partyDemote")) partyDemote = obj.get("partyDemote").getAsBoolean();
		if (obj.has("partyPromote")) partyPromote = obj.get("partyPromote").getAsBoolean();
		if (obj.has("kickOffline")) kickOffline = obj.get("kickOffline").getAsBoolean();
		if (obj.has("location")) location = obj.get("location").getAsBoolean();
		if (obj.has("holding")) holding = obj.get("holding").getAsBoolean();
		if (obj.has("queInstance")) queInstance = obj.get("queInstance").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Adds \"!command\" chat responders (boop, kick, coinflip, 8ball, etc.) usable in party/guild/private chat, plus optional chat emote replacement.";
	}
}
