package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.debug.DebugLog;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Solves the Garden Greenhouse DNA Analyser color-matching puzzle — ported from SkyHanni's
 * DnaAnalyzerSolver.kt, keeping its exact column-permutation DP solver (minimum swaps per column,
 * connectivity between adjacent columns) and its confirmed item-name color detection ("§cDNA" /
 * "§eDNA" / "§9DNA" / "§aDNA" prefixes). Highlights the two slots to swap next directly in the DNA
 * Analyser GUI (drawn via PreItemRenderRegistry, which already fires inside the screen's own translated
 * pose so slot.x/y are usable directly) — this isn't a HUD/GUI
 * overlay feature, it just marks slots in Hypixel's own inventory. The two end columns are treated as
 * fixed border slots (not part of the puzzle), matching the more common Hypixel layout; SkyHanni reads
 * this as a toggle from its own community repo data which isn't available here.
 */
public class DnaAnalyzerSolverFeature extends Feature {
	private static final int ROWS = 4;
	private static final int COLUMNS = 9;
	private static final int UNREACHABLE = 1_000;
	private static final boolean ALLOW_ENDS = false;
	private static final int SLOT_SIZE = 16;
	// Halved from 0x80 — "really bright" per user report.
	private static final int DEFAULT_COLOR = 0x5000FF00;
	private static final List<List<Integer>> ROW_PERMUTATIONS = generateRowPermutations();

	private enum Colors { RED, GREEN, BLUE, YELLOW }

	private record SlotPos(int column, int row) {
		int menuSlotIndex() {
			return (row + 1) * 9 + column;
		}
	}

	private record Swap(SlotPos a, SlotPos b) {}

	private boolean boardValid = false;
	private List<Swap> currentSwaps = List.of();
	private int color = DEFAULT_COLOR;

