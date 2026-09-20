package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.BazaarApi;
import com.cokelord.skyblocksimplified.api.NeuInternalName;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows what every garden visitor spoken to this session (not just the one currently open) still needs —
 * ported from SkyHanni's GardenVisitorTooltip.kt (readShoppingList, confirmed "§7Items Required:" section
 * header and item-amount line format) but reworked per user request into a real cross-visitor "pile up"
 * list: each visitor's own required items are tracked separately (keyed by their info item's own display
 * name), summed together into one merged view, and only a specific visitor's own contribution is removed
 * — the moment THEIR "Accept Offer" is actually clicked — rather than the whole list clearing or a new
 * visitor just overwriting whatever the last one needed. Visible (and clickable) on every container
 * screen, not just the visitor's own offer screen, so it's still there to check against once a line's
 * click opens the bazaar for that item — real /bz search, ported command format.
 */
public class VisitorShoppingListFeature extends Feature {
	private static final int INFO_SLOT = 13;
	private static final int ACCEPT_SLOT = 29;
	private static final int REFUSE_SLOT = 33;
	private static final String OFFERS_ACCEPTED_PREFIX = "Offers Accepted: ";
	private static final String REQUIRED_HEADER = "Items Required:";
	// Per user report, the bazaar search was including the leading amount (e.g. "x8 Enchanted Pumpkin"
	// searched literally, which /bz can't match) — the old single combined regex apparently wasn't reliably
	// separating the amount from the name for every real line format. This strips it as an explicit, separate
	// first pass instead (handling "8x Name" and "x8 Name" orderings, PLUS a bare "167 Name" with no "x" at
	// all — per the user's own worked example, "167 enchanted pumpkins" — since the real Hypixel "Items
	// Required" lore apparently doesn't always use an "x" multiplier separator), so whatever remains
	// afterward is unambiguously just the item name, used for BOTH the display line and the /bz search term.
	private static final Pattern LEADING_AMOUNT = Pattern.compile(
		"^(?:(?<amt1>[\\d.,]+)(?<suf1>[km])?x\\s+|x(?<amt2>[\\d.,]+)(?<suf2>[km])?\\s+|(?<amt3>[\\d.,]+)(?<suf3>[km])?\\s+)", Pattern.CASE_INSENSITIVE);
	// Per user report, the real line is actually "Enchanted Carrot x553" — the amount TRAILS the name with
	// an "x" separator, a format none of LEADING_AMOUNT's alternatives cover (they're all anchored to the
	// start of the string). Checked only when no leading amount matched, so a genuine leading format never
	// gets double-stripped.
	private static final Pattern TRAILING_AMOUNT = Pattern.compile(
		"\\s+x(?<amt>[\\d.,]+)(?<suf>[km])?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern COLOR_CODE = Pattern.compile("§.");

	private record ShoppingItem(long amount, double totalPrice) {}

	private record ClickableLine(int x, int y, int width, int height, String plainItemName) {}

	// Per-visitor contribution, keyed by the info item's own real display name (Hypixel titles that item
	// after the visitor it's describing) — lets accepting one specific visitor's offer remove exactly
	// their own items without touching anyone else's still-pending list. Deliberately NOT reset on GUI
	// close/reopen: the whole point of "pile up" is that it survives walking away and talking to the next
	// visitor, only ever cleared by an explicit Accept/Refuse click (see onOfferClicked).
	private static final LinkedHashMap<String, LinkedHashMap<String, ShoppingItem>> perVisitor = new LinkedHashMap<>();

	// Screen-space bounds of each rendered merged line THIS frame, for click hit-testing — recomputed every
	// render call, same "hit-test only ever trusts this frame's own just-drawn positions" pattern
	// NeuStyleButtonsFeature's own button ring already relies on.
	private final List<ClickableLine> clickableLines = new ArrayList<>();
	private boolean registeredClickRule = false;

	public VisitorShoppingListFeature() {
		super("visitor_shopping_list", "Visitor Shopping List", FeatureCategory.FARMING, false);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				// Per user request ("hide outside garden"): garden visitors are only ever real on the Garden
				// island, but this used to render the aggregate pile on EVERY container screen (any chest,
				// any other island's menu) the instant it wasn't empty — clutter on screens the list can
				// never actually be relevant to. Visitor GUIs themselves only ever open on the Garden anyway,
				// so this only ever changes anything for screens OTHER than a visitor's own offer screen.
				if (!isEnabled() || !com.cokelord.skyblocksimplified.util.IslandGate.isInGarden()) return;
				try {
					updateFromCurrentVisitor(containerScreen);
					renderAggregate(graphics);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Visitor Shopping List render failed, skipping this frame", e);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
				if (!isEnabled() || !com.cokelord.skyblocksimplified.util.IslandGate.isInGarden()) return true;
				return handleClick(event.x(), event.y());
			});
		});
	}

	@Override
	protected void onEnable() {
		if (!registeredClickRule) {
			// Purely observational — always returns false (never vetoes) — see onOfferClicked's own doc
			// comment for why this is a safe use of a "should I cancel this click" registry.
			ContainerClickRegistry.setRule(getId(), this::onOfferClicked);
			registeredClickRule = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registeredClickRule) {
			ContainerClickRegistry.clearRule(getId());
			registeredClickRule = false;
		}
	}

	@Override
	public String getSubcategory() {
		return "Visitors";
	}

	// Reuses ContainerClickRegistry purely to OBSERVE the real Accept/Refuse Offer click (it's the single
	// choke point every real slot click already passes through) — never actually blocks it (always returns
	// false), just uses the moment it happens to drop that one visitor's contribution out of the pile. Per
	// user request, BOTH accept and decline remove the entry (either way, that visitor is no longer pending).
	//
	// Real bug found ("decline detection" never worked): this used to also require the clicked slot's own
	// hover name to read exactly "Refuse Offer" — an assumption never actually confirmed against real
	// Hypixel text, unlike the sibling RareRewardWarningFeature (same INFO_SLOT/ACCEPT_SLOT/REFUSE_SLOT
	// trio), which deliberately fires on REFUSE_SLOT by index alone with no text check at all. If the real
	// button's text is worded differently (or carries formatting this string compare didn't tolerate), a
	// decline click could never match, silently leaving that visitor's items stuck in the pile forever.
	// Matches RareRewardWarningFeature's own confirmed-safe pattern now: Accept still requires its own
	// "Accept Offer" text (that comparison IS relied on elsewhere in this codebase and known to work), but
	// Refuse only needs to be the right slot index with something actually in it.
	private boolean onOfferClicked(Slot slot, int slotId, int mouseButton, net.minecraft.world.inventory.ContainerInput type) {
		if (slotId != ACCEPT_SLOT && slotId != REFUSE_SLOT) return false;
		if (!slot.hasItem()) return false;
		if (slotId == ACCEPT_SLOT && !"Accept Offer".equals(slot.getItem().getHoverName().getString())) return false;

		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
			AbstractContainerMenu menu = screen.getMenu();
			if (menu.slots.size() > INFO_SLOT) {
				ItemStack infoStack = menu.getSlot(INFO_SLOT).getItem();
				if (isVisitorInfo(infoStack)) {
					perVisitor.remove(infoStack.getHoverName().getString());
				}
			}
		}
		return false;
	}

	private void updateFromCurrentVisitor(AbstractContainerScreen<?> screen) {
		AbstractContainerMenu menu = screen.getMenu();
		if (menu.slots.size() <= ACCEPT_SLOT) return;

		ItemStack infoStack = menu.getSlot(INFO_SLOT).getItem();
		if (!isVisitorInfo(infoStack)) return;
		ItemStack acceptStack = menu.getSlot(ACCEPT_SLOT).getItem();
		if (!"Accept Offer".equals(acceptStack.getHoverName().getString())) return;

		perVisitor.put(infoStack.getHoverName().getString(), readShoppingList(acceptStack));
	}

	private void renderAggregate(GuiGraphicsExtractor graphics) {
		clickableLines.clear();
		if (perVisitor.isEmpty()) return;

		LinkedHashMap<String, ShoppingItem> merged = new LinkedHashMap<>();
		for (LinkedHashMap<String, ShoppingItem> items : perVisitor.values()) {
			for (var entry : items.entrySet()) {
				merged.merge(entry.getKey(), entry.getValue(),
					(a, b) -> new ShoppingItem(a.amount() + b.amount(), a.totalPrice() + b.totalPrice()));
			}
		}
		if (merged.isEmpty()) return;

		Font font = Minecraft.getInstance().font;
		int x = 10;
		int y = 10;
		graphics.text(font, Component.literal("§6§lVisitors need §7(click to search bazaar)§6§l:"), x, y, 0xFFFFFFFF);
		y += 10;
		for (var entry : merged.entrySet()) {
			ShoppingItem item = entry.getValue();
			String priceText = item.totalPrice() > 0
				? " §7(§6" + String.format(Locale.ROOT, "%,.0f", item.totalPrice()) + "§7)" : "";
			String line = "§7- §e" + item.amount() + "x §f" + entry.getKey() + priceText;
			int width = font.width(line);
			graphics.text(font, Component.literal(line), x, y, 0xFFFFFFFF);
			clickableLines.add(new ClickableLine(x, y, width, font.lineHeight, entry.getKey()));
			y += 10;
		}
	}

	private boolean handleClick(double mouseX, double mouseY) {
		for (ClickableLine line : clickableLines) {
			if (mouseX >= line.x() && mouseX <= line.x() + line.width() && mouseY >= line.y() && mouseY <= line.y() + line.height()) {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					mc.player.connection.sendCommand("bz " + line.plainItemName());
				}
				return false;
			}
		}
		return true;
	}

	private boolean isVisitorInfo(ItemStack stack) {
		if (stack.isEmpty()) return false;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return false;
		List<Component> lines = lore.lines();
		if (lines.size() != 4) return false;
		return lines.get(3).getString().startsWith(OFFERS_ACCEPTED_PREFIX);
	}

	private LinkedHashMap<String, ShoppingItem> readShoppingList(ItemStack stack) {
		LinkedHashMap<String, ShoppingItem> result = new LinkedHashMap<>();
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return result;

		boolean reading = false;
		for (Component component : lore.lines()) {
			String line = component.getString();
			if (line.equals(REQUIRED_HEADER)) {
				reading = true;
				continue;
			}
			if (!reading) continue;
			if (line.isEmpty()) break;

			String plain = COLOR_CODE.matcher(line).replaceAll("").trim();
			if (plain.isEmpty()) continue;

			long amount = 1;
			Matcher amtMatcher = LEADING_AMOUNT.matcher(plain);
			if (amtMatcher.find() && amtMatcher.start() == 0) {
				String amountStr = amtMatcher.group("amt1") != null ? amtMatcher.group("amt1")
					: amtMatcher.group("amt2") != null ? amtMatcher.group("amt2") : amtMatcher.group("amt3");
				String suffix = amtMatcher.group("suf1") != null ? amtMatcher.group("suf1")
					: amtMatcher.group("suf2") != null ? amtMatcher.group("suf2") : amtMatcher.group("suf3");
				try {
					double parsed = Double.parseDouble(amountStr.replace(",", ""));
					if ("k".equalsIgnoreCase(suffix)) parsed *= 1_000;
					else if ("m".equalsIgnoreCase(suffix)) parsed *= 1_000_000;
					amount = Math.round(parsed);
				} catch (NumberFormatException ignored) {
					amount = 1;
				}
				plain = plain.substring(amtMatcher.end()).trim();
			} else {
				Matcher trailingMatcher = TRAILING_AMOUNT.matcher(plain);
				if (trailingMatcher.find()) {
					String amountStr = trailingMatcher.group("amt");
					String suffix = trailingMatcher.group("suf");
					try {
						double parsed = Double.parseDouble(amountStr.replace(",", ""));
						if ("k".equalsIgnoreCase(suffix)) parsed *= 1_000;
						else if ("m".equalsIgnoreCase(suffix)) parsed *= 1_000_000;
						amount = Math.round(parsed);
					} catch (NumberFormatException ignored) {
						amount = 1;
					}
					plain = plain.substring(0, trailingMatcher.start()).trim();
				}
			}
			if (plain.isEmpty()) continue;

			String plainName = plain;
			String id = SkyblockItemRepo.findIdByName(plainName);
			double totalPrice = 0;
			if (id != null) {
				NeuInternalName internalName = NeuInternalName.of(id);
				BazaarApi.BazaarPrice price = internalName.getBazaarPrice();
				Double unitPrice = price != null ? price.buyPrice() : internalName.getNpcSellPrice();
				if (unitPrice != null) totalPrice = unitPrice * amount;
			}
			result.put(plainName, new ShoppingItem(amount, totalPrice));
		}
		return result;
	}

	@Override
	public String getDescription() {
		return "Shows what every garden visitor you've talked to this session still needs, all in one list.";
	}
}
