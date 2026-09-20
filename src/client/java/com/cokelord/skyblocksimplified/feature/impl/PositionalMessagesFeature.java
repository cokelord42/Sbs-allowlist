package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sends a party-chat message when you're near a user-authored point. Ported from Odin's {@code
 * PositionalMessages.kt}, but reconfigured entirely through the GUI now (per user request/mockup) instead
 * of the original {@code /posmsg} chat command — each entry gets its own editable Text/Range/X/Y/Z fields,
 * a Classes/Section/Boss-Part restriction, a Preset picker, and a "use current position" button, with a
 * "+" to add another entry underneath.
 */
public class PositionalMessagesFeature extends Feature {
	/** Which F7 boss phase (per {@link DungeonState.F7Phase}) a message is restricted to — ANY (the
	 *  default) always activates. Confirmed real phase order/boundaries against Odin's own {@code
	 *  getF7Phase()} (identical Y thresholds); P3 specifically is confirmed real Goldor-terminal-phase by
	 *  every real Odin terminal-device feature (Simon Says, Melody Message, Arrow Align, ...) gating on it. */
	public enum BossPart {
		ANY("Any"), MAXOR("Maxor"), STORM("Storm"), GOLDOR("Goldor");
		public final String label;
		BossPart(String label) { this.label = label; }
	}

	public static final class PosMessage {
		public String message = "";
		public String xInput = "0";
		public String yInput = "0";
		public String zInput = "0";
		public String rangeInput = "10";
		public double x, y, z;
		public double range = 10;
		// Empty = ALL (no restriction) for both — per user request, multi-select now instead of a single
		// value, so e.g. a message can be restricted to "S2 or S3" at once (matches the "At Core!" preset).
		public final Set<DungeonState.TerminalSection> sections = new LinkedHashSet<>();
		public final Set<DungeonClass> classes = new LinkedHashSet<>();
		public BossPart bossPart = BossPart.ANY;
	}

	/** One hardcoded preset entry — per user-supplied list, applied via the GUI's Preset dropdown (picking
	 *  one overwrites the message/coords/range/section/class/boss-part fields of whichever row it was
	 *  opened from, same "just fills in the fields" behavior as Use Current Position). */
	public record Preset(String label, String message, DungeonClass clazz, double x, double y, double z,
						  double range, Set<DungeonState.TerminalSection> sections, BossPart bossPart) {}

	private static final DungeonState.TerminalSection S1 = DungeonState.TerminalSection.S1;
	private static final DungeonState.TerminalSection S2 = DungeonState.TerminalSection.S2;
	private static final DungeonState.TerminalSection S3 = DungeonState.TerminalSection.S3;

	public static final List<Preset> PRESETS = List.of(
		new Preset("At SS!", "At SS!", DungeonClass.HEALER, 108, 120, 94, 3, Set.of(), BossPart.STORM),
		new Preset("At HIGH EE2!", "At HIGH EE2!", DungeonClass.MAGE, 60.5, 132, 139, 2, Set.of(S1), BossPart.GOLDOR),
		new Preset("At LOW EE2!", "At LOW EE2!", DungeonClass.MAGE, 58, 109, 131, 1, Set.of(S1), BossPart.GOLDOR),
		new Preset("At EE3!", "At EE3!", DungeonClass.HEALER, 2, 109, 104.5, 1, Set.of(S2), BossPart.GOLDOR),
		new Preset("At Core!", "At Core!", DungeonClass.MAGE, 54.5, 115, 50.5, 0.5, Set.of(S2, S3), BossPart.GOLDOR),
		new Preset("At I4!", "At I4!", DungeonClass.BERSERK, 63.5, 127, 35.5, 0.5, Set.of(), BossPart.STORM),
		new Preset("At GY Dropoff!", "At GY Dropoff!", DungeonClass.BERSERK, 56.5, 169, 53.5, 1, Set.of(), BossPart.MAXOR),
		new Preset("Waiting for Healer!", "Waiting for Healer!", DungeonClass.BERSERK, 101, 115, 51, 1, Set.of(), BossPart.STORM)
	);

