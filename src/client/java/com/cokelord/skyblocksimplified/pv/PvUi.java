package com.cokelord.skyblocksimplified.pv;

import com.cokelord.skyblocksimplified.gui.MsdfFont;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Layout blocks for Player Viewer pages. A page is just a vertical list of blocks; each block reports its
 * height for a given width and draws itself — so every page shares the same spacing, colors and hover
 * tooltips, and the screen only has to stack and scroll them. Colors all go through {@link Theme} so the
 * viewer follows the mod menu's active panel theme and accent.
 */
public final class PvUi {
	private PvUi() {}

	public static final int ROW = 12;
	/** Slot pitch: an 18px slot box plus a 2px gap, so neighbouring slots never touch. */
	public static final int SLOT = 20;

	/** Per-frame drawing context: graphics, mouse, and the visible viewport (hover only counts inside it). */
	public static final class Ctx {
		public final GuiGraphicsExtractor g;
		public final Font font = Minecraft.getInstance().font;
		public final int mouseX, mouseY, viewTop, viewBottom;
		List<Component> tooltip;
		ItemStack tooltipItem;

		public Ctx(GuiGraphicsExtractor g, int mouseX, int mouseY, int viewTop, int viewBottom) {
			this.g = g;
			this.mouseX = mouseX;
			this.mouseY = mouseY;
			this.viewTop = viewTop;
			this.viewBottom = viewBottom;
		}

		public boolean hover(int x, int y, int w, int h) {
			return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= viewTop && mouseY < viewBottom;
		}

		public void tooltip(List<Component> lines) { tooltip = lines; }
		public void itemTooltip(ItemStack stack) { tooltipItem = stack; }

		public void flushTooltip() {
			if (tooltipItem != null && !tooltipItem.isEmpty()) g.setTooltipForNextFrame(font, tooltipItem, mouseX, mouseY);
			else if (tooltip != null && !tooltip.isEmpty()) g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
		}

		// ---- Intro animation (Overview, first open only). introClock = seconds since the content phase began
		// (menu landed AND data loaded); negative = no intro running, everything drawn normally.
		public float introClock = -1f;
		/** Screen y that counts as intro row 0 (defaults to the viewport top). */
		public int rowOrigin = Integer.MIN_VALUE;
		private float boxAlpha = 1f;
		private int boxDepth;

		private static final float TEXT_FADE = 0.2f, BOX_DURATION = 0.4f, ROW_STAGGER = 0.1f, SLIDE_PX = 100f, ROW_PX = 36f;

		public static float expoOut(float t) { return t >= 1f ? 1f : 1f - (float) Math.pow(2, -10 * t); }

		private float textAlpha() {
			return introClock < 0 ? 1f : Math.max(0f, Math.min(1f, introClock / TEXT_FADE));
		}

		/** Starts a "box" at screen row {@code y}: while the intro runs it slides in from the left and fades in,
		 *  each row {@link #ROW_STAGGER}s after the one above. Must be paired with {@link #endBox}. */
		public void beginBox(int y) {
			boxDepth++;
			if (introClock < 0 || boxDepth > 1) return;
			int row = Math.max(0, Math.round((y - (rowOrigin == Integer.MIN_VALUE ? viewTop : rowOrigin)) / ROW_PX));
			float p = Math.max(0f, Math.min(1f, (introClock - row * ROW_STAGGER) / BOX_DURATION));
			boxAlpha = p;
			g.pose().pushMatrix();
			g.pose().translate(-SLIDE_PX * (1f - expoOut(p)), 0);
		}

		public void endBox() {
			boxDepth--;
			if (introClock < 0 || boxDepth > 0) return;
			boxAlpha = 1f;
			g.pose().popMatrix();
		}

		/** Scales a color's alpha by the current box fade (and text fade for text). */
		int fade(int argb, boolean isText) {
			float a = boxAlpha * (isText ? textAlpha() : 1f);
			if (a >= 1f) return argb;
			return (Math.round(((argb >>> 24) & 0xFF) * a) << 24) | (argb & 0xFFFFFF);
		}

		boolean visible(int argb) { return ((argb >>> 24) & 0xFF) > 4; }

		/** Current box's fade progress (1 outside the intro). */
		public float boxAlpha() { return boxAlpha; }

