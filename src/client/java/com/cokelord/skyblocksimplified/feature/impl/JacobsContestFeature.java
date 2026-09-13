package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.JacobContestApi;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Jacob's Contest display — backed by the real contest schedule (JacobContestApi, same public API
 * SkyHanni uses), showing which contest is currently running (with its 3 crops) or, if none is active
 * right now, which crops the next contest will have and how long until it starts.
 */
public class JacobsContestFeature extends TabWidgetOverlayFeature {
	private static final long WARN_THRESHOLD_MILLIS = 60_000L;
	private static final int ICON_SIZE = 16;

	// Normalized (uppercase, spaces->underscores) crop name -> representative vanilla item icon. The
	// external contest API's exact crop-name casing/format isn't confirmed, so lookups normalize first
	// rather than matching a single assumed literal string.
	private static final Map<String, Item> CROP_ICONS = Map.ofEntries(
		Map.entry("WHEAT", Items.WHEAT),
		Map.entry("CARROT", Items.CARROT),
		Map.entry("POTATO", Items.POTATO),
		Map.entry("PUMPKIN", Items.PUMPKIN),
		Map.entry("MELON", Items.MELON),
		Map.entry("SUGAR_CANE", Items.SUGAR_CANE),
		Map.entry("NETHER_WART", Items.NETHER_WART),
		Map.entry("COCOA", Items.COCOA_BEANS),
		Map.entry("COCOA_BEANS", Items.COCOA_BEANS),
		Map.entry("MUSHROOM", Items.RED_MUSHROOM),
		Map.entry("CACTUS", Items.CACTUS),
		// Newer crops (confirmed real from SkyHanni's CropType enum). Per user correction: Moonflower is
		// Blue Orchid (not Chorus Flower — wrong guess), Wild Rose is Rose Bush.
		Map.entry("SUNFLOWER", Items.SUNFLOWER),
		Map.entry("MOONFLOWER", Items.BLUE_ORCHID),
		Map.entry("WILD_ROSE", Items.ROSE_BUSH)
	);

	private static ItemStack iconFor(String cropName) {
		String key = cropName.toUpperCase(Locale.ROOT).replace(' ', '_');
		Item item = CROP_ICONS.get(key);
		return item != null ? new ItemStack(item) : ItemStack.EMPTY;
	}

	private boolean warned = false;

	@Override
	protected boolean requiresGarden() {
		return true;
	}

