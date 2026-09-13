package com.cokelord.skyblocksimplified.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HudWidgetRegistry {
	private static final Map<String, MoveableWidget> BY_ID = new LinkedHashMap<>();
	private static final List<MoveableWidget> WIDGETS = new ArrayList<>();

	private HudWidgetRegistry() {}

	public static void register(MoveableWidget widget) {
		if (BY_ID.putIfAbsent(widget.getId(), widget) != null) {
			throw new IllegalStateException("Duplicate hud widget id: " + widget.getId());
		}
		WIDGETS.add(widget);
	}

	public static List<MoveableWidget> all() {
		return Collections.unmodifiableList(WIDGETS);
	}
}
