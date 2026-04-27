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

public class ArithmeticLeftHandIntegerJassValueVisitor implements JassValueVisitor<JassValue> {
	public static final ArithmeticLeftHandIntegerJassValueVisitor INSTANCE = new ArithmeticLeftHandIntegerJassValueVisitor();
	private IntegerJassValue leftHand;
	private ArithmeticSign sign;

	public ArithmeticLeftHandIntegerJassValueVisitor reset(final IntegerJassValue leftHand, final ArithmeticSign sign) {
		this.leftHand = leftHand;
		this.sign = sign;
		return this;
	}

	@Override
	public JassValue accept(final BooleanJassValue value) {
		throw new UnsupportedOperationException("Cannot perform integer arithmetic on boolean");
	}

	@Override
	public JassValue accept(final IntegerJassValue value) {
		return this.sign.apply(this.leftHand, value);
	}

	@Override
	public JassValue accept(final RealJassValue value) {
		return this.sign.apply(this.leftHand, value);
	}

	@Override
	public JassValue accept(final StringJassValue value) {
		return this.sign.apply(this.leftHand.toString(), value.getValue());
	}

	@Override
	public JassValue accept(final CodeJassValue value) {
		throw new UnsupportedOperationException("Cannot perform arithmetic on code");
	}

	@Override
	public JassValue accept(final ArrayJassValue value) {
		throw new UnsupportedOperationException("Cannot perform arithmetic on array");
	}

	@Override
	public JassValue accept(final HandleJassValue value) {
		// Mirror of {@link ArithmeticLeftHandHandleJassValueVisitor#accept(IntegerJassValue)}
		// for scripts that write the comparison the other way around
		// ({@code 1 == GetAiPlayer()} instead of {@code GetAiPlayer() == 1}).
		// {@code ==} → false, {@code !=} → true; other operators throw.
		if (this.sign == ArithmeticSigns.EQUALS) {
			return BooleanJassValue.of(false);
		}
		if (this.sign == ArithmeticSigns.NOT_EQUALS) {
			return BooleanJassValue.of(true);
		}
		throw new UnsupportedOperationException("Cannot perform integer arithmetic on handle: lhs="
				+ (this.leftHand == null ? "<null>" : this.leftHand.getValue()) + " op=" + this.sign + " rhs=handle<"
				+ (value.getType() == null ? "<no-type>" : value.getType().getName()) + ">");
	}

	@Override
	public JassValue accept(final DummyJassValue value) {
		return value;
	}

	@Override
	public JassValue accept(final StructJassValue value) {
		throw new UnsupportedOperationException("Cannot perform arithmetic on struct");
	}

	@Override
	public JassValue accept(final StaticStructTypeJassValue value) {
		throw new UnsupportedOperationException("Cannot perform arithmetic on struct type");
	}

}
