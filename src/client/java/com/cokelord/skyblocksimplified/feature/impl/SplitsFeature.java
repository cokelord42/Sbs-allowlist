package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.hud.ScoreboardReader;
import com.cokelord.skyblocksimplified.util.IslandGate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Visual split timers for Kuudra and Dungeon runs, with per-split personal bests. Ported from Odin's
 * {@code Splits.kt} + {@code SplitsManager.kt}, merged into one file since nothing else in this codebase
 * needs to consume "current splits" state — the same consolidation this session already applied to
 * Odin's multi-file terminal subsystem (see TerminalSolverFeature).
 *
 * <p>Odin ships two independently-positioned HUD widgets (full split list, and a "current split only"
 * compact readout); only the full list is kept here — the compact readout was removed per explicit user
 * request ("its not needed"). Draggable via the HUD edit screen like every other widget in this codebase.
 * Kuudra tier is read by polling the scoreboard
 * sidebar for "Kuudra's Hollow (T#)" each tick (Odin reads it off a scoreboard team packet directly; this
 * codebase's existing {@link ScoreboardReader} already exposes the same sidebar text more cheaply). The
 * split "tick" clock increments once per client tick rather than a genuine server-tick event (Odin's
 * {@code TickEvent.Server}, which this codebase has no equivalent hook for) — equivalent in practice since
 * the client can't meaningfully diverge from server tick rate while actively playing a timed dungeon/Kuudra run.
 *
 * <p>Per user question ("is it possible to make the splits lag proof, since lag is normal and it will
 * probably throw off the timers"): every real displayed time already is — every split records a real
 * {@code System.currentTimeMillis()} timestamp the instant its chat line matches, and every shown duration
 * ({@link #computeTimes}) is a plain subtraction between two of those, so a client-side stutter/frame drop
 * (a slow tick, a GC pause, low FPS) can't skew anything: the wall clock keeps advancing correctly through
 * it regardless of how choppy rendering/ticking gets. tickCounter/Split.ticks only feed showTickTime's
 * secondary parenthetical cross-check readout — off by default with no exposed toggle — so the one
 * genuinely tick-based number in this class is already inert unless a future session wires up a UI for it.
 *
 * <p>Per explicit user request ("Everything completely breaks if splits isnt on. Splits is the underlying
 * backbone of all features, and it should be forced on. The module and toggle should only serve as the
 * display and not actually the feature of counting the splits, which should be done automatically."):
 * {@link DungeonTimersFeature} and {@link DungeonsCopilotFeature} both key off {@link #millisSinceSplit}
 * to know "which phase/split are we in" regardless of whether they'd ever touch this class's own HUD
 * widget, so the split-tracking state machine (the chat listener that advances {@link #currentSplits},
 * the per-tick Kuudra-tier poll and dungeon-leave reset) is registered unconditionally — once, in the
 * constructor, exactly like {@link com.cokelord.skyblocksimplified.dungeon.DungeonState#register()} runs
 * independently of any feature toggle — instead of inside {@code onEnable()}/{@code onDisable()} the way
 * every other Feature in this codebase gates its own tick/event work. {@link #isEnabled()} (the module's
 * on/off checkbox in the mod menu) now controls ONLY {@link #isVisible()} — whether the HUD panel actually
 * draws on screen — never whether splits are being tracked. Someone with the toggle off still gets fully
 * correct {@link #millisSinceSplit} data flowing to every dependent feature; they just see no on-screen
 * splits widget, which is exactly the cosmetic-only distinction the user asked for. */
public class SplitsFeature extends Feature implements MoveableWidget {
	private static final class Split {
		final Pattern regex;
		final String name;
		long time = 0L;
		long ticks = 0L;
		Split(Pattern regex, String name) { this.regex = regex; this.name = name; }
	}

	private static final class SplitsGroup {
		final List<Split> splits;
		final PersonalBest personalBest;
		SplitsGroup(List<Split> splits, PersonalBest personalBest) { this.splits = splits; this.personalBest = personalBest; }
		static final SplitsGroup EMPTY = new SplitsGroup(List.of(), null);
	}

	private final class PersonalBest {
		final String id;
		final Map<String, Float> data = new LinkedHashMap<>();
		PersonalBest(String id) { this.id = id; personalBests.put(id, this); }

		void time(String name, float time, String message) {
			float oldPb = data.getOrDefault(name, 9999f);
			String msg;
			if (oldPb > time) {
				data.put(name, time);
				com.cokelord.skyblocksimplified.config.ConfigManager.save();
				msg = "§7(§d§lNew PB§r§7) Old PB was §8" + oldPb;
			} else {
				msg = "§8(§7" + oldPb + "§8)";
			}
			if (sendSplits) chatMessage(message + time + "s§7! " + msg);
		}
	}


	private final Map<String, PersonalBest> personalBests = new LinkedHashMap<>();

	private final PersonalBest kuudraT5PB = new PersonalBest("KuudraT5");
	private final PersonalBest kuudraT4PB = new PersonalBest("KuudraT4");
	private final PersonalBest kuudraT3PB = new PersonalBest("KuudraT3");
	private final PersonalBest kuudraT2PB = new PersonalBest("KuudraT2");
	private final PersonalBest kuudraT1PB = new PersonalBest("KuudraT1");
	private final PersonalBest[] dungeonPBs = {
		new PersonalBest("DungeonE"), new PersonalBest("DungeonF1"), new PersonalBest("DungeonF2"), new PersonalBest("DungeonF3"),
		new PersonalBest("DungeonF4"), new PersonalBest("DungeonF5"), new PersonalBest("DungeonF6"), new PersonalBest("DungeonF7"),
	};
	private final PersonalBest[] masterModePBs = {
		new PersonalBest("DungeonM1"), new PersonalBest("DungeonM2"), new PersonalBest("DungeonM3"), new PersonalBest("DungeonM4"),
		new PersonalBest("DungeonM5"), new PersonalBest("DungeonM6"), new PersonalBest("DungeonM7"),
	};

	private static Pattern p(String regex) { return Pattern.compile(regex); }

	private static final Pattern MORT_REGEX = p("\\[NPC] Mort: Here, I found this map when I first entered the dungeon\\.|\\[NPC] Mort: Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!");
	private static final Pattern BLOOD_OPEN_REGEX = p("^\\[BOSS] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|Ah, we meet again\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|You've managed to scratch and claw your way here, eh\\?|I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|Things feel a little more roomy now, eh\\?)$|^The BLOOD DOOR has been opened!$");

	private static final Pattern[] ENTRY_REGEXES = {
		p("^\\[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable\\.$"),
		p("^\\[BOSS] Scarf: This is where the journey ends for you, Adventurers\\.$"),
		p("^\\[BOSS] The Professor: I was burdened with terrible news recently\\.\\.\\.$"),
		p("^\\[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!$"),
		p("^\\[BOSS] Livid: Welcome, you've arrived right on time\\. I am Livid, the Master of Shadows\\.$"),
		p("^\\[BOSS] Sadan: So you made it all the way here\\.\\.\\. Now you wish to defy me\\? Sadan\\?!$"),
		p("^\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$"),
	};

	private static List<Split> dungeonSplitGroup(int floorNumber) {
		List<Split> splits = new ArrayList<>();
		switch (floorNumber) {
			case 0 -> {}
			case 1 -> { splits.add(new Split(ENTRY_REGEXES[0], "§cBonzo's Sike")); splits.add(new Split(p("\\[BOSS] Bonzo: Oh I'm dead!"), "§4Cleared")); }
			case 2 -> { splits.add(new Split(ENTRY_REGEXES[1], "§cScarf's minions")); splits.add(new Split(p("^\\[BOSS] Scarf: Did you forget\\? I was taught by the best! Let's dance\\.$"), "§4Cleared")); }
			case 3 -> {
				splits.add(new Split(ENTRY_REGEXES[2], "§cThe Guardians"));
				splits.add(new Split(p("^\\[BOSS] The Professor: Oh\\? You found my Guardians' one weakness\\?$"), "§aThe Professor"));
				splits.add(new Split(p("^\\[BOSS] The Professor: What\\?! My Guardian power is unbeatable!$"), "§4Cleared"));
			}
			case 4 -> splits.add(new Split(ENTRY_REGEXES[3], "§4Cleared"));
			case 5 -> splits.add(new Split(ENTRY_REGEXES[4], "§4Cleared"));
			case 6 -> {
				splits.add(new Split(ENTRY_REGEXES[5], "§cTerracottas"));
				splits.add(new Split(p("^\\[BOSS] Sadan: ENOUGH!$"), "§aGiants"));
				splits.add(new Split(p("^\\[BOSS] Sadan: You did it\\. I understand now, you have earned my respect\\.$"), "§4Cleared"));
			}
			case 7 -> {
				splits.add(new Split(ENTRY_REGEXES[6], "§5Maxor"));
				splits.add(new Split(p("\\[BOSS] Storm: Pathetic Maxor, just like expected\\."), "§3Storm"));
				splits.add(new Split(p("\\[BOSS] Goldor: Who dares trespass into my domain\\?"), "§6Terminals"));
				splits.add(new Split(p("The Core entrance is opening!"), "§7Goldor"));
				splits.add(new Split(p("\\[BOSS] Necron: You went further than any human before, congratulations\\."), "§cNecron"));
				splits.add(new Split(p("\\[BOSS] Necron: All this, for nothing\\.\\.\\."), "§4Cleared"));
			}
			default -> {}
		}
		return splits;
	}

	private static List<Split> kuudraT5SplitGroup() {
		List<Split> s = new ArrayList<>();
		s.add(new Split(p("^\\[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!$"), "§2Supplies"));
		s.add(new Split(p("^\\[NPC] Elle: OMG! Great work collecting my supplies!$"), "§bBuild"));
		s.add(new Split(p("^\\[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!$"), "§dEaten"));
		s.add(new Split(p("^(?!Elle has been eaten by Kuudra!$)(.{1,16}) has been eaten by Kuudra!$"), "§cStun"));
		s.add(new Split(p("^(.{1,16}) destroyed one of Kuudra's pods!$"), "§4DPS"));
		s.add(new Split(p("^\\[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!$"), "§4Cleared"));
		s.add(new Split(p("^\\[NPC] Elle: Good job everyone\\. A hard fought battle come to an end\\. Let's get out of here before we run into any more trouble!$"), "Total"));
		return s;
	}

	private static List<Split> kuudraSplitGroup() {
		List<Split> s = new ArrayList<>();
		s.add(new Split(p("^\\[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!$"), "§2Supplies"));
		s.add(new Split(p("^\\[NPC] Elle: OMG! Great work collecting my supplies!$"), "§bBuild"));
		s.add(new Split(p("^\\[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!$"), "§cStun"));
		s.add(new Split(p("^\\[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!$"), "§4Cleared"));
		s.add(new Split(p("^\\[NPC] Elle: Good job everyone\\. A hard fought battle come to an end\\. Let's get out of here before we run into any more trouble!$"), "Total"));
		return s;
	}

	private static final Pattern KUUDRA_TIER_PATTERN = Pattern.compile("Kuudra's Hollow \\(T(\\d)\\)$");

	public enum SplitLocation { BOTH, DUNGEONS_ONLY, KUUDRA_ONLY }

	private boolean fixedWidth = true;
	private boolean bossEntrySplit = true;
	// Per user request ("you can hide the chat for it honestly theres no need for the chat messages") — no
	// exposed UI toggle for this either (same gap as showTickTime above), defaulted off directly.
	private boolean sendSplits = false;
	// Per user request ("it should honestly just hide the second part of the text and just show only the
	// timer") — this parenthetical tick-based cross-check has no exposed UI toggle anywhere in the mod menu
	// (isShowTickTime/setShowTickTime exist but nothing ever calls them), so there was no way for the user
	// to turn it off themselves; defaulted off instead of adding a toggle for a single confirmed preference.
	private boolean showTickTime = false;
	private SplitLocation splitLocation = SplitLocation.BOTH;
	// Per user request ("Allow users to change the color of the splits background, add an outline and
	// change the color of that too."): the panel background used to be a hardcoded 0x80000000 with no
	// outline at all — now both are real, user-configurable settings, defaulted to match that same old
	// look (same color, outline off) so nobody's existing panel changes appearance unasked.
	private int backgroundColor = 0x80000000;
	private boolean outlineEnabled = false;
	private int outlineColor = 0xFF000000;
	private int outlineThickness = 1;

	private SplitsGroup currentSplits = SplitsGroup.EMPTY;
	private long tickCounter = 0L;
	private int kuudraTier = 0;
	// Real bug found (per user report — "the splits module keeps going after leaving the dungeon"):
	// isVisible() never checked DungeonState.isInDungeon()/IslandGate.isInKuudra() at all, only "on
	// Hypixel + currentSplits not empty" — and onTick()'s own reset branch only fires once
	// DungeonState.isInDungeon() goes false, which requires either IslandGate.isInHubOrLobby() or the
	// literal "instance will close" chat line (see DungeonState.reset()'s own trigger conditions). If the
	// player leaves through any OTHER path (warped straight to a different island, disconnected and
	// reconnected elsewhere, etc.) floorNumber can stay stuck positive indefinitely, and this widget then
	// never clears. Rather than widen DungeonState's own reset conditions (deliberately narrow/sticky so a
	// mid-fight sidebar flicker can't wipe it — see its own doc comments), this tracks the run's own
	// completion independently: once the LAST split in a group fires (a real "run is objectively over"
	// signal from Splits' own data), the widget auto-clears RUN_FINISHED_GRACE_MILLIS later regardless of
	// what DungeonState/IslandGate still think.
	private static final long RUN_FINISHED_GRACE_MILLIS = 45_000L;
	private Long runFinishedAtMillis = null;

	private final HudPosition defaultPosition = new HudPosition(0.02f, 0.5f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static boolean listenersRegistered = false;
	private static SplitsFeature instance;

	public SplitsFeature() {
		super("splits", "Splits", FeatureCategory.INVENTORY, false);
		instance = this;
		HudWidgetRegistry.register(this);
		registerAlwaysOnTracking();
	}

	/** The actual split-tracking state machine — chat matching, Kuudra-tier polling, dungeon-leave reset —
	 *  registered exactly once here in the constructor rather than in {@code onEnable()}, so it keeps
	 *  running whether or not the module's own toggle is on. See this class's top-of-file doc comment for
	 *  why. Only {@link #render} and the {@code HudElementRegistry} callback below are display and stay
	 *  gated behind {@link #isVisible()} (which itself still checks {@link #isEnabled()}). */
	private void registerAlwaysOnTracking() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			// Real bug found (per user report — a garbage "Blood Open took 1.7869861E9s! (9999.0)" chat
			// message, and the widget stuck showing "0s" for that same split): MORT_REGEX
			// ("[NPC] Mort: Here, I found this map...") never matched, because message.getString() was
			// never stripped of "§" color codes before matching — Hypixel colors "[NPC]"/"Mort" and the
			// message body differently, breaking up the literal substring this regex expects into
			// non-contiguous pieces. That left the "Blood Open" split's own .time permanently 0, which
			// computeTimes() (see its own doc comment) treats as "not started" and bails out to an
			// all-zero times[] array — explaining the perpetual "0s" — until the NEXT split (Blood Clear)
			// fired and computed its own elapsed time as (a real epoch-ms timestamp - 0) / 1000, a
			// multi-billion-"second" garbage value. The same class of bug already found and fixed for
			// DungeonChatFilter/DungeonState elsewhere this session, just never applied here.
			//
			// Deliberately NOT gated on instance.isEnabled() (see this class's own top-of-file doc
			// comment): tracking must keep advancing regardless of the module's display toggle so every
			// dependent feature's millisSinceSplit() calls stay correct even with the widget hidden.
			if (instance != null && !overlay) instance.onChatLine(message.getString().replaceAll("§.", ""));
			return true;
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (instance != null) instance.trackTick(client);
		});
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "splits"), (graphics, tracker) -> {
			if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
				|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
			Minecraft mc = Minecraft.getInstance();
			int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
			int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
			instance.render(graphics, x, y, instance.position.scale);
		});
	}

	/** Millis since the named split (matched by a substring of its display name — color codes included in
	 *  the stored name but not needed in the query, e.g. "Necron" matches "§cNecron") fired this run, or
	 *  null if it hasn't fired yet / there's no active run. Used by DungeonTimersFeature's Necron
	 *  lava-drop warning, which needs to know how long ago the Necron split itself fired without this
	 *  class exposing its whole internal split list. */
	public static Long millisSinceSplit(String nameContains) {
		if (instance == null) return null;
		for (Split s : instance.currentSplits.splits) {
			if (s.time != 0L && s.name.contains(nameContains)) return System.currentTimeMillis() - s.time;
		}
		return null;
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	/** The always-on tracking engine's per-tick work — advances the Kuudra-tier poll, the split-rebuild
	 *  retry, and the dungeon/Kuudra-leave reset. Called unconditionally from the {@code ClientTickEvents}
	 *  hook registered in {@link #registerAlwaysOnTracking()}, NOT via the normal {@code Feature.onTick}
	 *  lifecycle (which {@link com.cokelord.skyblocksimplified.feature.FeatureRegistry#tickAll} only drives
	 *  for enabled features) — see this class's own top-of-file doc comment for why. */
	private void trackTick(Minecraft client) {
		tickCounter++;
		if (IslandGate.isInKuudra()) {
			for (String line : ScoreboardReader.readCurrentSidebarLines()) {
				java.util.regex.Matcher m = KUUDRA_TIER_PATTERN.matcher(line);
				if (m.find()) {
					try {
						int parsed = Integer.parseInt(m.group(1));
						kuudraTier = parsed;
					} catch (NumberFormatException ignored) {}
					break;
				}
			}
		} else if (!DungeonState.isInDungeon() || (runFinishedAtMillis != null && System.currentTimeMillis() - runFinishedAtMillis > RUN_FINISHED_GRACE_MILLIS)) {
			// Real bug found (per user report — "Splits start at like 20 seconds when entering dungeons"):
			// this used to read the raw, still-flicker-prone IslandGate.isInDungeon() directly instead of
			// DungeonState.isInDungeon() (which now centralizes the "trust a confirmed floorNumber over a
			// bare sidebar-flicker false negative" leniency — see that method's own doc comment, added after
			// the exact same class of bug broke Door Highlight). A mid-run sidebar flicker reading as "not in
			// a dungeon" wiped currentSplits and tickCounter back to empty/0 right in the middle of a real
			// run, forcing a full rebuild on the very next tick — explains erratic/reset split timings.
			if (!currentSplits.splits.isEmpty()) { currentSplits = SplitsGroup.EMPTY; tickCounter = 0L; }
			kuudraTier = 0;
			runFinishedAtMillis = null;
		}
		// Real bug found (per user report — "Splits shows nothing" despite the "run started" chat trigger
		// firing): the very first buildSplitsGroup() attempt (from "Starting in 1 second.") can genuinely
		// come back empty (DungeonState's floor number/label — or, for Kuudra, the tier read from the
		// scoreboard above — hasn't caught up yet from the tab list/scoreboard by the time that chat line
		// fires), which is a real, already-known race — but the RETRY for that used to only run for a fixed
		// 100 ticks (~5s) before silently giving up forever. That's not always enough: "Starting in 1
		// second." fires before the actual dungeon teleport/world-generation completes, which can easily
		// take longer than 5 seconds on a loaded Hypixel instance, so the retry window could lapse before
		// the floor/tier data was ever available to retry against. Simplified to just keep retrying every
		// tick for as long as the group is still empty AND the player is actually in a dungeon/Kuudra
		// encounter — cheap (a few Pattern-backed object constructions), and naturally bounded by the reset
		// branch above the moment the player actually leaves, so there's no real "runs forever" risk.
		if (currentSplits.splits.isEmpty() && (DungeonState.isInDungeon() || IslandGate.isInKuudra())) {
			SplitsGroup rebuilt = buildSplitsGroup();
			if (!rebuilt.splits.isEmpty()) {
				currentSplits = rebuilt;
			}
		}
	}

	private void onChatLine(String rawText) {
		String text = rawText.trim();
		if (text.equals("Starting in 1 second.")) {
			tickCounter = 0L;
			runFinishedAtMillis = null;
			currentSplits = buildSplitsGroup();
			// If empty (floor/tier not known yet), onTick's own retry (see its doc comment) picks this back
			// up every tick for as long as it takes, no fixed timeout.
			return;
		}
		if (currentSplits.splits.isEmpty()) return;

		Split matched = null;
		for (Split s : currentSplits.splits) {
			if (s.time == 0L && s.regex.matcher(text).find()) { matched = s; break; }
		}
		if (matched == null) return;

		matched.time = System.currentTimeMillis();
		matched.ticks = tickCounter;
		int index = currentSplits.splits.indexOf(matched);
		if (index == 0) return;

		float currentSplitTime = (matched.time - currentSplits.splits.get(index - 1).time) / 1000f;
		if (index == currentSplits.splits.size() - 1) {
			runFinishedAtMillis = matched.time;
			long[] times = computeTimes(currentSplits);
			Split prevSplit = currentSplits.splits.get(index - 1);
			if (currentSplits.personalBest != null) {
				currentSplits.personalBest.time(prevSplit.name, currentSplitTime, "§6" + prevSplit.name + " §7took §6");
				currentSplits.personalBest.time(matched.name, times[times.length - 1] / 1000f, "§6Total time §7took §6");
			}
			if (sendSplits) {
				for (int i = 0; i < times.length; i++) {
					String name = i == currentSplits.splits.size() - 1 ? "Total" : currentSplits.splits.get(i).name;
					chatMessage("§6" + name + " §7took §6" + formatTime(times[i]) + "§7.");
				}
			}
		} else if (currentSplits.personalBest != null) {
			Split prevSplit = currentSplits.splits.get(index - 1);
			currentSplits.personalBest.time(prevSplit.name, currentSplitTime, "§6" + prevSplit.name + " §7took §6");
		}
	}

	private SplitsGroup buildSplitsGroup() {
		if (DungeonState.isInDungeon() && splitLocation != SplitLocation.KUUDRA_ONLY) {
			int floorNumber = DungeonState.getFloorNumber();
			String floorLabel = DungeonState.getFloorLabel();
			if (floorNumber < 0 || floorLabel == null) return SplitsGroup.EMPTY;
			boolean masterMode = floorLabel.startsWith("M");

			List<Split> splits = new ArrayList<>();
			splits.add(new Split(MORT_REGEX, "§2Blood Open"));
			splits.add(new Split(BLOOD_OPEN_REGEX, "§bBlood Clear"));
			splits.add(new Split(p("\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\."), "§dPortal Entry"));
			splits.addAll(dungeonSplitGroup(floorNumber));
			splits.add(new Split(p("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$"), "§1Total"));

			PersonalBest pb = floorNumber == 0 ? null
				: masterMode ? (floorNumber - 1 < masterModePBs.length ? masterModePBs[floorNumber - 1] : null)
				: (floorNumber < dungeonPBs.length ? dungeonPBs[floorNumber] : null);
			return new SplitsGroup(splits, pb);
		}
		if (IslandGate.isInKuudra() && splitLocation != SplitLocation.DUNGEONS_ONLY) {
			return switch (kuudraTier) {
				case 5 -> new SplitsGroup(kuudraT5SplitGroup(), kuudraT5PB);
				case 4 -> new SplitsGroup(kuudraSplitGroup(), kuudraT4PB);
				case 3 -> new SplitsGroup(kuudraSplitGroup(), kuudraT3PB);
				case 2 -> new SplitsGroup(kuudraSplitGroup(), kuudraT2PB);
				case 1 -> new SplitsGroup(kuudraSplitGroup(), kuudraT1PB);
				default -> SplitsGroup.EMPTY;
			};
		}
		return SplitsGroup.EMPTY;
	}

	/** Returns per-split elapsed millis (last entry is always "so far/total"), mirroring Odin's getAndUpdateSplitsTimes. */
	private long[] computeTimes(SplitsGroup group) {
		int n = group.splits.size();
		long[] times = new long[n];
		if (n == 0 || group.splits.get(0).time == 0L) return times;

		Split last = group.splits.get(n - 1);
		long latestTime = last.time == 0L ? System.currentTimeMillis() : last.time;
		times[n - 1] = latestTime - group.splits.get(0).time;

		for (int i = 0; i < n - 1; i++) {
			if (group.splits.get(i + 1).time != 0L) {
				times[i] = group.splits.get(i + 1).time - group.splits.get(i).time;
			} else {
				// Per user report ("Splits don't account for lag, they kept going" — confirmed on follow-up:
				// "the counting keeps going during server lag/temporary freezes"): every FINALIZED split time
				// above is a plain real-timestamp subtraction and stays that way on purpose (a real, correct
				// elapsed-time measurement for PB comparisons, immune to client stutter — see this class's own
				// doc comment on why that's right). The one still-actively-counting split (this branch, whose
				// end trigger hasn't fired yet) is different: it's the number the player is watching climb in
				// real time while waiting on the next chat line, and during genuine SERVER lag the real event
				// it's counting toward hasn't actually happened yet either — same "misaligns"/"doesn't stop for
				// lag" class of report DungeonTimersFeature.lagAdjustedElapsed() already fixed for its own
				// future-event countdowns. Scaling just this live preview by the same TpsMonitor-based TPS
				// ratio makes it visibly slow down/pause during a real lag spike instead of blazing through
				// wall-clock time regardless — the eventual FINAL value once the real trigger lands is still
				// computed fresh from real timestamps up above, so PB accuracy is untouched.
				times[i] = lagAdjustedElapsed(latestTime - group.splits.get(i).time);
				break;
			}
		}
		return times;
	}

	/** See the doc comment at computeTimes()'s live-split branch above — same TpsMonitor-ratio scaling
	 *  DungeonTimersFeature.lagAdjustedElapsed() already uses for its own future-event countdowns. */
	private static long lagAdjustedElapsed(long realElapsedMillis) {
		double tps = com.cokelord.skyblocksimplified.util.TpsMonitor.getEstimatedTps();
		return Math.round(realElapsedMillis * (tps / 20.0));
	}

	private void chatMessage(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(message));
	}

	private static String formatTime(long millis) {
		if (millis == 0L) return "0s";
		long remaining = millis;
		long hours = remaining / 3600000L; remaining -= hours * 3600000L;
		long minutes = remaining / 60000L; remaining -= minutes * 60000L;
		float seconds = remaining / 1000f;
		StringBuilder sb = new StringBuilder();
		if (hours > 0) sb.append(hours).append("h ");
		if (minutes > 0) sb.append(minutes).append("m ");
		sb.append(String.format(java.util.Locale.ROOT, "%.2f", seconds)).append('s');
		return sb.toString();
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() { return isEnabled() && IslandGate.isOnHypixel() && !currentSplits.splits.isEmpty(); }

	@Override
	public boolean isRelevantToCurrentIsland() { return isSplitLocationRelevant(); }

	@Override
	public boolean hasVisibleContent() { return !currentSplits.splits.isEmpty(); }

	/** Mirrors the same Dungeons/Kuudra/Both branching the actual split-tracking logic already uses (see
	 *  the splitLocation checks elsewhere in this class) — so the edit screen only shows this widget while
	 *  standing somewhere the player's current Split Location setting actually tracks. */
	private boolean isSplitLocationRelevant() {
		boolean dungeonOk = splitLocation != SplitLocation.KUUDRA_ONLY && DungeonState.isInDungeon();
		boolean kuudraOk = splitLocation != SplitLocation.DUNGEONS_ONLY && IslandGate.isInKuudra();
		return dungeonOk || kuudraOk;
	}

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		if (currentSplits.splits.isEmpty()) return new Size(0, 0);

		long[] times = computeTimes(currentSplits);
		int rowCount = currentSplits.splits.size() - 1 + (bossEntrySplit && currentSplits.splits.size() > 3 ? 1 : 0);
		int maxNameWidth = 0;
		for (int i = 0; i < currentSplits.splits.size() - 1; i++) maxNameWidth = Math.max(maxNameWidth, font.width(currentSplits.splits.get(i).name));
		if (bossEntrySplit) maxNameWidth = Math.max(maxNameWidth, font.width("§9Boss Entry"));

		String widestTimeSample = "0h 00m 00.00s" + (showTickTime ? " (00.00)" : "");
		int totalWidth = maxNameWidth + 4 + font.width(widestTimeSample) + 8;
		int rowHeight = font.lineHeight + 1;
		int panelHeight = rowHeight * Math.max(1, rowCount) + 4;

		// scale used to be accepted but never applied — the edit screen's box (built from this same method's
		// returned Size) would grow/shrink with the drag handle while the actual in-game panel stayed fixed
		// at 1x, so a resized widget no longer matched its own edit-mode outline. Wrapping the whole draw in
		// a pose transform (rather than scaling every font.width/rowHeight by hand) keeps the row-layout math
		// above untouched and correct at any scale.
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale);
		graphics.pose().translate(-x, -y);

		RenderUtil.fillRounded(graphics, x, y, x + totalWidth, y + panelHeight, 3, backgroundColor);
		if (outlineEnabled && outlineThickness > 0) {
			RenderUtil.fillRoundedRing(graphics, x, y, x + totalWidth, y + panelHeight, 3, outlineThickness, outlineColor);
		}

		int row = 0;
		for (int i = 0; i < currentSplits.splits.size() - 1; i++) {
			Split split = currentSplits.splits.get(i);
			String timeText = formatTime(i < times.length ? times[i] : 0L);
			String displayText = showTickTime ? timeText + " §8(§7" + String.format(java.util.Locale.ROOT, "%.2f", (matchedTicks(i) / 20f)) + "§8)" : timeText;
			int rowY = y + 2 + row * rowHeight;
			graphics.text(font, split.name, x + 4, rowY, 0xFFFFFFFF);
			int timeX = fixedWidth ? x + totalWidth - 4 - font.width(displayText) : x + 4 + maxNameWidth + 4;
			graphics.text(font, displayText, timeX, rowY, 0xFFFFFFFF);
			row++;
		}
		if (bossEntrySplit && currentSplits.splits.size() > 3) {
			long bossEntryTotal = (times.length > 0 ? times[0] : 0) + (times.length > 1 ? times[1] : 0) + (times.length > 2 ? times[2] : 0);
			String timeText = formatTime(bossEntryTotal);
			int rowY = y + 2 + row * rowHeight;
			graphics.text(font, "§9Boss Entry", x + 4, rowY, 0xFFFFFFFF);
			int timeX = fixedWidth ? x + totalWidth - 4 - font.width(timeText) : x + 4 + maxNameWidth + 4;
			graphics.text(font, timeText, timeX, rowY, 0xFFFFFFFF);
		}

		graphics.pose().popMatrix();
		return new Size(Math.round(totalWidth * scale), Math.round(panelHeight * scale));
	}

	/** Per-split tick duration (ticks elapsed FROM this split TO the next one, or to now if the next one
	 *  hasn't happened yet) — the tick-unit equivalent of computeTimes()'s millis durations, not a
	 *  cumulative-from-run-start count. */
	private long matchedTicks(int splitIndex) {
		if (splitIndex + 1 >= currentSplits.splits.size()) return 0L;
		Split current = currentSplits.splits.get(splitIndex);
		Split next = currentSplits.splits.get(splitIndex + 1);
		if (next.time == 0L) return tickCounter - current.ticks;
		return next.ticks - current.ticks;
	}

	public boolean isFixedWidth() { return fixedWidth; }
	public void setFixedWidth(boolean value) { fixedWidth = value; }
	public boolean isBossEntrySplit() { return bossEntrySplit; }
	public void setBossEntrySplit(boolean value) { bossEntrySplit = value; }
	public boolean isSendSplits() { return sendSplits; }
	public void setSendSplits(boolean value) { sendSplits = value; }
	public boolean isShowTickTime() { return showTickTime; }
	public void setShowTickTime(boolean value) { showTickTime = value; }
	public SplitLocation getSplitLocation() { return splitLocation; }
	public void setSplitLocation(SplitLocation value) { splitLocation = value; }
	public int getBackgroundColor() { return backgroundColor; }
	public void setBackgroundColor(int value) { backgroundColor = value; }
	public boolean isOutlineEnabled() { return outlineEnabled; }
	public void setOutlineEnabled(boolean value) { outlineEnabled = value; }
	public int getOutlineColor() { return outlineColor; }
	public void setOutlineColor(int value) { outlineColor = value; }
	public int getOutlineThickness() { return outlineThickness; }
	public void setOutlineThickness(int value) { outlineThickness = Math.max(1, value); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("fixedWidth", fixedWidth);
		obj.addProperty("bossEntrySplit", bossEntrySplit);
		obj.addProperty("sendSplits", sendSplits);
		obj.addProperty("showTickTime", showTickTime);
		obj.addProperty("splitLocation", splitLocation.name());
		obj.addProperty("backgroundColor", backgroundColor);
		obj.addProperty("outlineEnabled", outlineEnabled);
		obj.addProperty("outlineColor", outlineColor);
		obj.addProperty("outlineThickness", outlineThickness);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when i
		// relaunch"): anchorX/anchorY were saved but scale never was, so resizing this widget via "Edit gui
		// locations" was always lost on relaunch.
		obj.addProperty("scale", position.scale);
		JsonObject pbsObj = new JsonObject();
		for (Map.Entry<String, PersonalBest> entry : personalBests.entrySet()) {
			JsonObject pbData = new JsonObject();
			for (Map.Entry<String, Float> pbEntry : entry.getValue().data.entrySet()) pbData.addProperty(pbEntry.getKey(), pbEntry.getValue());
			pbsObj.add(entry.getKey(), pbData);
		}
		obj.add("personalBests", pbsObj);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("fixedWidth")) fixedWidth = obj.get("fixedWidth").getAsBoolean();
		if (obj.has("bossEntrySplit")) bossEntrySplit = obj.get("bossEntrySplit").getAsBoolean();
		if (obj.has("sendSplits")) sendSplits = obj.get("sendSplits").getAsBoolean();
		if (obj.has("showTickTime")) showTickTime = obj.get("showTickTime").getAsBoolean();
		if (obj.has("splitLocation")) { try { splitLocation = SplitLocation.valueOf(obj.get("splitLocation").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("backgroundColor")) backgroundColor = obj.get("backgroundColor").getAsInt();
		if (obj.has("outlineEnabled")) outlineEnabled = obj.get("outlineEnabled").getAsBoolean();
		if (obj.has("outlineColor")) outlineColor = obj.get("outlineColor").getAsInt();
		if (obj.has("outlineThickness")) outlineThickness = obj.get("outlineThickness").getAsInt();
		if (obj.has("anchorX")) position.anchorX = obj.get("anchorX").getAsFloat();
		if (obj.has("anchorY")) position.anchorY = obj.get("anchorY").getAsFloat();
		if (obj.has("scale")) position.scale = obj.get("scale").getAsFloat();
		if (obj.has("personalBests") && obj.get("personalBests").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("personalBests").entrySet()) {
				PersonalBest pb = personalBests.get(entry.getKey());
				if (pb == null || !entry.getValue().isJsonObject()) continue;
				for (Map.Entry<String, JsonElement> pbEntry : entry.getValue().getAsJsonObject().entrySet()) {
					pb.data.put(pbEntry.getKey(), pbEntry.getValue().getAsFloat());
				}
			}
		}
	}

	@Override
	public String getDescription() {
		return "Visual split timers for Kuudra and Dungeon runs, with personal-best comparisons for each split.";
	}
}
