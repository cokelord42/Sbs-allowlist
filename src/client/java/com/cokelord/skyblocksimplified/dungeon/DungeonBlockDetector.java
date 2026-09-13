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
	public enum ClickedBlockType { LEVER, CHEST, TRAPPED_CHEST, WITHER_ESSENCE_PICKUP, ITEM_SECRET_PICKUP, SECRET_BAT_DEATH }

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
			if (type != null) {
				fire(pos, type);
			}
			return InteractionResult.PASS;
		});
		// Wrapped in try-catch — see BlessingParticleFilter's doc comment on its own registration for why:
		// this registers before FeatureRegistry::tickAll (during feature construction), and an uncaught
		// throw here would silently skip tickAll (and every feature it ticks) for that whole frame.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tickSecretBats(client);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("DungeonBlockDetector secret-bat tick threw, skipping this tick", e);
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
		var stack = item.getItem();
		String skyblockId = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemId(stack);
		String texture = SkullTextureUtil.fromItem(stack);
		String plainName = stack.getHoverName().getString().replaceAll("§.", "");
		boolean isWitherEssence = WITHER_ESSENCE_TEXTURE.equals(texture) || plainName.contains("Wither Essence");
		if (isWitherEssence) {
			fire(item.blockPosition(), ClickedBlockType.WITHER_ESSENCE_PICKUP);
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
		}
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
				nowNearby.put(entity.getId(), entity);
			}
		}
		for (var entry : lastNearbySecretBats.entrySet()) {
			if (nowNearby.containsKey(entry.getKey())) continue;
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

	private static void fire(BlockPos pos, ClickedBlockType type) {
		for (BiConsumer<BlockPos, ClickedBlockType> listener : LISTENERS) {
			listener.accept(pos, type);
		}
	}
}
