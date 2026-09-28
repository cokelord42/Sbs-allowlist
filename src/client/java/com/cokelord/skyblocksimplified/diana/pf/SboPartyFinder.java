package com.cokelord.skyblocksimplified.diana.pf;

import com.cokelord.skyblocksimplified.feature.impl.diana.SboPartyFinderFeature;
import com.cokelord.skyblocksimplified.party.HypixelPartyApi;
import com.cokelord.skyblocksimplified.party.PartyApi;
import com.cokelord.skyblocksimplified.util.ChatText;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SBO party finder logic (PartyFinderManager.kt + PartyPlayer.kt + PartyCheck.kt): queueing a party with
 * requirements, keeping it refreshed/updated while queued, join requests over /msg (same
 * "[SBO] join party request - id:" text SBO uses, so both mods interoperate), auto-accepting the invite back,
 * and checking players' Diana stats.
 *
 * <p>Rate limits mirror SBO's own client-side limits: party list refresh at most every 5s, queue keep-alive
 * every 4 minutes, one join request per leader per minute, party check every 30s, own stats cached 10 minutes;
 * requests of the same kind never overlap.
 */
public final class SboPartyFinder {
	public static final int MAX_PARTY_SIZE = 6;
	private static final Pattern JOIN_REQUEST = Pattern.compile("§d(.*?) (.*?)§7: (.*?) join party request - id:(.*)", Pattern.DOTALL);
	private static final Pattern INVITED = Pattern.compile("(\\w{1,16}) has invited you to join their party!");
	private static final String[] DISBAND = {"has disbanded the party!", "The party was disbanded because", "You left the party.",
		"You are not currently in a party.", "You have been kicked from the party by"};
	private static final Pattern[] LEADER_CHANGE = {Pattern.compile("^You have joined .+'s? party!$"),
		Pattern.compile("^The party was transferred to .+ by .+$"), Pattern.compile("^.+ has promoted .+ to Party Leader$")};
	private static final Pattern[] JOINED = {Pattern.compile("^.+ joined the party\\.$")};
	private static final Pattern[] LEFT = {Pattern.compile("^.+ has been removed from the party\\.$"), Pattern.compile("^.+ has left the party\\.$"),
		Pattern.compile("^.+ was removed from your party because they disconnected\\.$"), Pattern.compile("^Kicked .+ because they were offline\\.$")};

	public static boolean inQueue = false;
	public static boolean creating = false;
	private static boolean usedPf = false;
	private static boolean requeuePending = false;
	private static boolean ghostParty = false;

	private static PartyModels.Reqs partyReqs = PartyModels.Reqs.NONE;
	private static String partyNote = "";
	private static String partyType = "Diana";
	private static int partySize = MAX_PARTY_SIZE;

	private static boolean isInParty = false;
	private static boolean isLeader = false;
	private static List<String> members = List.of();
	private static int memberCount = 1;
	private static long updateScheduledAt = -1;

	private static final Map<String, Long> sentRequests = new HashMap<>();
	private static long lastPartyCheckAt = 0;
	private static long lastRefreshAt = 0;
	private static long lastKeepAliveAt = 0;

	private static PartyModels.PlayerStats ownStats = PartyModels.PlayerStats.EMPTY;
	private static long ownStatsAt = 0;
	private static boolean ownStatsLoading = false;
	private static final List<Consumer<PartyModels.PlayerStats>> ownStatsWaiters = new ArrayList<>();

	private static final Map<String, List<PartyModels.Party>> partyCache = new HashMap<>();
	private static final Map<String, Boolean> refreshing = new HashMap<>();
	private static final Map<String, Long> lastListAt = new HashMap<>();
	public static int activeUsers = -1;

	private static Consumer<PartyInfo> pendingPartyInfo;
	private static long pendingPartyInfoAt;
	private static boolean registered = false;

	public record PartyInfo(boolean inParty, boolean leader, List<String> uuids) {}

	private SboPartyFinder() {}

	public static String key() {
		SboPartyFinderFeature f = SboPartyFinderFeature.get();
		return f == null ? "" : f.string("key").trim();
	}

	static void msg(String text) {
		ChatText.clientMessage("§6[SBO] " + text);
	}

