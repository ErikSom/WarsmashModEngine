// Poki Netlib bridge for the TeaVM Java side.
//
// Pure-side-effect module: parcel bundles this together with @poki/netlib
// into a single JS file that, when loaded via a `<script>` tag, assigns a
// flat function API to the global object. The Java side
// (`PokiNetlibBridge.java`) calls into these globals via `@JSBody`.
//
// Mirrors the pattern engine-worker-boot.js uses for the worker target —
// a thin JS surface that hides a stateful library behind primitives that
// `@JSFunctor` can marshal cleanly.
//
// All callbacks here receive primitives (strings, numbers, Uint8Array)
// because TeaVM `@JSFunctor` cannot easily marshal arbitrary JS objects.
// `peer` events expose only `peer.id` (string); incoming binary messages
// arrive as Uint8Array regardless of whether the wire type was Blob,
// ArrayBuffer, or ArrayBufferView.

import { Network } from '@poki/netlib'

interface BridgeCallbacks {
  ready?: (selfId: string) => void
  /** Fires when we enter a lobby (whether by `create()` or `join()`).
   *  `leaderId` carries the lobby's current leader at join time —
   *  netlib already knows it from the 'joined' packet, so this
   *  spares consumers from racing the separate 'leader' event. */
  lobby?: (code: string, leaderId: string) => void
  left?: () => void
  peerConnected?: (peerId: string) => void
  peerDisconnected?: (peerId: string, reason: string) => void
  leader?: (leaderId: string) => void
  message?: (peerId: string, channel: string, bytes: Uint8Array, isString: boolean, stringValue: string) => void
  error?: (kind: string, message: string) => void
  /** Fires after a successful reconnect — netlib has restored our
   *  saved peer id + secret with the signaling server. The 'ready'
   *  callback does NOT fire on reconnect (the server short-circuits
   *  it once receivedID is already set), so consumers that gate on
   *  "are we connected?" must subscribe to this too. */
  signalingreconnected?: (selfId: string) => void
}

// Lazy-resolved reference to the live Network instance. Init() returns
// false on platforms where construction fails so the Java side can fall
// back gracefully — e.g. very old browsers without WebRTC.
let net: Network | null = null

const cb: Required<BridgeCallbacks> = {
  ready:                  () => {},
  lobby:                  () => {},
  left:                   () => {},
  peerConnected:          () => {},
  peerDisconnected:       () => {},
  leader:                 () => {},
  message:                () => {},
  error:                  () => {},
  signalingreconnected:   () => {}
}

function safe<T extends (...args: any[]) => unknown> (fn: T) {
  return (...args: Parameters<T>): void => {
    try { fn(...args) }
    catch (e) {
      // Swallow Java-side throws so they don't propagate back into netlib
      // and tear down the WebSocket / RTCPeerConnection state machines.
      // Real diagnostics should be emitted by the Java callback itself.
      console.error('[poki-bridge] callback threw:', e)
    }
  }
}

function toUint8 (data: ArrayBuffer | ArrayBufferView): Uint8Array {
  if (data instanceof Uint8Array) return data
  if (data instanceof ArrayBuffer) return new Uint8Array(data)
  return new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
}

// ----------------------------------------------------------------------
// Public API consumed by PokiNetlibBridge.java via @JSBody.
// ----------------------------------------------------------------------

