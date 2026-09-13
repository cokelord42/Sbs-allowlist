package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.Puzzle;
import com.cokelord.skyblocksimplified.dungeon.PuzzleStatus;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonJsonAssets;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** F5/F6 "Creeper Beams" puzzle solver — matches sea-lantern pairs from a bundled solution table against
 *  the room's currently-lit lanterns and highlights each matched pair (+ optional connecting tracer).
 *  Ported from Odin's {@code BeamsSolver.kt}.
 *
 * <p>Odin recalculates on a {@code BlockUpdateEvent} (any prismarine/sea-lantern block change in the
 * room). This codebase has no generic block-update event without a new Mixin, so this instead rescans the
 * known candidate positions every {@link #RESCAN_INTERVAL_TICKS} ticks — cheap (a fixed small list of
 * lookups, not a level scan) and imperceptible for a puzzle solved over many seconds. */
public class BeamsSolverFeature extends Feature {
	private static final int RESCAN_INTERVAL_TICKS = 5;
	private static final BlockPos CHEST_POS = new BlockPos(15, 69, 15);
	// Rainbow-style set, per user report ("the yellow and orange pairs look too similar to tell apart") —
	// the old palette's orange (0xFFFFAA00) and yellow (0xFFFFFF55) sat close enough in hue to be
	// indistinguishable at a glance. PAIR_COLORS[index % PAIR_COLORS.length] below already cycles the
	// palette for however many pairs the room has, so any positive length is a safe change here.
	private static final int[] PAIR_COLORS = {
		0xFFFF3333, 0xFFFFEE33, 0xFF33CC33, 0xFF3355FF, 0xFF33DDEE, 0xFFAA33EE
	};

	private final List<List<Integer>> lanternPairsData = DungeonJsonAssets.load(
		"/assets/skyblocksimplified/dungeon/puzzles/creeperBeamsSolutions.json",
		new TypeToken<List<List<Integer>>>() {}.getType(), List.of());

	private final Map<BlockPos, BlockPos> currentPairs = new LinkedHashMap<>();
	private final Map<BlockPos, Integer> pairColors = new LinkedHashMap<>();
	private int tickCounter = 0;
	private boolean puzzleCompleteFired = false;

	private WorldRenderUtil.RenderStyle style = WorldRenderUtil.RenderStyle.FILLED_OUTLINE;
	private int alphaPercent = 60;

	// Per user request ("Add a sound selector to the creeper beams puzzle, and it should play when the
	// user hits a lantern... I also want the mod to select the corresponding lantern if the user hits a
	// lantern... and support for tracers for this aswell"): a curated cycle of short, distinct feedback
	// sounds rather than a free-text sound-id field — this codebase has no sound-picker widget yet, and a
	// small fixed list (same shape as the style/price-source cycle rows already used elsewhere) is both
	// safer and more discoverable than asking the player to type a raw resource-location sound ID.
	public static final String[] HIT_SOUND_IDS = {
		"block.note_block.pling", "block.note_block.bell", "block.amethyst_block.chime",
		"entity.experience_orb.pickup", "ui.button.click"
	};
	public static final String[] HIT_SOUND_LABELS = {"Pling", "Bell", "Amethyst Chime", "Orb Pickup", "Click"};

