# What's new

## 0.1.2 — April 2026

- **In-game performance.** Multi-second freezes are gone. The game
  starts up much faster too — the heavy first-tick AI initialisation
  has been disabled (single-player matches play out without computer
  opponents for now while we work on faster AI).
- **Crisper visuals.** A bunch of behind-the-scenes work removed
  per-frame overhead from MPQ asset reads and OpenGL error checks.
- **Right-click works.** Move/attack-move orders register correctly.
- **Mouse cursor.** The OS cursor is hidden over the game; the
  in-game Warcraft III cursor is the only one you see.
- **Background image** behind the game canvas's letterbox area when
  your window doesn't match the game's 4:3 aspect.
- **Window resize** updates the game canvas live, though text stays
  pixel-sharp only at the size you opened the page at — reload after
  resizing for crisp text.

## 0.1.1 — April 2026

- **Smoother upgrade from older builds.** If we detect cached files
  from a previous version that didn't always boot cleanly (especially
  on phones), we now wipe everything and ask you to re-pick your
  Warcraft III folder once. Future visits work as normal.

## 0.1.0 — April 2026

The first proper release of the browser build.

- **Much faster startup.** The game now opens straight to the main menu
  instead of waiting ~30 seconds while it unpacked your install on every
  visit. Cached visits are essentially instant.
- **Lower memory use.** The browser no longer needs to hold the entire
  game's contents in memory just to start, which paves the way for the
  game to run on phones and tablets in a future release.
- **Sound.** Music and sound effects play in the menu and during games.
- **Custom Game now shows your maps.** Picking a map from the menu just
  works.
- **Cleaner first run.** Selecting your Warcraft III folder takes you
  straight to a "Play game" button — no extra "Start extraction" step.
- **Loading splash.** Clicking "Play game" shows a brief image while the
  game starts up, so you're not looking at a black screen.
- **Smoother main-menu intro.** The chains now drop into place once,
  instead of dropping, snapping back, and dropping again.
- **One-time tidy-up.** If you played an earlier build, around a
  gigabyte of unused cached files is freed up for you on first visit.
