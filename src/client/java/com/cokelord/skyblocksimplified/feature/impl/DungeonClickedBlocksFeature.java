package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonBlockDetector;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.cokelord.skyblocksimplified.sound.SoundMuteRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.AABB;

import java.util.regex.Pattern;

/**
 * Flags levers, chests, item-secret pickups, and Wither Essence pickups you've triggered recently — one
 * color per category, per user request — ported from SkyHanni's DungeonHighlightClickedBlocks.kt (see
 * DungeonBlockDetector's own doc comment for the block/pickup detection this reads).
 *
 * <p>Originally a HUD text readout (no real 3D rendering hook existed yet when this was first ported).
 * Per user request ("remove the little gui from this module, fix the highlight") this now draws a real
 * depth-tested-equivalent world-space box on the actual clicked block/pickup position via
 * {@link WorldRenderUtil} (the same infra Etherwarp/the puzzle solvers use) instead of a movable HUD label
 * — there's no more GUI element to drag around, just the block itself lighting up for a few seconds.
 *
 * <p>Secret Chime, and the chest-open/lever-click sound mutes, live inside this module's settings —
 * they're all reactions to the exact same set of clicks/pickups this module already detects.
 */
public class DungeonClickedBlocksFeature extends Feature {
	private static final Pattern LOCKED_CHEST = Pattern.compile("§cThat chest is locked!");
	private static final long DISPLAY_MILLIS = 3000;

	private int chestColor = 0xFFFFFF55;
	private int leverColor = 0xFF55FFFF;
	private int witherEssenceColor = 0xFF9955FF;
	private int itemSecretColor = 0xFF55FF55;

	private boolean chimeEnabled = false;
	private String chimeSoundId = "entity.experience_orb.pickup";
	private float chimePitch = 1.0f;
	private boolean muteChestSound = false;
	private boolean muteLeverSound = false;
	private boolean muteBatSound = false;

	// Per user request ("allow the user to change the render type... between outline and filled... allow
	// them to also change the opacity") — same WorldRenderUtil.RenderStyle/alphaPercent pattern the puzzle
	// solvers already use (see BeamsSolverFeature's drawBox), kept as OUTLINE by default to match the
	// previous hardcoded behavior.
	private WorldRenderUtil.RenderStyle style = WorldRenderUtil.RenderStyle.OUTLINE;
	private int alphaPercent = 60;

	private BlockPos lastPos;
	private int lastColor = 0xFFFFFFFF;
	private long lastMillis = Long.MIN_VALUE;

	private static boolean listenersRegistered = false;
	private static DungeonClickedBlocksFeature instance;

	public DungeonClickedBlocksFeature() {
		super("dungeon_clicked_blocks", "Clicked Blocks", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() {
		return "Dungeons";
	}

	@Override
	public java.util.List<String> getSearchAliases() { return java.util.List.of("Secret Clicked"); }

	@Override
	protected void onEnable() {
		applyMuteRules();
		if (!listenersRegistered) {
			listenersRegistered = true;
			DungeonBlockDetector.addListener((pos, type) -> {
				if (instance == null) return;
				// ANY_ITEM_PICKUP is DungeonRoutesFeature's own proximity-based fallback signal (see that
				// type's own doc comment on DungeonBlockDetector) — it fires for every mundane item pickup in
				// a dungeon (mob drops, floor loot, everything), not just the specifically-known secret item
				// types this module highlights/chimes for. Ignored here entirely rather than flooding the
				// chime/highlight on every ordinary pickup.
				if (type == DungeonBlockDetector.ClickedBlockType.ANY_ITEM_PICKUP) return;
				// Per user request: none of this should fire during the boss fight — Simon Says alone means
				// dozens of real lever/button clicks in quick succession there, none of which are the kind of
				// "you found a secret/chest" moment this module is meant to flag.
				if (com.cokelord.skyblocksimplified.dungeon.DungeonState.isInBoss()) return;
				// Real bug found (per user report — "Clicked Blocks should only trigger in rooms with
				// secrets"): this used to fire in every room regardless of whether it actually has any
				// secrets to find (Blood/Fairy/puzzle rooms, etc.), flagging ordinary lever/chest clicks
				// that have nothing to do with secret-hunting. Reuses the exact same room.data.getSecrets()
				// accessor Dungeon Map already reads for its own room-label rendering, rather than a new one.
				if (!currentRoomHasSecrets()) return;
				if (type == DungeonBlockDetector.ClickedBlockType.SECRET_BAT_DEATH) {
					instance.playChime();
					return;
				}
				instance.playChime();
				if (!instance.isEnabled()) return;
				instance.show(pos, instance.colorFor(type));
			});
			ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
				if (overlay || instance == null || !instance.isEnabled()) return;
				if (com.cokelord.skyblocksimplified.dungeon.DungeonState.isInBoss()) return;
				if (!currentRoomHasSecrets()) return;
				if (LOCKED_CHEST.matcher(message.getString()).matches()) {
					Minecraft mc = Minecraft.getInstance();
					if (mc.player != null) instance.show(mc.player.blockPosition(), instance.chestColor);
				}
			});
			// Real depth-tested 3D rendering (World3DRenderer) instead of WorldRenderUtil's screen-space
			// projection, per user request ("still renders a 2d box instead of rendering a highlight on the
			// block... look at the etherwarp feature") — matches EtherwarpFeature's own exact pattern.
			World3DRenderer.addRenderCallback(DungeonClickedBlocksFeature::renderStatic);
		}
	}

