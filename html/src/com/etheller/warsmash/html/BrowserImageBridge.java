package com.etheller.warsmash.html;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

import com.etheller.warsmash.util.Blp1Decoder.JpegMipData;

/**
 * Decodes WC3 JPEG-compressed BLP mipmaps to raw RGBA on the web by invoking
 * {@code jpeg-js} (loaded from {@code scripts/jpeg-js.js} at page startup). The
 * library preserves all source JPEG components; Warsmash's WC3 JPEG BLPs are
 * 4-channel with components in BGRA order, so the alpha channel is recovered
 * directly from the JPEG instead of being guessed from RGB brightness.
 *
 * <p>Why not the native canvas path? {@code createImageBitmap} + 2D canvas
 * decodes WC3's 4-channel BLP JPEGs into 3-channel RGB, discarding the alpha
 * component. That's the root cause of the "see-through legs / red speckles on
 * tinted team-color regions" artifacts — the alpha mask in the 4th component
 * simply never reaches the shader.
 */
final class BrowserImageBridge {
	interface RgbaCallback {
		void onRgba(byte[] rgbaOrNull, int width, int height);
	}

	private BrowserImageBridge() {
	}

	/**
	 * Decode a JPEG-encoded BLP mip directly to raw RGBA8888 pixels (width *
	 * height * 4 bytes). Invokes {@code window['jpeg-js'].JpegImage} to parse
	 * the JPEG, then repacks the decoded components according to the BLP's
	 * conventions:
	 * <ul>
	 * <li>4 components → JPEG is BGRA; R = comp[2], G = comp[1], B = comp[0],
	 *     A = comp[3]</li>
	 * <li>3 components → JPEG is RGB (post YCbCr→RGB); alpha comes from the
	 *     BLP's separately-addressable alpha bytes if present, else 255</li>
	 * <li>1 component → grayscale; R = G = B = Y, alpha as above</li>
	 * </ul>
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

	/**
	 * Synchronous variant for the decode-on-demand thumbnail path. Always
	 * goes through the jpeg-js library (which decodes synchronously inside
	 * {@code decoder.parse}) — never through the async createImageBitmap
	 * fast path. Designed for tiny mips (1×1 to ~16×16); calling it on a
	 * full-resolution JPEG works but blocks the main thread for the decode
	 * duration. Returns null on failure (jpeg-js missing, malformed JPEG).
	 */
	static byte[] decodeJpegBlpMipToRgbaSync(final JpegMipData mipData) {
		if (mipData == null) {
			return null;
		}
		final int w = mipData.getWidth();
		final int h = mipData.getHeight();
		final Int8Array jpegBytes = toInt8Array(mipData.getJpegBytes());
		final byte[] alphaBytesArray = mipData.getAlphaBytes();
		final Int8Array alphaBytes = toInt8Array(alphaBytesArray == null ? new byte[0] : alphaBytesArray);
		final Int8Array result = decodeJpegBlpMipToRgbaSyncImpl(jpegBytes, alphaBytes, mipData.getAlphaDepth(),
				mipData.getPictureType(), w, h);
		return toByteArray(result);
	}