		/** Item icons and entities can't be drawn translucent, so their fade-in is faked: after drawing them,
		 *  cover them with exactly what's behind them (the box's own {@code layerColor} at its current fade,
		 *  over the panel background), at opacity 1 - progress. The result is lerp(behind, icon, progress) —
		 *  a true fade on opaque themes, a close approximation on Transparent. */
		public void veil(int x0, int y0, int x1, int y1, int layerColor) {
			if (introClock < 0 || boxAlpha >= 1f) return;
			int panel = Theme.panelBackground();
			float la = ((layerColor >>> 24) & 0xFF) / 255f * boxAlpha;
			int r = Math.round(((panel >> 16) & 0xFF) * (1 - la) + ((layerColor >> 16) & 0xFF) * la);
			int gg = Math.round(((panel >> 8) & 0xFF) * (1 - la) + ((layerColor >> 8) & 0xFF) * la);
			int b = Math.round((panel & 0xFF) * (1 - la) + (layerColor & 0xFF) * la);
			int alpha = Math.round(255 * (1f - boxAlpha));
			if (alpha > 0) g.fill(x0, y0, x1, y1, (alpha << 24) | (r << 16) | (gg << 8) | b);
		}

		public void round(int x0, int y0, int x1, int y1, int radius, int color) {
			int c = fade(color, false);
			if (visible(c)) RenderUtil.fillRounded(g, x0, y0, x1, y1, radius, c);
		}

		public void fill(int x0, int y0, int x1, int y1, int color) {
			int c = fade(color, false);
			if (visible(c)) g.fill(x0, y0, x1, y1, c);
		}

		private static final int[] LEGACY_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

		private record Run(String text, int color) {}

		/** Splits a legacy "§"-coded string into colored runs; null if the menu font can't draw it. */
		private static List<Run> msdfRuns(String s, int baseColor) {
			if (!com.cokelord.skyblocksimplified.feature.impl.CustomMenuFontFeature.isMenuFontActive()) return null;
			List<Run> runs = new ArrayList<>();
			int color = baseColor;
			StringBuilder cur = new StringBuilder();
			for (int i = 0; i < s.length(); i++) {
				char ch = s.charAt(i);
				if (ch == '§' && i + 1 < s.length()) {
					int idx = "0123456789abcdef".indexOf(Character.toLowerCase(s.charAt(i + 1)));
					char code = Character.toLowerCase(s.charAt(i + 1));
					if (idx >= 0 || code == 'r' || "klmno".indexOf(code) >= 0) {
						if (cur.length() > 0) { runs.add(new Run(cur.toString(), color)); cur.setLength(0); }
						if (idx >= 0) color = (baseColor & 0xFF000000) | LEGACY_RGB[idx];
						else if (code == 'r') color = baseColor;
						i++;
						continue;
					}
				}
				cur.append(ch);
			}
			if (cur.length() > 0) runs.add(new Run(cur.toString(), color));
			for (Run r : runs) if (!MsdfFont.supportsAllGlyphs(r.text())) return null;
			return runs;
		}

		/** Same rule as MainScreen#text — the custom menu font when it's on — but color codes are drawn as
		 *  colored runs in that font too (they used to fall back to vanilla, which sat at a different height
		 *  than the menu-font label beside it). Vanilla only when the menu font can't draw a glyph. */
		public void text(String s, int x, int y, int color) {
			int c = fade(color, true);
			if (!visible(c)) return;
			List<Run> runs = msdfRuns(s, c);
			if (runs != null) {
				float dx = x;
				for (Run r : runs) {
					MsdfFont.draw(g, r.text(), dx, y - (MsdfFont.lineHeight() - 8) / 2f, r.color());
					dx += MsdfFont.width(r.text());
				}
			} else {
				g.text(font, s, x, y, c);
			}
		}

		public int width(String s) {
			List<Run> runs = msdfRuns(s, 0xFFFFFFFF);
			if (runs == null) return font.width(s);
			int w = 0;
			for (Run r : runs) w += MsdfFont.width(r.text());
			return w;
		}

		public void slot(ItemStack stack, int x, int y) {
			beginBox(y);
			try {
				round(x, y, x + 18, y + 18, 3, Theme.chrome(0xFF2A2A2A));
				if (stack == null || stack.isEmpty() || boxAlpha <= 0.02f) return;
				// Same fill the Item Rarity Background module paints in real inventories (respects its toggle and
				// shape); the color is cached per stack since these slots are static and redrawn every frame.
				com.cokelord.skyblocksimplified.feature.impl.ItemRarityBackgroundFeature.fillSlotColor(g, x + 1, y + 1,
					RARITY_CACHE.computeIfAbsent(stack, s -> java.util.Optional.ofNullable(
						com.cokelord.skyblocksimplified.feature.impl.ItemRarityBackgroundFeature.rarityColorPublic(s))).orElse(null));
				g.item(stack, x + 1, y + 1);
				g.itemDecorations(font, stack, x + 1, y + 1);
				veil(x + 1, y + 1, x + 17, y + 17, Theme.chrome(0xFF2A2A2A));
				if (hover(x, y, 18, 18)) {
					g.fill(x + 1, y + 1, x + 17, y + 17, 0x40FFFFFF);
					itemTooltip(stack);
				}
			} finally {
				endBox();
			}
		}
	}

