package com.cokelord.skyblocksimplified.dungeon.map.tile;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonScan;
import com.cokelord.skyblocksimplified.dungeon.map.IVec2;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/** A single dungeon room: its map tiles, real-world clay-corner position + rotation once resolved, and the
 *  coordinate transforms every room-relative dungeon feature (puzzle solvers, door highlight, etc.) needs.
 *  Faithful port of Odin's {@code DungeonRoom.kt}. */
public class DungeonRoom {
	public RoomType type;
	public RoomData data;
	public final List<IVec2> tiles = new ArrayList<>(4);
	public IVec2 topLeft;
	public RoomRotation rotation;
	public RoomShape shape = RoomShape.ONE_BY_ONE;

	public boolean walkedInto = false;
	public BlockPos clayPos;
	public Integer highestBlock;
	public MapCheckmark checkmark = MapCheckmark.UNDISCOVERED;
	public boolean isKnown1x1 = false;
	// Real per-room secrets-found count, -1 = unknown. Confirmed real signal: the action bar shows a plain
	// "N/Y Secrets" fraction for whichever room the player is CURRENTLY standing in (the exact same text
	// SecretsCounterFeature already parses) — cross-checked against devonian's own DungeonScanner.kt, which
	// only trusts a reading once it's seen the SAME room on two consecutive action-bar updates (guards
	// against a stale reading from the room just left leaking onto the room just entered, before a fresh
	// action-bar tick arrives). See WorldScan's action-bar listener for where this is actually set.
	public int secretsFound = -1;

	private IVec2 center;

	public DungeonRoom(RoomType type, IVec2 initialPosition, RoomData data) {
		this.type = type;
		this.topLeft = initialPosition;
		this.data = data;
	}

	public IVec2 getCenter() {
		return center;
	}

	public boolean isViewable() {
		return walkedInto || checkmark != MapCheckmark.UNDISCOVERED;
	}

	public String getName() {
		return data != null ? data.getName() : null;
	}

	public void addSegment(DungeonTile segment) {
		if (tiles.contains(segment.position)) return;
		tiles.add(segment.position);
		int minX = tiles.stream().mapToInt(IVec2::x).min().orElseThrow();
		int minZ = tiles.stream().mapToInt(IVec2::z).min().orElseThrow();
		topLeft = new IVec2(minX, minZ);
		if (highestBlock != null && data != null && tiles.size() == data.getShape().getTileAmount()) {
			inferLayout(highestBlock);
		}
		recalculateCenter();
	}

	private void recalculateCenter() {
		if (tiles.isEmpty()) return;
		int rs = DungeonScan.roomSize;
		if (rs == -1) return;
		int rg = DungeonScan.roomGap;
		int half = rs / 2;

		if (rotation == null) {
			IVec2 tile = tiles.stream().min((a, b) -> Integer.compare(a.sortKey(), b.sortKey())).orElseThrow();
			center = new IVec2(tile.x() * rg + half, tile.z() * rg + half);
			return;
		}

		List<int[]> centers = tiles.stream().map(t -> new int[]{t.x() * rg + half, t.z() * rg + half}).toList();
		float x = (centers.stream().mapToInt(c -> c[0]).min().orElseThrow()
			+ centers.stream().mapToInt(c -> c[0]).max().orElseThrow()) / 2f;
		float z;
		if (shape == RoomShape.L) {
			z = (rotation == RoomRotation.NORTH || rotation == RoomRotation.WEST)
				? centers.stream().mapToInt(c -> c[1]).min().orElseThrow()
				: centers.stream().mapToInt(c -> c[1]).max().orElseThrow();
		} else {
			z = (centers.stream().mapToInt(c -> c[1]).min().orElseThrow()
				+ centers.stream().mapToInt(c -> c[1]).max().orElseThrow()) / 2f;
		}
		center = new IVec2(Math.round(x), Math.round(z));
	}

	public void inferLayoutFromMap() {
		int minX = tiles.stream().mapToInt(IVec2::x).min().orElseThrow();
		int minZ = tiles.stream().mapToInt(IVec2::z).min().orElseThrow();
		int maxX = tiles.stream().mapToInt(IVec2::x).max().orElseThrow();
		int maxZ = tiles.stream().mapToInt(IVec2::z).max().orElseThrow();

		shape = switch (tiles.size()) {
			case 1 -> RoomShape.ONE_BY_ONE;
			case 2 -> RoomShape.TWO_BY_ONE;
			case 3 -> (maxX - minX == 1 && maxZ - minZ == 1) ? RoomShape.L : RoomShape.THREE_BY_ONE;
			case 4 -> (maxX - minX == 1 && maxZ - minZ == 1) ? RoomShape.TWO_BY_TWO : RoomShape.FOUR_BY_ONE;
			default -> RoomShape.ONE_BY_ONE;
		};

		RoomRotation rot = resolveGeometryRotation();
		if (rot != null) rotation = rot;
	}

	public void inferLayout(int highestBlock) {
		if (data == null) return;
		if (applyFairyFallback(highestBlock)) return;

		shape = data.getShape();
		if (shape == RoomShape.ONE_BY_ONE) {
			get1x1Rotation();
			return;
		}

		RoomRotation rot = resolveGeometryRotation();
		BlockPos pos = rot != null ? resolveClayPos(rot, highestBlock) : null;

		if (rot == null || pos == null) {
			SkyblockSimplified.LOGGER.warn("Failed to resolve geometry for {} at {} with tiles {}",
				data != null ? data.getName() : type, topLeft, tiles);
			return;
		}

		rotation = rot;
		clayPos = pos;
	}