	private boolean onlyInFloor7Boss = true;
	private boolean showPositions = true;
	// Per user request: an optional round outline instead of the square, still built from ordinary
	// world-space line segments (not a real curve) — see WorldRenderUtil.drawGroundCircle for the segment
	// count reasoning.
	private boolean circleMode = false;
	private int boxColor = 0xFFFFFF55;
	private boolean showText = true;
	// Task #602 ("rework world-space line rendering — mage beam, secrets, positional messages, etc."): the
	// ground square/circle now draws through World3DRenderer (real GPU depth-tested 3D, same backend Mage
	// Beam/Quiz Solver/Dungeon Routes already use) instead of WorldRenderUtil's screen-space projected-line
	// approach — same visual shape (drawGroundSquare/drawGroundCircle's geometry was ported verbatim onto
	// World3DRenderer, see its own doc comment), but real occlusion instead of the old CPU raycast
	// approximation. Depth Check mirrors Mage Beam's own setting (default true — occluded by walls like a
	// real object; off draws through walls, useful for placing/checking a point you can't currently see).
	private boolean depthCheck = true;
	// Per user request ("Allow users to change the height of the positional messages box/circle walls"): the
	// ground square/circle used to be a flat ring/outline on the ground with no vertical extent at all — this
	// extrudes it upward into an actual wall, height adjustable since different rooms/purposes want a
	// taller-or-shorter visual (a subtle floor marker vs. an obvious can't-miss-it wall). Global rather than
	// per-message, matching boxColor/depthCheck/circleMode's own scope — one shared "how tall are my walls"
	// preference, not something worth authoring per entry.
	private double wallHeight = 3.0;
	// A block of slack on the Y axis specifically — per user request, so a carpet/slab/stair shifting the
	// player's real feet position by up to a block doesn't fail an otherwise-correct horizontal trigger.
	private static final double Y_SLACK = 1.0;

	private final List<PosMessage> messages = new ArrayList<>();
	// Per user request ("It should trigger once when entering the ring"): edge-triggered on the transition
	// into a fully-triggered (in range AND restrictions match) state, rather than the earlier per-run/
	// per-cooldown latch this replaces or the brief no-debounce testing build before it. Naturally allows
	// re-firing on a genuine re-entry (walk out, walk back in) since membership is cleared the moment the
	// message stops being triggered, with no separate per-run/cooldown bookkeeping needed.
	private final Set<PosMessage> currentlyTriggered = new java.util.HashSet<>();

	private static boolean rendererRegistered = false;
	private static PositionalMessagesFeature instance;

