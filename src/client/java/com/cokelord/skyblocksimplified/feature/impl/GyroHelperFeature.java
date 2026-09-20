package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Real port of Odin's own confirmed {@code GyroWand.kt} (user-supplied Odin 0.3.2 source), replacing this
 * feature's previous guessed placement-preview design entirely. The real Gyrokinetic Wand ability
 * ("Gravity Storm") doesn't place a pad at a specific block at all — it pulls every mob within a fixed
 * 10-block radius of wherever you're looking toward you, for a fixed real duration. Odin's own module shows
 * exactly that: a single thick ring ("cylinder") at the block you're aiming at, radius 10, with no separate
 * shape for "where mobs get pulled to" (that IS the same ring — there's no real second "suckbox" concept in
 * the actual source, despite the user's own phrasing suggesting two shapes).
 *
 * <p>Real per-value confirmation against the user-supplied source: item check requires both
 * {@code Items.BLAZE_ROD} (the wand's real base item) AND the real {@code GYROKINETIC_WAND} id; target block
 * is whatever the player's crosshair is aiming at within 25 blocks (Odin's own voxel traversal, replicated
 * here with the vanilla equivalent raytrace); the ring itself is a real {@code drawCylinder} call — radius
 * 10, height 0.3 (a short wireframe cylinder, which is what actually reads as "thicker" than a flat 2D
 * circle — same real primitive Odin's own Positional Messages module uses, matching the user's own "like the
 * positional messages" comparison), 64 segments, centered at the target block + (0.5, 1.0, 0.5).
 *
 * <p>Per user request ("the gyro cooldown... broke when we started color coding... just rewrite to have a 30
 * second cooldown on left click and 10 second on right click, and color code the same half half like we are
 * currently doing"): rather than keep chasing a single chat-line/item-use signal for one combined cooldown
 * (the previous approach, which the user reports broke once the on/off ring color-coding was added), this
 * tracks left-click and right-click as two independent timers with their own fixed durations — the ring
 * shows {@link #cooldownColor} while EITHER is still counting down, {@link #ringColor} otherwise, the same
 * two-color scheme as before.
 *
 * <p>Real bug found (per user report — "still doesnt have a cooldown when left clicked. Only right click,
 * and its 30 seconds" instead of the intended 10): the wand's own right-click ability plays a real client-side
 * arm swing as part of its use animation, the same generic instant-item-use swing vanilla itself plays for
 * throwables — {@code LivingEntitySwingMixin} can't tell that apart from a genuine left-click attack, so every
 * real right click was ALSO silently re-arming the (longer) 30-second left-click timer right alongside the
 * real 10-second right-click one. Since the ring shows a cooldown while EITHER timer is active, every right
 * click visibly produced a 30-second ring instead of 10, and a real left-click's own signal was completely
 * indistinguishable from that noise. {@link #CONTAMINATION_WINDOW_MS} suppresses whichever of the two timers
 * lands within a short window of the other one firing — applied symmetrically since the exact local ordering
 * between the fabric item-use event and the mixin's swing callback isn't guaranteed.
 */
public class GyroHelperFeature extends Feature {
	private static final String GYRO_ITEM_ID = "GYROKINETIC_WAND";
	private static final double RANGE = 25.0;
	private static final float RADIUS = 10f;
	private static final float HEIGHT = 0.3f;
	private static final int SEGMENTS = 64;
	private static final long LEFT_CLICK_COOLDOWN_MS = 30_000L;
	private static final long RIGHT_CLICK_COOLDOWN_MS = 10_000L;
	private static final long CONTAMINATION_WINDOW_MS = 500L;

	private boolean showCooldown = true;
	private float ringWidth = 5f;
	private boolean depthCheck = true;
	private int ringColor = 0x80AA00AA; // Odin's own default: dark purple @ 50% alpha
	private int cooldownColor = 0x80FF5555; // Odin's own default: red @ 50% alpha

	private long leftClickCooldownTimer = 0L;
	private long rightClickCooldownTimer = 0L;

	private static boolean listenersRegistered = false;
	private static GyroHelperFeature instance;

	public GyroHelperFeature() {
		// Real bug found (per user report — "Remember last mod page... on newer features, for example gyro
		// helper, it just sends me to inventory and shows nothing when i reopen the menu"): getSubcategory()
		// below was moved to "Dungeons" per an earlier user request ("Move Gyro Helper into Dungeons
		// subcategory"), but this top-level category was never updated to match — "Dungeons" is one of
		// FeatureCategory.COMBAT's own fixed subcategories (see that enum's own list), not one of INVENTORY's,
		// so a (category=INVENTORY, subcategory="Dungeons") pair Remember Last Page restores is invalid and
		// lands on nothing. Same mismatch existed for plain manual browsing too, just less noticeable there.
		super("gyro_helper", "Gyro Helper", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			World3DRenderer.addRenderCallback(GyroHelperFeature::renderStatic);
			// Right-click timer — fires the instant the wand is used, no dependency on a chat line's exact
			// wording or the ~1-tick delay chat can lag behind the actual action.
			net.fabricmc.fabric.api.event.player.ItemEvents.USE.register((level, player, hand) -> {
				if (instance != null && instance.isEnabled() && level.isClientSide()
					&& hand == net.minecraft.world.InteractionHand.MAIN_HAND && player == Minecraft.getInstance().player
					&& isGyroWand(player.getMainHandItem())) {
					long now = System.currentTimeMillis();
					// See the class doc comment's "contamination" note: if a swing landed just before this
					// real right click (whichever order the two actually fire in locally), that swing was
					// almost certainly this same use's own auto-swing, not a genuine separate left-click —
					// undo it so it doesn't keep the ring showing the longer left-click duration.
					if (now - instance.leftClickCooldownTimer < CONTAMINATION_WINDOW_MS) instance.leftClickCooldownTimer = 0L;
					instance.rightClickCooldownTimer = now;
				}
				return net.minecraft.world.InteractionResult.PASS;
			});
		}
	}

	/** Left-click timer — called from {@code LivingEntitySwingMixin} for every real left-click swing of the
	 *  local player's main hand (attacking an entity, a block, or plain air all funnel through the same real
	 *  {@code LivingEntity#swing} call, so this needs no separate handling for each case). */
	public static void onLeftClickSwing() {
		if (instance == null || !instance.isEnabled()) return;
		var player = Minecraft.getInstance().player;
		if (player == null || !isGyroWand(player.getMainHandItem())) return;
		long now = System.currentTimeMillis();
		// See the class doc comment's "contamination" note: skip a swing that lands right after a real right
		// click — it's almost certainly that same click's own auto-swing side effect, not a genuine separate
		// left-click attack.
		if (now - instance.rightClickCooldownTimer < CONTAMINATION_WINDOW_MS) return;
		instance.leftClickCooldownTimer = now;
	}

	private static boolean isGyroWand(ItemStack stack) {
		return stack.getItem() == Items.BLAZE_ROD && GYRO_ITEM_ID.equals(SkyblockNbtUtils.getItemId(stack));
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;

		ItemStack mainHand = mc.player.getMainHandItem();
		if (mainHand.getItem() != Items.BLAZE_ROD || !GYRO_ITEM_ID.equals(SkyblockNbtUtils.getItemId(mainHand))) return;

		HitResult hit = mc.player.pick(RANGE, 1.0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) return;

		Vec3 center = Vec3.atLowerCornerOf(blockHit.getBlockPos()).add(0.5, 1.0, 0.5);
		long now = System.currentTimeMillis();
		boolean onCooldown = instance.showCooldown
			&& (now - instance.leftClickCooldownTimer < LEFT_CLICK_COOLDOWN_MS
				|| now - instance.rightClickCooldownTimer < RIGHT_CLICK_COOLDOWN_MS);
		int color = onCooldown ? instance.cooldownColor : instance.ringColor;
		World3DRenderer.drawCylinder(center, RADIUS, HEIGHT, color, SEGMENTS, instance.ringWidth, instance.depthCheck);
	}

	public boolean isShowCooldown() { return showCooldown; }
	public void setShowCooldown(boolean value) { showCooldown = value; }
	public float getRingWidth() { return ringWidth; }
	public void setRingWidth(float value) { ringWidth = value; }
	public boolean isDepthCheck() { return depthCheck; }
	public void setDepthCheck(boolean value) { depthCheck = value; }
	public int getRingColor() { return ringColor; }
	public void setRingColor(int value) { ringColor = value; }
	public int getCooldownColor() { return cooldownColor; }
	public void setCooldownColor(int value) { cooldownColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showCooldown", showCooldown);
		obj.addProperty("ringWidth", ringWidth);
		obj.addProperty("depthCheck", depthCheck);
		obj.addProperty("ringColor", ringColor);
		obj.addProperty("cooldownColor", cooldownColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("showCooldown")) showCooldown = obj.get("showCooldown").getAsBoolean();
		if (obj.has("ringWidth")) ringWidth = obj.get("ringWidth").getAsFloat();
		if (obj.has("depthCheck")) depthCheck = obj.get("depthCheck").getAsBoolean();
		if (obj.has("ringColor")) ringColor = obj.get("ringColor").getAsInt();
		if (obj.has("cooldownColor")) cooldownColor = obj.get("cooldownColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Shows a world-space preview ring for where the Gyrokinetic Wand's Gravity Storm ability will land.";
	}
}
