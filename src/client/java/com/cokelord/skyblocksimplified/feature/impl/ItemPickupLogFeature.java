package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Logs items added to / removed from the player's own inventory while no GUI is open — ported from
 * SkyHanni's ItemPickupLog.kt, scoped to its core mechanism (there's no generic "item picked up" client
 * event in Fabric, so like SkyHanni this diffs the player's own 36-slot inventory once a tick and treats
 * any per-item count delta as a pickup/drop) with its two confirmed real toggles (Compact Lines, Compact
 * Numbers) and the 1-20s expiration slider. Sacks/Shards/Coins tracking and the coin-value total are left
 * out: those need SkyHanni's SackChangeEvent/ShardEvent/PurseChangeEvent equivalents, none of which exist
 * in this project yet — adding a "Sacks" toggle with no real sack-reading behind it would be a dead
 * setting, so it's simply not here rather than faked.
 *
 * <p>Alignment is forced to the top (per the wishlist), unlike SkyHanni's configurable dropdown.
 *
 * <p>Grouping key is (vanilla item id, cleaned display name) rather than SkyHanni's (internal name,
 * clean name, rarity) — this project doesn't have a confirmed rarity reader, and NBT-based internal names
 * are already read via SkyblockNbtUtils elsewhere but aren't necessary here since display name + item id
 * already disambiguates the overwhelming majority of real pickups.
 */
public class ItemPickupLogFeature extends TabWidgetOverlayFeature {
	private static final long EXPIRE_MIN_SECONDS = 1;
	private static final long EXPIRE_MAX_SECONDS = 20;

	private static final class PickupEntry {
		final String displayName;
		final String rarityColor;
		final ItemStack icon;
		long amount;
		long lastUpdateMillis;

		PickupEntry(String displayName, String rarityColor, ItemStack icon, long amount) {
			this.displayName = displayName;
			this.rarityColor = rarityColor;
			this.icon = icon;
			this.amount = amount;
			this.lastUpdateMillis = System.currentTimeMillis();
		}
	}

	// Real per-tick allocation hotspot found (final optimization pass): onTick() used to allocate 4 fresh
	// HashMaps every single tick (20/sec, unconditionally while this feature is enabled and no screen is
	// open) just to diff them against these four and then copy the results back in with clear()+putAll() —
	// discarding all 4 maps' worth of work immediately after. These "scratch" maps are now permanent,
	// reused buffers: each tick fills them fresh (clear(), not reallocate) and then SWAPS references with
	// the "last" maps below (a field-reference swap, not a copy) instead of copying entries across — same
	// end state, zero steady-state allocation and no more entry-by-entry copy.
	private Map<String, Long> lastSnapshot = new HashMap<>();
	private Map<String, String> lastDisplayNames = new HashMap<>();
	private Map<String, String> lastRarityColors = new HashMap<>();
	private Map<String, ItemStack> lastIcons = new HashMap<>();
	private Map<String, Long> scratchSnapshot = new HashMap<>();
	private Map<String, String> scratchDisplayNames = new HashMap<>();
	private Map<String, String> scratchRarityColors = new HashMap<>();
	private Map<String, ItemStack> scratchIcons = new HashMap<>();
	private final Map<String, PickupEntry> added = new HashMap<>();
	private final Map<String, PickupEntry> removed = new HashMap<>();

	private boolean compactLines = true;
	private boolean compactNumbers = false;
	private int expireSeconds = 10;

	public ItemPickupLogFeature() {
		super("item_pickup_log", "Item Pickup Log", FeatureCategory.INVENTORY, "Storage", 0.02f, 0.3f);
	}

	// Real bug found (per user report — "Moving the pickup log past the crosshair makes the box for it in
	// the edit gui panel place on the outside, so it doesn't place inside the box"): this class overrides
	// render() with its own icon+text layout (below) that always grows rightward from the anchor, same as
	// every screen position — it never flips to right-aligned/leftward growth past screen-center the way
	// TabWidgetOverlayFeature's own default render() does. Left inheriting rightAlignsPastCenter()'s
	// TabWidgetOverlayFeature default of true, the edit screen's hit-box logic still assumed the leftward-
	// growth flip once dragged past center, disagreeing with what render() actually draws.
	@Override
	public boolean rightAlignsPastCenter() {
		return false;
	}

