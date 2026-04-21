package com.etheller.warsmash.html.worker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * {@link SeekableByteChannel} backed by a pre-opened
 * {@code FileSystemSyncAccessHandle} living on the JS side. TeaVM's classlib
 * ships {@code SeekableByteChannel} but not {@code FileChannel}, and Warsmash's
 * MPQ reader is already written against the {@code SeekableByteChannel}
 * interface, so we can slot this straight under {@code new MPQArchive(channel)}.
 *
 * <p>The handle must have been opened via
 * {@link OpfsBridge#openMpqHandle(String)} before the channel is used.
 */
public final class OpfsSeekableByteChannel implements SeekableByteChannel {
	private final String opfsPath;
	private final long size;
	private final Int8Array scratch;
	private long position;
	private boolean open = true;

	public OpfsSeekableByteChannel(final String opfsPath) {
		this.opfsPath = opfsPath;
		this.size = (long) OpfsBridge.mpqSize(opfsPath);
		if (this.size < 0) {
			throw new IllegalStateException("OPFS MPQ handle not open for " + opfsPath);
		}
		// 64 KiB staging buffer — MPQ reads are mostly small headers + 4 KiB blocks.
		// Grown on demand if a larger single read comes through.
		this.scratch = Int8Array.create(64 * 1024);
	}

	@Override
	public int read(final ByteBuffer dst) throws IOException {
		if (!this.open) {
			throw new ClosedChannelException();
		}
		final long remaining = this.size - this.position;
		if (remaining <= 0) {
			return -1;
		}
		int toRead = (int) Math.min(dst.remaining(), remaining);
		if (toRead <= 0) {
			return 0;
		}

		final Int8Array buf = (toRead <= this.scratch.getLength())
				? this.scratch
				: Int8Array.create(toRead);
		final int n = OpfsBridge.readMpq(this.opfsPath, this.position, toRead, buf, 0);
		for (int i = 0; i < n; i++) {
			dst.put(buf.get(i));
		}
		this.position += n;
		return n;
	}

	@Override
	public int write(final ByteBuffer src) {
		throw new NonWritableChannelException();
	}

	@Override
	public long position() throws IOException {
		if (!this.open) {
			throw new ClosedChannelException();
		}
		return this.position;
	}

	@Override
	public SeekableByteChannel position(final long newPosition) throws IOException {
		if (!this.open) {
			throw new ClosedChannelException();
		}
		if (newPosition < 0) {
			throw new IllegalArgumentException("negative position");
		}
		this.position = newPosition;
		return this;
	}

	@Override
	public long size() throws IOException {
		if (!this.open) {
			throw new ClosedChannelException();
		}
		return this.size;
	}

	@Override
	public SeekableByteChannel truncate(final long size) {
		throw new NonWritableChannelException();
	}

	@Override
	public boolean isOpen() {
		return this.open;
	}

	@Override
	public void close() {
		this.open = false;
	}

	// Keep ArrayBuffer referenced in case TeaVM's Int8Array is redirected via it.
	@SuppressWarnings("unused")
	private static final Class<?> KEEP_AB = ArrayBuffer.class;
}
