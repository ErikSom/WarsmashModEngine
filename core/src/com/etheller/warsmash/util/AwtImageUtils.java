package com.etheller.warsmash.util;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.util.ImageUtils.DecodedImage;
import com.etheller.warsmash.viewer5.handlers.tga.TgaFile;

/**
 * AWT-backed image utilities. Everything in this file assumes the desktop
 * {@code java.awt.*} classes are available; it is <b>not</b> safe to reference
 * this class from code reachable in the TeaVM web build (no AWT in classlib).
 *
 * <p>The web build installs a different {@link ImageUtils.TextureDecoder}
 * via {@link ImageUtils#textureDecoder} that decodes BLPs directly to a
 * libGDX {@link Pixmap}; desktop uses {@link Decoder} below.
 */
public final class AwtImageUtils {
	private static final int BYTES_PER_PIXEL = 4;

	private AwtImageUtils() {
	}

	/** Desktop-backing {@link ImageUtils.TextureDecoder}. Registered via
	 *  {@link ImageUtils#textureDecoder} by the desktop launcher. */
	public static final ImageUtils.TextureDecoder DECODER = new ImageUtils.TextureDecoder() {
		@Override
		public Texture decode(final byte[] bytes, final boolean sRGBFix) {
			try (final ByteArrayInputStreamCompat in = new ByteArrayInputStreamCompat(bytes)) {
				final BufferedImage img = ImageIO.read(in);
				return (img == null) ? null : getTexture(img, sRGBFix);
			}
			catch (final IOException e) {
				return null;
			}
		}

		@Override
		public Pixmap decodeToPixmap(final byte[] bytes) {
			try (final ByteArrayInputStreamCompat in = new ByteArrayInputStreamCompat(bytes)) {
				final BufferedImage img = ImageIO.read(in);
				return (img == null) ? null : bufferedImageToPixmap(img);
			}
			catch (final IOException e) {
				return null;
			}
		}

		@Override
		public Texture getAnyExtensionTexture(final DataSource ds, final String path) {
			try {
				final AnyExtensionImage imageInfo = getAnyExtensionImageFixRGB(ds, path, "texture");
				final BufferedImage image = imageInfo.getImageData();
				return (image == null) ? null : getTexture(image, imageInfo.isNeedsSRGBFix());
			}
			catch (final IOException e) {
				return null;
			}
		}

		@Override
		public DecodedImage getAnyExtensionImageData(final DataSource dataSource, final String path) {
			try {
				final AnyExtensionImage imageInfo = getAnyExtensionImageFixRGB(dataSource, path, "texture");
				if (imageInfo.getImageData() == null) {
					return null;
				}
				return new DecodedImage(imageInfo.isNeedsSRGBFix(), bufferedImageToRgbaImage(imageInfo.getImageData()),
						bufferedImageToRgbaImage(imageInfo.getRGBCorrectImageData()));
			}
			catch (final IOException e) {
				return null;
			}
		}
	};