	public boolean isCompactLines() {
		return compactLines;
	}

	public void setCompactLines(boolean compactLines) {
		this.compactLines = compactLines;
	}

	public boolean isCompactNumbers() {
		return compactNumbers;
	}

	public void setCompactNumbers(boolean compactNumbers) {
		this.compactNumbers = compactNumbers;
	}

	public int getExpireSeconds() {
		return expireSeconds;
	}

	public void setExpireSeconds(int expireSeconds) {
		this.expireSeconds = (int) Math.max(EXPIRE_MIN_SECONDS, Math.min(EXPIRE_MAX_SECONDS, expireSeconds));
	}

	@Override
	public Map<String, Float> getPersistedFloats() {
		return Map.of("expire_seconds", (float) expireSeconds);
	}

	@Override
	public void loadPersistedFloats(Map<String, Float> values) {
		Float value = values.get("expire_seconds");
		if (value != null) setExpireSeconds(Math.round(value));
	}

	// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when i
	// relaunch"): these used to build a brand new JsonObject, discarding the position/scale the
	// TabWidgetOverlayFeature base class now persists (see that class's own doc comment) — now calls
	// through super and merges its own keys onto the same object.
	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = (JsonObject) super.savePersistedData();
		obj.addProperty("compact_lines", compactLines);
		obj.addProperty("compact_numbers", compactNumbers);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("compact_lines")) compactLines = obj.get("compact_lines").getAsBoolean();
		if (obj.has("compact_numbers")) compactNumbers = obj.get("compact_numbers").getAsBoolean();
	}

	private boolean seeded = false;

	// Populates the baseline from whatever's already in the inventory the moment this turns on, instead
	// of diffing against an empty snapshot on the very first tick — without this, every item already
	// owned when the toggle is enabled reads as a fresh "pickup" of its full stack size.
	@Override
	protected void onEnable() {
		seeded = false;
	}

	private void seedSnapshot(Minecraft client) {
		lastSnapshot.clear();
		lastDisplayNames.clear();
		lastRarityColors.clear();
		lastIcons.clear();
		Inventory inventory = client.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || isSkyblockMenu(stack)) continue;
			String key = keyOf(stack);
			lastSnapshot.merge(key, (long) stack.getCount(), Long::sum);
			lastDisplayNames.putIfAbsent(key, stack.getHoverName().getString());
			lastRarityColors.putIfAbsent(key, rarityColorOf(stack));
			lastIcons.putIfAbsent(key, stack.copyWithCount(1));
		}
		seeded = true;
	}

	@Override
	public void onTick(Minecraft client) {
		if (client.gui.screen() != null || client.player == null) return;
		if (!seeded) {
			seedSnapshot(client);
			return;
		}

		Map<String, Long> current = scratchSnapshot;
		Map<String, String> displayNames = scratchDisplayNames;
		Map<String, String> rarityColors = scratchRarityColors;
		Map<String, ItemStack> icons = scratchIcons;
		current.clear();
		displayNames.clear();
		rarityColors.clear();
		icons.clear();
		Inventory inventory = client.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || isSkyblockMenu(stack)) continue;
			String key = keyOf(stack);
			current.merge(key, (long) stack.getCount(), Long::sum);
			displayNames.putIfAbsent(key, stack.getHoverName().getString());
			rarityColors.putIfAbsent(key, rarityColorOf(stack));
			icons.putIfAbsent(key, stack.copyWithCount(1));
		}

		if (!lastSnapshot.isEmpty() || !current.isEmpty()) {
			for (Map.Entry<String, Long> entry : current.entrySet()) {
				long delta = entry.getValue() - lastSnapshot.getOrDefault(entry.getKey(), 0L);
				if (delta > 0) record(added, removed, entry.getKey(), displayNames.get(entry.getKey()), rarityColors.get(entry.getKey()), icons.get(entry.getKey()), delta);
				else if (delta < 0) record(removed, added, entry.getKey(), displayNames.get(entry.getKey()), rarityColors.get(entry.getKey()), icons.get(entry.getKey()), -delta);
			}
			for (String key : lastSnapshot.keySet()) {
				if (!current.containsKey(key)) {
					long delta = lastSnapshot.get(key);
					record(removed, added, key, lastDisplayNames.get(key), lastRarityColors.get(key), lastIcons.get(key), delta);
				}
			}
		}
		// Swap references instead of clearing+copying: the "current" tick's maps become next tick's "last"
		// maps, and the old "last" maps become next tick's scratch buffers to fill and clear again.
		scratchSnapshot = lastSnapshot;
		scratchDisplayNames = lastDisplayNames;
		scratchRarityColors = lastRarityColors;
		scratchIcons = lastIcons;
		lastSnapshot = current;
		lastDisplayNames = displayNames;
		lastRarityColors = rarityColors;
		lastIcons = icons;

		long now = System.currentTimeMillis();
		added.values().removeIf(e -> now - e.lastUpdateMillis > expireSeconds * 1000L);
		removed.values().removeIf(e -> now - e.lastUpdateMillis > expireSeconds * 1000L);
	}

	private void record(Map<String, PickupEntry> target, Map<String, PickupEntry> opposite, String key, String displayName, String rarityColor, ItemStack icon, long delta) {
		PickupEntry opposingEntry = opposite.get(key);
		if (opposingEntry != null) opposingEntry.lastUpdateMillis = System.currentTimeMillis();

		PickupEntry entry = target.get(key);
		if (entry != null) {
			entry.amount += delta;
			entry.lastUpdateMillis = System.currentTimeMillis();
		} else {
			target.put(key, new PickupEntry(displayName != null ? displayName : key, rarityColor != null ? rarityColor : "§f",
				icon != null ? icon : ItemStack.EMPTY, delta));
		}
	}

	private static boolean isSkyblockMenu(ItemStack stack) {
		return stack.getHoverName().getString().replaceAll("§.", "").trim().equals("SkyBlock Menu");
	}

	/** Hypixel rarity tiers are always the last lore line ("RARE SWORD" etc., colored) — its color is the
	 *  rarity color, the same trick NEU-style mods use instead of hardcoding a rarity name list. Reads the
	 *  line's real resolved TextColor via Component#visit() rather than scanning getString() for a literal
	 *  '§' character — getString() already strips all styling, so a literal-character scan could never
	 *  find one and this always fell back to the white default (the actual "does not color code" bug). */
	private static String rarityColorOf(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return "§f";
		List<Component> lines = lore.lines();
		for (int i = lines.size() - 1; i >= 0; i--) {
			Component line = lines.get(i);
			if (line.getString().isBlank()) continue;
			Integer rgb = line.visit((style, text) -> {
				if (text.isEmpty() || style.getColor() == null) return java.util.Optional.<Integer>empty();
				return java.util.Optional.of(style.getColor().getValue());
			}, net.minecraft.network.chat.Style.EMPTY).orElse(null);
			return rgb != null ? legacyCodeFor(rgb) : "§f";
		}
		return "§f";
	}

	/** Every real Hypixel rarity color is one of the 16 standard legacy formatting colors — mapping the
	 *  resolved RGB back to its §-code is exact, not an approximation, for every real rarity tier. */
	private static String legacyCodeFor(int rgb) {
		return switch (rgb) {
			case 0x000000 -> "§0";
			case 0x0000AA -> "§1";
			case 0x00AA00 -> "§2";
			case 0x00AAAA -> "§3";
			case 0xAA0000 -> "§4";
			case 0xAA00AA -> "§5";
			case 0xFFAA00 -> "§6";
			case 0xAAAAAA -> "§7";
			case 0x555555 -> "§8";
			case 0x5555FF -> "§9";
			case 0x55FF55 -> "§a";
			case 0x55FFFF -> "§b";
			case 0xFF5555 -> "§c";
			case 0xFF55FF -> "§d";
			case 0xFFFF55 -> "§e";
			case 0xFFFFFF -> "§f";
			default -> "§f";
		};
	}

	private String keyOf(ItemStack stack) {
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		String plainName = stack.getHoverName().getString().replaceAll("§.", "");
		return id + "|" + plainName;
	}

	private record LogRow(String text, ItemStack icon) {}

	// render() below draws these directly (text + icon) instead of going through the plain-text
	// currentLines() pipeline — kept implemented only to satisfy TabWidgetOverlayFeature's contract.
	@Override
	protected List<String> currentLines() {
		return List.of();
	}

	private List<LogRow> buildRows() {
		List<LogRow> rows = new ArrayList<>();
		if (compactLines) {
			Map<String, PickupEntry> addedCopy = new HashMap<>(added);
			for (Map.Entry<String, PickupEntry> entry : addedCopy.entrySet()) {
				PickupEntry addEntry = entry.getValue();
				PickupEntry removeEntry = removed.get(entry.getKey());
				long net = addEntry.amount - (removeEntry != null ? removeEntry.amount : 0);
				if (net > 0) rows.add(new LogRow("§a+" + format(net) + " " + addEntry.rarityColor + addEntry.displayName, addEntry.icon));
				else if (net < 0) rows.add(new LogRow("§c" + format(net) + " " + addEntry.rarityColor + addEntry.displayName, addEntry.icon));
			}
			for (Map.Entry<String, PickupEntry> entry : removed.entrySet()) {
				if (added.containsKey(entry.getKey())) continue;
				PickupEntry removeEntry = entry.getValue();
				rows.add(new LogRow("§c-" + format(removeEntry.amount) + " " + removeEntry.rarityColor + removeEntry.displayName, removeEntry.icon));
			}
		} else {
			for (PickupEntry entry : added.values()) {
				rows.add(new LogRow("§a+" + format(entry.amount) + " " + entry.rarityColor + entry.displayName, entry.icon));
			}
			for (PickupEntry entry : removed.values()) {
				rows.add(new LogRow("§c-" + format(entry.amount) + " " + entry.rarityColor + entry.displayName, entry.icon));
			}
		}
		return rows;
	}

	private static final int ICON_SIZE = 16;

	@Override
	public Size render(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, float scale) {
		List<LogRow> rows = buildRows();
		if (rows.isEmpty()) return new Size(0, 0);

		var font = Minecraft.getInstance().font;
		int lineHeight = Math.round(Math.max(font.lineHeight, ICON_SIZE) * scale);
		int maxWidth = 0;
		int curY = y;
		boolean unscaled = Math.abs(scale - 1f) < 0.01f;
		for (LogRow row : rows) {
			boolean hasIcon = !row.icon().isEmpty();
			int textX = x + (hasIcon ? Math.round((ICON_SIZE + 2) * scale) : 0);
			if (hasIcon) {
				// Skips the pose-matrix wrap entirely at the common default scale (1.0) — wrapping
				// graphics.item() in a manual push/scale/pop was silently throwing here (the same failure
				// mode found and fixed in NeuStyleStorageOverlayFeature's item rendering), which is why
				// this widget never actually appeared: the exception aborted the row loop on the very
				// first icon, every single frame.
				if (unscaled) {
					graphics.item(row.icon(), x, curY);
				} else {
					graphics.pose().pushMatrix();
					graphics.pose().translate(x, curY);
					graphics.pose().scale(scale);
					graphics.pose().translate(-x, -curY);
					graphics.item(row.icon(), x, curY);
					graphics.pose().popMatrix();
				}
			}
			if (unscaled) {
				graphics.text(font, Component.literal(row.text()), textX, curY, 0xFFFFFFFF);
			} else {
				graphics.pose().pushMatrix();
				graphics.pose().translate(textX, curY);
				graphics.pose().scale(scale);
				graphics.pose().translate(-textX, -curY);
				graphics.text(font, Component.literal(row.text()), textX, curY, 0xFFFFFFFF);
				graphics.pose().popMatrix();
			}
			maxWidth = Math.max(maxWidth, textX - x + Math.round(font.width(row.text()) * scale));
			curY += lineHeight;
		}
		return new Size(maxWidth, curY - y);
	}

	private String format(long amount) {
		if (!compactNumbers) return String.valueOf(amount);
		long abs = Math.abs(amount);
		String sign = amount < 0 ? "-" : "";
		if (abs >= 1_000_000_000) return sign + String.format(Locale.ROOT, "%.1fb", abs / 1_000_000_000.0);
		if (abs >= 1_000_000) return sign + String.format(Locale.ROOT, "%.1fm", abs / 1_000_000.0);
		if (abs >= 1_000) return sign + String.format(Locale.ROOT, "%.1fk", abs / 1_000.0);
		return String.valueOf(amount);
	}

	@Override
	public String getDescription() {
		return "Keeps a running log of specific dungeon items you've picked up.";
	}
}
