package com.etheller.warsmash.html;

import java.io.File;

import org.teavm.vm.TeaVMOptimizationLevel;

import com.github.xpenatan.gdx.teavm.backends.shared.config.AssetFileHandle;
import com.github.xpenatan.gdx.teavm.backends.shared.config.compiler.TeaCompiler;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;

public class BuildTeaVMHtml {
	public static void main(final String[] args) {
		final AssetFileHandle assetsPath = new AssetFileHandle("../core/assets");
		final AssetFileHandle resourcesPath = new AssetFileHandle("../resources");
		new TeaCompiler(new WebBackend().setStartJettyAfterBuild(false))
				.addAssets(assetsPath)
				.addAssets(resourcesPath)
				.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE)
				.setMainClass(HtmlLauncher.class.getName())
				.setObfuscated(false)
				.build(new File("build/dist"));
	}
}
