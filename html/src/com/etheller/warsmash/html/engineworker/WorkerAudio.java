package com.etheller.warsmash.html.engineworker;

import java.util.concurrent.atomic.AtomicLong;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.audio.AudioRecorder;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;

/**
 * Worker-side {@link Audio}. Reads bytes from {@link FileHandle}s, ships them
 * to the main thread via {@link AudioBridge}, returns proxy {@link Sound} /
 * {@link Music} objects whose play/stop/volume calls postMessage. Main owns
 * Howler; worker owns the engine's audio API.
 *
 * <p>If reading bytes fails (rare — DataSource layer is robust), returns a
 * silent fallback so engine code doesn't crash.
 */
final class WorkerAudio implements Audio {
	private static final AtomicLong NEXT_ID = new AtomicLong();

	private static long nextId() { return NEXT_ID.incrementAndGet(); }

	@Override
	public AudioDevice newAudioDevice(final int samplingRate, final boolean isMono) {
		return null;
	}

	@Override
	public AudioRecorder newAudioRecorder(final int samplingRate, final boolean isMono) {
		return null;
	}

	@Override
	public Sound newSound(final FileHandle fileHandle) {
		final byte[] bytes = readSafely(fileHandle);
		if (bytes == null) {
			return new SilentSound();
		}
		final long id = nextId();
		AudioBridge.createSound(id, bytes, fileHandle.path(), false);
		return new RemoteSound(id);
	}

	@Override
	public Music newMusic(final FileHandle file) {
		final byte[] bytes = readSafely(file);
		if (bytes == null) {
			return new SilentMusic();
		}
		final long id = nextId();
		AudioBridge.createSound(id, bytes, file.path(), true);
		return new RemoteMusic(id);
	}

	private static byte[] readSafely(final FileHandle fh) {
		try {
			return fh.readBytes();
		}
		catch (final Throwable t) {
			return null;
		}
	}

	@Override public boolean switchOutputDevice(final String deviceIdentifier) { return false; }
	@Override public String[] getAvailableOutputDevices() { return new String[0]; }

	// ----- Sound implementations -----

	private static final class RemoteSound implements Sound {
		private final long id;
		RemoteSound(final long id) { this.id = id; }
		@Override public long play() { AudioBridge.play(this.id, 1f, 1f, 0f, false); return -1L; }
		@Override public long play(final float volume) { AudioBridge.play(this.id, volume, 1f, 0f, false); return -1L; }
		@Override public long play(final float volume, final float pitch, final float pan) {
			AudioBridge.play(this.id, volume, pitch, pan, false); return -1L;
		}
		@Override public long loop() { AudioBridge.play(this.id, 1f, 1f, 0f, true); return -1L; }
		@Override public long loop(final float volume) { AudioBridge.play(this.id, volume, 1f, 0f, true); return -1L; }
		@Override public long loop(final float volume, final float pitch, final float pan) {
			AudioBridge.play(this.id, volume, pitch, pan, true); return -1L;
		}
		@Override public void stop() { AudioBridge.stop(this.id); }
		@Override public void pause() { /* not exposed by Howler proxy yet */ }
		@Override public void resume() { /* not exposed by Howler proxy yet */ }
		@Override public void dispose() { /* sounds are GC'd on main thread when worker forgets them; fine for now */ }
		@Override public void stop(final long soundId) { AudioBridge.stop(this.id); }
		@Override public void pause(final long soundId) { /* per-instance control needs main↔worker round-trip; skipped */ }
		@Override public void resume(final long soundId) { /* same */ }
		@Override public void setLooping(final long soundId, final boolean looping) { AudioBridge.setLooping(this.id, looping); }
		@Override public void setPitch(final long soundId, final float pitch) { /* per-instance only */ }
		@Override public void setVolume(final long soundId, final float volume) { AudioBridge.setVolume(this.id, volume); }
		@Override public void setPan(final long soundId, final float pan, final float volume) { AudioBridge.setVolume(this.id, volume); }
	}

	private static final class SilentSound implements Sound {
		@Override public long play() { return -1L; }
		@Override public long play(final float volume) { return -1L; }
		@Override public long play(final float volume, final float pitch, final float pan) { return -1L; }
		@Override public long loop() { return -1L; }
		@Override public long loop(final float volume) { return -1L; }
		@Override public long loop(final float volume, final float pitch, final float pan) { return -1L; }
		@Override public void stop() { }
		@Override public void pause() { }
		@Override public void resume() { }
		@Override public void dispose() { }
		@Override public void stop(final long soundId) { }
		@Override public void pause(final long soundId) { }
		@Override public void resume(final long soundId) { }
		@Override public void setLooping(final long soundId, final boolean looping) { }
		@Override public void setPitch(final long soundId, final float pitch) { }
		@Override public void setVolume(final long soundId, final float volume) { }
		@Override public void setPan(final long soundId, final float pan, final float volume) { }
	}

	// ----- Music implementations -----

	private static final class RemoteMusic implements Music {
		private final long id;
		private boolean playing;
		private boolean looping;
		private float volume = 1f;
		private float pan;
		private float position;

		RemoteMusic(final long id) { this.id = id; }
		@Override public void play() { this.playing = true; AudioBridge.play(this.id, this.volume, 1f, this.pan, this.looping); }
		@Override public void pause() { this.playing = false; AudioBridge.stop(this.id); }
		@Override public void stop() { this.playing = false; this.position = 0f; AudioBridge.stop(this.id); }
		@Override public boolean isPlaying() { return this.playing; }
		@Override public void setLooping(final boolean isLooping) { this.looping = isLooping; AudioBridge.setLooping(this.id, isLooping); }
		@Override public boolean isLooping() { return this.looping; }
		@Override public void setVolume(final float volume) { this.volume = volume; AudioBridge.setVolume(this.id, volume); }
		@Override public float getVolume() { return this.volume; }
		@Override public void setPan(final float pan, final float volume) { this.pan = pan; this.volume = volume; AudioBridge.setVolume(this.id, volume); }
		@Override public void setPosition(final float position) { this.position = position; }
		@Override public float getPosition() { return this.position; }
		@Override public void dispose() { AudioBridge.stop(this.id); }
		@Override public void setOnCompletionListener(final OnCompletionListener listener) { /* not wired through bridge yet */ }
	}

	private static final class SilentMusic implements Music {
		private boolean playing;
		private boolean looping;
		private float volume = 1f;
		private float position;

		@Override public void play() { this.playing = true; }
		@Override public void pause() { this.playing = false; }
		@Override public void stop() { this.playing = false; this.position = 0f; }
		@Override public boolean isPlaying() { return this.playing; }
		@Override public void setLooping(final boolean isLooping) { this.looping = isLooping; }
		@Override public boolean isLooping() { return this.looping; }
		@Override public void setVolume(final float volume) { this.volume = volume; }
		@Override public float getVolume() { return this.volume; }
		@Override public void setPan(final float pan, final float volume) { this.volume = volume; }
		@Override public void setPosition(final float position) { this.position = position; }
		@Override public float getPosition() { return this.position; }
		@Override public void dispose() { }
		@Override public void setOnCompletionListener(final OnCompletionListener listener) { }
	}
}