	private RoomRotation resolveGeometryRotation() {
		IVec2 bottomRight = tiles.stream().max((a, b) -> Integer.compare(a.sortKey(), b.sortKey())).orElse(null);
		if (bottomRight == null) return null;

		if (shape == RoomShape.L) {
			IVec2 other = tiles.stream().filter(t -> !t.equals(topLeft) && !t.equals(bottomRight)).findFirst().orElse(null);
			if (other == null) return null;
			if (topLeft.x() == bottomRight.x()) return RoomRotation.EAST;
			if (topLeft.z() == bottomRight.z()) return RoomRotation.WEST;
			if (other.x() == topLeft.x()) return RoomRotation.SOUTH;
			return RoomRotation.NORTH;
		} else {
			return topLeft.x() == bottomRight.x() ? RoomRotation.WEST : RoomRotation.SOUTH;
		}
	}

	private BlockPos resolveClayPos(RoomRotation rotation, int height) {
		IVec2 bottomRight = tiles.stream().max((a, b) -> Integer.compare(a.sortKey(), b.sortKey())).orElse(null);
		if (bottomRight == null) return null;

		int[] tl = getRealPosition(topLeft.x(), topLeft.z());
		int[] br = getRealPosition(bottomRight.x(), bottomRight.z());

		if (shape == RoomShape.L) {
			IVec2 other = tiles.stream().filter(t -> !t.equals(topLeft) && !t.equals(bottomRight)).findFirst().orElse(null);
			if (other == null) return null;
			int[] ot = getRealPosition(other.x(), other.z());
			return switch (rotation) {
				case EAST -> new BlockPos(ot[0] - 15, height, tl[1] + 15);
				case WEST -> new BlockPos(br[0] + 15, height, br[1] - 15);
				case SOUTH -> new BlockPos(tl[0] - 15, height, tl[1] - 15);
				case NORTH -> new BlockPos(br[0] + 15, height, br[1] + 15);
			};
		} else {
			return rotation == RoomRotation.WEST
				? new BlockPos(tl[0] + 15, height, tl[1] - 15)
				: new BlockPos(tl[0] - 15, height, tl[1] - 15);
		}
	}

	public boolean get1x1Rotation() {
		if (shape != RoomShape.ONE_BY_ONE) return false;
		if (highestBlock == null) return false;
		int y = highestBlock;
		if (applyFairyFallback(y)) return true;

		Minecraft mc = Minecraft.getInstance();
		for (RoomRotation rot : RoomRotation.values()) {
			BlockPos pos = clayProbePos(rot, y);
			if (mc.level != null && isBlueTerracotta(mc.level.getBlockState(pos).getBlock())) {
				rotation = rot;
				clayPos = pos;
				return true;
			}
		}
		return false;
	}

	private boolean applyFairyFallback(int highestBlock) {
		if (data == null || !"Fairy".equals(data.getName())) return false;
		clayPos = clayProbePos(RoomRotation.SOUTH, highestBlock);
		rotation = RoomRotation.SOUTH;
		return true;
	}

	private BlockPos clayProbePos(RoomRotation rotation, int y) {
		int[] pos = getRealPosition();
		return new BlockPos(pos[0] + rotation.dx, y, pos[1] + rotation.dz);
	}

	public int[] getRealPosition() {
		return getRealPosition(topLeft.x(), topLeft.z());
	}

	public int[] getRealPosition(int x, int z) {
		return new int[]{x * 32 - 185, z * 32 - 185};
	}

	/** World BlockPos -> room-relative BlockPos, as if the room's clay corner were the origin facing
	 *  north. Used by every puzzle solver / door / waypoint that stores positions relative to the room. */
	public BlockPos getRelativeCoords(BlockPos pos) {
		if (clayPos == null || rotation == null) return BlockPos.ZERO;
		BlockPos relative = pos.subtract(clayPos.atY(0));
		return rotateToNorth(relative, rotation);
	}

	/** Room-relative BlockPos -> world BlockPos, the inverse of {@link #getRelativeCoords}. */
	public BlockPos getRealCoords(BlockPos pos) {
		if (clayPos == null || rotation == null) return BlockPos.ZERO;
		BlockPos rotated = rotateAroundNorth(pos, rotation);
		return rotated.offset(clayPos.getX(), 0, clayPos.getZ());
	}

	private static BlockPos rotateAroundNorth(BlockPos pos, RoomRotation rotation) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		return switch (rotation) {
			case NORTH -> new BlockPos(-x, y, -z);
			case WEST -> new BlockPos(-z, y, x);
			case SOUTH -> new BlockPos(x, y, z);
			case EAST -> new BlockPos(z, y, -x);
		};
	}

	// No compile-time Blocks.BLUE_TERRACOTTA constant exists for every color variant in this MC version
	// (see RareRewardWarningFeature's isRareReward for the same workaround) — compared by registry id instead.
	private static boolean isBlueTerracotta(Block block) {
		return "minecraft:blue_terracotta".equals(BuiltInRegistries.BLOCK.getKey(block).toString());
	}

	private static BlockPos rotateToNorth(BlockPos pos, RoomRotation rotation) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		return switch (rotation) {
			case NORTH -> new BlockPos(-x, y, -z);
			case WEST -> new BlockPos(z, y, -x);
			case SOUTH -> new BlockPos(x, y, z);
			case EAST -> new BlockPos(-z, y, x);
		};
	}
}