	public PositionalMessagesFeature() {
		super("positional_messages", "Positional Messages", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() {
		return "Dungeons";
	}

	@Override
	protected void onEnable() {
		if (!rendererRegistered) {
			rendererRegistered = true;
			// Per user request: shouldn't render "through" GUI elements like the hotbar. addLast put this
			// layer on top of literally everything else registered before it (hotbar included), which is why
			// a ground square dipping toward the bottom of the screen drew over the hotbar instead of behind
			// it. Attaching before HOTBAR means the hotbar (and everything Fabric/vanilla registers after it)
			// draws on top of this layer instead, occluding it near the bottom of the screen exactly like a
			// real HUD element would.
			HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR,
				Identifier.fromNamespaceAndPath("skyblocksimplified", "positional_messages"), PositionalMessagesFeature::renderStatic);
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.addRenderCallback(PositionalMessagesFeature::renderShapes);
		}
	}

	private static boolean inFloor7Boss() {
		return DungeonState.isInBoss() && DungeonState.getFloorNumber() == 7;
	}

	@Override
	public void onTick(Minecraft client) {
		if (onlyInFloor7Boss && !inFloor7Boss()) return;
		if (client.player == null) return;

		for (PosMessage message : messages) {
			boolean inRange = isInRange(client, message);
			boolean restrictionsOk = matches(message);
			boolean triggered = inRange && restrictionsOk;
			boolean wasTriggered = currentlyTriggered.contains(message);

			// Diagnostic only — per earlier user report ("it doesnt do anything at all"), distinguishes
			// "never gets in range at all" from "in range but a Section/Class/Boss Part restriction is
			// blocking it" from "triggered and the /pc command was actually sent".
			// Per user request ("It should trigger once when entering the ring") — edge-triggered on the
			// range/restriction transition into "triggered", not fired again every tick spent inside it.
			if (triggered && !wasTriggered && message.message != null && !message.message.isBlank()) {
				sendPartyChat(message.message);
			}
			if (triggered) currentlyTriggered.add(message); else currentlyTriggered.remove(message);
		}
	}

	private static boolean isInRange(Minecraft client, PosMessage message) {
		double dx = client.player.getX() - message.x;
		double dz = client.player.getZ() - message.z;
		double dy = client.player.getY() - message.y;
		return (dx * dx + dz * dz) <= message.range * message.range && Math.abs(dy) <= message.range + Y_SLACK;
	}

	/** All three restrictions (Section/Class/Boss Part) at once — an empty Section/Class set means "no
	 *  restriction on that axis", matching the GUI's "ALL" label. */
	private static boolean matches(PosMessage message) {
		return sectionMatches(message) && classMatches(message) && bossPartMatches(message);
	}

	private static boolean sectionMatches(PosMessage message) {
		return message.sections.isEmpty() || message.sections.contains(DungeonState.getTerminalSection());
	}

	private static boolean classMatches(PosMessage message) {
		if (message.classes.isEmpty()) return true;
		DungeonClass self = selfClass();
		return self != null && message.classes.contains(self);
	}

	private static boolean bossPartMatches(PosMessage message) {
		if (message.bossPart == BossPart.ANY) return true;
		DungeonState.F7Phase phase = DungeonState.getF7Phase();
		return switch (message.bossPart) {
			case MAXOR -> phase == DungeonState.F7Phase.P1;
			case STORM -> phase == DungeonState.F7Phase.P2;
			case GOLDOR -> phase == DungeonState.F7Phase.P3;
			case ANY -> true;
		};
	}

	/** The local player's own live-tracked dungeon class, or null if not resolved yet — same {@code
	 *  DungeonState.getTeammates()} lookup PlayerGlowFeature/StarredMobHighlightFeature already use for
	 *  teammates, just matched against the local player's own name instead of another entity's. */
	private static DungeonClass selfClass() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		String selfName = mc.player.getName().getString();
		for (DungeonPlayer teammate : DungeonState.getTeammates()) {
			if (teammate.name.equals(selfName)) return teammate.clazz;
		}
		return null;
	}

	private void sendPartyChat(String message) {
		Minecraft mc = Minecraft.getInstance();
		// Per explicit user request ("that wasnt the positional messages issue... remove that gate since i am
		// not testing in a party"): the party-membership check added last round is removed again — the user
		// isn't testing in a party at all, so it was blocking every send outright rather than helping.
		if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand("pc " + message);
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled() || !instance.showPositions || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Positional Messages render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || (onlyInFloor7Boss && !inFloor7Boss())) return;

