package net.warsmash.networking.udp;

public interface OrderedUdpServerListener extends UdpServerListener {
	// sourceAddress widened to Object — see UdpServerListener.parse for rationale.
	void cantReplay(Object sourceAddress, int seqNo);
}
