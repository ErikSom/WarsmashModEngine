# What's new

## 0.2.0 — May 2026

The whole site has been rebuilt around a proper multiplayer lobby
and a tidier menu.

- **A real homepage.** Instead of dropping you straight onto the
  game's boot screen, the site now opens with three tiles: Play,
  Multiplayer, Manage assets. Pick your path.
- **Dedicated Multiplayer page.** Set your player name once (it's
  remembered), browse public lobbies, host a new one with a single
  click, or join by code. Lobbies whose host has left are filtered
  out automatically so the list stays clean.
- **Proper lobby room.** Once you're in a lobby you see a Warcraft
  III-style slot list with everyone's names, races, colours and
  teams. Slots are grouped under each force — "The Horde",
  "The Alliance", etc. — exactly as the map declares them.
- **Pick your position.** Click an open slot to claim it; click a
  different one to move. The host can close empty slots, kick
  players (with confirmation), and change the map mid-lobby — if
  the new map has fewer slots a confirmation lists who'd be removed.
- **Race & colour pickers** for each player. Colour is a clickable
  cube; click it for a small popup of all twelve Warcraft colours,
  with already-taken ones visibly disabled. Race-locked maps (most
  custom UMS games) display the locked race read-only.
- **Map browser with search.** A new picker reads every map under
  your install, shows the real map name + author + player count
  + a thumbnail preview pulled from the map itself, and you can
  filter the list by name, author, or path.
- **Coloured map names.** Custom map names with embedded colour
  codes (`|cffffaa00...|r`) render with the right colours instead
  of the literal escape characters.
- **In-game roster matches the lobby.** Race, colour and team picks
  from the lobby now actually take effect when the game starts.
  Previously the engine was using the map's defaults regardless.
- **Phone & tablet friendly.** The lobby, map picker, and asset
  manager reflow cleanly down to a phone in portrait orientation.
- **Cleaner tab close.** Closing or refreshing the tab now tells
  the signalling server you've left, so empty lobbies disappear
  promptly instead of lingering for the GC window.

Under the hood: the front end has been rewritten on Astro + Preact
with view transitions, replacing the single-file legacy build. The
multiplayer lobby exchanges its state over the same WebRTC mesh the
game uses, with the host as authority for slot assignments.

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
