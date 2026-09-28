package com.cokelord.skyblocksimplified.pv;

import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.api.SkyblockTextureMapApi;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns Hypixel's inventory blobs ({@code {"type":0,"data":"<base64 gzipped NBT>"}}, 1.8-format item NBT)
 * into real, renderable ItemStacks. The SkyBlock id's pre-texture-pack model/skin comes from
 * {@link SkyblockTextureMapApi}; the real head skin in the item's own SkullOwner always wins when present.
 * ExtraAttributes is kept as the stack's CUSTOM_DATA (the same place Hypixel puts it on live items), which
 * is what {@link PvNetworth} reads. Name/lore are the original legacy-coded strings, which the vanilla font
 * still renders with their colors.
 */
public final class PvItems {
	private PvItems() {}

	/** Slot-preserving decode — empty slots come back as {@link ItemStack#EMPTY}. Never null. */
	public static List<ItemStack> decode(JsonObject blob) {
		List<ItemStack> out = new ArrayList<>();
		if (blob == null || !blob.has("data")) return out;
		try {
			byte[] bytes = Base64.getDecoder().decode(blob.get("data").getAsString());
			CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
			ListTag list = root.getListOrEmpty("i");
			for (int i = 0; i < list.size(); i++) out.add(toStack(list.getCompoundOrEmpty(i)));
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.warn("Player Viewer: failed to decode an inventory blob", e);
		}
		return out;
	}

