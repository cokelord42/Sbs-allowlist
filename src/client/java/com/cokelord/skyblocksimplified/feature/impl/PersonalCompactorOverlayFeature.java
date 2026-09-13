package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows the Personal Compactor/Deletor's stored contents as a real icon grid (shaped like the compactor's
 * own tier-sized layout) inside the item's own vanilla tooltip, exactly like the Bundle item's own
 * contents-preview tooltip does. Two cooperating pieces: appendCompactorLines() below adds the plain
 * "Personal Compactor: Enabled/Disabled" status line via Fabric's ItemTooltipCallback (confirmed via user
 * testing to be the one detection path that reliably fires for these items — see tooltipImageFor()'s own
 * doc comment for why), and tooltipImageFor() + ItemStackTooltipImageMixin/ClientTooltipComponentCreateMixin
 * supply the actual icon grid through vanilla's real TooltipComponent/ClientTooltipComponent mechanism
 * (confirmed via javap against the real 26.2 classes — the same mechanism backing ClientBundleTooltip).
 * This replaced an earlier hand-drawn floating panel (PreTooltipRenderRegistry-based) that never actually
 * fired for real compactors — see prior HANDOFF entries — so that code path was removed rather than left
 * dead. Internal name regex + tier -> slot-count map confirmed against SkyHanni's PersonalCompactorOverlay.kt.
 */
public class PersonalCompactorOverlayFeature extends Feature {
	private static final Pattern COMPACTOR_ID = Pattern.compile("PERSONAL_(?<type>[^_]+)_(?<tier>\\d+)");
	private static final Map<String, Integer> TIER_SLOTS = Map.of("4000", 1, "5000", 3, "6000", 7, "7000", 12);
	static final int SLOT_PIXELS = 18;
	// 7, not a round number, per user request for a 2-row-max grid — matches SkyHanni's own
	// PersonalCompactorOverlay.kt MAX_ITEMS_PER_ROW exactly, which keeps even the biggest 12-slot tier
	// at 2 rows (7 + 5) instead of 3.
	static final int COLUMNS = 7;

