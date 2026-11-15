// Signaling + static file server + LAN discovery beacon.
// Standalone: `npm start`.  Embedded: require('./server').startServer() (used by the Electron app).
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const dgram = require('dgram');
const { execFile } = require('child_process');
const { WebSocketServer } = require('ws');

const PUBLIC = path.join(__dirname, 'public');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
const DISCOVERY_PORT = 41234;           // the Fire TV app listens here for "CASTAPP|<port>"

// Laptop volume/mute (Windows). Only the PC itself may call this, never other devices on the LAN.
function pcAudio(args, cb) {
  execFile('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'pc-audio.ps1'), ...args],
    { timeout: 15000, windowsHide: true }, (err, out) => {
      if (err) return cb(err);
      const [volume, muted] = out.trim().split(',').map(Number);
      cb(null, { volume, muted: !!muted });
    });
}

function isLocal(req) {
  return ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
}

function handlePcAudio(req, res) {
  const origin = req.headers.origin;
  if (!isLocal(req) || (origin && new URL(origin).host !== req.headers.host)) { res.writeHead(403); return res.end(); }
  const reply = (err, data) => {
    res.writeHead(err ? 500 : 200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(err ? { error: String(err.message || err) } : data));
  };
  if (req.method === 'GET') return pcAudio([], reply);
  let body = '';
  req.on('data', (c) => { body += c; if (body.length > 1000) req.destroy(); });
  req.on('end', () => {
    let m; try { m = JSON.parse(body); } catch { return reply(new Error('bad json')); }
    const args = [];
    if (Number.isFinite(m.volume)) args.push('-Volume', String(Math.round(Math.max(0, Math.min(100, m.volume)))));
    if (typeof m.muted === 'boolean') args.push('-Mute', m.muted ? '1' : '0');
    pcAudio(args, reply);
  });
}

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces()))
    for (const i of list) if (i.family === 'IPv4' && !i.internal) out.push(i);
  return out;
}

function broadcastAddr(ip, mask) {
  const a = ip.split('.').map(Number), m = mask.split('.').map(Number);
  return a.map((x, k) => (x & m[k]) | (~m[k] & 255)).join('.');
}

// Name shown on the TV's device list. Override with the CAST_NAME environment variable.
function deviceName() {
  return String(process.env.CAST_NAME || os.hostname() || 'PC').replace(/[|\r\n]/g, ' ').slice(0, 40);
}

function startBeacon(getPort) {
  const sock = dgram.createSocket('udp4');
  sock.on('error', () => {});
  sock.bind(0, () => {
    sock.setBroadcast(true);
    const tick = () => {
      const msg = Buffer.from(`CASTAPP|${getPort()}|${deviceName()}`);
      for (const i of lanAddresses()) sock.send(msg, DISCOVERY_PORT, broadcastAddr(i.address, i.netmask), () => {});
    };
    tick();
    sock.timer = setInterval(tick, 2000);
  });
  return sock;
}

function createHttp() {
  return http.createServer((req, res) => {
    const url = req.url.split('?')[0];
    if (url === '/api/pc-audio') return handlePcAudio(req, res);
    if (url === '/api/info') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ name: deviceName(), port: res.socket.localPort, addresses: lanAddresses().map((i) => i.address) }));
    }
    let p = url === '/' ? '/sender.html' : url;
    const file = path.join(PUBLIC, path.normalize(p).replace(/^(\.\.[/\\])+/, ''));
    if (!file.startsWith(PUBLIC)) { res.writeHead(403); return res.end(); }
    fs.readFile(file, (err, data) => {
      if (err) { res.writeHead(404); return res.end('Not found'); }
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
      res.end(data);
    });
  });
}

function attachSignaling(server) {
  const rooms = new Map();    // code -> { receiver, sender }
  const wss = new WebSocketServer({ server });
  wss.on('error', () => {});   // it re-emits the HTTP server's errors (e.g. port in use); startServer handles those
  const send = (ws, msg) => ws && ws.readyState === 1 && ws.send(JSON.stringify(msg));
  const newCode = () => {
    let c;
    do { c = String(Math.floor(100000 + Math.random() * 900000)); } while (rooms.has(c));
    return c;
  };
  const RELAY = ['offer', 'answer', 'candidate', 'bye', 'audio-output', 'audio-state', 'stats-visible'];

  wss.on('connection', (ws) => {
    ws.on('message', (raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'register') {               // receiver (TV)
        const code = newCode();
        const tvName = String(m.name || 'Fire TV').slice(0, 40);
        rooms.set(code, { receiver: ws, sender: null, name: tvName });
        ws.code = code; ws.role = 'receiver';
        send(ws, { type: 'registered', code, pcName: deviceName() });
      } else if (m.type === 'join') {            // sender (PC)
        const room = rooms.get(String(m.code));
        if (!room) return send(ws, { type: 'error', message: 'Invalid code' });
        room.sender = ws; ws.code = String(m.code); ws.role = 'sender';
        send(ws, { type: 'joined', name: room.name });
        send(room.receiver, { type: 'sender-joined' });
      } else if (RELAY.includes(m.type)) {
        const room = rooms.get(ws.code);
        if (!room) return;
        send(ws.role === 'sender' ? room.receiver : room.sender, m);
      }
    });
    ws.on('close', () => {
      const room = rooms.get(ws.code);
      if (!room) return;
      if (ws.role === 'receiver') { send(room.sender, { type: 'bye' }); rooms.delete(ws.code); }
      else { room.sender = null; send(room.receiver, { type: 'bye' }); }
    });
  });
  return wss;
}

// Starts the server on the first free port at/after `port`. Resolves { port, close }.
function startServer(port = Number(process.env.PORT) || 3000) {
  return new Promise((resolve, reject) => {
    const tryListen = (p, left) => {
      const server = createHttp();
      attachSignaling(server);
      server.once('error', (e) => {
        if (e.code === 'EADDRINUSE' && left > 0) tryListen(p + 1, left - 1); else reject(e);
      });
      server.listen(p, '0.0.0.0', () => {
        const beacon = startBeacon(() => p);
        resolve({ port: p, close: () => { clearInterval(beacon.timer); try { beacon.close(); } catch {} server.close(); } });
      });
    };
    tryListen(port, 9);
  });
}

module.exports = { startServer, lanAddresses };

if (require.main === module) {
  startServer().then(({ port }) => {
    console.log('\nScreencast server running.\n');
    console.log(`  On this PC, open (sender):  http://localhost:${port}/`);
    for (const i of lanAddresses()) console.log(`  On the Fire TV (receiver):  http://${i.address}:${port}/receiver.html`);
    console.log(`\n  This PC appears on the Fire TV app as "${deviceName()}" (UDP broadcast on port 41234).\n`);
  });
}
