package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base for the Events > Diana modules: a small typed settings bag (booleans / ints / strings keyed by id)
 * with automatic JSON persistence and one-liner {@link SettingRow} builders, so each module only declares
 * its defaults and rows.
 */
public abstract class DianaFeature extends Feature {
	private final Map<String, Boolean> bools = new LinkedHashMap<>();
	private final Map<String, Integer> ints = new LinkedHashMap<>();
	private final Map<String, String> strings = new LinkedHashMap<>();
	private final String description;

	protected DianaFeature(String id, String displayName, boolean enabledByDefault, String description) {
		this(id, displayName, FeatureCategory.EVENTS, enabledByDefault, description);
	}

	/** Same settings-bag base for modules outside Events > Diana (e.g. the M7 dragon modules). */
	protected DianaFeature(String id, String displayName, FeatureCategory category, boolean enabledByDefault, String description) {
		super(id, displayName, category, enabledByDefault);
		this.description = description;
	}

	@Override
	public String getSubcategory() { return "Diana"; }

	@Override
	public String getDescription() { return description; }

	protected void defBool(String key, boolean value) { bools.put(key, value); }
	protected void defInt(String key, int value) { ints.put(key, value); }
	protected void defString(String key, String value) { strings.put(key, value); }

	public boolean bool(String key) { return bools.getOrDefault(key, false); }
	public void setBool(String key, boolean value) { bools.put(key, value); }
	public int integer(String key) { return ints.getOrDefault(key, 0); }
	public void setInt(String key, int value) { ints.put(key, value); }
	public String string(String key) { return strings.getOrDefault(key, ""); }
	public void setString(String key, String value) { strings.put(key, value); }

	protected SettingRow.Toggle toggle(String label, String key, String tooltip) {
		return new SettingRow.Toggle(label, () -> bool(key), v -> setBool(key, v), tooltip);
	}

	protected SettingRow.Slider slider(String label, String key, int min, int max, String suffix, String tooltip) {
		return new SettingRow.Slider(label, min, max, () -> integer(key), v -> setInt(key, v), suffix, tooltip);
	}

	protected SettingRow.Color color(String label, String key) {
		int def = ints.getOrDefault(key + "#default", integer(key));
		return new SettingRow.Color(label, getId() + "_" + key, () -> integer(key), v -> setInt(key, v | 0xFF000000), def);
	}

	/** Declares a color setting (ARGB, opaque) and remembers its default for the picker's Reset button. */
	protected void defColor(String key, int argb) {
		ints.put(key, argb | 0xFF000000);
		ints.put(key + "#default", argb | 0xFF000000);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		bools.forEach(obj::addProperty);
		ints.forEach((k, v) -> { if (!k.endsWith("#default")) obj.addProperty(k, v); });
		strings.forEach(obj::addProperty);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (data == null || !data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		try {
			for (String key : bools.keySet()) if (obj.has(key)) bools.put(key, obj.get(key).getAsBoolean());
			for (String key : ints.keySet()) if (obj.has(key) && !key.endsWith("#default")) ints.put(key, obj.get(key).getAsInt());
			for (String key : strings.keySet()) if (obj.has(key)) strings.put(key, obj.get(key).getAsString());
		} catch (RuntimeException ignored) {
			// Malformed value: keep defaults for the rest.
		}
	}
}
