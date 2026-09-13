package com.cokelord.skyblocksimplified.item;

import java.util.Locale;

/** Converts enchant levels between roman numerals (how Hypixel always displays them in lore) and plain
 *  decimal ints (how the user may type them in the Custom Enchant Parsing GUI) — "VII" and "7" must be
 *  treated as the same level everywhere in that feature. */
public final class RomanNumeralUtil {
	private RomanNumeralUtil() {}

	private static final int[] VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
	private static final String[] SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

	public static String toRoman(int value) {
		if (value <= 0) return String.valueOf(value);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < VALUES.length; i++) {
			while (value >= VALUES[i]) {
				value -= VALUES[i];
				sb.append(SYMBOLS[i]);
			}
		}
		return sb.toString();
	}

	/** Parses either a roman numeral ("VII") or a plain decimal ("7") into an int. Returns -1 if the
	 *  text is neither (e.g. empty, garbage letters, or a malformed roman sequence). */
	public static int parseLevel(String text) {
		if (text == null) return -1;
		String trimmed = text.trim();
		if (trimmed.isEmpty()) return -1;
		boolean allDigits = true;
		for (int i = 0; i < trimmed.length(); i++) {
			if (!Character.isDigit(trimmed.charAt(i))) { allDigits = false; break; }
		}
		if (allDigits) {
			try {
				return Integer.parseInt(trimmed);
			} catch (NumberFormatException e) {
				return -1;
			}
		}
		return fromRoman(trimmed.toUpperCase(Locale.ROOT));
	}

	private static int fromRoman(String roman) {
		int total = 0;
		int i = 0;
		for (int v = 0; v < VALUES.length; v++) {
			while (roman.startsWith(SYMBOLS[v], i)) {
				total += VALUES[v];
				i += SYMBOLS[v].length();
			}
		}
		return (i == roman.length() && total > 0) ? total : -1;
	}
}
