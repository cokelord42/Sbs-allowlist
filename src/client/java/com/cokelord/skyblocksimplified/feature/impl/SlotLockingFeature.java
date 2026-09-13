package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Locks an item by its real per-item Hypixel UUID (see {@link SkyblockNbtUtils#getItemUuid}) so it can
 * never accidentally be dropped — regardless of which slot it's currently sitting in.
 *
 * <p>Round 2 real bug found (per user report — "Slot locking STILL does nothing... When pressing the
 * keybind when hovering an item, it should lock that uuid specifically"): the toggle keybind was polled via
 * {@code onTick()}'s {@code KeyMapping.consumeClick()} — the exact same structural bug this session already
 * found and fixed for {@link CustomLoadoutKeybindsFeature}/{@link LeapMenuFeature}: vanilla only ever feeds
 * a raw key-down into a KeyMapping's click bookkeeping through {@code Minecraft.handleKeybinds()}, which is
 * itself skipped entirely whenever ANY screen is open — and this feature's whole point (hovering an item)
 * requires exactly that. The keybind could never have fired even once. Reads the raw key event directly via
 * {@link ScreenKeyboardEvents#allowKeyPress}, same proven mechanism {@link SlotBindsFeature} already uses
 * for its own identical "press a key while hovering a slot" gesture.
 *
 * <p>Round 2 redesigned protection per the user's own explicit step-by-step spec: "Locking an item means no
 * dropping (by any means, protect it!)... It should still be able to be picked up and move across guis (the
 * uuid rememberance should still stay if moved) but it should never ever leave the players inventories." The
 * previous design blocked PICKUP outright, which directly contradicted "should still be able to be picked
 * up and move across guis." Redesigned around one general rule: a locked item's UUID may freely move
 * onto/off the cursor and between any of the player's OWN inventory slots (covers moving it to another
 * GUI's player-inventory row too, e.g. an ender chest or backpack screen), but may never actually be PLACED
 * into a slot belonging to any container other than the player's own Inventory — which also happens to be
 * what dropping into a plain chest requires. Shift-click (QUICK_MOVE) FROM a player slot while any foreign
 * screen is open is blocked outright for the same reason (that direction sends it OUT); the reverse
 * direction (a foreign slot back into the player's own inventory) stays allowed. THROW (Q/Ctrl+Q while
 * hovering a slot) and a click outside the window entirely (vanilla's own "drop the cursor stack into the
 * world" path) are both still blocked outright. Dropping straight from the hotbar with no screen open at all
 * doesn't go through a container click at all — see {@link com.cokelord.skyblocksimplified.mixin.LocalPlayerDropMixin}
 * for that separate path.
 *
 * <p>Round 3 (per user report — "I dont think stuff with uuids can be sold anyway, so remove that part of
 * the slot locking message... since it doesnt even work and its not like i can sell it anyway"): dropped the
 * earlier framing of this as also preventing NPC/Auction House selling specifically — real per-item-UUID
 * gear generally can't be sold that way at all regardless of this feature, so the claim was both misleading
 * and moot. The confirmation message and this doc comment now only describe what this genuinely does: keep
 * a locked item from being dropped or moved out of your own inventory.
 *
 * <p>Round 4 (per user report — "When an item is locked... it cannot move guis. It should be able to"):
 * shift-click (QUICK_MOVE) of a locked item out of a player-inventory slot was blocked unconditionally,
 * regardless of whether any foreign container was actually open. First attempt scoped the block to only
 * fire when a real foreign container was present, per the original round 2 "never leave the players
 * inventories" spec.
 *
 * <p>Round 5 (per user follow-up — "It still doesnt work. I still cant move items out of my inventory when
 * they are locked... Slot locking... shouldnt disallow that since it just binds slots and not actually
 * items"): the round 4 fix only patched shift-click; a regular drag-and-drop PLACE of a carried locked item
 * into a non-player slot was still blocked outright by design, which is exactly what was still being
 * reported. Per this explicit follow-up, the "never leave the player's inventories" restriction from round 2
 * is removed entirely — a locked item can now be freely moved anywhere via any click type, into any GUI,
 * same as an unlocked item. The one thing this feature still protects against, and the only thing it was ever
 * really for per the very first round 1 request, is accidentally DROPPING it: THROW (Q/Ctrl+Q on a slot), a
 * click outside the window (dropping the whole cursor stack), and a world-drop with no screen open at all
 * (via {@link com.cokelord.skyblocksimplified.mixin.LocalPlayerDropMixin}) all stay blocked.
 */
public class SlotLockingFeature extends Feature {
	private final Set<String> lockedItemUuids = new HashSet<>();

	private final KeyMapping toggleKey = new KeyMapping("key.skyblocksimplified.slot_lock_toggle",
		InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), com.cokelord.skyblocksimplified.keybind.ModKeyCategory.MAIN);

	private static final int LOCK_OUTLINE_COLOR = 0xFFFFD700;

	private static SlotLockingFeature instance;

	public SlotLockingFeature() {
		super("slot_locking", "Slot Locking", FeatureCategory.INVENTORY, false);
		instance = this;
		KeyMappingHelper.registerKeyMapping(toggleKey);
	}

	@Override
	public String getSubcategory() {
		return "Misc";
	}

	@Override
	public List<KeyMapping> getKeybinds() {
		return List.of(toggleKey);
	}

	/** Used by {@link com.cokelord.skyblocksimplified.mixin.LocalPlayerDropMixin} to block the world-drop
	 *  (Q/Ctrl+Q with no screen open) path, which never goes through a container click at all. */
	public static boolean isLocked(ItemStack stack) {
		if (instance == null || !instance.isEnabled() || instance.lockedItemUuids.isEmpty() || stack.isEmpty()) return false;
		String uuid = SkyblockNbtUtils.getItemUuid(stack);
		return uuid != null && instance.lockedItemUuids.contains(uuid);
	}

	// Real bug found (per user report — "Can't ult when pressing Q on a locked slot... allow users to 'drop'
	// their currently held item when ult is ready, but only in dungeons"): a locked drop-to-activate ultimate
	// item (its own lore prints "Ability: ... DROP" as the trigger, e.g. the Bonzo/Livid-style "throw it" ult
	// weapons) was being blocked from dropping at all once locked, same as any other item — silently taking
	// away the ability to use it mid-fight, the one place locking it matters least (nobody accidentally drops
	// their weapon by pressing Q for no reason in the middle of a dungeon). Detected straight off the item's
	// own current lore rather than depending on the separate, independently-toggleable Ability Cooldown Timer
	// module being enabled too — "Cooldown: Xs" only appears in the lore while the ability is actually on
	// cooldown, so its absence on a DROP-triggered item is Hypixel's own live "ready" signal.
	private static final java.util.regex.Pattern DROP_ABILITY_LINE = java.util.regex.Pattern.compile("(?i).*Ability:.*\\bDROP\\b.*");
	private static final java.util.regex.Pattern ACTIVE_COOLDOWN_LINE = java.util.regex.Pattern.compile(".*Cooldown:\\s*\\d.*");

	/** Used by {@link com.cokelord.skyblocksimplified.mixin.LocalPlayerDropMixin} to let a locked item's own
	 *  drop-to-activate ultimate through anyway, but only inside a dungeon and only while the item's own lore
	 *  shows the ability is actually ready (no active cooldown line). */
	public static boolean isReadyToDropUltimate(ItemStack stack) {
		if (stack.isEmpty() || !com.cokelord.skyblocksimplified.dungeon.DungeonState.isInDungeon()) return false;
		var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		if (lore == null) return false;
		boolean isDropAbility = false;
		boolean onCooldown = false;
		for (Component line : lore.lines()) {
			String plain = line.getString();
			if (DROP_ABILITY_LINE.matcher(plain).matches()) isDropAbility = true;
			if (ACTIVE_COOLDOWN_LINE.matcher(plain).matches()) onCooldown = true;
		}
		return isDropAbility && !onCooldown;
	}

	private static boolean listenersRegistered = false;

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			ContainerClickRegistry.setRule("slot_locking", this::onSlotClick);
			// Per user request ("Remove the outline from the locked items") — the lock-icon badge on
			// PreTooltipRenderRegistry (see renderLockIcon's own doc comment) is the only visible indicator now.
			com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
				if (isEnabled()) renderLockIcons(graphics, screen);
			});
			ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
				if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
				// Real bug found (per user report — "Slot Locking: poll more often when switching between
				// different-size GUIs (5x9 to 3x9)"): lastSlotUuid/lastSlotUuidMillis are keyed by raw slot
				// index, not by which screen that index belongs to, and used to persist across screen changes
				// with no invalidation. Switching from a larger container (e.g. a 5x9 chest) to a smaller one
				// (e.g. a 3x9 chest) fast enough to land inside the 400ms grace window meant a locked item's
				// leftover cache entry at some index could get misread as still applying to whatever unrelated
				// item now happens to occupy that same raw index in the brand-new GUI — a stale-cache bug, not
				// a real polling-rate problem, but the fix is the same either way: never trust cached per-slot
				// state from a screen that's no longer open. Cleared on every real container screen open.
				instance.lastSlotUuid.clear();
				instance.lastSlotUuidMillis.clear();
				// Real bug found (per user report - "My frames seem to decline a LOT in my inventory, and I
				// think its the lock polling. Make it less often but make it update when switching
				// inventories"): renderLockIcons below runs once per RENDERED FRAME (fired from
				// AbstractContainerScreenTooltipMixin, not a fixed-rate tick), and used to re-derive every
				// slot's real item UUID on every single one of those frames via SkyblockNbtUtils.getItemUuid,
				// which deep-copies that slot's whole NBT CompoundTag (CustomData#copyTag()) per call - at
				// 60-240fps with a full inventory that's dozens of full NBT copies every frame, purely to
				// redraw a badge that only actually changes when items move. Forcing lockedSlotScanDue here on
				// every screen open/switch guarantees the badges never lag behind a GUI change, while
				// renderLockIcons itself now only re-scans on a throttled timer otherwise.
				instance.lockedSlotScanDue = true;
				ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
					if (instance == null || !instance.isEnabled() || instance.toggleKey.isUnbound()
						|| !instance.toggleKey.matches(event)) return true;
					instance.onToggleKeyPressed(client, containerScreen);
					return false;
				});
			});
		}
		// Per user report: hovering a slot and pressing "the button" silently did nothing — the keybind
		// starts unbound (InputConstants.UNKNOWN) like every other feature keybind in this project. This is
		// the one place that can actually detect "the key was never set" and say so, right when the module
		// is turned on.
		if (toggleKey.isUnbound() && Minecraft.getInstance().player != null) {
			Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(
				"§eSlot Locking has no keybind set yet — bind \"Slot Locking Toggle\" in Controls > Key Binds first."));
		}
	}

	// Round 6 (per user request, task tracker #584 — "Extend Slot Locking to UUID items in storage GUIs"):
	// this used to require hovering a slot in the player's own inventory specifically, forcing a round trip
	// (move the item to your inventory, lock it, move it back) to lock anything sitting in an Ender Chest
	// page or backpack. Locking has been screen-agnostic since round 5 (see this class's own doc comment —
	// protection is keyed purely on the item's UUID, not its container), so there's no real reason locking
	// itself needs to be initiated from the player's own inventory either; toggleLock already refuses
	// anything without a trackable UUID, which is the only real gate that matters here.
	private void onToggleKeyPressed(Minecraft client, AbstractContainerScreen<?> screen) {
		if (client.player == null) return;
		Slot hovered = ((AbstractContainerScreenAccessor) screen).skyblocksimplified$getHoveredSlot();
		if (hovered == null || hovered.getItem().isEmpty()) {
			client.gui.hud.getChat().addClientSystemMessage(Component.literal(
				"§cHover an item first."));
			return;
		}
		toggleLock(client, hovered.getItem());
	}

	private void toggleLock(Minecraft client, ItemStack stack) {
		String uuid = SkyblockNbtUtils.getItemUuid(stack);
		if (uuid == null) {
			if (client.player != null) {
				client.gui.hud.getChat().addClientSystemMessage(Component.literal(
					"§cThat item has no unique id Hypixel tracks it by — it can't be locked."));
			}
			return;
		}

		boolean nowLocked;
		if (lockedItemUuids.remove(uuid)) {
			nowLocked = false;
		} else {
			lockedItemUuids.add(uuid);
			nowLocked = true;
		}
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
		if (client.player != null) {
			// Per user request ("It should lock an item with an audible sound"): same confirm-click sound
			// this project already uses elsewhere (ChatCommandsFeature/TerminalSoundsFeature), pitched up for
			// locking and down for unlocking so the two are audibly distinguishable with no visible feedback
			// needed.
			client.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, nowLocked ? 1.4f : 0.8f);
			// Per round 5 (user report — "I still cant move items out of my inventory when they are
			// locked... it just binds slots and not actually items"): this no longer restricts movement at
			// all, only dropping — the message now says exactly that instead of the old, now-inaccurate
			// "or moved out of your inventory" claim.
			client.gui.hud.getChat().addClientSystemMessage(Component.literal(nowLocked
				? "§aItem locked. It can't be dropped until unlocked."
				: "§cItem unlocked."));
		}
	}

	/** Identity check is the item's own real UUID, read fresh off whatever is currently in the slot/on the
	 *  cursor — not a remembered index, so this stays correct no matter how the item got there. */
	private boolean onSlotClick(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		if (!isEnabled() || lockedItemUuids.isEmpty()) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return false;

		ItemStack carried = screen.getMenu().getCarried();
		String carriedUuid = carried.isEmpty() ? null : SkyblockNbtUtils.getItemUuid(carried);
		boolean carriedIsLocked = carriedUuid != null && lockedItemUuids.contains(carriedUuid);

		String slotUuid = slot != null ? slotUuidWithGrace(slot) : null;
		boolean slotHoldsLocked = slotUuid != null && lockedItemUuids.contains(slotUuid);

		if (!carriedIsLocked && !slotHoldsLocked) return false;

		// Drops directly from the hovered slot without ever touching the cursor — the literal Q/Ctrl+Q drop
		// action while hovering a slot in an open screen. Blocked, unless this is exactly the same
		// ready-ultimate exception LocalPlayerDropMixin allows for the no-screen-open case (see
		// isReadyToDropUltimate's own doc comment) — kept consistent here for the (rarer) case of pressing Q
		// on the hotbar slot while some other screen (own inventory, a chest, etc.) happens to be open.
		if (type == ContainerInput.THROW) return slotHoldsLocked && !isReadyToDropUltimate(slot.getItem());

		// Clicking outside the window entirely — vanilla's own "drop the whole cursor stack into the world"
		// path. Blocked if the item currently riding the cursor is locked.
		if (slot == null) return carriedIsLocked;

		// Per round 5 (see this class's own doc comment): every other click type — SWAP, QUICK_MOVE
		// (shift-click), PICKUP, CLONE, QUICK_CRAFT, PICKUP_ALL — is left completely unblocked now. A locked
		// item can be freely moved into any slot, any GUI, exactly like an unlocked item; only the THROW/
		// outside-window/world-drop paths above still protect it from being dropped.
		return false;
	}

	// Grace period fixing a real bug (per user report — "the lock and outline disappears for a moment
	// sometimes" on dyed/color-cycling leather armor): Hypixel's periodic color-refresh re-sends that
	// slot's ItemStack, and for one client tick the freshly-arrived stack's CUSTOM_DATA (and therefore its
	// "uuid" attribute — see SkyblockNbtUtils.getItemUuid) can momentarily read as absent before the full
	// NBT lands. Without this, that one blank tick makes the lock icon flicker off and back on every time
	// the color ticks over. Remembering the last real UUID seen per slot for a short window rides through
	// that gap; a slot whose item is genuinely swapped out updates immediately once the new item's own
	// (non-null) uuid is read, so this never misattributes the badge to a different real item.
	private static final long SLOT_UUID_GRACE_MILLIS = 400L;
	private final Map<Integer, String> lastSlotUuid = new HashMap<>();
	private final Map<Integer, Long> lastSlotUuidMillis = new HashMap<>();

	private String slotUuidWithGrace(Slot slot) {
		String uuid = SkyblockNbtUtils.getItemUuid(slot.getItem());
		long now = System.currentTimeMillis();
		if (uuid != null) {
			lastSlotUuid.put(slot.index, uuid);
			lastSlotUuidMillis.put(slot.index, now);
			return uuid;
		}
		Long lastSeen = lastSlotUuidMillis.get(slot.index);
		if (lastSeen != null && now - lastSeen <= SLOT_UUID_GRACE_MILLIS) return lastSlotUuid.get(slot.index);
		return null;
	}

	// See the ScreenEvents.AFTER_INIT registration above for why this is throttled: the per-slot UUID scan
	// deep-copies NBT, so it only actually re-runs on this interval instead of on every rendered frame.
	// lockedSlotScanDue forces one immediate off-schedule rescan right after a screen opens/switches, so a
	// freshly-opened GUI's badges are never stale for even one throttle window.
	private static final long LOCKED_SLOT_SCAN_INTERVAL_MILLIS = 150L;
	private long lastLockedSlotScanMillis = 0L;
	private boolean lockedSlotScanDue = true;
	private final Set<Integer> cachedLockedSlotIndices = new HashSet<>();

	private void renderLockIcons(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		if (lockedItemUuids.isEmpty()) return;
		long now = System.currentTimeMillis();
		if (lockedSlotScanDue || now - lastLockedSlotScanMillis >= LOCKED_SLOT_SCAN_INTERVAL_MILLIS) {
			lockedSlotScanDue = false;
			lastLockedSlotScanMillis = now;
			cachedLockedSlotIndices.clear();
			for (Slot slot : screen.getMenu().slots) {
				String uuid = slotUuidWithGrace(slot);
				if (uuid != null && lockedItemUuids.contains(uuid)) cachedLockedSlotIndices.add(slot.index);
			}
		}
		if (cachedLockedSlotIndices.isEmpty()) return;
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();
		List<Slot> slots = screen.getMenu().slots;
		for (int slotIndex : cachedLockedSlotIndices) {
			if (slotIndex >= slots.size()) continue;
			Slot slot = slots.get(slotIndex);
			renderLockIcon(graphics, leftPos + slot.x, topPos + slot.y);
		}
	}

	// Small vector padlock badge (no texture asset — drawn with the same RenderUtil primitives every
	// other overlay in this project already uses) in the slot's top-left corner, per user request: a
	// clear "this item is locked" indicator beyond just the outline ring, legible against any item icon
	// underneath. Draw order matters here: the shackle ring is painted first, then the body rect is
	// painted on top of its lower half, so only the ring's top arc stays visible — that's what makes it
	// read as a padlock silhouette instead of a floating circle plus a separate square. Registered on
	// PreTooltipRenderRegistry (same listener as the outline ring above), which by construction fires
	// after the item icon but before the tooltip — the icon sits under the item's lore, never over it.
	private static final int LOCK_ICON_BACKING = 0xB0000000;

	private void renderLockIcon(GuiGraphicsExtractor graphics, int slotScreenX, int slotScreenY) {
		int x0 = slotScreenX + 1;
		int y0 = slotScreenY + 1;
		RenderUtil.fillRounded(graphics, x0, y0, x0 + 7, y0 + 8, 1, LOCK_ICON_BACKING);
		RenderUtil.fillRoundedRing(graphics, x0 + 1, y0 + 1, x0 + 6, y0 + 6, 2, 1, LOCK_OUTLINE_COLOR);
		RenderUtil.fillRounded(graphics, x0 + 1, y0 + 3, x0 + 6, y0 + 7, 1, LOCK_OUTLINE_COLOR);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		JsonArray arr = new JsonArray();
		for (String uuid : lockedItemUuids) arr.add(uuid);
		obj.add("lockedItemUuids", arr);
		// Per user report ("Some keybinds, like the leap menu keybinds, dont seem to save to config"): this
		// toggleKey is a real vanilla KeyMapping — rebinding it already round-trips through vanilla's own
		// options.txt (MainScreen's key-capture handler calls Minecraft.options.save()), but this mod's OWN
		// config never stored it, unlike every other keybind feature. See LeapMenuFeature's identical fix for
		// the full explanation.
		obj.addProperty("toggleKey", toggleKey.saveString());
		return obj;
	}

	// Per user request ("Locked items (UUID-based) shouldn't export with the whole-mod config... all uuid
	// item related stuff shouldn't export"): the locked-UUID list is real per-player inventory state (this
	// account's own specific items), not a portable "setting" — nobody wants their exact item lock list
	// carried into a config shared with someone else or imported on another account. This mirrors the
	// existing large-local-cache trimming pattern (see ChestRollingFeature.savePersistedDataForExport's own
	// doc comment) — the real on-disk save above is completely unaffected, only the clipboard export is.
	@Override
	public JsonElement savePersistedDataForExport() {
		return new JsonObject();
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		// Real bug found while fixing keybind persistence (see savePersistedData's own doc comment): this
		// used to return early whenever lockedItemUuids was missing/malformed, which would ALSO skip restoring
		// toggleKey below even though the two are unrelated — moved the array-only early-return down so a
		// config missing one doesn't silently drop the other.
		if (obj.has("lockedItemUuids") && obj.get("lockedItemUuids").isJsonArray()) {
			lockedItemUuids.clear();
			for (JsonElement el : obj.getAsJsonArray("lockedItemUuids")) {
				try { lockedItemUuids.add(el.getAsString()); } catch (Exception ignored) {}
			}
		}
		if (obj.has("toggleKey")) {
			try {
				toggleKey.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(obj.get("toggleKey").getAsString()));
				KeyMapping.resetMapping();
			} catch (Exception ignored) {}
		}
	}

	@Override
	public String getDescription() {
		return "Locks a specific item (by its unique ID, regardless of which slot it's in) so it can never be accidentally dropped.";
	}
}
