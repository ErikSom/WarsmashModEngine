// Engine-worker boot script. Two responsibilities before invoking the TeaVM
// bundle's main():
//
// 1. Load Emscripten-compiled gdx.wasm.js. libGDX-TeaVM's `Gdx2DPixmap`
//    routes Pixmap operations through `Gdx.Gdx.prototype.g2d_*`, which only
//    exist after the wasm module has resolved into an instance. The script's
//    top-level `asyncCall()` does `window.Gdx = await Gdx()` — `window` is
//    undefined in workers, so that assignment throws and the global `Gdx`
//    is left as the factory function. We instantiate explicitly here, await
//    it, and assign the result to `self.Gdx` ourselves.
//
// 2. importScripts engine-worker.js and call its exported main(). TeaVM
//    bundles export but don't auto-invoke main (same pattern as
//    worker-boot.js for the extraction worker).

// The script's own auto-init throws a "window is not defined" rejection at
// import time. Suppress just that specific case so it doesn't pollute the
// console — our own Gdx() call is what actually populates self.Gdx.
self.addEventListener('unhandledrejection', (e) => {
	const msg = e.reason && (e.reason.message || String(e.reason));
	if (msg && /window is not defined/.test(msg)) {
		e.preventDefault();
	}
});

// Early message buffer. Main thread posts the OffscreenCanvas immediately
// after constructing the worker, but our Java handler doesn't register until
// engine-worker.js loads + main() runs — which is gated on async wasm
// instantiation. Without buffering, the init message fires into a worker
// with no listener and is silently dropped.
//
// Java code calls __installMessageHandler(fn) once it's ready; the buffer
// is replayed at that moment, after which messages flow straight to fn.
(function installMessageBuffer() {
	const buffered = [];
	let installed = null;
	self.addEventListener('message', (e) => {
		if (installed) {
			installed(e.data);
		}
		else {
			buffered.push(e.data);
		}
	});
	self.__installMessageHandler = function(fn) {
		installed = fn;
		while (buffered.length) {
			try { installed(buffered.shift()); }
			catch (err) { self.postMessage('engine-worker-boot: handler threw on buffered message: ' + err); }
		}
	};
})();

// OPFS helpers — mirror of worker-boot.js's helpers. Java OpfsBridge expects
// these symbols on `self`; the engine-worker uses the same Java class, so we
// reproduce them verbatim. Diverging the helpers (e.g. moving them into a
// shared file via importScripts) is a refactor-later thing.
self._w3Root = null;
self._w3Files = new Map();
self._w3MpqHandles = new Map();
self._w3ExtractedRoot = null;

async function w3WalkDir(dir, prefix) {
	for await (const [name, handle] of dir.entries()) {
		const p = prefix + name;
		if (handle.kind === 'directory') {
			await w3WalkDir(handle, p + '/');
		}
		else {
			const file = await handle.getFile();
			self._w3Files.set(p, { dir, name, size: file.size });
		}
	}
}

async function w3InitOpfs() {
	const storage = await navigator.storage.getDirectory();
	try {
		self._w3Root = await storage.getDirectoryHandle('w3');
	}
	catch (e) {
		self._w3Root = null;
		self.postMessage('engine-worker-boot: no /w3 (re-upload via main page to enable MPQ reads)');
		return;
	}
	await w3WalkDir(self._w3Root, '');
	self.postMessage('engine-worker-boot: indexed ' + self._w3Files.size + ' files in /w3');
}

async function w3WithSyncHandle(path, op) {
	const entry = self._w3Files.get(path);
	if (!entry) throw new Error('OPFS path not found: ' + path);
	const fh = await entry.dir.getFileHandle(entry.name);
	const h = await fh.createSyncAccessHandle();
	try { return op(h, entry.size); }
	finally { try { h.close(); } catch (e) {} }
}

self.w3ReadFullAsync = function(path) {
	return w3WithSyncHandle(path, (h, size) => {
		const buf = new Int8Array(size);
		h.read(buf, { at: 0 });
		return buf;
	});
};
self.w3ReadRangeAsync = function(path, offset, length) {
	return w3WithSyncHandle(path, (h) => {
		const buf = new Int8Array(length);
		h.read(buf, { at: offset });
		return buf;
	});
};
self.w3ListPathsWithPrefix = function(prefix) {
	const out = [];
	for (const p of self._w3Files.keys()) if (p.startsWith(prefix)) out.push(p);
	return out.join('\n');
};
self.w3FileSize = function(path) {
	const e = self._w3Files.get(path);
	return e ? e.size : -1;
};
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
	if (h) { try { h.close(); } catch (e) {} self._w3MpqHandles.delete(path); }
};
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
// Engine worker doesn't write to /extracted or drop /w3 — those live in the
// extraction worker. Stub the writers so OpfsBridge static refs link cleanly
// if anything pulls them in transitively.
self.w3WriteExtractedAsync = async function() { throw new Error('engine worker does not write OPFS'); };
self.w3ClearExtracted = async function() { throw new Error('engine worker does not clear OPFS'); };
self.w3DropUploadAsync = async function() { throw new Error('engine worker does not drop OPFS'); };
self.w3ReadExtractedAsync = async function(relPath) {
	try {
		const storage = await navigator.storage.getDirectory();
		let dir = await storage.getDirectoryHandle('extracted');
		const parts = String(relPath).split('/').filter(Boolean);
		for (let i = 0; i < parts.length - 1; i++) dir = await dir.getDirectoryHandle(parts[i]);
		const fh = await dir.getFileHandle(parts[parts.length - 1]);
		const h = await fh.createSyncAccessHandle();
		try {
			const buf = new Int8Array(h.getSize());
			h.read(buf, { at: 0 });
			return buf;
		}
		finally { try { h.close(); } catch (e) {} }
	}
	catch (e) { return null; }
};

