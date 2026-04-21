package com.hiveworkshop.blizzard.casc.io;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.hiveworkshop.blizzard.casc.storage.DataFileSource;

/**
 * Abstraction over a Warcraft III CASC install's I/O surface. Lets
 * {@link WarcraftIIICASC} work without direct {@code java.nio.file}
 * access so the CASC parser can run in environments without
 * {@code FileChannel} (e.g. a browser Web Worker backed by OPFS).
 *
 * <p>The caller of {@link #getDataFileSource()} receives the data source
 * by reference — {@link WarcraftIIICASC} hands it to its {@code Storage},
 * which owns and closes it. Implementations must not close the same
 * source on their own {@link #close()}.
 */
public interface CascInstallReader extends AutoCloseable {
	/** Read {@code .build.info} at the install root. */
	ByteBuffer readBuildInfo() throws IOException;

	/**
	 * Read a CASC configuration file by its hex key. The file lives under
	 * {@code Data/config/XX/YY/<keyHex>} where XX/YY are the first two
	 * pairs of hex digits of the key.
	 */
	ByteBuffer readConfigFile(String keyHex) throws IOException;

	/**
	 * Returns the {@link DataFileSource} backing {@code Data/data/*.idx}
	 * and {@code Data/data/data.NNN}. May be created lazily; the caller
	 * (typically {@code Storage}) takes ownership.
	 */
	DataFileSource getDataFileSource() throws IOException;

	@Override
	void close() throws IOException;
}
