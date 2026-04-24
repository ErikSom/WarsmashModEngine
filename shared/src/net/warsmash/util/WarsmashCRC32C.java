package net.warsmash.util;

import java.util.zip.Checksum;

public final class WarsmashCRC32C implements Checksum {
	private static final int[] TABLE = new int[256];

	static {
		for (int i = 0; i < TABLE.length; i++) {
			int crc = i;
			for (int bit = 0; bit < 8; bit++) {
				if ((crc & 1) != 0) {
					crc = (crc >>> 1) ^ 0x82F63B78;
				}
				else {
					crc >>>= 1;
				}
			}
			TABLE[i] = crc;
		}
	}

	private int crc = -1;

	@Override
	public void update(final int b) {
		this.crc = (this.crc >>> 8) ^ TABLE[(this.crc ^ b) & 0xFF];
	}

	@Override
	public void update(final byte[] bytes, final int off, final int len) {
		for (int i = 0; i < len; i++) {
			this.crc = (this.crc >>> 8) ^ TABLE[(this.crc ^ bytes[off + i]) & 0xFF];
		}
	}

	@Override
	public long getValue() {
		return (~this.crc) & 0xFFFFFFFFL;
	}

	@Override
	public void reset() {
		this.crc = -1;
	}
}
