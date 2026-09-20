package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * While Hypixel's Loadouts menu is open, pressing a real hotbar number key (1-9) clicks the corresponding
 * loadout icon directly, without needing to move the mouse there. Ported from Devonian's
 * {@code LoadoutKeybinds.kt} per user request ("Im pretty sure the feature exists in devonian... so just
 * port it over") after FIVE straight rounds of this mod's own from-scratch attempts all failed for the same
 * reason none of them ever actually found: {@code KeyMapping.consumeClick()}, polled from {@code onTick()},
 * can NEVER see a click while any {@link net.minecraft.client.gui.screens.Screen} is open — vanilla only
 * ever feeds raw key-downs into a KeyMapping's click bookkeeping through {@code Minecraft.handleKeybinds()},
 * which is itself skipped entirely whenever a screen without {@code passEvents=true} is open (a real
 * Loadouts chest GUI, like almost every other screen, doesn't set that). Every prior round instead assumed
 * the SLOT-DETECTION heuristic was still wrong and kept revising it (fixed position -> negative
 * filler-exclusion -> positive gear-word whitelist) — the last of which, per the most recent user-supplied
 * debug log, was actually already matching the right slots correctly the whole time. The keybinds
 * themselves just structurally could not have ever fired, no matter how good the detection was, and no
 * matter whether the (also newly-registered, separately unbound) custom keybinds were ever bound.
 *
 * <p>Devonian's own fix reads the raw key event directly through its Screen key-down hook instead of
 * polling a KeyMapping's click state — the exact same {@code ScreenKeyboardEvents.allowKeyPress} mechanism
 * this mod already uses for {@link LeapMenuFeature}/{@link SlotBindsFeature}'s own in-screen key handling,
 * for the identical reason. Also matches Devonian's choice to reuse the player's own real, already-bound
 * vanilla hotbar-slot keys (1-9) instead of registering new custom keybinds that need separate binding
 * under Controls before they do anything — the exact friction the previous round's unbound-key chat warning
 * was only ever a band-aid for. Slot detection itself is kept as this mod's own name-based whitelist
 * (matching a real gear-category word — Helmet/Chestplate/Leggings/Boots/Necklace/Cloak/Belt/Pet — in the
 * item's name) rather than Devonian's hardcoded slot-index list, since that's already confirmed correct via
 * live user data and doesn't depend on Hypixel never rearranging the exact slot layout. Devonian's
 * previous/next-page (A/D) shortcut was NOT ported — that would need its own new, unverified slot-index or
 * name-based detection for the page-arrow icons, and paging wasn't part of what was reported broken here.
 *
 * <p>Round 9 (per user report — "Loadout keybinds works but clicks the wrong buttons"): the whitelist-only
 * detection above only ever matches real ARMOR pieces (Helmet/Chestplate/Leggings/Boots), since those
 * genuinely include the category word in their own display name (e.g. "Necrotic Helmet") — the same
 * user-supplied live debug log this round's predecessor cross-checked against only ever showed 4 matches,
 * all armor. But ACCESSORY-slot items (Necklace/Cloak/Belt/Pet) are almost always named after their own
 * unique identity instead ("Wither Amulet", "Frozen Scythe" for a Pet, etc.) — they essentially never
 * literally contain the word "Necklace"/"Cloak"/"Belt"/"Pet" in their own name. So on any real Loadouts page
 * mixing armor AND accessory rows, the accessory rows silently fail the whitelist and vanish from the
 * candidate list entirely, COMPACTING the list and shifting every subsequent index up — pressing "3"
 * expecting the 3rd visual row instead clicks whatever ended up 3rd in the shrunken list, a real button but
 * the wrong one. Column-anchoring attempted to fix this by widening detection to every non-empty slot in
 * the same column as a whitelist match, superseded below by round 10's exact confirmed layout.
 *
 * <p>Round 10 (per user report, with the exact real layout this time — "When i press 1 it clicks the third
 * square and 2 clicks the one under and so on... first keybind should click slot 15, second slot 16, third
 * 17, fourth 24... it goes three clicks, then the next keybind should be in 7 slots... 15 > 16 > 17 > 24 >
 * 25 > 26 > 33 > 34 > 35... only 12 since there are only 12 slots on each page"): the real Loadouts grid is
 * a fixed 4-row x 3-column block at container columns 6-8 (0-indexed), NOT a single vertical column as the
 * previous round's live-tested-but-incomplete data (which only ever saw 4 armor pieces, all in one column
 * by coincidence) suggested. With the real layout now known exactly, replaced all name/column-detection
 * heuristics with this literal, fixed 12-slot list — nothing left to misdetect.
 *
 * <p>Round 11 (per user report — "You are REALLY close with the loadout keybinds, pressing 1 presses the
 * second loadout, pressing 2 presses the third and pressing 3 presses the glass pane next to them. Just -1
 * the slots"): the previous round's live-tested list was off by exactly one slot across the board — key 1
 * was landing on the SECOND loadout icon (slot 15), meaning the real first loadout icon is actually slot 14,
 * not 15. Subtracted 1 from every entry per the user's own exact fix.
 *
 * <p>Round 11 also added an optional "highlight the currently selected loadout" subtoggle, per user request
 * ("detect this by detecting a line that contains data on the loadouts (for example Helmet: Necrotic Young
 * Dragon Helmet) then matching that with the helmet equipped on the left side"), matching each loadout
 * icon's own lore against a reference "equipped" slot. Per user follow-up report ("The highlight doesnt
 * work on the loadouts. It should atleast highlight the keybind i used") that real-detection attempt didn't
 * actually work live (the lore format and/or reference-slot assumption was wrong) — replaced with the
 * simpler, guaranteed-correct alternative the user offered as a fallback: remember and highlight whichever
 * loadout slot THIS feature itself most recently clicked via a keybind, since that's driven by our own
 * action rather than a guess about the real screen's content.
 *
 * <p>Round 12 (per user report - "when i go in the module itself theres just the subtoggle and no actual
 * buttons for me to select any keybinds"): true bug, not a misunderstanding - this feature silently reused
 * the player's already-bound vanilla hotbar keys (1-9) under the hood, so despite the module's own name
 * there was never actually anything "custom" to bind, and the settings panel had zero UI for it (also
 * meaning loadout slots 10-12 could never be triggered at all, since vanilla only exposes 9 hotbar keys).
 * Replaced with 12 real, independently rebindable {@link KeyCombo}s (one per loadout slot, using the same
 * capturable-combo row every other keybind feature in this codebase already exposes), defaulting to 1-9 for
 * the first nine slots (preserving old behavior out of the box) and unbound for the last three. Since a
 * {@code KeyCombo}'s own {@code isHeld()} polling can't see keydowns while a screen is open (the entire
 * reason this feature reads raw key events in the first place - see this class's own opening paragraph),
 * matching is done differently here: on each real keydown event, a combo is considered triggered if the
 * event's key is one of its keys AND every other key in the combo is currently physically held (checked via
 * {@link KeyCombo#isDown}, which polls raw hardware state and works regardless of screen focus).
 *
 * <p>Round 14: redid the "highlight selected loadout" detection again, this time against a real confirmed
 * source instead of the last-clicked-slot fallback (see {@link #renderSelectedHighlight}) — NoammAddons'
 * {@code WardrobeKeybinds.kt} marks the currently-active slot in Hypixel's sibling "Armor Sets" screen with
 * a real {@code LIME_DYE} item, and unselected slots with {@code GRAY_DYE} filler that must be excluded so
 * it's never mistaken for a selection. The last-clicked fallback is kept underneath in case Loadouts turns
 * out not to follow the exact same convention.
 *
 * <p>Round 15 (per user request — "port SkyHanni's loadout highlighting fixes"): Round 14's assumption WAS
 * wrong — the real Loadouts screen (unlike its "Armor Sets" sibling) never actually uses a lime/gray dye
 * marker at all, so that detection could only ever fall through to the last-clicked fallback. Replaced with
 * SkyHanni's own confirmed {@code LoadoutApi.isCurrentSelectedLoadout()} approach instead: the currently
 * equipped loadout icon is simply whichever real (non-empty, non-"Loadout N Locked") icon's lore contains
 * NEITHER "Left-click to equip!" (shown on every unequipped candidate) NOR "You must customize this loadout"
 * (shown on an empty/uncustomized slot) — both hint lines Hypixel always shows on every OTHER icon, so the
 * one icon missing both is the active one by elimination. No item-identity marker needed at all. The
 * last-clicked fallback is kept underneath for the same reason as before. SkyHanni's own "favorite loadout"
 * highlight was NOT ported — this mod doesn't track a per-loadout favorite flag (Hypixel doesn't expose one
 * as a simple lore line the way "currently equipped" is), and the user explicitly asked to skip that part.
 */
public class CustomLoadoutKeybindsFeature extends Feature {
	// Per user-supplied exact live-tested layout (round 10, corrected -1 per round 11 — see class doc
	// comment): a fixed 4-row x 3-column grid (container slot columns 6-8), row-major, capped at 12 since a
	// Loadouts page only ever has 12 icons.
	private static final int[] LOADOUT_SLOTS = {14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43};
	// Per user request (Round 15, "port SkyHanni's loadout highlighting fixes... hex popup color"): the color
	// itself is now a real per-user setting (see highlightColor below) instead of a fixed constant — ARGB, alpha
	// baked in like every other color setting in this codebase. SkyHanni's own default for this exact highlight
	// is LorenzColor.GREEN, so that's this setting's own default too (0x50 alpha to match the old constant's look).
	private static final int DEFAULT_HIGHLIGHT_COLOR = 0x5055FF55;
	private static final java.util.regex.Pattern LOCKED_SLOT_PATTERN = java.util.regex.Pattern.compile("^Loadout \\d+ Locked$");
	private static final int[] DEFAULT_KEYS = {
		InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4,
		InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9
	};

	// Per user request ("default to 'Currently equipped'"): on by default, matching SkyHanni's own
	// LoadoutHighlightingConfig.currentlyEquipped default (true).
	private boolean highlightSelectedLoadout = true;
	private int highlightColor = DEFAULT_HIGHLIGHT_COLOR;
	private int lastUsedSlot = -1;
	// One independently rebindable KeyCombo per loadout slot — see this class's own doc comment (round 12)
	// for why this replaced the old silent vanilla-hotbar-key reuse. Slots 0-8 default to 1-9 (same
	// out-of-the-box behavior as before); slots 9-11 start unbound since there's no vanilla equivalent.
	private final KeyCombo[] loadoutCombos = new KeyCombo[LOADOUT_SLOTS.length];

	private static boolean listenersRegistered = false;
	private static CustomLoadoutKeybindsFeature instance;

	public CustomLoadoutKeybindsFeature() {
		super("custom_loadout_keybinds", "Custom Loadout Keybinds", FeatureCategory.INVENTORY, false);
		instance = this;
		for (int i = 0; i < loadoutCombos.length; i++) {
			loadoutCombos[i] = new KeyCombo();
			if (i < DEFAULT_KEYS.length) {
				loadoutCombos[i].setKeys(java.util.Set.of(InputConstants.Type.KEYSYM.getOrCreate(DEFAULT_KEYS[i])));
			}
		}
	}

	@Override
	public String getSubcategory() {
		return "Inventory";
	}

	public boolean isHighlightSelectedLoadout() { return highlightSelectedLoadout; }
	public void setHighlightSelectedLoadout(boolean value) { highlightSelectedLoadout = value; }
	public int getHighlightColor() { return highlightColor; }
	public void setHighlightColor(int value) { highlightColor = value; }

	public int getLoadoutSlotCount() { return LOADOUT_SLOTS.length; }
	public KeyCombo getLoadoutCombo(int index) { return loadoutCombos[index]; }

	/** The same confirmed real 4x3 layout used above (see this class's own Round 10/11 doc comment for how it
	 *  was live-tested), exposed for {@link LoadoutOverlayFeature} per user request ("Literally just display
	 *  the slots that the keybinds use... use those slots instead of searching for anything") — a single
	 *  source of truth instead of a second guessed/duplicated array. */
	public static int[] loadoutSlots() {
		return LOADOUT_SLOTS.clone();
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("highlightSelectedLoadout", highlightSelectedLoadout);
		obj.addProperty("highlightColor", highlightColor);
		JsonArray combosArray = new JsonArray();
		for (KeyCombo combo : loadoutCombos) {
			JsonArray keysArray = new JsonArray();
			for (String key : combo.serialize()) keysArray.add(key);
			combosArray.add(keysArray);
		}
		obj.add("loadoutCombos", combosArray);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("highlightSelectedLoadout")) highlightSelectedLoadout = obj.get("highlightSelectedLoadout").getAsBoolean();
		if (obj.has("highlightColor")) highlightColor = obj.get("highlightColor").getAsInt();
		if (obj.has("loadoutCombos")) {
			JsonArray combosArray = obj.getAsJsonArray("loadoutCombos");
			for (int i = 0; i < loadoutCombos.length && i < combosArray.size(); i++) {
				List<String> serialized = new java.util.ArrayList<>();
				for (JsonElement keyEl : combosArray.get(i).getAsJsonArray()) serialized.add(keyEl.getAsString());
				loadoutCombos[i].setKeys(KeyCombo.deserialize(serialized).getKeys());
			}
		}
	}

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
				if (instance == null || !instance.isEnabled()) return true;
				return instance.onKeyPress(client, containerScreen, event);
			});
		});
		// Drawn from PreItemRenderRegistry (fires before item icons are drawn, so the highlight sits UNDER
		// the loadout icon instead of painted over it — matching every other item-highlight feature in this
		// codebase, see that registry's own doc comment).
		com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (instance == null || !instance.isEnabled() || !instance.highlightSelectedLoadout) return;
			// Per user request ("make sure the loadout highlighting system disables itself if the overlay
			// is active"): LoadoutOverlayFeature draws its own per-card green tint for the same real
			// "currently selected" state, so this would otherwise double-draw over its cards.
			if (LoadoutOverlayFeature.isOverlayActive()) return;
			if (!screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("loadouts")) return;
			instance.renderSelectedHighlight(graphics, screen);
		});
	}

	/** Real Hypixel detection, ported from SkyHanni's confirmed {@code LoadoutApi.isCurrentSelectedLoadout()}
	 *  (see this class's own Round 15 doc comment for why Round 14's lime/gray-dye approach didn't work on
	 *  the real Loadouts screen): a real, in-use loadout icon is any non-empty icon that isn't a locked slot
	 *  ("Loadout N Locked") and whose lore contains NEITHER "Left-click to equip!" nor "You must customize
	 *  this loadout" — the two hint lines Hypixel shows on every OTHER candidate. Falls back to whichever
	 *  slot this feature itself most recently clicked via a keybind if no such icon is found this frame. */
	private void renderSelectedHighlight(net.minecraft.client.gui.GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		List<Slot> allSlots = screen.getMenu().slots;
		for (int loadoutSlot : LOADOUT_SLOTS) {
			if (loadoutSlot >= allSlots.size()) continue;
			Slot slot = allSlots.get(loadoutSlot);
			if (isCurrentlySelectedLoadout(slot.getItem())) {
				drawHighlight(graphics, slot);
				return;
			}
		}
		if (lastUsedSlot < 0 || lastUsedSlot >= allSlots.size()) return;
		drawHighlight(graphics, allSlots.get(lastUsedSlot));
	}

	/** Exposed for {@link PetDisplayFeature} per user request ("make the mod read all loadouts... when it
	 *  detects that the loadout has been swapped to (same method highlight currently selected loadout uses)
	 *  it should swap the pet") — the same confirmed real detection this class's own highlight already uses,
	 *  reused instead of a second guessed/duplicated copy. */
	public static boolean isCurrentlySelectedLoadout(ItemStack item) {
		if (item.isEmpty()) return false;
		if (LOCKED_SLOT_PATTERN.matcher(item.getHoverName().getString()).matches()) return false;
		var lore = item.get(net.minecraft.core.component.DataComponents.LORE);
		if (lore != null) {
			for (net.minecraft.network.chat.Component line : lore.lines()) {
				String text = line.getString();
				if (text.contains("Left-click to equip!") || text.contains("You must customize this loadout")) return false;
			}
		}
		return true;
	}

	private void drawHighlight(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Slot slot) {
		// PreItemRenderRegistry fires from inside AbstractContainerScreen's own already-translated
		// pose().translate(leftPos, topPos) block — slot.x/slot.y are already the right coordinates.
		int x = slot.x;
		int y = slot.y;
		RenderUtil.fillRounded(graphics, x - 1, y - 1, x + 17, y + 17, 2, highlightColor);
	}

	/** A combo is considered triggered by this keydown event if the event's key is one of the combo's keys
	 *  and every OTHER key in the combo (e.g. a modifier) is currently physically held — see this class's
	 *  own doc comment (round 12) for why {@link KeyCombo#isHeld} polling can't be used directly here. */
	private static boolean comboMatchesEvent(KeyCombo combo, net.minecraft.client.input.KeyEvent event) {
		if (combo.isEmpty()) return false;
		InputConstants.Key eventKey = InputConstants.Type.KEYSYM.getOrCreate(event.key());
		if (!combo.getKeys().contains(eventKey)) return false;
		for (InputConstants.Key key : combo.getKeys()) {
			if (!key.equals(eventKey) && !KeyCombo.isDown(key)) return false;
		}
		return true;
	}

	/** Returns false (cancel/consume) if one of the 12 configured combos matched while the Loadouts screen
	 *  was open — even if there's no candidate loadout icon at that index — so the player's actual held
	 *  hotbar slot never silently changes underneath the GUI, matching Devonian's own behavior. */
	private boolean onKeyPress(Minecraft client, AbstractContainerScreen<?> screen, net.minecraft.client.input.KeyEvent event) {
		// Case-insensitive substring match — the real title has a leading page counter ("(1/3) Loadouts")
		// and the word isn't guaranteed to always be title-cased.
		if (!screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("loadouts")) return true;
		LocalPlayer player = client.player;
		if (player == null || client.gameMode == null) return true;

		int matchedIndex = -1;
		for (int i = 0; i < loadoutCombos.length; i++) {
			if (comboMatchesEvent(loadoutCombos[i], event)) { matchedIndex = i; break; }
		}
		if (matchedIndex < 0) return true;

		int inventorySlot = LOADOUT_SLOTS[matchedIndex];

		// Sanity bound against the real container's own slot count (not the player's 36 always appended
		// after it) — guards against a differently-shaped screen whose title just happens to also contain
		// "loadouts", rather than trusting the fixed list blindly.
		List<Slot> allSlots = screen.getMenu().slots;
		int containerSlotCount = Math.max(0, allSlots.size() - 36);
		if (inventorySlot >= containerSlotCount) return false;

		lastUsedSlot = inventorySlot;
		client.gameMode.handleContainerInput(screen.getMenu().containerId, inventorySlot, 0, ContainerInput.PICKUP, player);
		return false;
	}

	@Override
	public String getDescription() {
		return "While the Loadouts menu is open, pressing 1-9 selects the matching loadout directly instead of clicking it with the mouse.";
	}
}
