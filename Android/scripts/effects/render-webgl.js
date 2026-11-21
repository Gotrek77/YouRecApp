// Runs the app's GLSL ES 1.0 effect shader in WebGL 1 (headless Chromium):
// node render-webgl.js cases.json — each case names its shader source, input
// (raw RGBA, top-down) and output file. Only a development check; the app
// itself runs the same source through OpenGL ES 2.
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const spec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  await page.setContent('<canvas id="c"></canvas>');
  for (const c of spec.cases) {
    const input = Array.from(fs.readFileSync(c.input));
    const result = await page.evaluate(({ c, input, vertex }) => {
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
      function texture(unit, w, h, bytes, wrap) {
        const t = gl.createTexture(); gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(bytes));
        for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, wrap], [gl.TEXTURE_WRAP_T, wrap]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
      }
      // GL rows run bottom-up: upload the picture flipped, as the app's framebuffer holds it.
      const flipped = new Uint8Array(input.length), row = c.width * 4;
      for (let y = 0; y < c.height; y++) flipped.set(input.slice((c.height - 1 - y) * row, (c.height - y) * row), y * row);
      texture(0, c.width, c.height, flipped, gl.CLAMP_TO_EDGE);
      texture(1, 2, 2, [128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128], gl.REPEAT);
      gl.uniform1i(gl.getUniformLocation(program, 'image'), 0);
      gl.uniform1i(gl.getUniformLocation(program, 'lattice'), 1);
      gl.uniform2f(gl.getUniformLocation(program, 'size'), c.width, c.height);
      gl.uniform4fv(gl.getUniformLocation(program, 'p'), new Float32Array(c.p));
      gl.uniform1f(gl.getUniformLocation(program, 'time'), c.time);
      gl.uniform1f(gl.getUniformLocation(program, 'intensity'), c.intensity);
      gl.uniform2f(gl.getUniformLocation(program, 'latticeOffset'), 0, 0);
      const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      const position = gl.getAttribLocation(program, 'position'); gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
      gl.viewport(0, 0, c.width, c.height); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const out = new Uint8Array(c.width * c.height * 4); gl.readPixels(0, 0, c.width, c.height, gl.RGBA, gl.UNSIGNED_BYTE, out);
      const top = new Uint8Array(out.length);
      for (let y = 0; y < c.height; y++) top.set(out.slice((c.height - 1 - y) * row, (c.height - y) * row), y * row);
      return { pixels: Array.from(top) };
    }, { c, input, vertex: spec.vertex });
    if (result.error) { console.log(`${c.name}: ${result.error}`); fs.writeFileSync(c.output + '.error', result.error); continue; }
    fs.writeFileSync(c.output, Buffer.from(result.pixels));
  }
  await browser.close();
})();