// Engine-internal assets (warsmash.ini, abilityBehaviors/*.json, etc.) live
// in core/assets/ and get bundled into app.js by gdx-teavm's asset loader on
// the main-thread build. The engine worker has none of that infrastructure;
// we fetch the same assets by parsing the preload.txt manifest gdx-teavm
// already produces, then expose them to Java via __engineAssets.
self.__engineAssets = new Map();
self.__engineAssetPaths = [];
async function w3FetchEngineAssets() {
	const r = await fetch('assets/preload.txt', { cache: 'no-store' });
	if (!r.ok) {
		self.postMessage('engine-worker-boot: WARN preload.txt not reachable (' + r.status + ')');
		return;
	}
	const lines = (await r.text()).split('\n');
	const relPaths = [];
	for (const line of lines) {
		// gdx-teavm tags entries by FileType: i = Internal (engine assets),
		// c = Classpath (libGDX-bundled resources like lsans-15.fnt/png and
		// the built-in shaders). The engine looks both up via
		// Gdx.files.internal/classpath and we collapse them onto the same
		// in-memory layer in the worker, so we fetch both kinds.
		if (!line.startsWith('i:b:') && !line.startsWith('c:b:')) continue;
		const after = line.substring(4);
		const lastColon = after.lastIndexOf(':');
		const second = after.lastIndexOf(':', lastColon - 1);
		const rawPath = after.substring(0, second);
		// Strip leading '/' so the URL is relative to assets/
		relPaths.push(rawPath.startsWith('/') ? rawPath.substring(1) : rawPath);
	}
	const tasks = relPaths.map(async (rel) => {
		try {
			const ar = await (await fetch('assets/' + rel, { cache: 'no-store' })).arrayBuffer();
			self.__engineAssets.set(rel, new Int8Array(ar));
			self.__engineAssetPaths.push(rel);
		}
		catch (e) {
			self.postMessage('engine-worker-boot: WARN fetch failed ' + rel + ': ' + e);
		}
	});
	await Promise.all(tasks);
	self.postMessage('engine-worker-boot: fetched ' + self.__engineAssets.size
		+ ' engine assets (' + Array.from(self.__engineAssets.values()).reduce((a, b) => a + b.byteLength, 0) + ' bytes)');
}

// jpeg-js: pure-JS JPEG decoder. WC3 uses JPEG BLPs for many UI textures
// (the menu background, button frames, unit portraits). The browser's
// native createImageBitmap discards their alpha channel; jpeg-js preserves
// the BGRA. Loaded the same way the main-thread build does in index.html
// — via importScripts here since we're in a worker.
self.postMessage('engine-worker-boot: importing jpeg-js');
try {
	importScripts('scripts/jpeg-js.js');
}
catch (err) {
	self.postMessage('engine-worker-boot: WARN jpeg-js import failed: ' + err);
}

self.postMessage('engine-worker-boot: importing gdx.wasm.js');
try {
	importScripts('scripts/gdx.wasm.js');
}
catch (err) {
	self.postMessage('engine-worker-boot: ERROR loading gdx.wasm.js: ' + err);
	throw err;
}

self.postMessage('engine-worker-boot: gdx.wasm.js imported, instantiating module…');

// `Gdx` here is the factory function the imported script left in worker scope.
// Calling it returns the wasm-loaded module instance via moduleArg.ready.
// `locateFile` is harmless when the wasm is base64-embedded inline (which it
// is) but kept for symmetry with the documented Emscripten pattern.
Promise.all([
	self.Gdx({ locateFile: (path) => 'scripts/' + path }),
	w3InitOpfs(),
	w3FetchEngineAssets(),
])
	.then(([gdxInstance]) => {
		self.Gdx = gdxInstance;
		self.postMessage('engine-worker-boot: gdx.wasm module instantiated');
		try {
			importScripts('engine-worker.js');
		}
		catch (err) {
			self.postMessage('engine-worker-boot: ERROR loading engine-worker.js: ' + err);
			throw err;
		}
		self.postMessage('engine-worker-boot: engine bundle loaded, invoking main()');
		self.main();
	})
	.catch((err) => {
		self.postMessage('engine-worker-boot: ERROR boot rejected: '
			+ (err && err.message ? err.message : String(err)));
	});

// Watchdog: if neither the module nor an error has surfaced after 5s, that's
// almost certainly a wasm-instantiation hang. Surface it so we don't stare at
// "instantiating module…" forever.
setTimeout(() => {
	if (typeof self.Gdx === 'function') {
		self.postMessage('engine-worker-boot: WARN gdx.wasm has not resolved after 5s — '
			+ 'check console for wasm errors or CSP blocks on WebAssembly');
	}
}, 5000);
