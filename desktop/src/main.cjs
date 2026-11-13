'use strict'
// O shell do app desktop: sobe o servidor local (front + proxy da API) e mostra o Tostão numa janela isolada.
const path = require('node:path')
const { app, BrowserWindow, Menu, ipcMain, session, shell } = require('electron')
const config = require('./config.cjs')
const { createServer, listen } = require('./server.cjs')

const PREFERRED_PORT = 47321 // fixa, para a sessão e os dados do navegador sobreviverem entre aberturas
const SETTINGS_PAGE = path.join(__dirname, 'settings.html')

let current // { apiUrl, apiOrigin }, lida do arquivo do usuário
let origin // http://127.0.0.1:<porta>
let mainWindow = null
let settingsWindow = null

const configFile = () => path.join(app.getPath('userData'), 'config.json')
const webDir = () => (app.isPackaged ? path.join(process.resourcesPath, 'web') : path.join(__dirname, '..', '..', 'frontend', 'dist'))

/** Só a própria origem local abre na janela; links https vão para o navegador do sistema; o resto é recusado. */
function lockNavigation(contents, allowed) {
  contents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) shell.openExternal(url)
    return { action: 'deny' }
  })
  contents.on('will-navigate', (event, url) => {
    if (allowed(url)) return
    event.preventDefault()
    if (url.startsWith('https://')) shell.openExternal(url)
  })
}

const webPreferences = (extra = {}) => ({ contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true, ...extra })

function openMain() {
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore()
    return mainWindow.focus()
  }
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 380,
    minHeight: 560,
    title: 'Tostão',
    backgroundColor: '#f6f4ee',
    autoHideMenuBar: true, // a barra de menus só aparece com Alt; os atalhos (Ctrl+, Ctrl+R) valem sempre
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    webPreferences: webPreferences(),
  })
  lockNavigation(mainWindow.webContents, (url) => url.startsWith(`${origin}/`) || url === origin)
  mainWindow.loadURL(origin)
  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

function openSettings() {
  if (settingsWindow) return settingsWindow.focus()
  settingsWindow = new BrowserWindow({
    width: 520,
    height: 470,
    resizable: false,
    minimizable: false,
    maximizable: false,
    title: 'Configurar servidor',
    parent: mainWindow ?? undefined,
    modal: Boolean(mainWindow),
    autoHideMenuBar: true,
    webPreferences: webPreferences({ preload: path.join(__dirname, 'preload.cjs') }),
  })
  settingsWindow.removeMenu()
  lockNavigation(settingsWindow.webContents, () => false)
  settingsWindow.loadFile(SETTINGS_PAGE)
  settingsWindow.on('closed', () => {
    settingsWindow = null
  })
}

function buildMenu() {
  Menu.setApplicationMenu(
    Menu.buildFromTemplate([
      {
        label: 'Arquivo',
        submenu: [
          { label: 'Configurar servidor…', accelerator: 'CmdOrCtrl+,', click: openSettings },
          { label: 'Recarregar', accelerator: 'CmdOrCtrl+R', click: () => mainWindow?.webContents.reload() },
          { type: 'separator' },
          { label: 'Sair', accelerator: 'CmdOrCtrl+Q', click: () => app.quit() },
        ],
      },
      { label: 'Editar', role: 'editMenu' },
      {
        label: 'Exibir',
        submenu: [{ role: 'resetZoom', label: 'Tamanho normal' }, { role: 'zoomIn', label: 'Aumentar' }, { role: 'zoomOut', label: 'Diminuir' }, { role: 'togglefullscreen', label: 'Tela cheia' }],
      },
    ]),
  )
}

// A janela de configuração é a única que fala com o processo principal; o que chega é validado de novo aqui.
function fromSettings(event) {
  return settingsWindow !== null && event.sender === settingsWindow.webContents
}

ipcMain.handle('settings:get', (event) => (fromSettings(event) ? current : null))
ipcMain.handle('settings:save', (event, input) => {
  if (!fromSettings(event)) return { ok: false, errors: { apiUrl: 'Pedido não aceito.' } }
  const saved = config.save(configFile(), input)
  if (saved.ok) {
    current = saved.config
    mainWindow?.webContents.reload()
    settingsWindow?.close()
  }
  return saved
})
ipcMain.on('settings:cancel', (event) => {
  if (fromSettings(event)) settingsWindow.close()
})

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', openMain)
  app.whenReady().then(async () => {
    session.defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false))
    current = config.load(configFile())
    const server = createServer({ webDir: webDir(), apiUrl: () => current.apiUrl, apiOrigin: () => current.apiOrigin })
    const port = await listen(server, PREFERRED_PORT)
    origin = `http://127.0.0.1:${port}`
    buildMenu()
    openMain()
  })
  app.on('window-all-closed', () => app.quit())
}
