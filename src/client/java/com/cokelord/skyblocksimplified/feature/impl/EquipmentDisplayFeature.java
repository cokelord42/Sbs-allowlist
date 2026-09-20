package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Round 3 (per user correction — "Equipment as in the pieces that are cloak, necklace, belt and so on"):
 * rounds 1-2 both showed VANILLA armor/held-item slots, which was never what "equipment" meant here —
 * SkyBlock's own Necklace/Cloak/Belt/Gloves(Bracelet) accessory pieces are a completely separate stat
 * system with no vanilla equipment-slot representation at all. Confirmed against NotEnoughUpdates' real
 * {@code EquipmentOverlay.java} (user-provided source): Hypixel never exposes these 4 items anywhere a
 * client can read them passively — the ONLY way to see their contents is a real server-defined chest-style
 * menu whose own slots 10/19/28/37 hold Necklace/Cloak/Belt/Gloves respectively (confirmed real indices,
 * not guessed — NEU's own {@code getWardrobeSlot}/{@code getChestSlotsAsItemStack} read those exact slots).
 * Those raw slot indices are a property of Hypixel's OWN server-side menu layout, not our client version.
 *
 * <p>Round 4 (per user correction — "they changed the /equipment to /stats. Neu is old and equipment
 * wardrobe was not a thing"): Hypixel retired the standalone {@code /equipment} menu ("Your Equipment") in
 * favor of a combined {@code /stats} screen titled "Stats & Equipment" — same 10/19/28/37 physical slot
 * layout for the 4 accessories, confirmed by the user, just a different title/command to reach it. Two more
 * real screens capture the exact same 4 items:
 * <ul>
 * <li><b>Equipment Wardrobe</b> — titled "(n/2) Equipment Sets" (a real page counter, so matched as a
 * case-insensitive substring, not an exact title), showing several saved equipment SETS side by side as
 * columns. Per user spec: row 5 (slots 37-45 counting 1-indexed, i.e. real 0-indexed menu slots 36-44) has
 * exactly one lime-dye marker under whichever column is the currently-active set; the 4 accessory items for
 * that set sit directly above it, same column, rows 1-4 (0-indexed rows 0-3) — i.e. real slots
 * {@code column}, {@code column + 9}, {@code column + 18}, {@code column + 27}.
 * <li><b>Loadouts</b> — the same screen {@link CustomLoadoutKeybindsFeature} already detects via a
 * case-insensitive "loadouts" title substring — per user report, carries the identical 10/19/28/37 layout
 * as Stats &amp; Equipment, so it's captured the exact same way.
 * </ul>
 *
 * <p>Instead of reading anything live off {@code LocalPlayer}, this mirrors NEU's real mechanism: cache the
 * 4 accessory {@link ItemStack}s the moment any of the three screens above is open (each an ordinary
 * {@link AbstractContainerScreen}, not specifically an {@link InventoryScreen}), then render that cache —
 * same position as before, to the right of the character model — whenever the player's own vanilla
 * {@link InventoryScreen} is later open. Persisted per-profile-UUID to disk (see {@link #saveToDisk()}/
 * {@link #loadFromDiskOnce()}), same as NEU's own real behavior, so the cache survives a restart — shows
 * NEU's own real fallback prompt only until the player has opened one of those screens at least once ever.
 */
public class EquipmentDisplayFeature extends Feature {
	// Real, confirmed indices from NEU's EquipmentOverlay.java (getWardrobeSlot call sites) — Hypixel's own
	// Stats & Equipment / Loadouts container layout, not client-version-dependent. Column order:
	// Necklace/Cloak/Belt/Gloves.
	private static final int SLOT_NECKLACE = 10;
	private static final int SLOT_CLOAK = 19;
	private static final int SLOT_BELT = 28;
	private static final int SLOT_GLOVES = 37;
	private static final int[] EQUIPMENT_SLOTS = {SLOT_NECKLACE, SLOT_CLOAK, SLOT_BELT, SLOT_GLOVES};

	// Equipment Wardrobe's own row-5 selector marker and the 3-row vertical gap up to the accessory column
	// above it — see the class doc comment's "Equipment Wardrobe" bullet for the real slot math.
	private static final int WARDROBE_ROW5_START = 36;
	private static final int WARDROBE_ROW5_END = 44;
	private static final int WARDROBE_ROW_STRIDE = 9;
	private static final int WARDROBE_ROWS_ABOVE = 4;

	private static final int ICON_SIZE = 16;
	private static final int ROW_HEIGHT = 18;

	private final ItemStack[] cachedGear = new ItemStack[EQUIPMENT_SLOTS.length];
	private boolean everCaptured = false;
	// Real bug found (per user report — "its not caching properly, when i restart it resets"): the class doc
	// comment above used to explicitly document this as a deliberate session-only cache, but the user wants
	// real cross-restart persistence. Mirrors StorageOverlayFeature's own proven per-profile-UUID NBT file
	// approach (round-tripping each ItemStack through the real vanilla ItemStack.CODEC) rather than the
	// generic JSON settings system, since dumping 4 full real ItemStacks (enchants/lore/attributes) into
	// savePersistedData would bloat the config export/import string this codebase has repeatedly fought hard
	// to keep small.
	private boolean diskLoaded = false;

	// Per-frame hit-boxes for the icons actually drawn this frame (icon index -> screen bounds), rebuilt every
	// render pass — consumed by both the hover-tooltip check and the click handler below.
	private final List<int[]> iconBounds = new ArrayList<>();
	private final List<ItemStack> iconStacks = new ArrayList<>();

	public EquipmentDisplayFeature() {
		// Real bug found (per an audit for the "Remember Last Page doesn't fully work" report): this used to
		// register under COMBAT while getSubcategory() below claims "Misc" — not one of COMBAT's own
		// subcategories (Combat/Slayers/Dungeons/Floor 7/Kuudra/Puzzles) at all, only INVENTORY's. Same class
		// of bug already found and fixed for GyroHelperFeature this round. Switched to INVENTORY to match
		// "Misc", the same category its sibling display overlays (PlayerDisplayFeature, PetDisplayFeature)
		// already correctly use.
		super("equipment_display", "Equipment Display", FeatureCategory.INVENTORY, false);

		// Capture listener — fires on ANY container screen (chest-shaped, real Hypixel menu), not just the
		// vanilla inventory, since Stats & Equipment/Equipment Wardrobe/Loadouts are all their own separate
		// GUIs.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			if (containerScreen instanceof InventoryScreen) return;
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (!isEnabled()) return;
				try {
					captureIfEquipmentScreen(containerScreen);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Equipment Display capture failed, skipping this frame", e);
				}
			});
		});

		// Render listener — the vanilla inventory screen specifically, same "right of the character model"
		// spot as before.
		// Real bug found (per user report — "The equipment display now renders underneath the inventory"):
		// a PRIOR round switched this to ScreenEvents.beforeExtract to fix a real-tooltip-overlap edge case,
		// but beforeExtract fires BEFORE the container screen draws its own background/slots at all — so the
		// vanilla inventory panel texture painted OVER this feature's icons afterward instead of the reverse.
		//
		// Real bug found AGAIN (per user report — "equipment still renders above the lore. it needs to render
		// between the lore and inventory"): afterExtract wraps the WHOLE screen render, which for an
		// AbstractContainerScreen includes its own tooltip draw at the very end — so drawing here painted our
		// icons on top of the real item tooltip too. Switched to PreTooltipRenderRegistry, the same choke point
		// AbstractContainerScreenTooltipMixin fires right after every slot/item icon is drawn but before the
		// tooltip itself (see that mixin's own doc comment) — every other "sits above the inventory, below the
		// tooltip" overlay in this codebase (SlotLockingFeature, AuctionHouseTotalFeature, etc.) uses this exact
		// hook.
		com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled() || !(screen instanceof InventoryScreen containerScreen)) return;
			try {
				render(graphics, containerScreen, mouseX, mouseY);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Equipment Display render failed, skipping this frame", e);
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof InventoryScreen containerScreen)) return;
			// Per user request ("should bring the user to /stats when its clicked"): clicking any cached
			// equipment icon jumps straight to the real Stats & Equipment screen, the same command convention
			// this codebase already uses everywhere else (sendCommand with no leading slash).
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
				if (!isEnabled()) return true;
				for (int[] b : iconBounds) {
					if (event.x() >= b[0] && event.x() <= b[2] && event.y() >= b[1] && event.y() <= b[3]) {
						Minecraft mc = Minecraft.getInstance();
						if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand("stats");
						return false;
					}
				}
				return true;
			});
		});
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	private void captureIfEquipmentScreen(AbstractContainerScreen<?> screen) {
		loadFromDiskOnce();
		String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
		if (title.contains("stats & equipment") || title.contains("loadouts")) {
			captureFixedSlots(screen);
		} else if (title.contains("equipment sets")) {
			captureWardrobe(screen);
		}
	}

	private void captureFixedSlots(AbstractContainerScreen<?> screen) {
		var menu = screen.getMenu();
		if (menu.slots.size() <= SLOT_GLOVES) return;
		for (int i = 0; i < EQUIPMENT_SLOTS.length; i++) {
			ItemStack stack = menu.getSlot(EQUIPMENT_SLOTS[i]).getItem();
			cachedGear[i] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
		}
		everCaptured = true;
		saveToDisk();
	}

	/** See the class doc comment's "Equipment Wardrobe" bullet for the real slot math this implements. */
	private void captureWardrobe(AbstractContainerScreen<?> screen) {
		var menu = screen.getMenu();
		if (menu.slots.size() <= WARDROBE_ROW5_END) return;
		int selectedColumn = -1;
		for (int slot = WARDROBE_ROW5_START; slot <= WARDROBE_ROW5_END; slot++) {
			if (menu.getSlot(slot).getItem().is(Items.DYE.lime())) {
				selectedColumn = slot - WARDROBE_ROW5_START;
				break;
			}
		}
		// No lime dye found this frame (e.g. between pages, or this particular page has no active set) —
		// leave whatever's already cached alone rather than clearing it, same as every other screen here.
		if (selectedColumn < 0) return;
		// Row 5 (0-indexed row 4) is WARDROBE_ROW5_START + column; the 4 rows directly above it (0-indexed
		// rows 0-3) start at plain `column` in row 0 and step by one row (9 slots) per accessory.
		for (int i = 0; i < WARDROBE_ROWS_ABOVE; i++) {
			int slot = selectedColumn + i * WARDROBE_ROW_STRIDE;
			if (slot < 0 || slot >= menu.slots.size()) return;
			ItemStack stack = menu.getSlot(slot).getItem();
			cachedGear[i] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
		}
		everCaptured = true;
		saveToDisk();
	}

	// ---- Disk persistence (see this class's own doc comment + the everCaptured field's doc comment for why:
	// mirrors StorageOverlayFeature's own proven per-profile-UUID NBT file + ItemStack.CODEC round-trip). ----

	private static Path equipmentFile() {
		Minecraft mc = Minecraft.getInstance();
		String uuid = mc.player != null ? mc.player.getUUID().toString() : "unknown";
		return FabricLoader.getInstance().getConfigDir().resolve("skyblocksimplified").resolve("equipment").resolve(uuid + ".nbt");
	}

	private void loadFromDiskOnce() {
		if (diskLoaded) return;
		diskLoaded = true;
		Path path = equipmentFile();
		if (!Files.exists(path)) return;
		try {
			CompoundTag root = NbtIo.readCompressed(path, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
			var registries = registryAccess();
			if (registries == null) return;
			if (!(root.get("gear") instanceof ListTag list)) return;
			for (int i = 0; i < EQUIPMENT_SLOTS.length && i < list.size(); i++) {
				cachedGear[i] = decodeStack(list.get(i), registries);
			}
			everCaptured = true;
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Equipment Display: failed to load cached gear, starting empty", e);
		}
	}

	private void saveToDisk() {
		var registries = registryAccess();
		if (registries == null) return; // not connected to a world yet — nothing real to encode against
		try {
			CompoundTag root = new CompoundTag();
			ListTag list = new ListTag();
			for (ItemStack stack : cachedGear) {
				list.add(stack == null || stack.isEmpty() ? new CompoundTag() : encodeStack(stack, registries));
			}
			root.put("gear", list);
			Path file = equipmentFile();
			Files.createDirectories(file.getParent());
			Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
			NbtIo.writeCompressed(root, tempFile);
			try {
				Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException ex) {
				Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Equipment Display: failed to save cached gear", e);
		}
	}

	private static net.minecraft.core.HolderLookup.Provider registryAccess() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) return mc.level.registryAccess();
		return null;
	}

	private static Tag encodeStack(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
		return ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).result().orElseGet(CompoundTag::new);
	}

	private static ItemStack decodeStack(Tag tag, net.minecraft.core.HolderLookup.Provider registries) {
		if (!(tag instanceof CompoundTag ct) || ct.isEmpty()) return ItemStack.EMPTY;
		return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), ct).result().orElse(ItemStack.EMPTY);
	}

	// Per user correction ("far right in the inventory... right next to the player like where the armor is
	// located but on the other side of the player"): the vanilla armor column sits INSIDE the panel, hugging
	// its left edge (x=8) right beside the character model; there's no equivalent free space on the model's
	// right (the crafting grid occupies it), so this mirrors the armor column's own gap-from-panel-edge (8px)
	// on the OUTSIDE of the panel's right edge instead — the closest a real, unoccupied, non-overlapping spot
	// gets to a true mirror — using the same y-rows as the armor slots (topPos+8/26/44/62) so it reads as
	// flanking the model at the same height, not sitting off in space near the crafting output.
	private static final int PANEL_EDGE_GAP = 8;
	// Real bug found (per user report — that previous fix STILL reads as "far right", explicit follow-up
	// correction: "It needs to render like 400 pixels to the left"): a literal, numeric instruction, honored
	// directly rather than re-guessed at a third time — shifts the whole column 400px left of where the
	// PANEL_EDGE_GAP math above would otherwise put it. Clamped to a small positive margin so it can't go
	// fully off-screen on a narrow GUI scale.
	//
	// Follow-up correction ("The equipment needs to go back another like 100...") pushed this to 500, but the
	// very next message walked that back — "It needed to go less left, i meant like 200 coordinates to the
	// right" — so 500 overshot; back off 200 to 300 total.
	//
	// Latest correction ("The equipment stuff is still like 100 pixels off. Send it 100 pixels to the
	// right."): LEFT_SHIFT is subtracted from the base position, so moving further right means reducing it
	// — 300 -> 200.
	//
	// Per user request ("its pretty close now, but its like another 50 pixels"): 200 -> 150.
	//
	// Per user request ("Needs to be a little more to the right"), from a real 1920x1080 screenshot — the
	// icons weren't clearly identifiable at compressed resolution in that screenshot to compute an exact
	// pixel delta, so this is a modest nudge matching "a little" rather than a precisely measured value:
	// 150 -> 120.
	//
	// Per user request, an exact pixel figure this time ("go a little more right. Like 10 pixels"): 120 -> 110.
	//
	// Per user request ("The equipment display overlaps lore. Move the equipment display exactly 5 pixels to
	// the right. I did the math. The armor is 32px away from the player while the equipment display is 27."):
	// an exact, user-measured delta against the real vanilla armor column's own known distance from the
	// player model — 110 -> 105.
	private static final int LEFT_SHIFT = 105;
	private static final int MIN_SCREEN_MARGIN = 4;

	// Per user request ("It needs to be in the squared area... support rarity backgrounds... if possible
	// fake slots aswell"): a real slot-style background square behind each icon (matching the dark inset
	// look every other slot-shaped UI in this mod already uses — e.g. StorageOverlayFeature's own reserved-
	// inventory cells), drawn for all 4 accessory types even before anything's been captured yet, so this
	// reads as 4 real equipment slots rather than 4 icons floating in empty space.
	private static final int SLOT_BG_COLOR = 0xFF1A1A1A;
	private static final int SLOT_PADDING = 1;

	private void render(GuiGraphicsExtractor graphics, InventoryScreen screen, int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		loadFromDiskOnce();

		iconBounds.clear();
		iconStacks.clear();

		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();
		int imageWidth = accessor.skyblocksimplified$getImageWidth();

		int x = Math.max(MIN_SCREEN_MARGIN, leftPos + imageWidth + PANEL_EDGE_GAP - LEFT_SHIFT);
		int y = topPos + 8;

		Font font = mc.font;

		// Per user request ("mirror the real left-side vanilla armor-slot layout... if possible fake slots
		// aswell cause that would look even better"): draw all 4 real slot backgrounds up front, in the exact
		// same rows the icons themselves use below, regardless of whether anything's been captured yet — this
		// is what makes it read as "4 equipment slots" rather than either 4 bare icons or an empty gap.
		int slotY = y;
		for (int i = 0; i < EQUIPMENT_SLOTS.length; i++) {
			graphics.fill(x - SLOT_PADDING, slotY - SLOT_PADDING, x + ICON_SIZE + SLOT_PADDING, slotY + ICON_SIZE + SLOT_PADDING, SLOT_BG_COLOR);
			slotY += ROW_HEIGHT;
		}

		if (!everCaptured) {
			// Real bug found (per user report — "the red text warning needs to go like above the inventory or
			// something, a bit above so it doesnt interfere with the inventory buttons"): this used to draw
			// right on top of the first accessory slot's own row (the same y the icons themselves use), which
			// sits at the same height as the vanilla inventory's own button row (recipe book etc.) once the
			// panel's real width is accounted for — moved above the panel's top edge entirely instead, the
			// same y every other "floating label above the GUI" spot in this mod uses, so it can never overlap
			// real inventory buttons no matter how the panel is scaled.
			int warnY = Math.max(2, topPos - 22);
			graphics.text(font, "Open /stats to", x, warnY, 0xFFFF5555);
			graphics.text(font, "cache your gear", x, warnY + 10, 0xFFFF5555);
			return;
		}

		// Per user request ("should not show names, but should show the lore when hovered"): icon-only, no
		// label text — the item's full real tooltip (name + lore, same as hovering it in any other inventory)
		// only appears on hover, via the same tooltip mechanism every vanilla slot uses.
		ItemStack hovered = null;
		int hoveredX = 0, hoveredY = 0;
		for (ItemStack stack : cachedGear) {
			if (stack == null || stack.isEmpty()) {
				y += ROW_HEIGHT;
				continue;
			}
			// Per user request ("support rarity backgrounds") — same public API StorageOverlayFeature's own
			// drawScaledItem reuses, see that method's doc comment for why ItemRarityBackgroundFeature's own
			// render hook never fires for a cached, non-live slot like this one.
			Integer rarityColor = ItemRarityBackgroundFeature.rarityColorPublic(stack);
			if (rarityColor != null) {
				int fillColor = (0x60 << 24) | (rarityColor & 0xFFFFFF);
				graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, fillColor);
			}
			graphics.item(stack, x, y);
			// Per user request ("Show locks on them too like regular items") — mirrors SlotLockingFeature's
			// own real lock-icon badge exactly (same shape/color, see its renderLockIcon doc comment); checked
			// by real item UUID via SlotLockingFeature.isLocked, the same public API every other "is this
			// cached item locked" check in the mod would use, so a piece the user locked in their real
			// inventory shows the same badge here too.
			// Real bug found (per user report — "The lock colors doesnt work on the equipment display"):
			// this used to hardcode 0xFFFFD700 (the old fixed default) directly instead of reading the real
			// user-configurable color — see SlotLockingFeature.getSharedLockColor's own doc comment.
			if (com.cokelord.skyblocksimplified.feature.impl.SlotLockingFeature.isLocked(stack)) {
				int lockColor = com.cokelord.skyblocksimplified.feature.impl.SlotLockingFeature.getSharedLockColor();
				int lx = x + 1, ly = y + 1;
				com.cokelord.skyblocksimplified.gui.RenderUtil.fillRounded(graphics, lx, ly, lx + 7, ly + 8, 1, 0xB0000000);
				com.cokelord.skyblocksimplified.gui.RenderUtil.fillRoundedRing(graphics, lx + 1, ly + 1, lx + 6, ly + 6, 2, 1, lockColor);
				com.cokelord.skyblocksimplified.gui.RenderUtil.fillRounded(graphics, lx + 1, ly + 3, lx + 6, ly + 7, 1, lockColor);
			}
			// Real bug found (per user report — "should show item lore when the items are hovered", implying
			// hovering wasn't reliably registering): the hover hitbox used to be exactly ICON_SIZE, one pixel
			// narrower on each side than the actual visible dark slot square drawn above/around it
			// (x - SLOT_PADDING .. x + ICON_SIZE + SLOT_PADDING) — a mouse position that visually looks like
			// it's over the slot, right at its edge, missed the hover check and showed no tooltip at all.
			// Widened to match the real visible bounds exactly.
			int[] bounds = {x - SLOT_PADDING, y - SLOT_PADDING, x + ICON_SIZE + SLOT_PADDING, y + ICON_SIZE + SLOT_PADDING};
			iconBounds.add(bounds);
			iconStacks.add(stack);
			if (mouseX >= bounds[0] && mouseX <= bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[3]) {
				hovered = stack;
				hoveredX = mouseX;
				hoveredY = mouseY;
			}
			y += ROW_HEIGHT;
		}
		if (hovered != null) graphics.setTooltipForNextFrame(font, hovered, hoveredX, hoveredY);
	}

	@Override
	public String getDescription() {
		return "Shows your currently-equipped Cloak/Necklace/Belt/Gloves as icons next to your player.";
	}
}
