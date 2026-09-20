package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * {@code /termsim [ping]} — an offline practice terminal, ported from Odin's {@code termsim}/{@code termGUI}
 * command. Opens a real vanilla chest-style GUI titled to match one of {@link TerminalTracker}'s own regex
 * patterns, so {@link TerminalTracker} (and therefore the real {@code TerminalSolverFeature} overlay) treats
 * it exactly like a real dungeon terminal — no separate "fake solver" needed, the real one just works on it.
 *
 * <p>Unlike a real terminal, this container is never opened by the server — there is no real containerId to
 * click into. Every click is captured and vetoed at {@link ContainerClickRegistry}'s existing choke point
 * (same one Experiment Addons' misclick-prevention uses) so the vanilla click packet never fires, and the
 * "server response" is instead simulated locally: the requested {@code ping} in milliseconds is how long
 * this controller waits before applying the click's effect to the fake inventory, mirroring the round-trip
 * delay the real terminal would have. This keeps termsim 100% client-local (matches this mod's standing rule
 * against spoofing anything server-authoritative) while still letting the solver overlay + click-blocking be
 * exercised for real.
 *
 * <p>Per explicit user request/audit: only one click may be "in flight" (simulating that round trip) at a
 * time — see {@link #onClick}. Clicking a second button before the first one has actually landed is simply
 * ignored, not queued for a later burst-apply. This is a deliberate, explicit design constraint, not an
 * accident: a "queue several clicks, then fire them all at once once some timer expires" mechanic is a real
 * cheat pattern seen in at least one other Skyblock mod's source (an adjustable/zero-able artificial delay
 * plus click-batching, sometimes called "zero ping terminals") — this controller has never done that
 * batching, and this note exists specifically so it never quietly grows into it later.
 *
 * <p>Melody ("Click the button on time!") is simulated too: a fake moving purple/magenta pane sweeps back
 * and forth across whichever row is the current attempt, same shape {@link TerminalTracker#getMelodyState()}
 * expects from a real one. The exact movement speed/pattern is this controller's own invention, not
 * confirmed real Hypixel timing — good enough to practice the "click when aligned" reaction, not a byte-
 * for-byte replica.
 */
public final class TermSimController {
	private static final int CONTAINER_ID = -777;
	private static final Random RANDOM = new Random();
	private static final String[] AZ_WORDS = {
		"Apple", "Banana", "Candle", "Drum", "Engine", "Feather", "Guitar", "Hammer", "Igloo", "Jacket",
		"Kite", "Lantern", "Mirror", "Nail", "Orange", "Pillow", "Quiver", "Rocket", "Saddle", "Turtle",
		"Umbrella", "Violin", "Wagon", "Xylophone", "Yarn", "Zipper"
	};

	private record PendingClick(long applyAtMs, int slotIndex, boolean leftClick) {}

	// One column step every 10 ticks (500ms) and a sweep range of columns 1-5 — matches Odin's own
	// termsim/MelodySim.kt exactly (a working, extensively-tested reference), not a guess.
	private static final int MELODY_COLUMN_STEP_TICKS = 10;
	// 9 columns per row — every terminal type here is played in a chest-shaped grid of some row count.
	private static final int ROW_WIDTH = 9;
	// Button sits one slot in from the right edge (column 7) — confirmed both by the user checking the real
	// terminal and by Odin's own hardcoded button slots {16,25,34,43} = row*9+7 for rows 1-4.
	private static final int MELODY_BUTTON_COLUMN = ROW_WIDTH - 2;
	private static final int MELODY_SWEEP_MIN = 1;
	private static final int MELODY_SWEEP_MAX = 5;
	// Real Hypixel freezes the moving light for about a second as the penalty for a wrong click — never
	// blocks the click itself (Melody is deliberately excluded from Block Wrong Clicks, see
	// TerminalSolverFeature.shouldBlockClick's own comment; no known terminal solver blocks Melody clicks).
	private static final int MELODY_WRONG_CLICK_FREEZE_TICKS = 20;

	private static TerminalTracker.TerminalType simType;
	private static SimpleContainer simContainer;
	private static int simWindowSize;
	private static int simPingMs;
	private static final Set<Integer> simCorrectSet = new HashSet<>();
	private static final List<Integer> simOrderedCorrect = new ArrayList<>();
	private static final List<PendingClick> pendingClicks = new ArrayList<>();
	private static int melodyTargetColumn;
	private static int melodyMovingRow;
	private static int melodyMovingColumn;
	private static int melodyMovingDirection;
	private static int melodyTickCounter;
	private static int melodyFreezeTicksRemaining;
	private static boolean registered = false;

	private TermSimController() {}

	/** True (and click applied locally) if a termsim session is active and this was its screen — false if
	 *  there's no termsim session at all, so a caller should treat the click as real. Checked against the
	 *  live screen, not just "a session was started at some point" — if a real Hypixel screen ever silently
	 *  replaces this fake one (e.g. the server opens something while it's up), Minecraft#setScreen calls the
	 *  outgoing screen's removed(), not onClose(), so this must not rely on state that only onClose() would
	 *  have cleared.
	 *
	 *  <p>Called two ways, both landing here: (1) the registered {@code ContainerClickRegistry} "termsim"
	 *  rule, for a real vanilla click routed through {@code ContainerSlotClickMixin} (Custom Terminal GUI
	 *  mode off); (2) directly from {@code TerminalSolverFeature.dispatchPanelClick} (Custom Terminal GUI
	 *  mode on). The direct call exists because of a real bug found: dispatchPanelClick used to reach this
	 *  through the SAME shared registry path as (1), which also holds "terminal_solver"'s own rule (also
	 *  calling shouldBlockClick) in the same map — for a real terminal that's a harmless redundant call, but
	 *  for termsim it silently poisoned the SECOND evaluation of the exact same click:
	 *  shouldBlockClick's first call (from dispatchPanelClick's own pre-check) already marked the slot
	 *  "recently clicked", so by the time this method's own shouldBlockTerminalClick check ran a moment
	 *  later, isCorrectSlot() had already flipped to false for that same slot — which made this method think
	 *  the click was wrong and skip queuing it into pendingClicks entirely. The optimistic "recently clicked"
	 *  hide still fired from the FIRST call, so the slot visually looked solved for its ~600ms grace window,
	 *  then reverted the moment that expired since the underlying fake item was never actually changed —
	 *  exactly "clicks respawning". Calling this directly bypasses that shared registry (and therefore
	 *  "terminal_solver"'s redundant rule) entirely, so shouldBlockClick only ever runs once per click. */
	public static boolean handlePanelClick(int slotId, boolean rightClick) {
		if (simContainer == null || !(Minecraft.getInstance().gui.screen() instanceof TermSimScreen)) return false;
		try {
			onClick(slotId, rightClick);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("TermSimController click handling failed, ignoring this click", e);
		}
		return true;
	}

	public static synchronized void register() {
		if (registered) return;
		registered = true;

		ContainerClickRegistry.setRule("termsim", (slot, slotId, mouseButton, type) -> handlePanelClick(slotId, mouseButton == 1));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tick();
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("TermSimController tick failed, skipping this tick", e);
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommands.literal("termsim")
				.executes(ctx -> {
					startRandom(ctx.getSource(), 0);
					return 1;
				})
				.then(ClientCommands.argument("ping", IntegerArgumentType.integer(0, 5000))
					.executes(ctx -> {
						startRandom(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ping"));
						return 1;
					}))
				// Per user request ("/termsim terminal ping, for example /termsim melody 150") — picking a
				// random type every time was "rather annoying" for testing one specific terminal repeatedly.
				// A sibling argument node, not nested under "ping" — Brigadier tries both an integer and a
				// word at the same position and picks whichever actually parses, so "/termsim 200" (random,
				// ping 200) and "/termsim melody" (named, ping 0) both still resolve unambiguously.
				.then(ClientCommands.argument("terminal", StringArgumentType.word())
					.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(TERMINAL_NAMES.keySet(), builder))
					.executes(ctx -> {
						startNamed(ctx.getSource(), StringArgumentType.getString(ctx, "terminal"), 0);
						return 1;
					})
					.then(ClientCommands.argument("ping", IntegerArgumentType.integer(0, 5000))
						.executes(ctx -> {
							startNamed(ctx.getSource(), StringArgumentType.getString(ctx, "terminal"), IntegerArgumentType.getInteger(ctx, "ping"));
							return 1;
						})))));
	}

	private static final java.util.Map<String, TerminalTracker.TerminalType> TERMINAL_NAMES = java.util.Map.of(
		"panes", TerminalTracker.TerminalType.PANES,
		"rubix", TerminalTracker.TerminalType.RUBIX,
		"numbers", TerminalTracker.TerminalType.NUMBERS,
		"startswith", TerminalTracker.TerminalType.STARTS_WITH,
		"select", TerminalTracker.TerminalType.SELECT,
		"melody", TerminalTracker.TerminalType.MELODY
	);

	private static void startRandom(FabricClientCommandSource source, int pingMs) {
		TerminalTracker.TerminalType[] pool = {
			TerminalTracker.TerminalType.PANES, TerminalTracker.TerminalType.RUBIX, TerminalTracker.TerminalType.NUMBERS,
			TerminalTracker.TerminalType.STARTS_WITH, TerminalTracker.TerminalType.SELECT, TerminalTracker.TerminalType.MELODY
		};
		startType(source, pool[RANDOM.nextInt(pool.length)], pingMs);
	}

	private static void startNamed(FabricClientCommandSource source, String name, int pingMs) {
		TerminalTracker.TerminalType type = TERMINAL_NAMES.get(name.toLowerCase(Locale.ROOT));
		if (type == null) {
			source.sendError(Component.literal("§cUnknown terminal '" + name + "'. Try: " + String.join(", ", TERMINAL_NAMES.keySet())));
			return;
		}
		startType(source, type, pingMs);
	}

	private static void startType(FabricClientCommandSource source, TerminalTracker.TerminalType type, int pingMs) {
		// Per user request, same reasoning as Positional Messages' singleplayer carve-out: this is purely a
		// practice tool, and the real Terminal Solver overlay/click-blocking it exercises has no business
		// running against a fake local GUI while actually connected to real Hypixel.
		if (!com.cokelord.skyblocksimplified.util.IslandGate.isSingleplayer()) {
			source.sendError(Component.literal("§ctermsim only works in singleplayer."));
			return;
		}
		Minecraft mc = source.getClient();
		if (mc.player == null) return;
		// Deferred to next tick: setScreen() called synchronously from inside a chat-command's executes()
		// callback loses a race against vanilla's own "close the chat screen after submitting" logic, which
		// runs right after and would silently null out the screen we just opened. mc.execute() queues this
		// for after that finishes, which is the standard fix for "my command's GUI closes immediately".
		mc.execute(() -> {
			try {
				open(mc, type, pingMs);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("TermSimController failed to open a simulated terminal", e);
				mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§cFailed to open termsim: " + e));
			}
		});
		source.sendFeedback(Component.literal("§aOpened a simulated §e" + typeLabel(type) + "§a terminal (ping: " + pingMs + "ms)."));
	}

	private static String typeLabel(TerminalTracker.TerminalType type) {
		return switch (type) {
			case PANES -> "Correct all the panes";
			case RUBIX -> "Change all to same color";
			case NUMBERS -> "Click in order";
			case STARTS_WITH -> "What starts with";
			case SELECT -> "Select all the items";
			case MELODY -> "Melody";
		};
	}

	private static void open(Minecraft mc, TerminalTracker.TerminalType type, int pingMs) {
		reset();
		simType = type;
		simPingMs = pingMs;

		String title;
		int rows;
		switch (type) {
			case PANES -> {
				title = "Correct all the panes!";
				rows = 5;
				simWindowSize = 45;
				simContainer = new SimpleContainer(simWindowSize);
				generatePanes();
			}
			case RUBIX -> {
				title = "Change all to same color!";
				rows = 5;
				simWindowSize = 45;
				simContainer = new SimpleContainer(simWindowSize);
				generateRubix();
			}
			case NUMBERS -> {
				title = "Click in order!";
				rows = 4;
				simWindowSize = 36;
				simContainer = new SimpleContainer(simWindowSize);
				generateNumbers();
			}
			case STARTS_WITH -> {
				char letter = (char) ('A' + RANDOM.nextInt(26));
				title = "What starts with: '" + letter + "'?";
				rows = 5;
				simWindowSize = 45;
				simContainer = new SimpleContainer(simWindowSize);
				generateStartsWith(letter);
			}
			case SELECT -> {
				DyeColor color = DyeColor.values()[RANDOM.nextInt(DyeColor.values().length)];
				String colorWord = color.getName().toLowerCase(Locale.ROOT).replace('_', ' ');
				title = "Select all the " + colorWord + " items!";
				rows = 6;
				simWindowSize = 54;
				simContainer = new SimpleContainer(simWindowSize);
				generateSelect(color);
			}
			case MELODY -> {
				title = "Click the button on time!";
				rows = 6;
				simWindowSize = 54;
				simContainer = new SimpleContainer(simWindowSize);
				generateMelody();
			}
			default -> {
				return;
			}
		}

		MenuType<ChestMenu> menuType = switch (rows) {
			case 4 -> MenuType.GENERIC_9x4;
			case 6 -> MenuType.GENERIC_9x6;
			default -> MenuType.GENERIC_9x5;
		};
		ChestMenu menu = new ChestMenu(menuType, CONTAINER_ID, mc.player.getInventory(), simContainer, rows);
		mc.gui.setScreen(new TermSimScreen(menu, mc.player.getInventory(), Component.literal(title)));
	}

	private static void reset() {
		simType = null;
		simContainer = null;
		simWindowSize = 0;
		simCorrectSet.clear();
		simOrderedCorrect.clear();
		pendingClicks.clear();
		melodyTargetColumn = 0;
		melodyMovingRow = 0;
		melodyMovingColumn = 0;
		melodyMovingDirection = 1;
		melodyTickCounter = 0;
		melodyFreezeTicksRemaining = 0;
	}

	private static ItemStack blackPane() {
		return new ItemStack(Blocks.STAINED_GLASS_PANE.pick(DyeColor.BLACK));
	}

	private static ItemStack pane(DyeColor color) {
		return new ItemStack(Blocks.STAINED_GLASS_PANE.pick(color));
	}

	private static ItemStack terracotta(DyeColor color) {
		return new ItemStack(Blocks.DYED_TERRACOTTA.pick(color));
	}

	/** The real terminal's lock-in button (row*9+7, rows 1-4 — same hardcoded slots TerminalTracker's
	 *  MelodyState uses for click-gating) was never actually rendered in termsim, just clickable — this
	 *  fills it in with the real block: green terracotta on whichever row is the current attempt, red on
	 *  the other three, matching the user's own report of what the real terminal shows. */
	private static void updateMelodyButtons() {
		for (int row = 1; row <= 4; row++) {
			simContainer.setItem(row * ROW_WIDTH + MELODY_BUTTON_COLUMN, terracotta(row == melodyMovingRow ? DyeColor.GREEN : DyeColor.RED));
		}
	}

	private static ItemStack named(String name, boolean foil) {
		ItemStack stack = new ItemStack(net.minecraft.world.item.Items.PAPER);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		if (foil) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
		return stack;
	}

	/** Only the middle 3x5 of the 5x9 window is real — confirmed against Odin's own TerminalTypes.kt
	 *  (simpleTermGui(rows = 3, cols = 5, startRow = 1, startCol = 2)), same sub-region pattern RUBIX's
	 *  3x3-within-5x9 and NUMBERS' 2x7-within-4x9 already use. The rest stays black filler instead of the
	 *  previous full-window fill. 25% red / 75% green matches PanesSim.kt's own ratio exactly. */
	private static void generatePanes() {
		for (int i = 0; i < simWindowSize; i++) simContainer.setItem(i, blackPane());
		for (int row = 1; row <= 3; row++) {
			for (int col = 2; col <= 6; col++) {
				boolean wrong = RANDOM.nextInt(100) < 25;
				int slot = row * ROW_WIDTH + col;
				simContainer.setItem(slot, pane(wrong ? DyeColor.RED : DyeColor.GREEN));
				if (wrong) simCorrectSet.add(slot);
			}
		}
	}

	/** Only the middle 3x3 of the 5x9 window is real — confirmed against Odin's own TerminalTypes.kt
	 *  (simpleTermGui(rows = 3, cols = 3, startRow = 1, startCol = 3)), the rest stays black filler
	 *  (paneColorOf ignores black, so it's already excluded from the solver regardless). */
	private static void generateRubix() {
		for (int i = 0; i < simWindowSize; i++) simContainer.setItem(i, blackPane());
		for (int row = 1; row <= 3; row++) {
			for (int col = 3; col <= 5; col++) {
				DyeColor color = TerminalTracker.RUBIX_ORDER[RANDOM.nextInt(TerminalTracker.RUBIX_ORDER.length)];
				simContainer.setItem(row * ROW_WIDTH + col, pane(color));
			}
		}
	}

	private static void generateNumbers() {
		for (int i = 0; i < simWindowSize; i++) simContainer.setItem(i, blackPane());
		// Real terminal is a 4x9 GUI but only the middle 2 rows AND middle 7 columns are ever used — the
		// outermost column on each side (col 0 and col 8) never gets a number, same as the outermost two
		// rows. That's exactly 14 slots (2x7), and per the user's report every one of them always has a
		// number — no randomness in WHICH slots are used, only in which number lands on which slot.
		int count = 14;
		List<Integer> slots = new ArrayList<>();
		for (int row = 1; row <= 2; row++) {
			for (int col = 1; col <= 7; col++) {
				slots.add(row * ROW_WIDTH + col);
			}
		}
		List<Integer> numbers = new ArrayList<>();
		for (int i = 1; i <= count; i++) numbers.add(i);
		java.util.Collections.shuffle(numbers, RANDOM);
		for (int i = 0; i < count; i++) {
			ItemStack stack = pane(DyeColor.RED);
			stack.setCount(numbers.get(i));
			simContainer.setItem(slots.get(i), stack);
		}
		// simOrderedCorrect must reflect ascending click order regardless of the shuffled slot assignment.
		List<Integer> ordered = new ArrayList<>(slots);
		ordered.sort((a, b) -> Integer.compare(simContainer.getItem(a).getCount(), simContainer.getItem(b).getCount()));
		simOrderedCorrect.addAll(ordered);
	}

	private static void generateStartsWith(char letter) {
		for (int i = 0; i < simWindowSize; i++) simContainer.setItem(i, blackPane());
		String matchWord = AZ_WORDS[letter - 'A'];
		// Only rows 1-3, cols 1-7 of the 5x9 window are real — confirmed against Odin's own
		// TerminalTypes.kt (simpleTermGui(rows = 3, cols = 7, startRow = 1, startCol = 1)).
		List<Integer> region = new ArrayList<>();
		for (int row = 1; row <= 3; row++) {
			for (int col = 1; col <= 7; col++) {
				region.add(row * ROW_WIDTH + col);
			}
		}
		List<Integer> slots = new ArrayList<>();
		int total = 10 + RANDOM.nextInt(6);
		while (slots.size() < total) {
			int slot = region.get(RANDOM.nextInt(region.size()));
			if (!slots.contains(slot)) slots.add(slot);
		}
		int correctCount = 3 + RANDOM.nextInt(4);
		for (int i = 0; i < slots.size(); i++) {
			int slot = slots.get(i);
			if (i < correctCount) {
				simContainer.setItem(slot, named(matchWord, false));
				simCorrectSet.add(slot);
			} else if (i == correctCount && RANDOM.nextBoolean()) {
				// A foiled decoy that DOES start with the right letter but must stay excluded — exercises
				// the solver's hasFoil() check, not just the plain name-prefix match.
				simContainer.setItem(slot, named(matchWord, true));
			} else {
				simContainer.setItem(slot, named(decoyWordAvoiding(Set.of(String.valueOf(Character.toLowerCase(letter)))), false));
			}
		}
	}

	private static void generateSelect(DyeColor color) {
		for (int i = 0; i < simWindowSize; i++) simContainer.setItem(i, blackPane());
		Set<String> prefixes = TerminalTracker.selectPrefixesFor(color.getName().toLowerCase(Locale.ROOT).replace('_', ' '));
		String prefix = prefixes.iterator().next();
		String matchName = Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1) + " Item";
		// Only rows 1-4, cols 1-7 of the 6x9 window are real — confirmed against Odin's own
		// TerminalTypes.kt (simpleTermGui(rows = 4, cols = 7, startRow = 1, startCol = 1)) and
		// SelectAllSim.kt's own generation range.
		List<Integer> region = new ArrayList<>();
		for (int row = 1; row <= 4; row++) {
			for (int col = 1; col <= 7; col++) {
				region.add(row * ROW_WIDTH + col);
			}
		}
		List<Integer> slots = new ArrayList<>();
		int total = 12 + RANDOM.nextInt(8);
		while (slots.size() < total) {
			int slot = region.get(RANDOM.nextInt(region.size()));
			if (!slots.contains(slot)) slots.add(slot);
		}
		int correctCount = 4 + RANDOM.nextInt(5);
		for (int i = 0; i < slots.size(); i++) {
			int slot = slots.get(i);
			if (i < correctCount) {
				simContainer.setItem(slot, named(matchName, false));
				simCorrectSet.add(slot);
			} else if (i == correctCount) {
				// Correct-prefix but foiled — must stay excluded, same solver edge case as STARTS_WITH.
				simContainer.setItem(slot, named(matchName, true));
			} else if (i == correctCount + 1) {
				// Correct-prefix but on a black pane — must also stay excluded per the real select rule.
				simContainer.setItem(slot, pane(DyeColor.BLACK));
			} else {
				simContainer.setItem(slot, named(decoyWordAvoiding(prefixes) + " Item", false));
			}
		}
	}

	/** Row 1 gets one fixed magenta target pane at a random column within the reachable sweep range; row 2
	 *  (melodyMovingRow starts at 1, the first attempt row) gets the lime moving pane at the leftmost
	 *  reachable column, ready to start sweeping in {@link #tickMelody()}. Colors match Odin's real
	 *  MelodyHandler.kt (MAGENTA target / LIME moving), not a guess. */
	private static void generateMelody() {
		for (int i = 0; i < simWindowSize; i++) simContainer.setItem(i, blackPane());
		melodyTargetColumn = MELODY_SWEEP_MIN + RANDOM.nextInt(MELODY_SWEEP_MAX - MELODY_SWEEP_MIN + 1);
		simContainer.setItem(melodyTargetColumn, pane(DyeColor.MAGENTA));
		melodyMovingRow = 1;
		melodyMovingColumn = MELODY_SWEEP_MIN;
		melodyMovingDirection = 1;
		melodyTickCounter = 0;
		// The active row's sweep track is red (except the lime marker's own cell), the other three attempt
		// rows are white — confirmed against Odin's own MelodySim.kt generateItemStack(), which was left out
		// entirely before (black/empty), not matching the real terminal's appearance at all.
		fillMelodyRow(melodyMovingRow, DyeColor.RED, melodyMovingColumn);
		for (int row = 1; row <= 4; row++) if (row != melodyMovingRow) fillMelodyRow(row, DyeColor.WHITE, -1);
		simContainer.setItem(melodyMovingRow * ROW_WIDTH + melodyMovingColumn, pane(DyeColor.LIME));
		updateMelodyButtons();
	}

	/** Fills one attempt row's sweep track (columns {@link #MELODY_SWEEP_MIN}-{@link #MELODY_SWEEP_MAX})
	 *  with a solid color, skipping skipColumn (the lime marker's own cell, or -1 for none). */
	private static void fillMelodyRow(int row, DyeColor color, int skipColumn) {
		for (int col = MELODY_SWEEP_MIN; col <= MELODY_SWEEP_MAX; col++) {
			if (col == skipColumn) continue;
			simContainer.setItem(row * ROW_WIDTH + col, pane(color));
		}
	}

	/** Bounces the moving pane back and forth across its current row, one column every
	 *  {@link #MELODY_COLUMN_STEP_TICKS} ticks — called unconditionally every tick while a Melody session
	 *  is open, independent of the ping-delay click queue, same as how the real marker would keep moving
	 *  regardless of the player's connection. */
	private static void tickMelody() {
		if (simType != TerminalTracker.TerminalType.MELODY || simContainer == null) return;
		if (melodyFreezeTicksRemaining > 0) {
			melodyFreezeTicksRemaining--;
			return;
		}
		melodyTickCounter++;
		if (melodyTickCounter < MELODY_COLUMN_STEP_TICKS) return;
		melodyTickCounter = 0;
		// The vacated cell rejoins the active row's red track (not black/empty — see generateMelody()'s
		// own comment) once the lime marker moves off it.
		simContainer.setItem(melodyMovingRow * ROW_WIDTH + melodyMovingColumn, pane(DyeColor.RED));
		melodyMovingColumn += melodyMovingDirection;
		if (melodyMovingColumn >= MELODY_SWEEP_MAX) {
			melodyMovingColumn = MELODY_SWEEP_MAX;
			melodyMovingDirection = -1;
		} else if (melodyMovingColumn <= MELODY_SWEEP_MIN) {
			melodyMovingColumn = MELODY_SWEEP_MIN;
			melodyMovingDirection = 1;
		}
		simContainer.setItem(melodyMovingRow * ROW_WIDTH + melodyMovingColumn, pane(DyeColor.LIME));
	}

	/** Picks an AZ_WORDS decoy that doesn't itself start with one of the target color's own accepted
	 *  prefixes (e.g. avoids "Orange" as a decoy when the terminal's target color is orange) — otherwise
	 *  the real solver would highlight it as correct while this controller's own click bookkeeping
	 *  wouldn't, since it was never added to simCorrectSet. */
	private static String decoyWordAvoiding(Set<String> prefixes) {
		for (int attempt = 0; attempt < 50; attempt++) {
			String word = AZ_WORDS[RANDOM.nextInt(AZ_WORDS.length)];
			String lower = word.toLowerCase(Locale.ROOT);
			boolean collides = false;
			for (String prefix : prefixes) {
				if (lower.startsWith(prefix) || prefix.startsWith(lower)) { collides = true; break; }
			}
			if (!collides) return word;
		}
		return "Bone";
	}

	// Real design correction (per user pushback — "regular minecraft allows you to queue a single click,
	// like i can click current then click next and it will click the next one after clicking current"):
	// this used to reject any click while a previous one was still awaiting its simulated round trip,
	// justified as avoiding the real SA-addons "high ping mode" cheat. That was conflating two different
	// things — the actual cheat pattern BATCH-FIRES every queued click the instant ANY ONE of them
	// resolves (making click #2's effective delay shorter than its own real round trip would allow); real
	// vanilla clicking never serializes clicks behind each other's response at all — every click sends its
	// own packet immediately and is processed independently, each with its own full round trip. Multiple
	// PendingClicks can coexist below now, but each one STILL waits out its own full simPingMs from its
	// own click time (see applyAt below) — nothing is shortened or batched, so this isn't the cheat
	// pattern the original design was guarding against, it's just no longer artificially serializing what
	// real networking already parallelizes. This is also why wouldOverclickRubix's own "how many are
	// already queued for this slot" check actually matters now, instead of being a permanent no-op.
	private static void onClick(int slotIndex, boolean rightClick) {
		if (simContainer == null || slotIndex < 0 || slotIndex >= simWindowSize) return;
		// Same "should this click even count" check the real Terminal Solver feature uses (direction-aware
		// for Rubix, first-click-protection window, etc.) — called directly rather than trusting
		// ContainerClickRegistry's rule-map ordering, which isn't guaranteed between "termsim" and
		// "terminal_solver" and previously let overclicking slip through even when it was correctly blocked
		// for real terminals.
		if (com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature.shouldBlockTerminalClick(slotIndex, rightClick ? 1 : 0)) return;
		if (simType == TerminalTracker.TerminalType.RUBIX && wouldOverclickRubix(slotIndex)) return;
		long applyAt = System.currentTimeMillis() + Math.max(0, simPingMs);
		pendingClicks.add(new PendingClick(applyAt, slotIndex, !rightClick));
	}

	private static boolean wouldOverclickRubix(int slotIndex) {
		for (TerminalTracker.RubixClick click : TerminalTracker.getRubixClicks()) {
			if (click.slotIndex() != slotIndex) continue;
			long alreadyQueued = pendingClicks.stream().filter(pc -> pc.slotIndex() == slotIndex).count();
			return alreadyQueued >= click.count();
		}
		return true; // Not in the solve list at all — already correct (or not a real pane) — block.
	}

	private static void tick() {
		tickMelody();
		if (pendingClicks.isEmpty() || simContainer == null) return;
		long now = System.currentTimeMillis();
		List<PendingClick> due = new ArrayList<>();
		pendingClicks.removeIf(click -> {
			if (click.applyAtMs() > now) return false;
			due.add(click);
			return true;
		});
		for (PendingClick click : due) applyClick(click.slotIndex(), click.leftClick());
	}

	// Fires the click animation/optimistic-hide only once a queued click actually lands here (its
	// simulated ping delay has elapsed) rather than the instant it was clicked — see
	// TerminalSolverFeature.shouldBlockTerminalClick's doc comment for the real bug this fixes ("animation
	// is instant, then reverts, then the button activates 5 seconds later" at a high simulated ping).
	private static void applyClick(int slotIndex, boolean leftClick) {
		if (simType == null) return;
		switch (simType) {
			case PANES, STARTS_WITH, SELECT -> {
				if (!leftClick || !simCorrectSet.remove(slotIndex)) return;
				simContainer.setItem(slotIndex, blackPane());
				com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature.applyTerminalClickEffects(slotIndex);
				if (simCorrectSet.isEmpty()) closeWithMessage("§aSimulated " + typeLabel(simType) + " terminal solved!");
			}
			case NUMBERS -> {
				if (!leftClick || simOrderedCorrect.isEmpty() || simOrderedCorrect.get(0) != slotIndex) return;
				simOrderedCorrect.remove(0);
				simContainer.setItem(slotIndex, blackPane());
				com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature.applyTerminalClickEffects(slotIndex);
				if (simOrderedCorrect.isEmpty()) closeWithMessage("§aSimulated " + typeLabel(simType) + " terminal solved!");
			}
			case RUBIX -> {
				DyeColor color = TerminalTracker.paneColorOf(simContainer.getItem(slotIndex));
				if (color == null) return;
				int idx = TerminalTracker.indexOf(TerminalTracker.RUBIX_ORDER, color);
				int order = TerminalTracker.RUBIX_ORDER.length;
				int newIdx = leftClick ? (idx + 1) % order : (idx + order - 1) % order;
				simContainer.setItem(slotIndex, pane(TerminalTracker.RUBIX_ORDER[newIdx]));
				com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature.applyTerminalClickEffects(slotIndex);
				if (isRubixSolved()) closeWithMessage("§aSimulated " + typeLabel(simType) + " terminal solved!");
			}
			case MELODY -> {
				int buttonSlot = melodyMovingRow * ROW_WIDTH + MELODY_BUTTON_COLUMN;
				if (!leftClick || slotIndex != buttonSlot) return;
				if (melodyMovingColumn != melodyTargetColumn) {
					// Wrong click: real Hypixel freezes the moving light for about a second as the penalty —
					// per user report, it's never blocked/prevented outright (no known terminal solver blocks
					// Melody clicks, see TerminalSolverFeature.shouldBlockClick's own MELODY exemption), you
					// just have to wait the freeze out before the sweep resumes and you can try again.
					melodyFreezeTicksRemaining = MELODY_WRONG_CLICK_FREEZE_TICKS;
					return;
				}
				com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature.applyTerminalClickEffects(slotIndex);
				int solvedRow = melodyMovingRow;
				if (melodyMovingRow >= 4) {
					fillMelodyRow(solvedRow, DyeColor.WHITE, -1);
					closeWithMessage("§aSimulated Melody terminal solved (4/4)!");
				} else {
					melodyMovingRow++;
					// Column/direction/tick-timing deliberately carry over unchanged from the row just
					// solved, instead of snapping back to MELODY_SWEEP_MIN with the tick counter zeroed —
					// per user report against real gameplay, that reset made every row transition visibly
					// freeze for up to MELODY_COLUMN_STEP_TICKS (~0.5s) and jump position, which also broke
					// spam-clicking through consecutive rows since the freshly reset column rarely already
					// matched the newly rolled target. The moving pane now sweeps continuously across all
					// four rows with no pause or jump.
					// The just-solved row's track turns white (it's no longer the active row — matches
					// Odin's MelodySim.kt), and the new active row's track turns red around the carried-over
					// lime marker column.
					fillMelodyRow(solvedRow, DyeColor.WHITE, -1);
					fillMelodyRow(melodyMovingRow, DyeColor.RED, melodyMovingColumn);
					// Per user report against real gameplay: the target column re-rolls on every new
					// attempt/row, it isn't fixed for the whole terminal — this used to only ever be set
					// once in generateMelody(), so termsim stayed on the same column the whole session
					// while real Hypixel visibly moves it each time. Clear the old target pane before
					// placing the new one so a stale magenta pane doesn't linger on the previous column.
					simContainer.setItem(melodyTargetColumn, blackPane());
					melodyTargetColumn = MELODY_SWEEP_MIN + RANDOM.nextInt(MELODY_SWEEP_MAX - MELODY_SWEEP_MIN + 1);
					simContainer.setItem(melodyTargetColumn, pane(DyeColor.MAGENTA));
					simContainer.setItem(melodyMovingRow * ROW_WIDTH + melodyMovingColumn, pane(DyeColor.LIME));
					updateMelodyButtons();
				}
			}
			default -> {}
		}
	}

	/** All non-black panes matching the same color — mirrors TerminalTracker's own win condition, computed
	 *  locally instead of reading TerminalTracker.getCurrentSolution() to avoid a race: that field only
	 *  updates once TerminalTracker's own tick() has scanned this frame's item state, which can lag one
	 *  tick behind the click that just solved it. */
	private static boolean isRubixSolved() {
		DyeColor first = null;
		for (int i = 0; i < simWindowSize; i++) {
			DyeColor color = TerminalTracker.paneColorOf(simContainer.getItem(i));
			if (color == null || color == DyeColor.BLACK) continue;
			if (first == null) first = color;
			else if (first != color) return false;
		}
		return first != null;
	}

	/** Prints a local (never sent anywhere) confirmation and closes the fake screen — matches the user's
	 *  "after a terminal is done it should auto close" for the real ones too, wired separately via
	 *  TerminalTracker's solve-chat listener in TerminalSolverFeature. */
	private static void closeWithMessage(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
		mc.gui.setScreen(null);
	}

	/** Real vanilla chest GUI, reused as-is for background/slot rendering. The only override is
	 *  {@code onClose()}: the vanilla default calls {@code minecraft.player.closeContainer()}, which sends a
	 *  real close-container packet referencing this fake, server-unknown containerId — skipped entirely in
	 *  favor of just clearing the screen, so termsim never sends the server anything at all. */
	private static class TermSimScreen extends ContainerScreen {
		TermSimScreen(ChestMenu menu, Inventory playerInventory, Component title) {
			super(menu, playerInventory, title);
		}

		@Override
		public void onClose() {
			Minecraft.getInstance().gui.setScreen(null);
		}

		@Override
		public void removed() {
			super.removed();
			// Backstop for both the normal ESC-close path (removed() always runs after onClose()'s
			// setScreen(null)) and the edge case where something else replaces this screen directly.
			reset();
		}
	}
}
