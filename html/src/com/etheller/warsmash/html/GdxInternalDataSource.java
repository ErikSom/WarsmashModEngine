package com.etheller.warsmash.html;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.etheller.warsmash.datasources.DataSource;

/**
 * Read-only DataSource backed by libGDX internal assets bundled with the web app.
 *
 * <p>This is the missing half of the web preload path: extracted OPFS assets cover
 * Warcraft data, while Warsmash-specific resources such as
 * {@code UI/FrameDef/SmashFrameDef.toc} live in the packaged app itself.
 */
final class GdxInternalDataSource implements DataSource {
	private static String normalize(final String path) {
		return path.replace('\\', '/');
	}

	private static FileHandle internal(final String path) {
		return Gdx.files.internal(normalize(path));
	}

	@Override
	public InputStream getResourceAsStream(final String filepath) {
		final FileHandle fileHandle = internal(filepath);
		return fileHandle.exists() ? fileHandle.read() : null;
	}

	@Override
	public File getFile(final String filepath) {
		return null;
	}

	@Override
	public File getDirectory(final String filepath) {
		return null;
	}

	@Override
	public ByteBuffer read(final String path) {
		final FileHandle fileHandle = internal(path);
		return fileHandle.exists() ? ByteBuffer.wrap(fileHandle.readBytes()) : null;
	}

	@Override
	public boolean has(final String filepath) {
		return internal(filepath).exists();
	}

	@Override
	public Collection<String> getListfile() {
		return Collections.emptyList();
	}

	@Override
	public void close() {
	}
}
