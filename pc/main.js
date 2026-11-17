// Fly (antes Zarpa) — conector do Windows (Windows 10 e 11).
// Recebe arquivos/links do celular pela rede local e faz o "pouso" na tela.
const { app, BrowserWindow, Tray, Menu, ipcMain, screen, shell, nativeImage, dialog } = require('electron');
const http = require('http'), fs = require('fs'), path = require('path'), os = require('os'), crypto = require('crypto');
const { pipeline } = require('stream');
const QRCode = require('qrcode');

const PORT = 47800;
const IMG_EXT = new Set(['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp']);
let cfg, cfgFile, saveDir, tray, overlay, pairWin;
let pairCode = null, pairExpires = 0, pairFails = 0, pairNonce = null;   // pairNonce: código de uso único que vai dentro do QR
const cards = new Map(); // id -> { file, icon }

app.setPath('userData', path.join(app.getPath('appData'), 'zarpa'));   // o nome do app mudou, mas os dados (pareamento) continuam na mesma pasta
if (!app.requestSingleInstanceLock()) app.quit();

// ---------- config ----------
function loadCfg() {
  cfgFile = path.join(app.getPath('userData'), 'config.json');
  try { cfg = JSON.parse(fs.readFileSync(cfgFile, 'utf8')); } catch { cfg = {}; }
  if (!cfg.token) cfg.token = crypto.randomBytes(24).toString('hex');
  if (!cfg.effect) cfg.effect = 'glow';
  saveCfg();
  saveDir = path.join(app.getPath('downloads'), 'Fly');
  const oldDir = path.join(app.getPath('downloads'), 'Zarpa');
  if (!fs.existsSync(saveDir) && fs.existsSync(oldDir)) { try { fs.renameSync(oldDir, saveDir); } catch { saveDir = oldDir; } }   // se não der pra mover, segue na pasta antiga
  fs.mkdirSync(saveDir, { recursive: true });
}
const saveCfg = () => fs.writeFileSync(cfgFile, JSON.stringify(cfg, null, 2));

// ---------- rede ----------
function hosts() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces()))
    for (const i of list || []) if (i.family === 'IPv4' && !i.internal && !i.address.startsWith('169.254.')) out.push(i.address);
  const rank = a => (a.startsWith('192.168.') ? 0 : a.startsWith('10.') ? 1 : 2);
  return out.sort((a, b) => rank(a) - rank(b));
}
const json = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
// ---------- defesa da rede ----------
const MAX_FILE = 2 * 1024 * 1024 * 1024;   // 2 GB por arquivo
const MIN_FREE = 500 * 1024 * 1024;        // sempre deixa 500 MB livres no disco
// Só aceita conexões da rede local (privada) e do Tailscale. Ignora Radmin/Hamachi e qualquer endereço público.
function allowedIp(ip) {
  ip = String(ip || '').replace(/^::ffff:/, '');
  if (ip === '::1') return true;
  const m = ip.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
  if (!m) return /^(fe80|fc|fd)/i.test(ip);
  const a = +m[1], b = +m[2];
  return a === 127 || a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254);
}
const badAuth = new Map();   // ip -> tentativas erradas
const isBlocked = ip => { const e = badAuth.get(ip); return !!e && e.until > Date.now(); };
function noteBad(ip) {
  const e = badAuth.get(ip) || { n: 0, t: Date.now(), until: 0 };
  if (Date.now() - e.t > 60000) { e.n = 0; e.t = Date.now(); }
  if (++e.n >= 15) e.until = Date.now() + 5 * 60000;   // 15 erros em 1 min: bloqueia 5 min
  badAuth.set(ip, e);
}
function freeBytes() { try { const s = fs.statfsSync(saveDir); return s.bavail * s.bsize; } catch { return Infinity; } }
const links = new Map();   // id -> url recebida (só abre quando você clica em "Abrir")

