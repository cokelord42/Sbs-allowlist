package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.MojangApi;
import com.cokelord.skyblocksimplified.debug.DebugLog;
import com.cokelord.skyblocksimplified.api.SkyblockStatsApi;
import com.cokelord.skyblocksimplified.dungeon.DungeonQueueDetector;
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
	// Per user request ("The api data gather needs to get catacombs level aswell") — SkyblockStatsApi now
	// computes a real level from catacombs.experience (see that class's own doc comment), surfaced here the
	// same optional-lore-line way every other stat already is.
	private boolean showCatacombsLevel = true;
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
		// Real bug found (per user report — "since the message is so delayed, I cant see when players join
		// until their api data is gathered which takes atleast 5 seconds"): this used to hold the ENTIRE join
		// announcement back for the full ENRICHMENT_WAIT_MILLIS wait, even though the class/level info in the
		// real chat line is already known with zero network calls. Split into two messages per the user's
		// own exact spec — an immediate "joined the party, gathering information" line (with the kick button,
		// so a joiner can be removed right away without waiting on stats at all) fired here, and a second
		// "information for X" hover-only follow-up fired later from announce() once enrichment lands.
		announceJoin(mc, username, roleName, roleLevel, isSelf);
		return false;
	}

	// Real bug found (per user report — "The data collector for the party finder joins still triggers when
	// the user joins a party, or atleast the first message does"): this used to send the exact same "joined
	// the party as {class} (Lvl N), gathering information..." line for the local player's own join too — but
	// no enrichment ever actually runs for self (see onChatMessage's own isSelf guard), so "gathering
	// information..." was always a lie for that specific line. Self now gets a plain, honest
	// "{username} joined the party!" instead, with no class/level detail and no kick line (already skipped).
	private void announceJoin(Minecraft mc, String username, String roleName, int roleLevel, boolean isSelf) {
		if (mc.player == null) return;
		if (isSelf) {
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§b[SBS] §f" + username + " §7joined the party!"));
			return;
		}
		String color = classColor(roleName);
		Component message = Component.literal("§b[SBS] §f" + username + " §7joined the party as " + color + "§l"
			+ classDisplayName(roleName) + " §7(Lvl " + roleLevel + "), gathering information...");
		mc.gui.hud.getChat().addClientSystemMessage(message);
		Component kickLine = Component.literal("§7 └ §c[Kick " + username + "]").withStyle(style -> style
			.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/party kick " + username))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("§cClick to kick " + username + " from the party."))));
		mc.gui.hud.getChat().addClientSystemMessage(kickLine);
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
		// Real bug found — see onChatMessage's own doc comment on the two-part split: the join line (class,
		// level, kick button) already went out immediately from announceJoin(); this second message now only
		// ever carries the enrichment RESULT, per the user's own exact spec ("information for {username}
		// HOVER"). Nothing worth showing without real stats — the immediate join line already said everything
		// that was known at that point, so this silently no-ops rather than repeating it a second time.
		if (stats == null) return;
		Component message = Component.literal("§b[SBS] §7Information for §f" + join.username + " §8| §7Hover for full stats")
			.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(buildStatsLore(join, stats))));
		// addClientSystemMessage (not sendSystemMessage/a real chat send) — appends straight to the local
		// uses; this line is purely informational and must never be sent to the server/party chat.
		client.gui.hud.getChat().addClientSystemMessage(message);
	}

	/** Per user request ("make sure it shows lore when i hover it in chat"): the full real stat breakdown
	 *  pulled by {@link SkyblockStatsApi}, as a hover tooltip on the compact one-line chat message — same
	 *  "keep the visible line short, put the detail in a hover" pattern the rest of this codebase already
	 *  uses for informational chat lines. */
	private Component buildStatsLore(PendingJoin join, SkyblockStatsApi.PlayerStats stats) {
		StringBuilder lore = new StringBuilder();
		lore.append("§f").append(join.username).append("'s Dungeon Stats");
		if (showCatacombsLevel) lore.append("\n§7Catacombs Level: §d").append(stats.catacombsLevel());
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
		// Per user request ("i dont think the party finder system has to give all completions, make it just
		// give currently queued floor and if it cant figure out currently queued floor it should give those
		// completions"): only ONE of these four ever mattered anyway (whichever floor the party is actually
		// queued for) — showing all four every time was noise the user never asked for. DungeonQueueDetector
		// (see its own doc comment) is the same real Group Builder/Party Finder-listing detection AutoKickFeature
		// already relies on for its own personal-best check, now shared. If the queued floor is confidently
		// known but isn't one of the four this codebase tracks completions for at all (e.g. a normal Floor 3),
		// nothing is shown — falling back to the unrelated F7/M5/M6/M7 numbers would misleadingly suggest they
		// were relevant. The "can't figure it out" fallback (showing every configured line, the original
		// behavior) only applies when detection itself has nothing to say at all.
		Integer queuedFloor = DungeonQueueDetector.getQueuedFloor();
		if (queuedFloor == null) {
			if (showF7Completions) lore.append("\n§7F7 Completions: §a").append(stats.f7Completions());
			if (showM5Completions) lore.append("\n§7M5 Completions: §a").append(stats.m5Completions());
			if (showM6Completions) lore.append("\n§7M6 Completions: §a").append(stats.m6Completions());
			if (showM7Completions) lore.append("\n§7M7 Completions: §a").append(stats.m7Completions());
		} else {
			boolean masterMode = DungeonQueueDetector.isQueuedMasterMode();
			if (!masterMode && queuedFloor == 7 && showF7Completions) {
				lore.append("\n§7F7 Completions: §a").append(stats.f7Completions());
			} else if (masterMode && queuedFloor == 5 && showM5Completions) {
				lore.append("\n§7M5 Completions: §a").append(stats.m5Completions());
			} else if (masterMode && queuedFloor == 6 && showM6Completions) {
				lore.append("\n§7M6 Completions: §a").append(stats.m6Completions());
			} else if (masterMode && queuedFloor == 7 && showM7Completions) {
				lore.append("\n§7M7 Completions: §a").append(stats.m7Completions());
			}
		}
		return Component.literal(lore.toString());
	}

	public boolean isShowCatacombsLevel() { return showCatacombsLevel; }
	public void setShowCatacombsLevel(boolean value) { showCatacombsLevel = value; }
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
		obj.addProperty("showCatacombsLevel", showCatacombsLevel);
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
		if (obj.has("showCatacombsLevel")) showCatacombsLevel = obj.get("showCatacombsLevel").getAsBoolean();
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
