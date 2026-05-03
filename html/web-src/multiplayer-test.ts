// Warsmash multiplayer substrate test — Poki Netlib over parcel.
// Pure JS-side proof that signaling + WebRTC P2P + byte transfer works
// before we wire it into the TeaVM Java engine. Two browser tabs:
// one hosts via `Host new lobby`, the other joins by code.

import { Network } from '@poki/netlib'

// Picked once for this engine's namespace; lobbies under this UUID are
// isolated from real Poki-published games. Generated locally — not
// registered server-side, just a routing key.
const WARSMASH_GAME_ID = 'a664aaff-9291-4c9c-9f26-47b56eb7914a'

// ---- WebSocket instrumentation -------------------------------------
// Patches the global WebSocket constructor BEFORE netlib is imported
// (must run before module-level `new Network()` that internally opens
// the signaling socket). Records every NEW / OPEN / SEND / RECV /
// CLOSE / ERROR so the diagnostic log shows the wire-level reality —
// useful for spotting silent reconnects, duplicate `connect` packets,
// missing offer SENDs, etc.
;(function instrumentWebSocket (): void {
  let wsSeq = 0
  let logEl: HTMLElement | null = null
  const pending: string[] = []
  const append = (text: string): void => {
    if (logEl == null) {
      logEl = document.getElementById('ws-log')
    }
    if (logEl == null) {
      pending.push(text)
      return
    }
    if (pending.length > 0) {
      pending.forEach(p => logEl!.appendChild(makeLine(p)))
      pending.length = 0
    }
    logEl.appendChild(makeLine(text))
    logEl.scrollTop = logEl.scrollHeight
  }
  const makeLine = (text: string): HTMLDivElement => {
    const d = document.createElement('div')
    d.textContent = text
    return d
  }
  const stamp = (): string => new Date().toISOString().slice(11, 23)
  const trim = (s: string, n: number): string => (s.length > n ? s.substring(0, n) + '...' : s)

  const Original = window.WebSocket
  // Wrap as a proxy. Everything else (CONNECTING/OPEN/etc constants, prototype)
  // stays inherited from Original.
  const Wrapper = function (this: WebSocket, url: string, protocols?: string | string[]) {
    const ws = (protocols !== undefined ? new Original(url, protocols) : new Original(url))
    const id = ++wsSeq
    append('[' + stamp() + '] ws#' + id + ' NEW ' + url)
    const origSend = ws.send.bind(ws)
    ws.send = (data: any): void => {
      const preview = (typeof data === 'string') ? data : '(binary ' + data.byteLength + 'B)'
      append('[' + stamp() + '] ws#' + id + ' SEND ' + trim(preview, 240))
      return origSend(data)
    }
    ws.addEventListener('message', (ev) => {
      const preview = (typeof ev.data === 'string') ? ev.data : '(binary ' + (ev.data?.byteLength) + 'B)'
      append('[' + stamp() + '] ws#' + id + ' RECV ' + trim(preview, 240))
    })
    ws.addEventListener('open',  () => append('[' + stamp() + '] ws#' + id + ' OPEN'))
    ws.addEventListener('close', (ev) => append('[' + stamp() + '] ws#' + id + ' CLOSE code=' + ev.code + ' reason="' + (ev.reason || '') + '"'))
    ws.addEventListener('error', () => append('[' + stamp() + '] ws#' + id + ' ERROR'))
    return ws
  } as unknown as typeof WebSocket
  // Inherit constants from the original.
  Wrapper.CONNECTING = Original.CONNECTING
  Wrapper.OPEN = Original.OPEN
  Wrapper.CLOSING = Original.CLOSING
  Wrapper.CLOSED = Original.CLOSED
  Wrapper.prototype = Original.prototype
  ;(window as any).WebSocket = Wrapper
})()

// ---- DOM refs --------------------------------------------------------

const logEl       = document.getElementById('log') as HTMLDivElement
const peersEl     = document.getElementById('peers') as HTMLDivElement
const lobbyCodeEl = document.getElementById('lobby-code') as HTMLSpanElement
const selfIdEl    = document.getElementById('self-id') as HTMLSpanElement
const joinCodeEl  = document.getElementById('join-code') as HTMLInputElement
const btnHost      = document.getElementById('btn-host') as HTMLButtonElement
const btnJoin      = document.getElementById('btn-join') as HTMLButtonElement
const btnLeave     = document.getElementById('btn-leave') as HTMLButtonElement
const btnSendText  = document.getElementById('btn-send-text') as HTMLButtonElement
const btnSendBytes = document.getElementById('btn-send-bytes') as HTMLButtonElement
const sendTextEl   = document.getElementById('send-text') as HTMLInputElement

function log (text: string, cls?: 'status' | 'err' | 'msg' | 'self'): void {
  const t = new Date().toISOString().slice(11, 23)
  const div = document.createElement('div')
  if (cls != null) div.className = cls
  div.textContent = '[' + t + '] ' + text
  logEl.appendChild(div)
  logEl.scrollTop = logEl.scrollHeight
}

function refreshPeers (): void {
  if (net.peers.size === 0) {
    peersEl.textContent = '(none)'
    btnSendText.disabled = true
    btnSendBytes.disabled = true
    return
  }
  peersEl.innerHTML = ''
  net.peers.forEach((_peer, id) => {
    const span = document.createElement('span')
    span.className = 'peer-row'
    span.textContent = id
    peersEl.appendChild(span)
  })
  btnSendText.disabled = false
  btnSendBytes.disabled = false
}

