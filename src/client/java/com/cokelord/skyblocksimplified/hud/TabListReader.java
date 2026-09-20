package com.cokelord.skyblocksimplified.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the real tab-list (player list overlay, the screen shown while holding Tab) entry text, the
 * same way ScoreboardReader reads the sidebar — through the actual PlayerInfo objects the game already
 * builds from server packets. Hypixel injects its "Tab Widgets" (Jacob's Contest, Pests, Visitors, Party,
 * etc.) as extra tab-list entries alongside real players, so this is the real data source those widgets
 * read from, not a guessed format.
 *
 * <p>Real gap found (per user report — {@code PartyApi}'s "Party: (N/5)" header scan still never finding
 * anything, even with Hypixel's own Party tab-list widget confirmed enabled): this used to read each
 * entry's text off {@code PlayerInfo.getTabListDisplayName()} directly, falling back to the raw profile
 * name. That skips a real step vanilla's own tab list renderer always does — {@code
 * PlayerTabOverlay.getNameForDisplay(PlayerInfo)} (confirmed via javap; not just {@code
 * getTabListDisplayName()}) additionally wraps the name in that entry's SCOREBOARD TEAM prefix/suffix via
 * {@code PlayerTeam.formatNameForTeam}. Server-side tab-list "widgets" are commonly implemented by giving a
 * fake player entry an empty/short name and carrying the actual visible text in its team's prefix+suffix —
 * a real, long-standing Bukkit/Spigot convention this codebase had never accounted for at all, so any
 * widget built that way (quite possibly including Hypixel's Party widget) was structurally invisible to
 * this reader regardless of any sorting/filtering logic. Ported the exact same read (and sort — {@code
 * PlayerTabOverlay}'s own real comparator: non-spectators first, then team name, then player name) SkyHanni
 * independently confirmed for the same real Hypixel tab list, rather than re-deriving it from a from-scratch
 * vanilla disassembly a second time.
 *
 * <p>Real vanilla tab list layout is a 4-column x 20-row grid (80 slots), and — again matching SkyHanni's
 * own confirmed handling — the sorted list has its LAST entry dropped when there are fewer than 80 total
 * (a real trailing padding slot vanilla's own layout leaves, not a real tab-list row) and is capped at 80
 * otherwise. Blank/empty lines are deliberately NOT filtered out here — a consumer treating "the next N
 * lines after a header" as meaningful (this class's own callers) needs the real row indices to line up
 * exactly with what's rendered, and dropping blank rows would shift everything after one out of alignment.
 */
public final class TabListReader {
	private TabListReader() {}

	private static final Comparator<PlayerInfo> PLAYER_COMPARATOR = Comparator
		.comparing((PlayerInfo info) -> info.getGameMode() == GameType.SPECTATOR)
		.thenComparing(info -> info.getTeam() != null ? info.getTeam().getName() : "")
		.thenComparing(info -> info.getProfile().name());

	public static List<String> readLines() {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) return List.of();
		PlayerTabOverlay tabOverlay = mc.gui.hud.getTabList();

		List<PlayerInfo> sorted = new ArrayList<>(connection.getOnlinePlayers());
		sorted.sort(PLAYER_COMPARATOR);
		List<PlayerInfo> trimmed = sorted.size() < 80
			? sorted.subList(0, Math.max(0, sorted.size() - 1))
			: sorted.subList(0, 80);

		List<String> lines = new ArrayList<>();
		for (PlayerInfo info : trimmed) {
			Component display = tabOverlay.getNameForDisplay(info);
			lines.add(display != null ? display.getString() : info.getProfile().name());
		}
		return lines;
	}
}
