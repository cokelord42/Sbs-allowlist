package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Announces Mimic/Prince/Bat kills in party chat on floor 6/7, ported from Odin's {@code Mimic.kt}.
 *
 * <p>Odin detects the Mimic kill precisely via the baby zombie's death-<b>animation</b> packet event
 * ({@code ClientboundEntityEventPacket} id 3). This codebase now has a Mixin for that exact packet
 * ({@code EntityEventMixin}, added per user report — "killing a mimic still doesnt seem to send a
 * message" — the earlier tick-diff despawn heuristic below evidently wasn't catching it reliably in
 * practice), matching Odin's real detection precisely. The tick-diff approach (track nearby baby zombies
 * each tick, treat one disappearing while on floor 6/7 during the clear phase as the kill) is kept as a
 * redundant fallback rather than removed outright — cheap, and {@code DungeonState.isMimicKilled()}
 * already latches immediately so having both fire is harmless.
 */
public class MimicFeature extends Feature {
	private static final Pattern PRINCE_PATTERN = Pattern.compile("^A Prince falls\\. \\+1 Bonus Score$");
	private static final Pattern BAT_PATTERN = Pattern.compile("^A Bat has been slain\\. \\+1 Bonus Score$");
	private static final double TRACK_RANGE = 32.0;

	private boolean mimicMessageEnabled = true;
	private boolean princeMessageEnabled = true;
	private boolean batMessageEnabled = true;
	private String mimicMessage = "Mimic Killed!";
	private String princeMessage = "Prince Killed!";
	private String batMessage = "Bat Killed!";

	// Per user request ("A 'Show mimic chest' subtoggle inside of the mimic module. It should highlight the
	// mimic chest in a filled box, allow users to change the color"): the real Mimic is a baby zombie mob
	// that spawns already disguised as one of the room's decorative chests, camouflaged until triggered —
	// this is the same real entity the existing tick-diff kill-detection below already tracks (it treats
	// any tracked baby zombie on floor 6/7 outside the boss fight, before the Mimic is confirmed killed, as
	// the Mimic itself). Highlighting reuses that exact same set of candidate entities rather than any new
	// detection logic, kept resolved to live Entity references (not just ids) for rendering.
	private boolean showMimicChest = false;
	private int mimicChestColor = 0xFFFF5555;
	private final List<Zombie> currentBabyZombies = new ArrayList<>();

	private final Set<Integer> trackedBabyZombies = new HashSet<>();
	private boolean chatRegistered = false;
	private static boolean renderRegistered = false;

	private static MimicFeature instance;

	public MimicFeature() {
		super("mimic", "Mimic", FeatureCategory.COMBAT, false);
		instance = this;
	}

	/** Fed by {@code EntityEventMixin} — the precise, real signal Odin's own Mimic.kt uses (a baby
	 *  zombie's death-animation event, id 3), independent of the tick-diff fallback below. */
	public static void onEntityEvent(Entity entity, byte eventId) {
		if (instance == null || !instance.isEnabled()) return;
		if (eventId != (byte) 3) return;
		if (!(entity instanceof Zombie zombie) || !zombie.isBaby()) return;
		if (!DungeonState.isFloor(6, 7) || DungeonState.isInBoss() || DungeonState.isMimicKilled()) return;
		net.minecraft.world.level.Level level = Minecraft.getInstance().level;
		instance.mimicKilled(level != null ? findNearestTrappedChest(level, zombie.blockPosition()) : null);
	}

	@Override
	public String getSubcategory() {
		return "Dungeons";
	}