	// ItemStack uses identity equality, so this is a per-instance cache; entries go away with the stacks.
	private static final java.util.Map<ItemStack, java.util.Optional<Integer>> RARITY_CACHE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	public static int textColor() { return Theme.text(0xFFDDDDDD); }
	public static int dimColor() { return Theme.text(0xFFAAAAAA); }
	public static int cardColor() { return Theme.chrome(0xFF1B1B1B); }

	public interface Block {
		int height(int width);
		void draw(Ctx c, int x, int y, int width);
	}

	// ---------------------------------------------------------------------------------------------------

	public record Header(String title) implements Block {
		public int height(int w) { return 16; }
		public void draw(Ctx c, int x, int y, int w) {
			c.beginBox(y);
			c.text(title, x, y + 3, Theme.accent() | 0xFF000000);
			c.fill(x, y + 13, x + w, y + 14, Theme.chrome(0xFF2A2A2A));
			c.endBox();
		}
	}

	public record Note(String text, int color) implements Block {
		public Note(String text) { this(text, 0); }
		public int height(int w) { return ROW; }
		public void draw(Ctx c, int x, int y, int w) {
			c.beginBox(y);
			c.text(text, x, y + 2, color == 0 ? dimColor() : color);
			c.endBox();
		}
	}

	public record Gap(int size) implements Block {
		public int height(int w) { return size; }
		public void draw(Ctx c, int x, int y, int w) {}
	}

	/** Label/value pair; {@code valueColor} 0 = default text color; legacy § codes in the value also work. */
	public record Stat(String label, String value, int valueColor, List<Component> tooltip) {
		public Stat(String label, String value) { this(label, value, 0, null); }
		public Stat(String label, String value, int color) { this(label, value, color, null); }
	}

	/** Label/value pairs laid out in as many columns as fit. */
	public static final class Stats implements Block {
		private final List<Stat> stats;
		private final int minColumn;

		public Stats(List<Stat> stats) { this(stats, 170); }
		public Stats(List<Stat> stats, int minColumn) { this.stats = stats; this.minColumn = minColumn; }

		private int cols(int w) { return Math.max(1, Math.min(stats.size(), w / minColumn)); }
		public int height(int w) { return (int) Math.ceil(stats.size() / (double) cols(w)) * ROW + 2; }

		public void draw(Ctx c, int x, int y, int w) {
			int cols = cols(w), colW = w / cols;
			for (int i = 0; i < stats.size(); i++) {
				Stat s = stats.get(i);
				int cx = x + (i % cols) * colW, cy = y + (i / cols) * ROW;
				String label = s.label() + ": ";
				c.beginBox(cy);
				c.text(label, cx, cy + 2, dimColor());
				c.text(s.value(), cx + c.width(label), cy + 2, s.valueColor() == 0 ? textColor() : s.valueColor());
				c.endBox();
				if (s.tooltip() != null && c.hover(cx, cy, colW - 4, ROW)) c.tooltip(s.tooltip());
			}
		}
	}

	/** Per user request: a maxed bar is a scrolling rainbow instead of the accent color. Drawn as 2px
	 *  columns whose hue shifts along the bar and over time. */
	static void chromaBar(Ctx c, int x0, int y0, int x1, int y1) {
		float time = (System.currentTimeMillis() % 4000L) / 4000f;
		for (int x = x0; x < x1; x += 2) {
			float hue = ((x - x0) / 120f + time) % 1f;
			int rgb = java.awt.Color.HSBtoRGB(hue, 0.75f, 1f) & 0xFFFFFF;
			int top = (x == x0 || x + 2 >= x1) ? y0 + 1 : y0;
			int bottom = (x == x0 || x + 2 >= x1) ? y1 - 1 : y1;
			c.fill(x, top, Math.min(x + 2, x1), bottom, 0xFF000000 | rgb);
		}
	}

	/** A level bar: icon, name + level, a progress track in the accent color, and a detail line. */
	public record Bar(ItemStack icon, String name, String level, double progress, String detail, List<Component> tooltip, boolean maxed) {
		/** Maxed when the detail line reads "MAX ..." — how every skill/slayer/collection bar marks it. */
		public Bar(ItemStack icon, String name, String level, double progress, String detail, List<Component> tooltip) {
			this(icon, name, level, progress, detail, tooltip, detail != null && detail.startsWith("MAX"));
		}
	}

