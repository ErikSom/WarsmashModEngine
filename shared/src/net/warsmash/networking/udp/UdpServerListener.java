package net.warsmash.networking.udp;

import java.nio.ByteBuffer;

public interface UdpServerListener {
	// sourceAddress is typed Object rather than java.net.SocketAddress so
	// the engine's protocol code can plug in non-UDP transports without
	// pulling java.net.* into the TeaVM reachability graph (web build's
	// WebRtcOrderedServer uses a custom marker class as the key). Desktop's
	// OrderedUdpServer continues to pass real SocketAddress instances —
	// they just appear here as Object.
	void parse(Object sourceAddress, ByteBuffer buffer);
}
