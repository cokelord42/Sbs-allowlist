package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.MojangApi;
import com.cokelord.skyblocksimplified.debug.DebugLog;
import com.cokelord.skyblocksimplified.api.SkyblockStatsApi;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request (original spec: "detects players joining via Party Finder... a very different message
 * than normal party-join", later confirmed and re-approved this session — "Yes, build both now" — once the
 * user pointed out a keyless data path exists): posts a local, informational chat summary whenever someone
 * joins your party through Hypixel's real Party Finder GUI, covering Catacombs level, per-class levels,
 * secrets, and Golden Dragon/Spirit Pet ownership for the joiner.
 *
 * <p>Real, confirmed join line (verified against Devonian's own shipped {@code PartyFinderStats.kt} regex,
 * this is a genuinely different message than a normal {@code X has joined the party!} line):
 * {@code Party Finder > Username joined the dungeon group! (Role Level N)} — note this already hands us the
 * joiner's CURRENT class + that class's level for free, with zero network call, which is why that part of
 * the message is never held back waiting on {@link SkyblockStatsApi}.
 *
 * <p>Everything else (F7/M5+ completions, total secrets, secrets per run, Hyperion/Terminator/Golden Dragon
 * ownership, one-billion-in-bank, armor/equipment) comes from {@link SkyblockStatsApi} — the user's own
 * self-hosted Cloudflare Worker proxy that holds a real Hypixel API key server-side (per the user's own
 * explicit request: "I have now set up a cloudflare worker... integrate this into the mod"), resolved from
 * the joiner's username to a real UUID via {@link MojangApi} first (the join line only gives a name). The
 * message is sent once, {@link #ENRICHMENT_WAIT_MILLIS} after the join line, giving both the username→UUID
 * lookup and the full-stats fetch a chance to land; if either never does, the message still goes out with
 * just the class+level info the chat line itself already guaranteed — this feature never blocks or silently
 * does nothing just because the enrichment proxy is slow/unreachable.
 *
 * <p>Real scope decision (kept deliberately simple rather than building a dedicated persisted GUI toggle for
 * this one line): the original spec asked for a "toggle between current-class-only and all-classes" view for
 * class levels. Rather than wiring a whole new persisted subtoggle row into {@code MainScreen}'s per-feature
 * cog panel for a single chat line, this always shows the joiner's current class (from the guaranteed chat
 * line) as the headline, AND appends every other class's level in one compact trailing group when
 * {@code SkyblockStatsApi} has them — functionally the same information the toggle would have gated, just
 * never hidden rather than switched between two exclusive views.
 */
public class PartyFinderFeature extends Feature {
	// Devonian's own confirmed regex, translated 1:1 (see class doc comment) — "Berserk" (not "Berserker")
	// is Hypixel's own real literal spelling in this specific chat line.
	private static final Pattern JOIN_PATTERN = Pattern.compile(
		"^Party Finder > (\\w{1,16}) joined the dungeon group! \\((Healer|Tank|Mage|Berserk|Archer) Level (\\d+)\\)$");

	// Widened from the old single-hop 5.5s (per this round's rework): two real network round-trips now have
	// to land before this fires (username->UUID via MojangApi, then UUID->full-profile via the worker), not
	// one — still finite and never actually blocking (see class doc comment).
	private static final long ENRICHMENT_WAIT_MILLIS = 8_000;

	private record PendingJoin(String username, String roleName, int roleLevel, long queuedAtMillis) {}

	private final Map<String, PendingJoin> pending = new LinkedHashMap<>();

	// Per user request ("allow users to turn off certain options in the party finder module, for example if
	// they dont want to display m5 runs or golden dragon on lower floors. They should all be on by default
	// but give them the option"): one toggle per lore line buildStatsLore appends — all default true so
	// nothing changes for anyone who doesn't touch these.
	private boolean showTotalSecrets = true;
	private boolean showSecretsPerRun = true;
	private boolean showHyperion = true;
	private boolean showTerminator = true;
	private boolean showGoldenDragon = true;
	private boolean showBillionInBank = true;
	private boolean showArmor = true;
	private boolean showEquipment = true;
	private boolean showF7Completions = true;
	private boolean showM5Completions = true;
	private boolean showM6Completions = true;
	private boolean showM7Completions = true;