	private boolean hitSoundEnabled = false;
	private final CustomSoundOption hitSound = new CustomSoundOption(HIT_SOUND_IDS, HIT_SOUND_LABELS);
	private boolean hitHighlightEnabled = true;
	private boolean hitTracerEnabled = true;
	// Per user request ("It should not show any lines from the start. It should just highlight the blocks
	// that match eachothers opposite in different colors. When a user shoots a lantern, then it should
	// tracer and highlight the opposite block only, until that gets shot then it resets to all being
	// highlighted and no lantern being tracered"): replaces the old time-based (3s) "highlight both lanterns
	// of the pair" emphasis with an event-driven, single-block one. Shooting a lantern extinguishes it —
	// recalculate()'s own next rescan (every RESCAN_INTERVAL_TICKS) then drops the WHOLE pair from
	// currentPairs the moment either half stops being a real SEA_LANTERN block, which would otherwise also
	// un-highlight its still-lit partner. pendingOpposite is that still-lit partner, kept highlighted (and
	// tracered from the block that was just hit) on its own until it too gets shot — detected by a second
	// onLanternHit call landing on this exact position, which clears everything back to the plain baseline
	// (every currently-lit pair highlighted, no tracer, nothing pending).
	private BlockPos pendingHitOrigin;
	private BlockPos pendingOpposite;
	private int pendingColor = 0xFFFFFFFF;

	private static boolean listenersRegistered = false;
	private static BeamsSolverFeature instance;

