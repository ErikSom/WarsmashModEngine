package com.hiveworkshop.blizzard.casc.storage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hiveworkshop.blizzard.casc.nio.MalformedCASCStructureException;

/**
 * Default {@link DataFileSource} backed by {@code java.nio.file} / FileChannel.
 * Used by the desktop build; not usable under TeaVM (its classlib ships
 * {@link java.nio.channels.SeekableByteChannel} but not {@code FileChannel}).
 */
public final class NioDataFileSource implements DataFileSource {
	private final Path folder;
	private final boolean useMemoryMapping;
	private final Map<Integer, FileChannel> channels = new HashMap<>();
	private boolean closed = false;

	/** @param dataFolder the {@code Data/data} folder inside the CASC install. */
	public NioDataFileSource(final Path dataFolder, final boolean useMemoryMapping) {
		this.folder = dataFolder;
		this.useMemoryMapping = useMemoryMapping;
	}

	@Override
	public List<IndexFileDescriptor> listIndexFiles() throws IOException {
		final List<IndexFileDescriptor> out = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.folder,
				"*." + Storage.INDEX_FILE_EXTENSION)) {
			for (final Path entry : stream) {
				final String name = entry.getFileName().toString();
				if (name.length() < 10) {
					continue;
				}
				final int bucket = Integer.parseUnsignedInt(name.substring(0, 2), 16);
				final long version = Long.parseUnsignedLong(name.substring(2, 10), 16);
				out.add(new IndexFileDescriptor(bucket, version, entry));
			}
		}
		return out;
	}

	@Override
	public ByteBuffer readIndexFile(final IndexFileDescriptor descriptor) throws IOException {
		return loadFileFully((Path) descriptor.handle);
	}

	@Override
	public ByteBuffer readFromDataFile(final int dataFileIndex, final long offset, final long length)
			throws IOException {
		if (length > Integer.MAX_VALUE) {
			throw new MalformedCASCStructureException("data buffer too large to process");
		}
		final FileChannel channel = getDataFileChannel(dataFileIndex);
		final ByteBuffer out;
		if (this.useMemoryMapping) {
			final MappedByteBuffer mapped = channel.map(MapMode.READ_ONLY, offset, length);
			mapped.load();
			out = mapped;
		}
		else {
			out = ByteBuffer.allocate((int) length);
			while (out.hasRemaining() && (channel.read(out, offset + out.position()) != -1)) {
				;
			}
			if (out.hasRemaining()) {
				throw new EOFException("unexpected end of file");
			}
			out.clear();
		}
		return out;
	}

	@Override
	public synchronized void close() throws IOException {
		if (this.closed) {
			return;
		}
		IOException first = null;
		for (final FileChannel ch : this.channels.values()) {
			try {
				ch.close();
			}
			catch (final IOException e) {
				if (first == null) {
					first = e;
				}
				else {
					first.addSuppressed(e);
				}
			}
		}
		this.closed = true;
		if (first != null) {
			throw new IOException("IOExceptions occurred during closure", first);
		}
	}

	private synchronized FileChannel getDataFileChannel(final int index) throws IOException {
		if (this.closed) {
			throw new ClosedChannelException();
		}
		FileChannel channel = this.channels.get(index);
		if (channel == null) {
			if (index > Storage.DATA_FILE_INDEX_MAXIMUM) {
				throw new MalformedCASCStructureException("storage data file index too large");
			}
			final StringBuilder builder = new StringBuilder(Storage.DATA_FILE_NAME).append('.');
			final String extension = Integer.toUnsignedString(index);
			for (int i = 0; i < Storage.DATA_FILE_EXTENSION_LENGTH - extension.length(); i++) {
				builder.append('0');
			}
			builder.append(extension);
			channel = FileChannel.open(this.folder.resolve(builder.toString()), StandardOpenOption.READ);
			this.channels.put(index, channel);
		}
		return channel;
	}

	private ByteBuffer loadFileFully(final Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			final long length = channel.size();
			if (length > Integer.MAX_VALUE) {
				throw new MalformedCASCStructureException("file too large to process");
			}
			if (this.useMemoryMapping) {
				final MappedByteBuffer mapped = channel.map(MapMode.READ_ONLY, 0, length);
				mapped.load();
				return mapped;
			}
			final ByteBuffer buf = ByteBuffer.allocate((int) length);
			while (buf.hasRemaining() && (channel.read(buf, buf.position()) != -1)) {
				;
			}
			if (buf.hasRemaining()) {
				throw new EOFException("unexpected end of file");
			}
			buf.clear();
			return buf;
		}
	}
}