function bytesToHex (buf: ArrayBuffer | ArrayBufferView): string {
  const arr = (buf instanceof ArrayBuffer)
    ? new Uint8Array(buf)
    : new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength)
  let s = ''
  for (let i = 0; i < arr.length; i++) {
    s += (i > 0 ? ' ' : '') + arr[i].toString(16).padStart(2, '0').toUpperCase()
  }
  return s
}

// ---- Netlib wiring ---------------------------------------------------

log('Constructing Network (parcel-bundled @poki/netlib)...', 'status')
const net = new Network(WARSMASH_GAME_ID)
;(window as any).net = net // for console poking

net.on('ready', () => {
  log('ready — self id: ' + net.id, 'status')
  selfIdEl.textContent = net.id
  btnHost.disabled = false
  btnJoin.disabled = false
})

net.on('lobby', (code, info) => {
  log('joined lobby ' + code + ' (' + JSON.stringify(info) + ')', 'status')
  lobbyCodeEl.textContent = code
  btnLeave.disabled = false
})

net.on('left', () => {
  log('left lobby', 'status')
  lobbyCodeEl.textContent = '—'
  btnLeave.disabled = true
  refreshPeers()
})

net.on('connecting',   peer => log('peer ' + peer.id + ' connecting...', 'status'))
net.on('connected',    peer => { log('peer ' + peer.id + ' CONNECTED', 'status'); refreshPeers() })
net.on('disconnected', peer => { log('peer ' + peer.id + ' disconnected', 'status'); refreshPeers() })
net.on('reconnecting', peer => log('peer ' + peer.id + ' reconnecting...', 'status'))
net.on('reconnected',  peer => { log('peer ' + peer.id + ' reconnected', 'status'); refreshPeers() })

net.on('leader', leaderID => log('leader is now ' + leaderID, 'status'))
net.on('failed', () => log('network FAILED', 'err'))
net.on('close',  reason => log('network closed: ' + (reason ?? '(none)'), 'err'))
net.on('rtcerror',       e => log('rtcerror: ' + ((e as any)?.error?.message ?? String(e)), 'err'))
net.on('signalingerror', e => log('signalingerror: ' + JSON.stringify(e), 'err'))

net.on('message', (peer, channel, data) => {
  if (typeof data === 'string') {
    log('  ← ' + peer.id + ' [' + channel + '] "' + data + '"', 'msg')
  } else if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
    log('  ← ' + peer.id + ' [' + channel + '] bytes(' + data.byteLength + '): ' + bytesToHex(data), 'msg')
  } else if (data instanceof Blob) {
    data.arrayBuffer().then(buf => {
      log('  ← ' + peer.id + ' [' + channel + '] blob(' + buf.byteLength + '): ' + bytesToHex(buf), 'msg')
    }).catch((e: unknown) => log('blob decode failed: ' + String(e), 'err'))
  } else {
    log('  ← ' + peer.id + ' [' + channel + '] (unknown type) ' + JSON.stringify(data), 'msg')
  }
})

// ---- Button wiring ---------------------------------------------------

btnHost.onclick = async () => {
  btnHost.disabled = true
  btnJoin.disabled = true
  try {
    const code = await net.create()
    if (code !== '') {
      log('hosting lobby ' + code, 'self')
    } else {
      log('create() returned empty — not connected to signaling?', 'err')
      btnHost.disabled = false
      btnJoin.disabled = false
    }
  } catch (e: unknown) {
    log('create() threw: ' + String(e), 'err')
    btnHost.disabled = false
    btnJoin.disabled = false
  }
}

btnJoin.onclick = async () => {
  const code = joinCodeEl.value.trim()
  if (code === '') { log('enter a lobby code first', 'err'); return }
  btnHost.disabled = true
  btnJoin.disabled = true
  try {
    const info = await net.join(code)
    if (info != null) {
      log('joined lobby ' + code, 'self')
    } else {
      log('join() returned undefined — bad code or full lobby?', 'err')
      btnHost.disabled = false
      btnJoin.disabled = false
    }
  } catch (e: unknown) {
    log('join() threw: ' + String(e), 'err')
    btnHost.disabled = false
    btnJoin.disabled = false
  }
}

btnLeave.onclick = async () => {
  try { await net.leave() } catch (e: unknown) { log('leave() threw: ' + String(e), 'err') }
  btnHost.disabled = false
  btnJoin.disabled = false
}

btnSendText.onclick = () => {
  const text = sendTextEl.value
  log('  → broadcast text "' + text + '"', 'self')
  net.broadcast('unreliable', text)
}

btnSendBytes.onclick = () => {
  const payload = new Uint8Array([0xDE, 0xAD, 0xBE, 0xEF, 0xCA, 0xFE, 0xBA, 0xBE])
  log('  → broadcast bytes(' + payload.byteLength + '): ' + bytesToHex(payload), 'self')
  // Pass the underlying ArrayBuffer so the receiver's byteLength matches what
  // we sent (no leading offset surprises in the Uint8Array view).
  net.broadcast('unreliable', payload.buffer)
}
