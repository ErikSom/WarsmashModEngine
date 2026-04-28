package com.etheller.interpreter.ast.function;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.etheller.interpreter.ast.debug.JassException;
import com.etheller.interpreter.ast.scope.GlobalScope;
import com.etheller.interpreter.ast.scope.TriggerExecutionScope;
import com.etheller.interpreter.ast.value.JassType;
import com.etheller.interpreter.ast.value.JassValue;
import com.etheller.interpreter.ast.value.visitor.JassTypeGettingValueVisitor;

public class NativeJassFunction {
	// Each unimplemented native is warned about exactly once per process. Without
	// this, JASS code that calls e.g. CachePlayerHeroData per player (or per tick)
	// floods the console; on the TeaVM/web build System.err routes to
	// console.error which Chrome decorates with a stack trace, and DevTools
	// freezes formatting them.
	private static final Set<String> WARNED_MISSING_NATIVES = new HashSet<>();

	private final List<JassParameter> parameters;
	private final JassType returnType;
	private final String name;
	private final JassFunction implementation;

	public NativeJassFunction(final List<JassParameter> parameters, final JassType returnType, final String name,
			final JassFunction impl) {
		this.parameters = parameters;
		this.returnType = returnType;
		this.name = name;
		this.implementation = impl;
	}

	public List<JassParameter> getParameters() {
		return this.parameters;
	}

	public JassType getReturnType() {
		return this.returnType;
	}

	public String getName() {
		return this.name;
	}

	public JassFunction getImplementation() {
		return this.implementation;
	}

	public final JassValue call(final List<JassValue> arguments, final GlobalScope globalScope,
			final TriggerExecutionScope triggerScope) {
		if (arguments.size() != this.parameters.size()) {
			throw new JassException(globalScope, "Invalid number of arguments passed to function: " + arguments.size()
					+ " != " + this.parameters.size(), null);
		}
		for (int i = 0; i < this.parameters.size(); i++) {
			final JassParameter parameter = this.parameters.get(i);
			final JassValue argument = arguments.get(i);
			if (!parameter.matchesType(argument)) {
				if ((parameter == null) || (argument == null)) {
					System.err.println(
							"We called some Jass function with incorrect argument types, and the types were null!!!");
					System.err.println("This is a temporary hack for tests and showcase programming solutions");
					return null;
				}
				System.err.println(
						parameter.getType() + " != " + argument.visit(JassTypeGettingValueVisitor.getInstance()));
				throw new JassException(globalScope,
						"Invalid type " + argument.visit(JassTypeGettingValueVisitor.getInstance()).getName()
								+ " for specified argument " + parameter.getType().getName(),
						null);
			}
		}
		if (!checkNativeExists()) {
			return this.returnType.getNullValue();
		}
		// JASS lets unset locals/globals reach native callsites as Java null
		// (see e.g. {@code TriggerRegisterTimerExpireEventBJ} firing before
		// the timer is created). Real WC3 tolerates that — the native sees a
		// type-correct null handle. Replace any Java-null arg with its
		// parameter type's null value so each native impl can handle the
		// missing input however it needs (return null event, no-op, etc.)
		// instead of crashing on an unguarded {@code arg.visit(...)}.
		for (int i = 0; i < arguments.size(); i++) {
			if (arguments.get(i) == null) {
				arguments.set(i, this.parameters.get(i).getType().getNullValue());
			}
		}
		try {
			return this.implementation.call(arguments, globalScope, triggerScope);
		}
		catch (final JassException e) {
			throw e;
		}
		catch (final Throwable e) {
			final Exception cause = (e instanceof Exception) ? (Exception) e : new RuntimeException(e);
			throw new JassException(globalScope,
					"Native '" + this.name + "' crashed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
					cause);
		}
	}

	private boolean checkNativeExists() {
		if (this.implementation == null) {
			if (WARNED_MISSING_NATIVES.add(this.name)) {
				System.out.println(
						"Call to native function that was declared but had no native implementation: " + this.name);
			}
			return false;
		}
		return true;
	}
}
