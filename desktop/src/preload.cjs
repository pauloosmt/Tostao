'use strict'
// A única ponte da janela de configuração com o programa: ler, gravar e cancelar. Nada mais é exposto.
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('tostaoSettings', {
  get: () => ipcRenderer.invoke('settings:get'),
  save: (input) => ipcRenderer.invoke('settings:save', { apiUrl: String(input?.apiUrl ?? ''), apiOrigin: String(input?.apiOrigin ?? '') }),
  cancel: () => ipcRenderer.send('settings:cancel'),
})
