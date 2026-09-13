package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** F7 boss-fight device-phase (P3) helper — highlights not-yet-activated terminals/devices/levers and
 *  shows a small HUD readout of how many of each remain in the current section. Ported from Odin's
 *  {@code InactiveWaypoints.kt}. */
public class InactiveWaypointsFeature extends Feature {
	private static final Pattern COMPLETED_PATTERN = Pattern.compile("^(.{1,16}) (?:activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)$");
	private static final Pattern GOLDOR_PATTERN = Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");
	private static final Pattern CORE_OPENING_PATTERN = Pattern.compile("^The Core entrance is opening!$");
	private static final Pattern GATE_PATTERN = Pattern.compile("^The gate has been destroyed!$");

	private boolean showTerminals = true;
	private boolean showDevices = true;
	private boolean showLevers = true;
	private boolean renderText = true;
	// Per user request ("I don't want it to be beacons or anything, just show a highlight... and place a
	// text"): defaults off now, matching what they actually want to see rather than Odin's own default.
	private boolean renderBeacon = false;
	private boolean renderBox = true;
	private boolean hideDefaultNames = true;
	private int color = 0x66FFFF55;
	// Per user request: a subtoggle that filters the highlight down to only the terminals/levers/device the
	// player's own class is actually responsible for that section, using a verbatim per-class assignment
	// list the user provided (see SYNC_TABLE below) — off by default since it's an opinionated filter on top
	// of the plain "everything still inactive" view the toggles above already give.
	private boolean syncWithClass = false;
	// Merged in from the now-deleted TerminalHitboxesFeature (per user request — "The terminal hitboxes can
	// be a part of the 'inactive waypoints' module. Make it into a subtoggle... that shows the terminal
	// hitbox as the inactive terminals highlight"): when on, TERMINAL-kind stands draw the ArmorStand's own
	// real getBoundingBox() (the exact, much smaller hitbox Hypixel's server actually resolves a click
	// against) instead of the synthetic full-block box every other inactive stand still uses.
	private boolean terminalHitboxAsHighlight = false;

	private enum StandKind { TERMINAL, DEVICE, LEVER }

	/** One class+section's assigned terminal/lever, with an approximate world position to match against —
	 *  null x/y/z means "any stand of this kind counts" (e.g. "both levers", or a device with no specific
	 *  coordinate given). Positions are matched with a generous tolerance since the user's own list gives
	 *  them as "around X, Y, Z", not exact block coordinates. */
	private record SyncEntry(StandKind kind, Double x, Double y, Double z) {
		private static SyncEntry any(StandKind kind) { return new SyncEntry(kind, null, null, null); }
		private static SyncEntry at(StandKind kind, double x, double y, double z) { return new SyncEntry(kind, x, y, z); }
		boolean matches(StandKind standKind, Vec3 pos) {
			if (standKind != kind) return false;
			if (x == null) return true;
			return Math.abs(pos.x - x) <= SYNC_TOLERANCE && Math.abs(pos.y - y) <= SYNC_TOLERANCE && Math.abs(pos.z - z) <= SYNC_TOLERANCE;
		}
	}

	private static final double SYNC_TOLERANCE = 6.0;

	// Verbatim per-user list of which terminal/lever/device each class handles per F7 P3 section — the user's
	// own words: "a list of the terminals all players do during the terminals phase." Coordinates as given,
	// approximate. Section 2 has 5 terminals (matches this class's own existing requiredTerminals logic
	// below), every other section has 4.
	private static final Map<DungeonClass, Map<Integer, List<SyncEntry>>> SYNC_TABLE = buildSyncTable();

