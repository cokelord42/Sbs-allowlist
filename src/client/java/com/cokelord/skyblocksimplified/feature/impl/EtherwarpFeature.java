package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.cokelord.skyblocksimplified.sound.SoundMuteRegistry;
import com.cokelord.skyblocksimplified.sound.SoundPlayListenerRegistry;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shows exactly where the Etherwarp Conduit / Aspect of the Void will land before you commit — a pure
 * visual preview, nothing more. Ported from Odin's {@code Etherwarp.kt} — the voxel DDA raycast algorithm
 * (credited there to "Bloom") is ported faithfully; the block-passability classification is rebuilt as an
 * on-demand-cached {@code Map<Block, Integer>} instead of pre-scanning the entire block-state registry
 * into an array (avoids depending on {@code Block.getId}/{@code BLOCK_STATE_REGISTRY} internals that may
 * not carry the same names in this MC version — classification is by block type either way, so the result
 * is identical).
 *
 * <p><b>Deliberately does not touch player position or send any packets.</b> Etherwarp is a
 * server-authoritative Hypixel ability: the real right-click is left to pass through untouched, the server
 * teleports the player and syncs the new position back down. An earlier version of this port
 * client-side-set the player's position on use (self-teleport, zero travel time) to "predict" the server —
 * that's exactly the signature real Etherwarp-range/speed cheats have, since a legitimate teleport still
 * takes at least one full round trip to the server. Never re-add position/packet manipulation here.
 *
 * <p>Success-sound subtoggle (per user request — "Etherwarp success sound doesn't work, and it can be a
 * subtoggle in the etherwarp helper instead"): merged in from the standalone EtherwarpSoundFeature this
 * was previously shipped as. Real bug found in that original version: it matched Hypixel's real landing
 * sound (vanilla Ender Dragon hurt, {@link #VANILLA_SOUND_PATH}) by an EXACT float {@code ==} comparison
 * against both a hardcoded pitch AND volume — any real-world floating-point rounding difference (which
 * this exact value is genuinely prone to, being a repeating decimal in binary) made the match silently
 * never succeed, which read as "the sound doesn't work at all." Now matches by sound path alone (still
 * scoped to the same real click-timing window below, so it can't fire on an unrelated dragon-hurt sound
 * elsewhere), which is the same real-world-safe matching precision every other sound-replace feature in
 * this codebase (Arrow Hit Sound, etc.) already uses. */
public class EtherwarpFeature extends Feature {
	private static final int PASSABLE = 1;
	private static final int BLOCKS_FEET = 2;
	private static final Set<String> ETHERWARP_ITEM_IDS = Set.of("ETHERWARP_CONDUIT", "ASPECT_OF_THE_VOID");

	// Real, confirmed value (Devonian's own source) for Hypixel's own Etherwarp-landed sound path.
	private static final String VANILLA_SOUND_PATH = "entity.ender_dragon.hurt";
	private static final long SOUND_CLICK_WINDOW_MILLIS = 700L;
	public static final String[] SOUND_IDS = {
		"entity.ender_dragon.hurt", "entity.enderman.teleport", "entity.experience_orb.pickup", "block.note_block.pling"
	};
	public static final String[] SOUND_LABELS = {"Vanilla (Dragon Hurt)", "Enderman Teleport", "Orb Pickup", "Pling"};

	public record EtherPos(boolean succeeded, BlockPos pos, BlockState state) {
		static final EtherPos NONE = new EtherPos(false, null, null);
	}

	private boolean showGuess = true;
	private int succeedColor = 0xD9FFAA00;
	private boolean showWhenFailed = true;
	private int failColor = 0xD9FF5555;
	private WorldRenderUtil.RenderStyle style = WorldRenderUtil.RenderStyle.OUTLINE;
	private boolean fullBlock = false;
	private boolean successSoundEnabled = false;
	private final CustomSoundOption successSound = new CustomSoundOption(SOUND_IDS, SOUND_LABELS);

	private final Map<Block, Integer> blockFlagsCache = new HashMap<>();
	private ItemStack cachedMainHandItem = ItemStack.EMPTY;
	private String cachedEtherwarpItemId = null;
	private EtherPos etherPos = EtherPos.NONE;
	private long lastClickAtMillis = -1L;

	private static boolean listenersRegistered = false;
	private static EtherwarpFeature instance;

	public EtherwarpFeature() {
		super("etherwarp", "Etherwarp", FeatureCategory.INVENTORY, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			World3DRenderer.addRenderCallback(EtherwarpFeature::renderStatic);

			UseItemCallback.EVENT.register((player, level, hand) -> {
				if (instance != null && instance.isEnabled()) instance.onRightClick(hand);
				return InteractionResult.PASS;
			});
			UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
				if (instance != null && instance.isEnabled()) instance.onRightClick(hand);
				return InteractionResult.PASS;
			});
			SoundMuteRegistry.setRule("etherwarp_sound", inst -> instance != null && instance.isEnabled() && instance.matchesLandingSound(inst));
			SoundPlayListenerRegistry.setListener("etherwarp_sound", inst -> {
				if (instance != null && instance.isEnabled() && instance.matchesLandingSound(inst)) instance.playSuccessSound();
			});
		}
	}

	private void onRightClick(InteractionHand hand) {
		if (!successSoundEnabled) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		ItemStack stack = mc.player.getItemInHand(hand);
		String id = SkyblockNbtUtils.getItemId(stack);
		if (id == null || !ETHERWARP_ITEM_IDS.contains(id)) return;
		if ("ASPECT_OF_THE_VOID".equals(id) && !mc.player.isShiftKeyDown()) return;
		lastClickAtMillis = System.currentTimeMillis();
	}

	private boolean matchesLandingSound(SoundInstance inst) {
		if (!successSoundEnabled) return false;
		if (lastClickAtMillis < 0) return false;
		long sinceClick = System.currentTimeMillis() - lastClickAtMillis;
		if (!successSound.isUseCustom() && successSound.getBuiltinIndex() == 0) return false; // "Vanilla" — nothing to swap.
		if (sinceClick > SOUND_CLICK_WINDOW_MILLIS) return false;
		return VANILLA_SOUND_PATH.equals(inst.getIdentifier().getPath());
	}

	// Per user request ("Allow users to play the sound they have selected in all sound selectors throughout
	// the mod"): made public so MainScreen's settings panel can preview the currently-selected sound
	// directly — this already plays unconditionally regardless of successSoundEnabled (that gate lives in
	// matchesLandingSound, the real trigger path), so calling it straight from a preview button is safe.
	public void playSuccessSound() {
		successSound.play();
	}

	@Override
	public void onTick(Minecraft client) {
		if (!showGuess || client.gui.screen() != null || client.player == null) { etherPos = EtherPos.NONE; return; }

		ItemStack mainHand = client.player.getMainHandItem();
		if (!ItemStack.matches(cachedMainHandItem, mainHand)) {
			cachedMainHandItem = mainHand.copy();
			String id = SkyblockNbtUtils.getItemId(mainHand);
			cachedEtherwarpItemId = id != null && ETHERWARP_ITEM_IDS.contains(id) ? id : null;
		}
		if (cachedEtherwarpItemId == null) { etherPos = EtherPos.NONE; return; }
		if (!client.player.isShiftKeyDown() && !"ETHERWARP_CONDUIT".equals(cachedEtherwarpItemId)) { etherPos = EtherPos.NONE; return; }

		Integer bonus = SkyblockNbtUtils.getAttributeInt(mainHand, "tuned_transmission");
		double range = 57.0 + (bonus != null ? bonus : 0);
		etherPos = computeEtherPos(client.player.position(), range);
	}

	private EtherPos computeEtherPos(Vec3 position, double distance) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return EtherPos.NONE;
		double eyeHeight = mc.player.getPose() == Pose.SWIMMING ? 0.4 : mc.player.isCrouching() ? 1.27 : 1.62;
		Vec3 start = position.add(0, eyeHeight, 0);
		Vec3 look = mc.player.getLookAngle();
		Vec3 end = start.add(look.scale(distance));
		return traverseVoxels(start, end);
	}

	private EtherPos traverseVoxels(Vec3 start, Vec3 end) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return EtherPos.NONE;

		double x0 = start.x, y0 = start.y, z0 = start.z;
		double x1 = end.x, y1 = end.y, z1 = end.z;

		int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
		int endX = (int) Math.floor(x1), endY = (int) Math.floor(y1), endZ = (int) Math.floor(z1);

		double dirX = x1 - x0, dirY = y1 - y0, dirZ = z1 - z0;
		int stepX = (int) Math.signum(dirX), stepY = (int) Math.signum(dirY), stepZ = (int) Math.signum(dirZ);

		double invDirX = dirX != 0 ? 1.0 / dirX : Double.MAX_VALUE;
		double invDirY = dirY != 0 ? 1.0 / dirY : Double.MAX_VALUE;
		double invDirZ = dirZ != 0 ? 1.0 / dirZ : Double.MAX_VALUE;

		double tDeltaX = Math.abs(invDirX * stepX);
		double tDeltaY = Math.abs(invDirY * stepY);
		double tDeltaZ = Math.abs(invDirZ * stepZ);

		double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
		double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
		double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);

		for (int step = 0; step < 1000; step++) {
			BlockPos blockPos = new BlockPos(x, y, z);
			BlockState state = mc.level.getBlockState(blockPos);
			int flags = flagsFor(state.getBlock());
			boolean passable = (flags & PASSABLE) != 0;

			if (!passable) {
				double collisionTop = state.getCollisionShape(mc.level, blockPos).max(net.minecraft.core.Direction.Axis.Y);
				int clearanceBaseY = blockPos.getY() + Math.max(1, (int) Math.ceil(collisionTop));

				BlockState feetState = mc.level.getBlockState(new BlockPos(x, clearanceBaseY, z));
				int feetFlags = flagsFor(feetState.getBlock());
				if ((feetFlags & PASSABLE) == 0 || (feetFlags & BLOCKS_FEET) != 0) return new EtherPos(false, blockPos, state);

				BlockState headState = mc.level.getBlockState(new BlockPos(x, clearanceBaseY + 1, z));
				int headFlags = flagsFor(headState.getBlock());
				if ((headFlags & PASSABLE) == 0 || (headFlags & BLOCKS_FEET) != 0) return new EtherPos(false, blockPos, state);

				return new EtherPos(true, blockPos, state);
			}

			if (x == endX && y == endY && z == endZ) return EtherPos.NONE;

			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) { tMaxX += tDeltaX; x += stepX; }
			else if (tMaxY <= tMaxZ) { tMaxY += tDeltaY; y += stepY; }
			else { tMaxZ += tDeltaZ; z += stepZ; }
		}
		return EtherPos.NONE;
	}

	private int flagsFor(Block block) {
		return blockFlagsCache.computeIfAbsent(block, EtherwarpFeature::classify);
	}

	private static int classify(Block block) {
		boolean passable = switch (block) {
			case net.minecraft.world.level.block.AirBlock ignored -> true;
			case net.minecraft.world.level.block.BushBlock ignored -> true;
			case net.minecraft.world.level.block.TorchBlock ignored -> true;
			case net.minecraft.world.level.block.BaseRailBlock ignored -> true;
			case net.minecraft.world.level.block.FireBlock ignored -> true;
			case net.minecraft.world.level.block.VineBlock ignored -> true;
			case net.minecraft.world.level.block.LiquidBlock ignored -> true;
			case net.minecraft.world.level.block.SaplingBlock ignored -> true;
			case net.minecraft.world.level.block.CropBlock ignored -> true;
			case net.minecraft.world.level.block.StemBlock ignored -> true;
			case net.minecraft.world.level.block.SugarCaneBlock ignored -> true;
			case net.minecraft.world.level.block.MushroomBlock ignored -> true;
			case net.minecraft.world.level.block.NetherWartBlock ignored -> true;
			case net.minecraft.world.level.block.RedStoneWireBlock ignored -> true;
			case net.minecraft.world.level.block.DiodeBlock ignored -> true;
			case net.minecraft.world.level.block.DoublePlantBlock ignored -> true;
			case net.minecraft.world.level.block.LeverBlock ignored -> true;
			case net.minecraft.world.level.block.SnowLayerBlock ignored -> true;
			case net.minecraft.world.level.block.BubbleColumnBlock ignored -> true;
			case net.minecraft.world.level.block.piston.PistonHeadBlock ignored -> true;
			case net.minecraft.world.level.block.ButtonBlock ignored -> true;
			case net.minecraft.world.level.block.LanternBlock ignored -> true;
			case net.minecraft.world.level.block.AbstractSkullBlock ignored -> true;
			case net.minecraft.world.level.block.LadderBlock ignored -> true;
			case net.minecraft.world.level.block.FlowerPotBlock ignored -> true;
			case net.minecraft.world.level.block.WebBlock ignored -> true;
			case net.minecraft.world.level.block.NetherPortalBlock ignored -> true;
			default -> false;
		};

		boolean blocksFeet = switch (block) {
			case net.minecraft.world.level.block.AbstractSkullBlock ignored -> true;
			case net.minecraft.world.level.block.FlowerPotBlock ignored -> true;
			case net.minecraft.world.level.block.LadderBlock ignored -> true;
			case net.minecraft.world.level.block.VineBlock ignored -> true;
			default -> false;
		};

		int flags = 0;
		if (passable) flags |= PASSABLE;
		if (blocksFeet) flags |= BLOCKS_FEET;
		return flags;
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled() || !instance.showGuess) return;
		try {
			instance.renderInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Etherwarp render failed, skipping this frame", e);
		}
	}

	// Real depth-tested 3D rendering (World3DRenderer) instead of WorldRenderUtil's screen-space
	// projection, per user request — the landing-spot box now actually sits on the block, occluded by
	// terrain in front of it, instead of a flat rectangle drawn over whatever's on screen.
	private void renderInner() {
		if (etherPos.pos() == null) return;
		if (!etherPos.succeeded() && !showWhenFailed) return;
		int color = etherPos.succeeded() ? succeedColor : failColor;
		AABB box = fullBlock || etherPos.state() == null
			? new AABB(etherPos.pos())
			: boundsOf(etherPos.state(), etherPos.pos());
		if (style == WorldRenderUtil.RenderStyle.FILLED || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			World3DRenderer.drawFilledBox(box, color);
		}
		if (style == WorldRenderUtil.RenderStyle.OUTLINE || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			World3DRenderer.drawWireBox(box, color, 2f);
		}
	}

	private static AABB boundsOf(BlockState state, BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return new AABB(pos);
		var shape = state.getCollisionShape(mc.level, pos);
		AABB bounds = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
		return bounds.move(pos);
	}

	public boolean isShowGuess() { return showGuess; }
	public void setShowGuess(boolean value) { showGuess = value; }
	public int getSucceedColor() { return succeedColor; }
	public void setSucceedColor(int value) { succeedColor = value; }
	public boolean isShowWhenFailed() { return showWhenFailed; }
	public void setShowWhenFailed(boolean value) { showWhenFailed = value; }
	public int getFailColor() { return failColor; }
	public void setFailColor(int value) { failColor = value; }
	public WorldRenderUtil.RenderStyle getStyle() { return style; }
	public void setStyle(WorldRenderUtil.RenderStyle value) { style = value; }
	public boolean isFullBlock() { return fullBlock; }
	public void setFullBlock(boolean value) { fullBlock = value; }
	public boolean isSuccessSoundEnabled() { return successSoundEnabled; }
	public void setSuccessSoundEnabled(boolean value) { successSoundEnabled = value; }
	public CustomSoundOption getSuccessSound() { return successSound; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showGuess", showGuess);
		obj.addProperty("succeedColor", succeedColor);
		obj.addProperty("showWhenFailed", showWhenFailed);
		obj.addProperty("failColor", failColor);
		obj.addProperty("style", style.name());
		obj.addProperty("fullBlock", fullBlock);
		obj.addProperty("successSoundEnabled", successSoundEnabled);
		obj.add("successSound", successSound.toJson());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("showGuess")) showGuess = obj.get("showGuess").getAsBoolean();
		if (obj.has("succeedColor")) succeedColor = obj.get("succeedColor").getAsInt();
		if (obj.has("showWhenFailed")) showWhenFailed = obj.get("showWhenFailed").getAsBoolean();
		if (obj.has("failColor")) failColor = obj.get("failColor").getAsInt();
		if (obj.has("style")) { try { style = WorldRenderUtil.RenderStyle.valueOf(obj.get("style").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("fullBlock")) fullBlock = obj.get("fullBlock").getAsBoolean();
		if (obj.has("successSoundEnabled")) successSoundEnabled = obj.get("successSoundEnabled").getAsBoolean();
		if (obj.has("successSound")) successSound.fromJson(obj.get("successSound"));
		// Back-compat with the old flat successSoundIndex field, pre-CustomSoundOption.
		else if (obj.has("successSoundIndex")) successSound.setBuiltinIndex(obj.get("successSoundIndex").getAsInt());
	}

	@Override
	public String getDescription() {
		return "Shows a preview of exactly where your Etherwarp will land before you commit to it.";
	}
}
