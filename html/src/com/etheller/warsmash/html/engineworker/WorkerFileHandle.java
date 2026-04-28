package com.etheller.warsmash.html.engineworker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.etheller.warsmash.datasources.DataSource;

/**
 * libGDX {@link FileHandle} backed by a Warsmash {@link DataSource}.
 * Read-only — write/delete/list operations throw. Path is held verbatim from
 * the caller; the data source layer (MPQ / compound / in-memory) handles
 * separator and case normalisation.
 *
 * <p>Worker-port note: in this context the data source is typically an
 * {@code MpqDataSource} chain over OPFS-resident MPQs. Each
 * {@link #readBytes()} ends up running a synchronous SAH read inside the
 * worker, which is fine — the engine's API contract is sync.
 */
final class WorkerFileHandle extends FileHandle {
	private final DataSource dataSource;
	private final String filePath;

	WorkerFileHandle(final DataSource dataSource, final String filePath) {
		super(filePath);
		this.dataSource = dataSource;
		this.filePath = filePath;
	}

	@Override public FileType type() { return FileType.Internal; }
	@Override public String path() { return this.filePath; }
	@Override public java.io.File file() { return null; }
	@Override public boolean isDirectory() { return false; }

	@Override
	public InputStream read() {
		try {
			final InputStream in = this.dataSource.getResourceAsStream(this.filePath);
			if (in == null) {
				throw new GdxRuntimeException("File not found: " + this.filePath);
			}
			return in;
		}
		catch (final IOException e) {
			throw new GdxRuntimeException("Read failed: " + this.filePath, e);
		}
	}

	@Override
	public byte[] readBytes() {
		try {
			final ByteBuffer bb = this.dataSource.read(this.filePath);
			if (bb == null) {
				throw new GdxRuntimeException("File not found: " + this.filePath);
			}
			// MpqDataSource hands back ByteBuffers with position == limit (just
			// filled). Reading directly via bb.remaining() / bb.get() yields
			// zero bytes. Duplicate + reset position so we don't disturb the
			// caller-shared buffer's cursor.
			final ByteBuffer dup = bb.duplicate();
			dup.position(0);
			final byte[] out = new byte[dup.remaining()];
			dup.get(out);
			return out;
		}
		catch (final IOException e) {
			throw new GdxRuntimeException("Read failed: " + this.filePath, e);
		}
	}

	@Override
	public long length() {
		try {
			final ByteBuffer bb = this.dataSource.read(this.filePath);
			// File size is the buffer's limit (filled-to-here). bb.remaining()
			// would be zero, see readBytes() comment.
			return (bb == null) ? 0 : bb.limit();
		}
		catch (final IOException e) {
			return 0;
		}
	}

	@Override
	public boolean exists() {
		return this.dataSource.has(this.filePath);
	}

	@Override
	public FileHandle child(final String name) {
		final String childPath = this.filePath.isEmpty() ? name : (this.filePath + "/" + name);
		return new WorkerFileHandle(this.dataSource, childPath);
	}

	@Override
	public FileHandle sibling(final String name) {
		final int slash = Math.max(this.filePath.lastIndexOf('/'), this.filePath.lastIndexOf('\\'));
		final String parent = (slash == -1) ? "" : this.filePath.substring(0, slash);
		return new WorkerFileHandle(this.dataSource, parent.isEmpty() ? name : (parent + "/" + name));
	}

	@Override
	public FileHandle parent() {
		final int slash = Math.max(this.filePath.lastIndexOf('/'), this.filePath.lastIndexOf('\\'));
		final String parent = (slash == -1) ? "" : this.filePath.substring(0, slash);
		return new WorkerFileHandle(this.dataSource, parent);
	}
}
