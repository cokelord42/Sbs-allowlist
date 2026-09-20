package com.cokelord.skyblocksimplified.api;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enchanting &gt; Experimentation Table detection, ported from SkyHanni's ExperimentationTableApi.kt
 * (confirmed regexes for the GUI titles, the "Stakes:"/rewards lore, and the "§d§kXX§5 ULTRA-RARE BOOK!
 * §d§kXX" + "§9(enchant)" lore pair). Tracks which run (type/tier) is currently open from the GUI title,
 * and fires two callbacks other features build on: a completed-run event (type/tier/XP gained) and an
 * ultra-rare-uncovered event (enchant name).
 *
 * <p>The completed-run event needs SkyHanni's own hash+delay trick to avoid double-firing: the
 * "Experiment Over"/"Superpairs Rewards" GUI's reward-lore is hashed once per genuinely new result
 * (guarding against the player simply re-opening the same already-seen result screen), and the actual
 * fire is deferred 150ms past the GUI closing so an early re-open of the same screen (which recreates
 * the AFTER_INIT event before the close event's effects should apply) doesn't produce a false positive.
 * Since this project has no tick-scheduler utility yet, that delay is a tiny local queue driven off
 * ClientTickEvents.END_CLIENT_TICK, registered once in {@link #start()}.
 *
 * <p>Scoped down from the original in one place: the Superpairs "ultra-rare misc item" detection (things
 * like Endcap Upgrades that don't carry the ULTRA-RARE lore header) relied on SkyHanni's own repo data
 * for which internal names count — not available here, so only the lore-header ultra-rare book case is
 * detected.
 */
public final class ExperimentationTableApi {
	private ExperimentationTableApi() {}

	private static final int ADDONS_OVER_DATA_SLOT = 11;
	private static final int SUPERPAIRS_OVER_DATA_SLOT = 13;
	private static final long CLOSE_CHECK_DELAY_MILLIS = 150;

	private static final Pattern INVENTORY_PATTERN = Pattern.compile(
		"(?:Superpairs|Chronomatron|Ultrasequencer) ?(?:\\(.+\\)|➜ Stakes|Rewards)|Experiment(?:ation Tabl| [Oo]v)er?");
	private static final Pattern TYPE_TIER_PATTERN = Pattern.compile(
		"(?<type>Superpairs|Chronomatron|Ultrasequencer) \\((?<tier>.*)\\)");
	// Plain text throughout — no literal §-code requirements. Component#getString() (what every lore/name
	// read in this file goes through) already strips all formatting, so the original SkyHanni-ported
	// patterns (which require literal "§7"/"§e"/etc. prefixes) could never match anything: tier detection
	// always failed (silently dropping every completed-run event before it could fire), and the
	// ultra-rare-book line/enchant-name patterns could never match either (the alert never fired).
	private static final Pattern EXP_OVER_PATTERN = Pattern.compile("Experiment [Oo]ver|Superpairs Rewards");
	private static final Pattern STAKES_PATTERN = Pattern.compile("Stakes: (?<stakes>.*)");
	private static final Pattern REWARDS_START_PATTERN = Pattern.compile("Rewards:");
	private static final Pattern REWARDS_END_PATTERN = Pattern.compile("Click to claim rewards!");
	private static final Pattern REWARDS_LORE_PATTERN = Pattern.compile(
		"\\s*\\+\\s*(?:\\[Lvl \\d+]\\s*)?(?<reward>.*?)(?=\\s\\((?:Stakes|Pairs)\\)|$)(?:\\s\\((?:Stakes|Pairs)\\))?");
	private static final Pattern ENCHANTING_EXP_PATTERN = Pattern.compile(
		"(?<amount>(?:\\d+|\\d+,\\d+)[MBk]?) Enchanting Exp");
	private static final String ULTRA_RARE_LINE = "XX ULTRA-RARE BOOK! XX";
	private static final Pattern BOOK_PATTERN = Pattern.compile("(?<enchant>.*)");

	public enum TaskType {
		CHRONOMATRON, ULTRASEQUENCER, SUPERPAIRS;

		private static TaskType fromDisplayName(String name) {
			for (TaskType type : values()) {
				if (type.name().equalsIgnoreCase(name)) return type;
			}
			return null;
		}
	}

	public enum Tier {
		BEGINNER("Beginner"), HIGH("High"), GRAND("Grand"), SUPREME("Supreme"), TRANSCENDENT("Transcendent"), METAPHYSICAL("Metaphysical");

		private final String displayName;

		Tier(String displayName) {
			this.displayName = displayName;
		}

		private static Tier fromDisplayName(String name) {
			for (Tier tier : values()) {
				if (tier.displayName.equalsIgnoreCase(name)) return tier;
			}
			return null;
		}
	}

	public record CompletedRun(TaskType type, Tier tier, long enchantingXpGained) {}

	public record RareUncover(String enchantName) {}

	private record ScheduledTask(long fireAtMillis, Runnable task) {}

	private static final List<Consumer<CompletedRun>> completedListeners = new ArrayList<>();
	private static final List<Consumer<RareUncover>> rareUncoverListeners = new ArrayList<>();
	private static final List<ScheduledTask> scheduled = new ArrayList<>();

	private static boolean started = false;
	private static TaskType currentType;
	private static Tier currentTier;
	private static boolean rareFoundFired;
	private static int lastExpOverHash;
	private static int currentExpOverHash;
	private static CompletedRun pendingRun;

	public static void start() {
		if (started) return;
		started = true;
		ScreenEvents.AFTER_INIT.register(ExperimentationTableApi::onScreenOpen);
		// Wrapped in try-catch — see BlessingParticleFilter's doc comment on its own registration for the
		// full reasoning: this registers before FeatureRegistry::tickAll (during feature construction), and
		// Fabric dispatches END_CLIENT_TICK listeners in registration order with no isolation between them,
		// so an uncaught throw here — plausible, since runScheduled() executes arbitrary Runnable tasks
		// other features hand it — would silently skip tickAll (and every feature it ticks) for that frame.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				runScheduled();
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("ExperimentationTableApi scheduled-task tick threw, skipping this tick", e);
			}
		});
	}

	public static void addCompletedRunListener(Consumer<CompletedRun> listener) {
		completedListeners.add(listener);
	}

	public static void addRareUncoverListener(Consumer<RareUncover> listener) {
		rareUncoverListeners.add(listener);
	}

	public static boolean inTable() {
		return INVENTORY_PATTERN.matcher(currentScreenTitle()).matches();
	}

	public static TaskType currentType() {
		return currentType;
	}

	public static Tier currentTier() {
		return currentTier;
	}

	public static boolean inChronomatron() {
		return currentType == TaskType.CHRONOMATRON;
	}

	public static boolean inUltrasequencer() {
		return currentType == TaskType.ULTRASEQUENCER;
	}

	public static boolean inSuperpairs() {
		return currentType == TaskType.SUPERPAIRS;
	}

	public static boolean inAddon() {
		return inChronomatron() || inUltrasequencer();
	}

	private static String currentScreenTitle() {
		Screen screen = Minecraft.getInstance().gui.screen();
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return "";
		return containerScreen.getTitle().getString();
	}

	private static void onScreenOpen(Minecraft client, Screen screen, int width, int height) {
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
		handleTitle(containerScreen.getTitle().getString(), containerScreen.getMenu());

		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> {
			if (inTable()) scanForRareUncover(containerScreen.getMenu());
		});
		ScreenEvents.remove(screen).register(s -> onScreenClose());
	}

	private static void handleTitle(String title, AbstractContainerMenu menu) {
		if (title.equals("Experimentation Table")) {
			currentType = null;
			currentTier = null;
			rareFoundFired = false;
			return;
		}

		Matcher typeTier = TYPE_TIER_PATTERN.matcher(title);
		if (typeTier.matches()) {
			TaskType type = TaskType.fromDisplayName(typeTier.group("type"));
			Tier tier = Tier.fromDisplayName(typeTier.group("tier"));
			if (type != null && tier != null) {
				currentType = type;
				currentTier = tier;
			}
			return;
		}

		if (currentExpOverHash == 0 && EXP_OVER_PATTERN.matcher(title).matches()) {
			tryProcessExperimentOver(menu);
		}
	}

	private static void tryProcessExperimentOver(AbstractContainerMenu menu) {
		int slotIndex = currentType == TaskType.SUPERPAIRS ? SUPERPAIRS_OVER_DATA_SLOT : ADDONS_OVER_DATA_SLOT;
		if (menu.slots.size() <= slotIndex) return;

		ItemStack item = menu.getSlot(slotIndex).getItem();
		List<Component> loreComponents = loreLines(item);
		if (loreComponents.isEmpty()) return;
		List<String> lore = loreComponents.stream().map(Component::getString).toList();

		int hash = lore.hashCode();
		if (hash == 0 || hash == lastExpOverHash || hash == currentExpOverHash) return;
		currentExpOverHash = hash;

		TaskType type = TaskType.fromDisplayName(item.getHoverName().getString().replaceAll("§.", "").trim());
		if (type == null) type = currentType;
		if (type == null) return;

		Tier tier = null;
		int rewardsBegin = -1;
		int rewardsEnd = -1;
		for (int i = 0; i < lore.size(); i++) {
			String line = lore.get(i);
			Matcher stakesMatcher = STAKES_PATTERN.matcher(line);
			if (stakesMatcher.matches()) tier = Tier.fromDisplayName(stakesMatcher.group("stakes"));
			if (REWARDS_START_PATTERN.matcher(line).matches()) rewardsBegin = i + 1;
			if (REWARDS_END_PATTERN.matcher(line).matches()) rewardsEnd = i - 1;
		}
		if (tier == null) return;

		long xpGained = 0;
		if (rewardsBegin >= 0) {
			for (int i = rewardsBegin; i <= rewardsEnd && i < lore.size(); i++) {
				Matcher rewardMatcher = REWARDS_LORE_PATTERN.matcher(lore.get(i));
				if (!rewardMatcher.find()) continue;
				Matcher xpMatcher = ENCHANTING_EXP_PATTERN.matcher(rewardMatcher.group("reward"));
				if (xpMatcher.matches()) xpGained += parseAmount(xpMatcher.group("amount"));
			}
		}

		pendingRun = new CompletedRun(type, tier, xpGained);
	}

	private static void scanForRareUncover(AbstractContainerMenu menu) {
		if (rareFoundFired) return;
		for (var slot : menu.slots) {
			List<Component> lore = loreLines(slot.getItem());
			if (lore.size() <= 2) continue;
			if (!lore.get(0).getString().equals(ULTRA_RARE_LINE)) continue;
			Matcher bookMatcher = BOOK_PATTERN.matcher(lore.get(2).getString());
			if (!bookMatcher.matches()) continue;

			rareFoundFired = true;
			RareUncover uncover = new RareUncover(bookMatcher.group("enchant"));
			for (Consumer<RareUncover> listener : rareUncoverListeners) listener.accept(uncover);
			return;
		}
	}

	private static void onScreenClose() {
		if (currentExpOverHash != 0) {
			lastExpOverHash = currentExpOverHash;
			currentExpOverHash = 0;
		}
		// Catches early closes that re-trigger this event before the "real" next screen is up: if what's
		// open 150ms later is still the same Experiment-Over-family GUI, this was just a flicker/re-open,
		// not an actual departure, so skip resetting anything until it genuinely closes.
		schedule(CLOSE_CHECK_DELAY_MILLIS, () -> {
			if (EXP_OVER_PATTERN.matcher(currentScreenTitle()).matches()) return;
			// Still somewhere in the Experimentation Table family (e.g. bounced straight from one task
			// back to the table's own main menu) — handleTitle() already resets state correctly for that
			// case when the main menu title is seen, so don't stomp on it here.
			if (inTable()) return;
			// currentType/currentTier used to only get cleared here when a run had actually completed
			// (pendingRun != null) — simply closing the Ultrasequencer/Chronomatron mid-experiment (Escape,
			// clicking away, etc., with no "Experiment Over" screen ever appearing) left currentType stuck
			// forever, since nothing else ever reset it. That's what let checkSixStackThreshold keep firing
			// "max clicks reached" in totally unrelated GUIs afterward — the actual "doesn't quit after the
			// user has quit the experiment" bug. Reset unconditionally now; only fire the completed-run
			// event when there actually was one.
			CompletedRun run = pendingRun;
			pendingRun = null;
			currentType = null;
			currentTier = null;
			rareFoundFired = false;
			if (run != null) {
				for (Consumer<CompletedRun> listener : completedListeners) listener.accept(run);
			}
		});
	}

	private static List<Component> loreLines(ItemStack stack) {
		if (stack.isEmpty()) return List.of();
		ItemLore lore = stack.get(DataComponents.LORE);
		return lore == null ? List.of() : lore.lines();
	}

	private static long parseAmount(String raw) {
		String cleaned = raw.replace(",", "");
		if (cleaned.isEmpty()) return 0L;
		char suffix = Character.toLowerCase(cleaned.charAt(cleaned.length() - 1));
		long multiplier = switch (suffix) {
			case 'k' -> 1_000L;
			case 'm' -> 1_000_000L;
			case 'b' -> 1_000_000_000L;
			default -> 1L;
		};
		String digits = multiplier == 1L ? cleaned : cleaned.substring(0, cleaned.length() - 1);
		try {
			return Long.parseLong(digits) * multiplier;
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private static void schedule(long delayMillis, Runnable task) {
		scheduled.add(new ScheduledTask(System.currentTimeMillis() + delayMillis, task));
	}

	private static void runScheduled() {
		if (scheduled.isEmpty()) return;
		long now = System.currentTimeMillis();
		Iterator<ScheduledTask> it = scheduled.iterator();
		while (it.hasNext()) {
			ScheduledTask task = it.next();
			if (now >= task.fireAtMillis()) {
				it.remove();
				task.task().run();
			}
		}
	}
}
