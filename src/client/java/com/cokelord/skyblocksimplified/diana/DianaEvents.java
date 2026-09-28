package com.cokelord.skyblocksimplified.diana;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks which Diana waypoint the player last dug at (left click on a block, or right click with a spade) and
 * turns the "(x/y)" burrow chain message into a dug event. Ported from SBO's DianaEvents.kt.
 */
public final class DianaEvents {
	private static final Pattern BURROW_DUG = Pattern.compile("^§eYou (.*?) Griffin [Bb]urrow(.*?) §7\\((.*?)/(.*?)\\)$", Pattern.DOTALL);

	static BlockPos lastWaypointClicked;
	private static long lastWaypointClickedAt;
	private static BlockPos lastBlockClicked;
	private static long lastBlockClickedAt;

	/** Where the player most recently dug: the last clicked waypoint, or a plain clicked block if that was
	 *  more recent (a burrow without a waypoint — e.g. an arrow guess that was a block off). */
	static BlockPos lastDigPos() {
		if (lastBlockClicked != null && (lastWaypointClicked == null || lastBlockClickedAt > lastWaypointClickedAt)) return lastBlockClicked;
		return lastWaypointClicked;
	}
	private static boolean registered;

	private DianaEvents() {}

	static synchronized void register() {
		if (registered) return;
		registered = true;
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (level.isClientSide() && DianaState.inHub()) {
				ArrowGuess.onBlockClicked(pos);
				onBlockInteraction(pos);
			}
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || !DianaState.inHub()) return InteractionResult.PASS;
			if (!DianaState.isSpade(player.getMainHandItem())) return InteractionResult.PASS;
			onBlockInteraction(hit.getBlockPos());
			return SpadeGuess.onSpadeUse() ? InteractionResult.FAIL : InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || !DianaState.inHub()) return InteractionResult.PASS;
			if (!DianaState.isSpade(player.getMainHandItem())) return InteractionResult.PASS;
			return SpadeGuess.onSpadeUse() ? InteractionResult.FAIL : InteractionResult.PASS;
		});
	}

	private static void onBlockInteraction(BlockPos pos) {
		lastBlockClicked = pos.immutable();
		lastBlockClickedAt = System.currentTimeMillis();
		for (DianaWaypoints.Type type : new DianaWaypoints.Type[]{DianaWaypoints.Type.BURROW, DianaWaypoints.Type.ARROW,
			DianaWaypoints.Type.GUESS, DianaWaypoints.Type.SUBGUESS}) {
			DianaWaypoints.Waypoint w = DianaWaypoints.getAt(pos, type);
			if (w == null) continue;
			w.userInteracted = true;
			lastWaypointClicked = w.pos;
			lastWaypointClickedAt = System.currentTimeMillis();
			return;
		}
	}

	/** @return true if this was the chain dug message. */
	static boolean onChat(String legacy) {
		Matcher m = BURROW_DUG.matcher(legacy);
		if (!m.find()) return false;
		int current, max;
		try {
			current = Integer.parseInt(m.group(3).replaceAll("§.", "").trim());
			max = Integer.parseInt(m.group(4).replaceAll("§.", "").trim());
		} catch (NumberFormatException e) {
			return false;
		}
		ArrowGuess.onBurrowDug(current, max);
		BurrowDetector.onBurrowDug();
		return true;
	}
}
