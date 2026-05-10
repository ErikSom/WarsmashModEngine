// @ts-check
import { defineConfig } from 'astro/config';
import preact from '@astrojs/preact';

// Static-only build; the engine is loaded client-side from the same
// origin. base="./" gives us relative URLs everywhere so the bundle
// can be served from any sub-path (Cloudflare Pages preview deploys,
// the gradle-copied dist/ inside webapp/, etc.).
//
// outDir is local to web-src/ during the migration. The gradle
// :html:buildWebSrc task picks it up from here and copies the contents
// into html/build/dist/webapp/ alongside the TeaVM-built engine
// artefacts. Keeping the outputs separate at this stage avoids fighting
// the legacy webapp-src/ copy step.
export default defineConfig({
  output: 'static',
  base: './',
  build: {
    // Don't add hash suffixes to public assets — the engine worker
    // bootstrap and TeaVM @JSBody calls reference fixed filenames
    // (engine-worker.js, poki-bridge.js, etc.). Hashing JS modules
    // produced by Astro itself is still fine; this only affects
    // anything we copy into /public.
    assets: '_astro'
  },
  integrations: [
    preact({ compat: false })
  ],
  // Astro's view-transitions API keeps marked elements alive across
  // page nav — which is how the Poki bridge + engine worker survive
  // /multiplayer/:code → /play without losing the WebRTC mesh.
  // (No global config here; pages opt in via <ClientRouter />.)
  vite: {
    // Surface clear logs when imports resolve oddly during the
    // multi-package migration; quiet later.
    logLevel: 'info',
    ssr: {
      // @poki/netlib ships as CJS with parcel-style namespaced exports,
      // and mdx-m3-viewer ships as CJS via webpack's __esModule wrapper
      // — neither pattern resolves cleanly on Node's ESM-from-CJS path.
      // noExternal forces Vite to bundle them for SSR, which hands the
      // interop to Vite's CJS handler instead of Node's.
      noExternal: ['@poki/netlib', 'mdx-m3-viewer']
    }
  }
});
