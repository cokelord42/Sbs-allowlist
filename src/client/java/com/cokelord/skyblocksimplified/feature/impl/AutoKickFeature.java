package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.MojangApi;
import com.cokelord.skyblocksimplified.api.SkyblockStatsApi;
import com.cokelord.skyblocksimplified.dungeon.DungeonQueueDetector;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.party.PartyApi;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request (large batch spec, floor-by-floor "advanced mode" explicitly retracted — "Forget the
 * part i said about changing for each floor since its a bit complicated"): kicks a Party Finder joiner who
 * doesn't meet a configured minimum personal-best time for the currently-queued floor, and/or doesn't own a
 * configured set of items/pets ({@code Hyperion}/{@code Terminator}/Golden Dragon/1B+ bank).
 *
 * <p>"Currently queued floor" detection ({@code Group Builder} GUI slots 11/12, and a Party Finder listing
 * slot with the same info) used to live directly in this class — extracted out to {@link
 * DungeonQueueDetector} per user request, once {@link PartyFinderFeature} also needed the same real signal:
 * that capture used to be gated behind this feature's OWN {@code isEnabled()}, an invisible coupling that
 * would have silently starved any other consumer of this data whenever Auto-Kick itself was turned off. See
 * that class's own doc comment for the real screen/slot/regex details (unchanged, just relocated).
 *
 * <p>Real per-floor personal-best data comes from {@link SkyblockStatsApi}'s {@code FloorTimes} — see that
 * record's own doc comment for the confirmed real API fields and the S+/S/any-rank fallback rule this
 * applies exactly as specified by the user ("Make the personal best always go off S+ ranking, and S ranking
 * on mastermode/regular floor 4 and below... If the user has no S+ or S runs on the floor queued it should
 * detect any personal best timer, no matter the rank").
 *
 * <p>Real join-line detection and kick command mirror {@link PartyFinderFeature}'s own confirmed regex and
 * {@link ChatCommandsFeature}'s own confirmed {@code "p kick " + name} convention — gated on
 * {@link PartyApi#isLeader()} the same way, since {@code /p kick} silently does nothing (or errors) when
 * the local player isn't the real party leader. This feature runs its own independent join-line listener
 * (never cancels the line — that's {@link PartyFinderFeature}'s job alone) so autokick works whether or not
 * Party Finder Stats is separately enabled.
 *
 * <p>Real, honest gap left open per the user's own explicit permission ("Make it also hide the kicked
 * message. I can get it for you later so you can skip this if you cant figure it out yourself"): the real
 * Hypixel system message shown when a party member is kicked is NOT hidden by this feature yet — its exact
 * text was never supplied and guessing it risks either missing it entirely or hiding an unrelated real
 * message.
 */
public class AutoKickFeature extends Feature {
	// Same real join-line regex PartyFinderFeature already confirmed (Devonian's own PartyFinderStats.kt) —
	// duplicated locally rather than shared, matching this codebase's own established small-duplication
	// convention (e.g. MelodyDisplayFeature's own duplicated classColor/classIcon). The class itself is now a
	// real capture group (group 2) instead of a plain non-capturing alternation — see requireNoDupeClass's
	// own doc comment for why this feature needs to know it.
	private static final Pattern JOIN_PATTERN = Pattern.compile(
		"^Party Finder > (\\w{1,16}) joined the dungeon group! \\((Healer|Tank|Mage|Berserk|Archer) Level \\d+\\)$");

	private static final long ENRICHMENT_WAIT_MILLIS = 8_000;
	// Real safety margin before the actual /p kick, mirroring Devonian's own 10-tick gap between its party
	// chat announcement and its kick command — gives the chat message a moment to actually land first.
	private static final long KICK_DELAY_MILLIS = 500;

	private record PendingJoin(String username, com.cokelord.skyblocksimplified.dungeon.DungeonClass clazz, long queuedAtMillis) {}
	private record PendingKick(String username, String reason, long fireAtMillis) {}

	private final Map<String, PendingJoin> pending = new LinkedHashMap<>();
	// Per user request ("kick people based on dupe classes, so if they join on a class that has already
	// joined (and not left, detect that too) it should automatically kick them"): every real join line
	// already states the joiner's own class (see JOIN_PATTERN's own doc comment on why that's now a real
	// capture group), so there's no need for the separate tablist/chat-swap/Catacombs-Gate class-detection
	// infra the user also described elsewhere — that's for knowing the LOCAL player's own class outside a
	// dungeon (Highlight Parties' own use case), a different problem from "what class did THIS joiner
	// announce." Remembers every joiner's announced class for as long as {@link PartyApi#members()} still
	// lists them — a member who leaves (PartyApi already removes them from that set on every real leave/
	// kick/disconnect line) stops counting toward a "duplicate" the moment they're gone, per the user's own
	// explicit "and not left, detect that too."
	private final Map<String, com.cokelord.skyblocksimplified.dungeon.DungeonClass> classByMember = new LinkedHashMap<>();
	private final List<PendingKick> pendingKicks = new ArrayList<>();

	private int minPbMinutes = 4;
	private int minPbSeconds = 30;
	// Per user request ("Add a minimum secrets per run auto-kick requirement (excludes mage because mage
	// doesnt do secrets, instead they blood rush)"): 0 disables the check, same "0 means off" convention
	// minPbMinutes/minPbSeconds already use together. A Mage joiner is never evaluated against this at all
	// (see evaluate()'s own doc comment) since Mage's whole role is Blood Rush utility, not secret-hunting —
	// judging them by SkyblockStatsApi's account-wide secrets/run average would be judging them on a stat
	// their class isn't meant to contribute to.
	private double minSecretsPerRun = 0;
	private boolean requireHyperion = false;
	private boolean requireTerminator = false;
	private boolean requireGoldenDragon = false;
	// Per user request ("Add an ender dragon option to the auto-kick aswell as the golden dragon. When the
	// golden dragon option is enabled, allow users to enable a 'Ender dragon is fine too' option which also
	// whitelists people that have ender dragons instead of a golden dragon"): two separate things — a plain
	// standalone Ender Dragon requirement (requireEnderDragon, same shape as every other require* checkbox),
	// and a sub-toggle of the EXISTING Golden Dragon requirement (enderDragonAlsoAcceptable) that widens it
	// into an OR instead of adding a second, independent AND check — see evaluate()'s own use of both.
	private boolean requireEnderDragon = false;
	private boolean enderDragonAlsoAcceptable = false;
	private boolean requireBillionBank = false;
	// Per user request ("Add a subtoggle for the auto-kick party chat message"): the "pc Kicked ... did not
	// match ..." announcement used to be unconditional — some users kick silently (leaving no public trace
	// of who got removed or why) rather than always broadcasting it to the whole party.
	private boolean announceKickReason = true;
	// Per user request ("kicks people based on dupe classes, so if they join on a class that has already
	// joined (and not left, detect that too) it should automatically kick them"): off by default, matching
	// this feature's own established "every extra check starts opt-in" convention (requireHyperion etc.).
	private boolean requireNoDupeClass = false;

	private static boolean listenersRegistered = false;
	private static AutoKickFeature instance;

	public AutoKickFeature() {
		super("autokick", "Auto-kick", FeatureCategory.COMBAT, false);
		instance = this;
		ensureListenersRegistered();
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	private static void ensureListenersRegistered() {
		if (listenersRegistered) return;
		listenersRegistered = true;

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (instance == null || !instance.isEnabled() || overlay) return true;
			try {
				instance.onChatMessage(message);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Auto-kick chat listener threw, skipping this line", e);
			}
			return true;
		});

	}

	private void onChatMessage(Component message) {
		Matcher match = JOIN_PATTERN.matcher(message.getString());
		if (!match.matches()) return;
		// Real bug found (per user report — "The api data gatherer still triggers when I join a party"):
		// this join-broadcast line is sent to every current member of the party, INCLUDING the joiner
		// themselves — so using Party Finder to join someone ELSE's group fires this exact line about your
		// own join, and nothing below ever checked leadership before kicking off an API fetch for it. Every
		// real kick decision already requires being the party leader (evaluate()'s own guard, and the dupe-
		// class check's own isDupeClass gate), so there is nothing useful to do here at all as a non-leader —
		// gated at the top now instead of only at the point of actually issuing a kick, so joining someone
		// else's dungeon group no longer queues a pointless stats fetch for yourself.
		if (!PartyApi.isLeader()) return;
		String username = match.group(1);
		com.cokelord.skyblocksimplified.dungeon.DungeonClass clazz;
		try {
			clazz = com.cokelord.skyblocksimplified.dungeon.DungeonClass.valueOf(match.group(2).toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			clazz = com.cokelord.skyblocksimplified.dungeon.DungeonClass.EMPTY;
		}
		// Dupe-class check runs immediately, not gated behind the slower stats-enrichment wait below — it
		// needs no API data at all (every real join line already states the class), and per the user's own
		// framing ("if they join on a class that has already joined... it should automatically kick them")
		// this is meant to act fast, before that player starts actually playing the wrong-class role.
		// (isLeader() already checked above, before either of these can run at all.)
		if (requireNoDupeClass && isDupeClass(username, clazz)) {
			String reason = "duplicate class (" + clazz.name() + ")";
			if (announceKickReason) sendCommand("pc Kicked " + username + " for " + reason);
			pendingKicks.add(new PendingKick(username, reason, System.currentTimeMillis() + KICK_DELAY_MILLIS));
		}
		classByMember.put(username, clazz);
		pending.put(username, new PendingJoin(username, clazz, System.currentTimeMillis()));
		MojangApi.resolve(username, SkyblockStatsApi::request);
	}

	/** True if some OTHER current party member already announced the same class this joiner did — see
	 *  classByMember's own doc comment. Self-excluded (a joiner obviously "matches" their own just-recorded
	 *  entry) and gated on PartyApi's live membership so a member who already left never counts. */
	private boolean isDupeClass(String username, com.cokelord.skyblocksimplified.dungeon.DungeonClass clazz) {
		if (clazz == com.cokelord.skyblocksimplified.dungeon.DungeonClass.EMPTY) return false;
		for (Map.Entry<String, com.cokelord.skyblocksimplified.dungeon.DungeonClass> entry : classByMember.entrySet()) {
			if (entry.getKey().equals(username)) continue;
			if (entry.getValue() == clazz && PartyApi.members().contains(entry.getKey())) return true;
		}
		return false;
	}

	@Override
	public void onTick(Minecraft client) {
		if (!pending.isEmpty()) {
			long now = System.currentTimeMillis();
			pending.values().removeIf(join -> {
				if (now - join.queuedAtMillis < ENRICHMENT_WAIT_MILLIS) return false;
				try {
					evaluate(join.username);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Auto-kick evaluation failed for {}, skipping", join.username, e);
				}
				return true;
			});
		}
		if (!pendingKicks.isEmpty()) {
			long now = System.currentTimeMillis();
			pendingKicks.removeIf(kick -> {
				if (now < kick.fireAtMillis) return false;
				sendCommand("p kick " + kick.username);
				return true;
			});
		}
	}

	private void evaluate(String username) {
		if (!PartyApi.isLeader()) return;
		int minSeconds = minPbMinutes * 60 + minPbSeconds;
		boolean pbCheckEnabled = minSeconds > 0;
		com.cokelord.skyblocksimplified.dungeon.DungeonClass clazz = classByMember.get(username);
		// See minSecretsPerRun's own field doc comment — Mage never contributes to secrets/run, so the check
		// simply doesn't apply to one at all rather than judging them against a stat their role doesn't earn.
		boolean secretsCheckEnabled = minSecretsPerRun > 0 && clazz != com.cokelord.skyblocksimplified.dungeon.DungeonClass.MAGE;
		boolean anyExtraCheck = requireHyperion || requireTerminator || requireGoldenDragon || requireEnderDragon || requireBillionBank || secretsCheckEnabled;
		if (!pbCheckEnabled && !anyExtraCheck) return;

		String uuid = MojangApi.get(username);
		SkyblockStatsApi.PlayerStats stats = uuid != null ? SkyblockStatsApi.get(uuid) : null;
		if (stats == null) return; // no real data available — never kick blind

		List<String> failedReasons = new ArrayList<>();

		Integer queuedFloor = DungeonQueueDetector.getQueuedFloor();
		if (pbCheckEnabled && queuedFloor != null) {
			boolean masterMode = DungeonQueueDetector.isQueuedMasterMode();
			SkyblockStatsApi.FloorTimes floorTimes = stats.floorTimes(masterMode, queuedFloor);
			boolean sAcceptable = masterMode || queuedFloor <= 4;
			Integer pbSeconds = floorTimes != null ? floorTimes.bestSecondsPreferringRank(sAcceptable) : null;
			if (pbSeconds == null || pbSeconds > minSeconds) failedReasons.add("personal best");
		}
		if (requireHyperion && !stats.hasHyperion()) failedReasons.add("Hyperion");
		if (requireTerminator && !stats.hasTerminator()) failedReasons.add("Terminator");
		// See enderDragonAlsoAcceptable's own field doc comment — widens this check into an OR rather than
		// stacking a second independent AND requirement.
		if (requireGoldenDragon && !(stats.hasGoldenDragon() || (enderDragonAlsoAcceptable && stats.hasEnderDragon()))) {
			failedReasons.add("Golden Dragon");
		}
		if (requireEnderDragon && !stats.hasEnderDragon()) failedReasons.add("Ender Dragon");
		if (requireBillionBank && !stats.hasBillionInBank()) failedReasons.add("1B+ bank");
		if (secretsCheckEnabled && stats.secretsPerRun() < minSecretsPerRun) failedReasons.add("secrets/run");

		if (failedReasons.isEmpty()) return;

		// Per user request ("Make the mod send a chat message when kicking someone like 'Kicked {username}
		// did not match personal best'"): kept literal for the personal-best case; the same phrasing pattern
		// extended honestly to the extra-requirement checkboxes rather than always blaming personal best
		// when e.g. Hyperion was the actual reason.
		String reason = String.join(", ", failedReasons);
		if (announceKickReason) sendCommand("pc Kicked " + username + " did not match " + reason);
		pendingKicks.add(new PendingKick(username, reason, System.currentTimeMillis() + KICK_DELAY_MILLIS));
	}

	private static void sendCommand(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand(command);
	}

	public int getMinPbMinutes() { return minPbMinutes; }
	public void setMinPbMinutes(int value) { minPbMinutes = Math.max(0, value); }
	public int getMinPbSeconds() { return minPbSeconds; }
	public void setMinPbSeconds(int value) { minPbSeconds = Math.max(0, Math.min(59, value)); }
	public double getMinSecretsPerRun() { return minSecretsPerRun; }
	public void setMinSecretsPerRun(double value) { minSecretsPerRun = Math.max(0, Math.min(20, value)); }
	public boolean isRequireHyperion() { return requireHyperion; }
	public void setRequireHyperion(boolean value) { requireHyperion = value; }
	public boolean isRequireTerminator() { return requireTerminator; }
	public void setRequireTerminator(boolean value) { requireTerminator = value; }
	public boolean isRequireGoldenDragon() { return requireGoldenDragon; }
	public void setRequireGoldenDragon(boolean value) { requireGoldenDragon = value; }
	public boolean isRequireEnderDragon() { return requireEnderDragon; }
	public void setRequireEnderDragon(boolean value) { requireEnderDragon = value; }
	/** Only meaningful while {@link #isRequireGoldenDragon()} is true — see the field's own doc comment. */
	public boolean isEnderDragonAlsoAcceptable() { return enderDragonAlsoAcceptable; }
	public void setEnderDragonAlsoAcceptable(boolean value) { enderDragonAlsoAcceptable = value; }
	public boolean isRequireBillionBank() { return requireBillionBank; }
	public void setRequireBillionBank(boolean value) { requireBillionBank = value; }
	public boolean isAnnounceKickReason() { return announceKickReason; }
	public void setAnnounceKickReason(boolean value) { announceKickReason = value; }
	public boolean isRequireNoDupeClass() { return requireNoDupeClass; }
	public void setRequireNoDupeClass(boolean value) { requireNoDupeClass = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("minPbMinutes", minPbMinutes);
		obj.addProperty("minPbSeconds", minPbSeconds);
		obj.addProperty("minSecretsPerRun", minSecretsPerRun);
		obj.addProperty("requireHyperion", requireHyperion);
		obj.addProperty("requireTerminator", requireTerminator);
		obj.addProperty("requireGoldenDragon", requireGoldenDragon);
		obj.addProperty("requireEnderDragon", requireEnderDragon);
		obj.addProperty("enderDragonAlsoAcceptable", enderDragonAlsoAcceptable);
		obj.addProperty("requireBillionBank", requireBillionBank);
		obj.addProperty("announceKickReason", announceKickReason);
		obj.addProperty("requireNoDupeClass", requireNoDupeClass);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("minPbMinutes")) minPbMinutes = obj.get("minPbMinutes").getAsInt();
		if (obj.has("minPbSeconds")) minPbSeconds = obj.get("minPbSeconds").getAsInt();
		if (obj.has("minSecretsPerRun")) minSecretsPerRun = obj.get("minSecretsPerRun").getAsDouble();
		if (obj.has("requireHyperion")) requireHyperion = obj.get("requireHyperion").getAsBoolean();
		if (obj.has("requireTerminator")) requireTerminator = obj.get("requireTerminator").getAsBoolean();
		if (obj.has("requireGoldenDragon")) requireGoldenDragon = obj.get("requireGoldenDragon").getAsBoolean();
		if (obj.has("requireEnderDragon")) requireEnderDragon = obj.get("requireEnderDragon").getAsBoolean();
		if (obj.has("enderDragonAlsoAcceptable")) enderDragonAlsoAcceptable = obj.get("enderDragonAlsoAcceptable").getAsBoolean();
		if (obj.has("requireBillionBank")) requireBillionBank = obj.get("requireBillionBank").getAsBoolean();
		if (obj.has("announceKickReason")) announceKickReason = obj.get("announceKickReason").getAsBoolean();
		if (obj.has("requireNoDupeClass")) requireNoDupeClass = obj.get("requireNoDupeClass").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Automatically kicks a Party Finder joiner from your party if they don't meet the requirements you set.";
	}
}
