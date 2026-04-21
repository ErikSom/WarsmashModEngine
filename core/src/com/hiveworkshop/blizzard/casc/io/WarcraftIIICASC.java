package com.hiveworkshop.blizzard.casc.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.hiveworkshop.blizzard.casc.ConfigurationFile;
import com.hiveworkshop.blizzard.casc.info.Info;
import com.hiveworkshop.blizzard.casc.nio.MalformedCASCStructureException;
import com.hiveworkshop.blizzard.casc.storage.Storage;
import com.hiveworkshop.blizzard.casc.vfs.VirtualFileSystem;
import com.hiveworkshop.blizzard.casc.vfs.VirtualFileSystem.PathResult;

/**
 * A convenient access to locally stored Warcraft III data files. Intended for
 * use with CASC versions of Warcraft III including classic and Reforged.
 */
public class WarcraftIIICASC implements AutoCloseable {
	/**
	 * File system view for accessing files from file paths.
	 */
	public class FileSystem {
		/**
		 * Private constructor, currently not used.
		 */
		private FileSystem() {

		}

		/**
		 * Enumerate all file paths contained in this file system.
		 * <p>
		 * This operation might be quite slow.
		 *
		 * @return A list containing all file paths contained in this file system.
		 * @throws IOException In an exception occurs when resolving files.
		 */
		public List<String> enumerateFiles() throws IOException {
			final List<PathResult> pathResults = vfs.getAllFiles();
			final ArrayList<String> filePathStrings = new ArrayList<String>(pathResults.size());

			for (final PathResult pathResult : pathResults) {
				filePathStrings.add(pathResult.getPath());
			}

			return filePathStrings;
		}

		/**
		 * Test if the specified file path is a file.
		 *
		 * @param filePath Path of file to test.
		 * @return True if path represents a file, otherwise false.
		 * @throws IOException In an exception occurs when resolving files.
		 */
		public boolean isFile(final String filePath) throws IOException {
			final byte[][] pathFragments = VirtualFileSystem.convertFilePath(filePath);
			try {
				final PathResult resolveResult = vfs.resolvePath(pathFragments);
				return resolveResult.isFile();
			} catch (final FileNotFoundException e) {
				return false;
			}
		}

		/**
		 * Test if the specified file path is available from local storage.
		 *
		 * @param filePath Path of file to test.
		 * @return True if path represents a file inside local storage, otherwise false.
		 * @throws IOException In an exception occurs when resolving files.
		 */
		public boolean isFileAvailable(final String filePath) throws IOException {
			final byte[][] pathFragments = VirtualFileSystem.convertFilePath(filePath);
			final PathResult resolveResult = vfs.resolvePath(pathFragments);
			return resolveResult.existsInStorage();
		}

		/**
		 * Test if the specified file path is a nested file system.
		 * <p>
		 * If true a file system can be resolved from the file path which files can be
		 * resolved from more efficiently than from higher up file systems.
		 * <p>
		 * Support for this feature is not yet implemented. Please resolve everything
		 * from the root.
		 *
		 * @param filePath Path of file to test.
		 * @return True if file is a nested file system, otherwise false.
		 * @throws IOException In an exception occurs when resolving files.
		 */
		public boolean isNestedFileSystem(final String filePath) throws IOException {
			final byte[][] pathFragments = VirtualFileSystem.convertFilePath(filePath);
			try {
				final PathResult resolveResult = vfs.resolvePath(pathFragments);
				return resolveResult.isTVFS();
			} catch (final FileNotFoundException e) {
				return false;
			}
		}

		/**
		 * Fully read the file at the specified file path into memory.
		 *
		 * @param filePath File path of file to read.
		 * @return Buffer containing file data.
		 * @throws IOException If an error occurs when reading the file.
		 */
		public ByteBuffer readFileData(final String filePath) throws IOException {
			final byte[][] pathFragments = VirtualFileSystem.convertFilePath(filePath);
			final PathResult resolveResult = vfs.resolvePath(pathFragments);

			if (!resolveResult.isFile()) {
				throw new FileNotFoundException("the specified file path does not resolve to a file");
			} else if (!resolveResult.existsInStorage()) {
				throw new FileNotFoundException("the specified file is not in local storage");
			}

			final ByteBuffer fileBuffer = resolveResult.readFile(null);
			fileBuffer.flip();
			return fileBuffer;
		}
	}