	/** Same accessor Dungeon Map already reads for its own "found/max secrets" room label
	 *  (see DungeonMapFeature's own room-label rendering) — a room with no secrets at all (Blood, Fairy,
	 *  most puzzle rooms) has room.data.getSecrets() == 0. */
	private static boolean currentRoomHasSecrets() {
		DungeonRoom room = WorldScan.getCurrentRoom();
		return room != null && room.data != null && room.data.getSecrets() > 0;
	}

	private int colorFor(DungeonBlockDetector.ClickedBlockType type) {
		return switch (type) {
			case LEVER -> leverColor;
			case CHEST, TRAPPED_CHEST -> chestColor;
			case WITHER_ESSENCE_PICKUP -> witherEssenceColor;
			case ITEM_SECRET_PICKUP -> itemSecretColor;
			case SECRET_BAT_DEATH -> itemSecretColor;
			// Never actually reached — the listener above returns before colorFor for ANY_ITEM_PICKUP.
			case ANY_ITEM_PICKUP -> itemSecretColor;
		};
	}

	private void show(BlockPos pos, int color) {
		lastPos = pos;
		lastColor = color;
		lastMillis = System.currentTimeMillis();
	}

	private void playChime() {
		if (!chimeEnabled) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Identifier id = Identifier.tryParse(chimeSoundId);
		if (id == null) return;
		SoundEvent soundEvent = SoundEvent.createVariableRangeEvent(id);
		// forUI(event, pitch) alone actually defaults to a hardcoded 0.25 volume (confirmed by decompiling
		// the vanilla class), which reads as barely audible over dungeon combat/ambient noise — boosted well
		// past that so it actually stands out as a "chime".
		mc.getSoundManager().play(SimpleSoundInstance.forUI(soundEvent, chimePitch, 4.0f));
	}

