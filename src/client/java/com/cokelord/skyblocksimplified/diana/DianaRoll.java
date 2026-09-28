package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.DianaRollFeature;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.util.ChatText;
import com.cokelord.skyblocksimplified.util.SkyblockItemIcons;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Diana "case opening": every rare mob kill (your own, or one you were near for lootshare) plays a CS-style
 * item strip on the HUD that lands on what actually dropped. Same roll kinematics as the dungeon Chest
 * Rolling (ramp, fixed cruise speed, exponential-decay landing) but drawn as a non-blocking overlay, since
 * this happens mid-fight.
 *
 * <p>Timing: the rare-drop chat line and the mob's death can arrive in either order, so rare-drop lines in
 * the Hub are held for up to 1.5s waiting for a death; a death then claims them. A roll keeps collecting
 * lines for its first {@link #LOCK_MS}, then fixes the winner (still off-screen at that point) — the rarest
 * real drop, or a common filler if nothing rare dropped. Held lines are printed the moment the strip lands.
 */
public final class DianaRoll {
	private static final Pattern RARE_DROP = Pattern.compile("^§6§lRARE DROP! (.*)$", Pattern.DOTALL);
	private static final long LIMBO_MS = 1500;
	private static final long LOCK_MS = 1600;
	private static final long LAND_HOLD_MS = 1300;
	private static final float CRUISE_CARDS_PER_SECOND = 7f;
	private static final float RAMP_SECONDS = 0.15f;
	private static final int OVERSHOOT_CARDS = 8;
	private static final int CARD_W = 24, CARD_H = 18;

	private record Held(Component message, String plain, long at) {}

	private static final class Roll {
		final RareMobs.Mob mob;
		final List<ItemStack> strip = new ArrayList<>();
		final List<Held> held = new ArrayList<>();
		float duration;
		float landSeconds;
		int winnerIndex;
		long startMs = -1;
		boolean locked;
		boolean landed;
		boolean winnerRare;
		DianaDropTables.Drop forced;
		DianaDropTables.Drop pickedUp;
		int lastTickCard = -1;

		Roll(RareMobs.Mob mob) { this.mob = mob; }
	}

	private static final List<Held> limbo = new ArrayList<>();
	private static final Deque<Roll> queue = new ArrayDeque<>();
	private static Roll active;
	private static boolean registered;

	private DianaRoll() {}

	static synchronized void register() {
		if (registered) return;
		registered = true;
		HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
			Identifier.fromNamespaceAndPath("skyblocksimplified", "diana_roll"), DianaRoll::render);
	}

	// ---- triggers -----------------------------------------------------------------------------------------

	/** @return true to hide the line (held until a roll lands, or released after the limbo window). */
	static boolean onChat(Component message, String legacy) {
		if (!DianaRollFeature.on() || !DianaState.inHub()) return false;
		boolean rareDrop = RARE_DROP.matcher(legacy).matches();
		if (!rareDrop) return false;
		Held h = new Held(message, ChatText.strip(legacy), System.currentTimeMillis());
		if (active != null && !active.landed) {
			active.held.add(h);
			return true;
		}
		limbo.add(h);
		return true;
	}

	/** A common Diana drop reached the inventory (DianaTracker's pickup diff) — the dry-kill landing item. */
	static void onCommonPickup(String skyblockId) {
		Roll r = active;
		if (r == null || r.locked || r.forced != null) return;
		DianaDropTables.Drop d = DianaDropTables.commonById(skyblockId);
		if (d != null && (r.pickedUp == null || d.weight() < r.pickedUp.weight())) r.pickedUp = d;
	}

	/** A tracked rare Diana mob hit 0 HP (DianaMobs). */
	static void onRareMobDeath(String name, boolean nearby) {
		if (!nearby || !DianaRollFeature.on()) return;
		RareMobs.Mob mob = RareMobs.Mob.fromName(name);
		if (mob == null) return;
		Roll roll = new Roll(mob);
		long now = System.currentTimeMillis();
		for (Iterator<Held> it = limbo.iterator(); it.hasNext(); ) {
			Held h = it.next();
			if (now - h.at() <= LIMBO_MS) {
				roll.held.add(h);
				it.remove();
			}
		}
		enqueue(roll);
	}

	/** /dianaroll test: {@code forced} null picks a weighted random result from the mob's table. */
	public static void test(RareMobs.Mob mob, DianaDropTables.Drop forced) {
		Roll roll = new Roll(mob);
		roll.forced = forced != null ? forced : DianaDropTables.weighted(DianaDropTables.pool(mob));
		enqueue(roll);
	}

	private static void enqueue(Roll roll) {
		if (active == null) start(roll);
		else if (queue.size() < 3) queue.add(roll);
		else roll.held.forEach(h -> ChatText.clientMessage(h.message()));
	}

	private static void start(Roll roll) {
		active = roll;
		roll.duration = DianaRollFeature.durationSeconds();
		roll.landSeconds = Math.min(2f, roll.duration * 0.5f);
		float cruise = Math.max(0f, roll.duration - RAMP_SECONDS - roll.landSeconds);
		float tau = roll.landSeconds * 0.2f;
		float landDist = CRUISE_CARDS_PER_SECOND * tau * (1f - (float) Math.exp(-roll.landSeconds / tau));
		roll.winnerIndex = Math.max(18, Math.round(CRUISE_CARDS_PER_SECOND * RAMP_SECONDS / 2f + CRUISE_CARDS_PER_SECOND * cruise + landDist));
		for (DianaDropTables.Drop d : DianaDropTables.strip(roll.mob, roll.winnerIndex + OVERSHOOT_CARDS)) roll.strip.add(stack(d));
		roll.startMs = System.currentTimeMillis();
	}

	// ---- per tick -----------------------------------------------------------------------------------------

	static void tick() {
		long now = System.currentTimeMillis();
		// Unclaimed rare-drop lines (not from a rare mob kill) go out as normal after the wait.
		for (Iterator<Held> it = limbo.iterator(); it.hasNext(); ) {
			Held h = it.next();
			if (now - h.at() > LIMBO_MS || !DianaRollFeature.on()) {
				ChatText.clientMessage(h.message());
				it.remove();
			}
		}
		Roll r = active;
		if (r == null) return;
		long elapsed = now - r.startMs;
		if (!r.locked && elapsed >= LOCK_MS) lock(r);
		if (!r.landed && elapsed >= Math.round(r.duration * 1000)) land(r);
		if (elapsed >= Math.round(r.duration * 1000) + LAND_HOLD_MS) {
			active = null;
			Roll next = queue.poll();
			if (next != null) start(next);
		}
	}

	private static void lock(Roll r) {
		r.locked = true;
		DianaDropTables.Drop winner = r.forced;
		ItemStack unknown = null;
		if (winner == null) {
			for (Held h : r.held) {
				DianaDropTables.Drop d = DianaDropTables.fromChat(h.plain());
				if (d == null) {
					if (unknown == null) unknown = textStack(h.plain());
					continue;
				}
				if (winner == null || d.weight() < winner.weight()) winner = d;
			}
		}
		ItemStack winnerStack;
		if (winner != null) {
			winnerStack = stack(winner);
			r.winnerRare = winner.rare();
		} else if (unknown != null) {
			winnerStack = unknown;
			r.winnerRare = true;
		} else {
			// Nothing rare dropped: land on the common actually picked up during the roll, else a weighted one.
			DianaDropTables.Drop common = r.pickedUp != null ? r.pickedUp : DianaDropTables.weighted(DianaDropTables.COMMONS);
			winnerStack = stack(common);
		}
		r.strip.set(r.winnerIndex, winnerStack);
		// Near miss: sometimes park one of this mob's rares right next to a non-rare result.
		if (!r.winnerRare && ThreadLocalRandom.current().nextInt(100) < 12) {
			DianaDropTables.Drop rare = DianaDropTables.randomRare(r.mob);
			if (rare != null) r.strip.set(r.winnerIndex + (ThreadLocalRandom.current().nextBoolean() ? 1 : -1), stack(rare));
		}
	}

	private static void land(Roll r) {
		r.landed = true;
		if (!r.locked) lock(r);
		for (Held h : r.held) ChatText.clientMessage(h.message());
		r.held.clear();
		if (r.winnerRare) DianaRollFeature.playLandSound();
	}

	// ---- stacks -------------------------------------------------------------------------------------------

	private static ItemStack stack(DianaDropTables.Drop d) {
		ItemStack icon = d.skyblockId() != null ? SkyblockItemIcons.getIcon(d.skyblockId()) : null;
		ItemStack stack = icon != null ? icon.copy() : new ItemStack(d.fallback());
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(d.color() + d.name()));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true); // every Diana drop is enchanted
		return stack;
	}

	/** A drop line we have no table entry for: show a glinting book named after it. */
	private static ItemStack textStack(String plain) {
		String name = plain.replaceFirst("^RARE DROP! ", "").replaceAll("\\(\\+.*$", "").trim();
		ItemStack stack = new ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6" + name));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	private static final int[] LEGACY_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	private static int accent(ItemStack stack) {
		String name = stack.getHoverName().getString();
		Component custom = stack.get(DataComponents.CUSTOM_NAME);
		String legacy = custom != null ? ChatText.legacy(custom) : name;
		if (legacy.length() >= 2 && legacy.charAt(0) == '§') {
			int idx = "0123456789abcdef".indexOf(legacy.charAt(1));
			if (idx >= 0) return LEGACY_RGB[idx];
		}
		return 0x888888;
	}

	// ---- rendering ----------------------------------------------------------------------------------------

	private static float cardsScrolled(Roll r, float t) {
		if (t <= 0f) return 0f;
		float cruiseSeconds = Math.max(0f, r.duration - RAMP_SECONDS - r.landSeconds);
		float rampDist = CRUISE_CARDS_PER_SECOND * RAMP_SECONDS / 2f;
		if (t < RAMP_SECONDS) {
			float local = t / RAMP_SECONDS;
			return rampDist * local * local;
		}
		float landStart = RAMP_SECONDS + cruiseSeconds;
		if (t < landStart) return rampDist + CRUISE_CARDS_PER_SECOND * (t - RAMP_SECONDS);
		float cruiseDist = CRUISE_CARDS_PER_SECOND * cruiseSeconds;
		float tau = r.landSeconds * 0.2f;
		if (tau <= 0f) return r.winnerIndex;
		float tl = Math.min(t - landStart, r.landSeconds);
		float raw = (1f - (float) Math.exp(-tl / tau));
		float full = (1f - (float) Math.exp(-r.landSeconds / tau));
		return rampDist + cruiseDist + (r.winnerIndex - rampDist - cruiseDist) * (raw / full);
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Roll r = active;
		if (r == null || r.startMs < 0) return;
		Minecraft mc = Minecraft.getInstance();
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		float t = Math.min((System.currentTimeMillis() - r.startMs) / 1000f, r.duration);
		int cardFull = Math.max(34, Math.min(56, sw / 9));
		float scale = (cardFull - 4) / (float) CARD_W;
		int stripHalf = cardFull * 7 / 2;
		int cx = sw / 2;
		int cy = Math.round(sh * DianaRollFeature.heightPercent() / 100f);
		int cardH = Math.round(CARD_H * scale);

		// Band + title
		RenderUtil.fillRounded(g, cx - stripHalf - 6, cy - cardH / 2 - 16, cx + stripHalf + 6, cy + cardH / 2 + 6, 6, 0xA0101010);
		String title = "§l" + r.mob.displayName + (r.landed ? "" : " §7rolling...");
		g.centeredText(mc.font, title, cx, cy - cardH / 2 - 12, 0xFFFFFFFF);

		float offset = cardsScrolled(r, t) * cardFull;
		int card = (int) (offset / cardFull);
		if (card > r.lastTickCard) {
			r.lastTickCard = card;
			if (DianaRollFeature.tickSound() && mc.player != null) mc.player.playSound(SoundEvents.CHAIN_FALL, 0.6f, 1f);
		}
		int baseX = cx - Math.round(offset) - cardFull / 2;
		g.enableScissor(cx - stripHalf, cy - cardH, cx + stripHalf, cy + cardH);
		int boosted = -1;
		for (int i = 0; i < r.strip.size(); i++) {
			int ccx = baseX + i * cardFull + cardFull / 2;
			if (ccx < cx - stripHalf - cardFull || ccx > cx + stripHalf + cardFull) continue;
			float dist = Math.abs(ccx - cx);
			if (dist < cardFull / 2f) { boosted = i; continue; }
			drawCard(g, r.strip.get(i), ccx, cy, scale, Math.min(1f, dist / stripHalf));
		}
		if (boosted >= 0) {
			int ccx = baseX + boosted * cardFull + cardFull / 2;
			float magnify = 1f + 0.2f * Math.max(0f, 1f - Math.abs(ccx - cx) / (cardFull / 2f));
			drawCard(g, r.strip.get(boosted), ccx, cy, scale * magnify, 0f);
		}
		g.disableScissor();
		g.fill(cx, cy - cardH / 2 - 3, cx + 1, cy + cardH / 2 + 3, 0xFFFF5555);
		if (r.landed) {
			ItemStack won = r.strip.get(r.winnerIndex);
			g.centeredText(mc.font, won.getHoverName().getString().isEmpty() ? "" : "§f" + ChatText.legacy(won.getHoverName()), cx, cy + cardH / 2 + 8, 0xFFFFFFFF);
		}
	}

	private static void drawCard(GuiGraphicsExtractor g, ItemStack item, int centerX, int centerY, float scale, float fade) {
		int w = Math.round(CARD_W * scale), h = Math.round(CARD_H * scale);
		g.pose().pushMatrix();
		try {
			g.pose().translate(centerX - w / 2f, centerY - h / 2f);
			g.pose().scale(scale);
			g.fill(0, 0, CARD_W, CARD_H - 1, 0x80303030);
			g.fill(0, CARD_H - 1, CARD_W, CARD_H, accent(item) | 0xFF000000);
			g.item(item, CARD_W / 2 - 8, CARD_H / 2 - 8);
			if (fade > 0f) g.fill(0, 0, CARD_W, CARD_H, Math.round(fade * 230f) << 24);
		} finally {
			g.pose().popMatrix();
		}
	}
}
