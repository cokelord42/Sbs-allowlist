package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DoorType;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonDoor;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

import java.util.regex.Pattern;

/** Highlights Wither/Blood dungeon doors, colored by whether you currently hold the matching key.
 *  Ported from Odin's {@code DoorHighlight.kt}. The key item's own highlight (once it spawns as a
 *  floating prop) is a separate module, {@link KeyHighlightFeature} — split out per user request ("Move
 *  it out of the door highlight and make it a seperate module"); this class still owns the key-count/
 *  door-open chat tracking below (whether a door is currently openable is a door concern, and never
 *  actually depended on the key box's own render), only the key entity's own detection/rendering moved. */
public class DoorHighlightFeature extends Feature {
	private static final Pattern WITHER_KEY_OBTAIN_PATTERN = Pattern.compile("^(?:\\[[^]]*?] )?(\\w{1,16}) has obtained Wither Key!?$");
	private static final Pattern WITHER_KEY_PICKED_UP_PATTERN = Pattern.compile("^A Wither Key was picked up!$");
	private static final Pattern WITHER_DOOR_OPEN_PATTERN = Pattern.compile("^(?:\\[[^]]*?] )?(\\w{1,16}) opened a WITHER door!$");
	private static final Pattern BLOOD_KEY_OBTAIN_PATTERN = Pattern.compile("^(?:\\[[^]]*?] )?(\\w{1,16}) has obtained Blood Key!$");
	private static final Pattern BLOOD_KEY_PICKED_UP_PATTERN = Pattern.compile("^A Blood Key was picked up!$");
	// See DungeonState's own copy of this pattern for why it's case-insensitive and no longer anchored at
	// the end (per user report — "the bossbar still does not show in the blood room... its a regex issue" —
	// this exact line's real capitalization/trailing punctuation was never independently confirmed).
	private static final Pattern BLOOD_DOOR_OPEN_PATTERN = Pattern.compile("^The BLOOD DOOR has been opened!", Pattern.CASE_INSENSITIVE);

	private int doorLockedColor = 0xCCFF5555;
	private int doorOpenableColor = 0xCC55FF55;

	private int witherKeys = 0;
	private boolean bloodKey = false;
	private boolean bloodOpened = false;
	// Real bug found: these were only ever reset in onEnable() (the feature toggle event), never on
	// actually starting a NEW dungeon run while staying continuously enabled — the normal way a toggle
	// feature is used. Completing a wither/blood door run, then starting a fresh dungeon in the same
	// session without ever flipping the toggle off/on, carried the previous run's key counts and
	// bloodOpened flag straight into the new run, coloring doors as openable/locked based on stale state.
	private boolean wasInDungeonForKeyState = false;
	// Real bug found (per user report — "Door highlight STILL isn't rendering. I'm still getting the same
	// '1 boxes drawn this frame' but I see nothing at all"): wasInDungeonForKeyState above only catches
	// isInDungeon() going false->true, which never happens on a direct dungeon-to-dungeon rejoin (see
	// DungeonState.runId's own doc comment) — DungeonScan.doors kept a stale door from the PREVIOUS run,
	// whose resolved world position doesn't correspond to anything in the new run's actual layout, so the
	// box rendered every frame exactly like the debug log said, just nowhere near the player. Comparing
	// against runId catches this case too, regardless of whether isInDungeon() ever flipped.
	private int lastSeenRunId = -1;

	// Per user request ("Read how odin does the detecting then do that, cause theirs is perfect"): every
	// PREVIOUS round's attempt at this (player-Y-anchored, then a real per-door floor-surface block scan,
	// then a real Coal Block bounding-box scan for Wither doors specifically) turned out to be far more
	// complicated than the real, actually-correct answer. Odin's own DoorHighlight.kt does none of that —
	// it just hardcodes the box as `AABB(door.worldX - 1.0, 69.0, door.worldZ - 1.0, door.worldX + 2.0,
	// 73.0, door.worldZ + 2.0)`, a literal, unconditional Y 69-73 span for EVERY door, Wither or Blood
	// alike, with no per-room/per-door lookup of any kind. This works because the entire Catacombs dungeon
	// instance — every room, every door, every floor number — is generated at the exact same fixed Y
	// baseline (the same real convention this codebase's own WorldScan.getRoomCore already independently
	// relies on via its "y < 69" bedrock cutoff), so a single constant really is the correct, universal
	// answer and none of the dynamic block-scanning this class used to do was ever necessary.

	private static boolean listenersRegistered = false;
	private static DoorHighlightFeature instance;

