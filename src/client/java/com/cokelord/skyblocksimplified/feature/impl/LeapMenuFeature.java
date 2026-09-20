package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws large clickable name/class quadrant boxes over the vanilla Spirit Leap / "Teleport to Player"
 * chest screen, with optional corner keybinds — no more mousing over a tiny 16x16 head icon mid-fight.
 * Ported from Odin's {@code LeapMenu.kt}, drawn as an overlay on the existing vanilla screen (this
 * codebase's {@link EstimatedItemValueFeature} already establishes the "extend a vanilla
 * {@code AbstractContainerScreen} via {@code ScreenEvents}" pattern this reuses) rather than a full custom
 * {@code Screen} replacement, since Odin's own implementation turns out to work the same way. Renders each
 * teammate's real face/skin icon via {@link PlayerFaceExtractor} next to their name/class text. Odin's own
 * "Custom sorting" order (which needs its own configuration command) is dropped in favor of this port's
 * {@link SortMode} enum (class priority / alphabetical class / alphabetical name / none).
 */
public class LeapMenuFeature extends Feature {
	public enum SortMode { ODIN, ALPHABETICAL_CLASS, ALPHABETICAL_NAME, NONE }

	private static final Pattern LEAPED_PATTERN = Pattern.compile("You have teleported to (\\w{1,16})!");
	private static final Pattern BARE_USERNAME = Pattern.compile("^\\w{1,16}$");
	private static final int BOX_WIDTH = 200;
	private static final int BOX_HEIGHT = 75;

	private SortMode sortMode = SortMode.ODIN;
	private boolean onlyShowClass = false;
	private boolean colorStyle = false;
	// Matches MainScreen's own panel background (COLOR_PANEL_BG) per user request to reuse the mod's palette.
	private int backgroundColor = 0xF0101010;
	// Per user report ("Background color in the leap menu also does nothing. It should render the whole
	// screen as the color in the background, like normal minecraft gui does. Let users also select
	// opacity."): backgroundColor used to only ever fill the individual quadrant boxes (when colorStyle is
	// off) — nothing painted it across the actual screen the way vanilla's own inventory-screen backdrop
	// darkens everything behind the GUI. renderFullScreenBackground now does that, using this same color's
	// RGB with its alpha driven by this new, separately adjustable opacity slider instead of whatever alpha
	// happened to be baked into the color itself.
	private float backgroundOpacity = 0.85f;
	private float renderScale = 1f;
	private boolean leapAnnounce = false;
	// Per user request: while on, clicking a real head marker on the Dungeon Map leaps to that player,
	// instead of (or alongside) the quadrant boxes. Round 2 (per user correction — "remove the extra map
	// that pops up in the leap menu, let players click heads on the real/original map"): this used to draw
	// a whole SECOND copy of the map at a hardcoded corner position specifically to have something to hit-
	// test against — DungeonMapFeature's own standalone widget already kept rendering normally through this
	// screen the whole time, so that duplicate was pure clutter. Click/keybind handling below now reads
	// DungeonMapFeature's own per-frame marker hit list directly instead.
	private boolean syncWithMap = false;

	private final KeyMapping topLeft = key("top_left");
	private final KeyMapping topRight = key("top_right");
	private final KeyMapping bottomLeft = key("bottom_left");
	private final KeyMapping bottomRight = key("bottom_right");

	private static boolean listenersRegistered = false;
	private static LeapMenuFeature instance;

	public LeapMenuFeature() {
		super("leap_menu", "Leap Menu", FeatureCategory.COMBAT, false);
		instance = this;
	}

