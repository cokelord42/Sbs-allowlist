package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Per user request ("Item rarity background, should fill the inventory/hotbar slot with the color of the
 * item rarity that is currently in the slot. Allow users to select between full square and circle"): fills
 * each slot's icon area with a translucent tint of the held item's real Hypixel rarity color, read the same
 * way ChestRollingFeature/ItemPickupLogFeature already do — the last non-blank lore line's actual structural
 * text color (see {@link #rarityColorOf}), which for a real rarity item is always one of Hypixel's own
 * fixed rarity colors (common gray up through mythic/special colors).
 *
 * <p>Two render paths cover the two places "inventory/hotbar" actually means: any container screen showing
 * real items (via PreItemRenderRegistry, same "paint behind the item icon" choke point every other item
 * highlight in this project already uses — per later user request this now covers every
 * {@code AbstractContainerScreen}, not just the player's own E-menu inventory, so storage pages/Ender
 * Chest/backpacks etc. all get it too) and the always-visible hotbar HUD row (via HudElementRegistry,
 * attached immediately before {@link VanillaHudElements#HOTBAR} so this fill sits under the vanilla item
 * icons instead of over them). No separate screen-type exclusion list is needed for GUIs with no rarity
 * items (a bank balance display, etc.) — {@link #rarityColorOf} already returns null for anything without
 * a real rarity-colored lore line, so those slots simply paint nothing regardless of which screen they're on.
 */
public class ItemRarityBackgroundFeature extends Feature {
	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("skyblocksimplified", "item_rarity_background");
	private static final int HOTBAR_WIDTH = 182;
	private static final int SLOT_SIZE = 20;
	private static final int BOTTOM_MARGIN = 22;
	private static final int ICON_SIZE = 16;
	private static final int FILL_ALPHA = 0x60;

	public enum Shape { SQUARE, CIRCLE }

	private Shape shape = Shape.SQUARE;
	private static boolean listenersRegistered = false;
	private static ItemRarityBackgroundFeature instance;

	// Per "Performance Toggles" (task: "optimize EVERYTHING"): rarityColorOf() re-parses an item's full
	// lore component list and walks a Style visitor every time it's called — cheap once, but this listener
	// now runs for EVERY slot of EVERY container screen EVERY single render frame (broadened from just the
	// player's own inventory earlier this round), so a busy storage screen could be re-deriving the same
	// unchanged rarity color 60+ times a second per slot for no reason. Cached per slot index, keyed off
	// simple reference equality of the ItemStack actually sitting in that slot right now — recomputes only
	// when the object in a slot actually changes (a real inventory update), reusing the cached color every
	// other frame. Reference equality can occasionally miss a "same item, new object" server resync and
	// recompute one frame it didn't strictly need to, but never serves a WRONG/stale color for a slot whose
	// contents visibly changed, and the cache itself is cleared outright whenever the screen changes.
	private static java.lang.ref.WeakReference<net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>> cachedScreen = null;
	private static final java.util.Map<Integer, ItemStack> cachedStacks = new java.util.HashMap<>();
	private static final java.util.Map<Integer, Integer> cachedColors = new java.util.HashMap<>();

	private static Integer cachedRarityColorOf(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen, int slotIndex, ItemStack stack) {
		if (!PerformanceTogglesFeature.isReduceRarityBackgroundUpdates()) return rarityColorOf(stack);
		if (cachedScreen == null || cachedScreen.get() != screen) {
			cachedScreen = new java.lang.ref.WeakReference<>(screen);
			cachedStacks.clear();
			cachedColors.clear();
		}
		ItemStack previous = cachedStacks.get(slotIndex);
		if (previous == stack && cachedColors.containsKey(slotIndex)) {
			return cachedColors.get(slotIndex);
		}
		Integer color = rarityColorOf(stack);
		cachedStacks.put(slotIndex, stack);
		cachedColors.put(slotIndex, color);
		return color;
	}

	// Separate, always-9-slot cache for the hotbar HUD row — kept apart from the container-screen cache
	// above so the two paths (mutually exclusive per frame in practice, but not guaranteed to stay that way)
	// can never cross-contaminate each other's cached colors just because both happen to use slot index 0-8.
	private static final ItemStack[] cachedHotbarStacks = new ItemStack[9];
	private static final Integer[] cachedHotbarColors = new Integer[9];

	private static Integer cachedHotbarRarityColorOf(int index, ItemStack stack) {
		if (!PerformanceTogglesFeature.isReduceRarityBackgroundUpdates()) return rarityColorOf(stack);
		if (cachedHotbarStacks[index] == stack) return cachedHotbarColors[index];
		Integer color = rarityColorOf(stack);
		cachedHotbarStacks[index] = stack;
		cachedHotbarColors[index] = color;
		return color;
	}

	public ItemRarityBackgroundFeature() {
		super("item_rarity_background", "Item Rarity Background", FeatureCategory.INVENTORY, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	public Shape getShape() { return shape; }
	public void cycleShape() {
		Shape[] values = Shape.values();
		shape = values[(shape.ordinal() + 1) % values.length];
	}

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;

		com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			// Real bug found (per user report — "the rarity background coloring only happens in the
			// inventory and hotbar... make it also trigger in stuff like storage pages, and every part
			// where the inventory is showing"): this used to hard-gate on screen instanceof InventoryScreen
			// specifically (the player's own E-menu inventory only), even though PreItemRenderRegistry
			// already fires for every AbstractContainerScreen — Ender Chest, backpacks, and any other
			// storage-type screen were never reached at all. No screen-type allowlist needed to cover
			// "obviously not counting stuff guis where the items dont have any rarity like the bank" either:
			// rarityColorOf already returns null for any item with no real rarity-colored lore line, so a
			// screen full of non-rarity items (a bank balance display, etc.) naturally paints nothing on its
			// own regardless of which screen it is.
			if (instance == null || !instance.isEnabled()) return;
			for (Slot slot : screen.getMenu().slots) {
				ItemStack stack = slot.getItem();
				if (stack.isEmpty()) continue;
				Integer color = cachedRarityColorOf(screen, slot.index, stack);
				if (color == null) continue;
				instance.fillSlot(graphics, slot.x, slot.y, color);
			}
		});

		HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR, HUD_ID, (graphics, deltaTracker) -> {
			if (instance == null || !instance.isEnabled()) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.gui.screen() != null) return;
			int screenWidth = mc.getWindow().getGuiScaledWidth();
			int screenHeight = mc.getWindow().getGuiScaledHeight();
			int barLeft = (screenWidth - HOTBAR_WIDTH) / 2;
			int slotY = screenHeight - BOTTOM_MARGIN + 2;
			var inventory = mc.player.getInventory();
			for (int i = 0; i < 9; i++) {
				ItemStack stack = inventory.getItem(i);
				if (stack.isEmpty()) continue;
				Integer color = cachedHotbarRarityColorOf(i, stack);
				if (color == null) continue;
				int slotX = barLeft + 3 + i * SLOT_SIZE;
				instance.fillSlot(graphics, slotX, slotY, color);
			}
		});
	}

	/** Per user report ("The rarity backgrounds should work in the storage overlay"): Storage Overlay draws
	 *  its own item icons directly rather than going through the real vanilla per-slot render this feature's
	 *  {@code PreItemRenderRegistry} listener hooks into (Storage Overlay cancels the real screen's render
	 *  entirely and draws its own cards instead — see {@code StorageOverlayFeature}'s own doc comment), so
	 *  that listener never fires for it. Exposed here so any feature drawing its own item icons can paint the
	 *  same real rarity-color fill immediately before drawing the icon itself, matching this feature's own
	 *  on/off toggle and shape setting instead of hard-coding a duplicate copy of either. No-ops (draws
	 *  nothing) when this feature is disabled or the item has no real rarity color — safe to call
	 *  unconditionally from any per-item draw loop. Fixed 16px footprint, matching this feature's own real
	 *  vanilla-icon-sized fill everywhere else it's used. */
	public static void fillSlotPublic(GuiGraphicsExtractor graphics, int x, int y, ItemStack stack) {
		if (instance == null || !instance.isEnabled() || stack.isEmpty()) return;
		Integer color = rarityColorOf(stack);
		if (color == null) return;
		instance.fillSlot(graphics, x, y, color);
	}

	/** Same real rarity-color read as {@link #fillSlotPublic}, but leaves drawing to the caller — for a
	 *  caller like Storage Overlay whose own item icons render at a variable, non-16px cell size and need the
	 *  fill sized to match instead of this feature's own fixed 16px footprint. */
	public static Integer rarityColorPublic(ItemStack stack) {
		if (instance == null || !instance.isEnabled() || stack.isEmpty()) return null;
		return rarityColorOf(stack);
	}

	public static Shape getGlobalShape() { return instance != null ? instance.shape : Shape.SQUARE; }

	private void fillSlot(GuiGraphicsExtractor graphics, int x, int y, int rgb) {
		int color = (FILL_ALPHA << 24) | (rgb & 0xFFFFFF);
		if (shape == Shape.CIRCLE) {
			int radius = ICON_SIZE / 2;
			RenderUtil.fillCircle(graphics, x + radius, y + radius, radius, color);
		} else {
			graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, color);
		}
	}

	// Every genuine Hypixel item rarity tier, as it literally appears (uppercase, optionally prefixed with
	// "DUNGEON" for dungeonized gear) in that item's own bottommost lore line.
	private static final java.util.regex.Pattern RARITY_TIER = java.util.regex.Pattern.compile(
		"\\b(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|SUPREME|ADMIN|ULTIMATE)\\b");

	// Real bug found (per user report — "the rare background only breaks on certain texture packs like no
	// texture pack"): the fill color used to come ONLY from Style#getColor() on the matched rarity-name lore
	// line, which requires that line's Component to actually carry a structured per-run TextColor. That's
	// normally reliable, but under some resource-pack states it apparently isn't (nothing about this reads
	// texture data at all, so exactly why is still unconfirmed) — this hard, fixed fallback (Hypixel's own
	// real per-tier colors, the same values ChestRollingFeature's accentColorOf hardcodes for RARE/EPIC/
	// LEGENDARY) kicks in whenever the tier name matched but no real style color could be extracted, so a
	// missing/inconsistent Style can never suppress the fill entirely again.
	private static final java.util.Map<String, Integer> RARITY_FALLBACK_COLOR = java.util.Map.ofEntries(
		java.util.Map.entry("COMMON", 0xFFFFFF),
		java.util.Map.entry("UNCOMMON", 0x55FF55),
		java.util.Map.entry("RARE", 0x5555FF),
		java.util.Map.entry("EPIC", 0xAA00AA),
		java.util.Map.entry("LEGENDARY", 0xFFAA00),
		java.util.Map.entry("MYTHIC", 0xFF55FF),
		java.util.Map.entry("DIVINE", 0x55FFFF),
		java.util.Map.entry("SPECIAL", 0xFF5555),
		java.util.Map.entry("SUPREME", 0xAA0000),
		java.util.Map.entry("ADMIN", 0xAA0000),
		java.util.Map.entry("ULTIMATE", 0xFFAA00));

	/** Real per-item rarity RGB, read off the last non-blank lore line's actual structural text color —
	 *  every genuine Hypixel rarity tier always colors that line with its own fixed rarity color, so this
	 *  is exact rather than a guess. Returns null for items with no lore/rarity line (blocks, vanilla junk,
	 *  the Skyblock menu compass, etc.) so those slots are left untouched.
	 *
	 *  <p>Real bug found (per user report — "Stuff with no rarity shouldn't render a rarity background. Its
	 *  rendering on stuff like everything in the skyblock menu and such"): this used to treat ANY colored
	 *  text on the last non-blank lore line as "the rarity color," unconditionally — plenty of non-rarity
	 *  items (menu navigation icons, GUI buttons, etc.) end their lore with some other colored line ("Click
	 *  to view!", a stat blurb, whatever) that isn't a rarity tier at all but still has a color, so it got
	 *  painted as if it were one. The bottommost non-blank line is still the only one checked (matching where
	 *  Hypixel always places the real rarity line), but its plain text must now actually read as one of
	 *  Hypixel's own known rarity tier names before its color counts as a real rarity — anything else,
	 *  including a colored non-rarity line sitting in that same last-line position, is now correctly treated
	 *  as "no rarity" and left unpainted. */
	private static Integer rarityColorOf(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return null;
		List<Component> lines = lore.lines();
		for (int i = lines.size() - 1; i >= 0; i--) {
			Component line = lines.get(i);
			String plain = line.getString();
			if (plain.isBlank()) continue;
			String upper = plain.toUpperCase(java.util.Locale.ROOT);
			var matcher = RARITY_TIER.matcher(upper);
			if (!matcher.find()) return null;
			Integer styleColor = line.visit((style, text) -> {
				if (text.isEmpty() || style.getColor() == null) return java.util.Optional.<Integer>empty();
				return java.util.Optional.of(style.getColor().getValue());
			}, Style.EMPTY).orElse(null);
			return styleColor != null ? styleColor : RARITY_FALLBACK_COLOR.get(matcher.group(1));
		}
		return null;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("shape", shape.name());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("shape")) {
			try { shape = Shape.valueOf(obj.get("shape").getAsString()); } catch (IllegalArgumentException ignored) {}
		}
	}

	@Override
	public String getDescription() {
		return "Fills each inventory/hotbar slot with a colored background matching that item's rarity.";
	}
}
