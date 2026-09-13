package com.cokelord.skyblocksimplified.config;

import com.google.gson.JsonElement;

import java.util.Map;

/** Persisted state for a single feature — both the on-disk config file and every clipboard export/import
 *  string are just a {@code Map<String, FeatureState>} keyed by feature id. Field names here are a wire
 *  format, not just Java fields: renaming or removing one breaks every export ever produced with the old
 *  name (Gson simply won't find the value under its new name, and the field silently resets to default).
 *  Only ever add new fields; see ConfigManager's own class doc comment for the full compatibility contract
 *  this exists to uphold. */
public class FeatureState {
	public boolean enabled;
	public Map<String, Float> floats;
	public Integer color;
	public JsonElement extra;
}
