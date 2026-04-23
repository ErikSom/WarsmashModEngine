package com.etheller.warsmash.html;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

import com.etheller.warsmash.util.Blp1Decoder.JpegMipData;

final class BrowserImageBridge {
	interface BytesCallback {
		void onBytes(byte[] bytesOrNull);
	}

	interface RgbaCallback {
		void onRgba(byte[] rgbaOrNull, int width, int height);
	}

	private BrowserImageBridge() {
	}

	/**
	 * Decode a JPEG-encoded BLP mip directly to raw RGBA8888 pixels (width * height * 4 bytes).
	 * This avoids the PNG round-trip + {@code new Pixmap(byte[])} path, which silently produces
	 * zero-filled textures on TeaVM.
	 */
	static void decodeJpegBlpMipToRgba(final JpegMipData mipData, final RgbaCallback cb) {
		if ((mipData == null) || (cb == null)) {
			return;
		}
		final int w = mipData.getWidth();
		final int h = mipData.getHeight();
		final Int8Array jpegBytes = toInt8Array(mipData.getJpegBytes());
		final byte[] alphaBytesArray = mipData.getAlphaBytes();
		final Int8Array alphaBytes = toInt8Array(alphaBytesArray == null ? new byte[0] : alphaBytesArray);
		decodeJpegBlpMipToRgbaImpl(jpegBytes, alphaBytes, mipData.getAlphaDepth(), mipData.getPictureType(), w, h,
				value -> cb.onRgba(toByteArray(value), w, h),
				err -> {
					System.err.println("JPEG BLP web RGBA decode failed: " + err);
					cb.onRgba(null, 0, 0);
				});
	}

	static void decodeJpegBlpMipToPng(final JpegMipData mipData, final BytesCallback cb) {
		if ((mipData == null) || (cb == null)) {
			return;
		}
		final Int8Array jpegBytes = toInt8Array(mipData.getJpegBytes());
		final byte[] alphaBytesArray = mipData.getAlphaBytes();
		final Int8Array alphaBytes = toInt8Array(alphaBytesArray == null ? new byte[0] : alphaBytesArray);
		decodeJpegBlpMipToPngImpl(jpegBytes, alphaBytes, mipData.getAlphaDepth(), mipData.getPictureType(),
				mipData.getWidth(), mipData.getHeight(), value -> cb.onBytes(toByteArray(value)),
				err -> {
					System.err.println("JPEG BLP web decode failed: " + err);
					cb.onBytes(null);
				});
	}

	private static Int8Array toInt8Array(final byte[] bytes) {
		final Int8Array arr = Int8Array.create(bytes.length);
		for (int i = 0; i < bytes.length; i++) {
			arr.set(i, bytes[i]);
		}
		return arr;
	}

	private static byte[] toByteArray(final Int8Array arr) {
		if (arr == null) {
			return null;
		}
		final int len = arr.getLength();
		final byte[] out = new byte[len];
		for (int i = 0; i < len; i++) {
			out[i] = arr.get(i);
		}
		return out;
	}

