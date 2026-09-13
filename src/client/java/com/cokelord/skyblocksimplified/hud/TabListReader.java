package com.cokelord.skyblocksimplified.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the real tab-list (player list overlay, the screen shown while holding Tab) entry text, the
 * same way ScoreboardReader reads the sidebar — through the actual PlayerInfo objects the game already
 * builds from server packets. Hypixel injects its "Tab Widgets" (Jacob's Contest, Pests, Visitors, etc.)
 * as extra tab-list entries alongside real players, so this is the real data source those widgets read
 * from, not a guessed format — only the regex patterns matched against these lines (in each feature)
 * are ported from SkyHanni's confirmed-working ones.
 */
public final class TabListReader {
	private TabListReader() {}

	public static List<String> readLines() {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) return List.of();

		List<String> lines = new ArrayList<>();
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			Component display = info.getTabListDisplayName();
			String plain = display != null ? display.getString() : info.getProfile().name();
			if (!plain.isBlank()) lines.add(plain);
		}
		return lines;
	}
}
