package com.etheller.warsmash.html.worker;

import java.io.File;

import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemTextConsumer;
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
		for (final Problem p : tool.getProblemProvider().getProblems()) {
			final StringBuilder sb = new StringBuilder();
			final ProblemTextConsumer sink = new ProblemTextConsumer() {
				@Override public void append(final String s) { sb.append(s); }
				@Override public void appendClass(final String c) { sb.append(c); }
				@Override public void appendMethod(final org.teavm.model.MethodReference m) { sb.append(m); }
				@Override public void appendField(final org.teavm.model.FieldReference f) { sb.append(f); }
				@Override public void appendType(final org.teavm.model.ValueType t) { sb.append(t); }
				@Override public void appendLocation(final org.teavm.model.TextLocation l) { sb.append(l); }
			};
			p.render(sink);
			System.err.println("TeaVM [" + p.getSeverity() + "] " + p.getLocation() + ": " + sb);
		}
	}
}
