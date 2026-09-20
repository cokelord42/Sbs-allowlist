package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Highlights the Wither/Blood Key item once it spawns as a floating armor-stand prop, so it can be found
 * across the room instead of needing to spot the tiny floating item model itself. Split out of {@link
 * DoorHighlightFeature} per user request ("I cant find an option for key highlight. Move it out of the
 * door highlight and make it a seperate module"): that class still owns whether a door is openable (real
 * key-count/door-open chat tracking, unaffected by this split — this key-box render never actually
 * consumed that count), this one now owns only the key entity's own detection and highlight rendering,
 * plus a real 2D/2D Fill/3D/3D Fill render-mode selector matching {@link MobHighlightFeature.RenderMode}'s
 * already-established pattern elsewhere in this codebase, and an independent fill-opacity slider (the fill
 * color always matches the regular key color, per user spec).
 */
public class KeyHighlightFeature extends Feature {
	private static final double KEY_SCAN_RANGE = 32.0;

	private int witherKeyColor = 0xCCAA55FF;
	private int bloodKeyColor = 0xCCFF5555;
	// Per user request ("allow users to select between the rendering modes for the key, i.e 3d/3d fill and
	// 2d/2d fill"): defaults to FULL_2D (2D Fill) to match this box's original fixed FILLED_OUTLINE look
	// before this render-mode selector existed, so existing users see no visual change on upgrade.
	private MobHighlightFeature.RenderMode renderMode = MobHighlightFeature.RenderMode.FULL_2D;
	// Per user request ("The fill color should match the regular color but add a slider for opacity"): the
	// old box had a fixed 60% fill — now user-adjustable, still only used by the two "Fill" render modes.
	private int fillOpacity = 60;
	// Per user request ("Add tracer support and occlusion support to the key highlight"): a plain crosshair-
	// to-key line, same WorldRenderUtil.drawTracer every other solver/highlight module already uses, colored
	// the same as the box itself (no separate color picker, matching this feature's own established "reuse
	// the regular color" convention). Off by default so existing users see no new line on upgrade.
	private boolean showTracer = false;
	// Same meaning as BlazeSolverFeature's own occlusion3D: true = real depth-tested occlusion (hidden behind
	// walls/terrain, both for the 3D box and the tracer's own raycast), false = always visible through walls.
	// Defaults false (through-walls) to match this box's existing behavior before this toggle existed — a
	// dungeon key is exactly the kind of "find it across the room" information Door Highlight's own door
	// boxes already justify staying visible through walls for by default.
	private boolean occlusion3D = false;

	private ArmorStand currentKeyEntity;
	private boolean isWitherKeyEntity;
	// Same reset-on-new-run tracking DoorHighlightFeature's own copy of these fields already established
	// (see that class's own doc comment on the real dungeon-rejoin bug this catches) — kept as this
	// feature's own independent copy since currentKeyEntity is now this feature's own state, not shared.
	private boolean wasInDungeonForKeyState = false;
	private int lastSeenRunId = -1;

	private static boolean listenersRegistered = false;
	private static KeyHighlightFeature instance;

