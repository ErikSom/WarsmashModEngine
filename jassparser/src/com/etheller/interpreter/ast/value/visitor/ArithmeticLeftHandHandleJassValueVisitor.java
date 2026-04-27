package com.etheller.interpreter.ast.value.visitor;

import com.etheller.interpreter.ast.expression.ArithmeticSign;
import com.etheller.interpreter.ast.expression.ArithmeticSigns;
import com.etheller.interpreter.ast.value.ArrayJassValue;
import com.etheller.interpreter.ast.value.BooleanJassValue;
import com.etheller.interpreter.ast.value.CodeJassValue;
import com.etheller.interpreter.ast.value.DummyJassValue;
import com.etheller.interpreter.ast.value.HandleJassValue;
import com.etheller.interpreter.ast.value.IntegerJassValue;
import com.etheller.interpreter.ast.value.JassValue;
import com.etheller.interpreter.ast.value.JassValueVisitor;
import com.etheller.interpreter.ast.value.RealJassValue;
import com.etheller.interpreter.ast.value.StaticStructTypeJassValue;
import com.etheller.interpreter.ast.value.StringJassValue;
import com.etheller.interpreter.ast.value.StructJassValue;

public class ArithmeticLeftHandHandleJassValueVisitor implements JassValueVisitor<JassValue> {
	public static final ArithmeticLeftHandHandleJassValueVisitor INSTANCE = new ArithmeticLeftHandHandleJassValueVisitor();
	private HandleJassValue leftHand;
	private ArithmeticSign sign;

	public ArithmeticLeftHandHandleJassValueVisitor reset(final HandleJassValue leftHand, final ArithmeticSign sign) {
		this.leftHand = leftHand;
		this.sign = sign;
		return this;
	}

	@Override
	public JassValue accept(final BooleanJassValue value) {
		throw new UnsupportedOperationException("Cannot perform handle comparison on boolean");
	}

	@Override
	public JassValue accept(final IntegerJassValue value) {
		// Some Blizzard scripts (notably stock {@code undead.ai}'s
		// "set trace_on = GetAiPlayer()==1" debug toggle) compare a handle
		// to an integer literal. WC3's interpreter tolerates this and
		// resolves it as never-equal — the dev presumably wanted the line
		// to evaluate {@code false} so trace stays off. Match that
		// behaviour for {@code ==} / {@code !=} so we don't crash on
		// type-loose comparisons. Other operators on these mixed types
		// have no sensible meaning and still throw with diagnostic info.
		if (this.sign == ArithmeticSigns.EQUALS) {
			return BooleanJassValue.of(false);
		}
		if (this.sign == ArithmeticSigns.NOT_EQUALS) {
			return BooleanJassValue.of(true);
		}
		throw new UnsupportedOperationException("Cannot perform handle arithmetic on integer: lhs="
				+ describeLeft() + " op=" + this.sign + " rhs=" + value.getValue());
	}

	private String describeLeft() {
		if (this.leftHand == null) {
			return "<null lhs>";
		}
		final Object java = this.leftHand.getJavaValue();
		final String typeName = (this.leftHand.getType() == null) ? "<no-type>" : this.leftHand.getType().getName();
		return "handle<" + typeName + ">(" + (java == null ? "null" : java.getClass().getSimpleName()) + ")";
	}

	@Override
	public JassValue accept(final RealJassValue value) {
		throw new UnsupportedOperationException("Cannot perform handle comparison on real");
	}

	@Override
	public JassValue accept(final StringJassValue value) {
		throw new UnsupportedOperationException("Cannot perform handle comparison on string");
	}

	@Override
	public JassValue accept(final CodeJassValue value) {
		throw new UnsupportedOperationException("Cannot perform handle comparison on code");
	}

	@Override
	public JassValue accept(final ArrayJassValue value) {
		throw new UnsupportedOperationException("Cannot perform handle comparison on array");
	}

	@Override
	public JassValue accept(final HandleJassValue value) {
		return this.sign.apply(this.leftHand, value);
	}

	@Override
	public JassValue accept(final DummyJassValue value) {
		return value;
	}

	@Override
	public JassValue accept(final StructJassValue value) {
		return this.sign.apply(this.leftHand, value);
	}

	@Override
	public JassValue accept(final StaticStructTypeJassValue value) {
		throw new UnsupportedOperationException("Cannot perform arithmetic on struct type");
	}
}
