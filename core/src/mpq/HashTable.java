package mpq;

import mpq.data.HashTableEntry;

public class HashTable {
	public static final int BLOCK_EMPTY_ALWAYS = 0xFFFFFFFF;
	public static final int BLOCK_EMPTY_NOW = 0xFFFFFFFE;
	private Entry[] bucketArray;
	
	// raw constructor, assumes every entry is not null and the array is a power of 2
	public HashTable(Entry[] entries){
		bucketArray = entries;
	}
	
	public int lookupBlock(HashLookup what) throws MPQException{
		final int idx = tryLookupBlock(what);
		if (idx < 0) {
			throw new MPQException("lookup not found");
		}
		return idx;
	}

	/**
	 * Non-throwing variant of {@link #lookupBlock}. Returns -1 when the file
	 * isn't in the archive — the most common case during compound-data-source
	 * lookups, where it'd previously throw + catch + return null up the chain.
	 * On TeaVM the throw alone is ~0.4 ms because every Throwable construction
	 * fills a JS stack trace. On a real compound source this dominated trace
	 * profiles (3+% of total CPU, multiple seconds per profile).
	 */
	public int tryLookupBlock(HashLookup what){
		final int mask = bucketArray.length - 1;
		final int index = what.index & mask;
		for (int pos = index; ;) {
			final Entry temp = bucketArray[pos];
			if (temp.blockIndex == BLOCK_EMPTY_ALWAYS) return -1;
			if (temp.getHash() == what.hash) return temp.blockIndex;
			pos = (pos + 1) & mask;
			if (pos == index) return -1;
		}
	}
	
	/*public static int lookupBlock(Entry[] hashtable, byte[] file) throws FileNotFoundException{
		int mask = hashtable.length-1;
		int index = Cryption.HashString(file, Cryption.MPQ_HASH_TABLE_OFFSET) & mask;
		long hash = Cryption.HashString(file, Cryption.MPQ_HASH_NAME_A) & 0xFFFFFFFFL | (long)Cryption.HashString(file, Cryption.MPQ_HASH_NAME_B)<<32;
		for(int pos = index ; ; ){
			Entry temp = hashtable[pos];
			if(temp.getBlockIndex() == BLOCK_EMPTY_ALWAYS) break;
			if(temp.getHash() == hash) return temp.getBlockIndex();
			pos = ( pos + 1 ) & mask;
			if(pos == index) break;
		}
		throw new FileNotFoundException("hash not in hashtable");
	}*/
	
	/*public static int lookupBlock(Entry[] hashtable, String file) throws FileNotFoundException{
		return  lookupBlock(hashtable, Cryption.stringToHashable(file));
	}*/
		
	// entry is an internal data type and as such performs no safety checks
	public static class Entry{
		public long hash;
		public short locale;
		public short platform;
		public int blockIndex;
		
		// raw constructor
		public Entry(){
		}
		
		public Entry(HashTableEntry source){
			hash = source.getHash();
			locale = source.getLocale();
			platform = source.getPlatform();
			blockIndex = source.getBlockIndex();
		}

		public long getHash() {
			return hash;
		}

		public short getLocale() {
			return locale;
		}

		public short getPlatform() {
			return platform;
		}

		public int getBlockIndex() {
			return blockIndex;
		}

		
	}
}
