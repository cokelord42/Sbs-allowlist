package com.cokelord.skyblocksimplified.diana.pf;

import com.cokelord.skyblocksimplified.feature.impl.diana.SboPartyFinderFeature;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.gui.Theme;
import com.cokelord.skyblocksimplified.pv.PvUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SBO's party finder (Diana + Custom party lists, filters, create/delete party, party details, join) in this
 * mod's own look: the Player Viewer's panel, header-rise/body-extend open animation, shrink close, rounded
 * cards and pill buttons. Data and actions go through {@link SboPartyFinder}.
 */
public class PartyFinderScreen extends Screen implements com.cokelord.skyblocksimplified.gui.BlurringScreen {
	private static final int PANEL_MIN_WIDTH = 420, PANEL_MAX_WIDTH = 760, PANEL_MIN_HEIGHT = 300, PANEL_MAX_HEIGHT = 560;
	private static final float PANEL_SIZE_FRACTION = 0.7f;
	private static final int PANEL_RADIUS = 8, HEADER_HEIGHT = 26, TAB_HEIGHT = 15, PAD = 10, CARD_HEIGHT = 46;
	private static final float HEADER_RISE = 0.2f, BODY_EXTEND = 0.2f, CLOSE_DURATION = 0.25f, SCROLL_EASE = 9f;
	private static final String[] PAGES = {"Diana", "Custom", "Help"};

	private enum View { LIST, INFO, CREATE }

	private final long openNanos = System.nanoTime();
	private long closeStartNanos;
	private boolean closing;

	private int page = 0;
	private View view = View.LIST;
	private boolean filterOpen = false;
	private PartyModels.Party infoParty;
	private float scroll, scrollTarget, maxScroll;
	private long lastFrameNanos;

