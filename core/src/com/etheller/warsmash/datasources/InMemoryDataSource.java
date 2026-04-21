package com.etheller.warsmash.datasources;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link DataSource} backed entirely by an in-memory map of path → bytes.
 * Intended for web-port use where all addressable asset bytes have been
 * pre-loaded out of OPFS; the engine's synchronous DataSource contract can
 * then be served without blocking on async I/O.
 *
 * <p>Lookups are case-insensitive and slash-agnostic (backslashes are
 * normalized to forward slashes) to match Warsmash's existing callsites
 * which mix both conventions.
 */
public final class InMemoryDataSource implements DataSource {
	private final Map<String, byte[]> filesByLowercasePath;

	public InMemoryDataSource(final Map<String, byte[]> files) {
		final Map<String, byte[]> lower = new HashMap<>(files.size());
		for (final Map.Entry<String, byte[]> e : files.entrySet()) {
			lower.put(normalize(e.getKey()), e.getValue());
		}
		this.filesByLowercasePath = lower;
	}

	private static String normalize(final String path) {
		return path.replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	private byte[] lookup(final String path) {
		if (path == null) {
			return null;
		}
		return this.filesByLowercasePath.get(normalize(path));
	}

	@Override
	public InputStream getResourceAsStream(final String filepath) {
		final byte[] bytes = lookup(filepath);
		return (bytes == null) ? null : new ByteArrayInputStream(bytes);
	}

	@Override
	public File getFile(final String filepath) {
		// No on-disk materialization; callers that need a java.io.File are out of luck.
		return null;
	}

	@Override
	public File getDirectory(final String filepath) {
		return null;
	}

	@Override
	public ByteBuffer read(final String path) {
		final byte[] bytes = lookup(path);
		return (bytes == null) ? null : ByteBuffer.wrap(bytes);
	}

	@Override
	public boolean has(final String filepath) {
		return lookup(filepath) != null;
	}

	@Override
	public Collection<String> getListfile() {
		return Collections.unmodifiableSet(this.filesByLowercasePath.keySet());
	}

	@Override
	public void close() {
		this.filesByLowercasePath.clear();
	}
}