	public KeyHighlightFeature() {
		super("key_highlight", "Key Highlight", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() {
		return "Dungeons";
	}

	@Override
	protected void onEnable() {
		currentKeyEntity = null;
		if (!listenersRegistered) {
			listenersRegistered = true;
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST,
				Identifier.fromNamespaceAndPath("skyblocksimplified", "key_highlight"), KeyHighlightFeature::renderStatic);
			World3DRenderer.addRenderCallback(KeyHighlightFeature::render3DStatic);
		}
	}

	@Override
	public void onTick(Minecraft client) {
		boolean inDungeonNow = DungeonState.isInDungeon();
		int runId = DungeonState.getRunId();
		if (inDungeonNow && (!wasInDungeonForKeyState || runId != lastSeenRunId)) {
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
			// See DoorHighlightFeature's own old call site doc comment (moved verbatim): this feature still
			// owns the actual spawn DETECTION, but whether to show an alert at all — and its title/sound/
			// party-message — is entirely Dungeon Notifications' call via fireKeyDrop.
			DungeonNotificationsFeature.fireKeyDrop(name);
			break;
		}
	}

	/** True if the given entity is the currently-tracked Wither/Blood Key prop — used by {@code
	 *  EntityHideMixin} to exempt it from every "hide X" toggle unconditionally, same reasoning as this
	 *  method's own doc comment did on {@code DoorHighlightFeature} before this split (a dungeon key is
	 *  critical run-affecting information that should never be silently suppressed by an unrelated
	 *  declutter toggle). */
	public static boolean isProtectedKeyEntity(Entity entity) {
		return instance != null && entity == instance.currentKeyEntity;
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled() || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		try {
			instance.render2DInner(graphics);
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Key Highlight 2D render failed, skipping this frame", e);
		}
	}

	private void render2DInner(GuiGraphicsExtractor graphics) {
		AABB keyBox = currentKeyBox();
		if (keyBox == null) return;
		int color = isWitherKeyEntity ? witherKeyColor : bloodKeyColor;
		// Tracer draws regardless of which render mode (2D/3D, box or not) is currently selected — it's an
		// independent "which way do I walk" aid, same as every other solver's own tracer toggle.
		if (showTracer) {
			WorldRenderUtil.drawTracer(graphics, keyBox.getCenter(), color, 2, occlusion3D);
		}
		if (renderMode != MobHighlightFeature.RenderMode.OUTLINE_2D && renderMode != MobHighlightFeature.RenderMode.FULL_2D) return;
		WorldRenderUtil.RenderStyle style = renderMode == MobHighlightFeature.RenderMode.FULL_2D
			? WorldRenderUtil.RenderStyle.FILLED_OUTLINE : WorldRenderUtil.RenderStyle.OUTLINE;
		WorldRenderUtil.drawStyledBox(graphics, keyBox, color, style, 2, fillOpacity);
	}

	private static void render3DStatic() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.render3DInner();
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Key Highlight 3D render failed, skipping this frame", e);
		}
	}

	private void render3DInner() {
		if (renderMode != MobHighlightFeature.RenderMode.WIRE_3D && renderMode != MobHighlightFeature.RenderMode.FULL_3D) return;
		AABB keyBox = currentKeyBox();
		if (keyBox == null) return;
		int color = isWitherKeyEntity ? witherKeyColor : bloodKeyColor;
		boolean fill = renderMode == MobHighlightFeature.RenderMode.FULL_3D;
		int fillAlpha = fill ? Math.round(fillOpacity / 100f * 255f) : 0;
		int fillArgb = (fillAlpha << 24) | (color & 0xFFFFFF);
		// Per user request ("Add occlusion support to the key highlight") — same real-depth-test-vs-through-
		// walls choice every other 3D-capable highlight module already offers (see occlusion3D's own field
		// doc comment for the default).
		if (occlusion3D) {
			if (fillAlpha > 0) World3DRenderer.drawFilledBox(keyBox, fillArgb);
			World3DRenderer.drawWireBox(keyBox, color, 2f);
		} else {
			if (fillAlpha > 0) World3DRenderer.drawFilledBoxThroughWalls(keyBox, fillArgb);
			World3DRenderer.drawWireBoxThroughWalls(keyBox, color, 2f);
		}
	}

	private AABB currentKeyBox() {
		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) return null;
		if (currentKeyEntity == null || !currentKeyEntity.isAlive()) return null;
		Vec3 pos = currentKeyEntity.position();
		return new AABB(pos.x - 0.5, pos.y + 1.0, pos.z - 0.5, pos.x + 0.5, pos.y + 2.0, pos.z + 0.5);
	}

	public int getWitherKeyColor() { return witherKeyColor; }
	public void setWitherKeyColor(int value) { witherKeyColor = value; }
	public int getBloodKeyColor() { return bloodKeyColor; }
	public void setBloodKeyColor(int value) { bloodKeyColor = value; }
	public MobHighlightFeature.RenderMode getRenderMode() { return renderMode; }
	public void setRenderMode(MobHighlightFeature.RenderMode value) { renderMode = value; }
	public int getFillOpacity() { return fillOpacity; }
	public void setFillOpacity(int value) { fillOpacity = Math.max(0, Math.min(100, value)); }
	public boolean isShowTracer() { return showTracer; }
	public void setShowTracer(boolean value) { showTracer = value; }
	public boolean isOcclusion3D() { return occlusion3D; }
	public void setOcclusion3D(boolean value) { occlusion3D = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("witherKeyColor", witherKeyColor);
		obj.addProperty("bloodKeyColor", bloodKeyColor);
		obj.addProperty("renderMode", renderMode.name());
		obj.addProperty("fillOpacity", fillOpacity);
		obj.addProperty("showTracer", showTracer);
		obj.addProperty("occlusion3D", occlusion3D);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("witherKeyColor")) witherKeyColor = obj.get("witherKeyColor").getAsInt();
		if (obj.has("bloodKeyColor")) bloodKeyColor = obj.get("bloodKeyColor").getAsInt();
		if (obj.has("renderMode")) {
			try { renderMode = MobHighlightFeature.RenderMode.valueOf(obj.get("renderMode").getAsString()); } catch (IllegalArgumentException ignored) {}
		}
		if (obj.has("fillOpacity")) fillOpacity = obj.get("fillOpacity").getAsInt();
		if (obj.has("showTracer")) showTracer = obj.get("showTracer").getAsBoolean();
		if (obj.has("occlusion3D")) occlusion3D = obj.get("occlusion3D").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Highlights the Wither/Blood Key item once it drops, with a selectable 2D/3D render mode and fill opacity.";
	}
}
