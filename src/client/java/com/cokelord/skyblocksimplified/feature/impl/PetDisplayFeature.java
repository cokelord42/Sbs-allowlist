package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.util.SkullTextureUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request: "Add a 'Show currently selected pet' module... render the pet itself as a skull with
 * the text (the text should be the color of the rarity of the pet)." The first version of this assumed
 * Hypixel's tab list shows a "Pet: [Lvl N] Name" line — confirmed wrong (no such line exists), which is
 * why it "didn't seem to do anything" per the follow-up report. Rewritten against Devonian's own confirmed
 * {@code PetDisplay.kt}: a summoned/despawned/autopet-swapped pet is announced via real chat lines whose
 * name is colored by the pet's actual rarity through the message Component's own real {@link Style} (not
 * literal legacy codes) — extracted the same way {@link DamageTruncatorFeature}'s own color fix does. The
 * skull ICON specifically is never sent through chat at all; the only place its real skin texture is ever
 * visible client-side is the real Pets menu GUI's own player-head items, so this also tick-scans that menu
 * while it's open (same as Devonian) to capture skins into a session-only name-&gt;texture cache, used the
 * next time (or immediately, if the menu happens to already be open) that same pet is the current one.
 */
public class PetDisplayFeature extends Feature implements MoveableWidget {
	// https://regex101.com/r/yLlhXf/1 equivalent, plain-text (Component#getString() strips real Style
	// formatting entirely, so no § run can appear here even though Devonian's own regex — written against
	// raw un-styled strings, not a real Component — expects one).
	private static final Pattern SUMMON_PATTERN = Pattern.compile("^You summoned your ([A-Za-z ]+?)(?: ✦)?!$");
	private static final Pattern DESPAWN_PATTERN = Pattern.compile("^You despawned your ([A-Za-z ]+?)(?: ✦)?!$");
	// Real, confirmed shape from Devonian's own autoPetRuleRegex — Autopet swaps a pet WITHOUT ever sending
	// the plain "You summoned your..." line above, so it needs its own trigger or the display goes stale
	// every time Autopet (not the player) is the one doing the swapping.
	//
	// Real bug found (per user report — still not detecting Autopet swaps, "make sure you are regex matching
	// like the pest thing"): the strict "^...$" full-line anchor above demanded an exact byte-for-byte shape
	// (a specific bracket/space/exclamation layout, nothing before "Autopet" or after "VIEW RULE"), which is
	// exactly the same class of bug PestSpawnAlertFeature's own doc comment already found and fixed for real
	// Hypixel pest-spawn lines — real Hypixel chat lines routinely splice in extra tier-tag brackets, symbols,
	// or trailing text this mod hasn't seen a live sample of. Rewritten the same way that fix was: tolerant
	// `\D*?` gaps between the fixed keywords instead of literal spaces/brackets, matched with `.find()`
	// against the whole line instead of `.matches()` against the whole line, so it survives formatting this
	// codebase hasn't specifically confirmed byte-for-byte (case-insensitive too, matching that same fix).
	//
	// Second real bug found (per user's own real captured line: "Autopet equipped your [Lvl 100] Black Cat
	// ✦! VIEW RULE" — a genuinely two-word pet name): the name group above was ITSELF lazy
	// ("(?:\s[A-Za-z]+)*?"), and since the trailing "\D*?VIEW" is also lazy and \D matches letters/spaces
	// too, the engine finds a shorter overall match by leaving the name group at its minimum (zero extra
	// words) and letting the trailing \D*? swallow the rest of the name instead — silently capturing "Black"
	// alone for "Black Cat". Made the name group greedy (no trailing "?") so it consumes every real name
	// word before backtracking, matching the "PestSpawnAlertFeature"-style tolerance concept without
	// re-introducing this exact class of under-capture bug.
	private static final Pattern AUTOPET_PATTERN = Pattern.compile(
		"Autopet\\D*?equipped\\D*?your\\D*?\\[Lvl\\D*?(\\d+)]\\D*?([A-Za-z]+(?:\\s[A-Za-z]+)*)\\D*?VIEW\\D*?RULE",
		Pattern.CASE_INSENSITIVE);
	// Real, standard Hypixel Skyblock chat line sent when the currently summoned pet gains a level from XP
	// (killing mobs, etc.), independent of any summon/despawn/autopet event — without this the displayed
	// level goes stale the moment the active pet levels up mid-session, only correcting itself the next time
	// it's re-summoned. No pet name in this line (only the currently active pet can level up while
	// summoned), so it only ever applies to whatever pet is already tracked as current.
	private static final Pattern LEVEL_UP_PATTERN = Pattern.compile("^Your pet leveled up to level (\\d+)!$");
	private static final Pattern PETS_MENU_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Pets$");
	private static final Pattern PETS_MENU_NAME = Pattern.compile("^(?:⭐ )?\\[Lvl (\\d+)](?: \\[\\d+✦])? ([A-Za-z ]+?)(?: ✦)?$");

