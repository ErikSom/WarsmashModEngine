package com.etheller.warsmash.html.engineworker;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.badlogic.gdx.Preferences;

/**
 * In-memory {@link Preferences} for the worker port. Persistence is a
 * follow-up — for now {@code flush()} is a no-op. Engine code reads/writes
 * a tiny set of keys (profile names, last-played map, audio volumes) via
 * this interface; losing them between page loads is acceptable while we
 * stand up the real engine boot.
 *
 * <p>Cached per-name on the {@link WorkerApplication} so repeated
 * {@code Gdx.app.getPreferences("X")} calls return the same instance
 * (matching libGDX desktop behaviour).
 */
final class WorkerPreferences implements Preferences {
	private final Map<String, Object> values = new LinkedHashMap<>();

	@Override public Preferences putBoolean(final String key, final boolean val) { this.values.put(key, val); return this; }
	@Override public Preferences putInteger(final String key, final int val) { this.values.put(key, val); return this; }
	@Override public Preferences putLong(final String key, final long val) { this.values.put(key, val); return this; }
	@Override public Preferences putFloat(final String key, final float val) { this.values.put(key, val); return this; }
	@Override public Preferences putString(final String key, final String val) { this.values.put(key, val); return this; }

	@Override
	public Preferences put(final Map<String, ?> vals) {
		this.values.putAll(vals);
		return this;
	}

	@Override public boolean getBoolean(final String key) { return getBoolean(key, false); }
	@Override public int getInteger(final String key) { return getInteger(key, 0); }
	@Override public long getLong(final String key) { return getLong(key, 0L); }
	@Override public float getFloat(final String key) { return getFloat(key, 0f); }
	@Override public String getString(final String key) { return getString(key, ""); }

	@Override
	public boolean getBoolean(final String key, final boolean defValue) {
		final Object v = this.values.get(key);
		return (v instanceof Boolean) ? (Boolean) v : defValue;
	}

	@Override
	public int getInteger(final String key, final int defValue) {
		final Object v = this.values.get(key);
		return (v instanceof Integer) ? (Integer) v : defValue;
	}

	@Override
	public long getLong(final String key, final long defValue) {
		final Object v = this.values.get(key);
		if (v instanceof Long) return (Long) v;
		if (v instanceof Integer) return (Integer) v;
		return defValue;
	}

	@Override
	public float getFloat(final String key, final float defValue) {
		final Object v = this.values.get(key);
		if (v instanceof Float) return (Float) v;
		if (v instanceof Integer) return ((Integer) v).floatValue();
		return defValue;
	}

	@Override
	public String getString(final String key, final String defValue) {
		final Object v = this.values.get(key);
		return (v instanceof String) ? (String) v : defValue;
	}

	@Override
	public Map<String, ?> get() {
		return Collections.unmodifiableMap(new HashMap<>(this.values));
	}

	@Override public boolean contains(final String key) { return this.values.containsKey(key); }
	@Override public void clear() { this.values.clear(); }
	@Override public void remove(final String key) { this.values.remove(key); }
	@Override public void flush() { /* in-memory only — no-op */ }
}
