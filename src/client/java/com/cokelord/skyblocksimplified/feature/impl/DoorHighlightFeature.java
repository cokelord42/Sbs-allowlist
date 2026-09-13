package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DoorType;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonDoor;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.regex.Pattern;

/** Highlights Wither/Blood dungeon doors (colored by whether you currently hold the matching key) and
 *  the key item itself once it spawns as a floating item. Ported from Odin's {@code DoorHighlight.kt}.
 *
 * <p>Odin detects the key spawning via a raw {@code ClientboundSetEntityDataPacket} listener (matching a
 * named ArmorStand); this codebase has no generic incoming-packet listener registry for that without a
 * new Mixin, so key spawn is instead detected by scanning nearby armor stands once per tick for the exact
 * name — the same tick-poll adaptation used by {@link MimicFeature}. */
public class DoorHighlightFeature extends Feature {
	private static final Pattern WITHER_KEY_OBTAIN_PATTERN = Pattern.compile("^(?:\\[[^]]*?] )?(\\w{1,16}) has obtained Wither Key!?$");
	private static final Pattern WITHER_KEY_PICKED_UP_PATTERN = Pattern.compile("^A Wither Key was picked up!$");
	private static final Pattern WITHER_DOOR_OPEN_PATTERN = Pattern.compile("^(?:\\[[^]]*?] )?(\\w{1,16}) opened a WITHER door!$");
	private static final Pattern BLOOD_KEY_OBTAIN_PATTERN = Pattern.compile("^(?:\\[[^]]*?] )?(\\w{1,16}) has obtained Blood Key!$");
	private static final Pattern BLOOD_KEY_PICKED_UP_PATTERN = Pattern.compile("^A Blood Key was picked up!$");
	private static final Pattern BLOOD_DOOR_OPEN_PATTERN = Pattern.compile("^The BLOOD DOOR has been opened!$");
	private static final double KEY_SCAN_RANGE = 32.0;

	private int doorLockedColor = 0xCCFF5555;
	private int doorOpenableColor = 0xCC55FF55;
	// Was 0xCC000000 (near-black) — a black outline is nearly invisible against dark dungeon stone, which
	// is very likely why the key highlight looked like it "wasn't there" despite rendering correctly.
	private int witherKeyColor = 0xCCAA55FF;
	private int bloodKeyColor = 0xCCFF5555;

	private int witherKeys = 0;
	private boolean bloodKey = false;
	private boolean bloodOpened = false;
	private ArmorStand currentKeyEntity;
	private boolean isWitherKeyEntity;
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
		currentKeyEntity = null;
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
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "door_highlight"), DoorHighlightFeature::renderStatic);
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
		} else if (BLOOD_DOOR_OPEN_PATTERN.matcher(text).matches()) {
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
			currentKeyEntity = null;
		}
		wasInDungeonForKeyState = inDungeonNow;
		lastSeenRunId = runId;

		if (!inDungeonNow || DungeonState.isInBoss() || client.player == null || client.level == null) return;
		if (currentKeyEntity != null && !currentKeyEntity.isAlive()) currentKeyEntity = null;
		if (currentKeyEntity != null) return;

		for (var entity : client.level.getEntities(client.player, client.player.getBoundingBox().inflate(KEY_SCAN_RANGE),
			e -> e instanceof ArmorStand)) {
			ArmorStand stand = (ArmorStand) entity;
			String name = stand.getName().getString();
			boolean isWither = "Wither Key".equals(name);
			boolean isBlood = "Blood Key".equals(name);
			if (!isWither && !isBlood) continue;

			currentKeyEntity = stand;
			isWitherKeyEntity = isWither;
			// Per user report ("Key dropped notification is still in door highlight. It should have moved
			// entirely to 'Dungeon notifications'"): this used to also send its own raw chat message here,
			// gated by a separate "Announce Key Spawn" toggle that lived on this module — a second on/off
			// switch duplicating DungeonNotificationsFeature's own KEY_DROP per-type toggle, plus a plain chat
			// line redundant with that module's title/sound/party-message alert. Both are removed: this
			// feature still owns the actual spawn DETECTION (scanning for the armor stand, real gameplay-
			// entity tracking outside Dungeon Notifications' chat-driven scope) but the alert itself — and
			// whether to show one at all — is now entirely Dungeon Notifications' call via fireKeyDrop, which
			// already no-ops when its own KEY_DROP type is disabled.
			DungeonNotificationsFeature.fireKeyDrop(name);
			break;
		}
	}

	/** True if the given entity is the currently-tracked Wither/Blood Key prop — used by {@code
	 *  EntityHideMixin} to exempt it from every "hide X" toggle unconditionally. Per user report ("the
	 *  blood key seems to hide sometimes... started after the superboom tnt hider was changed", confirmed
	 *  with "Hide Superboom TNT" on and both the key's own model AND this feature's highlight box missing
	 *  right after killing the mob that drops it): a dungeon key is critical run-affecting information that
	 *  should never be silently suppressed by an unrelated declutter toggle, whatever the exact matching
	 *  collision turns out to be — the underlying entity being genuinely hidden (not found by
	 *  {@code getEntities()}, or a fresh drop still transitioning through an intermediate representation)
	 *  couldn't otherwise be pinned down without live data, so this closes the whole class of "some hider
	 *  rule accidentally eats the key" bug directly instead of chasing one specific predicate. */
	public static boolean isProtectedKeyEntity(Entity entity) {
		return instance != null && entity == instance.currentKeyEntity;
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled() || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Door Highlight render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) return;

		if (currentKeyEntity == null || !currentKeyEntity.isAlive()) return;
		Vec3 pos = currentKeyEntity.position();
		AABB keyBox = new AABB(pos.x - 0.5, pos.y + 1.0, pos.z - 0.5, pos.x + 0.5, pos.y + 2.0, pos.z + 0.5);
		WorldRenderUtil.drawStyledBox(graphics, keyBox, isWitherKeyEntity ? witherKeyColor : bloodKeyColor, WorldRenderUtil.RenderStyle.FILLED_OUTLINE, 2, 60);
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
	public int getWitherKeyColor() { return witherKeyColor; }
	public void setWitherKeyColor(int value) { witherKeyColor = value; }
	public int getBloodKeyColor() { return bloodKeyColor; }
	public void setBloodKeyColor(int value) { bloodKeyColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("doorLockedColor", doorLockedColor);
		obj.addProperty("doorOpenableColor", doorOpenableColor);
		obj.addProperty("witherKeyColor", witherKeyColor);
		obj.addProperty("bloodKeyColor", bloodKeyColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("doorLockedColor")) doorLockedColor = obj.get("doorLockedColor").getAsInt();
		if (obj.has("doorOpenableColor")) doorOpenableColor = obj.get("doorOpenableColor").getAsInt();
		if (obj.has("witherKeyColor")) witherKeyColor = obj.get("witherKeyColor").getAsInt();
		if (obj.has("bloodKeyColor")) bloodKeyColor = obj.get("bloodKeyColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Highlights Wither/Blood dungeon doors (colored by whether you're holding the matching key) and the key item itself once it drops.";
	}
}
