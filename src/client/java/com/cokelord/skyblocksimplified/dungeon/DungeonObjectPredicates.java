package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.util.SkullTextureUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Entity-matching predicates for the Dungeons Object Hider — ported from SkyHanni's DungeonHideItems.kt,
 * keeping its confirmed nametag-text prefixes and its confirmed repo skull textures (fetched from
 * github.com/hannibal002/SkyHanni-REPO's Skulls.json, same source as FlareDetector's textures). Each
 * of these decorative dungeon objects is really an ArmorStand wearing (or holding) a custom-skin head,
 * or in a couple of cases a plain dropped ItemStack — not a guessed format, the same detection SkyHanni
 * itself uses.
 */
public final class DungeonObjectPredicates {
	private static final String SOUL_WEAVER_TEXTURE =
		"eyJ0aW1lc3RhbXAiOjE1NTk1ODAzNjI1NTMsInByb2ZpbGVJZCI6ImU3NmYwZDlhZjc4MjQyYzM5NDY2ZDY3MjE3MzBmNDUzIiwicHJvZmlsZU5hbWUiOiJLbGxscmFoIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yZjI0ZWQ2ODc1MzA0ZmE0YTFmMGM3ODViMmNiNmE2YTcyNTYzZTlmM2UyNGVhNTVlMTgxNzg0NTIxMTlhYTY2In19fQ==";
	private static final String BLESSING_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTYzNTE0NTU0NzUxMiwKICAicHJvZmlsZUlkIiA6ICJmYzUwMjkzYTVkMGI0NzViYWYwNDJhNzIwMWJhMzBkMiIsCiAgInByb2ZpbGVOYW1lIiA6ICJDVUNGTDE3IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FiNDM2ZWRiN2I4MDVlMTMzZjdkMzQ4OWQ0NmNlMDYzNmY3ZTFjYjM3YjY3Njg5ZmFhMTJlNjk4ZGJiZDdjNjYiCiAgICB9CiAgfQp9";
	private static final String REVIVE_STONE_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTg1MDM4MjMzNCwKICAicHJvZmlsZUlkIiA6ICIxZDlkYmE3NzdlMWE0NzVkOTQ1ZDYxNmZlYzNiNjhlMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJGcmFzeWRpIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2I2YTc2Y2MyMmU3YzJhYjljNTQwZDEyNDRlYWRiYTU4MWY1ZGQ5ZTE4ZjlhZGFjZjA1MjgwYTViNDhiOGY2MTgiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";
	private static final String PREMIUM_FLESH_TEXTURE =
		"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWE3NWU4YjA0NGM3MjAxYTRiMmU4NTZiZTRmYzMxNmE1YWFlYzY2NTc2MTY5YmFiNTg3MmE4ODUzNGI4MDI1NiJ9fX0K";
	private static final String ABILITY_ORB_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTYzODUyNDAzODE5OCwKICAicHJvZmlsZUlkIiA6ICIzOWEzOTMzZWE4MjU0OGU3ODQwNzQ1YzBjNGY3MjU2ZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJkZW1pbmVjcmFmdGVybG9sIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVlZTRiYjQ4MjFkMGY1ZWQ4NjVjMjEwOTBhODBiNWVlN2Q1MjI2ODQ3NmVlMjVkMzg5NzEwZjdjYzlmMTEwZDYiCiAgICB9CiAgfQp9";
	private static final String SUPPORT_ORB_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTYwNTM1NjUyNzQzOSwKICAicHJvZmlsZUlkIiA6ICJhYTZhNDA5NjU4YTk0MDIwYmU3OGQwN2JkMzVlNTg5MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiejE0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzE1NzhiNGFmM2ZkZDkxNTFiODUwYjEzYzY3YzQ1ODAyMjRjN2Y2MDA1MjcxM2YyZDE1MWY3YzE1ZGMwZDdiMzQiCiAgICB9CiAgfQp9";
	private static final String DAMAGE_ORB_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTYwNDY4NDIxNTAyMCwKICAicHJvZmlsZUlkIiA6ICI3NzI3ZDM1NjY5Zjk0MTUxODAyM2Q2MmM2ODE3NTkxOCIsCiAgInByb2ZpbGVOYW1lIiA6ICJsaWJyYXJ5ZnJlYWsiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWI4NmRhMmUyNDNjMDVkYzA4OThiMGNjNWQzZTY0ODc3MTczMTc3ZTBhMjM5NDQyNWNlYzEwMDI1OWNiNDUyNiIKICAgIH0KICB9Cn0=";
	private static final String HEALER_FAIRY_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTQ2MzA5MTA0NywKICAicHJvZmlsZUlkIiA6ICIyNjRkYzBlYjVlZGI0ZmI3OTgxNWIyZGY1NGY0OTgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJxdWludHVwbGV0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlZWRjZmZjNmExMWEzODM0YTI4ODQ5Y2MzMTZhZjdhMjc1MmEzNzZkNTM2Y2Y4NDAzOWNmNzkxMDhiMTY3YWUiCiAgICB9CiAgfQp9";
	// Real Necron/P5 tentacle skull texture — confirmed against NoammAddons' own RenderOptimizer.kt
	// TENTACLE_TEXTURE constant (used there to discard the tentacle's equipment packet outright; here it's
	// consumed by EntityHideRegistry via HideTentaclesFeature, same non-mixin render-time hide every other
	// "Hide X" performance toggle in this codebase already uses).
	private static final String TENTACLE_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTg1NzI3NzI0OSwKICAicHJvZmlsZUlkIiA6ICIxODA1Y2E2MmM0ZDI0M2NiOWQxYmY4YmM5N2E1YjgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJSdWxsZWQiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzdkODM2NzQ5MjZiODk3MTRlNmI1YTU1NDcwNTAxYzA0YjA2NmRkODdiZjZjMzM1Y2RkYzZlNjBhMWExYTVmNSIKICAgIH0KICB9Cn0=";

