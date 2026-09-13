package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.LobbyIdTracker;
import com.cokelord.skyblocksimplified.dungeon.TerminalTracker;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.network.WebSocketClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Movable HUD element showing party Melody terminal progress. Two modes, both gated by the feature's own
 * enable toggle:
 *
 * <p><b>Chat-parsed alert (default)</b> — shows a party member's Melody progress the instant they broadcast
 * it in party chat, per user request ("i want a gui element to display when it detects that someone in the
 * party has melody"), ported from NoammAddons' confirmed {@code MelodyDisplay.kt}. Real party-chat text
 * parsing only (a "Party &gt; Name: ... X/4 ..." or "... NN% ..." line) — nothing here sends packets, clicks
 * anything, or reads any data the player couldn't already read by watching party chat themselves.
 *
 * <p><b>Broadcast Progress subtoggle</b> — per the relay owner's own explicit go-ahead ("linking to the
 * websocket was okay as long as I didn't rate limit it or do anything stupid"), replaces the chat-parsed
 * single-alert display with a real live view over Odin's own hosted relay ({@code wss://ws.odtheking.com}),
 * 1:1 protocol port of Odin's {@code MelodyMessage.kt}: connects on the real P3 boss line keyed to
 * {@link LobbyIdTracker}'s real lobby code, sends a JSON {@code {username,type,slot}} update on every real
 * slot change, and disconnects on the real "Core entrance" line or on leaving the world. Per user request
 * ("mix it with the party melody display... make it render the melody display if on instead of the one we
 * had before") this REPLACES the chat-parsed alert's render output entirely while active — they're two
 * mutually-exclusive display modes on the same widget, not two separate GUI elements.
 *
 * <p>Real design correction (per user report — "The melody display isnt quite how i envisioned it... Since
 * only one melody can be active at the same time, it should only render when a melody is active on the
 * websocket... It should only display a single one (no text)... It should display exactly like the custom
 * melody terminal does in our mod"): only one real Melody terminal (and therefore only one real solver) can
 * ever be active in a dungeon at once, so a prior round's multi-row text table (player name/class label per
 * row) was solving a problem that doesn't exist — replaced with a single reskin of
 * {@link TerminalSolverFeature}'s own {@code renderMelody} squircle look (see {@link #renderMelodyPanel}):
 * the black rounded panel, the fixed target squircle, the moving squircle, and the 4 fixed button squircles
 * — no text, no per-player labels, nothing else. Drawn directly from whatever the latest broadcast message
 * says (per user report — "the display doesnt have to animate, it just moves two squares per second. No
 * need for that"): the real relay already sends a fresh column the instant the real terminal's own marker
 * moves, so no local interpolation is needed on top of it.
 */
public class MelodyDisplayFeature extends Feature implements MoveableWidget {
	private static final Pattern MELODY_PARTY_LINE = Pattern.compile("^Party > (?:\\[[^]]+]\\s)?(\\w{1,16}):.*$");
	private static final Pattern PROGRESS_FRACTION = Pattern.compile("([0-4])/4");
	private static final Pattern PROGRESS_PERCENT = Pattern.compile("(25|50|75|100)%");

	private float alertDurationSeconds = 2.5f;
	private boolean soundEnabled = true;
	// Per user request ("&h means custom color, that the user can choose in the settings for that module"):
	// used for the player's own name and the punctuation around the count fraction (see render's own
	// buildSegments) — everything that isn't tied to a real class color or the fixed count-threshold colors.
	private int customColor = 0xFFFF55FF;

	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.15f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private String displayName = null;
	private DungeonClass displayClass = DungeonClass.EMPTY;
	private int displayCount = 0;
	private ItemStack displayIcon = ItemStack.EMPTY;
	private long shownAtMillis = 0L;

	// --- Broadcast Progress (real relay integration) ---
	private static final String RELAY_URL = "wss://ws.odtheking.com/";
	private static final Pattern CONTROL_CODES = Pattern.compile("§.");
	private static final Pattern P3_START = Pattern.compile("(?s).*\\[BOSS] Goldor: Who dares trespass into my domain\\?.*");
	private static final Pattern CORE_OPENING = Pattern.compile("(?s).*The Core entrance is opening!.*");
	// Row 2/3/4 = attempt 1/2/3 out of 4 (row 1 is the very start) — matches TerminalTracker.MelodyState's
	// own "movingRow 1..4 = attempt 0/4..3/4" mapping.
	private static final Map<Integer, Integer> CLAY_ROW_ATTEMPT = Map.of(2, 1, 3, 2, 4, 3);

	// Off by default — opens a real network connection to a third party's server the instant it's turned on.
	private boolean broadcastProgress = false;

	private Integer lastClayRow = null;
	private Integer lastSentPurple = null;
	private Integer lastSentPane = null;
	private boolean websocketActive = false;

	private final WebSocketClient webSocket = new WebSocketClient();
	// Real Hypixel constraint (per user report): only one Melody terminal can ever be open in a dungeon at
	// once, so this only ever holds (at most) one real entry — kept as a map rather than a single field only
	// because the relay's own "leave" message (type 0) is keyed by username, the same shape every other
	// per-player relay message uses.
	private final Map<String, MelodyData> melodies = new ConcurrentHashMap<>();

	/** purple = target column (0-4, row 0's fixed marker), pane = moving column (0-4, whichever attempt row
	 *  is active), clay = the active attempt row itself (1-4) — all three straight off Odin's own real
	 *  {@code MelodyMessage.kt} protocol (see this class' onSocketMessage), rendered directly with no local
	 *  smoothing (per user report — "the display doesnt have to animate, it just moves two squares per
	 *  second. No need for that": the real relay already sends a fresh column the instant it changes, at the
	 *  real terminal's own pace, so drawing whatever the latest message says is already correct). */
	private static final class MelodyData {
		Integer purple;
		Integer pane;
		Integer clay;
	}

	private static MelodyDisplayFeature instance;
	private static boolean listenersRegistered = false;

	public MelodyDisplayFeature() {
		super("melody_display", "Melody Display", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			LobbyIdTracker.ensureRegistered();

			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance == null || !instance.isEnabled()) return true;
				String plain = message.getString().replaceAll("§.", "");
				instance.onChatMessage(plain);
				if (instance.broadcastProgress) instance.onBroadcastChatMessage(plain);
				return true;
			});

			TerminalTracker.addOpenListener(type -> {
				if (instance == null || !instance.isEnabled() || !instance.broadcastProgress || type != TerminalTracker.TerminalType.MELODY) return;
				if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return;
				instance.lastClayRow = null;
				instance.lastSentPurple = null;
				instance.lastSentPane = null;
			});

			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance == null || !instance.isEnabled() || !instance.broadcastProgress) return;
				instance.broadcastTick(client);
			});

			Identifier melodyId = Identifier.fromNamespaceAndPath("skyblocksimplified", "melody_display");
			net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement melodyElement = (graphics, tracker) -> {
				if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
					|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.render(graphics, x, y, instance.position.scale);
			};
			// Real bug found (per user report — "it also seems to render under the map but above the outline
			// of the map, make it not do that"): both this element and Dungeon Map's own were registered via
			// plain addLast, which only renders on top of whatever was already registered earlier — purely a
			// function of which feature happened to enable() first, not any deliberate ordering. Same real fix
			// already used for Dungeon Notifications' own identical complaint against Dungeon Map (see that
			// feature's own onEnable doc comment): attachElementAfter Dungeon Map's own id makes this always
			// render on top of it regardless of enable order, falling back to plain addLast if Dungeon Map's
			// id isn't registered at all (module disabled) — attachElementAfter throws on a missing anchor.
			try {
				HudElementRegistry.attachElementAfter(
					Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeon_map"), melodyId, melodyElement);
			} catch (Exception e) {
				HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, melodyId, melodyElement);
			}

			webSocket.onMessage(MelodyDisplayFeature::onSocketMessage);

			ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
				if (instance != null) instance.resetWebSocket();
			});
		}
	}

	private void onChatMessage(String text) {
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return;
		Matcher lineMatch = MELODY_PARTY_LINE.matcher(text);
		if (!lineMatch.matches()) return;
		String name = lineMatch.group(1);

		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && name.equals(mc.player.getName().getString())) return;

		Integer progress = null;
		Matcher fraction = PROGRESS_FRACTION.matcher(text);
		if (fraction.find()) progress = Integer.parseInt(fraction.group(1));
		else {
			Matcher percent = PROGRESS_PERCENT.matcher(text);
			if (percent.find()) progress = Integer.parseInt(percent.group(1)) / 25;
		}
		if (progress == null) return;

		DungeonClass clazz = DungeonClass.EMPTY;
		for (DungeonPlayer teammate : DungeonState.getTeammates()) {
			if (teammate.name.equals(name)) { clazz = teammate.clazz; break; }
		}

		displayName = name;
		displayClass = clazz;
		displayCount = progress;
		displayIcon = classIcon(clazz);
		shownAtMillis = System.currentTimeMillis();
		if (soundEnabled && mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f);
	}

	// --- Broadcast Progress: real relay connect/send/receive, 1:1 port of Odin's MelodyMessage.kt ---

	private void onBroadcastChatMessage(String plain) {
		if (P3_START.matcher(plain).matches()) {
			String lobbyId = LobbyIdTracker.getLobbyId();
			if (lobbyId != null) {
				webSocket.connect(RELAY_URL + lobbyId);
				melodies.clear();
			}
		} else if (CORE_OPENING.matcher(plain).matches()) {
			resetWebSocket();
		}
	}

	private void resetWebSocket() {
		webSocket.shutdown();
		melodies.clear();
		websocketActive = false;
	}

	private static void onSocketMessage(String json) {
		if (instance == null) return;
		try {
			JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
			String username = obj.get("username").getAsString();
			int type = obj.get("type").getAsInt();
			int slot = obj.get("slot").getAsInt();
			if (type == 0) { instance.melodies.remove(username); return; }

			// Per user report ("The melody display shows for the person doing the melody aswell"): the local
			// player is the one calling sendUpdate() while they're the one actually in the terminal, and this
			// relay is a shared broadcast channel — DungeonState.getTeammates() includes the local player, so
			// their own username was ending up as a key in melodies and rendering exactly like a teammate's.
			// Only accept updates for a real current teammate OTHER than the local player — same legitimacy
			// check the relay's own sender side relies on (Odin's MelodyMessage.kt looks the sender up in
			// dungeonTeammates too), guards against acting on stray/malformed relay data for someone not
			// actually in this party, now combined with excluding the local player's own broadcast echo.
			Minecraft mcForTeammateCheck = Minecraft.getInstance();
			if (mcForTeammateCheck.player != null && username.equals(mcForTeammateCheck.player.getName().getString())) return;
			boolean isTeammate = false;
			for (DungeonPlayer teammate : DungeonState.getTeammates()) {
				if (teammate.name.equals(username)) { isTeammate = true; break; }
			}
			if (!isTeammate) return;

			MelodyData data = instance.melodies.computeIfAbsent(username, k -> new MelodyData());
			switch (type) {
				case 1 -> data.clay = slot;
				case 2 -> data.purple = slot;
				case 5 -> data.pane = slot;
				default -> { }
			}
		} catch (Exception ignored) { }
	}

	private void broadcastTick(Minecraft client) {
		if (TerminalTracker.getCurrentType() != TerminalTracker.TerminalType.MELODY) {
			if (websocketActive) {
				sendUpdate(0, 0);
				websocketActive = false;
			}
			return;
		}
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return;
		websocketActive = true;
		if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;

		var items = screen.getMenu().getItems();
		for (int i = 0; i < items.size(); i++) {
			ItemStack item = items.get(i);
			if (!isLimeTerracotta(item)) continue;
			int row = i / 9;
			if (lastClayRow == null || lastClayRow != row) {
				lastClayRow = row;
				sendUpdate(1, row);
			}
			break;
		}

		TerminalTracker.MelodyState state = TerminalTracker.getMelodyState();
		if (state != null) {
			int purpleIndex = state.targetColumn() - 1;
			int paneIndex = state.movingColumn() - 1;
			if (purpleIndex >= 0 && purpleIndex <= 4 && (lastSentPurple == null || lastSentPurple != purpleIndex)) {
				lastSentPurple = purpleIndex;
				sendUpdate(2, purpleIndex);
			}
			if (paneIndex >= 0 && paneIndex <= 4 && (lastSentPane == null || lastSentPane != paneIndex)) {
				lastSentPane = paneIndex;
				sendUpdate(5, paneIndex);
			}
		}
	}

	private void sendUpdate(int type, int slot) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		JsonObject obj = new JsonObject();
		obj.addProperty("username", mc.player.getName().getString());
		obj.addProperty("type", type);
		obj.addProperty("slot", slot);
		webSocket.send(obj.toString());
	}

	// No compile-time Items.LIME_TERRACOTTA constant exists for every color variant in this MC version
	// (same workaround as RareRewardWarningFeature.isRareReward / DungeonRoom.isBlueTerracotta).
	private static boolean isLimeTerracotta(ItemStack stack) {
		return "minecraft:lime_terracotta".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	private boolean active() {
		return displayName != null && System.currentTimeMillis() - shownAtMillis < Math.round(alertDurationSeconds * 1000);
	}

	/** Real, confirmed per-class chat colors this codebase already established in
	 *  {@code PartyFinderFeature#classColor} — duplicated here (small, self-contained) rather than shared
	 *  since that method is private to a different, otherwise-unrelated feature. */
	private static int classColor(DungeonClass clazz) {
		return switch (clazz) {
			case HEALER -> 0xFFFF55FF;
			case MAGE -> 0xFF55FFFF;
			case BERSERK -> 0xFFFF5555;
			case ARCHER -> 0xFFFFAA00;
			case TANK -> 0xFFAAAAAA;
			case EMPTY -> 0xFFFFFFFF;
		};
	}

	/** Same count->color rule the user specified for Leap Counter's own count text ("0 should always be
	 *  dark red... 1... regular red... 2... yellow... [hitting the max]... lime green") — duplicated here
	 *  (see {@code LeapCounterFeature#countColor}) rather than shared since both are small, self-contained,
	 *  four-line switches. Melody's max is always 4 (a real fixed Hypixel constant — the terminal has
	 *  exactly 4 rows), unlike Leap Counter's own per-zone variable requirement. */
	private static int countColor(int count) {
		if (count <= 0) return 0xFFAA0000;
		if (count == 1) return 0xFFFF5555;
		if (count >= 4) return 0xFF55FF55;
		return 0xFFFFFF55;
	}

	public int getCustomColor() { return customColor; }
	public void setCustomColor(int value) { customColor = value; }
	public boolean isBroadcastProgress() { return broadcastProgress; }
	public void setBroadcastProgress(boolean value) {
		broadcastProgress = value;
		if (!value) resetWebSocket();
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	// Real bug found (per user follow-up correcting last round's fix — "the melody terminal should not check
	// if a terminal is open, just if the username matches the user so it doesnt show for the user aswell. It
	// should also show during a terminal"): gating visibility on TerminalTracker's "is a melody terminal
	// screen currently open" state was wrong — the display should keep showing DURING a terminal (for
	// teammates' progress), it should just never show the LOCAL PLAYER'S OWN name/progress. That's already
	// handled purely by the username-equality check in onSocketMessage below (and onChatMessage's own
	// pre-existing one) — no visibility gating needed here at all.
	@Override
	public boolean isVisible() {
		if (!isEnabled()) return false;
		if (!DungeonState.isInDungeon()) return false;
		return broadcastProgress ? !melodies.isEmpty() : active();
	}

	@Override
	public boolean isRelevantToCurrentIsland() { return DungeonState.isInDungeon(); }

	@Override
	public boolean hasVisibleContent() {
		return broadcastProgress ? !melodies.isEmpty() : active();
	}

	/** Only one real Melody terminal (and therefore only one real broadcaster) can ever be active at once —
	 *  see this class' own doc comment — so this just returns whichever single entry currently exists,
	 *  {@code null} if none. */
	private MelodyData currentBroadcastMelody() {
		for (MelodyData data : melodies.values()) return data;
		return null;
	}

	// One (text, color) piece of the rendered line — see buildSegments' own doc comment for the real layout
	// this follows.
	private record Segment(String text, int color) {}

	/** Per user request (real Minecraft §-code shorthand they gave: "&o means follow class color, &h means
	 *  custom color... &o(Class)&hUsername has &dmelody! &h(&40&h/&a4)"): builds the line as real colored
	 *  segments instead of one flat color — class name in the real class color, the player's own name and
	 *  the surrounding punctuation in the user's configurable custom color, "Melody" in a fixed light
	 *  purple, and the count digit colored by how close it is to the real fixed max of 4 (see countColor). */
	private List<Segment> buildSegments(String name, DungeonClass clazz, int count) {
		String className = clazz != DungeonClass.EMPTY
			? clazz.name().charAt(0) + clazz.name().substring(1).toLowerCase(Locale.ROOT)
			: "Unknown";
		return List.of(
			new Segment("(" + className + ") ", classColor(clazz)),
			new Segment(name, customColor),
			new Segment(" has ", 0xFFFFFFFF),
			new Segment("Melody", 0xFFFF55FF),
			new Segment(" (", customColor),
			new Segment(String.valueOf(count), countColor(count)),
			new Segment("/", customColor),
			new Segment("4", 0xFF55FF55),
			new Segment(")", customColor)
		);
	}

	// --- Broadcast Progress panel: a compact reskin of TerminalSolverFeature's own real melody-terminal
	// squircle rendering (see this class' own doc comment for why a text/label table was replaced with this),
	// driven by whichever single MelodyData currentBroadcastMelody() returns. ---

	private static final int MELODY_PANEL_PURPLE = 0xFFCC33FF;
	private static final int MELODY_PANEL_GREEN = 0xFF55FF55;
	private static final int MELODY_PANEL_RED = 0xFFFF5555;
	private static final int MELODY_PANEL_RADIUS = 8;
	private static final int MELODY_SQUIRCLE_RADIUS = 4;
	private static final int MELODY_CELL = 16;
	private static final int MELODY_PITCH = 18;
	private static final int MELODY_PANEL_MARGIN = 4;
	// Columns per attempt row (0-4, straight off the real relay protocol's own 0-4 range — see
	// onSocketMessage/mapToRange in the real Odin source this was ported from) plus a 2-cell gap before the
	// single button column, mirroring the real terminal's own "button sits off to the side" layout.
	private static final int MELODY_PANEL_WIDTH = MELODY_PANEL_MARGIN * 2 + 6 * MELODY_PITCH + MELODY_CELL;
	private static final int MELODY_PANEL_HEIGHT = MELODY_PANEL_MARGIN * 2 + 4 * MELODY_PITCH + MELODY_CELL;

	private static int melodyColX(int column) { return MELODY_PANEL_MARGIN + column * MELODY_PITCH; }
	private static int melodyRowY(int row) { return MELODY_PANEL_MARGIN + row * MELODY_PITCH; }
	private static final int MELODY_BUTTON_COL_X = MELODY_PANEL_MARGIN + 6 * MELODY_PITCH;

	private Size renderMelodyPanel(GuiGraphicsExtractor graphics, int x, int y, float scale, MelodyData data) {
		boolean scaled = Math.abs(scale - 1f) >= 0.01f;
		if (scaled) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
		}
		int ox = scaled ? 0 : x;
		int oy = scaled ? 0 : y;

		RenderUtil.fillRounded(graphics, ox, oy, ox + MELODY_PANEL_WIDTH, oy + MELODY_PANEL_HEIGHT, MELODY_PANEL_RADIUS, 0xFF000000);

		// The fixed target squircle — row 0's real magenta marker, the column the player has to align to.
		if (data.purple != null) {
			int px = ox + melodyColX(data.purple);
			int py = oy + melodyRowY(0);
			RenderUtil.fillRounded(graphics, px, py, px + MELODY_CELL, py + MELODY_CELL, MELODY_SQUIRCLE_RADIUS, MELODY_PANEL_PURPLE);
			RenderUtil.fillRoundedRing(graphics, px, py, px + MELODY_CELL, py + MELODY_CELL, MELODY_SQUIRCLE_RADIUS, 1, 0x80FFFFFF);
		}

		// The moving squircle — only drawn once we actually know both which attempt row is active and where
		// the marker currently sits in it, rendered directly at the latest broadcast column (no local
		// smoothing — see this class' own doc comment for why).
		if (data.clay != null && data.pane != null) {
			int mx = ox + melodyColX(data.pane);
			int my = oy + melodyRowY(data.clay);
			RenderUtil.fillRounded(graphics, mx, my, mx + MELODY_CELL, my + MELODY_CELL, MELODY_SQUIRCLE_RADIUS, MELODY_PANEL_PURPLE);
			RenderUtil.fillRoundedRing(graphics, mx, my, mx + MELODY_CELL, my + MELODY_CELL, MELODY_SQUIRCLE_RADIUS, 1, 0x80FFFFFF);
		}

		// The 4 fixed button squircles — always shown (matching the real terminal, where all 4 are visible
		// the whole time), the currently active one green/red per whether the columns line up, the other 3
		// always red (matching TerminalSolverFeature's own renderMelody).
		boolean aligned = data.purple != null && data.pane != null && data.purple.intValue() == data.pane.intValue();
		for (int row = 1; row <= 4; row++) {
			int bx = ox + MELODY_BUTTON_COL_X;
			int by = oy + melodyRowY(row);
			int color = (data.clay != null && row == data.clay && aligned) ? MELODY_PANEL_GREEN : MELODY_PANEL_RED;
			RenderUtil.fillRounded(graphics, bx, by, bx + MELODY_CELL, by + MELODY_CELL, MELODY_SQUIRCLE_RADIUS, color);
			RenderUtil.fillRoundedRing(graphics, bx, by, bx + MELODY_CELL, by + MELODY_CELL, MELODY_SQUIRCLE_RADIUS, 1, 0x80FFFFFF);
		}

		if (scaled) graphics.pose().popMatrix();
		return new Size(Math.round(MELODY_PANEL_WIDTH * scale), Math.round(MELODY_PANEL_HEIGHT * scale));
	}

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		if (broadcastProgress) {
			MelodyData data = currentBroadcastMelody();
			// Nothing broadcasting right now (broadcastProgress on but melodies empty) — per user request
			// ("it should only render when a melody is active on the websocket"), draw nothing at all rather
			// than an empty panel.
			if (data == null) return new Size(0, 0);
			return renderMelodyPanel(graphics, x, y, scale, data);
		}

		// Real bug found (per user report — "Melody display is not in its gui element box"): this drew text
		// CENTERED on x (x - width/2) while still reporting a Size/box as if it were left-aligned starting
		// at x — the same left-x-is-the-real-left-edge convention every other MoveableWidget in this codebase
		// (Speed Percentage, Network Display, Dungeon Notifications, etc.) actually follows, and the one the
		// GUI editor's drag/selection hitbox assumes. The rendered text sat up to half its own width to the
		// LEFT of its own reported box. Now drawn left-aligned at x like everything else, matching the box.
		Font font = Minecraft.getInstance().font;
		boolean isActive = active();
		List<Segment> segments = isActive
			? buildSegments(displayName, displayClass, displayCount)
			: buildSegments("PlayerName", DungeonClass.ARCHER, 4);
		// Per user request ("show the player's class icon"): the same real per-class item icon
		// LeapMenuFeature's own classIcon() already uses (bow/potion/chestplate/rod/sword) — a fixed Archer
		// bow for the example preview, matching the example segments above.
		ItemStack icon = isActive ? displayIcon : classIcon(DungeonClass.ARCHER);
		boolean hasIcon = !icon.isEmpty();
		int iconWidth = hasIcon ? 18 : 0;
		int textWidth = 0;
		for (Segment s : segments) textWidth += font.width(s.text());
		int width = iconWidth + textWidth;

		if (Math.abs(scale - 1f) < 0.01f) {
			if (hasIcon) graphics.item(icon, x, y - 4);
			int textX = x + iconWidth;
			for (Segment s : segments) { graphics.text(font, s.text(), textX, y, s.color()); textX += font.width(s.text()); }
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			if (hasIcon) graphics.item(icon, 0, -4);
			int textX = iconWidth;
			for (Segment s : segments) { graphics.text(font, s.text(), textX, 0, s.color()); textX += font.width(s.text()); }
			graphics.pose().popMatrix();
		}
		return new Size(Math.round(width * scale), Math.round(font.lineHeight * scale));
	}

	@Override
	public Size renderExample(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		if (broadcastProgress) {
			MelodyData example = new MelodyData();
			example.purple = 2;
			example.pane = 3;
			example.clay = 2;
			return renderMelodyPanel(graphics, x, y, scale, example);
		}
		return render(graphics, x, y, scale);
	}

	/** Same real per-class item icon LeapMenuFeature's own {@code classIcon()} uses, duplicated here rather
	 *  than shared since that one is private to that class and this is a small, self-contained switch. */
	private static ItemStack classIcon(DungeonClass clazz) {
		return switch (clazz) {
			case ARCHER -> new ItemStack(net.minecraft.world.item.Items.BOW);
			case HEALER -> new ItemStack(net.minecraft.world.item.Items.LINGERING_POTION);
			case TANK -> {
				ItemStack stack = new ItemStack(net.minecraft.world.item.Items.LEATHER_CHESTPLATE);
				stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(0x808080));
				yield stack;
			}
			case MAGE -> new ItemStack(net.minecraft.world.item.Items.BLAZE_ROD);
			case BERSERK -> new ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
			case EMPTY -> ItemStack.EMPTY;
		};
	}

	public float getAlertDurationSeconds() { return alertDurationSeconds; }
	public void setAlertDurationSeconds(float value) { alertDurationSeconds = Math.max(0.5f, Math.min(10f, value)); }
	public boolean isSoundEnabled() { return soundEnabled; }
	public void setSoundEnabled(boolean value) { soundEnabled = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("alertDurationSeconds", alertDurationSeconds);
		obj.addProperty("soundEnabled", soundEnabled);
		obj.addProperty("customColor", customColor);
		obj.addProperty("broadcastProgress", broadcastProgress);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("alertDurationSeconds")) alertDurationSeconds = obj.get("alertDurationSeconds").getAsFloat();
		if (obj.has("soundEnabled")) soundEnabled = obj.get("soundEnabled").getAsBoolean();
		if (obj.has("customColor")) customColor = obj.get("customColor").getAsInt();
		if (obj.has("broadcastProgress")) broadcastProgress = obj.get("broadcastProgress").getAsBoolean();
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
	}

	@Override
	public String getDescription() {
		return "Movable HUD element showing your party's live progress on the Melody terminal.";
	}
}
