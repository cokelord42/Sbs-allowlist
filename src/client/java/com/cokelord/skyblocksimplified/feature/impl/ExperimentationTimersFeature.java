package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.config.ConfigManager;
import com.cokelord.skyblocksimplified.debug.DebugLog;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.cokelord.skyblocksimplified.util.IslandGate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request ("Detect the Experimentation Table gui name, detect slot 22 accounted for the -1. It has
 * a lore line that goes like 'Charges: 1/3' up until 3. If its not 3, then detect the next line 'Next charge
 * in: 49m 03s'. It should then add that to the time currently and set a timer at that time. When the time
 * hits, or when the user logs in after the timer is up, it should notify them that they have an experiment
 * charge."): reads slot 22 (the real slot Hypixel's Experimentation Table GUI keeps the charge display on)
 * every tick the GUI is open, computes a real absolute notification time from the "Next charge in:" lore
 * line, and persists it so the notification still fires even across a game restart.
 *
 * <p>Per user follow-up ("Allow it to be notify only at 3 aswell... One charge comes every 24 hours, unless
 * its at 3/3 then the 24 hour timer starts when they use a charge. So if its at 0 charges, the next timer
 * would be 1 charge, and then you add 48 hours to that. If its at 1 charge, the next timer would be 2, so add
 * 24 hours. If its at 2 its just the timer in the lore"): with "Notify Only at 3" on, the real lore-given
 * "Next charge in" countdown (which only ever gets the table to ONE more charge than its current count) gets
 * extra 24-hour blocks added on top for however many additional charges are still needed to reach 3/3 —
 * {@code (2 - currentCharges) * 24h}, which works out to exactly the 48h/24h/0h the user gave for 0/1/2
 * charges respectively.
 */
public class ExperimentationTimersFeature extends Feature {
	private static final Pattern CHARGES_LINE = Pattern.compile("Charges: (\\d)/3");
	private static final Pattern NEXT_CHARGE_LINE = Pattern.compile("Next charge in: (?:(\\d+)h ?)?(?:(\\d+)m ?)?(\\d+)s");
	// Per user spec ("detect slot 22 accounted for the -1") — the user's own number already accounts for the
	// 1-indexed-in-GUI vs 0-indexed-in-code offset, so this is used directly as a real Slot list index.
	private static final int SLOT_INDEX = 22;

	public static final String[] SOUND_IDS = {
		"block.note_block.pling", "block.note_block.harp", "entity.experience_orb.pickup", "block.note_block.bell"
	};
	public static final String[] SOUND_LABELS = {"Pling", "Note Block Harp", "Orb Pickup", "Bell"};

	private boolean notifyOnlyAtThree = false;
	private boolean notifyChat = false;
	private boolean notifySound = false;
	private boolean notifyTitle = false;
	// Per user request ("Add the sound support like we have (imports, pitch, volume, sound) to the new timer
	// modules"): delegates built-in/custom sound choice + volume/pitch/deadspace entirely to the same shared
	// CustomSoundOption every other sound-picking module in the mod uses, replacing the old hardcoded
	// SoundEvents.NOTE_BLOCK_PLING at a fixed 1f/1f.
	private final CustomSoundOption sound = new CustomSoundOption(SOUND_IDS, SOUND_LABELS);
	// Persisted across restarts (per user request — see class doc comment). 0 means no pending notification.
	private long notifyAtMillis = 0L;

	private static boolean listenersRegistered = false;
	private static ExperimentationTimersFeature instance;

	public ExperimentationTimersFeature() {
		super("experimentation_timers", "Experimentation Timers", FeatureCategory.ENCHANTING, false);
		instance = this;
	}

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (instance != null && instance.isEnabled()) instance.tick();
		});
		// Per user request ("Make sure the timer goes off if its past. If its past the timer but the user
		// hasnt used the mod since the timer was on... it should notify on the next hypixel join"): the
		// persisted notifyAtMillis already survives a restart, and tick() below fires the instant mc.player
		// is non-null again — but that alone would also fire on a plain singleplayer world. Gate the actual
		// fire on IslandGate.isOnHypixel() (checked in tick()) and log the missed-timer detection here, right
		// on join, so it's independently visible in the debug log even before the next tick runs.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (instance != null && instance.notifyAtMillis != 0L && System.currentTimeMillis() >= instance.notifyAtMillis) {
				DebugLog.detected("Experimentation Timers: missed notification (target already passed while offline), "
					+ "will fire on next Hypixel join");
			}
		});
	}

	private void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen && screen.getTitle().getString().contains("Experimentation Table")) {
			scanTable(screen);
		}
		if (notifyAtMillis != 0L && mc.player != null && IslandGate.isOnHypixel() && System.currentTimeMillis() >= notifyAtMillis) {
			fireNotification();
			notifyAtMillis = 0L;
			ConfigManager.save();
		}
	}

	// Re-reads the real live GUI state every tick it's open rather than once on open — self-heals from any
	// drift (a charge used since the table was last checked, etc.) against the server's own current truth
	// instead of trusting a snapshot that can go stale the moment it's taken.
	private void scanTable(AbstractContainerScreen<?> screen) {
		var slots = screen.getMenu().slots;
		if (SLOT_INDEX >= slots.size()) return;
		Slot slot = slots.get(SLOT_INDEX);
		ItemStack stack = slot.getItem();
		if (stack.isEmpty()) return;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return;

		Integer charges = null;
		Long nextChargeSeconds = null;
		for (Component line : lore.lines()) {
			String text = line.getString();
			Matcher chargesMatcher = CHARGES_LINE.matcher(text);
			if (chargesMatcher.find()) {
				charges = Integer.parseInt(chargesMatcher.group(1));
				continue;
			}
			Matcher nextMatcher = NEXT_CHARGE_LINE.matcher(text);
			if (nextMatcher.find()) {
				int hours = nextMatcher.group(1) != null ? Integer.parseInt(nextMatcher.group(1)) : 0;
				int minutes = nextMatcher.group(2) != null ? Integer.parseInt(nextMatcher.group(2)) : 0;
				int seconds = Integer.parseInt(nextMatcher.group(3));
				nextChargeSeconds = hours * 3600L + minutes * 60L + seconds;
			}
		}
		if (charges == null) return;

		// Already full (or no countdown line found, which only happens at 3/3) — nothing left to wait for.
		if (charges >= 3 || nextChargeSeconds == null) {
			if (notifyAtMillis != 0L) {
				notifyAtMillis = 0L;
				ConfigManager.save();
			}
			return;
		}

		long extraSeconds = notifyOnlyAtThree ? (2 - charges) * 24L * 3600L : 0L;
		long newTarget = System.currentTimeMillis() + (nextChargeSeconds + extraSeconds) * 1000L;
		if (notifyAtMillis != newTarget) {
			notifyAtMillis = newTarget;
			ConfigManager.save();
			// Per user request ("add a debug message showing the time when it detects a timer, for the
			// experimentation table so i can make sure it actually catches the timer"): only logs on an
			// actual new-target detection (not every tick the GUI happens to be open) so it doesn't spam.
			DebugLog.detected("Experimentation Timers: detected timer, charges=" + charges + "/3, notifying at "
				+ java.time.Instant.ofEpochMilli(newTarget));
		}
	}

	private void fireNotification() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String message = notifyOnlyAtThree
			? "§bExperimentation Table is fully charged §f(3/3)§b!"
			: "§bExperimentation Table has a charge ready!";
		if (notifyChat) mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
		if (notifySound) sound.play();
		if (notifyTitle) {
			mc.gui.hud.setTimes(5, 40, 10);
			mc.gui.hud.setTitle(Component.literal(message));
		}
	}

	public boolean isNotifyOnlyAtThree() { return notifyOnlyAtThree; }
	public void setNotifyOnlyAtThree(boolean value) { notifyOnlyAtThree = value; }
	public boolean isNotifyChat() { return notifyChat; }
	public void setNotifyChat(boolean value) { notifyChat = value; }
	public boolean isNotifySound() { return notifySound; }
	public void setNotifySound(boolean value) { notifySound = value; }
	public boolean isNotifyTitle() { return notifyTitle; }
	public void setNotifyTitle(boolean value) { notifyTitle = value; }
	public CustomSoundOption getSound() { return sound; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("notifyOnlyAtThree", notifyOnlyAtThree);
		obj.addProperty("notifyChat", notifyChat);
		obj.addProperty("notifySound", notifySound);
		obj.addProperty("notifyTitle", notifyTitle);
		obj.addProperty("notifyAtMillis", notifyAtMillis);
		obj.add("sound", sound.toJson());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!(el instanceof JsonObject obj)) return;
		if (obj.has("notifyOnlyAtThree")) notifyOnlyAtThree = obj.get("notifyOnlyAtThree").getAsBoolean();
		if (obj.has("notifyChat")) notifyChat = obj.get("notifyChat").getAsBoolean();
		if (obj.has("notifySound")) notifySound = obj.get("notifySound").getAsBoolean();
		if (obj.has("notifyTitle")) notifyTitle = obj.get("notifyTitle").getAsBoolean();
		if (obj.has("notifyAtMillis")) notifyAtMillis = obj.get("notifyAtMillis").getAsLong();
		if (obj.has("sound")) sound.fromJson(obj.get("sound"));
	}

	@Override
	public String getDescription() {
		return "Tracks your Experimentation Table's next charge and notifies you (even after a restart) once it's ready.";
	}
}
