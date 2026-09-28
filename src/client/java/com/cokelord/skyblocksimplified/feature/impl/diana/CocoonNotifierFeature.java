package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.List;

public class CocoonNotifierFeature extends DianaFeature {
	private static CocoonNotifierFeature instance;

	public CocoonNotifierFeature() {
		super("diana_cocoon_notifier", "Cocoon Notifier", false, "Notifies you when you cocoon a rare Diana mob.");
		defBool("party", true);
		defBool("title", true);
		instance = this;
	}

	public static CocoonNotifierFeature get() { return instance; }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			toggle("Send Message To Party", "party", "Sends \"Cocooned a <mob>!\" to party chat."),
			toggle("Title", "title", "Shows a COCOON! title with the mob's name."));
	}
}