	public DnaAnalyzerSolverFeature() {
		super("dna_analyser_solver", "DNA Analyser Solver", FeatureCategory.FARMING, false);
		// Drawn from PreItemRenderRegistry (fires before item icons are drawn, so the highlight sits UNDER
		// the tile's own item icon instead of painted over it — per user request, "change all item
		// highlights... to render BEHIND the item they are highlighting") instead of
		// ScreenEvents.afterExtract (drew on top of everything). Board solving moved in alongside the render
		// call itself since both need to run once per frame regardless; a separate AFTER_INIT/afterExtract
		// pair isn't needed anymore since the title check below already gates this to the DNA Analyser
		// screen specifically.
		com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled()) return;
			String title = screen.getTitle().getString();
			if (!title.endsWith(" DNA")) return;
			updateBoard(screen.getMenu());
			renderHighlights(graphics, screen, mouseX, mouseY);
		});
	}

	@Override
	public String getSubcategory() {
		return "Greenhouse";
	}

	public int getColor() {
		return color;
	}

	public void setColor(int color) {
		this.color = color;
	}

	@Override
	public Integer getPersistedColor() {
		return color;
	}

	@Override
	public void loadPersistedColor(int argb) {
		this.color = argb;
	}

	private void updateBoard(AbstractContainerMenu menu) {
		List<List<Colors>> board = new ArrayList<>();
		for (int col = 0; col < COLUMNS; col++) {
			board.add(new ArrayList<>(List.of(Colors.GREEN, Colors.GREEN, Colors.GREEN, Colors.GREEN)));
		}

		for (int i = 9; i < 45; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			int row = (i / 9) - 1;
			int column = i % 9;
			Colors itemColor = colorOf(stack);
			if (itemColor == null) {
				boardValid = false;
				currentSwaps = List.of();
				return;
			}
			board.get(column).set(row, itemColor);
		}

		for (List<Colors> column : board) {
			if (new HashSet<>(column).size() != ROWS) {
				boardValid = false;
				currentSwaps = List.of();
				return;
			}
		}

		boardValid = true;
		currentSwaps = solve(board);
	}

	private Colors colorOf(ItemStack stack) {
		if (stack.isEmpty()) return null;
		net.minecraft.network.chat.Component name = stack.getHoverName();
		// The color IS the signal here (every slot's item is just named "DNA") — Component#getString()
		// strips all formatting, so matching against a literal "§cDNA" prefix (as ported from SkyHanni,
		// which reads chat/lore through a source that keeps legacy codes inline) could never match here
		// and every slot read as an unknown color, which is exactly why the board always came back
		// invalid. Component#visit() resolves each run's real (possibly inherited) TextColor instead.
		Integer rgb = firstColorRgb(name);
		if (rgb == null) return null;
		return switch (rgb) {
			case 0xFF5555 -> Colors.RED;
			case 0xFFFF55 -> Colors.YELLOW;
			case 0x5555FF -> Colors.BLUE;
			case 0x55FF55 -> Colors.GREEN;
			default -> null;
		};
	}

	private static Integer firstColorRgb(net.minecraft.network.chat.Component component) {
		return component.visit((style, text) -> {
			if (text.isEmpty() || style.getColor() == null) return java.util.Optional.<Integer>empty();
			return java.util.Optional.of(style.getColor().getValue());
		}, net.minecraft.network.chat.Style.EMPTY).orElse(null);
	}

	private void renderHighlights(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
		if (!boardValid || currentSwaps.isEmpty()) return;
		Swap next = currentSwaps.get(currentSwaps.size() - 1);
		AbstractContainerMenu menu = screen.getMenu();

		highlightSlot(graphics, menu, next.a());
		highlightSlot(graphics, menu, next.b());
	}

	// PreItemRenderRegistry fires from inside AbstractContainerScreen's own already-translated
	// pose().translate(leftPos, topPos) block — slot.x/slot.y are already the right coordinates, no manual
	// leftPos/topPos offset needed (see that registry's own doc comment).
	private void highlightSlot(GuiGraphicsExtractor graphics, AbstractContainerMenu menu, SlotPos slotPos) {
		Slot slot = menu.getSlot(slotPos.menuSlotIndex());
		int x = slot.x;
		int y = slot.y;
		RenderUtil.fillRounded(graphics, x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, 2, color);
	}

	@SuppressWarnings("unchecked")
	private static List<Swap> solve(List<List<Colors>> board) {
		int firstMutable = ALLOW_ENDS ? 0 : 1;
		int lastMutable = ALLOW_ENDS ? COLUMNS - 1 : COLUMNS - 2;
		int mutableCount = lastMutable - firstMutable + 1;
		int permCount = ROW_PERMUTATIONS.size();

		int[][] dp = new int[mutableCount][permCount];
		int[][] parent = new int[mutableCount][permCount];
		for (int[] row : dp) Arrays.fill(row, UNREACHABLE);
		for (int[] row : parent) Arrays.fill(row, -1);

		int[][] cost = new int[mutableCount][permCount];
		List[][] swapMap = new List[mutableCount][permCount];

		for (int i = 0; i < mutableCount; i++) {
			List<Colors> column = board.get(firstMutable + i);
			for (int p = 0; p < permCount; p++) {
				List<Colors> perm = permuteColumn(column, ROW_PERMUTATIONS.get(p));
				ColumnSwapResult result = getMinimumColumnSwaps(column, perm);
				cost[i][p] = result.cost();
				swapMap[i][p] = result.swaps();
			}
		}

		for (int p = 0; p < permCount; p++) {
			List<Colors> perm = permuteColumn(board.get(firstMutable), ROW_PERMUTATIONS.get(p));
			if (!ALLOW_ENDS && !canColumnsConnect(board.get(0), perm)) continue;
			dp[0][p] = cost[0][p];
		}

		for (int i = 1; i < mutableCount; i++) {
			for (int p = 0; p < permCount; p++) {
				List<Colors> cur = permuteColumn(board.get(firstMutable + i), ROW_PERMUTATIONS.get(p));
				for (int q = 0; q < permCount; q++) {
					if (dp[i - 1][q] == UNREACHABLE) continue;
					List<Colors> prev = permuteColumn(board.get(firstMutable + i - 1), ROW_PERMUTATIONS.get(q));
					if (canColumnsConnect(prev, cur)) {
						int newCost = dp[i - 1][q] + cost[i][p];
						if (newCost < dp[i][p]) {
							dp[i][p] = newCost;
							parent[i][p] = q;
						}
					}
				}
			}
		}

		int best = UNREACHABLE;
		int last = -1;
		for (int p = 0; p < permCount; p++) {
			List<Colors> perm = permuteColumn(board.get(lastMutable), ROW_PERMUTATIONS.get(p));
			if (!ALLOW_ENDS && !canColumnsConnect(perm, board.get(COLUMNS - 1))) continue;
			if (dp[mutableCount - 1][p] < best) {
				best = dp[mutableCount - 1][p];
				last = p;
			}
		}

		if (last == -1) return List.of();

		List<Swap> result = new ArrayList<>();
		int i = mutableCount - 1;
		int cur = last;
		while (i >= 0) {
			int colIndex = firstMutable + i;
			List<int[]> swaps = swapMap[i][cur];
			for (int[] swap : swaps) {
				result.add(new Swap(new SlotPos(colIndex, swap[0]), new SlotPos(colIndex, swap[1])));
			}
			cur = parent[i][cur];
			i--;
		}
		return result;
	}

	private static List<Colors> permuteColumn(List<Colors> column, List<Integer> permutation) {
		List<Colors> result = new ArrayList<>(ROWS);
		for (int idx : permutation) result.add(column.get(idx));
		return result;
	}

	private static boolean canColumnsConnect(List<Colors> a, List<Colors> b) {
		for (int r = 0; r < ROWS; r++) {
			Colors v = a.get(r);
			if (b.get(r) == v) continue;
			if (r > 0 && b.get(r - 1) == v) continue;
			if (r < ROWS - 1 && b.get(r + 1) == v) continue;
			return false;
		}
		return true;
	}

	private record ColumnSwapResult(int cost, List<int[]> swaps) {}

	private static ColumnSwapResult getMinimumColumnSwaps(List<Colors> from, List<Colors> to) {
		int[] pos = new int[ROWS];
		for (int i = 0; i < ROWS; i++) {
			pos[from.indexOf(to.get(i))] = i;
		}

		boolean[] visited = new boolean[ROWS];
		List<int[]> swaps = new ArrayList<>();
		int cost = 0;

		for (int i = 0; i < ROWS; i++) {
			if (visited[i]) continue;
			int cur = i;
			List<Integer> cycle = new ArrayList<>();
			while (!visited[cur]) {
				visited[cur] = true;
				cycle.add(cur);
				cur = pos[cur];
			}
			if (cycle.size() > 1) {
				cost += cycle.size() - 1;
				for (int k = 1; k < cycle.size(); k++) {
					swaps.add(new int[]{cycle.get(0), cycle.get(k)});
				}
			}
		}
		return new ColumnSwapResult(cost, swaps);
	}

	private static List<List<Integer>> generateRowPermutations() {
		List<List<Integer>> perms = new ArrayList<>();
		generateRowPermutations(perms, new int[]{0, 1, 2, 3}, 0);
		return perms;
	}

	private static void generateRowPermutations(List<List<Integer>> perms, int[] a, int l) {
		if (l == ROWS) {
			perms.add(List.of(a[0], a[1], a[2], a[3]));
			return;
		}
		for (int i = l; i < ROWS; i++) {
			int tmp = a[l];
			a[l] = a[i];
			a[i] = tmp;
			generateRowPermutations(perms, a, l + 1);
			tmp = a[l];
			a[l] = a[i];
			a[i] = tmp;
		}
	}

	@Override
	public String getDescription() {
		return "Solves the Garden Greenhouse DNA Analyser color-matching puzzle and highlights the correct clicks.";
	}
}
