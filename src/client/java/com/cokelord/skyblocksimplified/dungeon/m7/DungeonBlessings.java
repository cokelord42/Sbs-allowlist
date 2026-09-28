package com.cokelord.skyblocksimplified.dungeon.m7;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.mixin.PlayerTabOverlayAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Current dungeon blessing levels, read from the tab-list footer ("Blessing of Power XIX" etc.) exactly like
 * Odin's DungeonListener (Blessing regexes over the footer string). Reset whenever you're not in a dungeon.
 */
public final class DungeonBlessings {
	private DungeonBlessings() {}

	public enum Blessing {
		POWER("Power", "Blessing of Power (X{0,3}(IX|IV|V?I{0,3}))"),
		LIFE("Life", "Blessing of Life (X{0,3}(IX|IV|V?I{0,3}))"),
		WISDOM("Wisdom", "Blessing of Wisdom (X{0,3}(IX|IV|V?I{0,3}))"),
		STONE("Stone", "Blessing of Stone (X{0,3}(IX|IV|V?I{0,3}))"),
		TIME("Time", "Blessing of Time (V)");

		public final String display;
		final Pattern pattern;
		int current = 0;

		Blessing(String display, String regex) { this.display = display; this.pattern = Pattern.compile(regex); }

		public int current() { return current; }
	}

	private static boolean registered = false;
	private static int tick = 0;

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++tick % 10 != 0) return;
			if (!DungeonState.isInDungeon()) {
				for (Blessing b : Blessing.values()) b.current = 0;
				return;
			}
			Component footer = ((PlayerTabOverlayAccessor) Minecraft.getInstance().gui.hud.getTabList()).skyblocksimplified$getFooter();
			if (footer == null) return;
			String text = footer.getString().replaceAll("§.", "");
			for (Blessing b : Blessing.values()) {
				Matcher m = b.pattern.matcher(text);
				if (m.find()) b.current = romanToInt(m.group(1));
			}
		});
	}

	static int romanToInt(String roman) {
		int total = 0, prev = 0;
		for (int i = roman.length() - 1; i >= 0; i--) {
			int v = switch (roman.charAt(i)) { case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; default -> 0; };
			total += v < prev ? -v : v;
			prev = Math.max(prev, v);
		}
		return total;
	}
}
