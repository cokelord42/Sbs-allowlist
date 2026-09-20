package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;

/** F6/M6 "Tic Tac Toe" puzzle solver — highlights the most "efficient" (minimax-optimal) button to press
 *  to at least tie the puzzle. Ported from Devonian's {@code TicTacToeSolver.kt} per user request/memory of
 *  an earlier ask that turned out to have been missed.
 *
 * <p>The board is read from the 9 item-frame-mounted maps in the room: Devonian detects a real
 * {@code ClientboundSetEntityDataPacket}-driven map-data update; this codebase has no generic incoming-
 * packet listener registry for that without a new Mixin, so this instead re-scans the 9 board slots once
 * per client tick — same tick-poll adaptation {@link com.cokelord.skyblocksimplified.dungeon.map.MapScan}
 * already uses for the dungeon overview map itself. Each frame's held map is decoded the same way Devonian
 * does: the pixel with real map-color value 114 marks the X/O glyph, and its pixel INDEX (not just presence)
 * tells the two apart — index 2700 is Hypixel's real "X" glyph position, anything else is "O". */
public class TicTacToeSolverFeature extends Feature {
	// Room-relative {x, y, z} for each of the 9 board slots, real values ported from Devonian's own
	// confirmed boardPos table — Y is a literal world coordinate (Tic Tac Toe's board sits at a fixed real
	// height every time, not something that varies per-instance the way room floor height does elsewhere).
	private static final int[][] BOARD_POS = {
		{8, 72, 17}, {8, 72, 16}, {8, 72, 15},
		{8, 71, 17}, {8, 71, 16}, {8, 71, 15},
		{8, 70, 17}, {8, 70, 16}, {8, 70, 15}
	};
	private static final byte X_MARK_COLOR = 114;
	private static final int X_MARK_PIXEL_INDEX = 2700;

	// Minimax move order and win lines — ported verbatim from Devonian, not reinvented.
	private static final int[] BOARD_ORDER = {4, 0, 2, 6, 8, 1, 3, 5, 7};
	private static final int[][] WINNING_SIDES = {
		{0, 1, 2}, {3, 4, 5}, {6, 7, 8},
		{0, 3, 6}, {1, 4, 7}, {2, 5, 8},
		{0, 4, 8}, {2, 4, 6}
	};

	private boolean sendDoneMessage = false;
	private boolean predictNext = false;
	private int bestMoveColor = 0xFF55FF55;
	private int predictedMoveColor = 0xFFFFAA00;

	private final String[] currentBoard = new String[9];
	private boolean inRoom = false;
	private int currentBestMove = -1;
	private int predictedMove = -1;
	private boolean hasSent = false;

	private static boolean listenersRegistered = false;
	private static TicTacToeSolverFeature instance;

	public TicTacToeSolverFeature() {
		super("puzzle_tic_tac_toe", "Tic Tac Toe Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Puzzles"; }

	@Override
	protected void onEnable() {
		reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			World3DRenderer.addRenderCallback(TicTacToeSolverFeature::renderStatic);
		}
	}

	@Override
	protected void onDisable() { reset(); }

	private void reset() {
		Arrays.fill(currentBoard, null);
		currentBestMove = -1;
		predictedMove = -1;
		hasSent = false;
	}

	@Override
	public void onTick(Minecraft client) {
		DungeonRoom room = WorldScan.getCurrentRoom();
		if (room == null || room.data == null || !"Tic Tac Toe".equals(room.data.getName())) {
			if (inRoom) { inRoom = false; reset(); }
			return;
		}
		inRoom = true;
		if (client.level == null) return;

		BlockPos startXZ = room.getRealCoords(new BlockPos(7, 0, 14));
		BlockPos endXZ = room.getRealCoords(new BlockPos(9, 0, 18));
		AABB area = new AABB(startXZ.getX(), 69, startXZ.getZ(), endXZ.getX(), 73, endXZ.getZ());

		String[] board = new String[9];
		boolean moved = false;
		String lastStatus = null;

		for (ItemFrame frame : client.level.getEntitiesOfClass(ItemFrame.class, area)) {
			if (!(frame.getItem().getItem() instanceof MapItem)) continue;
			MapId mapId = frame.getItem().get(DataComponents.MAP_ID);
			if (mapId == null) continue;

			BlockPos framePos = BlockPos.containing(frame.getX(), frame.getY(), frame.getZ());
			BlockPos relative = room.getRelativeCoords(framePos);
			int frameY = (int) Math.floor(frame.getY());

			int idx = -1;
			for (int i = 0; i < BOARD_POS.length; i++) {
				if (BOARD_POS[i][0] == relative.getX() && BOARD_POS[i][1] == frameY && BOARD_POS[i][2] == relative.getZ()) {
					idx = i;
					break;
				}
			}
			if (idx == -1) continue;

			MapItemSavedData data = client.level.getMapData(mapId);
			byte[] colors = data != null ? data.colors : null;
			if (colors == null) continue;
			int markIndex = -1;
			for (int c = 0; c < colors.length; c++) {
				if (colors[c] == X_MARK_COLOR) { markIndex = c; break; }
			}
			if (markIndex == -1) continue;

			String status = markIndex == X_MARK_PIXEL_INDEX ? "X" : "O";
			board[idx] = status;
			if (!status.equals(currentBoard[idx])) {
				currentBestMove = -1;
				lastStatus = status;
				moved = true;
			}
		}
		if (!moved) return;

		System.arraycopy(board, 0, currentBoard, 0, 9);

		if ("X".equals(lastStatus)) onAIMove(currentBoard);

		int filled = 0;
		for (String s : currentBoard) if (s != null) filled++;
		if (filled == 8) {
			String nextStatus = "X".equals(lastStatus) ? "O" : "X";
			String[] projected = currentBoard.clone();
			for (int i = 0; i < 9; i++) {
				if (projected[i] == null) { projected[i] = nextStatus; break; }
			}
			boolean aiWon = isWinner(projected, "X");
			if (!aiWon && sendDoneMessage && !hasSent) {
				hasSent = true;
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand("pc Tic Tac Toe done");
			}
		}
	}