		for (PosMessage message : messages) {
			if (!shouldShow(mc, message)) continue;
			Vec3 center = new Vec3(message.x, message.y, message.z);
			// Per user request: text a block higher than before (was +1, now +2), an opt-out sub-toggle, and
			// no longer shown once the player is actually standing inside the trigger ring — the callout
			// stops being useful once you've arrived, and it was one of the things reading as "rendering
			// through the player" up close. Text stays on this screen-space path — World3DRenderer (the
			// ground shape's new real 3D backend, see renderShapes) has no text-drawing capability of its own.
			if (showText && message.message != null && !message.message.isBlank() && !isInRange(mc, message)) {
				WorldRenderUtil.drawText(graphics, message.message, center.add(0, 2, 0), 0xFFFFFFFF);
			}
		}
	}

	/** Per user request ("build a square that shows the range on the ground"): the old drawWireBox never
	 *  actually drew a 3D box — it derived a flat on-screen bounding rectangle from the 8 projected corners,
	 *  which always read as a flat 2D overlay regardless of camera angle. That was already fixed once (a
	 *  screen-space "each edge independently projected" ground square/circle), but task #602 replaces that
	 *  screen-space approximation with the real thing: a real depth-tested 3D shape via {@link
	 *  com.cokelord.skyblocksimplified.highlight.World3DRenderer}, same backend Mage Beam/Quiz Solver/Dungeon
	 *  Routes already use, with a real Depth Check setting instead of the old CPU-raycast approximation. */
	private static void renderShapes() {
		if (instance == null || !instance.isEnabled() || !instance.showPositions) return;
		try {
			instance.renderShapesInner();
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Positional Messages shape render failed, skipping this frame", e);
		}
	}

	private void renderShapesInner() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || (onlyInFloor7Boss && !inFloor7Boss())) return;
		for (PosMessage message : messages) {
			if (!shouldShow(mc, message)) continue;
			Vec3 center = new Vec3(message.x, message.y, message.z);
			Vec3 top = center.add(0, wallHeight, 0);
			if (circleMode) {
				if (depthCheck) com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawCircle(center, message.range, boxColor, 2f);
				else com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawCircleThroughWalls(center, message.range, boxColor, 2f);
				if (wallHeight > 0) {
					if (depthCheck) com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawCircle(top, message.range, boxColor, 2f);
					else com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawCircleThroughWalls(top, message.range, boxColor, 2f);
					// Four cardinal vertical connectors turn the two flat rings into a readable wall shape
					// (a real cylinder mesh isn't worth building for an outline-only highlight).
					for (double[] offset : new double[][]{{message.range, 0}, {-message.range, 0}, {0, message.range}, {0, -message.range}}) {
						Vec3 from = center.add(offset[0], 0, offset[1]);
						Vec3 to = top.add(offset[0], 0, offset[1]);
						if (depthCheck) com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawLine(from, to, boxColor, 2f);
						else com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawLineThroughWalls(from, to, boxColor, 2f);
					}
				}
			} else {
				if (depthCheck) com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawGroundSquare(center, message.range, boxColor, 2f);
				else com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawGroundSquareThroughWalls(center, message.range, boxColor, 2f);
				if (wallHeight > 0) {
					AABB box = new AABB(center.x - message.range, center.y, center.z - message.range,
						center.x + message.range, top.y, center.z + message.range);
					if (depthCheck) com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawWireBox(box, boxColor, 2f);
					else com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawWireBoxThroughWalls(box, boxColor, 2f);
				}
			}
		}
	}

	private boolean shouldShow(Minecraft mc, PosMessage message) {
		// Per user correction: the box should behave exactly like before (respecting Section/Class/Boss
		// Part restrictions normally on real Hypixel) — the ONLY carve-out wanted is singleplayer, which
		// has no real party/terminal/boss state to ever match a restricted message against at all, so a
		// restricted message's box would otherwise never be visible there for placement/testing purposes.
		boolean bypassRestrictions = com.cokelord.skyblocksimplified.util.IslandGate.isSingleplayer();
		if (!bypassRestrictions && !matches(message)) return false;
		Vec3 center = new Vec3(message.x, message.y, message.z);
		return mc.player.position().distanceToSqr(center) <= 1024;
	}

	// ---- Export/import — per user request ("Positional messages need an export feature"), same gzip+base64
	// shape as Dungeon Routes' own all-rooms export/import, reusing savePersistedData()/loadPersistedData()
	// directly so the string carries every setting (toggles, box color, and every message) at once.
	private static final String POSMSG_EXPORT_PREFIX = "SBSPOSMSG1:";
	private static final int POSMSG_MAX_IMPORT_LENGTH = 500_000;
	private static final int POSMSG_MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024;

	public String exportToClipboardString() {
		try {
			String json = new com.google.gson.Gson().toJson(savePersistedData());
			java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
			try (java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(byteOut)) {
				gzipOut.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
			return POSMSG_EXPORT_PREFIX + java.util.Base64.getEncoder().encodeToString(byteOut.toByteArray());
		} catch (java.io.IOException e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Failed to export a Positional Messages string", e);
			return null;
		}
	}

	public boolean importFromClipboardString(String raw) {
		if (raw == null) return false;
		String trimmed = raw.strip();
		if (trimmed.length() > POSMSG_MAX_IMPORT_LENGTH || !trimmed.startsWith(POSMSG_EXPORT_PREFIX)) return false;
		try {
			byte[] compressed = java.util.Base64.getDecoder().decode(trimmed.substring(POSMSG_EXPORT_PREFIX.length()));
			java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
			try (java.util.zip.GZIPInputStream gzipIn = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
				byte[] buffer = new byte[8192];
				int total = 0, read;
				while ((read = gzipIn.read(buffer)) != -1) {
					total += read;
					if (total > POSMSG_MAX_DECOMPRESSED_BYTES) throw new java.io.IOException("Decompressed Positional Messages import exceeds size cap");
					byteOut.write(buffer, 0, read);
				}
			}
			String json = new String(byteOut.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
			loadPersistedData(com.google.gson.JsonParser.parseString(json));
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			return true;
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.warn("Failed to apply an imported Positional Messages string", e);
			return false;
		}
	}

	// ---- GUI editing surface (called from MainScreen) ----
	public List<PosMessage> getMessages() { return messages; }

	public void addMessage() {
		messages.add(new PosMessage());
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void removeMessage(int index) {
		if (index < 0 || index >= messages.size()) return;
		currentlyTriggered.remove(messages.remove(index));
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Toggles one section's membership in a message's multi-select restriction — per user request
	 *  ("make the Section selector into a dropdown aswell so you can select multiple"). */
	public void toggleSection(int index, DungeonState.TerminalSection section) {
		if (index < 0 || index >= messages.size()) return;
		Set<DungeonState.TerminalSection> set = messages.get(index).sections;
		if (!set.add(section)) set.remove(section);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void toggleClass(int index, DungeonClass clazz) {
		if (index < 0 || index >= messages.size()) return;
		Set<DungeonClass> set = messages.get(index).classes;
		if (!set.add(clazz)) set.remove(clazz);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Cycles Boss Part ANY -> Maxor -> Storm -> Goldor -> ANY, one step per call. */
	public void cycleBossPart(int index) {
		if (index < 0 || index >= messages.size()) return;
		PosMessage m = messages.get(index);
		BossPart[] values = BossPart.values();
		m.bossPart = values[(m.bossPart.ordinal() + 1) % values.length];
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	/** Overwrites one message's message/coords/range/section/class/boss-part fields from a hardcoded
	 *  preset — same "just fills in the fields" idea as {@link #useCurrentPosition}. */
	public void applyPreset(int index, Preset preset) {
		if (index < 0 || index >= messages.size()) return;
		PosMessage m = messages.get(index);
		m.message = preset.message();
		m.x = preset.x(); m.y = preset.y(); m.z = preset.z(); m.range = preset.range();
		m.xInput = fmt(m.x); m.yInput = fmt(m.y); m.zInput = fmt(m.z); m.rangeInput = fmt(m.range);
		m.sections.clear();
		m.sections.addAll(preset.sections());
		m.classes.clear();
		m.classes.add(preset.clazz());
		m.bossPart = preset.bossPart();
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void useCurrentPosition(int index) {
		if (index < 0 || index >= messages.size()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		PosMessage m = messages.get(index);
		m.x = mc.player.getX(); m.y = mc.player.getY(); m.z = mc.player.getZ();
		m.xInput = fmt(m.x); m.yInput = fmt(m.y); m.zInput = fmt(m.z);
		// The player is now standing exactly on the new point by definition, so the very next tick's trigger
		// check would find them already "just entered" range and fire immediately. Pre-marking this message
		// as already-triggered skips that one spurious send without affecting any real future entry (which
		// still requires actually leaving and re-entering range to clear this and re-trigger).
		currentlyTriggered.add(m);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	// Per user request ("make them only have 1 decimal point of accuracy") — was full double precision.
	private static String fmt(double v) {
		return String.format(java.util.Locale.ROOT, "%.1f", v);
	}

	public boolean isOnlyInFloor7Boss() { return onlyInFloor7Boss; }
	public void setOnlyInFloor7Boss(boolean value) { onlyInFloor7Boss = value; }
	public boolean isShowPositions() { return showPositions; }
	public void setShowPositions(boolean value) { showPositions = value; }
	public boolean isCircleMode() { return circleMode; }
	public void setCircleMode(boolean value) { circleMode = value; }
	public int getBoxColor() { return boxColor; }
	public void setBoxColor(int value) { boxColor = value; }
	public boolean isShowText() { return showText; }
	public void setShowText(boolean value) { showText = value; }
	public boolean isDepthCheck() { return depthCheck; }
	public void setDepthCheck(boolean value) { depthCheck = value; }
	public double getWallHeight() { return wallHeight; }
	public void setWallHeight(double value) { wallHeight = Math.max(0, value); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("onlyInFloor7Boss", onlyInFloor7Boss);
		obj.addProperty("showPositions", showPositions);
		obj.addProperty("circleMode", circleMode);
		obj.addProperty("boxColor", boxColor);
		obj.addProperty("showText", showText);
		obj.addProperty("depthCheck", depthCheck);
		obj.addProperty("wallHeight", wallHeight);
		JsonArray array = new JsonArray();
		for (PosMessage m : messages) {
			JsonObject e = new JsonObject();
			e.addProperty("x", m.x); e.addProperty("y", m.y); e.addProperty("z", m.z);
			e.addProperty("range", m.range);
			e.addProperty("message", m.message);
			JsonArray sections = new JsonArray();
			for (DungeonState.TerminalSection s : m.sections) sections.add(s.name());
			e.add("sections", sections);
			JsonArray classes = new JsonArray();
			for (DungeonClass c : m.classes) classes.add(c.name());
			e.add("classes", classes);
			e.addProperty("bossPart", m.bossPart.name());
			array.add(e);
		}
		obj.add("messages", array);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("onlyInFloor7Boss")) onlyInFloor7Boss = obj.get("onlyInFloor7Boss").getAsBoolean();
		else if (obj.has("onlyInDungeons")) onlyInFloor7Boss = obj.get("onlyInDungeons").getAsBoolean();
		if (obj.has("showPositions")) showPositions = obj.get("showPositions").getAsBoolean();
		if (obj.has("circleMode")) circleMode = obj.get("circleMode").getAsBoolean();
		if (obj.has("boxColor")) boxColor = obj.get("boxColor").getAsInt();
		if (obj.has("showText")) showText = obj.get("showText").getAsBoolean();
		if (obj.has("depthCheck")) depthCheck = obj.get("depthCheck").getAsBoolean();
		if (obj.has("wallHeight")) wallHeight = obj.get("wallHeight").getAsDouble();
		messages.clear();
		if (obj.has("messages")) {
			for (JsonElement el : obj.getAsJsonArray("messages")) {
				JsonObject e = el.getAsJsonObject();
				PosMessage m = new PosMessage();
				m.x = e.get("x").getAsDouble(); m.y = e.get("y").getAsDouble(); m.z = e.get("z").getAsDouble();
				m.range = e.has("range") ? e.get("range").getAsDouble() : 10;
				m.message = e.has("message") && !e.get("message").isJsonNull() ? e.get("message").getAsString() : "";
				// Back-compat with the old single-section format (a message never had more than one anyway).
				if (e.has("section")) {
					try {
						DungeonState.TerminalSection s = DungeonState.TerminalSection.valueOf(e.get("section").getAsString());
						if (s != DungeonState.TerminalSection.NONE) m.sections.add(s);
					} catch (IllegalArgumentException ignored) {}
				}
				if (e.has("sections")) {
					for (JsonElement s : e.getAsJsonArray("sections")) {
						try { m.sections.add(DungeonState.TerminalSection.valueOf(s.getAsString())); } catch (IllegalArgumentException ignored) {}
					}
				}
				if (e.has("classes")) {
					for (JsonElement c : e.getAsJsonArray("classes")) {
						try { m.classes.add(DungeonClass.valueOf(c.getAsString())); } catch (IllegalArgumentException ignored) {}
					}
				}
				if (e.has("bossPart")) {
					try { m.bossPart = BossPart.valueOf(e.get("bossPart").getAsString()); } catch (IllegalArgumentException ignored) {}
				}
				m.xInput = fmt(m.x); m.yInput = fmt(m.y); m.zInput = fmt(m.z); m.rangeInput = fmt(m.range);
				messages.add(m);
			}
		}
	}

	@Override
	public String getDescription() {
		return "Sends a party-chat message automatically whenever you get near a location you've marked.";
	}
}
