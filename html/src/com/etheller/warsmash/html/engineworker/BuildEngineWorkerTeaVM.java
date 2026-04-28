package com.etheller.warsmash.html.engineworker;

import java.io.File;

import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemTextConsumer;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Builds {@link EngineWorkerMain} into
 * {@code build/dist/webapp/engine-worker.js} via TeaVMTool directly. Mirrors
 * {@code BuildWorkerTeaVM} — kept separate from the gdx-teavm libGDX build
 * because the engine worker has no DOM access (it lives in a
 * {@code DedicatedWorkerGlobalScope}). Once the worker grows real libGDX
 * bindings this build will pull in the engine codebase too; for the Phase 1
 * spike it's intentionally tiny.
 */
public final class BuildEngineWorkerTeaVM {
	private BuildEngineWorkerTeaVM() {
	}

	public static void main(final String[] args) throws Exception {
		final TeaVMTool tool = new TeaVMTool();
		tool.setTargetDirectory(new File("build/dist/webapp"));
		tool.setTargetFileName("engine-worker.js");
		tool.setMainClass(EngineWorkerMain.class.getName());
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
