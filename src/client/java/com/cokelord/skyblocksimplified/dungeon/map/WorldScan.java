package com.cokelord.skyblocksimplified.dungeon.map;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonTile;
import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomData;
import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomShape;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Detects which dungeon room the player is currently standing in, and (as new chunks load) identifies
 *  unknown rooms by hashing their terrain layout against the {@link RoomData} database. Faithful port of
 *  Odin's {@code WorldScan.kt}, registered once as always-on infra (like the existing
 *  {@code DungeonBlockDetector}/{@code WorldToScreen}), not a toggleable {@code Feature}.
 *
 *  <p>{@link #getRoomCore} hashes each room's terrain into a string whose {@code hashCode()} is looked up
 *  against {@code rooms.json}'s precomputed "cores", matching Odin's own algorithm exactly: each block
 *  contributes {@code Block.toString()}'s real output (confirmed via bytecode — {@code Block} overrides
 *  {@code toString()} to return {@code "Block{" + registeredName + "}"}, e.g. {@code "Block{minecraft:
 *  stone}"} — NOT the bare registry id). An earlier version of this port used {@code
 *  BuiltInRegistries.BLOCK.getKey(...)} directly (missing the {@code "Block{...}"} wrapper), which changed
 *  every hash and meant no room ever matched rooms.json at all — silently breaking every room-name/room-
 *  type-dependent feature (all 8 puzzle solvers, Room Clear, Door Highlight) at once. */
public final class WorldScan {
	private static volatile DungeonRoom currentRoom;
	private static final Map<RoomData, DungeonRoom> dataToRoom = new HashMap<>();
	private static final Set<IVec2> chunksToScan = new HashSet<>();
	private static final List<Consumer<DungeonRoom>> roomEnterListeners = new ArrayList<>();
	private static boolean wasInDungeon = false;
	// See DungeonRoom.secretsFound's own doc comment — real signal (action bar "N/Y Secrets"), only trusted
	// once the SAME room has been seen on two consecutive action-bar ticks, matching devonian's own guard.
	private static final java.util.regex.Pattern ROOM_SECRETS_PATTERN = java.util.regex.Pattern.compile("\\b(\\d+)/(\\d+) Secrets");
	private static DungeonRoom lastSecretsRoom;

	private static boolean registered = false;

	private WorldScan() {}

	public static DungeonRoom getCurrentRoom() {
		return currentRoom;
	}

	/** Same tile-index math as {@link #tick}'s own player-room lookup, generalized to an arbitrary world
	 *  position — added per user request ("I want the highlighted starred mobs to hide when not in their
	 *  room"), which needs to know which room a non-player entity (a tracked highlighted mob) is standing
	 *  in, not just the player. Returns null if the position falls outside the known 6x6 dungeon tile grid
	 *  or that tile has no resolved room yet — same "nothing known yet" cases {@link #tick} itself already
	 *  bails out on. */
	public static DungeonRoom roomAt(int blockX, int blockZ) {
		int tileX = (blockX + 201) >> 5;
		int tileZ = (blockZ + 201) >> 5;
		if (tileX < 0 || tileX > 5 || tileZ < 0 || tileZ > 5) return null;
		DungeonTile tile = DungeonScan.tiles[tileX + tileZ * 6];
		return tile.room;
	}

	public static void addRoomEnterListener(Consumer<DungeonRoom> listener) {
		roomEnterListeners.add(listener);
	}

