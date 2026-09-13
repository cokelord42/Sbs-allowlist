package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat alert on pest spawns — ported from SkyHanni's PestSpawn.kt. Confirmed real message via user
 * screenshot: "TUCK! 4 <icon> Pest have spawned in Plot - 3! CLICK HERE to teleport to the plot!" —
 * Hypixel inserts an icon glyph (a private-use-area codepoint, not whitespace) between the amount and
 * "Pest", a flavor word varies ("TUCK!"/"GROSS!"/"YUCK!"), and the line has trailing "CLICK HERE..."
 * text after the exclamation. The previous patterns required `.matches()` against the *entire* line with
 * only whitespace between the number and "Pest", so they could never match any of that — every real pest
 * spawn silently fell through to the "unmatched" debug branch instead of alerting. Now uses `.find()`
 * against a narrow core pattern (tolerant of anything between the digit and "Pest", and anything after
 * the closing "!") instead of demanding an exact full-line match. Also confirms the plot id in chat is a
 * plain number, same as the tab-list's.
 */
public class PestSpawnAlertFeature extends Feature {
	// Case-insensitive now, and no longer anchored on the trailing "!" — per user report this was still
	// falling through to "unmatched" for real spawns. The flavor word ("TUCK!"/"YUCK!"/"EWW!"/etc.) in
	// front varies and was never actually part of these patterns to begin with, but matching case exactly
	// and requiring the line to end in a literal "!" (right after whatever trailing text/CLICK HERE
	// wording Hypixel appends, which isn't fully confirmed) were both real, avoidable ways to miss a match
	// — searching case-insensitively for just the core "<amount> Pest(s) have spawned in Plot" substring,
	// same idea as the offline/one-pest variants below, is far more robust to those unconfirmed details.
	// Real confirmed message (user chat sample): "§6§lYUCK! §24 §2 Pest §7have spawned in §aPlot §7- §be12§7!"
	// — Hypixel splices a color code between *nearly every* word here (even between "Plot" and "-", and
	// between "-" and the plot label itself), not just around the amount/icon-glyph as an earlier revision
	// of this comment assumed. Matched against the message with §-codes stripped FIRST (not just tolerated
	// with \D*?), and the plot group captures a real alphanumeric label (confirmed by user report: a real
	// plot is genuinely labeled "e12", not just "12" with a stray color-code letter in front of it) — a
	// plain \D*?-tolerant \d+-only group would have silently dropped the "e" from that exact label.
	private static final Pattern ONE_PEST_PLOT = Pattern.compile("A\\D*?Pest\\D*?has\\D*?appeared\\D*?in\\D*?Plot\\D*?-\\D*?(?<plot>[A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern ONE_PEST_BARN = Pattern.compile("A\\D*?Pest\\D*?has\\D*?appeared\\D*?in\\D*?(?<plot>The\\D*?Barn)", Pattern.CASE_INSENSITIVE);
	private static final Pattern MULTI_PEST_PLOT = Pattern.compile("(?<amount>\\d+)\\D*?Pests?\\D*?have\\D*?spawned\\D*?in\\D*?Plot\\D*?-\\D*?(?<plot>[A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern MULTI_PEST_BARN = Pattern.compile("(?<amount>\\d+)\\D*?Pests?\\D*?have\\D*?spawned\\D*?in\\D*?(?<plot>The\\D*?Barn)", Pattern.CASE_INSENSITIVE);
	private static final Pattern OFFLINE_PESTS = Pattern.compile("While\\D*?you\\D*?were\\D*?offline,?\\D*?Pests?\\D*?spawned\\D*?in\\D*?Plots\\D*?(?<plots>[\\d,\\s]+)", Pattern.CASE_INSENSITIVE);

	public PestSpawnAlertFeature() {
		super("pest_spawn_alert", "Pest Spawn Alert", FeatureCategory.FARMING, false);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !isEnabled()) return;
			onChatMessage(message.getString().replaceAll("§.", ""));
		});
	}

	@Override
	public String getSubcategory() {
		return "Pest farming";
	}

	private void onChatMessage(String message) {
		Matcher matcher = ONE_PEST_PLOT.matcher(message);
		if (matcher.find()) {
			notifySpawn(1, matcher.group("plot"));
			return;
		}
		matcher = ONE_PEST_BARN.matcher(message);
		if (matcher.find()) {
			notifySpawn(1, matcher.group("plot"));
			return;
		}
		matcher = MULTI_PEST_PLOT.matcher(message);
		if (matcher.find()) {
			notifySpawn(Integer.parseInt(matcher.group("amount")), matcher.group("plot"));
			return;
		}
		matcher = MULTI_PEST_BARN.matcher(message);
		if (matcher.find()) {
			notifySpawn(Integer.parseInt(matcher.group("amount")), matcher.group("plot"));
			return;
		}
		matcher = OFFLINE_PESTS.matcher(message);
		if (matcher.find()) {
			notifyOfflineSpawn(matcher.group("plots"));
		}
	}

	private void notifySpawn(int amount, String plotName) {
		String pestWord = amount == 1 ? "Pest" : "Pests";
		sendClientMessage("§e" + amount + " §a" + pestWord + " Spawned in §b" + plotName + "§a!");
		showTitle("§e" + amount + " §a" + pestWord + " spawned in §b" + plotName + "§a!");
	}

	private void notifyOfflineSpawn(String plots) {
		sendClientMessage("§ePests spawned while offline in Plots §b" + plots + "§e!");
		showTitle("§ePests spawned while offline in §b" + plots + "§e!");
	}

	private void sendClientMessage(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		// addClientSystemMessage, not sendSystemMessage — the latter re-fires
		// ClientReceiveMessageEvents.GAME, which this exact class listens on; echoing our own "X Pest(s)
		// Spawned..." notification back through sendSystemMessage caused it to re-enter onChatMessage,
		// fall through to the "unmatched pest-related chat line" debug branch (since the echo itself
		// contains "Pest"), and — combined with DebugLog's own former use of sendSystemMessage — recurse
		// forever. This was the real StackOverflowError that crashed the game while farming.
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal(text));
	}

	private void showTitle(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.hud.setTimes(5, 40, 10);
		mc.gui.hud.setTitle(Component.literal(text));
	}

	@Override
	public String getDescription() {
		return "Chat alert whenever a new pest spawns on one of your garden plots.";
	}
}
