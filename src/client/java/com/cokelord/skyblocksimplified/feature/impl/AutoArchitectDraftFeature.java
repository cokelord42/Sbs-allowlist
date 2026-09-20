package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatically sends the real {@code /gfs architect's first draft 1} command (Hypixel's own quick-fetch-
 * from-storage command, the same alias {@code ChatCommandsFeature}'s own {@code /gfs} shortcut already
 * uses) the instant the LOCAL player personally fails a Catacombs puzzle — per user request/memory of an
 * earlier ask, ported from Devonian's confirmed {@code AutoArchitectDraft.kt}.
 *
 * <p>Real, confirmed chat lines (both from Devonian's own source): a generic puzzle fail ("PUZZLE FAIL!
 * &lt;name&gt; lost Tic Tac Toe! Yikes!", "...killed a Blaze in the wrong order! Yikes!", etc. — the
 * trailing reason text varies per puzzle type, only the fixed "PUZZLE FAIL! &lt;name&gt;" prefix is
 * matched) and Quiz Solver's own distinct failure line ("[STATUE] Oruo the Omniscient: &lt;name&gt; chose
 * the wrong answer!..."), which isn't covered by the generic pattern since it never says "PUZZLE FAIL."
 * Both are gated on the captured name matching the LOCAL player specifically — a teammate failing their
 * own puzzle shouldn't fetch a draft for this player.
 */
public class AutoArchitectDraftFeature extends Feature {
	private static final Pattern PUZZLE_FAIL_PATTERN = Pattern.compile("^PUZZLE FAIL! (\\w{1,16}) .*$");
	private static final Pattern QUIZ_FAIL_PATTERN = Pattern.compile(
		"^\\[STATUE] Oruo the Omniscient: (\\w{1,16}) chose the wrong answer! I shall never forget this moment of misrememberance\\.$");
	// Real Hypixel command — same "gfs <item name> <remaining count>" syntax ChatCommandsFeature's own
	// /gfs shortcut already sends.
	private static final String DRAFT_COMMAND = "gfs architect's first draft 1";
	// Guards against a rare double-fire (e.g. the Quiz Solver's own failure line and a generic PUZZLE FAIL
	// line both matching within the same failure) sending the command twice in a row.
	private static final long COOLDOWN_MILLIS = 3000L;

	private long lastFiredAtMillis = 0L;

	private static boolean listenersRegistered = false;
	private static AutoArchitectDraftFeature instance;

	public AutoArchitectDraftFeature() {
		super("auto_architect_draft", "Auto Architect Draft", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString().replaceAll("§.", ""));
				return true;
			});
		}
	}

	private void onChatMessage(String text) {
		if (!DungeonState.isInDungeon()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.player.connection == null) return;
		String selfName = mc.player.getName().getString();

		String failedName = null;
		Matcher quizMatch = QUIZ_FAIL_PATTERN.matcher(text);
		if (quizMatch.matches()) {
			failedName = quizMatch.group(1);
		} else {
			Matcher puzzleMatch = PUZZLE_FAIL_PATTERN.matcher(text);
			if (puzzleMatch.matches()) failedName = puzzleMatch.group(1);
		}
		if (failedName == null || !failedName.equals(selfName)) return;

		long now = System.currentTimeMillis();
		if (now - lastFiredAtMillis < COOLDOWN_MILLIS) return;
		lastFiredAtMillis = now;
		mc.player.connection.sendCommand(DRAFT_COMMAND);
	}

	@Override
	public JsonElement savePersistedData() {
		return new JsonObject();
	}

	@Override
	public void loadPersistedData(JsonElement data) {}

	@Override
	public String getDescription() {
		return "Automatically sends the /gfs architect's first draft 1 command on a timer so you don't have to type it yourself.";
	}
}
