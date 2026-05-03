package com.etheller.warsmash.html.network;

/**
 * Marker / identifier class wrapping a Poki Netlib peer id (an opaque
 * string assigned by the netlib signaling server). Used as the
 * {@link Object}-typed "address" key by {@code WarsmashServer} et al.
 * (engine-side protocol code keys per-client state on these as opaque
 * map keys).
 *
 * <p>Equality and hash are by peer id, so a fresh
 * {@code new PeerIdSocketAddress(peerId)} can be used as a Map key
 * interchangeably with any earlier instance for the same peer.
 *
 * <p>Class kept under the legacy "SocketAddress" name so the diff against
 * earlier transport scaffolding stays small. It does NOT extend
 * {@link java.net.SocketAddress} — TeaVM's classlib doesn't ship that
 * class, and the engine's protocol code only treats addresses as
 * {@code Object}-typed map keys anyway.
 */
public final class PeerIdSocketAddress {
	private final String peerId;

	public PeerIdSocketAddress(final String peerId) {
		if (peerId == null) {
			throw new IllegalArgumentException("peerId must not be null");
		}
		this.peerId = peerId;
	}

	public String getPeerId() {
		return this.peerId;
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o) return true;
		if (!(o instanceof PeerIdSocketAddress)) return false;
		return this.peerId.equals(((PeerIdSocketAddress) o).peerId);
	}

	@Override
	public int hashCode() {
		return this.peerId.hashCode();
	}

	@Override
	public String toString() {
		return "peer:" + this.peerId;
	}
}
