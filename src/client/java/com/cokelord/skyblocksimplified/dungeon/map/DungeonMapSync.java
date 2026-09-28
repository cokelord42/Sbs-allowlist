package com.cokelord.skyblocksimplified.dungeon.map;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.LobbyIdTracker;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.network.WebSocketClient;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import java.util.List;

/**
 * Real-time cross-teammate dungeon-room secrets sync over Odin's own shared relay — per user request ("Odin
 * added websocket support for the dungeon map, rendering our rememberance system a little useless. Make it
 * prioritize sending websocket data but if it cant find any it should fall back to our method"). Ported
 * from Odin's confirmed {@code DungeonMap.kt}/{@code ClickGUIModule.kt} websocket wiring, reusing the exact
 * same relay {@link com.cokelord.skyblocksimplified.feature.impl.MelodyDisplayFeature} already connects to
 * ({@code wss://ws.odtheking.com/}, keyed by {@link LobbyIdTracker}'s own real lobby id): broadcasts this
 * player's own currently-known secrets count for their current room to every other SBS user in the same
 * real dungeon lobby, and adopts a teammate's broadcast for a room whenever its reported count is HIGHER
 * than what this client already has locally — a monotonic max, so a stale/late packet can never regress
 * real progress. This is the "prioritize websocket data" half of the request: {@link WorldScan}'s own
 * existing local detection (the real action-bar "N/Y Secrets" reading — this class's one and only local
 * source of truth to broadcast in the first place) is completely untouched and keeps running exactly as
 * before, so a lobby with no other SBS users in it (nothing ever arrives over the socket) just keeps working
 * off local detection alone — the "fall back to our method" half, automatically, since nothing here ever
 * disables or replaces that local detection, it only ever gets a chance to be overridden upward by a
 * teammate's more-current count.
 *
 * <p>Only teammates ALSO running this mod (not Odin itself) can ever understand these messages — this
 * relay is shared, third-party-provided infrastructure (see {@code MelodyDisplayFeature}'s own doc comment
 * on the relay owner's explicit go-ahead), not a shared wire format with Odin — so the JSON shape below is
 * this mod's own minimal DTO, not a literal copy of Odin's much larger {@code DungeonRoom} serialization.
 *
 * <p>Re-verified against Odin's actual {@code WorldScan.kt}/{@code EventDispatcher.kt} source (per user
 * request "make the websocket map stuff fully work... it should be exactly like the odin implementation"):
 * Odin's own {@code RoomEnterEvent(null)} does NOT fire on ordinary hallway transitions — a previous version
 * of this doc comment claimed otherwise and that claim was wrong. Odin's real firing condition is
 * {@code currentRoom != null && (!DungeonUtils.inDungeons || DungeonUtils.inBoss)} — a null event only on the
 * rare "just left the dungeon" or "just entered the boss fight" edge, each a one-time transition per run; an
 * unresolved/hallway tile is a silent no-op on Odin's side, never a null event. THIS codebase's own
 * {@link WorldScan} does have a second, genuinely frequent null-room-enter firing site of its own (walking
 * through a tile whose room geometry hasn't finished resolving yet — see that class's own {@code tick()} doc
 * comment) that has no Odin equivalent and both cases share the exact same callback, so hooking a shutdown
 * onto {@link WorldScan#addRoomEnterListener} directly would still reconnect on that codebase-specific case
 * even though Odin's real trigger is safe to copy. Instead, this class reproduces Odin's real two conditions
 * directly against {@link DungeonState}: shuts down once on the real "was in a dungeon, now isn't" transition
 * (matches Odin's {@code !DungeonUtils.inDungeons} half) and once on the real "just entered the boss fight"
 * transition (matches Odin's {@code DungeonUtils.inBoss} half) — the same pair of one-time edges Odin's own
 * {@code RoomEnterEvent(null)} covers, without ever tripping on this codebase's own hallway-resolution case.
 * Also matches Odin's {@code LevelEvent.Load} (a relog/rejoin, which in Odin's own {@code EventDispatcher.kt}
 * is literally just Fabric's {@code ClientPlayConnectionEvents.JOIN}) by shutting down on JOIN too, so a stale
 * connection from a previous world can never survive into a new one.
 */
public final class DungeonMapSync {
	private static final String RELAY_URL = "wss://ws.odtheking.com/";
	// Same real, one-time-per-run signal SelfClassCache/SplitsFeature already anchor their own "a run just
	// began" logic to.
	private static final String DUNGEON_STARTING_LINE = "Starting in 1 second.";

	private static final WebSocketClient SOCKET = new WebSocketClient();
	private static final Gson GSON = new Gson();