	/** Warcraft III build information. */
	private final Info buildInfo;

	/** Detected active build information record. */
	private final int activeInfoRecord;

	/** Warcraft III build configuration. */
	private final ConfigurationFile buildConfiguration;

	/** Warcraft III local storage. */
	private final Storage localStorage;

	/** TVFS file system to resolve file paths. */
	private final VirtualFileSystem vfs;

	/** Kept for the (installFolder,_) API to continue exposing getDataPath()-style behavior. */
	private final CascInstallReader reader;

	/**
	 * Legacy constructor for desktop callers — wraps a
	 * {@link NioCascInstallReader}.
	 */
	public WarcraftIIICASC(final Path installFolder, final boolean useMemoryMapping) throws IOException {
		this(new NioCascInstallReader(installFolder, useMemoryMapping));
	}

	/**
	 * Construct over an arbitrary {@link CascInstallReader}. The reader's
	 * data source is handed to {@link Storage}, which closes it when this
	 * object is closed.
	 */
	public WarcraftIIICASC(final CascInstallReader reader) throws IOException {
		this.reader = reader;

		this.buildInfo = new Info(reader.readBuildInfo());

		final int recordCount = this.buildInfo.getRecordCount();
		if (recordCount < 1) {
			throw new MalformedCASCStructureException("build info contains no records");
		}

		final int activeFieldIndex = this.buildInfo.getFieldIndex("Active");
		if (activeFieldIndex == -1) {
			throw new MalformedCASCStructureException("build info contains no active field");
		}
		int recordIndex = 0;
		for (; recordIndex < recordCount; recordIndex++) {
			if (Integer.parseInt(this.buildInfo.getField(recordIndex, activeFieldIndex)) == 1) {
				break;
			}
		}
		if (recordIndex == recordCount) {
			throw new MalformedCASCStructureException("build info contains no active record");
		}
		this.activeInfoRecord = recordIndex;

		final int buildKeyFieldIndex = this.buildInfo.getFieldIndex("Build Key");
		if (buildKeyFieldIndex == -1) {
			throw new MalformedCASCStructureException("build info contains no build key field");
		}
		final String buildKey = this.buildInfo.getField(this.activeInfoRecord, buildKeyFieldIndex);

		this.buildConfiguration = new ConfigurationFile(reader.readConfigFile(buildKey));

		this.localStorage = new Storage(reader.getDataFileSource(), false);

		VirtualFileSystem constructed = null;
		try {
			constructed = new VirtualFileSystem(this.localStorage, this.buildConfiguration.getConfiguration());
		}
		finally {
			if (constructed == null) {
				this.localStorage.close();
			}
		}
		this.vfs = constructed;
	}

	@Override
	public void close() throws IOException {
		try {
			this.localStorage.close();
		}
		finally {
			this.reader.close();
		}
	}

	/**
	 * Returns the active record index of the build information. This is the index
	 * of the record that is mounted.
	 */
	public int getActiveRecordIndex() {
		return this.activeInfoRecord;
	}

	/**
	 * Returns the active branch name which is currently mounted.
	 */
	public String getBranch() throws IOException {
		final int branchFieldIndex = this.buildInfo.getFieldIndex("Branch");
		if (branchFieldIndex == -1) {
			throw new MalformedCASCStructureException("build info contains no branch field");
		}
		return this.buildInfo.getField(this.activeInfoRecord, branchFieldIndex);
	}

	public Info getBuildInfo() {
		return this.buildInfo;
	}

	public FileSystem getRootFileSystem() {
		return new FileSystem();
	}
}
