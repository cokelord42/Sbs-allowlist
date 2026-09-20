package com.cokelord.skyblocksimplified.dungeon;

import net.minecraft.world.entity.player.Player;

/** A dungeon teammate's tracked state — ported from Odin's {@code DungeonPlayer} data class. */
public class DungeonPlayer {
	public final String name;
	public DungeonClass clazz;
	public int clazzLvl;
	public Player entity;
	public boolean isDead;
	public int deaths;

	/** The last real world position seen for this teammate via a live tracked {@link #entity} (NaN until the
	 *  first sighting this run). DungeonMapFeature's marker fallback (used whenever a teammate has no live
	 *  entity — out of render distance, or briefly right after a Spirit Leap/teleport before the client
	 *  starts tracking them at the new spot) reads this to match the right anonymous vanilla map decoration
	 *  back to the right player by nearest position, instead of just grabbing decorations in list order —
	 *  see that class's own doc comment on the marker fallback for the real "teammates swap positions on the
	 *  map" bug this fixes. Reset to NaN on a fresh run (a new DungeonPlayer is created per run) so a
	 *  leftover position from a previous run/floor is never mistaken for real intel on the current one. */
	public float lastWorldX = Float.NaN;
	public float lastWorldZ = Float.NaN;
	public long lastSeenAtMillis = 0L;

	public DungeonPlayer(String name, DungeonClass clazz, int clazzLvl) {
		this.name = name;
		this.clazz = clazz;
		this.clazzLvl = clazzLvl;
	}

	public float getRenderYaw() {
		return entity != null ? entity.getYRot() : 0f;
	}
}
