package com.etheller.interpreter.ast.scope.trigger;

public abstract class RemovableTriggerEvent {
	public RemovableTriggerEvent(final Trigger t) {
		// JASS code occasionally passes a null trigger (e.g. map init code that
		// calls TriggerRegister*Event before its trigger global is assigned).
		// Real WC3 silently ignores those — match that behaviour rather than
		// NPE'ing inside the constructor and aborting map init.
		if (t != null) {
			t.addEvent(this);
		}
	}

	public abstract void remove();

//	RemovableTriggerEvent DO_NOTHING = new RemovableTriggerEvent() {
//		@Override
//		public void remove() {
//		}
//	};

	public static RemovableTriggerEvent doNothing(final Trigger trigger) {
		return new RemovableTriggerEvent(trigger) {
			@Override
			public void remove() {
			}
		};
	}
}
