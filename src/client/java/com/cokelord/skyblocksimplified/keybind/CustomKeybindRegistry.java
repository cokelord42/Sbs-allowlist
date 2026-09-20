package com.cokelord.skyblocksimplified.keybind;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.EnumMap;
import java.util.Map;

/**
 * Remaps the 8 core movement/action keys (attack/use/left/right/forward/back/jump/sneak) to whatever
 * combo each KeybindOverrideFeature captures, consumed by KeyMappingOverrideMixin — ported from
 * SkyHanni's GardenCustomKeybinds.kt, including its real gating (Garden island only, optionally an actual
 * farming tool/vacuum/fishing rod in hand). "Force Exclude Barn" still works exactly as SkyHanni's does,
 * using its same confirmed real-world barn-plot bounding box, since that check only needs the player's
 * position.
 */
public final class CustomKeybindRegistry {
	private CustomKeybindRegistry() {}

	public enum Slot { ATTACK, USE, LEFT, RIGHT, FORWARD, BACK, JUMP, SNEAK }

	// Ported verbatim from SkyHanni's GardenApi.barnArea.
	private static final AABB BARN_AREA = new AABB(35.5, 70.0, -4.5, -32.5, 100.0, -46.5);

	private static final Map<Slot, KeyCombo> combos = new EnumMap<>(Slot.class);
	private static final Map<Slot, Boolean> previousHeld = new EnumMap<>(Slot.class);

	public static void register(Slot slot, KeyCombo combo) {
		combos.put(slot, combo);
	}

	public static boolean isActive() {
		Feature master = FeatureRegistry.get("custom_keybinds");
		if (master == null || !master.isEnabled()) return false;
		if (Minecraft.getInstance().gui.screen() != null) return false;
		if (!com.cokelord.skyblocksimplified.util.IslandGate.isInGarden()) return false;
		Feature excludeBarn = FeatureRegistry.get("force_exclude_barn");
		if (excludeBarn != null && excludeBarn.isEnabled() && isInBarnArea()) return false;
		Feature holdingOnlyFeature = FeatureRegistry.get("custom_keybinds_holding_only");
		if (holdingOnlyFeature != null && holdingOnlyFeature.isEnabled()
			&& holdingOnlyFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsHoldingOnlyFeature holdingOnly
			&& !isHoldingRelevantItem(holdingOnly)) {
			return false;
		}
		return true;
	}

	/** 1.0 (unscaled) whenever the remap isn't active, so this is always safe to multiply the mouse
	 *  turn deltas by regardless of whether Custom Keybinds is even on. */
	public static float sensitivityScale() {
		if (!isActive()) return 1.0f;
		return FeatureRegistry.get("custom_keybinds") instanceof com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsMasterFeature master
			? master.getSensitivity() : 1.0f;
	}

	private static boolean isInBarnArea() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return false;
		return BARN_AREA.contains(player.getX(), player.getY(), player.getZ());
	}

	// "These items" = hoes (any farming tool — Hypixel's dedicated Wheat/Carrot/Potato/etc hoes are all
	// reskinned vanilla hoes, so a real HoeItem check covers every tier/reforge at once) and fishing rods
	// — Pest Vacuum removed per user request (Hypixel nerfed the vacuum item, so the near-0 sensitivity
	// this whole subsystem existed for isn't needed with it anymore).
	private static boolean isHoldingRelevantItem(com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsHoldingOnlyFeature settings) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return false;
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) return false;
		Item item = held.getItem();
		if (settings.isHoe() && item instanceof HoeItem) return true;
		return settings.isFishingRod() && item instanceof net.minecraft.world.item.FishingRodItem;
	}

	private static Slot slotFor(KeyMapping mapping) {
		Options options = Minecraft.getInstance().options;
		if (options == null) return null;
		if (mapping == options.keyAttack) return Slot.ATTACK;
		if (mapping == options.keyUse) return Slot.USE;
		if (mapping == options.keyLeft) return Slot.LEFT;
		if (mapping == options.keyRight) return Slot.RIGHT;
		if (mapping == options.keyUp) return Slot.FORWARD;
		if (mapping == options.keyDown) return Slot.BACK;
		if (mapping == options.keyJump) return Slot.JUMP;
		if (mapping == options.keyShift) return Slot.SNEAK;
		return null;
	}

	/** Null means "don't override" (mapping isn't one of the 8, the system isn't active, or that slot
	 *  has no combo captured yet) — the mixin leaves vanilla's own isDown() result alone in that case. */
	public static Boolean overrideIsDown(KeyMapping mapping) {
		if (!isActive()) return null;
		Slot slot = slotFor(mapping);
		if (slot == null) return null;
		KeyCombo combo = combos.get(slot);
		if (combo == null || combo.isEmpty()) return null;
		return combo.isHeld();
	}

	/** Same idea for consumeClick(), approximated as a rising-edge check on the combo's held state
	 *  since KeyMapping's own click-counting internals aren't something a mixin can cleanly reuse. */
	public static Boolean overrideConsumeClick(KeyMapping mapping) {
		if (!isActive()) return null;
		Slot slot = slotFor(mapping);
		if (slot == null) return null;
		KeyCombo combo = combos.get(slot);
		if (combo == null || combo.isEmpty()) return null;
		boolean held = combo.isHeld();
		boolean was = previousHeld.getOrDefault(slot, false);
		previousHeld.put(slot, held);
		return held && !was;
	}
}
