package com.cokelord.skyblocksimplified.feature.impl.m7;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaFeature;

import java.util.List;

/**
 * M7 "Dragon Priority" — Odin's DragonPriority: when two dragons spawn together, picks which one YOU go to
 * from your class, the party's Power blessing (+2.5 with a Time blessing) and the split thresholds. Paul's
 * Benediction perk (blessings +25%) is detected automatically instead of Odin's manual "Paul Buff" toggle.
 * Needs the Dragons module on (it runs the dragon tracking).
 */
public class DragonPriorityFeature extends DianaFeature {
	private static final String[] SOLO = {"Tank", "Healer"};

	public DragonPriorityFeature() {
		super("m7_dragon_priority", "Dragon Priority", FeatureCategory.COMBAT, false,
			"Picks your M7 priority dragon when two spawn at once (Odin's split logic): by class, Power blessing and the split thresholds. "
				+ "Paul's Benediction perk is detected automatically. Requires the Dragons module.");
		defInt("normalPower", 0);
		defInt("easyPower", 0);
		defInt("soloDebuff", 0);
		defBool("soloDebuffOnAll", false);
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			slider("Normal Power", "normalPower", 0, 32, "", "Power needed to split."),
			slider("Easy Power", "easyPower", 0, 32, "", "Power needed when it's Purple and another dragon."),
			new SettingRow.Cycle("Purple Solo Debuff", () -> SOLO[Math.floorMod(integer("soloDebuff"), 2)],
				() -> setInt("soloDebuff", (integer("soloDebuff") + 1) % 2),
				"The class that solo debuffs purple; the other class helps B/M."),
			toggle("Solo Debuff on All Splits", "soloDebuffOnAll", "Same as Purple Solo Debuff but for every split (A only gets 1 debuff)."));
	}
}
