package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * NEU-style quick-command buttons around the edge of any inventory screen — a full rectangular ring of
 * configurable slots (10 across the top edge, 10 across the bottom, 8 down each side, corners shared —
 * 32 total, per user spec) each running a chosen command (with an optional item-search-picked icon,
 * stack count, and enchant glint) on click. Ported concept, not ported code: NEU's own version reads its
 * own item-icon renderer this project doesn't have, so button icons are simplified to a colored square +
 * short label instead of a real ItemStack render — real command execution and per-button config are the
 * substantive part.
 */
public class NeuStyleButtonsFeature extends Feature {
	private static final int TOP_COUNT = 10;
	private static final int SIDE_COUNT = 8;
	private static final int SLOT_COUNT = 2 * TOP_COUNT + 2 * SIDE_COUNT - 4; // corners shared, not double-counted

	// "As big as they were before" (the pre-ring stacked layout used a flat 20px) — the preferred/max
	// size at normal window sizes. Only shrinks below this when the full 32-button ring wouldn't
	// otherwise fit within the player's actual screen space (smaller window or a higher GUI Scale),
	// per user request.
	private static final int PREFERRED_BUTTON_SIZE = 20;
	private static final int MIN_BUTTON_SIZE = 8;
	private static final int RING_GAP = 5;

	public static class ButtonConfig {
		public String command = "";
		public String itemQuery = "";
		public String itemDisplayName = "";
		// The matched search result's real Hypixel item id (not just its display name) — lets the button
		// look up a real rendered icon via SkyblockItemIcons instead of the plain colored-square+letter
		// placeholder this used to always draw. Null for a button configured before this field existed, or
		// whenever the typed item query never matched a real search result.
		public String itemId = null;
		public int count = 1;
		public boolean glint = false;

		public boolean isConfigured() {
			return !command.isBlank();
		}
	}

	private final List<ButtonConfig> buttons = new ArrayList<>();

	// Inline setup-form state for whichever slot is currently being edited, or -1 if none.
	private int editingSlot = -1;
	private String formCommand = "";
	private String formItemQuery = "";
	private String formCount = "1";
	private boolean formGlint = false;
	private int formFocusField = 0; // 0=command, 1=item query, 2=count
	private List<SkyblockItemRepo.ItemInfo> formSuggestions = List.of();

	// Recomputed every render() call and reused as-is by hit-testing, so a click can never land on a
	// button position the render pass didn't just draw at — a stale/independently-computed hit-test
	// rect was the actual reason clicks on a visible button could "miss" and fall through to whatever
	// real container slot was underneath (closing the GUI and letting the leftover keystroke move the
	// player, per the bug report).
	private final int[] buttonX = new int[SLOT_COUNT];
	private final int[] buttonY = new int[SLOT_COUNT];
	private int buttonSize = 20;
	// Anchor for the setup form popup — the position of whichever button opened it.
	private int panelX, panelY;

