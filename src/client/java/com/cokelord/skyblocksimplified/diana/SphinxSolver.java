package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.SphinxSolverFeature;
import com.cokelord.skyblocksimplified.util.ChatText;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sphinx riddle solver, ported from SBO's SphinxSolver/SphinxQuestions: the three answer lines ("   A) ...")
 * are held back until all three arrived, then re-printed with the known correct answer green+underlined and
 * the rest red — every line clickable to submit the correct answer via /sphinxanswer.
 */
public final class SphinxSolver {
	private static final Pattern ANSWER = Pattern.compile("^§7 {3}([ABC])\\) §f(.*?)$");
	/** SBO SphinxQuestions.CORRECT_ANSWERS. */
	private static final Set<String> CORRECT_ANSWERS = Set.of("Slime", "Shark", "Mushroom Desert", "Roddy", "Ruby", "Divine",
		"Dark Auction", "Hoppity", "7", "Zombie", "Vacuum", "Marigold", "Prismite", "Junk", "Backwater Bayou");

	private static final Map<String, Component> answers = new LinkedHashMap<>();
	private static int correctIndex = -1;

	private SphinxSolver() {}

	/** @return false to hide the original line. */
	static boolean onChat(Component message, String legacy) {
		if (!SphinxSolverFeature.on()) return true;
		Matcher m = ANSWER.matcher(legacy);
		if (!m.matches()) return true;
		String letter = m.group(1);
		String answer = ChatText.strip(m.group(2)).trim();
		boolean correct = CORRECT_ANSWERS.contains(answer);
		if (correct) correctIndex = "ABC".indexOf(letter);
		HoverEvent hover = message.getStyle().getHoverEvent();
		answers.put(letter, Component.literal(legacy.replace("§f", correct ? "§a§n" : "§c")).withStyle(Style.EMPTY.withHoverEvent(hover)));
		if (answers.size() == 3) {
			for (Component line : answers.values()) {
				ChatText.clientMessage(correctIndex < 0 ? line
					: line.copy().withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/sphinxanswer " + correctIndex))));
			}
			answers.clear();
			correctIndex = -1;
		}
		return false;
	}
}
