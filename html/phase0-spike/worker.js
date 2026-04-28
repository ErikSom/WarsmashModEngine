// Phase 0 spike: WebGL2 renderer running entirely inside a DedicatedWorker.
//
// Goal: prove the platform pieces we depend on for the engine-in-worker port —
//   1. OffscreenCanvas transferred from main works as a GL surface here.
//   2. WebGL2 context obtainable inside the worker.
//   3. self.requestAnimationFrame fires (needs a transferred OffscreenCanvas;
//      bare workers don't have rAF).
//   4. Shader compile + draw works (catches more issues than just glClear).
//   5. postMessage from main delivers input events at interactive cadence.
//
// Deliberately hand-written JS — TeaVM-in-worker is the Phase 1 question.

let gl = null;
let canvas = null;
let program = null;
let vao = null;
let frameCount = 0;
let lastFpsSampleTime = 0;
let framesSinceFpsSample = 0;
let fps = 0;
let clickCount = 0;
let lastClick = '';
let clearColor = [0.10, 0.13, 0.18, 1.0];
let triangleHue = 0.55;

self.onmessage = (e) => {
	const m = e.data;
	switch (m.kind) {
		case 'init': onInit(m.canvas); break;
		case 'resize': onResize(m); break;
		case 'pointer': onPointer(m); break;
	}
};

function postError(message) {
	self.postMessage({ kind: 'error', message });
}

function onInit(transferred) {
	canvas = transferred;
	gl = canvas.getContext('webgl2', { antialias: false, alpha: false });
	if (!gl) {
		postError('webgl2 context unavailable in worker');
		return;
	}

	const vsSource = `#version 300 es
		layout(location = 0) in vec2 a_pos;
		layout(location = 1) in vec3 a_col;
		out vec3 v_col;
		void main() {
			v_col = a_col;
			gl_Position = vec4(a_pos, 0.0, 1.0);
		}`;
	const fsSource = `#version 300 es
		precision mediump float;
		in vec3 v_col;
		out vec4 o_col;
		uniform float u_hue;
		void main() {
			// hue rotate around the colour wheel for visible per-frame motion
			vec3 k = vec3(0.0, 0.333, 0.667);
			vec3 shifted = 0.5 + 0.5 * cos(6.2831 * (u_hue + k));
			o_col = vec4(v_col * shifted, 1.0);
		}`;

	const vs = compileShader(gl.VERTEX_SHADER, vsSource);
	const fs = compileShader(gl.FRAGMENT_SHADER, fsSource);
	if (!vs || !fs) return;

	program = gl.createProgram();
	gl.attachShader(program, vs);
	gl.attachShader(program, fs);
	gl.linkProgram(program);
	if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
		postError('program link: ' + gl.getProgramInfoLog(program));
		return;
	}

	vao = gl.createVertexArray();
	gl.bindVertexArray(vao);

	const verts = new Float32Array([
		// pos       // col
		 0.0,  0.7,  1.0, 0.3, 0.4,
		-0.7, -0.6,  0.3, 1.0, 0.4,
		 0.7, -0.6,  0.4, 0.3, 1.0,
	]);
	const buf = gl.createBuffer();
	gl.bindBuffer(gl.ARRAY_BUFFER, buf);
	gl.bufferData(gl.ARRAY_BUFFER, verts, gl.STATIC_DRAW);

	const stride = 5 * 4;
	gl.enableVertexAttribArray(0);
	gl.vertexAttribPointer(0, 2, gl.FLOAT, false, stride, 0);
	gl.enableVertexAttribArray(1);
	gl.vertexAttribPointer(1, 3, gl.FLOAT, false, stride, 2 * 4);
	gl.bindVertexArray(null);

	const debug = gl.getExtension('WEBGL_debug_renderer_info');
	const vendor = debug ? gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) : '(no debug ext)';
	const renderer = debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : '(no debug ext)';
	const glVersion = gl.getParameter(gl.VERSION);

	self.postMessage({ kind: 'ready', vendor, renderer, glVersion });

	// rAF in a dedicated worker requires that the worker has a transferred
	// OffscreenCanvas — which we just received. Without it, self.requestAnimationFrame
	// is undefined.
	if (typeof self.requestAnimationFrame !== 'function') {
		postError('self.requestAnimationFrame is undefined — OffscreenCanvas transfer did not enable rAF in worker');
		return;
	}

	const tick = (t) => {
		frame(t, glVersion);
		self.requestAnimationFrame(tick);
	};
	self.requestAnimationFrame(tick);
}

function compileShader(type, src) {
	const sh = gl.createShader(type);
	gl.shaderSource(sh, src);
	gl.compileShader(sh);
	if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
		postError('shader compile: ' + gl.getShaderInfoLog(sh));
		return null;
	}
	return sh;
}

function onResize(m) {
	if (!canvas || !gl) return;
	canvas.width = m.pixelWidth;
	canvas.height = m.pixelHeight;
	gl.viewport(0, 0, m.pixelWidth, m.pixelHeight);
}

function onPointer(m) {
	if (m.name === 'down') {
		clickCount++;
		lastClick = `(${m.x.toFixed(0)}, ${m.y.toFixed(0)}) btn=${m.button}`;
		clearColor = [Math.random() * 0.4, Math.random() * 0.4, Math.random() * 0.5, 1.0];
	}
}

function frame(t, glVersion) {
	frameCount++;
	framesSinceFpsSample++;
	if (lastFpsSampleTime === 0) lastFpsSampleTime = t;
	const dt = t - lastFpsSampleTime;
	if (dt >= 500) {
		fps = (framesSinceFpsSample * 1000) / dt;
		framesSinceFpsSample = 0;
		lastFpsSampleTime = t;
		// Throttle stat posts to ~2 Hz so postMessage isn't itself a perf factor
		// in the spike's measurements.
		self.postMessage({
			kind: 'frame-stats',
			fps,
			frameCount,
			clickCount,
			lastClick,
			pixelWidth: canvas.width,
			pixelHeight: canvas.height,
			glVersion,
		});
	}

	triangleHue += 0.003;
	gl.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
	gl.clear(gl.COLOR_BUFFER_BIT);
	gl.useProgram(program);
	gl.uniform1f(gl.getUniformLocation(program, 'u_hue'), triangleHue);
	gl.bindVertexArray(vao);
	gl.drawArrays(gl.TRIANGLES, 0, 3);
}