	public static final class Bars implements Block {
		private final List<Bar> bars;
		private final int minColumn;

		public Bars(List<Bar> bars) { this(bars, 190); }
		public Bars(List<Bar> bars, int minColumn) { this.bars = bars; this.minColumn = minColumn; }

		private int cols(int w) { return Math.max(1, Math.min(Math.max(1, bars.size()), w / minColumn)); }
		private static final int CARD = 34, PITCH = 38;

		public int height(int w) { return (int) Math.ceil(bars.size() / (double) cols(w)) * PITCH; }

		public void draw(Ctx c, int x, int y, int w) {
			int cols = cols(w), colW = w / cols;
			for (int i = 0; i < bars.size(); i++) {
				Bar b = bars.get(i);
				int bx = x + (i % cols) * colW, by = y + (i / cols) * PITCH, bw = colW - 6;
				c.beginBox(by);
				c.round(bx, by, bx + bw, by + CARD, 4, cardColor());
				int tx = bx + 6;
				if (b.icon() != null && !b.icon().isEmpty()) {
					int iy = by + (CARD - 16) / 2;
					if (c.boxAlpha > 0.02f) {
						c.g.item(b.icon(), bx + 6, iy);
						c.veil(bx + 6, iy, bx + 22, iy + 16, cardColor());
					}
					tx = bx + 28;
				}
				c.text(b.name(), tx, by + 6, textColor());
				c.text(b.level(), bx + bw - 6 - c.width(b.level()), by + 6, Theme.accent() | 0xFF000000);
				int trackX0 = tx, trackX1 = bx + bw - 6, trackY = by + 18;
				c.round(trackX0, trackY, trackX1, trackY + 3, 1, Theme.chrome(0xFF2A2A2A));
				int fillW = (int) Math.round((trackX1 - trackX0) * Math.max(0, Math.min(1, b.progress())));
				if (b.maxed()) chromaBar(c, trackX0, trackY, trackX1, trackY + 3);
				else if (fillW > 0) c.round(trackX0, trackY, trackX0 + fillW, trackY + 3, 1, Theme.accent() | 0xFF000000);
				if (b.detail() != null) {
					String d = b.detail();
					int max = trackX1 - trackX0;
					while (d.length() > 3 && c.font.width(d) > max) d = d.substring(0, d.length() - 2);
					int dc = c.fade(dimColor(), true);
					if (c.visible(dc)) c.g.text(c.font, d, trackX0, trackY + 5, dc, false);
				}
				if (b.tooltip() != null && c.hover(bx, by, bw, CARD)) c.tooltip(b.tooltip());
				c.endBox();
			}
		}
	}

	/** Item grid with slot backgrounds and hover tooltips. */
	public static final class Items implements Block {
		private final List<ItemStack> items;
		private final int columns;

		/** {@code columns <= 0} = as many as fit in the available width. */
		public Items(List<ItemStack> items, int columns) { this.items = items; this.columns = columns; }

		private int cols(int w) { return columns > 0 ? columns : Math.max(1, (w + 2) / SLOT); }

		public int height(int w) { return (int) Math.ceil(Math.max(1, items.size()) / (double) cols(w)) * SLOT + 2; }

		public void draw(Ctx c, int x, int y, int w) {
			int cols = cols(w);
			for (int i = 0; i < items.size(); i++) c.slot(items.get(i), x + (i % cols) * SLOT, y + (i / cols) * SLOT);
		}
	}

	/** Titled item grids (ender chest pages, backpacks) laid side by side, wrapping to a new row when the next
	 *  one doesn't fit; each row is centered in the available width. */
	public record Panel(String title, List<ItemStack> items, int columns) {}

	public static final class Panels implements Block {
		private static final int GAP = 12, TITLE = 13;
		private final List<Panel> panels;

		public Panels(List<Panel> panels) { this.panels = panels; }

		private static int width(Panel p) { return p.columns() * SLOT - 2; }
		private static int height(Panel p) { return TITLE + (int) Math.ceil(Math.max(1, p.items().size()) / (double) p.columns()) * SLOT; }

		private List<List<Panel>> rows(int w) {
			List<List<Panel>> rows = new ArrayList<>();
			List<Panel> row = new ArrayList<>();
			int used = 0;
			for (Panel p : panels) {
				int pw = width(p);
				if (!row.isEmpty() && used + GAP + pw > w) {
					rows.add(row);
					row = new ArrayList<>();
					used = 0;
				}
				used += (row.isEmpty() ? 0 : GAP) + pw;
				row.add(p);
			}
			if (!row.isEmpty()) rows.add(row);
			return rows;
		}

