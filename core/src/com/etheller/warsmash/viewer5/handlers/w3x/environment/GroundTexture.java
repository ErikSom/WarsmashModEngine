package com.etheller.warsmash.viewer5.handlers.w3x.environment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.badlogic.gdx.graphics.GL30;
import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.units.Element;
import com.etheller.warsmash.util.ImageUtils;
import com.etheller.warsmash.util.ImageUtils.DecodedImage;
import com.etheller.warsmash.util.RgbaImage;

public class GroundTexture {
	public int id;
	private String tileId;
	private int tileSize;
	private boolean buildable;
	public boolean extended;

	public GroundTexture(final String path, final Element terrainTileInfo, final DataSource dataSource, final GL30 gl)
			throws IOException {
		if (terrainTileInfo != null) {
			this.tileId = terrainTileInfo.getId();
			final String buildableFieldValue = terrainTileInfo.getField("buildable");
			this.buildable = buildableFieldValue.isEmpty() ? false : Integer.parseInt(buildableFieldValue) == 1;
		}
		else {
			this.buildable = true;
		}
		if (dataSource.has(path)) {
			final DecodedImage imageInfo = ImageUtils.getAnyExtensionImageData(dataSource, path);
			if ((imageInfo == null) || (imageInfo.getImageData() == null)) {
				throw new IllegalStateException("Unsupported ground texture decode: " + path);
			}
			loadImage(path, gl, imageInfo.getImageData(), imageInfo.isNeedsSRGBFix());
		}
	}

	public boolean isBuildable() {
		return this.buildable;
	}

	private void loadImage(final String path, final GL30 gl, final RgbaImage image, final boolean sRGBFix) {
		if (image == null) {
			throw new IllegalStateException(this.tileId + ": Missing ground texture: " + path);
		}
		final ByteBuffer source = (ByteBuffer) image.getPixels();
		final int width = image.getWidth();
		final int height = image.getHeight();
		final int limit = source.limit();

		this.tileSize = (int) (height * 0.25);
		if (this.tileSize <= 0) {
			throw new IllegalStateException("Invalid ground texture tile size for " + path + " (" + width + "x"
					+ height + ")");
		}
		final int columns = Math.max(1, width / this.tileSize);
		final int rows = Math.max(1, height / this.tileSize);
		final int pageCount = Math.max(1, (columns + 3) / 4);
		this.extended = pageCount > 1;

		this.id = gl.glGenTexture();
		gl.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.id);
		gl.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA8, this.tileSize, this.tileSize,
				pageCount * 16, 0, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, null);
		gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR);
		gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
		gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);

		// Previously this relied on glPixelStorei(UNPACK_ROW_LENGTH, width) + buffer.position()
		// to extract each sub-tile directly out of the wide atlas. TeaVM's libgdx-teavm GL
		// bridge does not reliably honour UNPACK_ROW_LENGTH when reading from a direct
		// ByteBuffer, so every tile ended up containing mis-aligned bytes from neighbouring
		// tiles — cells then showed as distinct shade patches with visible boundaries.
		// Build a contiguous tile-sized buffer per tile and upload that with default stride.
		final int tileByteSize = this.tileSize * this.tileSize * 4;
		final ByteBuffer tileBuffer = ByteBuffer.allocateDirect(tileByteSize).order(ByteOrder.nativeOrder());
		for (int page = 0; page < pageCount; page++) {
			final int sourceColumns = Math.min(4, Math.max(0, columns - (page * 4)));
			for (int y = 0; y < Math.min(4, rows); y++) {
				for (int x = 0; x < sourceColumns; x++) {
					final int srcColumn = x + (page * 4);
					final int start = ((y * this.tileSize * width) + (srcColumn * this.tileSize)) * 4;
					final int span = (((this.tileSize - 1) * width) + this.tileSize) * 4;
					if ((start < 0) || ((start + span) > limit)) {
						System.err.println("Skipping malformed ground texture tile for " + path + " at page=" + page
								+ ", x=" + x + ", y=" + y + " (" + width + "x" + height + ", tileSize="
								+ this.tileSize + ", columns=" + columns + ", rows=" + rows + ", start=" + start
								+ ", span=" + span + ", limit=" + limit + ")");
						continue;
					}
					// Copy each tile row (tileSize*4 bytes) from the atlas at stride `width*4`
					// into the contiguous tile buffer at stride `tileSize*4`. Absolute per-byte
					// copy avoids any reliance on ByteBuffer bulk-transfer semantics that have
					// been unreliable on TeaVM.
					final int rowBytes = this.tileSize * 4;
					final int atlasRowStride = width * 4;
					for (int ty = 0; ty < this.tileSize; ty++) {
						final int srcOff = start + (ty * atlasRowStride);
						final int dstOff = ty * rowBytes;
						for (int k = 0; k < rowBytes; k++) {
							tileBuffer.put(dstOff + k, source.get(srcOff + k));
						}
					}
					tileBuffer.position(0);
					tileBuffer.limit(tileByteSize);
					gl.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, (page * 16) + (y * 4) + x, this.tileSize,
							this.tileSize, 1, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, tileBuffer);
				}
			}
		}
		gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
	}
}
