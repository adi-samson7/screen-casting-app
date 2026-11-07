// Electron shell for the PC side: runs the signaling server and the sender UI in one app,
// and captures the screen (+ system audio) natively with GPU-accelerated video encoding.
const { app, BrowserWindow, desktopCapturer, session, screen, Menu, shell, powerSaveBlocker } = require('electron');
const path = require('path');

// ---- GPU / hardware acceleration ----
// Let Chromium use the GPU even if it is on the driver blocklist, and keep captured frames on the GPU
// (zero-copy) so the hardware H.264 encoder (NVENC / Quick Sync / AMD VCE via Media Foundation) can read them directly.
app.commandLine.appendSwitch('ignore-gpu-blocklist');
app.commandLine.appendSwitch('enable-gpu-rasterization');
app.commandLine.appendSwitch('enable-zero-copy');
app.commandLine.appendSwitch('enable-hardware-overlays', 'single-fullscreen,single-on-top,underlay');
app.commandLine.appendSwitch('enable-features', [
  'MediaFoundationD3D11VideoCapture',
  'WebRtcUseGpuMemoryBufferVideoFrames',
  'PlatformHEVCEncoderSupport',
].join(','));
// The window is often minimized/behind other windows while casting; don't throttle it.
// (CalculateNativeWinOcclusion makes Chromium treat a covered window as hidden and slow its capture/encode.)
app.commandLine.appendSwitch('disable-features', 'CalculateNativeWinOcclusion');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');

let win, serverHandle;

async function createWindow() {
  const { startServer } = require(path.join(__dirname, '..', 'server.js'));
  serverHandle = await startServer(3000);
  powerSaveBlocker.start('prevent-app-suspension');   // keep the PC awake and the app at full speed while it's open

  // Entire-screen capture without a picker, with system audio ("loopback") — no Chrome prompt needed.
  session.defaultSession.setDisplayMediaRequestHandler(async (_request, callback) => {
    try {
      const primary = screen.getPrimaryDisplay();
      const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } });
      const source = sources.find((s) => String(s.display_id) === String(primary.id)) || sources[0];
      callback({ video: source, audio: 'loopback' });
    } catch (e) {
      console.error('capture failed', e);
      callback({});
    }
  }, { useSystemPicker: false });

  win = new BrowserWindow({
    width: 480, height: 820, minWidth: 420, minHeight: 700,
    title: 'Cast to Fire TV', backgroundColor: '#111111', autoHideMenuBar: true,
    webPreferences: { backgroundThrottling: false, contextIsolation: true, nodeIntegration: false },
  });
  Menu.setApplicationMenu(null);
  win.loadURL(`http://localhost:${serverHandle.port}/`);
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) app.quit();
else {
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(createWindow).catch((e) => { console.error(e); app.quit(); });
  app.on('window-all-closed', () => { if (serverHandle) serverHandle.close(); app.quit(); });
}