let lastSave = 0;
function touch() {   // anota o último contato do celular (aparece na galeria)
  cfg.lastSeen = Date.now();
  if (cfg.lastSeen - lastSave > 30000) { lastSave = cfg.lastSeen; saveCfg(); }
}
function authed(req) {
  const h = req.headers.authorization || '';
  const a = Buffer.from(h.startsWith('Bearer ') ? h.slice(7) : ''), b = Buffer.from(cfg.token);
  const ok = a.length === b.length && crypto.timingSafeEqual(a, b);
  if (ok) touch(); else noteBad(req.socket.remoteAddress);
  return ok;
}
// Celular acabou de parear: fecha a janela do QR e abre a galeria.
function onPaired() {
  pairNonce = null; pairCode = null;
  if (pairWin && !pairWin.isDestroyed()) {
    const w = pairWin;
    w.webContents.send('paired');                                  // mostra "Celular conectado ✓"
    setTimeout(() => { if (!w.isDestroyed()) w.close(); openGallery(); }, 1300);
  } else openGallery();
  devLog('PAIR concluido: janela do QR fecha e a galeria abre');
}
function devLog(msg) { if (app.isPackaged) return; try { fs.appendFileSync(path.join(app.getPath('userData'), 'zarpa.log'), `   [dev] ${msg}\n`); } catch {} }
function readSmall(req, max, cb) {
  let n = 0; const parts = [];
  req.on('data', c => { n += c.length; if (n > max) req.destroy(); else parts.push(c); });
  req.on('end', () => cb(Buffer.concat(parts).toString('utf8')));
}
function uniquePath(name) {
  const clean = (path.basename(name || 'arquivo').replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').trim() || 'arquivo').slice(0, 150);
  const ext = path.extname(clean), base = path.basename(clean, ext);
  let p = path.join(saveDir, clean), i = 1;
  while (fs.existsSync(p)) p = path.join(saveDir, `${base} (${i++})${ext}`);
  return p;
}

function logReq(req) {
  try { fs.appendFileSync(path.join(app.getPath('userData'), 'zarpa.log'), `${new Date().toISOString()} ${req.socket.remoteAddress} ${req.method} ${(req.url || '').split('?')[0]}\n`); } catch {}
}
const server = http.createServer((req, res) => {
  logReq(req);
  if (isBlocked(req.socket.remoteAddress)) return json(res, 429, { error: 'Muitas tentativas. Aguarde alguns minutos.' });
  const u = new URL(req.url, 'http://x');
  if (req.method === 'GET' && u.pathname === '/ping') return json(res, 200, { app: 'zarpa' });   // sem revelar o nome do PC

  // O APK do celular, pra baixar direto pelo QR (sem segredo dentro; só responde pra rede local/Tailscale).
  if (req.method === 'GET' && u.pathname === '/zarpa.apk') {
    let st; try { st = fs.statSync(apkPath()); } catch { return json(res, 404, { error: 'O APK não está neste PC' }); }
    res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': st.size, 'Content-Disposition': 'attachment; filename="Fly.apk"' });
    return pipeline(fs.createReadStream(apkPath()), res, () => {});
  }

  if (req.method === 'POST' && u.pathname === '/pair') {
    return readSmall(req, 1024, body => {
      let code = ''; try { code = String(JSON.parse(body).code || ''); } catch {}
      if (!pairCode || Date.now() > pairExpires) return json(res, 403, { error: 'Abra "Parear celular" no PC' });
      if (code !== pairCode) { if (++pairFails >= 5) pairCode = null; return json(res, 403, { error: 'Código errado' }); }
      pairCode = null; json(res, 200, { token: cfg.token, name: os.hostname() });
      touch(); onPaired();
    });
  }

  // O app do celular avisa que abriu e está conectado. Se a janela do QR estava aberta, o pareamento terminou.
  if (req.method === 'POST' && u.pathname === '/hello') {
    if (!authed(req)) return json(res, 401, { error: 'Celular não pareado' });
    json(res, 200, { ok: true, name: os.hostname() });
    notePhoneVersion(req);
    // Só encerra o pareamento se o celular trouxer o código único do QR que está na tela (e ainda valendo).
    const k = String(req.headers['x-zarpa-pair'] || '');
    if (k && pairNonce && k === pairNonce && Date.now() < pairExpires) onPaired();
    return;
  }

  if (req.method === 'POST' && u.pathname === '/upload') {
    if (!authed(req)) return json(res, 401, { error: 'Celular não pareado' });
    const kind = u.searchParams.get('kind') || 'file';
    if (kind === 'link') {
      return readSmall(req, 4096, body => {
        let url; try { url = new URL(body.trim()); } catch { return json(res, 400, { error: 'Link inválido' }); }
        if (!/^https?:$/.test(url.protocol)) return json(res, 400, { error: 'Só links http/https' });
        const lid = crypto.randomUUID();
        // O link não abre sozinho: vira um cartão com botão "Abrir link". Quem decide é você.
        links.set(lid, url.href);
        land({ id: lid, name: url.hostname, link: true, url: url.href });
        json(res, 200, { ok: true, dest: 'pc' });
      });
    }
    const id = (u.searchParams.get('id') || '').slice(0, 64);
    const total = parseInt(req.headers['x-zarpa-size'] || '0', 10) || 0;
    if (total > MAX_FILE) return json(res, 413, { error: 'Arquivo grande demais (limite de 2 GB)' });
    if (total + MIN_FREE > freeBytes()) return json(res, 507, { error: 'Sem espaço livre no PC' });
    const dest = uniquePath(u.searchParams.get('name'));
    const t0 = Date.now(); let got = 0, lastP = 0;
    req.on('data', c => {
      got += c.length;
      if (got > MAX_FILE) return req.destroy(new Error('arquivo acima do limite'));
      const now = Date.now();
      if (id && total > 0 && now - lastP > 150 && overlay && !overlay.isDestroyed()) { lastP = now; overlay.webContents.send('progress', { id, pct: Math.min(99, Math.floor(got * 100 / total)) }); }
    });
    return pipeline(req, fs.createWriteStream(dest), err => {
      if (err) { fs.rm(dest, () => {}); return json(res, 500, { error: 'Falha ao gravar' }); }
      json(res, 200, { ok: true, saved: path.basename(dest) });
      const ms = Date.now() - t0;
      try { fs.appendFileSync(path.join(app.getPath('userData'), 'zarpa.log'), `   upload ${path.basename(dest)} ${got} bytes em ${ms} ms (${(got / 1048576 / Math.max(ms / 1000, 0.001)).toFixed(2)} MB/s)\n`); } catch {}
      libNew(dest);
      if (id && shown.has(id)) finalize(id, dest);       // a prévia já pousou: só troca pelo original
      else { if (id) shown.add(id); land({ id: id || undefined, file: dest, name: path.basename(dest) }); }
    });
  }

  // ---------- PC → celular ----------
  // O celular fica perguntando "tem algo pra mim?" (long-poll). Quando você solta algo na aba, a resposta sai na hora.
  if (req.method === 'GET' && u.pathname === '/inbox/wait') {
    if (!authed(req)) return json(res, 401, { error: 'Celular não pareado' });
    const after = parseInt(u.searchParams.get('after') || '0', 10) || 0;
    notePhoneVersion(req);
    const ready = readyFor(after);
    if (ready.length) { lastPoll = Date.now(); return json(res, 200, { items: handOut(ready) }); }
    const w = { res, after };
    w.timer = setTimeout(() => { waiters.delete(w); lastPoll = Date.now(); outNotify(); json(res, 200, { items: [] }); }, 25000);
    waiters.add(w); lastPoll = Date.now(); outNotify();
    req.on('close', () => { clearTimeout(w.timer); if (waiters.delete(w)) outNotify(); });
    return;
  }
  if (req.method === 'GET' && u.pathname === '/inbox/file') {
    if (!authed(req)) return json(res, 401, { error: 'Celular não pareado' });
    const it = outbox.find(i => i.id === u.searchParams.get('id') && i.kind === 'file');
    let st; try { st = it && fs.statSync(it.path); } catch {}
    if (!it || !st || !st.isFile()) return json(res, 404, { error: 'O arquivo não está mais no PC' });
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': st.size });
    return pipeline(fs.createReadStream(it.path), res, () => {});
  }
  if (req.method === 'POST' && u.pathname === '/inbox/ack') {
    if (!authed(req)) return json(res, 401, { error: 'Celular não pareado' });
    const it = outbox.find(i => i.id === u.searchParams.get('id'));
    if (it) { it.state = u.searchParams.get('status') === 'received' ? 'delivered' : 'declined'; it.at = Date.now(); saveOutbox(); outNotify(); }
    return json(res, 200, { ok: true });
  }

  // Prévia leve (miniatura) que chega antes do arquivo original e faz o pouso imediato.
  if (req.method === 'POST' && u.pathname === '/preview') {
    if (!authed(req)) return json(res, 401, { error: 'Celular não pareado' });
    const id = (u.searchParams.get('id') || '').slice(0, 64);
    const chunks = []; let n = 0;
    req.on('data', c => { n += c.length; if (n > 4 * 1024 * 1024) req.destroy(); else chunks.push(c); });
    req.on('end', () => {
      json(res, 200, { ok: true });
      if (!id || shown.has(id) || !chunks.length) return;   // o original já chegou antes
      shown.add(id);
      land({ id, name: u.searchParams.get('name') || 'foto', preview: 'data:image/jpeg;base64,' + Buffer.concat(chunks).toString('base64'), uploading: true });
    });
    return;
  }
  json(res, 404, { error: 'não encontrado' });
});
server.on('error', e => console.error('servidor:', e.message));
server.on('connection', s => { if (!allowedIp(s.remoteAddress)) s.destroy(); });   // endereço de fora da rede local/Tailscale: derruba

// ---------- pouso ----------
function makeOverlay() {
  overlay = new BrowserWindow({
    show: false, frame: false, transparent: true, resizable: false, movable: false, hasShadow: false,
    skipTaskbar: true, focusable: false, alwaysOnTop: true, fullscreenable: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true }
  });
  overlay.setAlwaysOnTop(true, 'screen-saver');
  overlay.setIgnoreMouseEvents(true, { forward: true });
  overlay.loadFile('overlay.html');
}
// ---------- portal: barra luminosa no canto, joga o arquivo e envia pro celular ----------
let portal, portalState = 'idle';
const PORTAL_SIZE = { idle: { w: 150, h: 36 }, near: { w: 190, h: 130 }, pop: { w: 330, h: 210 } };
function portalPlace() {
  if (!portal || portal.isDestroyed()) return;
  const wa = screen.getPrimaryDisplay().workArea, s = PORTAL_SIZE[portalState] || PORTAL_SIZE.idle;
  // âncora fixa: a barra fica sempre colada no canto de baixo à direita, em cima da barra de tarefas; a janela cresce pra esquerda e pra cima
  portal.setBounds({ x: wa.x + wa.width - 8 - s.w, y: wa.y + wa.height - s.h, width: s.w, height: s.h });
  portal.setAlwaysOnTop(true, 'screen-saver');
}
function makePortal() {
  if (portal && !portal.isDestroyed()) return;
  portal = new BrowserWindow({
    show: false, frame: false, transparent: true, resizable: false, movable: false, hasShadow: false,
    skipTaskbar: true, focusable: false, alwaysOnTop: true, fullscreenable: false, thickFrame: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true }
  });
  portalState = 'idle'; portalPlace();
  portal.loadFile('portal.html');
  portal.once('ready-to-show', () => { portalPlace(); portal.showInactive(); });
}
function killPortal() { if (portal && !portal.isDestroyed()) portal.destroy(); portal = null; }
ipcMain.on('portal-size', (_e, s) => { if (PORTAL_SIZE[s]) { portalState = s; portalPlace(); } });
const shown = new Set();   // ids de envios que já têm cartão na tela
function fileSrc(file) { return IMG_EXT.has(path.extname(file).toLowerCase()) ? require('url').pathToFileURL(file).href : null; }
// Ícone usado ao arrastar o arquivo pra fora. Calculado depois do pouso, sem travar nada.
function prepareIcon(id, file) {
  app.getFileIcon(file, { size: 'large' }).then(ic => { const c = cards.get(id); if (c) c.icon = ic; }).catch(() => {});
}
// O original terminou de chegar: troca a prévia pela foto inteira e libera arrastar/abrir pasta.
function finalize(id, file) {
  cards.set(id, { file, icon: null });
  prepareIcon(id, file);
  if (overlay && !overlay.isDestroyed()) overlay.webContents.send('final', { id, src: fileSrc(file), name: path.basename(file) });
}
async function land(item) {
  const id = item.id || crypto.randomUUID();
  let src = item.preview || null;
  if (item.file) {
    if (!src) src = fileSrc(item.file);   // o Chromium respeita a rotação EXIF; o nativeImage não
    cards.set(id, { file: item.file, icon: null });
    prepareIcon(id, item.file);
  }
  const d = screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
  overlay.setBounds(d.bounds);
  overlay.showInactive();
  overlay.setAlwaysOnTop(true, 'screen-saver');
  const wa = d.workArea, b = d.bounds;
  overlay.webContents.send('land', {
    id, name: item.name, src, link: !!item.link, url: item.url ? String(item.url).slice(0, 220) : null, effect: cfg.effect, uploading: !!item.uploading,
    ext: item.file ? path.extname(item.file).replace('.', '').toUpperCase() : 'LINK',
    area: { x: wa.x - b.x, y: wa.y - b.y, w: wa.width, h: wa.height }
  });
}
ipcMain.on('mouse', (_e, ignore) => overlay && overlay.setIgnoreMouseEvents(!!ignore, { forward: true }));
ipcMain.on('drag', (e, id) => {
  const c = cards.get(id); if (!c) return;
  e.sender.startDrag({ file: c.file, icon: c.icon && !c.icon.isEmpty() ? c.icon : nativeImage.createFromPath(path.join(__dirname, 'assets', 'icon.png')).resize({ width: 64 }) });
});
// Coloca o arquivo (e a imagem, se for foto) na área de transferência.
function copyFile(file) {
  return new Promise(resolve => {
    if (!file) return resolve(false);
    require('child_process').execFile('powershell.exe',
      // O PowerShell não lê de dentro do .asar; no app instalado o script fica em app.asar.unpacked.
      ['-NoProfile', '-STA', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'tools', 'copy.ps1').replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep), '-Path', file],
      { windowsHide: true, timeout: 20000 }, err => resolve(!err));
  });
}
ipcMain.handle('copy', (_e, id) => copyFile((cards.get(id) || {}).file));

