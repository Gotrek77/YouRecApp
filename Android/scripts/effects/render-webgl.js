// Runs the app's GLSL ES 1.0 effect passes in WebGL 1 (headless Chromium):
// node render-webgl.js cases.json. Each case gives a fragment shader, its
// output size, input textures (raw RGBA, top-down, uploaded bottom-up as the
// app's framebuffers hold them) and uniforms; the output is written top-down.
// Only a development check: the app runs the same sources on OpenGL ES 2.
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const spec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  await page.setContent('<canvas id="c"></canvas>');
  for (const c of spec.cases) {
    const textures = c.textures.map(t => ({ ...t, data: Array.from(fs.readFileSync(t.file)) }));
    const result = await page.evaluate(({ c, textures, vertex }) => {
      const canvas = document.getElementById('c'); canvas.width = c.width; canvas.height = c.height;
      const gl = canvas.getContext('webgl', { preserveDrawingBuffer: true, antialias: false, premultipliedAlpha: false });
      if (!gl) return { error: 'no webgl' };
      function shader(type, source) { const s = gl.createShader(type); gl.shaderSource(s, source); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; }
      let program;
      try {
        program = gl.createProgram();
        gl.attachShader(program, shader(gl.VERTEX_SHADER, vertex)); gl.attachShader(program, shader(gl.FRAGMENT_SHADER, c.fragment)); gl.linkProgram(program);
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
      } catch (e) { return { error: String(e.message || e) }; }
      gl.useProgram(program);
      textures.forEach((t, unit) => {
        const row = t.width * 4, flipped = new Uint8Array(t.data.length);
        for (let y = 0; y < t.height; y++) flipped.set(t.data.slice((t.height - 1 - y) * row, (t.height - y) * row), y * row);
        const id = gl.createTexture(); gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, id);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, t.width, t.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, flipped);
        const wrap = t.repeat ? gl.REPEAT : gl.CLAMP_TO_EDGE;
        for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, wrap], [gl.TEXTURE_WRAP_T, wrap]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
        gl.uniform1i(gl.getUniformLocation(program, t.name), unit);
      });
      for (const u of c.uniforms) {
        const at = gl.getUniformLocation(program, u.name);
        if (at === null) continue;
        if (u.type === '1f') gl.uniform1f(at, u.value);
        else if (u.type === '2f') gl.uniform2f(at, u.value[0], u.value[1]);
        else if (u.type === '4f') gl.uniform4f(at, u.value[0], u.value[1], u.value[2], u.value[3]);
        else if (u.type === '1fv') gl.uniform1fv(at, new Float32Array(u.value));
        else if (u.type === '2fv') gl.uniform2fv(at, new Float32Array(u.value));
        else if (u.type === '4fv') gl.uniform4fv(at, new Float32Array(u.value));
      }
      const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      const position = gl.getAttribLocation(program, 'position'); gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
      gl.viewport(0, 0, c.width, c.height); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const out = new Uint8Array(c.width * c.height * 4); gl.readPixels(0, 0, c.width, c.height, gl.RGBA, gl.UNSIGNED_BYTE, out);
      const row = c.width * 4, top = new Uint8Array(out.length);
      for (let y = 0; y < c.height; y++) top.set(out.slice((c.height - 1 - y) * row, (c.height - y) * row), y * row);
      return { pixels: Array.from(top) };
    }, { c, textures, vertex: spec.vertex });
    if (result.error) { console.log(`${c.name}: ${result.error}`); fs.writeFileSync(c.output + '.error', result.error); continue; }
    fs.writeFileSync(c.output, Buffer.from(result.pixels));
  }
  await browser.close();
})();