		public int height(int w) {
			int h = 0;
			for (List<Panel> row : rows(w)) h += row.stream().mapToInt(Panels::height).max().orElse(0) + GAP;
			return h;
		}

		public void draw(Ctx c, int x, int y, int w) {
			int ry = y;
			for (List<Panel> row : rows(w)) {
				int rowW = row.stream().mapToInt(Panels::width).sum() + GAP * (row.size() - 1);
				int px = x + (w - rowW) / 2;
				for (Panel p : row) {
					String title = p.title();
					while (title.length() > 3 && c.font.width(title) > width(p)) title = title.substring(0, title.length() - 2);
					c.beginBox(ry);
					c.text(title, px, ry + 2, Theme.accent() | 0xFF000000);
					c.endBox();
					for (int i = 0; i < p.items().size(); i++) c.slot(p.items().get(i), px + (i % p.columns()) * SLOT, ry + TITLE + (i / p.columns()) * SLOT);
					px += width(p) + GAP;
				}
				ry += row.stream().mapToInt(Panels::height).max().orElse(0) + GAP;
			}
		}
	}

	/** Simple table: first column left-aligned, the rest right-aligned (numbers). */
	public static final class Table implements Block {
		private final String[] headers;
		private final List<String[]> rows;
		private final float firstColumnShare;

		public Table(String[] headers, List<String[]> rows) { this(headers, rows, 0.34f); }
		public Table(String[] headers, List<String[]> rows, float firstColumnShare) {
			this.headers = headers;
			this.rows = rows;
			this.firstColumnShare = firstColumnShare;
		}

		public int height(int w) { return (rows.size() + 1) * ROW + 4; }

		public void draw(Ctx c, int x, int y, int w) {
			int firstW = Math.round(w * firstColumnShare);
			int otherW = headers.length > 1 ? (w - firstW) / (headers.length - 1) : 0;
			c.beginBox(y);
			drawRow(c, headers, x, y, firstW, otherW, Theme.accent() | 0xFF000000);
			c.endBox();
			for (int r = 0; r < rows.size(); r++) {
				int ry = y + (r + 1) * ROW + 2;
				c.beginBox(ry);
				if (r % 2 == 0) c.fill(x, ry - 1, x + w, ry + ROW - 1, Theme.chrome(0x40202020));
				drawRow(c, rows.get(r), x, ry, firstW, otherW, textColor());
				c.endBox();
			}
		}

		private void drawRow(Ctx c, String[] cells, int x, int y, int firstW, int otherW, int color) {
			for (int i = 0; i < cells.length && i < headers.length; i++) {
				String s = cells[i] == null ? "" : cells[i];
				if (i == 0) c.text(s, x + 2, y + 1, color);
				else {
					int right = x + firstW + otherW * i;
					c.text(s, right - 2 - c.width(s), y + 1, color);
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Formatting

	public static String shortNum(double v) {
		double a = Math.abs(v);
		if (a >= 1e12) return String.format(Locale.ROOT, "%.2fT", v / 1e12);
		if (a >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
		if (a >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
		if (a >= 1e3) return String.format(Locale.ROOT, "%.1fk", v / 1e3);
		return String.format(Locale.ROOT, "%.0f", v);
	}

	public static String commas(double v) {
		return String.format(Locale.ROOT, "%,.0f", v);
	}

	public static String duration(long millis) {
		if (millis <= 0) return "-";
		long s = millis / 1000;
		if (s < 60) return s + "s";
		long m = s / 60;
		if (m < 60) return m + "m " + (s % 60) + "s";
		long h = m / 60;
		if (h < 48) return h + "h " + (m % 60) + "m";
		return (h / 24) + "d " + (h % 24) + "h";
	}

	/** "1:23.456"-style run time from milliseconds. */
	public static String runTime(double millis) {
		if (millis <= 0) return "-";
		long ms = (long) millis;
		return String.format(Locale.ROOT, "%d:%02d", ms / 60000, (ms / 1000) % 60);
	}

	/** SOME_ID_NAME -> Some Id Name. */
	public static String pretty(String id) {
		if (id == null) return "";
		String[] parts = id.toLowerCase(Locale.ROOT).replace(':', ' ').split("[_ ]+");
		StringBuilder sb = new StringBuilder();
		for (String p : parts) {
			if (p.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
		}
		return sb.toString();
	}

	public static List<Component> lines(String... lines) {
		List<Component> out = new ArrayList<>(lines.length);
		for (String l : lines) out.add(Component.literal(l));
		return out;
	}
}
