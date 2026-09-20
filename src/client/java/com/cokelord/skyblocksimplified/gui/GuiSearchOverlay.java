package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per user request: "Not a module, but pressing CTRL + F in any gui should bring up a search, where you
 * can search lore and item names in guis. Searching for example Sharpness & Smite should search for both,
 * and searching for example Sharpness, Smite should search for either." Deliberately NOT a {@code Feature}
 * (no toggle, no FeatureRegistry entry) — always on for every {@link AbstractContainerScreen}, the same way
 * vanilla's own inventory search on other platforms just always works. State is plain static fields, same
 * "single screen open at a time" assumption every other one-off static-registration class in this codebase
 * already makes (see e.g. {@code SuperboomTntParticleFilter}).
 *
 * <p>Query syntax: comma splits into OR-groups; within a group, spaces or {@code &} split AND-terms — e.g.
 * {@code "Sharpness & Smite"} is one AND-group (both must appear), {@code "Sharpness, Smite"} is two
 * OR-groups (either may appear). A slot's item matches if any OR-group's terms all appear (case-insensitive,
 * substring) somewhere in that item's display name or lore.
 *
 * <p>Per user report ("The search bar should stay until the inventory is left, its really annoying searching
 * for stuff on the auction then switching pages or anything and it resets. Also allow the user to select text
 * and CTRL A and everything you can do to normal text usually"): the query used to be wiped on every single
 * {@code AFTER_INIT} (i.e. every new container screen, including switching Auction House pages/tabs, which
 * opens a fresh screen instance with a new container id each time) and on every {@code ScreenEvents.remove}.
 * It's now only cleared once the player has no container screen open at all (tracked below via a tick-based
 * open→closed transition), so it survives paging around within the same inventory session. The query also now
 * carries real cursor/selection state (click-to-position, click-drag select, double-click-free but full
 * keyboard editing: arrows, Ctrl+arrow word-jump, Home/End, Backspace/Delete, Ctrl+A/C/X/V) instead of only
 * ever appending/removing at the end — the same textCursor/textSelectionAnchor approach MainScreen's own text
 * fields already use, just scoped to this one always-on query.
 */
public final class GuiSearchOverlay {
	private GuiSearchOverlay() {}

	private static boolean registered = false;
	private static boolean wasContainerScreenOpen = false;
	private static boolean open = false;
	private static final StringBuilder query = new StringBuilder();
	private static int cursor = 0;
	private static int selectionAnchor = -1;
	private static boolean dragging = false;
	// Cached from the most recent render, used to hit-test mouse clicks/drags against the bar and to map an
	// X coordinate back to a character index — the bar's width/position depends on the current query+result
	// text so it can only be known after a render pass, same tradeoff GuiSearchOverlay already made for the
	// bar's own bounds before this round.
	private static int barX0, barY0, barX1, barY1, queryTextX;
	private static final int MAX_QUERY_LENGTH = 200;
	private static final Pattern COMMA_SPLIT = Pattern.compile(",");
	private static final Pattern AND_SPLIT = Pattern.compile("[&\\s]+");
	private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");
	// Per user request ("Make the CTRL F search bar support math, so i can do like 32x594 (or 32 x 594)
	// and get the answer written"): a query made up ONLY of digits/operators/parens/whitespace is treated
	// as a calculator expression instead of an item search — requires at least one operator so a plain
	// numeric search term (e.g. an item actually named "100") still behaves as a normal search.
	private static final Pattern MATH_QUERY = Pattern.compile("(?i)^[\\d.,\\s()+\\-*/xX]+$");
	private static final Pattern MATH_OPERATOR = Pattern.compile("[+\\-*/xX]");