	public static ItemStack toStack(CompoundTag nbt) {
		if (nbt.isEmpty() || !nbt.contains("id")) return ItemStack.EMPTY;
		CompoundTag tag = nbt.getCompoundOrEmpty("tag");
		CompoundTag extra = tag.getCompoundOrEmpty("ExtraAttributes");
		CompoundTag display = tag.getCompoundOrEmpty("display");
		String sbId = extra.getStringOr("id", null);
		String texture = skullTexture(tag);

		String textureKey = sbId;
		if ("PET".equals(sbId) && extra.contains("petInfo")) {
			try {
				JsonObject pet = com.google.gson.JsonParser.parseString(extra.getStringOr("petInfo", "{}")).getAsJsonObject();
				textureKey = pet.get("type").getAsString() + ";" + PvRepo.rarityIndex(pet.get("tier").getAsString());
			} catch (Exception ignored) {}
		}
		Item item = resolveItem(textureKey, sbId, nbt.getShortOr("id", (short) 0), nbt.getShortOr("Damage", (short) 0));
		ItemStack stack = new ItemStack(item, Math.max(1, nbt.getByteOr("Count", (byte) 1)));

		if (item == Items.PLAYER_HEAD) {
			if (texture == null) {
				Map<String, SkyblockTextureMapApi.Entry> map = SkyblockTextureMapApi.get();
				SkyblockTextureMapApi.Entry entry = map != null && textureKey != null ? map.get(textureKey.replace(':', '-')) : null;
				if (entry != null && entry.profile() != null) stack.set(DataComponents.PROFILE, entry.profile());
			} else {
				stack.set(DataComponents.PROFILE, profile(texture));
			}
		}
		String name = display.getStringOr("Name", null);
		if (name != null) stack.set(DataComponents.CUSTOM_NAME, legacy(name));
		ListTag loreTag = display.getListOrEmpty("Lore");
		if (!loreTag.isEmpty()) {
			List<Component> lore = new ArrayList<>(loreTag.size());
			for (int i = 0; i < loreTag.size(); i++) lore.add(legacy(loreTag.getStringOr(i, "")));
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		if (display.contains("color")) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(display.getIntOr("color", 0)));
		if (!extra.isEmpty()) stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra));
		if (!extra.getCompoundOrEmpty("enchantments").isEmpty() || tag.contains("ench")) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		// The lore already carries every stat Hypixel wants shown; vanilla's own attack-damage/"Dyed" lines
		// would just duplicate or contradict it.
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(false, new LinkedHashSet<>(List.of(
			DataComponents.ATTRIBUTE_MODIFIERS, DataComponents.DYED_COLOR, DataComponents.ENCHANTMENTS, DataComponents.UNBREAKABLE))));
		return stack;
	}

	private static final java.util.Map<String, ItemStack> ICONS = new java.util.concurrent.ConcurrentHashMap<>();

	/** Display-only icon for a SkyBlock id ("DIAMOND", "INK_SACK:3", "GOLDEN_DRAGON;4") or a vanilla id
	 *  ("minecraft:golden_hoe"). Cached once the texture map has loaded. */
	public static ItemStack icon(String id) {
		ItemStack cached = ICONS.get(id);
		if (cached != null) return cached;
		ItemStack stack;
		if (id.startsWith("minecraft:")) {
			Identifier vanilla = Identifier.tryParse(id);
			stack = new ItemStack(vanilla != null && BuiltInRegistries.ITEM.containsKey(vanilla) ? BuiltInRegistries.ITEM.getValue(vanilla) : Items.PAPER);
		} else {
			String base = id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
			short damage = 0;
			try { if (id.contains(":")) damage = Short.parseShort(id.substring(id.indexOf(':') + 1)); } catch (NumberFormatException ignored) {}
			stack = new ItemStack(resolveItem(id, base, (short) 0, damage));
			if (stack.is(Items.PLAYER_HEAD)) {
				Map<String, SkyblockTextureMapApi.Entry> map = SkyblockTextureMapApi.get();
				SkyblockTextureMapApi.Entry entry = map != null ? map.get(id.replace(':', '-')) : null;
				if (entry != null && entry.profile() != null) stack.set(DataComponents.PROFILE, entry.profile());
			}
		}
		if (SkyblockTextureMapApi.get() != null) ICONS.put(id, stack);
		return stack;
	}

	/** Minecraft renders a literal string's legacy § codes itself; italic is forced off because a custom
	 *  name/lore line is otherwise italic by default. */
	/**
	 * Converts a legacy "§"-coded string into real styled text runs. The font would draw the raw codes fine, but
	 * anything that reads colors off {@link Style} (Item Rarity Background's name check, among others) sees an
	 * uncolored string — which is why rarity backgrounds never showed in the viewer.
	 */
	public static Component legacy(String text) {
		text = glyphs(text);
		net.minecraft.network.chat.MutableComponent out = Component.empty().withStyle(Style.EMPTY.withItalic(false));
		Style style = Style.EMPTY.withItalic(false);
		StringBuilder run = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '§' && i + 1 < text.length()) {
				net.minecraft.ChatFormatting fmt = net.minecraft.ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
				if (fmt != null) {
					if (run.length() > 0) {
						out.append(Component.literal(run.toString()).withStyle(style));
						run.setLength(0);
					}
					style = fmt.ordinal() < 16 || fmt == net.minecraft.ChatFormatting.RESET
						? Style.EMPTY.withItalic(false).withColor(fmt == net.minecraft.ChatFormatting.RESET ? null : fmt)
						: style.applyLegacyFormat(fmt);
					i++;
					continue;
				}
			}
			run.append(ch);
		}
		if (run.length() > 0) out.append(Component.literal(run.toString()).withStyle(style));
		return out;
	}

	/**
	 * Hypixel now writes stat/icon symbols as private-use characters (U+E0xx) that only its own resource pack
	 * can draw, so API item text shows blanks without it. Mapped back to the classic SkyBlock symbols:
	 * <ul>
	 *   <li>Confirmed pairs, from SkyBlockAPI's own regexes (e.g. {@code [❤\uE010]}): E003 ✎, E008 ❈, E010 ❤,
	 *       E017 ʬ, E018/E07F ൠ, E01A ✯, E01B ❍, E020 ф, E067 ⏣.</li>
	 *   <li>Unambiguous from the lore text around them in real API data (e.g. "+1 _ Crit Damage"): E007 ☠,
	 *       E02C ☣, E00B ⫽, E011 ❣, E022 ✦, E050 ❁, E019 ൠ, E023 ∮, E025 ⛃, E05B ☘, E02A ♔, E014 ☄, E028 ♨,
	 *       and E068 ⚚ (fragged, per user).</li>
	 * </ul>
	 * Anything else is left untouched rather than guessed.
	 */
	private static final java.util.Map<Character, Character> GLYPHS = java.util.Map.ofEntries(
		java.util.Map.entry('\uE003', '✎'), java.util.Map.entry('\uE008', '❈'), java.util.Map.entry('\uE010', '❤'),
		java.util.Map.entry('\uE017', 'ʬ'), java.util.Map.entry('\uE018', 'ൠ'), java.util.Map.entry('\uE07F', 'ൠ'),
		java.util.Map.entry('\uE01A', '✯'), java.util.Map.entry('\uE01B', '❍'), java.util.Map.entry('\uE020', 'ф'),
		java.util.Map.entry('\uE067', '⏣'), java.util.Map.entry('\uE007', '☠'), java.util.Map.entry('\uE02C', '☣'),
		java.util.Map.entry('\uE00B', '⫽'), java.util.Map.entry('\uE011', '❣'), java.util.Map.entry('\uE022', '✦'),
		java.util.Map.entry('\uE050', '❁'), java.util.Map.entry('\uE019', 'ൠ'), java.util.Map.entry('\uE023', '∮'),
		java.util.Map.entry('\uE025', '⛃'), java.util.Map.entry('\uE05B', '☘'), java.util.Map.entry('\uE02A', '♔'),
		java.util.Map.entry('\uE014', '☄'), java.util.Map.entry('\uE028', '♨'), java.util.Map.entry('\uE068', '⚚'),
		// Gemstone slot/stat icons, pinned down from a Power Relic (one slot of every gem, in Hypixel's
		// gemstone_slots order) and each gem's own stat line: Jade/Peridot/Citrine fortunes ☘, Amber mining
		// speed ⸕, Topaz pristine ✧, Jasper strength ❁, Opal true defense ❂, Aquamarine fishing speed ☂.
		java.util.Map.entry('\uE053', '☘'), java.util.Map.entry('\uE015', '⸕'), java.util.Map.entry('\uE01C', '✧'),
		java.util.Map.entry('\uE00D', '❁'), java.util.Map.entry('\uE027', '❂'), java.util.Map.entry('\uE051', '☘'),
		java.util.Map.entry('\uE054', '☘'), java.util.Map.entry('\uE00C', '☂'));

	static String glyphs(String text) {
		// With Hypixel's own icon font cached and loaded (see HypixelFontPack), the real icons render as-is.
		// Mapping only matters while Remove Skyblock Texture Pack is on and the icon font isn't cached yet;
		// otherwise the real icons render as-is (Hypixel's own pack, or the cached font from HypixelFontPack).
		if (!com.cokelord.skyblocksimplified.feature.impl.RemoveSkyblockTexturePackFeature.isFontPackWanted()
				|| com.cokelord.skyblocksimplified.util.HypixelFontPack.isAvailable()) return text;
		boolean any = false;
		for (int i = 0; i < text.length() && !any; i++) any = text.charAt(i) >= '\uE000' && text.charAt(i) <= '\uF8FF';
		if (!any) return text;
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			sb.append(GLYPHS.getOrDefault(ch, ch));
		}
		return sb.toString();
	}

	public static ResolvableProfile profile(String texture) {
		com.google.common.collect.Multimap<String, Property> raw = com.google.common.collect.HashMultimap.create();
		raw.put("textures", new Property("textures", texture));
		UUID id = UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return ResolvableProfile.createResolved(new GameProfile(id, "PvItem", new PropertyMap(raw)));
	}

	private static String skullTexture(CompoundTag tag) {
		ListTag textures = tag.getCompoundOrEmpty("SkullOwner").getCompoundOrEmpty("Properties").getListOrEmpty("textures");
		if (textures.isEmpty()) return null;
		String value = textures.getCompoundOrEmpty(0).getStringOr("Value", null);
		return value == null || value.isEmpty() ? null : value;
	}

	/** Pre-pack model from the texture map, then Hypixel's own item "material", then the 1.8 numeric id,
	 *  then paper — so an item always renders as something sensible rather than the missing-model cube. */
	static Item resolveItem(String textureKey, String sbId, short legacyId, short damage) {
		Map<String, SkyblockTextureMapApi.Entry> map = SkyblockTextureMapApi.get();
		if (map != null && textureKey != null) {
			SkyblockTextureMapApi.Entry entry = map.get(textureKey.replace(':', '-'));
			if (entry != null) return BuiltInRegistries.ITEM.getValue(entry.model);
		}
		if (sbId != null) {
			SkyblockItemRepo.ItemInfo info = SkyblockItemRepo.getItem(sbId);
			if (info != null && info.material() != null) {
				Item fromMaterial = fromLegacyName(info.material(), damage);
				if (fromMaterial != null) return fromMaterial;
			}
		}
		Item legacy = LegacyItemIds.get(legacyId, damage);
		return legacy != null ? legacy : Items.PAPER;
	}

	static Item fromLegacyName(String material, short damage) {
		String name = material.toLowerCase(Locale.ROOT);
		Item mapped = LegacyItemIds.byName(name, damage);
		if (mapped != null) return mapped;
		Identifier id = Identifier.tryParse(name);
		return id != null && BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : null;
	}
}