	public static boolean hasKey() {
		String key = key();
		if (key.isBlank() || !key.startsWith("sbo")) {
			msg("§cSet your SBO key in Events > Diana > SBO Party Finder (get one in the SBO Discord).");
			return false;
		}
		return true;
	}

	// ---- registration -------------------------------------------------------------------------------------

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		try {
			HypixelPartyApi.addListener(packet -> Minecraft.getInstance().execute(() -> onModApiPartyInfo(packet)));
		} catch (Throwable ignored) {
			// Mod API library unavailable: chat-based party tracking only.
		}
		ClientTickEvents.END_CLIENT_TICK.register(SboPartyFinder::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			for (String name : new String[]{"sbopf", "sbopartyfinder"}) {
				dispatcher.register(ClientCommands.literal(name).executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					mc.execute(() -> { if (mc.level != null) mc.gui.setScreen(new PartyFinderScreen()); });
					return 1;
				}));
			}
			dispatcher.register(ClientCommands.literal("sbsrequeue").executes(ctx -> {
				if (!inQueue) {
					msg("§eRequeuing party with last used requirements...");
					createParty(partyReqs, partyNote, partyType, partySize);
				}
				return 1;
			}));
			dispatcher.register(ClientCommands.literal("sbsdequeue").executes(ctx -> {
				if (inQueue) {
					usedPf = false;
					unqueue(null);
				} else {
					msg("§4You are not in a party queue.");
				}
				return 1;
			}));
			for (String name : new String[]{"sbscheck", "sbsc"}) {
				dispatcher.register(ClientCommands.literal(name)
					.executes(ctx -> { checkPlayer(ctx.getSource().getClient().getUser().getName(), false, null); return 1; })
					.then(ClientCommands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
						.executes(ctx -> {
							checkPlayer(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"), false, null);
							return 1;
						})));
			}
			for (String name : new String[]{"sbscheckparty", "sbscp"}) {
				dispatcher.register(ClientCommands.literal(name).executes(ctx -> { checkParty(); return 1; }));
			}
		});
	}

	public static void onDisconnect() {
		if (inQueue) unqueue(null);
		isInParty = false;
		isLeader = false;
	}

	private static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (inQueue && now - lastKeepAliveAt > 4 * 60_000) {
			lastKeepAliveAt = now;
			if (!hasKey()) return;
			SboApi.refreshParty(r -> {
				if (!SboApi.bool(r, "Success")) {
					inQueue = false;
					String error = SboApi.str(r, "Error");
					msg("§4" + (error != null ? error : "Unknown error"));
				}
			}, e -> {
				inQueue = false;
				msg("§4Unexpected error while updating party: " + e);
			});
		}
		if (updateScheduledAt > 0 && now >= updateScheduledAt) {
			updateScheduledAt = -1;
			requestPartyInfo(SboPartyFinder::updateParty);
		}
		if (pendingPartyInfo != null && now - pendingPartyInfoAt > 2500) {
			Consumer<PartyInfo> cb = pendingPartyInfo;
			pendingPartyInfo = null;
			cb.accept(chatPartyInfo());
		}
	}

	// ---- party info (Mod API when installed, chat-tracked members otherwise) ------------------------------

	private static void requestPartyInfo(Consumer<PartyInfo> callback) {
		if (PartyApi.requestModApiPartyInfo()) {
			pendingPartyInfo = callback;
			pendingPartyInfoAt = System.currentTimeMillis();
		} else {
			callback.accept(chatPartyInfo());
		}
	}

	private static void onModApiPartyInfo(ClientboundPartyInfoPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		List<String> uuids = new ArrayList<>();
		boolean leader;
		if (packet.isInParty()) {
			UUID leaderUuid = packet.getLeader().orElse(null);
			if (leaderUuid != null) uuids.add(leaderUuid.toString());
			for (UUID member : packet.getMembers()) if (!member.equals(leaderUuid)) uuids.add(member.toString());
			leader = mc.player.getUUID().equals(leaderUuid);
		} else {
			leader = true;
			uuids.add(mc.player.getUUID().toString());
		}
		PartyInfo info = new PartyInfo(packet.isInParty(), leader, uuids);
		applyPartyInfo(info);
		Consumer<PartyInfo> cb = pendingPartyInfo;
		pendingPartyInfo = null;
		if (cb != null) cb.accept(info);
	}

	private static PartyInfo chatPartyInfo() {
		Minecraft mc = Minecraft.getInstance();
		List<String> uuids = new ArrayList<>();
		if (mc.player == null) return new PartyInfo(false, true, uuids);
		boolean inParty = PartyApi.isInParty();
		if (!inParty) {
			uuids.add(mc.player.getUUID().toString());
			PartyInfo info = new PartyInfo(false, true, uuids);
			applyPartyInfo(info);
			return info;
		}
		if (mc.getConnection() != null) {
			for (String name : PartyApi.members()) {
				PlayerInfo pi = mc.getConnection().getPlayerInfo(name);
				if (pi != null) uuids.add(pi.getProfile().id().toString());
			}
		}
		String self = mc.player.getUUID().toString();
		if (!uuids.contains(self)) uuids.add(0, self);
		PartyInfo info = new PartyInfo(true, PartyApi.isLeader(), uuids);
		applyPartyInfo(info);
		return info;
	}

	private static void applyPartyInfo(PartyInfo info) {
		isInParty = info.inParty();
		isLeader = info.leader();
		members = info.uuids();
		memberCount = Math.max(1, info.uuids().size());
	}

	// ---- queue --------------------------------------------------------------------------------------------

	public static void createParty(PartyModels.Reqs reqs, String note, String type, int size) {
		if (creating) return;
		if (!hasKey()) return;
		partyReqs = reqs;
		partyNote = note;
		partyType = type;
		partySize = size <= 0 ? MAX_PARTY_SIZE : Math.min(size, MAX_PARTY_SIZE);
		usedPf = true;
		creating = true;
		requestPartyInfo(SboPartyFinder::queueParty);
	}

	private static JsonObject request() {
		List<String> uuids = new ArrayList<>();
		for (String u : members) uuids.add(u.replace("-", ""));
		return SboApi.partyRequest(uuids, partyReqs, partyType, cleanNote(partyNote), partySize);
	}

	/** SBO's checkPartyNote. */
	private static String cleanNote(String note) {
		String cleaned = note.replaceAll("[^\\p{L}\\p{N} ,.!?\\-_]", "");
		return (cleaned.length() > 30 ? cleaned.substring(0, 30) : cleaned).trim();
	}

	private static void queueParty(PartyInfo info) {
		creating = false;
		if (info.uuids().size() > partySize) {
			msg("§4Party is over the limit. " + info.uuids().size() + "/" + partySize);
			return;
		}
		if (inQueue) {
			msg("§4Party is already in the queue.");
			return;
		}
		if (!info.leader()) {
			msg("§4You must be the party leader to queue the party.");
			return;
		}
		long started = System.currentTimeMillis();
		SboApi.createParty(request(), r -> {
			if (SboApi.bool(r, "Success")) {
				inQueue = true;
				lastKeepAliveAt = System.currentTimeMillis();
				if (r.has("PartyReqs") && r.get("PartyReqs").isJsonObject()) partyReqs = PartyModels.Reqs.from(r.getAsJsonObject("PartyReqs"));
				partySize = SboApi.integer(r, "PartySize", partySize);
				lastListAt.remove(partyType);
				if (ghostParty) {
					ghostParty = false;
					unqueue(null);
				}
				if (requeuePending) {
					requeuePending = false;
					ChatText.clientMessage(clickable("§6[SBO] §eClick to dequeue party", "/sbsdequeue", "Dequeue Party"));
				}
				msg("§eParty created successfully! Time taken: " + (System.currentTimeMillis() - started) + "ms");
				if (isInParty) ChatText.partyChat("[SBO] Party now in queue.");
			} else {
				String error = SboApi.str(r, "Error");
				msg("§4Failed to create party: " + (error != null ? error.replace("&", "§") : "Unknown error"));
			}
		}, e -> msg("§4Unexpected error while creating party: " + e));
	}

	private static void updateParty(PartyInfo info) {
		if (!inQueue || !info.inParty() || !info.leader()) return;
		if (info.uuids().size() < 2 || info.uuids().size() >= partySize) return;
		long started = System.currentTimeMillis();
		SboApi.updateQueuedParty(request(), r -> {
			if (SboApi.bool(r, "Success")) {
				if (r.has("PartyReqs") && r.get("PartyReqs").isJsonObject()) partyReqs = PartyModels.Reqs.from(r.getAsJsonObject("PartyReqs"));
				partySize = SboApi.integer(r, "PartySize", partySize);
				msg("§eParty updated successfully! Time taken: " + (System.currentTimeMillis() - started) + "ms");
			} else {
				inQueue = false;
				String error = SboApi.str(r, "Error");
				msg("§4Failed to update party: " + (error != null ? error.replace("&", "§") : "Unknown error"));
			}
		}, e -> {
			inQueue = false;
			msg("§4Unexpected error while updating party: " + e);
		});
	}

	/** Delete button / /sbsdequeue: stop auto-requeueing and leave the queue. */
	public static void dequeue(Runnable done) {
		usedPf = false;
		unqueue(done);
	}

	public static void unqueue(Runnable done) {
		if (inQueue) {
			inQueue = false;
			if (!hasKey()) {
				if (done != null) done.run();
				return;
			}
			SboApi.unqueueParty(r -> {
				if (SboApi.bool(r, "Success")) msg("§eParty removed from queue.");
				else {
					String error = SboApi.str(r, "Error");
					msg("§4" + (error != null ? error : "Failed to remove party from queue."));
				}
				lastListAt.remove(partyType);
				if (done != null) done.run();
			}, e -> {
				msg("§4Unexpected error while removing party from queue: " + e);
				if (done != null) done.run();
			});
		} else if (creating) {
			ghostParty = true;
		}
	}

	// ---- chat ---------------------------------------------------------------------------------------------

	public static void onChat(String legacy) {
		String plain = ChatText.strip(legacy).trim();
		Matcher m = JOIN_REQUEST.matcher(legacy);
		if (m.find() && m.group(1).contains("From")) {
			if (!inQueue || memberCount >= partySize) return;
			String name = playerName(m.group(2));
			SboPartyFinderFeature f = SboPartyFinderFeature.get();
			if (f != null && f.bool("autoInvite")) {
				checkPlayer(name, true, stats -> {
					if (meetsReqs(stats, partyReqs) && memberCount < partySize) {
						ChatText.command("p invite " + name);
						msg("§eInvited " + name + " to the party.");
					}
				});
			} else {
				MutableComponent line = Component.literal("§6[SBO] §b" + name + " §ewants to join your party. ")
					.append(clickable("§7[§aInvite§7]", "/p invite " + name, "/p invite " + name))
					.append(Component.literal(" "))
					.append(clickable("§7[§eCheck Stats§7]", "/sbscheck " + name, "/sbscheck " + name));
				ChatText.clientMessage(line);
			}
			return;
		}
		Matcher inv = INVITED.matcher(plain);
		if (inv.find()) {
			String name = inv.group(1);
			if (sentRequests.remove(name) != null) {
				msg("§eJoining party of §b" + name + "§e...");
				ChatText.command("p accept " + name);
			}
			return;
		}
		trackMembers(plain);
	}

	private static String playerName(String raw) {
		String name = raw;
		int bracket = name.indexOf(']');
		if (bracket != -1 && bracket + 2 <= name.length()) name = name.substring(Math.min(name.length(), bracket + 2));
		return ChatText.strip(name).replaceAll("[^a-zA-Z0-9_]", "").trim();
	}

	private static void trackMembers(String plain) {
		boolean match = false;
		for (Pattern p : LEADER_CHANGE) {
			if (p.matcher(plain).matches()) {
				match = true;
				isInParty = true;
				isLeader = false;
				unqueue(null);
			}
		}
		for (String d : DISBAND) {
			if (plain.contains(d)) {
				creating = false;
				memberCount = 1;
				match = true;
				isInParty = false;
				unqueue(null);
			}
		}
		for (Pattern p : JOINED) {
			if (p.matcher(plain).matches()) {
				memberCount++;
				match = true;
				isInParty = true;
			}
		}
		for (Pattern p : LEFT) {
			if (p.matcher(plain).matches()) {
				memberCount = Math.max(1, memberCount - 1);
				match = true;
				isInParty = memberCount > 1;
			}
		}
		if (match) onMemberCountChanged();
	}

	private static void onMemberCountChanged() {
		if (inQueue) {
			if (memberCount >= partySize) {
				msg("§4Party is full, removing from queue.");
				unqueue(null);
			} else {
				updateScheduledAt = System.currentTimeMillis() + 200;
			}
		} else if (isInParty && isLeader && memberCount < partySize && !creating && !requeuePending && usedPf) {
			requeuePending = true;
			SboPartyFinderFeature f = SboPartyFinderFeature.get();
			if (f != null && f.bool("autoRequeue")) {
				msg("§eRequeuing party with last used requirements...");
				createParty(partyReqs, partyNote, partyType, partySize);
			} else {
				ChatText.clientMessage(clickable("§6[SBO] §eClick to requeue party with last used requirements.", "/sbsrequeue", "/sbsrequeue"));
			}
		}
	}

	static MutableComponent clickable(String text, String command, String hover) {
		return Component.literal(text).withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	// ---- joining ------------------------------------------------------------------------------------------

	public static boolean meetsReqs(PartyModels.PlayerStats s, PartyModels.Reqs r) {
		if (s.sbLvl() < r.lvl()) return false;
		if (s.mythosKills() < r.kills()) return false;
		if (r.eman9() && !s.eman9()) return false;
		if (r.looting5() && !s.looting5daxe()) return false;
		return s.magicalPower() >= r.mp();
	}

	public static void joinParty(PartyModels.Party party) {
		Minecraft mc = Minecraft.getInstance();
		boolean own = mc.player != null && party.leaderName().equals(mc.player.getGameProfile().name());
		if (own) { msg("§eYou can't join your own party."); return; }
		if (inQueue) { msg("§eYou are already in queue."); return; }
		if (isInParty) { msg("§eYou are already in a party."); return; }
		ownStats(stats -> {
			if (!meetsReqs(stats, party.reqs())) {
				msg("§cYou don't meet the requirements to join this party.");
				return;
			}
			Long sent = sentRequests.get(party.leaderName());
			if (sent != null && System.currentTimeMillis() - sent < 60_000) {
				msg("§cYou have already sent a request to this player recently.");
				return;
			}
			msg("§eSending join request to " + party.leaderName() + "...");
			ChatText.command("msg " + party.leaderName() + " [SBO] join party request - id:" + UUID.randomUUID());
			sentRequests.put(party.leaderName(), System.currentTimeMillis());
		});
	}

	// ---- stats --------------------------------------------------------------------------------------------

	/** Own Diana stats, cached for 10 minutes (SBO PartyPlayer). */
	public static void ownStats(Consumer<PartyModels.PlayerStats> callback) {
		if (System.currentTimeMillis() - ownStatsAt <= 10 * 60_000 && ownStats.sbLvl() != -1) {
			callback.accept(ownStats);
			return;
		}
		ownStatsWaiters.add(callback);
		if (ownStatsLoading) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			flushOwnStats();
			return;
		}
		ownStatsLoading = true;
		SboApi.playerInfo(mc.player.getGameProfile().name(), true, r -> {
			ownStatsLoading = false;
			if (SboApi.bool(r, "Success") && r.has("PlayerInfo") && r.get("PlayerInfo").isJsonObject()) {
				ownStats = PartyModels.PlayerStats.from(r.getAsJsonObject("PlayerInfo"));
				ownStatsAt = System.currentTimeMillis();
				if (ownStats.sbLvl() == -1) msg("§cYour stats are not available, please try again later.");
			}
			flushOwnStats();
		}, e -> {
			ownStatsLoading = false;
			flushOwnStats();
		});
	}

	public static PartyModels.PlayerStats cachedOwnStats() {
		return ownStats;
	}

	private static void flushOwnStats() {
		List<Consumer<PartyModels.PlayerStats>> waiters = new ArrayList<>(ownStatsWaiters);
		ownStatsWaiters.clear();
		for (Consumer<PartyModels.PlayerStats> w : waiters) w.accept(ownStats);
	}

	public static void checkPlayer(String name, boolean quiet, Consumer<PartyModels.PlayerStats> onComplete) {
		if (!quiet) msg("§eChecking player: §b" + name);
		SboApi.playerInfo(name, true, r -> {
			if (!SboApi.bool(r, "Success") || !r.has("PlayerInfo") || !r.get("PlayerInfo").isJsonObject()) {
				if (!quiet) msg("§eError checking player: " + (SboApi.str(r, "Error") != null ? SboApi.str(r, "Error") : "not found"));
				return;
			}
			PartyModels.PlayerStats stats = PartyModels.PlayerStats.from(r.getAsJsonObject("PlayerInfo"));
			if (!quiet) printStats(stats, !name.equalsIgnoreCase(Minecraft.getInstance().getUser().getName()));
			if (onComplete != null) onComplete.accept(stats);
		}, e -> msg("§eError checking player: " + e));
	}

	private static void checkParty() {
		long now = System.currentTimeMillis();
		if (now - lastPartyCheckAt < 30_000) {
			msg("§ePlease wait 30 seconds before checking party members again.");
			return;
		}
		if (!hasKey()) return;
		lastPartyCheckAt = now;
		msg("§eChecking party members...");
		requestPartyInfo(info -> {
			Minecraft mc = Minecraft.getInstance();
			List<String> others = new ArrayList<>();
			for (String u : info.uuids()) {
				if (mc.player != null && u.equals(mc.player.getUUID().toString())) continue;
				others.add(u.replace("-", ""));
			}
			if (others.isEmpty()) {
				msg("§eNo party members found.");
				return;
			}
			if (others.size() > 6) others = others.subList(0, 6);
			SboApi.partyInfoByUuids(others, r -> {
				if (!SboApi.bool(r, "Success") || !r.has("PartyInfo") || !r.get("PartyInfo").isJsonArray()) return;
				for (var e : r.getAsJsonArray("PartyInfo")) if (e.isJsonObject()) printStats(PartyModels.PlayerStats.from(e.getAsJsonObject()), false);
			}, e -> msg("§eError checking party members: " + e));
		});
	}

	private static void printStats(PartyModels.PlayerStats p, boolean inviteButton) {
		ChatText.clientMessage("§6[SBO] §eName: §b" + p.name() + " §9│ §eLvL: §6" + p.sbLvl()
			+ " §9│ §eEman 9: §f" + (p.eman9() ? "§a✓" : "§4✗") + " §9│ §eL5 Daxe: " + (p.looting5daxe() ? "§a✓" : "§4✗")
			+ " §9│ §eKills: §6" + String.format(java.util.Locale.ROOT, "%,d", p.mythosKills()));
		if (inviteButton) ChatText.clientMessage(clickable("§7[§eClick to invite§7]", "/p invite " + p.name(), "/p invite " + p.name()));
	}

	// ---- party list ---------------------------------------------------------------------------------------

	public static List<PartyModels.Party> cachedParties(String type) {
		return partyCache.getOrDefault(type, List.of());
	}

	public static boolean isRefreshing(String type) {
		return refreshing.getOrDefault(type, false);
	}

	/** @param explicit user clicked Refresh (subject to the 5s cooldown message). */
	public static void refreshParties(String type, boolean explicit, Runnable done) {
		long now = System.currentTimeMillis();
		if (explicit && now - lastListAt.getOrDefault(type, 0L) < 5000) {
			msg("§ePlease wait 5 seconds before refreshing the party list again.");
			return;
		}
		if (isRefreshing(type)) return;
		refreshing.put(type, true);
		lastListAt.put(type, now);
		SboApi.listParties(type, r -> {
			refreshing.put(type, false);
			if (SboApi.bool(r, "Success")) partyCache.put(type, PartyModels.parties(r));
			else msg("§4Failed to get parties");
			if (done != null) done.run();
		}, e -> {
			refreshing.put(type, false);
			msg("§4Unexpected error while getting parties: " + e);
			if (done != null) done.run();
		});
		if (now - lastRefreshAt > 30_000) {
			lastRefreshAt = now;
			SboApi.activeUsers(r -> activeUsers = SboApi.integer(r, "activeUsers", activeUsers), e -> {});
		}
	}

	public static boolean hasCache(String type) {
		return partyCache.containsKey(type);
	}
}