// Construct the Network and install all listeners. Idempotent: a second
// call closes the previous Network and starts fresh, so the menu can
// re-host after a leave without reloading the page.
export function pokiBridgeInit (gameId: string, callbacks: BridgeCallbacks): boolean {
  if (net != null) {
    try { net.close('reinit') } catch { /* ignore */ }
    net = null
  }
  cb.ready                = callbacks.ready                ?? cb.ready
  cb.lobby                = callbacks.lobby                ?? cb.lobby
  cb.left                 = callbacks.left                 ?? cb.left
  cb.peerConnected        = callbacks.peerConnected        ?? cb.peerConnected
  cb.peerDisconnected     = callbacks.peerDisconnected     ?? cb.peerDisconnected
  cb.leader               = callbacks.leader               ?? cb.leader
  cb.message              = callbacks.message              ?? cb.message
  cb.error                = callbacks.error                ?? cb.error
  cb.signalingreconnected = callbacks.signalingreconnected ?? cb.signalingreconnected

  try {
    net = new Network(gameId)
  } catch (e) {
    console.error('[poki-bridge] Network constructor threw:', e)
    return false
  }

  net.on('ready',                () =>     safe(cb.ready)(net!.id))
  // signalingreconnected fires when netlib's auto-reconnect succeeds
  // on a transient WebSocket drop within the same page session. The
  // 'ready' event does NOT fire on reconnect (the server short-
  // circuits welcome once it sees we already have an id), so the
  // signalingreconnected hook is the right place for consumers to
  // re-establish "we're connected" UI state.
  //
  // (Reconnect ACROSS a page reload would need persistence of
  // signaling.receivedID/receivedSecret + a netlib API to inject
  // them on construction. Both are netlib internals today; we'd
  // upstream a public reconnect surface there before relying on it.)
  net.on('signalingreconnected', () =>     safe(cb.signalingreconnected)(net!.id))
  net.on('lobby',                (code, info) => safe(cb.lobby)(code, info?.leader ?? ''))
  net.on('left',                 () =>     safe(cb.left)())
  net.on('connected',            (peer) => safe(cb.peerConnected)(peer.id))
  net.on('disconnected',         (peer) => safe(cb.peerDisconnected)(peer.id, 'disconnected'))
  net.on('leader',               (id) =>   safe(cb.leader)(id))
  net.on('failed',               () =>     safe(cb.error)('failed', 'network failed'))
  net.on('rtcerror',             (e: any) => safe(cb.error)('rtcerror', String(e?.error?.message ?? e)))
  net.on('signalingerror',       (e) =>    safe(cb.error)('signalingerror', JSON.stringify(e)))

  net.on('message', (peer, channel, data) => {
    // Binary path — typical for in-game traffic (the lockstep wire
    // protocol is raw bytes via WarsmashClientWriter / WarsmashServerWriter).
    if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
      safe(cb.message)(peer.id, channel, toUint8(data), false, '')
      return
    }
    // Blob fallback — netlib's type signature allows it but our path
    // never sends Blobs. Decode async and re-fire the callback.
    if (typeof Blob !== 'undefined' && data instanceof Blob) {
      data.arrayBuffer().then(buf => {
        safe(cb.message)(peer.id, channel, new Uint8Array(buf), false, '')
      }).catch((e: unknown) => {
        safe(cb.error)('message-decode', 'blob -> arrayBuffer failed: ' + String(e))
      })
      return
    }
    // String path — for ad-hoc text messages (chat, lobby control).
    if (typeof data === 'string') {
      safe(cb.message)(peer.id, channel, new Uint8Array(0), true, data)
      return
    }
    // Unknown shape — degrade gracefully and surface via error callback.
    safe(cb.error)('message-shape', 'unexpected message data type: ' + Object.prototype.toString.call(data))
  })

  return true
}

// Create a new lobby. cbCode receives the lobby code; cbErr receives a
// reason string on failure. Never throws.
export function pokiBridgeCreateLobby (cbCode: (code: string) => void, cbErr: (reason: string) => void, settings?: object): void {
  if (net == null) { safe(cbErr)('not-initialized'); return }
  net.create(settings as any).then(code => {
    if (code !== '') safe(cbCode)(code)
    else             safe(cbErr)('create-empty')
  }).catch((e: unknown) => safe(cbErr)(String((e as any)?.message ?? e)))
}

/** Result of a successful join. Includes the host-supplied customData
 *  so the caller can do compat checks (game version, mods, …) BEFORE
 *  settling the user into the lobby. Shape matches a subset of
 *  netlib's `LobbyInfo`. */
export interface JoinedLobbyInfo {
  code: string
  customData?: { [key: string]: any }
}

// Join an existing lobby by code. Fires cbInfo({code, customData}) on
// success or cbErr(reason) on failure.
export function pokiBridgeJoinLobby (code: string, cbInfo: (info: JoinedLobbyInfo) => void, cbErr: (reason: string) => void, password?: string): void {
  if (net == null) { safe(cbErr)('not-initialized'); return }
  net.join(code, password).then(info => {
    if (info != null) safe(cbInfo)({ code: info.code ?? code, customData: (info as any).customData })
    else              safe(cbErr)('join-not-found-or-full')
  }).catch((e: unknown) => safe(cbErr)(String((e as any)?.message ?? e)))
}

// Leave the current lobby. Fire-and-forget; the 'left' callback fires
// when the server confirms.
export function pokiBridgeLeaveLobby (): void {
  if (net == null) return
  net.leave().catch((e: unknown) => safe(cb.error)('leave', String((e as any)?.message ?? e)))
}

// Send raw bytes to a specific peer over the named channel. Java passes
// an Int8Array; we reinterpret as Uint8Array so RTCDataChannel.send sees
// the right magnitude per byte.
export function pokiBridgeSendBytesTo (peerId: string, channel: string, int8: Int8Array): void {
  if (net == null) return
  const u8 = new Uint8Array(int8.buffer, int8.byteOffset, int8.byteLength)
  // Pass the underlying ArrayBuffer slice so the receiver's byteLength
  // matches what we sent (no leading offset surprises in the view).
  const buf = (u8.byteOffset === 0 && u8.byteLength === u8.buffer.byteLength)
    ? u8.buffer
    : u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength)
  net.send(channel, peerId, buf)
}

// Broadcast raw bytes to all connected peers over the named channel.
// Same magnitude-conversion notes as sendBytesTo.
export function pokiBridgeBroadcastBytes (channel: string, int8: Int8Array): void {
  if (net == null) return
  const u8 = new Uint8Array(int8.buffer, int8.byteOffset, int8.byteLength)
  const buf = (u8.byteOffset === 0 && u8.byteLength === u8.buffer.byteLength)
    ? u8.buffer
    : u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength)
  net.broadcast(channel, buf)
}

