package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.util.SkullTextureUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Detects right-clicks on dungeon-relevant blocks (levers, chests, trapped chests) plus item pickups
 * relevant to dungeon secrets (Wither Essence, plus a fixed real item-secret list). Block-click detection
 * is ported from SkyHanni's DungeonApi.onBlockClick (same real vanilla block types plus the same repo
 * skull texture); item-pickup detection now hooks the real vanilla take-item packet via
 * {@link com.cokelord.skyblocksimplified.mixin.ItemPickupPacketMixin} (see that class's doc comment) —
 * replaces an old tick-based nearby-ItemEntity diff that both missed real pickups and, per user report,
 * could false-positive near chest interactions; a container-slot transfer (taking an item out of a chest
 * GUI) never sends this packet at all, so this is structurally immune to that false positive rather than
 * needing a suppression window.
 */
public final class DungeonBlockDetector {
	public enum ClickedBlockType { LEVER, CHEST, TRAPPED_CHEST, WITHER_ESSENCE_PICKUP, ITEM_SECRET_PICKUP, SECRET_BAT_DEATH, ANY_ITEM_PICKUP }

	private static final double BAT_TRACK_RANGE = 24.0;
	// Real bug found (per user report — "Bats also don't advance step, it should detect a bat dying
	// nearby"): this used to match on a literal custom name ("Dungeon Secret Bat") that real Hypixel secret
	// bats never actually carry — confirmed against Devonian's own real detection (Dungeons.kt's
	// EntityDeathEvent handler and HighlightBat.kt), both of which identify a secret bat purely by real
	// vanilla max health: a plain vanilla Bat's maxHealth is always 6, while Hypixel's dungeon secret bats
	// are spawned with a much higher max health (100, or 200 during the Derpy mayor perk) — matching "any
	// Bat whose maxHealth isn't the vanilla 6" (Devonian's own simpler formulation, which also covers Derpy
	// without needing separate mayor-perk detection) instead of a name string that was never real.
	private static final float VANILLA_BAT_MAX_HEALTH = 6f;
	private static java.util.Map<Integer, net.minecraft.world.entity.Entity> lastNearbySecretBats = new java.util.HashMap<>();

	// Real, confirmed value from github.com/hannibal002/SkyHanni-REPO's Skulls.json ("WITHER_ESSENCE").
	private static final String WITHER_ESSENCE_TEXTURE =
		"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzRkYjRhZGZhOWJmNDhmZjVkNDE3MDdhZTM0ZWE3OGJkMjM3MTY1OWZjZDhjZDg5MzQ3NDlhZjRjY2U5YiJ9fX0=";

	private static final List<BiConsumer<BlockPos, ClickedBlockType>> LISTENERS = new ArrayList<>();
	private static boolean registered = false;

	// Real bug found (per user report — "dungeon routes doesnt detect a wither essence click. It needs to
	// detect me right clicking a player head and the secret count increasing shortly after"): classify()'s
	// exact-texture match below is a fast, precise path, but it silently drops every click on a Wither
	// Essence head whose texture doesn't byte-for-byte match WITHER_ESSENCE_TEXTURE — and there's no live
	// confirmation that's the only texture Hypixel has ever used for this head (SkyHanni-REPO data can go
	// stale same as any other bundled skin hash). Rather than trust one fixed texture as the only signal,
	// any right-click on an UNRECOGNIZED player head while in a dungeon is tracked here and confirmed
	// retroactively the same way the user described: if the real tab-list secret counter
	// ({@link DungeonState#getSecretsFound()}) actually goes up within a couple seconds of that click, the
	// click + secret-gained combination is treated as a genuine Wither Essence pickup regardless of the
	// head's exact texture — a decorative head that isn't a real secret never sees its own click followed by
	// a secret count increase, so this can't misfire on ordinary dungeon decoration.
	private static final long WITHER_ESSENCE_CONFIRM_WINDOW_MILLIS = 5000L;
	private record PendingHeadClick(BlockPos pos, long clickedAtMillis, int secretsAtClick) {}
	private static final List<PendingHeadClick> pendingHeadClicks = new ArrayList<>();
	private static int secretsConsumedByPending = 0;

	private DungeonBlockDetector() {}

	public static synchronized void addListener(BiConsumer<BlockPos, ClickedBlockType> listener) {
		ensureRegistered();
		LISTENERS.add(listener);
	}

	private static synchronized void ensureRegistered() {
		if (registered) return;
		registered = true;
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (!com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) return InteractionResult.PASS;
			BlockPos pos = hitResult.getBlockPos();
			ClickedBlockType type = classify(level, pos);
			if (type == ClickedBlockType.WITHER_ESSENCE_PICKUP) {
				fireEssence(pos, false);
			} else if (type != null) {
				fire(pos, type);
			} else if (isUnrecognizedPlayerHead(level, pos)) {
				pendingHeadClicks.add(new PendingHeadClick(pos, System.currentTimeMillis(), DungeonState.getSecretsFound()));
			}
			return InteractionResult.PASS;
		});
		// Wrapped in try-catch — see BlessingParticleFilter's doc comment on its own registration for why:
		// this registers before FeatureRegistry::tickAll (during feature construction), and an uncaught
		// throw here would silently skip tickAll (and every feature it ticks) for that whole frame.
		// ALLOW_GAME (always returning true), not GAME: Chat De-clutter's "Hide Wither Essence Messages" cancels
		// the line, and cancelled lines never reach GAME listeners — essence detection must still see it.
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay || LISTENERS.isEmpty()) return true;
			try {
				onChat(message.getString().replaceAll("§.", "").strip());
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("DungeonBlockDetector chat hook threw", e);
			}
			return true;
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tickSecretBats(client);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("DungeonBlockDetector secret-bat tick threw, skipping this tick", e);
			}
			try {
				tickTouchedItems(client);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("DungeonBlockDetector item-touch tick threw, skipping this tick", e);
			}
			try {
				tickPendingHeadClicks();
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("DungeonBlockDetector pending-head-click tick threw, skipping this tick", e);
			}
		});
		// Real bug found (per user report — "Secret pickup gets detected when picking up items from chests" —
		// still happening despite ItemPickupPacketMixin's real vanilla take-item packet, which per this
		// class's own earlier doc comment should never fire for a container-slot transfer): Hypixel's own
		// heavily-customized server logic apparently DOES send that packet for a chest-take too, at least
		// sometimes, contradicting the assumption this project's own earlier fix relied on. Adds the
		// suppression window from the user's own original proposal as a second, independent layer: any
		// container GUI (any chest included) closing sets this timestamp, and onLocalPlayerItemPickup below
		// ignores a pickup within CONTAINER_CLOSE_SUPPRESS_MILLIS of it, on top of (not instead of) the real
		// packet-based signal.
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen).register(s -> lastContainerCloseMillis = System.currentTimeMillis());
			}
		});
	}

	private static final long CONTAINER_CLOSE_SUPPRESS_MILLIS = 1000L;
	private static volatile long lastContainerCloseMillis = 0L;

	private static ClickedBlockType classify(net.minecraft.world.level.Level level, BlockPos pos) {
		var block = level.getBlockState(pos).getBlock();
		if (block == Blocks.CHEST) return ClickedBlockType.CHEST;
		if (block == Blocks.TRAPPED_CHEST) return ClickedBlockType.TRAPPED_CHEST;
		if (block == Blocks.LEVER) return ClickedBlockType.LEVER;
		// Real bug found (per user report — "The wither essence pickup method is annoying since the chat
		// message is like 2 seconds delayed, and it also doesnt work with the routes... Its literally just
		// clicking a wither essence head placed on the ground"): Wither Essence is a real placed player-head
		// BLOCK the player right-clicks (same UseBlockCallback pipeline as chests/levers above), not a ground
		// ItemEntity or a right-click-entity pickup — matched by the same real skull texture this class already
		// uses for the (now largely dead) item-pickup path, off the real placed block entity instead of a stack.
		if ((block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD)
			&& WITHER_ESSENCE_TEXTURE.equals(SkullTextureUtil.fromBlockEntity(level.getBlockEntity(pos)))) {
			return ClickedBlockType.WITHER_ESSENCE_PICKUP;
		}
		return null;
	}

	/** See {@link #pendingHeadClicks}'s own doc comment — any player-head block classify() didn't already
	 *  recognize (a decorative head, or a real Wither Essence head using a texture this class doesn't have
	 *  on file) is a candidate for the click+secret-count-increase fallback below. */
	private static boolean isUnrecognizedPlayerHead(net.minecraft.world.level.Level level, BlockPos pos) {
		var block = level.getBlockState(pos).getBlock();
		return block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD;
	}

	/** Confirms or discards every click tracked by {@link #isUnrecognizedPlayerHead}: fires a (delayed, but
	 *  still real) {@link ClickedBlockType#WITHER_ESSENCE_PICKUP} the moment the tab-list secret counter
	 *  actually rises above what it was at click time, or silently drops the click once
	 *  {@link #WITHER_ESSENCE_CONFIRM_WINDOW_MILLIS} passes with no such increase (an ordinary decorative
	 *  head, or a secret that came from something else entirely in that window). */
	private static void tickPendingHeadClicks() {
		if (pendingHeadClicks.isEmpty()) return;
		if (!com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) {
			pendingHeadClicks.clear();
			return;
		}
		long now = System.currentTimeMillis();
		int currentSecrets = DungeonState.getSecretsFound();
		// One confirmation per secret actually gained (oldest click first): two quick essence clicks made
		// before the counter moved share the same baseline, and a single +1 must not confirm both.
		java.util.Iterator<PendingHeadClick> it = pendingHeadClicks.iterator();
		while (it.hasNext()) {
			PendingHeadClick click = it.next();
			if (currentSecrets > click.secretsAtClick() + secretsConsumedByPending) {
				secretsConsumedByPending++;
				it.remove();
				fireEssence(click.pos(), false);
			} else if (now - click.clickedAtMillis() > WITHER_ESSENCE_CONFIRM_WINDOW_MILLIS) {
				it.remove();
			}
		}
		if (pendingHeadClicks.isEmpty()) secretsConsumedByPending = 0;
	}

	// Real, confirmed fixed dungeon item-secret list — Skyblock item ids ported from Devonian's own
	// DungeonEvent.SecretPickup.SECRET_ITEMS, trimmed to exactly the items the user asked to detect. "POTION"
	// is handled separately below (Healing-only, per Devonian's own special case for it) since the user
	// specifically wants Healing VIII Splash Potion. ARCHITECT_FIRST_DRAFT added per user report ("the item
	// pickup doesnt detect paper for the architect draft either") — a real paper item carrying its own
	// ExtraAttributes id (unlike the two spawn eggs below, which don't), so the normal id lookup covers it.
	private static final java.util.Set<String> ITEM_SECRET_IDS = java.util.Set.of(
		"DUNGEON_DECOY", "DUNGEON_TRAP", "TRAINING_WEIGHTS", "INFLATABLE_JERRY", "DUNGEON_CHEST_KEY",
		"TREASURE_TALISMAN", "DEFUSE_KIT", "CANDYCOMB", "ARCHITECT_FIRST_DRAFT"
	);

	/** Called by {@link com.cokelord.skyblocksimplified.mixin.ItemPickupPacketMixin} for every ground item
	 *  the local player just collected, in-dungeon or not — dungeon-gating happens here rather than in the
	 *  mixin so this stays a dumb packet relay. */
	public static void onLocalPlayerItemPickup(ItemEntity item) {
		if (LISTENERS.isEmpty() || !com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) return;
		if (System.currentTimeMillis() - lastContainerCloseMillis < CONTAINER_CLOSE_SUPPRESS_MILLIS) return;
		// Already fired the instant the player touched it (tickTouchedItems) — the server's pickup packet
		// arriving afterwards is the same pickup.
		if (!firedItemIds.add(item.getId())) return;
		classifyAndFirePickup(item);
	}

	// Per user request ("All step types containing secrets need to be INSTANT"): the server's pickup packet
	// only arrives after its own pickup check, a noticeable delay. The player's vanilla pickup reach (their
	// box grown 1 block sideways, 0.5 up/down) touching a ground item is when the pickup actually happens, so
	// fire then. Items younger than 10 ticks are skipped (a just-dropped item can't be picked up yet), and
	// each item entity fires once (firedItemIds, shared with the packet path).
	private static final java.util.Set<Integer> firedItemIds = new java.util.HashSet<>();

	private static void tickTouchedItems(net.minecraft.client.Minecraft client) {
		if (client.player == null || client.level == null || LISTENERS.isEmpty()) return;
		if (!com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) {
			firedItemIds.clear();
			firedBatIds.clear();
			firedEssencePositions.clear();
			chatSwallows.clear();
			clickSwallows.clear();
			return;
		}
		if (client.player.isSpectator() || !client.player.isAlive()) return;
		AABB reach = client.player.getBoundingBox().inflate(1.0, 0.5, 1.0);
		for (ItemEntity item : client.level.getEntitiesOfClass(ItemEntity.class, reach)) {
			if (item.tickCount < 10 || item.getItem().isEmpty() || firedItemIds.contains(item.getId())) continue;
			firedItemIds.add(item.getId());
			classifyAndFirePickup(item);
		}
		if (firedItemIds.size() > 512) firedItemIds.clear();
	}

	private static void classifyAndFirePickup(ItemEntity item) {
		var stack = item.getItem();
		String skyblockId = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemId(stack);
		String texture = SkullTextureUtil.fromItem(stack);
		String plainName = stack.getHoverName().getString().replaceAll("§.", "");
		boolean isWitherEssence = WITHER_ESSENCE_TEXTURE.equals(texture) || plainName.contains("Wither Essence");
		if (isWitherEssence) {
			fireEssence(item.blockPosition(), false);
			return;
		}
		boolean isHealingPotion = "POTION".equals(skyblockId) && isHealingSplashPotion(stack);
		// Real bug found (per user report — "the item pickup doesnt work on decoys and jerry eggs... Decoys
		// are ghast spawn eggs, the white ones. Jerry eggs are just villager spawn eggs"): these two are real
		// plain vanilla spawn-egg items with just a custom display name, not genuine Hypixel items carrying an
		// ExtraAttributes "id" tag at all — SkyblockNbtUtils.getItemId(stack) can only ever return null for
		// them, so the ITEM_SECRET_IDS lookup above (DUNGEON_DECOY/INFLATABLE_JERRY, both guessed ids that
		// were never actually reachable this way) could never fire. Matched by real vanilla item type + name
		// instead, the only signal these two genuinely carry.
		// Real bug found (per user report — "Decoy egg is a goat spawn egg"): the earlier guess (ghast spawn
		// egg, the "white" one) was wrong — Hypixel's real Dungeon Decoy item is a goat spawn egg.
		boolean isDecoy = stack.is(net.minecraft.world.item.Items.GOAT_SPAWN_EGG) && plainName.contains("Decoy");
		boolean isJerryEgg = stack.is(net.minecraft.world.item.Items.VILLAGER_SPAWN_EGG) && plainName.contains("Jerry");
		if (isHealingPotion || isDecoy || isJerryEgg || (skyblockId != null && ITEM_SECRET_IDS.contains(skyblockId))) {
			fire(item.blockPosition(), ClickedBlockType.ITEM_SECRET_PICKUP);
			return;
		}
		// Real gap found (per user report — "the secret pickup step in the dungeon routes still is not
		// triggering when an item is picked up"): ITEM_SECRET_PICKUP above only ever fires for this fixed,
		// manually-curated whitelist, which every prior round's fix has only ever narrowed one specific item
		// at a time (Healing Potion, then Decoy egg, then Jerry egg, then Architect's Draft) rather than
		// actually closing the gap — there will always be another real dungeon item this whitelist hasn't
		// been taught about yet. Every OTHER real local item pickup in a dungeon now also fires this generic
		// signal with its real position — DungeonRoutesFeature's own SECRET_PICKUP step already trusts real
		// ground-truth proximity to the step's expected position for chests/levers (see its own
		// SECRET_CLICK_MAX_DISTANCE), so it falls back to that same proximity check here instead of needing
		// this whitelist to be exhaustive. DungeonClickedBlocksFeature (the only other listener) explicitly
		// ignores this type, so it keeps highlighting only the specifically-known secret item types as before
		// — this is deliberately NOT routed through its chime/highlight, which would otherwise fire on every
		// mundane item pickup in a dungeon (mob drops, floor loot, everything).
		fire(item.blockPosition(), ClickedBlockType.ANY_ITEM_PICKUP);
	}

	private static boolean isHealingSplashPotion(net.minecraft.world.item.ItemStack stack) {
		var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
		return contents != null && contents.potion().isPresent()
			&& contents.potion().get().is(net.minecraft.world.item.alchemy.Potions.HEALING);
	}

	// Same tick-diff approach as item pickups: no vanilla client-side "entity died" event exists without
	// a new mixin, but "a real secret Bat that was nearby is now gone" is an equally reliable signal here
	// (bats don't walk far in one tick, and BAT_TRACK_RANGE is generous), without one.
	private static void tickSecretBats(net.minecraft.client.Minecraft client) {
		if (client.player == null || client.level == null || LISTENERS.isEmpty()) return;
		if (!com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) {
			lastNearbySecretBats.clear();
			return;
		}
		AABB area = client.player.getBoundingBox().inflate(BAT_TRACK_RANGE);
		java.util.Map<Integer, net.minecraft.world.entity.Entity> nowNearby = new java.util.HashMap<>();
		for (var entity : client.level.getEntitiesOfClass(net.minecraft.world.entity.ambient.Bat.class, area)) {
			if (entity.getMaxHealth() != VANILLA_BAT_MAX_HEALTH) {
				// Per user request ("bat also cause that one takes forever"): the bat only despawns after its
				// ~1s death animation — fire the moment it's dead (health 0) instead.
				if (entity.isDeadOrDying()) {
					if (firedBatIds.add(entity.getId())) fire(entity.blockPosition(), ClickedBlockType.SECRET_BAT_DEATH);
					continue;
				}
				nowNearby.put(entity.getId(), entity);
			}
		}
		for (var entry : lastNearbySecretBats.entrySet()) {
			if (nowNearby.containsKey(entry.getKey())) continue;
			if (!firedBatIds.add(entry.getKey())) continue;
			// Being tracked one tick and gone from this same 24-block scan the next is already the exact
			// despawn signal SkyHanni's own onMobDeSpawn listener fires the chime on — this used to also
			// require bat.isRemoved()/!isAlive() on the stale cached Entity reference, but a client-side
			// entity's removal (from the destroy-entity packet) isn't guaranteed to flip those flags on the
			// same object in every case, and requiring it on top of "already gone from the world" was
			// silently dropping real deaths — the actual "chime doesn't proc on bat death anymore" bug.
			fire(entry.getValue().blockPosition(), ClickedBlockType.SECRET_BAT_DEATH);
		}
		lastNearbySecretBats = nowNearby;
	}

	private static final java.util.Set<Integer> firedBatIds = new java.util.HashSet<>();

	// ---- Wither Essence: one signal per real essence, from whichever source is first ----
	// Per user report ("If the server lags after a wither essence is picked up and the chat message doesnt
	// fire it bugs out. Make it search for one for atleast 3 seconds"): an essence can be seen as a head
	// click (instant when the texture is known), an unrecognized-head click confirmed by the secret count or
	// the chat line within WITHER_ESSENCE_CONFIRM_WINDOW_MILLIS, or only the chat line (click missed). Every
	// source funnels through fireEssence so Dungeon Routes and Clicked Blocks get exactly one event:
	// - a head position fires at most once per run;
	// - after a click fires, the next own chat line within 10s is that same essence and is swallowed;
	// - after a chat-only fire, a click within 3s is that same essence and is swallowed.
	private static final String OWN_ESSENCE_LINE = "You found a Wither Essence!";
	private static final java.util.Set<BlockPos> firedEssencePositions = new java.util.HashSet<>();
	// Per user report ("rooms that have two wither essences next to each other and you can pick these up
	// really fast"): swallows are COUNTED, one per essence, so two quick pickups (two clicks, then two chat
	// lines) stay two events instead of the second chat line reading as a third essence. Each entry is its
	// expiry time.
	private static final java.util.ArrayDeque<Long> chatSwallows = new java.util.ArrayDeque<>();
	private static final java.util.ArrayDeque<Long> clickSwallows = new java.util.ArrayDeque<>();

	private static boolean takeSwallow(java.util.ArrayDeque<Long> queue, long now) {
		while (!queue.isEmpty() && queue.peekFirst() < now) queue.pollFirst();
		return queue.pollFirst() != null;
	}

	private static void fireEssence(BlockPos pos, boolean fromChat) {
		long now = System.currentTimeMillis();
		if (fromChat) {
			if (takeSwallow(chatSwallows, now)) return;
			clickSwallows.addLast(now + 3000L);
			fire(null, ClickedBlockType.WITHER_ESSENCE_PICKUP);
			return;
		}
		if (pos != null && !firedEssencePositions.add(pos.immutable())) return;
		if (takeSwallow(clickSwallows, now)) return;
		chatSwallows.addLast(now + 10_000L);
		fire(pos, ClickedBlockType.WITHER_ESSENCE_PICKUP);
	}

	/** Chat hook for the local player's own essence line (teammates' read "<name> found a Wither Essence!"). */
	private static void onChat(String plain) {
		if (!plain.contains(OWN_ESSENCE_LINE) || !com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) return;
		// A click still waiting for confirmation IS this essence — confirm it (with its position) now.
		if (!pendingHeadClicks.isEmpty()) {
			// Oldest first: with two essences grabbed back to back, the first line belongs to the first click.
			PendingHeadClick oldest = pendingHeadClicks.remove(0);
			if (oldest.pos() == null || firedEssencePositions.add(oldest.pos().immutable())) {
				fire(oldest.pos(), ClickedBlockType.WITHER_ESSENCE_PICKUP);
			}
			return;
		}
		fireEssence(null, true);
	}

	/** EntityEventMixin: the server's death event (id 3) for a secret bat — the earliest possible signal. */
	public static void onEntityEvent(net.minecraft.world.entity.Entity entity, byte eventId) {
		if (eventId != (byte) 3 || LISTENERS.isEmpty() || !(entity instanceof net.minecraft.world.entity.ambient.Bat bat)) return;
		if (bat.getMaxHealth() == VANILLA_BAT_MAX_HEALTH || !com.cokelord.skyblocksimplified.util.IslandGate.isInDungeon()) return;
		if (firedBatIds.add(bat.getId())) fire(bat.blockPosition(), ClickedBlockType.SECRET_BAT_DEATH);
	}

	private static void fire(BlockPos pos, ClickedBlockType type) {
		for (BiConsumer<BlockPos, ClickedBlockType> listener : LISTENERS) {
			listener.accept(pos, type);
		}
	}
}