	@JSBody(params = { "jpegBytes", "alphaBytes", "alphaDepth", "pictureType", "width", "height", "ok", "err" },
			script = ""
					+ "var jpeg = new Uint8Array(jpegBytes.buffer, jpegBytes.byteOffset, jpegBytes.byteLength);"
					+ "var alpha = alphaBytes ? new Uint8Array(alphaBytes.buffer, alphaBytes.byteOffset, alphaBytes.byteLength) : new Uint8Array(0);"
					+ "var decodeAlpha = function(pixelIndex) {"
					+ "  var a = 255;"
					+ "  switch (alphaDepth) {"
					+ "    case 0: a = 255; break;"
					+ "    case 1: { var b = alpha[pixelIndex >> 3] || 0; a = (((b >>> (pixelIndex & 7)) & 1) === 0) ? 0 : 255; break; }"
					+ "    case 4: { var b = alpha[pixelIndex >> 1] || 0; var nib = ((pixelIndex & 1) === 0) ? (b & 15) : ((b >>> 4) & 15); a = nib * 17; break; }"
					+ "    case 8: a = alpha[pixelIndex] || 0; break;"
					+ "    default: a = 255; break;"
					+ "  }"
					+ "  if (pictureType === 5) { a = 255 - a; }"
					+ "  return a;"
					+ "};"
					+ "var canvas = (typeof OffscreenCanvas !== 'undefined')"
					+ "  ? new OffscreenCanvas(width, height)"
					+ "  : (function(){ var c = document.createElement('canvas'); c.width = width; c.height = height; return c; })();"
					+ "var makePngBlob = function(c) {"
					+ "  if (c.convertToBlob) { return c.convertToBlob({ type: 'image/png' }); }"
					+ "  return new Promise(function(resolve, reject) {"
					+ "    c.toBlob(function(blob) { if (blob) { resolve(blob); } else { reject(new Error('toBlob failed')); } }, 'image/png');"
					+ "  });"
					+ "};"
					+ "createImageBitmap(new Blob([jpeg], { type: 'image/jpeg' }))"
					+ "  .then(function(bitmap) {"
					+ "    var ctx = canvas.getContext('2d', { willReadFrequently: true });"
					+ "    if (!ctx) { throw new Error('2d context unavailable'); }"
					+ "    ctx.drawImage(bitmap, 0, 0, width, height);"
					+ "    if (bitmap.close) { bitmap.close(); }"
					+ "    var image = ctx.getImageData(0, 0, width, height);"
					+ "    var data = image.data;"
					+ "    for (var i = 0, p = 0; i < data.length; i += 4, p++) {"
					+ "      var r = data[i];"
					+ "      data[i] = data[i + 2];"
					+ "      data[i + 2] = r;"
					+ "      if (alphaDepth > 0 && alpha.length > 0) {"
					+ "        data[i + 3] = decodeAlpha(p);"
					+ "      }"
					+ "    }"
					+ "    ctx.putImageData(image, 0, 0);"
					+ "    return makePngBlob(canvas);"
					+ "  })"
					+ "  .then(function(blob) { return blob.arrayBuffer(); })"
					+ "  .then(function(buffer) { ok(new Int8Array(buffer)); })"
					+ "  .catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void decodeJpegBlpMipToPngImpl(Int8Array jpegBytes, Int8Array alphaBytes, int alphaDepth,
			int pictureType, int width, int height, Int8ArrayCallback ok, StringCallback err);