	private static boolean registered = false;
	private static boolean connected = false;
	private static boolean wasInDungeon = false;
	private static boolean wasInBoss = false;

	// Per user request/Odin's own real "Parse map websocket" setting — gates only the RECEIVE side (adopting
	// a teammate's broadcast), matching Odin's own exact scope: this client still SENDS its own progress
	// regardless of this toggle, since that's what lets OTHER teammates benefit even from a client that's
	// opted itself out of reading others' updates.
	private static volatile boolean allowReceive = true;

	public static void setAllowReceive(boolean value) { allowReceive = value; }

	private DungeonMapSync() {}

	public static void register() {
		if (registered) return;
		registered = true;

		// Not guaranteed to already be registered — MelodyDisplayFeature calls this too, but that Feature might
		// be disabled while this always-on infra still needs a lobby id. Idempotent (its own registered guard).
		LobbyIdTracker.ensureRegistered();
		SOCKET.onMessage(DungeonMapSync::onMessage);

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (!overlay && DUNGEON_STARTING_LINE.equals(message.getString())) {
				try {
					connectForRun();
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("DungeonMapSync: failed to connect for this run", e);
				}
			}
			return true;
		});

		WorldScan.addRoomEnterListener(room -> {
			if (room == null) return;
			try {
				send(room);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("DungeonMapSync: failed to broadcast room-enter", e);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> shutdown());
		// Matches Odin's LevelEvent.Load -> DungeonMap.syncSocket.shutdown() (which is exactly Fabric's own
		// JOIN event on Odin's side too — see this class's own doc comment) — a relog/rejoin can never carry
		// a stale connection from the previous world into the new one.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> shutdown());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean inDungeon = DungeonState.isInDungeon();
			boolean inBoss = DungeonState.isInBoss();
			// Reproduces Odin's real RoomEnterEvent(null) firing condition (see this class's own doc comment):
			// a one-time shutdown on genuinely leaving the dungeon, and a separate one-time shutdown on
			// genuinely entering the boss fight — the exact two edges Odin's own null-room event covers.
			if ((!inDungeon && wasInDungeon) || (inBoss && !wasInBoss)) shutdown();
			wasInDungeon = inDungeon;
			wasInBoss = inBoss;
		});
	}

	private static void connectForRun() {
		String lobbyId = LobbyIdTracker.getLobbyId();
		if (lobbyId == null) return;
		SOCKET.connect(RELAY_URL + lobbyId);
		connected = true;
	}

	private static void shutdown() {
		if (!connected) return;
		connected = false;
		SOCKET.shutdown();
	}

	/** Called from {@link WorldScan}'s own action-bar secrets parsing, right after a fresh real "N/Y Secrets"
	 *  reading updates {@code room.secretsFound} — matches Odin's own {@code SecretsUpdateEvent} trigger
	 *  point exactly (a fresh local reading, not just a room-enter). Also called directly by this class's own
	 *  room-enter listener above, matching Odin's second, independent send trigger. */
	public static void send(DungeonRoom room) {
		if (!connected || room.secretsFound < 0) return;
		SyncedRoom payload = new SyncedRoom(room.getName(), room.tiles, room.secretsFound);
		SOCKET.send(GSON.toJson(payload));
	}

	private static void onMessage(String message) {
		if (!allowReceive || !DungeonState.isInDungeon()) return;
		SyncedRoom synced;
		try {
			synced = GSON.fromJson(message, SyncedRoom.class);
		} catch (Exception e) {
			return;
		}
		if (synced == null || synced.tiles == null) return;
		for (DungeonRoom room : DungeonScan.rooms) {
			boolean matchesByName = synced.name != null && synced.name.equals(room.getName());
			boolean matchesByTiles = synced.tiles.equals(room.tiles);
			if (!matchesByName && !matchesByTiles) continue;
			if (synced.secretsFound > room.secretsFound) room.secretsFound = synced.secretsFound;
			room.walkedInto = true;
			break;
		}
	}

	// Plain Gson DTO — deliberately minimal (just what matching + syncing actually needs), not a mirror of
	// DungeonRoom's own much larger real field set (rotation/clayPos/checkmark/etc. are this client's own
	// local geometry-resolution state, meaningless to broadcast to a teammate whose own instance already
	// resolves those independently from their own real chunk data).
	private static final class SyncedRoom {
		final String name;
		final List<IVec2> tiles;
		final int secretsFound;

		SyncedRoom(String name, List<IVec2> tiles, int secretsFound) {
			this.name = name;
			this.tiles = tiles;
			this.secretsFound = secretsFound;
		}
	}
}
