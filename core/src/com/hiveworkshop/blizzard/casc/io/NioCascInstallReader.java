package com.hiveworkshop.blizzard.casc.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.hiveworkshop.blizzard.casc.ConfigurationFile;
import com.hiveworkshop.blizzard.casc.info.Info;
import com.hiveworkshop.blizzard.casc.nio.MalformedCASCStructureException;
import com.hiveworkshop.blizzard.casc.storage.DataFileSource;
import com.hiveworkshop.blizzard.casc.storage.NioDataFileSource;
import com.hiveworkshop.blizzard.casc.storage.Storage;

/**
 * Default {@link CascInstallReader} backed by {@code java.nio.file}.
 * Used by the desktop build.
 */
public final class NioCascInstallReader implements CascInstallReader {
	private static final String WC3_DATA_FOLDER_NAME = "Data";

	private final Path installFolder;
	private final boolean useMemoryMapping;
	private NioDataFileSource dataSource;

	public NioCascInstallReader(final Path installFolder, final boolean useMemoryMapping) {
		this.installFolder = installFolder;
		this.useMemoryMapping = useMemoryMapping;
	}

	@Override
	public ByteBuffer readBuildInfo() throws IOException {
		return ByteBuffer.wrap(Files.readAllBytes(this.installFolder.resolve(Info.BUILD_INFO_FILE_NAME)));
	}

	@Override
	public ByteBuffer readConfigFile(final String keyHex) throws IOException {
		Path file = this.installFolder.resolve(WC3_DATA_FOLDER_NAME)
				.resolve(ConfigurationFile.CONFIGURATION_FOLDER_NAME);
		for (int tier = 0; tier < ConfigurationFile.BUCKET_TIERS; tier++) {
			final int offset = tier * ConfigurationFile.BUCKET_NAME_LENGTH;
			file = file.resolve(keyHex.substring(offset, offset + ConfigurationFile.BUCKET_NAME_LENGTH));
		}
		file = file.resolve(keyHex);
		return ByteBuffer.wrap(Files.readAllBytes(file));
	}

	@Override
	public DataFileSource getDataFileSource() throws IOException {
		if (this.dataSource == null) {
			final Path dataPath = this.installFolder.resolve(WC3_DATA_FOLDER_NAME);
			if (!Files.isDirectory(dataPath)) {
				throw new MalformedCASCStructureException("data folder is missing");
			}
			this.dataSource = new NioDataFileSource(dataPath.resolve(Storage.DATA_FOLDER_NAME),
					this.useMemoryMapping);
		}
		return this.dataSource;
	}

	@Override
	public void close() {
		// Ownership of the data source is handed to Storage once returned.
		// Nothing else to release here.
	}
}
