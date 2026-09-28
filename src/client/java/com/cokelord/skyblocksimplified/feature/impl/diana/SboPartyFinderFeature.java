package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.pf.PartyFinderScreen;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Settings + saved form/filter state for the SBO Party Finder (/sbopf). */
public class SboPartyFinderFeature extends DianaFeature {
	private static SboPartyFinderFeature instance;

	public SboPartyFinderFeature() {
		super("diana_sbo_party_finder", "SBO Party Finder", false,
			"SBO's Diana party finder in this mod's style (/sbopf). Uses SBO's server, so it works together with SBO users. "
				+ "Get a key from SBO's Discord bot (same key as SBO's /sboKey), then press Paste.");
		defString("key", "");
		defBool("autoInvite", false);
		defBool("autoRequeue", false);
		for (String page : new String[]{"diana_", "custom_"}) {
			defString(page + "lvl", "");
			defString(page + "note", "");
			defBool(page + "eman9", false);
			defBool(page + "filterEman9", false);
			defBool(page + "filterCanJoin", false);
		}
		defString("diana_kills", "");
		defBool("diana_looting5", false);
		defBool("diana_filterLooting5", false);
		defString("custom_mp", "");
		defString("custom_size", "");
		instance = this;
	}

	public static SboPartyFinderFeature get() { return instance; }

	// Official SBO Discord invite, from SBO's own Settings.kt / PartyFinderGUI.kt.
	private static final String SBO_DISCORD = "https://discord.gg/QvM6b9jsJD";

	public static void openDiscord() {
		net.minecraft.util.Util.getPlatform().openUri(SBO_DISCORD);
	}

	public void pasteKey() {
		String clip = Minecraft.getInstance().keyboardHandler.getClipboard().trim();
		if (!clip.startsWith("sbo") || clip.contains(" ") || clip.length() > 128) {
			com.cokelord.skyblocksimplified.util.ChatText.clientMessage("§6[SBO] §cClipboard doesn't contain an SBO key (they start with \"sbo\").");
			return;
		}
		setString("key", clip);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
		com.cokelord.skyblocksimplified.util.ChatText.clientMessage("§6[SBO] §aKey has been set.");
	}

	@Override
	public boolean isToggleable() { return false; }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			new SettingRow.Text("SBO Key", "sbo_pf_key", () -> string("key"), v -> setString("key", v.trim()), "sbo..."),
			new SettingRow.Button("Get Key (opens SBO Discord)", SboPartyFinderFeature::openDiscord),
			new SettingRow.Button("Paste Key From Clipboard", this::pasteKey),
			toggle("Auto Invite", "autoInvite", "Automatically invites players who send a join request and meet your party's requirements."),
			toggle("Auto Requeue", "autoRequeue", "Requeues your party with the same requirements when someone leaves."),
			new SettingRow.Button("Open Party Finder", () -> {
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> { if (mc.level != null) mc.gui.setScreen(new PartyFinderScreen()); });
			}));
	}
}