	private static KeyMapping key(String id) {
		KeyMapping mapping = new KeyMapping("key.skyblocksimplified.leap_" + id,
			InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), com.cokelord.skyblocksimplified.keybind.ModKeyCategory.MAIN);
		KeyMappingHelper.registerKeyMapping(mapping);
		return mapping;
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	public List<KeyMapping> getKeybinds() {
		return List.of(topLeft, topRight, bottomLeft, bottomRight);
	}

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled() && instance.leapAnnounce && DungeonState.isInDungeon()) {
					Matcher m = LEAPED_PATTERN.matcher(message.getString());
					if (m.find()) {
						Minecraft mc = Minecraft.getInstance();
						if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand("pc Leaped to " + m.group(1) + "!");
					}
				}
				return true;
			});

			ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
				if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
				if (!isLeapScreen(containerScreen)) return;

				// Real bug found (per user report — "[Leap Menu is] completely unclickable when 'Sync with
				// Map' is on"): beforeMouseClick can only OBSERVE a click, never cancel it — the real vanilla
				// click always still went through afterward, at the real (cancelled-render, stale-hoveredSlot)
				// GUI's own click routing, racing/clobbering the deliberate containerInput packet leapTo already
				// sends. TerminalSolverFeature's own custom-GUI mode hit and already solved this exact class of
				// bug (see its own onPanelClick registration's doc comment) by switching to allowMouseClick and
				// returning false to swallow the real click outright — same fix applied here.
				ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
					if (instance == null || !instance.isEnabled() || !isLeapScreen(containerScreen)) return true;
					instance.onQuadrantClick(containerScreen, event.x(), event.y());
					return false;
				});

				// Real bug found (per user report — "the keybinds also dont work"): vanilla only ever
				// translates a raw key-down into a plain KeyMapping's click/isDown bookkeeping through
				// Minecraft.handleKeybinds(), which is itself skipped entirely whenever a Screen without
				// passEvents=true is open — true for this vanilla chest GUI like almost every other Screen.
				// tickKeybinds()'s old per-tick consumeClick() polling could therefore never see a click
				// while this exact menu was open, the only place these keybinds are ever meant to fire.
				// Reading the raw key event directly through Fabric's ScreenKeyboardEvents (same mechanism
				// NeuStyleButtonsFeature already uses for in-screen key handling) bypasses that entirely.
				ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
					if (instance == null || !instance.isEnabled() || !isLeapScreen(containerScreen)) return true;
					instance.onKeyPress(containerScreen, event);
					return true;
				});
			});
		}
	}

	private static boolean isLeapScreen(AbstractContainerScreen<?> screen) {
		String title = screen.getTitle().getString();
		return title.equalsIgnoreCase("Spirit Leap") || title.equalsIgnoreCase("Teleport to Player");
	}

	/** Whether the real vanilla render of this screen should be cancelled and replaced with
	 *  {@link #renderReplacement} — driven by {@code LeapMenuCustomGuiRenderMixin}/
	 *  {@code LeapMenuCustomGuiBackgroundMixin}, the same cancel-and-replace mechanism
	 *  TerminalSolverFeature's own Custom GUI mode already proved (see those mixins' doc comments). Per user
	 *  report ("rendering a black box over the original gui instead of hiding it"): the previous approach
	 *  painted an opaque rect via ScreenEvents.afterExtract, which runs AFTER the real vanilla frame/slots
	 *  already rendered — paint-over, not cancellation, so anything the paint-over didn't perfectly cover
	 *  (or that rendered after it, like the vanilla tooltip) could still show through. */
	public static boolean shouldReplaceRender(AbstractContainerScreen<?> screen) {
		return instance != null && instance.isEnabled() && isLeapScreen(screen);
	}

	/** Called from the render mixin once {@link #shouldReplaceRender} has confirmed the real render was
	 *  just cancelled.
	 *
	 *  <p>Round 2 (per user request — "remove the extra map that pops up in the leap menu, let players
	 *  click heads on the real/original map"): no longer draws its own copy of the map at all — {@link
	 *  DungeonMapFeature}'s own standalone HUD widget already renders normally while this replacement screen
	 *  is up (it doesn't gate on "is a screen open" at all), so with the old duplicate removed, whatever's
	 *  actually on screen right now already IS the real map; onQuadrantClick below just needs to hit-test
	 *  against it. */
	public static void renderReplacement(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		if (instance == null) return;
		try {
			instance.renderFullScreenBackground(graphics);
			instance.renderQuadrants(graphics);
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Leap Menu render failed, skipping this frame", e);
		}
	}

	private void renderFullScreenBackground(GuiGraphicsExtractor graphics) {
		// Real bug found (per user report — "The dungeon map should stay visible instead of getting the gui
		// darkening treatment when sync with map is on so the user can see the heads"): this full-screen tint
		// paints over EVERYTHING, including DungeonMapFeature's own HUD widget (which — per renderReplacement's
		// own doc comment — renders normally underneath regardless of this replacement screen being up). That's
		// fine when Sync with Map is off (there's nothing real to see behind the quadrant menu), but with it on,
		// the map IS the real content the player needs to read (teammate head markers included) — darkening it
		// the same way as an empty vanilla background just obscures the thing they turned this on to see.
		if (syncWithMap) return;
		Minecraft mc = Minecraft.getInstance();
		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		int alpha = Math.round(Math.max(0f, Math.min(1f, backgroundOpacity)) * 255f);
		if (alpha <= 0) return;
		graphics.fill(0, 0, width, height, (alpha << 24) | (backgroundColor & 0xFFFFFF));
	}

	private List<DungeonPlayer> sortedTeammates() {
		List<DungeonPlayer> teammates = new ArrayList<>(DungeonState.getTeammatesNoSelf());
		return switch (sortMode) {
			case ODIN -> odinSorting(teammates);
			case ALPHABETICAL_CLASS -> {
				teammates.sort((a, b) -> {
					int c = Integer.compare(a.clazz.ordinal(), b.clazz.ordinal());
					return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
				});
				yield teammates;
			}
			case ALPHABETICAL_NAME -> {
				teammates.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
				yield teammates;
			}
			case NONE -> teammates;
		};
	}

	/** Places each teammate in their class's default quadrant, falling back to any empty quadrant for
	 *  collisions — ported from Odin's {@code odinSorting}. */
	private static List<DungeonPlayer> odinSorting(List<DungeonPlayer> players) {
		DungeonPlayer[] result = new DungeonPlayer[4];
		List<DungeonPlayer> overflow = new ArrayList<>();

		List<DungeonPlayer> byPriority = new ArrayList<>(players);
		byPriority.sort((a, b) -> Integer.compare(a.clazz.priority, b.clazz.priority));
		for (DungeonPlayer player : byPriority) {
			int quadrant = defaultQuadrant(player.clazz);
			if (result[quadrant] == null) result[quadrant] = player;
			else overflow.add(player);
		}
		for (int i = 0; i < 4 && !overflow.isEmpty(); i++) {
			if (result[i] == null) result[i] = overflow.remove(0);
		}
		List<DungeonPlayer> list = new ArrayList<>();
		for (DungeonPlayer p : result) if (p != null) list.add(p);
		return list;
	}

	private static int defaultQuadrant(DungeonClass clazz) {
		return switch (clazz) {
			case ARCHER -> 0; case BERSERK -> 1; case HEALER -> 2; case MAGE -> 3; case TANK -> 3; case EMPTY -> 0;
		};
	}

	private void renderQuadrants(GuiGraphicsExtractor graphics) {
		List<DungeonPlayer> teammates = sortedTeammates();
		if (teammates.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		// The real vanilla background/slots are now cancelled outright by LeapMenuCustomGuiBackgroundMixin/
		// LeapMenuCustomGuiRenderMixin before this ever runs (see shouldReplaceRender's doc comment for why
		// painting an opaque rect over them here — the old approach — wasn't good enough), so this only
		// needs to draw the quadrant boxes themselves now, same as before.
		int halfW = mc.getWindow().getGuiScaledWidth() / 2;
		int halfH = mc.getWindow().getGuiScaledHeight() / 2;

		for (int i = 0; i < 4 && i < teammates.size(); i++) {
			DungeonPlayer player = teammates.get(i);
			int col = i % 2, row = i / 2;
			int nearX = col == 0 ? halfW - 24 : halfW + 24;
			int nearY = row == 0 ? halfH - 24 : halfH + 24;
			int w = Math.round(BOX_WIDTH * renderScale);
			int h = Math.round(BOX_HEIGHT * renderScale);
			int x = col == 0 ? nearX - w : nearX;
			int y = row == 0 ? nearY - h : nearY;

			// Per user request ("make all buttons darker gray and make the outline the class color instead"):
			// the old class-color/backgroundColor FILL choice is gone — every quadrant box now fills with the
			// same fixed dark gray and gets a real class-colored outline around it instead, so the per-class
			// color is still immediately visible without making white/class-colored text hard to read against
			// a bright class-color fill. colorStyle now only ever affects the TEXT color below (white vs
			// class-colored) — the one part of its old meaning that's still independently useful.
			RenderUtil.fillRounded(graphics, x, y, x + w, y + h, 9, 0xF0303030);
			RenderUtil.fillRoundedRing(graphics, x, y, x + w, y + h, 9, 2, player.clazz.color);

			int iconSize = Math.round(28 * renderScale);
			int iconX = x + 8;
			int iconY = y + (h - iconSize) / 2;
			// Real bug found (per user report — "Leap menu does not show heads mostly. Sometimes it shows 1 or
			// 2, it should always show the heads of the players when opened"): this only ever drew a head when
			// player.entity was a currently-live AbstractClientPlayer — but the leap menu is opened by holding
			// Spirit Leap mid-fight, exactly when most teammates are commonly out of render distance or in a
			// different room and have no live tracked entity at all, so most/all heads silently skipped. Same
			// root cause and fix already found for the Dungeon Map's own head rendering (see that feature's
			// drawTeammateMarker call site): Minecraft's own tab list (PlayerInfo, from
			// ClientboundPlayerInfoUpdatePacket) tracks every real player on the server by name regardless of
			// entity-render distance, and carries the same real PlayerSkin a live entity's own getSkin() would
			// return — resolved by name here instead of requiring a live entity.
			net.minecraft.world.entity.player.PlayerSkin skin = player.entity instanceof AbstractClientPlayer acp
				? acp.getSkin() : null;
			if (skin == null) {
				var conn = mc.getConnection();
				var info = conn != null ? conn.getPlayerInfo(player.name) : null;
				if (info != null) skin = info.getSkin();
			}
			if (skin != null) {
				try {
					PlayerFaceExtractor.extractRenderState(graphics, skin, iconX, iconY, iconSize);
				} catch (Exception ignored) {
					// Skin texture not downloaded/ready yet — just skip the icon this frame.
				}
			}
			int textX = iconX + iconSize + 8;

			// Per user request ("make the text bold"): both lines rendered as real bold Components instead
			// of plain strings.
			int textColor = colorStyle ? 0xFFFFFFFF : player.clazz.color;
			String primary = onlyShowClass ? player.clazz.name() : player.name;
			graphics.text(mc.font, Component.literal(primary).withStyle(Style.EMPTY.withBold(true)), textX, y + Math.round(h / 2.5f), textColor);

			if (!onlyShowClass || player.isDead) {
				String secondary = player.isDead ? "DEAD" : player.clazz.name();
				int secondaryColor = player.isDead ? 0xFFFF5555 : 0xFFFFFFFF;
				int secondaryY = y + Math.round(h / 1.7f);
				int secondaryTextX = textX;
				// Per user request ("the classes have different items next to the class name, like bow for
				// archer, lingering regen potion for healer, a gray leather chestplate for tank, blaze rod
				// for mage and an iron sword for berserk") — skipped for the DEAD label itself, only shown
				// alongside the real class name line.
				if (!player.isDead) {
					ItemStack icon = classIcon(player.clazz);
					if (!icon.isEmpty()) {
						graphics.item(icon, secondaryTextX, secondaryY - 5);
						secondaryTextX += 18;
					}
				}
				graphics.text(mc.font, Component.literal(secondary).withStyle(Style.EMPTY.withBold(true)), secondaryTextX, secondaryY, secondaryColor);
			}
		}
	}

	/** Per user request — see renderQuadrants' own doc comment for the exact icon list. */
	private static ItemStack classIcon(DungeonClass clazz) {
		return switch (clazz) {
			case ARCHER -> new ItemStack(Items.BOW);
			case HEALER -> new ItemStack(Items.LINGERING_POTION);
			case TANK -> {
				ItemStack stack = new ItemStack(Items.LEATHER_CHESTPLATE);
				stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0x808080));
				yield stack;
			}
			case MAGE -> new ItemStack(Items.BLAZE_ROD);
			case BERSERK -> new ItemStack(Items.IRON_SWORD);
			case EMPTY -> ItemStack.EMPTY;
		};
	}

	private void onQuadrantClick(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
		// Real bug found (per user request — "sync with map should not disallow me from leaping regularly,
		// it should only add the option that allows me to click heads on the map"): this used to fully
		// REPLACE the normal quadrant-click behavior whenever Sync with Map was on — any click that didn't
		// land exactly on a map marker's own hitbox just returned and did nothing, with no fallback to the
		// ordinary quadrant leap. Now a map-marker hit is checked FIRST (an added option, not a replacement)
		// and only falls through to the normal quadrant-based leap below when the click didn't land on any
		// marker — Sync with Map stays purely additive.
		if (syncWithMap && DungeonMapFeature.getInstance() != null) {
			Minecraft mc = Minecraft.getInstance();
			String selfName = mc.player != null ? mc.player.getName().getString() : null;
			for (DungeonMapFeature.MarkerHit hit : DungeonMapFeature.getInstance().getLastMarkerHits()) {
				if (!hit.contains(mouseX, mouseY)) continue;
				if (hit.player().name.equals(selfName)) return;
				leapTo(hit.player(), screen);
				return;
			}
		}
		Minecraft mc = Minecraft.getInstance();
		int halfW = mc.getWindow().getGuiScaledWidth() / 2;
		int halfH = mc.getWindow().getGuiScaledHeight() / 2;
		int quadrant = (mouseY >= halfH ? 2 : 0) + (mouseX >= halfW ? 1 : 0);
		List<DungeonPlayer> teammates = sortedTeammates();
		if (quadrant >= teammates.size()) return;
		leapTo(teammates.get(quadrant), screen);
	}

	private void onKeyPress(AbstractContainerScreen<?> screen, net.minecraft.client.input.KeyEvent event) {
		// Real bug found (see onQuadrantClick's own doc comment — same "Sync with Map should be additive,
		// not a replacement" fix): the four corner keybinds used to be disabled entirely whenever Sync with
		// Map was on, even though they still map onto the same quadrant-sorted teammate list either way —
		// nothing about map-marker clicking actually requires giving these up.
		List<DungeonPlayer> teammates = sortedTeammates();
		KeyMapping[] keys = {topLeft, topRight, bottomLeft, bottomRight};
		for (int i = 0; i < keys.length; i++) {
			if (keys[i].isUnbound() || !keys[i].matches(event)) continue;
			if (i < teammates.size()) leapTo(teammates.get(i), screen);
		}
	}

	private void leapTo(DungeonPlayer player, AbstractContainerScreen<?> screen) {
		if (player.isDead) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal("This player is dead, can't leap."));
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		var menu = screen.getMenu();
		int foundIndex = -1;
		// Real bug found (per user report — "the spirit leap menu does not work when dead and trying to
		// haunt people"): this used to only scan a hardcoded slots-11-through-15 window and assume the head's
		// hover name was always exactly "<one word> <PlayerName>" (take everything after the first space).
		// Both assumptions hold for the normal alive-leap menu but have no confirmed guarantee for the
		// dead/haunting variant of this same screen, which may lay heads out differently or word the hover
		// name with a different number of leading words ("Haunt PlayerName" vs "Teleport to PlayerName", a
		// leading spectator/back slot shifting everything over, etc.). Scans every slot in the menu instead
		// of a fixed range, and extracts the LAST whitespace-token that looks like a real username (same
		// bracket/prefix-tolerant technique ChatCommandsFeature/PartyApi already use for chat-line usernames)
		// instead of assuming exactly two words — works regardless of how many words precede the name or
		// which slot indices the head actually ends up at.
		for (Slot slot : menu.slots) {
			String name = extractLastUsername(slot.getItem().getHoverName().getString());
			if (name.equalsIgnoreCase(player.name)) { foundIndex = slot.index; break; }
		}
		if (foundIndex < 0 || mc.player == null) return;
		mc.gameMode.handleContainerInput(menu.containerId, foundIndex, 0, ContainerInput.PICKUP, mc.player);
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal("Teleporting to " + player.name + "."));
	}

	private static String extractLastUsername(String hoverName) {
		String[] parts = hoverName.trim().split(" ");
		for (int i = parts.length - 1; i >= 0; i--) {
			if (BARE_USERNAME.matcher(parts[i]).matches()) return parts[i];
		}
		return hoverName;
	}

	public SortMode getSortMode() { return sortMode; }
	public void setSortMode(SortMode value) { sortMode = value; }
	public boolean isOnlyShowClass() { return onlyShowClass; }
	public void setOnlyShowClass(boolean value) { onlyShowClass = value; }
	public boolean isColorStyle() { return colorStyle; }
	public void setColorStyle(boolean value) { colorStyle = value; }
	public int getBackgroundColor() { return backgroundColor; }
	public void setBackgroundColor(int value) { backgroundColor = value; }
	public float getBackgroundOpacity() { return backgroundOpacity; }
	public void setBackgroundOpacity(float value) { backgroundOpacity = Math.max(0f, Math.min(1f, value)); }
	public float getRenderScale() { return renderScale; }
	public void setRenderScale(float value) { renderScale = Math.max(0.1f, Math.min(2f, value)); }
	public boolean isLeapAnnounce() { return leapAnnounce; }
	public void setLeapAnnounce(boolean value) { leapAnnounce = value; }
	public boolean isSyncWithMap() { return syncWithMap; }
	public void setSyncWithMap(boolean value) { syncWithMap = value; }

	// Per user report ("Some keybinds, like the leap menu keybinds, dont seem to save to config"): these are
	// real vanilla KeyMapping objects — MainScreen's own key-capture handler already calls
	// Minecraft.options.save() after rebinding one, so vanilla's OWN options.txt round-trips them fine, but
	// this feature's savePersistedData()/loadPersistedData() never touched them at all, unlike every other
	// keybind feature in this codebase (CustomLoadoutKeybindsFeature/PetKeybindsFeature/Dungeon Routes' next-
	// step key), which all store their bindings in THIS mod's own JSON. Any operation that only round-trips
	// this mod's config — export/import to another device, a config reset/restore — silently dropped these 4
	// binds even though every other keybind survived. Saved/restored here now too, using KeyMapping's own
	// real saveString()/InputConstants.getKey() serialization (the same format vanilla's options.txt itself
	// uses), so this mod's config is a complete, self-contained source of truth for these too.
	private static void restoreKey(KeyMapping mapping, String saved) {
		try {
			mapping.setKey(InputConstants.getKey(saved));
			KeyMapping.resetMapping();
		} catch (Exception ignored) {
			// A key name that no longer resolves (renamed/removed upstream) — leave the binding as-is
			// rather than crash the whole config load over one stale entry.
		}
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("sortMode", sortMode.name());
		obj.addProperty("onlyShowClass", onlyShowClass);
		obj.addProperty("colorStyle", colorStyle);
		obj.addProperty("backgroundColor", backgroundColor);
		obj.addProperty("backgroundOpacity", backgroundOpacity);
		obj.addProperty("renderScale", renderScale);
		obj.addProperty("leapAnnounce", leapAnnounce);
		obj.addProperty("syncWithMap", syncWithMap);
		obj.addProperty("topLeftKey", topLeft.saveString());
		obj.addProperty("topRightKey", topRight.saveString());
		obj.addProperty("bottomLeftKey", bottomLeft.saveString());
		obj.addProperty("bottomRightKey", bottomRight.saveString());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("sortMode")) { try { sortMode = SortMode.valueOf(obj.get("sortMode").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("onlyShowClass")) onlyShowClass = obj.get("onlyShowClass").getAsBoolean();
		if (obj.has("colorStyle")) colorStyle = obj.get("colorStyle").getAsBoolean();
		if (obj.has("backgroundColor")) backgroundColor = obj.get("backgroundColor").getAsInt();
		if (obj.has("backgroundOpacity")) backgroundOpacity = obj.get("backgroundOpacity").getAsFloat();
		if (obj.has("renderScale")) renderScale = obj.get("renderScale").getAsFloat();
		if (obj.has("leapAnnounce")) leapAnnounce = obj.get("leapAnnounce").getAsBoolean();
		if (obj.has("syncWithMap")) syncWithMap = obj.get("syncWithMap").getAsBoolean();
		if (obj.has("topLeftKey")) restoreKey(topLeft, obj.get("topLeftKey").getAsString());
		if (obj.has("topRightKey")) restoreKey(topRight, obj.get("topRightKey").getAsString());
		if (obj.has("bottomLeftKey")) restoreKey(bottomLeft, obj.get("bottomLeftKey").getAsString());
		if (obj.has("bottomRightKey")) restoreKey(bottomRight, obj.get("bottomRightKey").getAsString());
	}

	@Override
	public String getDescription() {
		return "Draws large clickable name/class boxes over the Spirit Leap menu so you don't have to aim at a tiny head icon.";
	}
}
