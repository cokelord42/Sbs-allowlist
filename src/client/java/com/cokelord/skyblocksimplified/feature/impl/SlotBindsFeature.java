package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds two inventory slots together for quick access — shift-clicking either one (while the vanilla
 * Inventory screen is open) swaps it with its bound partner instead of doing a normal quick-move. Ported
 * from Odin's {@code SlotBinds.kt}. One of the two bound slots must be in the hotbar (36-44), matching
 * Odin's own restriction (a bind between two non-hotbar slots would have nothing distinguishing "from" vs
 * "to" for the swap).
 *
 * <p>The bind-set key and per-profile slot maps are plain persisted fields; the bind-set key is entered as
 * a raw GLFW key code (no MainScreen keybind-capture UI yet, matching the "toggle-only settings" gap
 * already flagged for most of this session's other ports) — {@link #GLFW_KEY_UNKNOWN} default means
 * "unset", matching Odin's own default. */
public class SlotBindsFeature extends Feature {
	private static final int MIN_SLOT = 5;
	// Real off-by-one bug found: this was 44, but the comment already said "5 until 45" — with an
	// EXCLUSIVE bound of 44, `slotId >= MAX_SLOT` rejected slot 44 itself, silently excluding the last
	// hotbar slot (HOTBAR_END, defined as inclusive) from ever being bindable or swappable at all.
	private static final int MAX_SLOT = 45; // exclusive upper bound used by Odin (5 until 45)
	private static final int HOTBAR_START = 36;
	private static final int HOTBAR_END = 44; // inclusive
	private static final int GLFW_KEY_UNKNOWN = GLFW.GLFW_KEY_UNKNOWN;

	public enum LineDisplayMode { HOVER, HOVER_SHIFT, NONE }

	private int bindSetKey = GLFW_KEY_UNKNOWN;
	private int lineColor = 0xFF55FF55;
	private int outlineColor = 0xFFFF5555;
	private LineDisplayMode lineDisplayMode = LineDisplayMode.HOVER;
	private int currentProfile = 0;
	private static final int PROFILE_COUNT = 6;

	@SuppressWarnings("unchecked")
	private final Map<Integer, Integer>[] profiles = new Map[PROFILE_COUNT];

	private Integer previousSlot = null;

	public SlotBindsFeature() {
		super("slot_binds", "Slot Binds", FeatureCategory.INVENTORY, false);
		for (int i = 0; i < PROFILE_COUNT; i++) profiles[i] = new HashMap<>();
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	private Map<Integer, Integer> binds() { return profiles[currentProfile]; }

	private static boolean renderListenerRegistered = false;

	@Override
	protected void onEnable() {
		ContainerClickRegistry.setRule("slot_binds", this::onSlotClick);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof InventoryScreen)) return;
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
				if (!isEnabled() || bindSetKey == GLFW_KEY_UNKNOWN || event.key() != bindSetKey) return true;
				onBindKeyPressed((InventoryScreen) s);
				return false;
			});
			ScreenEvents.remove(screen).register(s -> previousSlot = null);
		});
		// Draws via PreTooltipRenderRegistry (fires right before the hovered slot's tooltip, after every
		// slot icon but before the tooltip itself) instead of ScreenEvents.afterExtract, which fires too
		// late and drew the outline/line on TOP of tooltips — the same z-order bug already fixed elsewhere
		// in this codebase for every other in-inventory overlay (see e.g. CroesusFeature/DnaAnalyzerSolverFeature).
		if (!renderListenerRegistered) {
			renderListenerRegistered = true;
			com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
				if (!isEnabled() || !(screen instanceof InventoryScreen invScreen)) return;
				renderBoundSlotOutlines(graphics, invScreen);
				renderLine(graphics, invScreen, mouseX, mouseY);
			});
		}
	}

	@Override
	protected void onDisable() {
		ContainerClickRegistry.clearRule("slot_binds");
	}

	private boolean onSlotClick(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		// type == QUICK_MOVE is itself the proof this was a real shift-click — vanilla only ever assigns
		// QUICK_MOVE from the literal Shift keyboard modifier at click time. A redundant extra check here
		// used to call player.isShiftKeyDown(), which reflects the player's SNEAK state instead — identical
		// to Shift only as long as sneak is still bound to the Shift key. Anyone who rebinds sneak (common,
		// since holding Shift is uncomfortable for a lot of people) got real QUICK_MOVE clicks silently
		// rejected right here, which is exactly "it binds and renders the boxes, but shift-clicking does
		// nothing" with no error to explain why.
		if (!isEnabled() || type != ContainerInput.QUICK_MOVE) return false;
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.gui.screen() instanceof InventoryScreen screen) || mc.player == null) return false;
		if (slotId < MIN_SLOT || slotId >= MAX_SLOT) return false;
		Integer boundSlot = binds().get(slotId);
		if (boundSlot == null) {
			return false;
		}

		int from, to;
		if (slotId >= HOTBAR_START && slotId <= HOTBAR_END) { from = boundSlot; to = slotId; }
		else if (boundSlot >= HOTBAR_START && boundSlot <= HOTBAR_END) { from = slotId; to = boundSlot; }
		else {
			return false;
		}

		LocalPlayer player = mc.player;
		if (mc.gameMode == null || player == null) return false;
		mc.gameMode.handleContainerInput(screen.getMenu().containerId, from, to % 36, ContainerInput.SWAP, player);
		return true;
	}

	private void onBindKeyPressed(InventoryScreen screen) {
		Slot hovered = ((AbstractContainerScreenAccessor) screen).skyblocksimplified$getHoveredSlot();
		if (hovered == null || hovered.index < MIN_SLOT || hovered.index >= MAX_SLOT) return;
		int clickedSlot = hovered.index;

		if (previousSlot != null) {
			int slot = previousSlot;
			previousSlot = null;
			if (slot == clickedSlot) { chatMessage("§cYou can't bind a slot to itself."); return; }
			boolean slotInHotbar = slot >= HOTBAR_START && slot <= HOTBAR_END;
			boolean clickedInHotbar = clickedSlot >= HOTBAR_START && clickedSlot <= HOTBAR_END;
			if (!slotInHotbar && !clickedInHotbar) { chatMessage("§cOne of the slots must be in the hotbar (36-44)."); return; }
			// Real bug found: binds were stored one-way only (slot -> clickedSlot), so shift-clicking the
			// OTHER slot in the pair found nothing in the map and silently did nothing ("only works one
			// way"). Worse, starting a fresh bind from that other slot created a SEPARATE forward entry
			// instead of being recognized as already-bound, so the pair ended up double-bound. Clearing
			// any existing entries touching either slot first, then storing BOTH directions, fixes both:
			// the lookup works no matter which of the two slots is shift-clicked, and re-binding either
			// slot always starts from a clean slate instead of stacking a second one-way entry on top.
			removeBindsInvolving(slot);
			removeBindsInvolving(clickedSlot);
			binds().put(slot, clickedSlot);
			binds().put(clickedSlot, slot);
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			chatMessage("§aAdded bind from slot §b" + slot + " §ato §d" + clickedSlot + " §7(Profile " + (currentProfile + 1) + ").");
			return;
		}

		Integer existing = binds().get(clickedSlot);
		if (existing != null) {
			removeBindsInvolving(clickedSlot);
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			chatMessage("§cRemoved bind from slot §b" + clickedSlot + " §cto §d" + existing + " §7(Profile " + (currentProfile + 1) + ").");
			return;
		}
		previousSlot = clickedSlot;
	}

	/** Removes every bind entry touching this slot in either direction — both the slot's own forward entry
	 *  and its partner's entry pointing back at it (also cleans up any stale one-way-only entry a config
	 *  saved before this fix might still have). See onBindKeyPressed's doc comment for why this matters. */
	private void removeBindsInvolving(int slot) {
		binds().entrySet().removeIf(e -> e.getKey() == slot || e.getValue() == slot);
	}

	/** The literal physical Shift key state (either side), read straight from GLFW via InputConstants —
	 *  unlike LocalPlayer#isShiftKeyDown() (sneak state, only equal to this if sneak is still bound to
	 *  Shift), this is what a "shift held right now" check actually needs. */
	private static boolean isShiftKeyPhysicallyDown() {
		Minecraft mc = Minecraft.getInstance();
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT)
			|| com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT);
	}

	private void chatMessage(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(message));
	}

	/** Every currently-bound slot (both the hotbar side and its partner) gets a thin persistent outline,
	 *  independent of hover — per user request, so bound slots are visible at a glance instead of only
	 *  when hovering one of them (that's what the connecting line, drawn separately below, already covers). */
	private void renderBoundSlotOutlines(GuiGraphicsExtractor graphics, InventoryScreen screen) {
		Map<Integer, Integer> binds = binds();
		if (binds.isEmpty()) return;
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();

		java.util.Set<Integer> boundSlots = new java.util.HashSet<>();
		boundSlots.addAll(binds.keySet());
		boundSlots.addAll(binds.values());
		for (int slotIndex : boundSlots) {
			if (slotIndex >= screen.getMenu().slots.size()) continue;
			Slot slot = screen.getMenu().getSlot(slotIndex);
			int x = leftPos + slot.x - 1, y = topPos + slot.y - 1;
			com.cokelord.skyblocksimplified.gui.RenderUtil.fillRoundedRing(graphics, x, y, x + 18, y + 18, 3, 1, outlineColor);
		}
	}

	private void renderLine(GuiGraphicsExtractor graphics, InventoryScreen screen, double mouseX, double mouseY) {
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		Slot hovered = accessor.skyblocksimplified$getHoveredSlot();
		if (hovered == null || hovered.index < MIN_SLOT || hovered.index >= MAX_SLOT) {
			if (previousSlot == null) return;
		}
		int hoveredIndex = hovered != null ? hovered.index : -1;
		Integer boundSlot = hoveredIndex >= 0 ? binds().get(hoveredIndex) : null;

		boolean shouldDraw = switch (lineDisplayMode) {
			case HOVER -> previousSlot != null || boundSlot != null;
			// Real keyboard-modifier state via InputConstants, not player.isShiftKeyDown()'s sneak state
			// (see onSlotClick's doc comment for why those two aren't the same thing).
			case HOVER_SHIFT -> previousSlot != null || (boundSlot != null && isShiftKeyPhysicallyDown());
			case NONE -> previousSlot != null;
		};
		if (!shouldDraw) return;

		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();
		Slot startSlot = screen.getMenu().getSlot(previousSlot != null ? previousSlot : hoveredIndex);
		int startX = startSlot.x + leftPos + 8;
		int startY = startSlot.y + topPos + 8;

		int endX, endY;
		if (previousSlot != null) {
			endX = (int) mouseX;
			endY = (int) mouseY;
		} else if (boundSlot != null) {
			Slot endSlot = screen.getMenu().getSlot(boundSlot);
			endX = endSlot.x + leftPos + 8;
			endY = endSlot.y + topPos + 8;
		} else return;

		com.cokelord.skyblocksimplified.gui.RenderUtil.drawLine(graphics, startX, startY, endX, endY, 2f, lineColor);
	}

	public int getBindSetKey() { return bindSetKey; }
	public void setBindSetKey(int value) { bindSetKey = value; }
	public int getLineColor() { return lineColor; }
	public void setLineColor(int value) { lineColor = value; }
	public int getOutlineColor() { return outlineColor; }
	public void setOutlineColor(int value) { outlineColor = value; }
	public LineDisplayMode getLineDisplayMode() { return lineDisplayMode; }
	public void setLineDisplayMode(LineDisplayMode value) { lineDisplayMode = value; }
	public int getCurrentProfile() { return currentProfile; }
	public void setCurrentProfile(int value) { currentProfile = Math.max(0, Math.min(PROFILE_COUNT - 1, value)); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("bindSetKey", bindSetKey);
		obj.addProperty("lineColor", lineColor);
		obj.addProperty("outlineColor", outlineColor);
		obj.addProperty("lineDisplayMode", lineDisplayMode.name());
		obj.addProperty("currentProfile", currentProfile);
		JsonArray profilesArr = new JsonArray();
		for (Map<Integer, Integer> profile : profiles) {
			JsonObject profileObj = new JsonObject();
			for (Map.Entry<Integer, Integer> entry : profile.entrySet()) {
				profileObj.addProperty(String.valueOf(entry.getKey()), entry.getValue());
			}
			profilesArr.add(profileObj);
		}
		obj.add("profiles", profilesArr);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("bindSetKey")) bindSetKey = obj.get("bindSetKey").getAsInt();
		if (obj.has("lineColor")) lineColor = obj.get("lineColor").getAsInt();
		if (obj.has("outlineColor")) outlineColor = obj.get("outlineColor").getAsInt();
		if (obj.has("lineDisplayMode")) {
			try { lineDisplayMode = LineDisplayMode.valueOf(obj.get("lineDisplayMode").getAsString()); } catch (IllegalArgumentException ignored) {}
		}
		if (obj.has("currentProfile")) setCurrentProfile(obj.get("currentProfile").getAsInt());
		if (obj.has("profiles") && obj.get("profiles").isJsonArray()) {
			JsonArray arr = obj.getAsJsonArray("profiles");
			for (int i = 0; i < Math.min(arr.size(), PROFILE_COUNT); i++) {
				JsonElement profileEl = arr.get(i);
				if (!profileEl.isJsonObject()) continue;
				Map<Integer, Integer> profile = profiles[i];
				profile.clear();
				for (Map.Entry<String, JsonElement> entry : profileEl.getAsJsonObject().entrySet()) {
					try { profile.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsInt()); } catch (NumberFormatException ignored) {}
				}
			}
		}
	}

	@Override
	public String getDescription() {
		return "Binds two inventory slots together so shift-clicking either one swaps it with its bound partner instead of a normal quick-move.";
	}
}