	private static Map<DungeonClass, Map<Integer, List<SyncEntry>>> buildSyncTable() {
		Map<DungeonClass, Map<Integer, List<SyncEntry>>> table = new EnumMap<>(DungeonClass.class);

		Map<Integer, List<SyncEntry>> healer = new java.util.HashMap<>();
		healer.put(1, List.of(SyncEntry.any(StandKind.DEVICE)));
		healer.put(2, List.of(SyncEntry.at(StandKind.LEVER, 23, 132, 138)));
		healer.put(3, List.of(SyncEntry.at(StandKind.TERMINAL, 2, 119, 93), SyncEntry.any(StandKind.DEVICE)));
		healer.put(4, List.of(SyncEntry.at(StandKind.TERMINAL, 72, 115, 47)));
		table.put(DungeonClass.HEALER, healer);

		Map<Integer, List<SyncEntry>> mage = new java.util.HashMap<>();
		mage.put(1, List.of(SyncEntry.any(StandKind.LEVER)));
		mage.put(2, List.of(SyncEntry.at(StandKind.TERMINAL, 59, 120, 123)));
		mage.put(3, List.of());
		mage.put(4, List.of());
		table.put(DungeonClass.MAGE, mage);

		Map<Integer, List<SyncEntry>> berserk = new java.util.HashMap<>();
		berserk.put(1, List.of());
		berserk.put(2, List.of(SyncEntry.at(StandKind.TERMINAL, 47, 109, 122), SyncEntry.at(StandKind.TERMINAL, 39, 108, 143)));
		berserk.put(3, List.of(SyncEntry.at(StandKind.TERMINAL, 19, 123, 93)));
		berserk.put(4, List.of(SyncEntry.at(StandKind.TERMINAL, 69, 109, 29), SyncEntry.any(StandKind.LEVER)));
		table.put(DungeonClass.BERSERK, berserk);

		Map<Integer, List<SyncEntry>> archer = new java.util.HashMap<>();
		archer.put(1, List.of(SyncEntry.at(StandKind.TERMINAL, 89, 122, 101), SyncEntry.at(StandKind.TERMINAL, 89, 112, 92)));
		archer.put(2, List.of(SyncEntry.at(StandKind.TERMINAL, 40, 124, 122), SyncEntry.at(StandKind.LEVER, 27, 124, 127)));
		archer.put(3, List.of(SyncEntry.at(StandKind.TERMINAL, -3, 109, 77), SyncEntry.any(StandKind.LEVER)));
		archer.put(4, List.of(SyncEntry.at(StandKind.TERMINAL, 44, 121, 29)));
		table.put(DungeonClass.ARCHER, archer);

		Map<Integer, List<SyncEntry>> tank = new java.util.HashMap<>();
		tank.put(1, List.of(SyncEntry.at(StandKind.TERMINAL, 111, 113, 73), SyncEntry.at(StandKind.TERMINAL, 111, 119, 79)));
		// Third terminal here deliberately overlaps Berserk's own S2 third-terminal assignment above — per
		// the user's own note, "stacks third with bers incase either get melody and cant do third terminal".
		tank.put(2, List.of(SyncEntry.at(StandKind.TERMINAL, 69, 109, 121), SyncEntry.at(StandKind.TERMINAL, 47, 109, 121)));
		tank.put(3, List.of(SyncEntry.at(StandKind.TERMINAL, -3, 109, 112), SyncEntry.any(StandKind.LEVER)));
		tank.put(4, List.of(SyncEntry.at(StandKind.TERMINAL, 41, 109, 29)));
		table.put(DungeonClass.TANK, tank);

		return table;
	}