	public NeuStyleButtonsFeature() {
		super("neu_style_buttons", "Inventory Buttons", FeatureCategory.INVENTORY, false);
		for (int i = 0; i < SLOT_COUNT; i++) buttons.add(new ButtonConfig());

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// Player's own inventory only (per user request) — not every AbstractContainerScreen (chests,
			// NPC shops, enchanting table, etc. all extend it too, which is why the ring used to show up
			// around every GUI the player opened).
			if (!(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen containerScreen)) return;
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
				if (!isEnabled()) return true;
				return handleClick(event.x(), event.y(), event.button());
			});
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
				if (!isEnabled() || editingSlot < 0) return true;
				return !handleKey(event.key());
			});
			ScreenKeyboardEvents.allowCharType(screen).register((s, event) -> {
				if (!isEnabled() || editingSlot < 0) return true;
				return !handleCharTyped((char) event.codepoint());
			});
			ScreenEvents.remove(screen).register(s -> editingSlot = -1);
		});

		// Real bug found (per user report — "The equipment lore renders under the inventory buttons"): this
		// used to also have a separate ScreenEvents.afterExtract render path for the "no tooltip showing"
		// case, which fires strictly after the WHOLE screen render (including every other feature's own
		// PreTooltipRenderRegistry-based draw, e.g. EquipmentDisplayFeature) — so the ring always painted over
		// those, tooltip or no tooltip. Consolidated onto this single PreTooltipRenderRegistry hook
		// (registered once, globally, not per-screen, since it already fires for every
		// AbstractContainerScreen, every frame, tooltip or not — see PersonalCompactorOverlayFeature's own use
		// of this same registry for the general explanation) — now z-order between this and every other
		// PreTooltipRenderRegistry-based overlay is just plain listener-registration order (this feature is
		// constructed near the start of SkyblockSimplifiedClient, so features registered after it, like
		// Equipment Display, draw on top, matching what the user wants).
		com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled()) return;
			if (!(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen containerScreen)) return;
			// Real item-icon rendering (SkyblockItemIcons, added this round) touches skull-profile
			// construction off live Hypixel API data this project doesn't fully control the shape of — an
			// uncaught throw here used to propagate straight out of a bare ScreenEvents callback with no
			// isolation at all, which is exactly what "opening my inventory crashes the game" reads like.
			// Every other per-frame render entry point in this project already has this same isolation (HUD
			// callbacks, several rounds earlier this session) — this GUI-screen callback just never had it
			// because it predates that whole investigation.
			try {
				render(graphics, containerScreen);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Quick Action Buttons render failed, skipping this frame", e);
			}
		});
	}

	@Override
	public String getSubcategory() {
		// Was null, which is invisible/orphaned once the feature lived under a category that has real
		// sub-tabs (Inventory's are Inventory/Enchantments/Storage/Misc) — MainScreen only shows a feature
		// under whichever sub-tab is currently selected, and null never matches any of them.
		return "Inventory";
	}

	private void computeLayout(AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();
		int imageWidth = accessor.skyblocksimplified$getImageWidth();
		int imageHeight = accessor.skyblocksimplified$getImageHeight();
		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();

		// imageWidth/imageHeight are a fixed per-screen-type constant (survival inventory is always 176
		// wide, regardless of window size or GUI Scale) — what actually changes with a smaller window or
		// a higher GUI Scale is screenWidth/screenHeight (less available room). So: use the preferred
		// size whenever the screen has room for it, and only shrink as far as needed to keep the whole
		// ring on-screen.
		int maxByWidth = (screenWidth - imageWidth) / 2 - RING_GAP;
		int maxByHeight = (screenHeight - imageHeight) / 2 - RING_GAP;
		buttonSize = Math.max(MIN_BUTTON_SIZE, Math.min(PREFERRED_BUTTON_SIZE, Math.min(maxByWidth, maxByHeight)));

		float ringLeftX = leftPos - (RING_GAP + buttonSize / 2f);
		float ringRightX = leftPos + imageWidth + (RING_GAP + buttonSize / 2f);
		float ringTopY = topPos - (RING_GAP + buttonSize / 2f);
		float ringBottomY = topPos + imageHeight + (RING_GAP + buttonSize / 2f);

		int slot = 0;
		// Top edge, left to right (10 points, both top corners included).
		for (int i = 0; i < TOP_COUNT; i++) {
			place(slot++, lerp(ringLeftX, ringRightX, i / (float) (TOP_COUNT - 1)), ringTopY, screenWidth, screenHeight);
		}
		// Right edge, top to bottom, skipping the top-right corner already placed above (7 points,
		// ending at the bottom-right corner).
		for (int i = 1; i < SIDE_COUNT; i++) {
			place(slot++, ringRightX, lerp(ringTopY, ringBottomY, i / (float) (SIDE_COUNT - 1)), screenWidth, screenHeight);
		}
		// Bottom edge, right to left, skipping the bottom-right corner already placed above (9 points,
		// ending at the bottom-left corner).
		for (int i = TOP_COUNT - 2; i >= 0; i--) {
			place(slot++, lerp(ringLeftX, ringRightX, i / (float) (TOP_COUNT - 1)), ringBottomY, screenWidth, screenHeight);
		}
		// Left edge, bottom to top, skipping both corners already placed above — interior points only.
		for (int i = SIDE_COUNT - 2; i >= 1; i--) {
			place(slot++, ringLeftX, lerp(ringTopY, ringBottomY, i / (float) (SIDE_COUNT - 1)), screenWidth, screenHeight);
		}
	}

	private void place(int slot, float centerX, float centerY, int screenWidth, int screenHeight) {
		int x = Math.round(centerX - buttonSize / 2f);
		int y = Math.round(centerY - buttonSize / 2f);
		buttonX[slot] = Math.max(2, Math.min(screenWidth - buttonSize - 2, x));
		buttonY[slot] = Math.max(2, Math.min(screenHeight - buttonSize - 2, y));
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private void render(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		computeLayout(screen);

		for (int i = 0; i < SLOT_COUNT; i++) {
			int x = buttonX[i];
			int y = buttonY[i];
			ButtonConfig config = buttons.get(i);
			boolean configured = config.isConfigured();
			int bg = configured ? 0x90202030 : 0x60101010;
			// Real bug found (per user report — "My frames seem to decline a LOT... its actually the
			// inventory buttons"): RenderUtil.fillRounded antialiases its corners with real per-pixel
			// graphics.fill() calls (see its own doc comment) — cheap for the handful of structural panels
			// the mod menu itself draws once per menu frame, but this ring redraws up to 32 of these EVERY
			// inventory frame, so the antialiased-corner cost that was negligible elsewhere multiplied into
			// thousands of extra draw calls a second here. Plain square-cornered graphics.fill() is one draw
			// call instead of dozens per button — a real, measurable fix, not just a micro-optimization, for
			// a UI element whose corners are only a few pixels across anyway.
			graphics.fill(x, y, x + buttonSize, y + buttonSize, bg);
			if (configured) {
				net.minecraft.world.item.ItemStack realIcon = com.cokelord.skyblocksimplified.util.SkyblockItemIcons.getIcon(config.itemId);
				if (realIcon != null) {
					// Real vanilla item-render pipeline (graphics.item), same as an inventory slot — centered
					// in whatever the button's current (possibly shrunk-to-fit) size is, since the vanilla
					// item renderer always draws at a fixed 16x16 regardless of the size passed in.
					int iconX = x + (buttonSize - 16) / 2;
					int iconY = y + (buttonSize - 16) / 2;
					// Real vanilla enchant foil (the actual animated shimmer, not a static overlay square) —
					// forced via the same glint-override component spectral arrows/glinting potions use,
					// applied to a copy so the cached icon in SkyblockItemIcons isn't itself mutated.
					net.minecraft.world.item.ItemStack rendered = config.glint ? realIcon.copy() : realIcon;
					if (config.glint) {
						rendered.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
					}
					graphics.item(rendered, iconX, iconY);
				} else {
					int itemColor = 0xFF66AACC;
					graphics.fill(x + 3, y + 3, x + buttonSize - 3, y + buttonSize - 3, itemColor);
					String label = (config.itemDisplayName.isBlank() ? config.command : config.itemDisplayName);
					String initial = label.isBlank() ? "?" : label.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
					graphics.text(font, initial, x + buttonSize / 2 - font.width(initial) / 2, y + buttonSize / 2 - 4, 0xFFFFFFFF);
					// Drawn LAST, on top of the placeholder square and its letter — per user report the glint
					// tint used to render first, right after the background fill, which just made it look like
					// part of the button's background plate rather than a shimmer sitting over the icon. This
					// fallback square only shows at all for an item id that couldn't resolve a real icon (see
					// SkyblockItemIcons) — once it resolves, the real vanilla enchant-glint animation renders
					// as part of graphics.item() itself instead, correctly layered over the actual icon.
					if (config.glint) {
						graphics.fill(x + 3, y + 3, x + buttonSize - 3, y + buttonSize - 3, 0x30FFFFFF);
					}
				}
				if (config.count > 1) {
					String countText = String.valueOf(config.count);
					graphics.text(font, countText, x + buttonSize - font.width(countText) - 2, y + buttonSize - 9, 0xFFFFFF55);
				}
			} else {
				String plus = "+";
				graphics.text(font, plus, x + buttonSize / 2 - font.width(plus) / 2, y + buttonSize / 2 - 4, 0x80FFFFFF);
			}
		}

		if (editingSlot >= 0) renderForm(graphics, font);
	}

	private void renderForm(GuiGraphicsExtractor graphics, Font font) {
		int formWidth = 200;
		int formHeight = 100;
		// panelX/panelY (set in startEditing) are already the form's fully-clamped, on-screen top-left
		// corner — this used to subtract formWidth+8 a second time here, which is what actually pushed
		// the popup far off the left edge of the screen regardless of which button opened it.
		int x = panelX;
		int y = panelY;
		RenderUtil.fillRounded(graphics, x, y, x + formWidth, y + formHeight, 6, 0xF0101010);
		graphics.text(font, "Set up button " + (editingSlot + 1), x + 8, y + 6, 0xFFFFFFFF);

		graphics.text(font, "Command:", x + 8, y + 20, 0xFFAAAAAA);
		drawField(graphics, font, x + 8, y + 30, formWidth - 16, formCommand, formFocusField == 0);

		graphics.text(font, "Item search:", x + 8, y + 44, 0xFFAAAAAA);
		drawField(graphics, font, x + 8, y + 54, formWidth - 16, formItemQuery, formFocusField == 1);

		graphics.text(font, "Count:", x + 8, y + 68, 0xFFAAAAAA);
		drawField(graphics, font, x + 8, y + 78, 40, formCount, formFocusField == 2);

		String glintText = formGlint ? "Glint: ON" : "Glint: OFF";
		graphics.text(font, glintText, x + 100, y + 80, formGlint ? 0xFFFFFF55 : 0xFF888888);

		// Explicit, deliberate delete action — only shown for a slot that's actually configured, since an
		// empty slot has nothing to clear. Replaces the old "right-click deletes" behavior, which the user
		// reported as both the only way to delete AND the thing blocking editing (right-click now opens
		// this same form instead — see handleClick).
		if (buttons.get(editingSlot).isConfigured()) {
			graphics.text(font, "Clear", x + formWidth - 8 - font.width("Clear"), y + 6, 0xFFFF6666);
		}

		int suggY = y + formHeight + 2;
		for (SkyblockItemRepo.ItemInfo info : formSuggestions) {
			RenderUtil.fillRounded(graphics, x, suggY, x + formWidth, suggY + 12, 2, 0xE0181818);
			graphics.text(font, info.name(), x + 4, suggY + 2, 0xFFDDDDDD);
			suggY += 13;
		}
	}

	private void drawField(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, String value, boolean focused) {
		RenderUtil.fillRounded(graphics, x, y, x + width, y + 10, 2, focused ? 0xFF303050 : 0xFF202020);
		graphics.text(font, value + (focused ? "_" : ""), x + 2, y + 1, 0xFFFFFFFF);
	}

	private boolean handleClick(double mouseX, double mouseY, int button) {
		if (editingSlot >= 0) return handleFormClick(mouseX, mouseY, button);

		for (int i = 0; i < SLOT_COUNT; i++) {
			int x = buttonX[i];
			int y = buttonY[i];
			if (mouseX >= x && mouseX <= x + buttonSize && mouseY >= y && mouseY <= y + buttonSize) {
				ButtonConfig config = buttons.get(i);
				if (button == 1) {
					// Right-click now opens the edit form (was: clear the slot outright) — per user report,
					// there was no way to edit an already-configured button at all, and a stray right-click
					// during normal play silently deleted it with no confirmation. Deleting now happens via
					// the explicit "Clear" action inside the form itself instead (see renderForm/handleFormClick).
					startEditing(i);
					return false;
				}
				if (config.isConfigured()) {
					runCommand(config);
				} else {
					startEditing(i);
				}
				return false;
			}
		}
		return true;
	}

	private boolean handleFormClick(double mouseX, double mouseY, int button) {
		int formWidth = 200;
		int formHeight = 100;
		int x = panelX;
		int y = panelY;
		// Bottom bound now reflects what's actually drawn (the form itself plus up to 5 suggestion rows
		// at 13px each) instead of an arbitrary +200px guess. Every click is still consumed (this always
		// returns false) whether it lands inside or outside that region — outside just also closes the
		// form — so nothing behind the popup (inventory slots included) is ever clickable while it's open.
		int suggestionsHeight = formSuggestions.size() * 13;
		if (mouseX < x - 10 || mouseX > x + formWidth + 10 || mouseY < y - 10 || mouseY > y + formHeight + suggestionsHeight + 10) {
			// Safety net for exactly the class of bug fixed below (a mouse-only field change, like the
			// glint toggle, that never went through applyFormToConfig): whatever's currently in the form
			// gets committed before the popup closes, not just on a keystroke or explicit Enter/Clear.
			applyFormToConfig();
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			editingSlot = -1;
			return false;
		}
		Font font = Minecraft.getInstance().font;
		int clearWidth = font.width("Clear");
		int clearX = x + formWidth - 8 - clearWidth;
		if (buttons.get(editingSlot).isConfigured() && mouseX >= clearX && mouseX <= clearX + clearWidth && mouseY >= y + 6 && mouseY <= y + 6 + font.lineHeight) {
			buttons.set(editingSlot, new ButtonConfig());
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			editingSlot = -1;
			return false;
		}
		if (mouseY >= y + 30 && mouseY <= y + 40) formFocusField = 0;
		else if (mouseY >= y + 54 && mouseY <= y + 64) formFocusField = 1;
		else if (mouseY >= y + 78 && mouseY <= y + 88 && mouseX <= x + 48) formFocusField = 2;
		else if (mouseY >= y + 80 && mouseY <= y + 90 && mouseX >= x + 100) {
			// Per user report, this toggle wasn't saving — it's a mouse-only field change (every other
			// field's own edit path goes through a keystroke, which already calls applyFormToConfig/save),
			// so without an explicit call here it only ever updated the form's own local display state and
			// was lost the moment the popup closed via anything other than a later keystroke.
			formGlint = !formGlint;
			applyFormToConfig();
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
		}

		int suggY = y + formHeight + 2;
		for (SkyblockItemRepo.ItemInfo info : formSuggestions) {
			if (mouseY >= suggY && mouseY <= suggY + 12) {
				formItemQuery = info.name();
				break;
			}
			suggY += 13;
		}
		return false;
	}

	private void startEditing(int slot) {
		editingSlot = slot;
		Minecraft mc = Minecraft.getInstance();
		int formWidth = 200;
		int formHeight = 100;
		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();
		// Tooltip-style anchor: open to the right of the button that was clicked, flipping to the left
		// side only if there's no room on the right, then clamp fully on both axes (leaving room below
		// for up to 5 suggestion rows) so the popup can never render partly off-screen no matter which
		// of the 32 ring slots was clicked.
		int maxSuggestionsHeight = 5 * 13;
		int x = buttonX[slot] + buttonSize + 8;
		if (x + formWidth > screenWidth - 4) {
			x = buttonX[slot] - formWidth - 8;
		}
		panelX = Math.max(4, Math.min(screenWidth - formWidth - 4, x));
		panelY = Math.max(4, Math.min(screenHeight - formHeight - maxSuggestionsHeight - 4, buttonY[slot]));
		ButtonConfig existing = buttons.get(slot);
		formCommand = existing.command;
		formItemQuery = existing.itemQuery;
		formCount = String.valueOf(Math.max(1, existing.count));
		formGlint = existing.glint;
		formFocusField = 0;
		formSuggestions = List.of();
	}

	public boolean handleCharTyped(char chr) {
		if (editingSlot < 0) return false;
		switch (formFocusField) {
			case 0 -> formCommand += chr;
			case 1 -> {
				formItemQuery += chr;
				formSuggestions = SkyblockItemRepo.search(formItemQuery, 5);
			}
			case 2 -> {
				if (Character.isDigit(chr)) formCount += chr;
			}
			default -> { }
		}
		applyFormToConfig();
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
		return true;
	}

	/** Backspace/Enter/Escape/Tab get their special handling; every other key is still swallowed
	 *  (returns true = consumed) rather than falling through — letting an unrecognized key's raw
	 *  key-press event pass to vanilla while this form is open was the actual "typing moves the player"
	 *  bug: vanilla's own "don't move while a screen is open" gate doesn't cover every action (jump in
	 *  particular can bypass it, since it's also needed for swimming/boats with certain screens open),
	 *  so Space specifically leaking through the raw key-press event — even though charTyped correctly
	 *  added its space character to the text field — was enough to trigger a real jump/move. */
	public boolean handleKey(int keyCode) {
		if (editingSlot < 0) return false;
		final int BACKSPACE = 259, ENTER = 257, ESCAPE = 256, TAB = 258;
		if (keyCode == BACKSPACE) {
			switch (formFocusField) {
				case 0 -> formCommand = trimLast(formCommand);
				case 1 -> {
					formItemQuery = trimLast(formItemQuery);
					formSuggestions = SkyblockItemRepo.search(formItemQuery, 5);
				}
				case 2 -> formCount = trimLast(formCount);
				default -> { }
			}
			applyFormToConfig();
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			return true;
		}
		if (keyCode == TAB) {
			formFocusField = (formFocusField + 1) % 3;
			return true;
		}
		if (keyCode == ENTER) {
			confirmForm();
			return true;
		}
		if (keyCode == ESCAPE) {
			editingSlot = -1;
			return true;
		}
		return true;
	}

	private static String trimLast(String s) {
		return s.isEmpty() ? s : s.substring(0, s.length() - 1);
	}

	// Applies the in-progress form fields to the actual ButtonConfig without closing the form — called
	// after every keystroke (char typed or backspace) so the config always reflects what's currently
	// typed, not just whatever was last confirmed. Previously only ran once, on Enter/click-away, which
	// is the actual "settings don't save after I've typed them" bug: closing the popup any other way (or
	// the game crashing/closing mid-edit) discarded everything typed since the last explicit confirm.
	// Per user report ("I select an item and it works until I deselect or close my inventory and then it
	// just becomes a letter") — startEditing() always resets formSuggestions to empty every time the form is
	// (re)opened, REGARDLESS of whether the button already has a correctly-resolved itemId from a previous
	// session. The old version of this method treated "formSuggestions is empty" as "nothing real is typed,
	// clear the id" unconditionally — which fired every single time this ran after simply reopening an
	// already-configured button without retyping anything (including the new safety-net apply-on-close and
	// the glint-toggle click, both added this round), silently wiping a perfectly good itemId back to null.
	// Now: only touch itemId/itemDisplayName when the query text has actually CHANGED from what's already
	// stored, or a fresh suggestion match exists for it — an untouched query leaves the existing resolved id
	// exactly as it was.
	private void applyFormToConfig() {
		if (editingSlot < 0) return;
		ButtonConfig config = buttons.get(editingSlot);
		config.command = formCommand.trim();
		config.count = Math.max(1, parseIntSafe(formCount, 1));
		config.glint = formGlint;

		String trimmedQuery = formItemQuery.trim();
		boolean queryChanged = !trimmedQuery.equals(config.itemQuery);
		config.itemQuery = trimmedQuery;
		if (trimmedQuery.isBlank()) {
			config.itemDisplayName = "";
			config.itemId = null;
		} else if (!formSuggestions.isEmpty()) {
			config.itemDisplayName = formSuggestions.get(0).name();
			config.itemId = formSuggestions.get(0).id();
		} else if (queryChanged) {
			config.itemDisplayName = trimmedQuery;
			config.itemId = null;
		}
		// else: query is unchanged from what's already stored and formSuggestions is only empty because
		// startEditing() always resets it — leave the already-resolved itemId/itemDisplayName untouched.
	}

	private void confirmForm() {
		if (editingSlot < 0) return;
		applyFormToConfig();
		editingSlot = -1;
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	private static int parseIntSafe(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void runCommand(ButtonConfig config) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String command = config.command.startsWith("/") ? config.command.substring(1) : config.command;
		mc.player.connection.sendCommand(command);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonArray arr = new JsonArray();
		for (ButtonConfig config : buttons) {
			JsonObject obj = new JsonObject();
			obj.addProperty("command", config.command);
			obj.addProperty("itemQuery", config.itemQuery);
			obj.addProperty("itemDisplayName", config.itemDisplayName);
			if (config.itemId != null) obj.addProperty("itemId", config.itemId);
			obj.addProperty("count", config.count);
			obj.addProperty("glint", config.glint);
			arr.add(obj);
		}
		return arr;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonArray()) return;
		JsonArray arr = data.getAsJsonArray();
		for (int i = 0; i < SLOT_COUNT && i < arr.size(); i++) {
			if (!arr.get(i).isJsonObject()) continue;
			JsonObject obj = arr.get(i).getAsJsonObject();
			ButtonConfig config = new ButtonConfig();
			if (obj.has("command")) config.command = obj.get("command").getAsString();
			if (obj.has("itemQuery")) config.itemQuery = obj.get("itemQuery").getAsString();
			if (obj.has("itemDisplayName")) config.itemDisplayName = obj.get("itemDisplayName").getAsString();
			// A config saved before itemId existed has no id at all — best-effort recover one from the
			// display name it already has, so an existing button upgrades to a real icon automatically
			// instead of staying on the old letter/square placeholder forever.
			if (obj.has("itemId")) {
				config.itemId = obj.get("itemId").getAsString();
			} else if (!config.itemDisplayName.isBlank()) {
				config.itemId = com.cokelord.skyblocksimplified.api.SkyblockItemRepo.findIdByName(config.itemDisplayName);
			}
			if (obj.has("count")) config.count = obj.get("count").getAsInt();
			if (obj.has("glint")) config.glint = obj.get("glint").getAsBoolean();
			buttons.set(i, config);
		}
	}

	@Override
	public String getDescription() {
		return "Adds a ring of configurable quick-command buttons around the edge of any inventory screen, NEU-style.";
	}
}
