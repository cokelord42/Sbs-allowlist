package com.cokelord.skyblocksimplified.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the vanilla sidebar scoreboard the same way any other client mod would — through the real
 * Scoreboard/Objective/PlayerScoreEntry/PlayerTeam objects the game already builds from server packets,
 * not by re-parsing raw text. Modern Minecraft added a per-entry display Component (PlayerScoreEntry.display()),
 * so servers mostly don't need the old team-prefix/suffix trick anymore, but we still fall back to it
 * for entries that don't set one.
 */
public final class ScoreboardReader {
	private ScoreboardReader() {}

	/** A static catalog of well-known real Hypixel Skyblock scoreboard line labels — sourced from
	 *  SkyHanni's own ScoreboardPattern regex set (Purse/Bank/Bits/etc. confirmed against its real
	 *  element configLine samples) — offered in the add-line menu regardless of what's currently live
	 *  on the player's own scoreboard. Without this, a line like "Motes:" or "Carnival Tokens:" could
	 *  only ever be added while standing in Crystal Hollows or during the Carnival event, since the
	 *  add-line menu otherwise only lists whatever the live sidebar happens to show right now. */
	public static final List<String> KNOWN_LABELS = List.of(
		"Purse:", "Bank:", "Bits:", "Motes:", "Copper:", "Gems:", "Sowdust:", "North Stars:",
		"Pelts:", "Fossil Dust:", "Dragon Essence:", "Whispers:", "Wave:", "Tokens:",
		"Cold:", "Heat:", "Soulflow:",
		"Time Elapsed:", "Time Left:", "Instance Shutdown In:", "Submerges In:",
		"Auto-closing in:", "Starting in:", "Cleared:", "Your Damage:", "Nearby Players:",
		"Carnival Tokens:", "Fruits:", "Score:", "Catch Streak:", "Accuracy:", "Kills:",
		"Clues:", "Hay Eaten:", "Eaten:", "Big damage in:",
		// Multi-line composite stats (SkyHanniScoreboardStats.MULTI_LINE_LABELS) — each expands to several
		// rendered lines (party roster, slayer quest block, powder totals, mayor + perks, SB level/xp).
		"Party:", "Slayer Quest:", "Powder:", "Mayor:", "SB Level:",
		// Passthrough event labels (SkyHanniScoreboardStats.PASSTHROUGH_EVENT_LABELS) — synthetic display
		// names for events whose real sidebar line has no "Label:" prefix at all.
		"Carnival Event:", "Anniversary Event:", "Traveling Zoo Event:", "New Year Event:", "Spooky Festival Event:",
		"Redstone Event:", "Visiting:", "Election Votes:"
	);

	public static List<String> readCurrentSidebarLines() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return List.of();
		Scoreboard scoreboard = mc.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return List.of();

		List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(objective));
		entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

		List<String> lines = new ArrayList<>();
		for (PlayerScoreEntry entry : entries) {
			if (entry.isHidden()) continue;
			Component text = entry.display();
			if (text == null) {
				PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
				text = team != null ? team.getFormattedName(entry.ownerName()) : entry.ownerName();
			}
			String plain = text.getString();
			if (!plain.isBlank()) lines.add(plain);
		}
		return lines;
	}

	/** Everything before the first digit, trimmed — used as a stable "label" to re-match a line that
	 *  keeps changing its numeric value (e.g. "Purse:" out of "Purse: 1,234"). If the line has no digit
	 *  at all, the whole line is the label (it'll just match itself, same as a frozen custom line).
	 *
	 *  Strips §-color-codes first: real Hypixel sidebar entries are near-always colored ("§7Purse: §6900"),
	 *  and a raw legacy code's own digit (the "6" in "§6") reads as a digit just as much as the real value
	 *  does, so scanning the unstripped string truncated almost every label to one or two characters right
	 *  at its first color code — this was the actual "auto-detected lines never show, only my custom ones
	 *  do" bug, since a garbage label like "§" can never usefully re-match anything. */
	public static String extractLabel(String line) {
		String plain = line.replaceAll("§.", "");
		for (int i = 0; i < plain.length(); i++) {
			if (Character.isDigit(plain.charAt(i))) {
				return plain.substring(0, i).trim();
			}
		}
		return plain.trim();
	}

	/** Finds the current live text for a previously-picked label, or null if nothing on the current
	 *  scoreboard starts with it (e.g. wrong island/area right now). Labels are always plain (see
	 *  extractLabel), so the live line's own color codes are stripped before comparing — otherwise a
	 *  colored live line ("§7Purse: §6900") would never startsWith a plain label ("Purse:") at all. */
	public static String findCurrentLineForLabel(String label) {
		if (label.isEmpty()) return null;
		for (String line : readCurrentSidebarLines()) {
			if (line.replaceAll("§.", "").startsWith(label)) return line;
		}
		return null;
	}
}