	public DoorHighlightFeature() {
		super("door_highlight", "Door Highlight", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() {
		return "Dungeons";
	}

	@Override
	protected void onEnable() {
		witherKeys = 0;
		bloodKey = false;
		bloodOpened = false;
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				// Stripped here too, same reasoning as DungeonState's own fix this round (per user report,
				// pointed at comparing against PestSpawnAlertFeature's already-working, defensively-stripped
				// chat regex) — the key-obtain/door-open patterns are exact-text matches with no defense
				// against embedded "§" formatting codes getString() alone might not have stripped.
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString().replaceAll("§.", ""));
				return true;
			});
			// Per user request ("the door needs to be a block HIGHLIGHT and not a 2d render... it needs to
			// be visible through walls, this is allowed since it pulls off the map") — matches Quiz Solver's
			// own real-3D-box-but-not-Etherwarp's-real-occlusion precedent: real box geometry (proper
			// perspective/rotation, unlike WorldRenderUtil's flat AABB-corner screen projection) via the new
			// *ThroughWalls World3DRenderer methods, which skip the normal depth test those didn't need to.
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.addRenderCallback(DoorHighlightFeature::renderDoorBoxes);
		}
	}

	private void onChatMessage(String text) {
		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) return;
		if (WITHER_KEY_OBTAIN_PATTERN.matcher(text).matches() || WITHER_KEY_PICKED_UP_PATTERN.matcher(text).matches()) {
			witherKeys++;
		} else if (WITHER_DOOR_OPEN_PATTERN.matcher(text).matches()) {
			witherKeys = Math.max(0, witherKeys - 1);
		} else if (BLOOD_KEY_OBTAIN_PATTERN.matcher(text).matches() || BLOOD_KEY_PICKED_UP_PATTERN.matcher(text).matches()) {
			bloodKey = true;
		} else if (BLOOD_DOOR_OPEN_PATTERN.matcher(text).find()) {
			bloodKey = false;
			bloodOpened = true;
		}
	}

	@Override
	public void onTick(Minecraft client) {
		boolean inDungeonNow = DungeonState.isInDungeon();
		int runId = DungeonState.getRunId();
		if (inDungeonNow && (!wasInDungeonForKeyState || runId != lastSeenRunId)) {
			witherKeys = 0;
			bloodKey = false;
			bloodOpened = false;
		}
		wasInDungeonForKeyState = inDungeonNow;
		lastSeenRunId = runId;
	}

	private static void renderDoorBoxes() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderDoorBoxesInner();
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Door Highlight box render failed, skipping this frame", e);
		}
	}

	private void renderDoorBoxesInner() {
		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) return;

		int rendered = 0;
		for (DungeonDoor door : DungeonScan.doors.values()) {
			if (door.type != DoorType.WITHER && door.type != DoorType.BLOOD) continue;
			if (door.type == DoorType.BLOOD && bloodOpened) continue;

			// Odin's own literal, unconditional box — see this class's own doc comment above for why a
			// fixed Y 69-73 constant is the real, correct answer for every door in every room.
			AABB box = new AABB(door.worldX - 1.0, 69.0, door.worldZ - 1.0, door.worldX + 2.0, 73.0, door.worldZ + 2.0);
			boolean isOpenable = door.type == DoorType.WITHER ? witherKeys > 0 : bloodKey;
			int color = isOpenable ? doorOpenableColor : doorLockedColor;
			// Same 20% fill / full-alpha outline weighting the old FILLED_OUTLINE style used (fillOpacityPercent
			// 40, halved for that style) — kept identical so switching renderers doesn't also change the look.
			int fillAlpha = Math.round(20f / 100f * 255f);
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawFilledBoxThroughWalls(box, (fillAlpha << 24) | (color & 0xFFFFFF));
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawWireBoxThroughWalls(box, color, 2f);
			rendered++;
		}
	}

	public int getDoorLockedColor() { return doorLockedColor; }
	public void setDoorLockedColor(int value) { doorLockedColor = value; }
	public int getDoorOpenableColor() { return doorOpenableColor; }
	public void setDoorOpenableColor(int value) { doorOpenableColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("doorLockedColor", doorLockedColor);
		obj.addProperty("doorOpenableColor", doorOpenableColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("doorLockedColor")) doorLockedColor = obj.get("doorLockedColor").getAsInt();
		if (obj.has("doorOpenableColor")) doorOpenableColor = obj.get("doorOpenableColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Highlights Wither/Blood dungeon doors, colored by whether you're currently holding the matching key.";
	}
}
