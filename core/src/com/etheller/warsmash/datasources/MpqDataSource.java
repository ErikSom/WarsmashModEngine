package com.etheller.warsmash.datasources;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import mpq.ArchivedFile;
import mpq.ArchivedFileExtractor;
import mpq.ArchivedFileStream;
import mpq.HashLookup;
import mpq.MPQArchive;
import mpq.MPQException;

public class MpqDataSource implements DataSource {

	private final MPQArchive archive;
	private final SeekableByteChannel inputChannel;
	private final ArchivedFileExtractor extractor = new ArchivedFileExtractor();

	public MpqDataSource(final MPQArchive archive, final SeekableByteChannel inputChannel) {
		this.archive = archive;
		this.inputChannel = inputChannel;
	}

	public MPQArchive getArchive() {
		return this.archive;
	}

	public SeekableByteChannel getInputChannel() {
		return this.inputChannel;
	}

	@Override
	public InputStream getResourceAsStream(final String filepath) throws IOException {
		final ByteBuffer buffer = read(filepath);
		if (buffer == null) {
			return null;
		}
		return new ByteArrayInputStream(copyToByteArray(buffer));
	}

	@Override
	public ByteBuffer read(final String path) throws IOException {
		// Non-throwing lookup: the "file not in this MPQ" path is hot — every
		// CompoundDataSource read tries each layer in turn, and the throw +
		// stack-trace fill on TeaVM cost ~0.4 ms per miss. Profiles showed
		// MPQException construction at 3+% of total CPU for in-game frames.
		final ArchivedFile file;
		try {
			file = this.archive.tryLookupHash2(new HashLookup(path));
		}
		catch (final MPQException exc) {
			throw new IOException(exc);
		}
		if (file == null) {
			return null;
		}
		try (final ArchivedFileStream stream = new ArchivedFileStream(this.inputChannel, this.extractor, file)) {
			final long size = stream.size();
			final ByteBuffer buffer = ByteBuffer.allocate((int) size);
			stream.read(buffer);
			return buffer;
		}
	}

	@Override
	public File getFile(final String filepath) throws IOException {
		final ArchivedFile file;
		try {
			file = this.archive.tryLookupHash2(new HashLookup(filepath));
		}
		catch (final MPQException exc) {
			throw new IOException(exc);
		}
		if (file == null) {
			return null;
		}
		String tmpdir = System.getProperty("java.io.tmpdir");
		if (!tmpdir.endsWith(File.separator)) {
			tmpdir += File.separator;
		}
		final String tempDir = tmpdir + "RMSExtract/";
		final File tempProduct = new File(tempDir + filepath.replace('\\', File.separatorChar));
		tempProduct.delete();
		tempProduct.getParentFile().mkdirs();
		try (FileOutputStream outputStream = new FileOutputStream(tempProduct)) {
			outputStream.write(copyToByteArray(read(filepath)));
		}
		tempProduct.deleteOnExit();
		return tempProduct;
	}

	@Override
	public File getDirectory(final String filepath) throws IOException {
		return null;
	}

	@Override
	public boolean has(final String filepath) {
		return this.archive.tryLookupPath(filepath) >= 0;
	}

	@Override
	public Collection<String> getListfile() {
		try {
			final Set<String> listfile = new HashSet<>();
			ArchivedFile listfileContents;
			listfileContents = this.archive.lookupHash2(new HashLookup("(listfile)"));
			// Read the listfile blob into a byte buffer directly, avoiding
			// java.nio.channels.Channels (absent from TeaVM's classlib).
			final byte[] listfileBytes;
			try (final ArchivedFileStream stream = new ArchivedFileStream(this.inputChannel, this.extractor,
					listfileContents)) {
				final long size = stream.size();
				final ByteBuffer buf = ByteBuffer.allocate((int) size);
				stream.read(buf);
				listfileBytes = buf.array();
			}
			catch (final IOException exc) {
				throw new RuntimeException(exc);
			}
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(new ByteArrayInputStream(listfileBytes), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					listfile.add(line);
				}
			}
			catch (final IOException exc) {
				throw new RuntimeException(exc);
			}
			return listfile;
		}
		catch (final MPQException exc) {
			if (exc.getMessage().equals("lookup not found")) {
				return null;
			}
			else {
				throw new RuntimeException(exc);
			}
		}
	}

	@Override
	public void close() throws IOException {
		this.inputChannel.close();
	}

	private static byte[] copyToByteArray(final ByteBuffer buffer) {
		final ByteBuffer duplicate = buffer.duplicate();
		duplicate.position(0);
		final byte[] out = new byte[duplicate.remaining()];
		duplicate.get(out);
		return out;
	}

}