	public static void register() {
		if (registered) return;
		registered = true;
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?>)) return;
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> handleKeyPress(event));
			ScreenKeyboardEvents.allowCharType(screen).register((s, event) -> handleCharTyped(event));
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> handleMouseClick(event));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dragX, dragY) -> handleMouseDrag(event));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> { dragging = false; return true; });
		});
		// Only resets the query when the player drops out of container screens entirely (a real screen
		// instanceof check every tick, not per-screen-instance events) — see the class doc comment above.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean containerOpen = client.gui.screen() instanceof AbstractContainerScreen<?>;
			if (wasContainerScreenOpen && !containerOpen) resetState();
			wasContainerScreenOpen = containerOpen;
		});
		PreTooltipRenderRegistry.addListener(GuiSearchOverlay::render);
	}

	private static void resetState() {
		open = false;
		query.setLength(0);
		cursor = 0;
		selectionAnchor = -1;
		dragging = false;
	}

	private static boolean hasSelection() {
		return selectionAnchor >= 0 && selectionAnchor != cursor;
	}

	private static int selStart() {
		return Math.max(0, Math.min(Math.min(cursor, selectionAnchor), query.length()));
	}

	private static int selEnd() {
		return Math.max(0, Math.min(Math.max(cursor, selectionAnchor), query.length()));
	}

	private static void deleteSelection() {
		query.delete(selStart(), selEnd());
		cursor = selStart();
		selectionAnchor = -1;
	}

	private static void insertText(String text) {
		if (text.isEmpty()) return;
		if (hasSelection()) deleteSelection();
		int room = MAX_QUERY_LENGTH - query.length();
		if (room <= 0) return;
		if (text.length() > room) text = text.substring(0, room);
		query.insert(cursor, text);
		cursor += text.length();
		selectionAnchor = -1;
	}

	private static void moveCursor(int delta, boolean extendSelection) {
		if (extendSelection) {
			if (selectionAnchor < 0) selectionAnchor = cursor;
		} else {
			selectionAnchor = -1;
		}
		cursor = Math.max(0, Math.min(query.length(), cursor + delta));
	}

	private static void setCursor(int pos, boolean extendSelection) {
		if (extendSelection) {
			if (selectionAnchor < 0) selectionAnchor = cursor;
		} else {
			selectionAnchor = -1;
		}
		cursor = Math.max(0, Math.min(query.length(), pos));
	}

	/** Standard Ctrl+Arrow word-jump: skips a run of spaces then a run of non-spaces (or the reverse
	 *  going backward) — same behavior as MainScreen's own shared text-field word-jump. */
	private static int findWordBoundary(int from, boolean forward) {
		String text = query.toString();
		int i = Math.max(0, Math.min(from, text.length()));
		if (forward) {
			while (i < text.length() && text.charAt(i) == ' ') i++;
			while (i < text.length() && text.charAt(i) != ' ') i++;
		} else {
			while (i > 0 && text.charAt(i - 1) == ' ') i--;
			while (i > 0 && text.charAt(i - 1) != ' ') i--;
		}
		return i;
	}

	private static void moveWord(boolean forward, boolean extendSelection) {
		setCursor(findWordBoundary(cursor, forward), extendSelection);
	}

	private static boolean isCaretBlinkOn() {
		return (System.currentTimeMillis() / 500L) % 2 == 0;
	}

	private static boolean handleKeyPress(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_F && event.hasControlDown()) {
			open = !open;
			if (!open) {
				query.setLength(0);
				cursor = 0;
				selectionAnchor = -1;
			} else {
				cursor = query.length();
				selectionAnchor = -1;
			}
			return false;
		}
		if (!open) return true;
		if (event.isEscape()) {
			open = false;
			query.setLength(0);
			cursor = 0;
			selectionAnchor = -1;
			return false;
		}
		if (event.isSelectAll()) {
			selectionAnchor = 0;
			cursor = query.length();
			return false;
		}
		if (event.isCopy() || event.isCut()) {
			if (hasSelection()) {
				Minecraft.getInstance().keyboardHandler.setClipboard(query.substring(selStart(), selEnd()));
				if (event.isCut()) deleteSelection();
			}
			return false;
		}
		if (event.isPaste()) {
			insertText(Minecraft.getInstance().keyboardHandler.getClipboard());
			return false;
		}
		boolean ctrl = event.hasControlDown();
		boolean shift = event.hasShiftDown();
		if (ctrl && event.key() == GLFW.GLFW_KEY_LEFT) { moveWord(false, shift); return false; }
		if (ctrl && event.key() == GLFW.GLFW_KEY_RIGHT) { moveWord(true, shift); return false; }
		if (event.key() == GLFW.GLFW_KEY_LEFT) { moveCursor(-1, shift); return false; }
		if (event.key() == GLFW.GLFW_KEY_RIGHT) { moveCursor(1, shift); return false; }
		if (event.key() == GLFW.GLFW_KEY_HOME) { setCursor(0, shift); return false; }
		if (event.key() == GLFW.GLFW_KEY_END) { setCursor(query.length(), shift); return false; }
		if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
			if (hasSelection()) deleteSelection();
			else if (cursor > 0) { query.deleteCharAt(cursor - 1); cursor--; }
			return false;
		}
		if (event.key() == GLFW.GLFW_KEY_DELETE) {
			if (hasSelection()) deleteSelection();
			else if (cursor < query.length()) query.deleteCharAt(cursor);
			return false;
		}
		// Swallow everything else while the search box is focused so typing can't fall through to the
		// underlying container screen's own keybinds (number-key hotbar swap, drop key, etc.).
		return false;
	}

	private static boolean handleCharTyped(CharacterEvent event) {
		if (!open) return true;
		insertText(event.codepointAsString());
		return false;
	}

	private static boolean mouseInBar(double mx, double my) {
		return mx >= barX0 && mx <= barX1 && my >= barY0 && my <= barY1;
	}

	private static int charIndexAtX(double mouseX) {
		String text = query.toString();
		double relX = mouseX - queryTextX;
		if (relX <= 0) return 0;
		net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
		for (int i = 0; i <= text.length(); i++) {
			if (font.width(text.substring(0, i)) >= relX) return i;
		}
		return text.length();
	}

	private static boolean handleMouseClick(MouseButtonEvent event) {
		if (!open || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !mouseInBar(event.x(), event.y())) return true;
		selectionAnchor = -1;
		cursor = charIndexAtX(event.x());
		dragging = true;
		return false;
	}

	private static boolean handleMouseDrag(MouseButtonEvent event) {
		if (!open || !dragging) return true;
		if (selectionAnchor < 0) selectionAnchor = cursor;
		cursor = charIndexAtX(event.x());
		return false;
	}

	private static void render(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
		if (!open && query.length() == 0) return;
		String rawQuery = query.toString().trim();
		boolean mathMode = isMathQuery(rawQuery);
		if (!rawQuery.isEmpty() && !mathMode) {
			AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
			int leftPos = accessor.skyblocksimplified$getLeftPos();
			int topPos = accessor.skyblocksimplified$getTopPos();
			for (Slot slot : screen.getMenu().slots) {
				ItemStack stack = slot.getItem();
				if (stack.isEmpty() || matchesQuery(haystack(stack), rawQuery)) continue;
				int x = leftPos + slot.x;
				int y = topPos + slot.y;
				graphics.fill(x, y, x + 16, y + 16, 0xB0000000);
			}
		}
		if (open) renderSearchBar(graphics, mathMode ? evaluateMath(rawQuery) : null);
	}

	private static boolean isMathQuery(String rawQuery) {
		return !rawQuery.isEmpty() && MATH_QUERY.matcher(rawQuery).matches() && MATH_OPERATOR.matcher(rawQuery).find();
	}

	private static void renderSearchBar(GuiGraphicsExtractor graphics, Double mathResult) {
		Minecraft mc = Minecraft.getInstance();
		String prefix = "Search: ";
		String queryText = query.toString();
		String suffix = mathResult != null ? "  = " + formatMathResult(mathResult) : "";
		int textWidth = mc.font.width(prefix + queryText + suffix);
		int barWidth = textWidth + 12;
		int barHeight = 16;
		int scaledWidth = graphics.guiWidth();
		int x1 = scaledWidth - 8;
		int x0 = x1 - barWidth;
		int y0 = 8;
		int y1 = y0 + barHeight;
		barX0 = x0;
		barY0 = y0;
		barX1 = x1;
		barY1 = y1;
		queryTextX = x0 + 6 + mc.font.width(prefix);
		RenderUtil.fillRounded(graphics, x0, y0, x1, y1, 3, 0xE0101010);
		RenderUtil.fillRoundedRing(graphics, x0, y0, x1, y1, 3, 1, 0xFF55FFAA);
		if (hasSelection()) {
			int selX0 = queryTextX + mc.font.width(queryText.substring(0, selStart()));
			int selX1 = queryTextX + mc.font.width(queryText.substring(0, selEnd()));
			graphics.fill(selX0, y0 + 2, selX1, y1 - 2, 0x668899FF);
		}
		graphics.text(mc.font, prefix + queryText + suffix, x0 + 6, y0 + 4, 0xFFFFFFFF);
		if (isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(cursor, queryText.length()));
			int caretX = queryTextX + mc.font.width(queryText.substring(0, idx));
			graphics.fill(caretX, y0 + 3, caretX + 1, y1 - 3, 0xFFFFFFFF);
		}

		String hint = "AND: space or & · OR: , · Esc to close";
		int hintWidth = mc.font.width(hint);
		graphics.text(mc.font, hint, x1 - hintWidth, y1 + 3, 0xFFAAAAAA);
	}

	// Per user request: a calculator inside the same search bar rather than a separate popup — "x"/"X" are
	// accepted as multiplication aliases (the user's own example, "32x594") alongside the standard "*".
	// A small hand-rolled recursive-descent parser rather than any scripting-engine "eval" — this only ever
	// needs to handle +-*/ and parens over plain numbers, and never runs untrusted code from anywhere but
	// the player's own keyboard input in their own client.
	private static Double evaluateMath(String expression) {
		try {
			MathParser parser = new MathParser(expression.replace(",", ""));
			double result = parser.parseExpression();
			parser.skipWhitespace();
			if (!parser.atEnd()) return null; // Trailing garbage (unbalanced parens, etc.) — don't show a wrong answer.
			return Double.isFinite(result) ? result : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String formatMathResult(double value) {
		if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
			return String.format(Locale.ROOT, "%,d", (long) value);
		}
		return String.format(Locale.ROOT, "%,.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	/** Minimal arithmetic expression parser over doubles (add, subtract, multiply, divide, parens) —
	 *  standard precedence (unary minus, then multiply/divide, then add/subtract), "x"/"X" accepted as an
	 *  alias for multiply. Throws on any malformed input; callers treat that as "not a valid expression"
	 *  rather than a crash. */
	private static final class MathParser {
		private final String s;
		private int pos = 0;

		MathParser(String s) { this.s = s; }

		boolean atEnd() { return pos >= s.length(); }

		void skipWhitespace() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

		double parseExpression() {
			double value = parseTerm();
			while (true) {
				skipWhitespace();
				if (pos < s.length() && s.charAt(pos) == '+') { pos++; value += parseTerm(); }
				else if (pos < s.length() && s.charAt(pos) == '-') { pos++; value -= parseTerm(); }
				else break;
			}
			return value;
		}

		private double parseTerm() {
			double value = parseFactor();
			while (true) {
				skipWhitespace();
				if (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == 'x' || s.charAt(pos) == 'X')) {
					pos++; value *= parseFactor();
				} else if (pos < s.length() && s.charAt(pos) == '/') {
					pos++; value /= parseFactor();
				} else break;
			}
			return value;
		}

		private double parseFactor() {
			skipWhitespace();
			if (pos >= s.length()) throw new IllegalStateException("Unexpected end of expression");
			char c = s.charAt(pos);
			if (c == '-') { pos++; return -parseFactor(); }
			if (c == '+') { pos++; return parseFactor(); }
			if (c == '(') {
				pos++;
				double value = parseExpression();
				skipWhitespace();
				if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalStateException("Missing closing paren");
				pos++;
				return value;
			}
			int start = pos;
			while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
			if (pos == start) throw new IllegalStateException("Expected a number at position " + pos);
			return Double.parseDouble(s.substring(start, pos));
		}
	}

	// Per user request ("searching Sharpness VII should match item lore enchant lines"): a lore line only
	// ever contains Hypixel's OWN baked-in enchant text when it chose to write one — the "Hide Vanilla
	// Enchants" toggle elsewhere in this codebase exists specifically because the OTHER source (vanilla's
	// real Enchantments/StoredEnchantments data components, rendered into the tooltip at hover time via
	// Enchantment#getFullname, exactly the same "Name Level" text a player reads) is a genuinely separate
	// thing from the item's stored Lore component GuiSearchOverlay used to read alone. An item whose enchant
	// tooltip comes solely from that live component (no redundant textual copy in Lore) was never
	// searchable by name+tier before — appended here so both possible sources are covered.
	private static String haystack(ItemStack stack) {
		StringBuilder sb = new StringBuilder();
		sb.append(stack.getHoverName().getString());
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) sb.append('\n').append(line.getString());
		}
		appendEnchantNames(sb, stack.get(DataComponents.ENCHANTMENTS));
		appendEnchantNames(sb, stack.get(DataComponents.STORED_ENCHANTMENTS));
		return COLOR_CODE.matcher(sb.toString()).replaceAll("").toLowerCase(Locale.ROOT);
	}

	private static void appendEnchantNames(StringBuilder sb, net.minecraft.world.item.enchantment.ItemEnchantments enchantments) {
		if (enchantments == null) return;
		for (var entry : enchantments.entrySet()) {
			sb.append('\n').append(net.minecraft.world.item.enchantment.Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
		}
	}

	private static boolean matchesQuery(String haystackLower, String query) {
		for (String group : COMMA_SPLIT.split(query)) {
			String trimmedGroup = group.trim();
			if (trimmedGroup.isEmpty()) continue;
			boolean allMatch = true;
			for (String term : AND_SPLIT.split(trimmedGroup)) {
				if (term.isEmpty()) continue;
				if (!haystackLower.contains(term.toLowerCase(Locale.ROOT))) {
					allMatch = false;
					break;
				}
			}
			if (allMatch) return true;
		}
		return false;
	}
}
