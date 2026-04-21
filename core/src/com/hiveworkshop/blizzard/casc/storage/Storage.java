package com.hiveworkshop.blizzard.casc.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import com.hiveworkshop.blizzard.casc.Key;
import com.hiveworkshop.blizzard.casc.nio.MalformedCASCStructureException;
import com.hiveworkshop.blizzard.casc.storage.DataFileSource.IndexFileDescriptor;

/**
 * Main data storage of a CASC archive. It consists of index files which point
 * to storage containers in data files.
 */
public class Storage implements AutoCloseable {
	/**
	 * The name of the data folder containing the configuration files.
	 */
	public static final String DATA_FOLDER_NAME = "data";

	/**
	 * Number of index files used by a data store.
	 */
	private static final int INDEX_COUNT = 16;

	/**
	 * File extension used by storage index files.
	 */
	public static final String INDEX_FILE_EXTENSION = "idx";

	/**
	 * File name of data files. 3 character extension is the index.
	 */
	public static final String DATA_FILE_NAME = "data";

	/**
	 * Largest permitted data file index.
	 */
	public static final int DATA_FILE_INDEX_MAXIMUM = 999;

	/**
	 * Extension length used by data files. Defined by the length needed to store
	 * DATA_FILE_INDEX_MAXIMUM as a decimal string.
	 */
	public static final int DATA_FILE_EXTENSION_LENGTH = 3;

	/**
	 * Converts an encoding key into an index file number.
	 *
	 * @param encodingKey Input encoding key.
	 * @param keyLength   Length of key to be processed.
	 * @return Index number.
	 */
	public static int getBucketIndex(final byte[] encodingKey, final int keyLength) {
		int accumulator = 0;
		for (int i = 0; i < keyLength; i += 1) {
			accumulator ^= encodingKey[i];
		}
		final int nibbleMask = (1 << 4) - 1;
		return (accumulator & nibbleMask) ^ ((accumulator >> 4) & nibbleMask);
	}

	private final DataFileSource source;

	private final IndexFile[] indicies = new IndexFile[INDEX_COUNT];

	/**
	 * Index file versions loaded. Possibly useful for debugging.
	 */
	private final long[] idxVersions = new long[INDEX_COUNT];

	/** Used to track closed status of the store. */
	private boolean closed = false;

	private int encodingKeyLength;

	/**
	 * Legacy constructor kept for desktop callers — wraps an
	 * {@link NioDataFileSource}.
	 *
	 * @param dataFolder       Path of the CASC data folder.
	 * @param useOld           Use other (old?) version of index files.
	 * @param useMemoryMapping If IO should be memory mapped.
	 */
	public Storage(final Path dataFolder, final boolean useOld, final boolean useMemoryMapping) throws IOException {
		this(new NioDataFileSource(dataFolder.resolve(DATA_FOLDER_NAME), useMemoryMapping), useOld);
	}

	/**
	 * Construct a Storage atop an arbitrary {@link DataFileSource}. Storage
	 * takes ownership of the source — closing Storage closes the source.
	 */
	public Storage(final DataFileSource source, final boolean useOld) throws IOException {
		this.source = source;

		final HashMap<Integer, ArrayList<IndexFileDescriptor>> metaMap = new HashMap<>(INDEX_COUNT);
		for (final IndexFileDescriptor desc : source.listIndexFiles()) {
			ArrayList<IndexFileDescriptor> bucketList = metaMap.get(desc.bucket);
			if (bucketList == null) {
				bucketList = new ArrayList<>();
				metaMap.put(desc.bucket, bucketList);
			}
			bucketList.add(desc);
		}

		Comparator<IndexFileDescriptor> bucketOrder = Comparator.comparingLong(d -> d.version);
		if (!useOld) {
			bucketOrder = Collections.reverseOrder(bucketOrder);
		}

		for (int index = 0; index < this.indicies.length; index++) {
			final ArrayList<IndexFileDescriptor> bucketList = metaMap.get(index);
			if (bucketList == null) {
				throw new MalformedCASCStructureException("storage index file missing");
			}
			Collections.sort(bucketList, bucketOrder);
			final IndexFileDescriptor pick = bucketList.get(0);
			this.idxVersions[index] = pick.version;
			this.indicies[index] = new IndexFile(source.readIndexFile(pick));
		}

		this.encodingKeyLength = this.indicies[0].getEncodingKeyLength();
		for (int i = 1; i < this.indicies.length; i++) {
			if (this.encodingKeyLength != this.indicies[i].getEncodingKeyLength()) {
				throw new MalformedCASCStructureException("inconsistent encoding key length between index files");
			}
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.source.close();
	}

	public boolean hasBanks(final Key encodingKey) {
		final int bucketIndex = getBucketIndex(encodingKey.getKey(), this.encodingKeyLength);
		final IndexFile index = this.indicies[bucketIndex];
		final IndexEntry indexEntry = index.getEntry(encodingKey);
		return indexEntry != null;
	}

	public BankStream getBanks(final Key encodingKey) throws IOException {
		final int bucketIndex = getBucketIndex(encodingKey.getKey(), this.encodingKeyLength);
		final IndexFile index = this.indicies[bucketIndex];
		final IndexEntry indexEntry = index.getEntry(encodingKey);
		if (indexEntry == null) {
			throw new FileNotFoundException("encoding key not in store indicies");
		}
		final long dataOffset = indexEntry.getDataOffset();
		final int storeIndex = index.getStoreIndex(dataOffset);
		final long storeOffset = index.getStoreOffset(dataOffset);
		final ByteBuffer storageBuffer = this.source.readFromDataFile(storeIndex, storeOffset, indexEntry.getFileSize());
		return new BankStream(storageBuffer, indexEntry.getKey());
	}
}