	@JSBody(params = { "jpegBytes", "alphaBytes", "alphaDepth", "pictureType", "width", "height" },
			script = ""
					+ "try {"
					+ "  if (!window['jpeg-js'] || !window['jpeg-js'].JpegImage) { return null; }"
					+ "  var jpeg = new Uint8Array(jpegBytes.buffer, jpegBytes.byteOffset, jpegBytes.byteLength);"
					+ "  var alpha = alphaBytes ? new Uint8Array(alphaBytes.buffer, alphaBytes.byteOffset, alphaBytes.byteLength) : new Uint8Array(0);"
					+ "  var JpegImage = window['jpeg-js'].JpegImage;"
					+ "  var decoder = new JpegImage();"
					+ "  decoder.opts = { useTArray: true, formatAsRGBA: true, colorTransform: false,"
					+ "                    tolerantDecoding: true, maxResolutionInMP: 100, maxMemoryUsageInMB: 64 };"
					+ "  JpegImage.resetMaxMemoryUsage(decoder.opts.maxMemoryUsageInMB * 1024 * 1024);"
					+ "  decoder.parse(jpeg);"
					+ "  var comps = decoder.components.length;"
					+ "  var compData = [];"
					+ "  for (var ci = 0; ci < comps; ci++) {"
					+ "    var c = decoder.components[ci];"
					+ "    compData.push({ lines: c.lines, scaleX: c.scaleX, scaleY: c.scaleY });"
					+ "  }"
					+ "  var sampleComp = function(ci, x, y) {"
					+ "    var cc = compData[ci];"
					+ "    var line = cc.lines[(y * cc.scaleY) | 0];"
					+ "    return line[(x * cc.scaleX) | 0];"
					+ "  };"
					+ "  var decodeAlpha = function(pixelIndex) {"
					+ "    var a = 255;"
					+ "    switch (alphaDepth) {"
					+ "      case 0: a = 255; break;"
					+ "      case 1: { var b = alpha[pixelIndex >> 3] || 0; a = (((b >>> (pixelIndex & 7)) & 1) === 0) ? 0 : 255; break; }"
					+ "      case 4: { var b = alpha[pixelIndex >> 1] || 0; var nib = ((pixelIndex & 1) === 0) ? (b & 15) : ((b >>> 4) & 15); a = nib * 17; break; }"
					+ "      case 8: a = alpha[pixelIndex] || 0; break;"
					+ "      default: a = 255; break;"
					+ "    }"
					+ "    if (pictureType === 5) { a = 255 - a; }"
					+ "    return a;"
					+ "  };"
					+ "  var hasSeparateAlpha = (alphaDepth > 0) && (alpha.length > 0);"
					+ "  var rgba = new Int8Array(width * height * 4);"
					+ "  var put = function(j, r, g, b, a) {"
					+ "    rgba[j]     = (r > 127) ? (r - 256) : r;"
					+ "    rgba[j + 1] = (g > 127) ? (g - 256) : g;"
					+ "    rgba[j + 2] = (b > 127) ? (b - 256) : b;"
					+ "    rgba[j + 3] = (a > 127) ? (a - 256) : a;"
					+ "  };"
					+ "  var x, y, j, r, g, b, a;"
					+ "  if (comps === 4) {"
					// 4-channel JPEG BLP: components are stored as BGRA.
					// 4th component IS the display alpha as stored —
					// comp[3]=0 means transparent, comp[3]=255 means opaque.
					// Do NOT apply the pictureType=5 inversion here: that
					// flag historically meant "the separate alpha stream
					// is stored inverted", and only applies when the BLP
					// has a separate alpha stream (alphaDepth > 0). For
					// 4-channel JPEGs the alpha is in-band and stored
					// straight. Inverting it broke command-card icons
					// (BTNAttack / BTNMove / BTNStop are JPEG BLPs with
					// pictureType=5 and comp[3] consistently 255 = opaque;
					// inverting yielded alpha=0 across the entire texture).
					+ "    for (y = 0; y < height; y++) {"
					+ "      for (x = 0; x < width; x++) {"
					+ "        b = sampleComp(0, x, y);"
					+ "        g = sampleComp(1, x, y);"
					+ "        r = sampleComp(2, x, y);"
					+ "        a = sampleComp(3, x, y);"
					+ "        put((y * width + x) * 4, r, g, b, a);"
					+ "      }"
					+ "    }"
					+ "  } else if (comps === 3) {"
					+ "    var clamp = function(v) { return v < 0 ? 0 : (v > 255 ? 255 : v | 0); };"
					+ "    for (y = 0; y < height; y++) {"
					+ "      for (x = 0; x < width; x++) {"
					+ "        var Y  = sampleComp(0, x, y);"
					+ "        var Cb = sampleComp(1, x, y);"
					+ "        var Cr = sampleComp(2, x, y);"
					+ "        r = clamp(Y + 1.402 * (Cr - 128));"
					+ "        g = clamp(Y - 0.3441363 * (Cb - 128) - 0.71413636 * (Cr - 128));"
					+ "        b = clamp(Y + 1.772 * (Cb - 128));"
					+ "        a = hasSeparateAlpha ? decodeAlpha(y * width + x) : 255;"
					+ "        put((y * width + x) * 4, r, g, b, a);"
					+ "      }"
					+ "    }"
					+ "  } else if (comps === 1) {"
					+ "    for (y = 0; y < height; y++) {"
					+ "      for (x = 0; x < width; x++) {"
					+ "        var yy = sampleComp(0, x, y);"
					+ "        a = hasSeparateAlpha ? decodeAlpha(y * width + x) : 255;"
					+ "        put((y * width + x) * 4, yy, yy, yy, a);"
					+ "      }"
					+ "    }"
					+ "  } else { return null; }"
					+ "  return rgba;"
					+ "} catch (e) { return null; }")
	private static native Int8Array decodeJpegBlpMipToRgbaSyncImpl(Int8Array jpegBytes, Int8Array alphaBytes,
			int alphaDepth, int pictureType, int width, int height);

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
					// Always use jpeg-js. Earlier this path had a fast branch for
					// alphaDepth==0 that decoded via createImageBitmap + 2D canvas
					// — but that path silently discards the JPEG's 4th component,
					// and WC3's "transparent" foliage / waterfall / water BLPs are
					// 4-channel JPEGs (BGRA) declared with alphaDepth=0 (the 0
					// just means "no separate alpha-byte stream"; the alpha is
					// already in the JPEG itself). Canvas-decoded textures came
					// back fully opaque, which renders as black around tree leaves
					// under blend mode. jpeg-js sees the 4 components and
					// preserves alpha.
					+ "try {"
					+ "  if (!window['jpeg-js'] || !window['jpeg-js'].JpegImage) {"
					+ "    err('jpeg-js library not loaded'); return;"
					+ "  }"
					+ "  var jpeg = new Uint8Array(jpegBytes.buffer, jpegBytes.byteOffset, jpegBytes.byteLength);"
					+ "  var alpha = alphaBytes ? new Uint8Array(alphaBytes.buffer, alphaBytes.byteOffset, alphaBytes.byteLength) : new Uint8Array(0);"
					+ "  var JpegImage = window['jpeg-js'].JpegImage;"
					+ "  var decoder = new JpegImage();"
					+ "  decoder.opts = { useTArray: true, formatAsRGBA: true, colorTransform: false,"
					+ "                    tolerantDecoding: true, maxResolutionInMP: 100, maxMemoryUsageInMB: 512 };"
					+ "  JpegImage.resetMaxMemoryUsage(decoder.opts.maxMemoryUsageInMB * 1024 * 1024);"
					+ "  decoder.parse(jpeg);"
					// Deliberately skip decoder.getData — for 4-component JPEGs without an
					// Adobe APP14 marker, getData throws 'Unsupported color mode (4 components)'.
					// WC3 BLP JPEGs fit that description exactly. Instead we read each
					// component's raw decoded samples straight out of components[i].lines
					// (post Huffman + IDCT + dequant, but pre any colour transform). For WC3
					// 4-channel BLPs those samples are the source BGRA bytes directly.
					+ "  var comps = decoder.components.length;"
					+ "  var decW = decoder.width;"
					+ "  var decH = decoder.height;"
					+ "  var ci;"
					+ "  var compData = [];"
					+ "  for (ci = 0; ci < comps; ci++) {"
					+ "    var c = decoder.components[ci];"
					+ "    compData.push({ lines: c.lines, scaleX: c.scaleX, scaleY: c.scaleY });"
					+ "  }"
					+ "  var sampleComp = function(ci, x, y) {"
					+ "    var cc = compData[ci];"
					+ "    var line = cc.lines[(y * cc.scaleY) | 0];"
					+ "    return line[(x * cc.scaleX) | 0];"
					+ "  };"
					+ "  var decodeAlpha = function(pixelIndex) {"
					+ "    var a = 255;"
					+ "    switch (alphaDepth) {"
					+ "      case 0: a = 255; break;"
					+ "      case 1: { var b = alpha[pixelIndex >> 3] || 0; a = (((b >>> (pixelIndex & 7)) & 1) === 0) ? 0 : 255; break; }"
					+ "      case 4: { var b = alpha[pixelIndex >> 1] || 0; var nib = ((pixelIndex & 1) === 0) ? (b & 15) : ((b >>> 4) & 15); a = nib * 17; break; }"
					+ "      case 8: a = alpha[pixelIndex] || 0; break;"
					+ "      default: a = 255; break;"
					+ "    }"
					+ "    if (pictureType === 5) { a = 255 - a; }"
					+ "    return a;"
					+ "  };"
					+ "  var hasSeparateAlpha = (alphaDepth > 0) && (alpha.length > 0);"
					+ "  var npix = width * height;"
					+ "  var rgba = new Int8Array(npix * 4);"
					+ "  var putRgba = function(j, r, g, b, a) {"
					+ "    rgba[j]     = (r > 127) ? (r - 256) : r;"
					+ "    rgba[j + 1] = (g > 127) ? (g - 256) : g;"
					+ "    rgba[j + 2] = (b > 127) ? (b - 256) : b;"
					+ "    rgba[j + 3] = (a > 127) ? (a - 256) : a;"
					+ "  };"
					+ "  var x, y, j, r, g, b, a;"
					+ "  if (comps === 4) {"
					// 4-channel JPEG BLP: BGRA, comp[3] is the in-band display
					// alpha (255 = opaque). Do NOT apply pictureType=5 inversion
					// — see sync decoder above for the reasoning.
					+ "    for (y = 0; y < height; y++) {"
					+ "      for (x = 0; x < width; x++) {"
					+ "        b = sampleComp(0, x, y);"
					+ "        g = sampleComp(1, x, y);"
					+ "        r = sampleComp(2, x, y);"
					+ "        a = sampleComp(3, x, y);"
					+ "        putRgba((y * width + x) * 4, r, g, b, a);"
					+ "      }"
					+ "    }"
					+ "  } else if (comps === 3) {"
					// 3-component JPEG (no separate alpha in the codestream). Apply the
					// standard YCbCr → RGB transform ourselves, then pull alpha from the
					// BLP's separate alpha stream if present, else 255.
					+ "    var clamp = function(v) { return v < 0 ? 0 : (v > 255 ? 255 : v | 0); };"
					+ "    for (y = 0; y < height; y++) {"
					+ "      for (x = 0; x < width; x++) {"
					+ "        var Y  = sampleComp(0, x, y);"
					+ "        var Cb = sampleComp(1, x, y);"
					+ "        var Cr = sampleComp(2, x, y);"
					+ "        r = clamp(Y + 1.402 * (Cr - 128));"
					+ "        g = clamp(Y - 0.3441363 * (Cb - 128) - 0.71413636 * (Cr - 128));"
					+ "        b = clamp(Y + 1.772 * (Cb - 128));"
					+ "        a = hasSeparateAlpha ? decodeAlpha(y * width + x) : 255;"
					+ "        putRgba((y * width + x) * 4, r, g, b, a);"
					+ "      }"
					+ "    }"
					+ "  } else if (comps === 1) {"
					+ "    for (y = 0; y < height; y++) {"
					+ "      for (x = 0; x < width; x++) {"
					+ "        var yy = sampleComp(0, x, y);"
					+ "        a = hasSeparateAlpha ? decodeAlpha(y * width + x) : 255;"
					+ "        putRgba((y * width + x) * 4, yy, yy, yy, a);"
					+ "      }"
					+ "    }"
					+ "  } else {"
					+ "    err('unsupported JPEG component count: ' + comps); return;"
					+ "  }"
					+ "  ok(rgba);"
					+ "} catch (e) {"
					+ "  err(e && e.message ? e.message : String(e));"
					+ "}")
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
