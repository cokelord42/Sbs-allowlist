package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.util.ItemAbilityDurations;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hotbar cooldown badge for weapon/armor active abilities — ported from SkyHanni's
 * ItemAbilityCooldown.kt/ItemAbility.kt. SkyHanni's primary detection is dozens of exact
 * sound-name/pitch/volume triples per item, which this project has no equivalent table for; instead this
 * uses the generic, universal "an item was used" signal (Fabric's {@code ItemEvents.USE}, firing on every
 * right-click-with-item regardless of which one) as the PRIMARY trigger, looked up against
 * {@link ItemAbilityDurations}'s ported table of SkyHanni's real confirmed per-ability durations — this
 * replaces the earlier lore-parsing approach ("Cooldown: 8s" off the item's own tooltip) as the primary
 * source, since not every ability item's lore actually prints that exact wording/position (the likely
 * reason this kept silently not showing up for a lot of real ability items across several earlier fix
 * attempts, even once the render-side bugs were fixed). Lore-parsing is kept as a fallback for any item not
 * in the ported table, so the pre-existing behavior isn't regressed for anything it used to catch.
 *
 * <p>The generic action-bar mana-cost line ("§b-24 Mana (§6Instant Transmission§b)") is also still checked
 * as a secondary trigger — SkyHanni itself uses this exact line for the handful of items it doesn't have a
 * confirmed sound for, so it's a genuine, low-risk extra confirmation for those specific items, though the
 * item-use event above already covers the same ground for every ability item in the ported table.
 *
 * <p>Right-clicking doesn't guarantee the ability actually fired (not enough mana, still on cooldown per
 * Hypixel's own server-side timer) — this is a real, accepted tradeoff of using a client-side "item was
 * used" signal instead of a confirmed server activation event. To avoid a spam-right-click on an
 * already-active timer re-triggering it early, a new activation is ignored while the current one hasn't
 * finished yet.
 *
 * <p>Renders on whichever hotbar slot was selected at the moment the ability fired, using the same
 * well-known, decade-stable vanilla hotbar layout constants (182px-wide bar, 20px per slot, centered)
 * rather than a mixin into hotbar rendering.
 *
 * <p>Per user report ("the right click has a cooldown of 10 seconds and left click has 30 seconds, but it
 * doesnt even detect left click being used"): a handful of ability items (the Gyrokinetic Wand confirmed so
 * far — see {@link ItemAbilityDurations#findLeftClick}) have a second, entirely separate left-click ability
 * with its own real duration. {@code ItemEvents.USE} (the primary trigger above) is a right-click-only
 * Fabric signal and could never have caught this. {@link #onLeftClickSwing()} is called from
 * {@code LivingEntitySwingMixin}, hooked into {@code LivingEntity#swing} — the one real call every left-click
 * action (attacking an entity, a block, or plain air) always funnels through client-side, so it needs no
 * separate handling for each of those three cases the way a raw key/mouse hook would.
 */
public class AbilityCooldownTimerFeature extends Feature {
	private static AbilityCooldownTimerFeature instance;
	// Plain text, no literal §-code requirements — Component#getString() (what the action-bar message
	// actually comes through as) already strips all formatting, so the original §b/§6-prefixed pattern
	// could never match anything, and this feature silently never activated regardless of its toggle.
	private static final Pattern MANA_ABILITY = Pattern.compile("-\\d+ Mana \\((?<name>[^)]*)\\)");
	// Plain text (no literal §-code requirement — see the many other doc comments in this project on why:
	// Component#getString() already strips all formatting). Matches "Cooldown: 8s", "Cooldown: 1m",
	// "Cooldown: 0.5s", etc. — the value used to require \d+ (integer only), which would silently fail to
	// match any ability with a sub-1-second cooldown printed as a decimal (several real Hypixel abilities
	// are that short), one plausible reason a specific item's timer could just never activate at all.
	private static final Pattern COOLDOWN_LORE = Pattern.compile(".*Cooldown:\\s*(?<value>\\d+(?:\\.\\d+)?)\\s*(?<unit>[sm]).*");
	private static final Pattern COLOR_CODE = Pattern.compile("§.");

	private static final int HOTBAR_WIDTH = 182;
	private static final int SLOT_SIZE = 20;
	private static final int BOTTOM_MARGIN = 22;

	// Real bug found (per user report — "The gyro cooldown goes a part of the timer (i tested and for me it
	// did 27) then just resets to R"): this used to track only ONE active ability's cooldown at a time via a
	// single shared activeSlot/activatedAtMillis/cooldownSeconds/activeItemSnapshot quadruple, regardless of
	// which hotbar slot or which item it belonged to. The Gyrokinetic Wand alone has two independent
	// abilities (left-click 30s, right-click 10s) that both funnel through this same activate() — and a
	// dungeon run constantly involves OTHER tracked ability items too (swinging a weapon, using a different
	// ultimate). Any later activation for a DIFFERENT slot/ability silently overwrote these shared fields,
	// so from the render loop's point of view the ORIGINAL ability's slot no longer matched activeSlot and
	// immediately looked "not on cooldown" (i.e. jumped straight to the ready "R" state) well before its
	// real duration actually elapsed — exactly the "counts down partway, then suddenly resets to R" symptom.
	// Tracking per-slot (a real cooldown can only ever occupy one hotbar slot at a time anyway) lets every
	// slot's own activation live independently, so using a different ability elsewhere can never again stop
	// an unrelated slot's own countdown from finishing.
	private record Activation(long activatedAtMillis, int cooldownSeconds, ItemStack itemSnapshot) {}
	private final java.util.Map<Integer, Activation> activations = new java.util.HashMap<>();
	private boolean shrinkingOverlay = false;

	// Real Skyblock ids confirmed via SkyblockAddons' own CooldownManager-triggering code (a currently-
	// maintained mod for this exact game version): unlike the generic mana-ability items the rest of this
	// class already covers, these two never print a "Used X! (Y Mana)" line at all — their cooldown is
	// server-enforced purely on repeated log breaks, so a block-break signal is the only real trigger for
	// them. cooldownSecondsFromLore is still the only source of the actual duration (no fabricated/guessed
	// number here) — if a future Hypixel change stops printing it on these items' lore, this simply never
	// activates for them again rather than showing a made-up countdown.
	private static final java.util.Set<String> LOG_BREAK_COOLDOWN_ITEMS = java.util.Set.of("JUNGLE_AXE", "TREECAPITATOR_AXE");

	public AbilityCooldownTimerFeature() {
		super("ability_cooldown_timer", "Ability Cooldown Timer", FeatureCategory.INVENTORY, false);
		instance = this;
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay || !isEnabled()) return;
			onActionBar(message.getString());
		});
		ItemEvents.USE.register((level, player, hand) -> {
			if (isEnabled() && level.isClientSide() && player == Minecraft.getInstance().player) {
				onItemUse(player, hand);
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (isEnabled() && level.isClientSide() && player == Minecraft.getInstance().player) {
				onLogBreak(player, state);
			}
		});
	}

	@Override
	public String getSubcategory() {
		return "Inventory";
	}

	private void onActionBar(String actionBar) {
		Matcher matcher = MANA_ABILITY.matcher(actionBar);
		if (!matcher.find()) return;

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;
		int slot = player.getInventory().getSelectedSlot();
		ItemStack held = player.getInventory().getItem(slot);
		Integer seconds = cooldownSecondsFromLore(held);
		if (seconds == null) return;
		activate(slot, held, seconds);
	}

	/** Primary trigger: fires on every real right-click-with-item, regardless of which ability (or
	 *  whether the item has one at all) — see the class doc comment for why this replaced lore-parsing as
	 *  the main source. Off-hand right-clicks are ignored: every real Hypixel ability item is used from the
	 *  main hand. */
	private void onItemUse(net.minecraft.world.entity.player.Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) return;
		int slot = player.getInventory().getSelectedSlot();
		ItemStack held = player.getInventory().getItem(slot);
		ItemAbilityDurations.AbilityInfo ability = ItemAbilityDurations.find(held);
		if (ability == null) return;
		// Ignore a click that lands while this exact item's own timer is still counting down — most likely
		// a spam-click on an ability that's still on Hypixel's real cooldown (which does nothing server-side),
		// not a genuine new activation; letting it through would keep resetting the timer and it would never
		// actually finish counting down for a player who taps the key again to check.
		Activation existing = activations.get(slot);
		if (existing != null && sameHeldItem(held, existing.itemSnapshot())) {
			double elapsedSeconds = (System.currentTimeMillis() - existing.activatedAtMillis()) / 1000.0;
			if (elapsedSeconds < existing.cooldownSeconds()) return;
		}
		// Prefer the item's own real, currently-printed lore duration over the ported table's assumed
		// value whenever both are available — the table's id-matching is a best-effort port (documented in
		// ItemAbilityDurations' own class comment as not independently re-confirmed per entry), so a wrong
		// assumed id/duration for one item is a real, expected risk; the item's own lore is first-party and
		// always correct when present. This was the actual "1 second cooldown item shows 10 seconds" bug —
		// the table match was winning even when the item's own lore already had the right answer.
		//
		// Exception: ItemAbilityDurations.isMandatoryTableDuration (Hyperion/Valkyrie/Scylla/Astraea's Wither
		// Shield) — those items' lore prints a DIFFERENT ability's (Wither Impact's) real cooldown, which must
		// never override this table's fixed Wither Shield duration.
		String id = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemId(held);
		Integer loreSeconds = ItemAbilityDurations.isMandatoryTableDuration(id) ? null : cooldownSecondsFromLore(held);
		activate(slot, held, loreSeconds != null ? loreSeconds : ability.cooldownSeconds());
	}

	/** Called from {@code LivingEntitySwingMixin} for every real left-click swing of the local player's main
	 *  hand — see this class's own doc comment for why this is needed alongside {@link #onItemUse}. */
	public static void onLeftClickSwing() {
		if (instance == null || !instance.isEnabled()) return;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;
		int slot = player.getInventory().getSelectedSlot();
		ItemStack held = player.getInventory().getItem(slot);
		ItemAbilityDurations.AbilityInfo ability = ItemAbilityDurations.findLeftClick(held);
		if (ability == null) return;
		Activation existing = instance.activations.get(slot);
		if (existing != null && sameHeldItem(held, existing.itemSnapshot())) {
			double elapsedSeconds = (System.currentTimeMillis() - existing.activatedAtMillis()) / 1000.0;
			if (elapsedSeconds < existing.cooldownSeconds()) return;
		}
		instance.activate(slot, held, ability.cooldownSeconds());
	}

	/** Jungle Axe/Treecapitator Axe re-use cooldown — see {@link #LOG_BREAK_COOLDOWN_ITEMS}'s own doc
	 *  comment for why this needs a dedicated block-break trigger instead of the generic mana-ability path
	 *  above. */
	private void onLogBreak(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.block.state.BlockState state) {
		if (!state.is(BlockTags.LOGS)) return;
		int slot = player.getInventory().getSelectedSlot();
		ItemStack held = player.getInventory().getItem(slot);
		String id = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemId(held);
		if (id == null || !LOG_BREAK_COOLDOWN_ITEMS.contains(id)) return;
		Activation existing = activations.get(slot);
		if (existing != null && sameHeldItem(held, existing.itemSnapshot())) {
			double elapsedSeconds = (System.currentTimeMillis() - existing.activatedAtMillis()) / 1000.0;
			if (elapsedSeconds < existing.cooldownSeconds()) return;
		}
		Integer loreSeconds = cooldownSecondsFromLore(held);
		if (loreSeconds == null) return;
		activate(slot, held, loreSeconds);
	}

	private void activate(int slot, ItemStack held, int seconds) {
		activations.put(slot, new Activation(System.currentTimeMillis(), seconds, held.copy()));
	}

	private static boolean sameHeldItem(ItemStack current, ItemStack snapshot) {
		if (current.isEmpty() || snapshot.isEmpty()) return false;
		String currentUuid = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemUuid(current);
		String snapshotUuid = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemUuid(snapshot);
		if (currentUuid != null && snapshotUuid != null) return currentUuid.equals(snapshotUuid);
		// Neither stack carries a uuid — fall back to comparing item type only, still tolerant of the
		// item's own lore/NBT changing between activation and render (unlike full component equality).
		return current.getItem() == snapshot.getItem();
	}

	private static Integer cooldownSecondsFromLore(ItemStack stack) {
		var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		if (lore == null) return null;
		for (Component line : lore.lines()) {
			// Unlike chat/action-bar text, item lore doesn't reliably come through getString() with its
			// formatting fully stripped (confirmed elsewhere in this project — e.g. ChestValueEstimator's
			// essence-price lines) — a stray "§r" between the number and its unit ("8§rs") would otherwise
			// break the value/unit match even though the leading ".*" already tolerates a prefix like "§e".
			String plain = COLOR_CODE.matcher(line.getString()).replaceAll("");
			Matcher matcher = COOLDOWN_LORE.matcher(plain);
			if (!matcher.matches()) continue;
			try {
				double value = Double.parseDouble(matcher.group("value"));
				double seconds = matcher.group("unit").equals("m") ? value * 60 : value;
				return Math.max(1, (int) Math.round(seconds));
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "ability_cooldown_timer");
	private boolean hudRegistered = false;

	// Same unconditional remove-then-add pattern as CustomScoreboardFeature/TabWidgetOverlayFeature — see
	// CustomScoreboardFeature's onEnable doc comment for the rapid-toggle race this avoids.
	@Override
	protected void onEnable() {
		try {
			HudElementRegistry.removeElement(HUD_ID);
		} catch (Exception ignored) {
			// Wasn't registered — the common case.
		}
		try {
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, HUD_ID, this::render);
			hudRegistered = true;
		} catch (Exception e) {
			hudRegistered = false;
			SkyblockSimplified.LOGGER.error("Failed to register ability_cooldown_timer HUD layer", e);
		}
	}

	@Override
	protected void onDisable() {
		try {
			HudElementRegistry.removeElement(HUD_ID);
		} catch (Exception ignored) {
			// Already gone — fine.
		}
		hudRegistered = false;
	}

	private void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		// Wrapped in try-catch — this callback was running completely unprotected, unlike every other HUD
		// widget's own extractHud()/render() in this project (see TabWidgetOverlayFeature's own doc comment
		// on the exact same gap, found in the same investigation): an uncaught throw here risks taking out
		// every OTHER HUD widget's render for that frame too if Fabric's per-frame Hud dispatch doesn't
		// isolate between registered layers, not just this one's — a real candidate for "all the farming
		// HUD widgets die together" recurring even after tickAll's own isolation fix.
		try {
			renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Ability Cooldown Timer render failed, skipping this frame", e);
		}
	}

	// Per user request ("The cooldown on items with a cooldown should always show R if they are ready, which
	// should be always unless used"): used to render nothing at all until an ability was actually activated
	// once this session (activeSlot started at -1 and only ever got set from a real activation) — a tracked
	// ability item sitting untouched in the hotbar looked identical to one with no ability at all. Now walks
	// every hotbar slot every frame: any slot holding an item this project has a real tracked ability
	// duration for (right-click via {@link ItemAbilityDurations#find} or left-click via {@link
	// ItemAbilityDurations#findLeftClick}) always shows a ready indicator by default, switching to the real
	// countdown only for whichever single slot is actually mid-cooldown from a real activation.
	private void renderInner(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen() || mc.player == null) return;

		// Stop tracking the countdown once the item that had the ability isn't in that slot anymore
		// (swapped/dropped). Compares the item's own stable per-item uuid (survives lore/NBT churn — many
		// ability items have live-updating tooltip readouts of their own — while still correctly detecting a
		// real swap/drop) rather than full component equality, which used to false-positive on that churn and
		// kill the timer almost immediately, long before the real cooldown ever elapsed.
		activations.entrySet().removeIf(e -> !sameHeldItem(mc.player.getInventory().getItem(e.getKey()), e.getValue().itemSnapshot()));

		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();
		int barLeft = (screenWidth - HOTBAR_WIDTH) / 2;
		// Real bug found (per user report — "the timer is in the middle of the item. I need it bottom
		// right"): this used to omit the "+ 2" that every other hotbar-slot overlay in this codebase applies
		// (see ItemRarityBackgroundFeature's own identical HOTBAR_WIDTH/SLOT_SIZE/BOTTOM_MARGIN constants,
		// confirmed already correctly aligned to the real slot), so this slot rectangle — and both the
		// overlay fill and text positioned off it below — sat 2px above the real slot the whole time, which
		// read as "not quite on the item"/"in the middle" rather than tight to its actual bottom edge.
		int slotY = screenHeight - BOTTOM_MARGIN + 2;
		Font font = mc.font;

		var inventory = mc.player.getInventory();
		for (int slot = 0; slot < 9; slot++) {
			ItemStack held = inventory.getItem(slot);
			if (held.isEmpty()) continue;

			Activation activation = activations.get(slot);
			double elapsedSeconds = 0;
			int cooldownSeconds = 0;
			boolean onCooldown = false;
			if (activation != null) {
				elapsedSeconds = (System.currentTimeMillis() - activation.activatedAtMillis()) / 1000.0;
				cooldownSeconds = activation.cooldownSeconds();
				onCooldown = elapsedSeconds < cooldownSeconds;
			}

			String text;
			if (onCooldown) {
				// Per user request ("Color code item cooldowns, first half of the cooldown should be red,
				// other half yellow, and obviously green when ready. On stuff with uneven cooldown, make the
				// red take priority so for example on a hyperion red would be 543 and yellow 21 and green R"):
				// floor(cooldownSeconds/2) as the red/yellow threshold puts the odd leftover second in the red
				// group, exactly matching that worked example — a 5-second cooldown has threshold 2, so
				// remaining values 5/4/3 (>2) read red and 2/1 read yellow. §-color codes work here the same
				// way the existing "§aR" ready-state text below already relies on (Font#width/draw both treat
				// them as zero-width formatting, not literal characters).
				int remaining = (int) Math.ceil(cooldownSeconds - elapsedSeconds);
				int threshold = cooldownSeconds / 2;
				text = (remaining > threshold ? "§c" : "§e") + remaining;
			} else if (ItemAbilityDurations.find(held) != null || ItemAbilityDurations.findLeftClick(held) != null) {
				text = "§aR";
			} else {
				continue;
			}

			int slotX = barLeft + 3 + slot * SLOT_SIZE;
			// Bottom-right of the slot, text only — the exact same formula vanilla's own item stack-count
			// number uses (see GuiGraphicsExtractor's own count-rendering call: {@code x + 19 - 2 -
			// font.width(s), y + 6 + 3}, i.e. x + 17 - width, y + 9 off the slot's top-left).
			int textX = slotX + 17 - font.width(text);
			int textY = slotY + 9;

			// Per user request ("add a vanilla-ender-pearl-style shrinking white overlay subtoggle"): the same
			// translucent-white receding bar vanilla itself draws over a real on-cooldown item slot (ender
			// pearls, shields, etc.) — a rectangle covering the REMAINING cooldown fraction of the slot's
			// height, shrinking from full coverage down to nothing as the ability becomes ready. Drawn before
			// the text so the number stays legible on top of it, same layering vanilla uses.
			if (shrinkingOverlay && onCooldown) {
				float remainingFraction = (float) Math.max(0.0, Math.min(1.0, 1.0 - elapsedSeconds / cooldownSeconds));
				int overlayHeight = Math.round(remainingFraction * 16f);
				if (overlayHeight > 0) {
					graphics.fill(slotX, slotY + 16 - overlayHeight, slotX + 16, slotY + 16, 0x7FFFFFFF);
				}
			}

			graphics.text(font, Component.literal(text), textX, textY, 0xFFFFFFFF);
		}
	}

	public boolean isShrinkingOverlay() { return shrinkingOverlay; }
	public void setShrinkingOverlay(boolean value) { shrinkingOverlay = value; }

	@Override
	public com.google.gson.JsonElement savePersistedData() {
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
		obj.addProperty("shrinkingOverlay", shrinkingOverlay);
		return obj;
	}

	@Override
	public void loadPersistedData(com.google.gson.JsonElement el) {
		if (!(el instanceof com.google.gson.JsonObject obj)) return;
		if (obj.has("shrinkingOverlay")) shrinkingOverlay = obj.get("shrinkingOverlay").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Shows a countdown badge over your hotbar item while a weapon or armor ability (like the Gyrokinetic Wand's ultimate) is on cooldown.";
	}
}
