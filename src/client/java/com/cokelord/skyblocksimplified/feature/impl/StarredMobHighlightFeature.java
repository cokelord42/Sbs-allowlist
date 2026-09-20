package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.MobHighlightRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Highlights dungeon mobs that have spawned "starred" (a rarer, harder variant Hypixel marks with a ✯ in
 * the nametag and boosted health) — the real mob entity is found by looking just below the starred armor
 * stand nametag holder. Ported from Odin's {@code Highlight.kt}, folded into this codebase's existing
 * {@link MobHighlightFeature}/{@link MobHighlightRegistry} pipeline as an additional rule source (per the
 * port plan: keep the existing 2D/2D-fill toggle as-is, don't replace it) rather than a separate render
 * mode — this class only supplies a different <em>matching</em> strategy (a tracked entity set populated
 * by scanning nametags each tick, instead of a name predicate); rendering (outline/fill, thickness,
 * tracers) is fully inherited from {@link MobHighlightFeature}.
 *
 */
public class StarredMobHighlightFeature extends MobHighlightFeature {
	// "Fels" restored here — see onTick's own doc comment for why the last two rounds' Fels-specific
	// deviations (a widened search box, then an EnderMan-entity-type theory) were both wrong: three
	// independent real mods confirm it's handled with zero special-casing, exactly like every other name
	// in this set.
	private static final Set<String> DUNGEON_MOB_SPAWN_NAMES = Set.of(
		"Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer",
		"Spider", "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer",
		"Shadow Assassin");
	// https://regex101.com/r/QQf502/2 — ported verbatim from Odin.
	private static final Pattern STARRED_PATTERN = Pattern.compile("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$");

	private boolean hideNonStarredNames = true;
	// Real bug found (per user report — "Fels dont get highlighted when woken up if the nametags are off"):
	// both this and hideNonStarredNames above used to DISCARD the nametag ArmorStand entirely
	// (Entity.RemovalReason.DISCARDED) to hide the floating text. The doc comment this replaced assumed the
	// real mob is always found in the SAME tick the stand first reads as starred, but that's not guaranteed —
	// Fels in particular can have their stand update to starred a tick or more before the real mob entity
	// itself is sent/spawned client-side. If the stand gets discarded before that later tick, the search-box
	// scan below (which needs the stand to still exist to search underneath it) can never run again for that
	// mob, and it's never linked/highlighted at all — a permanent miss, not just a delayed one. Both toggles
	// now use setCustomNameVisible(false) instead: same visual result (the floating name text disappears),
	// but the stand entity itself stays alive so re-scanning can keep working on later ticks.
	private boolean hideStarredNames = false;

	private final Set<Entity> trackedEntities = new HashSet<>();

	public StarredMobHighlightFeature() {
		super("starred_mob_highlight", "Starred Mob Highlight", FeatureCategory.COMBAT, "Dungeons", 0xFFFFFFFF);
	}

	@Override
	protected boolean matches(Entity entity) {
		return trackedEntities.contains(entity);
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			MobHighlightRegistry.setRule(getId(), this::matches, getColor(), this);
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			MobHighlightRegistry.clearRule(getId());
			registered = false;
		}
		trackedEntities.clear();
	}

	@Override
	public void onTick(Minecraft client) {
		if (client.level == null || !DungeonState.isInDungeon() || DungeonState.isInBoss()) return;

		for (Entity e : client.level.entitiesForRendering()) {
			if (!e.isAlive()) continue;

			// Per explicit user request ("Remove all fixes to highlighting the fel heads, since im starting
			// to think thats not actually allowed"): the last several rounds' attempts at highlighting a
			// separate "resting/inactive Fels head" state (first an ArmorStand+skull-texture guess, then a
			// nameless-EnderMan-entity-type guess) are removed entirely — back to only ever highlighting the
			// STARRED/awakened variant below, exactly matching Odin/SkyHanni/devonian's own confirmed real
			// behavior with zero special-casing for Fels specifically.
			if (!(e instanceof ArmorStand)) continue;
			ArmorStand stand = (ArmorStand) e;
			trackedEntities.remove(stand);

			String name = stand.getName().getString();
			if (DUNGEON_MOB_SPAWN_NAMES.stream().noneMatch(name::contains)) continue;

			boolean isStarred = STARRED_PATTERN.matcher(name).matches();
			boolean allowInvisibleShadowAssassin = name.contains("Shadow Assassin");

			if (hideNonStarredNames && stand.isInvisible() && !isStarred) stand.setCustomNameVisible(false);

			// Real bug found (per user report — "shadow assassins still dont get highlighted", TWICE now
			// after the equipment-fingerprint fix): this whole search+highlight block used to be gated on
			// `isStarred` alone — a plain (non-elite) Shadow Assassin, which is what actually spawns in normal
			// rooms most of the time, would never even reach the search below at all, regardless of whether
			// the equipment check itself was correct. Invisibility is inherent to the MOB TYPE here, not just
			// its starred variant, so Shadow Assassin now bypasses the isStarred gate entirely — every other
			// mob type keeps the original starred-only behavior.
			if (isStarred || allowInvisibleShadowAssassin) {
				if (isStarred && hideStarredNames) stand.setCustomNameVisible(false);
				// Real bug found (per user report — "every single starred mob head is highlighted on top of
				// their regular highlight"): a previous round unconditionally added the nametag stand itself
				// to trackedEntities for EVERY dungeon mob type here, not just Fels — Odin's own real
				// Highlight.kt (confirmed source) never highlights the stand at all, only the real mob found
				// below it, so this was a straight regression for every non-Fel mob (double highlight: the
				// invisible stand's own outline stacked on the real mob's).
				AABB searchBox = stand.getBoundingBox().move(0, -1.0, 0);
				Entity realMob = null;
				for (Entity candidate : client.level.getEntities(stand, searchBox, target -> isValidTarget(target, allowInvisibleShadowAssassin))) {
					realMob = candidate;
					break;
				}
				// Per user request ("I want the highlighted starred mobs to hide when not in their room if
				// possible"): only keep tracking the real mob while the player is standing in the SAME
				// dungeon room it's in.
				if (realMob != null && shouldShowInCurrentRoom(realMob)) trackedEntities.add(realMob);
			}
		}

		// Real bug found (per user report — "shadow assassins are starred mobs but the star doesnt show up
		// until visible because they are invisible and have no nametags"): the ENTIRE mechanism above
		// requires finding a real ArmorStand nametag stand first, matched by name, before it ever looks for
		// the real mob underneath — but per the user's own clarification, a genuinely stealthed Shadow
		// Assassin has no nametag stand to find AT ALL while invisible (that's why it was only ever getting
		// highlighted once it un-stealthed and a real nametag appeared). This is a fully independent scan of
		// every entity for the same boots-only equipment fingerprint, with zero dependency on any nametag —
		// the only signal that can actually catch one while it's still stealthed.
		//
		// Real bug found AGAIN (per user report — "still dont get highlighted until they are no longer
		// invisible" — after the above independent scan was already live): this loop blanket-excluded every
		// `Player` instance, but Hypixel reskins several humanoid dungeon mobs (Shadow Assassin included) as
		// fake NPC players — exactly the same real UUID-version-2 fake-player entity `isValidTarget` above
		// already special-cases for the visible/nametag path. Blanket-excluding Player here meant a Shadow
		// Assassin implemented as one of these fake players could never be caught by this scan at all, no
		// matter how correct the equipment fingerprint was — only a REAL player (the local player, or any
		// other real UUID-version-4 player) should ever be excluded.
		for (Entity e : client.level.entitiesForRendering()) {
			if (!e.isAlive() || !e.isInvisible() || e instanceof ArmorStand) continue;
			if (e instanceof Player player && (player == client.player || player.getUUID().version() != 2)) continue;
			if (trackedEntities.contains(e)) continue;
			if (!isLikelyShadowAssassin(e)) continue;
			if (shouldShowInCurrentRoom(e)) trackedEntities.add(e);
		}
		trackedEntities.removeIf(e -> !e.isAlive() || !shouldShowInCurrentRoom(e));
	}

	/** True unless the entity is confirmed to be in a DIFFERENT, already-resolved room than the player's
	 *  own current room — {@link WorldScan#roomAt} reuses the exact tile-index math {@link WorldScan}'s own
	 *  player-room tracking already uses, generalized to an arbitrary position. Deliberately fails open
	 *  (stays visible) whenever either side's room isn't resolved yet, rather than hiding on uncertainty —
	 *  room detection can legitimately lag a tick or two right after entering a run/room, and hiding
	 *  everything in that window would read as the highlight randomly not working rather than the
	 *  intentional "different room" suppression this was actually asked for. */
	private static boolean shouldShowInCurrentRoom(Entity entity) {
		DungeonRoom playerRoom = WorldScan.getCurrentRoom();
		if (playerRoom == null) return true;
		DungeonRoom entityRoom = WorldScan.roomAt(entity.getBlockX(), entity.getBlockZ());
		return entityRoom == null || entityRoom == playerRoom;
	}

	private static boolean isValidTarget(Entity entity, boolean allowInvisibleShadowAssassin) {
		Minecraft mc = Minecraft.getInstance();
		if (entity instanceof ArmorStand) return false;
		if (entity instanceof WitherBoss) return false;
		if (entity instanceof Player player) return player.getUUID().version() == 2 && player != mc.player;
		// Reverted a previous round's speculative "!entity.hasCustomName()" addition here (meant to fix a
		// "highlights bugged mobs" report) — per a later user report, it likely broke Fels specifically
		// (waking one no longer highlighted at all even when starred), and that exclusion was never
		// confirmed against real Hypixel data in the first place (Odin's own real Highlight.kt has no such
		// check either — see isValidEntity there). Back to the plain non-invisible catch-all until there's
		// a confirmed, targeted signal for the original "bugged mobs" complaint.
		if (!entity.isInvisible()) return true;
		return allowInvisibleShadowAssassin && isLikelyShadowAssassin(entity);
	}

	/** Shadow Assassins stay invisible until they backstab (per user report), so the plain
	 *  "!entity.isInvisible()" catch-all above would never match one at all. Rather than blanket-allow every
	 *  invisible entity through (which would wrongly sweep up other invisible dungeon entities this ESP was
	 *  never meant to highlight), this fingerprints the actual equipment Hypixel gives a Shadow Assassin.
	 *
	 *  <p>Real bug found twice now (per user reports): the first version guessed dyed-purple leather boots
	 *  (wrong RGB), the second version "fixed" that by checking for pure black (RGB 0,0,0) — per the user's
	 *  own correction, that black-boots check was actually fingerprinting the wearable "Shadow Assassin
	 *  Boots" item drop from the Floor 5 boss, a completely different thing from the real mob. The user's own
	 *  clarification: the real Shadow Assassin mob just needs to be matched as "an invisible humanoid with
	 *  only boots equipped" — no color/material check on the boots at all, just boots present and every
	 *  other armor slot empty. */
	// Package-private (not private) rather than tightened back to private — this class's own single caller
	// doesn't need it, but nothing forces this fingerprint check to stay this class's own private concern.
	static boolean isLikelyShadowAssassin(Entity entity) {
		if (!(entity instanceof LivingEntity living)) return false;
		if (living.getItemBySlot(EquipmentSlot.FEET).isEmpty()) return false;
		return living.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
			&& living.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
			&& living.getItemBySlot(EquipmentSlot.LEGS).isEmpty();
	}

	public boolean isHideNonStarredNames() { return hideNonStarredNames; }
	public void setHideNonStarredNames(boolean value) { hideNonStarredNames = value; }
	public boolean isHideStarredNames() { return hideStarredNames; }
	public void setHideStarredNames(boolean value) { hideStarredNames = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = (JsonObject) super.savePersistedData();
		obj.addProperty("hideNonStarredNames", hideNonStarredNames);
		obj.addProperty("hideStarredNames", hideStarredNames);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("hideNonStarredNames")) hideNonStarredNames = obj.get("hideNonStarredNames").getAsBoolean();
		if (obj.has("hideStarredNames")) hideStarredNames = obj.get("hideStarredNames").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Highlights dungeon mobs that spawned as a rarer \"starred\" variant.";
	}
}
