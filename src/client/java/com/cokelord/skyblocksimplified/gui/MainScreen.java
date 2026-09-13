package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.config.ConfigManager;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.CustomEnchantParsingFeature;
import com.cokelord.skyblocksimplified.feature.impl.GuiAnimationsFeature;
import com.cokelord.skyblocksimplified.feature.impl.ItemPickupLogFeature;
import com.cokelord.skyblocksimplified.feature.impl.LividFinderFeature;
import com.cokelord.skyblocksimplified.feature.impl.InstanceChestProfitFeature;
import com.cokelord.skyblocksimplified.feature.impl.CroesusFeature;
import com.cokelord.skyblocksimplified.feature.impl.DungeonClickedBlocksFeature;
import com.cokelord.skyblocksimplified.feature.impl.MoneyPerHourFeature;
import com.cokelord.skyblocksimplified.feature.impl.PestCooldownFeature;
import com.cokelord.skyblocksimplified.feature.impl.GuiColorFeature;
import com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature;
import com.cokelord.skyblocksimplified.feature.impl.ActiveHotfPerksHighlightFeature;
import com.cokelord.skyblocksimplified.feature.impl.DnaAnalyzerSolverFeature;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Main config GUI: sidebar category nav + feature toggle list, per the provided mockups, with
 * exponential-curve animations throughout (see {@link Anim}). Rectangles/squares stand in for
 * real art until textures exist, same placeholder approach as the mockups themselves.
 */
public class MainScreen extends Screen {
	// White silhouettes with the shape carried entirely in alpha — tinted via blit's color-multiply
	// param, so they track Theme.accent() the same as the placeholder shapes they replaced.
	private static final net.minecraft.resources.Identifier COG_TEXTURE =
		net.minecraft.resources.Identifier.fromNamespaceAndPath(com.cokelord.skyblocksimplified.SkyblockSimplified.MOD_ID, "textures/gui/cog.png");
	private static final net.minecraft.resources.Identifier MAGNIFIER_TEXTURE =
		net.minecraft.resources.Identifier.fromNamespaceAndPath(com.cokelord.skyblocksimplified.SkyblockSimplified.MOD_ID, "textures/gui/magnifying_glass.png");
	// Real pushpin art (user-supplied pixel-art asset), cropped tight to its opaque pixels and resized to
	// the same 64x64 convention as the other white/gray icons above, so it tints via drawIcon's multiply
	// exactly like they do (white head -> full tint color, gray shading -> a softer version of it, the black
	// outline pixels stay black since black * anything = black, which is what keeps the silhouette crisp).
	private static final net.minecraft.resources.Identifier GRADIENT_PIN_TEXTURE =
		net.minecraft.resources.Identifier.fromNamespaceAndPath(com.cokelord.skyblocksimplified.SkyblockSimplified.MOD_ID, "textures/gui/gradient_pin.png");

	private static final int PANEL_MIN_WIDTH = 460;
	private static final int PANEL_MAX_WIDTH = 880;
	private static final int PANEL_MIN_HEIGHT = 320;
	private static final int PANEL_MAX_HEIGHT = 620;
	private static final float PANEL_SIZE_FRACTION = 0.72f;

	private static final int SIDEBAR_WIDTH = 140;
	private static final int HEADER_HEIGHT = 26;
	private static final int SUB_TAB_BAR_HEIGHT = 30;
	private static final int ROW_HEIGHT = 34;
	private static final int SLOT_ROW_HEIGHT = 24;
	private static final int SLOT_SIZE = 14;
	private static final int EXPAND_PADDING = 34;
	private static final int SEARCH_FULL_WIDTH = 220;
	private static final int COLOR_SQUARE_SIZE = 90;
	private static final int COLOR_SQUARE_CELLS = 24;
	private static final int COLOR_CONTENT_HEIGHT = 150;
	private static final int ANIM_ROW_HEIGHT = 28;
	// 6 duration/blur sliders + 2 toggle rows (cog spin, slider animation) — Pixelated Look's own row is gone
	// (pixelated is unconditional now, per user request, nothing left for a toggle to control).
	private static final int ANIM_CONTENT_HEIGHT = ANIM_ROW_HEIGHT * 6 + 16 + 60;
	private static final int COLOR_SWATCH_SIZE = 14;
	private static final String EDIT_GUI_LOCATIONS_SUBCATEGORY = "Edit gui locations";

	private static final float COG_FADE_RATE_DURATION = 0.3f;
	private static final float HOVER_DURATION = 0.18f;
	private static final float SCROLL_DURATION = 0.22f; // fast, snappy stop
	// Per user request ("when hovered it should become gray/darker, remove the getting bigger animation,
	// same with the search top right and the cog, just make those a little darker instead"): shared darken
	// amount for sidebar category text, the search magnifier icon, and settings cog icons on hover — replaces
	// the old scale-up-on-hover treatment for all three.
	private static final float HOVER_DARKEN_AMOUNT = 0.35f;
	private static final float SUB_TAB_HOVER_GROWTH = 0.10f;

	// Dungeons Copilot panel layout — must stay in sync with drawDungeonsCopilotContent/drawCopilotItemBlock's
	// actual row math below (expandedContentHeight has no other way to know the content's real height ahead
	// of drawing it, same constraint every other dynamic-height panel in this file already works under).
	// 66 (class picker row + Enabled-for-class toggle + Add Step button, original layout) + 58 more for the
	// Show All Steps / Show Coordinates toggles and the Config export/import row added since — see
	// drawDungeonsCopilotContent's own row math, which this must stay in sync with (expandedContentHeight
	// can't just measure this after the fact).
	private static final int COPILOT_HEADER_HEIGHT = 124 + 18 * 3 + 18 * 2 + 18 * 2; // + Waypoint Tracer/Beacon Line toggles + Bold/Italic Title toggles
	// Was 78 — BREAKABLE_BLOCKS items gained a second row (manual X/Y/Z + "Add Block", alongside the
	// existing "Use Looked-At Block"/"Clear" row) per user request, so this single flat per-item estimate
	// (used only for the panel's total scroll-height allocation — drawCopilotItemBlock's own return value
	// still positions each item exactly, so this being an overestimate for TITLE/WAYPOINT items just means
	// mildly extra scroll room there, not any visual gap) needs to cover that new tallest case.
	private static final int COPILOT_ITEM_HEIGHT = 97;

	// Dungeon Routes panel layout — must stay in sync with drawDungeonRoutesContent/drawRouteItemBlock's own
	// row math, same constraint as Boss Guide's constants above. The room-button grid's own height depends on
	// how many rooms wrap per row, which needs the panel's actual width — computed from the `panelWidth`
	// field (set in init()) the same way drawDungeonRoutesContent computes its own rowWidth, since
	// expandedContentHeight(Feature) has no width parameter of its own to work with.
	// Real bug found (per user report — "clicking a room... cuts off in the middle of the room name... the
	// config part of the mod menu isnt showing fully"): drawDungeonRoutesContent draws the two waypoint
	// toggle rows FIRST, before branching into either the room grid or the per-room editor — but the editor
	// branch's own height estimate below used to omit that shared header entirely, so the panel's real
	// scissor/expand height came out ~40px short of what was actually drawn, clipping everything from partway
	// through the back button onward. Both branches now explicitly add this shared row height.
	// Per user request ("Allow users to change opacity for all highlights in the dungeon routes" /
	// "Add an 'Edit mode' toggle") — two more shared header rows (a slider, 20px per drawSliderRow's own
	// return delta, plus a third toggle) now sit between the original two toggles and the gap below.
	// The "Fast Path Updates" toggle row (and the A* pathfinding system behind it) was removed per later
	// user request ("Our waypoint path system was good with the line from the player. Revert that and add
	// the moving line back") — back down to 3 toggle rows.
	// waypoint tracer + beacon line + edit mode toggles + opacity slider + gap + the
	// "next step" keybind row (drawKeybindRows, added per user request) + its own trailing gap.
	private static final int ROUTE_TOGGLES_HEIGHT = 18 * 3 + 20 + 4 + SLOT_ROW_HEIGHT + 4;
	// + all-rooms export/import row + gap, + room search box + gap, + "Open current room" row + gap, + padding
	// Real bug found (per user report — "some rooms barely show the 'Add step' button because i cant scroll
	// down far enough"): this also never accounted for the "Open current room" row added between the search
	// box and the room tile grid — undershooting maxScrollOffset by that row's own height too.
	private static final int ROUTE_GRID_HEADER_HEIGHT = ROUTE_TOGGLES_HEIGHT + 18 + 4 + 16 + 4 + 16 + 4 + 8;
	private static final int ROUTE_ROOM_TILE_WIDTH = 108;
	private static final int ROUTE_ROOM_TILE_HEIGHT = 26;
	private static final int ROUTE_ROOM_TILE_GAP = 6;
	// + back row + gap, + per-room export/import row + gap, + add-step button + gap
	private static final int ROUTE_EDITOR_HEADER_HEIGHT = ROUTE_TOGGLES_HEIGHT + 16 + 4 + 18 + 4 + 16 + 6;
	// One item block's own tallest case (a Breakable Blocks step: header row + XYZ/Add Block row + Use
	// Looked-At/Clear row) — shorter than Boss Guide's own COPILOT_ITEM_HEIGHT since route steps have no
	// chat/split trigger fields (a room is scoped by physically being in it, not a chat/split signal).
	private static final int ROUTE_ITEM_HEIGHT = 16 + 3 + 16 + 3 + 16 + 4 + 4;

	private static final int PANEL_RADIUS = 8;
	private static final int BOX_RADIUS = 4;
	private static final int SMALL_RADIUS = 3;
	private static final int FULL_ROUND = 999;

	private static final int COLOR_SIDEBAR_SELECTED = 0xFFFFFFFF;
	// Per user request ("make sure the GUI color applies on ALL colors in the GUI... I pressed something
	// and it was dark red and my GUI color is not dark red"): the toggle-on track and the "listening for a
	// keybind" box used to be independently hardcoded (a fixed green, a fixed dark maroon) instead of ever
	// reading Theme.accent() — colorToggleOn() in particular never had anything to do with the accent at
	// all despite looking like it should. Both are now small helper methods below instead of constants, so
	// every draw site picks up live chroma-cycling too, not just a static snapshot. colorKnobOff() was the
	// most likely actual culprit for the report (a hardcoded 0xFFCC3333 — coincidentally identical to the
	// factory-default accent color, which is almost certainly why it read as "some other GUI color" rather
	// than an obviously-unthemed one) — switched to a plain neutral gray, which can't ever clash with
	// whatever the user's real accent is. Close/remove ('x') buttons deliberately keep literal red — same
	// reasoning Theme.java's own doc comment already gives for the window-close button: a destructive
	// action staying red regardless of theme is a real usability convention, not an oversight.
	private static final int COLOR_KNOB_ON = 0xFFFFFFFF;
	private static final int COLOR_CLOSE_BUTTON = 0xFFCC3333;

	// Real Hypixel rarity colors — same values ItemRarityBackgroundFeature's own RARITY_COLORS map and
	// ChestRollingFeature's accentColorOf already hardcode for EPIC/MYTHIC — reused here per user request
	// ("Bonzo shard text should be epic... Derpy stuff should be pink/mythic since its a special mayor") for
	// the Catacombs Experience Calculator's label coloring.
	private static final int RARITY_EPIC = 0xFFAA00AA;
	private static final int RARITY_MYTHIC = 0xFFFF55FF;

	// Real bug found (per user report — "The themes don't change everything. They should change everything
	// in the mod menu itself and keep the same hierarchy of colors... The colors for the text should also
	// change when using White mode... they blend in"): every one of these used to be a flat constant tuned
	// only for the Black theme; PanelThemeFeature only ever recolored the outer panel fill behind them, so
	// switching to White/Transparent left the header strip, module rows, sub-tab tiles, search box, sliders,
	// etc. exactly as dark as before. Routed through Theme.chrome()/Theme.text() (see that class's own doc
	// comment for how the derivation preserves each constant's original relative lightness/darkness under
	// any theme) so every one of these now actually follows the selected Panel Theme.
	private static int colorHeaderBg() { return Theme.chrome(0xF0181818); }
	private static int colorRowBg() { return Theme.chrome(0xFF1B1B1B); }

	/** Per user request ("Do those now" — "Extend gradient/solid-color option to individual feature rows and
	 *  subtoggle buttons"): when the Custom theme's own "Apply Gradient to Rows & Buttons" toggle is on and a
	 *  gradient is actually active, module rows sample the SAME live panel gradient at their own screen
	 *  position instead of the flat chrome() gray — using panelX/panelWidth as the "full" X span (matching
	 *  exactly what the real header/sidebar/content panel fills already use as their own shared full span, so
	 *  a row's tint stays visually continuous with the panel behind it) and the content list's own viewport
	 *  as the full Y span (rows only ever exist inside that area anyway). Falls back to the flat gray whenever
	 *  the toggle is off, unset, or no gradient is active — so this can't regress the existing look. */
	private int colorRowBg(int rowX, int rowTop, int rowWidth, int rowHeight) {
		com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf = panelThemeFeatureInstance();
		if (ptf != null && ptf.getMode() == com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature.Mode.CUSTOM
				&& ptf.isCustomApplyGradientToRows() && Theme.isPanelGradientActive() && panelWidth > 0
				&& listViewportY1 > listViewportY0) {
			float fracX = (float) (rowX + rowWidth / 2 - panelX) / panelWidth;
			float fracY = (float) (rowTop + rowHeight / 2 - listViewportY0) / (listViewportY1 - listViewportY0);
			return Theme.panelGradientColorAt(fracX, fracY, panelWidth, listViewportY1 - listViewportY0);
		}
		return colorRowBg();
	}
	private static int colorRowText() { return Theme.text(0xFFAAAAAA); }
	private static int colorToggleOff() { return Theme.chrome(0xFF2A2A2A); }

	/** Same idea as the position-aware {@link #colorRowBg(int, int, int, int)} overload, for every
	 *  subtoggle's own off-state track fill — see {@link #drawToggleRow}, the one central place literally
	 *  every subtoggle in the whole mod menu already goes through, which is what actually makes this cover
	 *  "subtoggle buttons" mod-wide from a single change. The ON state deliberately keeps using the user's own
	 *  accent color unchanged (that's its own separate, intentional meaning, not a background fill). */
	private int colorToggleOff(int tx, int ty, int toggleW, int toggleH) {
		com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf = panelThemeFeatureInstance();
		if (ptf != null && ptf.getMode() == com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature.Mode.CUSTOM
				&& ptf.isCustomApplyGradientToRows() && Theme.isPanelGradientActive() && panelWidth > 0
				&& listViewportY1 > listViewportY0) {
			float fracX = (float) (tx + toggleW / 2 - panelX) / panelWidth;
			float fracY = (float) (ty + toggleH / 2 - listViewportY0) / (listViewportY1 - listViewportY0);
			return Theme.panelGradientColorAt(fracX, fracY, panelWidth, listViewportY1 - listViewportY0);
		}
		return colorToggleOff();
	}
	private static int colorKnobOff() { return Theme.chrome(0xFF555555); }
	private static int colorSearchBg() { return Theme.chrome(0xFF3A3A3A); }
	private static int colorExpandBg() { return Theme.chrome(0xFF141414); }
	// Per user report ("subcategory buttons... dont really stand out and its hard to see where the button
	// ends"): darkened both fill colors (were barely lighter than the near-black panel behind them).
	private static int colorSubTabBg() { return Theme.chrome(0xFF0C0C0C); }
	private static int colorSubTabSelectedBg() { return Theme.chrome(0xFF1E1E1E); }
	private static int colorKeybindBox() { return Theme.chrome(0xFF2A2A2A); }
	private static int colorSliderTrack() { return Theme.chrome(0xFF222222); }

	private static int colorToggleOn() { return Theme.accent(); }
	private static int colorKeybindListening() { return RenderUtil.lerpColor(0xFF000000, Theme.accent(), 0.35f); }

	private enum ExpandPhase { CLOSED, FADING_COG_OUT, EXPANDING, EXPANDED, COLLAPSING, FADING_COG_IN }
	// Per user request ("I want them all to fall up above the mod menu itself... then the modules
	private enum TextFocus { NONE, SEARCH, HEX, ENCHANT_NAME, ENCHANT_LEVEL,
		POSMSG_TEXT, POSMSG_X, POSMSG_Y, POSMSG_Z, POSMSG_RANGE, CONFIG_IMPORT, VISUALWORD_FIND, VISUALWORD_REPLACE,
		DUNGEON_NOTIF_TITLE, DUNGEON_NOTIF_PARTY_MSG, COPILOT_FIELD, ALIAS_FIELD, SHORTCUT_COMMAND, COPILOT_IMPORT, KISMET_THRESHOLD, ROUTE_FIELD,
		ROUTE_ALL_IMPORT, ROUTE_ROOM_IMPORT, ROUTE_ROOM_SEARCH, GENERIC_FIELD, POSMSG_CONFIG_IMPORT }
	private int editingRuleIndex = -1;
	private int editingPosMsgIndex = -1;
	// Shared cursor/selection state for whichever of SEARCH/TITLE/FOOTER/LINE_TEXT is currently focused —
	// only one field can ever be focused at a time in this screen, so one shared cursor is enough
	// (reset whenever focus moves to a different field). HEX keeps its own dedicated flow (short,
	// character-filtered, auto-committing) rather than being folded into this.
	private int textCursor = 0;
	private int textSelectionAnchor = -1;

	private final Anim openAnim = new Anim(0f, 0.5f);
	// Per user request ("Make the top part (the one with the search part) rise from the bottom, and then
	// the categories list should extend down from it, and then the module list expands from the categories
	// list. This should all base happen within 0.5 seconds, and should be able to be sped up and slowed
	// down in the mod animation settings"): a 3-phase sequential reveal that is now the ONLY visible opening
	// motion — openAnim above no longer animates on open (see init()'s own doc comment on why the two used
	// to fight each other), it just snaps currentScale to 1 immediately, leaving these three scissor
	// clips/translate-offset (see layoutAndDraw) as the entire "opening" effect. Chained one after another in
	// updateMenuOpenReveal — each only starts once the previous is essentially settled — and all three share
	// openDuration/3 as their individual duration so the whole sequence tracks the existing "Opening" slider
	// in GUI Animations without needing a separate control. Deliberately NOT reversed on close: the close
	// animation still has its own distinct ease-in-expo shrink (see closeStartScale handling in
	// extractRenderState, untouched by any of this), so these simply stay fully revealed once open and only
	// ever replay on the next fresh MainScreen (these are per-instance fields, reset to 0 whenever the menu
	// is reopened).
	private final Anim menuOpenHeaderAnim = new Anim(0f, 0.5f);
	private final Anim menuOpenSidebarAnim = new Anim(0f, 0.5f);
	private final Anim menuOpenContentAnim = new Anim(0f, 0.5f);
	private final Anim searchAnim = new Anim(0f, 0.5f);
	private final Anim cogVisibility = new Anim(1f, COG_FADE_RATE_DURATION);
	private final Anim expandProgress = new Anim(0f, 0.4f);
	private final Anim scrollAnim = new Anim(0f, SCROLL_DURATION);
	private final Anim sidebarScrollAnim = new Anim(0f, SCROLL_DURATION);
	private float sidebarScrollTarget = 0f;
	private float maxSidebarScrollOffset = 0f;
	private final Map<FeatureCategory, Anim> hoverAnims = new HashMap<>();
	private final Map<String, Anim> cogHoverAnims = new HashMap<>();
	private final Map<String, Anim> cogSpinAnims = new HashMap<>();
	private final Anim magnifierHoverAnim = new Anim(0f, 0.15f);

	// Per user request ("Changing categories should also have an animation... the current modules should
	// just fade out and the new ones should just fade in. Make sure it doesn't get stuck if a user spams
	// between subcategories"): a lightweight crossfade for category-sidebar and flat-subcategory-tab clicks.
	// Self-correcting shape, same as every other Anim-driven transition in this file: triggerContentSwap
	// always retargets to 0 and re-stashes whatever change should apply, so repeated clicks mid-fade can
	// never get stuck on a half-applied swap — the fade just keeps chasing whatever was clicked last.
	private enum ContentSwapPhase { NONE, FADING_OUT, FADING_IN }
	private ContentSwapPhase contentSwapPhase = ContentSwapPhase.NONE;
	private final Anim contentSwapFadeAnim = new Anim(1f, 0.3f);
	private Runnable pendingContentSwap = null;

	private boolean closing = false;
	private long lastFrameNanos = 0L;
	private boolean interactive = false;

	private float currentScale = 0f;
	private float previousScale = 0f;
	private float closeStartScale = 0f;
	private long closeStartNanos = 0L;
	private float closeDurationSeconds = 0.3f;

	private static final List<String> SLAYER_TYPES = List.of("General", "Tarantula", "Voidgloom", "Blaze", "Vampire", "Revenant", "Sven");

	private FeatureCategory selectedCategory = FeatureCategory.ABOUT;
	private String selectedSubcategory = null;
	private String selectedSlayerType = null;
	private final List<SubTabRow> slayerTypeRows = new ArrayList<>();
	private boolean searchOpen = false;
	private String searchQuery = "";
	private TextFocus textFocus = TextFocus.NONE;
	// Generalized hex-picker system: any number of hex fields can exist on screen at once (title/footer
	// color, background/outline colors, GuiColorFeature's own swatch), each identified by a stable string
	// key rather than a dedicated field per use site. Only one can hold keyboard focus at a time.
	private String focusedHexKey = null;
	private String hexFieldBuffer = "";
	// LinkedHashMap (not HashMap) specifically so iteration order matches insertion/render order — needed
	// for the same popup-vs-underlying-content hit priority fix as clickHits/colorSquareHits/sliderHits.
	private final Map<String, int[]> hexFieldBounds = new LinkedHashMap<>();
	private final Map<String, Integer> hexFieldColors = new HashMap<>();
	private final Map<String, Consumer<Integer>> hexFieldSetters = new HashMap<>();
	// General-purpose id-keyed single-line text field, same shape as hexFieldBounds/hexFieldSetters above but
	// for a plain String setting rather than a hex color — avoids needing a new TextFocus enum constant plus
	// six hand-wired call sites (getFocusedText/setFocusedText/isSharedTextFocus/draw/click/panel-height) for
	// every individual text setting a feature ever wants; one GENERIC_FIELD case handles all of them, keyed
	// by whatever id string the caller passes to drawGenericTextField.
	private final Map<String, int[]> genericFieldBounds = new LinkedHashMap<>();
	private final Map<String, java.util.function.Supplier<String>> genericFieldGetters = new HashMap<>();
	private final Map<String, java.util.function.Consumer<String>> genericFieldSetters = new HashMap<>();
	private String genericFocusedFieldId = null;
	private boolean searchBoxVisible;
	private int searchBoxX, searchBoxY, searchBoxWidth, searchBoxHeight;

	// Tracked directly from mouse position while dragging the color square, instead of being re-derived
	// from the stored RGB every frame — hsvToRgb -> rgbToHsv round-trips are not perfectly stable right
	// at the hue 0/360 wrap boundary, which made the marker visibly snap between the two square edges
	// during a smooth drag near red. Only re-synced from the actual color when NOT dragging the square.
	private float pickerHue = 0f;
	private float pickerSat = 1f;

	private ExpandPhase expandPhase = ExpandPhase.CLOSED;
	private Feature expandTarget;
	private Feature pendingExpand;
	private KeyMapping listeningFor;
	private boolean listeningEscapeUnbinds;
	// Plain-GLFW-keycode capture for settings that aren't a real vanilla KeyMapping (e.g. Slot Binds'
	// bind-set key) — same "next key press wins" idea as listeningFor, just a raw int callback instead of
	// a KeyMapping object since there's no registered keybind to rebind.
	private java.util.function.IntConsumer listeningForRawKey;

	private float scrollTarget = 0f;
	private float maxScrollOffset = 0f;

	private int panelWidth;
	private int panelHeight;
	private int panelX;
	private int panelY;
	private int magnifierX;
	private int magnifierY;
	private int magnifierSize;
	private final List<CategoryRow> categoryRows = new ArrayList<>();
	private final List<SubTabRow> subTabRows = new ArrayList<>();
	private final List<FeatureRow> featureRows = new ArrayList<>();
	private final List<InlineKeybindRow> inlineKeybindRows = new ArrayList<>();
	private final List<ComboRow> comboRows = new ArrayList<>();
	private final List<SlotRow> slotRows = new ArrayList<>();
	private KeyCombo listeningForCombo = null;
	private boolean comboEscapeUnbinds = true;
	private final Set<InputConstants.Key> capturingComboKeys = new LinkedHashSet<>();
	private final List<SliderHit> sliderHits = new ArrayList<>();
	private final List<ColorSquareHit> colorSquareHits = new ArrayList<>();
	private String draggingColorKey = null;

	// Per user request ("Do those now" — redesign the gradient editor as a square widget with an
	// angular/clock-hand direction control): pos1/pos2 now go through the generic, already-tested
	// sliderHits/drawSliderRow mechanism and pin selection through the generic clickHits/ClickHit mechanism
	// (see drawGradientPinEditor), so the only bespoke widget state left is the angle dial itself — a single,
	// non-generic control (only PanelThemeFeature ever uses it) whose geometry is recorded fresh every frame
	// it's actually drawn (gradientDialActiveThisFrame reset false at the top of every frame, alongside every
	// other per-frame hit list, then set true only inside drawGradientPinEditor) so a click can never match
	// stale bounds from a frame where the Custom theme's settings panel, or its Linear gradient mode, wasn't
	// even showing.
	private boolean draggingGradientAngle = false;
	private int selectedGradientPin = 1;
	private int gradientDialCx, gradientDialCy, gradientDialR;
	private boolean gradientDialActiveThisFrame = false;

	// Per user correction ("the pins should be next to the gradient preview square... they should always
	// [be] opposite each other, but the angle of them depends on the clock angle selected... they should not
	// be able to move but they should be able to go in on the square itself"): each pin's own screen position
	// (gradientPin1X/Y, gradientPin2X/Y) is recomputed fresh every frame in drawGradientPinEditor from the
	// live pos1/pos2/angle, then reused here for hit-testing a click/drag against — same per-frame-freshness
	// pattern as the angle dial's own fields above, so a click can never match a stale position from a frame
	// where the Custom theme panel wasn't even open. draggingGradientPinWhich is 0 (none), 1, or 2.
	private int gradientPinCx, gradientPinCy;
	private float gradientPinAxisX, gradientPinAxisY;
	private int gradientPin1X, gradientPin1Y, gradientPin2X, gradientPin2Y;
	private int draggingGradientPinWhich = 0;
	private boolean gradientPinActiveThisFrame = false;
	// Per user report ("grabbing on a pin sends it to the cursor. It should not do that. It should not follow
	// the cursor just match its movement"): applyGradientPinDrag used to snap the pin's position directly onto
	// the cursor's own projected location every call, including the very first one at grab time — picking up a
	// pin anywhere on its (now-larger) hit rect visibly teleported it to wherever the cursor happened to be
	// instead of staying put until the cursor actually moved. Grabbing now records the pin's CURRENT pos and
	// the cursor's own axis-projection at that instant; every subsequent drag call moves the pin by exactly
	// how far the cursor has moved SINCE the grab, not to an absolute cursor-derived position.
	private float gradientPinDragStartPos;
	private double gradientPinDragStartProj;

	// Boss Guide / Dungeon Routes step-number box drag-reorder state — shared between CopilotItem and
	// RouteItem since both boxes behave identically (see the two drawXItemBlock methods' own doc comments).
	// A press on the box always starts here uncommitted; mouseDragged promotes it to a real drag only once
	// the pointer actually moves past a small threshold, so a plain click (the common case) still reads as
	// "advance one step" rather than an accidental zero-distance reorder.
	private Object stepDragItem = null;
	private double stepDragStartY;
	private double stepDragCurrentY;
	private boolean stepDragCommitted = false;
	private SliderHit activeSlider;

	// "Hover a confusing setting for ~2s, get a small tooltip explaining it" — per user request (Blood
	// Camp's timing offset settings specifically: "make it so hovering a subtoggle for like 2 seconds shows
	// a small tooltip that tells the user what the subtoggle does"). Keyed by a stable per-row string (not
	// geometry) so a re-layout mid-hover (scrolling, a sibling row appearing/disappearing) doesn't reset the
	// timer just because the row moved a pixel. Only one row's hover clock runs at a time; whichever row's
	// checkHoverTooltip call last found the mouse inside its bounds this frame wins and gets rendered, after
	// everything else, so it's never drawn under another row.
	private static final long HOVER_TOOLTIP_DELAY_MS = 2000;
	private String hoverTooltipKey;
	private long hoverTooltipSinceMs;
	private String pendingTooltipText;
	private int pendingTooltipX, pendingTooltipY;
	// Latest frame's real mouse position, cached at the top of layoutAndDraw — see its own doc comment there.
	private int lastMouseX, lastMouseY;

	/** Call right after drawing a row, with that row's own hit bounds and explanatory text — arms a 2-second
	 *  hover timer keyed on {@code key}, and once it elapses, queues a tooltip to be drawn (by {@link
	 *  #drawPendingTooltip}) at the current mouse position on top of everything else in the panel. */
	private void checkHoverTooltip(int mouseX, int mouseY, int x, int y, int w, int h, String key, String text) {
		boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		if (!inside) {
			if (key.equals(hoverTooltipKey)) hoverTooltipKey = null;
			return;
		}
		long now = System.currentTimeMillis();
		if (!key.equals(hoverTooltipKey)) {
			hoverTooltipKey = key;
			hoverTooltipSinceMs = now;
		}
		if (now - hoverTooltipSinceMs >= HOVER_TOOLTIP_DELAY_MS) {
			pendingTooltipText = text;
			pendingTooltipX = mouseX;
			pendingTooltipY = mouseY;
		}
	}

	private static final int TOOLTIP_MAX_WIDTH = 200;

	/** Draws (and clears) whatever {@link #checkHoverTooltip} queued this frame — called once, after every
	 *  row in the panel has had a chance to arm/draw it, so it always renders on top. */
	private void drawPendingTooltip(GuiGraphicsExtractor graphics) {
		if (pendingTooltipText == null) return;
		List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(pendingTooltipText), TOOLTIP_MAX_WIDTH);
		int textWidth = 0;
		for (var line : lines) textWidth = Math.max(textWidth, this.font.width(line));
		int boxWidth = textWidth + 10;
		int boxHeight = lines.size() * this.font.lineHeight + 8;
		int bx = Math.min(pendingTooltipX + 12, this.width - boxWidth - 4);
		int by = Math.min(pendingTooltipY + 12, this.height - boxHeight - 4);
		fillRounded(graphics, bx, by, bx + boxWidth, by + boxHeight, BOX_RADIUS, 0xEE1A1A1A);
		int ly = by + 4;
		for (var line : lines) {
			graphics.text(this.font, line, bx + 5, ly, Theme.text(0xFFDDDDDD));
			ly += this.font.lineHeight;
		}
		pendingTooltipText = null;
	}

	// Clicking a rule's Color/Chroma button opens a small popup instead of instantly toggling/applying —
	// lets the user actually see and adjust sliders before committing. Only one popup target set exists
	// now (enchant rules) since Custom Scoreboard was removed (SkyHanni ships a dedicated scoreboard mod).
	private CustomEnchantParsingFeature.Rule enchantColorPopupTarget;
	private CustomEnchantParsingFeature.Rule enchantChromaPopupTarget;
	// Per user request (task tracker #589 — "per-tier color/bold/italic overrides"): same popup-button
	// shape as enchantColorPopupTarget above, just targeting a TierOverride instead of a per-name Rule.
	private CustomEnchantParsingFeature.TierOverride enchantTierColorPopupTarget;
	// Positional Messages' three dropdown buttons (Classes/Section — multi-select checklists — and Preset
	// list — single-select, applies and closes) share this one popup slot: only one can be open at a time,
	// same as the enchant color/chroma popups above, and reuses the exact same anchor/bounds/hit-priority
	// machinery (popupAnchorX/Y, popupX0..Y1, popupHitsStart*) rather than a parallel copy of it.
	private Integer posMsgDropdownIndex;
	private String posMsgDropdownKind;
	private int popupAnchorX, popupAnchorY;
	private int popupX0, popupY0, popupX1, popupY1;
	// Real bug found (per user report — "I cant scroll in the positional messages presets menu"): the
	// Presets list (8 entries) renders taller than the mod-menu panel's own viewport, and clampPopupY
	// (see its own doc comment) only ever slides an oversized popup back inside the viewport, it never
	// shrinks it — so the popup's bottom rows spilled past the viewport's bottom edge and got clipped by
	// the panel's own outer scissor, with no way to reach them. This offset (rows, not pixels-per-tick;
	// scrolled straight in mouseScrolled below) lets the popup cap its own drawn height to the viewport
	// and scroll its row list inside that fixed box instead.
	private float posMsgDropdownScroll = 0f;
	private float maxPosMsgDropdownScroll = 0f;
	// Real bug found: the old cap only shrank the popup once its full content exceeded the PANEL's own
	// viewport (listViewportY1 - listViewportY0), which on a normal-sized window is comfortably taller
	// than the 8-entry Presets list ever gets — so maxPosMsgDropdownScroll came out to 0 (nothing to
	// scroll) on a typical desktop window, even though the user reported being unable to scroll at all,
	// which only makes sense if their actual on-screen popup WAS taller than what fit (e.g. a smaller
	// window / higher GUI Scale, where the viewport math still claimed plenty of room). Capping the
	// popup to a small, fixed row count regardless of the panel's own viewport makes the scroll path
	// deterministic on every screen size instead of conditional on how much space happens to be free.
	private static final int POSMSG_POPUP_MAX_VISIBLE_ROWS = 5;
	// List sizes captured right before drawLineStylePopups()/drawEnchantStylePopups() draws the popup's own
	// content — everything added to clickHits/colorSquareHits/sliderHits from that index onward this frame
	// is unambiguously the popup's own controls, drawn last and therefore on top. mouseClicked()'s popup
	// branch uses this instead of (or alongside) geometric bounds checking: a bounds check alone can't tell
	// "belongs to the popup" from "an underlying row's control just happens to sit at the same screen
	// position the popup was drawn over" — which is exactly what let a click on the popup's own Chroma
	// toggle (or its blank background, matching some GEOMETRICALLY-overlapping underlying Bold/Tilted/color
	// button) fire that EARLIER-added underlying hit instead, since forward iteration matches whichever hit
	// was registered first regardless of which one is actually drawn on top per user report.
	private int popupHitsStartClick, popupHitsStartColorSquare, popupHitsStartSlider, popupHitsStartHex;
	// The scrollable feature list's own visible viewport (set fresh in layoutAndDraw every frame, right
	// before its enableScissor call) — see mouseClicked()'s general viewport filter below for why this
	// needs to be a field, not just the method-local listY/listHeight it mirrors.
	private int listViewportY0, listViewportY1;
	// Per user request ("Add a draggable scrollbar on the right side of any scrollable panel, sized
	// proportionally to content length, so users can scroll faster/jump directly"): a small reusable
	// scrollbar drawn+hit-tested by drawScrollbar/handleScrollbarClick/handleScrollbarDrag below, applied to
	// the two real scrollable regions this screen has — the sidebar category list and the main content/
	// settings list. Drag state is a single field (not per-scrollbar) since only one can ever be dragged at
	// once; CONTENT vs SIDEBAR is enough to know which scrollTarget field a drag should write back to.
	private enum ScrollbarKind { CONTENT, SIDEBAR }
	private ScrollbarKind draggingScrollbar = null;
	// Captured once when a drag starts (the track's own geometry that frame) so mid-drag math stays stable
	// even if the underlying content height changes while dragging (e.g. a panel finishes expanding).
	private int scrollbarDragTrackY0, scrollbarDragTrackHeight, scrollbarDragHandleHeight;
	private record ScrollbarHit(ScrollbarKind kind, int x0, int y0, int x1, int y1, int handleY0, int handleY1, float maxOffset) {}
	private final List<ScrollbarHit> scrollbarHits = new ArrayList<>();
	// Per user request ("When the 'Add step' button isn't visible on screen, place a + at the bottom left
	// corner of the mod menu"): set by drawDungeonRoutesContent (only reached while a route room's editor
	// is actually open) to the real "+ Add Step" action for whatever room is currently being edited,
	// whenever that real button's own on-screen position is scrolled outside listViewportY0/Y1 this frame.
	// Reset to null at the top of every frame (see layoutAndDraw, right before listViewportY0/Y1 themselves
	// get set) so it can never linger once the panel closes or scrolls the real button back into view.
	private Runnable routeAddStepAction = null;
	// Per user request ("Also add the little plus button like we did in dungeon routes"): same mechanism as
	// routeAddStepAction above, for the Dungeons Copilot step editor's own "+ Add Step" button.
	private Runnable copilotAddStepAction = null;
	// X counterpart of the above, same "set fresh every frame" reasoning — added per user report ("the
	// presets thing cuts off since it lines up with the mod menu itself"): a style/dropdown popup only ever
	// popped the ONE scissor level drawExpandedSettings pushes for the expanded panel's own content
	// (x,y,width,height), not the WIDER list-viewport scissor pushed one level further out around the whole
	// scrollable feature list (contentX..contentX+contentWidth) — so a popup positioned anywhere near or
	// past that outer edge (fine for the narrower enchant color/chroma popups, but not the new, wider
	// Positional Messages dropdowns opened from a button near the row's right edge) still got clipped by
	// that still-active outer scissor. Used by drawPositionalMessageDropdownPopup (and could be reused by
	// any future popup) to both clamp its own position to the actually-visible panel area and to properly
	// pop/restore BOTH scissor levels around its own draw call, not just the inner one.
	private int listViewportX0, listViewportX1;

	// Custom Enchant Parsing (Inventory > Enchantments): each rule row's name/level text-field bounds,
	// stored as [x, y, width, height, ruleIndex] — no drag-to-reorder needed (rules match independently
	// of order), so unlike lineRows there's nothing beyond click-to-focus/click-to-remove to track.
	private final List<int[]> enchantNameBoxes = new ArrayList<>();
	private final List<int[]> enchantLevelBoxes = new ArrayList<>();
	private int addRuleButtonX, addRuleButtonY, addRuleButtonSize;

	// Visual Words (Inventory > Misc): same two-list-of-boxes shape as enchantNameBoxes/enchantLevelBoxes
	// above, one entry per (find, replace) row.
	private int editingVisualWordIndex = -1;
	private final List<int[]> visualWordFindBoxes = new ArrayList<>();
	private final List<int[]> visualWordReplaceBoxes = new ArrayList<>();
	private int addVisualWordButtonX, addVisualWordButtonY, addVisualWordButtonSize;

	// Dungeon Notifications: each type's title-text/party-message boxes, stored as
	// [x, y, width, height, notificationTypeOrdinal] — same shape as enchantNameBoxes/visualWordFindBoxes
	// above. The color swatch button instead opens a popup (dungeonNotifColorPopupTarget), same mechanism
	// as enchantColorPopupTarget.
	private com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType editingNotifType;
	private com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType dungeonNotifColorPopupTarget;
	private final List<int[]> dungeonNotifTitleBoxes = new ArrayList<>();
	private final List<int[]> dungeonNotifPartyMsgBoxes = new ArrayList<>();

	// Dungeons Copilot: which class's step list is currently shown/edited, one shared text-field focus
	// (editingCopilotItem + editingCopilotField, a small string key rather than one TextFocus enum entry
	// per field — chat/split/offset/title/x/y/z would otherwise need 7 near-identical enum values) and its
	// own color popup target, same shape as dungeonNotifColorPopupTarget above.
	private com.cokelord.skyblocksimplified.dungeon.DungeonClass selectedCopilotClass = com.cokelord.skyblocksimplified.dungeon.DungeonClass.MAGE;
	private com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem editingCopilotItem;
	private String editingCopilotField;
	private com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem copilotColorPopupTarget;
	private record CopilotBoxHit(int x, int y, int w, int h, com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item, String field) {}
	private final List<CopilotBoxHit> copilotFieldBoxes = new ArrayList<>();
	// Right-click support for "send this step backward" — the generic ClickHit system is left-click only
	// (mouseClicked swallows every other button before ever reaching clickHits), so this needs its own
	// small hit list checked directly against event.button() == RIGHT.
	private record CopilotStepBoxHit(int x, int y, int w, int h, com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item) {}
	private final List<CopilotStepBoxHit> copilotStepBoxHits = new ArrayList<>();

	// Command Aliases / Command Shortcuts — same shared-focus text-field pattern as Dungeons Copilot above.
	private com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature.Alias editingAlias;
	private String editingAliasField;
	private record AliasBoxHit(int x, int y, int w, int h, com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature.Alias alias, String field) {}
	private final List<AliasBoxHit> aliasFieldBoxes = new ArrayList<>();
	private com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature.Shortcut editingShortcut;
	private record ShortcutBoxHit(int x, int y, int w, int h, com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature.Shortcut shortcut) {}
	private final List<ShortcutBoxHit> shortcutCommandBoxes = new ArrayList<>();
	// Kismet Feathers' single threshold text box — only ever one at a time (not keyed to a list item like
	// the alias/shortcut/copilot fields above), so a single bounds record is enough.
	private record KismetThresholdBoxHit(int x, int y, int w, int h) {}
	private KismetThresholdBoxHit kismetThresholdBox;

	// Positional Messages (Combat > Dungeons): each message row's 5 editable fields, stored as
	// [x, y, width, height, messageIndex, textFocusOrdinal] — one unified list instead of 5 separate ones
	// since there are 5 field types per row (Text/X/Y/Z/Range), same idea as enchantNameBoxes/
	// enchantLevelBoxes just consolidated given the larger field count per row.
	private final List<int[]> posMsgFieldBoxes = new ArrayList<>();
	private int addPosMsgButtonX, addPosMsgButtonY, addPosMsgButtonWidth, addPosMsgButtonHeight;

	// Config export/import (About > Mod settings): rendered inline in the module's own always-visible
	// list row (no expand/cog — per user request, just a simple single-line bar: label, paste box,
	// export button) rather than as an expandable settings panel. A single plain-text scratch buffer for
	// a pasted config string (not persisted — see setFocusedText's CONFIG_IMPORT case), and the row's own
	// hit-boxes recomputed every frame it's actually drawn — configRowVisible guards mouseClicked/
	// keyPressed against a stale previous-frame box when the row has scrolled out of view or is filtered
	// out, since (unlike an expand panel) there's no separate "is this the open one" state to check instead.
	private String configImportBuffer = "";
	private boolean configRowVisible = false;
	private int configImportBoxX, configImportBoxY, configImportBoxWidth, configImportBoxHeight;
	private int configExportButtonX, configExportButtonY, configExportButtonWidth, configExportButtonHeight;

	// Dungeons Copilot's own standalone export/import row (per user request — "like the mod itself... under
	// the 'Enabled for class' subtoggle"), same shape as the config box above but scoped to just this one
	// module's plan and only drawn while its settings panel is actually expanded, so no configRowVisible-style
	// "always visible" flag is needed — expandTarget itself already gates it.
	private String copilotImportBuffer = "";
	private int copilotImportBoxX, copilotImportBoxY, copilotImportBoxWidth, copilotImportBoxHeight;
	private int copilotExportButtonX, copilotExportButtonY, copilotExportButtonWidth, copilotExportButtonHeight;

	// Positional Messages' own standalone export/import row (per user request: "Positional messages need an
	// export feature") — same shape as Dungeons Copilot's own config row above.
	private String posMsgConfigImportBuffer = "";
	private int posMsgConfigImportBoxX, posMsgConfigImportBoxY, posMsgConfigImportBoxWidth, posMsgConfigImportBoxHeight;
	private int posMsgConfigExportButtonX, posMsgConfigExportButtonY, posMsgConfigExportButtonWidth, posMsgConfigExportButtonHeight;

	// Dungeon Routes — same shared-focus text-field pattern as Dungeons Copilot above, keyed by room name
	// instead of DungeonClass. null selectedRouteRoomName = showing the room-button grid; non-null = editing
	// that room's own step list.
	private String selectedRouteRoomName = null;
	// Per user request ("Allow users to search for a room") — filters the room grid's tiles by substring
	// match, same shared text-field mechanics (textCursor/textSelectionAnchor/drawTextField) every other
	// simple standalone field in this screen already uses. Not persisted — resets to empty on every GUI
	// reopen, same as the other import/export buffer fields nearby.
	private String routeRoomSearchQuery = "";
	private int routeRoomSearchBoxX, routeRoomSearchBoxY, routeRoomSearchBoxWidth, routeRoomSearchBoxHeight;
	private com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem editingRouteItem;
	private String editingRouteField;
	private com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem routeColorPopupTarget;
	// Per user request ("Replace the Blocks/Etherwarp 'Clear' button with a 'Selected' button that opens a
	// dropdown listing each selected block/etherwarp with a red X to remove individually") — same shared
	// popup machinery (isStylePopupOpen/closeStylePopups/popupAnchorX,Y/popupX0..Y1/popupHitsStartClick) as
	// every other popup on this screen, drawn in drawEnchantStylePopups.
	private com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem routeSelectedPopupTarget;
	private record RouteBoxHit(int x, int y, int w, int h, com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item, String field) {}
	private final List<RouteBoxHit> routeFieldBoxes = new ArrayList<>();
	private record RouteStepBoxHit(int x, int y, int w, int h, com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item) {}
	private final List<RouteStepBoxHit> routeStepBoxHits = new ArrayList<>();
	// Per user request ("Add imports/exports to each room and an overall import/export for all dungeon
	// rooms") — same standalone export/import row shape as Boss Guide's own, one for the whole feature
	// (shown on the room grid) and one scoped to whichever room's editor is currently open.
	private String routeAllImportBuffer = "";
	private int routeAllImportBoxX, routeAllImportBoxY, routeAllImportBoxWidth, routeAllImportBoxHeight;
	private int routeAllExportButtonX, routeAllExportButtonY, routeAllExportButtonWidth, routeAllExportButtonHeight;
	private String routeRoomImportBuffer = "";
	private int routeRoomImportBoxX, routeRoomImportBoxY, routeRoomImportBoxWidth, routeRoomImportBoxHeight;
	private int routeRoomExportButtonX, routeRoomExportButtonY, routeRoomExportButtonWidth, routeRoomExportButtonHeight;

	// MobHighlightFeature's custom-image-fill buttons — shared fields, same reasoning as the config
	// export/import ones above (only one panel can ever be expanded at a time).
	private int mhfImageButtonX, mhfImageButtonY, mhfImageButtonWidth, mhfImageButtonHeight;
	private int mhfClearImageButtonX, mhfClearImageButtonY, mhfClearImageButtonWidth, mhfClearImageButtonHeight;

	private final List<ClickHit> clickHits = new ArrayList<>();
	private final List<ClickHit> tabButtonHits = new ArrayList<>();

	public MainScreen() {
		super(Minecraft.getInstance(), com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature.currentMenuFont(Minecraft.getInstance()), Component.literal("SkyblockSimplified"));
		// Opening the GUI should always land on About > GUI, not a category with no subcategory selected
		// (the field default left selectedSubcategory null, which meant the very first screen open before
		// any category click showed an unfiltered/wrong list instead of the intended GUI tab) — UNLESS
		// Remember Last Mod Page is on and something was actually recorded from a previous close.
		com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature remember =
			com.cokelord.skyblocksimplified.feature.FeatureRegistry.get("remember_last_mod_page")
				instanceof com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature f ? f : null;
		boolean rememberEnabled = remember != null && remember.isEnabled();
		// Real bug found: selectCategory() below calls closeSearchIfOpen(), which unconditionally wipes
		// searchQuery back to "" whenever it's non-empty — restoring the saved query BEFORE that call meant
		// it got immediately clobbered right back to empty. Restored AFTER selectCategory() now instead.
		FeatureCategory startCategory = com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature
			.lastCategoryOrNull(rememberEnabled);
		selectCategory(startCategory != null ? startCategory : FeatureCategory.ABOUT);
		searchQuery = com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.lastSearchQueryOrEmpty(rememberEnabled);
		com.cokelord.skyblocksimplified.dungeon.DungeonClass lastCopilotClass =
			com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.lastCopilotClassOrNull(rememberEnabled);
		if (lastCopilotClass != null) selectedCopilotClass = lastCopilotClass;
		String lastRouteRoom = com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.lastRouteRoomOrNull(rememberEnabled);
		if (lastRouteRoom != null) selectedRouteRoomName = lastRouteRoom;
		if (startCategory != null) {
			String lastSub = com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.lastSubcategoryOrNull(rememberEnabled);
			if (lastSub != null && startCategory.getSubcategories().contains(lastSub)) {
				selectedSubcategory = lastSub;
			}
			// Restore whichever module's settings panel was open and how far the list was scrolled, so the
			// screen looks exactly like it did on close instead of just landing on the right tab. Snapped
			// instantly (not animated) — this is a restore, not something the player just clicked.
			String expandedId = com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.lastExpandedFeatureIdOrNull(rememberEnabled);
			if (expandedId != null) {
				Feature savedTarget = com.cokelord.skyblocksimplified.feature.FeatureRegistry.get(expandedId);
				if (savedTarget != null) {
					expandTarget = savedTarget;
					expandPhase = ExpandPhase.EXPANDED;
					expandProgress.snapTo(1f);
					cogVisibility.snapTo(0f);
				}
			}
			float savedScroll = com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.lastScrollOffsetOrZero(rememberEnabled);
			scrollTarget = savedScroll;
			scrollAnim.snapTo(savedScroll);
		}
	}

	@Override
	protected void init() {
		panelWidth = clamp(Math.round(this.width * PANEL_SIZE_FRACTION), PANEL_MIN_WIDTH, Math.min(PANEL_MAX_WIDTH, this.width - 20));
		panelHeight = clamp(Math.round(this.height * PANEL_SIZE_FRACTION), PANEL_MIN_HEIGHT, Math.min(PANEL_MAX_HEIGHT, this.height - 20));
		panelX = (this.width - panelWidth) / 2;
		panelY = (this.height - panelHeight) / 2;
		lastFrameNanos = System.nanoTime();
		// Per user report ("its still getting bigger from the center when the part should be extending from
		// the bottom of the screen... the categories panel doesnt even drop down, it just kind of spawns in"
		// / "lags a lot"): the whole-panel scale-from-center transform (currentScale, applied around the
		// panel's own center in extractRenderState) used to run concurrently with the new 3-phase reveal
		// below, and since that transform wraps EVERYTHING drawn inside layoutAndDraw — including this
		// reveal's own scissor clips and translate offset, computed in un-scaled panel coordinates — the
		// reveal was being warped by a simultaneously, rapidly-changing zoom instead of reading as its own
		// independent motion, which is exactly what looked like stutter/lag and swallowed the slide/extend
		// motion into "just spawns in". Opening no longer animates currentScale at all — it snaps straight to
		// 1 so the panel is at full size/position from frame one, and the reveal below is the ONLY visible
		// opening motion. Closing is untouched (see requestClose/closeStartScale — a completely separate
		// ease-in-expo shrink, never fed by openAnim).
		openAnim.snapTo(1f);
		menuOpenHeaderAnim.setTarget(1f);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(Math.min(value, Math.max(min, max)), Math.min(min, max));
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private static boolean isCaretBlinkOn() {
		return (System.currentTimeMillis() / 500L) % 2 == 0;
	}

	public void requestClose() {
		if (closing || !interactive) return;
		closing = true;
		closeStartScale = currentScale;
		closeStartNanos = System.nanoTime();
	}

	@Override
	public void onClose() {
		if (com.cokelord.skyblocksimplified.feature.FeatureRegistry.get("remember_last_mod_page")
				instanceof com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature remember && remember.isEnabled()) {
			com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.recordPage(selectedCategory, selectedSubcategory);
			// Only remember a panel as "open" if it's actually fully expanded — one mid-collapse when the
			// screen happened to close shouldn't reopen next time.
			String expandedId = expandPhase == ExpandPhase.EXPANDED && expandTarget != null ? expandTarget.getId() : null;
			com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.recordExpandedAndScroll(expandedId, scrollTarget);
			com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.recordSearchQuery(searchQuery);
			com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.recordCopilotClass(selectedCopilotClass);
			com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature.recordRouteRoom(selectedRouteRoomName);
			ConfigManager.save();
		}
		// Per user request ("Sound should also not play when exiting the menu"): a sound-option preview
		// left playing shouldn't keep going once the settings screen it was triggered from is gone.
		com.cokelord.skyblocksimplified.sound.CustomSoundOption.stopAll();
		requestClose();
	}

	private GuiAnimationsFeature animationsFeature() {
		return FeatureRegistry.get("gui_animations") instanceof GuiAnimationsFeature gaf ? gaf : null;
	}

	// Per user request ("Make Transparent theme much more transparent + add gaussian blur background"): a
	// near-see-through panel with nothing blurred behind it just shows the raw game world under the menu
	// text, which hurts legibility badly at the new lower TRANSPARENT_ALPHA. Rather than give Panel Theme its
	// own separate blur pass, this reuses the existing 0-10 blur slider/pipeline (GuiAnimationsFeature +
	// GameRendererBlurMixin, see extractBackground's own doc comment) and simply forces it to at least this
	// floor whenever Transparent mode is active — the user's own blur slider still wins if they've set it
	// higher than this floor.
	private static final int TRANSPARENT_THEME_MIN_BLUR = 4;

	/** Single source of truth for how much background blur should apply this frame — combines the user's own
	 *  GuiAnimationsFeature blur slider with a forced minimum whenever Panel Theme is set to Transparent (see
	 *  {@link #TRANSPARENT_THEME_MIN_BLUR}'s doc comment). Used by both {@link #extractBackground} (to decide
	 *  whether to trigger the blur pass at all) and {@code GameRendererBlurMixin} (which needs the same value
	 *  at the real vanilla read site) so the two can never disagree. */
	public static int effectiveBlurAmount() {
		int level = FeatureRegistry.get("gui_animations") instanceof GuiAnimationsFeature gaf ? gaf.getBlurAmount() : 0;
		if (FeatureRegistry.get("panel_theme") instanceof com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf
				&& ptf.getMode() == com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature.Mode.TRANSPARENT) {
			level = Math.max(level, TRANSPARENT_THEME_MIN_BLUR);
		}
		return level;
	}

	/**
	 * True background blur (not an approximation) — our 0-10 slider is independent of the player's actual
	 * "Menu Background Blurriness" setting. Real bug found and fixed (task #472, "blur slider caps at
	 * 1/10 no matter what it's set to"): this used to temporarily {@code option.set(level)} right here,
	 * call {@code extractBlurredBackground()}, then restore the option — but bytecode disassembly of the
	 * real client jar confirmed {@code GameRenderer.extractOptions()} already reads and caches
	 * {@code Options.getMenuBackgroundBlurriness()} for this frame BEFORE this method (called via
	 * {@code Gui.extractRenderState}) ever runs, so that override always arrived one step too late to
	 * affect anything — the actual blur pass just kept reading the player's real, unmodified vanilla
	 * setting every frame. Fixed at the real read site instead: see {@code GameRendererBlurMixin}, which
	 * redirects that one call to return this mod's own blur amount whenever this screen is open. This
	 * method now only needs to trigger the blur pass itself, not fight over what value it reads — and as a
	 * bonus, no longer flips the player's Graphics Preset to "Custom" the way touching the real option
	 * used to.
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int level = effectiveBlurAmount();
		if (level >= 1) {
			this.extractBlurredBackground(graphics);
		}
		this.extractTransparentBackground(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
		lastFrameNanos = now;
		dt = Math.min(dt, 0.05f);

		GuiAnimationsFeature gaf = animationsFeature();
		if (gaf != null) {
			openAnim.setDurationSeconds(gaf.getOpenDuration());
			searchAnim.setDurationSeconds(gaf.getSearchDuration());
			expandProgress.setDurationSeconds(gaf.getExpandDuration());
			closeDurationSeconds = gaf.getCloseDuration();
			// Half for fading the old modules out, half for fading the new ones in.
			contentSwapFadeAnim.setDurationSeconds(gaf.getContentSwitchDuration() / 2f);
			float revealPhaseDuration = gaf.getOpenDuration() / 3f;
			menuOpenHeaderAnim.setDurationSeconds(revealPhaseDuration);
			menuOpenSidebarAnim.setDurationSeconds(revealPhaseDuration);
			menuOpenContentAnim.setDurationSeconds(revealPhaseDuration);
		}
		previousScale = currentScale;

		if (closing) {
			boolean instantClose = Anim.isGlobalInstant() || closeDurationSeconds <= 0.001f;
			if (instantClose) {
				this.minecraft.gui.setScreen(null);
				return;
			}
			float elapsed = (now - closeStartNanos) / 1_000_000_000f;
			float t = Math.min(1f, elapsed / closeDurationSeconds);
			// ease-in-expo: barely moves at first, then snaps to 0 exponentially fast right at the end
			float eased = t <= 0f ? 0f : (float) Math.pow(2, 10 * (t - 1));
			currentScale = closeStartScale * (1f - eased);
			if (t >= 1f) {
				this.minecraft.gui.setScreen(null);
				return;
			}
		} else {
			openAnim.update(dt);
			currentScale = openAnim.get();
		}

		// currentScale itself settles instantly on open now (see init()'s own doc comment), so gate
		// interactivity on the 3-phase reveal actually finishing too — otherwise a click could land mid-reveal,
		// before the module list has even finished expanding into view.
		interactive = !closing && currentScale >= 0.995f && menuOpenContentAnim.get() >= 0.999f;

		searchAnim.update(dt);
		updateExpandState(dt);
		// Per user request ("add a subtoggle to mod animations to disable [smooth scrolling] if the user
		// wants to"): scrollAnim already only eases when the master GUI Animations toggle is on (Anim.update
		// consults the global-instant flag itself) — this layers the same independent per-behavior override
		// isSliderAnimationEnabled() already gets, by collapsing the duration to 0 (an instant snap, same as
		// SCROLL_DURATION<=0 would) whenever the new subtoggle specifically is off, regardless of the master
		// switch's own state.
		GuiAnimationsFeature scrollGaf = animationsFeature();
		scrollAnim.setDurationSeconds(scrollGaf == null || scrollGaf.isScrollAnimationEnabled() ? SCROLL_DURATION : 0f);
		scrollAnim.setTarget(scrollTarget);
		scrollAnim.update(dt);
		updateContentSwapState(dt);
		updateMenuOpenReveal(dt);

		float centerX = panelX + panelWidth / 2f;
		float centerY = panelY + panelHeight / 2f;

		drawPanelGhosts(graphics, centerX, centerY, previousScale, currentScale);

		graphics.pose().pushMatrix();
		graphics.pose().translate(centerX, centerY);
		graphics.pose().scale(currentScale);
		graphics.pose().translate(-centerX, -centerY);
		layoutAndDraw(graphics, mouseX, mouseY, dt);
		graphics.pose().popMatrix();

		drawPendingTooltip(graphics);
		drawHypixelModApiWarning(graphics);
	}

	private void updateExpandState(float dt) {
		cogVisibility.update(dt);
		expandProgress.update(dt);
		switch (expandPhase) {
			case FADING_COG_OUT -> {
				if (cogVisibility.get() <= 0.02f) {
					expandPhase = ExpandPhase.EXPANDING;
					expandProgress.setTarget(1f);
				}
			}
			case EXPANDING -> {
				if (expandProgress.get() >= 0.999f) {
					expandPhase = ExpandPhase.EXPANDED;
				}
			}
			case COLLAPSING -> {
				if (expandProgress.get() <= 0.001f) {
					if (pendingExpand != null) {
						Feature next = pendingExpand;
						pendingExpand = null;
						startExpand(next);
					} else if (expandTarget instanceof GuiColorFeature) {
						// No cog to fade back in for this one.
						expandPhase = ExpandPhase.CLOSED;
						expandTarget = null;
					} else {
						expandPhase = ExpandPhase.FADING_COG_IN;
						cogVisibility.setTarget(1f);
					}
				}
			}
			case FADING_COG_IN -> {
				if (cogVisibility.get() >= 0.999f) {
					expandPhase = ExpandPhase.CLOSED;
					expandTarget = null;
				}
			}
			default -> {}
		}
	}

	/** Drives the header-rise / sidebar-extend / content-expand open sequence (see menuOpenHeaderAnim's own
	 *  doc comment above). Each phase's target is only raised once the previous phase is essentially
	 *  settled, chaining the three into one sequential reveal that replays fresh every time the menu opens
	 *  (these fields start at 0 and menuOpenHeaderAnim.setTarget(1f) is kicked off once, in init()). */
	private void updateMenuOpenReveal(float dt) {
		menuOpenHeaderAnim.update(dt);
		if (menuOpenHeaderAnim.get() >= 0.999f) {
			menuOpenSidebarAnim.setTarget(1f);
		}
		menuOpenSidebarAnim.update(dt);
		if (menuOpenSidebarAnim.get() >= 0.999f) {
			menuOpenContentAnim.setTarget(1f);
		}
		menuOpenContentAnim.update(dt);
	}

	/** Drives the category/flat-subcategory crossfade (see ContentSwapPhase's own doc comment above). */
	private void updateContentSwapState(float dt) {
		contentSwapFadeAnim.update(dt);
		switch (contentSwapPhase) {
			case FADING_OUT -> {
				if (contentSwapFadeAnim.get() <= 0.001f) {
					Runnable change = pendingContentSwap;
					pendingContentSwap = null;
					if (change != null) change.run();
					contentSwapPhase = ContentSwapPhase.FADING_IN;
					contentSwapFadeAnim.setTarget(1f);
				}
			}
			case FADING_IN -> {
				if (contentSwapFadeAnim.get() >= 0.999f) {
					contentSwapPhase = ContentSwapPhase.NONE;
				}
			}
			default -> {}
		}
	}

	/** Queues a module-list content change (which category/subcategory is selected) behind a fade-out, then
	 *  applies it and fades back in — see ContentSwapPhase's own doc comment for why this stays separate
	 *  from the tile-grid transition. Always safe to call again mid-fade: it just retargets the fade back to
	 *  0 and replaces whichever change was pending, so spamming clicks can never leave this stuck half-faded. */
	private void triggerContentSwap(Runnable applyChange) {
		// Per user request ("The page/category switching animation also doesnt follow the gradient. Honestly,
		// just remove that animation and make it an instant switch instead. Its more satisfying in my opinion
		// than the animation"): this used to fade the old module list out, apply the change, then fade the
		// new one back in via contentSwapFadeAnim/ContentSwapPhase — an earlier round already found that
		// crossfade's own panel-colored overlay doesn't track the Custom theme's gradient at all (it always
		// used a flat panel color, see this method's own removed doc comment for the Transparent-theme carve
		// out that predates this), and the user now prefers an instant switch outright rather than a fixed
		// gradient-aware animation. Every category/subcategory switch now takes the same instant path
		// Transparent mode already used as its own workaround — contentSwapFadeAnim/ContentSwapPhase are left
		// in place (still driven at rest by updateContentSwapState) rather than removed outright, since
		// contentSwapFadeAnim's resting value of 1 is also read elsewhere (e.g. drawRowContent) as "not mid
		// swap", which snapTo(1f) here preserves exactly.
		pendingContentSwap = null;
		contentSwapPhase = ContentSwapPhase.NONE;
		contentSwapFadeAnim.snapTo(1f);
		applyChange.run();
	}

	/** True while any of this screen's popups (enchant color/chroma, or a Positional Messages dropdown)
	 *  is open — the shared condition every popup-priority check below gates on, since only one of them
	 *  can ever be open at once and they all reuse the same anchor/bounds/hit-priority fields. */
	private boolean isStylePopupOpen() {
		return enchantColorPopupTarget != null || enchantChromaPopupTarget != null || posMsgDropdownIndex != null
			|| dungeonNotifColorPopupTarget != null || copilotColorPopupTarget != null || routeColorPopupTarget != null
			|| routeSelectedPopupTarget != null || enchantTierColorPopupTarget != null;
	}

	private void closeStylePopups() {
		enchantColorPopupTarget = null;
		enchantChromaPopupTarget = null;
		posMsgDropdownIndex = null;
		posMsgDropdownKind = null;
		posMsgDropdownScroll = 0f;
		dungeonNotifColorPopupTarget = null;
		copilotColorPopupTarget = null;
		routeColorPopupTarget = null;
		routeSelectedPopupTarget = null;
		enchantTierColorPopupTarget = null;
	}

	/** Draws a thin draggable scrollbar handle on the right edge of a scrollable viewport, sized
	 *  proportionally to how much of the total content is actually visible (a viewport showing half the
	 *  content gets a handle half the track's height), and records its bounds in {@link #scrollbarHits} for
	 *  {@link #handleScrollbarClick}/{@link #mouseDragged} to hit-test against. No-ops (draws nothing, hit-
	 *  tests nothing) when there's nothing to scroll — a maxOffset of ~0 means the content already fits. */
	private void drawScrollbar(GuiGraphicsExtractor graphics, ScrollbarKind kind, int viewportX1, int viewportY0, int viewportY1, float scrollOffset, float maxOffset) {
		if (maxOffset <= 1f) return;
		int trackWidth = 4;
		int x1 = viewportX1 - 2;
		int x0 = x1 - trackWidth;
		int trackHeight = viewportY1 - viewportY0;
		int handleHeight = Math.max(20, Math.round(trackHeight * (trackHeight / (trackHeight + maxOffset))));
		int handleY0 = viewportY0 + Math.round(RenderUtil.clamp01(scrollOffset / maxOffset) * (trackHeight - handleHeight));
		int handleY1 = handleY0 + handleHeight;
		fillRounded(graphics, x0, viewportY0, x1, viewportY1, 2, 0x20FFFFFF);
		boolean active = draggingScrollbar == kind;
		fillRounded(graphics, x0, handleY0, x1, handleY1, 2, active ? 0xFFBBBBBB : 0x99CCCCCC);
		scrollbarHits.add(new ScrollbarHit(kind, x0, viewportY0, x1, viewportY1, handleY0, handleY1, maxOffset));
	}

	/** Converts a mouse Y position within a scrollbar's track into the scroll offset that should put the
	 *  handle's CENTER there — shared by both the initial click (jump-to-position, per user request "so
	 *  users can scroll faster/jump directly") and every subsequent drag frame. */
	private float scrollbarMouseYToOffset(double mouseY, int trackY0, int trackHeight, int handleHeight, float maxOffset) {
		float usableTrack = trackHeight - handleHeight;
		if (usableTrack <= 0f) return 0f;
		float fraction = ((float) mouseY - trackY0 - handleHeight / 2f) / usableTrack;
		return Math.max(0f, Math.min(maxOffset, fraction * maxOffset));
	}

	/** True (and starts a drag/jump) if {@code mouseX,mouseY} landed on a scrollbar drawn this frame —
	 *  called from mouseClicked with the same priority as every other hit list. Clicking the HANDLE starts a
	 *  normal drag from wherever the handle already is; clicking elsewhere in the TRACK jumps straight to
	 *  that position (the handle's center snaps under the cursor), matching common scrollbar UX. */
	private boolean handleScrollbarClick(double mouseX, double mouseY) {
		for (ScrollbarHit hit : scrollbarHits) {
			if (mouseX < hit.x0() || mouseX > hit.x1() || mouseY < hit.y0() || mouseY > hit.y1()) continue;
			draggingScrollbar = hit.kind();
			scrollbarDragTrackY0 = hit.y0();
			scrollbarDragTrackHeight = hit.y1() - hit.y0();
			scrollbarDragHandleHeight = hit.handleY1() - hit.handleY0();
			boolean onHandle = mouseY >= hit.handleY0() && mouseY <= hit.handleY1();
			if (!onHandle) {
				applyScrollbarOffset(hit.kind(), scrollbarMouseYToOffset(mouseY, scrollbarDragTrackY0, scrollbarDragTrackHeight, scrollbarDragHandleHeight, hit.maxOffset()));
			}
			return true;
		}
		return false;
	}

	/** Writes a new scroll offset straight to both the target and the Anim driving it (snapTo, not
	 *  setTarget) — dragging a scrollbar handle is expected to track the cursor 1:1, not ease toward it the
	 *  way a mouse-wheel nudge does; scrollTarget itself still gets updated so a wheel-scroll right after
	 *  releasing the drag continues smoothly from here instead of jumping back to some stale value. */
	private void applyScrollbarOffset(ScrollbarKind kind, float offset) {
		if (kind == ScrollbarKind.CONTENT) {
			scrollTarget = offset;
			scrollAnim.snapTo(offset);
		} else {
			sidebarScrollTarget = offset;
			sidebarScrollAnim.snapTo(offset);
		}
	}

	private void startExpand(Feature feature) {
		playCogSound(true);
		// A leftover popup target from whichever panel was open before would otherwise keep swallowing
		// every click on the new panel (the guard at the top of mouseClicked treats any non-null popup
		// target as "there's an open popup" regardless of which feature it belonged to).
		closeStylePopups();
		// Same problem for per-panel edit/focus state: it's keyed by index/flag, not by which feature it
		// belongs to, so a stale value left over from the previously expanded panel would misapply to the
		// newly opened one (wrong rule editing, stale text focus, etc).
		textFocus = TextFocus.NONE;
		editingRuleIndex = -1;
		editingPosMsgIndex = -1;
		focusedHexKey = null;
		listeningFor = null;
		// Deliberately does NOT reset scrollTarget/scrollAnim here (it used to unconditionally snap both to
		// 0) — this fires on every single cog click regardless of scroll position, so opening the settings
		// panel of a module near the bottom of a long, already-scrolled list yanked the view back up to the
		// top the instant it started expanding. The per-frame clamp in the layout pass (maxScrollOffset,
		// where scrollTarget is bounded to whatever the list's new content height actually allows) already
		// keeps the scroll position valid as the panel's expansion changes total content height, with no
		// need to force it to 0 first.
		expandTarget = feature;
		if (feature instanceof GuiColorFeature) {
			// No cog to fade out for this one — the swatch itself was clicked, so expand immediately.
			expandPhase = ExpandPhase.EXPANDING;
			expandProgress.setTarget(1f);
		} else {
			expandPhase = ExpandPhase.FADING_COG_OUT;
			cogVisibility.setTarget(0f);
		}
	}

	private void startCollapse() {
		if (expandPhase == ExpandPhase.CLOSED || expandPhase == ExpandPhase.COLLAPSING || expandPhase == ExpandPhase.FADING_COG_IN) return;
		playCogSound(false);
		expandPhase = ExpandPhase.COLLAPSING;
		expandProgress.setTarget(0f);
	}

	/**
	 * Searching used to only filter within whatever category/subtab you already had open, so typing a
	 * query that matched a feature elsewhere just showed an empty list ("search isn't working"). Now it
	 * ranks every registered feature by how well its name matches (exact > starts-with > contains) and
	 * navigates straight to the best match's category/subcategory/slayer-tab.
	 */
	private void jumpToSearchMatch() {
		if (searchQuery.isEmpty()) return;
		String q = searchQuery.toLowerCase(Locale.ROOT);
		Feature best = null;
		int bestRank = Integer.MAX_VALUE;
		boolean bestFromSubSetting = false;
		for (Feature f : FeatureRegistry.all()) {
			if (f.isHiddenFromGui()) continue;
			String name = f.getDisplayName().toLowerCase(Locale.ROOT);
			int rank;
			boolean fromSubSetting = false;
			if (name.equals(q)) rank = 0;
			else if (name.startsWith(q)) rank = 1;
			else if (name.contains(q)) rank = 2;
			else if (f.getSearchAliases().stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).contains(q))) {
				// A real alias (e.g. "Secret Routes" for Dungeon Routes, "CS"/"Case" for Chest Rolling) —
				// ranked above a mere sub-setting match since it directly names the module, just under a
				// different word than its own display name.
				rank = 3;
			} else if (settingsLabelsContain(f, q)) {
				// Doesn't match the module's own name or an alias at all — only one of its sub-toggles/
				// sliders does (e.g. searching "essence" for Instance Chest Profit's "Include Essence Price"
				// toggle). Per user request this should still surface the module, with its settings already
				// open rather than just landing on the category — a name-only jump wouldn't explain the match.
				rank = 4;
				fromSubSetting = true;
			} else continue;
			if (rank < bestRank) {
				bestRank = rank;
				best = f;
				bestFromSubSetting = fromSubSetting;
				if (rank == 0) break;
			}
		}
		if (best == null) return;
		selectedCategory = best.getCategory();
		List<String> subs = selectedCategory.getSubcategories();
		selectedSubcategory = best.getSubcategory() != null ? best.getSubcategory() : (subs.isEmpty() ? null : subs.get(0));
		selectedSlayerType = best.getSlayerType();
		scrollTarget = 0f;
		// A search jump is a direct navigation, not a click-triggered crossfade — cancel anything
		// content-swap-related left mid-flight so a jump mid-fade can't leave the list stuck half-faded.
		contentSwapPhase = ContentSwapPhase.NONE;
		pendingContentSwap = null;
		contentSwapFadeAnim.snapTo(1f);
		if (bestFromSubSetting) {
			// startExpand() resets textFocus to NONE (it's meant for a real row click, where nothing was
			// focused beforehand) — called from here, mid-typing in the search box, that would silently
			// steal focus away from the search field the user is still typing in. Restore it right after.
			startExpand(best);
			textFocus = TextFocus.SEARCH;
		}
	}

	/** True if the search query matches any of this feature's own sub-toggle/slider/setting labels —
	 *  hand-collected from the settings-panel dispatcher below (drawExpandedSettings and the content
	 *  methods it delegates to), since those labels aren't otherwise stored anywhere searchable. Only
	 *  covers features whose settings panel has fixed, named controls; scoreboard lines, enchant rules,
	 *  and similar free-form per-entry content aren't included (there's no fixed label to search for).
	 *
	 *  <p><b>Maintenance note (per user request — "put it in your permanent memory to keep updating when
	 *  you add new stuff"):</b> this switch is hand-maintained and WILL go stale on its own. Whenever a new
	 *  feature gets a settings panel branch in {@code drawExpandedSettings} (or an existing one gains/renames
	 *  a row), add/update its case here too with the exact same label strings drawn there — otherwise
	 *  searching for that setting by name silently finds nothing, exactly the bug a full pass over every
	 *  feature branch fixed once already. jumpToSearchMatch() already ranks a module's own name/alias match
	 *  strictly above a sub-setting match here, so this list only ever gets consulted as the fallback once no
	 *  module name/alias matches at all — keep it that way; don't rank sub-setting matches on par with a real
	 *  module match. */
	private boolean settingsLabelsContain(Feature f, String q) {
		Feature target = f instanceof com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror mirror ? mirror.getTarget() : f;
		String[] labels = switch (target) {
			// LividFinderFeature/WitherHighlightFeature/StarredMobHighlightFeature all now extend
			// MobHighlightFeature (see that class's own doc comment) — their cases must come first, since Java
			// requires a subtype pattern-switch case to precede the supertype case that would otherwise
			// dominate it.
			case LividFinderFeature lff -> new String[]{"Render mode", "Outline thickness", "Occlusion (hide behind walls)",
				"Tracers", "High Update Rate (uses more FPS)", "Tracer thickness", "Tracer opacity", "Fill opacity",
				"Use Custom Image", "Image Fill Mode", "Highlight Color", "Fill Color", "Hide Wrong Livids",
				"Color By Livid's Color"};
			case com.cokelord.skyblocksimplified.feature.impl.WitherHighlightFeature whf -> new String[]{"Render mode", "Outline thickness",
				"Occlusion (hide behind walls)", "Tracers", "High Update Rate (uses more FPS)", "Tracer thickness", "Tracer opacity",
				"Fill opacity", "Use Custom Image", "Image Fill Mode", "Highlight Color", "Fill Color",
				"Maxor Color", "Storm Color", "Goldor Color", "Necron Color",
				"Maxor Fill Color", "Storm Fill Color", "Goldor Fill Color", "Necron Fill Color"};
			case com.cokelord.skyblocksimplified.feature.impl.StarredMobHighlightFeature smhf -> new String[]{"Render mode", "Outline thickness",
				"Occlusion (hide behind walls)", "Tracers", "High Update Rate (uses more FPS)", "Tracer thickness", "Tracer opacity",
				"Fill opacity", "Use Custom Image", "Image Fill Mode", "Highlight Color", "Fill Color",
				"Hide Non-Starred Nametags", "Hide Starred Nametags"};
			case com.cokelord.skyblocksimplified.feature.impl.HighlightPartyMembersFeature hpmf -> new String[]{"Render mode", "Outline thickness",
				"Occlusion (hide behind walls)", "Tracers", "High Update Rate (uses more FPS)", "Tracer thickness", "Tracer opacity",
				"Fill opacity", "Use Custom Image", "Image Fill Mode", "Highlight Color", "Fill Color",
				"Color by Role", "Hide Glow"};
			case MobHighlightFeature mhf -> new String[]{"Render mode", "Outline thickness", "Occlusion (hide behind walls)",
				"Tracers", "High Update Rate (uses more FPS)", "Tracer thickness", "Tracer opacity", "Fill opacity",
				"Use Custom Image", "Image Fill Mode", "Highlight Color", "Fill Color"};
			case com.cokelord.skyblocksimplified.feature.impl.HideDamageSplashesFeature hdsf ->
				new String[]{"Only Near Slayer Bosses", "Only Near Dungeon Bosses"};
			case com.cokelord.skyblocksimplified.feature.impl.HideFireFeature hff -> new String[]{"Hide Fire On Entities Aswell"};
			case com.cokelord.skyblocksimplified.feature.impl.HideNametagsFeature hntf -> new String[]{"Only In Dungeons"};
			case com.cokelord.skyblocksimplified.feature.impl.MoongladeBeaconAlertFeature mbaf ->
				new String[]{"Moonglade Beacon Solver", "Solver Highlight Color"};
			case com.cokelord.skyblocksimplified.feature.impl.TerminalSoundsFeature tsf2 ->
				new String[]{"Click Sounds", "Click Sound", "Complete Sounds", "Complete Sound"};
			case com.cokelord.skyblocksimplified.feature.impl.ExperimentAddonsFeature eaf -> new String[]{"Current Click Color", "Next Click Color"};
			case com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsMasterFeature ckmf -> new String[]{"Sensitivity While Active"};
			case com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsHoldingOnlyFeature ckhof -> new String[]{"Hoe", "Fishing Rod"};
			case com.cokelord.skyblocksimplified.feature.impl.HidePestDropsFeature hpdf -> new String[]{"Hide Vinyl Drops"};
			case PestCooldownFeature pcf -> new String[]{"Custom Cooldown", "Custom Cooldown Seconds"};
			case MoneyPerHourFeature mphf -> new String[]{"Price source"};
			case DungeonClickedBlocksFeature dcbf -> new String[]{"Chest Color", "Lever Color", "Wither Essence Color", "Item Secret Color",
				"Secret Chime", "Disable Chest Opening Sound", "Disable Lever Activation Sound", "Disable Bat Dying Sound", "Style", "Box Opacity"};
			case com.cokelord.skyblocksimplified.feature.impl.CustomLoadoutKeybindsFeature clkf -> new String[]{"Highlight Selected Loadout",
				"Loadout 1", "Loadout 2", "Loadout 3", "Loadout 4", "Loadout 5", "Loadout 6",
				"Loadout 7", "Loadout 8", "Loadout 9", "Loadout 10", "Loadout 11", "Loadout 12"};
			case com.cokelord.skyblocksimplified.feature.impl.PetKeybindsFeature pkf ->
				new String[]{"Pet 1", "Pet 2", "Pet 3", "Pet 4", "Pet 5", "Pet 6", "Pet 7", "Pet 8"};
			case CroesusFeature cf -> new String[]{"Hide Claimed Chests", "Highlight Profitable", "Best Chest Color", "2nd Best Chest Color"};
			case com.cokelord.skyblocksimplified.feature.impl.SecretsCounterFeature scf -> new String[]{"Hide Vanilla Action Bar", "Compact Mode", "Text Color"};
			case com.cokelord.skyblocksimplified.feature.impl.ChatDeclutterFeature declutter -> new String[]{"Hide Obtaining Messages",
				"Keys and Doors", "Hide duped class stats message", "Hide solo class buffed stats message", "Hide Fairy Dialogue message",
				"Hide Boss Messages", "Hide Blessing Messages", "Hide Keys", "Hide Grandma Wolf Combo Messages", "Hide Ultimate Ready Message",
				"Remove Rewards Message", "Remove Event Rewards Message", "Remove Profile Message", "Hide Lowballers", "Hide Guild EXP Gain"};
			case com.cokelord.skyblocksimplified.feature.impl.DisableEndermanDeathAnimationFeature dedaf -> new String[]{"Disable Dying Sound", "Disable Teleport Sound"};
			case com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature iaf -> new String[]{"X Offset", "Y Offset", "Z Offset",
				"Yaw", "Pitch", "Roll", "Size", "Disable Full Swing Animation", "In Place Swing Animation", "Swing Speed",
				"Disable Item Swapping Animation", "Disable Hand Swaying"};
			case ItemPickupLogFeature iplf -> new String[]{"Compact Lines", "Compact Numbers", "Expire after (s)"};
			case GuiAnimationsFeature gaf -> new String[]{"Opening", "Closing", "Module opening", "Search bar", "Category switch",
				"Background blur", "Cog spin on hover", "Slider animation", "Smooth scrolling"};
			case GuiColorFeature gcf -> new String[]{"Accent Color", "Chroma", "Chroma Saturation", "Chroma Speed"};
			case com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature tsf -> new String[]{"Custom Terminal GUI",
				"Block Wrong Clicks", "Middle Click Redirect", "Click Animations", "GUI Scale", "First Click Protection",
				"Terminal Color"};
			case com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature lmf -> new String[]{"Sort Mode", "Only Show Class",
				"Color Style", "Background Color", "Background Opacity", "Render Scale", "Announce Leap", "Sync with Map"};
			case com.cokelord.skyblocksimplified.feature.impl.DungeonQueueFeature dqf -> new String[]{"Announce Kick",
				"Auto Requeue", "Requeue Delay", "Disable on leave/kick"};
			case com.cokelord.skyblocksimplified.feature.impl.InactiveWaypointsFeature iwf2 -> new String[]{"Show Terminals",
				"Show Devices", "Show Levers", "Show Titles", "Show Block Highlight", "Hide Default Vanilla Names",
				"Sync With Class", "Show Terminal Hitbox as Highlight", "Highlight Color"};
			case com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature ddf2 -> new String[]{"Hide Superboom TNT",
				"Hide Blessings", "Hide Revive Stones", "Hide Premium Flesh", "Hide Journal Entry", "Hide Healer Orbs",
				"Hide Skeleton Skull", "Hide Healer Fairy", "Hide Soulweaver Skulls", "Hide Wither Skulls", "Skull Message Remover", "Milestone Message Remover",
				"Ability Text Remover", "Hide Solo Class Stats Message", "Hide Dungeon Potion Reminder", "Hide Dungeonbreaker Messages",
				"Hide Dungeon Mob Messages", "Hide Unable to Teleport Message", "Hide Oruo Messages"};
			case com.cokelord.skyblocksimplified.feature.impl.LeapCounterFeature lcf2 -> new String[]{"SS", "High EE2",
				"EE3", "Core", "Waiting for Healer", "GY Dropoff", "Custom Color"};
			case com.cokelord.skyblocksimplified.feature.impl.DamageTruncatorFeature dtf1 -> new String[]{"Decimals"};
			case com.cokelord.skyblocksimplified.feature.impl.NetworkDisplayFeature ndf -> new String[]{"Show Ping", "Show TPS",
				"Bold Text", "Italic Text", "Color Code TPS", "Ping Color", "TPS Color"};
			case com.cokelord.skyblocksimplified.feature.impl.BossBarFeature bbf -> new String[]{"Hide Vanilla Boss Bar",
				"Show Floor 7 Phase", "Text Color"};
			case com.cokelord.skyblocksimplified.feature.impl.HideArrowsFeature haf -> new String[]{"Only Ground / In Players"};
			case com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature dnf -> new String[]{"Show Title", "Play Sound",
				"Bold Title", "Italic Title", "Watcher Done Spawning", "All Blood Mobs Dead", "Healer at SS",
				"Berserker at GY Dropoff", "Berserker Waiting for Healer", "All Early Enters", "Room Cleared", "300 Score",
				"270 Score", "Ultimate Ready", "Healer Should Ult", "Storm Crushed", "5 Crypts", "Key Drop",
				"Downtime Request (Leader)"};
			case com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature dmf -> new String[]{"Outline", "Outline Thickness",
				"Outline Color", "Background", "Background Opacity", "Background Color", "Player Heads Instead of Arrows",
				"Reveal Unexplored Rooms (use at own risk)", "Show Names", "Room Label",
				"Map Info", "Show Crypts", "Show Deaths", "Show Mimic Status", "Map Info Text Color"};
			case com.cokelord.skyblocksimplified.feature.impl.DungeonScoreCalculatorFeature dscf -> new String[]{"Only in Dungeons",
				"Auto-detect Mayor Perk"};
			case com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature cef -> new String[]{"Catacombs Expert Ring",
				"Derpy Mayor", "Hecatomb Level", "Bonzo's Shard Level", "Floor", "Target Catacombs Level",
				"Include First-30-Minutes Boost", "Average Run Length"};
			case com.cokelord.skyblocksimplified.feature.impl.DungeonTimersFeature dtmf -> new String[]{"Storm Purple Pad Timer",
				"Terminals Timer", "Goldor Start Timer", "Goldor Early-Enter Loop", "Goldor Loop Style", "Necron Drop Timer",
				"Maxor Crystal Timer", "Bold Text", "Italic Text", "Storm Lightning Sync"};
			case com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf -> new String[]{"Edit Mode",
				"Show All Steps", "Show Coordinates", "Waypoint Tracer", "Waypoint Beacon Line", "Title Update Sound", "Sound",
				"Hide Title Background", "Bold Title", "Italic Title"};
			case com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf -> new String[]{"Waypoint Tracer",
				"Waypoint Path Line", "Highlight Opacity", "Edit Mode (restart on finish)"};
			case com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature csf -> new String[]{"Keybind"};
			case com.cokelord.skyblocksimplified.feature.impl.ChestRollingFeature crf -> new String[]{"Only Bedrock Chests",
				"Obsidian Chests Too on Lower Floors", "Only Croesus", "Obfuscate Rare Items", "Roll Duration",
				"Hide Lore", "Hide Lore on All Chests", "Landing Sound"};
			case com.cokelord.skyblocksimplified.feature.impl.ChatCommandsFeature ccf -> new String[]{"Chat Emotes",
				"Party Commands", "Guild Commands", "Private Commands", "!coords", "!boop", "!cf (coinflip)", "!8ball",
				"!dice", "!racism", "!ping", "!fps", "!time", "!location", "!holding", "!warp (party)",
				"!allinvite (party)", "!pt / transfer (party)", "!promote (party)", "!demote (party)", "!kick (party)",
				"!kickoffline (party)", "!downtime / !undowntime (party)", "!reinvite (party)",
				"!f1-f7/!m1-m7/!t1-t5 (party)", "!invite (private)", "Auto Confirm Invite (private)"};
			case com.cokelord.skyblocksimplified.feature.impl.InvincibilityTimerFeature itf -> new String[]{"Show Spirit Mask",
				"Show Bonzo's Mask", "Show Phoenix Pet", "Alert (chat + sound)", "Announce to Party", "Only in Dungeons",
				"Only in Boss Fight", "Show as Title"};
			case com.cokelord.skyblocksimplified.feature.impl.AbilityCooldownTimerFeature actf -> new String[]{"Shrinking Overlay (vanilla-style)"};
			case com.cokelord.skyblocksimplified.feature.impl.PetDisplayFeature pdf -> new String[]{"Compact Mode (Icon Only)"};
			case com.cokelord.skyblocksimplified.feature.impl.AutoKickFeature akf -> new String[]{"Require Hyperion",
				"Require Terminator", "Require Golden Dragon", "Require 1B+ in Bank", "Min PB Minutes", "Min PB Seconds"};
			case com.cokelord.skyblocksimplified.feature.impl.PartyFinderFeature pff -> new String[]{"Total Secrets",
				"Secrets/Run", "Hyperion", "Terminator", "Golden Dragon", "1B+ in Bank", "Armor List", "Equipment List",
				"F7 Completions", "M5 Completions", "M6 Completions", "M7 Completions"};
			case com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature sof -> new String[]{"Columns", "Size",
				"Retain Scroll", "Show Background", "Inventory Position"};
			case com.cokelord.skyblocksimplified.feature.impl.LoadoutOverlayFeature lof -> new String[]{"Columns", "Size", "Show Background"};
			case com.cokelord.skyblocksimplified.feature.impl.MageBeamFeature mbf -> new String[]{"Opacity", "Thickness",
				"Hide Vanilla Particles", "Depth Check", "Beam Color"};
			case com.cokelord.skyblocksimplified.feature.impl.SlotBindsFeature sbf -> new String[]{"Bind-Set Key", "Line Display",
				"Profile", "Connector Line Color", "Bound Slot Outline Color"};
			case com.cokelord.skyblocksimplified.feature.impl.EtherwarpFeature ewf -> new String[]{"Style", "Show When Failed",
				"Full Block (ignore hitbox shape)", "Success Sound", "Sound", "Success Color", "Fail Color"};
			case com.cokelord.skyblocksimplified.feature.impl.ArrowHitSoundFeature ahsf -> new String[]{"Sound"};
			case com.cokelord.skyblocksimplified.feature.impl.MimicFeature mf -> new String[]{"Announce Mimic Kill",
				"Announce Prince Kill", "Announce Bat Kill", "Show Mimic Chest", "Mimic Chest Color"};
			case com.cokelord.skyblocksimplified.feature.impl.MelodyMessageFeature mmf -> new String[]{"Send Open Message",
				"Send Progress Message", "Prefix (appears as \"Prefix (N/4)\")"};
			case com.cokelord.skyblocksimplified.feature.impl.ItemRarityBackgroundFeature irbf -> new String[]{"Shape"};
			case com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature kfb -> new String[]{"Block on Non-Bedrock Chests",
				"Block on Rare Drops", "Rare-drop profit threshold"};
			case com.cokelord.skyblocksimplified.feature.impl.ArrowsDeviceFeature adf -> new String[]{"Announce Completion",
				"Show Emerald Blocks", "Show Prediction", "Emerald Opacity", "Marked Color", "Target Color", "Prediction Color"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.BeamsSolverFeature bsf -> new String[]{"Sound on Lantern Hit",
				"Highlight Matching Lantern", "Tracer to Matching Lantern", "Style", "Hit Sound", "Box Opacity"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.BlazeSolverFeature blsf -> new String[]{"Draw Line to Next Target",
				"Occlusion (hide behind walls)", "Announce Completion to Party", "Don't Render Blazes", "Render mode",
				"Line to Next Count", "1st Target Color", "2nd Target Color", "3rd Target Color", "Rest Color"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.BoulderSolverFeature bof -> new String[]{"Show All Remaining Clicks",
				"Style", "Occlusion (hide behind walls)", "Highlight Color"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.TicTacToeSolverFeature tttf -> new String[]{"Predict Next Move",
				"Send \"Tic Tac Toe Done\" Message", "Best Move Color", "Predicted Move Color"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.IceFillSolverFeature ifsf -> new String[]{"Optimized (Hard) Patterns",
				"Path Line Color"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.QuizSolverFeature qsf -> new String[]{"Depth Check", "Highlight Color"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.TPMazeSolverFeature tmsf -> new String[]{
				"Only Candidate Color", "Multiple Candidates Color", "Visited Pad Color"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.WaterSolverFeature wsf -> new String[]{"Show Tracer",
				"Optimized Solution", "Tracer Color", "Next-Up Line Color"};
			case com.cokelord.skyblocksimplified.feature.impl.puzzle.WeirdosSolverFeature wdsf -> new String[]{"Style",
				"Correct Chest Color", "Wrong Chest Color"};
			case com.cokelord.skyblocksimplified.feature.impl.PlayerDisplayFeature pldf -> new String[]{"Show Icons",
				"Hide Vanilla Armor Bar", "Hide Vanilla Food Bar", "Hide Vanilla Hearts", "Hide Vanilla XP Bar",
				"Show Health", "Show Defense", "Show Mana", "Show Overflow Mana", "Show Vitality", "Show Speed",
				"Speed Text Color"};
			case com.cokelord.skyblocksimplified.feature.impl.DoorHighlightFeature dhf -> new String[]{"Locked Door Color",
				"Openable Door Color", "Wither Key Color", "Blood Key Color"};
			case com.cokelord.skyblocksimplified.feature.impl.BloodCampFeature bcf -> new String[]{"Move Prediction",
				"Announce Move Time", "\"Kill Mobs\" Title", "Predict Spawn Location", "Blood Mob Spawn Tracers",
				"Show Time Left Until Spawn", "Render mode", "Occlusion (hide behind walls)", "Auto Ping Offset", "Box Size",
				"Assume Tick", "Timing Offset (ms)", "Manual Offset (ms)", "Position Color", "Spawn Color", "Final (Ready) Color"};
			case ActiveHotfPerksHighlightFeature ahpf -> new String[]{"Highlight Color"};
			case DnaAnalyzerSolverFeature dasf -> new String[]{"Highlight Color"};
			case com.cokelord.skyblocksimplified.feature.impl.ChatCopyFeature ccpf -> new String[]{"Copy Hovered Line Keybind"};
			case com.cokelord.skyblocksimplified.feature.impl.RareRewardWarningFeature rrwf -> new String[]{"Bypass Key (allows refusing rare offers)"};
			case com.cokelord.skyblocksimplified.feature.impl.GyroHelperFeature ghf -> new String[]{"Show Cooldown", "Depth Check",
				"Ring Width", "Ring Color", "Cooldown Color"};
			case com.cokelord.skyblocksimplified.feature.impl.LavaToWaterFeature ltwf -> new String[]{"Color Tint", "Hide Fog", "Tint Color"};
			case com.cokelord.skyblocksimplified.feature.impl.MelodyDisplayFeature mdf -> new String[]{"Play Sound",
				"Broadcast Progress (Odin Relay)", "Alert Duration", "Custom Color"};
			case com.cokelord.skyblocksimplified.feature.impl.SimonSaysFeature ssf -> new String[]{"SS Skip Compatibility",
				"Progress Display", "Announce Progress in Party Chat", "Block Wrong Clicks", "Block Wrong on Start", "Style",
				"Max Start Clicks", "First Color", "Second Color", "Other Color"};
			case com.cokelord.skyblocksimplified.feature.impl.EntityRenderDistanceFeature erdf -> new String[]{"Include Players", "Distance"};
			case com.cokelord.skyblocksimplified.feature.impl.CameraFeature cmf -> new String[]{"Disable Front View", "Max Distance"};
			case com.cokelord.skyblocksimplified.feature.impl.SplitsFeature spf -> new String[]{"Fixed Width", "Boss Entry Split",
				"Send Splits to Chat", "Outline", "Split Location", "Outline Thickness", "Outline Color", "Background Color"};
			case com.cokelord.skyblocksimplified.feature.impl.AuctionHouseTotalFeature ahtf -> new String[]{"Show \"If Sold\" Total"};
			case com.cokelord.skyblocksimplified.feature.impl.AuctionTimersFeature atmf -> new String[]{"Bold", "Italic", "Color"};
			case com.cokelord.skyblocksimplified.feature.impl.ExperimentationTimersFeature etf -> new String[]{"Notify Only at 3",
				"Notify Chat", "Notify Sound", "Notify Title"};
			case com.cokelord.skyblocksimplified.feature.impl.ArrowAlignFeature aaf -> new String[]{"Predevice Support"};
			case com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf -> new String[]{"Panel Theme",
				"Custom", "Gradient", "Opacity", "Accent Color"};
			case com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature fif -> new String[]{"Applies To"};
			case com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature ptgf -> new String[]{
				"Reduce Item Rarity Background Updates", "Door Highlight", "Blood Camp", "Dungeon Map"};
			case com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf -> new String[]{"Only in Floor 7 Bossfight",
				"Show Position Boxes", "Circle Instead of Square", "Show Text", "Depth Check", "Wall Height", "Box Color"};
			default -> null;
		};
		if (labels == null) return false;
		for (String label : labels) {
			if (label.toLowerCase(Locale.ROOT).contains(q)) return true;
		}
		return false;
	}

	// --- Shared text editing (cursor + selection) for SEARCH/ENCHANT_NAME/ENCHANT_LEVEL/POSMSG_* ----

	private String getFocusedText() {
		return switch (textFocus) {
			case SEARCH -> searchQuery;
			case ENCHANT_NAME -> (expandTarget instanceof CustomEnchantParsingFeature cef && editingRuleIndex >= 0 && editingRuleIndex < cef.getRules().size())
				? cef.getRules().get(editingRuleIndex).name : "";
			case ENCHANT_LEVEL -> (expandTarget instanceof CustomEnchantParsingFeature cef && editingRuleIndex >= 0 && editingRuleIndex < cef.getRules().size())
				? cef.getRules().get(editingRuleIndex).levelInput : "";
			case POSMSG_TEXT -> currentPosMsg() != null ? currentPosMsg().message : "";
			case POSMSG_X -> currentPosMsg() != null ? currentPosMsg().xInput : "";
			case POSMSG_Y -> currentPosMsg() != null ? currentPosMsg().yInput : "";
			case POSMSG_Z -> currentPosMsg() != null ? currentPosMsg().zInput : "";
			case POSMSG_RANGE -> currentPosMsg() != null ? currentPosMsg().rangeInput : "";
			case CONFIG_IMPORT -> configImportBuffer;
			case COPILOT_IMPORT -> copilotImportBuffer;
			case POSMSG_CONFIG_IMPORT -> posMsgConfigImportBuffer;
			case VISUALWORD_FIND -> currentVisualWord() != null ? currentVisualWord().find : "";
			case VISUALWORD_REPLACE -> currentVisualWord() != null ? currentVisualWord().replace : "";
			case DUNGEON_NOTIF_TITLE -> currentDungeonNotifications() != null && editingNotifType != null
				? currentDungeonNotifications().getTypeTitleRaw(editingNotifType) : "";
			case DUNGEON_NOTIF_PARTY_MSG -> currentDungeonNotifications() != null && editingNotifType != null
				? currentDungeonNotifications().getPartyChatMessage(editingNotifType) : "";
			case COPILOT_FIELD -> editingCopilotItem != null && editingCopilotField != null
				? copilotFieldValueOf(editingCopilotItem, editingCopilotField) : "";
			case ALIAS_FIELD -> editingAlias != null && editingAliasField != null
				? aliasFieldValueOf(editingAlias, editingAliasField) : "";
			case SHORTCUT_COMMAND -> editingShortcut != null ? editingShortcut.commandInput : "";
			case KISMET_THRESHOLD -> currentKismetFeatherBlock() != null ? currentKismetFeatherBlock().getThresholdInput() : "";
			case ROUTE_FIELD -> editingRouteItem != null && editingRouteField != null
				? routeFieldValueOf(editingRouteItem, editingRouteField) : "";
			case ROUTE_ALL_IMPORT -> routeAllImportBuffer;
			case ROUTE_ROOM_IMPORT -> routeRoomImportBuffer;
			case ROUTE_ROOM_SEARCH -> routeRoomSearchQuery;
			case GENERIC_FIELD -> genericFocusedFieldId != null && genericFieldGetters.containsKey(genericFocusedFieldId)
				? genericFieldGetters.get(genericFocusedFieldId).get() : "";
			default -> "";
		};
	}

	private com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature currentKismetFeatherBlock() {
		return expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature kfb ? kfb : null;
	}

	private static String aliasFieldValueOf(com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature.Alias alias, String field) {
		return "trigger".equals(field) ? alias.triggerInput : alias.replacementInput;
	}

	private static String copilotFieldValueOf(com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item, String field) {
		return switch (field) {
			case "chat" -> item.chatTrigger;
			case "split" -> item.splitTrigger;
			case "offset" -> item.splitOffsetInput;
			case "title" -> item.titleText;
			case "x" -> item.xInput;
			case "y" -> item.yInput;
			case "z" -> item.zInput;
			case "targetBlock" -> item.targetBlockInput;
			default -> "";
		};
	}

	private static String routeFieldValueOf(com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item, String field) {
		return switch (field) {
			case "x" -> item.xInput;
			case "y" -> item.yInput;
			case "z" -> item.zInput;
			default -> "";
		};
	}

	private static void setRouteFieldValue(com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item, String field, String newText) {
		switch (field) {
			case "x" -> item.xInput = newText;
			case "y" -> item.yInput = newText;
			case "z" -> item.zInput = newText;
		}
	}

	private com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature currentDungeonNotifications() {
		return expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature dnf ? dnf : null;
	}

	private com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature.WordReplacement currentVisualWord() {
		if (!(expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature vwf)) return null;
		List<com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature.WordReplacement> list = vwf.getReplacements();
		return editingVisualWordIndex >= 0 && editingVisualWordIndex < list.size() ? list.get(editingVisualWordIndex) : null;
	}

	private com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.PosMessage currentPosMsg() {
		if (!(expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf)) return null;
		List<com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.PosMessage> list = pmf.getMessages();
		return editingPosMsgIndex >= 0 && editingPosMsgIndex < list.size() ? list.get(editingPosMsgIndex) : null;
	}

	private void setFocusedText(String newText) {
		switch (textFocus) {
			case SEARCH -> { searchQuery = newText; jumpToSearchMatch(); return; }
			case ENCHANT_NAME -> {
				if (expandTarget instanceof CustomEnchantParsingFeature cef && editingRuleIndex >= 0 && editingRuleIndex < cef.getRules().size()) {
					cef.getRules().get(editingRuleIndex).name = newText;
				}
			}
			// The level field's underlying model is an int (see Rule.level) parsed from whatever the
			// user typed — kept in sync on every keystroke that parses cleanly, but the raw text (which
			// may be mid-edit garbage) is what's actually displayed, in levelInput, so a not-yet-valid
			// in-progress edit doesn't get silently reformatted/reverted out from under the cursor.
			case ENCHANT_LEVEL -> {
				if (expandTarget instanceof CustomEnchantParsingFeature cef && editingRuleIndex >= 0 && editingRuleIndex < cef.getRules().size()) {
					CustomEnchantParsingFeature.Rule rule = cef.getRules().get(editingRuleIndex);
					rule.levelInput = newText;
					int parsed = com.cokelord.skyblocksimplified.item.RomanNumeralUtil.parseLevel(newText);
					if (parsed > 0) rule.level = parsed;
				}
			}
			case POSMSG_TEXT -> { if (currentPosMsg() != null) currentPosMsg().message = newText; }
			case POSMSG_X -> {
				var m = currentPosMsg();
				if (m != null) { m.xInput = newText; try { m.x = Double.parseDouble(newText); } catch (NumberFormatException ignored) {} }
			}
			case POSMSG_Y -> {
				var m = currentPosMsg();
				if (m != null) { m.yInput = newText; try { m.y = Double.parseDouble(newText); } catch (NumberFormatException ignored) {} }
			}
			case POSMSG_Z -> {
				var m = currentPosMsg();
				if (m != null) { m.zInput = newText; try { m.z = Double.parseDouble(newText); } catch (NumberFormatException ignored) {} }
			}
			case POSMSG_RANGE -> {
				var m = currentPosMsg();
				if (m != null) { m.rangeInput = newText; try { m.range = Math.max(0.1, Double.parseDouble(newText)); } catch (NumberFormatException ignored) {} }
			}
			// Deliberately not persisted: this is scratch space for a config string the player is about to
			// import, not a real setting — nothing worth saving to disk, and returning early (like SEARCH)
			// skips the ConfigManager.save() call below.
			case CONFIG_IMPORT -> { configImportBuffer = newText; return; }
			// Same reasoning as CONFIG_IMPORT above: scratch space for a Copilot plan string about to be
			// imported, not itself a persisted setting.
			case COPILOT_IMPORT -> { copilotImportBuffer = newText; return; }
			// Same reasoning as CONFIG_IMPORT/COPILOT_IMPORT above: scratch space for a Positional Messages
			// export string about to be imported, not itself a persisted setting.
			case POSMSG_CONFIG_IMPORT -> { posMsgConfigImportBuffer = newText; return; }
			case VISUALWORD_FIND -> { if (currentVisualWord() != null) currentVisualWord().find = newText; }
			case VISUALWORD_REPLACE -> { if (currentVisualWord() != null) currentVisualWord().replace = newText; }
			case DUNGEON_NOTIF_TITLE -> {
				if (currentDungeonNotifications() != null && editingNotifType != null) currentDungeonNotifications().setTypeTitle(editingNotifType, newText);
			}
			case DUNGEON_NOTIF_PARTY_MSG -> {
				if (currentDungeonNotifications() != null && editingNotifType != null) currentDungeonNotifications().setPartyChatMessage(editingNotifType, newText);
			}
			case COPILOT_FIELD -> {
				if (editingCopilotItem != null && editingCopilotField != null) setCopilotFieldValue(editingCopilotItem, editingCopilotField, newText);
			}
			case ALIAS_FIELD -> {
				if (editingAlias != null && editingAliasField != null) setAliasFieldValue(editingAlias, editingAliasField, collapseLeadingSlash(newText));
			}
			case SHORTCUT_COMMAND -> {
				if (editingShortcut != null) editingShortcut.commandInput = collapseLeadingSlash(newText);
			}
			case KISMET_THRESHOLD -> {
				if (currentKismetFeatherBlock() != null) currentKismetFeatherBlock().setThresholdInput(newText);
			}
			case ROUTE_FIELD -> {
				if (editingRouteItem != null && editingRouteField != null) setRouteFieldValue(editingRouteItem, editingRouteField, newText);
			}
			// Scratch space for a Dungeon Routes plan string about to be imported, same reasoning as
			// COPILOT_IMPORT/CONFIG_IMPORT above — not itself a persisted setting.
			case ROUTE_ALL_IMPORT -> { routeAllImportBuffer = newText; return; }
			case ROUTE_ROOM_IMPORT -> { routeRoomImportBuffer = newText; return; }
			case ROUTE_ROOM_SEARCH -> { routeRoomSearchQuery = newText; return; }
			case GENERIC_FIELD -> {
				if (genericFocusedFieldId != null) {
					var setter = genericFieldSetters.get(genericFocusedFieldId);
					if (setter != null) setter.accept(newText);
				}
			}
			default -> { return; }
		}
		ConfigManager.save();
	}

	/** Per user request ("automatically add a slash at the start and if the user types a slash at the
	 *  start strip it") for both Command Aliases' fields and Command Shortcuts' command field: the stored
	 *  value always starts with exactly one "/" — auto-added if missing, collapsed down to one if the user
	 *  typed extra — so what's displayed IS what's stored (no separate display-only prefix to keep cursor
	 *  math in sync with). Each feature's own normalize()/matching logic strips it back off again at
	 *  match/send time, since ClientConnection#sendCommand/MODIFY_COMMAND both expect no leading slash. */
	private static String collapseLeadingSlash(String text) {
		int i = 0;
		while (i < text.length() && text.charAt(i) == '/') i++;
		return "/" + text.substring(i);
	}

	private static void setAliasFieldValue(com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature.Alias alias, String field, String newText) {
		if ("trigger".equals(field)) alias.triggerInput = newText; else alias.replacementInput = newText;
	}

	private static void setCopilotFieldValue(com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item, String field, String newText) {
		switch (field) {
			case "chat" -> item.chatTrigger = newText;
			case "split" -> item.splitTrigger = newText;
			case "offset" -> {
				item.splitOffsetInput = newText;
				try { item.splitOffsetSeconds = Float.parseFloat(newText); } catch (NumberFormatException ignored) {}
			}
			case "title" -> item.titleText = newText;
			case "x" -> { item.xInput = newText; try { item.x = Double.parseDouble(newText); } catch (NumberFormatException ignored) {} }
			case "y" -> { item.yInput = newText; try { item.y = Double.parseDouble(newText); } catch (NumberFormatException ignored) {} }
			case "z" -> { item.zInput = newText; try { item.z = Double.parseDouble(newText); } catch (NumberFormatException ignored) {} }
			case "targetBlock" -> item.targetBlockInput = newText;
		}
	}

	/** Focuses one of the shared-cursor field types and resets the cursor to the end of its text with
	 *  no selection — called from every click site that focuses SEARCH/TITLE/FOOTER/LINE_TEXT so the
	 *  cursor/selection state doesn't leak stale positions from whichever field was focused before. */
	private void focusTextField(TextFocus focus) {
		textFocus = focus;
		textSelectionAnchor = -1;
		textCursor = getFocusedText().length();
	}

	private boolean hasSelection() {
		return textSelectionAnchor >= 0 && textSelectionAnchor != textCursor;
	}

	/** Every TextFocus value that shares the common cursor/selection editing plumbing (as opposed to
	 *  NONE or the separately-handled HEX buffer) — used by charTyped/keyPressed instead of repeating
	 *  the same five-way OR chain at each call site. */
	private static boolean isSharedTextFocus(TextFocus focus) {
		return focus == TextFocus.SEARCH || focus == TextFocus.ENCHANT_NAME || focus == TextFocus.ENCHANT_LEVEL
			|| focus == TextFocus.POSMSG_TEXT || focus == TextFocus.POSMSG_X || focus == TextFocus.POSMSG_Y
			|| focus == TextFocus.POSMSG_Z || focus == TextFocus.POSMSG_RANGE || focus == TextFocus.CONFIG_IMPORT
			|| focus == TextFocus.VISUALWORD_FIND || focus == TextFocus.VISUALWORD_REPLACE
			|| focus == TextFocus.DUNGEON_NOTIF_TITLE || focus == TextFocus.DUNGEON_NOTIF_PARTY_MSG
			|| focus == TextFocus.COPILOT_FIELD || focus == TextFocus.ALIAS_FIELD || focus == TextFocus.SHORTCUT_COMMAND
			|| focus == TextFocus.COPILOT_IMPORT || focus == TextFocus.KISMET_THRESHOLD || focus == TextFocus.ROUTE_FIELD
			|| focus == TextFocus.ROUTE_ALL_IMPORT || focus == TextFocus.ROUTE_ROOM_IMPORT || focus == TextFocus.ROUTE_ROOM_SEARCH
			|| focus == TextFocus.GENERIC_FIELD || focus == TextFocus.POSMSG_CONFIG_IMPORT;
	}

	private void insertAtCursor(String insertText) {
		String current = getFocusedText();
		int start = hasSelection() ? Math.min(textCursor, textSelectionAnchor) : textCursor;
		int end = hasSelection() ? Math.max(textCursor, textSelectionAnchor) : textCursor;
		start = Math.max(0, Math.min(start, current.length()));
		end = Math.max(0, Math.min(end, current.length()));
		String result = current.substring(0, start) + insertText + current.substring(end);
		setFocusedText(result);
		// Real bug found (per user report — "I can't type in the command aliases boxes"): ALIAS_FIELD and
		// SHORTCUT_COMMAND both run setFocusedText's value through collapseLeadingSlash, which can change the
		// text's length (e.g. typing the very first character into an empty field silently gained a leading
		// "/", one character longer than `result`). The cursor was set from `result`'s own length, not the
		// text that actually ended up stored — every subsequent keystroke then inserted one character short
        // of the real end, landing progressively further from where the user was actually typing, which is
		// exactly what "can't type" looks like (the caret visually stuck near the front, later keystrokes
		// scrambling into the middle of already-typed text). Re-measuring against the real stored text and
		// carrying the length delta forward fixes every caller of insertAtCursor, not just these two fields —
		// a no-op adjustment (delta 0) for every other field whose setFocusedText doesn't transform the value.
		int delta = getFocusedText().length() - result.length();
		textCursor = Math.max(0, Math.min(getFocusedText().length(), start + insertText.length() + delta));
		textSelectionAnchor = -1;
	}

	/** Backspace: deletes the selection if there is one, otherwise the character before the cursor. */
	private void backspaceAtCursor() {
		String current = getFocusedText();
		if (hasSelection()) {
			int start = Math.min(textCursor, textSelectionAnchor);
			int end = Math.max(textCursor, textSelectionAnchor);
			String result = current.substring(0, start) + current.substring(Math.min(end, current.length()));
			setFocusedText(result);
			int delta = getFocusedText().length() - result.length();
			textCursor = Math.max(0, Math.min(getFocusedText().length(), start + delta));
			textSelectionAnchor = -1;
		} else if (textCursor > 0 && textCursor <= current.length()) {
			String result = current.substring(0, textCursor - 1) + current.substring(textCursor);
			setFocusedText(result);
			int delta = getFocusedText().length() - result.length();
			textCursor = Math.max(0, Math.min(getFocusedText().length(), textCursor - 1 + delta));
		}
	}

	private void selectAllFocused() {
		String current = getFocusedText();
		textSelectionAnchor = 0;
		textCursor = current.length();
	}

	/** Finds the next word boundary from `from`, skipping any run of spaces first then the run of
	 *  non-space characters (or the reverse, going backward) — standard Ctrl+Arrow word-jump behavior. */
	private static int findWordBoundary(String text, int from, boolean forward) {
		int i = Math.max(0, Math.min(from, text.length()));
		if (forward) {
			while (i < text.length() && text.charAt(i) == ' ') i++;
			while (i < text.length() && text.charAt(i) != ' ') i++;
		} else {
			while (i > 0 && text.charAt(i - 1) == ' ') i--;
			while (i > 0 && text.charAt(i - 1) != ' ') i--;
		}
		return i;
	}

	private void moveCursorByWord(boolean forward, boolean extendSelection) {
		String current = getFocusedText();
		int newPos = findWordBoundary(current, textCursor, forward);
		if (extendSelection) {
			if (textSelectionAnchor < 0) textSelectionAnchor = textCursor;
		} else {
			textSelectionAnchor = -1;
		}
		textCursor = newPos;
	}

	/** Maps a mouse X coordinate to the nearest character boundary in `text`, for click-to-position and
	 *  double-click-to-select-word. */
	private int charIndexAtX(String text, int fieldTextX, double mouseX) {
		double relX = mouseX - fieldTextX;
		if (relX <= 0) return 0;
		for (int i = 0; i <= text.length(); i++) {
			if (this.font.width(text.substring(0, i)) >= relX) return i;
		}
		return text.length();
	}

	/** Selects the run of non-space characters touching `index` (or does nothing if it's on a space). */
	private void selectWordAt(String text, int index) {
		index = Math.max(0, Math.min(index, text.length()));
		if (index >= text.length() || text.charAt(index) == ' ') {
			if (index > 0 && text.charAt(index - 1) != ' ') index--;
			else { textCursor = index; textSelectionAnchor = -1; return; }
		}
		int start = index;
		while (start > 0 && text.charAt(start - 1) != ' ') start--;
		int end = index;
		while (end < text.length() && text.charAt(end) != ' ') end++;
		textSelectionAnchor = start;
		textCursor = end;
	}

	private void selectCategory(FeatureCategory category) {
		selectedCategory = category;
		// Per user request ("remove the entire tile grid subcategory system, I like the buttons at the
		// top"): subcategories are always a plain flat button row now (see drawFlatSubcategoryTabs) — landing
		// on a category auto-selects its first real subcategory (skipping the Edit GUI Locations sentinel,
		// which opens a screen rather than filtering) so a module list is always showing immediately.
		List<String> subs = category.getSubcategories();
		selectedSubcategory = subs.stream().filter(s -> !s.equals(EDIT_GUI_LOCATIONS_SUBCATEGORY)).findFirst().orElse(null);
		selectedSlayerType = null;
		scrollTarget = 0f;
		closeSearchIfOpen();
	}

	/** Per user request: search should reset (query cleared, box closed) the moment the player navigates
	 *  away from it — switching category/subcategory — rather than staying populated with a stale query
	 *  that no longer matches what's now showing. Deliberately NOT called for opening a result's own
	 *  settings (cog or row click), toggling a feature, or scrolling — see those call sites' own comments;
	 *  acting on a search result (or scrolling to reach it) isn't "leaving" it. */
	private void closeSearchIfOpen() {
		if (!searchOpen && searchQuery.isEmpty()) return;
		searchOpen = false;
		searchQuery = "";
		if (textFocus == TextFocus.SEARCH) textFocus = TextFocus.NONE;
		searchAnim.setTarget(0f);
	}

	private float expandedContentHeight(Feature feature) {
		if (feature instanceof com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror mirror) feature = mirror.getTarget();
		if (feature instanceof GuiColorFeature gcf) {
			int chromaHeight = gcf.isChromaEnabled() ? 18 + 20 * 2 : 18;
			return 18 /* Accent Presets row */ + 10 + COLOR_CONTENT_HEIGHT + 8 + chromaHeight;
		}
		if (feature instanceof GuiAnimationsFeature) return ANIM_CONTENT_HEIGHT;
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.WitherHighlightFeature) {
			MobHighlightFeature mhf = (MobHighlightFeature) feature;
			boolean full = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_2D;
			// is3D gates the shared Occlusion subtoggle (both 3D modes); isFull3D gates the fill opacity/color
			// controls specifically (FULL_3D only — WIRE_3D has no fill, same split as OUTLINE_2D/FULL_2D).
			boolean is3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.WIRE_3D || mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			boolean isFull3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			int customImageHeight = full ? 18 + 8 + 20 + (mhf.getCustomImagePath() != null ? 6 + 20 + 8 + 20 : 0) : 0;
			// Same generic MobHighlightFeature layout, plus 4 extra labeled color pickers for the per-phase
			// (Maxor/Storm/Goldor/Necron) colors this module's own remake added.
			return 4 + 18 + 20 + (is3D ? 18 : 0) + 18 + 18 + (mhf.isTracersEnabled() ? 40 : 0)
				+ ((full || isFull3D) ? 20 : 0) + (full ? 18 : 0) + customImageHeight
				+ 4 + 10 + COLOR_CONTENT_HEIGHT + ((full || isFull3D) ? 8 + 10 + COLOR_CONTENT_HEIGHT : 0)
				+ (8 + 10 + COLOR_CONTENT_HEIGHT) * 4
				+ ((full || isFull3D) ? (8 + 10 + COLOR_CONTENT_HEIGHT) * 4 : 0);
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.StarredMobHighlightFeature) {
			MobHighlightFeature mhf = (MobHighlightFeature) feature;
			boolean full = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_2D;
			boolean is3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.WIRE_3D || mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			boolean isFull3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			int customImageHeight = full ? 18 + 8 + 20 + (mhf.getCustomImagePath() != null ? 6 + 20 + 8 + 20 : 0) : 0;
			// Same generic MobHighlightFeature layout, plus the 2 extra toggles (hideNonStarredNames/
			// hideStarredNames) this subclass draws — see the matching instanceof branch in
			// drawExpandedSettings for why those were never reachable at all before this round. A third
			// toggle (teammateClassGlow) used to be drawn here too — removed entirely (per user report — "there
			// is a 'teammate class color' in the starred mob esp? That has nothing to do with starred mobs"):
			// the class doc comment already admitted this was ported but never wired into any real nametag
			// rendering, so the toggle was a genuine no-op regardless of its setting.
			return 4 + 18 + 20 + (is3D ? 18 : 0) + 18 + 18 + (mhf.isTracersEnabled() ? 40 : 0)
				+ ((full || isFull3D) ? 20 : 0) + (full ? 18 : 0) + customImageHeight
				+ 4 + 10 + COLOR_CONTENT_HEIGHT + ((full || isFull3D) ? 8 + 10 + COLOR_CONTENT_HEIGHT : 0)
				+ 8 + 18 * 2;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HighlightPartyMembersFeature) {
			MobHighlightFeature mhf = (MobHighlightFeature) feature;
			boolean full = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_2D;
			boolean is3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.WIRE_3D || mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			boolean isFull3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			int customImageHeight = full ? 18 + 8 + 20 + (mhf.getCustomImagePath() != null ? 6 + 20 + 8 + 20 : 0) : 0;
			// Same generic MobHighlightFeature layout, plus the 2 extra toggles (Color by Role, and the linked
			// Hide Glow subtoggle) this subclass draws — see the matching instanceof branch in
			// drawExpandedSettings, and HighlightPartyMembersFeature's own class doc comment for why "Color by
			// Role" doesn't get real 3D support (it needs 5 simultaneous colors, which the shared
			// MobHighlightRegistry one-rule-one-color model can't express as a single Feature-owned rule).
			return 4 + 18 + 20 + (is3D ? 18 : 0) + 18 + 18 + (mhf.isTracersEnabled() ? 40 : 0)
				+ ((full || isFull3D) ? 20 : 0) + (full ? 18 : 0) + customImageHeight
				+ 4 + 10 + COLOR_CONTENT_HEIGHT + ((full || isFull3D) ? 8 + 10 + COLOR_CONTENT_HEIGHT : 0)
				+ 8 + 18 * 2;
		}
		if (feature instanceof MobHighlightFeature mhf) {
			boolean full = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_2D;
			// Settings first, color pickers last (per user request — see the matching reorder in
			// drawExpandedSettings). +18 for the Tracers toggle, +40 more (two slider rows) when Tracers is
			// on for its thickness/opacity controls. Custom image fill (per user request) adds a toggle +
			// "Select Image" button always, + "Clear Image" button and an "Image Fill Mode" cycle row only
			// once one is actually set (the preview thumbnail draws alongside the Select Image button, not
			// on its own row, so it needs no extra height). WIRE_3D/FULL_3D (real world-space box, see
			// World3DRenderer) share the Occlusion subtoggle; FULL_3D additionally reuses the flat fill
			// opacity/color from FULL_2D (WIRE_3D has no fill, so it skips those, same as OUTLINE_2D does).
			// Neither 3D mode gets the custom-image controls, which only make sense for a flat 2D rect.
			boolean is3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.WIRE_3D || mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			boolean isFull3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			int customImageHeight = full ? 18 + 8 + 20 + (mhf.getCustomImagePath() != null ? 6 + 20 + 8 + 20 : 0) : 0;
			// +18 for the shared "High Update Rate" toggle, drawn right after Tracers. LividFinderFeature (now
			// a MobHighlightFeature subclass — see that class's own doc comment) adds its own extra "Hide
			// Wrong Livids" toggle row at the end of the panel, same +18+6 the old dedicated branch used.
			return 4 + 18 + 20 + (is3D ? 18 : 0) + 18 + 18 + (mhf.isTracersEnabled() ? 40 : 0)
				+ ((full || isFull3D) ? 20 : 0) + (full ? 18 : 0) + customImageHeight
				+ 4 + 10 + COLOR_CONTENT_HEIGHT + ((full || isFull3D) ? 8 + 10 + COLOR_CONTENT_HEIGHT : 0)
				+ (mhf instanceof LividFinderFeature ? 18 + 6 + 18 : 0);
		}
		if (feature instanceof CustomEnchantParsingFeature cef) {
			// Round 2 (task tracker #589 — per-tier color/bold/italic overrides): the flat 30 covering just
			// the add-rule button's own row grew to also cover the new "Tier Overrides" section drawn right
			// after it — 28 (button height + the gap drawEnchantParsingContent now leaves before the tier
			// section) + 12 (that section's own label) + one 18px row per tier override.
			return 24 /* header hint text */ + cef.getRules().size() * 18 + 28 + 12 + cef.getTierOverrides().length * 18;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf) {
			return 4 + 18 * 5 + 4 + 20 + 8 + 10 + (COLOR_CONTENT_HEIGHT + 8) /* 5 toggles (added Depth Check, task #602) + Wall Height slider + box color label/picker */
				+ 18 + 8 /* config export/import row + padding */
				+ pmf.getMessages().size() * 74 /* 4 rows (text+preset / classes+section+bosspart / xyz+range / use-current button) + gaps per message */
				+ 30 /* add-message button row */;
		}
		if (feature instanceof ItemPickupLogFeature) {
			return 18 * 2 + 20 + 16 /* two toggles + one slider + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatCopyFeature) {
			return 18 + 6 /* one combo-capture row + padding — shorter than a multi-row panel needs */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.RareRewardWarningFeature) {
			return 18 + 6 /* one combo-capture row + padding — shorter than a multi-row panel needs */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ModInformationFeature) {
			return modInformationPatchNotesHeight();
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.GyroHelperFeature) {
			return 4 + 18 * 2 + 20 + 8 + (10 + COLOR_CONTENT_HEIGHT) * 2 + 8 /* 2 toggles + slider + 2 color pickers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.LavaToWaterFeature ltwf) {
			int tintColorRows = ltwf.isColorTint() ? (10 + COLOR_CONTENT_HEIGHT + 8) : 0;
			return 18 * 2 + 8 + tintColorRows + 12 /* color-tint/hide-fog toggles + conditional tint color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MelodyDisplayFeature) {
			return 20 + 18 + 8 + 10 + COLOR_CONTENT_HEIGHT + 8 + 18 + 12 /* alert-duration slider + sound
				toggle, then gap + color label + full color picker, then gap + broadcast toggle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.SimonSaysFeature) {
			return 18 * 5 + 20 + 18 + 8 + (10 + COLOR_CONTENT_HEIGHT) * 3 + 8
				/* 5 toggles (skip-compat/progress-display/announce/block-wrong-clicks/block-wrong-start)
				 * + max-start-clicks slider + style cycle row + 3 color pickers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.EntityRenderDistanceFeature) {
			return 20 + 18 + 12 /* one slider + one toggle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CameraFeature) {
			return 18 + 20 + 12 /* one toggle + one slider + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatDeclutterFeature) {
			// "Hide Oruo Messages" moved to Dungeon De-clutter (dungeon-specific voice-line spam) — back
			// down from sixteen to fifteen rows, keeping this in sync with the drawToggleRow calls above.
			return 18 * 15 + 16 /* fifteen toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DisableEndermanDeathAnimationFeature) {
			return 18 * 2 + 12 /* two toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature) {
			// Real bug found (per user report — "some places like the item animations module settings doesnt
			// fit all the settings in the pane"): drawSliderRow actually consumes 23px per row (see its own
			// `return y + 23`), not the 20px this formula assumed — an 8-slider panel like this one fell 24px
			// short of the space it actually needed, cutting the last row or two off. drawToggleRow's real 18px
			// was already correct.
			return 23 * 8 + 18 * 4 + 16 /* eight sliders (incl. swing speed) + four toggles (incl. In Place
				Swing Animation) + padding */;
		}
		if (feature instanceof InstanceChestProfitFeature) {
			return 18 + 6 /* one toggle + padding — shorter than a multi-row panel needs */;
		}
		if (feature instanceof CroesusFeature) {
			return (10 + COLOR_CONTENT_HEIGHT + 8) * 2 + 18 * 2 + 12 /* two labeled full color pickers + two toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.SecretsCounterFeature) {
			return 18 * 2 + 8 + 10 + COLOR_CONTENT_HEIGHT + 12 /* two toggles + labeled full color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.NetworkDisplayFeature) {
			return 18 * 4 + (8 + 10 + COLOR_CONTENT_HEIGHT) * 2 + 12 /* four toggles (adds bold/italic) + two labeled full color pickers (ping/tps split) + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.BossBarFeature) {
			return 18 * 2 + 8 + 10 + COLOR_CONTENT_HEIGHT + 12 /* two toggles + labeled full color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HideArrowsFeature) {
			return 18 + 6 /* one toggle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ItemRarityBackgroundFeature) {
			return 18 + 6 /* one shape cycle row + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature) {
			return 18 * 4 + 24 + 8
				+ (11 + NOTIF_ROW_HEIGHT + 3 + NOTIF_TYPE_GAP) * com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType.values().length
				+ (NOTIF_ROW_HEIGHT + 3) * 3 + 8
				/* show title + play sound toggles + gap + (enable toggle + title row, pulled 7px tighter per
				   user report — see the matching -7 in drawExpandedSettings, + NOTIF_TYPE_GAP breathing room
				   after the group) per notification type (each with its own color swatch popup now, so no
				   separate global title-color picker) + party-message row for the two score-threshold types
				   (the old repeat-reminders toggle for Ultimate Ready is gone — always collapsed now, no
				   opt-in setting left to draw a row for) + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatCommandsFeature) {
			return 4 + 27 * 18 + 3 * 6 + 8 /* 27 command/channel toggles across 4 groups + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.InvincibilityTimerFeature) {
			return 4 + 8 * 18 + 6 + 8 /* 3 item toggles + 5 toggles + one gap + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MelodyMessageFeature) {
			return 18 + 18 + 6 + 18 + 12 + 18 + 8 /* open toggle + open field + gap + progress toggle + label + progress field + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MageBeamFeature) {
			return 10 + (COLOR_CONTENT_HEIGHT + 8) + 18 * 4 + 8 /* labeled color picker + opacity/thickness sliders + hide-particles/depth-check toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.SlotBindsFeature) {
			// Settings before colors, per user request (see the matching reorder in drawExpandedSettings).
			return 18 + 8 + 18 * 2 + 4 + (10 + COLOR_CONTENT_HEIGHT) + 8 + (10 + COLOR_CONTENT_HEIGHT) + 12
				/* bind-key row + display-mode/profile cycle rows + two labeled color pickers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.EtherwarpFeature ewf) {
			int soundRows = ewf.isSuccessSoundEnabled() ? expandedSoundOptionHeight(ewf.getSuccessSound()) + 4 : 0;
			return (10 + COLOR_CONTENT_HEIGHT + 8) * 2 + 18 * 4 + soundRows + 8
				/* labeled succeed/fail color pickers + style cycle + three toggles + conditional sound option block + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MimicFeature) {
			return 18 * 4 + 8 + 10 + COLOR_CONTENT_HEIGHT + 12
				/* mimic/prince/bat message toggles + show-chest toggle + labeled color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature) {
			return 18 * 2 + 16 + 12 /* non-obsidian toggle + rare-drops toggle + threshold text field + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowsDeviceFeature adf2) {
			int predictionColorRow = adf2.isShowPrediction() ? (10 + COLOR_CONTENT_HEIGHT + 8) : 0;
			return 18 * 3 + 8 + (10 + COLOR_CONTENT_HEIGHT + 8) * 2 + predictionColorRow + 20 + 12
				/* alert/show-emerald/show-prediction toggles + gap + marked/target labeled color pickers +
				   conditional prediction color picker + emerald opacity slider + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BeamsSolverFeature bsf) {
			// Per user request ("It should not show any lines from the start"): the always-on "Show Tracer"
			// toggle for every pair is gone — only the hit-triggered tracer/highlight toggles remain — one
			// fewer row than before.
			int soundRows = bsf.isHitSoundEnabled() ? expandedSoundOptionHeight(bsf.getHitSound()) + 4 : 0;
			return 18 * 4 + 20 + soundRows + 12
				/* style cycle + alpha slider + hit-sound toggle + conditional sound option block + hit-highlight/tracer toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BlazeSolverFeature) {
			// +1 row (per user request, "Add 3d rendering support to the blaze solver aswell"): a conditional
			// Occlusion toggle only shown in the two 3D render modes — accounted for unconditionally here
			// (max-possible-height estimate), same convention every other conditional-row module in this
			// panel already uses. +1 more row + 1 more labeled color picker (per later user request, "three
			// color selectors, one for current, one for next, one for the rest... allow users to turn off the
			// last one") for the "Show Rest of the Blazes" toggle and its conditional third color picker —
			// same max-possible-height convention.
			return 18 * 6 + 20 + (10 + COLOR_CONTENT_HEIGHT + 8) * 3 + 12
				/* draw-line toggle + line-count slider + render-mode cycle + occlusion toggle + show-rest toggle + 3 labeled color pickers (current/next/rest) + send-complete toggle + hide-blazes toggle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BoulderSolverFeature) {
			return 18 * 3 + (10 + COLOR_CONTENT_HEIGHT + 8) + 12 /* show-all toggle + style cycle + occlusion toggle + labeled color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.TicTacToeSolverFeature) {
			return 18 * 2 + (10 + COLOR_CONTENT_HEIGHT + 8) * 2 + 12
				/* predict-next/send-message toggles + labeled best-move/predicted-move color pickers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.IceFillSolverFeature) {
			return 18 + 4 + (10 + COLOR_CONTENT_HEIGHT) + 12 /* optimized toggle + labeled color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.QuizSolverFeature) {
			return 18 + 4 + (10 + COLOR_CONTENT_HEIGHT) + 12 /* depth-check toggle + labeled color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.TPMazeSolverFeature) {
			return (10 + COLOR_CONTENT_HEIGHT + 8) * 3 + 12 /* 3 labeled color pickers + padding (tracer removed per user request) */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.WaterSolverFeature) {
			return 18 * 2 + (10 + COLOR_CONTENT_HEIGHT + 8) * 2 + 12 /* show-tracer + optimized toggles + 2 labeled color pickers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.WeirdosSolverFeature) {
			return 18 + 4 + (10 + COLOR_CONTENT_HEIGHT) + 8 + (10 + COLOR_CONTENT_HEIGHT) + 12
				/* style cycle + 2 labeled color pickers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PlayerDisplayFeature) {
			// Per user request ("Speed percentage can be a part of Player Display. Allow users to turn off
			// specific elements") — 5 new per-element show toggles (health/defense/mana/overflow/speed,
			// folded in from the deleted SpeedPercentageFeature) plus that module's own labeled color picker.
			// Plus Show Vitality (task: "add a vitality display to the player display").
			// Plus Show Skill XP (task: "the skill calculator... make it movable and show with player display").
			return 18 * 12 + (10 + COLOR_CONTENT_HEIGHT + 8) + 12
				/* show-icons + 4 hide-vanilla toggles + 6 show-element toggles + speed color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DoorHighlightFeature) {
			return (10 + COLOR_CONTENT_HEIGHT + 8) * 4 + 12 /* 4 labeled color pickers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.BloodCampFeature bcfHeight) {
			boolean bcfIs3D = bcfHeight.getRenderMode() == MobHighlightFeature.RenderMode.WIRE_3D
				|| bcfHeight.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			return 18 * 8 + (bcfIs3D ? 18 : 0) + 20 * 4 + (10 + COLOR_CONTENT_HEIGHT + 8) * 3 + 20
				/* 7 toggles + render-mode cycle row (+1 Occlusion toggle only in a 3D mode) + 4 sliders
				 * (box size/assume-tick/offset/manual-offset) + 3 labeled color pickers + padding */;
		}
		if (feature instanceof ActiveHotfPerksHighlightFeature || feature instanceof DnaAnalyzerSolverFeature) {
			return 10 + COLOR_CONTENT_HEIGHT + 12 /* labeled full color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HideDamageSplashesFeature) {
			return 18 * 2 + 12 /* two toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DamageTruncatorFeature) {
			return 18 + 8 /* decimals slider + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HideFireFeature) {
			return 18 + 6 /* one toggle + padding — shorter than a multi-row panel needs */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HideNametagsFeature) {
			return 18 + 6 /* one toggle (Only In Dungeons) + padding */;
		}
		if (feature instanceof DungeonClickedBlocksFeature) {
			return 18 * 6 + (10 + COLOR_CONTENT_HEIGHT + 8) * 4 + 16
				/* four toggles + style cycle + opacity slider + four labeled color pickers + padding */;
		}
		if (feature instanceof MoneyPerHourFeature) {
			return 18 + 12 /* one cycle row + padding */;
		}
		if (feature instanceof PestCooldownFeature) {
			return 18 + 20 + 12 /* one toggle + one slider + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HidePestDropsFeature) {
			return 18 + 6 /* one toggle + padding — shorter than a multi-row panel needs */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsMasterFeature) {
			return 20 + 12 /* one slider + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsHoldingOnlyFeature) {
			return 18 * 3 + 12 /* three toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ExperimentAddonsFeature) {
			return (10 + COLOR_CONTENT_HEIGHT + 8) * 2 + 12 /* two labeled full (hex-capable) color pickers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MoongladeBeaconAlertFeature) {
			return 18 * 2 + 12 /* one toggle + one color row + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.TerminalSoundsFeature tsf) {
			// Each sound option block only renders while its own toggle is on (see the toggle-row block below).
			int clickSoundRows = tsf.isClickSounds() ? expandedSoundOptionHeight(tsf.getClickSound()) + 4 : 0;
			int completeSoundRows = tsf.isCompleteSounds() ? expandedSoundOptionHeight(tsf.getCompleteSound()) + 4 : 0;
			return 18 * 2 + clickSoundRows + completeSoundRows + 12 /* two toggles + up to two conditional sound option blocks + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature tsf) {
			// GUI Scale row only renders while Custom Terminal GUI is on (see the toggle-row block above) —
			// its 18px must drop out of the height here too, or the panel would reserve dead space below it.
			int guiScaleRow = tsf.isCustomTerminalGui() ? 18 : 0;
			return 18 * 4 + guiScaleRow + 20 + 4 + (10 + COLOR_CONTENT_HEIGHT + 8) + 12 /* four toggles + optional gui scale cycle + one slider + one labeled full color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature lmf) {
			return 18 + 18 * 4 + 20 + 8 + 20 + 8 + 10 + COLOR_CONTENT_HEIGHT + 8 + lmf.getKeybinds().size() * (float) SLOT_ROW_HEIGHT + 12
				/* cycle + four toggles (incl. Sync with Map) + render scale slider + gap + background opacity slider
				   + gap + color label + full color picker + gap + four keybind rows + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonQueueFeature dqf) {
			// Announce Kick toggle + Auto Requeue toggle, then (only while Auto Requeue is on, same conditional-
			// row pattern DungeonMapFeature's outline/background toggles below already use) the Requeue Delay
			// slider + Disable on leave/kick toggle.
			int autoRequeueRows = dqf.isAutoRequeue() ? 18 + 18 : 0;
			return 4 + 18 + 18 + autoRequeueRows + 12;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature dmf) {
			int outlineRows = dmf.isOutlineEnabled() ? 20 + 8 + 10 + COLOR_CONTENT_HEIGHT + 8 : 0;
			int backgroundRows = dmf.isBackgroundEnabled() ? 20 + 8 + 10 + COLOR_CONTENT_HEIGHT + 8 : 0;
			int mapInfoRows = dmf.isMapInfoEnabled() ? 18 * 3 + 8 + 10 + COLOR_CONTENT_HEIGHT : 0;
			return 4 + 18 + outlineRows + 18 + backgroundRows + 18 + 18 + 18 + 20 + 20 + 18 + mapInfoRows + 12
				/* outline toggle (+conditional thickness/color) + background toggle (+conditional opacity/color)
				   + player heads toggle + show-in-boss toggle + reveal-unexplored-rooms toggle + show-names
				   cycle + room-label cycle + map-info toggle (+conditional crypts/deaths/mimic toggles + text
				   color picker) + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonScoreCalculatorFeature) {
			return 18 * 2 + 20 + 12 /* two toggles + one keybind-less info note row + padding, see its own content drawer */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature cef) {
			// Kept in exact row-for-row sync with the drawExpandedSettings branch below — every constant here
			// mirrors that branch's own rowY increments so the panel never clips or over-allocates. "loaded"
			// mirrors that branch's own null-check on stats()/computeResult() for the result block only — the
			// level line, ring toggle, and Derpy/Hecatomb lines above it are now fixed-height regardless of
			// load state (see that branch's own doc comment on why those no longer need to wait on stats()).
			boolean loaded = cef.stats() != null && cef.computeResult() != null;
			int headerBlock = 14 /* level line (loaded or loading) */ + 18 /* ring toggle */ + 14 /* derpy line */ + 14 /* hecatomb line */ + 4 /* gap */;
			int earlyBoostBlock = cef.isIncludeEarlyBoost() ? 38 : 0 /* run length label + minutes/seconds field row + gap */;
			int resultBlock = loaded ? 13 * 4 : 13 /* bonus/xp-needed/xp-per-run/runs-needed lines, or a single waiting line */;
			return headerBlock + 34 /* Bonzo's Shard label + field + gap */ + 24 /* Floor cycle row + gap */
				+ 34 /* Target Level label + field + gap */ + 18 /* early-boost toggle */ + earlyBoostBlock
				+ 6 /* gap before result block */ + resultBlock + 12;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonTimersFeature dtf) {
			int goldorStyleRow = dtf.isGoldorLoopEnabled() ? 18 : 0;
			return 18 * 9 + goldorStyleRow + 12
				/* storm pad / terminals / goldor-start / goldor-loop / necron-warning / maxor-crystal / bold-text /
				   italic-text / storm-lightning-sync toggles + conditional goldor-style cycle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf) {
			int itemCount = dcf.getItemsForClass(selectedCopilotClass).size();
			// Title Update Sound's row block replaces what used to be a single fixed 18px cycle row (never
			// itself accounted for in COPILOT_HEADER_HEIGHT) with the full CustomSoundOption row set — sized
			// here so enabling it doesn't overflow the panel's scroll bounds.
			int soundRows = dcf.isTitleUpdateSoundEnabled() ? expandedSoundOptionHeight(dcf.getTitleUpdateSound()) : 0;
			return COPILOT_HEADER_HEIGHT + soundRows + itemCount * COPILOT_ITEM_HEIGHT + 12
				/* class picker row + enable toggle + add-step button + one block per step item + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf) {
			if (selectedRouteRoomName == null) {
				// Real bug found (per user report — "The list of rooms doesnt go down long enough to see
				// all"): this used panelWidth - 20 as its own estimate of the row's real drawable width, but
				// the ACTUAL width drawDungeonRoutesContent receives (and wraps tiles against) is
				// panelWidth - SIDEBAR_WIDTH - 16 — the sidebar's own 140px eats a big chunk of the panel
				// that this estimate never accounted for. Assuming a much WIDER row than actually exists
				// meant this estimate fit more tiles per row than the real draw does, which UNDERcounted the
				// real number of wrapped rows needed — and therefore the real total content height — capping
				// how far the panel could ever scroll well short of the actual bottom row.
				int rowWidth = Math.max(1, panelWidth - SIDEBAR_WIDTH - 16);
				int perRow = Math.max(1, (rowWidth + ROUTE_ROOM_TILE_GAP) / (ROUTE_ROOM_TILE_WIDTH + ROUTE_ROOM_TILE_GAP));
				// Per user request ("Allow users to search for a room") — matches the filtered count
				// drawDungeonRoutesContent actually wraps tiles against, not the full room list, so an active
				// search doesn't leave stale extra scroll height behind for rooms no longer shown.
				String query = routeRoomSearchQuery.toLowerCase(Locale.ROOT);
				long roomCount = drf.getAllRoomNames().stream().filter(name -> query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query)).count();
				int rows = (int) ((roomCount + perRow - 1) / perRow);
				return ROUTE_GRID_HEADER_HEIGHT + rows * (ROUTE_ROOM_TILE_HEIGHT + ROUTE_ROOM_TILE_GAP) + 12
					/* waypoint toggles + search box + wrapped room-button grid + padding */;
			}
			int itemCount = drf.getItemsForRoom(selectedRouteRoomName).size();
			return ROUTE_EDITOR_HEADER_HEIGHT + itemCount * ROUTE_ITEM_HEIGHT + 12
				/* back row + add-step button + one block per step item + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ChestRollingFeature crfHeight) {
			int obsidianRow = crfHeight.isOnlyBedrock() ? 18 : 0;
			// Per user request (Hide Lore, moved from CroesusFeature): "hide lore on ALL chests" only shows as
			// a sub-option once Hide Lore itself is on, same conditional-row pattern onlyBedrock/obsidianRow
			// above already uses.
			int hideLoreAllRow = crfHeight.isHideLore() ? 18 : 0;
			// Per user request ("Add the new sound options for the chest rolling aswell. Also add a way to
			// disable the sound"): the landing-sound toggle always shows, and the full CustomSoundOption block
			// (built-in/import/volume/pitch rows) only expands once it's actually enabled — same conditional-
			// expansion pattern hideLoreAllRow above already uses.
			int landSoundRows = 18 + (crfHeight.isLandSoundEnabled() ? 4 + expandedSoundOptionHeight(crfHeight.getLandSound()) : 0);
			return 18 * 4 + obsidianRow + hideLoreAllRow + 20 + landSoundRows + 12
				/* only-bedrock (+ obsidian-lower-floors) + only-croesus + obfuscate-rare-items + hide-lore
				   (+ hide-lore-all-chests) toggles + duration slider + landing-sound toggle (+ full sound
				   option block) + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature caf) {
			return ALIAS_HEADER_HEIGHT + caf.getAliases().size() * ALIAS_ROW_HEIGHT + 12;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature csf) {
			return SHORTCUT_HEADER_HEIGHT + csf.getShortcuts().size() * SHORTCUT_ROW_HEIGHT + 12;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.InactiveWaypointsFeature) {
			return 18 * 8 + 8 + 10 + COLOR_CONTENT_HEIGHT + 12
				/* show terminals/devices/levers + show titles + show block highlight + hide default names
				   + sync with class + terminal-hitbox-as-highlight toggles, then gap + color label + full
				   color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature) {
			// Per "Hide Oruo Messages" moving here from Chat De-clutter: bumped from eighteen to nineteen
			// rows, keeping this in sync with the drawToggleRow calls in drawDungeonDeclutterContent.
			return 18 * 19 + 12 /* ten hide-object subtoggles + skull/milestone/ability message removers
				+ solo class stats/potion reminder/dungeonbreaker/mob/teleport/oruo message removers + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.LeapCounterFeature) {
			return 18 * 6 + 8 + 10 + COLOR_CONTENT_HEIGHT + 12 /* one enable toggle per zone, then gap + color
				label + full color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature vwf) {
			return 14 + (16 + 4) * vwf.getReplacements().size() + 8 + 16 + 12 /* label + rows + add button + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.SplitsFeature spf) {
			int outlineRows = spf.isOutlineEnabled() ? 20 + 8 + 10 + COLOR_CONTENT_HEIGHT + 8 : 0;
			return 18 * 5 + outlineRows + 8 + 10 + COLOR_CONTENT_HEIGHT + 12
				/* fixed width + boss entry split + send splits to chat + split location cycle + outline
				   toggles, then optional thickness slider + labeled outline color picker, then background
				   color label + full color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.AuctionHouseTotalFeature) {
			return 18 + 12 /* one toggle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.AuctionTimersFeature) {
			return 18 * 2 + 8 + 10 + COLOR_CONTENT_HEIGHT + 12
				/* bold/italic toggles, then gap + color label + full color picker + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ExperimentationTimersFeature etf) {
			// Per user request ("Add the sound support like we have... to the new timer modules"): the
			// sound-option block only takes up space when Notify Sound is on, mirroring the same
			// isSuccessSoundEnabled conditional-height pattern EtherwarpFeature uses elsewhere in this method.
			int soundRows = etf.isNotifySound() ? expandedSoundOptionHeight(etf.getSound()) + 4 : 0;
			return 18 * 4 + soundRows + 12 /* notify-only-at-3/notify-chat/notify-sound/notify-title toggles + sound block + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowAlignFeature) {
			return 18 + 12 /* one toggle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptfHeight) {
			// Per user request (GuiColorFeature removal — see its own doc comment at the render call site):
			// the accent presets/picker/chroma block is now unconditional, not Custom-only — keep this in sync
			// with that render code's exact row order.
			boolean chromaOn = FeatureRegistry.get("gui_color") instanceof com.cokelord.skyblocksimplified.feature.impl.GuiColorFeature gcfHeight
				&& gcfHeight.isChromaEnabled();
			// theme cycle row, then gap + accent presets row, then gap + accent color label/picker, then gap +
			// chroma toggle (+ 2 chroma sliders if enabled).
			int accentBlock = 18 + 4 + 18 + 8 + 10 + COLOR_CONTENT_HEIGHT + 8 + 18 + (chromaOn ? 18 * 2 : 0);
			if (ptfHeight.getMode() == com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature.Mode.CUSTOM) {
				// + gap + opacity slider + "Apply Gradient to Rows & Buttons" toggle rows, then the gradient's
				// own Chroma toggle (+ 1 speed slider if enabled), then gap + gradient editor (square widget +
				// mode cycle + pos sliders + pin swatches + full picker — see gradientPinEditorHeight()).
				int gradientChromaRows = 18 + (ptfHeight.isCustomChromaEnabled() ? 18 : 0);
				return accentBlock + 8 + 18 * 2 + gradientChromaRows + 12 + gradientPinEditorHeight() + 12;
			}
			return accentBlock + 12;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature fif) {
			// Import/Reset buttons row + scope cycle row + padding, plus one extra row of space for an
			// error message whenever the last import attempt failed (kept out of the layout otherwise so
			// the common "everything's fine" case doesn't waste vertical space).
			return 18 * 2 + 12 + (fif.getLastError() != null ? 14 : 0);
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.AutoKickFeature) {
			// Per historical task note ("AutoKick system settings UI still unfinished") — wires the cog
			// settings this feature's own logic already fully supported, just never had a panel to edit from.
			return 20 * 2 + 18 * 4 + 12 /* min-PB-minutes slider + min-PB-seconds slider + 4 require-X toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PartyFinderFeature) {
			// Per user request ("allow users to turn off certain options in the party finder module"): one
			// toggle row per lore line the hover tooltip can show.
			return 18 * 12 + 12 /* 12 show-X toggles + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature) {
			// Real bug found (per user report — "the module settings for the storage overlay isnt tall enough
			// to fit the size selector, so the 'show background' text and toggle cut off"): the "Size" cycle
			// row added alongside sizeScale was never accounted for here, so the panel stayed sized for only
			// 3 rows while 4 are actually drawn (columns slider + size cycle + retain-scroll + show-background).
			return 20 + 18 + 18 + 18 + 18 + 12 /* columns slider + size cycle + retain-scroll toggle + show-background toggle + position cycle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.LoadoutOverlayFeature) {
			return 20 + 18 + 18 + 12 /* columns slider + size cycle + show-background toggle + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature) {
			return 18 * 4 + 12 /* one real throttle row + three quick-access mirror rows + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomLoadoutKeybindsFeature clkf) {
			int colorRow = clkf.isHighlightSelectedLoadout() ? (10 + COLOR_CONTENT_HEIGHT + 8) : 0;
			return 18 + colorRow + 8 + clkf.getLoadoutSlotCount() * 18 + 12
				/* highlight toggle + conditional color picker + gap + one combo row per loadout slot + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PetKeybindsFeature pkf) {
			return pkf.getPetSlotCount() * 18 + 12 /* one combo row per pet slot + padding */;
		}
		if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowHitSoundFeature ahsf) {
			return expandedSoundOptionHeight(ahsf.getSound()) + 12 /* sound option block + padding */;
		}
		return feature.getKeybinds().size() * (float) SLOT_ROW_HEIGHT + EXPAND_PADDING;
	}

	/** Real bug found (per user report — "the squircle corners are still unsmooth"): this used to be its own
	 *  separate, older scanline-inset approximation (a per-row circular inset, no anti-aliasing, no squircle
	 *  support at all) — completely independent of {@link RenderUtil#fillRounded}, which already has real
	 *  supersampled anti-aliasing AND the squircle-corners opt-in shape. Because every plain unqualified
	 *  {@code fillRounded(...)} call in this file (the vast majority of this whole menu's chrome — panels,
	 *  buttons, rows, tiles) resolves to THIS method rather than the static {@code RenderUtil} one, none of
	 *  that work was actually visible anywhere except the handful of call sites that explicitly qualified
	 *  {@code RenderUtil.fillRounded(...)}. Now just delegates, so every corner in the menu gets both the
	 *  anti-aliasing and the squircle toggle for free. */
	private void fillRounded(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color,
							  boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight) {
		RenderUtil.fillRounded(graphics, x0, y0, x1, y1, radius, color, roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight);
	}

	private void fillRounded(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
		fillRounded(graphics, x0, y0, x1, y1, radius, color, true, true, true, true);
	}

	// Per later user request ("remove the gradients I had you add, since we are now making squircles smooth
	// and whatnot the menu doesn't really need gradients"): these three overloads used to draw a real
	// top-to-bottom gradient (see prior round's history) across the mod's structural chrome (main panel,
	// header, feature rows, expanded settings panel background). Kept as thin delegates to the flat
	// fillRounded so every existing call site keeps compiling unchanged, but the gradient itself is gone —
	// bottomColor/darken amount are simply ignored now.
	private void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int topColor, int bottomColor,
									  boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight) {
		fillRounded(graphics, x0, y0, x1, y1, radius, topColor, roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight);
	}

	private void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color,
									  boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight) {
		fillRounded(graphics, x0, y0, x1, y1, radius, color, roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight);
	}

	private void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int color) {
		fillRounded(graphics, x0, y0, x1, y1, radius, color, true, true, true, true);
	}

	/** Per user request ("the gradient doesnt apply to stuff like module settings, subcategory buttons, the
	 *  top of the menu, text fields and the update button. All stuff should match the gradient except text,
	 *  strongly depending on what they are and therefore some areas should be darker, kind of like liquid
	 *  glass on the iphone"): a shared choke point for extending the Custom theme's live gradient to chrome
	 *  elements BEYOND the header/sidebar/content panel fills themselves. When the gradient isn't active this
	 *  behaves exactly like the flat-color {@code fillRoundedGradient} the caller used before (zero visual
	 *  change for Black/White/Transparent). When it IS active, the element samples the exact same gradient —
	 *  continuous across the full panel's own X span so nested elements read as real cut-outs of the panel
	 *  behind them, not each restarting its own 0..1 sweep — optionally darkened first for the "liquid glass"
	 *  recessed look (search boxes, settings panels, text fields) versus left at full brightness for raised
	 *  surfaces (the header strip, matching the sidebar/content's own brightness). */
	private void fillPanelBackgroundOrGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int flatColor,
												boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight,
												float darkenAmount) {
		if (Theme.isPanelGradientActive()) {
			int c1 = Theme.panelBackground(), c2 = Theme.panelGradientColor2();
			if (darkenAmount > 0f) {
				c1 = RenderUtil.darken(c1, darkenAmount);
				c2 = RenderUtil.darken(c2, darkenAmount);
			}
			RenderUtil.fillPanelGradient(graphics, x0, y0, x1, y1, radius, c1, c2,
				Theme.panelGradientPos1(), Theme.panelGradientPos2(), Theme.panelGradientMode(), Theme.panelGradientAngle(),
				roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight, panelX, panelX + panelWidth);
		} else {
			fillRoundedGradient(graphics, x0, y0, x1, y1, radius, flatColor, roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight);
		}
	}

	/** Convenience overload — all four corners rounded, matching {@link #fillRoundedGradient}'s own 6-arg
	 *  shape overload. */
	private void fillPanelBackgroundOrGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius, int flatColor, float darkenAmount) {
		fillPanelBackgroundOrGradient(graphics, x0, y0, x1, y1, radius, flatColor, true, true, true, true, darkenAmount);
	}

	/** The mod panel's own actual background fill (main panel/sidebar/content sections) — a flat {@link
	 *  Theme#panelBackground()} rounded rect normally, or the Custom theme's real two-pin gradient (see
	 *  {@link RenderUtil#fillPanelGradient}) once {@link Theme#isPanelGradientActive()}. Every one of this
	 *  method's own callers used to call the now-neutered {@code fillRoundedGradient(..., Theme.
	 *  panelBackground())} directly (a flat-color passthrough, per that method's own doc comment on the
	 *  earlier "remove the gradients" request) — this is the one new choke point that actually lets the
	 *  Custom theme's own gradient reach the real screen instead of that per-user-request stripped-out path. */
	private void fillPanelBackground(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius,
									  boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight) {
		fillPanelBackground(graphics, x0, y0, x1, y1, radius, roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight, x0, x1);
	}

	/** Same as the 9-arg overload, but lets a caller drawing only PART of the panel (the sidebar or content
	 *  section, each a narrower slice of the real full panel width) say what that full width actually is —
	 *  see {@link RenderUtil#fillPanelGradient}'s own {@code fullX0}/{@code fullX1} overload doc comment for
	 *  the real "each section re-traversed its own independent 0..1 gradient" bug this exists to fix. */
	private void fillPanelBackground(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int radius,
									  boolean roundTopLeft, boolean roundTopRight, boolean roundBottomLeft, boolean roundBottomRight,
									  int fullX0, int fullX1) {
		if (Theme.isPanelGradientActive()) {
			RenderUtil.fillPanelGradient(graphics, x0, y0, x1, y1, radius, Theme.panelBackground(), Theme.panelGradientColor2(),
				Theme.panelGradientPos1(), Theme.panelGradientPos2(), Theme.panelGradientMode(), Theme.panelGradientAngle(),
				roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight, fullX0, fullX1);
		} else {
			fillRounded(graphics, x0, y0, x1, y1, radius, Theme.panelBackground(), roundTopLeft, roundTopRight, roundBottomLeft, roundBottomRight);
		}
	}

	/** A true circle (standard row-by-row circle rasterization), not the corner-inset approximation
	 *  fillRounded uses — that one reads as slightly chunky/octagonal at small sizes like the close
	 *  button. Use this wherever something should look like an actual circle: close button, magnifier,
	 *  toggle knobs. */
	private void fillCircle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
		for (int dy = -radius; dy < radius; dy++) {
			double yMid = dy + 0.5;
			double halfWidth = Math.sqrt(Math.max(0.0, (double) radius * radius - yMid * yMid));
			int rowY = cy + dy;
			int x0 = (int) Math.round(cx - halfWidth);
			int x1 = (int) Math.round(cx + halfWidth);
			if (x1 > x0) graphics.fill(x0, rowY, x1, rowY + 1, color);
		}
	}

	private static Boolean hypixelModApiInstalled = null;

	/** Drawn unscaled (outside the panel's open/close animation transform) so it stays fully readable
	 *  the instant the menu opens, regardless of GUI Animations settings. Several features (currently
	 *  just island detection in IslandGate/HypixelLocationApi) prefer this companion Fabric mod's real
	 *  server-pushed data over chat/scoreboard scraping when it's present, but fall back cleanly when it
	 *  isn't — so this is a "you're missing out on more reliable detection" notice, not a hard failure. */
	private void drawHypixelModApiWarning(GuiGraphicsExtractor graphics) {
		if (hypixelModApiInstalled == null) {
			hypixelModApiInstalled = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("hypixel-mod-api");
		}
		if (hypixelModApiInstalled) return;

		// Per user report ("Hypixel Mod API warning needs to be a big, unmissable red-text banner above the
		// mod menu... a lot of players probably won't notice" a quiet single line): scaled up 1.6x and bolded,
		// with a bright solid-red background (not just a thin dark-red pill) and a warning glyph on both
		// sides, so it reads as a real alert rather than one more small status line easy to skim past.
		String text = "⚠ Hypixel Mod API is required for most features, please install it. ⚠";
		float scale = 1.6f;
		int textWidth = Math.round(this.font.width(text) * scale);
		int boxX0 = this.width / 2 - textWidth / 2 - 14;
		int boxX1 = this.width / 2 + textWidth / 2 + 14;
		int boxY0 = 6;
		int boxY1 = boxY0 + Math.round(this.font.lineHeight * scale) + 12;
		RenderUtil.fillRounded(graphics, boxX0, boxY0, boxX1, boxY1, 4, 0xFFAA0000);
		RenderUtil.fillRoundedRing(graphics, boxX0, boxY0, boxX1, boxY1, 4, 2, 0xFFFF5555);
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(this.width / 2f - textWidth / 2f, boxY0 + 6);
		pose.scale(scale);
		graphics.text(this.font, net.minecraft.network.chat.Component.literal(text)
			.withStyle(net.minecraft.network.chat.Style.EMPTY.withBold(true)), 0, 0, 0xFFFFFFFF);
		pose.popMatrix();
	}

	private void layoutAndDraw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
		currentFrameDt = dt;
		// Cached so the generic row-drawing helpers (drawToggleRow/drawSliderRow/etc.) can check their own
		// subtoggle tooltips without threading mouseX/mouseY through every one of their ~350 existing call
		// sites — those keep their original no-tooltip-param signature unchanged, only the new tooltip-aware
		// overloads read this.
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		updateComboCapture();
		categoryRows.clear();
		subTabRows.clear();
		slayerTypeRows.clear();
		featureRows.clear();
		inlineKeybindRows.clear();
		comboRows.clear();
		configRowVisible = false;
		slotRows.clear();
		sliderHits.clear();
		clickHits.clear();
		scrollbarHits.clear();
		tabButtonHits.clear();
		colorSquareHits.clear();
		hexFieldBounds.clear();
		hexFieldColors.clear();
		hexFieldSetters.clear();
		gradientDialActiveThisFrame = false;
		gradientPinActiveThisFrame = false;
		genericFieldBounds.clear();
		genericFieldGetters.clear();
		genericFieldSetters.clear();
		enchantNameBoxes.clear();
		enchantLevelBoxes.clear();
		posMsgFieldBoxes.clear();
		visualWordFindBoxes.clear();
		visualWordReplaceBoxes.clear();
		dungeonNotifTitleBoxes.clear();
		dungeonNotifPartyMsgBoxes.clear();
		copilotFieldBoxes.clear();
		copilotStepBoxHits.clear();
		routeFieldBoxes.clear();
		routeStepBoxHits.clear();
		aliasFieldBoxes.clear();
		shortcutCommandBoxes.clear();
		kismetThresholdBox = null;

		int accent = Theme.accent();

		// Real bug found (per repeated user report — "Fully rebuild mod-menu open animation: real sequential
		// slide-out per section, panel/background extending in phase, not just text"): this used to
		// unconditionally fill the ENTIRE panel rectangle here, every frame, before any of the three reveal
		// phases below had even started — so the full solid panel silhouette was already sitting on screen
		// from the very first frame, and the header/sidebar/content phases only ever grew scissor windows
		// that revealed TEXT/CONTENT drawn on top of that already-fully-present backdrop. That's exactly "not
		// the background, just the text" — the background itself never visibly extended with the sequence.
		// Deleted outright: the header (below), sidebar, and content sections below each now draw their OWN
		// background fill, individually gated behind that same section's own growing reveal scissor, so the
		// background genuinely grows in lockstep with each phase instead of pre-existing underneath it. The
		// three sections' fills exactly tile the panel rectangle with no gap/overlap (header spans the full
		// width across the top strip; sidebar+content together span the full width across the remaining
		// height), so nothing is lost by removing this single base fill — before the header phase starts,
		// the panel is now genuinely, visibly empty, not just textless.
		//
		// Real bug found (per user report — "the new thing that seperates the mod menu parts makes them have
		// gaps when closing the menu"): three independently-issued fills whose edges are only MEANT to align
		// exactly can develop a hairline seam once they're rendered through a non-1.0 uniform scale transform
		// (the closing shrink's own `currentScale`, applied via the pose stack around the panel's center) —
		// each fill's own edge coordinates round to the nearest device pixel independently, and at a
		// fractional scale those roundings don't always agree, unlike a single continuous quad which can't
		// develop a seam with itself. The opening reveal itself never scales (currentScale snaps to 1
		// immediately on open, see its own doc comment below), so this only ever showed up on the close. Once
		// the reveal has fully settled (every phase certain to be permanently at 1 for the rest of this menu
		// instance, opening OR closing), the base fill returns as a single continuous quad under the sidebar/
		// content section fills, exactly restoring the old seamless behavior there for the entire post-reveal
		// lifetime — while staying absent during the brief opening window so that still starts genuinely empty.
		//
		// Real bug found (per user report — "the top of the menu, the search part, is reversed. It starts
		// brighter then gets less bright when the animation is done"): this used to also cover the HEADER's
		// own rectangle, which made an earlier round gate the header's OWN fill (colorHeaderBg(), a
		// deliberately different, slightly brighter tint) off once this base fill activated, to avoid
		// double-blending them — but that just replaced "the header stays its own tint" with "the header's
		// tint visibly drops the moment the reveal finishes", which is exactly what got reported. The actually
		// correct fix is for the two fills to never overlap in the first place: this one now stops at
		// HEADER_HEIGHT, leaving the header's own always-on fill (below) as the ONLY thing ever covering that
		// strip — no double-blend possible since there's nothing underneath it to blend with, and no more
		// tint drop since it never has to turn off.
		if (menuOpenContentAnim.get() >= 0.999f) {
			fillPanelBackground(graphics, panelX, panelY + HEADER_HEIGHT, panelX + panelWidth, panelY + panelHeight, PANEL_RADIUS, false, false, true, true);
		}

		// Per user request ("Make the top part (the one with the search part) rise from the bottom"): the
		// header bar (background strip + magnifier + search box) is offset downward by however much of
		// menuOpenHeaderAnim is left to go, clipped to the header's own bounds so it visibly slides up into
		// place from below rather than overflowing into the content area beneath it. Once the phase settles
		// (headerRiseOffset reaches 0) this is a no-op translate — same cost as before this feature existed.
		int headerRiseOffset = Math.round((1f - menuOpenHeaderAnim.get()) * HEADER_HEIGHT);
		graphics.enableScissor(panelX, panelY, panelX + panelWidth, panelY + HEADER_HEIGHT);
		graphics.pose().pushMatrix();
		graphics.pose().translate(0, headerRiseOffset);

		// Real bug found (per user report — "the top of the menu, the search part, is reversed. It starts
		// brighter then gets less bright when the animation is done"): a previous round made this fill
		// conditional (skipped once the base fill below activates) to fix a whiteness double-blend — but the
		// base fill now stops at HEADER_HEIGHT (see its own doc comment above) and never overlaps this
		// rectangle at all, so there's nothing left to double-blend with. Back to unconditional/always-on,
		// which is also what fixes the reported bug: turning this OFF once the reveal settled was exactly
		// what made the header's own (slightly brighter) tint visibly drop away at that moment.
		fillPanelBackgroundOrGradient(graphics, panelX, panelY, panelX + panelWidth, panelY + HEADER_HEIGHT, PANEL_RADIUS, colorHeaderBg(),
			true, true, false, false, 0f);

		magnifierSize = 20;
		magnifierX = panelX + panelWidth - magnifierSize - 8;
		magnifierY = panelY + (HEADER_HEIGHT - magnifierSize) / 2;
		boolean magnifierHovered = mouseX >= magnifierX && mouseX <= magnifierX + magnifierSize && mouseY >= magnifierY && mouseY <= magnifierY + magnifierSize;
		magnifierHoverAnim.setTarget(magnifierHovered ? 1f : 0f);
		magnifierHoverAnim.update(dt);
		int magnifierCx = magnifierX + magnifierSize / 2;
		int magnifierCy = magnifierY + magnifierSize / 2;
		int magnifierColor = magnifierHoverAnim.get() > 0.001f
			? RenderUtil.darken(accent, magnifierHoverAnim.get() * HOVER_DARKEN_AMOUNT) : accent;
		drawIcon(graphics, MAGNIFIER_TEXTURE, magnifierCx - magnifierSize / 2, magnifierCy - magnifierSize / 2, magnifierSize, magnifierColor);

		// Real bug found (per user report — "The search bar also isnt extending from the bottom, it just kind
		// of fades in in the center of the screen"): searchAnim runs on its own independent timer (also used
		// for the normal magnifier-click open/close toggle, unrelated to the menu-open reveal), so on a fresh
		// open it grew to full width on its own schedule regardless of how far the header had actually risen
		// into view — usually finishing well before/after the header's own (now up to 1s) rise, reading as a
		// separate "pops in" grow instead of part of one rising motion. Multiplying by menuOpenHeaderAnim's own
		// progress caps the search box's visible width at however far the header has risen so far; once the
		// header settles at 1 (its permanent steady state for the rest of the menu's lifetime) this is a
		// no-op, so the normal magnifier toggle animation is completely unaffected outside this brief window.
		float searchT = searchAnim.get() * menuOpenHeaderAnim.get();
		searchBoxVisible = false;
		if (searchT > 0.01f) {
			int searchWidth = Math.round(SEARCH_FULL_WIDTH * searchT);
			int searchHeight = 16;
			int searchRight = magnifierX - 8;
			int searchX = searchRight - searchWidth;
			int searchY = panelY + (HEADER_HEIGHT - searchHeight) / 2;
			fillPanelBackgroundOrGradient(graphics, searchX, searchY, searchRight, searchY + searchHeight, BOX_RADIUS, colorSearchBg(), 0.15f);
			if (searchT > 0.4f) {
				searchBoxVisible = true;
				searchBoxX = searchX;
				searchBoxY = searchY;
				searchBoxWidth = searchWidth;
				searchBoxHeight = searchHeight;
				graphics.enableScissor(searchX, searchY, searchRight, searchY + searchHeight);
				boolean searchFocused = textFocus == TextFocus.SEARCH;
				if (searchFocused) drawSelectionHighlight(graphics, searchQuery, searchX + 4, searchY + 2, searchHeight - 4);
				graphics.text(this.font, searchQuery, searchX + 4, searchY + 4, Theme.text(0xFFBBBBBB));
				if (searchFocused && isCaretBlinkOn()) {
					int idx = Math.max(0, Math.min(textCursor, searchQuery.length()));
					int caretX = searchX + 4 + this.font.width(searchQuery.substring(0, idx)) + 1;
					graphics.fill(caretX, searchY + 3, caretX + 1, searchY + searchHeight - 3, 0xFFFFFFFF);
				}
				graphics.disableScissor();
			}
		}

		graphics.pose().popMatrix();
		graphics.disableScissor();

		int sidebarX = panelX;
		int sidebarTop = panelY + HEADER_HEIGHT;
		int sidebarBottom = panelY + panelHeight;
		FeatureCategory[] allCategories = FeatureCategory.values();
		float totalSidebarHeight = allCategories.length * 22f + 12f;
		maxSidebarScrollOffset = Math.max(0f, totalSidebarHeight - (sidebarBottom - sidebarTop));
		sidebarScrollTarget = Math.min(sidebarScrollTarget, maxSidebarScrollOffset);
		// Same "Smooth scrolling" subtoggle as the content list's own scrollAnim above — see that call site's
		// doc comment.
		GuiAnimationsFeature sidebarGaf = animationsFeature();
		sidebarScrollAnim.setDurationSeconds(sidebarGaf == null || sidebarGaf.isScrollAnimationEnabled() ? SCROLL_DURATION : 0f);
		sidebarScrollAnim.setTarget(sidebarScrollTarget);
		sidebarScrollAnim.update(dt);

		// Per user request ("the categories list should extend down from it"): the sidebar's own scissor
		// bottom bound grows from sidebarTop down to sidebarBottom as menuOpenSidebarAnim advances, revealing
		// the already-drawn category rows underneath rather than needing any separate reveal draw path.
		float sidebarT = menuOpenSidebarAnim.get();
		int sidebarRevealBottom = sidebarTop + Math.round((sidebarBottom - sidebarTop) * sidebarT);
		graphics.enableScissor(sidebarX, sidebarTop, sidebarX + SIDEBAR_WIDTH, sidebarRevealBottom);
		// Own background fill for this section (see the removed base full-panel fill's doc comment above) —
		// only the panel's real outer bottom-left corner (this section's own) needs rounding; top-left is the
		// header's outer corner, right edge is interior against the content section.
		//
		// Real bug found (per user report — "the mod becomes more white when the opening animation is
		// finished... looks good while opening then just becomes saturated"): once the reveal settles, the
		// base full-panel fill (re-added to fix the closing-animation seam) draws EVERY frame underneath this
		// section's own fill — but this fill is the exact same plain Theme.panelBackground() color as the
		// base fill, so post-reveal they stack as two translucent layers of the identical color, which
		// composites strictly more opaque than either alone (translucent-over-translucent isn't idempotent) —
		// a real, visible jump the instant the base fill switches on. Skipped once the base fill is active
		// (mutually exclusive with it, never both drawing at once) since it's then 100% redundant — unlike
		// the header's own fill just above, which uses a genuinely different tint color and isn't a duplicate.
		if (menuOpenContentAnim.get() < 0.999f) {
			fillPanelBackground(graphics, sidebarX, sidebarTop, sidebarX + SIDEBAR_WIDTH, sidebarBottom, PANEL_RADIUS, false, false, true, false,
				panelX, panelX + panelWidth);
		}
		int catY = sidebarTop + 12 - Math.round(sidebarScrollAnim.get());
		for (FeatureCategory category : allCategories) {
			Anim hoverAnim = hoverAnims.computeIfAbsent(category, c -> new Anim(0f, HOVER_DURATION));
			int px = sidebarX + 16;
			int py = catY;
			int textWidth = this.font.width(category.getDisplayName());
			boolean isHoveringText = interactive && mouseX >= px && mouseX <= px + textWidth
				&& mouseY >= catY - 4 && mouseY <= catY - 4 + 16;
			boolean big = isHoveringText || category == selectedCategory;
			hoverAnim.setTarget(big ? 1f : 0f);
			hoverAnim.update(dt);

			boolean rowVisible = catY - 4 + 16 >= sidebarTop && catY - 4 <= sidebarBottom;
			if (rowVisible) {
				int baseColor = category == selectedCategory ? Theme.text(COLOR_SIDEBAR_SELECTED) : accent;
				// Per user request ("when hovered it should become gray/darker, remove the getting bigger
				// animation"): darken on hover instead of scaling the text up.
				int color = hoverAnim.get() > 0.001f ? RenderUtil.darken(baseColor, hoverAnim.get() * HOVER_DARKEN_AMOUNT) : baseColor;
				drawScaledText(graphics, category.getDisplayName(), px, py, color, 1f, px, py + 4);
				categoryRows.add(new CategoryRow(category, sidebarX, catY - 4, SIDEBAR_WIDTH, 16));
			}
			catY += 22;
		}
		graphics.disableScissor();
		// Held back until the sidebar has fully extended — a scrollbar handle spanning the FINAL full height
		// while the list beneath it is still mid-reveal would look like a rendering glitch, not a reveal.
		if (sidebarT > 0.999f) {
			drawScrollbar(graphics, ScrollbarKind.SIDEBAR, sidebarX + SIDEBAR_WIDTH, sidebarTop, sidebarBottom, sidebarScrollAnim.get(), maxSidebarScrollOffset);
		}

		int contentX = panelX + SIDEBAR_WIDTH;
		int contentY = panelY + HEADER_HEIGHT;
		int contentWidth = panelWidth - SIDEBAR_WIDTH;
		int contentHeight = panelHeight - HEADER_HEIGHT;

		// Per user request ("the module list expands from the categories list"): everything in the content
		// area — background, subcategory/slayer tabs, the scrollable feature list, its scrollbar, the
		// floating add-step button, and the content-swap fade overlay — is clipped behind a scissor whose
		// right edge grows from the sidebar's own right edge (contentX, i.e. zero width, flush against the
		// categories list) out to the full panel width as menuOpenContentAnim advances. A single outer
		// scissor around the whole section is safe here because GuiGraphicsExtractor's scissor calls are a
		// real push/pop stack that intersects with whatever's already active (see ScissorStack.push) — every
		// enableScissor/disableScissor pair already inside this section (the row list's own viewport clip,
		// etc.) just nests inside this one instead of needing to change. Closed at the very end of this
		// method, right after the content-swap fade overlay.
		float contentT = menuOpenContentAnim.get();
		int contentRevealRight = contentX + Math.round(contentWidth * contentT);
		graphics.enableScissor(contentX, contentY, contentRevealRight, panelY + panelHeight);

		// Same double-blend fix as the sidebar's own fill above — skipped once the base fill is active, since
		// this is also the exact same plain Theme.panelBackground() color, not a distinct tint.
		if (contentT < 0.999f) {
			fillPanelBackground(graphics, contentX, contentY, contentX + contentWidth, contentY + contentHeight, PANEL_RADIUS, false, false, false, true,
				panelX, panelX + panelWidth);
		}

		List<String> subcats = selectedCategory.getSubcategories();
		// Per user request ("remove the entire tile grid subcategory system, I like the buttons at the
		// top"): subcategories are always a plain flat button row — landing on a category with no
		// subcategory selected yet (e.g. the very first frame) auto-selects the first real one (skipping
		// the Edit GUI Locations sentinel, which opens a screen rather than filtering).
		if (selectedSubcategory == null && !subcats.isEmpty()) {
			selectedSubcategory = subcats.stream().filter(s -> !s.equals(EDIT_GUI_LOCATIONS_SUBCATEGORY)).findFirst().orElse(null);
		}
		int listY = contentY;
		int listHeight = contentHeight;
		if (!subcats.isEmpty()) {
			drawFlatSubcategoryTabs(graphics, contentX, contentY, subcats, mouseX, mouseY, accent);
			listY += SUB_TAB_BAR_HEIGHT;
			listHeight -= SUB_TAB_BAR_HEIGHT;
		}

		// Combat > Slayers gets a second-level tab row (one per slayer type) so those modules stay easy
		// to find instead of being one long undifferentiated list, per user request.
		boolean showSlayerTypeTabs = selectedCategory == FeatureCategory.COMBAT && "Slayers".equals(selectedSubcategory);
		if (showSlayerTypeTabs) {
			drawSlayerTypeTabs(graphics, contentX, listY, mouseX, mouseY, accent);
			listY += SUB_TAB_BAR_HEIGHT;
			listHeight -= SUB_TAB_BAR_HEIGHT;
		}

		List<Feature> features = FeatureRegistry.all().stream()
			.filter(f -> !f.isHiddenFromGui())
			.filter(f -> f.getCategory() == selectedCategory)
			.filter(f -> subcats.isEmpty() || Objects.equals(f.getSubcategory(), selectedSubcategory))
			.filter(f -> !showSlayerTypeTabs || Objects.equals(f.getSlayerType(), selectedSlayerType))
			.filter(f -> searchQuery.isEmpty() || f.getDisplayName().toLowerCase(Locale.ROOT).contains(searchQuery.toLowerCase(Locale.ROOT))
				|| f.getSearchAliases().stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).contains(searchQuery.toLowerCase(Locale.ROOT)))
				|| settingsLabelsContain(f, searchQuery.toLowerCase(Locale.ROOT)))
			.toList();

		// A feature's expanded panel used to stay "open" (expandTarget non-null) even after navigating away
		// from wherever it lives — category tabs, subcategory tabs, slayer-type tabs, and typing a new
		// search query all change this filtered list without ever touching expandTarget/expandPhase. That
		// alone is mostly harmless (the panel content simply isn't in this list to draw), but a couple of
		// per-frame checks elsewhere key off `expandTarget instanceof X` directly, not off whether X is
		// still actually in view — Custom Scoreboard's own live-preview render call and its instant-
		// animation override both keep firing for a feature you've since navigated completely away from,
		// which reads exactly like "the scoreboard breaks after I switch categories/modules": its editor
		// panel is gone from the list, but a stray preview call for a now-orphaned expandTarget is still
		// running every frame behind the scenes. Closing outright the moment the target drops out of view
		// (checked fresh every frame here, so it catches every navigation path uniformly instead of needing
		// a fix at each individual click handler) stops that dangling reference from lingering at all.
		if (expandTarget != null && !features.contains(expandTarget) && expandPhase != ExpandPhase.CLOSED) {
			expandPhase = ExpandPhase.CLOSED;
			expandTarget = null;
			pendingExpand = null;
			expandProgress.snapTo(0f);
			cogVisibility.snapTo(1f);
		}

		float totalContentHeight = features.size() * (float) ROW_HEIGHT;
		if (expandTarget != null && features.contains(expandTarget)) {
			totalContentHeight += expandProgress.get() * expandedContentHeight(expandTarget);
		}
		// maxScrollOffset deliberately uses the FULLY expanded height, not the expandProgress-scaled one
		// above (that one's for the row-slide-down animation only) — clamping the scroll max to whatever
		// fit mid-animation meant scrolling right as/after a panel opened could clamp scrollTarget down to
		// a value that never grew back once the panel finished expanding, effectively capping how far you
		// could scroll until some other interaction (e.g. collapsing and reopening) forced a fresh clamp.
		float maxScrollContentHeight = features.size() * (float) ROW_HEIGHT
			+ (expandTarget != null && features.contains(expandTarget) ? expandedContentHeight(expandTarget) : 0f);
		maxScrollOffset = Math.max(0f, maxScrollContentHeight - listHeight);
		scrollTarget = Math.min(scrollTarget, maxScrollOffset);

		int rowX = contentX + 8;
		int rowWidth = contentWidth - 16;
		float rowY = listY + 8 - scrollAnim.get();

		listViewportY0 = listY;
		listViewportY1 = listY + listHeight;
		listViewportX0 = contentX;
		listViewportX1 = contentX + contentWidth;
		// Per user request ("When the 'Add step' button isn't visible on screen, place a + at the bottom left
		// corner of the mod menu. Its super annoying scrolling up and down to add a step."): reset every
		// frame before the row loop below (which may or may not redraw the Dungeon Routes room editor this
		// frame at all) re-arms it — see drawDungeonRoutesContent's own doc comment on this field for how it
		// gets set, and the floating-button draw right after this method's own scissor closes for where it's
		// actually drawn.
		routeAddStepAction = null;
		copilotAddStepAction = null;
		// Real bug found (per user report, still happening after the earlier alpha-cap fix — "The white
		// module menu fading thing is still not the same color"): capping the overlay's own max alpha to the
		// panel's real alpha (the earlier fix) only ever corrected the fade's single MOST-opaque frame —
		// every frame in between still alpha-blends an increasingly-opaque light panel color OVER whatever
		// the module rows themselves are drawing that frame (row backgrounds, toggle colors, accent text),
		// and blending toward a lighter color always visually reads as "brightening" the content underneath
		// regardless of the overlay's own max alpha, an inherent property of alpha compositing this project
		// has no cheap way to avoid while both layers draw at once. Rather than threading an alpha multiplier
		// through every one of the many per-row/per-widget draw calls (the exact complexity the original
		// crossfade shortcut was built to avoid), row content simply stops drawing once the overlay is more
		// than half covering it — the visible result is a quick dissolve-to-solid-color rather than a
		// wash-through-saturated-content crossfade, which is what "just fade out the modules/fade in an
		// overlayed same color as background pane" actually asked for: the modules disappear, the flat panel
		// color is what's left, nothing blends through it.
		boolean drawRowContent = contentSwapFadeAnim.get() >= 0.5f;
		graphics.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
		for (Feature feature : features) {
			int rowTop = Math.round(rowY);
			int rowInnerHeight = ROW_HEIGHT - 4;
			boolean rowVisible = rowTop + rowInnerHeight >= listY && rowTop <= listY + listHeight;

			if (rowVisible && drawRowContent) {
				fillRoundedGradient(graphics, rowX, rowTop, rowX + rowWidth, rowTop + rowInnerHeight, SMALL_RADIUS,
					colorRowBg(rowX, rowTop, rowWidth, rowInnerHeight));
				// Per user report (a real screenshot of visibly jagged/aliased text on exactly this row
				// label) — first rollout of SmoothTextRenderer, a genuinely different Java2D-rasterized
				// renderer for the same bundled Quicksand font, real antialiasing instead of Minecraft's own
				// fixed-resolution bitmap glyph atlas (see that class' own doc comment for why). Falls back
				// to the normal graphics.text call automatically if the bundled TTF fails to load.
				SmoothTextRenderer.draw(graphics, this.font, feature.getDisplayName(), rowX + 8, rowTop + rowInnerHeight / 2 - 4, colorRowText());
				// Per user request ("tooltips for every feature in the mod... hovering the module name...
				// should show a small tooltip explaining what it does"): every feature's own getDescription()
				// (empty by default, overridden per-feature) is shown via the same 2-second-hover tooltip
				// mechanism already used for individual subtoggles — hovering just the label text, not the
				// whole row, so it doesn't fight with the toggle/cog/keybind hitboxes right next to it.
				if (feature.getDescription() != null) {
					checkHoverTooltip(mouseX, mouseY, rowX + 8, rowTop, this.font.width(feature.getDisplayName()), rowInnerHeight,
						"module_desc_" + feature.getId(), feature.getDescription());
				}

				// Config export/import draws its paste box + export button right in this always-visible
				// row instead of behind a cog/expand panel — per user request, a simple single-line bar
				// (label, paste box, export button), nothing to click to open. Handled entirely separately
				// from the toggle/keybind/cog machinery below since there's no toggle and nothing expands.
				if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ConfigExportImportFeature) {
					drawConfigRowInline(graphics, feature, rowX, rowTop, rowWidth, rowInnerHeight, accent);
					rowY += ROW_HEIGHT;
					continue;
				}

				// Same "no toggle, always-visible single-line row" pattern as the config export/import row
				// right above — version text on the left, a colored Fully Tested/Untested indicator on the
				// right. Per user request ("add support for patch notes in the mod information panel"): unlike
				// Config Export/Import this one now DOES fall through into the normal cog/expand machinery
				// below (no `continue`) — GitHubReleaseApi's fetched release body needs somewhere to actually
				// be shown, and the panel-expand system already exists generically for exactly that, so this
				// reuses it instead of hacking a variable-height inline row into the fixed-ROW_HEIGHT list.
				if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ModInformationFeature) {
					drawModInformationRowInline(graphics, rowX, rowTop, rowWidth, rowInnerHeight);
				}

				// Same "no toggle, nothing to click, no expand panel" pattern as Config Export/Import right
				// above (per user request: "Replace Hypixel Mod API dependency module with an always-on
				// warning banner") — a plain confirmation when the companion mod is detected, or a warning
				// when it's missing, right-aligned same as Mod Information's own status text. The banner IS
				// the entire content, so this `continue`s out before the toggle/cog machinery below rather
				// than reserving a cog slot for a settings panel that doesn't exist.
				if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HypixelModApiDependencyFeature) {
					drawHypixelModApiRowInline(graphics, rowX, rowTop, rowWidth, rowInnerHeight);
					rowY += ROW_HEIGHT;
					continue;
				}

				boolean toggleable = feature.isToggleable();
				KeyMapping primaryKeybind = feature.getPrimaryKeybind();
				KeyCombo primaryCombo = feature.getPrimaryKeyCombo();
				boolean isColorSwatch = !toggleable && primaryKeybind == null && primaryCombo == null && feature instanceof GuiColorFeature;
				boolean isUpdateButton = !toggleable && primaryKeybind == null && primaryCombo == null
					&& feature instanceof com.cokelord.skyblocksimplified.feature.impl.UpdateModuleFeature;
				com.cokelord.skyblocksimplified.api.UpdateApi.State updateState = com.cokelord.skyblocksimplified.api.UpdateApi.getState();

				int slotWidth = 34;
				int slotHeight = 14;
				// Guarded by !toggleable here, matching the drawing dispatch below exactly (toggleable always
				// wins there too — a combo/keybind box only actually renders when the feature ISN'T
				// toggleable). Without this, a feature that's both toggleable AND has a primary combo (e.g.
				// Storage Overview's manual show/hide combo) still fell into this branch and had its slotWidth
				// computed from the combo LABEL's text width — a plain toggle switch had no business being
				// sized off text it never draws, which is what "toggle width too long" actually was: the
				// switch stretched to fit "NONE" or a bound combo's display name instead of its normal 34px.
				if (!toggleable && primaryCombo != null) {
					boolean listening = primaryCombo == listeningForCombo;
					String label = listening ? comboCaptureLabel() : (primaryCombo.isEmpty() ? "NONE" : primaryCombo.getDisplayName());
					slotWidth = Math.max(50, this.font.width(label) + 10);
				} else if (!toggleable && primaryKeybind != null) {
					boolean listening = primaryKeybind == listeningFor;
					String label = listening ? "press a key..." : (primaryKeybind.isUnbound() ? "NONE" : primaryKeybind.getTranslatedKeyMessage().getString());
					slotWidth = Math.max(50, this.font.width(label) + 10);
				} else if (isColorSwatch) {
					slotWidth = COLOR_SWATCH_SIZE;
					slotHeight = COLOR_SWATCH_SIZE;
				} else if (isUpdateButton) {
					String label = updateState == com.cokelord.skyblocksimplified.api.UpdateApi.State.DOWNLOADING ? "Downloading..." : "Update";
					slotWidth = this.font.width(label) + 16;
					slotHeight = 16;
				} else if (!toggleable) {
					// Real bug found (per user report — cog sitting with a visible dead gap to its right on
					// modules like Panel Theme): a non-toggleable feature with no combo/keybind/swatch/button
					// either still fell through with the default 34px toggle-pill slotWidth even though
					// nothing is ever actually drawn in that reserved area — the cog (anchored off slotX
					// below) ended up floating well short of the row's real right edge instead of flush
					// against it. Zeroing the phantom reservation here lets the cog anchor at the true edge
					// instead (see cogX below) for every such feature, not just a one-off per-feature nudge.
					slotWidth = 0;
				}
				int slotX = rowX + rowWidth - slotWidth - 8;
				int slotY = rowTop + (rowInnerHeight - slotHeight) / 2;

				if (toggleable) {
					boolean enabled = feature.isEnabled();
					float toggleT = toggleAnimValue(feature.getId(), enabled);
					RenderUtil.fillPill(graphics, slotX, slotY, slotX + slotWidth, slotY + slotHeight, FULL_ROUND, RenderUtil.lerpColor(colorToggleOff(), colorToggleOn(), toggleT));
					int knobSize = slotHeight - 4;
					int knobX = slotX + 2 + Math.round(toggleT * (slotWidth - knobSize - 4));
					int knobY = slotY + 2;
					fillCircle(graphics, knobX + knobSize / 2, knobY + knobSize / 2, knobSize / 2, RenderUtil.lerpColor(colorKnobOff(), COLOR_KNOB_ON, toggleT));
				} else if (primaryCombo != null) {
					boolean listening = primaryCombo == listeningForCombo;
					String label = listening ? comboCaptureLabel() : (primaryCombo.isEmpty() ? "NONE" : primaryCombo.getDisplayName());
					fillRounded(graphics, slotX, slotY, slotX + slotWidth, slotY + slotHeight, BOX_RADIUS, listening ? colorKeybindListening() : colorKeybindBox());
					SmoothTextRenderer.draw(graphics, this.font, label, slotX + 4, slotY + 3, Theme.text(0xFFDDDDDD));
					comboRows.add(new ComboRow(primaryCombo, slotX, slotY, slotWidth, slotHeight));
				} else if (primaryKeybind != null) {
					boolean listening = primaryKeybind == listeningFor;
					String label = listening ? "press a key..." : (primaryKeybind.isUnbound() ? "NONE" : primaryKeybind.getTranslatedKeyMessage().getString());
					fillRounded(graphics, slotX, slotY, slotX + slotWidth, slotY + slotHeight, BOX_RADIUS, listening ? colorKeybindListening() : colorKeybindBox());
					SmoothTextRenderer.draw(graphics, this.font, label, slotX + 4, slotY + 3, Theme.text(0xFFDDDDDD));
					inlineKeybindRows.add(new InlineKeybindRow(primaryKeybind, slotX, slotY, slotWidth, slotHeight));
				} else if (isColorSwatch) {
					fillRounded(graphics, slotX, slotY, slotX + slotWidth, slotY + slotHeight, SMALL_RADIUS, ((GuiColorFeature) feature).getColor());
				} else if (isUpdateButton) {
					boolean downloading = updateState == com.cokelord.skyblocksimplified.api.UpdateApi.State.DOWNLOADING;
					boolean available = updateState == com.cokelord.skyblocksimplified.api.UpdateApi.State.AVAILABLE;
					String label = downloading ? "Downloading..." : "Update";
					int bg = downloading ? Theme.chrome(0xFF3A3A3A) : available ? accent : Theme.chrome(0xFF2A2A2A);
					if (available) {
						fillRounded(graphics, slotX, slotY, slotX + slotWidth, slotY + slotHeight, BOX_RADIUS, bg);
					} else {
						fillPanelBackgroundOrGradient(graphics, slotX, slotY, slotX + slotWidth, slotY + slotHeight, BOX_RADIUS, bg, 0.12f);
					}
					int textColor = available || downloading ? 0xFFFFFFFF : 0xFF777777;
					SmoothTextRenderer.draw(graphics, this.font, label, slotX + (slotWidth - this.font.width(label)) / 2, slotY + (slotHeight - 8) / 2, textColor);
					// Per user request: no update available means this button does nothing, so it's greyed
					// out AND has a real vanilla barrier icon drawn over its own text — same "this is inert"
					// language RareRewardWarningFeature's Refuse Offer slot already uses elsewhere in this
					// project, rather than inventing a new visual vocabulary for "disabled." Actual click
					// gating lives in mouseClicked (state == AVAILABLE), not here — this is purely visual.
					if (!available && !downloading) {
						graphics.item(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BARRIER),
							slotX + (slotWidth - 16) / 2, slotY + (slotHeight - 16) / 2);
					}
				}

				// GUI Color has no cog: the swatch itself opens its settings, per user request.
				Feature settingsFeature = feature instanceof com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror mirror ? mirror.getTarget() : feature;
				boolean hasSettings = !feature.getKeybinds().isEmpty()
					|| settingsFeature instanceof GuiAnimationsFeature
					|| settingsFeature instanceof CustomEnchantParsingFeature
					|| settingsFeature instanceof MobHighlightFeature
					|| settingsFeature instanceof ItemPickupLogFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatDeclutterFeature
					|| settingsFeature instanceof CroesusFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.SecretsCounterFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.NetworkDisplayFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.BossBarFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.HideArrowsFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.HideDamageSplashesFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DamageTruncatorFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.HideFireFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.HideNametagsFeature
					|| settingsFeature instanceof DungeonClickedBlocksFeature
					|| settingsFeature instanceof MoneyPerHourFeature
					|| settingsFeature instanceof PestCooldownFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.HidePestDropsFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsMasterFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsHoldingOnlyFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ExperimentAddonsFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.MoongladeBeaconAlertFeature
					|| settingsFeature instanceof ActiveHotfPerksHighlightFeature
					|| settingsFeature instanceof DnaAnalyzerSolverFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatCopyFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.RareRewardWarningFeature
						|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.GyroHelperFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.LavaToWaterFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowHitSoundFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.MelodyDisplayFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.SimonSaysFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.EntityRenderDistanceFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CameraFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DisableEndermanDeathAnimationFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatCommandsFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.InvincibilityTimerFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.AbilityCooldownTimerFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.PetDisplayFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.LoadoutOverlayFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.MageBeamFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.SlotBindsFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.EtherwarpFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.AutoKickFeature
					|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.PartyFinderFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.WaterSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.TPMazeSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.IceFillSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BlazeSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BeamsSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.WeirdosSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.QuizSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BoulderSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.TicTacToeSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.PlayerDisplayFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DoorHighlightFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.InactiveWaypointsFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.LeapCounterFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.BloodCampFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.TerminalSoundsFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonScoreCalculatorFeature
				// Real bug found (per user report — "the catacombs experience calculator has no cog at all and
				// i cant see the settings"): same class of bug this list's own SplitsFeature/AuctionHouseTotal
				// fix already called out — a drawExpandedSettings/expandedContentHeight branch existed, but
				// this separate, hand-maintained hasSettings whitelist (the only thing that actually decides
				// whether a cog is drawn at all) never got this brand-new feature added to it.
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonTimersFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ChestRollingFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.MimicFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowsDeviceFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.MelodyMessageFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ItemRarityBackgroundFeature
				// Real bug found (per user report — "add their auto-requeue... add a timer aswell"): the whole
				// Auto Requeue/Requeue Delay/Disable on leave/Announce Kick backend was already fully built and
				// working in DungeonQueueFeature (ported from Odin's own DungeonQueue.kt), but this same class
				// of missing-cog bug meant none of it was ever reachable from the mod menu — the user had no way
				// to even turn Auto Requeue on, so it looked like the feature had never been added at all.
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonQueueFeature
				// Real bug found (per user report — "Splits still doesnt have a config cog"): a settings
				// panel branch existed in drawExpandedSettings for both of these, but the cog that's the only
				// way to actually OPEN that panel is gated by this separate, hand-maintained list — both were
				// missing from it, so the panel was unreachable in-game even though it rendered fine once
				// expanded some other way.
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.SplitsFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.AuctionHouseTotalFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.AuctionTimersFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ExperimentationTimersFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowAlignFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomLoadoutKeybindsFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.PetKeybindsFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature
				|| settingsFeature instanceof com.cokelord.skyblocksimplified.feature.impl.ModInformationFeature;
				int cogSize = 12;
				// Real fix (per user follow-up — "Move cog further left in Mod Information row (verify vs
				// earlier -4px fix)": the previous -4px nudge here was a guess that turned out too small —
				// "Fully Tested."/"Checking..." are wide enough that the cog still landed inside the text's
				// own span, not just close to it. Structural fix instead: ModInformationFeature now zeroes its
				// phantom slotWidth like every other no-content non-toggleable feature (see slotWidth's own
				// comment above), so cogX here is the SAME flush-right formula for all of them — and
				// drawModInformationRowInline's own status-text placement (below) is written to stop clear of
				// this exact cogX, rather than the cog trying to dodge a text width it doesn't know.
				int cogX = slotX - cogSize - 8;
				int cogY = rowTop + (rowInnerHeight - cogSize) / 2;
				float cogAlpha = feature == expandTarget ? cogVisibility.get() : 1f;
				if (hasSettings && cogAlpha > 0.02f) {
					boolean cogHovered = mouseX >= cogX && mouseX <= cogX + cogSize && mouseY >= cogY && mouseY <= cogY + cogSize;
					Anim hoverAnim = cogHoverAnims.computeIfAbsent(feature.getId(), id -> new Anim(0f, 0.15f));
					hoverAnim.setTarget(cogHovered ? 1f : 0f);
					hoverAnim.update(dt);
					float hoverT = hoverAnim.get();
					int drawnSize = cogSize;
					GuiAnimationsFeature gafForSpin = animationsFeature();
					boolean spinEnabled = gafForSpin == null || gafForSpin.isCogSpinEnabled();
					// While hovered: spin continuously (near-instant tracking, not eased). Once hover ends,
					// ease back to the nearest "upright" (multiple of 360) over 0.3s instead of just
					// stopping wherever it happened to be.
					Anim spinAnim = cogSpinAnims.computeIfAbsent(feature.getId(), id -> new Anim(0f, 0.02f));
					if (cogHovered && spinEnabled) {
						spinAnim.setDurationSeconds(0.02f);
						spinAnim.setTarget(spinAnim.getTarget() + dt * 60f);
					} else {
						spinAnim.setDurationSeconds(0.3f);
						spinAnim.setTarget(Math.round(spinAnim.get() / 360f) * 360f);
					}
					spinAnim.update(dt);
					float spinAngle = spinAnim.get();
					int cx = cogX + cogSize / 2;
					int cy = cogY + cogSize / 2;
					int alphaByte = Math.round(cogAlpha * 255f) << 24;
					int cogColor = alphaByte | (accent & 0xFFFFFF);
					if (hoverT > 0.001f) cogColor = RenderUtil.darken(cogColor, hoverT * HOVER_DARKEN_AMOUNT);
					graphics.pose().pushMatrix();
					graphics.pose().translate(cx, cy);
					graphics.pose().rotate((float) Math.toRadians(spinAngle));
					graphics.pose().translate(-cx, -cy);
					drawIcon(graphics, COG_TEXTURE, cx - drawnSize / 2, cy - drawnSize / 2, drawnSize, cogColor);
					graphics.pose().popMatrix();
				}
				// Faded-out (cogAlpha low) must also stop being clickable — otherwise a hidden cog was
				// still hit-testable, which is the "unclickable when hidden" bug.
				boolean cogClickable = hasSettings && cogAlpha > 0.5f;

				featureRows.add(new FeatureRow(feature, slotX, slotY, slotWidth, slotHeight, cogX, cogY, cogSize, hasSettings, cogClickable, toggleable, isColorSwatch,
					rowX, rowTop, rowWidth, rowInnerHeight, isUpdateButton));
			}
			rowY += ROW_HEIGHT;

			if (feature == expandTarget && expandPhase != ExpandPhase.CLOSED) {
				float prevHeight = expandProgress.getPrevious() * expandedContentHeight(feature);
				float curHeight = expandProgress.get() * expandedContentHeight(feature);
				int boxTop = Math.round(rowY);
				int boxBottom = boxTop + Math.round(curHeight);
				if (boxBottom >= listY && boxTop <= listY + listHeight) {
					drawExpandGhost(graphics, rowX, boxTop, rowWidth, prevHeight, curHeight);
					drawExpandedSettings(graphics, feature, rowX, boxTop, rowWidth, Math.round(curHeight), accent, mouseX, mouseY);
				}
				rowY += curHeight;
			}
		}
		graphics.disableScissor();

		// Per user request ("Add a draggable scrollbar on the right side of any scrollable panel"): drawn
		// after the scissor above closes so it isn't clipped by the content it scrolls.
		drawScrollbar(graphics, ScrollbarKind.CONTENT, listViewportX1, listViewportY0, listViewportY1, scrollAnim.get(), maxScrollOffset);

		// Per user request ("When the 'Add step' button isn't visible on screen, place a + at the bottom
		// left corner of the mod menu. Its super annoying scrolling up and down to add a step."): drawn
		// outside every scissor above so it always renders on top, unaffected by scroll clipping — see
		// routeAddStepAction's own field doc comment for how/when it gets set this frame.
		Runnable floatingAddAction = routeAddStepAction != null ? routeAddStepAction : copilotAddStepAction;
		if (floatingAddAction != null) {
			int floatSize = 22;
			int floatX = contentX + 10;
			int floatY = listY + listHeight - floatSize - 10;
			fillRounded(graphics, floatX, floatY, floatX + floatSize, floatY + floatSize, SMALL_RADIUS, accent);
			String plus = "+";
			SmoothTextRenderer.draw(graphics, this.font, plus, floatX + (floatSize - this.font.width(plus)) / 2, floatY + (floatSize - 8) / 2, Theme.text(0xFFFFFFFF));
			Runnable action = floatingAddAction;
			clickHits.add(new ClickHit(floatX, floatY, floatSize, floatSize, action));
		}

		// Per user request ("the current modules should just fade out and the new ones should just fade
		// in"): a cheap crossfade rather than threading an alpha multiplier through every one of the many
		// per-row/per-panel draw calls above (rows, cogs, sliders, expanded settings panels, etc.) — an
		// increasingly-opaque panel-background-colored overlay reads as a fade in/out without needing to
		// touch any of that existing rendering. Only drawn while a fade is actually in progress; fully
		// skipped once contentSwapFadeAnim settles back to 1 (its resting value).
		if (contentSwapFadeAnim.get() < 0.999f) {
			// Real bug found (per user report — "The fading animation when switching categories and
			// subcategories makes the modules section brighter in the white theme, when it should stay the
			// same color but just fade out the modules/fade in an overlayed same color as background pane"):
			// this used to ramp the overlay's own alpha across the FULL 0-255 range regardless of the real
			// panel background's own alpha — Panel Theme's White mode sets panelBackground to 0xF0C9C9C9 (94%
			// opacity, deliberately not fully opaque, per that feature's own doc comment), so as the fade
			// approached its most-covered point this overlay went MORE opaque (up to 255) than the real panel
			// background ever actually is, painting a purer/flatter, visibly brighter swatch of the same RGB
			// than what the panel normally shows blended over whatever sits behind it. Capping the overlay's
			// own max alpha at the real panel's own alpha keeps the fade's most-opaque frame visually
			// identical to the real background pane instead of overshooting past it.
			int baseAlpha = (Theme.panelBackground() >>> 24) & 0xFF;
			int overlayAlpha = Math.round((1f - contentSwapFadeAnim.get()) * baseAlpha);
			graphics.fill(contentX, listY, contentX + contentWidth, listY + listHeight, (overlayAlpha << 24) | (Theme.panelBackground() & 0xFFFFFF));
		}

		graphics.disableScissor();
	}

	/** Second-level tab row under Combat > Slayers (Tarantula/Enderman/Blaze/Vampire/Zombie/Wolf, plus
	 *  a "General" tab for slayer-agnostic toggles like Highlight Minibosses) — kept deliberately
	 *  simpler than drawSubTabs (no hover-grow) since this is a one-off, not a reused primitive. */
	private void drawSlayerTypeTabs(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY, int accent) {
		int bx = x + 8;
		int buttonHeight = 18;
		int by = y + (SUB_TAB_BAR_HEIGHT - buttonHeight) / 2;
		String current = selectedSlayerType == null ? "General" : selectedSlayerType;
		for (String type : SLAYER_TYPES) {
			int textWidth = this.font.width(type);
			int buttonWidth = textWidth + 14;
			boolean selected = type.equals(current);
			fillPanelBackgroundOrGradient(graphics, bx, by, bx + buttonWidth, by + buttonHeight, BOX_RADIUS,
				selected ? colorSubTabSelectedBg() : colorSubTabBg(), selected ? 0f : 0.1f);
			SmoothTextRenderer.draw(graphics, this.font, type, bx + (buttonWidth - textWidth) / 2, by + (buttonHeight - 8) / 2, accent);
			slayerTypeRows.add(new SubTabRow(type, bx, by, buttonWidth, buttonHeight));
			bx += buttonWidth + 6;
		}
	}

	/** Per user request ("remove the entire tile grid subcategory system, I like the buttons at the top"):
	 *  a plain always-visible row of text buttons — same shape as {@link #drawSlayerTypeTabs} (no image, no
	 *  hover-grow), driving {@code selectedSubcategory} directly. */
	private void drawFlatSubcategoryTabs(GuiGraphicsExtractor graphics, int x, int y, List<String> subcats, int mouseX, int mouseY, int accent) {
		int bx = x + 8;
		int buttonHeight = 18;
		int by = y + (SUB_TAB_BAR_HEIGHT - buttonHeight) / 2;
		for (String sub : subcats) {
			int textWidth = this.font.width(sub);
			int buttonWidth = textWidth + 14;
			boolean selected = sub.equals(selectedSubcategory);
			fillPanelBackgroundOrGradient(graphics, bx, by, bx + buttonWidth, by + buttonHeight, BOX_RADIUS,
				selected ? colorSubTabSelectedBg() : colorSubTabBg(), selected ? 0f : 0.1f);
			SmoothTextRenderer.draw(graphics, this.font, sub, bx + (buttonWidth - textWidth) / 2, by + (buttonHeight - 8) / 2, selected ? accent : Theme.text(0xFFDDDDDD));
			subTabRows.add(new SubTabRow(sub, bx, by, buttonWidth, buttonHeight));
			bx += buttonWidth + 6;
		}
	}

	private void drawExpandedSettings(GuiGraphicsExtractor graphics, Feature feature, int x, int y, int width, int height, int accent, int mouseX, int mouseY) {
		if (feature instanceof com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror mirror) feature = mirror.getTarget();
		if (height <= 1) return;
		fillPanelBackgroundOrGradient(graphics, x, y, x + width, y + height, BOX_RADIUS, colorExpandBg(), 0.2f);
		if (height < SLOT_ROW_HEIGHT / 2) return;

		graphics.enableScissor(x, y, x + width, y + height);
		if (feature instanceof GuiColorFeature gcf) {
			drawColorPickerContent(graphics, gcf, x, y, width, height, accent, mouseX, mouseY);
		} else if (feature instanceof GuiAnimationsFeature gaf) {
			drawAnimationsContent(graphics, gaf, x, y, width, height, accent);
		} else if (feature instanceof CustomEnchantParsingFeature cef) {
			drawEnchantParsingContent(graphics, cef, x, y, width, height, accent);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf) {
			drawPositionalMessagesContent(graphics, pmf, x, y, width, height, accent);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.InactiveWaypointsFeature iwf) {
			drawInactiveWaypointsContent(graphics, iwf, x, y, width, height, accent);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature ddf) {
			drawDungeonDeclutterContent(graphics, ddf, x, y, width, height);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.LeapCounterFeature lcf) {
			drawLeapCounterContent(graphics, lcf, x, y, width, height, accent);
		} else if (feature instanceof MobHighlightFeature mhf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			// Per user request: every subtoggle/slider/cycle setting first, color pickers last — "the user
			// should input their settings before picking the colors they want." Highlight Color/Fill Color
			// moved to the end of this block (still drawn relative to each other in their old order);
			// nothing else about their behavior changed.
			int rowY = y + 4;
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Render mode", renderModeLabel(mhf.getRenderMode()),
				() -> { cycleRenderMode(mhf); ConfigManager.save(); },
				"Cycles how the highlight is drawn: 2D (flat screen-space outline), 2D Fill (outline plus a colored interior), 3D (a real outline in the world), or 3D Fill (a real outline plus interior fill in the world).");
			boolean full = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_2D;
			boolean is3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.WIRE_3D || mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			boolean isFull3D = mhf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Outline thickness", String.valueOf(mhf.getOutlineThickness()),
				(mhf.getOutlineThickness() - 1) / 9f, v -> { mhf.setOutlineThickness(1 + Math.round(v * 9f)); ConfigManager.save(); }, accent,
				"How thick the highlight's outline is drawn, in pixels.");
			if (is3D) {
				// Shared by both 3D modes: true = a real occluded box (hidden behind terrain like a normal
				// object), false = "ESP-style" always-visible-through-walls — see World3DRenderer's own doc
				// comment for the underlying depth-stencil-state mechanism.
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Occlusion (hide behind walls)",
					mhf.isOcclusion3D(), () -> { mhf.setOcclusion3D(!mhf.isOcclusion3D()); ConfigManager.save(); },
					"On: the 3D highlight is hidden behind walls/terrain like a normal object. Off: it stays visible through walls (ESP-style).");
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Tracers",
				mhf.isTracersEnabled(), () -> { mhf.setTracersEnabled(!mhf.isTracersEnabled()); ConfigManager.save(); },
				"Draws a line from your crosshair to each highlighted mob, so you can find it even when it isn't currently on screen.");
			// Per user request: one shared switch, exposed inside EVERY highlight module's own settings
			// (not per-feature state — MobHighlightFeature.isHighFrequencyPolling()/setHighFrequencyPolling()
			// are static, so flipping this row from ANY highlight module's panel flips it for all of them at
			// once, same "one underlying setting reachable from several places" idea as the Kuudra/Dungeons
			// Croesus row). Trades a bit of FPS for how quickly a newly-appearing/disappearing entity gets
			// added to or dropped from the highlighted set — see HighlightBoxRenderer's own doc comment.
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "High Update Rate (uses more FPS)",
				MobHighlightFeature.isHighFrequencyPolling(), () -> { MobHighlightFeature.setHighFrequencyPolling(!MobHighlightFeature.isHighFrequencyPolling()); ConfigManager.save(); },
				"Shared by every highlight module in the mod. On: newly-appearing or disappearing mobs get highlighted/unhighlighted faster, at the cost of some FPS. Off: slightly slower to react, but lighter on performance.");
			if (mhf.isTracersEnabled()) {
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Tracer thickness", String.valueOf(mhf.getTracerThickness()),
					(mhf.getTracerThickness() - 1) / 9f, v -> { mhf.setTracerThickness(1 + Math.round(v * 9f)); ConfigManager.save(); }, accent,
					"How thick the tracer line is drawn, in pixels.");
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Tracer opacity", mhf.getTracerOpacity() + "%",
					mhf.getTracerOpacity() / 100f, v -> { mhf.setTracerOpacity(Math.round(v * 100f)); ConfigManager.save(); }, accent,
					"How see-through the tracer line is.");
			}
			if (full || isFull3D) {
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Fill opacity", mhf.getFillOpacity() + "%",
					mhf.getFillOpacity() / 100f, v -> { mhf.setFillOpacity(Math.round(v * 100f)); ConfigManager.save(); }, accent,
					"How see-through the highlight's interior fill color is.");
			}
			if (full) {
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Use Custom Image",
					mhf.isUseCustomImage(), () -> { mhf.setUseCustomImage(!mhf.isUseCustomImage()); ConfigManager.save(); },
					"Replaces the flat fill color with an image you upload, drawn over the highlighted mob.");
				rowY += 8;
				boolean hasImage = mhf.getCustomImagePath() != null;
				net.minecraft.resources.Identifier previewId = hasImage ? mhf.getCustomImageTextureId() : null;
				mhfImageButtonHeight = 20;
				mhfImageButtonY = rowY;
				if (previewId != null) {
					// A small preview thumbnail next to the button — per user request, so it's clear what's
					// actually loaded without needing to check it in-game against a real highlighted mob.
					int previewSize = mhfImageButtonHeight;
					fillRounded(graphics, rowX, mhfImageButtonY, rowX + previewSize, mhfImageButtonY + previewSize, SMALL_RADIUS, 0xFF000000);
					graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, previewId, rowX, mhfImageButtonY, 0f, 0f,
						previewSize, previewSize, mhf.getCustomImageWidth(), mhf.getCustomImageHeight(),
						mhf.getCustomImageWidth(), mhf.getCustomImageHeight(), 0xFFFFFFFF);
					mhfImageButtonX = rowX + previewSize + 6;
					mhfImageButtonWidth = rowWidth - previewSize - 6;
				} else {
					mhfImageButtonX = rowX;
					mhfImageButtonWidth = rowWidth;
				}
				fillRounded(graphics, mhfImageButtonX, mhfImageButtonY, mhfImageButtonX + mhfImageButtonWidth, mhfImageButtonY + mhfImageButtonHeight, BOX_RADIUS, accent);
				String selectLabel = hasImage ? "Change Image..." : "Select Image...";
				SmoothTextRenderer.draw(graphics, this.font, selectLabel, mhfImageButtonX + (mhfImageButtonWidth - this.font.width(selectLabel)) / 2,
					mhfImageButtonY + (mhfImageButtonHeight - 8) / 2, 0xFFFFFFFF);
				rowY += mhfImageButtonHeight;
				if (hasImage) {
					rowY += 6;
					mhfClearImageButtonX = rowX;
					mhfClearImageButtonY = rowY;
					mhfClearImageButtonWidth = rowWidth;
					mhfClearImageButtonHeight = 20;
					fillRounded(graphics, mhfClearImageButtonX, mhfClearImageButtonY, mhfClearImageButtonX + mhfClearImageButtonWidth,
						mhfClearImageButtonY + mhfClearImageButtonHeight, BOX_RADIUS, Theme.chrome(0xFF3A3A3A));
					String clearLabel = "Clear Image";
					SmoothTextRenderer.draw(graphics, this.font, clearLabel, mhfClearImageButtonX + (mhfClearImageButtonWidth - this.font.width(clearLabel)) / 2,
						mhfClearImageButtonY + (mhfClearImageButtonHeight - 8) / 2, 0xFFDDDDDD);
					rowY += mhfClearImageButtonHeight + 8;
					// Fill mode only matters once an image actually exists to be fit/tiled — per user
					// request, "like a Windows 11 background" (Stretch/Fill/Fit/Tile).
					rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Image Fill Mode", mhf.getImageFillMode().label,
						() -> { mhf.cycleImageFillMode(); ConfigManager.save(); }, (Runnable) null,
						"Controls how your custom image is fit into the highlighted mob's shape: Stretch, Fill, Fit, or Tile — like a desktop background.");
				}
			}
			rowY += 4;
			colorPickerLabel(graphics, rowX, rowY, "Highlight Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "mhf_" + mhf.getId(), COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				mhf::getColor, c -> { mhf.setColor((mhf.getColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, mhf.getDefaultColor());
			if (full || isFull3D) {
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Fill Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "mhf_fill_" + mhf.getId(), COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					mhf::getFillColor, c -> { mhf.setFillColor((mhf.getFillColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, mhf.getDefaultColor());
			}
			// Per user request ("remake this one"), cross-checked against NoammAddons' own confirmed
			// per-phase color scheme: one color each for Maxor/Storm/Goldor/Necron, applied automatically as
			// the fight progresses (see WitherHighlightFeature.onTick) — the generic "Highlight Color" above
			// still exists (shared MobHighlightFeature machinery) but gets overwritten by whichever of these
			// is active, so these are the ones that actually matter for this specific module.
			if (mhf instanceof com.cokelord.skyblocksimplified.feature.impl.WitherHighlightFeature whf) {
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Maxor Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_maxor", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					whf::getMaxorColor, c -> { whf.setMaxorColor((whf.getMaxorColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF5804A4);
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Storm Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_storm", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					whf::getStormColor, c -> { whf.setStormColor((whf.getStormColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF00D0FF);
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Goldor Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_goldor", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					whf::getGoldorColor, c -> { whf.setGoldorColor((whf.getGoldorColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFBB00);
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Necron Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_necron", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					whf::getNecronColor, c -> { whf.setNecronColor((whf.getNecronColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF0000);
				// Per user request ("colorable per-boss fill matching outline color"): only meaningful for the
				// fill-capable render modes (FULL_2D/FULL_3D), same gate as the generic "Fill Color" picker
				// above — a wire-only mode has nothing for these to paint.
				if (full || isFull3D) {
					rowY += 8;
					colorPickerLabel(graphics, rowX, rowY, "Maxor Fill Color");
					rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_maxor_fill", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
						whf::getMaxorFillColor, c -> { whf.setMaxorFillColor((whf.getMaxorFillColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF5804A4);
					rowY += 8;
					colorPickerLabel(graphics, rowX, rowY, "Storm Fill Color");
					rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_storm_fill", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
						whf::getStormFillColor, c -> { whf.setStormFillColor((whf.getStormFillColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF00D0FF);
					rowY += 8;
					colorPickerLabel(graphics, rowX, rowY, "Goldor Fill Color");
					rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_goldor_fill", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
						whf::getGoldorFillColor, c -> { whf.setGoldorFillColor((whf.getGoldorFillColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFBB00);
					rowY += 8;
					colorPickerLabel(graphics, rowX, rowY, "Necron Fill Color");
					drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_necron_fill", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
						whf::getNecronFillColor, c -> { whf.setNecronFillColor((whf.getNecronFillColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF0000);
				}
			}
			// Real gap found: hideNonStarredNames/teammateClassGlow were added to StarredMobHighlightFeature
			// (with real persistence and behavior already wired into onTick) but this class was never checked
			// for by name anywhere in MainScreen, so it fell entirely into the generic MobHighlightFeature
			// branch above and neither toggle was ever actually reachable from the menu. Per user request this
			// round ("Hide starred mob nametags (the esp should bypass this hide)") a third toggle
			// (hideStarredNames) was added for the same real gap — all three are wired in together here now.
			if (mhf instanceof com.cokelord.skyblocksimplified.feature.impl.StarredMobHighlightFeature smhf) {
				rowY += 8;
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Non-Starred Nametags",
					smhf.isHideNonStarredNames(), () -> { smhf.setHideNonStarredNames(!smhf.isHideNonStarredNames()); ConfigManager.save(); });
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Starred Nametags",
					smhf.isHideStarredNames(), () -> { smhf.setHideStarredNames(!smhf.isHideStarredNames()); ConfigManager.save(); });
			}
			// LividFinderFeature now extends MobHighlightFeature (see that class's own doc comment) — its own
			// extra "Hide Wrong Livids" toggle is appended here instead of a separate dedicated branch, same
			// precedent as the WitherHighlightFeature/StarredMobHighlightFeature extras above.
			if (mhf instanceof LividFinderFeature lff) {
				rowY += 8;
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Wrong Livids",
					lff.isHideWrongLivids(), () -> { lff.setHideWrongLivids(!lff.isHideWrongLivids()); ConfigManager.save(); });
				// Per user request ("Add a toggle for coloring the correct livid their color. For example, if
				// the correct livid color is purple then it should color the ESP purple") — see
				// LividFinderFeature.colorByLividColor's own doc comment for how this overrides the drawn color.
				drawToggleRow(graphics, rowX, rowY, rowWidth, "Color By Livid's Color",
					lff.isColorByLividColor(), () -> { lff.setColorByLividColor(!lff.isColorByLividColor()); ConfigManager.save(); });
			}
			// Per user request ("Make highlight party members into its own module... add the hide glow module
			// as a subtoggle inside highlight party members ASWELL so the hide glow we currently have links
			// with the one in the teammate highlight"): "Color by Role" switches between the single shared
			// Highlight Color above and 5 fixed per-class colors (see the class doc comment for why role mode
			// can't use the 3D render modes); "Hide Glow" reads/writes PlayerGlowFeature's own enabled state
			// directly, so toggling it here or on the standalone Hide Glow module always shows the same value.
			if (mhf instanceof com.cokelord.skyblocksimplified.feature.impl.HighlightPartyMembersFeature hpmf) {
				rowY += 8;
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Color by Role",
					hpmf.isColorByRole(), () -> { hpmf.setColorByRole(!hpmf.isColorByRole()); ConfigManager.save(); },
					"Colors each party member by their dungeon class instead of one shared color. Not available in 3D render modes.");
				drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Glow",
					hpmf.isHideGlowEnabled(), () -> hpmf.setHideGlowEnabled(!hpmf.isHideGlowEnabled()),
					"Same setting as the standalone \"Hide Glow\" module — hides the real glow outline on every entity, not just party members.");
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HideDamageSplashesFeature hdsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Only Near Slayer Bosses",
				hdsf.isOnlyNearSlayerBosses(), () -> { hdsf.setOnlyNearSlayerBosses(!hdsf.isOnlyNearSlayerBosses()); ConfigManager.save(); },
				"Only hides damage numbers while you're near a slayer boss, leaving them visible everywhere else.");
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Only Near Dungeon Bosses",
				hdsf.isOnlyNearDungeonBosses(), () -> { hdsf.setOnlyNearDungeonBosses(!hdsf.isOnlyNearDungeonBosses()); ConfigManager.save(); },
				"Only hides damage numbers while you're near a dungeon boss, leaving them visible everywhere else.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DamageTruncatorFeature dtf) {
			drawSliderRow(graphics, x + 10, y + 4, width - 20, "Decimals", String.valueOf(dtf.getDecimals()),
				dtf.getDecimals() / 4f, v -> { dtf.setDecimals(Math.round(v * 4f)); ConfigManager.save(); }, accent,
				"How many decimal places to show on shortened damage numbers (e.g. 1.4M vs 1.42M).");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HideFireFeature hff) {
			drawToggleRow(graphics, x + 10, y + 7, width - 20, "Hide Fire On Entities Aswell",
				hff.isHideOnEntities(), () -> { hff.setHideOnEntities(!hff.isHideOnEntities()); ConfigManager.save(); },
				"Also hides the fire overlay on other burning entities, not just your own first-person screen.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HideNametagsFeature hntf) {
			drawToggleRow(graphics, x + 10, y + 7, width - 20, "Only In Dungeons",
				hntf.isOnlyInDungeons(), () -> { hntf.setOnlyInDungeons(!hntf.isOnlyInDungeons()); ConfigManager.save(); },
				"Only hides nametags while you're in a dungeon, leaving them visible everywhere else.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MoongladeBeaconAlertFeature mbaf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Moonglade Beacon Solver",
				mbaf.isSolverEnabled(), () -> { mbaf.setSolverEnabled(!mbaf.isSolverEnabled()); ConfigManager.save(); },
				"Highlights the correct color-select slots for the Moonglade Beacon puzzle when it's ready.");
			drawColorCycleRow(graphics, rowX, rowY, rowWidth, "Solver Highlight Color", mbaf.getSolverColor(), c -> { mbaf.setSolverColor(c); ConfigManager.save(); },
				"Color used to highlight the correct slots.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.TerminalSoundsFeature tsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Click Sounds",
				tsf.isClickSounds(), () -> { tsf.setClickSounds(!tsf.isClickSounds()); ConfigManager.save(); },
				"Plays a sound every time you make a correct click on a dungeon terminal.");
			if (tsf.isClickSounds()) {
				rowY = drawSoundOptionRows(graphics, rowX, rowY, rowWidth, accent, tsf.getClickSound());
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Complete Sounds",
				tsf.isCompleteSounds(), () -> { tsf.setCompleteSounds(!tsf.isCompleteSounds()); ConfigManager.save(); },
				"Plays a sound when a dungeon terminal is fully solved.");
			if (tsf.isCompleteSounds()) {
				drawSoundOptionRows(graphics, rowX, rowY, rowWidth, accent, tsf.getCompleteSound());
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ExperimentAddonsFeature eaf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			// Hex-capable full color pickers (was a plain preset-cycle swatch with no way to type an exact
			// hex value) — same colorPickerLabel + drawFullColorPicker pattern every other feature's colors use.
			colorPickerLabel(graphics, rowX, rowY, "Current Click Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "eaf_next", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				eaf::getNextClickColor, c -> { eaf.setNextClickColor((eaf.getNextClickColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x4000FF00);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Next Click Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "eaf_second", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				eaf::getSecondClickColor, c -> { eaf.setSecondClickColor((eaf.getSecondClickColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x40FFFF00);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsMasterFeature ckmf) {
			drawSliderRow(graphics, x + 10, y + 4, width - 20, "Sensitivity While Active", Math.round(ckmf.getSensitivity() * 100) + "%",
				ckmf.getSensitivity(), v -> { ckmf.setSensitivity(v); ConfigManager.save(); }, accent,
				"Mouse sensitivity multiplier applied only while a Custom Keybinds remap is active — 0% means the camera doesn't turn at all.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsHoldingOnlyFeature ckhof) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hoe",
				ckhof.isHoe(), () -> { ckhof.setHoe(!ckhof.isHoe()); ConfigManager.save(); },
				"Only remaps your keys while holding a Hoe.");
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Fishing Rod",
				ckhof.isFishingRod(), () -> { ckhof.setFishingRod(!ckhof.isFishingRod()); ConfigManager.save(); },
				"Only remaps your keys while holding a Fishing Rod.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HidePestDropsFeature hpdf) {
			drawToggleRow(graphics, x + 10, y + 7, width - 20, "Hide Vinyl Drops",
				hpdf.isHideVinylDrops(), () -> { hpdf.setHideVinylDrops(!hpdf.isHideVinylDrops()); ConfigManager.save(); },
				"Also hides the separate rare vinyl-drop announcement chat message.");
		} else if (feature instanceof PestCooldownFeature pcf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Custom Cooldown",
				pcf.isCustomCooldownEnabled(), () -> { pcf.setCustomCooldownEnabled(!pcf.isCustomCooldownEnabled()); ConfigManager.save(); },
				"Uses your own custom cooldown length below instead of Hypixel's real pest-spawn cooldown.");
			drawSliderRow(graphics, rowX, rowY, rowWidth, "Custom Cooldown Seconds", pcf.getCustomCooldownSeconds() + "s",
				(pcf.getCustomCooldownSeconds() - 1) / (float) (PestCooldownFeature.MAX_CUSTOM_SECONDS - 1),
				v -> { pcf.setCustomCooldownSeconds(Math.round(1 + v * (PestCooldownFeature.MAX_CUSTOM_SECONDS - 1))); ConfigManager.save(); }, accent,
				"Length of the custom cooldown, in seconds.");
		} else if (feature instanceof MoneyPerHourFeature mphf) {
			drawCycleRow(graphics, x + 10, y + 4, width - 20, "Price source", MoneyPerHourFeature.displayName(mphf.getPriceSource()),
				() -> { mphf.cyclePriceSource(); ConfigManager.save(); },
				"Which market prices to use when estimating your coins/hour (Bazaar, Auction House, or NPC sell price).");
		} else if (feature instanceof DungeonClickedBlocksFeature dcbf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			// Toggles before colors, per user request.
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Secret Chime",
				dcbf.isChimeEnabled(), () -> { dcbf.setChimeEnabled(!dcbf.isChimeEnabled()); ConfigManager.save(); },
				"Plays a sound when you pick up a dungeon secret.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Disable Chest Opening Sound",
				dcbf.isMuteChestSound(), () -> { dcbf.setMuteChestSound(!dcbf.isMuteChestSound()); ConfigManager.save(); },
				"Mutes the vanilla sound that plays when you open a secret chest.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Disable Lever Activation Sound",
				dcbf.isMuteLeverSound(), () -> { dcbf.setMuteLeverSound(!dcbf.isMuteLeverSound()); ConfigManager.save(); },
				"Mutes the vanilla sound that plays when you activate a secret lever.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Disable Bat Dying Sound",
				dcbf.isMuteBatSound(), () -> { dcbf.setMuteBatSound(!dcbf.isMuteBatSound()); ConfigManager.save(); },
				"Mutes the vanilla sound that plays when you kill a secret bat.");
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Chest Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "clickedblocks_chest", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dcbf::getChestColor, c -> { dcbf.setChestColor((dcbf.getChestColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Lever Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "clickedblocks_lever", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dcbf::getLeverColor, c -> { dcbf.setLeverColor((dcbf.getLeverColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FFFF);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Wither Essence Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "clickedblocks_wither", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dcbf::getWitherEssenceColor, c -> { dcbf.setWitherEssenceColor((dcbf.getWitherEssenceColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF9955FF);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Item Secret Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "clickedblocks_itemsecret", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dcbf::getItemSecretColor, c -> { dcbf.setItemSecretColor((dcbf.getItemSecretColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			rowY += 8;
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Style", renderStyleLabel(dcbf.getStyle()),
				() -> { dcbf.setStyle(nextRenderStyle(dcbf.getStyle())); ConfigManager.save(); },
				"How the flagged blocks are drawn: outline only, filled, or both.");
			drawSliderRow(graphics, rowX, rowY, rowWidth, "Box Opacity", dcbf.getAlphaPercent() + "%",
				dcbf.getAlphaPercent() / 100f, v -> { dcbf.setAlphaPercent(Math.round(v * 100f)); ConfigManager.save(); }, accent,
				"How see-through the highlight box's fill is.");
		} else if (feature instanceof CroesusFeature cf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			// Toggles before colors, per user request.
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Hide Claimed Chests",
				cf.isHideClaimed(), () -> { cf.setHideClaimed(!cf.isHideClaimed()); ConfigManager.save(); },
				"Stops outlining chests you've already opened.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Highlight Profitable",
				cf.isHighlightProfitable(), () -> { cf.setHighlightProfitable(!cf.isHighlightProfitable()); ConfigManager.save(); },
				"Only highlights chests estimated to be profitable to open.");
			rowY += 8;
			// Same overlap issue as BloodCampFeature's own color pickers (see its doc comment above) — shortened.
			colorPickerLabel(graphics, rowX, rowY, "Best Chest Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "croesus_first", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				cf::getFirstColor, c -> { cf.setFirstColor((cf.getFirstColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF00AA00);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "2nd Best Chest Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "croesus_second", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				cf::getSecondColor, c -> { cf.setSecondColor((cf.getSecondColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFF55);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.SecretsCounterFeature scf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Hide Vanilla Action Bar",
				scf.isHideVanillaActionBar(), () -> { scf.setHideVanillaActionBar(!scf.isHideVanillaActionBar()); ConfigManager.save(); },
				"Hides Hypixel's own action-bar secrets counter, since this module already shows the same information.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Compact Mode",
				scf.isCompactMode(), () -> { scf.setCompactMode(!scf.isCompactMode()); ConfigManager.save(); },
				"Shows a smaller, more condensed version of the readout.");
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Text Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "secrets_counter_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				scf::getTextColor, c -> { scf.setTextColor((scf.getTextColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFFFF);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.NetworkDisplayFeature ndf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Show Ping",
				ndf.isShowPing(), () -> { ndf.setShowPing(!ndf.isShowPing()); ConfigManager.save(); },
				"Shows your real client-measured ping as a movable HUD widget.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show TPS",
				ndf.isShowTps(), () -> { ndf.setShowTps(!ndf.isShowTps()); ConfigManager.save(); },
				"Shows the server's current ticks-per-second as a movable HUD widget.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Bold Text",
				ndf.isBoldText(), () -> { ndf.setBoldText(!ndf.isBoldText()); ConfigManager.save(); },
				"Makes the ping/TPS text bold.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Italic Text",
				ndf.isItalicText(), () -> { ndf.setItalicText(!ndf.isItalicText()); ConfigManager.save(); },
				"Makes the ping/TPS text italic.");
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Ping Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "network_display_ping_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ndf::getPingColor, c -> { ndf.setPingColor((ndf.getPingColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFFFF);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "TPS Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "network_display_tps_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ndf::getTpsColor, c -> { ndf.setTpsColor((ndf.getTpsColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFFFF);
			rowY += 8;
			// Per user request ("Add a color coding option to the tps display. 18-20 is lime green, 15-17 is
			// yellow and 14 and below is red"): overrides the fixed TPS Color above with a live threshold color
			// while on.
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Color Code TPS",
				ndf.isTpsColorCoded(), () -> { ndf.setTpsColorCoded(!ndf.isTpsColorCoded()); ConfigManager.save(); },
				"Colors the TPS number by how healthy it is: lime green at 18-20, yellow at 15-17, red at 14 and below — instead of the fixed TPS Color above.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.BossBarFeature bbf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Vanilla Boss Bar",
				bbf.isHideVanilla(), () -> { bbf.setHideVanilla(!bbf.isHideVanilla()); ConfigManager.save(); },
				"Hides Minecraft's own boss bar, since this module already shows the boss name and HP.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Floor 7 Phase",
				bbf.isShowPhase(), () -> { bbf.setShowPhase(!bbf.isShowPhase()); ConfigManager.save(); },
				"Shows the current Necron's Fortress boss phase (Maxor/Storm/Goldor/Necron) alongside the boss bar.");
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Text Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "boss_bar_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				bbf::getTextColor, c -> { bbf.setTextColor((bbf.getTextColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFFFF);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CustomLoadoutKeybindsFeature clkf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Highlight Selected Loadout",
				clkf.isHighlightSelectedLoadout(), () -> { clkf.setHighlightSelectedLoadout(!clkf.isHighlightSelectedLoadout()); ConfigManager.save(); },
				"Draws a colored highlight over the loadout that's currently equipped, in the Loadouts menu.");
			if (clkf.isHighlightSelectedLoadout()) {
				colorPickerLabel(graphics, rowX, rowY, "Highlight Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "loadout_highlight_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					clkf::getHighlightColor, c -> { clkf.setHighlightColor((clkf.getHighlightColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFFFF);
			}
			rowY += 8;
			// Real bug found (per user report — "when i go in the module itself theres just the subtoggle
			// and no actual buttons for me to select any keybinds"): this feature used to silently reuse the
			// player's vanilla hotbar keys with no way to see or change them at all. One capturable combo row
			// per loadout slot now — see CustomLoadoutKeybindsFeature's own doc comment (round 12).
			for (int i = 0; i < clkf.getLoadoutSlotCount(); i++) {
				rowY = drawComboCaptureRow(graphics, rowX, rowY, rowWidth, "Loadout " + (i + 1), clkf.getLoadoutCombo(i));
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PetKeybindsFeature pkf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			for (int i = 0; i < pkf.getPetSlotCount(); i++) {
				rowY = drawComboCaptureRow(graphics, rowX, rowY, rowWidth, "Pet " + (i + 1), pkf.getPetCombo(i));
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.HideArrowsFeature haf) {
			drawToggleRow(graphics, x + 10, y + 4, width - 20, "Only Ground / In Players",
				haf.isOnlyGroundOrPlayers(), () -> { haf.setOnlyGroundOrPlayers(!haf.isOnlyGroundOrPlayers()); ConfigManager.save(); },
				"Only hides arrows that have already landed (stuck in the ground or in a player), leaving arrows still flying visible.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature dnf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Show Title",
				dnf.isShowTitle(), () -> { dnf.setShowTitle(!dnf.isShowTitle()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Play Sound",
				dnf.isPlaySound(), () -> { dnf.setPlaySound(!dnf.isPlaySound()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Bold Title",
				dnf.isBoldTitle(), () -> { dnf.setBoldTitle(!dnf.isBoldTitle()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Italic Title",
				dnf.isItalicTitle(), () -> { dnf.setItalicTitle(!dnf.isItalicTitle()); ConfigManager.save(); });
			rowY += 4;
			// Per user request: color-code support in title text ('&'/'§' both work) and a "(username)"
			// placeholder for the notifications that have a detected sender (Early Enter, Healer at SS, etc.).
			SmoothTextRenderer.draw(graphics, this.font, "§7Titles support &7/§7 codes and (username)", rowX, rowY, Theme.text(0xFF888888));
			rowY += 12;
			rowY += 8;
			// Per follow-up request ("theres currently a hex picker in dungeon notifications, for the title
			// color even though that is changeable per module now, so remove it"): the old shared "Title
			// Color" full picker is gone — every type already has its own per-type swatch (see
			// drawDungeonNotifTitleRow) since the per-notification color feature shipped, making this one
			// fully redundant.
			for (var type : com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType.values()) {
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, type.displayName,
					dnf.isTypeEnabled(type), () -> { dnf.setTypeEnabled(type, !dnf.isTypeEnabled(type)); ConfigManager.save(); });
				// Per user follow-up (screenshot showing the label sitting well above its own title box, not
				// on top of it): drawToggleRow's own 18px row height is sized for its label+switch, leaving a
				// bigger gap than needed before whatever comes next. A first attempt pulled this up by 4px,
				// which per a later user report ("the title gap is still there but its a little lower") wasn't
				// enough — the toggle switch itself spans y-1..y+11 (see drawToggleRow), so the true bottom of
				// that row's visible content is y+11, not the y+18 drawToggleRow returns; only a 7px pull-up
				// actually closes the gap completely. expandedContentHeight's DungeonNotificationsFeature
				// branch subtracts the same 7px per type to stay in sync.
				rowY = drawDungeonNotifTitleRow(graphics, rowX, rowY - 7, rowWidth, dnf, type);
				if (type == com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType.SCORE_300
					|| type == com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType.SCORE_270
					|| type == com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType.FIVE_CRYPTS) {
					rowY = drawDungeonNotifPartyRow(graphics, rowX, rowY, rowWidth, dnf, type);
				}
				// Per user request ("Remove repeat reminders from the Ultimate Ready notification (fires
				// repeatedly; should fire once)"): Hypixel re-sends the ultimate-ready line as a repeating nag,
				// which used to be collapsed into one title UNLESS this "Repeat Reminders" toggle was turned
				// on — removed entirely so there's no longer an opt-in to bring the spam back; it's always
				// collapsed now (see DungeonNotificationsFeature's own fire-once logic).
				// Per user request ("Add more room between the dungeon notifications separately, they stack
				// currently and need a bit more room"): each type's own row group (toggle + title box, plus a
				// party-message/repeat-reminders row for the types that have one) used to run directly into the
				// next type's toggle row with no breathing room at all — NOTIF_TYPE_GAP below is that missing
				// separation. expandedContentHeight's DungeonNotificationsFeature branch adds the same amount
				// per type to stay in sync.
				rowY += NOTIF_TYPE_GAP;
			}
			// Real bug found (per user report — "I cant change [the per-notification color], clicking the
			// colored squares does nothing"): the popup content for dungeonNotifColorPopupTarget/
			// copilotColorPopupTarget both already lived inside drawEnchantStylePopups, but that method was
			// only ever CALLED from CustomEnchantParsingFeature's own content-drawing branch — with the
			// Dungeon Notifications panel open, drawEnchantStylePopups (and the popupHitsStart* bookkeeping
			// it needs) never ran at all, so the swatch set the target field but nothing was ever drawn or
			// hit-tested for it. Same fix as PositionalMessages' own dropdown popup: trigger it here too,
			// with its own popupHitsStart* snapshot right before drawing.
			if (isStylePopupOpen()) {
				popupHitsStartClick = clickHits.size();
				popupHitsStartColorSquare = colorSquareHits.size();
				popupHitsStartSlider = sliderHits.size();
				popupHitsStartHex = hexFieldBounds.size();
				graphics.disableScissor();
				graphics.disableScissor();
				drawEnchantStylePopups(graphics, accent);
				graphics.enableScissor(listViewportX0, listViewportY0, listViewportX1, listViewportY1);
				graphics.enableScissor(x, y, x + width, y + height);
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature tsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Custom Terminal GUI",
				tsf.isCustomTerminalGui(), () -> { tsf.setCustomTerminalGui(!tsf.isCustomTerminalGui()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Block Wrong Clicks",
				tsf.isBlockWrongClicks(), () -> { tsf.setBlockWrongClicks(!tsf.isBlockWrongClicks()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Middle Click Redirect",
				tsf.isMiddleClickRedirect(), () -> { tsf.setMiddleClickRedirect(!tsf.isMiddleClickRedirect()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Click Animations",
				tsf.isClickAnimations(), () -> { tsf.setClickAnimations(!tsf.isClickAnimations()); ConfigManager.save(); });
			// Moved to sit right under Click Animations instead of between two unrelated toggle groups —
			// per user request, three toggles / a selector / another toggle read oddly in a row. Still only
			// shown while Custom Terminal GUI is on (see expandedContentHeight for the matching height math),
			// since it has nothing to size without that panel existing.
			if (tsf.isCustomTerminalGui()) {
				rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "GUI Scale",
					com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature.guiScaleLabel(tsf.getGuiScale()),
					() -> { tsf.cycleGuiScale(); ConfigManager.save(); });
			}
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "First Click Protection", tsf.getFirstClickProtectionMs() + "ms",
				tsf.getFirstClickProtectionMs() / 2000f, v -> { tsf.setFirstClickProtectionMs(Math.round(v * 2000f)); ConfigManager.save(); }, accent);
			rowY += 4;
			// Panes/Starts With/Select unified into one shared color per user request — all three are just
			// "highlight the correct slot(s)", nothing about them individually needed a distinct color.
			colorPickerLabel(graphics, rowX, rowY, "Terminal Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "tsf_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				tsf::getTerminalColor, c -> { tsf.setTerminalColor((tsf.getTerminalColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature lmf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Sort Mode", leapSortModeLabel(lmf.getSortMode()),
				() -> { cycleLeapSortMode(lmf); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Only Show Class",
				lmf.isOnlyShowClass(), () -> { lmf.setOnlyShowClass(!lmf.isOnlyShowClass()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Color Style",
				lmf.isColorStyle(), () -> { lmf.setColorStyle(!lmf.isColorStyle()); ConfigManager.save(); });
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Render Scale", String.format(Locale.ROOT, "%.1fx", lmf.getRenderScale()),
				(lmf.getRenderScale() - 0.1f) / 1.9f, v -> { lmf.setRenderScale(0.1f + v * 1.9f); ConfigManager.save(); }, accent);
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Announce Leap",
				lmf.isLeapAnnounce(), () -> { lmf.setLeapAnnounce(!lmf.isLeapAnnounce()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Sync with Map",
				lmf.isSyncWithMap(), () -> { lmf.setSyncWithMap(!lmf.isSyncWithMap()); ConfigManager.save(); });
			rowY += 8;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Background Opacity", Math.round(lmf.getBackgroundOpacity() * 100) + "%",
				lmf.getBackgroundOpacity(), v -> { lmf.setBackgroundOpacity(v); ConfigManager.save(); }, accent);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Background Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "leapmenu_bg", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				lmf::getBackgroundColor, c -> { lmf.setBackgroundColor((lmf.getBackgroundColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF101010);
			rowY += 8;
			drawKeybindRows(graphics, x, y, width, height, rowY, lmf.getKeybinds());
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonQueueFeature dqf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Announce Kick",
				dqf.isAnnounceKick(), () -> { dqf.setAnnounceKick(!dqf.isAnnounceKick()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Auto Requeue",
				dqf.isAutoRequeue(), () -> { dqf.setAutoRequeue(!dqf.isAutoRequeue()); ConfigManager.save(); });
			if (dqf.isAutoRequeue()) {
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Requeue Delay", dqf.getRequeueDelaySeconds() + "s",
					dqf.getRequeueDelaySeconds() / 30f, v -> { dqf.setRequeueDelaySeconds(Math.round(v * 30f)); ConfigManager.save(); }, accent);
				// Per user request ("make sure it does NOT trigger if the !dt command is used during the run"):
				// this is exactly what disableOnPartyLeave's sibling, ChatCommandsFeature's own !dt/!downtime
				// handler, already does via DungeonQueueFeature.setDisableRequeue() — see that class's own doc
				// comment on isDisableRequeue()/setDisableRequeue(). Nothing more to wire for !dt specifically;
				// this toggle is the same "disable this run's requeue" mechanism, just for a party-leave instead.
				drawToggleRow(graphics, rowX, rowY, rowWidth, "Disable on leave/kick",
					dqf.isDisableOnPartyLeave(), () -> { dqf.setDisableOnPartyLeave(!dqf.isDisableOnPartyLeave()); ConfigManager.save(); });
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature dmf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Outline",
				dmf.isOutlineEnabled(), () -> { dmf.setOutlineEnabled(!dmf.isOutlineEnabled()); ConfigManager.save(); });
			if (dmf.isOutlineEnabled()) {
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Outline Thickness", String.valueOf(dmf.getOutlineThickness()),
					(dmf.getOutlineThickness() - 1) / 9f, v -> { dmf.setOutlineThickness(1 + Math.round(v * 9f)); ConfigManager.save(); }, accent);
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Outline Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "dungeon_map_outline", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					dmf::getOutlineColor, c -> { dmf.setOutlineColor((dmf.getOutlineColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF000000);
				rowY += 8;
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Background",
				dmf.isBackgroundEnabled(), () -> { dmf.setBackgroundEnabled(!dmf.isBackgroundEnabled()); ConfigManager.save(); });
			if (dmf.isBackgroundEnabled()) {
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Background Opacity", Math.round(dmf.getBackgroundOpacity() * 100) + "%",
					dmf.getBackgroundOpacity(), v -> { dmf.setBackgroundOpacity(v); ConfigManager.save(); }, accent);
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Background Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "dungeon_map_bg", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					dmf::getBackgroundColor, c -> { dmf.setBackgroundColor(c); ConfigManager.save(); }, 0x101010);
				rowY += 8;
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Player Heads Instead of Arrows",
				dmf.isPlayerHeads(), () -> { dmf.setPlayerHeads(!dmf.isPlayerHeads()); ConfigManager.save(); });
			// Per explicit user request/informed choice — see DungeonMapFeature.revealUnexploredRooms' own
			// doc comment for the risk discussion this came out of. Off by default.
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Reveal Unexplored Rooms (use at own risk)",
				dmf.isRevealUnexploredRooms(), () -> { dmf.setRevealUnexploredRooms(!dmf.isRevealUnexploredRooms()); ConfigManager.save(); });
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Show Names", showNamesModeLabel(dmf.getShowNamesMode()),
				() -> { cycleShowNamesMode(dmf); ConfigManager.save(); });
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Room Label", roomLabelModeLabel(dmf.getRoomLabelMode()),
				() -> { cycleRoomLabelMode(dmf); ConfigManager.save(); });
			// Per user request ("New subtoggle: Map info... turning it on shows more subtoggles based on what
			// the user wants to display").
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Map Info",
				dmf.isMapInfoEnabled(), () -> { dmf.setMapInfoEnabled(!dmf.isMapInfoEnabled()); ConfigManager.save(); });
			if (dmf.isMapInfoEnabled()) {
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Crypts",
					dmf.isMapInfoShowCrypts(), () -> { dmf.setMapInfoShowCrypts(!dmf.isMapInfoShowCrypts()); ConfigManager.save(); });
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Deaths",
					dmf.isMapInfoShowDeaths(), () -> { dmf.setMapInfoShowDeaths(!dmf.isMapInfoShowDeaths()); ConfigManager.save(); });
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Mimic Status",
					dmf.isMapInfoShowMimic(), () -> { dmf.setMapInfoShowMimic(!dmf.isMapInfoShowMimic()); ConfigManager.save(); });
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Map Info Text Color");
				drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "dungeon_map_info_text", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					dmf::getMapInfoTextColor, c -> { dmf.setMapInfoTextColor((dmf.getMapInfoTextColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFFFF);
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonScoreCalculatorFeature dscf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Only in Dungeons",
				dscf.isOnlyInDungeons(), () -> { dscf.setOnlyInDungeons(!dscf.isOnlyInDungeons()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Auto-detect Mayor Perk",
				dscf.isAutoDetectMayorPerk(), () -> { dscf.setAutoDetectMayorPerk(!dscf.isAutoDetectMayorPerk()); ConfigManager.save(); });
			rowY += 4;
			String mayorNote = dscf.mayorStatusText();
			SmoothTextRenderer.draw(graphics, this.font, mayorNote, rowX, rowY, Theme.text(0xFF888888));
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature cef) {
			// Called every frame the cog is open (see the feature's own doc comment on refreshStats) — request()
			// is idempotent once cached/in-flight so this is safe unconditionally, same pattern PartyFinder uses.
			cef.refreshStats();
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;

			com.cokelord.skyblocksimplified.api.SkyblockStatsApi.PlayerStats stats = cef.stats();
			com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature.Result result = cef.computeResult();
			if (stats == null || result == null) {
				SmoothTextRenderer.draw(graphics, this.font, "Loading Hypixel API data...", rowX, rowY, Theme.text(0xFF888888));
			} else {
				SmoothTextRenderer.draw(graphics, this.font, String.format("Catacombs Level %d (%,.0f / %,d XP to next)",
					result.currentLevel(), result.currentLevelProgressXp(), result.currentLevelXpForNext()), rowX, rowY, Theme.text(0xFFDDDDDD));
			}
			rowY += 14;
			// Per user report ("Catacombs expert ring still isnt being detected... just add a checkbox for
			// owning the catacombs expert ring instead"): the live API-based detection was unreliable in
			// practice, so ring ownership is now a plain manual toggle — no longer gated behind stats/result
			// having loaded at all, unlike the block above. Real bug fixed alongside this: Derpy Mayor status
			// (isDerpyXpBoostActive()) only ever reads HypixelElectionApi's own mayor cache, not player stats,
			// so it never needed to wait on stats() either — it used to sit inside the stats-loaded branch above
			// and would just show nothing until the (sometimes very slow/broken) player-stats fetch resolved.
			rowY = drawColoredToggleRow(graphics, rowX, rowY, rowWidth, "catacombs_expert_ring", "Catacombs Expert Ring", RARITY_EPIC,
				cef.isOwnsExpertRing(), () -> { cef.setOwnsExpertRing(!cef.isOwnsExpertRing()); ConfigManager.save(); });
			rowY = drawCatacombsStatusLine(graphics, rowX, rowY, "Derpy Mayor (MOAR SKILLZ!!!)", RARITY_MYTHIC, cef.isDerpyXpBoostActive());
			SmoothTextRenderer.draw(graphics, this.font, "Hecatomb Level: " + (stats != null ? stats.maxHecatombLevel() : "-"), rowX, rowY, Theme.text(0xFFFF5555));
			rowY += 14;
			rowY += 4;

			SmoothTextRenderer.draw(graphics, this.font, "Bonzo's Shard Level (0-10, blank = 0)", rowX, rowY, Theme.text(RARITY_EPIC));
			rowY = drawGenericTextField(graphics, "catacombs_bonzo_shard", rowX, rowY + 12, rowWidth, 16,
				"0", cef::getBonzoShardInput, cef::setBonzoShardInput) + 6;

			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Floor", cef.getFloor().label,
				() -> { cycleCatacombsFloor(cef); ConfigManager.save(); });
			rowY += 4;

			SmoothTextRenderer.draw(graphics, this.font, "Target Catacombs Level", rowX, rowY, Theme.text(0xFFAAAAAA));
			rowY = drawGenericTextField(graphics, "catacombs_target_level", rowX, rowY + 12, rowWidth, 16,
				"50", cef::getTargetLevelInput, cef::setTargetLevelInput) + 6;

			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Include First-30-Minutes Boost (+50%)",
				cef.isIncludeEarlyBoost(), () -> { cef.setIncludeEarlyBoost(!cef.isIncludeEarlyBoost()); ConfigManager.save(); });

			if (cef.isIncludeEarlyBoost()) {
				SmoothTextRenderer.draw(graphics, this.font, "Average Run Length (min / sec)", rowX, rowY + 4, Theme.text(0xFFAAAAAA));
				int fieldGap = 6;
				int fieldWidth = (rowWidth - fieldGap) / 2;
				drawGenericTextField(graphics, "catacombs_run_minutes", rowX, rowY + 16, fieldWidth, 16,
					"5", cef::getRunMinutesInput, cef::setRunMinutesInput);
				drawGenericTextField(graphics, "catacombs_run_seconds", rowX + fieldWidth + fieldGap, rowY + 16, fieldWidth, 16,
					"0", cef::getRunSecondsInput, cef::setRunSecondsInput);
				rowY += 16 + 16 + 6;
			}
			rowY += 6;

			if (result == null) {
				SmoothTextRenderer.draw(graphics, this.font, "Waiting for Hypixel API data to load...", rowX, rowY, Theme.text(0xFF888888));
			} else {
				SmoothTextRenderer.draw(graphics, this.font, String.format("Total Bonus: +%.2f%% Catacombs EXP", result.multiplierPercent()),
					rowX, rowY, Theme.text(accent));
				rowY += 13;
				SmoothTextRenderer.draw(graphics, this.font, String.format("EXP Needed: %,.0f", result.xpNeeded()), rowX, rowY, Theme.text(0xFFCCCCCC));
				rowY += 13;
				SmoothTextRenderer.draw(graphics, this.font, String.format("EXP per Run: %,.0f normal / %,.0f early-boosted",
					result.xpPerNormalRun(), result.xpPerEarlyRun()), rowX, rowY, Theme.text(0xFFCCCCCC));
				rowY += 13;
				SmoothTextRenderer.draw(graphics, this.font, "Estimated Runs Needed: " + result.runsNeeded(), rowX, rowY, Theme.text(0xFFFFFF55));
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonTimersFeature dtf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Storm Purple Pad Timer",
				dtf.isStormPadTimerEnabled(), () -> { dtf.setStormPadTimerEnabled(!dtf.isStormPadTimerEnabled()); ConfigManager.save(); },
				"Countdown timer for the Storm boss's purple pad phase.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Terminals Timer",
				dtf.isTerminalsTimerEnabled(), () -> { dtf.setTerminalsTimerEnabled(!dtf.isTerminalsTimerEnabled()); ConfigManager.save(); },
				"Countdown timer for the F7 device/terminal phase starting.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Goldor Start Timer",
				dtf.isGoldorStartTimerEnabled(), () -> { dtf.setGoldorStartTimerEnabled(!dtf.isGoldorStartTimerEnabled()); ConfigManager.save(); },
				"Countdown timer for when Goldor's phase starts.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Goldor Early-Enter Loop",
				dtf.isGoldorLoopEnabled(), () -> { dtf.setGoldorLoopEnabled(!dtf.isGoldorLoopEnabled()); ConfigManager.save(); },
				"Repeats a reminder for early-entering Goldor's core in a loop until it's actually done.");
			if (dtf.isGoldorLoopEnabled()) {
				rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Goldor Loop Style", goldorLoopStyleLabel(dtf.getGoldorLoopStyle()),
					() -> { dtf.cycleGoldorLoopStyle(); ConfigManager.save(); },
					"How the Goldor early-enter reminder is delivered (title, chat, or both).");
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Necron Drop Timer",
				dtf.isNecronWarningEnabled(), () -> { dtf.setNecronWarningEnabled(!dtf.isNecronWarningEnabled()); ConfigManager.save(); },
				"Countdown timer warning before Necron's next lava drop.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Maxor Crystal Timer",
				dtf.isMaxorCrystalTimerEnabled(), () -> { dtf.setMaxorCrystalTimerEnabled(!dtf.isMaxorCrystalTimerEnabled()); ConfigManager.save(); },
				"Countdown timer for Maxor's crystal phase.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Bold Text",
				dtf.isBoldText(), () -> { dtf.setBoldText(!dtf.isBoldText()); ConfigManager.save(); },
				"Makes the timer text bold.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Italic Text",
				dtf.isItalicText(), () -> { dtf.setItalicText(!dtf.isItalicText()); ConfigManager.save(); },
				"Makes the timer text italic.");
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Storm Lightning Sync",
				dtf.isStormLightningSyncEnabled(), () -> { dtf.setStormLightningSyncEnabled(!dtf.isStormLightningSyncEnabled()); ConfigManager.save(); },
				"Shows a countdown synced to Storm's lightning strikes, and hides the vanilla title for it.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf) {
			drawDungeonsCopilotContent(graphics, dcf, x, y, width, accent, mouseX, mouseY);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf) {
			drawDungeonRoutesContent(graphics, drf, x, y, width, height, accent, mouseX, mouseY);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature caf) {
			drawCommandAliasesContent(graphics, caf, x, y, width, accent);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature csf) {
			drawCommandShortcutsContent(graphics, csf, x, y, width, accent);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ChestRollingFeature crf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Only Bedrock Chests",
				crf.isOnlyBedrock(), () -> { crf.setOnlyBedrock(!crf.isOnlyBedrock()); ConfigManager.save(); });
			if (crf.isOnlyBedrock()) {
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Obsidian Chests Too on Lower Floors",
					crf.isObsidianOnLowerFloors(), () -> { crf.setObsidianOnLowerFloors(!crf.isObsidianOnLowerFloors()); ConfigManager.save(); });
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Only Croesus",
				crf.isOnlyCroesus(), () -> { crf.setOnlyCroesus(!crf.isOnlyCroesus()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Obfuscate Rare Items",
				crf.isObfuscateRareItems(), () -> { crf.setObfuscateRareItems(!crf.isObfuscateRareItems()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Lore",
				crf.isHideLore(), () -> { crf.setHideLore(!crf.isHideLore()); ConfigManager.save(); },
				"Hides the tooltip on the highest chest tier present (Bedrock, else Obsidian) so you can't see its contents before rolling.");
			if (crf.isHideLore()) {
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Lore on All Chests",
					crf.isHideLoreAllChests(), () -> { crf.setHideLoreAllChests(!crf.isHideLoreAllChests()); ConfigManager.save(); },
					"Hides every unopened chest option's tooltip, not just the highest tier — for rolling every chest.");
			}
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Roll Duration",
				String.format(Locale.ROOT, "%.1fs", crf.getAnimationDurationSeconds()),
				(crf.getAnimationDurationSeconds() - 0.5f) / 9.5f,
				v -> { crf.setAnimationDurationSeconds(0.5f + v * 9.5f); ConfigManager.save(); }, accent);
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Landing Sound",
				crf.isLandSoundEnabled(), () -> { crf.setLandSoundEnabled(!crf.isLandSoundEnabled()); ConfigManager.save(); },
				"Plays a sound when the roll lands on its final drop.");
			if (crf.isLandSoundEnabled()) {
				rowY += 4;
				drawSoundOptionRows(graphics, rowX, rowY, rowWidth, accent, crf.getLandSound());
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature vwf) {
			drawVisualWordsContent(graphics, vwf, x, y, width, accent);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatCommandsFeature ccf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Chat Emotes", ccf.isChatEmotes(), () -> { ccf.setChatEmotes(!ccf.isChatEmotes()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Party Commands", ccf.isPartyChatCommands(), () -> { ccf.setPartyChatCommands(!ccf.isPartyChatCommands()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Guild Commands", ccf.isGuildChatCommands(), () -> { ccf.setGuildChatCommands(!ccf.isGuildChatCommands()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Private Commands", ccf.isPrivateChatCommands(), () -> { ccf.setPrivateChatCommands(!ccf.isPrivateChatCommands()); ConfigManager.save(); });
			rowY += 6;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!coords", ccf.isCoords(), () -> { ccf.setCoords(!ccf.isCoords()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!boop", ccf.isBoop(), () -> { ccf.setBoop(!ccf.isBoop()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!cf (coinflip)", ccf.isCoinFlip(), () -> { ccf.setCoinFlip(!ccf.isCoinFlip()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!8ball", ccf.isEightBall(), () -> { ccf.setEightBall(!ccf.isEightBall()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!dice", ccf.isDice(), () -> { ccf.setDice(!ccf.isDice()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!racism", ccf.isRacism(), () -> { ccf.setRacism(!ccf.isRacism()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!ping", ccf.isPing(), () -> { ccf.setPing(!ccf.isPing()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!fps", ccf.isFps(), () -> { ccf.setFps(!ccf.isFps()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!time", ccf.isTime(), () -> { ccf.setTime(!ccf.isTime()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!location", ccf.isLocation(), () -> { ccf.setLocation(!ccf.isLocation()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!holding", ccf.isHolding(), () -> { ccf.setHolding(!ccf.isHolding()); ConfigManager.save(); });
			rowY += 6;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!warp (party)", ccf.isPartyWarp(), () -> { ccf.setPartyWarp(!ccf.isPartyWarp()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!allinvite (party)", ccf.isPartyAllInvite(), () -> { ccf.setPartyAllInvite(!ccf.isPartyAllInvite()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!pt / transfer (party)", ccf.isPartyTransfer(), () -> { ccf.setPartyTransfer(!ccf.isPartyTransfer()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!promote (party)", ccf.isPartyPromote(), () -> { ccf.setPartyPromote(!ccf.isPartyPromote()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!demote (party)", ccf.isPartyDemote(), () -> { ccf.setPartyDemote(!ccf.isPartyDemote()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!kick (party)", ccf.isKick(), () -> { ccf.setKick(!ccf.isKick()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!kickoffline (party)", ccf.isKickOffline(), () -> { ccf.setKickOffline(!ccf.isKickOffline()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!downtime / !undowntime (party)", ccf.isDt(), () -> { ccf.setDt(!ccf.isDt()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!reinvite (party)", ccf.isReinvite(), () -> { ccf.setReinvite(!ccf.isReinvite()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!f1-f7/!m1-m7/!t1-t5 (party)", ccf.isQueInstance(), () -> { ccf.setQueInstance(!ccf.isQueInstance()); ConfigManager.save(); });
			rowY += 6;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "!invite (private)", ccf.isInvite(), () -> { ccf.setInvite(!ccf.isInvite()); ConfigManager.save(); });
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Auto Confirm Invite (private)", ccf.isAutoConfirm(), () -> { ccf.setAutoConfirm(!ccf.isAutoConfirm()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.InvincibilityTimerFeature itf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Spirit Mask", itf.isShowSpirit(), () -> { itf.setShowSpirit(!itf.isShowSpirit()); ConfigManager.save(); },
				"Tracks the Spirit Mask's second-life active/cooldown state.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Bonzo's Mask", itf.isShowBonzo(), () -> { itf.setShowBonzo(!itf.isShowBonzo()); ConfigManager.save(); },
				"Tracks Bonzo's Mask's second-life active/cooldown state.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Phoenix Pet", itf.isShowPhoenix(), () -> { itf.setShowPhoenix(!itf.isShowPhoenix()); ConfigManager.save(); },
				"Tracks the Phoenix Pet's second-life active/cooldown state.");
			rowY += 6;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Alert (chat + sound)", itf.isInvincibilityAlert(), () -> { itf.setInvincibilityAlert(!itf.isInvincibilityAlert()); ConfigManager.save(); },
				"Plays a sound and sends a chat message the moment a second-life proc triggers.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Announce to Party", itf.isInvincibilityAnnounce(), () -> { itf.setInvincibilityAnnounce(!itf.isInvincibilityAnnounce()); ConfigManager.save(); },
				"Sends a party chat message when your second-life item procs, so your team knows.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Only in Dungeons", itf.isOnlyInDungeons(), () -> { itf.setOnlyInDungeons(!itf.isOnlyInDungeons()); ConfigManager.save(); },
				"Only tracks and shows this while you're in a dungeon.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Only in Boss Fight", itf.isShowOnlyInBoss(), () -> { itf.setShowOnlyInBoss(!itf.isShowOnlyInBoss()); ConfigManager.save(); },
				"Only shows the readout during a boss fight.");
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Show as Title", itf.isShowTitle(), () -> { itf.setShowTitle(!itf.isShowTitle()); ConfigManager.save(); },
				"Also shows the countdown as a large on-screen title, not just the HUD widget.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.AbilityCooldownTimerFeature actf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			drawToggleRow(graphics, rowX, y + 4, rowWidth, "Shrinking Overlay (vanilla-style)",
				actf.isShrinkingOverlay(), () -> { actf.setShrinkingOverlay(!actf.isShrinkingOverlay()); ConfigManager.save(); },
				"Uses a shrinking-pie overlay on the hotbar item, matching vanilla's own item-cooldown look, instead of a plain countdown number.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PetDisplayFeature pdf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			drawToggleRow(graphics, rowX, y + 4, rowWidth, "Compact Mode (Icon Only)",
				pdf.isCompact(), () -> { pdf.setCompact(!pdf.isCompact()); ConfigManager.save(); },
				"Shows only the pet's icon, without its name text.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.AutoKickFeature akf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Min PB Minutes", String.valueOf(akf.getMinPbMinutes()),
				akf.getMinPbMinutes() / 10f, v -> { akf.setMinPbMinutes(Math.round(v * 10)); ConfigManager.save(); }, accent,
				"Minimum personal-best minutes required to stay in the party.");
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Min PB Seconds", String.valueOf(akf.getMinPbSeconds()),
				akf.getMinPbSeconds() / 59f, v -> { akf.setMinPbSeconds(Math.round(v * 59)); ConfigManager.save(); }, accent,
				"Extra seconds added to the minimum personal-best requirement above.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Require Hyperion", akf.isRequireHyperion(),
				() -> { akf.setRequireHyperion(!akf.isRequireHyperion()); ConfigManager.save(); },
				"A joiner must own a Hyperion to stay in the party.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Require Terminator", akf.isRequireTerminator(),
				() -> { akf.setRequireTerminator(!akf.isRequireTerminator()); ConfigManager.save(); },
				"A joiner must own a Terminator to stay in the party.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Require Golden Dragon", akf.isRequireGoldenDragon(),
				() -> { akf.setRequireGoldenDragon(!akf.isRequireGoldenDragon()); ConfigManager.save(); },
				"A joiner must own a Golden Dragon armor set to stay in the party.");
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Require 1B+ in Bank", akf.isRequireBillionBank(),
				() -> { akf.setRequireBillionBank(!akf.isRequireBillionBank()); ConfigManager.save(); },
				"A joiner must have at least 1 billion coins in their bank to stay in the party.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PartyFinderFeature pff) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Total Secrets", pff.isShowTotalSecrets(),
				() -> { pff.setShowTotalSecrets(!pff.isShowTotalSecrets()); ConfigManager.save(); }, "Shows the joiner's total lifetime secrets found.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Secrets/Run", pff.isShowSecretsPerRun(),
				() -> { pff.setShowSecretsPerRun(!pff.isShowSecretsPerRun()); ConfigManager.save(); }, "Shows the joiner's average secrets found per run.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hyperion", pff.isShowHyperion(),
				() -> { pff.setShowHyperion(!pff.isShowHyperion()); ConfigManager.save(); }, "Shows whether the joiner owns a Hyperion.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Terminator", pff.isShowTerminator(),
				() -> { pff.setShowTerminator(!pff.isShowTerminator()); ConfigManager.save(); }, "Shows whether the joiner owns a Terminator.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Golden Dragon", pff.isShowGoldenDragon(),
				() -> { pff.setShowGoldenDragon(!pff.isShowGoldenDragon()); ConfigManager.save(); }, "Shows whether the joiner owns a Golden Dragon armor set.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "1B+ in Bank", pff.isShowBillionInBank(),
				() -> { pff.setShowBillionInBank(!pff.isShowBillionInBank()); ConfigManager.save(); }, "Shows whether the joiner has 1 billion+ coins in their bank.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Armor List", pff.isShowArmor(),
				() -> { pff.setShowArmor(!pff.isShowArmor()); ConfigManager.save(); }, "Lists the joiner's currently equipped armor pieces.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Equipment List", pff.isShowEquipment(),
				() -> { pff.setShowEquipment(!pff.isShowEquipment()); ConfigManager.save(); }, "Lists the joiner's currently equipped Cloak/Necklace/Belt/Gloves.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "F7 Completions", pff.isShowF7Completions(),
				() -> { pff.setShowF7Completions(!pff.isShowF7Completions()); ConfigManager.save(); }, "Shows the joiner's Floor 7 completion count.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "M5 Completions", pff.isShowM5Completions(),
				() -> { pff.setShowM5Completions(!pff.isShowM5Completions()); ConfigManager.save(); }, "Shows the joiner's Master 5 completion count.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "M6 Completions", pff.isShowM6Completions(),
				() -> { pff.setShowM6Completions(!pff.isShowM6Completions()); ConfigManager.save(); }, "Shows the joiner's Master 6 completion count.");
			drawToggleRow(graphics, rowX, rowY, rowWidth, "M7 Completions", pff.isShowM7Completions(),
				() -> { pff.setShowM7Completions(!pff.isShowM7Completions()); ConfigManager.save(); }, "Shows the joiner's Master 7 completion count.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature sof) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Columns", String.valueOf(sof.getColumns()),
				(sof.getColumns() - 1) / 5f, v -> { sof.setColumns(Math.round(1 + v * 5)); ConfigManager.save(); }, accent,
				"How many cards are shown per row in the overlay grid.");
			// Per user request ("Allow users to also resize the storage overlay. The current size should be
			// 4x gui scale, but add 3x and 2x aswell since its a bit too big for my liking").
			// Real bug found (per user report — "The gui scaling on the storage overlay go backwards. It goes
			// from 4-3-2 instead of going from 4-2-3 for example"): cycled descending (4->3->2->4...) before —
			// now ascending (2->3->4->2...), a plain wrap-forward instead of wrap-backward.
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Size", sof.getSizeScale() + "x",
				() -> { sof.setSizeScale(sof.getSizeScale() >= 4 ? 2 : sof.getSizeScale() + 1); ConfigManager.save(); },
				"Overall size of the overlay panel and its cards.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Retain Scroll",
				sof.isRetainScroll(), () -> { sof.setRetainScroll(!sof.isRetainScroll()); ConfigManager.save(); },
				"Remembers your scroll position between openings instead of resetting to the top each time.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Background",
				sof.isShowBackground(), () -> { sof.setShowBackground(!sof.isShowBackground()); ConfigManager.save(); },
				"Draws a solid background panel behind the overlay grid.");
			drawCycleRow(graphics, rowX, rowY, rowWidth, "Inventory Position", storageOverlayInventoryCornerLabel(sof.getInventoryCorner()),
				() -> { cycleStorageOverlayInventoryCorner(sof); ConfigManager.save(); },
				"Which corner of the screen your own inventory is anchored to while this overlay is open.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.LoadoutOverlayFeature lof) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Columns", String.valueOf(lof.getColumns()),
				(lof.getColumns() - 1) / 5f, v -> { lof.setColumns(Math.round(1 + v * 5)); ConfigManager.save(); }, accent,
				"How many cards are shown per row in the overlay grid.");
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Size", lof.getSizeScale() + "x",
				() -> { lof.setSizeScale(lof.getSizeScale() >= 4 ? 2 : lof.getSizeScale() + 1); ConfigManager.save(); },
				"Overall size of the overlay panel and its cards.");
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Background",
				lof.isShowBackground(), () -> { lof.setShowBackground(!lof.isShowBackground()); ConfigManager.save(); },
				"Draws a solid background panel behind the overlay grid.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MageBeamFeature mbf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			// Settings before colors, per user request.
			int rowY = drawSliderRow(graphics, rowX, y + 4, rowWidth, "Opacity", Math.round(mbf.getOpacity() * 100) + "%",
				mbf.getOpacity(), v -> { mbf.setOpacity(v); ConfigManager.save(); }, accent, "How see-through the beam line is.");
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Thickness", String.valueOf(mbf.getThickness()),
				(mbf.getThickness() - 1) / 19f, v -> { mbf.setThickness(Math.round(1 + v * 19f)); ConfigManager.save(); }, accent,
				"How thick the beam line is drawn, in pixels.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Vanilla Particles",
				mbf.isHideParticles(), () -> { mbf.setHideParticles(!mbf.isHideParticles()); ConfigManager.save(); },
				"Hides Hypixel's own scattered firework-particle beam effect, leaving only the clean line.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Depth Check",
				mbf.isDepthCheck(), () -> { mbf.setDepthCheck(!mbf.isDepthCheck()); ConfigManager.save(); },
				"Makes the beam line hide behind walls/terrain like a real object instead of always drawing on top.");
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Beam Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "mage_beam_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				mbf::getColor, c -> { mbf.setColor(c); ConfigManager.save(); }, 0xFFAA0000);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.SlotBindsFeature sbf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;

			boolean listening = listeningForRawKey != null;
			String keyLabel = listening ? "Press a key..." : (sbf.getBindSetKey() == GLFW.GLFW_KEY_UNKNOWN
				? "NONE" : InputConstants.Type.KEYSYM.getOrCreate(sbf.getBindSetKey()).getDisplayName().getString());
			SmoothTextRenderer.draw(graphics, this.font, "Bind-Set Key", rowX, rowY, Theme.text(0xFFAAAAAA));
			int keyBoxWidth = Math.max(70, this.font.width(keyLabel) + 10);
			int keyBoxX = rowX + rowWidth - keyBoxWidth;
			fillRounded(graphics, keyBoxX, rowY - 1, keyBoxX + keyBoxWidth, rowY + 13, SMALL_RADIUS, listening ? accent : Theme.chrome(0xFF2A2A2A));
			SmoothTextRenderer.draw(graphics, this.font, keyLabel, keyBoxX + (keyBoxWidth - this.font.width(keyLabel)) / 2, rowY + 2, Theme.text(0xFFFFFFFF));
			clickHits.add(new ClickHit(keyBoxX, rowY - 1, keyBoxWidth, 14, () ->
				listeningForRawKey = keyCode -> { sbf.setBindSetKey(keyCode); ConfigManager.save(); }));
			rowY += 18 + 8;

			// Settings before colors, per user request.
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Line Display", slotBindsLineModeLabel(sbf.getLineDisplayMode()),
				() -> { cycleSlotBindsLineMode(sbf); ConfigManager.save(); },
				"When to draw the connector line between two bound slots: always, only while hovering one, or never.");
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Profile", "Profile " + (sbf.getCurrentProfile() + 1),
				() -> { sbf.setCurrentProfile((sbf.getCurrentProfile() + 1) % 6); ConfigManager.save(); },
				"Switches between 6 independent sets of slot bindings, so different loadouts/classes can each have their own.");
			rowY += 4;
			colorPickerLabel(graphics, rowX, rowY, "Connector Line Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "slotbinds_line", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				sbf::getLineColor, c -> { sbf.setLineColor((sbf.getLineColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Bound Slot Outline Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "slotbinds_outline", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				sbf::getOutlineColor, c -> { sbf.setOutlineColor((sbf.getOutlineColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.EtherwarpFeature ewf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			// Settings before colors, per user request.
			int rowY = drawCycleRow(graphics, rowX, y + 4, rowWidth, "Style", etherwarpStyleLabel(ewf.getStyle()),
				() -> { cycleEtherwarpStyle(ewf); ConfigManager.save(); },
				"How the Etherwarp landing preview is drawn (outline, filled block, etc.).");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show When Failed",
				ewf.isShowWhenFailed(), () -> { ewf.setShowWhenFailed(!ewf.isShowWhenFailed()); ConfigManager.save(); },
				"Still shows a preview (in the fail color) even when you're not currently able to Etherwarp there.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Full Block (ignore hitbox shape)",
				ewf.isFullBlock(), () -> { ewf.setFullBlock(!ewf.isFullBlock()); ConfigManager.save(); },
				"Draws the preview as a full cube, ignoring the target block's real (possibly smaller) hitbox shape.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Success Sound",
				ewf.isSuccessSoundEnabled(), () -> { ewf.setSuccessSoundEnabled(!ewf.isSuccessSoundEnabled()); ConfigManager.save(); },
				"Plays a sound when you successfully land an Etherwarp.");
			if (ewf.isSuccessSoundEnabled()) {
				rowY = drawSoundOptionRows(graphics, rowX, rowY, rowWidth, accent, ewf.getSuccessSound());
			}
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Success Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "etherwarp_succeed", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ewf::getSucceedColor, c -> { ewf.setSucceedColor((ewf.getSucceedColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xD9FFAA00);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Fail Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "etherwarp_fail", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ewf::getFailColor, c -> { ewf.setFailColor((ewf.getFailColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xD9FF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowHitSoundFeature ahsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			drawSoundOptionRows(graphics, rowX, y + 4, rowWidth, accent, ahsf.getSound());
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MimicFeature mf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Announce Mimic Kill",
				mf.isMimicMessageEnabled(), () -> { mf.setMimicMessageEnabled(!mf.isMimicMessageEnabled()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Announce Prince Kill",
				mf.isPrinceMessageEnabled(), () -> { mf.setPrinceMessageEnabled(!mf.isPrinceMessageEnabled()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Announce Bat Kill",
				mf.isBatMessageEnabled(), () -> { mf.setBatMessageEnabled(!mf.isBatMessageEnabled()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Mimic Chest",
				mf.isShowMimicChest(), () -> { mf.setShowMimicChest(!mf.isShowMimicChest()); ConfigManager.save(); });
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Mimic Chest Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "mimic_chest", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				mf::getMimicChestColor, c -> { mf.setMimicChestColor((mf.getMimicChestColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MelodyMessageFeature mmf) {
			// Real bug found (per user report — "The melody messages do not seem to work"): this settings
			// panel never existed at all, so `sendProgress` (defaulted off) and the message text fields had
			// no way to be turned on/edited in the GUI — only the fixed open-message toggle was ever reachable.
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Send Open Message",
				mmf.isSendOpenMessage(), () -> { mmf.setSendOpenMessage(!mmf.isSendOpenMessage()); ConfigManager.save(); });
			rowY = drawGenericTextField(graphics, "melody_open_msg", rowX, rowY + 2, rowWidth, 16,
				"Melody Terminal start!", mmf::getOpenMessage, mmf::setOpenMessage);
			rowY += 6;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Send Progress Message",
				mmf.isSendProgress(), () -> { mmf.setSendProgress(!mmf.isSendProgress()); ConfigManager.save(); });
			SmoothTextRenderer.draw(graphics, this.font, "Prefix (appears as \"Prefix (N/4)\")", rowX, rowY + 2, Theme.text(0xFFAAAAAA));
			drawGenericTextField(graphics, "melody_progress_msg", rowX, rowY + 12, rowWidth, 16,
				"Melody", mmf::getProgressMessage, mmf::setProgressMessage);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ItemRarityBackgroundFeature irbf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			String shapeLabel = irbf.getShape() == com.cokelord.skyblocksimplified.feature.impl.ItemRarityBackgroundFeature.Shape.CIRCLE
				? "Circle" : "Square";
			drawCycleRow(graphics, rowX, y + 4, rowWidth, "Shape", shapeLabel,
				() -> { irbf.cycleShape(); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature kfb) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Block on Non-Bedrock Chests",
				kfb.isPreventNonBedrock(), () -> { kfb.setPreventNonBedrock(!kfb.isPreventNonBedrock()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Block on Rare Drops",
				kfb.isPreventRareDrops(), () -> { kfb.setPreventRareDrops(!kfb.isPreventRareDrops()); ConfigManager.save(); });
			SmoothTextRenderer.draw(graphics, this.font, "Rare-drop profit threshold (" + kfb.getThresholdDisplay() + ")", rowX, rowY, Theme.text(0xFFAAAAAA));
			drawKismetThresholdField(graphics, rowX, rowY + 10, rowWidth, 16, kfb);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowsDeviceFeature adf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Announce Completion",
				adf.isAlertOnComplete(), () -> { adf.setAlertOnComplete(!adf.isAlertOnComplete()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Emerald Blocks",
				adf.isShowEmerald(), () -> { adf.setShowEmerald(!adf.isShowEmerald()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Prediction",
				adf.isShowPrediction(), () -> { adf.setShowPrediction(!adf.isShowPrediction()); ConfigManager.save(); });
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Marked Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "arrows_marked", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				adf::getMarkedColor, c -> { adf.setMarkedColor((adf.getMarkedColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x8000AAAA);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Target Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "arrows_target", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				adf::getTargetColor, c -> { adf.setTargetColor((adf.getTargetColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x80FF55FF);
			if (adf.isShowPrediction()) {
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Prediction Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "arrows_prediction", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					adf::getPredictionColor, c -> { adf.setPredictionColor((adf.getPredictionColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x80FFFF55);
			}
			rowY += 8;
			drawSliderRow(graphics, rowX, rowY, rowWidth, "Emerald Opacity", adf.getEmeraldOpacity() + "%",
				adf.getEmeraldOpacity() / 100f, v -> { adf.setEmeraldOpacity(Math.round(v * 100f)); ConfigManager.save(); }, accent);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BeamsSolverFeature bsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawCycleRow(graphics, rowX, y + 4, rowWidth, "Style", renderStyleLabel(bsf.getStyle()),
				() -> { bsf.setStyle(nextRenderStyle(bsf.getStyle())); ConfigManager.save(); });
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Box Opacity", bsf.getAlphaPercent() + "%",
				bsf.getAlphaPercent() / 100f, v -> { bsf.setAlphaPercent(Math.round(v * 100f)); ConfigManager.save(); }, accent);
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Sound on Lantern Hit",
				bsf.isHitSoundEnabled(), () -> { bsf.setHitSoundEnabled(!bsf.isHitSoundEnabled()); ConfigManager.save(); });
			if (bsf.isHitSoundEnabled()) {
				rowY = drawSoundOptionRows(graphics, rowX, rowY, rowWidth, accent, bsf.getHitSound());
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Highlight Matching Lantern",
				bsf.isHitHighlightEnabled(), () -> { bsf.setHitHighlightEnabled(!bsf.isHitHighlightEnabled()); ConfigManager.save(); });
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Tracer to Matching Lantern",
				bsf.isHitTracerEnabled(), () -> { bsf.setHitTracerEnabled(!bsf.isHitTracerEnabled()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BlazeSolverFeature blsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Draw Line to Next Target",
				blsf.isDrawLineToNext(), () -> { blsf.setDrawLineToNext(!blsf.isDrawLineToNext()); ConfigManager.save(); });
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Line to Next Count", String.valueOf(blsf.getLineToNextCount()),
				blsf.getLineToNextCount() / 10f, v -> { blsf.setLineToNextCount(Math.round(v * 10f)); ConfigManager.save(); }, accent);
			// Per user request ("Add 3d rendering support to the blaze solver aswell"): the same 2D/2D
			// Fill/3D/3D Fill render selector every other entity-highlight module offers, reusing
			// MobHighlightFeature.RenderMode directly — see BlazeSolverFeature's own renderMode field doc
			// comment for how the two 3D modes actually get drawn as real occluded/ESP-style boxes.
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Render mode", renderModeLabel(blsf.getRenderMode()),
				() -> { blsf.setRenderMode(nextRenderMode(blsf.getRenderMode())); ConfigManager.save(); });
			if (blsf.getRenderMode() == MobHighlightFeature.RenderMode.WIRE_3D || blsf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D) {
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Occlusion (hide behind walls)",
					blsf.isOcclusion3D(), () -> { blsf.setOcclusion3D(!blsf.isOcclusion3D()); ConfigManager.save(); });
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Announce Completion to Party",
				blsf.isSendCompleteMessage(), () -> { blsf.setSendCompleteMessage(!blsf.isSendCompleteMessage()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Don't Render Blazes",
				blsf.isHideBlazes(), () -> { blsf.setHideBlazes(!blsf.isHideBlazes()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Rest of the Blazes",
				blsf.isShowRestBlazes(), () -> { blsf.setShowRestBlazes(!blsf.isShowRestBlazes()); ConfigManager.save(); });
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Current Target Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "blaze_first", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				blsf::getFirstColor, c -> { blsf.setFirstColor((blsf.getFirstColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF5555);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Next Target Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "blaze_second", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				blsf::getSecondColor, c -> { blsf.setSecondColor((blsf.getSecondColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFAA00);
			if (blsf.isShowRestBlazes()) {
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Rest of the Blazes Color");
				drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "blaze_third", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					blsf::getThirdColor, c -> { blsf.setThirdColor((blsf.getThirdColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.BoulderSolverFeature bof) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Show All Remaining Clicks",
				bof.isShowAllClicks(), () -> { bof.setShowAllClicks(!bof.isShowAllClicks()); ConfigManager.save(); });
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Style", renderStyleLabel(bof.getStyle()),
				() -> { bof.setStyle(nextRenderStyle(bof.getStyle())); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Occlusion (hide behind walls)",
				bof.isOcclusion(), () -> { bof.setOcclusion(!bof.isOcclusion()); ConfigManager.save(); },
				"On: the 3D highlight is hidden behind walls/terrain like a normal object. Off: it stays visible through walls (ESP-style).");
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Highlight Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "boulder_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				bof::getColor, c -> { bof.setColor((bof.getColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.TicTacToeSolverFeature tttf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Predict Next Move",
				tttf.isPredictNext(), () -> { tttf.setPredictNext(!tttf.isPredictNext()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Send \"Tic Tac Toe Done\" Message",
				tttf.isSendDoneMessage(), () -> { tttf.setSendDoneMessage(!tttf.isSendDoneMessage()); ConfigManager.save(); });
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Best Move Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "ttt_best", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				tttf::getBestMoveColor, c -> { tttf.setBestMoveColor((tttf.getBestMoveColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Predicted Move Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "ttt_predicted", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				tttf::getPredictedMoveColor, c -> { tttf.setPredictedMoveColor((tttf.getPredictedMoveColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFAA00);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.IceFillSolverFeature ifsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			// Settings before colors, per user request.
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Optimized (Hard) Patterns",
				ifsf.isOptimizedPatterns(), () -> { ifsf.setOptimizedPatterns(!ifsf.isOptimizedPatterns()); ConfigManager.save(); });
			rowY += 4;
			colorPickerLabel(graphics, rowX, rowY, "Path Line Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "icefill_line", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ifsf::getLineColor, c -> { ifsf.setLineColor((ifsf.getLineColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FFFF);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.QuizSolverFeature qsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			// Settings before colors, per user request.
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Depth Check",
				qsf.isDepthCheck(), () -> { qsf.setDepthCheck(!qsf.isDepthCheck()); ConfigManager.save(); });
			rowY += 4;
			colorPickerLabel(graphics, rowX, rowY, "Highlight Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "quiz_highlight", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				qsf::getHighlightColor, c -> { qsf.setHighlightColor((qsf.getHighlightColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.TPMazeSolverFeature tmsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			colorPickerLabel(graphics, rowX, rowY, "Only Candidate Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "tpmaze_one", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				tmsf::getColorOne, c -> { tmsf.setColorOne((tmsf.getColorOne() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Multiple Candidates Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "tpmaze_multiple", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				tmsf::getColorMultiple, c -> { tmsf.setColorMultiple((tmsf.getColorMultiple() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Visited Pad Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "tpmaze_visited", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				tmsf::getColorVisited, c -> { tmsf.setColorVisited((tmsf.getColorVisited() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.WaterSolverFeature wsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Show Tracer",
				wsf.isShowTracer(), () -> { wsf.setShowTracer(!wsf.isShowTracer()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Optimized Solution",
				wsf.isOptimized(), () -> { wsf.setOptimized(!wsf.isOptimized()); ConfigManager.save(); });
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Tracer Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "water_tracer", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				wsf::getTracerColor, c -> { wsf.setTracerColor((wsf.getTracerColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Next-Up Line Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "water_second", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				wsf::getSecondColor, c -> { wsf.setSecondColor((wsf.getSecondColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFF55);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.puzzle.WeirdosSolverFeature wdsf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			// Settings before colors, per user request.
			int rowY = drawCycleRow(graphics, rowX, y + 4, rowWidth, "Style", renderStyleLabel(wdsf.getStyle()),
				() -> { wdsf.setStyle(nextRenderStyle(wdsf.getStyle())); ConfigManager.save(); });
			rowY += 4;
			colorPickerLabel(graphics, rowX, rowY, "Correct Chest Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "weirdos_correct", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				wdsf::getCorrectColor, c -> { wdsf.setCorrectColor((wdsf.getCorrectColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Wrong Chest Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "weirdos_wrong", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				wdsf::getWrongColor, c -> { wdsf.setWrongColor((wdsf.getWrongColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PlayerDisplayFeature pdf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Show Icons",
				pdf.isShowIcons(), () -> { pdf.setShowIcons(!pdf.isShowIcons()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Vanilla Armor Bar",
				pdf.isHideArmor(), () -> { pdf.setHideArmor(!pdf.isHideArmor()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Vanilla Food Bar",
				pdf.isHideFood(), () -> { pdf.setHideFood(!pdf.isHideFood()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Vanilla Hearts",
				pdf.isHideHearts(), () -> { pdf.setHideHearts(!pdf.isHideHearts()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Vanilla XP Bar",
				pdf.isHideXp(), () -> { pdf.setHideXp(!pdf.isHideXp()); ConfigManager.save(); });
			rowY += 8;
			// Per user request ("Speed percentage can be a part of 'Player display'. Allow users to turn off
			// specific elements of the player display, this will hide the mod version and show the vanilla
			// version in the actionbar") — each parsed stat plus the folded-in Speed widget can now be shown/
			// hidden independently; turning off all four action-bar stats restores Hypixel's own actionbar
			// text (see the OVERLAY_MESSAGE replacement in PlayerDisplayFeature.onEnable for that logic).
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Health",
				pdf.isShowHealth(), () -> { pdf.setShowHealth(!pdf.isShowHealth()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Defense",
				pdf.isShowDefense(), () -> { pdf.setShowDefense(!pdf.isShowDefense()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Mana",
				pdf.isShowMana(), () -> { pdf.setShowMana(!pdf.isShowMana()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Overflow Mana",
				pdf.isShowOverflowMana(), () -> { pdf.setShowOverflowMana(!pdf.isShowOverflowMana()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Vitality",
				pdf.isShowVitality(), () -> { pdf.setShowVitality(!pdf.isShowVitality()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Speed",
				pdf.isShowSpeed(), () -> { pdf.setShowSpeed(!pdf.isShowSpeed()); ConfigManager.save(); });
			// Per user request ("the skill calculator... the exact same thing as it currently does in the
			// actionbar... just make it movable and make sure it shows with player display cause it doesnt
			// currently"): the real per-skill XP-gain popup ("+13 Combat (24.5%)"), now a Player Display widget.
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Skill XP",
				pdf.isShowSkillXp(), () -> { pdf.setShowSkillXp(!pdf.isShowSkillXp()); ConfigManager.save(); });
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Speed Text Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "speed_percentage_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				pdf::getSpeedTextColor, c -> { pdf.setSpeedTextColor((pdf.getSpeedTextColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFFFF);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DoorHighlightFeature dhf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			// Per user report ("Key dropped notification is still in door highlight. It should have moved
			// entirely to 'Dungeon notifications'"): the "Key Dropped Notification" toggle that used to live
			// here is gone — it duplicated Dungeon Notifications' own KEY_DROP per-type toggle, which now
			// fully owns whether an alert shows (see DoorHighlightFeature's own doc comment at the
			// fireKeyDrop call site).
			int rowY = y + 4;
			colorPickerLabel(graphics, rowX, rowY, "Locked Door Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "door_locked", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dhf::getDoorLockedColor, c -> { dhf.setDoorLockedColor((dhf.getDoorLockedColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xCCFF5555);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Openable Door Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "door_openable", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dhf::getDoorOpenableColor, c -> { dhf.setDoorOpenableColor((dhf.getDoorOpenableColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xCC55FF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Wither Key Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "wither_key", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dhf::getWitherKeyColor, c -> { dhf.setWitherKeyColor((dhf.getWitherKeyColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xCCAA55FF);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Blood Key Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "blood_key", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dhf::getBloodKeyColor, c -> { dhf.setBloodKeyColor((dhf.getBloodKeyColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xCCFF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.BloodCampFeature bcf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Move Prediction",
				bcf.isMovePrediction(), () -> { bcf.setMovePrediction(!bcf.isMovePrediction()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Announce Move Time",
				bcf.isPartyMoveTime(), () -> { bcf.setPartyMoveTime(!bcf.isPartyMoveTime()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "\"Kill Mobs\" Title",
				bcf.isKillTitle(), () -> { bcf.setKillTitle(!bcf.isKillTitle()); ConfigManager.save(); });
			rowY += 8;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Predict Spawn Location",
				bcf.isBloodAssist(), () -> { bcf.setBloodAssist(!bcf.isBloodAssist()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Blood Mob Spawn Tracers",
				bcf.isDrawLine(), () -> { bcf.setDrawLine(!bcf.isDrawLine()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Time Left Until Spawn",
				bcf.isDrawTimeLeft(), () -> { bcf.setDrawTimeLeft(!bcf.isDrawTimeLeft()); ConfigManager.save(); });
			// Per user report ("The blood camp feature still doesnt have the render selector. Replace the
			// 'Box style' with it"): now the SAME render selector every entity-highlight module exposes
			// (2D/2D Fill/3D/3D Fill, reusing MobHighlightFeature.RenderMode directly) instead of the earlier,
			// narrower Outline/Filled/Filled Outline cycle — see BloodCampFeature's own doc comment on its
			// renderMode field for how the two 3D modes actually get drawn as real occluded/ESP-style boxes.
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Render mode", renderModeLabel(bcf.getRenderMode()),
				() -> { bcf.setRenderMode(nextRenderMode(bcf.getRenderMode())); ConfigManager.save(); });
			if (bcf.getRenderMode() == MobHighlightFeature.RenderMode.WIRE_3D || bcf.getRenderMode() == MobHighlightFeature.RenderMode.FULL_3D) {
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Occlusion (hide behind walls)",
					bcf.isOcclusion3D(), () -> { bcf.setOcclusion3D(!bcf.isOcclusion3D()); ConfigManager.save(); });
			}
			int pingOffsetRowY = rowY;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Auto Ping Offset",
				bcf.isPingOffset(), () -> { bcf.setPingOffset(!bcf.isPingOffset()); ConfigManager.save(); });
			checkHoverTooltip(mouseX, mouseY, rowX, pingOffsetRowY - 2, rowWidth, 18, "bloodcamp_pingOffset",
				"Offsets the mob box by your ping.");
			rowY += 4;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Box Size", String.format(java.util.Locale.ROOT, "%.2f", bcf.getBoxSize()),
				(float) ((bcf.getBoxSize() - 0.1) / 0.9), v -> { bcf.setBoxSize(0.1 + v * 0.9); ConfigManager.save(); }, accent);
			// Per user request ("the timing offset for the blood camp module makes it so complicated to set
			// up... make it so hovering a subtoggle for like 2 seconds shows a small tooltip that tells the
			// user what the subtoggle does"): these 3 aren't really automatable — Odin's own real settings for
			// the exact same values say so directly ("Mobs spawn randomly between 37 - 41 ticks, adjust offset
			// to adjust between ticks" — i.e. genuinely random per-spawn jitter a player has to eyeball-tune
			// against, not something that can be measured once and set automatically) — so tooltips (using
			// Odin's own real descriptions for these settings, the ones actually explaining what each one
			// does) are the right fix here, not an auto-detect that can't exist for a genuinely random value.
			int assumeTickRowY = rowY;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Assume Tick", String.valueOf(bcf.getAssumeTick()),
				(bcf.getAssumeTick() - 35) / 6f, v -> { bcf.setAssumeTick(Math.round(35 + v * 6f)); ConfigManager.save(); }, accent);
			checkHoverTooltip(mouseX, mouseY, rowX, assumeTickRowY - 2, rowWidth, 20, "bloodcamp_assumeTick",
				"Tick to assume the mob spawns on. Mobs spawn randomly between 37-41 ticks after the preview marker starts moving — adjust Timing Offset to fine-tune between ticks.");
			int offsetMillisRowY = rowY;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Timing Offset (ms)", String.valueOf(bcf.getOffsetMillis()),
				(bcf.getOffsetMillis() + 100) / 200f, v -> { bcf.setOffsetMillis(Math.round(-100 + v * 200f)); ConfigManager.save(); }, accent);
			checkHoverTooltip(mouseX, mouseY, rowX, offsetMillisRowY - 2, rowWidth, 20, "bloodcamp_offsetMillis",
				"Millisecond offset added to the assumed spawn tick — use this to nudge the countdown/box timing earlier or later if it's consistently off.");
			if (!bcf.isPingOffset()) {
				int manualOffsetRowY = rowY;
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Manual Offset (ms)", String.valueOf(Math.round(bcf.getManualOffsetMillis())),
					bcf.getManualOffsetMillis() / 300f, v -> { bcf.setManualOffsetMillis(v * 300f); ConfigManager.save(); }, accent);
				checkHoverTooltip(mouseX, mouseY, rowX, manualOffsetRowY - 2, rowWidth, 20, "bloodcamp_manualOffset",
					"Manually offsets the mob box's predicted position, in milliseconds — only used while Auto Ping Offset is off.");
			}
			rowY += 8;
			// Per user report ("all except the last hex pickers name overlap with the vertical sliders next
			// to the hex picker"): a long label here sits at the same Y as the picker's own R/G/B/V slider
			// header letters (drawn at squareY - 10, i.e. the same row this label occupies), starting only
			// ~114px to the right of rowX — a label wider than that visually collides with them. Shortened
			// instead of widening the gap globally (every other color picker in the mod already fits fine).
			colorPickerLabel(graphics, rowX, rowY, "Position Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "bloodcamp_position", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				bcf::getPositionColor, c -> { bcf.setPositionColor((bcf.getPositionColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Spawn Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "bloodcamp_spawn", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				bcf::getSpawnColor, c -> { bcf.setSpawnColor((bcf.getSpawnColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF5555);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Final (Ready) Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "bloodcamp_final", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				bcf::getFinalColor, c -> { bcf.setFinalColor((bcf.getFinalColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF00AAAA);
		} else if (feature instanceof ActiveHotfPerksHighlightFeature ahpf) {
			colorPickerLabel(graphics, x + 10, y, "Highlight Color");
			drawFullColorPicker(graphics, x + 10, y + 10, width - 20, "hotf_highlight", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ahpf::getEnabledColor, c -> { ahpf.setEnabledColor((ahpf.getEnabledColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x3000FF00);
		} else if (feature instanceof DnaAnalyzerSolverFeature dasf) {
			colorPickerLabel(graphics, x + 10, y, "Highlight Color");
			drawFullColorPicker(graphics, x + 10, y + 10, width - 20, "dna_highlight", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				dasf::getColor, c -> { dasf.setColor((dasf.getColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x5000FF00);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatDeclutterFeature declutter) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Obtaining Messages",
				declutter.isRareDrops(), () -> { declutter.setRareDrops(!declutter.isRareDrops()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Keys and Doors",
				declutter.isKeysAndDoors(), () -> { declutter.setKeysAndDoors(!declutter.isKeysAndDoors()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide duped class stats message",
				declutter.isDupedClassStats(), () -> { declutter.setDupedClassStats(!declutter.isDupedClassStats()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide solo class buffed stats message",
				declutter.isSoloClassBuffedStats(), () -> { declutter.setSoloClassBuffedStats(!declutter.isSoloClassBuffedStats()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Fairy Dialogue message",
				declutter.isFairyDialogue(), () -> { declutter.setFairyDialogue(!declutter.isFairyDialogue()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Boss Messages",
				declutter.isBossMessages(), () -> { declutter.setBossMessages(!declutter.isBossMessages()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Blessing Messages",
				declutter.isBlessingMessages(), () -> { declutter.setBlessingMessages(!declutter.isBlessingMessages()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Keys",
				declutter.isHideKeys(), () -> { declutter.setHideKeys(!declutter.isHideKeys()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Grandma Wolf Combo Messages",
				declutter.isHideGrandmaWolfCombo(), () -> { declutter.setHideGrandmaWolfCombo(!declutter.isHideGrandmaWolfCombo()); ConfigManager.save(); });
			// Ultimate Ready's title alert moved into Dungeon Notifications (see the ULTIMATE_READY
			// NotificationType) — this toggle (hiding the chat line itself) is unaffected, still its own
			// independent setting here.
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Ultimate Ready Message",
				declutter.isHideUltimateReady(), () -> { declutter.setHideUltimateReady(!declutter.isHideUltimateReady()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Remove Rewards Message",
				declutter.isSeasonalRewardsMessage(), () -> { declutter.setSeasonalRewardsMessage(!declutter.isSeasonalRewardsMessage()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Remove Event Rewards Message",
				declutter.isEventRewardsMessage(), () -> { declutter.setEventRewardsMessage(!declutter.isEventRewardsMessage()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Remove Profile Message",
				declutter.isProfileMessage(), () -> { declutter.setProfileMessage(!declutter.isProfileMessage()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Lowballers",
				declutter.isHideLowballers(), () -> { declutter.setHideLowballers(!declutter.isHideLowballers()); ConfigManager.save(); });
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Guild EXP Gain",
				declutter.isHideGuildExpGain(), () -> { declutter.setHideGuildExpGain(!declutter.isHideGuildExpGain()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.DisableEndermanDeathAnimationFeature dedaf) {
			int rowY = drawToggleRow(graphics, x + 10, y + 7, width - 20, "Disable Dying Sound",
				dedaf.isDisableDyingSound(), () -> { dedaf.setDisableDyingSound(!dedaf.isDisableDyingSound()); ConfigManager.save(); });
			drawToggleRow(graphics, x + 10, rowY, width - 20, "Disable Teleport Sound",
				dedaf.isDisableTeleportSound(), () -> { dedaf.setDisableTeleportSound(!dedaf.isDisableTeleportSound()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature iaf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "X Offset", String.format(java.util.Locale.ROOT, "%.2f", iaf.getOffsetX()),
				(iaf.getOffsetX() + 2f) / 4f, v -> { iaf.setOffsetX(v * 4f - 2f); ConfigManager.save(); }, accent);
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Y Offset", String.format(java.util.Locale.ROOT, "%.2f", iaf.getOffsetY()),
				(iaf.getOffsetY() + 2f) / 4f, v -> { iaf.setOffsetY(v * 4f - 2f); ConfigManager.save(); }, accent);
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Z Offset", String.format(java.util.Locale.ROOT, "%.2f", iaf.getOffsetZ()),
				(iaf.getOffsetZ() + 2f) / 4f, v -> { iaf.setOffsetZ(v * 4f - 2f); ConfigManager.save(); }, accent);
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Yaw", Math.round(iaf.getYaw()) + "°",
				(iaf.getYaw() + 180f) / 360f, v -> { iaf.setYaw(v * 360f - 180f); ConfigManager.save(); }, accent);
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Pitch", Math.round(iaf.getPitch()) + "°",
				(iaf.getPitch() + 180f) / 360f, v -> { iaf.setPitch(v * 360f - 180f); ConfigManager.save(); }, accent);
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Roll", Math.round(iaf.getRoll()) + "°",
				(iaf.getRoll() + 180f) / 360f, v -> { iaf.setRoll(v * 360f - 180f); ConfigManager.save(); }, accent);
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Size", String.format(java.util.Locale.ROOT, "%.2f", iaf.getScale()),
				(iaf.getScale() - 0.1f) / 2.9f, v -> { iaf.setScale(0.1f + v * 2.9f); ConfigManager.save(); }, accent);
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Disable Full Swing Animation",
				iaf.isDisableFullSwing(), () -> { iaf.setDisableFullSwing(!iaf.isDisableFullSwing()); ConfigManager.save(); });
			// Per user request ("Devonian has this, port theirs"): swing still plays (rotation), just doesn't
			// physically travel toward the crosshair — independent of Disable Full Swing above, which cancels
			// both.
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "In Place Swing Animation",
				iaf.isInPlaceSwing(), () -> { iaf.setInPlaceSwing(!iaf.isInPlaceSwing()); ConfigManager.save(); });
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Swing Speed", swingSpeedLabel(iaf.getSwingSpeed()),
				(iaf.getSwingSpeed() - com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MIN_SWING_SPEED)
					/ (com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MAX_SWING_SPEED - com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MIN_SWING_SPEED),
				v -> {
					iaf.setSwingSpeed(com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MIN_SWING_SPEED
						+ v * (com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MAX_SWING_SPEED - com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MIN_SWING_SPEED));
					ConfigManager.save();
				}, accent);
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Disable Item Swapping Animation",
				iaf.isDisableItemSwapping(), () -> { iaf.setDisableItemSwapping(!iaf.isDisableItemSwapping()); ConfigManager.save(); });
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Disable Hand Swaying",
				iaf.isDisableHandSwaying(), () -> { iaf.setDisableHandSwaying(!iaf.isDisableHandSwaying()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ChatCopyFeature ccf) {
			drawComboCaptureRow(graphics, x + 10, y + 7, width - 20, "Copy Hovered Line Keybind", ccf.getPrimaryKeyCombo());
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.RareRewardWarningFeature rrwf) {
			drawComboCaptureRow(graphics, x + 10, y + 7, width - 20, "Bypass Key (allows refusing rare offers)", rrwf.getPrimaryKeyCombo());
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ModInformationFeature) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			com.cokelord.skyblocksimplified.api.GitHubReleaseApi.State releaseState = com.cokelord.skyblocksimplified.api.GitHubReleaseApi.getState();
			if (releaseState != com.cokelord.skyblocksimplified.api.GitHubReleaseApi.State.FOUND) {
				String statusText = releaseState == com.cokelord.skyblocksimplified.api.GitHubReleaseApi.State.LOADING
					? "Checking GitHub for patch notes..." : "No GitHub releases published yet.";
				SmoothTextRenderer.draw(graphics, this.font, statusText, rowX, rowY, Theme.text(0xFF888888));
			} else {
				String heading = "Patch Notes"
					+ (com.cokelord.skyblocksimplified.api.GitHubReleaseApi.getReleaseName() != null
						? " — " + com.cokelord.skyblocksimplified.api.GitHubReleaseApi.getReleaseName() : "");
				SmoothTextRenderer.draw(graphics, this.font, heading, rowX, rowY, accent);
				rowY += 14;
				String body = com.cokelord.skyblocksimplified.api.GitHubReleaseApi.getReleaseBody();
				if (body != null && !body.isBlank()) {
					for (var line : this.font.split(Component.literal(body), rowWidth)) {
						graphics.text(this.font, line, rowX, rowY, Theme.text(0xFFCCCCCC));
						rowY += 10;
					}
				}
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.GyroHelperFeature ghf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Cooldown",
				ghf.isShowCooldown(), () -> { ghf.setShowCooldown(!ghf.isShowCooldown()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Depth Check",
				ghf.isDepthCheck(), () -> { ghf.setDepthCheck(!ghf.isDepthCheck()); ConfigManager.save(); });
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Ring Width", String.valueOf(Math.round(ghf.getRingWidth())),
				(ghf.getRingWidth() - 1f) / 9f, v -> { ghf.setRingWidth(Math.round(1 + v * 9)); ConfigManager.save(); }, accent);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Ring Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "gyro_ring", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ghf::getRingColor, c -> { ghf.setRingColor((ghf.getRingColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x80AA00AA);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Cooldown Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "gyro_cooldown", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ghf::getCooldownColor, c -> { ghf.setCooldownColor((ghf.getCooldownColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x80FF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.LavaToWaterFeature ltwf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = drawToggleRow(graphics, rowX, y + 4, rowWidth, "Color Tint",
				ltwf.isColorTint(), () -> { ltwf.setColorTint(!ltwf.isColorTint()); ConfigManager.save(); });
			if (ltwf.isColorTint()) {
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Tint Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "lava_to_water_tint", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					ltwf::getTintColor, c -> { ltwf.setTintColor((ltwf.getTintColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF3F76E4);
				rowY += 8;
			}
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Fog",
				ltwf.isHideFog(), () -> { ltwf.setHideFog(!ltwf.isHideFog()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.MelodyDisplayFeature mdf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Alert Duration", String.format(java.util.Locale.ROOT, "%.1fs", mdf.getAlertDurationSeconds()),
				(mdf.getAlertDurationSeconds() - 0.5f) / 9.5f, v -> { mdf.setAlertDurationSeconds(0.5f + v * 9.5f); ConfigManager.save(); }, accent);
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Play Sound",
				mdf.isSoundEnabled(), () -> { mdf.setSoundEnabled(!mdf.isSoundEnabled()); ConfigManager.save(); });
			rowY += 8;
			// Per user request ("&h means custom color, that the user can choose in the settings for that
			// module"): colors the player's name and the punctuation around the count fraction (see
			// MelodyDisplayFeature's own buildSegments) — the class name, "Melody", and the count digit
			// itself are colored automatically and aren't affected by this.
			colorPickerLabel(graphics, rowX, rowY, "Custom Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "melody_display_custom", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				mdf::getCustomColor, c -> { mdf.setCustomColor((mdf.getCustomColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF55FF);
			rowY += 8;
			// Per user request ("mix it with the party melody display we already have... make it render the
			// melody display if on instead of the one we had before") — real relay integration with Odin's
			// own hosted server, so this stays off by default and clearly labeled rather than silently
			// connecting. When on, this widget's render() shows a reskin of the real melody terminal's own
			// squircle GUI (see MelodyDisplayFeature's renderMelodyPanel) instead of the chat-parsed
			// "{username} has Melody!" alert above — no per-player label/cycle setting any more, since only
			// one real Melody terminal can ever be active at once (per user report).
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Broadcast Progress (Odin Relay)",
				mdf.isBroadcastProgress(), () -> { mdf.setBroadcastProgress(!mdf.isBroadcastProgress()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.SimonSaysFeature ssf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "SS Skip Compatibility",
				ssf.isSsSkipCompat(), () -> { ssf.setSsSkipCompat(!ssf.isSsSkipCompat()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Progress Display",
				ssf.isProgressDisplay(), () -> { ssf.setProgressDisplay(!ssf.isProgressDisplay()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Announce Progress in Party Chat",
				ssf.isAnnounceProgress(), () -> { ssf.setAnnounceProgress(!ssf.isAnnounceProgress()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Block Wrong Clicks",
				ssf.isBlockWrongClicks(), () -> { ssf.setBlockWrongClicks(!ssf.isBlockWrongClicks()); ConfigManager.save(); });
			// Ported from Odin's SimonSays.kt ("Block Wrong on Start"/"Max Start Clicks") — blocks the start
			// button once it's been clicked past the limit, since repeatedly mashing it re-rolls the device's
			// sequence and can undo an already-memorized attempt.
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Block Wrong on Start",
				ssf.isBlockWrongStart(), () -> { ssf.setBlockWrongStart(!ssf.isBlockWrongStart()); ConfigManager.save(); });
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Max Start Clicks", String.valueOf(ssf.getMaxStartClicks()),
				(ssf.getMaxStartClicks() - 1) / 9f, v -> { ssf.setMaxStartClicks(Math.round(1 + v * 9f)); ConfigManager.save(); }, accent);
			// Per user report ("Remove the SS break notification since its very broken and says SS broke even
			// when it completed"): the break/restart alert subsystem this used to gate (Alerts Enabled/SS Break
			// Alert/Send Restart Chat/Alert Sound/Show Title) is gone entirely — see SimonSaysFeature's own
			// class doc comment for why it was never fixable, not just disabled.
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Style", renderStyleLabel(ssf.getStyle()),
				() -> { ssf.setStyle(nextRenderStyle(ssf.getStyle())); ConfigManager.save(); });
			rowY += 4;
			colorPickerLabel(graphics, rowX, rowY, "First Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "ss_first", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ssf::getFirstColor, c -> { ssf.setFirstColor((ssf.getFirstColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF55FF55);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Second Color");
			rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "ss_second", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ssf::getSecondColor, c -> { ssf.setSecondColor((ssf.getSecondColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFAA00);
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Other Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "ss_third", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ssf::getThirdColor, c -> { ssf.setThirdColor((ssf.getThirdColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF5555);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.EntityRenderDistanceFeature erdf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Distance", Math.round(erdf.getDistance()) + " blocks",
				(erdf.getDistance() - 8f) / (128f - 8f), v -> { erdf.setDistance(8f + v * (128f - 8f)); ConfigManager.save(); }, accent);
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Include Players",
				erdf.isIncludePlayers(), () -> { erdf.setIncludePlayers(!erdf.isIncludePlayers()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.CameraFeature cf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Disable Front View",
				cf.isDisableFrontView(), () -> { cf.setDisableFrontView(!cf.isDisableFrontView()); ConfigManager.save(); });
			rowY += 2;
			drawSliderRow(graphics, rowX, rowY, rowWidth, "Max Distance", Math.round(cf.getMaxDistance()) + " blocks",
				(cf.getMaxDistance() - 4f) / (32f - 4f), v -> { cf.setMaxDistance(4f + v * (32f - 4f)); ConfigManager.save(); }, accent);
		} else if (feature instanceof ItemPickupLogFeature iplf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Compact Lines",
				iplf.isCompactLines(), () -> { iplf.setCompactLines(!iplf.isCompactLines()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Compact Numbers",
				iplf.isCompactNumbers(), () -> { iplf.setCompactNumbers(!iplf.isCompactNumbers()); ConfigManager.save(); });
			rowY += 2;
			drawSliderRow(graphics, rowX, rowY, rowWidth, "Expire after (s)", String.valueOf(iplf.getExpireSeconds()),
				(iplf.getExpireSeconds() - 1) / 19f, v -> { iplf.setExpireSeconds(Math.round(1 + v * 19f)); ConfigManager.save(); }, accent);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.SplitsFeature spf) {
			// Real gap found (per user request — "Allow users to change the color of the splits background,
			// add an outline and change the color of that too"): this feature had NO settings panel branch
			// here at all before now — every one of its existing toggles/cycle (Fixed Width, Boss Entry
			// Split, Split Location) was already fully wired up in the Feature itself but had no way to
			// actually be reached in-game. Built from scratch here, the new background/outline controls
			// alongside the pre-existing ones that were silently unreachable until now.
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Fixed Width",
				spf.isFixedWidth(), () -> { spf.setFixedWidth(!spf.isFixedWidth()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Boss Entry Split",
				spf.isBossEntrySplit(), () -> { spf.setBossEntrySplit(!spf.isBossEntrySplit()); ConfigManager.save(); });
			// Real gap found (per user request — "Remove the splits chat messages"): isSendSplits/setSendSplits
			// already existed on the feature itself (defaults off) but had no settings row here at all, so a
			// config that somehow had it on (or anyone who wanted it on deliberately) had no way to see or
			// change it in-game.
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Send Splits to Chat",
				spf.isSendSplits(), () -> { spf.setSendSplits(!spf.isSendSplits()); ConfigManager.save(); });
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Split Location", splitLocationLabel(spf.getSplitLocation()),
				() -> { cycleSplitLocation(spf); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Outline",
				spf.isOutlineEnabled(), () -> { spf.setOutlineEnabled(!spf.isOutlineEnabled()); ConfigManager.save(); });
			if (spf.isOutlineEnabled()) {
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Outline Thickness", String.valueOf(spf.getOutlineThickness()),
					(spf.getOutlineThickness() - 1) / 9f, v -> { spf.setOutlineThickness(1 + Math.round(v * 9f)); ConfigManager.save(); }, accent);
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Outline Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "splits_outline", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					spf::getOutlineColor, c -> { spf.setOutlineColor((spf.getOutlineColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF000000);
				rowY += 8;
			}
			colorPickerLabel(graphics, rowX, rowY, "Background Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "splits_bg", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				spf::getBackgroundColor, c -> { spf.setBackgroundColor((spf.getBackgroundColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFF101010);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.AuctionHouseTotalFeature ahtf) {
			// Per user request: the "if sold" total is its own subtoggle now, not always-on whenever nonzero.
			drawToggleRow(graphics, x + 10, y + 4, width - 20, "Show \"If Sold\" Total",
				ahtf.isShowActiveTotal(), () -> { ahtf.setShowActiveTotal(!ahtf.isShowActiveTotal()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.AuctionTimersFeature atmf) {
			int rowX = x + 10, rowWidth = width - 20, rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Bold",
				atmf.isBold(), () -> { atmf.setBold(!atmf.isBold()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Italic",
				atmf.isItalic(), () -> { atmf.setItalic(!atmf.isItalic()); ConfigManager.save(); });
			rowY += 8;
			colorPickerLabel(graphics, rowX, rowY, "Text Color");
			drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "auction_timers_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				() -> atmf.getTextColor() & 0xFFFFFF, c -> { atmf.setTextColor((atmf.getTextColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF55);
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ExperimentationTimersFeature etf) {
			int rowX = x + 10, rowWidth = width - 20, rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Notify Only at 3",
				etf.isNotifyOnlyAtThree(), () -> { etf.setNotifyOnlyAtThree(!etf.isNotifyOnlyAtThree()); ConfigManager.save(); },
				"Waits until the table has all 3 charges before notifying, instead of notifying on every single charge.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Notify Chat",
				etf.isNotifyChat(), () -> { etf.setNotifyChat(!etf.isNotifyChat()); ConfigManager.save(); });
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Notify Sound",
				etf.isNotifySound(), () -> { etf.setNotifySound(!etf.isNotifySound()); ConfigManager.save(); });
			if (etf.isNotifySound()) {
				rowY += 4;
				rowY = drawSoundOptionRows(graphics, rowX, rowY, rowWidth, accent, etf.getSound());
			}
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Notify Title",
				etf.isNotifyTitle(), () -> { etf.setNotifyTitle(!etf.isNotifyTitle()); ConfigManager.save(); });
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.ArrowAlignFeature aaf) {
			// Per user request: a subtoggle for the Healer "Predevice" workflow, with a hover tooltip
			// explaining what it does (same 2-second hover delay as every other explanatory tooltip in this
			// menu) — previously unconditional with no way to turn it off and no in-GUI explanation at all.
			int rowX = x + 10, rowWidth = width - 20, rowY = y + 4;
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Predevice Support",
				aaf.isPredevSupport(), () -> { aaf.setPredevSupport(!aaf.isPredevSupport()); ConfigManager.save(); });
			checkHoverTooltip(mouseX, mouseY, rowX, rowY - 2, rowWidth, 18, "arrow_align_predev",
				"Before terminals start, leaves one arrow 1 click away from finishing the device instead of "
					+ "completing it outright — so the Healer can insta-complete it the moment terminals actually "
					+ "begin. Once terminals have started, this no longer applies.");
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf) {
			// Real panel background theme (per user clarification: "Themes clarification: NOT mod accent
			// color... wants a SEPARATE module with three real panel themes"). A simple cycle row, same
			// pattern as MobHighlightFeature's Render Mode row above.
			int rowX = x + 10, rowWidth = width - 20;
			int rowY = drawCycleRow(graphics, rowX, y + 4, rowWidth, "Panel Theme", panelThemeLabel(ptf.getMode()),
				() -> { cyclePanelTheme(ptf); ConfigManager.save(); });
			// Per user request ("Remove the gui color module, since the accent color is now in the panel
			// theme thing. It should just always live there, even if the theme isnt custom, since all themes
			// use the accent color"): this used to only show nested under Custom mode (and GuiColorFeature
			// itself still had its own separate top-level module row) — now unconditional, and GuiColorFeature
			// is hidden from the top-level module list entirely (see its own isHiddenFromGui() override) since
			// this is now its only home. Reuses the exact same presets-row/full-picker/chroma UI its old
			// settings-cog panel (drawColorPickerContent) already had, inlined here instead of behind a cog —
			// keep expandedSettingsHeightFor's own PanelThemeFeature branch in sync with this row order.
			com.cokelord.skyblocksimplified.feature.impl.GuiColorFeature gcf = guiColorFeatureInstance();
			if (gcf != null) {
				rowY += 4;
				rowY = drawAccentPresetsRow(graphics, gcf, rowX, rowY, rowWidth, mouseX, mouseY);
				rowY += 8;
				colorPickerLabel(graphics, rowX, rowY, "Accent Color");
				rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "gui_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
					gcf::getColor, c -> { gcf.setColor((gcf.getColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, GUI_COLOR_DEFAULT);
				rowY += 8;
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "accent_chroma", "Chroma",
					gcf.isChromaEnabled(), () -> { gcf.setChromaEnabled(!gcf.isChromaEnabled()); ConfigManager.save(); }, null);
				if (gcf.isChromaEnabled()) {
					rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Chroma Saturation", Math.round(gcf.getChromaSaturation() * 100f) + "%",
						gcf.getChromaSaturation(), v -> { gcf.setChromaSaturation(v); ConfigManager.save(); }, accent);
					rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Chroma Speed", String.format(Locale.ROOT, "%.1fx", gcf.getChromaSpeed()),
						(gcf.getChromaSpeed() - 0.1f) / 4.9f, v -> { gcf.setChromaSpeed(0.1f + v * 4.9f); ConfigManager.save(); }, accent);
				}
			}
			// Per user request ("Build the 'Custom' theme... a Custom theme with user-picked main color +
			// opacity slider... plus a two-pin drag gradient editor... horizontal/vertical orientation"):
			// everything below only shows once Custom is actually selected, same conditional-content pattern
			// every other mode-gated panel in this file already uses.
			if (ptf.getMode() == com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature.Mode.CUSTOM) {
				rowY += 8;
				rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Opacity", ptf.getCustomOpacityPercent() + "%",
					ptf.getCustomOpacityPercent() / 100f, v -> { ptf.setCustomOpacityPercent(Math.round(v * 100f)); ConfigManager.save(); }, accent);
				// Per user request ("Do those now" — "Extend gradient/solid-color option to individual feature
				// rows and subtoggle buttons"): opt-in since a busy per-row tint isn't everyone's taste even with
				// the panel's own gradient on — see colorRowBg()/drawToggleRow's own doc comments for the actual
				// sampling.
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Apply Gradient to Rows & Buttons",
					ptf.isCustomApplyGradientToRows(), () -> { ptf.setCustomApplyGradientToRows(!ptf.isCustomApplyGradientToRows()); ConfigManager.save(); });
				// Per user request ("Add a chroma gradient option that forces the pins to scroll through the
				// chroma colors smoothly. They should never match color, always be one before or ahead so it
				// actually looks chroma"): same toggle+speed-slider UI shape the Accent Color's own chroma
				// already uses above, just driving the two gradient pins instead of one flat accent color —
				// see PanelThemeFeature's own doc comment on the fixed hue-gap between the two pins.
				rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "gradient_chroma", "Chroma",
					ptf.isCustomChromaEnabled(), () -> { ptf.setCustomChromaEnabled(!ptf.isCustomChromaEnabled()); ConfigManager.save(); }, null);
				if (ptf.isCustomChromaEnabled()) {
					rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Chroma Speed", String.format(Locale.ROOT, "%.1fx", ptf.getCustomChromaSpeed()),
						(ptf.getCustomChromaSpeed() - 0.1f) / 4.9f, v -> { ptf.setCustomChromaSpeed(0.1f + v * 4.9f); ConfigManager.save(); }, accent);
				}
				rowY += 12;
				drawGradientPinEditor(graphics, rowX, rowY, rowWidth, accent, ptf);
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature fif) {
			// Per user request: Import button (native file picker, .ttf or .zip containing one), Reset
			// button (back to bundled Quicksand), and a scope cycle for whether the imported font stays
			// scoped to just this mod's own menu or replaces Minecraft's own default font everywhere.
			int rowX = x + 10, rowWidth = width - 20, rowY = y + 4;
			int btnHeight = 18, gap = 8;
			int btnWidth = (rowWidth - gap) / 2;
			String importLabel = fif.hasImportedFont() ? "Re-import" : "Import Font";
			fillRounded(graphics, rowX, rowY, rowX + btnWidth, rowY + btnHeight, BOX_RADIUS, Theme.chrome(0xFF2A2A2A));
			SmoothTextRenderer.draw(graphics, this.font, importLabel, rowX + (btnWidth - this.font.width(importLabel)) / 2, rowY + 5, Theme.text(0xFFFFFFFF));
			clickHits.add(new ClickHit(rowX, rowY, btnWidth, btnHeight, () -> {
				String picked;
				try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
					org.lwjgl.PointerBuffer filters = stack.pointers(stack.UTF8("*.ttf"), stack.UTF8("*.zip"));
					picked = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
						"Select a font (.ttf or .zip)", "", filters, "Font files", false);
				}
				if (picked != null) {
					fif.importFromPath(picked);
					ConfigManager.save();
				}
			}));
			int resetX = rowX + btnWidth + gap;
			boolean resetEnabled = fif.hasImportedFont();
			fillRounded(graphics, resetX, rowY, resetX + btnWidth, rowY + btnHeight, BOX_RADIUS, Theme.chrome(0xFF2A2A2A));
			SmoothTextRenderer.draw(graphics, this.font, "Reset", resetX + (btnWidth - this.font.width("Reset")) / 2, rowY + 5,
				resetEnabled ? Theme.text(0xFFFFFFFF) : 0xFF888888);
			if (resetEnabled) {
				clickHits.add(new ClickHit(resetX, rowY, btnWidth, btnHeight, () -> { fif.reset(); ConfigManager.save(); }));
			}
			int scopeY = rowY + btnHeight + 4;
			String scopeLabel = fif.getScope() == com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature.Scope.GAME_WIDE
				? "Replace Minecraft's Font" : "Keep Inside Mod";
			scopeY = drawCycleRow(graphics, rowX, scopeY, rowWidth, "Applies To", scopeLabel, () -> {
				com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature.Scope[] scopes =
					com.cokelord.skyblocksimplified.feature.impl.FontImporterFeature.Scope.values();
				fif.setScope(scopes[(fif.getScope().ordinal() + 1) % scopes.length]);
				ConfigManager.save();
			});
			if (fif.getLastError() != null) {
				SmoothTextRenderer.draw(graphics, this.font, fif.getLastError(), rowX, scopeY, Theme.text(0xFFFF5555));
			}
		} else if (feature instanceof com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature ptgf) {
			int rowX = x + 10;
			int rowWidth = width - 20;
			int rowY = y + 4;
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Reduce Item Rarity Background Updates",
				com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.isReduceRarityBackgroundUpdates(),
				() -> { ptgf.setReduceRarityBackgroundUpdates(!com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.isReduceRarityBackgroundUpdates()); ConfigManager.save(); },
				"A real throttle: only recomputes an inventory slot's rarity-color background tint when the item in that slot actually changes, instead of every frame. Safe to leave on — no visible behavior change.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Door Highlight",
				com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.isMirrorEnabled("door_highlight"),
				() -> { com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.setMirrorEnabled("door_highlight",
					!com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.isMirrorEnabled("door_highlight")); ConfigManager.save(); },
				"Quick-access mirror of the Door Highlight module's own on/off switch — turns off its continuous door-scanning/3D rendering to save frames.");
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Blood Camp",
				com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.isMirrorEnabled("blood_camp"),
				() -> { com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.setMirrorEnabled("blood_camp",
					!com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.isMirrorEnabled("blood_camp")); ConfigManager.save(); },
				"Quick-access mirror of the Blood Camp module's own on/off switch — turns off its continuous scanning/rendering to save frames.");
			drawToggleRow(graphics, rowX, rowY, rowWidth, "Dungeon Map",
				com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.isMirrorEnabled("dungeon_map"),
				() -> { com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.setMirrorEnabled("dungeon_map",
					!com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature.isMirrorEnabled("dungeon_map")); ConfigManager.save(); },
				"Quick-access mirror of the Dungeon Map module's own on/off switch — turns off its continuous room/map scanning and rendering to save frames.");
			// Pixelated Look's own mirror row is gone (per user request, pixelated rendering is unconditional
			// now — see GuiAnimationsFeature's own doc comment), nothing left for it to toggle.
		} else {
			drawKeybindRows(graphics, x, y, width, height, y + 6, feature.getKeybinds());
		}
		graphics.disableScissor();
	}

	private static String splitLocationLabel(com.cokelord.skyblocksimplified.feature.impl.SplitsFeature.SplitLocation location) {
		return switch (location) {
			case BOTH -> "Both";
			case DUNGEONS_ONLY -> "Dungeons Only";
			case KUUDRA_ONLY -> "Kuudra Only";
		};
	}

	private static void cycleSplitLocation(com.cokelord.skyblocksimplified.feature.impl.SplitsFeature spf) {
		com.cokelord.skyblocksimplified.feature.impl.SplitsFeature.SplitLocation[] values =
			com.cokelord.skyblocksimplified.feature.impl.SplitsFeature.SplitLocation.values();
		spf.setSplitLocation(values[(spf.getSplitLocation().ordinal() + 1) % values.length]);
	}

	// Clamped against the scrollable feature list's own visible viewport (listViewportX0/X1, Y0/Y1) rather
	// than the full game window — per user report ("the presets thing cuts off since it lines up with the
	// mod menu itself"), clamping to the whole screen let a popup compute a valid on-screen position that
	// was still well outside the actual mod-menu panel, where it then visibly got clipped by the panel's
	// own still-active outer scissor (see drawPositionalMessageDropdownPopup's own scissor handling).
	// Keeping popups within the panel's own bounds instead means their position and their clip region always
	// agree.
	private int clampPopupX(int x, int popupWidth) {
		return Math.max(listViewportX0 + 4, Math.min(x, listViewportX1 - popupWidth - 4));
	}

	private int clampPopupY(int y, int popupHeight) {
		return Math.max(listViewportY0 + 4, Math.min(y, listViewportY1 - popupHeight - 4));
	}

	/** Custom Enchant Parsing panel content: a plain list of rules (no tabs, no drag-to-reorder — match
	 *  order doesn't matter since each rule is checked independently) followed by an "add rule" button
	 *  that inserts a blank rule directly, unlike the scoreboard's add-line button there's only one kind
	 *  of row here so no type-picker dropdown is needed. */
	private void drawEnchantParsingContent(GuiGraphicsExtractor graphics, CustomEnchantParsingFeature cef, int x, int y, int width, int height, int accent) {
		int rowY = y + 4;
		SmoothTextRenderer.draw(graphics, this.font, "Restyles matching \"Name Level\" lore to the chosen style below.", x + 10, rowY, Theme.text(0xFF888888));
		rowY += 14;

		List<CustomEnchantParsingFeature.Rule> rules = cef.getRules();
		for (int i = 0; i < rules.size(); i++) {
			rowY = drawEnchantRuleRow(graphics, cef, rules.get(i), i, x, rowY, width, accent);
			rowY += 2;
		}

		addRuleButtonX = x + 8;
		addRuleButtonY = rowY + 2;
		addRuleButtonSize = 16;
		fillRounded(graphics, addRuleButtonX, addRuleButtonY, addRuleButtonX + addRuleButtonSize, addRuleButtonY + addRuleButtonSize, BOX_RADIUS, accent);
		SmoothTextRenderer.draw(graphics, this.font, "+", addRuleButtonX + (addRuleButtonSize - this.font.width("+")) / 2, addRuleButtonY + (addRuleButtonSize - 8) / 2, Theme.text(0xFFFFFFFF));
		rowY = addRuleButtonY + addRuleButtonSize + 10;

		// Per user request (task tracker #589 — "per-tier color/bold/italic overrides"): a flat style
		// applied to ANY enchant at that exact level, for banding a whole loadout by tier without hand-
		// adding a same-styled Rule per enchant name — see TierOverride's own doc comment for how this
		// interacts with (and always loses to) a more specific per-name Rule above.
		SmoothTextRenderer.draw(graphics, this.font, "Tier Overrides", x + 10, rowY, Theme.text(0xFFAAAAAA));
		rowY += 12;
		CustomEnchantParsingFeature.TierOverride[] tiers = cef.getTierOverrides();
		for (int level = 1; level <= tiers.length; level++) {
			rowY = drawEnchantTierRow(graphics, tiers[level - 1], level, x, rowY, width, accent);
			rowY += 2;
		}

		if (isStylePopupOpen()) {
			// Two levels need popping here, not one: drawExpandedSettings' own enableScissor(x,y,x+width,
			// y+height) for this panel's content AND the wider list-viewport scissor one level further out
			// (enableScissor(contentX..contentX+contentWidth, ...) in the main layout method) are BOTH still
			// active at this point — scissor is a stack (GuiGraphicsExtractor.ScissorStack), so a single
			// disableScissor() only pops the inner one, leaving the outer list-viewport clip still able to
			// cut off a popup drawn anywhere near that edge. Harmless for these specific popups in practice
			// (small, drawn well inside the panel), but see drawPositionalMessageDropdownPopup's own version
			// of this comment for where it actually became visible.
			popupHitsStartClick = clickHits.size();
			popupHitsStartColorSquare = colorSquareHits.size();
			popupHitsStartSlider = sliderHits.size();
			popupHitsStartHex = hexFieldBounds.size();
			graphics.disableScissor();
			graphics.disableScissor();
			drawEnchantStylePopups(graphics, accent);
			graphics.enableScissor(listViewportX0, listViewportY0, listViewportX1, listViewportY1);
			graphics.enableScissor(x, y, x + width, y + height);
		}
	}

	/** Positional Messages panel: two toggles + a shared box color, then one editable row block per
	 *  message (text / X-Y-Z-range / "use current position" button), then an "add message" button —
	 *  per user mockup, replacing the feature's old command-only configuration entirely. */
	private void drawPositionalMessagesContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf,
												int x, int y, int width, int height, int accent) {
		int rowX = x + 10;
		int rowWidth = width - 20;
		int rowY = y + 4;
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Only in Floor 7 Bossfight",
			pmf.isOnlyInFloor7Boss(), () -> { pmf.setOnlyInFloor7Boss(!pmf.isOnlyInFloor7Boss()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Position Boxes",
			pmf.isShowPositions(), () -> { pmf.setShowPositions(!pmf.isShowPositions()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Circle Instead of Square",
			pmf.isCircleMode(), () -> { pmf.setCircleMode(!pmf.isCircleMode()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Text",
			pmf.isShowText(), () -> { pmf.setShowText(!pmf.isShowText()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Depth Check",
			pmf.isDepthCheck(), () -> { pmf.setDepthCheck(!pmf.isDepthCheck()); ConfigManager.save(); });
		rowY += 4;
		rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Wall Height", String.format(java.util.Locale.ROOT, "%.1f", pmf.getWallHeight()),
			(float) (pmf.getWallHeight() / 10.0), v -> { pmf.setWallHeight(v * 10.0); ConfigManager.save(); }, accent);
		rowY += 8;
		colorPickerLabel(graphics, rowX, rowY, "Box Color");
		rowY = drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "posmsg_box_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
			pmf::getBoxColor, c -> { pmf.setBoxColor((pmf.getBoxColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFF55);
		rowY += 8;
		rowY = drawPosMsgConfigRow(graphics, pmf, rowX, rowY, rowWidth, accent);
		rowY += 8;

		List<com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.PosMessage> list = pmf.getMessages();
		for (int i = 0; i < list.size(); i++) {
			rowY = drawPositionalMessageRow(graphics, pmf, list.get(i), i, rowX, rowY, rowWidth);
			rowY += 4;
		}

		addPosMsgButtonX = rowX;
		addPosMsgButtonY = rowY + 2;
		addPosMsgButtonWidth = rowWidth;
		addPosMsgButtonHeight = 16;
		fillRounded(graphics, addPosMsgButtonX, addPosMsgButtonY, addPosMsgButtonX + addPosMsgButtonWidth, addPosMsgButtonY + addPosMsgButtonHeight, BOX_RADIUS, accent);
		String addLabel = "+ Add Positional Message";
		SmoothTextRenderer.draw(graphics, this.font, addLabel, addPosMsgButtonX + (addPosMsgButtonWidth - this.font.width(addLabel)) / 2,
			addPosMsgButtonY + (addPosMsgButtonHeight - 8) / 2, 0xFFFFFFFF);

		if (isStylePopupOpen()) {
			// Real bug found (per user report — "the presets thing cuts off since it lines up with the mod
			// menu itself"): this only popped ONE scissor level (drawExpandedSettings' own enableScissor(x,y,
			// x+width,y+height) for this panel's content), but a SECOND, wider one — the scrollable feature
			// list's own viewport scissor, pushed one level further out — was still active underneath it
			// (scissor is a stack: GuiGraphicsExtractor.ScissorStack). The Preset dropdown (140px wide,
			// opened from a button near the row's right edge) was the first popup wide/far-right enough to
			// actually get clipped by that still-active outer level, reading as "cut off right where the mod
			// menu panel ends." Popping both levels here, and restoring both in the same order they were
			// originally pushed (outer first, then inner), fixes it for real instead of just for this one
			// popup's specific size.
			popupHitsStartClick = clickHits.size();
			popupHitsStartColorSquare = colorSquareHits.size();
			popupHitsStartSlider = sliderHits.size();
			popupHitsStartHex = hexFieldBounds.size();
			graphics.disableScissor();
			graphics.disableScissor();
			drawPositionalMessageDropdownPopup(graphics, pmf, accent);
			graphics.enableScissor(listViewportX0, listViewportY0, listViewportX1, listViewportY1);
			graphics.enableScissor(x, y, x + width, y + height);
		}
	}

	/** Inactive Waypoints (F7 P3 device highlighter, shown to the user as "Inactive Terminals") had no
	 *  settings panel at all before this — every toggle/color getter already existed on the feature itself,
	 *  just never wired into a GUI branch, so nothing here was actually reachable in-game. Built per user
	 *  request: subtoggles for hiding titles / hiding the block highlight separately, plus the new Sync With
	 *  Class filter and its own color picker for the (now real, through-walls, full-block) highlight. */
	private void drawInactiveWaypointsContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.InactiveWaypointsFeature iwf,
												int x, int y, int width, int height, int accent) {
		int rowX = x + 10;
		int rowWidth = width - 20;
		int rowY = y + 4;
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Terminals",
			iwf.isShowTerminals(), () -> { iwf.setShowTerminals(!iwf.isShowTerminals()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Devices",
			iwf.isShowDevices(), () -> { iwf.setShowDevices(!iwf.isShowDevices()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Levers",
			iwf.isShowLevers(), () -> { iwf.setShowLevers(!iwf.isShowLevers()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Titles",
			iwf.isRenderText(), () -> { iwf.setRenderText(!iwf.isRenderText()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Block Highlight",
			iwf.isRenderBox(), () -> { iwf.setRenderBox(!iwf.isRenderBox()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Default Vanilla Names",
			iwf.isHideDefaultNames(), () -> { iwf.setHideDefaultNames(!iwf.isHideDefaultNames()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Sync With Class",
			iwf.isSyncWithClass(), () -> { iwf.setSyncWithClass(!iwf.isSyncWithClass()); ConfigManager.save(); });
		// Merged in from the now-deleted Terminal Hitboxes module (per user request — "The terminal hitboxes
		// can be a part of the 'inactive waypoints' module. Make it into a subtoggle").
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Terminal Hitbox as Highlight",
			iwf.isTerminalHitboxAsHighlight(), () -> { iwf.setTerminalHitboxAsHighlight(!iwf.isTerminalHitboxAsHighlight()); ConfigManager.save(); });
		rowY += 8;
		colorPickerLabel(graphics, rowX, rowY, "Highlight Color");
		drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "inactive_waypoints_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
			iwf::getColor, c -> { iwf.setColor((iwf.getColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0x66FFFF55);
	}

	/** The 9 dungeon object-hide toggles this module consolidates — see DungeonDeclutterFeature's own doc
	 *  comment for why they used to be 9 separate top-level rows instead of subtoggles of one module. */
	private void drawDungeonDeclutterContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature ddf,
											   int x, int y, int width, int height) {
		int rowX = x + 10;
		int rowWidth = width - 20;
		int rowY = y + 4;
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Superboom TNT",
			ddf.isHideSuperboomTnt(), () -> { ddf.setHideSuperboomTnt(!ddf.isHideSuperboomTnt()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Blessings",
			ddf.isHideBlessings(), () -> { ddf.setHideBlessings(!ddf.isHideBlessings()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Revive Stones",
			ddf.isHideReviveStones(), () -> { ddf.setHideReviveStones(!ddf.isHideReviveStones()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Premium Flesh",
			ddf.isHidePremiumFlesh(), () -> { ddf.setHidePremiumFlesh(!ddf.isHidePremiumFlesh()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Journal Entry",
			ddf.isHideJournalEntry(), () -> { ddf.setHideJournalEntry(!ddf.isHideJournalEntry()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Healer Orbs",
			ddf.isHideHealerOrbs(), () -> { ddf.setHideHealerOrbs(!ddf.isHideHealerOrbs()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Skeleton Skull",
			ddf.isHideSkeletonSkull(), () -> { ddf.setHideSkeletonSkull(!ddf.isHideSkeletonSkull()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Healer Fairy",
			ddf.isHideHealerFairy(), () -> { ddf.setHideHealerFairy(!ddf.isHideHealerFairy()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Soulweaver Skulls",
			ddf.isHideSoulweaverSkulls(), () -> { ddf.setHideSoulweaverSkulls(!ddf.isHideSoulweaverSkulls()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Wither Skulls",
			ddf.isHideWitherSkulls(), () -> { ddf.setHideWitherSkulls(!ddf.isHideWitherSkulls()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Skull Message Remover",
			ddf.isHideSkullMessages(), () -> { ddf.setHideSkullMessages(!ddf.isHideSkullMessages()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Milestone Message Remover",
			ddf.isHideMilestoneMessages(), () -> { ddf.setHideMilestoneMessages(!ddf.isHideMilestoneMessages()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Ability Text Remover",
			ddf.isHideAbilityMessages(), () -> { ddf.setHideAbilityMessages(!ddf.isHideAbilityMessages()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Solo Class Stats Message",
			ddf.isSoloClassStatsMessage(), () -> { ddf.setSoloClassStatsMessage(!ddf.isSoloClassStatsMessage()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Dungeon Potion Reminder",
			ddf.isHideDungeonPotionReminder(), () -> { ddf.setHideDungeonPotionReminder(!ddf.isHideDungeonPotionReminder()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Dungeonbreaker Messages",
			ddf.isHideDungeonbreakerMessages(), () -> { ddf.setHideDungeonbreakerMessages(!ddf.isHideDungeonbreakerMessages()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Dungeon Mob Messages",
			ddf.isHideDungeonMobMessages(), () -> { ddf.setHideDungeonMobMessages(!ddf.isHideDungeonMobMessages()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Unable to Teleport Message",
			ddf.isHideUnableToTeleport(), () -> { ddf.setHideUnableToTeleport(!ddf.isHideUnableToTeleport()); ConfigManager.save(); });
		// Moved here from Chat De-clutter (per user request — dungeon-specific voice-line spam). Hides Oruo
		// the Omniscient's Quiz-puzzle dialogue lines by speaker prefix only (see
		// DungeonDeclutterFeature.ORUO_PREFIX) — his questions are randomized text.
		drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Oruo Messages",
			ddf.isHideOruoMessages(), () -> { ddf.setHideOruoMessages(!ddf.isHideOruoMessages()); ConfigManager.save(); });
	}

	/** Per user request ("Allow users also to toggle the ones they want"): one enable checkbox per Leap
	 *  Counter zone, in the same fixed order as {@link com.cokelord.skyblocksimplified.feature.impl.LeapCounterFeature#getZoneLabels()}. */
	private void drawLeapCounterContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.LeapCounterFeature lcf,
										 int x, int y, int width, int height, int accent) {
		int rowX = x + 10;
		int rowWidth = width - 20;
		int rowY = y + 4;
		for (String[] zone : lcf.getZoneLabels()) {
			String key = zone[0];
			String label = zone[1];
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, label,
				lcf.isZoneEnabled(key), () -> { lcf.setZoneEnabled(key, !lcf.isZoneEnabled(key)); ConfigManager.save(); });
		}
		rowY += 8;
		// Per user request ("&h means custom color, that the user can choose in the settings for that
		// module"): colors the "/" between the count and its required amount (see LeapCounterFeature's own
		// renderZones/drawZoneLine) — the count digit itself and the icon are colored automatically by how
		// close the count is to the required amount, not by this setting.
		colorPickerLabel(graphics, rowX, rowY, "Custom Color");
		drawFullColorPicker(graphics, rowX, rowY + 10, rowWidth, "leap_counter_custom", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
			lcf::getCustomColor, c -> { lcf.setCustomColor((lcf.getCustomColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFFFFFF);
	}

	/** Config export/import, drawn directly in its own always-visible module-list row: "Config" label,
	 *  a paste box that fills the space between it and the export button, and the export button itself —
	 *  per user request, as simple as that, no cog/expand step needed. Import happens automatically the
	 *  moment a config string lands in the box (Enter or Ctrl+V — see attemptConfigImport's callers in
	 *  keyPressed), so there's no separate Import button to click; Export still gives chat feedback
	 *  (character count / failure) rather than a new GUI status-label system, matching this project's
	 *  established one-off-action-feedback pattern (Slot Binds, Slot Locking). */
	private void drawConfigRowInline(GuiGraphicsExtractor graphics, Feature feature, int rowX, int rowTop, int rowWidth, int rowInnerHeight, int accent) {
		configRowVisible = true;
		String label = feature.getDisplayName();
		int labelWidth = this.font.width(label) + 16;

		configExportButtonHeight = 18;
		configExportButtonY = rowTop + (rowInnerHeight - configExportButtonHeight) / 2;
		String exportLabel = "Export to Clipboard";
		configExportButtonWidth = this.font.width(exportLabel) + 12;
		configExportButtonX = rowX + rowWidth - configExportButtonWidth - 4;
		fillRounded(graphics, configExportButtonX, configExportButtonY, configExportButtonX + configExportButtonWidth,
			configExportButtonY + configExportButtonHeight, BOX_RADIUS, accent);
		SmoothTextRenderer.draw(graphics, this.font, exportLabel, configExportButtonX + (configExportButtonWidth - this.font.width(exportLabel)) / 2,
			configExportButtonY + (configExportButtonHeight - 8) / 2, 0xFFFFFFFF);

		configImportBoxHeight = 18;
		configImportBoxY = rowTop + (rowInnerHeight - configImportBoxHeight) / 2;
		configImportBoxX = rowX + labelWidth;
		configImportBoxWidth = configExportButtonX - 8 - configImportBoxX;
		drawTextField(graphics, configImportBoxX, configImportBoxY, Math.max(0, configImportBoxWidth), configImportBoxHeight,
			configImportBuffer.isEmpty() && textFocus != TextFocus.CONFIG_IMPORT ? "§7Paste configs here" : configImportBuffer,
			textFocus == TextFocus.CONFIG_IMPORT);
	}

	/** Mod Information's row — same "always-visible single-line bar, nothing to click" shape as
	 *  {@link #drawConfigRowInline}. Shows the currently running version next to the row's own label (drawn
	 *  by the caller, same as every other row), and a colored Fully Tested/Untested indicator right-aligned.
	 *  "Fully Tested" means this exact running version is the one {@link
	 *  com.cokelord.skyblocksimplified.api.UpdateApi} last confirmed as the signed latest.json's version —
	 *  since latest.json only ever gets updated after a build has actually been tested live (see this
	 *  project's own release process), a match means this build went through that process; anything else
	 *  (ahead, behind, or simply a different dev build) hasn't. While the update check hasn't returned a
	 *  latest version yet (fresh launch, still polling, or a failed fetch), shown as gray "Checking..."
	 *  rather than guessing either way. */
	/** Content height for ModInformationFeature's expanded settings panel — the patch-notes body's own
	 *  length is unpredictable (whatever the user typed on GitHub), so unlike every other feature's fixed
	 *  constant-sum height calc, this one actually wraps the real fetched text against the panel's real
	 *  content width to count real line rows. panelWidth is only set once init() has run, which it always
	 *  has by the time this can be called (a settings panel can't be expanded before the screen itself has
	 *  opened), same assumption drawDungeonRoutesContent's own panelWidth use already relies on. */
	private int modInformationPatchNotesHeight() {
		int width = Math.max(1, panelWidth - SIDEBAR_WIDTH - 16) - 20;
		com.cokelord.skyblocksimplified.api.GitHubReleaseApi.State state = com.cokelord.skyblocksimplified.api.GitHubReleaseApi.getState();
		if (state != com.cokelord.skyblocksimplified.api.GitHubReleaseApi.State.FOUND) {
			return 4 + 18 + 8 /* one status line + padding */;
		}
		String body = com.cokelord.skyblocksimplified.api.GitHubReleaseApi.getReleaseBody();
		int bodyLines = (body == null || body.isBlank()) ? 1
			: this.font.split(Component.literal(body), width).size();
		return 4 + 18 /* "Patch Notes" heading */ + bodyLines * 10 + 8;
	}

	private void drawModInformationRowInline(GuiGraphicsExtractor graphics, int rowX, int rowTop, int rowWidth, int rowInnerHeight) {
		String label = "Mod Information";
		int labelWidth = this.font.width(label) + 16;

		String currentVersion = com.cokelord.skyblocksimplified.api.UpdateApi.getCurrentVersion();
		String versionText = "v" + currentVersion;
		SmoothTextRenderer.draw(graphics, this.font, versionText, rowX + labelWidth, rowTop + (rowInnerHeight - 8) / 2, Theme.text(0xFFAAAAAA));

		String latestVersion = com.cokelord.skyblocksimplified.api.UpdateApi.getLatestVersion();
		String statusText;
		int statusColor;
		if (latestVersion == null) {
			statusText = "Checking...";
			statusColor = 0xFFAAAAAA;
		} else if (latestVersion.equals(currentVersion)) {
			statusText = "Fully Tested.";
			statusColor = 0xFF55FF55;
		} else {
			statusText = "Untested.";
			statusColor = 0xFFFF5555;
		}
		// Ends clear of the cog rather than the row's raw right edge — per user follow-up ("Move cog further
		// left in Mod Information row"), the earlier fixed -4px cog nudge was too small for "Fully
		// Tested."/"Checking..." to not land inside the cog itself. Mirrors the exact cogX formula the main
		// row-drawing loop now uses for every no-toggle-content feature (12px cog + 8px cog gap + 8px slot
		// gap = 28px from the row's right edge), plus 6px of its own clearance so the text never touches it.
		int statusX = rowX + rowWidth - this.font.width(statusText) - 34;
		// Per user report, this text sat 1px too high relative to the row's other text baselines.
		SmoothTextRenderer.draw(graphics, this.font, statusText, statusX, rowTop + (rowInnerHeight - 8) / 2 + 1, statusColor);
	}

	/** Hypixel Mod API's row — same "always-visible single-line bar, nothing to click" shape as {@link
	 *  #drawModInformationRowInline}, replacing what used to be a genuinely pointless on/off toggle (see
	 *  {@link com.cokelord.skyblocksimplified.feature.impl.HypixelModApiDependencyFeature}'s own doc comment
	 *  for why). Right-aligned status text: green confirmation when the companion "Hypixel Mod API" Fabric
	 *  mod is actually installed, or an amber warning explaining the upside of installing it when it's not —
	 *  a real informational banner instead of a switch with no real reason to flip it off. */
	private void drawHypixelModApiRowInline(GuiGraphicsExtractor graphics, int rowX, int rowTop, int rowWidth, int rowInnerHeight) {
		boolean installed = com.cokelord.skyblocksimplified.feature.impl.HypixelModApiDependencyFeature.isCompanionModInstalled();
		// Per user request: the long "Detected - using precise location data" explanation was more detail
		// than this always-visible banner needed — just a plain "Active" now.
		String statusText = installed ? "Active" : "Not installed — install for more reliable location detection.";
		int statusColor = installed ? 0xFF55FF55 : 0xFFFFAA00;
		int statusX = rowX + rowWidth - this.font.width(statusText) - 4;
		SmoothTextRenderer.draw(graphics, this.font, statusText, statusX, rowTop + (rowInnerHeight - 8) / 2, statusColor);
	}

	/** Dungeons Copilot's own standalone export/import row — same shape as drawConfigRowInline above, but
	 *  scoped to just this module's plan and drawn inline within its own expanded settings panel (per user
	 *  request: "like the mod itself... under the 'Enabled for class' subtoggle"). Returns the Y position
	 *  right after the row, matching every other row-drawing helper's own convention. */
	private int drawCopilotConfigRow(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf,
									  int rowX, int rowY, int rowWidth, int accent) {
		String label = "Config:";
		int labelWidth = this.font.width(label) + 8;
		int rowHeight = 18;

		copilotExportButtonHeight = rowHeight;
		copilotExportButtonY = rowY;
		String exportLabel = "Export";
		copilotExportButtonWidth = this.font.width(exportLabel) + 12;
		copilotExportButtonX = rowX + rowWidth - copilotExportButtonWidth;
		fillRounded(graphics, copilotExportButtonX, copilotExportButtonY, copilotExportButtonX + copilotExportButtonWidth,
			copilotExportButtonY + copilotExportButtonHeight, BOX_RADIUS, accent);
		SmoothTextRenderer.draw(graphics, this.font, exportLabel, copilotExportButtonX + (copilotExportButtonWidth - this.font.width(exportLabel)) / 2,
			copilotExportButtonY + (copilotExportButtonHeight - 8) / 2, 0xFFFFFFFF);

		SmoothTextRenderer.draw(graphics, this.font, label, rowX, rowY + (rowHeight - 8) / 2, Theme.text(0xFFAAAAAA));
		copilotImportBoxHeight = rowHeight;
		copilotImportBoxY = rowY;
		copilotImportBoxX = rowX + labelWidth;
		copilotImportBoxWidth = copilotExportButtonX - 8 - copilotImportBoxX;
		drawTextField(graphics, copilotImportBoxX, copilotImportBoxY, Math.max(0, copilotImportBoxWidth), copilotImportBoxHeight,
			copilotImportBuffer.isEmpty() && textFocus != TextFocus.COPILOT_IMPORT ? "§7Paste Boss Guide plan here" : copilotImportBuffer,
			textFocus == TextFocus.COPILOT_IMPORT);
		return rowY + rowHeight;
	}

	/** Positional Messages' own standalone export/import row (per user request: "Positional messages need an
	 *  export feature") — same shape as {@link #drawCopilotConfigRow}. */
	private int drawPosMsgConfigRow(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf,
									 int rowX, int rowY, int rowWidth, int accent) {
		String label = "Config:";
		int labelWidth = this.font.width(label) + 8;
		int rowHeight = 18;

		posMsgConfigExportButtonHeight = rowHeight;
		posMsgConfigExportButtonY = rowY;
		String exportLabel = "Export";
		posMsgConfigExportButtonWidth = this.font.width(exportLabel) + 12;
		posMsgConfigExportButtonX = rowX + rowWidth - posMsgConfigExportButtonWidth;
		fillRounded(graphics, posMsgConfigExportButtonX, posMsgConfigExportButtonY, posMsgConfigExportButtonX + posMsgConfigExportButtonWidth,
			posMsgConfigExportButtonY + posMsgConfigExportButtonHeight, BOX_RADIUS, accent);
		SmoothTextRenderer.draw(graphics, this.font, exportLabel, posMsgConfigExportButtonX + (posMsgConfigExportButtonWidth - this.font.width(exportLabel)) / 2,
			posMsgConfigExportButtonY + (posMsgConfigExportButtonHeight - 8) / 2, 0xFFFFFFFF);

		SmoothTextRenderer.draw(graphics, this.font, label, rowX, rowY + (rowHeight - 8) / 2, Theme.text(0xFFAAAAAA));
		posMsgConfigImportBoxHeight = rowHeight;
		posMsgConfigImportBoxY = rowY;
		posMsgConfigImportBoxX = rowX + labelWidth;
		posMsgConfigImportBoxWidth = posMsgConfigExportButtonX - 8 - posMsgConfigImportBoxX;
		drawTextField(graphics, posMsgConfigImportBoxX, posMsgConfigImportBoxY, Math.max(0, posMsgConfigImportBoxWidth), posMsgConfigImportBoxHeight,
			posMsgConfigImportBuffer.isEmpty() && textFocus != TextFocus.POSMSG_CONFIG_IMPORT ? "§7Paste Positional Messages config here" : posMsgConfigImportBuffer,
			textFocus == TextFocus.POSMSG_CONFIG_IMPORT);
		return rowY + rowHeight;
	}

	/** Overall export/import row — every room's routes at once, shown on the room grid. Same shape as
	 *  {@link #drawCopilotConfigRow}. */
	private int drawRouteAllConfigRow(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf,
									   int rowX, int rowY, int rowWidth, int accent) {
		String label = "All Rooms:";
		int labelWidth = this.font.width(label) + 8;
		int rowHeight = 18;

		routeAllExportButtonHeight = rowHeight;
		routeAllExportButtonY = rowY;
		String exportLabel = "Export";
		routeAllExportButtonWidth = this.font.width(exportLabel) + 12;
		routeAllExportButtonX = rowX + rowWidth - routeAllExportButtonWidth;
		fillRounded(graphics, routeAllExportButtonX, routeAllExportButtonY, routeAllExportButtonX + routeAllExportButtonWidth,
			routeAllExportButtonY + routeAllExportButtonHeight, BOX_RADIUS, accent);
		SmoothTextRenderer.draw(graphics, this.font, exportLabel, routeAllExportButtonX + (routeAllExportButtonWidth - this.font.width(exportLabel)) / 2,
			routeAllExportButtonY + (routeAllExportButtonHeight - 8) / 2, 0xFFFFFFFF);

		SmoothTextRenderer.draw(graphics, this.font, label, rowX, rowY + (rowHeight - 8) / 2, Theme.text(0xFFAAAAAA));
		routeAllImportBoxHeight = rowHeight;
		routeAllImportBoxY = rowY;
		routeAllImportBoxX = rowX + labelWidth;
		routeAllImportBoxWidth = routeAllExportButtonX - 8 - routeAllImportBoxX;
		drawTextField(graphics, routeAllImportBoxX, routeAllImportBoxY, Math.max(0, routeAllImportBoxWidth), routeAllImportBoxHeight,
			routeAllImportBuffer.isEmpty() && textFocus != TextFocus.ROUTE_ALL_IMPORT ? "§7Paste all-rooms export, or a SecretRoutes file, here" : routeAllImportBuffer,
			textFocus == TextFocus.ROUTE_ALL_IMPORT);
		return rowY + rowHeight;
	}

	/** Per user request ("Find a way to import routes from SecretRoutes mod... make our routes syntax into
	 *  their syntax so it automatically gets [supported]") — the same "All Rooms" paste box already used for
	 *  this mod's own export string now ALSO accepts a raw SecretRoutes route file pasted straight from the
	 *  user's downloads folder (routes.json/pearlroutes.json/etc), auto-detected by trying our own prefixed
	 *  format first and falling back to {@link com.cokelord.skyblocksimplified.dungeon.SecretRoutesImporter}
	 *  when that fails and the pasted text actually looks like JSON — no separate button/UI needed for a
	 *  second import format. */
	private void attemptRouteAllImport() {
		if (routeAllImportBuffer.isEmpty()) return;
		if (!(expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf)) return;
		boolean applied = drf.importAllFromClipboardString(routeAllImportBuffer);
		String msg;
		if (applied) {
			msg = "§aImported Dungeon Routes (all rooms).";
		} else if (routeAllImportBuffer.strip().startsWith("{")) {
			com.cokelord.skyblocksimplified.dungeon.SecretRoutesImporter.ImportResult result =
				com.cokelord.skyblocksimplified.dungeon.SecretRoutesImporter.importJson(routeAllImportBuffer, drf);
			if (result.roomsMatched() > 0) {
				applied = true;
				msg = "§aImported " + result.roomsMatched() + " room(s) / " + result.stepsImported()
					+ " step(s) from a SecretRoutes file." + (result.roomsUnmatched() > 0
					? " (" + result.roomsUnmatched() + " room(s) had no matching name here.)" : "");
			} else {
				msg = "§cThat doesn't look like a valid Dungeon Routes or SecretRoutes export.";
			}
		} else {
			msg = "§cThat doesn't look like a valid all-rooms Dungeon Routes string.";
		}
		if (this.minecraft.player != null) {
			this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(msg));
		}
		if (applied) {
			routeAllImportBuffer = "";
			textFocus = TextFocus.NONE;
		}
	}

	/** Per-room export/import row, shown inside a room's own step editor. Same shape as {@link
	 *  #drawCopilotConfigRow}, scoped to just {@code roomName}'s own steps. */
	private int drawRouteRoomConfigRow(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf,
										String roomName, int rowX, int rowY, int rowWidth, int accent) {
		String label = "Room:";
		int labelWidth = this.font.width(label) + 8;
		int rowHeight = 18;

		routeRoomExportButtonHeight = rowHeight;
		routeRoomExportButtonY = rowY;
		String exportLabel = "Export";
		routeRoomExportButtonWidth = this.font.width(exportLabel) + 12;
		routeRoomExportButtonX = rowX + rowWidth - routeRoomExportButtonWidth;
		fillRounded(graphics, routeRoomExportButtonX, routeRoomExportButtonY, routeRoomExportButtonX + routeRoomExportButtonWidth,
			routeRoomExportButtonY + routeRoomExportButtonHeight, BOX_RADIUS, accent);
		SmoothTextRenderer.draw(graphics, this.font, exportLabel, routeRoomExportButtonX + (routeRoomExportButtonWidth - this.font.width(exportLabel)) / 2,
			routeRoomExportButtonY + (routeRoomExportButtonHeight - 8) / 2, 0xFFFFFFFF);

		SmoothTextRenderer.draw(graphics, this.font, label, rowX, rowY + (rowHeight - 8) / 2, Theme.text(0xFFAAAAAA));
		routeRoomImportBoxHeight = rowHeight;
		routeRoomImportBoxY = rowY;
		routeRoomImportBoxX = rowX + labelWidth;
		routeRoomImportBoxWidth = routeRoomExportButtonX - 8 - routeRoomImportBoxX;
		drawTextField(graphics, routeRoomImportBoxX, routeRoomImportBoxY, Math.max(0, routeRoomImportBoxWidth), routeRoomImportBoxHeight,
			routeRoomImportBuffer.isEmpty() && textFocus != TextFocus.ROUTE_ROOM_IMPORT ? "§7Paste this room's export here" : routeRoomImportBuffer,
			textFocus == TextFocus.ROUTE_ROOM_IMPORT);
		return rowY + rowHeight;
	}

	private void attemptRouteRoomImport() {
		if (routeRoomImportBuffer.isEmpty() || selectedRouteRoomName == null) return;
		if (!(expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf)) return;
		boolean applied = drf.importRoomFromClipboardString(selectedRouteRoomName, routeRoomImportBuffer);
		if (this.minecraft.player != null) {
			String msg = applied ? "§aImported route for " + selectedRouteRoomName + "." : "§cThat doesn't look like a valid Dungeon Routes room string.";
			this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(msg));
		}
		if (applied) {
			routeRoomImportBuffer = "";
			textFocus = TextFocus.NONE;
		}
	}

	/** Shared by Enter and Ctrl+V while the Copilot import box is focused (see keyPressed) — imports
	 *  whatever's currently in the buffer, same feedback shape as attemptConfigImport below. */
	private void attemptCopilotImport() {
		if (copilotImportBuffer.isEmpty()) return;
		if (!(expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf)) return;
		boolean applied = dcf.importFromClipboardString(copilotImportBuffer);
		if (this.minecraft.player != null) {
			String msg = applied ? "§aImported Boss Guide plan." : "§cThat doesn't look like a valid Boss Guide config string.";
			this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(msg));
		}
		if (applied) {
			copilotImportBuffer = "";
			textFocus = TextFocus.NONE;
		}
	}

	/** Shared by Enter and Ctrl+V while the Positional Messages import box is focused — same shape as
	 *  {@link #attemptCopilotImport}. */
	private void attemptPosMsgConfigImport() {
		if (posMsgConfigImportBuffer.isEmpty()) return;
		if (!(expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf)) return;
		boolean applied = pmf.importFromClipboardString(posMsgConfigImportBuffer);
		if (this.minecraft.player != null) {
			String msg = applied ? "§aImported Positional Messages config." : "§cThat doesn't look like a valid Positional Messages config string.";
			this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(msg));
		}
		if (applied) {
			posMsgConfigImportBuffer = "";
			textFocus = TextFocus.NONE;
		}
	}

	/** Shared by Enter and Ctrl+V while the config paste box is focused (see keyPressed) — imports
	 *  whatever's currently in the buffer and reports the result the same way the old Import button did. */
	private void attemptConfigImport() {
		if (configImportBuffer.isEmpty()) return;
		int applied = ConfigManager.importFromClipboardString(configImportBuffer);
		if (this.minecraft.player != null) {
			String msg = applied >= 0
				? "§aImported " + applied + " module setting" + (applied == 1 ? "" : "s") + "."
				: "§cThat doesn't look like a valid config string.";
			this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(msg));
		}
		if (applied >= 0) {
			configImportBuffer = "";
			textFocus = TextFocus.NONE;
		}
	}

	/** Visual Words panel: a plain list of (find, replace) rows plus an "+" add button — same shape as
	 *  drawEnchantParsingContent, just with two plain text fields per row instead of one text/one level
	 *  field plus style buttons. */
	private void drawVisualWordsContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature vwf,
										 int x, int y, int width, int accent) {
		int rowY = y + 4;
		SmoothTextRenderer.draw(graphics, this.font, "Replaces matching text anywhere it's visible in chat/system messages.", x + 10, rowY, Theme.text(0xFF888888));
		rowY += 14;

		List<com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature.WordReplacement> list = vwf.getReplacements();
		for (int i = 0; i < list.size(); i++) {
			rowY = drawVisualWordRow(graphics, list.get(i), i, x, rowY, width);
			rowY += 4;
		}

		addVisualWordButtonX = x + 8;
		addVisualWordButtonY = rowY + 2;
		addVisualWordButtonSize = 16;
		fillRounded(graphics, addVisualWordButtonX, addVisualWordButtonY, addVisualWordButtonX + addVisualWordButtonSize,
			addVisualWordButtonY + addVisualWordButtonSize, BOX_RADIUS, accent);
		SmoothTextRenderer.draw(graphics, this.font, "+", addVisualWordButtonX + (addVisualWordButtonSize - this.font.width("+")) / 2,
			addVisualWordButtonY + (addVisualWordButtonSize - 8) / 2, 0xFFFFFFFF);
	}

	private static final int COLOR_PLACEHOLDER_TEXT = 0xFF777777;

	private int drawVisualWordRow(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature.WordReplacement r,
								   int index, int x, int y, int width) {
		int rowHeight = 16;
		int btnSize = 12;

		int removeX = x + width - 8 - btnSize;
		int removeY = y + 2;
		fillCircle(graphics, removeX + btnSize / 2, removeY + btnSize / 2, btnSize / 2, COLOR_CLOSE_BUTTON);
		SmoothTextRenderer.draw(graphics, this.font, "x", removeX + (btnSize - this.font.width("x")) / 2, removeY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(removeX, removeY, btnSize, btnSize, () -> {
			if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature vwf) vwf.removeReplacement(index);
		}));

		int fieldsRight = removeX - 8;
		int gap = 6;
		int fieldWidth = Math.max(20, (fieldsRight - (x + 8) - gap) / 2);

		int findBoxX = x + 8;
		drawVisualWordField(graphics, findBoxX, y, fieldWidth, rowHeight, r.find, "Text that will be replaced",
			TextFocus.VISUALWORD_FIND, index);
		visualWordFindBoxes.add(new int[]{findBoxX, y, fieldWidth, rowHeight, index});

		int replaceBoxX = findBoxX + fieldWidth + gap;
		drawVisualWordField(graphics, replaceBoxX, y, fieldWidth, rowHeight, r.replace, "Text that will replace",
			TextFocus.VISUALWORD_REPLACE, index);
		visualWordReplaceBoxes.add(new int[]{replaceBoxX, y, fieldWidth, rowHeight, index});

		return y + rowHeight;
	}

	private void drawVisualWordField(GuiGraphicsExtractor graphics, int boxX, int boxY, int boxWidth, int boxHeight,
									  String value, String placeholder, TextFocus focusType, int index) {
		boolean editing = textFocus == focusType && editingVisualWordIndex == index;
		fillRounded(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		boolean empty = value.isEmpty();
		String display = !editing && empty ? placeholder : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, boxY + 2, boxHeight - 4);
		graphics.text(this.font, display, boxX + 4, boxY + 4, !editing && empty ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, boxY + 3, caretX + 1, boxY + boxHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
	}

	private static final int NOTIF_ROW_HEIGHT = 16;
	private static final int NOTIF_SWATCH_SIZE = 12;
	// Per user request ("the dungeon notifications stack currently and need a bit more room"): extra vertical
	// gap after each notification type's own row group, before the next type's rows begin.
	private static final int NOTIF_TYPE_GAP = 6;

	/** Per user request ("Allow users to change the color with a hex picker KIND OF... a little button with
	 *  the current color, and clicking that should bring up a hex picker in another window, not in the mod
	 *  menu itself"): an editable title-text box (blank = use this type's own built-in default text) plus a
	 *  swatch button that opens the same kind of small popup color picker the enchant rules' color button
	 *  already uses, rather than an inline full color picker eating a chunk of the panel per notification. */
	private int drawDungeonNotifTitleRow(GuiGraphicsExtractor graphics, int x, int y, int width,
										  com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature dnf,
										  com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType type) {
		int swatchX = x + width - NOTIF_SWATCH_SIZE;
		int swatchY = y + (NOTIF_ROW_HEIGHT - NOTIF_SWATCH_SIZE) / 2;
		fillRounded(graphics, swatchX, swatchY, swatchX + NOTIF_SWATCH_SIZE, swatchY + NOTIF_SWATCH_SIZE, SMALL_RADIUS,
			dnf.getTypeColor(type) | 0xFF000000);
		clickHits.add(new ClickHit(swatchX, swatchY, NOTIF_SWATCH_SIZE, NOTIF_SWATCH_SIZE, () -> {
			dungeonNotifColorPopupTarget = type;
			popupAnchorX = swatchX;
			popupAnchorY = swatchY + NOTIF_SWATCH_SIZE + 2;
		}));

		int boxX = x;
		int boxWidth = Math.max(20, swatchX - 4 - x);
		boolean editing = textFocus == TextFocus.DUNGEON_NOTIF_TITLE && editingNotifType == type;
		String value = dnf.getTypeTitleRaw(type);
		fillRounded(graphics, boxX, y, boxX + boxWidth, y + NOTIF_ROW_HEIGHT, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, y, boxX + boxWidth, y + NOTIF_ROW_HEIGHT);
		String display = !editing && value.isEmpty() ? type.displayName + "!" : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, y + 2, NOTIF_ROW_HEIGHT - 4);
		graphics.text(this.font, display, boxX + 4, y + 4, !editing && value.isEmpty() ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, y + 3, caretX + 1, y + NOTIF_ROW_HEIGHT - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		dungeonNotifTitleBoxes.add(new int[]{boxX, y, boxWidth, NOTIF_ROW_HEIGHT, type.ordinal()});
		return y + NOTIF_ROW_HEIGHT + 3;
	}

	/** Party-chat message row, only shown for the score-threshold types — an editable message box plus a
	 *  small on/off switch for whether it actually gets sent (typing a message alone shouldn't start
	 *  spamming party chat until the user explicitly turns it on). */
	private int drawDungeonNotifPartyRow(GuiGraphicsExtractor graphics, int x, int y, int width,
										  com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature dnf,
										  com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType type) {
		int toggleW = 28, toggleH = 12;
		int tx = x + width - toggleW;
		int ty = y + (NOTIF_ROW_HEIGHT - toggleH) / 2;
		boolean sendEnabled = dnf.isPartyChatEnabled(type);
		float t = toggleAnimValue("dungeon_notif_party_" + type.name(), sendEnabled);
		RenderUtil.fillPill(graphics, tx, ty, tx + toggleW, ty + toggleH, FULL_ROUND, RenderUtil.lerpColor(colorToggleOff(), colorToggleOn(), t));
		int knob = toggleH - 4;
		int kx = tx + 2 + Math.round(t * (toggleW - knob - 4));
		fillCircle(graphics, kx + knob / 2, ty + 2 + knob / 2, knob / 2, RenderUtil.lerpColor(colorKnobOff(), COLOR_KNOB_ON, t));
		clickHits.add(new ClickHit(tx, ty, toggleW, toggleH, () -> {
			playToggleSound(!sendEnabled);
			dnf.setPartyChatEnabled(type, !dnf.isPartyChatEnabled(type));
			ConfigManager.save();
		}));

		int boxX = x;
		int boxWidth = Math.max(20, tx - 4 - x);
		boolean editing = textFocus == TextFocus.DUNGEON_NOTIF_PARTY_MSG && editingNotifType == type;
		String value = dnf.getPartyChatMessage(type);
		fillRounded(graphics, boxX, y, boxX + boxWidth, y + NOTIF_ROW_HEIGHT, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, y, boxX + boxWidth, y + NOTIF_ROW_HEIGHT);
		String placeholder = "Party chat message...";
		String display = !editing && value.isEmpty() ? placeholder : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, y + 2, NOTIF_ROW_HEIGHT - 4);
		graphics.text(this.font, display, boxX + 4, y + 4, !editing && value.isEmpty() ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, y + 3, caretX + 1, y + NOTIF_ROW_HEIGHT - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		dungeonNotifPartyMsgBoxes.add(new int[]{boxX, y, boxWidth, NOTIF_ROW_HEIGHT, type.ordinal()});
		return y + NOTIF_ROW_HEIGHT + 3;
	}

	private static final com.cokelord.skyblocksimplified.dungeon.DungeonClass[] COPILOT_CLASSES = {
		com.cokelord.skyblocksimplified.dungeon.DungeonClass.ARCHER, com.cokelord.skyblocksimplified.dungeon.DungeonClass.TANK,
		com.cokelord.skyblocksimplified.dungeon.DungeonClass.HEALER, com.cokelord.skyblocksimplified.dungeon.DungeonClass.MAGE,
		com.cokelord.skyblocksimplified.dungeon.DungeonClass.BERSERK
	};

	private static String copilotClassLabel(com.cokelord.skyblocksimplified.dungeon.DungeonClass clazz) {
		String n = clazz.name();
		return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT);
	}

	/** No mockup was provided for this module ("make your interpretation and I'll fix it") — this is this
	 *  session's own design: a class picker (which class's step list is shown below, and whether that
	 *  class's plan is currently live), an "+ Add Step" button, then every step item for that class as its
	 *  own editable block (see drawCopilotItemBlock). Row math here must stay in sync with
	 *  COPILOT_HEADER_HEIGHT/COPILOT_ITEM_HEIGHT (used by expandedContentHeight, which can't just measure
	 *  this after the fact). */
	private void drawDungeonsCopilotContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf,
											 int x, int y, int width, int accent, int mouseX, int mouseY) {
		int rowX = x + 10;
		int rowWidth = width - 20;
		int rowY = y + 4;

		int btnH = 18;
		int btnW = (rowWidth - (COPILOT_CLASSES.length - 1) * 4) / COPILOT_CLASSES.length;
		int bx = rowX;
		for (var clazz : COPILOT_CLASSES) {
			boolean selected = clazz == selectedCopilotClass;
			boolean enabled = dcf.isClassEnabled(clazz);
			int bg = selected ? accent : (enabled ? Theme.chrome(0xFF2A2A2A) : Theme.chrome(0xFF1A1A1A));
			fillRounded(graphics, bx, rowY, bx + btnW, rowY + btnH, SMALL_RADIUS, bg);
			String label = copilotClassLabel(clazz);
			SmoothTextRenderer.draw(graphics, this.font, label, bx + (btnW - this.font.width(label)) / 2, rowY + 5, enabled ? 0xFFFFFFFF : 0xFF888888);
			int fbx = bx;
			clickHits.add(new ClickHit(fbx, rowY, btnW, btnH, () -> { selectedCopilotClass = clazz; dcf.setEditModeClass(clazz); }));
			bx += btnW + 4;
		}
		rowY += btnH + 4;

		// Per user request ("remove the /bg command and add it to the boss guide itself. It should show up
		// when editing mode is on, and allow users to choose current class to display waypoints for. This
		// should override anything cause most players dont have a singleplayer world of the f7 bossfight and
		// have to make routes in-game"): replaces the old /bg singleplayer-only command family. While on,
		// whichever class tab above is currently selected overrides the live-detected class everywhere
		// (world markers, HUD title) — in a real dungeon, not just singleplayer, which /bg could never do.
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Edit Mode (preview selected class live)",
			dcf.isEditMode(), () -> { dcf.setEditMode(!dcf.isEditMode()); dcf.setEditModeClass(selectedCopilotClass); ConfigManager.save(); });

		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Enabled for " + copilotClassLabel(selectedCopilotClass),
			dcf.isClassEnabled(selectedCopilotClass), () -> { dcf.setClassEnabled(selectedCopilotClass, !dcf.isClassEnabled(selectedCopilotClass)); ConfigManager.save(); });
		// Per user request: "there should be a subtoggle under each class that all connect that shows all
		// steps at the same time" — one shared boolean, just surfaced again on every class's own tab.
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show All Steps",
			dcf.isShowAllSteps(), () -> { dcf.setShowAllSteps(!dcf.isShowAllSteps()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Show Coordinates",
			dcf.isShowCoords(), () -> { dcf.setShowCoords(!dcf.isShowCoords()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Waypoint Tracer",
			dcf.isWaypointTracer(), () -> { dcf.setWaypointTracer(!dcf.isWaypointTracer()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Waypoint Beacon Line",
			dcf.isWaypointBeaconLine(), () -> { dcf.setWaypointBeaconLine(!dcf.isWaypointBeaconLine()); ConfigManager.save(); });
		// Per user request: a sound cue for when the title text updates, and a way to strip the panel's
		// own background fill.
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Title Update Sound",
			dcf.isTitleUpdateSoundEnabled(), () -> { dcf.setTitleUpdateSoundEnabled(!dcf.isTitleUpdateSoundEnabled()); ConfigManager.save(); });
		if (dcf.isTitleUpdateSoundEnabled()) {
			rowY = drawSoundOptionRows(graphics, rowX, rowY, rowWidth, accent, dcf.getTitleUpdateSound());
		}
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Hide Title Background",
			dcf.isHideTitleBackground(), () -> { dcf.setHideTitleBackground(!dcf.isHideTitleBackground()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Bold Title",
			dcf.isBoldTitle(), () -> { dcf.setBoldTitle(!dcf.isBoldTitle()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Italic Title",
			dcf.isItalicTitle(), () -> { dcf.setItalicTitle(!dcf.isItalicTitle()); ConfigManager.save(); });
		rowY += 4;
		rowY = drawCopilotConfigRow(graphics, dcf, rowX, rowY, rowWidth, accent);
		rowY += 4;

		int addH = 16;
		fillRounded(graphics, rowX, rowY, rowX + rowWidth, rowY + addH, BOX_RADIUS, accent);
		String addLabel = "+ Add Step";
		SmoothTextRenderer.draw(graphics, this.font, addLabel, rowX + (rowWidth - this.font.width(addLabel)) / 2, rowY + 4, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(rowX, rowY, rowWidth, addH, () -> dcf.addItem(selectedCopilotClass)));
		// Per user request ("Also add the little plus button like we did in dungeon routes"): same
		// off-screen fallback as routeAddStepAction (see its own doc comment) for this panel's own button.
		if (rowY + addH < listViewportY0 || rowY > listViewportY1) {
			var addClass = selectedCopilotClass;
			copilotAddStepAction = () -> dcf.addItem(addClass);
		}
		rowY += addH + 6;

		var classItems = dcf.getItemsForClass(selectedCopilotClass);
		classItems.sort((a, b) -> a.stepNumber - b.stepNumber);
		for (var item : classItems) {
			rowY = drawCopilotItemBlock(graphics, rowX, rowY, rowWidth, dcf, item, accent, mouseX, mouseY);
		}

		// Real bug found (per user report — "the color selector also doesnt work like the notifications"):
		// same root cause as Dungeon Notifications' own swatch — drawEnchantStylePopups (which already
		// contains copilotColorPopupTarget's draw block) was never actually being called while this panel
		// was open, so the swatch set the target field but nothing ever rendered or hit-tested. See the
		// Dungeon Notifications branch in drawExpandedSettings for the identical fix/explanation.
		if (isStylePopupOpen()) {
			popupHitsStartClick = clickHits.size();
			popupHitsStartColorSquare = colorSquareHits.size();
			popupHitsStartSlider = sliderHits.size();
			popupHitsStartHex = hexFieldBounds.size();
			graphics.disableScissor();
			graphics.disableScissor();
			drawEnchantStylePopups(graphics, accent);
			graphics.enableScissor(listViewportX0, listViewportY0, listViewportX1, listViewportY1);
			graphics.enableScissor(x, y, x + width, y + Math.round(expandedContentHeight(dcf)));
		}
	}

	/** One step item's editable block: header (step number stepper, type cycle, advancer toggle, color
	 *  swatch, remove), a chat-message trigger field, a split-name + offset trigger row, then whichever
	 *  type-specific fields that step's type needs. */
	private int drawCopilotItemBlock(GuiGraphicsExtractor graphics, int x, int y, int width,
									  com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf,
									  com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item, int accent,
									  int mouseX, int mouseY) {
		int btnSize = 14;
		int cursorX = x + width - btnSize;

		fillCircle(graphics, cursorX + btnSize / 2, y + btnSize / 2, btnSize / 2, COLOR_CLOSE_BUTTON);
		SmoothTextRenderer.draw(graphics, this.font, "x", cursorX + (btnSize - this.font.width("x")) / 2, y + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(cursorX, y, btnSize, btnSize, () -> dcf.removeItem(item)));
		cursorX -= btnSize + 4;

		fillRounded(graphics, cursorX, y, cursorX + btnSize, y + btnSize, SMALL_RADIUS, copilotItemColor(item) | 0xFF000000);
		int swX = cursorX, swY = y;
		clickHits.add(new ClickHit(cursorX, y, btnSize, btnSize, () -> {
			copilotColorPopupTarget = item;
			popupAnchorX = swX;
			popupAnchorY = swY + btnSize + 2;
		}));
		cursorX -= btnSize + 4;

		String typeLabel = switch (item.type) {
			case TITLE -> "Title";
			case BREAKABLE_BLOCKS -> "Blocks";
			case WAYPOINT -> "Waypoint";
			case DEVICE_FINISHED -> "Device";
			case LEAP_USED -> "Leap";
			case TERMINAL_DONE -> "Terminal Done";
			case BLOCK_WATCH -> "Block Watch";
		};
		int typeW = this.font.width(typeLabel) + 10;
		cursorX -= typeW;
		fillRounded(graphics, cursorX, y, cursorX + typeW, y + btnSize, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, typeLabel, cursorX + 5, y + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(cursorX, y, typeW, btnSize, () -> dcf.cycleType(item)));
		cursorX -= 4;

		int stepBoxW = 34;
		cursorX -= stepBoxW;
		boolean stepBeingDragged = stepDragItem == item && stepDragCommitted;
		fillRounded(graphics, cursorX, y, cursorX + stepBoxW, y + btnSize, SMALL_RADIUS, stepBeingDragged ? accent : Theme.chrome(0xFF262626));
		String stepLabel = "#" + item.stepNumber;
		SmoothTextRenderer.draw(graphics, this.font, stepLabel, cursorX + (stepBoxW - this.font.width(stepLabel)) / 2, y + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		int stepBoxX = cursorX;
		// Real bug found (per user report): this box used to split into a left-half-decrements/right-half-
		// increments stepper — a 17px-wide click target either half, easy to miss and land on the WRONG half,
		// reading as "clicking the step number randomly sends it backward." Redesigned around
		// mouseClicked/mouseDragged/mouseReleased's own shared step-drag state machine instead: a plain click
		// (no meaningful drag) now always just advances by one, a right-click still decrements (unchanged),
		// and holding + dragging the box up/down over another step's row reorders it there instead — the real
		// "drag reorder handle" this box doubles as now, without needing a separate dedicated grip widget.
		copilotStepBoxHits.add(new CopilotStepBoxHit(stepBoxX, y, stepBoxW, btnSize, item));
		cursorX -= 4;

		// Per user request ("the advance step button can be turned into an A like dungeon routes, with a
		// hoverable tooltip explaining what it does"): reverted from the wide labeled button back to a
		// compact icon-sized "A" square at the far left, matching Dungeon Routes' own drawRouteItemBlock —
		// see that method's own comment for why THAT one shows it unconditionally; here it still only shows
		// for Breakable Blocks (every other Boss Guide step type always auto-advances on its own, so the
		// toggle has nothing to control for them — see this block's own history for the waypoint-always-
		// advances reasoning).
		if (item.type == com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.StepType.BREAKABLE_BLOCKS) {
			int advW = btnSize;
			fillRounded(graphics, x, y, x + advW, y + btnSize, SMALL_RADIUS, item.advancesStep ? accent : Theme.chrome(0xFF2A2A2A));
			SmoothTextRenderer.draw(graphics, this.font, "A", x + (advW - this.font.width("A")) / 2, y + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
			clickHits.add(new ClickHit(x, y, advW, btnSize, () -> { item.advancesStep = !item.advancesStep; ConfigManager.save(); }));
			checkHoverTooltip(mouseX, mouseY, x, y, advW, btnSize, "copilot_advances_step_" + System.identityHashCode(item),
				"Advances Step: whether all the tracked blocks being broken moves the guide to the next step. "
				+ "Turned on by default for Blocks steps.");
		}

		int rowH = 16;
		int curY = y + btnSize + 3;
		curY = drawCopilotTextField(graphics, x, curY, width, rowH, item.chatTrigger, "Chat message trigger (optional)", item, "chat") + 3;

		int splitW = Math.round(width * 0.6f);
		drawCopilotTextField(graphics, x, curY, splitW, rowH, item.splitTrigger, "Split name (optional)", item, "split");
		drawCopilotTextField(graphics, x + splitW + 4, curY, width - splitW - 4, rowH, item.splitOffsetInput, "Offset s", item, "offset");
		curY += rowH + 3;

		switch (item.type) {
			case TITLE -> curY = drawCopilotTextField(graphics, x, curY, width, rowH, item.titleText, "Title text", item, "title") + 3;
			// Per user request ("delete stuff like the block adder in the blocks step type, i think everyone
			// should use currently looking at button instead"): the manual X/Y/Z + "Add Block" row (an
			// alternative way to add a block by typed coordinates) is gone — "Use Looked-At Block" is the
			// only way to add a block to this list now, shortening this step type down to one row.
			case BREAKABLE_BLOCKS -> {
				int useW = (width - 8) / 2;
				fillRounded(graphics, x, curY, x + useW, curY + rowH, SMALL_RADIUS, accent);
				String useLabel = "Use Looked-At Block";
				SmoothTextRenderer.draw(graphics, this.font, useLabel, x + (useW - this.font.width(useLabel)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				clickHits.add(new ClickHit(x, curY, useW, rowH, () -> dcf.useLookedAtBlock(item)));
				int clearX = x + useW + 8;
				int clearW = width - useW - 8;
				fillRounded(graphics, clearX, curY, clearX + clearW, curY + rowH, SMALL_RADIUS, 0xFF3A1E1E);
				String clearLabel = "Clear (" + item.blocks.size() + ")";
				SmoothTextRenderer.draw(graphics, this.font, clearLabel, clearX + (clearW - this.font.width(clearLabel)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				clickHits.add(new ClickHit(clearX, curY, clearW, rowH, () -> dcf.clearBlocks(item)));
				curY += rowH + 3;
			}
			// Per user request ("add a block highlighting, it should just highlight blocks and also actually
			// allow the boss guide to go to next step when the highlighted blocks turn into a block selected
			// by the user... allow users to type in a block name with spaces like 'grass block'... not case
			// sensitive"): same "Use Looked-At Block"/"Clear" row BREAKABLE_BLOCKS uses (shares the same
			// `blocks` list), plus a text field for the target block's real display name — matching is done
			// case-insensitively in DungeonsCopilotFeature#isBlockWatchSatisfied.
			case BLOCK_WATCH -> {
				int useW = (width - 8) / 2;
				fillRounded(graphics, x, curY, x + useW, curY + rowH, SMALL_RADIUS, accent);
				String useLabel = "Use Looked-At Block";
				SmoothTextRenderer.draw(graphics, this.font, useLabel, x + (useW - this.font.width(useLabel)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				clickHits.add(new ClickHit(x, curY, useW, rowH, () -> dcf.useLookedAtBlock(item)));
				int clearX = x + useW + 8;
				int clearW = width - useW - 8;
				fillRounded(graphics, clearX, curY, clearX + clearW, curY + rowH, SMALL_RADIUS, 0xFF3A1E1E);
				String clearLabel = "Clear (" + item.blocks.size() + ")";
				SmoothTextRenderer.draw(graphics, this.font, clearLabel, clearX + (clearW - this.font.width(clearLabel)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				clickHits.add(new ClickHit(clearX, curY, clearW, rowH, () -> dcf.clearBlocks(item)));
				curY += rowH + 3;

				curY = drawCopilotTextField(graphics, x, curY, width, rowH, item.targetBlockInput,
					"Target block name (e.g. Polished Granite)", item, "targetBlock") + 3;
			}
			case WAYPOINT -> {
				int fieldW = (width - 8) / 4;
				int fx = x;
				drawCopilotTextField(graphics, fx, curY, fieldW, rowH, item.xInput, "X", item, "x"); fx += fieldW + 2;
				drawCopilotTextField(graphics, fx, curY, fieldW, rowH, item.yInput, "Y", item, "y"); fx += fieldW + 2;
				drawCopilotTextField(graphics, fx, curY, fieldW, rowH, item.zInput, "Z", item, "z"); fx += fieldW + 2;
				int useW = width - (fx - x);
				fillRounded(graphics, fx, curY, fx + useW, curY + rowH, SMALL_RADIUS, accent);
				String label = "Use Pos";
				SmoothTextRenderer.draw(graphics, this.font, label, fx + (useW - this.font.width(label)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				int useX = fx;
				clickHits.add(new ClickHit(useX, curY, useW, rowH, () -> dcf.useCurrentPosition(item)));
				curY += rowH + 3;
			}
			case DEVICE_FINISHED -> {
				int kindW = width;
				fillRounded(graphics, x, curY, x + kindW, curY + rowH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
				String kindLabel = "Device: " + item.deviceKind.name();
				SmoothTextRenderer.draw(graphics, this.font, kindLabel, x + (kindW - this.font.width(kindLabel)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				clickHits.add(new ClickHit(x, curY, kindW, rowH, () -> dcf.cycleDeviceKind(item)));
				curY += rowH + 3;

				if (item.deviceKind == com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.DeviceKind.ALIGN
					|| item.deviceKind == com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.DeviceKind.LEVERS) {
					String pdLabel = "PD Mode";
					int pdW = this.font.width(pdLabel) + 10;
					fillRounded(graphics, x, curY, x + pdW, curY + rowH, SMALL_RADIUS, item.pdMode ? accent : Theme.chrome(0xFF2A2A2A));
					SmoothTextRenderer.draw(graphics, this.font, pdLabel, x + (pdW - this.font.width(pdLabel)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
					clickHits.add(new ClickHit(x, curY, pdW, rowH, () -> { item.pdMode = !item.pdMode; ConfigManager.save(); }));
					curY += rowH + 3;
				}
			}
			// Per user request ("Add a leap detector to the boss guide... coordinate with the keybinds/leap
			// menu"): no per-item config needed at all — same one-shot, chat-confirmed shape as DEVICE_FINISHED
			// but with no "kind" to pick and no PD Mode equivalent (see StepType.LEAP_USED's own doc comment),
			// so this is purely an informational row telling the editor what already makes the step advance.
			case LEAP_USED -> {
				String leapLabel = "Advances automatically on a real Spirit Leap";
				fillRounded(graphics, x, curY, x + width, curY + rowH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
				SmoothTextRenderer.draw(graphics, this.font, leapLabel, x + (width - this.font.width(leapLabel)) / 2, curY + 4, Theme.text(0xFFAAAAAA));
				curY += rowH + 3;
			}
			// Per user request ("also create a Terminal done which detects when the terminal is finished
			// through chat or through the solver having no more steps"): same no-per-item-config informational
			// row shape as LEAP_USED — both real detection signals (chat line + solver state) run automatically.
			case TERMINAL_DONE -> {
				String termLabel = "Advances automatically when the terminal phase ends";
				fillRounded(graphics, x, curY, x + width, curY + rowH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
				SmoothTextRenderer.draw(graphics, this.font, termLabel, x + (width - this.font.width(termLabel)) / 2, curY + 4, Theme.text(0xFFAAAAAA));
				curY += rowH + 3;
			}
		}
		return curY + 4;
	}

	private int drawCopilotTextField(GuiGraphicsExtractor graphics, int boxX, int boxY, int boxWidth, int boxHeight,
									  String value, String placeholder,
									  com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item, String field) {
		boolean editing = textFocus == TextFocus.COPILOT_FIELD && editingCopilotItem == item && field.equals(editingCopilotField);
		fillRounded(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		boolean empty = value.isEmpty();
		String display = !editing && empty ? placeholder : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, boxY + 2, boxHeight - 4);
		graphics.text(this.font, display, boxX + 4, boxY + 4, !editing && empty ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, boxY + 3, caretX + 1, boxY + boxHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		copilotFieldBoxes.add(new CopilotBoxHit(boxX, boxY, boxWidth, boxHeight, item, field));
		return boxY + boxHeight;
	}

	/** Dungeon Routes' settings panel: {@link #selectedRouteRoomName} null shows every known room
	 *  ({@link com.cokelord.skyblocksimplified.dungeon.map.tile.RoomData#getAllRoomNames()}) as a small
	 *  button grid (per user request — "a list of every single room as smaller buttons (like the old
	 *  subcategory size) and show the name of the room"); picking one switches to that room's own step
	 *  editor, the same shape as Boss Guide's (drawRouteItemBlock below), minus the chat/split trigger
	 *  fields (a route step is scoped by physically standing in its room, not a chat/split signal). */
	private void drawDungeonRoutesContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf,
										   int x, int y, int width, int height, int accent, int mouseX, int mouseY) {
		int rowX = x + 10;
		int rowWidth = width - 20;
		int rowY = y + 4;

		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Waypoint Tracer",
			drf.isWaypointTracer(), () -> { drf.setWaypointTracer(!drf.isWaypointTracer()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Waypoint Path Line",
			drf.isWaypointBeaconLine(), () -> { drf.setWaypointBeaconLine(!drf.isWaypointBeaconLine()); ConfigManager.save(); });
		rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Highlight Opacity", drf.getHighlightOpacityPercent() + "%",
			drf.getHighlightOpacityPercent() / 100f, v -> { drf.setHighlightOpacityPercent(Math.round(v * 100f)); ConfigManager.save(); }, accent);
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Edit Mode (restart on finish)",
			drf.isEditMode(), () -> { drf.setEditMode(!drf.isEditMode()); ConfigManager.save(); });
		rowY += 4;
		// Real gap found (per user request — "Add Dungeon Routes 'next step' keybind to mod menu settings"):
		// nextStepKey was already fully wired up on the feature side (getKeybinds(), tickKeybinds()) but this
		// settings branch never called drawKeybindRows at all, so the only place to bind it was the vanilla
		// Controls screen — same real gap Leap Menu's own keybinds had before that was fixed.
		rowY = drawKeybindRows(graphics, x, y, width, height, rowY, drf.getKeybinds());
		rowY += 4;

		if (selectedRouteRoomName == null) {
			rowY = drawRouteAllConfigRow(graphics, drf, rowX, rowY, rowWidth, accent);
			rowY += 4;
			// Per user request ("Allow users to search for a room") — filters the tile grid below by
			// substring match, case-insensitive.
			routeRoomSearchBoxX = rowX;
			routeRoomSearchBoxY = rowY;
			routeRoomSearchBoxWidth = rowWidth;
			routeRoomSearchBoxHeight = 16;
			drawTextField(graphics, routeRoomSearchBoxX, routeRoomSearchBoxY, routeRoomSearchBoxWidth, routeRoomSearchBoxHeight,
				routeRoomSearchQuery.isEmpty() && textFocus != TextFocus.ROUTE_ROOM_SEARCH ? "§7Search rooms..." : routeRoomSearchQuery,
				textFocus == TextFocus.ROUTE_ROOM_SEARCH);
			rowY += routeRoomSearchBoxHeight + 4;

			// Per user request ("Add an 'Open current room' button in the list of rooms so the user can open
			// the room they are currently in"). getActiveRoomName() already mirrors this feature's own
			// resolvedActiveRoom() gating (in a dungeon, geometry-resolved, not the boss room), so it's null
			// — and this button a no-op — exactly when there's no real "current room" to jump to.
			String currentRoom = drf.getActiveRoomName();
			int openCurrentH = 16;
			boolean hasCurrentRoom = currentRoom != null;
			fillRounded(graphics, rowX, rowY, rowX + rowWidth, rowY + openCurrentH, SMALL_RADIUS, hasCurrentRoom ? accent : Theme.chrome(0xFF2A2A2A));
			String openCurrentLabel = hasCurrentRoom ? "Open current room: " + currentRoom : "Open current room (not in a room)";
			graphics.enableScissor(rowX, rowY, rowX + rowWidth, rowY + openCurrentH);
			SmoothTextRenderer.draw(graphics, this.font, openCurrentLabel, rowX + 5, rowY + (openCurrentH - 8) / 2, hasCurrentRoom ? 0xFFFFFFFF : 0xFF888888);
			graphics.disableScissor();
			if (hasCurrentRoom) {
				clickHits.add(new ClickHit(rowX, rowY, rowWidth, openCurrentH, () -> selectedRouteRoomName = currentRoom));
			}
			rowY += openCurrentH + 4;

			int tileW = ROUTE_ROOM_TILE_WIDTH;
			int tileH = ROUTE_ROOM_TILE_HEIGHT;
			int gap = ROUTE_ROOM_TILE_GAP;
			int perRow = Math.max(1, (rowWidth + gap) / (tileW + gap));
			int col = 0;
			int tileX = rowX;
			String query = routeRoomSearchQuery.toLowerCase(Locale.ROOT);
			List<String> matchingRooms = drf.getAllRoomNames().stream()
				.filter(name -> query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query))
				.toList();
			for (String roomName : matchingRooms) {
				int stepCount = drf.getStepCountForRoom(roomName);
				fillRounded(graphics, tileX, rowY, tileX + tileW, rowY + tileH, SMALL_RADIUS, stepCount > 0 ? accent : Theme.chrome(0xFF2A2A2A));
				graphics.enableScissor(tileX, rowY, tileX + tileW, rowY + tileH);
				String label = stepCount > 0 ? roomName + " (" + stepCount + ")" : roomName;
				SmoothTextRenderer.draw(graphics, this.font, label, tileX + 4, rowY + (tileH - 8) / 2, Theme.text(0xFFFFFFFF));
				graphics.disableScissor();
				clickHits.add(new ClickHit(tileX, rowY, tileW, tileH, () -> selectedRouteRoomName = roomName));
				col++;
				if (col >= perRow) { col = 0; tileX = rowX; rowY += tileH + gap; } else { tileX += tileW + gap; }
			}
			if (col != 0) rowY += tileH + gap;
		} else {
			String roomName = selectedRouteRoomName;
			int backH = 16;
			String backLabel = "< " + roomName;
			fillRounded(graphics, rowX, rowY, rowX + rowWidth, rowY + backH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
			graphics.enableScissor(rowX, rowY, rowX + rowWidth, rowY + backH);
			SmoothTextRenderer.draw(graphics, this.font, backLabel, rowX + 5, rowY + 4, Theme.text(0xFFFFFFFF));
			graphics.disableScissor();
			clickHits.add(new ClickHit(rowX, rowY, rowWidth, backH, () -> selectedRouteRoomName = null));
			rowY += backH + 4;

			rowY = drawRouteRoomConfigRow(graphics, drf, roomName, rowX, rowY, rowWidth, accent);
			rowY += 4;

			int addH = 16;
			fillRounded(graphics, rowX, rowY, rowX + rowWidth, rowY + addH, BOX_RADIUS, accent);
			String addLabel = "+ Add Step";
			SmoothTextRenderer.draw(graphics, this.font, addLabel, rowX + (rowWidth - this.font.width(addLabel)) / 2, rowY + 4, Theme.text(0xFFFFFFFF));
			clickHits.add(new ClickHit(rowX, rowY, rowWidth, addH, () -> drf.addItem(roomName)));
			// Per user request ("Its super annoying scrolling up and down to add a step"): whenever the real
			// button drawn just above is scrolled outside the panel's own visible viewport, the floating
			// bottom-left "+" (drawn once per frame after this whole list finishes, see layoutAndDraw) takes
			// over as the way to reach it without scrolling.
			if (rowY + addH < listViewportY0 || rowY > listViewportY1) {
				routeAddStepAction = () -> drf.addItem(roomName);
			}
			rowY += addH + 6;

			var roomItems = drf.getItemsForRoom(roomName);
			roomItems.sort((a, b) -> a.stepNumber - b.stepNumber);
			for (var item : roomItems) {
				rowY = drawRouteItemBlock(graphics, rowX, rowY, rowWidth, drf, item, accent, mouseX, mouseY);
			}
		}

		if (isStylePopupOpen()) {
			popupHitsStartClick = clickHits.size();
			popupHitsStartColorSquare = colorSquareHits.size();
			popupHitsStartSlider = sliderHits.size();
			popupHitsStartHex = hexFieldBounds.size();
			graphics.disableScissor();
			graphics.disableScissor();
			drawEnchantStylePopups(graphics, accent);
			graphics.enableScissor(listViewportX0, listViewportY0, listViewportX1, listViewportY1);
			graphics.enableScissor(x, y, x + width, y + Math.round(expandedContentHeight(drf)));
		}
	}

	/** One route step's editable block — same shape as {@link #drawCopilotItemBlock}, minus the chat/split
	 *  trigger row (a route step is scoped by physically being in its room, not a chat/split signal). Its
	 *  own "Advances Step" toggle (see below) is shown for every type, unlike Boss Guide's version of the
	 *  same control. */
	private int drawRouteItemBlock(GuiGraphicsExtractor graphics, int x, int y, int width,
									com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf,
									com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item, int accent,
									int mouseX, int mouseY) {
		int btnSize = 14;
		int cursorX = x + width - btnSize;

		fillCircle(graphics, cursorX + btnSize / 2, y + btnSize / 2, btnSize / 2, COLOR_CLOSE_BUTTON);
		SmoothTextRenderer.draw(graphics, this.font, "x", cursorX + (btnSize - this.font.width("x")) / 2, y + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(cursorX, y, btnSize, btnSize, () -> drf.removeItem(item)));
		cursorX -= btnSize + 4;

		int swatchColor = com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.routeItemColor(item);
		fillRounded(graphics, cursorX, y, cursorX + btnSize, y + btnSize, SMALL_RADIUS, swatchColor | 0xFF000000);
		int swX = cursorX, swY = y;
		clickHits.add(new ClickHit(cursorX, y, btnSize, btnSize, () -> {
			routeColorPopupTarget = item;
			popupAnchorX = swX;
			popupAnchorY = swY + btnSize + 2;
		}));
		cursorX -= btnSize + 4;

		String typeLabel = com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.typeLabel(item.type);
		int typeW = this.font.width(typeLabel) + 10;
		cursorX -= typeW;
		fillRounded(graphics, cursorX, y, cursorX + typeW, y + btnSize, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, typeLabel, cursorX + 5, y + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(cursorX, y, typeW, btnSize, () -> drf.cycleType(item)));
		// Per user report ("Theres a new 'Interact' trigger which i have no idea what it does") — every type
		// badge now explains itself on hover (same 2-second-delay tooltip mechanism as "Advance Step" above),
		// not just the newly-added Interact type, since none of these were ever explained inline before.
		checkHoverTooltip(mouseX, mouseY, cursorX, y, typeW, btnSize, "route_type_" + System.identityHashCode(item),
			com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.typeDescription(item.type) + " Click to cycle to the next type.");
		cursorX -= 4;

		int stepBoxW = 34;
		cursorX -= stepBoxW;
		boolean stepBeingDragged = stepDragItem == item && stepDragCommitted;
		fillRounded(graphics, cursorX, y, cursorX + stepBoxW, y + btnSize, SMALL_RADIUS, stepBeingDragged ? accent : Theme.chrome(0xFF262626));
		String stepLabel = "#" + item.stepNumber;
		SmoothTextRenderer.draw(graphics, this.font, stepLabel, cursorX + (stepBoxW - this.font.width(stepLabel)) / 2, y + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		int stepBoxX = cursorX;
		// Same redesign as drawCopilotItemBlock's own step box — see its comment for the "left-click sends it
		// backward" bug this replaces and the drag-to-reorder behavior this box now doubles as.
		routeStepBoxHits.add(new RouteStepBoxHit(stepBoxX, y, stepBoxW, btnSize, item));

		// Per user request ("Allow users to also select which of the items on the same step should advance
		// to next step, since etherwarping advances to next step even if the user hasn't opened the
		// chest/took the secret") — shown for every type here (unlike Boss Guide's own identical toggle,
		// which only needs it for Breakable Blocks since every other Boss Guide type there always
		// auto-advances unconditionally by design): a Dungeon Routes step can mix ANY of its types together
		// (an Etherwarp Waypoint next to a Secret Chest is exactly the reported case), so any of them might
		// need to be the one that doesn't drive the step forward.
		//
		// Per user request ("Rename/restyle Advance Step back to the old 'A' toggle with a 2s-hover tooltip
		// explaining it"): shrunk from a wide labeled button back down to a small square "A" icon, matching
		// every other icon-sized button in this row (close/swatch/type) — freeing up the row for the wider
		// "Stand in this room to set positions" hint and, more importantly, the coordinate row below to fit
		// on one line instead of two. The label alone no longer explains what it does at this size, so a
		// hover tooltip (same 2-second-delay mechanism as every other tooltip on this screen) replaces it.
		int advW = btnSize;
		fillRounded(graphics, x, y, x + advW, y + btnSize, SMALL_RADIUS, item.advancesStep ? accent : Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, "A", x + (advW - this.font.width("A")) / 2, y + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(x, y, advW, btnSize, () -> { item.advancesStep = !item.advancesStep; ConfigManager.save(); }));
		checkHoverTooltip(mouseX, mouseY, x, y, advW, btnSize, "route_advances_step_" + System.identityHashCode(item),
			"Advances Step: whether reaching or completing this item moves the route to the next step. Turn "
			+ "off for an item sharing a step with something else (e.g. an Etherwarp point next to a Secret "
			+ "Chest) so it doesn't skip past the other one.");

		if (!drf.canCapturePosition(item)) {
			String hint = "Stand in this room to set positions";
			int hintX = x + advW + 4;
			graphics.enableScissor(hintX, y, cursorX - 4, y + btnSize);
			SmoothTextRenderer.draw(graphics, this.font, hint, hintX, y + (btnSize - 8) / 2, Theme.text(0xFF888888));
			graphics.disableScissor();
		}

		int rowH = 16;
		int curY = y + btnSize + 3;

		switch (item.type) {
			// Per user request ("Allow multiple etherwarp points per single Etherwarp step, list-based, like
			// Blocks already works") — Etherwarp moved onto the same RouteItem#blocks list Breakable Blocks
			// already uses (see DungeonRoutesFeature's own doc comments on isPositional/renderWorldMarkers/
			// onTick), so it now shares this exact editor row instead of the single-point one below.
			//
			// Per user request ("Replace the Blocks/Etherwarp 'Clear' button with a 'Selected' button that
			// opens a dropdown listing each selected block/etherwarp with a red X to remove individually" /
			// "Shrink 'use looked at block' + coordinate fields... so all buttons fit on one row instead of
			// two"): capture button, the new Selected(N) dropdown trigger, X/Y/Z fields, and Add all fit on
			// one row now (proportional widths so it degrades gracefully at any panel width — text scissor-
			// clips like every other narrow label on this screen already does if it's ever too tight).
			case BREAKABLE_BLOCKS, ENDERWARP_WAYPOINT, INTERACT_WAYPOINT -> {
				int gap = 3;
				int unit = (width - gap * 5) / 9;
				int useW = unit * 2;
				int fieldW = unit;
				int selW = unit * 2;
				int addW = width - gap * 5 - useW - fieldW * 3 - selW;

				int ux = x;
				fillRounded(graphics, ux, curY, ux + useW, curY + rowH, SMALL_RADIUS, accent);
				graphics.enableScissor(ux, curY, ux + useW, curY + rowH);
				SmoothTextRenderer.draw(graphics, this.font, "Use Looked-At Block", ux + 4, curY + 4, Theme.text(0xFFFFFFFF));
				graphics.disableScissor();
				clickHits.add(new ClickHit(ux, curY, useW, rowH, () -> drf.useLookedAtBlock(item)));

				int selX = ux + useW + gap;
				fillRounded(graphics, selX, curY, selX + selW, curY + rowH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
				graphics.enableScissor(selX, curY, selX + selW, curY + rowH);
				SmoothTextRenderer.draw(graphics, this.font, "Selected (" + item.blocks.size() + ")", selX + 4, curY + 4, Theme.text(0xFFFFFFFF));
				graphics.disableScissor();
				int selXFinal = selX, selYFinal = curY;
				clickHits.add(new ClickHit(selX, curY, selW, rowH, () -> {
					routeSelectedPopupTarget = item;
					popupAnchorX = selXFinal;
					popupAnchorY = selYFinal + rowH + 2;
				}));

				int bfx = selX + selW + gap;
				drawRouteTextField(graphics, bfx, curY, fieldW, rowH, item.xInput, "X", item, "x"); bfx += fieldW + gap;
				drawRouteTextField(graphics, bfx, curY, fieldW, rowH, item.yInput, "Y", item, "y"); bfx += fieldW + gap;
				drawRouteTextField(graphics, bfx, curY, fieldW, rowH, item.zInput, "Z", item, "z"); bfx += fieldW + gap;
				fillRounded(graphics, bfx, curY, bfx + addW, curY + rowH, SMALL_RADIUS, accent);
				graphics.enableScissor(bfx, curY, bfx + addW, curY + rowH);
				SmoothTextRenderer.draw(graphics, this.font, "Add", bfx + (addW - this.font.width("Add")) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				graphics.disableScissor();
				clickHits.add(new ClickHit(bfx, curY, addW, rowH, () -> drf.addBlockFromInput(item)));
				curY += rowH + 3;
			}
			// ENDERPEARL_WAYPOINT and the three clickable secret types (lever, chest, essence) all get the
			// "Use Looked-At Block" capture button (per user request — "I should be able to select the block
			// im looking at like the break block highlights" / "Make secrets that are clickable (levers,
			// chests, essence) use looked at block instead") — these are all fixed things you aim at and
			// click, so capturing the block you're LOOKING at is the natural gesture, same as Breakable
			// Blocks/Etherwarp above (Etherwarp moved to that shared list-based row, not this one, per the
			// comment above). WAYPOINT and the two secret types with no fixed clickable target (a pickup can
			// spawn in a small area, a bat secret has no single block) stay on "Use Pos" (the player's own
			// position) below.
			// All share a second row: a real bug found here (per user request "the coordinates for it
			// should be able to have 1 decimal point" prompted a closer look) — the typed X/Y/Z fields above
			// had NO way to actually commit a manually-typed value at all; only the capture button ever
			// wrote to the item's real position, silently discarding anything typed. "Apply" fixes that.
			// Per user request ("this should have a currently-looking feature, instead of looking for
			// currently looked at block it should look at the yaw, pitch, and everything to make it just
			// like the angle the user is currently facing when the button is pressed") — Ender Pearl no
			// longer shares the "Use Looked-At Block" row with the other clickable-secret types below, since
			// a pearl throw has no block to aim AT — it's an angle you stand and face, not a target you look
			// at. "Use Current Facing" appends a new PearlShot (position + real facing) every press; "Clear
			// Pearls" drops all of them back to the legacy single-point behavior.
			case ENDERPEARL_WAYPOINT -> {
				int gap = 3;
				int useW = (width - gap) * 2 / 3;
				int clearW = width - gap - useW;
				fillRounded(graphics, x, curY, x + useW, curY + rowH, SMALL_RADIUS, accent);
				graphics.enableScissor(x, curY, x + useW, curY + rowH);
				SmoothTextRenderer.draw(graphics, this.font, "Use Current Facing", x + 4, curY + 4, Theme.text(0xFFFFFFFF));
				graphics.disableScissor();
				clickHits.add(new ClickHit(x, curY, useW, rowH, () -> drf.useCurrentFacingAsPearl(item)));

				int clx = x + useW + gap;
				fillRounded(graphics, clx, curY, clx + clearW, curY + rowH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
				String clearLabel = "Clear (" + item.pearlShots.size() + ")";
				graphics.enableScissor(clx, curY, clx + clearW, curY + rowH);
				SmoothTextRenderer.draw(graphics, this.font, clearLabel, clx + 4, curY + 4, Theme.text(0xFFFFFFFF));
				graphics.disableScissor();
				clickHits.add(new ClickHit(clx, curY, clearW, rowH, () -> drf.clearPearlShots(item)));
				curY += rowH + 3;

				int fieldW = (width - 8) / 4;
				int fx = x;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.xInput, "X", item, "x"); fx += fieldW + 2;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.yInput, "Y", item, "y"); fx += fieldW + 2;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.zInput, "Z", item, "z"); fx += fieldW + 2;
				int applyW = width - (fx - x);
				fillRounded(graphics, fx, curY, fx + applyW, curY + rowH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
				String applyLabel = "Apply";
				SmoothTextRenderer.draw(graphics, this.font, applyLabel, fx + (applyW - this.font.width(applyLabel)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				clickHits.add(new ClickHit(fx, curY, applyW, rowH, () -> drf.applyTypedPosition(item)));
				curY += rowH + 3;
			}
			case SECRET_LEVER, SECRET_CHEST, SECRET_ESSENCE -> {
				int fieldW = (width - 8) / 4;
				int fx = x;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.xInput, "X", item, "x"); fx += fieldW + 2;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.yInput, "Y", item, "y"); fx += fieldW + 2;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.zInput, "Z", item, "z"); fx += fieldW + 2;
				int useW = width - (fx - x);
				fillRounded(graphics, fx, curY, fx + useW, curY + rowH, SMALL_RADIUS, accent);
				String label = "Use Looked-At Block";
				graphics.enableScissor(fx, curY, fx + useW, curY + rowH);
				SmoothTextRenderer.draw(graphics, this.font, label, fx + 4, curY + 4, Theme.text(0xFFFFFFFF));
				graphics.disableScissor();
				clickHits.add(new ClickHit(fx, curY, useW, rowH, () -> drf.useLookedAtPosition(item)));
				curY += rowH + 3;
				curY = drawApplyPositionRow(graphics, x, curY, width, rowH, drf, item, accent);
			}
			default -> {
				int fieldW = (width - 8) / 4;
				int fx = x;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.xInput, "X", item, "x"); fx += fieldW + 2;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.yInput, "Y", item, "y"); fx += fieldW + 2;
				drawRouteTextField(graphics, fx, curY, fieldW, rowH, item.zInput, "Z", item, "z"); fx += fieldW + 2;
				int useW = width - (fx - x);
				fillRounded(graphics, fx, curY, fx + useW, curY + rowH, SMALL_RADIUS, accent);
				String label = "Use Pos";
				SmoothTextRenderer.draw(graphics, this.font, label, fx + (useW - this.font.width(label)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
				clickHits.add(new ClickHit(fx, curY, useW, rowH, () -> drf.useCurrentPosition(item)));
				curY += rowH + 3;
				curY = drawApplyPositionRow(graphics, x, curY, width, rowH, drf, item, accent);
			}
		}
		return curY + 4;
	}

	/** "Apply" — commits whatever's currently typed in the X/Y/Z fields above to the item's real stored
	 *  position (see {@link com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature#applyTypedPosition}).
	 *  Full-width, its own row, since it's meaningfully different from the capture button above it (that one
	 *  reads live game state; this one reads whatever the user just typed). */
	private int drawApplyPositionRow(GuiGraphicsExtractor graphics, int x, int curY, int width, int rowH,
									  com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf,
									  com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item, int accent) {
		fillRounded(graphics, x, curY, x + width, curY + rowH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
		String label = "Apply Typed Coordinates";
		SmoothTextRenderer.draw(graphics, this.font, label, x + (width - this.font.width(label)) / 2, curY + 4, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(x, curY, width, rowH, () -> drf.applyTypedPosition(item)));
		return curY + rowH + 3;
	}

	private int drawRouteTextField(GuiGraphicsExtractor graphics, int boxX, int boxY, int boxWidth, int boxHeight,
									String value, String placeholder,
									com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item, String field) {
		boolean editing = textFocus == TextFocus.ROUTE_FIELD && editingRouteItem == item && field.equals(editingRouteField);
		fillRounded(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		boolean empty = value.isEmpty();
		String display = !editing && empty ? placeholder : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, boxY + 2, boxHeight - 4);
		graphics.text(this.font, display, boxX + 4, boxY + 4, !editing && empty ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, boxY + 3, caretX + 1, boxY + boxHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		routeFieldBoxes.add(new RouteBoxHit(boxX, boxY, boxWidth, boxHeight, item, field));
		return boxY + boxHeight;
	}

	private static final int ALIAS_HEADER_HEIGHT = 4 + 16 + 6;
	private static final int ALIAS_ROW_HEIGHT = 16 + 3 + 16 + 3 + 4;

	/** Per user request: an add/remove row list like Positional Messages, each row two text fields —
	 *  "Command you want to replace" (the trigger) above "Command that will replace" (what actually gets
	 *  sent instead). Row math here must stay in sync with ALIAS_HEADER_HEIGHT/ALIAS_ROW_HEIGHT. */
	private void drawCommandAliasesContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature caf,
											int x, int y, int width, int accent) {
		int rowX = x + 10;
		int rowWidth = width - 20;
		int rowY = y + 4;

		int addH = 16;
		fillRounded(graphics, rowX, rowY, rowX + rowWidth, rowY + addH, BOX_RADIUS, accent);
		String addLabel = "+ Add Alias";
		SmoothTextRenderer.draw(graphics, this.font, addLabel, rowX + (rowWidth - this.font.width(addLabel)) / 2, rowY + 4, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(rowX, rowY, rowWidth, addH, caf::addAlias));
		rowY += addH + 6;

		for (var alias : caf.getAliases()) {
			rowY = drawAliasRow(graphics, rowX, rowY, rowWidth, caf, alias);
		}
	}

	private int drawAliasRow(GuiGraphicsExtractor graphics, int x, int y, int width,
							  com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature caf,
							  com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature.Alias alias) {
		int btnSize = 14;
		int removeX = x + width - btnSize;
		int removeY = y + 1;
		fillCircle(graphics, removeX + btnSize / 2, removeY + btnSize / 2, btnSize / 2, COLOR_CLOSE_BUTTON);
		SmoothTextRenderer.draw(graphics, this.font, "x", removeX + (btnSize - this.font.width("x")) / 2, removeY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(removeX, removeY, btnSize, btnSize, () -> caf.removeAlias(alias)));

		int fieldWidth = width - btnSize - 6;
		int rowH = 16;
		int curY = y;
		curY = drawAliasTextField(graphics, x, curY, fieldWidth, rowH, alias.triggerInput, "Command you want to replace", alias, "trigger") + 3;
		curY = drawAliasTextField(graphics, x, curY, width, rowH, alias.replacementInput, "Command that will replace", alias, "replacement") + 3;
		return curY + 4;
	}

	private int drawAliasTextField(GuiGraphicsExtractor graphics, int boxX, int boxY, int boxWidth, int boxHeight,
									String value, String placeholder,
									com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature.Alias alias, String field) {
		boolean editing = textFocus == TextFocus.ALIAS_FIELD && editingAlias == alias && field.equals(editingAliasField);
		fillRounded(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		boolean empty = value.isEmpty() || value.equals("/");
		String display = !editing && empty ? placeholder : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, boxY + 2, boxHeight - 4);
		graphics.text(this.font, display, boxX + 4, boxY + 4, !editing && empty ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, boxY + 3, caretX + 1, boxY + boxHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		aliasFieldBoxes.add(new AliasBoxHit(boxX, boxY, boxWidth, boxHeight, alias, field));
		return boxY + boxHeight;
	}

	private static final int SHORTCUT_HEADER_HEIGHT = 4 + 16 + 6;
	private static final int SHORTCUT_ROW_HEIGHT = 16 + 3 + 18 + 4;

	/** Per user request: same add/remove row shape as Command Aliases, but each row is one command field
	 *  plus a keybind selector (reusing drawComboCaptureRow's already-existing capture UI verbatim) instead
	 *  of a second text field — pressing that combo sends the row's command once. */
	private void drawCommandShortcutsContent(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature csf,
											  int x, int y, int width, int accent) {
		int rowX = x + 10;
		int rowWidth = width - 20;
		int rowY = y + 4;

		int addH = 16;
		fillRounded(graphics, rowX, rowY, rowX + rowWidth, rowY + addH, BOX_RADIUS, accent);
		String addLabel = "+ Add Shortcut";
		SmoothTextRenderer.draw(graphics, this.font, addLabel, rowX + (rowWidth - this.font.width(addLabel)) / 2, rowY + 4, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(rowX, rowY, rowWidth, addH, csf::addShortcut));
		rowY += addH + 6;

		for (var shortcut : csf.getShortcuts()) {
			rowY = drawShortcutRow(graphics, rowX, rowY, rowWidth, csf, shortcut);
		}
	}

	private int drawShortcutRow(GuiGraphicsExtractor graphics, int x, int y, int width,
								 com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature csf,
								 com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature.Shortcut shortcut) {
		int btnSize = 14;
		int removeX = x + width - btnSize;
		int removeY = y + 1;
		fillCircle(graphics, removeX + btnSize / 2, removeY + btnSize / 2, btnSize / 2, COLOR_CLOSE_BUTTON);
		SmoothTextRenderer.draw(graphics, this.font, "x", removeX + (btnSize - this.font.width("x")) / 2, removeY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(removeX, removeY, btnSize, btnSize, () -> csf.removeShortcut(shortcut)));

		int fieldWidth = width - btnSize - 6;
		int rowH = 16;
		int curY = y;
		curY = drawShortcutCommandField(graphics, x, curY, fieldWidth, rowH, shortcut) + 3;
		curY = drawComboCaptureRow(graphics, x, curY, width, "Keybind", shortcut.combo);
		return curY + 4;
	}

	private int drawShortcutCommandField(GuiGraphicsExtractor graphics, int boxX, int boxY, int boxWidth, int boxHeight,
										  com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature.Shortcut shortcut) {
		String value = shortcut.commandInput;
		boolean editing = textFocus == TextFocus.SHORTCUT_COMMAND && editingShortcut == shortcut;
		fillRounded(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		boolean empty = value.isEmpty() || value.equals("/");
		String display = !editing && empty ? "Command to run" : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, boxY + 2, boxHeight - 4);
		graphics.text(this.font, display, boxX + 4, boxY + 4, !editing && empty ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, boxY + 3, caretX + 1, boxY + boxHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		shortcutCommandBoxes.add(new ShortcutBoxHit(boxX, boxY, boxWidth, boxHeight, shortcut));
		return boxY + boxHeight;
	}

	private int drawKismetThresholdField(GuiGraphicsExtractor graphics, int boxX, int boxY, int boxWidth, int boxHeight,
										  com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature kfb) {
		String value = kfb.getThresholdInput();
		boolean editing = textFocus == TextFocus.KISMET_THRESHOLD;
		fillRounded(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		boolean empty = value.isEmpty();
		String display = !editing && empty ? "e.g. 100K, 100,000" : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, boxY + 2, boxHeight - 4);
		graphics.text(this.font, display, boxX + 4, boxY + 4, !editing && empty ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, boxY + 3, caretX + 1, boxY + boxHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		kismetThresholdBox = new KismetThresholdBoxHit(boxX, boxY, boxWidth, boxHeight);
		return boxY + boxHeight;
	}

	/** General-purpose single-line text field bound to an arbitrary String getter/setter, keyed by `id` —
	 *  see the {@code genericFieldBounds} field's own doc comment for why this exists instead of a new
	 *  TextFocus constant per setting. Visually identical to {@link #drawKismetThresholdField}. `id` must be
	 *  unique among every generic field drawn this same frame (per-item fields should fold an item index/uuid
	 *  into it). */
	private int drawGenericTextField(GuiGraphicsExtractor graphics, String id, int boxX, int boxY, int boxWidth, int boxHeight,
									  String placeholder, java.util.function.Supplier<String> getter, java.util.function.Consumer<String> setter) {
		genericFieldGetters.put(id, getter);
		genericFieldSetters.put(id, setter);
		genericFieldBounds.put(id, new int[]{boxX, boxY, boxX + boxWidth, boxY + boxHeight});
		String value = getter.get();
		boolean editing = textFocus == TextFocus.GENERIC_FIELD && id.equals(genericFocusedFieldId);
		fillRounded(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		boolean empty = value.isEmpty();
		String display = !editing && empty && placeholder != null ? placeholder : value;
		if (editing) drawSelectionHighlight(graphics, value, boxX + 4, boxY + 2, boxHeight - 4);
		graphics.text(this.font, display, boxX + 4, boxY + 4, !editing && empty ? COLOR_PLACEHOLDER_TEXT : 0xFFCCCCCC);
		if (editing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, value.length()));
			int caretX = boxX + 4 + this.font.width(value.substring(0, idx)) + 1;
			graphics.fill(caretX, boxY + 3, caretX + 1, boxY + boxHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		return boxY + boxHeight;
	}

	/** One message's editable block, per the user's mockup + follow-up clarifications:
	 *  row 1 is the chat-text field, a Preset dropdown button, and a remove button;
	 *  row 2 is the Classes dropdown, Section dropdown, and Boss Part cycle button (all restriction
	 *  controls together);
	 *  row 3 is the smaller 1-decimal X/Y/Z/Range fields (PosMessage.xInput etc. are already formatted to
	 *  1 decimal by PositionalMessagesFeature.fmt);
	 *  row 4 is the "use current position" button.
	 *  The Classes/Section/Preset dropdowns reuse the same popup machinery as the enchant Color/Chroma
	 *  popups (posMsgDropdownIndex/Kind + popupAnchorX/Y + popupX0..Y1 + popupHitsStart*, drawn by
	 *  drawPositionalMessageDropdownPopup from drawPositionalMessagesContent) rather than a parallel system. */
	private int drawPositionalMessageRow(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf,
										  com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.PosMessage m,
										  int index, int x, int y, int width) {
		int rowHeight = 16;
		int rowGap = 2;

		// Row 1: message text field, Preset dropdown button, remove button.
		int removeSize = 16;
		int removeX = x + width - removeSize;
		int presetGap = 4;
		int presetButtonWidth = this.font.width("Preset") + 14;
		int presetX = removeX - presetGap - presetButtonWidth;
		boolean textEditing = textFocus == TextFocus.POSMSG_TEXT && editingPosMsgIndex == index;
		int textBoxWidth = presetX - presetGap - x;
		fillRounded(graphics, x, y, x + textBoxWidth, y + rowHeight, SMALL_RADIUS, textEditing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(x, y, x + textBoxWidth, y + rowHeight);
		String textDisplay = !textEditing && m.message.isEmpty() ? "(message text)" : m.message;
		if (textEditing) drawSelectionHighlight(graphics, m.message, x + 4, y + 2, rowHeight - 4);
		SmoothTextRenderer.draw(graphics, this.font, textDisplay, x + 4, y + 4, Theme.text(0xFFCCCCCC));
		if (textEditing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, m.message.length()));
			int caretX = x + 4 + this.font.width(m.message.substring(0, idx)) + 1;
			graphics.fill(caretX, y + 3, caretX + 1, y + rowHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		posMsgFieldBoxes.add(new int[]{x, y, textBoxWidth, rowHeight, index, TextFocus.POSMSG_TEXT.ordinal()});

		fillRounded(graphics, presetX, y, presetX + presetButtonWidth, y + rowHeight, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, "Preset", presetX + (presetButtonWidth - this.font.width("Preset")) / 2, y + (rowHeight - 8) / 2, Theme.text(0xFFDDDDDD));
		int presetAnchorX = presetX, presetAnchorY = y;
		clickHits.add(new ClickHit(presetX, y, presetButtonWidth, rowHeight, () -> {
			posMsgDropdownIndex = index;
			posMsgDropdownKind = "preset";
			popupAnchorX = presetAnchorX;
			popupAnchorY = presetAnchorY + rowHeight + 2;
			posMsgDropdownScroll = 0f;
		}));

		fillCircle(graphics, removeX + removeSize / 2, y + removeSize / 2, removeSize / 2, COLOR_CLOSE_BUTTON);
		SmoothTextRenderer.draw(graphics, this.font, "x", removeX + (removeSize - this.font.width("x")) / 2, y + (removeSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(removeX, y, removeSize, removeSize, () -> pmf.removeMessage(index)));

		// Row 2: Classes / Section (both multi-select, checkmarked in their dropdowns) / Boss Part (cycle).
		int row2Y = y + rowHeight + rowGap;
		int row2Gap = 4;
		int row2FieldWidth = (width - row2Gap * 2) / 3;

		int classesX = x;
		String classesLabel = m.classes.isEmpty() ? "Classes: ALL" : "Classes (" + m.classes.size() + ")";
		fillRounded(graphics, classesX, row2Y, classesX + row2FieldWidth, row2Y + rowHeight, SMALL_RADIUS, m.classes.isEmpty() ? Theme.chrome(0xFF2A2A2A) : 0xFF3A5A3A);
		SmoothTextRenderer.draw(graphics, this.font, classesLabel, classesX + (row2FieldWidth - this.font.width(classesLabel)) / 2, row2Y + (rowHeight - 8) / 2, Theme.text(0xFFDDDDDD));
		int classesAnchorX = classesX, classesAnchorY = row2Y;
		clickHits.add(new ClickHit(classesX, row2Y, row2FieldWidth, rowHeight, () -> {
			posMsgDropdownIndex = index;
			posMsgDropdownKind = "classes";
			popupAnchorX = classesAnchorX;
			popupAnchorY = classesAnchorY + rowHeight + 2;
			posMsgDropdownScroll = 0f;
		}));

		int sectionX = classesX + row2FieldWidth + row2Gap;
		String sectionLabel = m.sections.isEmpty() ? "Section: ALL" : "Section (" + m.sections.size() + ")";
		fillRounded(graphics, sectionX, row2Y, sectionX + row2FieldWidth, row2Y + rowHeight, SMALL_RADIUS, m.sections.isEmpty() ? Theme.chrome(0xFF2A2A2A) : 0xFF3A5A3A);
		SmoothTextRenderer.draw(graphics, this.font, sectionLabel, sectionX + (row2FieldWidth - this.font.width(sectionLabel)) / 2, row2Y + (rowHeight - 8) / 2, Theme.text(0xFFDDDDDD));
		int sectionAnchorX = sectionX, sectionAnchorY = row2Y;
		clickHits.add(new ClickHit(sectionX, row2Y, row2FieldWidth, rowHeight, () -> {
			posMsgDropdownIndex = index;
			posMsgDropdownKind = "section";
			popupAnchorX = sectionAnchorX;
			popupAnchorY = sectionAnchorY + rowHeight + 2;
			posMsgDropdownScroll = 0f;
		}));

		int bossPartX = sectionX + row2FieldWidth + row2Gap;
		String bossPartLabel = m.bossPart.label;
		fillRounded(graphics, bossPartX, row2Y, bossPartX + row2FieldWidth, row2Y + rowHeight, SMALL_RADIUS,
			m.bossPart == com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.BossPart.ANY ? Theme.chrome(0xFF2A2A2A) : 0xFF3A5A3A);
		SmoothTextRenderer.draw(graphics, this.font, bossPartLabel, bossPartX + (row2FieldWidth - this.font.width(bossPartLabel)) / 2, row2Y + (rowHeight - 8) / 2, Theme.text(0xFFDDDDDD));
		clickHits.add(new ClickHit(bossPartX, row2Y, row2FieldWidth, rowHeight, () -> pmf.cycleBossPart(index)));

		// Row 3: X/Y/Z/Range fields — already 1-decimal formatted (PositionalMessagesFeature.fmt), so kept
		// narrower than the old full-precision layout per user request ("smaller coordinate boxes").
		int row3Y = row2Y + rowHeight + rowGap;
		int fieldCount = 4;
		int gap = 4;
		int fieldWidth = (width - gap * (fieldCount - 1)) / fieldCount;
		String[] labels = {"X:", "Y:", "Z:", "R:"};
		String[] values = {m.xInput, m.yInput, m.zInput, m.rangeInput};
		TextFocus[] focuses = {TextFocus.POSMSG_X, TextFocus.POSMSG_Y, TextFocus.POSMSG_Z, TextFocus.POSMSG_RANGE};
		int fieldX = x;
		for (int f = 0; f < fieldCount; f++) {
			boolean editing = textFocus == focuses[f] && editingPosMsgIndex == index;
			fillRounded(graphics, fieldX, row3Y, fieldX + fieldWidth, row3Y + rowHeight, SMALL_RADIUS, editing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
			graphics.enableScissor(fieldX, row3Y, fieldX + fieldWidth, row3Y + rowHeight);
			String label = labels[f];
			String value = values[f];
			int labelWidth = this.font.width(label);
			if (editing) drawSelectionHighlight(graphics, value, fieldX + 4 + labelWidth, row3Y + 2, rowHeight - 4);
			SmoothTextRenderer.draw(graphics, this.font, label, fieldX + 4, row3Y + 4, Theme.text(0xFF888888));
			SmoothTextRenderer.draw(graphics, this.font, value, fieldX + 4 + labelWidth, row3Y + 4, Theme.text(0xFFCCCCCC));
			if (editing && isCaretBlinkOn()) {
				int idx = Math.max(0, Math.min(textCursor, value.length()));
				int caretX = fieldX + 4 + labelWidth + this.font.width(value.substring(0, idx)) + 1;
				graphics.fill(caretX, row3Y + 3, caretX + 1, row3Y + rowHeight - 3, 0xFFFFFFFF);
			}
			graphics.disableScissor();
			posMsgFieldBoxes.add(new int[]{fieldX, row3Y, fieldWidth, rowHeight, index, focuses[f].ordinal()});
			fieldX += fieldWidth + gap;
		}

		// Row 4: "use current position" button.
		int row4Y = row3Y + rowHeight + rowGap;
		fillRounded(graphics, x, row4Y, x + width, row4Y + rowHeight, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
		String useLabel = "Use Current Position";
		SmoothTextRenderer.draw(graphics, this.font, useLabel, x + (width - this.font.width(useLabel)) / 2, row4Y + 4, Theme.text(0xFFDDDDDD));
		clickHits.add(new ClickHit(x, row4Y, width, rowHeight, () -> { pmf.useCurrentPosition(index); ConfigManager.save(); }));

		return row4Y + rowHeight;
	}

	private static String titleCase(String s) {
		if (s.isEmpty()) return s;
		return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
	}

	/** Whichever of the Classes/Section/Preset dropdowns is currently open (posMsgDropdownIndex/Kind) for
	 *  a Positional Messages row — Classes and Section render as checkmarked multi-select lists (clicking
	 *  a row toggles it and leaves the popup open, same "click outside to close" behavior every other
	 *  popup in this file already has); Preset renders as a plain list that applies-and-closes on click,
	 *  matching how "Use Current Position" is a one-shot fill-the-fields action. */
	private void drawPositionalMessageDropdownPopup(GuiGraphicsExtractor graphics, com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf, int accent) {
		if (posMsgDropdownIndex == null) return;
		List<com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.PosMessage> list = pmf.getMessages();
		int index = posMsgDropdownIndex;
		if (index < 0 || index >= list.size()) { closeStylePopups(); return; }
		com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.PosMessage m = list.get(index);

		record DropdownRow(String label, boolean checked, Runnable action, boolean closesOnClick) {}
		List<DropdownRow> rows = new ArrayList<>();
		String title;
		if ("classes".equals(posMsgDropdownKind)) {
			title = "Classes (none = all)";
			for (com.cokelord.skyblocksimplified.dungeon.DungeonClass c : com.cokelord.skyblocksimplified.dungeon.DungeonClass.values()) {
				if (c == com.cokelord.skyblocksimplified.dungeon.DungeonClass.EMPTY) continue;
				rows.add(new DropdownRow(titleCase(c.name()), m.classes.contains(c), () -> pmf.toggleClass(index, c), false));
			}
		} else if ("section".equals(posMsgDropdownKind)) {
			title = "Sections (none = all)";
			for (com.cokelord.skyblocksimplified.dungeon.DungeonState.TerminalSection s : com.cokelord.skyblocksimplified.dungeon.DungeonState.TerminalSection.values()) {
				if (s == com.cokelord.skyblocksimplified.dungeon.DungeonState.TerminalSection.NONE) continue;
				rows.add(new DropdownRow(s.name(), m.sections.contains(s), () -> pmf.toggleSection(index, s), false));
			}
		} else {
			title = "Presets";
			for (com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.Preset preset : com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature.PRESETS) {
				rows.add(new DropdownRow(preset.label(), false, () -> pmf.applyPreset(index, preset), true));
			}
		}

		int rowHeight = 14;
		int popupWidth = 140;
		int listTop = 18;
		int fullHeight = listTop + rows.size() * rowHeight + 6;
		boolean needsScrollbar = rows.size() > POSMSG_POPUP_MAX_VISIBLE_ROWS;
		// Cap the popup to whichever is smaller: the panel's own viewport, or a fixed row count — see
		// POSMSG_POPUP_MAX_VISIBLE_ROWS's own doc comment for why the fixed cap is needed at all.
		int viewportMax = Math.max(listTop + rowHeight + 6, listViewportY1 - listViewportY0 - 8);
		int rowCountMax = listTop + POSMSG_POPUP_MAX_VISIBLE_ROWS * rowHeight + 6;
		int maxHeight = Math.min(viewportMax, rowCountMax);
		int popupHeight = Math.min(fullHeight, maxHeight);
		maxPosMsgDropdownScroll = Math.max(0, fullHeight - popupHeight);
		posMsgDropdownScroll = Math.max(0f, Math.min(posMsgDropdownScroll, maxPosMsgDropdownScroll));

		int px = clampPopupX(popupAnchorX, popupWidth);
		int py = clampPopupY(popupAnchorY, popupHeight);
		fillRounded(graphics, px, py, px + popupWidth, py + popupHeight, BOX_RADIUS, 0xFF232323);
		SmoothTextRenderer.draw(graphics, this.font, title, px + 8, py + 6, Theme.text(0xFFAAAAAA));

		// Reserve a thin strip on the right for the scrollbar (only when actually scrollable) so row
		// content/hit boxes never sit underneath it.
		int rowRight = px + popupWidth - (needsScrollbar ? 12 : 6);
		int listBottom = py + popupHeight - 2;
		graphics.enableScissor(px, py + listTop, px + popupWidth, listBottom);
		int ry = py + listTop - (int) posMsgDropdownScroll;
		for (DropdownRow row : rows) {
			int thisRy = ry;
			ry += rowHeight;
			if (thisRy + rowHeight < py + listTop || thisRy > listBottom) continue;
			fillRounded(graphics, px + 6, thisRy, rowRight, thisRy + rowHeight - 2, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
			SmoothTextRenderer.draw(graphics, this.font, row.label(), px + 10, thisRy + 3, Theme.text(0xFFDDDDDD));
			if (row.checked()) {
				SmoothTextRenderer.draw(graphics, this.font, "x", rowRight - 12, thisRy + 3, accent);
			}
			clickHits.add(new ClickHit(px + 6, Math.max(thisRy, py + listTop), rowRight - (px + 6),
				Math.min(thisRy + rowHeight - 2, listBottom) - Math.max(thisRy, py + listTop), () -> {
				row.action().run();
				if (row.closesOnClick()) closeStylePopups();
			}));
		}
		graphics.disableScissor();

		// Visible scrollbar — draws even though wheel-scroll alone already moves the list, so the user has
		// a clear, always-present confirmation the list scrolls plus a second (drag-to-scroll) way to move
		// it, in case whatever originally made wheel input feel unreliable here comes back.
		if (needsScrollbar) {
			int trackX0 = px + popupWidth - 9, trackX1 = px + popupWidth - 4;
			int trackY0 = py + listTop, trackY1 = listBottom;
			fillRounded(graphics, trackX0, trackY0, trackX1, trackY1, 1, Theme.chrome(0xFF1A1A1A));
			int trackHeight = trackY1 - trackY0;
			int thumbHeight = Math.max(10, Math.round(trackHeight * ((float) popupHeight / fullHeight)));
			float scrollFraction = maxPosMsgDropdownScroll > 0 ? posMsgDropdownScroll / maxPosMsgDropdownScroll : 0f;
			int thumbY = trackY0 + Math.round((trackHeight - thumbHeight) * scrollFraction);
			fillRounded(graphics, trackX0, thumbY, trackX1, thumbY + thumbHeight, 1, accent);
			// Reuses the existing vertical-SliderHit drag machinery (activeSlider/mouseDragged) instead of a
			// parallel one-off drag flag — same click-and-drag mechanism the color picker's hue/sat sliders
			// already use elsewhere in this file. Vertical sliders report 1.0 at the TOP of the track and 0.0
			// at the bottom (see SliderHit.applyAt), so the scroll fraction is inverted here to turn that back
			// into the natural "top of track = scrolled to top" direction.
			float scrollMax = maxPosMsgDropdownScroll;
			sliderHits.add(new SliderHit(trackX0, trackY0, trackX1 - trackX0, trackHeight,
				true, value -> posMsgDropdownScroll = (1f - value) * scrollMax, "posmsg_dropdown_scroll"));
		}
		popupX0 = px; popupY0 = py; popupX1 = px + popupWidth; popupY1 = py + popupHeight;
	}

	/**
	 * One rule row: an editable Name field, a small editable Level field (accepts roman or decimal —
	 * see RomanNumeralUtil — the raw typed text is shown as-is, normalized level used only for matching),
	 * then Color/Bold/Chroma("R")/Tilted("/") style buttons and a remove button, laid out right-to-left
	 * the same way drawLineRow does (minus alignment/reorder, which don't apply here).
	 */
	private int drawEnchantRuleRow(GuiGraphicsExtractor graphics, CustomEnchantParsingFeature cef, CustomEnchantParsingFeature.Rule rule,
									int index, int x, int y, int width, int accent) {
		int rowTop = y;
		int rowHeight = 16;
		boolean nameEditing = textFocus == TextFocus.ENCHANT_NAME && editingRuleIndex == index;
		boolean levelEditing = textFocus == TextFocus.ENCHANT_LEVEL && editingRuleIndex == index;
		fillRounded(graphics, x + 8, rowTop, x + width - 8, rowTop + rowHeight, SMALL_RADIUS, (nameEditing || levelEditing) ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF1E1E1E));

		int btnSize = 12;
		int btnGap = 4;
		int cursorX = x + width - 8 - btnSize - 4;

		int removeX = cursorX, removeY = rowTop + 2;
		fillCircle(graphics, removeX + btnSize / 2, removeY + btnSize / 2, btnSize / 2, COLOR_CLOSE_BUTTON);
		SmoothTextRenderer.draw(graphics, this.font, "x", removeX + (btnSize - this.font.width("x")) / 2, removeY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(removeX, removeY, btnSize, btnSize, () -> { cef.removeRule(index); ConfigManager.save(); }));
		cursorX -= btnSize + btnGap;

		int colorX = cursorX, colorY = rowTop + 2;
		fillRounded(graphics, colorX, colorY, colorX + btnSize, colorY + btnSize, SMALL_RADIUS, rule.color);
		int colorPopupX = colorX, colorPopupY = colorY;
		clickHits.add(new ClickHit(colorX, colorY, btnSize, btnSize, () -> {
			enchantColorPopupTarget = rule;
			enchantChromaPopupTarget = null;
			popupAnchorX = colorPopupX;
			popupAnchorY = colorPopupY + btnSize + 2;
		}));
		cursorX -= btnSize + btnGap;

		int tiltX = cursorX, tiltY = rowTop + 2;
		fillRounded(graphics, tiltX, tiltY, tiltX + btnSize, tiltY + btnSize, SMALL_RADIUS, rule.tilted ? accent : Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, "/", tiltX + (btnSize - this.font.width("/")) / 2, tiltY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(tiltX, tiltY, btnSize, btnSize, () -> { rule.tilted = !rule.tilted; ConfigManager.save(); }));
		cursorX -= btnSize + btnGap;

		int chromaX = cursorX, chromaY = rowTop + 2;
		fillRounded(graphics, chromaX, chromaY, chromaX + btnSize, chromaY + btnSize, SMALL_RADIUS, rule.chromaEnabled ? accent : Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, "R", chromaX + (btnSize - this.font.width("R")) / 2, chromaY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		int chromaPopupX = chromaX, chromaPopupY = chromaY;
		clickHits.add(new ClickHit(chromaX, chromaY, btnSize, btnSize, () -> {
			enchantChromaPopupTarget = rule;
			enchantColorPopupTarget = null;
			popupAnchorX = chromaPopupX;
			popupAnchorY = chromaPopupY + btnSize + 2;
		}));
		cursorX -= btnSize + btnGap;

		int boldX = cursorX, boldY = rowTop + 2;
		fillRounded(graphics, boldX, boldY, boldX + btnSize, boldY + btnSize, SMALL_RADIUS, rule.bold ? accent : Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, "B", boldX + (btnSize - this.font.width("B")) / 2, boldY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(boldX, boldY, btnSize, btnSize, () -> { rule.bold = !rule.bold; ConfigManager.save(); }));
		cursorX -= btnSize + btnGap + 4;

		int levelBoxWidth = 34;
		int levelBoxX = cursorX - levelBoxWidth;
		fillRounded(graphics, levelBoxX, rowTop, levelBoxX + levelBoxWidth, rowTop + rowHeight, SMALL_RADIUS, levelEditing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(levelBoxX, rowTop, levelBoxX + levelBoxWidth, rowTop + rowHeight);
		if (levelEditing) drawSelectionHighlight(graphics, rule.levelInput, levelBoxX + 4, rowTop + 2, rowHeight - 4);
		SmoothTextRenderer.draw(graphics, this.font, rule.levelInput, levelBoxX + 4, rowTop + 4, Theme.text(0xFFCCCCCC));
		if (levelEditing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, rule.levelInput.length()));
			int caretX = levelBoxX + 4 + this.font.width(rule.levelInput.substring(0, idx)) + 1;
			graphics.fill(caretX, rowTop + 3, caretX + 1, rowTop + rowHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		enchantLevelBoxes.add(new int[]{levelBoxX, rowTop, levelBoxWidth, rowHeight, index});

		int nameBoxX = x + 14;
		int nameBoxWidth = Math.max(20, levelBoxX - 4 - nameBoxX);
		fillRounded(graphics, nameBoxX, rowTop, nameBoxX + nameBoxWidth, rowTop + rowHeight, SMALL_RADIUS, nameEditing ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(nameBoxX, rowTop, nameBoxX + nameBoxWidth, rowTop + rowHeight);
		String nameDisplay = !nameEditing && rule.name.isEmpty() ? "(enchant name)" : rule.name;
		if (nameEditing) drawSelectionHighlight(graphics, rule.name, nameBoxX + 4, rowTop + 2, rowHeight - 4);
		SmoothTextRenderer.draw(graphics, this.font, nameDisplay, nameBoxX + 4, rowTop + 4, Theme.text(0xFFCCCCCC));
		if (nameEditing && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, rule.name.length()));
			int caretX = nameBoxX + 4 + this.font.width(rule.name.substring(0, idx)) + 1;
			graphics.fill(caretX, rowTop + 3, caretX + 1, rowTop + rowHeight - 3, 0xFFFFFFFF);
		}
		graphics.disableScissor();
		enchantNameBoxes.add(new int[]{nameBoxX, rowTop, nameBoxWidth, rowHeight, index});

		return rowTop + rowHeight;
	}

	/** One "Level N" tier-override row — same right-to-left button layout/sizing as {@link
	 *  #drawEnchantRuleRow}'s style buttons (Bold/Tilted/color swatch), just without the name/level text
	 *  fields since the level here is fixed by the row itself, not user-editable. */
	private int drawEnchantTierRow(GuiGraphicsExtractor graphics, CustomEnchantParsingFeature.TierOverride tier,
									int level, int x, int y, int width, int accent) {
		int rowTop = y;
		int rowHeight = 16;
		fillRounded(graphics, x + 8, rowTop, x + width - 8, rowTop + rowHeight, SMALL_RADIUS, Theme.chrome(0xFF1E1E1E));

		int btnSize = 12;
		int btnGap = 4;
		int cursorX = x + width - 8 - btnSize - 4;

		int tiltX = cursorX, tiltY = rowTop + 2;
		fillRounded(graphics, tiltX, tiltY, tiltX + btnSize, tiltY + btnSize, SMALL_RADIUS, tier.tilted ? accent : Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, "/", tiltX + (btnSize - this.font.width("/")) / 2, tiltY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(tiltX, tiltY, btnSize, btnSize, () -> { tier.tilted = !tier.tilted; ConfigManager.save(); }));
		cursorX -= btnSize + btnGap;

		int boldX = cursorX, boldY = rowTop + 2;
		fillRounded(graphics, boldX, boldY, boldX + btnSize, boldY + btnSize, SMALL_RADIUS, tier.bold ? accent : Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, "B", boldX + (btnSize - this.font.width("B")) / 2, boldY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		clickHits.add(new ClickHit(boldX, boldY, btnSize, btnSize, () -> { tier.bold = !tier.bold; ConfigManager.save(); }));
		cursorX -= btnSize + btnGap;

		int colorX = cursorX, colorY = rowTop + 2;
		fillRounded(graphics, colorX, colorY, colorX + btnSize, colorY + btnSize, SMALL_RADIUS, tier.color);
		int colorPopupX = colorX, colorPopupY = colorY;
		clickHits.add(new ClickHit(colorX, colorY, btnSize, btnSize, () -> {
			enchantTierColorPopupTarget = tier;
			popupAnchorX = colorPopupX;
			popupAnchorY = colorPopupY + btnSize + 2;
		}));
		cursorX -= btnSize + btnGap;

		int enableX = cursorX, enableY = rowTop + 2;
		fillRounded(graphics, enableX, enableY, enableX + btnSize, enableY + btnSize, SMALL_RADIUS, tier.enabled ? accent : Theme.chrome(0xFF2A2A2A));
		if (tier.enabled) {
			String check = "✓";
			SmoothTextRenderer.draw(graphics, this.font, check, enableX + (btnSize - this.font.width(check)) / 2, enableY + (btnSize - 8) / 2, Theme.text(0xFFFFFFFF));
		}
		clickHits.add(new ClickHit(enableX, enableY, btnSize, btnSize, () -> { tier.enabled = !tier.enabled; ConfigManager.save(); }));

		SmoothTextRenderer.draw(graphics, this.font, "Level " + level, x + 14, rowTop + 4, Theme.text(tier.enabled ? 0xFFDDDDDD : 0xFF888888));

		return rowTop + rowHeight;
	}

	/** Same popup mechanics as drawLineStylePopups (color square/hex vs. chroma toggle+sliders), just
	 *  targeting enchantColorPopupTarget/enchantChromaPopupTarget instead of the scoreboard's Line —
	 *  drawFullColorPicker/drawTextChromaSliders are already generic over get/set callbacks so the only
	 *  thing that actually differs here is which object's fields those callbacks close over. */
	// Per user follow-up ("The little gui for the hex picker looks good but remove the title from the hex
	// picker cause it overlaps everything. They users know what module its for anyway."): every popup below
	// used to draw its own "X color"/"Step color" caption above the picker — removed, and the picker shifted
	// up to where the caption used to be (popupHeight shrunk to match, so the popup itself is smaller now too).
	private void drawEnchantStylePopups(GuiGraphicsExtractor graphics, int accent) {
		if (enchantColorPopupTarget != null) {
			CustomEnchantParsingFeature.Rule rule = enchantColorPopupTarget;
			int popupWidth = 170;
			int popupHeight = STYLE_SQUARE_SIZE + HEX_FIELD_HEIGHT + 18;
			int px = clampPopupX(popupAnchorX - popupWidth + 20, popupWidth);
			int py = clampPopupY(popupAnchorY, popupHeight);
			fillRounded(graphics, px, py, px + popupWidth, py + popupHeight, BOX_RADIUS, 0xFF232323);
			drawFullColorPicker(graphics, px + 10, py + 6, popupWidth - 20, "enchant_color_popup", STYLE_SQUARE_SIZE, STYLE_SQUARE_CELLS, accent,
				() -> rule.color, c -> { rule.color = (rule.color & 0xFF000000) | (c & 0xFFFFFF); ConfigManager.save(); }, 0xFFFFFFFF);
			popupX0 = px; popupY0 = py; popupX1 = px + popupWidth; popupY1 = py + popupHeight;
		}
		if (enchantChromaPopupTarget != null) {
			CustomEnchantParsingFeature.Rule rule = enchantChromaPopupTarget;
			int popupWidth = 130;
			int popupHeight = 100;
			int px = clampPopupX(popupAnchorX - popupWidth + 20, popupWidth);
			int py = clampPopupY(popupAnchorY, popupHeight);
			fillRounded(graphics, px, py, px + popupWidth, py + popupHeight, BOX_RADIUS, 0xFF232323);
			int cy = drawToggleRow(graphics, px + 10, py + 8, popupWidth - 20, "Chroma",
				rule.chromaEnabled, () -> { rule.chromaEnabled = !rule.chromaEnabled; ConfigManager.save(); });
			cy += 2;
			drawTextChromaSliders(graphics, px + 10, cy, "enchant_chroma_popup", accent,
				rule.chromaSize, v -> { rule.chromaSize = 0.1f + v * 4.9f; ConfigManager.save(); },
				rule.chromaSpeed, v -> { rule.chromaSpeed = v * 5f; ConfigManager.save(); },
				rule.chromaSaturation, v -> { rule.chromaSaturation = v; ConfigManager.save(); });
			popupX0 = px; popupY0 = py; popupX1 = px + popupWidth; popupY1 = py + popupHeight;
		}
		if (enchantTierColorPopupTarget != null) {
			CustomEnchantParsingFeature.TierOverride tier = enchantTierColorPopupTarget;
			int popupWidth = 170;
			int popupHeight = STYLE_SQUARE_SIZE + HEX_FIELD_HEIGHT + 18;
			int px = clampPopupX(popupAnchorX - popupWidth + 20, popupWidth);
			int py = clampPopupY(popupAnchorY, popupHeight);
			fillRounded(graphics, px, py, px + popupWidth, py + popupHeight, BOX_RADIUS, 0xFF232323);
			drawFullColorPicker(graphics, px + 10, py + 6, popupWidth - 20, "enchant_tier_color_popup", STYLE_SQUARE_SIZE, STYLE_SQUARE_CELLS, accent,
				() -> tier.color, c -> { tier.color = (tier.color & 0xFF000000) | (c & 0xFFFFFF); ConfigManager.save(); }, 0xFFFFFFFF);
			popupX0 = px; popupY0 = py; popupX1 = px + popupWidth; popupY1 = py + popupHeight;
		}
		if (dungeonNotifColorPopupTarget != null) {
			com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType type = dungeonNotifColorPopupTarget;
			com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature dnf = currentDungeonNotifications();
			if (dnf == null) {
				dungeonNotifColorPopupTarget = null;
			} else {
				int popupWidth = 170;
				int popupHeight = STYLE_SQUARE_SIZE + HEX_FIELD_HEIGHT + 18;
				int px = clampPopupX(popupAnchorX - popupWidth + 20, popupWidth);
				int py = clampPopupY(popupAnchorY, popupHeight);
				fillRounded(graphics, px, py, px + popupWidth, py + popupHeight, BOX_RADIUS, 0xFF232323);
				drawFullColorPicker(graphics, px + 10, py + 6, popupWidth - 20, "dungeon_notif_color_popup", STYLE_SQUARE_SIZE, STYLE_SQUARE_CELLS, accent,
					() -> dnf.getTypeColor(type), c -> { dnf.setTypeColor(type, (dnf.getTypeColor(type) & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, 0xFFFF55FF);
				popupX0 = px; popupY0 = py; popupX1 = px + popupWidth; popupY1 = py + popupHeight;
			}
		}
		if (copilotColorPopupTarget != null) {
			com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item = copilotColorPopupTarget;
			int popupWidth = 170;
			int popupHeight = STYLE_SQUARE_SIZE + HEX_FIELD_HEIGHT + 18;
			int px = clampPopupX(popupAnchorX - popupWidth + 20, popupWidth);
			int py = clampPopupY(popupAnchorY, popupHeight);
			fillRounded(graphics, px, py, px + popupWidth, py + popupHeight, BOX_RADIUS, 0xFF232323);
			drawFullColorPicker(graphics, px + 10, py + 6, popupWidth - 20, "copilot_color_popup", STYLE_SQUARE_SIZE, STYLE_SQUARE_CELLS, accent,
				() -> copilotItemColor(item), c -> { setCopilotItemColor(item, c); ConfigManager.save(); }, 0xFFFFFFFF);
			popupX0 = px; popupY0 = py; popupX1 = px + popupWidth; popupY1 = py + popupHeight;
		}
		if (routeColorPopupTarget != null) {
			com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item = routeColorPopupTarget;
			int popupWidth = 170;
			int popupHeight = STYLE_SQUARE_SIZE + HEX_FIELD_HEIGHT + 18;
			int px = clampPopupX(popupAnchorX - popupWidth + 20, popupWidth);
			int py = clampPopupY(popupAnchorY, popupHeight);
			fillRounded(graphics, px, py, px + popupWidth, py + popupHeight, BOX_RADIUS, 0xFF232323);
			drawFullColorPicker(graphics, px + 10, py + 6, popupWidth - 20, "route_color_popup", STYLE_SQUARE_SIZE, STYLE_SQUARE_CELLS, accent,
				() -> com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.routeItemColor(item),
				c -> { com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.setRouteItemColor(item, c); ConfigManager.save(); }, 0xFFFFFFFF);
			popupX0 = px; popupY0 = py; popupX1 = px + popupWidth; popupY1 = py + popupHeight;
		}
		if (routeSelectedPopupTarget != null) {
			com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem item = routeSelectedPopupTarget;
			int rowH = 16;
			int headerH = 18;
			int popupWidth = 160;
			int popupHeight = headerH + Math.max(1, item.blocks.size()) * (rowH + 2) + 6;
			int px = clampPopupX(popupAnchorX, popupWidth);
			int py = clampPopupY(popupAnchorY, popupHeight);
			fillRounded(graphics, px, py, px + popupWidth, py + popupHeight, BOX_RADIUS, 0xFF232323);
			String header = item.blocks.isEmpty() ? "Nothing selected yet" : item.blocks.size() + " selected (relative)";
			SmoothTextRenderer.draw(graphics, this.font, header, px + 8, py + 6, Theme.text(0xFFAAAAAA));
			int ry = py + headerH;
			for (net.minecraft.core.BlockPos pos : new ArrayList<>(item.blocks)) {
				fillRounded(graphics, px + 6, ry, px + popupWidth - 6, ry + rowH, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
				String posLabel = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
				int xBtnSize = 12;
				int xBtnX = px + popupWidth - 6 - xBtnSize - 2;
				graphics.enableScissor(px + 8, ry, xBtnX - 2, ry + rowH);
				SmoothTextRenderer.draw(graphics, this.font, posLabel, px + 8, ry + 4, Theme.text(0xFFDDDDDD));
				graphics.disableScissor();
				int xBtnY = ry + (rowH - xBtnSize) / 2;
				fillCircle(graphics, xBtnX + xBtnSize / 2, xBtnY + xBtnSize / 2, xBtnSize / 2, COLOR_CLOSE_BUTTON);
				SmoothTextRenderer.draw(graphics, this.font, "x", xBtnX + (xBtnSize - this.font.width("x")) / 2, xBtnY + (xBtnSize - 8) / 2, Theme.text(0xFFFFFFFF));
				clickHits.add(new ClickHit(xBtnX, xBtnY, xBtnSize, xBtnSize, () -> {
					item.blocks.remove(pos);
					ConfigManager.save();
				}));
				ry += rowH + 2;
			}
			popupX0 = px; popupY0 = py; popupX1 = px + popupWidth; popupY1 = py + popupHeight;
		}
	}

	private static int copilotItemColor(com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item) {
		return switch (item.type) {
			case TITLE -> item.titleColor;
			case BREAKABLE_BLOCKS -> item.blockColor;
			case WAYPOINT -> item.waypointColor;
			case DEVICE_FINISHED -> item.blockColor;
			case LEAP_USED -> item.blockColor;
			case TERMINAL_DONE -> item.blockColor;
			case BLOCK_WATCH -> item.blockColor;
		};
	}

	private static void setCopilotItemColor(com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem item, int rgb) {
		switch (item.type) {
			case TITLE -> item.titleColor = (item.titleColor & 0xFF000000) | (rgb & 0xFFFFFF);
			case BREAKABLE_BLOCKS -> item.blockColor = (item.blockColor & 0xFF000000) | (rgb & 0xFFFFFF);
			case WAYPOINT -> item.waypointColor = (item.waypointColor & 0xFF000000) | (rgb & 0xFFFFFF);
			case DEVICE_FINISHED -> item.blockColor = (item.blockColor & 0xFF000000) | (rgb & 0xFFFFFF);
			case LEAP_USED -> item.blockColor = (item.blockColor & 0xFF000000) | (rgb & 0xFFFFFF);
			case TERMINAL_DONE -> item.blockColor = (item.blockColor & 0xFF000000) | (rgb & 0xFFFFFF);
			case BLOCK_WATCH -> item.blockColor = (item.blockColor & 0xFF000000) | (rgb & 0xFFFFFF);
		}
	}

	private static final int STYLE_SQUARE_SIZE = 40;
	private static final int STYLE_SQUARE_CELLS = 10;

	/** Three compact checkbox toggles on one row ("Bold [x] Chroma [x] Tilted [x]"), for title/footer text style. */
	private int drawTextStyleToggles(GuiGraphicsExtractor graphics, int x, int y, int width, int accent,
									  boolean bold, Runnable toggleBold, boolean chroma, Runnable toggleChroma, boolean tilted, Runnable toggleTilted) {
		String[] labels = {"Bold", "Chroma", "Tilted"};
		boolean[] values = {bold, chroma, tilted};
		Runnable[] actions = {toggleBold, toggleChroma, toggleTilted};
		int boxSize = 9;
		int cx = x;
		for (int i = 0; i < labels.length; i++) {
			SmoothTextRenderer.draw(graphics, this.font, labels[i], cx, y, Theme.text(0xFFAAAAAA));
			int labelWidth = this.font.width(labels[i]);
			int boxX = cx + labelWidth + 3;
			int boxY = y - 1;
			fillRounded(graphics, boxX, boxY, boxX + boxSize, boxY + boxSize, SMALL_RADIUS, values[i] ? accent : Theme.chrome(0xFF2A2A2A));
			if (values[i]) {
				SmoothTextRenderer.draw(graphics, this.font, "x", boxX + 1, boxY - 1, Theme.text(0xFFFFFFFF));
			}
			clickHits.add(new ClickHit(boxX, boxY, boxSize, boxSize, actions[i]));
			cx = boxX + boxSize + 8;
		}
		return y + 14;
	}

	/** Bottom-to-top vertical sliders for a chroma cycle (size/speed/saturation) — kept vertical to
	 *  match the convention that color/customization controls run bottom-to-top while everything else
	 *  in this file is horizontal. Grouped tightly next to each other (small fixed gap) rather than
	 *  spread across the full available width. keyPrefix keeps this instance's slider-position-animation
	 *  state distinct from any other chroma slider group drawn in the same frame (background vs. title
	 *  vs. footer all have their own). */
	private int drawTextChromaSliders(GuiGraphicsExtractor graphics, int x, int y, String keyPrefix, int accent,
									   float chromaSize, Consumer<Float> setChromaSize,
									   float chromaSpeed, Consumer<Float> setChromaSpeed,
									   float chromaSaturation, Consumer<Float> setChromaSaturation) {
		String[] labels = {"Size", "Speed", "Sat."};
		float[] values01 = {(chromaSize - 0.1f) / 4.9f, chromaSpeed / 5f, chromaSaturation};
		Consumer<Float>[] setters = new Consumer[]{setChromaSize, setChromaSpeed, setChromaSaturation};
		int trackHeight = 50;
		int sliderWidth = 10;
		// Gap sized off the widest label ("Speed") so labels don't overlap their neighbors — a fixed
		// small gap looked "next to each other" as asked, but was tight enough to overlap the text.
		int widestLabel = 0;
		for (String label : labels) widestLabel = Math.max(widestLabel, this.font.width(label));
		int gap = Math.max(8, widestLabel - sliderWidth + 6);
		for (int i = 0; i < labels.length; i++) {
			int sx = x + i * (sliderWidth + gap);
			SmoothTextRenderer.draw(graphics, this.font, labels[i], sx + (sliderWidth - this.font.width(labels[i])) / 2, y, Theme.text(0xFFAAAAAA));
			int trackY = y + 11;
			String sliderKey = keyPrefix + "_" + labels[i];
			drawVerticalSlider(graphics, sliderKey, sx, trackY, sliderWidth, trackHeight, values01[i], accent);
			int index = i;
			sliderHits.add(new SliderHit(sx - 3, trackY - 3, sliderWidth + 6, trackHeight + 6,
				sx, trackY, sliderWidth, trackHeight, true, setters[index], sliderKey));
		}
		return y + 11 + trackHeight + 8;
	}

	/** Renders one bindable-key row per KeyMapping, same layout every feature's default settings panel uses
	 *  — factored out so features with a custom settings branch (e.g. Leap Menu) can still show their own
	 *  keybinds instead of only getting them for free via the generic fallback branch. Per user report
	 *  ("I should also be able to select binds for the leaps"): Leap Menu's four corner KeyMappings were
	 *  fully wired up on the feature side (getKeybinds(), tickKeybinds()) but its settings branch never
	 *  called this rendering at all, so there was simply no UI control to bind them from. */
	private int drawKeybindRows(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int startY, List<KeyMapping> keybinds) {
		int slotY = startY;
		// The keybind capture control is now the wide button itself (was a separate far-right square
		// with the bound-key text floating outside it, off to the left, which read as disconnected
		// and cramped) — click anywhere on it to rebind, current binding shown centered inside.
		int buttonWidth = 130;
		for (KeyMapping keyMapping : keybinds) {
			// KeyMapping#getName() returns the raw translation KEY, not translated text — this was
			// rendering the literal "key.skyblocksimplified.loadout_slot_N" string instead of resolving
			// it through the lang file (which does have the right "Slot N" entries).
			String label = net.minecraft.client.resources.language.I18n.get(keyMapping.getName());
			SmoothTextRenderer.draw(graphics, this.font, label, x + 10, slotY + 3, Theme.text(0xFFAAAAAA));

			boolean listening = keyMapping == listeningFor;
			String bound = listening ? "press a key..." : (keyMapping.isUnbound() ? "unbound" : keyMapping.getTranslatedKeyMessage().getString());
			int buttonX = x + width - buttonWidth - 12;
			fillRounded(graphics, buttonX, slotY, buttonX + buttonWidth, slotY + SLOT_SIZE, SMALL_RADIUS, listening ? colorKeybindListening() : colorKeybindBox());
			// Was slotY + 5, which — against this row's 14px height and the font's 9px line height —
			// left only a 0px gap below the text vs. a 5px gap above it, reading as "stuck to the
			// bottom" per user report. slotY + 3 centers it (14 - 9 = 5, so 2-3px on each side).
			SmoothTextRenderer.draw(graphics, this.font, bound, buttonX + (buttonWidth - this.font.width(bound)) / 2, slotY + 3, Theme.text(0xFFDDDDDD));

			if (slotY >= y && slotY + SLOT_SIZE <= y + height) {
				slotRows.add(new SlotRow(keyMapping, buttonX, slotY, buttonWidth, SLOT_SIZE, true));
			}
			slotY += SLOT_ROW_HEIGHT;
		}
		return slotY;
	}

	private int drawToggleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, boolean value, Runnable onToggle) {
		return drawToggleRow(graphics, x, y, width, label, value, onToggle, null);
	}

	/** Per user request ("tooltips for every feature in the mod... hovering... a subtoggle inside the module
	 *  should show a small tooltip explaining what it does"): same 2-second-hover mechanism as the module-name
	 *  tooltip above, keyed on the label text itself (unique per row within a single settings panel) so it
	 *  survives panel scroll position changing frame to frame. tooltip == null means no tooltip (every
	 *  pre-existing call site through the no-tooltip overload above keeps behaving exactly as before). */
	private int drawToggleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, boolean value, Runnable onToggle, String tooltip) {
		return drawToggleRow(graphics, x, y, width, label, label, value, onToggle, tooltip);
	}

	/** Real bug found (per user report — "Chroma toggle is buggy. It stops in the middle and also links
	 *  with the chroma toggle for the text above. It doesnt enable it but the animation plays for that one
	 *  too"): the Accent Color's own "Chroma" toggle row and the Custom panel theme's gradient "Chroma" toggle
	 *  row both call the label-keyed overload above with the literal same string "Chroma" — {@link
	 *  #toggleAnimValue} keys its shared {@code toggleAnims} map purely by that string, so both rows'
	 *  {@code drawToggleRow} calls this same frame drive ONE shared {@link Anim} instance, each overwriting
	 *  the other's target via {@code setTarget} before the other ever gets to read a settled value — a real
	 *  "target flips between two different toggles' states every frame" bug, exactly matching "stops in the
	 *  middle" (the anim chases whichever target won the last overwrite instead of ever reaching either) and
	 *  "plays the animation for that one too" (the shared instance visibly moves even though the OTHER row's
	 *  own boolean value never changed). This overload lets a caller pass a separate {@code animKey}
	 *  (distinct per toggle instance, e.g. "accent_chroma"/"gradient_chroma") while keeping the exact same
	 *  displayed label text — every pre-existing single-label caller still gets an animKey equal to its own
	 *  label via the overload above, so nothing else regresses. */
	private int drawToggleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String animKey, String label, boolean value, Runnable onToggle, String tooltip) {
		SmoothTextRenderer.draw(graphics, this.font, label, x, y, Theme.text(0xFFAAAAAA));
		int toggleW = 28, toggleH = 12;
		int tx = x + width - toggleW;
		int ty = y - 1;
		float t = toggleAnimValue(animKey, value);
		RenderUtil.fillPill(graphics, tx, ty, tx + toggleW, ty + toggleH, FULL_ROUND, RenderUtil.lerpColor(colorToggleOff(tx, ty, toggleW, toggleH), colorToggleOn(), t));
		int knob = toggleH - 4;
		int kx = tx + 2 + Math.round(t * (toggleW - knob - 4));
		fillCircle(graphics, kx + knob / 2, ty + 2 + knob / 2, knob / 2, RenderUtil.lerpColor(colorKnobOff(), COLOR_KNOB_ON, t));
		clickHits.add(new ClickHit(tx, ty, toggleW, toggleH, () -> { playToggleSound(!value); onToggle.run(); }));
		if (tooltip != null) checkHoverTooltip(lastMouseX, lastMouseY, x, y - 2, width, 18, "toggle_" + animKey, tooltip);
		return y + 18;
	}

	/** Same shape as drawToggleRow, but for a KeyCombo setting inside a settings panel — for toggleable
	 *  features whose primary combo can't use the generic row-level slot (that slot always shows the
	 *  on/off toggle instead whenever the feature is toggleable, so a combo with nowhere else to go could
	 *  never actually be bound through the UI at all — the real "right-click chat to copy does nothing"
	 *  bug, since its combo could never be captured in the first place). */
	private int drawComboCaptureRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, KeyCombo combo) {
		SmoothTextRenderer.draw(graphics, this.font, label, x, y, Theme.text(0xFFAAAAAA));
		boolean listening = combo == listeningForCombo;
		String valueLabel = listening ? comboCaptureLabel() : (combo.isEmpty() ? "NONE" : combo.getDisplayName());
		int boxWidth = Math.max(70, this.font.width(valueLabel) + 10);
		int boxHeight = 14;
		int bx = x + width - boxWidth;
		int by = y - 2;
		fillRounded(graphics, bx, by, bx + boxWidth, by + boxHeight, BOX_RADIUS, listening ? colorKeybindListening() : colorKeybindBox());
		// See drawCycleRow's own doc comment on the same fix — every boxed value centers now, not just
		// left-aligns with a fixed inset.
		int comboTextX = bx + Math.max(0, (boxWidth - this.font.width(valueLabel)) / 2);
		SmoothTextRenderer.draw(graphics, this.font, valueLabel, comboTextX, by + 3, Theme.text(0xFFDDDDDD));
		comboRows.add(new ComboRow(combo, bx, by, boxWidth, boxHeight));
		return y + 18;
	}

	private static final int[] SWATCH_CYCLE_PRESETS = {
		0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF, 0xFF5555FF, 0xFFFF55FF, 0xFFFFFFFF
	};

	/** Named accent-color presets for the quick-pick row above the full picker — a shortcut over
	 *  the same {@link GuiColorFeature#setColor} the full drag/hex picker already writes to, not a
	 *  separate color system. Deliberately doesn't touch panel/background colors — that's the real,
	 *  separate {@link com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature} module now (per user
	 *  clarification: "Themes clarification: NOT mod accent color... wants a SEPARATE module with three
	 *  real panel themes" — this row used to be mislabeled "Themes", which is exactly what caused that
	 *  confusion, so it's named "Accent Presets" now to keep it distinct from the real Panel Theme
	 *  module). Only the one accent value every highlight/knob/slider in the menu already reads. */
	private static final String[] ACCENT_PRESET_NAMES = {
		"Crimson", "Amber", "Emerald", "Ocean", "Violet", "Rose", "Cyan", "Slate"
	};
	private static final int[] ACCENT_PRESET_COLORS = {
		0xFFCC3333, 0xFFD98E2C, 0xFF3FA34D, 0xFF2E7CD6, 0xFF7B4FCC, 0xFFD6478F, 0xFF2CB8B8, 0xFF7A828E
	};

	/** Row of small named-preset swatches, each independently clickable (unlike {@link #drawColorCycleRow},
	 *  which cycles ONE swatch through a fixed list — this draws all the choices side by side). Clicking a
	 *  swatch snaps the accent straight to that preset and turns chroma off (a static pick should stick,
	 *  not get overwritten by the next chroma tick). Hovering shows the preset's name via the shared
	 *  tooltip queue. */
	private int drawAccentPresetsRow(GuiGraphicsExtractor graphics, GuiColorFeature gcf, int x, int y, int width, int mouseX, int mouseY) {
		SmoothTextRenderer.draw(graphics, this.font, "Accent Presets", x, y, Theme.text(0xFFAAAAAA));
		int size = COLOR_SWATCH_SIZE;
		int gap = 4;
		int count = ACCENT_PRESET_COLORS.length;
		int totalWidth = count * size + (count - 1) * gap;
		int startX = x + width - totalWidth;
		int sy = y - 2;
		int currentColor = gcf.getColor() | 0xFF000000;
		for (int i = 0; i < count; i++) {
			int sx = startX + i * (size + gap);
			int preset = ACCENT_PRESET_COLORS[i] | 0xFF000000;
			boolean selected = preset == currentColor && !gcf.isChromaEnabled();
			if (selected) {
				fillRounded(graphics, sx - 2, sy - 2, sx + size + 2, sy + size + 2, SMALL_RADIUS, 0xFFFFFFFF);
			}
			fillRounded(graphics, sx, sy, sx + size, sy + size, SMALL_RADIUS, preset);
			checkHoverTooltip(mouseX, mouseY, sx, sy, size, size, "theme_preset_" + i, ACCENT_PRESET_NAMES[i]);
			int chosen = ACCENT_PRESET_COLORS[i];
			clickHits.add(new ClickHit(sx, sy, size, size, () -> {
				playClickSound();
				gcf.setChromaEnabled(false);
				gcf.setColor(chosen);
				ConfigManager.save();
			}));
		}
		return y + 18;
	}

	/** Compact "label + clickable color square" row that cycles through a small fixed palette on click —
	 *  a lighter-weight alternative to the full drag/hex color picker for panels that need several
	 *  independent colors at once (a full picker per color wouldn't fit). */
	private int drawColorCycleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, int currentColor, java.util.function.IntConsumer onChange) {
		return drawColorCycleRow(graphics, x, y, width, label, currentColor, onChange, null);
	}

	/** Tooltip-aware overload — see {@link #drawToggleRow(GuiGraphicsExtractor, int, int, int, String, boolean, Runnable, String)}'s doc comment. */
	private int drawColorCycleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, int currentColor, java.util.function.IntConsumer onChange, String tooltip) {
		SmoothTextRenderer.draw(graphics, this.font, label, x, y, Theme.text(0xFFAAAAAA));
		int size = COLOR_SWATCH_SIZE;
		int sx = x + width - size;
		int sy = y - 2;
		fillRounded(graphics, sx, sy, sx + size, sy + size, SMALL_RADIUS, currentColor | 0xFF000000);
		clickHits.add(new ClickHit(sx, sy, size, size, () -> {
			playClickSound();
			int idx = 0;
			for (int i = 0; i < SWATCH_CYCLE_PRESETS.length; i++) {
				if (SWATCH_CYCLE_PRESETS[i] == (currentColor | 0xFF000000)) { idx = i + 1; break; }
			}
			onChange.accept(SWATCH_CYCLE_PRESETS[idx % SWATCH_CYCLE_PRESETS.length]);
		}));
		if (tooltip != null) checkHoverTooltip(lastMouseX, lastMouseY, x, y - 2, width, 18, "colorcycle_" + label, tooltip);
		return y + 18;
	}

	private static String renderModeLabel(MobHighlightFeature.RenderMode mode) {
		return switch (mode) {
			case OUTLINE_2D -> "2D";
			case FULL_2D -> "2D Fill";
			case WIRE_3D -> "3D";
			case FULL_3D -> "3D Fill";
		};
	}

	private static void cycleRenderMode(MobHighlightFeature mhf) {
		mhf.setRenderMode(nextRenderMode(mhf.getRenderMode()));
	}

	/** Same advance-by-one-with-wraparound as {@link #cycleRenderMode} above, but taking the bare enum value
	 *  directly — for a module (e.g. BloodCampFeature) that reuses {@link MobHighlightFeature.RenderMode} for
	 *  its own render selector without being a MobHighlightFeature itself. */
	private static MobHighlightFeature.RenderMode nextRenderMode(MobHighlightFeature.RenderMode mode) {
		MobHighlightFeature.RenderMode[] modes = MobHighlightFeature.RenderMode.values();
		return modes[(mode.ordinal() + 1) % modes.length];
	}

	private static String panelThemeLabel(com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature.Mode mode) {
		return switch (mode) {
			case BLACK -> "Black";
			case WHITE -> "White";
			case TRANSPARENT -> "Transparent";
			case CUSTOM -> "Custom";
		};
	}

	private static final int GRADIENT_SQUARE_SIZE = 70;

	/** The Custom panel theme's own gradient editor — redesigned this round around a square live preview (the
	 *  real {@link RenderUtil#fillPanelGradient} renderer at a small size, never an approximation) with the
	 *  two pins drawn DIRECTLY on the square itself (per user correction — "the pins should be next to the
	 *  gradient preview square... they should always [be] opposite each other, but the angle of them depends
	 *  on the clock angle selected"), replacing an earlier, wrong attempt at this that used separate sliders
	 *  instead. Each pin's screen position is derived from the EXACT SAME direction math the real gradient
	 *  render uses ({@code (sin θ,-cos θ)} — see {@link RenderUtil#gradientRawT}), so pin1 sits at
	 *  {@code center + (0.5-pos1)*size*(ux,uy)} and pin2 at the mirrored {@code (0.5-pos2)} point — at
	 *  pos1=0/pos2=1 (the defaults) they land exactly on opposite edges of the square in the direction the
	 *  angle dial points, and dragging a pin only ever moves it along that SAME line (see {@link
	 *  #applyGradientPinDrag}), which is what makes it naturally "movable only inward/toward the other pin":
	 *  {@link com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature#setCustomPin1Pos}/{@code
	 *  setCustomPin2Pos} already clamp pos to [0,1] and refuse to let the pins cross, so there's no separate
	 *  clamp to write here. RADIAL mode has no real "direction" (a feathered circle looks the same from every
	 *  angle) so its pins use a fixed reference axis (straight up) purely to give pos1/pos2 (inner/outer
	 *  radius fraction there) somewhere consistent to sit. Clicking a pin selects it (for the hex-capable
	 *  picker below, per the original spec's "hex-popup pickers on each end") and starts dragging it in the
	 *  same gesture — see {@link #applyGradientPinDrag}'s own doc comment for why there's no more
	 *  click-nearest-pin snapping. Returns the Y position after everything this method drew — see {@link
	 *  #gradientPinEditorHeight} for the matching height calc. */
	private int drawGradientPinEditor(GuiGraphicsExtractor graphics, int x, int y, int width, int accent,
									   com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf) {
		int c1 = ptf.getCustomPin1Argb(), c2 = ptf.getCustomPin2Argb();
		float pos1 = ptf.getCustomPin1Pos(), pos2 = ptf.getCustomPin2Pos();
		Theme.GradientMode mode = ptf.getCustomGradientMode();
		float angle = ptf.getCustomGradientAngle();
		int squareSize = GRADIENT_SQUARE_SIZE;

		RenderUtil.fillPanelGradient(graphics, x, y, x + squareSize, y + squareSize, SMALL_RADIUS, c1, c2, pos1, pos2, mode, angle);
		graphics.fill(x - 1, y - 1, x + squareSize + 1, y, accent);
		graphics.fill(x - 1, y + squareSize, x + squareSize + 1, y + squareSize + 1, accent);
		graphics.fill(x - 1, y - 1, x, y + squareSize + 1, accent);
		graphics.fill(x + squareSize, y - 1, x + squareSize + 1, y + squareSize + 1, accent);

		int rightX = x + squareSize + 14;
		if (mode == Theme.GradientMode.LINEAR) {
			int dialD = Math.min(squareSize, 56);
			gradientDialCx = rightX + dialD / 2;
			gradientDialCy = y + dialD / 2;
			gradientDialR = dialD / 2;
			gradientDialActiveThisFrame = true;
			fillCircle(graphics, gradientDialCx, gradientDialCy, gradientDialR, Theme.chrome(0xFF2A2A2A));
			double rad = Math.toRadians(angle);
			float hx = (float) Math.sin(rad), hy = (float) -Math.cos(rad);
			float handLen = gradientDialR - 5;
			RenderUtil.drawLine(graphics, gradientDialCx, gradientDialCy, gradientDialCx + hx * handLen, gradientDialCy + hy * handLen, 2f, accent);
			fillCircle(graphics, gradientDialCx, gradientDialCy, 3, accent);
			SmoothTextRenderer.draw(graphics, this.font, Math.round(angle) + "°", rightX, y + dialD + 4, Theme.text(0xFFAAAAAA));
		} else {
			gradientDialActiveThisFrame = false;
			SmoothTextRenderer.draw(graphics, this.font, "Feathered", rightX, y, Theme.text(0xFFAAAAAA));
			SmoothTextRenderer.draw(graphics, this.font, "Circle", rightX, y + 10, Theme.text(0xFFAAAAAA));
		}

		// Real direction vector — RADIAL has no meaningful angle of its own, so its pins sit along a fixed
		// "straight up" reference axis purely to give pos1/pos2 (inner/outer radius fraction there) a place
		// to live; LINEAR uses the real live angle, identical to what the gradient itself renders with.
		double rad = Math.toRadians(mode == Theme.GradientMode.RADIAL ? 0f : angle);
		gradientPinAxisX = (float) Math.sin(rad);
		gradientPinAxisY = (float) -Math.cos(rad);
		gradientPinCx = x + squareSize / 2;
		gradientPinCy = y + squareSize / 2;
		gradientPinActiveThisFrame = true;
		gradientPin1X = Math.round(gradientPinCx + gradientPinAxisX * (0.5f - pos1) * squareSize);
		gradientPin1Y = Math.round(gradientPinCy + gradientPinAxisY * (0.5f - pos1) * squareSize);
		gradientPin2X = Math.round(gradientPinCx + gradientPinAxisX * (0.5f - pos2) * squareSize);
		gradientPin2Y = Math.round(gradientPinCy + gradientPinAxisY * (0.5f - pos2) * squareSize);
		drawGradientPin(graphics, gradientPin1X, gradientPin1Y, c1, selectedGradientPin == 1);
		drawGradientPin(graphics, gradientPin2X, gradientPin2Y, c2, selectedGradientPin == 2);

		int rowY = y + squareSize + 10;
		Theme.GradientMode capturedMode = mode;
		rowY = drawCycleRow(graphics, x, rowY, width, "Gradient Mode", capturedMode == Theme.GradientMode.RADIAL ? "Radial" : "Linear",
			() -> { ptf.setCustomGradientMode(capturedMode == Theme.GradientMode.LINEAR ? Theme.GradientMode.RADIAL : Theme.GradientMode.LINEAR); ConfigManager.save(); });

		rowY += 4;
		String pinLabel = selectedGradientPin == 1 ? "Pin 1 Color" : "Pin 2 Color";
		colorPickerLabel(graphics, x, rowY, pinLabel);
		int pickerY = rowY + 10;
		if (selectedGradientPin == 1) {
			return drawFullColorPicker(graphics, x, pickerY, width, "panel_theme_pin1", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
				ptf::getCustomPin1Argb, c -> { ptf.setCustomPin1Argb(c); ConfigManager.save(); }, 0xFF202020);
		}
		return drawFullColorPicker(graphics, x, pickerY, width, "panel_theme_pin2", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
			ptf::getCustomPin2Argb, c -> { ptf.setCustomPin2Argb(c); ConfigManager.save(); }, 0xFF202020);
	}

	// Real pushpin texture (GRADIENT_PIN_TEXTURE, cropped/resized from the user-supplied art — see its
	// javadoc above) replaces the old procedural circle+tail marker. The source art points tip-toward-
	// bottom-left; a pushpin's tip is the point that conceptually "marks" a location, so (cx, cy) — the
	// pin's actual position on the gradient axis — is anchored to the TIP, not the image's top-left corner
	// or its center. PIN_TIP_U/V below are the tip's measured fractional position within the (square,
	// cropped-tight) source image; a fixed on-screen angle is fine since real pushpin icons in most UIs are
	// drawn at one constant angle regardless of context.
	private static final float PIN_TIP_U = 0.02f;
	private static final float PIN_TIP_V = 0.99f;
	// Per user request ("make the pins a bit smaller, like 2/3rds the size it currently is"): both sizes
	// scaled down together so the selected pin still reads as slightly larger than the unselected one.
	private static final int GRADIENT_PIN_SIZE = 11;
	private static final int GRADIENT_PIN_SIZE_SELECTED = 13;

	/** Real on-screen rect a pin icon occupies for a given anchor, same drawX/drawY math
	 *  {@link #drawGradientPin} itself uses (at the unselected size, since hit-testing runs before selection
	 *  state is known) plus a couple pixels of forgiveness — see the mouseClicked call site's own doc comment
	 *  for the bug this replaced (a small hit-radius centered on the tip anchor, covering almost none of the
	 *  actual visible icon). */
	private boolean gradientPinIconContains(int cx, int cy, double mx, double my) {
		int size = GRADIENT_PIN_SIZE;
		int drawX = Math.round(cx - size * PIN_TIP_U);
		int drawY = Math.round(cy - size * PIN_TIP_V);
		int pad = 2;
		return mx >= drawX - pad && mx <= drawX + size + pad && my >= drawY - pad && my <= drawY + size + pad;
	}

	private void drawGradientPin(GuiGraphicsExtractor graphics, int cx, int cy, int color, boolean selected) {
		// Per user request ("Remove the outline honestly, the mod doesnt really need to show which one is
		// selected"): the selection ring (itself a fix for an earlier "full circle glow" report) is gone
		// outright — the slightly-larger selected size below is still enough of a cue on its own.
		int size = selected ? GRADIENT_PIN_SIZE_SELECTED : GRADIENT_PIN_SIZE;
		int drawX = Math.round(cx - size * PIN_TIP_U);
		int drawY = Math.round(cy - size * PIN_TIP_V);
		// Tint via blit's color-multiply, same convention as drawIcon()/COG_TEXTURE/MAGNIFIER_TEXTURE: the
		// pin's assigned gradient-stop color is what conveys "which color stop is this", replacing the old
		// solid-fill head color.
		drawIcon(graphics, GRADIENT_PIN_TEXTURE, drawX, drawY, size, 0xFF000000 | (color & 0xFFFFFF));
	}

	/** Height {@link #drawGradientPinEditor} above actually consumes — mirrors it row-for-row, same
	 *  "compute height and draw content in two separately-maintained places" pattern every other feature's
	 *  settings panel in this file already uses (e.g. {@link #expandedSoundOptionHeight}). Deliberately the
	 *  SAME total regardless of Linear/Radial mode — the angle dial/"Feathered Circle" label and the pins
	 *  themselves sit ON/beside the square preview, not below it, so switching modes never changes how much
	 *  vertical space this consumes. */
	private static int gradientPinEditorHeight() {
		return GRADIENT_SQUARE_SIZE + 10 + 18 + 4 + 10 + COLOR_CONTENT_HEIGHT;
	}

	private static void cyclePanelTheme(com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf) {
		com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature.Mode[] modes =
			com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature.Mode.values();
		ptf.setMode(modes[(ptf.getMode().ordinal() + 1) % modes.length]);
	}

	private static void cycleCatacombsFloor(com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature cef) {
		com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature.Floor[] floors =
			com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature.Floor.values();
		cef.setFloor(floors[(cef.getFloor().ordinal() + 1) % floors.length]);
	}

	/** One auto-detected bonus's checkmark line for the Catacombs Experience Calculator. Per user request
	 *  ("Checkmarks should be green and Xs should be red"), the mark's color is ALWAYS green/red by active
	 *  state, independent of the label's own rarity/mayor color (labelColor) — the two are drawn as separate
	 *  colored runs, not one shared-color string like the mod's earlier version had. */
	private int drawCatacombsStatusLine(GuiGraphicsExtractor graphics, int x, int y, String label, int labelColor, boolean active) {
		String prefix = label + ": ";
		SmoothTextRenderer.draw(graphics, this.font, prefix, x, y, Theme.text(labelColor));
		String mark = active ? "✓" : "✗";
		int markColor = active ? 0xFF55FF55 : 0xFFFF5555;
		SmoothTextRenderer.draw(graphics, this.font, mark, x + this.font.width(prefix), y, Theme.text(markColor));
		return y + 14;
	}

	/** Same toggle-row shape as {@link #drawToggleRow}, but with a caller-chosen label color instead of the
	 *  generic gray every other toggle row hardcodes — needed for the Catacombs Expert Ring toggle, whose label
	 *  should read in Hypixel's own Epic rarity color like every other auto-detected bonus on that panel. */
	private int drawColoredToggleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String animKey, String label, int labelColor, boolean value, Runnable onToggle) {
		SmoothTextRenderer.draw(graphics, this.font, label, x, y, Theme.text(labelColor));
		int toggleW = 28, toggleH = 12;
		int tx = x + width - toggleW;
		int ty = y - 1;
		float t = toggleAnimValue(animKey, value);
		RenderUtil.fillPill(graphics, tx, ty, tx + toggleW, ty + toggleH, FULL_ROUND, RenderUtil.lerpColor(colorToggleOff(tx, ty, toggleW, toggleH), colorToggleOn(), t));
		int knob = toggleH - 4;
		int kx = tx + 2 + Math.round(t * (toggleW - knob - 4));
		fillCircle(graphics, kx + knob / 2, ty + 2 + knob / 2, knob / 2, RenderUtil.lerpColor(colorKnobOff(), COLOR_KNOB_ON, t));
		clickHits.add(new ClickHit(tx, ty, toggleW, toggleH, () -> { playToggleSound(!value); onToggle.run(); }));
		return y + 18;
	}

	private static String leapSortModeLabel(com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature.SortMode mode) {
		return switch (mode) {
			case ODIN -> "Class Priority";
			case ALPHABETICAL_CLASS -> "Class (A-Z)";
			case ALPHABETICAL_NAME -> "Username (A-Z)";
			case NONE -> "None";
		};
	}

	private static void cycleLeapSortMode(com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature lmf) {
		com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature.SortMode[] modes = com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature.SortMode.values();
		lmf.setSortMode(modes[(lmf.getSortMode().ordinal() + 1) % modes.length]);
	}

	private static String storageOverlayInventoryCornerLabel(com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature.InventoryCorner corner) {
		return switch (corner) {
			case BOTTOM_RIGHT -> "Bottom Right";
			case BOTTOM_LEFT -> "Bottom Left";
			case TOP_RIGHT -> "Top Right";
			case TOP_LEFT -> "Top Left";
			case BOTTOM_CENTER -> "Bottom Center";
			case TOP_CENTER -> "Top Center";
		};
	}

	private static void cycleStorageOverlayInventoryCorner(com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature sof) {
		com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature.InventoryCorner[] corners =
			com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature.InventoryCorner.values();
		sof.setInventoryCorner(corners[(sof.getInventoryCorner().ordinal() + 1) % corners.length]);
	}

	private static String showNamesModeLabel(com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature.ShowNamesMode mode) {
		return switch (mode) {
			case OFF -> "Off";
			case HOLDING_LEAP -> "Holding Spirit Leap";
			case ALWAYS -> "Always";
		};
	}

	private static void cycleShowNamesMode(com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature dmf) {
		var modes = com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature.ShowNamesMode.values();
		dmf.setShowNamesMode(modes[(dmf.getShowNamesMode().ordinal() + 1) % modes.length]);
	}

	private static String roomLabelModeLabel(com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature.RoomLabelMode mode) {
		return switch (mode) {
			case SECRETS -> "Secret Count";
			case NAME -> "Room Name";
			case CHECKMARK -> "Checkmark";
		};
	}

	private static String goldorLoopStyleLabel(com.cokelord.skyblocksimplified.feature.impl.DungeonTimersFeature.GoldorLoopStyle style) {
		return switch (style) {
			case COUNTDOWN -> "Countdown";
			case NOTIFICATION -> "Notification";
		};
	}

	private static void cycleRoomLabelMode(com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature dmf) {
		var modes = com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature.RoomLabelMode.values();
		dmf.setRoomLabelMode(modes[(dmf.getRoomLabelMode().ordinal() + 1) % modes.length]);
	}

	/** Shared cycle-row label/advance pair for the puzzle solvers' WorldRenderUtil.RenderStyle setting —
	 *  every solver that has one (Beams/Blaze/Boulder/Weirdos) uses the exact same 3-way enum. */
	private static String renderStyleLabel(com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.RenderStyle style) {
		return switch (style) {
			case OUTLINE -> "Outline";
			case FILLED -> "Filled";
			case FILLED_OUTLINE -> "Filled + Outline";
		};
	}

	private static com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.RenderStyle nextRenderStyle(
			com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.RenderStyle style) {
		var values = com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.RenderStyle.values();
		return values[(style.ordinal() + 1) % values.length];
	}

	private static String slotBindsLineModeLabel(com.cokelord.skyblocksimplified.feature.impl.SlotBindsFeature.LineDisplayMode mode) {
		return switch (mode) {
			case HOVER -> "On Hover";
			case HOVER_SHIFT -> "Hover + Shift";
			case NONE -> "Never";
		};
	}

	private static void cycleSlotBindsLineMode(com.cokelord.skyblocksimplified.feature.impl.SlotBindsFeature sbf) {
		var modes = com.cokelord.skyblocksimplified.feature.impl.SlotBindsFeature.LineDisplayMode.values();
		sbf.setLineDisplayMode(modes[(sbf.getLineDisplayMode().ordinal() + 1) % modes.length]);
	}

	private static String etherwarpStyleLabel(com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.RenderStyle style) {
		return switch (style) {
			case OUTLINE -> "Outline";
			case FILLED -> "Filled";
			case FILLED_OUTLINE -> "Filled + Outline";
		};
	}

	private static void cycleEtherwarpStyle(com.cokelord.skyblocksimplified.feature.impl.EtherwarpFeature ewf) {
		var styles = com.cokelord.skyblocksimplified.highlight.WorldRenderUtil.RenderStyle.values();
		ewf.setStyle(styles[(ewf.getStyle().ordinal() + 1) % styles.length]);
	}

	private final Map<String, Anim> toggleAnims = new HashMap<>();
	private final Map<String, Boolean> toggleAnimWasEnabled = new HashMap<>();

	/** Animated on/off fraction for a toggle switch, keyed the same stable-string way as sliders (see
	 *  animatedSliderValue) so it survives scrolling — drives both the knob's slide and a background
	 *  color cross-fade instead of an instant flip, in both directions.
	 *
	 *  Real bug found (per user report — "the slider has no animation when turning off, only works turning
	 *  on"): a toggle whose OWN state gates whether animations play at all (the "Slider Animation" row
	 *  itself, and GUI Animations' own master switch via Anim.setGlobalInstant) always read the flag AFTER
	 *  it had already flipped by the time this rendered — its own off transition self-consistently computed
	 *  a zero duration (animations ARE now disabled, post-click) so that one specific transition could never
	 *  actually animate, while its on transition always read the flag as already true and animated fine.
	 *  Remembering whether animating was allowed a moment ago — and only letting that memory catch up to the
	 *  live flag once the Anim is fully settled again, so an in-flight transition can't have its duration
	 *  pulled out from under it mid-flight — covers exactly that one-frame gap without changing behavior for
	 *  any ordinary toggle whose own state doesn't move the flag it's gated by. */
	private float toggleAnimValue(String key, boolean value) {
		GuiAnimationsFeature gaf = animationsFeature();
		boolean liveCanAnimate = (gaf == null || gaf.isSliderAnimationEnabled()) && !Anim.isGlobalInstant();
		Anim anim = toggleAnims.computeIfAbsent(key, k -> new Anim(value ? 1f : 0f, 0.2f));
		boolean wasCanAnimate = toggleAnimWasEnabled.getOrDefault(key, liveCanAnimate);
		boolean effective = liveCanAnimate || wasCanAnimate;
		anim.setTarget(value ? 1f : 0f);
		anim.setDurationSeconds(effective ? 0.2f : 0f);
		anim.updateIgnoringGlobalInstant(currentFrameDt);
		if (anim.get() == anim.getTarget()) toggleAnimWasEnabled.put(key, liveCanAnimate);
		return anim.get();
	}

	/** Per original spec ("Turning a module/submodule on should play the sound for the wooden button
	 *  clicking on, and turning one off should play the turning off version"): real vanilla wooden-button
	 *  click sounds, distinct for on vs. off, instead of the generic (and previously identical either way)
	 *  lever click. {@code turningOn} is the NEW state after the toggle, not the state before it — every
	 *  call site below passes whichever is correct for its own click handler's ordering. */
	private static void playToggleSound(boolean turningOn) {
		if (!com.cokelord.skyblocksimplified.feature.impl.UiSoundEffectsFeature.isSoundEnabled()) return;
		// Real bug found (per user report — "all the sounds are really low volume"): SimpleSoundInstance's
		// 2-arg forUI(sound, pitch) overload hardcodes volume to 0.25f internally (confirmed by decompiling
		// the vanilla class) — quiet-by-design for incidental UI blips, not for a deliberate button-press
		// sound effect the user actually wants to hear. Using the 3-arg forUI(sound, pitch, volume) overload
		// with a real 1.0f volume everywhere in this file fixes all of them at once.
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
			turningOn ? SoundEvents.WOODEN_BUTTON_CLICK_ON : SoundEvents.WOODEN_BUTTON_CLICK_OFF, 1f, 1f));
	}

	/** Generic UI click feedback for navigation actions that aren't a module on/off toggle — category tabs,
	 *  subcategory tiles, the search magnifier, and the settings-cog open/close — using vanilla's own
	 *  general-purpose button click sound so it reads as "menu navigation" rather than "flipped a switch". */
	private static void playClickSound() {
		if (!com.cokelord.skyblocksimplified.feature.impl.UiSoundEffectsFeature.isSoundEnabled()) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1f));
	}

	/** Per original spec: opening a module's settings through its cog plays the iron trapdoor OPEN sound,
	 *  closing it plays the CLOSE sound — distinct from the generic navigation click above. */
	private static void playCogSound(boolean opening) {
		if (!com.cokelord.skyblocksimplified.feature.impl.UiSoundEffectsFeature.isSoundEnabled()) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
			opening ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, 1f, 1f));
	}

	/** Shows the actual swing duration the slider produces (per ItemAnimationsFeature's absolute-duration
	 *  spec), not a raw 0.1-1.0 number that wouldn't mean anything to look at on its own. */
	private static String swingSpeedLabel(float speed) {
		float t = (speed - com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MIN_SWING_SPEED)
			/ (com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MAX_SWING_SPEED - com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature.MIN_SWING_SPEED);
		float durationSeconds = 10f * (1f - t);
		return durationSeconds <= 0.05f ? "Instant" : String.format(java.util.Locale.ROOT, "%.1fs", durationSeconds);
	}

	private int drawSliderRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, String valueText,
							   float value01, Consumer<Float> onChange, int accent) {
		return drawSliderRow(graphics, x, y, width, label, valueText, value01, onChange, accent, null);
	}

	/** Tooltip-aware overload — see {@link #drawToggleRow(GuiGraphicsExtractor, int, int, int, String, boolean, Runnable, String)}'s doc comment. */
	private int drawSliderRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, String valueText,
							   float value01, Consumer<Float> onChange, int accent, String tooltip) {
		SmoothTextRenderer.draw(graphics, this.font, label, x, y, Theme.text(0xFFAAAAAA));
		// Real bug found (per user report — "no gap between the columns and the slider"): the slider used to
		// start only 11px below the label, tight enough that SmoothTextRenderer's own taller glyph metrics
		// (the 15% HEIGHT_BOOST added for legibility) visually run straight into the bar with no breathing
		// room. Also centers the numeric readout within its own reserved column (see this class's own
		// "center every value in its box" pass) instead of just anchoring it at a fixed left edge.
		int valueColumnX = x + width - 50;
		int valueWidth = Math.max(50, this.font.width(valueText) + 4);
		SmoothTextRenderer.draw(graphics, this.font, valueText, valueColumnX + Math.max(0, (valueWidth - this.font.width(valueText)) / 2), y, Theme.text(0xFF888888));
		int sliderY = y + 14;
		int sliderWidth = width - 60;
		drawHorizontalSlider(graphics, label, x, sliderY, sliderWidth, 5, value01, accent);
		sliderHits.add(new SliderHit(x, sliderY - 4, sliderWidth, 12, false, onChange, label));
		if (tooltip != null) checkHoverTooltip(lastMouseX, lastMouseY, x, y - 2, width, 23, "slider_" + label, tooltip);
		return y + 23;
	}

	private int drawCycleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, String valueText, Runnable onClick) {
		return drawCycleRow(graphics, x, y, width, label, valueText, onClick, null, null);
	}

	private int drawCycleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, String valueText, Runnable onClick, String tooltip) {
		return drawCycleRow(graphics, x, y, width, label, valueText, onClick, null, tooltip);
	}

	/** Per user request ("Allow users to play the sound they have selected in all sound selectors throughout
	 *  the mod"): every sound-choice cycle row in the mod (Terminal Sounds' click/complete, Etherwarp's
	 *  success sound, Arrow Hit Sound, Beams' hit sound, Dungeons Copilot's title-update sound) now passes a
	 *  non-null onPlay, which draws a small "▶" preview button immediately left of the cycle box — clicking
	 *  it plays back whatever the box currently reads, with no need to actually trigger the real in-game
	 *  moment the sound is meant for just to hear it. onPlay is intentionally independent of onClick (which
	 *  still only cycles the selection) and of the feature's own enabled state — every call site's onPlay
	 *  plays the sound unconditionally, the same way a "preview" button should regardless of whether the
	 *  feature/subtoggle that would normally trigger it is currently on. */
	private int drawCycleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, String valueText, Runnable onClick, Runnable onPlay) {
		return drawCycleRow(graphics, x, y, width, label, valueText, onClick, onPlay, null);
	}

	/** Tooltip-aware master implementation — see {@link #drawToggleRow(GuiGraphicsExtractor, int, int, int, String, boolean, Runnable, String)}'s doc comment. */
	private int drawCycleRow(GuiGraphicsExtractor graphics, int x, int y, int width, String label, String valueText, Runnable onClick, Runnable onPlay, String tooltip) {
		SmoothTextRenderer.draw(graphics, this.font, label, x, y, Theme.text(0xFFAAAAAA));
		// Real bug found (per user report — "the holding spirit leap text when cycling goes off the little
		// box it uses"): this box used to be a flat 90px regardless of what valueText actually needed, which
		// overflowed for anything longer than that (e.g. "Holding Spirit Leap"). Sized to the real text now,
		// with 90 kept only as a floor so short values (existing call sites) don't shrink the row.
		int boxW = Math.max(90, this.font.width(valueText) + 12), boxH = 14;
		int bx = x + width - boxW;
		int by = y - 2;
		if (onPlay != null) {
			int playW = 16;
			int playX = bx - 4 - playW;
			fillRounded(graphics, playX, by, playX + playW, by + boxH, BOX_RADIUS, Theme.chrome(0xFF2A2A2A));
			SmoothTextRenderer.draw(graphics, this.font, "▶", playX + (playW - this.font.width("▶")) / 2, by + 3, 0xFFAAFFAA);
			clickHits.add(new ClickHit(playX, by, playW, boxH, onPlay));
		}
		fillRounded(graphics, bx, by, bx + boxW, by + boxH, BOX_RADIUS, Theme.chrome(0xFF2A2A2A));
		// Real bug found (per user report — "the size indicator is placed very weird, should be centered
		// within the box"): valueText used to always sit flush against the box's left edge instead of
		// centered — visible whenever boxW's own text-plus-12px sizing left slack (the exact-fit case masked
		// it). Centered both axes now, matching this class's own "every value centers in its box" pass.
		int textX = bx + Math.max(0, (boxW - this.font.width(valueText)) / 2);
		SmoothTextRenderer.draw(graphics, this.font, valueText, textX, by + 3, Theme.text(0xFFDDDDDD));
		clickHits.add(new ClickHit(bx, by, boxW, boxH, onClick));
		if (tooltip != null) checkHoverTooltip(lastMouseX, lastMouseY, x, y - 2, width, 20, "cycle_" + label, tooltip);
		return y + 20;
	}

	/** Per user request ("Add sound importing to all sound modules and add a toggle for removing deadspace
	 *  at the start. All sound modules should also have volume and pitch sliders from 0-2, defaulted at 1"):
	 *  one shared settings block for every {@link com.cokelord.skyblocksimplified.sound.CustomSoundOption}
	 *  in the mod, so each sound-picking feature's panel only needs a single call instead of duplicating this
	 *  whole layout. Mirrors {@link #expandedSoundOptionHeight} row-for-row — keep both in sync. */
	private int drawSoundOptionRows(GuiGraphicsExtractor graphics, int rowX, int rowY, int rowWidth, int accent,
									 com.cokelord.skyblocksimplified.sound.CustomSoundOption sound) {
		if (!sound.isUseCustom()) {
			rowY = drawCycleRow(graphics, rowX, rowY, rowWidth, "Sound", sound.getBuiltinLabel(),
				() -> { sound.cycleBuiltin(); ConfigManager.save(); }, sound::play);
		}
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Use Custom Sound (.wav .mp3 .ogg)",
			sound.isUseCustom(), () -> { sound.setUseCustom(!sound.isUseCustom()); ConfigManager.save(); },
			"Plays your own imported sound file instead of one of the built-in sounds above.");
		if (sound.isUseCustom()) {
			rowY += 4;
			int btnHeight = 18;
			// Every format javax.sound.sampled can decode with this project's bundled mp3spi/vorbisspi SPI
			// providers (see CustomSoundOption's own class doc comment) — .wav, .mp3, .ogg, .aiff/.aif, and
			// .au all work.
			String importLabel = sound.getCustomFilePath() != null ? "Re-import Sound" : "Import Sound";
			fillRounded(graphics, rowX, rowY, rowX + rowWidth, rowY + btnHeight, BOX_RADIUS, Theme.chrome(0xFF2A2A2A));
			SmoothTextRenderer.draw(graphics, this.font, importLabel, rowX + (rowWidth - this.font.width(importLabel)) / 2, rowY + 5, Theme.text(0xFFFFFFFF));
			int importY = rowY;
			clickHits.add(new ClickHit(rowX, importY, rowWidth, btnHeight, () -> {
				String picked;
				try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
					org.lwjgl.PointerBuffer filters = stack.pointers(
						stack.UTF8("*.wav"), stack.UTF8("*.mp3"), stack.UTF8("*.ogg"),
						stack.UTF8("*.aiff"), stack.UTF8("*.aif"), stack.UTF8("*.au"));
					picked = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
						"Select a sound", "", filters, "Audio files (.wav, .mp3, .ogg, .aiff, .au)", false);
				}
				if (picked != null) {
					sound.setCustomFilePath(picked);
					ConfigManager.save();
				}
			}));
			rowY += btnHeight + 4;
			if (sound.getCustomFilePath() != null) {
				String fileName = new java.io.File(sound.getCustomFilePath()).getName();
				SmoothTextRenderer.draw(graphics, this.font, fileName, rowX, rowY, Theme.text(0xFF888888));
				rowY += 12;
				// Per user request ("Replace the 'Preview sound' button with a 'Stop playing' button when
				// the sound is active in the mod menu"): same button, swaps label/action based on whether
				// THIS option's own custom clip is currently playing.
				boolean playing = sound.isPlaying();
				String previewLabel = playing ? "Stop" : "Preview";
				int previewWidth = 60;
				fillRounded(graphics, rowX, rowY, rowX + previewWidth, rowY + btnHeight, BOX_RADIUS, playing ? 0xFFAA3333 : accent);
				SmoothTextRenderer.draw(graphics, this.font, previewLabel, rowX + (previewWidth - this.font.width(previewLabel)) / 2, rowY + 5, 0xFFFFFFFF);
				int previewY = rowY;
				clickHits.add(new ClickHit(rowX, previewY, previewWidth, btnHeight, playing ? sound::stop : sound::play));
				rowY += btnHeight + 4;
			}
			rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "Remove Deadspace",
				sound.isRemoveDeadspace(), () -> { sound.setRemoveDeadspace(!sound.isRemoveDeadspace()); ConfigManager.save(); },
				"Trims silence from the start of your imported sound file so it plays immediately instead of after a delay.");
		}
		rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Volume", String.format(Locale.ROOT, "%.2f", sound.getVolume()),
			sound.getVolume() / 2f, v -> { sound.setVolume(v * 2f); ConfigManager.save(); }, accent);
		rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Pitch", String.format(Locale.ROOT, "%.2f", sound.getPitch()),
			sound.getPitch() / 2f, v -> { sound.setPitch(v * 2f); ConfigManager.save(); }, accent);
		return rowY;
	}

	/** Total height {@link #drawSoundOptionRows} will occupy for the given option's current state — kept in
	 *  sync with that method row-for-row so panels sizing around it don't clip or leave a gap. */
	private static int expandedSoundOptionHeight(com.cokelord.skyblocksimplified.sound.CustomSoundOption sound) {
		int height = sound.isUseCustom() ? 0 : 20;
		height += 18; // Use Custom Sound toggle
		if (sound.isUseCustom()) {
			height += 4 + 18 + 4; // gap + import button + gap
			if (sound.getCustomFilePath() != null) height += 12 + 18 + 4; // filename + preview button + gap
			height += 18; // Remove Deadspace toggle
		}
		height += 23 + 23; // volume + pitch sliders
		return height;
	}

	private static final int HEX_FIELD_HEIGHT = 16;

	/** A compact "#RRGGBB" text field, reusable for any color a feature exposes (title/footer text
	 *  color, background/outline colors, GuiColorFeature's own swatch) — identified by a stable string
	 *  key instead of a dedicated field per use site, since several can now be on screen at once. */
	private int drawHexPicker(GuiGraphicsExtractor graphics, int x, int y, int width, String key, int currentColor, Consumer<Integer> setColor) {
		boolean focused = textFocus == TextFocus.HEX && key.equals(focusedHexKey);
		String hex = focused ? hexFieldBuffer : String.format(Locale.ROOT, "%06X", currentColor & 0xFFFFFF);
		fillRounded(graphics, x, y, x + width, y + HEX_FIELD_HEIGHT, BOX_RADIUS, focused ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		String display = "#" + hex;
		graphics.text(this.font, display, x + 6, y + 4, Theme.text(0xFFDDDDDD));
		if (focused && isCaretBlinkOn()) {
			int caretX = x + 6 + this.font.width(display) + 1;
			graphics.fill(caretX, y + 3, caretX + 1, y + HEX_FIELD_HEIGHT - 3, 0xFFFFFFFF);
		}
		// Color preview: a quick visual check that the hex value looks right, without hunting for the
		// color elsewhere on screen.
		int previewR = 5;
		int previewCx = x + width - previewR - 4;
		int previewCy = y + HEX_FIELD_HEIGHT / 2;
		fillCircle(graphics, previewCx, previewCy, previewR, 0xFF000000 | (currentColor & 0xFFFFFF));
		hexFieldBounds.put(key, new int[]{x, y, x + width, y + HEX_FIELD_HEIGHT});
		hexFieldColors.put(key, currentColor);
		hexFieldSetters.put(key, setColor);
		return y + HEX_FIELD_HEIGHT + 4;
	}

	/** Same field, but with a label drawn above it instead of consuming vertical space via drawSliderRow's layout. */
	private int drawLabeledHexPicker(GuiGraphicsExtractor graphics, int x, int y, int width, String label, String key, int currentColor, Consumer<Integer> setColor) {
		SmoothTextRenderer.draw(graphics, this.font, label, x, y, Theme.text(0xFFAAAAAA));
		return drawHexPicker(graphics, x, y + 11, width, key, currentColor, setColor);
	}

	private void drawTextField(GuiGraphicsExtractor graphics, int x, int y, int width, int height, String text, boolean focused) {
		fillRounded(graphics, x, y, x + width, y + height, BOX_RADIUS, focused ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF262626));
		graphics.enableScissor(x, y, x + width, y + height);
		if (focused) drawSelectionHighlight(graphics, text, x + 4, y + 2, height - 4);
		SmoothTextRenderer.draw(graphics, this.font, text, x + 4, y + (height - 8) / 2, Theme.text(0xFFDDDDDD));
		if (focused && isCaretBlinkOn()) {
			int idx = Math.max(0, Math.min(textCursor, text.length()));
			int caretX = x + 4 + this.font.width(text.substring(0, idx)) + 1;
			graphics.fill(caretX, y + 2, caretX + 1, y + height - 2, 0xFFFFFFFF);
		}
		graphics.disableScissor();
	}

	/** Translucent highlight rectangle behind the current selection, if any — drawn before the text
	 *  itself so the letters stay legible on top of it. Shared by every SEARCH/TITLE/FOOTER/LINE_TEXT
	 *  field, since they all use the same textCursor/textSelectionAnchor state. */
	private void drawSelectionHighlight(GuiGraphicsExtractor graphics, String text, int textX, int y, int height) {
		if (!hasSelection()) return;
		int start = Math.max(0, Math.min(Math.min(textCursor, textSelectionAnchor), text.length()));
		int end = Math.max(0, Math.min(Math.max(textCursor, textSelectionAnchor), text.length()));
		int x0 = textX + this.font.width(text.substring(0, start));
		int x1 = textX + this.font.width(text.substring(0, end));
		graphics.fill(x0, y, x1, y + height, 0x668899FF);
	}

	private void drawAnimationsContent(GuiGraphicsExtractor graphics, GuiAnimationsFeature gaf, int x, int y, int width, int height, int accent) {
		String[] labels = {"Opening", "Closing", "Module opening", "Search bar", "Category switch", "Background blur"};
		float[] values = {gaf.getOpenDuration(), gaf.getCloseDuration(), gaf.getExpandDuration(), gaf.getSearchDuration(),
			gaf.getContentSwitchDuration(), gaf.getBlurAmount() / 10f};
		// The first five are raw seconds (0..MAX_DURATION), not an already-normalized 0..1 fraction — feeding
		// them straight into drawHorizontalSlider as value01 made the fill/knob only ever reach MAX_DURATION
		// (0.5) of the track's width, capping visually at 50% and growing the cursor/knob gap the further you
		// dragged (read as "stops at 0.5 seconds" and "gets slower the more you drag"). Blur is already 0..1
		// (divided by its own max of 10 above), so it's left as-is. Opening uses its own higher max
		// (MAX_OPEN_DURATION) — see that field's own doc comment.
		float[] fractions = {
			values[0] / GuiAnimationsFeature.MAX_OPEN_DURATION,
			values[1] / GuiAnimationsFeature.MAX_DURATION,
			values[2] / GuiAnimationsFeature.MAX_DURATION,
			values[3] / GuiAnimationsFeature.MAX_DURATION,
			values[4] / GuiAnimationsFeature.MAX_DURATION,
			values[5]
		};

		int rowY = y + 8;
		int sliderWidth = width - 110;
		int sliderHeight = 6;
		for (int i = 0; i < labels.length; i++) {
			boolean isBlur = i == 5;
			SmoothTextRenderer.draw(graphics, this.font, labels[i], x + 10, rowY, Theme.text(0xFFAAAAAA));
			String valueText = isBlur ? gaf.getBlurAmount() + "/10" : String.format(Locale.ROOT, "%.2f seconds", values[i]);
			SmoothTextRenderer.draw(graphics, this.font, valueText, x + width - 96, rowY, Theme.text(0xFF888888));

			int sliderX = x + 10;
			int sliderY = rowY + 12;
			drawHorizontalSlider(graphics, labels[i], sliderX, sliderY, sliderWidth, sliderHeight, fractions[i], accent);

			int index = i;
			sliderHits.add(new SliderHit(sliderX, sliderY - 4, sliderWidth, sliderHeight + 8, false,
				v -> applyAnimSetting(gaf, index, v), labels[i]));
			rowY += ANIM_ROW_HEIGHT;
		}

		rowY += 4;
		rowY = drawToggleRow(graphics, x + 10, rowY, width - 20, "Cog spin on hover",
			gaf.isCogSpinEnabled(), () -> { gaf.setCogSpinEnabled(!gaf.isCogSpinEnabled()); ConfigManager.save(); });
		rowY = drawToggleRow(graphics, x + 10, rowY, width - 20, "Slider animation",
			gaf.isSliderAnimationEnabled(), () -> { gaf.setSliderAnimationEnabled(!gaf.isSliderAnimationEnabled()); ConfigManager.save(); });
		drawToggleRow(graphics, x + 10, rowY, width - 20, "Smooth scrolling",
			gaf.isScrollAnimationEnabled(), () -> { gaf.setScrollAnimationEnabled(!gaf.isScrollAnimationEnabled()); ConfigManager.save(); });
	}

	private static void applyAnimSetting(GuiAnimationsFeature gaf, int index, float value01) {
		switch (index) {
			case 0 -> gaf.setOpenDuration(value01 * GuiAnimationsFeature.MAX_OPEN_DURATION);
			case 1 -> gaf.setCloseDuration(value01 * GuiAnimationsFeature.MAX_DURATION);
			case 2 -> gaf.setExpandDuration(value01 * GuiAnimationsFeature.MAX_DURATION);
			case 3 -> gaf.setSearchDuration(value01 * GuiAnimationsFeature.MAX_DURATION);
			case 4 -> gaf.setContentSwitchDuration(value01 * GuiAnimationsFeature.MAX_DURATION);
			case 5 -> gaf.setBlurAmount(Math.round(value01 * 10f));
			default -> {}
		}
		ConfigManager.save();
	}

	private static final int GUI_COLOR_DEFAULT = 0xFFCC3333;

	private void drawColorPickerContent(GuiGraphicsExtractor graphics, GuiColorFeature gcf, int x, int y, int width, int height, int accent, int mouseX, int mouseY) {
		int presetsY = drawAccentPresetsRow(graphics, gcf, x + 10, y, width - 20, mouseX, mouseY);
		colorPickerLabel(graphics, x + 10, presetsY, "Accent Color");
		int rowY = drawFullColorPicker(graphics, x + 10, presetsY + 10, width - 20, "gui_color", COLOR_SQUARE_SIZE, COLOR_SQUARE_CELLS, accent,
			gcf::getColor, c -> { gcf.setColor((gcf.getColor() & 0xFF000000) | (c & 0xFFFFFF)); ConfigManager.save(); }, GUI_COLOR_DEFAULT);
		rowY += 8;
		int rowX = x + 10;
		int rowWidth = width - 20;
		// Per user request: chroma cycles the accent color itself (Theme.accent()) rather than being its
		// own separate system — everything already reading Theme.accent() (every menu highlight/knob/
		// slider that uses the accent color) gets the chroma cycle for free with no per-feature change.
		rowY = drawToggleRow(graphics, rowX, rowY, rowWidth, "accent_chroma", "Chroma",
			gcf.isChromaEnabled(), () -> { gcf.setChromaEnabled(!gcf.isChromaEnabled()); ConfigManager.save(); }, null);
		if (gcf.isChromaEnabled()) {
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Chroma Saturation", Math.round(gcf.getChromaSaturation() * 100f) + "%",
				gcf.getChromaSaturation(), v -> { gcf.setChromaSaturation(v); ConfigManager.save(); }, accent);
			rowY = drawSliderRow(graphics, rowX, rowY, rowWidth, "Chroma Speed", String.format(Locale.ROOT, "%.1fx", gcf.getChromaSpeed()),
				(gcf.getChromaSpeed() - 0.1f) / 4.9f, v -> { gcf.setChromaSpeed(0.1f + v * 4.9f); ConfigManager.save(); }, accent);
		}
	}

	/**
	 * Full color-picker widget: draggable hue/saturation square, R/G/B/V vertical sliders, a reset
	 * button, and a hex text field below — everything GuiColorFeature's own swatch already had, now
	 * reusable for any color a feature exposes (Style tab's background/outline/title/footer colors).
	 * Multiple instances can be on screen in the same frame; only one square can be actively dragged at
	 * a time (tracked by key via draggingColorKey), and the shared pickerHue/pickerSat fields are
	 * authoritative only for whichever key is currently being dragged (avoids the hsv<->rgb round-trip
	 * flicker near hue 0/360).
	 */
	/** Small caption drawn above a color picker so it's clear what the color actually controls — every
	 *  drawFullColorPicker call site should have one of these (or an equivalent label) right before it. */
	private void colorPickerLabel(GuiGraphicsExtractor graphics, int x, int y, String label) {
		SmoothTextRenderer.draw(graphics, this.font, label, x, y, Theme.text(0xFFAAAAAA));
	}

	private int drawFullColorPicker(GuiGraphicsExtractor graphics, int squareX, int squareY, int width, String key,
									 int squareSize, int cellCount, int accent, java.util.function.IntSupplier getColor,
									 Consumer<Integer> setColor, int defaultColor) {
		int color = getColor.getAsInt();
		boolean draggingThis = key.equals(draggingColorKey);
		float[] hsv = draggingThis ? new float[]{pickerHue, pickerSat, rgbToHsv(color)[2]} : rgbToHsv(color);

		drawColorSquare(graphics, squareX, squareY, squareSize, cellCount, accent, hsv[0], hsv[1]);
		colorSquareHits.add(new ColorSquareHit(squareX, squareY, squareSize, key, getColor, setColor));

		// A bit more clearance than the square's own width so long labels drawn above this picker by the
		// caller (e.g. "Bottom color") don't run into the R slider's own label. Compact instances (the
		// Style tab's narrower columns) use tighter spacing so the reset button still fits without
		// overflowing the column.
		boolean compact = squareSize <= 50;
		int sliderAreaX = squareX + squareSize + (compact ? 14 : 24);
		int sliderTrackHeight = squareSize;
		String[] labels = {"R", "G", "B", "V"};
		int[] channelValues = {(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, Math.round(hsv[2] * 255)};
		int sliderWidth = 12;
		int sliderGap = compact ? 15 : 22;
		for (int i = 0; i < labels.length; i++) {
			int sliderX = sliderAreaX + i * sliderGap;
			SmoothTextRenderer.draw(graphics, this.font, labels[i], sliderX + (sliderWidth - this.font.width(labels[i])) / 2, squareY - 10, Theme.text(0xFFAAAAAA));
			float value01 = channelValues[i] / 255f;
			String sliderKey = key + "_" + labels[i];
			drawVerticalSlider(graphics, sliderKey, sliderX, squareY, sliderWidth, sliderTrackHeight, value01, accent);

			int channelIndex = i;
			sliderHits.add(new SliderHit(sliderX - 3, squareY - 3, sliderWidth + 6, sliderTrackHeight + 6,
				sliderX, squareY, sliderWidth, sliderTrackHeight, true,
				v -> applyColorChannel(getColor, setColor, channelIndex, v), sliderKey));
		}

		String resetLabel = "Reset";
		int resetHeight = 14;
		int resetWidth = this.font.width(resetLabel) + 8;
		int resetX = sliderAreaX + labels.length * sliderGap + 6;
		int resetY = squareY + sliderTrackHeight / 2 - resetHeight / 2;
		fillRounded(graphics, resetX, resetY, resetX + resetWidth, resetY + resetHeight, SMALL_RADIUS, Theme.chrome(0xFF2A2A2A));
		SmoothTextRenderer.draw(graphics, this.font, resetLabel, resetX + (resetWidth - this.font.width(resetLabel)) / 2, resetY + (resetHeight - 8) / 2, Theme.text(0xFFDDDDDD));
		clickHits.add(new ClickHit(resetX, resetY, resetWidth, resetHeight, () -> { setColor.accept(defaultColor); ConfigManager.save(); }));

		int hexY = drawHexPicker(graphics, squareX, squareY + squareSize + 12, width, key + "_hex", color, setColor);
		return hexY;
	}

	private void applyColorChannel(java.util.function.IntSupplier getColor, Consumer<Integer> setColor, int channelIndex, float value01) {
		int rgb = getColor.getAsInt();
		int newRgb;
		if (channelIndex == 3) { // V (value/brightness)
			float[] hsv = rgbToHsv(rgb);
			newRgb = hsvToRgb(hsv[0], hsv[1], clamp01(value01));
		} else {
			int channel = Math.round(clamp01(value01) * 255f);
			int shift = channelIndex == 0 ? 16 : channelIndex == 1 ? 8 : 0;
			int mask = ~(0xFF << shift);
			newRgb = (rgb & mask) | (channel << shift);
		}
		int alpha = rgb & 0xFF000000;
		setColor.accept(alpha | (newRgb & 0xFFFFFF));
		ConfigManager.save();
	}

	/** Draws a full-alpha-mask icon tinted to `color` via blit's multiply tint (the source PNG is a
	 *  white silhouette on transparent, so multiplying by an arbitrary accent color works correctly —
	 *  unlike tinting a solid-red source image, which would zero out any non-red channel). */
	private void drawIcon(GuiGraphicsExtractor graphics, net.minecraft.resources.Identifier texture, int x, int y, int size, int color) {
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, size, size, 64, 64, 64, 64, color);
	}

	// Real bug found (per user report — "Opening certain parts of the mod menu decreases fps a LOT. one
	// example is the wither highlight module, which decreases my fps from like 260 to about 50"): the
	// hue/saturation gradient square below used to redraw itself from scratch via a cellCount×cellCount
	// (24×24 = 576) nested loop of individual graphics.fill() calls PLUS 576 hsvToRgb() computations, EVERY
	// SINGLE FRAME, for every color picker on screen — Wither Highlight's settings panel shows 6 of these at
	// once (Highlight/Fill/Maxor/Storm/Goldor/Necron), so opening it means ~3,456 extra draw calls a frame
	// for content that's byte-for-byte IDENTICAL every time (the gradient itself depends only on cx/cy grid
	// position, never on the picker's own current color, key, or accent). Baked once into a real GPU texture
	// on first use and blitted thereafter — one draw call per picker instead of hundreds, and zero repeated
	// hsvToRgb work — the same fix category as this round's earlier Inventory Buttons FPS investigation.
	private static net.minecraft.resources.Identifier colorSquareGradientTextureId;

	private static net.minecraft.resources.Identifier colorSquareGradientTexture(int cellCount) {
		if (colorSquareGradientTextureId != null) return colorSquareGradientTextureId;
		com.mojang.blaze3d.platform.NativeImage image =
			new com.mojang.blaze3d.platform.NativeImage(com.mojang.blaze3d.platform.NativeImage.Format.RGBA, cellCount, cellCount, false);
		for (int cx = 0; cx < cellCount; cx++) {
			float cellHue = (cx + 0.5f) / cellCount * 360f;
			for (int cy = 0; cy < cellCount; cy++) {
				float cellSat = 1f - (cy + 0.5f) / cellCount;
				int rgb = hsvToRgb(cellHue, cellSat, 1f);
				int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
				image.setPixelABGR(cx, cy, (0xFF << 24) | (b << 16) | (g << 8) | r);
			}
		}
		net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath(
			"skyblocksimplified", "color_square_gradient");
		Minecraft.getInstance().getTextureManager().register(id,
			new net.minecraft.client.renderer.texture.DynamicTexture(() -> "sbs-color-square-gradient", image));
		colorSquareGradientTextureId = id;
		return id;
	}

	private void drawColorSquare(GuiGraphicsExtractor graphics, int x, int y, int size, int cellCount, int accent, float hue, float sat) {
		net.minecraft.resources.Identifier gradientId = colorSquareGradientTexture(cellCount);
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, gradientId, x, y, 0f, 0f,
			size, size, cellCount, cellCount, cellCount, cellCount, 0xFFFFFFFF);
		graphics.fill(x - 1, y - 1, x + size + 1, y, accent);
		graphics.fill(x - 1, y + size, x + size + 1, y + size + 1, accent);
		graphics.fill(x - 1, y - 1, x, y + size + 1, accent);
		graphics.fill(x + size, y - 1, x + size + 1, y + size + 1, accent);

		int markerX = x + Math.round(hue / 360f * size);
		int markerY = y + Math.round((1f - sat) * size);
		graphics.fill(markerX - 2, markerY - 2, markerX + 2, markerY + 2, 0xFF000000);
		graphics.fill(markerX - 1, markerY - 1, markerX + 1, markerY + 1, 0xFFFFFFFF);
	}

	private void drawHorizontalSlider(GuiGraphicsExtractor graphics, String key, int x, int y, int width, int height, float value01, int fillColor) {
		float animated = animatedSliderValue(key, clamp01(value01));
		fillRounded(graphics, x, y, x + width, y + height, height / 2, colorSliderTrack());
		int filledWidth = Math.round(width * animated);
		if (filledWidth > 0) {
			// Only the left edge is a true track boundary (always touching x); the right edge sits at
			// the knob, an internal/variable position, so it must stay flat or it looks like a broken notch.
			fillRounded(graphics, x, y, x + filledWidth, y + height, height / 2, fillColor, true, false, true, false);
		}
		int knobX = x + filledWidth;
		graphics.fill(knobX - 1, y - 2, knobX + 1, y + height + 2, 0xFFFFFFFF);
	}

	private void drawVerticalSlider(GuiGraphicsExtractor graphics, String key, int x, int y, int width, int height, float value01, int fillColor) {
		float animated = animatedSliderValue(key, clamp01(value01));
		fillRounded(graphics, x, y, x + width, y + height, width / 2, colorSliderTrack());
		int filledFromY = y + Math.round(height * (1f - animated));
		if (filledFromY < y + height) {
			// Only the bottom edge is a true track boundary (always touching y+height); the top edge
			// sits at the knob (the white line), an internal/variable position, so it must stay flat.
			fillRounded(graphics, x, filledFromY, x + width, y + height, width / 2, fillColor, false, false, true, true);
		}
		graphics.fill(x - 2, filledFromY - 1, x + width + 2, filledFromY + 1, 0xFFFFFFFF);
	}

	private final Map<String, Anim> sliderPosAnims = new HashMap<>();
	private float currentFrameDt = 0f;

	/** Smooths a slider's rendered knob/fill position toward its real value instead of snapping.
	 *  Keyed by an explicit caller-supplied string identity (a label, unique within the one settings
	 *  screen that can be open at a time) rather than screen position — position seemed like a natural
	 *  free identity at first (a given slider is drawn at the same spot frame to frame), but breaks the
	 *  moment the panel scrolls: every row's y shifts slightly each frame during the scroll's own
	 *  animation, so the "same" slider kept getting treated as a brand-new one and jumping/lagging.
	 *
	 *  While THIS slider is the one being actively dragged, the live target is returned directly instead
	 *  of going through the Anim chase at all (previously just zeroed the Anim's duration and still ran
	 *  it through update() — correct in theory, but any one frame where that path didn't fire exactly as
	 *  expected left the rendered knob a frame behind the cursor, which is what read as "sliders don't
	 *  track the mouse, they sit offset to the left" — dragging only ever moves a value up, so a lagging
	 *  knob is always behind, i.e. left, of both the cursor and the correct position). The underlying Anim
	 *  is still kept snapped to the live value throughout the drag so there's nothing to catch up on with
	 *  a visible jump-then-slide once the mouse is released and normal chasing resumes. */
	private final Map<String, Boolean> sliderAnimWasEnabled = new HashMap<>();

	private float animatedSliderValue(String key, float target) {
		GuiAnimationsFeature gaf = animationsFeature();
		boolean liveCanAnimate = (gaf == null || gaf.isSliderAnimationEnabled()) && !Anim.isGlobalInstant();
		boolean isBeingDragged = activeSlider != null && key.equals(activeSlider.key);
		if (isBeingDragged) {
			Anim dragged = sliderPosAnims.get(key);
			if (dragged != null) dragged.snapTo(target);
			return target;
		}
		Anim anim = sliderPosAnims.computeIfAbsent(key, k -> new Anim(target, 0.3f));
		// Same "remember whether animating was allowed a moment ago" grace as toggleAnimValue (see its own
		// doc comment) — covers a slider whose own live-position transition is triggered by the very flag it
		// just changed (none currently is, but this keeps the two animation paths consistent and future-proof)
		// with no behavior change for any ordinary slider.
		boolean wasCanAnimate = sliderAnimWasEnabled.getOrDefault(key, liveCanAnimate);
		boolean effective = liveCanAnimate || wasCanAnimate;
		anim.setTarget(target);
		anim.setDurationSeconds(effective ? 0.3f : 0f);
		anim.updateIgnoringGlobalInstant(currentFrameDt);
		if (anim.get() == anim.getTarget()) sliderAnimWasEnabled.put(key, liveCanAnimate);
		return anim.get();
	}

	private void drawScaledText(GuiGraphicsExtractor graphics, String text, int x, int y, int color, float scale, float pivotX, float pivotY) {
		if (Math.abs(scale - 1f) < 0.002f) {
			SmoothTextRenderer.draw(graphics, this.font, text, x, y, color);
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(pivotX, pivotY);
		graphics.pose().scale(scale);
		graphics.pose().translate(-pivotX, -pivotY);
		SmoothTextRenderer.draw(graphics, this.font, text, x, y, color);
		graphics.pose().popMatrix();
	}

	/** Lightweight motion-blur approximation: a couple of faint trailing outlines between last frame's
	 *  and this frame's scale, rather than true GPU motion blur (which would need a post-process shader
	 *  pass over a framebuffer — out of proportion for a config panel). */
	private void drawPanelGhosts(GuiGraphicsExtractor graphics, float centerX, float centerY, float prevScale, float curScale) {
		float delta = curScale - prevScale;
		if (Math.abs(delta) < 0.002f) return;

		float[] steps = {0.35f, 0.7f};
		int[] alphas = {0x22, 0x10};
		// Real bug found (per user report — "When i use transparent theme mode i can see the outline around
		// the mod menu when opening and closing it"): this used to always tint the ghost strips pure white at
		// a fixed alpha, which reads as a subtle flash against the Black/White themes' own near-opaque panel
		// (alpha ~0xF0) but stands out as a stark rectangular white outline against Transparent mode's mostly-
		// see-through panel and the game world behind it. Tinting with the panel's OWN current color (so a
		// dark panel still ghosts a dark edge, a light one a light edge) and scaling the strip's alpha by the
		// panel's own current alpha fraction — same "fade in step with the panel" principle Theme.chrome()
		// already applies to every other chrome element — makes a fully-transparent panel's ghosts correspondingly
		// faint instead of a fixed-opacity outline that ignores the theme entirely.
		int panelRgb = Theme.panelBackground() & 0xFFFFFF;
		float panelAlphaFraction = ((Theme.panelBackground() >>> 24) & 0xFF) / 255f;
		for (int i = 0; i < steps.length; i++) {
			float s = prevScale + delta * steps[i];
			if (s <= 0f) continue;
			graphics.pose().pushMatrix();
			graphics.pose().translate(centerX, centerY);
			graphics.pose().scale(s);
			graphics.pose().translate(-centerX, -centerY);
			int color = (Math.round(alphas[i] * panelAlphaFraction) << 24) | panelRgb;
			int x0 = panelX, y0 = panelY, x1 = panelX + panelWidth, y1 = panelY + panelHeight;
			// Inset past the rounded corners (PANEL_RADIUS) so these straight-edge strips don't poke out
			// past the squircle curve — that mismatch was the visible white corner artifact during open/close.
			graphics.fill(x0 + PANEL_RADIUS, y0, x1 - PANEL_RADIUS, y0 + 2, color);
			graphics.fill(x0 + PANEL_RADIUS, y1 - 2, x1 - PANEL_RADIUS, y1, color);
			graphics.fill(x0, y0 + PANEL_RADIUS, x0 + 2, y1 - PANEL_RADIUS, color);
			graphics.fill(x1 - 2, y0 + PANEL_RADIUS, x1, y1 - PANEL_RADIUS, color);
			graphics.pose().popMatrix();
		}
	}

	private void drawExpandGhost(GuiGraphicsExtractor graphics, int x, int y, int width, float prevHeight, float curHeight) {
		float delta = curHeight - prevHeight;
		if (Math.abs(delta) < 0.5f) return;
		float ghostHeight = prevHeight + delta * 0.5f;
		if (ghostHeight <= 0f) return;
		int ghostY = y + Math.round(ghostHeight);
		graphics.fill(x, ghostY, x + width, ghostY + 1, 0x20FFFFFF);
	}

	private static float[] rgbToHsv(int argb) {
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		float h;
		if (delta < 1e-6f) {
			h = 0f;
		} else if (max == r) {
			h = 60f * (((g - b) / delta) % 6f);
		} else if (max == g) {
			h = 60f * (((b - r) / delta) + 2f);
		} else {
			h = 60f * (((r - g) / delta) + 4f);
		}
		if (h < 0) h += 360f;
		float s = max <= 1e-6f ? 0f : delta / max;
		return new float[]{h, s, max};
	}

	private static int hsvToRgb(float h, float s, float v) {
		h = ((h % 360f) + 360f) % 360f;
		float c = v * s;
		float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
		float m = v - c;
		float r, g, b;
		if (h < 60) { r = c; g = x; b = 0; }
		else if (h < 120) { r = x; g = c; b = 0; }
		else if (h < 180) { r = 0; g = c; b = x; }
		else if (h < 240) { r = 0; g = x; b = c; }
		else if (h < 300) { r = c; g = 0; b = x; }
		else { r = x; g = 0; b = c; }
		int ri = Math.round((r + m) * 255);
		int gi = Math.round((g + m) * 255);
		int bi = Math.round((b + m) * 255);
		return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
	}

	// colorSquareHits/sliderHits/hexFieldBounds/clickHits are shared, whole-frame lists populated by
	// drawExpandedSettings() for WHATEVER content it draws — including rows that scrolled above/below the
	// scrollable feature list's own visible viewport. layoutAndDraw()'s enableScissor call (see
	// listViewportY0/Y1) clips those off-viewport rows out of the actual RENDERED frame, but a scissor is a
	// GPU rasterizer clip only — it has no effect on these application-level hit-test lists, so an
	// off-viewport row (e.g. the bottom of a tall expanded panel scrolled partway off-screen) still had a
	// perfectly live, clickable hit sitting at its true computed position, which could easily land on top of
	// whatever's actually visible there (another feature row entirely, once the panel is scrolled far
	// enough). Per user report ("the entire panel is leaking... I have to spam for the toggle to work
	// sometimes") this reads exactly like that: a click visually on a real, visible toggle occasionally
	// matching a stale off-viewport hit from earlier in the same list instead, since list order — not
	// visibility — decided which hit won. Popup content (the small color/chroma popup, drawn deliberately
	// after the panel's own scissor is lifted so it can render outside/on top of it) is exempt — it's scoped
	// against popupX0..Y1 instead, at each call site above/below this helper.
	private boolean inListViewport(int y) {
		return y >= listViewportY0 && y <= listViewportY1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!interactive) return true;
		// While capturing a keybind combo, every click is raw input for the combo itself (so mouse
		// buttons can be bound) rather than UI navigation — swallow it here instead of falling through
		// to hit-testing or super.mouseClicked (which would e.g. close the screen on a stray right-click).
		if (listeningForCombo != null) return true;
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && interactive && !isStylePopupOpen()) {
			for (CopilotStepBoxHit hit : copilotStepBoxHits) {
				if (!inListViewport(hit.y())) continue;
				if (event.x() >= hit.x() && event.x() <= hit.x() + hit.w() && event.y() >= hit.y() && event.y() <= hit.y() + hit.h()) {
					hit.item().stepNumber = Math.max(1, hit.item().stepNumber - 1);
					ConfigManager.save();
					return true;
				}
			}
			for (RouteStepBoxHit hit : routeStepBoxHits) {
				if (!inListViewport(hit.y())) continue;
				if (event.x() >= hit.x() && event.x() <= hit.x() + hit.w() && event.y() >= hit.y() && event.y() <= hit.y() + hit.h()) {
					hit.item().stepNumber = Math.max(1, hit.item().stepNumber - 1);
					ConfigManager.save();
					return true;
				}
			}
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(event, doubleClick);
		}
		double mx = event.x();
		double my = event.y();

		// Per user request ("Add a draggable scrollbar... so users can scroll faster/jump directly") —
		// checked before everything else below (it's drawn on top of the panel content it scrolls), but
		// only while no popup is covering it, same as the step-drag boxes right after this.
		if (!isStylePopupOpen() && handleScrollbarClick(mx, my)) return true;

		// Left-press on a step-number box starts the drag-reorder state machine (see stepDragItem's own
		// field comment) instead of acting immediately — mouseReleased below decides whether this ends up
		// being a plain click (advance one step) or a real drag (reorder).
		if (!isStylePopupOpen()) {
			for (CopilotStepBoxHit hit : copilotStepBoxHits) {
				if (!inListViewport(hit.y())) continue;
				if (mx >= hit.x() && mx <= hit.x() + hit.w() && my >= hit.y() && my <= hit.y() + hit.h()) {
					stepDragItem = hit.item();
					stepDragStartY = my;
					stepDragCurrentY = my;
					stepDragCommitted = false;
					return true;
				}
			}
			for (RouteStepBoxHit hit : routeStepBoxHits) {
				if (!inListViewport(hit.y())) continue;
				if (mx >= hit.x() && mx <= hit.x() + hit.w() && my >= hit.y() && my <= hit.y() + hit.h()) {
					stepDragItem = hit.item();
					stepDragStartY = my;
					stepDragCurrentY = my;
					stepDragCommitted = false;
					return true;
				}
			}
		}

		if (isStylePopupOpen()) {
			boolean insidePopup = mx >= popupX0 && mx <= popupX1 && my >= popupY0 && my <= popupY1;
			if (!insidePopup) {
				closeStylePopups();
				return true;
			}
		}

		// While a popup is open, a hit here MUST also belong to the popup itself, not just happen to contain
		// (mx, my) — colorSquareHits/sliderHits/hexFieldBounds are shared, whole-frame lists that also
		// still hold entries from whatever's drawn UNDERNEATH the popup (e.g. the Style tab's own
		// background/outline color pickers and sliders), added earlier in the same frame, before the popup's
		// own controls. The insidePopup guard above only confirms the CLICK POSITION is inside the popup's
		// bounds — it says nothing about which registered hit gets matched, and `contains` just checks
		// geometry: an underlying panel slider/color-square that happens to occupy the same screen region
		// the popup was drawn over (very possible — popups render anchored to wherever their button was,
		// often right on top of other panel content) still matched FIRST since it was added to the list
		// first. That's the actual "I can click behind the popup" bug: the click visually lands on the
		// popup, but silently drags whatever panel control was underneath it instead. Requiring the hit's
		// OWN position to also fall inside the popup's bounds when one is open excludes those
		// underneath-but-geometrically-overlapping entries, since nothing the popup itself draws can be
		// positioned outside the rectangle it was just drawn into.
		boolean popupOpen = isStylePopupOpen();

		// Index-based, not just geometry-based: a hit's own POSITION falling inside the popup's bounds
		// doesn't mean it BELONGS to the popup — an underlying row's Bold/Tilted/color-square button can
		// legitimately sit at the same screen coordinates the popup was drawn over (popups anchor to
		// wherever their opening button was, i.e. right in the middle of other panel content). Only
		// entries added from popupHitsStart* onward were actually registered WHILE drawing the popup's own
		// content this frame, so restricting to that index range is what actually guarantees "this hit is
		// the popup's own control", regardless of any coincidental geometric overlap with what's underneath.
		if (gradientDialActiveThisFrame && !popupOpen) {
			double dx = mx - gradientDialCx, dy = my - gradientDialCy;
			if (dx * dx + dy * dy <= (double) gradientDialR * gradientDialR) {
				draggingGradientAngle = true;
				applyGradientDialDrag(mx, my);
				return true;
			}
		}
		if (gradientPinActiveThisFrame && !popupOpen) {
			// Real bug found (per user report — "i cant select the pin by clicking it, i have to click its
			// tip"): this hit-test was a small radius centered on (gradientPinNX, gradientPinNY), which is the
			// pin's TIP anchor point (see drawGradientPin's own doc comment — the icon is drawn tip-anchored,
			// so almost the entire visible ~16px icon sits ABOVE and to the right of that point, not centered
			// on it). A 9px radius around the tip barely overlaps the actual icon at all. Now tests against the
			// icon's real on-screen rect (same drawX/drawY math drawGradientPin itself uses), with a couple
			// pixels of forgiveness, so clicking anywhere on the visible pin selects it.
			boolean hit1 = gradientPinIconContains(gradientPin1X, gradientPin1Y, mx, my);
			boolean hit2 = gradientPinIconContains(gradientPin2X, gradientPin2Y, mx, my);
			if (hit1 || hit2) {
				int which;
				if (hit1 && hit2) {
					double d1x = mx - gradientPin1X, d1y = my - gradientPin1Y;
					double d2x = mx - gradientPin2X, d2y = my - gradientPin2Y;
					which = (d1x * d1x + d1y * d1y) <= (d2x * d2x + d2y * d2y) ? 1 : 2;
				} else {
					which = hit1 ? 1 : 2;
				}
				selectedGradientPin = which;
				draggingGradientPinWhich = which;
				startGradientPinDrag(which, mx, my);
				return true;
			}
		}

		for (int i = colorSquareHits.size() - 1; i >= 0; i--) {
			ColorSquareHit hit = colorSquareHits.get(i);
			if (popupOpen) {
				if (i < popupHitsStartColorSquare) continue;
			} else if (!inListViewport(hit.y)) {
				continue;
			}
			if (hit.contains(mx, my)) {
				draggingColorKey = hit.key;
				hit.applyAt(mx, my);
				ConfigManager.save();
				return true;
			}
		}

		for (int i = sliderHits.size() - 1; i >= 0; i--) {
			SliderHit slider = sliderHits.get(i);
			if (popupOpen) {
				if (i < popupHitsStartSlider) continue;
			} else if (!inListViewport(slider.y)) {
				continue;
			}
			if (slider.contains(mx, my)) {
				activeSlider = slider;
				slider.applyAt(mx, my);
				return true;
			}
		}

		int hexIndex = 0;
		for (Map.Entry<String, int[]> entry : hexFieldBounds.entrySet()) {
			int[] b = entry.getValue();
			int thisHexIndex = hexIndex++;
			if (popupOpen) {
				if (thisHexIndex < popupHitsStartHex) continue;
			} else if (!inListViewport(b[1])) {
				continue;
			}
			if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
				textFocus = TextFocus.HEX;
				focusedHexKey = entry.getKey();
				hexFieldBuffer = String.format(Locale.ROOT, "%06X", hexFieldColors.getOrDefault(focusedHexKey, 0) & 0xFFFFFF);
				return true;
			}
		}

		// A popup being open only stopped it from closing on an inside click (the insidePopup check way
		// above) — it never actually stopped the click once none of the popup's own widgets (color square/
		// slider/hex field, all checked above) matched. Clicking blank space between two buttons inside
		// the popup fell through everything above and reached whatever's underneath in screen space (a
		// feature row, a toggle, etc.), which is the actual "I can still click behind the chroma gui if I
		// press between the buttons" bug. Swallow it here instead.
		//
		// But the chroma popup's own "Chroma: ON/OFF" switch (drawLineStylePopups -> drawToggleRow) is
		// registered into the generic clickHits list, not colorSquareHits/sliderHits/hexFieldBounds above
		// — this swallow used to run unconditionally before clickHits was ever checked (that loop lives
		// much later in this method), so the toggle's own click was swallowed by its own popup and never
		// fired. Checking clickHits here first, scoped to just this popup's bounds, was the actual
		// "chroma won't turn on for lines" bug.
		if (isStylePopupOpen()) {
			if (mx >= popupX0 && mx <= popupX1 && my >= popupY0 && my <= popupY1) {
				// Per user report ("the toggle is leaking... when I click through the rest of the stuff it
				// clicks the buttons behind it, like the bold button"): a plain geometric bounds check here
				// (matching hit.x/y against the popup's own rectangle) isn't enough, because it can't tell
				// "this hit belongs to the popup" from "an underlying row's Bold/Tilted/color button just
				// happens to sit at the same screen position the popup was drawn over" — popups anchor to
				// wherever their opening button was, which is often right on top of other panel content.
				// clickHits is forward-iterated in render order, so the EARLIER-added underlying hit matched
				// first regardless of the popup's own control (added later, drawn on top) being the one
				// actually visible and clicked. Restricting to index >= popupHitsStartClick only considers
				// entries registered WHILE drawing the popup's own content this frame — an explicit
				// "belongs to the popup" check, not a geometric guess.
				for (int i = popupHitsStartClick; i < clickHits.size(); i++) {
					ClickHit hit = clickHits.get(i);
					if (hit.contains(mx, my)) {
						hit.action.run();
						return true;
					}
				}
				return true;
			}
		}

		if (expandTarget instanceof CustomEnchantParsingFeature cef
			&& mx >= addRuleButtonX && mx <= addRuleButtonX + addRuleButtonSize && my >= addRuleButtonY && my <= addRuleButtonY + addRuleButtonSize) {
			cef.addRule("", 1);
			ConfigManager.save();
			return true;
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature vwf) {
			if (mx >= addVisualWordButtonX && mx <= addVisualWordButtonX + addVisualWordButtonSize
					&& my >= addVisualWordButtonY && my <= addVisualWordButtonY + addVisualWordButtonSize) {
				vwf.addReplacement();
				return true;
			}
			for (int[] b : visualWordFindBoxes) {
				if (!inListViewport(b[1])) continue;
				if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
					int index = b[4];
					if (index < vwf.getReplacements().size()) {
						String text = vwf.getReplacements().get(index).find;
						boolean alreadyFocused = textFocus == TextFocus.VISUALWORD_FIND && editingVisualWordIndex == index;
						textFocus = TextFocus.VISUALWORD_FIND;
						editingVisualWordIndex = index;
						int textX = b[0] + 4;
						if (doubleClick && alreadyFocused) {
							selectWordAt(text, charIndexAtX(text, textX, mx));
						} else {
							textSelectionAnchor = -1;
							textCursor = charIndexAtX(text, textX, mx);
						}
					}
					return true;
				}
			}
			for (int[] b : visualWordReplaceBoxes) {
				if (!inListViewport(b[1])) continue;
				if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
					int index = b[4];
					if (index < vwf.getReplacements().size()) {
						String text = vwf.getReplacements().get(index).replace;
						boolean alreadyFocused = textFocus == TextFocus.VISUALWORD_REPLACE && editingVisualWordIndex == index;
						textFocus = TextFocus.VISUALWORD_REPLACE;
						editingVisualWordIndex = index;
						int textX = b[0] + 4;
						if (doubleClick && alreadyFocused) {
							selectWordAt(text, charIndexAtX(text, textX, mx));
						} else {
							textSelectionAnchor = -1;
							textCursor = charIndexAtX(text, textX, mx);
						}
					}
					return true;
				}
			}
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature notifTarget) {
			var notifTypes = com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature.NotificationType.values();
			for (int[] b : dungeonNotifTitleBoxes) {
				if (!inListViewport(b[1])) continue;
				if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
					var type = notifTypes[b[4]];
					String text = notifTarget.getTypeTitleRaw(type);
					boolean alreadyFocused = textFocus == TextFocus.DUNGEON_NOTIF_TITLE && editingNotifType == type;
					textFocus = TextFocus.DUNGEON_NOTIF_TITLE;
					editingNotifType = type;
					int textX = b[0] + 4;
					if (doubleClick && alreadyFocused) {
						selectWordAt(text, charIndexAtX(text, textX, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(text, textX, mx);
					}
					return true;
				}
			}
			for (int[] b : dungeonNotifPartyMsgBoxes) {
				if (!inListViewport(b[1])) continue;
				if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
					var type = notifTypes[b[4]];
					String text = notifTarget.getPartyChatMessage(type);
					boolean alreadyFocused = textFocus == TextFocus.DUNGEON_NOTIF_PARTY_MSG && editingNotifType == type;
					textFocus = TextFocus.DUNGEON_NOTIF_PARTY_MSG;
					editingNotifType = type;
					int textX = b[0] + 4;
					if (doubleClick && alreadyFocused) {
						selectWordAt(text, charIndexAtX(text, textX, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(text, textX, mx);
					}
					return true;
				}
			}
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcfExpanded) {
			if (mx >= copilotImportBoxX && mx <= copilotImportBoxX + copilotImportBoxWidth
					&& my >= copilotImportBoxY && my <= copilotImportBoxY + copilotImportBoxHeight && inListViewport(copilotImportBoxY)) {
				boolean alreadyFocused = textFocus == TextFocus.COPILOT_IMPORT;
				textFocus = TextFocus.COPILOT_IMPORT;
				if (doubleClick && alreadyFocused) {
					selectWordAt(copilotImportBuffer, charIndexAtX(copilotImportBuffer, copilotImportBoxX + 4, mx));
				} else {
					textSelectionAnchor = -1;
					textCursor = charIndexAtX(copilotImportBuffer, copilotImportBoxX + 4, mx);
				}
				return true;
			}
			if (mx >= copilotExportButtonX && mx <= copilotExportButtonX + copilotExportButtonWidth
					&& my >= copilotExportButtonY && my <= copilotExportButtonY + copilotExportButtonHeight && inListViewport(copilotExportButtonY)) {
				String exported = dcfExpanded.exportToClipboardString();
				if (exported != null) {
					this.minecraft.keyboardHandler.setClipboard(exported);
					if (this.minecraft.player != null) {
						this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
							"§aBoss Guide plan copied to clipboard (" + exported.length() + " characters)."));
					}
				}
				return true;
			}
			for (CopilotBoxHit b : copilotFieldBoxes) {
				if (!inListViewport(b.y())) continue;
				if (mx >= b.x() && mx <= b.x() + b.w() && my >= b.y() && my <= b.y() + b.h()) {
					String text = copilotFieldValueOf(b.item(), b.field());
					boolean alreadyFocused = textFocus == TextFocus.COPILOT_FIELD && editingCopilotItem == b.item() && b.field().equals(editingCopilotField);
					textFocus = TextFocus.COPILOT_FIELD;
					editingCopilotItem = b.item();
					editingCopilotField = b.field();
					int textX = b.x() + 4;
					if (doubleClick && alreadyFocused) {
						selectWordAt(text, charIndexAtX(text, textX, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(text, textX, mx);
					}
					return true;
				}
			}
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drfExpanded) {
			if (selectedRouteRoomName == null) {
				if (mx >= routeAllImportBoxX && mx <= routeAllImportBoxX + routeAllImportBoxWidth
						&& my >= routeAllImportBoxY && my <= routeAllImportBoxY + routeAllImportBoxHeight && inListViewport(routeAllImportBoxY)) {
					boolean alreadyFocused = textFocus == TextFocus.ROUTE_ALL_IMPORT;
					textFocus = TextFocus.ROUTE_ALL_IMPORT;
					if (doubleClick && alreadyFocused) {
						selectWordAt(routeAllImportBuffer, charIndexAtX(routeAllImportBuffer, routeAllImportBoxX + 4, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(routeAllImportBuffer, routeAllImportBoxX + 4, mx);
					}
					return true;
				}
				if (mx >= routeAllExportButtonX && mx <= routeAllExportButtonX + routeAllExportButtonWidth
						&& my >= routeAllExportButtonY && my <= routeAllExportButtonY + routeAllExportButtonHeight && inListViewport(routeAllExportButtonY)) {
					String exported = drfExpanded.exportAllToClipboardString();
					if (exported != null) {
						this.minecraft.keyboardHandler.setClipboard(exported);
						if (this.minecraft.player != null) {
							this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
								"§aDungeon Routes (all rooms) copied to clipboard (" + exported.length() + " characters)."));
						}
					}
					return true;
				}
				if (mx >= routeRoomSearchBoxX && mx <= routeRoomSearchBoxX + routeRoomSearchBoxWidth
						&& my >= routeRoomSearchBoxY && my <= routeRoomSearchBoxY + routeRoomSearchBoxHeight && inListViewport(routeRoomSearchBoxY)) {
					boolean alreadyFocused = textFocus == TextFocus.ROUTE_ROOM_SEARCH;
					textFocus = TextFocus.ROUTE_ROOM_SEARCH;
					if (doubleClick && alreadyFocused) {
						selectWordAt(routeRoomSearchQuery, charIndexAtX(routeRoomSearchQuery, routeRoomSearchBoxX + 4, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(routeRoomSearchQuery, routeRoomSearchBoxX + 4, mx);
					}
					return true;
				}
			} else {
				if (mx >= routeRoomImportBoxX && mx <= routeRoomImportBoxX + routeRoomImportBoxWidth
						&& my >= routeRoomImportBoxY && my <= routeRoomImportBoxY + routeRoomImportBoxHeight && inListViewport(routeRoomImportBoxY)) {
					boolean alreadyFocused = textFocus == TextFocus.ROUTE_ROOM_IMPORT;
					textFocus = TextFocus.ROUTE_ROOM_IMPORT;
					if (doubleClick && alreadyFocused) {
						selectWordAt(routeRoomImportBuffer, charIndexAtX(routeRoomImportBuffer, routeRoomImportBoxX + 4, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(routeRoomImportBuffer, routeRoomImportBoxX + 4, mx);
					}
					return true;
				}
				if (mx >= routeRoomExportButtonX && mx <= routeRoomExportButtonX + routeRoomExportButtonWidth
						&& my >= routeRoomExportButtonY && my <= routeRoomExportButtonY + routeRoomExportButtonHeight && inListViewport(routeRoomExportButtonY)) {
					String exported = drfExpanded.exportRoomToClipboardString(selectedRouteRoomName);
					if (exported != null) {
						this.minecraft.keyboardHandler.setClipboard(exported);
						if (this.minecraft.player != null) {
							this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
								"§aDungeon Routes (" + selectedRouteRoomName + ") copied to clipboard (" + exported.length() + " characters)."));
						}
					}
					return true;
				}
			}
			for (RouteBoxHit b : routeFieldBoxes) {
				if (!inListViewport(b.y())) continue;
				if (mx >= b.x() && mx <= b.x() + b.w() && my >= b.y() && my <= b.y() + b.h()) {
					String text = routeFieldValueOf(b.item(), b.field());
					boolean alreadyFocused = textFocus == TextFocus.ROUTE_FIELD && editingRouteItem == b.item() && b.field().equals(editingRouteField);
					textFocus = TextFocus.ROUTE_FIELD;
					editingRouteItem = b.item();
					editingRouteField = b.field();
					int textX = b.x() + 4;
					if (doubleClick && alreadyFocused) {
						selectWordAt(text, charIndexAtX(text, textX, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(text, textX, mx);
					}
					return true;
				}
			}
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature) {
			for (AliasBoxHit b : aliasFieldBoxes) {
				if (!inListViewport(b.y())) continue;
				if (mx >= b.x() && mx <= b.x() + b.w() && my >= b.y() && my <= b.y() + b.h()) {
					String text = aliasFieldValueOf(b.alias(), b.field());
					boolean alreadyFocused = textFocus == TextFocus.ALIAS_FIELD && editingAlias == b.alias() && b.field().equals(editingAliasField);
					textFocus = TextFocus.ALIAS_FIELD;
					editingAlias = b.alias();
					editingAliasField = b.field();
					int textX = b.x() + 4;
					if (doubleClick && alreadyFocused) {
						selectWordAt(text, charIndexAtX(text, textX, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(text, textX, mx);
					}
					return true;
				}
			}
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature kfb0 && kismetThresholdBox != null) {
			KismetThresholdBoxHit b = kismetThresholdBox;
			if (inListViewport(b.y()) && mx >= b.x() && mx <= b.x() + b.w() && my >= b.y() && my <= b.y() + b.h()) {
				String text = kfb0.getThresholdInput();
				boolean alreadyFocused = textFocus == TextFocus.KISMET_THRESHOLD;
				textFocus = TextFocus.KISMET_THRESHOLD;
				int textX = b.x() + 4;
				if (doubleClick && alreadyFocused) {
					selectWordAt(text, charIndexAtX(text, textX, mx));
				} else {
					textSelectionAnchor = -1;
					textCursor = charIndexAtX(text, textX, mx);
				}
				return true;
			}
		}

		for (Map.Entry<String, int[]> entry : genericFieldBounds.entrySet()) {
			int[] b = entry.getValue();
			if (!inListViewport(b[1])) continue;
			if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
				String id = entry.getKey();
				var getter = genericFieldGetters.get(id);
				String text = getter != null ? getter.get() : "";
				boolean alreadyFocused = textFocus == TextFocus.GENERIC_FIELD && id.equals(genericFocusedFieldId);
				textFocus = TextFocus.GENERIC_FIELD;
				genericFocusedFieldId = id;
				int textX = b[0] + 4;
				if (doubleClick && alreadyFocused) {
					selectWordAt(text, charIndexAtX(text, textX, mx));
				} else {
					textSelectionAnchor = -1;
					textCursor = charIndexAtX(text, textX, mx);
				}
				return true;
			}
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature) {
			for (ShortcutBoxHit b : shortcutCommandBoxes) {
				if (!inListViewport(b.y())) continue;
				if (mx >= b.x() && mx <= b.x() + b.w() && my >= b.y() && my <= b.y() + b.h()) {
					String text = b.shortcut().commandInput;
					boolean alreadyFocused = textFocus == TextFocus.SHORTCUT_COMMAND && editingShortcut == b.shortcut();
					textFocus = TextFocus.SHORTCUT_COMMAND;
					editingShortcut = b.shortcut();
					int textX = b.x() + 4;
					if (doubleClick && alreadyFocused) {
						selectWordAt(text, charIndexAtX(text, textX, mx));
					} else {
						textSelectionAnchor = -1;
						textCursor = charIndexAtX(text, textX, mx);
					}
					return true;
				}
			}
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature addPmf
			&& mx >= addPosMsgButtonX && mx <= addPosMsgButtonX + addPosMsgButtonWidth
			&& my >= addPosMsgButtonY && my <= addPosMsgButtonY + addPosMsgButtonHeight) {
			addPmf.addMessage();
			return true;
		}

		// configRowVisible instead of an expandTarget check — this row is always visible in the list now
		// (no cog/expand step), so the only guard needed is "was it actually drawn this frame" (false when
		// scrolled out of view or filtered out by search/category), same purpose the expandTarget check
		// used to serve for the old expandable-panel version.
		if (configRowVisible) {
			if (mx >= configImportBoxX && mx <= configImportBoxX + configImportBoxWidth
					&& my >= configImportBoxY && my <= configImportBoxY + configImportBoxHeight) {
				boolean alreadyFocused = textFocus == TextFocus.CONFIG_IMPORT;
				textFocus = TextFocus.CONFIG_IMPORT;
				if (doubleClick && alreadyFocused) {
					selectWordAt(configImportBuffer, charIndexAtX(configImportBuffer, configImportBoxX + 4, mx));
				} else {
					textSelectionAnchor = -1;
					textCursor = charIndexAtX(configImportBuffer, configImportBoxX + 4, mx);
				}
				return true;
			}
			if (mx >= configExportButtonX && mx <= configExportButtonX + configExportButtonWidth
					&& my >= configExportButtonY && my <= configExportButtonY + configExportButtonHeight) {
				String exported = ConfigManager.exportToClipboardString();
				if (exported != null) {
					this.minecraft.keyboardHandler.setClipboard(exported);
					if (this.minecraft.player != null) {
						this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
							"§aConfig copied to clipboard (" + exported.length() + " characters)."));
					}
				} else if (this.minecraft.player != null) {
					this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("§cExport failed."));
				}
				return true;
			}
		}

		if (expandTarget instanceof MobHighlightFeature mhfClick && mhfClick.getRenderMode() == MobHighlightFeature.RenderMode.FULL_2D) {
			if (mx >= mhfImageButtonX && mx <= mhfImageButtonX + mhfImageButtonWidth
					&& my >= mhfImageButtonY && my <= mhfImageButtonY + mhfImageButtonHeight) {
				// Blocking on purpose — a native "browse for a file" dialog freezing the frame while open is
				// normal, expected desktop-app behavior for a user-initiated, modal file pick, not a stray hang.
				// Filter patterns are cosmetic only (the OS dialog still lets a user override/pick anything) —
				// getCustomImageTextureId() is what actually determines whether a pick loads, now via
				// ImageIO as well as NativeImage, so this list is just steering, not a hard format cap.
				String picked;
				try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
					org.lwjgl.PointerBuffer filters = stack.pointers(
						stack.UTF8("*.png"), stack.UTF8("*.jpg"), stack.UTF8("*.jpeg"), stack.UTF8("*.bmp"), stack.UTF8("*.gif"));
					picked = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
						"Select highlight image", "", filters, "Image files", false);
				}
				if (picked != null) {
					mhfClick.setCustomImagePath(picked);
					mhfClick.setUseCustomImage(true);
					ConfigManager.save();
				}
				return true;
			}
			if (mhfClick.getCustomImagePath() != null
					&& mx >= mhfClearImageButtonX && mx <= mhfClearImageButtonX + mhfClearImageButtonWidth
					&& my >= mhfClearImageButtonY && my <= mhfClearImageButtonY + mhfClearImageButtonHeight) {
				mhfClick.setCustomImagePath(null);
				mhfClick.setUseCustomImage(false);
				ConfigManager.save();
				return true;
			}
		}

		for (ClickHit hit : tabButtonHits) {
			if (!inListViewport(hit.y)) continue;
			if (hit.contains(mx, my)) {
				hit.action.run();
				return true;
			}
		}
		for (ClickHit hit : clickHits) {
			if (!inListViewport(hit.y)) continue;
			if (hit.contains(mx, my)) {
				hit.action.run();
				return true;
			}
		}

		if (expandTarget instanceof CustomEnchantParsingFeature cef) {
			for (int[] b : enchantNameBoxes) {
				if (!inListViewport(b[1])) continue;
				if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
					int ruleIndex = b[4];
					if (ruleIndex < cef.getRules().size()) {
						String nameText = cef.getRules().get(ruleIndex).name;
						boolean alreadyFocused = textFocus == TextFocus.ENCHANT_NAME && editingRuleIndex == ruleIndex;
						textFocus = TextFocus.ENCHANT_NAME;
						editingRuleIndex = ruleIndex;
						int textX = b[0] + 4;
						if (doubleClick && alreadyFocused) {
							selectWordAt(nameText, charIndexAtX(nameText, textX, mx));
						} else {
							textSelectionAnchor = -1;
							textCursor = charIndexAtX(nameText, textX, mx);
						}
					}
					return true;
				}
			}
			for (int[] b : enchantLevelBoxes) {
				if (!inListViewport(b[1])) continue;
				if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
					int ruleIndex = b[4];
					if (ruleIndex < cef.getRules().size()) {
						String levelText = cef.getRules().get(ruleIndex).levelInput;
						boolean alreadyFocused = textFocus == TextFocus.ENCHANT_LEVEL && editingRuleIndex == ruleIndex;
						textFocus = TextFocus.ENCHANT_LEVEL;
						editingRuleIndex = ruleIndex;
						int textX = b[0] + 4;
						if (doubleClick && alreadyFocused) {
							selectWordAt(levelText, charIndexAtX(levelText, textX, mx));
						} else {
							textSelectionAnchor = -1;
							textCursor = charIndexAtX(levelText, textX, mx);
						}
					}
					return true;
				}
			}
		}

		if (expandTarget instanceof com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature pmf) {
			if (mx >= posMsgConfigImportBoxX && mx <= posMsgConfigImportBoxX + posMsgConfigImportBoxWidth
					&& my >= posMsgConfigImportBoxY && my <= posMsgConfigImportBoxY + posMsgConfigImportBoxHeight && inListViewport(posMsgConfigImportBoxY)) {
				boolean alreadyFocused = textFocus == TextFocus.POSMSG_CONFIG_IMPORT;
				textFocus = TextFocus.POSMSG_CONFIG_IMPORT;
				if (doubleClick && alreadyFocused) {
					selectWordAt(posMsgConfigImportBuffer, charIndexAtX(posMsgConfigImportBuffer, posMsgConfigImportBoxX + 4, mx));
				} else {
					textSelectionAnchor = -1;
					textCursor = charIndexAtX(posMsgConfigImportBuffer, posMsgConfigImportBoxX + 4, mx);
				}
				return true;
			}
			if (mx >= posMsgConfigExportButtonX && mx <= posMsgConfigExportButtonX + posMsgConfigExportButtonWidth
					&& my >= posMsgConfigExportButtonY && my <= posMsgConfigExportButtonY + posMsgConfigExportButtonHeight && inListViewport(posMsgConfigExportButtonY)) {
				String exported = pmf.exportToClipboardString();
				if (exported != null) {
					this.minecraft.keyboardHandler.setClipboard(exported);
					if (this.minecraft.player != null) {
						this.minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
							"§aPositional Messages config copied to clipboard (" + exported.length() + " characters)."));
					}
				}
				return true;
			}
			TextFocus[] posMsgFocuses = TextFocus.values();
			for (int[] b : posMsgFieldBoxes) {
				if (!inListViewport(b[1])) continue;
				if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
					int msgIndex = b[4];
					TextFocus focus = posMsgFocuses[b[5]];
					if (msgIndex < pmf.getMessages().size()) {
						boolean alreadyFocused = textFocus == focus && editingPosMsgIndex == msgIndex;
						textFocus = focus;
						editingPosMsgIndex = msgIndex;
						String fieldText = getFocusedText();
						int textX = b[0] + 4 + (focus == TextFocus.POSMSG_TEXT ? 0 : this.font.width(switch (focus) {
							case POSMSG_X -> "X:"; case POSMSG_Y -> "Y:"; case POSMSG_Z -> "Z:"; case POSMSG_RANGE -> "R:"; default -> "";
						}));
						if (doubleClick && alreadyFocused) {
							selectWordAt(fieldText, charIndexAtX(fieldText, textX, mx));
						} else {
							textSelectionAnchor = -1;
							textCursor = charIndexAtX(fieldText, textX, mx);
						}
					}
					return true;
				}
			}
		}

		for (SlotRow row : slotRows) {
			if (row.contains(mx, my)) {
				listeningFor = row.keyMapping;
				listeningEscapeUnbinds = row.escapeUnbinds;
				return true;
			}
		}

		for (InlineKeybindRow row : inlineKeybindRows) {
			if (row.contains(mx, my)) {
				listeningFor = row.keyMapping;
				listeningEscapeUnbinds = true;
				return true;
			}
		}

		for (ComboRow row : comboRows) {
			if (row.contains(mx, my)) {
				startComboCapture(row.combo, true);
				return true;
			}
		}

		if (mx >= magnifierX && mx <= magnifierX + magnifierSize && my >= magnifierY && my <= magnifierY + magnifierSize) {
			playClickSound();
			searchOpen = !searchOpen;
			if (!searchOpen) {
				searchQuery = "";
				if (textFocus == TextFocus.SEARCH) textFocus = TextFocus.NONE;
			} else {
				focusTextField(TextFocus.SEARCH);
			}
			searchAnim.setTarget(searchOpen ? 1f : 0f);
			return true;
		}

		// Clicking inside the already-open search box must (re-)focus it, not fall through to the
		// "click empty space unfocuses" case below — that was the actual cause of "can't type in search".
		if (searchOpen && searchBoxVisible && mx >= searchBoxX && mx <= searchBoxX + searchBoxWidth
			&& my >= searchBoxY && my <= searchBoxY + searchBoxHeight) {
			boolean alreadyFocused = textFocus == TextFocus.SEARCH;
			textFocus = TextFocus.SEARCH;
			if (doubleClick && alreadyFocused) {
				selectWordAt(searchQuery, charIndexAtX(searchQuery, searchBoxX + 4, mx));
			} else {
				textSelectionAnchor = -1;
				textCursor = charIndexAtX(searchQuery, searchBoxX + 4, mx);
			}
			return true;
		}

		for (CategoryRow row : categoryRows) {
			if (row.contains(mx, my)) {
				playClickSound();
				FeatureCategory clickedCategory = row.category;
				triggerContentSwap(() -> selectCategory(clickedCategory));
				return true;
			}
		}

		for (SubTabRow row : subTabRows) {
			if (row.contains(mx, my)) {
				// Not a real content filter: opens the dedicated edit-mode screen over the real game
				// view instead, same as clicking a feature's cog does for normal settings.
				if (row.subcategory.equals(EDIT_GUI_LOCATIONS_SUBCATEGORY)) {
					playClickSound();
					this.minecraft.gui.setScreen(new HudEditScreen());
					return true;
				}
				playClickSound();
				selectedSlayerType = null;
				scrollTarget = 0f;
				closeSearchIfOpen();
				String clickedSubcategory = row.subcategory;
				triggerContentSwap(() -> selectedSubcategory = clickedSubcategory);
				return true;
			}
		}

		for (SubTabRow row : slayerTypeRows) {
			if (row.contains(mx, my)) {
				selectedSlayerType = row.subcategory.equals("General") ? null : row.subcategory;
				scrollTarget = 0f;
				return true;
			}
		}

		for (FeatureRow row : featureRows) {
			boolean opensSettings = (row.cogClickable && row.cogContains(mx, my))
				|| (row.isColorSwatch && row.toggleContains(mx, my));
			if (opensSettings) {
				// Per user request: opening a result's settings (cog, or the row itself below) is drilling
				// into that same search result, not navigating away from it — the query should stay put so
				// the module list underneath is still exactly what was searched for once the panel closes.
				if (expandTarget == row.feature) {
					startCollapse();
				} else if (expandPhase == ExpandPhase.CLOSED) {
					startExpand(row.feature);
				} else {
					pendingExpand = row.feature;
					startCollapse();
				}
				return true;
			}
			if (row.toggleable && row.toggleContains(mx, my)) {
				// Per user request: toggling a feature is acting on a search result, not navigating away
				// from it — same "drilling in, not leaving" reasoning as the cog/row-click branches below,
				// which already leave the query in place. Previously closed search here too (see this
				// method's own now-stale history), which reset the list out from under repeat toggles.
				// Real bug found (per user report — "the toggle sound plays even if the toggle is on
				// cooldown"): Feature#setEnabled silently no-ops while its own 500ms debounce is active, but
				// this used to play the click sound unconditionally regardless of whether the toggle actually
				// took effect — capturing the before-state and only playing the sound on a real change fixes it.
				boolean wasEnabled = row.feature.isEnabled();
				row.feature.setEnabled(!wasEnabled);
				if (row.feature.isEnabled() != wasEnabled) {
					playToggleSound(row.feature.isEnabled());
					ConfigManager.save();
				}
				return true;
			}
			if (row.isUpdateButton && row.toggleContains(mx, my)
					&& com.cokelord.skyblocksimplified.api.UpdateApi.getState() == com.cokelord.skyblocksimplified.api.UpdateApi.State.AVAILABLE) {
				com.cokelord.skyblocksimplified.api.UpdateApi.startUpdate();
				return true;
			}
			// The module itself is clickable to open/close its settings (not just the cog) — the cog
			// stays too, in case it's not obvious the row itself is clickable. Search stays open here too,
			// same reasoning as the cog branch above.
			if (row.hasSettings && row.rowContains(mx, my)) {
				if (expandTarget == row.feature) {
					startCollapse();
				} else if (expandPhase == ExpandPhase.CLOSED) {
					startExpand(row.feature);
				} else {
					pendingExpand = row.feature;
					startCollapse();
				}
				return true;
			}
		}

		textFocus = TextFocus.NONE;
		editingRuleIndex = -1;
		editingPosMsgIndex = -1;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!interactive) return true;
		if (draggingScrollbar != null) {
			applyScrollbarOffset(draggingScrollbar, scrollbarMouseYToOffset(event.y(), scrollbarDragTrackY0, scrollbarDragTrackHeight, scrollbarDragHandleHeight,
				draggingScrollbar == ScrollbarKind.CONTENT ? maxScrollOffset : maxSidebarScrollOffset));
			return true;
		}
		if (stepDragItem != null) {
			stepDragCurrentY = event.y();
			if (!stepDragCommitted && Math.abs(stepDragCurrentY - stepDragStartY) > 6) stepDragCommitted = true;
			return true;
		}
		if (draggingColorKey != null) {
			for (ColorSquareHit hit : colorSquareHits) {
				if (hit.key.equals(draggingColorKey)) {
					hit.applyAt(event.x(), event.y());
					ConfigManager.save();
					break;
				}
			}
			return true;
		}
		if (draggingGradientAngle) {
			applyGradientDialDrag(event.x(), event.y());
			return true;
		}
		if (draggingGradientPinWhich != 0) {
			applyGradientPinDrag(draggingGradientPinWhich, event.x(), event.y());
			return true;
		}
		if (activeSlider != null) {
			activeSlider.applyAt(event.x(), event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = null;
		if (stepDragItem != null) {
			if (stepDragCommitted) {
				resolveStepDrag(stepDragItem, stepDragCurrentY);
			} else if (stepDragItem instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem ci) {
				ci.stepNumber++;
				ConfigManager.save();
			} else if (stepDragItem instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem ri) {
				ri.stepNumber++;
				ConfigManager.save();
			}
			stepDragItem = null;
			stepDragCommitted = false;
		}
		draggingColorKey = null;
		activeSlider = null;
		draggingGradientAngle = false;
		draggingGradientPinWhich = 0;
		return super.mouseReleased(event);
	}

	/** Per spec ("angle control... draggable around a circle", 0°=top=pin1, clockwise) — a click/drag
	 *  anywhere inside the dial sets the angle to point at the cursor, using the exact same clockwise-from-
	 *  top convention {@link RenderUtil#colorAtFraction}'s own LINEAR math uses (see Theme.GradientMode's
	 *  doc comment): {@code atan2(dx,-dy)} treats "up" as 0° and sweeps clockwise, the inverse of the
	 *  {@code (sin θ,-cos θ)} direction vector that math derives an angle FROM. */
	private void applyGradientDialDrag(double mx, double my) {
		com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf = panelThemeFeatureInstance();
		if (ptf == null) return;
		double dx = mx - gradientDialCx, dy = my - gradientDialCy;
		if (dx * dx + dy * dy < 1e-6) return;
		float angle = (float) Math.toDegrees(Math.atan2(dx, -dy));
		if (angle < 0) angle += 360f;
		ptf.setCustomGradientAngle(angle);
		ConfigManager.save();
	}

	/** Per user report ("grabbing on a pin sends it to the cursor. It should not do that... if i grab it it
	 *  will not move but if i move my cursor up while its grabbed then it should move up just as much"):
	 *  records the pin's own current pos plus the cursor's axis-projection at the exact moment of the grab —
	 *  see {@link #applyGradientPinDrag}'s own doc comment for how these two are turned into a relative,
	 *  not absolute, movement on every subsequent drag call. Deliberately does NOT move the pin itself. */
	private void startGradientPinDrag(int which, double mx, double my) {
		com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf = panelThemeFeatureInstance();
		if (ptf == null) return;
		gradientPinDragStartPos = which == 1 ? ptf.getCustomPin1Pos() : ptf.getCustomPin2Pos();
		double dx = mx - gradientPinCx, dy = my - gradientPinCy;
		gradientPinDragStartProj = dx * gradientPinAxisX + dy * gradientPinAxisY;
	}

	/** Per user correction ("dont do all that clicking will drag the nearest pin anymore, just make them
	 *  pickupable and movable. It should only be movable inward/towards the other pin"): projects the mouse
	 *  onto the SAME direction axis {@link #drawGradientPinEditor} placed the pins along (a plain dot product,
	 *  since {@code gradientPinAxisX/Y} is already a unit vector) to get a signed pixel offset from the
	 *  square's center. Per a later user report (see {@link #startGradientPinDrag}'s own doc comment), this no
	 *  longer converts that ABSOLUTE offset straight into a pos value (which snapped the pin onto the cursor
	 *  the instant it was grabbed, wherever that was) — it instead measures how far the projection has moved
	 *  since the grab and applies that same delta on top of the pos the pin already had, so picking a pin up
	 *  never moves it and only actual cursor movement does, by a matching amount. The actual "can't move
	 *  outward past the square edge, can't cross the other pin" constraint isn't reimplemented here at all —
	 *  {@link com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature#setCustomPin1Pos}/{@code
	 *  setCustomPin2Pos} already clamp to [0,1] and refuse to cross, the exact same choke point the OLD
	 *  drag-bar editor went through, so dragging a pin off either end of the line just pins it at its own
	 *  0/1 bound or against the other pin, never past either. */
	private void applyGradientPinDrag(int which, double mx, double my) {
		com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf = panelThemeFeatureInstance();
		if (ptf == null) return;
		double dx = mx - gradientPinCx, dy = my - gradientPinCy;
		double proj = dx * gradientPinAxisX + dy * gradientPinAxisY;
		float t = gradientPinDragStartPos - (float) ((proj - gradientPinDragStartProj) / GRADIENT_SQUARE_SIZE);
		float minGapFraction = 2f / GRADIENT_SQUARE_SIZE;
		if (which == 1) ptf.setCustomPin1Pos(t, minGapFraction);
		else ptf.setCustomPin2Pos(t, minGapFraction);
		ConfigManager.save();
	}

	private static com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature panelThemeFeatureInstance() {
		return FeatureRegistry.get("panel_theme") instanceof com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature ptf ? ptf : null;
	}

	private static com.cokelord.skyblocksimplified.feature.impl.GuiColorFeature guiColorFeatureInstance() {
		return FeatureRegistry.get("gui_color") instanceof com.cokelord.skyblocksimplified.feature.impl.GuiColorFeature gcf ? gcf : null;
	}

	/** Ends a committed step-number-box drag (see stepDragItem's own field comment): finds which row's
	 *  vertical band the pointer was released over among this frame's own step-box hit list (the real
	 *  on-screen order, whatever it happens to be) and reorders the underlying feature's step list to match,
	 *  via {@link com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature#reorderItem}/
	 *  {@link com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature#reorderItem}. */
	private void resolveStepDrag(Object item, double dropY) {
		if (item instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature.CopilotItem ci) {
			com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf = copilotFeatureInstance();
			if (dcf == null) return;
			int targetIndex = 0;
			for (CopilotStepBoxHit hit : copilotStepBoxHits) {
				if (hit.item().forClass != ci.forClass) continue;
				if (dropY > hit.y() + hit.h() / 2.0) targetIndex++;
			}
			dcf.reorderItem(ci, targetIndex);
		} else if (item instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem ri) {
			com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf = routesFeatureInstance();
			if (drf == null) return;
			int targetIndex = 0;
			for (RouteStepBoxHit hit : routeStepBoxHits) {
				if (!hit.item().roomName.equals(ri.roomName)) continue;
				if (dropY > hit.y() + hit.h() / 2.0) targetIndex++;
			}
			drf.reorderItem(ri, targetIndex);
		}
	}

	private static com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature copilotFeatureInstance() {
		com.cokelord.skyblocksimplified.feature.Feature f = com.cokelord.skyblocksimplified.feature.FeatureRegistry.get("dungeons_copilot");
		return f instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature dcf ? dcf : null;
	}

	private static com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature routesFeatureInstance() {
		com.cokelord.skyblocksimplified.feature.Feature f = com.cokelord.skyblocksimplified.feature.FeatureRegistry.get("dungeon_routes");
		return f instanceof com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature drf ? drf : null;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
		if (!interactive) return true;
		// Any open popup must fully block scroll from reaching whatever's behind it, not just clicks —
		// otherwise scrolling while a color/chroma popup is open also scrolled the list underneath it.
		// The Positional Messages dropdown (Classes/Section/Preset) is the one popup whose own content can
		// be taller than itself (see posMsgDropdownScroll's own doc comment), so it gets to consume the
		// scroll itself instead of just swallowing it.
		if (posMsgDropdownIndex != null) {
			posMsgDropdownScroll -= (float) scrollDeltaY * (14f / 2f);
			posMsgDropdownScroll = Math.max(0f, Math.min(maxPosMsgDropdownScroll, posMsgDropdownScroll));
			return true;
		}
		if (isStylePopupOpen()) {
			return true;
		}
		boolean overSidebar = mouseX >= panelX && mouseX <= panelX + SIDEBAR_WIDTH
			&& mouseY >= panelY + HEADER_HEIGHT && mouseY <= panelY + panelHeight;
		if (overSidebar) {
			sidebarScrollTarget -= (float) scrollDeltaY * 22f;
			sidebarScrollTarget = Math.max(0f, Math.min(maxSidebarScrollOffset, sidebarScrollTarget));
		} else {
			scrollTarget -= (float) scrollDeltaY * (ROW_HEIGHT / 2f);
			scrollTarget = Math.max(0f, Math.min(maxScrollOffset, scrollTarget));
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!interactive) return super.charTyped(event);
		if (textFocus == TextFocus.HEX) {
			char c = (char) event.codepoint();
			if (isHexChar(c) && hexFieldBuffer.length() < 6) {
				hexFieldBuffer += c;
				tryCommitHex();
			}
			return true;
		}
		if (isSharedTextFocus(textFocus)) {
			insertAtCursor(event.codepointAsString());
			return true;
		}
		return super.charTyped(event);
	}

	private static boolean isHexChar(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	private void tryCommitHex() {
		if (hexFieldBuffer.length() != 6 || focusedHexKey == null) return;
		Consumer<Integer> setter = hexFieldSetters.get(focusedHexKey);
		if (setter == null) return;
		try {
			int rgb = Integer.parseInt(hexFieldBuffer, 16);
			setter.accept(rgb);
		} catch (NumberFormatException ignored) {
			// leave color unchanged if somehow unparsable
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Combo capture is driven entirely by updateComboCapture()'s raw per-frame poll, not key events —
		// still swallow the event here so it can't also fall through to closing the screen (Escape) or
		// triggering some other shortcut while a combo capture is in progress.
		if (listeningForCombo != null) return true;
		if (listeningForRawKey != null) {
			if (event.key() != InputConstants.KEY_ESCAPE) listeningForRawKey.accept(event.key());
			listeningForRawKey = null;
			return true;
		}
		if (listeningFor != null) {
			if (event.key() == InputConstants.KEY_ESCAPE) {
				if (listeningEscapeUnbinds) {
					listeningFor.setKey(InputConstants.UNKNOWN);
					KeyMapping.resetMapping();
					this.minecraft.options.save();
				}
			} else {
				listeningFor.setKey(InputConstants.Type.KEYSYM.getOrCreate(event.key()));
				KeyMapping.resetMapping();
				this.minecraft.options.save();
			}
			listeningFor = null;
			return true;
		}
		if (textFocus == TextFocus.HEX) {
			if (event.isPaste()) {
				String clipboard = this.minecraft.keyboardHandler.getClipboard();
				StringBuilder sanitized = new StringBuilder();
				for (int i = 0; i < clipboard.length() && sanitized.length() < 6; i++) {
					char c = clipboard.charAt(i);
					if (c == '#') continue;
					if (isHexChar(c)) sanitized.append(c);
				}
				hexFieldBuffer = sanitized.toString();
				tryCommitHex();
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE && !hexFieldBuffer.isEmpty()) {
				hexFieldBuffer = hexFieldBuffer.substring(0, hexFieldBuffer.length() - 1);
				return true;
			}
		}
		// Per user request, the config paste box imports automatically rather than needing a separate
		// Import button click — either the moment a config string is pasted in (Ctrl+V), or on Enter for
		// anyone who typed/edited the buffer by hand instead. Checked before the generic isSharedTextFocus
		// block below so both still get its normal paste-insert/Enter-noop handling too where relevant.
		if (textFocus == TextFocus.CONFIG_IMPORT) {
			if (event.isPaste()) {
				insertAtCursor(this.minecraft.keyboardHandler.getClipboard());
				attemptConfigImport();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				attemptConfigImport();
				return true;
			}
		}
		// Same auto-import-on-paste-or-Enter shape as the whole-mod config box above, scoped to Dungeons
		// Copilot's own plan string.
		if (textFocus == TextFocus.COPILOT_IMPORT) {
			if (event.isPaste()) {
				insertAtCursor(this.minecraft.keyboardHandler.getClipboard());
				attemptCopilotImport();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				attemptCopilotImport();
				return true;
			}
		}
		// Same auto-import-on-paste-or-Enter shape as the whole-mod config box above, scoped to Positional
		// Messages' own export/import string.
		if (textFocus == TextFocus.POSMSG_CONFIG_IMPORT) {
			if (event.isPaste()) {
				insertAtCursor(this.minecraft.keyboardHandler.getClipboard());
				attemptPosMsgConfigImport();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				attemptPosMsgConfigImport();
				return true;
			}
		}
		// Same auto-import-on-paste-or-Enter shape as the whole-mod config box above, scoped to Dungeon
		// Routes' own export/import strings (one all-rooms, one per-room).
		if (textFocus == TextFocus.ROUTE_ALL_IMPORT) {
			if (event.isPaste()) {
				insertAtCursor(this.minecraft.keyboardHandler.getClipboard());
				attemptRouteAllImport();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				attemptRouteAllImport();
				return true;
			}
		}
		if (textFocus == TextFocus.ROUTE_ROOM_IMPORT) {
			if (event.isPaste()) {
				insertAtCursor(this.minecraft.keyboardHandler.getClipboard());
				attemptRouteRoomImport();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				attemptRouteRoomImport();
				return true;
			}
		}
		if (isSharedTextFocus(textFocus)) {
			boolean ctrl = event.hasControlDown();
			boolean shift = event.hasShiftDown();
			if (event.isSelectAll()) {
				selectAllFocused();
				return true;
			}
			if (event.isCopy() || event.isCut()) {
				if (hasSelection()) {
					String current = getFocusedText();
					int start = Math.min(textCursor, textSelectionAnchor);
					int end = Math.max(textCursor, textSelectionAnchor);
					this.minecraft.keyboardHandler.setClipboard(current.substring(start, end));
					if (event.isCut()) backspaceAtCursor();
				}
				return true;
			}
			if (event.isPaste()) {
				insertAtCursor(this.minecraft.keyboardHandler.getClipboard());
				return true;
			}
			if (ctrl && event.key() == InputConstants.KEY_LEFT) {
				moveCursorByWord(false, shift);
				return true;
			}
			if (ctrl && event.key() == InputConstants.KEY_RIGHT) {
				moveCursorByWord(true, shift);
				return true;
			}
			if (event.key() == InputConstants.KEY_LEFT) {
				if (shift) {
					if (textSelectionAnchor < 0) textSelectionAnchor = textCursor;
				} else {
					textSelectionAnchor = -1;
				}
				textCursor = Math.max(0, textCursor - 1);
				return true;
			}
			if (event.key() == InputConstants.KEY_RIGHT) {
				if (shift) {
					if (textSelectionAnchor < 0) textSelectionAnchor = textCursor;
				} else {
					textSelectionAnchor = -1;
				}
				textCursor = Math.min(getFocusedText().length(), textCursor + 1);
				return true;
			}
			// Per user request ("Allow users more quirks of typing when typing in fields, like holding CTRL
			// will delete the word before"): reuses the same word-boundary logic Ctrl+Left/Right already use,
			// then hands off to the existing selection-delete path in backspaceAtCursor (already handles the
			// cursor-delta correction for fields like Command Aliases whose stored value gets transformed —
			// see that method's own doc comment) rather than duplicating it.
			if (ctrl && event.key() == InputConstants.KEY_BACKSPACE) {
				if (!hasSelection()) textSelectionAnchor = findWordBoundary(getFocusedText(), textCursor, false);
				backspaceAtCursor();
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				backspaceAtCursor();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static class CategoryRow {
		final FeatureCategory category;
		final int x, y, width, height;

		CategoryRow(FeatureCategory category, int x, int y, int width, int height) {
			this.category = category;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + width && my >= y && my <= y + height;
		}
	}

	private static class SubTabRow {
		final String subcategory;
		final int x, y, width, height;

		SubTabRow(String subcategory, int x, int y, int width, int height) {
			this.subcategory = subcategory;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + width && my >= y && my <= y + height;
		}
	}

	private static class FeatureRow {
		final Feature feature;
		final int toggleX, toggleY, toggleWidth, toggleHeight;
		final int cogX, cogY, cogSize;
		final boolean hasSettings;
		final boolean cogClickable;
		final boolean toggleable;
		final boolean isColorSwatch;
		final boolean isUpdateButton;
		final int rowX, rowY, rowWidth, rowHeight;

		FeatureRow(Feature feature, int toggleX, int toggleY, int toggleWidth, int toggleHeight,
				   int cogX, int cogY, int cogSize, boolean hasSettings, boolean cogClickable, boolean toggleable, boolean isColorSwatch,
				   int rowX, int rowY, int rowWidth, int rowHeight, boolean isUpdateButton) {
			this.feature = feature;
			this.toggleX = toggleX;
			this.toggleY = toggleY;
			this.toggleWidth = toggleWidth;
			this.toggleHeight = toggleHeight;
			this.cogX = cogX;
			this.cogY = cogY;
			this.cogSize = cogSize;
			this.hasSettings = hasSettings;
			this.cogClickable = cogClickable;
			this.toggleable = toggleable;
			this.isColorSwatch = isColorSwatch;
			this.isUpdateButton = isUpdateButton;
			this.rowX = rowX;
			this.rowY = rowY;
			this.rowWidth = rowWidth;
			this.rowHeight = rowHeight;
		}

		boolean toggleContains(double mx, double my) {
			return mx >= toggleX && mx <= toggleX + toggleWidth && my >= toggleY && my <= toggleY + toggleHeight;
		}

		boolean cogContains(double mx, double my) {
			return mx >= cogX && mx <= cogX + cogSize && my >= cogY && my <= cogY + cogSize;
		}

		boolean rowContains(double mx, double my) {
			return mx >= rowX && mx <= rowX + rowWidth && my >= rowY && my <= rowY + rowHeight;
		}
	}

	private boolean comboSuppressInitialMouse;

	private void startComboCapture(KeyCombo combo, boolean escapeUnbinds) {
		// Per user report: clicking a keybind button while a text field still had focus meant the very key
		// pressed to set the bind also typed straight into that field (e.g. a command box) at the same
		// time. Capturing keyboard input for the combo should always take sole ownership of it.
		if (textFocus != TextFocus.NONE) textFocus = TextFocus.NONE;
		listeningForCombo = combo;
		comboEscapeUnbinds = escapeUnbinds;
		capturingComboKeys.clear();
		comboSuppressInitialMouse = true;
	}

	private String comboCaptureLabel() {
		return capturingComboKeys.isEmpty() ? "press keys..." : "release to confirm";
	}

	/**
	 * Polled once per frame while listeningForCombo is set — raw GLFW state, not key/mouse events, so
	 * it can accumulate multiple simultaneously-held keys and/or mouse buttons. Finalizes (commits the
	 * combo) the moment everything captured so far has been released, per user request: "the button
	 * should only be set when a player has released binds like CTRL, shift, and alt" — this applies
	 * uniformly to any key, not just modifiers, so e.g. Ctrl+F commits once both Ctrl and F let go.
	 * The mouse button used to click the slot open is suppressed from capture until it's been released
	 * once, otherwise merely opening capture would immediately bind "Mouse Left".
	 */
	private void updateComboCapture() {
		if (listeningForCombo == null) return;
		long handle = Minecraft.getInstance().getWindow().handle();

		if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
			if (comboEscapeUnbinds) {
				listeningForCombo.setKeys(Set.of());
				ConfigManager.save();
			}
			listeningForCombo = null;
			capturingComboKeys.clear();
			return;
		}

		boolean anyHeld = false;
		for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
			if (key == GLFW.GLFW_KEY_ESCAPE) continue;
			if (GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS) {
				capturingComboKeys.add(InputConstants.Type.KEYSYM.getOrCreate(key));
				anyHeld = true;
			}
		}
		boolean leftMouseDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		if (!leftMouseDown) comboSuppressInitialMouse = false;
		for (int button = GLFW.GLFW_MOUSE_BUTTON_1; button <= GLFW.GLFW_MOUSE_BUTTON_8; button++) {
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && comboSuppressInitialMouse) continue;
			if (GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS) {
				capturingComboKeys.add(InputConstants.Type.MOUSE.getOrCreate(button));
				anyHeld = true;
			}
		}

		if (!anyHeld && !capturingComboKeys.isEmpty()) {
			listeningForCombo.setKeys(new LinkedHashSet<>(capturingComboKeys));
			ConfigManager.save();
			listeningForCombo = null;
			capturingComboKeys.clear();
		}
	}

	private static class ComboRow {
		final KeyCombo combo;
		final int x, y, width, height;

		ComboRow(KeyCombo combo, int x, int y, int width, int height) {
			this.combo = combo;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + width && my >= y && my <= y + height;
		}
	}

	private static class InlineKeybindRow {
		final KeyMapping keyMapping;
		final int x, y, width, height;

		InlineKeybindRow(KeyMapping keyMapping, int x, int y, int width, int height) {
			this.keyMapping = keyMapping;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + width && my >= y && my <= y + height;
		}
	}

	private static class SlotRow {
		final KeyMapping keyMapping;
		final int x, y, width, height;
		final boolean escapeUnbinds;

		SlotRow(KeyMapping keyMapping, int x, int y, int width, int height, boolean escapeUnbinds) {
			this.keyMapping = keyMapping;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.escapeUnbinds = escapeUnbinds;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + width && my >= y && my <= y + height;
		}
	}

	/** A draggable slider region. vertical ? value increases bottom-to-top : value increases left-to-right.
	 *  x/y/width/height are the clickable hit region — some call sites pad this out past the visible
	 *  track for an easier grab target. trackX/trackY/trackWidth/trackHeight are the ACTUAL rendered
	 *  track bounds, used only for the value math in applyAt — mapping a drag position against the padded
	 *  hit region instead of the true track (as this used to do unconditionally) skews the computed value
	 *  by the padding amount, which reads as the knob/fill sitting offset from the cursor. */
	private static class SliderHit {
		final int x, y, width, height;
		final int trackX, trackY, trackWidth, trackHeight;
		final boolean vertical;
		final Consumer<Float> apply;
		final String key;

		SliderHit(int x, int y, int width, int height, boolean vertical, Consumer<Float> apply, String key) {
			this(x, y, width, height, x, y, width, height, vertical, apply, key);
		}

		SliderHit(int x, int y, int width, int height, int trackX, int trackY, int trackWidth, int trackHeight,
				  boolean vertical, Consumer<Float> apply, String key) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.trackX = trackX;
			this.trackY = trackY;
			this.trackWidth = trackWidth;
			this.trackHeight = trackHeight;
			this.vertical = vertical;
			this.apply = apply;
			this.key = key;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + width && my >= y && my <= y + height;
		}

		void applyAt(double mx, double my) {
			float value = vertical
				? clamp01(1f - (float) (my - trackY) / trackHeight)
				: clamp01((float) (mx - trackX) / trackWidth);
			apply.accept(value);
		}
	}

	/** A generic clickable rectangle running an arbitrary action — used for tab buttons, toggles, and
	 *  cycle buttons in the Custom Scoreboard's Style tab, instead of a dedicated hit-test class per kind. */
	private static class ClickHit {
		final int x, y, width, height;
		final Runnable action;

		ClickHit(int x, int y, int width, int height, Runnable action) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.action = action;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + width && my >= y && my <= y + height;
		}
	}

	/** Non-static: writes directly to the enclosing screen's pickerHue/pickerSat fields rather than
	 *  round-tripping through the feature's stored RGB, which is what caused the marker-snapping bug
	 *  near the hue 0/360 wrap (see the field comment on pickerHue). */
	private class ColorSquareHit {
		final int x, y, size;
		final String key;
		final java.util.function.IntSupplier getColor;
		final Consumer<Integer> setColor;

		ColorSquareHit(int x, int y, int size, String key, java.util.function.IntSupplier getColor, Consumer<Integer> setColor) {
			this.x = x;
			this.y = y;
			this.size = size;
			this.key = key;
			this.getColor = getColor;
			this.setColor = setColor;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + size && my >= y && my <= y + size;
		}

		void applyAt(double mx, double my) {
			pickerHue = clamp01((float) (mx - x) / size) * 360f;
			pickerSat = 1f - clamp01((float) (my - y) / size);
			int current = getColor.getAsInt();
			float v = rgbToHsv(current)[2];
			int alpha = current & 0xFF000000;
			setColor.accept(alpha | (hsvToRgb(pickerHue, pickerSat, v) & 0xFFFFFF));
		}
	}
}
