package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.DianaMobs;
import com.cokelord.skyblocksimplified.diana.DianaState;
import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.List;

public class MythosMobHpFeature extends DianaHudFeature {
	private static MythosMobHpFeature instance;

	public MythosMobHpFeature() {
		super("diana_mob_hp", "Mob HP Display",
			"Lists the name and health of every mythological mob near you, one per line.", 0.4f, 0.55f);
		defInt("range", 20);
		instance = this;
	}

	public static MythosMobHpFeature get() { return instance; }

	@Override
	protected List<String> lines() { return DianaMobs.hpLines(); }

	@Override
	protected List<String> exampleLines() {
		return List.of("§2Exalted Minos Inquisitor §a31.2M§f/§a40M§c❤", "§2Stalwart Minotaur §a3.1M§f/§a4M§c❤");
	}

	@Override
	protected boolean shouldShow() { return DianaState.inHub(); }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(slider("Range", "range", 5, 60, " blocks", "Only mobs within this distance are listed."));
	}
}