	private int panelX, panelY, panelWidth, panelHeight;
	private final List<Hit> hits = new ArrayList<>();
	private String focusedField;
	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
	}

	public PartyFinderScreen() {
		super(Component.literal("SBO Party Finder"));
		SboPartyFinder.ownStats(stats -> {});
		refresh(false);
	}

	private String pageType() { return PAGES[page]; }

	private void refresh(boolean explicit) {
		if (page >= 2) return;
		String type = pageType();
		if (!explicit && SboPartyFinder.hasCache(type)) return;
		SboPartyFinder.refreshParties(type, explicit, null);
	}

	@Override
	protected void init() {
		panelWidth = clamp(Math.round(width * PANEL_SIZE_FRACTION), PANEL_MIN_WIDTH, Math.min(PANEL_MAX_WIDTH, width - 20));
		panelHeight = clamp(Math.round(height * PANEL_SIZE_FRACTION), PANEL_MIN_HEIGHT, Math.min(PANEL_MAX_HEIGHT, height - 20));
		panelX = (width - panelWidth) / 2;
		panelY = (height - panelHeight) / 2;
	}

	private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
	private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

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

	@Override
	public int effectiveBlurAmount() {
		float f = closing ? 1f - closeEase(System.nanoTime()) : PvUi.Ctx.expoOut(clamp01((System.nanoTime() - openNanos) / 1e9f / (HEADER_RISE + BODY_EXTEND)));
		return Math.max(0, Math.min(10, Math.round(com.cokelord.skyblocksimplified.gui.MainScreen.configuredBlurAmount() * f)));
	}

	private float closeEase(long now) {
		float t = clamp01((now - closeStartNanos) / 1e9f / CLOSE_DURATION);
		return t <= 0f ? 0f : (float) Math.pow(2, 10 * (t - 1));
	}

	@Override
	public void onClose() {
		if (!closing) {
			closing = true;
			closeStartNanos = System.nanoTime();
		}
	}

	// ---- rendering ----------------------------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		hits.clear();
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;
		scroll += (scrollTarget - scroll) * (1f - (float) Math.exp(-SCROLL_EASE * dt));
		if (Math.abs(scrollTarget - scroll) < 0.1f) scroll = scrollTarget;

		float scale = 1f;
		if (closing) {
			if (clamp01((now - closeStartNanos) / 1e9f / CLOSE_DURATION) >= 1f) {
				minecraft.gui.setScreen(null);
				return;
			}
			scale = 1f - closeEase(now);
		}
		float openT = (now - openNanos) / 1e9f;
		float headerP = PvUi.Ctx.expoOut(clamp01(openT / HEADER_RISE));
		float bodyP = PvUi.Ctx.expoOut(clamp01((openT - HEADER_RISE) / BODY_EXTEND));

		float cx = panelX + panelWidth / 2f, cy = panelY + panelHeight / 2f;
		g.pose().pushMatrix();
		if (scale < 1f) {
			g.pose().translate(cx, cy);
			g.pose().scale(scale);
			g.pose().translate(-cx, -cy);
		}
		try {
			drawPanel(g, mouseX, mouseY, headerP, bodyP);
		} finally {
			g.pose().popMatrix();
		}
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

	private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float headerP, float bodyP) {
		int bodyTop = panelY + HEADER_HEIGHT;
		int bodyBottom = bodyTop + Math.round((panelHeight - HEADER_HEIGHT) * bodyP);
		if (bodyBottom > bodyTop + 1) {
			fillPanel(g, panelX, bodyTop, panelX + panelWidth, bodyBottom, false, false, true, true, Theme.panelBackground(),
				Math.min(PANEL_RADIUS, (bodyBottom - bodyTop) / 2));
		}
		// Background bubbles, only over the panel body's own fill.
		com.cokelord.skyblocksimplified.gui.GuiBubbles.render(g, panelX, bodyTop, panelX + panelWidth, panelY + panelHeight,
			panelX, bodyTop, panelX + panelWidth, bodyBottom, bubbleOpen);
		int headerOffset = Math.round((height - panelY) * (1f - headerP));
		g.pose().pushMatrix();
		g.pose().translate(0, headerOffset);
		fillPanel(g, panelX, panelY, panelX + panelWidth, bodyTop, true, true, bodyP <= 0f, bodyP <= 0f, Theme.chrome(0xF0181818), PANEL_RADIUS);
		PvUi.Ctx header = new PvUi.Ctx(g, mouseX, mouseY, panelY, panelY + HEADER_HEIGHT);
		header.text("SBO Party Finder  §7» §f" + pageType(), panelX + PAD, panelY + (HEADER_HEIGHT - 8) / 2, PvUi.textColor());
		String online = SboPartyFinder.activeUsers >= 0 ? "§7Online: §a" + SboPartyFinder.activeUsers : "";
		if (!online.isEmpty()) header.text(online, panelX + panelWidth - PAD - header.width(online), panelY + (HEADER_HEIGHT - 8) / 2, PvUi.textColor());
		g.pose().popMatrix();
		if (bodyBottom <= bodyTop + 1) return;

		g.enableScissor(panelX, bodyTop, panelX + panelWidth, bodyBottom);
		PvUi.Ctx c = new PvUi.Ctx(g, mouseX, mouseY, bodyTop, bodyBottom);
		int y = drawTabs(c, bodyTop + 6);
		int contentTop = y + 4;
		int contentBottom = Math.min(bodyBottom, panelY + panelHeight - 8);
		if (page == 2) drawHelp(c, contentTop);
		else if (view == View.CREATE) drawCreate(c, contentTop);
		else if (view == View.INFO && infoParty != null) drawInfo(c, contentTop, contentBottom);
		else drawList(c, contentTop, contentBottom);
		g.disableScissor();
		if (filterOpen && view == View.LIST && page < 2) drawFilter(new PvUi.Ctx(g, mouseX, mouseY, 0, height));
		if (!closing) c.flushTooltip();
	}

	private int pill(PvUi.Ctx c, String label, int x, int y, int h, boolean selected, Runnable action) {
		int w = c.width(label) + 12;
		boolean hover = c.hover(x, y, w, h);
		int color = selected ? (Theme.accent() | 0xFF000000) : hover ? Theme.chrome(0xFF3A3A3A) : Theme.chrome(0xFF2A2A2A);
		c.round(x, y, x + w, y + h, 4, color);
		c.text(label, x + 6, y + (h - 8) / 2, selected ? 0xFFFFFFFF : PvUi.textColor());
		hits.add(new Hit(x, y, w, h, action));
		return w;
	}

	private int drawTabs(PvUi.Ctx c, int y) {
		int x = panelX + PAD;
		for (int i = 0; i < PAGES.length; i++) {
			final int index = i;
			x += pill(c, PAGES[i], x, y, TAB_HEIGHT, i == page, () -> {
				if (page == index) return;
				page = index;
				view = View.LIST;
				filterOpen = false;
				focusedField = null;
				scroll = scrollTarget = 0;
				refresh(false);
				click();
			}) + 4;
		}
		if (page < 2 && view == View.LIST) {
			int right = panelX + panelWidth - PAD;
			String[] labels = {"Create", "Delete", "Filter", "Refresh"};
			Runnable[] actions = {
				() -> { view = View.CREATE; filterOpen = false; click(); },
				() -> { SboPartyFinder.dequeue(() -> SboPartyFinder.refreshParties(pageType(), false, null)); click(); },
				() -> { filterOpen = !filterOpen; click(); },
				() -> { SboPartyFinder.refreshParties(pageType(), true, null); click(); }};
			for (int i = 0; i < labels.length; i++) {
				String label = labels[i];
				if (label.equals("Delete") && !SboPartyFinder.inQueue) continue;
				int w = c.width(label) + 12;
				right -= w;
				pill(c, label, right, y, TAB_HEIGHT, label.equals("Filter") && filterOpen, actions[i]);
				right -= 4;
			}
		}
		y += TAB_HEIGHT + 4;
		c.fill(panelX + PAD, y + 2, panelX + panelWidth - PAD, y + 3, Theme.chrome(0xFF2A2A2A));
		return y + 3;
	}

	// ---- list ---------------------------------------------------------------------------------------------

	private List<PartyModels.Party> filtered() {
		SboPartyFinderFeature f = SboPartyFinderFeature.get();
		String type = pageType();
		List<PartyModels.Party> all = SboPartyFinder.cachedParties(type);
		if (f == null) return all;
		String prefix = type.toLowerCase(Locale.ROOT) + "_";
		boolean eman9 = f.bool(prefix + "filterEman9");
		boolean looting5 = type.equals("Diana") && f.bool(prefix + "filterLooting5");
		boolean canJoin = f.bool(prefix + "filterCanJoin");
		PartyModels.PlayerStats stats = SboPartyFinder.cachedOwnStats();
		List<PartyModels.Party> out = new ArrayList<>();
		for (PartyModels.Party p : all) {
			if (eman9 && !p.reqs().eman9()) continue;
			if (looting5 && !p.reqs().looting5()) continue;
			if (canJoin) {
				PartyModels.Reqs r = p.reqs();
				if (r.lvl() > 0 && stats.sbLvl() < r.lvl()) continue;
				if (type.equals("Diana")) {
					if (r.kills() > 0 && stats.mythosKills() < r.kills()) continue;
					if (r.eman9() && !stats.eman9()) continue;
					if (r.looting5() && !stats.looting5daxe()) continue;
				} else if (r.mp() > 0 && stats.magicalPower() < r.mp()) continue;
			}
			out.add(p);
		}
		return out;
	}

	private String reqsString(PartyModels.Reqs r) {
		PartyModels.PlayerStats s = SboPartyFinder.cachedOwnStats();
		List<String> parts = new ArrayList<>();
		if (r.lvl() > 0) parts.add("§bLvl: " + (s.sbLvl() >= r.lvl() ? "§a" : "§c") + r.lvl());
		if (page == 0 && r.kills() > 0) parts.add("§bKills: " + (s.mythosKills() >= r.kills() ? "§a" : "§c") + PvUi.shortNum(r.kills()));
		if (page == 1 && r.mp() > 0) parts.add("§bMP: " + (s.magicalPower() >= r.mp() ? "§a" : "§c") + r.mp());
		if (r.eman9()) parts.add(s.eman9() ? "§aEman9" : "§cEman9");
		if (page == 0 && r.looting5()) parts.add(s.looting5daxe() ? "§aLooting5" : "§cLooting5");
		return parts.isEmpty() ? "§7No requirements" : String.join("§7, ", parts);
	}

	private void drawList(PvUi.Ctx c, int top, int bottom) {
		String type = pageType();
		List<PartyModels.Party> parties = filtered();
		int x = panelX + PAD, w = panelWidth - PAD * 2;
		String status = SboPartyFinder.isRefreshing(type) ? "§7Refreshing..." : "§7" + parties.size() + " part" + (parties.size() == 1 ? "y" : "ies")
			+ (SboPartyFinder.inQueue ? "  §a● Your party is queued" : "");
		c.text(status, x, top, PvUi.textColor());
		int listTop = top + 12;
		if (SboPartyFinder.key().isBlank()) {
			// No SBO key yet: the backend rejects every request without one.
			c.round(x, listTop, x + w, listTop + 24, 6, PvUi.cardColor());
			c.text("§eNo SBO key set.", x + 8, listTop + 8, PvUi.textColor());
			int bx = x + w - 8;
			String paste = "Paste Key", get = "Get Key";
			bx -= c.width(paste) + 12;
			pill(c, paste, bx, listTop + 5, 14, false, () -> { SboPartyFinderFeature f = SboPartyFinderFeature.get(); if (f != null) f.pasteKey(); click(); });
			bx -= c.width(get) + 12 + 4;
			pill(c, get, bx, listTop + 5, 14, true, () -> { SboPartyFinderFeature.openDiscord(); click(); });
			listTop += 28;
		}
		int total = parties.size() * (CARD_HEIGHT + 4);
		maxScroll = Math.max(0, total - (bottom - listTop));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		if (parties.isEmpty()) {
			if (!SboPartyFinder.isRefreshing(type)) c.text("§7No parties right now. Create one, or refresh in a bit.", x, listTop + 8, PvUi.textColor());
			return;
		}
		c.g.enableScissor(x - 1, listTop, x + w + 1, bottom);
		int y = listTop - Math.round(scroll);
		for (PartyModels.Party p : parties) {
			if (y + CARD_HEIGHT >= listTop && y <= bottom) drawCard(c, p, x, y, w, listTop, bottom);
			y += CARD_HEIGHT + 4;
		}
		c.g.disableScissor();
		if (maxScroll > 0) {
			int trackH = bottom - listTop;
			int thumbH = Math.max(20, Math.round(trackH * (trackH / (float) total)));
			int thumbY = listTop + Math.round((trackH - thumbH) * (scroll / maxScroll));
			RenderUtil.fillRounded(c.g, panelX + panelWidth - 5, thumbY, panelX + panelWidth - 3, thumbY + thumbH, 1, Theme.chrome(0xFF3A3A3A));
		}
	}

	private void drawCard(PvUi.Ctx c, PartyModels.Party p, int x, int y, int w, int clipTop, int clipBottom) {
		boolean hover = c.hover(x, y, w, CARD_HEIGHT) && c.mouseY >= clipTop && c.mouseY < clipBottom;
		c.round(x, y, x + w, y + CARD_HEIGHT, 6, hover ? Theme.chrome(0xFF262626) : PvUi.cardColor());
		c.text("§l" + p.leaderName(), x + 8, y + 6, PvUi.textColor());
		c.text(reqsString(p.reqs()), x + 8, y + 18, PvUi.textColor());
		String note = p.note().isBlank() ? "" : "§bNote: §7" + p.note();
		if (!note.isEmpty()) c.text(note, x + 8, y + 30, PvUi.textColor());
		String count = "§f" + p.memberCount() + "§7/§f" + p.partySize();
		int joinW = c.width("Join") + 16;
		int joinX = x + w - 8 - joinW, joinY = y + (CARD_HEIGHT - 16) / 2;
		c.text(count, joinX - 8 - c.width(count), y + (CARD_HEIGHT - 8) / 2, PvUi.textColor());
		boolean joinHover = c.hover(joinX, joinY, joinW, 16);
		c.round(joinX, joinY, joinX + joinW, joinY + 16, 4, joinHover ? (Theme.accent() | 0xFF000000) : Theme.chrome(0xFF2E2E2E));
		c.text("Join", joinX + 8, joinY + 4, 0xFFFFFFFF);
		if (y < clipTop || y + CARD_HEIGHT > clipBottom) {
			// Partially scrolled out: only the visible part is clickable.
			int vy0 = Math.max(y, clipTop), vy1 = Math.min(y + CARD_HEIGHT, clipBottom);
			if (joinY >= clipTop && joinY + 16 <= clipBottom) hits.add(new Hit(joinX, joinY, joinW, 16, () -> { SboPartyFinder.joinParty(p); click(); }));
			hits.add(new Hit(x, vy0, w, vy1 - vy0, () -> openInfo(p)));
		} else {
			hits.add(new Hit(joinX, joinY, joinW, 16, () -> { SboPartyFinder.joinParty(p); click(); }));
			hits.add(new Hit(x, y, w, CARD_HEIGHT, () -> openInfo(p)));
		}
	}

	private void openInfo(PartyModels.Party p) {
		infoParty = p;
		view = View.INFO;
		filterOpen = false;
		scroll = scrollTarget = 0;
		click();
	}

	private void drawFilter(PvUi.Ctx c) {
		SboPartyFinderFeature f = SboPartyFinderFeature.get();
		if (f == null) return;
		String prefix = pageType().toLowerCase(Locale.ROOT) + "_";
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[]{"Eman9", prefix + "filterEman9"});
		if (page == 0) rows.add(new String[]{"Looting 5", prefix + "filterLooting5"});
		rows.add(new String[]{"Can I Join?", prefix + "filterCanJoin"});
		int w = 120, rowH = 16;
		int x = panelX + panelWidth - PAD - w, y = panelY + HEADER_HEIGHT + 6 + TAB_HEIGHT + 4;
		List<Hit> saved = new ArrayList<>(hits);
		hits.clear();
		RenderUtil.fillRounded(c.g, x, y, x + w, y + rows.size() * rowH + 6, 5, Theme.chrome(0xFF1B1B1B) | 0xFF000000);
		for (int i = 0; i < rows.size(); i++) {
			String[] row = rows.get(i);
			int ry = y + 3 + i * rowH;
			boolean on = f.bool(row[1]);
			if (c.hover(x, ry, w, rowH)) c.g.fill(x + 2, ry, x + w - 2, ry + rowH, Theme.chrome(0xFF2A2A2A));
			RenderUtil.fillRounded(c.g, x + 6, ry + 3, x + 16, ry + 13, 2, on ? (Theme.accent() | 0xFF000000) : Theme.chrome(0xFF3A3A3A));
			c.g.text(c.font, row[0], x + 22, ry + 4, PvUi.textColor(), false);
			hits.add(new Hit(x, ry, w, rowH, () -> {
				f.setBool(row[1], !f.bool(row[1]));
				com.cokelord.skyblocksimplified.config.ConfigManager.save();
				click();
			}));
		}
		// Clicking outside closes it; everything else stays clickable underneath.
		hits.add(new Hit(x - 1, y - 1, w + 2, rows.size() * rowH + 8, () -> {}));
		hits.addAll(saved);
		hits.add(new Hit(0, 0, width, height, () -> filterOpen = false));
	}

	// ---- party details ------------------------------------------------------------------------------------

	private void drawInfo(PvUi.Ctx c, int top, int bottom) {
		PartyModels.Party p = infoParty;
		int x = panelX + PAD, w = panelWidth - PAD * 2;
		pill(c, "« Back", x, top, 14, false, () -> { view = View.LIST; scroll = scrollTarget = 0; click(); });
		int joinW = c.width("Join Party") + 12;
		pill(c, "Join Party", x + w - joinW, top, 14, false, () -> { SboPartyFinder.joinParty(p); click(); });
		c.text("§l" + p.leaderName() + "'s party  §7" + p.memberCount() + "/" + p.partySize(), x + 60, top + 3, PvUi.textColor());
		int listTop = top + 20;
		int colW = Math.max(180, (w - 8) / 2);
		int cols = Math.max(1, (w + 8) / (colW + 8));
		colW = (w - (cols - 1) * 8) / cols;
		int cardH = page == 0 ? 142 : 90;
		int rowsCount = (int) Math.ceil(p.members().size() / (double) cols);
		int total = rowsCount * (cardH + 6);
		maxScroll = Math.max(0, total - (bottom - listTop));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		c.g.enableScissor(x - 1, listTop, x + w + 1, bottom);
		for (int i = 0; i < p.members().size(); i++) {
			PartyModels.PlayerStats s = p.members().get(i);
			int cx = x + (i % cols) * (colW + 8);
			int cy = listTop + (i / cols) * (cardH + 6) - Math.round(scroll);
			if (cy > bottom || cy + cardH < listTop) continue;
			c.round(cx, cy, cx + colW, cy + cardH, 6, PvUi.cardColor());
			List<String> lines = new ArrayList<>();
			lines.add("§b§l" + s.name());
			lines.add("§9Skyblock Level: " + PartyModels.lvlColor(s.sbLvl()));
			lines.add("§9Eman: " + PartyModels.numberColor(s.emanLvl(), 9) + (s.eman9() ? " §a✔" : ""));
			lines.add("§9Clover: " + (s.clover() ? "§a✔" : "§c✘"));
			if (page == 0) {
				lines.add("§9Looting: " + PartyModels.numberColor(s.daxeLootingLvl(), 5) + "  §9Chimera: " + PartyModels.numberColor(s.daxeChimLvl(), 5));
				lines.add("§9Griffin: " + PartyModels.rarityColor(s.griffinRarity()) + " §7" + PartyModels.griffinItemColor(s.griffinItem()));
				lines.add("§9Diana Kills: " + PartyModels.killsColor(s.mythosKills()));
				lines.add("§9Leaderboard: §b#" + (s.killLeaderboard() >= 999999 ? "-" : String.valueOf(s.killLeaderboard())));
			}
			lines.add("§9Magical Power: §b" + s.magicalPower());
			lines.add("§9Enrichments: §b" + s.enrichments() + " §7(missing " + s.missingEnrichments() + ")");
			if (!s.warnings().isEmpty()) lines.add("§c" + String.join(", ", s.warnings()));
			int ly = cy + 6;
			for (String line : lines) {
				c.text(line, cx + 8, ly, PvUi.textColor());
				ly += 12;
			}
		}
		c.g.disableScissor();
	}

	// ---- create -------------------------------------------------------------------------------------------

	private int field(PvUi.Ctx c, String label, String key, int x, int y, int w, boolean numeric) {
		SboPartyFinderFeature f = SboPartyFinderFeature.get();
		c.text("§7" + label, x, y, PvUi.textColor());
		int fy = y + 11;
		boolean focused = key.equals(focusedField);
		c.round(x, fy, x + w, fy + 16, 4, focused ? Theme.chrome(0xFF333333) : Theme.chrome(0xFF232323));
		String value = f == null ? "" : f.string(key);
		boolean caret = focused && (System.currentTimeMillis() / 500) % 2 == 0;
		c.text((value.isEmpty() && !focused ? "§8" + (numeric ? "0" : "optional") : value) + (caret ? "_" : ""), x + 5, fy + 4, PvUi.textColor());
		hits.add(new Hit(x, fy, w, 16, () -> focusedField = key));
		return fy + 22;
	}

	private int checkbox(PvUi.Ctx c, String label, String key, int x, int y) {
		SboPartyFinderFeature f = SboPartyFinderFeature.get();
		boolean on = f != null && f.bool(key);
		int w = 14 + c.width(label) + 8;
		RenderUtil.fillRounded(c.g, x, y, x + 12, y + 12, 3, on ? (Theme.accent() | 0xFF000000) : Theme.chrome(0xFF3A3A3A));
		if (on) c.text("✔", x + 2, y + 2, 0xFFFFFFFF);
		c.text(label, x + 16, y + 2, PvUi.textColor());
		hits.add(new Hit(x, y, w, 12, () -> {
			if (f == null) return;
			f.setBool(key, !f.bool(key));
			com.cokelord.skyblocksimplified.config.ConfigManager.save();
			click();
		}));
		return w;
	}

	private void drawCreate(PvUi.Ctx c, int top) {
		String p = pageType().toLowerCase(Locale.ROOT) + "_";
		int w = Math.min(260, panelWidth - PAD * 2);
		int x = panelX + (panelWidth - w) / 2;
		pill(c, "« Back", panelX + PAD, top, 14, false, () -> { view = View.LIST; focusedField = null; click(); });
		c.text("§lCreate " + pageType() + " Party", x, top + 3, PvUi.textColor());
		int y = top + 22;
		y = field(c, "SkyBlock Level", p + "lvl", x, y, w, true);
		if (page == 0) y = field(c, "Diana Kills", p + "kills", x, y, w, true);
		else {
			y = field(c, "Magical Power", p + "mp", x, y, w, true);
			y = field(c, "Party Size (2-6)", p + "size", x, y, w, true);
		}
		y = field(c, "Note (30 characters)", p + "note", x, y, w, false);
		int cx = x;
		cx += checkbox(c, "Eman9", p + "eman9", cx, y) + 12;
		if (page == 0) checkbox(c, "Looting5", p + "looting5", cx, y);
		y += 22;
		int bw = c.width("Create Party") + 24;
		pill(c, "Create Party", x + (w - bw) / 2, y, 18, true, this::submitCreate);
	}

	private void submitCreate() {
		SboPartyFinderFeature f = SboPartyFinderFeature.get();
		if (f == null) return;
		String p = pageType().toLowerCase(Locale.ROOT) + "_";
		int lvl = parse(f.string(p + "lvl"));
		PartyModels.Reqs reqs;
		int size = 6;
		if (page == 0) {
			reqs = new PartyModels.Reqs(lvl, parse(f.string(p + "kills")), f.bool(p + "eman9"), f.bool(p + "looting5"), 0);
		} else {
			reqs = new PartyModels.Reqs(lvl, 0, f.bool(p + "eman9"), false, parse(f.string(p + "mp")));
			int s = parse(f.string(p + "size"));
			size = s <= 0 ? 6 : Math.max(2, Math.min(6, s));
		}
		SboPartyFinder.createParty(reqs, f.string(p + "note"), pageType(), size);
		view = View.LIST;
		focusedField = null;
		click();
	}

	private static int parse(String s) {
		try {
			return s.isBlank() ? 0 : Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// ---- help ---------------------------------------------------------------------------------------------

	private void drawHelp(PvUi.Ctx c, int top) {
		String[] lines = {
			"§lSBO Party Finder",
			"",
			"§7・ Find Diana parties with requirements Hypixel's finder doesn't offer.",
			"§7・ Create your own party (you must be the leader) or join someone else's.",
			"§7・ Joining sends the leader an SBO join request by /msg; if you meet their",
			"§7   requirements they'll invite you, and the invite is accepted automatically.",
			"§7・ Lists and stats come from the SBO backend, run by the Skyblock Overhaul team.",
			"",
			"§eNeeds an SBO key: §7get one in the SBO Discord and paste it into",
			"§7Events > Diana > SBO Party Finder in the mod menu.",
			"",
			"§eCommands: §7/sbopf, /sbscheck <player>, /sbscheckparty, /sbsrequeue, /sbsdequeue"};
		int y = top + 4;
		for (String line : lines) {
			c.text(line, panelX + PAD + 4, y, PvUi.textColor());
			y += 12;
		}
	}

	// ---- input --------------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			for (Hit hit : new ArrayList<>(hits)) {
				if (hit.contains(event.x(), event.y())) {
					focusedField = null; // a text field's own hit re-focuses it
					hit.action().run();
					return true;
				}
			}
			focusedField = null;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (float) scrollY * 28f));
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (focusedField == null) return super.charTyped(event);
		SboPartyFinderFeature f = SboPartyFinderFeature.get();
		if (f == null) return true;
		String value = f.string(focusedField);
		boolean numeric = !focusedField.endsWith("note");
		String typed = event.codepointAsString();
		if (numeric && !typed.chars().allMatch(Character::isDigit)) return true;
		int max = focusedField.endsWith("note") ? 30 : focusedField.endsWith("kills") ? 6 : focusedField.endsWith("size") ? 1 : 4;
		if (value.length() + typed.length() > max) return true;
		f.setString(focusedField, value + typed);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (focusedField != null) {
			SboPartyFinderFeature f = SboPartyFinderFeature.get();
			if (event.key() == GLFW.GLFW_KEY_BACKSPACE && f != null) {
				String value = f.string(focusedField);
				if (!value.isEmpty()) f.setString(focusedField, value.substring(0, value.length() - 1));
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER || event.key() == GLFW.GLFW_KEY_ESCAPE) {
				focusedField = null;
				com.cokelord.skyblocksimplified.config.ConfigManager.save();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_TAB) return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void removed() {
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
		super.removed();
	}

	private static void click() {
		if (!com.cokelord.skyblocksimplified.feature.impl.UiSoundEffectsFeature.isSoundEnabled()) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1f));
	}
}
