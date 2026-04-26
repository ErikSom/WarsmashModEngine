package com.etheller.warsmash.viewer5.handlers.blp;

import com.etheller.warsmash.util.RgbaImage;

/**
 * Pluggable strategy for decode-on-demand BLP loading. When the engine binds
 * a JPEG-compressed BLP and the full-res RGBA isn't already cached, the
 * registered upgrader is asked for a sync-decoded thumbnail mip (so the
 * texture can be uploaded immediately) and asked to schedule an async
 * full-resolution decode that replaces the texture data on the next frame.
 *
 * <p>The desktop build doesn't register an upgrader — it has a synchronous
 * JPEG codec available via libGDX's stb_image and {@link
 * com.etheller.warsmash.util.Blp1Decoder#decodeMip0(byte[])} works directly.
 * The web build registers an implementation backed by
 * {@code BrowserImageBridge.decodeJpegBlpMipToRgba} (jpeg-js sync) for the
 * thumbnail and the async canvas / jpeg-js path for the full upgrade.
 */
public interface BlpAsyncUpgrader {

	/**
	 * Decode a small "thumbnail" mip synchronously so {@link BlpTexture#load}
	 * has something to upload immediately. The smallest mip whose larger
	 * dimension is ≥ 4 is preferred — small enough to decode in microseconds,
	 * big enough to look like a blurry version of the texture rather than a
	 * flat color block. Returns null if the BLP has no usable mip chain.
	 */
	RgbaImage decodeThumbnailMip(byte[] blpBytes);

	/**
	 * Schedule the async full-resolution decode + texture refresh. Implementers
	 * should hold a weak reference to the texture (so disposed textures can
	 * be skipped), decode mip 0, populate any RGBA cache they maintain, and
	 * call {@link BlpTexture#applyAsyncUpgrade} on the texture.
	 */
	void scheduleAsyncUpgrade(BlpTexture texture, String path, byte[] blpBytes);

	/** Single-slot registry. */
	BlpAsyncUpgrader[] REGISTRY = new BlpAsyncUpgrader[1];

	static void register(final BlpAsyncUpgrader upgrader) {
		REGISTRY[0] = upgrader;
	}

	static BlpAsyncUpgrader get() {
		return REGISTRY[0];
	}
}
