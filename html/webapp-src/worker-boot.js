// Worker entry. Loaded via `new Worker('worker-boot.js')`. Imports the
// TeaVM-compiled worker.js (UMD) which attaches `main` to `self`, then
// invokes it. Keeping the boot separate means the TeaVM output is a
// pure library that we control the entry of.
importScripts('worker.js');
self.main();