	@Override
	protected void onEnable() {
		trackedBabyZombies.clear();
		currentBabyZombies.clear();
		if (!chatRegistered) {
			chatRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (isEnabled()) onChatMessage(message.getString());
				return true;
			});
		}
		if (!renderRegistered) {
			renderRegistered = true;
			World3DRenderer.addRenderCallback(MimicFeature::renderChestHighlight);
		}
	}

	@Override
	public void onTick(Minecraft client) {
		if (client.level == null || client.player == null) return;
		if (!DungeonState.isFloor(6, 7) || DungeonState.isInBoss() || DungeonState.isMimicKilled()) {
			trackedBabyZombies.clear();
			currentBabyZombies.clear();
			return;
		}

		Set<Integer> nearby = new HashSet<>();
		List<Zombie> nearbyEntities = new ArrayList<>();
		for (Entity entity : client.level.getEntities(client.player, client.player.getBoundingBox().inflate(TRACK_RANGE),
			e -> e instanceof Zombie zombie && zombie.isBaby())) {
			nearby.add(entity.getId());
			nearbyEntities.add((Zombie) entity);
		}

		for (int id : trackedBabyZombies) {
			if (!nearby.contains(id)) {
				net.minecraft.core.BlockPos chestPos = null;
				for (Zombie old : currentBabyZombies) {
					if (old.getId() == id) {
						chestPos = findNearestTrappedChest(client.level, old.blockPosition());
						break;
					}
				}
				mimicKilled(chestPos);
				break;
			}
		}
		trackedBabyZombies.clear();
		trackedBabyZombies.addAll(nearby);
		currentBabyZombies.clear();
		currentBabyZombies.addAll(nearbyEntities);
	}

	// Per user request ("The 'Show mimic chest' feature also doesnt work, it should literally just 3d
	// occluded/depth check highlight the trapped chest a mimic spawns in"): this used to highlight the
	// tracked baby zombie ENTITY's own bounding box — the real, camouflaged mob, not the decorative trapped
	// chest BLOCK it's disguised as sitting in/next to, which is what the player actually sees and needs
	// highlighted. World3DRenderer's plain drawFilledBox/drawWireBox (as opposed to their own *ThroughWalls
	// siblings) already render as real depth-tested world geometry — genuinely occluded by walls — so no
	// separate occlusion mechanism is needed here, just highlighting the right block.
	private static final double CHEST_SEARCH_RADIUS = 1.5;

	private static void renderChestHighlight() {
		if (instance == null || !instance.isEnabled() || !instance.showMimicChest) return;
		if (!DungeonState.isFloor(6, 7) || DungeonState.isInBoss() || DungeonState.isMimicKilled()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		try {
			for (Zombie zombie : instance.currentBabyZombies) {
				if (!zombie.isAlive()) continue;
				net.minecraft.core.BlockPos chestPos = findNearestTrappedChest(mc.level, zombie.blockPosition());
				if (chestPos == null) continue;
				AABB box = new AABB(chestPos);
				int alpha = 0x60 << 24;
				World3DRenderer.drawFilledBox(box, alpha | (instance.mimicChestColor & 0xFFFFFF));
				World3DRenderer.drawWireBox(box, instance.mimicChestColor, 2f);
			}
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Mimic chest highlight render failed, skipping this frame", e);
		}
	}

	/** The closest real TRAPPED_CHEST block within a small radius of the tracked mimic entity's own position
	 *  — that entity is always spawned co-located with the decorative chest it's disguised as, per this
	 *  class's own doc comment. Null if none is found (e.g. the disguise happens to be a different block type
	 *  this round, or the entity hasn't settled into position yet). */
	private static net.minecraft.core.BlockPos findNearestTrappedChest(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos center) {
		net.minecraft.core.BlockPos closest = null;
		double closestDistSq = Double.MAX_VALUE;
		int r = (int) Math.ceil(CHEST_SEARCH_RADIUS);
		for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
				center.offset(-r, -r, -r), center.offset(r, r, r))) {
			if (!level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) continue;
			double distSq = pos.distSqr(center);
			if (distSq > CHEST_SEARCH_RADIUS * CHEST_SEARCH_RADIUS) continue;
			if (distSq < closestDistSq) {
				closestDistSq = distSq;
				closest = pos.immutable();
			}
		}
		return closest;
	}

	private void onChatMessage(String text) {
		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) return;
		if (PRINCE_PATTERN.matcher(text).matches()) princeKilled();
		if (BAT_PATTERN.matcher(text).matches()) batKilled();
	}

	// Real bug found (per user report — "Dungeon Routes not advancing when a step's target is a mimic-disguised
	// chest"): a Mimic's hitbox sits directly on/over the same block it's camouflaged as, so a right-click
	// aimed at "the chest" resolves to the entity, not the block — DungeonBlockDetector's UseBlockCallback
	// listener (the only thing that normally advances a SECRET_CHEST route step) never fires at all near a
	// live Mimic. The kill itself is a real, unambiguous secret-chest-equivalent event, so it's now routed
	// into the exact same step-advance path a real chest click uses, using the disguise's own decorative
	// TRAPPED_CHEST block position (resolved by the caller via findNearestTrappedChest before the entity
	// reference goes stale) as the click position DungeonRoutesFeature's own proximity check expects.
	private void mimicKilled(net.minecraft.core.BlockPos chestPos) {
		if (DungeonState.isMimicKilled()) return;
		DungeonState.setMimicKilled();
		if (mimicMessageEnabled) sendPartyChat(mimicMessage);
		if (chestPos != null) DungeonRoutesFeature.notifyMimicChestRevealed(chestPos);
	}

	private void princeKilled() {
		if (DungeonState.isPrinceKilled()) return;
		DungeonState.setPrinceKilled();
		if (princeMessageEnabled) sendPartyChat(princeMessage);
	}

	private void batKilled() {
		if (DungeonState.isBatKilled()) return;
		DungeonState.setBatKilled();
		if (batMessageEnabled) sendPartyChat(batMessage);
	}

	private void sendPartyChat(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.connection != null) {
			mc.player.connection.sendCommand("pc " + message);
		}
	}

	public boolean isMimicMessageEnabled() { return mimicMessageEnabled; }
	public void setMimicMessageEnabled(boolean value) { mimicMessageEnabled = value; }
	public boolean isPrinceMessageEnabled() { return princeMessageEnabled; }
	public void setPrinceMessageEnabled(boolean value) { princeMessageEnabled = value; }
	public boolean isBatMessageEnabled() { return batMessageEnabled; }
	public void setBatMessageEnabled(boolean value) { batMessageEnabled = value; }
	public String getMimicMessage() { return mimicMessage; }
	public void setMimicMessage(String value) { mimicMessage = value == null || value.isBlank() ? "Mimic Killed!" : value; }
	public String getPrinceMessage() { return princeMessage; }
	public void setPrinceMessage(String value) { princeMessage = value == null || value.isBlank() ? "Prince Killed!" : value; }
	public String getBatMessage() { return batMessage; }
	public void setBatMessage(String value) { batMessage = value == null || value.isBlank() ? "Bat Killed!" : value; }
	public boolean isShowMimicChest() { return showMimicChest; }
	public void setShowMimicChest(boolean value) { showMimicChest = value; }
	public int getMimicChestColor() { return mimicChestColor; }
	public void setMimicChestColor(int value) { mimicChestColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("mimicMessageEnabled", mimicMessageEnabled);
		obj.addProperty("princeMessageEnabled", princeMessageEnabled);
		obj.addProperty("batMessageEnabled", batMessageEnabled);
		obj.addProperty("mimicMessage", mimicMessage);
		obj.addProperty("princeMessage", princeMessage);
		obj.addProperty("batMessage", batMessage);
		obj.addProperty("showMimicChest", showMimicChest);
		obj.addProperty("mimicChestColor", mimicChestColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("mimicMessageEnabled")) mimicMessageEnabled = obj.get("mimicMessageEnabled").getAsBoolean();
		if (obj.has("princeMessageEnabled")) princeMessageEnabled = obj.get("princeMessageEnabled").getAsBoolean();
		if (obj.has("batMessageEnabled")) batMessageEnabled = obj.get("batMessageEnabled").getAsBoolean();
		if (obj.has("mimicMessage")) mimicMessage = obj.get("mimicMessage").getAsString();
		if (obj.has("princeMessage")) princeMessage = obj.get("princeMessage").getAsString();
		if (obj.has("batMessage")) batMessage = obj.get("batMessage").getAsString();
		if (obj.has("showMimicChest")) showMimicChest = obj.get("showMimicChest").getAsBoolean();
		if (obj.has("mimicChestColor")) mimicChestColor = obj.get("mimicChestColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Announces Mimic/Prince/Bat kills to your party on floor 6/7.";
	}
}
