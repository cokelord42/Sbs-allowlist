package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.regex.Pattern;

/**
 * Real bug found (per user report — "Superboom TNT detector still isn't working. I think its because
 * superboom tnt isn't actually getting placed, so try to detect when the player is within a 5 block range
 * AND when a superboom in their inventory happens to get used/stack goes down by one"): every prior round's
 * fix for the Dungeon Routes Superboom step (see the SUPERBOOM_BLOCK case's own doc comment) kept tuning the
 * SAME PrimedTnt-entity signal, but the user's own theory this round is that a real usable "primed" entity
 * never actually spawns for at least some real Superboom TNT throws — no amount of tuning that one signal
 * fixes a case where it's never emitted at all. This tracker adds a genuinely different, entity-independent
 * signal: it watches the player's own inventory every tick for a Superboom TNT stack shrinking by at least
 * one, which is real, unambiguous proof the player actually used one regardless of what (if anything) the
 * server spawns to represent it.
 *
 * <p>Infiniboom Powder (per user report — "Find a way with infiniboom aswell, I think the item disappears
 * on use for a split second"): a reusable Superboom TNT replacement that doesn't consume a stack — its own
 * "used" signal is the SAME item transiently vanishing from the player's inventory for a moment (presumably
 * a real per-use cooldown indicator) rather than a permanent count decrease, so it's tracked as its own,
 * separate presence-flicker case. Its real internal item id isn't confirmed, so it's matched by display name
 * (case-insensitive "infiniboom") rather than guessing one — the same fallback approach already used
 * elsewhere in this codebase (see {@link DungeonObjectPredicates#isSuperboomTnt}'s own name-based checks)
 * for items whose exact id isn't known for certain.
 *
 * <p>Per user report ("the secret routes no longer detect using superboom... that wont work for stuff like
 * infiniboom TNT"): both signals above are inventory-state guesses (a stack shrinking, an item transiently
 * vanishing) that depend on assumptions about how each item's use is represented that were never confirmed
 * against a real live test — either could simply not hold for the item's actual real behavior. Added a
 * third, genuinely different signal that doesn't depend on any inventory-state theory at all: Fabric's own
 * {@link UseItemCallback}/{@link UseBlockCallback} events fire the instant the player actually right-clicks
 * with an item in hand (the same real client action that throws/places a Superboom TNT or activates an
 * Infiniboom Powder), so matching the held item by its real NBT id or, failing that, its display name is a
 * direct "the player just used this" signal instead of an indirect inference from inventory contents before
 * and after.
 */
public final class SuperboomUseTracker {
	private static final Pattern INFINIBOOM_NAME = Pattern.compile("(?i).*infiniboom.*");
	private static final Pattern SUPERBOOM_OR_INFINIBOOM_NAME = Pattern.compile("(?i).*(superboom|infiniboom).*");

	private static int lastSuperboomCount = -1;
	private static boolean lastInfiniboomPresent = false;
	private static long lastUseMillis = 0L;
	private static boolean registered = false;

	private SuperboomUseTracker() {}

	public static void register() {
		if (registered) return;
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tick(client);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("SuperboomUseTracker tick threw, skipping this tick", e);
			}
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			onRightClick(player, hand);
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			onRightClick(player, hand);
			return InteractionResult.PASS;
		});
	}

	private static void onRightClick(Player player, InteractionHand hand) {
		if (matchesSuperboomOrInfiniboom(player.getItemInHand(hand))) lastUseMillis = System.currentTimeMillis();
	}

	private static boolean matchesSuperboomOrInfiniboom(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if ("SUPERBOOM_TNT".equals(SkyblockNbtUtils.getItemId(stack))) return true;
		String plain = stack.getHoverName().getString().replaceAll("§.", "");
		return SUPERBOOM_OR_INFINIBOOM_NAME.matcher(plain).matches();
	}

	private static void tick(Minecraft client) {
		if (client.player == null) {
			lastSuperboomCount = -1;
			lastInfiniboomPresent = false;
			return;
		}
		Inventory inventory = client.player.getInventory();
		int superboomCount = 0;
		boolean infiniboomPresent = false;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if ("SUPERBOOM_TNT".equals(SkyblockNbtUtils.getItemId(stack))) {
				superboomCount += stack.getCount();
				continue;
			}
			String plain = stack.getHoverName().getString().replaceAll("§.", "");
			if (INFINIBOOM_NAME.matcher(plain).matches()) infiniboomPresent = true;
		}

		long now = System.currentTimeMillis();
		// -1 means this is the very first tick with a valid player: nothing to compare against yet, only
		// establish the baseline (otherwise the initial 0 -> real-count jump would misfire as a "use").
		if (lastSuperboomCount >= 0 && superboomCount < lastSuperboomCount) lastUseMillis = now;
		if (lastInfiniboomPresent && !infiniboomPresent) lastUseMillis = now;
		lastSuperboomCount = superboomCount;
		lastInfiniboomPresent = infiniboomPresent;
	}

	/** True if a Superboom TNT stack decrease or an Infiniboom Powder disappearance was observed within the
	 *  last {@code windowMillis} milliseconds. */
	public static boolean usedWithinMillis(long windowMillis) {
		return lastUseMillis != 0 && System.currentTimeMillis() - lastUseMillis <= windowMillis;
	}
}