	public JacobsContestFeature() {
		super("jacobs_contest_toggle", "Jacob's Contest", FeatureCategory.FARMING, "Events", 0.01f, 0.62f);
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled()) return;
		JacobContestApi.ContestSlot active = JacobContestApi.getActiveContest();
		if (active == null) {
			warned = false;
			return;
		}
		long msLeft = active.endEpochMillis() - System.currentTimeMillis();
		if (msLeft <= WARN_THRESHOLD_MILLIS) {
			if (!warned) {
				warned = true;
				var player = client.player;
				if (player != null) {
					client.gui.hud.getChat().addClientSystemMessage(Component.literal("§eJacob's Contest ending in §b" + formatDuration(msLeft) + "§e!"));
				}
			}
		} else {
			warned = false;
		}
	}

	// render() is overridden below to draw crop icons instead — currentLines() stays implemented only to
	// satisfy TabWidgetOverlayFeature's abstract contract, but is never actually invoked.
	@Override
	protected List<String> currentLines() {
		return List.of();
	}

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		if (!JacobContestApi.isLoaded()) {
			return drawLine(graphics, x, y, scale, "§eJacob's Contest: §7loading schedule...");
		}

		JacobContestApi.ContestSlot active = JacobContestApi.getActiveContest();
		if (active != null) {
			long msLeft = active.endEpochMillis() - System.currentTimeMillis();
			return drawContest(graphics, x, y, scale, "§aActive §7(§b" + formatDuration(msLeft) + "§7)", active.crops());
		}

		JacobContestApi.ContestSlot next = JacobContestApi.getNextContest();
		if (next == null) {
			return drawLine(graphics, x, y, scale, "§eJacob's Contest: §7no upcoming contest found");
		}
		long msUntil = next.startEpochMillis() - System.currentTimeMillis();
		return drawContest(graphics, x, y, scale, "§eNext §7(§b" + formatDuration(msUntil) + "§7)", next.crops());
	}

	/** Public entry point for a single line: x is the raw anchor, resolved to a left- or right-aligned
	 *  draw position depending on which side of the crosshair it's on. */
	private Size drawLine(GuiGraphicsExtractor graphics, int x, int y, float scale, String text) {
		var font = Minecraft.getInstance().font;
		int width = Math.round(font.width(text) * scale);
		return drawTextAt(graphics, resolveDrawX(x, width), y, scale, text, width);
	}

	/** Draws at an exact, already-resolved absolute position — used internally once a caller (drawContest)
	 *  has already worked out where a line belongs within a centered block, so alignment isn't resolved
	 *  a second time against that already-shifted coordinate. */
	private Size drawTextAt(GuiGraphicsExtractor graphics, int drawX, int y, float scale, String text, int width) {
		var font = Minecraft.getInstance().font;
		graphics.pose().pushMatrix();
		graphics.pose().translate(drawX, y);
		graphics.pose().scale(scale);
		graphics.pose().translate(-drawX, -y);
		graphics.text(font, Component.literal(text), drawX, y, 0xFFFFFFFF);
		graphics.pose().popMatrix();
		return new Size(width, Math.round(font.lineHeight * scale));
	}

	// This override bypasses TabWidgetOverlayFeature's own render()/currentLines() path entirely (see the
	// comment above), so it needs its own copy of that class's crosshair-side alignment logic to avoid text
	// running off the right edge of the screen when this widget is dragged right of screen center.
	private static int resolveDrawX(int anchorX, int width) {
		int screenCenterX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2;
		return anchorX > screenCenterX ? anchorX - width : anchorX;
	}

	private Size drawContest(GuiGraphicsExtractor graphics, int x, int y, float scale, String label, List<String> crops) {
		var font = Minecraft.getInstance().font;
		int labelWidth = Math.round(font.width(label) * scale);
		int iconSize = Math.round(ICON_SIZE * scale);
		int iconGap = Math.round(2 * scale);
		int iconsWidth = crops.isEmpty() ? 0 : crops.size() * iconSize + (crops.size() - 1) * iconGap;
		int blockWidth = Math.max(labelWidth, iconsWidth);

		// Center whichever row (label text or icon strip) is narrower under/above the wider one, rather
		// than left-aligning both to the same anchor — the icon strip is almost always narrower than the
		// label text ("Active (1m58s)" vs. 3 small icons), which previously left the icons bunched at the
		// left edge under a much wider line of text instead of centered beneath it.
		int blockX = resolveDrawX(x, blockWidth);
		int labelX = blockX + (blockWidth - labelWidth) / 2;
		int iconsStartX = blockX + (blockWidth - iconsWidth) / 2;

		int labelHeight = drawTextAt(graphics, labelX, y, scale, label, labelWidth).height();
		int iconY = y + labelHeight + 2;
		int iconX = iconsStartX;
		for (String crop : crops) {
			ItemStack stack = iconFor(crop);
			if (!stack.isEmpty()) {
				graphics.pose().pushMatrix();
				graphics.pose().translate(iconX, iconY);
				graphics.pose().scale(scale);
				graphics.pose().translate(-iconX, -iconY);
				graphics.item(stack, iconX, iconY);
				graphics.pose().popMatrix();
			}
			iconX += iconSize + iconGap;
		}
		return new Size(blockWidth, labelHeight + iconSize + 2);
	}

	private static String formatDuration(long millis) {
		long totalSeconds = Math.max(0, millis / 1000);
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return minutes > 0 ? minutes + "m" + seconds + "s" : seconds + "s";
	}

	@Override
	public String getDescription() {
		return "Shows which Jacob's Contest is currently running (and its 3 crops), or when the next one starts.";
	}
}