	private void onAIMove(String[] board) {
		currentBestMove = bestMove(board, "O");
		if (!predictNext) { predictedMove = -1; return; }
		boolean anyFilled = false;
		for (String s : board) if (s != null) { anyFilled = true; break; }
		if (!anyFilled) { predictedMove = -1; return; }

		String[] nextBoard = board.clone();
		if (currentBestMove != -1) nextBoard[currentBestMove] = "O";
		int predictX = bestMove(nextBoard, "X");
		String[] postBoard = nextBoard.clone();
		if (predictX != -1) postBoard[predictX] = "X";
		predictedMove = bestMove(postBoard, "O");
	}

	private static boolean isWinner(String[] board, String player) {
		for (int[] side : WINNING_SIDES) {
			if (player.equals(board[side[0]]) && player.equals(board[side[1]]) && player.equals(board[side[2]])) return true;
		}
		return false;
	}

	// Alpha-beta pruned minimax — ported verbatim from Devonian's own confirmed-working implementation.
	private static int minMax(String[] board, int depth, int alpha, int beta, boolean isPlayer) {
		if (isWinner(board, "X")) return 10 - depth;
		if (isWinner(board, "O")) return depth - 10;
		boolean full = true;
		for (String s : board) if (s == null) { full = false; break; }
		if (full) return 0;

		int a = alpha, b = beta;
		if (isPlayer) {
			int best = Integer.MIN_VALUE;
			for (int idx : BOARD_ORDER) {
				if (board[idx] != null) continue;
				String[] temp = board.clone();
				temp[idx] = "X";
				int score = minMax(temp, depth + 1, a, b, false);
				best = Math.max(best, score);
				a = Math.max(a, score);
				if (b <= a) break;
			}
			return best;
		}

		int best = Integer.MAX_VALUE;
		for (int idx : BOARD_ORDER) {
			if (board[idx] != null) continue;
			String[] temp = board.clone();
			temp[idx] = "O";
			int score = minMax(temp, depth + 1, a, b, true);
			best = Math.min(best, score);
			b = Math.min(b, score);
			if (b <= a) break;
		}
		return best;
	}

	private static int bestMove(String[] board, String player) {
		boolean maximizing = "X".equals(player);
		int bestScore = maximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;
		int best = -1;

		// A single-filled board hard-codes the reply toward middle/top-left rather than running the full
		// search — matches Devonian's own real-game-confirmed opening move exactly.
		int filled = 0;
		for (String s : board) if (s != null) filled++;
		if (filled == 1) return board[4] == null ? 4 : 0;

		for (int idx : BOARD_ORDER) {
			if (board[idx] != null) continue;
			String[] temp = board.clone();
			temp[idx] = player;
			int score = minMax(temp, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, !"X".equals(player));
			if (maximizing ? score > bestScore : score < bestScore) {
				bestScore = score;
				best = idx;
			}
		}
		return best;
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Tic Tac Toe solver render failed, skipping this frame", e);
		}
	}

	private void renderInner() {
		if (!inRoom) return;
		DungeonRoom room = WorldScan.getCurrentRoom();
		if (room == null || room.data == null || !"Tic Tac Toe".equals(room.data.getName())) return;

		if (predictNext && predictedMove != -1) drawBoardBox(room, predictedMove, predictedMoveColor);
		if (currentBestMove != -1) drawBoardBox(room, currentBestMove, bestMoveColor);
	}

	private void drawBoardBox(DungeonRoom room, int idx, int color) {
		int[] pos = BOARD_POS[idx];
		BlockPos xz = room.getRealCoords(new BlockPos(pos[0], 0, pos[2]));
		AABB box = new AABB(xz.getX(), pos[1], xz.getZ(), xz.getX() + 1.0, pos[1] + 1.0, xz.getZ() + 1.0);
		int fillAlpha = Math.round(31f / 100f * 255f);
		World3DRenderer.drawFilledBox(box, (fillAlpha << 24) | (color & 0xFFFFFF));
		World3DRenderer.drawWireBox(box, color, 2f);
	}

	public boolean isSendDoneMessage() { return sendDoneMessage; }
	public void setSendDoneMessage(boolean value) { sendDoneMessage = value; }
	public boolean isPredictNext() { return predictNext; }
	public void setPredictNext(boolean value) { predictNext = value; }
	public int getBestMoveColor() { return bestMoveColor; }
	public void setBestMoveColor(int value) { bestMoveColor = value; }
	public int getPredictedMoveColor() { return predictedMoveColor; }
	public void setPredictedMoveColor(int value) { predictedMoveColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("sendDoneMessage", sendDoneMessage);
		obj.addProperty("predictNext", predictNext);
		obj.addProperty("bestMoveColor", bestMoveColor);
		obj.addProperty("predictedMoveColor", predictedMoveColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("sendDoneMessage")) sendDoneMessage = obj.get("sendDoneMessage").getAsBoolean();
		if (obj.has("predictNext")) predictNext = obj.get("predictNext").getAsBoolean();
		if (obj.has("bestMoveColor")) bestMoveColor = obj.get("bestMoveColor").getAsInt();
		if (obj.has("predictedMoveColor")) predictedMoveColor = obj.get("predictedMoveColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Solves the F6/M6 Tic Tac Toe puzzle: highlights the most efficient button to press.";
	}
}