	// See the HudElementRegistry render callback's own doc comment for why this is tracked manually via
	// ScreenEvents rather than a `mc.screen == null` check.
	private static volatile boolean blockingScreenOpen = false;

	private String currentPetName = null;
	private Integer currentPetLevel = null;
	private int currentPetColor = 0xFFFFFFFF;
	private ItemStack petIcon = ItemStack.EMPTY;
	private boolean compact = false;

	// Session-only: real pet name -> its skull skin texture, captured the last time the Pets menu was open
	// with that pet visible. Never persisted — a stale texture from a previous session isn't worth the
	// complexity of serializing skin/signature pairs for what's purely a "nice to have if we've seen it"
	// icon; the name+color from chat alone already satisfies the report's core ask either way.
	private final Map<String, String> skinCacheByName = new HashMap<>();

	private final HudPosition defaultPosition = new HudPosition(0.01f, 0.5f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static boolean listenersRegistered = false;
	private static PetDisplayFeature instance;

	public PetDisplayFeature() {
		super("pet_display", "Show Currently Selected Pet", FeatureCategory.INVENTORY, false);
		instance = this;
		HudWidgetRegistry.register(this);
		ensureListenersRegistered();
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	/** Real active-pet name, tracked regardless of whether this feature's own HUD widget is enabled — see
	 *  {@link #ensureListenersRegistered()}'s doc comment. Used by DungeonScoreCalculatorFeature to model the
	 *  Spirit Pet's real -1 death-penalty reduction (confirmed formula: {@code 2 * deaths - (spirit ? 1 : 0)},
	 *  cross-checked against devonian's own {@code Dungeons.kt}). Null/not "Spirit" is treated as "no Spirit
	 *  Pet" rather than assumed true — devonian's own port never actually resolves this (hardcodes true
	 *  unconditionally), so there's no real reference behavior to match here beyond the formula itself. */
	public static boolean isSpiritPetActive() {
		return instance != null && "Spirit".equals(instance.currentPetName);
	}

	// Real gap found (per "Spirit Pet detection" backlog item): DungeonScoreCalculatorFeature needs to know
	// the active pet's name to model the Spirit Pet's real -1 death-penalty reduction, regardless of whether
	// this feature's own HUD widget ("Show Currently Selected Pet") is toggled on — previously the chat/Pets-
	// menu tracking below only ever ran while THIS feature was enabled, so a user who never turned the widget
	// on could never get correct death-penalty scoring either. Tracking is now always-on background infra
	// (same pattern as SuperboomUseTracker), registered once at construction; only the HUD render itself
	// stays gated on isVisible()/isEnabled().
	static void ensureListenersRegistered() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (instance != null && !overlay) {
				try {
					instance.onChatMessage(message);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Pet Display chat listener threw, skipping this line", e);
				}
			}
			return true;
		});
		// Real bug found (per user report — "still doesnt support autopet rules", real line confirmed:
		// "Autopet equipped your [Lvl 200] Golden Dragon! VIEW RULE"): the regex itself already matches that
		// exact string (verified in isolation), so the actual gap is that the listener above only ever looks
		// at normal chat (overlay == false) — every other "VIEW RULE"/click-prompt-style Hypixel line in this
		// codebase (see AbilityCooldownTimerFeature's own mana-ability action-bar trigger) arrives as an
		// action-bar message instead, which that listener explicitly skips. Since it's not confirmed which
		// channel Autopet's own line actually uses, this checks the action-bar channel too, independently of
		// the chat-only listener above — harmless if Autopet's line turns out to only ever use one or the
		// other.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (instance == null || !overlay) return;
			try {
				instance.onAutopetActionBar(message);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Pet Display action-bar listener threw, skipping this line", e);
			}
		});
		// Real bug found (per user report — "the pet display is still rendering above guis, and it shows up in
		// the storage overlay for example which makes it a little annoying"): Fabric's HudElementRegistry layers
		// fire every frame regardless of whether a real Screen is open — unlike vanilla's own HUD, nothing here
		// was gating on screen state at all, so this painted straight over any open GUI, Storage Overlay
		// included (whose own panel is drawn from a separate ScreenEvents render hook, not this one, so it
		// never got a say in the ordering). This MC version's own Minecraft class no longer exposes a public
		// `screen` field/getter at all (confirmed by decompiling the real mapped client jar — every method that
		// used to expose it, like setScreen, is now paired with no visible getter), so screen-open state is
		// tracked locally the same way every other feature in this codebase already does it — via
		// ScreenEvents.AFTER_INIT/remove — rather than reaching for a field that doesn't exist here. Hidden
		// behind any real screen the same way the vanilla HUD itself is, except ChatScreen — typing in chat
		// should still show it, matching vanilla's own "hotbar/health stay visible while chat is open" behavior.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			boolean isChat = screen instanceof net.minecraft.client.gui.screens.ChatScreen;
			if (!isChat) blockingScreenOpen = true;
			ScreenEvents.remove(screen).register(s -> {
				if (!isChat) blockingScreenOpen = false;
			});
		});
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "pet_display"), (graphics, tracker) -> {
			if (instance == null || !instance.isVisible()) return;
			if (blockingScreenOpen) return;
			Minecraft mc = Minecraft.getInstance();
			int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
			int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
			instance.render(graphics, x, y, instance.position.scale);
		});
	}

	private void onChatMessage(Component message) {
		String text = message.getString();
		Matcher summon = SUMMON_PATTERN.matcher(text);
		if (summon.matches()) {
			setCurrentPet(summon.group(1), null, colorForSubstring(message, summon.group(1)));
			return;
		}
		Matcher despawn = DESPAWN_PATTERN.matcher(text);
		if (despawn.matches()) {
			currentPetName = null;
			currentPetLevel = null;
			petIcon = ItemStack.EMPTY;
			return;
		}
		Matcher autopet = AUTOPET_PATTERN.matcher(text);
		if (autopet.find()) {
			int level = Integer.parseInt(autopet.group(1));
			setCurrentPet(autopet.group(2), level, colorForSubstring(message, autopet.group(2)));
			return;
		}
		Matcher levelUp = LEVEL_UP_PATTERN.matcher(text);
		if (levelUp.matches() && currentPetName != null) {
			currentPetLevel = Integer.parseInt(levelUp.group(1));
		}
	}

	/** See the action-bar listener's own registration doc comment for why this exists as a separate path
	 *  from {@link #onChatMessage}. */
	private void onAutopetActionBar(Component message) {
		Matcher autopet = AUTOPET_PATTERN.matcher(message.getString());
		if (!autopet.find()) return;
		int level = Integer.parseInt(autopet.group(1));
		setCurrentPet(autopet.group(2), level, colorForSubstring(message, autopet.group(2)));
	}

	private void setCurrentPet(String name, Integer level, int color) {
		currentPetName = name.strip();
		currentPetLevel = level;
		currentPetColor = color;
		String skin = skinCacheByName.get(currentPetName);
		petIcon = skin != null ? SkullTextureUtil.buildHeadWithTexture(skin) : ItemStack.EMPTY;
		// Diagnostic for the still-open "Pet icon still isnt caching properly" report (specifically: no icon
		// on the first summon of a session, before the Pets menu is reopened) — the persisted skinCacheByName
		// SHOULD already have this pet's skin from a previous session's menu scan, so a miss here on a pet
		// that's genuinely been seen before means either a name-key mismatch (e.g. this chat line's parsed
		// name doesn't exactly match the Pets menu's own parsed name for the same pet) or the cache not being
		// loaded yet at this point — logging the exact key and cache size lets a real repro tell those apart.
	}

	/** Hypixel colors the pet name in these chat lines by the pet's actual rarity (common=white,
	 *  uncommon=green, rare=blue, epic=purple, legendary=gold, mythic=pink) via the message Component's
	 *  own real Style, not literal legacy codes baked into the text — same technique
	 *  {@code DamageTruncatorFeature}'s own team-color fix uses for a different real-Style-vs-raw-text
	 *  case. Falls back to white if no styled run overlaps the captured name at all (shouldn't normally
	 *  happen for a real summon/autopet line). */
	private static int colorForSubstring(Component component, String needle) {
		Integer rgb = component.visit((style, text) -> {
			if (style.getColor() != null && !text.isBlank() && (text.contains(needle) || needle.contains(text.strip()))) {
				return Optional.of(style.getColor().getValue());
			}
			return Optional.<Integer>empty();
		}, Style.EMPTY).orElse(null);
		return rgb != null ? (0xFF000000 | rgb) : 0xFFFFFFFF;
	}

	@Override
	public void onTick(Minecraft client) {
		// Real, confirmed mechanism (Devonian's own PetDisplay.kt): Hypixel never sends a pet's actual
		// skull skin texture through chat — the only place it's ever visible client-side is the real Pets
		// menu GUI's own player-head items. Tick-scanned (not a one-shot on open) since Hypixel populates
		// these slots with a SetSlot packet a moment after the menu itself opens, same "detection may lag
		// a frame or two behind the screen opening" reasoning TerminalSolverFeature's ensureDetected
		// already documents elsewhere in this codebase.
		if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
		if (!PETS_MENU_TITLE.matcher(screen.getTitle().getString()).matches()) return;
		for (Slot slot : screen.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (!stack.is(Items.PLAYER_HEAD)) continue;
			Component nameComponent = stack.get(DataComponents.CUSTOM_NAME);
			if (nameComponent == null) continue;
			Matcher nameMatch = PETS_MENU_NAME.matcher(nameComponent.getString());
			if (!nameMatch.matches()) continue;
			String name = nameMatch.group(2).strip();
			String skin = SkullTextureUtil.fromItem(stack);
			if (skin != null) skinCacheByName.put(name, skin);
			if (!isSelectedInMenu(stack)) continue;
			if (name.equals(currentPetName)) {
				if (petIcon.isEmpty() && skin != null) petIcon = SkullTextureUtil.buildHeadWithTexture(skin);
			} else {
				// The chat line that would normally set this hasn't fired yet this session (the menu was
				// opened before any summon/despawn/autopet line was ever seen) — the menu's own "Click to
				// despawn!" marker on this slot is just as authoritative, so trust it directly instead of
				// staying blank until the player re-triggers a chat line.
				String levelGroup = nameMatch.group(1);
				setCurrentPet(name, levelGroup != null ? Integer.parseInt(levelGroup) : null, colorForSubstring(nameComponent, name));
			}
		}
	}

	private static boolean isSelectedInMenu(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return false;
		for (Component line : lore.lines()) {
			if ("Click to despawn!".equals(line.getString())) return true;
		}
		return false;
	}

	@Override
	public String getId() { return super.getId(); }

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() { return isEnabled() && currentPetName != null; }

	@Override
	public boolean hasVisibleContent() { return currentPetName != null; }

	// Per user report ("The level '[Lvl 200]' Should be gray always"): the level prefix is real vanilla UI
	// chrome around the pet's own name, not part of the pet itself, so it stays this fixed neutral gray
	// regardless of the pet's rarity color — same reasoning every other "gray bracket around a colored
	// name" convention in this mod already follows.
	private static final int LEVEL_TEXT_COLOR = 0xFFAAAAAA;
	// Per user report ("The image of the pet is also hanging a bit low. Make it go up like 2 pixels."): the
	// vanilla player-head item render has a bit of built-in bottom padding that reads as "hanging low"
	// relative to the text baseline next to it — nudged up 2px to compensate, text position unaffected.
	private static final int ICON_Y_OFFSET = -2;

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		if (currentPetName == null) return new Size(0, 0);
		try {
			Font font = Minecraft.getInstance().font;
			boolean hasIcon = !petIcon.isEmpty();
			// Per user request ("Add Pet Display compact mode (icon-only)"): only actually goes icon-only
			// when there IS a real icon to show — with no cached skin yet (see this class's own doc comment
			// on why the skull texture isn't always available immediately), showing nothing at all would be
			// strictly worse than the normal text fallback, so compact mode only takes effect once a real
			// icon exists.
			boolean iconOnly = compact && hasIcon;
			String levelText = (!iconOnly && currentPetLevel != null) ? "[Lvl " + currentPetLevel + "] " : "";
			int iconSize = 16;
			int textX = x + (hasIcon ? iconSize + 4 : 0);
			int width = iconOnly ? iconSize
				: (hasIcon ? iconSize + 4 : 0) + font.width(levelText) + font.width(currentPetName);
			int height = Math.max(hasIcon ? iconSize : 0, iconOnly ? 0 : font.lineHeight);

			if (Math.abs(scale - 1f) < 0.01f) {
				drawContent(graphics, font, x, y, textX, levelText, hasIcon, iconOnly, height);
			} else {
				graphics.pose().pushMatrix();
				graphics.pose().translate(x, y);
				graphics.pose().scale(scale);
				graphics.pose().translate(-x, -y);
				drawContent(graphics, font, x, y, textX, levelText, hasIcon, iconOnly, height);
				graphics.pose().popMatrix();
			}
			return new Size(Math.round(width * scale), Math.round(height * scale));
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Pet Display render failed, skipping this frame", e);
			return new Size(0, 0);
		}
	}

	private void drawContent(GuiGraphicsExtractor graphics, Font font, int x, int y, int textX, String levelText, boolean hasIcon, boolean iconOnly, int height) {
		if (hasIcon) graphics.item(petIcon, x, y + ICON_Y_OFFSET);
		if (iconOnly) return;
		int textY = y + (height - font.lineHeight) / 2;
		int nameX = textX;
		if (!levelText.isEmpty()) {
			graphics.text(font, levelText, textX, textY, LEVEL_TEXT_COLOR);
			nameX += font.width(levelText);
		}
		graphics.text(font, currentPetName, nameX, textY, currentPetColor);
	}

	public boolean isCompact() { return compact; }
	public void setCompact(boolean value) { compact = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		obj.addProperty("compact", compact);
		// Per user report ("Pet display isnt caching. It doesnt remember when i restart my game"): the active
		// pet's name/level/color/skin used to live purely in-memory, wiped on every relog — this mirrors
		// StorageOverlayFeature's own "cache the last real observation to config, restore it on next launch"
		// pattern instead of waiting for a fresh chat line every single session.
		if (currentPetName != null) {
			obj.addProperty("petName", currentPetName);
			if (currentPetLevel != null) obj.addProperty("petLevel", currentPetLevel);
			obj.addProperty("petColor", currentPetColor);
		}
		// Real bug found (per user report — "the mod doesnt cache the head model/texture, so it only displays
		// the [lvl 100] jellyfish, like if it wasnt compact mode... atleast until i open my pets menu and then
		// it refreshes"): only the CURRENT pet's own skin used to be persisted here — every OTHER pet's skin
		// this session had ever actually seen inside the real Pets menu was thrown away on relog, so summoning
		// a DIFFERENT pet via chat (autopet/!pet swap etc.) before reopening the Pets menu that session always
		// showed text-only until the menu was opened again to repopulate skinCacheByName from scratch. The
		// whole cache is now persisted — once a pet's real skin has ever been seen in the Pets menu, it's
		// remembered permanently and available immediately on the very next summon, in any future session,
		// with no need to reopen the menu first.
		JsonObject skins = new JsonObject();
		for (Map.Entry<String, String> entry : skinCacheByName.entrySet()) {
			skins.addProperty(entry.getKey(), entry.getValue());
		}
		obj.add("skinCache", skins);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
		if (obj.has("compact")) compact = obj.get("compact").getAsBoolean();
		// Real bug found (see savePersistedData's own doc comment on skinCache): restores every pet skin
		// this account has ever seen in the real Pets menu, not just the one that happened to be active at
		// last save, so a chat-only summon/autopet line for ANY previously-seen pet can resolve its real icon
		// immediately this session instead of waiting for the Pets menu to be reopened.
		if (obj.has("skinCache") && obj.get("skinCache").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("skinCache").entrySet()) {
				if (entry.getValue().isJsonPrimitive()) skinCacheByName.put(entry.getKey(), entry.getValue().getAsString());
			}
		}
		if (obj.has("petName")) {
			currentPetName = obj.get("petName").getAsString();
			currentPetLevel = obj.has("petLevel") ? obj.get("petLevel").getAsInt() : null;
			currentPetColor = obj.has("petColor") ? obj.get("petColor").getAsInt() : 0xFFFFFFFF;
			// Legacy single-skin field from before the full skinCache was persisted — still honored so an
			// existing config isn't silently dropped, but the map lookup below (now populated from the real
			// full cache above) is the primary path going forward.
			if (obj.has("petSkin")) skinCacheByName.putIfAbsent(currentPetName, obj.get("petSkin").getAsString());
			String skin = skinCacheByName.get(currentPetName);
			if (skin != null) petIcon = SkullTextureUtil.buildHeadWithTexture(skin);
		}
	}

	@Override
	public String getDescription() {
		return "Shows your currently-selected pet as a skull icon with its name colored by rarity.";
	}
}
