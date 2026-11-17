// Antes de gerar o instalador: copia o APK mais novo (Zarpa.apk, na pasta acima de pc/) pra dentro do pacote
// e anota a versão dele (lida do build.gradle.kts do app Android).
const fs = require('fs'), path = require('path');
const src = path.join(__dirname, '..', '..', 'Zarpa.apk'), dir = path.join(__dirname, '..', 'build');
if (!fs.existsSync(src)) { console.error('Zarpa.apk não encontrado em', src); process.exit(1); }
fs.mkdirSync(dir, { recursive: true });
fs.copyFileSync(src, path.join(dir, 'Zarpa.apk'));
let v = '';
try { v = (fs.readFileSync(path.join(__dirname, '..', '..', 'android', 'app', 'build.gradle.kts'), 'utf8').match(/versionName\s*=\s*"([^"]+)"/) || [])[1] || ''; } catch {}
fs.writeFileSync(path.join(dir, 'apk-version.txt'), v);
console.log('APK copiado pro instalador:', (fs.statSync(src).size / 1048576).toFixed(1), 'MB, versão', v || '?');
