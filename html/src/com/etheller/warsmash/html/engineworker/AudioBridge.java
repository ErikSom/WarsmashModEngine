package com.etheller.warsmash.html.engineworker;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Sound playback bridge from the engine worker back to the main thread.
 * Workers can't reliably drive a real-time {@code AudioContext} on every
 * browser, but the main thread already has Howler loaded. So: worker reads
 * the audio bytes, posts them to main with a unique ID, returns a Sound
 * proxy; play/stop calls postMessage by ID.
 *
 * <p>IDs are doubles (TeaVM longs become two ints; doubles round-trip
 * cleanly to JS Number and have 2^53 safe-integer range — plenty).
 */
final class AudioBridge {
	private AudioBridge() {
	}

	static void createSound(final double id, final byte[] bytes, final String name, final boolean isMusic) {
		final Int8Array arr = Int8Array.create(bytes.length);
		for (int i = 0; i < bytes.length; i++) {
			arr.set(i, bytes[i]);
		}
		createSoundImpl(id, arr, name, isMusic);
	}

	@JSBody(params = { "id", "bytes", "name", "isMusic" },
			script = "self.postMessage("
					+ "  { kind: 'audio.create', id: id, bytes: bytes, name: name, isMusic: isMusic },"
					+ "  [bytes.buffer]);")
	private static native void createSoundImpl(double id, Int8Array bytes, String name, boolean isMusic);

	@JSBody(params = { "id", "vol", "pitch", "pan", "loop" },
			script = "self.postMessage({"
					+ "  kind: 'audio.play', id: id, vol: vol, pitch: pitch, pan: pan, loop: loop"
					+ "});")
	static native void play(double id, float vol, float pitch, float pan, boolean loop);

	@JSBody(params = { "id" },
			script = "self.postMessage({ kind: 'audio.stop', id: id });")
	static native void stop(double id);

	@JSBody(params = { "id", "vol" },
			script = "self.postMessage({ kind: 'audio.volume', id: id, vol: vol });")
	static native void setVolume(double id, float vol);

	@JSBody(params = { "id", "loop" },
			script = "self.postMessage({ kind: 'audio.loop', id: id, loop: loop });")
	static native void setLooping(double id, boolean loop);
}