	/** BufferedImage → RGBA Pixmap (pixel loop). */
	public static Pixmap bufferedImageToPixmap(final BufferedImage image) {
		final int w = image.getWidth();
		final int h = image.getHeight();
		final int[] pixels = new int[w * h];
		image.getRGB(0, 0, w, h, pixels, 0, w);
		final Pixmap pm = new Pixmap(w, h, Format.RGBA8888);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				final int argb = pixels[(y * w) + x];
				pm.drawPixel(x, y, (argb << 8) | (argb >>> 24));
			}
		}
		return pm;
	}

	public static RgbaImage bufferedImageToRgbaImage(final BufferedImage image) {
		return RgbaImage.fromPixmap(bufferedImageToPixmap(image));
	}

	public static AnyExtensionImage getAnyExtensionImageFixRGB(final DataSource dataSource, final String path,
			final String errorType) throws IOException {
		if (path.toLowerCase().endsWith(".blp")) {
			try (InputStream stream = dataSource.getResourceAsStream(path)) {
				if (stream == null) {
					final String tgaPath = path.substring(0, path.length() - 4) + ".tga";
					try (final InputStream tgaStream = dataSource.getResourceAsStream(tgaPath)) {
						if (tgaStream != null) {
							final BufferedImage tgaData = TgaFile.readTGA(tgaPath, tgaStream);
							return new AnyExtensionImage(false, tgaData);
						}
						final String ddsPath = path.substring(0, path.length() - 4) + ".dds";
						try (final InputStream ddsStream = dataSource.getResourceAsStream(ddsPath)) {
							if (ddsStream != null) {
								final BufferedImage image = ImageIO.read(ddsStream);
								return new AnyExtensionImage(false, image);
							}
							throw new IllegalStateException("Missing " + errorType + ": " + path);
						}
					}
				}
				final BufferedImage image = ImageIO.read(stream);
				return new AnyExtensionImage(true, image);
			}
		}
		throw new IllegalStateException("Missing " + errorType + ": " + path);
	}

	public static final class AnyExtensionImage {
		private final boolean needsSRGBFix;
		private final BufferedImage imageData;

		public AnyExtensionImage(final boolean needsSRGBFix, final BufferedImage imageData) {
			this.needsSRGBFix = needsSRGBFix;
			this.imageData = imageData;
		}

		public BufferedImage getImageData() {
			return this.imageData;
		}

		public BufferedImage getRGBCorrectImageData() {
			return this.needsSRGBFix ? forceBufferedImagesRGB(this.imageData) : this.imageData;
		}

		public boolean isNeedsSRGBFix() {
			return this.needsSRGBFix;
		}
	}

	public static Texture getTexture(final BufferedImage image, final boolean sRGBFix) {
		final int[] pixels = new int[image.getWidth() * image.getHeight()];
		image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
		final Pixmap pixmap = sRGBFix ? new Pixmap(image.getWidth(), image.getHeight(), Format.RGBA8888) {
			@Override
			public int getGLInternalFormat() {
				return GL30.GL_SRGB8_ALPHA8;
			}
		} : new Pixmap(image.getWidth(), image.getHeight(), Format.RGBA8888);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				final int pixel = pixels[(y * image.getWidth()) + x];
				pixmap.drawPixel(x, y, (pixel << 8) | (pixel >>> 24));
			}
		}
		final Texture texture = new Texture(pixmap);
		texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
		return texture;
	}

	public static Texture getTextureNoColorCorrection(final BufferedImage image) {
		final int[] pixels = new int[image.getWidth() * image.getHeight()];
		image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
		final Pixmap pixmap = new Pixmap(image.getWidth(), image.getHeight(), Format.RGBA8888);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				final int pixel = pixels[(y * image.getWidth()) + x];
				pixmap.drawPixel(x, y, (pixel << 8) | (pixel >>> 24));
			}
		}
		final Texture texture = new Texture(pixmap);
		texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
		return texture;
	}

	public static Buffer getTextureBuffer(final BufferedImage image) {
		final int imageWidth = image.getWidth();
		final int imageHeight = image.getHeight();
		final int[] pixels = new int[imageWidth * imageHeight];
		image.getRGB(0, 0, imageWidth, imageHeight, pixels, 0, imageWidth);
		final ByteBuffer buffer = ByteBuffer.allocateDirect(imageWidth * imageHeight * BYTES_PER_PIXEL)
				.order(ByteOrder.nativeOrder());
		for (int y = 0; y < imageHeight; y++) {
			for (int x = 0; x < imageWidth; x++) {
				final int pixel = pixels[(y * imageWidth) + x];
				buffer.put((byte) ((pixel >> 16) & 0xFF));
				buffer.put((byte) ((pixel >> 8) & 0xFF));
				buffer.put((byte) (pixel & 0xFF));
				buffer.put((byte) ((pixel >> 24) & 0xFF));
			}
		}
		buffer.flip();
		return buffer;
	}

	public static BufferedImage forceBufferedImagesRGB(final BufferedImage in) {
		final ColorSpace inCS = in.getColorModel().getColorSpace();
		final ColorSpace sRGBCS = ColorSpace.getInstance(ColorSpace.CS_sRGB);
		if (inCS == sRGBCS) {
			return in;
		}
		if (inCS.getNumComponents() != sRGBCS.getNumComponents()) {
			throw new IllegalArgumentException("Input color space has different number of components from sRGB.");
		}
		final ColorModel lRGBModel = new ComponentColorModel(inCS, true, false, Transparency.TRANSLUCENT,
				DataBuffer.TYPE_BYTE);
		final ColorModel sRGBModel = new ComponentColorModel(sRGBCS, true, false, Transparency.TRANSLUCENT,
				DataBuffer.TYPE_BYTE);
		final BufferedImage lRGB = new BufferedImage(lRGBModel,
				lRGBModel.createCompatibleWritableRaster(in.getWidth(), in.getHeight()), false, null);
		for (int i = 0; i < in.getWidth(); i++) {
			for (int j = 0; j < in.getHeight(); j++) {
				lRGB.setRGB(i, j, in.getRGB(i, j));
			}
		}
		return new BufferedImage(sRGBModel, lRGB.getRaster(), false, null);
	}

	/** Trivial AutoCloseable {@code ByteArrayInputStream} — the stock
	 *  {@code java.io.ByteArrayInputStream} implements {@code AutoCloseable}
	 *  already; this is just a concrete-class alias to avoid an extra import. */
	private static final class ByteArrayInputStreamCompat extends java.io.ByteArrayInputStream {
		ByteArrayInputStreamCompat(final byte[] bytes) {
			super(bytes);
		}
	}
}
