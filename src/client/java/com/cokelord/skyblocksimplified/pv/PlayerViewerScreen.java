package com.cokelord.skyblocksimplified.pv;

import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.gui.Theme;
import com.cokelord.skyblocksimplified.pv.PvUi.Block;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Per user request: "/pv" or "/playerviewer [name]" opens a themed profile viewer — category buttons on
 * top (skills and the rest, Misc furthest right), each with smaller subcategory buttons underneath, and an
 * Overview landing page with networth (total/unsoulbound/soulbound), the player's skin wearing their armor,
 * equipment, online status, SkyBlock level and last online. Sized and colored exactly like the mod menu
 * (MainScreen's panel formula + {@link Theme}), and profile-switchable (selected profile first).
 *
 * <p>All data arrives through {@link PvApi} (one Worker round trip); pages are built once per
 * category/subcategory from {@link PvPages} and cached until the profile changes or late reference data
 * (level tables, texture map, collections) finishes loading.
 */
public class PlayerViewerScreen extends Screen implements com.cokelord.skyblocksimplified.gui.BlurringScreen {
	// Same panel sizing constants as MainScreen so the two read as one UI.
	private static final int PANEL_MIN_WIDTH = 460, PANEL_MAX_WIDTH = 880, PANEL_MIN_HEIGHT = 320, PANEL_MAX_HEIGHT = 620;
	private static final float PANEL_SIZE_FRACTION = 0.72f;
	private static final int PANEL_RADIUS = 8, HEADER_HEIGHT = 26, TAB_HEIGHT = 15, SUBTAB_HEIGHT = 13, PAD = 10;
	private static final float SCROLL_EASE = 9f;

	// Open/close animation (per user spec). Header rises from the bottom of the screen, then the body extends
	// down from it, then text fades in and boxes slide/fade in row by row (PvUi.Ctx#beginBox) — Overview on
	// first open only. Close shrinks the whole panel to nothing, exponential-in, like the mod menu.
	private static final float HEADER_RISE = 0.2f, BODY_EXTEND = 0.2f, CLOSE_DURATION = 0.25f, INTRO_MAX = 3f;
	private final long openNanos = System.nanoTime();
	private long contentStartNanos, closeStartNanos;
	private boolean closing, introActive = true;

	private final String requestedName;
	private final String knownUuid;

	private PvApi.PvData data;
	private List<PvProfile> profiles = List.of();
	private PvProfile profile;
	private String error;
	private CompletableFuture<PvNetworth.Result> networth;
	private ViewerMannequin mannequin;
	private boolean modelFailed;

	private int tab, sub;
	private float scroll, scrollTarget, maxScroll;
	private long lastFrameNanos;
	private int contentHeight;
	private boolean profileMenuOpen;
	private final Map<String, List<Block>> pageCache = new HashMap<>();
	private int pageCacheVersion = -1;

	private int panelX, panelY, panelWidth, panelHeight;
	private int contentX, contentY, contentW, contentBottom;
	private final List<Hit> hits = new ArrayList<>();

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
	}

	public PlayerViewerScreen(String name, String knownUuid) {
		super(Component.literal("Player Viewer"));
		this.requestedName = name;
		this.knownUuid = knownUuid;
		PvApi.load(name, knownUuid).whenComplete((result, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null || result == null) {
				error = describe(err);
				return;
			}
			data = result;
			profiles = PvProfile.all(result.profiles(), result.uuid());
			if (profiles.isEmpty()) {
				error = result.name() + " has no SkyBlock profiles.";
				return;
			}
			selectProfile(profiles.get(0));
		}));
	}

	private static String describe(Throwable err) {
		String msg = err == null ? "" : String.valueOf(err.getCause() != null ? err.getCause().getMessage() : err.getMessage());
		if (msg.contains("HTTP 404") || msg.contains("HTTP 204") || msg.contains("Not a JSON Object") || msg.contains("\"id\"")) return "Player not found.";
		if (msg.contains("HTTP 429")) return "Hypixel is rate limiting requests right now. Try again in a minute.";
		return "Couldn't load this player (" + (msg.length() > 80 ? msg.substring(0, 80) + "..." : msg) + ")";
	}

	private void selectProfile(PvProfile p) {
		if (profile != null) introActive = false;
		profile = p;
		pageCache.clear();
		scroll = scrollTarget = 0;
		mannequin = null;
		networth = CompletableFuture.supplyAsync(() -> PvNetworth.calculate(p));
	}

	@Override
	protected void init() {
		panelWidth = clamp(Math.round(width * PANEL_SIZE_FRACTION), PANEL_MIN_WIDTH, Math.min(PANEL_MAX_WIDTH, width - 20));
		panelHeight = clamp(Math.round(height * PANEL_SIZE_FRACTION), PANEL_MIN_HEIGHT, Math.min(PANEL_MAX_HEIGHT, height - 20));
		panelX = (width - panelWidth) / 2;
		panelY = (height - panelHeight) / 2;
	}

	private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (effectiveBlurAmount() >= 1) this.extractBlurredBackground(graphics);
		this.extractTransparentBackground(graphics);
		// Background bubbles, following the same open/close curve as the blur.
		float bubbleOpen = closing ? 1f - closeEase(System.nanoTime())
			: PvUi.Ctx.expoOut(clamp01((System.nanoTime() - openNanos) / 1e9f / (HEADER_RISE + BODY_EXTEND)));
		this.bubbleOpen = bubbleOpen;
	}

	private float bubbleOpen = 0f;

	/** Same blur setting as the mod menu (GuiAnimations blur slider, Transparent-theme minimum), faded in over
	 *  the open animation and out over the close. */
	@Override
	public int effectiveBlurAmount() {
		float f = closing ? 1f - closeEase(System.nanoTime()) : PvUi.Ctx.expoOut(clamp01(seconds(openNanos) / (HEADER_RISE + BODY_EXTEND)));
		return Math.max(0, Math.min(10, Math.round(com.cokelord.skyblocksimplified.gui.MainScreen.configuredBlurAmount() * f)));
	}

	private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
	private static float seconds(long since) { return (System.nanoTime() - since) / 1_000_000_000f; }

	private float closeEase(long now) {
		float t = clamp01((now - closeStartNanos) / 1_000_000_000f / CLOSE_DURATION);
		return t <= 0f ? 0f : (float) Math.pow(2, 10 * (t - 1));
	}

	@Override
	public void onClose() {
		if (!closing) {
			closing = true;
			closeStartNanos = System.nanoTime();
		}
	}

	// -----------------------------------------------------------------------------------------------------
	// Rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		hits.clear();
		long now = System.nanoTime();
		// Frame-rate independent exponential ease toward the target. Higher SCROLL_EASE = snappier.
		float dt = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000f);
		lastFrameNanos = now;
		scroll += (scrollTarget - scroll) * (1f - (float) Math.exp(-SCROLL_EASE * dt));
		if (Math.abs(scrollTarget - scroll) < 0.1f) scroll = scrollTarget;

		float scale = 1f;
		if (closing) {
			float eased = closeEase(now);
			if (eased >= 1f || clamp01((now - closeStartNanos) / 1_000_000_000f / CLOSE_DURATION) >= 1f) {
				minecraft.gui.setScreen(null);
				return;
			}
			scale = 1f - eased;
		}

		float openT = (now - openNanos) / 1_000_000_000f;
		float headerP = PvUi.Ctx.expoOut(clamp01(openT / HEADER_RISE));
		float bodyP = PvUi.Ctx.expoOut(clamp01((openT - HEADER_RISE) / BODY_EXTEND));
		boolean landed = openT >= HEADER_RISE + BODY_EXTEND;
		if (landed && contentStartNanos == 0 && (profile != null || error != null)) contentStartNanos = now;
		float introClock = -1f;
		if (introActive) {
			introClock = contentStartNanos == 0 ? 0f : (now - contentStartNanos) / 1_000_000_000f;
			if (introClock > INTRO_MAX) introActive = false;
		}
		if (!introActive) introClock = -1f;

		float cx = panelX + panelWidth / 2f, cy = panelY + panelHeight / 2f;
		g.pose().pushMatrix();
		if (scale < 1f) {
			g.pose().translate(cx, cy);
			g.pose().scale(scale);
			g.pose().translate(-cx, -cy);
		}
		try {
			drawPanel(g, mouseX, mouseY, headerP, bodyP, introClock);
		} finally {
			g.pose().popMatrix();
		}
	}

	private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float headerP, float bodyP, float introClock) {
		int bodyTop = panelY + HEADER_HEIGHT;
		int bodyBottom = bodyTop + Math.round((panelHeight - HEADER_HEIGHT) * bodyP);
		if (bodyBottom > bodyTop + 1) {
			fillPanel(g, panelX, bodyTop, panelX + panelWidth, bodyBottom, false, false, true, true, Theme.panelBackground(),
				Math.min(PANEL_RADIUS, (bodyBottom - bodyTop) / 2));
		}
		// Background bubbles, only over the panel body's own fill.
		com.cokelord.skyblocksimplified.gui.GuiBubbles.render(g, panelX, bodyTop, panelX + panelWidth, panelY + panelHeight,
			panelX, bodyTop, panelX + panelWidth, bodyBottom, bubbleOpen);

		// Header: rises from below the bottom of the screen into place.
		int headerOffset = Math.round((height - panelY) * (1f - headerP));
		g.pose().pushMatrix();
		g.pose().translate(0, headerOffset);
		fillPanel(g, panelX, panelY, panelX + panelWidth, bodyTop, true, true, bodyP <= 0f, bodyP <= 0f, Theme.chrome(0xF0181818), PANEL_RADIUS);
		PvUi.Ctx header = new PvUi.Ctx(g, mouseX, mouseY, panelY, panelY + HEADER_HEIGHT);
		header.introClock = introClock;
		String title = "Player Viewer" + (data != null ? "  §7» §f" + data.name() : requestedName != null ? "  §7» §f" + requestedName : "");
		header.text(title, panelX + PAD, panelY + (HEADER_HEIGHT - 8) / 2, PvUi.textColor());
		if (profile != null && error == null) drawProfileButton(header);
		g.pose().popMatrix();
		if (bodyBottom <= bodyTop + 1) return;

		if (error != null) {
			centered(g, error, panelY + panelHeight / 2, 0xFFFF5555);
			return;
		}
		if (profile == null) {
			if (bodyP >= 1f) {
				String dots = ".".repeat((int) (System.currentTimeMillis() / 350 % 4));
				centered(g, "Loading " + (requestedName != null ? requestedName : "player") + dots, panelY + panelHeight / 2, PvUi.textColor());
			}
			return;
		}

		g.enableScissor(panelX, bodyTop, panelX + panelWidth, bodyBottom);
		PvUi.Ctx tabs = new PvUi.Ctx(g, mouseX, mouseY, bodyTop, bodyBottom);
		tabs.introClock = introClock;
		int y = drawTabs(tabs, panelY + HEADER_HEIGHT + 6);
		g.disableScissor();

		contentX = panelX + PAD;
		contentW = panelWidth - PAD * 2;
		contentY = y + 4;
		contentBottom = Math.min(bodyBottom, panelY + panelHeight - 8);

		if (pageCacheVersion != PvRepo.version()) {
			pageCacheVersion = PvRepo.version();
			pageCache.clear();
		}
		List<Block> blocks = page();
		int total = 0;
		for (Block b : blocks) total += b.height(contentW) + 2;
		contentHeight = total;
		maxScroll = Math.max(0, contentHeight - (panelY + panelHeight - 8 - contentY));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		if (contentBottom <= contentY) return;

		PvUi.Ctx ctx = new PvUi.Ctx(g, mouseX, mouseY, contentY, contentBottom);
		ctx.introClock = introClock;
		ctx.rowOrigin = bodyTop; // content rows continue the stagger after the tab rows
		g.enableScissor(contentX - 2, contentY, contentX + contentW + 2, contentBottom);
		int by = contentY - Math.round(scroll);
		for (Block b : blocks) {
			int h = b.height(contentW);
			if (by + h >= contentY && by <= contentBottom) b.draw(ctx, contentX, by, contentW);
			by += h + 2;
		}
		g.disableScissor();
		if (maxScroll > 0) {
			int trackH = contentBottom - contentY;
			int thumbH = Math.max(20, Math.round(trackH * (trackH / (float) contentHeight)));
			int thumbY = contentY + Math.round((trackH - thumbH) * (scroll / maxScroll));
			RenderUtil.fillRounded(g, panelX + panelWidth - 5, thumbY, panelX + panelWidth - 3, thumbY + thumbH, 1, Theme.chrome(0xFF3A3A3A));
		}

		if (profileMenuOpen) drawProfileMenu(new PvUi.Ctx(g, mouseX, mouseY, 0, height));
		else if (!closing) { // no tooltips once the close animation starts
			ctx.flushTooltip();
			if (ctx.tooltip == null && ctx.tooltipItem == null) tabs.flushTooltip();
		}
	}

	private void centered(GuiGraphicsExtractor g, String text, int y, int color) {
		g.centeredText(font, text, panelX + panelWidth / 2, y, Theme.text(color));
	}

	private void fillPanel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, boolean tl, boolean tr, boolean bl, boolean br, int flat, int radius) {
		if (Theme.isPanelGradientActive()) {
			RenderUtil.fillPanelGradient(g, x0, y0, x1, y1, radius, Theme.panelBackground(), Theme.panelGradientColor2(),
				Theme.panelGradientPos1(), Theme.panelGradientPos2(), Theme.panelGradientMode(), Theme.panelGradientAngle(),
				tl, tr, bl, br, panelX, panelX + panelWidth);
		} else {
			RenderUtil.fillRounded(g, x0, y0, x1, y1, radius, flat, tl, tr, bl, br);
		}
	}

	private int pillButton(PvUi.Ctx c, String label, int x, int y, int h, boolean selected, Runnable action) {
		return pillButton(c, label, x, y, h, 12, selected, action);
	}

	private int pillButton(PvUi.Ctx c, String label, int x, int y, int h, int pad, boolean selected, Runnable action) {
		int w = c.width(label) + pad;
		boolean hover = c.hover(x, y, w, h);
		int color = selected ? (Theme.accent() | 0xFF000000) : Theme.chrome(0xFF2A2A2A);
		if (hover && !selected) color = Theme.chrome(0xFF3A3A3A);
		c.beginBox(y);
		c.round(x, y, x + w, y + h, 4, color);
		c.text(label, x + pad / 2, y + (h - 8) / 2, selected ? 0xFFFFFFFF : PvUi.textColor());
		c.endBox();
		hits.add(new Hit(x, y, w, h, action));
		return w;
	}

	/** Largest pill padding (12 down to 4) at which every label plus its gap fits in {@code available}. */
	private static int fittingPad(PvUi.Ctx c, List<String> labels, int gap, int available) {
		int text = 0;
		for (String l : labels) text += c.width(l) + gap;
		for (int pad = 12; pad > 4; pad--) if (text + pad * labels.size() <= available) return pad;
		return 4;
	}

	/** Category row, wrapping when it doesn't fit, then the subcategory row. Returns the y below them. */
	private int drawTabs(PvUi.Ctx c, int y) {
		int x = panelX + PAD;
		int right = panelX + panelWidth - PAD;
		// Per user request: one row each. Pill padding shrinks (12 -> 4) until the row fits; wrapping below
		// stays only as a last resort for a very narrow window.
		List<String> catLabels = new java.util.ArrayList<>();
		for (PvPages.Category cat : PvPages.CATEGORIES) catLabels.add(cat.name());
		int catPad = fittingPad(c, catLabels, 4, right - x);
		for (int i = 0; i < PvPages.CATEGORIES.size(); i++) {
			String label = PvPages.CATEGORIES.get(i).name();
			int w = c.width(label) + catPad;
			if (x + w > right) {
				x = panelX + PAD;
				y += TAB_HEIGHT + 3;
			}
			final int index = i;
			x += pillButton(c, label, x, y, TAB_HEIGHT, catPad, i == tab, () -> { if (tab != index) { introActive = false; tab = index; sub = 0; scroll = scrollTarget = 0; click(); } }) + 4;
		}
		y += TAB_HEIGHT + 4;
		List<PvPages.Sub> subs = PvPages.CATEGORIES.get(tab).subs();
		if (subs.size() > 1) {
			x = panelX + PAD;
			List<String> subLabels = new java.util.ArrayList<>();
			for (PvPages.Sub s : subs) subLabels.add(s.name());
			int subPad = fittingPad(c, subLabels, 3, right - x);
			for (int i = 0; i < subs.size(); i++) {
				String label = subs.get(i).name();
				int w = c.width(label) + subPad;
				if (x + w > right) {
					x = panelX + PAD;
					y += SUBTAB_HEIGHT + 3;
				}
				final int index = i;
				x += pillButton(c, label, x, y, SUBTAB_HEIGHT, subPad, i == sub, () -> { if (sub != index) { introActive = false; sub = index; scroll = scrollTarget = 0; click(); } }) + 3;
			}
			y += SUBTAB_HEIGHT + 2;
		}
		c.fill(panelX + PAD, y + 2, panelX + panelWidth - PAD, y + 3, Theme.chrome(0xFF2A2A2A));
		return y + 3;
	}

	private void drawProfileButton(PvUi.Ctx c) {
		String label = "Profile: " + profile.name() + (profile.modeLabel().isEmpty() ? "" : " (" + profile.modeLabel() + ")") + " ▾";
		int w = c.font.width(label) + 12;
		int x = panelX + panelWidth - PAD - w, y = panelY + (HEADER_HEIGHT - 16) / 2;
		boolean hover = c.hover(x, y, w, 16);
		c.beginBox(y);
		c.round(x, y, x + w, y + 16, 4, hover || profileMenuOpen ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF2A2A2A));
		c.text(label, x + 6, y + 4, PvUi.textColor());
		c.endBox();
		hits.add(new Hit(x, y, w, 16, () -> { profileMenuOpen = !profileMenuOpen; click(); }));
	}

	private void drawProfileMenu(PvUi.Ctx c) {
		// Drawn last and hit-tested first: an open menu sits above everything else.
		hits.clear();
		int rowH = 14, w = 150;
		int x = panelX + panelWidth - PAD - w, y = panelY + HEADER_HEIGHT;
		RenderUtil.fillRounded(c.g, x, y, x + w, y + profiles.size() * rowH + 4, 4, Theme.chrome(0xFF1B1B1B) | 0xFF000000);
		for (int i = 0; i < profiles.size(); i++) {
			PvProfile p = profiles.get(i);
			int ry = y + 2 + i * rowH;
			boolean hover = c.hover(x, ry, w, rowH);
			if (hover) c.g.fill(x + 2, ry, x + w - 2, ry + rowH, Theme.chrome(0xFF3A3A3A));
			String label = (p == profile ? "§a" : "§f") + p.name() + (p.isSelected() ? " §7(selected)" : "") + (p.modeLabel().isEmpty() ? "" : " §8" + p.modeLabel());
			c.g.text(c.font, label, x + 6, ry + 3, PvUi.textColor(), false);
			hits.add(new Hit(x, ry, w, rowH, () -> { profileMenuOpen = false; if (p != profile) selectProfile(p); click(); }));
		}
		hits.add(new Hit(0, 0, width, height, () -> profileMenuOpen = false));
	}

	private List<Block> page() {
		String key = tab + ":" + sub;
		List<Block> cached = pageCache.get(key);
		if (cached != null) return cached;
		List<Block> blocks = new ArrayList<>();
		if (tab == 0) blocks.add(new OverviewTop());
		try {
			blocks.addAll(PvPages.CATEGORIES.get(tab).subs().get(sub).build().apply(profile));
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Player Viewer: failed to build page {}", key, e);
			blocks.add(new PvUi.Note("This page couldn't be built from the API data.", 0xFFFF5555));
		}
		pageCache.put(key, blocks);
		return blocks;
	}

	// -----------------------------------------------------------------------------------------------------
	// Overview top section: player model + equipped gear on the left, summary on the right.

	private final class OverviewTop implements Block {
		private static final int MODEL_W = 110, HEIGHT = 196;

		public int height(int w) { return HEIGHT; }

		public void draw(PvUi.Ctx c, int x, int y, int w) {
			if (mannequin == null && minecraft != null && minecraft.level != null) {
				mannequin = new ViewerMannequin(UUID.fromString(dashed(profile.uuid)), profile.armor());
			}
			c.beginBox(y);
			c.round(x, y, x + MODEL_W, y + 150, 4, PvUi.cardColor());
			if (mannequin != null && !modelFailed && c.boxAlpha() > 0.02f && y + 150 > c.viewTop && y < c.viewBottom) {
				// Entity rendering takes plain screen coordinates and ignores the pose (the intro slide and the
				// close shrink), so map the model box through the current pose by hand.
				org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(c.g.pose());
				org.joml.Vector2f p0 = pose.transformPosition(new org.joml.Vector2f(x, Math.max(y, c.viewTop)));
				org.joml.Vector2f p1 = pose.transformPosition(new org.joml.Vector2f(x + MODEL_W, Math.min(y + 150, c.viewBottom)));
				float scale = pose.m00();
				int size = Math.round(60 * scale);
				if (size >= 2 && p1.x - p0.x >= 2 && p1.y - p0.y >= 2) {
					try {
						InventoryScreen.extractEntityInInventoryFollowsMouse(c.g, Math.round(p0.x), Math.round(p0.y), Math.round(p1.x), Math.round(p1.y),
							size, 0.0625f, c.mouseX, c.mouseY, mannequin);
					} catch (RuntimeException e) {
						// A preview model is never worth crashing the game over — hide it for this screen instead.
						modelFailed = true;
						com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Player Viewer: failed to render the player model", e);
					}
					c.veil(x + 1, Math.max(y, c.viewTop) + 1, x + MODEL_W - 1, Math.min(y + 150, c.viewBottom) - 1, PvUi.cardColor());
				}
			}
			c.endBox();
			List<ItemStack> armor = new ArrayList<>(profile.armor());
			java.util.Collections.reverse(armor);
			List<ItemStack> equipment = profile.equipment();
			for (int i = 0; i < 4; i++) {
				c.slot(i < armor.size() ? armor.get(i) : ItemStack.EMPTY, x + (MODEL_W - 78) / 2 + i * PvUi.SLOT, y + 154);
				c.slot(i < equipment.size() ? equipment.get(i) : ItemStack.EMPTY, x + (MODEL_W - 78) / 2 + i * PvUi.SLOT, y + 174);
			}

			int sx = x + MODEL_W + 10, sw = w - MODEL_W - 10;
			new PvUi.Header("Summary").draw(c, sx, y, sw);
			new PvUi.Stats(summaryStats(), 190).draw(c, sx, y + 18, sw);
		}
	}

	/** SkyBlock level bracket colors — same table SkyBlockAPI's ProfileAPI.getLevelColor uses. */
	private static String levelColor(int level) {
		String[] colors = {"§7", "§f", "§e", "§a", "§2", "§b", "§3", "§9", "§d", "§5", "§6", "§c", "§4"};
		return colors[Math.min(colors.length - 1, Math.max(0, level / 40))];
	}

	private static String dashed(String uuid) {
		return uuid.length() == 32 ? uuid.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5") : uuid;
	}

	private List<PvUi.Stat> summaryStats() {
		List<PvUi.Stat> s = new ArrayList<>();
		JsonObject status = data.status();
		JsonObject player = data.player();
		if (status != null) {
			boolean online = status.has("online") && status.get("online").getAsBoolean();
			String where = online ? " §7(" + PvUi.pretty(PvProfile.str(status, "mode", PvProfile.str(status, "gameType", ""))) + ")" : "";
			s.add(new PvUi.Stat("Status", (online ? "§aOnline" : "§cOffline") + where));
		} else {
			s.add(new PvUi.Stat("Status", "§7Unknown", 0, PvUi.lines("§7Online status needs the updated", "§7Cloudflare Worker (type=all).")));
		}
		long lastSeen = player != null ? (long) Math.max(PvProfile.num(player, "lastLogout", 0), PvProfile.num(player, "lastLogin", 0)) : 0;
		s.add(new PvUi.Stat("Last Online", lastSeen > 0 ? PvUi.duration(System.currentTimeMillis() - lastSeen) + " ago" : "§7Hidden"));

		double sbXp = profile.sbXp();
		s.add(new PvUi.Stat("SkyBlock Level", levelColor((int) (sbXp / 100)) + (int) (sbXp / 100), 0,
			PvUi.lines("§7Level XP: §f" + (int) (sbXp % 100) + " / 100", "§7Total XP: §f" + PvUi.commas(sbXp))));

		PvNetworth.Result nw = networth != null && networth.isDone() && !networth.isCompletedExceptionally() ? networth.getNow(null) : null;
		if (nw == null) {
			s.add(new PvUi.Stat("Networth", "§7Calculating..."));
		} else {
			List<Component> breakdown = new ArrayList<>();
			breakdown.add(Component.literal("§6Networth: §f" + PvUi.commas(nw.total())));
			breakdown.add(Component.literal(""));
			nw.categories().entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
				.forEach(e -> breakdown.add(Component.literal("§e" + e.getKey() + ": §a" + PvUi.shortNum(e.getValue()))));
			breakdown.add(Component.literal(""));
			breakdown.add(Component.literal("§7Unsoulbound: §6" + PvUi.commas(nw.unsoulbound())));
			if (!nw.pricesReady()) breakdown.add(Component.literal("§cAuction prices are still loading; reopen for a more accurate value."));
			s.add(new PvUi.Stat("Networth", "§6" + PvUi.shortNum(nw.total()), 0, breakdown));
		}
		s.add(new PvUi.Stat("Purse", "§6" + PvUi.shortNum(profile.purse())));
		s.add(new PvUi.Stat("Bank", "§6" + PvUi.shortNum(profile.bank() + profile.personalBank())));
		s.add(new PvUi.Stat("Skill Average", String.format(Locale.ROOT, "%.2f", profile.skillAverage())));
		JsonObject pet = profile.activePet();
		s.add(new PvUi.Stat("Active Pet", pet != null ? PvPages.RARITY_COLORS.getOrDefault(PvProfile.str(pet, "tier", ""), "§f")
			+ PvUi.pretty(PvProfile.str(pet, "type", "")) : "None"));
		s.add(new PvUi.Stat("Fairy Souls", PvUi.commas(PvProfile.num(PvProfile.obj(profile.member, "fairy_soul"), "total_collected", 0))));
		s.add(new PvUi.Stat("Magical Power", PvUi.commas(PvProfile.num(PvProfile.obj(profile.member, "accessory_bag_storage"), "highest_magical_power", 0))));
		s.add(new PvUi.Stat("First Join", profile.firstJoin() > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(profile.firstJoin())) : "-"));
		return s;
	}

	// -----------------------------------------------------------------------------------------------------
	// Input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			// Iterate a copy: a click can rebuild state (profile switch) that clears the list.
			for (Hit hit : new ArrayList<>(hits)) {
				if (hit.contains(event.x(), event.y())) {
					hit.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// Clamped here, not just at draw time, so scrolling past either end never starts an ease that then
		// has to bounce back (the "tries to scroll up at the top" jitter).
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (float) scrollY * 28f));
		return true;
	}

	private static void click() {
		if (!com.cokelord.skyblocksimplified.feature.impl.UiSoundEffectsFeature.isSoundEnabled()) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1f));
	}
}
