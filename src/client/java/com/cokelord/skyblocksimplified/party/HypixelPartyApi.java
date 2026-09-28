package com.cokelord.skyblocksimplified.party;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket;
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;

/**
 * Bridge to Hypixel's own official Mod API party packet — real, server-confirmed, UUID-keyed party
 * membership/leader data, discovered while fixing a chat-scraping regression in {@link PartyApi} (per user
 * question: "doesnt hypixel mod api feed party data?"). Same isolation pattern as
 * {@link com.cokelord.skyblocksimplified.util.HypixelLocationApi}: this class is the only place that
 * references the Mod API party types, so a session where the bridge never starts (or the companion mod
 * fails to load) never triggers class-loading of them at all.
 *
 * <p>Unlike {@code ClientboundLocationPacket}, {@code ClientboundPartyInfoPacket} is NOT an
 * {@code EventPacket} (confirmed via javap: it implements only the plain {@code ClientboundHypixelPacket}
 * interface) — Hypixel doesn't proactively push it on every party change, it's a request/response pair.
 * {@link #requestUpdate()} sends the request half ({@code ServerboundPartyInfoPacket}); {@link PartyApi}
 * calls it right after connecting and again on every organic party-change signal it already parses from
 * chat, so this stays accurate without ever needing to poll on a timer.
 *
 * <p>Deliberately narrow in scope: this only ever resolves the PARTY LEADER (via {@link #confirmedLeaderName()}),
 * not the full member list. {@link PartyApi}'s existing chat + tab-list tracking already handles membership
 * correctly — the real, reported bug was leader detection specifically (a stale/never-learned leader
 * silently broke every {@code isLeader()}-gated chat command) — and a member's UUID can only resolve to a
 * name through this client's own local {@code PlayerInfo} cache, which isn't guaranteed to contain every
 * party member (e.g. one not yet in the same server instance); scoping to just the leader keeps this from
 * ever silently shrinking a correct membership list over an incomplete UUID resolution.
 */
public final class HypixelPartyApi {
	private HypixelPartyApi() {}

	private static volatile ClientboundPartyInfoPacket lastPacket = null;
	private static boolean started = false;

	/** Idempotent. Only ever called after the caller has already confirmed the "hypixel-mod-api" Fabric
	 *  mod is loaded — see PartyApi. */
	public static void start() {
		if (started) return;
		started = true;
		HypixelModAPI.getInstance().createHandler(ClientboundPartyInfoPacket.class, packet -> {
			lastPacket = packet;
			for (java.util.function.Consumer<ClientboundPartyInfoPacket> listener : LISTENERS) {
				try {
					listener.accept(packet);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("HypixelPartyApi listener failed", e);
				}
			}
		});
	}

	/** Sends the request half of this request/response pair. Safe to call often — it's a tiny custom-payload
	 *  packet, not a visible chat command, so there's no user-facing spam concern the way {@code /party list}
	 *  has; still only ever called from real event triggers (never a timer) by {@link PartyApi}. */
	public static void requestUpdate() {
		try {
			HypixelModAPI.getInstance().sendPacket(new ServerboundPartyInfoPacket());
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("HypixelPartyApi: failed to send party info request", e);
		}
	}

	/** The real party leader's username per Hypixel's own confirmed packet, or null if no packet has
	 *  arrived yet, the confirmed state is "not in a party", or the leader's UUID doesn't resolve to a name
	 *  in this client's own local player-info cache. Never a guess — see this class's own doc comment. */
	public static String confirmedLeaderName() {
		ClientboundPartyInfoPacket packet = lastPacket;
		if (packet == null || !packet.isInParty()) return null;
		UUID leaderUuid = packet.getLeader().orElse(null);
		if (leaderUuid == null) return null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) return null;
		PlayerInfo info = mc.getConnection().getPlayerInfo(leaderUuid);
		return info != null ? info.getProfile().name() : null;
	}

	/** Clears the cached packet on disconnect, matching HypixelLocationApi's own reset() — a stale leader
	 *  from a previous Hypixel session can't briefly read as "confirmed" again before the first real
	 *  response of the new session arrives. */
	private static final java.util.List<java.util.function.Consumer<ClientboundPartyInfoPacket>> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Called for every party info packet (on whatever thread the Mod API delivers it). */
	public static void addListener(java.util.function.Consumer<ClientboundPartyInfoPacket> listener) {
		LISTENERS.add(listener);
	}

	public static ClientboundPartyInfoPacket lastPacket() {
		return lastPacket;
	}

	public static void reset() {
		lastPacket = null;
	}
}
