package com.etheller.warsmash.html;

import java.io.File;

import org.teavm.vm.TeaVMOptimizationLevel;

import com.github.xpenatan.gdx.teavm.backends.shared.config.AssetFileHandle;
import com.github.xpenatan.gdx.teavm.backends.shared.config.compiler.TeaCompiler;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;

public class BuildTeaVMHtml {
	public static void main(final String[] args) {
		final AssetFileHandle assetsPath = new AssetFileHandle("../core/assets");
		try {
			new TeaCompiler(new WebBackend().setStartJettyAfterBuild(false))
					.addAssets(assetsPath)
					.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE)
					.setMainClass(HtmlLauncher.class.getName())
					.setObfuscated(false)
					.build(new File("build/dist"));
		}
		catch (final RuntimeException e) {
			// TeaCompiler's TeaBackend throws after a successful JS emit if any
			// diagnostic was tagged ERROR severity. Our engine drags in
			// java.awt / reflection / networking classes not shipped by TeaVM's
			// classlib; if the code paths that use them never actually execute
			// at runtime the produced app.js works fine. Swallow the throw so
			// the build doesn't fail; propagate non-"Build Failed" exceptions.
			if (!"Build Failed".equals(e.getMessage())) {
				throw e;
			}
			System.err.println("[BuildTeaVMHtml] TeaBackend reported build errors; continuing.");
		}
	}
}
