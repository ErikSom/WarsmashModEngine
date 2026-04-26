package com.etheller.warsmash.html;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.GL20;
import com.etheller.warsmash.viewer5.AudioContext;
import com.etheller.warsmash.viewer5.AudioContext.Listener;
import com.etheller.warsmash.viewer5.AudioDestination;
import com.etheller.warsmash.viewer5.gl.ANGLEInstancedArrays;
import com.etheller.warsmash.viewer5.gl.AudioExtension;
import com.etheller.warsmash.viewer5.gl.DynamicShadowExtension;
import com.etheller.warsmash.viewer5.gl.Extensions;
import com.etheller.warsmash.viewer5.gl.WireframeExtension;

public final class WebExtensions {
	private WebExtensions() {
	}

	public static void install() {
		Extensions.angleInstancedArrays = new ANGLEInstancedArrays() {
			@Override
			public void glVertexAttribDivisorANGLE(final int index, final int divisor) {
				Gdx.gl30.glVertexAttribDivisor(index, divisor);
			}

			@Override
			public void glDrawArraysInstancedANGLE(final int mode, final int first, final int count,
					final int instanceCount) {
				Gdx.gl30.glDrawArraysInstanced(mode, first, count, instanceCount);
			}

			@Override
			public void glDrawElementsInstancedANGLE(final int mode, final int count, final int type,
					final int indicesOffset, final int instanceCount) {
				Gdx.gl30.glDrawElementsInstanced(mode, count, type, indicesOffset, instanceCount);
			}
		};
		Extensions.dynamicShadowExtension = new DynamicShadowExtension() {
			@Override
			public void glFramebufferTexture(final int target, final int attachment, final int texture,
					final int level) {
				// WebGL2 lacks glFramebufferTexture (layer-less). Closest equivalent is
				// glFramebufferTexture2D with TEXTURE_2D target; callers using cube or
				// layered textures will need a different path.
				Gdx.gl20.glFramebufferTexture2D(target, attachment, GL20.GL_TEXTURE_2D, texture, level);
			}

			@Override
			public void glDrawBuffer(final int mode) {
				// WebGL2 has glDrawBuffers(int[]) but no single-arg glDrawBuffer. No-op
				// here — dynamic shadow pipeline may render incorrectly until ported.
			}
		};
		Extensions.wireframeExtension = new WireframeExtension() {
			@Override
			public void glPolygonMode(final int face, final int mode) {
				// WebGL has no glPolygonMode. Wireframe rendering is unsupported in web.
			}
		};
		Extensions.audio = new AudioExtension() {
			@Override
			public AudioContext createContext(final boolean world) {
				// Listener.DO_NOTHING reports is3DSupported() == false, which
				// makes AudioBufferSource skip the distance-attenuation gate
				// and play every requested sound. Good default for now;
				// upgrade to a real 3D listener (camera-derived) later.
				return new AudioContext(Listener.DO_NOTHING, new AudioDestination() {
				});
			}

			@Override
			public float getDuration(final Sound sound) {
				// libGDX Sound has no duration API. The only consumer
				// today is UnitSound.playUnitResponse, which uses this to
				// debounce repeated "Yes Sir" / "Ready" plays — 1.5 s is
				// close enough to the typical unit-ack length to feel right.
				return 1.5f;
			}

			@Override
			public long play(final Sound buffer, final float volume, final float pitch, final float x, final float y,
					final float z, final boolean is3DSound, final float maxDistance, final float refDistance,
					final boolean looping) {
				if (buffer == null) {
					return -1L;
				}
				try {
					// pan = 0 (stereo center). Real positional audio
					// (camera-relative panning, distance attenuation) is a
					// follow-up — most WC3 sounds sit fine at center pan
					// and the distance cull already happens in the panner.
					if (looping) {
						return buffer.loop(volume, pitch, 0f);
					}
					return buffer.play(volume, pitch, 0f);
				}
				catch (final Throwable t) {
					return -1L;
				}
			}
		};
		// Classic OpenGL polygon-mode constants; not exposed in WebGL, kept only
		// so callsites that pass them into the (no-op) wireframe extension compile.
		Extensions.GL_LINE = 0x1B01;
		Extensions.GL_FILL = 0x1B02;
	}
}