// ---------- galeria (tudo que já chegou) ----------
let gallery;
const thumbCache = new Map();
function libInfo(file) {
  try {
    const st = fs.statSync(file);
    return { path: file, name: path.basename(file), mtime: st.mtimeMs, size: st.size, ext: path.extname(file).replace('.', '').toUpperCase() };
  } catch { return null; }
}
function libList() {
  try {
    return fs.readdirSync(saveDir, { withFileTypes: true }).filter(d => d.isFile()).map(d => libInfo(path.join(saveDir, d.name)))
      .filter(Boolean).sort((a, b) => b.mtime - a.mtime).slice(0, 300);
  } catch { return []; }
}
async function thumbOf(info) {
  const k = info.path + ':' + info.mtime;
  if (thumbCache.has(k)) return thumbCache.get(k);
  let t = null;
  try {   // miniatura do próprio Windows: rápida e já na orientação certa
    const img = await nativeImage.createThumbnailFromPath(info.path, { width: 420, height: 420 });
    if (!img.isEmpty()) t = 'data:image/jpeg;base64,' + img.toJPEG(80).toString('base64');
  } catch {}
  thumbCache.set(k, t);
  return t;
}
async function sendThumbs(list) {
  let i = 0;
  const worker = async () => {
    while (i < list.length) {
      const it = list[i++], t = await thumbOf(it);
      if (t && gallery && !gallery.isDestroyed()) gallery.webContents.send('lib-thumb', { path: it.path, thumb: t });
    }
  };
  await Promise.all([worker(), worker(), worker(), worker()]);
}
function libNew(file) {   // chegou arquivo novo: entra na galeria se ela estiver aberta
  const info = libInfo(file);
  if (!info || !gallery || gallery.isDestroyed()) return;
  gallery.webContents.send('lib-add', info);
  thumbOf(info).then(t => { if (t && gallery && !gallery.isDestroyed()) gallery.webContents.send('lib-thumb', { path: info.path, thumb: t }); });
}
function openGallery() {
  if (gallery && !gallery.isDestroyed()) { if (gallery.isMinimized()) gallery.restore(); gallery.show(); return gallery.focus(); }
  gallery = new BrowserWindow({ width: 1040, height: 720, minWidth: 560, minHeight: 420, autoHideMenuBar: true, title: 'Fly', backgroundColor: '#08080a',
    icon: path.join(__dirname, 'assets', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true } });
  gallery.loadFile('gallery.html');
}
const inLib = p => { const r = path.resolve(String(p || '')); return r.startsWith(saveDir + path.sep) && fs.existsSync(r) ? r : null; };
ipcMain.handle('lib-list', () => { const l = libList(); setImmediate(() => sendThumbs(l)); return l; });
ipcMain.handle('lib-status', () => ({ lastSeen: cfg.lastSeen || 0 }));
ipcMain.handle('lib-copy', (_e, p) => copyFile(inLib(p)));
ipcMain.on('lib-reveal', (_e, p) => { const f = inLib(p); if (f) shell.showItemInFolder(f); });
// Só abre direto tipos que não executam código. Qualquer outra coisa (.exe, .bat, .lnk, .js...) só é mostrada na pasta.
const OPEN_OK = new Set(['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.heic', '.heif', '.tif', '.tiff', '.mp4', '.mov', '.mkv', '.avi', '.webm', '.m4v', '.3gp',
  '.mp3', '.wav', '.m4a', '.ogg', '.flac', '.aac', '.pdf', '.txt', '.csv', '.md', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.odt', '.ods']);
ipcMain.on('lib-open', (_e, p) => {
  const f = inLib(p); if (!f) return;
  if (OPEN_OK.has(path.extname(f).toLowerCase())) shell.openPath(f); else shell.showItemInFolder(f);
});
ipcMain.on('lib-folder', () => shell.openPath(saveDir));
ipcMain.on('lib-drag', (e, p) => {
  const f = inLib(p); if (!f) return;
  let icon = nativeImage.createFromPath(path.join(__dirname, 'assets', 'icon.png')).resize({ width: 64 });
  for (const [k, t] of thumbCache) if (t && k.startsWith(f + ':')) { icon = nativeImage.createFromDataURL(t).resize({ width: 96 }); break; }
  e.sender.startDrag({ file: f, icon });
});
ipcMain.on('reveal', (_e, id) => { const c = cards.get(id); if (c) shell.showItemInFolder(c.file); });
ipcMain.on('gone', (_e, id) => { cards.delete(id); shown.delete(id); links.delete(id); });
ipcMain.on('open-link', (_e, id) => { const u = links.get(id); if (u && /^https?:\/\//i.test(u)) shell.openExternal(u); });
ipcMain.on('idle', () => overlay && overlay.hide());

// ---------- caixa de saída (PC → celular) ----------
let outbox = [], outSeq = 0, lastPoll = 0;
const waiters = new Set();
const outFile = () => path.join(app.getPath('userData'), 'outbox.json');
function loadOutbox() {
  try { const j = JSON.parse(fs.readFileSync(outFile(), 'utf8').replace(/^﻿/, '')); outbox = j.items || []; outSeq = j.seq || 0; } catch {}
  const cut = Date.now() - 7 * 864e5; outbox = outbox.filter(i => i.created > cut);   // some o que ficou mais de 7 dias sem ser entregue
}
function saveOutbox() { try { fs.writeFileSync(outFile(), JSON.stringify({ seq: outSeq, items: outbox })); } catch {} }
const pubItem = i => ({ seq: i.seq, id: i.id, kind: i.kind, name: i.name, size: i.size || 0, text: i.text || null, url: i.url || null, update: !!i.update });
function outList() {
  return {
    online: waiters.size > 0 || Date.now() - lastPoll < 40000,
    items: outbox.slice().reverse().map(i => ({ id: i.id, kind: i.kind, name: i.name, size: i.size || 0, state: i.state, created: i.created, at: i.at || 0, update: !!i.update,
      preview: i.kind === 'text' ? String(i.text).slice(0, 90) : (i.url || null) }))
  };
}
// O celular guarda "o último número que vi" (after). Se esse número ficar maior que o do PC (fila recriada, PC reinstalado...),
// o PC passava a achar que não havia nada novo e o item nunca chegava. Por isso, o que ainda não foi entregue sai SEMPRE,
// independente do número do celular; o celular ignora o que já tem (confere pelo id).
const readyFor = after => outbox.filter(i => i.state === 'pending' && (i.seq > after || !i.sent));
function handOut(items) { items.forEach(i => { i.sent = true; }); saveOutbox(); return items.map(pubItem); }
function outNotify() { if (gallery && !gallery.isDestroyed()) gallery.webContents.send('out-update', outList()); }
function flushWaiters() {
  for (const w of [...waiters]) {
    const ready = readyFor(w.after);
    if (!ready.length) continue;
    clearTimeout(w.timer); waiters.delete(w);
    json(w.res, 200, { items: handOut(ready) });
  }
}
const newId = () => crypto.randomBytes(8).toString('hex');
function outAddFile(p) {
  let st; try { st = fs.statSync(p); } catch { return 'não consegui ler'; }
  if (!st.isFile()) return 'pastas não são suportadas (compacte em .zip)';
  if (st.size > MAX_FILE) return 'passa de 2 GB';
  outbox.push({ seq: ++outSeq, id: newId(), kind: 'file', name: path.basename(p), path: p, size: st.size, created: Date.now(), state: 'pending' });
  return null;
}
function outAddText(t) {
  t = String(t || '').trim();
  if (!t) return null;
  if (t.length > 100000) return 'texto grande demais';
  let kind = 'text', url = null;
  try { const u = new URL(t); if (/^https?:$/.test(u.protocol) && !/\s/.test(t)) { kind = 'link'; url = u.href; } } catch {}
  outbox.push({ seq: ++outSeq, id: newId(), kind, url, text: kind === 'text' ? t : null,
    name: kind === 'link' ? new URL(url).hostname : t.slice(0, 40).replace(/\s+/g, ' '), created: Date.now(), state: 'pending' });
  return null;
}
ipcMain.handle('out-list', () => outList());
ipcMain.handle('out-add', (_e, spec) => {
  const errors = [];
  for (const p of (spec && spec.paths) || []) { const e = outAddFile(String(p)); if (e) errors.push(path.basename(String(p)) + ': ' + e); }
  for (const t of (spec && spec.texts) || []) { const e = outAddText(t); if (e) errors.push(e); }
  saveOutbox(); flushWaiters(); outNotify();
  return { errors };
});
ipcMain.handle('out-pick', async () => {
  const r = await dialog.showOpenDialog(gallery, { properties: ['openFile', 'multiSelections'] });
  return r.canceled ? [] : r.filePaths;
});
ipcMain.on('out-remove', (_e, id) => { outbox = outbox.filter(i => i.id !== id); saveOutbox(); outNotify(); });

// ---------- baixar o app do celular (QR do APK) ----------
const apkPath = () => app.isPackaged ? path.join(process.resourcesPath, 'Zarpa.apk') : path.join(__dirname, '..', 'Zarpa.apk');
let apkWin;
async function openApk() {
  if (apkWin && !apkWin.isDestroyed()) return apkWin.focus();
  apkWin = new BrowserWindow({ width: 440, height: 680, resizable: false, autoHideMenuBar: true, title: 'Fly — instalar no celular', icon: path.join(__dirname, 'assets', 'icon.png'), backgroundColor: '#08080a',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true } });
  apkWin.loadFile('apk.html');
}
ipcMain.handle('apk-info', async () => {
  const list = hosts().filter(allowedIp).sort((a, b) => (b.startsWith('100.') ? 1 : 0) - (a.startsWith('100.') ? 1 : 0));   // Tailscale primeiro: funciona em qualquer rede
  const out = [];
  for (const h of list) {
    const url = `http://${h}:${PORT}/zarpa.apk`;
    out.push({ host: h, url, label: h.startsWith('100.') ? 'Tailscale (funciona em qualquer rede)' : 'Wi-Fi da casa (mesma rede)', qr: await QRCode.toDataURL(url, { margin: 1, width: 280 }) });
  }
  let size = 0; try { size = fs.statSync(apkPath()).size; } catch {}
  return { items: out, size, ok: size > 0 };
});
ipcMain.on('apk-open', () => openApk());

// ---------- atualizações do app do celular ----------
function apkVersion() {
  try {
    if (app.isPackaged) return fs.readFileSync(path.join(process.resourcesPath, 'apk-version.txt'), 'utf8').trim();
    return (fs.readFileSync(path.join(__dirname, '..', 'android', 'app', 'build.gradle.kts'), 'utf8').match(/versionName\s*=\s*"([^"]+)"/) || [])[1] || '';
  } catch { return ''; }
}
const verCmp = (a, b) => { const x = String(a).split('.').map(Number), y = String(b).split('.').map(Number); for (let i = 0; i < 4; i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d > 0 ? 1 : -1; } return 0; };
function notePhoneVersion(req) {
  const v = String(req.headers['x-zarpa-app'] || '').replace(/[^0-9.]/g, '').slice(0, 20);
  if (v && cfg.phoneVersion !== v) { cfg.phoneVersion = v; saveCfg(); }
}
ipcMain.handle('upd-info', () => {
  let size = 0; try { size = fs.statSync(apkPath()).size; } catch {}
  const apk = apkVersion(), phone = cfg.phoneVersion || '';
  return { ok: size > 0, size, apkVersion: apk, phoneVersion: phone, newer: !!(apk && phone && verCmp(apk, phone) > 0), unknown: !phone };
});
ipcMain.handle('upd-send', () => {
  let st; try { st = fs.statSync(apkPath()); } catch { return { error: 'O APK não está neste PC' }; }
  const v = apkVersion() || 'novo';
  outbox = outbox.filter(i => !(i.update && i.state === 'pending'));      // só uma atualização esperando por vez
  outbox.push({ seq: ++outSeq, id: newId(), kind: 'file', name: `Fly-${v}.apk`, path: apkPath(), size: st.size, update: true, created: Date.now(), state: 'pending' });
  saveOutbox(); flushWaiters(); outNotify();
  return { ok: true, version: v };
});

// ---------- parear ----------
async function openPair() {
  pairCode = String(crypto.randomInt(0, 1000000)).padStart(6, '0'); pairExpires = Date.now() + 10 * 60 * 1000; pairFails = 0;
  pairNonce = crypto.randomBytes(8).toString('hex');
  devLog('PAIR janela aberta nonce=' + pairNonce);
  const h = hosts();
  const qr = await QRCode.toDataURL(`zarpa://pair?h=${h.join(',')}&p=${PORT}&t=${cfg.token}&n=${encodeURIComponent(os.hostname())}&k=${pairNonce}`, { margin: 1, width: 300 });
  const info = { qr, hosts: h, port: PORT, code: pairCode, name: os.hostname() };
  if (pairWin && !pairWin.isDestroyed()) { pairWin.webContents.send('pair', info); return pairWin.focus(); }
  pairWin = new BrowserWindow({ width: 420, height: 620, resizable: false, autoHideMenuBar: true, title: 'Fly — parear celular', icon: path.join(__dirname, 'assets', 'icon.png'), backgroundColor: '#08080a',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true } });
  pairWin.loadFile('pair.html');
  pairWin.webContents.once('did-finish-load', () => pairWin.webContents.send('pair', info));
}
ipcMain.on('pair-refresh', () => openPair());

// ---------- bandeja ----------
function buildMenu() {
  return Menu.buildFromTemplate([
    { label: 'Abrir fotos recebidas', click: openGallery },
    { label: 'Parear celular…', click: openPair },
    { label: 'Instalar/atualizar o app no celular (QR)…', click: openApk },
    { label: 'Abrir pasta do Fly', click: () => shell.openPath(saveDir) },
    { label: 'Testar pouso', click: testLand },
    { type: 'separator' },
    { label: 'Efeito do pouso', submenu: [
      { label: 'Brilho na borda', type: 'radio', checked: cfg.effect === 'glow', click: () => { cfg.effect = 'glow'; saveCfg(); } },
      { label: 'Onda', type: 'radio', checked: cfg.effect === 'ripple', click: () => { cfg.effect = 'ripple'; saveCfg(); } } ] },
    { label: 'Portal no canto da tela', type: 'checkbox', checked: cfg.portal !== false, click: m => { cfg.portal = m.checked; saveCfg(); if (m.checked) makePortal(); else killPortal(); } },
    { label: 'Iniciar com o Windows', type: 'checkbox', checked: app.getLoginItemSettings().openAtLogin, click: m => app.setLoginItemSettings({ openAtLogin: m.checked }) },
    { label: 'Desparear todos os celulares', click: () => { cfg.token = crypto.randomBytes(24).toString('hex'); saveCfg(); } },
    { type: 'separator' },
    { label: 'Sair', click: () => app.quit() }
  ]);
}
function testLand() {
  const f = uniquePath('Teste Fly.png');
  fs.copyFileSync(path.join(__dirname, 'assets', 'icon.png'), f);
  land({ file: f, name: path.basename(f) });
}

app.on('second-instance', (_e, argv) => { argv = argv || []; if (argv.includes('--pair')) openPair(); else if (argv.includes('--apk')) openApk(); else openGallery(); });   // "Fly.exe --pair" abre direto o QR
app.whenReady().then(() => {
  loadCfg();
  loadOutbox();
  app.setAppUserModelId('app.fly');
  makeOverlay();
  if (cfg.portal !== false) makePortal();
  if (process.argv.includes('--portal-demo')) {   // só pra eu conferir a animação sem arrastar nada: troca de estado sozinho
    let k = 0; const seq = ['near', 'pop', 'idle'];
    setTimeout(() => setInterval(() => { if (!portal || portal.isDestroyed()) return;
      portal.webContents.executeJavaScript(`$('pn').textContent='relatorio.pdf';$('ps').textContent='2,4 MB';setState('${seq[k++ % 3]}')`).catch(() => {}); }, 3000), 3000);
  }
  screen.on('display-metrics-changed', portalPlace);
  tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'assets', 'icon.png')).resize({ width: 32 }));
  tray.setToolTip('Fly');
  tray.setContextMenu(buildMenu());
  tray.on('click', openGallery);
  server.listen(PORT, '0.0.0.0', () => console.log('Fly ouvindo em', hosts().join(', '), 'porta', PORT));
  if (!cfg.paired_once) {
    cfg.paired_once = true; saveCfg(); openApk();   // primeira vez: começa pelo QR do app do celular; de lá o botão leva ao QR de parear
    if (app.isPackaged) app.setLoginItemSettings({ openAtLogin: true });   // app instalado: abre com o Windows (dá pra desligar no menu da bandeja)
  }
});
app.on('window-all-closed', e => e.preventDefault());