	private DungeonObjectPredicates() {}

	private static String headTexture(Entity entity) {
		if (!(entity instanceof ArmorStand stand)) return null;
		return SkullTextureUtil.fromItem(stand.getItemBySlot(EquipmentSlot.HEAD));
	}

	private static String handTexture(Entity entity) {
		if (!(entity instanceof ArmorStand stand)) return null;
		return SkullTextureUtil.fromItem(stand.getItemBySlot(EquipmentSlot.MAINHAND));
	}

	// getCustomName().getString() strips Style-based formatting entirely (confirmed via decompiled
	// Component.getString() — see TreeProgressDisplayFeature's doc comment), so if Hypixel sends this
	// entity's nametag via structured Style color rather than literal "§" characters embedded in the
	// content, getString() would never contain "§" at all. Stripping any literal codes here (a no-op if
	// there were none) and comparing against plain, code-free literals below handles both cases.
	private static String plainName(Entity entity) {
		var name = entity.getCustomName();
		return name != null ? name.getString().replaceAll("§.", "") : null;
	}

	private static boolean isItemNamed(Entity entity, String cleanName) {
		if (!(entity instanceof ItemEntity itemEntity)) return false;
		ItemStack stack = itemEntity.getItem();
		return stack.getHoverName().getString().replaceAll("§.", "").equals(cleanName);
	}

