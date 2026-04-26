package com.etheller.warsmash.html;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.etheller.warsmash.util.Blp1Decoder;
import com.etheller.warsmash.util.Blp1Decoder.JpegMipData;
import com.etheller.warsmash.util.RgbaImage;
import com.etheller.warsmash.viewer5.handlers.blp.BlpAsyncUpgrader;
import com.etheller.warsmash.viewer5.handlers.blp.BlpTexture;

/**
 * Web-side {@link BlpAsyncUpgrader}: renders an instant blurry placeholder
 * sourced from the BLP's smallest mip, then queues an async full-resolution
 * decode that re-uploads the texture once it's ready. Skips the upfront
 * preload-time decode entirely so first-frame time isn't gated on canvas
 * decode tail latency.
 *
 * <p>The async pump is bounded by {@link PreloadTuning#decodeConcurrency} —
 * same pool size {@link ExtractedPreloader} used to use; reusing the limit
 * avoids piling up parallel canvas decodes that would just contend on the
 * main thread.
 *
 * <p>Multiple {@link BlpTexture} instances can ask for the same path at
 * different times (e.g. one per material that uses the same BLP). They're
 * accumulated in a per-path waiter list so a single decode satisfies all of
 * them on the same frame.
 */
final class WebBlpAsyncUpgrader implements BlpAsyncUpgrader {
	private final Deque<String> pending = new ArrayDeque<>();
	private final Map<String, PathState> states = new HashMap<>();
	private int inFlight;

	@Override
	public RgbaImage decodeThumbnailMip(final byte[] blpBytes) {
		if (!Blp1Decoder.isBlp1(blpBytes)) {
			return null;
		}
		if (Blp1Decoder.isJpeg(blpBytes)) {
			final JpegMipData thumbMip = Blp1Decoder.extractThumbnailJpegMipData(blpBytes);
			if (thumbMip == null) {
				return null;
			}
			final byte[] rgba = BrowserImageBridge.decodeJpegBlpMipToRgbaSync(thumbMip);
			if (rgba == null) {
				return null;
			}
			final ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length);
			for (int i = 0; i < rgba.length; i++) {
				buf.put(i, rgba[i]);
			}
			buf.position(0);
			return new RgbaImage(thumbMip.getWidth(), thumbMip.getHeight(), buf);
		}
		// Palette BLP: the palette decoder is already sync and cheap even at
		// mip 0 (no JPEG entropy decode required), so just decode the full
		// resolution directly. No async upgrade needed — scheduleAsyncUpgrade
		// also early-returns for non-JPEG BLPs below.
		return Blp1Decoder.decodePaletteMip0RgbaImage(blpBytes);
	}

	@Override
	public void scheduleAsyncUpgrade(final BlpTexture texture, final String path, final byte[] blpBytes) {
		if ((texture == null) || (path == null) || (blpBytes == null)) {
			return;
		}
		// JPEG BLPs are the only case where the placeholder differs from
		// mip 0 quality enough to warrant an async upgrade — palette BLPs
		// were already mip 0 quality from the sync decode.
		if (!Blp1Decoder.isJpeg(blpBytes)) {
			return;
		}
		PathState state = this.states.get(path);
		if (state == null) {
			state = new PathState(blpBytes);
			this.states.put(path, state);
			this.pending.addLast(path);
		}
		state.waiters.add(new WeakReference<>(texture));
		pump();
	}

	private void pump() {
		final int limit = Math.max(1, PreloadTuning.decodeConcurrency);
		while ((this.inFlight < limit) && !this.pending.isEmpty()) {
			final String path = this.pending.pollFirst();
			final PathState state = this.states.get(path);
			if (state == null) {
				continue;
			}
			this.inFlight++;
			final JpegMipData mip0 = Blp1Decoder.extractJpegMip0Data(state.blpBytes);
			if (mip0 == null) {
				onDecodeComplete(path, null, 0, 0);
				continue;
			}
			BrowserImageBridge.decodeJpegBlpMipToRgba(mip0,
					(rgba, width, height) -> onDecodeComplete(path, rgba, width, height));
		}
	}

	private void onDecodeComplete(final String path, final byte[] rgba, final int width, final int height) {
		this.inFlight--;
		final PathState state = this.states.remove(path);
		if ((state != null) && (rgba != null) && (width > 0) && (height > 0)) {
			DecodedRgbaCache.put(path, width, height, rgba);
			for (final WeakReference<BlpTexture> ref : state.waiters) {
				final BlpTexture texture = ref.get();
				if (texture != null) {
					texture.applyAsyncUpgrade(rgba, width, height);
				}
			}
		}
		pump();
	}

	private static final class PathState {
		final byte[] blpBytes;
		final java.util.List<WeakReference<BlpTexture>> waiters = new java.util.ArrayList<>(1);

		PathState(final byte[] blpBytes) {
			this.blpBytes = blpBytes;
		}
	}
}
