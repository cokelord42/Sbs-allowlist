package com.cokelord.skyblocksimplified.inventory;

import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central registry of "veto this container-slot click" rules, consumed by ContainerSlotClickMixin at
 * the single choke point every real slot click passes through before its packet is sent
 * (AbstractContainerScreen.slotClicked). Mirrors SoundMuteRegistry's design: features register/clear a
 * rule under their own stable id. Backs Experiment Addons' "Prevent Misclicks" toggle (Chronomatron/
 * Ultrasequencer wrong-slot clicks).
 */
public final class ContainerClickRegistry {
	private ContainerClickRegistry() {}

	@FunctionalInterface
	public interface ClickRule {
		boolean shouldCancel(Slot slot, int slotId, int mouseButton, ContainerInput type);
	}

	private static final Map<String, ClickRule> rules = new LinkedHashMap<>();
	private static final Map<String, ClickRule> middleClickRules = new LinkedHashMap<>();

	public static void setRule(String id, ClickRule rule) {
		rules.put(id, rule);
	}

	public static void clearRule(String id) {
		rules.remove(id);
	}

	// Real bug found (per user report — "I cant place down items in inventories sometimes" — still active
	// after ChestRollingFeature's own containerId-scoping fix): this loop had no exception isolation at
	// all, unlike every other multi-feature dispatch loop in this codebase (ConfigManager's per-feature
	// try/catch, FeatureRegistry.tickAll, etc. — all deliberately isolated for exactly this reason). If ANY
	// single registered rule ever threw (a stale Slot reference, a null menu mid screen-transition, any of
	// the several rules here that read live dungeon/terminal state that could plausibly be momentarily
	// inconsistent), the exception propagates straight out of ContainerSlotClickMixin's HEAD injection —
	// which means the REAL vanilla slotClicked logic below it never runs either, since the method never
	// reaches its own body. That silently eats that one click with zero visible feedback: no block message,
	// no error, the item just doesn't move — exactly "sometimes, in any inventory, for no visible reason."
	// A broken rule now loses its own vote (fails open — never cancels) instead of taking every other rule
	// and the real click down with it.
	public static boolean shouldCancel(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		for (Map.Entry<String, ClickRule> entry : rules.entrySet()) {
			try {
				if (entry.getValue().shouldCancel(slot, slotId, mouseButton, type)) return true;
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
					"ContainerClickRegistry rule \"{}\" threw, treating this click as not blocked", entry.getKey(), e);
			}
		}
		return false;
	}

	/** Same choke point again, but for "upgrade this click to a real middle-click (ContainerInput.CLONE)"
	 *  instead of a shift-click — Terminal Solver's "redirect left clicks to middle clicks" toggle, ported
	 *  from Odin's confirmed real behavior (Hypixel processes middle-clicks on terminal slots fastest). */
	public static void setMiddleClickRule(String id, ClickRule rule) {
		middleClickRules.put(id, rule);
	}

	public static void clearMiddleClickRule(String id) {
		middleClickRules.remove(id);
	}

	public static boolean shouldUpgradeToMiddleClick(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		for (Map.Entry<String, ClickRule> entry : middleClickRules.entrySet()) {
			try {
				if (entry.getValue().shouldCancel(slot, slotId, mouseButton, type)) return true;
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
					"ContainerClickRegistry middle-click rule \"{}\" threw, treating this click as not upgraded", entry.getKey(), e);
			}
		}
		return false;
	}

	private static final Map<String, ClickRule> rawResendRules = new LinkedHashMap<>();

	public static void setRawResendRule(String id, ClickRule rule) {
		rawResendRules.put(id, rule);
	}

	public static void clearRawResendRule(String id) {
		rawResendRules.remove(id);
	}

	/** Same choke point again, for "cancel vanilla's own click handling and resend the exact same click
	 *  packet manually" instead of letting {@code slotClicked}'s real body run — see
	 *  {@link com.cokelord.skyblocksimplified.mixin.ContainerSlotClickMixin}'s doc comment for why this needs
	 *  to exist at all (the real bug it fixes): the middle-click-upgrade path above already avoids vanilla's
	 *  own client-side click PREDICTION for left clicks (it cancels the real body before that prediction ever
	 *  runs, sending a raw CLONE packet directly instead) — right clicks, which are never upgraded, had no
	 *  such protection and ran vanilla's real body unmodified, which DOES locally predict a pickup (removing
	 *  or halving the clicked slot's item into the cursor) before the server's own authoritative response
	 *  arrives. For a real inventory that's invisible/self-correcting; for a dungeon terminal's specially-
	 *  handled panes it means the client's own copy of that slot's item goes briefly wrong (emptied, in
	 *  practice) purely from local prediction, not a real server-confirmed change. This lets a feature cancel
	 *  that prediction the same way the middle-click path already does, while keeping the original
	 *  button/type (no CLONE substitution) so the real click semantics sent to the server are unchanged. */
	public static boolean shouldRawResend(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		for (Map.Entry<String, ClickRule> entry : rawResendRules.entrySet()) {
			try {
				if (entry.getValue().shouldCancel(slot, slotId, mouseButton, type)) return true;
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
					"ContainerClickRegistry raw-resend rule \"{}\" threw, treating this click as not resent", entry.getKey(), e);
			}
		}
		return false;
	}

	@FunctionalInterface
	public interface ClickListener {
		void onClick(Slot slot, int slotId, int mouseButton, ContainerInput type);
	}

	private static final Map<String, ClickListener> allowedListeners = new LinkedHashMap<>();

	public static void setAllowedListener(String id, ClickListener listener) {
		allowedListeners.put(id, listener);
	}

	public static void clearAllowedListener(String id) {
		allowedListeners.remove(id);
	}

	/** Fired by ContainerSlotClickMixin right after it decides NOT to cancel a click (i.e. no registered
	 *  {@link ClickRule} — from THIS feature or any other one sharing this same choke point — vetoed it).
	 *  Real bug this exists to fix (per user report — "the experimentation table solvers... sometimes go
	 *  forward a click even if the click didn't go through"): a feature that tracks click PROGRESS
	 *  (Experiment Addons' Prevent Misclicks) used to mutate that progress state directly inside its own
	 *  {@code ClickRule} predicate — but that predicate only knows whether ITS OWN rule would veto the
	 *  click, not whether some OTHER rule sharing this registry (e.g. Slot Locking) vetoes the exact same
	 *  click afterward. A click this feature's own rule judged "correct" could still end up cancelled
	 *  overall, and the state had already advanced as if it went through regardless. Listeners here should
	 *  only ever be used to COMMIT state that a {@link ClickRule} already computed was valid, once it's
	 *  confirmed the click is actually proceeding — never to make a new decision from scratch. */
	public static void notifyAllowed(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		for (Map.Entry<String, ClickListener> entry : allowedListeners.entrySet()) {
			try {
				entry.getValue().onClick(slot, slotId, mouseButton, type);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
					"ContainerClickRegistry allowed-listener \"{}\" threw, skipping it for this click", entry.getKey(), e);
			}
		}
	}
}