	public static void register() {
		if (registered) return;
		registered = true;

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tick(client);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("WorldScan tick failed, skipping this tick", e);
			}
		});

		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			try {
				if (!DungeonState.isInDungeon()) chunksToScan.add(new IVec2(chunk.getPos().x(), chunk.getPos().z()));
				else scanChunk(chunk);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("WorldScan chunk-load scan failed, skipping this chunk", e);
			}
		});
		ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
			if (!DungeonState.isInDungeon()) chunksToScan.remove(new IVec2(chunk.getPos().x(), chunk.getPos().z()));
		});

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			try {
				if (overlay) onActionBar(message.getString());
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("WorldScan action-bar secrets parse failed, skipping this line", e);
			}
			return true;
		});
	}

	private static void onActionBar(String rawText) {
		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) return;
		DungeonRoom room = currentRoom;
		if (room == null) return;
		if (room != lastSecretsRoom) {
			// First reading since entering (or re-entering) this room — matches devonian's own guard: don't
			// trust it yet, it could still be a stale action-bar frame left over from the PREVIOUS room that
			// just hasn't refreshed. Only start trusting once the SAME room shows up on the next tick too.
			lastSecretsRoom = room;
			return;
		}
		java.util.regex.Matcher m = ROOM_SECRETS_PATTERN.matcher(rawText.replaceAll("§.", ""));
		if (!m.find()) return;
		try {
			int found = Integer.parseInt(m.group(1));
			int total = Integer.parseInt(m.group(2));
			int roomTotal = room.data != null ? room.data.getSecrets() : -1;
			if (roomTotal >= 0 && total != roomTotal) return;
			room.secretsFound = found;
		} catch (NumberFormatException ignored) {}
	}

	private static void reset() {
		dataToRoom.clear();
		chunksToScan.clear();
		currentRoom = null;
		lastSecretsRoom = null;
		MapScan.reset();
		DungeonScan.reset();
	}

	/** Per user report ("if I join a new dungeon from the dungeon without dying/going to the lobby...
	 *  running the command again just leaves it all as the old dungeon, including the map"): this class's
	 *  own {@link #tick} only ever calls the private {@link #reset()} on a "was in a dungeon, now isn't"
	 *  transition — a direct dungeon-to-dungeon rejoin (party leader re-running /joininstance mid-run, one
	 *  of the very shortcuts this mod's own Chat Commands feature just added real support for) never passes
	 *  through that transition client-side, so every cached tile/room/door from the OLD run silently carried
	 *  into the new one. DungeonState.tick() now calls this the moment it detects the floor-number scoreboard
	 *  line has changed to a genuinely different value while a floor was already known — the one signal that
	 *  can only mean "this is a different run," regardless of whether isInDungeon() ever went false. */
	public static void forceReset() {
		reset();
	}

	private static void tick(Minecraft client) {
		DungeonState.tick();
		MapScan.tick(client);

		if (client.player == null) return;

		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) {
			// Real bug found (per user report: "the mod has issues detecting rooms, its showing the
			// teleport pad solver in the spider room"): DungeonScan.tiles/rooms — and this class's own
			// dataToRoom/chunksToScan cache — were previously only ever cleared on a full relog
			// (ClientPlayConnectionEvents.JOIN/DISCONNECT), never on simply finishing one dungeon run and
			// starting another in the same session. A tile's cached DungeonRoom (with its old data/type)
			// from the PREVIOUS run satisfied scanChunk's "already scanned" check for the new run at that
			// same tile index, so the new run's actual room at that grid position was never rescanned —
			// leaving whatever room/solver last occupied that tile stuck there, e.g. Teleport Maze's pads
			// rendering while standing in this run's Spider room. Reset once on the actual "was in a
			// dungeon, now isn't" transition, not every tick spent outside one.
			//
			// Second real bug found (per user report: "the dungeon map resets when watcher is done. It
			// should only reset when the boss is entered."): this used to reset the instant
			// DungeonState.isInBoss() went true, but that flips true as early as the Watcher's own "You may
			// pass" portal-entry line (F7) or the boss-entry chat line — well before the player has actually
			// walked into the boss room. isBossFightVisuallyActive() is the narrower, later signal
			// (DungeonState's own doc comment already calls out Dungeon Map's "Show in Boss" as its intended
			// use) — gating the reset on that instead means the map only clears once the boss fight is
			// really visually starting, not the instant the Watcher lets you through. wasInDungeon is
			// deliberately NOT cleared during the in-between window (isInBoss() true but not yet visually
			// active) so this reset still fires exactly once, the moment that later signal arrives.
			// Real bug found (per user report — "Dungeon Map in the boss fight needs retrying... the map
			// tries to reload the actual dungeon (fails since out of render distance) instead of preserving
			// explored-room/player-position state on boss entry"): this used to call reset() — which wipes
			// DungeonScan.rooms/doors entirely, see DungeonScan#reset() — the moment isBossFightVisuallyActive()
			// went true, on the theory that boss data should be cleared "fresh" too. But nothing ever re-scans
			// the floor's rooms while in the boss room (the boss arena's chunks are the only ones in render
			// distance there), so that wipe just permanently threw away the whole explored layout for the rest
			// of the run — a genuine data loss, not a reload, since there's nothing left to reload it FROM. The
			// fix is to simply not reset on this transition at all: boss entry already short-circuits every tick
			// below (the early `return` a few lines down), so DungeonScan.rooms/doors naturally stay frozen at
			// whatever was last explored, undisturbed, for the whole boss fight — exactly the "preserve state"
			// behavior requested. Only a genuine "was in a dungeon, now isn't" transition still resets.
			boolean trulyLeftDungeon = !DungeonState.isInDungeon();
			if (trulyLeftDungeon) {
				if (wasInDungeon) reset();
				wasInDungeon = false;
			}
			if (currentRoom != null) {
				currentRoom = null;
				fireRoomEnter(null);
			}
			return;
		}
		wasInDungeon = true;

		// Once the player is inside the dungeon, catch up on any chunks that loaded before we knew we
		// were in a dungeon (matches Odin's LocationChangeEvent-triggered flush).
		if (!chunksToScan.isEmpty() && client.level != null) {
			for (IVec2 pos : chunksToScan) {
				LevelChunk chunk = client.level.getChunk(pos.x(), pos.z());
				scanChunk(chunk);
			}
			chunksToScan.clear();
		}

		for (DungeonRoom room : DungeonScan.rooms) {
			if (room.shape != RoomShape.ONE_BY_ONE) continue;
			if (room.rotation != null && room.clayPos != null) continue;
			if (room.highestBlock != null) room.get1x1Rotation();
		}

		int tileX = (client.player.getBlockX() + 201) >> 5;
		int tileZ = (client.player.getBlockZ() + 201) >> 5;
		if (tileX < 0 || tileX > 5 || tileZ < 0 || tileZ > 5) return;

		DungeonTile tile = DungeonScan.tiles[tileX + tileZ * 6];
		DungeonRoom room = tile.room;
		if (room == null) return;
		if (room == currentRoom) return;
		if (room.rotation == null || room.highestBlock == null) {
			// Real bug found (per user report — "when blood room is opened/finished the mod sets my current
			// room to blood even if i exit... no routes render"): this used to just fall through here,
			// silently leaving currentRoom pointed at whatever room was active before — including a room the
			// player has genuinely already left — until the NEW room's own geometry (rotation/highestBlock)
			// finishes resolving, which can lag a tick or more behind the player physically walking into it.
			// That stale currentRoom stuck DungeonRoutesFeature's activeRoomName on the room just left, so
			// its route markers never advanced for anywhere after it. Clearing here instead means "no
			// confirmed active room" is the honest interim state — self-heals the instant this same room's
			// geometry resolves (still driven every tick elsewhere), rather than pretending the player is
			// still standing in the old room.
			if (currentRoom != null) {
				currentRoom = null;
				fireRoomEnter(null);
			}
			return;
		}
		// Odin also skips this while standing on the roof above a not-yet-descended-into room, unless in
		// a singleplayer test world; SBAR doesn't currently have a singleplayer-detection helper wired
		// into IslandGate, so that carve-out is dropped rather than guessed at.
		if (client.player.getBlockY() > room.highestBlock - 10) return;

		currentRoom = room;
		room.walkedInto = true;
		fireRoomEnter(room);
	}

	// Per user report ("The map doesnt update often enough. It takes like 2 seconds when i enter a new room
	// to detect all doors going out from the room"): doors are drawn straight from DungeonScan.doors the
	// instant they're populated (see DungeonMapFeature's own door-render loop — no gate on room resolution),
	// and MapScan.tick() already re-parses the held dungeon map's cached pixel data every single client tick
	// (no throttle of our own anywhere in that path) — so there is no artificial delay left in THIS mod's own
	// pipeline to remove; whatever gap remains is however long it takes the real vanilla MapItemSavedData
	// object to actually receive the server's next ClientboundMapItemDataPacket covering the new area, which
	// is server-driven and outside this client mod's control. Rather than guess at an unconfirmed fix for a
	// server-side cadence this environment has no way to verify live, this records the real elapsed time so
	// a future round (or the user, with Debug on) has real numbers instead of another guess.
	private static long lastRoomEnterMillis = 0L;
	private static java.util.Set<Integer> lastRoomEnterTileIndices = java.util.Set.of();

	/** See {@link #lastRoomEnterMillis}'s own doc comment — consumed by {@link MapScan#addOrFixDoor} to time
	 *  how long after a room-enter its own doors actually get detected. */
	public static long lastRoomEnterMillis() { return lastRoomEnterMillis; }
	public static java.util.Set<Integer> lastRoomEnterTileIndices() { return lastRoomEnterTileIndices; }

	private static void fireRoomEnter(DungeonRoom room) {
		if (room != null) {
			lastRoomEnterMillis = System.currentTimeMillis();
			java.util.Set<Integer> indices = new java.util.HashSet<>();
			for (IVec2 tile : room.tiles) indices.add(tile.x() + tile.z() * 6);
			lastRoomEnterTileIndices = indices;
		}
		for (Consumer<DungeonRoom> listener : roomEnterListeners) {
			try {
				listener.accept(room);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("WorldScan room-enter listener threw", e);
			}
		}
	}

	private static void scanChunk(LevelChunk chunk) {
		IVec2 chunkPosition = new IVec2(chunk.getPos().x(), chunk.getPos().z());
		boolean columnEven = chunkPosition.z() % 2 == 0;
		boolean rowEven = chunkPosition.x() % 2 == 0;

		if (chunkPosition.x() >= -12 && chunkPosition.x() <= -2 && chunkPosition.z() >= -12 && chunkPosition.z() <= -2) {
			if (rowEven && columnEven) {
				IVec2 tilePos = chunkPosition.divide(2).plus(6);
				int index = tilePos.x() + tilePos.z() * 6;
				DungeonTile tile = index >= 0 && index < DungeonScan.tiles.length ? DungeonScan.tiles[index] : null;
				if (tile == null || tile.room == null || tile.room.data == null) scanRoom(chunk, chunkPosition);
			}
		}
	}

	private static void scanRoom(LevelChunk chunk, IVec2 chunkPosition) {
		IVec2 samplePos = chunkPosition.times(16).plus(7);
		int[] coreAndHeight = getRoomCore(chunk, samplePos.x(), samplePos.z());
		int core = coreAndHeight[0];
		int highestBlock = coreAndHeight[1];

		RoomData data = RoomData.getRoomData(core);
		if (data == null) {
			if (core != -318865360) {
				SkyblockSimplified.LOGGER.warn("Unknown dungeon room core: {} at {}", core, chunkPosition);
			}
			return;
		}

		IVec2 tilePosition = chunkPosition.divide(2).plus(6);
		int tileIndex = tilePosition.x() + tilePosition.z() * 6;
		if (tileIndex < 0 || tileIndex >= DungeonScan.tiles.length) return;
		DungeonTile tile = DungeonScan.tiles[tileIndex];

		DungeonRoom room = dataToRoom.computeIfAbsent(data, d -> {
			DungeonRoom existing = tile.room;
			if (existing != null) {
				existing.data = d;
				existing.type = d.getType();
				return existing;
			}
			DungeonRoom created = new DungeonRoom(d.getType(), tile.position, d);
			DungeonScan.rooms.add(created);
			return created;
		});

		if (tile.room != room) {
			room.highestBlock = highestBlock;
			tile.room = room;
			room.addSegment(tile);
		} else if (room.highestBlock == null) {
			// Real bug found (per user report — "the Creeper Beams lantern-matching highlight isn't working
			// at all"): MapScan can pre-create this exact DungeonRoom (from the held dungeon map's pixel
			// data, often well before this room's own chunk loads — routine for small single-tile PUZZLE
			// rooms, which get their own distinct map icon visible from across the floor) and already add
			// every one of its tiles as segments before this method ever runs for it. When that happens,
			// `tile.room == room` already (the branch above never fires) AND DungeonRoom#addSegment's own
			// "already added this tile" guard silently no-ops if called again — so its "just reached the
			// room's full tile count, resolve layout now" trigger (the ONLY place highestBlock ever reached
			// DungeonRoom#inferLayout/get1x1Rotation) never fires either. highestBlock — and therefore
			// rotation/clayPos — stayed permanently null, and DungeonRoom#getRealCoords silently returns
			// BlockPos.ZERO forever for every position any room-relative feature (every puzzle solver, Door
			// Highlight, Room Clear) asks this room for. Resolving layout directly here covers the one case
			// addSegment's own tile-count trigger structurally can't: a room whose tiles were already fully
			// known before its real per-tile height was.
			room.highestBlock = highestBlock;
			if (room.data != null && room.tiles.size() == room.data.getShape().getTileAmount()) {
				room.inferLayout(highestBlock);
			}
		}

		if (room.data == null) {
			room.data = data;
			room.type = data.getType();
		}
	}

	private static int[] getRoomCore(LevelChunk chunk, int worldX, int worldZ) {
		StringBuilder sb = new StringBuilder(1024);
		boolean foundHighest = false;
		int highestBlock = 0;
		int bedrock = 0;

		for (int y = 140; y >= 12; y--) {
			BlockState state = chunk.getBlockState(new BlockPos(worldX, y, worldZ));

			if (!foundHighest) {
				if (!state.isAir() && state.getBlock() != Blocks.GOLD_BLOCK) {
					foundHighest = true;
					highestBlock = y;
				} else {
					sb.append('0');
				}
			}

			if (foundHighest) {
				if (state.isAir() && bedrock >= 2 && y < 69) {
					sb.repeat("0", Math.max(0, y - 11));
					break;
				}
				if (state.getBlock() == Blocks.BEDROCK) {
					bedrock++;
				} else {
					bedrock = 0;
					if (state.getBlock() == Blocks.OAK_PLANKS || state.getBlock() == Blocks.TRAPPED_CHEST || state.getBlock() == Blocks.CHEST) {
						continue;
					}
				}
				sb.append(state.getBlock());
			}
		}

		return new int[]{sb.toString().hashCode(), highestBlock};
	}
}
