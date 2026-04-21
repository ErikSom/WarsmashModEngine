package com.etheller.warsmash.html.worker;

import java.io.File;

import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Builds {@link WorkerMain} into {@code build/dist/webapp/worker.js} using
 * TeaVMTool directly — separate from the gdx-teavm libGDX build, since
 * the worker runs without libGDX and with no DOM access.
 */
public final class BuildWorkerTeaVM {
	private BuildWorkerTeaVM() {
	}

	public static void main(final String[] args) throws Exception {
		final TeaVMTool tool = new TeaVMTool();
		tool.setTargetDirectory(new File("build/dist/webapp"));
		tool.setTargetFileName("worker.js");
		tool.setMainClass(WorkerMain.class.getName());
		tool.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
		tool.setObfuscated(false);
		tool.setSourceMapsFileGenerated(false);
		tool.setDebugInformationGenerated(false);
		tool.generate();
		if (!tool.getProblemProvider().getProblems().isEmpty()) {
			tool.getProblemProvider().getProblems().forEach(p -> System.err.println("TeaVM: " + p.toString()));
		}
	}
}