	public PersonalCompactorOverlayFeature() {
		super("personal_compactor_overlay", "Personal Compactor Overlay", FeatureCategory.INVENTORY, false);

		// Real gap found (per user request — "can you port skyhannis? This is becoming a bigger issues"):
		// the previous hand-drawn panel hooked PreTooltipRenderRegistry, which only ever fires from
		// AbstractContainerScreenTooltipMixin (scoped to AbstractContainerScreen specifically) — the real
		// SkyHanni source (PersonalCompactorOverlay.kt, in scratchpad/refsrc) instead hooks ToolTipTextEvent,
		// the Forge/Architectury equivalent of Fabric's ItemTooltipCallback: a strictly more general hook
		// fired from Item's own getTooltipLines() pipeline itself, not gated to any particular Screen
		// subtype. User confirmed this fixed detection ("Ok the overlay works").
		net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!isEnabled()) return;
			try {
				appendCompactorLines(stack, lines);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Personal Compactor Overlay tooltip-line render failed, skipping this stack", e);
			}
		});
	}

	/** Adds just the plain-text status line — the actual item contents now render as a real icon grid via
	 *  tooltipImageFor()/ItemStackTooltipImageMixin instead of as text lines here (per user request: "it
	 *  should usually display the amount of slots the tier has as the tooltip, and then show the items as
	 *  images/sprites in those slots"). */
	private void appendCompactorLines(ItemStack stack, java.util.List<Component> lines) {
		if (stack.isEmpty()) return;
		String id = SkyblockNbtUtils.getItemId(stack);
		if (id == null) return;
		Matcher matcher = COMPACTOR_ID.matcher(id);
		if (!matcher.matches()) return;
		if (!TIER_SLOTS.containsKey(matcher.group("tier"))) return;

		boolean isDeletor = "DELETOR".equals(matcher.group("type"));
		boolean active = SkyblockNbtUtils.getAttributeByte(stack, "PERSONAL_DELETOR_ACTIVE") == 1;


		lines.add(Component.literal("§7" + (isDeletor ? "Personal Deletor: " : "Personal Compactor: ")
			+ (active ? "§aEnabled" : "§cDisabled")));
	}

	/** Called from ItemStackTooltipImageMixin (ItemStack.getTooltipImage()) for every item hovered anywhere
	 *  in the game — matches the same PERSONAL_&lt;type&gt;_&lt;tier&gt; id used by appendCompactorLines()
	 *  above, and is queried by vanilla from the exact same call sites that already build this item's plain
	 *  tooltip lines (confirmed via javap: both GuiGraphicsExtractor.setTooltipForNextFrame(Font, ItemStack,
	 *  int, int) and AbstractContainerScreen's own tooltip-extraction method fetch the text lines and
	 *  stack.getTooltipImage() together, back to back) — so this fires wherever ItemTooltipCallback already
	 *  proved reliable for these items, without depending on AbstractContainerScreen specifically. */
	public static Optional<TooltipComponent> tooltipImageFor(ItemStack stack) {
		com.cokelord.skyblocksimplified.feature.Feature feature =
			com.cokelord.skyblocksimplified.feature.FeatureRegistry.get("personal_compactor_overlay");
		if (feature == null || !feature.isEnabled()) return Optional.empty();
		if (stack.isEmpty()) return Optional.empty();
		String id = SkyblockNbtUtils.getItemId(stack);
		if (id == null) return Optional.empty();
		Matcher matcher = COMPACTOR_ID.matcher(id);
		if (!matcher.matches()) return Optional.empty();
		Integer slotCount = TIER_SLOTS.get(matcher.group("tier"));
		if (slotCount == null) return Optional.empty();

		boolean isDeletor = "DELETOR".equals(matcher.group("type"));
		boolean active = SkyblockNbtUtils.getAttributeByte(stack, "PERSONAL_DELETOR_ACTIVE") == 1;
		String attributePrefix = isDeletor ? "personal_deletor_" : "personal_compact_";

		// Real bug found (confirmed against SkyblockAddons' own bundled containers.json): Hypixel's real
		// "personal_compact_N" ExtraAttributes keys are ZERO-indexed ("personal_compact_0".."_11" for the
		// 12-slot tier).
		String[] slotItems = new String[slotCount];
		for (int slot = 0; slot < slotCount; slot++) {
			slotItems[slot] = SkyblockNbtUtils.getAttributeString(stack, attributePrefix + slot);
		}
		return Optional.of(new PersonalCompactorTooltipData(isDeletor, active, slotCount, slotItems));
	}

	@Override
	public String getSubcategory() {
		return "Inventory";
	}

	public record IconResult(ItemStack icon, boolean matchedVanilla) {}

	// A handful of well-known legacy Hypixel/Bukkit-era SkyBlock ids that never matched their vanilla
	// counterpart 1:1 (predating the modern item id convention most other SkyBlock ids already follow) —
	// common enough in what a Personal Compactor actually stores (farming/mining drops) to be worth a
	// direct alias rather than leaving them to fall through to the initial-letter badge.
	private static final Map<String, String> LEGACY_ID_ALIASES = Map.of(
		"NETHER_STALK", "nether_wart",
		"SULPHUR", "gunpowder",
		"CARROT_ITEM", "carrot",
		"POTATO_ITEM", "potato"
	);

	// Real item icons now come from SkyblockItemIcons (Hypixel's own item-resource data — real skin
	// textures for reskinned-head items, real vanilla materials for everything else), which this compactor
	// grid ids into directly first. The legacy-alias/prefix-stripped vanilla-id guess below is only the
	// SECOND fallback, for ids too old/obscure to have a repo entry at all. Anything that still doesn't
	// resolve falls back to a plain player head, with an initial-letter badge (see ClientPersonalCompactorTooltip)
	// so unresolved items are at least distinguishable from each other instead of all looking identical.
	static IconResult iconFor(String skyblockId) {
		ItemStack realIcon = com.cokelord.skyblocksimplified.util.SkyblockItemIcons.getIcon(skyblockId);
		if (realIcon != null) return new IconResult(realIcon, true);

		String base = skyblockId.startsWith("ENCHANTED_") ? skyblockId.substring("ENCHANTED_".length()) : skyblockId;
		base = LEGACY_ID_ALIASES.getOrDefault(base, base);
		Identifier vanillaId = Identifier.tryParse("minecraft:" + base.toLowerCase(java.util.Locale.ROOT));
		Item item = vanillaId != null ? BuiltInRegistries.ITEM.getOptional(vanillaId).orElse(null) : null;
		if (item == null || item == Items.AIR) return new IconResult(new ItemStack(Items.PLAYER_HEAD), false);
		return new IconResult(new ItemStack(item), true);
	}

	// Two letters, not one, per user request — word initials ("Enchanted Diamond" -> "ED") read as a more
	// useful abbreviation than the first two characters of a single word ("En") would, so this prefers
	// the first letter of each of the first two words and only falls back to substring(0, 2) for a
	// single-word name.
	static String initialFor(String skyblockId) {
		SkyblockItemRepo.ItemInfo info = SkyblockItemRepo.getItem(skyblockId);
		String name = info != null ? info.name() : skyblockId;
		String plain = name.replaceAll("§.", "").trim();
		if (plain.isEmpty()) return null;
		String[] words = plain.split("\\s+");
		if (words.length >= 2) {
			return ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase(java.util.Locale.ROOT);
		}
		return plain.substring(0, Math.min(2, plain.length())).toUpperCase(java.util.Locale.ROOT);
	}

	@Override
	public String getDescription() {
		return "Shows the Personal Compactor's stored contents as an icon grid right inside its own tooltip.";
	}
}