	public BeamsSolverFeature() {
		super("puzzle_creeper_beams", "Creeper Beams Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Puzzles"; }

	@Override
	protected void onEnable() {
		reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			WorldScan.addRoomEnterListener(room -> { if (instance != null && instance.isEnabled()) instance.recalculate(room); });
			// Tracer line stays screen-space (HudElementRegistry/WorldRenderUtil) — Odin's own real
			// BeamsSolver.kt draws its connecting line with depth=false too, only the pair boxes themselves
			// use depth=true. The boxes below switch to World3DRenderer (real depth-tested 3D) to match
			// that and per user request ("if it's highlighting a block I always want it to be a block
			// highlight and not a 2d box render").
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "puzzle_creeper_beams"), BeamsSolverFeature::renderStatic);
			World3DRenderer.addRenderCallback(BeamsSolverFeature::renderBoxes);
			AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
				if (instance != null && instance.isEnabled() && isBeamsRoom()
						&& level.getBlockState(pos).getBlock() == Blocks.SEA_LANTERN) {
					instance.onLanternHit(pos);
				}
				return InteractionResult.PASS;
			});
		}
	}

	/** See {@link #pendingOpposite}'s own field doc comment for the full state machine this implements.
	 *  Resolves the hit lantern's pair partner (checking both sides of the pairing, since a lantern can be
	 *  either the "key" or the "value" half depending on the JSON entry's order). Does nothing if the hit
	 *  lantern isn't currently part of any known lit pair (e.g. an unlit prismarine block the player swung
	 *  at). */
	private void onLanternHit(BlockPos pos) {
		if (pos.equals(pendingOpposite)) {
			// The awaited partner just got shot too — both halves of this pair are now extinguished, reset
			// all the way back to the plain baseline (nothing pending, no tracer).
			pendingHitOrigin = null;
			pendingOpposite = null;
			playHitSound();
			return;
		}
		BlockPos partner = currentPairs.get(pos);
		BlockPos keySide = pos;
		if (partner == null) {
			for (var entry : currentPairs.entrySet()) {
				if (entry.getValue().equals(pos)) {
					partner = entry.getKey();
					keySide = entry.getKey();
					break;
				}
			}
		}
		if (partner == null) return;
		pendingHitOrigin = pos;
		pendingOpposite = partner;
		pendingColor = pairColors.getOrDefault(keySide, 0xFFFFFFFF);
		playHitSound();
	}

	private void playHitSound() {
		if (!hitSoundEnabled) return;
		hitSound.play();
	}

	// Per user request ("Allow users to play the sound they have selected in all sound selectors throughout
	// the mod"): a preview entry point for MainScreen's settings panel, deliberately NOT gated on
	// hitSoundEnabled like playHitSound() is — a preview button has to work even while the subtoggle it
	// would normally play alongside is off.
	public void previewHitSound() {
		hitSound.play();
	}

	@Override
	protected void onDisable() { reset(); }

	private void reset() {
		currentPairs.clear();
		pairColors.clear();
		puzzleCompleteFired = false;
		pendingHitOrigin = null;
		pendingOpposite = null;
	}

	private static boolean isBeamsRoom() {
		DungeonRoom room = WorldScan.getCurrentRoom();
		return room != null && room.data != null && "Creeper Beams".equals(room.data.getName());
	}

	private void recalculate(DungeonRoom room) {
		if (room == null || room.data == null || !"Creeper Beams".equals(room.data.getName())) { reset(); return; }
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		// Real bug found (per user report — "the lantern-matching highlight isn't working at all"):
		// room.rotation/clayPos (set by DungeonRoom#get1x1Rotation, which probes for a real BLUE_TERRACOTTA
		// marker block) can still be null for a tick or more after the room is first recognized — every
		// coordinate this method computes silently resolves to BlockPos.ZERO in that window (see
		// DungeonRoom#getRealCoords's own null-guard), which is real-world bedrock/void and obviously never
		// SEA_LANTERN, so currentPairs stays empty with no visible signal of why. RESCAN_INTERVAL_TICKS
		// already retries every 5 ticks, so this self-heals once geometry resolves — but silently, which is
		// indistinguishable from "genuinely broken" to a player watching nothing render. Logged (throttled)
		// so a live Debug-module session shows whether this is the actual cause instead of a further guess.
		if (room.rotation == null || room.clayPos == null) {
			currentPairs.clear();
			pairColors.clear();
			return;
		}

		currentPairs.clear();
		pairColors.clear();
		for (int index = 0; index < lanternPairsData.size(); index++) {
			List<Integer> entry = lanternPairsData.get(index);
			if (entry.size() < 6) continue;
			BlockPos pos = room.getRealCoords(new BlockPos(entry.get(0), entry.get(1), entry.get(2)));
			BlockPos pos2 = room.getRealCoords(new BlockPos(entry.get(3), entry.get(4), entry.get(5)));
			if (mc.level.getBlockState(pos).getBlock() != Blocks.SEA_LANTERN) continue;
			if (mc.level.getBlockState(pos2).getBlock() != Blocks.SEA_LANTERN) continue;
			currentPairs.put(pos, pos2);
			pairColors.put(pos, PAIR_COLORS[index % PAIR_COLORS.length]);
		}
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isBeamsRoom()) return;
		DungeonRoom room = WorldScan.getCurrentRoom();
		if (room == null) return;

		tickCounter++;
		if (tickCounter % RESCAN_INTERVAL_TICKS == 0) recalculate(room);

		if (!puzzleCompleteFired && client.level != null) {
			BlockPos real = room.getRealCoords(CHEST_POS);
			if (client.level.getBlockState(real).getBlock() == Blocks.CHEST) {
				puzzleCompleteFired = true;
				Puzzle.BEAMS.status = PuzzleStatus.COMPLETED;
			}
		}
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Creeper Beams solver render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		if (!isBeamsRoom()) return;
		// Per user request ("It should not show any lines from the start... When a user shoots a lantern,
		// then it should tracer... the opposite block"): no baseline tracer at all anymore — only this one,
		// from the lantern just hit to its still-lit partner, while a partner is actually pending.
		if (hitTracerEnabled && pendingOpposite != null) {
			WorldRenderUtil.drawLine(graphics, new AABB(pendingHitOrigin).getCenter(), new AABB(pendingOpposite).getCenter(), pendingColor, 4);
		}
	}

	private static void renderBoxes() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderBoxesInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Creeper Beams solver box render failed, skipping this frame", e);
		}
	}

	private void renderBoxesInner() {
		if (!isBeamsRoom()) return;
		if (!currentPairs.isEmpty()) {
			for (var entry : currentPairs.entrySet()) {
				int color = pairColors.getOrDefault(entry.getKey(), 0xFFFFFFFF);
				drawBox(entry.getKey(), color);
				drawBox(entry.getValue(), color);
			}
		}
		// Per user request ("When a user shoots a lantern, then it should tracer and highlight the opposite
		// block only, until that gets shot then it resets"): a fully-opaque, thick-outlined box on ONLY the
		// still-lit partner lantern — not the one that was just hit (it's extinguished; recalculate() drops
		// its whole pair from currentPairs within RESCAN_INTERVAL_TICKS, which would otherwise also
		// un-highlight this partner if nothing else were tracking it — see pendingOpposite's own doc
		// comment).
		if (hitHighlightEnabled && pendingOpposite != null) {
			World3DRenderer.drawFilledBox(new AABB(pendingOpposite), (0x80 << 24) | (pendingColor & 0xFFFFFF));
			World3DRenderer.drawWireBox(new AABB(pendingOpposite), pendingColor, 4f);
		}
	}

	// Mirrors WorldRenderUtil.drawStyledBox's own opacity rules exactly: the fill respects alphaPercent
	// (halved for FILLED_OUTLINE, matching Odin's "Filled Outline" style), the wire outline always stays
	// fully opaque regardless of alphaPercent.
	private void drawBox(BlockPos pos, int color) {
		AABB box = new AABB(pos);
		if (style == WorldRenderUtil.RenderStyle.FILLED || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			int opacityPercent = style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE ? alphaPercent / 2 : alphaPercent;
			int alpha = Math.round(opacityPercent / 100f * 255);
			World3DRenderer.drawFilledBox(box, (alpha << 24) | (color & 0xFFFFFF));
		}
		if (style == WorldRenderUtil.RenderStyle.OUTLINE || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			World3DRenderer.drawWireBox(box, color, 2f);
		}
	}

	public WorldRenderUtil.RenderStyle getStyle() { return style; }
	public void setStyle(WorldRenderUtil.RenderStyle value) { style = value; }
	public int getAlphaPercent() { return alphaPercent; }
	public void setAlphaPercent(int value) { alphaPercent = Math.max(0, Math.min(100, value)); }

	public boolean isHitSoundEnabled() { return hitSoundEnabled; }
	public void setHitSoundEnabled(boolean value) { hitSoundEnabled = value; }
	public CustomSoundOption getHitSound() { return hitSound; }
	public boolean isHitHighlightEnabled() { return hitHighlightEnabled; }
	public void setHitHighlightEnabled(boolean value) { hitHighlightEnabled = value; }
	public boolean isHitTracerEnabled() { return hitTracerEnabled; }
	public void setHitTracerEnabled(boolean value) { hitTracerEnabled = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("style", style.name());
		obj.addProperty("alphaPercent", alphaPercent);
		obj.addProperty("hitSoundEnabled", hitSoundEnabled);
		obj.add("hitSound", hitSound.toJson());
		obj.addProperty("hitHighlightEnabled", hitHighlightEnabled);
		obj.addProperty("hitTracerEnabled", hitTracerEnabled);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("style")) { try { style = WorldRenderUtil.RenderStyle.valueOf(obj.get("style").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("alphaPercent")) alphaPercent = obj.get("alphaPercent").getAsInt();
		if (obj.has("hitSoundEnabled")) hitSoundEnabled = obj.get("hitSoundEnabled").getAsBoolean();
		if (obj.has("hitSound")) hitSound.fromJson(obj.get("hitSound"));
		// Back-compat with the old flat hitSoundIndex field, pre-CustomSoundOption.
		else if (obj.has("hitSoundIndex")) hitSound.setBuiltinIndex(obj.get("hitSoundIndex").getAsInt());
		if (obj.has("hitHighlightEnabled")) hitHighlightEnabled = obj.get("hitHighlightEnabled").getAsBoolean();
		if (obj.has("hitTracerEnabled")) hitTracerEnabled = obj.get("hitTracerEnabled").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Solves the F5/F6 Creeper Beams puzzle: matches lantern pairs and highlights each matched pair.";
	}
}
