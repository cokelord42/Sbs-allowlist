package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.DianaData;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.util.ChatText;

import java.util.List;

/** Loot lines are SBO's DianaLoot LOOT_ITEMS (same keys, names and rarity colors), LS variants split out. */
public class DianaLootTrackerFeature extends DianaCountsHudFeature {
	public DianaLootTrackerFeature() {
		super("diana_tracker", "Diana Tracker",
			"Tracks Diana loot: rare drops (including lootshare and inventory-only drops), feathers, coins and burrows.",
			"Diana Tracker", List.of(
				new Line("MYTHOLOGICAL_DYE", "Mythological Dye", "§c"),
				new Line("MYTH_THE_FISH", "Myth the Fish", "§c"),
				new Line("SHIMMERING_WOOL", "Shimmering Wool", "§c"),
				new Line("SHIMMERING_WOOL_LS", "Shimmering Wool (LS)", "§c"),
				new Line("MANTI_CORE", "Manti-core", "§c"),
				new Line("MANTI_CORE_LS", "Manti-core (LS)", "§c"),
				new Line("KING_MINOS_SHARD", "King Minos Shard", "§c"),
				new Line("FATEFUL_STINGER", "Fateful Stinger", "§d"),
				new Line("FATEFUL_STINGER_LS", "Fateful Stinger (LS)", "§d"),
				new Line("CHIMERA", "Chimera", "§d"),
				new Line("CHIMERA_LS", "Chimera (LS)", "§d"),
				new Line("BRAIN_FOOD", "Brain Food", "§5"),
				new Line("BRAIN_FOOD_LS", "Brain Food (LS)", "§5"),
				new Line("MINOS_RELIC", "Minos Relic", "§5"),
				new Line("SPHINX_SHARD", "Sphinx Shard", "§5"),
				new Line("BRAIDED_GRIFFIN_FEATHER", "Braided Griffin Feather", "§5"),
				new Line("DAEDALUS_STICK", "Daedalus Stick", "§6"),
				new Line("MINOTAUR_SHARD", "Minotaur Shard", "§6"),
				new Line("CROWN_OF_GREED", "Crown of Greed", "§6"),
				new Line("WASHED_UP_SOUVENIR", "Washed-up Souvenir", "§6"),
				new Line("GRIFFIN_FEATHER", "Griffin Feather", "§6"),
				new Line("MYTHOS_FRAGMENT", "Mythos Fragment", "§6"),
				new Line("CRETAN_URN", "Cretan Urn", "§2"),
				new Line("DWARF_TURTLE_SHELMET", "Dwarf Turtle Shelmet", "§2"),
				new Line("CROCHET_TIGER_PLUSHIE", "Crochet Tiger Plushie", "§2"),
				new Line("ANTIQUE_REMEDIES", "Antique Remedies", "§2"),
				new Line("CRETAN_BULL_SHARD", "Cretan Bull Shard", "§2"),
				new Line("HARPY_SHARD", "Harpy Shard", "§2"),
				new Line("HILT_OF_REVELATIONS", "Hilt of Revelations", "§9"),
				new Line("ANCIENT_CLAW", "Ancient Claw", "§9"),
				new Line("ENCHANTED_ANCIENT_CLAW", "Enchanted Ancient Claw", "§9"),
				new Line("ENCHANTED_GOLD", "Enchanted Gold", "§9"),
				new Line("COINS", "Coins", "§6", true),
				new Line("TOTAL_BURROWS", "Burrows", "§7")),
			0.01f, 0.25f);
	}

	@Override
	protected List<SettingRow> extraRows() {
		return List.of(new SettingRow.Button("Reset Event Counts", () -> {
			DianaData.resetEvent();
			ChatText.clientMessage("§b[SBS] §aDiana event counts reset.");
		}));
	}
}
