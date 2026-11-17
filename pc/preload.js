const { contextBridge, ipcRenderer, webUtils } = require('electron');
contextBridge.exposeInMainWorld('zarpa', {
  // pouso
  onLand: cb => ipcRenderer.on('land', (_e, d) => cb(d)),
  onProgress: cb => ipcRenderer.on('progress', (_e, d) => cb(d)),
  onFinal: cb => ipcRenderer.on('final', (_e, d) => cb(d)),
  mouse: ignore => ipcRenderer.send('mouse', ignore),
  drag: id => ipcRenderer.send('drag', id),
  reveal: id => ipcRenderer.send('reveal', id),
  copy: id => ipcRenderer.invoke('copy', id),
  openLink: id => ipcRenderer.send('open-link', id),
  gone: id => ipcRenderer.send('gone', id),
  idle: () => ipcRenderer.send('idle'),
  // portal (barra luminosa no canto)
  portalSize: s => ipcRenderer.send('portal-size', s),
  // parear
  onPair: cb => ipcRenderer.on('pair', (_e, d) => cb(d)),
  onPaired: cb => ipcRenderer.on('paired', () => cb()),
  apkInfo: () => ipcRenderer.invoke('apk-info'),
  apkOpen: () => ipcRenderer.send('apk-open'),
  updInfo: () => ipcRenderer.invoke('upd-info'),
  updSend: () => ipcRenderer.invoke('upd-send'),
  refreshPair: () => ipcRenderer.send('pair-refresh'),
  // galeria
  libList: () => ipcRenderer.invoke('lib-list'),
  libStatus: () => ipcRenderer.invoke('lib-status'),
  libCopy: p => ipcRenderer.invoke('lib-copy', p),
  libDrag: p => ipcRenderer.send('lib-drag', p),
  libReveal: p => ipcRenderer.send('lib-reveal', p),
  libOpen: p => ipcRenderer.send('lib-open', p),
  libFolder: () => ipcRenderer.send('lib-folder'),
  // PC → celular
  pathOf: f => { try { return webUtils.getPathForFile(f); } catch { return ''; } },
  outList: () => ipcRenderer.invoke('out-list'),
  outAdd: spec => ipcRenderer.invoke('out-add', spec),
  outPick: () => ipcRenderer.invoke('out-pick'),
  outRemove: id => ipcRenderer.send('out-remove', id),
  onOutUpdate: cb => ipcRenderer.on('out-update', (_e, d) => cb(d)),
  onLibAdd: cb => ipcRenderer.on('lib-add', (_e, d) => cb(d)),
  onLibThumb: cb => ipcRenderer.on('lib-thumb', (_e, d) => cb(d))
});