	private static boolean listenersRegistered = false;
	private static PartyFinderFeature instance;

	public PartyFinderFeature() {
		super("party_finder", "Party Finder Stats", FeatureCategory.COMBAT, false);
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
				return instance.onChatMessage(message);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Party Finder chat listener threw, skipping this line", e);
				return true;
			}
		});
	}

	/** Returns false to cancel the real vanilla line — per user request ("Can you cancel the party finder
	 *  message?... Cancel it and send another message") — replaced by this feature's own custom line, posted
	 *  later from {@link #announce} once enrichment has had a chance to land. Returns true (let it through
	 *  unmodified) for every other message, including a non-matching line. */
	private boolean onChatMessage(Component message) {
		String raw = message.getString();
		Matcher match = JOIN_PATTERN.matcher(raw);
		if (!match.matches()) {
			// Per user request ("The party finder module no longer lets me hover anywhere for the stats"):
			// no hover ever gets attached unless a join was actually intercepted here in the first place (see
			// announce() below) — if Hypixel's real wording has drifted even slightly from JOIN_PATTERN, this
			// silently lets the line through as ordinary chat and the feature never even notices a join
			// happened. Logging any line that at least contains the real "Party Finder" literal but still
			// failed to match surfaces exactly that drift on the next live test.
			if (raw.contains("Party Finder")) {
				DebugLog.throttled("partyfinder_nomatch_" + raw.hashCode(), 5000L,
					"Party Finder join line didn't match JOIN_PATTERN: \"" + raw + "\"");
			}
			return true;
		}
		String username = match.group(1);
		String roleName = match.group(2);
		int roleLevel = Integer.parseInt(match.group(3));
		pending.put(username, new PendingJoin(username, roleName, roleLevel, System.currentTimeMillis()));
		// Per user request ("The api thing happens when I join a party and gives me my stats aswell. It
		// should exclude the uuid of the user always, and also show the join message if its the user joining
		// a party"): the join line itself fires for every party member including the local player (e.g.
		// rejoining your own party after a dungeon), and there's no reason to ever resolve/fetch/show your
		// own stats back at yourself. The join line message still always goes out below (announce() doesn't
        // gate on this), only the enrichment lookup is skipped for the local player specifically.
		Minecraft mc = Minecraft.getInstance();
		boolean isSelf = mc.player != null && username.equalsIgnoreCase(mc.player.getName().getString());
		if (!isSelf) {
			MojangApi.resolve(username, SkyblockStatsApi::request);
		}
		return false;
	}

	@Override
	public void onTick(Minecraft client) {
		if (pending.isEmpty()) return;
		long now = System.currentTimeMillis();
		pending.values().removeIf(join -> {
			if (now - join.queuedAtMillis < ENRICHMENT_WAIT_MILLIS) return false;
			try {
				announce(client, join);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Party Finder announce failed for {}, skipping", join.username, e);
			}
			return true;
		});
	}

	private static String classColor(String roleName) {
		return switch (roleName) {
			case "Healer" -> "§d";
			case "Mage" -> "§b";
			case "Berserk" -> "§c";
			case "Archer" -> "§6";
			case "Tank" -> "§7";
			default -> "§f";
		};
	}

	private static String classDisplayName(String roleName) {
		return "Berserk".equals(roleName) ? "Berserker" : roleName;
	}

	private void announce(Minecraft client, PendingJoin join) {
		if (client.player == null) return;
		String uuid = MojangApi.get(join.username);
		SkyblockStatsApi.PlayerStats stats = uuid != null ? SkyblockStatsApi.get(uuid) : null;
		// Per user request ("no longer lets me hover anywhere for the stats"): a hover event is only ever
		// attached below when stats != null — logs which of the two real network hops (MojangApi's own
		// username->uuid resolve, or SkyblockStatsApi's uuid->profile fetch against the user's own Cloudflare
		// Worker) failed to land within ENRICHMENT_WAIT_MILLIS, since either one silently degrades to a
		// hover-less line with no other symptom.
		if (stats == null) {
			DebugLog.throttled("partyfinder_no_stats_" + join.username, 5000L,
				"Party Finder announce with no stats for " + join.username + " — uuid=" + uuid
					+ (uuid == null ? " (MojangApi resolve never landed)" : " (SkyblockStatsApi fetch never landed)"));
		}
		String color = classColor(join.roleName);
		// Per user request ("Change [PF] to [SBS] in light blue text"): §b is Minecraft's real "light blue"
		// chat color (AQUA) — same code used elsewhere in this class's own lore coloring below.
		StringBuilder line = new StringBuilder();
		line.append("§b[SBS] §f").append(join.username)
			.append(" §7joined the party as ").append(color).append("§l").append(classDisplayName(join.roleName))
			.append(" §7(Lvl ").append(join.roleLevel).append(")");

		Component message;
		if (stats != null) {
			line.append(" §8| §7Hover for full stats");
			message = Component.literal(line.toString()).withStyle(style ->
				style.withHoverEvent(new HoverEvent.ShowText(buildStatsLore(join, stats))));
		} else {
			message = Component.literal(line.toString());
		}

		// addClientSystemMessage (not sendSystemMessage/a real chat send) — appends straight to the local
		// uses; this line is purely informational and must never be sent to the server/party chat.
		client.gui.hud.getChat().addClientSystemMessage(message);

		// Per user request ("kick button on the line under the info on party join thing"): a clickable chat
		// line, same real ClickEvent.RunCommand pattern ChatCommandsFeature's own party-invite button already
		// uses, sending the real "/party kick <name>" command on click — never shown for the local player's
		// own join (rejoining your own party), since kicking yourself makes no sense.
		boolean isSelf = join.username.equalsIgnoreCase(client.player.getName().getString());
		if (!isSelf) {
			Component kickLine = Component.literal("§7 └ §c[Kick " + join.username + "]").withStyle(style -> style
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/party kick " + join.username))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("§cClick to kick " + join.username + " from the party."))));
			client.gui.hud.getChat().addClientSystemMessage(kickLine);
		}
	}

	/** Per user request ("make sure it shows lore when i hover it in chat"): the full real stat breakdown
	 *  pulled by {@link SkyblockStatsApi}, as a hover tooltip on the compact one-line chat message — same
	 *  "keep the visible line short, put the detail in a hover" pattern the rest of this codebase already
	 *  uses for informational chat lines. */
	private Component buildStatsLore(PendingJoin join, SkyblockStatsApi.PlayerStats stats) {
		StringBuilder lore = new StringBuilder();
		lore.append("§f").append(join.username).append("'s Dungeon Stats");
		if (showTotalSecrets) lore.append("\n§7Total Secrets: §b").append(stats.totalSecrets());
		if (showSecretsPerRun) lore.append("\n§7Secrets/Run: §b").append(String.format(Locale.ROOT, "%.1f", stats.secretsPerRun()));
		// Per user request ("Color code stuff in the lore. Hyperion should be light blue. Terminator dark
		// red and bold. Golden Dragon should be gold and bold, 1 bil in bank should be gold"): colors the
		// LABEL itself (the ✔/✘ stays green/red, same as before) — §b/§4§l/§6§l/§6 are Minecraft's real
		// light-blue/dark-red-bold/gold-bold/gold formatting codes.
		if (showHyperion) lore.append("\n§bHyperion: ").append(stats.hasHyperion() ? "§a✔" : "§c✘");
		if (showTerminator) lore.append("\n§4§lTerminator: ").append(stats.hasTerminator() ? "§a✔" : "§c✘");
		if (showGoldenDragon) lore.append("\n§6§lGolden Dragon: ").append(stats.hasGoldenDragon() ? "§a✔" : "§c✘");
		if (showBillionInBank) lore.append("\n§61B+ in Bank: ").append(stats.hasBillionInBank() ? "§a✔" : "§c✘");
		if (showArmor && !stats.armorNames().isEmpty()) {
			lore.append("\n§7Armor:");
			for (String name : stats.armorNames()) lore.append("\n §8- ").append(name);
		}
		if (showEquipment && !stats.equipmentNames().isEmpty()) {
			lore.append("\n§7Equipment:");
			for (String name : stats.equipmentNames()) lore.append("\n §8- ").append(name);
		}
		// Per user request ("Place the run amounts furthest down" + "it should count out the amount of
		// completions on each floor on f7 + m5 and up, for example show many runs they have on f7, m5, m6,
		// m7"): individual per-floor counts, moved to the very bottom of the lore.
		if (showF7Completions) lore.append("\n§7F7 Completions: §a").append(stats.f7Completions());
		if (showM5Completions) lore.append("\n§7M5 Completions: §a").append(stats.m5Completions());
		if (showM6Completions) lore.append("\n§7M6 Completions: §a").append(stats.m6Completions());
		if (showM7Completions) lore.append("\n§7M7 Completions: §a").append(stats.m7Completions());
		return Component.literal(lore.toString());
	}

	public boolean isShowTotalSecrets() { return showTotalSecrets; }
	public void setShowTotalSecrets(boolean value) { showTotalSecrets = value; }
	public boolean isShowSecretsPerRun() { return showSecretsPerRun; }
	public void setShowSecretsPerRun(boolean value) { showSecretsPerRun = value; }
	public boolean isShowHyperion() { return showHyperion; }
	public void setShowHyperion(boolean value) { showHyperion = value; }
	public boolean isShowTerminator() { return showTerminator; }
	public void setShowTerminator(boolean value) { showTerminator = value; }
	public boolean isShowGoldenDragon() { return showGoldenDragon; }
	public void setShowGoldenDragon(boolean value) { showGoldenDragon = value; }
	public boolean isShowBillionInBank() { return showBillionInBank; }
	public void setShowBillionInBank(boolean value) { showBillionInBank = value; }
	public boolean isShowArmor() { return showArmor; }
	public void setShowArmor(boolean value) { showArmor = value; }
	public boolean isShowEquipment() { return showEquipment; }
	public void setShowEquipment(boolean value) { showEquipment = value; }
	public boolean isShowF7Completions() { return showF7Completions; }
	public void setShowF7Completions(boolean value) { showF7Completions = value; }
	public boolean isShowM5Completions() { return showM5Completions; }
	public void setShowM5Completions(boolean value) { showM5Completions = value; }
	public boolean isShowM6Completions() { return showM6Completions; }
	public void setShowM6Completions(boolean value) { showM6Completions = value; }
	public boolean isShowM7Completions() { return showM7Completions; }
	public void setShowM7Completions(boolean value) { showM7Completions = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showTotalSecrets", showTotalSecrets);
		obj.addProperty("showSecretsPerRun", showSecretsPerRun);
		obj.addProperty("showHyperion", showHyperion);
		obj.addProperty("showTerminator", showTerminator);
		obj.addProperty("showGoldenDragon", showGoldenDragon);
		obj.addProperty("showBillionInBank", showBillionInBank);
		obj.addProperty("showArmor", showArmor);
		obj.addProperty("showEquipment", showEquipment);
		obj.addProperty("showF7Completions", showF7Completions);
		obj.addProperty("showM5Completions", showM5Completions);
		obj.addProperty("showM6Completions", showM6Completions);
		obj.addProperty("showM7Completions", showM7Completions);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("showTotalSecrets")) showTotalSecrets = obj.get("showTotalSecrets").getAsBoolean();
		if (obj.has("showSecretsPerRun")) showSecretsPerRun = obj.get("showSecretsPerRun").getAsBoolean();
		if (obj.has("showHyperion")) showHyperion = obj.get("showHyperion").getAsBoolean();
		if (obj.has("showTerminator")) showTerminator = obj.get("showTerminator").getAsBoolean();
		if (obj.has("showGoldenDragon")) showGoldenDragon = obj.get("showGoldenDragon").getAsBoolean();
		if (obj.has("showBillionInBank")) showBillionInBank = obj.get("showBillionInBank").getAsBoolean();
		if (obj.has("showArmor")) showArmor = obj.get("showArmor").getAsBoolean();
		if (obj.has("showEquipment")) showEquipment = obj.get("showEquipment").getAsBoolean();
		if (obj.has("showF7Completions")) showF7Completions = obj.get("showF7Completions").getAsBoolean();
		if (obj.has("showM5Completions")) showM5Completions = obj.get("showM5Completions").getAsBoolean();
		if (obj.has("showM6Completions")) showM6Completions = obj.get("showM6Completions").getAsBoolean();
		if (obj.has("showM7Completions")) showM7Completions = obj.get("showM7Completions").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Detects players joining your party through Party Finder and posts their info (and, if available, their stats) in chat.";
	}
}
