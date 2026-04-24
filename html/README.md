# Warsmash Web Port — Dev Notes

TeaVM-compiled build that runs the Warsmash engine in the browser. If you're
on the desktop build, see the root [`README.md`](../README.md) — this file is
web-only.

## Quick start

```sh
./html/dev.sh build    # ./gradlew :html:buildWeb
./html/dev.sh serve    # python3 -m http.server 8000 in html/build/dist/webapp
./html/dev.sh dev      # build + serve, one shot
```

Then open <http://127.0.0.1:8000/>. Hard-reload (⌘⇧R / Ctrl⇧R) after rebuilds
to bust `app.js?v=…` cache.

## Asset staging

The engine doesn't ship with Warcraft III assets — you supply them yourself.
On first load, a worker extracts staged MPQs into OPFS under `/extracted` and
drops a `.w3-ready` marker. The boot screen waits for that marker. If you get
stuck on "waiting for extraction worker", your OPFS is empty — seed it via
the file picker at boot.

## URL flags

| flag | default | meaning |
|---|---|---|
| `?menu=1` | off | route through the real `MenuUI` (MeleeUI, Custom Game, …) instead of the direct-map harness |
| `?preloadOpfs=N` | 12 | OPFS read concurrency (1–64) |
| `?preloadDecode=M` | 6 | JPEG-BLP decode concurrency (1–32) |
| `?tier1=0` | on | disable the parallel preload pump — fall back to serial |

Example: `http://127.0.0.1:8000/?menu=1&preloadOpfs=24`.

## Architecture cheatsheet

- **Java → JS** via TeaVM. Build output is a single `app.js` plus assets,
  served static.
- **No async → sync bridge.** The engine's DataSource contract is synchronous.
  OPFS is async. We bridge by pre-reading everything the main menu touches
  into an `InMemoryDataSource` before handing it to the engine. See
  `ExtractedPreloader`, `WebMapBootScreen`, `MapBytesEnsurer`.
- **Backend-swap hooks** follow a static-plugin pattern — each has a
  `install(…)` the web launcher calls once at startup:
  - `util.Platform` — URL opener
  - `parsers.fdf.DynamicFontGeneratorHolderFactory` — fonts (FreeType vs stub)
  - `networking.NetworkPlatform` — real UDP/TCP on desktop, no-op on web
  - `datasources.MapBytesEnsurer` — OPFS lazy-fetch safety net on web
- **ANTLR UUID.** `html/src/org/antlr/v4/runtime/atn/ATNDeserializer.java`
  is a web-only override — TeaVM doesn't emulate `new UUID(long, long)`, so
  we round-trip through `UUID.fromString`.
