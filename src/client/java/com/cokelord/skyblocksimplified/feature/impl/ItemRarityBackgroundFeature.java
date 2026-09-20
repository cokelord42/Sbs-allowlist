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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per user request ("Item rarity background, should fill the inventory/hotbar slot with the color of the
 * item rarity that is currently in the slot. Allow users to select between full square and circle"): fills
 * each slot's icon area with a translucent tint of the held item's real Hypixel rarity color.
 *
 * <p>Real design correction (per user report — "i need you to scan both names and lore text. Only if both
 * match the same rarity... should it show the background, to make sure stuff that has the words in the
 * lore dont get rarity backgrounded if they dont match the name color"): an earlier version of this class
 * checked ONLY the item's lore text for a rarity word (false-positived on menu/event items that merely
 * mention a rarity name in a description sentence, e.g. Halloween pumpkins with "Common" in their lore), and
 * the version that replaced it checked ONLY the item's display name color (which can't tell a genuine
 * rarity item from anything else whose name simply happens to be colored the same as a rarity, e.g. plain
 * white/green/red text used for unrelated UI reasons). Requiring BOTH signals to agree on the exact same
 * rarity tier (see {@link #combinedRarityColorOf}) is what actually confirms "this is genuinely a rarity
 * item" — one signal alone was never enough on its own.
 *
 * <p>The one confirmed exception is the Pets menu (per the same report — pets never carry the usual
 * bottommost rarity-tier lore line other gear does, so the lore half of the AND-check can never pass for
 * them even though a pet's name IS still colored by its real rarity): inside that specific menu, and only
 * for its actual pet-icon slots (see {@link #PET_MENU_SLOTS}), name color alone is used instead — see
 * {@link #rarityColorOf(AbstractContainerScreen, int, ItemStack)}.
 *
 * <p>Two render paths cover the two places "inventory/hotbar" actually means: any container screen showing
 * real items (via PreItemRenderRegistry, same "paint behind the item icon" choke point every other item
 * highlight in this project already uses — per later user request this now covers every
 * {@code AbstractContainerScreen}, not just the player's own E-menu inventory, so storage pages/Ender
 * Chest/backpacks etc. all get it too) and the always-visible hotbar HUD row (via HudElementRegistry,
 * attached immediately before {@link VanillaHudElements#HOTBAR} so this fill sits under the vanilla item
 * icons instead of over them — the hotbar can never show the Pets menu, so it always uses the general
 * combined lore+name check). No separate screen-type exclusion list is needed for GUIs with no rarity items
 * (a bank balance display, etc.) — both rarity checks already return null for anything that doesn't
 * genuinely qualify, so those slots simply paint nothing regardless of which screen they're on.
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

	private static Integer cachedRarityColorOf(AbstractContainerScreen<?> screen, int slotIndex, ItemStack stack) {
		if (!PerformanceTogglesFeature.isReduceRarityBackgroundUpdates()) return rarityColorOf(screen, slotIndex, stack);
		if (cachedScreen == null || cachedScreen.get() != screen) {
			cachedScreen = new java.lang.ref.WeakReference<>(screen);
			cachedStacks.clear();
			cachedColors.clear();
		}
		ItemStack previous = cachedStacks.get(slotIndex);
		if (previous == stack && cachedColors.containsKey(slotIndex)) {
			return cachedColors.get(slotIndex);
		}
		Integer color = rarityColorOf(screen, slotIndex, stack);
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
		if (!PerformanceTogglesFeature.isReduceRarityBackgroundUpdates()) return combinedRarityColorOf(stack);
		if (cachedHotbarStacks[index] == stack) return cachedHotbarColors[index];
		Integer color = combinedRarityColorOf(stack);
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
		Integer color = combinedRarityColorOf(stack);
		if (color == null) return;
		instance.fillSlot(graphics, x, y, color);
	}

	/** Same real rarity-color read as {@link #fillSlotPublic}, but leaves drawing to the caller — for a
	 *  caller like Storage Overlay whose own item icons render at a variable, non-16px cell size and need the
	 *  fill sized to match instead of this feature's own fixed 16px footprint. Never the Pets menu (Storage
	 *  Overlay only ever shows Ender Chest/backpack pages), so this always uses the general combined check. */
	public static Integer rarityColorPublic(ItemStack stack) {
		if (instance == null || !instance.isEnabled() || stack.isEmpty()) return null;
		return combinedRarityColorOf(stack);
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

	// Every real Hypixel rarity's own fixed name-text color (confirmed against Hypixel's own item-name
	// coloring convention, the same 16-color legacy palette ChestRollingFeature/ItemPickupLogFeature already
	// hardcode elsewhere in this project) — membership in this set is what actually confirms "this item has
	// a real rarity" now, not lore content at all.
	private static final java.util.Set<Integer> RARITY_NAME_COLORS = java.util.Set.of(
		0xFFFFFF, // COMMON
		0x55FF55, // UNCOMMON
		0x5555FF, // RARE
		0xAA00AA, // EPIC
		0xFFAA00, // LEGENDARY / ULTIMATE
		0xFF55FF, // MYTHIC
		0x55FFFF, // DIVINE
		0xFF5555, // SPECIAL / VERY SPECIAL
		0xAA0000  // SUPREME / ADMIN
	);

	// Every genuine Hypixel item rarity tier, as it literally appears (uppercase, optionally prefixed with
	// "DUNGEON" for dungeonized gear) in that item's own bottommost lore line. Restored from the pre-name-
	// color version of this class (see combinedRarityColorOf's own doc comment for why lore scanning is back).
	private static final Pattern RARITY_TIER = Pattern.compile(
		"\\b(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|SUPREME|ADMIN|ULTIMATE)\\b");

	// Each tier's own fixed real color, keyed by the literal word RARITY_TIER matches — used here purely to
	// decide whether the lore's rarity WORD names the same tier the item's name is actually colored with, not
	// to pick the fill color itself (nameColorOf's own reading, already validated against RARITY_NAME_COLORS,
	// is what actually gets painted). ULTIMATE/LEGENDARY and SUPREME/ADMIN intentionally share a color, same
	// as ChestRollingFeature/ItemPickupLogFeature's own hardcoded copies of this same palette.
	private static final java.util.Map<String, Integer> RARITY_TIER_COLOR = java.util.Map.ofEntries(
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

	// Per user request ("Only in the pets menu specifically should it only search for name color, and make
	// sure it also only searches the pet slots"): the exact same real slot-layout convention this codebase
	// already uses for a 4-row/7-col inner icon grid inside a larger container (see ChestRollingFeature's own
	// CROESUS_RUN_SLOTS, identical shape). Title pattern is PetDisplayFeature/PetKeybindsFeature's own already-
	// confirmed "(n/n) Pets" / bare "Pets" regex, duplicated here rather than exposed from either (both keep
	// theirs private, same convention as this project's other small duplicated confirmed-regex constants).
	private static final Pattern PETS_MENU_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Pets$");
	private static final java.util.Set<Integer> PET_MENU_SLOTS = java.util.Set.of(
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
		37, 38, 39, 40, 41, 42, 43);

	/** Real per-item rarity RGB, read off the item's own display NAME color. Returns null for anything whose
	 *  name isn't colored with one of Hypixel's own fixed rarity colors.
	 *
	 *  <p>Real bug found (per user report — "item rarity background doesnt work on dyed items for some
	 *  reason, it could be because they have a flower at the beginning of the name"): {@code Component.visit}
	 *  short-circuits on the FIRST leaf run its visitor returns a present {@code Optional} for — a dyed
	 *  item's name literally does start with a "❁" flower glyph run colored by the DYE color, not the item's
	 *  rarity, and that used to be the run this method returned (or nothing at all, if that particular dye
	 *  color happened not to be a real rarity color, which is the common case). Fixed by never returning
	 *  present from the visitor at all (so the traversal always runs to completion over every leaf) and
	 *  instead recording the first leaf color that's actually a MEMBER of {@link #RARITY_NAME_COLORS} — a
	 *  decorative prefix's dye color essentially never coincidentally matches one of the 9 fixed rarity RGBs,
	 *  so the real rarity-colored run (always present somewhere in the name) is still found correctly. */
	private static Integer nameColorOf(ItemStack stack) {
		Component name = stack.getHoverName();
		java.util.concurrent.atomic.AtomicReference<Integer> found = new java.util.concurrent.atomic.AtomicReference<>();
		name.visit((style, text) -> {
			if (!text.isEmpty() && style.getColor() != null) {
				int color = style.getColor().getValue();
				if (RARITY_NAME_COLORS.contains(color)) found.compareAndSet(null, color);
			}
			return java.util.Optional.<Integer>empty();
		}, Style.EMPTY);
		return found.get();
	}

	/** The rarity tier's own fixed color, if the item's lore contains a real Hypixel rarity word anywhere
	 *  (scanned backward from the last non-blank line, matching real event/menu items whose rarity tag isn't
	 *  necessarily the very last lore line — e.g. redemption/usage instructions after it). Returns null if no
	 *  line anywhere in the lore names a real rarity tier at all. */
	private static Integer loreRarityColorOf(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return null;
		List<Component> lines = lore.lines();
		for (int i = lines.size() - 1; i >= 0; i--) {
			String plain = lines.get(i).getString();
			if (plain.isBlank()) continue;
			var matcher = RARITY_TIER.matcher(plain.toUpperCase(Locale.ROOT));
			if (matcher.find()) return RARITY_TIER_COLOR.get(matcher.group(1));
		}
		return null;
	}

	/** Real design correction (per user report — "i need you to scan both names and lore text. Only if both
	 *  match the same rarity (i.e, lore says uncommon and the item name is green) should it show the
	 *  background, to make sure stuff that has the words in the lore dont get rarity backgrounded if they
	 *  dont match the name color"): neither signal alone is reliable — a rarity WORD anywhere in an item's
	 *  lore doesn't mean the item itself has that rarity (menu/event items can mention "Common"/"Rare" in a
	 *  description sentence with no structural connection to their own real rarity), and a name merely
	 *  colored the same as a rarity tier doesn't confirm it's actually a rarity item either. Requiring BOTH
	 *  to agree on the exact same tier is what actually confirms "this is genuinely a rarity item" — this is
	 *  now safe to scan the whole lore permissively again (see loreRarityColorOf's own doc comment) since a
	 *  false-positive lore word can no longer paint anything on its own; it still needs the name color to
	 *  independently agree. */
	private static Integer combinedRarityColorOf(ItemStack stack) {
		Integer nameColor = nameColorOf(stack);
		if (nameColor == null) return null;
		Integer loreColor = loreRarityColorOf(stack);
		return nameColor.equals(loreColor) ? nameColor : null;
	}

	/** Screen-aware entry point used by the container-screen render path. Per user request, the Pets menu is
	 *  the one confirmed exception to {@link #combinedRarityColorOf}: pets never carry the usual bottommost
	 *  rarity-tier lore line other gear does (no lore requirement could ever pass for them), but a pet's own
	 *  name is still colored by its real rarity — so inside that specific menu, and only for its actual
	 *  pet-icon slots ({@link #PET_MENU_SLOTS}), name color alone decides it. Every other slot in every other
	 *  screen (including non-pet slots inside the Pets menu itself, e.g. its own nav buttons) still goes
	 *  through the general combined lore+name check. */
	private static Integer rarityColorOf(AbstractContainerScreen<?> screen, int slotIndex, ItemStack stack) {
		if (PET_MENU_SLOTS.contains(slotIndex) && PETS_MENU_TITLE.matcher(screen.getTitle().getString()).matches()) {
			return nameColorOf(stack);
		}
		return combinedRarityColorOf(stack);
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