// Send a UTF-8 string to a specific peer over the named channel. Used
// for JSON control messages (player name / map picks / start handshake)
// during the lobby phase. Engine traffic uses sendBytesTo.
export function pokiBridgeSendStringTo (peerId: string, channel: string, str: string): void {
  if (net == null) return
  net.send(channel, peerId, str)
}

// Broadcast a UTF-8 string to all connected peers.
export function pokiBridgeBroadcastString (channel: string, str: string): void {
  if (net == null) return
  net.broadcast(channel, str)
}

// Update lobby settings (mutable on the host side via the netlib API).
// Used to publish the host's chosen map / lobby name / max players.
export function pokiBridgeSetLobbySettings (settings: object, cbOk?: () => void, cbErr?: (reason: string) => void): void {
  if (net == null) { cbErr?.('not-initialized'); return }
  net.setLobbySettings(settings as any).then(res => {
    if (res === true) cbOk?.()
    else              cbErr?.(res instanceof Error ? res.message : String(res))
  }).catch((e: unknown) => cbErr?.(String((e as any)?.message ?? e)))
}

export interface PublicLobbyEntry {
  code: string
  playerCount: number
  maxPlayers: number
  hasPassword: boolean
  customData?: { [key: string]: any }
  leader?: string
  createdAt: string
  updatedAt: string
}

// List public lobbies. Returns an empty array if not connected.
export function pokiBridgeListLobbies (cbOk: (entries: PublicLobbyEntry[]) => void, cbErr: (reason: string) => void): void {
  if (net == null) { cbErr('not-initialized'); return }
  net.list().then(entries => {
    cbOk(entries.map(e => ({
      code: e.code,
      playerCount: e.playerCount,
      maxPlayers: e.maxPlayers,
      hasPassword: e.hasPassword,
      customData: e.customData,
      leader: e.leader,
      createdAt: e.createdAt,
      updatedAt: e.updatedAt,
    })))
  }).catch((e: unknown) => cbErr(String((e as any)?.message ?? e)))
}

// Read-only state accessors — Java polls these instead of holding a
// reference to the Network object directly (cleaner @JSBody surface).
export function pokiBridgeSelfId (): string         { return net?.id ?? '' }
export function pokiBridgeCurrentLobby (): string   { return net?.currentLobby ?? '' }
export function pokiBridgeCurrentLeader (): string  { return net?.currentLeader ?? '' }
export function pokiBridgePeerCount (): number      { return net?.size ?? 0 }
export function pokiBridgePeerIds (): string[] {
  if (net == null) return []
  const ids: string[] = []
  net.peers.forEach((_peer, id) => ids.push(id))
  return ids
}
/** True iff a Network instance has been constructed. Doesn't imply
 *  signaling-ready — pair with the 'ready' callback for that. */
export function pokiBridgeReady (): boolean { return net != null }

// Tear down the Network (closes signaling + all peer connections).
// Survivable: pokiBridgeInit() can be called again afterward.
export function pokiBridgeClose (): void {
  if (net == null) return
  try { net.close('shutdown') } catch { /* ignore */ }
  net = null
}

// ----------------------------------------------------------------------
// Expose to the global object so PokiNetlibBridge.java's @JSBody calls
// can reach them. Mirrors the pattern jpeg-js etc. use — globals are the
// path of least resistance for the @JSBody single-line script bodies.
// ----------------------------------------------------------------------

// New code in the web-src/ codebase imports the functions directly,
// but we still install globals for two reasons:
//   1. TeaVM's PokiNetlibBridge.java @JSBody calls reach for them.
//   2. The persistent <script> in Layout.astro can warm up the bridge
//      from a vanilla <script> tag (Astro view-transitions inline a
//      separate <script> for hydration, but a static script tag with
//      transition:persist runs once and stays alive across navs).
const target: any = (typeof globalThis !== 'undefined') ? globalThis : self
target.pokiBridgeInit            = pokiBridgeInit
target.pokiBridgeCreateLobby     = pokiBridgeCreateLobby
target.pokiBridgeJoinLobby       = pokiBridgeJoinLobby
target.pokiBridgeLeaveLobby      = pokiBridgeLeaveLobby
target.pokiBridgeSendBytesTo     = pokiBridgeSendBytesTo
target.pokiBridgeBroadcastBytes  = pokiBridgeBroadcastBytes
target.pokiBridgeSelfId          = pokiBridgeSelfId
target.pokiBridgeCurrentLobby    = pokiBridgeCurrentLobby
target.pokiBridgeCurrentLeader   = pokiBridgeCurrentLeader
target.pokiBridgePeerCount       = pokiBridgePeerCount
target.pokiBridgePeerIds         = pokiBridgePeerIds
target.pokiBridgeSendStringTo    = pokiBridgeSendStringTo
target.pokiBridgeBroadcastString = pokiBridgeBroadcastString
target.pokiBridgeSetLobbySettings = pokiBridgeSetLobbySettings
target.pokiBridgeListLobbies     = pokiBridgeListLobbies
target.pokiBridgeReady           = pokiBridgeReady
target.pokiBridgeClose           = pokiBridgeClose