	private static DungeonClass selfClass() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		String selfName = mc.player.getName().getString();
		for (DungeonPlayer teammate : DungeonState.getTeammates()) {
			if (teammate.name.equals(selfName)) return teammate.clazz;
		}
		return null;
	}

	/** True if syncWithClass is off (nothing filtered) or the given stand matches the local player's own
	 *  class+section assignment in {@link #SYNC_TABLE}. Fails open (shows the stand) when the class hasn't
	 *  resolved yet or the table has no data for it, rather than silently hiding everything on an unknown
	 *  class — a missing filter is far less confusing than a highlight that mysteriously never appears. */
	private boolean passesClassSync(StandKind kind, Vec3 pos) {
		if (!syncWithClass) return true;
		DungeonClass self = selfClass();
		if (self == null) return true;
		Map<Integer, List<SyncEntry>> bySection = SYNC_TABLE.get(self);
		if (bySection == null) return true;
		List<SyncEntry> entries = bySection.get(section);
		if (entries == null) return true;
		for (SyncEntry entry : entries) if (entry.matches(kind, pos)) return true;
		return false;
	}

	private Set<ArmorStand> inactiveList = Set.of();

	/** True while this feature is actively managing (via its own {@code hideDefaultNames} toggle in
	 *  renderInner) the given stand's nametag visibility itself — per user request ("Make a new performance
	 *  feature called 'Hide nametags'... it should not try to overpower stuff like the inactive terminals
	 *  title hider"), HideNametagsFeature's own blanket suppression exempts anything this returns true for, so
	 *  the two features can never fight over the same entity's visibility flag every frame/tick. */
	public static boolean isManagingNameVisibility(net.minecraft.world.entity.Entity entity) {
		return instance != null && instance.isEnabled() && instance.inactiveList.contains(entity);
	}
	// complete/gate/section are still needed for passesClassSync (Sync with Class needs to know which F7 P3
	// subsection is current) — only the per-type counts (levers/terminals/device) and the shouldRender/
	// firstInSection/lastCompleted bookkeeping that fed them are gone, along with them the top-left "Levers
	// 2/2 / Terms 4/4" HUD readout itself (per user request — "immovable and pretty useless, remove it").
	private boolean complete = false;
	private boolean gate = false;
	private int section = 1;
	private int scanTickCounter = 0;

	private static boolean listenersRegistered = false;
	private static InactiveWaypointsFeature instance;

	public InactiveWaypointsFeature() {
		super("inactive_waypoints", "Inactive Waypoints", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		resetState();
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString());
				return true;
			});
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance != null && instance.isEnabled()) instance.tick(client);
			});
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "inactive_waypoints"), InactiveWaypointsFeature::renderStatic);
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.addRenderCallback(InactiveWaypointsFeature::renderBoxes);
		}
	}

	private void resetState() {
		inactiveList = Set.of();
		complete = false;
		gate = false;
		section = 1;
	}

	private void newSection() {
		complete = false;
		gate = false;
		section++;
	}

	// Section-advance tracking only now (the per-type completion counts this used to also track were purely
	// for the removed HUD readout) — still needed so passesClassSync knows which F7 P3 subsection is current.
	private void onChatMessage(String text) {
		if (!DungeonState.isInBoss()) return;

		Matcher completedMatch = COMPLETED_PATTERN.matcher(text);
		if (completedMatch.matches()) {
			int completedCount = parseIntOr(completedMatch.group(3), 0);
			int totalCount = parseIntOr(completedMatch.group(4), 0);
			if (completedCount == totalCount) {
				if (gate) newSection(); else complete = true;
			}
			return;
		}

		if (GATE_PATTERN.matcher(text).matches()) {
			gate = true;
			if (complete) newSection();
			return;
		}
		if (GOLDOR_PATTERN.matcher(text).matches()) {
			resetState();
			return;
		}
		if (CORE_OPENING_PATTERN.matcher(text).matches()) {
			resetState();
		}
	}

	private static int parseIntOr(String s, int fallback) {
		try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
	}

	private void tick(Minecraft client) {
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3) { inactiveList = Set.of(); return; }
		if (client.player == null || client.level == null) return;
		if (++scanTickCounter % 10 != 0) return;

		// Real bug found comparing against Odin's InactiveWaypoints.kt: it scans ALL entities within
		// render distance (entitiesForRendering()), not a fixed 64-block bubble around the player — F7's
		// terminal room is large enough that a section's inactive terminals can legitimately sit farther
		// than that from wherever the player currently is (e.g. checking on a different section), which
		// silently dropped them from inactiveList and read as "doesn't show inactive terminals at all."
		Set<ArmorStand> found = new HashSet<>();
		for (var entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof ArmorStand stand)) continue;
			String name = stand.getName().getString().toLowerCase(java.util.Locale.ROOT);
			// Per user report ("the devices just say 'device' in red text" even with "Hide Default Vanilla
			// Names" on): a device's real nametag apparently isn't always the exact literal "Inactive" this
			// class matches below in renderInner/renderBoxesInner — a standalone "Device" label wasn't
			// caught by this scan at all, so it never reached the unconditional setCustomNameVisible(false)
			// call those methods already make for every tracked stand (regardless of which specific kind it
			// turns out to be) and stayed visible untouched.
			if (name.contains("inactive") || name.contains("not activated") || name.contains("click here") || name.contains("device")) {
				found.add(stand);
			}
		}
		inactiveList = found;
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Inactive Waypoints render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		if (inactiveList.isEmpty() || DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return;
		// Real bug found (per user report — "the inactive waypoints text renders above the terminal gui"):
		// this is drawn via HudElementRegistry, which composites on top of whatever screen is currently
		// open (the terminal solver's own container screen included) — the world-projected label text has
		// no business covering the terminal GUI the player is actively looking at, so it's skipped entirely
		// while any screen is open. The 3D box highlight (renderBoxesInner, below) draws during the level
		// render pass instead, well before the screen layer, so it isn't affected by this and stays as-is.
		if (Minecraft.getInstance().gui.screen() != null) return;

		for (ArmorStand stand : inactiveList) {
			String name = stand.getName().getString();
			String label;
			StandKind kind;
			boolean show;
			if (name.equals("Inactive Terminal") && showTerminals) { label = "Inactive Terminal!"; kind = StandKind.TERMINAL; show = true; }
			else if (name.equals("Inactive") && showDevices) { label = "Inactive Device!"; kind = StandKind.DEVICE; show = true; }
			else if (name.equals("Not Activated") && showLevers) { label = "Inactive Lever!"; kind = StandKind.LEVER; show = true; }
			else { label = null; kind = null; show = false; }

			stand.setCustomNameVisible(!hideDefaultNames);
			if (!show) continue;

			Vec3 pos = stand.position();
			if (!passesClassSync(kind, pos)) continue;
			if (renderText) WorldRenderUtil.drawText(graphics, label, pos.add(0, 2, 0), 0xFFFFFFFF);
			if (renderBeacon) WorldRenderUtil.drawBeacon(graphics, pos, color, 30);
		}
	}

	// Real depth-tested 3D rendering (World3DRenderer) instead of WorldRenderUtil's screen-space
	// projection, per user request ("just show a highlight" — read together with the earlier blanket "if
	// it's highlighting a block I always want it to be a block highlight, not a 2d box render").
	private static void renderBoxes() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderBoxesInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Inactive Waypoints box render failed, skipping this frame", e);
		}
	}

	private void renderBoxesInner() {
		if (!renderBox || inactiveList.isEmpty() || DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return;
		for (ArmorStand stand : inactiveList) {
			String name = stand.getName().getString();
			StandKind kind;
			boolean show;
			if (name.equals("Inactive Terminal") && showTerminals) { kind = StandKind.TERMINAL; show = true; }
			else if (name.equals("Inactive") && showDevices) { kind = StandKind.DEVICE; show = true; }
			else if (name.equals("Not Activated") && showLevers) { kind = StandKind.LEVER; show = true; }
			else { kind = null; show = false; }
			if (!show) continue;
			Vec3 pos = stand.position();
			if (!passesClassSync(kind, pos)) continue;
			// Real redesign (per user request — "the inactive terminals highlight looks kind of dumb... add
			// block renders for the blocks that the user can also pick the color of. They should render
			// through walls, and be a full block"): was a thin depth-tested wire outline (drawWireBox) — now
			// a real full filled block that renders through walls, matching Door Highlight's own real-3D
			// through-walls box approach.
			AABB box = (kind == StandKind.TERMINAL && terminalHitboxAsHighlight)
				? stand.getBoundingBox()
				: new AABB(pos.x - 0.5, pos.y, pos.z - 0.5, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5);
			int fillAlpha = Math.round(35f / 100f * 255f);
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawFilledBoxThroughWalls(box, (fillAlpha << 24) | (color & 0xFFFFFF));
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawWireBoxThroughWalls(box, color, 2f);
		}
	}

	public boolean isShowTerminals() { return showTerminals; }
	public void setShowTerminals(boolean value) { showTerminals = value; }
	public boolean isShowDevices() { return showDevices; }
	public void setShowDevices(boolean value) { showDevices = value; }
	public boolean isShowLevers() { return showLevers; }
	public void setShowLevers(boolean value) { showLevers = value; }
	public boolean isRenderText() { return renderText; }
	public void setRenderText(boolean value) { renderText = value; }
	public boolean isRenderBeacon() { return renderBeacon; }
	public void setRenderBeacon(boolean value) { renderBeacon = value; }
	public boolean isRenderBox() { return renderBox; }
	public void setRenderBox(boolean value) { renderBox = value; }
	public boolean isHideDefaultNames() { return hideDefaultNames; }
	public void setHideDefaultNames(boolean value) { hideDefaultNames = value; }
	public int getColor() { return color; }
	public void setColor(int value) { color = value; }
	public boolean isSyncWithClass() { return syncWithClass; }
	public void setSyncWithClass(boolean value) { syncWithClass = value; }
	public boolean isTerminalHitboxAsHighlight() { return terminalHitboxAsHighlight; }
	public void setTerminalHitboxAsHighlight(boolean value) { terminalHitboxAsHighlight = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showTerminals", showTerminals);
		obj.addProperty("showDevices", showDevices);
		obj.addProperty("showLevers", showLevers);
		obj.addProperty("renderText", renderText);
		obj.addProperty("renderBeacon", renderBeacon);
		obj.addProperty("renderBox", renderBox);
		obj.addProperty("hideDefaultNames", hideDefaultNames);
		obj.addProperty("color", color);
		obj.addProperty("syncWithClass", syncWithClass);
		obj.addProperty("terminalHitboxAsHighlight", terminalHitboxAsHighlight);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("showTerminals")) showTerminals = obj.get("showTerminals").getAsBoolean();
		if (obj.has("showDevices")) showDevices = obj.get("showDevices").getAsBoolean();
		if (obj.has("showLevers")) showLevers = obj.get("showLevers").getAsBoolean();
		if (obj.has("renderText")) renderText = obj.get("renderText").getAsBoolean();
		if (obj.has("renderBeacon")) renderBeacon = obj.get("renderBeacon").getAsBoolean();
		if (obj.has("renderBox")) renderBox = obj.get("renderBox").getAsBoolean();
		if (obj.has("hideDefaultNames")) hideDefaultNames = obj.get("hideDefaultNames").getAsBoolean();
		if (obj.has("color")) color = obj.get("color").getAsInt();
		if (obj.has("syncWithClass")) syncWithClass = obj.get("syncWithClass").getAsBoolean();
		if (obj.has("terminalHitboxAsHighlight")) terminalHitboxAsHighlight = obj.get("terminalHitboxAsHighlight").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "F7 boss-fight helper: highlights not-yet-activated terminals/devices/levers and shows how many remain.";
	}
}
