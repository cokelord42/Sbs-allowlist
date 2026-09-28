package com.cokelord.skyblocksimplified.highlight;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * A client-tick counter for per-entity caches. Rule predicates (mob highlight / hide / transparency) look at
 * an entity's name, type and state — none of which change between frames of the same tick — yet render hooks
 * evaluated every rule for every entity several times per FRAME. Caching per (entity, tick) turns that into
 * one evaluation per entity per tick (10x+ fewer at high FPS).
 */
public final class TickCache {
	private static long tick;
	private static boolean registered;

	private TickCache() {}

	public static synchronized void ensureRegistered() {
		if (registered) return;
		registered = true;
		ClientTickEvents.START_CLIENT_TICK.register(client -> tick++);
	}

	public static long tick() {
		return tick;
	}

	/** Entity-id keyed cache that empties itself whenever a new client tick starts. */
	public static final class PerTick<V> {
		private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<V> map = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
		private long stamp = -1;

		public PerTick() { ensureRegistered(); }

		public V get(int id) {
			if (stamp != tick) {
				map.clear();
				stamp = tick;
			}
			return map.get(id);
		}

		public void put(int id, V value) { map.put(id, value); }

		public void clear() { map.clear(); }
	}
}
