package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.MojangApi;
import com.cokelord.skyblocksimplified.api.SkyblockStatsApi;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.item.RomanNumeralUtil;
import com.cokelord.skyblocksimplified.party.PartyApi;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per user request (large batch spec, floor-by-floor "advanced mode" explicitly retracted — "Forget the
 * part i said about changing for each floor since its a bit complicated"): kicks a Party Finder joiner who
 * doesn't meet a configured minimum personal-best time for the currently-queued floor, and/or doesn't own a
 * configured set of items/pets ({@code Hyperion}/{@code Terminator}/Golden Dragon/1B+ bank).
 *
 * <p>Real "Group Builder" GUI slot layout (0-indexed) confirmed via a real, live, actively-maintained
 * dungeon mod's own working AutoKick feature (Devonian's {@code AutoKick.kt}, {@code onBuildingParty}) —
 * slot 11 holds the dungeon-type icon ("Currently Selected: The Catacombs" / "...Master Mode The
 * Catacombs"), slot 12 holds the floor icon ("Currently Selected: Floor VII"). Read once per real GUI open
 * (not every frame — per user request "The mod should not need to poll these"), with a short self-healing
 * re-check across the first few frames only, in case the real slot contents haven't arrived yet the instant
 * the screen opens (same "detection may lag a frame" caveat this codebase already documents for other
 * screen-open captures, e.g. PetDisplayFeature's own Pets-menu skin capture) — capture stops for good the
 * moment both slots have matched once, so this never re-polls after a successful read.
 *
 * <p>Real per-floor personal-best data comes from {@link SkyblockStatsApi}'s {@code FloorTimes} — see that
 * record's own doc comment for the confirmed real API fields and the S+/S/any-rank fallback rule this
 * applies exactly as specified by the user ("Make the personal best always go off S+ ranking, and S ranking
 * on mastermode/regular floor 4 and below... If the user has no S+ or S runs on the floor queued it should
 * detect any personal best timer, no matter the rank").
 *
 * <p>Real join-line detection and kick command mirror {@link PartyFinderFeature}'s own confirmed regex and
 * {@link ChatCommandsFeature}'s own confirmed {@code "p kick " + name} convention — gated on
 * {@link PartyApi#isLeader()} the same way, since {@code /p kick} silently does nothing (or errors) when
 * the local player isn't the real party leader. This feature runs its own independent join-line listener
 * (never cancels the line — that's {@link PartyFinderFeature}'s job alone) so autokick works whether or not
 * Party Finder Stats is separately enabled.
 *
 * <p>Real, honest gap left open per the user's own explicit permission ("Make it also hide the kicked
 * message. I can get it for you later so you can skip this if you cant figure it out yourself"): the real
 * Hypixel system message shown when a party member is kicked is NOT hidden by this feature yet — its exact
 * text was never supplied and guessing it risks either missing it entirely or hiding an unrelated real
 * message.
 */
public class AutoKickFeature extends Feature {
	// Real Group Builder slot indices — see class doc comment for the Devonian source citation.
	private static final int SLOT_FLOOR_TYPE = 11;
	private static final int SLOT_FLOOR_NUMBER = 12;
	private static final Pattern FLOOR_TYPE_PATTERN = Pattern.compile("^Currently Selected: (Master Mode )?The Catacombs$");
	private static final Pattern FLOOR_NUMBER_PATTERN = Pattern.compile("^Currently Selected: Floor ([IVXLCDM]+)$");

	// Real second capture source, ported alongside the existing Group Builder one (per user request — "make
	// sure we are also detecting the floor queued by the stuff i mentioned a while ago with detecting gui
	// names and certain slots to detect the floor being queued"): confirmed against Devonian's own real
	// AutoKick.kt, which reads the floor from TWO different real screens, not just one — "Group Builder" (the
	// floor/mode PICKER, slots 11/12, already implemented above) AND the real "Party Finder" listing screen
	// itself (what you see reviewing your own posted party), whose slot 53 holds a player-head item with its
	// own "Dungeon: ..."/"Floor: Floor N" lore lines. Group Builder is only open for as long as it takes to
	// set the floor once; Party Finder's own listing is naturally reopened far more often (checking on your
	// posted party), so this gives selectedFloor/selectedMasterMode a second, more reliably-available source
	// instead of depending entirely on the player having had Group Builder open at some point this session.
	private static final int SLOT_PARTY_FINDER_LISTING = 53;
	private static final Pattern PARTY_FINDER_FLOOR_TYPE_PATTERN = Pattern.compile("^Dungeon: (Master Mode )?The Catacombs$");
	private static final Pattern PARTY_FINDER_FLOOR_PATTERN = Pattern.compile("^Floor: Floor ([IVXLCDM]+)$");

	// Same real join-line regex PartyFinderFeature already confirmed (Devonian's own PartyFinderStats.kt) —
	// duplicated locally rather than shared, matching this codebase's own established small-duplication
	// convention (e.g. MelodyDisplayFeature's own duplicated classColor/classIcon).
	private static final Pattern JOIN_PATTERN = Pattern.compile(
		"^Party Finder > (\\w{1,16}) joined the dungeon group! \\((?:Healer|Tank|Mage|Berserk|Archer) Level \\d+\\)$");

	private static final long ENRICHMENT_WAIT_MILLIS = 8_000;
	// Real safety margin before the actual /p kick, mirroring Devonian's own 10-tick gap between its party
	// chat announcement and its kick command — gives the chat message a moment to actually land first.
	private static final long KICK_DELAY_MILLIS = 500;

	private record PendingJoin(String username, long queuedAtMillis) {}
	private record PendingKick(String username, String reason, long fireAtMillis) {}

	private final Map<String, PendingJoin> pending = new LinkedHashMap<>();
	private final List<PendingKick> pendingKicks = new ArrayList<>();

	// Real session-only state — remembered until the next Group Builder open, never persisted (this is live
	// queue state, not a setting). -1 = unknown floor (no PB check possible yet this session).
	private boolean selectedMasterMode = false;
	private int selectedFloor = -1;
	private boolean groupBuilderCaptured = false;
	private boolean partyFinderListingCaptured = false;

	private int minPbMinutes = 4;
	private int minPbSeconds = 30;
	private boolean requireHyperion = false;
	private boolean requireTerminator = false;
	private boolean requireGoldenDragon = false;
	private boolean requireBillionBank = false;

	private static boolean listenersRegistered = false;
	private static AutoKickFeature instance;

	public AutoKickFeature() {
		super("autokick", "Auto-kick", FeatureCategory.COMBAT, false);
		instance = this;
		ensureListenersRegistered();
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	private static void ensureListenersRegistered() {
		if (listenersRegistered) return;
		listenersRegistered = true;

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (instance == null || !instance.isEnabled() || overlay) return true;
			try {
				instance.onChatMessage(message);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Auto-kick chat listener threw, skipping this line", e);
			}
			return true;
		});

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			if (instance == null || !instance.isEnabled()) return;
			String title = containerScreen.getTitle().getString();
			if ("Group Builder".equals(title)) {
				instance.groupBuilderCaptured = false;
				ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
					if (instance == null || !instance.isEnabled() || instance.groupBuilderCaptured) return;
					try {
						instance.captureGroupBuilder(containerScreen);
					} catch (Exception e) {
						SkyblockSimplified.LOGGER.error("Auto-kick Group Builder capture failed, skipping this frame", e);
					}
				});
			} else if ("Party Finder".equals(title)) {
				instance.partyFinderListingCaptured = false;
				ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
					if (instance == null || !instance.isEnabled() || instance.partyFinderListingCaptured) return;
					try {
						instance.capturePartyFinderListing(containerScreen);
					} catch (Exception e) {
						SkyblockSimplified.LOGGER.error("Auto-kick Party Finder listing capture failed, skipping this frame", e);
					}
				});
			}
		});
	}

	/** Real self-healing capture — see class doc comment. Stops re-checking the instant BOTH slots have
	 *  matched at least once; a slot that never matches (e.g. the menu is a different real Hypixel screen
	 *  than expected) just leaves the last-known floor/mode in place rather than resetting to unknown. */
	private void captureGroupBuilder(AbstractContainerScreen<?> screen) {
		var menu = screen.getMenu();
		if (menu.slots.size() <= SLOT_FLOOR_NUMBER) return;
		boolean sawType = false, sawFloor = false;

		var typeLore = menu.getSlot(SLOT_FLOOR_TYPE).getItem().get(net.minecraft.core.component.DataComponents.LORE);
		if (typeLore != null) {
			for (Component line : typeLore.lines()) {
				Matcher m = FLOOR_TYPE_PATTERN.matcher(line.getString());
				if (m.matches()) {
					selectedMasterMode = m.group(1) != null;
					sawType = true;
					break;
				}
			}
		}

		var floorLore = menu.getSlot(SLOT_FLOOR_NUMBER).getItem().get(net.minecraft.core.component.DataComponents.LORE);
		if (floorLore != null) {
			for (Component line : floorLore.lines()) {
				Matcher m = FLOOR_NUMBER_PATTERN.matcher(line.getString());
				if (m.matches()) {
					int floor = RomanNumeralUtil.parseLevel(m.group(1));
					if (floor > 0) {
						selectedFloor = floor;
						sawFloor = true;
					}
					break;
				}
			}
		}

		if (sawType && sawFloor) groupBuilderCaptured = true;
	}

	/** Second real capture source — see SLOT_PARTY_FINDER_LISTING's own doc comment. Reads both lore lines
	 *  off the one real head item at slot 53 in a single pass, same self-healing "stop once both are seen"
	 *  contract as captureGroupBuilder. */
	private void capturePartyFinderListing(AbstractContainerScreen<?> screen) {
		var menu = screen.getMenu();
		if (menu.slots.size() <= SLOT_PARTY_FINDER_LISTING) return;
		ItemStack stack = menu.getSlot(SLOT_PARTY_FINDER_LISTING).getItem();
		if (!stack.is(Items.PLAYER_HEAD)) return;
		var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		if (lore == null) return;

		boolean sawType = false, sawFloor = false;
		for (Component line : lore.lines()) {
			Matcher typeMatch = PARTY_FINDER_FLOOR_TYPE_PATTERN.matcher(line.getString());
			if (typeMatch.matches()) {
				selectedMasterMode = typeMatch.group(1) != null;
				sawType = true;
				continue;
			}
			Matcher floorMatch = PARTY_FINDER_FLOOR_PATTERN.matcher(line.getString());
			if (floorMatch.matches()) {
				int floor = RomanNumeralUtil.parseLevel(floorMatch.group(1));
				if (floor > 0) {
					selectedFloor = floor;
					sawFloor = true;
				}
			}
		}

		if (sawType && sawFloor) partyFinderListingCaptured = true;
	}

	private void onChatMessage(Component message) {
		Matcher match = JOIN_PATTERN.matcher(message.getString());
		if (!match.matches()) return;
		String username = match.group(1);
		pending.put(username, new PendingJoin(username, System.currentTimeMillis()));
		MojangApi.resolve(username, SkyblockStatsApi::request);
	}

	@Override
	public void onTick(Minecraft client) {
		if (!pending.isEmpty()) {
			long now = System.currentTimeMillis();
			pending.values().removeIf(join -> {
				if (now - join.queuedAtMillis < ENRICHMENT_WAIT_MILLIS) return false;
				try {
					evaluate(join.username);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Auto-kick evaluation failed for {}, skipping", join.username, e);
				}
				return true;
			});
		}
		if (!pendingKicks.isEmpty()) {
			long now = System.currentTimeMillis();
			pendingKicks.removeIf(kick -> {
				if (now < kick.fireAtMillis) return false;
				sendCommand("p kick " + kick.username);
				return true;
			});
		}
	}

	private void evaluate(String username) {
		if (!PartyApi.isLeader()) return;
		int minSeconds = minPbMinutes * 60 + minPbSeconds;
		boolean pbCheckEnabled = minSeconds > 0;
		boolean anyExtraCheck = requireHyperion || requireTerminator || requireGoldenDragon || requireBillionBank;
		if (!pbCheckEnabled && !anyExtraCheck) return;

		String uuid = MojangApi.get(username);
		SkyblockStatsApi.PlayerStats stats = uuid != null ? SkyblockStatsApi.get(uuid) : null;
		if (stats == null) return; // no real data available — never kick blind

		List<String> failedReasons = new ArrayList<>();

		if (pbCheckEnabled && selectedFloor >= 1 && selectedFloor <= 7) {
			SkyblockStatsApi.FloorTimes floorTimes = stats.floorTimes(selectedMasterMode, selectedFloor);
			boolean sAcceptable = selectedMasterMode || selectedFloor <= 4;
			Integer pbSeconds = floorTimes != null ? floorTimes.bestSecondsPreferringRank(sAcceptable) : null;
			if (pbSeconds == null || pbSeconds > minSeconds) failedReasons.add("personal best");
		}
		if (requireHyperion && !stats.hasHyperion()) failedReasons.add("Hyperion");
		if (requireTerminator && !stats.hasTerminator()) failedReasons.add("Terminator");
		if (requireGoldenDragon && !stats.hasGoldenDragon()) failedReasons.add("Golden Dragon");
		if (requireBillionBank && !stats.hasBillionInBank()) failedReasons.add("1B+ bank");

		if (failedReasons.isEmpty()) return;

		// Per user request ("Make the mod send a chat message when kicking someone like 'Kicked {username}
		// did not match personal best'"): kept literal for the personal-best case; the same phrasing pattern
		// extended honestly to the extra-requirement checkboxes rather than always blaming personal best
		// when e.g. Hyperion was the actual reason.
		String reason = String.join(", ", failedReasons);
		sendCommand("pc Kicked " + username + " did not match " + reason);
		pendingKicks.add(new PendingKick(username, reason, System.currentTimeMillis() + KICK_DELAY_MILLIS));
	}

	private static void sendCommand(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand(command);
	}

	public int getMinPbMinutes() { return minPbMinutes; }
	public void setMinPbMinutes(int value) { minPbMinutes = Math.max(0, value); }
	public int getMinPbSeconds() { return minPbSeconds; }
	public void setMinPbSeconds(int value) { minPbSeconds = Math.max(0, Math.min(59, value)); }
	public boolean isRequireHyperion() { return requireHyperion; }
	public void setRequireHyperion(boolean value) { requireHyperion = value; }
	public boolean isRequireTerminator() { return requireTerminator; }
	public void setRequireTerminator(boolean value) { requireTerminator = value; }
	public boolean isRequireGoldenDragon() { return requireGoldenDragon; }
	public void setRequireGoldenDragon(boolean value) { requireGoldenDragon = value; }
	public boolean isRequireBillionBank() { return requireBillionBank; }
	public void setRequireBillionBank(boolean value) { requireBillionBank = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("minPbMinutes", minPbMinutes);
		obj.addProperty("minPbSeconds", minPbSeconds);
		obj.addProperty("requireHyperion", requireHyperion);
		obj.addProperty("requireTerminator", requireTerminator);
		obj.addProperty("requireGoldenDragon", requireGoldenDragon);
		obj.addProperty("requireBillionBank", requireBillionBank);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("minPbMinutes")) minPbMinutes = obj.get("minPbMinutes").getAsInt();
		if (obj.has("minPbSeconds")) minPbSeconds = obj.get("minPbSeconds").getAsInt();
		if (obj.has("requireHyperion")) requireHyperion = obj.get("requireHyperion").getAsBoolean();
		if (obj.has("requireTerminator")) requireTerminator = obj.get("requireTerminator").getAsBoolean();
		if (obj.has("requireGoldenDragon")) requireGoldenDragon = obj.get("requireGoldenDragon").getAsBoolean();
		if (obj.has("requireBillionBank")) requireBillionBank = obj.get("requireBillionBank").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Automatically kicks a Party Finder joiner from your party if they don't meet the requirements you set.";
	}
}
