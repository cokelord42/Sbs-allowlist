package com.cokelord.skyblocksimplified.combat;

import com.cokelord.skyblocksimplified.util.SkullTextureUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

/**
 * Identifies "Flare" ArmorStands (the SOS/Alert/Warning skull markers players drop as distress signals)
 * by their equipped head's skin texture — ported from SkyHanni's FlareDisplay.kt/SkullTextureHolder.
 * The three base64 skin-texture values are SkyHanni's own confirmed, public repo data (fetched from
 * github.com/hannibal002/SkyHanni-REPO's Skulls.json constant, the same data SkullTextureHolder loads
 * at runtime), not guessed — SkyHanni compares the exact same full property value.
 */
public final class FlareDetector {
	private FlareDetector() {}

	public enum FlareType { WARNING, ALERT, SOS }

	private static final String WARNING_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTY2MjY4Mjg0NTU4NiwKICAicHJvZmlsZUlkIiA6ICIwODFiZTAxZmZlMmU0ODMyODI3MDIwMjBlNmI1M2ExNyIsCiAgInByb2ZpbGVOYW1lIiA6ICJMeXJpY1BsYXRlMjUyNDIiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjJlMmJmNmMxZWMzMzAyNDc5MjdiYTYzNDc5ZTU4NzJhYzY2YjA2OTAzYzg2YzgyYjUyZGFjOWYxYzk3MTQ1OCIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9";
	private static final String ALERT_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTcxOTg1MDQzMTY4MywKICAicHJvZmlsZUlkIiA6ICJmODg2ZDI3YjhjNzU0NjAyODYyYTM1M2NlYmYwZTgwZiIsCiAgInByb2ZpbGVOYW1lIiA6ICJOb2JpbkdaIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzhkNjc4ZmMwZTM1MzZiOTRkOTBhMDlmNzE1Nzg4NDMxYzYzNzBjMTk4M2NkYWZmZDQ1MTAxZmZlOWEwMzY2NmYiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";
	private static final String SOS_TEXTURE =
		"ewogICJ0aW1lc3RhbXAiIDogMTY2MjY4Mjc3NjUxNiwKICAicHJvZmlsZUlkIiA6ICI4YjgyM2E1YmU0Njk0YjhiOTE0NmE5MWRhMjk4ZTViNSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTZXBoaXRpcyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yYmZlOWQ0NDgwZTU1NTQ1MzhlYTUzNTQzZjgzODhmYzQ5NzhiOTVhNzc2ZGE1ZjZlZWZkYWI0YmQ0YTZlZDA2IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=";

	public static FlareType getFlareType(ArmorStand entity) {
		String texture = getHeadTexture(entity);
		if (texture == null) return null;
		if (texture.equals(WARNING_TEXTURE)) return FlareType.WARNING;
		if (texture.equals(ALERT_TEXTURE)) return FlareType.ALERT;
		if (texture.equals(SOS_TEXTURE)) return FlareType.SOS;
		return null;
	}

	private static String getHeadTexture(ArmorStand entity) {
		ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
		return SkullTextureUtil.fromItem(head);
	}
}
