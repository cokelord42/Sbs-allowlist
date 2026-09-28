package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.util.ChatText;
import com.cokelord.skyblocksimplified.util.TpsMonitor;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per user request: after a Dungeon or Kuudra run completes, prints "N.Ns lost to lag (N ticks)".
 *
 * <p>Measured against the server's own clock: Hypixel sends a time-sync packet (ClientboundSetTimePacket,
 * already observed by {@link TpsMonitor}) carrying the server's game time, which advances exactly one per
 * processed server tick. Sampling (game time, arrival wall-clock) at run start and run end, the run took
 * {@code wallMs} of real time but only {@code ticks} server ticks; every tick the server failed to process
 * in that real time is lag: {@code lostMs = wallMs - ticks * 50}. Both endpoints are packet-arrival
 * samples, so network jitter only shifts them by a few ms.
 *
 * <p>Start/end lines reuse this codebase's confirmed patterns: Mort's opening line (SplitsFeature), Elle's
 * "fish up Kuudra" line (SplitsFeature's Kuudra splits), and "> EXTRA STATS <" / Elle's closing line
 * (ChatCommandsFeature's END_RUN_PATTERN).
 */
public class LagCounterFeature extends Feature {
	private static final Pattern DUNGEON_START = Pattern.compile("^\\[NPC] Mort: Here, I found this map when I first entered the dungeon\\.$|^\\[NPC] Mort: Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!$");
	private static final Pattern KUUDRA_START = Pattern.compile("^\\[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!$");
	private static final Pattern RUN_END = Pattern.compile("^ *> EXTRA STATS <$|^\\[NPC] Elle: Good job everyone\\. A hard fought battle come to an end\\. Let's get out of here before we run into any more trouble!$");

	private long startGameTime = -1;
	private long startWallMs = -1;
	private Object startLevel;

	public LagCounterFeature() {
		super("lag_counter", "Lag Counter", FeatureCategory.COMBAT, false);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !isEnabled()) return;
			onChat(ChatText.strip(message.getString()).trim());
		});
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	private void onChat(String line) {
		if (DUNGEON_START.matcher(line).matches() || KUUDRA_START.matcher(line).matches()) {
			startGameTime = TpsMonitor.lastGameTime();
			startWallMs = TpsMonitor.lastPacketAtMs();
			startLevel = Minecraft.getInstance().level;
			return;
		}
		if (startGameTime < 0 || !RUN_END.matcher(line).matches()) return;
		long endGameTime = TpsMonitor.lastGameTime();
		long endWallMs = TpsMonitor.lastPacketAtMs();
		boolean sameWorld = startLevel == Minecraft.getInstance().level;
		long ticks = endGameTime - startGameTime;
		long wallMs = endWallMs - startWallMs;
		startGameTime = -1;
		startLevel = null;
		if (!sameWorld || ticks <= 0 || wallMs <= 0) return;
		long lostMs = Math.max(0, wallMs - ticks * 50);
		long lostTicks = Math.round(lostMs / 50.0);
		ChatText.clientMessage(String.format(Locale.ROOT, "§b[SBS] §c%.1fs §7lost to lag §8(§c%d ticks§8)", lostMs / 1000.0, lostTicks));
	}

	@Override
	public String getDescription() {
		return "After a Dungeon or Kuudra run completes, shows how much time the server lost to lag during the run.";
	}
}
