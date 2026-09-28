package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.RareMobs;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.util.ChatText;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public class RareMobShareFeature extends DianaFeature {
	private static RareMobShareFeature instance;

	public RareMobShareFeature() {
		super("diana_share_rare_mobs", "Share Rare Mobs", false,
			"Sends your coordinates to party chat when you dig up a rare mob (same format as SBO, so SBO users get a waypoint too).");
		for (RareMobs.Mob mob : RareMobs.Mob.values()) defBool(mob.key(), true);
		instance = this;
	}

	/** Called when a rare mob is dug out (DianaTracker). Format is SBO's exactly: "x: X, y: Y, z: Z | Mob". */
	public static void onRareSpawn(String mobName) {
		if (instance == null || !instance.isEnabled()) return;
		RareMobs.Mob mob = RareMobs.Mob.fromName(mobName);
		if (mob == null || !instance.bool(mob.key())) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		long x = Math.round(mc.player.getX()), y = Math.round(mc.player.getY()) - 1, z = Math.round(mc.player.getZ());
		ChatText.partyChat("x: " + x + ", y: " + y + ", z: " + z + " | " + mob.displayName);
	}

	@Override
	public List<SettingRow> getSettingRows() {
		List<SettingRow> mobs = new ArrayList<>();
		for (RareMobs.Mob mob : RareMobs.Mob.values()) mobs.add(toggle(mob.displayName, mob.key(), "Share when you dig up a " + mob.displayName + "."));
		return List.of(new SettingRow.Group("Mobs To Share", "mobs", mobs));
	}
}
