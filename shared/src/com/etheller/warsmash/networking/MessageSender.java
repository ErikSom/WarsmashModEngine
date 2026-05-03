package com.etheller.warsmash.networking;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Abstraction over "I can send a buffer to a destination identifier" — the
 * single piece of behaviour {@link WarsmashServerWriter} actually needs from
 * the underlying transport. {@code destination} is typed {@code Object}
 * rather than {@code java.net.SocketAddress} so non-UDP transports can plug
 * in without pulling {@code java.net.*} into the TeaVM reachability graph
 * (the web build's
 * {@code com.etheller.warsmash.html.network.WebRtcOrderedServer} uses a
 * custom marker class to identify peers by their netlib peer id rather
 * than IP+port). Desktop's {@code OrderedUdpServer} continues to pass real
 * {@code SocketAddress} instances — they just arrive here as {@code Object}.
 */
public interface MessageSender {
	void send(Object destination, ByteBuffer buffer) throws IOException;
}
