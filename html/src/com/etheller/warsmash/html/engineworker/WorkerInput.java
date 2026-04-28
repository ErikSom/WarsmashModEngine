package com.etheller.warsmash.html.engineworker;

import java.util.ArrayDeque;
import java.util.Deque;

import com.badlogic.gdx.AbstractInput;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.input.NativeInputConfiguration;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * Minimal libGDX {@link com.badlogic.gdx.Input} for the worker port.
 * Pointer/key events arrive from the main thread via postMessage and land
 * here through {@link #postPointer(String, int, int, int)} and
 * {@link #postKey(String, int, char)}; {@link #processEvents()} drains the
 * queue once per frame from the worker render loop, fanning out to
 * {@link InputProcessor} callbacks and updating poll-style state.
 *
 * <p>Most methods of {@code Input} (accelerometer, gyroscope, vibrator,
 * orientation, virtual keyboard) are stubs — workers don't have those
 * affordances and the engine doesn't expect them on web.
 */
final class WorkerInput extends AbstractInput {
	// Up to 5 buttons covers left/middle/right/back/forward — enough for our needs.
	private static final int BUTTON_COUNT = 5;

	private InputProcessor processor;
	private final Deque<Object> pendingEvents = new ArrayDeque<>();

	private int x, y, deltaX, deltaY;
	private final boolean[] buttonPressed = new boolean[BUTTON_COUNT];
	private final boolean[] buttonJustPressed = new boolean[BUTTON_COUNT];
	private boolean justTouched;
	private long currentEventTimeNanos;

	void postPointer(final String name, final int x, final int y, final int button) {
		synchronized (this.pendingEvents) {
			this.pendingEvents.addLast(new PointerEvent(name, x, y, button));
		}
	}

	void postKey(final String name, final int keycode, final char ch) {
		synchronized (this.pendingEvents) {
			this.pendingEvents.addLast(new KeyEvent(name, keycode, ch));
		}
	}

	void postScroll(final float dx, final float dy) {
		synchronized (this.pendingEvents) {
			this.pendingEvents.addLast(new ScrollEvent(dx, dy));
		}
	}

	/** Drain queued events into InputProcessor + state. Call once per frame. */
	void processEvents(final long nowNanos) {
		// Reset per-frame "just" state before applying the new batch.
		this.justTouched = false;
		for (int i = 0; i < this.buttonJustPressed.length; i++) this.buttonJustPressed[i] = false;
		for (int i = 0; i < this.justPressedKeys.length; i++) this.justPressedKeys[i] = false;
		this.keyJustPressed = false;
		this.deltaX = 0;
		this.deltaY = 0;

		this.currentEventTimeNanos = nowNanos;

		while (true) {
			final Object e;
			synchronized (this.pendingEvents) {
				e = this.pendingEvents.pollFirst();
			}
			if (e == null) {
				break;
			}
			if (e instanceof PointerEvent) {
				dispatchPointer((PointerEvent) e);
			}
			else if (e instanceof KeyEvent) {
				dispatchKey((KeyEvent) e);
			}
			else if (e instanceof ScrollEvent) {
				dispatchScroll((ScrollEvent) e);
			}
		}
	}

	private void dispatchPointer(final PointerEvent e) {
		this.deltaX = e.x - this.x;
		this.deltaY = e.y - this.y;
		this.x = e.x;
		this.y = e.y;
		final int btn = (e.button >= 0 && e.button < BUTTON_COUNT) ? e.button : 0;
		switch (e.name) {
		case "down":
			if (!this.buttonPressed[btn]) {
				this.buttonPressed[btn] = true;
				this.buttonJustPressed[btn] = true;
				this.justTouched = true;
			}
			if (this.processor != null) this.processor.touchDown(this.x, this.y, 0, btn);
			break;
		case "up":
			this.buttonPressed[btn] = false;
			if (this.processor != null) this.processor.touchUp(this.x, this.y, 0, btn);
			break;
		case "move":
			if (this.processor != null) {
				if (isTouched()) this.processor.touchDragged(this.x, this.y, 0);
				else this.processor.mouseMoved(this.x, this.y);
			}
			break;
		default:
			// ignore unknown
			break;
		}
	}

	private void dispatchKey(final KeyEvent e) {
		final int keycode = e.keycode;
		switch (e.name) {
		case "down":
			if (keycode >= 0 && keycode <= com.badlogic.gdx.Input.Keys.MAX_KEYCODE) {
				if (!this.pressedKeys[keycode]) {
					this.pressedKeys[keycode] = true;
					this.justPressedKeys[keycode] = true;
					this.pressedKeyCount++;
					this.keyJustPressed = true;
				}
			}
			if (this.processor != null) this.processor.keyDown(keycode);
			if (e.ch != 0 && this.processor != null) this.processor.keyTyped(e.ch);
			break;
		case "up":
			if (keycode >= 0 && keycode <= com.badlogic.gdx.Input.Keys.MAX_KEYCODE) {
				if (this.pressedKeys[keycode]) {
					this.pressedKeys[keycode] = false;
					this.pressedKeyCount = Math.max(0, this.pressedKeyCount - 1);
				}
			}
			if (this.processor != null) this.processor.keyUp(keycode);
			break;
		default:
			break;
		}
	}

	private void dispatchScroll(final ScrollEvent e) {
		if (this.processor != null) this.processor.scrolled(e.dx, e.dy);
	}

	@Override public int getX() { return this.x; }
	@Override public int getX(final int pointer) { return (pointer == 0) ? this.x : 0; }
	@Override public int getY() { return this.y; }
	@Override public int getY(final int pointer) { return (pointer == 0) ? this.y : 0; }
	@Override public int getDeltaX() { return this.deltaX; }
	@Override public int getDeltaX(final int pointer) { return (pointer == 0) ? this.deltaX : 0; }
	@Override public int getDeltaY() { return this.deltaY; }
	@Override public int getDeltaY(final int pointer) { return (pointer == 0) ? this.deltaY : 0; }
	@Override public boolean isTouched() {
		for (final boolean b : this.buttonPressed) if (b) return true;
		return false;
	}
	@Override public boolean isTouched(final int pointer) { return pointer == 0 && isTouched(); }
	@Override public boolean justTouched() { return this.justTouched; }
	@Override public float getPressure() { return isTouched() ? 1f : 0f; }
	@Override public float getPressure(final int pointer) { return (pointer == 0) ? getPressure() : 0f; }
	@Override public boolean isButtonPressed(final int button) {
		return button >= 0 && button < BUTTON_COUNT && this.buttonPressed[button];
	}
	@Override public boolean isButtonJustPressed(final int button) {
		return button >= 0 && button < BUTTON_COUNT && this.buttonJustPressed[button];
	}

	@Override public int getMaxPointers() { return 1; }
	@Override public long getCurrentEventTime() { return this.currentEventTimeNanos; }

	@Override public void setInputProcessor(final InputProcessor processor) { this.processor = processor; }
	@Override public InputProcessor getInputProcessor() { return this.processor; }

	// ----- Stubbed peripherals (workers don't have these) -----
	@Override public float getAccelerometerX() { return 0f; }
	@Override public float getAccelerometerY() { return 0f; }
	@Override public float getAccelerometerZ() { return 0f; }
	@Override public float getGyroscopeX() { return 0f; }
	@Override public float getGyroscopeY() { return 0f; }
	@Override public float getGyroscopeZ() { return 0f; }
	@Override public float getAzimuth() { return 0f; }
	@Override public float getPitch() { return 0f; }
	@Override public float getRoll() { return 0f; }
	@Override public void getRotationMatrix(final float[] matrix) { /* no-op */ }
	@Override public int getRotation() { return 0; }
	@Override public Orientation getNativeOrientation() { return Orientation.Landscape; }

	@Override public void getTextInput(final TextInputListener listener, final String title, final String text, final String hint) {
		if (listener != null) listener.canceled();
	}
	@Override public void getTextInput(final TextInputListener listener, final String title, final String text, final String hint, final OnscreenKeyboardType type) {
		if (listener != null) listener.canceled();
	}
	@Override public void setOnscreenKeyboardVisible(final boolean visible) { /* no-op */ }
	@Override public void setOnscreenKeyboardVisible(final boolean visible, final OnscreenKeyboardType type) { /* no-op */ }
	@Override public void openTextInputField(final NativeInputConfiguration configuration) { /* no-op */ }
	@Override public void closeTextInputField(final boolean sendReturn) { /* no-op */ }
	@Override public void setKeyboardHeightObserver(final KeyboardHeightObserver observer) { /* no-op */ }

	@Override public void vibrate(final int milliseconds) { /* no-op */ }
	@Override public void vibrate(final int milliseconds, final boolean fallback) { /* no-op */ }
	@Override public void vibrate(final int milliseconds, final int amplitude, final boolean fallback) { /* no-op */ }
	@Override public void vibrate(final VibrationType vibrationType) { /* no-op */ }

	@Override public boolean isPeripheralAvailable(final Peripheral peripheral) { return peripheral == Peripheral.HardwareKeyboard || peripheral == Peripheral.MultitouchScreen; }
	@Override public void setCursorCatched(final boolean catched) { /* no-op — main thread owns the canvas */ }
	@Override public boolean isCursorCatched() { return false; }
	@Override public void setCursorPosition(final int x, final int y) {
		throw new GdxRuntimeException("setCursorPosition not supported in worker");
	}

	// ----- Event records -----
	private static final class PointerEvent {
		final String name;
		final int x, y, button;
		PointerEvent(final String name, final int x, final int y, final int button) {
			this.name = name; this.x = x; this.y = y; this.button = button;
		}
	}

	private static final class KeyEvent {
		final String name;
		final int keycode;
		final char ch;
		KeyEvent(final String name, final int keycode, final char ch) {
			this.name = name; this.keycode = keycode; this.ch = ch;
		}
	}

	private static final class ScrollEvent {
		final float dx, dy;
		ScrollEvent(final float dx, final float dy) { this.dx = dx; this.dy = dy; }
	}
}
