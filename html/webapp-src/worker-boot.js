// Worker entry. Loaded via `new Worker('worker-boot.js')`. Defines JS
// helpers (OPFS, hex-dump, etc.) on `self` before importing the TeaVM
// bundle and calling main(). TeaVM's @JSBody script parser is ES5 only,
// so anything using async/await, arrow classes etc. lives here.

self.w3ReadHeader = async function(path, n) {
	try {
		const parts = String(path).split('/').filter(Boolean);
		const root = await navigator.storage.getDirectory();
		let dir = await root.getDirectoryHandle('w3');
		for (let i = 0; i < parts.length - 1; i++) {
			dir = await dir.getDirectoryHandle(parts[i]);
		}
		const fh = await dir.getFileHandle(parts[parts.length - 1]);
		const h = await fh.createSyncAccessHandle();
		try {
			const total = h.getSize();
			const toRead = Math.min(n | 0, total);
			const buf = new Uint8Array(toRead);
			h.read(buf, { at: 0 });
			let hex = '';
			for (let j = 0; j < buf.length; j++) {
				hex += (buf[j] < 16 ? '0' : '') + buf[j].toString(16);
			}
			self.postMessage('header ' + path + ' (size=' + total + '): ' + hex);
		}
		finally { h.close(); }
	}
	catch (e) {
		self.postMessage('header ' + path + ' ERROR: ' + (e && e.message ? e.message : String(e)));
	}
};

importScripts('worker.js');
self.main();
