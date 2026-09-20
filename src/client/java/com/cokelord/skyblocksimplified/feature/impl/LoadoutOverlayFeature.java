package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.highlight.ScreenRenderCancelRegistry;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.util.SkyblockItemIcons;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage-Overlay-style card grid over Hypixel's real Loadouts menu. {@link ScreenRenderCancelRegistry}
 * cancels the real vanilla screen's own render while this panel is visible, and every click here is routed
 * through the real {@code MultiPlayerGameMode#handleContainerInput} pipeline (via
 * {@link ContainerClickRegistry} first, same as {@link StorageOverlayFeature}) rather than faking anything
 * client-side — clicking a card really does equip/edit that loadout on Hypixel's own server.
 *
 * <p>Per user correction ("Literally just display the slots that the keybinds use. Thats all. Use those
 * slots instead of searching for anything, and hide the ones that are red dye"): every previous round of this
 * class tried to derive which slots hold real loadouts generically (a full-container scan, then a lore-based
 * "has all 4 armor pieces" filter) and kept either under- or over-matching against real unrelated menu items
 * (skill icons, accessory pieces, a pet display) that happened to share a tracked keyword. The fix is to stop
 * guessing entirely: {@link CustomLoadoutKeybindsFeature#loadoutSlots()} is the exact same real, live-tested
 * 4x3 slot layout that feature's own keybinds already click successfully, so this class now reads directly off
 * that array instead of scanning anything. A locked (not yet unlocked) loadout slot renders as a real
 * {@code RED_DYE} placeholder item — checking the item type directly is simpler and more robust than the old
 * hover-name regex, which is kept as a fallback in case some accounts still show the old locked wording.
 *
 * <p>Per user correction with exact real slot numbers ("The slot for next page is actually slot 45 not 54 as
 * i imagined. The slot for the arrow for the previous page is 18, but it only shows up on the second page"):
 * page navigation is now two fixed slot indices ({@link #NEXT_PAGE_SLOT}, {@link #PREV_PAGE_SLOT}) instead of
 * a generic hover-name scan — whichever one is empty on the current page (there's no "previous" on page 1,
 * for instance) simply doesn't get an arrow drawn for it.
 *
 * <p>Per user request ("Remove the little pencil since you can just right click them"): there is no longer a
 * separate pencil hit-box — a real right-click (button 1) anywhere on a card sends Hypixel's own real "edit
 * this loadout" input directly to that slot, and a left-click selects/equips it, mirroring how right-clicking
 * a real Loadouts slot already behaves without this overlay open at all.
 *
 * <p>Per user correction ("The lore shows the specific gear... displayed like Helmet: 'Young Dragon Helmet'
 * and Chestplate: 'Young Dragon Chestplate'"): each loadout icon's own lore carries real per-piece gear names
 * — {@link #parseGearFromLore} reads those "Category: \"Item Name\"" lines and resolves each real item name
 * back to an actual {@link ItemStack} icon via {@link SkyblockItemRepo#findIdByName} +
 * {@link SkyblockItemIcons#getIcon}, the same real Hypixel-item-repo pipeline Personal Compactor/Quick Action
 * Buttons already use elsewhere in this codebase. The card's main icon becomes the real resolved Helmet piece
 * (a player-head-skin texture for the many Hypixel helmets that are reskinned heads), with a "Full &lt;Set
 * Name&gt;" text label ({@link #computeSetName}) and a small yellow Pet badge near the icon when the lore
 * names a real, resolvable pet, per the user's own hand-drawn mockup.
 *
 * <p>Per user request ("make sure the loadout highlighting system disables itself if the overlay is
 * active"): {@link #isOverlayActive()} is a public static check {@link CustomLoadoutKeybindsFeature}'s own
 * highlight render hook consults to skip drawing its own green box while this overlay is up, since this
 * overlay's own per-card green tint already covers the same "currently selected" information.
 *
 * <p>Per user report ("clicking one is like clicking them normally... they should not get removed from the
 * menu" / "using a keybind in the custom loadouts menu also makes it disappear for a second"): a real click
 * on a real Hypixel menu slot — whether sent by this overlay's own routing or by
 * {@link CustomLoadoutKeybindsFeature}'s keybind, either way a genuine left-click {@code PICKUP} packet, the
 * same real input the account's own mouse click would send — has a brief server round-trip during which the
 * real container slot legitimately reports empty before Hypixel resends the updated contents. Every OTHER
 * screen never shows this since vanilla's own local prediction fills that gap instantly, but this overlay
 * reads {@code screen.getMenu().slots} fresh every single frame with no prediction of its own, so that one
 * genuinely-empty frame was enough to flash the whole card away. {@link #effectiveIcon} smooths this out
 * generically (regardless of what caused the transient emptiness) by remembering each slot's last real,
 * non-empty icon and substituting it back in for a short grace window whenever the live read comes back
 * empty — real gear/right-click edits and genuinely equipping-out a loadout for real still update fine,
 * they just don't flicker through a blank frame first. (An earlier round tried switching the select click to
 * a middle-click-style {@code CLONE} input instead, on the theory this was vanilla's own pickup prediction —
 * that theory was wrong, per the user's own follow-up that CLONE didn't even select the loadout at all;
 * reverted back to a real {@code PICKUP} click, same as {@link CustomLoadoutKeybindsFeature} already uses.)
 */
public class LoadoutOverlayFeature extends Feature {
	private static final Pattern LOCKED_SLOT_PATTERN = Pattern.compile("^Loadout \\d+ Locked$");

	// Per user report ("the right click to edit doesnt work... it should close the custom gui and send me to
	// the actual hypixel menu that opens when i edit a loadout"): a loose "contains loadouts" check kept
	// matching whatever real Hypixel sub-screen opens after a genuine right-click edit too, so this overlay
	// kept rendering/intercepting clicks over it instead of stepping aside. Tightened to only the exact real
	// "Loadouts" menu title (optionally prefixed with a real "(n/m)" page indicator, same as every other
	// paginated Hypixel menu) so any other screen falls straight through to vanilla.
	private static final Pattern LOADOUTS_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\)\\s*)?Loadouts$", Pattern.CASE_INSENSITIVE);

	// Per user-supplied exact real slot numbers (originally 45/18, corrected -1 per user follow-up), THEN
	// swapped per a further user report ("Left arrow still shows on first page and right arrow shows on
	// second" — the exact mirror image of the intended "next shows on page 1, previous shows on page 2"):
	// the two real slot numbers themselves were always right, but which one is the NEXT-page arrow vs the
	// PREVIOUS-page arrow had been assumed backwards since the very first round that introduced this fixed-
	// slot approach. The "previous" arrow only has a real item in it once there's an actual earlier page to
	// go back to; "next" only has one while there's a further page ahead.
	private static final int NEXT_PAGE_SLOT = 17;
	private static final int PREV_PAGE_SLOT = 44;

	// Per user report — real lore lines are exactly "Category: \"Item Name\"" (e.g. Helmet: "Young Dragon
	// Helmet").
	private static final Pattern GEAR_LORE_LINE = Pattern.compile("^(\\w+): \"(.+)\"$");
	private static final List<String> GEAR_CATEGORIES = List.of(
		"Helmet", "Chestplate", "Leggings", "Boots", "Necklace", "Cloak", "Belt", "Pet");

	private static final int MIN_COLUMNS = 1;
	private static final int MAX_COLUMNS = 6;
	private static final int DEFAULT_COLUMNS = 6;
	private static final int CARD_GAP = 10;
	private static final int CARD_PADDING = 6;
	private static final int HEADER_HEIGHT = 14;
	private static final int LABEL_HEIGHT = 12;
	private static final float SCROLL_SPEED = 24f;
	private static final long CLICK_COOLDOWN_MS = 150L;

	private int columns = DEFAULT_COLUMNS;
	private int sizeScale = 4;
	private boolean showBackground = true;

	private boolean panelVisible = false;
	private int panelX, panelY, panelWidth, panelHeight;
	private float scrollOffset = 0f;
	private int contentHeight = 0;

	private final List<int[]> cardBounds = new ArrayList<>();
	private final List<Integer> cardSlotIndex = new ArrayList<>();
	private Integer nextPageSlot, prevPageSlot;
	private int[] nextArrowBounds, prevArrowBounds;

	private ItemStack hoveredTooltipStack = ItemStack.EMPTY;
	private int hoveredTooltipX, hoveredTooltipY;
	private long lastContainerClickMs = 0L;

	// Per user report on the click-removal flicker — see this class's own doc comment. Grace window a slot's
	// last known real icon is substituted back in for once the live read comes back empty.
	private static final long DISAPPEAR_GRACE_MS = 500L;
	private final Map<Integer, ItemStack> lastKnownIcon = new java.util.HashMap<>();
	private final Map<Integer, Long> lastNonEmptyMs = new java.util.HashMap<>();

	private static LoadoutOverlayFeature instance;

	public LoadoutOverlayFeature() {
		super("loadout_overlay", "Loadout Overlay", FeatureCategory.INVENTORY, false);
		instance = this;

		ScreenRenderCancelRegistry.setRule("loadout_overlay", screen -> isEnabled() && panelVisible);

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			if (!isEnabled()) return;
			// Per user request ("It should open on page 1"): a fresh screen open always starts scrolled to
			// the top of whatever the real menu currently shows (Hypixel itself always opens Loadouts on its
			// own page 1), so this only ever needs to reset OUR OWN leftover scroll position from a previous
			// open, not track "page 1" as separate state of its own.
			scrollOffset = 0f;

			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (!isEnabled()) return;
				try {
					// Re-checked every frame (not just once on screen open) since a real Hypixel next-page
					// click reuses this same screen instance and just refreshes its title/slots in place —
					// the same "GUI content can change without a new screen instance" pattern StorageOverlay-
					// Feature's own class doc comment (Round 4) already documents for this exact engine.
					String title = containerScreen.getTitle().getString().trim();
					panelVisible = LOADOUTS_TITLE.matcher(title).matches();
					if (!panelVisible) return;
					render(graphics, mouseX, mouseY, containerScreen);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Loadout Overlay render failed, skipping this frame", e);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
				if (!isEnabled() || !panelVisible) return true;
				return handleClick(containerScreen, event);
			});
			ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
				if (!isEnabled() || !panelVisible) return true;
				return false;
			});
			ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
				if (!isEnabled() || !panelVisible) return true;
				if (mouseX < panelX || mouseX > panelX + panelWidth || mouseY < panelY || mouseY > panelY + panelHeight) return true;
				float maxScroll = Math.max(0, contentHeight - (panelHeight - CARD_PADDING * 2));
				scrollOffset = Math.max(0f, Math.min(maxScroll, scrollOffset - (float) verticalAmount * SCROLL_SPEED));
				return false;
			});
			ScreenEvents.remove(screen).register(s -> {
				panelVisible = false;
				cardBounds.clear();
				cardSlotIndex.clear();
				nextPageSlot = null;
				prevPageSlot = null;
				nextArrowBounds = null;
				prevArrowBounds = null;
				lastKnownIcon.clear();
				lastNonEmptyMs.clear();
			});
		});
	}

	@Override
	public String getSubcategory() {
		return "Inventory";
	}

	public int getColumns() { return columns; }
	public void setColumns(int value) { columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, value)); }
	public int getSizeScale() { return sizeScale; }
	public void setSizeScale(int value) { sizeScale = Math.max(2, Math.min(4, value)); }
	public boolean isShowBackground() { return showBackground; }
	public void setShowBackground(boolean value) { showBackground = value; }

	/** See this class's own doc comment on why {@link CustomLoadoutKeybindsFeature} consults this. */
	public static boolean isOverlayActive() {
		return instance != null && instance.isEnabled() && instance.panelVisible;
	}

	/** Per user correction ("hide the ones that are red dye"): a locked loadout slot's real item is a plain
	 *  {@code RED_DYE} placeholder — checking the item type directly is simpler and more robust than parsing
	 *  hover-name text. The old "Loadout N Locked" hover-name pattern is kept as a fallback. */
	private static boolean isLockedLoadout(ItemStack icon) {
		return icon.is(Items.DYE.red()) || LOCKED_SLOT_PATTERN.matcher(icon.getHoverName().getString()).matches();
	}

	/** Real per-piece gear names off the loadout icon's own lore — see this class's own doc comment for the
	 *  exact "Category: \"Item Name\"" line format this reads. */
	private static Map<String, String> parseGearFromLore(ItemStack icon) {
		Map<String, String> result = new LinkedHashMap<>();
		ItemLore lore = icon.get(DataComponents.LORE);
		if (lore == null) return result;
		for (Component line : lore.lines()) {
			Matcher m = GEAR_LORE_LINE.matcher(line.getString().trim());
			if (m.matches() && GEAR_CATEGORIES.contains(m.group(1))) result.put(m.group(1), m.group(2));
		}
		return result;
	}

	/** The common "<Set Name>" shared by the four core armor pieces, for the card's "Full <Set Name>" label
	 *  per the user's own hand-drawn mockup. Each piece's real name ends in its own category word (e.g. "Young
	 *  Dragon Helmet"); stripping that word from the Helmet piece (the single most decoration-relevant one)
	 *  is used directly, since a loadout slot isn't guaranteed to always carry all four core pieces. Empty if
	 *  there's no Helmet line to work from. */
	private static String computeSetName(Map<String, String> gear) {
		return stripCategoryWord(gear.get("Helmet"), "Helmet");
	}

	private static String stripCategoryWord(String pieceName, String category) {
		if (pieceName == null) return "";
		String trimmed = pieceName.trim();
		if (trimmed.endsWith(category)) {
			return trimmed.substring(0, trimmed.length() - category.length()).trim();
		}
		return trimmed;
	}

	/** Real item-name -> icon resolve via the same Hypixel-item-repo pipeline Personal Compactor/Quick Action
	 *  Buttons already use — see this class's own doc comment. Empty (not null) on any failure to resolve, so
	 *  callers can check {@link ItemStack#isEmpty()} without a separate null check. */
	private static ItemStack resolveGearIcon(String name) {
		if (name == null) return ItemStack.EMPTY;
		String id = SkyblockItemRepo.findIdByName(name);
		if (id == null) return ItemStack.EMPTY;
		ItemStack icon = SkyblockItemIcons.getIcon(id);
		return icon != null ? icon : ItemStack.EMPTY;
	}

	/** Same confirmed real detection {@link CustomLoadoutKeybindsFeature#isCurrentlySelectedLoadout} uses —
	 *  see that method's own doc comment for the SkyHanni source this was ported from. */
	private static boolean isCurrentlySelectedLoadout(ItemStack icon) {
		ItemLore lore = icon.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) {
				String text = line.getString();
				if (text.contains("Left-click to equip!") || text.contains("You must customize this loadout")) return false;
			}
		}
		return true;
	}

	/** See this class's own doc comment on the click-removal flicker — returns the slot's live icon, unless
	 *  it just went empty within {@link #DISAPPEAR_GRACE_MS} of last being real, in which case the last real
	 *  icon is substituted back in instead. */
	private ItemStack effectiveIcon(int slotIndex, ItemStack liveIcon) {
		long now = System.currentTimeMillis();
		if (!liveIcon.isEmpty()) {
			lastKnownIcon.put(slotIndex, liveIcon.copy());
			lastNonEmptyMs.put(slotIndex, now);
			return liveIcon;
		}
		Long lastSeen = lastNonEmptyMs.get(slotIndex);
		if (lastSeen != null && now - lastSeen < DISAPPEAR_GRACE_MS) {
			ItemStack cached = lastKnownIcon.get(slotIndex);
			if (cached != null) return cached;
		}
		return liveIcon;
	}

	private void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();
		hoveredTooltipStack = ItemStack.EMPTY;

		int margin = 12;
		int maxPanelWidth = screenWidth - margin * 2;
		int maxPanelHeight = screenHeight - margin * 2;
		// Same "Size" scaling technique as StorageOverlayFeature.render — sizeScale/4 shrinks the whole
		// window (not just the cards inside it) so the panel itself visibly gets smaller too.
		float windowScale = sizeScale / 4f;
		panelWidth = Math.round(maxPanelWidth * windowScale);
		panelHeight = Math.round(maxPanelHeight * windowScale);
		panelX = margin + (maxPanelWidth - panelWidth) / 2;
		panelY = margin + (maxPanelHeight - panelHeight) / 2;

		// Per user mockup: a plain card — main icon, a "Full <Set Name>" label, and a small Pet badge.
		int naturalIconSize = Math.max(8, 16 * sizeScale);
		int naturalCardWidth = naturalIconSize + CARD_PADDING * 2;
		int naturalCardHeight = HEADER_HEIGHT + naturalIconSize + LABEL_HEIGHT + CARD_PADDING;
		int availableWidth = Math.max(naturalCardWidth, panelWidth - CARD_PADDING * 2);
		int naturalRowWidth = naturalCardWidth * columns + CARD_GAP * (columns - 1);

		int cardWidth, cardHeight, iconSize;
		if (naturalRowWidth <= availableWidth) {
			cardWidth = naturalCardWidth;
			cardHeight = naturalCardHeight;
			iconSize = naturalIconSize;
		} else {
			cardWidth = Math.max(40, (availableWidth - CARD_GAP * (columns - 1)) / columns);
			iconSize = Math.max(8, cardWidth - CARD_PADDING * 2);
			cardHeight = HEADER_HEIGHT + iconSize + LABEL_HEIGHT + CARD_PADDING;
		}

		if (showBackground) {
			graphics.fill(0, 0, screenWidth, screenHeight, 0xD0060606);
		}
		graphics.enableScissor(panelX, panelY, panelX + panelWidth, panelY + panelHeight);

		cardBounds.clear();
		cardSlotIndex.clear();

		List<Slot> allSlots = screen.getMenu().slots;
		ItemStack nextIcon = NEXT_PAGE_SLOT < allSlots.size() ? effectiveIcon(NEXT_PAGE_SLOT, allSlots.get(NEXT_PAGE_SLOT).getItem()) : ItemStack.EMPTY;
		ItemStack prevIcon = PREV_PAGE_SLOT < allSlots.size() ? effectiveIcon(PREV_PAGE_SLOT, allSlots.get(PREV_PAGE_SLOT).getItem()) : ItemStack.EMPTY;
		nextPageSlot = !nextIcon.isEmpty() ? NEXT_PAGE_SLOT : null;
		prevPageSlot = !prevIcon.isEmpty() ? PREV_PAGE_SLOT : null;

		// Per user correction ("Literally just display the slots that the keybinds use. Thats all. Use those
		// slots instead of searching for anything"): the exact same real, live-tested slot layout
		// CustomLoadoutKeybindsFeature's own keybinds already click successfully — no scanning needed.
		List<Integer> visibleSlots = new ArrayList<>();
		for (int slotIndex : CustomLoadoutKeybindsFeature.loadoutSlots()) {
			if (slotIndex >= allSlots.size()) continue;
			ItemStack icon = effectiveIcon(slotIndex, allSlots.get(slotIndex).getItem());
			if (icon.isEmpty()) continue;
			if (isLockedLoadout(icon)) continue;
			visibleSlots.add(slotIndex);
		}

		// Per user request ("make them render in the center and center the most center pixel between all the
		// loadouts"): the grid's own total height is computed up front so the whole block of cards can be
		// vertically centered in the panel — the old code always started drawing from the panel's own top
		// edge, which left a short list of loadouts visibly hugging the top instead of sitting in the middle.
		// Only centers when everything actually fits without scrolling; once there's more content than the
		// panel can show, it falls back to the previous top-anchored + scroll-offset behavior since a
		// "centered" scroll position has no well-defined meaning.
		int numRows = columns > 0 ? (visibleSlots.size() + columns - 1) / columns : 0;
		int unscrolledContentHeight = numRows > 0 ? numRows * cardHeight + (numRows - 1) * CARD_GAP : 0;
		int availableContentHeight = panelHeight - CARD_PADDING * 2;
		int col = 0;
		int cardY = unscrolledContentHeight <= availableContentHeight
			? panelY + (panelHeight - unscrolledContentHeight) / 2
			: panelY + CARD_PADDING - Math.round(scrollOffset);
		for (int i = 0; i < visibleSlots.size(); i++) {
			boolean lastInRow = col == columns - 1 || i == visibleSlots.size() - 1;
			if (lastInRow) {
				int cardsInRow = col + 1;
				int rowWidth = cardWidth * cardsInRow + CARD_GAP * (cardsInRow - 1);
				int rowStartX = panelX + (panelWidth - rowWidth) / 2;
				int rx = rowStartX;
				for (int j = i - col; j <= i; j++) {
					int slotIndex = visibleSlots.get(j);
					if (cardY + cardHeight >= panelY && cardY <= panelY + panelHeight) {
						drawLoadoutCard(graphics, font, effectiveIcon(slotIndex, allSlots.get(slotIndex).getItem()), rx, cardY, cardWidth, cardHeight, iconSize, mouseX, mouseY);
					}
					cardBounds.add(new int[]{rx, cardY, rx + cardWidth, cardY + cardHeight});
					cardSlotIndex.add(slotIndex);
					rx += cardWidth + CARD_GAP;
				}
				cardY += cardHeight + CARD_GAP;
				col = 0;
			} else {
				col++;
			}
		}
		graphics.disableScissor();

		contentHeight = unscrolledContentHeight;
		float maxScroll = Math.max(0, contentHeight - availableContentHeight);
		scrollOffset = Math.max(0f, Math.min(maxScroll, scrollOffset));

		// Per user request ("Add an arrow where clicking it switches the loadout pages... Add an arrow back
		// aswell"): only drawn on whichever end this page actually has a real nav item at its fixed slot.
		nextArrowBounds = nextPageSlot != null ? drawNavArrow(graphics, font, screenWidth, screenHeight, mouseX, mouseY, true) : null;
		prevArrowBounds = prevPageSlot != null ? drawNavArrow(graphics, font, screenWidth, screenHeight, mouseX, mouseY, false) : null;

		if (!hoveredTooltipStack.isEmpty()) {
			RenderUtil.renderItemTooltip(graphics, font, hoveredTooltipStack, hoveredTooltipX, hoveredTooltipY);
		}

		// See StorageOverlayFeature.render's own doc comment on this same trick — cancelling the real
		// vanilla screen's render also cancels its own cursor-carried-item draw.
		AbstractContainerMenu menu = screen.getMenu();
		ItemStack carried = menu.getCarried();
		if (!carried.isEmpty()) graphics.item(carried, mouseX - 8, mouseY - 8);
	}

	private void drawLoadoutCard(GuiGraphicsExtractor graphics, Font font, ItemStack icon, int x, int y, int width, int height, int iconSize, int mouseX, int mouseY) {
		boolean selected = isCurrentlySelectedLoadout(icon);
		boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
		int bg = selected ? 0xE01D4D24 : (hovered ? 0xE0222222 : 0xE0161616);
		RenderUtil.fillRounded(graphics, x, y, x + width, y + height, 6, bg);
		if (selected) RenderUtil.fillRoundedRing(graphics, x, y, x + width, y + height, 6, 2, 0xFF55FF55);

		String name = icon.getHoverName().getString();
		int nameWidth = font.width(name);
		// Long real loadout names (a player-renamed loadout) can exceed a small card's width — shrink the
		// draw scale down rather than letting it overflow into a neighboring card.
		float nameScale = nameWidth > width - 6 ? Math.max(0.5f, (width - 6) / (float) nameWidth) : 1f;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + width / 2f, y + 4);
		graphics.pose().scale(nameScale);
		graphics.text(font, name, Math.round(-nameWidth / 2f), 0, 0xFFEEEEEE);
		graphics.pose().popMatrix();

		// Per user mockup: a plain main icon (the real Helmet piece — a player-head-skin texture for most
		// decorative Hypixel helmets, falling back to the loadout's own icon), a "Full <Set Name>" label, and
		// a small Pet badge near the icon.
		Map<String, String> gear = parseGearFromLore(icon);
		ItemStack helmetIcon = resolveGearIcon(gear.get("Helmet"));
		ItemStack mainIcon = !helmetIcon.isEmpty() ? helmetIcon : icon;

		int iconX = x + Math.max(0, (width - iconSize) / 2);
		int iconY = y + HEADER_HEIGHT + Math.max(0, (height - HEADER_HEIGHT - LABEL_HEIGHT - iconSize) / 2);
		drawItemIcon(graphics, font, mainIcon, iconX, iconY, iconSize);
		// Tooltip always shows the real loadout icon's own full lore (every gear line at once), not just
		// whichever piece happens to be drawn as the main visual.
		if (mouseX >= iconX && mouseX <= iconX + iconSize && mouseY >= iconY && mouseY <= iconY + iconSize) {
			hoveredTooltipStack = icon;
			hoveredTooltipX = mouseX;
			hoveredTooltipY = mouseY;
		}

		// Small yellow Pet badge near the icon's top-right corner, per the user's own mockup, drawn only when
		// this loadout's lore actually names a real, resolvable pet.
		ItemStack petIcon = resolveGearIcon(gear.get("Pet"));
		if (!petIcon.isEmpty()) {
			int badgeSize = Math.max(8, iconSize / 3);
			int badgeX = iconX + iconSize - badgeSize * 3 / 4;
			int badgeY = iconY - badgeSize / 4;
			RenderUtil.fillRounded(graphics, badgeX, badgeY, badgeX + badgeSize, badgeY + badgeSize, 3, 0xFFD9B23A);
			drawItemIcon(graphics, font, petIcon, badgeX + 1, badgeY + 1, badgeSize - 2);
		}

		String setName = computeSetName(gear);
		if (!setName.isEmpty()) {
			String label = "Full " + setName;
			int labelWidth = font.width(label);
			float labelScale = labelWidth > width - 6 ? Math.max(0.5f, (width - 6) / (float) labelWidth) : 1f;
			int labelY = y + HEADER_HEIGHT + iconSize + Math.max(0, (LABEL_HEIGHT - 8) / 2);
			graphics.pose().pushMatrix();
			graphics.pose().translate(x + width / 2f, labelY);
			graphics.pose().scale(labelScale);
			graphics.text(font, label, Math.round(-labelWidth / 2f), 0, 0xFFCCCCCC);
			graphics.pose().popMatrix();
		}
	}

	private static void drawItemIcon(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y, int size) {
		if (size == 16) {
			graphics.item(stack, x, y);
			graphics.itemDecorations(font, stack, x, y);
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(size / 16f);
		graphics.pose().translate(-x, -y);
		graphics.item(stack, x, y);
		graphics.itemDecorations(font, stack, x, y);
		graphics.pose().popMatrix();
	}

	/** One screen-edge arrow (right for "next", left for "previous") — see this class's own doc comment for
	 *  the fixed real nav-icon slots that decide whether to call this at all. */
	private int[] drawNavArrow(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY, boolean forward) {
		int size = 24;
		int arrowX = forward ? screenWidth - size - 8 : 8;
		int arrowY = screenHeight / 2 - size / 2;
		boolean hovered = mouseX >= arrowX && mouseX <= arrowX + size && mouseY >= arrowY && mouseY <= arrowY + size;
		RenderUtil.fillRounded(graphics, arrowX, arrowY, arrowX + size, arrowY + size, 4, hovered ? 0xE0333333 : 0xE0202020);
		String glyph = forward ? ">" : "<";
		int gw = font.width(glyph);
		graphics.text(font, glyph, arrowX + (size - gw) / 2, arrowY + (size - 9) / 2, 0xFFFFFFFF);
		return new int[]{arrowX, arrowY, arrowX + size, arrowY + size};
	}

	private boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
		double mouseX = event.x(), mouseY = event.y();
		int mouseButton = event.button();

		if (nextArrowBounds != null && mouseX >= nextArrowBounds[0] && mouseX <= nextArrowBounds[2]
			&& mouseY >= nextArrowBounds[1] && mouseY <= nextArrowBounds[3]) {
			if (nextPageSlot != null) routeLoadoutClick(screen, nextPageSlot, 0);
			return false;
		}
		if (prevArrowBounds != null && mouseX >= prevArrowBounds[0] && mouseX <= prevArrowBounds[2]
			&& mouseY >= prevArrowBounds[1] && mouseY <= prevArrowBounds[3]) {
			if (prevPageSlot != null) routeLoadoutClick(screen, prevPageSlot, 0);
			return false;
		}

		for (int i = 0; i < cardBounds.size(); i++) {
			int[] b = cardBounds.get(i);
			if (mouseX < b[0] || mouseX > b[2] || mouseY < b[1] || mouseY > b[3]) continue;
			if (mouseY < panelY || mouseY > panelY + panelHeight) continue;
			int slotIndex = cardSlotIndex.get(i);
			// Per user request ("Remove the little pencil since you can just right click them"): right-click
			// (button 1) anywhere on the card sends Hypixel's own real "edit this loadout" input directly;
			// left-click (button 0) selects/equips it — no separate pencil hit-box needed.
			routeLoadoutClick(screen, slotIndex, mouseButton == 1 ? 1 : 0);
			return false;
		}

		// Same reasoning as StorageOverlayFeature.handleClick: every click while the panel is visible must
		// be consumed here, never fall through to the real (render-cancelled but still open) vanilla screen.
		return false;
	}

	private void routeLoadoutClick(AbstractContainerScreen<?> screen, int slotIndex, int mouseButton) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) return;
		long now = System.currentTimeMillis();
		if (now - lastContainerClickMs < CLICK_COOLDOWN_MS) return;
		List<Slot> allSlots = screen.getMenu().slots;
		if (slotIndex >= allSlots.size()) return;
		Slot slot = allSlots.get(slotIndex);
		// Real click, same as a genuine mouse click on this slot would send — a CLONE (middle-click) attempt
		// here in an earlier round turned out to not select anything at all (per user report), so this is a
		// real PICKUP either way; button 0 selects/equips, button 1 (a real right-click) edits. The visible
		// "removal" this used to cause is now handled generically by effectiveIcon's short grace window
		// instead of by changing the click itself — see this class's own doc comment.
		ContainerInput type = ContainerInput.PICKUP;
		if (ContainerClickRegistry.shouldCancel(slot, slotIndex, mouseButton, type)) return;
		ContainerClickRegistry.notifyAllowed(slot, slotIndex, mouseButton, type);
		mc.gameMode.handleContainerInput(screen.getMenu().containerId, slotIndex, mouseButton, type, mc.player);
		// Per user request ("cancel the cursor picking up the item client side so it doesnt look like it
		// picked up anything"): a real PICKUP click is vanilla's own local prediction for "pick this slot's
		// item up onto the cursor" — since this overlay's own click here is really just a "press this button"
		// action (select/edit a loadout, or page nav), not an actual drag-and-drop, immediately clearing the
		// menu's predicted carried stack stops render() from drawing it following the cursor afterward. The
		// packet routing this click to Hypixel has already been queued by handleContainerInput above, so this
		// only ever touches our own local render-side prediction, never what the server actually receives.
		screen.getMenu().setCarried(ItemStack.EMPTY);
		lastContainerClickMs = now;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("columns", columns);
		obj.addProperty("sizeScale", sizeScale);
		obj.addProperty("showBackground", showBackground);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("columns")) columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, obj.get("columns").getAsInt()));
		if (obj.has("sizeScale")) sizeScale = Math.max(2, Math.min(4, obj.get("sizeScale").getAsInt()));
		if (obj.has("showBackground")) showBackground = obj.get("showBackground").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Replaces Hypixel's real Loadouts menu with a card-grid overlay that's easier to read and click.";
	}
}
