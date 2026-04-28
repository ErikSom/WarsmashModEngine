package com.etheller.warsmash.html.engineworker;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import com.etheller.warsmash.datasources.DataSource;

/**
 * libGDX {@link Files} that routes every file lookup — internal, classpath,
 * external, absolute, local — through a single Warsmash {@link DataSource}.
 * Worker context has no real classpath / external storage / absolute paths,
 * so collapsing all five {@code FileType}s onto the same backing store is
 * the honest answer: there's only one place files come from here.
 */
final class WorkerFiles implements Files {
	private final DataSource dataSource;

	WorkerFiles(final DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override public FileHandle getFileHandle(final String path, final FileType type) { return handle(path); }
	@Override public FileHandle classpath(final String path) { return handle(path); }
	@Override public FileHandle internal(final String path) { return handle(path); }
	@Override public FileHandle external(final String path) { return handle(path); }
	@Override public FileHandle absolute(final String path) { return handle(path); }
	@Override public FileHandle local(final String path) { return handle(path); }

	private FileHandle handle(final String path) {
		return new WorkerFileHandle(this.dataSource, path);
	}

	@Override public String getExternalStoragePath() { return ""; }
	@Override public boolean isExternalStorageAvailable() { return false; }
	@Override public String getLocalStoragePath() { return ""; }
	@Override public boolean isLocalStorageAvailable() { return false; }
}
