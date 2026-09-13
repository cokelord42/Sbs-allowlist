package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.gui.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

/**
 * Real image-content half of the Personal Compactor tooltip — dispatched to by
 * ClientTooltipComponentCreateMixin whenever vanilla's own tooltip system resolves a
 * PersonalCompactorTooltipData, exactly the way vanilla's own ClientBundleTooltip is dispatched to for
 * BundleTooltip (confirmed via javap: ClientTooltipComponent.create(TooltipComponent) is a plain
 * instanceof-switch, getHeight/getWidth size the image region, extractImage(Font, x, y, w, h, graphics)
 * does the actual drawing — this MC version's renamed "extract" equivalent of the classic renderImage).
 * The per-slot grid/icon logic below is the same layout PersonalCompactorOverlayFeature's old standalone
 * panel used (columns capped at 7, per user request, so even the 12-slot tier stays 2 rows), just moved
 * from a hand-drawn floating panel into a real vanilla tooltip image so it always appears exactly where
 * (and only when) the game's own tooltip does — no separate hover-detection/positioning logic to keep in
 * sync with it.
 */
public class ClientPersonalCompactorTooltip implements ClientTooltipComponent {
	private static final int SLOT_PIXELS = PersonalCompactorOverlayFeature.SLOT_PIXELS;
	private static final int COLUMNS = PersonalCompactorOverlayFeature.COLUMNS;

	private final PersonalCompactorTooltipData data;

	public ClientPersonalCompactorTooltip(PersonalCompactorTooltipData data) {
		this.data = data;
	}

	private int columns() {
		return Math.min(COLUMNS, data.slotCount());
	}

	private int rows() {
		int columns = columns();
		return (data.slotCount() + columns - 1) / columns;
	}

	@Override
	public int getWidth(Font font) {
		return columns() * SLOT_PIXELS;
	}

	@Override
	public int getHeight(Font font) {
		// +2 top margin, matching the small gap vanilla's own ClientBundleTooltip leaves between the text
		// lines above and its own item grid.
		return 2 + rows() * SLOT_PIXELS;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
		int columns = columns();
		int rows = rows();
		int gridY = y + 2;
		// Only the trailing, possibly-incomplete row needs centering under the full row(s) above it — a
		// full row's own width already matches the grid width exactly.
		int lastRow = rows - 1;
		int itemsInLastRow = data.slotCount() - lastRow * columns;
		int lastRowOffsetPixels = (columns - itemsInLastRow) * SLOT_PIXELS / 2;
		for (int slot = 0; slot < data.slotCount(); slot++) {
			int row = slot / columns;
			int col = slot % columns;
			int cellX = x + col * SLOT_PIXELS + (row == lastRow ? lastRowOffsetPixels : 0);
			int cellY = gridY + row * SLOT_PIXELS;
			RenderUtil.fillRounded(graphics, cellX, cellY, cellX + SLOT_PIXELS - 2, cellY + SLOT_PIXELS - 2, 2, 0xFF2A2A2A);
			String stored = slot < data.slotItems().length ? data.slotItems()[slot] : null;
			if (stored == null) continue;
			PersonalCompactorOverlayFeature.IconResult resolved = PersonalCompactorOverlayFeature.iconFor(stored);
			graphics.item(resolved.icon(), cellX + 1, cellY + 1);
			// Same distinguishability fallback as the old panel: an unresolved item still gets its real
			// display-name initials badged in the corner instead of reading as an identical blank head.
			if (!resolved.matchedVanilla()) {
				String label = PersonalCompactorOverlayFeature.initialFor(stored);
				if (label != null) {
					graphics.text(font, label, cellX + SLOT_PIXELS - 2 - font.width(label) - 2, cellY + SLOT_PIXELS - 2 - 9, 0xFFFFFF55);
				}
			}
		}
	}
}
