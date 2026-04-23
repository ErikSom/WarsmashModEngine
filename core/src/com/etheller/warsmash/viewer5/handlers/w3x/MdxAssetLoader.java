package com.etheller.warsmash.viewer5.handlers.w3x;

import com.etheller.warsmash.util.RgbaImage;
import com.etheller.warsmash.viewer5.handlers.mdx.MdxModel;

public interface MdxAssetLoader {
	public MdxModel loadModelMdx(final String path);

	public RgbaImage loadPathingTexture(String pathingTexture);
}
