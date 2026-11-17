// Gera o ícone do Fly (512x512): um cartão de foto dourado, torto e de cantos redondos, com um "F",
// como se tivesse acabado de ser arremessado. Sai em:
//   pc/assets/icon.png
//   android/app/src/main/res/drawable-nodpi/ic_launcher.png   (o mesmo desenho no celular)
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const N = 512;
const px = Buffer.alloc(N * N * 4);

const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
const mix = (a, b, t) => a + (b - a) * t;
const hex = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const GOLD_DK = hex('#b8860b'), GOLD_LT = hex('#ffe9a0'), INK = hex('#1a1405'), EDGE = hex('#fff4c4');

// distância assinada até um retângulo de cantos redondos (centro cx,cy; meia-largura/altura hw,hh; raio r; rotação ang)
function sdRound(x, y, cx, cy, hw, hh, r, ang) {
  const c = Math.cos(-ang), s = Math.sin(-ang);
  const dx = x - cx, dy = y - cy;
  const lx = dx * c - dy * s, ly = dx * s + dy * c;
  const qx = Math.abs(lx) - (hw - r), qy = Math.abs(ly) - (hh - r);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
}
function distSeg(px_, py_, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay, t = clamp(((px_ - ax) * dx + (py_ - ay) * dy) / (dx * dx + dy * dy));
  return Math.hypot(px_ - (ax + t * dx), py_ - (ay + t * dy));
}
const toLocal = (x, y, cx, cy, ang) => { const c = Math.cos(-ang), s = Math.sin(-ang), dx = x - cx, dy = y - cy; return [dx * c - dy * s, dx * s + dy * c]; };
const deg = d => d * Math.PI / 180;

// cartões (em coordenadas 0..1 da imagem)
const FRONT = { cx: 0.47, cy: 0.49, hw: 0.265, hh: 0.315, r: 0.085, a: deg(-14) };
const BACK  = { cx: 0.54, cy: 0.50, hw: 0.245, hh: 0.295, r: 0.08,  a: deg(10) };

const ainv = 1 / N;           // 1 pixel em coordenadas normalizadas
for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
  const x = (i + 0.5) / N, y = (j + 0.5) / N, o = (j * N + i) * 4;

  // ---- fundo: quadrado arredondado, obsidiana com um brilho dourado atrás dos cartões ----
  const dBg = sdRound(x, y, 0.5, 0.5, 0.5, 0.5, 0.2, 0);
  const aBg = clamp(0.5 - dBg / ainv);
  if (aBg <= 0) { px[o + 3] = 0; continue; }
  const t = (x + y) / 2;
  let col = [mix(31, 8, t), mix(28, 8, t), mix(21, 10, t)];
  const halo = clamp(1 - Math.hypot(x - 0.5, y - 0.52) / 0.55) ** 2 * 0.5;       // brilho quente atrás
  col = col.map((v, k) => mix(v, [140, 104, 12][k], halo * 0.55));

  const over = (c, rgb, a) => c.map((v, k) => mix(v, rgb[k], a));

  // ---- rastro de movimento (3 riscos finos, saindo do cartão pra baixo e pra esquerda) ----
  for (const [k, al] of [[0, 0.5], [1, 0.32], [2, 0.2]]) {
    const y0 = 0.70 + k * 0.055, d = distSeg(x, y, 0.07 + k * 0.012, y0 + 0.07, 0.25 + k * 0.01, y0 - 0.06);
    col = over(col, GOLD_LT, clamp(0.5 - (d - 0.006) / ainv) * al);
  }

  // ---- cartão de trás (mais escuro, girado pro outro lado) ----
  const dB = sdRound(x, y, BACK.cx, BACK.cy, BACK.hw, BACK.hh, BACK.r, BACK.a);
  const tb = clamp((toLocal(x, y, BACK.cx, BACK.cy, BACK.a)[0] / BACK.hw + toLocal(x, y, BACK.cx, BACK.cy, BACK.a)[1] / BACK.hh + 2) / 4);
  col = over(col, [mix(105, 160, tb), mix(78, 120, tb), mix(10, 24, tb)], clamp(0.5 - dB / ainv) * 0.95);

  // ---- sombra do cartão da frente ----
  const dS = sdRound(x, y, FRONT.cx + 0.012, FRONT.cy + 0.03, FRONT.hw, FRONT.hh, FRONT.r, FRONT.a);
  col = over(col, [0, 0, 0], clamp(1 - dS / 0.055) ** 2 * 0.5 * (dS > 0 ? 1 : 0));

  // ---- cartão da frente (dourado) + borda clara + "F" ----
  const dF = sdRound(x, y, FRONT.cx, FRONT.cy, FRONT.hw, FRONT.hh, FRONT.r, FRONT.a);
  const aF = clamp(0.5 - dF / ainv);
  if (aF > 0) {
    const [lx, ly] = toLocal(x, y, FRONT.cx, FRONT.cy, FRONT.a);
    const tf = clamp((lx / FRONT.hw + ly / FRONT.hh + 2) / 4);
    let cf = [mix(GOLD_DK[0], GOLD_LT[0], tf), mix(GOLD_DK[1], GOLD_LT[1], tf), mix(GOLD_DK[2], GOLD_LT[2], tf)];
    cf = over(cf, EDGE, clamp(1 - Math.abs(dF + 0.004) / (2.2 * ainv)) * 0.85);    // fio de luz na borda
    // o "F" (em coordenadas locais do cartão, gira junto com ele)
    const w = 0.052;
    const dLet = Math.min(distSeg(lx, ly, -0.115, -0.165, -0.115, 0.175), distSeg(lx, ly, -0.115, -0.165, 0.135, -0.165), distSeg(lx, ly, -0.115, 0.0, 0.085, 0.0));
    cf = over(cf, INK, clamp(0.5 - (dLet - w) / ainv));
    col = over(col, cf, aF);
  }
  px[o] = col[0]; px[o + 1] = col[1]; px[o + 2] = col[2]; px[o + 3] = Math.round(255 * aBg);
}

// ---- grava o PNG ----
const crcT = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
const crc = b => { let c = 0xffffffff; for (const v of b) c = crcT[(c ^ v) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => { const l = Buffer.alloc(4); l.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
const raw = Buffer.alloc((N * 4 + 1) * N);
for (let y = 0; y < N; y++) { raw[y * (N * 4 + 1)] = 0; px.copy(raw, y * (N * 4 + 1) + 1, y * N * 4, (y + 1) * N * 4); }
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(N, 0); ihdr.writeUInt32BE(N, 4); ihdr[8] = 8; ihdr[9] = 6;
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);

const outPc = path.join(__dirname, '..', 'assets'); fs.mkdirSync(outPc, { recursive: true });
fs.writeFileSync(path.join(outPc, 'icon.png'), png);
const outAnd = path.join(__dirname, '..', '..', 'android', 'app', 'src', 'main', 'res', 'drawable-nodpi');
if (fs.existsSync(path.join(__dirname, '..', '..', 'android', 'app', 'src', 'main', 'res'))) { fs.mkdirSync(outAnd, { recursive: true }); fs.writeFileSync(path.join(outAnd, 'ic_launcher.png'), png); }
console.log('icone ok', png.length, 'bytes');