	// Per user request ("Remove the other superboom hiding methods to make sure it doesnt hide anything
	// else. The Nametag needs to hide however."): the plain-vanilla-TNT-in-mainhand match and the exploding
	// PrimedTnt match were both hiding things the user didn't want hidden — removed on the user's explicit
	// instruction. Follow-up ("keep the new superboom method that actually fixed it, the armorstand holding
	// it... I was having issues where primed tnt was not rendering"): the ArmorStand-mainhand real-NBT-id
	// check stays — it's the one branch a live Debug log actually confirmed matches a real case (an
	// ArmorStand holding the genuine SUPERBOOM_TNT item) — and PrimedTnt stays removed since hiding it was
	// exactly what caused real primed TNT to stop rendering.
	public static boolean isSuperboomTnt(Entity entity) {
		String name = plainName(entity);
		if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("superboom")) return true;
		if (entity instanceof ArmorStand stand) {
			ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
			if (!head.isEmpty() && head.getHoverName().getString().replaceAll("§.", "")
					.toLowerCase(java.util.Locale.ROOT).contains("superboom")) return true;
			ItemStack mainHand = stand.getItemBySlot(EquipmentSlot.MAINHAND);
			if ("SUPERBOOM_TNT".equals(com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemId(mainHand))) return true;
		}
		if (entity instanceof ItemEntity itemEntity) {
			String itemName = itemEntity.getItem().getHoverName().getString().replaceAll("§.", "");
			if (itemName.toLowerCase(java.util.Locale.ROOT).contains("superboom")) return true;
		}
		return false;
	}

	public static boolean isBlessing(Entity entity) {
		String name = plainName(entity);
		if (name != null && name.startsWith("Blessing of ")) return true;
		return BLESSING_TEXTURE.equals(headTexture(entity));
	}

	public static boolean isReviveStone(Entity entity) {
		String name = plainName(entity);
		if (name != null && name.equals("Revive Stone")) return true;
		if (REVIVE_STONE_TEXTURE.equals(headTexture(entity))) return true;
		return isItemNamed(entity, "Revive Stone");
	}

	public static boolean isPremiumFlesh(Entity entity) {
		String name = plainName(entity);
		if (name != null && name.equals("Premium Flesh")) return true;
		return PREMIUM_FLESH_TEXTURE.equals(headTexture(entity));
	}

	public static boolean isJournalEntry(Entity entity) {
		return isItemNamed(entity, "Journal Entry");
	}

	public static boolean isHealerOrb(Entity entity) {
		String name = plainName(entity);
		if (name != null && (name.startsWith("DAMAGE ") || name.startsWith("ABILITY DAMAGE ") || name.startsWith("DEFENSE "))) {
			return true;
		}
		String texture = headTexture(entity);
		return ABILITY_ORB_TEXTURE.equals(texture) || SUPPORT_ORB_TEXTURE.equals(texture) || DAMAGE_ORB_TEXTURE.equals(texture);
	}

	public static boolean isSkeletonSkull(Entity entity) {
		if (!(entity instanceof ArmorStand stand)) return false;
		ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
		return head.getHoverName().getString().replaceAll("§.", "").equals("Skeleton Skull");
	}

	public static boolean isHealerFairy(Entity entity) {
		return HEALER_FAIRY_TEXTURE.equals(handTexture(entity));
	}

	public static boolean isSoulweaverSkull(Entity entity) {
		return SOUL_WEAVER_TEXTURE.equals(headTexture(entity));
	}

	public static boolean isP5Tentacle(Entity entity) {
		return TENTACLE_TEXTURE.equals(headTexture(entity));
	}

	// Per user request ("hide these annoying wither skulls that send out when a wither skeleton dies in a
	// dungeon... im pretty sure it shares a texture with the wither artifact item"): the Wither Artifact
	// accessory's own real Hypixel icon IS the vanilla Wither Skeleton Skull item (net.minecraft.world.item
	// .Items.WITHER_SKELETON_SKULL) — the real clutter this describes is that same vanilla skull item
	// dropping (and scattering across the floor) every time a dungeon Wither Skeleton mob dies, not a
	// custom-skinned prop like the other predicates in this class. A genuine dropped skull the player picked
	// up themselves (e.g. via /kit or a real drop trade) would match too, but that's never a real gameplay
	// scenario inside a Catacombs/Master Mode run.
	//
	// Real bug found (per user report — "wither head hider doesnt work" with a live screenshot showing the
	// skulls): the ItemEntity-only check above assumed a freely dropped item, the exact same wrong assumption
	// the Superboom TNT predicate made before ITS own confirmed fix this round (that one turned out to really
	// be an ArmorStand holding the real item in its MAINHAND slot, not a dropped ItemEntity) — so this also now
	// checks an ArmorStand wearing the same real vanilla skull in its HEAD slot, the same "prop wearing/holding
	// the real item" shape already confirmed for Superboom TNT. Also covers the real vanilla WitherSkull
	// projectile entity (gated to dungeons only, same reasoning as isSuperboomTnt's PrimedTnt branch) in case
	// "send out when a wither skeleton dies" literally means a launched projectile rather than a static prop.
	// Falls back to the same owner-gated unmatched-candidate diagnostic as isSuperboomTnt if none of these
	// match either, so a future live test can settle this for real instead of a third guess.
	public static boolean isWitherSkull(Entity entity) {
		if (entity instanceof ItemEntity itemEntity && itemEntity.getItem().is(net.minecraft.world.item.Items.WITHER_SKELETON_SKULL)) {
			return true;
		}
		if (entity instanceof ArmorStand stand) {
			if (stand.getItemBySlot(EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.WITHER_SKELETON_SKULL)) return true;
			// Per user report ("The little wither heads is the same thing i think but actual default minecraft
			// wither skeleton skulls"): the same ArmorStand-holding-the-real-item shape the Superboom TNT fix
			// confirmed live — checked here in MAINHAND, same slot Superboom TNT's own confirmed case uses.
			if (stand.getItemBySlot(EquipmentSlot.MAINHAND).is(net.minecraft.world.item.Items.WITHER_SKELETON_SKULL)) return true;
		}
		if (entity instanceof net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull && DungeonState.isInDungeon()) return true;
		return false;
	}
}