	private void applyMuteRules() {
		if (muteChestSound) {
			SoundMuteRegistry.setRule("dungeon_clicked_blocks_mute_chest", inst -> inst.getIdentifier().getPath().contains("chest.open"));
		} else {
			SoundMuteRegistry.clearRule("dungeon_clicked_blocks_mute_chest");
		}
		if (muteLeverSound) {
			SoundMuteRegistry.setRule("dungeon_clicked_blocks_mute_lever", inst -> inst.getIdentifier().getPath().contains("lever.click"));
		} else {
			SoundMuteRegistry.clearRule("dungeon_clicked_blocks_mute_lever");
		}
		if (muteBatSound) {
			// Vanilla bats have no distinct death sound — "entity.bat.hurt" (confirmed real via SkyHanni's
			// own sound-id references) is the closest real match and plays on/near death.
			SoundMuteRegistry.setRule("dungeon_clicked_blocks_mute_bat", inst -> inst.getIdentifier().getPath().contains("bat.hurt"));
		} else {
			SoundMuteRegistry.clearRule("dungeon_clicked_blocks_mute_bat");
		}
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Clicked Blocks render failed, skipping this frame", e);
		}
	}

	private void renderInner() {
		if (lastPos == null || System.currentTimeMillis() - lastMillis > DISPLAY_MILLIS) return;
		AABB box = new AABB(lastPos);
		// Mirrors BeamsSolverFeature's own drawBox opacity rules exactly (fill respects alphaPercent, halved
		// for FILLED_OUTLINE; the wire outline always stays fully opaque).
		if (style == WorldRenderUtil.RenderStyle.FILLED || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			int opacityPercent = style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE ? alphaPercent / 2 : alphaPercent;
			int alpha = Math.round(opacityPercent / 100f * 255);
			World3DRenderer.drawFilledBox(box, (alpha << 24) | (lastColor & 0xFFFFFF));
		}
		if (style == WorldRenderUtil.RenderStyle.OUTLINE || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			World3DRenderer.drawWireBox(box, lastColor, 2f);
		}
	}

	public int getChestColor() { return chestColor; }
	public void setChestColor(int color) { this.chestColor = color; }
	public int getLeverColor() { return leverColor; }
	public void setLeverColor(int color) { this.leverColor = color; }
	public int getWitherEssenceColor() { return witherEssenceColor; }
	public void setWitherEssenceColor(int color) { this.witherEssenceColor = color; }
	public int getItemSecretColor() { return itemSecretColor; }
	public void setItemSecretColor(int color) { this.itemSecretColor = color; }

	public boolean isChimeEnabled() { return chimeEnabled; }
	public void setChimeEnabled(boolean v) { chimeEnabled = v; }
	public String getChimeSoundId() { return chimeSoundId; }
	public void setChimeSoundId(String v) { chimeSoundId = v; }

	public boolean isMuteChestSound() { return muteChestSound; }
	public void setMuteChestSound(boolean v) { muteChestSound = v; applyMuteRules(); }
	public boolean isMuteLeverSound() { return muteLeverSound; }
	public void setMuteLeverSound(boolean v) { muteLeverSound = v; applyMuteRules(); }
	public boolean isMuteBatSound() { return muteBatSound; }
	public void setMuteBatSound(boolean v) { muteBatSound = v; applyMuteRules(); }

	public WorldRenderUtil.RenderStyle getStyle() { return style; }
	public void setStyle(WorldRenderUtil.RenderStyle value) { style = value; }
	public int getAlphaPercent() { return alphaPercent; }
	public void setAlphaPercent(int value) { alphaPercent = Math.max(0, Math.min(100, value)); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("chestColor", chestColor);
		obj.addProperty("leverColor", leverColor);
		obj.addProperty("witherEssenceColor", witherEssenceColor);
		obj.addProperty("itemSecretColor", itemSecretColor);
		obj.addProperty("chimeEnabled", chimeEnabled);
		obj.addProperty("chimeSoundId", chimeSoundId);
		obj.addProperty("muteChestSound", muteChestSound);
		obj.addProperty("muteLeverSound", muteLeverSound);
		obj.addProperty("muteBatSound", muteBatSound);
		obj.addProperty("style", style.name());
		obj.addProperty("alphaPercent", alphaPercent);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("chestColor")) chestColor = obj.get("chestColor").getAsInt();
		if (obj.has("leverColor")) leverColor = obj.get("leverColor").getAsInt();
		if (obj.has("witherEssenceColor")) witherEssenceColor = obj.get("witherEssenceColor").getAsInt();
		if (obj.has("itemSecretColor")) itemSecretColor = obj.get("itemSecretColor").getAsInt();
		if (obj.has("chimeEnabled")) chimeEnabled = obj.get("chimeEnabled").getAsBoolean();
		if (obj.has("chimeSoundId")) chimeSoundId = obj.get("chimeSoundId").getAsString();
		if (obj.has("muteChestSound")) muteChestSound = obj.get("muteChestSound").getAsBoolean();
		if (obj.has("muteLeverSound")) muteLeverSound = obj.get("muteLeverSound").getAsBoolean();
		if (obj.has("muteBatSound")) muteBatSound = obj.get("muteBatSound").getAsBoolean();
		if (obj.has("style")) { try { style = WorldRenderUtil.RenderStyle.valueOf(obj.get("style").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("alphaPercent")) alphaPercent = obj.get("alphaPercent").getAsInt();
		applyMuteRules();
	}

	@Override
	public String getDescription() {
		return "Flags levers, chests, and item/essence pickups you've recently triggered in a dungeon, each in its own color.";
	}
}