	@JSBody(params = { "jpegBytes", "alphaBytes", "alphaDepth", "pictureType", "width", "height", "ok", "err" },
			script = ""
					+ "var jpeg = new Uint8Array(jpegBytes.buffer, jpegBytes.byteOffset, jpegBytes.byteLength);"
					+ "var alpha = alphaBytes ? new Uint8Array(alphaBytes.buffer, alphaBytes.byteOffset, alphaBytes.byteLength) : new Uint8Array(0);"
					+ "var decodeAlpha = function(pixelIndex) {"
					+ "  var a = 255;"
					+ "  switch (alphaDepth) {"
					+ "    case 0: a = 255; break;"
					+ "    case 1: { var b = alpha[pixelIndex >> 3] || 0; a = (((b >>> (pixelIndex & 7)) & 1) === 0) ? 0 : 255; break; }"
					+ "    case 4: { var b = alpha[pixelIndex >> 1] || 0; var nib = ((pixelIndex & 1) === 0) ? (b & 15) : ((b >>> 4) & 15); a = nib * 17; break; }"
					+ "    case 8: a = alpha[pixelIndex] || 0; break;"
					+ "    default: a = 255; break;"
					+ "  }"
					+ "  if (pictureType === 5) { a = 255 - a; }"
					+ "  return a;"
					+ "};"
					+ "var canvas = (typeof OffscreenCanvas !== 'undefined')"
					+ "  ? new OffscreenCanvas(width, height)"
					+ "  : (function(){ var c = document.createElement('canvas'); c.width = width; c.height = height; return c; })();"
					+ "createImageBitmap(new Blob([jpeg], { type: 'image/jpeg' }))"
					+ "  .then(function(bitmap) {"
					+ "    var ctx = canvas.getContext('2d', { willReadFrequently: true });"
					+ "    if (!ctx) { throw new Error('2d context unavailable'); }"
					+ "    ctx.drawImage(bitmap, 0, 0, width, height);"
					+ "    var image = ctx.getImageData(0, 0, width, height);"
					+ "    var data = image.data;"
					+ "    if (width === 256 && height === 256 && jpeg.length > 0) {"
					+ "      var midR = data[(128*256+128)*4];"
					+ "      var cornerR = data[0];"
					+ "      if (midR === 0 && cornerR === 0) {"
					+ "        var nz = 0;"
					+ "        for (var k = 0; k < data.length; k += 4) { if (data[k] !== 0) { nz++; if (nz > 4) break; } }"
					+ "        console.log('[js] jpeg->canvas: bitmap=' + bitmap.width + 'x' + bitmap.height"
					+ "          + ' jpegLen=' + jpeg.length + ' alphaDepth=' + alphaDepth"
					+ "          + ' nonZeroR(0..?)=' + nz + ' sample[0..7]=' + data[0] + ',' + data[1] + ',' + data[2] + ',' + data[3] + ',' + data[4] + ',' + data[5] + ',' + data[6] + ',' + data[7]);"
					+ "      }"
					+ "    }"
					+ "    if (bitmap.close) { bitmap.close(); }"
					// Swap R/B: canvas getImageData gives us native RGBA, but the engine's
					// GL upload path expects bytes in BGRA order (this matches both libgdx's
					// native Pixmap layout and what the old PNG-path variant of this code did
					// before it was replaced). Without the swap, tree bark shows up as blue
					// instead of brown.
					// Alpha handling:
					//   - If the BLP carries a separately-addressable alpha channel
					//     (alphaDepth>0 AND trailing alpha bytes exist), use it.
					//   - Otherwise (alphaDepth>0 but alpha bytes missing — common for WC3
					//     JPEG BLPs where alpha was baked into the JPEG's 4th channel and
					//     browsers drop it during decode) derive alpha from RGB brightness:
					//     pure-black pixels in WC3 tree/foliage BLPs are the convention for
					//     "transparent", so alpha = max(R,G,B) gives the correct cutout.
					//   - alphaDepth=0 means opaque; leave alpha at 255.
					+ "    var hasExplicitAlpha = alphaDepth > 0 && alpha.length > 0;"
					+ "    var deriveAlphaFromRgb = alphaDepth > 0 && alpha.length === 0;"
					+ "    for (var i = 0, p = 0; i < data.length; i += 4, p++) {"
					+ "      var r = data[i]; data[i] = data[i + 2]; data[i + 2] = r;"
					+ "      if (hasExplicitAlpha) {"
					+ "        data[i + 3] = decodeAlpha(p);"
					+ "      } else if (deriveAlphaFromRgb) {"
					// Threshold-based cutout: WC3 JPEG BLPs encode transparency as pure
					// black RGB. After JPEG compression near-zero values bleed up a bit,
					// so we use a small threshold (8) rather than strict ==0. Anything
					// above the threshold is fully opaque. This gives the binary-mask
					// behaviour the engine expects for foliage/bark cutouts without
					// accidentally making dark-but-real pixels translucent.
					+ "        var rr = data[i], gg = data[i + 1], bb = data[i + 2];"
					+ "        var mx = rr > gg ? (rr > bb ? rr : bb) : (gg > bb ? gg : bb);"
					+ "        data[i + 3] = mx < 8 ? 0 : 255;"
					+ "      }"
					+ "    }"
					+ "    var out = new Int8Array(data.length);"
					+ "    for (var j = 0; j < data.length; j++) { out[j] = data[j] > 127 ? data[j] - 256 : data[j]; }"
					+ "    ok(out);"
					+ "  })"
					+ "  .catch(function(e) { err(e && e.message ? e.message : String(e)); });")
	private static native void decodeJpegBlpMipToRgbaImpl(Int8Array jpegBytes, Int8Array alphaBytes, int alphaDepth,
			int pictureType, int width, int height, Int8ArrayCallback ok, StringCallback err);

	@JSFunctor
	interface Int8ArrayCallback extends JSObject {
		void accept(Int8Array value);
	}

	@JSFunctor
	interface StringCallback extends JSObject {
		void accept(String value);
	}
}
