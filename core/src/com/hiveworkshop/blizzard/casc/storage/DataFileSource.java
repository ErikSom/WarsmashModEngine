package com.hiveworkshop.blizzard.casc.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Pluggable byte source behind {@link Storage}. Decouples the CASC
 * index/data-file I/O from {@code java.nio.file} so alternative backends
 * (e.g. browser OPFS in a Web Worker) can be plugged in without dragging
 * {@code FileChannel} into environments that don't support it.
 *
 * <p>The default desktop implementation is {@link NioDataFileSource}.
 */
public interface DataFileSource extends AutoCloseable {
	/** Enumerate all {@code .idx} index files discoverable in the data folder. */
	List<IndexFileDescriptor> listIndexFiles() throws IOException;

	/** Read an index file fully into memory. */
	ByteBuffer readIndexFile(IndexFileDescriptor descriptor) throws IOException;

	/**
	 * Read a slice of a CASC data file. {@code dataFileIndex} is the numeric
	 * suffix of {@code data.NNN}. Implementations may memory-map on platforms
	 * that support it.
	 */
	ByteBuffer readFromDataFile(int dataFileIndex, long offset, long length) throws IOException;

	@Override
	void close() throws IOException;

	/**
	 * Metadata for a discovered index file. {@code handle} is opaque — each
	 * source implementation chooses how to identify the file and it's passed
	 * back to that same source in {@link #readIndexFile(IndexFileDescriptor)}.
	 */
	final class IndexFileDescriptor {
		public final int bucket;
		public final long version;
		public final Object handle;

		public IndexFileDescriptor(final int bucket, final long version, final Object handle) {
			this.bucket = bucket;
			this.version = version;
			this.handle = handle;
		}
	}
}
