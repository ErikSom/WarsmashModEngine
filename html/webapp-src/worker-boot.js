// Worker entry. Sets up async JS helpers (OPFS walkers / readers) on
// `self` so Java code compiled by TeaVM can call them. TeaVM's @JSBody
// parser is ES5, so async/await JS has to live here in plain JS files,
// not inline in Java.

self.postMessage('worker: boot script loaded');

self._w3Root = null;
// path -> { dir: FileSystemDirectoryHandle, name: string, size: number }
self._w3Files = new Map();
// path -> FileSystemSyncAccessHandle (pre-opened for sync reads)
self._w3MpqHandles = new Map();
// Lazily created extraction root (OPFS /extracted).
self._w3ExtractedRoot = null;

async function walkDir(dir, prefix) {
	for await (const [name, handle] of dir.entries()) {
		const p = prefix + name;
		if (handle.kind === 'directory') {
			await walkDir(handle, p + '/');
		}
		else {
			const file = await handle.getFile();
			self._w3Files.set(p, { dir, name, size: file.size });
		}
	}
}

async function initOpfs() {
	const storage = await navigator.storage.getDirectory();
	self._w3Root = await storage.getDirectoryHandle('w3');
	await walkDir(self._w3Root, '');
	self.postMessage('opfs: indexed ' + self._w3Files.size + ' files');
}

// Shared primitive: open a sync access handle, run op(handle, entry.size), close.
async function withSyncHandle(path, op) {
	const entry = self._w3Files.get(path);
	if (!entry) throw new Error('OPFS path not found: ' + path);
	const fh = await entry.dir.getFileHandle(entry.name);
	const h = await fh.createSyncAccessHandle();
	try {
		return op(h, entry.size);
	}
	finally {
		try { h.close(); } catch (e) { /* swallow */ }
	}
}

self.w3ReadFullAsync = function(path) {
	return withSyncHandle(path, (h, size) => {
		const buf = new Int8Array(size);
		h.read(buf, { at: 0 });
		return buf;
	});
};

self.w3ReadRangeAsync = function(path, offset, length) {
	return withSyncHandle(path, (h) => {
		const buf = new Int8Array(length);
		h.read(buf, { at: offset });
		return buf;
	});
};

// Returns a single '\n' separated list of paths that start with prefix.
// (Easier for @JSBody than returning arrays.)
self.w3ListPathsWithPrefix = function(prefix) {
	const out = [];
	for (const p of self._w3Files.keys()) {
		if (p.startsWith(prefix)) out.push(p);
	}
	return out.join('\n');
};

self.w3FileSize = function(path) {
	const e = self._w3Files.get(path);
	return e ? e.size : -1;
};

// Return newline-joined OPFS paths for files whose base name ends in ".mpq"
// (case-insensitive). Output order is deterministic (sorted).
self.w3FindMpqFiles = function() {
	const out = [];
	for (const p of self._w3Files.keys()) {
		const slash = p.lastIndexOf('/');
		const name = (slash === -1 ? p : p.substring(slash + 1)).toLowerCase();
		if (name.endsWith('.mpq')) out.push(p);
	}
	out.sort();
	return out.join('\n');
};

// Pre-open (async) a sync access handle for the given OPFS file path and
// cache it on self._w3MpqHandles. Once open, subsequent reads are sync.
self.w3OpenMpqHandleAsync = async function(path) {
	if (self._w3MpqHandles.has(path)) return true;
	const entry = self._w3Files.get(path);
	if (!entry) throw new Error('OPFS path not found: ' + path);
	const fh = await entry.dir.getFileHandle(entry.name);
	const h = await fh.createSyncAccessHandle();
	self._w3MpqHandles.set(path, h);
	return true;
};

self.w3CloseMpqHandle = function(path) {
	const h = self._w3MpqHandles.get(path);
	if (h) {
		try { h.close(); } catch (e) {}
		self._w3MpqHandles.delete(path);
	}
};

// Synchronous read from a pre-opened MPQ handle. Returns number of bytes
// actually read (may be less than `length` at end-of-file, or 0).
// Writes into pre-allocated `buf` (Int8Array) starting at `bufOffset`.
self.w3ReadMpq = function(path, offset, length, buf, bufOffset) {
	const h = self._w3MpqHandles.get(path);
	if (!h) throw new Error('no open handle for ' + path);
	const view = (bufOffset === 0 && buf.byteLength === length)
		? buf
		: new Int8Array(buf.buffer, buf.byteOffset + bufOffset, length);
	return h.read(view, { at: offset });
};

self.w3MpqSize = function(path) {
	const h = self._w3MpqHandles.get(path);
	if (!h) return -1;
	return h.getSize();
};

// Async write of a file to /extracted/<relPath>. Creates intermediate dirs.
self.w3WriteExtractedAsync = async function(relPath, bytes) {
	if (!self._w3ExtractedRoot) {
		const storage = await navigator.storage.getDirectory();
		self._w3ExtractedRoot = await storage.getDirectoryHandle('extracted', { create: true });
	}
	// MPQs use either forward or backslash separators; normalize.
	const normalized = String(relPath).replace(/\\/g, '/').replace(/^\/+/, '');
	const parts = normalized.split('/').filter(Boolean);
	if (parts.length === 0) throw new Error('empty path');
	let dir = self._w3ExtractedRoot;
	for (let i = 0; i < parts.length - 1; i++) {
		dir = await dir.getDirectoryHandle(parts[i], { create: true });
	}
	const fh = await dir.getFileHandle(parts[parts.length - 1], { create: true });
	const h = await fh.createSyncAccessHandle();
	try {
		h.write(bytes, { at: 0 });
		h.truncate(bytes.byteLength);
	}
	finally {
		try { h.close(); } catch (e) {}
	}
	return true;
};

self.w3ClearExtracted = async function() {
	try {
		const storage = await navigator.storage.getDirectory();
		await storage.removeEntry('extracted', { recursive: true });
	}
	catch (e) { /* may not exist */ }
	self._w3ExtractedRoot = null;
	return true;
};

// Read an extracted file's bytes. Returns Int8Array, or null if not found.
self.w3ReadExtractedAsync = async function(relPath) {
	try {
		const storage = await navigator.storage.getDirectory();
		let dir = await storage.getDirectoryHandle('extracted');
		const parts = String(relPath).split('/').filter(Boolean);
		for (let i = 0; i < parts.length - 1; i++) {
			dir = await dir.getDirectoryHandle(parts[i]);
		}
		const fh = await dir.getFileHandle(parts[parts.length - 1]);
		const h = await fh.createSyncAccessHandle();
		try {
			const buf = new Int8Array(h.getSize());
			h.read(buf, { at: 0 });
			return buf;
		}
		finally {
			try { h.close(); } catch (e) {}
		}
	}
	catch (e) {
		return null;
	}
};

// Load TeaVM bundle, then launch main() after async init completes.
importScripts('worker.js');
initOpfs()
	.then(() => { self.main(); })
	.catch((e) => {
		self.postMessage('worker boot ERROR: ' + (e && e.message ? e.message : String(e)));
	});
